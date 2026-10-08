package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.Value;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import static cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.*;

/** The same block inverse of V B'B + (I-DB)'W(I-DB), with certified numerical coefficients. */
final class LiquidPressureEnergyInverse {
	record Result(double[][] matrix, double[][] error, LiquidExactPressureMetric.Factors factors, LiquidExactPressureMetric.Action action,
			double normalizedEnergyTraceUpper, double[] weightUpper, double[][] representationError, double normalizedPerturbationUpper) { }
	private record Key(double density, List<Fraction> geometry) {
		Key(ControlVolumeGradient geometry, double density) { this(density, coordinates(geometry)); }
		private static List<Fraction> coordinates(ControlVolumeGradient geometry) {
			var values = new ArrayList<Fraction>(); values.add(geometry.exactVolume);
			for (int i = 0; i < geometry.indices.length; i++) {
				values.add(geometry.exactWeight[i]); values.addAll(List.of(geometry.exactBasis[i])); values.addAll(List.of(geometry.exactDisplacement[i]));
			}
			return List.copyOf(values);
		}
	}
	private static final ThreadLocal<Scope> CACHE = new ThreadLocal<>();
	/** Bounded private-request reuse; each key contains full geometry and density. */
	static final class Scope implements AutoCloseable {
		private static final long MAXIMUM_COORDINATES = 65_536;
		private final Scope previous = CACHE.get();
		private final LinkedHashMap<Key, Result> values = new LinkedHashMap<>(16, 0.75F, true);
		private long retained;
		private Scope() { CACHE.set(this); }
		private Result get(ControlVolumeGradient geometry, double density) {
			var key = new Key(geometry, density); var result = values.get(key);
			if (result != null) return result;
			result = compute(geometry, density); long count = size(key);
			if (count <= MAXIMUM_COORDINATES) {
				while (retained > MAXIMUM_COORDINATES - count) retained -= size(values.pollFirstEntry().getKey());
				values.put(key, result); retained += count;
			}
			return result;
		}
		private static long size(Key key) { long faces = (key.geometry.size() - 1) / 7; return key.geometry.size() + 3 * faces * faces + 29 * faces + 8; }
		@Override public void close() {
			if (CACHE.get() != this) throw new IllegalStateException("Pressure geometry scope closed out of order");
			if (previous == null) CACHE.remove(); else CACHE.set(previous);
		}
	}
	static Scope openScope() { return new Scope(); }
	private LiquidPressureEnergyInverse() { }
	static Result build(ControlVolumeGradient geometry, double density) {
		var scope = CACHE.get(); return scope == null ? compute(geometry, density) : scope.get(geometry, density);
	}
	private static Result compute(ControlVolumeGradient geometry, double density) {
		int n = geometry.indices.length;
		var basis = new Value[n][3]; var displacement = new Value[n][3]; var weight = new Value[n];
		for (int i = 0; i < n; i++) {
			weight[i] = Value.of(geometry.exactWeight[i]);
			for (int a = 0; a < 3; a++) { basis[i][a] = Value.of(geometry.exactBasis[i][a]); displacement[i][a] = Value.of(geometry.exactDisplacement[i][a]); }
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
		var certified = LiquidCertifiedPressureMetric.build(geometry, density, weightUpper, Math.nextDown(1 / trace.magnitude()));
		var exact = certified.factors(); var result = new double[n][n]; var error = new double[n][n];
		for (int i = 0; i < n; i++) for (int j = 0; j <= i; j++) {
			result[i][j] = result[j][i] = exact.coefficient(i, j).rounded();
			error[i][j] = error[j][i] = Math.nextUp(certified.error()[i][j] + Math.ulp(result[i][j]));
			if (!Double.isFinite(result[i][j]) || i == j && !(result[i][j] > 0)) throw new IllegalStateException("Pressure metric exceeds its coefficient observation range");
		}
		return new Result(result, error, exact, new LiquidExactPressureMetric.Action(exact), trace.magnitude(), weightUpper, certified.error(), certified.normalizedPerturbationUpper());
	}
}
