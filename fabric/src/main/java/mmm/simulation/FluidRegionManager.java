package mmm.simulation;

import mmm.application.RegionCatalog;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Minecraft descriptor adapter over the loader-neutral region catalog. */
public final class FluidRegionManager {
	private final RegionCatalog.Limits limits;
	private final RegionCatalog<FluidRegion> catalog;
	private java.util.function.BiConsumer<FluidRegion, Boolean> committedObserver = (region, present) -> { };

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

	public RegionCatalog.AdmissionResult replaceSelected(FluidRegion candidate) {
		FluidRegion previous = catalog.selected();
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

	private void bind(FluidRegion region) {
		region.committedObservationListener(() -> committedObserver.accept(region, true));
		committedObserver.accept(region, true);
	}

	private void removed(FluidRegion region) {
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
				region.reservedCellCapacity());
	}

}
