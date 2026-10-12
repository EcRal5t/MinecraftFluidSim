package mmm.simulation.persistence;

import mmm.model.region.RegionExtension;
import mmm.model.region.RegionManifest;
import mmm.model.region.RegionSectionState;

import mmm.fluid.FluidDomain;
import mmm.fluid.FlipChemicalParticleState;
import mmm.fluid.FlipMaterialParticleState;
import mmm.fluid.FlipParticleState;
import mmm.fluid.FlipThermalParticleState;
import mmm.fluid.FluidMaterialRegistry;
import mmm.fluid.FluidSolverConfig;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.fluid.air.GasRegistry;
import mmm.fluid.air.GasStateSnapshot;
import mmm.granular.GranularRegistry;
import mmm.granular.GranularStateSnapshot;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.simulation.FluidRegion;
import mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

abstract class RegionPersistenceValidationSupport {
	static final FluidSolverConfig CONFIG = new FluidSolverConfig(
			0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	static final ResourceKey<Registry<Level>> DIMENSION_REGISTRY = ResourceKey.createRegistryKey(
			Identifier.fromNamespaceAndPath("mmm", "persistence_validation_registry"));
	static final ResourceKey<Level> DIMENSION = ResourceKey.create(DIMENSION_REGISTRY,
			Identifier.fromNamespaceAndPath("mmm", "persistence_validation"));

	static FluidRegion populatedRegion(UUID id) {
		BlockPos origin = new BlockPos(15, 16, 15);
		FluidDomain domain = new FluidDomain(6, 4, 6, CONFIG, 512);
		domain.setSolid(0, 0, 0, true);
		domain.setVolume(1, 1, 1, 0.25F);
		domain.setVolume(2, 1, 2, 1.0F);
		domain.setVolume(4, 2, 4, 0.625F);
		FlipParticleState state = domain.exportParticleState();
		float[] velocities = state.copyParticleVelocities();
		for (int i = 0; i < velocities.length; i++) velocities[i] = (i % 7 - 3) * 0.03125F;
		domain.restoreParticleState(new FlipParticleState(state.sizeX(), state.sizeY(), state.sizeZ(), state.cellSize(),
				state.particleCount(), state.totalVolume(), state.copyPositionVolume(), velocities, state.copySolidMask()));
		Set<Long> interior = new HashSet<>();
		for (int z = 15; z <= 17; z++) for (int y = 16; y <= 17; y++) for (int x = 15; x <= 17; x++) interior.add(new BlockPos(x, y, z).asLong());
		BlockPos controller = new BlockPos(14, 16, 15);
		Set<Long> chunks = Set.of(ChunkPos.pack(0, 0), ChunkPos.pack(1, 0), ChunkPos.pack(0, 1), ChunkPos.pack(1, 1));
		FluidRegionFootprint footprint = new FluidRegionFootprint(controller, origin, new BlockPos(17, 17, 17),
				interior, Set.of(controller.asLong()), chunks);
		return new FluidRegion(id, DIMENSION, origin, domain, 512L, footprint);
	}

	static FluidRegion emptyRegion(UUID id) {
		BlockPos origin = new BlockPos(32, 16, 32);
		FluidDomain domain = new FluidDomain(8, 8, 8, CONFIG, 4_096);
		Set<Long> interior = new HashSet<>();
		for (int z = 32; z <= 35; z++) for (int y = 16; y <= 19; y++) {
			for (int x = 32; x <= 35; x++) interior.add(new BlockPos(x, y, z).asLong());
		}
		BlockPos controller = new BlockPos(31, 17, 33);
		FluidRegionFootprint footprint = new FluidRegionFootprint(controller, origin, new BlockPos(35, 19, 35),
				interior, Set.of(controller.asLong()), Set.of(ChunkPos.pack(2, 2)));
		return new FluidRegion(id, DIMENSION, origin, domain, 4_096L, footprint);
	}

	static void assertParticleStateEquals(FlipParticleState expected, FlipParticleState actual) {
		require(expected.particleCount() == actual.particleCount(), "marker count changed across persistence");
		require(Arrays.equals(floatBits(expected.copyPositionVolume()), floatBits(actual.copyPositionVolume())),
				"marker order/position/particleVolume changed across persistence");
		require(Arrays.equals(floatBits(expected.copyParticleVelocities()), floatBits(actual.copyParticleVelocities())),
				"marker velocity changed across persistence");
		require(Arrays.equals(expected.copySolidMask(), actual.copySolidMask()), "solid mask changed across persistence");
	}

	static void assertGasStateEquals(GasStateSnapshot expected, GasStateSnapshot actual) {
		require(expected.sizeX() == actual.sizeX() && expected.sizeY() == actual.sizeY() && expected.sizeZ() == actual.sizeZ()
				&& expected.registryFingerprint().equals(actual.registryFingerprint())
				&& expected.chemicalRegistryFingerprint().equals(actual.chemicalRegistryFingerprint())
				&& expected.nodes().equals(actual.nodes())
				&& Arrays.equals(expected.pieceNodeOrdinal(), actual.pieceNodeOrdinal())
				&& expected.externalSource().equals(actual.externalSource())
				&& expected.escapedSink().equals(actual.escapedSink())
				&& expected.massRoundoff().equals(actual.massRoundoff())
				&& expected.residualRoundoff().equals(actual.residualRoundoff()),
				"gas component/topology/audit changed across persistence");
	}

	static void assertMaterialStateEquals(FlipMaterialParticleState expected, FlipMaterialParticleState actual) {
		require(expected.materials().equals(actual.materials()), "material palette changed across persistence");
		require(Double.doubleToRawLongBits(expected.totalVolume()) == Double.doubleToRawLongBits(actual.totalVolume())
				&& Double.doubleToRawLongBits(expected.totalMass()) == Double.doubleToRawLongBits(actual.totalMass()),
				"material total ledger changed across persistence");
		require(Arrays.equals(floatBits(expected.copyPositionVolume()), floatBits(actual.copyPositionVolume())),
				"material marker order/position/particleVolume changed across persistence");
		require(Arrays.equals(floatBits(expected.copyParticleVelocities()), floatBits(actual.copyParticleVelocities())),
				"material marker velocity changed across persistence");
		require(Arrays.equals(expected.copyParticleMass(), actual.copyParticleMass())
				&& Arrays.equals(expected.copyMaterialPaletteIndex(), actual.copyMaterialPaletteIndex()),
				"material marker mass or palette index changed across persistence");
		require(Arrays.equals(expected.copySolidMask(), actual.copySolidMask()), "material solid mask changed across persistence");
	}

	static void assertThermalStateEquals(FlipThermalParticleState expected, FlipThermalParticleState actual) {
		require(expected.particleCount() == actual.particleCount()
				&& expected.thermalRegistryFingerprint().equals(actual.thermalRegistryFingerprint())
				&& Double.doubleToRawLongBits(expected.insertionTemperatureK())
						== Double.doubleToRawLongBits(actual.insertionTemperatureK())
				&& Arrays.equals(expected.copyInternalEnergy(), actual.copyInternalEnergy())
				&& Arrays.equals(expected.copyActiveParcelEnergy(), actual.copyActiveParcelEnergy())
				&& Arrays.equals(expected.copyDepositEnergy(), actual.copyDepositEnergy())
				&& Arrays.equals(expected.copyGasNodeEnergy(), actual.copyGasNodeEnergy())
				&& expected.coherentGroups().equals(actual.coherentGroups())
				&& expected.audit().equals(actual.audit()),
				"thermal inventory/audit changed across persistence");
	}

	static ThermalRegistry thermalRegistry(FluidMaterialRegistry liquids) {
		return thermalRegistry(liquids, null);
	}

	static ThermalRegistry thermalRegistry(FluidMaterialRegistry liquids, GranularRegistry granular) {
		List<ThermalMaterialDefinition> definitions = new ArrayList<>(liquids.materials().size());
		for (int index = 0; index < liquids.materials().size(); index++) {
			definitions.add(new ThermalMaterialDefinition("mmm:thermal/persistence_" + index,
					ThermalMaterialDefinition.CarrierKind.LIQUID, liquids.material(index).id(), 300.0, 0.0, 1.0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 4_200.0))));
		}
		definitions.add(new ThermalMaterialDefinition("mmm:thermal/persistence_solid",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "mmm:solid/persistence",
				300.0, 50.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 4_200.0))));
		definitions.add(new ThermalMaterialDefinition("mmm:thermal/persistence_air",
				ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.AIR,
				GasRegistry.STANDARD_TEMPERATURE_K, 0.0, 0.025,
				List.of(new ThermalMaterialDefinition.CaloricSegment(150.0, 1_500.0, 718.0))));
		definitions.add(new ThermalMaterialDefinition("mmm:thermal/persistence_device",
				ThermalMaterialDefinition.CarrierKind.DEVICE, "mmm:device/persistence",
				300.0, 10.0, 4.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 500.0))));
		if (granular != null) for (int index = 0; index < granular.materials().size(); index++) {
			definitions.add(new ThermalMaterialDefinition("mmm:thermal/persistence_granular_" + index,
					ThermalMaterialDefinition.CarrierKind.GRANULAR, granular.material(index).id(),
					300.0, 20.0, 1.0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0))));
		}
		return granular == null ? new ThermalRegistry(definitions)
				: new ThermalRegistry(definitions, List.of(), List.of(), "", granular.fingerprint());
	}

	static void assertGranularStateEquals(GranularStateSnapshot expected, GranularStateSnapshot actual) {
		require(expected.registryFingerprint().equals(actual.registryFingerprint())
				&& expected.chemicalRegistryFingerprint().equals(actual.chemicalRegistryFingerprint())
				&& expected.materialPalette().equals(actual.materialPalette())
				&& expected.solidCompositions().equals(actual.solidCompositions())
				&& expected.solidSpeciesTotals().equals(actual.solidSpeciesTotals())
				&& expected.activeParcels().equals(actual.activeParcels())
				&& expected.depositGroups().equals(actual.depositGroups())
				&& expected.externalSource().equals(actual.externalSource())
				&& expected.escapedSink().equals(actual.escapedSink())
				&& expected.totalSolidVolumeSvq() == actual.totalSolidVolumeSvq()
				&& expected.totalMassBits() == actual.totalMassBits(),
				"granular inventory changed across persistence");
	}

	static void assertChemicalStateEquals(FlipChemicalParticleState expected, FlipChemicalParticleState actual) {
		assertMaterialStateEquals(expected.materials(), actual.materials());
		require(expected.registryFingerprint().equals(actual.registryFingerprint())
				&& expected.speciesPalette().equals(actual.speciesPalette())
				&& expected.mixtures().equals(actual.mixtures())
				&& Arrays.equals(expected.copyMarkerMixtureIndex(), actual.copyMarkerMixtureIndex())
				&& Arrays.equals(expected.copyIndicator(), actual.copyIndicator())
				&& Arrays.equals(expected.copySpeciesTotals(), actual.copySpeciesTotals())
				&& expected.residualLedgerBits() == actual.residualLedgerBits()
				&& expected.externalSource().groups().equals(actual.externalSource().groups())
				&& expected.escapedSink().groups().equals(actual.escapedSink().groups()),
				"chemical handoff changed across persistence");
	}

	static int[] floatBits(float[] values) {
		int[] result = new int[values.length];
		for (int i = 0; i < values.length; i++) result[i] = Float.floatToRawIntBits(values[i]);
		return result;
	}

	static RegionSectionState signedSection(RegionSectionState section) {
		return new RegionSectionState(section.layout(), section.regionId(), section.dimensionId(), section.checkpointGeneration(), section.sectionX(),
				section.sectionY(), section.sectionZ(), section.localStartX(), section.localStartY(), section.localStartZ(),
				section.sizeX(), section.sizeY(), section.sizeZ(), section.markerOrdinals(), section.positionVolume(),
				section.velocities(), section.markerMass(), section.materialPaletteIndex(), section.solidMask(), section.extensions(),
				section.chemicalMixtureIndex(), section.indicator(), section.chemicalSpeciesTotals(),
				section.chemicalResidualLedgerBits(), RegionStateIntegrity.sectionChecksum(section));
	}

	static RegionManifest signedManifest(RegionManifest manifest) {
		return new RegionManifest(manifest.layout(), manifest.regionId(), manifest.dimensionId(), manifest.checkpointGeneration(),
				manifest.networkGeneration(), manifest.origin(), manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(),
				manifest.reservedMarkerCapacity(), manifest.runtime(), manifest.footprint(), manifest.totalMarkerCount(), manifest.totalVolumeBits(),
				manifest.materialPalette(), manifest.totalMassBits(), manifest.materialLedgers(), manifest.sections(), manifest.extensions(),
				manifest.registryFingerprint(), manifest.auScale(), manifest.speciesPalette(), manifest.chemicalMixtures(),
				manifest.chemicalSpeciesTotals(), manifest.chemicalResidualLedgerBits(), RegionStateIntegrity.manifestChecksum(manifest));
	}

	static RegionManifest manifestWithExtensions(
			RegionManifest manifest, List<RegionExtension> extensions
	) {
		RegionManifest unsigned = new RegionManifest(manifest.layout(), manifest.regionId(), manifest.dimensionId(),
				manifest.checkpointGeneration(), manifest.networkGeneration(), manifest.origin(), manifest.sizeX(), manifest.sizeY(),
				manifest.sizeZ(), manifest.solverConfig(), manifest.reservedMarkerCapacity(), manifest.runtime(), manifest.footprint(),
				manifest.totalMarkerCount(), manifest.totalVolumeBits(), manifest.materialPalette(), manifest.totalMassBits(),
				manifest.materialLedgers(), manifest.sections(), extensions, manifest.registryFingerprint(), manifest.auScale(),
				manifest.speciesPalette(), manifest.chemicalMixtures(), manifest.chemicalSpeciesTotals(),
				manifest.chemicalResidualLedgerBits(), 0L);
		return signedManifest(unsigned);
	}

	static double sumVolumes(float[] positionVolume) {
		double result = 0.0;
		for (int index = 3; index < positionVolume.length; index += 4) result += positionVolume[index];
		return result;
	}

	static double sum(double[] values) {
		double result = 0.0;
		for (double value : values) result += value;
		return result;
	}

	static void expectIOException(IoAction action, String message) throws Exception {
		try { action.run(); throw new AssertionError(message); }
		catch (IOException expected) { }
	}

	static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	@FunctionalInterface interface IoAction { void run() throws Exception; }

	static RegionCheckpointRestorer restorer(RegionState state) {
		return new RegionCheckpointRestorer(state.checkpoint());
	}
}
