package mmm.simulation;

import mmm.fluid.CpuFluidSolverBackend;
import mmm.fluid.FluidDomainState;
import mmm.fluid.FluidDomainStateAssertions;
import mmm.fluid.FluidSolverBackend;
import mmm.fluid.FluidStepCandidate;
import mmm.fluid.FluidStepRequest;
import mmm.fluid.FluidThermalStepCandidate;
import mmm.fluid.FluidThermalStepRequest;
import mmm.fluid.FluidDomain;
import mmm.thermal.ThermalLimits;
import mmm.world.MovingBodyFluidCoupling;

import java.util.List;

/**
 * Characterization for the complete backend handoff: capture, restore and candidate states must
 * round-trip with exact raw bits. Structural refactors of the solver state owners must keep
 * these parities; they run inside the existing lifecycle gate.
 */
public final class FluidDomainStateParityValidation {
	private FluidDomainStateParityValidation() { }

	public static void main(String[] args) throws Exception {
		captureRestoreCapturePreservesRawBits();
		customViscositySurvivesHandoff();
		phaseOffCandidateRestoresToIdenticalState();
		phaseOnCandidateRestoresToIdenticalState();
		thermalOnlyCandidateIsPrivateAndRestorable();
		privateCandidateAssertionIsTypedAndLeavesLiveStateUntouched();
		entityTargetLifecycleSurvivesCandidateAndCheckpoint();
		System.out.println("Fluid domain state parity characterization passed.");
	}

	private static void customViscositySurvivesHandoff() throws Exception {
		var original = FullCapabilityTestFixture.load();
		var definitions = FullCapabilityTestFixture.doubledWaterViscosity();
		var registries = new FullCapabilityTestFixture.Registries(original.liquids(), original.chemical(),
				original.granular(), original.gas(), original.thermal(), definitions);
		FluidDomain domain = ProductionFluidReplayValidation.createFullyBoundHalfFilledDomain(registries);
		FluidDomainState input = domain.captureAuthoritativeState();
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(input);
		FluidDomainStateAssertions.assertSameState(input, restored.captureAuthoritativeState(), "custom viscosity handoff");
		if (!domain.authoritativeLayout().equals(input.layout()) || !restored.authoritativeLayout().equals(input.layout())) {
			throw new AssertionError("material definition layout identity changed during handoff");
		}
		boolean observed = false;
		for (int cell = 0; cell < domain.sizeX() * domain.sizeY() * domain.sizeZ(); cell++) {
			Double before = domain.carrierViscosityPascalSeconds(cell), after = restored.carrierViscosityPascalSeconds(cell);
			if (!java.util.Objects.equals(before, after)) throw new AssertionError("restored carrier viscosity changed");
			observed |= before != null;
		}
		if (!observed) throw new AssertionError("custom viscosity handoff fixture has no liquid");
		var backend = new CpuFluidSolverBackend();
		var first = backend.evaluate(new FluidStepRequest(0, input, List.of(), false, 0, false));
		var second = backend.evaluate(new FluidStepRequest(0, restored.captureAuthoritativeState(), List.of(), false, 0, false));
		FluidDomainStateAssertions.assertSameState(first.state(), second.state(), "custom viscosity next step");
	}

	/** The complete entity target replaces the accepted table only with an accepted private successor. */
	private static void entityTargetLifecycleSurvivesCandidateAndCheckpoint() throws Exception {
		var domain = new FluidDomain(4, 4, 4, new mmm.fluid.FluidSolverConfig(.5F, 1000, 0, .025F, 120, 1e-5F, .002F));
		domain.sealDomainBoundary();
		float[] fill = new float[64];
		for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) fill[x + 4 * (4 * z)] = .25F;
		domain.setVolumes(fill);
		var original = domain.captureAuthoritativeState();
		var backend = new CpuFluidSolverBackend();
		var id = new java.util.UUID(101, 211);
		var first = new MovingBodyFluidCoupling.Request(id,
				new MovingBodyFluidCoupling.Bounds(.6, .05, .6, .7, .15, .7), 0, 0, 0);
		var entered = backend.evaluate(new FluidStepRequest(0, original, List.of(first), false, 0));
		FluidDomainStateAssertions.assertSameState(original, domain.captureAuthoritativeState(), "entity entry changed source before adoption");
		if (!entered.state().entityBodyState().bodies().equals(List.of(first))) throw new AssertionError("Entity entry lost its complete target");
		var accepted = entered.claimPreparedCpuDomain();
		try {
			entered.claimPreparedCpuDomain();
			throw new AssertionError("Entity candidate was adopted twice");
		} catch (IllegalStateException expected) { }
		var movedBody = new MovingBodyFluidCoupling.Request(id,
				new MovingBodyFluidCoupling.Bounds(.7, .05, .6, .8, .15, .7), .2, 0, 0);
		var beforeMove = accepted.captureAuthoritativeState();
		var moved = backend.evaluate(new FluidStepRequest(1, beforeMove, List.of(movedBody), false, 0));
		FluidDomainStateAssertions.assertSameState(beforeMove, accepted.captureAuthoritativeState(), "entity movement changed accepted source");
		if (!moved.state().entityBodyState().bodies().equals(List.of(movedBody))) throw new AssertionError("Entity movement retained its previous target");
		var movedDomain = FluidDomain.restoreAuthoritativeState(moved.state());
		var settled = backend.evaluate(FluidStepRequest.pendingPhaseSettlement(2, moved.state()));
		var settledBody = settled.state().entityBodyState().bodies().getFirst();
		if (!settledBody.bodyId().equals(id) || !settledBody.bounds().equals(movedBody.bounds())
				|| settledBody.velocityXMetresPerSecond() != 0 || settledBody.velocityYMetresPerSecond() != 0
				|| settledBody.velocityZMetresPerSecond() != 0 || !settled.movingBodyCoupling().equals(MovingBodyFluidCoupling.Result.NONE))
			throw new AssertionError("Phase settlement moved an accepted body or delivered its response twice");
		var origin = net.minecraft.core.BlockPos.ZERO;
		var controller = new net.minecraft.core.BlockPos(-1, 0, 0);
		var interior = new java.util.HashSet<Long>();
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++)
			interior.add(new net.minecraft.core.BlockPos(x, y, z).asLong());
		var footprint = new mmm.simulation.controller.FluidRegionFootprint(controller, origin,
				new net.minecraft.core.BlockPos(1, 1, 1), interior, java.util.Set.of(controller.asLong()), java.util.Set.of(0L));
		net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<net.minecraft.world.level.Level>> dimensionRegistry =
				net.minecraft.resources.ResourceKey.createRegistryKey(net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm", "entity_parity_registry"));
		var dimension = net.minecraft.resources.ResourceKey.create(dimensionRegistry,
				net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm", "entity_parity"));
		var region = new FluidRegion(new java.util.UUID(223, 227), dimension, origin, movedDomain, footprint);
		var checkpoint = RegionCheckpointFixtures.capture(region, 1);
		checkpoint.validate();
		var fromDisk = new mmm.simulation.persistence.RegionCheckpointRestorer(checkpoint.checkpoint())
				.reconstructAuthoritativeState(null, null, null, moved.state().gasRegistry(), null);
		FluidDomainStateAssertions.assertSameDurableState(moved.state(), fromDisk, "entity target disk recovery");
		var nextLive = backend.evaluate(new FluidStepRequest(2, moved.state(), List.of(movedBody), false, 0));
		var nextDisk = backend.evaluate(new FluidStepRequest(2, fromDisk, List.of(movedBody), false, 0));
		FluidDomainStateAssertions.assertSameDurableState(nextLive.state(), nextDisk.state(), "entity disk recovery successor");
		var departed = backend.evaluate(new FluidStepRequest(3, nextDisk.state(), List.of(), false, 0));
		if (!departed.state().entityBodyState().bodies().isEmpty() || !departed.movingBodyCoupling().bodyImpulses().isEmpty())
			throw new AssertionError("Departed entity retained occupancy or received a delayed impulse");
		var originalVolume = original.liquid().stocks().stream().map(mmm.inventory.LiquidCellInventory.Stock::volume)
				.reduce(mmm.units.SignedDyadic.ZERO, mmm.units.SignedDyadic::add);
		var finalVolume = departed.state().liquid().stocks().stream().map(mmm.inventory.LiquidCellInventory.Stock::volume)
				.reduce(mmm.units.SignedDyadic.ZERO, mmm.units.SignedDyadic::add);
		if (!finalVolume.equals(originalVolume)
				|| departed.state().liquid().stocks().stream().mapToLong(mmm.inventory.LiquidCellInventory.Stock::massUnits).sum()
				!= original.liquid().stocks().stream().mapToLong(mmm.inventory.LiquidCellInventory.Stock::massUnits).sum())
			throw new AssertionError("Entity enter/move/depart changed material inventory");
		var tooMany = new java.util.ArrayList<MovingBodyFluidCoupling.Request>();
		for (int i = 0; i <= MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP; i++)
			tooMany.add(new MovingBodyFluidCoupling.Request(new java.util.UUID(239, i), first.bounds(), 0, 0, 0));
		try {
			backend.evaluate(new FluidStepRequest(4, departed.state(), tooMany, false, 0));
			throw new AssertionError("Oversized complete body target was partially applied");
		} catch (IllegalArgumentException expected) { }
		var full = new FluidDomain(4, 4, 4, domain.config());
		full.sealDomainBoundary();
		java.util.Arrays.fill(fill, 1); full.setVolumes(fill);
		var fullBefore = full.captureAuthoritativeState();
		boolean rejected = false;
		try {
			backend.evaluate(new FluidStepRequest(0, fullBefore, List.of(first), false, 0));
		} catch (RuntimeException unavailable) { rejected = true; }
		if (!rejected) throw new AssertionError("Entity insertion ignored a sealed full domain's physical capacity");
		FluidDomainStateAssertions.assertSameState(fullBefore, full.captureAuthoritativeState(), "rejected entity insertion changed source");
	}

	/** An unforeseen private-candidate assertion must pause one region, never escape as a server crash. */
	private static void privateCandidateAssertionIsTypedAndLeavesLiveStateUntouched() throws Exception {
		FullCapabilityTestFixture.Registries registries = ProductionFluidReplayValidation.loadProductionRegistries();
		FluidDomain live = ProductionFluidReplayValidation.createFullyBoundHalfFilledDomain(registries);
		FluidDomainState base = live.captureAuthoritativeState();
		boolean typed = false;
		try {
			new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0L, base, List.of(
					MovingBodyFluidCoupling.prescribedCube(0.5F, 0.5F, 0.5F,
							Double.MAX_VALUE, 0.0, 0.0, 0.01)), false, 0, false));
		} catch (FluidSolverBackend.CandidateEvaluationRejectedException | FluidDomain.PressureSolveBlockedException expected) {
			typed = true;
		}
		if (!typed) throw new AssertionError("private candidate assertion escaped the typed rejection boundary");
		FluidDomainStateAssertions.assertSameState(base, live.captureAuthoritativeState(),
				"rejected private candidate mutated live state");
	}

	/** capture -> restore -> capture must reproduce every authoritative field bit-for-bit. */
	private static void captureRestoreCapturePreservesRawBits() throws Exception {
		FullCapabilityTestFixture.Registries registries = ProductionFluidReplayValidation.loadProductionRegistries();
		FluidDomain domain = ProductionFluidReplayValidation.createFullyBoundHalfFilledDomain(registries);
		var key = domain.captureAuthoritativeState().liquid().stocks().stream()
				.filter(stock -> domain.materialRegistry().material(stock.key().material()).id().equals("mmm:water"))
				.findFirst().orElseThrow().key();
		domain.chemistry().restoreMassTransferPending(List.of(new mmm.chemistry.RegionChemistry.MassTransferPending(
				key.cell(), key.material(), key.supportMask(), "mmm:co2_water", .125, -2.5e-7)));
		domain.restoreEntityBodyState(new mmm.fluid.EntityBodyState(List.of(
				new mmm.world.MovingBodyFluidCoupling.Request(new java.util.UUID(29, 47),
						new mmm.world.MovingBodyFluidCoupling.Bounds(-2, -2, -2, -1.75, -1.5, -1.25), .125, -.25, .5))));
		FluidDomainState captured = domain.captureAuthoritativeState();
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(captured);
		FluidDomainState recaptured = restored.captureAuthoritativeState();
		FluidDomainStateAssertions.assertSameState(captured, recaptured, "capture/restore/capture");
	}

	/** A phase-off backend candidate restored into a fresh domain must re-capture to the same bits. */
	private static void phaseOffCandidateRestoresToIdenticalState() {
		FullCapabilityTestFixture.Registries registries = null;
		try {
			registries = ProductionFluidReplayValidation.loadProductionRegistries();
		} catch (Exception failure) {
			throw new IllegalStateException("production registries failed to load", failure);
		}
		FluidDomain domain = ProductionFluidReplayValidation.createFullyBoundPlanarHalfFill(registries);
		FluidDomainState base = domain.captureAuthoritativeState();
		FluidStepCandidate candidate = new CpuFluidSolverBackend().evaluate(
				new FluidStepRequest(0L, base, List.of(), false, 0, true));
		var resolution = candidate.mechanicalResolution();
		if (resolution.reservedSubsteps() < candidate.stats().substeps()
				|| resolution.reservedSubsteps() > CpuFluidSolverBackend.MAXIMUM_CUMULATIVE_SUBSTEPS
				|| (resolution.timeRefinements() == 0) != resolution.lastRefinementReason().isEmpty()
				|| resolution.timeRefinements() == 0 && resolution.reservedSubsteps() != candidate.stats().substeps()) {
			throw new AssertionError("CPU candidate lost its actual time-refinement budget accounting");
		}
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(candidate.state());
		FluidDomainStateAssertions.assertSameState(
				candidate.state(), restored.captureAuthoritativeState(), "phase-off candidate/restore/capture");
	}

	/** The same round trip with the full phase-on pipeline enabled. */
	private static void phaseOnCandidateRestoresToIdenticalState() throws Exception {
		FullCapabilityTestFixture.Registries registries = ProductionFluidReplayValidation.loadProductionRegistries();
		FluidDomain domain = ProductionFluidReplayValidation.createFullyBoundHalfFilledDomain(registries);
		FluidDomainState base = domain.captureAuthoritativeState();
		FluidStepCandidate candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0L, base, List.of(), true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP, false));
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(candidate.state());
		FluidDomainStateAssertions.assertSameState(
				candidate.state(), restored.captureAuthoritativeState(), "phase-on candidate/restore/capture");
	}

	/** Hydrostatic thermal work must stay on a private backend domain until its whole successor exists. */
	private static void thermalOnlyCandidateIsPrivateAndRestorable() throws Exception {
		FullCapabilityTestFixture.Registries registries = ProductionFluidReplayValidation.loadProductionRegistries();
		FluidDomain live = ProductionFluidReplayValidation.createFullyBoundHalfFilledDomain(registries);
		FluidDomainState base = live.captureAuthoritativeState();
		FluidThermalStepCandidate candidate = new CpuFluidSolverBackend().evaluateThermal(
				new FluidThermalStepRequest(
						0L, base, true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));
		FluidDomainStateAssertions.assertSameState(
				base, live.captureAuthoritativeState(), "thermal-only backend mutated live input");
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(candidate.state());
		FluidDomainStateAssertions.assertSameState(
				candidate.state(), restored.captureAuthoritativeState(),
				"thermal-only candidate/restore/capture");
	}
}

