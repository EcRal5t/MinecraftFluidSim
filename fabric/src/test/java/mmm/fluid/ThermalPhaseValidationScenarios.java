package mmm.fluid;

import mmm.thermal.ThermalDomainState;

import mmm.thermal.ThermalCarrierContext;

import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ExactRational;
import mmm.granular.SolidVolume;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.GranularRegistry;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.fluid.air.GasRegistry;
import mmm.units.SignedDyadic;
import mmm.thermal.CoherentPhaseStore;
import mmm.units.SolverUnitScale;
import mmm.thermal.ThermalAuditLedger;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalPhaseTransition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalSpeciesOffset;
import mmm.thermal.ThermalStateFunction;

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
				273.15, SolidVolume.SVQ_PER_CUBIC_METER, SolidVolume.SVQ_PER_CUBIC_METER * 917L / 1_000L, ExactRational.of(917),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 80_000.0, 120_000.0);
		ThermalRegistry registry = new ThermalRegistry(thermalMaterials(List.of(water, ice)), List.of(), List.of(melt), "", "");
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
				SolidVolume.SVQ_PER_CUBIC_METER, SolidVolume.SVQ_PER_CUBIC_METER * 917L / 1_000L,
				ExactRational.of(917), ThermalPhaseTransition.InterfaceRequirement.NONE,
				List.of(new ThermalPhaseTransition.EquilibriumPoint(3_000_000.0, 273.15),
						new ThermalPhaseTransition.EquilibriumPoint(4_000_000.0, 273.15)));
		ThermalRegistry highPressureRegistry = new ThermalRegistry(
				thermalMaterials(List.of(water, ice)), List.of(), List.of(highPressure), "", "");
		require(highPressureRegistry.transition(0).minimumPressurePa() == 3_000_000.0
				&& close(highPressure.equilibriumTemperatureK(3_500_000.0), 273.15),
				"solid/liquid curve was incorrectly required to cover standard pressure");
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 2.0E-3F);
		FluidDomain continuedPressure = new FluidDomain(2, 2, 2, config);
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
				thermalMaterials(List.of(water, ice, mercury)), List.of(), List.of(highPressure), "", "");
		FluidDomain mercuryOnly = new FluidDomain(2, 2, 2, config);
		mercuryOnly.setVolume(0, 0, 0, 0.5F);
		mercuryOnly.domainCollision.setMaterialInCell(0, 0, 0, waterAndMercury,
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
				thermalMaterials(List.of(water, ice, mercury)), List.of(), List.of(mixedMelt), "", "");
		for (float fill : new float[]{0.25F, 0.5F}) {
			FluidDomain mixedCell = new FluidDomain(2, 2, 2, config);
			mixedCell.setVolume(0, 0, 0, fill);
			mixedCell.domainCollision.setMaterialInCell(0, 0, 0, waterAndMercury,
					waterAndMercury.handle(BuiltinFluidMaterials.WATER.id()));
			require(mixedCell.domainInventory.tryInsertExactMaterial(0, 0, 0, fill, mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized(fill * BuiltinFluidMaterials.MERCURY.referenceDensity()), 0,
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
		ThermalRegistry registry = new ThermalRegistry(thermalMaterials(List.of(lower, upper)), List.of(), List.of(transition), "", "");

		FluidMaterial liquid = FluidMaterial.withSurfaceTension("test:liquid", 1.0,
				"test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(liquid));
		ThermalCarrierContext carriers = new ThermalCarrierContext() {
			private double internalEnergy;private long revision;
			@Override public double liquidInternalEnergy(int ordinal){return internalEnergy;}
			@Override public void setLiquidInternalEnergy(int ordinal,double energy){internalEnergy=energy;revision++;}
			@Override public long liquidInventoryRevision(){return revision;}
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
		coupled.planCoherentExternal(new CoherentPhaseStore.Group(
				0, lower.id(), 1L, mmm.units.Mass.fromKilograms(0.5), null, 0.0)).commit();
		require(close(coupled.temperatureInCell(0, carriers), 300.0),
				"liquid/coherent LTE did not derive a shared temperature");
		coupled.setTemperatureInCell(0, 310.0, carriers);
		require(close(coupled.temperatureInCell(0, carriers), 310.0)
				&& coupled.exactEnergy(1).equals(coupled.auditLedger().expectedInventory()),
				"liquid/coherent heat exchange did not close the shared energy audit");

		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 2.0E-3F);
		FluidDomain coherentCavity = new FluidDomain(3, 2, 2, config);
		coherentCavity.sealDomainBoundary();
		coherentCavity.setVolume(0, 0, 0, 0.5F);
		coherentCavity.bindGasRegistry(BuiltinGasDefinitions.createRegistry());
		coherentCavity.bootstrapGasState("");
		var cavityThermal = new ThermalRegistry(thermalMaterials(List.of(lower, upper,
				new ThermalMaterialDefinition("test:thermal/coherent_cavity_air",
						ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
						GasRegistry.STANDARD_TEMPERATURE_K, 0.0, 0.026,
						List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 718.0))))),
				List.of(), List.of(transition), "", "");
		coherentCavity.bindThermal(cavityThermal, liquids, 300.0);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) {
			coherentCavity.addCoherentPhaseExternal(1, y, z, lower.id(), SolidVolume.SVQ_PER_CELL, mmm.units.Mass.fromKilograms(0.125), null, 0);
		}
		FluidDomainState coherentSnapshot = coherentCavity.captureAuthoritativeState();
		int[] cavityMembership = coherentSnapshot.gas().pieceNodeOrdinal();
		int leftCavity = cavityMembership[8 * coherentCavity.domainFlowFields.cellIndex(0, 1, 1)];
		int rightCavity = cavityMembership[8 * coherentCavity.domainFlowFields.cellIndex(2, 1, 1)];
		require(leftCavity > 0 && rightCavity > 0 && leftCavity != rightCavity
				&& coherentCavity.exactInternalEnergy().equals(coherentCavity.thermalAudit().expectedInventory()),
				"coherent wall did not isolate the dry space on its two sides");
		FluidDomain coherentRestored = FluidDomain.restoreAuthoritativeState(coherentSnapshot);
		require(java.util.Arrays.equals(cavityMembership, coherentRestored.captureAuthoritativeState().gas().pieceNodeOrdinal())
				&& coherentRestored.totalCoherentSolidVolumeSvq() == 4 * SolidVolume.SVQ_PER_CELL
				&& coherentRestored.exactInternalEnergy().equals(coherentCavity.exactInternalEnergy())
				&& coherentRestored.thermalAudit().equals(coherentCavity.thermalAudit())
				&& coherentRestored.gasBoundary().auditClosed(),
				"gas restore must include the incoming coherent geometry before gas-dependent thermal energy");
		FluidDomain domain = new FluidDomain(2, 2, 2, config);
		domain.setVolume(0, 0, 0, 0.5F);
		domain.bindThermal(registry, liquids, 300.0);
		domain.addCoherentPhaseExternal(0, 0, 0, lower.id(),
				SolidVolume.SVQ_PER_CELL / 2L, mmm.units.Mass.fromKilograms(0.5), null, 0.0);
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
				thermalMaterials(List.of(lower, upper)), List.of(), List.of(cellTransition), "", "");
		FluidDomain pressureHandoff = new FluidDomain(2, 2, 2, config);
		pressureHandoff.setVolume(0, 0, 0, 1.0F);
		pressureHandoff.bindGasRegistry(BuiltinGasDefinitions.createRegistry());
		pressureHandoff.bootstrapGasState("");
		ThermalPhaseTransition continuedTransition = new ThermalPhaseTransition(
				"test:phase/continued_lower_upper", lower.id(), upper.id(), batchSvq, batchSvq,
				ExactRational.parse("0.015625"), ThermalPhaseTransition.InterfaceRequirement.NONE,
				List.of(new ThermalPhaseTransition.EquilibriumPoint(0.0, 310.0),
						new ThermalPhaseTransition.EquilibriumPoint(200_000.0, 300.0)));
		float projectedGaugePressure = 250_000.0F;
		double continuedMeltingPoint = continuedTransition.equilibriumTemperatureK(
				GasRegistry.STANDARD_PRESSURE_PA + projectedGaugePressure);
		pressureHandoff.bindThermal(new ThermalRegistry(
				thermalMaterials(List.of(lower, upper)), List.of(), List.of(continuedTransition), "", ""),
				liquids, continuedMeltingPoint + 5.0);
		// Persist the physical projection supplied to phase equilibrium.
		float[] projectedPressure = new float[pressureHandoff.cellCount];
		projectedPressure[pressureHandoff.domainFlowFields.cellIndex(0, 0, 0)] = projectedGaugePressure;
		pressureHandoff.pressureSystem.restoreLiquidPressure(new CondensedPhasePressureField(0, projectedPressure));
		FluidDomain pressureRestore = FluidDomain.restoreAuthoritativeState(
				pressureHandoff.captureAuthoritativeState());
		require(Math.abs(pressureRestore.pressureSystem.liquidPressureAt(pressureRestore.domainFlowFields.cellIndex(0, 0, 0), 0xff)
				- projectedGaugePressure) <= 1.0
				&& pressureRestore.stepInertPhaseChangeOnly(16).operations() == 0,
				"restored physical pressure did not retain the continued phase equilibrium");
		pressureRestore.setThermalTemperatureInCell(0, 0, 0, continuedMeltingPoint - 5.0);
		require(pressureRestore.stepInertPhaseChangeOnly(16).operations() > 0,
				"continued high-pressure phase curve skipped freezing below its melting point");

		FluidDomain phaseDomain = new FluidDomain(2, 2, 2, config);
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

		phaseDomain = FluidDomain.restoreAuthoritativeState(phaseDomain.captureAuthoritativeState());
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

		phaseDomain = FluidDomain.restoreAuthoritativeState(phaseDomain.captureAuthoritativeState());
		phaseDomain = advance(phaseDomain);
		require(close(phaseDomain.totalVolumeExact(), 1.0) && phaseDomain.totalCoherentSolidVolumeSvq() == 0
				&& phaseDomain.exactInternalEnergy().equals(phaseDomain.thermalAudit().expectedInventory()),
				"Restored melt could not continue through a complete mechanical step");

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
				thermalMaterials(List.of(roundoffIce, roundoffWater)), List.of(), List.of(roundoffTransition), "", "");
		FluidSolverConfig roundoffConfig = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 2.0E-3F);
		FluidDomain roundoffDomain = new FluidDomain(2, 2, 2, roundoffConfig);
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

		FluidDomain quantizedDomain = new FluidDomain(2, 2, 2, roundoffConfig);
		quantizedDomain.bindThermal(roundoffRegistry, roundoffLiquids, 273.15);
		double quantizedIceEnergy = new ThermalStateFunction(roundoffRegistry).internalEnergyAtTemperature(
				roundoffRegistry.materialHandle(roundoffIce.id()), 0.001, Map.of(), 273.16);
		quantizedDomain.addCoherentPhaseExternal(0, 0, 0, roundoffIce.id(), 146L,
				mmm.units.Mass.fromKilograms(0.001), null, quantizedIceEnergy);
		SignedDyadic quantizedEnergy = quantizedDomain.exactInternalEnergy();
		FluidDomain.ThermalPhaseStats unavailableMelt = quantizedDomain.stepInertPhaseChangeOnly(16);
		require(unavailableMelt.operations() == 0
				&& quantizedDomain.exactInternalEnergy().equals(quantizedEnergy)
				&& quantizedDomain.exactInternalEnergy().equals(quantizedDomain.thermalAudit().expectedInventory()),
				"an unrepresentable exact phase quantum stopped the region instead of leaving inventory unchanged");

		require(FluidDomain.minimumPhaseBatchesForCapacity(1_025L, 1_000L, -12L) == 3L,
				"shrinking phase planning did not clear transient joint-cell overfill before evaluating LTE");
		long markerSvq = SolidVolume.SVQ_PER_CELL / 8L;
		float tailedMarkerVolume = Math.nextUp(SolidVolume.toCellVolume(markerSvq));
		require(SolidVolume.cellVolumeSvqAtMost(tailedMarkerVolume) == markerSvq
				&& SolidVolume.cellVolumeSvqAtLeast(tailedMarkerVolume) == markerSvq + 1L,
				"phase SVQ bounds quantized away an authoritative float marker-volume tail");
	}

	static void condensedTransfersRequireActualMassChange() {
		for (long batchUnits : new long[]{1_000_000L, 2_000_000L}) {
			var liquid = FluidMaterial.withSurfaceTension("test:mass/water", 1000, "test:mass/miscible", 0, 90);
			var liquids = new FluidMaterialRegistry(List.of(liquid));
			var segments = List.of(new ThermalMaterialDefinition.CaloricSegment(150, 800, 4_180));
			var water = new ThermalMaterialDefinition("test:mass/water", ThermalMaterialDefinition.CarrierKind.LIQUID,
					liquid.id(), 300, 0, 0, segments);
			var ice = new ThermalMaterialDefinition("test:mass/ice", ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID,
					"test:mass/ice", 300, -334_000, 0, segments);
			var transition = new ThermalPhaseTransition("test:mass/freeze", ice.id(), water.id(), 300, 1_074, 1_074,
					ExactRational.of(batchUnits).divide(mmm.units.Mass.UNITS_PER_KILOGRAM),
					ThermalPhaseTransition.InterfaceRequirement.NONE, 10000, 3000000);
			var domain = new FluidDomain(3, 3, 3,
					new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1.0E-5F, .002F));
			restorePhaseTestCarriers(domain, liquids, new float[]{.125F}, new int[1]);
			domain.bindThermal(new ThermalRegistry(thermalMaterials(List.of(water, ice)), List.of(), List.of(transition), "", ""), domain.materialRegistry, 200);
			long sourceMass = domain.totalMassUnits();
			var stats = domain.stepInertPhaseChangeOnly(1);
			require(stats.operations() == 1 && domain.thermalState.coherentPhases().totalMassUnits() == batchUnits
					&& domain.totalMassUnits() == sourceMass - batchUnits
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"fixed-point phase transfer must debit every complete mass count: " + batchUnits);
			FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		}
	}

	static void condensedCandidatesPreserveActualThermalOwners() {
		var fluids = new FluidMaterialRegistry(List.of(
				FluidMaterial.withSurfaceTension("test:owner/water", 64, "test:owner/miscible", 0, 90),
				FluidMaterial.withSurfaceTension("test:owner/bath", 64, "test:owner/miscible", 0, 90)));
		var segments = List.of(new ThermalMaterialDefinition.CaloricSegment(200, 400, 1_000));
		var water = new ThermalMaterialDefinition("test:owner/water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:owner/water", 300, 1_000, 0, segments);
		var bath = new ThermalMaterialDefinition("test:owner/bath", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:owner/bath", 300, -1_000, 0, segments);
		var ice = new ThermalMaterialDefinition("test:owner/ice", ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID,
				"test:owner/ice", 301, 1_000, 0, segments);
		long batchSvq = SolidVolume.SVQ_PER_CELL / 64;
		var transition = new ThermalPhaseTransition("test:owner/freeze", ice.id(), water.id(), 301,
				batchSvq, batchSvq, ExactRational.parse("1/8"),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 10000, 3000000);
		var registry = new ThermalRegistry(thermalMaterials(List.of(water, bath, ice)), List.of(), List.of(transition), "", "");
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 64, 0, .05F, 100, 1.0E-5F, .002F));
		restorePhaseTestCarriers(domain, fluids, new float[]{.25F, .25F}, new int[]{0, 1});
		domain.bindThermal(registry, domain.materialRegistry, 300);
		var before = domain.exactInternalEnergy();
		var audit = domain.thermalAudit();
		var stats = domain.stepInertPhaseChangeOnly(1);
		require(stats.operations() == 1 && stats.frozenCells() == 1
				&& domain.totalCoherentSolidVolumeSvq() == batchSvq
				&& domain.totalCoherentMass() == .125
				&& domain.unitScale.kilograms(domain.totalMass()) + domain.totalCoherentMass() == 4
				&& Math.abs(domain.thermalTemperatureInCell(1, 1, 1) - 300.03125) <= 1E-4
				&& domain.exactInternalEnergy().subtract(before).equals(
						domain.thermalAudit().roundoffResidual().subtract(audit.roundoffResidual()))
				&& domain.thermalAudit().externalSource().equals(audit.externalSource())
				&& domain.thermalAudit().externalSink().equals(audit.externalSink())
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"condensed candidate changed the common reference-energy and heat-bath response");
		for (int marker = 0; marker < domain.particleCount(); marker++)
			require(Double.isFinite(domain.domainCapabilities.thermalMarkerTemperature(marker))
					&& domain.domainCapabilities.thermalMarkerTemperature(marker) > 0, "freeze published invalid liquid energy");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
				&& restored.thermalAudit().equals(domain.thermalAudit())
				&& restored.thermalState.coherentPhases().snapshot().equals(domain.thermalState.coherentPhases().snapshot())
				&& Math.abs(restored.thermalTemperatureInCell(1, 1, 1) - 300.03125) <= 1E-4,
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
			// Cell stock and coherent groups exchange their complete integer inventory.
			var stats = domain.stepInertPhaseChangeOnly(batches);
			var after = condensedLedger(domain, chemistry);
			require(stats.operations() == batches && stats.signedBatches() == (freezing ? batches : -batches)
					&& stats.frozenCells() == (freezing ? 1 : 0) && stats.meltedCells() == (freezing ? 0 : 1),
					"complete condensed integer candidates must remain representable: " + stats);
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
				mmm.fluid.air.BuiltinGasDefinitions.AIR, 0, 718);
		long batchSvq = SolidVolume.SVQ_PER_CELL / 16;
		var transition = new ThermalPhaseTransition("test:integer/phase", ice.id(), water.id(), 300,
				batchSvq, batchSvq, ExactRational.parse("125/16"),
				ThermalPhaseTransition.InterfaceRequirement.NONE, 10_000, 3_000_000);
		var thermal = new ThermalRegistry(thermalMaterials(List.of(water, oil, ice, air)), List.of(
				new ThermalSpeciesOffset("test:integer/wh", BuiltinChemicalDefinitions.H2O, water.id(), ExactRational.ZERO),
				new ThermalSpeciesOffset("test:integer/ih", BuiltinChemicalDefinitions.H2O, ice.id(), ExactRational.ZERO)),
				List.of(transition), chemistry.fingerprint(), "");
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 1_000, 0, .05F, 100, 1E-5F, .002F));
		domain.sealDomainBoundary();
		restorePhaseTestCarriers(domain, liquids,
				freezing ? new float[]{SolidVolume.toCellVolume(batches * batchSvq), .25F} : new float[]{.125F, .125F},
				new int[]{0, 1});
		domain.bindChemistry(chemistry);
		if (freezing) {
			domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.H2O, 1L));
			if (batches == 4) domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.H2O, 4L));
		}
		domain.bindThermal(thermal, domain.materialRegistry, freezing ? 200 : 500);
		if (!freezing) {
			long quanta = batches == 2 ? 1 : 4;
			double mass = transition.massKgPerBatch().multiply(batches).toDouble();
			double speciesMass = chemistry.species(chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O))
					.molarMassKgPerMol().multiply(quanta).divide(mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL).toDouble();
			var composition = new CoherentPhaseStore.Composition(
					List.of(new CoherentPhaseStore.Component(BuiltinChemicalDefinitions.H2O, quanta)),
					mmm.units.Mass.fromKilograms(mass - speciesMass));
			double energy = new ThermalStateFunction(thermal).internalEnergyAtTemperature(
					thermal.materialHandle(ice.id()), mass, Map.of(BuiltinChemicalDefinitions.H2O, quanta), 500);
			domain.addCoherentPhaseExternal(1, 1, 1, ice.id(), batchSvq * batches, mmm.units.Mass.fromKilograms(mass), composition, energy);
		}
		domain.captureAuthoritativeState();
		return domain;
	}

	private static ThermalMaterialDefinition condensedIntegerMaterial(String id,
			ThermalMaterialDefinition.CarrierKind kind, String carrier, double reference, double capacity) {
		return new ThermalMaterialDefinition(id, kind, carrier, 300, reference, .1,
				List.of(new ThermalMaterialDefinition.CaloricSegment(150, 800, capacity)));
	}

	static void coherentExternalMergeIsPreflightedAndAudited() {
		var components = List.of(new CoherentPhaseStore.Component("test:a", 1));
		for (double residual : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
			boolean rejected = false;
			try { new CoherentPhaseStore.Composition(components, mmm.units.Mass.fromKilograms(residual)); }
			catch (IllegalArgumentException expected) { rejected = true; }
			require(rejected, "coherent composition accepted a non-finite residual before record field initialization");
		}
		for (long residual : new long[]{0L, 1L, -1L, Long.MAX_VALUE, -12_500_000_000L}) {
			var composition = new CoherentPhaseStore.Composition(components, residual);
			require(composition.massResidualUnits() == residual, "Coherent composition changed integer mass residual");
		}
		var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		var chemistry = new ChemicalRegistry(List.of(
				new mmm.chemistry.ChemicalSpecies("test:a", Map.of("A", 1), 0, ExactRational.parse("0.001"), 0),
				new mmm.chemistry.ChemicalSpecies("test:b", Map.of("A", 1), 0, ExactRational.parse("0.001"), 0)),
				List.of(new mmm.chemistry.ChemicalPhaseDefinition("test:water", BuiltinFluidMaterials.WATER.id(),
						List.of("test:a", "test:b"), Map.of("test:a", ExactRational.ONE))), List.of(
				new mmm.chemistry.ChemicalReaction("test:reaction", mmm.chemistry.ChemicalReaction.Kind.INSTANTANEOUS,
						"test:water", Map.of("test:a", 1), Map.of("test:b", 1), ExactRational.ZERO)));
		var config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 2.0E-3F);
		String phase = "test:thermal/coherent_merge";
		for (int compositionCase = 0; compositionCase < 3; compositionCase++) {
			var water = new ThermalMaterialDefinition("test:thermal/merge_water",
					ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
					300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1_000, 1)));
			var solid = new ThermalMaterialDefinition(phase, ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID,
					"test:solid", 300, 0, 0, water.caloricSegments());
			var offsets = List.of(new ThermalSpeciesOffset("test:offset/a", "test:a", phase, ExactRational.ZERO),
					new ThermalSpeciesOffset("test:offset/b", "test:b", phase, ExactRational.ZERO));
			var registry = new ThermalRegistry(thermalMaterials(List.of(water, solid)), offsets, List.of(), chemistry.fingerprint(), "");
			var domain = new FluidDomain(3, 3, 3, config);
			domain.bindMaterialRegistry(liquids); domain.bindChemistry(chemistry);
			domain.bindThermal(registry, liquids, 300);
			double firstMass = 1.0, secondMass = 1.0;
			CoherentPhaseStore.Composition first = compositionCase == 0 ? null : new CoherentPhaseStore.Composition(
					List.of(new CoherentPhaseStore.Component("test:a", 1)), mmm.units.Mass.fromKilograms(firstMass - 1e-9));
			CoherentPhaseStore.Composition second = compositionCase == 0 ? null : new CoherentPhaseStore.Composition(
					List.of(new CoherentPhaseStore.Component(compositionCase == 1 ? "test:a" : "test:b", 1)),
					mmm.units.Mass.fromKilograms(secondMass - 1e-9));
			domain.addCoherentPhaseExternal(1, 1, 1, phase, 3, mmm.units.Mass.fromKilograms(firstMass), first, 128);
			var before = domain.thermalState.coherentPhases().snapshot();
			var revision = domain.thermalState.thermalRevision();
			var audit = domain.thermalAudit();
			boolean rejected = false;
			try { domain.addCoherentPhaseExternal(1, 1, 1, phase, 3, mmm.units.Mass.fromKilograms(secondMass), second, -1_000); }
			catch (IllegalArgumentException expected) { rejected = true; }
			require(rejected && domain.thermalState.coherentPhases().snapshot().equals(before)
					&& domain.thermalState.thermalRevision().equals(revision) && domain.thermalAudit().equals(audit)
					&& domain.exactInternalEnergy().equals(audit.expectedInventory()),
					"coherent merge committed an invalid final thermal owner or changed a rejected candidate");

			// A valid merge must book its raw storage loss, including chemical groups.
			var ordinary = new ThermalRegistry(thermalMaterials(List.of(water, new ThermalMaterialDefinition(phase,
					ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "test:solid", 300, 0, 0,
					water.caloricSegments()))), List.of(
					new ThermalSpeciesOffset("test:offset/a", "test:a", phase, ExactRational.of(1_000_000)),
					new ThermalSpeciesOffset("test:offset/b", "test:b", phase, ExactRational.of(-1_000_000))),
					List.of(), chemistry.fingerprint(), "");
			var accepted = new FluidDomain(3, 3, 3, config);
			accepted.bindMaterialRegistry(liquids); accepted.bindChemistry(chemistry);
			accepted.bindThermal(ordinary, liquids, 300);
			accepted.addCoherentPhaseExternal(1, 1, 1, phase, 3, mmm.units.Mass.fromKilograms(firstMass), first, 128);
			double additionEnergy = compositionCase == 2 ? -1 + 1e-15 : 1e-15;
			accepted.addCoherentPhaseExternal(1, 1, 1, phase, 3, mmm.units.Mass.fromKilograms(secondMass), second, additionEnergy);
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
			var restored = new FluidDomain(3, 3, 3, config);
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
				100, 1.0E-5F, 2.0E-3F);
		FluidDomain domain = new FluidDomain(3, 3, 3, config);
		domain.setVolume(1, 1, 1, 0.75F);
		domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
		domain.bindChemistry(chemistry);
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.H2O, 1_000_000L));
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long sandSvq = granular.material(sand).maximumActiveParcelSvq();
		double sandMass = sandSvq / (double) SolidVolume.SVQ_PER_CUBIC_METER
				* granular.material(sand).referenceDensity();
		require(domain.tryInsertExactGranular(1, 1, 1, sand, 0, sandSvq, mmm.units.Mass.fromKilograms(sandMass)).inserted(),
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
				&& domain.totalGranularSolidVolumeSvq() == initialGranularSvq
				&& close(domain.totalGranularMass(), initialGranularMass)
				&& close(massScale.kilograms(domain.totalMass()) + domain.totalCoherentMass(), initialMass)
				&& Math.abs(domain.exactInternalEnergy().subtract(coldEnergy).toDouble()) <= 1.0E-8
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"composition/granular freeze did not conserve species, solid and thermal inventories: stats="
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
				100, 1.0E-5F, 2.0E-3F);
		var species = new java.util.ArrayList<mmm.chemistry.ChemicalSpecies>();
		var ids = new java.util.ArrayList<String>();
		for (int i = 0; i < 17; i++) {
			String id = "test:s" + i; ids.add(id);
			species.add(new mmm.chemistry.ChemicalSpecies(id, Map.of("A", 1), 0, ExactRational.parse("0.001"), 0));
		}
		var chemistry = new ChemicalRegistry(species, List.of(new mmm.chemistry.ChemicalPhaseDefinition(
				"test:phase", "mmm:water", ids, Map.of(ids.getFirst(), ExactRational.ONE))),
				List.of(new mmm.chemistry.ChemicalReaction("test:r",
						mmm.chemistry.ChemicalReaction.Kind.INSTANTANEOUS, "test:phase",
						Map.of("test:s0", 1), Map.of("test:s1", 1), ExactRational.ZERO)));
		var thermal = compositionPhaseThermalRegistry(liquids, granular, chemistry);
		for (int divisor = 1; divisor <= 2; divisor++) {
			var domain=new FluidDomain(3,3,3,config);
			restorePhaseTestCarriers(domain,liquids,0,0,0,new float[]{.125F},new int[1]);domain.bindChemistry(chemistry);
			var amounts=new java.util.TreeMap<String,Long>();
			for(int speciesIndex=0;speciesIndex<16;speciesIndex++)amounts.put(ids.get(speciesIndex),10L);
			domain.setMarkerChemical(0,amounts);
			var transition = thermal.transitions().getFirst();
			var bounded = new ThermalRegistry(thermalMaterials(thermal.materials()), thermal.speciesOffsets(), List.of(
					new ThermalPhaseTransition(transition.id(), transition.lowerEnergyMaterialId(), transition.upperEnergyMaterialId(),
							300.0, transition.lowerVolumeSvqPerBatch() / divisor, transition.upperVolumeSvqPerBatch() / divisor,
							transition.massKgPerBatch().divide(divisor), transition.interfaceRequirement(), 0, 200_000)),
					chemistry.fingerprint(), granular.fingerprint());
			domain.bindThermal(bounded, domain.materialRegistry, 280.0);
			var energy = domain.exactInternalEnergy();
			require(domain.stepInertPhaseChangeOnly(1).operations() == 1
					&& domain.exactInternalEnergy().equals(energy),
					"Supported cell and target compositions did not advance within the one-operation budget");
		}

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
		return new ThermalRegistry(thermalMaterials(materials), offsets, List.of(transition),
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
		return new CondensedLedger(Map.copyOf(species), residual);
	}

	private record CondensedLedger(Map<String, Long> species, double massResidual) { }

	static void phaseVolumeWorkWaitsForMechanicalProjection() {
		var work = new PendingPhaseVolumeWork(8);
		work.add(0, SignedDyadic.of(.125));
		work.add(1, SignedDyadic.of(-.25));
		var captured = work.capture();
		work.add(0, SignedDyadic.of(-.125));
		require(work.hasWork() && !work.isPending(0) && work.isPending(1)
				&& captured.entries().size() == 2,
				"phase-volume cancellation changed another cell or its captured state");
		work.add(1, SignedDyadic.of(.25));
		require(!work.hasWork() && work.capture().isEmpty(),
				"exactly cancelled phase-volume work remained pending");
		phaseLiquidGeometryMatchesPublishedMarkers();
		FluidMaterial liquid = FluidMaterial.withSurfaceTension("test:pending_liquid", 1.0,
				"test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(liquid));
		ThermalMaterialDefinition lower = new ThermalMaterialDefinition("test:thermal/pending_solid",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "test:pending_solid",
				300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		ThermalMaterialDefinition upper = new ThermalMaterialDefinition("test:thermal/pending_liquid",
				ThermalMaterialDefinition.CarrierKind.LIQUID, liquid.id(),
				300.0, 100.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		long batchSvq = SolidVolume.SVQ_PER_CELL / 8L;
		ThermalPhaseTransition transition = new ThermalPhaseTransition("test:phase/pending_volume",
				lower.id(), upper.id(), 300.0, batchSvq * 2L, batchSvq,
				ExactRational.parse("0.015625"), ThermalPhaseTransition.InterfaceRequirement.NONE,
				0.0, 10_000_000.0);
		ThermalRegistry registry = new ThermalRegistry(thermalMaterials(List.of(lower, upper)), List.of(), List.of(transition), "", "");
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 2.0E-3F);
		FluidDomain domain = new FluidDomain(2, 2, 2, config);
		domain.setVolume(0, 0, 0, 0.5F);
		domain.bindThermal(registry, liquids, 280.0);
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
		FluidDomain lienDomain = new FluidDomain(2, 2, 2, config);
		lienDomain.setVolume(0, 0, 0, 0.125F);
		lienDomain.bindThermal(registry, liquids, 280.0);
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

	private static void phaseLiquidGeometryMatchesPublishedMarkers(){
		for(float amount:new float[]{.46875F,.53125F,.75F,0}){
			var domain=new FluidDomain(3,3,3,new FluidSolverConfig(1,1000,0,.001F,100,1e-6F,.002F));
			domain.setVolume(1,1,1,.5F);domain.setVolume(2,1,1,.25F);domain.setVolume(2,0,1,.25F);
			var neighbour=domain.liquidCells.stocksInCell(domain.domainFlowFields.cellIndex(2,1,1));
			domain.setVolume(1,1,1,amount);domain.pressureSystem.prepareGeometry();
			int cell=domain.domainFlowFields.cellIndex(1,1,1);
			require(Math.abs(domain.pressureSystem.phaseGeometry().liquidFractionExact(cell)-amount)<1e-7,"Published phase geometry missed committed cell volume");
			require(neighbour.equals(domain.liquidCells.stocksInCell(domain.domainFlowFields.cellIndex(2,1,1))),"Local phase geometry preparation edited neighbouring inventory");
			var restored=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());restored.pressureSystem.prepareGeometry();
			require(domain.liquidCells.stocks().equals(restored.liquidCells.stocks())&&restored.gasBoundary().auditClosed(),"Phase geometry restore changed liquid or gas inventory");
		}
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
		operatorClearBindsTheCompleteGeometry();
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 2.0E-3F);
		FluidDomain staleGas = new FluidDomain(3, 3, 3, config);
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

		FluidDomain domain = new FluidDomain(3, 3, 3, config);
		domain.setVolume(1, 1, 1, 0.75F);
		domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
		domain.bindChemistry(chemistry);
		domain.bindThermal(compositionPhaseThermalRegistry(liquids, granular, chemistry), liquids, 295.0);
		CondensedLedger before = condensedLedger(domain, chemistry);
		require(domain.stepInertPhaseChangeOnly(16).operations() > 0
				&& domain.totalCoherentSolidVolumeSvq() > 0L,
				"operator-empty fixture did not create coherent condensed inventory");
		// The operator command rejects this phase event until a real mechanical projection consumes it.
		domain = advance(domain);
		require(!domain.hasPendingPhaseVolumeWork(),
				"operator-empty fixture did not consume pending phase work");
		long coherentSvq = domain.totalCoherentSolidVolumeSvq();
		FluidDomain.OperatorClear cleared = domain.commitOperatorClear(domain.planOperatorClear());
		long initialQuanta = before.species().values().stream().mapToLong(Long::longValue).sum();
		require(domain.particleCount() == 0 && domain.totalCoherentSolidVolumeSvq() == 0L
				&& cleared.condensed().coherentVolumeSvq() == coherentSvq
				&& cleared.condensed().coherentGroups() > 0
				&& domain.chemistry().escapedSink().totalQuanta() == initialQuanta
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"operator empty did not jointly clear/audit liquid and coherent carriers: cleared=" + cleared
						+ " U=" + domain.exactInternalEnergy()
						+ " expected=" + domain.thermalAudit().expectedInventory());
	}

	static void operatorClearBindsTheCompleteGeometry() {
		var d = new FluidDomain(2, 2, 2, new FluidSolverConfig(1, 1000, 0, .001F, 100, 1e-5F, .002F));
		d.sealDomainBoundary(); d.bootstrapGasState("");
		var plan = d.planOperatorClear();
		d.setSolidMicroMask(0, 0, 0, 1);
		var edited = d.captureAuthoritativeState();
		expectFailure(() -> d.commitOperatorClear(plan), "Operator clear accepted a plan prepared before the collision edit");
		FluidDomainStateAssertions.assertSameState(edited, d.captureAuthoritativeState(),
				"Rejected geometry-stale clear changed committed inventories, energy or geometry");
		var current = d.planOperatorClear(); d.commitOperatorClear(current);
		require(d.gasState.network.availableVolume(1) == 7.875, "Current operator clear did not use the actual geometric capacity");
	}

	private static FluidDomain advance(FluidDomain domain) {
		return new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0, domain.captureAuthoritativeState(), List.of(), false, 0)).claimPreparedCpuDomain();
	}

}
