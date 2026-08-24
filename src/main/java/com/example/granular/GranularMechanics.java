package com.example.granular;

/** Schiller-Naumann buoyancy/drag update for active parcels, including equal fluid reaction impulse. */
public final class GranularMechanics {
	private static final double REYNOLDS_EPSILON = 1.0e-12;
	private static final double GRAVITY = 9.81;

	private GranularMechanics() { }

	public static StepStats stepVelocities(
			GranularParcelStore parcels, GranularRegistry registry, Environment environment,
			double cellSizeMeters, double dt
	) {
		return stepVelocities(parcels, registry, environment, cellSizeMeters, GRAVITY, dt);
	}

	public static StepStats stepVelocities(
			GranularParcelStore parcels, GranularRegistry registry, Environment environment,
			double cellSizeMeters, double gravity, double dt
	) {
		if (parcels == null || registry == null || environment == null) {
			throw new IllegalArgumentException("Granular mechanics input is missing");
		}
		if (!(Double.isFinite(cellSizeMeters) && cellSizeMeters > 0.0 && Double.isFinite(gravity) && gravity >= 0.0
				&& Double.isFinite(dt) && dt > 0.0)) {
			throw new IllegalArgumentException("Granular mechanics scale and dt must be finite and positive");
		}
		validateCarrierRange(parcels, registry, environment);
		double maximumSpeed = 0.0, totalFluidImpulseX = 0.0, totalFluidImpulseY = 0.0, totalFluidImpulseZ = 0.0;
		double boundaryImpulseX = 0.0, boundaryImpulseY = 0.0, boundaryImpulseZ = 0.0;
		int submerged = 0;
		for (int index = 0; index < parcels.count(); index++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index);
			ParticulateMaterial material = registry.material(parcel.materialHandle());
			FluidSample fluid = environment.sample(parcel.x(), parcel.y(), parcel.z());
			if (fluid == null) throw new IllegalStateException("Granular environment returned no fluid sample");
			double fraction = clamp(fluid.submergedFraction(), 0.0, 1.0);
			double oldX = parcel.velocityX(), oldY = parcel.velocityY(), oldZ = parcel.velocityZ();
			double newX = oldX, newY = oldY, newZ = oldZ;
			double externalAccelerationY = -gravity;
			double dragRate = 0.0;
			if (fluid.hasLiquid() && fraction > 0.0) {
				submerged++;
				double cellVolumeM3 = cellSizeMeters * cellSizeMeters * cellSizeMeters;
				double solidVolumeM3 = parcel.solidVolumeSvq() / (double) SolidVolume.SVQ_PER_BV;
				double massKg = parcel.mass() * cellVolumeM3;
				if (!(Double.isFinite(massKg) && massKg > 0.0)) throw new IllegalStateException("Invalid parcel mass scale");
				externalAccelerationY += fluid.density() * solidVolumeM3 * fraction * gravity / massKg;
				double slipX = oldX - fluid.velocityX(), slipY = oldY - fluid.velocityY(), slipZ = oldZ - fluid.velocityZ();
				double slip = Math.sqrt(slipX * slipX + slipY * slipY + slipZ * slipZ);
				double diameter = material.primaryGrainDiameterMeters();
				double reynolds = fluid.density() * diameter * slip / fluid.dynamicViscosity();
				if (reynolds > fluid.maximumReynolds()) {
					throw new IllegalStateException("Granular carrier Reynolds validity range exceeded");
				}
				double primaryVolume = Math.PI * diameter * diameter * diameter / 6.0;
				double representedGrains = solidVolumeM3 / primaryVolume;
				double area = representedGrains * Math.PI * diameter * diameter / 4.0;
				double linearDrag = reynolds <= REYNOLDS_EPSILON
						? 3.0 * Math.PI * fluid.dynamicViscosity() * diameter * representedGrains
						: 0.5 * dragCoefficient(reynolds) * fluid.density() * area * slip;
				dragRate = linearDrag / massKg;
				double relaxation = Math.exp(-dragRate * dt);
				newX = fluid.velocityX() + slipX * relaxation;
				newY = fluid.velocityY() + slipY * relaxation
						+ externalAccelerationY * (dragRate <= REYNOLDS_EPSILON
						? dt : (1.0 - relaxation) / dragRate);
				newZ = fluid.velocityZ() + slipZ * relaxation;
			} else {
				newY += externalAccelerationY * dt;
			}
			float nextX = finiteFloat(newX, "parcel velocity x");
			float nextY = finiteFloat(newY, "parcel velocity y");
			float nextZ = finiteFloat(newZ, "parcel velocity z");
			parcels.overwrite(index, new GranularParcelStore.Parcel(parcel.x(), parcel.y(), parcel.z(),
					nextX, nextY, nextZ, parcel.solidVolumeSvq(), parcel.mass(), parcel.materialHandle(),
					parcel.solidCompositionHandle(), parcel.contactAge(), parcel.flags()));
			if (fluid.hasLiquid() && fraction > 0.0) {
				// Parcel momentum change minus gravity/buoyancy external impulse is the drag impulse.
				double hydrodynamicX = parcel.mass() * (nextX - oldX);
				double hydrodynamicY = parcel.mass() * (nextY - oldY + gravity * dt);
				double hydrodynamicZ = parcel.mass() * (nextZ - oldZ);
				double fluidX = -hydrodynamicX, fluidY = -hydrodynamicY, fluidZ = -hydrodynamicZ;
				FluidImpulseApplication application = environment.addFluidImpulse(
						parcel.x(), parcel.y(), parcel.z(), fluidX, fluidY, fluidZ);
				if (application == null) throw new IllegalStateException("Granular environment returned no impulse application");
				totalFluidImpulseX += application.appliedX(); totalFluidImpulseY += application.appliedY();
				totalFluidImpulseZ += application.appliedZ();
				boundaryImpulseX += application.boundaryX(); boundaryImpulseY += application.boundaryY();
				boundaryImpulseZ += application.boundaryZ();
			}
			maximumSpeed = Math.max(maximumSpeed, Math.sqrt(newX * newX + newY * newY + newZ * newZ));
		}
		return new StepStats(parcels.count(), submerged, maximumSpeed,
				totalFluidImpulseX, totalFluidImpulseY, totalFluidImpulseZ,
				boundaryImpulseX, boundaryImpulseY, boundaryImpulseZ);
	}

	private static void validateCarrierRange(
			GranularParcelStore parcels, GranularRegistry registry, Environment environment
	) {
		for (int index = 0; index < parcels.count(); index++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index);
			FluidSample fluid = environment.sample(parcel.x(), parcel.y(), parcel.z());
			if (fluid == null) throw new ValidityExceededException("Granular environment returned no fluid sample");
			if (!fluid.hasLiquid() || clamp(fluid.submergedFraction(), 0.0, 1.0) <= 0.0) continue;
			double slipX = parcel.velocityX() - fluid.velocityX();
			double slipY = parcel.velocityY() - fluid.velocityY();
			double slipZ = parcel.velocityZ() - fluid.velocityZ();
			double slip = Math.sqrt(slipX * slipX + slipY * slipY + slipZ * slipZ);
			double diameter = registry.material(parcel.materialHandle()).primaryGrainDiameterMeters();
			double reynolds = fluid.density() * diameter * slip / fluid.dynamicViscosity();
			if (!(Double.isFinite(reynolds) && reynolds <= fluid.maximumReynolds())) {
				throw new ValidityExceededException("Granular carrier Reynolds validity range exceeded");
			}
		}
	}

	public static double dragCoefficient(double reynolds) {
		if (!(Double.isFinite(reynolds) && reynolds >= 0.0)) throw new IllegalArgumentException("Invalid Reynolds number");
		if (reynolds <= REYNOLDS_EPSILON) return Double.POSITIVE_INFINITY;
		return reynolds < 1_000.0
				? 24.0 / reynolds * (1.0 + 0.15 * Math.pow(reynolds, 0.687)) : 0.44;
	}

	private static float finiteFloat(double value, String label) {
		float result = (float) value;
		if (!Float.isFinite(result)) throw new IllegalStateException(label + " is not finite");
		return result;
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	public record FluidSample(
			boolean hasLiquid, double velocityX, double velocityY, double velocityZ,
			double density, double dynamicViscosity, double maximumReynolds, double submergedFraction
	) {
		public FluidSample {
			if (!(Double.isFinite(velocityX) && Double.isFinite(velocityY) && Double.isFinite(velocityZ)
					&& Double.isFinite(density) && density > 0.0
					&& Double.isFinite(dynamicViscosity) && dynamicViscosity > 0.0
					&& Double.isFinite(maximumReynolds) && maximumReynolds > 0.0
					&& Double.isFinite(submergedFraction))) {
				throw new IllegalArgumentException("Invalid granular fluid sample");
			}
		}
	}

	public record StepStats(
			int parcels, int submergedParcels, double maximumSpeed,
			double fluidImpulseX, double fluidImpulseY, double fluidImpulseZ,
			double boundaryImpulseX, double boundaryImpulseY, double boundaryImpulseZ
	) { }

	public record FluidImpulseApplication(
			double appliedX, double appliedY, double appliedZ,
			double boundaryX, double boundaryY, double boundaryZ
	) { }

	public interface Environment {
		FluidSample sample(float x, float y, float z);
		FluidImpulseApplication addFluidImpulse(
				float x, float y, float z, double impulseX, double impulseY, double impulseZ);
	}

	public static final class ValidityExceededException extends IllegalStateException {
		public ValidityExceededException(String message) { super(message); }
	}
}
