package cc.ecisr.mmm.fluid;

/** A posteriori Courant failure; the backend retries from the unchanged authoritative request. */
final class MechanicalTimeRefinement extends RuntimeException {
	final int minimumSubsteps;

	MechanicalTimeRefinement(int minimumSubsteps) {
		super("Projected motion requires at least " + minimumSubsteps + " mechanical substeps");
		this.minimumSubsteps = minimumSubsteps;
	}
}
