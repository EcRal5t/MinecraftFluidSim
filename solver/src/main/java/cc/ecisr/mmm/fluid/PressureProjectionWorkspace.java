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
	final double[] rhs;
	final double[] residual;
	final double[] preconditioned;
	final double[] direction;
	final double[] matrixDirection;
	final int[] fluidUnknownCells;
	final double[] matrixDiagonal;
	final double[] matrixNegativeX;
	final double[] matrixPositiveX;
	final double[] matrixNegativeY;
	final double[] matrixPositiveY;
	final double[] matrixNegativeZ;
	final double[] matrixPositiveZ;
	final boolean[] gasPressureUnknown;
	final int[] gasUnknownNodes;
	final float[] gasPressure;
	final double[] gasRhs;
	final double[] gasResidual;
	final double[] gasPreconditioned;
	final double[] gasDirection;
	final double[] gasMatrixDirection;
	final double[] gasMatrixDiagonal;
	final double[] gasStorageCoefficient;
	int fluidUnknownCount;
	int gasUnknownCount;

	PressureProjectionWorkspace(int cellCount) {
		this.pressureFluid = new boolean[cellCount];
		this.pressureAirNodeId = new int[cellCount];
		this.pressureAirQueue = new int[cellCount];
		Arrays.fill(this.pressureAirNodeId, -1);
		this.pressureVolume = new double[cellCount];
		this.pressure = new float[cellCount];
		this.divergence = new float[cellCount];
		this.rhs = new double[cellCount];
		this.residual = new double[cellCount];
		this.preconditioned = new double[cellCount];
		this.direction = new double[cellCount];
		this.matrixDirection = new double[cellCount];
		this.fluidUnknownCells = new int[cellCount];
		this.matrixDiagonal = new double[cellCount];
		this.matrixNegativeX = new double[cellCount];
		this.matrixPositiveX = new double[cellCount];
		this.matrixNegativeY = new double[cellCount];
		this.matrixPositiveY = new double[cellCount];
		this.matrixNegativeZ = new double[cellCount];
		this.matrixPositiveZ = new double[cellCount];
		// Node ids are one-based and cannot outnumber cells. These vectors are projection scratch;
		// AirNetwork inventory/U remain the sole persistent gas truth.
		this.gasPressureUnknown = new boolean[cellCount + 1];
		this.gasUnknownNodes = new int[cellCount];
		this.gasPressure = new float[cellCount + 1];
		this.gasRhs = new double[cellCount + 1];
		this.gasResidual = new double[cellCount + 1];
		this.gasPreconditioned = new double[cellCount + 1];
		this.gasDirection = new double[cellCount + 1];
		this.gasMatrixDirection = new double[cellCount + 1];
		this.gasMatrixDiagonal = new double[cellCount + 1];
		this.gasStorageCoefficient = new double[cellCount + 1];
	}
}
