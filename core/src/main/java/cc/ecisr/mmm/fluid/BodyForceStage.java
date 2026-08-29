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
	private final FlipFluidDomain domain;

	BodyForceStage(FlipFluidDomain domain) {
		this.domain = domain;
	}

	void applyGranularPorousDamping(float dt) {
		if (domain.granularState == null || domain.granularState.deposits.occupiedCellCount() == 0) return;
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 1; x < domain.sizeX; x++) {
			int left = domain.cellIndex(x - 1, y, z), right = domain.cellIndex(x, y, z);
			domain.mac.velocityX[domain.xFaceIndex(x, y, z)] *= granularPorousFaceDamping(left, right, dt);
		}
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 1; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int below = domain.cellIndex(x, y - 1, z), above = domain.cellIndex(x, y, z);
			domain.mac.velocityY[domain.yFaceIndex(x, y, z)] *= granularPorousFaceDamping(below, above, dt);
		}
		for (int z = 1; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int back = domain.cellIndex(x, y, z - 1), front = domain.cellIndex(x, y, z);
			domain.mac.velocityZ[domain.zFaceIndex(x, y, z)] *= granularPorousFaceDamping(back, front, dt);
		}
	}

	private float granularPorousFaceDamping(int first, int second, float dt) {
		double firstK = granularPermeability(first), secondK = granularPermeability(second);
		if (Double.isInfinite(firstK) && Double.isInfinite(secondK)) return 1.0F;
		double permeability;
		if (Double.isInfinite(firstK)) permeability = secondK;
		else if (Double.isInfinite(secondK)) permeability = firstK;
		else permeability = 2.0 * firstK * secondK / (firstK + secondK);
		FlipFluidDomain.CarrierProperties firstCarrier = domain.granularCarrierProperties(first),
				secondCarrier = domain.granularCarrierProperties(second);
		double viscosity = 0.5 * (firstCarrier.viscosity() + secondCarrier.viscosity());
		double density = 0.5 * (firstCarrier.density() + secondCarrier.density());
		return (float) (1.0 / (1.0 + dt * viscosity / (density * permeability)));
	}

	private double granularPermeability(int cell) {
		GranularDepositStore.Group dominant = domain.granularState.deposits.dominantGroup(cell);
		if (dominant == null) return Double.POSITIVE_INFINITY;
		double porosity = depositsPorosity(cell);
		double solid = 1.0 - porosity;
		double diameter = domain.granularState.registry.material(dominant.materialHandle()).primaryGrainDiameterMeters();
		return diameter * diameter * porosity * porosity * porosity / (180.0 * solid * solid);
	}

	private double depositsPorosity(int cell) {
		return domain.granularState.deposits.porosityQ(cell) / (double) GranularLimits.POROSITY_SCALE;
	}

	void applyGravity(float dt) {
		float impulse = domain.config.gravity() * dt;
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 1; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int below = domain.cellIndex(x, y - 1, z), above = domain.cellIndex(x, y, z);
			if (domain.carrierFaceActive(below, above, domain.pressureYFaceOpen(x, y, z))) {
				domain.mac.velocityY[domain.yFaceIndex(x, y, z)] -= impulse;
			}
		}
	}

	/** Adds the standard Boussinesq density correction to the existing gravity face impulse. */
	void applyThermalBuoyancy(float dt) {
		if (domain.thermalState == null || domain.particles.count() == 0 || domain.config.gravity() == 0.0F) return;
		java.util.Arrays.fill(domain.thermalBuoyancySum, 0.0);
		java.util.Arrays.fill(domain.thermalBuoyancyVolume, 0.0);
		for (int marker = 0; marker < domain.particles.count(); marker++) {
			int cell = domain.particleCellIndex(marker);
			double markerVolume = domain.particles.volume(marker);
			String carrier = domain.materialRegistry.material(domain.particles.materialHandle(marker)).id();
			double relative = LiquidThermalExpansion.require(carrier)
					.relativeBuoyancy(domain.thermalMarkerTemperature(marker));
			domain.thermalBuoyancySum[cell] += markerVolume * relative;
			domain.thermalBuoyancyVolume[cell] += markerVolume;
		}
		double scale = domain.config.gravity() * dt;
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 1; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int below = domain.cellIndex(x, y - 1, z), above = domain.cellIndex(x, y, z);
			float aperture = domain.pressureYFaceOpen(x, y, z);
			if (!domain.carrierFaceActive(below, above, aperture)) continue;
			double volumeSum = domain.thermalBuoyancyVolume[below] + domain.thermalBuoyancyVolume[above];
			if (volumeSum <= 0.0) continue;
			double relative = (domain.thermalBuoyancySum[below] + domain.thermalBuoyancySum[above]) / volumeSum;
			domain.mac.velocityY[domain.yFaceIndex(x, y, z)] += (float) (scale * relative);
		}
	}

	/** Balanced-force CSF momentum for every immiscible liquid pair with a positive sigma12. */
	void applyLiquidPairSurfaceTensionForce(float dt) {
		if (!domain.liquidPairTensionActive) return;
		float scale = dt / (domain.config.density() * domain.config.cellSize());
		for (Map.Entry<Long, Double> entry : domain.materialRegistry.liquidPairTensions().entrySet()) {
			if (entry.getValue() <= 0.0) continue;
			int secondPhase = (int) (entry.getKey() & 0xffffffffL);
			applyLiquidPairSurfaceTensionPair(secondPhase, entry.getValue(), scale);
		}
	}

	private void applyLiquidPairSurfaceTensionPair(int phaseB, double tensionScale, float scale) {
		for (int index = 0; index < domain.cellCount; index++) {
			if (domain.geometry.solid[index]) {
				domain.tension.surfaceTensionColor[index] = 0.0;
				continue;
			}
			domain.tension.surfaceTensionColor[index] = domain.phaseFraction(index, phaseB);
		}
		domain.smoothSurfaceTensionColor();
		domain.rebuildSurfaceTensionNormals();
		java.util.Arrays.fill(domain.tension.surfaceTensionPairCurvature, 0.0);
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = domain.cellIndex(x, y, z);
			if (domain.geometry.solid[index] || domain.markerGrid.markerCount[index] == 0) continue;
			domain.tension.surfaceTensionPairCurvature[index] = domain.surfaceTensionMeanCurvature(x, y, z);
		}
		float inverseCell = 1.0F / domain.config.cellSize();
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 1; x < domain.sizeX; x++) {
			int left = domain.cellIndex(x - 1, y, z), right = domain.cellIndex(x, y, z), face = domain.xFaceIndex(x, y, z);
			applyLiquidPairFaceForce(left, right, face, tensionScale, scale, inverseCell, domain.pressureXFaceOpen(x, y, z), domain.mac.velocityX);
		}
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 1; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int below = domain.cellIndex(x, y - 1, z), above = domain.cellIndex(x, y, z), face = domain.yFaceIndex(x, y, z);
			applyLiquidPairFaceForce(below, above, face, tensionScale, scale, inverseCell, domain.pressureYFaceOpen(x, y, z), domain.mac.velocityY);
		}
		for (int z = 1; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int back = domain.cellIndex(x, y, z - 1), front = domain.cellIndex(x, y, z), face = domain.zFaceIndex(x, y, z);
			applyLiquidPairFaceForce(back, front, face, tensionScale, scale, inverseCell, domain.pressureZFaceOpen(x, y, z), domain.mac.velocityZ);
		}
	}

	private void applyLiquidPairFaceForce(int first, int second, int face, double tensionScale, float scale,
	                                      float inverseCell, float openArea, float[] velocity) {
		if (openArea <= 0.0F || !domain.isFluid(first) || !domain.isFluid(second)) return;
		double delta = domain.tension.surfaceTensionSmoothed[second] - domain.tension.surfaceTensionSmoothed[first];
		if (Math.abs(delta) <= 1.0E-12) return;
		float densityScale = domain.variableDensityActive ? domain.pressureDensityScale(first, second) : 1.0F;
		double kappa = 0.5 * (domain.tension.surfaceTensionPairCurvature[first] + domain.tension.surfaceTensionPairCurvature[second]);
		float forceDifference = (float) (tensionScale * kappa * delta);
		velocity[face] += scale * densityScale * forceDifference;
	}

	void enforceSolidFaceVelocities() {
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) {
			domain.mac.velocityX[domain.xFaceIndex(0, y, z)] = 0.0F;
			domain.mac.velocityX[domain.xFaceIndex(domain.sizeX, y, z)] = 0.0F;
			for (int x = 1; x < domain.sizeX; x++) {
				if (domain.geometry.cutCells.xFaceOpen(x, y, z) <= 0.0F) {
					domain.mac.velocityX[domain.xFaceIndex(x, y, z)] = 0.0F;
				}
			}
		}
		for (int z = 0; z < domain.sizeZ; z++) for (int x = 0; x < domain.sizeX; x++) {
			domain.mac.velocityY[domain.yFaceIndex(x, 0, z)] = 0.0F;
			domain.mac.velocityY[domain.yFaceIndex(x, domain.sizeY, z)] = 0.0F;
			for (int y = 1; y < domain.sizeY; y++) {
				if (domain.geometry.cutCells.yFaceOpen(x, y, z) <= 0.0F) {
					domain.mac.velocityY[domain.yFaceIndex(x, y, z)] = 0.0F;
				}
			}
		}
		for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			domain.mac.velocityZ[domain.zFaceIndex(x, y, 0)] = 0.0F;
			domain.mac.velocityZ[domain.zFaceIndex(x, y, domain.sizeZ)] = 0.0F;
			for (int z = 1; z < domain.sizeZ; z++) {
				if (domain.geometry.cutCells.zFaceOpen(x, y, z) <= 0.0F) {
					domain.mac.velocityZ[domain.zFaceIndex(x, y, z)] = 0.0F;
				}
			}
		}
	}
}
