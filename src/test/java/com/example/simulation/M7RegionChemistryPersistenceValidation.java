package com.example.simulation;

import com.example.chemistry.BuiltinChemicalDefinitions;
import com.example.chemistry.ChemicalRegistry;
import com.example.fluid.BuiltinFluidMaterials;
import com.example.fluid.FlipChemicalParticleState;
import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FlipMaterialParticleState;
import com.example.fluid.FlipParticleState;
import com.example.fluid.FluidSolverConfig;
import com.example.simulation.controller.FluidRegionFootprint;
import com.example.simulation.persistence.RegionState;
import com.example.simulation.persistence.RegionStateFiles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Feature-gate, service migration and two-slot fault boundary for region-state v3. */
public final class M7RegionChemistryPersistenceValidation {
	private static final FluidSolverConfig CONFIG = new FluidSolverConfig(
			0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	private static final ResourceKey<Registry<Level>> DIMENSION_REGISTRY = ResourceKey.createRegistryKey(
			Identifier.fromNamespaceAndPath("realwater", "m7_persistence_registry"));
	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(DIMENSION_REGISTRY,
			Identifier.fromNamespaceAndPath("realwater", "m7_persistence"));

	private M7RegionChemistryPersistenceValidation() { }

	public static void main(String[] args) throws Exception {
		validateFeatureGateAndMigration();
		validateAtomicFaultBoundary();
		System.out.println("M7 region chemistry persistence passed: gate lock, v1/v2 migration and v3 fault matrix");
	}

	private static void validateFeatureGateAndMigration() throws Exception {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		for (int sourceVersion = 1; sourceVersion <= 2; sourceVersion++) {
			Path root = Files.createTempDirectory(Path.of("."), "chemical-migration-v" + sourceVersion + "-");
			UUID id = UUID.randomUUID();
			FluidRegion source = populatedRegion(id);
			RegionState old;
			if (sourceVersion == 1) old = RegionState.capture(source, 1L);
			else {
				bindDefaultMaterials(source);
				old = RegionState.captureMaterial(source, 1L);
			}
			new RegionStateFiles(root).publish(old);
			FluidRegionManager manager = manager();
			FluidRegionPersistence persistence = new FluidRegionPersistence(root, true, true, chemistry);
			check(persistence.loadMetadata(manager).isEmpty(), "legacy checkpoint was locked before v3 migration");
			FluidRegion admitted = manager.regions().getFirst();
			persistence.restore(admitted);
			check(admitted.solverLoaded() && admitted.domain().hasChemistryState()
					&& admitted.checkpointGeneration() == 2L,
					"v" + sourceVersion + " checkpoint did not migrate atomically to live chemistry v3");
			RegionState migrated = new RegionStateFiles(root, chemistry).read(id);
			check(migrated.manifest().formatVersion() == 3 && migrated.manifest().checkpointGeneration() == 2L,
					"v" + sourceVersion + " migration did not publish a complete v3 generation");
			assertChemicalStateEquals(admitted.domain().exportChemicalParticleState(), migrated.reconstructChemical(chemistry));
		}

		Path gateRoot = Files.createTempDirectory(Path.of("."), "chemical-gate-off-");
		FluidRegion chemical = populatedRegion(UUID.randomUUID());
		bindDefaultMaterials(chemical);
		chemical.domain().bindChemistry(chemistry);
		new RegionStateFiles(gateRoot, chemistry).publish(RegionState.captureChemical(chemical, 1L));
		FluidRegionManager gateManager = manager();
		List<String> issues = new FluidRegionPersistence(gateRoot, true, false, null).loadMetadata(gateManager);
		check(gateManager.regions().isEmpty() && issues.stream().anyMatch(issue -> issue.contains("v3")),
				"chemistry-off discovery did not preserve and lock region-state v3");
	}

	private static void validateAtomicFaultBoundary() throws Exception {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		for (RegionStateFiles.FaultPoint point : RegionStateFiles.FaultPoint.values()) {
			Path root = Files.createTempDirectory(Path.of("."), "v3-fault-" + point.name().toLowerCase() + "-");
			FluidRegion fixture = populatedRegion(UUID.randomUUID());
			bindDefaultMaterials(fixture);
			RegionState v2 = RegionState.captureMaterial(fixture, 1L);
			RegionStateFiles files = new RegionStateFiles(root, chemistry);
			files.publish(v2);
			fixture.domain().bindChemistry(chemistry);
			RegionState v3 = RegionState.captureChemical(fixture, 2L);
			try {
				files.publish(v3, (observed, path) -> {
					if (observed == point) throw new IOException("injected " + point);
				});
				throw new AssertionError("v3 migration fault point did not interrupt publication: " + point);
			} catch (IOException expected) { }
			RegionState visible = new RegionStateFiles(root, chemistry).read(fixture.id());
			long expectedGeneration = point == RegionStateFiles.FaultPoint.AFTER_HEAD_PUBLISH ? 2L : 1L;
			check(visible.manifest().checkpointGeneration() == expectedGeneration
					&& visible.manifest().formatVersion() == (expectedGeneration == 2L ? 3 : 2),
					"v3 migration fault exposed a mixed generation at " + point);
		}
	}

	private static FluidRegionManager manager() {
		return new FluidRegionManager(new FluidRegionManager.Limits(4, 10_000L, 100_000L, 40_000L, 400_000L));
	}

	private static FluidRegion populatedRegion(UUID id) {
		BlockPos origin = new BlockPos(15, 16, 15);
		FlipFluidDomain domain = new FlipFluidDomain(6, 4, 6, CONFIG, 512);
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
				totalMass, BuiltinFluidMaterials.M3_PALETTE, positions, pure.copyParticleVelocities(), pure.copySolidMask(),
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
				"chemical state changed across migration persistence");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
