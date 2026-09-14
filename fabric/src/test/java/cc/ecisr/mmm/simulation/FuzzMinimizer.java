package cc.ecisr.mmm.simulation;

/**
 * Records a failing seed as one RNG-token slice per action, then delta-debugs the failure to a strict
 * subsequence of those actions. It replays through the shared action table and normal-play state.
 */
final class FuzzMinimizer {
	private FuzzMinimizer() { }

	private record RngToken(char kind, int bound, int intValue, double doubleValue, boolean boolValue) { }

	private static final class RecordingRandom extends java.util.Random {
		private final java.util.List<RngToken> tokens = new java.util.ArrayList<>();

		RecordingRandom(long seed) { super(seed); }

		@Override public int nextInt(int bound) {
			int value = super.nextInt(bound);
			tokens.add(new RngToken('i', bound, value, 0.0, false));
			return value;
		}

		@Override public boolean nextBoolean() {
			boolean value = super.nextBoolean();
			tokens.add(new RngToken('b', 0, 0, 0.0, value));
			return value;
		}

		@Override public double nextDouble() {
			double value = super.nextDouble();
			tokens.add(new RngToken('d', 0, 0, value, false));
			return value;
		}
	}

	private static final class ScriptedRandom extends java.util.Random {
		private final java.util.List<RngToken> tokens;
		private int cursor;

		ScriptedRandom(java.util.List<RngToken> tokens) { this.tokens = tokens; }

		private RngToken take(char kind) {
			if (cursor >= tokens.size()) throw new IllegalStateException("script exhausted");
			RngToken token = tokens.get(cursor++);
			if (token.kind() != kind) throw new IllegalStateException("script kind mismatch");
			return token;
		}

		@Override public int nextInt(int bound) {
			RngToken token = take('i');
			if (token.bound() != bound) throw new IllegalStateException("script bound mismatch");
			return token.intValue();
		}

		@Override public boolean nextBoolean() { return take('b').boolValue(); }

		@Override public double nextDouble() { return take('d').doubleValue(); }
	}

	private record ScriptedFailure(
			ControllerInventoryDurabilityValidation.FuzzScene scene, int seed, String root,
			java.util.List<java.util.List<RngToken>> slices, java.util.List<String> labels
	) { }

	private static String failedLabel(java.util.List<RngToken> slice) {
		if (slice.isEmpty()) return "<failed>";
		RngToken first = slice.get(0);
		if (first.kind() != 'i' || first.bound() != 100) return "<failed>";
		return branchName(first.intValue());
	}

	/** Mirrors the thresholds in the expanded action table; used only to label recorded slices. */
	static String branchName(int pick) {
		if (pick < 5) return "insertWater";
		if (pick < 10) return "insertMercury";
		if (pick < 15) return "insertOil";
		if (pick < 22) return "insertAcid";
		if (pick < 29) return "insertBase";
		if (pick < 34) return "insertSand";
		if (pick < 39) return "burst";
		if (pick < 42) return "insertMixture";
		if (pick < 47) return "step(motion)";
		if (pick < 52) return "step(phase)";
		if (pick < 57) return "steps(phase)";
		if (pick < 60) return "steps(motion)";
		if (pick < 64) return "temperature";
		if (pick < 67) return "rampTemperature";
		if (pick < 70) return "thermalStep(motion)";
		if (pick < 73) return "thermalStep(phase)";
		if (pick < 77) return "step(movingBody)";
		if (pick < 79) return "pause";
		if (pick < 81) return "resume";
		if (pick < 83) return "saveWhilePaused";
		if (pick < 86) return "containerTransfer";
		if (pick < 88) return "coldRestart";
		if (pick < 91) return "extractWater";
		if (pick < 94) return "extractChemical";
		if (pick < 96) return "extractMixture";
		if (pick < 97) return "indicator";
		if (pick < 98) return "clear";
		if (pick < 99) return "vent";
		return "settle";
	}

	private static ScriptedFailure recordScriptedFailure(
			FullCapabilityTestFixture.Registries registries,
			ControllerInventoryDurabilityValidation.FuzzScene scene, int seed, int maxActions
	) throws Exception {
		ControllerInventoryDurabilityValidation.NormalPlayState state =
				new ControllerInventoryDurabilityValidation.NormalPlayState(registries, scene);
		RecordingRandom random = new RecordingRandom(seed);
		java.util.List<java.util.List<RngToken>> slices = new java.util.ArrayList<>();
		java.util.List<String> labels = new java.util.ArrayList<>();
		for (int index = 0; index < maxActions; index++) {
			int before = random.tokens.size();
			try {
				labels.add(ControllerInventoryDurabilityValidation.applyExpandedRandomAction(state, random));
			} catch (Throwable failure) {
				java.util.List<RngToken> slice =
						java.util.List.copyOf(random.tokens.subList(before, random.tokens.size()));
				slices.add(slice);
				labels.add(failedLabel(slice));
				return new ScriptedFailure(scene, seed,
						ControllerInventoryDurabilityValidation.rootCause(failure), slices, labels);
			}
			slices.add(java.util.List.copyOf(random.tokens.subList(before, random.tokens.size())));
		}
		return null;
	}

	private static String replayScript(
			FullCapabilityTestFixture.Registries registries, ScriptedFailure script, int[] keep
	) throws Exception {
		ControllerInventoryDurabilityValidation.NormalPlayState state =
				new ControllerInventoryDurabilityValidation.NormalPlayState(registries, script.scene());
		for (int index : keep) {
			ControllerInventoryDurabilityValidation.applyExpandedRandomAction(
					state, new ScriptedRandom(script.slices().get(index)));
		}
		return null;
	}

	private static boolean reproduces(
			FullCapabilityTestFixture.Registries registries, ScriptedFailure script, int[] keep
	) {
		try {
			return script.root().equals(replayScript(registries, script, keep));
		} catch (Throwable failure) {
			return script.root().equals(ControllerInventoryDurabilityValidation.rootCause(failure));
		}
	}

	static void minimize(
			FullCapabilityTestFixture.Registries registries,
			ControllerInventoryDurabilityValidation.FuzzScene scene, int seed, int actions
	) throws Exception {
		ScriptedFailure script = recordScriptedFailure(registries, scene, seed, actions);
		if (script == null) {
			System.out.println("minimize[" + scene.name() + " seed=" + seed + "]: did not fail");
			return;
		}
		int size = script.slices().size();
		int[] keep = new int[size];
		for (int index = 0; index < size; index++) keep[index] = index;
		System.out.println("minimize[" + scene.name() + " seed=" + seed + "] start actions=" + size
				+ " root=" + script.root());
		int granularity = 2;
		while (keep.length >= 2) {
			int chunk = (keep.length + granularity - 1) / granularity;
			int[] reduced = null;
			for (int start = 0; start < keep.length; start += chunk) {
				int end = Math.min(keep.length, start + chunk);
				int[] candidate = new int[keep.length - (end - start)];
				System.arraycopy(keep, 0, candidate, 0, start);
				System.arraycopy(keep, end, candidate, start, keep.length - end);
				if (reproduces(registries, script, candidate)) {
					reduced = candidate;
					break;
				}
			}
			if (reduced != null) {
				keep = reduced;
				granularity = Math.max(2, granularity - 1);
			} else if (granularity >= keep.length) {
				break;
			} else {
				granularity = Math.min(keep.length, granularity * 2);
			}
		}
		StringBuilder trace = new StringBuilder("[");
		for (int index = 0; index < keep.length; index++) {
			if (index > 0) trace.append(", ");
			trace.append(keep[index] + 1).append(':').append(script.labels().get(keep[index]));
		}
		trace.append("]");
		System.out.println("minimize[" + scene.name() + " seed=" + seed + "] final actions=" + keep.length);
		System.out.println("minimize[" + scene.name() + " seed=" + seed + "] trace=" + trace);
	}
}
