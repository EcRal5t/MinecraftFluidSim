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
	public record MechanicalStepAudit(
			boolean captured,
			double initialParticleKineticEnergyJoules,
			double finalParticleKineticEnergyJoules,
			double initialGravitationalPotentialEnergyJoules,
			double finalGravitationalPotentialEnergyJoules,
			double particleToGridTransferJoules,
			double granularCouplingWorkJoules,
			double porousBoundaryWorkJoules,
			double gravityWorkJoules,
			double thermalBuoyancyWorkJoules,
			double surfaceTensionWorkJoules,
			double fixedBoundaryWorkJoules,
			double pressureProjectionWorkJoules,
			double gridToParticleTransferJoules,
			double particleCollisionWorkJoules,
			double carrierInventoryTransferJoules,
			double reseedTransferJoules,
			SignedDyadic thermalInventoryMechanicalWork,
			double closureResidualJoules
	) {
		static MechanicalStepAudit notCaptured() {
			return new MechanicalStepAudit(false, 0.0, 0.0, 0.0, 0.0,
					0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
					0.0, 0.0, 0.0, 0.0, SignedDyadic.ZERO, 0.0);
		}
	}
	record MaterialInventory(double volume, double mass, double centerY) { }
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
			GranularDepositDynamics.Stats granularTransition
	) {
		public StepStats {
			java.util.Objects.requireNonNull(thermalAxis, "thermalAxis");
			java.util.Objects.requireNonNull(granularTransition, "granularTransition");
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
