package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.List;

/** Publishes geometry and carrier observations against unchanged inventory columns. */
final class LiquidGeometryPublication {
	interface Observation {
		int owner();
		LiquidPolyhedron.Point positionMetres();
		LiquidPolyhedron.Point velocityMetresPerSecond();
	}
	private LiquidGeometryPublication() { }
	static void publish(FluidDomain domain, LiquidGeometryState input, LiquidGeometryState geometry, List<? extends Observation> observations) {
		if (domain.liquidGeometryState != input) throw new IllegalStateException("Geometric publication refers to an obsolete liquid input");
		LiquidGeometryStateMapper.requireCompatible(input, domain);
		int count = domain.particles.count(); int[] markers = geometry.copyMarkerCells();
		var motion = new Observation[geometry.cells().size()];
		for (var group : observations) {
			if (group.owner() < 0 || group.owner() >= motion.length || motion[group.owner()] != null) throw new IllegalArgumentException("Invalid geometry inventory observation");
			motion[group.owner()] = group;
		}
		float[] positions = new float[3 * count], velocities = new float[3 * count];
		for (int p = 0; p < count; p++) for (int a = 0; a < 3; a++) {
			int extent = a == 0 ? domain.sizeX : a == 1 ? domain.sizeY : domain.sizeZ; var group = motion[markers[p]];
			if (group == null) throw new IllegalArgumentException("Missing geometry inventory observation");
			positions[3 * p + a] = Math.min((float) group.positionMetres().coordinate(a), Math.nextDown(extent * domain.config.cellSize()));
			velocities[3 * p + a] = (float) group.velocityMetresPerSecond().coordinate(a);
		}
		var previous = LiquidGeometryInventoryView.of(domain.particles);
		LiquidGeometryStateMapper.requireCompatible(geometry, new LiquidGeometryInventoryView() {
			public int count() { return count; }
			public double volume(int p) { return previous.volume(p); }
			public double mass(int p) { return previous.mass(p); }
			public SignedDyadic exactVolume(int p) { return previous.exactVolume(p); }
			public SignedDyadic exactMass(int p) { return previous.exactMass(p); }
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
