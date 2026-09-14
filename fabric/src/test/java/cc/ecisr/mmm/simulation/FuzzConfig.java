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

	static final double[] RANDOM_TEMPERATURES = {
			1.0, 50.0, 150.0, 273.15, 293.15, 320.0, 373.15, 600.0, 1_500.0, 3_000.0
	};

	static final double[] RAMP_DELTAS = {
			-300.0, -50.0, -5.0, 5.0, 50.0, 300.0
	};

	/** Deterministic fractional cell volume from a cell coordinate; consumes no RNG. */
	static float partialVolume(int x, int y, int z) {
		return 0.25F + ((x * 31 + y * 17 + z * 7) % 32) * 0.24F;
	}
}
