package mmm.fluid;

import java.math.BigInteger;
import java.util.Arrays;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

/** Independent exact active-subset reference for a contact cone with at most three rows. */
final class GranularContactSmallCone {
	private GranularContactSmallCone() { }

	static void solve(double[] mass, double[][] input, GranularContactCompression.Row[] rows, double[] lambda, Runnable spend) {
		int count = rows.length;
		if (count > 3) throw new IllegalArgumentException("Small contact cone row budget exceeded");
		ExactRational[][] normals = new ExactRational[count][3], velocity = new ExactRational[mass.length][3];
		ExactRational[] inverseMass = new ExactRational[mass.length], q = new ExactRational[count];
		for (int i = 0; i < mass.length; i++) {
			spend.run(); inverseMass[i] = inverse(exact(mass[i]));
			for (int a = 0; a < 3; a++) { spend.run(); velocity[i][a] = exact(input[i][a]); }
		}
		for (int i = 0; i < count; i++) {
			q[i] = ExactRational.ZERO;
			for (int a = 0; a < 3; a++) {
				spend.run(); normals[i][a] = exact(rows[i].normal()[a]);
				var v = rows[i].second() < 0 ? velocity[rows[i].first()][a]
						: velocity[rows[i].second()][a].subtract(velocity[rows[i].first()][a]);
				q[i] = q[i].add(normals[i][a].multiply(v));
			}
		}
		ExactRational[][] gram = new ExactRational[count][count];
		for (int i = 0; i < count; i++) for (int j = 0; j <= i; j++) {
			var dot = ExactRational.ZERO;
			for (int a = 0; a < 3; a++) { spend.run(); dot = dot.add(normals[i][a].multiply(normals[j][a])); }
			var value = ExactRational.ZERO;
			for (int source : new int[]{rows[i].first(), rows[i].second()}) if (source >= 0) {
				spend.run(); int sign = coefficient(rows[i], source) * coefficient(rows[j], source);
				if (sign != 0) value = value.add(dot.multiply(sign).multiply(inverseMass[source]));
			}
			gram[i][j] = value; gram[j][i] = value;
		}
		for (int subset = 0; subset < 1 << count; subset++) {
			spend.run(); int[] active = new int[Integer.bitCount(subset)]; int next = 0;
			for (int i = 0; i < count; i++) if ((subset & 1 << i) != 0) active[next++] = i;
			var solution = solveSubset(gram, q, active, spend); if (solution == null) continue;
			ExactRational[] candidate = new ExactRational[count]; Arrays.fill(candidate, ExactRational.ZERO);
			boolean valid = true;
			for (int i = 0; i < active.length; i++) { spend.run(); candidate[active[i]] = solution[i]; valid &= solution[i].signum() >= 0; }
			if (!valid) continue;
			for (int i = 0; i < count; i++) {
				spend.run(); var gap = q[i];
				for (int j = 0; j < count; j++) { spend.run(); gap = gap.add(gram[i][j].multiply(candidate[j])); }
				valid &= gap.signum() >= 0 && (candidate[i].isZero() || gap.isZero());
			}
			if (!valid) continue;
			for (int i = 0; i < count; i++) {
				spend.run(); lambda[i] = candidate[i].toDouble();
				if (!Double.isFinite(lambda[i]) || candidate[i].signum() > 0 && lambda[i] == 0)
					throw new MechanicalTimeRefinement(2, "Unrepresentable exact contact impulse");
			}
			return;
		}
		throw new MechanicalTimeRefinement(2, "Small contact cone has no certified independent active subset");
	}

	private static ExactRational[] solveSubset(ExactRational[][] gram, ExactRational[] q, int[] active, Runnable spend) {
		int count = active.length; ExactRational[][] matrix = new ExactRational[count][count + 1];
		for (int i = 0; i < count; i++) {
			for (int j = 0; j < count; j++) { spend.run(); matrix[i][j] = gram[active[i]][active[j]]; }
			spend.run(); matrix[i][count] = q[active[i]].multiply(-1);
		}
		for (int column = 0; column < count; column++) {
			int pivot = column;
			while (pivot < count && matrix[pivot][column].isZero()) { spend.run(); pivot++; }
			if (pivot == count) return null;
			spend.run(); var swap = matrix[column]; matrix[column] = matrix[pivot]; matrix[pivot] = swap;
			var scale = inverse(matrix[column][column]); matrix[column][column] = ExactRational.ONE;
			for (int j = column + 1; j <= count; j++) { spend.run(); matrix[column][j] = matrix[column][j].multiply(scale); }
			for (int i = 0; i < count; i++) if (i != column) {
				spend.run(); var factor = matrix[i][column]; matrix[i][column] = ExactRational.ZERO;
				for (int j = column + 1; j <= count; j++) {
					spend.run(); matrix[i][j] = matrix[i][j].subtract(factor.multiply(matrix[column][j]));
				}
			}
		}
		ExactRational[] result = new ExactRational[count];
		for (int i = 0; i < count; i++) { spend.run(); result[i] = matrix[i][count]; }
		return result;
	}

	private static int coefficient(GranularContactCompression.Row row, int source) {
		return row.first() == source ? row.second() < 0 ? 1 : -1 : row.second() == source ? 1 : 0;
	}
	private static ExactRational inverse(ExactRational value) { return new ExactRational(value.denominator(), value.numerator()); }
	private static ExactRational exact(double value) {
		var dyadic = SignedDyadic.of(value);
		return dyadic.exponent() >= 0 ? new ExactRational(dyadic.significand().shiftLeft(dyadic.exponent()), BigInteger.ONE)
				: new ExactRational(dyadic.significand(), BigInteger.ONE.shiftLeft(-dyadic.exponent()));
	}
}
