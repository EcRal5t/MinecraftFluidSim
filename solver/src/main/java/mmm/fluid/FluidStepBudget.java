package mmm.fluid;

/** Work limits shared by solver implementations for one complete candidate. */
public final class FluidStepBudget {
	/** Resolved cell-volume / relative-displacement RMS error over one mechanical step. */
	public static final double MECHANICAL_RESOLUTION_ERROR = 1e-3;
	public static final int MAXIMUM_PRESSURE_ITERATIONS = 16;
	public static final int MAXIMUM_TIME_REFINEMENTS = 16;
	public static final long MAXIMUM_CUMULATIVE_SUBSTEPS = 4096L;

	private FluidStepBudget() { }
}
