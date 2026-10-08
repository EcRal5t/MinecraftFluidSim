package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Bisects actual material support along its independent coordinate extents, preserving collision leaf addresses. */
final class LiquidPhysicalControlRefinement {
	record Piece(LiquidGeometryRefinement.Cell cell, int source) { }
	private record Facet(int piece, LiquidExactPolygon face) { }
	private LiquidPhysicalControlRefinement() { }
	static List<List<Piece>> partition(List<Piece> source, int depth, int maximumPieces) {
		if (depth < 0) throw new IllegalArgumentException("Invalid physical control refinement depth");
		if (maximumPieces < source.size()) throw new IllegalStateException("Physical control refinement exceeds its positive-region budget");
		var work = new LiquidSupportVolumeFit.Work(4_194_304); work.context = ", physicalControlSharedFaces";
		List<List<Piece>> groups = connected(source, work);
		for (int level = 0; level < depth; level++) {
			var next = new ArrayList<List<Piece>>(); int retained = 0;
			for (var group : groups) {
				var middle = midpoints(group); var children = new ArrayList<List<Piece>>(); children.add(group);
				for (int axis = 0; axis < 3; axis++) {
					var split = new ArrayList<List<Piece>>();
					var plane = new ArrayList<BigInteger>(4);
					for (int a = 0; a < 3; a++) plane.add(a == axis ? middle[axis].denominator() : BigInteger.ZERO);
					plane.add(middle[axis].numerator());
					long count = retained;
					for (var child : children) {
						var low = new ArrayList<Piece>(); var high = new ArrayList<Piece>();
						for (var piece : child) {
							int before = low.size() + high.size();
							clip(piece, plane, 1, low); clip(piece, plane, -1, high);
							count += low.size() + high.size() - before;
							if (count > maximumPieces) throw new IllegalStateException("Physical control refinement exceeds its positive-region budget");
						}
						split.addAll(connected(low, work)); split.addAll(connected(high, work));
					}
					children = split;
				}
				for (var child : children) { retained += child.size(); next.add(child); }
			}
			groups = List.copyOf(next);
		}
		return groups;
	}
	/** Every pressure unknown has connected positive-volume support; a point or edge supplies no fluid passage. */
	static List<List<Piece>> connected(List<Piece> pieces, LiquidSupportVolumeFit.Work work) {
		var positive = new java.util.HashMap<LiquidGeometryPlane.Key, ArrayList<Facet>>();
		var negative = new java.util.HashMap<LiquidGeometryPlane.Key, ArrayList<Facet>>();
		int[] roots = java.util.stream.IntStream.range(0, pieces.size()).toArray();
		for (int p = 0; p < pieces.size(); p++) for (var face : LiquidExactPolygon.faces(pieces.get(p).cell().geometry())) {
			var plane = face.plane(); var opposite = (plane.positive() ? negative : positive).get(plane.key());
			if (opposite != null) for (var other : opposite) {
				int first = root(roots, p), second = root(roots, other.piece()); if (first == second) continue;
				work.charge(); if (face.intersection(other.face()).hasArea()) roots[Math.max(first, second)] = Math.min(first, second);
			}
			(plane.positive() ? positive : negative).computeIfAbsent(plane.key(), ignored -> new ArrayList<>()).add(new Facet(p, face));
		}
		var groups = new java.util.LinkedHashMap<Integer, ArrayList<Piece>>();
		for (int p = 0; p < pieces.size(); p++) groups.computeIfAbsent(root(roots, p), ignored -> new ArrayList<>()).add(pieces.get(p));
		return groups.values().stream().map(List::copyOf).toList();
	}
	private static int root(int[] roots, int p) {
		while (roots[p] != p) { roots[p] = roots[roots[p]]; p = roots[p]; } return p;
	}
	private static Fraction[] midpoints(List<Piece> group) {
		var low = new Fraction[3]; var high = new Fraction[3];
		for (var piece : group) for (var face : LiquidExactPolygon.faces(piece.cell().geometry())) for (var point : face.vertices()) {
			var x = point.coordinates(); for (int a = 0; a < 3; a++) {
				if (low[a] == null || x[a].compareTo(low[a]) < 0) low[a] = x[a];
				if (high[a] == null || x[a].compareTo(high[a]) > 0) high[a] = x[a];
			}
		}
		var middle = new Fraction[3];
		for (int a = 0; a < 3; a++) {
			if (low[a] == null || high[a].compareTo(low[a]) <= 0) throw new IllegalArgumentException("Physical control has no positive spatial extent");
			middle[a] = low[a].add(high[a]).divide(Fraction.of(2));
		}
		return middle;
	}
	private static void clip(Piece piece, List<BigInteger> plane, int side, ArrayList<Piece> result) {
		var original = LiquidExactPolygon.faces(piece.cell().geometry()); var faces = LiquidExactPolygon.clipVolume(original, plane, side);
		if (!faces.isEmpty()) result.add(new Piece(LiquidPhasePartition.Address.of(piece.cell()).cell(
				faces == original ? piece.cell().geometry() : LiquidPolyhedron.restoredExact(faces)), piece.source()));
	}
}
