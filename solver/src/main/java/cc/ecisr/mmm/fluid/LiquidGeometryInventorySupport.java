package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Exact inventory-group volumes in physical grid cells and downward-rounded transfer capacity. */
final class LiquidGeometryInventorySupport {
	private LiquidGeometryInventorySupport() { }
	/** Volumes in normalized root units; same-cell auxiliary fragments are integrated before observation. */
	static Map<Integer, Map<Integer, Fraction>> volumesByOwner(FluidDomainStepCoordinator domain) {
		int span = LiquidGeometryDomainPartition.rootSpan(domain.sizeX, domain.sizeY, domain.sizeZ);
		int level = Integer.numberOfTrailingZeros(span);
		var state = domain.liquidGeometryState; int[] owners = state.copyFragmentOwners();
		var cells = LiquidGeometryStateMapper.restore(state);
		var expected = new HashMap<Integer, Fraction>(); var volumes = new HashMap<Integer, TreeMap<Integer, Fraction>>();
		for (int f = 0; f < cells.size(); f++) {
			expected.merge(owners[f], cells.get(f).geometry().measure().volume(), Fraction::add);
			accumulate(cells.get(f), level, domain, volumes.computeIfAbsent(owners[f], ignored -> new TreeMap<>()));
		}
		var result = new HashMap<Integer, Map<Integer, Fraction>>();
		for (var group : volumes.entrySet()) {
			var total = Fraction.ZERO; for (var volume : group.getValue().values()) total = total.add(volume);
			if (!total.equals(expected.get(group.getKey()))) throw new IllegalStateException("Geometric inventory support lost occupied volume");
			result.put(group.getKey(), java.util.Collections.unmodifiableMap(group.getValue()));
		}
		return Map.copyOf(result);
	}
	static SignedDyadic[] available(FluidDomain domain) {
		var occupied = new Fraction[domain.cellCount]; Arrays.fill(occupied, Fraction.ZERO);
		int span = LiquidGeometryDomainPartition.rootSpan(domain.sizeX, domain.sizeY, domain.sizeZ);
		var scale = Fraction.of(span); scale = scale.multiply(scale).multiply(scale);
		for (var group : volumesByOwner(domain).values()) for (var entry : group.entrySet())
			occupied[entry.getKey()] = occupied[entry.getKey()].add(entry.getValue().multiply(scale));
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
	private static void accumulate(LiquidGeometryRefinement.Cell cell, int level, FluidDomainStepCoordinator domain, TreeMap<Integer, Fraction> occupied) {
		if (cell.level() < level) {
			for (var child : LiquidGeometryRefinement.children(cell)) accumulate(child, level, domain, occupied);
			return;
		}
		int shift = cell.level() - level, x = cell.x() >>> shift, y = cell.y() >>> shift, z = cell.z() >>> shift;
		int index = domain.cellIndexChecked(x, y, z);
		occupied.merge(index, cell.geometry().measure().volume(), Fraction::add);
	}
}
