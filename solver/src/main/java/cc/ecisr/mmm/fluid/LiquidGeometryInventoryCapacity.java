package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;

/** Transfer capacity comes from occupied geometry; binary transfer quantities round available space downward. */
final class LiquidGeometryInventoryCapacity {
	private LiquidGeometryInventoryCapacity() { }
	static SignedDyadic[] available(FluidDomain domain) {
		var occupied = new Fraction[domain.cellCount]; Arrays.fill(occupied, Fraction.ZERO);
		int span = Integer.highestOneBit(Math.max(domain.sizeX, Math.max(domain.sizeY, domain.sizeZ)) - 1) << 1;
		int level = Integer.numberOfTrailingZeros(span); var scale = Fraction.of(span); scale = scale.multiply(scale).multiply(scale);
		for (var cell : LiquidGeometryStateMapper.restore(domain.liquidGeometryState)) accumulate(cell, level, domain, scale, occupied);
		var room = new SignedDyadic[occupied.length];
		for (int c = 0; c < room.length; c++) {
			var capacity = Fraction.of(domain.effectiveOpenCellVolume(c)).subtract(occupied[c]);
			if (capacity.signum() < 0) throw new IllegalStateException("Resolved liquid exceeds actual cell capacity");
			double represented = capacity.rounded();
			if (Fraction.of(represented).subtract(capacity).signum() > 0) represented = Math.nextDown(represented);
			room[c] = SignedDyadic.of(represented);
		}
		return room;
	}
	private static void accumulate(LiquidGeometryRefinement.Cell cell, int level, FluidDomain domain, Fraction scale, Fraction[] occupied) {
		if (cell.level() < level) {
			for (var child : LiquidGeometryRefinement.children(cell)) accumulate(child, level, domain, scale, occupied);
			return;
		}
		int shift = cell.level() - level, x = cell.x() >>> shift, y = cell.y() >>> shift, z = cell.z() >>> shift;
		int index = domain.cellIndexChecked(x, y, z);
		occupied[index] = occupied[index].add(LiquidConvexRegion.of(cell.geometry()).measure().volume().multiply(scale));
	}
}
