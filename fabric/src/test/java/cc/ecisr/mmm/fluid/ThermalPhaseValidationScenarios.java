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
		FluidDomain unsupportedPressure = new FluidDomain(2, 2, 2, config, 128);
		unsupportedPressure.setVolume(0, 0, 0, 0.5F);
		unsupportedPressure.bindGasRegistry(BuiltinGasDefinitions.createRegistry());
		unsupportedPressure.bootstrapGasState("");
		unsupportedPressure.bindThermal(highPressureRegistry,
				new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER)), 273.15);
		double unsupportedEnergy = unsupportedPressure.totalInternalEnergy();
		FluidDomain.ThermalPhaseStats unsupported = unsupportedPressure.stepInertPhaseChangeOnly(16);
		require(unsupported.operations() == 0 && unsupported.signedBatches() == 0L
				&& Double.doubleToRawLongBits(unsupportedPressure.totalInternalEnergy())
				== Double.doubleToRawLongBits(unsupportedEnergy),
				"an inapplicable solid/liquid curve changed inventory or energy");

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
		FluidDomain mixedCell = new FluidDomain(2, 2, 2, config, 128);
		mixedCell.setVolume(0, 0, 0, 0.25F);
		mixedCell.setMaterialInCell(0, 0, 0, waterAndMercury,
				waterAndMercury.handle(BuiltinFluidMaterials.WATER.id()));
		require(mixedCell.tryInsertExactMaterial(0, 0, 0, 0.25F,
				0.25 * BuiltinFluidMaterials.MERCURY.referenceDensity(), 0,
				waterAndMercury.handle(BuiltinFluidMaterials.MERCURY.id())).transferred(),
				"mixed condensed-phase fixture could not insert mercury");
		mixedCell.bindThermal(mixedRegistry, waterAndMercury, 273.15);
		mixedCell.setThermalTemperatureInCell(0, 0, 0, 260.0);
		double mercuryBefore = mixedCell.exactVolumeNear(0, 0, 0, 0,
				waterAndMercury.handle(BuiltinFluidMaterials.MERCURY.id()));
		FluidDomain.ThermalPhaseStats mixed = mixedCell.stepInertPhaseChangeOnly(16);
		double mercuryAfter = mixedCell.exactVolumeNear(0, 0, 0, 0,
				waterAndMercury.handle(BuiltinFluidMaterials.MERCURY.id()));
		require(mixed.operations() > 0 && mixed.frozenCells() == 1
				&& close(mercuryAfter, mercuryBefore),
				"ice/water phase change rejected or modified its mercury heat bath: "
						+ mixed + ", mercury=" + mercuryBefore + " -> " + mercuryAfter);
	}

	static void coherentPhaseInventoryAndExactBatchPlanning() {
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
		pressureHandoff.bindThermal(cellRegistry, liquids, 295.0);
		pressureHandoff.pressureReference = 250_000.0F;
		FluidDomain pressureRestore = FluidDomain.restoreAuthoritativeState(
				pressureHandoff.captureAuthoritativeState());
		require(pressureRestore.stepInertPhaseChangeOnly(16).operations() == 0,
				"thermal-only handoff replaced an out-of-curve real pressure projection with standard pressure");

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

	static void compositionAndGranularPhaseChangeConservesJointTruth() {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(3, 3, 3, config, 256);
		domain.setVolume(1, 1, 1, 0.75F);
		domain.bindGranular(granular, liquids);
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
				&& after.mechanicalWork().compareTo(before.mechanicalWork()) < 0
				&& domain.exactInternalEnergy().equals(after.expectedInventory()),
				"mechanics-only projection did not consume expansion exactly once without new phase work");
		domain.stepMechanicsOnly();
		require(!domain.hasPendingPhaseVolumeWork()
				&& domain.thermalAudit().mechanicalWork().equals(after.mechanicalWork()),
				"cleared phase volume repeated its mechanical work on a later projection");
	}

	static void exactDyadicAuditDoesNotLoseCancellationRemainders() {
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
		domain.bindGranular(granular, liquids);
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
