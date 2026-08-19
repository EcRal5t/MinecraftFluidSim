package com.example.simulation;

import com.example.fluid.BuiltinFluidMaterials;
import com.example.simulation.persistence.RegionManifest;
import com.example.simulation.persistence.RegionState;
import com.example.simulation.persistence.RegionStateFiles;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Coordinates persistent controller state without coupling it to world takeover transactions. */
final class FluidRegionPersistence {
	private final RegionStateFiles files;
	private final boolean materialsEnabled;

	FluidRegionPersistence(Path worldRoot, boolean materialsEnabled) {
		files = new RegionStateFiles(worldRoot);
		this.materialsEnabled = materialsEnabled;
	}

	List<String> loadMetadata(FluidRegionManager manager) {
		List<String> issues = new ArrayList<>();
		RegionStateFiles.Discovery discovery;
		try {
			discovery = files.discover();
		} catch (IOException exception) {
			return List.of("无法扫描 region-state：" + exception.getMessage());
		}
		for (var issue : discovery.issues().entrySet()) issues.add(issue.getKey().getFileName() + " 已锁定：" + issue.getValue());
		for (RegionManifest manifest : discovery.manifests()) {
			try {
				if (manifest.formatVersion() == 2 && !materialsEnabled) {
					throw new IOException("region-state v2 requires the materials feature gate");
				}
				validateControllerGeometry(manifest);
				Identifier dimensionId = Identifier.tryParse(manifest.dimensionId());
				if (dimensionId == null) throw new IOException("invalid dimension");
				ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
				FluidRegion region = FluidRegion.unloaded(manifest.regionId(), dimension, manifest.origin(),
						manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(),
						manifest.reservedMarkerCapacity(), manifest.footprint(), manifest.networkGeneration(),
						manifest.checkpointGeneration(), manifest.totalMarkerCount(), (float) manifest.totalVolume(), manifest.runtime());
				FluidRegionManager.AdmissionResult admission = manager.add(region);
				if (!admission.accepted()) issues.add(manifest.regionId() + " 未载入：" + admission.failure());
			} catch (IOException | IllegalArgumentException exception) {
				issues.add(manifest.regionId() + " 已锁定：" + exception.getMessage());
			}
		}
		return List.copyOf(issues);
	}

	long checkpoint(FluidRegion region) throws IOException {
		if (!region.controllerManaged() || !region.solverLoaded()) throw new IOException("Region has no persistent live solver");
		long generation = Math.addExact(region.checkpointGeneration(), 1L);
		long published = files.publish(region.domain().hasMaterialState()
				? RegionState.captureMaterial(region, generation)
				: RegionState.capture(region, generation));
		if (!region.recordPublishedCheckpoint(published)) throw new IOException("Published checkpoint was not accepted by the live region");
		return published;
	}

	void checkpointAndUnload(FluidRegion region) throws IOException {
		long generation = checkpoint(region);
		if (!region.releaseSolverAfterCheckpoint(generation)) throw new IOException("Published checkpoint did not release region solver");
	}

	void restore(FluidRegion region) throws IOException {
		if (region.solverLoaded() || region.lifecycle().state() != FluidRegionLifecycle.State.UNLOADED) return;
		RegionState state = files.read(region.id());
		RegionManifest manifest = state.manifest();
		if (manifest.checkpointGeneration() != region.checkpointGeneration() || manifest.networkGeneration() != region.generation()
				|| !manifest.dimensionId().equals(region.dimension().identifier().toString())
				|| !manifest.origin().equals(region.origin()) || manifest.sizeX() != region.sizeX()
				|| manifest.sizeY() != region.sizeY() || manifest.sizeZ() != region.sizeZ()
				|| manifest.reservedMarkerCapacity() != region.reservedMarkerCapacity()) {
			throw new IOException("Committed checkpoint no longer matches admitted region metadata");
		}
		if (materialsEnabled && manifest.formatVersion() == 1) {
			long migratedGeneration = Math.addExact(manifest.checkpointGeneration(), 1L);
			state = RegionState.migrateV1(state, migratedGeneration, BuiltinFluidMaterials.M3_PALETTE);
			files.publish(state);
			if (!region.adoptMigratedCheckpoint(manifest.checkpointGeneration(), migratedGeneration)) {
				throw new IOException("Published v2 migration was not accepted by unloaded region metadata");
			}
			manifest = state.manifest();
		}
		if (manifest.formatVersion() == 2) {
			if (!materialsEnabled) throw new IOException("Material checkpoint cannot restore while the feature gate is disabled");
			region.restoreMaterialSolver(state.reconstructMaterial(), manifest.checkpointGeneration());
		} else {
			region.restoreSolver(state.reconstruct(), manifest.checkpointGeneration());
		}
	}

	void delete(FluidRegion region) throws IOException {
		if (region.controllerManaged() && region.checkpointGeneration() > 0L) files.quarantine(region.id());
	}

	private static void validateControllerGeometry(RegionManifest manifest) throws IOException {
		int cellsPerBlock = Math.round(1.0F / manifest.solverConfig().cellSize());
		if (cellsPerBlock <= 0 || Math.abs(cellsPerBlock * manifest.solverConfig().cellSize() - 1.0F) > 1.0E-6F
				|| manifest.sizeX() != Math.multiplyExact(manifest.footprint().blockSizeX(), cellsPerBlock)
				|| manifest.sizeY() != Math.multiplyExact(manifest.footprint().blockSizeY(), cellsPerBlock)
				|| manifest.sizeZ() != Math.multiplyExact(manifest.footprint().blockSizeZ(), cellsPerBlock)) {
			throw new IOException("Persisted solver grid does not match its visible controller footprint");
		}
	}
}
