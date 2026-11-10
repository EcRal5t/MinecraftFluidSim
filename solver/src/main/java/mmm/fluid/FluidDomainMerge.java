package mmm.fluid;

import java.util.ArrayList;
import java.util.List;
import mmm.fluid.air.GasAuditLedger;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.SpatialGasState;
import mmm.spatial.SpatialGrid;
import mmm.thermal.ThermalAuditLedger;

/** Conserved inventories prepared for merging independently committed regions. */
public final class FluidDomainMerge {
    private FluidDomainMerge() { }

    public record Source(FluidDomainState state, int offsetX, int offsetY, int offsetZ, boolean[] retainedCells) {
        public Source {
            java.util.Objects.requireNonNull(state);
            if (retainedCells != null && retainedCells.length != state.grid().cellCount())
                throw new IllegalArgumentException("Retained cells differ from source grid");
            retainedCells = retainedCells == null ? null : retainedCells.clone();
        }
        @Override public boolean[] retainedCells() { return retainedCells == null ? null : retainedCells.clone(); }
        boolean retains(int cell) { return retainedCells == null || retainedCells[cell]; }
        SpatialGridRemap map(SpatialGrid target) {
            return new SpatialGridRemap(state.grid(), target, offsetX, offsetY, offsetZ, retainedCells);
        }
    }

    public static FluidDomainState prepare(List<Source> sources, SpatialGrid target, FixedGeometrySnapshot sampledGeometry,
            SpatialGasState gas, ThermalAuditLedger audit,
            double[] sampledBoundaryTemperatures) {
        if (sources.isEmpty() || !sampledGeometry.grid().equals(target))
            throw new IllegalArgumentException("Invalid merge inputs");
        var first = sources.getFirst().state();
        var palette = new ArrayList<FluidMaterial>();
        for (var input : sources) for (var material : input.state().materialPalette()) {
            int index = -1;
            for (int i = 0; i < palette.size(); i++) if (palette.get(i).id().equals(material.id())) index = i;
            if (index < 0) palette.add(material);
            else if (!palette.get(index).equals(material)) throw new IllegalArgumentException("Material definitions differ");
        }
        var rows = new ArrayList<List<mmm.inventory.LiquidCellInventory.Stock>>();
        for (int i = 0; i < target.cellCount(); i++) rows.add(new ArrayList<>());
        var masks = sampledGeometry.copyMasks(); var occupied = new boolean[target.cellCount()];
        var micro = new double[target.cellCount() * 8];
        double[][] momentum = {new double[target.faceCount(0)], new double[target.faceCount(1)], new double[target.faceCount(2)]};
        var screens = new java.util.TreeMap<Integer, ScreenFace>();
        for (var screen : sampledGeometry.screens()) screens.put(screen.face(), screen);
        var bodies = new java.util.TreeMap<java.util.UUID, mmm.world.MovingBodyFluidCoupling.Request>();
        var dirty = new java.util.TreeSet<Integer>();
        var pendingChemical = new ArrayList<mmm.chemistry.RegionChemistry.MassTransferPending>();
        var pendingPhase = new java.util.TreeSet<Integer>();
        var coherent = new ArrayList<mmm.thermal.CoherentPhaseStore.Group>();
        var chemicalSource = mmm.chemistry.ChemicalTransferLedger.EMPTY;
        var chemicalSink = mmm.chemistry.ChemicalTransferLedger.EMPTY;
        double[] boundary = sampledBoundaryTemperatures == null ? null : sampledBoundaryTemperatures.clone();
        float[] pressure = first.exportCondensedPhasePressure() == null ? null : new float[target.cellCount()];
        for (int n = 0; n < sources.size(); n++) {
            var input = sources.get(n); var state = input.state(); var map = input.map(target);
            if (!state.config().equals(first.config()) || state.sealedDomainBoundary() != first.sealedDomainBoundary()
                    || !state.definitions().material().fingerprint().equals(first.definitions().material().fingerprint())
                    || !state.definitions().chemistry().fingerprint().equals(first.definitions().chemistry().fingerprint())
                    || !state.definitions().gas().fingerprint().equals(first.definitions().gas().fingerprint())
                    || !state.definitions().granular().fingerprint().equals(first.definitions().granular().fingerprint())
                    || !state.definitions().thermal().fingerprint().equals(first.definitions().thermal().fingerprint())
                    || (state.chemicalRegistry() == null) != (first.chemicalRegistry() == null)
                    || (state.granular() == null) != (first.granular() == null)
                    || (state.thermalOwner() == null) != (first.thermalOwner() == null)
                    || state.carrierKind() != first.carrierKind()
                    || state.hasGas() != first.hasGas() || state.precipitationEnabled() != first.precipitationEnabled()
                    || state.maximumThermalTransportSubsteps() != first.maximumThermalTransportSubsteps())
                throw new IllegalArgumentException("Source physics configurations differ");
            int[] material = new int[state.materialPalette().size()];
            for (int i = 0; i < material.length; i++) material[i] = palette.indexOf(state.materialPalette().get(i));
            var oldMicro = state.phaseOccupancy().copyLiquidMicroVolumes();
            var oldMasks = state.copyCollisionMasks(); var oldBoundary = state.copyThermalBoundaryTemperaturesK();
            var oldPressure = state.exportCondensedPhasePressure();
            if (oldBoundary != null && boundary == null || (oldPressure == null) != (pressure == null))
                throw new IllegalArgumentException("Thermal fields differ");
            if (state.thermalOwner() != null && (state.thermalOwner().copyGasNodeEnergy().length != 0
                    || state.thermalOwner().insertionTemperatureK() != first.thermalOwner().insertionTemperatureK()))
                throw new IllegalArgumentException("Thermal continuation differs");
            if (oldPressure != null && oldPressure.pressureReferencePa() != first.exportCondensedPhasePressure().pressureReferencePa())
                throw new IllegalArgumentException("Pressure references differ");
            var values = oldPressure == null ? null : oldPressure.copyRelativeGaugePressurePa();
            for (int cell = 0; cell < state.grid().cellCount(); cell++) {
                if (!input.retains(cell)) {
                    if (!state.liquid().rows().get(cell).isEmpty()) throw new IllegalArgumentException("Former padding contains liquid");
                    for (int k = 0; k < 8; k++) if (oldMicro[cell * 8 + k] != 0) throw new IllegalArgumentException("Former padding contains occupancy");
                    continue;
                }
                int next = map.cell(cell);
                if (occupied[next]) throw new IllegalArgumentException("Source cells overlap");
                if (state.hasGas() && (state.spatialGas() == null || gas == null || !target.equals(gas.grid())
                        || !state.spatialGas().cells().get(cell).equals(gas.cells().get(next))
                        || Double.doubleToLongBits(state.spatialGas().energyAt(cell)) != Double.doubleToLongBits(gas.energyAt(next))))
                    throw new IllegalArgumentException("Merge changed source gas inventory");
                occupied[next] = true; masks[next] = oldMasks[cell];
                System.arraycopy(oldMicro, cell * 8, micro, next * 8, 8);
                if (oldBoundary != null) System.arraycopy(oldBoundary, cell * 6, boundary, next * 6, 6);
                if (pressure != null) pressure[next] = values[cell];
                for (var stock : state.liquid().rows().get(cell)) {
                    var key = stock.key();
                    rows.get(next).add(new mmm.inventory.LiquidCellInventory.Stock(
                            new mmm.inventory.LiquidCellInventory.Key(next, material.length == 0 ? key.material() : material[key.material()], key.supportMask()),
                            stock.volume(), stock.massUnits(), stock.mixture(), stock.internalEnergy()));
                }
            }
            double[][] oldMomentum = {state.momentum().copyX(), state.momentum().copyY(), state.momentum().copyZ()};
            for (int axis = 0; axis < 3; axis++) for (int i = 0; i < oldMomentum[axis].length; i++) {
                int face = state.grid().faceOffset(axis) + i, left = state.grid().faceLeft(face), right = state.grid().faceRight(face);
                if (left >= 0 && input.retains(left) || right >= 0 && input.retains(right))
                    momentum[axis][map.face(face) - target.faceOffset(axis)] += oldMomentum[axis][i];
                else if (oldMomentum[axis][i] != 0) throw new IllegalArgumentException("Former padding contains momentum");
            }
            for (var screen : state.fixedGeometry().screens()) {
                int left = state.grid().faceLeft(screen.face()), right = state.grid().faceRight(screen.face());
                if (!(left >= 0 && input.retains(left) || right >= 0 && input.retains(right))) continue;
                int face = map.face(screen.face());
                if (face < 0) continue;
                var moved = new ScreenFace(face, screen.poreDiameter(), screen.openFraction());
                var prior = screens.put(face, moved);
                if (prior != null && !prior.equals(moved)) throw new IllegalArgumentException("Screen definitions differ");
            }
            for (var body : map.bodies(state.entityBodyState(), state.config().cellSize()).bodies()) {
                var previous = bodies.putIfAbsent(body.bodyId(), body);
                if (previous != null && !previous.equals(body))
                    throw new IllegalArgumentException("Shared entity body states differ");
            }
            chemicalSource = chemicalSource.plus(state.chemicalSource()); chemicalSink = chemicalSink.plus(state.chemicalSink());
            if (state.copyChemicalDirtyCells() != null) for (int cell : state.copyChemicalDirtyCells())
                if (input.retains(cell)) dirty.add(map.cell(cell));
            for (var p : state.chemicalMassTransferPending()) {
                if (!input.retains(p.cell())) throw new IllegalArgumentException("Former padding contains pending chemistry");
                pendingChemical.add(new mmm.chemistry.RegionChemistry.MassTransferPending(
                        map.cell(p.cell()), material[p.material()], p.supportMask(), p.definitionId(), p.pendingSeconds(), p.residualMoles()));
            }
            if (state.pendingPhaseMotion() != null) for (var p : state.pendingPhaseMotion().entries()) {
                if (!input.retains(p.cell())) throw new IllegalArgumentException("Former padding contains pending phase motion");
                pendingPhase.add(map.cell(p.cell()));
            }
            if (state.thermalOwner() != null) for (var p : state.thermalOwner().coherentGroups()) {
                if (!input.retains(p.cell())) throw new IllegalArgumentException("Former padding contains coherent inventory");
                coherent.add(new mmm.thermal.CoherentPhaseStore.Group(map.cell(p.cell()), p.phaseId(), p.occupiedVolumeSvq(), p.massUnits(), p.composition(), p.internalEnergy()));
            }
        }
        if (!first.hasGas() && gas != null) throw new IllegalArgumentException("Unexpected gas inventory");
        if (first.hasGas()) {
            var newCells = new ArrayList<>(gas.cells()); var newEnergy = gas.internalEnergy();
            for (int cell = 0; cell < occupied.length; cell++) if (occupied[cell]) {
                newCells.set(cell, GasInventory.EMPTY); newEnergy[cell] = 0;
            }
            var expected = gas(sources, target, newCells, newEnergy);
            if (!expected.externalSource().equals(gas.externalSource()) || !expected.escapedSink().equals(gas.escapedSink())
                    || !expected.phaseSource().equals(gas.phaseSource()) || !expected.phaseSink().equals(gas.phaseSink()))
                throw new IllegalArgumentException("Merge changed gas audit");
            if (first.thermalOwner() != null) {
                var previous = thermalAudit(sources);
                if (audit == null || !audit.initialInventory().equals(previous.initialInventory())
                        || !audit.externalSink().equals(previous.externalSink())
                        || !audit.mechanicalWork().equals(previous.mechanicalWork())
                        || !audit.finiteDeviceExchange().equals(previous.finiteDeviceExchange())
                        || !audit.externalSource().subtract(previous.externalSource())
                                .add(audit.roundoffResidual().subtract(previous.roundoffResidual()))
                                .equals(mmm.units.SignedDyadic.sum(newEnergy)))
                    throw new IllegalArgumentException("Merge changed thermal audit");
            }
        }
        pendingChemical.sort(null);
        coherent.sort(java.util.Comparator.comparingInt(mmm.thermal.CoherentPhaseStore.Group::cell).thenComparing(mmm.thermal.CoherentPhaseStore.Group::phaseId));
        var grains = first.granular() == null ? null : granular(sources, target);
        mmm.granular.GranularDepositDynamics.State dynamics = grains == null ? null
                : new mmm.granular.GranularDepositDynamics.State(0, new int[target.columnCount()],
                        java.util.stream.IntStream.range(0, target.columnCount()).toArray());
        mmm.thermal.ThermalOwnerSnapshot thermal = first.thermalOwner() == null ? null
                : new mmm.thermal.ThermalOwnerSnapshot(first.thermalOwner().registryFingerprint(), first.thermalOwner().insertionTemperatureK(),
                        grains == null ? new double[0] : grains.activeEnergy(), grains == null ? new double[0] : grains.depositEnergy(),
                        new double[0], coherent, audit);
        var phase = first.pendingPhaseMotion() == null ? null : new PendingPhaseMotionState(target.cellCount(),
                pendingPhase.stream().map(PendingPhaseMotionState.Entry::new).toList());
        var pressureField = pressure == null ? null : new CondensedPhasePressureField(first.exportCondensedPhasePressure().pressureReferencePa(), pressure);
        return new FluidDomainState(target, first.config(), first.definitions(), first.sealedDomainBoundary(),
                new mmm.inventory.LiquidCellInventory.Snapshot(rows), new mmm.inventory.PhaseOccupancyState(micro),
                new MacMomentumState(target, momentum[0], momentum[1], momentum[2]),
                FixedGeometrySnapshot.fromMasks(target, masks, sampledGeometry.copyExteriorMasks(), List.copyOf(screens.values())), new EntityBodyState(List.copyOf(bodies.values())), palette,
                first.chemicalRegistry(), chemicalSource, chemicalSink, first.chemicalRegistry() == null ? null : dirty.stream().mapToInt(Integer::intValue).toArray(),
                pendingChemical, first.granularRegistry(), grains == null ? null : grains.state(), dynamics,
                first.precipitationEnabled(), first.gasRegistry(), FluidDomainState.GasContinuation.spatial(gas), first.thermalRegistry(), thermal,
                first.maximumThermalTransportSubsteps(), phase, boundary, pressureField, first.materialDefinitions());
    }

    /** Adds source gas ledgers and maps their cell inventories over caller-sampled new cells. */
    public static SpatialGasState gas(List<Source> sources, SpatialGrid target,
            List<GasInventory> sampledCells, double[] sampledEnergy) {
        if (sources.isEmpty() || sampledCells.size() != target.cellCount() || sampledEnergy.length != target.cellCount())
            throw new IllegalArgumentException("Invalid gas merge inputs");
        var first = sources.getFirst().state().spatialGas();
        if (first == null) throw new IllegalArgumentException("Merge requires spatial gas");
        var cells = new ArrayList<>(sampledCells);
        var energy = sampledEnergy.clone();
        var occupied = new boolean[target.cellCount()];
        var source = GasAuditLedger.EMPTY; var sink = GasAuditLedger.EMPTY;
        var phaseSource = GasAuditLedger.EMPTY; var phaseSink = GasAuditLedger.EMPTY;
        for (var input : sources) {
            var old = input.state().spatialGas();
            if (old == null || !old.registryFingerprint().equals(first.registryFingerprint())
                    || !old.chemicalRegistryFingerprint().equals(first.chemicalRegistryFingerprint()))
                throw new IllegalArgumentException("Gas registry identity differs");
            var map = input.map(target);
            source = source.add(old.externalSource()); sink = sink.add(old.escapedSink());
            phaseSource = phaseSource.add(old.phaseSource()); phaseSink = phaseSink.add(old.phaseSink());
            for (int cell = 0; cell < old.cellCount(); cell++) {
                if (!input.retains(cell)) {
                    if (!old.cells().get(cell).isEmpty() || old.energyAt(cell) != 0)
                        throw new IllegalArgumentException("Former padding contains gas");
                    continue;
                }
                int mapped = map.cell(cell);
                if (occupied[mapped] || !cells.get(mapped).isEmpty() || energy[mapped] != 0)
                    throw new IllegalArgumentException("Gas cells overlap");
                occupied[mapped] = true; cells.set(mapped, old.cells().get(cell)); energy[mapped] = old.energyAt(cell);
            }
        }
        for (int cell = 0; cell < cells.size(); cell++) if (!occupied[cell]) source = source.add(cells.get(cell));
        return new SpatialGasState(target, first.registryFingerprint(), first.chemicalRegistryFingerprint(), cells,
                energy, source, sink, phaseSource, phaseSink);
    }

    public static ThermalAuditLedger thermalAudit(List<Source> sources) {
        ThermalAuditLedger result = null;
        for (var source : sources) {
            var owner = source.state().thermalOwner();
            if (owner == null) throw new IllegalArgumentException("Missing thermal state");
            var next = owner.audit();
            result = result == null ? next : new ThermalAuditLedger(
                    result.initialInventory().add(next.initialInventory()), result.externalSource().add(next.externalSource()),
                    result.externalSink().add(next.externalSink()), result.mechanicalWork().add(next.mechanicalWork()),
                    result.finiteDeviceExchange().add(next.finiteDeviceExchange()), result.roundoffResidual().add(next.roundoffResidual()));
        }
        return java.util.Objects.requireNonNull(result, "No merge sources");
    }

    /** Merges the stable granular tables and keeps deposit energy paired with its reordered group. */
    public static GranularMerge granular(List<Source> sources, SpatialGrid target) {
        if (sources.isEmpty() || sources.getFirst().state().granular() == null)
            throw new IllegalArgumentException("Granular sources are missing");
        var first = sources.getFirst().state().granular();
        var materials = new java.util.TreeSet<String>();
        var compositions = new java.util.TreeSet<mmm.granular.GranularStateSnapshot.StableComposition>((a, b) -> {
            int order = Integer.compare(a.components().size(), b.components().size());
            for (int i = 0; order == 0 && i < a.components().size(); i++) {
                var x = a.components().get(i); var y = b.components().get(i);
                order = x.speciesId().compareTo(y.speciesId());
                if (order == 0) order = Long.compare(x.amountQuanta(), y.amountQuanta());
            }
            return order == 0 ? Long.compareUnsigned(a.massResidualUnits(), b.massResidualUnits()) : order;
        });
        for (var source : sources) {
            var value = source.state().granular();
            if (value == null || !value.registryFingerprint().equals(first.registryFingerprint())
                    || !value.chemicalRegistryFingerprint().equals(first.chemicalRegistryFingerprint()))
                throw new IllegalArgumentException("Granular registry identity differs");
            materials.addAll(value.materialPalette()); compositions.addAll(value.solidCompositions());
        }
        var materialList = List.copyOf(materials); var compositionList = List.copyOf(compositions);
        var active = new ArrayList<mmm.granular.GranularStateSnapshot.ActiveParcel>();
        var activeEnergy = new ArrayList<Double>(); var deposits = new ArrayList<DepositEnergy>();
        var totals = new java.util.TreeMap<String, Long>();
        var sourceAudit = mmm.granular.GranularAuditLedger.EMPTY;
        var sinkAudit = mmm.granular.GranularAuditLedger.EMPTY;
        long volume = 0, mass = 0;
        for (var source : sources) {
            var value = source.state().granular(); var owner = source.state().thermalOwner();
            var map = source.map(target); var mapped = map.granular(value, source.state().config().cellSize());
            var moved = mapped.state();
            double[] ae = owner == null ? new double[moved.activeParcels().size()] : owner.copyActiveParcelEnergy();
            double[] de = owner == null ? new double[value.depositGroups().size()] : owner.copyDepositEnergy();
            if (ae.length != moved.activeParcels().size() || de.length != value.depositGroups().size())
                throw new IllegalArgumentException("Granular energy shape differs");
            int[] order = mapped.depositSourceOrder();
            for (int i = 0; i < moved.activeParcels().size(); i++) {
                var p = moved.activeParcels().get(i);
                int material = materialList.indexOf(value.materialPalette().get(p.materialPaletteIndex()));
                int composition = p.solidCompositionIndex() == 0 ? 0
                        : compositionList.indexOf(value.solidCompositions().get(p.solidCompositionIndex() - 1)) + 1;
                active.add(new mmm.granular.GranularStateSnapshot.ActiveParcel(p.x(), p.y(), p.z(), p.velocityX(), p.velocityY(), p.velocityZ(),
                        p.solidVolumeSvq(), p.massUnits(), material, composition, p.contactAge(), p.flags(), p.ceilMask()));
                activeEnergy.add(ae[i]);
            }
            for (int i = 0; i < moved.depositGroups().size(); i++) {
                var p = moved.depositGroups().get(i);
                if (!source.retains(value.depositGroups().get(order[i]).cell()))
                    throw new IllegalArgumentException("Former padding contains granular inventory");
                int material = materialList.indexOf(value.materialPalette().get(p.materialPaletteIndex()));
                int composition = p.solidCompositionIndex() == 0 ? 0
                        : compositionList.indexOf(value.solidCompositions().get(p.solidCompositionIndex() - 1)) + 1;
                deposits.add(new DepositEnergy(new mmm.granular.GranularStateSnapshot.DepositGroup(p.cell(), p.porosityQ(),
                        material, composition, p.solidVolumeSvq(), p.massUnits()), de[order[i]]));
            }
            value.solidSpeciesTotals().forEach((id, amount) -> totals.merge(id, amount, Math::addExact));
            volume = Math.addExact(volume, value.totalSolidVolumeSvq()); mass = Math.addExact(mass, value.totalMassUnits());
            sourceAudit = sourceAudit.plus(value.externalSource()); sinkAudit = sinkAudit.plus(value.escapedSink());
        }
        deposits.sort(java.util.Comparator.comparingInt((DepositEnergy d) -> d.group().cell())
                .thenComparingInt(d -> d.group().materialPaletteIndex()).thenComparingInt(d -> d.group().solidCompositionIndex()));
        var state = new mmm.granular.GranularStateSnapshot(target, first.registryFingerprint(), first.chemicalRegistryFingerprint(),
                materialList, compositionList, totals, active, deposits.stream().map(DepositEnergy::group).toList(),
                volume, mass, sourceAudit, sinkAudit);
        return new GranularMerge(state, activeEnergy.stream().mapToDouble(Double::doubleValue).toArray(),
                deposits.stream().mapToDouble(DepositEnergy::energy).toArray());
    }

    private record DepositEnergy(mmm.granular.GranularStateSnapshot.DepositGroup group, double energy) { }
    public record GranularMerge(mmm.granular.GranularStateSnapshot state, double[] activeEnergy, double[] depositEnergy) {
        public GranularMerge { activeEnergy = activeEnergy.clone(); depositEnergy = depositEnergy.clone(); }
        @Override public double[] activeEnergy() { return activeEnergy.clone(); }
        @Override public double[] depositEnergy() { return depositEnergy.clone(); }
    }
}
