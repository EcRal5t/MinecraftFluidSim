package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.fluid.FlipChemicalParticleState;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FlipThermalParticleState;
import cc.ecisr.mmm.fluid.PendingPhaseVolumeState;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.fluid.FluidMaterialRegistry;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.granular.GranularStateSnapshot;
import cc.ecisr.mmm.granular.SolidCompositionRecord;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.CoherentPhaseStore;
import cc.ecisr.mmm.thermal.SignedDyadic;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.simulation.FluidRegion;
import cc.ecisr.mmm.simulation.FluidRegionLifecycle;
import cc.ecisr.mmm.simulation.FluidRegionManager;
import cc.ecisr.mmm.simulation.FluidServerConfig;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Standalone exact-ledger, codec, atomic-publication and metadata-only unload regression. */
public final class FluidRegionPersistenceValidation {
	private static final FluidSolverConfig CONFIG = new FluidSolverConfig(
			0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	private static final ResourceKey<Registry<Level>> DIMENSION_REGISTRY = ResourceKey.createRegistryKey(
			Identifier.fromNamespaceAndPath("mmm", "persistence_validation_registry"));
	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(DIMENSION_REGISTRY,
			Identifier.fromNamespaceAndPath("mmm", "persistence_validation"));

	private FluidRegionPersistenceValidation() { }

	public static void main(String[] args) throws Exception {
		validateExactRoundTripAndMetadataUnload();
		validateMaterialV2Codec();
		validateChemicalV3Codec();
		validateGranularV5Codec();
		validateThermalV7Codec();
		validateUnknownExtensions();
		validateFaultMatrix();
		validateStagedGeneration();
		validateExplicitPreviousGenerationActivation();
		validateCorruptionAndAtomicMoveFailure();
		validateWorldConfig();
		System.out.println("Fluid region persistence validation passed");
	}

	private static void validateThermalV7Codec() throws Exception {
		FluidRegion region = populatedRegion(new UUID(27L, 28L));
		FlipParticleState pure = region.domain().exportParticleState();
		float[] positions = pure.copyPositionVolume();
		double[] mass = new double[pure.particleCount()];
		double totalMass = 0.0;
		for (int marker = 0; marker < mass.length; marker++) {
			mass[marker] = positions[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			totalMass += mass[marker];
		}
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		region.domain().restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(), pure.totalVolume(),
				totalMass, liquids.materials(), positions, pure.copyParticleVelocities(), pure.copySolidMask(),
				mass, new int[pure.particleCount()]));
		liquids = region.domain().materialRegistry();
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long activeSvq = GranularLimits.MAX_PARCEL_VOLUME_SVQ, depositSvq = activeSvq - 17L;
		double activeMass = 3.25, depositMass = 4.75;
		int depositCell = (5 * region.sizeY() + 3) * region.sizeX() + 5;
		GranularStateSnapshot granularFixture = new GranularStateSnapshot(
				region.sizeX(), region.sizeY(), region.sizeZ(), granular.fingerprint(),
				granular.materials().stream().map(cc.ecisr.mmm.granular.ParticulateMaterial::id).toList(),
				List.of(new GranularStateSnapshot.ActiveParcel(0.75F, 0.75F, 0.75F,
						0.125F, -0.25F, 0.0625F, activeSvq, activeMass, sand, 0)),
				List.of(new GranularStateSnapshot.DepositGroup(depositCell, granular.material(sand).restPorosityQ(),
						sand, 0, depositSvq, depositMass)), Math.addExact(activeSvq, depositSvq),
				Double.doubleToRawLongBits(activeMass + depositMass));
		region.domain().restoreGranularState(granularFixture, granular, liquids);
		ThermalRegistry thermal = thermalRegistry(liquids, granular);
		region.domain().bindThermal(thermal, liquids, 300.0);
		region.domain().setThermalTemperatureInCell(2, 1, 2, 325.0);
		region.domain().setThermalTemperatureInCell(1, 1, 1, 340.0);
		region.domain().setThermalTemperatureInCell(5, 3, 5, 280.0);
		region.domain().addCoherentPhaseExternal(3, 2, 3, "mmm:thermal/persistence_solid",
				SolidVolume.SVQ_PER_CELL / 8L, 0.125, null, 6.25);
		PendingPhaseVolumeState expectedPending = new PendingPhaseVolumeState(
				region.sizeX() * region.sizeY() * region.sizeZ(), List.of(
				new PendingPhaseVolumeState.Entry((1 * region.sizeY() + 1) * region.sizeX() + 1,
						SignedDyadic.of(Math.scalb(17.0, -24))),
				new PendingPhaseVolumeState.Entry((3 * region.sizeY() + 2) * region.sizeX() + 3,
						SignedDyadic.of(Math.scalb(-9.0, -24)))));
		region.domain().restorePendingPhaseVolumeState(expectedPending);
		FlipThermalParticleState expected = region.domain().exportThermalParticleState();
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		GasStateSnapshot expectedGas = region.domain().exportGasState("");
		GranularStateSnapshot expectedGranular = region.domain().exportGranularState();

		RegionState state = RegionState.captureThermal(region, 1L);
		state.validate();
		assertMaterialStateEquals(region.domain().exportMaterialParticleState(), state.reconstructMaterial());
		assertGranularStateEquals(expectedGranular, state.reconstructGranular(granular));
		assertGasStateEquals(expectedGas, state.reconstructGas(gas, null));
		assertThermalStateEquals(expected, state.reconstructThermal(thermal));
		require(state.reconstructPendingPhaseVolume().entries().equals(expectedPending.entries()),
				"pending phase-volume v7 record did not round-trip");
		FlipFluidDomain restoredDomain = new FlipFluidDomain(region.sizeX(), region.sizeY(), region.sizeZ(), CONFIG, 512);
		restoredDomain.sealDomainBoundary();
		restoredDomain.restoreMaterialParticleState(state.reconstructMaterial());
		restoredDomain.restoreGranularState(state.reconstructGranular(granular), granular,
				restoredDomain.materialRegistry());
		restoredDomain.restoreGasState(state.reconstructGas(gas, null), gas);
		restoredDomain.restoreThermalParticleState(state.reconstructThermal(thermal), thermal);
		restoredDomain.restorePendingPhaseVolumeState(state.reconstructPendingPhaseVolume());
		assertGranularStateEquals(expectedGranular, restoredDomain.exportGranularState());
		assertThermalStateEquals(expected, restoredDomain.exportThermalParticleState());
		require(restoredDomain.exportPendingPhaseVolumeState().entries().equals(expectedPending.entries()),
				"pending phase volume was not restored beside thermal inventory");

		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest decodedManifest = codec.decodeManifest(codec.encodeManifest(state.manifest()));
		require(decodedManifest.equals(state.manifest()) && decodedManifest.formatVersion() == 7
				&& decodedManifest.hasThermalState(), "region-state v7 manifest did not round-trip");
		for (RegionSectionState section : state.sections()) {
			RegionSectionState decoded = codec.decodeSection(codec.encodeSection(section));
			require(decoded.formatVersion() == 7 && decoded.extensions().equals(section.extensions())
					&& Arrays.equals(decoded.markerOrdinals(), section.markerOrdinals()),
					"region-state v7 thermal section did not round-trip");
		}
		CoherentPhaseStore.Composition composition = new CoherentPhaseStore.Composition(List.of(
				new CoherentPhaseStore.Component("test:a", 7L),
				new CoherentPhaseStore.Component("test:b", 11L)), Double.doubleToRawLongBits(0.25));
		ThermalRegionStateCodec thermalCodec = new ThermalRegionStateCodec();
		ThermalRegionStateCodec.SectionData composed = new ThermalRegionStateCodec.SectionData(
				new long[0], new long[0], new long[0], List.of(new ThermalRegionStateCodec.CoherentEntry(
				3, "test:solid", 8L, Double.doubleToRawLongBits(2.0), composition,
				Double.doubleToRawLongBits(5.0))), SignedDyadic.of(5.0));
		ThermalRegionStateCodec.SectionData decodedComposed = thermalCodec.decodeSection(
				thermalCodec.encodeSection(composed), 0, 0, 0);
		require(decodedComposed.coherentGroups().equals(composed.coherentGroups())
				&& decodedComposed.energy().equals(composed.energy()),
				"coherent composition did not round-trip the thermal section payload");

		Path root = Files.createTempDirectory(Path.of("."), "thermal-v7-");
		RegionStateFiles files = new RegionStateFiles(root, null, granular, thermal, gas);
		files.publish(state);
		assertThermalStateEquals(expected, files.read(region.id()).reconstructThermal(thermal));
		require(files.read(region.id()).reconstructPendingPhaseVolume().entries().equals(expectedPending.entries()),
				"published pending phase volume did not survive committed readback");
		expectIOException(() -> state.reconstructThermal(thermalRegistry(new FluidMaterialRegistry(
				List.of(BuiltinFluidMaterials.WATER)))), "foreign v7 thermal registry was accepted");

		RegionSectionState original = state.sections().stream()
				.filter(section -> section.markerCount() > 0).findFirst().orElseThrow();
		CompoundTag missingThermal = codec.encodeSection(original);
		missingThermal.put("Extensions", new net.minecraft.nbt.ListTag());
		RegionSectionState unsignedMissing = codec.decodeSection(missingThermal);
		List<RegionSectionState> brokenSections = new ArrayList<>(state.sections());
		brokenSections.set(brokenSections.indexOf(original), signedSection(unsignedMissing));
		expectIOException(() -> new RegionState(state.manifest(), brokenSections).validate(),
				"v7 section missing its required thermal payload was accepted");

		List<RegionExtension> withoutBuoyancy = state.manifest().extensions().stream()
				.filter(extension -> !ThermalBuoyancyCodec.MANIFEST_EXTENSION.equals(extension.type())).toList();
		expectIOException(() -> new RegionState(manifestWithExtensions(state.manifest(), withoutBuoyancy),
				state.sections()).validate(), "region-state v7 without buoyancy identity was accepted");
	}

	private static void validateGranularV5Codec() throws Exception {
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		FluidRegion region = populatedRegion(new UUID(23L, 24L));
		FlipParticleState pure = region.domain().exportParticleState();
		float[] positions = pure.copyPositionVolume();
		double[] liquidMass = new double[pure.particleCount()];
		double totalLiquidMass = 0.0;
		for (int marker = 0; marker < liquidMass.length; marker++) {
			liquidMass[marker] = positions[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			totalLiquidMass += liquidMass[marker];
		}
		region.domain().restoreMaterialParticleState(new FlipMaterialParticleState(pure.sizeX(), pure.sizeY(), pure.sizeZ(),
				pure.cellSize(), pure.particleCount(), pure.totalVolume(), totalLiquidMass, BuiltinFluidMaterials.DEFAULT_PALETTE,
				positions, pure.copyParticleVelocities(), pure.copySolidMask(), liquidMass, new int[pure.particleCount()]));
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long activeSvq = GranularLimits.MAX_PARCEL_VOLUME_SVQ, depositSvq = activeSvq - 17L;
		double activeMass = 3.25, depositMass = 4.75;
		int depositCell = (5 * region.sizeY() + 3) * region.sizeX() + 5;
		GranularStateSnapshot fixture = new GranularStateSnapshot(region.sizeX(), region.sizeY(), region.sizeZ(),
				granular.fingerprint(), granular.materials().stream().map(cc.ecisr.mmm.granular.ParticulateMaterial::id).toList(),
				List.of(new GranularStateSnapshot.ActiveParcel(0.75F, 0.75F, 0.75F,
						0.125F, -0.25F, 0.0625F, activeSvq, activeMass, sand, 0)),
				List.of(new GranularStateSnapshot.DepositGroup(depositCell, granular.material(sand).restPorosityQ(),
						sand, 0, depositSvq, depositMass)), Math.addExact(activeSvq, depositSvq),
				Double.doubleToRawLongBits(activeMass + depositMass));
		region.domain().restoreGranularState(fixture, granular, liquids);
		GranularStateSnapshot expected = region.domain().exportGranularState();
		RegionState state = RegionState.captureGranular(region, 1L);
		state.validate();
		assertMaterialStateEquals(region.domain().exportMaterialParticleState(), state.reconstructMaterial());
		assertGranularStateEquals(expected, state.reconstructGranular(granular));

		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest decodedManifest = codec.decodeManifest(codec.encodeManifest(state.manifest()));
		require(decodedManifest.equals(state.manifest()) && decodedManifest.formatVersion() == 5
				&& !decodedManifest.hasChemicalState(), "region-state v5 manifest did not round-trip");
		for (RegionSectionState section : state.sections()) {
			RegionSectionState decoded = codec.decodeSection(codec.encodeSection(section));
			require(decoded.formatVersion() == 5 && decoded.key().equals(section.key())
					&& decoded.extensions().equals(section.extensions())
					&& Arrays.equals(decoded.markerOrdinals(), section.markerOrdinals())
					&& Arrays.equals(decoded.solidMask(), section.solidMask())
					&& decoded.checksum() == section.checksum(),
					"region-state v5 granular section did not round-trip");
		}
		GranularRegionStateCodec granularCodec = new GranularRegionStateCodec();
		byte[] unsupportedManifestPayload = state.manifest().extensions().stream()
				.filter(extension -> extension.type().equals(GranularRegionStateCodec.MANIFEST_EXTENSION))
				.findFirst().orElseThrow().payload();
		java.nio.ByteBuffer.wrap(unsupportedManifestPayload).putInt(Integer.BYTES, 1);
		expectIOException(() -> granularCodec.decodeManifest(unsupportedManifestPayload),
				"non-current granular manifest payload was accepted");
		byte[] unsupportedSectionPayload = state.sections().stream().flatMap(section -> section.extensions().stream())
				.filter(extension -> extension.type().equals(GranularRegionStateCodec.SECTION_EXTENSION))
				.findFirst().orElseThrow().payload();
		java.nio.ByteBuffer.wrap(unsupportedSectionPayload).putInt(Integer.BYTES, 1);
		expectIOException(() -> granularCodec.decodeSection(unsupportedSectionPayload,
				GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION),
				"non-current granular section payload was accepted");

		Path root = Files.createTempDirectory(Path.of("."), "granular-v5-");
		RegionStateFiles files = new RegionStateFiles(root, null, granular);
		files.publish(state);
		RegionState restored = files.read(region.id());
		assertGranularStateEquals(expected, restored.reconstructGranular(granular));

		FluidRegion reactiveRegion = populatedRegion(new UUID(25L, 26L));
		FlipParticleState reactivePure = reactiveRegion.domain().exportParticleState();
		float[] reactivePositions = reactivePure.copyPositionVolume();
		double[] reactiveLiquidMass = new double[reactivePure.particleCount()]; double reactiveTotalMass = 0.0;
		for (int marker = 0; marker < reactiveLiquidMass.length; marker++) {
			reactiveLiquidMass[marker] = reactivePositions[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			reactiveTotalMass += reactiveLiquidMass[marker];
		}
		reactiveRegion.domain().restoreMaterialParticleState(new FlipMaterialParticleState(reactivePure.sizeX(),
				reactivePure.sizeY(), reactivePure.sizeZ(), reactivePure.cellSize(), reactivePure.particleCount(),
				reactivePure.totalVolume(), reactiveTotalMass, BuiltinFluidMaterials.DEFAULT_PALETTE, reactivePositions,
				reactivePure.copyParticleVelocities(), reactivePure.copySolidMask(), reactiveLiquidMass,
				new int[reactivePure.particleCount()]));
		reactiveRegion.domain().bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		SolidCompositionRecord composition = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(BuiltinChemicalDefinitions.NA_PLUS, 640L,
						BuiltinChemicalDefinitions.CL_MINUS, 640L), 0.0);
		GranularStateSnapshot reactiveFixture = new GranularStateSnapshot(reactiveRegion.sizeX(), reactiveRegion.sizeY(),
				reactiveRegion.sizeZ(), granular.fingerprint(), BuiltinChemicalDefinitions.REGISTRY.fingerprint(),
				granular.materials().stream().map(cc.ecisr.mmm.granular.ParticulateMaterial::id).toList(),
				List.of(new GranularStateSnapshot.StableComposition(List.of(
						new GranularStateSnapshot.CompositionComponent(BuiltinChemicalDefinitions.CL_MINUS, 640L),
						new GranularStateSnapshot.CompositionComponent(BuiltinChemicalDefinitions.NA_PLUS, 640L)), 0L)),
				java.util.Map.of(BuiltinChemicalDefinitions.CL_MINUS, 640L, BuiltinChemicalDefinitions.NA_PLUS, 640L),
				List.of(new GranularStateSnapshot.ActiveParcel(0.75F, 0.75F, 0.75F, 0.0F, 0.0F, 0.0F,
						10L, composition.totalMass(BuiltinChemicalDefinitions.REGISTRY),
						granular.materialHandle(BuiltinGranularDefinitions.PRECIPITATE), 1)), List.of(), 10L,
				Double.doubleToRawLongBits(composition.totalMass(BuiltinChemicalDefinitions.REGISTRY)));
		reactiveRegion.domain().restoreGranularState(reactiveFixture, granular, liquids);
		var removedReactive = reactiveRegion.domain().tryExtractExactGranular(1, 1, 1, 10L, 0, true);
		require(removedReactive.transferred()
				&& reactiveRegion.domain().tryInsertExactGranular(1, 1, 1, removedReactive.ledger(), 0, true).inserted(),
				"reactive granular audit fixture could not cross the region boundary");
		GranularStateSnapshot reactiveExpected = reactiveRegion.domain().exportGranularState();
		RegionState reactiveState = RegionState.captureGranular(reactiveRegion, 1L);
		reactiveState.validate();
		GranularStateSnapshot reactiveRoundTrip = reactiveState.reconstructGranular(granular);
		reactiveRoundTrip.validateSolidCompositionMasses(BuiltinChemicalDefinitions.REGISTRY);
		assertGranularStateEquals(reactiveExpected, reactiveRoundTrip);
		Path reactiveRoot = Files.createTempDirectory(Path.of("."), "reactive-granular-v5-");
		RegionStateFiles reactiveFiles = new RegionStateFiles(
				reactiveRoot, BuiltinChemicalDefinitions.REGISTRY, granular);
		reactiveFiles.publish(reactiveState);
		reactiveRegion.domain().removeAllGranularExact();
		GranularStateSnapshot auditOnlyExpected = reactiveRegion.domain().exportGranularState();
		require(auditOnlyExpected.solidCompositions().isEmpty() && auditOnlyExpected.requiresChemistry(),
				"reactive clear fixture did not retain audit-only chemistry identity");
		RegionState auditOnlyState = RegionState.captureGranular(reactiveRegion, 2L);
		auditOnlyState.validate();
		GranularStateSnapshot auditOnlyRoundTrip = auditOnlyState.reconstructGranular(granular);
		auditOnlyRoundTrip.validateAgainst(granular, BuiltinChemicalDefinitions.REGISTRY);
		assertGranularStateEquals(auditOnlyExpected, auditOnlyRoundTrip);
		reactiveFiles.publish(auditOnlyState);

		CompoundTag unknownV8 = codec.encodeManifest(state.manifest());
		unknownV8.putInt("Version", 8); unknownV8.putInt("Palette", 8);
		expectIOException(() -> codec.decodeManifest(unknownV8), "unknown region-state v8 was treated as a known format");
		CompoundTag missingGranular = codec.encodeSection(state.sections().getFirst());
		missingGranular.put("Extensions", new net.minecraft.nbt.ListTag());
		RegionSectionState unsignedMissing = codec.decodeSection(missingGranular);
		RegionSectionState signedMissing = signedSection(unsignedMissing);
		List<RegionSectionState> brokenSections = new ArrayList<>(state.sections());
		brokenSections.set(0, signedMissing);
		expectIOException(() -> new RegionState(state.manifest(), brokenSections).validate(),
				"v5 section missing its required granular payload was accepted");
	}

	private static void validateChemicalV3Codec() throws Exception {
		ChemicalRegistry chemistry = BuiltinChemicalDefinitions.REGISTRY;
		FluidRegion region = populatedRegion(new UUID(19L, 20L));
		FlipParticleState pure = region.domain().exportParticleState();
		double[] mass = new double[pure.particleCount()];
		int[] material = new int[pure.particleCount()];
		double totalMass = 0.0;
		float[] positionVolume = pure.copyPositionVolume();
		for (int marker = 0; marker < pure.particleCount(); marker++) {
			mass[marker] = positionVolume[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			totalMass += mass[marker];
		}
		region.domain().restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(), pure.totalVolume(),
				totalMass, BuiltinFluidMaterials.DEFAULT_PALETTE, positionVolume, pure.copyParticleVelocities(),
				pure.copySolidMask(), mass, material));
		region.domain().bindChemistry(chemistry);
		region.domain().setMarkerChemical(0, java.util.Map.of(
				BuiltinChemicalDefinitions.H2O, 123_457L,
				BuiltinChemicalDefinitions.NACL, 9_999L), true);
		var auditFixture = region.domain().chemicalLedgerForMarkerRange(0, 1);
		region.domain().chemicalParcels().recordExternalSource(auditFixture);
		region.domain().chemicalParcels().recordEscapedSink(auditFixture);
		FlipChemicalParticleState expected = region.domain().exportChemicalParticleState();
		RegionState state = RegionState.captureChemical(region, 1L);
		state.validate();
		expectIOException(state::reconstruct, "chemical v3 was silently reconstructed as pure water");
		expectIOException(state::reconstructMaterial, "chemical v3 was silently reconstructed as material v2");
		assertChemicalStateEquals(expected, state.reconstructChemical(chemistry));

		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest manifest = codec.decodeManifest(codec.encodeManifest(state.manifest()));
		require(manifest.equals(state.manifest())
				&& Arrays.equals(manifest.chemicalSpeciesTotals(), state.manifest().chemicalSpeciesTotals()),
				"region-state v3 manifest did not round-trip");
		for (RegionSectionState section : state.sections()) {
			RegionSectionState decoded = codec.decodeSection(codec.encodeSection(section));
			require(decoded.formatVersion() == 3
					&& Arrays.equals(decoded.chemicalMixtureIndex(), section.chemicalMixtureIndex())
					&& Arrays.equals(decoded.indicator(), section.indicator())
					&& Arrays.equals(decoded.chemicalSpeciesTotals(), section.chemicalSpeciesTotals())
					&& decoded.chemicalResidualLedgerBits() == section.chemicalResidualLedgerBits(),
					"region-state v3 section did not round-trip");
		}

		Path root = Files.createTempDirectory(Path.of("."), "chemical-v3-");
		RegionStateFiles files = new RegionStateFiles(root, chemistry);
		files.publish(state);
		assertChemicalStateEquals(expected, files.read(region.id()).reconstructChemical(chemistry));

		FluidRegion emptyRegion = emptyRegion(new UUID(21L, 22L));
		FlipParticleState emptyPure = emptyRegion.domain().exportParticleState();
		emptyRegion.domain().restoreMaterialParticleState(new FlipMaterialParticleState(
				emptyPure.sizeX(), emptyPure.sizeY(), emptyPure.sizeZ(), emptyPure.cellSize(), 0, 0.0, 0.0,
				BuiltinFluidMaterials.DEFAULT_PALETTE, new float[0], new float[0], emptyPure.copySolidMask(),
				new double[0], new int[0]));
		emptyRegion.domain().bindChemistry(chemistry);
		FlipChemicalParticleState emptyExpected = emptyRegion.domain().exportChemicalParticleState();
		require(emptyExpected.particleCount() == 0 && emptyExpected.mixtures().isEmpty(),
				"empty chemistry handoff invented a parcel mixture");
		RegionState emptyState = RegionState.captureChemical(emptyRegion, 1L);
		emptyState.validate();
		RegionManifest emptyManifest = codec.decodeManifest(codec.encodeManifest(emptyState.manifest()));
		require(emptyManifest.chemicalMixtures().isEmpty(), "empty v3 manifest invented a chemical mixture");
		assertChemicalStateEquals(emptyExpected, emptyState.reconstructChemical(chemistry));
		files.publish(emptyState);
		assertChemicalStateEquals(emptyExpected, files.read(emptyRegion.id()).reconstructChemical(chemistry));

		CompoundTag missingIndicator = codec.encodeSection(state.sections().stream()
				.filter(section -> section.markerCount() > 0).findFirst().orElseThrow());
		missingIndicator.remove("Indicator");
		expectIOException(() -> codec.decodeSection(missingIndicator), "v3 section missing indicator bits was accepted");
		CompoundTag foreignFingerprint = codec.encodeManifest(state.manifest());
		foreignFingerprint.putString("RegistryFingerprint", "0".repeat(64));
		RegionManifest foreign = signedManifest(codec.decodeManifest(foreignFingerprint));
		expectIOException(() -> new RegionState(foreign, state.sections()).reconstructChemical(chemistry),
				"foreign v3 registry was accepted without authoritative reconstruction");

		Path regionDir = root.toAbsolutePath().normalize().resolve("mmm").resolve("region-state")
				.resolve(region.id().toString());
		CompoundTag head = NbtIo.readCompressed(regionDir.resolve("HEAD.nbt"), NbtAccounter.unlimitedHeap());
		Path activeSlot = regionDir.resolve(head.getStringOr("Slot", ""));
		Path section = Files.list(activeSlot)
				.filter(path -> path.getFileName().toString().startsWith("section_"))
				.findFirst().orElseThrow();
		Files.delete(section);
		RegionStateFiles.Discovery discovery = files.discover();
		require(discovery.manifests().stream().noneMatch(value -> value.regionId().equals(region.id()))
				&& discovery.issues().containsKey(regionDir),
				"discovery admitted a v3 manifest whose committed section was missing");
	}

	private static void validateMaterialV2Codec() throws Exception {
		FluidRegion region = populatedRegion(new UUID(15L, 16L));
		List<FluidMaterial> palette = List.of(
				new FluidMaterial("mmm:water", 1_000.0, "mmm:water", 0.0, Math.PI / 2.0),
				new FluidMaterial("mmm:oil", 800.0, "mmm:oil", 0.0, Math.PI / 2.0)
		);
		FlipParticleState pureParticles = region.domain().exportParticleState();
		float[] positions = pureParticles.copyPositionVolume();
		double[] markerMass = new double[pureParticles.particleCount()];
		int[] materialIndices = new int[pureParticles.particleCount()];
		double totalMass = 0.0;
		for (int ordinal = 0; ordinal < pureParticles.particleCount(); ordinal++) {
			int material = ordinal & 1;
			materialIndices[ordinal] = material;
			markerMass[ordinal] = positions[ordinal * 4 + 3] * palette.get(material).referenceDensity();
			totalMass += markerMass[ordinal];
		}
		FlipMaterialParticleState expected = new FlipMaterialParticleState(pureParticles.sizeX(), pureParticles.sizeY(), pureParticles.sizeZ(),
				pureParticles.cellSize(), pureParticles.particleCount(), sumVolumes(positions), totalMass, palette, positions,
				pureParticles.copyParticleVelocities(), pureParticles.copySolidMask(), markerMass, materialIndices);
		region.domain().restoreMaterialParticleState(expected);
		RegionState state = RegionState.captureMaterial(region, 1L);
		require(state.manifest().checksum() == 1_833_858_350L
				&& state.sections().stream().map(RegionSectionState::checksum).toList().equals(List.of(
				2_322_127_614L, 2_368_282_172L, 2_710_607_279L, 3_147_109_226L)),
				"region-state v2 canonical golden bytes changed");
		state.validate();
		expectIOException(state::reconstruct, "material v2 was silently reconstructed as pure water");
		assertMaterialStateEquals(expected, state.reconstructMaterial());

		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest decodedManifest = codec.decodeManifest(codec.encodeManifest(state.manifest()));
		require(decodedManifest.equals(state.manifest()), "region-state v2 manifest did not round-trip");
		for (RegionSectionState section : state.sections()) {
			RegionSectionState decoded = codec.decodeSection(codec.encodeSection(section));
			require(decoded.formatVersion() == 2 && Arrays.equals(decoded.markerMass(), section.markerMass())
					&& Arrays.equals(decoded.materialPaletteIndex(), section.materialPaletteIndex())
					&& decoded.checksum() == section.checksum(), "region-state v2 section did not round-trip");
		}
		CompoundTag missingMass = codec.encodeSection(state.sections().getFirst());
		missingMass.remove("MassBits");
		expectIOException(() -> codec.decodeSection(missingMass), "v2 section missing marker mass was accepted");
		int changedIndex = 0;
		while (state.sections().get(changedIndex).markerCount() == 0) changedIndex++;
		RegionSectionState originalSection = state.sections().get(changedIndex);
		double[] changedMass = originalSection.markerMass();
		changedMass[0] += 1.0;
		RegionSectionState changedSection = signedSection(new RegionSectionState(2, originalSection.regionId(),
				originalSection.dimensionId(), originalSection.checkpointGeneration(), originalSection.sectionX(), originalSection.sectionY(),
				originalSection.sectionZ(), originalSection.localStartX(), originalSection.localStartY(), originalSection.localStartZ(),
				originalSection.sizeX(), originalSection.sizeY(), originalSection.sizeZ(), originalSection.markerOrdinals(),
				originalSection.positionVolume(), originalSection.velocities(), changedMass, originalSection.materialPaletteIndex(),
				originalSection.solidMask(), originalSection.extensions(), 0L));
		List<RegionSectionState> changedSections = new ArrayList<>(state.sections());
		changedSections.set(changedIndex, changedSection);
		List<RegionManifest.SectionDescriptor> changedDescriptors = new ArrayList<>(state.manifest().sections());
		for (int descriptor = 0; descriptor < changedDescriptors.size(); descriptor++) {
			RegionManifest.SectionDescriptor current = changedDescriptors.get(descriptor);
			if (current.key().equals(changedSection.key())) {
				changedDescriptors.set(descriptor, new RegionManifest.SectionDescriptor(current.sectionX(), current.sectionY(), current.sectionZ(),
						current.markerCount(), current.volumeBits(), Double.doubleToRawLongBits(sum(changedMass)), changedSection.checksum()));
			}
		}
		RegionManifest base = state.manifest();
		RegionManifest changedManifest = signedManifest(new RegionManifest(2, base.regionId(), base.dimensionId(), base.checkpointGeneration(),
				base.networkGeneration(), base.origin(), base.sizeX(), base.sizeY(), base.sizeZ(), base.solverConfig(),
				base.reservedMarkerCapacity(), base.runtime(), base.footprint(), base.totalMarkerCount(), base.totalVolumeBits(),
				base.materialPalette(), base.totalMassBits(), base.materialLedgers(), changedDescriptors, base.extensions(), 0L));
		expectIOException(() -> new RegionState(changedManifest, changedSections).validate(),
				"rechecksummed v2 marker-mass corruption escaped exact material ledgers");
		RegionState v1State = RegionState.capture(populatedRegion(new UUID(17L, 18L)), 1L);
		RegionManifest v1Manifest = v1State.manifest();
		require(v1Manifest.checksum() == 3_787_058_426L
				&& v1State.sections().stream().map(RegionSectionState::checksum).toList().equals(List.of(
				2_773_569_827L, 3_307_158_646L, 2_205_742_042L, 252_601_808L)),
				"region-state v1 canonical golden bytes changed");
		CompoundTag encodedV1 = codec.encodeManifest(v1Manifest);
		require(encodedV1.get("Materials") == null && encodedV1.get("MassBits") == null,
				"v1 manifest acquired v2 material fields");

		Path root = Files.createTempDirectory(Path.of("."), "material-v2-");
		RegionStateFiles files = new RegionStateFiles(root);
		files.publish(state);
		FlipMaterialParticleState restored = files.read(region.id()).reconstructMaterial();
		assertMaterialStateEquals(expected, restored);
		require(region.recordPublishedCheckpoint(1L) && region.releaseSolverAfterCheckpoint(1L),
				"material controller fixture did not release its published solver");
		region.restoreMaterialSolver(restored, 1L);
		require(region.domain().hasMaterialState(), "material controller restore lost its bound registry");
		assertMaterialStateEquals(expected, region.domain().exportMaterialParticleState());
	}

	private static void validateExactRoundTripAndMetadataUnload() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "roundtrip-");
		FluidRegion region = populatedRegion(new UUID(11L, 12L));
		FlipParticleState before = region.domain().exportParticleState();
		RegionState state = RegionState.capture(region, 1L);
		require(state.sections().size() == 4, "cross-chunk controller was not split into four complete sections");
		RegionStateFiles files = new RegionStateFiles(root);
		require(files.publish(state) == 1L, "first checkpoint generation was not published");
		RegionManifest manifest = files.readManifest(region.id());
		require(manifest.totalMarkerCount() == before.particleCount()
				&& Float.floatToRawIntBits((float) manifest.totalVolume()) == Float.floatToRawIntBits(before.totalVolume()),
				"manifest did not retain the exact marker ledger");
		FlipParticleState after = files.read(region.id()).reconstruct();
		assertParticleStateEquals(before, after);
		FluidRegion startupMetadata = FluidRegion.unloaded(manifest.regionId(), DIMENSION, manifest.origin(), manifest.sizeX(),
				manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(), manifest.reservedMarkerCapacity(), manifest.footprint(),
				manifest.networkGeneration(), manifest.checkpointGeneration(), manifest.totalMarkerCount(),
				(float) manifest.totalVolume(), manifest.runtime());
		require(!startupMetadata.solverLoaded() && startupMetadata.lifecycle().state() == FluidRegionLifecycle.State.UNLOADED,
				"startup metadata allocated solver arrays");

		require(region.recordPublishedCheckpoint(1L), "live region rejected published generation");
		FluidRegionManager manager = new FluidRegionManager(new FluidRegionManager.Limits(2, 256L, 512L, 512L, 1_024L));
		require(manager.add(region).accepted(), "loaded fixture was not admitted");
		FluidRegionManager.BudgetUsage reserved = manager.usage();
		require(region.releaseSolverAfterCheckpoint(1L), "checkpointed solver was not released");
		require(!region.solverLoaded() && region.lifecycle().state() == FluidRegionLifecycle.State.UNLOADED,
				"released region did not become metadata-only UNLOADED");
		require(manager.usage().equals(reserved), "UNLOADED region escaped its hard reservation budget");
		region.restoreSolver(after, 1L);
		require(region.solverLoaded() && region.lifecycle().operatorPaused() && region.collisionRefreshRequired(),
				"restore bypassed operator pause or current-world collision refresh");
		assertParticleStateEquals(before, region.domain().exportParticleState());
		for (long generation = 2L; generation <= 4L; generation++) {
			RegionState repeated = RegionState.capture(region, generation);
			files.publish(repeated);
			require(region.recordPublishedCheckpoint(generation), "repeated checkpoint was not recorded");
			require(region.releaseSolverAfterCheckpoint(generation), "repeated unload was rejected");
			FlipParticleState restored = files.read(region.id()).reconstruct();
			region.restoreSolver(restored, generation);
			assertParticleStateEquals(before, region.domain().exportParticleState());
		}
	}

	private static void validateUnknownExtensions() throws Exception {
		FluidRegion region = populatedRegion(new UUID(21L, 22L));
		RegionState original = RegionState.capture(region, 1L);
		RegionExtension extension = new RegionExtension("future:test", new byte[] {1, 2, 3, 4});
		RegionManifest base = original.manifest();
		RegionManifest unsigned = new RegionManifest(base.regionId(), base.dimensionId(), base.checkpointGeneration(),
				base.networkGeneration(), base.origin(), base.sizeX(), base.sizeY(), base.sizeZ(), base.solverConfig(),
				base.reservedMarkerCapacity(), base.runtime(), base.footprint(), base.totalMarkerCount(), base.totalVolumeBits(),
				base.sections(), List.of(extension), 0L);
		RegionManifest signed = new RegionManifest(unsigned.regionId(), unsigned.dimensionId(), unsigned.checkpointGeneration(),
				unsigned.networkGeneration(), unsigned.origin(), unsigned.sizeX(), unsigned.sizeY(), unsigned.sizeZ(), unsigned.solverConfig(),
				unsigned.reservedMarkerCapacity(), unsigned.runtime(), unsigned.footprint(), unsigned.totalMarkerCount(), unsigned.totalVolumeBits(),
				unsigned.sections(), unsigned.extensions(), RegionStateIntegrity.manifestChecksum(unsigned));
		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest decoded = codec.decodeManifest(codec.encodeManifest(signed));
		require(decoded.extensions().equals(List.of(extension)), "unknown length-delimited manifest extension was not retained");
		RegionSectionState section = original.sections().getFirst();
		RegionSectionState unsignedSection = new RegionSectionState(section.regionId(), section.dimensionId(), section.checkpointGeneration(),
				section.sectionX(), section.sectionY(), section.sectionZ(), section.localStartX(), section.localStartY(), section.localStartZ(),
				section.sizeX(), section.sizeY(), section.sizeZ(), section.markerOrdinals(), section.positionVolume(), section.velocities(),
				section.solidMask(), List.of(extension), 0L);
		RegionSectionState signedSection = signedSection(unsignedSection);
		require(codec.decodeSection(codec.encodeSection(signedSection)).extensions().equals(List.of(extension)),
				"unknown length-delimited section extension was not retained");
		CompoundTag future = codec.encodeManifest(signed);
		future.putInt("Version", 4); future.putInt("Palette", 4);
		expectIOException(() -> codec.decodeManifest(future), "unknown region-state version was accepted");
	}

	private static void validateFaultMatrix() throws Exception {
		for (RegionStateFiles.FaultPoint point : RegionStateFiles.FaultPoint.values()) {
			Path root = Files.createTempDirectory(Path.of("."), "fault-" + point.name().toLowerCase() + "-");
			FluidRegion region = populatedRegion(UUID.randomUUID());
			RegionStateFiles files = new RegionStateFiles(root);
			RegionState first = RegionState.capture(region, 1L);
			files.publish(first);
			FlipParticleState firstParticles = first.reconstruct();
			FluidRegion nextRegion = populatedRegion(region.id());
			nextRegion.domain().setVolume(1, 1, 1, 0.5F);
			RegionState second = RegionState.capture(nextRegion, 2L);
			FlipParticleState secondParticles = second.reconstruct();
			try {
				files.publish(second, (observed, path) -> { if (observed == point) throw new IOException("injected " + point); });
				throw new AssertionError("Fault point did not interrupt publication: " + point);
			} catch (IOException expected) { }
			RegionState reopened = new RegionStateFiles(root).read(region.id());
			long expectedGeneration = point == RegionStateFiles.FaultPoint.AFTER_HEAD_PUBLISH ? 2L : 1L;
			require(reopened.manifest().checkpointGeneration() == expectedGeneration,
					"fault exposed a mixed or unexpected generation at " + point);
			require(reopened.manifest().formatVersion() == 1, "fault changed the current pure-water layout");
			assertParticleStateEquals(expectedGeneration == 1L ? firstParticles : secondParticles, reopened.reconstruct());
		}
	}

	private static void validateCorruptionAndAtomicMoveFailure() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "corrupt-");
		FluidRegion region = populatedRegion(UUID.randomUUID());
		RegionStateFiles files = new RegionStateFiles(root);
		RegionState original = RegionState.capture(region, 1L);
		RegionSectionState first = original.sections().getFirst();
		RegionSectionState wrongOwner = signedSection(new RegionSectionState(UUID.randomUUID(), first.dimensionId(),
				first.checkpointGeneration(), first.sectionX(), first.sectionY(), first.sectionZ(), first.localStartX(), first.localStartY(),
				first.localStartZ(), first.sizeX(), first.sizeY(), first.sizeZ(), first.markerOrdinals(), first.positionVolume(),
				first.velocities(), first.solidMask(), first.extensions(), 0L));
		List<RegionSectionState> mixedSections = new ArrayList<>(original.sections());
		mixedSections.set(0, wrongOwner);
		expectIOException(() -> new RegionState(original.manifest(), mixedSections).validate(), "mixed section owner was accepted");
		files.publish(original);
		require(region.recordPublishedCheckpoint(1L), "corruption fixture did not advance generation");
		region.domain().setVolume(3, 2, 3, 0.5F);
		RegionState second = RegionState.capture(region, 2L);
		RegionStateFiles unsupported = new RegionStateFiles(root, (source, target, replace) -> {
			throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "validation");
		});
		expectIOException(() -> unsupported.publish(second), "unsupported atomic move did not fail closed");
		require(files.read(region.id()).manifest().checkpointGeneration() == 1L,
				"failed slot publication changed the committed generation");

		Path regionDir = root.toAbsolutePath().normalize().resolve("mmm").resolve("region-state")
				.resolve(region.id().toString());
		CompoundTag head = NbtIo.readCompressed(regionDir.resolve("HEAD.nbt"), NbtAccounter.unlimitedHeap());
		Path slot = regionDir.resolve(head.getStringOr("Slot", ""));
		Path section = Files.list(slot).filter(path -> path.getFileName().toString().startsWith("section_")).findFirst().orElseThrow();
		Files.delete(section);
		expectIOException(() -> files.read(region.id()), "missing section was accepted");
		RegionStateFiles.Discovery missingSection = files.discover();
		require(missingSection.manifests().isEmpty() && missingSection.issues().containsKey(regionDir),
				"discovery admitted a manifest whose committed section was missing");

		Path headRoot = Files.createTempDirectory(Path.of("."), "head-corrupt-");
		FluidRegion headRegion = populatedRegion(UUID.randomUUID());
		RegionStateFiles headFiles = new RegionStateFiles(headRoot);
		headFiles.publish(RegionState.capture(headRegion, 1L));
		Path headPath = headRoot.resolve("mmm").resolve("region-state").resolve(headRegion.id().toString()).resolve("HEAD.nbt");
		CompoundTag corruptHead = NbtIo.readCompressed(headPath, NbtAccounter.unlimitedHeap());
		corruptHead.putLong("Checksum", corruptHead.getLongOr("Checksum", 0L) + 1L);
		NbtIo.writeCompressed(corruptHead, headPath);
		expectIOException(() -> headFiles.read(headRegion.id()), "corrupt HEAD checksum was accepted");

		Path deleteRoot = Files.createTempDirectory(Path.of("."), "quarantine-");
		FluidRegion deleteRegion = populatedRegion(UUID.randomUUID());
		RegionStateFiles deleteFiles = new RegionStateFiles(deleteRoot);
		deleteFiles.publish(RegionState.capture(deleteRegion, 1L));
		deleteFiles.quarantine(deleteRegion.id());
		require(deleteFiles.discover().manifests().isEmpty() && deleteFiles.discover().issues().isEmpty(),
				"logically deleted checkpoint remained discoverable");
	}

	private static void validateWorldConfig() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "config-");
		FluidServerConfig defaults = FluidServerConfig.loadOrCreate(root);
		require(!defaults.controllersEnabled() && !defaults.materialsEnabled() && !defaults.worldBridgeEnabled()
				&& !defaults.chemistryEnabled() && !defaults.granularEnabled() && !defaults.precipitationEnabled()
				&& !defaults.thermalEnabled() && !defaults.phaseChangeEnabled()
				&& defaults.limits().maximumRegions() == 4, "safe default config changed");
		FluidServerConfig enabled = defaults.withControllersEnabled(true).withMaterialsEnabled(true)
				.withWorldBridgeEnabled(true).withChemistryEnabled(true).withGranularEnabled(true)
				.withPrecipitationEnabled(true).withThermalEnabled(true).withPhaseChangeEnabled(true);
		enabled.save(root);
		require(FluidServerConfig.loadOrCreate(root).equals(enabled), "world-local config did not round-trip");

		String valid = Files.readString(FluidServerConfig.path(root));
		Files.writeString(FluidServerConfig.path(root), valid.replace("materialsEnabled=true", "materialsEnabled=false"));
		expectIOException(() -> FluidServerConfig.loadOrCreate(root),
				"invalid current feature dependency was accepted");

		Files.writeString(FluidServerConfig.path(root), valid.replace("formatVersion=6", "formatVersion=99"));
		expectIOException(() -> FluidServerConfig.loadOrCreate(root), "unknown config version was silently replaced");
	}

	/** prepare/commit/abort split: staging must stay unreadable until HEAD publishes, and a moved base must fail closed. */
	private static void validateStagedGeneration() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "staged-");
		RegionStateFiles files = new RegionStateFiles(root);
		UUID id = UUID.randomUUID();

		RegionState first = RegionState.capture(populatedRegion(id), 1L);
		RegionStateFiles.PreparedGeneration preparedFirst = files.prepare(first, (point, path) -> { });
		require(!preparedFirst.alreadyCommitted(), "first staging was marked idempotent");
		expectIOException(() -> files.read(id), "uncommitted staging exposed a readable generation");
		files.commit(preparedFirst, (point, path) -> { });
		require(files.read(id).manifest().checkpointGeneration() == 1L, "first staged commit did not publish");

		RegionState second = RegionState.capture(populatedRegion(id), 2L);
		RegionStateFiles.PreparedGeneration preparedSecond = files.prepare(second, (point, path) -> { });
		require(files.read(id).manifest().checkpointGeneration() == 1L, "staging exposed the uncommitted generation");
		files.abort(preparedSecond);
		require(files.read(id).manifest().checkpointGeneration() == 1L, "abort changed the committed generation");
		files.publish(second);
		require(files.read(id).manifest().checkpointGeneration() == 2L, "publish after abort did not advance");

		RegionStateFiles.PreparedGeneration stale = files.prepare(RegionState.capture(populatedRegion(id), 3L), (point, path) -> { });
		files.publish(RegionState.capture(populatedRegion(id), 4L));
		expectIOException(() -> files.commit(stale, (point, path) -> { }), "commit on a moved base was accepted");
		require(files.read(id).manifest().checkpointGeneration() == 4L, "moved-base failure changed the committed generation");

		RegionStateFiles.PreparedGeneration repeat = files.prepare(RegionState.capture(populatedRegion(id), 4L), (point, path) -> { });
		require(repeat.alreadyCommitted() && files.commit(repeat, (point, path) -> { }) == 4L,
				"idempotent re-staging was not recognized");
	}

	private static void validateExplicitPreviousGenerationActivation() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "previous-generation-");
		RegionStateFiles files = new RegionStateFiles(root);
		UUID id = UUID.randomUUID();
		FluidRegion region = populatedRegion(id);
		RegionState first = RegionState.capture(region, 1L);
		files.publish(first);
		region.domain().setVolume(3, 2, 3, 0.5F);
		RegionState second = RegionState.capture(region, 2L);
		files.publish(second);

		RegionStateFiles.PreviousGeneration previous = files.readPrevious(id, 2L);
		require(previous.state().manifest().checkpointGeneration() == 1L
				&& previous.state().manifest().checksum() == first.manifest().checksum(),
				"explicit previous-generation probe did not bind the inactive complete slot");
		expectIOException(() -> files.readPrevious(id, 3L),
				"previous-generation probe ignored its explicit current-generation guard");
		require(files.activatePrevious(previous) == 1L
				&& files.read(id).manifest().checkpointGeneration() == 1L
				&& files.read(id).manifest().checksum() == first.manifest().checksum(),
				"explicit previous-generation activation did not atomically move HEAD");

		files.publish(second);
		require(files.read(id).manifest().checkpointGeneration() == 2L
				&& files.read(id).manifest().checksum() == second.manifest().checksum(),
				"publication after explicit rollback did not replace the retained rejected slot");
	}

	private static FluidRegion populatedRegion(UUID id) {
		BlockPos origin = new BlockPos(15, 16, 15);
		FlipFluidDomain domain = new FlipFluidDomain(6, 4, 6, CONFIG, 512);
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

	private static FluidRegion emptyRegion(UUID id) {
		BlockPos origin = new BlockPos(32, 16, 32);
		FlipFluidDomain domain = new FlipFluidDomain(8, 8, 8, CONFIG, 4_096);
		Set<Long> interior = new HashSet<>();
		for (int z = 32; z <= 35; z++) for (int y = 16; y <= 19; y++) {
			for (int x = 32; x <= 35; x++) interior.add(new BlockPos(x, y, z).asLong());
		}
		BlockPos controller = new BlockPos(31, 17, 33);
		FluidRegionFootprint footprint = new FluidRegionFootprint(controller, origin, new BlockPos(35, 19, 35),
				interior, Set.of(controller.asLong()), Set.of(ChunkPos.pack(2, 2)));
		return new FluidRegion(id, DIMENSION, origin, domain, 4_096L, footprint);
	}


	private static void assertParticleStateEquals(FlipParticleState expected, FlipParticleState actual) {
		require(expected.particleCount() == actual.particleCount(), "marker count changed across persistence");
		require(Arrays.equals(floatBits(expected.copyPositionVolume()), floatBits(actual.copyPositionVolume())),
				"marker order/position/particleVolume changed across persistence");
		require(Arrays.equals(floatBits(expected.copyParticleVelocities()), floatBits(actual.copyParticleVelocities())),
				"marker velocity changed across persistence");
		require(Arrays.equals(expected.copySolidMask(), actual.copySolidMask()), "solid mask changed across persistence");
	}

	private static void assertGasStateEquals(GasStateSnapshot expected, GasStateSnapshot actual) {
		require(expected.sizeX() == actual.sizeX() && expected.sizeY() == actual.sizeY() && expected.sizeZ() == actual.sizeZ()
				&& expected.registryFingerprint().equals(actual.registryFingerprint())
				&& expected.chemicalRegistryFingerprint().equals(actual.chemicalRegistryFingerprint())
				&& expected.nodes().equals(actual.nodes())
				&& Arrays.equals(expected.cellNodeOrdinal(), actual.cellNodeOrdinal())
				&& expected.externalSource().equals(actual.externalSource())
				&& expected.escapedSink().equals(actual.escapedSink())
				&& expected.massRoundoff().equals(actual.massRoundoff())
				&& expected.residualRoundoff().equals(actual.residualRoundoff()),
				"gas component/topology/audit changed across persistence");
	}

	private static void assertMaterialStateEquals(FlipMaterialParticleState expected, FlipMaterialParticleState actual) {
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

	private static void assertThermalStateEquals(FlipThermalParticleState expected, FlipThermalParticleState actual) {
		require(expected.particleCount() == actual.particleCount()
				&& expected.thermalRegistryFingerprint().equals(actual.thermalRegistryFingerprint())
				&& Arrays.equals(expected.copyInternalEnergy(), actual.copyInternalEnergy())
				&& Arrays.equals(expected.copyActiveParcelEnergy(), actual.copyActiveParcelEnergy())
				&& Arrays.equals(expected.copyDepositEnergy(), actual.copyDepositEnergy())
				&& Arrays.equals(expected.copyGasNodeEnergy(), actual.copyGasNodeEnergy())
				&& expected.coherentGroups().equals(actual.coherentGroups())
				&& expected.audit().equals(actual.audit()),
				"thermal inventory/audit changed across persistence");
	}

	private static ThermalRegistry thermalRegistry(FluidMaterialRegistry liquids) {
		return thermalRegistry(liquids, null);
	}

	private static ThermalRegistry thermalRegistry(FluidMaterialRegistry liquids, GranularRegistry granular) {
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
		if (granular != null) for (int index = 0; index < granular.materials().size(); index++) {
			definitions.add(new ThermalMaterialDefinition("mmm:thermal/persistence_granular_" + index,
					ThermalMaterialDefinition.CarrierKind.GRANULAR, granular.material(index).id(),
					300.0, 20.0, 1.0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 10.0))));
		}
		return granular == null ? new ThermalRegistry(definitions)
				: new ThermalRegistry(definitions, List.of(), List.of(), "", granular.fingerprint());
	}

	private static void assertGranularStateEquals(GranularStateSnapshot expected, GranularStateSnapshot actual) {
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

	private static void assertChemicalStateEquals(FlipChemicalParticleState expected, FlipChemicalParticleState actual) {
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

	private static int[] floatBits(float[] values) {
		int[] result = new int[values.length];
		for (int i = 0; i < values.length; i++) result[i] = Float.floatToRawIntBits(values[i]);
		return result;
	}

	private static RegionSectionState signedSection(RegionSectionState section) {
		return new RegionSectionState(section.formatVersion(), section.regionId(), section.dimensionId(), section.checkpointGeneration(), section.sectionX(),
				section.sectionY(), section.sectionZ(), section.localStartX(), section.localStartY(), section.localStartZ(),
				section.sizeX(), section.sizeY(), section.sizeZ(), section.markerOrdinals(), section.positionVolume(),
				section.velocities(), section.markerMass(), section.materialPaletteIndex(), section.solidMask(), section.extensions(),
				section.chemicalMixtureIndex(), section.indicator(), section.chemicalSpeciesTotals(),
				section.chemicalResidualLedgerBits(), RegionStateIntegrity.sectionChecksum(section));
	}

	private static RegionManifest signedManifest(RegionManifest manifest) {
		return new RegionManifest(manifest.formatVersion(), manifest.regionId(), manifest.dimensionId(), manifest.checkpointGeneration(),
				manifest.networkGeneration(), manifest.origin(), manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(),
				manifest.reservedMarkerCapacity(), manifest.runtime(), manifest.footprint(), manifest.totalMarkerCount(), manifest.totalVolumeBits(),
				manifest.materialPalette(), manifest.totalMassBits(), manifest.materialLedgers(), manifest.sections(), manifest.extensions(),
				manifest.registryFingerprint(), manifest.auScale(), manifest.speciesPalette(), manifest.chemicalMixtures(),
				manifest.chemicalSpeciesTotals(), manifest.chemicalResidualLedgerBits(), RegionStateIntegrity.manifestChecksum(manifest));
	}

	private static RegionManifest manifestWithExtensions(
			RegionManifest manifest, List<RegionExtension> extensions
	) {
		RegionManifest unsigned = new RegionManifest(manifest.formatVersion(), manifest.regionId(), manifest.dimensionId(),
				manifest.checkpointGeneration(), manifest.networkGeneration(), manifest.origin(), manifest.sizeX(), manifest.sizeY(),
				manifest.sizeZ(), manifest.solverConfig(), manifest.reservedMarkerCapacity(), manifest.runtime(), manifest.footprint(),
				manifest.totalMarkerCount(), manifest.totalVolumeBits(), manifest.materialPalette(), manifest.totalMassBits(),
				manifest.materialLedgers(), manifest.sections(), extensions, manifest.registryFingerprint(), manifest.auScale(),
				manifest.speciesPalette(), manifest.chemicalMixtures(), manifest.chemicalSpeciesTotals(),
				manifest.chemicalResidualLedgerBits(), 0L);
		return signedManifest(unsigned);
	}

	private static double sumVolumes(float[] positionVolume) {
		double result = 0.0;
		for (int index = 3; index < positionVolume.length; index += 4) result += positionVolume[index];
		return result;
	}

	private static double sum(double[] values) {
		double result = 0.0;
		for (double value : values) result += value;
		return result;
	}

	private static void expectIOException(IoAction action, String message) throws Exception {
		try { action.run(); throw new AssertionError(message); }
		catch (IOException expected) { }
	}

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	@FunctionalInterface private interface IoAction { void run() throws Exception; }
}
