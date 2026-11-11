package mmm.simulation;

import mmm.application.RegionCatalog;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Minecraft descriptor adapter over the loader-neutral region catalog. */
public final class FluidRegionManager {
	private final RegionCatalog.Limits limits;
	private final RegionCatalog<FluidRegion> catalog;
	private mmm.fluid.FluidSolverBackend solverBackend;
	private java.util.function.BiConsumer<FluidRegion, Boolean> committedObserver = (region, present) -> { };
	private java.util.function.Consumer<FluidRegion> removalObserver = region -> { };
	private java.util.function.Consumer<FluidRegion> geometryObserver = region -> { };
	void observeGeometry(java.util.function.Consumer<FluidRegion> observer) { geometryObserver = Objects.requireNonNull(observer); }
	void observeRemoval(java.util.function.Consumer<FluidRegion> observer) { removalObserver = Objects.requireNonNull(observer); }

	public FluidRegionManager(RegionCatalog.Limits limits) {
		this.limits = Objects.requireNonNull(limits, "limits");
		this.catalog = new RegionCatalog<>(limits);
	}

	public RegionCatalog.Limits limits() { return limits; }
	public List<FluidRegion> regions() { return catalog.values(); }
	public FluidRegion selected() { return catalog.selected(); }
	FluidRegion region(UUID id) { return catalog.value(id); }

	public RegionCatalog.BudgetUsage usage() { return catalog.usage(); }

	public RegionCatalog.AdmissionResult add(FluidRegion candidate) {
		return admitted(candidate, catalog.add(candidate, descriptor(candidate)));
	}

	public RegionCatalog.AdmissionResult addAndSelect(FluidRegion candidate) {
		return admitted(candidate, catalog.addAndSelect(candidate, descriptor(candidate)));
	}

	public RegionCatalog.AdmissionResult previewAdd(FluidRegion candidate) {
		return catalog.previewAdd(descriptor(candidate));
	}

	public RegionCatalog.AdmissionResult registerDiscovered(FluidRegion candidate) {
		return admitted(candidate, catalog.registerDiscovered(candidate, descriptor(candidate)));
	}

	public RegionCatalog.AdmissionResult runtimeAdmission(FluidRegion candidate) {
		return catalog.runtimeAdmission(candidate, descriptor(candidate));
	}

	public RegionCatalog.AdmissionResult previewAdd(long requiredCells) {
		return catalog.previewAdd(requiredCells);
	}

	RegionCatalog.AdmissionResult previewAdd(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			mmm.simulation.controller.FluidRegionFootprint footprint, mmm.spatial.SpatialGrid grid) {
		var min = footprint.minimumInterior();
		var max = footprint.maximumInterior();
		var blocks = footprint.interiorBlocks().stream().map(net.minecraft.core.BlockPos::of)
				.map(p -> new RegionCatalog.Descriptor.Block(p.getX(), p.getY(), p.getZ()))
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		return catalog.previewAdd(new RegionCatalog.Descriptor(UUID.randomUUID(), dimension.identifier().toString(),
				min.getX(), min.getY(), min.getZ(), max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0,
				grid.cellCount(), blocks));
	}

	RegionCatalog.AdmissionResult previewResize(FluidRegion previous,
			mmm.simulation.controller.FluidRegionFootprint footprint, mmm.spatial.SpatialGrid grid) {
		var min = footprint.minimumInterior();
		var max = footprint.maximumInterior();
		var blocks = footprint.interiorBlocks().stream().map(net.minecraft.core.BlockPos::of)
				.filter(footprint::containsInterior)
				.map(p -> new RegionCatalog.Descriptor.Block(p.getX(), p.getY(), p.getZ()))
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		var descriptor = new RegionCatalog.Descriptor(previous.id(), previous.dimension().identifier().toString(),
				min.getX(), min.getY(), min.getZ(), max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0,
				grid.cellCount(), blocks);
		return catalog.previewUpdate(previous, descriptor);
	}

	RegionCatalog.AdmissionResult adoptResize(FluidRegion previous, FluidRegion candidate) {
		var result = catalog.update(previous, candidate, descriptor(candidate));
		if (result.accepted()) {
			bind(candidate);
			geometryObserver.accept(candidate);
			try { previous.releaseAfterReplacement(); }
			catch (RuntimeException failure) {
				mmm.MmmMod.LOGGER.error("Failed to release replaced region {}", previous.id(), failure);
			}
		}
		return result;
	}

	public RegionCatalog.AdmissionResult replaceSelected(FluidRegion candidate) {
		FluidRegion previous = catalog.selected();
		var result = catalog.replaceSelected(candidate, descriptor(candidate));
		if (result.accepted() && previous != null && previous != candidate) removed(previous);
		return admitted(candidate, result);
	}

    RegionCatalog.AdmissionResult previewMerge(List<FluidRegion> sources,FluidRegion candidate) {
        return catalog.previewMerge(sources,descriptor(candidate));
    }

    RegionCatalog.AdmissionResult adoptMerge(List<FluidRegion> sources,FluidRegion candidate) {
        var result=catalog.merge(sources,candidate,descriptor(candidate));
        if(result.accepted()) {
            for(var source:sources) {
                if(!source.id().equals(candidate.id()))removed(source);
                try { source.releaseAfterReplacement(); }
                catch (RuntimeException failure) {
                    mmm.MmmMod.LOGGER.error("Failed to release merged region {}", source.id(), failure);
                }
            }
            bind(candidate);
            geometryObserver.accept(candidate);
        }
        return result;
    }

	public RegionCatalog.AdmissionResult previewReplacement(long requiredCells) {
		return catalog.previewReplacement(requiredCells);
	}
	RegionCatalog.AdmissionResult previewReplacement(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			net.minecraft.core.BlockPos origin, int sizeX, int sizeY, int sizeZ, long requiredCells) {
		return catalog.previewReplacement(new RegionCatalog.Descriptor(UUID.randomUUID(), dimension.identifier().toString(),
				origin.getX(), origin.getY(), origin.getZ(), origin.getX() + sizeX, origin.getY() + sizeY,
				origin.getZ() + sizeZ, requiredCells));
	}

	public boolean select(UUID id) { return catalog.select(id); }
	public FluidRegion remove(UUID id) {
		var region = catalog.remove(id);
		if (region != null) removed(region);
		return region;
	}
	public void clear() {
		var previous = catalog.values();
		catalog.clear();
		for (var region : previous) removed(region);
	}

	void observeCommittedChanges(java.util.function.BiConsumer<FluidRegion, Boolean> observer) {
		committedObserver = Objects.requireNonNull(observer);
		for (var region : catalog.values()) bind(region);
	}

	private RegionCatalog.AdmissionResult admitted(FluidRegion region, RegionCatalog.AdmissionResult result) {
		if (result.accepted()) { bind(region); geometryObserver.accept(region); }
		return result;
	}

	mmm.fluid.FluidSolverBackend solverBackend() { return solverBackend; }

	void solverBackend(mmm.fluid.FluidSolverBackend backend) {
		solverBackend = Objects.requireNonNull(backend);
		for (var region : catalog.values()) region.solverBackend(backend);
	}

	private void bind(FluidRegion region) {
		if (solverBackend != null) {
			region.solverBackend(solverBackend);
			if (region.hasSelectedVolume() && region.solverLoaded()) try { region.ensureDeviceResident(); }
			catch (RuntimeException failure) { region.blockNumerical("Vulkan 区域初始化失败：" + failure.getMessage()); }
		}
		region.committedObservationListener(() -> committedObserver.accept(region, true));
		committedObserver.accept(region, true);
	}

	private void removed(FluidRegion region) {
		geometryObserver.accept(region);
		removalObserver.accept(region);
		region.committedObservationListener(() -> { });
		committedObserver.accept(region, false);
	}


	private static RegionCatalog.Descriptor descriptor(FluidRegion region) {
		Objects.requireNonNull(region, "region");
		double cellSize = region.solverConfig().cellSize();
		return new RegionCatalog.Descriptor(region.id(), region.dimension().identifier().toString(),
				region.origin().getX(), region.origin().getY(), region.origin().getZ(),
				region.origin().getX() + region.sizeX() * cellSize,
				region.origin().getY() + region.sizeY() * cellSize,
				region.origin().getZ() + region.sizeZ() * cellSize,
				region.reservedCellCapacity(), region.footprint() == null ? null : region.footprint().interiorBlocks().stream()
                        .map(net.minecraft.core.BlockPos::of)
                        .filter(region.footprint()::containsInterior)
                        .map(block -> new RegionCatalog.Descriptor.Block(block.getX(), block.getY(), block.getZ()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
	}

}
