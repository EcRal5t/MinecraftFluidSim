package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Private geometric successor preserves inventory groups through characteristic motion and refinement. */
final class LiquidGeometryMotionPlan {
	private interface MotionField {
		Fraction[] mean(int cell);
		List<LiquidPolyhedron> images(int cell);
	}
	record InventoryMotion(int owner, LiquidPolyhedron.Point positionMetres, LiquidPolyhedron.Point velocityMetresPerSecond) implements LiquidGeometryPublication.Observation { }
	final LiquidGeometryState geometry;
	final List<InventoryMotion> inventoryMotion;
	final ResolvedGasInterfaceMotion gasInterfaces;
	private final LiquidGeometryState input;

	LiquidGeometryMotionPlan(LiquidGeometryState input, LiquidControlVolumeMesh mesh, int[] fragments,
			LiquidControlVolumeVelocity.Result[] velocity, List<LiquidGeometryRefinement.Cell> nextLeaves,
			double dt, int maximumFragments, int maximumFaceEvents) {
		this(input, mesh, fragments, velocity, nextLeaves, dt, maximumFragments, maximumFaceEvents, null);
	}
	LiquidGeometryMotionPlan(LiquidGeometryState input, LiquidControlVolumeMesh mesh, int[] fragments,
			LiquidControlVolumeVelocity.Result[] velocity, List<LiquidGeometryRefinement.Cell> nextLeaves,
			double dt, int maximumFragments, int maximumFaceEvents, ResolvedGasInterfaceMotion.Builder interfaces) {
		this(input, mesh, fragments, nextLeaves, maximumFragments, interfaces, characteristic(mesh, velocity, dt, maximumFragments, maximumFaceEvents, interfaces));
	}
	LiquidGeometryMotionPlan(LiquidGeometryState input, LiquidControlVolumeMesh mesh, int[] fragments,
			LiquidConformingVelocity field, LiquidConformingTransport motion, List<LiquidGeometryRefinement.Cell> nextLeaves,
			int maximumFragments, ResolvedGasInterfaceMotion.Builder interfaces) {
		this(input, mesh, fragments, nextLeaves, maximumFragments, interfaces, conforming(mesh, fragments, field, motion, interfaces));
	}
	LiquidGeometryMotionPlan(LiquidGeometryPhaseInput input, LiquidConformingVelocity field, LiquidConformingTransport motion,
			List<LiquidGeometryRefinement.Cell> nextLeaves, int maximumFragments, ResolvedGasInterfaceMotion.Builder interfaces) {
		this(input.liquidGeometry, input.mesh, input.fragments, nextLeaves, maximumFragments, interfaces,
				conforming(input.mesh, input.fragments, field, motion, interfaces), input.liquidGeometry.copyControlCells());
		if (input.mechanicalSupport == null) throw new IllegalArgumentException("Mechanical motion requires bound control-cell support");
	}
	LiquidGeometryMotionPlan(LiquidGeometryPhaseInput input, LiquidMechanicalFaceInput mechanical, LiquidConformingVelocity field,
			LiquidConformingTransport motion, List<LiquidGeometryRefinement.Cell> nextLeaves, int maximumFragments, ResolvedGasInterfaceMotion.Builder interfaces) {
		this(input.liquidGeometry, mechanical.mesh, mechanical.fragments, nextLeaves, maximumFragments, interfaces,
				conforming(mechanical.mesh, mechanical.fragments, field, motion, interfaces), input.liquidGeometry.copyControlCells());
		if (input.mechanicalSupport == null) throw new IllegalArgumentException("Mechanical motion requires bound control-cell support");
	}
	private LiquidGeometryMotionPlan(LiquidGeometryState input, LiquidControlVolumeMesh mesh, int[] fragments,
			List<LiquidGeometryRefinement.Cell> nextLeaves, int maximumFragments, ResolvedGasInterfaceMotion.Builder interfaces, MotionField field) {
		this(input, mesh, fragments, nextLeaves, maximumFragments, interfaces, field, null);
	}
	private LiquidGeometryMotionPlan(LiquidGeometryState input, LiquidControlVolumeMesh mesh, int[] fragments,
			List<LiquidGeometryRefinement.Cell> nextLeaves, int maximumFragments, ResolvedGasInterfaceMotion.Builder interfaces, MotionField field, int[] sourceControls) {
		if (fragments.length != mesh.cells.size() || maximumFragments <= 0 || maximumFragments < input.cells().size()) throw new IllegalArgumentException("Invalid geometric motion support");
		if (interfaces != null) interfaces.requireInput(mesh, fragments);
		this.input = input;
		var source = LiquidGeometryStateMapper.restore(input); var width = Fraction.of(input.rootWidthMetres()); var inverse = Fraction.of(1).divide(width);
		var coverage = new LiquidExactPolygon.Measure[source.size()]; Arrays.fill(coverage, LiquidConvexRegion.EMPTY.measure());
		var expectedSupport = new LiquidExactPolygon.Measure[source.size()]; Arrays.fill(expectedSupport, LiquidConvexRegion.EMPTY.measure());
		for (int f = 0; f < source.size(); f++) {
			int support = sourceControls == null ? f : sourceControls[f];
			expectedSupport[support] = expectedSupport[support].add(source.get(f).geometry().scaled(width).measure());
		}
		for (int c = 0; c < fragments.length; c++) {
			int f = fragments[c]; if (f < -1 || f >= source.size()) throw new IllegalArgumentException("Phase cell has no liquid fragment");
			if (f >= 0) { int support = sourceControls == null ? f : sourceControls[f]; coverage[support] = coverage[support].add(mesh.cells.get(c).geometry().measure()); }
		}
		for (int f = 0; f < source.size(); f++) if (!coverage[f].same(expectedSupport[f]))
			throw new IllegalArgumentException("Mechanical geometry differs from submitted liquid geometry");
		var remap = new LiquidGeometryRemap(nextLeaves);
		var cells = new ArrayList<LiquidGeometryRefinement.Cell>(); var ownership = new ArrayList<Integer>(); int[] previousOwners = input.copyFragmentOwners();
		var controls = new ArrayList<Integer>();
		var payload = new ArrayList<Fraction[]>();
		var integral = new Fraction[source.size()][3]; var sourceVolume = new Fraction[source.size()]; Arrays.fill(sourceVolume, Fraction.ZERO);
		for (var vector : integral) Arrays.fill(vector, Fraction.ZERO);
		for (int c = 0; c < fragments.length; c++) if (fragments[c] >= 0) {
			var volume = LiquidConvexRegion.of(mesh.cells.get(c).geometry()).measure().volume(); var mean = field.mean(c);
			int owner = previousOwners[fragments[c]]; sourceVolume[owner] = sourceVolume[owner].add(volume);
			for (int a = 0; a < 3; a++) integral[owner][a] = integral[owner][a].add(mean[a].multiply(volume));
			for (var image : field.images(c)) for (var fragment : remap.partitionGeometry(image.scaled(inverse))) {
				if (cells.size() == maximumFragments) throw new IllegalStateException("Liquid geometric successor exceeds its fragment budget");
				var leaf = nextLeaves.get(fragment.cell()); cells.add(LiquidPhasePartition.Address.of(leaf).cell(fragment.geometry()));
				ownership.add(previousOwners[fragments[c]]);
				controls.add(c);
				payload.add(mean);
			}
		}
		var first = new HashMap<Integer, Integer>(); int[] groups = new int[cells.size()];
		for (int f = 0; f < groups.length; f++) { first.putIfAbsent(ownership.get(f), f); groups[f] = first.get(ownership.get(f)); }
		int[] markers = input.copyMarkerCells(); for (int p = 0; p < markers.length; p++) {
			var owner = first.get(markers[p]); if (owner == null) throw new IllegalStateException("Liquid motion lost an inventory group"); markers[p] = owner;
		}
		var transportedVelocity = new double[3 * cells.size()];
		for (int f = 0; f < cells.size(); f++) for (int a = 0; a < 3; a++) transportedVelocity[3 * f + a] = payload.get(f)[a].rounded();
		var firstControl = new HashMap<Integer, Integer>(); int[] controlCells = new int[cells.size()];
		for (int f = 0; f < cells.size(); f++) { firstControl.putIfAbsent(controls.get(f), f); controlCells[f] = firstControl.get(controls.get(f)); }
		geometry = LiquidGeometryCompaction.compact(LiquidGeometryStateMapper.capture(cells, input.rootWidthMetres(), markers, groups, transportedVelocity, controlCells), maximumFragments);
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
		gasInterfaces = interfaces == null ? null : interfaces.finish();
	}
	private static MotionField characteristic(LiquidControlVolumeMesh mesh, LiquidControlVolumeVelocity.Result[] velocity,
			double dt, int maximumFragments, int maximumEvents, ResolvedGasInterfaceMotion.Builder interfaces) {
		var transport = new LiquidCharacteristicTransport(new LiquidVelocityTrajectory(mesh, velocity));
		return new MotionField() {
			public Fraction[] mean(int c) {
				var integral = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
				for (var tetra : velocity[c].tetrahedra()) {
					var center = tetra.geometry().faces().getFirst().centroidCoordinates(); var q = tetra.outwardFlux().getFirst();
					for (int a = 0; a < 3; a++) integral[a] = integral[a].add(center[a].multiply(q));
				}
				for (int a = 0; a < 3; a++) integral[a] = integral[a].divide(mesh.cells.get(c).geometry().measure().volume()); return integral;
			}
			public List<LiquidPolyhedron> images(int c) {
				var moved = transport.images(c, mesh.cells.get(c).geometry(), dt, maximumFragments, maximumEvents);
				if (interfaces != null) interfaces.collect(c, moved); return moved.stream().map(LiquidCharacteristicTransport.Image::geometry).toList();
			}
		};
	}
	private static MotionField conforming(LiquidControlVolumeMesh mesh, int[] fragments, LiquidConformingVelocity field,
			LiquidConformingTransport motion, ResolvedGasInterfaceMotion.Builder interfaces) {
		if (motion.mesh != mesh || motion.field != field || !Arrays.equals(motion.liquid, fragments))
			throw new IllegalArgumentException("Continuous liquid transport refers to another mechanical input");
		if (interfaces != null) interfaces.collect(motion);
		var images = new HashMap<Integer, ArrayList<LiquidConformingTransport.Image>>();
		for (var image : motion.images) images.computeIfAbsent(image.cell(), ignored -> new ArrayList<>()).add(image);
		return new MotionField() {
			public Fraction[] mean(int c) {
				return field.meanVelocity(c);
			}
			public List<LiquidPolyhedron> images(int c) {
				var local = images.get(c); if (local == null) throw new IllegalArgumentException("Continuous transport has no liquid cell support");
				var map = local.getFirst().affineMap();
				var geometry = mesh.cells.get(c).geometry();
				var faces = LiquidExactPolygon.faces(geometry); var vertices = faces.stream().flatMap(face -> face.vertices().stream()).distinct().toList();
				boolean convex = faces.stream().allMatch(face -> vertices.stream().allMatch(vertex -> vertex.distance(face.coefficients()).signum() <= 0));
				if (convex && map.size() == 12 && local.stream().allMatch(image -> image.affineMap().equals(map)))
					return List.of(LiquidConvexRegion.of(mesh.cells.get(c).geometry()).transformed(map).geometry());
				return local.stream().map(image -> image.geometry().geometry()).toList();
			}
		};
	}

	/** Publishes observations and actual geometry only after the complete successor passes inventory and collision checks. */
	void publish(FluidDomain domain) {
		LiquidGeometryPublication.publish(domain, input, geometry, inventoryMotion);
	}
}
