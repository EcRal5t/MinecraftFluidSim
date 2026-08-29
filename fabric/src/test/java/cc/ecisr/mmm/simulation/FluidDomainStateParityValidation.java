package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidDomainState;
import cc.ecisr.mmm.fluid.FluidDomainStateAssertions;
import cc.ecisr.mmm.fluid.FluidStepCandidate;
import cc.ecisr.mmm.fluid.FluidStepRequest;
import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.thermal.ThermalLimits;

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
		phaseOffCandidateRestoresToIdenticalState();
		phaseOnCandidateRestoresToIdenticalState();
		System.out.println("Fluid domain state parity characterization passed.");
	}

	/** capture -> restore -> capture must reproduce every authoritative field bit-for-bit. */
	private static void captureRestoreCapturePreservesRawBits() throws Exception {
		ProductionFluidReplayValidation.RegistrySet registries = ProductionFluidReplayValidation.loadProductionRegistries();
		FlipFluidDomain domain = ProductionFluidReplayValidation.createFullyBoundHalfFilledDomain(registries);
		FluidDomainState captured = domain.captureAuthoritativeState();
		FlipFluidDomain restored = FlipFluidDomain.restoreAuthoritativeState(captured);
		FluidDomainState recaptured = restored.captureAuthoritativeState();
		FluidDomainStateAssertions.assertSameState(captured, recaptured, "capture/restore/capture");
	}

	/** A phase-off backend candidate restored into a fresh domain must re-capture to the same bits. */
	private static void phaseOffCandidateRestoresToIdenticalState() {
		ProductionFluidReplayValidation.RegistrySet registries = null;
		try {
			registries = ProductionFluidReplayValidation.loadProductionRegistries();
		} catch (Exception failure) {
			throw new IllegalStateException("production registries failed to load", failure);
		}
		FlipFluidDomain domain = ProductionFluidReplayValidation.createFullyBoundPlanarHalfFill(registries);
		FluidDomainState base = domain.captureAuthoritativeState();
		FluidStepCandidate candidate = new CpuFluidSolverBackend().evaluate(
				new FluidStepRequest(0L, base, List.of(), false, 0, true));
		FlipFluidDomain restored = FlipFluidDomain.restoreAuthoritativeState(candidate.state());
		FluidDomainStateAssertions.assertSameState(
				candidate.state(), restored.captureAuthoritativeState(), "phase-off candidate/restore/capture");
	}

	/** The same round trip with the full phase-on pipeline enabled. */
	private static void phaseOnCandidateRestoresToIdenticalState() throws Exception {
		ProductionFluidReplayValidation.RegistrySet registries = ProductionFluidReplayValidation.loadProductionRegistries();
		FlipFluidDomain domain = ProductionFluidReplayValidation.createFullyBoundHalfFilledDomain(registries);
		FluidDomainState base = domain.captureAuthoritativeState();
		FluidStepCandidate candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0L, base, List.of(), true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP, false));
		FlipFluidDomain restored = FlipFluidDomain.restoreAuthoritativeState(candidate.state());
		FluidDomainStateAssertions.assertSameState(
				candidate.state(), restored.captureAuthoritativeState(), "phase-on candidate/restore/capture");
	}
}
