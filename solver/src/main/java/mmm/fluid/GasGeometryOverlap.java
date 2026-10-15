package mmm.fluid;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Microbox intersections: analytic boxes and single planes, numerical convex clipping otherwise. */
final class GasGeometryOverlap {
	/** Dry support nx*x+ny*y+nz*z >= threshold in cell-local coordinates. */
	record HalfSpace(SignedDyadic nx, SignedDyadic ny, SignedDyadic nz, SignedDyadic threshold) {
		HalfSpace { Objects.requireNonNull(nx); Objects.requireNonNull(ny); Objects.requireNonNull(nz); Objects.requireNonNull(threshold); }
	}
	private static final ExactRational HALF = ExactRational.ONE.divide(2);
	private static final int[][] POWERS = {
			{0,0,0}, {1,0,0}, {0,1,0}, {0,0,1}, {2,0,0}, {1,1,0}, {1,0,1}, {0,2,0}, {0,1,1}, {0,0,2},
			{3,0,0}, {2,1,0}, {2,0,1}, {1,2,0}, {1,1,1}, {1,0,2}, {0,3,0}, {0,2,1}, {0,1,2}, {0,0,3}
	};
	private static final int[][] AFFINE_PRODUCTS = {
			{1,2,3}, {4,5,6}, {5,7,8}, {6,8,9}, {10,11,12},
			{11,13,14}, {12,14,15}, {13,16,17}, {14,17,18}, {15,18,19}
	};
	private record Workspace(ConvexPolyhedron polyhedron, ConvexPolyhedron.Moments moments) { }
	private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(
			() -> new Workspace(new ConvexPolyhedron(), new ConvexPolyhedron.Moments()));
	private GasGeometryOverlap() { }

	/** Cell-normalized volume. The caller supplies microboxes open in both geometries. */
	static ExactRational normalizedVolume(int micro, List<HalfSpace> planes) { return integrate(micro, planes, 0)[0]; }

	/** V, X, Y, Z about the whole-cell centre. */
	static ExactRational[] normalizedMoments(int micro, List<HalfSpace> planes) { return integrate(micro, planes, 1); }

	/** V, X, Y, Z, XX, XY, XZ, YY, YZ, ZZ about the whole-cell centre. */
	static ExactRational[] normalizedSecondMoments(int micro, List<HalfSpace> planes) { return integrate(micro, planes, 2); }

	static ExactRational unionVolume(int micro, List<List<HalfSpace>> supports) {
		var result = ExactRational.ZERO;
		for (var convex : supports) result = result.add(normalizedVolume(micro, convex));
		return result;
	}
	static ExactRational unionOverlapVolume(int micro, List<List<HalfSpace>> before, List<List<HalfSpace>> after) {
		var result = ExactRational.ZERO;
		for (var first : before) for (var second : after) {
			var intersection = new ArrayList<HalfSpace>(first.size() + second.size());
			intersection.addAll(first); intersection.addAll(second);
			result = result.add(normalizedVolume(micro, intersection));
		}
		return result;
	}

	/** Exact positivity only; null denotes the complete microbox. */
	static boolean hasPositiveOverlap(int micro, HalfSpace before, HalfSpace after) {
		if (micro < 0 || micro >= 8) throw new IllegalArgumentException("Invalid gas overlap microbox");
		boolean unrestricted = before == null || zeroNormal(before);
		if (before != null && unrestricted && before.threshold().signum() > 0) return false;
		SignedDyadic[] first = new SignedDyadic[8];
		boolean positiveBefore = unrestricted;
		for (int corner = 0; corner < 8; corner++) {
			first[corner] = unrestricted ? SignedDyadic.of(1) : cornerDistance(micro, corner, before);
			positiveBefore |= first[corner].signum() > 0;
		}
		if (!positiveBefore) return false;
		if (after == null || zeroNormal(after)) return after == null || after.threshold().signum() <= 0;
		SignedDyadic[] second = new SignedDyadic[8];
		for (int corner = 0; corner < 8; corner++) {
			second[corner] = cornerDistance(micro, corner, after);
			if (first[corner].signum() >= 0 && second[corner].signum() > 0) return true;
		}
		// A linear function on the positive-volume clipped box reaches its maximum at a
		// box corner or a first-plane/box-edge intersection. A strict maximum permits an
		// interior perturbation into both dry supports; a zero maximum is only tangency.
		for (int corner = 0; corner < 8; corner++) for (int axis = 0; axis < 3; axis++) {
			if ((corner & 1 << axis) != 0) continue;
			int peer = corner | 1 << axis;
			if (first[corner].signum() * first[peer].signum() >= 0) continue;
			SignedDyadic numerator = second[corner].multiply(first[peer])
					.subtract(second[peer].multiply(first[corner]));
			if (numerator.signum() * first[peer].subtract(first[corner]).signum() > 0) return true;
		}
		return false;
	}

	private static boolean zeroNormal(HalfSpace plane) {
		return plane.nx().signum() == 0 && plane.ny().signum() == 0 && plane.nz().signum() == 0;
	}

	/** Positive linear maximum on a positive-volume half-space/box intersection. */
	static ExactRational maximumLinearValue(int micro, HalfSpace support, HalfSpace function) {
		if (micro < 0 || micro >= 8 || support == null || function == null)
			throw new IllegalArgumentException("Invalid linear microbox maximum");
		boolean unrestricted = zeroNormal(support);
		if (unrestricted && support.threshold().signum() > 0) return ExactRational.ZERO;
		SignedDyadic[] distance = new SignedDyadic[8], values = new SignedDyadic[8];
		boolean positiveVolume = unrestricted;
		ExactRational maximum = ExactRational.ZERO;
		for (int corner = 0; corner < 8; corner++) {
			distance[corner] = unrestricted ? SignedDyadic.of(1) : cornerDistance(micro, corner, support);
			values[corner] = cornerDistance(micro, corner, function);
			positiveVolume |= distance[corner].signum() > 0;
			if (distance[corner].signum() >= 0 && values[corner].signum() > 0) {
				var value = exact(values[corner]); if (value.compareTo(maximum) > 0) maximum = value;
			}
		}
		if (!positiveVolume) return ExactRational.ZERO;
		if (!unrestricted) for (int corner = 0; corner < 8; corner++) for (int axis = 0; axis < 3; axis++) {
			if ((corner & 1 << axis) != 0) continue;
			int peer = corner | 1 << axis;
			if (distance[corner].signum() * distance[peer].signum() >= 0) continue;
			var numerator = values[corner].multiply(distance[peer]).subtract(values[peer].multiply(distance[corner]));
			var value = PlicComponentGeometry.ratio(numerator, distance[peer].subtract(distance[corner]));
			if (value.compareTo(maximum) > 0) maximum = value;
		}
		return maximum;
	}
	private static SignedDyadic cornerDistance(int micro, int corner, HalfSpace plane) {
		return plane.nx().multiply(SignedDyadic.of((micro & 1) + (corner & 1), -1))
				.add(plane.ny().multiply(SignedDyadic.of((micro >>> 1 & 1) + (corner >>> 1 & 1), -1)))
				.add(plane.nz().multiply(SignedDyadic.of((micro >>> 2 & 1) + (corner >>> 2 & 1), -1)))
				.subtract(plane.threshold());
	}

	/** Integrates q, x*q and x*x*q; q=c0+c1*x+c2*y+c3*z about the cell centre. The caller supplies q>=0 support. */
	static ExactRational[] normalizedAffineSecondMoments(int micro, List<HalfSpace> planes, ExactRational[] coefficients) {
		if (coefficients == null || coefficients.length != 4 || java.util.Arrays.stream(coefficients).anyMatch(Objects::isNull))
			throw new IllegalArgumentException("Affine overlap weight needs four exact coefficients");
		var q = coefficients.clone();
		int order = q[1].isZero() && q[2].isZero() && q[3].isZero() ? 2 : 3;
		var moments = integrate(micro, planes, order); var result = new ExactRational[10];
		try {
			for (int index = 0; index < result.length; index++) {
				result[index] = moments[index].multiply(q[0]);
				for (int axis = 0; axis < 3; axis++) if (!q[axis + 1].isZero())
					result[index] = result[index].add(moments[AFFINE_PRODUCTS[index][axis]].multiply(q[axis + 1]));
			}
			return result;
		} catch (IllegalArgumentException magnitude) {
			throw new GasTopologyBlockedException("Exact affine overlap exceeds the rational representation budget: " + magnitude.getMessage());
		}
	}

	private static ExactRational[] integrate(int micro, List<HalfSpace> planes, int order) {
		if (micro < 0 || micro >= 8 || planes == null || planes.size() > 12)
			throw new IllegalArgumentException("Invalid gas overlap microbox or half-spaces");
		var active = new ArrayList<HalfSpace>(planes.size());
		boolean axial = true;
		for (var plane : planes) {
			Objects.requireNonNull(plane, "Null overlap half-space");
			var low = cornerDistance(micro, 0, plane);
			var high = low;
			int axes = 0;
			for (int a = 0; a < 3; a++) {
				var coefficient = normal(plane, a);
				var spread = coefficient.multiply(SignedDyadic.of(1, -1));
				if (coefficient.signum() < 0) low = low.add(spread); else high = high.add(spread);
				if (coefficient.signum() != 0) axes++;
			}
			if (high.signum() <= 0 && low.signum() < 0) return zero(order);
			if (low.signum() >= 0) continue;
			boolean duplicate = false;
			for (var previous : active) if (samePlane(previous, plane)) { duplicate = true; break; }
			if (!duplicate) { active.add(plane); axial &= axes == 1; }
		}
		if (active.size() == 1) return MicroBoxPlaneVolume.dryMoments(micro, active.getFirst(), order);
		if (axial) return boxMoments(micro, active, order);
		var work = WORKSPACE.get();
		work.polyhedron.resetBox(micro, active);
		work.polyhedron.integrate(order, work.moments);
		return work.moments.globalMoments(order);
	}

	/** Equal half-spaces may carry different positive normal scales. */
	private static boolean samePlane(HalfSpace first, HalfSpace second) {
		int pivot = 0;
		while (pivot < 3 && normal(first, pivot).signum() == 0) pivot++;
		if (pivot == 3) return zeroNormal(second) && first.threshold.equals(second.threshold);
		var a = normal(first, pivot); var b = normal(second, pivot);
		if (a.signum() != b.signum()) return false;
		for (int axis = 0; axis < 3; axis++)
			if (!normal(first, axis).multiply(b).equals(normal(second, axis).multiply(a))) return false;
		return first.threshold.multiply(b).equals(second.threshold.multiply(a));
	}
	private static SignedDyadic normal(HalfSpace plane, int axis) {
		return axis == 0 ? plane.nx : axis == 1 ? plane.ny : plane.nz;
	}
	private static ExactRational[] zero(int order) {
		var result = new ExactRational[order == 3 ? 20 : order == 2 ? 10 : 4];
		Arrays.fill(result, ExactRational.ZERO);
		return result;
	}

	/** Axis cuts retain exact bounds, including slabs thinner than a global double ULP. */
	private static ExactRational[] boxMoments(int micro, List<HalfSpace> planes, int order) {
		var low = new ExactRational[3]; var high = new ExactRational[3];
		for (int a = 0; a < 3; a++) { low[a] = ExactRational.of(micro >> a & 1).divide(2); high[a] = low[a].add(HALF); }
		for (var plane : planes) {
			int a = 0; while (normal(plane, a).signum() == 0) a++;
			var bound = PlicComponentGeometry.ratio(plane.threshold, normal(plane, a));
			if (normal(plane, a).signum() > 0) {
				if (bound.compareTo(low[a]) > 0) low[a] = bound;
			} else if (bound.compareTo(high[a]) < 0) high[a] = bound;
		}
		var result = zero(order);
		for (int a = 0; a < 3; a++) if (high[a].compareTo(low[a]) <= 0) return result;
		var axis = new ExactRational[3][order + 1];
		for (int a = 0; a < 3; a++) {
			var lo = low[a].subtract(HALF); var hi = high[a].subtract(HALF);
			var loPower = ExactRational.ONE; var hiPower = ExactRational.ONE;
			for (int degree = 0; degree <= order; degree++) {
				loPower = loPower.multiply(lo); hiPower = hiPower.multiply(hi);
				axis[a][degree] = hiPower.subtract(loPower).divide(degree + 1);
			}
		}
		for (int i = 0; i < (order == 0 ? 1 : result.length); i++)
			result[i] = axis[0][POWERS[i][0]].multiply(axis[1][POWERS[i][1]]).multiply(axis[2][POWERS[i][2]]);
		return result;
	}
	private static ExactRational exact(SignedDyadic value) {
		long exponent = value.exponent();
		long numeratorBits = value.significand().abs().bitLength() + Math.max(exponent, 0);
		if (numeratorBits > ChemicalLimits.MAX_RATIONAL_BITS || Math.max(-exponent, 0) + 1 > ChemicalLimits.MAX_RATIONAL_BITS) {
			throw new GasTopologyBlockedException("Gas overlap half-space exceeds the rational representation budget");
		}
		return exponent >= 0 ? new ExactRational(value.significand().shiftLeft((int) exponent), BigInteger.ONE)
				: new ExactRational(value.significand(), BigInteger.ONE.shiftLeft((int) -exponent));
	}
}
