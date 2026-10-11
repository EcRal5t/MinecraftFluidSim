package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularDepositDynamics;
import cc.ecisr.mmm.units.SignedDyadic;

/** Public result and transaction contracts inherited by the bounded-domain API. */
abstract class FluidDomainContracts extends FluidDomainStorage {
	FluidDomainContracts(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount) {
		super(sizeX, sizeY, sizeZ, config, maximumParticleCount);
	}

	record CellCandidate(int index, int distanceSquared) { }
	public record VelocitySample(float x, float y, float z) { static final VelocitySample ZERO = new VelocitySample(0.0F, 0.0F, 0.0F); }
	public record BodyFluidSample(
			double displacedVolumeCubicMetres,
			double centerXMetres, double centerYMetres, double centerZMetres,
			double velocityXMetresPerSecond, double velocityYMetresPerSecond, double velocityZMetresPerSecond
	) {
		static final BodyFluidSample EMPTY = new BodyFluidSample(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

		public BodyFluidSample {
			if (!(Double.isFinite(displacedVolumeCubicMetres) && displacedVolumeCubicMetres >= 0.0
					&& Double.isFinite(centerXMetres) && Double.isFinite(centerYMetres) && Double.isFinite(centerZMetres)
					&& Double.isFinite(velocityXMetresPerSecond) && Double.isFinite(velocityYMetresPerSecond)
					&& Double.isFinite(velocityZMetresPerSecond))) {
				throw new IllegalArgumentException("Body fluid sample must be finite with non-negative volume");
			}
			if (displacedVolumeCubicMetres == 0.0 && (centerXMetres != 0.0 || centerYMetres != 0.0
					|| centerZMetres != 0.0 || velocityXMetresPerSecond != 0.0
					|| velocityYMetresPerSecond != 0.0 || velocityZMetresPerSecond != 0.0)) {
				throw new IllegalArgumentException("Dry body fluid sample cannot carry derived observations");
			}
		}
	}
	public record VolumeAddition(float addedVolume, boolean markerBudgetLimited) { }
	public record VolumeDistribution(int activeCells, int visibleCells, int denseCells) { }
	public record SolidDistribution(int partialCells, int fullCells) { }
	/** Three spatial components; the enclosing diagnostic declares their units. */
	public record Moment3(double x, double y, double z) {
		static final Moment3 ZERO = new Moment3(0, 0, 0);
		Moment3 plus(Moment3 other) { return new Moment3(x + other.x, y + other.y, z + other.z); }
	}
	/** Per logical step: mass moments in kg*m, volume moments in cell fraction*m, centroid distance in m. */
	public record LiquidTransportStats(Moment3 massProjection, Moment3 massStorage, Moment3 volumeResidual,
			Moment3 unadvancedMassMoment, Moment3 signedMassResidual, double maximumCentroidProjection,
			int projectedGroups, int reconstructedGroups) {
		static final LiquidTransportStats INACTIVE = new LiquidTransportStats(Moment3.ZERO, Moment3.ZERO, Moment3.ZERO,
				Moment3.ZERO, Moment3.ZERO, 0, 0, 0);
		LiquidTransportStats plus(LiquidTransportStats other) {
			if (other == INACTIVE) return this;
			if (this == INACTIVE) return other;
			return new LiquidTransportStats(massProjection.plus(other.massProjection), massStorage.plus(other.massStorage),
					volumeResidual.plus(other.volumeResidual), unadvancedMassMoment.plus(other.unadvancedMassMoment),
					signedMassResidual.plus(other.signedMassResidual), Math.max(maximumCentroidProjection, other.maximumCentroidProjection),
					Math.addExact(projectedGroups, other.projectedGroups), Math.addExact(reconstructedGroups, other.reconstructedGroups));
		}
		/** Potential error from remap geometry relative to its trapezoidal momentum displacement. */
		public double gravitationalPotentialErrorJoules(double gravityMetresPerSecondSquared) {
			return gravityMetresPerSecondSquared * signedMassResidual.y;
		}
	}
	/** Stage kinetic-energy accounting for liquid and granular carriers; potential includes uniform beds. */
	public record MechanicalStepAudit(
			boolean captured,
			double initialParticleKineticEnergyJoules,
			double finalParticleKineticEnergyJoules,
			double initialGravitationalPotentialEnergyJoules,
			double finalGravitationalPotentialEnergyJoules,
			double particleToGridTransferJoules,
			double porousBoundaryWorkJoules,
			double gravityWorkJoules,
			double thermalBuoyancyWorkJoules,
			double surfaceTensionWorkJoules,
			double viscousWorkJoules,
			double fixedBoundaryWorkJoules,
			double pressureProjectionWorkJoules,
			double gridToParticleTransferJoules,
			double particleCollisionWorkJoules,
			double volumeTransportTransferJoules,
			double carrierInventoryTransferJoules,
			double reseedTransferJoules,
			SignedDyadic thermalInventoryMechanicalWork,
			double closureResidualJoules
	) {
		static MechanicalStepAudit notCaptured() {
			return new MechanicalStepAudit(false, 0.0, 0.0, 0.0, 0.0,
					0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
					0.0, 0.0, 0.0, 0.0, 0.0, SignedDyadic.ZERO, 0.0);
		}
	}
	record MaterialInventory(double volume, double mass, double centerY) { }
	/**
	 * Carrier viscosity evaluations that left the material's declared temperature domain.
	 *
	 * <p>Counted per cell per property build, so a cell read by many parcels, Darcy faces or the bed
	 * shear closure in one revision counts once. A candidate that never publishes accumulates into
	 * this record only through the successful step's stats.
	 */
	public record CarrierViscosityStats(int extrapolatedCells, String sample) {
		public CarrierViscosityStats {
			if (extrapolatedCells < 0) throw new IllegalArgumentException("Extrapolated cell count is negative");
			if (extrapolatedCells == 0) sample = "";
			else java.util.Objects.requireNonNull(sample, "sample");
		}

		public boolean extrapolated() { return extrapolatedCells > 0; }
	}

	/** Liquid momentum supplied by fixed walls; dissipation uses the viscous control-volume metric. */
	public record ViscousStepStats(int stressRows, double spectralBoundPerSecond,
			double boundaryImpulseX, double boundaryImpulseY, double boundaryImpulseZ, double dissipatedJoules,
			double roundoffImpulseX, double roundoffImpulseY, double roundoffImpulseZ) {
		static final ViscousStepStats INACTIVE = new ViscousStepStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
		ViscousStepStats plus(ViscousStepStats other) {
			return new ViscousStepStats(Math.addExact(stressRows, other.stressRows),
					Math.max(spectralBoundPerSecond, other.spectralBoundPerSecond),
					boundaryImpulseX + other.boundaryImpulseX, boundaryImpulseY + other.boundaryImpulseY,
					boundaryImpulseZ + other.boundaryImpulseZ, dissipatedJoules + other.dissipatedJoules,
					roundoffImpulseX + other.roundoffImpulseX, roundoffImpulseY + other.roundoffImpulseY,
					roundoffImpulseZ + other.roundoffImpulseZ);
		}
	}

	public record StepStats(
			int substeps,
			int pressureIterations,
			float pressureResidual,
			float volumeBefore,
			float volumeAfter,
			double massBefore,
			double massAfter,
			int reseededMerges,
			int reseededSplits,
			int reseedBudgetSkipped,
			int reseedGeometrySkipped,
			int reseedConservationSkipped,
			double reseedLedgerError,
			double reseedMassLedgerError,
			int chemistryDirtyCells,
			int chemistryEvaluations,
			int chemistryChangedMarkers,
			FluidDomain.ThermalPhaseStats condensedPhase,
			FluidDomain.GasThermalPhaseStats gasPhase,
			MechanicalStepAudit mechanicalAudit,
			FluidDomain.ThermalAxisStats thermalAxis,
			GranularDepositDynamics.Stats granularTransition,
			CarrierViscosityStats carrierViscosity,
			ViscousStepStats viscosity,
			LiquidTransportStats liquidTransport
	) {
		public StepStats {
			java.util.Objects.requireNonNull(thermalAxis, "thermalAxis");
			java.util.Objects.requireNonNull(granularTransition, "granularTransition");
			java.util.Objects.requireNonNull(carrierViscosity, "carrierViscosity");
			java.util.Objects.requireNonNull(viscosity, "viscosity");
			java.util.Objects.requireNonNull(liquidTransport, "liquidTransport");
		}
		public float relativeVolumeError() { return volumeBefore == 0.0F ? 0.0F : Math.abs(volumeAfter - volumeBefore) / volumeBefore; }
		public double relativeMassError() { return massBefore == 0.0 ? 0.0 : Math.abs(massAfter - massBefore) / massBefore; }
	}

	public static final class ParticleBudgetExceededException extends IllegalStateException {
		final int requiredMarkers;
		final int availableMarkers;

		ParticleBudgetExceededException(int requiredMarkers, int availableMarkers) {
			super("FLIP marker budget exceeded: requires " + requiredMarkers + ", available " + Math.max(0, availableMarkers));
			this.requiredMarkers = requiredMarkers;
			this.availableMarkers = Math.max(0, availableMarkers);
		}

		public int requiredMarkers() { return requiredMarkers; }
		public int availableMarkers() { return availableMarkers; }
	}

	/** Rejects a complete mechanics candidate when its pressure projection is not converged. */
	public static final class PressureSolveBlockedException extends IllegalStateException {
		PressureSolveBlockedException(String message) { super(message); }
	}
}
