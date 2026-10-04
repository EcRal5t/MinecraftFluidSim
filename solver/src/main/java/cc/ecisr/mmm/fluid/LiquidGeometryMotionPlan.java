package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Private geometric successor preserves inventory groups through characteristic motion and refinement. */
final class LiquidGeometryMotionPlan {
	record InventoryMotion(int owner, LiquidPolyhedron.Point positionMetres, LiquidPolyhedron.Point velocityMetresPerSecond) { }
	final LiquidGeometryState geometry;
	final List<InventoryMotion> inventoryMotion;

	LiquidGeometryMotionPlan(LiquidGeometryState input, LiquidControlVolumeMesh mesh, int[] fragments,
			LiquidControlVolumeVelocity.Result[] velocity, List<LiquidGeometryRefinement.Cell> nextLeaves,
			double dt, int maximumFragments, int maximumFaceEvents) {
		if (fragments.length != mesh.cells.size() || maximumFragments <= 0 || maximumFragments < input.cells().size()) throw new IllegalArgumentException("Invalid geometric motion support");
		var source = LiquidGeometryStateMapper.restore(input); var width = Fraction.of(input.rootWidthMetres()); var inverse = Fraction.of(1).divide(width);
		var coverage = new LiquidExactPolygon.Measure[source.size()]; Arrays.fill(coverage, LiquidConvexRegion.EMPTY.measure());
		for (int c = 0; c < fragments.length; c++) {
			int f = fragments[c]; if (f < -1 || f >= source.size()) throw new IllegalArgumentException("Phase cell has no liquid fragment");
			if (f >= 0) coverage[f] = coverage[f].add(LiquidConvexRegion.of(mesh.cells.get(c).geometry()).measure());
		}
		for (int f = 0; f < source.size(); f++) if (!coverage[f].same(LiquidConvexRegion.of(source.get(f).geometry().scaled(width)).measure()))
			throw new IllegalArgumentException("Mechanical geometry differs from submitted liquid geometry");
		var transport = new LiquidCharacteristicTransport(new LiquidVelocityTrajectory(mesh, velocity)); var remap = new LiquidGeometryRemap(nextLeaves);
		var cells = new ArrayList<LiquidGeometryRefinement.Cell>(); var ownership = new ArrayList<Integer>(); int[] previousOwners = input.copyFragmentOwners();
		var integral = new Fraction[source.size()][3]; var sourceVolume = new Fraction[source.size()]; Arrays.fill(sourceVolume, Fraction.ZERO);
		for (var vector : integral) Arrays.fill(vector, Fraction.ZERO);
		for (int c = 0; c < fragments.length; c++) if (fragments[c] >= 0) {
			int owner = previousOwners[fragments[c]]; sourceVolume[owner] = sourceVolume[owner].add(LiquidConvexRegion.of(mesh.cells.get(c).geometry()).measure().volume());
			for (var tetra : velocity[c].tetrahedra()) {
				var center = tetra.geometry().faces().getFirst().centroidCoordinates(); var rate = tetra.outwardFlux().getFirst();
				for (int a = 0; a < 3; a++) integral[owner][a] = integral[owner][a].add(center[a].multiply(rate));
			}
			var moved = transport.advectGeometry(c, mesh.cells.get(c).geometry(), dt, maximumFragments, maximumFaceEvents);
			for (var image : moved) for (var fragment : remap.partitionGeometry(image.scaled(inverse))) {
				if (cells.size() == maximumFragments) throw new IllegalStateException("Liquid geometric successor exceeds its fragment budget");
				var leaf = nextLeaves.get(fragment.cell()); cells.add(LiquidPhasePartition.Address.of(leaf).cell(fragment.geometry()));
				ownership.add(previousOwners[fragments[c]]);
			}
		}
		var first = new HashMap<Integer, Integer>(); int[] groups = new int[cells.size()];
		for (int f = 0; f < groups.length; f++) { first.putIfAbsent(ownership.get(f), f); groups[f] = first.get(ownership.get(f)); }
		int[] markers = input.copyMarkerCells(); for (int p = 0; p < markers.length; p++) {
			var owner = first.get(markers[p]); if (owner == null) throw new IllegalStateException("Liquid motion lost an inventory group"); markers[p] = owner;
		}
		geometry = LiquidGeometryStateMapper.capture(cells, input.rootWidthMetres(), markers, groups);
		var restored = LiquidGeometryStateMapper.restore(geometry); int[] owners = geometry.copyFragmentOwners();
		int[] originalMarkers = input.copyMarkerCells(), nextMarkers = geometry.copyMarkerCells(), oldGroup = new int[restored.size()]; Arrays.fill(oldGroup, -1);
		for (int p = 0; p < originalMarkers.length; p++) oldGroup[nextMarkers[p]] = originalMarkers[p];
		var measures = new LiquidExactPolygon.Measure[restored.size()]; Arrays.fill(measures, LiquidConvexRegion.EMPTY.measure());
		for (int f = 0; f < restored.size(); f++) measures[owners[f]] = measures[owners[f]].add(LiquidConvexRegion.of(restored.get(f).geometry()).measure());
		var positions = new ArrayList<InventoryMotion>(); var widthCubed = width.multiply(width).multiply(width);
		for (int f = 0; f < restored.size(); f++) if (owners[f] == f) {
			var center = measures[f].centroidCoordinates(); int oldOwner = oldGroup[f];
			if (!measures[f].volume().equals(sourceVolume[oldOwner].divide(widthCubed))) throw new IllegalStateException("Liquid motion changed inventory-group volume");
			positions.add(new InventoryMotion(f, new LiquidPolyhedron.Point(center[0].multiply(width).rounded(), center[1].multiply(width).rounded(), center[2].multiply(width).rounded()),
					new LiquidPolyhedron.Point(integral[oldOwner][0].divide(sourceVolume[oldOwner]).rounded(), integral[oldOwner][1].divide(sourceVolume[oldOwner]).rounded(), integral[oldOwner][2].divide(sourceVolume[oldOwner]).rounded())));
		}
		inventoryMotion = List.copyOf(positions);
	}
}
