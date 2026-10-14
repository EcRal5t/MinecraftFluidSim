package mmm.fluid;

import mmm.chemistry.ChemicalMixtureRecord;

/**
 * Sole executor of carrier structural mutations: marker append, main-column swap-remove and
 * wholesale count adoption. Every call goes through a revision-bound {@link CarrierMutationPlan}
 * and a fixed commit order (main column, chemistry column, thermal column, count, revision,
 * grid invalidation). All possibly-failing checks belong to the plan phase; once the first
 * column write happened the commit must not throw a foreseeable business rejection.
 */
final class CarrierMutationCoordinator {
	private final FluidDomain domain;

	CarrierMutationCoordinator(FluidDomain domain) {
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

	/** Publishes a cell-local replacement whose pieces and complete audits were preflighted together. */
	void replaceCellVolume(CellVolumeReplacementPlan candidate) {
		verify(candidate.mutation, CarrierMutationPlan.Kind.WHOLESALE_COUNT);
		candidate.requireCurrent(domain);
		for (int ordinal : candidate.removed) swapRemoveMarker(
				CarrierMutationPlan.swapRemove(domain.carrierMutationRevision(), domain.particles.count(), ordinal),
				domain.thermalState != null);
		int firstOrdinal = domain.particles.count();
		for (var piece : candidate.pieces) appendMarker(
				CarrierMutationPlan.append(domain.carrierMutationRevision(), domain.particles.count()), candidate.cell,
				piece.x(), piece.y(), piece.z(), piece.volume(), 0, piece.mass(), piece.mixture(), false, false);
		if (domain.chemicalParcels != null) {
			domain.chemicalParcels.commitAuditExchange(candidate.chemistryAudit);
		}
		if (candidate.thermal != null) domain.thermalState.commitLiquidReplacement(firstOrdinal, candidate.thermal);
		if (candidate.gasTopology != null) domain.gasTopologyCoordinator.commit(candidate.gasTopology);
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

	/** Publishes the preflighted cell quadrature and aligned side columns. */
	void publishCellRemap(CarrierMutationPlan plan, CellRemapCandidate candidate) {
		verify(plan, CarrierMutationPlan.Kind.WHOLESALE_COUNT);
		var markers = candidate.markers(); var gasTopology = candidate.gasTopology();
		int count = markers.size();
		if (count > domain.maximumParticleCount) throw new IllegalArgumentException("Cell remap exceeds marker budget");
		domain.ensureParticleCapacity(count);
		mmm.chemistry.ChemicalParcelStore.MarkerRemap chemistry = null;
		if (domain.chemicalParcels != null) {
			ChemicalMixtureRecord[] records = new ChemicalMixtureRecord[count]; boolean[] indicators = new boolean[count];
			for (int ordinal = 0; ordinal < count; ordinal++) {
				records[ordinal] = markers.mixture[ordinal]; indicators[ordinal] = markers.indicator[ordinal];
			}
			chemistry = domain.chemicalParcels.planMarkerRemap(domain.particles.count(), records, indicators);
		}
		double[] energies = domain.thermalState == null ? null : markers.energy;
		if (gasTopology != null) domain.gasTopologyCoordinator.requireBeforeCarrierCommit(gasTopology);
		if (energies != null) {
			var after = domain.thermalState.auditLedger().withRoundoff(candidate.energyRoundoff());
			var gas = gasTopology == null ? null : gasTopology.thermalPlan();
			if (gas != null) after = after.withExternalSourceEnergy(gas.sourceEnergy()).withExternalSinkEnergy(gas.sinkEnergy())
					.withMechanicalWork(gas.mechanicalWork()).withRoundoff(gas.roundoff());
			after.expectedInventory();
		}
		if (candidate.commitGranularCollision()) {
			domain.granularMechanicalStage.requireCollisionPreview();
			domain.granularMechanicalStage.commitCollision();
		}
		float h = domain.config.cellSize();
		mmm.compute.CpuWorkBatch.run(count, (from, until) -> {
			for (int ordinal = from; ordinal < until; ordinal++) {
				int x = markers.cell[ordinal] % domain.sizeX, y = markers.cell[ordinal] / domain.sizeX % domain.sizeY, z = markers.cell[ordinal] / (domain.sizeX * domain.sizeY);
				domain.particles.setPositionX(ordinal, (float) ((x + 0.5 + markers.offset[0][ordinal]) * h));
				domain.particles.setPositionY(ordinal, (float) ((y + 0.5 + markers.offset[1][ordinal]) * h));
				domain.particles.setPositionZ(ordinal, (float) ((z + 0.5 + markers.offset[2][ordinal]) * h));
				domain.particles.setVolume(ordinal, markers.volume[ordinal]); domain.particles.setMass(ordinal, markers.mass[ordinal]);
				domain.particles.setVelocityX(ordinal, (float) markers.velocity[0][ordinal]);
				domain.particles.setVelocityY(ordinal, (float) markers.velocity[1][ordinal]);
				domain.particles.setVelocityZ(ordinal, (float) markers.velocity[2][ordinal]);
				domain.particles.setMaterialHandle(ordinal, markers.material[ordinal]);
				domain.particles.setPhaseLabel(ordinal, domain.materialRegistry == null ? 0 : domain.materialRegistry.phaseHandle(markers.material[ordinal]));
			}
		});
		if (domain.chemicalParcels != null) {
			domain.chemicalParcels.commitMarkerRemap(chemistry);
			var changed = candidate.changedSpeciesCells();
			for (int c = 0; c < changed.length; c++) if (changed[c]) domain.chemicalParcels.markDirtyCell(c);
		}
		if (energies != null) domain.thermalState.restoreLiquidEnergies(energies);
		domain.particles.setCount(count); domain.bumpCarrierMutationRevision(); domain.gridDirty = true;
		if (energies != null) domain.thermalState.recordRoundoff(candidate.energyRoundoff());
		if (gasTopology != null) domain.gasTopologyCoordinator.commit(gasTopology);
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
