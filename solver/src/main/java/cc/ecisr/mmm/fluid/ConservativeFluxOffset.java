package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Solve increments about an exact feasible rate field without rounding its geometric offset. */
final class ConservativeFluxOffset {
	record Observation(double[] values, double[] error) { }
	private final ConservativeFluxCoordinates coordinates;
	private final Fraction[] reference, source;
	private final Fraction[] origin;
	private final Fraction[] exactBaseline;
	private final double[] baseline, zeroSource;
	ConservativeFluxOffset(ConservativeFluxCoordinates coordinates, Fraction[] reference, Fraction[] source) {
		this.coordinates = coordinates; this.reference = reference.clone(); this.source = source.clone(); zeroSource = new double[source.length];
		origin = coordinates.liftExact(reference, source); exactBaseline = origin; baseline = new double[origin.length];
		for (int f = 0; f < origin.length; f++) baseline[f] = origin[f].rounded();
	}
	private ConservativeFluxOffset(ConservativeFluxOffset offset, Fraction[] faceReference) {
		if (faceReference.length != offset.origin.length) throw new IllegalArgumentException("Incomplete flux reference field");
		coordinates = offset.coordinates; reference = offset.reference; source = offset.source; origin = offset.origin; zeroSource = offset.zeroSource;
		exactBaseline = new Fraction[origin.length]; baseline = new double[origin.length];
		for (int f = 0; f < baseline.length; f++) { exactBaseline[f] = origin[f].subtract(faceReference[f]); baseline[f] = exactBaseline[f].rounded(); }
	}
	/** Observes the exact reference difference while retaining the same actual conservative rate field. */
	ConservativeFluxOffset relativeTo(Fraction[] faceReference) { return new ConservativeFluxOffset(this, faceReference); }
	/** Observes the actual lifted field, including exact cancellation between its offset and increment. */
	Observation observe(double[] increment) {
		var exact = coordinates.liftExactHomogeneous(increment); var value = new double[exact.length]; var error = value.clone();
		for (int f = 0; f < exact.length; f++) {
			var a = exactBaseline[f]; var b = exact[f];
			var observed = LiquidBoundedArithmetic.Value.ratio(a.numerator().multiply(b.denominator()).add(b.numerator().multiply(a.denominator())),
					a.denominator().multiply(b.denominator()));
			value[f] = observed.value(); error[f] = observed.error();
		}
		return new Observation(value, error);
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
