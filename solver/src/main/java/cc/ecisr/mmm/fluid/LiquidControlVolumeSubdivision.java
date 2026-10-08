package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Positive integration regions retain the inertia of their complete physical control. */
final class LiquidControlVolumeSubdivision {
	private record Plane(LiquidGeometryPlane.Key key, boolean positive) {
		static Plane of(LiquidExactPolygon polygon) { return new Plane(polygon.plane().key(), polygon.plane().positive()); }
	}
	record Fan(int cell, LiquidPolyhedron geometry, int[] faces, List<LiquidExactPolygon> polygons) { }
	final List<LiquidControlVolumeProjection.Cell> cells;
	final List<LiquidControlVolumeProjection.Face> faces;
	final List<LiquidExactPolygon> polygons;
	final List<Fan> fans;

	LiquidControlVolumeSubdivision(List<LiquidGeometryRefinement.Cell> controls,
			List<List<LiquidGeometryRefinement.Cell>> support, double width, double density,
			LiquidControlVolumeMesh.BoundaryCondition boundary) {
		if (support.size() != controls.size()) throw new IllegalArgumentException("Incomplete physical control subdivision");
		var pieces = new ArrayList<LiquidGeometryRefinement.Cell>(); var owners = new ArrayList<Integer>();
		var physical = new ArrayList<LiquidControlVolumeProjection.Cell>(); var work = new LiquidStarControlPartition.Work();
		for (int c = 0; c < controls.size(); c++) {
			var geometry = controls.get(c).geometry(); var measure = LiquidVolumeInterface.measure(support.get(c).stream().map(LiquidGeometryRefinement.Cell::geometry).toList());
			if (!measure.same(geometry.measure())) throw new IllegalArgumentException("Auxiliary subdivision changed a physical control's volume or first moment");
			var exterior = LiquidExteriorGeometry.of(support.get(c));
			requireCovered(LiquidExactPolygon.faces(geometry), exterior, work); requireCovered(exterior, LiquidExactPolygon.faces(geometry), work);
			physical.add(new LiquidControlVolumeProjection.Cell(geometry.scaled(width), density));
			var partition = LiquidStarControlPartition.build(support.get(c), work);
			for (var region : partition) { pieces.add(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, region.geometry())); owners.add(c); }
		}
		var auxiliary = new LiquidControlVolumeMesh(pieces, width, density, (c, face) -> boundary.at(owners.get(c), face));
		var retained = new ArrayList<LiquidControlVolumeProjection.Face>(); var surfaces = new ArrayList<LiquidExactPolygon>();
		var physicalFaces = new int[auxiliary.faces.size()]; java.util.Arrays.fill(physicalFaces, -1);
		for (int f = 0; f < physicalFaces.length; f++) {
			var face = auxiliary.faces.get(f); int a = owners.get(face.first()), b = face.second() < 0 ? face.second() : owners.get(face.second());
			if (a == b) continue;
			physicalFaces[f] = retained.size(); surfaces.add(auxiliary.exactPolygons.get(f));
			retained.add(new LiquidControlVolumeProjection.Face(a, b, face.surface(), face.prescribed(), face.areaVector(), face.centroid()));
		}
		var integration = new ArrayList<Fan>();
		for (int p = 0; p < pieces.size(); p++) {
			var ids = new int[auxiliary.cellFaces[p].length]; var exterior = new ArrayList<LiquidExactPolygon>();
			for (int i = 0; i < ids.length; i++) {
				int f = auxiliary.cellFaces[p][i]; ids[i] = physicalFaces[f]; var polygon = auxiliary.exactPolygons.get(f);
				exterior.add(auxiliary.faces.get(f).first() == p ? polygon : polygon.reversed());
			}
			integration.add(new Fan(owners.get(p), auxiliary.cells.get(p).geometry(), ids, List.copyOf(exterior)));
		}
		cells = List.copyOf(physical); faces = List.copyOf(retained); polygons = List.copyOf(surfaces); fans = List.copyOf(integration);
	}
	private static void requireCovered(List<LiquidExactPolygon> original, List<LiquidExactPolygon> covering, LiquidStarControlPartition.Work work) {
		var planes = new java.util.HashMap<Plane, List<LiquidExactPolygon>>();
		for (var polygon : covering) planes.computeIfAbsent(Plane.of(polygon), ignored -> new ArrayList<>()).add(polygon);
		for (var polygon : original) {
			List<LiquidExactPolygon> remaining = List.of(polygon);
			for (var candidate : planes.getOrDefault(Plane.of(polygon), List.of())) {
				if (remaining.isEmpty()) break; var next = new ArrayList<LiquidExactPolygon>();
				for (var piece : remaining) { work.pair(); next.addAll(piece.subtract(candidate)); } remaining = next;
			}
			if (!remaining.isEmpty()) throw new IllegalArgumentException("Auxiliary subdivision changed a physical exterior face");
		}
	}
}
