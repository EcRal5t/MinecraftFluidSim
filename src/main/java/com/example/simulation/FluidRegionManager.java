package com.example.simulation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-thread registry and admission budget for in-memory fluid regions.
 * It does not persist regions, change worlds, step solvers, or infer water from capacity counters.
 */
public final class FluidRegionManager {
	private final Limits limits;
	private final Map<UUID, FluidRegion> regions = new LinkedHashMap<>();
	private UUID selectedId;

	public FluidRegionManager(Limits limits) {
		this.limits = Objects.requireNonNull(limits, "limits");
	}

	public Limits limits() {
		return limits;
	}

	public List<FluidRegion> regions() {
		return List.copyOf(regions.values());
	}

	public FluidRegion selected() {
		return selectedId == null ? null : regions.get(selectedId);
	}

	public BudgetUsage usage() {
		long cells = 0L;
		long markers = 0L;
		for (FluidRegion region : regions.values()) {
			cells = Math.addExact(cells, region.reservedCellCapacity());
			markers = Math.addExact(markers, region.reservedMarkerCapacity());
		}
		return new BudgetUsage(regions.size(), cells, markers);
	}

	public AdmissionResult add(FluidRegion candidate) {
		Objects.requireNonNull(candidate, "candidate");
		if (regions.containsKey(candidate.id())) {
			return AdmissionResult.rejected("region id already exists: " + candidate.id());
		}
		BudgetUsage current = usage();
		AdmissionResult admission = evaluate(candidate, current.regionCount() + 1, current.reservedCells(), current.reservedMarkers());
		if (!admission.accepted()) return admission;
		regions.put(candidate.id(), candidate);
		if (selectedId == null) selectedId = candidate.id();
		return admission;
	}

	/** Atomically replaces the selected region after evaluating the candidate against the remaining regions. */
	public AdmissionResult replaceSelected(FluidRegion candidate) {
		Objects.requireNonNull(candidate, "candidate");
		FluidRegion selected = selected();
		FluidRegion duplicate = regions.get(candidate.id());
		if (duplicate != null && duplicate != selected) {
			return AdmissionResult.rejected("region id already exists: " + candidate.id());
		}

		BudgetUsage current = usage();
		int regionCount = selected == null ? current.regionCount() + 1 : current.regionCount();
		long baseCells = current.reservedCells() - (selected == null ? 0L : selected.reservedCellCapacity());
		long baseMarkers = current.reservedMarkers() - (selected == null ? 0L : selected.reservedMarkerCapacity());
		AdmissionResult admission = evaluate(candidate, regionCount, baseCells, baseMarkers);
		if (!admission.accepted()) return admission;

		if (selected != null) regions.remove(selected.id());
		regions.put(candidate.id(), candidate);
		selectedId = candidate.id();
		return admission;
	}

	/** Evaluates a replacement before allocating its solver arrays; no registry or selection state is changed. */
	public AdmissionResult previewReplacement(long requiredCells, long requiredMarkers) {
		if (requiredCells <= 0L || requiredMarkers <= 0L) {
			throw new IllegalArgumentException("Fluid region capacity requirements must be positive");
		}
		FluidRegion selected = selected();
		BudgetUsage current = usage();
		int regionCount = selected == null ? current.regionCount() + 1 : current.regionCount();
		long baseCells = current.reservedCells() - (selected == null ? 0L : selected.reservedCellCapacity());
		long baseMarkers = current.reservedMarkers() - (selected == null ? 0L : selected.reservedMarkerCapacity());
		return evaluate(requiredCells, requiredMarkers, regionCount, baseCells, baseMarkers);
	}

	public boolean select(UUID id) {
		if (!regions.containsKey(id)) return false;
		selectedId = id;
		return true;
	}

	public FluidRegion remove(UUID id) {
		FluidRegion removed = regions.remove(id);
		if (removed != null && id.equals(selectedId)) {
			selectedId = regions.isEmpty() ? null : regions.keySet().iterator().next();
		}
		return removed;
	}

	public void clear() {
		regions.clear();
		selectedId = null;
	}

	private AdmissionResult evaluate(FluidRegion candidate, int regionCount, long baseCells, long baseMarkers) {
		long requiredCells = candidate.reservedCellCapacity();
		long requiredMarkers = candidate.reservedMarkerCapacity();
		return evaluate(requiredCells, requiredMarkers, regionCount, baseCells, baseMarkers);
	}

	private AdmissionResult evaluate(long requiredCells, long requiredMarkers, int regionCount, long baseCells, long baseMarkers) {
		long remainingCells = limits.maximumTotalCells() - baseCells;
		long remainingMarkers = limits.maximumTotalMarkers() - baseMarkers;
		String requirement = "requires " + requiredCells + " cells/" + requiredMarkers + " markers; per-region limit "
				+ limits.maximumCellsPerRegion() + "/" + limits.maximumMarkersPerRegion() + "; remaining "
				+ Math.max(0L, remainingCells) + "/" + Math.max(0L, remainingMarkers);
		if (regionCount > limits.maximumRegions()) {
			return AdmissionResult.rejected("region-count limit " + limits.maximumRegions() + " exceeded; " + requirement);
		}
		if (requiredCells > limits.maximumCellsPerRegion() || requiredMarkers > limits.maximumMarkersPerRegion()) {
			return AdmissionResult.rejected("per-region budget exceeded; " + requirement);
		}
		if (requiredCells > remainingCells || requiredMarkers > remainingMarkers) {
			return AdmissionResult.rejected("server fluid budget exceeded; " + requirement);
		}
		return AdmissionResult.ACCEPTED;
	}

	public record Limits(
			int maximumRegions,
			long maximumCellsPerRegion,
			long maximumMarkersPerRegion,
			long maximumTotalCells,
			long maximumTotalMarkers
	) {
		public Limits {
			if (maximumRegions <= 0 || maximumCellsPerRegion <= 0L || maximumMarkersPerRegion <= 0L
					|| maximumTotalCells <= 0L || maximumTotalMarkers <= 0L) {
				throw new IllegalArgumentException("Fluid region limits must be positive");
			}
			if (maximumCellsPerRegion > maximumTotalCells || maximumMarkersPerRegion > maximumTotalMarkers) {
				throw new IllegalArgumentException("Per-region limits cannot exceed total limits");
			}
		}
	}

	public record BudgetUsage(int regionCount, long reservedCells, long reservedMarkers) { }

	public record AdmissionResult(boolean accepted, String failure) {
		private static final AdmissionResult ACCEPTED = new AdmissionResult(true, null);

		private static AdmissionResult rejected(String failure) {
			return new AdmissionResult(false, Objects.requireNonNull(failure, "failure"));
		}
	}
}
