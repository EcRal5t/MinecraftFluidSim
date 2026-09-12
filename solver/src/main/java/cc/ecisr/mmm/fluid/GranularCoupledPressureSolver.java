package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** Double-precision PCG for the pressure Schur system with finite, shared drag inertia. */
final class GranularCoupledPressureSolver {
	private final FluidDomain domain;
	private final GranularImplicitDrag drag;
	private final GranularPressurePreconditioner preconditioner;
	private final double[] pressure, rhs, residual, direction, product, diagonal, preconditioned;
	private final double[] reference, resolvedPressure;
	private final int[] unknowns;
	private int count;

	GranularCoupledPressureSolver(FluidDomain domain, GranularImplicitDrag drag) {
		this.domain = domain;
		this.drag = drag;
		preconditioner = new GranularPressurePreconditioner(domain);
		int size = 2 * domain.cellCount + 1;
		pressure = new double[size]; rhs = new double[size]; residual = new double[size];
		direction = new double[size]; product = new double[size]; diagonal = new double[size];
		preconditioned = new double[size];
		reference = new double[size]; resolvedPressure = new double[size];
		unknowns = new int[size];
	}

	double[] pressure() { return resolvedPressure; }
	double[] reference() { return reference; }
	void reset() {
		Arrays.fill(pressure, 0); Arrays.fill(direction, 0); Arrays.fill(reference, 0);
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			int node = domain.projection.gasUnknownNodes[ordinal];
			reference[domain.cellCount + node] = domain.pressureBoundaryView.gasPressureOfNode(node) - domain.pressureReference;
		}
		System.arraycopy(reference, 0, resolvedPressure, 0, reference.length);
	}

	PressureProjectionResult solve(GranularPressureCoupling granular, double target) {
		count = 0;
		double h = domain.config.cellSize();
		for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
			int cell = domain.projection.fluidUnknownCells[ordinal];
			unknowns[count++] = cell;
			diagonal[cell] = domain.projection.matrixDiagonal[cell] / (h * h) + granular.liquidDiagonal(cell);
		}
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			int node = domain.projection.gasUnknownNodes[ordinal], row = domain.cellCount + node;
			unknowns[count++] = row;
			diagonal[row] = domain.projection.gasMatrixDiagonal[node] + granular.gasDiagonal(node);
		}
		preconditioner.prepare(unknowns, count);
		drag.buildRhs(rhs);
		// Geometry is fixed during the nonlinear solve. Reuse its previous pressure,
		// but recompute the residual against the updated resistance and right-hand side.
		drag.applyMatrix(pressure, product);
		for (int ordinal = 0; ordinal < count; ordinal++) {
			int row = unknowns[ordinal];
			residual[row] = rhs[row] - product[row];
		}
		preconditioner.apply(unknowns, count, diagonal, residual, direction);
		double rz = dot(residual, direction);
		double rms = norm(residual);
		int iterations = 0;
		for (; rms > target && iterations < domain.config.maxPressureIterations(); iterations++) {
			drag.applyMatrix(direction, product);
			double denominator = dot(direction, product);
			if (!(denominator > 0 && Double.isFinite(denominator) && rz > 0 && Double.isFinite(rz))) break;
			double alpha = rz / denominator;
			for (int ordinal = 0; ordinal < count; ordinal++) {
				int row = unknowns[ordinal];
				pressure[row] += alpha * direction[row];
				residual[row] -= alpha * product[row];
			}
			rms = norm(residual);
			if (rms <= target) {
				// Check the actual equations, including the nested inertia solve, before accepting PCG.
				drag.applyMatrix(pressure, product);
				for (int ordinal = 0; ordinal < count; ordinal++) {
					int row = unknowns[ordinal];
					residual[row] = rhs[row] - product[row];
				}
				rms = norm(residual);
				if (rms <= target) { iterations++; break; }
				for (int ordinal = 0; ordinal < count; ordinal++) direction[unknowns[ordinal]] = 0;
				rz = Double.POSITIVE_INFINITY;
			}
			preconditioner.apply(unknowns, count, diagonal, residual, preconditioned);
			double nextRz = dot(residual, preconditioned);
			double beta = nextRz / rz;
			for (int ordinal = 0; ordinal < count; ordinal++) {
				int row = unknowns[ordinal];
				direction[row] = preconditioned[row] + beta * direction[row];
			}
			rz = nextRz;
		}
		for (int ordinal = 0; ordinal < count; ordinal++) {
			int row = unknowns[ordinal];
			resolvedPressure[row] = reference[row] + pressure[row];
		}
		for (int ordinal = 0; ordinal < domain.projection.fluidUnknownCount; ordinal++) {
			int cell = domain.projection.fluidUnknownCells[ordinal];
			domain.projection.pressure[cell] = (float) resolvedPressure[cell];
		}
		for (int ordinal = 0; ordinal < domain.projection.gasUnknownCount; ordinal++) {
			int node = domain.projection.gasUnknownNodes[ordinal];
			domain.projection.gasPressure[node] = (float) resolvedPressure[domain.cellCount + node];
		}
		return new PressureProjectionResult(iterations, rms, Double.isFinite(rms) && rms <= target);
	}

	private double norm(double[] vector) { return count == 0 ? 0 : Math.sqrt(dot(vector, vector) / count); }
	private double dot(double[] a, double[] b) {
		double sum = 0;
		for (int ordinal = 0; ordinal < count; ordinal++) {
			int row = unknowns[ordinal];
			sum += a[row] * b[row];
		}
		return sum;
	}
}
