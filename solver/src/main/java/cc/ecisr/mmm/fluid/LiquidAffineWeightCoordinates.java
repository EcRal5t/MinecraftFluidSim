package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.Value;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.math.BigInteger;

/** Finite independent interpolation weights with exact partition-of-unity and affine constraints. */
final class LiquidAffineWeightCoordinates {
	record Result(Map<Integer, Fraction> weights, Map<Integer, Double> errors) { }
	private LiquidAffineWeightCoordinates() { }
	static Result complete(Vertex point, LiquidAffineFit.Basis basis, List<Integer> patch,
			List<Vertex> centers, Map<Integer, Value> observed) {
		var anchors = new java.util.HashSet<Integer>();
		for (int local : basis.anchor) anchors.add(patch.get(local));
		var weights = new LinkedHashMap<Integer, Fraction>(); var errors = new LinkedHashMap<Integer, Double>();
		for (var entry : observed.entrySet()) if (!anchors.contains(entry.getKey())) {
			weights.put(entry.getKey(), Fraction.of(entry.getValue().value())); errors.put(entry.getKey(), entry.getValue().error());
		}
		// All four affine moments share one denominator; each completed coefficient needs one final reduction.
		var divisor = point.w();
		for (var entry : weights.entrySet()) {
			var local = centers.get(entry.getKey()).w().multiply(entry.getValue().denominator());
			divisor = divisor.divide(LiquidIntegerArithmetic.gcd(divisor, local)).multiply(local);
		}
		var factor = divisor.divide(point.w());
		var remaining = new BigInteger[]{point.x().multiply(factor), point.y().multiply(factor), point.z().multiply(factor), divisor};
		for (var entry : weights.entrySet()) {
			var center = centers.get(entry.getKey()); var weight = entry.getValue();
			factor = weight.numerator().multiply(divisor.divide(center.w().multiply(weight.denominator())));
			for (int a = 0; a < 3; a++) remaining[a] = remaining[a].subtract(center.coordinate(a).multiply(factor));
			remaining[3] = remaining[3].subtract(center.w().multiply(factor));
		}
		for (int a = 0; a < basis.anchor.length; a++) {
			var shape = basis.shape[a]; var value = shape.evaluate(remaining, divisor); double error = 0;
			for (var entry : observed.entrySet()) if (!anchors.contains(entry.getKey())) {
				if (entry.getValue().error() != 0) {
					var magnitude = shape.magnitude(centers.get(entry.getKey()));
					if (magnitude != 0) error = LiquidBoundedArithmetic.up(error + LiquidBoundedArithmetic.up(magnitude * entry.getValue().error()));
				}
			}
			int cell = patch.get(basis.anchor[a]); weights.put(cell, value); errors.put(cell, error);
		}
		return new Result(java.util.Collections.unmodifiableMap(weights), java.util.Collections.unmodifiableMap(errors));
	}
}
