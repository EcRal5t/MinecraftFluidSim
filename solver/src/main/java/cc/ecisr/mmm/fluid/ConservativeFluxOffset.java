package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Solve increments about an exact feasible rate field without rounding its geometric offset. */
final class ConservativeFluxOffset {
	private final ConservativeFluxCoordinates coordinates;
	private final Fraction[] reference, source;
	private final double[] baseline, zeroSource;
	ConservativeFluxOffset(ConservativeFluxCoordinates coordinates, Fraction[] reference, Fraction[] source) {
		this.coordinates = coordinates; this.reference = reference.clone(); this.source = source.clone(); zeroSource = new double[source.length];
		var rates = coordinates.liftExact(reference, source); baseline = new double[rates.length];
		for (int f = 0; f < rates.length; f++) baseline[f] = rates[f].rounded();
	}
	double[] values(double[] increment) {
		var result = coordinates.lift(increment, zeroSource, false);
		for (int f = 0; f < result.length; f++) result[f] += baseline[f]; return result;
	}
	double[] error(double[] increment) {
		var result = coordinates.liftError(increment, zeroSource, false); var values = values(increment);
		for (int f = 0; f < result.length; f++) result[f] += Math.ulp(baseline[f]) + Math.ulp(values[f]); return result;
	}
	Fraction[] exact(double[] increment) {
		var value = new Fraction[reference.length];
		for (int i = 0; i < value.length; i++) value[i] = reference[i].add(Fraction.of(increment[i])); return coordinates.liftExact(value, source);
	}
}
