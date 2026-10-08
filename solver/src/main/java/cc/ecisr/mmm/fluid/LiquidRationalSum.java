package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Certified sum comparisons avoid joining unrelated rational denominators unless their bounds overlap. */
final class LiquidRationalSum {
	private LiquidRationalSum() { }
	static int compare(List<Fraction> first, List<Fraction> second) {
		var counts = new LinkedHashMap<Fraction, Integer>();
		for (var value : first) if (value.signum() != 0) counts.merge(value, 1, Integer::sum);
		for (var value : second) if (value.signum() != 0) counts.merge(value, -1, Integer::sum);
		var remainingFirst = new ArrayList<Fraction>(); var remainingSecond = new ArrayList<Fraction>();
		for (var entry : counts.entrySet()) for (int n = 0; n < Math.abs(entry.getValue()); n++)
			(entry.getValue() > 0 ? remainingFirst : remainingSecond).add(entry.getKey());
		var a = interval(remainingFirst); var b = interval(remainingSecond);
		if (a.high().compareTo(b.low()) < 0) return -1;
		if (a.low().compareTo(b.high()) > 0) return 1;
		if (a.low().equals(a.high()) && b.low().equals(b.high())) return a.low().compareTo(b.low());
		return exact(remainingFirst).compareTo(exact(remainingSecond));
	}
	private static LiquidDyadicInterval interval(List<Fraction> values) {
		var total = LiquidDyadicInterval.of(Fraction.ZERO, 64);
		for (var value : values) total = total.add(LiquidDyadicInterval.of(value, 64));
		return total;
	}
	private static Fraction exact(List<Fraction> values) {
		var total = Fraction.ZERO; for (var value : values) total = total.add(value); return total;
	}
}
