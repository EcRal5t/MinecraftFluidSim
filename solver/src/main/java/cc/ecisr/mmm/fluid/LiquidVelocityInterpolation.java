package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import cc.ecisr.mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import static cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.*;

/** Compact partition of unity combines fixed cell-centered affine fits with continuous spatial weights. */
final class LiquidVelocityInterpolation {
	static final class Node {
		final Map<Integer, Fraction> weights;
		final boolean affineComplete;
		private final Map<Integer, Double> errors;
		private final int[] cells;
		private final BigInteger[] numerator;
		private final BigInteger denominator;
		Node(Map<Integer, Fraction> weights, Map<Integer, Double> errors, boolean affineComplete) {
			this.weights = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(weights));
			this.errors = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(errors)); this.affineComplete = affineComplete;
			cells = weights.keySet().stream().mapToInt(Integer::intValue).toArray(); numerator = new BigInteger[cells.length];
			var common = BigInteger.ONE;
			for (var weight : weights.values()) common = common.divide(LiquidIntegerArithmetic.gcd(common, weight.denominator())).multiply(weight.denominator());
			denominator = common;
			for (int i = 0; i < cells.length; i++) { var weight = weights.get(cells[i]); numerator[i] = weight.numerator().multiply(common.divide(weight.denominator())); }
		}
		Fraction[] velocity(SignedDyadic[] mean) {
			var result = new Fraction[3];
			for (int a = 0; a < 3; a++) {
				int exponent = 0; for (int c : cells) if (mean[3 * c + a].signum() != 0) exponent = Math.min(exponent, mean[3 * c + a].exponent());
				var sum = BigInteger.ZERO;
				for (int i = 0; i < cells.length; i++) { var value = mean[3 * cells[i] + a]; if (value.signum() != 0) sum = sum.add(numerator[i].multiply(value.significand()).shiftLeft(value.exponent() - exponent)); }
				result[a] = new Fraction(sum, denominator.shiftLeft(-exponent));
			}
			return result;
		}
		double error(double[] magnitude) {
			double result = 0;
			for (var entry : errors.entrySet()) if (entry.getValue() != 0 && magnitude[entry.getKey()] != 0)
				result = up(result + up(entry.getValue() * magnitude[entry.getKey()]));
			if (!Double.isFinite(result)) throw new IllegalStateException("Continuous interpolation has no finite velocity error bound");
			return result;
		}
	}
	private record Support(int cell, int component, List<Integer> patch, LiquidAffineFit fit, Vertex center, Fraction radiusSquared) { }
	private record Distance(BigInteger numerator, BigInteger denominator) {
		Fraction fraction() { return new Fraction(numerator, denominator); }
		Value interiorWeight(Fraction radius) {
			var total = radius.numerator().multiply(denominator);
			var remaining = total.subtract(numerator.multiply(radius.denominator()));
			if (remaining.signum() <= 0) return null;
			var base = Value.ratio(remaining, total); var square = base.multiply(base); return square.multiply(square);
		}
	}
	private final Index index;
	private final int[] components;
	private final List<Vertex> centers;
	LiquidVelocityInterpolation(LiquidControlVolumeMesh mesh, int[] liquid) {
		centers = mesh.cells.stream().map(cell -> LiquidExactPolygon.vertex(cell.geometry().centroidCoordinates())).toList();
		components = components(mesh, liquid); var ranks = ranks(mesh, components); var supports = new ArrayList<Support>();
		var fits = new java.util.HashMap<List<Integer>, LiquidAffineFit>();
		var spans = new java.util.HashMap<List<Integer>, LiquidAffineFit.Basis>();
		for (int c = 0; c < liquid.length; c++) if (liquid[c] >= 0) {
			var patch = patch(mesh, liquid, c, ranks.get(components[c]), spans);
			var fit = fits.computeIfAbsent(patch, cells -> new LiquidAffineFit(cells.stream().map(k -> mesh.cells.get(k).geometry().centroidCoordinates()).toList(),
					cells.stream().map(k -> mesh.cells.get(k).geometry().measure().volume()).toList(), spans.get(cells)));
			var center = centers.get(c); var radius = Fraction.ZERO;
			for (int f : mesh.cellFaces[c]) for (var point : mesh.exactPolygons.get(f).vertices()) {
				var square = distanceSquared(point, center).fraction(); if (square.compareTo(radius) > 0) radius = square;
			}
			// Twice the enclosing radius gives every incident node strictly positive support.
			supports.add(new Support(c, components[c], patch, fit, center, radius.multiply(Fraction.of(4))));
		}
		index = Index.of(supports, 0);
	}
	Node at(Vertex point, int incidentCell) {
		var p = point.rounded(); var nearby = new ArrayList<Support>(); Index.collect(index, new double[]{p.x(), p.y(), p.z()}, nearby);
		nearby.sort(java.util.Comparator.comparingInt(Support::cell));
		var active = new ArrayList<Support>(); var raw = new ArrayList<Value>(); var represented = new ArrayList<Fraction>();
		var total = ZERO; var representedTotal = Fraction.ZERO;
		for (var support : nearby) if (support.component == components[incidentCell]) {
			var weight = distanceSquared(point, support.center).interiorWeight(support.radiusSquared); if (weight == null) continue;
			var observed = Fraction.of(weight.value());
			active.add(support); raw.add(weight); represented.add(observed); total = total.add(weight); representedTotal = representedTotal.add(observed);
		}
		if (representedTotal.signum() <= 0) throw new IllegalStateException("Continuous interpolation lost its incident spatial support");
		var observed = new LinkedHashMap<Integer, Value>(); boolean complete = true;
		for (int k = 0; k < active.size(); k++) {
			var support = active.get(k); var blend = represented.get(k).divide(representedTotal); var observedBlend = Value.of(blend);
			double blendError = observedBlend.subtract(raw.get(k).divide(total)).magnitude();
			var localWeights = support.fit.weights(point); complete &= support.fit.contains(point);
			for (int i = 0; i < support.patch.size(); i++) {
				int c = support.patch.get(i); var local = localWeights[i];
				var contribution = local.multiply(new Value(observedBlend.value(), up(observedBlend.error() + blendError)));
				observed.merge(c, contribution, Value::add);
			}
		}
		var anchor = active.getFirst();
		var coordinates = LiquidAffineWeightCoordinates.complete(point, anchor.fit.basis, anchor.patch, centers, observed);
		return new Node(coordinates.weights(), coordinates.errors(), complete);
	}
	private static List<Integer> patch(LiquidControlVolumeMesh mesh, int[] liquid, int cell, int rank, Map<List<Integer>, LiquidAffineFit.Basis> spans) {
		var patch = new LinkedHashSet<Integer>(); patch.add(cell);
		for (;;) {
			var next = new LinkedHashSet<>(patch);
			for (int c : patch) for (int f : mesh.cellFaces[c]) {
				var face = mesh.faces.get(f); if (liquid[face.first()] >= 0) next.add(face.first());
				if (face.second() >= 0 && liquid[face.second()] >= 0) next.add(face.second());
			}
			var key = next.stream().sorted().toList(); var span = spans.computeIfAbsent(key, cells -> new LiquidAffineFit.Basis(cells.stream().map(c -> mesh.cells.get(c).geometry().centroidCoordinates()).toList()));
			if (span.coordinate.length == rank) return key;
			patch = next;
		}
	}
	/** At most four exact independent centers identify each connected liquid's observable affine span. */
	private static Map<Integer, Integer> ranks(LiquidControlVolumeMesh mesh, int[] components) {
		var centers = new java.util.HashMap<Integer, ArrayList<Fraction[]>>(); var spans = new java.util.HashMap<Integer, LiquidAffineFit.Basis>();
		for (int c = 0; c < components.length; c++) if (components[c] >= 0) {
			int component = components[c]; var span = spans.get(component); var center = mesh.cells.get(c).geometry().centroidCoordinates();
			if (span != null && span.contains(LiquidExactPolygon.vertex(center))) continue;
			var independent = centers.computeIfAbsent(component, ignored -> new ArrayList<>()); independent.add(center);
			spans.put(component, new LiquidAffineFit.Basis(independent));
		}
		var result = new java.util.HashMap<Integer, Integer>(); spans.forEach((component, span) -> result.put(component, span.coordinate.length)); return result;
	}
	private static int[] components(LiquidControlVolumeMesh mesh, int[] liquid) {
		var result = new int[liquid.length]; Arrays.fill(result, -1); var pending = new java.util.ArrayDeque<Integer>();
		for (int c = 0; c < liquid.length; c++) if (liquid[c] >= 0 && result[c] < 0) {
			result[c] = c; pending.add(c);
			while (!pending.isEmpty()) for (int f : mesh.cellFaces[pending.removeFirst()]) {
				var face = mesh.faces.get(f);
				for (int next : new int[]{face.first(), face.second()}) if (next >= 0 && liquid[next] >= 0 && result[next] < 0) { result[next] = c; pending.add(next); }
			}
		}
		return result;
	}
	private static Distance distanceSquared(Vertex point, Vertex center) {
		var numerator = BigInteger.ZERO; var denominator = point.w().multiply(center.w());
		for (int a = 0; a < 3; a++) {
			var difference = point.coordinate(a).multiply(center.w()).subtract(center.coordinate(a).multiply(point.w()));
			numerator = numerator.add(difference.multiply(difference));
		}
		return new Distance(numerator, denominator.multiply(denominator));
	}
	/** Outward-rounded sphere boxes only select candidates; exact distances decide support. */
	private static final class Index {
		final Support support;
		final Index left, right;
		final double[] lower = new double[3], upper = new double[3];
		Index(Support support, Index left, Index right) {
			this.support = support; this.left = left; this.right = right;
			double radius = up(Math.sqrt(up(support.radiusSquared.rounded())));
			var center = support.center.rounded();
			for (int a = 0; a < 3; a++) {
				double x = center.coordinate(a), extent = up(radius + Math.ulp(x));
				lower[a] = Math.nextDown(x - extent); upper[a] = up(x + extent);
				for (var child : new Index[]{left, right}) if (child != null) { lower[a] = Math.min(lower[a], child.lower[a]); upper[a] = Math.max(upper[a], child.upper[a]); }
			}
		}
		static Index of(List<Support> values, int axis) {
			if (values.isEmpty()) return null;
			var sorted = new ArrayList<>(values); sorted.sort(java.util.Comparator.comparingDouble(s -> s.center.rounded().coordinate(axis))); int middle = sorted.size() / 2;
			return new Index(sorted.get(middle), of(sorted.subList(0, middle), (axis + 1) % 3), of(sorted.subList(middle + 1, sorted.size()), (axis + 1) % 3));
		}
		static void collect(Index node, double[] point, List<Support> result) {
			if (node == null) return;
			for (int a = 0; a < 3; a++) if (up(point[a] + Math.ulp(point[a])) < node.lower[a] || Math.nextDown(point[a] - Math.ulp(point[a])) > node.upper[a]) return;
			result.add(node.support); collect(node.left, point, result); collect(node.right, point, result);
		}
	}
}
