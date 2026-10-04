package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Proportional integrated rates retain the exact geometric measure of every support. */
final class LiquidFluxAllocation {
	private LiquidFluxAllocation() { }
	static Fraction[] proportional(Fraction total, Fraction[] weights) {
		var sum = Fraction.ZERO;
		for (var weight : weights) {
			if (weight.signum() <= 0) throw new IllegalArgumentException("Non-positive flux allocation support"); sum = sum.add(weight);
		}
		if (weights.length == 0 && total.signum() != 0) throw new IllegalArgumentException("Rate has no geometric support");
		var result = new Fraction[weights.length];
		for (int i = 0; i < result.length; i++) result[i] = total.signum() == 0 ? Fraction.ZERO : total.multiply(weights[i]).divide(sum);
		return result;
	}
}
