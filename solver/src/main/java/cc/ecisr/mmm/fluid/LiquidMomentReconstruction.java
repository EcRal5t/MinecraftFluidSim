package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.List;

/** Error-controlled local interface reconstruction inside a convex open dyadic cell. */
final class LiquidMomentReconstruction {
	/** Distances use the input coordinate units; occupancy length is cell width times symmetric difference / liquid volume. */
	record Tolerance(double centroidDistance, double occupancyErrorLength, int coordinateBits) {
		Tolerance {
			if (!(centroidDistance > 0) || !Double.isFinite(centroidDistance) || !(occupancyErrorLength > 0)
					|| !Double.isFinite(occupancyErrorLength) || coordinateBits < 1 || coordinateBits > 1074)
				throw new IllegalArgumentException("Invalid interface reconstruction tolerance");
		}
	}
	record Budget(int maximumLeaves, int maximumEvaluations, int maximumFitEvaluations) {
		Budget { if (maximumLeaves < 1 || maximumEvaluations < 1 || maximumFitEvaluations < 1) throw new IllegalArgumentException("Invalid interface reconstruction budget"); }
	}
	record Leaf(LiquidGeometryRefinement.Cell open, LiquidPolyhedron liquid) { }
	record Result(List<Leaf> leaves, LiquidExactPolygon.Measure sourceMeasure, LiquidExactPolygon.Measure representedMeasure,
			Fraction symmetricDifferenceVolume, double maximumCentroidError, int evaluations) {
		Result { leaves = List.copyOf(leaves); }
	}
	private record Pending(LiquidGeometryRefinement.Cell open, List<LiquidPolyhedron> source, LiquidPolyhedron.Point normal) { }
	private LiquidMomentReconstruction() { }
	static Result reconstruct(LiquidGeometryRefinement.Cell open, List<LiquidPolyhedron> source, LiquidPolyhedron.Point normal,
			Tolerance tolerance, Budget budget) {
		if (open == null || source == null || normal == null || tolerance == null || budget == null || open.geometry().measure().signum() <= 0)
			throw new IllegalArgumentException("Incomplete local interface reconstruction");
		for (var face : LiquidExactPolygon.faces(open.geometry())) for (var vertex : face.vertices()) for (int axis = 0; axis < 3; axis++) {
			var coordinate = vertex.coordinates()[axis]; var lo = Fraction.of(open.minimum(axis));
			if (coordinate.subtract(lo).signum() < 0 || coordinate.subtract(lo.add(Fraction.of(open.width()))).signum() > 0)
				throw new IllegalArgumentException("Open interface geometry leaves its dyadic cell");
		}
		var address = LiquidPhasePartition.Address.of(open); var pieces = new ArrayList<LiquidGeometryRefinement.Cell>();
		for (var geometry : source) {
			if (geometry == null || geometry.measure().signum() <= 0) throw new IllegalArgumentException("Empty interface source fragment");
			var contained = LiquidConvexRegion.of(geometry);
			for (var plane : open.geometry().planes()) contained = contained.clip(plane);
			if (!contained.measure().same(geometry.measure())) throw new IllegalArgumentException("Interface source leaves its open cell");
			pieces.add(address.cell(geometry));
		}
		LiquidPhasePartition.requireDisjoint(pieces);
		var target = measure(source); var represented = LiquidConvexRegion.EMPTY.measure(); var difference = Fraction.ZERO;
		var pending = new java.util.ArrayDeque<Pending>(); pending.add(new Pending(open, List.copyOf(source), normal));
		var leaves = new ArrayList<Leaf>(); double maximumError = 0; int evaluations = 0;
		while (!pending.isEmpty()) {
			var cell = pending.removeLast(); var original = measure(cell.source); var geometry = LiquidPolyhedron.EMPTY;
			double error = 0; var localDifference = Fraction.ZERO; boolean accepted = true; var nextNormal = cell.normal;
			if (original.signum() > 0) {
				if (original.volume().equals(cell.open.geometry().measure().volume())) {
					if (!original.same(cell.open.geometry().measure())) throw new IllegalStateException("Full interface occupancy lost its exact moments");
					geometry = cell.open.geometry();
				} else {
					if (evaluations == budget.maximumEvaluations) throw new IllegalStateException("Interface reconstruction exceeds its evaluation budget");
					var fit = LiquidMomentInterface.reconstruct(cell.open.geometry(), original, cell.normal, tolerance.coordinateBits,
							tolerance.centroidDistance, Math.min(budget.maximumFitEvaluations, budget.maximumEvaluations - evaluations));
					evaluations += fit.evaluations(); geometry = fit.interfaceGeometry().geometry; error = fit.centroidError(); nextNormal = fit.normal();
					var overlap = Fraction.ZERO;
					for (var part : cell.source) {
						var common = LiquidConvexRegion.of(part); for (var plane : geometry.planes()) common = common.clip(plane);
						overlap = overlap.add(common.measure().volume());
					}
					localDifference = original.volume().subtract(overlap).multiply(Fraction.of(2));
					if (localDifference.signum() < 0) throw new IllegalStateException("Interface reconstruction overlap exceeds source occupancy");
					accepted = error <= tolerance.centroidDistance && localDifference.multiply(Fraction.of(cell.open.width())).divide(original.volume())
							.subtract(Fraction.of(tolerance.occupancyErrorLength)).signum() <= 0;
				}
			}
			if (accepted) {
				leaves.add(new Leaf(cell.open, geometry)); represented = represented.add(geometry.measure());
				difference = difference.add(localDifference); maximumError = Math.max(maximumError, error); continue;
			}
			var children = LiquidGeometryRefinement.children(cell.open);
			if (leaves.size() + pending.size() + children.size() > budget.maximumLeaves)
				throw new IllegalStateException("Interface reconstruction exceeds its spatial leaf budget");
			for (var child : children) {
				var occupied = new ArrayList<LiquidPolyhedron>();
				for (var part : cell.source) {
					var clipped = LiquidConvexRegion.of(part); for (var plane : child.geometry().planes()) clipped = clipped.clip(plane);
					if (clipped.hasVolume()) occupied.add(clipped.geometry());
				}
				pending.add(new Pending(child, List.copyOf(occupied), nextNormal));
			}
		}
		if (!represented.volume().equals(target.volume())) throw new IllegalStateException("Local reconstruction lost exact source volume");
		return new Result(leaves, target, represented, difference, maximumError, evaluations);
	}
	private static LiquidExactPolygon.Measure measure(List<LiquidPolyhedron> geometry) {
		var result = LiquidConvexRegion.EMPTY.measure(); for (var part : geometry) result = result.add(part.measure()); return result;
	}
}
