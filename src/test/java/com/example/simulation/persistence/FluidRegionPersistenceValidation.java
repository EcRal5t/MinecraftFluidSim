package com.example.simulation.persistence;

import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FlipChemicalParticleState;
import com.example.fluid.FlipMaterialParticleState;
import com.example.fluid.FlipParticleState;
import com.example.fluid.FlipThermalParticleState;
import com.example.fluid.BuiltinFluidMaterials;
import com.example.fluid.FluidMaterial;
import com.example.fluid.FluidMaterialRegistry;
import com.example.fluid.FluidSolverConfig;
import com.example.chemistry.BuiltinChemicalDefinitions;
import com.example.chemistry.ChemicalRegistry;
import com.example.granular.BuiltinGranularDefinitions;
import com.example.granular.GranularLimits;
import com.example.granular.GranularRegistry;
import com.example.granular.GranularStateSnapshot;
import com.example.granular.SolidCompositionRecord;
import com.example.granular.SolidVolume;
import com.example.thermal.ThermalMaterialDefinition;
import com.example.thermal.CoherentPhaseStore;
import com.example.thermal.SignedDyadic;
import com.example.thermal.ThermalRegistry;
import com.example.simulation.FluidRegion;
import com.example.simulation.FluidRegionLifecycle;
import com.example.simulation.FluidRegionManager;
import com.example.simulation.FluidServerConfig;
import com.example.simulation.controller.FluidRegionFootprint;
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
			Identifier.fromNamespaceAndPath("realwater", "persistence_validation_registry"));
	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(DIMENSION_REGISTRY,
			Identifier.fromNamespaceAndPath("realwater", "persistence_validation"));

	private FluidRegionPersistenceValidation() { }

	public static void main(String[] args) throws Exception {
		validateExactRoundTripAndMetadataUnload();
		validateMaterialV2Codec();
		validateChemicalV3Codec();
		validateGranularV5Codec();
		validateThermalV6Codec();
		validateUnknownExtensions();
		validateFaultMatrix();
		validateStagedGeneration();
		validateCorruptionAndAtomicMoveFailure();
		validateWorldConfig();
		System.out.println("Fluid region persistence validation passed");
	}

	private static void validateThermalV6Codec() throws Exception {
		FluidRegion region = populatedRegion(new UUID(27L, 28L));
		FlipParticleState pure = region.domain().exportParticleState();
		float[] positions = pure.copyPositionVolume();
		double[] mass = new double[pure.particleCount()];
		double totalMass = 0.0;
		for (int marker = 0; marker < mass.length; marker++) {
			mass[marker] = positions[marker * 4 + 3] * BuiltinFluidMaterials.WATER.referenceDensity();
			totalMass += mass[marker];
		}
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.M3_PALETTE);
		region.domain().restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(), pure.totalVolume(),
				totalMass, liquids.materials(), positions, pure.copyParticleVelocities(), pure.copySolidMask(),
				mass, new int[pure.particleCount()]));
		liquids = region.domain().materialRegistry();
		ThermalRegistry thermal = thermalRegistry(liquids);
		region.domain().bindThermal(thermal, liquids, 300.0);
		region.domain().setThermalTemperatureInCell(2, 1, 2, 325.0);
		region.domain().addCoherentPhaseExternal(3, 2, 3, "realwater:thermal/persistence_solid",
				SolidVolume.SVQ_PER_CELL / 8L, 0.125, null, 6.25);
		FlipThermalParticleState expected = region.domain().exportThermalParticleState();

		RegionState state = RegionState.captureThermal(region, 1L);
		state.validate();
		assertMaterialStateEquals(region.domain().exportMaterialParticleState(), state.reconstructMaterial());
		assertThermalStateEquals(expected, state.reconstructThermal(thermal));
		FlipFluidDomain restoredDomain = new FlipFluidDomain(region.sizeX(), region.sizeY(), region.sizeZ(), CONFIG, 512);
		restoredDomain.restoreMaterialParticleState(state.reconstructMaterial());
		restoredDomain.restoreThermalParticleState(state.reconstructThermal(thermal), thermal);
		assertThermalStateEquals(expected, restoredDomain.exportThermalParticleState());

		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest decodedManifest = codec.decodeManifest(codec.encodeManifest(state.manifest()));
		require(decodedManifest.equals(state.manifest()) && decodedManifest.formatVersion() == 6
				&& decodedManifest.hasThermalState(), "region-state v6 manifest did not round-trip");
		for (RegionSectionState section : state.sections()) {
			RegionSectionState decoded = codec.decodeSection(codec.encodeSection(section));
			require(decoded.formatVersion() == 6 && decoded.extensions().equals(section.extensions())
					&& Arrays.equals(decoded.markerOrdinals(), section.markerOrdinals()),
					"region-state v6 thermal section did not round-trip");
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
				"coherent composition did not round-trip the v6 thermal section payload");

		Path root = Files.createTempDirectory(Path.of("."), "thermal-v6-");
		RegionStateFiles files = new RegionStateFiles(root, null, null, thermal);
		files.publish(state);
		assertThermalStateEquals(expected, files.read(region.id()).reconstructThermal(thermal));
		expectIOException(() -> state.reconstructThermal(thermalRegistry(new FluidMaterialRegistry(
				List.of(BuiltinFluidMaterials.WATER)))), "foreign v6 thermal registry was accepted");

		RegionSectionState original = state.sections().stream()
				.filter(section -> section.markerCount() > 0).findFirst().orElseThrow();
		CompoundTag missingThermal = codec.encodeSection(original);
		missingThermal.put("Extensions", new net.minecraft.nbt.ListTag());
		RegionSectionState unsignedMissing = codec.decodeSection(missingThermal);
		List<RegionSectionState> brokenSections = new ArrayList<>(state.sections());
		brokenSections.set(brokenSections.indexOf(original), signedSection(unsignedMissing));
		expectIOException(() -> new RegionState(state.manifest(), brokenSections).validate(),
				"v6 section missing its required thermal payload was accepted");
	}

	private static void validateGranularV5Codec() throws Exception {
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.M3_PALETTE);
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
				pure.cellSize(), pure.particleCount(), pure.totalVolume(), totalLiquidMass, BuiltinFluidMaterials.M3_PALETTE,
				positions, pure.copyParticleVelocities(), pure.copySolidMask(), liquidMass, new int[pure.particleCount()]));
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long activeSvq = GranularLimits.MAX_PARCEL_VOLUME_SVQ, depositSvq = activeSvq - 17L;
		double activeMass = 3.25, depositMass = 4.75;
		int depositCell = (5 * region.sizeY() + 3) * region.sizeX() + 5;
		GranularStateSnapshot fixture = new GranularStateSnapshot(region.sizeX(), region.sizeY(), region.sizeZ(),
				granular.fingerprint(), granular.materials().stream().map(com.example.granular.ParticulateMaterial::id).toList(),
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
				reactivePure.totalVolume(), reactiveTotalMass, BuiltinFluidMaterials.M3_PALETTE, reactivePositions,
				reactivePure.copyParticleVelocities(), reactivePure.copySolidMask(), reactiveLiquidMass,
				new int[reactivePure.particleCount()]));
		reactiveRegion.domain().bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		SolidCompositionRecord composition = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(BuiltinChemicalDefinitions.NA_PLUS, 640L,
						BuiltinChemicalDefinitions.CL_MINUS, 640L), 0.0);
		GranularStateSnapshot reactiveFixture = new GranularStateSnapshot(reactiveRegion.sizeX(), reactiveRegion.sizeY(),
				reactiveRegion.sizeZ(), granular.fingerprint(), BuiltinChemicalDefinitions.REGISTRY.fingerprint(),
				granular.materials().stream().map(com.example.granular.ParticulateMaterial::id).toList(),
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

		CompoundTag reservedV4 = codec.encodeManifest(state.manifest());
		reservedV4.putInt("Version", 4); reservedV4.putInt("Palette", 4);
		expectIOException(() -> codec.decodeManifest(reservedV4), "reserved region-state v4 was treated as a known format");
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
				totalMass, BuiltinFluidMaterials.M3_PALETTE, positionVolume, pure.copyParticleVelocities(),
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
				BuiltinFluidMaterials.M3_PALETTE, new float[0], new float[0], emptyPure.copySolidMask(),
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

		Path regionDir = root.toAbsolutePath().normalize().resolve("realwater").resolve("region-state")
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
				new FluidMaterial("realwater:water", 1_000.0, "realwater:water", 0.0, Math.PI / 2.0),
				new FluidMaterial("realwater:oil", 800.0, "realwater:oil", 0.0, Math.PI / 2.0)
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
		FluidMaterial water = BuiltinFluidMaterials.WATER;
		for (RegionStateFiles.FaultPoint point : RegionStateFiles.FaultPoint.values()) {
			Path root = Files.createTempDirectory(Path.of("."), "fault-" + point.name().toLowerCase() + "-");
			FluidRegion region = populatedRegion(UUID.randomUUID());
			RegionStateFiles files = new RegionStateFiles(root);
			RegionState first = RegionState.capture(region, 1L);
			files.publish(first);
			FlipParticleState pure = first.reconstruct();
			RegionState second = RegionState.migrateV1(first, 2L, BuiltinFluidMaterials.M3_PALETTE);
			try {
				files.publish(second, (observed, path) -> { if (observed == point) throw new IOException("injected " + point); });
				throw new AssertionError("Fault point did not interrupt publication: " + point);
			} catch (IOException expected) { }
			RegionState reopened = new RegionStateFiles(root).read(region.id());
			long expectedGeneration = point == RegionStateFiles.FaultPoint.AFTER_HEAD_PUBLISH ? 2L : 1L;
			require(reopened.manifest().checkpointGeneration() == expectedGeneration,
					"fault exposed a mixed or unexpected generation at " + point);
			if (expectedGeneration == 1L) {
				require(reopened.manifest().formatVersion() == 1, "pre-HEAD migration fault exposed v2");
				assertParticleStateEquals(pure, reopened.reconstruct());
			} else {
				require(reopened.manifest().formatVersion() == 2, "post-HEAD migration did not expose v2");
				for (int material = 1; material < reopened.manifest().materialLedgers().size(); material++) {
					RegionManifest.MaterialLedger empty = reopened.manifest().materialLedgers().get(material);
					require(empty.markerCount() == 0 && empty.volumeBits() == Double.doubleToRawLongBits(0.0)
							&& empty.massBits() == Double.doubleToRawLongBits(0.0),
							"v1 migration did not preserve exact zero ledger for an unused material");
				}
				assertMigratedWaterState(pure, reopened.reconstructMaterial(), BuiltinFluidMaterials.M3_PALETTE);
			}
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

		Path regionDir = root.toAbsolutePath().normalize().resolve("realwater").resolve("region-state")
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
		Path headPath = headRoot.resolve("realwater").resolve("region-state").resolve(headRegion.id().toString()).resolve("HEAD.nbt");
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
				&& defaults.limits().maximumRegions() == 4, "safe default config changed");
		FluidServerConfig enabled = defaults.withControllersEnabled(true).withMaterialsEnabled(true)
				.withWorldBridgeEnabled(true).withChemistryEnabled(true).withGranularEnabled(true)
				.withPrecipitationEnabled(true);
		enabled.save(root);
		require(FluidServerConfig.loadOrCreate(root).equals(enabled), "world-local config did not round-trip");

		Path legacyRoot = Files.createTempDirectory(Path.of("."), "config-v1-");
		Files.createDirectories(FluidServerConfig.path(legacyRoot).getParent());
		Files.writeString(FluidServerConfig.path(legacyRoot), """
				formatVersion=1
				controllersEnabled=true
				maximumRegions=4
				maximumCellsPerRegion=9216
				maximumMarkersPerRegion=73728
				maximumTotalCells=36864
				maximumTotalMarkers=294912
				""");
		FluidServerConfig legacy = FluidServerConfig.loadOrCreate(legacyRoot);
		require(legacy.controllersEnabled() && !legacy.materialsEnabled() && !legacy.worldBridgeEnabled()
				&& !legacy.chemistryEnabled() && !legacy.granularEnabled() && !legacy.precipitationEnabled(),
				"v1 config did not migrate with materials and world bridge safely disabled");

		Path v2Root = Files.createTempDirectory(Path.of("."), "config-v2-");
		Files.createDirectories(FluidServerConfig.path(v2Root).getParent());
		Files.writeString(FluidServerConfig.path(v2Root), """
				formatVersion=2
				controllersEnabled=false
				materialsEnabled=true
				maximumRegions=4
				maximumCellsPerRegion=9216
				maximumMarkersPerRegion=73728
				maximumTotalCells=36864
				maximumTotalMarkers=294912
				""");
		FluidServerConfig v2 = FluidServerConfig.loadOrCreate(v2Root);
		require(v2.materialsEnabled() && !v2.worldBridgeEnabled() && !v2.chemistryEnabled()
				&& !v2.granularEnabled() && !v2.precipitationEnabled(),
				"v2 config did not keep newer features safely disabled");
		Files.writeString(FluidServerConfig.path(v2Root), Files.readString(FluidServerConfig.path(v2Root))
				.replace("formatVersion=2", "formatVersion=3"));
		expectIOException(() -> FluidServerConfig.loadOrCreate(v2Root), "v3 config without the worldBridgeEnabled key was accepted");

		Path v3Root = Files.createTempDirectory(Path.of("."), "config-v3-");
		Files.createDirectories(FluidServerConfig.path(v3Root).getParent());
		Files.writeString(FluidServerConfig.path(v3Root), """
				formatVersion=3
				controllersEnabled=true
				materialsEnabled=true
				worldBridgeEnabled=true
				maximumRegions=4
				maximumCellsPerRegion=9216
				maximumMarkersPerRegion=73728
				maximumTotalCells=36864
				maximumTotalMarkers=294912
				""");
		require(!FluidServerConfig.loadOrCreate(v3Root).chemistryEnabled(),
				"v3 config did not keep chemistry safely disabled");
		Files.writeString(FluidServerConfig.path(v3Root), Files.readString(FluidServerConfig.path(v3Root))
				.replace("formatVersion=3", "formatVersion=4"));
		expectIOException(() -> FluidServerConfig.loadOrCreate(v3Root),
				"v4 config without chemistryEnabled was accepted");
		Files.writeString(FluidServerConfig.path(v3Root), Files.readString(FluidServerConfig.path(v3Root))
				+ "chemistryEnabled=true\n");
		Files.writeString(FluidServerConfig.path(v3Root), Files.readString(FluidServerConfig.path(v3Root))
				.replace("materialsEnabled=true", "materialsEnabled=false"));
		expectIOException(() -> FluidServerConfig.loadOrCreate(v3Root),
				"chemistry=true/materials=false config was accepted");

		Path v5Root = Files.createTempDirectory(Path.of("."), "config-v5-");
		Files.createDirectories(FluidServerConfig.path(v5Root).getParent());
		Files.writeString(FluidServerConfig.path(v5Root), """
				formatVersion=5
				controllersEnabled=true
				materialsEnabled=true
				worldBridgeEnabled=true
				chemistryEnabled=true
				maximumRegions=4
				maximumCellsPerRegion=9216
				maximumMarkersPerRegion=73728
				maximumTotalCells=36864
				maximumTotalMarkers=294912
				""");
		expectIOException(() -> FluidServerConfig.loadOrCreate(v5Root),
				"v5 config without explicit granular gates/budgets was accepted");

		Files.writeString(FluidServerConfig.path(root), "formatVersion=99\ncontrollersEnabled=false\n");
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
				&& expected.coherentGroups().equals(actual.coherentGroups())
				&& expected.audit().equals(actual.audit()),
				"thermal inventory/audit changed across persistence");
	}

	private static ThermalRegistry thermalRegistry(FluidMaterialRegistry liquids) {
		List<ThermalMaterialDefinition> definitions = new ArrayList<>(liquids.materials().size());
		for (int index = 0; index < liquids.materials().size(); index++) {
			definitions.add(new ThermalMaterialDefinition("realwater:thermal/persistence_" + index,
					ThermalMaterialDefinition.CarrierKind.LIQUID, liquids.material(index).id(), 300.0, 0.0, 1.0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 4_200.0))));
		}
		definitions.add(new ThermalMaterialDefinition("realwater:thermal/persistence_solid",
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID, "realwater:solid/persistence",
				300.0, 50.0, 1.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(200.0, 500.0, 4_200.0))));
		return new ThermalRegistry(definitions);
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

	private static void assertMigratedWaterState(
			FlipParticleState pure, FlipMaterialParticleState migrated, List<FluidMaterial> palette
	) {
		FluidMaterial water = palette.getFirst();
		require(migrated.materials().equals(palette), "v1 migration did not create the frozen M3 palette");
		require(Arrays.equals(floatBits(pure.copyPositionVolume()), floatBits(migrated.copyPositionVolume()))
				&& Arrays.equals(floatBits(pure.copyParticleVelocities()), floatBits(migrated.copyParticleVelocities()))
				&& Arrays.equals(pure.copySolidMask(), migrated.copySolidMask()), "v1 migration changed pure-water physical state");
		float[] positions = pure.copyPositionVolume();
		double[] mass = migrated.copyParticleMass();
		int[] materials = migrated.copyMaterialPaletteIndex();
		double totalMass = 0.0;
		for (int marker = 0; marker < pure.particleCount(); marker++) {
			double expectedMass = positions[marker * 4 + 3] * water.referenceDensity();
			require(Double.doubleToRawLongBits(mass[marker]) == Double.doubleToRawLongBits(expectedMass) && materials[marker] == 0,
					"v1 migration changed marker mass or default material assignment");
			totalMass += expectedMass;
		}
		require(Double.doubleToRawLongBits(totalMass) == Double.doubleToRawLongBits(migrated.totalMass()),
				"v1 migration total mass ledger changed");
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
