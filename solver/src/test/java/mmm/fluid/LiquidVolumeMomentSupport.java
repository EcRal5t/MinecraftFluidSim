package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.List;

/** Directional necessary condition for liquid coverage bounded by fixed open pore capacity. */
final class LiquidVolumeMomentSupport {
	/** A positive excess disproves coverage; a non-positive excess leaves other directions unresolved. */
	record Certificate(ExactRational observedProjectedMoment, ExactRational upperBound, ExactRational excess) { }
	private LiquidVolumeMomentSupport() { }

	static Certificate evaluate(int support, ExactRational poreScale, ExactRational volume,
	                            ExactRational[] centeredFirst, GasGeometryOverlap.HalfSpace upperSlice) {
		if (support < 0 || support > 255 || poreScale == null || volume == null || upperSlice == null
				|| centeredFirst == null || centeredFirst.length != 3
				|| poreScale.signum() < 0 || poreScale.compareTo(ExactRational.ONE) > 0 || volume.signum() < 0)
			throw new IllegalArgumentException("Invalid liquid volume moment support");
		if (volume.compareTo(poreScale.multiply(Integer.bitCount(support)).divide(8)) > 0)
			throw new IllegalArgumentException("Liquid volume exceeds fixed support pore capacity");
		for (var first : centeredFirst) if (first == null || volume.isZero() && !first.isZero())
			throw new IllegalArgumentException("Liquid first moments require their matching volume");
		var normal = new ExactRational[]{exact(upperSlice.nx()), exact(upperSlice.ny()), exact(upperSlice.nz())};
		var threshold = exact(upperSlice.threshold()).subtract(normal[0].add(normal[1]).add(normal[2]).divide(2));
		var observed = ExactRational.ZERO; var tail = ExactRational.ZERO;
		for (int axis = 0; axis < 3; axis++) observed = observed.add(normal[axis].multiply(centeredFirst[axis]));
		for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
			var moments = GasGeometryOverlap.normalizedMoments(micro, List.of(upperSlice));
			var contribution = threshold.multiply(moments[0]).multiply(-1);
			for (int axis = 0; axis < 3; axis++) contribution = contribution.add(normal[axis].multiply(moments[axis + 1]));
			tail = tail.add(contribution);
		}
		// For 0 <= coverage <= poreScale, (n.x-t)*coverage <= poreScale*max(n.x-t,0).
		var upper = threshold.multiply(volume).add(poreScale.multiply(tail));
		return new Certificate(observed, upper, observed.subtract(upper));
	}

	/** Uses only one captured component's exact inventory, fixed pore capacity and PLIC direction. */
	static Certificate evaluate(GasRuntimeGeometry frame, int cell, int support, int sizeX, int sizeY) {
		if (frame == null || cell < 0 || cell >= frame.liquidStock().size() || sizeX <= 0 || sizeY <= 0
				|| sizeX != frame.geometry().sizeX() || sizeY != frame.geometry().sizeY())
			throw new IllegalArgumentException("Invalid captured liquid moment support coordinates");
		var edit = frame.liquidStock().get(cell);
		int selected = -1;
		for (int i = 0; i < edit.components().size(); i++)
			if (edit.components().get(i).supportMask() == support) { selected = i; break; }
		if (selected < 0) {
			if (support == 0 && edit.collisionMask() == 255)
				return evaluate(0, ExactRational.ZERO, ExactRational.ZERO,
						new ExactRational[]{ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO},
						new GasGeometryOverlap.HalfSpace(SignedDyadic.of(1), SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO));
			throw new IllegalArgumentException("Liquid moment support must match a captured open component");
		}
		var stock = edit.components().get(selected);
		var capacities = CellRemapMicroTopology.capacities(edit.collisionMask(), frame.poreVolume(cell));
		var pore = exact(capacities[selected]).multiply(8).divide(Integer.bitCount(support));
		var volume = exact(stock.volume()); var first = new ExactRational[3];
		int[] coordinate = {cell % sizeX, cell / sizeX % sizeY, cell / Math.multiplyExact(sizeX, sizeY)};
		for (int axis = 0; axis < 3; axis++) first[axis] = PlicComponentGeometry.ratio(stock.firstMoment(axis), SignedDyadic.of(frame.cellSize()))
				.subtract(ExactRational.of(2L * coordinate[axis] + 1).divide(2).multiply(volume));
		int micro = Integer.numberOfTrailingZeros(support);
		SignedDyadic[] normal = new SignedDyadic[3];
		for (int axis = 0; axis < 3; axis++) normal[axis] = SignedDyadic.of(frame.planeNormal(cell, micro, axis)).negate();
		SignedDyadic threshold = SignedDyadic.of(frame.planeOffset(cell, micro)).negate();
		if (normal[0].signum() == 0 && normal[1].signum() == 0 && normal[2].signum() == 0) {
			var uniform = new ExactRational[]{ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO};
			for (int bit = 0; bit < 8; bit++) if ((support & 1 << bit) != 0)
				for (int axis = 0; axis < 3; axis++) uniform[axis] = uniform[axis].add(ExactRational.of((bit >>> axis & 1) == 0 ? -1 : 1));
			int selectedAxis = 0; var largest = ExactRational.ZERO; int sign = 1;
			for (int axis = 0; axis < 3; axis++) {
				var delta = first[axis].subtract(uniform[axis].multiply(volume).divide(4L * Integer.bitCount(support)));
				var magnitude = delta.signum() < 0 ? delta.multiply(-1) : delta;
				if (magnitude.compareTo(largest) > 0) { largest = magnitude; selectedAxis = axis; sign = delta.signum(); }
			}
			normal[selectedAxis] = SignedDyadic.of(sign);
			threshold = SignedDyadic.of(Math.min(0, sign));
		}
		return evaluate(support, pore, volume, first, new GasGeometryOverlap.HalfSpace(normal[0], normal[1], normal[2], threshold));
	}

	private static ExactRational exact(SignedDyadic value) { return PlicComponentGeometry.ratio(value, SignedDyadic.of(1)); }
}
