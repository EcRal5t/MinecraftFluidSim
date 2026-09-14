package cc.ecisr.mmm.fluid;

/** A posteriori motion or nonlinear solve failure; retry the unchanged authoritative request. */
final class MechanicalTimeRefinement extends RuntimeException {
	final int minimumSubsteps;

	MechanicalTimeRefinement(int minimumSubsteps) {
		this(minimumSubsteps, "Projected motion");
	}

	MechanicalTimeRefinement(int minimumSubsteps, String reason) {
		super(reason + " requires at least " + minimumSubsteps + " mechanical substeps");
		this.minimumSubsteps = minimumSubsteps;
	}
}
