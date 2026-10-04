package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Whole-volume characteristics preserve geometry, exact inventory and shared velocity traces. */
final class LiquidCharacteristicValidation {
	private static final LiquidPolyhedron.Point GRAVITY = new LiquidPolyhedron.Point(0, -9.81, 0);
	static void validate() {
		repeatedGravityPreservesUniformCharacteristic();
		wholeVolumeCharacteristicsPreserveGeometry();
		mixedVelocitySupportSurvivesNextStep();
		thinLiquidLayerPreservesSeparateGasPressureNodes();
	}
	private static void mixedVelocitySupportSurvivesNextStep() {
		var left = new LiquidGeometryRefinement.Cell(1, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 0.5, 0.5, 0.5));
		var right = new LiquidGeometryRefinement.Cell(1, 1, 0, 0, LiquidPolyhedron.box(0.5, 0, 0, 1, 0.5, 0.5));
		var mesh = new LiquidControlVolumeMesh(List.of(left, right), 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var rates = new cc.ecisr.mmm.units.SignedDyadic[mesh.faces.size()]; var velocity = new double[6]; velocity[3] = 0.125;
		for (int f = 0; f < rates.length; f++) {
			var face = mesh.faces.get(f); var normal = face.surface().areaVector(); rates[f] = cc.ecisr.mmm.units.SignedDyadic.ZERO;
			if (normal.x() > 0) rates[f] = cc.ecisr.mmm.units.SignedDyadic.of(normal.x() * 0.125);
			else if (face.first() == 0 && normal.y() > 0) rates[f] = cc.ecisr.mmm.units.SignedDyadic.of(-0.03125);
		}
		var trajectory = new LiquidVelocityTrajectory(mesh, LiquidControlVolumeVelocity.reconstruct(mesh, rates, velocity));
		var support = new LiquidVelocityRegions(trajectory);
		if (support.cells(0).size() <= 1 || support.cells(1).size() != 1) throw new AssertionError("Exact uniform velocity support coalescing changed a nonuniform field");
		var source = LiquidPolyhedron.box(0.49, 0.125, 0.125, 0.499, 0.375, 0.375); var inventory = cc.ecisr.mmm.units.SignedDyadic.of(source.moments().volume());
		var moved = new LiquidCharacteristicTransport(trajectory).advect(0, source, inventory, 0.1, 16384, 256);
		var sum = LiquidPolyhedron.Moments.ZERO; var amount = cc.ecisr.mmm.units.SignedDyadic.ZERO;
		for (var fragment : moved) { sum = sum.add(fragment.geometry().moments()); amount = amount.add(fragment.inventory()); }
		if (!amount.equals(inventory)) throw new AssertionError("Mixed velocity support lost exact liquid inventory");
		close(sum.volume(), source.moments().volume(), 1e-18); close(sum.centroid(0), source.moments().centroid(0) + 0.0125, 1e-15);
		characteristicImageRebuildsSpace(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 0.5, 0.5)), moved);
	}
	private static void thinLiquidLayerPreservesSeparateGasPressureNodes() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var layer = root.geometry().clip(0, 1, 0, 0.501).clip(0, -1, 0, -0.499);
		var regions = LiquidPhasePartition.build(root, List.of(layer), 16); var labels = regions.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray();
		var mesh = new LiquidControlVolumeMesh(regions.stream().map(LiquidPhasePartition.Region::cell).toList(), 1, 1000,
				(c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var density = new double[regions.size()]; var viscosity = new double[regions.size()]; var nodes = new int[regions.size()];
		java.util.Arrays.fill(density, 1000); java.util.Arrays.fill(viscosity, 0.001); java.util.Arrays.fill(nodes, -1);
		for (int c = 0; c < regions.size(); c++) if (labels[c] < 0) nodes[c] = mesh.cells.get(c).centroid().y() < 0.5 ? 0 : 1;
		var gas = List.of(new LiquidControlVolumeProjection.GasNode(100000, 1e-6), new LiquidControlVolumeProjection.GasNode(100000, 1e-6));
		var mechanics = new LiquidPhaseMechanics(mesh, labels, density, viscosity, gas, nodes, (f, c) -> 100000, FluidSolverConfig.WallCondition.FREE_SLIP);
		var result = mechanics.advance(new double[3 * regions.size()], new double[3 * mesh.faces.size()], new double[3 * regions.size()], 0.01, 1e-11, 1e-11, 512);
		close(result.pressure().pressure()[0], 100000, 0);
		for (double pressure : result.pressure().gasPressure()) close(pressure, 100000, 0);
		for (var cell : result.velocity()) for (var tetra : cell.tetrahedra()) for (var rate : tetra.outwardFlux())
			if (rate.signum() != 0) throw new AssertionError("Balanced thin layer acquired artificial gas or liquid motion");
		for (int c = 0; c < nodes.length; c++) if (labels[c] < 0) nodes[c] = 0;
		try {
			new LiquidPhaseMechanics(mesh, labels, density, viscosity, gas, nodes, (f, c) -> 100000, FluidSolverConfig.WallCondition.FREE_SLIP);
			throw new AssertionError("Separated gas chambers accepted one pressure node");
		} catch (IllegalArgumentException expected) {
			if (!expected.getMessage().contains("Separate gas chambers")) throw expected;
		}
	}
	private static void repeatedGravityPreservesUniformCharacteristic() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var liquid = LiquidPolyhedron.box(0.4375, 0.6875, 0.4375, 0.5625, 0.8125, 0.5625);
		var inventory = cc.ecisr.mmm.units.SignedDyadic.of(liquid.moments().volume()); var velocity = new double[3]; double dt = 0.01;
		for (int step = 1; step <= 5; step++) {
			var regions = LiquidPhasePartition.build(root, List.of(liquid), 64); var labels = regions.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray();
			var mesh = new LiquidControlVolumeMesh(regions.stream().map(LiquidPhasePartition.Region::cell).toList(), 1, 1000,
					(c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			var density = new double[regions.size()]; var viscosity = new double[regions.size()]; var gasNodes = new int[regions.size()];
			java.util.Arrays.fill(density, 1000); java.util.Arrays.fill(viscosity, 0.001); java.util.Arrays.fill(gasNodes, -1);
			var primary = new double[3 * regions.size()]; System.arraycopy(velocity, 0, primary, 0, 3);
			var traces = new double[3 * mesh.faces.size()]; for (int f = 0; f < mesh.faces.size(); f++) System.arraycopy(velocity, 0, traces, 3 * f, 3);
			var acceleration = new double[primary.length]; acceleration[1] = GRAVITY.y();
			var mechanics = new LiquidPhaseMechanics(mesh, labels, density, viscosity, List.of(), gasNodes, (f, gas) -> 0, FluidSolverConfig.WallCondition.FREE_SLIP);
			var result = mechanics.advance(primary, traces, acceleration, dt, 1e-11, 1e-11, 512); velocity = result.pressure().cellVelocity(); var field = result.velocity();
			close(result.viscosity().dissipatedJoules(), 0, 1e-20);
			for (var cell : field) for (var tetra : cell.tetrahedra()) {
				var exact = LiquidCharacteristicVelocity.reconstruct(tetra.geometry(), tetra.outwardFlux());
				for (int a = 0; a < 3; a++) if (!exact[a].equals(LiquidExactPolygon.Fraction.of(velocity[a])))
					throw new AssertionError("Uniform acceleration gained artificial characteristic velocity variation");
			}
			var moved = new LiquidCharacteristicTransport(new LiquidVelocityTrajectory(mesh, field)).advect(0, liquid, inventory, dt, 4096, 128);
			if (moved.size() != 1 || !moved.getFirst().inventory().equals(inventory)) throw new AssertionError("Uniform acceleration changed characteristic inventory or fragmentation");
			liquid = moved.getFirst().geometry(); close(liquid.moments().volume(), inventory.toDouble(), 1e-15);
			close(velocity[1], -9.81 * dt * step, 1e-14); close(liquid.moments().centroid(1), 0.75 - 9.81 * dt * dt * step * (step + 1) / 2, 1e-14);
		}
	}
	private static void wholeVolumeCharacteristicsPreserveGeometry() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var drop = LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75);
		var regions = LiquidPhasePartition.build(root, List.of(drop), 64); var labels = regions.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray();
		var mesh = new LiquidControlVolumeMesh(regions.stream().map(LiquidPhasePartition.Region::cell).toList(), 1, 1000,
				(c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var rates = new cc.ecisr.mmm.units.SignedDyadic[mesh.faces.size()]; var primary = new double[3 * regions.size()]; primary[0] = 1;
		for (int f = 0; f < rates.length; f++) {
			var face = mesh.faces.get(f); rates[f] = labels[face.first()] >= 0 || face.second() >= 0 && labels[face.second()] >= 0
					? cc.ecisr.mmm.units.SignedDyadic.of(face.surface().areaVector().x()) : cc.ecisr.mmm.units.SignedDyadic.ZERO;
		}
		var extension = LiquidInterfaceVelocityExtension.extend(mesh, labels, rates, primary, 1e-11, 512);
		var transport = new LiquidCharacteristicTransport(new LiquidVelocityTrajectory(mesh,
				LiquidControlVolumeVelocity.reconstruct(mesh, extension.volumeRate(), extension.cellVelocity())));
		for (double dt : new double[] {0, 0.2}) {
			var moved = transport.advect(0, drop, cc.ecisr.mmm.units.SignedDyadic.of(0.125), dt, 4096, 128);
			if (moved.size() != 1 || !moved.getFirst().inventory().equals(cc.ecisr.mmm.units.SignedDyadic.of(0.125)))
				throw new AssertionError("Auxiliary velocity faces changed a uniform liquid image");
			var moments = moved.getFirst().geometry().moments(); close(moments.volume(), 0.125, 1e-15); close(moments.centroid(0), 0.5 + dt, 1e-15);
			if (dt == 0 && !moved.getFirst().geometry().faces().equals(drop.faces())) throw new AssertionError("Zero-time transport changed liquid geometry");
			characteristicImageRebuildsSpace(root, moved);
		}
		var cells = LiquidGeometryRefinement.children(root);
		mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		rates = new cc.ecisr.mmm.units.SignedDyadic[mesh.faces.size()]; primary = new double[3 * cells.size()];
		for (int c = 0; c < cells.size(); c++) { primary[3 * c] = mesh.cells.get(c).centroid().y(); primary[3 * c + 1] = 0.1; }
		for (int f = 0; f < rates.length; f++) {
			var face = mesh.faces.get(f); var area = face.surface().areaVector();
			rates[f] = cc.ecisr.mmm.units.SignedDyadic.of(area.x() * mesh.cells.get(face.first()).centroid().y() + area.y() * 0.1);
		}
		transport = new LiquidCharacteristicTransport(new LiquidVelocityTrajectory(mesh, LiquidControlVolumeVelocity.reconstruct(mesh, rates, primary)));
		var source = LiquidPolyhedron.box(0.1, 0.1, 0.1, 0.3, 0.49, 0.3); var inventory = cc.ecisr.mmm.units.SignedDyadic.of(source.moments().volume());
		var moved = transport.advect(0, source, inventory, 0.2, 4096, 128); var moments = LiquidPolyhedron.Moments.ZERO; var total = cc.ecisr.mmm.units.SignedDyadic.ZERO;
		for (var fragment : moved) { moments = moments.add(fragment.geometry().moments()); total = total.add(fragment.inventory()); }
		if (!total.equals(inventory)) throw new AssertionError("Crossing a velocity change lost exact liquid inventory");
		close(moments.volume(), 0.0156, 1e-15); close(moments.centroid(0), 0.25064102564102564, 1e-14);
		close(moments.centroid(1), 0.315, 1e-14); close(moments.centroid(2), 0.2, 1e-14);
		characteristicImageRebuildsSpace(root, moved);
		var oblique = source.clip(1, 2, 3, 1.3);
		moved = transport.advect(0, oblique, cc.ecisr.mmm.units.SignedDyadic.of(oblique.moments().volume()), 0.2, 4096, 128);
		var expected = oblique.clip(0, 1, 0, 0.48).translated(0.05, 0.02, 0).moments().add(
				oblique.clip(0, -1, 0, -0.48).transformed(new double[] {1, 5, 0, 0, 1, 0, 0, 0, 1},
						new LiquidPolyhedron.Point(0, 0, 0), new LiquidPolyhedron.Point(-2.35, 0.02, 0)).moments());
		moments = LiquidPolyhedron.Moments.ZERO; for (var fragment : moved) moments = moments.add(fragment.geometry().moments());
		close(moments.volume(), expected.volume(), 1e-14); close(moments.x(), expected.x(), 1e-14);
		close(moments.y(), expected.y(), 1e-14); close(moments.z(), expected.z(), 1e-14);
		characteristicImageRebuildsSpace(root, moved);
	}
	private static void characteristicImageRebuildsSpace(LiquidGeometryRefinement.Cell root, List<LiquidCharacteristicTransport.Fragment> moved) {
		var remap = new LiquidGeometryRemap(LiquidGeometryRefinement.children(root));
		for (var fragment : moved) {
			var partition = remap.partition(fragment.geometry(), fragment.inventory()); var inventory = cc.ecisr.mmm.units.SignedDyadic.ZERO;
			for (var piece : partition) inventory = inventory.add(piece.inventory());
			if (!inventory.equals(fragment.inventory())) throw new AssertionError("Characteristic image lost exact inventory across refinement");
		}
		var liquids = moved.stream().map(f -> new LiquidGeometryRefinement.Cell(0, 0, 0, 0, f.geometry())).toList();
		var state = LiquidGeometryStateMapper.capture(liquids, 1, java.util.stream.IntStream.range(0, liquids.size()).toArray());
		var restored = LiquidGeometryStateMapper.restore(state); var restoredState = LiquidGeometryStateMapper.capture(restored, 1, state.copyMarkerCells());
		for (int c = 0; c < state.cells().size(); c++) if (LiquidGeometryState.compare(state.cells().get(c), restoredState.cells().get(c)) != 0)
			throw new AssertionError("Characteristic image lost exact geometry across backend handoff");
		var regions = LiquidPhasePartition.build(root, moved.stream().map(LiquidCharacteristicTransport.Fragment::geometry).toList(), 256);
		var mesh = new LiquidControlVolumeMesh(regions.stream().map(LiquidPhasePartition.Region::cell).toList(), 1, 1000,
				(c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var sum = LiquidPolyhedron.Moments.ZERO; for (var cell : mesh.cells) sum = sum.add(cell.geometry().moments());
		var expected = root.geometry().moments(); close(sum.volume(), expected.volume(), 1e-14);
		close(sum.x(), expected.x(), 1e-14); close(sum.y(), expected.y(), 1e-14); close(sum.z(), expected.z(), 1e-14);
		var result = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[3 * mesh.cells.size()],
				new LiquidPolyhedron.Point(0, 0, 0), 0.025, 1e-11, 512);
		for (var rate : result.volumeRate()) if (rate.signum() != 0) throw new AssertionError("Transported geometry lost zero-force equilibrium");
		var velocity = LiquidControlVolumeVelocity.reconstruct(mesh, result.volumeRate(), result.cellVelocity()); new LiquidVelocityTrajectory(mesh, velocity);
	}

	private static void close(double actual, double expected, double tolerance) {
		if (!(Math.abs(actual - expected) <= tolerance)) throw new AssertionError(actual + " differs from " + expected);
	}
	public static void main(String[] args) { validate(); }
}
