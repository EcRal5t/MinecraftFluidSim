package com.example.simulation;

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

	public int quietCompletedSteps() {
		return quietCompletedSteps;
	}

	public boolean canStep() {
		return !operatorPaused && availabilityFailure == null
				&& state != State.HYDROSTATIC_SLEEP && state != State.UNLOADED;
	}

	public void setOperatorPaused(boolean paused, int stepIntervalTicks) {
		if (!paused && state == State.UNLOADED) return;
		operatorPaused = paused;
		if (!paused) wakeForActivity(stepIntervalTicks);
	}

	/** A partial or invalid region is frozen and requires an explicit run after it becomes ready again. */
	public void blockAvailability(String failure) {
		availabilityFailure = Objects.requireNonNull(failure, "failure");
		operatorPaused = true;
		quietCompletedSteps = 0;
	}

	public void clearAvailabilityBlock() {
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

	/**
	 * The live service must not call this until an atomic, complete checkpoint has been published and
	 * the solver arrays can be released. The current in-memory M2 gate therefore always passes false.
	 */
	boolean requestUnload(boolean completeCheckpointPublished, boolean liveSolverReleased) {
		if (!completeCheckpointPublished || !liveSolverReleased) return false;
		operatorPaused = true;
		availabilityFailure = null;
		quietCompletedSteps = 0;
		state = State.UNLOADED;
		return true;
	}

	private static State runningState(int stepIntervalTicks) {
		if (stepIntervalTicks <= 0) throw new IllegalArgumentException("stepIntervalTicks must be positive");
		return stepIntervalTicks == 1 ? State.ACTIVE : State.REDUCED;
	}
}
