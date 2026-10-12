package mmm.simulation;

import mmm.application.RegionCatalog;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Minecraft descriptor adapter over the loader-neutral region catalog. */
public final class FluidRegionManager {
	private final RegionCatalog.Limits limits;
	private final RegionCatalog<FluidRegion> catalog;

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
		return catalog.add(candidate, descriptor(candidate));
	}

	public RegionCatalog.AdmissionResult addAndSelect(FluidRegion candidate) {
		return catalog.addAndSelect(candidate, descriptor(candidate));
	}

	public RegionCatalog.AdmissionResult previewAdd(FluidRegion candidate) {
		return catalog.previewAdd(descriptor(candidate));
	}

	public RegionCatalog.AdmissionResult registerDiscovered(FluidRegion candidate) {
		return catalog.registerDiscovered(candidate, descriptor(candidate));
	}

	public RegionCatalog.AdmissionResult runtimeAdmission(FluidRegion candidate) {
		return catalog.runtimeAdmission(candidate, descriptor(candidate));
	}

	public RegionCatalog.AdmissionResult previewAdd(long requiredCells, long requiredMarkers) {
		return catalog.previewAdd(requiredCells, requiredMarkers);
	}

	public RegionCatalog.AdmissionResult replaceSelected(FluidRegion candidate) {
		return catalog.replaceSelected(candidate, descriptor(candidate));
	}

	public RegionCatalog.AdmissionResult previewReplacement(long requiredCells, long requiredMarkers) {
		return catalog.previewReplacement(requiredCells, requiredMarkers);
	}

	public boolean select(UUID id) { return catalog.select(id); }
	public FluidRegion remove(UUID id) { return catalog.remove(id); }
	public void clear() { catalog.clear(); }

	private static RegionCatalog.Descriptor descriptor(FluidRegion region) {
		Objects.requireNonNull(region, "region");
		float cellSize = region.solverConfig().cellSize();
		return new RegionCatalog.Descriptor(region.id(), region.dimension().identifier().toString(),
				region.origin().getX(), region.origin().getY(), region.origin().getZ(),
				region.origin().getX() + region.sizeX() * cellSize,
				region.origin().getY() + region.sizeY() * cellSize,
				region.origin().getZ() + region.sizeZ() * cellSize,
				region.reservedCellCapacity(), region.reservedMarkerCapacity());
	}

}
