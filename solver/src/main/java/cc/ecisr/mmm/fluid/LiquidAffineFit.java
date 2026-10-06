package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.Value;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.*;
import static cc.ecisr.mmm.fluid.LiquidIntegerArithmetic.gcd;

/** Certified weighted least squares, completed to exact affine moments on an independent simplex. */
final class LiquidAffineFit {
	final Form[] shape;
	private final Form[] coordinates;
	private final double[][] coefficientError;
	private final Basis basis;

	LiquidAffineFit(List<Fraction[]> centers, List<Fraction> weights) {
		if (centers.isEmpty() || weights.size() != centers.size()) throw new IllegalArgumentException("Incomplete affine observations");
		basis = new Basis(centers); coordinates = basis.coordinate;
		int n = centers.size(), rank = coordinates.length;
		shape = new Form[n]; coefficientError = new double[n][rank + 1];
		for (var weight : weights) if (weight.signum() <= 0) throw new IllegalArgumentException("Affine observations require positive volumes");
		// A determined affine interpolant already minimizes every positive weighted residual exactly.
		if (n == basis.anchor.length) {
			for (int j = 0; j < basis.anchor.length; j++) shape[basis.anchor[j]] = basis.shape[j]; return;
		}
		var position = new Value[n][rank]; var volume = new Value[n]; var total = ZERO;
		var mean = new Value[rank]; Arrays.fill(mean, ZERO);
		for (int i = 0; i < n; i++) {
			volume[i] = Value.of(weights.get(i)); total = total.add(volume[i]); var point = LiquidExactPolygon.vertex(centers.get(i));
			for (int a = 0; a < rank; a++) { position[i][a] = Value.of(coordinates[a].evaluate(point)); mean[a] = mean[a].add(volume[i].multiply(position[i][a])); }
		}
		for (int a = 0; a < rank; a++) mean[a] = mean[a].divide(total);
		var direction = new Value[n][rank];
		for (int i = 0; i < n; i++) for (int a = 0; a < rank; a++) direction[i][a] = position[i][a].subtract(mean[a]);
		var gram = new Value[rank][rank];
		for (int a = 0; a < rank; a++) for (int b = 0; b < rank; b++) {
			gram[a][b] = ZERO; for (int i = 0; i < n; i++) gram[a][b] = gram[a][b].add(volume[i].multiply(direction[i][a]).multiply(direction[i][b]));
		}
		var inverse = rank == 0 ? new Value[0][0] : LiquidBoundedMatrix.inverse(gram);
		var anchorRows = new Fraction[basis.anchor.length][];
		for (int j = 0; j < anchorRows.length; j++) anchorRows[j] = basis.shape[j].coefficients.clone();
		for (int i = 0; i < n; i++) {
			boolean anchor = false; for (int selected : basis.anchor) anchor |= selected == i; if (anchor) continue;
			var local = new Value[rank + 1]; local[rank] = volume[i].divide(total);
			var row = zeroRow();
			for (int a = 0; a < rank; a++) {
				local[a] = ZERO;
				for (int b = 0; b < rank; b++) local[a] = local[a].add(volume[i].multiply(inverse[a][b]).multiply(direction[i][b]));
				local[rank] = local[rank].subtract(local[a].multiply(mean[a]));
				add(row, coordinates[a].coefficients, Fraction.of(local[a].value()));
			}
			row[3] = row[3].add(Fraction.of(local[rank].value())); shape[i] = new Form(row);
			for (int a = 0; a <= rank; a++) coefficientError[i][a] = local[a].error();
			var point = LiquidExactPolygon.vertex(centers.get(i));
			for (int j = 0; j < basis.anchor.length; j++) {
				var barycentric = basis.shape[j].evaluate(point); add(anchorRows[j], row, barycentric.negate());
				double magnitude = magnitude(barycentric);
				for (int a = 0; a <= rank; a++) coefficientError[basis.anchor[j]][a] = up(coefficientError[basis.anchor[j]][a] + up(magnitude * local[a].error()));
			}
		}
		for (int j = 0; j < basis.anchor.length; j++) shape[basis.anchor[j]] = new Form(anchorRows[j]);
	}
	/** Exact affine completion reproduces every point in the actual observation span. */
	boolean contains(Vertex point) { return basis.contains(point); }

	double[] weightErrors(Vertex point) {
		int rank = coordinates.length; var magnitude = new double[rank]; var result = new double[shape.length];
		for (int a = 0; a < rank; a++) magnitude[a] = coordinates[a].magnitude(point);
		for (int i = 0; i < shape.length; i++) {
			result[i] = coefficientError[i][rank];
			for (int a = 0; a < rank; a++) if (coefficientError[i][a] != 0 && magnitude[a] != 0) result[i] = up(result[i] + up(coefficientError[i][a] * magnitude[a]));
			if (!Double.isFinite(result[i])) throw new IllegalStateException("Affine interpolation has no finite observation error bound");
		}
		return result;
	}
	private static double magnitude(Fraction value) { double observed = value.rounded(); return value.signum() == 0 ? 0 : up(Math.abs(observed) + Math.ulp(observed)); }
	private static Fraction[] zeroRow() { return new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO, Fraction.ZERO}; }
	private static void add(Fraction[] target, Fraction[] row, Fraction factor) { for (int a = 0; a < 4; a++) target[a] = target[a].add(row[a].multiply(factor)); }
	private static Fraction dot(Fraction[] first, Fraction[] second) { var result = Fraction.ZERO; for (int a = 0; a < 3; a++) result = result.add(first[a].multiply(second[a])); return result; }
	private static Fraction[] cross(Fraction[] first, Fraction[] second) {
		return new Fraction[]{first[1].multiply(second[2]).subtract(first[2].multiply(second[1])), first[2].multiply(second[0]).subtract(first[0].multiply(second[2])), first[0].multiply(second[1]).subtract(first[1].multiply(second[0]))};
	}
	private static Fraction[] scale(Fraction[] vector, Fraction factor) { return Arrays.stream(vector).map(value -> value.multiply(factor)).toArray(Fraction[]::new); }

	/** Same rational affine form; homogeneous evaluation performs one final reduction. */
	static final class Form {
		final Fraction[] coefficients;
		private final BigInteger[] numerator = new BigInteger[4];
		private final BigInteger denominator;
		Form(Fraction[] coefficients) {
			this.coefficients = coefficients.clone(); var common = BigInteger.ONE;
			for (var value : coefficients) common = common.divide(gcd(common, value.denominator())).multiply(value.denominator()); denominator = common;
			for (int a = 0; a < 4; a++) numerator[a] = coefficients[a].numerator().multiply(common.divide(coefficients[a].denominator()));
		}
		static Form combine(Form[] forms, Fraction[] weights) {
			if (forms.length != weights.length) throw new IllegalArgumentException("Incomplete affine combination");
			var common = BigInteger.ONE; var local = new BigInteger[forms.length];
			for (int i = 0; i < forms.length; i++) if (weights[i].signum() != 0) {
				local[i] = forms[i].denominator.multiply(weights[i].denominator()); common = common.divide(gcd(common, local[i])).multiply(local[i]);
			}
			var numerator = new BigInteger[4]; Arrays.fill(numerator, BigInteger.ZERO);
			for (int i = 0; i < forms.length; i++) if (weights[i].signum() != 0) {
				var factor = weights[i].numerator().multiply(common.divide(local[i]));
				for (int a = 0; a < 4; a++) numerator[a] = numerator[a].add(forms[i].numerator[a].multiply(factor));
			}
			var coefficients = new Fraction[4]; for (int a = 0; a < 4; a++) coefficients[a] = new Fraction(numerator[a], common); return new Form(coefficients);
		}
		Fraction evaluate(Vertex point) {
			return new Fraction(numeratorAt(point), denominator.multiply(point.w()));
		}
		double magnitude(Vertex point) {
			var value = numeratorAt(point); if (value.signum() == 0) return 0;
			double observed = LiquidExactPolygon.roundedRatio(value, denominator.multiply(point.w())); return up(Math.abs(observed) + Math.ulp(observed));
		}
		private BigInteger numeratorAt(Vertex point) {
			var value = numerator[3].multiply(point.w()); for (int a = 0; a < 3; a++) value = value.add(numerator[a].multiply(point.coordinate(a))); return value;
		}
	}
	private static final class Basis {
		final int[] anchor;
		final Form[] shape, coordinate;
		private final Vertex reference;
		private final List<Vertex> directions = new ArrayList<>();
		Basis(List<Fraction[]> centers) {
			reference = LiquidExactPolygon.vertex(centers.getFirst());
			var chosen = new ArrayList<Integer>(); chosen.add(0); var vectors = new ArrayList<Fraction[]>(); var integer = directions;
			for (int i = 1; i < centers.size() && vectors.size() < 3; i++) {
				var vector = new Fraction[3]; for (int a = 0; a < 3; a++) vector[a] = centers.get(i)[a].subtract(centers.getFirst()[a]);
				var direction = LiquidExactPolygon.vertex(vector); boolean independent = false;
				if (vectors.isEmpty()) independent = direction.x().signum() != 0 || direction.y().signum() != 0 || direction.z().signum() != 0;
				else {
					var first = integer.getFirst(); var second = integer.size() == 1 ? direction : integer.get(1);
					var normal = List.of(first.y().multiply(second.z()).subtract(first.z().multiply(second.y())), first.z().multiply(second.x()).subtract(first.x().multiply(second.z())), first.x().multiply(second.y()).subtract(first.y().multiply(second.x())), BigInteger.ZERO);
					independent = integer.size() == 1 ? normal.stream().anyMatch(value -> value.signum() != 0) : direction.distance(normal).signum() != 0;
				}
				if (independent) { chosen.add(i); vectors.add(vector); integer.add(direction); }
			}
			anchor = chosen.stream().mapToInt(Integer::intValue).toArray(); int rank = vectors.size();
			var gradient = new Fraction[rank][];
			if (rank == 1) gradient[0] = scale(vectors.getFirst(), Fraction.of(1).divide(dot(vectors.getFirst(), vectors.getFirst())));
			if (rank == 2) {
				var first = vectors.getFirst(); var second = vectors.get(1); var aa = dot(first, first); var ab = dot(first, second); var bb = dot(second, second); var determinant = aa.multiply(bb).subtract(ab.multiply(ab));
				gradient[0] = new Fraction[3]; gradient[1] = new Fraction[3];
				for (int a = 0; a < 3; a++) { gradient[0][a] = bb.multiply(first[a]).subtract(ab.multiply(second[a])).divide(determinant); gradient[1][a] = aa.multiply(second[a]).subtract(ab.multiply(first[a])).divide(determinant); }
			}
			if (rank == 3) {
				var determinant = dot(vectors.getFirst(), cross(vectors.get(1), vectors.get(2)));
				for (int a = 0; a < 3; a++) gradient[a] = scale(cross(vectors.get((a + 1) % 3), vectors.get((a + 2) % 3)), Fraction.of(1).divide(determinant));
			}
			coordinate = new Form[rank]; shape = new Form[rank + 1]; var first = zeroRow(); first[3] = Fraction.of(1);
			for (int a = 0; a < rank; a++) {
				var row = new Fraction[]{gradient[a][0], gradient[a][1], gradient[a][2], dot(gradient[a], centers.getFirst()).negate()};
				coordinate[a] = shape[a + 1] = new Form(row); add(first, row, Fraction.of(-1));
			}
			shape[0] = new Form(first);
		}
		boolean contains(Vertex point) {
			if (directions.size() == 3) return true;
			if (directions.isEmpty()) return point.equals(reference);
			var difference = new BigInteger[3]; for (int a = 0; a < 3; a++) difference[a] = point.coordinate(a).multiply(reference.w()).subtract(reference.coordinate(a).multiply(point.w()));
			var first = directions.getFirst();
			if (directions.size() == 1) return first.y().multiply(difference[2]).equals(first.z().multiply(difference[1]))
					&& first.z().multiply(difference[0]).equals(first.x().multiply(difference[2])) && first.x().multiply(difference[1]).equals(first.y().multiply(difference[0]));
			var second = directions.get(1);
			return first.y().multiply(second.z()).subtract(first.z().multiply(second.y())).multiply(difference[0])
					.add(first.z().multiply(second.x()).subtract(first.x().multiply(second.z())).multiply(difference[1]))
					.add(first.x().multiply(second.y()).subtract(first.y().multiply(second.x())).multiply(difference[2])).signum() == 0;
		}
	}
}
