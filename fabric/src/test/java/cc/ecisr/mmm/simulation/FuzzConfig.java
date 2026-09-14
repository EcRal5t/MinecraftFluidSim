package cc.ecisr.mmm.simulation;

/** Mutable run configuration and shared scalar constants for the normal-play fuzz harness. */
final class FuzzConfig {
	private FuzzConfig() { }

	static boolean FUZZ_PROGRESS;
	static boolean FUZZ_DEBUG;
	static boolean FUZZ_PICKS;
	static boolean FUZZ_DISABLE_GRANULAR = false;
	static boolean FUZZ_DISK = false;
	static boolean FUZZ_PARTIAL = false;
	static final int FUZZ_DISK_ROUND_TRIP_INTERVAL = 4;
	static final int SCENE_HALF_BOTTOM = 0x33;

	/**
	 * Per-job wall-clock budget. Deliberately generous so the known slow scenes are never misreported
	 * as hangs; override with -Dmmm.fuzzJobTimeoutMillis=... when narrowing.
	 */
	static final long JOB_TIMEOUT_MILLIS = Long.getLong("mmm.fuzzJobTimeoutMillis", 30L * 60L * 1000L);

	static final double[] RANDOM_TEMPERATURES = {
			1.0, 50.0, 150.0, 273.15, 293.15, 320.0, 373.15, 600.0, 1_500.0, 3_000.0
	};

	static final double[] RAMP_DELTAS = {
			-300.0, -50.0, -5.0, 5.0, 50.0, 300.0
	};

	/** Sub-bucket volumes down to a cubic-millimetre scale, for minimal-remainder and tail coverage. */
	static final float[] MINIMUM_VOLUMES = {
			1.0E-9F, 1.0E-6F, 1.0E-3F, 0.0625F, 0.25F, 1.0F, 7.999F
	};

	/** Deterministic fractional cell volume from a cell coordinate; consumes no RNG. */
	static float partialVolume(int x, int y, int z) {
		return 0.25F + ((x * 31 + y * 17 + z * 7) % 32) * 0.24F;
	}

	/**
	 * Single source of truth for the expanded action table thresholds. {@code applyExpandedRandomAction}
	 * and {@code FuzzMinimizer.branchName} both resolve names through here so the mapping cannot drift.
	 */
	static String actionName(int pick) {
		if (pick < 4) return "insertWater";
		if (pick < 8) return "insertMercury";
		if (pick < 12) return "insertOil";
		if (pick < 19) return "insertAcid";
		if (pick < 26) return "insertBase";
		if (pick < 30) return "insertSand";
		if (pick < 34) return "burst";
		if (pick < 37) return "insertMixture";
		if (pick < 42) return "step(motion)";
		if (pick < 47) return "step(phase)";
		if (pick < 52) return "steps(phase)";
		if (pick < 55) return "steps(motion)";
		if (pick < 59) return "temperature";
		if (pick < 62) return "rampTemperature";
		if (pick < 65) return "thermalStep(motion)";
		if (pick < 68) return "thermalStep(phase)";
		if (pick < 72) return "step(movingBody)";
		if (pick < 74) return "pause";
		if (pick < 76) return "resume";
		if (pick < 78) return "saveWhilePaused";
		if (pick < 81) return "containerTransfer";
		if (pick < 83) return "coldRestart";
		if (pick < 86) return "extractWater";
		if (pick < 89) return "extractChemical";
		if (pick < 91) return "extractMixture";
		if (pick < 92) return "extractTail";
		if (pick < 93) return "extractOverCapacity";
		if (pick < 94) return "extractSubBucket";
		if (pick < 95) return "extractThenRestore";
		if (pick < 96) return "insertMinimalVolume";
		if (pick < 97) return "indicator";
		if (pick < 98) return "clear";
		if (pick < 99) return "vent";
		return "settle";
	}
}
