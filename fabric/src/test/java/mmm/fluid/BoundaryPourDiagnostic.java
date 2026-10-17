package mmm.fluid;

import java.util.List;

/** Original boundary pour and L-shaped input through complete backend steps and restoration. */
public final class BoundaryPourDiagnostic {
	private BoundaryPourDiagnostic() { }

	public static void main(String[] args) {
		boolean flux = false, lShape = false; int steps = 4;
		for (String argument : args) {
			if (argument.equals("--flux") && !flux) flux = true;
			else if (argument.equals("--l-shape") && !lShape) lShape = true;
			else if (argument.startsWith("--steps=")) { steps = Integer.parseInt(argument.substring(8)); if (steps < 1) throw new IllegalArgumentException("Positive step count required"); }
			else throw new IllegalArgumentException("Boundary pour accepts --flux and --l-shape");
		}
		var domain = lShape ? CellRemapMomentsValidation.lShapedTranslationSource() : pouredSource();
		var inventory = CellRemapMomentsValidation.Inventory.capture(domain);
		var backend = new CpuFluidSolverBackend(); String fixture = lShape ? "l-shape" : "sealed-pour";
		for (int step = 0; step < steps; step++) {
			long started = System.nanoTime(); var source = domain.captureAuthoritativeState();
			long captured = System.nanoTime(); FluidStepCandidate candidate;
			try { candidate = backend.evaluate(new FluidStepRequest(step, source, List.of(), false, 0, true)); }
			catch (RuntimeException | AssertionError failure) {
				System.out.printf("POUR fixture=%s logical=%d outcome=FAILED fullMs=%.3f sourceVolume=%g sourceMass=%g sourceMarkers=%d gasNanos=%d failure=%s%n",
						fixture, step, (System.nanoTime() - started) / 1e6, domain.totalVolumeExact(), domain.totalMass(),
						domain.particleCount(), domain.gasBoundary().sealedInventoryNanos(), failure);
				throw failure;
			}
			long computed = System.nanoTime(); domain = candidate.claimPreparedCpuDomain();
			domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			double fullMs = (System.nanoTime() - started) / 1e6;
			inventory.requireUnchanged(domain, "POUR " + fixture + " step=" + step);
			System.out.printf("POUR fixture=%s logical=%d outcome=COMPLETE fullMs=%.3f captureMs=%.3f evaluateMs=%.3f substeps=%d reserved=%d pressureIterations=%d residual=%g maxSpeed=%g markers=%d gasNodes=%d K=%g%n",
					fixture, step, fullMs, (captured - started) / 1e6, (computed - captured) / 1e6,
					candidate.stats().substeps(), candidate.mechanicalResolution().reservedSubsteps(), candidate.stats().pressureIterations(),
					(double) candidate.stats().pressureResidual(), (double) domain.maximumParticleSpeed(), domain.particleCount(),
					domain.gasState.network.nodeCount(), CellRemapMomentsValidation.Motion.capture(domain).kinetic());
			if (flux) System.out.println("POUR transport " + candidate.stats().liquidTransport());
		}
		System.out.println("Boundary pour completed " + steps + " full steps including capture/restore: " + fixture);
	}

	static FluidDomain pouredSource() {
		var domain = new FluidDomain(8, 8, 8,
				new FluidSolverConfig(.5F, 1000, 9.81F, .05F, 120, 1e-4F, .40F, .002F, .80F), 4096);
		domain.sealDomainBoundary(); domain.bootstrapGasState(null); domain.setGasNodeStandardVolume(1, 34);
		var addition = domain.addVolumeNearBudgeted(6, 0, 0, 128, 6);
		if (addition.addedVolume() != 128 || addition.markerBudgetLimited()) throw new AssertionError("Original pour input changed");
		return domain;
	}
}
