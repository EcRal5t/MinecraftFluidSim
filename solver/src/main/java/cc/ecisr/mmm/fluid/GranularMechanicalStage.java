package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularMechanics;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.units.SolverUnitScale;

/** Predicts gravity, validates the joint pressure/drag solution, and previews collision. */
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
		void addBoundaryImpulse(FlipGranularCollision.BoundaryImpulse impulse);
		boolean granularOccupancyFitsGeometry(GranularCouplingFields coupling);
		/** Derived per-cell liquid carrier properties for the current revision. */
		LiquidCarrierProperties liquidCarrierProperties();
		boolean massWeightedP2G();
		boolean granularCarrierFaceActive(int axis, int x, int y, int z);
		float effectiveOpenCellVolume(int cell);
		float trilinearWeight(int ox, int oy, int oz, float fx, float fy, float fz);
	}

	private final Workspace state;
	private final Access access;

	GranularMechanicalStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
	}

	GranularMechanics.StepStats predictVelocities(float dt) {
		if (!access.hasGranularInventory()) return null;
		var granular = access.granularState();
		int submerged = 0;
		for (int index = 0; index < granular.parcels.count(); index++) {
			var p = granular.parcels.parcel(index);
			var fluid = sample(p.x(), p.y(), p.z());
			if (fluid.hasLiquid()) submerged++;
		}
		double maximum = 0, roundingY = 0;
		for (int index = 0; index < granular.parcels.count(); index++) {
			var p = granular.parcels.parcel(index);
			double predictedY = p.velocityY() - state.config.gravity() * (double) dt;
			float vy = (float) predictedY;
			roundingY += p.mass() * (predictedY - vy);
			granular.parcels.overwrite(index, new cc.ecisr.mmm.granular.GranularParcelStore.Parcel(
					p.x(), p.y(), p.z(), p.velocityX(), vy, p.velocityZ(), p.solidVolumeSvq(), p.mass(),
					p.materialHandle(), p.solidCompositionHandle(), p.contactAge(), p.flags()));
			maximum = Math.max(maximum, Math.sqrt(p.velocityX() * (double) p.velocityX()
					+ vy * (double) vy + p.velocityZ() * (double) p.velocityZ()));
		}
		return new GranularMechanics.StepStats(granular.parcels.count(), submerged, maximum, 0, 0, 0, 0, roundingY, 0);
	}

	/** The implicit constitutive law is evaluated at the solved, collocated end-of-step slip. */
	void validateVelocities() {
		if (!access.hasGranularInventory()) return;
		var granular = access.granularState();
		for (int index = 0; index < granular.parcels.count(); index++) {
			var p = granular.parcels.parcel(index);
			GranularMechanics.validateCarrierRange(p, granular.registry.material(p.materialHandle()),
					sample(p.x(), p.y(), p.z()));
		}
	}

	void previewCollision(float dt) {
		if (!access.hasGranularInventory()) return;
		GranularDomainState state = access.granularState();
		// Pressure can accelerate a resting parcel beyond the initial velocity-based time step.
		double maximum = 0;
		for (int index = 0; index < state.parcels.count(); index++) {
			var parcel = state.parcels.parcel(index);
			maximum = Math.max(maximum, Math.abs((double) parcel.velocityX())
					+ Math.abs(parcel.velocityY()) + Math.abs(parcel.velocityZ()));
		}
		double distance = this.state.config.cellSize() * this.state.config.maxCourantNumber();
		if (maximum * dt > distance * (1 + 8 * Math.ulp(1.0F))) {
			throw new MechanicalTimeRefinement((int) Math.ceil(maximum * this.state.config.timeStep() / distance));
		}
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
	}

	/** Applies the already-previewed collision capacity after marker advection. */
	void commitCollision() {
		if (access.hasGranularInventory()) {
			access.addBoundaryImpulse(access.granularCollision().commit(access.granularState().parcels));
		}
	}

	GranularMechanics.FluidSample sample(float x, float y, float z) {
		CarrierProperties properties = carrierProperties(x, y, z);
		if (!(properties.submerged() > 0)) {
			return new GranularMechanics.FluidSample(false, 0, 0, 0, LiquidCarrierProperties.AIR_DENSITY,
					LiquidCarrierProperties.AIR_VISCOSITY, LiquidCarrierProperties.AIR_MAXIMUM_REYNOLDS, 0, 0, 0, 0);
		}
		CarrierKernel xKernel = sampleCarrier(state.mac.velocityX, state.mac.p2gWeightX, 0, x, y, z);
		CarrierKernel yKernel = sampleCarrier(state.mac.velocityY, state.mac.p2gWeightY, 1, x, y, z);
		CarrierKernel zKernel = sampleCarrier(state.mac.velocityZ, state.mac.p2gWeightZ, 2, x, y, z);
		return new GranularMechanics.FluidSample(true, xKernel.velocity(), yKernel.velocity(), zKernel.velocity(),
				properties.density(), properties.viscosity(), properties.maximumReynolds(), properties.submerged(),
				xKernel.mass(), yKernel.mass(), zKernel.mass());
	}

	private record CarrierKernel(double velocity, double mass) { }
	private record CarrierProperties(double submerged, double density, double viscosity, double maximumReynolds) { }

	/** Wet support follows the parcel's continuous volume footprint across cell and liquid interfaces. */
	private CarrierProperties carrierProperties(float px, float py, float pz) {
		LiquidCarrierProperties properties = access.liquidCarrierProperties();
		double h = state.config.cellSize();
		double x = px / h - 0.5, y = py / h - 0.5, z = pz / h - 0.5;
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
		double fx = x - x0, fy = y - y0, fz = z - z0;
		double support = 0, wet = 0, density = 0, viscosity = 0, maximumSlipDiameter = Double.POSITIVE_INFINITY;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int cx = x0 + ox, cy = y0 + oy, cz = z0 + oz;
			if (cx < 0 || cx >= state.sizeX || cy < 0 || cy >= state.sizeY || cz < 0 || cz >= state.sizeZ) continue;
			double weight = (ox == 0 ? 1 - fx : fx) * (oy == 0 ? 1 - fy : fy) * (oz == 0 ? 1 - fz : fz);
			support += weight;
			int cell = (cz * state.sizeY + cy) * state.sizeX + cx;
			double capacity = access.effectiveOpenCellVolume(cell);
			if (weight <= 0 || capacity <= 0 || !properties.hasLiquid(cell)) continue;
			double portion = weight * Math.min(1.0, state.markerGrid.exactCellMarkerVolume[cell] / capacity);
			double cellViscosity = properties.viscosityPaPerS(cell);
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
				: new CarrierProperties(0, LiquidCarrierProperties.AIR_DENSITY,
						LiquidCarrierProperties.AIR_VISCOSITY, LiquidCarrierProperties.AIR_MAXIMUM_REYNOLDS);
	}

	/** The sampled impulse response is sum(w*w*m)/sum(w*m)^2; its reciprocal is the coupled mass. */
	private CarrierKernel sampleCarrier(
			float[] field, float[] faceMass, int axis, float px, float py, float pz
	) {
		float h = state.config.cellSize();
		int nx = axis == 0 ? state.sizeX + 1 : state.sizeX;
		int ny = axis == 1 ? state.sizeY + 1 : state.sizeY;
		int nz = axis == 2 ? state.sizeZ + 1 : state.sizeZ;
		float gx = px / h - (axis == 0 ? 0.0F : 0.5F);
		float gy = py / h - (axis == 1 ? 0.0F : 0.5F);
		float gz = pz / h - (axis == 2 ? 0.0F : 0.5F);
		int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
		float fx = gx - x0, fy = gy - y0, fz = gz - z0;
		double weightedVelocity = 0.0, weightedMass = 0.0, squaredWeightMass = 0.0;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) continue;
			int face = (z * ny + y) * nx + x;
			if (!(faceMass[face] > 0.0F) || !access.granularCarrierFaceActive(axis, x, y, z)) continue;
			double weight = access.trilinearWeight(ox, oy, oz, fx, fy, fz);
			double massWeight = weight * faceMass[face];
			weightedVelocity += massWeight * field[face];
			weightedMass += massWeight;
			squaredWeightMass += massWeight * weight;
		}
		return weightedMass > 0.0 ? new CarrierKernel(weightedVelocity / weightedMass,
				state.unitScale.kilograms(weightedMass * weightedMass / squaredWeightMass * faceMassScale())) : new CarrierKernel(0.0, 0.0);
	}

	private double faceMassScale() { return access.massWeightedP2G() ? 1.0 : state.config.density(); }

}
