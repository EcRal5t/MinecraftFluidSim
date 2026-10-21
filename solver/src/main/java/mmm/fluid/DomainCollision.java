package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.chemistry.ChemicalParcelStore;
import java.util.List;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalTransferLedger;
import java.util.ArrayList;
import mmm.granular.GranularParcelStore;
import mmm.granular.SolidVolume;
import mmm.thermal.GranularEnergyColumn;
import mmm.thermal.ThermalDomainState;
import mmm.units.SignedDyadic;
import mmm.granular.GranularAuditLedger;
import mmm.granular.GranularDepositStore;
import mmm.chemistry.ChemicalRegistry;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static mmm.fluid.FluidDomain.*;

/** Applies fixed collision edits, initial inventory and material binding with their prepared successors. */
final class DomainCollision {
	private final FluidDomain domain;
	DomainCollision(FluidDomain domain) { this.domain = domain; }
	public void setMarkerChemical(int ordinal, Map<String, Long> stableAmounts, boolean indicator) {
		var stock = domain.liquidCells.physicalStockAt(ordinal);
		var registry = domain.chemicalParcels().registry();
		var zero = ChemicalMixtureRecord.fromStableAmounts(registry, stableAmounts, 0);
		long speciesMass=zero.speciesMassUnits(registry);
		if(speciesMass>stock.massUnits())throw new IllegalArgumentException("Chemical assignment exceeds the existing stock mass");
		var record = zero.withMassResidualUnits(Math.subtractExact(stock.massUnits(), speciesMass));
		domain.chemicalParcels.requireCompatible(record, domain.materialRegistry.material(stock.key().material()).id(), indicator);
		if (domain.thermalState != null) domain.thermalState.requireReactionState(ordinal, record,
				domain.unitScale.normalizedMass(stock.massUnits()), domain.domainCapabilities.thermalMarkerContext());
		domain.liquidCells.planEdits(List.of(new LiquidCellInventory.Edit(stock.key(), new LiquidCellInventory.Stock(stock.key(), stock.volume(),
				stock.massUnits(), record, stock.internalEnergy(), indicator)))).commit();
		domain.chemicalParcels.markDirtyCell(stock.key().cell());
	}
	public int materialHandle(String materialId) {
		if (domain.materialRegistry == null) throw new IllegalStateException("Material registry is not bound");
		return domain.materialRegistry.handle(materialId);
	}
	public void restoreParticleState(FlipParticleState state) {
		if (domain.materialDefinitions != null) throw new IllegalStateException("Pure input cannot remove bound material identity");
		LiquidInitialInput.restore(domain, state, null, null, null, null, null);
		domain.chemicalParcels = null;
	}
	public void restoreMaterialParticleState(FlipMaterialParticleState state) {
		if (state == null) throw new IllegalArgumentException("Material input is missing");
		var registry = new FluidMaterialRegistry(state.materials());
		if (domain.materialDefinitions != null) LiquidMaterialBinding.validate(domain, registry, domain.materialDefinitions);
		LiquidInitialInput.restore(domain, state.particles(), registry, state.copyMaterialPaletteIndex(), null, null, null);
		domain.chemicalParcels = null;
	}
	public void restoreChemicalParticleState(FlipChemicalParticleState state, ChemicalRegistry registry) {
		if (state == null || registry == null || !state.registryFingerprint().equals(registry.fingerprint()))
			throw new IllegalArgumentException("Chemical input registry mismatch");
		var incoming = state.materials();
		var palette = new FluidMaterialRegistry(incoming.materials());
		var table = state.runtimeMixtures(registry); int[] indices = state.copyMarkerMixtureIndex();
		var mixtures = new ArrayList<ChemicalMixtureRecord>();
		for (int index : indices) mixtures.add(table.get(index));
		var metadata = new ChemicalParcelStore(registry, domain.unitScale, domain.maximumParticleCount, domain.cellCount);
		metadata.restoreAuditLedgers(state.externalSource(), state.escapedSink());
		int[] materials = incoming.copyMaterialPaletteIndex();byte[] indicators = state.copyIndicator();
		for (int ordinal = 0; ordinal < materials.length; ordinal++)
			metadata.requireCompatible(mixtures.get(ordinal), palette.material(materials[ordinal]).id(), indicators[ordinal] != 0);
		LiquidInitialInput.restore(domain, incoming.particles(), palette, materials, mixtures, indicators, registry);
		domain.chemicalParcels = metadata;
		for (var stock : domain.liquidCells.stocks()) domain.chemicalParcels.markDirtyCell(stock.key().cell());
	}
	public FlipParticleState exportParticleState() { return domain.carriers.exportParticleState(); }
	public FlipMaterialParticleState exportMaterialParticleState() { return domain.carriers.exportMaterialParticleState(); }
	public float setSolid(int x, int y, int z, boolean value) {
		return setSolidMicroMask(x, y, z, value ? FlipCutCellGeometry.FULL_MASK : 0);
	}
	public float setSolidMicroMask(int x, int y, int z, int mask) {
		ChemicalTransferLedger.Builder displaced = domain.chemicalParcels == null ? null
				: ChemicalTransferLedger.builder(domain.chemicalParcels.registry());
		float volume = setSolidMicroMaskInternal(x, y, z, mask, displaced, null, null);
		if (displaced != null) domain.chemicalParcels.recordEscapedSink(displaced.build());
		return volume;
	}
	public FlipChemicalParticleState exportChemicalParticleState() { return domain.carriers.exportChemicalParticleState(); }
	public ChemicalDisplacement setSolidMicroMaskChemical(int x, int y, int z, int mask) {
		if (domain.chemicalParcels == null) throw new IllegalStateException("Chemical collision transfer requires chemistry");
		ChemicalTransferLedger.Builder displaced = ChemicalTransferLedger.builder(domain.chemicalParcels.registry());
		RemovedThermalEnergy energy = new RemovedThermalEnergy();
		float volume = setSolidMicroMaskInternal(x, y, z, mask, displaced, null, energy);
		return new ChemicalDisplacement(volume, displaced.build(), energy.value().toDouble());
	}
	public MaterialDisplacement setSolidMicroMaskMaterial(int x, int y, int z, int mask) {
		if (domain.materialRegistry == null || domain.chemicalParcels != null) {
			throw new IllegalStateException("Material collision transfer requires non-chemical material state");
		}
		RemovedMaterialLedger material = new RemovedMaterialLedger(domain.materialRegistry.size());
		RemovedThermalEnergy energy = new RemovedThermalEnergy();
		float volume = setSolidMicroMaskInternal(x, y, z, mask, null, material, energy);
		return new MaterialDisplacement(volume, material.build(), energy.value().toDouble());
	}
	public CollisionEditPlan planCollisionEdits(List<CollisionEdit> edits) {
		if (edits == null || edits.isEmpty()) throw new IllegalArgumentException("Collision edit batch is missing or empty");
		domain.domainFlowFields.ensureVelocityGrid();
		ArrayList<CollisionEdit> canonical = new ArrayList<>(edits.size());
		int[] previousMasks = new int[edits.size()];
		boolean[] granularCells = domain.granularState == null ? null : new boolean[domain.cellCount];
		Set<Integer> uniqueCells = new HashSet<>();
		for (int editIndex = 0; editIndex < edits.size(); editIndex++) {
			CollisionEdit edit = edits.get(editIndex);
			if (edit == null || edit.mask() < 0 || edit.mask() > FlipCutCellGeometry.FULL_MASK) {
				throw new IllegalArgumentException("Collision edit is invalid");
			}
			int cell = domain.domainFlowFields.cellIndexChecked(edit.x(), edit.y(), edit.z());
			if (!uniqueCells.add(cell)) throw new IllegalArgumentException("Collision batch edits one cell twice");
			int previous = domain.geometry.cutCells.mask(cell);
			if (previous == edit.mask()) throw new IllegalArgumentException("Collision batch contains a no-op edit");
			previousMasks[editIndex] = previous;
			canonical.add(edit);
			if ((edit.mask() & ~previous) == 0) continue;
			if (domain.thermalState != null && domain.thermalState.coherentPhases().cellOccupiedSvq(cell) > 0L) {
				throw new CollisionEditBlockedException(
						"Collision edit intersects authoritative coherent phase inventory", null);
			}
			if (granularCells != null && (domain.granularCoupling.solidSvq(cell) > 0L
					|| domain.granularState.deposits.cellSolidVolumeSvq(cell) > 0L)) granularCells[cell] = true;
		}
		GranularCollisionPlan granularPlan;
		try {
			granularPlan = granularCells == null ? null : planGranularCollision(granularCells);
		} catch (RuntimeException failure) {
			throw new CollisionEditBlockedException("Granular collision displacement could not be planned", failure);
		}
		return new CollisionEditPlan((FluidDomain) domain, List.copyOf(canonical), previousMasks, domain.liquidCells.size(),
				Double.doubleToRawLongBits(totalVolumeExact()), totalMassUnits(), granularPlan,
				LiquidCollisionEdit.prepare(domain, canonical, granularPlan),
				domain.thermalState == null ? null : domain.thermalState.thermalRevision());
	}
	public void beginCollisionEdits(CollisionEditPlan plan) {
		if (plan == null || plan.owner != domain || plan.consumed || domain.activeCollisionPlan != null) {
			throw new IllegalArgumentException("Collision edit plan is missing, foreign or already consumed");
		}
		requireCollisionThermalCurrent(plan.thermalRevision);
		if (domain.liquidCells.size() != plan.planMarkerCount
				|| Double.doubleToRawLongBits(totalVolumeExact()) != plan.totalVolumeBits
				|| totalMassUnits() != plan.totalMassUnits) {
			throw new IllegalStateException("Liquid inventory changed after collision planning");
		}
		for (int index = 0; index < plan.edits.size(); index++) {
			CollisionEdit edit = plan.edits.get(index);
			if (solidMicroMask(edit.x(), edit.y(), edit.z()) != plan.previousMasks[index]) {
				throw new IllegalStateException("Collision geometry changed after planning");
			}
		}
		plan.liquid.inventory.requireCurrent();
		if (plan.liquid.gas != null) domain.gasTopologyCoordinator.requireBeforeCarrierCommit(plan.liquid.gas);
		if (domain.thermalState != null) {
			var audit = domain.thermalState.auditLedger().withExternalSinkEnergy(
					plan.granular == null ? SignedDyadic.ZERO : plan.granular.removedEnergy());
			var gas = plan.liquid.gas == null ? null : plan.liquid.gas.thermalPlan();
			if (gas != null) audit = audit.withExternalSourceEnergy(gas.sourceEnergy()).withExternalSinkEnergy(gas.sinkEnergy())
					.withMechanicalWork(gas.mechanicalWork()).withRoundoff(gas.roundoff());
			audit.withExternalSinkEnergy(plan.liquid.removedEnergy).withMechanicalWork(plan.liquid.inventory.mechanicalHeat())
					.withRoundoff(plan.liquid.energyRoundoff).expectedInventory();
		}
		if (plan.granular != null) try {
			commitGranularCollision(plan.granular);
		} catch (RuntimeException failure) {
			throw new CollisionEditBlockedException("Granular collision displacement could not be committed", failure);
		}
		plan.liquid.inventory.commit();
		domain.cellMacStage.commitMomentum(plan.liquid.momentum);
		domain.geometry.cutCells.restoreMicroMasks(plan.liquid.masks);
		for (var edit : plan.edits) {
			int cell = domain.domainFlowFields.cellIndex(edit.x(), edit.y(), edit.z());
			domain.geometry.solid[cell] = edit.mask() == 255; domain.pressureSystem.clearLiquidPressure(cell);
			if (domain.chemicalParcels != null) domain.chemicalParcels.markDirtyCell(cell);
			if (domain.granularDepositDynamics != null) domain.granularDepositDynamics.disturbCell(cell);
		}
		domain.markCellInventoryChanged();
		if (plan.liquid.gas != null) domain.gasTopologyCoordinator.commit(plan.liquid.gas);
		if (domain.thermalState != null) {
			domain.thermalState.recordExternalSink(plan.liquid.removedEnergy);
			domain.thermalState.restoreAuditLedger(domain.thermalState.auditLedger().withMechanicalWork(plan.liquid.inventory.mechanicalHeat()));
			domain.thermalState.recordRoundoff(plan.liquid.energyRoundoff);
		}
		plan.consumed = true;
		domain.activeCollisionPlan = plan;
		domain.activeCollisionEditIndex = 0;
	}
	float setSolidMicroMaskInternal(
			int x, int y, int z, int mask, ChemicalTransferLedger.Builder chemicalDisplacement,
			RemovedMaterialLedger materialDisplacement, RemovedThermalEnergy thermalDisplacement
	) {
		domain.domainFlowFields.cellIndexChecked(x, y, z);
		if (domain.activeCollisionPlan == null) {
			if (domain.geometry.cutCells.mask(domain.domainFlowFields.cellIndex(x, y, z)) == mask) return 0;
			beginCollisionEdits(planCollisionEdits(List.of(new CollisionEdit(x, y, z, mask))));
		}
		var expected = domain.activeCollisionPlan.edits.get(domain.activeCollisionEditIndex);
		if (expected.x() != x || expected.y() != y || expected.z() != z || expected.mask() != mask)
			throw new IllegalStateException("Collision edits differ from prepared batch order");
		float displaced = domain.activeCollisionPlan.liquid.report(domain.activeCollisionEditIndex, domain, chemicalDisplacement, materialDisplacement, thermalDisplacement);
		if (++domain.activeCollisionEditIndex == domain.activeCollisionPlan.edits.size()) { domain.activeCollisionPlan = null; domain.activeCollisionEditIndex = 0; }
		return displaced;
	}
	GranularCollisionPlan planGranularCollision(boolean[] affectedCells) {
		domain.exportGranularState();
		domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		boolean[] selectedParcels = new boolean[domain.granularState.parcels.count()];
		int selectedCount = 0;
		for (int parcel = 0; parcel < domain.granularState.parcels.count(); parcel++) {
			GranularParcelStore.Parcel state = domain.granularState.parcels.parcel(parcel);
			for (int cell = 0; cell < affectedCells.length; cell++) {
				if (!affectedCells[cell] || domain.granularCoupling.parcelShareAtCell(
						domain.parcelPosition(state), state.solidVolumeSvq(), cell) <= 0L) continue;
				selectedParcels[parcel] = true;
				selectedCount++;
				break;
			}
		}
		boolean[] selectedDepositCells = new boolean[domain.cellCount];
		boolean anyDeposit = false;
		for (int cell = 0; cell < domain.cellCount; cell++) if (affectedCells[cell]
				&& domain.granularState.deposits.cellSolidVolumeSvq(cell) > 0L) {
			selectedDepositCells[cell] = true;
			anyDeposit = true;
		}
		if (selectedCount == 0 && !anyDeposit) return null;
		GranularAuditLedger removed = GranularAuditLedger.captureSelected(
				domain.granularState.registry, domain.granularState.parcels, parcel -> selectedParcels[parcel],
				domain.granularState.deposits, selectedCell -> selectedDepositCells[selectedCell], domain.granularState.compositions);
		GranularAuditLedger sinkAfter = domain.granularState.escapedSink.plus(removed);
		ArrayList<CollisionDeposit> depositGroups = new ArrayList<>();
		if (anyDeposit) domain.granularState.deposits.forEachGroup((groupCell, porosity, material, composition, svq, mass) -> {
			if (selectedDepositCells[groupCell]) depositGroups.add(new CollisionDeposit(groupCell,
					new GranularDepositStore.Group(material, composition, svq, mass)));
		});
		SignedDyadic removedEnergy = SignedDyadic.ZERO;
		if (domain.thermalState != null) {
			var audit = domain.thermalState.auditLedger();
			// Prepare the same descending swap-removal energy chain that will be published.
			for (int parcel = selectedParcels.length - 1; parcel >= 0; parcel--) if (selectedParcels[parcel]) {
				GranularParcelStore.Parcel state = domain.granularState.parcels.parcel(parcel);
				var energy = SignedDyadic.of(domain.thermalState.previewActiveTake(parcel, state.massUnits(), state.massUnits()));
				removedEnergy = removedEnergy.add(energy);
				audit = audit.withExternalSinkEnergy(energy);
			}
			for (CollisionDeposit deposit : depositGroups) {
				GranularDepositStore.Group group = deposit.group();
				var energy = SignedDyadic.of(domain.thermalState.previewDepositTake(new GranularEnergyColumn.DepositKey(
						deposit.cell(), group.materialHandle(), group.solidCompositionHandle()),
						group.massUnits(), group.massUnits()));
				removedEnergy = removedEnergy.add(energy);
				audit = audit.withExternalSinkEnergy(energy);
			}
			audit.expectedInventory();
		}

		long plannedSvq = 0L;
		long plannedMass = 0L;
		for (int parcel = 0; parcel < selectedParcels.length; parcel++) if (selectedParcels[parcel]) {
			GranularParcelStore.Parcel state = domain.granularState.parcels.parcel(parcel);
			plannedSvq = Math.addExact(plannedSvq, state.solidVolumeSvq());
			plannedMass = Math.addExact(plannedMass, state.massUnits());
		}
		for (CollisionDeposit deposit : depositGroups) {
			plannedSvq = Math.addExact(plannedSvq, deposit.group().solidVolumeSvq());
			plannedMass = Math.addExact(plannedMass, deposit.group().massUnits());
		}
		long auditSvq = 0L;
		long auditMass = 0L;
		for (GranularAuditLedger.Entry entry : removed.entries()) {
			auditSvq = Math.addExact(auditSvq, entry.solidVolumeSvq());
			auditMass = Math.addExact(auditMass, entry.massUnits());
		}
		if (auditSvq != plannedSvq || auditMass != plannedMass) {
			throw new IllegalStateException("Granular collision plan differs from its preflight audit");
		}
		return new GranularCollisionPlan(selectedParcels, List.copyOf(depositGroups), sinkAfter,
				affectedCells.clone(), domain.granularState.parcels.count(), domain.granularState.inventory.totalSolidVolumeSvq(),
				domain.granularState.inventory.totalMassUnits(), removedEnergy,
				domain.thermalState == null ? null : domain.thermalState.thermalRevision());
	}
	private void requireCollisionThermalCurrent(ThermalDomainState.Revision revision) {
		if (domain.thermalState == null ? revision != null : !domain.thermalState.matchesThermalRevision(revision))
			throw new IllegalStateException("Thermal inventory changed after collision planning");
	}
	void commitGranularCollision(GranularCollisionPlan plan) {
		requireCollisionThermalCurrent(plan.thermalRevision());
		if (domain.granularState.parcels.count() != plan.parcelCount()
				|| domain.granularState.inventory.totalSolidVolumeSvq() != plan.totalSvq()
				|| domain.granularState.inventory.totalMassUnits() != plan.totalMassUnits()) {
			throw new IllegalStateException("Granular inventory changed after collision planning");
		}
		for (int parcel = plan.selectedParcels().length - 1; parcel >= 0; parcel--) if (plan.selectedParcels()[parcel]) {
			GranularParcelStore.Parcel state = domain.granularState.parcels.parcel(parcel);
			if (domain.thermalState != null) domain.thermalState.commitExternalActiveTake(
					parcel, state.massUnits(), state.massUnits());
			domain.granularState.parcels.removeSwap(parcel);
			if (state.solidCompositionHandle() != 0) domain.granularState.compositions.release(state.solidCompositionHandle());
		}
		for (CollisionDeposit deposit : plan.depositGroups()) {
			GranularDepositStore.Group group = deposit.group();
			if (domain.thermalState != null) domain.thermalState.commitExternalDepositTake(new GranularEnergyColumn.DepositKey(
					deposit.cell(), group.materialHandle(), group.solidCompositionHandle()),
					group.massUnits(), group.massUnits());
			GranularDepositStore.SolidPortion taken = domain.granularState.deposits.take(
					deposit.cell(), group.materialHandle(), group.solidCompositionHandle(), group.solidVolumeSvq());
			if (taken.solidCompositionHandle() != 0) domain.granularState.compositions.release(taken.solidCompositionHandle());
		}
		domain.granularState.escapedSink = plan.sinkAfter();
		domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);

		for (int cell = 0; cell < plan.affectedCells().length; cell++) {
			if (plan.affectedCells()[cell]) domain.domainPhaseInventory.markGranularExternalChange(cell);
		}
	}
	public boolean isSolid(int x, int y, int z) { return domain.geometry.solid[domain.domainFlowFields.cellIndexChecked(x, y, z)]; }
	public int solidMicroMask(int x, int y, int z) { return domain.geometry.cutCells.mask(domain.domainFlowFields.cellIndexChecked(x, y, z)); }
	public float solidVolumeFraction(int x, int y, int z) {
		return 1.0F - domain.geometry.cutCells.openCellVolume(domain.domainFlowFields.cellIndexChecked(x, y, z));
	}
	public float openFaceFractionX(int x, int y, int z) { return domain.geometry.cutCells.xFaceOpen(x, y, z); }
	public float openFaceFractionY(int x, int y, int z) { return domain.geometry.cutCells.yFaceOpen(x, y, z); }
	public float openFaceFractionZ(int x, int y, int z) { return domain.geometry.cutCells.zFaceOpen(x, y, z); }
	public void setVolume(int x, int y, int z, float value) {
		domain.transferPlanner.replaceCellVolumes(new int[]{domain.domainFlowFields.cellIndexChecked(x,y,z)},new float[]{value},null);
	}
	public void setVolumes(float[] cellVolumes) { setVolumes(cellVolumes,null); }
	public void setVolumes(float[] cellVolumes,int[] materialHandles) {
		if (cellVolumes==null || cellVolumes.length!=domain.cellCount || materialHandles!=null && materialHandles.length!=domain.cellCount)
			throw new IllegalArgumentException("Cell volumes and materials must cover the entire grid");
		int[] cells=new int[domain.cellCount];for(int cell=0;cell<domain.cellCount;cell++) cells[cell]=cell;
		domain.transferPlanner.replaceCellVolumes(cells,cellVolumes,materialHandles);
	}
	public float volume(int x, int y, int z) {
		domain.domainFlowFields.ensureInventoryGrid();
		return domain.volume[domain.domainFlowFields.cellIndexChecked(x, y, z)];
	}
	public float pressure(int x, int y, int z) {
		int cell = domain.domainFlowFields.cellIndexChecked(x, y, z);
		domain.pressureSystem.prepareGeometry();
		return (float) domain.pressureSystem.liquidPressureAt(cell, ~domain.geometry.cutCells.mask(cell) & 255);
	}
	float surfaceTensionPressureAt(int x, int y, int z) {
		domain.pressureSystem.prepareGeometry();
		return domain.tension.surfaceTensionPressure[domain.domainFlowFields.cellIndexChecked(x, y, z)];
	}
	public float totalVolume() { return domain.metrics.totalVolume(); }
	public double totalVolumeExact() { return domain.metrics.totalVolumeExact(); }
	public float totalOpenVolume() { return domain.metrics.totalOpenVolume(); }
	public double totalOpenVolumeExact() { return domain.metrics.totalOpenVolumeExact(); }
	double totalGeometricOpenVolumeExact() {
		double result = 0.0;
		for (int cell = 0; cell < domain.cellCount; cell++) result += domain.geometry.cutCells.openCellVolume(cell);
		return result;
	}
	double remainingCarrierVolumeExact() {
		long solid = Math.addExact(domain.totalGranularSolidVolumeSvq(), domain.totalCoherentSolidVolumeSvq());
		return totalGeometricOpenVolumeExact() - solid / (double) SolidVolume.SVQ_PER_CELL - totalVolumeExact();
	}
	boolean canAddCarrierVolume(double volume) {
		double remaining = remainingCarrierVolumeExact() - volume;
		return remaining > 0.0 || remaining == 0.0
				&& (!domain.gasState.sealedDomainBoundary || domain.gasState.network.sealedInventoryNanos() == 0L);
	}
	float effectiveOpenCellVolume(int cell) {
		float geometric = domain.geometry.cutCells.openCellVolume(cell);
		return domain.granularCoupling == null || domain.granularCoupling.totalSolidSvq() == 0 ? geometric
				: domain.granularCoupling.effectiveOpenCellVolume(cell, geometric);
	}
	public long totalMassUnits() { return domain.metrics.totalMassUnits(); }
	public double totalMass() { return domain.metrics.totalMass(); }
	public int dominantMaterialInCell(int x, int y, int z) { return domain.metrics.dominantMaterialInCell(x, y, z); }
	public double exactVolumeNear(int centerX, int centerY, int centerZ, int maxRadius, int materialHandle) { return domain.metrics.exactVolumeNear(centerX, centerY, centerZ, maxRadius, materialHandle); }
	MaterialInventory materialInventory(int materialHandle) {
		double volume = 0, mass = 0, weightedY = 0;
		for (var stock : domain.liquidCells.stocks()) if (stock.key().material() == materialHandle) {
			volume += stock.volumeFraction(); mass += domain.unitScale.normalizedMass(stock.massUnits());
			weightedY += (stock.key().cell() / domain.sizeX % domain.sizeY + .5) * stock.volumeFraction();
		}
		return new MaterialInventory(volume, mass, volume == 0 ? Double.NaN : weightedY / volume);
	}
	int phaseCountInCell(int x, int y, int z) {
		domain.domainFlowFields.ensureInventoryGrid();
		int result = 0;
		for (int entry = domain.liquidGrid.cellPhaseHead[domain.domainFlowFields.cellIndexChecked(x, y, z)]; entry >= 0; entry = domain.liquidGrid.phaseEntryNext[entry]) result++;
		return result;
	}
	void setMaterialInCell(int x, int y, int z, FluidMaterialRegistry registry, int materialHandle) {
		int cell = domain.domainFlowFields.cellIndexChecked(x, y, z);
		bindMaterialRegistry(registry);
		if (domain.liquidCells.physicalStocksInCell(cell).isEmpty()) throw new IllegalArgumentException("Cannot assign material to empty cell");
		var keys = domain.liquidCells.physicalStocksInCell(cell).stream().map(LiquidCellInventory.Stock::key).toList();
		for (var key : keys) assignStockMaterial(key, registry, materialHandle);
	}
	void setMarkerMaterial(int ordinal, FluidMaterialRegistry registry, int materialHandle) {
		bindMaterialRegistry(registry); assignStockMaterial(domain.liquidCells.physicalStockAt(ordinal).key(), registry, materialHandle);
	}
	private void assignStockMaterial(LiquidCellInventory.Key key, FluidMaterialRegistry registry, int materialHandle) {
		if (domain.chemicalParcels != null) throw new IllegalStateException("Material relabeling requires exact chemical transfer");
		var stock = domain.liquidCells.stock(key);
		if (key.material() == materialHandle) return;
		long mass = domain.unitScale.massUnitsForNormalized(stock.volumeFraction() * registry.material(materialHandle).referenceDensity());
		var nextKey = new LiquidCellInventory.Key(key.cell(), materialHandle, key.supportMask());
		var candidate = domain.liquidCells.copy(); candidate.planEdits(List.of(new LiquidCellInventory.Edit(key, null))).commit();
		candidate.planAdditions(List.of(new LiquidCellInventory.Stock(nextKey, stock.volume(), mass, null, stock.internalEnergy(), stock.indicator()))).commit();
		var edits = new ArrayList<LiquidCellInventory.Edit>();
		edits.add(new LiquidCellInventory.Edit(key, null)); edits.add(new LiquidCellInventory.Edit(nextKey, candidate.stock(nextKey)));
		var plan = domain.liquidCells.planEdits(edits); var momentum = domain.cellMacStage.prepareExternalMomentum(plan);
		plan = plan.withMechanicalHeat(momentum.heatByCell());
		plan.commit(); domain.cellMacStage.commitMomentum(momentum); domain.markCellInventoryChanged();
		if (domain.thermalState != null) domain.thermalState.restoreAuditLedger(domain.thermalState.auditLedger()
				.withMechanicalWork(plan.mechanicalHeat())
				.withRoundoff(plan.energyChange().subtract(plan.mechanicalHeat())));
	}
	public void bindMaterialRegistry(FluidMaterialRegistry registry) {
		if (registry == null) throw new IllegalArgumentException("Material registry must not be null");
		if (domain.materialRegistry != null && !domain.materialRegistry.materials().equals(registry.materials())) {
			throw new IllegalArgumentException("A domain cannot change its ordered liquid palette");
		}
		// Equal ordered definitions give the same material and phase handles. Retain
		// the bound registry and its runtime pair tensions across restored inputs.
		if (domain.materialRegistry != null) return;
		double defaultDensity = registry.material(0).referenceDensity();
		double tolerance = Math.max(1.0E-9, Math.abs(domain.config.density()) * 1.0E-12);
		if (Math.abs(defaultDensity - domain.config.density()) > tolerance) {
			throw new IllegalArgumentException("Material handle 0 must match the domain default density");
		}
		domain.materialRegistry = registry;
	}
	void assignMarkerMaterial(int ordinal, FluidMaterialRegistry registry, int materialHandle) { setMarkerMaterial(ordinal, registry, materialHandle); }
	boolean hasNonDefaultMaterialState() { return domain.materialRegistry != null; }
	public float maximumParticleSpeed() { return domain.metrics.maximumParticleSpeed(); }
	public float maximumPressure() { return domain.metrics.maximumPressure(); }
	public byte[] copyQuantizedVolume() { return domain.carriers.copyQuantizedVolume(); }
	public byte[] copyBinarySolidMask() { return domain.carriers.copyBinarySolidMask(); }
	public byte[] copyCollisionMicroMasks() { return domain.carriers.copyCollisionMicroMasks(); }
	public byte[] copyMarkerMask() { return domain.carriers.copyMarkerMask(); }
	public FluidMaterialRenderSnapshot copyMaterialRenderSnapshot() { return domain.carriers.copyMaterialRenderSnapshot(); }
	public VolumeDistribution volumeDistribution(float visibleThreshold) { return domain.metrics.volumeDistribution(visibleThreshold); }
	public SolidDistribution solidDistribution() { return domain.metrics.solidDistribution(); }
	public boolean containsFluidAt(float localX, float localY, float localZ) { return domain.metrics.containsFluidAt(localX, localY, localZ); }
}
