package cc.ecisr.mmm.fluid;

import java.util.List;
import java.math.BigInteger;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Exact constant RT coefficients have the submitted flux on all four tetrahedral faces. */
final class LiquidCharacteristicVelocity {
	private LiquidCharacteristicVelocity() { }
	static Fraction[] reconstruct(LiquidConvexRegion geometry, List<SignedDyadic> rates) {
		if (geometry.faces().size() != 4 || rates.size() != 4) throw new IllegalArgumentException("Invalid characteristic tetrahedron");
		var sum = SignedDyadic.ZERO; for (var rate : rates) sum = sum.add(rate);
		if (sum.signum() != 0) throw new IllegalStateException("Liquid characteristic enters compressible velocity support");
		var matrix = new Fraction[3][4];
		for (int i = 0; i < 3; i++) {
			var area = geometry.faces().get(i).areaVector(); System.arraycopy(area, 0, matrix[i], 0, 3); matrix[i][3] = exact(rates.get(i));
		}
		for (int column = 0; column < 3; column++) {
			int pivot = column; while (pivot < 3 && matrix[pivot][column].numerator().signum() == 0) pivot++;
			if (pivot == 3) throw new IllegalStateException("Singular characteristic tetrahedron");
			var row = matrix[column]; matrix[column] = matrix[pivot]; matrix[pivot] = row; var divisor = matrix[column][column];
			for (int j = column; j < 4; j++) matrix[column][j] = matrix[column][j].divide(divisor);
			for (int i = 0; i < 3; i++) if (i != column) {
				var factor = matrix[i][column]; for (int j = column; j < 4; j++) matrix[i][j] = matrix[i][j].subtract(factor.multiply(matrix[column][j]));
			}
		}
		var velocity = new Fraction[] {matrix[0][3], matrix[1][3], matrix[2][3]}; var area = geometry.faces().get(3).areaVector(); var flux = Fraction.of(0);
		for (int a = 0; a < 3; a++) flux = flux.add(area[a].multiply(velocity[a]));
		if (!flux.equals(exact(rates.get(3)))) throw new IllegalStateException("Characteristic velocity lost exact fourth-face continuity");
		return velocity;
	}
	private static Fraction exact(SignedDyadic value) {
		return value.exponent() >= 0 ? new Fraction(value.significand().shiftLeft(value.exponent()), BigInteger.ONE)
				: new Fraction(value.significand(), BigInteger.ONE.shiftLeft(-value.exponent()));
	}
}
