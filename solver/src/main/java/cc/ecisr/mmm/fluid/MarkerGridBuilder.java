package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.compute.CpuWorkBatch;
import java.util.Arrays;

/**
 * Rebuilds the derived marker grid from authoritative marker columns. Cell inventories and each
 * MAC axis retain marker ordinal accumulation order; the three independent face arrays can
 * be scattered concurrently after the cell index is complete.
 */
final class MarkerGridBuilder {
	private MarkerGridBuilder() { }

	static void build(FluidDomain domain) {
		boolean conservativeVolume = CellLiquidVolumeTransport.enabled();
		domain.closedLiquidVolume.prepare();
		domain.carrierPropertiesWorkspace.invalidate();
		MarkerGridIndex grid = domain.markerGrid;
		Arrays.fill(grid.markerCount, 0);
		Arrays.fill(grid.cellMarkerHead, -1);
		Arrays.fill(grid.cellMarkerTail, -1);
		Arrays.fill(grid.exactCellMarkerVolume, 0.0);
		Arrays.fill(grid.exactCellMarkerMass, 0.0);
		Arrays.fill(grid.cellDensity, domain.config.density());
		Arrays.fill(grid.cellPhaseHead, -1);
		grid.phaseEntryCount = 0;
		if (domain.liquidCarrierMaterialByCell != null) {
			Arrays.fill(domain.liquidCarrierMaterialByCell, -1);
			Arrays.fill(domain.liquidCarrierVolumeByCell, -1.0);
		}
		for (int i = 0; i < domain.particles.count(); i++) {
			float positionX = domain.particles.positionX(i);
			float positionY = domain.particles.positionY(i);
			float positionZ = domain.particles.positionZ(i);
			float particleVolume = domain.particles.volume(i);
			double particleMass = domain.particles.mass(i);
			int materialHandle = domain.particles.materialHandle(i);
			int particleCell = domain.particleCellIndex(positionX, positionY, positionZ);
			int previous = grid.cellMarkerTail[particleCell];
			grid.particleCellPrevious[i] = previous;
			grid.particleCellNext[i] = -1;
			if (previous < 0) grid.cellMarkerHead[particleCell] = i;
			else grid.particleCellNext[previous] = i;
			grid.cellMarkerTail[particleCell] = i;
			grid.markerCount[particleCell]++;
			grid.exactCellMarkerMass[particleCell] += particleMass;
			grid.addCellPhaseVolume(particleCell, domain.particles.phaseLabel(i), particleVolume);
			if (domain.liquidCarrierMaterialByCell != null
					&& (particleVolume > domain.liquidCarrierVolumeByCell[particleCell]
					|| particleVolume == domain.liquidCarrierVolumeByCell[particleCell]
					&& materialHandle < domain.liquidCarrierMaterialByCell[particleCell])) {
				domain.liquidCarrierVolumeByCell[particleCell] = particleVolume;
				domain.liquidCarrierMaterialByCell[particleCell] = materialHandle;
			}
			if (!conservativeVolume) domain.splatCenter(positionX, positionY, positionZ, particleVolume);
			if (!conservativeVolume) domain.splatPressureVolume(positionX, positionY, positionZ, particleVolume);
		}
		grid.exactCellVolumeInventory = ExactCellVolumeSums.sum(domain.particles.directArrays().volume,
				domain.particles.count(), grid.cellMarkerHead, grid.particleCellNext);
		for (int cell = 0; cell < grid.cellCount; cell++) if (grid.markerCount[cell] > 0) {
			grid.exactCellMarkerVolume[cell] = grid.exactCellVolumeInventory[cell].toDouble();
			domain.freeSurfaceGeometry.addExactVolume(cell, (float) grid.exactCellMarkerVolume[cell]);
			if (conservativeVolume) {
				domain.projection.pressureVolume[cell] = grid.exactCellMarkerVolume[cell];
				domain.volume[cell] = (float) grid.exactCellMarkerVolume[cell];
			}
			grid.cellDensity[cell] = grid.exactCellMarkerMass[cell] / grid.exactCellMarkerVolume[cell];
			if (!(Double.isFinite(grid.cellDensity[cell]) && grid.cellDensity[cell] > 0.0)) {
				throw new IllegalStateException("Marker inventory produced invalid cell density");
			}
		}
	}

	static void scatterVelocities(FluidDomain domain) {
		if (domain.pressureSystem.usesMarkerPressure()) { scatterMarkerVelocities(domain); return; }
		CpuWorkBatch.run(3, domain.particles.count() >= 2_048 ? 1 : 3, (from, until) -> {
			for (int axis = from; axis < until; axis++) scatterAxis(domain, axis);
		});
	}

	/** Uses the pressure map for both the incoming velocity and subsequent marker publications. */
	private static void scatterMarkerVelocities(FluidDomain domain) {
		CellAffineVelocity basis = CellAffineVelocity.capture(domain);
		CellAffineMacTransfer transfer = CellAffineMacTransfer.capture(domain, basis, LiquidPressureGeometry.capture(domain));
		double[][] markerVelocity = new double[3][domain.particles.count()];
		for (int marker = 0; marker < domain.particles.count(); marker++) {
			markerVelocity[0][marker] = domain.particles.velocityX(marker);
			markerVelocity[1][marker] = domain.particles.velocityY(marker);
			markerVelocity[2][marker] = domain.particles.velocityZ(marker);
		}
		double[][] coefficients = new double[3][4 * domain.cellCount];
		basis.fit(markerVelocity, coefficients);
		double[][] mapped = {new double[domain.mac.velocityX.length], new double[domain.mac.velocityY.length], new double[domain.mac.velocityZ.length]};
		transfer.apply(coefficients, mapped);
		var field = new CellAffineVelocityField(basis, coefficients);
		for (int axis = 0; axis < 3; axis++) {
			float[] velocity = axis == 0 ? domain.mac.velocityX : axis == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
			float[] flags = axis == 0 ? domain.mac.velocityWeightX : axis == 1 ? domain.mac.velocityWeightY : domain.mac.velocityWeightZ;
			double[] mass = axis == 0 ? domain.mac.p2gWeightX : axis == 1 ? domain.mac.p2gWeightY : domain.mac.p2gWeightZ;
			scatterMassAxis(domain, axis, velocity, flags, mass, domain.config.cellSize());
			for (int face = 0; face < velocity.length; face++) {
				if (!Float.isFinite((float) mapped[axis][face])) throw new IllegalStateException("Unrepresentable marker MAC velocity");
				velocity[face] = (float) mapped[axis][face];
				flags[face] = transfer.wetArea(axis, face) > 0 ? 1 : 0;
			}
		}
		domain.pressureSystem.updateMarkerVelocityField(field);
	}

	private static void scatterAxis(FluidDomain domain, int axis) {
		float[] sums = axis == 0 ? domain.mac.velocityX : axis == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
		float[] weights = axis == 0 ? domain.mac.velocityWeightX : axis == 1 ? domain.mac.velocityWeightY : domain.mac.velocityWeightZ;
		double[] retained = axis == 0 ? domain.mac.p2gWeightX : axis == 1 ? domain.mac.p2gWeightY : domain.mac.p2gWeightZ;
		float h = domain.config.cellSize();
		if (domain.variableDensityActive) {
			scatterMassAxis(domain, axis, sums, weights, retained, h); return;
		}
		for (int i = 0; i < domain.particles.count(); i++) {
			float transferWeight = domain.particles.volume(i);
			float velocity = axis == 0 ? domain.particles.velocityX(i) : axis == 1 ? domain.particles.velocityY(i) : domain.particles.velocityZ(i);
			domain.splatFaceField(sums, weights, axis, domain.particles.positionX(i) / h,
					domain.particles.positionY(i) / h, domain.particles.positionZ(i) / h, velocity, transferWeight);
		}
		for (int face = 0; face < retained.length; face++) retained[face] = weights[face];
	}

	private static void scatterMassAxis(FluidDomain domain, int axis, float[] velocities, float[] flags,
			double[] mass, float h) {
		double[] means = domain.mac.scatterMeans(axis, mass.length);
		Arrays.fill(means, 0); Arrays.fill(mass, 0);
		int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0), nz = domain.sizeZ + (axis == 2 ? 1 : 0);
		for (int marker = 0; marker < domain.particles.count(); marker++) {
			float gx = Math.clamp(domain.particles.positionX(marker) / h - (axis == 0 ? 0 : .5F), 0, nx - 1);
			float gy = Math.clamp(domain.particles.positionY(marker) / h - (axis == 1 ? 0 : .5F), 0, ny - 1);
			float gz = Math.clamp(domain.particles.positionZ(marker) / h - (axis == 2 ? 0 : .5F), 0, nz - 1);
			int x = (int) gx, y = (int) gy, z = (int) gz;
			float fx = gx - x, fy = gy - y, fz = gz - z;
			int endX = Math.min(1, nx - 1 - x), endY = Math.min(1, ny - 1 - y), endZ = Math.min(1, nz - 1 - z);
			double velocity = axis == 0 ? domain.particles.velocityX(marker) : axis == 1 ? domain.particles.velocityY(marker) : domain.particles.velocityZ(marker);
			for (int oz = 0; oz <= endZ; oz++) for (int oy = 0; oy <= endY; oy++) for (int ox = 0; ox <= endX; ox++) {
				double weight = FluidDomainStepCoordinator.trilinearWeight(ox, oy, oz, fx, fy, fz) * domain.particles.mass(marker);
				if (weight == 0) continue;
				int face = ((z + oz) * ny + y + oy) * nx + x + ox;
				double total = mass[face] + weight;
				if (!Double.isFinite(total)) throw new IllegalStateException("Marker face mass is not representable");
				// Normalize before multiplying by velocity, so a finite weighted mean needs no mass*velocity intermediate.
				means[face] += (velocity - means[face]) * (weight / total);
				mass[face] = total;
			}
		}
		for (int face = 0; face < mass.length; face++) {
			velocities[face] = (float) means[face]; flags[face] = mass[face] > 0 ? 1 : 0;
		}
	}
}
