package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ExactRational;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.GranularPhaseTransferEngine;
import mmm.granular.SolidVolume;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalSpeciesOffset;
import mmm.units.Mass;
import mmm.units.SignedDyadic;
import mmm.units.SolverUnitScale;

/** Integer cell/solid transfers, local mechanical heat and atomic joint publication. */
public final class GranularReactionCandidateValidation {
  private GranularReactionCandidateValidation() {}

  public static void main(String[] args) {
    validate();
  }

  public static void validate() {
    integerStockAllocation();
    continuousPrecipitationAndRecovery();
    thermalMomentumTransfer();
    bedDissolution();
    thermalDissolutionAndEnergyLimitedProgress();
    dissolutionUsesSharedCapacityTransport();
    System.out.println(
        "Cell granular reactions passed: integer inventory, continuous phase transfer, local heat"
            + " and recovery");
  }

  private static void integerStockAllocation() {
    var chemistry = BuiltinChemicalDefinitions.REGISTRY;
    var rule = BuiltinGranularDefinitions.createRegistry().reactions().getFirst();
    var sources = new ArrayList<GranularPhaseTransferEngine.LiquidPortion>();
    for (int material = 0; material < 2; material++) {
      long mass = Mass.fromKilograms(material == 0 ? 2 : 3);
      var mixture =
          ChemicalMixtureRecord.fromStableAmounts(
              chemistry,
              Map.of(
                  BuiltinChemicalDefinitions.NA_PLUS,
                  8_320_000L,
                  BuiltinChemicalDefinitions.CL_MINUS,
                  8_320_000L),
              0);
      mixture = mixture.withMassResidualUnits(mass - mixture.speciesMassUnits(chemistry));
      sources.add(
          new GranularPhaseTransferEngine.LiquidPortion(
              new LiquidCellInventory.Key(0, material, 255), SignedDyadic.of(.25), mass, mixture));
    }
    var plan =
        new GranularPhaseTransferEngine(chemistry, SolverUnitScale.forCellSize(.5F))
            .plan(rule, sources, 0, 281 * 3L);
    require(plan.precipitation(), "Supersaturated cell stocks did not precipitate");
    long mass = plan.signedSolidMassUnits(), residual = 0;
    var volume = SignedDyadic.of(plan.signedSolidSvq(), -24);
    for (int i = 0; i < sources.size(); i++) {
      var after = plan.stockUpdates().get(i);
      var before = sources.get(i);
      mass = Math.addExact(mass, after.massUnits());
      residual += after.mixture().massResidualUnits();
      volume = volume.add(after.volume());
      double expected =
          plan.signedSolidMassUnits() * ((double) before.massUnits() / Mass.fromKilograms(5));
      require(
          Math.abs(before.massUnits() - after.massUnits() - expected) <= 1,
          "Actual-mass integer allocation exceeded one quantum");
      for (String species :
          List.of(BuiltinChemicalDefinitions.NA_PLUS, BuiltinChemicalDefinitions.CL_MINUS)) {
        int handle = chemistry.speciesHandle(species);
        require(
            after.mixture().amount(handle) <= before.mixture().amount(handle),
            "Precipitation created aqueous species");
      }
    }
    require(
        mass == Mass.fromKilograms(5) && volume.equals(SignedDyadic.of(.5)),
        "Cell/solid mass or volume did not close");
    require(
        residual == sources.stream().mapToLong(s -> s.mixture().massResidualUnits()).sum(),
        "Reaction changed unresolved mass");
  }

  private static void continuousPrecipitationAndRecovery() {
    var d = fixture(false, false);
    var initial = inventory(d);
    for (int step = 0; step < 3; step++) {
      var stats = new GranularReactionStage(d).step(new int[] {d.domainFlowFields.cellIndex(1, 1, 1)});
      require(stats.precipitations() > 0, "Normal repeated precipitation stopped at step " + step);
      require(inventory(d).equals(initial), "Precipitation changed total integer inventory");
      d = FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());
      require(inventory(d).equals(initial), "Restoration changed reactive inventory");
    }
  }

  private static void thermalMomentumTransfer() {
    for (boolean sheared : new boolean[] {false, true}) {
      var d = fixture(true, sheared);
      var initial = inventory(d);
      double beforeK = kinetic(d), beforeU = d.exactInternalEnergy().toDouble();
      double beforeP = momentumX(d), beforeHeat = d.thermalAudit().mechanicalWork().toDouble();
      var stage = new GranularReactionStage(d);
      var stats = stage.step(new int[] {d.domainFlowFields.cellIndex(1, 1, 1)});
      require(
          stats.precipitations() > 0 && inventory(d).equals(initial),
          "Thermal candidate did not publish conserved phase transfer");
      d.domainFlowFields.ensureGrid();
      double heat = d.thermalAudit().mechanicalWork().toDouble() - beforeHeat;
      require(
          Math.abs(momentumX(d) - beforeP) < 1e-7, "Reaction changed combined MAC/solid momentum");
      require(
          Math.abs(beforeK - kinetic(d) - heat - stage.mechanicalRoundingJoules()) < 1e-8,
          "Explicit phase mechanical operators did not close K + heat");
      require(
          sheared ? heat > 0 : Math.abs(heat) < 1e-12,
          "Resolved velocity variance has the wrong heat response");
      require(
          d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),
          "Phase owner energies do not close shared audit");
      require(
          Math.abs(d.exactInternalEnergy().toDouble() - beforeU - heat) < 1e-7,
          "Formation energy changed total U");
      require(
          d.thermalTemperatureInCell(1, 1, 1) > 300,
          "Solid formation reference energy did not heat the cell");
      d = FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());
      require(
          inventory(d).equals(initial)
              && d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),
          "Restored thermal phase state did not close");
    }
  }

  private static void bedDissolution() {
    var d = fixture(false, false);
    int cell = d.domainFlowFields.cellIndex(1, 1, 1);
    new GranularReactionStage(d).step(new int[] {cell});
    require(
        d.granularState.inventory.depositParcel(0, cell, SolidVolume.SVQ_PER_CELL),
        "Reactive bed fixture could not settle");
    d.granularCoupling.rebuild(d.granularState.parcels, d.granularState.deposits);
    for (int i = 0; i < d.liquidCells.physicalSize(); i++)
      mmm.simulation.FullCapabilityTestFixture.assignPureSpecies(d, i, BuiltinChemicalDefinitions.H2O, false);
    var initial = inventory(d);
    long solid = d.totalGranularSolidVolumeSvq();
    var stats = new GranularReactionStage(d).step(new int[] {cell});
    require(
        stats.dissolutions() > 0 && d.totalGranularSolidVolumeSvq() < solid,
        "Exposed reactive bed did not dissolve");
    require(inventory(d).equals(initial), "Bed dissolution changed total integer inventory");
    var restored = FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());
    require(inventory(restored).equals(initial), "Bed dissolution did not survive restoration");
  }

  private static void thermalDissolutionAndEnergyLimitedProgress() {
    var d = fixture(true, true);
    int cell = d.domainFlowFields.cellIndex(1, 1, 1);
    for (int step = 0; step < 3; step++) {
      var stage = new GranularReactionStage(d);
      var before = inventory(d);
      require(
          stage.step(new int[] {cell}).precipitations() > 0,
          "Repeated thermal precipitation did not advance");
      require(
          inventory(d).equals(before)
              && d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),
          "Thermal merge changed inventory");
    }
    for (int i = 0; i < d.liquidCells.physicalSize(); i++)
      mmm.simulation.FullCapabilityTestFixture.assignPureSpecies(d, i, BuiltinChemicalDefinitions.H2O, false);
    var before = inventory(d);
    double initialTemperature = d.thermalTemperatureInCell(1, 1, 1);
    boolean dissolved = false;
    for (int step = 0; step < 3; step++) {
      dissolved |= new GranularReactionStage(d).step(new int[] {cell}).dissolutions() > 0;
      require(
          inventory(d).equals(before)
              && d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),
          "Thermal dissolution changed inventory");
      d = FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());
    }
    require(
        dissolved && d.thermalTemperatureInCell(1, 1, 1) < initialTemperature,
        "Dissolution did not consume sensible heat");
    var limited = fixture(true, false, 1e6);
    limited.setThermalTemperatureInCell(1,1,1,1);
    var inventory = inventory(limited);
    var result = new GranularReactionStage(limited).step(new int[] {cell});
    require(
        result.precipitations()>0
            && inventory(limited).equals(inventory)
            && limited.thermalTemperatureInCell(1,1,1)>0
            && limited.thermalTemperatureInCell(1,1,1)<1
            && limited.exactInternalEnergy().equals(limited.thermalAudit().expectedInventory()),
        "Cold endothermic precipitation did not admit its energy-limited extent");
    var restored=FluidDomain.restoreAuthoritativeState(limited.captureAuthoritativeState());
    new GranularReactionStage(restored).step(new int[] {cell});
    require(inventory(restored).equals(inventory)
            && restored.exactInternalEnergy().equals(restored.thermalAudit().expectedInventory()),
        "Energy-limited reaction did not continue through cold recovery");
  }

  private static void dissolutionUsesSharedCapacityTransport() {
    var d =
        new FluidDomain(
            3, 3, 3, new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1e-5F, .002F, 0), 216);
    d.sealDomainBoundary();
    var granular = BuiltinGranularDefinitions.createRegistry();
    d.bindGranular(
        granular,
        new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER)),
        BuiltinFluidMaterials.createRegistry());
    d.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
    d.setPrecipitationEnabled(true);
    long extent = 281 * 81L;
    var composition =
        mmm.granular.SolidCompositionRecord.fromStableAmounts(
            BuiltinChemicalDefinitions.REGISTRY,
            Map.of(
                BuiltinChemicalDefinitions.NA_PLUS,
                64 * extent,
                BuiltinChemicalDefinitions.CL_MINUS,
                64 * extent),
            0);
    int handle = d.granularState.compositions.create(composition);
    d.granularState.parcels.add(
        new mmm.granular.GranularParcelStore.Parcel(
            .999F,
            .75F,
            .75F,
            0,
            0,
            0,
            50 * 81L,
            composition.massUnits(BuiltinChemicalDefinitions.REGISTRY),
            granular.materialHandle(BuiltinGranularDefinitions.PRECIPITATE),
            handle,
            0,
            (byte) 0));
    d.granularCoupling.rebuild(d.granularState.parcels, d.granularState.deposits);
    var stocks = new ArrayList<LiquidCellInventory.Stock>();
    for (int c = 0; c < d.cellCount; c++) {
      var volume =
          SignedDyadic.of(1).subtract(SignedDyadic.of(d.granularCoupling.solidSvq(c), -24));
      var key = new LiquidCellInventory.Key(c, 0, 255);
      long mass = d.unitScale.massUnitsForNormalized(volume.toDouble() * 1000);
      var mixture =
          d.chemistry.defaultRecord(
              BuiltinFluidMaterials.WATER.id(), volume.toDouble(), .5F, mass, false);
      stocks.add(new LiquidCellInventory.Stock(key, volume, mass, mixture, 0, false));
    }
    d.liquidCells.planAdditions(stocks).commit();
    d.markCellInventoryChanged();
    bindReactiveThermal(d,-1);
    d.domainFlowFields.ensureGrid();
    Arrays.fill(d.mac.velocityX,.25F);
    d.mac.velocityX[d.domainFlowFields.xFaceIndex(2,1,1)]=.75F;
    d.cellMacStage.publishMomentum();
    d.bootstrapGasState(null);
    var before = inventory(d);
    var initialU=d.exactInternalEnergy();
    var initialAudit=d.thermalAudit();
    double initialK=kinetic(d),storageAllowance=energyStorageAllowance(d);
    int cell = d.domainFlowFields.cellIndex(1, 1, 1);
    var stage=new GranularReactionStage(d);
    var stats = stage.step(new int[] {cell});
    require(
        stats.dissolutions() > 0 && inventory(d).equals(before),
        "Full-cell dissolution changed joint inventory");
    for (int c = 0; c < d.cellCount; c++)
      require(
          d.liquidCells.exactCellVolume(c).equals(SignedDyadic.of(1)),
          "Shared capacity flux did not fill the released neighbouring pore");
    double deltaU=d.exactInternalEnergy().subtract(initialU).toDouble();
    double rounding=d.thermalAudit().roundoffResidual().subtract(initialAudit.roundoffResidual()).toDouble();
    storageAllowance+=energyStorageAllowance(d);
    double finalK=kinetic(d),error=deltaU+finalK-initialK+stage.mechanicalRoundingJoules();
    require(Math.abs(error)<1e-6,
        "Sheared reactive displacement changed independently measured K + U: error="+error
            +", dU="+deltaU+", K="+initialK+"/"+finalK+", mechanicalRounding="+stage.mechanicalRoundingJoules());
    require(Math.abs(rounding)<=storageAllowance&&d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),
        "Reactive displacement hid a material energy change in storage roundoff");

    var restored = FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());
    require(inventory(restored).equals(before)&&restored.exactInternalEnergy().equals(d.exactInternalEnergy()),
        "Reactive capacity transport did not restore its actual inventory and U");
  }

  private static double energyStorageAllowance(FluidDomain d){
    double allowance=0;
    for(var stock:d.liquidCells.physicalStocks())allowance+=8*Math.ulp(stock.internalEnergy());
    for(double energy:d.thermalState.granularColumn().copyActiveEnergy())allowance+=8*Math.ulp(energy);
    return allowance;
  }

  private static FluidDomain fixture(boolean thermal, boolean shear) {
    return fixture(thermal, shear, -1);
  }

  private static FluidDomain fixture(boolean thermal, boolean shear, double formationEnergy) {
    var d =
        new FluidDomain(
            3, 3, 3, new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1e-5F, .002F, 0), 216);
    d.sealDomainBoundary();
    d.setVolume(1, 1, 1, .5F);
    var granular = BuiltinGranularDefinitions.createRegistry();
    d.bindGranular(
        granular,
        new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER)),
        BuiltinFluidMaterials.createRegistry());
    d.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
    d.setPrecipitationEnabled(true);
    d.setMarkerChemical(
        0,
        Map.of(
            BuiltinChemicalDefinitions.NA_PLUS,
            8_320_000L,
            BuiltinChemicalDefinitions.CL_MINUS,
            8_320_000L),
        false);
    if (thermal) bindReactiveThermal(d,formationEnergy);
    d.domainFlowFields.ensureGrid();
    Arrays.fill(d.mac.velocityX, .25F);
    if (shear) d.mac.velocityX[d.domainFlowFields.xFaceIndex(2, 1, 1)] = .75F;
    d.cellMacStage.publishMomentum();
    d.bootstrapGasState(null);
    return d;
  }

  private static void bindReactiveThermal(FluidDomain d,double formationEnergy) {
    var granular=d.granularState.registry;
      var materials = new ArrayList<ThermalMaterialDefinition>();
      var offsets = new ArrayList<ThermalSpeciesOffset>();
      materials.add(
          new ThermalMaterialDefinition(
              "test:thermal/reactive_water",
              ThermalMaterialDefinition.CarrierKind.LIQUID,
              BuiltinFluidMaterials.WATER.id(),
              300,
              0,
              0,
              List.of(new ThermalMaterialDefinition.CaloricSegment(150, 800, 4000))));
      for (var solid : granular.materials()) {
        var caloric =
            new ThermalMaterialDefinition(
                "test:thermal/" + solid.id().replace(':', '/'),
                ThermalMaterialDefinition.CarrierKind.GRANULAR,
                solid.id(),
                300,
                0,
                0,
                List.of(new ThermalMaterialDefinition.CaloricSegment(150, 800, 1000)));
        materials.add(caloric);
      }
      for (var host : materials)
        for (var species : BuiltinChemicalDefinitions.REGISTRY.species()) {
          boolean formation =
              host.carrierKind() == ThermalMaterialDefinition.CarrierKind.GRANULAR
                  && host.carrierId().equals(BuiltinGranularDefinitions.PRECIPITATE)
                  && (species.id().equals(BuiltinChemicalDefinitions.NA_PLUS)
                      || species.id().equals(BuiltinChemicalDefinitions.CL_MINUS));
          offsets.add(
              new ThermalSpeciesOffset(
                  "test:offset/"
                      + host.id().replace(':', '/')
                      + "/"
                      + species.id().replace(':', '/'),
                  species.id(),
                  host.id(),
                  formation ? ExactRational.ofDouble(formationEnergy) : ExactRational.ZERO));
        }
      materials.add(ThermalValidationSupport.AIR);
      d.bindThermal(
          new ThermalRegistry(
              materials,
              offsets,
              List.of(),
              BuiltinChemicalDefinitions.REGISTRY.fingerprint(),
              granular.fingerprint()),
          d.materialRegistry(),
          300);
  }

  private record Inventory(long mass, SignedDyadic volume, Map<Integer, Long> species) {}

  private static Inventory inventory(FluidDomain d) {
    var species = new TreeMap<Integer, Long>();
    for (var stock : d.liquidCells.stocks())
      if (stock.mixture() != null)
        for (int i = 0; i < stock.mixture().entryCount(); i++)
          species.merge(
              stock.mixture().speciesHandle(i), stock.mixture().amountAt(i), Math::addExact);
    var handles = new java.util.HashSet<Integer>();
    for (int i = 0; i < d.granularState.parcels.count(); i++)
      handles.add(d.granularState.parcels.parcel(i).solidCompositionHandle());
    d.granularState.deposits.forEachGroup(
        (cell, porosity, material, handle, volume, mass) -> handles.add(handle));
    for (int handle : handles)
      if (handle != 0) {
        var c = d.granularState.compositions.record(handle);
        for (int i = 0; i < c.entryCount(); i++)
          species.merge(c.speciesHandle(i), c.amountAt(i), Math::addExact);
      }
    return new Inventory(
        Math.addExact(d.totalMassUnits(), d.granularState.inventory.totalMassUnits()),
        d.liquidCells.exactVolume().add(SignedDyadic.of(d.totalGranularSolidVolumeSvq(), -24)),
        species);
  }

  private static double momentumX(FluidDomain d) {
    double value = Arrays.stream(d.mac.momentumX).sum();
    for (int i = 0; i < d.granularState.parcels.count(); i++) {
      var p = d.granularState.parcels.parcel(i);
      value += p.mass() * p.velocityX();
    }
    return value;
  }

  private static double kinetic(FluidDomain d) {
    d.domainFlowFields.ensureGrid();
    double value = 0;
    for (int axis = 0; axis < 3; axis++) {
      double[] mass =
          axis == 0
              ? d.cellMacStage.massX
              : axis == 1 ? d.cellMacStage.massY : d.cellMacStage.massZ;
      double[] p = axis == 0 ? d.mac.momentumX : axis == 1 ? d.mac.momentumY : d.mac.momentumZ;
      for (int i = 0; i < mass.length; i++) if (mass[i] > 0) value += .5 * p[i] * p[i] / mass[i];
    }
    for (int i = 0; i < d.granularState.parcels.count(); i++) {
      var p = d.granularState.parcels.parcel(i);
      value +=
          .5
              * p.mass()
              * (p.velocityX() * p.velocityX()
                  + p.velocityY() * p.velocityY()
                  + p.velocityZ() * p.velocityZ());
    }
    return value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
