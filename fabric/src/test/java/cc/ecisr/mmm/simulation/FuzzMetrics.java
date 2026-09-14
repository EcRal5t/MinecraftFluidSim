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
