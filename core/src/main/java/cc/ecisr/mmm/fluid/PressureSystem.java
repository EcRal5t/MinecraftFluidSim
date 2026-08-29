package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/**
 * Pure pressure numerics: the PCG iteration over the domain-discretized Laplacian and its
 * diagonal preconditioner. Loop order, float/double typing, the absolute RMS tolerance
 * handling and the iteration budget are kept verbatim from the original solve; the stage
 * owns assembly, anchoring and the typed non-convergence rejection.
 */
final class PressureSystem {
	private final FlipFluidDomain domain;

	PressureSystem(FlipFluidDomain domain) {
		this.domain = domain;
	}

	/** Runs PCG on the assembled right-hand side; the pressure vector mutates in the workspace. */
	PressureProjectionResult solve(int fluidCells, double target) {
		System.arraycopy(domain.projection.rhs, 0, domain.projection.residual, 0, domain.projection.residual.length);
		double residualRms = Math.sqrt(dot(domain.projection.residual, domain.projection.residual) / fluidCells);
		boolean converged = Double.isFinite(residualRms) && residualRms <= target;
		if (!converged) {
			applyPreconditioner(domain.projection.residual, domain.projection.preconditioned);
			System.arraycopy(domain.projection.preconditioned, 0, domain.projection.direction, 0, domain.projection.residual.length);
		}
		double rz = dot(domain.projection.residual, domain.projection.preconditioned);
		int iterations = 0;
		for (int iteration = 0; !converged && iteration < domain.config.maxPressureIterations(); iteration++) {
			applyPressureMatrix(domain.projection.direction, domain.projection.matrixDirection);
			double denominator = dot(domain.projection.direction, domain.projection.matrixDirection);
			if (!(Double.isFinite(denominator) && denominator > 1.0E-20
					&& Double.isFinite(rz) && rz > 0.0)) break;
			float alpha = (float) (rz / denominator);
			if (!Float.isFinite(alpha)) break;
			for (int i = 0; i < domain.cellCount; i++) {
				domain.projection.pressure[i] += alpha * domain.projection.direction[i];
				domain.projection.residual[i] -= alpha * domain.projection.matrixDirection[i];
			}
			iterations = iteration + 1;
			residualRms = Math.sqrt(dot(domain.projection.residual, domain.projection.residual) / fluidCells);
			if (Double.isFinite(residualRms) && residualRms <= target) {
				converged = true;
				break;
			}
			applyPreconditioner(domain.projection.residual, domain.projection.preconditioned);
			double nextRz = dot(domain.projection.residual, domain.projection.preconditioned);
			if (!(Double.isFinite(nextRz) && nextRz > 0.0)) break;
			float beta = (float) (nextRz / rz);
			if (!Float.isFinite(beta)) break;
			for (int i = 0; i < domain.cellCount; i++) domain.projection.direction[i] = domain.projection.preconditioned[i] + beta * domain.projection.direction[i];
			rz = nextRz;
		}
		return new PressureProjectionResult(iterations, residualRms, converged);
	}

	private void applyPressureMatrix(float[] source, float[] destination) {
		Arrays.fill(destination, 0.0F);
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
				value -= coefficient * neighborValue(neighbor, source);
			}
			if (x + 1 < domain.sizeX) {
				int neighbor = domain.cellIndex(x + 1, y, z);
				float coefficient = pressureFaceCoefficient(index, neighbor, 1, 0, 0);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source);
			}
			if (y > 0) {
				int neighbor = domain.cellIndex(x, y - 1, z);
				float coefficient = pressureFaceCoefficient(index, neighbor, 0, -1, 0);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source);
			}
			if (y + 1 < domain.sizeY) {
				int neighbor = domain.cellIndex(x, y + 1, z);
				float coefficient = pressureFaceCoefficient(index, neighbor, 0, 1, 0);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source);
			}
			if (z > 0) {
				int neighbor = domain.cellIndex(x, y, z - 1);
				float coefficient = pressureFaceCoefficient(index, neighbor, 0, 0, -1);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source);
			}
			if (z + 1 < domain.sizeZ) {
				int neighbor = domain.cellIndex(x, y, z + 1);
				float coefficient = pressureFaceCoefficient(index, neighbor, 0, 0, 1);
				diagonal += coefficient;
				value -= coefficient * neighborValue(neighbor, source);
			}
			destination[index] = (diagonal * source[index] + value) * inverseCellSquared;
		}
	}

	private float neighborValue(int neighbor, float[] source) {
		return domain.isFluid(neighbor) && neighbor != domain.pressureAnchor ? source[neighbor] : 0.0F;
	}

	private float pressureFaceCoefficient(int fluid, int neighbor, int directionX, int directionY, int directionZ) {
		float openArea = domain.pressureOpenFaceFromCell(fluid, neighbor, directionX, directionY, directionZ);
		if (openArea <= 0.0F || domain.geometry.solid[neighbor]) return 0.0F;
		float densityScale = domain.variableDensityActive ? domain.pressureDensityScale(fluid, neighbor) : 1.0F;
		if (domain.isFluid(neighbor)) return openArea * densityScale;
		return openArea * densityScale
				/ domain.pressureInterfaceTheta(fluid, directionX, directionY, directionZ);
	}

	private void applyPreconditioner(float[] source, float[] destination) {
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
	}

	private static double dot(float[] first, float[] second) {
		double sum = 0.0;
		for (int i = 0; i < first.length; i++) sum += (double) first[i] * second[i];
		return sum;
	}
}
