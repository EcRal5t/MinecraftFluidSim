package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;

/** Material interface area and stationary Young wall energy on the actual shared velocity triangles. */
final class LiquidGeometricSurfaceEnergy {
	final LiquidAreaEnergy area;
	final LiquidPressureFaceEnergy pressure;
	LiquidGeometricSurfaceEnergy(LiquidConformingVelocity field, double[] coefficient) {
		if (coefficient.length != field.mesh.faces.size()) throw new IllegalArgumentException("Incomplete geometric surface coefficients");
		field.requireSurfaceResolution(coefficient);
		var polygons = new ArrayList<LiquidExactPolygon>(); var values = new ArrayList<Double>();
		var free = new ArrayList<LiquidExactPolygon>(); var freeValues = new ArrayList<Double>();
		var walls = new ArrayList<LiquidExactPolygon>(); var wallValues = new ArrayList<Double>();
		var incident = new int[coefficient.length]; Arrays.fill(incident, -1);
		for (var tet : field.tetrahedra) {
			int face = tet.face(); if (face < 0) continue; double value = coefficient[face];
			if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite geometric surface coefficient");
			if (value == 0) continue;
			if (incident[face] < 0) incident[face] = tet.cell();
			if (incident[face] != tet.cell()) continue;
			var polygon = LiquidExactPolygon.tetrahedron(tet.vertices()).getFirst(); polygons.add(polygon); values.add(value);
			if (field.mesh.faces.get(face).second() == -2) { walls.add(polygon); wallValues.add(value); }
			else { free.add(polygon); freeValues.add(value); }
		}
		area = new LiquidAreaEnergy(polygons, values.stream().mapToDouble(Double::doubleValue).toArray());
		pressure = new LiquidPressureFaceEnergy(field, area,
				new LiquidSurfaceMetric(free, freeValues.stream().mapToDouble(Double::doubleValue).toArray()),
				new LiquidWetWallEnergy(walls, wallValues.stream().mapToDouble(Double::doubleValue).toArray()));
	}
}
