package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositStore;
import mmm.granular.GranularLimits;
import mmm.granular.GranularParcelStore;
import mmm.granular.GranularPhaseTransferEngine;
import mmm.granular.SolidCompositionPool;
import mmm.granular.SolidCompositionRecord;
import mmm.granular.SolidLiquidReaction;
import mmm.thermal.CoherentPhaseStore;
import mmm.thermal.GranularEnergyColumn;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalStateFunction;
import mmm.units.Mass;
import mmm.units.SignedDyadic;

/** One prepared cell/solid/energy transaction for the complete same-cell saturation pass. */
final class GranularReactionStage {
  private final FluidDomain domain;
  private Candidate candidate;
  private Map<Integer, ArrayList<Integer>> activeByCell;
  private double mechanicalRoundingJoules;

  GranularReactionStage(FluidDomain domain) {
    this.domain = domain;
  }

  double mechanicalRoundingJoules() {
    return mechanicalRoundingJoules;
  }

  private record Entity(
      boolean deposit,
      int index,
      int cell,
      int material,
      int composition,
      long volume,
      long mass,
      long extent) {}

  private record Accessible(long total, Entity selected) {}

  FluidDomain.PhaseTransferStats step(int[] dirtyCells) {
    candidate = null;
    activeByCell = null;
    mechanicalRoundingJoules = 0;
    if (!domain.precipitationEnabled() || dirtyCells.length == 0)
      return new FluidDomain.PhaseTransferStats(0, 0, 0, 0);
    if (domain.granularState.parcels.count() == 0 && domain.granularState.deposits.groupCount() == 0) {
      boolean possible = false;
      for (var rule : domain.granularState.registry.reactions()) {
        boolean complete = true;
        for (String species : rule.aqueousStoichiometry().keySet())
          if (!domain.liquidCells.hasSpecies(domain.chemicalParcels.registry().speciesHandle(species))) {
            complete = false; break;
          }
        if (complete) { possible = true; break; }
      }
      if (!possible) return new FluidDomain.PhaseTransferStats(0, 0, 0, 0);
    }
    // A solid can dissolve without aqueous reactants; precipitation needs every term.
    // Filter before allocating any private solid, momentum or caloric candidate.
    var solidCells = new TreeSet<Integer>();
    for (int index = 0; index < domain.granularState.parcels.count(); index++)
      solidCells.add(domain.granularParcelCell(domain.granularState.parcels.parcel(index)));
    domain.granularState.deposits.forEachGroup((cell, porosity, material, composition, volume, mass) -> solidCells.add(cell));
    var participating = new TreeSet<Integer>();
    for (int cell : dirtyCells) {
      if (cell < 0 || cell >= domain.cellCount)
        throw new IllegalArgumentException("Reactive cell is outside the domain");
      if (solidCells.contains(cell)) { participating.add(cell); continue; }
      for (int support : LiquidCellInventory.connectedSupports(domain.geometry.cutCells.mask(cell))) {
        for (var rule : domain.granularState.registry.reactions())
          if (canPrecipitate(cell, support, rule)) { participating.add(cell); break; }
        if (participating.contains(cell)) break;
      }
    }
    if (participating.isEmpty()) return new FluidDomain.PhaseTransferStats(0, 0, 0, 0);
    domain.ensureGrid();
    int transfers = 0, precipitated = 0, dissolved = 0, evaluations = 0;
    long signedVolume = 0;
    for (int cell : participating) {
      for (int support : LiquidCellInventory.connectedSupports(domain.geometry.cutCells.mask(cell)))
        for (var rule : domain.granularState.registry.reactions()) {
          var accessible = solidCells.contains(cell) || candidate != null ? accessible(cell, support, rule) : null;
          if ((accessible == null || !rule.reversible()) && !canPrecipitate(cell, support, rule)) continue;
          if (evaluations++ == GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP) {
            for (int deferred : dirtyCells) domain.chemicalParcels.markDirtyCell(deferred);
            publish();
            return new FluidDomain.PhaseTransferStats(
                transfers, precipitated, dissolved, signedVolume);
          }
          var portions = new ArrayList<GranularPhaseTransferEngine.LiquidPortion>();
          int phase = domain.chemicalParcels.registry().phaseHandle(rule.liquidPhaseId());
          for (var stock : physicalStocks(cell))
            if (stock.key().supportMask() == support
                && stock.mixture() != null
                && domain
                        .chemicalParcels
                        .registry()
                        .phaseHandleForMaterial(
                            domain.materialRegistry.material(stock.key().material()).id())
                    == phase)
              portions.add(
                  new GranularPhaseTransferEngine.LiquidPortion(
                      stock.key(), stock.volume(), stock.massUnits(), stock.mixture()));
          if (portions.isEmpty()) continue;
          long existing = accessible == null ? 0 : accessible.total();
          long cap =
              Math.min(
                      rule.accessibleSurfaceSvqPerCellStep(),
                      domain
                          .granularState
                          .registry
                          .material(
                              domain.granularState.registry.materialHandle(rule.solidMaterialId()))
                          .maximumActiveParcelSvq())
                  / rule.solidVolumeSvqPerBatch()
                  * rule.solidExtentPerBatch();
          if (cap == 0) continue;
          var plan = domain.granularPhaseTransfer().plan(rule, portions, existing, cap);
          if (plan.changed()
              && !plan.precipitation()
              && -plan.signedExtent() > accessible.selected().extent())
            plan =
                domain
                    .granularPhaseTransfer()
                    .plan(rule, portions, existing, Math.min(cap, accessible.selected().extent()));
          if (!plan.changed()) {
            if (plan.retryRequired()) domain.chemicalParcels.markDirtyCell(cell);
            continue;
          }
          ensureCandidate();
          if (plan.precipitation()) precipitate(cell, support, rule, plan);
          else dissolve(cell, accessible.selected(), plan);
          candidate.applyLiquid(plan);
          candidate.changed.add(cell);
          transfers++;
          signedVolume = Math.addExact(signedVolume, plan.signedSolidSvq());
          if (plan.precipitation()) precipitated++;
          else dissolved++;
        }
    }
    publish();
    return new FluidDomain.PhaseTransferStats(transfers, precipitated, dissolved, signedVolume);
  }

  private boolean canPrecipitate(int cell, int support, SolidLiquidReaction rule) {
    var registry = domain.chemicalParcels.registry();
    int phase = registry.phaseHandle(rule.liquidPhaseId());
    var stocks = physicalStocks(cell);
    for (var term : rule.aqueousStoichiometry().entrySet()) {
      int species = registry.speciesHandle(term.getKey());
      long required = Math.multiplyExact(rule.solidExtentPerBatch(), term.getValue()), amount = 0;
      for (var stock : stocks) {
        if (stock.key().supportMask() != support || stock.mixture() == null
            || registry.phaseHandleForMaterial(domain.materialRegistry.material(stock.key().material()).id()) != phase) continue;
        amount = Math.addExact(amount, stock.mixture().amount(species));
      }
      if (amount < required) return false;
    }
    return true;
  }

  private List<LiquidCellInventory.Stock> physicalStocks(int cell) {
    return candidate == null
        ? domain.liquidCells.physicalStocksInCell(cell)
        : candidate.row(cell).values().stream().filter(stock -> stock.massUnits() > 0).toList();
  }

  private GranularParcelStore parcels() {
    return candidate == null ? domain.granularState.parcels : candidate.parcels;
  }

  private GranularDepositStore deposits() {
    return candidate == null ? domain.granularState.deposits : candidate.deposits;
  }

  private SolidCompositionPool compositions() {
    return candidate == null ? domain.granularState.compositions : candidate.compositions;
  }

  private void ensureCandidate() {
    if (candidate == null) candidate = new Candidate();
  }

  private List<Integer> active(int cell) {
    if (activeByCell == null) {
      activeByCell = new HashMap<>();
      for (int i = 0; i < parcels().count(); i++)
        activeByCell
            .computeIfAbsent(
                domain.granularParcelCell(parcels().parcel(i)), ignored -> new ArrayList<>())
            .add(i);
    }
    return activeByCell.getOrDefault(cell, new ArrayList<>());
  }

  private Accessible accessible(int cell, int support, SolidLiquidReaction rule) {
    int material = domain.granularState.registry.materialHandle(rule.solidMaterialId());
    long total = 0;
    Entity selected = null;
    for (int index : active(cell)) {
      var p = parcels().parcel(index);
      if (p.materialHandle() != material
          || CellRemapMicroTopology.positionSupport(
                  domain.geometry.cutCells.mask(cell),
                  domain.config.cellSize(),
                  p.x(),
                  p.y(),
                  p.z())
              != support) continue;
      long extent = extent(p.solidCompositionHandle(), p.solidVolumeSvq(), p.massUnits(), rule);
      if (extent > 0) {
        total = Math.addExact(total, extent);
        if (selected == null)
          selected =
              new Entity(
                  false,
                  index,
                  cell,
                  material,
                  p.solidCompositionHandle(),
                  p.solidVolumeSvq(),
                  p.massUnits(),
                  extent);
      }
    }
    // A cell bed has one connected pore volume; fixed disjoint micro components retain separate
    // liquids.
    var bed = deposits().dominantGroup(cell);
    if (bed != null
        && bed.materialHandle() == material
        && LiquidCellInventory.connectedSupports(domain.geometry.cutCells.mask(cell)).size() == 1) {
      long extent =
          extent(bed.solidCompositionHandle(), bed.solidVolumeSvq(), bed.massUnits(), rule);
      if (extent > 0) {
        total = Math.addExact(total, extent);
        if (selected == null)
          selected =
              new Entity(
                  true,
                  cell,
                  cell,
                  material,
                  bed.solidCompositionHandle(),
                  bed.solidVolumeSvq(),
                  bed.massUnits(),
                  extent);
      }
    }
    return selected == null ? null : new Accessible(total, selected);
  }

  private long extent(int handle, long volume, long mass, SolidLiquidReaction rule) {
    if (handle == 0 || !compositions().isLive(handle)) return 0;
    var composition = compositions().record(handle);
    if (composition.massResidualUnits() != 0
        || composition.massUnits(domain.chemicalParcels.registry()) != mass
        || composition.entryCount() != rule.solidComposition().size()) return 0;
    long result = -1;
    for (var term : rule.solidComposition().entrySet()) {
      long amount =
          composition.amount(domain.chemicalParcels.registry().speciesHandle(term.getKey()));
      if (amount <= 0 || amount % term.getValue() != 0) return 0;
      long value = amount / term.getValue();
      if (result < 0) result = value;
      else if (result != value) return 0;
    }
    return result > 0
            && result % rule.solidExtentPerBatch() == 0
            && result / rule.solidExtentPerBatch() * rule.solidVolumeSvqPerBatch() == volume
        ? result
        : 0;
  }

  private void precipitate(
      int cell, int support, SolidLiquidReaction rule, GranularPhaseTransferEngine.Plan plan) {
    int material = domain.granularState.registry.materialHandle(rule.solidMaterialId());
    long units = plan.signedSolidMassUnits();
    double mass = Mass.kilograms(units);
    double[] velocity = candidate.removeLiquidMass(cell, units);
    double oldY = (cell / domain.sizeX % domain.sizeY + .5) * domain.config.cellSize();
    for (int index : active(cell)) {
      var p = parcels().parcel(index);
      if (p.materialHandle() != material
          || p.solidCompositionHandle() == 0
          || !plan.transferredComposition()
              .proportionalTo(
                  compositions().record(p.solidCompositionHandle()),
                  plan.signedSolidSvq(),
                  p.solidVolumeSvq())) continue;
      long volume = Math.addExact(p.solidVolumeSvq(), plan.signedSolidSvq());
      if (volume > domain.granularState.registry.material(material).maximumActiveParcelSvq())
        continue;
      if (CellRemapMicroTopology.positionSupport(
              domain.geometry.cutCells.mask(cell), domain.config.cellSize(), p.x(), p.y(), p.z())
          != support) continue;
      long all = Math.addExact(p.massUnits(), units);
      double combined = Mass.kilograms(all);
      float x = (float) (p.x() + (cellCenter(cell, 0) - p.x()) * (mass / combined)),
          y = (float) (p.y() + (oldY - p.y()) * (mass / combined)),
          z = (float) (p.z() + (cellCenter(cell, 2) - p.z()) * (mass / combined));
      if (!domain.granularCollision.positionValid(x, y, z, volume)
          || !fitsSpheres(x, y, z, volume, index)) continue;
      var merged =
          new GranularParcelStore.Parcel(
              x,
              y,
              z,
              (float) ((p.mass() * p.velocityX() + mass * velocity[0]) / combined),
              (float) ((p.mass() * p.velocityY() + mass * velocity[1]) / combined),
              (float) ((p.mass() * p.velocityZ() + mass * velocity[2]) / combined),
              volume,
              all,
              material,
              p.solidCompositionHandle(),
              0,
              (byte) 0);
      merged = parcels().prepareOverwrite(index, merged);
      double relative =
          square(p.velocityX() - velocity[0])
              + square(p.velocityY() - velocity[1])
              + square(p.velocityZ() - velocity[2]);
      double slip = .5 * (p.mass() * (mass / combined)) * relative;
      double expectedY = p.y() + (oldY - p.y()) * (mass / combined);
      double gpe = 0;
      candidate.heat(cell, slip - gpe);
      mechanicalRoundingJoules +=
          kinetic(p)
              + .5 * mass * dot(velocity, velocity)
              - kinetic(merged)
              - slip
              - combined * domain.config.gravity() * (y - expectedY);
      compositions()
          .overwrite(
              p.solidCompositionHandle(),
              compositions()
                  .record(p.solidCompositionHandle())
                  .plus(plan.transferredComposition()));
      parcels().overwrite(index, merged);
      return;
    }
    var position = birthPosition(cell, support, plan.signedSolidSvq());
    if (position == null)
      throw new GranularPhaseTransferEngine.BlockedException(
          "Reactive liquid has no collision-resolved precipitate position");
    int composition = compositions().create(plan.transferredComposition());
    var parcel =
        new GranularParcelStore.Parcel(
            position[0],
            position[1],
            position[2],
            (float) velocity[0],
            (float) velocity[1],
            (float) velocity[2],
            plan.signedSolidSvq(),
            units,
            material,
            composition,
            0,
            (byte) 0);
    int index = parcels().add(parcel);
    activeByCell.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(index);
    candidate.activeEnergy.add(0.0);
    candidate.heat(cell, -mass * domain.config.gravity() * (parcel.y() - oldY));
    mechanicalRoundingJoules += .5 * mass * dot(velocity, velocity) - kinetic(parcel);
  }

  private void dissolve(int cell, Entity solid, GranularPhaseTransferEngine.Plan plan) {
    long removedVolume = -plan.signedSolidSvq(), removedMass = -plan.signedSolidMassUnits();
    double mass = Mass.kilograms(removedMass);
    boolean full = removedVolume == solid.volume();
    var original = compositions().record(solid.composition());
    var remaining = full ? null : subtract(original, plan.transferredComposition());
    double oldPotential = solid.deposit() ? bedPotential(cell) : 0;
    double[] velocity = {0, 0, 0};
    double sourceY;
    if (solid.deposit()) {
      sourceY =
          (cell / domain.sizeX % domain.sizeY
                  + Math.scalb((double) deposits().cellBulkVolumeSvq(cell), -25))
              * domain.config.cellSize();
      var taken =
          deposits()
              .takeCompositionAware(cell, solid.material(), solid.composition(), removedVolume);
      if (taken.massUnits() != removedMass)
        throw new IllegalStateException("Reactive bed mass differs from its exact stoichiometry");
    } else {
      var p = parcels().parcel(solid.index());
      velocity = new double[] {p.velocityX(), p.velocityY(), p.velocityZ()};
      sourceY = p.y();
      if (full) {
        int last = parcels().count() - 1,
            lastCell = domain.granularParcelCell(parcels().parcel(last));
        activeByCell.get(cell).remove(Integer.valueOf(solid.index()));
        if (last != solid.index()) {
          activeByCell.get(lastCell).remove(Integer.valueOf(last));
          activeByCell.get(lastCell).add(solid.index());
        }
        parcels().removeSwap(solid.index());
        double moved = candidate.activeEnergy.remove(last);
        if (last != solid.index()) candidate.activeEnergy.set(solid.index(), moved);
      } else
        parcels()
            .overwrite(
                solid.index(),
                new GranularParcelStore.Parcel(
                    p.x(),
                    p.y(),
                    p.z(),
                    p.velocityX(),
                    p.velocityY(),
                    p.velocityZ(),
                    p.solidVolumeSvq() - removedVolume,
                    p.massUnits() - removedMass,
                    p.materialHandle(),
                    p.solidCompositionHandle(),
                    p.contactAge(),
                    p.flags()));
    }
    if (full) compositions().release(solid.composition());
    else compositions().overwrite(solid.composition(), remaining);
    candidate.addLiquidMass(cell, removedMass, velocity);
    double addedPotential = mass * domain.config.gravity() * cellCenter(cell, 1);
    if (solid.deposit()) candidate.heat(cell, oldPotential - bedPotential(cell) - addedPotential);
    else candidate.heat(cell, mass * domain.config.gravity() * sourceY - addedPotential);
  }

  private double bedPotential(int cell) {
    double[] mass = {0};
    deposits().forEachGroupInCell(cell, (c, p, m, s, v, u) -> mass[0] += Mass.kilograms(u));
    return mass[0]
        * domain.config.gravity()
        * (cell / domain.sizeX % domain.sizeY
            + Math.scalb((double) deposits().cellBulkVolumeSvq(cell), -25))
        * domain.config.cellSize();
  }

  private SolidCompositionRecord subtract(
      SolidCompositionRecord source, SolidCompositionRecord removed) {
    var map = new TreeMap<String, Long>();
    for (int i = 0; i < source.entryCount(); i++) {
      long amount = Math.subtractExact(source.amountAt(i), removed.amount(source.speciesHandle(i)));
      if (amount < 0) throw new IllegalStateException("Reactive solid composition underflow");
      if (amount > 0)
        map.put(domain.chemicalParcels.registry().species(source.speciesHandle(i)).id(), amount);
    }
    return SolidCompositionRecord.fromStableAmounts(
        domain.chemicalParcels.registry(), map, source.massResidualUnits());
  }

  private float[] birthPosition(int cell, int support, long volume) {
    int x = cell % domain.sizeX,
        y = cell / domain.sizeX % domain.sizeY,
        z = cell / (domain.sizeX * domain.sizeY);
    float h = domain.config.cellSize();
    for (int slot = -1; slot < 8; slot++) {
      if (slot >= 0 && (support & 1 << slot) == 0) continue;
      float px = (x + (slot < 0 ? .5F : .25F + .5F * (slot & 1))) * h,
          py = (y + (slot < 0 ? .5F : .25F + .5F * (slot >> 1 & 1))) * h,
          pz = (z + (slot < 0 ? .5F : .25F + .5F * (slot >> 2 & 1))) * h;
      if (domain.granularCollision.positionValid(px, py, pz, volume)
          && CellRemapMicroTopology.positionSupport(
                  domain.geometry.cutCells.mask(cell), h, px, py, pz)
              == support
          && fitsSpheres(px, py, pz, volume, -1)) return new float[] {px, py, pz};
    }
    return null;
  }

  private boolean fitsSpheres(float x, float y, float z, long volume, int excluded) {
    int cell =
        domain.cellIndex(
            (int) (x / domain.config.cellSize()),
            (int) (y / domain.config.cellSize()),
            (int) (z / domain.config.cellSize()));
    int cx = cell % domain.sizeX,
        cy = cell / domain.sizeX % domain.sizeY,
        cz = cell / (domain.sizeX * domain.sizeY);
    float radius =
        mmm.granular.GranularMechanics.representativeRadius(volume, domain.config.cellSize());
    for (int iz = Math.max(0, cz - 1); iz <= Math.min(domain.sizeZ - 1, cz + 1); iz++)
      for (int iy = Math.max(0, cy - 1); iy <= Math.min(domain.sizeY - 1, cy + 1); iy++)
        for (int ix = Math.max(0, cx - 1); ix <= Math.min(domain.sizeX - 1, cx + 1); ix++)
          for (int index : active(domain.cellIndex(ix, iy, iz))) {
            if (index == excluded) continue;
            var p = parcels().parcel(index);
            if (GranularSpawnAdmission.interiorsOverlap(
                x,
                y,
                z,
                radius,
                p.x(),
                p.y(),
                p.z(),
                mmm.granular.GranularMechanics.representativeRadius(
                    p.solidVolumeSvq(), domain.config.cellSize()))) return false;
          }
    return true;
  }

  private double cellCenter(int cell, int axis) {
    return ((axis == 0
                ? cell % domain.sizeX
                : axis == 1
                    ? cell / domain.sizeX % domain.sizeY
                    : cell / (domain.sizeX * domain.sizeY))
            + .5)
        * domain.config.cellSize();
  }

  private static double square(double value) {
    return value * value;
  }

  private static double dot(double[] first, double[] second) {
    return first[0] * second[0] + first[1] * second[1] + first[2] * second[2];
  }

  private static double kinetic(GranularParcelStore.Parcel p) {
    return .5 * p.mass() * (square(p.velocityX()) + square(p.velocityY()) + square(p.velocityZ()));
  }

  private void publish() {
    if (candidate != null && !candidate.changed.isEmpty()) candidate.publish();
    candidate = null;
  }

  private final class Candidate {
    LiquidCellInventory liquid;
    final TreeMap<Integer, TreeMap<LiquidCellInventory.Key, LiquidCellInventory.Stock>>
        changedRows = new TreeMap<>();
    final GranularParcelStore parcels = domain.granularState.parcels.copy();
    final GranularDepositStore deposits = domain.granularState.deposits.copy();
    final SolidCompositionPool compositions = domain.granularState.compositions.copy();
    final long parcelRevision = domain.granularState.parcels.mutationRevision(),
        depositRevision = domain.granularState.deposits.mutationRevision(),
        compositionRevision = domain.granularState.compositions.mutationRevision();
    final TreeSet<Integer> changed = new TreeSet<>();
    final TreeMap<Integer, SignedDyadic> heatByCell = new TreeMap<>();
    final ArrayList<Double> activeEnergy = new ArrayList<>();
    final TreeMap<GranularEnergyColumn.DepositKey, Double> depositEnergy = new TreeMap<>();
    final ArrayList<CoherentPhaseStore.Group> coherent = new ArrayList<>();
    final Map<Integer, ArrayList<Integer>> coherentByCell = new HashMap<>();
    final double[][] momentum = {
      domain.mac.momentumX.clone(), domain.mac.momentumY.clone(), domain.mac.momentumZ.clone()
    };
    final double[][] faceMass = {
      domain.cellMacStage.massX.clone(),
      domain.cellMacStage.massY.clone(),
      domain.cellMacStage.massZ.clone()
    };

    Candidate() {
      if (domain.thermalState != null) {
        for (double u : domain.thermalState.granularColumn().copyActiveEnergy())
          activeEnergy.add(u);
        for (var item : domain.thermalState.granularColumn().depositSnapshot())
          depositEnergy.put(item.key(), item.internalEnergy());
        coherent.addAll(domain.thermalState.coherentPhases().snapshot());
        for (int i = 0; i < coherent.size(); i++)
          coherentByCell
              .computeIfAbsent(coherent.get(i).cell(), ignored -> new ArrayList<>())
              .add(i);
      } else for (int i = 0; i < parcels.count(); i++) activeEnergy.add(0.0);
    }

    void heat(int cell, double value) {
      if (value != 0) heatByCell.merge(cell, SignedDyadic.of(value), SignedDyadic::add);
    }

    TreeMap<LiquidCellInventory.Key, LiquidCellInventory.Stock> row(int cell) {
      return changedRows.computeIfAbsent(
          cell,
          key -> {
            var row = new TreeMap<LiquidCellInventory.Key, LiquidCellInventory.Stock>();
            for (var stock : domain.liquidCells.stocksInCell(cell)) row.put(stock.key(), stock);
            return row;
          });
    }

    void applyLiquid(GranularPhaseTransferEngine.Plan plan) {
      for (var update : plan.stockUpdates()) {
        var row = row(update.key().cell());
        var before = row.get(update.key());
        row.put(
            update.key(),
            new LiquidCellInventory.Stock(
                update.key(),
                update.volume(),
                update.massUnits(),
                update.mixture(),
                update.massUnits() == 0 ? 0 : before.internalEnergy(),
                before.indicator()));
      }
    }

    int face(int cell, int axis, int side) {
      int x = cell % domain.sizeX,
          y = cell / domain.sizeX % domain.sizeY,
          z = cell / (domain.sizeX * domain.sizeY);
      return axis == 0
          ? domain.xFaceIndex(x + side, y, z)
          : axis == 1 ? domain.yFaceIndex(x, y + side, z) : domain.zFaceIndex(x, y, z + side);
    }

    double[] removeLiquidMass(int cell, long units) {
      double mass = Mass.kilograms(units);
      var velocity = new double[3];
      double removedKinetic = 0;
      double[][] oldVelocity = new double[3][2];
      for (int axis = 0; axis < 3; axis++)
        for (int side = 0; side < 2; side++) {
          int face = face(cell, axis, side);
          double old = faceMass[axis][face],
              share = .5 * mass,
              v = old > 0 ? momentum[axis][face] / old : 0;
          if (share > old + 16 * Math.ulp(old))
            throw new IllegalStateException(
                "Reaction removed more face inertia than its liquid inventory");
          oldVelocity[axis][side] = v;
          double carried = share * v;
          momentum[axis][face] -= carried;
          faceMass[axis][face] = Math.max(0, old - share);
          velocity[axis] += .5 * v;
          removedKinetic += .5 * share * v * v;
        }
      double physical = 0;
      for (int axis = 0; axis < 3; axis++) {
        double first = oldVelocity[axis][0], second = oldVelocity[axis][1];
        physical += mass * .125 * square(first - second);
      }
      heat(cell, physical);
      mechanicalRoundingJoules += removedKinetic - .5 * mass * dot(velocity, velocity) - physical;
      return velocity;
    }

    double faceVelocity(int cell, int axis, int side) {
      int i = face(cell, axis, side);
      return faceMass[axis][i] > 0 ? momentum[axis][i] / faceMass[axis][i] : 0;
    }

    void addLiquidMass(int cell, long units, double[] velocity) {
      double mass = Mass.kilograms(units);
      for (int axis = 0; axis < 3; axis++)
        for (int side = 0; side < 2; side++) {
          int i = face(cell, axis, side);
          double old = faceMass[axis][i],
              added = .5 * mass,
              all = old + added,
              v = old > 0 ? momentum[axis][i] / old : 0;
          double loss = old > 0 ? .5 * (old * (added / all)) * square(v - velocity[axis]) : 0;
          momentum[axis][i] += added * velocity[axis];
          faceMass[axis][i] = all;
          heat(cell, loss);
        }
    }

    void publish() {
      var materialEdits = new ArrayList<LiquidCellInventory.Edit>();
      for (var row : changedRows.values())
        for (var stock : row.values())
          if (!stock.equals(domain.liquidCells.stock(stock.key())))
            materialEdits.add(new LiquidCellInventory.Edit(stock.key(), stock));
      liquid = domain.liquidCells.planEdits(materialEdits).future();
      ThermalDomainState.PhaseBatchEnergyPlan energyPlan = null;
      mmm.granular.GranularInventory.EnergyPlan solidEnergy = null;
      if (domain.thermalState != null) {
        int[] cells = changed.stream().mapToInt(Integer::intValue).toArray();
        var current =
            domain.thermalState.preparePhaseCandidateCells(cells, domain.thermalMarkerContext());
        var trials = new ArrayList<ThermalDomainState.PhaseCellTrial>();
        var ownersByCell = new TreeMap<Integer, List<EnergyOwner>>();
        for (int cell : cells) {
          var owners = energyOwners(cell);
          ownersByCell.put(cell, owners);
          var inventory = owners.stream().map(EnergyOwner::portion).toList();
          trials.add(
              domain.thermalState.trialOwnedCellEnergy(
                  current[cell], inventory, heatByCell.getOrDefault(cell, SignedDyadic.ZERO)));
        }
        SignedDyadic heat = SignedDyadic.ZERO;
        for (var value : heatByCell.values()) heat = heat.add(value);
        energyPlan =
            domain.thermalState.planOwnedBatchEnergy(trials, domain.thermalMarkerContext(), heat);
        var edits = new ArrayList<LiquidCellInventory.Edit>();
        for (var cell : ownersByCell.entrySet()) {
          double[] energies = energyPlan.ownerEnergies(cell.getKey());
          for (int i = 0; i < energies.length; i++)
            cell.getValue().get(i).assign(energies[i], edits);
        }
        liquid.planEdits(edits).commit();
        var futureDeposits = new ArrayList<GranularEnergyColumn.DepositEnergy>();
        deposits.forEachGroup(
            (cell, p, m, c, v, mass) -> {
              var key = new GranularEnergyColumn.DepositKey(cell, m, c);
              futureDeposits.add(
                  new GranularEnergyColumn.DepositEnergy(key, depositEnergy.get(key)));
            });
        solidEnergy =
            domain.thermalState.planGranularEnergyReplacement(
                activeEnergy.stream().mapToDouble(Double::doubleValue).toArray(),
                futureDeposits,
                coherent);
      }
      var coupling =
          new GranularCouplingFields(
              domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
      var fixed = new long[domain.cellCount];
      for (int cell = 0; cell < fixed.length; cell++)
        fixed[cell] = domain.granularCoupling.fixedCellSvq(cell);
      coupling.setFixedCellSvq(fixed);
      coupling.rebuild(parcels, deposits);
      long[] occupied = coupling.copySolidSvq();
      var volume = new SignedDyadic[domain.cellCount];
      var capacity = new SignedDyadic[domain.cellCount];
      var displacement = new SignedDyadic[domain.cellCount];
      boolean displace = false;
      for (int cell = 0; cell < volume.length; cell++) {
        volume[cell] = liquid.exactCellVolume(cell);
        capacity[cell] =
            SignedDyadic.of(domain.geometry.cutCells.openCellVolume(cell))
                .subtract(SignedDyadic.of(occupied[cell], -24));
        if (capacity[cell].signum() < 0)
          throw new IllegalStateException("Reactive solid exceeds geometric capacity at " + cell);
        displacement[cell] =
            volume[cell].compareTo(capacity[cell]) > 0
                ? volume[cell].subtract(capacity[cell])
                : SignedDyadic.ZERO;
        displace |= displacement[cell].signum() > 0 || CellRemapMicroTopology.exceedsCapacity(liquid,cell,domain.geometry.cutCells.mask(cell),capacity[cell]);
      }
      SignedDyadic advectionRoundoff = SignedDyadic.ZERO;
      SignedDyadic advectionHeat = SignedDyadic.ZERO;
      MacMomentumState preparedMomentum;
      GasVolumeAllocation transportedGas=null;
      if (displace) {
        var flux = new ConservativeLiquidFlux(domain);
        flux.prepareDisplacement(occupied,liquid);
        if (domain.cellLiquidVolumeTransport == null)
          domain.cellLiquidVolumeTransport = new CellLiquidVolumeTransport(domain);
        var transport = domain.cellLiquidVolumeTransport.prepareFlux(flux, 0, liquid);
        var momentumTransfer =
            new CellMomentumTransport(domain)
                .prepare(transport, new float[][]{velocities(0), velocities(1), velocities(2)});
        preparedMomentum = momentumTransfer.momentum();
        var transportedLiquid = transport.inventory().withMechanicalHeat(momentumTransfer.heatByCell());
        advectionRoundoff = transportedLiquid.energyRoundoff();
        advectionHeat = transportedLiquid.mechanicalHeat();
        transportedGas=transport.gasAllocation();
        transportedLiquid.commit();
      } else
        preparedMomentum =
            new MacMomentumState(
                domain.sizeX, domain.sizeY, domain.sizeZ, momentum[0], momentum[1], momentum[2]);
      var edits = new ArrayList<LiquidCellInventory.Edit>();
      for (int cell = 0; cell < domain.cellCount; cell++)
        if (domain.liquidCells.stocksInCell(cell) != liquid.stocksInCell(cell)) {
          var keys = new TreeSet<LiquidCellInventory.Key>();
          for (var stock : domain.liquidCells.stocksInCell(cell)) keys.add(stock.key());
          for (var stock : liquid.stocksInCell(cell)) keys.add(stock.key());
          for (var key : keys)
            if (!java.util.Objects.equals(domain.liquidCells.stock(key), liquid.stock(key)))
              edits.add(new LiquidCellInventory.Edit(key, liquid.stock(key)));
        }
      var stockPlan = domain.liquidCells.planEdits(edits);
      var gas =
          domain.gasTopologyCoordinator.planAfterLiquidCells(stockPlan.future(), occupied,
              transportedGas==null?GasVolumeAllocation.prepare(domain,stockPlan.future(),occupied,null):new GasVolumeAllocation(domain,stockPlan.future(),transportedGas.futureOccupancy(),transportedGas.shares(),transportedGas.preparedPhase(),transportedGas.preparedGeometry()));
      if(domain.thermalState!=null)domain.thermalState.auditLedger().withMechanicalWork(advectionHeat).withRoundoff(advectionRoundoff).expectedInventory();
      stockPlan.requireCurrent();
      domain.granularState.parcels.requirePrepared(parcels, parcelRevision);
      domain.granularState.deposits.requirePrepared(deposits, depositRevision);
      domain.granularState.compositions.requirePrepared(compositions, compositionRevision);
      if (energyPlan != null) {
        domain.thermalState.requirePhaseBatchEnergyCurrent(energyPlan);
        solidEnergy.requireCurrent();
      }
      if (gas != null) domain.gasTopologyCoordinator.requireBeforeCarrierCommit(gas);
      stockPlan.commit();
      domain.granularState.parcels.adoptPrepared(parcels, parcelRevision);
      domain.granularState.deposits.adoptPrepared(deposits, depositRevision);
      domain.granularState.compositions.adoptPrepared(compositions, compositionRevision);
      domain.mac.restoreMomentum(preparedMomentum);
      domain.granularCoupling.copyFrom(coupling);
      if (energyPlan != null) {
        solidEnergy.commit();
        domain.thermalState.commitOwnedBatchEnergy(energyPlan);
      }
      domain.markCellInventoryChanged();
      for (int cell : changed) {
        domain.chemicalParcels.markDirtyCell(cell);
        domain.granularDepositDynamics().disturbCell(cell);
      }
      if (gas != null) domain.gasTopologyCoordinator.commit(gas);
      if(domain.thermalState!=null)domain.thermalState.restoreAuditLedger(domain.thermalState.auditLedger()
          .withMechanicalWork(advectionHeat).withRoundoff(advectionRoundoff));
    }

    float[] velocities(int axis) {
      var values = new float[momentum[axis].length];
      for (int i = 0; i < values.length; i++)
        values[i] = faceMass[axis][i] > 0 ? (float) (momentum[axis][i] / faceMass[axis][i]) : 0;
      return values;
    }

    List<EnergyOwner> energyOwners(int cell) {
      var result = new ArrayList<EnergyOwner>();
      for (var stock : liquid.physicalStocksInCell(cell))
        result.add(
            new EnergyOwner(
                0,
                stock.key(),
                -1,
                null,
                null,
                new ThermalStateFunction.InventoryPortion(
                    domain.thermalState.thermalMaterialHandleForFluid(stock.key().material()),
                    stock.massKilograms(),
                    stock.mixture() == null
                        ? Map.of()
                        : stock.mixture().stableAmounts(domain.chemicalParcels.registry()))));
      for (int i : active(cell)) {
        var p = parcels.parcel(i);
        result.add(
            new EnergyOwner(
                1,
                null,
                i,
                null,
                null,
                solidPortion(p.materialHandle(), p.massUnits(), p.solidCompositionHandle())));
      }
      deposits.forEachGroupInCell(
          cell,
          (c, porosity, material, composition, volume, mass) -> {
            var key = new GranularEnergyColumn.DepositKey(c, material, composition);
            result.add(
                new EnergyOwner(2, null, -1, key, null, solidPortion(material, mass, composition)));
          });
      for (int i : coherentByCell.getOrDefault(cell, new ArrayList<>())) {
        var group = coherent.get(i);
        result.add(
            new EnergyOwner(
                3,
                null,
                i,
                null,
                group,
                new ThermalStateFunction.InventoryPortion(
                    domain.thermalState.registry().materialHandle(group.phaseId()),
                    group.mass(),
                    group.composition() == null ? Map.of() : group.composition().stableAmounts())));
      }
      return result;
    }

    ThermalStateFunction.InventoryPortion solidPortion(int material, long mass, int composition) {
      int handle =
          domain
              .thermalState
              .registry()
              .materialHandle(
                  ThermalMaterialDefinition.CarrierKind.GRANULAR,
                  domain.granularState.registry.material(material).id());
      return new ThermalStateFunction.InventoryPortion(
          handle,
          Mass.kilograms(mass),
          composition == 0
              ? Map.of()
              : compositions.record(composition).stableAmounts(domain.chemicalParcels.registry()));
    }

    private final class EnergyOwner {
      final int kind, index;
      final LiquidCellInventory.Key key;
      final GranularEnergyColumn.DepositKey deposit;
      final CoherentPhaseStore.Group coherent;
      final ThermalStateFunction.InventoryPortion portion;

      EnergyOwner(
          int kind,
          LiquidCellInventory.Key key,
          int index,
          GranularEnergyColumn.DepositKey deposit,
          CoherentPhaseStore.Group coherent,
          ThermalStateFunction.InventoryPortion portion) {
        this.kind = kind;
        this.key = key;
        this.index = index;
        this.deposit = deposit;
        this.coherent = coherent;
        this.portion = portion;
      }

      ThermalStateFunction.InventoryPortion portion() {
        return portion;
      }

      void assign(double energy, List<LiquidCellInventory.Edit> edits) {
        switch (kind) {
          case 0 -> {
            var stock = candidate.liquid.stock(key);
            edits.add(
                new LiquidCellInventory.Edit(
                    key,
                    new LiquidCellInventory.Stock(
                        key,
                        stock.volume(),
                        stock.massUnits(),
                        stock.mixture(),
                        energy,
                        stock.indicator())));
          }
          case 1 -> candidate.activeEnergy.set(index, energy);
          case 2 -> candidate.depositEnergy.put(deposit, energy);
          case 3 ->
              candidate.coherent.set(
                  index,
                  new CoherentPhaseStore.Group(
                      coherent.cell(),
                      coherent.phaseId(),
                      coherent.occupiedVolumeSvq(),
                      coherent.massUnits(),
                      coherent.composition(),
                      energy));
          default -> throw new IllegalStateException("Unknown reactive heat owner");
        }
      }
    }
  }
}
