package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local finite volume coordinates preserve every leaf capacity and global phase inventory. */
final class LiquidPhaseVolumeRounding {
	private LiquidPhaseVolumeRounding() { }
	static List<Map<Integer, Fraction>> round(List<Map<Integer, Fraction>> reference, Map<Integer, Fraction> totals,
			int coordinateBits, List<Fraction> maximumQuantum) {
		if (maximumQuantum.size() != reference.size() || maximumQuantum.stream().anyMatch(value -> value.signum() <= 0)
				|| coordinateBits < 1 || coordinateBits > 1074) throw new IllegalArgumentException("Invalid phase volume coordinate support");
		var columns = new LinkedHashMap<Integer, Integer>();
		for (var row : reference) for (int phase : row.keySet()) columns.computeIfAbsent(phase, ignored -> columns.size());
		if (!columns.keySet().equals(totals.keySet())) throw new IllegalArgumentException("Incomplete phase volume inventories");
		var first = new ArrayList<Integer>(); var second = new ArrayList<Integer>(); var values = new ArrayList<Fraction>();
		var phases = new ArrayList<Integer>(); var source = new Fraction[reference.size() + columns.size()]; Arrays.fill(source, Fraction.ZERO);
		int largestExponent = Integer.MIN_VALUE;
		for (int r = 0; r < reference.size(); r++) for (var entry : reference.get(r).entrySet()) {
			var value = entry.getValue(); if (value.signum() <= 0) throw new IllegalArgumentException("Invalid local phase volume");
			int c = reference.size() + columns.get(entry.getKey()); first.add(r); second.add(c); values.add(value); phases.add(entry.getKey());
			source[r] = source[r].add(value); source[c] = source[c].subtract(value);
			largestExponent = Math.max(largestExponent, exponent(value));
		}
		for (var entry : totals.entrySet()) if (!source[reference.size() + columns.get(entry.getKey())].negate().equals(entry.getValue()))
			throw new IllegalArgumentException("Local phase volumes disagree with inventory");
		var constrained = new boolean[source.length]; Arrays.fill(constrained, true); var weights = new double[values.size()];
		for (int e = 0; e < weights.length; e++) weights[e] = 1.0 + largestExponent - exponent(values.get(e));
		// Large volume entries carry the tree closure; small entries receive their own relative coordinate precision.
		var coordinates = new ConservativeFluxCoordinates(source.length, first.stream().mapToInt(Integer::intValue).toArray(),
				second.stream().mapToInt(Integer::intValue).toArray(), constrained, new Fraction[values.size()], weights);
		int bits = coordinateBits;
		for (;;) {
			var independent = new Fraction[coordinates.size()];
			for (int e = 0; e < independent.length; e++) independent[e] = lower(values.get(coordinates.face(e)), bits);
			var rounded = coordinates.liftExact(independent, source); boolean accepted = true;
			for (int e = 0; e < rounded.length; e++) accepted &= rounded[e].signum() > 0
					&& rounded[e].subtract(values.get(e)).absolute().compareTo(maximumQuantum.get(first.get(e))) <= 0;
			if (accepted) {
				var result = new ArrayList<Map<Integer, Fraction>>();
				for (int r = 0; r < reference.size(); r++) result.add(new LinkedHashMap<>());
				for (int e = 0; e < rounded.length; e++) result.get(first.get(e)).put(phases.get(e), rounded[e]);
				return result.stream().map(row -> java.util.Collections.unmodifiableMap(row)).toList();
			}
			if (bits == 1074) throw new LiquidCoordinateResolutionRequired("Positive phase volume exceeds finite volume coordinate support");
			bits = Math.min(1074, 2 * bits);
		}
	}
	private static int exponent(Fraction value) { return value.numerator().bitLength() - value.denominator().bitLength(); }
	private static Fraction lower(Fraction value, int bits) {
		int shift = bits - exponent(value);
		return shift >= 0 ? new Fraction(value.numerator().shiftLeft(shift).divide(value.denominator()), BigInteger.ONE.shiftLeft(shift))
				: new Fraction(value.numerator().divide(value.denominator().shiftLeft(-shift)).shiftLeft(-shift), BigInteger.ONE);
	}
}
