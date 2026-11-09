package mmm.fluid;

import mmm.granular.GranularMechanics;
import mmm.granular.SolidVolume;

/** Moves the jointly pressure-projected solid velocity through the collision-resolved position endpoint. */
final class GranularMechanicalStage {
	private final HostFluidState domain;
	private boolean collisionPending;
	GranularMechanicalStage(HostFluidState domain) { this.domain = domain; }

	private void addBoundaryImpulse(FlipGranularCollision.BoundaryImpulse impulse) {
		domain.lastGranularBoundaryImpulseX += impulse.x();
		domain.lastGranularBoundaryImpulseY += impulse.y();
		domain.lastGranularBoundaryImpulseZ += impulse.z();
	}

	GranularMechanics.StepStats predictVelocities(float dt) {
		collisionPending = false;
		if (!domain.domainPhaseInventory.hasGranularInventory()) return null;
		var granular = domain.granularState;
		int submerged = 0;
		for (int index = 0; index < granular.parcels.count(); index++) {
			var p = granular.parcels.parcel(index);
            if(!domain.activityGroups.parcelAvailable(index))continue;
			var fluid = sample(p.x(), p.y(), p.z());
			if (fluid.hasLiquid()) submerged++;
		}
		double maximum = 0, roundingY = 0;
		for (int index = 0; index < granular.parcels.count(); index++) {
			var p = granular.parcels.parcel(index);
            if(!domain.activityGroups.parcelAvailable(index))continue;
			double predictedY = p.velocityY() - domain.config.gravity() * (double) dt;
			float vy = (float) predictedY;
			roundingY += p.mass() * (predictedY - vy);
			granular.parcels.overwrite(index, new mmm.granular.GranularParcelStore.Parcel(
					p.x(), p.y(), p.z(), p.velocityX(), vy, p.velocityZ(), p.solidVolumeSvq(), p.massUnits(),
					p.materialHandle(), p.solidCompositionHandle(), p.contactAge(), p.flags()));
			maximum = Math.max(maximum, Math.sqrt(p.velocityX() * (double) p.velocityX()
					+ vy * (double) vy + p.velocityZ() * (double) p.velocityZ()));
		}
		return new GranularMechanics.StepStats(granular.parcels.count(), submerged, maximum, 0, 0, 0, 0, roundingY, 0);
	}

	/** Checks the material carrier range at the committed candidate velocity. */
	void validateVelocities() {
		if (!domain.domainPhaseInventory.hasGranularInventory()) return;
		var granular = domain.granularState;
		for (int index = 0; index < granular.parcels.count(); index++) {
			var p = granular.parcels.parcel(index);
            if(!domain.activityGroups.parcelAvailable(index))continue;
			GranularMechanics.validateCarrierRange(p, granular.registry.material(p.materialHandle()),
					sample(p.x(), p.y(), p.z()));
		}
	}

	void previewCollision(float dt) {
		collisionPending = false;
		if (!domain.domainPhaseInventory.hasGranularInventory()) return;
		GranularDomainState state = domain.granularState;
		// Both phases have received the same pressure projection before this drift.
		domain.granularCollision.preview(state.parcels, state.registry, dt, domain.activityGroups::parcelAvailable);
		domain.granularNextCoupling.rebuild(state.parcels, state.deposits,
				domain.granularCollision::previewPosition);
		if (!domain.domainInventoryGeometry.granularOccupancyFitsGeometry(domain.granularNextCoupling)) {
			domain.granularCollision.resolveCapacity(state.parcels, domain.granularCoupling,
					cell -> Math.max(0, SolidVolume.fromCellVolumeExact(domain.geometry.cutCells.openCellVolume(cell)) - domain.entityBodyCoupling.occupied(cell)));
				domain.granularNextCoupling.rebuild(state.parcels, state.deposits,
					domain.granularCollision::previewPosition);
			if (!domain.domainInventoryGeometry.granularOccupancyFitsGeometry(domain.granularNextCoupling)) {
				throw new IllegalStateException("Locally resolved granular occupancy exceeds geometry");
			}
		}
		collisionPending = true;
	}

	double plannedPositionY(int index){
		var parcel=domain.granularState.parcels.parcel(index);
		return collisionPending?domain.granularCollision.previewPosition(index,parcel).y():parcel.y();
	}
	int plannedContactComponent(int index) {
		return collisionPending ? domain.granularCollision.plannedContactComponent(index) : index;
	}
	boolean collisionPending(){return collisionPending;}
	void publishCollisionVelocities(){
		if(collisionPending)addBoundaryImpulse(domain.granularCollision.publishVelocities(domain.granularState.parcels));
	}

	void commitCollision() {
		if (!collisionPending) return;
		var granular = domain.granularState;
		addBoundaryImpulse(domain.granularCollision.commit(granular.parcels));
		domain.granularCoupling.copyFrom(domain.granularNextCoupling);
		collisionPending = false;
	}

	GranularMechanics.FluidSample sample(float x, float y, float z) {
		return samplePrepared(x,y,z,domain.domainPhaseInventory.derivedCarrierState(),null);
	}
	GranularMechanics.FluidSample samplePrepared(float x,float y,float z,LiquidCarrierProperties prepared,long[] sourceSolid){
		CarrierProperties properties = carrierProperties(x, y, z,prepared,sourceSolid);
		if (!(properties.submerged() > 0)) {
			return new GranularMechanics.FluidSample(false, 0, 0, 0, LiquidCarrierProperties.EMPTY_LIQUID_DENSITY,
					LiquidCarrierProperties.EMPTY_LIQUID_VISCOSITY, LiquidCarrierProperties.DEFAULT_MAXIMUM_REYNOLDS, 0, 0, 0, 0);
		}
		CarrierKernel xKernel = sampleCarrier(domain.mac.velocityX, domain.mac.faceMassX, 0, x, y, z);
		CarrierKernel yKernel = sampleCarrier(domain.mac.velocityY, domain.mac.faceMassY, 1, x, y, z);
		CarrierKernel zKernel = sampleCarrier(domain.mac.velocityZ, domain.mac.faceMassZ, 2, x, y, z);
		return new GranularMechanics.FluidSample(true, xKernel.velocity(), yKernel.velocity(), zKernel.velocity(),
				properties.density(), properties.viscosity(), properties.maximumReynolds(), properties.submerged(),
				xKernel.mass(), yKernel.mass(), zKernel.mass());
	}

	private record CarrierKernel(double velocity, double mass) { }
	private record CarrierProperties(double submerged, double density, double viscosity, double maximumReynolds) { }

	/** Wet support follows the parcel's continuous volume footprint across cell and liquid interfaces. */
	private CarrierProperties carrierProperties(float px, float py, float pz,LiquidCarrierProperties properties,long[] sourceSolid) {
		double h = domain.config.cellSize();
		double x = px / h - 0.5, y = py / h - 0.5, z = pz / h - 0.5;
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
		double fx = x - x0, fy = y - y0, fz = z - z0;
		double support = 0, wet = 0, density = 0, viscosity = 0, maximumSlipDiameter = Double.POSITIVE_INFINITY;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int cx = x0 + ox, cy = y0 + oy, cz = z0 + oz;
			int cell=domain.grid.cell(cx,cy,cz);if(cell<0)continue;
			double weight = (ox == 0 ? 1 - fx : fx) * (oy == 0 ? 1 - fy : fy) * (oz == 0 ? 1 - fz : fz);
			support += weight;
			double capacity = sourceSolid==null?domain.domainCollision.effectiveOpenCellVolume(cell)
					:domain.geometry.cutCells.openCellVolume(cell)-Math.scalb((double)sourceSolid[cell],-24);
			if (weight <= 0 || capacity <= 0 || !properties.hasLiquid(cell)) continue;
			double portion = weight * Math.min(1.0, domain.liquidGrid.cellStockVolume[cell] / capacity);
			double cellViscosity = properties.viscosityPascalSeconds(cell);
			double cellDensity = properties.density(cell);
			wet += portion;
			density += portion * cellDensity;
			viscosity += portion * cellViscosity;
			maximumSlipDiameter = Math.min(maximumSlipDiameter,
					properties.maximumReynolds(cell) * cellViscosity / cellDensity);
		}
		// Validity is the intersection of constituent slip ranges, expressed in the mixture's Reynolds scale.
		return wet > 0 ? new CarrierProperties(wet / support, density / wet, viscosity / wet,
				maximumSlipDiameter * density / viscosity)
				: new CarrierProperties(0, LiquidCarrierProperties.EMPTY_LIQUID_DENSITY,
						LiquidCarrierProperties.EMPTY_LIQUID_VISCOSITY, LiquidCarrierProperties.DEFAULT_MAXIMUM_REYNOLDS);
	}

	/** The sampled impulse response is sum(w*w*m)/sum(w*m)^2; its reciprocal is the coupled mass. */
	private CarrierKernel sampleCarrier(
			float[] field, double[] faceMass, int axis, float px, float py, float pz
	) {
		float h = domain.config.cellSize();
		int nx = axis == 0 ? domain.sizeX + 1 : domain.sizeX;
		int ny = axis == 1 ? domain.sizeY + 1 : domain.sizeY;
		int nz = axis == 2 ? domain.sizeZ + 1 : domain.sizeZ;
		float gx = px / h - (axis == 0 ? 0.0F : 0.5F);
		float gy = py / h - (axis == 1 ? 0.0F : 0.5F);
		float gz = pz / h - (axis == 2 ? 0.0F : 0.5F);
		int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
		float fx = gx - x0, fy = gy - y0, fz = gz - z0;
		double weightedVelocity = 0.0, weightedMass = 0.0, squaredWeightMass = 0.0;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) continue;
			int global=domain.grid.faceAt(x,y,z,axis);if(global<0)continue;
			int face=global-domain.grid.faceOffset(axis);
			if (!(faceMass[face] > 0.0F) || !domain.domainFlowFields.granularCarrierFaceActive(axis, x, y, z)) continue;
			double weight = domain.trilinearWeight(ox, oy, oz, fx, fy, fz);
			double massWeight = weight * faceMass[face];
			weightedVelocity += massWeight * field[face];
			weightedMass += massWeight;
			squaredWeightMass += massWeight * weight;
		}
		return weightedMass > 0.0 ? new CarrierKernel(weightedVelocity / weightedMass,
				domain.unitScale.kilograms(weightedMass * weightedMass / squaredWeightMass)) : new CarrierKernel(0.0, 0.0);
	}


}
