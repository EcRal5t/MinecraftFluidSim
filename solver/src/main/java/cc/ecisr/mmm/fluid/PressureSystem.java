package cc.ecisr.mmm.fluid;

/**
 * Pure pressure numerics: PCG over the symmetric liquid-cell / lumped gas-node block and
 * its diagonal preconditioner. The stage owns physical RHS/storage assembly, anchoring and
 * typed non-convergence rejection; this class owns only matrix application and iteration.
 */
final class PressureSystem {
	private final FluidDomain domain;

	PressureSystem(FluidDomain domain) {
		this.domain = domain;
	}

	/** Runs PCG on the assembled right-hand side; the pressure vector mutates in the workspace. */
	PressureProjectionResult solve(int unknownCount, double target) {
		assembleMatrix();
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
		for (int iteration = 0; !converged && iteration < domain.config.maxPressureIterations(); iteration++) {
			applyPressureMatrix(domain.projection.direction, domain.projection.gasDirection,
					domain.projection.matrixDirection, domain.projection.gasMatrixDirection);
			double denominator = dot(domain.projection.direction, domain.projection.gasDirection,
					domain.projection.matrixDirection, domain.projection.gasMatrixDirection);
			if (!(Double.isFinite(denominator) && denominator > 0.0
					&& Double.isFinite(rz) && rz > 0.0)) break;
			float alpha = (float) (rz / denominator);
			if (!Float.isFinite(alpha)) break;
			for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
				int cell = domain.projection.fluidUnknownCells[ordinal];
				domain.projection.pressure[cell] += alpha * domain.projection.direction[cell];
				domain.projection.residual[cell] -= alpha * domain.projection.matrixDirection[cell];
			}
			for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
				int node = domain.projection.gasUnknownNodes[ordinal];
				domain.projection.gasPressure[node] += alpha * domain.projection.gasDirection[node];
				domain.projection.gasResidual[node] -= alpha * domain.projection.gasMatrixDirection[node];
			}
			iterations = iteration + 1;
			residualRms = Math.sqrt(dot(
					domain.projection.residual, domain.projection.gasResidual,
					domain.projection.residual, domain.projection.gasResidual) / unknownCount);
			if (Double.isFinite(residualRms) && residualRms <= target) {
				converged = true;
				break;
			}
			applyPreconditioner(domain.projection.residual, domain.projection.gasResidual,
					domain.projection.preconditioned, domain.projection.gasPreconditioned);
			double nextRz = dot(domain.projection.residual, domain.projection.gasResidual,
					domain.projection.preconditioned, domain.projection.gasPreconditioned);
			if (!(Double.isFinite(nextRz) && nextRz > 0.0)) break;
			float beta = (float) (nextRz / rz);
			if (!Float.isFinite(beta)) break;
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
		return new PressureProjectionResult(iterations, residualRms, converged);
	}

	/** Freezes the physical face operator once for this projection; PCG only changes its vectors. */
	private void assembleMatrix() {
		for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
			int index = domain.projection.fluidUnknownCells[ordinal];
			int x = index % domain.sizeX;
			int yz = index / domain.sizeX;
			int y = yz % domain.sizeY;
			int z = yz / domain.sizeY;
			float negativeX = x > 0
					? pressureFaceCoefficient(index, index - 1, -1, 0, 0) : 0.0F;
			float positiveX = x + 1 < domain.sizeX
					? pressureFaceCoefficient(index, index + 1, 1, 0, 0) : 0.0F;
			float negativeY = y > 0
					? pressureFaceCoefficient(index, index - domain.sizeX, 0, -1, 0) : 0.0F;
			float positiveY = y + 1 < domain.sizeY
					? pressureFaceCoefficient(index, index + domain.sizeX, 0, 1, 0) : 0.0F;
			int zStride = domain.sizeX * domain.sizeY;
			float negativeZ = z > 0
					? pressureFaceCoefficient(index, index - zStride, 0, 0, -1) : 0.0F;
			float positiveZ = z + 1 < domain.sizeZ
					? pressureFaceCoefficient(index, index + zStride, 0, 0, 1) : 0.0F;
			domain.projection.matrixNegativeX[index] = negativeX;
			domain.projection.matrixPositiveX[index] = positiveX;
			domain.projection.matrixNegativeY[index] = negativeY;
			domain.projection.matrixPositiveY[index] = positiveY;
			domain.projection.matrixNegativeZ[index] = negativeZ;
			domain.projection.matrixPositiveZ[index] = positiveZ;
			domain.projection.matrixDiagonal[index] = negativeX + positiveX
					+ negativeY + positiveY + negativeZ + positiveZ;
		}
	}

	private void applyPressureMatrix(
			float[] source, float[] gasSource, float[] destination, float[] gasDestination
	) {
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			gasDestination[domain.projection.gasUnknownNodes[ordinal]] = 0.0F;
		}
		float inverseCellSquared = 1.0F / (domain.config.cellSize() * domain.config.cellSize());
		int yStride = domain.sizeX;
		int zStride = domain.sizeX * domain.sizeY;
		for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
			int index = domain.projection.fluidUnknownCells[ordinal];
			float diagonal = domain.projection.matrixDiagonal[index];
			float value = 0.0F;
			float coefficient = domain.projection.matrixNegativeX[index];
			if (coefficient > 0.0F) {
				int neighbor = index - 1;
				value -= coefficient * neighborValue(neighbor, source, gasSource);
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			coefficient = domain.projection.matrixPositiveX[index];
			if (coefficient > 0.0F) {
				int neighbor = index + 1;
				value -= coefficient * neighborValue(neighbor, source, gasSource);
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			coefficient = domain.projection.matrixNegativeY[index];
			if (coefficient > 0.0F) {
				int neighbor = index - yStride;
				value -= coefficient * neighborValue(neighbor, source, gasSource);
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			coefficient = domain.projection.matrixPositiveY[index];
			if (coefficient > 0.0F) {
				int neighbor = index + yStride;
				value -= coefficient * neighborValue(neighbor, source, gasSource);
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			coefficient = domain.projection.matrixNegativeZ[index];
			if (coefficient > 0.0F) {
				int neighbor = index - zStride;
				value -= coefficient * neighborValue(neighbor, source, gasSource);
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			coefficient = domain.projection.matrixPositiveZ[index];
			if (coefficient > 0.0F) {
				int neighbor = index + zStride;
				value -= coefficient * neighborValue(neighbor, source, gasSource);
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			destination[index] = (diagonal * source[index] + value) * inverseCellSquared;
		}
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			int node = domain.projection.gasUnknownNodes[ordinal];
			gasDestination[node] += domain.projection.gasStorageCoefficient[node] * gasSource[node];
		}
	}

	private float neighborValue(int neighbor, float[] source, float[] gasSource) {
		if (domain.isFluid(neighbor)) return neighbor != domain.pressureAnchor ? source[neighbor] : 0.0F;
		int node = gasUnknownOfAirCell(neighbor);
		return node > 0 ? gasSource[node] : 0.0F;
	}

	private void accumulateGasFace(
			int fluid, int neighbor, float coefficient, float[] source, float[] gasSource,
			float[] gasDestination, float inverseCellSquared
	) {
		int node = gasUnknownOfAirCell(neighbor);
		if (node > 0 && coefficient > 0.0F) {
			gasDestination[node] += coefficient * (gasSource[node] - source[fluid]) * inverseCellSquared;
		}
	}

	private int gasUnknownOfAirCell(int cell) {
		if (!domain.isAir(cell)) return -1;
		int node = domain.gasNodeOfAirCell(cell);
		return node > 0 && domain.projection.gasPressureUnknown[node] ? node : -1;
	}

	private float pressureFaceCoefficient(int fluid, int neighbor, int directionX, int directionY, int directionZ) {
		float openArea = domain.pressureOpenFaceFromCell(fluid, neighbor, directionX, directionY, directionZ);
		if (openArea <= 0.0F || domain.geometry.solid[neighbor]) return 0.0F;
		return domain.pressureFaceCoupling(fluid, neighbor, directionX, directionY, directionZ)
				.matrixCoefficient(openArea);
	}

	private void applyPreconditioner(
			float[] source, float[] gasSource, float[] destination, float[] gasDestination
	) {
		float cellSquared = domain.config.cellSize() * domain.config.cellSize();
		for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
			int index = domain.projection.fluidUnknownCells[ordinal];
			float diagonal = domain.projection.matrixDiagonal[index];
			destination[index] = diagonal == 0.0F ? 0.0F : source[index] * cellSquared / diagonal;
		}
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			int node = domain.projection.gasUnknownNodes[ordinal];
			gasDestination[node] = gasSource[node] / domain.projection.gasMatrixDiagonal[node];
		}
	}

	private double dot(float[] first, float[] firstGas, float[] second, float[] secondGas) {
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
