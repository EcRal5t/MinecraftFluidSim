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
		var wall = sharedWall(candidate, null);
		return wall == null ? admitted(candidate, catalog.add(candidate, descriptor(candidate))) : wall;
	}

	public RegionCatalog.AdmissionResult addAndSelect(FluidRegion candidate) {
		var wall = sharedWall(candidate, null);
		return wall == null ? admitted(candidate, catalog.addAndSelect(candidate, descriptor(candidate))) : wall;
	}

	public RegionCatalog.AdmissionResult previewAdd(FluidRegion candidate) {
		var wall = sharedWall(candidate, null);
		return wall == null ? catalog.previewAdd(descriptor(candidate)) : wall;
	}

	public RegionCatalog.AdmissionResult registerDiscovered(FluidRegion candidate) {
		return admitted(candidate, catalog.registerDiscovered(candidate, descriptor(candidate)));
	}

	public RegionCatalog.AdmissionResult runtimeAdmission(FluidRegion candidate) {
		var wall = sharedWall(candidate, candidate);
		return wall == null ? catalog.runtimeAdmission(candidate, descriptor(candidate)) : wall;
	}

	public RegionCatalog.AdmissionResult previewAdd(long requiredCells) {
		return catalog.previewAdd(requiredCells);
	}

	public RegionCatalog.AdmissionResult replaceSelected(FluidRegion candidate) {
		FluidRegion previous = catalog.selected();
		var wall = sharedWall(candidate, previous);
		if (wall != null) return wall;
		var result = catalog.replaceSelected(candidate, descriptor(candidate));
		if (result.accepted() && previous != null && previous != candidate) removed(previous);
		return admitted(candidate, result);
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
		if (result.accepted()) bind(region);
		return result;
	}

	void solverBackend(mmm.fluid.FluidSolverBackend backend) {
		solverBackend = Objects.requireNonNull(backend);
		for (var region : catalog.values()) region.solverBackend(backend);
	}

	private void bind(FluidRegion region) {
		if (solverBackend != null) {
			region.solverBackend(solverBackend);
			if (region.controllerManaged() && region.solverLoaded()) try { region.ensureDeviceResident(); }
			catch (RuntimeException failure) { region.blockNumerical("Vulkan 区域初始化失败：" + failure.getMessage()); }
		}
		region.committedObservationListener(() -> committedObserver.accept(region, true));
		committedObserver.accept(region, true);
	}

	private void removed(FluidRegion region) {
		removalObserver.accept(region);
		region.committedObservationListener(() -> { });
		committedObserver.accept(region, false);
	}


	private RegionCatalog.AdmissionResult sharedWall(FluidRegion candidate, FluidRegion ignored) {
		var proposed = possibleWallPositions(candidate);
		for (var other : catalog.values()) {
			if (other == ignored || other == candidate || !other.dimension().equals(candidate.dimension())) continue;
			var walls = possibleWallPositions(other);
			for (long position : proposed) if (walls.contains(position))
				return new RegionCatalog.AdmissionResult(false, mmm.api.RegionAvailabilityReason.OVERLAP,
						"两个区域共用墙体：" + net.minecraft.core.BlockPos.of(position).toShortString());
		}
		return null;
	}


	private static java.util.Set<Long> possibleWallPositions(FluidRegion region) {
		if (region.footprint() != null) return region.footprint().boundaryBlocks();
		double h = region.solverConfig().cellSize();
		int sx=(int)Math.ceil(region.sizeX()*h), sy=(int)Math.ceil(region.sizeY()*h), sz=(int)Math.ceil(region.sizeZ()*h);
		var positions = new java.util.HashSet<Long>();
		var origin = region.origin();
		for(int z=0;z<sz;z++)for(int y=0;y<sy;y++) { positions.add(origin.offset(-1,y,z).asLong()); positions.add(origin.offset(sx,y,z).asLong()); }
		for(int z=0;z<sz;z++)for(int x=0;x<sx;x++) { positions.add(origin.offset(x,-1,z).asLong()); positions.add(origin.offset(x,sy,z).asLong()); }
		for(int y=0;y<sy;y++)for(int x=0;x<sx;x++) { positions.add(origin.offset(x,y,-1).asLong()); positions.add(origin.offset(x,y,sz).asLong()); }
		return positions;
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
