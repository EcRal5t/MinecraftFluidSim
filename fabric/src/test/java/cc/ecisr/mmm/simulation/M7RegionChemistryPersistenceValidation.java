package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.application.RegionCatalog;
import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FlipChemicalParticleState;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.simulation.persistence.RegionState;
import cc.ecisr.mmm.simulation.persistence.RegionGenerationStore;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalSpeciesOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

	/** Current feature-layout admission and restore checks for chemistry, granular and thermal regions. */
public final class M7RegionChemistryPersistenceValidation {
	private static final FluidSolverConfig CONFIG = new FluidSolverConfig(
			0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	private static final ResourceKey<Registry<Level>> DIMENSION_REGISTRY = ResourceKey.createRegistryKey(
			Identifier.fromNamespaceAndPath("mmm", "m7_persistence_registry"));
	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(DIMENSION_REGISTRY,
			Identifier.fromNamespaceAndPath("mmm", "m7_persistence"));

	private M7RegionChemistryPersistenceValidation() { }

	public static void main(String[] args) throws Exception {
		validateChemicalFeatureLayout();
		validateGranularFeatureLayout();
		validateThermalProductionGate();
		customViscosityRestoresFromCommittedBundle();
		System.out.println("Region persistence passed: current chemistry, granular and thermal feature layouts");
	}

	private static void customViscosityRestoresFromCommittedBundle() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "custom-viscosity-restore-");
		var production = FullCapabilityTestFixture.load();
		var custom = FullCapabilityTestFixture.doubledWaterViscosity();
		var definitions = new cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
				java.util.EnumSet.allOf(cc.ecisr.mmm.worldprofile.WorldCapability.class), custom,
				production.chemical(), production.granular(), production.gas(), production.thermal());
		var store = new cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleStore(root,
				new cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleStore.QuarantineAdmission(64, 64L << 20));
		var committed = store.openOrPublish(definitions).bundle();
		FluidRegion source = populatedRegion(UUID.randomUUID());
		bindDefaultMaterials(source);
		FullCapabilityTestFixture.bindFull(source.domain(), new FullCapabilityTestFixture.Registries(
				production.liquids(), production.chemical(), production.granular(), production.gas(),
				production.thermal(), custom), 293.15, 4);
		source.domain().setThermalTemperatureInCell(2, 1, 2, 353.15);
		try {
			new RegionRepositoryAdapter(root, committed.profile(), committed.chemical(), committed.granular(),
					committed.thermal(), committed.gas(), 4, production.materials());
			throw new AssertionError("repository accepted definitions from another world profile");
		} catch (IllegalArgumentException expectedMismatch) {
			check(expectedMismatch.getMessage().contains("world profile"), "unexpected repository rejection");
		}
		try {
			new RegionRepositoryAdapter(root, TestProfiles.full(), production.chemical(), production.granular(),
					production.thermal(), production.gas(), 4, production.materials()).checkpoint(source);
			throw new AssertionError("checkpoint reinterpreted a domain with different material definitions");
		} catch (java.io.IOException expectedMismatch) {
			check(expectedMismatch.getMessage().contains("material definitions"), "unexpected checkpoint rejection");
		}
		var writer = new RegionRepositoryAdapter(root, committed.profile(), committed.chemical(), committed.granular(),
				committed.thermal(), committed.gas(), 4, committed.material());
		writer.checkpoint(source);
		var drifted = store.openOrPublish(new cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
				definitions.capabilities(), production.materials(), production.chemical(), production.granular(),
				production.gas(), production.thermal()));
		check(drifted.definitionMismatch(), "changed datapack definitions must report drift");
		var restoredBundle = drifted.bundle();
		var reader = new RegionRepositoryAdapter(root, restoredBundle.profile(), restoredBundle.chemical(),
				restoredBundle.granular(), restoredBundle.thermal(), restoredBundle.gas(), 4, restoredBundle.material());
		FluidRegionManager manager = manager();
		check(reader.loadMetadata(manager).isEmpty(), "custom viscosity checkpoint metadata was rejected");
		FluidRegion restored = manager.regions().getFirst();
		reader.restore(restored);
		cc.ecisr.mmm.fluid.FluidDomainStateAssertions.assertSameDurableState(
				source.domain().captureAuthoritativeState(), restored.domain().captureAuthoritativeState(),
				"custom viscosity disk restore");
		int cell = (2 * 4 + 1) * 6 + 2;
		double actual = restored.domain().carrierViscosityPaPerS(cell);
		double expected = custom.dynamicViscosityAt("mmm:water", 353.15);
		check(Math.abs(actual - expected) <= expected * 1e-12
				&& actual > 1.9 * production.materials().dynamicViscosityAt("mmm:water", 353.15),
				"disk restore replaced committed viscosity with built-in definitions");
	}

	private static void validateThermalProductionGate() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "thermal-production-");
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		ThermalRegistry thermal = productionThermalRegistry();
		FluidRegion source = populatedRegion(UUID.randomUUID());
		bindDefaultMaterials(source);
		source.domain().bindGasRegistry(gas);
		source.domain().bindThermal(thermal, source.domain().materialRegistry(), 300.0);
		source.domain().setThermalTemperatureInCell(2, 1, 2, 325.0);
		RegionRepositoryAdapter persistence = new RegionRepositoryAdapter(root, TestProfiles.thermal(null, thermal), null, null, thermal, gas, TestProfiles.materialRegistry());
		check(persistence.checkpoint(source) == 1L, "production thermal checkpoint was not published");

		FluidRegionManager manager = manager();
		RegionRepositoryAdapter restoring = new RegionRepositoryAdapter(root, TestProfiles.thermal(null, thermal), null, null, thermal, gas, TestProfiles.materialRegistry());
		check(restoring.loadMetadata(manager).isEmpty(), "valid thermal metadata was locked");
		FluidRegion admitted = manager.regions().getFirst(); restoring.restore(admitted);
		check(admitted.solverLoaded() && admitted.domain().hasThermalState()
				&& admitted.domain().exportGasState("").registryFingerprint().equals(gas.fingerprint())
				&& admitted.domain().thermalAudit().equals(source.domain().thermalAudit()),
				"production restore did not reconstruct gas plus thermal truth");

		FluidRegionManager constrained = new FluidRegionManager(
				new RegionCatalog.Limits(1, 100L, 100L, 100L, 100L));
		check(restoring.loadMetadata(constrained).isEmpty() && constrained.regions().size() == 1,
				"temporary policy budget discarded valid committed metadata");
		FluidRegion retained = constrained.regions().getFirst();
		check(!retained.solverLoaded() && retained.availabilityReason() == RegionAvailabilityReason.BUDGET,
				"over-budget committed metadata was not retained as PAUSED_BUDGET");

		FluidRegionManager gateOff = manager();
		List<String> gateIssues = new RegionRepositoryAdapter(root, TestProfiles.materials(), null, null, null, null, TestProfiles.materialRegistry()).loadMetadata(gateOff);
		check(gateOff.regions().isEmpty() && gateIssues.stream().anyMatch(issue -> issue.contains("thermal")),
				"thermal-off discovery did not preserve and lock thermal region state");

	}

	private static ThermalRegistry productionThermalRegistry() {
		return productionThermalRegistry(null, null);
	}

	private static ThermalRegistry productionThermalRegistry(
			ChemicalRegistry chemistry, GranularRegistry granular
	) {
		List<ThermalMaterialDefinition> definitions = new java.util.ArrayList<>();
		java.util.Map<String, String> liquidHosts = new java.util.HashMap<>();
		for (var liquid : BuiltinFluidMaterials.DEFAULT_PALETTE) {
			String id = "test:thermal/" + liquid.id().substring(liquid.id().indexOf(':') + 1);
			liquidHosts.put(liquid.id(), id);
			definitions.add(new ThermalMaterialDefinition(id,
				ThermalMaterialDefinition.CarrierKind.LIQUID, liquid.id(), 293.15, 0.0, 0.5,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 1000.0))));
		}
		definitions.add(new ThermalMaterialDefinition("test:thermal/air", ThermalMaterialDefinition.CarrierKind.GAS,
				BuiltinGasDefinitions.AIR, 293.15, 0.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 718.0))));
		definitions.add(new ThermalMaterialDefinition("test:thermal/vapor", ThermalMaterialDefinition.CarrierKind.GAS,
				BuiltinGasDefinitions.WATER_VAPOR, 293.15, 2_400_000.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 1850.0))));
		java.util.Map<String, String> granularHosts = new java.util.HashMap<>();
		if (granular != null) for (var material : granular.materials()) {
			String id = "test:thermal/granular_" + material.id().substring(material.id().indexOf(':') + 1);
			granularHosts.put(material.id(), id);
			definitions.add(new ThermalMaterialDefinition(id, ThermalMaterialDefinition.CarrierKind.GRANULAR,
					material.id(), 293.15, 0.0, 0.5,
					List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 800.0))));
		}
		List<ThermalSpeciesOffset> offsets = new java.util.ArrayList<>();
		int offsetOrdinal = 0;
		if (chemistry != null) for (var phase : chemistry.phases()) {
			String host = liquidHosts.get(phase.materialId());
			for (String species : phase.allowedSpecies()) offsets.add(new ThermalSpeciesOffset(
					"test:offset/liquid_" + offsetOrdinal++, species, host, ExactRational.ZERO));
		}
		if (granular != null) for (var reaction : granular.reactions()) {
			String host = granularHosts.get(reaction.solidMaterialId());
			for (String species : reaction.solidComposition().keySet()) offsets.add(new ThermalSpeciesOffset(
					"test:offset/granular_" + offsetOrdinal++, species, host, ExactRational.ZERO));
		}
		return new ThermalRegistry(definitions, offsets, List.of(),
				chemistry == null ? "" : chemistry.fingerprint(),
				granular == null ? "" : granular.fingerprint());
	}

	private static void validateGranularFeatureLayout() throws Exception {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		Path gateRoot = Files.createTempDirectory(Path.of("."), "granular-gate-off-");
		FluidRegion source = populatedRegion(UUID.randomUUID()); bindDefaultMaterials(source);
		source.domain().bindChemistry(chemistry);
		source.domain().bindGranular(granular, source.domain().materialRegistry(), TestProfiles.materialRegistry());
		new RegionGenerationStore(gateRoot, chemistry, granular).publish(RegionCheckpointFixtures.captureGranular(source, 1L));
		FluidRegionManager currentManager = manager();
		RegionRepositoryAdapter current = new RegionRepositoryAdapter(gateRoot, TestProfiles.granular(chemistry, granular), chemistry, granular, null, null, TestProfiles.materialRegistry());
		check(current.loadMetadata(currentManager).isEmpty(), "current granular layout was locked");
		FluidRegion restored = currentManager.regions().getFirst(); current.restore(restored);
		check(restored.domain().hasChemistryState() && restored.domain().hasGranularState()
				&& restored.domain().precipitationEnabled(), "current granular layout did not restore");
		FluidRegionManager gateManager = manager();
		List<String> issues = new RegionRepositoryAdapter(gateRoot, TestProfiles.materials(), null, null, null, null, TestProfiles.materialRegistry()).loadMetadata(gateManager);
		check(gateManager.regions().isEmpty() && !issues.isEmpty(),
				"granular-off discovery did not preserve and lock granular region state");
	}

	private static void validateChemicalFeatureLayout() throws Exception {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		Path gateRoot = Files.createTempDirectory(Path.of("."), "chemical-gate-off-");
		FluidRegion chemical = populatedRegion(UUID.randomUUID());
		bindDefaultMaterials(chemical);
		chemical.domain().bindChemistry(chemistry);
		new RegionGenerationStore(gateRoot, chemistry).publish(RegionCheckpointFixtures.captureChemical(chemical, 1L));
		FluidRegionManager currentManager = manager();
		RegionRepositoryAdapter current = new RegionRepositoryAdapter(gateRoot, TestProfiles.chemical(chemistry), chemistry, null, null, null, TestProfiles.materialRegistry());
		check(current.loadMetadata(currentManager).isEmpty(), "current chemical layout was locked");
		FluidRegion restored = currentManager.regions().getFirst(); current.restore(restored);
		assertChemicalStateEquals(chemical.domain().exportChemicalParticleState(), restored.domain().exportChemicalParticleState());
		FluidRegionManager gateManager = manager();
		List<String> issues = new RegionRepositoryAdapter(gateRoot, TestProfiles.materials(), null, null, null, null, TestProfiles.materialRegistry()).loadMetadata(gateManager);
		check(gateManager.regions().isEmpty() && !issues.isEmpty(),
				"chemistry-off discovery did not preserve and lock chemical region state");
	}

	private static FluidRegionManager manager() {
		return new FluidRegionManager(new RegionCatalog.Limits(4, 10_000L, 100_000L, 40_000L, 400_000L));
	}

	private static FluidRegion populatedRegion(UUID id) {
		BlockPos origin = new BlockPos(15, 16, 15);
		FluidDomain domain = new FluidDomain(6, 4, 6, CONFIG, 512);
		domain.setSolid(0, 0, 0, true);
		domain.setVolume(1, 1, 1, 0.25F);
		domain.setVolume(2, 1, 2, 1.0F);
		domain.setVolume(4, 2, 4, 0.625F);
		Set<Long> interior = new HashSet<>();
		for (int z = 15; z <= 17; z++) for (int y = 16; y <= 17; y++) {
			for (int x = 15; x <= 17; x++) interior.add(new BlockPos(x, y, z).asLong());
		}
		BlockPos controller = new BlockPos(14, 16, 15);
		Set<Long> chunks = Set.of(ChunkPos.pack(0, 0), ChunkPos.pack(1, 0), ChunkPos.pack(0, 1), ChunkPos.pack(1, 1));
		FluidRegionFootprint footprint = new FluidRegionFootprint(controller, origin, new BlockPos(17, 17, 17),
				interior, Set.of(controller.asLong()), chunks);
		return new FluidRegion(id, DIMENSION, origin, domain, 512L, footprint);
	}

	private static void bindDefaultMaterials(FluidRegion region) {
		FlipParticleState pure = region.domain().exportParticleState();
		float[] positions = pure.copyPositionVolume();
		double[] mass = new double[pure.particleCount()];
		double totalMass = 0.0;
		for (int marker = 0; marker < pure.particleCount(); marker++) {
			mass[marker] = positions[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			totalMass += mass[marker];
		}
		region.domain().restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(), pure.totalVolume(),
				totalMass, BuiltinFluidMaterials.DEFAULT_PALETTE, positions, pure.copyParticleVelocities(), pure.copySolidMask(),
				mass, new int[pure.particleCount()]));
	}

	private static void assertChemicalStateEquals(FlipChemicalParticleState expected, FlipChemicalParticleState actual) {
		check(expected.materials().materials().equals(actual.materials().materials())
				&& Arrays.equals(expected.materials().copyPositionVolume(), actual.materials().copyPositionVolume())
				&& Arrays.equals(expected.materials().copyParticleMass(), actual.materials().copyParticleMass())
				&& expected.mixtures().equals(actual.mixtures())
				&& Arrays.equals(expected.copyMarkerMixtureIndex(), actual.copyMarkerMixtureIndex())
				&& Arrays.equals(expected.copyIndicator(), actual.copyIndicator())
				&& Arrays.equals(expected.copySpeciesTotals(), actual.copySpeciesTotals())
				&& expected.residualLedgerBits() == actual.residualLedgerBits()
				&& expected.externalSource().groups().equals(actual.externalSource().groups())
				&& expected.escapedSink().groups().equals(actual.escapedSink().groups()),
				"chemical state changed across persistence");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
