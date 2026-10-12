package mmm.model.region;

import java.util.Objects;

/** Persisted scheduling intent; transient availability and timing evidence are deliberately absent. */
public record RegionRuntimeIntent(
		ResumeState resumeState,
		boolean operatorPaused,
		boolean entityPhysicsEnabled,
		boolean debugOverlays,
		int stepIntervalTicks,
		long tick
) {
	public RegionRuntimeIntent {
		Objects.requireNonNull(resumeState, "resumeState");
		if (stepIntervalTicks <= 0 || tick < 0L) throw new IllegalArgumentException("Invalid persisted runtime intent");
	}

	public enum ResumeState { ACTIVE, REDUCED, HYDROSTATIC_SLEEP }
}
