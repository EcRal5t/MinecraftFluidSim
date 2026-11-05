package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalCarrierContext;
import mmm.chemistry.RegionChemistry;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositDynamics;
import mmm.granular.GranularPhaseTransferEngine;
import mmm.units.SolverUnitScale;
import java.util.List;
import mmm.units.SignedDyadic;
import mmm.chemistry.ChemicalTransferLedger;
import java.util.ArrayList;
import mmm.fluid.air.AirNetwork;
import mmm.granular.GranularParcelStore;
import mmm.thermal.ThermalTransport;
import mmm.world.MovingBodyFluidCoupling;
import mmm.thermal.GasEnergyStore;
import mmm.fluid.air.GasAuditLedger;
import mmm.granular.GranularAuditLedger;
import mmm.granular.GranularDepositStore;
import mmm.thermal.CoherentPhaseStore;
import mmm.chemistry.ChemicalRegistry;
import java.util.Map;
import mmm.thermal.FiniteDeviceExchangeCoordinator;
import mmm.thermal.ThermalOwnerSnapshot;
import mmm.chemistry.ChemicalDisplaySnapshot;
import mmm.granular.SolidCompositionPool;
import mmm.thermal.FiniteThermalExchange;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalDisplaySnapshot;
import mmm.fluid.air.GasRegistry;
import mmm.fluid.air.GasStateSnapshot;
import mmm.granular.GranularDisplaySnapshot;
import mmm.granular.GranularRegistry;
import mmm.granular.GranularStateSnapshot;
import mmm.granular.GranularTransferLedger;
import mmm.thermal.ThermalAuditLedger;
import mmm.thermal.ThermalCellGrid;

/** Shared observations, transactions and step results for fluid operations. */
public final class FluidDomainContracts {
	private FluidDomainContracts() { }

	public record ActivityStats(int groupCount, int sleepingGroups, int sleepingCells, int activeCells,
                                int blockedGroups, int blockedCells) {
        public ActivityStats(int groups,int sleeping,int sleepingCells,int activeCells){this(groups,sleeping,sleepingCells,activeCells,0,0);}
    }
	public record GasEdit(int node, mmm.fluid.air.GasInventory moved, SignedDyadic internalEnergy) { }
	record CellCandidate(int index, int distanceSquared) { }
	public record VelocitySample(float x, float y, float z) { static final VelocitySample ZERO = new VelocitySample(0.0F, 0.0F, 0.0F); }
	/** Local axis-aligned face. A=(axis+1)%3, B=(axis+2)%3; side points toward the contacted water. */
	/** One contacted water stock; water volume occurs once even when several faces touch it. */
	public record WaterContactFace(int axis, double coordinateMetres,
			double minimumAMetres, double maximumAMetres, double minimumBMetres, double maximumBMetres, int side) {
		public WaterContactFace {
			if (axis < 0 || axis > 2 || side != -1 && side != 1 || !Double.isFinite(coordinateMetres)
					|| !Double.isFinite(minimumAMetres) || !Double.isFinite(maximumAMetres)
					|| !Double.isFinite(minimumBMetres) || !Double.isFinite(maximumBMetres)
					|| !(maximumAMetres > minimumAMetres) || !(maximumBMetres > minimumBMetres))
				throw new IllegalArgumentException("Water contact face is invalid");
		}
	}
	/** One contacted water stock; water volume occurs once even when several faces touch it. */
	public record WaterContact(int cell, int supportMask, int materialHandle,
			double areaSquareMetres, double waterVolumeCubicMetres) { }
	public record ContactWaterTransfer(boolean transferred, long actualNanos, List<MaterialPortion> portions,
			ChemicalTransferLedger ledger, double internalEnergy) {
		static final ContactWaterTransfer EMPTY = new ContactWaterTransfer(false, 0, List.of(), ChemicalTransferLedger.EMPTY, 0);
		public ContactWaterTransfer { portions = List.copyOf(portions); }
	}
	public record BodyFluidSample(
			double displacedVolumeCubicMetres,
			double centerXMetres, double centerYMetres, double centerZMetres,
			double velocityXMetresPerSecond, double velocityYMetresPerSecond, double velocityZMetresPerSecond,
            double wetSurfaceAreaSquareMetres, double displacedMassKilograms,
            double pressureForceXNewtons, double pressureForceYNewtons, double pressureForceZNewtons,
            double waterContactVolumeCubicMetres
	) {
		static final BodyFluidSample EMPTY = new BodyFluidSample(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0);

		public BodyFluidSample {
			if (!(Double.isFinite(waterContactVolumeCubicMetres) && waterContactVolumeCubicMetres >= 0
                    && Double.isFinite(wetSurfaceAreaSquareMetres) && wetSurfaceAreaSquareMetres >= 0
					&& Double.isFinite(displacedMassKilograms) && displacedMassKilograms >= 0
					&& Double.isFinite(pressureForceXNewtons) && Double.isFinite(pressureForceYNewtons)
					&& Double.isFinite(pressureForceZNewtons)))
				throw new IllegalArgumentException("Body wet surface and pressure force must be finite");
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
			double porousBoundaryWorkJoules,
			double gravityWorkJoules,
			double thermalBuoyancyWorkJoules,
			double surfaceTensionWorkJoules,
			double viscousWorkJoules,
			double fixedBoundaryWorkJoules,
			double pressureProjectionWorkJoules,
			double contactWorkJoules,
			double volumeTransportTransferJoules,
			double carrierInventoryTransferJoules,
			SignedDyadic thermalInventoryMechanicalWork,
			double closureResidualJoules
	) {
		static MechanicalStepAudit notCaptured() {
			return new MechanicalStepAudit(false,0,0,0,0,
					0,0,0,0,0,0,0,0,0,0,SignedDyadic.ZERO,0);
		}
	}
	record MaterialInventory(double volume, double mass, double centerY) { }
	public record CarrierViscosityStats(int extrapolatedCells, String sample) {
		public CarrierViscosityStats {
			if (extrapolatedCells < 0) throw new IllegalArgumentException("Extrapolated cell count is negative");
			if (extrapolatedCells == 0) sample = "";
			else java.util.Objects.requireNonNull(sample, "sample");
		}

		public boolean extrapolated() { return extrapolatedCells > 0; }
	}
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
	/** Mechanical heat exchange and its sources. Optional PLIC observation separates cell-centre
	 * representation exchange from resolved pressure/force exchange; absolute total variation is diagnostic. */
	public record MechanicalCorrection(double liquidForceJoules,double granularForceJoules,double transportPotentialJoules,
	                                  double gasCounterWorkJoules,double signedJoules,double absoluteJoules,
	                                  boolean spatialObservationCaptured,double representationJoules,double resolvedJoules,
	                                  double resolvedPotentialChangeJoules,double initialRepresentationBoundJoules,
	                                  double finalRepresentationBoundJoules) { }
	public record StepStats(
			int substeps,
			int pressureIterations,
			float pressureResidual,
			float volumeBefore,
			float volumeAfter,
			double massBefore,
			double massAfter,
			int chemistryEvaluations,
			int chemistryChangedStocks,
			FluidDomainContracts.ThermalPhaseStats condensedPhase,
			FluidDomainContracts.GasThermalPhaseStats gasPhase,
			MechanicalStepAudit mechanicalAudit,
			FluidDomainContracts.ThermalAxisStats thermalAxis,
			GranularDepositDynamics.Stats granularTransition,
			CarrierViscosityStats carrierViscosity,
			ViscousStepStats viscosity,
			MechanicalCorrection mechanicalCorrection
	) {
		public StepStats {
			java.util.Objects.requireNonNull(thermalAxis, "thermalAxis");
			java.util.Objects.requireNonNull(granularTransition, "granularTransition");
			java.util.Objects.requireNonNull(carrierViscosity, "carrierViscosity");
			java.util.Objects.requireNonNull(viscosity, "viscosity");
		}
		public float relativeVolumeError() { return volumeBefore == 0.0F ? 0.0F : Math.abs(volumeAfter - volumeBefore) / volumeBefore; }
		public double relativeMassError() { return massBefore == 0.0 ? 0.0 : Math.abs(massAfter - massBefore) / massBefore; }
	}
	public static final class PressureSolveBlockedException extends IllegalStateException {
		PressureSolveBlockedException(String message) { super(message); }
	}
	public record ThermalAxisStats(
			ThermalTransport.Stats transport,
			RegionChemistry.ReactionStats chemistry,
			PhaseTransferStats granularPhase,
			FluidDomainContracts.ThermalPhaseStats condensedPhase,
			FluidDomainContracts.GasThermalPhaseStats gasPhase,
			GasLiquidMassTransferStats massTransfer
	) { }
	public record GasLiquidMassTransferStats(boolean active, boolean changedStocks) { }
	public record PhaseTransferStats(int transfers, int precipitations, int dissolutions, long signedSolidSvq) { }
	public record WaterClearPlan(
			int markerCount, long totalVolumeBits, long totalMassUnits, ChemicalTransferLedger drained,
			SignedDyadic internalEnergy, long mutationRevision, RegionChemistry.AuditExchange chemicalAudit, LiquidCellInventory.Plan inventory
	) { }
	public record CondensedClear(long coherentVolumeSvq, long coherentMassUnits, int coherentGroups) {
		public double coherentMass() { return mmm.units.Mass.kilograms(coherentMassUnits); }
	}
	public record CondensedClearPlan(
			WaterClearPlan liquid, List<CoherentPhaseStore.Group> coherentGroups,
			ChemicalTransferLedger drained, SignedDyadic coherentEnergy,
			long coherentVolumeSvq, long coherentMassUnits, long mutationRevision, RegionChemistry.AuditExchange chemicalAudit
	) {
		public CondensedClearPlan { coherentGroups = List.copyOf(coherentGroups); }
	}
	public record OperatorClear(CondensedClear condensed, FluidDomainContracts.GranularClear granular) { }
	public record OperatorGasVent(GasAuditLedger removed, SignedDyadic internalEnergy, int sealedNodes) { }
    public record OperatorGasVentPlan(int nodeCount, GasAuditLedger removed, SignedDyadic internalEnergy,
                                      HostMixtureEdit edit, ThermalDomainState.Revision thermalRevision,
                                      long momentumRevision) { }

	public static final class OperatorClearPlan {
		final CondensedClearPlan condensed;
		final FluidDomainContracts.GranularClearPlan granular;
		final HostMixtureEdit mixture;
		final ThermalDomainState.Revision thermalRevision;

		OperatorClearPlan(
				CondensedClearPlan condensed, FluidDomainContracts.GranularClearPlan granular,
				HostMixtureEdit mixture, ThermalDomainState.Revision thermalRevision
		) {
			this.condensed = condensed;
			this.granular = granular;
			this.mixture = mixture;
			this.thermalRevision = thermalRevision;
		}

		public CondensedClearPlan condensed() { return condensed; }
		public FluidDomainContracts.GranularClearPlan granular() { return granular; }
	}
	public record CollisionEdit(int x, int y, int z, int mask) { }
	public static final class CollisionEditPlan {
		final HostFluidState owner;
		final List<CollisionEdit> edits;
		final int[] previousMasks;
		final int planMarkerCount;
		final long totalVolumeBits;
		final long totalMassUnits;
		final GranularCollisionPlan granular;
		final LiquidCollisionEdit liquid;
		final ThermalDomainState.Revision thermalRevision;
		final long momentumRevision;
		boolean consumed;

		CollisionEditPlan(
				HostFluidState owner, List<CollisionEdit> edits, int[] previousMasks,
				int planMarkerCount, long totalVolumeBits, long totalMassUnits, GranularCollisionPlan granular, LiquidCollisionEdit liquid,
				ThermalDomainState.Revision thermalRevision
		) {
			this.owner = owner;
			this.momentumRevision = owner.mac.momentumRevision();
			this.edits = edits;
			this.previousMasks = previousMasks.clone();
			this.planMarkerCount = planMarkerCount;
			this.totalVolumeBits = totalVolumeBits;
			this.totalMassUnits = totalMassUnits;
			this.granular = granular; this.liquid = liquid; this.thermalRevision = thermalRevision;
		}
	}
	record CollisionDeposit(int cell, GranularDepositStore.Group group) { }
	record GranularCollisionPlan(
			boolean[] selectedParcels, List<CollisionDeposit> depositGroups,
			GranularAuditLedger sinkAfter, boolean[] affectedCells,
			int parcelCount, long totalSvq, long totalMassUnits,
			SignedDyadic removedEnergy, ThermalDomainState.Revision thermalRevision
	) { }
	public static final class CollisionEditBlockedException extends IllegalStateException {
		public CollisionEditBlockedException(String message, Throwable cause) { super(message, cause); }
	}
	static final class RemovedMaterialLedger {
		final double[] volume;
		final long[] mass;
		RemovedMaterialLedger(int materialCount) {
			volume = new double[materialCount]; mass = new long[materialCount];
		}
		void add(int material, double addedVolume, long addedMass) {
			volume[material] += addedVolume; mass[material] = Math.addExact(mass[material], addedMass);
		}
		List<MaterialPortion> build() {
			ArrayList<MaterialPortion> result = new ArrayList<>();
			for (int material = 0; material < volume.length; material++) if (volume[material] > 0.0) {
				if (mass[material] < 0L) {
					throw new IllegalStateException("Displaced material has negative mass");
				}
				result.add(new MaterialPortion(material, volume[material], mass[material]));
			}
			return List.copyOf(result);
		}
	}
	static final class RemovedThermalEnergy {
		SignedDyadic value = SignedDyadic.ZERO;
		void add(double energy) { value = value.add(SignedDyadic.of(energy)); }
		SignedDyadic value() { return value; }
	}
	public record ExactVolumeTransfer(boolean transferred, double volume, long massUnits, double internalEnergy) {
		public ExactVolumeTransfer(boolean transferred, double volume, long massUnits) {
			this(transferred, volume, massUnits, 0.0);
		}
	}
	public record ExactMixtureTransfer(boolean transferred, List<MaterialPortion> portions, double internalEnergy) {
		public ExactMixtureTransfer(boolean transferred, List<MaterialPortion> portions) {
			this(transferred, portions, 0.0);
		}
	}
	public record ExactChemicalTransfer(boolean transferred, ChemicalTransferLedger ledger, double internalEnergy) {
		public ExactChemicalTransfer(boolean transferred, ChemicalTransferLedger ledger) {
			this(transferred, ledger, 0.0);
		}
	}
	public record ChemicalDisplacement(float volume, ChemicalTransferLedger ledger, double internalEnergy) {
		public ChemicalDisplacement(float volume, ChemicalTransferLedger ledger) { this(volume, ledger, 0.0); }
	}
	public record MaterialDisplacement(float volume, List<MaterialPortion> portions, double internalEnergy) { }
	public record MaterialPortion(int materialHandle, double volume, long massUnits) { }
	/** A full cell face; direction is -X,+X,-Y,+Y,-Z,+Z. Contacts merge by linear cell index. */
	public record ThermalPortRequest(int cellX, int cellY, int cellZ, int direction,
			double wallThicknessMetres, double wallConductivityWPerMetreK, double filmThicknessMetres) {
		public ThermalPortRequest {
			if (direction < 0 || direction > 5 || !Double.isFinite(wallThicknessMetres) || wallThicknessMetres < 0
					|| !Double.isFinite(wallConductivityWPerMetreK) || wallConductivityWPerMetreK <= 0
					|| !Double.isFinite(filmThicknessMetres) || filmThicknessMetres <= 0)
				throw new IllegalArgumentException("Invalid finite device port geometry or resistance");
		}
	}
	public record ThermalContactRequest(
			int ordinal, int cellX, int cellY, int cellZ, double conductanceWPerK
	) {
		public ThermalContactRequest {
			if (ordinal < 0 || !(Double.isFinite(conductanceWPerK) && conductanceWPerK > 0.0)) {
				throw new IllegalArgumentException("Finite thermal contact request is invalid");
			}
		}
	}
	public record ThermalCellEnergyDelta(int cellX, int cellY, int cellZ, double internalEnergyDeltaJ,
	        List<FiniteThermalExchange.CarrierDelta> carriers) {
		public ThermalCellEnergyDelta(int x,int y,int z,double heat){this(x,y,z,heat,List.of());}
		public ThermalCellEnergyDelta {
			if (!Double.isFinite(internalEnergyDeltaJ)) {
				throw new IllegalArgumentException("Finite thermal cell energy delta is invalid");
			}
		}
	}
	public static final class FiniteThermalCellPlan {
		final FiniteDeviceExchangeCoordinator.FiniteDeviceCellPlan delegate;
		FiniteThermalCellPlan(FiniteDeviceExchangeCoordinator.FiniteDeviceCellPlan delegate) {
			this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
		}
		public SignedDyadic requestedDelta() { return delegate.requestedDelta(); }
	}
	public record ThermalPhaseStats(int operations, int frozenCells, int meltedCells, long signedBatches) { }
	public record GasThermalPhaseStats(int operations, int boiledCells, int condensedCells) { }
	public static final class ThermalPhaseBlockedException extends IllegalStateException {
		public ThermalPhaseBlockedException(String message) { super(message); }
		ThermalPhaseBlockedException(String message, Throwable cause) { super(message, cause); }
	}
	static final class PhaseCandidateUnavailableException extends IllegalArgumentException {
		PhaseCandidateUnavailableException(String message) { super(message); }
		PhaseCandidateUnavailableException(String message, Throwable cause) { super(message, cause); }
	}
	public static final class ThermalTransportBlockedException extends IllegalStateException {
		ThermalTransportBlockedException(String message, Throwable cause) { super(message, cause); }
	}
	public static final class GasThermalBlockedException extends IllegalStateException {
		GasThermalBlockedException(String message, Throwable cause) { super(message, cause); }
	}
	public record GranularClear(long solidVolumeSvq, long massUnits, int activeParcels, int depositGroups) {
		public double mass() { return mmm.units.Mass.kilograms(massUnits); }
	}
	public record GranularClearPlan(
			GranularAuditLedger removed, GranularAuditLedger sinkAfter,
			long solidVolumeSvq, long totalMassUnits, int activeParcels, int depositGroups,
			SignedDyadic internalEnergy, long granularRevision
	) { }
	public record GranularInsertion(boolean inserted, long solidVolumeSvq, long massUnits, String failure) {
		static GranularInsertion rejected(String reason) { return new GranularInsertion(false, 0L, 0L, reason); }
	}
	public record GranularExtraction(
			boolean transferred, GranularTransferLedger ledger, double internalEnergy, String failure
	) {
		static GranularExtraction rejected(String reason) { return new GranularExtraction(false, null, 0.0, reason); }
	}
}
