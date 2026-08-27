package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.controller.ControllerSummary;
import cc.ecisr.mmm.controller.LifecycleOperation;
import cc.ecisr.mmm.controller.LifecycleOperationPreview;
import cc.ecisr.mmm.controller.LifecycleOperationSessions;
import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidStepCandidate;
import cc.ecisr.mmm.fluid.FluidStepRequest;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
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
		validateAvailabilityPreservesRunIntent();
		validateHydrostaticSleepAndWake();
		validateHydrostaticSleepPreservesMarkerLedger();
		validateBackendCandidateAdoption();
		validateUnloadRequiresPublishedCheckpointAndReleasedSolver();
		validateMetadataOnlySummaryCannotInventInventory();
		validateLifecyclePreviewTokensBindEveryCommitGate();
		System.out.println("FluidRegionLifecycle validation passed");
	}

	private static void validateLifecyclePreviewTokensBindEveryCommitGate() {
		UUID player = new UUID(1L, 2L), otherPlayer = new UUID(3L, 4L), region = new UUID(5L, 6L);
		LifecycleOperationSessions sessions = new LifecycleOperationSessions();
		LifecycleOperationSessions.Context context = lifecycleContext(player, region, 7L,
				LifecycleOperation.EMPTY_CONDENSED, false, "READY", 100L);
		LifecycleOperationPreview preview = sessions.issue(context, 140L,
				LifecycleOperationPreview.Removal.none(), LifecycleOperationPreview.GasState.none(), 9, java.util.List.of());
		require(preview.ready() && preview.ownershipBlocks() == 9,
				"server lifecycle preview lost its bounded removal/ownership summary");
		require(!sessions.consume(preview.nonce(), lifecycleContext(otherPlayer, region, 7L,
				LifecycleOperation.EMPTY_CONDENSED, false, "READY", 101L)),
				"another player consumed a lifecycle preview token");
		require(sessions.consume(preview.nonce(), lifecycleContext(player, region, 7L,
				LifecycleOperation.EMPTY_CONDENSED, false, "READY", 101L))
				&& !sessions.consume(preview.nonce(), context),
				"valid lifecycle token did not consume exactly once");

		for (LifecycleOperationSessions.Context changed : java.util.List.of(
				lifecycleContext(player, region, 8L, LifecycleOperation.EMPTY_CONDENSED, false, "READY", 101L),
				lifecycleContext(player, region, 7L, LifecycleOperation.VENT_SEALED_GAS, false, "READY", 101L),
				new LifecycleOperationSessions.Context(player, region, 7L,
						LifecycleOperation.EMPTY_CONDENSED,
						LifecycleOperationPreview.PermissionDecision.BUILTIN_ADMIN_DENIED,
						false, "READY", 101L),
				lifecycleContext(player, region, 7L, LifecycleOperation.EMPTY_CONDENSED, true, "READY", 101L),
				lifecycleContext(player, region, 7L, LifecycleOperation.EMPTY_CONDENSED, false, "CHUNK_MISSING", 101L),
				lifecycleContext(player, region, 7L, LifecycleOperation.EMPTY_CONDENSED, false, "READY", 141L))) {
			LifecycleOperationPreview stale = sessions.issue(context, 140L,
					LifecycleOperationPreview.Removal.none(), LifecycleOperationPreview.GasState.none(), 0, java.util.List.of());
			require(!sessions.consume(stale.nonce(), changed),
					"lifecycle preview survived a generation/action/WAL/availability/expiry change");
			require(!sessions.consume(stale.nonce(), context),
					"invalidated lifecycle preview token was reusable");
		}
	}

	private static LifecycleOperationSessions.Context lifecycleContext(
			UUID player, UUID region, long generation, LifecycleOperation operation,
			boolean pendingWal, String availability, long tick
	) {
		return new LifecycleOperationSessions.Context(player, region, generation, operation,
				LifecycleOperationPreview.PermissionDecision.BUILTIN_ADMIN_GRANTED,
				pendingWal, availability, tick);
	}

	private static void validateMetadataOnlySummaryCannotInventInventory() {
		ControllerSummary.Inventory unavailable = ControllerSummary.Inventory.unavailable();
		require(!unavailable.live() && !unavailable.internalEnergyAvailable()
				&& Double.isNaN(unavailable.totalInternalEnergyEu())
				&& !unavailable.temperatureRange().available(),
				"metadata-only controller inventory did not remain explicitly unavailable");
		boolean rejected = false;
		try {
			new ControllerSummary.Inventory(false, 1.0, 0.0, 0L, 0L, 0.0,
					0L, 0.0, 0L, 0.0, false, Double.NaN,
					ControllerSummary.TemperatureRange.unavailable());
		} catch (IllegalArgumentException expected) {
			rejected = true;
		}
		require(rejected, "metadata-only controller inventory accepted invented live volume");
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

	private static void validateBackendCandidateAdoption() {
		FluidSolverConfig config = FluidSolverConfig.prototypeDefaults();
		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, config);
		FluidRegion region = new FluidRegion(new UUID(19L, 23L), DIMENSION, BlockPos.ZERO,
				domain, domain.maximumParticleCount());
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		FluidStepCandidate first = backend.evaluate(new FluidStepRequest(
				region.solverGeneration(), region.domain().captureAuthoritativeState(), false, 0));
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
				region.solverGeneration(), region.domain().captureAuthoritativeState(), false, 0));
		boolean backendRejected = false;
		try {
			region.adoptStepCandidate(second, "mmm:unexpected");
		} catch (IllegalStateException expected) {
			backendRejected = true;
		}
		require(backendRejected && region.domain() == committed && region.solverGeneration() == 1L,
				"backend identity mismatch changed the committed solver");

		FluidRegion.PreparedStepCandidate prepared = region.prepareStepCandidate(second, backend.id());
		require(region.domain() == committed && region.solverGeneration() == 1L,
				"candidate preparation changed the committed solver before entity revalidation");
		region.commitPreparedStepCandidate(prepared);
		require(region.domain() == prepared.replacement() && region.solverGeneration() == 2L,
				"prepared candidate did not commit as one generation");
		region.rollbackPreparedStepCandidate(prepared);
		require(region.domain() == committed && region.solverGeneration() == 1L,
				"external participant failure did not restore the exact base solver generation");
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
