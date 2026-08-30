package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.model.region.RegionManifest;
import cc.ecisr.mmm.model.region.RegionSectionState;
import cc.ecisr.mmm.model.region.RegionStateLayout;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** ACTIVE slot serialization, strict readback and immutable solver reconstruction validation. */
final class ActiveGenerationStore {
	private static final String MANIFEST = "manifest.nbt";
	private static final long MAX_NBT_BYTES = 64L * 1024L * 1024L;

	private final RegionStateCodec codec = new RegionStateCodec();
	private final ChemicalRegistry chemicalRegistry;
	private final GranularRegistry granularRegistry;
	private final ThermalRegistry thermalRegistry;
	private final GasRegistry gasRegistry;

	ActiveGenerationStore(
			ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry,
			ThermalRegistry thermalRegistry,
			GasRegistry gasRegistry
	) {
		this.chemicalRegistry = chemicalRegistry;
		this.granularRegistry = granularRegistry;
		this.thermalRegistry = thermalRegistry;
		this.gasRegistry = gasRegistry;
	}

	void writeTemporary(
			RegionState state,
			Path temporary,
			RegionGenerationStore.FaultInjector injector
	) throws IOException {
		Files.createDirectory(temporary);
		ArrayList<RegionSectionState> sections = new ArrayList<>(state.sections());
		sections.sort(Comparator.comparing(RegionSectionState::key));
		for (RegionSectionState section : sections) {
			Path path = temporary.resolve(sectionFile(section.key()));
			NbtIo.writeCompressed(codec.encodeSection(section), path);
			DurableFileOperations.forceFile(path);
			injector.check(RegionGenerationStore.FaultPoint.AFTER_SECTION_WRITE, path);
		}
		Path manifestPath = temporary.resolve(MANIFEST);
		NbtIo.writeCompressed(codec.encodeManifest(state.manifest()), manifestPath);
		DurableFileOperations.forceFile(manifestPath);
		DurableFileOperations.forceDirectory(temporary);
		injector.check(RegionGenerationStore.FaultPoint.AFTER_MANIFEST_WRITE, manifestPath);
		RegionState verified = readSlot(temporary);
		verified.validate();
		validateReconstruction(verified);
		injector.check(RegionGenerationStore.FaultPoint.AFTER_TEMP_VALIDATION, temporary);
	}

	RegionState readGeneration(Path region, RegionHeadStore.Head head) throws IOException {
		if (head.kind() != RegionHeadStore.HeadKind.ACTIVE) {
			throw new IOException("Region HEAD is a REMOVED tombstone");
		}
		RegionState state = readSlot(region.resolve(head.slot()));
		RegionHeadStore.validateBinding(head, state.manifest());
		state.validate();
		return state;
	}

	RegionManifest readManifest(Path region, RegionHeadStore.Head head) throws IOException {
		RegionManifest manifest = codec.decodeManifest(readNbt(region.resolve(head.slot()).resolve(MANIFEST)));
		RegionHeadStore.validateBinding(head, manifest);
		if (RegionStateIntegrity.manifestChecksum(manifest) != manifest.checksum()) {
			throw new IOException("Manifest checksum mismatch");
		}
		return manifest;
	}

	RegionState readSlot(Path slot) throws IOException {
		if (!Files.isDirectory(slot)) throw new IOException("Missing committed region slot " + slot);
		RegionManifest manifest = codec.decodeManifest(readNbt(slot.resolve(MANIFEST)));
		Map<RegionSectionState.SectionKey, RegionSectionState> sections = new HashMap<>();
		Set<String> expectedFiles = new HashSet<>();
		for (RegionManifest.SectionDescriptor descriptor : manifest.sections()) {
			String file = sectionFile(descriptor.key());
			if (!expectedFiles.add(file)) throw new IOException("Duplicate section filename in manifest");
			RegionSectionState section = codec.decodeSection(readNbt(slot.resolve(file)));
			if (sections.put(section.key(), section) != null) throw new IOException("Duplicate section coordinate");
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(slot)) {
			int actual = 0;
			for (Path file : files) {
				if (!Files.isRegularFile(file)) throw new IOException("Unexpected non-file content " + file);
				if (file.getFileName().toString().equals(MANIFEST)) continue;
				actual++;
				if (!expectedFiles.contains(file.getFileName().toString())) {
					throw new IOException("Unexpected section file " + file);
				}
			}
			if (actual != expectedFiles.size()) throw new IOException("Missing or extra section files");
		}
		return new RegionState(manifest, new ArrayList<>(sections.values()));
	}

	void validateReconstruction(RegionState state) throws IOException {
		RegionCheckpointRestorer restorer = new RegionCheckpointRestorer(state.checkpoint());
		if (state.manifest().layout() == RegionStateLayout.PURE) {
			restorer.reconstruct();
		} else if (state.manifest().layout() == RegionStateLayout.MATERIAL) {
			restorer.reconstructMaterial();
		} else if (state.manifest().layout() == RegionStateLayout.CHEMICAL) {
			if (chemicalRegistry == null) {
				throw new IOException("Chemical region verification requires the authoritative registry");
			}
			restorer.reconstructChemical(chemicalRegistry);
		} else if (state.manifest().layout() == RegionStateLayout.GRANULAR) {
			validateGranular(state, restorer, false);
		} else if (state.manifest().layout() == RegionStateLayout.THERMAL) {
			validateThermal(state, restorer);
		} else {
			throw new IOException("Unsupported region-state layout " + state.manifest().layout());
		}
	}

	private void validateGranular(
			RegionState state,
			RegionCheckpointRestorer restorer,
			boolean thermal
	) throws IOException {
		if (granularRegistry == null) {
			throw new IOException((thermal ? "Granular thermal region" : "Granular region")
					+ " verification requires the authoritative registry");
		}
		if (state.manifest().hasChemicalState()) {
			if (chemicalRegistry == null) {
				throw new IOException("Chemical " + (thermal ? "thermal" : "granular-region")
						+ " verification requires the authoritative chemical registry");
			}
			restorer.reconstructChemical(chemicalRegistry);
		} else {
			restorer.reconstructMaterial();
		}
		var granular = restorer.reconstructGranular(granularRegistry);
		if (granular.requiresChemistry() && chemicalRegistry == null) {
			throw new IOException("Reactive " + (thermal ? "thermal" : "granular-region")
					+ " verification requires the authoritative chemical registry");
		}
		try {
			granular.validateAgainst(granularRegistry, chemicalRegistry);
		} catch (IllegalArgumentException invalid) {
			throw new IOException((thermal ? "Thermal region granular" : "Granular region")
					+ " inventory/audit validation failed", invalid);
		}
	}

	private void validateThermal(RegionState state, RegionCheckpointRestorer restorer) throws IOException {
		if (thermalRegistry == null) {
			throw new IOException("Thermal region verification requires the authoritative thermal registry");
		}
		if (gasRegistry == null) {
			throw new IOException("Thermal region verification requires the authoritative gas registry");
		}
		boolean hasGranular = state.manifest().extensions().stream()
				.anyMatch(extension -> GranularRegionStateCodec.MANIFEST_EXTENSION.equals(extension.type()));
		if (hasGranular) {
			validateGranular(state, restorer, true);
		} else if (state.manifest().hasChemicalState()) {
			if (chemicalRegistry == null) {
				throw new IOException("Chemical thermal region verification requires the authoritative chemical registry");
			}
			restorer.reconstructChemical(chemicalRegistry);
		} else {
			restorer.reconstructMaterial();
		}
		restorer.reconstructThermal(thermalRegistry, gasRegistry, chemicalRegistry);
		restorer.reconstructFiniteThermalDevices(thermalRegistry);
	}

	private static String sectionFile(RegionSectionState.SectionKey key) {
		return "section_" + key.x() + "_" + key.y() + "_" + key.z() + ".nbt";
	}

	private static CompoundTag readNbt(Path path) throws IOException {
		if (!Files.isRegularFile(path)) throw new IOException("Missing region-state file " + path);
		return NbtIo.readCompressed(path, NbtAccounter.create(MAX_NBT_BYTES));
	}

}
