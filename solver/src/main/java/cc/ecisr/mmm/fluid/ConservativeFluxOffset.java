package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Solve increments about an exact feasible rate field without rounding its geometric offset. */
final class ConservativeFluxOffset {
	private final ConservativeFluxCoordinates coordinates;
	private final Fraction[] reference, source;
	private final Fraction[] origin;
	private final double[] baseline, zeroSource;
	ConservativeFluxOffset(ConservativeFluxCoordinates coordinates, Fraction[] reference, Fraction[] source) {
		this.coordinates = coordinates; this.reference = reference.clone(); this.source = source.clone(); zeroSource = new double[source.length];
		origin = coordinates.liftExact(reference, source); baseline = new double[origin.length];
		for (int f = 0; f < origin.length; f++) baseline[f] = origin[f].rounded();
	}
	private ConservativeFluxOffset(ConservativeFluxOffset offset, Fraction[] faceReference) {
		if (faceReference.length != offset.origin.length) throw new IllegalArgumentException("Incomplete flux reference field");
		coordinates = offset.coordinates; reference = offset.reference; source = offset.source; origin = offset.origin; zeroSource = offset.zeroSource;
		baseline = new double[origin.length]; for (int f = 0; f < baseline.length; f++) baseline[f] = origin[f].subtract(faceReference[f]).rounded();
	}
	/** Observes the exact reference difference while retaining the same actual conservative rate field. */
	ConservativeFluxOffset relativeTo(Fraction[] faceReference) { return new ConservativeFluxOffset(this, faceReference); }
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
