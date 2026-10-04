package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Pressure behavior is tied to liquid geometry, including controls thinner than half a base cell. */
final class LiquidControlVolumeValidation {
	private static final LiquidPolyhedron.Point GRAVITY = new LiquidPolyhedron.Point(0, -9.81, 0);
	static void validate() {
		for (double size : new double[] {0.5, 0.125, 1.0 / 1024}) freeLiquidReceivesGravity(size);
		for (double height : new double[] {0.5, 0.125, 1.0 / 1024}) thinHydrostaticColumn(height);
		densityJumpUsesBothLiquidDistances();
		refinedHydrostaticColumn();
		refinedAffinePressure();
		System.out.println("Liquid control-volume pressure: free motion, thin hydrostatics and density jump passed");
	}

	private static void refinedHydrostaticColumn() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		var refined = leaves.removeFirst();
		var children = new ArrayList<>(LiquidGeometryRefinement.children(refined));
		var smallest = children.removeFirst(); leaves.addAll(children); leaves.addAll(LiquidGeometryRefinement.children(smallest));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000, (cell, surface) ->
				new LiquidControlVolumeMesh.Boundary(!(surface.areaVector().y() > 0 && surface.centroid().y() == 1), 0));
		var result = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[mesh.faces.size()], GRAVITY, 0.025, 1e-11, 512);
		for (int c = 0; c < mesh.cells.size(); c++) close(result.pressure()[c], 1000 * 9.81 * (1 - mesh.cells.get(c).centroid().y()), 1e-7);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 1e-10);
	}

	private static void refinedAffinePressure() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0,
				LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(1, 0.7, 0.3, 1.21));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		var refined = leaves.removeFirst(); leaves.addAll(LiquidGeometryRefinement.children(refined));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000, (cell, surface) ->
				new LiquidControlVolumeMesh.Boundary(false, 1000 * 9.81 * (1 - surface.centroid().y())));
		var result = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[mesh.faces.size()], GRAVITY, 0.025, 1e-11, 1024);
		for (int c = 0; c < mesh.cells.size(); c++) close(result.pressure()[c], 1000 * 9.81 * (1 - mesh.cells.get(c).centroid().y()), 1e-7);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 1e-10);
	}

	private static void freeLiquidReceivesGravity(double size) {
		var geometry = LiquidPolyhedron.box(0.125, 0.25, 0.375, 0.125 + size, 0.25 + size, 0.375 + size);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var face : geometry.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0, -1, face, 100_000));
		var projection = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces);
		double dt = 0.025;
		var result = projection.project(new double[faces.size()], GRAVITY, dt, 1e-12, 64);
		close(result.pressure()[0], 100_000, 1e-9);
		for (int f = 0; f < faces.size(); f++) {
			var surface = faces.get(f).surface();
			close(result.normalVelocity()[f], dt * surface.areaVector().dot(GRAVITY) / surface.area(), 1e-10);
		}
	}

	private static void thinHydrostaticColumn(double height) {
		var geometry = LiquidPolyhedron.box(0, 0, 0, 1, height, 1);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var surface : geometry.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0,
				surface.areaVector().y() > 0 ? -1 : -2, surface, 0));
		var projection = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces);
		var result = projection.project(new double[faces.size()], GRAVITY, 0.025, 1e-12, 64);
		close(result.pressure()[0], 1000 * 9.81 * height / 2, 1e-10);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 1e-12);
	}

	private static void densityJumpUsesBothLiquidDistances() {
		var lower = LiquidPolyhedron.box(0, 0, 0, 1, 0.25, 1);
		var upper = LiquidPolyhedron.box(0, 0.25, 0, 1, 0.375, 1);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var surface : lower.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0,
				surface.areaVector().y() > 0 ? 1 : -2, surface, 0));
		for (var surface : upper.surfaces()) if (!(surface.areaVector().y() < 0))
			faces.add(new LiquidControlVolumeProjection.Face(1, surface.areaVector().y() > 0 ? -1 : -2, surface, 0));
		var cells = List.of(new LiquidControlVolumeProjection.Cell(lower, 2000), new LiquidControlVolumeProjection.Cell(upper, 1000));
		var result = new LiquidControlVolumeProjection(cells, faces).project(new double[faces.size()], GRAVITY, 0.025, 1e-12, 64);
		close(result.pressure()[0], 9.81 * (2000 * 0.125 + 1000 * 0.125), 1e-10);
		close(result.pressure()[1], 9.81 * 1000 * 0.0625, 1e-10);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 1e-12);
	}

	private static void close(double actual, double expected, double tolerance) {
		if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance)
			throw new AssertionError("Liquid control-volume value: actual=" + actual + " expected=" + expected);
	}
	public static void main(String[] args) { validate(); }
}
