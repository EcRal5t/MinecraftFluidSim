package mmm.fluid;

/** Paired midpoint and finite arrival age for one directional source share. */
final class CellRemapArrival {
	private CellRemapArrival() { }
	static double[] prepare(CellRemapSpatialMoments spatial, int axis, boolean positive, double step,
			double volume, double[] volumeMoment, double[] volumeMotion) {
		var point = new double[6]; var mean = spatial.centroid(); var velocity = spatial.meanVelocity();
		for (int a = 0; a < 3; a++) {
			point[a] = a == axis ? (positive ? .5 : -.5) : mean[a] + .5 * step * velocity[a];
			point[a + 3] = a == axis ? point[a] : volumeMoment[a] / volume + .5 * step * volumeMotion[a] / volume;
		}
		spatial.advancePositions(.5 * step);
		spatial.projectPositionAxis(axis);
		spatial.spreadFaceArrival(axis, step);
		spatial.setCentroid(java.util.Arrays.copyOf(point, 3));
		return point;
	}
}
