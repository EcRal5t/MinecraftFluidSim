package mmm.granular;

/** Schiller-Naumann buoyancy/drag update using kg and SI impulse, including equal fluid reaction impulse. */
public final class GranularMechanics {
	private static final double REYNOLDS_EPSILON = 1.0e-12;

	private GranularMechanics() { }

	/** Collision footprint of a representative parcel, shared by bed release and advection. */
	public static float representativeRadius(long solidVolumeSvq, float cellSize) {
		double radius = Math.cbrt(3.0 * SolidVolume.toCellVolume(solidVolumeSvq) / (4.0 * Math.PI)) * cellSize;
		return (float) Math.max(cellSize * .02F, Math.min(cellSize * .20F, radius));
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

	/** Continuous connection between Schiller-Naumann and the high-Reynolds sphere limit. */
	public static double dragResistanceFactor(double reynolds) {
		if (!(Double.isFinite(reynolds) && reynolds >= 0.0)) throw new IllegalArgumentException("Invalid Reynolds number");
		return Math.max(1.0 + 0.15 * Math.pow(reynolds, 0.687), 0.44 * reynolds / 24.0);
	}

	public static double dragCoefficient(double reynolds) {
		double factor = dragResistanceFactor(reynolds);
		return reynolds <= REYNOLDS_EPSILON ? Double.POSITIVE_INFINITY : 24.0 / reynolds * factor;
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
					&& Double.isFinite(density) && (hasLiquid ? density > 0.0 : density >= 0.0)
					&& Double.isFinite(dynamicViscosity) && (hasLiquid ? dynamicViscosity > 0.0 : dynamicViscosity >= 0.0)
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
