package mmm.simulation.persistence;

import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.air.GasRegistry;
import mmm.granular.GranularRegistry;
import mmm.model.region.RegionManifest;
import mmm.model.region.RegionSectionState;
import mmm.model.region.RegionStateLayout;
import mmm.thermal.ThermalRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
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
        writeTemporary(state, temporary, injector, true);
    }

    void writeTemporary(RegionState state, Path temporary, RegionGenerationStore.FaultInjector injector,
                        boolean verify) throws IOException {
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
        if (verify) {
            RegionState verified = readSlot(temporary);
            verified.validate();
            validateReconstruction(verified);
        }
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
		RegionCheckpointValidator.validateManifest(manifest);
		return manifest;
	}

	RegionState readSlot(Path slot) throws IOException {
		if (!Files.isDirectory(slot)) throw new IOException("Missing committed region slot " + slot);
		RegionManifest manifest = codec.decodeManifest(readNbt(slot.resolve(MANIFEST)));
		RegionCheckpointValidator.validateManifest(manifest);
		validateSectionFiles(slot,manifest);
		var sections=new ArrayList<RegionSectionState>(manifest.sections().size());
		for(var descriptor:manifest.sections()) sections.add(readSection(slot,manifest,descriptor.key()));
		return new RegionState(manifest,sections);
	}

	RegionSectionState readSection(Path slot,RegionManifest manifest,RegionSectionState.SectionKey key) throws IOException {
		if(manifest.sections().stream().noneMatch(descriptor->descriptor.key().equals(key)))
			throw new IOException("Section is absent from the bound manifest: "+key);
		var section=codec.decodeSection(readNbt(slot.resolve(sectionFile(key))));
		if(!section.key().equals(key)) throw new IOException("Section file coordinate differs from requested key: "+key);
		new RegionCheckpointValidator(new mmm.model.region.RegionCheckpoint(manifest,List.of(section))).validateSection(section);
		return section;
	}

	void validateSectionFiles(Path slot,RegionManifest manifest) throws IOException {
		Set<String> expected=new HashSet<>(); expected.add(MANIFEST);
		for(var descriptor:manifest.sections()) expected.add(sectionFile(descriptor.key()));
		try(var files=Files.newDirectoryStream(slot)) {
			for(var file:files) if(!Files.isRegularFile(file) || !expected.remove(file.getFileName().toString()))
				throw new IOException("Unexpected committed section content: "+file);
		}
		if(!expected.isEmpty()) throw new IOException("Missing committed section files: "+expected);
	}

	void validateReconstruction(RegionState state) throws IOException {
		RegionCheckpointRestorer restorer = new RegionCheckpointRestorer(state.checkpoint());
		if (state.manifest().layout() == RegionStateLayout.PURE) {
			restorer.reconstructLiquid(null);
		} else if (state.manifest().layout() == RegionStateLayout.MATERIAL) {
			restorer.reconstructLiquid(null);
		} else if (state.manifest().layout() == RegionStateLayout.CHEMICAL) {
			if (chemicalRegistry == null) {
				throw new IOException("Chemical region verification requires the authoritative registry");
			}
			restorer.reconstructLiquid(chemicalRegistry);
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
			restorer.reconstructLiquid(chemicalRegistry);
		} else {
			restorer.reconstructLiquid(null);
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
			restorer.reconstructLiquid(chemicalRegistry);
		} else {
			restorer.reconstructLiquid(null);
		}
		var owners=restorer.reconstructThermalOwners(thermalRegistry);
		var model=new mmm.thermal.GasThermalModel(thermalRegistry,gasRegistry);var spatial=restorer.hasSpatialGas()?restorer.reconstructSpatialGas(gasRegistry,chemicalRegistry):null;
		if(spatial==null)throw new IOException("Thermal checkpoint requires local gas inventory");
		for(int i=0;i<spatial.cellCount();i++)model.requireInventoryEnergy(spatial.cells().get(i),spatial.energyAt(i));
		var liquid=restorer.reconstructLiquid(chemicalRegistry);var liquidU=mmm.units.SignedDyadic.ZERO;
		for(var stock:liquid.stocks())liquidU=liquidU.add(mmm.units.SignedDyadic.of(stock.internalEnergy()));
		owners.requireClosed(liquidU.add(mmm.units.SignedDyadic.sum(spatial.internalEnergy())),liquid.cellCount());
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
