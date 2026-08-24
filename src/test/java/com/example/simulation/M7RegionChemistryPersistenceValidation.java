package com.example.simulation;

import com.example.chemistry.BuiltinChemicalDefinitions;
import com.example.chemistry.ChemicalRegistry;
import com.example.chemistry.ExactRational;
import com.example.fluid.BuiltinFluidMaterials;
import com.example.fluid.FlipChemicalParticleState;
import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FlipMaterialParticleState;
import com.example.fluid.FlipParticleState;
import com.example.fluid.FluidSolverConfig;
import com.example.fluid.air.BuiltinGasDefinitions;
import com.example.fluid.air.GasRegistry;
import com.example.granular.BuiltinGranularDefinitions;
import com.example.granular.GranularRegistry;
import com.example.simulation.controller.FluidRegionFootprint;
import com.example.simulation.persistence.RegionState;
import com.example.simulation.persistence.RegionStateFiles;
import com.example.simulation.persistence.ThermalInitializationRecord;
import com.example.thermal.ThermalMaterialDefinition;
import com.example.thermal.ThermalRegistry;
import com.example.thermal.ThermalSpeciesOffset;
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
		validateGranularV5FeatureGateAndMigration();
		validateThermalV6ProductionGate();
		System.out.println("Region persistence passed: v1/v2/v3 migration, v3/v5/v6 gates and fault matrix");
	}

	private static void validateThermalV6ProductionGate() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "thermal-production-v6-");
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		ThermalRegistry thermal = productionThermalRegistry();
		FluidRegion source = populatedRegion(UUID.randomUUID());
		bindDefaultMaterials(source);
		source.domain().bindGasRegistry(gas);
		source.domain().bindThermal(thermal, source.domain().materialRegistry(), 300.0);
		source.domain().setThermalTemperatureInCell(2, 1, 2, 325.0);
		FluidRegionPersistence persistence = new FluidRegionPersistence(root, true, false, null,
				false, null, false, true, thermal, gas);
		check(persistence.checkpoint(source) == 1L, "production thermal checkpoint did not publish v6");

		FluidRegionManager manager = manager();
		FluidRegionPersistence restoring = new FluidRegionPersistence(root, true, false, null,
				false, null, false, true, thermal, gas);
		check(restoring.loadMetadata(manager).isEmpty(), "valid v6 metadata was locked");
		FluidRegion admitted = manager.regions().getFirst(); restoring.restore(admitted);
		check(admitted.solverLoaded() && admitted.domain().hasThermalState()
				&& admitted.domain().exportGasState("").registryFingerprint().equals(gas.fingerprint())
				&& admitted.domain().thermalAudit().equals(source.domain().thermalAudit()),
				"production v6 restore did not reconstruct gas plus thermal truth");

		FluidRegionManager gateOff = manager();
		List<String> gateIssues = new FluidRegionPersistence(root, true).loadMetadata(gateOff);
		check(gateOff.regions().isEmpty() && gateIssues.stream().anyMatch(issue -> issue.contains("v6")),
				"thermal-off discovery did not preserve and lock region-state v6");

		Path legacyRoot = Files.createTempDirectory(Path.of("."), "thermal-explicit-init-");
		FluidRegion legacy = populatedRegion(UUID.randomUUID()); bindDefaultMaterials(legacy);
		new RegionStateFiles(legacyRoot).publish(RegionState.captureMaterial(legacy, 1L));
		FluidRegionManager legacyManager = manager();
		FluidRegionPersistence legacyPersistence = new FluidRegionPersistence(legacyRoot, true, false, null,
				false, null, false, true, thermal, gas);
		check(legacyPersistence.loadMetadata(legacyManager).isEmpty(), "legacy metadata should remain selectable for explicit initialization");
		try {
			legacyPersistence.restore(legacyManager.regions().getFirst());
			throw new AssertionError("legacy checkpoint was assigned an implicit temperature");
		} catch (IOException expected) {
			check(expected.getMessage().contains("explicit operator"), "legacy thermal refusal did not name explicit initialization");
		}

		validateExplicitThermalInitializationVersions();
	}

	private static void validateExplicitThermalInitializationVersions() throws Exception {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		ThermalRegistry thermal = productionThermalRegistry(chemistry, granular);
		for (int sourceVersion : List.of(1, 2, 3, 5)) {
			Path root = Files.createTempDirectory(Path.of("."), "thermal-init-v" + sourceVersion + "-");
			FluidRegion source = populatedRegion(UUID.randomUUID());
			RegionState old;
			if (sourceVersion == 1) old = RegionState.capture(source, 1L);
			else {
				bindDefaultMaterials(source);
				if (sourceVersion >= 3) source.domain().bindChemistry(chemistry);
				if (sourceVersion == 5) {
					source.domain().bindGranular(granular, source.domain().materialRegistry());
					old = RegionState.captureGranular(source, 1L);
				} else if (sourceVersion == 3) old = RegionState.captureChemical(source, 1L);
				else old = RegionState.captureMaterial(source, 1L);
			}
			new RegionStateFiles(root, chemistry, granular, thermal, gas).publish(old);
			FluidRegionManager manager = manager();
			FluidRegionPersistence persistence = new FluidRegionPersistence(root, true, true, chemistry,
					true, granular, true, true, thermal, gas);
			check(persistence.loadMetadata(manager).isEmpty(), "v" + sourceVersion + " initialization source was locked");
			FluidRegion admitted = manager.regions().getFirst();
			long initialized = persistence.initializeThermal(admitted, 310.0, "test:operator");
			check(initialized == 2L && admitted.solverLoaded() && admitted.paused()
					&& admitted.domain().hasChemistryState() && admitted.domain().hasGranularState()
					&& admitted.domain().hasThermalState(),
					"v" + sourceVersion + " did not become one complete paused v6 generation");
			RegionState v6 = new RegionStateFiles(root, chemistry, granular, thermal, gas).read(admitted.id());
			ThermalInitializationRecord evidence = v6.reconstructThermalInitialization();
			check(v6.manifest().formatVersion() == 6 && evidence != null
					&& evidence.sourceFormatVersion() == sourceVersion
					&& evidence.initialTemperatureBits() == Double.doubleToRawLongBits(310.0)
					&& evidence.gasBootstrap()
					== ThermalInitializationRecord.GasBootstrap.CURRENT_GEOMETRY_AMBIENT_V1
					&& evidence.operatorMetadata().equals("test:operator"),
					"v" + sourceVersion + " initialization evidence was incomplete");
			check(persistence.checkpoint(admitted) == 3L, "initialized v6 did not accept a later checkpoint");
			RegionState next = new RegionStateFiles(root, chemistry, granular, thermal, gas).read(admitted.id());
			check(evidence.equals(next.reconstructThermalInitialization()),
					"v" + sourceVersion + " initialization evidence was not inherited by later v6");
			FluidRegionManager reloadedManager = manager();
			FluidRegionPersistence reloadedPersistence = new FluidRegionPersistence(root, true, true, chemistry,
					true, granular, true, true, thermal, gas);
			check(reloadedPersistence.loadMetadata(reloadedManager).isEmpty(),
					"v" + sourceVersion + " initialized v6 failed admission after restart");
			FluidRegion reloaded = reloadedManager.regions().getFirst(); reloadedPersistence.restore(reloaded);
			check(evidence.equals(reloaded.thermalInitializationRecord()),
					"v" + sourceVersion + " initialization evidence was lost during restart restore");
		}

		Path failureRoot = Files.createTempDirectory(Path.of("."), "thermal-init-failure-");
		FluidRegion failureSource = populatedRegion(UUID.randomUUID()); bindDefaultMaterials(failureSource);
		new RegionStateFiles(failureRoot, chemistry, granular, thermal, gas)
				.publish(RegionState.captureMaterial(failureSource, 1L));
		FluidRegionManager failureManager = manager();
		FluidRegionPersistence failurePersistence = new FluidRegionPersistence(failureRoot, true, true, chemistry,
				true, granular, true, true, thermal, gas);
		check(failurePersistence.loadMetadata(failureManager).isEmpty(), "failed-init source was not admitted");
		FluidRegion failed = failureManager.regions().getFirst();
		try {
			failurePersistence.initializeThermal(failed, 700.0, "test:operator");
			throw new AssertionError("out-of-range initialization temperature was accepted");
		} catch (IllegalArgumentException expected) { }
		RegionState stillOld = new RegionStateFiles(failureRoot, chemistry, granular, thermal, gas).read(failed.id());
		check(stillOld.manifest().formatVersion() == 2 && stillOld.manifest().checkpointGeneration() == 1L
				&& !failed.solverLoaded(), "failed initialization changed HEAD or retained guessed thermal truth");
	}

	private static ThermalRegistry productionThermalRegistry() {
		return productionThermalRegistry(null, null);
	}

	private static ThermalRegistry productionThermalRegistry(
			ChemicalRegistry chemistry, GranularRegistry granular
	) {
		List<ThermalMaterialDefinition> definitions = new java.util.ArrayList<>();
		java.util.Map<String, String> liquidHosts = new java.util.HashMap<>();
		for (var liquid : BuiltinFluidMaterials.M3_PALETTE) {
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

	private static void validateGranularV5FeatureGateAndMigration() throws Exception {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		for (int sourceVersion = 1; sourceVersion <= 3; sourceVersion++) {
			Path root = Files.createTempDirectory(Path.of("."), "granular-migration-v" + sourceVersion + "-");
			UUID id = UUID.randomUUID(); FluidRegion source = populatedRegion(id);
			RegionState old;
			if (sourceVersion == 1) old = RegionState.capture(source, 1L);
			else {
				bindDefaultMaterials(source);
				if (sourceVersion == 3) { source.domain().bindChemistry(chemistry); old = RegionState.captureChemical(source, 1L); }
				else old = RegionState.captureMaterial(source, 1L);
			}
			RegionStateFiles initialFiles = sourceVersion == 3 ? new RegionStateFiles(root, chemistry) : new RegionStateFiles(root);
			initialFiles.publish(old);
			FluidRegionManager manager = manager();
			FluidRegionPersistence persistence = new FluidRegionPersistence(root, true, true, chemistry, true, granular, true);
			check(persistence.loadMetadata(manager).isEmpty(), "legacy checkpoint was locked before v5 migration");
			FluidRegion admitted = manager.regions().getFirst(); persistence.restore(admitted);
			check(admitted.solverLoaded() && admitted.domain().hasChemistryState()
					&& admitted.domain().hasGranularState() && admitted.domain().precipitationEnabled()
					&& admitted.checkpointGeneration() == 2L,
					"v" + sourceVersion + " checkpoint did not migrate atomically to granular v5");
			RegionState migrated = new RegionStateFiles(root, chemistry, granular).read(id);
			check(migrated.manifest().formatVersion() == 5 && migrated.manifest().hasChemicalState()
					&& migrated.reconstructGranular(granular).totalSolidVolumeSvq() == 0L,
					"v" + sourceVersion + " migration did not publish complete empty-granular v5");
		}

		Path gateRoot = Files.createTempDirectory(Path.of("."), "granular-gate-off-");
		FluidRegion v5 = populatedRegion(UUID.randomUUID()); bindDefaultMaterials(v5);
		v5.domain().bindGranular(granular, v5.domain().materialRegistry());
		new RegionStateFiles(gateRoot, null, granular).publish(RegionState.captureGranular(v5, 1L));
		FluidRegionManager gateManager = manager();
		List<String> issues = new FluidRegionPersistence(gateRoot, true, false, null, false, null).loadMetadata(gateManager);
		check(gateManager.regions().isEmpty() && !issues.isEmpty(),
				"granular-off discovery did not preserve and lock region-state v5");
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
