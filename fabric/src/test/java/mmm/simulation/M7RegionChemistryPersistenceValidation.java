package mmm.simulation;

import mmm.api.RegionAvailabilityReason;
import mmm.application.RegionCatalog;
import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ExactRational;
import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.FlipChemicalParticleState;
import mmm.fluid.HostFluidState;
import mmm.fluid.FlipMaterialParticleState;
import mmm.fluid.FlipParticleState;
import mmm.fluid.FluidSolverConfig;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.fluid.air.GasRegistry;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.GranularRegistry;
import mmm.simulation.controller.FluidRegionFootprint;
import mmm.simulation.persistence.RegionState;
import mmm.simulation.persistence.RegionGenerationStore;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalSpeciesOffset;
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
	private static final FluidSolverConfig CONFIG = new FluidSolverConfig(0.5F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 2.0E-3F);
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
		materialOnlyViscosityRestores();
		System.out.println("Region persistence passed: current chemistry, granular and thermal feature layouts");
	}

	private static void customViscosityRestoresFromCommittedBundle() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "custom-viscosity-restore-");
		var production = FullCapabilityTestFixture.load();
		var custom = FullCapabilityTestFixture.doubledWaterViscosity();
		var definitions = new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
				java.util.EnumSet.allOf(mmm.worldprofile.WorldCapability.class), custom,
				production.chemical(), production.granular(), production.gas(), production.thermal());
		var store = new mmm.simulation.persistence.WorldCapabilityBundleStore(root,
				new mmm.simulation.persistence.WorldCapabilityBundleStore.QuarantineAdmission(64, 64L << 20));
		var committed = store.openOrPublish(definitions).bundle();
		FluidRegion source = populatedRegion(UUID.randomUUID(), CONFIG,
				mmm.fluid.TestFluidDefinitions.withRegistries(custom,production.chemical(),production.gas(),production.granular(),production.thermal()));
		bindDefaultMaterials(source);
		FullCapabilityTestFixture.bindFull(source.editableHost(), new FullCapabilityTestFixture.Registries(
				production.liquids(), production.chemical(), production.granular(), production.gas(),
				production.thermal(), custom), 293.15, 4);
		source.editableHost().setThermalTemperatureInCell(2, 1, 2, 353.15);
		var pendingKey = source.editableHost().captureAuthoritativeState().liquid().stocks().stream()
				.filter(stock -> source.editableHost().materialRegistry().material(stock.key().material()).id().equals("mmm:water"))
				.findFirst().orElseThrow().key();
		source.editableHost().chemistry().restoreMassTransferPending(java.util.List.of(
				new mmm.chemistry.RegionChemistry.MassTransferPending(pendingKey.cell(), pendingKey.material(),
						pendingKey.supportMask(), "mmm:co2_water", .25, 3.75e-7)));
		source.editableHost().restoreEntityBodyState(new mmm.fluid.EntityBodyState(java.util.List.of(
				new mmm.world.MovingBodyFluidCoupling.Request(new java.util.UUID(43, 59),
						new mmm.world.MovingBodyFluidCoupling.Bounds(-2, -2, -2, -1.75, -1.5, -1.25), -.5, .25, -.125))));

		try {
			new RegionRepositoryAdapter(root, committed.profile(), committed.chemical(), committed.granular(),
					committed.thermal(), committed.gas(), 4, production.materials());
			throw new AssertionError("repository accepted definitions from another world profile");
		} catch (IllegalArgumentException expectedMismatch) {
			check(expectedMismatch.getMessage().contains("committed profile"),
					"unexpected repository rejection: " + expectedMismatch.getMessage());
		}
		try {
			new RegionRepositoryAdapter(root, TestProfiles.full(), production.chemical(), production.granular(),
					production.thermal(), production.gas(), 4, production.materials()).checkpoint(source);
			throw new AssertionError("checkpoint reinterpreted a domain with different material definitions");
		} catch (java.io.IOException expectedMismatch) {
			check(expectedMismatch.getMessage().contains("material definitions"),
					"unexpected checkpoint rejection: " + expectedMismatch.getMessage());
		}
		var writer = new RegionRepositoryAdapter(root, committed.profile(), committed.chemical(), committed.granular(),
				committed.thermal(), committed.gas(), 4, committed.material());
		writer.checkpoint(source);
		var drifted = store.openOrPublish(new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
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
		mmm.fluid.FluidDomainStateAssertions.assertSameDurableState(
				source.editableHost().captureAuthoritativeState(), restored.editableHost().captureAuthoritativeState(),
				"custom viscosity disk restore");
		int cell = (2 * 4 + 1) * 6 + 2;
		double actual = restored.editableHost().carrierViscosityPascalSeconds(cell);
		double expected = custom.dynamicViscosityAt("mmm:water", 353.15);
		check(Math.abs(actual - expected) <= expected * 1e-12
				&& actual > 1.9 * production.materials().dynamicViscosityAt("mmm:water", 353.15),
				"disk restore replaced committed viscosity with built-in definitions");
	}

	/** The reduced material layout also binds constitutive data without granular inventory. */
	private static void materialOnlyViscosityRestores() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "material-viscosity-restore-");
		var custom = FullCapabilityTestFixture.doubledWaterViscosity();
		var customDefinitions=mmm.fluid.TestFluidDefinitions.withRegistries(custom,null,null,null,null);
		var profile = mmm.worldprofile.WorldCapabilityProfile.of(
				java.util.EnumSet.of(mmm.worldprofile.WorldCapability.MATERIALS),
				new mmm.worldprofile.RegistryFingerprintSet(custom.fingerprint(),customDefinitions.chemistry().fingerprint(),
						customDefinitions.granular().fingerprint(),customDefinitions.gas().fingerprint(),customDefinitions.thermal().fingerprint()));
		FluidRegion source = populatedRegion(UUID.randomUUID(),CONFIG,customDefinitions);
		bindDefaultMaterials(source);
		source.editableHost().bindMaterialDefinitions(custom);
		var world=customDefinitions;
		var repository = new RegionRepositoryAdapter(root,profile,world.chemistry(),world.granular(),world.thermal(),world.gas(),custom);
		repository.checkpoint(source);
		var manager = manager();
		check(repository.loadMetadata(manager).isEmpty(), "material-only viscosity metadata was rejected");
		var restored = manager.regions().getFirst(); repository.restore(restored);
		mmm.fluid.FluidDomainStateAssertions.assertSameDurableState(
				source.editableHost().captureAuthoritativeState(), restored.editableHost().captureAuthoritativeState(),
				"material-only viscosity disk restore");
		check(!restored.editableHost().hasGranularState()
				&& restored.editableHost().carrierViscosityPascalSeconds((2 * 4 + 1) * 6 + 2)
				== custom.dynamicViscosityAt("mmm:water", 293.15),
				"material-only restore lost custom constitutive data");
	}

	private static void validateThermalProductionGate() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "thermal-production-");
		GasRegistry gas = mmm.fluid.TestFluidDefinitions.load().gas();
		ThermalRegistry thermal = mmm.fluid.TestFluidDefinitions.load().thermal();
		FluidRegion source = populatedRegion(UUID.randomUUID());
		bindDefaultMaterials(source);
		source.editableHost().bindGasRegistry(gas);
		source.editableHost().bindThermal(thermal, source.editableHost().materialRegistry(), 300.0);
		source.editableHost().setThermalTemperatureInCell(2, 1, 2, 325.0);
		var definitions=mmm.fluid.TestFluidDefinitions.load();
		RegionRepositoryAdapter persistence = new RegionRepositoryAdapter(root, TestProfiles.thermal(null, thermal), definitions.chemistry(), definitions.granular(), definitions.thermal(), definitions.gas(), definitions.material());
		check(persistence.checkpoint(source) == 1L, "production thermal checkpoint was not published");

		FluidRegionManager manager = manager();
		RegionRepositoryAdapter restoring = new RegionRepositoryAdapter(root, TestProfiles.thermal(null, thermal), definitions.chemistry(), definitions.granular(), definitions.thermal(), definitions.gas(), definitions.material());
		check(restoring.loadMetadata(manager).isEmpty(), "valid thermal metadata was locked");
		FluidRegion admitted = manager.regions().getFirst(); restoring.restore(admitted);
		check(admitted.solverLoaded() && admitted.editableHost().hasThermalState()
				&& admitted.editableHost().captureAuthoritativeState().spatialGas().registryFingerprint().equals(gas.fingerprint())
				&& admitted.editableHost().thermalAudit().equals(source.editableHost().thermalAudit()),
				"production restore did not reconstruct gas plus thermal truth");

		FluidRegionManager constrained = new FluidRegionManager(
				new RegionCatalog.Limits(1, 100L, 100L));
		check(restoring.loadMetadata(constrained).isEmpty() && constrained.regions().size() == 1,
				"temporary policy budget discarded valid committed metadata");
		FluidRegion retained = constrained.regions().getFirst();
		check(!retained.solverLoaded() && retained.availabilityReason() == RegionAvailabilityReason.BUDGET,
				"over-budget committed metadata was not retained as PAUSED_BUDGET");

		FluidRegionManager gateOff = manager();
		List<String> gateIssues = new RegionRepositoryAdapter(root,TestProfiles.materials(),definitions.chemistry(),definitions.granular(),definitions.thermal(),definitions.gas(),definitions.material()).loadMetadata(gateOff);
		check(gateOff.regions().isEmpty() && gateIssues.stream().anyMatch(issue -> issue.contains("thermal")),
				"thermal-off discovery did not preserve and lock thermal region state");

	}

	private static void validateGranularFeatureLayout() throws Exception {
		var definitions = FullCapabilityTestFixture.load();
		ChemicalRegistry chemistry = definitions.chemical();
		GranularRegistry granular = definitions.granular();
		Path gateRoot = Files.createTempDirectory(Path.of("."), "granular-gate-off-");
		FluidRegion source = populatedRegion(UUID.randomUUID()); bindDefaultMaterials(source);
		FullCapabilityTestFixture.bindFull(source.editableHost(), definitions, 293.15, 4);
		var profile = TestProfiles.full();
		new RegionRepositoryAdapter(gateRoot, profile, chemistry, granular, definitions.thermal(), definitions.gas(), TestProfiles.materialRegistry()).checkpoint(source);
		FluidRegionManager currentManager = manager();
		RegionRepositoryAdapter current = new RegionRepositoryAdapter(gateRoot, profile, chemistry, granular, definitions.thermal(), definitions.gas(), TestProfiles.materialRegistry());
		check(current.loadMetadata(currentManager).isEmpty(), "current granular layout was locked");
		FluidRegion restored = currentManager.regions().getFirst(); current.restore(restored);
		check(restored.editableHost().hasChemistryState() && restored.editableHost().hasGranularState()
				&& restored.editableHost().precipitationEnabled(), "current granular layout did not restore");
		FluidRegionManager gateManager = manager();
		List<String> issues = new RegionRepositoryAdapter(gateRoot,TestProfiles.materials(),definitions.chemical(),definitions.granular(),definitions.thermal(),definitions.gas(),definitions.materials()).loadMetadata(gateManager);
		check(gateManager.regions().isEmpty() && !issues.isEmpty(),
				"granular-off discovery did not preserve and lock granular region state");
	}

	private static void validateChemicalFeatureLayout() throws Exception {
		var definitions = FullCapabilityTestFixture.load();
		ChemicalRegistry chemistry = definitions.chemical();
		Path gateRoot = Files.createTempDirectory(Path.of("."), "chemical-gate-off-");
		FluidRegion chemical = populatedRegion(UUID.randomUUID());
		bindDefaultMaterials(chemical);
		FullCapabilityTestFixture.bindFull(chemical.editableHost(), definitions, 293.15, 4);
		var profile = TestProfiles.full();
		new RegionRepositoryAdapter(gateRoot, profile, chemistry, definitions.granular(), definitions.thermal(), definitions.gas(), TestProfiles.materialRegistry()).checkpoint(chemical);
		FluidRegionManager currentManager = manager();
		RegionRepositoryAdapter current = new RegionRepositoryAdapter(gateRoot, profile, chemistry, definitions.granular(), definitions.thermal(), definitions.gas(), TestProfiles.materialRegistry());
		check(current.loadMetadata(currentManager).isEmpty(), "current chemical layout was locked");
		FluidRegion restored = currentManager.regions().getFirst(); current.restore(restored);
		assertChemicalStateEquals(chemical.editableHost().exportChemicalParticleState(), restored.editableHost().exportChemicalParticleState());
		FluidRegionManager gateManager = manager();
		List<String> issues = new RegionRepositoryAdapter(gateRoot,TestProfiles.materials(),definitions.chemical(),definitions.granular(),definitions.thermal(),definitions.gas(),definitions.materials()).loadMetadata(gateManager);
		check(gateManager.regions().isEmpty() && !issues.isEmpty(),
				"chemistry-off discovery did not preserve and lock chemical region state");
	}

	private static FluidRegionManager manager() {
		return new FluidRegionManager(new RegionCatalog.Limits(4, 10_000L, 40_000L));
	}

	private static FluidRegion populatedRegion(UUID id) { return populatedRegion(id, CONFIG); }

	private static FluidRegion populatedRegion(UUID id, FluidSolverConfig config) {
		return populatedRegion(id,config,mmm.fluid.TestFluidDefinitions.load());
	}
	private static FluidRegion populatedRegion(UUID id,FluidSolverConfig config,mmm.fluid.FluidDefinitions definitions) {
		BlockPos origin = new BlockPos(15, 16, 15);
		HostFluidState domain = new HostFluidState(6, 4, 6, config,definitions);
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
		FluidRegionFootprint footprint = FluidRegionFootprint.selected(origin, new BlockPos(17, 17, 17), interior, chunks);
		return new FluidRegion(id, DIMENSION, origin, domain, footprint);
	}

	private static void bindDefaultMaterials(FluidRegion region) {
		FlipParticleState pure = region.editableHost().exportParticleState();
		float[] positions = pure.copyPositionVolume();
		double[] mass = new double[pure.particleCount()];
		double totalMass = 0.0;
		for (int marker = 0; marker < pure.particleCount(); marker++) {
			mass[marker] = positions[marker * 4 + 3] * mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material().referenceDensity();
			totalMass += mass[marker];
		}
		region.editableHost().restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(), pure.totalVolume(), mmm.units.SolverUnitScale.forCellSize(pure.cellSize()).massUnitsForNormalized(totalMass), mmm.simulation.ProductionRegistries.palette(), positions, pure.copyParticleVelocities(), pure.copySolidMask(), java.util.Arrays.stream(mass).mapToLong(m -> mmm.units.SolverUnitScale.forCellSize(pure.cellSize()).massUnitsForNormalized(m)).toArray(), new int[pure.particleCount()]));
	}

	private static void assertChemicalStateEquals(FlipChemicalParticleState expected, FlipChemicalParticleState actual) {
		check(expected.materials().materials().equals(actual.materials().materials())
				&& Arrays.equals(expected.materials().copyPositionVolume(), actual.materials().copyPositionVolume())
				&& Arrays.equals(expected.materials().copyParticleMassUnits(), actual.materials().copyParticleMassUnits())
				&& expected.mixtures().equals(actual.mixtures())
				&& Arrays.equals(expected.copyMarkerMixtureIndex(), actual.copyMarkerMixtureIndex())
				&& Arrays.equals(expected.copySpeciesTotals(), actual.copySpeciesTotals())
				&& expected.residualLedgerUnits() == actual.residualLedgerUnits()
				&& expected.externalSource().groups().equals(actual.externalSource().groups())
				&& expected.escapedSink().groups().equals(actual.escapedSink().groups()),
				"chemical state changed across persistence");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
