package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.List;

/** Joint reconstruction retains each control volume inside the shared open dyadic cell. */
final class LiquidMomentReconstruction {
	/** Distances use input coordinates; occupancy length is cell width times symmetric difference / control volume. */
	record Tolerance(double centroidDistance, double occupancyErrorLength, int coordinateBits) {
		Tolerance {
			if (!(centroidDistance > 0) || !Double.isFinite(centroidDistance) || !(occupancyErrorLength > 0)
					|| !Double.isFinite(occupancyErrorLength) || coordinateBits < 1 || coordinateBits > 1074)
				throw new IllegalArgumentException("Invalid interface reconstruction tolerance");
		}
	}
	record Budget(int maximumLeaves, int maximumEvaluations, int maximumFitEvaluations, int maximumRegions) {
		Budget {
			if (maximumLeaves < 1 || maximumEvaluations < 1 || maximumFitEvaluations < 1 || maximumRegions < 1)
				throw new IllegalArgumentException("Invalid interface reconstruction budget");
		}
	}
	record Source(int control, List<LiquidPolyhedron> pieces, LiquidPolyhedron.Point normal) {
		Source { if (control < 0 || normal == null) throw new IllegalArgumentException("Invalid reconstruction control"); pieces = List.copyOf(pieces); }
	}
	record Phase(int control, List<LiquidPolyhedron> pieces) {
		Phase { pieces = List.copyOf(pieces); }
	}
	record Leaf(LiquidGeometryRefinement.Cell open, List<Phase> phases) {
		Leaf { phases = List.copyOf(phases); }
	}
	record PhaseStats(int control, LiquidExactPolygon.Measure sourceMeasure, LiquidExactPolygon.Measure representedMeasure,
			Fraction symmetricDifferenceVolumeBound, double maximumCentroidError, double maximumOccupancyErrorLength) { }
	record Result(List<Leaf> leaves, List<PhaseStats> phases, int evaluations) {
		Result { leaves = List.copyOf(leaves); phases = List.copyOf(phases); }
	}
	private static final class Totals {
		final LiquidExactPolygon.Measure source;
		LiquidExactPolygon.Measure represented = LiquidConvexRegion.EMPTY.measure();
		Fraction difference = Fraction.ZERO;
		double centroidError, occupancyError;
		Totals(LiquidExactPolygon.Measure source) { this.source = source; }
	}
	private record Pending(LiquidGeometryRefinement.Cell open, List<Source> sources, int coordinateBits) { }
	private LiquidMomentReconstruction() { }
	static Result reconstruct(LiquidGeometryRefinement.Cell open, List<LiquidPolyhedron> source, LiquidPolyhedron.Point normal,
			Tolerance tolerance, Budget budget) {
		return reconstruct(open, List.of(new Source(0, source, normal)), tolerance, budget);
	}
	static Result reconstruct(LiquidGeometryRefinement.Cell open, List<Source> sources, Tolerance tolerance, Budget budget) {
		return reconstruct(open, sources, tolerance, budget, ignored -> true);
	}
	static Result reconstruct(LiquidGeometryRefinement.Cell open, List<Source> sources, Tolerance tolerance, Budget budget,
			java.util.function.Predicate<LiquidGeometryRefinement.Cell> velocityResolved) {
		if (open == null || sources == null || tolerance == null || budget == null || velocityResolved == null || open.geometry().measure().signum() <= 0)
			throw new IllegalArgumentException("Incomplete local interface reconstruction");
		for (var face : LiquidExactPolygon.faces(open.geometry())) for (var vertex : face.vertices()) for (int axis = 0; axis < 3; axis++) {
			var coordinate = vertex.coordinates()[axis]; var lo = Fraction.of(open.minimum(axis));
			if (coordinate.subtract(lo).signum() < 0 || coordinate.subtract(lo.add(Fraction.of(open.width()))).signum() > 0)
				throw new IllegalArgumentException("Open interface geometry leaves its dyadic cell");
		}
		var totals = new java.util.LinkedHashMap<Integer, Totals>(); var address = LiquidPhasePartition.Address.of(open);
		var pieces = new ArrayList<LiquidGeometryRefinement.Cell>();
		for (var source : sources) {
			if (totals.putIfAbsent(source.control, new Totals(LiquidVolumeInterface.measure(source.pieces))) != null)
				throw new IllegalArgumentException("Repeated reconstruction control");
			for (var geometry : source.pieces) {
				if (geometry.measure().signum() <= 0) throw new IllegalArgumentException("Empty interface source fragment");
				var contained = LiquidConvexRegion.of(geometry); for (var plane : open.geometry().planes()) contained = contained.clip(plane);
				if (!contained.measure().same(geometry.measure())) throw new IllegalArgumentException("Interface source leaves its open cell");
				pieces.add(address.cell(geometry));
			}
		}
		LiquidPhasePartition.requireDisjoint(pieces);
		var pending = new java.util.ArrayDeque<Pending>(); pending.add(new Pending(open, List.copyOf(sources), tolerance.coordinateBits));
		var leaves = new ArrayList<Leaf>(); int evaluations = 0, retainedRegions = 0;
		while (!pending.isEmpty()) {
			var cell = pending.removeLast(); var available = List.of(cell.open.geometry());
			var assigned = new ArrayList<LiquidGeometryRefinement.Cell>(); var phases = new ArrayList<Phase>();
			var volumes = new ArrayList<Fraction>();
			var statistics = new ArrayList<PhaseStats>(); var hints = new java.util.HashMap<Integer, LiquidPolyhedron.Point>();
			var order = cell.sources.stream().filter(source -> !source.pieces.isEmpty()).sorted((a, b) -> compare(a, b, cell.open)).toList();
			var total = Fraction.ZERO; for (var source : order) total = total.add(LiquidVolumeInterface.measure(source.pieces).volume());
			boolean complete = total.equals(cell.open.geometry().measure().volume());
			boolean accepted = velocityResolved.test(cell.open);
			List<Phase> planar = List.of(); List<Fraction> correction = List.of();
			try {
			if (accepted) {
			for (int p = 0; p < order.size(); p++) {
				var source = order.get(p); var original = LiquidVolumeInterface.measure(source.pieces);
				if (evaluations == budget.maximumEvaluations) throw new IllegalStateException("Interface reconstruction exceeds its evaluation budget");
				var target = original;
				if (complete && p + 1 == order.size()) {
					var capacity = LiquidVolumeInterface.measure(available).volume(); var moments = new Fraction[3];
					for (int a = 0; a < 3; a++) moments[a] = original.firstMoment(a).multiply(capacity).divide(original.volume());
					target = new LiquidExactPolygon.Measure(capacity, moments);
				}
				var work = new LiquidMomentInterface.Work(Math.min(budget.maximumFitEvaluations, budget.maximumEvaluations - evaluations));
				LiquidMomentInterface.Result fit;
				try { fit = LiquidMomentInterface.planar(available, target, source.normal, cell.coordinateBits, tolerance.centroidDistance, work); }
				finally { evaluations += work.evaluations; }
				var geometry = fit.interfaceGeometry().pieces; hints.put(source.control, fit.normal());
				phases.add(new Phase(source.control, geometry)); volumes.add(original.volume());
				for (var part : geometry) assigned.add(LiquidPhasePartition.Address.of(cell.open).cell(part));
				if (p + 1 < order.size() || !complete) available = LiquidGasPartition.build(cell.open, assigned, budget.maximumRegions);
			}
			if (!order.isEmpty()) {
				if (!complete) { phases.add(new Phase(0, available)); volumes.add(cell.open.geometry().measure().volume().subtract(total)); }
				planar = List.copyOf(phases);
				var projection = LiquidJointInterfaceVolume.project(cell.open, phases, volumes, budget.maximumRegions - retainedRegions);
				correction = projection.symmetricDifferenceBounds(); phases = new ArrayList<>(projection.phases().subList(0, order.size()));
				assigned.clear();
			}
			for (int p = 0; p < order.size(); p++) {
				var source = order.get(p); var original = LiquidVolumeInterface.measure(source.pieces); var geometry = phases.get(p).pieces;
				var represented = LiquidVolumeInterface.measure(geometry); var center = represented.centroidCoordinates(); var desired = original.centroidCoordinates();
				var squared = Fraction.ZERO; for (int a = 0; a < 3; a++) { var delta = center[a].subtract(desired[a]); squared = squared.add(delta.multiply(delta)); }
				double centroidError = squared.signum() == 0 ? 0 : Math.nextUp(Math.sqrt(Math.nextUp(squared.rounded())));
				var overlap = Fraction.ZERO;
				for (var old : source.pieces) for (var next : planar.get(p).pieces) {
					var common = LiquidExactPolygon.faces(old);
					for (var plane : LiquidExactPolygon.faces(next).stream().map(LiquidExactPolygon::coefficients).distinct().toList()) {
						common = LiquidExactPolygon.clipVolume(common, plane, 1); if (common.isEmpty()) break;
					}
					overlap = overlap.add(LiquidExactPolygon.volume(common));
				}
				var difference = original.volume().add(LiquidVolumeInterface.measure(planar.get(p).pieces).volume()).subtract(overlap.multiply(Fraction.of(2))).add(correction.get(p));
				if (difference.signum() < 0) throw new IllegalStateException("Interface reconstruction overlap exceeds source occupancy");
				var length = difference.multiply(Fraction.of(cell.open.width())).divide(original.volume());
				double occupancy = length.signum() == 0 ? 0 : Math.nextUp(length.rounded());
				if (centroidError > tolerance.centroidDistance || occupancy > tolerance.occupancyErrorLength) { accepted = false; break; }
				statistics.add(new PhaseStats(source.control, original, represented, difference, centroidError, occupancy));
				for (var part : geometry) assigned.add(LiquidPhasePartition.Address.of(cell.open).cell(part));
			}
			}
			if (accepted) {
				if (retainedRegions + assigned.size() > budget.maximumRegions) throw new IllegalStateException("Interface reconstruction exceeds its region budget");
				retainedRegions += assigned.size(); leaves.add(new Leaf(cell.open, phases));
				for (var observation : statistics) {
					var sum = totals.get(observation.control); sum.represented = sum.represented.add(observation.representedMeasure);
					sum.difference = sum.difference.add(observation.symmetricDifferenceVolumeBound);
					sum.centroidError = Math.max(sum.centroidError, observation.maximumCentroidError);
					sum.occupancyError = Math.max(sum.occupancyError, observation.maximumOccupancyErrorLength);
				}
				continue;
			}
			var children = LiquidGeometryRefinement.children(cell.open);
			if (leaves.size() + pending.size() + children.size() > budget.maximumLeaves)
				throw new IllegalStateException("Interface reconstruction exceeds its spatial leaf budget");
			for (var child : children) {
				var clippedSources = new ArrayList<Source>();
				for (var source : cell.sources) {
					var occupied = new ArrayList<LiquidPolyhedron>();
					for (var part : source.pieces) {
						var clipped = LiquidConvexRegion.of(part); for (var plane : child.geometry().planes()) clipped = clipped.clip(plane);
						if (clipped.hasVolume()) occupied.add(clipped.geometry());
					}
					clippedSources.add(new Source(source.control, occupied, hints.getOrDefault(source.control, source.normal)));
				}
				pending.add(new Pending(child, List.copyOf(clippedSources), cell.coordinateBits));
			}
			} catch (LiquidCoordinateResolutionRequired finer) {
				if (cell.coordinateBits >= 1074) throw finer;
				pending.add(new Pending(cell.open, cell.sources, Math.min(1074, 2 * cell.coordinateBits)));
			}
		}
		var statistics = new ArrayList<PhaseStats>();
		totals.forEach((control, sum) -> {
			if (!sum.source.volume().equals(sum.represented.volume())) throw new IllegalStateException("Local reconstruction lost exact control volume");
			statistics.add(new PhaseStats(control, sum.source, sum.represented, sum.difference, sum.centroidError, sum.occupancyError));
		});
		return new Result(leaves, statistics, evaluations);
	}
	/** Geometric ordering keeps material/control identifiers out of the reconstruction decision. */
	private static int compare(Source a, Source b, LiquidGeometryRefinement.Cell open) {
		if (a == b) return 0;
		var first = LiquidVolumeInterface.measure(a.pieces); var second = LiquidVolumeInterface.measure(b.pieces);
		int result = second.volume().subtract(first.volume()).signum(); if (result != 0) return result;
		var x = first.centroidCoordinates(); var y = second.centroidCoordinates();
		for (int i = 0; i < 3; i++) { result = x[i].subtract(y[i]).signum(); if (result != 0) return result; }
		return compareOccupancy(a.pieces, b.pieces, open);
	}
	/** Equal volume and centroid still describe distinct controls; dyadic occupancy resolves their geometric order. */
	private static int compareOccupancy(List<LiquidPolyhedron> a, List<LiquidPolyhedron> b, LiquidGeometryRefinement.Cell open) {
		if (open.level() == 30) throw new LiquidCoordinateResolutionRequired("Equal-moment phase ordering exceeds dyadic spatial resolution");
		LiquidGeometryRefinement.Cell shared = null; List<LiquidPolyhedron> first = null, second = null;
		for (var child : LiquidGeometryRefinement.children(open)) {
			var x = clipped(a, child.geometry()); var y = clipped(b, child.geometry());
			var v = LiquidVolumeInterface.measure(x).volume(); var w = LiquidVolumeInterface.measure(y).volume();
			int comparison = w.subtract(v).signum(); if (comparison != 0) return comparison;
			if (shared == null && v.signum() > 0) { shared = child; first = x; second = y; }
		}
		if (shared == null) throw new IllegalStateException("Positive equal-moment phases lost their dyadic support");
		return compareOccupancy(first, second, shared);
	}
	private static List<LiquidPolyhedron> clipped(List<LiquidPolyhedron> pieces, LiquidPolyhedron open) {
		var result = new ArrayList<LiquidPolyhedron>();
		for (var piece : pieces) {
			var region = LiquidConvexRegion.of(piece); for (var plane : open.planes()) region = region.clip(plane);
			if (region.hasVolume()) result.add(region.geometry());
		}
		return result;
	}
}
