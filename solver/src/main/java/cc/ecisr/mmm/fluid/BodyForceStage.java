package cc.ecisr.mmm.fluid;

import java.util.Map;

import cc.ecisr.mmm.granular.GranularDepositStore;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.thermal.LiquidThermalExpansion;

/**
 * Body-force stages of the substep, in pipeline order: porous damping of the previously
 * committed flow, gravity, the Boussinesq thermal buoyancy correction and the solid-face
 * velocity enforcement. The liquid-pair surface tension force joins this stage in its own
 * slice; the buoyancy scratch columns stay with the domain lifecycle.
 */
final class BodyForceStage {
	record Workspace(
			int sizeX, int sizeY, int sizeZ, int cellCount, FluidSolverConfig config,
			DomainGeometry geometry, MacVelocityGrid mac, MarkerGridIndex markerGrid,
			LiquidParticleStore particles, SurfaceTensionWorkspace tension
	) { }

	interface Access {
		GranularDomainState granularState();
		CarrierProperties granularCarrierProperties(int cell);
		int cellIndex(int x, int y, int z);
		int xFaceIndex(int x, int y, int z);
		int yFaceIndex(int x, int y, int z);
		int zFaceIndex(int x, int y, int z);
		boolean carrierFaceActive(int first, int second, float openArea);
		float pressureXFaceOpen(int x, int y, int z);
		float pressureYFaceOpen(int x, int y, int z);
		float pressureZFaceOpen(int x, int y, int z);
		boolean thermalActive();
		double[] thermalBuoyancySum();
		double[] thermalBuoyancyVolume();
		int particleCellIndex(int marker);
		FluidMaterialRegistry materialRegistry();
		double thermalMarkerTemperature(int marker);
		boolean liquidPairTensionActive();
		double phaseFraction(int cell, int phase);
		void smoothSurfaceTensionColor();
		void rebuildSurfaceTensionNormals();
		double surfaceTensionMeanCurvature(int x, int y, int z);
		boolean isFluid(int cell);
		boolean variableDensityActive();
		float pressureDensityScale(int first, int second);
	}

	private final Workspace state;
	private final Access access;

	BodyForceStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
	}

	void applyGranularPorousDamping(float dt) {
		if (access.granularState() == null || access.granularState().deposits.occupiedCellCount() == 0) return;
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 1; x < state.sizeX; x++) {
			int left = access.cellIndex(x - 1, y, z), right = access.cellIndex(x, y, z);
			state.mac.velocityX[access.xFaceIndex(x, y, z)] *= granularPorousFaceDamping(left, right, dt);
		}
		for (int z = 0; z < state.sizeZ; z++) for (int y = 1; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int below = access.cellIndex(x, y - 1, z), above = access.cellIndex(x, y, z);
			state.mac.velocityY[access.yFaceIndex(x, y, z)] *= granularPorousFaceDamping(below, above, dt);
		}
		for (int z = 1; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int back = access.cellIndex(x, y, z - 1), front = access.cellIndex(x, y, z);
			state.mac.velocityZ[access.zFaceIndex(x, y, z)] *= granularPorousFaceDamping(back, front, dt);
		}
	}

	private float granularPorousFaceDamping(int first, int second, float dt) {
		double firstK = granularPermeability(first), secondK = granularPermeability(second);
		if (Double.isInfinite(firstK) && Double.isInfinite(secondK)) return 1.0F;
		double permeability;
		if (Double.isInfinite(firstK)) permeability = secondK;
		else if (Double.isInfinite(secondK)) permeability = firstK;
		else permeability = 2.0 * firstK * secondK / (firstK + secondK);
		CarrierProperties firstCarrier = access.granularCarrierProperties(first),
				secondCarrier = access.granularCarrierProperties(second);
		double viscosity = 0.5 * (firstCarrier.viscosity() + secondCarrier.viscosity());
		double density = 0.5 * (firstCarrier.density() + secondCarrier.density());
		return (float) (1.0 / (1.0 + dt * viscosity / (density * permeability)));
	}

	private double granularPermeability(int cell) {
		GranularDomainState granular = access.granularState();
		GranularDepositStore.Group dominant = granular.deposits.dominantGroup(cell);
		if (dominant == null) return Double.POSITIVE_INFINITY;
		double porosity = depositsPorosity(cell);
		double solid = 1.0 - porosity;
		double diameter = granular.registry.material(dominant.materialHandle()).primaryGrainDiameterMeters();
		return diameter * diameter * porosity * porosity * porosity / (180.0 * solid * solid);
	}

	private double depositsPorosity(int cell) {
		return access.granularState().deposits.porosityQ(cell) / (double) GranularLimits.POROSITY_SCALE;
	}

	void applyGravity(float dt) {
		float impulse = state.config.gravity() * dt;
		for (int z = 0; z < state.sizeZ; z++) for (int y = 1; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int below = access.cellIndex(x, y - 1, z), above = access.cellIndex(x, y, z);
			if (access.carrierFaceActive(below, above, access.pressureYFaceOpen(x, y, z))) {
				state.mac.velocityY[access.yFaceIndex(x, y, z)] -= impulse;
			}
		}
	}

	/** Adds the standard Boussinesq density correction to the existing gravity face impulse. */
	void applyThermalBuoyancy(float dt) {
		if (!access.thermalActive() || state.particles.count() == 0 || state.config.gravity() == 0.0F) return;
		double[] buoyancySum = access.thermalBuoyancySum();
		double[] buoyancyVolume = access.thermalBuoyancyVolume();
		java.util.Arrays.fill(buoyancySum, 0.0);
		java.util.Arrays.fill(buoyancyVolume, 0.0);
		for (int marker = 0; marker < state.particles.count(); marker++) {
			int cell = access.particleCellIndex(marker);
			double markerVolume = state.particles.volume(marker);
			String carrier = access.materialRegistry().material(state.particles.materialHandle(marker)).id();
			double relative = LiquidThermalExpansion.require(carrier)
					.relativeBuoyancy(access.thermalMarkerTemperature(marker));
			buoyancySum[cell] += markerVolume * relative;
			buoyancyVolume[cell] += markerVolume;
		}
		double scale = state.config.gravity() * dt;
		for (int z = 0; z < state.sizeZ; z++) for (int y = 1; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int below = access.cellIndex(x, y - 1, z), above = access.cellIndex(x, y, z);
			float aperture = access.pressureYFaceOpen(x, y, z);
			if (!access.carrierFaceActive(below, above, aperture)) continue;
			double volumeSum = buoyancyVolume[below] + buoyancyVolume[above];
			if (volumeSum <= 0.0) continue;
			double relative = (buoyancySum[below] + buoyancySum[above]) / volumeSum;
			state.mac.velocityY[access.yFaceIndex(x, y, z)] += (float) (scale * relative);
		}
	}

	/** Balanced-force CSF momentum for every immiscible liquid pair with a positive sigma12. */
	void applyLiquidPairSurfaceTensionForce(float dt) {
		if (!access.liquidPairTensionActive()) return;
		float scale = dt / (state.config.density() * state.config.cellSize());
		for (Map.Entry<Long, Double> entry : access.materialRegistry().liquidPairTensions().entrySet()) {
			if (entry.getValue() <= 0.0) continue;
			int secondPhase = (int) (entry.getKey() & 0xffffffffL);
			applyLiquidPairSurfaceTensionPair(secondPhase, entry.getValue(), scale);
		}
	}

	private void applyLiquidPairSurfaceTensionPair(int phaseB, double tensionScale, float scale) {
		for (int index = 0; index < state.cellCount; index++) {
			if (state.geometry.solid[index]) {
				state.tension.surfaceTensionColor[index] = 0.0;
				continue;
			}
			state.tension.surfaceTensionColor[index] = access.phaseFraction(index, phaseB);
		}
		access.smoothSurfaceTensionColor();
		access.rebuildSurfaceTensionNormals();
		java.util.Arrays.fill(state.tension.surfaceTensionPairCurvature, 0.0);
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int index = access.cellIndex(x, y, z);
			if (state.geometry.solid[index] || state.markerGrid.markerCount[index] == 0) continue;
			state.tension.surfaceTensionPairCurvature[index] = access.surfaceTensionMeanCurvature(x, y, z);
		}
		float inverseCell = 1.0F / state.config.cellSize();
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 1; x < state.sizeX; x++) {
			int left = access.cellIndex(x - 1, y, z), right = access.cellIndex(x, y, z), face = access.xFaceIndex(x, y, z);
			applyLiquidPairFaceForce(left, right, face, tensionScale, scale, inverseCell, access.pressureXFaceOpen(x, y, z), state.mac.velocityX);
		}
		for (int z = 0; z < state.sizeZ; z++) for (int y = 1; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int below = access.cellIndex(x, y - 1, z), above = access.cellIndex(x, y, z), face = access.yFaceIndex(x, y, z);
			applyLiquidPairFaceForce(below, above, face, tensionScale, scale, inverseCell, access.pressureYFaceOpen(x, y, z), state.mac.velocityY);
		}
		for (int z = 1; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int back = access.cellIndex(x, y, z - 1), front = access.cellIndex(x, y, z), face = access.zFaceIndex(x, y, z);
			applyLiquidPairFaceForce(back, front, face, tensionScale, scale, inverseCell, access.pressureZFaceOpen(x, y, z), state.mac.velocityZ);
		}
	}

	private void applyLiquidPairFaceForce(int first, int second, int face, double tensionScale, float scale,
	                                      float inverseCell, float openArea, float[] velocity) {
		if (openArea <= 0.0F || !access.isFluid(first) || !access.isFluid(second)) return;
		double delta = state.tension.surfaceTensionSmoothed[second] - state.tension.surfaceTensionSmoothed[first];
		if (Math.abs(delta) <= 1.0E-12) return;
		float densityScale = access.variableDensityActive() ? access.pressureDensityScale(first, second) : 1.0F;
		double kappa = 0.5 * (state.tension.surfaceTensionPairCurvature[first] + state.tension.surfaceTensionPairCurvature[second]);
		float forceDifference = (float) (tensionScale * kappa * delta);
		velocity[face] += scale * densityScale * forceDifference;
	}

	void enforceSolidFaceVelocities() {
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) {
			state.mac.velocityX[access.xFaceIndex(0, y, z)] = 0.0F;
			state.mac.velocityX[access.xFaceIndex(state.sizeX, y, z)] = 0.0F;
			for (int x = 1; x < state.sizeX; x++) {
				if (state.geometry.cutCells.xFaceOpen(x, y, z) <= 0.0F) {
					state.mac.velocityX[access.xFaceIndex(x, y, z)] = 0.0F;
				}
			}
		}
		for (int z = 0; z < state.sizeZ; z++) for (int x = 0; x < state.sizeX; x++) {
			state.mac.velocityY[access.yFaceIndex(x, 0, z)] = 0.0F;
			state.mac.velocityY[access.yFaceIndex(x, state.sizeY, z)] = 0.0F;
			for (int y = 1; y < state.sizeY; y++) {
				if (state.geometry.cutCells.yFaceOpen(x, y, z) <= 0.0F) {
					state.mac.velocityY[access.yFaceIndex(x, y, z)] = 0.0F;
				}
			}
		}
		for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			state.mac.velocityZ[access.zFaceIndex(x, y, 0)] = 0.0F;
			state.mac.velocityZ[access.zFaceIndex(x, y, state.sizeZ)] = 0.0F;
			for (int z = 1; z < state.sizeZ; z++) {
				if (state.geometry.cutCells.zFaceOpen(x, y, z) <= 0.0F) {
					state.mac.velocityZ[access.zFaceIndex(x, y, z)] = 0.0F;
				}
			}
		}
	}
}
