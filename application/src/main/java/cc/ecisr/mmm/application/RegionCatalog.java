package cc.ecisr.mmm.application;

import cc.ecisr.mmm.api.RegionAvailabilityReason;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Loader-neutral owner catalog, reservation budget, placement and selection policy. */
public final class RegionCatalog<T> {
	private final Limits limits;
	private final Map<UUID, Entry<T>> entries = new LinkedHashMap<>();
	private UUID selectedId;

	public RegionCatalog(Limits limits) { this.limits = Objects.requireNonNull(limits, "limits"); }
	public Limits limits() { return limits; }
	public List<T> values() { return entries.values().stream().map(Entry::value).toList(); }
	public T selected() { return selectedId == null ? null : entries.get(selectedId).value(); }
	public T value(UUID id) {
		Entry<T> entry = entries.get(Objects.requireNonNull(id, "id"));
		return entry == null ? null : entry.value();
	}

	public BudgetUsage usage() {
		long cells = 0L, markers = 0L;
		for (Entry<T> entry : entries.values()) {
			cells = Math.addExact(cells, entry.descriptor().reservedCells());
			markers = Math.addExact(markers, entry.descriptor().reservedMarkers());
		}
		return new BudgetUsage(entries.size(), cells, markers);
	}

	public AdmissionResult add(T value, Descriptor descriptor) {
		Objects.requireNonNull(value, "value");
		Objects.requireNonNull(descriptor, "descriptor");
		if (entries.containsKey(descriptor.regionId())) return duplicate(descriptor.regionId());
		BudgetUsage current = usage();
		AdmissionResult admission = evaluate(descriptor.reservedCells(), descriptor.reservedMarkers(),
				current.regionCount() + 1, current.reservedCells(), current.reservedMarkers());
		if (!admission.accepted()) return admission;
		AdmissionResult placement = evaluatePlacement(descriptor, null);
		if (!placement.accepted()) return placement;
		entries.put(descriptor.regionId(), new Entry<>(value, descriptor));
		if (selectedId == null) selectedId = descriptor.regionId();
		return admission;
	}

	public AdmissionResult addAndSelect(T value, Descriptor descriptor) {
		AdmissionResult admission = add(value, descriptor);
		if (admission.accepted()) selectedId = descriptor.regionId();
		return admission;
	}

	public AdmissionResult previewAdd(Descriptor descriptor) {
		Objects.requireNonNull(descriptor, "descriptor");
		if (entries.containsKey(descriptor.regionId())) return duplicate(descriptor.regionId());
		BudgetUsage current = usage();
		AdmissionResult admission = evaluate(descriptor.reservedCells(), descriptor.reservedMarkers(),
				current.regionCount() + 1, current.reservedCells(), current.reservedMarkers());
		return admission.accepted() ? evaluatePlacement(descriptor, null) : admission;
	}

	/** Retains committed metadata without applying current runtime budget or placement policy. */
	public AdmissionResult registerDiscovered(T value, Descriptor descriptor) {
		Objects.requireNonNull(value, "value");
		Objects.requireNonNull(descriptor, "descriptor");
		if (entries.containsKey(descriptor.regionId())) return duplicate(descriptor.regionId());
		entries.put(descriptor.regionId(), new Entry<>(value, descriptor));
		if (selectedId == null) selectedId = descriptor.regionId();
		return AdmissionResult.ACCEPTED;
	}

	public AdmissionResult runtimeAdmission(T value, Descriptor descriptor) {
		Objects.requireNonNull(value, "value");
		Objects.requireNonNull(descriptor, "descriptor");
		Entry<T> registered = entries.get(descriptor.regionId());
		if (registered == null || registered.value() != value || !registered.descriptor().equals(descriptor)) {
			return AdmissionResult.rejected(RegionAvailabilityReason.HEAD_INVALID,
					"region is not the registered committed metadata instance");
		}
		BudgetUsage current = usage();
		AdmissionResult admission = evaluate(descriptor.reservedCells(), descriptor.reservedMarkers(),
				current.regionCount(), current.reservedCells() - descriptor.reservedCells(),
				current.reservedMarkers() - descriptor.reservedMarkers());
		return admission.accepted() ? evaluatePlacement(descriptor, value) : admission;
	}

	public AdmissionResult previewAdd(long requiredCells, long requiredMarkers) {
		requireCapacity(requiredCells, requiredMarkers);
		BudgetUsage current = usage();
		return evaluate(requiredCells, requiredMarkers, current.regionCount() + 1,
				current.reservedCells(), current.reservedMarkers());
	}

	public AdmissionResult replaceSelected(T value, Descriptor descriptor) {
		Objects.requireNonNull(value, "value");
		Objects.requireNonNull(descriptor, "descriptor");
		Entry<T> selected = selectedId == null ? null : entries.get(selectedId);
		Entry<T> duplicate = entries.get(descriptor.regionId());
		if (duplicate != null && duplicate != selected) return duplicate(descriptor.regionId());
		BudgetUsage current = usage();
		int regionCount = selected == null ? current.regionCount() + 1 : current.regionCount();
		long baseCells = current.reservedCells()
				- (selected == null ? 0L : selected.descriptor().reservedCells());
		long baseMarkers = current.reservedMarkers()
				- (selected == null ? 0L : selected.descriptor().reservedMarkers());
		AdmissionResult admission = evaluate(descriptor.reservedCells(), descriptor.reservedMarkers(),
				regionCount, baseCells, baseMarkers);
		if (!admission.accepted()) return admission;
		AdmissionResult placement = evaluatePlacement(descriptor, selected == null ? null : selected.value());
		if (!placement.accepted()) return placement;
		if (selected != null) entries.remove(selected.descriptor().regionId());
		entries.put(descriptor.regionId(), new Entry<>(value, descriptor));
		selectedId = descriptor.regionId();
		return admission;
	}

	public AdmissionResult previewReplacement(long requiredCells, long requiredMarkers) {
		requireCapacity(requiredCells, requiredMarkers);
		Entry<T> selected = selectedId == null ? null : entries.get(selectedId);
		BudgetUsage current = usage();
		int regionCount = selected == null ? current.regionCount() + 1 : current.regionCount();
		long baseCells = current.reservedCells()
				- (selected == null ? 0L : selected.descriptor().reservedCells());
		long baseMarkers = current.reservedMarkers()
				- (selected == null ? 0L : selected.descriptor().reservedMarkers());
		return evaluate(requiredCells, requiredMarkers, regionCount, baseCells, baseMarkers);
	}

	public boolean select(UUID id) {
		if (!entries.containsKey(Objects.requireNonNull(id, "id"))) return false;
		selectedId = id;
		return true;
	}

	public T remove(UUID id) {
		Entry<T> removed = entries.remove(Objects.requireNonNull(id, "id"));
		if (removed != null && id.equals(selectedId)) {
			selectedId = entries.isEmpty() ? null : entries.keySet().iterator().next();
		}
		return removed == null ? null : removed.value();
	}

	public void clear() { entries.clear(); selectedId = null; }

	private AdmissionResult evaluatePlacement(Descriptor candidate, T ignored) {
		for (Entry<T> existing : entries.values()) {
			if (existing.value() == ignored) continue;
			if (candidate.overlaps(existing.descriptor())) {
				return AdmissionResult.rejected(RegionAvailabilityReason.OVERLAP,
						"region overlaps existing region " + existing.descriptor().regionId());
			}
		}
		return AdmissionResult.ACCEPTED;
	}

	private AdmissionResult evaluate(
			long requiredCells, long requiredMarkers, int regionCount, long baseCells, long baseMarkers
	) {
		long remainingCells = limits.maximumTotalCells() - baseCells;
		long remainingMarkers = limits.maximumTotalMarkers() - baseMarkers;
		String requirement = "requires " + requiredCells + " cells/" + requiredMarkers
				+ " markers; per-region limit " + limits.maximumCellsPerRegion() + "/"
				+ limits.maximumMarkersPerRegion() + "; remaining " + Math.max(0L, remainingCells)
				+ "/" + Math.max(0L, remainingMarkers);
		if (regionCount > limits.maximumRegions()) return AdmissionResult.rejected(RegionAvailabilityReason.BUDGET,
				"region-count limit " + limits.maximumRegions() + " exceeded; " + requirement);
		if (requiredCells > limits.maximumCellsPerRegion()
				|| requiredMarkers > limits.maximumMarkersPerRegion()) {
			return AdmissionResult.rejected(RegionAvailabilityReason.BUDGET,
					"per-region budget exceeded; " + requirement);
		}
		if (requiredCells > remainingCells || requiredMarkers > remainingMarkers) {
			return AdmissionResult.rejected(RegionAvailabilityReason.BUDGET,
					"server fluid budget exceeded; " + requirement);
		}
		return AdmissionResult.ACCEPTED;
	}

	private static AdmissionResult duplicate(UUID id) {
		return AdmissionResult.rejected(RegionAvailabilityReason.HEAD_INVALID, "region id already exists: " + id);
	}

	private static void requireCapacity(long cells, long markers) {
		if (cells <= 0L || markers <= 0L) {
			throw new IllegalArgumentException("Fluid region capacity requirements must be positive");
		}
	}

	private record Entry<T>(T value, Descriptor descriptor) { }

	public record Descriptor(
			UUID regionId, String dimensionId,
			double minimumX, double minimumY, double minimumZ,
			double maximumX, double maximumY, double maximumZ,
			long reservedCells, long reservedMarkers
	) {
		public Descriptor {
			Objects.requireNonNull(regionId, "regionId");
			if (dimensionId == null || dimensionId.isBlank()
					|| !(Double.isFinite(minimumX) && Double.isFinite(minimumY) && Double.isFinite(minimumZ)
					&& Double.isFinite(maximumX) && Double.isFinite(maximumY) && Double.isFinite(maximumZ))
					|| !(minimumX < maximumX && minimumY < maximumY && minimumZ < maximumZ)
					|| reservedCells <= 0L || reservedMarkers <= 0L) {
				throw new IllegalArgumentException("Region catalog descriptor is invalid");
			}
		}

		boolean overlaps(Descriptor other) {
			return dimensionId.equals(other.dimensionId)
					&& minimumX < other.maximumX && other.minimumX < maximumX
					&& minimumY < other.maximumY && other.minimumY < maximumY
					&& minimumZ < other.maximumZ && other.minimumZ < maximumZ;
		}
	}

	public record Limits(
			int maximumRegions, long maximumCellsPerRegion, long maximumMarkersPerRegion,
			long maximumTotalCells, long maximumTotalMarkers
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

	public record AdmissionResult(boolean accepted, RegionAvailabilityReason reason, String failure) {
		private static final AdmissionResult ACCEPTED = new AdmissionResult(true, null, null);
		public AdmissionResult {
			if (accepted ? reason != null || failure != null : reason == null || failure == null) {
				throw new IllegalArgumentException("Region admission result is inconsistent");
			}
		}
		private static AdmissionResult rejected(RegionAvailabilityReason reason, String failure) {
			return new AdmissionResult(false, Objects.requireNonNull(reason, "reason"),
					Objects.requireNonNull(failure, "failure"));
		}
	}
}
