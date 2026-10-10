package cc.ecisr.mmm.fluid;

/**
 * Discardable surface-tension workspace: color field, smoothing, pair curvature, normals,
 * capillary pressure and Young wall-energy density. Rebuilt inside the body-force stage whenever tension is
 * active; never checkpoint truth. Solver-internal package-private access only.
 */
final class SurfaceTensionWorkspace {
	final double[] columnHeights = new double[9];
	final float[] surfaceTensionPressure;
	final double[] wallSurfaceEnergyDensity;
	final double[] surfaceTensionColor;
	final double[] surfaceTensionSmoothed;
	final double[] surfaceTensionPairCurvature;
	final double[] surfaceTensionNormalX;
	final double[] surfaceTensionNormalY;
	final double[] surfaceTensionNormalZ;

	SurfaceTensionWorkspace(int cellCount) {
		this.surfaceTensionPressure = new float[cellCount];
		this.wallSurfaceEnergyDensity = new double[cellCount];
		this.surfaceTensionColor = new double[cellCount];
		this.surfaceTensionSmoothed = new double[cellCount];
		this.surfaceTensionPairCurvature = new double[cellCount];
		this.surfaceTensionNormalX = new double[cellCount];
		this.surfaceTensionNormalY = new double[cellCount];
		this.surfaceTensionNormalZ = new double[cellCount];
	}
}
