package mmm.simulation;

import mmm.api.RegionAvailabilityReason;

import java.util.Objects;

/** Pure scheduling state for one region; it never infers physical water from reconstructed fields. */
public final class FluidRegionLifecycle {
	public enum State {
		ACTIVE,
		REDUCED,
		HYDROSTATIC_SLEEP,
		UNLOADED
	}

	private State state = State.ACTIVE;
	private boolean operatorPaused = true;
	private RegionAvailabilityReason availabilityReason;
	private String availabilityFailure;
	private int quietCompletedSteps;

	public State state() {
		return state;
	}

	public boolean operatorPaused() {
		return operatorPaused;
	}

	public String availabilityFailure() {
		return availabilityFailure;
	}

	public RegionAvailabilityReason availabilityReason() {
		return availabilityReason;
	}

	public int quietCompletedSteps() {
		return quietCompletedSteps;
	}

	public boolean canStep() {
		return !operatorPaused && availabilityFailure == null
				&& state != State.HYDROSTATIC_SLEEP && state != State.UNLOADED;
	}

	public void setOperatorPaused(boolean paused, int stepIntervalTicks) {
		operatorPaused = paused;
		if (!paused && state != State.UNLOADED) wakeForActivity(stepIntervalTicks);
	}

	/** Availability blocks stepping without overwriting the player's persistent run/pause intent. */
	public void blockAvailability(String failure) {
		blockAvailability(RegionAvailabilityReason.NUMERICAL_PAUSE, failure);
	}

	public void blockAvailability(RegionAvailabilityReason reason, String failure) {
		availabilityReason = Objects.requireNonNull(reason, "reason");
		availabilityFailure = Objects.requireNonNull(failure, "failure");
		quietCompletedSteps = 0;
	}

	public void clearAvailabilityBlock() {
		availabilityReason = null;
		availabilityFailure = null;
	}

	public void onStepIntervalChanged(int stepIntervalTicks) {
		if (state == State.HYDROSTATIC_SLEEP || state == State.UNLOADED) return;
		state = runningState(stepIntervalTicks);
	}

	public void wakeForActivity(int stepIntervalTicks) {
		if (state == State.UNLOADED) return;
		state = runningState(stepIntervalTicks);
		quietCompletedSteps = 0;
	}

	/** Returns true only on the step that enters hydrostatic sleep. */
	public boolean observeCompletedStep(boolean hydrostaticCandidate, int requiredQuietSteps) {
		if (requiredQuietSteps <= 0) throw new IllegalArgumentException("requiredQuietSteps must be positive");
		if (operatorPaused || availabilityFailure != null || state == State.UNLOADED) {
			quietCompletedSteps = 0;
			return false;
		}
		if (!hydrostaticCandidate) {
			quietCompletedSteps = 0;
			return false;
		}
		if (state == State.HYDROSTATIC_SLEEP) return false;
		quietCompletedSteps++;
		if (quietCompletedSteps < requiredQuietSteps) return false;
		state = State.HYDROSTATIC_SLEEP;
		return true;
	}

	/** The caller must prove both publication and solver release before committing UNLOADED. */
	boolean requestUnload(boolean completeCheckpointPublished, boolean liveSolverReleased) {
		if (!completeCheckpointPublished || !liveSolverReleased) return false;
		availabilityReason = null;
		availabilityFailure = null;
		quietCompletedSteps = 0;
		state = State.UNLOADED;
		return true;
	}

	void initializeUnloaded(boolean persistedOperatorPaused) {
		operatorPaused = persistedOperatorPaused;
		availabilityReason = null;
		availabilityFailure = null;
		quietCompletedSteps = 0;
		state = State.UNLOADED;
	}

	void restoreFromUnloaded(State resumeState, int stepIntervalTicks) {
		if (state != State.UNLOADED) throw new IllegalStateException("Region is not unloaded");
		if (resumeState == State.UNLOADED) throw new IllegalArgumentException("Resume state cannot be UNLOADED");
		state = resumeState == State.HYDROSTATIC_SLEEP ? State.HYDROSTATIC_SLEEP : runningState(stepIntervalTicks);
		availabilityReason = null;
		availabilityFailure = null;
		quietCompletedSteps = 0;
	}

	/** Initializes a detached loaded candidate with exactly the persisted scheduling intent. */
	void initializeLoaded(State persistedState, boolean persistedOperatorPaused, int stepIntervalTicks) {
		if (persistedState == null || persistedState == State.UNLOADED) {
			throw new IllegalArgumentException("Loaded candidate state is invalid");
		}
		operatorPaused = persistedOperatorPaused;
		state = persistedState == State.HYDROSTATIC_SLEEP ? State.HYDROSTATIC_SLEEP : runningState(stepIntervalTicks);
		availabilityReason = null;
		availabilityFailure = null;
		quietCompletedSteps = 0;
	}

	private static State runningState(int stepIntervalTicks) {
		if (stepIntervalTicks <= 0) throw new IllegalArgumentException("stepIntervalTicks must be positive");
		return stepIntervalTicks == 1 ? State.ACTIVE : State.REDUCED;
	}
}
