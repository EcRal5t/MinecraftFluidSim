package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalDomainState;

import cc.ecisr.mmm.thermal.ThermalCarrierContext;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.granular.GranularTransferLedger;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.thermal.GranularEnergyColumn;
import cc.ecisr.mmm.thermal.LiquidThermalExpansion;
import cc.ecisr.mmm.units.SolverUnitScale;
import cc.ecisr.mmm.thermal.FiniteThermalBodyState;
import cc.ecisr.mmm.thermal.FiniteThermalExchange;
import cc.ecisr.mmm.thermal.ThermalCellGrid;
import cc.ecisr.mmm.thermal.ThermalAuditLedger;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.thermal.ThermalTransport;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** One retained selector for M9 definition, state-function and later transport/phase invariants. */

abstract class ThermalTransportValidationScenarios extends ThermalValidationSupport {
	static void containerTransferCarriesAuthoritativeEnergy() {
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/container_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				300.0, 0.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 400.0, 4_000.0)));
		ThermalRegistry registry = new ThermalRegistry(List.of(water));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(4, 3, 3, config, 512);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.bindThermal(registry, liquids, 300.0);
		domain.removeAllWaterExact();

		double importedEnergy = SolverUnitScale.forCellSize(config.cellSize()).kilograms(1_000.0)
				* 4_000.0 * 20.0;
		FluidDomain.ExactVolumeTransfer inserted = domain.tryInsertExactVolume(
				1, 1, 1, 1.0F, 1, 0, false, importedEnergy);
		require(inserted.transferred() && close(inserted.internalEnergy(), importedEnergy)
				&& close(domain.thermalTemperatureInCell(1, 1, 1), 320.0)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"explicit container insertion did not assign item U through the state function");

		FluidDomain.ExactVolumeTransfer extracted = domain.tryExtractExactVolume(1, 1, 1, 1.0F, 1, 0);
		require(extracted.transferred() && close(extracted.internalEnergy(), importedEnergy)
				&& domain.particleCount() == 0 && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"container extraction did not return the exact removed marker energy");
		FluidDomain.ExactVolumeTransfer restored = domain.tryInsertExactVolume(
				1, 1, 1, 1.0F, 1, 0, false, extracted.internalEnergy());
		require(restored.transferred() && close(domain.thermalTemperatureInCell(1, 1, 1), 320.0)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"container energy did not survive an extract/reinsert round trip");

		FluidDomain.MaterialDisplacement collision = domain.setSolidMicroMaskMaterial(1, 1, 1, 0xff);
		require(close(collision.internalEnergy(), importedEnergy) && collision.portions().size() == 1
				&& domain.particleCount() == 0,
				"collision displacement did not carry the removed material energy ledger");
		domain.setSolidMicroMaskMaterial(1, 1, 1, 0);
		FluidDomain.ExactMixtureTransfer relocated = domain.tryInsertExactMixture(
				1, 1, 1, collision.portions(), 1, false, collision.internalEnergy());
		require(relocated.transferred() && close(domain.thermalTemperatureInCell(1, 1, 1), 320.0)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"collision relocation did not restore material U without a guessed temperature");
	}

	static void granularEnergyColumnTransactions() {
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

		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry productionLiquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.8F);
		FluidDomain domain = new FluidDomain(8, 8, 8, config, 8 * 8 * 8 * 8);
		domain.bindGranular(granular, productionLiquids, BuiltinFluidMaterials.createRegistry());
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long parcelSvq = SolidVolume.SVQ_PER_BV;
		double parcelMass = parcelSvq / (double) SolidVolume.SVQ_PER_BV
				* granular.material(sand).referenceDensity();
		GranularTransferLedger bucket = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(
				BuiltinGranularDefinitions.FINE_SAND, parcelSvq, parcelMass, null)));
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
		for (int step = 0; step < 180 && domain.granularDepositCellCount() == 0; step++) domain.step();
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
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(4, 3, 3, config, 512);
		domain.setVolume(1, 1, 1, 1.0F);
		domain.setVolume(2, 1, 1, 1.0F);
		domain.bindThermal(registry, liquids, 300.0);
		domain.setThermalTemperatureInCell(1, 1, 1, 350.0);
		domain.setThermalTemperatureInCell(2, 1, 1, 250.0);
		SignedDyadic before = domain.exactInternalEnergy();
		FluidDomain stiff = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		stiff.stepThermalOnly(10_000.0);
		require(close(stiff.thermalTemperatureInCell(1, 1, 1), 300.0 + 50.0 / 121.0)
				&& stiff.exactInternalEnergy().equals(stiff.thermalAudit().expectedInventory()),
				"stiff pair matches two conservative backward-Euler half steps");
		domain.stepThermalOnly(1_000.0);
		double hot = domain.thermalTemperatureInCell(1, 1, 1);
		double cold = domain.thermalTemperatureInCell(2, 1, 1);
		require(hot < 350.0 && hot >= 300.0 && cold > 250.0 && cold <= 300.0 && hot >= cold,
				"pairwise thermal transport was not monotone: hot=" + hot + " cold=" + cold);
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"transient thermal transport did not close its audit ledger");
		require(Math.abs(domain.exactInternalEnergy().subtract(before).toDouble()) <= 1.0E-6,
				"adiabatic thermal transport changed total internal energy beyond representation residual");
		double[] equilibratedCarrierEnergy = domain.exportThermalParticleState().copyInternalEnergy();
		ThermalTransport.Stats noFlux = domain.stepThermalOnly(10_000.0);
		require(noFlux.substeps() == 1 && noFlux.maximumAbsoluteTransferEu() == 0.0
				&& Arrays.equals(equilibratedCarrierEnergy,
						domain.exportThermalParticleState().copyInternalEnergy()),
				"zero-flux LTE transport rewrote authoritative carrier energy");

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
				&& Arrays.equals(restoredGas.cellNodeOrdinal(), expectedGas.cellNodeOrdinal())
				&& Arrays.equals(restoredGas.cellLineageOrdinal(), expectedGas.cellLineageOrdinal());
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
				100, 1.0E-5F, 0.40F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(6, 6, 6, config, 1_728);
		for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
			if (x == 0 || x == 5 || y == 0 || y == 5 || z == 0 || z == 5) domain.setSolid(x, y, z, true);
			else domain.setVolume(x, y, z, 1.0F);
		}
		domain.bindThermal(new ThermalRegistry(List.of(water)), liquids, 293.15);
		domain.setThermalTemperatureInCell(2, 1, 2, 333.15);
		for (int step = 0; step < 4; step++) domain.step();
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
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
		FluidDomain domain = new FluidDomain(4, 3, 3, config, 512);
		// Two microcells obstruct the shared +x face: the authoritative aperture is exactly one half.
		domain.setSolidMicroMask(1, 1, 1, 0x22);
		domain.setVolume(1, 1, 1, 0.5F);
		domain.setVolume(2, 1, 1, 1.0F);
		domain.bindThermal(new ThermalRegistry(List.of(water)), liquids, 300.0);
		domain.setThermalTemperatureInCell(1, 1, 1, 350.0);
		domain.setThermalTemperatureInCell(2, 1, 1, 250.0);
		ThermalTransport.Stats transport = domain.stepThermalOnly(1_000.0);
		require(transport.evaluatedFaces() == 1 && transport.maximumAbsoluteTransferEu() > 0.0
				&& domain.thermalTemperatureInCell(1, 1, 1) < 350.0
				&& domain.thermalTemperatureInCell(2, 1, 1) > 250.0,
				"partial cut/PLIC contact was dropped from production thermal transport");
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"partial cut/PLIC thermal contact did not close its energy audit");
	}

	static void finiteThermalBodyExchangesWithoutMixing() {
		ThermalMaterialDefinition wall = new ThermalMaterialDefinition("test:thermal/exchanger_wall",
				ThermalMaterialDefinition.CarrierKind.DEVICE, "test:exchanger_wall",
				300.0, 0.0, 20.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 500.0, 500.0)));
		ThermalRegistry registry = new ThermalRegistry(List.of(wall));
		ThermalStateFunction stateFunction = new ThermalStateFunction(registry);
		double initialEnergy = stateFunction.internalEnergyAtTemperature(
				registry.materialHandle(wall.id()), 10.0, Map.of(), 400.0);
		FiniteThermalBodyState body = new FiniteThermalBodyState(
				7L, registry.fingerprint(), wall.id(), 10.0, initialEnergy);
		FiniteThermalExchange.Plan plan = FiniteThermalExchange.plan(registry, body, List.of(
				new FiniteThermalExchange.Contact(0, 300.0, 2_000.0, 100.0, 250.0, 500.0),
				new FiniteThermalExchange.Contact(1, 350.0, 1_000.0, 50.0, 250.0, 500.0)
		), 1.0, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		FiniteThermalExchange.ContactDelta cold = plan.contacts().get(0);
		FiniteThermalExchange.ContactDelta warm = plan.contacts().get(1);
		SignedDyadic jointChange = SignedDyadic.of(plan.after().internalEnergyEu())
				.subtract(SignedDyadic.of(plan.before().internalEnergyEu()))
				.add(SignedDyadic.of(cold.internalEnergyDeltaEu()))
				.add(SignedDyadic.of(warm.internalEnergyDeltaEu()));
		require(plan.changed() && plan.before().generation() == 7L && plan.after().generation() == 8L
				&& cold.ordinal() == 0 && warm.ordinal() == 1
				&& close(cold.internalEnergyDeltaEu(), 10_000.0)
				&& close(warm.internalEnergyDeltaEu(), 2_500.0)
				&& close(cold.finalTemperatureK(), 305.0)
				&& close(warm.finalTemperatureK(), 352.5)
				&& close(stateFunction.stateForInternalEnergy(registry.materialHandle(wall.id()), 10.0,
						Map.of(), plan.after().internalEnergyEu()).temperatureK(), 397.5)
				&& jointChange.equals(plan.roundoffResidual()),
				"finite thermal body did not produce a closed three-owner energy plan");

		double equilibriumEnergy = stateFunction.internalEnergyAtTemperature(
				registry.materialHandle(wall.id()), 10.0, Map.of(), 350.0);
		FiniteThermalBodyState equilibrium = new FiniteThermalBodyState(
				9L, registry.fingerprint(), wall.id(), 10.0, equilibriumEnergy);
		FiniteThermalExchange.Plan noOp = FiniteThermalExchange.plan(registry, equilibrium, List.of(
				new FiniteThermalExchange.Contact(0, 350.0, 2_000.0, 100.0, 250.0, 500.0),
				new FiniteThermalExchange.Contact(1, 350.0, 1_000.0, 50.0, 250.0, 500.0)
		), 1_000.0, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		require(!noOp.changed() && noOp.substeps() == 1 && noOp.after().equals(equilibrium)
				&& noOp.contacts().stream().allMatch(contact -> contact.internalEnergyDeltaEu() == 0.0),
				"equilibrated finite thermal body produced a false durable mutation");

		ThermalMaterialDefinition segmented = new ThermalMaterialDefinition("test:thermal/segmented_device",
				ThermalMaterialDefinition.CarrierKind.DEVICE, "test:segmented_device",
				350.0, 0.0, 20.0, List.of(
						new ThermalMaterialDefinition.CaloricSegment(250.0, 300.0, 1.0),
						new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 1_000.0)));
		ThermalRegistry segmentedRegistry = new ThermalRegistry(List.of(segmented));
		ThermalStateFunction segmentedState = new ThermalStateFunction(segmentedRegistry);
		FiniteThermalBodyState segmentedBody = new FiniteThermalBodyState(1L,
				segmentedRegistry.fingerprint(), segmented.id(), 1.0,
				segmentedState.internalEnergyAtTemperature(0, 1.0, Map.of(), 400.0));
		double[] segmentedCellEnergy = {segmentedBody.internalEnergyEu()};
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
		require(segmentedCell.minimumTemperature(0) == 300.0
				&& segmentedCell.maximumTemperature(0) == Double.MAX_VALUE,
				"finite thermal contact did not expose the endpoint-caloric continuation interval");
		expectFailure(() -> FiniteThermalExchange.plan(segmentedRegistry, segmentedBody, List.of(
				new FiniteThermalExchange.Contact(0, 250.0, 1_000.0, 100.0, 250.0, 500.0)
		), 1.0, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS),
				"finite thermal planner ignored a lower-capacity reachable caloric segment");
	}

	static void productionFixedBoundaryFieldAuditsExchange() {
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		ThermalMaterialDefinition water = new ThermalMaterialDefinition("mmm:thermal/production_boundary_water",
				ThermalMaterialDefinition.CarrierKind.LIQUID, BuiltinFluidMaterials.WATER.id(),
				293.15, 0.0, 0.598,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 4_184.0)));
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
				100, 1.0E-5F, 0.45F, 2.0E-3F, 0.0F);
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
		double[] mass = {1.0E-8, 1.0E-8, 1.0, 1.0};
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
		for (int scenario = 0; scenario < 3; scenario++) {
			ThermalMaterialDefinition caloric = new ThermalMaterialDefinition("test:thermal/precision",
					ThermalMaterialDefinition.CarrierKind.LIQUID, "test:precision", 300.0, 0.0, 20.0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(100.0, 300.0, 10.0),
							new ThermalMaterialDefinition.CaloricSegment(300.0, 500.0, 100.0)));
			double carrierMass = scenario == 0 ? 1.0e-14 : scenario == 1 ? 1.0e-8 : 1.0e-15;
			double initialTemperature = scenario == 1 ? 1.0 : 350.0;
			double boundaryTemperature = scenario == 1 ? Math.nextUp(1.0) : 400.0;
			double baseEnergy = scenario == 2 ? 1.0 : 0.0;
			double[] energy = {baseEnergy + carrierMass * caloric.sensibleSpecificEnergyAt(initialTemperature)};
			ThermalCellGrid small = new ThermalCellGrid(1);
			small.rebuild(new ThermalCellGrid.CarrierAccess() {
				@Override public int carrierCount() { return 1; }
				@Override public int cell(int i) { return 0; }
				@Override public ThermalMaterialDefinition material(int i) { return caloric; }
				@Override public double mass(int i) { return carrierMass; }
				@Override public double volume(int i) { return 1.0; }
				@Override public double baseEnergy(int i) { return baseEnergy; }
				@Override public double internalEnergy(int i) { return energy[0]; }
				@Override public void setInternalEnergy(int i, double value) { energy[0] = value; }
			});
			require(scenario == 2 || Math.abs(small.temperature(0) - initialTemperature) < 1.0e-10,
					"caloric inversion preserves the tiny carrier temperature");
			SignedDyadic initial = SignedDyadic.of(energy[0]);
			var exchange = new ThermalTransport(1, 1, 1, 1.0, 8).step(small, (x,y,z,a) -> 0.0,
					(x,y,z,d) -> d == 0 ? new ThermalTransport.FixedBoundary(1.0, boundaryTemperature) : null, 0.05);
			require(SignedDyadic.of(energy[0]).subtract(initial).subtract(exchange.reservoirExchange())
					.equals(exchange.roundoffResidual()), "tiny and adjacent-temperature contacts close their energy ledger");
		}
	}

	static void crowdedCellEnergyAssignmentPreservesEquilibrium() {
		var material = material("test:thermal/crowded", "test:crowded", 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(100.0, 500.0, 4180.0)));
		double temperature = 3_117_853.5263624573;
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
		require(grid.carriersMatchSolvedTemperature() && close(grid.temperature(0), temperature),
				"crowded hot cell lost local equilibrium through its carrier sum");
		require(SignedDyadic.sum(energies).equals(SignedDyadic.of(requested)),
				"crowded hot cell failed exact energy assignment");
	}

	static void reseedingMovesEnergyWithMarkerTruth() {
		int capacity = 64, count = 17;
		boolean[] solid = new boolean[4 * 4 * 4];
		float[] x = new float[capacity], y = new float[capacity], z = new float[capacity];
		float[] vx = new float[capacity], vy = new float[capacity], vz = new float[capacity];
		float[] volume = new float[capacity];
		double[] mass = new double[capacity], energy = new double[capacity];
		int[] material = new int[capacity], phase = new int[capacity];
		for (int marker = 0; marker < 16; marker++) {
			x[marker] = 1.1F + (marker & 3) * 0.02F;
			y[marker] = 1.1F + (marker >> 2 & 3) * 0.02F;
			z[marker] = 1.1F;
			volume[marker] = 1.0F / 64.0F;
			mass[marker] = volume[marker] * 1_000.0;
			energy[marker] = mass[marker] * 7.0;
		}
		x[16] = 2.25F; y[16] = 1.25F; z[16] = 1.25F;
		volume[16] = 0.5F; mass[16] = 500.0; energy[16] = 3_500.0;
		SignedDyadic before = SignedDyadic.sum(java.util.Arrays.copyOf(energy, count));
		FlipParticleReseeder.ThermalAdapter adapter = new FlipParticleReseeder.ThermalAdapter() {
			@Override public double totalEnergy(int particleCount) {
				double total = 0.0;
				for (int marker = 0; marker < particleCount; marker++) total += energy[marker];
				return total;
			}
			@Override public void merge(int target, int source) { energy[target] += energy[source]; }
			@Override public FlipParticleReseeder.ThermalSplit beforeSplit(int source) {
				return new FlipParticleReseeder.ThermalSplit(energy[source], energy[source] / mass[source]);
			}
			@Override public void afterSplit(FlipParticleReseeder.ThermalSplit split, int source, int destination) {
				energy[source] = mass[source] * split.temperatureK();
				energy[destination] = split.parentEnergy() - energy[source];
			}
			@Override public void move(int source, int destination) { energy[destination] = energy[source]; }
		};
		FlipParticleReseeder.Result result = new FlipParticleReseeder(4, 4, 4, 1.0F, capacity).reseed(
				solid, x, y, z, vx, vy, vz, volume, mass, material, phase, null, adapter, count, false,
				(candidateX, candidateY, candidateZ) -> true);
		SignedDyadic after = SignedDyadic.sum(java.util.Arrays.copyOf(energy, result.particleCount()));
		require(result.merged() > 0 && result.split() > 0 && before.equals(after),
				"thermal reseeding did not move energy with merged/split marker truth");
	}

}
