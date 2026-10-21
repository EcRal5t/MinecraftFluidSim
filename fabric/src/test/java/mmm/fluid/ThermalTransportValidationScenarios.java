package mmm.fluid;

import mmm.thermal.ThermalDomainState;

import mmm.thermal.ThermalCarrierContext;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalRegistry;
import mmm.granular.SolidVolume;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.GranularRegistry;
import mmm.granular.GranularTransferLedger;
import mmm.units.SignedDyadic;
import mmm.thermal.GranularEnergyColumn;
import mmm.thermal.LiquidThermalExpansion;
import mmm.units.SolverUnitScale;
import mmm.thermal.FiniteThermalBodyState;
import mmm.thermal.FiniteThermalExchange;
import mmm.thermal.FiniteDeviceExchangeCoordinator;
import mmm.thermal.ThermalCellGrid;
import mmm.thermal.ThermalAuditLedger;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalStateFunction;
import mmm.thermal.ThermalTransport;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** One retained selector for M9 definition, state-function and later transport/phase invariants. */

abstract class ThermalTransportValidationScenarios extends ThermalValidationSupport {
	static void containerTransferCarriesAuthoritativeEnergy() {
		liquidInitializationValidatesStoredEnergy();
		thermalHandoffPreservesInsertionTemperature();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/container_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 400.0, 4_000.0)));
		ThermalRegistry registry = new ThermalRegistry(List.of(water));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(4, 3, 3, config, 512);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.bindThermal(registry, liquids, 300.0);
		domain.removeAllWaterExact();

		double importedEnergy = SolverUnitScale.forCellSize(config.cellSize()).kilograms(1_000.0)
				* 4_000.0 * 20.0;
		FluidDomain.ExactVolumeTransfer inserted = domain.domainInventory.tryInsertExactVolume(
				1, 1, 1, 1.0F, 1, 0, false, importedEnergy);
		require(inserted.transferred() && close(inserted.internalEnergy(), importedEnergy)
				&& close(domain.thermalTemperatureInCell(1, 1, 1), 320.0)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"explicit container insertion did not assign item U through the state function");

		FluidDomain.ExactVolumeTransfer extracted = domain.tryExtractExactVolume(1, 1, 1, 1.0F, 1, 0);
		require(extracted.transferred() && close(extracted.internalEnergy(), importedEnergy)
				&& domain.particleCount() == 0 && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"container extraction did not return the exact removed marker energy");
		FluidDomain.ExactVolumeTransfer restored = domain.domainInventory.tryInsertExactVolume(
				1, 1, 1, 1.0F, 1, 0, false, extracted.internalEnergy());
		require(restored.transferred() && close(domain.thermalTemperatureInCell(1, 1, 1), 320.0)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"container energy did not survive an extract/reinsert round trip");
		require(Math.abs(domain.thermalAudit().roundoffResidual().toDouble())<=8*Math.ulp(importedEnergy),
				"Container transfer booked a finite energy discrepancy as storage roundoff");

		FluidDomain.MaterialDisplacement collision = domain.setSolidMicroMaskMaterial(1, 1, 1, 0xff);
		require(close(collision.internalEnergy(), importedEnergy) && collision.portions().size() == 1
				&& domain.particleCount() == 0,
				"collision displacement did not carry the removed material energy ledger");
		domain.setSolidMicroMaskMaterial(1, 1, 1, 0);
		FluidDomain.ExactMixtureTransfer relocated = domain.domainInventory.tryInsertExactMixture(
				1, 1, 1, collision.portions(), 1, false, collision.internalEnergy());
		require(relocated.transferred() && close(domain.thermalTemperatureInCell(1, 1, 1), 320.0)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"collision relocation did not restore material U without a guessed temperature");
	}

	private static void liquidInitializationValidatesStoredEnergy() {
		var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		var water = new ThermalMaterialDefinition("test:initial/water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				BuiltinFluidMaterials.WATER.id(), 300, 0, 0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(1, 2000, 4_180)));
		var registry = new ThermalRegistry(List.of(water));
		var config = new FluidSolverConfig(.5F, 1000F, 0F, .05F, 100, 1e-5F, .002F, .8F);
		var domain = new FluidDomain(4, 4, 4, config, 512);
		restorePhaseTestCarriers(domain, liquids, 2, 2, 2, new float[]{.0625F}, new int[1]);
		var initial = domain.exportLiquidCellState();
		expectFailure(() -> domain.bindThermal(registry, liquids, 0),
				"liquid initialization accepted a nonpositive source temperature");
		require(domain.thermalState == null && domain.exportLiquidCellState().stocks().equals(initial.stocks()),
				"rejected thermal initialization published a binding or changed liquid inventory");
		domain.bindThermal(registry, liquids, 300);
		domain.setThermalTemperatureInCell(2, 2, 2, 380);
		var snapshot = domain.exportThermalParticleState();
		var changedSource = new FlipThermalParticleState(4, 4, 4, .5F, snapshot.particleCount(),
				registry.fingerprint(), 310, snapshot.copyInternalEnergy(), snapshot.copyActiveParcelEnergy(),
				snapshot.copyDepositEnergy(), snapshot.coherentGroups(), snapshot.copyGasNodeEnergy(), snapshot.audit());
		var restored = new FluidDomain(4, 4, 4, config, 512);
		restored.restoreMaterialParticleState(domain.exportMaterialParticleState());
		restored.restoreThermalParticleState(changedSource, registry);
		require(restored.thermalState.insertionTemperatureK() == 310
				&& Math.abs(restored.thermalTemperatureInCell(2, 2, 2) - 380) <= 1E-4
				&& restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
				&& restored.thermalAudit().equals(domain.thermalAudit()),
				"liquid restore substituted configured insertion temperature for the saved actual U");
	}

	private static void thermalHandoffPreservesInsertionTemperature() {
		var granular = BuiltinGranularDefinitions.createRegistry();
		var liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		var config = new FluidSolverConfig(.5F, 1000F, 0F, .05F, 100, 1e-5F, .002F, .8F);
		for (boolean narrow : new boolean[]{false, true}) {
			var materials = new java.util.ArrayList<ThermalMaterialDefinition>();
			for (var fluid : liquids.materials()) materials.add(new ThermalMaterialDefinition(
					"test:handoff/" + fluid.id().replace(':', '_'), ThermalMaterialDefinition.CarrierKind.LIQUID,
					fluid.id(), fluid.id().equals(BuiltinFluidMaterials.WATER.id()) ? (narrow ? 500 : 293.15) : 300,
					0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 600, 1))));
			for (var material : granular.materials()) materials.add(new ThermalMaterialDefinition(
					"test:handoff/" + material.id().replace(':', '_'), ThermalMaterialDefinition.CarrierKind.GRANULAR,
					material.id(), 300, 20, 0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(250, narrow ? 350 : 600, 1))));
			var registry = new ThermalRegistry(materials, List.of(), List.of(), "", granular.fingerprint());
			var source = new FluidDomain(4, 4, 4, config, 512);
			source.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
			source.setVolume(2, 2, 2, .001F);
			int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
			long svq = 262144; double mass = 5.078125;
			require(source.tryInsertExactGranular(1, 1, 1, sand, 0, svq, mmm.units.Mass.fromKilograms(mass)).inserted(),
					"thermal handoff could not create its initial grain");
			source.bindThermal(registry, liquids, 300);
			var snapshot = source.exportThermalParticleState();
			require(snapshot.insertionTemperatureK() == 300, "thermal capture omitted the configured insertion temperature");
			var materialSnapshot = source.exportMaterialParticleState();
			var granularSnapshot = source.exportGranularState();
			var target = new FluidDomain(4, 4, 4, config, 512);
			target.restoreMaterialParticleState(source.exportMaterialParticleState());
			target.restoreGranularState(source.exportGranularState(), granular, liquids, BuiltinFluidMaterials.createRegistry());
			target.restoreThermalParticleState(snapshot, registry);
			require(target.thermalState.insertionTemperatureK() == 300
					&& target.exactInternalEnergy().equals(source.exactInternalEnergy())
					&& target.thermalAudit().equals(source.thermalAudit()),
					"public thermal restore changed the source configuration or its inventory");
			var backend = FluidDomain.restoreAuthoritativeState(source.captureAuthoritativeState());
			require(backend.exportThermalParticleState().insertionTemperatureK() == 300
					&& backend.exactInternalEnergy().equals(source.exactInternalEnergy()),
					"complete backend handoff changed the configured insertion temperature");
			require(source.authoritativeLayout().equals(source.captureAuthoritativeState().layout()),
					"live and captured backend configuration identities differ");
			var changedTemperature = new FlipThermalParticleState(4, 4, 4, .5F, snapshot.particleCount(), registry.fingerprint(),
					301, snapshot.copyInternalEnergy(), snapshot.copyActiveParcelEnergy(), snapshot.copyDepositEnergy(),
					snapshot.coherentGroups(), snapshot.copyGasNodeEnergy(), snapshot.audit());
			var changed = new FluidDomain(4, 4, 4, config, 512);
			changed.restoreMaterialParticleState(materialSnapshot);
			changed.restoreGranularState(granularSnapshot, granular, liquids, BuiltinFluidMaterials.createRegistry());
			changed.restoreThermalParticleState(changedTemperature, registry);
			require(changed.exactInternalEnergy().equals(source.exactInternalEnergy())
					&& !changed.authoritativeLayout().equals(source.authoritativeLayout())
					&& !changed.authoritativeLayout().equals(source.captureAuthoritativeState().layout()),
					"backend configuration identity omitted the external insertion temperature");
			SignedDyadic before = source.thermalAudit().externalSource();
			require(source.tryInsertExactGranular(2, 1, 1, sand, 0, svq, mmm.units.Mass.fromKilograms(mass)).inserted(), "source handoff insertion failed");
			SignedDyadic added = source.thermalAudit().externalSource().subtract(before);
			int thermal = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR, BuiltinGranularDefinitions.FINE_SAND);
			for (var restored : List.of(target, backend)) {
				SignedDyadic restoredBefore = restored.thermalAudit().externalSource();
				require(restored.tryInsertExactGranular(2, 1, 1, sand, 0, svq, mmm.units.Mass.fromKilograms(mass)).inserted()
						&& restored.thermalAudit().externalSource().subtract(restoredBefore).equals(added)
						&& Double.doubleToRawLongBits(restored.thermalState.granularColumn().activeEnergy(1))
						== Double.doubleToRawLongBits(source.thermalState.granularColumn().activeEnergy(1))
						&& restored.thermalState.stateFunction().stateForInternalEnergy(thermal, mass, Map.of(),
								restored.thermalState.granularColumn().activeEnergy(1)).temperatureK() == 300
						&& restored.exactInternalEnergy().equals(restored.thermalAudit().expectedInventory()),
						"thermal handoff changed the energy or temperature of the next public insertion");
			}
			for (double invalid : new double[]{0, Math.nextDown(mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K),
					Double.NaN, Double.POSITIVE_INFINITY}) expectFailure(() -> new FlipThermalParticleState(
					4, 4, 4, .5F, snapshot.particleCount(), registry.fingerprint(), invalid, snapshot.copyInternalEnergy(),
					snapshot.copyActiveParcelEnergy(), snapshot.copyDepositEnergy(), snapshot.coherentGroups(),
					snapshot.copyGasNodeEnergy(), snapshot.audit()), "thermal handoff accepted an invalid source temperature");
			if (narrow) {
				var outside = new FlipThermalParticleState(4, 4, 4, .5F, snapshot.particleCount(), registry.fingerprint(),
						500, snapshot.copyInternalEnergy(), snapshot.copyActiveParcelEnergy(), snapshot.copyDepositEnergy(),
						snapshot.coherentGroups(), snapshot.copyGasNodeEnergy(), snapshot.audit());
				var original = new FluidDomain(4, 4, 4, config, 512);
				original.restoreMaterialParticleState(materialSnapshot);
				original.restoreGranularState(granularSnapshot, granular, liquids, BuiltinFluidMaterials.createRegistry());
				var revision = original.granularState.mutationRevision();
				try {
					original.restoreThermalParticleState(outside, registry);
					throw new AssertionError("thermal restore bypassed the common external temperature calibration");
				} catch (IllegalArgumentException expected) {
					require(expected.getMessage().startsWith("Initial temperature is outside"),
							"invalid configuration was rejected for an unrelated reason");
				}
				require(original.thermalState == null && original.granularState.mutationRevision() == revision,
						"invalid external temperature published a thermal candidate");
			}
		}
	}
	static void granularEnergyColumnTransactions() {
		liquidSolidSlipConservesActualMechanicalAndInternalEnergy();
		granularRepresentationTransfersMechanicalHeat();
		granularExternalExtractionRounding();
		granularInsertionPreflightsExternalSource();
		granularRestoreValidatesActualEnergy();
		String granularFingerprint = "2".repeat(64);
		ThermalMaterialDefinition liquid = material("test:thermal/granular_host", "test:liquid", 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		ThermalMaterialDefinition solid = new ThermalMaterialDefinition("test:thermal/granular",
				ThermalMaterialDefinition.CarrierKind.GRANULAR, "test:sand", 300.0, 20.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 350.0, 10.0)));
		ThermalRegistry registry = new ThermalRegistry(List.of(liquid, solid), List.of(), List.of(), "", granularFingerprint);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(
				FluidMaterial.withSurfaceTension("test:liquid", 1.0, "test:miscible", 0.0, 90.0)));
		GranularEnergyColumn.DepositKey deposit = new GranularEnergyColumn.DepositKey(0, 0, 0);
		ThermalCarrierContext carriers = new ThermalCarrierContext() {
			private final double[] liquidEnergy = new double[0];
			private long liquidRevision;
			@Override public double liquidInternalEnergy(int ordinal) { return liquidEnergy[ordinal]; }
			@Override public void setLiquidInternalEnergy(int ordinal, double energy) { liquidEnergy[ordinal] = energy; liquidRevision++; }
			@Override public long liquidInventoryRevision() { return liquidRevision; }
			@Override public int markerCount() { return 0; }
			@Override public int cell(int marker) { throw new IllegalArgumentException(); }
			@Override public int fluidMaterialHandle(int marker) { throw new IllegalArgumentException(); }
			@Override public double mass(int marker) { throw new IllegalArgumentException(); }
			@Override public double volume(int marker) { throw new IllegalArgumentException(); }
			@Override public ChemicalMixtureRecord mixture(int marker) { throw new IllegalArgumentException(); }
			@Override public ChemicalRegistry chemicalRegistry() { return null; }
			@Override public int granularMaterialCount() { return 1; }
			@Override public String granularRegistryFingerprint() { return granularFingerprint; }
			@Override public String granularMaterialId(int materialHandle) { return "test:sand"; }
			@Override public int activeParcelCount() { return 1; }
			@Override public int activeParcelCell(int ordinal) { return 0; }
			@Override public int activeParcelMaterial(int ordinal) { return 0; }
			@Override public int activeParcelComposition(int ordinal) { return 0; }
			@Override public long activeParcelSvq(int ordinal) { return SolidVolume.SVQ_PER_CELL / 2L; }
			@Override public double activeParcelMass(int ordinal) { return 0.5; }
			@Override public List<GranularEnergyColumn.DepositKey> depositKeys() { return List.of(deposit); }
			@Override public long depositSvq(GranularEnergyColumn.DepositKey key) { return SolidVolume.SVQ_PER_CELL / 4L; }
			@Override public double depositMass(GranularEnergyColumn.DepositKey key) { return 0.25; }
		};
		ThermalDomainState granularThermal = FluidThermalBinding.create(registry, liquids, 300.0,
				1, 2, 2, 2, 0.5F, carriers);
		require(granularThermal.exactEnergy(0).equals(SignedDyadic.of(15.0))
				&& close(granularThermal.temperatureInCell(0, carriers), 300.0),
				"M8 active/deposit energy did not join the LTE state function");
		granularThermal.setTemperatureInCell(0, 310.0, carriers);
		require(close(granularThermal.temperatureInCell(0, carriers), 310.0)
				&& granularThermal.exactEnergy(0).equals(granularThermal.auditLedger().expectedInventory()),
				"M8 active/deposit cell heat exchange did not close the thermal audit");
		granularThermal.restoreThermalGranular(new double[]{1.0},
				List.of(new GranularEnergyColumn.DepositEnergy(deposit, 1.0)));
		granularThermal.restoreAuditLedger(ThermalAuditLedger.initialized(SignedDyadic.of(2.0)));
		granularThermal.commitExternalActiveTake(0, 1, 3);
		granularThermal.commitExternalDepositTake(deposit, 1, 3);
		require(granularThermal.exactEnergy(0).equals(granularThermal.auditLedger().expectedInventory())
				&& granularThermal.auditLedger().roundoffResidual().equals(SignedDyadic.of(1, -53)),
				"partial active/deposit extraction omitted its exact binary64 energy roundoff");

		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry productionLiquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				100, 1.0E-5F, 2.0E-3F, 0.8F);
		FluidDomain domain = new FluidDomain(8, 8, 8, config, 8 * 8 * 8 * 8);
		domain.bindGranular(granular, productionLiquids, BuiltinFluidMaterials.createRegistry());
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long parcelSvq = SolidVolume.SVQ_PER_CUBIC_METER;
		double parcelMass = parcelSvq / (double) SolidVolume.SVQ_PER_CUBIC_METER
				* granular.material(sand).referenceDensity();
		GranularTransferLedger bucket = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(
				BuiltinGranularDefinitions.FINE_SAND, parcelSvq, mmm.units.Mass.fromKilograms(parcelMass), null)));
		require(domain.tryInsertExactGranular(4, 6, 4, bucket, 3).inserted(),
				"granular thermal domain fixture could not insert its parcel");
		ThermalRegistry productionThermal = granularThermalRegistry(productionLiquids, granular);
		domain.bindThermal(productionThermal, productionLiquids, 300.0);
		SignedDyadic insertedEnergy = domain.exactInternalEnergy();
		FluidDomain.GranularExtraction extracted = domain.tryExtractExactGranular(
				4, 6, 4, parcelSvq, 3, false);
		require(extracted.transferred() && close(extracted.internalEnergy(), insertedEnergy.toDouble())
				&& domain.exactInternalEnergy().equals(SignedDyadic.ZERO)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"granular extraction did not move its raw energy to the external sink");
		require(domain.tryInsertExactGranular(4, 6, 4, bucket, 3).inserted()
				&& domain.exactInternalEnergy().equals(insertedEnergy),
				"granular insertion did not restore its explicit-temperature source energy");
		for (int step = 0; step < 180 && domain.granularDepositCellCount() == 0; step++) {
			var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
					step, domain.captureAuthoritativeState(), List.of(), false, 0));
			domain = candidate.claimPreparedCpuDomain();
		}
		require(domain.granularDepositCellCount() > 0
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"active-to-deposit settlement failed: deposits=" + domain.granularDepositCellCount()
						+ " energy=" + domain.exactInternalEnergy()
						+ " expected=" + domain.thermalAudit().expectedInventory());
		domain.removeAllGranularExact();
		require(domain.exactInternalEnergy().equals(SignedDyadic.ZERO)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"granular clear did not move all active/deposit energy to the escaped sink");
	}

	/** The complete pressure/drag/transport commit carries slip loss into actual inventory U. */
	private static void liquidSolidSlipConservesActualMechanicalAndInternalEnergy() {
		try {
			var production=mmm.simulation.FullCapabilityTestFixture.load();
			var domain=new FluidDomain(5,5,5,new FluidSolverConfig(.5F,1000,0,.025F,160,1e-5F,.002F,0),1024);
			domain.sealDomainBoundary();
			var liquids=mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(domain,production.liquids());
			domain.bindChemistry(production.chemical());
			domain.bindGranular(production.granular(),liquids,production.materials());
			int sand=production.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND);
			long volume=mmm.granular.GranularLimits.MAX_PARCEL_VOLUME_SVQ;
			long mass=mmm.units.Mass.fromKilograms(production.granular().material(sand).referenceDensity()
					*volume/SolidVolume.SVQ_PER_CUBIC_METER);
			require(domain.tryInsertExactGranular(2,2,2,sand,0,volume,mass).inserted(),
					"Liquid/solid slip fixture could not insert its grain");
			domain.domainFlowFields.ensureGrid();
			int water=liquids.handle(BuiltinFluidMaterials.WATER.id());
			for(int z=0;z<5;z++)for(int y=0;y<5;y++)for(int x=0;x<5;x++){
				float pore=domain.domainCollision.effectiveOpenCellVolume(domain.domainFlowFields.cellIndex(x,y,z));
				require(domain.domainInventory.tryInsertExactVolume(x,y,z,pore,0,water).transferred(),
						"Liquid/solid slip fixture could not fill its pore water");
			}
			mmm.simulation.FullCapabilityTestFixture.bindFull(domain,production,293.15,ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
			for(int p=0;p<domain.granularState.parcels.count();p++){
				var grain=domain.granularState.parcels.parcel(p);
				domain.granularState.parcels.overwrite(p,new mmm.granular.GranularParcelStore.Parcel(grain.x(),grain.y(),grain.z(),
						1,0,0,grain.solidVolumeSvq(),grain.massUnits(),grain.materialHandle(),grain.solidCompositionHandle(),grain.contactAge(),grain.flags(),grain.ceilMask()));
			}
			double initialK=liquidSolidKineticEnergy(domain);var initialU=domain.exactInternalEnergy();
			require(initialK>1,"Liquid/solid slip fixture has no finite mechanical energy signal");
			double tolerance=1e-4+1e-3*initialK;
			for(int step=0;step<6;step++){
				domain.domainStep.step(false,0,true,true,1);
				double kinetic=liquidSolidKineticEnergy(domain),heat=domain.exactInternalEnergy().subtract(initialU).toDouble();
				require(Double.isFinite(heat)&&Math.abs(kinetic+heat-initialK)<=tolerance,
						"Full liquid/solid slip step lost actual K+U: step="+(step+1)+", K0="+initialK+", K="+kinetic+", dU="+heat);
			}
			require(domain.exactInternalEnergy().subtract(initialU).toDouble()>.01&&liquidSolidKineticEnergy(domain)<initialK,
					"Full liquid/solid slip did not turn relative motion into inventory heat");
		}catch(Exception failure){throw new AssertionError("Full liquid/solid mechanical heat validation failed",failure);}
	}
	private static double liquidSolidKineticEnergy(FluidDomain domain){
		domain.domainFlowFields.ensureVelocityGrid();double energy=granularMechanicalEnergy(domain,0);
		double[][] momentum={domain.mac.momentumX,domain.mac.momentumY,domain.mac.momentumZ};
		double[][] mass={domain.cellMacStage.massX,domain.cellMacStage.massY,domain.cellMacStage.massZ};
		for(int axis=0;axis<3;axis++)for(int face=0;face<momentum[axis].length;face++)if(mass[axis][face]>0)
			energy+=.5*momentum[axis][face]*momentum[axis][face]/mass[axis][face];
		return energy;
	}

	private static void granularRepresentationTransfersMechanicalHeat() {
		var granular = BuiltinGranularDefinitions.createRegistry();
		var liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = SolidVolume.SVQ_PER_CELL / 256;
		long mass = mmm.units.Mass.fromKilograms(2600.0 / 8 / 256);
		for (boolean thermal : new boolean[]{false, true}) {
			var domain = new FluidDomain(4, 4, 4, new FluidSolverConfig(.5F, 1000, 9.81F, .05F,
					100, 1e-5F, .002F, 0), 512);
			domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
			var state = domain.granularState;
			state.parcels.add(new mmm.granular.GranularParcelStore.Parcel(.75F, .70F, .75F,
					0, 1, 0, svq, mass, sand, 0, 0, (byte) 0));
			state.parcels.add(new mmm.granular.GranularParcelStore.Parcel(.75F, .80F, .75F,
					0, -1, 0, 3 * svq, 3 * mass, sand, 0, 0, (byte) 0));
			if (thermal) domain.bindThermal(granularThermalRegistry(liquids, granular), liquids, 300);
			SignedDyadic before = thermal ? domain.exactInternalEnergy() : SignedDyadic.ZERO;
			double mechanicalBefore = granularMechanicalEnergy(domain, 9.81F);
			var merge = state.inventory.compactActive(index -> domain.domainFlowFields.cellIndex(1, 1, 1),
					(first, second, left, right, replacement) -> true, 2, 4, 9.81F);
			SignedDyadic expectedHeat = SignedDyadic.of(1.5 * mmm.units.Mass.kilograms(mass));
			require(merge.merges() == 1 && merge.physicalHeat().equals(expectedHeat) && merge.mechanicalRounding().signum() != 0
					&& Math.abs(mechanicalBefore - granularMechanicalEnergy(domain, 9.81F)
							- merge.physicalHeat().toDouble() - merge.mechanicalRounding().toDouble()) < 1e-12,
					"Active compaction lost relative-slip heat or float kinetic/potential rounding");
			if (thermal) require(domain.thermalAudit().mechanicalWork().equals(expectedHeat)
					&& domain.exactInternalEnergy().subtract(before).equals(expectedHeat.add(domain.thermalAudit().roundoffResidual()))
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"Active compaction did not transfer its explicit heat to U and its audit");
			System.out.printf("  granular compaction thermal=%s heat=%.12g J mechanicalRounding=%.12g J%n",
					thermal, merge.physicalHeat().toDouble(), merge.mechanicalRounding().toDouble());
		}
		for (boolean relaxation : new boolean[]{false, true}) {
			var domain = new FluidDomain(2, 4, 2, new FluidSolverConfig(.5F, 1000, 9.81F, .05F,
					100, 1e-5F, .002F, 0), 64);
			domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
			var state = domain.granularState;
			if (relaxation) require(state.deposits.tryAdd(domain.domainFlowFields.cellIndex(0, 2, 0),
					new mmm.granular.GranularDepositStore.SolidPortion(sand, 0,
							SolidVolume.SVQ_PER_CELL / 4, mmm.units.Mass.fromKilograms(2600.0 / 32)), SolidVolume.SVQ_PER_CELL),
					"Mechanical bed-heat fixture did not fit its source");
			else state.parcels.add(new mmm.granular.GranularParcelStore.Parcel(.25F, .1F, .25F,
					.03F, .04F, .12F, svq, mass, sand, 0,
					mmm.granular.GranularDepositDynamics.SETTLE_CONTACT_STEPS,
					mmm.granular.GranularParcelStore.FLAG_SUPPORTED));
			domain.bindThermal(granularThermalRegistry(liquids, granular), liquids, 300);
			SignedDyadic initialU = domain.exactInternalEnergy();
			double initialMechanical = granularMechanicalEnergy(domain, 9.81F);
			var dynamics = new mmm.granular.GranularDepositDynamics(granular, state.parcels, state.deposits,
					state.inventory, 2, 4, 2, .5F);
			if (relaxation) dynamics.disturbCell(domain.domainFlowFields.cellIndex(0, 2, 0));
			var environment = new mmm.granular.GranularDepositDynamics.Environment() {
				private mmm.granular.GranularInventory.EnergyPlan energy;
				@Override public long geometricOpenSvq(int cell) { return SolidVolume.SVQ_PER_CELL; }
				@Override public double gravity() { return 9.81F; }
				@Override public boolean horizontalPathOpen(int from, int dx, int dz) { return true; }
				@Override public double shields(int cell, mmm.granular.ParticulateMaterial material) { return 0; }
				@Override public void requireThermalRelaxation(int from, int to, int material, int composition,
						long moved, long total, SignedDyadic heat) {
					energy = domain.thermalState.planDepositMove(new GranularEnergyColumn.DepositKey(from, material, composition),
							new GranularEnergyColumn.DepositKey(to, material, composition), moved, total, heat);
					energy.requireCurrent();
				}
				@Override public void commitThermalRelaxation(int from, int to, int material, int composition,
						long moved, long total, SignedDyadic heat) { energy.commit(); energy = null; }
			};
			var stats = dynamics.step(environment, .05F);
			double lostMechanical = initialMechanical - granularMechanicalEnergy(domain, 9.81F);
			require((relaxation ? stats.relaxedMoves() > 0 : stats.settledParcels() == 1)
					&& stats.physicalHeat().signum() > 0
					&& Math.abs(lostMechanical - stats.physicalHeat().toDouble()) < 1e-10 * Math.max(1, initialMechanical)
					&& domain.thermalAudit().mechanicalWork().equals(stats.physicalHeat())
					&& domain.exactInternalEnergy().subtract(initialU).equals(stats.physicalHeat().add(domain.thermalAudit().roundoffResidual()))
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
					&& state.inventory.totalMassUnits() == (relaxation ? mmm.units.Mass.fromKilograms(2600.0 / 32) : mass),
					"Granular " + (relaxation ? "relaxation" : "settlement") + " failed its independent KE/GPE/U balance: heat="
							+ stats.physicalHeat() + " mechanicalLoss=" + lostMechanical + " stats=" + stats);
			System.out.printf("  granular %s heat=%.12g J independentMechanicalLoss=%.12g J Uroundoff=%.12g J%n",
					relaxation ? "relaxation" : "settlement", stats.physicalHeat().toDouble(), lostMechanical,
					domain.thermalAudit().roundoffResidual().toDouble());
			var restored = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0,
					domain.captureAuthoritativeState(), List.of(), false, 0)).claimPreparedCpuDomain();
			require(restored.exactInternalEnergy().equals(restored.thermalAudit().expectedInventory()),
					"Granular mechanical heat did not survive restore and the next normal step");
		}
	}

	private static double granularMechanicalEnergy(FluidDomain domain, double gravity) {
		double result = 0;
		for (int i = 0; i < domain.granularState.parcels.count(); i++) {
			var parcel = domain.granularState.parcels.parcel(i);
			result += parcel.mass() * (gravity * parcel.y() + .5 * ((double) parcel.velocityX() * parcel.velocityX()
					+ (double) parcel.velocityY() * parcel.velocityY() + (double) parcel.velocityZ() * parcel.velocityZ()));
		}
		double[] bed = {0};
		domain.granularState.deposits.forEachGroup((cell, porosity, material, composition, volume, mass) -> {
			double center = (cell / domain.sizeX % domain.sizeY
					+ .5 * domain.granularState.deposits.cellBulkVolumeSvq(cell) / SolidVolume.SVQ_PER_CELL) * domain.config().cellSize();
			bed[0] += mmm.units.Mass.kilograms(mass) * gravity * center;
		});
		return result + bed[0];
	}

	private static void granularInsertionPreflightsExternalSource() {
		try {
			var production = mmm.simulation.FullCapabilityTestFixture.load();
			var huge = SignedDyadic.of(1L, 40_000);
			for (double temperature : new double[]{280, 300}) for (int mode = 0; mode < 3; mode++) {
				var domain = new FluidDomain(4, 4, 4,
						new FluidSolverConfig(.5F, 1000F, 0F, .05F, 100, 1e-5F, .002F, .8F), 512);
				mmm.simulation.FullCapabilityTestFixture.bindFull(domain, production, temperature,
						ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
				domain.setVolume(2, 2, 2, .001F);
				int initialLiquidCount = domain.particleCount();
				int sand = production.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND);
				long parcel = mmm.granular.GranularLimits.MAX_PARCEL_VOLUME_SVQ;
				double mass = production.granular().material(sand).referenceDensity() * parcel / SolidVolume.SVQ_PER_CUBIC_METER;
				require(domain.tryInsertExactGranular(2, 1, 1, sand, 0, parcel, mmm.units.Mass.fromKilograms(mass)).inserted(),
						"granular source-budget fixture could not create its retained parcel");
				var actual = domain.exactInternalEnergy();
				var audit = new ThermalAuditLedger(SignedDyadic.ZERO, huge, huge, actual, SignedDyadic.ZERO, SignedDyadic.ZERO);
				domain.thermalState.restoreAuditLedger(audit);
				require(audit.expectedInventory().equals(actual), "restored granular source/sink did not close");
				var granular = domain.granularState;
				var retained = granular.parcels.parcel(0);
				var inventory = mmm.granular.GranularAuditLedger.captureInventory(
						granular.registry, granular.parcels, granular.deposits, granular.compositions);
				var source = granular.externalSource; var sink = granular.escapedSink;
				var liquid = domain.liquidCells.stocks();
				var liquidSource = domain.chemicalParcels.externalSource(); var liquidSink = domain.chemicalParcels.escapedSink();
				long carrierRevision = domain.domainFlowFields.carrierMutationRevision();
				var revision = domain.thermalState.thermalRevision(); long structuralRevision = granular.mutationRevision();
				var energy = domain.thermalState.granularColumn().copyActiveEnergy();
				int compositions = granular.compositions.liveCount();
				mmm.granular.SolidCompositionRecord composition = mode != 2 ? null
						: mmm.granular.SolidCompositionRecord.fromStableAmounts(production.chemical(), Map.of(
								mmm.chemistry.BuiltinChemicalDefinitions.NA_PLUS, 17_984L,
								mmm.chemistry.BuiltinChemicalDefinitions.CL_MINUS, 17_984L), 0L);
				var group = mode != 2
						? new GranularTransferLedger.Group(BuiltinGranularDefinitions.FINE_SAND, parcel * 2, mmm.units.Mass.fromKilograms(mass * 2), null)
						: new GranularTransferLedger.Group(BuiltinGranularDefinitions.PRECIPITATE, 50,
								mmm.units.Mass.fromKilograms(composition.totalMass(production.chemical())), composition);
				var packet = new GranularTransferLedger(List.of(group));
				java.util.function.Supplier<FluidDomain.GranularInsertion> insertion = mode == 0
						? () -> domain.tryInsertExactGranular(1, 1, 1, sand, 0, parcel, mmm.units.Mass.fromKilograms(mass))
						: () -> domain.tryInsertExactGranular(1, 1, 1, packet, 0);
				try {
					insertion.get();
					throw new AssertionError("unrepresentable granular external-source field was accepted");
				} catch (IllegalArgumentException expected) {
					require(expected.getMessage().contains("dyadic significand is over budget"),
							"granular insertion failed before reaching the external-source budget");
				}
				require(granular.parcels.count() == 1 && granular.parcels.parcel(0).equals(retained)
						&& granular.mutationRevision() == structuralRevision && granular.deposits.groupCount() == 0
						&& granular.compositions.liveCount() == compositions && granular.externalSource.equals(source)
						&& granular.escapedSink.equals(sink) && inventory.equals(mmm.granular.GranularAuditLedger.captureInventory(
								granular.registry, granular.parcels, granular.deposits, granular.compositions))
						&& Arrays.equals(energy, domain.thermalState.granularColumn().copyActiveEnergy())
						&& domain.thermalState.matchesThermalRevision(revision) && domain.thermalAudit().equals(audit)
						&& domain.liquidCells.physicalSize() == initialLiquidCount && domain.domainFlowFields.carrierMutationRevision() == carrierRevision
						&& domain.liquidCells.stocks().equals(liquid) && domain.chemicalParcels.externalSource().equals(liquidSource)
						&& domain.chemicalParcels.escapedSink().equals(liquidSink)
						&& domain.exactInternalEnergy().equals(actual),
						"granular source-budget rejection changed inventory, compositions, energy, audit or generation");
				domain.thermalState.restoreAuditLedger(ThermalAuditLedger.initialized(actual));
				require(insertion.get().inserted() && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
						&& granular.parcels.count() == domain.thermalState.granularColumn().activeCount(),
						"valid granular insertion failed after a rejected source budget or lost its audit");
			}
		} catch (Exception failure) { throw new AssertionError(failure); }
	}

	private static void granularRestoreValidatesActualEnergy() {
		try {
			var production = mmm.simulation.FullCapabilityTestFixture.load();
			for (int mode = 0; mode < 3; mode++) {
				String id = mode == 2 ? BuiltinGranularDefinitions.PRECIPITATE : BuiltinGranularDefinitions.FINE_SAND;
				int material = production.granular().materialHandle(id);
				long svq = mmm.granular.GranularLimits.MAX_PARCEL_VOLUME_SVQ;
				long mass = mmm.units.Mass.fromKilograms(production.granular().material(material).referenceDensity()
						* svq / SolidVolume.SVQ_PER_CUBIC_METER);
				var amounts = Map.of(mmm.chemistry.BuiltinChemicalDefinitions.NA_PLUS, 17_984L,
						mmm.chemistry.BuiltinChemicalDefinitions.CL_MINUS, 17_984L);
				var species = mmm.granular.SolidCompositionRecord.fromStableAmounts(production.chemical(), amounts, 0);
				var composition = mode == 2 ? mmm.granular.SolidCompositionRecord.fromStableAmounts(
						production.chemical(), amounts, mass - species.massUnits(production.chemical())) : null;
				granularRestoreUsesSnapshotEnergy(production, production.thermal(), id, svq,
						mmm.units.Mass.kilograms(mass), composition, mode == 1);
			}
		} catch (Exception failure) { throw new AssertionError(failure); }
	}

	private static void granularRestoreUsesSnapshotEnergy(mmm.simulation.FullCapabilityTestFixture.Registries production,
			ThermalRegistry registry, String materialId, long svq, double mass,
			mmm.granular.SolidCompositionRecord composition, boolean deposited) {
		mass = mmm.units.Mass.kilograms(mmm.units.Mass.fromKilograms(mass));
		var config = new FluidSolverConfig(.5F, 1000F, 0F, .05F, 100, 1e-5F, .002F, .8F);
		var source = new FluidDomain(4, 4, 4, config, 512);
		var liquids = mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(source, production.liquids());
		source.bindChemistry(production.chemical()); source.bindGranular(production.granular(), liquids, production.materials());
		source.bindGasRegistry(production.gas()); source.setVolume(2, 2, 2, .001F);
		int material = production.granular().materialHandle(materialId);
		int handle = composition == null ? 0 : source.granularState.compositions.create(composition);
		require(source.tryInsertExactGranular(1, 1, 1, material, handle, svq, mmm.units.Mass.fromKilograms(mass)).inserted(), "snapshot grain could not be inserted");
		if (deposited) require(source.granularState.inventory.depositParcel(0, source.domainFlowFields.cellIndex(1, 1, 1), SolidVolume.SVQ_PER_CELL),
				"snapshot grain could not be deposited");
		source.bindThermal(registry, liquids, 400);
		source.setThermalTemperatureInCell(2, 2, 2, 1);
		var snapshot = source.exportThermalParticleState();
		var function = new ThermalStateFunction(registry);
		int thermal = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR, materialId);
		var species = composition == null ? Map.<String, Long>of() : composition.stableAmounts(production.chemical());
		double invalid = function.internalEnergyAtTemperature(thermal, mass, species, 1)
				- 2 * mass * registry.material(thermal).specificHeatAt(1);
		var badActive = snapshot.copyActiveParcelEnergy(); var badDeposit = snapshot.copyDepositEnergy();
		if (deposited) badDeposit[0] = invalid; else badActive[0] = invalid;
		var bad = new FlipThermalParticleState(4, 4, 4, .5F, source.liquidCells.physicalSize(), registry.fingerprint(), snapshot.insertionTemperatureK(),
				snapshot.copyInternalEnergy(), badActive, badDeposit, snapshot.coherentGroups(), snapshot.copyGasNodeEnergy(),
				ThermalAuditLedger.initialized(SignedDyadic.sum(snapshot.copyInternalEnergy()).add(SignedDyadic.sum(badActive))
						.add(SignedDyadic.sum(badDeposit)).add(SignedDyadic.sum(snapshot.copyGasNodeEnergy()))));
		var target = new FluidDomain(4, 4, 4, config, 512);
		target.restoreMaterialParticleState(source.exportMaterialParticleState());
		target.bindChemistry(production.chemical());
		target.restoreGranularState(source.exportGranularState(), production.granular(), target.materialRegistry, production.materials());
		target.bindGasRegistry(production.gas());
		var structural = target.granularState.mutationRevision();
		var inventory = mmm.granular.GranularAuditLedger.captureInventory(target.granularState.registry,
				target.granularState.parcels, target.granularState.deposits, target.granularState.compositions);
		var liquid = target.liquidCells.stocks();
		try { target.restoreThermalParticleState(bad, registry);
			throw new AssertionError("snapshot restored an invalid raw granular temperature");
		} catch (ThermalStateFunction.EnergyOutsideValidityException expected) { }
		require(target.thermalState == null && target.granularState.mutationRevision() == structural
				&& inventory.equals(mmm.granular.GranularAuditLedger.captureInventory(target.granularState.registry,
						target.granularState.parcels, target.granularState.deposits, target.granularState.compositions))
				&& target.liquidCells.stocks().equals(liquid), "invalid thermal restore published a partial target");
		target.restoreThermalParticleState(snapshot, registry);
		var restored = target.exportThermalParticleState();
		var sourceContext = source.domainCapabilities.thermalMarkerContext();
		var targetContext = target.domainCapabilities.thermalMarkerContext();
		boolean sameTemperatures = source.particleCount() == target.particleCount();
		for (int marker = 0; sameTemperatures && marker < source.particleCount(); marker++) {
			sameTemperatures = Math.abs(source.thermalState.temperature(marker, sourceContext)
					- target.thermalState.temperature(marker, targetContext)) <= 1E-4;
		}
		require(Arrays.equals(snapshot.copyInternalEnergy(), restored.copyInternalEnergy())
				&& Arrays.equals(snapshot.copyActiveParcelEnergy(), restored.copyActiveParcelEnergy())
				&& Arrays.equals(snapshot.copyDepositEnergy(), restored.copyDepositEnergy())
				&& source.exactInternalEnergy().equals(target.exactInternalEnergy()) && source.thermalAudit().equals(target.thermalAudit())
				&& sameTemperatures
				&& close(function.stateForInternalEnergy(thermal, mass, species,
						deposited ? restored.copyDepositEnergy()[0] : restored.copyActiveParcelEnergy()[0]).temperatureK(), 400)
				&& target.exactInternalEnergy().equals(target.thermalAudit().expectedInventory()),
				"thermal restore modeled placeholder grain U instead of preserving the valid snapshot and its temperatures"
						+ ": sourceFirstT=" + source.thermalState.temperature(0, sourceContext)
						+ ", targetFirstT=" + target.thermalState.temperature(0, targetContext));
	}

	static void granularExternalExtractionRounding() {
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 2.0E-3F, 0.0F);
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		for (double[] energies : List.of(new double[]{.1, .2, .3}, new double[]{.3, .2, .1},
				new double[]{.1, .2, -.1}, new double[]{-.1, .2, .1})) {
			FluidDomain domain = new FluidDomain(4, 4, 4, config, 512);
			domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
			for (int parcel = 0; parcel < 3; parcel++) require(domain.tryInsertExactGranular(
					1, 1, 1, sand, 0, 3, mmm.units.Mass.fromKilograms(3.0 / SolidVolume.SVQ_PER_CUBIC_METER * granular.material(sand).referenceDensity())).inserted(),
					"granular extraction rounding fixture could not insert its parcel");
			domain.bindThermal(granularThermalRegistry(liquids, granular), liquids, 300.0);
			domain.thermalState.restoreThermalGranular(energies, List.of());
			SignedDyadic initial = SignedDyadic.sum(energies);
			domain.thermalState.restoreAuditLedger(ThermalAuditLedger.initialized(initial));
			var revision = domain.thermalState.thermalRevision();
			var before = java.util.stream.IntStream.range(0, 3).mapToObj(domain.granularState.parcels::parcel).toList();
			var rejected = domain.tryExtractExactGranular(1, 1, 1, 9, 0, false, ignored -> false);
			require(!rejected.transferred() && domain.thermalState.matchesThermalRevision(revision)
					&& Arrays.equals(domain.thermalState.granularColumn().copyActiveEnergy(), energies)
					&& domain.granularState.parcels.count() == 3
					&& java.util.stream.IntStream.range(0, 3).mapToObj(domain.granularState.parcels::parcel).toList().equals(before),
					"rejected granular extraction changed inventory or thermal audit");
			var extracted = domain.tryExtractExactGranular(1, 1, 1, 9, 0, false);
			require(extracted.transferred()
					&& Double.doubleToRawLongBits(extracted.internalEnergy()) == Double.doubleToRawLongBits(initial.toDouble())
					&& domain.thermalAudit().externalSink().equals(SignedDyadic.of(extracted.internalEnergy()))
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"multi-parcel extraction did not round once at the item energy boundary");
			require(Math.abs(domain.thermalAudit().roundoffResidual().toDouble())<=8*Math.ulp(initial.toDouble()),
					"Granular extraction hid a finite energy discrepancy in its roundoff ledger");
		}
		for (boolean deposited : new boolean[]{false, true}) {
			FluidDomain domain = new FluidDomain(4, 4, 4, config, 512);
			domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
			require(domain.tryInsertExactGranular(1, 1, 1, sand, 0, 3,
					mmm.units.Mass.fromKilograms(3.0 / SolidVolume.SVQ_PER_CUBIC_METER * granular.material(sand).referenceDensity())).inserted(),
					"partial granular extraction fixture could not insert its parcel");
			domain.bindThermal(granularThermalRegistry(liquids, granular), liquids, 300.0);
			domain.thermalState.restoreThermalGranular(new double[]{1.0}, List.of());
			domain.thermalState.restoreAuditLedger(ThermalAuditLedger.initialized(SignedDyadic.of(1.0)));
			if (deposited) require(domain.granularState.inventory.depositParcel(0,
					domain.domainFlowFields.cellIndexChecked(1, 1, 1), SolidVolume.SVQ_PER_CELL), "partial deposit fixture could not settle");
			var extracted = domain.tryExtractExactGranular(1, 1, 1, 1, 0, false);
			SignedDyadic actualRemoval = SignedDyadic.of(1.0).subtract(domain.exactInternalEnergy());
			require(extracted.transferred() && extracted.ledger().totalSolidVolumeSvq() == 1
					&& Double.doubleToRawLongBits(extracted.internalEnergy()) == Double.doubleToRawLongBits(actualRemoval.toDouble())
					&& domain.thermalAudit().externalSink().equals(SignedDyadic.of(extracted.internalEnergy()))
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"partial active/deposit item U differs from the actual raw carrier energy removal");
			require(Math.abs(domain.thermalAudit().roundoffResidual().toDouble())<=8*Math.ulp(1.0),
					"Partial granular extraction roundoff exceeded its stored-owner precision");
		}
		granularColdRemainderRetainsTemperature();
	}

	private static void granularColdRemainderRetainsTemperature() {
		for (int variant = 0; variant < 3; variant++) try {
			var production = mmm.simulation.FullCapabilityTestFixture.load();
			var domain = new FluidDomain(3, 3, 3,
					new FluidSolverConfig(.5F, 1000F, 0F, .05F, 100, 1e-5F, .002F, .8F), 216);
			var liquids = mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(domain, production.liquids());
			domain.bindChemistry(production.chemical()); domain.bindGranular(production.granular(), liquids, production.materials());
			domain.bindGasRegistry(production.gas());
			int cell = domain.domainFlowFields.cellIndexChecked(1, 1, 1), sand = production.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND);
			boolean active = variant == 2;
			long svq = variant == 0 ? 10_401_873 : active ? 262_143 : 10_401_812;
			double mass = production.granular().material(sand).referenceDensity() * Math.scalb((double) svq, -27)
					* (variant == 0 ? 1 : 1 + 1e-9);
			mass = mmm.units.Mass.kilograms(mmm.units.Mass.fromKilograms(mass));
			if (active) domain.granularState.parcels.add(new mmm.granular.GranularParcelStore.Parcel(
					.75F, .75F, .75F, 0, 0, 0, svq, mmm.units.Mass.fromKilograms(mass), sand, 0, 0, (byte) 0));
			else require(domain.granularState.deposits.tryAdd(cell, new mmm.granular.GranularDepositStore.SolidPortion(
					sand, 0, svq, mmm.units.Mass.fromKilograms(mass)), SolidVolume.SVQ_PER_CELL), "Cold remainder bed exceeds packing capacity");
			domain.bindThermal(production.thermal(), liquids, 293.15); domain.setThermalTemperatureInCell(1, 1, 1, 1);
			var function = new mmm.thermal.ThermalStateFunction(production.thermal());
			var material = production.thermal().materials().stream().filter(entry -> entry.carrierId().equals(BuiltinGranularDefinitions.FINE_SAND)).findFirst().orElseThrow();
			int handle = production.thermal().materialHandle(material.id()); var key = new GranularEnergyColumn.DepositKey(cell, sand, 0);
			double sourceK = function.stateForInternalEnergy(handle, mass, java.util.Map.of(), active
					? domain.thermalState.granularColumn().activeEnergy(0) : domain.thermalState.granularColumn().depositEnergy(key)).temperatureK();
			SignedDyadic before = domain.exactInternalEnergy();
			var rejected = domain.tryExtractExactGranular(1, 1, 1, svq - 1, 0, false, ledger -> false);
			require(!rejected.transferred() && domain.exactInternalEnergy().equals(before)
					&& (active ? domain.granularState.parcels.parcel(0).solidVolumeSvq()
					: domain.granularState.deposits.dominantGroup(cell).solidVolumeSvq()) == svq, "Rejected cold extraction changed inventory or U");
			var taken = domain.tryExtractExactGranular(1, 1, 1, svq - 1, 0, false);
			double remainingMass = active ? domain.granularState.parcels.parcel(0).mass() : domain.granularState.deposits.dominantGroup(cell).mass();
			long remainingVolume = active ? domain.granularState.parcels.parcel(0).solidVolumeSvq()
					: domain.granularState.deposits.dominantGroup(cell).solidVolumeSvq();
			double expected = function.internalEnergyAtTemperature(handle, remainingMass, java.util.Map.of(), sourceK);
			double actual = active ? domain.thermalState.granularColumn().activeEnergy(0) : domain.thermalState.granularColumn().depositEnergy(key);
			double finalK = function.stateForInternalEnergy(handle, remainingMass, java.util.Map.of(), actual).temperatureK();
			double temperatureBound = 1E-4;
			require(taken.transferred() && remainingVolume == 1
					&& mmm.units.Mass.fromKilograms(remainingMass) == mmm.units.Mass.fromKilograms(mass) - taken.ledger().totalMassUnits()
					&& Math.abs(actual - expected) <= remainingMass * material.specificHeatAt(sourceK) * temperatureBound
					&& Math.abs(finalK - sourceK) <= temperatureBound && finalK > 0
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()), "Cold granular remainder lost temperature or exact U audit: variant=" + variant
					+ " transferred=" + taken.transferred() + " mass=" + remainingMass + " volume=" + remainingVolume
					+ " energyError=" + (actual - expected)
					+ " temperature=" + finalK + " source=" + sourceK + " bound=" + temperatureBound);
		} catch (Exception failure) { throw new AssertionError("Production cold granular extraction fixture failed", failure); }
	}

	static ThermalRegistry granularThermalRegistry(
			FluidMaterialRegistry liquids, GranularRegistry granular
	) {
		java.util.ArrayList<ThermalMaterialDefinition> definitions = new java.util.ArrayList<>();
		for (int material = 0; material < liquids.size(); material++) definitions.add(new ThermalMaterialDefinition(
				"test:thermal/liquid_" + material, ThermalMaterialDefinition.CarrierKind.LIQUID,
				liquids.material(material).id(), 300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0))));
		for (int material = 0; material < granular.materials().size(); material++) definitions.add(
				new ThermalMaterialDefinition("test:thermal/granular_" + material,
						ThermalMaterialDefinition.CarrierKind.GRANULAR, granular.material(material).id(),
						300.0, 20.0, 1.0,
						List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0))));
		return new ThermalRegistry(definitions, List.of(), List.of(), "", granular.fingerprint());
	}

	static void liquidTransportAndHandoffCloseEnergy() {
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				300.0, 0.0, 1_000.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 4_000.0)));
		ThermalRegistry registry = new ThermalRegistry(List.of(water));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(4, 3, 3, config, 512);
		domain.setVolume(1, 1, 1, 1.0F);
		domain.setVolume(2, 1, 1, 1.0F);
		domain.bindThermal(registry, liquids, 300.0);
		domain.setThermalTemperatureInCell(1, 1, 1, 350.0);
		domain.setThermalTemperatureInCell(2, 1, 1, 250.0);
		SignedDyadic before = domain.exactInternalEnergy();
		FluidDomain stiff = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		stiff.stepThermalOnly(10_000.0);
		for (int p = 0; p < stiff.particleCount(); p++) require(
				stiff.thermalState.temperature(p, stiff.domainCapabilities.thermalMarkerContext()) >= 250
						&& stiff.thermalState.temperature(p, stiff.domainCapabilities.thermalMarkerContext()) <= 350,
				"stiff finite carrier contacts exceeded the initial temperature range");
		require(Math.abs(stiff.thermalTemperatureInCell(1, 1, 1) - 300) < 50
				&& stiff.exactInternalEnergy().equals(stiff.thermalAudit().expectedInventory()),
				"stiff pair failed relaxation or conservation");
		domain.stepThermalOnly(1_000.0);
		double hot = domain.thermalTemperatureInCell(1, 1, 1);
		double cold = domain.thermalTemperatureInCell(2, 1, 1);
		require(hot < 350.0 && hot >= 300.0 && cold > 250.0 && cold <= 300.0 && hot >= cold,
				"pairwise thermal transport was not monotone: hot=" + hot + " cold=" + cold);
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"transient thermal transport did not close its audit ledger");
		require(Math.abs(domain.exactInternalEnergy().subtract(before).toDouble()) <= 1.0E-6,
				"adiabatic thermal transport changed total internal energy beyond representation residual");
		FluidDomain equilibrium = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		equilibrium.setThermalTemperatureInCell(1, 1, 1, 300);
		equilibrium.setThermalTemperatureInCell(2, 1, 1, 300);
		double[] equilibratedCarrierEnergy = equilibrium.exportThermalParticleState().copyInternalEnergy();
		ThermalTransport.Stats noFlux = equilibrium.stepThermalOnly(10_000.0);
		require(noFlux.substeps() == 1 && noFlux.maximumAbsoluteTransferJ() == 0.0
				&& Arrays.equals(equilibratedCarrierEnergy,
						equilibrium.exportThermalParticleState().copyInternalEnergy()),
				"zero-flux transport rewrote authoritative carrier energy");

		FlipMaterialParticleState materials = domain.exportMaterialParticleState();
		FlipThermalParticleState thermal = domain.exportThermalParticleState();
		FluidDomain restored = new FluidDomain(4, 3, 3, config, 512);
		restored.restoreMaterialParticleState(materials);
		restored.restoreThermalParticleState(thermal, registry);
		require(restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
				&& close(restored.thermalTemperatureInCell(1, 1, 1), hot)
				&& close(restored.thermalTemperatureInCell(2, 1, 1), cold),
				"orthogonal thermal handoff did not preserve energy/temperature");

		FluidDomainState authoritative = domain.captureAuthoritativeState();
		FluidDomain fullRestore = FluidDomain.restoreAuthoritativeState(authoritative);
		var expectedGas = domain.exportGasState("");
		var restoredGas = fullRestore.exportGasState("");
		boolean energyEqual = fullRestore.exactInternalEnergy().equals(domain.exactInternalEnergy());
		boolean gasEqual = restoredGas.nodes().equals(expectedGas.nodes())
				&& Arrays.equals(restoredGas.pieceNodeOrdinal(), expectedGas.pieceNodeOrdinal());
		PendingPhaseVolumeState expectedPending = domain.exportPendingPhaseVolumeState();
		PendingPhaseVolumeState restoredPending = fullRestore.exportPendingPhaseVolumeState();
		boolean pendingEqual = restoredPending.cellCount() == expectedPending.cellCount()
				&& restoredPending.entries().equals(expectedPending.entries());
		require(energyEqual && gasEqual && pendingEqual,
				"complete loader-neutral handoff changed truth: energy=" + energyEqual
						+ " gas=" + gasEqual + " pending=" + pendingEqual);
		SignedDyadic committedEnergy = domain.exactInternalEnergy();
		FluidStepCandidate candidate = new CpuFluidSolverBackend().evaluate(
				new FluidStepRequest(7L, authoritative, List.of(), false, 0));
		require(candidate.baseGeneration() == 7L && candidate.candidateGeneration() == 8L
				&& candidate.backendId().equals(CpuFluidSolverBackend.ID)
				&& domain.exactInternalEnergy().equals(committedEnergy),
				"CPU candidate boundary changed generation identity or mutated the owner");
	}

	static void localizedHeatingDrivesBoussinesqCirculation() {
		require(LiquidThermalExpansion.require(BuiltinFluidMaterials.WATER.id())
				.relativeBuoyancy(313.15) > 0.0, "water expansion definition does not produce hot buoyancy");
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/buoyancy_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				293.15, 0.0, 0.598,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 4_184.0)));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 0.05F,
				100, 1.0E-5F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(6, 6, 6, config, 1_728);
		for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
			if (x == 0 || x == 5 || y == 0 || y == 5 || z == 0 || z == 5) domain.setSolid(x, y, z, true);
			else domain.setVolume(x, y, z, 1.0F);
		}
		domain.bindThermal(new ThermalRegistry(List.of(water)), liquids, 293.15);
		domain.setThermalTemperatureInCell(2, 1, 2, 333.15);
		for (int step = 0; step < 4; step++) domain = advance(domain);
		FluidDomain.VelocitySample plume = domain.sampleVelocity(1.25F, 0.75F, 1.25F);
		FluidDomain.VelocitySample far = domain.sampleVelocity(1.75F, 0.75F, 1.75F);
		require(plume.y() > far.y() + 1.0E-5F && domain.maximumParticleSpeed() > 1.0E-4F,
				"localized heat did not create an upward Boussinesq plume: plume=" + plume.y() + " far=" + far.y());
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"Boussinesq momentum coupling changed authoritative thermal energy");
	}

	static void partialCutFaceUsesPlicThermalContact() {
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/partial_contact_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 400.0, 4_000.0)));
		ThermalMaterialDefinition air = new ThermalMaterialDefinition("mmm:thermal/air",
				ThermalMaterialDefinition.CarrierKind.GAS, mmm.fluid.air.BuiltinGasDefinitions.AIR,
				mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K, 10.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 718.0)));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(4, 3, 3, config, 512);
		// Two microcells obstruct the shared +x face: the authoritative aperture is exactly one half.
		domain.setSolidMicroMask(1, 1, 1, 0x22);
		domain.setVolume(1, 1, 1, 0.5F);
		domain.setVolume(2, 1, 1, 1.0F);
		domain.bindThermal(new ThermalRegistry(List.of(water, air)), liquids, 300.0);
		domain.setThermalTemperatureInCell(1, 1, 1, 350.0);
		domain.setThermalTemperatureInCell(2, 1, 1, 250.0);
		ThermalTransport.Stats transport = domain.stepThermalOnly(1_000.0);
		require(transport.evaluatedContacts() > 0 && transport.maximumAbsoluteTransferJ() > 0.0
				&& domain.thermalTemperatureInCell(1, 1, 1) < 350.0
				&& domain.thermalTemperatureInCell(2, 1, 1) > 250.0,
				"partial cut/PLIC contact was dropped from production thermal transport");
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"partial cut/PLIC thermal contact did not close its energy audit");
	}

	static void finiteThermalBodyExchangesWithoutMixing() {
		localThermalEditsPreserveRemoteCarrierEnergy();
		finiteThermalExchangeRetainsEnergyScale();
		ThermalMaterialDefinition wall = new ThermalMaterialDefinition("test:thermal/exchanger_wall",
				ThermalMaterialDefinition.CarrierKind.DEVICE, "test:exchanger_wall",
				300.0, 0.0, 20.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 500.0, 500.0)));
		ThermalRegistry registry = new ThermalRegistry(List.of(wall));
		ThermalStateFunction stateFunction = new ThermalStateFunction(registry);
		double initialEnergy = stateFunction.internalEnergyAtTemperature(
				registry.materialHandle(wall.id()), 10.0, Map.of(), 400.0);
		FiniteThermalBodyState body = new FiniteThermalBodyState(
				7L, registry.fingerprint(), wall.id(), mmm.units.Mass.fromKilograms(10.0), initialEnergy);
		FiniteThermalExchange.Plan plan = FiniteThermalExchange.plan(registry, body, List.of(
				new FiniteThermalExchange.Contact(0, 300.0, 2_000.0, 100.0, 250.0, 500.0),
				new FiniteThermalExchange.Contact(1, 350.0, 1_000.0, 50.0, 250.0, 500.0)
		), 1.0, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		FiniteThermalExchange.ContactDelta cold = plan.contacts().get(0);
		FiniteThermalExchange.ContactDelta warm = plan.contacts().get(1);
		SignedDyadic jointChange = SignedDyadic.of(plan.after().internalEnergyJ())
				.subtract(SignedDyadic.of(plan.before().internalEnergyJ()))
				.add(SignedDyadic.of(cold.internalEnergyDeltaJ()))
				.add(SignedDyadic.of(warm.internalEnergyDeltaJ()));
		require(plan.changed() && plan.before().generation() == 7L && plan.after().generation() == 8L
				&& cold.ordinal() == 0 && warm.ordinal() == 1
				&& close(cold.internalEnergyDeltaJ(), 10_000.0)
				&& close(warm.internalEnergyDeltaJ(), 2_500.0)
				&& close(cold.finalTemperatureK(), 305.0)
				&& close(warm.finalTemperatureK(), 352.5)
				&& close(stateFunction.stateForInternalEnergy(registry.materialHandle(wall.id()), 10.0,
						Map.of(), plan.after().internalEnergyJ()).temperatureK(), 397.5)
				&& jointChange.equals(plan.roundoffResidual()),
				"finite thermal body did not produce a closed three-owner energy plan");

		double equilibriumEnergy = stateFunction.internalEnergyAtTemperature(
				registry.materialHandle(wall.id()), 10.0, Map.of(), 350.0);
		FiniteThermalBodyState equilibrium = new FiniteThermalBodyState(
				9L, registry.fingerprint(), wall.id(), mmm.units.Mass.fromKilograms(10.0), equilibriumEnergy);
		FiniteThermalExchange.Plan noOp = FiniteThermalExchange.plan(registry, equilibrium, List.of(
				new FiniteThermalExchange.Contact(0, 350.0, 2_000.0, 100.0, 250.0, 500.0),
				new FiniteThermalExchange.Contact(1, 350.0, 1_000.0, 50.0, 250.0, 500.0)
		), 1_000.0, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		require(!noOp.changed() && noOp.substeps() == 1 && noOp.after().equals(equilibrium)
				&& noOp.contacts().stream().allMatch(contact -> contact.internalEnergyDeltaJ() == 0.0),
				"equilibrated finite thermal body produced a false durable mutation");

		ThermalMaterialDefinition segmented = new ThermalMaterialDefinition("test:thermal/segmented_device",
				ThermalMaterialDefinition.CarrierKind.DEVICE, "test:segmented_device",
				350.0, 0.0, 20.0, List.of(
						new ThermalMaterialDefinition.CaloricSegment(250.0, 300.0, 1.0),
						new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 1_000.0)));
		ThermalRegistry segmentedRegistry = new ThermalRegistry(List.of(segmented));
		ThermalStateFunction segmentedState = new ThermalStateFunction(segmentedRegistry);
		FiniteThermalBodyState segmentedBody = new FiniteThermalBodyState(1L,
				segmentedRegistry.fingerprint(), segmented.id(), mmm.units.Mass.fromKilograms(1.0),
				segmentedState.internalEnergyAtTemperature(0, 1.0, Map.of(), 400.0));
		double[] segmentedCellEnergy = {segmentedBody.internalEnergyJ()};
		ThermalCellGrid segmentedCell = new ThermalCellGrid(1);
		segmentedCell.rebuild(new ThermalCellGrid.CarrierAccess() {
			@Override public int carrierCount() { return 1; }
			@Override public int cell(int carrier) { return 0; }
			@Override public ThermalMaterialDefinition material(int carrier) { return segmented; }
			@Override public double mass(int carrier) { return 1.0; }
			@Override public double volume(int carrier) { return 1.0; }
			@Override public double baseEnergy(int carrier) { return 0.0; }
			@Override public double internalEnergy(int carrier) { return segmentedCellEnergy[carrier]; }
			@Override public void setInternalEnergy(int carrier, double energy) { segmentedCellEnergy[carrier] = energy; }
		});
		require(segmentedCell.minimumTemperature(0) <= 400
				&& segmentedCell.maximumTemperature(0) >= 400,
				"finite thermal contact omitted its actual caloric temperature from the prepared interval");
		expectFailure(() -> FiniteThermalExchange.plan(segmentedRegistry, segmentedBody, List.of(
				new FiniteThermalExchange.Contact(0, 250.0, 1_000.0, 100.0, 250.0, 500.0)
		), 1.0, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS),
				"finite thermal planner ignored a lower-capacity reachable caloric segment");
	}

	private static void finiteThermalExchangeRetainsEnergyScale() {
		for (double capacity : new double[]{100, 900, 4_180}) {
			var material = new ThermalMaterialDefinition("test:scaled_device", ThermalMaterialDefinition.CarrierKind.DEVICE,
					"test:scaled_device", 300, 0, 1, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1000, capacity)));
			var registry = new ThermalRegistry(List.of(material)); var state = new ThermalStateFunction(registry);
			var body = new FiniteThermalBodyState(0, registry.fingerprint(), material.id(), mmm.units.Mass.fromKilograms(1),
					state.internalEnergyAtTemperature(0, 1, Map.of(), 400));
			var plan = FiniteThermalExchange.plan(registry, body, List.of(
					new FiniteThermalExchange.Contact(0, 300, capacity, capacity, 1, 1000)), 1, 8);
			require(plan.changed() && Math.abs(plan.contacts().getFirst().finalTemperatureK() - 350) <= 1E-4
					&& Math.abs(state.stateForInternalEnergy(0, 1, Map.of(), plan.after().internalEnergyJ()).temperatureK() - 350) <= 1E-4
					&& SignedDyadic.of(plan.after().internalEnergyJ()).subtract(SignedDyadic.of(body.internalEnergyJ()))
							.add(SignedDyadic.of(plan.contacts().getFirst().internalEnergyDeltaJ())).equals(plan.roundoffResidual()),
					"finite exchange lost its normal caloric response or exact stored-energy ledger");
		}
	}

	private static void localThermalEditsPreserveRemoteCarrierEnergy() {
		FluidMaterial liquid = FluidMaterial.withSurfaceTension("test:local_heat", 1.0,
				"test:miscible", 0.0, 90.0);
		ThermalMaterialDefinition definition = new ThermalMaterialDefinition("test:thermal/local_heat",
				ThermalMaterialDefinition.CarrierKind.LIQUID, liquid.id(), 300.0, 0.0, 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0)));
		ThermalCarrierContext markers = new ThermalCarrierContext() {
			private final double[] liquidEnergy = new double[3];
			private long liquidRevision;
			@Override public double liquidInternalEnergy(int ordinal) { return liquidEnergy[ordinal]; }
			@Override public void setLiquidInternalEnergy(int ordinal, double energy) { liquidEnergy[ordinal] = energy; liquidRevision++; }
			@Override public long liquidInventoryRevision() { return liquidRevision; }
			@Override public int markerCount() { return 3; }
			@Override public int cell(int marker) { return marker == 0 ? 0 : 1; }
			@Override public int fluidMaterialHandle(int marker) { return 0; }
			@Override public double mass(int marker) { return 1.0; }
			@Override public double volume(int marker) { return 0.25; }
			@Override public ChemicalMixtureRecord mixture(int marker) { return null; }
			@Override public ChemicalRegistry chemicalRegistry() { return null; }
		};
		ThermalDomainState thermal = FluidThermalBinding.create(new ThermalRegistry(List.of(definition)),
				new FluidMaterialRegistry(List.of(liquid)), 300.0, 3, 2, 1, 1, 0.5F, markers);
		thermal.resetMarkerTemperature(1, 250.0, markers, thermal.exactEnergy(3));
		thermal.resetMarkerTemperature(2, 350.0, markers, thermal.exactEnergy(3));
		long cold = Double.doubleToRawLongBits(thermal.liquid().energy(1));
		long hot = Double.doubleToRawLongBits(thermal.liquid().energy(2));
		for (int operation = 0; operation < 3; operation++) {
			if (operation == 0) thermal.setTemperatureInCell(0, 320.0, markers);
			else if (operation == 1) {
				var plan = thermal.planFiniteDeviceCellExchange(List.of(
						new FiniteDeviceExchangeCoordinator.CellEnergyDelta(0, 100.0),
						new FiniteDeviceExchangeCoordinator.CellEnergyDelta(1, 0.0)), markers);
				thermal.commitFiniteDeviceCellExchange(plan, markers);
			} else thermal.repartitionCellEnergy(0, thermal.liquid().energy(0),
					markers, thermal.exactEnergy(3));
			require(Double.doubleToRawLongBits(thermal.liquid().energy(1)) == cold
					&& Double.doubleToRawLongBits(thermal.liquid().energy(2)) == hot,
					"local thermal edit equilibrated remote zero-conductivity carriers: operation=" + operation);
			require(thermal.exactEnergy(3).equals(thermal.auditLedger().expectedInventory()),
					"local thermal edit did not close its energy audit: operation=" + operation);
		}
	}

	static void productionFixedBoundaryFieldAuditsExchange() {
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/production_boundary_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				293.15, 0.0, 0.598,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 4_184.0)));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(4, 3, 3, config, 512);
		domain.setVolume(0, 1, 1, 1.0F);
		domain.bindThermal(new ThermalRegistry(List.of(water)), liquids, 293.15);
		double[] boundaries = new double[4 * 3 * 3 * 6];
		java.util.Arrays.fill(boundaries, Double.NaN);
		boundaries[((1 * 3 + 1) * 4) * 6] = 10_000_000.0; // -x domain wall of cell (0,1,1)
		require(domain.replaceThermalBoundaryTemperatures(boundaries)
				&& !domain.replaceThermalBoundaryTemperatures(boundaries),
				"production thermal boundary field did not detect an actual world edit exactly once");
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
				0L, domain.captureAuthoritativeState(), List.of(), false, 0));
		ThermalTransport.Stats transport = candidate.stats().thermalAxis().transport();
		domain = candidate.claimPreparedCpuDomain();
		double expectedOneCellFlux = 0.598 * (10_000_000.0 - 293.15) * config.timeStep();
		require(transport.reservoirExchange().compareTo(SignedDyadic.ZERO) > 0
				&& Math.abs(transport.reservoirExchange().toDouble() - expectedOneCellFlux) < 1.0E-6
				&& domain.thermalTemperatureInCell(0, 1, 1) > 293.15
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"production fixed boundary did not cross the frozen backend input and audit its finite-volume SI flux");
	}

	static void fixedBoundaryAuditsCaloricContinuation() {
		stiffContactPreservesLocalEnergy();
		FluidMaterial liquid = FluidMaterial.withSurfaceTension("test:boundary_liquid", 1.0,
				"test:miscible", 0.0, 90.0);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(liquid));
		ThermalMaterialDefinition definition = new ThermalMaterialDefinition("test:thermal/boundary_liquid",
				ThermalMaterialDefinition.CarrierKind.LIQUID, liquid.id(), 300.0, 0.0, 0.125,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0)));
		ThermalCarrierContext markers = new ThermalCarrierContext() {
			private final double[] liquidEnergy = new double[1];
			private long liquidRevision;
			@Override public double liquidInternalEnergy(int ordinal) { return liquidEnergy[ordinal]; }
			@Override public void setLiquidInternalEnergy(int ordinal, double energy) { liquidEnergy[ordinal] = energy; liquidRevision++; }
			@Override public long liquidInventoryRevision() { return liquidRevision; }
			@Override public int markerCount() { return 1; }
			@Override public int cell(int marker) { return 0; }
			@Override public int fluidMaterialHandle(int marker) { return 0; }
			@Override public double mass(int marker) { return 1.0; }
			@Override public double volume(int marker) { return 1.0; }
			@Override public ChemicalMixtureRecord mixture(int marker) { return null; }
			@Override public ChemicalRegistry chemicalRegistry() { return null; }
		};
		ThermalDomainState thermal = FluidThermalBinding.create(new ThermalRegistry(List.of(definition)), liquids,
				300.0, 1, 2, 2, 2, 0.5F, markers);
		SignedDyadic equilibriumEnergy = thermal.exactEnergy(1);
		ThermalTransport.Stats equilibrium = thermal.step(markers, (x, y, z, axis) -> 0.0,
				(x, y, z, direction) -> direction == 0
						? new ThermalTransport.FixedBoundary(0.25, 300.0) : null, 1_000.0);
		require(equilibrium.substeps() == 1 && equilibrium.reservoirExchange().equals(SignedDyadic.ZERO)
				&& thermal.exactEnergy(1).equals(equilibriumEnergy), "equilibrated boundary preserves energy");
		ThermalTransport.Stats heated = thermal.step(markers, (x, y, z, axis) -> 0.0,
				(x, y, z, direction) -> direction == 0
						? new ThermalTransport.FixedBoundary(0.25, 400.0) : null, 1.0);
		require(close(thermal.temperature(0, markers), 310.0)
				&& heated.reservoirExchange().equals(SignedDyadic.of(12.5))
				&& thermal.exactEnergy(1).equals(thermal.auditLedger().expectedInventory()),
				"fixed-temperature boundary did not close source/audit energy");
		ThermalTransport.Stats continued = thermal.step(markers, (x, y, z, axis) -> 0.0,
				(x, y, z, direction) -> direction == 0
						? new ThermalTransport.FixedBoundary(0.25, 10_000.0) : null, 10.0);
		require(thermal.temperature(0, markers) > definition.maximumTemperatureK()
				&& continued.reservoirExchange().toDouble() > 0.0
				&& thermal.exactEnergy(1).equals(thermal.auditLedger().expectedInventory()),
				"fixed-boundary transport did not conserve energy through caloric continuation");
	}

	private static void stiffContactPreservesLocalEnergy() {
		ThermalMaterialDefinition material = new ThermalMaterialDefinition("test:thermal/stiff",
				ThermalMaterialDefinition.CarrierKind.LIQUID, "test:stiff", 350.0, 0.0, 20.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(1.0, 300.0, 1.0),
						new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 1_000.0)));
		double[] mass = {.01, .01, 1, 1};
		double[] temperatures = {300.0, 300.0, 400.0, 250.0};
		double[] energies = new double[mass.length];
		for (int i = 0; i < mass.length; i++) energies[i] = mass[i] * material.sensibleSpecificEnergyAt(temperatures[i]);
		ThermalCellGrid grid = new ThermalCellGrid(mass.length);
		grid.rebuild(new ThermalCellGrid.CarrierAccess() {
			@Override public int carrierCount() { return mass.length; }
			@Override public int cell(int i) { return i; }
			@Override public ThermalMaterialDefinition material(int i) { return material; }
			@Override public double mass(int i) { return mass[i]; }
			@Override public double volume(int i) { return mass[i]; }
			@Override public double baseEnergy(int i) { return 0.0; }
			@Override public double internalEnergy(int i) { return energies[i]; }
			@Override public void setInternalEnergy(int i, double energy) { energies[i] = energy; }
		});
		double quietFirst = energies[0], quietSecond = energies[1];
		SignedDyadic before = SignedDyadic.sum(energies);
		ThermalTransport transport = new ThermalTransport(4, 1, 1, 1.0, 8);
		ThermalTransport.Stats internal = transport.step(grid, (x, y, z, axis) -> x == 1 ? 0.0 : 1.0, 0.05);
		require(energies[0] == quietFirst && energies[1] == quietSecond
				&& grid.temperature(2) < 400.0 && grid.temperature(3) > 250.0
				&& SignedDyadic.sum(energies).subtract(before).equals(internal.roundoffResidual()),
				"isolated equilibrium and active contact retain separate energy balances");
		before = SignedDyadic.sum(energies);
		ThermalTransport.Stats boundary = transport.step(grid, (x, y, z, axis) -> 0.0,
				(x, y, z, direction) -> x == 2 && direction == 2 ? new ThermalTransport.FixedBoundary(1.0, 1.0) : null, 10.0);
		require(grid.temperature(2) >= 1.0 && grid.temperature(2) < 300.0
				&& SignedDyadic.sum(energies).subtract(before).subtract(boundary.reservoirExchange()).equals(boundary.roundoffResidual()),
				"stiff boundary crosses caloric intervals with a closed finite heat transfer");

	}

	static void crowdedCellEnergyAssignmentPreservesEquilibrium() {
		var material = material("test:thermal/crowded", "test:crowded", 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(100.0, 500.0, 4180.0)));
		double temperature = 450;
		double[] energies = new double[2048];
		Arrays.fill(energies, 0.1 * material.sensibleSpecificEnergyAt(temperature));
		double requested = SignedDyadic.sum(energies).toDouble();
		ThermalCellGrid grid = new ThermalCellGrid(1);
		grid.rebuild(new ThermalCellGrid.CarrierAccess() {
			@Override public int carrierCount() { return energies.length; }
			@Override public int cell(int i) { return 0; }
			@Override public ThermalMaterialDefinition material(int i) { return material; }
			@Override public double mass(int i) { return 0.1; }
			@Override public double volume(int i) { return 0.0001; }
			@Override public double baseEnergy(int i) { return 0.0; }
			@Override public double internalEnergy(int i) { return energies[i]; }
			@Override public void setInternalEnergy(int i, double value) { energies[i] = value; }
		});
		grid.setCellInternalEnergies(new double[] {requested});
		require(Math.abs(grid.temperature(0) - temperature) <= 1E-4,
				"crowded hot cell lost local equilibrium through its carrier sum");
		require(SignedDyadic.sum(energies).equals(SignedDyadic.of(requested)),
				"crowded hot cell failed exact energy assignment");
	}


	private static FluidDomain advance(FluidDomain domain) {
		return new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0, domain.captureAuthoritativeState(), List.of(), false, 0)).claimPreparedCpuDomain();
	}

}
