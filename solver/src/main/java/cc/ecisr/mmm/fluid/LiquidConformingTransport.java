package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Space-time face sweeps realize the prescribed integrated flux with shared continuous geometry. */
final class LiquidConformingTransport {
	record Image(int cell, int face, LiquidConvexRegion geometry, List<Fraction> affineMap) { }
	final LiquidControlVolumeMesh mesh;
	final LiquidConformingVelocity field;
	final int[] liquid;
	final Map<Vertex, Vertex> positions;
	final List<Image> images;
	final Fraction[] sweptVolume;
	final double maximumPositionRoundoffMetres;

	LiquidConformingTransport(LiquidControlVolumeMesh mesh, LiquidConformingVelocity field, Fraction[] rate, double dt) {
		this(mesh, field, rate, dt, new int[mesh.cells.size()]);
	}
	LiquidConformingTransport(LiquidControlVolumeMesh mesh, LiquidConformingVelocity field, Fraction[] rate, double dt, int[] liquid) {
		if (field.mesh != mesh || rate.length != mesh.faces.size() || liquid.length != mesh.cells.size() || !(dt > 0) || !Double.isFinite(dt)) throw new IllegalArgumentException("Invalid continuous transport input");
		this.mesh = mesh; this.field = field; this.liquid = liquid.clone();
		var time = Fraction.of(dt); var moved = new LinkedHashMap<Vertex, Vertex>();
		double roundoff = 0;
		for (var node : field.velocity.entrySet()) {
			var position = field.advectedPosition(node.getKey(), time); moved.put(node.getKey(), position.point());
			if (position.roundoffSquared().signum() != 0) roundoff = Math.max(roundoff, Math.nextUp(Math.sqrt(Math.nextUp(position.roundoffSquared().rounded()))));
		}
		maximumPositionRoundoffMetres = roundoff;
		sweptVolume = new Fraction[rate.length];
		for (int f = 0; f < rate.length; f++) {
			if (rate[f] == null) throw new IllegalArgumentException("Absent continuous transport rate");
			var face = mesh.faces.get(f);
			if (liquid[face.first()] < 0 && (face.second() < 0 || liquid[face.second()] < 0)) { sweptVolume[f] = Fraction.ZERO; continue; }
			var polygon = mesh.exactPolygons.get(f); var center = field.faceCenters.get(f); var area = area(polygon.vertices().stream().map(moved::get).toList());
			var norm = dot(area, area); if (norm.signum() == 0) throw new LiquidCharacteristicTimeResolutionRequired("Continuous face collapsed during transport");
			var target = rate[f].multiply(time); var delta = target.subtract(sweep(polygon.vertices(), center, moved)).multiply(Fraction.of(3)).divide(norm);
			var x = moved.get(center).coordinates(); add(x, scale(area, delta)); moved.put(center, LiquidExactPolygon.vertex(x));
			sweptVolume[f] = sweep(polygon.vertices(), center, moved);
			if (!sweptVolume[f].equals(target)) throw new IllegalStateException("Continuous face sweep lost exact integrated volume");
		}
		// The endpoint fan apex is an auxiliary partition point. Closed-surface volume and moments are independent of it.
		// Momentum is carried by the original velocity-field mean in LiquidGeometryMotionPlan.
		for (int c = 0; c < mesh.cells.size(); c++) if (liquid[c] >= 0)
			moved.put(field.cellCenters.get(c), LiquidExactPolygon.vertex(field.meanPosition(c, time)));
		var result = new ArrayList<Image>(); var volume = new Fraction[mesh.cells.size()]; java.util.Arrays.fill(volume, Fraction.ZERO);
		for (var tet : field.tetrahedra) if (liquid[tet.cell()] >= 0) {
			var source = tet.vertices(); var target = source.stream().map(moved::get).toList();
			var oldDet = LiquidExactPolygon.homogeneousTetrahedronDeterminant(source); var newDet = LiquidExactPolygon.homogeneousTetrahedronDeterminant(target);
			if (newDet.signum() != oldDet.signum()) throw new LiquidCharacteristicTimeResolutionRequired("Continuous transport inverted a tetrahedron");
			var geometry = LiquidConvexRegion.ofTetrahedron(target);
			volume[tet.cell()] = volume[tet.cell()].add(geometry.measure().volume()); result.add(new Image(tet.cell(), tet.face(), geometry, affine(source, target)));
		}
		for (int c = 0; c < volume.length; c++) if (liquid[c] >= 0) {
			var expected = mesh.cells.get(c).geometry().measure().volume();
			for (int f : mesh.cellFaces[c]) expected = expected.add(mesh.faces.get(f).first() == c ? sweptVolume[f] : sweptVolume[f].negate());
			if (!volume[c].equals(expected)) throw new IllegalStateException("Continuous transport lost cell geometric conservation");
		}
		positions = java.util.Collections.unmodifiableMap(moved); images = List.copyOf(result);
	}
	private static Fraction sweep(List<Vertex> points, Vertex center, Map<Vertex, Vertex> moved) {
		var value = Fraction.ZERO; var oldCenter = center.coordinates(); var newCenter = moved.get(center).coordinates();
		for (int i = 0; i < points.size(); i++) {
			var a = points.get(i); var b = points.get((i + 1) % points.size()); var x = a.coordinates(); var y = b.coordinates();
			var nextX = moved.get(a).coordinates(); var nextY = moved.get(b).coordinates();
			value = value.add(cone(newCenter, nextX, nextY)).subtract(cone(oldCenter, x, y));
			// Both incident polygons triangulate the temporal edge quad along the same diagonal.
			if (compare(a, b) < 0) value = value.add(cone(x, y, nextY)).add(cone(x, nextY, nextX));
			else value = value.add(cone(x, y, nextX)).add(cone(y, nextY, nextX));
		}
		return value;
	}
	private static int compare(Vertex a, Vertex b) {
		for (int i = 0; i < 3; i++) { var d = new Fraction(a.coordinate(i), a.w()).subtract(new Fraction(b.coordinate(i), b.w())); if (d.signum() != 0) return d.signum(); }
		return 0;
	}
	private static Fraction cone(Fraction[] a, Fraction[] b, Fraction[] c) { return dot(a, cross(b, c)).divide(Fraction.of(6)); }
	private static Fraction[] area(List<Vertex> points) {
		var value = zero(); for (int i = 0; i < points.size(); i++) add(value, cross(points.get(i).coordinates(), points.get((i + 1) % points.size()).coordinates()));
		return scale(value, Fraction.of(0.5));
	}
	private static List<Fraction> affine(List<Vertex> source, List<Vertex> target) {
		var origin = source.getFirst().coordinates(); var next = target.getFirst().coordinates();
		var columns = new Fraction[3][]; var moved = new Fraction[3][];
		for (int i = 0; i < 3; i++) { columns[i] = subtract(source.get(i + 1).coordinates(), origin); moved[i] = subtract(target.get(i + 1).coordinates(), next); }
		var det = dot(columns[0], cross(columns[1], columns[2])); var inverse = new Fraction[3][];
		for (int i = 0; i < 3; i++) inverse[i] = scale(cross(columns[(i + 1) % 3], columns[(i + 2) % 3]), Fraction.of(1).divide(det));
		var result = new ArrayList<Fraction>();
		for (int a = 0; a < 3; a++) {
			var offset = next[a];
			for (int b = 0; b < 3; b++) {
				var entry = Fraction.ZERO; for (int i = 0; i < 3; i++) entry = entry.add(moved[i][a].multiply(inverse[i][b]));
				result.add(entry); offset = offset.subtract(entry.multiply(origin[b]));
			}
			result.add(offset);
		}
		return List.copyOf(result);
	}
	private static Fraction[] zero() { return new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO}; }
	private static void add(Fraction[] a, Fraction[] b) { for (int i = 0; i < 3; i++) a[i] = a[i].add(b[i]); }
	private static Fraction[] subtract(Fraction[] a, Fraction[] b) { return new Fraction[]{a[0].subtract(b[0]), a[1].subtract(b[1]), a[2].subtract(b[2])}; }
	private static Fraction[] scale(Fraction[] a, Fraction b) { return new Fraction[]{a[0].multiply(b), a[1].multiply(b), a[2].multiply(b)}; }
	private static Fraction dot(Fraction[] a, Fraction[] b) { var value = Fraction.ZERO; for (int i = 0; i < 3; i++) value = value.add(a[i].multiply(b[i])); return value; }
	private static Fraction[] cross(Fraction[] a, Fraction[] b) { return new Fraction[]{a[1].multiply(b[2]).subtract(a[2].multiply(b[1])), a[2].multiply(b[0]).subtract(a[0].multiply(b[2])), a[0].multiply(b[1]).subtract(a[1].multiply(b[0]))}; }
}
