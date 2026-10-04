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
		refinedViscosityPreservesRigidMotionAndMomentum();
		finiteGasPressureSharesLiquidProjection();
		pressureAndViscosityShareMomentumAndEnergy();
		System.out.println("Liquid control-volume pressure and viscosity validation passed");
	}

	private static void finiteGasPressureSharesLiquidProjection() {
		var geometry = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>(); var nodes = new ArrayList<Integer>();
		for (var surface : geometry.surfaces()) {
			int node = surface.areaVector().x() < 0 ? 0 : surface.areaVector().x() > 0 ? 1 : -1;
			faces.add(new LiquidControlVolumeProjection.Face(0, node < 0 ? -2 : -1, surface, node == 0 ? 120_000 : node == 1 ? 100_000 : 0));
			nodes.add(node);
		}
		double dt = 0.01, compliance = 1e-6;
		var gas = List.of(new LiquidControlVolumeProjection.GasNode(120_000, compliance), new LiquidControlVolumeProjection.GasNode(100_000, compliance));
		var projection = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces,
				gas, nodes.stream().mapToInt(Integer::intValue).toArray());
		var result = projection.project(new double[3], new LiquidPolyhedron.Point(0, 0, 0), dt, 1e-11, 256);
		double velocity = dt * 20_000 / (1000 + dt * dt * 2 / compliance);
		close(result.pressure()[0], 110_000, 1e-7);
		close(result.gasPressure()[0], 120_000 - dt * velocity / compliance, 1e-7);
		close(result.gasPressure()[1], 100_000 + dt * velocity / compliance, 1e-7);
		close(result.gasVolumeChange()[0], dt * velocity, 1e-12); close(result.gasVolumeChange()[1], -dt * velocity, 1e-12);
		for (int f = 0; f < faces.size(); f++) close(result.normalVelocity()[f], velocity * faces.get(f).surface().areaVector().x(), 1e-10);
		close(-dt * result.pressureGradient()[0].x() / 1000, velocity, 1e-10);
	}

	private static void refinedViscosityPreservesRigidMotionAndMomentum() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		var refined = leaves.removeFirst(); leaves.addAll(LiquidGeometryRefinement.children(refined));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000, (cell, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		double[] viscosity = new double[mesh.cells.size()]; java.util.Arrays.fill(viscosity, 20);
		var operator = new LiquidControlVolumeViscosity(mesh.cells, mesh.faces, viscosity, FluidSolverConfig.WallCondition.FREE_SLIP);
		for (int mode = 0; mode < 3; mode++) {
			double[] incoming = new double[3 * mesh.cells.size()], faces = new double[3 * mesh.faces.size()];
			for (int c = 0; c < mesh.cells.size(); c++) velocity(incoming, c, mesh.cells.get(c).centroid(), mode);
			for (int f = 0; f < mesh.faces.size(); f++) velocity(faces, f, mesh.faces.get(f).surface().centroid(), mode);
			var result = operator.advance(incoming, faces, 0.05, 1e-9, 1024);
			double before = 0, after = 0; double[] momentum = new double[3];
			for (int c = 0; c < mesh.cells.size(); c++) {
				double mass = 1000 * mesh.cells.get(c).geometry().moments().volume();
				for (int a = 0; a < 3; a++) {
					int id = 3 * c + a; double next = result.cellVelocity()[id];
					before += 0.5 * mass * incoming[id] * incoming[id]; after += 0.5 * mass * next * next;
					momentum[a] += mass * (next - incoming[id]);
					if (mode < 2) close(next, incoming[id], 1e-11);
				}
			}
			for (double change : momentum) close(change, 0, 1e-8);
			close(before - after, result.dissipatedJoules() + result.temporalDissipationJoules(), 1e-8);
			if (mode < 2) close(result.dissipatedJoules(), 0, 1e-20);
			else if (!(result.dissipatedJoules() > 0 && after < before)) throw new AssertionError("Liquid viscous shear failed to dissipate");
		}
	}

	private static void velocity(double[] target, int index, LiquidPolyhedron.Point p, int mode) {
		target[3 * index] = mode == 0 ? 3 : mode == 1 ? 3 - 2 * p.y() : p.y();
		target[3 * index + 1] = mode == 1 ? -2 + 2 * p.x() : mode == 0 ? -2 : 0;
		target[3 * index + 2] = mode < 2 ? 1 : 0;
	}

	private static void pressureAndViscosityShareMomentumAndEnergy() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0,
				LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(1, 0.7, 0.3, 1.21));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		leaves.addAll(LiquidGeometryRefinement.children(leaves.removeFirst()));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000,
				(cell, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		double[] incoming = new double[3 * mesh.cells.size()], viscosity = new double[mesh.cells.size()];
		java.util.Arrays.fill(viscosity, 20);
		for (int c = 0; c < mesh.cells.size(); c++) for (int a = 0; a < 3; a++)
			incoming[3 * c + a] = Math.sin(0.7 + 3.1 * c + 1.3 * a);
		var viscous = new LiquidControlVolumeViscosity(mesh.cells, mesh.faces, viscosity,
				FluidSolverConfig.WallCondition.FREE_SLIP).advance(incoming, new double[3 * mesh.faces.size()], 0.025, 1e-9, 2048);
		var projected = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(viscous.cellVelocity(),
				new LiquidPolyhedron.Point(0, 0, 0), 0.025, 1e-11, 2048);
		double before = 0, after = 0; double[] momentum = new double[3];
		for (int c = 0; c < mesh.cells.size(); c++) for (int a = 0; a < 3; a++) {
			int id = 3 * c + a; double mass = mesh.cells.get(c).density() * mesh.cells.get(c).geometry().moments().volume();
			before += 0.5 * mass * incoming[id] * incoming[id];
			after += 0.5 * mass * projected.cellVelocity()[id] * projected.cellVelocity()[id];
			momentum[a] += mass * (projected.cellVelocity()[id] - incoming[id]);
		}
		for (double change : momentum) close(change, 0, 1e-8);
		close(before - after + projected.boundaryWorkJoules() + viscous.boundaryWorkJoules(),
				viscous.dissipatedJoules() + viscous.temporalDissipationJoules() + projected.pressureDissipationJoules(), 1e-8);
		if (!(projected.pressureDissipationJoules() > 0)) throw new AssertionError("Unresolved pressure modes have no dissipative energy");
	}

	private static void refinedHydrostaticColumn() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		var refined = leaves.removeFirst();
		var children = new ArrayList<>(LiquidGeometryRefinement.children(refined));
		var smallest = children.removeFirst(); leaves.addAll(children); leaves.addAll(LiquidGeometryRefinement.children(smallest));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000, (cell, surface) ->
				new LiquidControlVolumeMesh.Boundary(!(surface.areaVector().y() > 0 && surface.centroid().y() == 1), 0));
		var result = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[3 * mesh.cells.size()], GRAVITY, 0.025, 1e-11, 512);
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
		var result = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[3 * mesh.cells.size()], GRAVITY, 0.025, 1e-11, 1024);
		for (int c = 0; c < mesh.cells.size(); c++) close(result.pressure()[c], 1000 * 9.81 * (1 - mesh.cells.get(c).centroid().y()), 1e-7);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 1e-10);
	}

	private static void freeLiquidReceivesGravity(double size) {
		var geometry = LiquidPolyhedron.box(0.125, 0.25, 0.375, 0.125 + size, 0.25 + size, 0.375 + size);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var face : geometry.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0, -1, face, 100_000));
		var projection = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces);
		double dt = 0.025;
		var result = projection.project(new double[3], GRAVITY, dt, 1e-12, 64);
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
		var result = projection.project(new double[3], GRAVITY, 0.025, 1e-12, 64);
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
		var result = new LiquidControlVolumeProjection(cells, faces).project(new double[3 * cells.size()], GRAVITY, 0.025, 1e-12, 64);
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
