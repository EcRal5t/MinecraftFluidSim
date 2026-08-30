package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidDomainState;
import cc.ecisr.mmm.fluid.FluidDomainStateAssertions;
import cc.ecisr.mmm.fluid.FluidStepCandidate;
import cc.ecisr.mmm.fluid.FluidStepRequest;
import cc.ecisr.mmm.fluid.FluidThermalStepCandidate;
import cc.ecisr.mmm.fluid.FluidThermalStepRequest;
import cc.ecisr.mmm.fluid.FluidDomain;
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
		thermalOnlyCandidateIsPrivateAndRestorable();
		System.out.println("Fluid domain state parity characterization passed.");
	}

	/** capture -> restore -> capture must reproduce every authoritative field bit-for-bit. */
	private static void captureRestoreCapturePreservesRawBits() throws Exception {
		ProductionFluidReplayValidation.RegistrySet registries = ProductionFluidReplayValidation.loadProductionRegistries();
		FluidDomain domain = ProductionFluidReplayValidation.createFullyBoundHalfFilledDomain(registries);
		FluidDomainState captured = domain.captureAuthoritativeState();
		FluidDomain restored = FluidDomain.restoreAuthoritativeState(captured);
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
		ProductionFluidReplayValidation.RegistrySet registries = ProductionFluidReplayValidation.loadProductionRegistries();
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
		ProductionFluidReplayValidation.RegistrySet registries = ProductionFluidReplayValidation.loadProductionRegistries();
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
