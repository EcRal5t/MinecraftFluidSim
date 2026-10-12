package mmm.fluid;

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
	boolean[] gasPressureUnknown;
	int[] gasUnknownNodes;
	float[] gasPressure;
	double[] gasRhs;
	double[] gasResidual;
	double[] gasPreconditioned;
	double[] gasDirection;
	double[] gasMatrixDirection;
	double[] gasMatrixDiagonal;
	double[] gasStorageCoefficient;
	int gasNodeCount;
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
		// One-based gas scratch grows with the actual micro-piece chamber count.
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

	void ensureGasCapacity(int nodes) {
		if (nodes < 0 || nodes > mmm.fluid.air.GasLimits.MAX_NODES_PER_REGION)
			throw new IllegalArgumentException("Gas pressure node count exceeds its representation budget");
		gasNodeCount = nodes;
		if (nodes < gasPressure.length) return;
		int length = Math.addExact(nodes, 1);
		gasPressureUnknown = Arrays.copyOf(gasPressureUnknown, length);
		gasUnknownNodes = Arrays.copyOf(gasUnknownNodes, nodes);
		gasPressure = Arrays.copyOf(gasPressure, length);
		gasRhs = Arrays.copyOf(gasRhs, length); gasResidual = Arrays.copyOf(gasResidual, length);
		gasPreconditioned = Arrays.copyOf(gasPreconditioned, length); gasDirection = Arrays.copyOf(gasDirection, length);
		gasMatrixDirection = Arrays.copyOf(gasMatrixDirection, length); gasMatrixDiagonal = Arrays.copyOf(gasMatrixDiagonal, length);
		gasStorageCoefficient = Arrays.copyOf(gasStorageCoefficient, length);
	}
}
