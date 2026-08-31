package cc.ecisr.mmm.fluid;

import java.util.Arrays;

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
		System.arraycopy(domain.projection.rhs, 0, domain.projection.residual, 0, domain.projection.residual.length);
		System.arraycopy(domain.projection.gasRhs, 0, domain.projection.gasResidual, 0,
				domain.projection.gasResidual.length);
		double residualRms = Math.sqrt(dot(
				domain.projection.residual, domain.projection.gasResidual,
				domain.projection.residual, domain.projection.gasResidual) / unknownCount);
		boolean converged = Double.isFinite(residualRms) && residualRms <= target;
		if (!converged) {
			applyPreconditioner(domain.projection.residual, domain.projection.gasResidual,
					domain.projection.preconditioned, domain.projection.gasPreconditioned);
			System.arraycopy(domain.projection.preconditioned, 0, domain.projection.direction, 0, domain.projection.residual.length);
			System.arraycopy(domain.projection.gasPreconditioned, 0, domain.projection.gasDirection, 0,
					domain.projection.gasResidual.length);
		}
		double rz = dot(domain.projection.residual, domain.projection.gasResidual,
				domain.projection.preconditioned, domain.projection.gasPreconditioned);
		int iterations = 0;
		for (int iteration = 0; !converged && iteration < domain.config.maxPressureIterations(); iteration++) {
			applyPressureMatrix(domain.projection.direction, domain.projection.gasDirection,
					domain.projection.matrixDirection, domain.projection.gasMatrixDirection);
			double denominator = dot(domain.projection.direction, domain.projection.gasDirection,
					domain.projection.matrixDirection, domain.projection.gasMatrixDirection);
			if (!(Double.isFinite(denominator) && denominator > 1.0E-20
					&& Double.isFinite(rz) && rz > 0.0)) break;
			float alpha = (float) (rz / denominator);
			if (!Float.isFinite(alpha)) break;
			for (int i = 0; i < domain.cellCount; i++) {
				domain.projection.pressure[i] += alpha * domain.projection.direction[i];
				domain.projection.residual[i] -= alpha * domain.projection.matrixDirection[i];
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
			for (int i = 0; i < domain.cellCount; i++) domain.projection.direction[i] = domain.projection.preconditioned[i] + beta * domain.projection.direction[i];
			for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
				int node = domain.projection.gasUnknownNodes[ordinal];
				domain.projection.gasDirection[node] = domain.projection.gasPreconditioned[node]
						+ beta * domain.projection.gasDirection[node];
			}
			rz = nextRz;
		}
		return new PressureProjectionResult(iterations, residualRms, converged);
	}

	private void applyPressureMatrix(
			float[] source, float[] gasSource, float[] destination, float[] gasDestination
	) {
		Arrays.fill(destination, 0.0F);
		Arrays.fill(gasDestination, 0.0F);
		float inverseCellSquared = 1.0F / (domain.config.cellSize() * domain.config.cellSize());
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = domain.cellIndex(x, y, z);
			if (!domain.isFluid(index) || index == domain.pressureAnchor) continue;
			float value = 0.0F;
			float diagonal = 0.0F;
			if (x > 0) {
				int neighbor = domain.cellIndex(x - 1, y, z);
				float coefficient = pressureFaceCoefficient(index, neighbor, -1, 0, 0);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source, gasSource);
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			if (x + 1 < domain.sizeX) {
				int neighbor = domain.cellIndex(x + 1, y, z);
				float coefficient = pressureFaceCoefficient(index, neighbor, 1, 0, 0);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source, gasSource);
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			if (y > 0) {
				int neighbor = domain.cellIndex(x, y - 1, z);
				float coefficient = pressureFaceCoefficient(index, neighbor, 0, -1, 0);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source, gasSource);
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			if (y + 1 < domain.sizeY) {
				int neighbor = domain.cellIndex(x, y + 1, z);
				float coefficient = pressureFaceCoefficient(index, neighbor, 0, 1, 0);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source, gasSource);
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			if (z > 0) {
				int neighbor = domain.cellIndex(x, y, z - 1);
				float coefficient = pressureFaceCoefficient(index, neighbor, 0, 0, -1);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source, gasSource);
				accumulateGasFace(index, neighbor, coefficient, source, gasSource, gasDestination, inverseCellSquared);
			}
			if (z + 1 < domain.sizeZ) {
				int neighbor = domain.cellIndex(x, y, z + 1);
				float coefficient = pressureFaceCoefficient(index, neighbor, 0, 0, 1);
				diagonal += coefficient;
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
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = domain.cellIndex(x, y, z);
			if (!domain.isFluid(index) || index == domain.pressureAnchor) { destination[index] = 0.0F; continue; }
			float diagonal = 0.0F;
			if (x > 0) diagonal += pressureFaceCoefficient(index, domain.cellIndex(x - 1, y, z), -1, 0, 0);
			if (x + 1 < domain.sizeX) diagonal += pressureFaceCoefficient(index, domain.cellIndex(x + 1, y, z), 1, 0, 0);
			if (y > 0) diagonal += pressureFaceCoefficient(index, domain.cellIndex(x, y - 1, z), 0, -1, 0);
			if (y + 1 < domain.sizeY) diagonal += pressureFaceCoefficient(index, domain.cellIndex(x, y + 1, z), 0, 1, 0);
			if (z > 0) diagonal += pressureFaceCoefficient(index, domain.cellIndex(x, y, z - 1), 0, 0, -1);
			if (z + 1 < domain.sizeZ) diagonal += pressureFaceCoefficient(index, domain.cellIndex(x, y, z + 1), 0, 0, 1);
			destination[index] = diagonal == 0.0F ? 0.0F : source[index] * cellSquared / diagonal;
		}
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			int node = domain.projection.gasUnknownNodes[ordinal];
			gasDestination[node] = gasSource[node] / domain.projection.gasMatrixDiagonal[node];
		}
	}

	private double dot(float[] first, float[] firstGas, float[] second, float[] secondGas) {
		double sum = 0.0;
		for (int i = 0; i < first.length; i++) sum += (double) first[i] * second[i];
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			int node = domain.projection.gasUnknownNodes[ordinal];
			sum += (double) firstGas[node] * secondGas[node];
		}
		return sum;
	}
}
