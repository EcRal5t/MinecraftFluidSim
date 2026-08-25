package cc.ecisr.mmm.world;

/** Loader-neutral moving-body sample and the physical momentum actually committed to liquid markers. */
public final class MovingBodyFluidCoupling {
	public static final int MAXIMUM_REQUESTS_PER_STEP = 256;
	private static final double KERNEL_SUPPORT_CELLS = 1.5;
	private static final double KERNEL_PEAK = 0.75;

	private MovingBodyFluidCoupling() { }

	public record Request(
			float localXMetres,
			float localYMetres,
			float localZMetres,
			double velocityXMetresPerSecond,
			double velocityYMetresPerSecond,
			double velocityZMetresPerSecond,
			double displacedVolumeCubicMetres
	) {
		public Request {
			if (!(Float.isFinite(localXMetres) && Float.isFinite(localYMetres) && Float.isFinite(localZMetres))
					|| !(Double.isFinite(velocityXMetresPerSecond)
					&& Double.isFinite(velocityYMetresPerSecond)
					&& Double.isFinite(velocityZMetresPerSecond))
					|| !(Double.isFinite(displacedVolumeCubicMetres) && displacedVolumeCubicMetres > 0.0)) {
				throw new IllegalArgumentException("Moving-body fluid coupling request is invalid");
			}
		}
	}

	public record Result(
			int coupledMarkers,
			double coupledVolumeCubicMetres,
			double impulseXKilogramMetresPerSecond,
			double impulseYKilogramMetresPerSecond,
			double impulseZKilogramMetresPerSecond
	) {
		public static final Result NONE = new Result(0, 0.0, 0.0, 0.0, 0.0);

		public Result {
			if (coupledMarkers < 0 || !(Double.isFinite(coupledVolumeCubicMetres)
					&& coupledVolumeCubicMetres >= 0.0)
					|| !(Double.isFinite(impulseXKilogramMetresPerSecond)
					&& Double.isFinite(impulseYKilogramMetresPerSecond)
					&& Double.isFinite(impulseZKilogramMetresPerSecond))) {
				throw new IllegalArgumentException("Moving-body fluid coupling result is invalid");
			}
		}
	}

	/** Tensor-product quadratic B-spline with unit peak and compact support of 1.5 cells. */
	public static double kernelWeight(
			double deltaXMetres, double deltaYMetres, double deltaZMetres, double cellSizeMetres
	) {
		if (!(Double.isFinite(deltaXMetres) && Double.isFinite(deltaYMetres)
				&& Double.isFinite(deltaZMetres)
				&& Double.isFinite(cellSizeMetres) && cellSizeMetres > 0.0)) {
			throw new IllegalArgumentException("Moving-body kernel input is invalid");
		}
		return axisWeight(Math.abs(deltaXMetres) / cellSizeMetres)
				* axisWeight(Math.abs(deltaYMetres) / cellSizeMetres)
				* axisWeight(Math.abs(deltaZMetres) / cellSizeMetres);
	}

	private static double axisWeight(double distanceCells) {
		double weight;
		if (distanceCells < 0.5) weight = 0.75 - distanceCells * distanceCells;
		else if (distanceCells < KERNEL_SUPPORT_CELLS) {
			double remaining = KERNEL_SUPPORT_CELLS - distanceCells;
			weight = 0.5 * remaining * remaining;
		} else return 0.0;
		return weight / KERNEL_PEAK;
	}
}
