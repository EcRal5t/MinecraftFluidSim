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
		if (fragments.length != mesh.cells.size() || maximumFragments <= 0 || maximumFragments < input.cells().size()) throw new IllegalArgumentException("Invalid geometric motion support");
		if (interfaces != null) interfaces.requireInput(mesh, fragments);
		this.input = input;
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
		var payload = new ArrayList<Fraction[]>();
		var integral = new Fraction[source.size()][3]; var sourceVolume = new Fraction[source.size()]; Arrays.fill(sourceVolume, Fraction.ZERO);
		for (var vector : integral) Arrays.fill(vector, Fraction.ZERO);
		for (int c = 0; c < fragments.length; c++) if (fragments[c] >= 0) {
			var volume = LiquidConvexRegion.of(mesh.cells.get(c).geometry()).measure().volume(); var momentum = new Fraction[] {Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
			int owner = previousOwners[fragments[c]]; sourceVolume[owner] = sourceVolume[owner].add(volume);
			for (var tetra : velocity[c].tetrahedra()) {
				var center = tetra.geometry().faces().getFirst().centroidCoordinates(); var rate = tetra.outwardFlux().getFirst();
				for (int a = 0; a < 3; a++) momentum[a] = momentum[a].add(center[a].multiply(rate));
			}
			for (int a = 0; a < 3; a++) integral[owner][a] = integral[owner][a].add(momentum[a]);
			var mean = new Fraction[] {momentum[0].divide(volume), momentum[1].divide(volume), momentum[2].divide(volume)};
			var moved = transport.images(c, mesh.cells.get(c).geometry(), dt, maximumFragments, maximumFaceEvents);
			if (interfaces != null) interfaces.collect(c, moved);
			for (var image : moved) for (var fragment : remap.partitionGeometry(image.geometry().scaled(inverse))) {
				if (cells.size() == maximumFragments) throw new IllegalStateException("Liquid geometric successor exceeds its fragment budget");
				var leaf = nextLeaves.get(fragment.cell()); cells.add(LiquidPhasePartition.Address.of(leaf).cell(fragment.geometry()));
				ownership.add(previousOwners[fragments[c]]);
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
		geometry = LiquidGeometryCompaction.compact(LiquidGeometryStateMapper.capture(cells, input.rootWidthMetres(), markers, groups, transportedVelocity), maximumFragments);
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

	/** Publishes observations and actual geometry only after the complete successor passes inventory and collision checks. */
	void publish(FluidDomain domain) {
		if (domain.liquidGeometryState != input) throw new IllegalStateException("Geometric motion refers to an obsolete liquid input");
		LiquidGeometryStateMapper.requireCompatible(input, domain);
		int count = domain.particles.count(); int[] markers = geometry.copyMarkerCells();
		var motion = new InventoryMotion[geometry.cells().size()]; for (var group : inventoryMotion) motion[group.owner()] = group;
		float[] positions = new float[3 * count], velocities = new float[3 * count];
		for (int p = 0; p < count; p++) for (int a = 0; a < 3; a++) {
			int extent = a == 0 ? domain.sizeX : a == 1 ? domain.sizeY : domain.sizeZ; var group = motion[markers[p]];
			positions[3 * p + a] = Math.min((float) group.positionMetres().coordinate(a), Math.nextDown(extent * domain.config.cellSize()));
			velocities[3 * p + a] = (float) group.velocityMetresPerSecond().coordinate(a);
		}
		var previous = LiquidGeometryInventoryView.of(domain.particles);
		LiquidGeometryStateMapper.requireCompatible(geometry, new LiquidGeometryInventoryView() {
			public int count() { return count; }
			public double volume(int p) { return previous.volume(p); }
			public double mass(int p) { return previous.mass(p); }
			public double position(int p, int a) { return positions[3 * p + a]; }
			public double velocity(int p, int a) { return velocities[3 * p + a]; }
		}, domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.geometry.cutCells.copyMicroMasks());
		for (int p = 0; p < count; p++) {
			if (domain.chemicalParcels != null) domain.chemicalParcels.markDirtyCell(domain.particleCellIndex(p));
			domain.particles.setPositionX(p, positions[3 * p]); domain.particles.setPositionY(p, positions[3 * p + 1]); domain.particles.setPositionZ(p, positions[3 * p + 2]);
			domain.particles.setVelocityX(p, velocities[3 * p]); domain.particles.setVelocityY(p, velocities[3 * p + 1]); domain.particles.setVelocityZ(p, velocities[3 * p + 2]);
			if (domain.chemicalParcels != null) domain.chemicalParcels.markDirtyCell(domain.particleCellIndex(p));
		}
		domain.liquidGeometryState = geometry; domain.gridDirty = true; domain.velocityGridDirty = true;
	}
}
