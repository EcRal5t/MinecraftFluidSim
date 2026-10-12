package cc.ecisr.mmm.fluid;

import java.util.Map;
import java.util.Arrays;

import cc.ecisr.mmm.granular.GranularDepositStore;
import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.granular.SolidVolume;

/**
 * Body-force stages of the substep, in pipeline order: porous damping of the previously
 * committed flow, gravity, the Boussinesq thermal buoyancy correction and the solid-face
 * velocity enforcement. Liquid-gas, liquid-solid and liquid-pair surface forces join this stage in their own
 * slice; thermal buoyancy shares the per-cell carrier property workspace.
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
		LiquidCarrierProperties thermalBuoyancyProperties();
		double thermalFaceRelativeBuoyancy(int first, int second, LiquidCarrierProperties properties);
		double thermalMarkerRelativeBuoyancy(int marker);
		FluidMaterialRegistry materialRegistry();
		boolean liquidGasTensionActive();
		boolean usesCellLiquidPressure();
		boolean liquidPairTensionActive();
		double phaseFraction(int cell, int phase);
		void smoothSurfaceTensionColor();
		double surfaceTensionMeanCurvature(int x, int y, int z);
		boolean isFluid(int cell);
		boolean variableDensityActive();
		float pressureDensityScale(int first, int second);
		float carrierDensityScale(int first, int second);
	}

	private final Workspace state;
	private final Access access;
	private double[] markerVerticalIncrement = new double[0];
	private final double[] faceVerticalIncrement;
	private boolean markerPredictionReady;

	/** Physical marker acceleration and the MAC increments already applied for observation. */
	record MarkerPrediction(double[] verticalIncrement, double[] faceVerticalIncrement) { }

	BodyForceStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
		faceVerticalIncrement = new double[state.mac.velocityY.length];
	}

	void applyGranularPorousDamping(float dt) {
		if (access.granularState() == null || access.granularState().deposits.occupiedCellCount() == 0) return;
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 1; x < state.sizeX; x++) {
			int left = access.cellIndex(x - 1, y, z), right = access.cellIndex(x, y, z);
			state.mac.velocityX[access.xFaceIndex(x, y, z)] *= granularPorousFaceDamping(left, right, dt, false);
		}
		for (int z = 0; z < state.sizeZ; z++) for (int y = 1; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int below = access.cellIndex(x, y - 1, z), above = access.cellIndex(x, y, z);
			state.mac.velocityY[access.yFaceIndex(x, y, z)] *= granularPorousFaceDamping(below, above, dt, true);
		}
		for (int z = 1; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int back = access.cellIndex(x, y, z - 1), front = access.cellIndex(x, y, z);
			state.mac.velocityZ[access.zFaceIndex(x, y, z)] *= granularPorousFaceDamping(back, front, dt, false);
		}
	}

	private float granularPorousFaceDamping(int first, int second, float dt, boolean normal) {
		double firstK = granularPermeability(first), secondK = granularPermeability(second);
		if (Double.isInfinite(firstK) && Double.isInfinite(secondK)) return 1.0F;
		CarrierProperties firstCarrier = access.granularCarrierProperties(first),
				secondCarrier = access.granularCarrierProperties(second);
		double viscosity = 0.5 * (firstCarrier.viscosity() + secondCarrier.viscosity());
		double density = 0.5 * (firstCarrier.density() + secondCarrier.density());
		double firstFraction = access.granularState().deposits.cellBulkVolumeSvq(first) / (double) SolidVolume.SVQ_PER_CELL;
		double secondFraction = access.granularState().deposits.cellBulkVolumeSvq(second) / (double) SolidVolume.SVQ_PER_CELL;
		double scale = dt * viscosity / density;
		if (normal) return (float) (1 / (1 + scale * (.5 * firstFraction / firstK + .5 * secondFraction / secondK)));
		// Parallel permeabilities, followed by ONE implicit Darcy update. Averaging two damping
		// factors would repeatedly mix free/pore velocities and make drag depend on substep size.
		firstK = tangentialPermeability(firstK, firstFraction);
		secondK = tangentialPermeability(secondK, secondFraction);
		return (float) (1 / (1 + scale * (.5 / firstK + .5 / secondK)));
	}

	private double tangentialPermeability(double pore, double fraction) {
		if (fraction == 0) return Double.POSITIVE_INFINITY;
		double freeDepth = (1 - fraction) * state.config.cellSize();
		// Laminar layer with no-slip below and zero shear above: K = depth² / 3.
		return fraction * pore + (1 - fraction) * freeDepth * freeDepth / 3;
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
		markerVerticalIncrement = new double[state.particles.count()];
		Arrays.fill(markerVerticalIncrement, -state.config.gravity() * (double) dt);
		Arrays.fill(faceVerticalIncrement, 0);
		markerPredictionReady = true;
		float impulse = state.config.gravity() * dt;
		for (int z = 0; z < state.sizeZ; z++) for (int y = 1; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int below = access.cellIndex(x, y - 1, z), above = access.cellIndex(x, y, z);
			int face = access.yFaceIndex(x, y, z);
			float open = access.pressureYFaceOpen(x, y, z);
			if (open > 0 && (state.mac.p2gWeightY[face] > 0 || access.carrierFaceActive(below, above, open))) {
				float before = state.mac.velocityY[face];
				state.mac.velocityY[face] -= impulse;
				faceVerticalIncrement[face] = (double) state.mac.velocityY[face] - before;
			}
		}
	}

	/** Adds the Boussinesq correction using the pressure face's composition-density segments. */
	void applyThermalBuoyancy(float dt) {
		if (!access.thermalActive() || state.particles.count() == 0 || state.config.gravity() == 0.0F) return;
		LiquidCarrierProperties properties = access.thermalBuoyancyProperties();
		double scale = state.config.gravity() * (double) dt;
		if (markerPredictionReady) for (int marker = 0; marker < markerVerticalIncrement.length; marker++)
			markerVerticalIncrement[marker] += scale * access.thermalMarkerRelativeBuoyancy(marker);
		for (int z = 0; z < state.sizeZ; z++) for (int y = 1; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int below = access.cellIndex(x, y - 1, z), above = access.cellIndex(x, y, z);
			int face = access.yFaceIndex(x, y, z);
			float open = access.pressureYFaceOpen(x, y, z);
			if (!(open > 0)) continue;
			double relative;
			if (access.carrierFaceActive(below, above, open)) relative = access.thermalFaceRelativeBuoyancy(below, above, properties);
			else if (state.mac.p2gWeightY[face] > 0) relative = fringeRelativeBuoyancy(x, y, z);
			else continue;
			float before = state.mac.velocityY[face];
			state.mac.velocityY[face] += (float) (scale * relative);
			if (markerPredictionReady) faceVerticalIncrement[face] += (double) state.mac.velocityY[face] - before;
		}
	}

	/** Each pressure preparation receives the current body-force prediction once. */
	MarkerPrediction takeMarkerPrediction() {
		if (!markerPredictionReady) return new MarkerPrediction(new double[state.particles.count()], new double[faceVerticalIncrement.length]);
		if (markerVerticalIncrement.length != state.particles.count()) throw new IllegalStateException("Body-force marker layout changed");
		markerPredictionReady = false;
		return new MarkerPrediction(markerVerticalIncrement.clone(), faceVerticalIncrement.clone());
	}

	/** Mass average on the same trilinear P2G support for faces beyond the cell-centred carrier footprint. */
	private double fringeRelativeBuoyancy(int x, int y, int z) {
		double mass = 0, correction = 0;
		float h = state.config.cellSize();
		for (int cz = Math.max(0, z - 1); cz <= Math.min(state.sizeZ - 1, z + 1); cz++)
			for (int cy = y - 1; cy <= y; cy++)
				for (int cx = Math.max(0, x - 1); cx <= Math.min(state.sizeX - 1, x + 1); cx++) {
					int cell = access.cellIndex(cx, cy, cz);
					for (int p = state.markerGrid.cellMarkerHead[cell]; p >= 0; p = state.markerGrid.particleCellNext[p]) {
						float gx = Math.clamp(state.particles.positionX(p) / h - 0.5F, 0, state.sizeX - 1);
						float gy = Math.clamp(state.particles.positionY(p) / h, 0, state.sizeY);
						float gz = Math.clamp(state.particles.positionZ(p) / h - 0.5F, 0, state.sizeZ - 1);
						int bx = (int) Math.floor(gx), by = (int) Math.floor(gy), bz = (int) Math.floor(gz);
						int ox = x - bx, oy = y - by, oz = z - bz;
						if (ox < 0 || ox > 1 || oy < 0 || oy > 1 || oz < 0 || oz > 1) continue;
						float weight = FluidDomainStepCoordinator.trilinearWeight(ox, oy, oz, gx - bx, gy - by, gz - bz);
						if (!(weight > 0)) continue;
						double contribution = weight * state.particles.mass(p);
						mass += contribution;
						correction += contribution * access.thermalMarkerRelativeBuoyancy(p);
					}
				}
		if (!(mass > 0 && Double.isFinite(mass) && Double.isFinite(correction)))
			throw new IllegalStateException("Thermal P2G face has invalid carrier mass/correction");
		return correction / mass;
	}

	/** Complementary CSF for the mixed-solid pressure path's unresolved liquid-gas interfaces. */
	void applyLiquidGasSurfaceTensionForce(float dt) {
		if (!access.liquidGasTensionActive() || access.usesCellLiquidPressure()) return;
		float scale = dt / (state.config.density() * state.config.cellSize());
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 1; x < state.sizeX; x++) {
			int left = access.cellIndex(x - 1, y, z), right = access.cellIndex(x, y, z), face = access.xFaceIndex(x, y, z);
			applyLiquidGasFaceForce(left, right, face, scale, access.pressureXFaceOpen(x, y, z), state.mac.velocityX);
		}
		for (int z = 0; z < state.sizeZ; z++) for (int y = 1; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int below = access.cellIndex(x, y - 1, z), above = access.cellIndex(x, y, z), face = access.yFaceIndex(x, y, z);
			applyLiquidGasFaceForce(below, above, face, scale, access.pressureYFaceOpen(x, y, z), state.mac.velocityY);
		}
		for (int z = 1; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int back = access.cellIndex(x, y, z - 1), front = access.cellIndex(x, y, z), face = access.zFaceIndex(x, y, z);
			applyLiquidGasFaceForce(back, front, face, scale, access.pressureZFaceOpen(x, y, z), state.mac.velocityZ);
		}
	}

	/** Tangential force obtained from the Young wall-energy density sigma_sl - sigma_sg. */
	void applyLiquidSolidSurfaceEnergyForce(float dt) {
		if (!access.liquidGasTensionActive()) return;
		float scale = dt / (state.config.density() * state.config.cellSize());
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 1; x < state.sizeX; x++) {
			int left = access.cellIndex(x - 1, y, z), right = access.cellIndex(x, y, z), face = access.xFaceIndex(x, y, z);
			applyWallEnergyFaceForce(left, right, face, 0, scale, access.pressureXFaceOpen(x, y, z), state.mac.velocityX);
		}
		for (int z = 0; z < state.sizeZ; z++) for (int y = 1; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int below = access.cellIndex(x, y - 1, z), above = access.cellIndex(x, y, z), face = access.yFaceIndex(x, y, z);
			applyWallEnergyFaceForce(below, above, face, 1, scale, access.pressureYFaceOpen(x, y, z), state.mac.velocityY);
		}
		for (int z = 1; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int back = access.cellIndex(x, y, z - 1), front = access.cellIndex(x, y, z), face = access.zFaceIndex(x, y, z);
			applyWallEnergyFaceForce(back, front, face, 2, scale, access.pressureZFaceOpen(x, y, z), state.mac.velocityZ);
		}
	}

	private void applyWallEnergyFaceForce(int first, int second, int face, int velocityAxis,
	                                      float scale, float openArea, float[] velocity) {
		if (!access.carrierFaceActive(first, second, openArea)) return;
		double firstPotential = state.tension.wallSurfaceEnergyDensity[first]
				* state.tension.surfaceTensionColor[first] * tangentialWallArea(first, velocityAxis);
		double secondPotential = state.tension.wallSurfaceEnergyDensity[second]
				* state.tension.surfaceTensionColor[second] * tangentialWallArea(second, velocityAxis);
		double difference = secondPotential - firstPotential;
		if (difference == 0.0) return;
		float densityScale = access.variableDensityActive() ? access.carrierDensityScale(first, second) : 1.0F;
		velocity[face] += (float) (scale * densityScale * difference);
	}

	private double tangentialWallArea(int cell, int velocityAxis) {
		double area = 0.0;
		for (int wallNormalAxis = 0; wallNormalAxis < 3; wallNormalAxis++) {
			if (wallNormalAxis != velocityAxis) area += state.geometry.cutCells.solidWallArea(cell, wallNormalAxis);
		}
		return area;
	}

	private void applyLiquidGasFaceForce(int first, int second, int face, float scale,
	                                     float openArea, float[] velocity) {
		if (!access.carrierFaceActive(first, second, openArea)) return;
		if (access.isFluid(first) || access.isFluid(second)) return;
		double delta = state.tension.surfaceTensionSmoothed[second] - state.tension.surfaceTensionSmoothed[first];
		if (delta == 0.0) return;
		boolean firstCarriesLiquid = state.markerGrid.markerCount[first] > 0;
		boolean secondCarriesLiquid = state.markerGrid.markerCount[second] > 0;
		double capillaryPressure;
		if (firstCarriesLiquid && secondCarriesLiquid) {
			capillaryPressure = 0.5 * (state.tension.surfaceTensionPressure[first]
					+ state.tension.surfaceTensionPressure[second]);
		} else {
			capillaryPressure = state.tension.surfaceTensionPressure[firstCarriesLiquid ? first : second];
		}
		float densityScale = access.variableDensityActive() ? access.carrierDensityScale(first, second) : 1.0F;
		velocity[face] += (float) (scale * densityScale * capillaryPressure * delta);
	}

	/** Balanced-force CSF momentum for every immiscible liquid pair with a positive sigma12. */
	void applyLiquidPairSurfaceTensionForce(float dt) {
		if (!access.liquidPairTensionActive()) return;
		float scale = dt / (state.config.density() * state.config.cellSize());
		for (Map.Entry<Long, Double> entry : access.materialRegistry().liquidPairTensions().entrySet()) {
			if (entry.getValue() <= 0.0) continue;
			int firstPhase = (int) (entry.getKey() >>> 32), secondPhase = (int) (entry.getKey() & 0xffffffffL);
			applyLiquidPairSurfaceTensionPair(firstPhase, secondPhase, entry.getValue(), scale);
		}
	}

	private void applyLiquidPairSurfaceTensionPair(int phaseA, int phaseB, double tensionScale, float scale) {
		for (int index = 0; index < state.cellCount; index++)
			state.tension.surfaceTensionColor[index] = state.geometry.solid[index] ? 0 : access.phaseFraction(index, phaseA);
		access.smoothSurfaceTensionColor();
		System.arraycopy(state.tension.surfaceTensionSmoothed, 0, state.tension.surfaceTensionPairFirstSupport, 0, state.cellCount);
		for (int index = 0; index < state.cellCount; index++) {
			if (state.geometry.solid[index]) {
				state.tension.surfaceTensionColor[index] = 0.0;
				continue;
			}
			state.tension.surfaceTensionColor[index] = access.phaseFraction(index, phaseB);
		}
		access.smoothSurfaceTensionColor();
		rebuildLiquidPairNormals();
		java.util.Arrays.fill(state.tension.surfaceTensionPairCurvature, 0.0);
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int index = access.cellIndex(x, y, z);
			if (state.geometry.solid[index] || state.markerGrid.markerCount[index] == 0) continue;
			state.tension.surfaceTensionPairCurvature[index] = access.surfaceTensionMeanCurvature(x, y, z);
		}
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 1; x < state.sizeX; x++) {
			int left = access.cellIndex(x - 1, y, z), right = access.cellIndex(x, y, z), face = access.xFaceIndex(x, y, z);
			applyLiquidPairFaceForce(left, right, face, tensionScale, scale, access.pressureXFaceOpen(x, y, z), state.mac.velocityX);
		}
		for (int z = 0; z < state.sizeZ; z++) for (int y = 1; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int below = access.cellIndex(x, y - 1, z), above = access.cellIndex(x, y, z), face = access.yFaceIndex(x, y, z);
			applyLiquidPairFaceForce(below, above, face, tensionScale, scale, access.pressureYFaceOpen(x, y, z), state.mac.velocityY);
		}
		for (int z = 1; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int back = access.cellIndex(x, y, z - 1), front = access.cellIndex(x, y, z), face = access.zFaceIndex(x, y, z);
			applyLiquidPairFaceForce(back, front, face, tensionScale, scale, access.pressureZFaceOpen(x, y, z), state.mac.velocityZ);
		}
	}

	private void applyLiquidPairFaceForce(int first, int second, int face,
	                                      double tensionScale, float scale, float openArea, float[] velocity) {
		if (openArea <= 0.0F || !access.isFluid(first) || !access.isFluid(second)) return;
		double delta = liquidPairGradient(first, second);
		if (delta == 0.0) return;
		float densityScale = access.variableDensityActive() ? access.pressureDensityScale(first, second) : 1.0F;
		double kappa = 0.5 * (state.tension.surfaceTensionPairCurvature[first] + state.tension.surfaceTensionPairCurvature[second]);
		float forceDifference = (float) (tensionScale * kappa * delta);
		velocity[face] += scale * densityScale * forceDifference;
	}

	/** Multiphase CSF gradient alpha_A grad(alpha_B) - alpha_B grad(alpha_A). */
	private double liquidPairGradient(int first, int second) {
		return state.tension.surfaceTensionPairFirstSupport[first] * state.tension.surfaceTensionSmoothed[second]
				- state.tension.surfaceTensionPairFirstSupport[second] * state.tension.surfaceTensionSmoothed[first];
	}

	private void rebuildLiquidPairNormals() {
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int cell = access.cellIndex(x, y, z);
			double gx = liquidPairGradient(access.cellIndex(Math.max(0, x - 1), y, z), access.cellIndex(Math.min(state.sizeX - 1, x + 1), y, z));
			double gy = liquidPairGradient(access.cellIndex(x, Math.max(0, y - 1), z), access.cellIndex(x, Math.min(state.sizeY - 1, y + 1), z));
			double gz = liquidPairGradient(access.cellIndex(x, y, Math.max(0, z - 1)), access.cellIndex(x, y, Math.min(state.sizeZ - 1, z + 1)));
			double length = Math.sqrt(gx * gx + gy * gy + gz * gz);
			if (state.geometry.solid[cell] || !(length > 0)) {
				state.tension.surfaceTensionNormalX[cell] = state.tension.surfaceTensionNormalY[cell] = 0;
				state.tension.surfaceTensionNormalZ[cell] = 1;
			} else {
				state.tension.surfaceTensionNormalX[cell] = gx / length;
				state.tension.surfaceTensionNormalY[cell] = gy / length;
				state.tension.surfaceTensionNormalZ[cell] = gz / length;
			}
		}
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
