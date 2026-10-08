package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.List;

/** Joint reconstruction retains each control volume inside the shared open dyadic cell. */
final class LiquidMomentReconstruction {
	/** Distances use input coordinates; occupancy length is cell width times symmetric difference / control volume. */
	record Tolerance(double centroidDistance, double occupancyErrorLength, int coordinateBits) {
		/** Coordinate precision grows from its minimum until the measured spatial error is certified. */
		Tolerance(double centroidDistance, double occupancyErrorLength) { this(centroidDistance, occupancyErrorLength, 1); }
		Tolerance {
			if (!(centroidDistance > 0) || !Double.isFinite(centroidDistance) || !(occupancyErrorLength > 0)
					|| !Double.isFinite(occupancyErrorLength) || coordinateBits < 1 || coordinateBits > 1074)
				throw new IllegalArgumentException("Invalid interface reconstruction tolerance");
		}
	}
	record Budget(int maximumLeaves, int maximumEvaluations, int maximumRegions) {
		Budget {
			if (maximumLeaves < 1 || maximumEvaluations < 1 || maximumRegions < 1)
				throw new IllegalArgumentException("Invalid interface reconstruction budget");
		}
	}
	record Source(int control, List<LiquidPolyhedron> pieces) {
		Source { if (control < 0) throw new IllegalArgumentException("Invalid reconstruction control"); pieces = List.copyOf(pieces); }
	}
	record Support(LiquidGeometryRefinement.Cell open, List<Source> sources) {
		Support { sources = List.copyOf(sources); }
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
	static Result reconstruct(LiquidGeometryRefinement.Cell open, List<Source> sources, Tolerance tolerance, Budget budget) {
		return reconstruct(open, sources, tolerance, budget, ignored -> true);
	}
	static Result reconstruct(LiquidGeometryRefinement.Cell open, List<Source> sources, Tolerance tolerance, Budget budget,
			java.util.function.Predicate<LiquidGeometryRefinement.Cell> velocityResolved) {
		return reconstruct(List.of(new Support(open, sources)), tolerance, budget, velocityResolved);
	}
	static Result reconstruct(List<Support> supports, Tolerance tolerance, Budget budget,
			java.util.function.Predicate<LiquidGeometryRefinement.Cell> velocityResolved) {
		if (supports == null || tolerance == null || budget == null || velocityResolved == null || supports.size() > budget.maximumLeaves)
			throw new IllegalArgumentException("Incomplete interface reconstruction support");
		new LiquidGeometryRemap(supports.stream().map(Support::open).toList());
		var totals = new java.util.LinkedHashMap<Integer, Totals>(); var pending = new java.util.ArrayDeque<Pending>();
		var capacity = Fraction.ZERO;
		for (var support : supports) {
		var open = support.open; var sources = support.sources;
		if (open == null || open.geometry().measure().signum() <= 0) throw new IllegalArgumentException("Empty interface reconstruction support");
		capacity = capacity.add(open.geometry().measure().volume());
		for (var face : LiquidExactPolygon.faces(open.geometry())) for (var vertex : face.vertices()) for (int axis = 0; axis < 3; axis++) {
			var coordinate = vertex.coordinates()[axis]; var lo = Fraction.of(open.minimum(axis));
			if (coordinate.compareTo(lo) < 0 || coordinate.compareTo(lo.add(Fraction.of(open.width()))) > 0)
				throw new IllegalArgumentException("Open interface geometry leaves its dyadic cell");
		}
		var address = LiquidPhasePartition.Address.of(open); var identifiers = new java.util.HashSet<Integer>();
		var pieces = new ArrayList<LiquidGeometryRefinement.Cell>();
		for (var source : sources) {
			if (!identifiers.add(source.control))
				throw new IllegalArgumentException("Repeated reconstruction control");
			var measured = LiquidVolumeInterface.measure(source.pieces);
			totals.merge(source.control, new Totals(measured), (a, b) -> new Totals(a.source.add(b.source)));
			for (var geometry : source.pieces) {
				if (geometry.measure().signum() <= 0) throw new IllegalArgumentException("Empty interface source fragment");
				var contained = LiquidExactPolygon.faces(geometry); for (var plane : LiquidExactPolygon.faces(open.geometry())) contained = LiquidExactPolygon.clipVolume(contained, plane.coefficients(), 1);
				if (!LiquidExactPolygon.volume(contained).equals(geometry.measure().volume())) throw new IllegalArgumentException("Interface source leaves its open cell");
				pieces.add(address.cell(geometry));
			}
		}
		LiquidPhasePartition.requireDisjoint(pieces);
		pending.add(new Pending(open, List.copyOf(sources), tolerance.coordinateBits));
		}
		int complement = totals.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
		var targets = new java.util.LinkedHashMap<Integer, Fraction>(); totals.forEach((control, sum) -> targets.put(control, sum.source.volume()));
		var empty = capacity.subtract(targets.values().stream().reduce(Fraction.ZERO, Fraction::add));
		if (empty.signum() < 0) throw new IllegalArgumentException("Interface sources exceed their open capacity");
		if (empty.signum() > 0) targets.put(complement, empty);
		var originals = new ArrayList<Support>(); var candidateStatistics = new ArrayList<List<PhaseStats>>();
		var leaves = new ArrayList<Leaf>(); var work = new LiquidSupportVolumeFit.Work(budget.maximumEvaluations); int retainedRegions = 0;
		while (!pending.isEmpty()) {
			var cell = pending.removeLast(); var available = List.of(cell.open.geometry());
			var assigned = new ArrayList<LiquidGeometryRefinement.Cell>(); var phases = new ArrayList<Phase>();
			var statistics = new ArrayList<PhaseStats>();
			var order = new ArrayList<>(cell.sources.stream().filter(source -> !source.pieces.isEmpty()).sorted((a, b) -> compare(a, b, cell.open)).toList());
			var total = Fraction.ZERO; for (var source : order) total = total.add(LiquidVolumeInterface.measure(source.pieces).volume());
			boolean complete = total.equals(cell.open.geometry().measure().volume());
			boolean accepted = velocityResolved.test(cell.open);
			List<Phase> candidateGeometry = List.of();
			try {
			if (accepted) {
			for (int p = 0; p < order.size(); p++) {
				if (!complete || p + 1 < order.size()) {
					boolean found = false; var availableVolume = LiquidVolumeInterface.measure(available).volume();
					for (int candidate = p; candidate < order.size(); candidate++) {
						var source = order.get(candidate); var original = LiquidVolumeInterface.measure(source.pieces);
						work.context = ", acceptedLeaves=" + leaves.size() + ", pendingLeaves=" + pending.size() + ", level=" + cell.open.level();
						var remainingVolume = availableVolume.subtract(original.volume());
						var scale = remainingVolume.signum() > 0 && remainingVolume.compareTo(original.volume()) < 0 ? remainingVolume : original.volume();
						var allowed = scale.multiply(Fraction.of(tolerance.occupancyErrorLength)).divide(Fraction.of(cell.open.width()));
						work.charge();
						var support = LiquidSupportInterface.reconstruct(cell.open, available, source.pieces, cell.coordinateBits);
						if (support.coordinateDifferenceVolumeBound().compareTo(allowed) > 0)
							throw new LiquidCoordinateResolutionRequired("Supporting plane coordinate error exceeds its spatial tolerance");
						var fitted = LiquidSupportVolumeFit.fit(cell.open, available, support, original.volume(), work);
						if (fitted.volumeErrorBound().compareTo(allowed) > 0)
							throw new LiquidCoordinateResolutionRequired("Supporting volume offset error exceeds its spatial tolerance");
						var geometry = fitted.pieces(); var represented = LiquidVolumeInterface.measure(geometry);
						if (represented.signum() == 0) continue;
						// Every intermediate allocation has another positive phase or complement left to represent.
						if (represented.volume().compareTo(availableVolume) >= 0) continue;
						var difference = occupiedDifference(source.pieces, geometry);
						if (difference.compareTo(allowed) > 0 || centroidDistance(represented, original) > tolerance.centroidDistance) continue;
						java.util.Collections.swap(order, p, candidate); phases.add(new Phase(source.control, geometry));
						for (var part : geometry) assigned.add(LiquidPhasePartition.Address.of(cell.open).cell(part));
						available = LiquidGasPartition.build(cell.open, assigned, budget.maximumRegions); found = true; break;
					}
					if (!found) { accepted = false; break; }
					continue;
				}
				var source = order.get(p); phases.add(new Phase(source.control, available));
				for (var part : available) assigned.add(LiquidPhasePartition.Address.of(cell.open).cell(part));
			}
			if (accepted && !order.isEmpty()) {
				if (!complete) phases.add(new Phase(complement, available));
				candidateGeometry = List.copyOf(phases);
				assigned.clear();
			} else if (accepted && !complete) {
				phases.add(new Phase(complement, available)); candidateGeometry = List.copyOf(phases);
			}
			for (int p = 0; accepted && p < order.size(); p++) {
				var source = order.get(p); var original = LiquidVolumeInterface.measure(source.pieces); var geometry = phases.get(p).pieces;
				var represented = LiquidVolumeInterface.measure(geometry);
				if (represented.signum() <= 0) throw new LiquidCoordinateResolutionRequired("Joint interface coordinates lost a positive phase remainder");
				var center = represented.centroidCoordinates(); var desired = original.centroidCoordinates();
				var squared = Fraction.ZERO; for (int a = 0; a < 3; a++) { var delta = center[a].subtract(desired[a]); squared = squared.add(delta.multiply(delta)); }
				double centroidError = squared.signum() == 0 ? 0 : Math.nextUp(Math.sqrt(Math.nextUp(squared.rounded())));
				var difference = occupiedDifference(source.pieces, candidateGeometry.get(p).pieces);
				if (difference.signum() < 0) throw new IllegalStateException("Interface reconstruction overlap exceeds source occupancy");
				var length = difference.multiply(Fraction.of(cell.open.width())).divide(original.volume());
				double occupancy = length.signum() == 0 ? 0 : Math.nextUp(length.rounded());
				if (centroidError > tolerance.centroidDistance || occupancy > tolerance.occupancyErrorLength) { accepted = false; break; }
				statistics.add(new PhaseStats(source.control, original, represented, difference, centroidError, occupancy));
				for (var part : geometry) assigned.add(LiquidPhasePartition.Address.of(cell.open).cell(part));
			}
			}
			if (accepted) {
				int count = phases.stream().mapToInt(phase -> phase.pieces.size()).sum();
				if (retainedRegions + count > budget.maximumRegions) throw new IllegalStateException("Interface reconstruction exceeds its region budget");
				retainedRegions += count; leaves.add(new Leaf(cell.open, phases));
				originals.add(new Support(cell.open, cell.sources)); candidateStatistics.add(List.copyOf(statistics));
				continue;
			}
			var children = LiquidGeometryRefinement.children(cell.open);
			if (leaves.size() + pending.size() + children.size() > budget.maximumLeaves)
				throw new IllegalStateException("Interface reconstruction exceeds its spatial leaf budget");
			for (var child : children) {
				var clippedSources = new ArrayList<Source>();
				for (var source : cell.sources) {
					var occupied = clipped(source.pieces, child.geometry());
					clippedSources.add(new Source(source.control, occupied));
				}
				pending.add(new Pending(child, List.copyOf(clippedSources), cell.coordinateBits));
			}
			} catch (LiquidCoordinateResolutionRequired finer) {
				if (cell.coordinateBits >= 1074) throw finer;
				pending.add(new Pending(cell.open, cell.sources, Math.min(1074, 2 * cell.coordinateBits)));
			}
		}
		LiquidJointInterfaceVolume.PartitionResult corrected;
		var referenceVolumes = new ArrayList<java.util.Map<Integer, Fraction>>(); var quantum = new ArrayList<Fraction>();
		for (int l = 0; l < leaves.size(); l++) {
			var reference = new java.util.LinkedHashMap<Integer, Fraction>(); var occupied = Fraction.ZERO;
			for (var source : originals.get(l).sources) {
				var value = LiquidVolumeInterface.measure(source.pieces).volume(); if (value.signum() == 0) continue;
				reference.put(source.control, value); occupied = occupied.add(value);
			}
			var remaining = leaves.get(l).open.geometry().measure().volume().subtract(occupied);
			if (remaining.signum() > 0) reference.put(complement, remaining);
			var ordered = new java.util.LinkedHashMap<Integer, Fraction>(); var localQuantum = leaves.get(l).open.geometry().measure().volume();
			for (var phase : leaves.get(l).phases) {
				var value = reference.get(phase.control); ordered.put(phase.control, value);
				var allowed = value.multiply(Fraction.of(tolerance.occupancyErrorLength)).divide(Fraction.of(leaves.get(l).open.width()));
				if (allowed.compareTo(localQuantum) < 0) localQuantum = allowed;
			}
			referenceVolumes.add(ordered); quantum.add(localQuantum);
		}
		try {
			corrected = LiquidFinitePhaseVolumeProjection.project(leaves, targets, referenceVolumes, quantum, budget.maximumRegions, work);
		}
		catch (LiquidInterfaceResolutionRequired finer) {
			var selected = new java.util.HashSet<Integer>(); for (int cell : finer.cells()) selected.add(cell);
			var refined = new ArrayList<Support>();
			for (int l = 0; l < originals.size(); l++) {
				var original = originals.get(l); if (!selected.contains(l)) { refined.add(original); continue; }
				for (var child : LiquidGeometryRefinement.children(original.open)) {
					var sources = new ArrayList<Source>();
					for (var source : original.sources) sources.add(new Source(source.control, clipped(source.pieces, child.geometry())));
					refined.add(new Support(child, sources));
				}
			}
			return retry(refined, tolerance, budget, velocityResolved, work.evaluations);
		}
		catch (LiquidCoordinateResolutionRequired finer) {
			if (tolerance.coordinateBits == 1074) throw finer;
			return retry(supports, new Tolerance(tolerance.centroidDistance, tolerance.occupancyErrorLength, Math.min(1074, 2 * tolerance.coordinateBits)), budget, velocityResolved, work.evaluations);
		}
		var refined = new ArrayList<Support>(); boolean accepted = true;
		for (int l = 0; l < corrected.leaves().size(); l++) {
			var leaf = corrected.leaves().get(l); boolean resolved = true;
			var byControl = new java.util.HashMap<Integer, PhaseStats>(); for (var observation : candidateStatistics.get(l)) byControl.put(observation.control, observation);
			for (int p = 0; p < leaf.phases.size(); p++) {
				var phase = leaf.phases.get(p); if (phase.control == complement && empty.signum() > 0) continue;
				var original = byControl.get(phase.control); var represented = LiquidVolumeInterface.measure(phase.pieces);
				var center = represented.centroidCoordinates(); var desired = original.sourceMeasure.centroidCoordinates(); var squared = Fraction.ZERO;
				for (int a = 0; a < 3; a++) { var delta = center[a].subtract(desired[a]); squared = squared.add(delta.multiply(delta)); }
				double centroid = squared.signum() == 0 ? 0 : Math.nextUp(Math.sqrt(Math.nextUp(squared.rounded())));
				var difference = original.symmetricDifferenceVolumeBound.add(corrected.symmetricDifferenceBounds().get(l).get(p));
				var length = difference.multiply(Fraction.of(leaf.open.width())).divide(original.sourceMeasure.volume());
				double occupancy = length.signum() == 0 ? 0 : Math.nextUp(length.rounded());
				resolved &= centroid <= tolerance.centroidDistance && occupancy <= tolerance.occupancyErrorLength;
				var sum = totals.get(phase.control); sum.represented = sum.represented.add(represented); sum.difference = sum.difference.add(difference);
				sum.centroidError = Math.max(sum.centroidError, centroid); sum.occupancyError = Math.max(sum.occupancyError, occupancy);
			}
			if (resolved) refined.add(originals.get(l));
			else {
				accepted = false; var original = originals.get(l);
				for (var child : LiquidGeometryRefinement.children(original.open)) {
					var sources = new ArrayList<Source>(); for (var source : original.sources) sources.add(new Source(source.control, clipped(source.pieces, child.geometry())));
					refined.add(new Support(child, sources));
				}
			}
		}
		if (!accepted) return retry(refined, tolerance, budget, velocityResolved, work.evaluations);
		var statistics = new ArrayList<PhaseStats>();
		totals.forEach((control, sum) -> {
			if (!sum.source.volume().equals(sum.represented.volume())) throw new IllegalStateException("Local reconstruction lost exact control volume");
			statistics.add(new PhaseStats(control, sum.source, sum.represented, sum.difference, sum.centroidError, sum.occupancyError));
		});
		var visible = new ArrayList<Leaf>();
		for (var leaf : corrected.leaves()) visible.add(new Leaf(leaf.open, leaf.phases.stream().filter(phase -> phase.control != complement || empty.signum() == 0).toList()));
		return new Result(visible, statistics, work.evaluations);
	}
	private static Result retry(List<Support> supports, Tolerance tolerance, Budget budget, java.util.function.Predicate<LiquidGeometryRefinement.Cell> velocityResolved, int evaluations) {
		if (supports.size() > budget.maximumLeaves) throw new IllegalStateException("Interface reconstruction exceeds its spatial leaf budget");
		if (evaluations >= budget.maximumEvaluations) throw new IllegalStateException("Interface reconstruction exceeds its evaluation budget");
		var result = reconstruct(supports, tolerance, new Budget(budget.maximumLeaves, budget.maximumEvaluations - evaluations, budget.maximumRegions), velocityResolved);
		return new Result(result.leaves, result.phases, evaluations + result.evaluations);
	}
	/** Exact first moments certify spatial distance independently of volume coordinate precision. */
	private static double centroidDistance(LiquidExactPolygon.Measure a, LiquidExactPolygon.Measure b) {
		var x = a.centroidCoordinates(); var y = b.centroidCoordinates(); var squared = Fraction.ZERO;
		for (int axis = 0; axis < 3; axis++) { var delta = x[axis].subtract(y[axis]); squared = squared.add(delta.multiply(delta)); }
		return squared.signum() == 0 ? 0 : Math.nextUp(Math.sqrt(Math.nextUp(squared.rounded())));
	}
	private static Fraction occupiedDifference(List<LiquidPolyhedron> original, List<LiquidPolyhedron> represented) {
		var overlap = Fraction.ZERO;
		for (var old : original) for (var next : represented) {
			var common = LiquidExactPolygon.faces(old);
			for (var plane : LiquidExactPolygon.faces(next).stream().map(LiquidExactPolygon::coefficients).distinct().toList()) {
				common = LiquidExactPolygon.clipVolume(common, plane, 1); if (common.isEmpty()) break;
			}
			overlap = overlap.add(LiquidExactPolygon.volume(common));
		}
		return LiquidVolumeInterface.measure(original).volume().add(LiquidVolumeInterface.measure(represented).volume()).subtract(overlap.multiply(Fraction.of(2)));
	}
	/** Geometric ordering keeps material/control identifiers out of the reconstruction decision. */
	private static int compare(Source a, Source b, LiquidGeometryRefinement.Cell open) {
		if (a == b) return 0;
		var first = LiquidVolumeInterface.measure(a.pieces); var second = LiquidVolumeInterface.measure(b.pieces);
		int result = second.volume().compareTo(first.volume()); if (result != 0) return result;
		var x = first.centroidCoordinates(); var y = second.centroidCoordinates();
		for (int i = 0; i < 3; i++) { result = x[i].compareTo(y[i]); if (result != 0) return result; }
		return compareOccupancy(a.pieces, b.pieces, open);
	}
	/** Equal volume and centroid still describe distinct controls; dyadic occupancy resolves their geometric order. */
	private static int compareOccupancy(List<LiquidPolyhedron> a, List<LiquidPolyhedron> b, LiquidGeometryRefinement.Cell open) {
		if (open.level() == 30) throw new LiquidCoordinateResolutionRequired("Equal-moment phase ordering exceeds dyadic spatial resolution");
		LiquidGeometryRefinement.Cell shared = null; List<LiquidPolyhedron> first = null, second = null;
		for (var child : LiquidGeometryRefinement.children(open)) {
			var x = clipped(a, child.geometry()); var y = clipped(b, child.geometry());
			var v = LiquidVolumeInterface.measure(x).volume(); var w = LiquidVolumeInterface.measure(y).volume();
			int comparison = w.compareTo(v); if (comparison != 0) return comparison;
			if (shared == null && v.signum() > 0) { shared = child; first = x; second = y; }
		}
		if (shared == null) throw new IllegalStateException("Positive equal-moment phases lost their dyadic support");
		return compareOccupancy(first, second, shared);
	}
	private static List<LiquidPolyhedron> clipped(List<LiquidPolyhedron> pieces, LiquidPolyhedron open) {
		var result = new ArrayList<LiquidPolyhedron>();
		for (var piece : pieces) {
			var faces = LiquidExactPolygon.faces(piece); for (var plane : LiquidExactPolygon.faces(open)) { faces = LiquidExactPolygon.clipVolume(faces, plane.coefficients(), 1); if (faces.isEmpty()) break; }
			if (!faces.isEmpty()) result.add(faces == LiquidExactPolygon.faces(piece) ? piece : LiquidPolyhedron.restoredExact(faces));
		}
		return result;
	}
}
