package mmm.fluid;

import java.util.List;
import mmm.units.SignedDyadic;
import mmm.chemistry.ChemicalTransferLedger;
import java.util.Arrays;
import mmm.thermal.CoherentPhaseStore;
import static mmm.fluid.FluidDomain.*;

/** Prepares inventory insertion, extraction and clear operations against one domain state. */
final class DomainInventory {
	private final FluidDomain domain;
	DomainInventory(FluidDomain domain) { this.domain = domain; }
	public BodyFluidSample sampleAxisAlignedBody(
			double minimumX, double minimumY, double minimumZ,
			double maximumX, double maximumY, double maximumZ
	) {
		if (!(Double.isFinite(minimumX) && Double.isFinite(minimumY) && Double.isFinite(minimumZ)
				&& Double.isFinite(maximumX) && Double.isFinite(maximumY) && Double.isFinite(maximumZ)
				&& maximumX > minimumX && maximumY > minimumY && maximumZ > minimumZ)) {
			throw new IllegalArgumentException("Axis-aligned body bounds are invalid");
		}
		domain.domainFlowFields.ensureVelocityGrid();
		double h = domain.config.cellSize();
		int firstX = Math.max(0, (int) Math.floor(minimumX / h));
		int firstY = Math.max(0, (int) Math.floor(minimumY / h));
		int firstZ = Math.max(0, (int) Math.floor(minimumZ / h));
		int lastX = Math.min(domain.sizeX - 1, (int) Math.ceil(maximumX / h) - 1);
		int lastY = Math.min(domain.sizeY - 1, (int) Math.ceil(maximumY / h) - 1);
		int lastZ = Math.min(domain.sizeZ - 1, (int) Math.ceil(maximumZ / h) - 1);
		if (lastX < firstX || lastY < firstY || lastZ < firstZ) return BodyFluidSample.EMPTY;
		double microSize = h / FlipCutCellGeometry.MICRO_CELLS_PER_AXIS;
		double displaced = 0.0, centerXSum = 0.0, centerYSum = 0.0, centerZSum = 0.0;
		double velocityXSum = 0.0, velocityYSum = 0.0, velocityZSum = 0.0;
		double[] layerFill = new double[FlipCutCellGeometry.MICRO_CELLS_PER_AXIS];
		for (int z = firstZ; z <= lastZ; z++) for (int y = firstY; y <= lastY; y++) {
			for (int x = firstX; x <= lastX; x++) {
				int cell = domain.domainFlowFields.cellIndex(x, y, z);
				if (domain.geometry.cutCells.fullySolid(cell) || domain.volume[cell] <= domain.config.fluidEpsilon()) continue;
				double geometricOpen = domain.geometry.cutCells.openCellVolume(cell);
				double effectiveOpen = domain.domainCollision.effectiveOpenCellVolume(cell);
				if (!(geometricOpen > 0.0 && effectiveOpen > 0.0)) continue;
				double poreScale = effectiveOpen / geometricOpen;
				Arrays.fill(layerFill, 0.0);
				double remainingFluid = Math.min(domain.volume[cell], effectiveOpen);
				for (int my = 0; my < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; my++) {
					int openInLayer = 0;
					for (int mz = 0; mz < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; mz++) {
						for (int mx = 0; mx < FlipCutCellGeometry.MICRO_CELLS_PER_AXIS; mx++) {
							if (domain.geometry.cutCells.microCellOpen(cell, mx, my, mz)) openInLayer++;
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
							if (!domain.geometry.cutCells.microCellOpen(cell, mx, my, mz)) continue;
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
		domain.domainFlowFields.ensureVelocityGrid();
		if (localX < 0.0F || localX > domain.sizeX * domain.config.cellSize()
				|| localY < 0.0F || localY > domain.sizeY * domain.config.cellSize()
				|| localZ < 0.0F || localZ > domain.sizeZ * domain.config.cellSize()) return VelocitySample.ZERO;
		return sampleStoredVelocity(localX, localY, localZ);
	}
	private VelocitySample sampleStoredVelocity(float localX, float localY, float localZ) {
		return new VelocitySample(
				domain.domainFlowFields.sampleX(domain.mac.velocityX, localX, localY, localZ),
				domain.domainFlowFields.sampleY(domain.mac.velocityY, localX, localY, localZ),
				domain.domainFlowFields.sampleZ(domain.mac.velocityZ, localX, localY, localZ)
		);
	}
	public float centerOfMassX() { return domain.metrics.centerOfMassX(); }
	public float addVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		return addVolumeNearBudgeted(centerX, centerY, centerZ, amount, maxRadius).addedVolume();
	}
	public VolumeAddition addVolumeNearBudgeted(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		return domain.transferPlanner.insertVolumeAtMost(centerX, centerY, centerZ, amount, maxRadius);
	}
	public float removeVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		return domain.transferPlanner.removeVolumeNear(centerX, centerY, centerZ, amount, maxRadius);
	}
	private void requireChemicalAudit(mmm.chemistry.RegionChemistry.AuditExchange audit){
		if(audit==null){if(domain.chemistry!=null)throw new IllegalStateException("Chemistry changed after clear planning");return;}
		if(domain.chemistry==null)throw new IllegalStateException("Chemistry changed after clear planning");
		domain.chemistry.requireCurrentAuditExchange(audit);
	}
	public void removeAllWaterExact() {
		commitRemoveAllWaterExact(planRemoveAllWaterExact());
	}
	public WaterClearPlan planRemoveAllWaterExact() {
		return domain.clearPlanner.planWater();
	}
	public void commitRemoveAllWaterExact(WaterClearPlan plan) {
		if (plan != null) { plan.inventory().requireCurrent();requireChemicalAudit(plan.chemicalAudit()); }
		if (plan == null || domain.liquidCells.size() != plan.markerCount()
				|| domain.domainFlowFields.carrierMutationRevision() != plan.mutationRevision()
				|| Double.doubleToRawLongBits(domain.totalVolumeExact()) != plan.totalVolumeBits()
				|| domain.totalMassUnits() != plan.totalMassUnits()
				|| !domain.liquidCells.exactInternalEnergy().equals(plan.internalEnergy()))
			throw new IllegalStateException("Liquid inventory changed after clear planning");
		domain.transferPlanner.clearLiquid(plan.inventory(), plan.drained(), plan.internalEnergy());
	}
	public CondensedClearPlan planRemoveAllCondensedExact() {
		return domain.clearPlanner.planCondensed();
	}
	public CondensedClear commitRemoveAllCondensedExact(CondensedClearPlan plan) {
		preflightCondensedClear(plan);
		var gas = domain.gasTopologyCoordinator.planAfterCondensedClear(plan.liquid().inventory().future());
		if (gas != null) domain.gasTopologyCoordinator.requireBeforeCarrierCommit(gas);
		if (domain.thermalState != null) domain.thermalState.requireCanCommitOperatorClear(domain.thermalState.thermalRevision(),
				plan.liquid().internalEnergy(), plan.coherentEnergy(), SignedDyadic.ZERO,
				gas == null ? null : gas.thermalPlan());
		CondensedClear cleared = applyCondensedClear(plan);
		if (gas != null) domain.gasTopologyCoordinator.commit(gas);
		return cleared;
	}
	void preflightCondensedClear(CondensedClearPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Condensed clear plan is missing");
		plan.liquid().inventory().requireCurrent();requireChemicalAudit(plan.chemicalAudit());
		if (domain.domainFlowFields.carrierMutationRevision() != plan.mutationRevision()) {
			throw new IllegalStateException("Liquid inventory changed after condensed clear planning");
		}
		WaterClearPlan liquid = plan.liquid();
		if (domain.liquidCells.size() != liquid.markerCount()
				|| Double.doubleToRawLongBits(domain.totalVolumeExact()) != liquid.totalVolumeBits()
				|| domain.totalMassUnits() != liquid.totalMassUnits()
				|| !(domain.thermalState == null ? SignedDyadic.ZERO : domain.liquidCells.exactInternalEnergy())
				.equals(liquid.internalEnergy())) {
			throw new IllegalStateException("Liquid inventory changed after condensed clear planning");
		}
		List<CoherentPhaseStore.Group> currentGroups = domain.thermalState == null
				? List.of() : domain.thermalState.coherentPhases().snapshot();
		if (!currentGroups.equals(plan.coherentGroups())
				|| (domain.thermalState == null ? SignedDyadic.ZERO : domain.thermalState.coherentPhases().exactEnergy())
				.compareTo(plan.coherentEnergy()) != 0
				|| (domain.thermalState == null ? 0L : domain.thermalState.coherentPhases().totalOccupiedSvq())
				!= plan.coherentVolumeSvq()
				|| (domain.thermalState == null ? 0L : domain.thermalState.coherentPhases().totalMassUnits()) != plan.coherentMassUnits()) {
			throw new IllegalStateException("Coherent phase inventory changed after condensed clear planning");
		}
	}
	CondensedClear applyCondensedClear(CondensedClearPlan plan) {
		if (domain.chemistry != null) {
			domain.chemistry.commitAuditExchange(plan.chemicalAudit());
		}
		if (domain.thermalState != null) {
			domain.thermalState.recordExternalSink(plan.liquid().internalEnergy());
			domain.thermalState.clearCoherentExternal(plan.coherentGroups(), plan.coherentEnergy());
		}
		plan.liquid().inventory().commit();
		domain.mac.restoreMomentum(new MacMomentumState(domain.sizeX, domain.sizeY, domain.sizeZ, new double[domain.mac.velocityX.length],
				new double[domain.mac.velocityY.length], new double[domain.mac.velocityZ.length]));
		domain.markCellInventoryChanged();
		domain.domainCapabilities.refreshCoherentOccupancy();
		domain.gridDirty = true;
		return new CondensedClear(plan.coherentVolumeSvq(),
				plan.coherentMassUnits(), plan.coherentGroups().size());
	}
	public OperatorClearPlan planOperatorClear() {
		return domain.lifecyclePlanner.planOperatorClear();
	}
	public OperatorClear commitOperatorClear(OperatorClearPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Operator clear plan is missing");
		preflightCondensedClear(plan.condensed());
		domain.domainPhaseInventory.preflightGranularClear(plan.granular());
		if (domain.thermalState == null ? plan.thermalRevision != null
				: !domain.thermalState.thermalRevision().equals(plan.thermalRevision)) {
			throw new IllegalStateException("Thermal inventory changed after operator clear planning");
		}
		if (domain.thermalState != null) domain.thermalState.requireCanCommitOperatorClear(
				plan.thermalRevision, plan.condensed.liquid().internalEnergy(), plan.condensed.coherentEnergy(),
				plan.granular.internalEnergy(), plan.gasTopology == null ? null : plan.gasTopology.thermalPlan());
		if (plan.gasTopology != null) domain.gasTopologyCoordinator.preflight(plan.gasTopology);
		CondensedClear condensed = applyCondensedClear(plan.condensed());
		FluidDomain.GranularClear granular = ((FluidDomain) domain).domainPhaseInventory.applyGranularClear(plan.granular());
		if (plan.gasTopology != null) domain.gasTopologyCoordinator.commit(plan.gasTopology);
		return new OperatorClear(condensed, granular);
	}
	public OperatorGasVentPlan planOperatorVentSealedGas() {
		return domain.gasLifecyclePlanner.planVent();
	}
	public OperatorGasVent commitOperatorVentSealedGas(OperatorGasVentPlan plan) {
		return domain.gasLifecyclePlanner.commitVent(plan);
	}
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
		domain.domainFlowFields.validateVolumeEdit(volume, maxRadius);
		if (volume <= 0.0F) return new ExactVolumeTransfer(volume == 0.0F, 0.0, 0L);
		long massUnits = domain.unitScale.massUnitsForNormalized((double) volume * (domain.materialRegistry == null
				? domain.config.density() : domain.materialRegistry.material(materialHandle).referenceDensity()));
		if (massUnits == 0) return new ExactVolumeTransfer(false, 0, 0L);
		return tryInsertExactMaterial(centerX, centerY, centerZ, volume, massUnits, maxRadius, materialHandle,
				externalSource, internalEnergy);
	}
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
		return domain.transferPlanner.insertMixture(centerX, centerY, centerZ, portions, maxRadius,
				externalSource, internalEnergy);
	}
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
		return domain.transferPlanner.insertChemical(centerX, centerY, centerZ, ledger, maxRadius,
				externalSource, internalEnergy);
	}
	public ExactChemicalTransfer tryExtractExactChemical(
			int centerX, int centerY, int centerZ, float volume, int maxRadius
	) {
		return tryExtractExactChemical(centerX, centerY, centerZ, volume, maxRadius, ignored -> true);
	}
	public ExactChemicalTransfer tryExtractExactChemical(
			int centerX, int centerY, int centerZ, float volume, int maxRadius,
			java.util.function.Predicate<ChemicalTransferLedger> acceptsLedger
	) {
		return domain.transferPlanner.extractChemical(centerX, centerY, centerZ, volume, maxRadius, acceptsLedger);
	}
	public ExactVolumeTransfer tryExtractExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius) {
	return tryExtractExactVolume(centerX, centerY, centerZ, volume, maxRadius, 0);
	}
	public ExactVolumeTransfer tryExtractExactVolume(int centerX, int centerY, int centerZ, float volume, int maxRadius, int materialHandle) {
	domain.domainFlowFields.validateVolumeEdit(volume, maxRadius);
	if (volume <= 0.0F) return new ExactVolumeTransfer(volume == 0.0F, 0.0, 0L);
	ExactMixtureTransfer transfer = domain.transferPlanner.extractMixture(
			centerX, centerY, centerZ, volume, maxRadius, materialHandle);
	if (!transfer.transferred()) return new ExactVolumeTransfer(false, 0.0, 0L);
	MaterialPortion portion = transfer.portions().getFirst();
	return new ExactVolumeTransfer(true, portion.volume(), portion.massUnits(), transfer.internalEnergy());
	}
	public ExactMixtureTransfer tryExtractExactMixture(
			int centerX, int centerY, int centerZ, float volume, int maxRadius
	) {
		domain.domainFlowFields.validateVolumeEdit(volume, maxRadius);
		if (volume <= 0.0F) return new ExactMixtureTransfer(volume == 0.0F, List.of());
		return domain.transferPlanner.extractMixture(centerX, centerY, centerZ, volume, maxRadius, -1);
	}
}
