package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.thermal.SignedDyadic;
import cc.ecisr.mmm.thermal.ThermalTransport;

import java.util.Objects;

/** Orthogonal thermal scheduling state. It never changes motion pacing or wall-time thresholds. */
public final class ThermalActivity {
	public enum State { ACTIVE, REDUCED, SLEEP, PAUSED }
	public enum WakeReason {
		BOUNDARY_CHANGE, CONTAINER_TRANSFER, ADMIN_SOURCE, CHEMISTRY_DIRTY,
		GRANULAR_DIRTY, GAS_ACTIVITY, PHASE_DIRTY, CARRIER_MOTION, AUDIT_RECOVERY
	}

	public record Observation(
			ThermalTransport.Stats transport,
			boolean chemistryDirty,
			boolean granularDirty,
			boolean phaseDirty,
			boolean gasActivity,
			boolean carrierMotion,
			boolean pendingVolumeOrWork,
			boolean auditClosed
	) {
		public Observation {
			Objects.requireNonNull(transport, "transport");
		}

		boolean quiet() {
			return transport.maximumAbsoluteTransferEu() == 0.0
					&& transport.reservoirExchange().equals(SignedDyadic.ZERO)
					&& !chemistryDirty && !granularDirty && !phaseDirty && !gasActivity && !carrierMotion
					&& !pendingVolumeOrWork && auditClosed;
		}
	}

	private State state = State.ACTIVE;
	private int quietSteps;
	private String failure;

	public State state() { return state; }
	public int quietSteps() { return quietSteps; }
	public String failure() { return failure; }
	public boolean canStep() { return state != State.SLEEP && state != State.PAUSED; }

	/** Returns true only on the observation that enters thermal sleep. */
	public boolean observe(Observation observation, int requiredQuietSteps) {
		Objects.requireNonNull(observation, "observation");
		if (requiredQuietSteps <= 1) throw new IllegalArgumentException("Thermal quiet-step requirement must exceed one");
		if (state == State.PAUSED) return false;
		if (!observation.quiet()) {
			state = State.ACTIVE; quietSteps = 0; return false;
		}
		if (state == State.SLEEP) return false;
		quietSteps++;
		if (quietSteps >= requiredQuietSteps) {
			state = State.SLEEP; return true;
		}
		state = State.REDUCED;
		return false;
	}

	public void wake(WakeReason reason) {
		Objects.requireNonNull(reason, "reason");
		if (state == State.PAUSED) return;
		state = State.ACTIVE; quietSteps = 0;
	}

	public void pause(String failure) {
		this.failure = Objects.requireNonNull(failure, "failure");
		state = State.PAUSED; quietSteps = 0;
	}

	public void clearPause() {
		if (state != State.PAUSED) return;
		failure = null; state = State.ACTIVE; quietSteps = 0;
	}
}
