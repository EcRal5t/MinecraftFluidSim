package cc.ecisr.mmm.simulation;

/**
 * Thread-safe coverage and timing counters for fuzz runs, plus the summary they print. It observes the
 * harness only; it never touches simulation state.
 */
final class FuzzMetrics {
	private FuzzMetrics() { }

	static final java.util.Map<String, java.util.concurrent.atomic.AtomicLong> ACTION_COVERAGE =
			new java.util.concurrent.ConcurrentHashMap<>();
	static final java.util.Map<String, java.util.concurrent.atomic.LongAdder> ACTION_NANOS =
			new java.util.concurrent.ConcurrentHashMap<>();
	static final java.util.Map<String, java.util.concurrent.atomic.LongAdder> ACTION_COUNTS =
			new java.util.concurrent.ConcurrentHashMap<>();
	static final java.util.Map<String, java.util.concurrent.atomic.LongAdder> ACTION_SUBSTEPS =
			new java.util.concurrent.ConcurrentHashMap<>();
	static final java.util.Map<String, Long> JOB_MILLIS = new java.util.concurrent.ConcurrentHashMap<>();
	static final java.util.Map<String, Long> JOB_SUBSTEPS = new java.util.concurrent.ConcurrentHashMap<>();
	static final java.util.Map<String, java.util.concurrent.atomic.LongAdder> EVENT_COUNTS =
			new java.util.concurrent.ConcurrentHashMap<>();

	static void cover(String action) {
		ACTION_COVERAGE.computeIfAbsent(coverageLabel(action),
				key -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
	}

	static String coverageLabel(String action) {
		if (action.startsWith("skip(")) {
			String inner = action.substring(5);
			int cut = inner.indexOf('(');
			if (cut <= 0 && inner.endsWith(")")) cut = inner.length() - 1;
			return "skip:" + (cut > 0 ? inner.substring(0, cut) : inner);
		}
		int cut = action.indexOf('(');
		if (cut <= 0) return action;
		String inner = action.substring(cut + 1);
		if (inner.isEmpty() || !Character.isLetter(inner.charAt(0))) return action.substring(0, cut);
		int comma = inner.indexOf(',');
		int close = inner.indexOf(')');
		int end = comma > 0 ? comma : (close > 0 ? close : inner.length());
		return action.substring(0, cut) + ":" + inner.substring(0, end);
	}

	static void printCoverage() {
		System.out.println("action coverage:");
		ACTION_COVERAGE.entrySet().stream()
				.sorted((left, right) -> Long.compare(right.getValue().get(), left.getValue().get()))
				.forEach(entry -> System.out.println("  " + entry.getValue() + " " + entry.getKey()));
	}

	private static void addEvent(String name, long delta) {
		if (delta != 0L) {
			EVENT_COUNTS.computeIfAbsent(name, key -> new java.util.concurrent.atomic.LongAdder()).add(delta);
		}
	}

	/** Records what the solver actually did, so action coverage can be told apart from process coverage. */
	static void recordEvents(cc.ecisr.mmm.fluid.FluidDomain.StepStats stats) {
		if (stats == null) return;
		addEvent("chemistry:evaluations", stats.chemistryEvaluations());
		addEvent("chemistry:changedMarkers", stats.chemistryChangedMarkers());
		addEvent("reseed:merges", stats.reseededMerges());
		addEvent("reseed:splits", stats.reseededSplits());
		var granular = stats.granularTransition();
		if (granular != null) {
			addEvent("granular:settledParcels", granular.settledParcels());
			addEvent("granular:wokenParcels", granular.wokenParcels());
		}
		recordPhaseCounters(stats.thermalAxis(), false);
	}

	static void recordEvents(cc.ecisr.mmm.fluid.FluidDomain.ThermalAxisStats axis) {
		recordPhaseCounters(axis, true);
	}

	private static void recordPhaseCounters(cc.ecisr.mmm.fluid.FluidDomain.ThermalAxisStats axis,
	                                        boolean withChemistry) {
		if (axis == null) return;
		if (axis.condensedPhase() != null) addEvent("phase:condensedOperations", axis.condensedPhase().operations());
		if (axis.gasPhase() != null) addEvent("phase:gasOperations", axis.gasPhase().operations());
		if (axis.granularPhase() != null) addEvent("granular:precipitations", axis.granularPhase().precipitations());
		if (withChemistry && axis.chemistry() != null) addEvent("chemistry:evaluations", axis.chemistry().evaluations());
	}

	private static final java.util.Set<String> EXERCISED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Marks a physics family as exercised so its absence from process coverage can be judged. */
	static void note(String family) {
		EXERCISED.add(family);
	}

	static void printEvents() {
		System.out.println("process coverage (events that actually occurred):");
		if (EVENT_COUNTS.isEmpty()) {
			System.out.println("  <none observed>");
			return;
		}
		EVENT_COUNTS.entrySet().stream()
				.sorted((left, right) -> Long.compare(right.getValue().sum(), left.getValue().sum()))
				.forEach(entry -> System.out.println("  " + entry.getValue().sum() + " " + entry.getKey()));
		warnIfMissing("thermal", "phase:condensedOperations", "phase:gasOperations");
		warnIfMissing("granular", "granular:settledParcels");
		warnIfMissing("chemistry", "chemistry:evaluations");
	}

	/** Warns only when a family ran but none of its expected events were ever observed. */
	private static void warnIfMissing(String family, String... events) {
		if (!EXERCISED.contains(family)) return;
		for (String event : events) if (EVENT_COUNTS.containsKey(event)) return;
		System.out.println("  WARN no observed process in " + family + ": " + String.join("/", events));
	}

	static void printHotspots() {
		System.out.println("action hotspots (total ms / count / ms-per-call / substeps):");
		ACTION_NANOS.entrySet().stream()
				.sorted((left, right) -> Long.compare(right.getValue().sum(), left.getValue().sum()))
				.limit(12)
				.forEach(entry -> {
					long totalNanos = entry.getValue().sum();
					long count = ACTION_COUNTS.get(entry.getKey()).sum();
					long substeps = ACTION_SUBSTEPS.containsKey(entry.getKey())
							? ACTION_SUBSTEPS.get(entry.getKey()).sum() : 0L;
					System.out.printf(java.util.Locale.ROOT, "  %9d ms  %6d x  %9.2f ms  %10d substeps  %s%n",
							totalNanos / 1_000_000L, count,
							count == 0L ? 0.0 : (double) totalNanos / 1_000_000.0 / count,
							substeps, entry.getKey());
				});
		System.out.println("slowest jobs (ms / substeps / scene/seed):");
		JOB_MILLIS.entrySet().stream()
				.sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
				.limit(10)
				.forEach(entry -> System.out.println("  " + entry.getValue() + " ms  substeps="
						+ JOB_SUBSTEPS.getOrDefault(entry.getKey(), 0L) + "  " + entry.getKey()));
	}
}
