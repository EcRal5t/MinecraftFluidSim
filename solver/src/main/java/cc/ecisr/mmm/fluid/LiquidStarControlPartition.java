package cc.ecisr.mmm.fluid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Cancels auxiliary cuts by joining adjacent positive regions with an exact common fan support. */
final class LiquidStarControlPartition {
	private record Side(int region, LiquidExactPolygon face) { }
	private record Pair(int first, int second) { }
	record Region(LiquidPolyhedron geometry, List<Integer> pieces) { }
	private static final int MAXIMUM_PAIRS = 4_194_304;
	static final class Work {
		private int pairs;
		void pair() { if (pairs++ == MAXIMUM_PAIRS) throw new IllegalStateException("Star control partition exceeds its shared-face work budget"); }
	}
	private LiquidStarControlPartition() { }

	static List<Region> build(List<LiquidGeometryRefinement.Cell> pieces, Work work) {
		var regions = new ArrayList<Region>();
		for (int i = 0; i < pieces.size(); i++) regions.add(new Region(pieces.get(i).geometry(), List.of(i)));
		var positive = new LinkedHashMap<LiquidGeometryPlane.Key, ArrayList<Side>>();
		var negative = new LinkedHashMap<LiquidGeometryPlane.Key, ArrayList<Side>>();
		var queue = new ArrayDeque<Pair>(); var scheduled = new java.util.HashSet<Long>();
		int next = 0;
		while (true) {
			while (next < regions.size()) {
				int index = next++; var region = regions.get(index); if (region == null) continue;
				for (var face : LiquidExactPolygon.faces(region.geometry())) {
					var plane = face.plane(); var same = plane.positive() ? positive : negative;
					var opposite = (plane.positive() ? negative : positive).get(plane.key());
					if (opposite != null) for (var side : opposite) if (regions.get(side.region()) != null) {
						long identity = (long) side.region() << 32 | index;
						if (scheduled.contains(identity)) continue;
						work.pair(); if (!face.intersection(side.face()).hasArea()) continue;
						scheduled.add(identity); queue.addLast(new Pair(side.region(), index));
					}
					same.computeIfAbsent(plane.key(), ignored -> new ArrayList<>()).add(new Side(index, face));
				}
			}
			if (queue.isEmpty()) break;
			var pair = queue.removeFirst(); var a = regions.get(pair.first()); var b = regions.get(pair.second());
			if (a == null || b == null) continue;
			var geometry = assemble(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, a.geometry()),
					new LiquidGeometryRefinement.Cell(0, 0, 0, 0, b.geometry())));
			if (geometry.kernelPoint() == null) continue;
			var support = new ArrayList<>(a.pieces()); support.addAll(b.pieces());
			regions.set(pair.first(), null); regions.set(pair.second(), null);
			regions.add(new Region(geometry, List.copyOf(support)));
		}
		return regions.stream().filter(java.util.Objects::nonNull).toList();
	}
	static LiquidPolyhedron assemble(List<LiquidGeometryRefinement.Cell> pieces) {
		if (pieces.size() == 1) return pieces.getFirst().geometry();
		var measure = LiquidConvexRegion.EMPTY.measure(); for (var piece : pieces) measure = measure.add(piece.geometry().measure());
		return LiquidPolyhedron.restoredExact(LiquidExactPolygon.splitEdges(LiquidExteriorGeometry.of(pieces)), measure);
	}
}
