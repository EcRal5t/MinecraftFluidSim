package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.GasGeometryState;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/** Exact sparse chamber overlap, indexed by dyadic leaf addresses before convex clipping. */
final class ResolvedGasOverlap {
	private static final class Index {
		final List<Integer> cells = new ArrayList<>();
		Index[] children;
	}
	final Fraction[] oldCovered, newCovered;
	private final TreeMap<Long, Fraction> overlap = new TreeMap<>();

	ResolvedGasOverlap(GasGeometryState previous, GasGeometryState next, int maximumPairs) {
		if (previous.rootWidthMetres() != next.rootWidthMetres() || maximumPairs < 0)
			throw new IllegalArgumentException("Inconsistent gas overlap support");
		var oldCells = LiquidGeometryStateMapper.restoreCells(previous.cells()); var newCells = LiquidGeometryStateMapper.restoreCells(next.cells());
		int[] oldNodes = previous.nodeOrdinals(), newNodes = next.nodeOrdinals();
		oldCovered = new Fraction[oldCells.size()]; newCovered = new Fraction[newCells.size()];
		Arrays.fill(oldCovered, Fraction.ZERO); Arrays.fill(newCovered, Fraction.ZERO);
		var width = Fraction.of(previous.rootWidthMetres()); var scale = width.multiply(width).multiply(width);
		var root = new Index();
		for (int c = 0; c < oldCells.size(); c++) {
			var cell = oldCells.get(c); var index = root;
			for (int level = 1; level <= cell.level(); level++) {
				if (index.children == null) index.children = new Index[8];
				int slot = slot(cell, cell.level() - level);
				if (index.children[slot] == null) index.children[slot] = new Index(); index = index.children[slot];
			}
			index.cells.add(c);
		}
		int pairs = 0;
		for (int c = 0; c < newCells.size(); c++) {
			var cell = newCells.get(c); var candidates = new ArrayList<Integer>(); var index = root;
			for (int level = 0; index != null && level < cell.level(); level++) {
				candidates.addAll(index.cells); index = index.children == null ? null : index.children[slot(cell, cell.level() - level - 1)];
			}
			collect(index, candidates);
			for (int old : candidates) {
				if (pairs++ == maximumPairs) throw new IllegalStateException("Resolved gas overlap exceeds its geometric work budget");
				var region = LiquidConvexRegion.of(cell.geometry());
				for (var plane : oldCells.get(old).geometry().planes()) { region = region.clip(plane); if (!region.hasVolume()) break; }
				var volume = region.measure().volume().multiply(scale); if (volume.signum() == 0) continue;
				oldCovered[old] = oldCovered[old].add(volume); newCovered[c] = newCovered[c].add(volume);
				overlap.merge(key(oldNodes[old], newNodes[c]), volume, Fraction::add);
			}
		}
	}
	TreeMap<Integer, Fraction> destinations(int oldNode) {
		var result = new TreeMap<Integer, Fraction>();
		for (var entry : overlap.subMap(key(oldNode, 0), key(oldNode + 1, 0)).entrySet()) result.put((int) (long) entry.getKey(), entry.getValue());
		return result;
	}
	static List<BigInteger> weights(List<Fraction> volumes) {
		var denominator = BigInteger.ONE;
		for (var volume : volumes) {
			if (volume.signum() < 0) throw new IllegalArgumentException("Negative gas overlap weight");
			denominator = denominator.divide(LiquidIntegerArithmetic.gcd(denominator, volume.denominator())).multiply(volume.denominator());
		}
		var result = new ArrayList<BigInteger>(volumes.size());
		for (var volume : volumes) result.add(volume.numerator().multiply(denominator.divide(volume.denominator())));
		return List.copyOf(result);
	}
	private static long key(int oldNode, int newNode) { return (long) oldNode << 32 | newNode & 0xffffffffL; }
	private static int slot(LiquidGeometryRefinement.Cell cell, int shift) { return (cell.x() >>> shift & 1) | (cell.y() >>> shift & 1) << 1 | (cell.z() >>> shift & 1) << 2; }
	private static void collect(Index index, ArrayList<Integer> result) {
		if (index == null) return; result.addAll(index.cells);
		if (index.children != null) for (var child : index.children) collect(child, result);
	}
}
