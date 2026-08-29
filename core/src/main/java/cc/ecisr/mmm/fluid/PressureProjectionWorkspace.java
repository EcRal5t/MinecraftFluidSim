package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/**
 * Discardable pressure-projection workspace: fluid classification, air-node bookkeeping and
 * the PCG vector scratch. Rebuilt per projection from geometry and marker state; memory may be
 * reused across steps but nothing here persists. Solver-internal package-private access only.
 */
final class PressureProjectionWorkspace {
	final boolean[] pressureFluid;
	final int[] pressureAirNodeId;
	final int[] pressureAirQueue;
	final double[] pressureVolume;
	final float[] pressure;
	final float[] divergence;
	final float[] rhs;
	final float[] residual;
	final float[] preconditioned;
	final float[] direction;
	final float[] matrixDirection;

	PressureProjectionWorkspace(int cellCount) {
		this.pressureFluid = new boolean[cellCount];
		this.pressureAirNodeId = new int[cellCount];
		this.pressureAirQueue = new int[cellCount];
		Arrays.fill(this.pressureAirNodeId, -1);
		this.pressureVolume = new double[cellCount];
		this.pressure = new float[cellCount];
		this.divergence = new float[cellCount];
		this.rhs = new float[cellCount];
		this.residual = new float[cellCount];
		this.preconditioned = new float[cellCount];
		this.direction = new float[cellCount];
		this.matrixDirection = new float[cellCount];
	}
}
