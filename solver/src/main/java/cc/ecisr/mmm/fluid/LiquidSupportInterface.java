package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** Finite supporting half-spaces of the actual phase surface, intersected with available open pieces. */
final class LiquidSupportInterface {
	record Result(List<LiquidPolyhedron> pieces, Fraction coordinateDifferenceVolumeBound) { }
	private record Bounds(BigInteger inner, BigInteger outer) { }
	private LiquidSupportInterface() { }
	static Result reconstruct(LiquidGeometryRefinement.Cell cell, List<LiquidPolyhedron> available, List<LiquidPolyhedron> source, int bits) {
		var areas = new HashMap<List<BigInteger>, Fraction>(); var vertices = new HashSet<Vertex>();
		var walls = new HashSet<List<BigInteger>>();
		for (var face : LiquidExactPolygon.faces(cell.geometry())) walls.add(unoriented(face.coefficients()));
		for (var piece : source) for (var face : LiquidExactPolygon.faces(piece)) {
			var plane = face.coefficients(); int axis = 0; while (plane.get(axis).signum() == 0) axis++;
			var key = unoriented(plane); vertices.addAll(face.vertices());
			if (!walls.contains(key)) areas.merge(key, face.areaVector()[axis], Fraction::add);
		}
		var bounds = new HashMap<List<BigInteger>, Bounds>(); var grid = BigInteger.ONE.shiftLeft(bits);
		for (var entry : areas.entrySet()) {
			int sign = entry.getValue().signum(); if (sign == 0) continue;
			var plane = entry.getKey(); var scale = BigInteger.ZERO;
			for (int axis = 0; axis < 3; axis++) scale = scale.max(plane.get(axis).abs());
			var normal = new ArrayList<BigInteger>();
			for (int axis = 0; axis < 3; axis++) normal.add(plane.get(axis).multiply(BigInteger.valueOf(sign)).shiftLeft(bits).divide(scale));
			BigInteger numerator = null, denominator = null;
			for (var vertex : vertices) {
				var value = plane.get(0).multiply(vertex.x()).add(plane.get(1).multiply(vertex.y())).add(plane.get(2).multiply(vertex.z())).multiply(BigInteger.valueOf(sign));
				if (numerator == null || value.multiply(denominator).compareTo(numerator.multiply(vertex.w())) > 0) { numerator = value; denominator = vertex.w(); }
			}
			var inner = new Fraction(numerator, denominator.multiply(scale)); var outer = inner;
			for (int axis = 0; axis < 3; axis++) {
				var error = new Fraction(normal.get(axis), grid).subtract(new Fraction(plane.get(axis).multiply(BigInteger.valueOf(sign)), scale));
				var lo = Fraction.of(cell.minimum(axis)); var hi = lo.add(Fraction.of(cell.width()));
				inner = inner.add(error.multiply(error.signum() >= 0 ? lo : hi)); outer = outer.add(error.multiply(error.signum() >= 0 ? hi : lo));
			}
			var pair = new Bounds(offset(inner, grid, false), offset(outer, grid, true));
			bounds.merge(List.copyOf(normal), pair, (a, b) -> new Bounds(a.inner.min(b.inner), a.outer.min(b.outer)));
		}
		var normals = new ArrayList<>(bounds.keySet());
		normals.sort((a, b) -> { for (int i = 0; i < 3; i++) { int order = a.get(i).compareTo(b.get(i)); if (order != 0) return order; } return 0; });
		var result = new ArrayList<LiquidPolyhedron>(); var difference = Fraction.ZERO;
		for (var open : available) {
			var original = LiquidExactPolygon.faces(open); var outer = original; var inner = original;
			for (var normal : normals) {
				var pair = bounds.get(normal);
				outer = LiquidExactPolygon.clipVolume(outer, List.of(normal.get(0), normal.get(1), normal.get(2), pair.outer), 1);
				inner = LiquidExactPolygon.clipVolume(inner, List.of(normal.get(0), normal.get(1), normal.get(2), pair.inner), 1);
			}
			if (!outer.isEmpty()) {
				var geometry = outer == original ? open : LiquidPolyhedron.restoredExact(outer); result.add(geometry);
				difference = difference.add(geometry.measure().volume().subtract(LiquidExactPolygon.volume(inner)));
			}
		}
		if (difference.signum() < 0) throw new IllegalStateException("Supporting plane coordinate enclosures inverted their volume bounds");
		return new Result(List.copyOf(result), difference);
	}
	private static List<BigInteger> unoriented(List<BigInteger> plane) {
		int axis = 0; while (plane.get(axis).signum() == 0) axis++;
		return plane.get(axis).signum() < 0 ? plane.stream().map(BigInteger::negate).toList() : plane;
	}
	private static BigInteger offset(Fraction value, BigInteger grid, boolean upper) {
		var division = value.numerator().multiply(grid).divideAndRemainder(value.denominator()); var result = division[0];
		if (division[1].signum() != 0 && (upper ? value.signum() > 0 : value.signum() < 0)) result = upper ? result.add(BigInteger.ONE) : result.subtract(BigInteger.ONE);
		return result;
	}
}
