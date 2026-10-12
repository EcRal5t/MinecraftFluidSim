package mmm.application;

/**
 * Loader-neutral scheduling state captured for one region tick.
 *
 * <p>World observations and solver state deliberately stay outside this value. They are frozen by
 * the loader adapter and passed to the selected step command only after a plan is chosen.</p>
 */
public record RegionRuntime(
		long tick,
		int stepIntervalTicks,
		int hardOverrunStreak,
		int recoveryStreak,
		boolean motionReady,
		boolean thermalReady
) {
	public RegionRuntime {
		if (tick < 0L) throw new IllegalArgumentException("tick must be non-negative");
		if (stepIntervalTicks <= 0) throw new IllegalArgumentException("step interval must be positive");
		if (hardOverrunStreak < 0) throw new IllegalArgumentException("hard overrun streak must be non-negative");
		if (recoveryStreak < 0) throw new IllegalArgumentException("recovery streak must be non-negative");
	}

	RegionRuntime withPacing(int interval, int hardOverruns, int recovery) {
		return new RegionRuntime(tick, interval, hardOverruns, recovery, motionReady, thermalReady);
	}
}
