package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.application.RegionCatalog;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Minecraft descriptor adapter over the loader-neutral region catalog. */
public final class FluidRegionManager {
	private final Limits limits;
	private final RegionCatalog<FluidRegion> catalog;

	public FluidRegionManager(Limits limits) {
		this.limits = Objects.requireNonNull(limits, "limits");
		this.catalog = new RegionCatalog<>(limits.catalogLimits());
	}

	public Limits limits() { return limits; }
	public List<FluidRegion> regions() { return catalog.values(); }
	public FluidRegion selected() { return catalog.selected(); }
	FluidRegion region(UUID id) { return catalog.value(id); }

	public BudgetUsage usage() {
		RegionCatalog.BudgetUsage usage = catalog.usage();
		return new BudgetUsage(usage.regionCount(), usage.reservedCells(), usage.reservedMarkers());
	}

	public AdmissionResult add(FluidRegion candidate) {
		return result(catalog.add(candidate, descriptor(candidate)));
	}

	public AdmissionResult addAndSelect(FluidRegion candidate) {
		return result(catalog.addAndSelect(candidate, descriptor(candidate)));
	}

	public AdmissionResult previewAdd(FluidRegion candidate) {
		return result(catalog.previewAdd(descriptor(candidate)));
	}

	public AdmissionResult registerDiscovered(FluidRegion candidate) {
		return result(catalog.registerDiscovered(candidate, descriptor(candidate)));
	}

	public AdmissionResult runtimeAdmission(FluidRegion candidate) {
		return result(catalog.runtimeAdmission(candidate, descriptor(candidate)));
	}

	public AdmissionResult previewAdd(long requiredCells, long requiredMarkers) {
		return result(catalog.previewAdd(requiredCells, requiredMarkers));
	}

	public AdmissionResult replaceSelected(FluidRegion candidate) {
		return result(catalog.replaceSelected(candidate, descriptor(candidate)));
	}

	public AdmissionResult previewReplacement(long requiredCells, long requiredMarkers) {
		return result(catalog.previewReplacement(requiredCells, requiredMarkers));
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

	private static AdmissionResult result(RegionCatalog.AdmissionResult result) {
		return result.accepted() ? AdmissionResult.ACCEPTED
				: new AdmissionResult(false, result.reason(), result.failure());
	}

	public record Limits(
			int maximumRegions, long maximumCellsPerRegion, long maximumMarkersPerRegion,
			long maximumTotalCells, long maximumTotalMarkers
	) {
		public Limits {
			new RegionCatalog.Limits(maximumRegions, maximumCellsPerRegion, maximumMarkersPerRegion,
					maximumTotalCells, maximumTotalMarkers);
		}

		private RegionCatalog.Limits catalogLimits() {
			return new RegionCatalog.Limits(maximumRegions, maximumCellsPerRegion, maximumMarkersPerRegion,
					maximumTotalCells, maximumTotalMarkers);
		}
	}

	public record BudgetUsage(int regionCount, long reservedCells, long reservedMarkers) { }

	public record AdmissionResult(boolean accepted, RegionAvailabilityReason reason, String failure) {
		private static final AdmissionResult ACCEPTED = new AdmissionResult(true, null, null);
		public AdmissionResult {
			if (accepted ? reason != null || failure != null : reason == null || failure == null) {
				throw new IllegalArgumentException("Region admission result is inconsistent");
			}
		}
	}
}
