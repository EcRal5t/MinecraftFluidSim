package mmm.application;

import mmm.application.command.MotionStepCommand;
import mmm.application.command.ThermalStepCommand;
import mmm.application.result.MotionStepResult;
import mmm.application.result.ThermalStepResult;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Chooses and executes at most one logical solver axis for a frozen region tick. */
public final class RegionTickUseCase {
	public enum Axis { NONE, MOTION, THERMAL }

	/** Narrow execution seam owned by the tick use case that consumes it. */
	public interface StepExecutor {
		MotionStepResult stepMotion(MotionStepCommand command);
		ThermalStepResult stepThermal(ThermalStepCommand command);
	}

	public record Policy(
			double stepBudgetMilliseconds,
			double hardPauseMilliseconds,
			int hardPauseStreak,
			int maximumStepIntervalTicks,
			int recoverySteps
	) {
		public Policy {
			if (!(Double.isFinite(stepBudgetMilliseconds) && stepBudgetMilliseconds > 0.0)) {
				throw new IllegalArgumentException("step budget must be finite and positive");
			}
			if (!(Double.isFinite(hardPauseMilliseconds) && hardPauseMilliseconds > 0.0)) {
				throw new IllegalArgumentException("hard pause duration must be finite and positive");
			}
			if (hardPauseStreak <= 0 || maximumStepIntervalTicks <= 0 || recoverySteps <= 0) {
				throw new IllegalArgumentException("pacing counts must be positive");
			}
		}

		public static Policy production() {
			return new Policy(40.0, 200.0, 3, 4, 8);
		}
	}

	public record Plan(RegionRuntime runtime, Axis axis) {
		public Plan {
			Objects.requireNonNull(runtime, "runtime");
			Objects.requireNonNull(axis, "axis");
			if (axis != dueAxis(runtime)) throw new IllegalArgumentException("plan axis disagrees with runtime");
		}
	}

	public record Pacing(RegionRuntime runtime, boolean autoPause) {
		public Pacing { Objects.requireNonNull(runtime, "runtime"); }
	}

	public record MotionOutcome(
			MotionStepResult result, RegionRuntime runtime, double elapsedMilliseconds, boolean autoPause
	) {
		public MotionOutcome {
			Objects.requireNonNull(result, "result");
			Objects.requireNonNull(runtime, "runtime");
			validateElapsed(elapsedMilliseconds);
			if (!result.accepted() && autoPause) {
				throw new IllegalArgumentException("rejected motion step cannot request auto-pause");
			}
		}
	}

	public record ThermalOutcome(
			ThermalStepResult result, RegionRuntime runtime, double elapsedMilliseconds, boolean autoPause
	) {
		public ThermalOutcome {
			Objects.requireNonNull(result, "result");
			Objects.requireNonNull(runtime, "runtime");
			validateElapsed(elapsedMilliseconds);
			if (!result.accepted() && autoPause) {
				throw new IllegalArgumentException("rejected thermal step cannot request auto-pause");
			}
		}
	}

	private final StepExecutor steps;
	private final LongSupplier nanoTime;
	private final Policy policy;

	public RegionTickUseCase(StepExecutor steps, Policy policy) {
		this(steps, System::nanoTime, policy);
	}

	public RegionTickUseCase(StepExecutor steps, LongSupplier nanoTime, Policy policy) {
		this.steps = Objects.requireNonNull(steps, "steps");
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
		this.policy = Objects.requireNonNull(policy, "policy");
	}

	public Plan plan(RegionRuntime runtime) {
		return new Plan(Objects.requireNonNull(runtime, "runtime"), dueAxis(runtime));
	}

	public MotionOutcome executeMotion(Plan plan, MotionStepCommand command) {
		requireAxis(plan, Axis.MOTION);
		long start = nanoTime.getAsLong();
		MotionStepResult result = steps.stepMotion(Objects.requireNonNull(command, "command"));
		double elapsed = elapsedMilliseconds(start, nanoTime.getAsLong());
		if (!result.accepted()) return new MotionOutcome(result, plan.runtime(), elapsed, false);
		Pacing pacing = paceAfterAcceptedStep(plan.runtime(), elapsed);
		return new MotionOutcome(result, pacing.runtime(), elapsed, pacing.autoPause());
	}

	public ThermalOutcome executeThermal(Plan plan, ThermalStepCommand command) {
		requireAxis(plan, Axis.THERMAL);
		long start = nanoTime.getAsLong();
		ThermalStepResult result = steps.stepThermal(Objects.requireNonNull(command, "command"));
		double elapsed = elapsedMilliseconds(start, nanoTime.getAsLong());
		if (!result.accepted()) return new ThermalOutcome(result, plan.runtime(), elapsed, false);
		Pacing pacing = paceAfterAcceptedStep(plan.runtime(), elapsed);
		return new ThermalOutcome(result, pacing.runtime(), elapsed, pacing.autoPause());
	}

	/** Pure pacing transition shared by production execution and deterministic characterization tests. */
	public Pacing paceAfterAcceptedStep(RegionRuntime runtime, double elapsedMilliseconds) {
		Objects.requireNonNull(runtime, "runtime");
		validateElapsed(elapsedMilliseconds);
		int hardOverruns = elapsedMilliseconds > policy.hardPauseMilliseconds()
				? Math.addExact(runtime.hardOverrunStreak(), 1) : 0;
		int requiredInterval = Math.max(1, Math.min(policy.maximumStepIntervalTicks(),
				(int) Math.ceil(elapsedMilliseconds / policy.stepBudgetMilliseconds())));
		int interval = runtime.stepIntervalTicks();
		int recovery = runtime.recoveryStreak();
		if (requiredInterval > interval) {
			interval = requiredInterval;
			recovery = 0;
		} else if (requiredInterval < interval) {
			recovery = Math.addExact(recovery, 1);
			if (recovery >= policy.recoverySteps()) {
				interval--;
				recovery = 0;
			}
		} else {
			recovery = 0;
		}
		RegionRuntime next = runtime.withPacing(interval, hardOverruns, recovery);
		return new Pacing(next, hardOverruns >= policy.hardPauseStreak());
	}

	private static Axis dueAxis(RegionRuntime runtime) {
		if (runtime.tick() % runtime.stepIntervalTicks() != 0L) return Axis.NONE;
		if (runtime.motionReady()) return Axis.MOTION;
		return runtime.thermalReady() ? Axis.THERMAL : Axis.NONE;
	}

	private static void requireAxis(Plan plan, Axis expected) {
		Objects.requireNonNull(plan, "plan");
		if (plan.axis() != expected) throw new IllegalArgumentException(
				"Cannot execute " + expected + " for " + plan.axis() + " plan");
	}

	private static double elapsedMilliseconds(long start, long end) {
		long elapsedNanos = end - start;
		if (elapsedNanos < 0L) throw new IllegalStateException("monotonic clock moved backwards");
		return elapsedNanos / 1_000_000.0;
	}

	private static void validateElapsed(double milliseconds) {
		if (!(Double.isFinite(milliseconds) && milliseconds >= 0.0)) {
			throw new IllegalArgumentException("step duration must be finite and non-negative");
		}
	}
}
