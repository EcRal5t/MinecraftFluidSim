package mmm.fluid;

import java.util.Map;

import mmm.granular.GranularDepositStore;
import mmm.granular.GranularLimits;
import mmm.granular.SolidVolume;

/**
 * Body-force stages of the substep, in pipeline order: porous damping of the previously
 * committed flow, gravity, thermal buoyancy and the solid-face
 * velocity enforcement. Liquid-pair surface forces join this stage in their own
 * slice; thermal buoyancy shares the per-cell carrier property workspace.
 */
final class BodyForceStage {
	private final FluidDomain domain;
	BodyForceStage(FluidDomain domain) { this.domain = domain; }
	void applyGranularPorousDamping(float dt) {
		if (domain.granularState == null || domain.granularState.deposits.occupiedCellCount() == 0) return;
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 1; x < domain.sizeX; x++) {
			int left = domain.domainFlowFields.cellIndex(x - 1, y, z), right = domain.domainFlowFields.cellIndex(x, y, z);
			dampPorousFace(0,domain.domainFlowFields.xFaceIndex(x,y,z),granularPorousFaceDamping(left,right,dt,false));
		}
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 1; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int below = domain.domainFlowFields.cellIndex(x, y - 1, z), above = domain.domainFlowFields.cellIndex(x, y, z);
			dampPorousFace(1,domain.domainFlowFields.yFaceIndex(x,y,z),granularPorousFaceDamping(below,above,dt,true));
		}
		for (int z = 1; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int back = domain.domainFlowFields.cellIndex(x, y, z - 1), front = domain.domainFlowFields.cellIndex(x, y, z);
			dampPorousFace(2,domain.domainFlowFields.zFaceIndex(x,y,z),granularPorousFaceDamping(back,front,dt,false));
		}
	}

	private void dampPorousFace(int axis,int face,float damping) {
		if(damping==1)return;
		float[] velocity=axis==0?domain.mac.velocityX:axis==1?domain.mac.velocityY:domain.mac.velocityZ;
		double[] mass=axis==0?domain.cellMacStage.massX:axis==1?domain.cellMacStage.massY:domain.cellMacStage.massZ;
		double before=velocity[face];velocity[face]*=damping;
		double heat=.5*mass[face]*(before*before-(double)velocity[face]*velocity[face]);
		if(heat>0)domain.cellMacStage.addDissipationAtFace(axis,face,heat);
	}

	private float granularPorousFaceDamping(int first, int second, float dt, boolean normal) {
		double firstK = granularPermeability(first), secondK = granularPermeability(second);
		if (Double.isInfinite(firstK) && Double.isInfinite(secondK)) return 1.0F;
		CarrierProperties firstCarrier = domain.domainStep.granularCarrierProperties(first),
				secondCarrier = domain.domainStep.granularCarrierProperties(second);
		double viscosity = 0.5 * (firstCarrier.viscosity() + secondCarrier.viscosity());
		double density = 0.5 * (firstCarrier.density() + secondCarrier.density());
		double firstFraction = domain.granularState.deposits.cellBulkVolumeSvq(first) / (double) SolidVolume.SVQ_PER_CELL;
		double secondFraction = domain.granularState.deposits.cellBulkVolumeSvq(second) / (double) SolidVolume.SVQ_PER_CELL;
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
		double freeDepth = (1 - fraction) * domain.config.cellSize();
		// Laminar layer with no-slip below and zero shear above: K = depth² / 3.
		return fraction * pore + (1 - fraction) * freeDepth * freeDepth / 3;
	}

	private double granularPermeability(int cell) {
		GranularDomainState granular = domain.granularState;
		GranularDepositStore.Group dominant = granular.deposits.dominantGroup(cell);
		if (dominant == null) return Double.POSITIVE_INFINITY;
		double porosity = depositsPorosity(cell);
		double solid = 1.0 - porosity;
		double diameter = granular.registry.material(dominant.materialHandle()).primaryGrainDiameterMeters();
		return diameter * diameter * porosity * porosity * porosity / (180.0 * solid * solid);
	}

	private double depositsPorosity(int cell) {
		return domain.granularState.deposits.porosityQ(cell) / (double) GranularLimits.POROSITY_SCALE;
	}

	double applyGravity(float dt) {
		float impulse = domain.config.gravity() * dt;
		double supportWork=0,supportImpulse=0;
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 1; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int below = domain.domainFlowFields.cellIndex(x, y - 1, z), above = domain.domainFlowFields.cellIndex(x, y, z);
			int face = domain.domainFlowFields.yFaceIndex(x, y, z);
			if(domain.cellMacStage.isUnresolvedWallFace(1,face))continue;
			float open = domain.domainFlowFields.pressureYFaceOpen(x, y, z);
			if (open > 0 && (domain.mac.faceMassY[face] > 0 || domain.domainFlowFields.carrierFaceActive(below, above, open))) {
				double mass=domain.cellMacStage.massY[face],old=domain.mac.velocityY[face];float free=(float)(old-impulse);
				double acceleration=domain.pressureSystem.faces.freeFallAcceleration(domain.pressureSystem.faces.offsets[1]+face);
				float next=acceleration==-domain.config.gravity()?free:(float)(old+dt*acceleration);
				domain.mac.velocityY[face]=next;
				supportImpulse+=mass*(next-free);
				supportWork+=.5*mass*((double)next*next-(double)free*free);
			}
		}
		var graph=domain.pressureSystem.faces;
		for(int face=0;face<graph.faceCount;face++){
			int axis=graph.axis[face],local=graph.localFace[face];if(axis==1||domain.cellMacStage.isUnresolvedWallFace(axis,local))continue;
			float[] velocity=axis==0?domain.mac.velocityX:domain.mac.velocityZ;
			velocity[local]+=(float)(dt*graph.barotropicAcceleration(face));
		}
		domain.cellMacStage.recordSupportedWeightImpulse(supportImpulse);
		return supportWork;
	}

	/** Adds thermal buoyancy from the same cell-density segments as the pressure face. */
	void applyThermalBuoyancy(float dt) {
		if (domain.thermalState == null || domain.config.gravity() == 0.0F) return;
		LiquidCarrierProperties properties = domain.domainPhaseInventory.thermalBuoyancyProperties();
		double scale = domain.config.gravity() * (double) dt;
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 1; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int below = domain.domainFlowFields.cellIndex(x, y - 1, z), above = domain.domainFlowFields.cellIndex(x, y, z);
			int face = domain.domainFlowFields.yFaceIndex(x, y, z);
			if(domain.cellMacStage.isUnresolvedWallFace(1,face))continue;
			float open = domain.domainFlowFields.pressureYFaceOpen(x, y, z);
			if (!(open > 0)) continue;
			double relative;
			if (domain.domainFlowFields.carrierFaceActive(below, above, open)) relative = domain.domainFlowFields.thermalFaceRelativeBuoyancy(below, above, properties);
			else continue;
			domain.mac.velocityY[face] += (float) (scale * relative);
		}
	}

	/** Balanced-force CSF momentum for every immiscible liquid pair with a positive sigma12. */
	void applyLiquidPairSurfaceTensionForce(float dt) {
		if (!domain.liquidPairTensionActive) return;
		float scale = dt / (domain.config.density() * domain.config.cellSize());
		for (Map.Entry<Long, Double> entry : domain.materialRegistry.liquidPairTensions().entrySet()) {
			if (entry.getValue() <= 0.0) continue;
			int firstPhase = (int) (entry.getKey() >>> 32), secondPhase = (int) (entry.getKey() & 0xffffffffL);
			applyLiquidPairSurfaceTensionPair(firstPhase, secondPhase, entry.getValue(), scale);
		}
	}

	private void applyLiquidPairSurfaceTensionPair(int phaseA, int phaseB, double tensionScale, float scale) {
		for (int index = 0; index < domain.cellCount; index++)
			domain.tension.surfaceTensionColor[index] = domain.geometry.solid[index] ? 0 : domain.domainFlowFields.phaseFraction(index, phaseA);
		domain.tension.smoothSurfaceTensionColor();
		System.arraycopy(domain.tension.surfaceTensionSmoothed, 0, domain.tension.surfaceTensionPairFirstSupport, 0, domain.cellCount);
		for (int index = 0; index < domain.cellCount; index++) {
			if (domain.geometry.solid[index]) {
				domain.tension.surfaceTensionColor[index] = 0.0;
				continue;
			}
			domain.tension.surfaceTensionColor[index] = domain.domainFlowFields.phaseFraction(index, phaseB);
		}
		domain.tension.smoothSurfaceTensionColor();
		rebuildLiquidPairNormals();
		java.util.Arrays.fill(domain.tension.surfaceTensionPairCurvature, 0.0);
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = domain.domainFlowFields.cellIndex(x, y, z);
			if (domain.geometry.solid[index] || domain.liquidGrid.stockCount[index] == 0) continue;
			domain.tension.surfaceTensionPairCurvature[index] = domain.tension.surfaceTensionMeanCurvature(x, y, z);
		}
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 1; x < domain.sizeX; x++) {
			int left = domain.domainFlowFields.cellIndex(x - 1, y, z), right = domain.domainFlowFields.cellIndex(x, y, z), face = domain.domainFlowFields.xFaceIndex(x, y, z);
			applyLiquidPairFaceForce(left, right, face, tensionScale, scale, domain.domainFlowFields.pressureXFaceOpen(x, y, z), domain.mac.velocityX);
		}
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 1; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int below = domain.domainFlowFields.cellIndex(x, y - 1, z), above = domain.domainFlowFields.cellIndex(x, y, z), face = domain.domainFlowFields.yFaceIndex(x, y, z);
			applyLiquidPairFaceForce(below, above, face, tensionScale, scale, domain.domainFlowFields.pressureYFaceOpen(x, y, z), domain.mac.velocityY);
		}
		for (int z = 1; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int back = domain.domainFlowFields.cellIndex(x, y, z - 1), front = domain.domainFlowFields.cellIndex(x, y, z), face = domain.domainFlowFields.zFaceIndex(x, y, z);
			applyLiquidPairFaceForce(back, front, face, tensionScale, scale, domain.domainFlowFields.pressureZFaceOpen(x, y, z), domain.mac.velocityZ);
		}
	}

	private void applyLiquidPairFaceForce(int first, int second, int face,
	                                      double tensionScale, float scale, float openArea, float[] velocity) {
		int axis=velocity==domain.mac.velocityX?0:velocity==domain.mac.velocityY?1:2;
		if(domain.cellMacStage.isUnresolvedWallFace(axis,face))return;
		if (openArea <= 0.0F || !domain.domainFlowFields.isFluid(first) || !domain.domainFlowFields.isFluid(second)) return;
		double delta = liquidPairGradient(first, second);
		if (delta == 0.0) return;
		float densityScale = domain.domainFlowFields.pressureDensityScale(first, second);
		double kappa = 0.5 * (domain.tension.surfaceTensionPairCurvature[first] + domain.tension.surfaceTensionPairCurvature[second]);
		float forceDifference = (float) (tensionScale * kappa * delta);
		velocity[face] += scale * densityScale * forceDifference;
	}

	/** Multiphase CSF gradient alpha_A grad(alpha_B) - alpha_B grad(alpha_A). */
	private double liquidPairGradient(int first, int second) {
		return domain.tension.surfaceTensionPairFirstSupport[first] * domain.tension.surfaceTensionSmoothed[second]
				- domain.tension.surfaceTensionPairFirstSupport[second] * domain.tension.surfaceTensionSmoothed[first];
	}

	private void rebuildLiquidPairNormals() {
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int cell = domain.domainFlowFields.cellIndex(x, y, z);
			double gx = liquidPairGradient(domain.domainFlowFields.cellIndex(Math.max(0, x - 1), y, z), domain.domainFlowFields.cellIndex(Math.min(domain.sizeX - 1, x + 1), y, z));
			double gy = liquidPairGradient(domain.domainFlowFields.cellIndex(x, Math.max(0, y - 1), z), domain.domainFlowFields.cellIndex(x, Math.min(domain.sizeY - 1, y + 1), z));
			double gz = liquidPairGradient(domain.domainFlowFields.cellIndex(x, y, Math.max(0, z - 1)), domain.domainFlowFields.cellIndex(x, y, Math.min(domain.sizeZ - 1, z + 1)));
			double length = Math.sqrt(gx * gx + gy * gy + gz * gz);
			if (domain.geometry.solid[cell] || !(length > 0)) {
				domain.tension.surfaceTensionNormalX[cell] = domain.tension.surfaceTensionNormalY[cell] = 0;
				domain.tension.surfaceTensionNormalZ[cell] = 1;
			} else {
				domain.tension.surfaceTensionNormalX[cell] = gx / length;
				domain.tension.surfaceTensionNormalY[cell] = gy / length;
				domain.tension.surfaceTensionNormalZ[cell] = gz / length;
			}
		}
	}

	void enforceSolidFaceVelocities() {
		float[][] velocities={domain.mac.velocityX,domain.mac.velocityY,domain.mac.velocityZ};
		for(int axis=0;axis<3;axis++)for(int face=0;face<velocities[axis].length;face++)
			if(domain.cellMacStage.isWallFace(axis,face,null))velocities[axis][face]=0;
	}
}
