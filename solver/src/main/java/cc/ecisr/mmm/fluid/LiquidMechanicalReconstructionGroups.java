package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Agglomerates adjacent mechanical fields within each physical phase's exact integrated projection budget. */
final class LiquidMechanicalReconstructionGroups {
	private record Patch(int owner, LiquidExactPolygon surface) { }
	private record Edge(int first, int second) implements Comparable<Edge> {
		static Edge of(int a, int b) { return new Edge(Math.min(a, b), Math.max(a, b)); }
		@Override public int compareTo(Edge other) { int result = Integer.compare(first, other.first); return result == 0 ? Integer.compare(second, other.second) : result; }
	}
	private record Moments(Fraction volume, Fraction mass, List<Fraction> momentum, Fraction kinetic) {
		static Moments of(LiquidGeometryVelocityProjection.Value field) { return new Moments(field.volume(), field.mass(), field.momentum(), field.originalKinetic()); }
		Moments add(Moments other) {
			var p = new ArrayList<Fraction>(); for (int a = 0; a < 3; a++) p.add(momentum.get(a).add(other.momentum.get(a)));
			return new Moments(volume.add(other.volume), mass.add(other.mass), List.copyOf(p), kinetic.add(other.kinetic));
		}
		Fraction loss() { var projected = Fraction.ZERO; for (var p : momentum) projected = projected.add(p.multiply(p).divide(mass.multiply(Fraction.of(2)))); return kinetic.subtract(projected); }
	}
	final int[] owners;
	final Map<Integer, Fraction> squaredLimits;
	final int evaluations;
	LiquidMechanicalReconstructionGroups(FluidDomain domain, double relativeAccuracy, LiquidMomentReconstruction.Budget budget) {
		var tolerance = new LiquidGeometryVelocityProjection.RmsTolerance(0, relativeAccuracy);
		if (relativeAccuracy == 0) throw new IllegalArgumentException("Relative mechanical accuracy must be positive");
		var stock = new LiquidReconstructionInventories(domain); var state = domain.liquidGeometryState;
		int[] previous = state.copyFragmentOwners();
		if (!Arrays.equals(previous, state.copyControlCells())) throw new IllegalArgumentException("Mechanical regrouping requires independent control inventories");
		var cells = LiquidGeometryStateMapper.restore(state); var physical = new ArrayList<LiquidGeometryVelocityProjection.Source>();
		if (cells.size() > budget.maximumRegions()) throw new IllegalStateException("Mechanical regrouping exceeds its positive-region budget");
		var mechanical = new ArrayList<LiquidGeometryVelocityProjection.Source>();
		for (int f = 0; f < cells.size(); f++) {
			int owner = previous[f]; var u = state.cells().get(f).copyVelocity(); var velocity = new LiquidPolyhedron.Point(u[0], u[1], u[2]);
			physical.add(new LiquidGeometryVelocityProjection.Source(stock.phases[owner], owner, stock.densities[owner], cells.get(f).geometry(), velocity));
			mechanical.add(new LiquidGeometryVelocityProjection.Source(owner, owner, stock.densities[owner], cells.get(f).geometry(), velocity));
		}
		var physicalFields = LiquidGeometryVelocityProjection.summarize(physical); var limits = tolerance.squaredLimits(physical);
		var remaining = new HashMap<Integer, Fraction>();
		for (var entry : physicalFields.entrySet()) remaining.put(entry.getKey(), entry.getValue().mass().multiply(limits.get(entry.getKey())).divide(Fraction.of(2)));
		var moments = new HashMap<Integer, Moments>(); LiquidGeometryVelocityProjection.summarize(mechanical).forEach((owner, field) -> moments.put(owner, Moments.of(field)));
		owners = new int[cells.size()]; for (int f = 0; f < owners.length; f++) owners[f] = f;
		var work = new LiquidSupportVolumeFit.Work(budget.maximumEvaluations()); var edges = adjacent(cells, previous, stock.phases, work);
		for (;;) {
			Edge selected = null; Moments merged = null; Fraction least = null;
			var active = new TreeSet<Edge>(); for (var edge : edges) { int a = root(edge.first), b = root(edge.second); if (a != b) active.add(Edge.of(a, b)); }
			for (var edge : active) {
				work.context = ", mechanicalGroups=" + moments.size(); work.charge();
				var a = moments.get(edge.first); var b = moments.get(edge.second); var candidate = a.add(b);
				var defect = candidate.loss().subtract(a.loss()).subtract(b.loss());
				if (defect.signum() < 0) throw new IllegalStateException("Mechanical agglomeration increased its exact resolved kinetic energy");
				if (defect.compareTo(remaining.get(stock.phases[edge.first])) > 0) continue;
				if (least != null && defect.compareTo(least) >= 0) continue;
				selected = edge; merged = candidate; least = defect;
			}
			if (selected == null) break;
			int phase = stock.phases[selected.first]; remaining.put(phase, remaining.get(phase).subtract(least));
			owners[selected.second] = selected.first; moments.put(selected.first, merged); moments.remove(selected.second);
		}
		var squared = new LinkedHashMap<Integer, Fraction>();
		for (var entry : moments.entrySet()) squared.put(entry.getKey(), entry.getValue().loss().multiply(Fraction.of(2)).divide(entry.getValue().mass));
		for (int f = 0; f < owners.length; f++) owners[f] = root(previous[f]);
		squaredLimits = Map.copyOf(squared); evaluations = work.evaluations;
	}
	private int root(int owner) { while (owners[owner] != owner) { owners[owner] = owners[owners[owner]]; owner = owners[owner]; } return owner; }
	private static TreeSet<Edge> adjacent(List<LiquidGeometryRefinement.Cell> cells, int[] owners, int[] phases, LiquidSupportVolumeFit.Work work) {
		var surfaces = new HashMap<LiquidGeometryPlane.Key, ArrayList<Patch>>(); var result = new TreeSet<Edge>();
		for (int f = 0; f < cells.size(); f++) for (var surface : LiquidExactPolygon.faces(cells.get(f).geometry())) {
			var candidates = surfaces.computeIfAbsent(surface.plane().key(), ignored -> new ArrayList<>());
			for (var candidate : candidates) if (owners[f] != candidate.owner && phases[owners[f]] == phases[candidate.owner]
					&& surface.plane().positive() != candidate.surface.plane().positive()) {
				var edge = Edge.of(owners[f], candidate.owner); if (result.contains(edge)) continue;
				work.context = ", mechanicalFaceIntersection=" + f; work.charge();
				if (surface.intersection(candidate.surface).hasArea()) result.add(edge);
			}
			candidates.add(new Patch(owners[f], surface));
		}
		return result;
	}
}
