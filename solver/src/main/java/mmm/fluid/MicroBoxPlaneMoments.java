package mmm.fluid;

import java.util.Arrays;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

/** Box finite differences of simplex monomials; each output uses one final exact quotient. */
final class MicroBoxPlaneMoments {
	private static final SignedDyadic ONE = SignedDyadic.of(1), HALF = SignedDyadic.of(1, -1);
	private static final int[] FACTORIAL = {1, 1, 2, 6, 24, 120, 720};
	private static final int[][] POWERS = {
			{0,0,0}, {1,0,0}, {0,1,0}, {0,0,1}, {2,0,0}, {1,1,0}, {1,0,1}, {0,2,0}, {0,1,1}, {0,0,2},
			{3,0,0}, {2,1,0}, {2,0,1}, {1,2,0}, {1,1,1}, {1,0,2}, {0,3,0}, {0,2,1}, {0,1,2}, {0,0,3}
	};
	private MicroBoxPlaneMoments() { }

	static ExactRational[] integrate(int micro, GasGeometryOverlap.HalfSpace plane, int order) {
		SignedDyadic[] normal = {plane.nx(), plane.ny(), plane.nz()};
		SignedDyadic[] magnitude = new SignedDyadic[3], centre = new SignedDyadic[3], extent = new SignedDyadic[3];
		int[] direction = new int[3], active = new int[3]; int axes = 0;
		SignedDyadic maximum = SignedDyadic.ZERO, totalExtent = SignedDyadic.ZERO;
		for (int a = 0; a < 3; a++) {
			int bit = micro >>> a & 1, sign = normal[a].signum();
			var corner = SignedDyadic.of(bit + (sign > 0 ? 1 : 0), -1);
			maximum = maximum.add(normal[a].multiply(corner)); centre[a] = corner.subtract(HALF);
			magnitude[a] = sign < 0 ? normal[a].negate() : normal[a]; direction[a] = sign > 0 ? -1 : 1;
			extent[a] = magnitude[a].multiply(HALF);
			if (sign != 0) { active[axes++] = a; totalExtent = totalExtent.add(extent[a]); }
		}
		var distance = maximum.subtract(plane.threshold());
		var result = new ExactRational[order == 3 ? 20 : order == 2 ? 10 : 4]; Arrays.fill(result, ExactRational.ZERO);
		if (axes == 0 ? distance.signum() < 0 : distance.signum() <= 0) return result;
		if (distance.compareTo(totalExtent) >= 0) {
			for (int index = 0; index < result.length; index++) {
				int degree = 0, divisor = 1, sign = 1;
				for (int a = 0; a < 3; a++) {
					int p = POWERS[index][a]; degree += p; divisor *= p + 1;
					if ((micro >>> a & 1) == 0 && (p & 1) != 0) sign = -sign;
				}
				result[index] = MicroBoxPlaneVolume.quotient(SignedDyadic.of(sign, -3 - degree), SignedDyadic.of(divisor));
			}
			return result;
		}
		// Reflect from the maximizing corner. z_a=|n_a|*distance_a gives z>=0, sum(z)<=distance.
		var spans = new SignedDyadic[1 << axes][];
		var bases = new SignedDyadic[1 << axes][3][];
		for (int subset = 0; subset < spans.length; subset++) {
			var span = distance;
			for (int i = 0; i < axes; i++) if ((subset >>> i & 1) != 0) span = span.subtract(extent[active[i]]);
			if (span.signum() <= 0) continue;
			spans[subset] = powers(span, axes + order);
			for (int i = 0; i < axes; i++) {
				int a = active[i]; var base = magnitude[a].multiply(centre[a]);
				if ((subset >>> i & 1) != 0) base = base.add(extent[a].multiply(SignedDyadic.of(direction[a])));
				bases[subset][a] = powers(base, order);
			}
		}
		for (int index = 0; index < result.length; index++) {
			int[] p = POWERS[index]; int degree = 0;
			var independent = ONE; int divisor = 1;
			for (int a = 0; a < 3; a++) {
				if (normal[a].signum() != 0) degree += p[a];
				else {
					int sign = (micro >>> a & 1) == 0 && (p[a] & 1) != 0 ? -1 : 1;
					independent = independent.multiply(SignedDyadic.of(sign, -p[a] - 1)); divisor *= p[a] + 1;
				}
			}
			int factorial = FACTORIAL[axes + degree]; var denominator = SignedDyadic.of((long) factorial * divisor);
			for (int i = 0; i < axes; i++) {
				int a = active[i];
				for (int power = 0; power <= p[a]; power++) denominator = denominator.multiply(magnitude[a]);
			}
			SignedDyadic numerator = SignedDyadic.ZERO;
			int px = normal[0].signum() == 0 ? 0 : p[0], py = normal[1].signum() == 0 ? 0 : p[1], pz = normal[2].signum() == 0 ? 0 : p[2];
			for (int subset = 0; subset < spans.length; subset++) if (spans[subset] != null) {
				for (int x = 0; x <= px; x++) for (int y = 0; y <= py; y++) for (int z = 0; z <= pz; z++) {
					int[] j = {x, y, z}; int coefficient = factorial / FACTORIAL[axes + x + y + z];
					if ((Integer.bitCount(subset) & 1) != 0) coefficient = -coefficient;
					var term = spans[subset][axes + x + y + z];
					for (int i = 0; i < axes; i++) {
						int a = active[i];
						// binomial(p,j)*j! is the falling factorial p!/(p-j)!.
						coefficient *= FACTORIAL[p[a]] / FACTORIAL[p[a] - j[a]];
						if (direction[a] < 0 && (j[a] & 1) != 0) coefficient = -coefficient;
						term = term.multiply(bases[subset][a][p[a] - j[a]]);
					}
					numerator = numerator.add(term.multiply(SignedDyadic.of(coefficient)));
				}
			}
			result[index] = MicroBoxPlaneVolume.quotient(numerator.multiply(independent), denominator);
		}
		return result;
	}
	private static SignedDyadic[] powers(SignedDyadic base, int degree) {
		var result = new SignedDyadic[degree + 1]; result[0] = ONE;
		for (int i = 1; i < result.length; i++) result[i] = result[i - 1].multiply(base);
		return result;
	}
}
