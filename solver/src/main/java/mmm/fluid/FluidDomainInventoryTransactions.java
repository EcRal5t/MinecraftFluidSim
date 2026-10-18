package mmm.fluid;

import mmm.thermal.GasEnergyStore;
import mmm.thermal.ThermalDomainState;

import mmm.chemistry.ChemicalTransferLedger;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasAuditLedger;
import mmm.granular.GranularAuditLedger;
import mmm.granular.GranularDepositStore;
import mmm.units.SignedDyadic;
import mmm.thermal.CoherentPhaseStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Plans and commits exact liquid, material, gas and whole-domain inventory edits. */
abstract class FluidDomainInventoryTransactions extends FluidDomainStepCoordinator {
	FluidDomainInventoryTransactions(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount) {
		super(sizeX, sizeY, sizeZ, config, maximumParticleCount);
	}

	/** Conservative AABB overlap with the current cut-micro-cell, horizontal-slab fluid observation. */
	public BodyFluidSample sampleAxisAlignedBody(
			double minimumX, double minimumY, double minimumZ,
			double maximumX, double maximumY, double maximumZ
	) {
		if (!(Double.isFinite(minimumX) && Double.isFinite(minimumY) && Double.isFinite(minimumZ)
				&& Double.isFinite(maximumX) && Double.isFinite(maximumY) && Double.isFinite(maximumZ)
				&& maximumX > minimumX && maximumY > minimumY && maximumZ > minimumZ)) {
			throw new IllegalArgumentException("Axis-aligned body bounds are invalid");
		}
		ensureGrid();
		double h = config.cellSize();
		int firstX = Math.max(0, (int) Math.floor(minimumX / h));
		int firstY = Math.max(0, (int) Math.floor(minimumY / h));
		int firstZ = Math.max(0, (int) Math.floor(minimumZ / h));
		int lastX = Math.min(sizeX - 1, (int) Math.ceil(maximumX / h) - 1);
		int lastY = Math.min(sizeY - 1, (int) Math.ceil(maximumY / h) - 1);
		int lastZ = Math.min(sizeZ - 1, (int) Math.ceil(maximumZ / h) - 1);
		if (lastX < firstX || lastY < firstY || lastZ < firstZ) return BodyFluidSample.EMPTY;
		double microSize = h / FlipCutCellGeometry.MICRO_CELLS_PER_AXIS;
		double displaced = 0.0, centerXSum = 0.0, centerYSum = 0.0, centerZSum = 0.0;
		double velocityXSum = 0.0, velocityYSum = 0.0, velocityZSum = 0.0;
		double[] layerFill = new double[FlipCutCellGeometry.MICRO_CELLS_PER_AXIS];
		for (int z = firstZ; z <= lastZ; z++) for (int y = firstY; y <= lastY; y++) {
			for (int x = firstX; x <= lastX; x++) {
				int cell = cellIndex(x, y, z);
				if (geometry.solid[cell] || volume[cell] <= config.fluidEpsilon()) continue;
				double geometricOpen = geometry.cutCells.openCellVolume(cell);
				double effectiveOpen = domain().effectiveOpenCellVolume(cell);
				if (!(geometricOpen > 0.0 && effectiveOpen > 0.0)) continue;
				double poreScale = effectiveOpen / geometricOpen;
				Arrays.fill(layerFill, 0.0);
				double remainingFluid = Math.min(volume[cell], effectiveOpen);
				for (int my = 0; my < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; my++) {
					int openInLayer = 0;
					for (int mz = 0; mz < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; mz++) {
						for (int mx = 0; mx < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; mx++) {
							if (geometry.cutCells.microCellOpen(cell, mx, my, mz)) openInLayer++;
						}
					}
					double layerCapacity = openInLayer * poreScale
							/ (FlipCutCellGeometry.MICRO_CELLS_PER_AXIS
							* FlipCutCellGeometry.MICRO_CELLS_PER_AXIS
							* FlipCutCellGeometry.MICRO_CELLS_PER_AXIS);
					if (!(layerCapacity > 0.0)) continue;
					layerFill[my] = Math.min(1.0, remainingFluid / layerCapacity);
					remainingFluid -= layerFill[my] * layerCapacity;
				}
				for (int mz = 0; mz < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; mz++) {
					for (int my = 0; my < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; my++) {
						if (!(layerFill[my] > 0.0)) continue;
						for (int mx = 0; mx < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; mx++) {
							if (!geometry.cutCells.microCellOpen(cell, mx, my, mz)) continue;
							double lowX = Math.max(minimumX, x * h + mx * microSize);
							double lowY = Math.max(minimumY, y * h + my * microSize);
							double lowZ = Math.max(minimumZ, z * h + mz * microSize);
							double highX = Math.min(maximumX, x * h + (mx + 1) * microSize);
							double highY = Math.min(maximumY,
									y * h + (my + layerFill[my]) * microSize);
							double highZ = Math.min(maximumZ, z * h + (mz + 1) * microSize);
							if (!(highX > lowX && highY > lowY && highZ > lowZ)) continue;
							double overlap = (highX - lowX) * (highY - lowY) * (highZ - lowZ) * poreScale;
							double centerSampleX = 0.5 * (lowX + highX);
							double centerSampleY = 0.5 * (lowY + highY);
							double centerSampleZ = 0.5 * (lowZ + highZ);
							displaced += overlap;
							centerXSum += overlap * centerSampleX;
							centerYSum += overlap * centerSampleY;
							centerZSum += overlap * centerSampleZ;
							VelocitySample velocity = sampleStoredVelocity((float) centerSampleX, (float) centerSampleY, (float) centerSampleZ);
							velocityXSum += overlap * velocity.x();
							velocityYSum += overlap * velocity.y();
							velocityZSum += overlap * velocity.z();
						}
					}
				}
			}
		}
		if (!(displaced > 0.0)) return BodyFluidSample.EMPTY;
		double inverse = 1.0 / displaced;
		return new BodyFluidSample(displaced,
				centerXSum * inverse, centerYSum * inverse, centerZSum * inverse,
				velocityXSum * inverse, velocityYSum * inverse, velocityZSum * inverse);
	}

	public VelocitySample sampleVelocity(float localX, float localY, float localZ) {
		ensureGrid();
		if (localX < 0.0F || localX > sizeX * config.cellSize()
				|| localY < 0.0F || localY > sizeY * config.cellSize()
				|| localZ < 0.0F || localZ > sizeZ * config.cellSize()) return VelocitySample.ZERO;
		return sampleStoredVelocity(localX, localY, localZ);
	}

	private VelocitySample sampleStoredVelocity(float localX, float localY, float localZ) {
		return new VelocitySample(
				sampleX(mac.velocityX, localX, localY, localZ),
				sampleY(mac.velocityY, localX, localY, localZ),
				sampleZ(mac.velocityZ, localX, localY, localZ)
		);
	}

	public float centerOfMassX() { return metrics.centerOfMassX(); }

	public float addVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		return addVolumeNearBudgeted(centerX, centerY, centerZ, amount, maxRadius).addedVolume();
	}

	/** Operator pour: inserts as much as fits, with every capacity and audit rejection in planning. */
	public VolumeAddition addVolumeNearBudgeted(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		return transferPlanner.insertVolumeAtMost(centerX, centerY, centerZ, amount, maxRadius);
	}

	/** Removes every marker; the exact marker-volume ledger becomes zero immediately. */
	public float removeVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		return transferPlanner.removeVolumeNear(centerX, centerY, centerZ, amount, maxRadius);
	}

	public void removeAllWaterExact() {
		commitRemoveAllWaterExact(planRemoveAllWaterExact());
	}

	/** Completes every fallible liquid-clear audit check without changing marker truth. */
	public WaterClearPlan planRemoveAllWaterExact() {
		return clearPlanner.planWater();
	}

	/** Commits a previously validated liquid clear; no new ledger candidate is constructed here. */
	public void commitRemoveAllWaterExact(WaterClearPlan plan) {
		if (plan == null || liquidCells.size() != plan.markerCount()
				|| carrierMutationRevision() != plan.mutationRevision()
				|| (chemicalParcels == null ? 0 : chemicalParcels.mutationRevision()) != plan.chemicalRevision()
				|| Double.doubleToRawLongBits(domain().totalVolumeExact()) != plan.totalVolumeBits()
				|| domain().totalMassUnits() != plan.totalMassUnits()
				|| !liquidCells.exactInternalEnergy().equals(plan.internalEnergy()))
			throw new IllegalStateException("Liquid inventory changed after clear planning");
		transferPlanner.clearLiquid();
	}

	/** Plans one operator clear across liquid markers and every coherent condensed phase. */
	public CondensedClearPlan planRemoveAllCondensedExact() {
		return clearPlanner.planCondensed();
	}

	/** Commits a jointly prevalidated liquid/coherent clear and records only the energy actually removed. */
	public CondensedClear commitRemoveAllCondensedExact(CondensedClearPlan plan) {
		preflightCondensedClear(plan);
		var gas = gasTopologyCoordinator.planAfterCondensedClear();
		if (gas != null) gasTopologyCoordinator.requireBeforeCarrierCommit(gas);
		if (thermalState != null) thermalState.requireCanCommitOperatorClear(thermalState.thermalRevision(),
				plan.liquid().internalEnergy(), plan.coherentEnergy(), SignedDyadic.ZERO,
				gas == null ? null : gas.thermalPlan());
		CondensedClear cleared = applyCondensedClear(plan);
		if (gas != null) gasTopologyCoordinator.commit(gas);
		return cleared;
	}

	/** Reruns every fallible condensed-clear check against live truth without mutating anything. */
	void preflightCondensedClear(CondensedClearPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Condensed clear plan is missing");
		if (carrierMutationRevision() != plan.mutationRevision()
				|| (chemicalParcels == null ? 0L : chemicalParcels.mutationRevision()) != plan.chemicalRevision()) {
			throw new IllegalStateException("Liquid inventory changed after condensed clear planning");
		}
		WaterClearPlan liquid = plan.liquid();
		if (liquidCells.size() != liquid.markerCount()
				|| Double.doubleToRawLongBits(domain().totalVolumeExact()) != liquid.totalVolumeBits()
				|| domain().totalMassUnits() != liquid.totalMassUnits()
				|| !(thermalState == null ? SignedDyadic.ZERO : liquidCells.exactInternalEnergy())
				.equals(liquid.internalEnergy())) {
			throw new IllegalStateException("Liquid inventory changed after condensed clear planning");
		}
		List<CoherentPhaseStore.Group> currentGroups = thermalState == null
				? List.of() : thermalState.coherentPhases().snapshot();
		if (!currentGroups.equals(plan.coherentGroups())
				|| (thermalState == null ? SignedDyadic.ZERO : thermalState.coherentPhases().exactEnergy())
				.compareTo(plan.coherentEnergy()) != 0
				|| (thermalState == null ? 0L : thermalState.coherentPhases().totalOccupiedSvq())
				!= plan.coherentVolumeSvq()
				|| (thermalState == null ? 0L : thermalState.coherentPhases().totalMassUnits()) != plan.coherentMassUnits()) {
			throw new IllegalStateException("Coherent phase inventory changed after condensed clear planning");
		}
	}

	/** Publishes an already preflighted condensed clear; the mutation phase has no rejections. */
	CondensedClear applyCondensedClear(CondensedClearPlan plan) {
		if (chemicalParcels != null) {
			chemicalParcels.recordEscapedSink(plan.drained());
		}
		if (thermalState != null) {
			thermalState.recordExternalSink(plan.liquid().internalEnergy());
			thermalState.clearCoherentExternal(plan.coherentGroups(), plan.coherentEnergy());
		}
		transferPlanner.planLiquidClear().commit();
		mac.restoreMomentum(new MacMomentumState(sizeX, sizeY, sizeZ, new double[mac.velocityX.length],
				new double[mac.velocityY.length], new double[mac.velocityZ.length]));
		domain().markCellInventoryChanged();
		domain().refreshCoherentOccupancy();
		gridDirty = true;
		return new CondensedClear(plan.coherentVolumeSvq(),
				plan.coherentMassUnits(), plan.coherentGroups().size());
	}

	/** Prevalidates every truth removed by the operator-level condensed inventory reset. */
	public OperatorClearPlan planOperatorClear() {
		return lifecyclePlanner.planOperatorClear();
	}

	/**
	 * Clears all condensed owners and publishes the already-planned retained-gas topology.
	 * Every sub-plan is preflighted against live truth before the first owner mutates, so a
	 * stale granular or gas plan can never leave the condensed side already cleared.
	 */
	public OperatorClear commitOperatorClear(OperatorClearPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Operator clear plan is missing");
		preflightCondensedClear(plan.condensed());
		domain().preflightGranularClear(plan.granular());
		if (thermalState == null ? plan.thermalRevision != null
				: !thermalState.thermalRevision().equals(plan.thermalRevision)) {
			throw new IllegalStateException("Thermal inventory changed after operator clear planning");
		}
		if (thermalState != null) thermalState.requireCanCommitOperatorClear(
				plan.thermalRevision, plan.condensed.liquid().internalEnergy(), plan.condensed.coherentEnergy(),
				plan.granular.internalEnergy(), plan.gasTopology == null ? null : plan.gasTopology.thermalPlan());
		if (plan.gasTopology != null) gasTopologyCoordinator.preflight(plan.gasTopology);
		CondensedClear condensed = applyCondensedClear(plan.condensed());
		FluidDomain.GranularClear granular = ((FluidDomain) this).applyGranularClear(plan.granular());
		if (plan.gasTopology != null) gasTopologyCoordinator.commit(plan.gasTopology);
		return new OperatorClear(condensed, granular);
	}

	/** Prevalidates an operator vent without treating ambient as inventory. */
	public OperatorGasVentPlan planOperatorVentSealedGas() {
		return gasLifecyclePlanner.planVent();
	}

	/** Retains sealed membership as explicit vacuum and books removed gas plus U to sinks. */
	public OperatorGasVent commitOperatorVentSealedGas(OperatorGasVentPlan plan) {
		return gasLifecyclePlanner.commitVent(plan);
	}

	public record WaterClearPlan(
			int markerCount, long totalVolumeBits, long totalMassUnits, ChemicalTransferLedger drained,
			SignedDyadic internalEnergy, long mutationRevision, long chemicalRevision
	) { }
	public record CondensedClear(long coherentVolumeSvq, long coherentMassUnits, int coherentGroups) {
		public double coherentMass() { return mmm.units.Mass.kilograms(coherentMassUnits); }
	}
	public record CondensedClearPlan(
			WaterClearPlan liquid, List<CoherentPhaseStore.Group> coherentGroups,
			ChemicalTransferLedger drained, SignedDyadic coherentEnergy,
			long coherentVolumeSvq, long coherentMassUnits, long mutationRevision, long chemicalRevision
	) {
		public CondensedClearPlan { coherentGroups = List.copyOf(coherentGroups); }
	}
	public record OperatorClear(CondensedClear condensed, FluidDomain.GranularClear granular) { }
	public record OperatorGasVent(GasAuditLedger removed, SignedDyadic internalEnergy, int sealedNodes) { }
	public record OperatorGasVentPlan(
			int nodeCount, GasAuditLedger removed, AirNetwork.RemapPlan gasPlan,
			GasEnergyStore.GasVentPlan thermalPlan, ThermalDomainState.Revision thermalRevision,
			GasRuntimeGeometry sourceGeometry
	) { }
	public static final class OperatorClearPlan {
		final CondensedClearPlan condensed;
		final FluidDomain.GranularClearPlan granular;
		final GasTopologyCoordinator.GasTopologyPlan gasTopology;
		final ThermalDomainState.Revision thermalRevision;

		OperatorClearPlan(
				CondensedClearPlan condensed, FluidDomain.GranularClearPlan granular,
				GasTopologyCoordinator.GasTopologyPlan gasTopology, ThermalDomainState.Revision thermalRevision
		) {
			this.condensed = condensed;
			this.granular = granular;
			this.gasTopology = gasTopology;
			this.thermalRevision = thermalRevision;
		}

		public CondensedClearPlan condensed() { return condensed; }
		public FluidDomain.GranularClearPlan granular() { return granular; }
	}
	public record CollisionEdit(int x, int y, int z, int mask) { }

	/** Opaque, single-use permit returned only after every collision owner accepts the batch. */
	public static final class CollisionEditPlan {
		final FluidDomain owner;
		final List<CollisionEdit> edits;
		final int[] previousMasks;
		final int planMarkerCount;
		final long totalVolumeBits;
		final long totalMassUnits;
		final GranularCollisionPlan granular;
		final LiquidCollisionEdit liquid;
		boolean consumed;

		CollisionEditPlan(
				FluidDomain owner, List<CollisionEdit> edits, int[] previousMasks,
				int planMarkerCount, long totalVolumeBits, long totalMassUnits, GranularCollisionPlan granular, LiquidCollisionEdit liquid
		) {
			this.owner = owner;
			this.edits = edits;
			this.previousMasks = previousMasks.clone();
			this.planMarkerCount = planMarkerCount;
			this.totalVolumeBits = totalVolumeBits;
			this.totalMassUnits = totalMassUnits;
			this.granular = granular; this.liquid = liquid;
		}
	}

	record CollisionDeposit(int cell, GranularDepositStore.Group group) { }
	record GranularCollisionPlan(
			boolean[] selectedParcels, List<CollisionDeposit> depositGroups,
			GranularAuditLedger sinkAfter, boolean[] affectedCells,
			int parcelCount, long totalSvq, long totalMassUnits
	) { }

	public static final class CollisionEditBlockedException extends IllegalStateException {
		public CollisionEditBlockedException(String message, Throwable cause) { super(message, cause); }
	}

	/**
	 * Container transfer primitive: all-or-nothing insertion of an exact volume near the
	 * target. Cells, pieces and the marker budget are planned before any state changes, so a
	 * refusal leaves the domain untouched. Pieces are {@code PARTICLE_VOLUME} chunks plus one
	 * float remainder; the returned volume is the exact double sum of the stored pieces.
	 */
	public ExactVolumeTransfer tryInsertExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius) {
		return tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius, 0);
	}

	public ExactVolumeTransfer tryInsertExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle) {
		return tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius, materialHandle, false);
	}

	public ExactVolumeTransfer tryInsertExactVolume(
			int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle,
			boolean externalSource
	) {
		return tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius, materialHandle,
				externalSource, null);
	}

	public ExactVolumeTransfer tryInsertExactVolume(
			int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle,
			boolean externalSource, double internalEnergy
	) {
		return tryInsertExactVolume(centerX, centerY, centerZ, volume, maxRadius, materialHandle,
				externalSource, Double.valueOf(internalEnergy));
	}

	ExactVolumeTransfer tryInsertExactVolume(
			int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle,
			boolean externalSource, Double internalEnergy
	) {
		validateVolumeEdit(volume, maxRadius);
		if (volume <= 0.0F) return new ExactVolumeTransfer(volume == 0.0F, 0.0, 0L);
		long massUnits = unitScale.massUnitsForNormalized((double) volume * (materialRegistry == null
				? config.density() : materialRegistry.material(materialHandle).referenceDensity()));
		if (massUnits == 0) return new ExactVolumeTransfer(false, 0, 0L);
		return tryInsertExactMaterial(centerX, centerY, centerZ, volume, massUnits, maxRadius, materialHandle,
				externalSource, internalEnergy);
	}

	/** Inserts independent material volume and mass ledgers without deriving either from the other. */
	public ExactVolumeTransfer tryInsertExactMaterial(
			int centerX, int centerY, int centerZ, float volume, long massUnits, int maxRadius, int materialHandle
	) {
		return tryInsertExactMaterial(centerX, centerY, centerZ, volume, massUnits, maxRadius, materialHandle, false);
	}

	ExactVolumeTransfer tryInsertExactMaterial(
			int centerX, int centerY, int centerZ, float volume, long massUnits, int maxRadius, int materialHandle,
			boolean externalSource
	) {
		return tryInsertExactMaterial(centerX, centerY, centerZ, volume, massUnits, maxRadius, materialHandle,
				externalSource, null);
	}

	ExactVolumeTransfer tryInsertExactMaterial(
			int centerX, int centerY, int centerZ, float volume, long massUnits, int maxRadius, int materialHandle,
			boolean externalSource, Double internalEnergy
	) {
		if (volume == 0.0F && massUnits == 0) return new ExactVolumeTransfer(true, 0.0, 0L);
		ExactMixtureTransfer transfer = tryInsertExactMixture(centerX, centerY, centerZ,
				List.of(new MaterialPortion(materialHandle, volume, massUnits)), maxRadius, externalSource, internalEnergy);
		return transfer.transferred()
				? new ExactVolumeTransfer(true, transfer.portions().getFirst().volume(),
						transfer.portions().getFirst().massUnits(), transfer.internalEnergy())
				: new ExactVolumeTransfer(false, 0.0, 0L);
	}

	/** Plans every mixture component against shared capacity before inserting any marker. */
	public ExactMixtureTransfer tryInsertExactMixture(
			int centerX, int centerY, int centerZ, List<MaterialPortion> portions, int maxRadius
	) {
		return tryInsertExactMixture(centerX, centerY, centerZ, portions, maxRadius, false);
	}

	public ExactMixtureTransfer tryInsertExactMixture(
			int centerX, int centerY, int centerZ, List<MaterialPortion> portions, int maxRadius,
			boolean externalSource
	) {
		return tryInsertExactMixture(centerX, centerY, centerZ, portions, maxRadius, externalSource, null);
	}

	public ExactMixtureTransfer tryInsertExactMixture(
			int centerX, int centerY, int centerZ, List<MaterialPortion> portions, int maxRadius,
			boolean externalSource, double internalEnergy
	) {
		return tryInsertExactMixture(centerX, centerY, centerZ, portions, maxRadius, externalSource,
				Double.valueOf(internalEnergy));
	}

	FluidDomain.ExactMixtureTransfer tryInsertExactMixture(
			int centerX, int centerY, int centerZ, List<MaterialPortion> portions, int maxRadius,
			boolean externalSource, Double internalEnergy
	) {
		return transferPlanner.insertMixture(centerX, centerY, centerZ, portions, maxRadius,
				externalSource, internalEnergy);
	}

	/** Plans and restores an exact chemical ledger without substituting a default carrier. */
	public ExactChemicalTransfer tryInsertExactChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius
	) {
		return tryInsertExactChemical(centerX, centerY, centerZ, ledger, maxRadius, false);
	}

	public ExactChemicalTransfer tryInsertExactChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius,
			boolean externalSource
	) {
		return tryInsertExactChemical(centerX, centerY, centerZ, ledger, maxRadius, externalSource, null);
	}

	public ExactChemicalTransfer tryInsertExactChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius,
			boolean externalSource, double internalEnergy
	) {
		return tryInsertExactChemical(centerX, centerY, centerZ, ledger, maxRadius, externalSource,
				Double.valueOf(internalEnergy));
	}

	ExactChemicalTransfer tryInsertExactChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius,
			boolean externalSource, Double internalEnergy
	) {
		return transferPlanner.insertChemical(centerX, centerY, centerZ, ledger, maxRadius,
				externalSource, internalEnergy);
	}

	/** All-or-nothing extraction of full species/residual/indicator truth for a chemical container. */
	public ExactChemicalTransfer tryExtractExactChemical(
			int centerX, int centerY, int centerZ, float volume, int maxRadius
	) {
		return tryExtractExactChemical(centerX, centerY, centerZ, volume, maxRadius, ignored -> true);
	}

	/** Applies the caller's bounded representation check to the complete ledger before committing removal. */
	public ExactChemicalTransfer tryExtractExactChemical(
			int centerX, int centerY, int centerZ, float volume, int maxRadius,
			java.util.function.Predicate<ChemicalTransferLedger> acceptsLedger
	) {
		return transferPlanner.extractChemical(centerX, centerY, centerZ, volume, maxRadius, acceptsLedger);
	}

	/**
	 * Container transfer primitive: all-or-nothing extraction of an exact volume near the
	 * target. Availability is verified in double precision first; removal takes whole markers and
	 * splits the last one. Settlement reports real marker decreases without exceeding the request;
	 * a positive request with no representable transfer is refused before mutation.
	 * The overload without a material handle extracts the default carrier (handle zero).
	 */
public ExactVolumeTransfer tryExtractExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius) {
return tryExtractExactVolume(centerX, centerY, centerZ, volume, maxRadius, 0);
}

public ExactVolumeTransfer tryExtractExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle) {
	validateVolumeEdit(volume, maxRadius);
	if (volume <= 0.0F) return new ExactVolumeTransfer(volume == 0.0F, 0.0, 0L);
	ExactMixtureTransfer transfer = transferPlanner.extractMixture(
			centerX, centerY, centerZ, volume, maxRadius, materialHandle);
	if (!transfer.transferred()) return new ExactVolumeTransfer(false, 0.0, 0L);
	MaterialPortion portion = transfer.portions().getFirst();
	return new ExactVolumeTransfer(true, portion.volume(), portion.massUnits(), transfer.internalEnergy());
}

	/** Extracts nearby materials in deterministic cell/marker order and returns every component. */
	public ExactMixtureTransfer tryExtractExactMixture(
			int centerX, int centerY, int centerZ, float volume, int maxRadius
	) {
		validateVolumeEdit(volume, maxRadius);
		if (volume <= 0.0F) return new ExactMixtureTransfer(volume == 0.0F, List.of());
		return transferPlanner.extractMixture(centerX, centerY, centerZ, volume, maxRadius, -1);
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

}
