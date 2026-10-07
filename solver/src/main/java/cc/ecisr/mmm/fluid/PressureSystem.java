package cc.ecisr.mmm.fluid;

/** Selects the liquid/gas PCG or the double-precision pressure system with shared granular drag. */
final class PressureSystem {
	private static final int LIQUID_PRESSURE = -1;
	private static final int FIXED_PRESSURE = 0;
	private final FluidDomain domain;
	private final GranularPressureCoupling granular;
	private final GranularImplicitDrag drag;
	private final double[] liquidPressure, gasPressure;
	/** Per-projection pressure source: liquid, fixed zero, or a positive gas node id. */
	private final int[] neighborPressureSource;
	private float substepDuration;

	PressureSystem(FluidDomain domain) {
		this.domain = domain;
		liquidPressure = new double[domain.cellCount]; gasPressure = new double[domain.cellCount + 1];
		neighborPressureSource = new int[domain.cellCount];
		this.granular = new GranularPressureCoupling(domain);
		this.drag = new GranularImplicitDrag(domain, granular);
	}
	void prepareGranular(float dt) { substepDuration = dt; granular.prepare(dt); drag.prepare(dt); }
	void forEachGranularGasNode(float dt, java.util.function.IntConsumer accept) { granular.forEachGasNode(dt, accept); }
	double liquidPressure(int cell) { return liquidPressure[cell]; }
	double gasPressure(int node) { return gasPressure[node]; }
	boolean projectGranular(float dt) {
		if (drag.active()) { drag.project(); return true; }
		granular.project(dt, liquidPressure, gasPressure);
		return false;
	}

	/** Runs PCG on the assembled right-hand side; the pressure vector mutates in the workspace. */
	PressureProjectionResult solve(int unknownCount, double target) {
		if (unknownCount == 0 && !drag.active()) return new PressureProjectionResult(0, 0, true);
		assembleMatrix();
		int totalIterations = 0;
		for (int iteration = 0; iteration < 32; iteration++) {
			granular.rebuildDiagonal(drag::parcelInertiaRatio);
			java.util.Arrays.fill(domain.projection.pressure, 0);
			java.util.Arrays.fill(domain.projection.gasPressure, 0);
			PressureProjectionResult result = drag.active() ? drag.solvePressure(target) : solveLinear(unknownCount, target);
			totalIterations += result.iterations();
			if (!result.converged() || !drag.updateResistance()) return new PressureProjectionResult(
					totalIterations, result.residualRms(), result.converged());
		}
		// A smaller implicit step reduces the drag/inertia ratio without changing the drag law.
		// Retry the complete private candidate, including phase geometry and external impulses.
		throw new MechanicalTimeRefinement((int) Math.ceil(2.0 * domain.config.timeStep() / substepDuration),
				"Nonlinear granular drag residual " + drag.nonlinearResidual());
	}

	private PressureProjectionResult solveLinear(int unknownCount, double target) {
		java.util.Arrays.fill(liquidPressure, 0); java.util.Arrays.fill(gasPressure, 0);
		for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
			int cell = domain.projection.fluidUnknownCells[ordinal];
			domain.projection.residual[cell] = domain.projection.rhs[cell];
		}
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			int node = domain.projection.gasUnknownNodes[ordinal];
			domain.projection.gasResidual[node] = domain.projection.gasRhs[node];
		}
		double residualRms = Math.sqrt(dot(
				domain.projection.residual, domain.projection.gasResidual,
				domain.projection.residual, domain.projection.gasResidual) / unknownCount);
		boolean converged = Double.isFinite(residualRms) && residualRms <= target;
		if (!converged) {
			applyPreconditioner(domain.projection.residual, domain.projection.gasResidual,
					domain.projection.preconditioned, domain.projection.gasPreconditioned);
			for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
				int cell = domain.projection.fluidUnknownCells[ordinal];
				domain.projection.direction[cell] = domain.projection.preconditioned[cell];
			}
			for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
				int node = domain.projection.gasUnknownNodes[ordinal];
				domain.projection.gasDirection[node] = domain.projection.gasPreconditioned[node];
			}
		}
		double rz = dot(domain.projection.residual, domain.projection.gasResidual,
				domain.projection.preconditioned, domain.projection.gasPreconditioned);
		int iterations = 0;
		for (int iteration = 0; !converged && iteration < domain.config.maxSolverIterations(); iteration++) {
			applyPressureMatrix(domain.projection.direction, domain.projection.gasDirection,
					domain.projection.matrixDirection, domain.projection.gasMatrixDirection);
			double denominator = dot(domain.projection.direction, domain.projection.gasDirection,
					domain.projection.matrixDirection, domain.projection.gasMatrixDirection);
			if (!(Double.isFinite(denominator) && denominator > 0.0
					&& Double.isFinite(rz) && rz > 0.0)) break;
			double alpha = rz / denominator;
			if (!Double.isFinite(alpha)) break;
			for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
				int cell = domain.projection.fluidUnknownCells[ordinal];
				liquidPressure[cell] += alpha * domain.projection.direction[cell];
				domain.projection.residual[cell] -= alpha * domain.projection.matrixDirection[cell];
			}
			for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
				int node = domain.projection.gasUnknownNodes[ordinal];
				gasPressure[node] += alpha * domain.projection.gasDirection[node];
				domain.projection.gasResidual[node] -= alpha * domain.projection.gasMatrixDirection[node];
			}
			iterations = iteration + 1;
			residualRms = Math.sqrt(dot(
					domain.projection.residual, domain.projection.gasResidual,
					domain.projection.residual, domain.projection.gasResidual) / unknownCount);
			if (Double.isFinite(residualRms) && residualRms <= target) {
				applyPressureMatrix(liquidPressure, gasPressure,
						domain.projection.matrixDirection, domain.projection.gasMatrixDirection);
				for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
					int cell = domain.projection.fluidUnknownCells[ordinal];
					domain.projection.residual[cell] = domain.projection.rhs[cell] - domain.projection.matrixDirection[cell];
				}
				for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
					int node = domain.projection.gasUnknownNodes[ordinal];
					domain.projection.gasResidual[node] = domain.projection.gasRhs[node] - domain.projection.gasMatrixDirection[node];
				}
				residualRms = Math.sqrt(dot(domain.projection.residual, domain.projection.gasResidual,
						domain.projection.residual, domain.projection.gasResidual) / unknownCount);
				if (Double.isFinite(residualRms) && residualRms <= attainableTolerance(target)) {
					converged = true;
					break;
				}
				java.util.Arrays.fill(domain.projection.direction, 0);
				java.util.Arrays.fill(domain.projection.gasDirection, 0);
				rz = Double.POSITIVE_INFINITY;
			}
			applyPreconditioner(domain.projection.residual, domain.projection.gasResidual,
					domain.projection.preconditioned, domain.projection.gasPreconditioned);
			double nextRz = dot(domain.projection.residual, domain.projection.gasResidual,
					domain.projection.preconditioned, domain.projection.gasPreconditioned);
			if (!(Double.isFinite(nextRz) && nextRz > 0.0)) break;
			double beta = nextRz / rz;
			if (!Double.isFinite(beta)) break;
			for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
				int cell = domain.projection.fluidUnknownCells[ordinal];
				domain.projection.direction[cell] = domain.projection.preconditioned[cell]
						+ beta * domain.projection.direction[cell];
			}
			for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
				int node = domain.projection.gasUnknownNodes[ordinal];
				domain.projection.gasDirection[node] = domain.projection.gasPreconditioned[node]
						+ beta * domain.projection.gasDirection[node];
			}
			rz = nextRz;
		}
		for (int i = 0; i < domain.projection.fluidUnknownCount; i++) {
			int cell = domain.projection.fluidUnknownCells[i]; domain.projection.pressure[cell] = (float) liquidPressure[cell];
		}
		for (int i = 0; i < domain.projection.gasUnknownCount; i++) {
			int node = domain.projection.gasUnknownNodes[i]; domain.projection.gasPressure[node] = (float) gasPressure[node];
		}
		return new PressureProjectionResult(iterations, residualRms, converged);
	}

	/** Freezes the physical face operator once for this projection; PCG only changes its vectors. */
	private void assembleMatrix() {
		for (int cell = 0; cell < domain.cellCount; cell++) {
			neighborPressureSource[cell] = domain.isFluid(cell)
					? (cell == domain.pressureAnchor ? FIXED_PRESSURE : LIQUID_PRESSURE)
					: Math.max(FIXED_PRESSURE, gasUnknownOfAirCell(cell));
		}
		for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
			int index = domain.projection.fluidUnknownCells[ordinal];
			int x = index % domain.sizeX;
			int yz = index / domain.sizeX;
			int y = yz % domain.sizeY;
			int z = yz / domain.sizeY;
			double negativeX = x > 0
					? pressureFaceCoefficient(index, index - 1, -1, 0, 0) : 0.0F;
			double positiveX = x + 1 < domain.sizeX
					? pressureFaceCoefficient(index, index + 1, 1, 0, 0) : 0.0F;
			double negativeY = y > 0
					? pressureFaceCoefficient(index, index - domain.sizeX, 0, -1, 0) : 0.0F;
			double positiveY = y + 1 < domain.sizeY
					? pressureFaceCoefficient(index, index + domain.sizeX, 0, 1, 0) : 0.0F;
			int zStride = domain.sizeX * domain.sizeY;
			double negativeZ = z > 0
					? pressureFaceCoefficient(index, index - zStride, 0, 0, -1) : 0.0F;
			double positiveZ = z + 1 < domain.sizeZ
					? pressureFaceCoefficient(index, index + zStride, 0, 0, 1) : 0.0F;
			domain.projection.matrixNegativeX[index] = negativeX;
			domain.projection.matrixPositiveX[index] = positiveX;
			domain.projection.matrixNegativeY[index] = negativeY;
			domain.projection.matrixPositiveY[index] = positiveY;
			domain.projection.matrixNegativeZ[index] = negativeZ;
			domain.projection.matrixPositiveZ[index] = positiveZ;
			domain.projection.matrixDiagonal[index] = (double) negativeX + positiveX
					+ negativeY + positiveY + negativeZ + positiveZ;
		}
	}

	private void applyPressureMatrix(
			double[] source, double[] gasSource, double[] destination, double[] gasDestination
	) {
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			gasDestination[domain.projection.gasUnknownNodes[ordinal]] = 0.0F;
		}
		double h = domain.config.cellSize(), inverseCellSquared = 1.0 / (h * h);
		int yStride = domain.sizeX;
		int zStride = domain.sizeX * domain.sizeY;
		for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
			int index = domain.projection.fluidUnknownCells[ordinal];
			double value = 0.0;
			double coefficient = domain.projection.matrixNegativeX[index];
			if (coefficient > 0.0F) {
				int neighbor = index - 1;
				value += coefficient * (source[index] - neighborValue(neighbor, source, gasSource));
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			coefficient = domain.projection.matrixPositiveX[index];
			if (coefficient > 0.0F) {
				int neighbor = index + 1;
				value += coefficient * (source[index] - neighborValue(neighbor, source, gasSource));
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			coefficient = domain.projection.matrixNegativeY[index];
			if (coefficient > 0.0F) {
				int neighbor = index - yStride;
				value += coefficient * (source[index] - neighborValue(neighbor, source, gasSource));
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			coefficient = domain.projection.matrixPositiveY[index];
			if (coefficient > 0.0F) {
				int neighbor = index + yStride;
				value += coefficient * (source[index] - neighborValue(neighbor, source, gasSource));
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			coefficient = domain.projection.matrixNegativeZ[index];
			if (coefficient > 0.0F) {
				int neighbor = index - zStride;
				value += coefficient * (source[index] - neighborValue(neighbor, source, gasSource));
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			coefficient = domain.projection.matrixPositiveZ[index];
			if (coefficient > 0.0F) {
				int neighbor = index + zStride;
				value += coefficient * (source[index] - neighborValue(neighbor, source, gasSource));
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			destination[index] = value * inverseCellSquared;
		}
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			int node = domain.projection.gasUnknownNodes[ordinal];
			gasDestination[node] += domain.projection.gasStorageCoefficient[node] * gasSource[node];
		}
		granular.apply(source, gasSource, destination, gasDestination);
	}

	private double neighborValue(int neighbor, double[] source, double[] gasSource) {
		int node = neighborPressureSource[neighbor];
		if (node == LIQUID_PRESSURE) return source[neighbor];
		return node > 0 ? gasSource[node] : 0.0F;
	}

	private void accumulateGasFace(
			int fluid, int neighbor, double coefficient, double[] source, double[] gasSource,
			double[] gasDestination, double inverseCellSquared
	) {
		int node = neighborPressureSource[neighbor];
		if (node > 0 && coefficient > 0.0F) {
			gasDestination[node] += coefficient * (gasSource[node] - source[fluid]) * inverseCellSquared;
		}
	}

	private int gasUnknownOfAirCell(int cell) {
		if (!domain.isAir(cell)) return -1;
		int node = domain.gasNodeOfAirCell(cell);
		return node > 0 && domain.projection.gasPressureUnknown[node] ? node : -1;
	}

	private double pressureFaceCoefficient(int fluid, int neighbor, int directionX, int directionY, int directionZ) {
		float openArea = domain.pressureOpenFaceFromCell(fluid, neighbor, directionX, directionY, directionZ);
		if (openArea <= 0.0F || domain.geometry.solid[neighbor]) return 0.0F;
		return domain.pressureFaceCoupling(fluid, neighbor, directionX, directionY, directionZ)
				.matrixCoefficient(openArea);
	}

	private void applyPreconditioner(
			double[] source, double[] gasSource, double[] destination, double[] gasDestination
	) {
		double h = domain.config.cellSize(), cellSquared = h * h;
		for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
			int index = domain.projection.fluidUnknownCells[ordinal];
			double diagonal = domain.projection.matrixDiagonal[index] + cellSquared * granular.liquidDiagonal(index);
			destination[index] = diagonal == 0.0F ? 0.0F : source[index] * cellSquared / diagonal;
		}
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			int node = domain.projection.gasUnknownNodes[ordinal];
			gasDestination[node] = gasSource[node] / (domain.projection.gasMatrixDiagonal[node] + granular.gasDiagonal(node));
		}
	}

	private double attainableTolerance(double target) {
		double maximumPressure = 0, scale = 0;
		for (int i = 0; i < domain.projection.fluidUnknownCount; i++) maximumPressure = Math.max(
				maximumPressure, Math.abs(liquidPressure[domain.projection.fluidUnknownCells[i]]));
		for (int i = 0; i < domain.projection.gasUnknownCount; i++) maximumPressure = Math.max(
				maximumPressure, Math.abs(gasPressure[domain.projection.gasUnknownNodes[i]]));
		double h = domain.config.cellSize();
		for (int i = 0; i < domain.projection.fluidUnknownCount; i++) {
			int cell = domain.projection.fluidUnknownCells[i];
			double diagonal = domain.projection.matrixDiagonal[cell] / (h * h) + granular.liquidDiagonal(cell);
			scale = Math.hypot(scale, Math.abs(domain.projection.rhs[cell]) + 2 * diagonal * maximumPressure);
		}
		for (int i = 0; i < domain.projection.gasUnknownCount; i++) {
			int node = domain.projection.gasUnknownNodes[i];
			double diagonal = domain.projection.gasMatrixDiagonal[node] + granular.gasDiagonal(node);
			scale = Math.hypot(scale, Math.abs(domain.projection.gasRhs[node]) + 2 * diagonal * maximumPressure);
		}
		int count = domain.projection.fluidUnknownCount + domain.projection.gasUnknownCount;
		double floor = (count + 8.0) * Math.ulp(1.0) * 0.5 * scale / Math.sqrt(Math.max(1, count));
		return Double.isFinite(floor) ? Math.max(target, floor) : target;
	}

	private double dot(double[] first, double[] firstGas, double[] second, double[] secondGas) {
		double sum = 0.0;
		for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
			int cell = domain.projection.fluidUnknownCells[ordinal];
			sum += (double) first[cell] * second[cell];
		}
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			int node = domain.projection.gasUnknownNodes[ordinal];
			sum += (double) firstGas[node] * secondGas[node];
		}
		return sum;
	}
}
