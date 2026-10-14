package mmm.granular;

import java.math.BigInteger;
import java.util.Objects;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

/** Reusable exact eight-cell hat kernel, including clipped-domain normalization and SVQ quotas. */
public final class GranularKernel {
	private static final SignedDyadic HALF = SignedDyadic.of(.5), ONE = SignedDyadic.of(1, 0);
	private final int[] size, base = new int[3], cells = new int[8];
	private final SignedDyadic h;
	private final boolean binary;
	private final ExactRational rationalH;
	private final SignedDyadic[][] binaryWeight = new SignedDyadic[3][2];
	private final ExactRational[][] rationalWeight = new ExactRational[3][2];
	private final SignedDyadic[] binaryQuota = new SignedDyadic[8], binaryRemainder = new SignedDyadic[8];
	private final ExactRational[] rationalQuota = new ExactRational[8], rationalRemainder = new ExactRational[8];
	private final long[] floors = new long[8];
	private int count;
	private long quantity;

	public GranularKernel(int sizeX, int sizeY, int sizeZ, double cellSize) {
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || !(Double.isFinite(cellSize) && cellSize > 0))
			throw new IllegalArgumentException("Granular kernel dimensions are invalid");
		Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		size = new int[]{sizeX, sizeY, sizeZ}; h = SignedDyadic.of(cellSize);
		binary = h.significand().equals(BigInteger.ONE);
		rationalH = binary ? null : exact(h);
	}

	public void capture(float x, float y, float z, long signedSvq) {
		count = 0;
		capture(SignedDyadic.of(x), SignedDyadic.of(y), SignedDyadic.of(z), signedSvq);
	}

	/** Signed quantities allow the same quotas to accumulate source and unrounded endpoint deltas. */
	public void capture(SignedDyadic x, SignedDyadic y, SignedDyadic z, long signedSvq) {
		count = 0;
		if (x == null || y == null || z == null || signedSvq == 0)
			throw new IllegalArgumentException("Granular kernel position or volume is invalid");
		quantity = signedSvq;
		var position = new SignedDyadic[]{x, y, z};
		// A clipped axis has one positive surviving factor; normalization cancels that factor exactly.
		for (int axis = 0; axis < 3; axis++) {
			if (binary) {
				var grid = new SignedDyadic(position[axis].significand(), position[axis].exponent() - h.exponent()).subtract(HALF);
				base[axis] = floor(grid).intValueExact();
				var fraction = grid.subtract(SignedDyadic.of(base[axis], 0));
				requireSupport(axis, fraction.signum());
				binaryWeight[axis][0] = base[axis] < 0 ? SignedDyadic.ZERO
						: base[axis] + 1 >= size[axis] ? ONE : ONE.subtract(fraction);
				binaryWeight[axis][1] = base[axis] < 0 ? ONE : base[axis] + 1 >= size[axis] ? SignedDyadic.ZERO : fraction;
			} else {
				var p = exact(position[axis]); var length = rationalH;
				var grid = new ExactRational(p.numerator().multiply(length.denominator()), p.denominator().multiply(length.numerator()))
						.subtract(ExactRational.ONE.divide(2));
				base[axis] = floor(grid).intValueExact();
				var fraction = grid.subtract(ExactRational.of(base[axis]));
				requireSupport(axis, fraction.signum());
				rationalWeight[axis][0] = base[axis] < 0 ? ExactRational.ZERO
						: base[axis] + 1 >= size[axis] ? ExactRational.ONE : ExactRational.ONE.subtract(fraction);
				rationalWeight[axis][1] = base[axis] < 0 ? ExactRational.ONE : base[axis] + 1 >= size[axis] ? ExactRational.ZERO : fraction;
			}
		}
		SignedDyadic binarySum = SignedDyadic.ZERO, quantity = SignedDyadic.of(signedSvq, 0);
		ExactRational rationalSum = ExactRational.ZERO;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int cx = base[0] + ox, cy = base[1] + oy, cz = base[2] + oz;
			if (cx < 0 || cy < 0 || cz < 0 || cx >= size[0] || cy >= size[1] || cz >= size[2]) continue;
			cells[count] = (cz * size[1] + cy) * size[0] + cx;
			binaryRemainder[count] = null; rationalRemainder[count] = null;
			if (binary) {
				binaryQuota[count] = binaryWeight[0][ox].multiply(binaryWeight[1][oy]).multiply(binaryWeight[2][oz]).multiply(quantity);
				binarySum = binarySum.add(binaryQuota[count]);
			} else {
				rationalQuota[count] = rationalWeight[0][ox].multiply(rationalWeight[1][oy]).multiply(rationalWeight[2][oz]).multiply(signedSvq);
				rationalSum = rationalSum.add(rationalQuota[count]);
			}
			count++;
		}
		if (binary ? !binarySum.equals(quantity) : !rationalSum.equals(ExactRational.of(signedSvq)))
			throw new IllegalStateException("Granular kernel does not re-sum to exact volume");
	}

	private void requireSupport(int axis, int fractionSign) {
		if (base[axis] < -1 || base[axis] >= size[axis] || base[axis] == -1 && fractionSign == 0)
			throw new IllegalStateException("Granular parcel has no in-domain occupancy support");
	}

	public int size() { return count; }
	public int cellCount() { return size[0] * size[1] * size[2]; }
	public boolean sameGeometry(GranularKernel other) {
		return other != null && java.util.Arrays.equals(size, other.size) && h.equals(other.h);
	}
	public int cell(int index) { return cells[Objects.checkIndex(index, count)]; }
	public SignedDyadic dyadicQuota(int index) {
		Objects.checkIndex(index, count);
		if (!binary) throw new IllegalStateException("Granular kernel quota is not dyadic at this cell size");
		return binaryQuota[index];
	}
	public long floor(int index) {
		Objects.checkIndex(index, count);
		if (binary && binaryRemainder[index] == null) {
			floors[index] = floor(binaryQuota[index]).longValueExact();
			binaryRemainder[index] = binaryQuota[index].subtract(SignedDyadic.of(floors[index], 0));
		} else if (!binary && rationalRemainder[index] == null) {
			floors[index] = floor(rationalQuota[index]).longValueExact();
			rationalRemainder[index] = rationalQuota[index].subtract(ExactRational.of(floors[index]));
		}
		return floors[index];
	}
	public long ceil(int index) {
		long low = floor(index);
		return (binary ? binaryRemainder[index].signum() : rationalRemainder[index].signum()) == 0 ? low : Math.addExact(low, 1);
	}
	public int compareRemainder(int first, int second) {
		floor(first); floor(second);
		return binary ? binaryRemainder[first].compareTo(binaryRemainder[second])
				: rationalRemainder[first].compareTo(rationalRemainder[second]);
	}

	/** One bit per captured in-domain iteration slot selects ceil; the order is bound to dimensions and position. */
	public int canonicalMask() {
		if (quantity <= 0 || count == 0) throw new IllegalStateException("Integer occupancy requires positive kernel volume");
		long assigned = 0; int mask = 0;
		for (int slot = 0; slot < count; slot++) assigned = Math.addExact(assigned, floor(slot));
		for (; assigned < quantity; assigned++) {
			int best = -1;
			for (int slot = 0; slot < count; slot++) {
				if ((mask & 1 << slot) != 0 || ceil(slot) == floor(slot)) continue;
				int order = best < 0 ? 1 : compareRemainder(slot, best);
				if (order > 0 || order == 0 && cell(slot) < cell(best)) best = slot;
			}
			if (best < 0) throw new IllegalStateException("Granular kernel lacks its required integer remainder");
			mask |= 1 << best;
		}
		return mask;
	}
	public void requireMask(int mask) {
		if (quantity <= 0 || count == 0 || mask < 0 || mask >>> count != 0)
			throw new IllegalArgumentException("Granular occupancy selection lies outside its support");
		long total = 0;
		for (int slot = 0; slot < count; slot++) {
			long low = floor(slot); boolean upper = (mask & 1 << slot) != 0;
			if (upper && ceil(slot) == low) throw new IllegalArgumentException("Granular occupancy selected ceil of an integer quota");
			total = Math.addExact(total, low + (upper ? 1 : 0));
		}
		if (total != quantity) throw new IllegalArgumentException("Granular occupancy selection changed its exact volume");
	}
	private static BigInteger floor(SignedDyadic value) {
		return value.exponent() >= 0 ? value.significand().shiftLeft(value.exponent()) : value.significand().shiftRight(-value.exponent());
	}
	private static BigInteger floor(ExactRational value) {
		var division = value.numerator().divideAndRemainder(value.denominator());
		return division[1].signum() < 0 ? division[0].subtract(BigInteger.ONE) : division[0];
	}
	private static ExactRational exact(SignedDyadic value) {
		return value.exponent() >= 0 ? new ExactRational(value.significand().shiftLeft(value.exponent()), BigInteger.ONE)
				: new ExactRational(value.significand(), BigInteger.ONE.shiftLeft(-value.exponent()));
	}
}
