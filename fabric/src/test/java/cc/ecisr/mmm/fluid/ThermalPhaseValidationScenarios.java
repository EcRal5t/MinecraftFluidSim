package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalDomainState;

import cc.ecisr.mmm.thermal.ThermalCarrierContext;

import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.thermal.CoherentPhaseStore;
import cc.ecisr.mmm.units.SolverUnitScale;
import cc.ecisr.mmm.thermal.ThermalAuditLedger;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalPhaseTransition;
import cc.ecisr.mmm.thermal.ThermalPhaseChangeEngine;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalSpeciesOffset;
import cc.ecisr.mmm.thermal.ThermalStateFunction;

import java.util.List;
import java.util.Map;

/** One retained selector for M9 definition, state-function and later transport/phase invariants. */

abstract class ThermalPhaseValidationScenarios extends ThermalValidationSupport {
	static void phasePlateauUsesTheStateFunctionGap() {
		ThermalMaterialDefinition ice = new ThermalMaterialDefinition("mmm:thermal/ice",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "mmm:ice", 273.15, 0.0, 2.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 273.15, 2_100.0)));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, "mmm:water", 273.15, 334_000.0, 0.6,
				List.of(new ThermalMaterialDefinition.CaloricSegment(273.15, 500.0, 4_180.0)));
		ThermalPhaseTransition melt = new ThermalPhaseTransition("mmm:phase/ice_water", ice.id(), water.id(),
				273.15, SolidVolume.SVQ_PER_BV, SolidVolume.SVQ_PER_BV * 917L / 1_000L, ExactRational.of(917),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 80_000.0, 120_000.0);
		ThermalRegistry registry = new ThermalRegistry(List.of(water, ice), List.of(), List.of(melt), "", "");
		ThermalStateFunction state = new ThermalStateFunction(registry);
		ThermalStateFunction.EquilibriumState lower = state.equilibriumState(registry.transitionHandle(melt.id()),
				1.0, Map.of(), 0.0, GasRegistry.STANDARD_PRESSURE_PA);
		double quarterEnergy = lower.lowerBoundaryEnergy()
				+ 0.25 * (lower.upperBoundaryEnergy() - lower.lowerBoundaryEnergy());
		ThermalStateFunction.EquilibriumState plateau = state.equilibriumState(registry.transitionHandle(melt.id()),
				1.0, Map.of(), quarterEnergy, GasRegistry.STANDARD_PRESSURE_PA);
		require(plateau.phaseState() == ThermalStateFunction.PhaseState.PLATEAU
				&& close(plateau.temperatureK(), 273.15) && close(plateau.upperPhaseFraction(), 0.25),
				"phase plateau did not derive its fraction from the state-function energy gap");

		ThermalPhaseTransition highPressure = new ThermalPhaseTransition(
				"mmm:phase/high_pressure_ice_water", ice.id(), water.id(),
				SolidVolume.SVQ_PER_BV, SolidVolume.SVQ_PER_BV * 917L / 1_000L,
				ExactRational.of(917), ThermalPhaseTransition.InterfaceRequirement.NONE,
				List.of(new ThermalPhaseTransition.EquilibriumPoint(3_000_000.0, 273.15),
						new ThermalPhaseTransition.EquilibriumPoint(4_000_000.0, 273.15)));
		ThermalRegistry highPressureRegistry = new ThermalRegistry(
				List.of(water, ice), List.of(), List.of(highPressure), "", "");
		require(highPressureRegistry.transition(0).minimumPressurePa() == 3_000_000.0
				&& close(highPressure.equilibriumTemperatureK(3_500_000.0), 273.15),
				"solid/liquid curve was incorrectly required to cover standard pressure");
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidDomain continuedPressure = new FluidDomain(2, 2, 2, config, 128);
		continuedPressure.setVolume(0, 0, 0, 0.5F);
		continuedPressure.bindGasRegistry(BuiltinGasDefinitions.createRegistry());
		continuedPressure.bootstrapGasState("");
		continuedPressure.bindThermal(highPressureRegistry,
				new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER)), 273.15);
		double continuedEnergy = continuedPressure.totalInternalEnergy();
		FluidDomain.ThermalPhaseStats continued = continuedPressure.stepInertPhaseChangeOnly(16);
		require(continued.operations() == 0 && continued.signedBatches() == 0L
				&& Double.doubleToRawLongBits(continuedPressure.totalInternalEnergy())
				== Double.doubleToRawLongBits(continuedEnergy),
				"constant endpoint continuation disturbed phase equilibrium");

		ThermalMaterialDefinition mercury = new ThermalMaterialDefinition("mmm:thermal/mercury",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.MERCURY.id(),
				293.15, 0.0, 8.3,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 140.0)));
		FluidMaterialRegistry waterAndMercury = new FluidMaterialRegistry(
				List.of(BuiltinFluidMaterials.WATER, BuiltinFluidMaterials.MERCURY));
		ThermalRegistry unrelatedTransition = new ThermalRegistry(
				List.of(water, ice, mercury), List.of(), List.of(highPressure), "", "");
		FluidDomain mercuryOnly = new FluidDomain(2, 2, 2, config, 128);
		mercuryOnly.setVolume(0, 0, 0, 0.5F);
		mercuryOnly.setMaterialInCell(0, 0, 0, waterAndMercury,
				waterAndMercury.handle(BuiltinFluidMaterials.MERCURY.id()));
		mercuryOnly.bindThermal(unrelatedTransition, waterAndMercury, 293.15);
		FluidDomain.ThermalPhaseStats unrelated = mercuryOnly.stepInertPhaseChangeOnly(16);
		require(unrelated.operations() == 0 && unrelated.signedBatches() == 0L,
				"a mercury-only cell was incorrectly evaluated against the ice/water pressure curve");

		ThermalPhaseTransition mixedMelt = new ThermalPhaseTransition(
				"mmm:phase/mixed_ice_water", ice.id(), water.id(), 273.15,
				146L, 134L, ExactRational.parse("0.001"),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 80_000.0, 120_000.0);
		ThermalRegistry mixedRegistry = new ThermalRegistry(
				List.of(water, ice, mercury), List.of(), List.of(mixedMelt), "", "");
		for (float fill : new float[]{0.25F, 0.5F}) {
			FluidDomain mixedCell = new FluidDomain(2, 2, 2, config, 128);
			mixedCell.setVolume(0, 0, 0, fill);
			mixedCell.setMaterialInCell(0, 0, 0, waterAndMercury,
					waterAndMercury.handle(BuiltinFluidMaterials.WATER.id()));
			require(mixedCell.tryInsertExactMaterial(0, 0, 0, fill,
					fill * BuiltinFluidMaterials.MERCURY.referenceDensity(), 0,
					waterAndMercury.handle(BuiltinFluidMaterials.MERCURY.id())).transferred(),
					"mixed condensed-phase fixture could not insert mercury");
			mixedCell.bindThermal(mixedRegistry, waterAndMercury, 273.15);
			mixedCell.setThermalTemperatureInCell(0, 0, 0, 260.0);
			double mercuryBefore = mixedCell.exactVolumeNear(0, 0, 0, 0,
					waterAndMercury.handle(BuiltinFluidMaterials.MERCURY.id()));
			FluidDomain.ThermalPhaseStats mixed = mixedCell.stepInertPhaseChangeOnly(16);
			double mercuryAfter = mixedCell.exactVolumeNear(0, 0, 0, 0,
					waterAndMercury.handle(BuiltinFluidMaterials.MERCURY.id()));
			require((fill < 0.5F ? mixed.operations() > 0 && mixed.frozenCells() == 1 : mixed.operations() == 0)
					&& close(mercuryAfter, mercuryBefore),
					"mixed-cell phase extent follows shared occupancy and retains the mercury heat bath: "
							+ mixed + ", mercury=" + mercuryBefore + " -> " + mercuryAfter);
		}
	}

	static void coherentPhaseInventoryAndExactBatchPlanning() {
		condensedIntegerCarrierSearch();
		condensedCandidatesPreserveActualThermalOwners();
		condensedTransfersRequireActualMassChange();
		condensedPhaseUsesActualRegionalCarrierBudget();
		coherentExternalMergeIsPreflightedAndAudited();
		ThermalMaterialDefinition lower = new ThermalMaterialDefinition("test:thermal/lower",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "test:solid", 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		ThermalMaterialDefinition upper = new ThermalMaterialDefinition("test:thermal/upper",
				ThermalMaterialDefinition.CarrierKind.LIQUID, "test:liquid", 300.0, 100.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		ThermalPhaseTransition transition = new ThermalPhaseTransition("test:phase/lower_upper",
				lower.id(), upper.id(), 300.0, 1L, 1L, ExactRational.parse("0.1"),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 0.0, 200_000.0);
		ThermalRegistry registry = new ThermalRegistry(List.of(lower, upper), List.of(), List.of(transition), "", "");
		ThermalPhaseChangeEngine engine = new ThermalPhaseChangeEngine(registry);
		ThermalPhaseChangeEngine.Plan freeze = engine.planInert(
				transition, 0.0, 1.0, 50.0, 16L, GasRegistry.STANDARD_PRESSURE_PA);
		ThermalPhaseChangeEngine.Plan melt = engine.planInert(
				transition, 1.0, 0.0, 50.0, 16L, GasRegistry.STANDARD_PRESSURE_PA);
		require(freeze.signedBatches() == 5L && melt.signedBatches() == -5L
				&& close(freeze.temperatureAfterK(), 300.0) && close(melt.temperatureAfterK(), 300.0),
				"exact-batch phase planner did not derive equilibrium extent from conserved energy");

		FluidMaterial liquid = FluidMaterial.withSurfaceTension("test:liquid", 1.0,
				"test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(liquid));
		ThermalCarrierContext carriers = new ThermalCarrierContext() {
			@Override public int markerCount() { return 1; }
			@Override public int cell(int marker) { return 0; }
			@Override public int fluidMaterialHandle(int marker) { return 0; }
			@Override public double mass(int marker) { return 0.5; }
			@Override public double volume(int marker) { return 0.5; }
			@Override public ChemicalMixtureRecord mixture(int marker) { return null; }
			@Override public ChemicalRegistry chemicalRegistry() { return null; }
		};
		ThermalDomainState coupled = FluidThermalBinding.create(registry, liquids, 300.0,
				1, 2, 2, 2, 0.5F, carriers);
		coupled.addCoherentExternal(new CoherentPhaseStore.Group(
				0, lower.id(), 1L, 0.5, null, 0.0));
		require(close(coupled.temperatureInCell(0, carriers), 300.0),
				"liquid/coherent LTE did not derive a shared temperature");
		coupled.setTemperatureInCell(0, 310.0, carriers);
		require(close(coupled.temperatureInCell(0, carriers), 310.0)
				&& coupled.exactEnergy(1).equals(coupled.auditLedger().expectedInventory()),
				"liquid/coherent heat exchange did not close the shared energy audit");

		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidDomain coherentCavity = new FluidDomain(3, 2, 2, config, 128);
		coherentCavity.sealDomainBoundary();
		coherentCavity.setVolume(0, 0, 0, 0.5F);
		coherentCavity.bindGasRegistry(BuiltinGasDefinitions.createRegistry());
		coherentCavity.bootstrapGasState("");
		var cavityThermal = new ThermalRegistry(List.of(lower, upper,
				new ThermalMaterialDefinition("test:thermal/coherent_cavity_air",
						ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
						GasRegistry.STANDARD_TEMPERATURE_K, 0.0, 0.026,
						List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 718.0)))),
				List.of(), List.of(transition), "", "");
		coherentCavity.bindThermal(cavityThermal, liquids, 300.0);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) {
			coherentCavity.addCoherentPhaseExternal(1, y, z, lower.id(), SolidVolume.SVQ_PER_CELL, 0.125, null, 0);
		}
		FluidDomainState coherentSnapshot = coherentCavity.captureAuthoritativeState();
		require(coherentSnapshot.gas().nodes().size() == 2, "coherent partition must split the sealed cavity");
		FluidDomain coherentRestored = FluidDomain.restoreAuthoritativeState(coherentSnapshot);
		require(coherentRestored.gasBoundary().nodeCount() == 2
				&& coherentRestored.totalCoherentSolidVolumeSvq() == 4 * SolidVolume.SVQ_PER_CELL
				&& coherentRestored.gasBoundary().auditClosed(),
				"gas restore must include the incoming coherent geometry before gas-dependent thermal energy");
		FluidDomain domain = new FluidDomain(2, 2, 2, config, 128);
		domain.setVolume(0, 0, 0, 0.5F);
		domain.bindThermal(registry, liquids, 300.0);
		domain.addCoherentPhaseExternal(0, 0, 0, lower.id(),
				SolidVolume.SVQ_PER_CELL / 2L, 0.5, null, 0.0);
		boolean collisionBlocked = false;
		try {
			domain.planCollisionEdits(List.of(
					new FluidDomain.CollisionEdit(1, 1, 1, 0xff),
					new FluidDomain.CollisionEdit(0, 0, 0, 0xff)));
		} catch (IllegalStateException expected) {
			collisionBlocked = true;
		}
		require(collisionBlocked && domain.solidMicroMask(1, 1, 1) == 0
				&& domain.solidMicroMask(0, 0, 0) == 0
				&& domain.totalCoherentSolidVolumeSvq() == SolidVolume.SVQ_PER_CELL / 2L,
				"collision batch mutated an earlier cell before rejecting coherent inventory");
		domain.setVolume(0, 0, 0, 1.0F);
		require(close(domain.totalVolumeExact(), 0.5)
				&& domain.totalCoherentSolidVolumeSvq() == SolidVolume.SVQ_PER_CELL / 2L,
				"coherent phase did not share the exact pressure/free-surface occupancy field");

		long batchSvq = SolidVolume.SVQ_PER_CELL / 8L;
		ThermalPhaseTransition cellTransition = new ThermalPhaseTransition("test:phase/cell_lower_upper",
				lower.id(), upper.id(), 300.0, batchSvq, batchSvq, ExactRational.parse("0.015625"),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 0.0, 200_000.0);
		ThermalRegistry cellRegistry = new ThermalRegistry(
				List.of(lower, upper), List.of(), List.of(cellTransition), "", "");
		FluidDomain pressureHandoff = new FluidDomain(2, 2, 2, config, 128);
		pressureHandoff.setVolume(0, 0, 0, 1.0F);
		pressureHandoff.bindGasRegistry(BuiltinGasDefinitions.createRegistry());
		pressureHandoff.bootstrapGasState("");
		ThermalPhaseTransition continuedTransition = new ThermalPhaseTransition(
				"test:phase/continued_lower_upper", lower.id(), upper.id(), batchSvq, batchSvq,
				ExactRational.parse("0.015625"), ThermalPhaseTransition.InterfaceRequirement.NONE,
				List.of(new ThermalPhaseTransition.EquilibriumPoint(0.0, 310.0),
						new ThermalPhaseTransition.EquilibriumPoint(200_000.0, 300.0)));
		pressureHandoff.bindThermal(new ThermalRegistry(
				List.of(lower, upper), List.of(), List.of(continuedTransition), "", ""), liquids, 295.0);
		pressureHandoff.pressureReference = 250_000.0F;
		FluidDomain pressureRestore = FluidDomain.restoreAuthoritativeState(
				pressureHandoff.captureAuthoritativeState());
		require(pressureRestore.stepInertPhaseChangeOnly(16).operations() == 0,
				"thermal handoff did not continue the phase curve using the real pressure projection");
		pressureRestore.setThermalTemperatureInCell(0, 0, 0, 290.0);
		require(pressureRestore.stepInertPhaseChangeOnly(16).operations() > 0,
				"continued high-pressure phase curve skipped freezing below its melting point");

		FluidDomain phaseDomain = new FluidDomain(2, 2, 2, config, 128);
		phaseDomain.setVolume(0, 0, 0, 1.0F);
		phaseDomain.bindThermal(cellRegistry, liquids, 295.0);
		SignedDyadic coldEnergy = phaseDomain.exactInternalEnergy();
		FluidDomain.ThermalPhaseStats freezeStats = phaseDomain.stepInertPhaseChangeOnly(16);
		require(freezeStats.operations() == 4 && freezeStats.signedBatches() == 4L
				&& close(phaseDomain.totalVolumeExact(), 0.5)
				&& phaseDomain.totalCoherentSolidVolumeSvq() == SolidVolume.SVQ_PER_CELL / 2L
				&& close(SolverUnitScale.forCellSize(config.cellSize()).kilograms(phaseDomain.totalMass())
						+ phaseDomain.totalCoherentMass(), 0.125)
				&& close(phaseDomain.thermalTemperatureInCell(0, 0, 0), 300.0)
				&& phaseDomain.exactInternalEnergy().equals(coldEnergy)
				&& phaseDomain.exactInternalEnergy().equals(phaseDomain.thermalAudit().expectedInventory()),
				"domain freeze commit did not conserve exact mass, volume and energy inventories");

		phaseDomain.setThermalTemperatureInCell(0, 0, 0, 305.0);
		SignedDyadic hotEnergy = phaseDomain.exactInternalEnergy();
		FluidDomain.ThermalPhaseStats meltStats = phaseDomain.stepInertPhaseChangeOnly(16);
		require(meltStats.operations() == 4 && meltStats.signedBatches() == -4L
				&& close(phaseDomain.totalVolumeExact(), 1.0)
				&& phaseDomain.totalCoherentSolidVolumeSvq() == 0L
				&& close(SolverUnitScale.forCellSize(config.cellSize()).kilograms(phaseDomain.totalMass())
						+ phaseDomain.totalCoherentMass(), 0.125)
				&& close(phaseDomain.thermalTemperatureInCell(0, 0, 0), 300.0)
				&& phaseDomain.exactInternalEnergy().equals(hotEnergy)
				&& phaseDomain.exactInternalEnergy().equals(phaseDomain.thermalAudit().expectedInventory()),
				"domain melt commit did not conserve exact mass, volume and energy inventories");

		FluidMaterial roundoffLiquid = FluidMaterial.withSurfaceTension("test:roundoff_liquid", 1_000.0,
				"test:miscible", 0.0, 90.0);
		FluidMaterialRegistry roundoffLiquids = new FluidMaterialRegistry(List.of(roundoffLiquid));
		ThermalMaterialDefinition roundoffWater = new ThermalMaterialDefinition("test:thermal/roundoff_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, roundoffLiquid.id(), 293.15, 0.0, 0.598,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 4_184.0)));
		ThermalMaterialDefinition roundoffIce = new ThermalMaterialDefinition("test:thermal/roundoff_ice",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "test:roundoff_ice", 293.15, -375_070.0, 2.22,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 2_108.0)));
		ThermalPhaseTransition roundoffTransition = new ThermalPhaseTransition("test:phase/roundoff_ice_water",
				roundoffIce.id(), roundoffWater.id(), 273.15, 146L, 134L, ExactRational.parse("0.001"),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 10_000.0, 2_000_000.0);
		ThermalRegistry roundoffRegistry = new ThermalRegistry(
				List.of(roundoffIce, roundoffWater), List.of(), List.of(roundoffTransition), "", "");
		FluidSolverConfig roundoffConfig = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidDomain roundoffDomain = new FluidDomain(2, 2, 2, roundoffConfig, 128);
		roundoffDomain.setVolume(0, 0, 0, 0.5F);
		roundoffDomain.bindThermal(roundoffRegistry, roundoffLiquids, 273.1498);
		SignedDyadic roundoffEnergy = roundoffDomain.exactInternalEnergy();
		FluidDomain.ThermalPhaseStats quantizedEquilibrium = roundoffDomain.stepInertPhaseChangeOnly(16);
		FluidDomain.ThermalPhaseStats repeatedEquilibrium = roundoffDomain.stepInertPhaseChangeOnly(16);
		require(quantizedEquilibrium.operations() == 0 && repeatedEquilibrium.operations() == 0
				&& close(roundoffDomain.thermalTemperatureInCell(0, 0, 0), 273.1498)
				&& roundoffDomain.exactInternalEnergy().equals(roundoffEnergy)
				&& roundoffDomain.exactInternalEnergy().equals(roundoffDomain.thermalAudit().expectedInventory()),
				"exact phase quantum farther from equilibrium was allowed to create a freeze/melt cycle: stats="
						+ quantizedEquilibrium + "/" + repeatedEquilibrium
						+ " energy=" + roundoffDomain.exactInternalEnergy() + "/" + roundoffEnergy
						+ " expected=" + roundoffDomain.thermalAudit().expectedInventory());

		FluidDomain quantizedDomain = new FluidDomain(2, 2, 2, roundoffConfig, 128);
		quantizedDomain.bindThermal(roundoffRegistry, roundoffLiquids, 273.15);
		double quantizedIceEnergy = new ThermalStateFunction(roundoffRegistry).internalEnergyAtTemperature(
				roundoffRegistry.materialHandle(roundoffIce.id()), 0.001, Map.of(), 273.16);
		quantizedDomain.addCoherentPhaseExternal(0, 0, 0, roundoffIce.id(), 146L,
				0.001, null, quantizedIceEnergy);
		SignedDyadic quantizedEnergy = quantizedDomain.exactInternalEnergy();
		FluidDomain.ThermalPhaseStats unavailableMelt = quantizedDomain.stepInertPhaseChangeOnly(16);
		require(unavailableMelt.operations() == 0
				&& quantizedDomain.exactInternalEnergy().equals(quantizedEnergy)
				&& quantizedDomain.exactInternalEnergy().equals(quantizedDomain.thermalAudit().expectedInventory()),
				"an unrepresentable exact phase quantum stopped the region instead of leaving inventory unchanged");

		FluidDomain markerBoundMelt = new FluidDomain(2, 2, 2, config, 8);
		markerBoundMelt.setVolume(1, 1, 1, 1.0F);
		markerBoundMelt.bindThermal(cellRegistry, liquids, 305.0);
		double markerBoundEnergy = new ThermalStateFunction(cellRegistry).internalEnergyAtTemperature(
				cellRegistry.materialHandle(lower.id()), 0.015625, Map.of(), 305.0);
		markerBoundMelt.addCoherentPhaseExternal(0, 0, 0, lower.id(), batchSvq,
				0.015625, null, markerBoundEnergy);
		SignedDyadic markerBoundInventory = markerBoundMelt.exactInternalEnergy();
		FluidDomain.ThermalPhaseStats markerBound = markerBoundMelt.stepInertPhaseChangeOnly(16);
		require(markerBound.operations() == 0 && markerBoundMelt.particleCount() == 8
				&& markerBoundMelt.totalCoherentSolidVolumeSvq() == batchSvq
				&& markerBoundMelt.exactInternalEnergy().equals(markerBoundInventory),
				"marker-budget-bound melt paused or changed a valid deferred base");

		require(FluidDomain.minimumPhaseBatchesForCapacity(1_025L, 1_000L, -12L) == 3L,
				"shrinking phase planning did not clear transient joint-cell overfill before evaluating LTE");
		long markerSvq = SolidVolume.SVQ_PER_CELL / 8L;
		float tailedMarkerVolume = Math.nextUp(SolidVolume.toCellVolume(markerSvq));
		require(SolidVolume.cellVolumeSvqAtMost(tailedMarkerVolume) == markerSvq
				&& SolidVolume.cellVolumeSvqAtLeast(tailedMarkerVolume) == markerSvq + 1L,
				"phase SVQ bounds quantized away an authoritative float marker-volume tail");
	}

	static void condensedTransfersRequireActualMassChange() {
		for (int batchMass : new int[] {1, 200, 256}) {
			var liquid = FluidMaterial.withSurfaceTension("test:mass/water", 1.0E20F, "test:mass/miscible", 0, 90);
			var liquids = new FluidMaterialRegistry(List.of(liquid));
			var segments = List.of(new ThermalMaterialDefinition.CaloricSegment(150, 800, 1));
			var water = new ThermalMaterialDefinition("test:mass/water", ThermalMaterialDefinition.CarrierKind.LIQUID,
					liquid.id(), 300, 0, 0, segments);
			var ice = new ThermalMaterialDefinition("test:mass/ice", ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID,
					"test:mass/ice", 300, -1.0E20 / batchMass, 0, segments);
			var transition = new ThermalPhaseTransition("test:mass/freeze", ice.id(), water.id(), 300, 1, 1,
					ExactRational.of(batchMass), ThermalPhaseTransition.InterfaceRequirement.NONE, 10000, 3000000);
			var domain = new FluidDomain(3, 3, 3,
					new FluidSolverConfig(.5F, 1.0E20F, 0, .05F, 100, 1.0E-5F, .45F, .002F, 0), 64);
			domain.setVolume(1, 1, 1, .125F);
			domain.setMarkerMaterial(0, liquids, 0);
			domain.bindThermal(new ThermalRegistry(List.of(water, ice), List.of(), List.of(transition), "", ""), liquids, 200);
			var before = domain.captureAuthoritativeState();
			var revision = domain.thermalState.thermalRevision();
			var energy = domain.exactInternalEnergy();
			double sourceMass = domain.unitScale.kilograms(domain.totalMass());
			double sourceSolverMass = domain.totalMass();
			require(Math.ulp(sourceMass) == 256, "mass-quantization fixture did not reach the intended actual kg spacing");
			var stats = domain.stepInertPhaseChangeOnly(1);
			if (batchMass < 256) {
				var after = domain.captureAuthoritativeState();
				require(stats.operations() == 0 && domain.totalCoherentSolidVolumeSvq() == 0
						&& domain.totalMass() == sourceSolverMass
						&& domain.totalVolumeExact() == .125 && domain.exactInternalEnergy().equals(energy)
						&& domain.thermalState.thermalRevision().equals(revision)
						&& after.thermal().audit().equals(before.thermal().audit())
						&& java.util.Arrays.equals(after.thermal().copyInternalEnergy(), before.thermal().copyInternalEnergy()),
						"unrepresentable freeze transferred requested kg without debiting the actual source: " + batchMass);
			} else {
				require(stats.operations() == 1 && domain.totalCoherentMass() == 256
						&& SignedDyadic.of(domain.unitScale.kilograms(domain.totalMass()))
								.add(SignedDyadic.of(domain.totalCoherentMass())).equals(SignedDyadic.of(sourceMass))
						&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
						"representable transferred kg must retain the normal phase path");
				FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			}
		}
	}

	static void condensedCandidatesPreserveActualThermalOwners() {
		var fluids = new FluidMaterialRegistry(List.of(
				FluidMaterial.withSurfaceTension("test:owner/water", 64, "test:owner/miscible", 0, 90),
				FluidMaterial.withSurfaceTension("test:owner/bath", 64, "test:owner/miscible", 0, 90)));
		var segments = List.of(new ThermalMaterialDefinition.CaloricSegment(200, 400, 1.0E307));
		var water = new ThermalMaterialDefinition("test:owner/water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:owner/water", 300, 1.0E308, 0, segments);
		var bath = new ThermalMaterialDefinition("test:owner/bath", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:owner/bath", 300, -1.0E308, 0, segments);
		var ice = new ThermalMaterialDefinition("test:owner/ice", ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID,
				"test:owner/ice", 301, 1.0E308, 0, segments);
		long batchSvq = SolidVolume.SVQ_PER_CELL / 64;
		var transition = new ThermalPhaseTransition("test:owner/freeze", ice.id(), water.id(), 301,
				batchSvq, batchSvq, ExactRational.parse("1/8"),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 10000, 3000000);
		var registry = new ThermalRegistry(List.of(water, bath, ice), List.of(), List.of(transition), "", "");
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 64, 0, .05F, 100, 1.0E-5F, .45F, .002F, 0), 64);
		domain.setVolume(1, 1, 1, .5F);
		for (int marker = 0; marker < domain.particleCount(); marker++)
			domain.setMarkerMaterial(marker, fluids, marker < 2 ? 0 : 1);
		domain.bindThermal(registry, fluids, 300);
		var before = domain.exactInternalEnergy();
		var audit = domain.thermalAudit();
		var stats = domain.stepInertPhaseChangeOnly(1);
		require(stats.operations() == 1 && stats.frozenCells() == 1
				&& domain.particleCount() == 4 && domain.totalCoherentSolidVolumeSvq() == batchSvq
				&& domain.totalCoherentMass() == .125
				&& domain.unitScale.kilograms(domain.totalMass()) + domain.totalCoherentMass() == 4
				&& domain.thermalTemperatureInCell(1, 1, 1) == 300.03125
				&& domain.exactInternalEnergy().subtract(before).equals(
						domain.thermalAudit().roundoffResidual().subtract(audit.roundoffResidual()))
				&& domain.thermalAudit().externalSource().equals(audit.externalSource())
				&& domain.thermalAudit().externalSink().equals(audit.externalSink())
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"condensed candidate merged independent raw reference-energy owners");
		for (int marker = 0; marker < domain.particleCount(); marker++)
			require(Double.isFinite(domain.thermalMarkerTemperature(marker))
					&& domain.thermalMarkerTemperature(marker) > 0, "freeze published invalid liquid energy");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
				&& restored.thermalAudit().equals(domain.thermalAudit())
				&& restored.thermalState.coherentPhases().snapshot().equals(domain.thermalState.coherentPhases().snapshot())
				&& restored.thermalTemperatureInCell(1, 1, 1) == 300.03125,
				"actual-owner condensed state did not survive capture/restore");
	}

	static void condensedIntegerCarrierSearch() {
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		for (boolean freezing : new boolean[]{true, false}) for (int batches : new int[]{2, 4}) {
			FluidDomain domain = condensedIntegerCarrierFixture(freezing, batches);
			var before = condensedLedger(domain, chemistry);
			var energy = domain.exactInternalEnergy(); var audit = domain.thermalAudit();
			var mass = SignedDyadic.of(domain.unitScale.kilograms(domain.totalMass()))
					.add(SignedDyadic.of(domain.totalCoherentMass()));
			double volume = domain.totalVolumeExact()
					+ SolidVolume.toCellVolume(domain.totalCoherentSolidVolumeSvq());
			// Freezing two batches from one quantum has no one-batch carrier split.
			// Two two-batch donors carrying one and three quanta admit 1/2/4, but not 3.
			// A two-batch coherent group carrying one quantum likewise only melts whole.
			var stats = domain.stepInertPhaseChangeOnly(batches);
			var after = condensedLedger(domain, chemistry);
			require(stats.operations() == batches && stats.signedBatches() == (freezing ? batches : -batches)
					&& stats.frozenCells() == (freezing ? 1 : 0) && stats.meltedCells() == (freezing ? 0 : 1),
					"complete condensed integer candidates must remain searchable across carrier holes: " + stats);
			require(SignedDyadic.of(domain.unitScale.kilograms(domain.totalMass()))
					.add(SignedDyadic.of(domain.totalCoherentMass())).equals(mass)
					&& domain.totalVolumeExact() + SolidVolume.toCellVolume(domain.totalCoherentSolidVolumeSvq()) == volume
					&& after.species().equals(before.species())
					&& Math.abs(after.massResidual() - before.massResidual()) <= Math.ulp(before.massResidual()),
					"complete condensed candidates preserve actual kg, species, residual and total SVQ");
			require(domain.exactInternalEnergy().subtract(energy).equals(
					domain.thermalAudit().roundoffResidual().subtract(audit.roundoffResidual()))
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
					&& domain.thermalAudit().externalSource().equals(audit.externalSource())
					&& domain.thermalAudit().externalSink().equals(audit.externalSink()),
					"complete condensed candidates book actual U storage roundoff without changing external inventory");
			double temperature = domain.thermalTemperatureInCell(1, 1, 1);
			require(freezing ? temperature > 200 && temperature < 300 : temperature > 300 && temperature < 500,
					"complete condensed extent must move the actual heat bath toward equilibrium");
			var snapshot = domain.captureAuthoritativeState();
			var restored = FluidDomain.restoreAuthoritativeState(snapshot);
			var restoredSnapshot = restored.captureAuthoritativeState();
			require(restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
					&& restored.thermalAudit().equals(domain.thermalAudit())
					&& condensedLedger(restored, chemistry).equals(after)
					&& restored.totalMass() == domain.totalMass() && restored.totalCoherentMass() == domain.totalCoherentMass()
					&& restored.totalVolumeExact() == domain.totalVolumeExact()
					&& java.util.Arrays.equals(restoredSnapshot.thermal().copyInternalEnergy(), snapshot.thermal().copyInternalEnergy())
					&& restoredSnapshot.thermal().coherentGroups().equals(snapshot.thermal().coherentGroups()),
					"condensed integer candidate handoff preserves the committed physical inventories");
		}
	}

	private static FluidDomain condensedIntegerCarrierFixture(boolean freezing, int batches) {
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER, BuiltinFluidMaterials.OIL));
		var water = condensedIntegerMaterial("test:integer/water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				BuiltinFluidMaterials.WATER.id(), 0, 4_180);
		var oil = condensedIntegerMaterial("test:integer/oil", ThermalMaterialDefinition.CarrierKind.LIQUID,
				BuiltinFluidMaterials.OIL.id(), 0, 2_000);
		var ice = condensedIntegerMaterial("test:integer/ice", ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID,
				"test:ice", -334_000, 2_000);
		var air = condensedIntegerMaterial("test:integer/air", ThermalMaterialDefinition.CarrierKind.GAS,
				cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions.AIR, 0, 718);
		long batchSvq = SolidVolume.SVQ_PER_CELL / 16;
		var transition = new ThermalPhaseTransition("test:integer/phase", ice.id(), water.id(), 300,
				batchSvq, batchSvq, ExactRational.parse("125/16"),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 10_000, 3_000_000);
		var thermal = new ThermalRegistry(List.of(water, oil, ice, air), List.of(
				new ThermalSpeciesOffset("test:integer/wh", BuiltinChemicalDefinitions.H2O, water.id(), ExactRational.ZERO),
				new ThermalSpeciesOffset("test:integer/ih", BuiltinChemicalDefinitions.H2O, ice.id(), ExactRational.ZERO),
				new ThermalSpeciesOffset("test:integer/oc", BuiltinChemicalDefinitions.OIL_CARRIER, oil.id(), ExactRational.ZERO)),
				List.of(transition), chemistry.fingerprint(), "");
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 1_000, 0, .05F, 100, 1E-5F, .45F, .002F, 0), 64);
		domain.sealDomainBoundary();
		domain.setVolume(1, 1, 1, freezing ? SolidVolume.toCellVolume(2 * batchSvq) : .25F);
		domain.setMarkerMaterial(0, liquids, freezing ? 0 : 1);
		if (freezing) {
			if (batches == 4) require(domain.tryInsertExactVolume(1, 1, 1,
					SolidVolume.toCellVolume(2 * batchSvq), 0, 0).transferred(), "second integer-phase water carrier");
			require(domain.tryInsertExactVolume(1, 1, 1, .25F, 0, 1).transferred(), "integer-phase oil heat bath");
		}
		domain.bindChemistry(chemistry);
		if (freezing) {
			domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.H2O, 1L), false);
			if (batches == 4) domain.setMarkerChemical(1, Map.of(BuiltinChemicalDefinitions.H2O, 3L), false);
		}
		domain.bindThermal(thermal, liquids, freezing ? 200 : 500);
		if (!freezing) {
			long quanta = batches == 2 ? 1 : 4;
			double mass = transition.massMuPerBatch().multiply(batches).toDouble();
			double speciesMass = chemistry.species(chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O))
					.massKilogramsPerAu().multiply(quanta).divide(cc.ecisr.mmm.chemistry.ChemicalLimits.QUANTA_PER_AU).toDouble();
			var composition = new CoherentPhaseStore.Composition(
					List.of(new CoherentPhaseStore.Component(BuiltinChemicalDefinitions.H2O, quanta)),
					Double.doubleToRawLongBits(mass - speciesMass));
			double energy = new ThermalStateFunction(thermal).internalEnergyAtTemperature(
					thermal.materialHandle(ice.id()), mass, Map.of(BuiltinChemicalDefinitions.H2O, quanta), 500);
			domain.addCoherentPhaseExternal(1, 1, 1, ice.id(), batchSvq * batches, mass, composition, energy);
		}
		domain.captureAuthoritativeState();
		return domain;
	}

	private static ThermalMaterialDefinition condensedIntegerMaterial(String id,
			ThermalMaterialDefinition.CarrierKind kind, String carrier, double reference, double capacity) {
		return new ThermalMaterialDefinition(id, kind, carrier, 300, reference, .1,
				List.of(new ThermalMaterialDefinition.CaloricSegment(150, 800, capacity)));
	}

	private static void condensedPhaseUsesActualRegionalCarrierBudget() {
		FluidMaterial target = FluidMaterial.withSurfaceTension("test:a_phase_carriers", 1.0,
				"test:miscible", 0.0, 90.0);
		FluidMaterial other = FluidMaterial.withSurfaceTension("test:z_phase_carriers", 1.0,
				"test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(target, other));
		var segments = List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0));
		var solid = new ThermalMaterialDefinition("test:thermal/phase_carriers_solid",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "test:solid", 300.0, 0.0, 0.0, segments);
		var liquid = new ThermalMaterialDefinition("test:thermal/phase_carriers_target",
				ThermalMaterialDefinition.CarrierKind.LIQUID, target.id(), 300.0, 100.0, 0.0, segments);
		var heatBath = new ThermalMaterialDefinition("test:thermal/phase_carriers_other",
				ThermalMaterialDefinition.CarrierKind.LIQUID, other.id(), 300.0, 100.0, 0.0, segments);
		var transition = new ThermalPhaseTransition("test:phase/regional_carriers", solid.id(), liquid.id(),
				300.0, 128L, 128L, ExactRational.parse("1/1048576"),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 0.0, 200_000.0);
		var registry = new ThermalRegistry(List.of(solid, liquid, heatBath), List.of(),
				List.of(transition), "", "");
		var config = new FluidSolverConfig(0.5F, 1.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(3, 3, 3, config, 512);
		domain.setVolume(1, 1, 1, 1.0F / 4_096);
		domain.setMarkerMaterial(0, liquids, liquids.handle(target.id()));
		for (int marker = 1; marker < 256; marker++) {
			require(domain.tryInsertExactVolume(1, 1, 1, 1.0F / 4_096, 1,
					liquids.handle(other.id()), false).transferred(), "phase heat-bath insertion failed");
		}
		domain.bindThermal(registry, liquids, 295.0);
		SignedDyadic before = domain.exactInternalEnergy();
		ThermalAuditLedger audit = domain.thermalAudit();
		double volume = domain.totalVolumeExact(), mass = domain.totalMass();
		FluidDomain.ThermalPhaseStats frozen = domain.stepInertPhaseChangeOnly(1);
		require(frozen.operations() == 1 && frozen.signedBatches() == 1L && domain.particleCount() == 256
				&& domain.thermalState.coherentPhases().groupCount() == 1
				&& domain.totalCoherentSolidVolumeSvq() == 128L
				&& domain.totalVolumeExact() + SolidVolume.toCellVolume(128L) == volume
				&& domain.unitScale.kilograms(domain.totalMass()) + domain.totalCoherentMass()
						== domain.unitScale.kilograms(mass)
				&& domain.exactInternalEnergy().subtract(before).equals(
						domain.thermalAudit().roundoffResidual().subtract(audit.roundoffResidual()))
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"valid 257-carrier phase inventory must conserve actual mass, volume and energy: " + frozen);
		FluidDomainState snapshot = domain.captureAuthoritativeState();
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(snapshot);
		FluidDomainState restoredSnapshot = restored.captureAuthoritativeState();
		var restoredThermal = restoredSnapshot.thermal();
		require(restored.particleCount() == domain.particleCount()
				&& restored.totalVolumeExact() == domain.totalVolumeExact()
				&& restored.totalMass() == domain.totalMass()
				&& restored.totalCoherentMass() == domain.totalCoherentMass()
				&& restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
				&& restored.thermalAudit().equals(domain.thermalAudit())
				&& java.util.Arrays.equals(restoredSnapshot.materials().copyParticleMass(), snapshot.materials().copyParticleMass())
				&& java.util.Arrays.equals(restoredSnapshot.materials().copyPositionVolume(), snapshot.materials().copyPositionVolume())
				&& java.util.Arrays.equals(restoredThermal.copyInternalEnergy(), snapshot.thermal().copyInternalEnergy())
				&& restoredThermal.coherentGroups().equals(snapshot.thermal().coherentGroups())
				&& restored.thermalTemperatureInCell(1, 1, 1) == domain.thermalTemperatureInCell(1, 1, 1),
				"public handoff changed the valid 257-carrier caloric inventory");
	}

	static void coherentExternalMergeIsPreflightedAndAudited() {
		var components = List.of(new CoherentPhaseStore.Component("test:a", 1));
		for (double residual : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
			boolean rejected = false;
			try { new CoherentPhaseStore.Composition(components, Double.doubleToRawLongBits(residual)); }
			catch (IllegalArgumentException expected) { rejected = true; }
			require(rejected, "coherent composition accepted a non-finite residual before record field initialization");
		}
		for (double residual : new double[]{0.0, -0.0, Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -1.25}) {
			long raw = Double.doubleToRawLongBits(residual);
			var composition = new CoherentPhaseStore.Composition(components, raw);
			require(composition.massResidualBits() == raw && Double.doubleToRawLongBits(composition.massResidual()) == raw,
					"coherent composition changed finite residual raw bits");
		}
		var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		var chemistry = new ChemicalRegistry(List.of(
				new cc.ecisr.mmm.chemistry.ChemicalSpecies("test:a", Map.of("A", 1), 0, ExactRational.ONE, 0),
				new cc.ecisr.mmm.chemistry.ChemicalSpecies("test:b", Map.of("A", 1), 0, ExactRational.ONE, 0)),
				List.of(new cc.ecisr.mmm.chemistry.ChemicalPhaseDefinition("test:water", BuiltinFluidMaterials.WATER.id(),
						List.of("test:a", "test:b"), Map.of("test:a", ExactRational.ONE), false)), List.of(
				new cc.ecisr.mmm.chemistry.ChemicalReaction("test:reaction", cc.ecisr.mmm.chemistry.ChemicalReaction.Kind.INSTANTANEOUS,
						"test:water", Map.of("test:a", 1), Map.of("test:b", 1), ExactRational.ZERO)));
		var config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		String phase = "test:thermal/coherent_merge";
		for (int compositionCase = 0; compositionCase < 3; compositionCase++) {
			var water = new ThermalMaterialDefinition("test:thermal/merge_water",
					ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
					300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1_000, 1)));
			var solid = new ThermalMaterialDefinition(phase, ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID,
					"test:solid", 300, 1e18, 0, water.caloricSegments());
			var offsets = List.of(new ThermalSpeciesOffset("test:offset/a", "test:a", phase, ExactRational.ZERO),
					new ThermalSpeciesOffset("test:offset/b", "test:b", phase, ExactRational.ZERO));
			var registry = new ThermalRegistry(List.of(water, solid), offsets, List.of(), chemistry.fingerprint(), "");
			var domain = new FluidDomain(3, 3, 3, config, 216);
			domain.bindMaterialRegistry(liquids); domain.bindChemistry(chemistry);
			domain.bindThermal(registry, liquids, 300);
			double firstMass = 0.24999999999999914, secondMass = 1.2999999999999952;
			CoherentPhaseStore.Composition first = compositionCase == 0 ? null : new CoherentPhaseStore.Composition(
					List.of(new CoherentPhaseStore.Component("test:a", 1)), Double.doubleToRawLongBits(firstMass - 1e-9));
			CoherentPhaseStore.Composition second = compositionCase == 0 ? null : new CoherentPhaseStore.Composition(
					List.of(new CoherentPhaseStore.Component(compositionCase == 1 ? "test:a" : "test:b", 1)),
					Double.doubleToRawLongBits(secondMass - 1e-9));
			domain.addCoherentPhaseExternal(1, 1, 1, phase, 3, firstMass, first, 2.4999999999999907e17);
			var before = domain.thermalState.coherentPhases().snapshot();
			var revision = domain.thermalState.thermalRevision();
			var audit = domain.thermalAudit();
			boolean rejected = false;
			try { domain.addCoherentPhaseExternal(1, 1, 1, phase, 3, secondMass, second, 1.299999999999995e18); }
			catch (ThermalStateFunction.EnergyOutsideValidityException expected) { rejected = true; }
			require(rejected && domain.thermalState.coherentPhases().snapshot().equals(before)
					&& domain.thermalState.thermalRevision().equals(revision) && domain.thermalAudit().equals(audit)
					&& domain.exactInternalEnergy().equals(audit.expectedInventory()),
					"coherent merge committed an invalid final thermal owner or changed a rejected candidate");

			// A valid merge must book its raw storage loss, including chemical groups.
			var ordinary = new ThermalRegistry(List.of(water, new ThermalMaterialDefinition(phase,
					ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "test:solid", 300, 0, 0,
					water.caloricSegments())), List.of(
					new ThermalSpeciesOffset("test:offset/a", "test:a", phase, ExactRational.of(1_000_000_000)),
					new ThermalSpeciesOffset("test:offset/b", "test:b", phase, ExactRational.of(-1_000_000_000))),
					List.of(), chemistry.fingerprint(), "");
			var accepted = new FluidDomain(3, 3, 3, config, 216);
			accepted.bindMaterialRegistry(liquids); accepted.bindChemistry(chemistry);
			accepted.bindThermal(ordinary, liquids, 300);
			accepted.addCoherentPhaseExternal(1, 1, 1, phase, 3, firstMass, first, 128);
			double additionEnergy = compositionCase == 2 ? -1 + 1e-15 : 1e-15;
			accepted.addCoherentPhaseExternal(1, 1, 1, phase, 3, secondMass, second, additionEnergy);
			var groups = accepted.thermalState.coherentPhases().snapshot();
			var importedEnergy = SignedDyadic.of(128).add(SignedDyadic.of(additionEnergy));
			require(groups.size() == 1 && groups.getFirst().internalEnergy() == 128 + additionEnergy
					&& groups.getFirst().mass() == firstMass + secondMass && groups.getFirst().occupiedVolumeSvq() == 6
					&& (compositionCase == 0 ? groups.getFirst().composition() == null
							: groups.getFirst().composition().stableAmounts().equals(compositionCase == 1
									? Map.of("test:a", 2L) : Map.of("test:a", 1L, "test:b", 1L)))
					&& accepted.thermalAudit().externalSource().equals(importedEnergy)
					&& accepted.thermalAudit().roundoffResidual().equals(
							SignedDyadic.of(groups.getFirst().internalEnergy()).subtract(importedEnergy))
					&& accepted.exactInternalEnergy().equals(accepted.thermalAudit().expectedInventory()),
					"coherent merge raw energy roundoff was omitted from its audit");
			var restored = new FluidDomain(3, 3, 3, config, 216);
			restored.bindMaterialRegistry(liquids); restored.bindChemistry(chemistry);
			restored.bindThermal(ordinary, liquids, 300);
			restored.thermalState.restoreThermalCoherent(groups);
			restored.thermalState.restoreAuditLedger(accepted.thermalAudit());
			var group = restored.thermalState.coherentPhases().group(0);
			require(restored.thermalState.coherentPhases().snapshot().equals(groups)
					&& restored.exactInternalEnergy().equals(restored.thermalAudit().expectedInventory())
					&& restored.thermalState.stateFunction().stateForInventory(List.of(new ThermalStateFunction.InventoryPortion(
							ordinary.materialHandle(phase), group.mass(), group.composition() == null
							? Map.of() : group.composition().stableAmounts())), group.internalEnergy()).temperatureK() > 0,
					"valid coherent capture/restore lost its exact energy or positive-temperature state");
		}
	}

	static void compositionAndGranularPhaseChangeConservesJointTruth() {
		boundedPhaseCandidates();
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(3, 3, 3, config, 256);
		domain.setVolume(1, 1, 1, 0.75F);
		domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
		domain.bindChemistry(chemistry);
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.H2O, 1_000_000L), true);
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long sandSvq = granular.material(sand).maximumActiveParcelSvq();
		double sandMass = sandSvq / (double) SolidVolume.SVQ_PER_BV
				* granular.material(sand).referenceDensity();
		require(domain.tryInsertExactGranular(1, 1, 1, sand, 0, sandSvq, sandMass).inserted(),
				"joint phase fixture could not insert its granular heat bath");
		domain.bindThermal(compositionPhaseThermalRegistry(liquids, granular, chemistry), liquids, 295.0);

		CondensedLedger initial = condensedLedger(domain, chemistry);
		long initialGranularSvq = domain.totalGranularSolidVolumeSvq();
		double initialGranularMass = domain.totalGranularMass();
		SolverUnitScale massScale = SolverUnitScale.forCellSize(config.cellSize());
		double initialMass = massScale.kilograms(domain.totalMass()) + domain.totalCoherentMass();
		SignedDyadic coldEnergy = domain.exactInternalEnergy();
		FluidDomain.ThermalPhaseStats freeze = domain.stepInertPhaseChangeOnly(16);
		CondensedLedger frozen = condensedLedger(domain, chemistry);
		require(freeze.operations() > 0 && freeze.signedBatches() > 0L
				&& domain.totalCoherentSolidVolumeSvq() > 0L
				&& frozen.species().equals(initial.species())
				&& close(frozen.massResidual(), initial.massResidual())
				&& frozen.indicatorCount() == 1
				&& Float.floatToRawIntBits(frozen.indicatorVolume())
				== Float.floatToRawIntBits(initial.indicatorVolume())
				&& Double.doubleToRawLongBits(frozen.indicatorMass())
				== Double.doubleToRawLongBits(initial.indicatorMass())
				&& domain.totalGranularSolidVolumeSvq() == initialGranularSvq
				&& close(domain.totalGranularMass(), initialGranularMass)
				&& close(massScale.kilograms(domain.totalMass()) + domain.totalCoherentMass(), initialMass)
				&& Math.abs(domain.exactInternalEnergy().subtract(coldEnergy).toDouble()) <= 1.0E-8
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"composition/granular freeze did not conserve joint species, indicator, solid and energy truth: stats="
						+ freeze + " initial=" + initial + " frozen=" + frozen
						+ " coherent=" + domain.totalCoherentSolidVolumeSvq()
						+ " granularSvq=" + domain.totalGranularSolidVolumeSvq() + "/" + initialGranularSvq
						+ " granularMass=" + domain.totalGranularMass() + "/" + initialGranularMass
						+ " massKg=" + (massScale.kilograms(domain.totalMass()) + domain.totalCoherentMass()) + "/" + initialMass
						+ " energy=" + domain.exactInternalEnergy() + "/" + coldEnergy
						+ " expected=" + domain.thermalAudit().expectedInventory());

		domain.setThermalTemperatureInCell(1, 1, 1, 305.0);
		SignedDyadic hotEnergy = domain.exactInternalEnergy();
		FluidDomain.ThermalPhaseStats melt = domain.stepInertPhaseChangeOnly(16);
		CondensedLedger melted = condensedLedger(domain, chemistry);
		require(melt.operations() > 0 && melt.signedBatches() < 0L
				&& domain.totalCoherentSolidVolumeSvq() == 0L
				&& melted.species().equals(initial.species())
				&& close(melted.massResidual(), initial.massResidual())
				&& melted.indicatorCount() == 1
				&& domain.totalGranularSolidVolumeSvq() == initialGranularSvq
				&& close(domain.totalGranularMass(), initialGranularMass)
				&& close(massScale.kilograms(domain.totalMass()) + domain.totalCoherentMass(), initialMass)
				&& Math.abs(domain.exactInternalEnergy().subtract(hotEnergy).toDouble()) <= 1.0E-8
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"composition/granular melt did not restore joint species, solid and energy truth");
	}

	private static void boundedPhaseCandidates() {
		var granular = BuiltinGranularDefinitions.createRegistry();
		var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		var config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		var species = new java.util.ArrayList<cc.ecisr.mmm.chemistry.ChemicalSpecies>();
		var ids = new java.util.ArrayList<String>();
		for (int i = 0; i < 17; i++) {
			String id = "test:s" + i; ids.add(id);
			species.add(new cc.ecisr.mmm.chemistry.ChemicalSpecies(id, Map.of("A", 1), 0, ExactRational.ONE, 0));
		}
		var chemistry = new ChemicalRegistry(species, List.of(new cc.ecisr.mmm.chemistry.ChemicalPhaseDefinition(
				"test:phase", "mmm:water", ids, Map.of(ids.getFirst(), ExactRational.ONE), false)),
				List.of(new cc.ecisr.mmm.chemistry.ChemicalReaction("test:r",
						cc.ecisr.mmm.chemistry.ChemicalReaction.Kind.INSTANTANEOUS, "test:phase",
						Map.of("test:s0", 1), Map.of("test:s1", 1), ExactRational.ZERO)));
		var thermal = compositionPhaseThermalRegistry(liquids, granular, chemistry);
		for (int divisor = 1; divisor <= 2; divisor++) {
			var domain = new FluidDomain(3, 3, 3, config, 256);
			domain.setVolume(0, 0, 0, 0.25F); domain.bindMaterialRegistry(liquids); domain.bindChemistry(chemistry);
			for (int marker = 0; marker < 2; marker++) {
				domain.particles.setVolume(marker, 0.0625F); domain.particles.setMass(marker, 62.5);
				var amounts = new java.util.TreeMap<String, Long>();
				for (int i = marker * 8; i < (marker == 0 ? 8 : 17); i++) amounts.put(ids.get(i), 10L);
				domain.setMarkerChemical(marker, amounts, false);
			}
			var transition = thermal.transitions().getFirst();
			var bounded = new ThermalRegistry(thermal.materials(), thermal.speciesOffsets(), List.of(
					new ThermalPhaseTransition(transition.id(), transition.lowerEnergyMaterialId(), transition.upperEnergyMaterialId(),
							300.0, transition.lowerVolumeSvqPerBatch() / divisor, transition.upperVolumeSvqPerBatch() / divisor,
							transition.massMuPerBatch().divide(divisor), transition.interfaceRequirement(), 0, 200_000)),
					chemistry.fingerprint(), granular.fingerprint());
			domain.bindThermal(bounded, liquids, 280.0);
			var energy = domain.exactInternalEnergy();
			require(domain.stepInertPhaseChangeOnly(1).operations() == divisor - 1
					&& domain.exactInternalEnergy().equals(energy),
					"cell aggregate and target carrier enforce their own composition capacities");
		}
		var domain = new FluidDomain(3, 3, 3, config, 256);
		domain.setVolume(2, 2, 2, 0.125F); domain.bindMaterialRegistry(liquids); domain.bindChemistry(chemistry);
		domain.setMarkerChemical(0, Map.of(ids.getFirst(), cc.ecisr.mmm.chemistry.ChemicalLimits.MAX_REGION_TOTAL_QUANTA), false);
		domain.bindThermal(thermal, liquids, 320.0);
		var composition = new CoherentPhaseStore.Composition(List.of(new CoherentPhaseStore.Component(ids.getFirst(), 1L)),
				Double.doubleToRawLongBits(15.625 - 1.0e-9));
		domain.addCoherentPhaseExternal(0, 0, 0, "test:thermal/composition_ice", SolidVolume.SVQ_PER_CELL / 8,
				15.625, composition, 3125.0);
		var energy = domain.exactInternalEnergy();
		require(domain.stepInertPhaseChangeOnly(1).operations() == 0 && domain.exactInternalEnergy().equals(energy),
				"melt reserves liquid chemical capacity before transferring solid inventory");
		domain.setMarkerChemical(0, Map.of(ids.getFirst(), 1L), false);
		require(domain.stepInertPhaseChangeOnly(1).operations() == 1, "melt resumes when liquid capacity opens");
	}

	static ThermalRegistry compositionPhaseThermalRegistry(
			FluidMaterialRegistry liquids, GranularRegistry granular, ChemicalRegistry chemistry
	) {
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("test:thermal/composition_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				300.0, 100.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		ThermalMaterialDefinition ice = new ThermalMaterialDefinition("test:thermal/composition_ice",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "test:composition_ice",
				300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		java.util.ArrayList<ThermalMaterialDefinition> materials = new java.util.ArrayList<>();
		materials.add(water); materials.add(ice);
		materials.add(new ThermalMaterialDefinition("test:thermal/composition_air",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
				300.0, 0.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 718.0))));
		for (int handle = 0; handle < granular.materials().size(); handle++) materials.add(
				new ThermalMaterialDefinition("test:thermal/composition_granular_" + handle,
						ThermalMaterialDefinition.CarrierKind.GRANULAR, granular.material(handle).id(),
						300.0, 0.0, 1.0,
						List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0))));
		java.util.ArrayList<ThermalSpeciesOffset> offsets = new java.util.ArrayList<>();
		for (int species = 0; species < chemistry.species().size(); species++) {
			String speciesId = chemistry.species(species).id();
			offsets.add(new ThermalSpeciesOffset("test:composition_offset/water_" + species,
					speciesId, water.id(), ExactRational.ZERO));
			offsets.add(new ThermalSpeciesOffset("test:composition_offset/ice_" + species,
					speciesId, ice.id(), ExactRational.ZERO));
		}
		long batchSvq = SolidVolume.SVQ_PER_CELL / 8L;
		ThermalPhaseTransition transition = new ThermalPhaseTransition(
				"test:phase/composition_ice_water", ice.id(), water.id(), 300.0,
				batchSvq, batchSvq, ExactRational.parse("15.625"),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 0.0, 200_000.0);
		return new ThermalRegistry(materials, offsets, List.of(transition),
				chemistry.fingerprint(), granular.fingerprint());
	}

	static CondensedLedger condensedLedger(FluidDomain domain, ChemicalRegistry chemistry) {
		FlipChemicalParticleState liquid = domain.exportChemicalParticleState();
		java.util.TreeMap<String, Long> species = new java.util.TreeMap<>();
		long[] liquidTotals = liquid.copySpeciesTotals();
		for (int handle = 0; handle < liquidTotals.length; handle++) if (liquidTotals[handle] > 0L) {
			species.put(chemistry.species(handle).id(), liquidTotals[handle]);
		}
		double residual = liquid.residualLedger();
		for (CoherentPhaseStore.Group group : domain.exportThermalParticleState().coherentGroups()) {
			if (group.composition() == null) continue;
			for (Map.Entry<String, Long> entry : group.composition().stableAmounts().entrySet()) {
				species.merge(entry.getKey(), entry.getValue(), Math::addExact);
			}
			residual += group.composition().massResidual();
		}
		byte[] indicators = liquid.copyIndicator();
		float[] positionVolume = liquid.materials().copyPositionVolume();
		double[] mass = liquid.materials().copyParticleMass();
		int indicatorCount = 0; float indicatorVolume = 0.0F; double indicatorMass = 0.0;
		for (int marker = 0; marker < indicators.length; marker++) if (indicators[marker] != 0) {
			indicatorCount++; indicatorVolume += positionVolume[marker * 4 + 3]; indicatorMass += mass[marker];
		}
		return new CondensedLedger(Map.copyOf(species), residual,
				indicatorCount, indicatorVolume, indicatorMass);
	}

	private record CondensedLedger(
			Map<String, Long> species, double massResidual,
			int indicatorCount, float indicatorVolume, double indicatorMass
	) { }

	static void phaseVolumeWorkWaitsForMechanicalProjection() {
		FluidMaterial liquid = FluidMaterial.withSurfaceTension("test:pending_liquid", 1.0,
				"test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(liquid));
		ThermalMaterialDefinition lower = new ThermalMaterialDefinition("test:thermal/pending_solid",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "test:pending_solid",
				300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 1.0E9)));
		ThermalMaterialDefinition upper = new ThermalMaterialDefinition("test:thermal/pending_liquid",
				ThermalMaterialDefinition.CarrierKind.LIQUID, liquid.id(),
				300.0, 100.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 1.0E9)));
		long batchSvq = SolidVolume.SVQ_PER_CELL / 8L;
		ThermalPhaseTransition transition = new ThermalPhaseTransition("test:phase/pending_volume",
				lower.id(), upper.id(), 300.0, batchSvq * 2L, batchSvq,
				ExactRational.parse("0.015625"), ThermalPhaseTransition.InterfaceRequirement.NONE,
				0.0, 10_000_000.0);
		ThermalRegistry registry = new ThermalRegistry(List.of(lower, upper), List.of(), List.of(transition), "", "");
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(2, 2, 2, config, 128);
		domain.setVolume(0, 0, 0, 0.5F);
		domain.bindThermal(registry, liquids, 295.0);
		ThermalAuditLedger before = domain.thermalAudit();
		FluidDomain.ThermalPhaseStats phase = domain.stepInertPhaseChangeOnly(16);
		PendingPhaseVolumeState pending = domain.exportPendingPhaseVolumeState();
		SignedDyadic signedCellVolume = pending.entries().stream()
				.map(PendingPhaseVolumeState.Entry::signedCellVolume)
				.reduce(SignedDyadic.ZERO, SignedDyadic::add);
		require(phase.operations() == 4 && signedCellVolume.equals(SignedDyadic.of(0.5))
				&& domain.hasPendingPhaseVolumeWork()
				&& domain.thermalAudit().mechanicalWork().equals(before.mechanicalWork()),
				"phase volume was not retained without prematurely booking projection work");
		FluidDomain lienDomain = new FluidDomain(2, 2, 2, config, 128);
		lienDomain.setVolume(0, 0, 0, 0.125F);
		lienDomain.bindThermal(registry, liquids, 295.0);
		PendingPhaseVolumeState restoredLien = new PendingPhaseVolumeState(8,
				List.of(new PendingPhaseVolumeState.Entry(0, SignedDyadic.of(0.125))));
		lienDomain.restorePendingPhaseVolumeState(restoredLien);
		double volumeBeforeBlockedExtraction = lienDomain.totalVolumeExact();
		FluidDomain.ExactVolumeTransfer blockedExtraction = lienDomain.tryExtractExactVolume(
				0, 0, 0, 0.125F, 0, 0);
		require(!blockedExtraction.transferred()
				&& lienDomain.totalVolumeExact() == volumeBeforeBlockedExtraction
				&& lienDomain.exportPendingPhaseVolumeState().entries().equals(restoredLien.entries()),
				"external extraction stranded pending phase mechanical work: transferred="
						+ blockedExtraction.transferred() + " volume=" + lienDomain.totalVolumeExact()
						+ "/" + volumeBeforeBlockedExtraction + " pending="
						+ lienDomain.exportPendingPhaseVolumeState().entries() + "/" + restoredLien.entries());

		FluidDomain.StepStats settlement = domain.stepMechanicsOnly();
		ThermalAuditLedger after = domain.thermalAudit();
		require(!domain.hasPendingPhaseVolumeWork()
				&& settlement.chemistryEvaluations() == 0
				&& settlement.thermalAxis().granularPhase().transfers() == 0
				&& settlement.condensedPhase().operations() == 0
				&& settlement.gasPhase().operations() == 0
				&& after.mechanicalWork().equals(before.mechanicalWork())
				&& domain.exactInternalEnergy().equals(after.expectedInventory()),
				"internal phase displacement was incorrectly booked as external pressure work");
		domain.stepMechanicsOnly();
		require(!domain.hasPendingPhaseVolumeWork()
				&& domain.thermalAudit().mechanicalWork().equals(after.mechanicalWork()),
				"cleared phase volume repeated its mechanical work on a later projection");
	}

	static void exactDyadicAuditDoesNotLoseCancellationRemainders() {
		var random = new java.util.Random(547219);
		long[] extremes = {0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 62, -(1L << 62)};
		for (int trial = 0; trial < 512; trial++) {
			long a = trial < extremes.length * extremes.length ? extremes[trial % extremes.length] : random.nextLong();
			long b = trial < extremes.length * extremes.length ? extremes[trial / extremes.length] : random.nextLong();
			var left = SignedDyadic.of(a, trial % 7 == 0 ? -1074 : trial % 5 - 30);
			var right = SignedDyadic.of(b, trial % 7 == 0 ? 971 : trial % 11 - 32);
			var wideLeft = new SignedDyadic(java.math.BigInteger.valueOf(a), trial % 7 == 0 ? -1074 : trial % 5 - 30);
			require(left.equals(wideLeft) && left.hashCode() == wideLeft.hashCode() && left.toString().equals(wideLeft.toString())
					&& left.signum() == left.significand().signum(), "compact dyadic changed canonical value identity");
			int unit = left.exponent() - 1;
			if (left.fitsLongUnits(unit)) require(left.longUnitsExact(unit) == left.significand().shiftLeft(1).longValueExact(),
					"compact dyadic changed its binary unit conversion");
			int exponent = Math.min(left.exponent(), right.exponent());
			var l = left.significand().shiftLeft(left.exponent() - exponent);
			var r = right.significand().shiftLeft(right.exponent() - exponent);
			require(left.add(right).equals(new SignedDyadic(l.add(r), exponent))
					&& left.subtract(right).equals(new SignedDyadic(l.subtract(r), exponent))
					&& Integer.signum(left.compareTo(right)) == Integer.signum(l.compareTo(r)),
					"primitive dyadic arithmetic differs from the independent integer oracle");
		}
		require(SignedDyadic.of(-1, 63).fitsLongUnits(0) && SignedDyadic.of(-1, 63).longUnitsExact(0) == Long.MIN_VALUE
				&& !SignedDyadic.of(1, 63).fitsLongUnits(0), "signed binary-unit conversion lost the asymmetric long range");
		SignedDyadic exact = SignedDyadic.sum(new double[]{1.0E16, 1.0, -1.0E16});
		require(exact.equals(SignedDyadic.of(1.0)), "exact dyadic audit lost a cancellation remainder");
		double[] varied = new double[8_192];
		SignedDyadic repeatedAdd = SignedDyadic.ZERO;
		for (int index = 0; index < varied.length; index++) {
			int exponent = index % 1_900 - 1_000;
			varied[index] = Math.scalb((index & 1) == 0 ? index + 1.0 : -(index + 0.5), exponent);
			repeatedAdd = repeatedAdd.add(SignedDyadic.of(varied[index]));
		}
		require(SignedDyadic.sum(varied).equals(repeatedAdd),
				"binned exact dyadic sum differs from repeated exact addition");
		require(SignedDyadic.sum(varied, 4_096).equals(
				SignedDyadic.sum(java.util.Arrays.copyOf(varied, 4_096))),
				"bounded exact dyadic sum consumed values outside its range");
		for (int length : new int[] {1, 2, 3, 8, 17, 1024}) for (int span : new int[] {0, 7, 8, 10, 11}) for (int signs = 0; signs < 3; signs++) {
			double[] values = new double[length + 1];
			SignedDyadic total = SignedDyadic.ZERO;
			for (int p = 0; p < length; p++) {
				values[p] = Math.scalb(Math.nextDown(2.0), p % (span + 1) - 540);
				if (signs == 1 || signs == 2 && p % 3 == 0) values[p] = -values[p];
				total = total.add(SignedDyadic.of(values[p]));
			}
			values[length] = Double.NaN;
			require(SignedDyadic.sum(values, length).equals(total),
					"signed-word sum changed exact inventory across its width or count limit");
		}
		double[] overflowBins = new double[2_051];
		java.util.Arrays.fill(overflowBins, 0, 1_025, Double.MAX_VALUE);
		java.util.Arrays.fill(overflowBins, 1_025, 2_050, -Double.MAX_VALUE);
		overflowBins[2_050] = Double.MIN_VALUE;
		for (double[] values : new double[][] {overflowBins, {0.0, -0.0},
				{Double.MIN_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE},
				{Double.MIN_NORMAL, Math.nextDown(Double.MIN_NORMAL), -Double.MIN_NORMAL},
				{Double.MAX_VALUE, Double.MAX_VALUE},
				{Double.MAX_VALUE, Double.MIN_VALUE, -Double.MAX_VALUE},
				{Float.MAX_VALUE, Float.MIN_VALUE, -Float.MAX_VALUE},
				{Math.scalb(1.0, 300), 3.25, -Math.scalb(1.0, 300), -1.0, Double.MIN_NORMAL}}) {
			SignedDyadic total = SignedDyadic.ZERO, remainder = SignedDyadic.of(Math.PI);
			for (double value : values) { total = total.add(SignedDyadic.of(value)); remainder = remainder.subtract(SignedDyadic.of(value)); }
			require(SignedDyadic.sum(values).equals(total)
					&& SignedDyadic.of(Math.PI).subtract(SignedDyadic.sum(values)).equals(remainder),
					"occupied exponent bins changed exact sum or emitted-energy remainder");
		}
		for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
			expectFailure(() -> SignedDyadic.sum(new double[] {0.0, invalid}), "dyadic sum accepted a non-finite value");
		for (double value : new double[]{0.0, -0.0, Double.MIN_VALUE, -17.25, Double.MAX_VALUE}) {
			require(Double.doubleToRawLongBits(SignedDyadic.of(value).toDouble())
					== Double.doubleToRawLongBits(value == 0.0 ? 0.0 : value),
					"dyadic conversion did not round-trip " + value);
		}
	}

	static void operatorEmptyClearsAuditedCondensedCarriers() {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidDomain staleGas = new FluidDomain(3, 3, 3, config, 256);
		staleGas.sealDomainBoundary();
		staleGas.setVolume(1, 1, 1, 0.5F);
		staleGas.bootstrapGasState("");
		require(staleGas.gasBoundary().nodeCount() == 1,
				"operator-clear stale-plan fixture did not create one sealed cavity");
		FluidDomain.OperatorClearPlan staleClear = staleGas.planOperatorClear();
		staleGas.setGasNodeStandardVolume(1, staleGas.gasBoundary().standardVolume(1) * 1.25);
		double retainedGas = staleGas.gasBoundary().standardVolume(1);
		int retainedMarkers = staleGas.particleCount();
		double retainedLiquid = staleGas.totalVolumeExact();
		expectFailure(() -> staleGas.commitOperatorClear(staleClear),
				"operator clear accepted a stale gas owner generation");
		require(staleGas.particleCount() == retainedMarkers
				&& Double.doubleToRawLongBits(staleGas.totalVolumeExact())
				== Double.doubleToRawLongBits(retainedLiquid)
				&& Double.doubleToRawLongBits(staleGas.gasBoundary().standardVolume(1))
				== Double.doubleToRawLongBits(retainedGas),
				"rejected stale operator clear changed condensed or gas truth");

		FluidDomain domain = new FluidDomain(3, 3, 3, config, 256);
		domain.setVolume(1, 1, 1, 0.75F);
		domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
		domain.bindChemistry(chemistry);
		domain.bindThermal(compositionPhaseThermalRegistry(liquids, granular, chemistry), liquids, 295.0);
		CondensedLedger before = condensedLedger(domain, chemistry);
		require(domain.stepInertPhaseChangeOnly(16).operations() > 0
				&& domain.totalCoherentSolidVolumeSvq() > 0L,
				"operator-empty fixture did not create coherent condensed inventory");
		// The operator command rejects this phase event until a real mechanical projection consumes it.
		domain.step(false, 0);
		require(!domain.hasPendingPhaseVolumeWork(),
				"operator-empty fixture did not consume pending phase work");
		long coherentSvq = domain.totalCoherentSolidVolumeSvq();
		FluidDomain.OperatorClear cleared = domain.commitOperatorClear(domain.planOperatorClear());
		long initialQuanta = before.species().values().stream().mapToLong(Long::longValue).sum();
		require(domain.particleCount() == 0 && domain.totalCoherentSolidVolumeSvq() == 0L
				&& cleared.condensed().coherentVolumeSvq() == coherentSvq
				&& cleared.condensed().coherentGroups() > 0
				&& domain.chemicalParcels().escapedSink().totalQuanta() == initialQuanta
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"operator empty did not jointly clear/audit liquid and coherent carriers: cleared=" + cleared
						+ " U=" + domain.exactInternalEnergy()
						+ " expected=" + domain.thermalAudit().expectedInventory());
	}

}
