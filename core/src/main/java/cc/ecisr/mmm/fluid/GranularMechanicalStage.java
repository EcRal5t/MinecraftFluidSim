package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularCarrier;
import cc.ecisr.mmm.granular.GranularMechanics;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.units.SolverUnitScale;

/**
 * The granular mechanical stage inside each substep: parcel velocity advance against the
 * sampled carrier flow, collision capacity preview with local geometric resolution, the
 * pressure-coupling swap to the previewed occupancy, and the post-advection collision
 * commit. Returns the typed mechanics stats; boundary-impulse accumulation stays with the
 * step pipeline.
 */
final class GranularMechanicalStage {
	record Workspace(
			FluidSolverConfig config, DomainGeometry geometry, MarkerGridIndex markerGrid,
			MacVelocityGrid mac, SolverUnitScale unitScale, int sizeX, int sizeY, int sizeZ
	) { }

	interface Access {
		boolean hasGranularInventory();
		GranularDomainState granularState();
		FlipGranularCollision granularCollision();
		GranularCouplingFields granularCoupling();
		GranularCouplingFields granularNextCoupling();
		boolean granularOccupancyFitsGeometry(GranularCouplingFields coupling);
		void setPressureGranularCoupling(GranularCouplingFields coupling);
		int particleCellIndex(float x, float y, float z);
		int[] granularCarrierMaterialByCell();
		FluidMaterialRegistry materialRegistry();
		float effectiveOpenCellVolume(int cell);
		float sampleX(float[] field, float x, float y, float z);
		float sampleY(float[] field, float x, float y, float z);
		float sampleZ(float[] field, float x, float y, float z);
		float trilinearWeight(int ox, int oy, int oz, float fx, float fy, float fz);
	}

	private final Workspace state;
	private final Access access;

	GranularMechanicalStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
	}

	/** Runs the whole granular substep; returns null when the domain carries no granular inventory. */
	GranularMechanics.StepStats stepVelocities(float dt) {
		if (!access.hasGranularInventory()) return null;
		GranularDomainState state = access.granularState();
		GranularMechanics.StepStats stats = GranularMechanics.stepVelocities(
				state.parcels, state.registry, granularEnvironment(), this.state.config.gravity(), dt);
		access.granularCollision().preview(state.parcels, state.registry, dt);
		access.granularNextCoupling().rebuild(state.parcels, state.deposits,
				access.granularCollision()::previewPosition);
		if (!access.granularOccupancyFitsGeometry(access.granularNextCoupling())) {
			access.granularCollision().resolveCapacity(state.parcels, access.granularCoupling(),
					cell -> SolidVolume.fromCellVolumeExact(this.state.geometry.cutCells.openCellVolume(cell)));
			access.granularNextCoupling().rebuild(state.parcels, state.deposits,
					access.granularCollision()::previewPosition);
			if (!access.granularOccupancyFitsGeometry(access.granularNextCoupling())) {
				throw new IllegalStateException("Locally resolved granular occupancy exceeds geometry");
			}
		}
		access.setPressureGranularCoupling(access.granularNextCoupling());
		return stats;
	}

	/** Applies the already-previewed collision capacity after marker advection. */
	void commitCollision() {
		if (access.hasGranularInventory()) {
			access.granularCollision().commit(access.granularState().parcels);
		}
	}

	private GranularMechanics.Environment granularEnvironment() {
		return new GranularMechanics.Environment() {
			@Override public GranularMechanics.FluidSample sample(float x, float y, float z) {
				int cell = access.particleCellIndex(x, y, z);
				int liquidMaterial = access.granularCarrierMaterialByCell()[cell];
				if (state.markerGrid.markerCount[cell] <= 0 || liquidMaterial < 0) {
					return new GranularMechanics.FluidSample(false, 0.0, 0.0, 0.0,
							1.225, 1.81e-5, 100_000.0, 0.0);
				}
				String materialId = access.materialRegistry().material(liquidMaterial).id();
				GranularCarrier carrier = access.granularState().registry.carrier(
						access.granularState().registry.carrierHandle(materialId));
				float capacity = access.effectiveOpenCellVolume(cell);
				double submerged = capacity <= 0.0F ? 0.0
						: Math.max(0.0, Math.min(1.0, state.markerGrid.exactCellMarkerVolume[cell] / capacity));
				return new GranularMechanics.FluidSample(true,
						access.sampleX(state.mac.velocityX, x, y, z),
						access.sampleY(state.mac.velocityY, x, y, z),
						access.sampleZ(state.mac.velocityZ, x, y, z),
						state.markerGrid.cellDensity[cell], carrier.dynamicViscosity(), carrier.maximumReynolds(), submerged);
			}

			@Override public GranularMechanics.FluidImpulseApplication addFluidImpulse(float x, float y, float z,
			                                      double impulseX, double impulseY, double impulseZ) {
				MacVelocityGrid mac = state.mac;
				boolean appliedX = addFaceImpulse(mac.velocityX, mac.velocityWeightX, 0, x, y, z,
						state.unitScale.signedSolverMass(impulseX));
				boolean appliedY = addFaceImpulse(mac.velocityY, mac.velocityWeightY, 1, x, y, z,
						state.unitScale.signedSolverMass(impulseY));
				boolean appliedZ = addFaceImpulse(mac.velocityZ, mac.velocityWeightZ, 2, x, y, z,
						state.unitScale.signedSolverMass(impulseZ));
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
		float h = state.config.cellSize();
		int nx = axis == 0 ? state.sizeX + 1 : state.sizeX;
		int ny = axis == 1 ? state.sizeY + 1 : state.sizeY;
		int nz = axis == 2 ? state.sizeZ + 1 : state.sizeZ;
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
			validWeight += access.trilinearWeight(ox, oy, oz, fx, fy, fz);
		}
		if (!(validWeight > 0.0)) return false;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) continue;
			int face = (z * ny + y) * nx + x;
			if (faceMass[face] <= 1.0e-8F) continue;
			double share = impulse * access.trilinearWeight(ox, oy, oz, fx, fy, fz) / validWeight;
			field[face] += (float) (share / faceMass[face]);
		}
		return true;
	}
}
