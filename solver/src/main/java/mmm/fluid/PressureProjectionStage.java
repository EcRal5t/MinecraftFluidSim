package mmm.fluid;

/** The single projection entry for full liquid capacity, lumped gas storage and solid coupling. */
final class PressureProjectionStage {
	private final FluidDomain domain;
	private final PressureSystem system;
	PressureProjectionStage(FluidDomain domain, PressureSystem system) { this.domain = domain; this.system = system; }
	void solve(float dt) {
		var result = system.solveProjection(dt);
		domain.lastPressureIterations = result.iterations(); domain.lastPressureResidual = (float) result.residualRms();
		if (!result.converged()) throw new FluidDomain.PressureSolveBlockedException("Pressure projection did not converge: residual="
				+ result.residualRms() + ", iterations=" + result.iterations());
	}
	void predictDrag(float dt){system.predictDrag(dt);}
	double wallBoundaryWork() { return system.wallBoundaryWork(); }
	void projectVelocities(float dt) { system.projectVelocities(dt); }
}
