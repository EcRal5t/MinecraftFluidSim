package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularCarrier;
import cc.ecisr.mmm.granular.GranularMechanics;
import cc.ecisr.mmm.granular.SolidVolume;

/**
 * The granular mechanical stage inside each substep: parcel velocity advance against the
 * sampled carrier flow, collision capacity preview with local geometric resolution, the
 * pressure-coupling swap to the previewed occupancy, and the post-advection collision
 * commit. Returns the typed mechanics stats; boundary-impulse accumulation stays with the
 * step pipeline.
 */
final class GranularMechanicalStage {
	private final FlipFluidDomain domain;

	GranularMechanicalStage(FlipFluidDomain domain) {
		this.domain = domain;
	}

	/** Runs the whole granular substep; returns null when the domain carries no granular inventory. */
	GranularMechanics.StepStats stepVelocities(float dt) {
		if (!domain.hasGranularInventory()) return null;
		GranularMechanics.StepStats stats = GranularMechanics.stepVelocities(
				domain.granularState.parcels, domain.granularState.registry, granularEnvironment(),
				domain.config.gravity(), dt);
		domain.granularCollision.preview(domain.granularState.parcels, domain.granularState.registry, dt);
		domain.granularNextCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits,
				domain.granularCollision::previewPosition);
		if (!domain.granularOccupancyFitsGeometry(domain.granularNextCoupling)) {
			domain.granularCollision.resolveCapacity(domain.granularState.parcels, domain.granularCoupling,
					cell -> SolidVolume.fromCellVolumeExact(domain.geometry.cutCells.openCellVolume(cell)));
			domain.granularNextCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits,
					domain.granularCollision::previewPosition);
			if (!domain.granularOccupancyFitsGeometry(domain.granularNextCoupling)) {
				throw new IllegalStateException("Locally resolved granular occupancy exceeds geometry");
			}
		}
		domain.pressureGranularCoupling = domain.granularNextCoupling;
		return stats;
	}

	/** Applies the already-previewed collision capacity after marker advection. */
	void commitCollision() {
		if (domain.hasGranularInventory()) domain.granularCollision.commit(domain.granularState.parcels);
	}

	private GranularMechanics.Environment granularEnvironment() {
		return new GranularMechanics.Environment() {
			@Override public GranularMechanics.FluidSample sample(float x, float y, float z) {
				int cell = domain.particleCellIndex(x, y, z);
				int liquidMaterial = domain.granularCarrierMaterialByCell[cell];
				if (domain.markerGrid.markerCount[cell] <= 0 || liquidMaterial < 0) {
					return new GranularMechanics.FluidSample(false, 0.0, 0.0, 0.0,
							1.225, 1.81e-5, 100_000.0, 0.0);
				}
				String materialId = domain.materialRegistry.material(liquidMaterial).id();
				GranularCarrier carrier = domain.granularState.registry.carrier(
						domain.granularState.registry.carrierHandle(materialId));
				float capacity = domain.effectiveOpenCellVolume(cell);
				double submerged = capacity <= 0.0F ? 0.0
						: Math.max(0.0, Math.min(1.0, domain.markerGrid.exactCellMarkerVolume[cell] / capacity));
				return new GranularMechanics.FluidSample(true,
						domain.sampleX(domain.mac.velocityX, x, y, z),
						domain.sampleY(domain.mac.velocityY, x, y, z),
						domain.sampleZ(domain.mac.velocityZ, x, y, z),
						domain.markerGrid.cellDensity[cell], carrier.dynamicViscosity(), carrier.maximumReynolds(), submerged);
			}

			@Override public GranularMechanics.FluidImpulseApplication addFluidImpulse(float x, float y, float z,
			                                      double impulseX, double impulseY, double impulseZ) {
				boolean appliedX = addFaceImpulse(domain.mac.velocityX, domain.mac.velocityWeightX, 0, x, y, z,
						domain.unitScale.signedSolverMass(impulseX));
				boolean appliedY = addFaceImpulse(domain.mac.velocityY, domain.mac.velocityWeightY, 1, x, y, z,
						domain.unitScale.signedSolverMass(impulseY));
				boolean appliedZ = addFaceImpulse(domain.mac.velocityZ, domain.mac.velocityWeightZ, 2, x, y, z,
						domain.unitScale.signedSolverMass(impulseZ));
				return new GranularMechanics.FluidImpulseApplication(
						appliedX ? impulseX : 0.0, appliedY ? impulseY : 0.0, appliedZ ? impulseZ : 0.0,
						appliedX ? 0.0 : impulseX, appliedY ? 0.0 : impulseY, appliedZ ? 0.0 : impulseZ);
			}
		};
	}

	private boolean addFaceImpulse(
			float[] field, float[] faceMass, int axis, float px, float py, float pz, double impulse
	) {
		if (impulse == 0.0) return true;
		float h = domain.config.cellSize();
		int nx = axis == 0 ? domain.sizeX + 1 : domain.sizeX;
		int ny = axis == 1 ? domain.sizeY + 1 : domain.sizeY;
		int nz = axis == 2 ? domain.sizeZ + 1 : domain.sizeZ;
		float gx = px / h - (axis == 0 ? 0.0F : 0.5F);
		float gy = py / h - (axis == 1 ? 0.0F : 0.5F);
		float gz = pz / h - (axis == 2 ? 0.0F : 0.5F);
		int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
		float fx = gx - x0, fy = gy - y0, fz = gz - z0;
		double validWeight = 0.0;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) continue;
			int face = (z * ny + y) * nx + x;
			if (faceMass[face] <= 1.0e-8F) continue;
			validWeight += domain.trilinearWeight(ox, oy, oz, fx, fy, fz);
		}
		if (!(validWeight > 0.0)) return false;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) continue;
			int face = (z * ny + y) * nx + x;
			if (faceMass[face] <= 1.0e-8F) continue;
			double share = impulse * domain.trilinearWeight(ox, oy, oz, fx, fy, fz) / validWeight;
			field[face] += (float) (share / faceMass[face]);
		}
		return true;
	}
}
