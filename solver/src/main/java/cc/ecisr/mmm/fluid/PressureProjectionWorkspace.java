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
	final boolean[] gasPressureUnknown;
	final int[] gasUnknownNodes;
	final float[] gasPressure;
	final float[] gasRhs;
	final float[] gasResidual;
	final float[] gasPreconditioned;
	final float[] gasDirection;
	final float[] gasMatrixDirection;
	final float[] gasMatrixDiagonal;
	final float[] gasStorageCoefficient;
	int gasUnknownCount;

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
		// Node ids are one-based and cannot outnumber cells. These vectors are projection scratch;
		// AirNetwork inventory/U remain the sole persistent gas truth.
		this.gasPressureUnknown = new boolean[cellCount + 1];
		this.gasUnknownNodes = new int[cellCount];
		this.gasPressure = new float[cellCount + 1];
		this.gasRhs = new float[cellCount + 1];
		this.gasResidual = new float[cellCount + 1];
		this.gasPreconditioned = new float[cellCount + 1];
		this.gasDirection = new float[cellCount + 1];
		this.gasMatrixDirection = new float[cellCount + 1];
		this.gasMatrixDiagonal = new float[cellCount + 1];
		this.gasStorageCoefficient = new float[cellCount + 1];
	}
}
