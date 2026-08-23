package com.example.simulation;

import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FlipParticleState;
import com.example.fluid.FluidSolverConfig;

/** Deterministic lifecycle transition gate independent of Minecraft chunks and wall-clock timing. */
public final class FluidRegionLifecycleValidation {
	private FluidRegionLifecycleValidation() { }

	public static void main(String[] args) {
		validatePacingAndOperatorPause();
		validateAvailabilityRequiresExplicitResume();
		validateHydrostaticSleepAndWake();
		validateHydrostaticSleepPreservesMarkerLedger();
		validateUnloadRequiresPublishedCheckpointAndReleasedSolver();
		System.out.println("FluidRegionLifecycle validation passed");
	}

	private static void validateHydrostaticSleepPreservesMarkerLedger() {
		FluidSolverConfig config = FluidSolverConfig.prototypeDefaults();
		FlipFluidDomain domain = new FlipFluidDomain(6, 5, 4, config);
		domain.restoreParticleState(new FlipParticleState(
				6, 5, 4, config.cellSize(), 2, 0.1875F,
				new float[] {
						0.25F, 0.25F, 0.25F, 0.125F,
						1.25F, 0.75F, 0.75F, 0.0625F
				},
				new float[] {0.5F, -0.25F, 0.125F, -0.125F, 0.25F, -0.5F},
				new byte[6 * 5 * 4]
		));
		double volume = domain.totalVolumeExact();
		double mass = domain.totalMass();
		int markers = domain.particleCount();
		domain.commitHydrostaticSleep();
		require(domain.maximumParticleSpeed() == 0.0F, "hydrostatic sleep left marker momentum");
		require(domain.particleCount() == markers && domain.totalVolumeExact() == volume && domain.totalMass() == mass,
				"hydrostatic sleep changed marker ledgers");
	}

	private static void validatePacingAndOperatorPause() {
		FluidRegionLifecycle lifecycle = new FluidRegionLifecycle();
		require(lifecycle.state() == FluidRegionLifecycle.State.ACTIVE && !lifecycle.canStep(),
				"new lifecycle must start operator-paused in ACTIVE scheduling state");
		lifecycle.setOperatorPaused(false, 1);
		require(lifecycle.canStep(), "run did not enable ACTIVE stepping");
		lifecycle.onStepIntervalChanged(3);
		require(lifecycle.state() == FluidRegionLifecycle.State.REDUCED && lifecycle.canStep(),
				"reduced pacing changed operator intent");
		lifecycle.setOperatorPaused(true, 3);
		require(!lifecycle.canStep() && lifecycle.state() == FluidRegionLifecycle.State.REDUCED,
				"operator pause was confused with scheduling state");
	}

	private static void validateAvailabilityRequiresExplicitResume() {
		FluidRegionLifecycle lifecycle = running();
		lifecycle.blockAvailability("required chunk missing");
		require(!lifecycle.canStep() && lifecycle.operatorPaused(), "availability failure did not freeze the whole region");
		lifecycle.clearAvailabilityBlock();
		require(!lifecycle.canStep(), "availability recovery silently resumed a previously partial region");
		lifecycle.setOperatorPaused(false, 1);
		require(lifecycle.canStep(), "explicit resume after availability recovery failed");
	}

	private static void validateHydrostaticSleepAndWake() {
		FluidRegionLifecycle lifecycle = running();
		require(!lifecycle.observeCompletedStep(true, 3), "sleep began before the quiet window");
		require(!lifecycle.observeCompletedStep(true, 3), "sleep began before the complete quiet window");
		require(lifecycle.observeCompletedStep(true, 3), "quiet window did not enter hydrostatic sleep");
		require(lifecycle.state() == FluidRegionLifecycle.State.HYDROSTATIC_SLEEP && !lifecycle.canStep(),
				"hydrostatic sleep still permits stepping");
		lifecycle.wakeForActivity(2);
		require(lifecycle.state() == FluidRegionLifecycle.State.REDUCED && lifecycle.canStep()
				&& lifecycle.quietCompletedSteps() == 0, "activity did not wake into current pacing state");
		lifecycle.observeCompletedStep(true, 3);
		lifecycle.observeCompletedStep(false, 3);
		require(lifecycle.quietCompletedSteps() == 0, "non-quiet step did not reset sleep evidence");
	}

	private static void validateUnloadRequiresPublishedCheckpointAndReleasedSolver() {
		FluidRegionLifecycle lifecycle = running();
		require(!lifecycle.requestUnload(false, true), "unpublished state entered UNLOADED");
		require(!lifecycle.requestUnload(true, false), "live solver entered UNLOADED");
		require(lifecycle.state() == FluidRegionLifecycle.State.ACTIVE && lifecycle.canStep(),
				"refused unload changed live scheduling state");
		require(lifecycle.requestUnload(true, true), "complete checkpoint/release transition was rejected");
		require(lifecycle.state() == FluidRegionLifecycle.State.UNLOADED && !lifecycle.canStep(),
				"UNLOADED lifecycle remained runnable");
		lifecycle.setOperatorPaused(false, 1);
		require(lifecycle.operatorPaused() && !lifecycle.canStep(), "run bypassed the UNLOADED restore gate");
	}

	private static FluidRegionLifecycle running() {
		FluidRegionLifecycle lifecycle = new FluidRegionLifecycle();
		lifecycle.setOperatorPaused(false, 1);
		return lifecycle;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
