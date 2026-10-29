package mmm.fluid;

/** Work limits shared by solver implementations for one complete candidate. */
public final class FluidStepBudget {
	public static final int MAXIMUM_TIME_REFINEMENTS = 16;
	public static final long MAXIMUM_CUMULATIVE_SUBSTEPS = 4096L;

	private FluidStepBudget() { }
}
