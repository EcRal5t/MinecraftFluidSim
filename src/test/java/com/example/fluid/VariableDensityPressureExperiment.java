package com.example.fluid;

import java.util.Arrays;

/**
 * Isolated one-dimensional finite-volume experiment for variable-density pressure faces.
 * It deliberately does not participate in the active Minecraft solver.
 */
final class VariableDensityPressureExperiment {
	private static final double CELL_SIZE = 0.5;
	private static final double TIME_STEP = 1.0 / 20.0;
	private static final double GRAVITY = 9.81;
	private static final int MAX_PCG_ITERATIONS = 128;
	private static final double PCG_TOLERANCE = 1.0E-12;

	private VariableDensityPressureExperiment() { }

	static Result run(Scenario scenario, FaceCoefficient candidate) {
		double[] inverseDensityByFace = faceCoefficients(scenario, candidate);
		double[][] matrix = pressureMatrix(scenario.cells(), inverseDensityByFace);
		double[] provisionalVelocity = gravityVelocity(scenario.cells());
		double[] rhs = pressureRightHandSide(scenario.cells(), provisionalVelocity);
		PcgResult solved = solvePcg(matrix, rhs);
		double[] expectedPressure = expectedHydrostaticPressure(scenario);
		double pressureError = maximumRelativeError(solved.pressure(), expectedPressure);
		double projectedVelocity = maximumProjectedVelocity(solved.pressure(), provisionalVelocity, inverseDensityByFace);
		double projectedDivergence = maximumDivergence(solved.pressure(), provisionalVelocity, inverseDensityByFace);
		double hydrostaticVelocityDefect = maximumHydrostaticVelocityDefect(expectedPressure, provisionalVelocity, inverseDensityByFace);
		MatrixDiagnostics matrixDiagnostics = inspectMatrix(matrix);
		double interfaceInverseDensity = inverseDensityByFace[scenario.interfaceFace()];
		return new Result(scenario, candidate, 1.0 / interfaceInverseDensity, pressureError,
				hydrostaticVelocityDefect, projectedVelocity, projectedDivergence,
				solved.iterations(), solved.residualRms(), matrixDiagnostics.symmetryError(),
				matrixDiagnostics.minimumCholeskyPivot());
	}

	static double timeStep() { return TIME_STEP; }
	static double gravity() { return GRAVITY; }

	private static double[] faceCoefficients(Scenario scenario, FaceCoefficient candidate) {
		double[] result = new double[scenario.cells() + 1];
		for (int face = 1; face < scenario.cells(); face++) {
			double below = scenario.cellDensity(face - 1);
			double above = scenario.cellDensity(face);
			if (below == above) {
				result[face] = 1.0 / below;
			} else {
				result[face] = candidate.inverseDensity(
						below, above, scenario.lowerMaterialDistanceFraction());
			}
		}
		result[scenario.cells()] = 1.0 / scenario.cellDensity(scenario.cells() - 1);
		return result;
	}

	private static double[][] pressureMatrix(int cells, double[] inverseDensityByFace) {
		double inverseCellSquared = 1.0 / (CELL_SIZE * CELL_SIZE);
		double[][] matrix = new double[cells][cells];
		for (int cell = 0; cell < cells; cell++) {
			if (cell > 0) {
				double coefficient = inverseDensityByFace[cell] * inverseCellSquared;
				matrix[cell][cell] += coefficient;
				matrix[cell][cell - 1] -= coefficient;
			}
			if (cell + 1 < cells) {
				double coefficient = inverseDensityByFace[cell + 1] * inverseCellSquared;
				matrix[cell][cell] += coefficient;
				matrix[cell][cell + 1] -= coefficient;
			} else {
				// Atmospheric pressure is zero in the air cell above the free surface.
				matrix[cell][cell] += inverseDensityByFace[cells] * inverseCellSquared;
			}
		}
		return matrix;
	}

	private static double[] gravityVelocity(int cells) {
		double[] velocity = new double[cells + 1];
		for (int face = 1; face <= cells; face++) velocity[face] = -GRAVITY * TIME_STEP;
		return velocity;
	}

	private static double[] pressureRightHandSide(int cells, double[] provisionalVelocity) {
		double[] rhs = new double[cells];
		for (int cell = 0; cell < cells; cell++) {
			double divergence = (provisionalVelocity[cell + 1] - provisionalVelocity[cell]) / CELL_SIZE;
			rhs[cell] = -divergence / TIME_STEP;
		}
		return rhs;
	}

	private static double[] expectedHydrostaticPressure(Scenario scenario) {
		double[] pressure = new double[scenario.cells()];
		int top = scenario.cells() - 1;
		pressure[top] = GRAVITY * CELL_SIZE * scenario.cellDensity(top);
		for (int cell = top - 1; cell >= 0; cell--) {
			int face = cell + 1;
			double effectiveDensity = face == scenario.interfaceFace()
					? scenario.interfaceEffectiveDensity()
					: scenario.cellDensity(cell);
			pressure[cell] = pressure[cell + 1] + GRAVITY * CELL_SIZE * effectiveDensity;
		}
		return pressure;
	}

	private static PcgResult solvePcg(double[][] matrix, double[] rhs) {
		int size = rhs.length;
		double[] pressure = new double[size];
		double[] residual = Arrays.copyOf(rhs, size);
		double[] preconditioned = new double[size];
		double[] direction = new double[size];
		double[] matrixDirection = new double[size];
		applyDiagonalPreconditioner(matrix, residual, preconditioned);
		System.arraycopy(preconditioned, 0, direction, 0, size);
		double rz = dot(residual, preconditioned);
		double target = PCG_TOLERANCE * Math.max(1.0, rms(rhs));
		int iterations = 0;
		for (; iterations < MAX_PCG_ITERATIONS && rms(residual) > target; iterations++) {
			multiply(matrix, direction, matrixDirection);
			double denominator = dot(direction, matrixDirection);
			if (!(denominator > 1.0E-30)) throw new IllegalStateException("Variable-density PCG lost positive definiteness");
			double alpha = rz / denominator;
			for (int i = 0; i < size; i++) {
				pressure[i] += alpha * direction[i];
				residual[i] -= alpha * matrixDirection[i];
			}
			if (rms(residual) <= target) { iterations++; break; }
			applyDiagonalPreconditioner(matrix, residual, preconditioned);
			double nextRz = dot(residual, preconditioned);
			if (!(Math.abs(rz) > 1.0E-30)) throw new IllegalStateException("Variable-density PCG recurrence collapsed");
			double beta = nextRz / rz;
			for (int i = 0; i < size; i++) direction[i] = preconditioned[i] + beta * direction[i];
			rz = nextRz;
		}
		return new PcgResult(pressure, iterations, rms(residual));
	}

	private static void applyDiagonalPreconditioner(double[][] matrix, double[] source, double[] destination) {
		for (int i = 0; i < source.length; i++) destination[i] = source[i] / matrix[i][i];
	}

	private static void multiply(double[][] matrix, double[] vector, double[] destination) {
		Arrays.fill(destination, 0.0);
		for (int row = 0; row < matrix.length; row++) {
			for (int column = 0; column < matrix.length; column++) destination[row] += matrix[row][column] * vector[column];
		}
	}

	private static double maximumProjectedVelocity(double[] pressure, double[] provisionalVelocity,
	                                               double[] inverseDensityByFace) {
		double maximum = 0.0;
		for (int face = 1; face < provisionalVelocity.length; face++) {
			double below = pressure[face - 1];
			double above = face < pressure.length ? pressure[face] : 0.0;
			double projected = provisionalVelocity[face]
					- TIME_STEP * inverseDensityByFace[face] * (above - below) / CELL_SIZE;
			maximum = Math.max(maximum, Math.abs(projected));
		}
		return maximum;
	}

	private static double maximumDivergence(double[] pressure, double[] provisionalVelocity,
	                                        double[] inverseDensityByFace) {
		double[] projected = new double[provisionalVelocity.length];
		for (int face = 1; face < projected.length; face++) {
			double below = pressure[face - 1];
			double above = face < pressure.length ? pressure[face] : 0.0;
			projected[face] = provisionalVelocity[face]
					- TIME_STEP * inverseDensityByFace[face] * (above - below) / CELL_SIZE;
		}
		double maximum = 0.0;
		for (int cell = 0; cell < pressure.length; cell++) {
			maximum = Math.max(maximum, Math.abs((projected[cell + 1] - projected[cell]) / CELL_SIZE));
		}
		return maximum;
	}

	private static double maximumHydrostaticVelocityDefect(double[] expectedPressure, double[] provisionalVelocity,
	                                                       double[] inverseDensityByFace) {
		return maximumProjectedVelocity(expectedPressure, provisionalVelocity, inverseDensityByFace);
	}

	private static MatrixDiagnostics inspectMatrix(double[][] matrix) {
		double maximumEntry = 0.0, maximumAsymmetry = 0.0;
		for (int row = 0; row < matrix.length; row++) for (int column = 0; column < matrix.length; column++) {
			maximumEntry = Math.max(maximumEntry, Math.abs(matrix[row][column]));
			maximumAsymmetry = Math.max(maximumAsymmetry, Math.abs(matrix[row][column] - matrix[column][row]));
		}
		double[][] lower = new double[matrix.length][matrix.length];
		double minimumPivot = Double.POSITIVE_INFINITY;
		for (int row = 0; row < matrix.length; row++) {
			for (int column = 0; column <= row; column++) {
				double value = matrix[row][column];
				for (int k = 0; k < column; k++) value -= lower[row][k] * lower[column][k];
				if (row == column) {
					if (!(value > 0.0)) throw new IllegalStateException("Variable-density matrix is not positive definite");
					minimumPivot = Math.min(minimumPivot, value);
					lower[row][column] = Math.sqrt(value);
				} else {
					lower[row][column] = value / lower[column][column];
				}
			}
		}
		return new MatrixDiagnostics(maximumEntry == 0.0 ? 0.0 : maximumAsymmetry / maximumEntry, minimumPivot);
	}

	private static double maximumRelativeError(double[] actual, double[] expected) {
		double maximumExpected = 0.0, maximumError = 0.0;
		for (int i = 0; i < actual.length; i++) {
			maximumExpected = Math.max(maximumExpected, Math.abs(expected[i]));
			maximumError = Math.max(maximumError, Math.abs(actual[i] - expected[i]));
		}
		return maximumError / maximumExpected;
	}

	private static double dot(double[] left, double[] right) {
		double result = 0.0;
		for (int i = 0; i < left.length; i++) result += left[i] * right[i];
		return result;
	}

	private static double rms(double[] values) { return Math.sqrt(dot(values, values) / values.length); }

	enum FaceCoefficient {
		AVERAGE_INVERSE_DENSITY("average(1/rho)") {
			@Override double inverseDensity(double below, double above, double lowerDistanceFraction) {
				return 0.5 * (1.0 / below + 1.0 / above);
			}
		},
		INVERSE_AVERAGE_DENSITY("1/average(rho)") {
			@Override double inverseDensity(double below, double above, double lowerDistanceFraction) {
				return 1.0 / (0.5 * (below + above));
			}
		},
		INTERFACE_DISTANCE_WEIGHTED("interface-distance conservative") {
			@Override double inverseDensity(double below, double above, double lowerDistanceFraction) {
				return 1.0 / (lowerDistanceFraction * below + (1.0 - lowerDistanceFraction) * above);
			}
		};

		private final String label;
		FaceCoefficient(String label) { this.label = label; }
		String label() { return label; }
		abstract double inverseDensity(double below, double above, double lowerDistanceFraction);
	}

	record Scenario(String name, int cells, int interfaceFace, double lowerDensity, double upperDensity,
	                double lowerMaterialDistanceFraction) {
		Scenario {
			if (cells < 3 || interfaceFace <= 0 || interfaceFace >= cells) throw new IllegalArgumentException("Invalid column topology");
			if (!(lowerDensity > 0.0) || !(upperDensity > 0.0)) throw new IllegalArgumentException("Density must be positive");
			if (!(lowerMaterialDistanceFraction > 0.0 && lowerMaterialDistanceFraction < 1.0)) {
				throw new IllegalArgumentException("Interface distance fraction must be inside (0,1)");
			}
		}

		double cellDensity(int cell) { return cell < interfaceFace ? lowerDensity : upperDensity; }
		double interfaceEffectiveDensity() {
			return lowerMaterialDistanceFraction * lowerDensity + (1.0 - lowerMaterialDistanceFraction) * upperDensity;
		}
	}

	record Result(Scenario scenario, FaceCoefficient candidate, double interfaceEffectiveDensity,
	              double pressureRelativeError, double hydrostaticVelocityDefect,
	              double projectedMaximumVelocity, double projectedMaximumDivergence,
	              int pcgIterations, double pcgResidualRms, double symmetryError,
	              double minimumCholeskyPivot) {
		double normalizedHydrostaticVelocityDefect() {
			return hydrostaticVelocityDefect / (GRAVITY * TIME_STEP);
		}
		double score() { return pressureRelativeError + normalizedHydrostaticVelocityDefect(); }
	}

	private record PcgResult(double[] pressure, int iterations, double residualRms) { }
	private record MatrixDiagnostics(double symmetryError, double minimumCholeskyPivot) { }
}
