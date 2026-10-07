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
	static Result reconstruct(LiquidGeometryRefinement.Cell open, List<LiquidPolyhedron> source, LiquidPolyhedron.Point normal,
			Tolerance tolerance, Budget budget) {
		return reconstruct(open, List.of(new Source(0, source, normal)), tolerance, budget);
	}
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
			if (coordinate.subtract(lo).signum() < 0 || coordinate.subtract(lo.add(Fraction.of(open.width()))).signum() > 0)
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
		var originals = new ArrayList<Support>(); var planarStatistics = new ArrayList<List<PhaseStats>>();
		var leaves = new ArrayList<Leaf>(); int evaluations = 0, retainedRegions = 0;
		while (!pending.isEmpty()) {
			var cell = pending.removeLast(); var available = List.of(cell.open.geometry());
			var assigned = new ArrayList<LiquidGeometryRefinement.Cell>(); var phases = new ArrayList<Phase>();
			var statistics = new ArrayList<PhaseStats>(); var hints = new java.util.HashMap<Integer, LiquidPolyhedron.Point>();
			var order = new ArrayList<>(cell.sources.stream().filter(source -> !source.pieces.isEmpty()).sorted((a, b) -> compare(a, b, cell.open)).toList());
			var total = Fraction.ZERO; for (var source : order) total = total.add(LiquidVolumeInterface.measure(source.pieces).volume());
			boolean complete = total.equals(cell.open.geometry().measure().volume());
			boolean accepted = velocityResolved.test(cell.open);
			List<Phase> planar = List.of();
			try {
			if (accepted) {
			for (int p = 0; p < order.size(); p++) {
				if (!complete || p + 1 < order.size()) {
					boolean found = false; var availableVolume = LiquidVolumeInterface.measure(available).volume();
					for (int candidate = p; candidate < order.size(); candidate++) {
						var source = order.get(candidate); var original = LiquidVolumeInterface.measure(source.pieces);
						if (evaluations >= budget.maximumEvaluations) throw new IllegalStateException("Interface reconstruction exceeds its evaluation budget");
						var remainingVolume = availableVolume.subtract(original.volume());
						var scale = remainingVolume.signum() > 0 && remainingVolume.subtract(original.volume()).signum() < 0 ? remainingVolume : original.volume();
						var allowed = scale.multiply(Fraction.of(tolerance.occupancyErrorLength)).divide(Fraction.of(cell.open.width()));
						var work = new LiquidMomentInterface.Work(Math.min(budget.maximumFitEvaluations, budget.maximumEvaluations - evaluations));
						LiquidMomentInterface.Result fit;
						try { fit = LiquidMomentInterface.planar(available, fittingTarget(original, cell.coordinateBits), source.normal, cell.coordinateBits, tolerance.centroidDistance, work, representation -> {
							if (original.volume().subtract(representation.measure.volume()).absolute().subtract(allowed).signum() > 0)
								throw new LiquidCoordinateResolutionRequired("Joint cut volume error exceeds the remaining phase's spatial tolerance");
							return occupiedDifference(source.pieces, representation.pieces).subtract(allowed).signum() <= 0;
						}); }
						finally { evaluations += work.evaluations; }
						hints.put(source.control, fit.normal());
						var geometry = fit.interfaceGeometry().pieces; var represented = fit.interfaceGeometry().measure;
						if (original.volume().subtract(represented.volume()).absolute().subtract(allowed).signum() > 0)
							throw new LiquidCoordinateResolutionRequired("Joint cut volume error exceeds the remaining phase's spatial tolerance");
						var difference = occupiedDifference(source.pieces, geometry);
						if (difference.subtract(allowed).signum() > 0 || fit.interfaceGeometry().centroidDistance(original) > tolerance.centroidDistance) continue;
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
				planar = List.copyOf(phases);
				assigned.clear();
			} else if (accepted && !complete) {
				phases.add(new Phase(complement, available)); planar = List.copyOf(phases);
			}
			for (int p = 0; accepted && p < order.size(); p++) {
				var source = order.get(p); var original = LiquidVolumeInterface.measure(source.pieces); var geometry = phases.get(p).pieces;
				var represented = LiquidVolumeInterface.measure(geometry); var center = represented.centroidCoordinates(); var desired = original.centroidCoordinates();
				var squared = Fraction.ZERO; for (int a = 0; a < 3; a++) { var delta = center[a].subtract(desired[a]); squared = squared.add(delta.multiply(delta)); }
				double centroidError = squared.signum() == 0 ? 0 : Math.nextUp(Math.sqrt(Math.nextUp(squared.rounded())));
				var difference = occupiedDifference(source.pieces, planar.get(p).pieces);
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
				originals.add(new Support(cell.open, cell.sources)); planarStatistics.add(List.copyOf(statistics));
				continue;
			}
			var children = LiquidGeometryRefinement.children(cell.open);
			if (leaves.size() + pending.size() + children.size() > budget.maximumLeaves)
				throw new IllegalStateException("Interface reconstruction exceeds its spatial leaf budget");
			for (var child : children) {
				var clippedSources = new ArrayList<Source>();
				for (var source : cell.sources) {
					var occupied = clipped(source.pieces, child.geometry());
					clippedSources.add(new Source(source.control, occupied, hints.getOrDefault(source.control, source.normal)));
				}
				pending.add(new Pending(child, List.copyOf(clippedSources), cell.coordinateBits));
			}
			} catch (LiquidCoordinateResolutionRequired finer) {
				if (cell.coordinateBits >= 1074) throw finer;
				pending.add(new Pending(cell.open, cell.sources, Math.min(1074, 2 * cell.coordinateBits)));
			}
		}
		LiquidJointInterfaceVolume.PartitionResult corrected;
		try { corrected = LiquidJointInterfaceVolume.project(leaves, targets, budget.maximumRegions); }
		catch (LiquidCoordinateResolutionRequired finer) {
			if (tolerance.coordinateBits == 1074) throw finer;
			return retry(supports, new Tolerance(tolerance.centroidDistance, tolerance.occupancyErrorLength, Math.min(1074, 2 * tolerance.coordinateBits)), budget, velocityResolved, evaluations);
		}
		var refined = new ArrayList<Support>(); boolean accepted = true;
		for (int l = 0; l < corrected.leaves().size(); l++) {
			var leaf = corrected.leaves().get(l); boolean resolved = true;
			var byControl = new java.util.HashMap<Integer, PhaseStats>(); for (var observation : planarStatistics.get(l)) byControl.put(observation.control, observation);
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
					var sources = new ArrayList<Source>(); for (var source : original.sources) sources.add(new Source(source.control, clipped(source.pieces, child.geometry()), source.normal));
					refined.add(new Support(child, sources));
				}
			}
		}
		if (!accepted) return retry(refined, tolerance, budget, velocityResolved, evaluations);
		var statistics = new ArrayList<PhaseStats>();
		totals.forEach((control, sum) -> {
			if (!sum.source.volume().equals(sum.represented.volume())) throw new IllegalStateException("Local reconstruction lost exact control volume");
			statistics.add(new PhaseStats(control, sum.source, sum.represented, sum.difference, sum.centroidError, sum.occupancyError));
		});
		var visible = new ArrayList<Leaf>();
		for (var leaf : corrected.leaves()) visible.add(new Leaf(leaf.open, leaf.phases.stream().filter(phase -> phase.control != complement || empty.signum() == 0).toList()));
		return new Result(visible, statistics, evaluations);
	}
	private static Result retry(List<Support> supports, Tolerance tolerance, Budget budget, java.util.function.Predicate<LiquidGeometryRefinement.Cell> velocityResolved, int evaluations) {
		if (supports.size() > budget.maximumLeaves) throw new IllegalStateException("Interface reconstruction exceeds its spatial leaf budget");
		if (evaluations >= budget.maximumEvaluations) throw new IllegalStateException("Interface reconstruction exceeds its evaluation budget");
		var result = reconstruct(supports, tolerance, new Budget(budget.maximumLeaves, budget.maximumEvaluations - evaluations, budget.maximumFitEvaluations, budget.maximumRegions), velocityResolved);
		return new Result(result.leaves, result.phases, evaluations + result.evaluations);
	}
	/** A lower dyadic fitting volume cannot consume the next phase; final errors still use the original exact measure. */
	private static LiquidExactPolygon.Measure fittingTarget(LiquidExactPolygon.Measure original, int bits) {
		var volume = dyadicLower(original.volume(), bits); var center = original.centroidCoordinates(); var moment = new Fraction[3];
		for (int a = 0; a < 3; a++) moment[a] = volume.multiply(dyadicLower(center[a], bits));
		return new LiquidExactPolygon.Measure(volume, moment);
	}
	private static Fraction dyadicLower(Fraction v, int bits) {
		if (v.signum() == 0) return Fraction.ZERO;
		if (v.signum() < 0) throw new IllegalArgumentException("Negative normalized reconstruction coordinate");
		int exponent = v.numerator().bitLength() - v.denominator().bitLength();
		if ((exponent >= 0 ? v.numerator().compareTo(v.denominator().shiftLeft(exponent)) : v.numerator().shiftLeft(-exponent).compareTo(v.denominator())) < 0) exponent--;
		int shift = bits - 1 - exponent;
		var dividend = shift >= 0 ? v.numerator().shiftLeft(shift) : v.numerator();
		var divisor = shift >= 0 ? v.denominator() : v.denominator().shiftLeft(-shift);
		var significand = dividend.divide(divisor);
		return shift >= 0 ? new Fraction(significand, java.math.BigInteger.ONE.shiftLeft(shift)) : new Fraction(significand.shiftLeft(-shift), java.math.BigInteger.ONE);
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
			var faces = LiquidExactPolygon.faces(piece); for (var plane : LiquidExactPolygon.faces(open)) { faces = LiquidExactPolygon.clipVolume(faces, plane.coefficients(), 1); if (faces.isEmpty()) break; }
			if (!faces.isEmpty()) result.add(faces == LiquidExactPolygon.faces(piece) ? piece : LiquidPolyhedron.restoredExact(faces));
		}
		return result;
	}
}
