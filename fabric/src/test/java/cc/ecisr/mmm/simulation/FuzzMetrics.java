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
	static final java.util.Map<String, java.util.concurrent.atomic.LongAdder> SCENE_EVENTS =
			new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.Set<String> SCENE_FAMILIES = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Families and the events that prove each one actually happened somewhere; row 0 is the family. */
	private static final String[][] REQUIRED_PROCESS = {
			{"thermal", "phase:condensedOperations", "phase:gasOperations"},
			{"granular", "granular:settledParcels", "granular:wokenParcels"},
			{"chemistry", "chemistry:evaluations"},
	};

	/** Actions whose success is a distinct coverage goal; a label that never appears is a gap. */
	private static final String[] REQUIRED_ACTIONS = {
			"extractTail", "extractSubBucket", "extractThenRestore", "containerTransfer",
			"applicationStep", "sleepWake", "blockAndRetry"
	};

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

	private static void addEvent(String scene, String name, long delta) {
		if (delta == 0L) return;
		EVENT_COUNTS.computeIfAbsent(name, key -> new java.util.concurrent.atomic.LongAdder()).add(delta);
		SCENE_EVENTS.computeIfAbsent(scene + "::" + name,
				key -> new java.util.concurrent.atomic.LongAdder()).add(delta);
	}

	/** Records what the solver actually did, per scene, to separate action coverage from process coverage. */
	static void recordEvents(String scene, cc.ecisr.mmm.fluid.FluidDomain.StepStats stats) {
		if (stats == null) return;
		addEvent(scene, "chemistry:evaluations", stats.chemistryEvaluations());
		addEvent(scene, "chemistry:changedMarkers", stats.chemistryChangedMarkers());
		addEvent(scene, "reseed:merges", stats.reseededMerges());
		addEvent(scene, "reseed:splits", stats.reseededSplits());
		var granular = stats.granularTransition();
		if (granular != null) {
			addEvent(scene, "granular:settledParcels", granular.settledParcels());
			addEvent(scene, "granular:wokenParcels", granular.wokenParcels());
		}
		recordPhaseCounters(scene, stats.thermalAxis(), false);
	}

	static void recordEvents(String scene, cc.ecisr.mmm.fluid.FluidDomain.ThermalAxisStats axis) {
		recordPhaseCounters(scene, axis, true);
	}

	private static void recordPhaseCounters(String scene, cc.ecisr.mmm.fluid.FluidDomain.ThermalAxisStats axis,
	                                        boolean withChemistry) {
		if (axis == null) return;
		if (axis.condensedPhase() != null) {
			addEvent(scene, "phase:condensedOperations", axis.condensedPhase().operations());
		}
		if (axis.gasPhase() != null) addEvent(scene, "phase:gasOperations", axis.gasPhase().operations());
		if (axis.granularPhase() != null) {
			addEvent(scene, "granular:precipitations", axis.granularPhase().precipitations());
		}
		if (withChemistry && axis.chemistry() != null) {
			addEvent(scene, "chemistry:evaluations", axis.chemistry().evaluations());
		}
	}

	/** Marks a physics family as exercised in one scene, so its absence can be judged per scene. */
	static void note(String scene, String family) {
		SCENE_FAMILIES.add(scene + "::" + family);
	}

	/**
	 * Process coverage is judged per scene: a family exercising in one scene no longer hides another
	 * scene that never produced its event. Reports are informational unless
	 * {@code -Dmmm.fuzzRequireCoverage=true} turns a gap into a failure.
	 */
	static void printEvents() {
		System.out.println("process coverage (events that actually occurred):");
		if (!EVENT_COUNTS.isEmpty()) {
			EVENT_COUNTS.entrySet().stream()
					.sorted((left, right) -> Long.compare(right.getValue().sum(), left.getValue().sum()))
					.forEach(entry -> System.out.println("  " + entry.getValue().sum() + " " + entry.getKey()));
		}
		int gaps = 0;
		java.util.TreeSet<String> scenes = new java.util.TreeSet<>();
		for (String key : SCENE_FAMILIES) scenes.add(key.substring(0, key.indexOf("::")));
		for (String scene : scenes) {
			for (String[] required : REQUIRED_PROCESS) {
				if (!familyGapExists(scene, required[0])) continue;
				gaps++;
				System.out.println("  COVERAGE GAP scene=" + scene + " family=" + required[0]
						+ " events=" + String.join("/",
						java.util.Arrays.copyOfRange(required, 1, required.length)));
			}
		}
		for (String action : REQUIRED_ACTIONS) {
			boolean seen = false;
			for (String label : ACTION_COVERAGE.keySet()) {
				if (label.equals(action) || label.startsWith(action + ":")) { seen = true; break; }
			}
			if (!seen) {
				gaps++;
				System.out.println("  COVERAGE GAP action=" + action + " never succeeded");
			}
		}
		System.out.println("FUZZ COVERAGE " + (gaps == 0 ? "adequate" : "insufficient gaps=" + gaps));
		if (gaps > 0 && Boolean.getBoolean("mmm.fuzzRequireCoverage")) {
			throw new AssertionError("fuzz coverage goals unmet: " + gaps + " gap(s)");
		}
	}

	/** True when a family was exercised in a scene but none of that family's proving events fired. */
	static boolean familyGapExists(String scene, String family) {
		if (!SCENE_FAMILIES.contains(scene + "::" + family)) return false;
		for (String[] required : REQUIRED_PROCESS) {
			if (!required[0].equals(family)) continue;
			for (int index = 1; index < required.length; index++) {
				if (SCENE_EVENTS.containsKey(scene + "::" + required[index])) return false;
			}
			return true;
		}
		return false;
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
