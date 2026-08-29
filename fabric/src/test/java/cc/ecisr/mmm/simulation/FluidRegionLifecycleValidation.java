package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.fluid.FluidStepCandidate;
import cc.ecisr.mmm.fluid.FluidStepRequest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/** Deterministic lifecycle transition gate independent of Minecraft chunks and wall-clock timing. */
public final class FluidRegionLifecycleValidation {
	private static final ResourceKey<Registry<Level>> DIMENSION_REGISTRY = ResourceKey.createRegistryKey(
			Identifier.fromNamespaceAndPath("mmm", "lifecycle_validation_dimension_registry"));
	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
			DIMENSION_REGISTRY, Identifier.fromNamespaceAndPath("mmm", "lifecycle_validation_dimension"));

	private FluidRegionLifecycleValidation() { }

	public static void main(String[] args) {
		validatePacingAndOperatorPause();
		validateMotionInputConsumptionSchedule();
		validateAvailabilityPreservesRunIntent();
		validateHydrostaticSleepAndWake();
		validateHydrostaticSleepPreservesMarkerLedger();
		validateBackendCandidateAdoption();
		validateUnloadRequiresPublishedCheckpointAndReleasedSolver();
		System.out.println("FluidRegionLifecycle validation passed");
	}

	/** The live solver is only replaced by a complete, adjacent candidate from the expected backend. */
	private static void validateBackendCandidateAdoption() {
		FluidSolverConfig config = FluidSolverConfig.prototypeDefaults();
		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, config);
		domain.setVolume(1, 1, 1, 0.5F);
		FluidRegion region = new FluidRegion(new UUID(19L, 23L), DIMENSION, BlockPos.ZERO,
				domain, domain.maximumParticleCount());
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		FluidStepCandidate first = backend.evaluate(new FluidStepRequest(
				region.solverGeneration(), region.domain().captureAuthoritativeState(), List.of(), false, 0));
		region.adoptStepCandidate(first, backend.id());
		require(region.solverGeneration() == 1L && region.domain() != domain,
				"valid backend candidate did not atomically replace the live solver");

		FlipFluidDomain committed = region.domain();
		boolean staleRejected = false;
		try {
			region.adoptStepCandidate(first, backend.id());
		} catch (IllegalStateException expected) {
			staleRejected = true;
		}
		require(staleRejected && region.domain() == committed && region.solverGeneration() == 1L,
				"stale backend candidate changed the committed solver");

		FluidStepCandidate second = backend.evaluate(new FluidStepRequest(
				region.solverGeneration(), region.domain().captureAuthoritativeState(), List.of(), false, 0));
		boolean backendRejected = false;
		try {
			region.adoptStepCandidate(second, "mmm:unexpected");
		} catch (IllegalStateException expected) {
			backendRejected = true;
		}
		require(backendRejected && region.domain() == committed && region.solverGeneration() == 1L,
				"backend identity mismatch changed the committed solver");

		region.adoptStepCandidate(second, backend.id());
		require(region.solverGeneration() == 2L, "adjacent backend candidate did not advance the generation");
	}

	private static void validateMotionInputConsumptionSchedule() {
		require(!FluidRegionLifecycle.consumesMotionInput(false, 4L, 2),
				"paused motion consumed a frozen entity input");
		require(!FluidRegionLifecycle.consumesMotionInput(true, 3L, 2),
				"a skipped game tick consumed a frozen entity input");
		require(FluidRegionLifecycle.consumesMotionInput(true, 4L, 2),
				"a due logical motion step did not consume its frozen entity input");
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

	private static void validateAvailabilityPreservesRunIntent() {
		FluidRegionLifecycle lifecycle = running();
		lifecycle.blockAvailability(RegionAvailabilityReason.CHUNK_MISSING, "required chunk missing");
		require(!lifecycle.canStep() && !lifecycle.operatorPaused()
				&& lifecycle.availabilityReason() == RegionAvailabilityReason.CHUNK_MISSING,
				"availability failure overwrote the running intent");
		lifecycle.clearAvailabilityBlock();
		require(lifecycle.canStep() && lifecycle.availabilityReason() == null,
				"availability recovery did not restore the running intent");
		lifecycle.setOperatorPaused(true, 1);
		lifecycle.blockAvailability(RegionAvailabilityReason.BOUNDARY_OPEN, "required boundary missing");
		lifecycle.clearAvailabilityBlock();
		require(!lifecycle.canStep() && lifecycle.operatorPaused(),
				"availability recovery resumed an operator-paused region");
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
		require(lifecycle.state() == FluidRegionLifecycle.State.UNLOADED && !lifecycle.canStep()
				&& !lifecycle.operatorPaused(), "UNLOADED lifecycle lost its running intent");
		lifecycle.restoreFromUnloaded(FluidRegionLifecycle.State.ACTIVE, 1);
		require(lifecycle.canStep(), "restored lifecycle did not resume its persisted running intent");
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
