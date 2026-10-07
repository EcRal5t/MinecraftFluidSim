package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.Value;
import static cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.*;

/** The same block inverse of V B'B + (I-DB)'W(I-DB), with certified numerical coefficients. */
final class LiquidPressureEnergyInverse {
	record Result(double[][] matrix, double[][] error, LiquidExactPressureMetric.Factors factors, double normalizedEnergyTraceUpper, double[] weightUpper) { }
	private LiquidPressureEnergyInverse() { }
	static Result build(ControlVolumeGradient geometry, double density) {
		int n = geometry.indices.length;
		var basis = new Value[n][3]; var displacement = new Value[n][3]; var weight = new Value[n];
		for (int i = 0; i < n; i++) {
			weight[i] = Value.of(geometry.exactWeight[i]);
			for (int a = 0; a < 3; a++) { basis[i][a] = Value.of(geometry.exactBasis[i][a]); displacement[i][a] = Value.of(geometry.exactDisplacement[i][a]); }
		}
		var exact = LiquidExactPressureMetric.build(geometry, density);
		var result = new double[n][n]; var error = new double[n][n];
		var dual = observed(exact.dual()); var coupling = observed(exact.coupling());
		var lifting = observed(exact.lifting()); var spatialDual = observed(exact.spatialDual());
		for (int i = 0; i < n; i++) for (int j = 0; j <= i; j++) {
			if (i == j) { result[i][j] = exact.coefficient(i, j).rounded(); error[i][j] = Math.ulp(result[i][j]); }
			else {
				var value = ZERO;
				for (int a = 0; a < 3; a++) value = value.subtract(coupling[i][a].multiply(dual[j][a])).add(spatialDual[i][a].multiply(lifting[j][a]));
				value = value.multiply(new Value(density, 0)); result[i][j] = result[j][i] = value.value(); error[i][j] = error[j][i] = value.error();
			}
			if (!Double.isFinite(result[i][j]) || i == j && !(result[i][j] > 0)) throw new IllegalStateException("Pressure metric exceeds its coefficient observation range");
		}
		var volume = Value.of(geometry.exactVolume);
		var trace = ZERO;
		for (int i = 0; i < n; i++) {
			for (int a = 0; a < 3; a++) trace = trace.add(volume.multiply(basis[i][a].multiply(basis[i][a])).divide(weight[i]));
			for (int j = 0; j < n; j++) {
				var residual = i == j ? ONE : ZERO;
				for (int a = 0; a < 3; a++) residual = residual.subtract(displacement[i][a].multiply(basis[j][a]));
				trace = trace.add(weight[i].multiply(residual.multiply(residual)).divide(weight[j]));
			}
		}
		var weightUpper = new double[n]; for (int i = 0; i < n; i++) weightUpper[i] = weight[i].magnitude();
		return new Result(result, error, exact, trace.magnitude(), weightUpper);
	}
	private static Value[][] observed(LiquidExactPolygon.Fraction[][] exact) {
		var result = new Value[exact.length][3];
		for (int i = 0; i < result.length; i++) for (int a = 0; a < 3; a++) result[i][a] = Value.of(exact[i][a]);
		return result;
	}
}
