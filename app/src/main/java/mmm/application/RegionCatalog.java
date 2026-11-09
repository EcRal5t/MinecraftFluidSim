package mmm.application;

import mmm.api.RegionAvailabilityReason;

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
		long cells = 0L;
		for (Entry<T> entry : entries.values()) {
			cells = Math.addExact(cells, entry.descriptor().reservedCells());
		}
		return new BudgetUsage(entries.size(), cells);
	}

	public AdmissionResult add(T value, Descriptor descriptor) {
		Objects.requireNonNull(value, "value");
		Objects.requireNonNull(descriptor, "descriptor");
		if (entries.containsKey(descriptor.regionId())) return duplicate(descriptor.regionId());
		BudgetUsage current = usage();
		AdmissionResult admission = evaluate(descriptor.reservedCells(),
				current.regionCount() + 1, current.reservedCells());
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
		AdmissionResult admission = evaluate(descriptor.reservedCells(),
				current.regionCount() + 1, current.reservedCells());
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
		AdmissionResult admission = evaluate(descriptor.reservedCells(),
				current.regionCount(), current.reservedCells() - descriptor.reservedCells());
		return admission.accepted() ? evaluatePlacement(descriptor, value) : admission;
	}

	public AdmissionResult previewAdd(long requiredCells) {
		requireCapacity(requiredCells);
		BudgetUsage current = usage();
		return evaluate(requiredCells, current.regionCount() + 1,
				current.reservedCells());
	}

	public AdmissionResult replaceSelected(T value, Descriptor descriptor) {
		Objects.requireNonNull(value, "value");
		Objects.requireNonNull(descriptor, "descriptor");
		Entry<T> selected = selectedId == null ? null : entries.get(selectedId);
		AdmissionResult admission = previewReplacement(descriptor);
		if (!admission.accepted()) return admission;
		if (selected != null) entries.remove(selected.descriptor().regionId());
		entries.put(descriptor.regionId(), new Entry<>(value, descriptor));
		selectedId = descriptor.regionId();
		return admission;
	}
	public AdmissionResult previewReplacement(Descriptor descriptor) {
		Objects.requireNonNull(descriptor, "descriptor");
		Entry<T> selected = selectedId == null ? null : entries.get(selectedId);
		Entry<T> duplicate = entries.get(descriptor.regionId());
		if (duplicate != null && duplicate != selected) return duplicate(descriptor.regionId());
		BudgetUsage current = usage();
		int regionCount = selected == null ? current.regionCount() + 1 : current.regionCount();
		long baseCells = current.reservedCells()
				- (selected == null ? 0L : selected.descriptor().reservedCells());
		AdmissionResult admission = evaluate(descriptor.reservedCells(),
				regionCount, baseCells);
		return admission.accepted() ? evaluatePlacement(descriptor, selected == null ? null : selected.value()) : admission;
	}

	public AdmissionResult previewReplacement(long requiredCells) {
		requireCapacity(requiredCells);
		Entry<T> selected = selectedId == null ? null : entries.get(selectedId);
		BudgetUsage current = usage();
		int regionCount = selected == null ? current.regionCount() + 1 : current.regionCount();
		long baseCells = current.reservedCells()
				- (selected == null ? 0L : selected.descriptor().reservedCells());
		return evaluate(requiredCells, regionCount, baseCells);
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
			long requiredCells, int regionCount, long baseCells
	) {
		long remainingCells = limits.maximumTotalCells() - baseCells;
		String requirement = "requires " + requiredCells + " cells; per-region limit "
				+ limits.maximumCellsPerRegion() + "; remaining " + Math.max(0L, remainingCells);
		if (regionCount > limits.maximumRegions()) return AdmissionResult.rejected(RegionAvailabilityReason.BUDGET,
				"region-count limit " + limits.maximumRegions() + " exceeded; " + requirement);
		if (requiredCells > limits.maximumCellsPerRegion()) {
			return AdmissionResult.rejected(RegionAvailabilityReason.BUDGET,
					"per-region budget exceeded; " + requirement);
		}
		if (requiredCells > remainingCells) {
			return AdmissionResult.rejected(RegionAvailabilityReason.BUDGET,
					"server fluid budget exceeded; " + requirement);
		}
		return AdmissionResult.ACCEPTED;
	}

	private static AdmissionResult duplicate(UUID id) {
		return AdmissionResult.rejected(RegionAvailabilityReason.HEAD_INVALID, "region id already exists: " + id);
	}

	private static void requireCapacity(long cells) {
		if (cells <= 0L) {
			throw new IllegalArgumentException("Fluid region capacity requirements must be positive");
		}
	}

	private record Entry<T>(T value, Descriptor descriptor) { }

	public record Descriptor(
			UUID regionId, String dimensionId,
			double minimumX, double minimumY, double minimumZ,
			double maximumX, double maximumY, double maximumZ,
			long reservedCells,
            java.util.Set<Block> occupiedBlocks
	) {
        public record Block(int x, int y, int z) { }
        public Descriptor(UUID id, String dimension, double minX, double minY, double minZ,
                double maxX, double maxY, double maxZ, long cells) {
            this(id, dimension, minX, minY, minZ, maxX, maxY, maxZ, cells, null);
        }
		public Descriptor {
			Objects.requireNonNull(regionId, "regionId");
            if (occupiedBlocks != null) occupiedBlocks = java.util.Set.copyOf(occupiedBlocks);
			if (dimensionId == null || dimensionId.isBlank()
					|| !(Double.isFinite(minimumX) && Double.isFinite(minimumY) && Double.isFinite(minimumZ)
					&& Double.isFinite(maximumX) && Double.isFinite(maximumY) && Double.isFinite(maximumZ))
					|| !(minimumX < maximumX && minimumY < maximumY && minimumZ < maximumZ)
					|| reservedCells <= 0L) {
				throw new IllegalArgumentException("Region catalog descriptor is invalid");
			}
		}

		boolean overlaps(Descriptor other) {
            if (!dimensionId.equals(other.dimensionId) || minimumX >= other.maximumX || other.minimumX >= maximumX
                    || minimumY >= other.maximumY || other.minimumY >= maximumY
                    || minimumZ >= other.maximumZ || other.minimumZ >= maximumZ) return false;
            if (occupiedBlocks == null && other.occupiedBlocks == null) return true;
            if (occupiedBlocks != null && other.occupiedBlocks != null) {
                var smaller = occupiedBlocks.size() <= other.occupiedBlocks.size() ? occupiedBlocks : other.occupiedBlocks;
                var larger = smaller == occupiedBlocks ? other.occupiedBlocks : occupiedBlocks;
                return smaller.stream().anyMatch(larger::contains);
            }
            var blocks = occupiedBlocks == null ? other.occupiedBlocks : occupiedBlocks;
            var box = occupiedBlocks == null ? this : other;
            return blocks.stream().anyMatch(block -> block.x() < box.maximumX && block.x()+1 > box.minimumX
                    && block.y() < box.maximumY && block.y()+1 > box.minimumY
                    && block.z() < box.maximumZ && block.z()+1 > box.minimumZ);
        }
	}

	public record Limits(
			int maximumRegions, long maximumCellsPerRegion, long maximumTotalCells
	) {
		public Limits {
			if (maximumRegions <= 0 || maximumCellsPerRegion <= 0L
					|| maximumTotalCells <= 0L) {
				throw new IllegalArgumentException("Fluid region limits must be positive");
			}
			if (maximumCellsPerRegion > maximumTotalCells) {
				throw new IllegalArgumentException("Per-region limits cannot exceed total limits");
			}
		}
	}

	public record BudgetUsage(int regionCount, long reservedCells) { }

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
