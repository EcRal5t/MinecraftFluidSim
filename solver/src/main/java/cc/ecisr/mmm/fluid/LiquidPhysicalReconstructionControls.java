package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Physical phase occupancy stays fixed while conservative velocity error selects its mechanical partition. */
final class LiquidPhysicalReconstructionControls {
	record Control(int leaf, int phase, List<LiquidPolyhedron> pieces, LiquidGeometryVelocityProjection.Value field) { }
	private record Node(int leaf, int phase, LiquidGeometryRefinement.Cell box, List<LiquidPolyhedron> pieces,
			List<LiquidGeometryVelocityProjection.Source> sources, LiquidGeometryVelocityProjection.Value reference, Fraction volume) {
		Node(int leaf, int phase, LiquidGeometryRefinement.Cell box, List<LiquidPolyhedron> pieces, List<LiquidGeometryVelocityProjection.Source> sources) {
			this(leaf, phase, box, pieces, sources, LiquidGeometryVelocityProjection.summarize(sources).get(phase), LiquidVolumeInterface.measure(pieces).volume());
		}
	}
	final List<Control> controls;
	final Fraction redistributedVolume, redistributionSquaredDisplacementVolumeBound;
	final int evaluations;

	LiquidPhysicalReconstructionControls(List<LiquidMomentReconstruction.Leaf> geometry,
			Function<LiquidGeometryRefinement.Cell, List<LiquidGeometryVelocityProjection.Source>> sources,
			LiquidGeometryVelocityProjection.RmsTolerance tolerance, Map<Integer, Fraction> limits,
			int liquidLabels, LiquidMomentReconstruction.Budget budget) {
		var work = new LiquidSupportVolumeFit.Work(budget.maximumEvaluations()); var nodes = new ArrayList<Node>();
		for (int l = 0; l < geometry.size(); l++) {
			var leaf = geometry.get(l); var reference = sources.apply(leaf.open());
			var fieldsByPhase = new java.util.TreeMap<Integer, List<LiquidGeometryVelocityProjection.Source>>();
			var piecesByPhase = new java.util.TreeMap<Integer, List<LiquidPolyhedron>>();
			for (var phase : leaf.phases()) if (phase.control() < liquidLabels) piecesByPhase.put(phase.control(), phase.pieces());
			for (var source : reference) {
				var shape = source.geometry(); for (var face : LiquidExactPolygon.faces(leaf.open().geometry())) {
					shape = clip(shape, face.coefficients(), 1, work); if (shape == null) break;
				}
				if (shape != null) fieldsByPhase.computeIfAbsent(source.phase(), ignored -> new ArrayList<>()).add(withGeometry(source, shape));
			}
			var labels = new java.util.TreeSet<>(piecesByPhase.keySet()); labels.addAll(fieldsByPhase.keySet());
			for (int phase : labels) {
				var pieces = piecesByPhase.getOrDefault(phase, List.of()); var fields = fieldsByPhase.getOrDefault(phase, List.of());
				var shapes = new ArrayList<>(pieces); fields.forEach(field -> shapes.add(field.geometry()));
				var bounds = bounds(shapes); var box = LiquidPhasePartition.Address.of(leaf.open()).cell(box(bounds[0], bounds[1]));
				nodes.add(new Node(l, phase, box, pieces, List.copyOf(fields)));
			}
		}
		LiquidConservativeVolumeRedistribution.Result balanced;
		for (;;) {
			requireBudget(nodes, budget); work.context = ", physicalVelocityControls=" + nodes.size(); work.charge();
			balanced = balance(nodes); var unresolved = tolerance.unresolvedLeaves(limits, balanced.fields()); if (unresolved.isEmpty()) break;
			if (nodes.size() >= budget.maximumLeaves()) throw new IllegalStateException("Physical velocity reconstruction exceeds its control budget");
			int selected = -1; var largest = Fraction.ZERO;
			for (int n : unresolved) for (var field : balanced.fields().get(n).values()) if (selected < 0 || field.kineticLoss().compareTo(largest) > 0
					|| field.kineticLoss().equals(largest) && n < selected) {
				selected = n; largest = field.kineticLoss();
			}
			var least = loss(balanced); ArrayList<Node> accepted = null;
			for (int axis = 0; axis < 3; axis++) {
				var candidate = new ArrayList<>(nodes); var children = refine(nodes.get(selected), axis, work);
				candidate.remove(selected); candidate.addAll(selected, children);
				if (candidate.size() > budget.maximumLeaves() || regions(candidate) > budget.maximumRegions()) continue;
				work.charge();
				var defect = loss(balance(candidate)); if (defect.compareTo(least) < 0) { least = defect; accepted = candidate; }
			}
			if (accepted == null) {
				// Tensor refinement resolves variations whose conditional mean cancels on every single-axis split.
				var children = List.of(nodes.get(selected));
				for (int axis = 0; axis < 3; axis++) {
					var next = new ArrayList<Node>(); for (var child : children) next.addAll(refine(child, axis, work)); children = List.copyOf(next);
				}
				accepted = new ArrayList<>(nodes); accepted.remove(selected); accepted.addAll(selected, children); requireBudget(accepted, budget);
			}
			nodes = accepted;
		}
		var result = new ArrayList<Control>();
		for (int n = 0; n < nodes.size(); n++) if (!nodes.get(n).pieces().isEmpty()) {
			var node = nodes.get(n); var field = balanced.fields().get(n).get(node.phase());
			if (!field.volume().equals(LiquidVolumeInterface.measure(node.pieces()).volume())) throw new IllegalStateException("Mechanical projection changed physical occupancy");
			result.add(new Control(node.leaf(), node.phase(), node.pieces(), field));
		}
		controls = List.copyOf(result); evaluations = work.evaluations;
		redistributedVolume = balanced.movedVolume(); redistributionSquaredDisplacementVolumeBound = balanced.squaredDisplacementVolumeBound();
	}
	private static LiquidConservativeVolumeRedistribution.Result balance(List<Node> nodes) {
		var sources = new ArrayList<LiquidConservativeVolumeRedistribution.Source>(); var targets = new ArrayList<LiquidConservativeVolumeRedistribution.Target>();
		for (int n = 0; n < nodes.size(); n++) {
			var node = nodes.get(n); var field = node.reference();
			if (field != null) sources.add(new LiquidConservativeVolumeRedistribution.Source(n, node.phase(), field));
			if (!node.pieces().isEmpty()) targets.add(new LiquidConservativeVolumeRedistribution.Target(n, node.phase(), node.volume()));
		}
		return LiquidConservativeVolumeRedistribution.project(nodes.stream().map(Node::box).toList(), sources, targets);
	}
	private static Fraction loss(LiquidConservativeVolumeRedistribution.Result result) {
		var value = Fraction.ZERO; for (var fields : result.fields()) for (var field : fields.values()) value = value.add(field.kineticLoss()); return value;
	}
	private static List<Node> refine(Node parent, int axis, LiquidSupportVolumeFit.Work work) {
		var extent = bounds(List.of(parent.box().geometry())); var middle = extent[0][axis].add(extent[1][axis]).divide(Fraction.of(2));
		var next = new ArrayList<Node>(); var plane = plane(axis, middle, 1);
		for (int side : new int[]{1, -1}) {
			var pieces = new ArrayList<LiquidPolyhedron>(); for (var piece : parent.pieces()) { var shape = clip(piece, plane, side, work); if (shape != null) pieces.add(shape); }
			var sources = new ArrayList<LiquidGeometryVelocityProjection.Source>(); for (var source : parent.sources()) {
				var shape = clip(source.geometry(), plane, side, work); if (shape != null) sources.add(withGeometry(source, shape));
			}
			if (pieces.isEmpty() && sources.isEmpty()) continue;
			var shape = clip(parent.box().geometry(), plane, side, work);
			next.add(new Node(parent.leaf(), parent.phase(), LiquidPhasePartition.Address.of(parent.box()).cell(shape), List.copyOf(pieces), List.copyOf(sources)));
		}
		return List.copyOf(next);
	}
	private static void requireBudget(List<Node> nodes, LiquidMomentReconstruction.Budget budget) {
		if (nodes.size() > budget.maximumLeaves()) throw new IllegalStateException("Physical velocity reconstruction exceeds its control budget");
		if (regions(nodes) > budget.maximumRegions()) throw new IllegalStateException("Physical velocity reconstruction exceeds its positive-region budget");
	}
	private static long regions(List<Node> nodes) {
		long pieces = 0; for (var node : nodes) pieces += node.pieces().size() + node.sources().size(); return pieces;
	}
	private static LiquidGeometryVelocityProjection.Source withGeometry(LiquidGeometryVelocityProjection.Source source, LiquidPolyhedron geometry) {
		return new LiquidGeometryVelocityProjection.Source(source.phase(), source.inventory(), source.density(), geometry, source.velocity());
	}
	private static LiquidPolyhedron clip(LiquidPolyhedron geometry, List<BigInteger> plane, int side, LiquidSupportVolumeFit.Work work) {
		var original = LiquidExactPolygon.faces(geometry); var faces = LiquidExactPolygon.clipVolume(original, plane, side);
		if (faces.isEmpty()) return null; if (faces == original) return geometry;
		work.charge(); return LiquidPolyhedron.restoredExact(faces);
	}
	private static Fraction[][] bounds(List<LiquidPolyhedron> geometry) {
		var low = new Fraction[3]; var high = new Fraction[3];
		for (var shape : geometry) {
			var extent = shape.coordinateBounds(); for (int a = 0; a < 3; a++) {
				if (low[a] == null || extent[0][a].compareTo(low[a]) < 0) low[a] = extent[0][a];
				if (high[a] == null || extent[1][a].compareTo(high[a]) > 0) high[a] = extent[1][a];
			}
		}
		for (int a = 0; a < 3; a++) if (low[a] == null || high[a].compareTo(low[a]) <= 0) throw new IllegalArgumentException("Mechanical support has no positive spatial extent");
		return new Fraction[][]{low, high};
	}
	private static List<BigInteger> plane(int axis, Fraction position, int side) {
		var result = new ArrayList<BigInteger>(); for (int a = 0; a < 3; a++) result.add(a == axis ? position.denominator().multiply(BigInteger.valueOf(side)) : BigInteger.ZERO);
		result.add(position.numerator().multiply(BigInteger.valueOf(side))); return List.copyOf(result);
	}
	private static LiquidPolyhedron box(Fraction[] low, Fraction[] high) {
		var points = new LiquidExactPolygon.Vertex[8]; for (int i = 0; i < 8; i++) {
			var x = new Fraction[3]; for (int a = 0; a < 3; a++) x[a] = (i & (1 << a)) == 0 ? low[a] : high[a]; points[i] = LiquidExactPolygon.vertex(x);
		}
		int[][] indices = {{0, 4, 6, 2}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 2, 3, 1}, {4, 5, 7, 6}};
		var faces = new ArrayList<LiquidExactPolygon>(); for (int f = 0; f < 6; f++) {
			var vertices = new ArrayList<LiquidExactPolygon.Vertex>(); for (int i : indices[f]) vertices.add(points[i]);
			faces.add(LiquidExactPolygon.restored(vertices, plane(f / 2, (f & 1) == 0 ? low[f / 2] : high[f / 2], (f & 1) == 0 ? -1 : 1)));
		}
		return LiquidPolyhedron.restoredExact(List.copyOf(faces));
	}
}
