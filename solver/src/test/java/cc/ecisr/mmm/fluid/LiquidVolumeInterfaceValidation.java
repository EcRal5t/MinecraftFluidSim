package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.List;

/** Interface representation preserves inventory and measures its geometric approximation. */
final class LiquidVolumeInterfaceValidation {
	static void validate() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		for (var open : List.of(cube, cube.clip(1, 2, 3, 3))) for (double[] cut : new double[][]{
				{1, 0, 0, 0.375}, {1, 1, 1, 1.125}, {-2, 3, 1, 0.7}, {0.125, 1, -0.75, 0.37}}) {
			var normal = new LiquidPolyhedron.Point(cut[0], cut[1], cut[2]);
			var planar = open.clip(cut[0], cut[1], cut[2], cut[3]); var target = planar.measure();
			if (target.signum() == 0 || target.volume().equals(open.measure().volume())) continue;
			double previous = Double.POSITIVE_INFINITY;
			for (int bits : new int[]{12, 24, 48}) {
				var reconstruction = LiquidVolumeInterface.reconstruct(open, target.volume(), normal, bits);
				var geometry = reconstruction.geometry; var overlap = LiquidConvexRegion.of(geometry);
				for (var plane : open.planes()) overlap = overlap.clip(plane);
				if (!overlap.measure().same(geometry.measure())) throw new AssertionError("Finite interface left its exact open space");
				if (!geometry.measure().volume().equals(target.volume())) throw new AssertionError("Finite interface lost exact inventory volume");
				overlap = LiquidConvexRegion.of(geometry);
				for (var plane : planar.planes()) overlap = overlap.clip(plane);
				var difference = target.volume().subtract(overlap.measure().volume()).multiply(Fraction.of(2));
				if (difference.signum() < 0 || difference.subtract(reconstruction.symmetricDifferenceVolumeBound).signum() > 0)
					throw new AssertionError("Finite interface understated its symmetric-difference volume");
				var error = reconstruction.centroidError(target);
				if (error > previous / 1024 || !Double.isFinite(reconstruction.normalPositionError))
					throw new AssertionError("Interface first moment did not converge with coordinate precision");
				previous = error;
				// The packed state independently requires closed, convex, planar facets and exact occupancy.
				var cell = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, geometry);
				var restored = LiquidGeometryStateMapper.restore(LiquidGeometryStateMapper.capture(List.of(cell), 1, new int[]{0}));
				if (!restored.getFirst().geometry().measure().same(geometry.measure())) throw new AssertionError("Finite interface lost its spatial moments during packing");
			}
		}
		for (var quantity : List.of(new Fraction(java.math.BigInteger.ONE, java.math.BigInteger.valueOf(3)),
				Fraction.of(1e-30), Fraction.of(0.999999))) {
			var source = new LiquidGeometrySourcePlan(List.of(cube), quantity, 1);
			if (source.geometry.size() != 1 || !source.geometry.getFirst().measure().volume().equals(quantity))
				throw new AssertionError("Source interface created auxiliary volume fragments");
		}
		if (LiquidVolumeInterface.reconstruct(cube, Fraction.ZERO, new LiquidPolyhedron.Point(1, 0, 0), 8).geometry.measure().signum() != 0
				|| LiquidVolumeInterface.reconstruct(cube, cube.measure().volume(), new LiquidPolyhedron.Point(1, 0, 0), 8).geometry != cube)
			throw new AssertionError("Empty or full interface reconstruction changed its occupancy");
		momentOrientationUsesTheSameVolume(cube);
		spatialRefinementResolvesClosedInterfaces(cube);
		adaptiveReconstructionEnforcesItsSpatialError(cube);
	}
	private static void momentOrientationUsesTheSameVolume(LiquidPolyhedron cube) {
		var normal = new LiquidPolyhedron.Point(1, 2, 3); var planar = cube.clip(1, 2, 3, 2.3); var target = planar.measure();
		var represented = LiquidVolumeInterface.reconstruct(cube, target.volume(), normal, 48); var derivative = represented.centroidDerivative();
		for (int i = 0; i < 3; i++) {
			var kernel = Fraction.ZERO;
			for (int j = 0; j < 3; j++) kernel = kernel.add(derivative[i][j].multiply(Fraction.of(normal.coordinate(j))));
			if (kernel.signum() != 0) throw new AssertionError("Orientation derivative responded to normal scaling");
			for (int j = 0; j < 3; j++) if (!derivative[i][j].equals(derivative[j][i])) throw new AssertionError("Cap covariance derivative lost symmetry");
		}
		for (int axis = 0; axis < 3; axis++) {
			double h = Math.scalb(1.0, -16); double[] plus = {1, 2, 3}, minus = plus.clone(); plus[axis] += h; minus[axis] -= h;
			var a = LiquidVolumeInterface.reconstruct(cube, target.volume(), new LiquidPolyhedron.Point(plus[0], plus[1], plus[2]), 48).geometry.centroidCoordinates();
			var b = LiquidVolumeInterface.reconstruct(cube, target.volume(), new LiquidPolyhedron.Point(minus[0], minus[1], minus[2]), 48).geometry.centroidCoordinates();
			for (int i = 0; i < 3; i++) if (Math.abs(a[i].subtract(b[i]).divide(Fraction.of(2 * h)).rounded() - derivative[i][axis].rounded()) > 1e-9)
				throw new AssertionError("Analytic fixed-volume centroid derivative differs from planar cuts");
		}
		for (var initial : List.of(new LiquidPolyhedron.Point(1, 0, 0), new LiquidPolyhedron.Point(0, 0, 1), new LiquidPolyhedron.Point(3, 2, 1))) {
			var fitted = LiquidMomentInterface.reconstruct(cube, target, initial, 48, 1e-9, 24);
			if (fitted.centroidError() > 1e-9 || !fitted.interfaceGeometry().geometry.measure().volume().equals(target.volume()) || fitted.evaluations() > 24)
				throw new AssertionError("Moment-of-fluid did not recover a planar interface within its bounded work budget: " + fitted);
		}
		var droplet = LiquidPolyhedron.box(0.4, 0.4, 0.4, 0.6, 0.6, 0.6);
		var unresolved = LiquidMomentInterface.reconstruct(cube, droplet.measure(), new LiquidPolyhedron.Point(0, 1, 0), 48, 1e-6, 8);
		if (!(unresolved.centroidError() > 1e-6) || unresolved.evaluations() > 8 || !unresolved.interfaceGeometry().geometry.measure().volume().equals(droplet.measure().volume()))
			throw new AssertionError("A subcell interface hid its unresolved centroid or lost inventory");
	}
	private record Leaf(LiquidGeometryRefinement.Cell open, LiquidPolyhedron liquid) { }
	private static void spatialRefinementResolvesClosedInterfaces(LiquidPolyhedron cube) {
		var droplet = LiquidPolyhedron.box(0.4, 0.4, 0.4, 0.6, 0.6, 0.6);
		var leaves = List.of(new Leaf(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube), droplet));
		double previousMoment = Double.POSITIVE_INFINITY, previousDifference = previousMoment;
		double initialMoment = 0, initialDifference = 0;
		for (int level = 0; level < 4; level++) {
			var exactVolume = Fraction.ZERO; var difference = Fraction.ZERO; double moment = 0;
			for (var leaf : leaves) {
				var target = leaf.liquid.measure(); var displacement = leaf.open.geometry().centroid().subtract(leaf.liquid.centroid());
				if (displacement.dot(displacement) == 0) displacement = new LiquidPolyhedron.Point(0, 1, 0);
				var result = LiquidMomentInterface.reconstruct(leaf.open.geometry(), target, displacement, 40, 1e-9, 16);
				exactVolume = exactVolume.add(result.interfaceGeometry().geometry.measure().volume());
				moment += target.volume().rounded() * result.centroidError();
				var overlap = LiquidConvexRegion.of(result.interfaceGeometry().geometry);
				for (var plane : leaf.liquid.planes()) overlap = overlap.clip(plane);
				difference = difference.add(target.volume().subtract(overlap.measure().volume()).multiply(Fraction.of(2)));
			}
			if (!exactVolume.equals(droplet.measure().volume())) throw new AssertionError("Spatial reconstruction lost the exact closed-interface volume");
			// Refinement changes the approximation when a new leaf face enters the interface support.
			if (moment > previousMoment || difference.rounded() > previousDifference)
				throw new AssertionError("Closed-interface centroid and occupancy defects did not decrease under spatial refinement: level=" + level
						+ " moment=" + moment + " previous=" + previousMoment + " difference=" + difference.rounded() + " previous=" + previousDifference);
			if (level == 0) { initialMoment = moment; initialDifference = difference.rounded(); }
			previousMoment = moment; previousDifference = difference.rounded();
			var refined = new java.util.ArrayList<Leaf>();
			for (var leaf : leaves) for (var child : LiquidGeometryRefinement.children(leaf.open)) {
				var occupied = LiquidConvexRegion.of(leaf.liquid); for (var plane : child.geometry().planes()) occupied = occupied.clip(plane);
				if (occupied.hasVolume()) refined.add(new Leaf(child, occupied.geometry()));
			}
			leaves = List.copyOf(refined);
		}
		if (!(previousMoment < initialMoment / 8) || !(previousDifference < initialDifference / 2))
			throw new AssertionError("Resolved leaf geometry did not reduce the closed-interface spatial errors");
	}
	private static void adaptiveReconstructionEnforcesItsSpatialError(LiquidPolyhedron cube) {
		var open = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube);
		var shape = LiquidPolyhedron.box(0.4, 0.4, 0.4, 0.6, 0.6, 0.6);
		var budget = new LiquidMomentReconstruction.Budget(1024, 4096, 8);
		var tolerance = new LiquidMomentReconstruction.Tolerance(0.01, 0.03, 40);
		var result = LiquidMomentReconstruction.reconstruct(open, List.of(shape), new LiquidPolyhedron.Point(0, 1, 0), tolerance, budget);
		if (result.leaves().size() <= 1 || result.leaves().size() > budget.maximumLeaves() || result.evaluations() > budget.maximumEvaluations()
				|| !result.sourceMeasure().same(shape.measure()) || !result.representedMeasure().volume().equals(shape.measure().volume())
				|| result.maximumCentroidError() > tolerance.centroidDistance()) throw new AssertionError("Adaptive interface reconstruction lost its declared geometry or work limits");
		var subdivided = LiquidGeometryRefinement.children(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, shape)).stream().map(LiquidGeometryRefinement.Cell::geometry).toList();
		var equivalent = LiquidMomentReconstruction.reconstruct(open, subdivided, new LiquidPolyhedron.Point(0, 1, 0), tolerance, budget);
		if (result.leaves().size() != equivalent.leaves().size() || !result.representedMeasure().same(equivalent.representedMeasure())
				|| !result.symmetricDifferenceVolume().equals(equivalent.symmetricDifferenceVolume()) || result.evaluations() != equivalent.evaluations())
			throw new AssertionError("Auxiliary source partitions changed the reconstructed interface or refinement decisions");
		var coverage = LiquidConvexRegion.EMPTY.measure(); var differences = Fraction.ZERO;
		var cells = new java.util.ArrayList<LiquidGeometryRefinement.Cell>();
		for (var leaf : result.leaves()) {
			coverage = coverage.add(leaf.open().geometry().measure()); cells.add(leaf.open());
			var original = LiquidConvexRegion.of(shape); for (var plane : leaf.open().geometry().planes()) original = original.clip(plane);
			var represented = leaf.liquid().measure();
			if (!represented.volume().equals(original.measure().volume())) throw new AssertionError("Adaptive reconstruction changed an exact leaf inventory");
			if (!original.hasVolume()) continue;
			var overlap = original; for (var plane : leaf.liquid().planes()) overlap = overlap.clip(plane);
			var difference = original.measure().volume().subtract(overlap.measure().volume()).multiply(Fraction.of(2));
			if (difference.multiply(Fraction.of(leaf.open().width())).divide(original.measure().volume()).subtract(Fraction.of(tolerance.occupancyErrorLength())).signum() > 0)
				throw new AssertionError("Adaptive reconstruction accepted an unresolved occupancy defect");
			differences = differences.add(difference);
		}
		LiquidPhasePartition.requireDisjoint(cells);
		if (!coverage.same(cube.measure()) || !differences.equals(result.symmetricDifferenceVolume())) throw new AssertionError("Adaptive leaves lost complete open-space coverage or understated occupied-space error");
		for (var exhausted : List.of(new LiquidMomentReconstruction.Budget(1, 4096, 8), new LiquidMomentReconstruction.Budget(1024, 1, 1))) {
			try {
				LiquidMomentReconstruction.reconstruct(open, List.of(shape), new LiquidPolyhedron.Point(0, 1, 0), tolerance, exhausted);
				throw new AssertionError("An exhausted reconstruction budget returned unresolved geometry");
			} catch (IllegalStateException expected) {
				var reason = exhausted.maximumLeaves() == 1 ? "spatial leaf budget" : "evaluation budget";
				if (!expected.getMessage().contains(reason)) throw new AssertionError("Reconstruction rejected for a reason unrelated to the exhausted budget", expected);
			}
		}
	}
}
