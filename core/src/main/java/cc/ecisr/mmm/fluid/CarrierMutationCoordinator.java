package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;

/**
 * Sole executor of carrier structural mutations: marker append, main-column swap-remove and
 * wholesale count adoption. Every call goes through a revision-bound {@link CarrierMutationPlan}
 * and a fixed commit order (main column, chemistry column, thermal column, count, revision,
 * grid invalidation). All possibly-failing checks belong to the plan phase; once the first
 * column write happened the commit must not throw a foreseeable business rejection.
 */
final class CarrierMutationCoordinator {
	private final FlipFluidDomain domain;

	CarrierMutationCoordinator(FlipFluidDomain domain) {
		this.domain = domain;
	}

	/**
	 * Appends one marker as a new live ordinal and settles both side columns; returns the new
	 * ordinal. Registry and finiteness rejections must have been completed by the caller.
	 */
	int appendMarker(
			CarrierMutationPlan plan, int cell,
			float positionX, float positionY, float positionZ,
			float amount, int materialHandle, double mass,
			ChemicalMixtureRecord chemicalMixture, boolean indicator, boolean thermalExternal
	) {
		verify(plan, CarrierMutationPlan.Kind.APPEND);
		domain.ensureParticleCapacity(plan.targetOrdinal() + 1);
		int marker = plan.targetOrdinal();
		domain.particles.setPositionX(marker, positionX);
		domain.particles.setPositionY(marker, positionY);
		domain.particles.setPositionZ(marker, positionZ);
		domain.particles.setVelocityX(marker, 0.0F);
		domain.particles.setVelocityY(marker, 0.0F);
		domain.particles.setVelocityZ(marker, 0.0F);
		domain.particles.setVolume(marker, amount);
		domain.particles.setMass(marker, mass);
		domain.particles.setMaterialHandle(marker, materialHandle);
		domain.particles.setPhaseLabel(marker,
				domain.materialRegistry == null ? 0 : domain.materialRegistry.phaseHandle(materialHandle));
		if (domain.chemicalParcels != null) {
			if (chemicalMixture == null) {
				domain.chemicalParcels.assignDefault(marker,
						domain.materialRegistry.material(materialHandle).id(), amount,
						domain.config.cellSize(), mass, false);
			} else {
				domain.chemicalParcels.assign(marker, chemicalMixture, indicator);
			}
			domain.chemicalParcels.markDirtyCell(cell);
		}
		if (domain.thermalState != null) {
			if (thermalExternal) domain.thermalState.addExternalMarker(marker, domain.thermalMarkerContext());
			else domain.thermalState.addInternalMarker(marker, 0.0);
		}
		domain.particles.incrementCount();
		domain.bumpCarrierMutationRevision();
		domain.gridDirty = true;
		return marker;
	}

	/**
	 * Removes the marker at the plan ordinal by swapping the current last marker into it across
	 * the main column and both side columns; returns the removed marker's internal energy when
	 * {@code settleInternalEnergy} is set, otherwise 0.
	 */
	double swapRemoveMarker(CarrierMutationPlan plan, boolean settleInternalEnergy) {
		verify(plan, CarrierMutationPlan.Kind.SWAP_REMOVE);
		int index = plan.targetOrdinal();
		if (domain.chemicalParcels != null) domain.chemicalParcels.markDirtyCell(domain.particleCellIndex(index));
		int last = domain.particles.count() - 1;
		double removedEnergy = 0.0;
		if (settleInternalEnergy) removedEnergy = domain.thermalState.removeInternalAndSwap(index, last);
		else if (domain.thermalState != null) domain.thermalState.removeExternalAndSwap(index, last);
		if (domain.chemicalParcels != null) domain.chemicalParcels.swapRemove(index, last);
		domain.particles.swapRemove(index);
		domain.bumpCarrierMutationRevision();
		domain.gridDirty = true;
		return removedEnergy;
	}

	/** Adopts an externally computed marker count for wholesale transitions (restore, clear, reseed). */
	void adoptWholesaleCount(CarrierMutationPlan plan, int newCount) {
		verify(plan, CarrierMutationPlan.Kind.WHOLESALE_COUNT);
		if (newCount < 0 || newCount > domain.maximumParticleCount) {
			throw new IllegalArgumentException("Wholesale marker count is outside the domain budget");
		}
		domain.particles.setCount(newCount);
		domain.bumpCarrierMutationRevision();
		domain.gridDirty = true;
	}

	/** Clears the liquid marker column together with its chemistry column after a validated plan. */
	void clearLiquidMarkers(CarrierMutationPlan plan) {
		verify(plan, CarrierMutationPlan.Kind.WHOLESALE_COUNT);
		if (domain.chemicalParcels != null) domain.chemicalParcels.clear(domain.particles.count());
		domain.particles.setCount(0);
		domain.bumpCarrierMutationRevision();
		domain.gridDirty = true;
	}

	private void verify(CarrierMutationPlan plan, CarrierMutationPlan.Kind kind) {
		if (plan == null) throw new IllegalArgumentException("Carrier mutation plan is missing");
		if (plan.kind() != kind) throw new IllegalArgumentException("Carrier mutation plan kind mismatch");
		if (plan.baseRevision() != domain.carrierMutationRevision()) {
			throw new IllegalStateException("Carrier mutation plan is stale: carrier structure changed after planning");
		}
		if (plan.expectedCount() != domain.particles.count()) {
			throw new IllegalStateException("Carrier marker count changed after planning");
		}
		if (kind == CarrierMutationPlan.Kind.APPEND) {
			if (plan.targetOrdinal() != plan.expectedCount()) {
				throw new IndexOutOfBoundsException("Append ordinal must be the live end");
			}
		} else if (kind == CarrierMutationPlan.Kind.SWAP_REMOVE) {
			if (plan.targetOrdinal() < 0 || plan.targetOrdinal() >= plan.expectedCount()) {
				throw new IndexOutOfBoundsException("Swap-remove ordinal is outside the live range");
			}
		}
	}
}
