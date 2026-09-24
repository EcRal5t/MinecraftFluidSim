package cc.ecisr.mmm.granular;

/** Schiller-Naumann buoyancy/drag update using kg and SI impulse, including equal fluid reaction impulse. */
public final class GranularMechanics {
	private static final double REYNOLDS_EPSILON = 1.0e-12;
	private static final double GRAVITY = 9.81;

	private GranularMechanics() { }

	/** Collision footprint of a representative parcel, shared by bed release and advection. */
	public static float representativeRadius(long solidVolumeSvq, float cellSize) {
		double radius = Math.cbrt(3.0 * SolidVolume.toCellVolume(solidVolumeSvq) / (4.0 * Math.PI)) * cellSize;
		return (float) Math.max(cellSize * .02F, Math.min(cellSize * .20F, radius));
	}

	public static StepStats stepVelocities(
			GranularParcelStore parcels, GranularRegistry registry, Environment environment,
			double dt
	) {
		return stepVelocities(parcels, registry, environment, GRAVITY, dt);
	}

	public static StepStats stepVelocities(
			GranularParcelStore parcels, GranularRegistry registry, Environment environment,
			double gravity, double dt
	) {
		if (parcels == null || registry == null || environment == null) {
			throw new IllegalArgumentException("Granular mechanics input is missing");
		}
		if (!(Double.isFinite(gravity) && gravity >= 0.0
				&& Double.isFinite(dt) && dt > 0.0)) {
			throw new IllegalArgumentException("Granular mechanics gravity and dt are invalid");
		}
		validateCarrierRange(parcels, registry, environment);
		double maximumSpeed = 0.0, totalFluidImpulseX = 0.0, totalFluidImpulseY = 0.0, totalFluidImpulseZ = 0.0;
		double boundaryImpulseX = 0.0, boundaryImpulseY = 0.0, boundaryImpulseZ = 0.0;
		int submerged = 0;
		for (int index = 0; index < parcels.count(); index++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index);
			ParticulateMaterial material = registry.material(parcel.materialHandle());
			FluidSample fluid = environment.sample(parcel.x(), parcel.y(), parcel.z());
			validateCarrierRange(parcel, material, fluid);
			double fraction = clamp(fluid.submergedFraction(), 0.0, 1.0);
			double oldX = parcel.velocityX(), oldY = parcel.velocityY(), oldZ = parcel.velocityZ();
			double newX = oldX, newY = oldY, newZ = oldZ;
			double fluidX = 0.0, fluidY = 0.0, fluidZ = 0.0;
			double solidVolumeM3 = parcel.solidVolumeSvq() / (double) SolidVolume.SVQ_PER_BV;
			double massKg = parcel.mass();
			if (!(Double.isFinite(massKg) && massKg > 0.0)) throw new IllegalStateException("Invalid parcel mass scale");
			double buoyancyAcceleration = fluid.hasLiquid() && !environment.buoyancyResolvedByPressure()
					? fluid.density() * solidVolumeM3 * fraction * gravity / massKg : 0.0;
			double externalAccelerationY = -gravity + buoyancyAcceleration;
			double dragRate = 0.0;
			if (fluid.hasLiquid() && fraction > 0.0) {
				submerged++;
				double slipX = oldX - fluid.velocityX(), slipY = oldY - fluid.velocityY(), slipZ = oldZ - fluid.velocityZ();
				double slip = Math.sqrt(slipX * slipX + slipY * slipY + slipZ * slipZ);
				double diameter = material.primaryGrainDiameterMeters();
				double reynolds = fluid.density() * diameter * slip / fluid.dynamicViscosity();
				double primaryVolume = Math.PI * diameter * diameter * diameter / 6.0;
				double representedGrains = solidVolumeM3 / primaryVolume * fraction;
				double area = representedGrains * Math.PI * diameter * diameter / 4.0;
				double linearDrag = reynolds <= REYNOLDS_EPSILON
						? 3.0 * Math.PI * fluid.dynamicViscosity() * diameter * representedGrains
						: 0.5 * dragCoefficient(reynolds) * fluid.density() * area * slip;
				dragRate = linearDrag / massKg;
				CoupledStep xStep = coupledStep(oldX, massKg, 0.0, fluid.velocityX(),
						fluid.coupledMassX(), 0.0, dragRate, dt);
				CoupledStep yStep = coupledStep(oldY, massKg, externalAccelerationY, fluid.velocityY(),
						fluid.coupledMassY(), -massKg * buoyancyAcceleration, dragRate, dt);
				CoupledStep zStep = coupledStep(oldZ, massKg, 0.0, fluid.velocityZ(),
						fluid.coupledMassZ(), 0.0, dragRate, dt);
				newX = xStep.particleVelocity(); newY = yStep.particleVelocity(); newZ = zStep.particleVelocity();
				fluidX = xStep.fluidImpulse(); fluidY = yStep.fluidImpulse(); fluidZ = zStep.fluidImpulse();
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
				FluidImpulseApplication application = environment.addFluidImpulse(
						parcel.x(), parcel.y(), parcel.z(), fluidX, fluidY, fluidZ);
				if (application == null) throw new IllegalStateException("Granular environment returned no impulse application");
				totalFluidImpulseX += application.appliedX(); totalFluidImpulseY += application.appliedY();
				totalFluidImpulseZ += application.appliedZ();
				boundaryImpulseX += application.boundaryX() + massKg * (newX - nextX);
				boundaryImpulseY += application.boundaryY() + massKg * (newY - nextY);
				boundaryImpulseZ += application.boundaryZ() + massKg * (newZ - nextZ);
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
			var parcel = parcels.parcel(index);
			validateCarrierRange(parcel, registry.material(parcel.materialHandle()),
					environment.sample(parcel.x(), parcel.y(), parcel.z()));
		}
	}

	public static void validateCarrierRange(
			GranularParcelStore.Parcel parcel, ParticulateMaterial material, FluidSample fluid
	) {
		if (fluid == null) throw new ValidityExceededException("Granular environment returned no fluid sample");

		double fraction = clamp(fluid.submergedFraction(), 0.0, 1.0);
		if (!fluid.hasLiquid() || fraction <= 0.0) return;
		double slipX = parcel.velocityX() - fluid.velocityX();
		double slipY = parcel.velocityY() - fluid.velocityY();
		double slipZ = parcel.velocityZ() - fluid.velocityZ();
		double slip = Math.sqrt(slipX * slipX + slipY * slipY + slipZ * slipZ);
		double diameter = material.primaryGrainDiameterMeters();
		double reynolds = fluid.density() * diameter * slip / fluid.dynamicViscosity();
		if (!(Double.isFinite(reynolds) && reynolds >= 0.0)) {
			throw new ValidityExceededException("Granular carrier Reynolds number is not representable: Re="
					+ reynolds + ", maximum=" + fluid.maximumReynolds() + ", slip=" + slip
					+ " m/s, diameter=" + diameter + " m, density=" + fluid.density()
					+ " kg/m^3, viscosity=" + fluid.dynamicViscosity() + " Pa*s, particle=("
					+ parcel.velocityX() + "," + parcel.velocityY() + "," + parcel.velocityZ()
					+ "), fluid=(" + fluid.velocityX() + "," + fluid.velocityY() + ","
					+ fluid.velocityZ() + ") m/s, submerged=" + fraction
					+ ", position=(" + parcel.x() + "," + parcel.y() + "," + parcel.z() + ") m");
		}

	}

	private record CoupledStep(double particleVelocity, double fluidImpulse) { }

	private static CoupledStep coupledStep(
			double particle, double particleMass, double particleAcceleration,
			double fluid, double fluidMass, double fluidForce, double dragRate, double dt
	) {
		if (fluidMass == 0.0) return new CoupledStep(particle + particleAcceleration * dt, fluidForce * dt);
		double coupledDragRate = dragRate * (1.0 + particleMass / fluidMass);
		double relaxation = Math.exp(-coupledDragRate * dt);
		double response = coupledDragRate == 0.0 ? dt : -Math.expm1(-coupledDragRate * dt) / coupledDragRate;
		double fluidAcceleration = fluidForce / fluidMass;
		double combinedMass = particleMass + fluidMass;
		double center = (particleMass * particle + fluidMass * fluid) / combinedMass
				+ (particleMass * particleAcceleration + fluidForce) / combinedMass * dt;
		double relative = (particle - fluid) * relaxation
				+ (particleAcceleration - fluidAcceleration) * response;
		double fluidFraction = fluidMass / combinedMass;
		double particleFraction = particleMass / combinedMass;
		double reducedMass = particleMass * fluidFraction;
		// Integrate the fluid impulse directly: parcel storage roundoff belongs to the parcel,
		// and can greatly exceed the physical momentum of a thin fluid carrier.
		double impulse = reducedMass * (particle - fluid) * -Math.expm1(-coupledDragRate * dt)
				+ reducedMass * particleAcceleration * (dt - response)
				+ fluidForce * (fluidFraction * dt + particleFraction * response);
		return new CoupledStep(center + fluidFraction * relative, impulse);
	}

	/** Continuous connection between Schiller-Naumann and the high-Reynolds sphere limit. */
	public static double dragResistanceFactor(double reynolds) {
		if (!(Double.isFinite(reynolds) && reynolds >= 0.0)) throw new IllegalArgumentException("Invalid Reynolds number");
		return Math.max(1.0 + 0.15 * Math.pow(reynolds, 0.687), 0.44 * reynolds / 24.0);
	}

	public static double dragCoefficient(double reynolds) {
		double factor = dragResistanceFactor(reynolds);
		return reynolds <= REYNOLDS_EPSILON ? Double.POSITIVE_INFINITY : 24.0 / reynolds * factor;
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
			double density, double dynamicViscosity, double maximumReynolds, double submergedFraction,
			double coupledMassX, double coupledMassY, double coupledMassZ
	) {
		public FluidSample {
			boolean couplingValid = Double.isFinite(coupledMassX) && coupledMassX >= 0.0
					&& Double.isFinite(coupledMassY) && coupledMassY >= 0.0
					&& Double.isFinite(coupledMassZ) && coupledMassZ >= 0.0;
			if (!(Double.isFinite(velocityX) && Double.isFinite(velocityY) && Double.isFinite(velocityZ)
					&& Double.isFinite(density) && density > 0.0
					&& Double.isFinite(dynamicViscosity) && dynamicViscosity > 0.0
					&& Double.isFinite(maximumReynolds) && maximumReynolds > 0.0
					&& Double.isFinite(submergedFraction) && couplingValid)) {
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
		default boolean buoyancyResolvedByPressure() { return false; }
		FluidSample sample(float x, float y, float z);
		FluidImpulseApplication addFluidImpulse(
				float x, float y, float z, double impulseX, double impulseY, double impulseZ);
	}

	public static final class ValidityExceededException extends IllegalStateException {
		public ValidityExceededException(String message) { super(message); }
	}
}
