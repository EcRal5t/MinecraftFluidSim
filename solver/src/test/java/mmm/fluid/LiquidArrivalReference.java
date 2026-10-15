package mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

/** Independent source-conditioned arrival checks for the frozen normal-trace reference. */
final class LiquidArrivalReference {
	private LiquidArrivalReference() { }

	/** Integrates the positive trace over each wet source, retaining the complete column's volume allocation. */
	static ExactRational[] lowOrderPhaseSecondMoments(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                                                double[] q, double dtOverH, int donorSupport, int receiverSupport,
	                                                int selectedSourceSupport) {
		if (selectedSourceSupport < 0 || selectedSourceSupport > 255 || (selectedSourceSupport & ~donorSupport) != 0)
			throw new IllegalArgumentException("Invalid low-order source moment support");
		var open = new ExactRational[8];
		LiquidAffineSweepGeometry.lowOrderPhaseVolumes(domain, donor, receiver, axis, positive, q, dtOverH,
				donorSupport, receiverSupport, open, new ExactRational[8], new ExactRational[8]);
		int sign = positive ? 1 : -1;
		SignedDyadic[] normal = {SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO};
		normal[(axis + 1) % 3] = SignedDyadic.of(q[1]).multiply(SignedDyadic.of(sign));
		normal[(axis + 2) % 3] = SignedDyadic.of(q[2]).multiply(SignedDyadic.of(sign));
		var positiveTrace = new GasGeometryOverlap.HalfSpace(normal[0], normal[1], normal[2], SignedDyadic.of(-sign * q[0]));
		ExactRational[] affine = {rational(q[0]).add(rational(q[1]).add(rational(q[2])).divide(2)).multiply(sign),
				ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO};
		affine[(axis + 1) % 3 + 1] = rational(q[1]).multiply(sign);
		affine[(axis + 2) % 3 + 1] = rational(q[2]).multiply(sign);
		var result = new ExactRational[10]; Arrays.fill(result, ExactRational.ZERO);
		for (int micro = 0; micro < 8; micro++) if ((selectedSourceSupport & 1 << micro) != 0 && !open[micro].isZero()) {
			var full = GasGeometryOverlap.normalizedAffineSecondMoments(micro, List.of(positiveTrace), affine);
			var scale = open[micro].multiply(new ExactRational(full[0].denominator(), full[0].numerator()));
			var supports = domain.pressurePhaseGeometry.liquidShape(donor) == null
					? List.of(List.of(LiquidAffineSweepGeometry.wetSourcePlane(domain, donor, micro)))
					: LiquidShapeGeometry.wetSupports(domain.pressurePhaseGeometry, donor, micro);
			for (var wet : supports) {
				var constraints = new ArrayList<GasGeometryOverlap.HalfSpace>(wet.size() + 1);
				constraints.add(positiveTrace); constraints.addAll(wet);
				var part = GasGeometryOverlap.normalizedAffineSecondMoments(micro, constraints, affine);
				for (int i = 0; i < result.length; i++) result[i] = result[i].add(part[i].multiply(scale));
			}
		}
		return result;
	}
	private static ExactRational rational(double value) {
		return PlicComponentGeometry.ratio(SignedDyadic.of(value), SignedDyadic.of(1));
	}
	static ExactRational[] arrivalSecondMoments(FluidDomain domain, CellAffineVelocityField field, int donor, int receiver, int axis, boolean positive,
	                                                     double dtOverH, int donorSupport, int receiverSupport, boolean lowOrder) {
		return arrivalSecondMoments(domain, field, donor, receiver, axis, positive, dtOverH, donorSupport, receiverSupport, donorSupport, lowOrder);
	}
	static ExactRational[] arrivalSecondMoments(FluidDomain domain, CellAffineVelocityField field, int donor, int receiver, int axis, boolean positive,
	                                                     double dtOverH, int donorSupport, int receiverSupport, int selectedSourceSupport,
	                                                     boolean lowOrder) {
		if (selectedSourceSupport < 0 || selectedSourceSupport > 255 || (selectedSourceSupport & ~donorSupport) != 0)
			throw new IllegalArgumentException("Arrival source selection must belong to donor support");
		var result = new ExactRational[10]; Arrays.fill(result, ExactRational.ZERO);
		var components = field.hasCell(donor) ? field.faceTraces(donor, axis, positive, domain.sizeX, domain.sizeY) : null;
		if (components == null) return result;
		for (var component : components) {
			int support = donorSupport & component.support(); if (support == 0) continue;
			int selected = support & selectedSourceSupport; if (selected == 0) continue;
			// The complete donor path remains available even when only its inner source microbox is selected.
			for (int micro = 0; micro < 8; micro++) if ((support & 1 << (positive ? micro | 1 << axis : micro & ~(1 << axis))) == 0)
				selected &= ~(1 << micro);
			double[] q = component.q();
			var source = lowOrder ? lowOrderPhaseSecondMoments(domain, donor, receiver, axis, positive,
					q, dtOverH, component.support(), receiverSupport, selected)
					: LiquidAffineSweepGeometry.sweptExactSecondMoments(domain, donor, receiver, axis, positive,
							q[0], q[1], q[2], dtOverH, support, receiverSupport, selected);
			var part = arrivalSecondMoments(source, axis, positive, q, dtOverH, lowOrder);
			for (int i = 0; i < result.length; i++) result[i] = result[i].add(part[i]);
		}
		return result;
	}
	/** Donor-centred arrival moments under the same frozen normal trace. */
	static ExactRational[] arrivalSecondMoments(ExactRational[] source, int axis, boolean positive,
	                                          double[] q, double dtOverH, boolean uniformAge) {
		if (source == null || source.length != 10 || axis < 0 || axis > 2 || q == null || q.length != 3
				|| !(Double.isFinite(dtOverH) && dtOverH >= 0))
			throw new IllegalArgumentException("Invalid liquid arrival moments");
		var result = source.clone();
		ExactRational[] affine = {rational(q[0]).add(rational(q[1]).add(rational(q[2])).divide(2)),
				ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO};
		affine[(axis + 1) % 3 + 1] = rational(q[1]); affine[(axis + 2) % 3 + 1] = rational(q[2]);
		var velocity = affine[0].multiply(source[0]); var squared = affine[0].multiply(affine[0]).multiply(source[0]);
		for (int a = 0; a < 3; a++) {
			velocity = velocity.add(affine[a + 1].multiply(source[a + 1]));
			squared = squared.add(affine[0].multiply(affine[a + 1]).multiply(source[a + 1]).multiply(2));
			for (int b = 0; b < 3; b++) squared = squared.add(affine[a + 1].multiply(affine[b + 1]).multiply(source[secondIndex(a, b)]));
		}
		var time = rational(dtOverH);
		var face = ExactRational.of(positive ? 1 : -1).divide(2);
		result[axis + 1] = uniformAge ? face.multiply(source[0]).add(time.multiply(velocity).divide(2))
				: source[axis + 1].add(time.multiply(velocity));
		for (int a = 0; a < 3; a++) {
			var cross = affine[0].multiply(source[a + 1]);
			for (int b = 0; b < 3; b++) cross = cross.add(affine[b + 1].multiply(source[secondIndex(a, b)]));
			int entry = secondIndex(axis, a);
			result[entry] = a == axis ? uniformAge
					? face.multiply(face).multiply(source[0]).add(face.multiply(time).multiply(velocity))
							.add(time.multiply(time).multiply(squared).divide(3))
					: source[entry].add(time.multiply(cross).multiply(2)).add(time.multiply(time).multiply(squared))
					: uniformAge ? face.multiply(source[a + 1]).add(time.multiply(cross).divide(2))
					: source[entry].add(time.multiply(cross));
		}
		return result;
	}
	private static int secondIndex(int a, int b) {
		int low = Math.min(a, b), high = Math.max(a, b);
		return low == 0 ? 4 + high : low == 1 ? 6 + high : 9;
	}
}
