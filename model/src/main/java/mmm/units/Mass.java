package mmm.units;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/** SI mass inventory: one signed integer count is 10^-10 kg (100 ng). */
public final class Mass {
	public static final long UNITS_PER_KILOGRAM = 10_000_000_000L;
	public static final String ENCODING = "mmm:mass_1e-10_kg";
	private Mass() { }

	/** Quantizes a newly created physical quantity once; transfers use integer allocation. */
	public static long fromKilograms(double kilograms) {
		if (!Double.isFinite(kilograms)) throw new IllegalArgumentException("Mass in kg must be finite");
		return BigDecimal.valueOf(kilograms).movePointRight(10).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
	}

	public static long exactUnits(BigInteger numerator, BigInteger denominator) {
		BigInteger[] division = numerator.multiply(BigInteger.valueOf(UNITS_PER_KILOGRAM))
				.divideAndRemainder(denominator);
		if (division[1].signum() != 0) throw new IllegalArgumentException("Mass is below the 10^-10 kg encoding");
		return division[0].longValueExact();
	}

	public static double kilograms(long units) { return units / (double) UNITS_PER_KILOGRAM; }

	/** The selected share rounds toward zero; the caller retains total minus this share. */
	public static long portion(long total, long numerator, long denominator) {
		if (numerator < 0 || denominator <= 0 || numerator > denominator) throw new IllegalArgumentException("Invalid mass fraction");
		if(total>=0&&denominator<=0x1_0000_0000L)
			return (total/denominator)*numerator+Long.divideUnsigned((total%denominator)*numerator,denominator);
		if(total>=0) {
			// A floating quotient only selects a nearby integer. Two-word products
			// then recover the exact signed remainder and correct the quotient.
			long quotient=Math.min(total,(long)((double)total*numerator/denominator));
			long first=total*numerator,second=quotient*denominator;
			long remainder=first-second;
			long high=Math.multiplyHigh(total,numerator)-Math.multiplyHigh(quotient,denominator)
					-(Long.compareUnsigned(first,second)<0?1:0);
			if(high==(remainder>>63))return quotient+Math.floorDiv(remainder,denominator);
		}
		return BigInteger.valueOf(total).multiply(BigInteger.valueOf(numerator))
				.divide(BigInteger.valueOf(denominator)).longValueExact();
	}

	public static long portion(long total, double numerator, double denominator) {
		if (!(Double.isFinite(numerator) && Double.isFinite(denominator) && numerator >= 0
				&& denominator > 0 && numerator <= denominator)) throw new IllegalArgumentException("Invalid mass weight");
		return portion(total, SignedDyadic.of(numerator), SignedDyadic.of(denominator));
	}

	public static long portion(long total, SignedDyadic top, SignedDyadic bottom) {
		if (top.signum() < 0 || bottom.signum() <= 0 || top.compareTo(bottom) > 0) throw new IllegalArgumentException("Invalid mass weight");
		if(top.fitsLongUnits(-32)&&bottom.fitsLongUnits(-32))return portion(total,top.longUnitsExact(-32),bottom.longUnitsExact(-32));
		int common=Math.min(top.exponent(),bottom.exponent());
		if(top.fitsLongUnits(common)&&bottom.fitsLongUnits(common))
			return portion(total,top.longUnitsExact(common),bottom.longUnitsExact(common));
		BigInteger n = BigInteger.valueOf(total).multiply(top.significand()), d = bottom.significand();
		int shift = top.exponent() - bottom.exponent();
		if (shift >= 0) n = n.shiftLeft(shift); else d = d.shiftLeft(-shift);
		return n.divide(d).longValueExact();
	}

	/** Creates a new liquid stock from an SI density and physical cell size. */
	public static long[] createLiquid(float cellSize, double densityKgPerCubicMeter, float[] positionVolume) {
		SolverUnitScale scale = SolverUnitScale.forCellSize(cellSize);
		long[] result = new long[positionVolume.length / 4];
		for (int i = 0; i < result.length; i++) {
			result[i] = fromKilograms(scale.cubicMeters(positionVolume[i * 4 + 3]) * densityKgPerCubicMeter);
			if (result[i] <= 0) throw new IllegalArgumentException("Liquid source is below mass resolution");
		}
		return result;
	}

	/** Redistributes one existing pure-fluid stock onto a new numerical sampling layout. */
	public static long[] distributeLiquid(long totalUnits, float[] positionVolume) {
		int count = positionVolume.length / 4;
		if (count == 0) {
			if (totalUnits != 0) throw new IllegalArgumentException("Non-empty mass has no liquid samples");
			return new long[0];
		}
		float[] weights = new float[count]; int largest = 0;
		for (int i = 0; i < count; i++) {
			weights[i] = positionVolume[i * 4 + 3];
			if (weights[i] > weights[largest]) largest = i;
		}
		SignedDyadic volume = SignedDyadic.sum(weights, count);
		long[] result = new long[count]; long assigned = 0;
		for (int i = 0; i < count; i++) if (i != largest) {
			result[i] = portion(totalUnits, SignedDyadic.of(weights[i]), volume);
			assigned = Math.addExact(assigned, result[i]);
		}
		result[largest] = Math.subtractExact(totalUnits, assigned);
		for (long value : result) if (value <= 0) throw new IllegalArgumentException("Liquid sample is below mass resolution");
		return result;
	}

	public static long sum(long[] values) { return sum(values, values.length); }
	public static long sum(long[] values, int length) {
		long result = 0;
		for (int i = 0; i < length; i++) result = Math.addExact(result, values[i]);
		return result;
	}
}
