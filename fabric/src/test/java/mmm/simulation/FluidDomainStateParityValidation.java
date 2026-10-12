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
			Double before = domain.carrierViscosityPaPerS(cell), after = restored.carrierViscosityPaPerS(cell);
			if (!java.util.Objects.equals(before, after)) throw new AssertionError("restored carrier viscosity changed");
			observed |= before != null;
		}
		if (!observed) throw new AssertionError("custom viscosity handoff fixture has no liquid");
		var backend = new CpuFluidSolverBackend();
		var first = backend.evaluate(new FluidStepRequest(0, input, List.of(), false, 0, false));
		var second = backend.evaluate(new FluidStepRequest(0, restored.captureAuthoritativeState(), List.of(), false, 0, false));
		FluidDomainStateAssertions.assertSameState(first.state(), second.state(), "custom viscosity next step");
	}

	/** An unforeseen private-candidate assertion must pause one region, never escape as a server crash. */
	private static void privateCandidateAssertionIsTypedAndLeavesLiveStateUntouched() throws Exception {
		FullCapabilityTestFixture.Registries registries = ProductionFluidReplayValidation.loadProductionRegistries();
		FluidDomain live = ProductionFluidReplayValidation.createFullyBoundHalfFilledDomain(registries);
		FluidDomainState base = live.captureAuthoritativeState();
		boolean typed = false;
		try {
			new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0L, base, List.of(
					new MovingBodyFluidCoupling.Request(0.5F, 0.5F, 0.5F,
							Double.MAX_VALUE, 0.0, 0.0, 0.01)), false, 0, false));
		} catch (FluidSolverBackend.CandidateEvaluationRejectedException expected) {
			typed = expected.getCause() instanceof IllegalArgumentException;
		}
		if (!typed) throw new AssertionError("private candidate assertion escaped the typed rejection boundary");
		FluidDomainStateAssertions.assertSameState(base, live.captureAuthoritativeState(),
				"rejected private candidate mutated live state");
	}

	/** capture -> restore -> capture must reproduce every authoritative field bit-for-bit. */
	private static void captureRestoreCapturePreservesRawBits() throws Exception {
		FullCapabilityTestFixture.Registries registries = ProductionFluidReplayValidation.loadProductionRegistries();
		FluidDomain domain = ProductionFluidReplayValidation.createFullyBoundHalfFilledDomain(registries);
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
