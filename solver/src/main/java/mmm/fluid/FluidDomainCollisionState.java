package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.granular.SolidVolume;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalParcelStore;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.granular.GranularAuditLedger;
import mmm.granular.GranularDepositStore;
import mmm.granular.GranularParcelStore;
import mmm.units.SignedDyadic;
import mmm.thermal.GranularEnergyColumn;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Restores carrier state and coordinates atomic collision-geometry replacement. */
abstract class FluidDomainCollisionState extends FluidDomainInventoryTransactions {
	FluidDomainCollisionState(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, int maximumParticleCount) {
		super(sizeX, sizeY, sizeZ, config, maximumParticleCount);
	}

	public void setMarkerChemical(int ordinal, Map<String, Long> stableAmounts, boolean indicator) {
		var stock = liquidCells.physicalStockAt(ordinal);
		var registry = domain().chemicalParcels().registry();
		var zero = ChemicalMixtureRecord.fromStableAmounts(registry, stableAmounts, 0);
		var record = zero.withMassResidualUnits(Math.subtractExact(stock.massUnits(), zero.speciesMassUnits(registry)));
		chemicalParcels.requireCompatible(record, materialRegistry.material(stock.key().material()).id(), indicator);
		if (thermalState != null) thermalState.requireReactionState(ordinal, record,
				unitScale.normalizedMass(stock.massUnits()), domain().thermalMarkerContext());
		liquidCells.planEdits(List.of(new LiquidCellInventory.Edit(stock.key(), new LiquidCellInventory.Stock(stock.key(), stock.volume(),
				stock.massUnits(), record, stock.internalEnergy(), indicator)))).commit();
		chemicalParcels.markDirtyCell(stock.key().cell());
	}

	/** Resolves a stable material id to the runtime handle of the bound registry. */
	public int materialHandle(String materialId) {
		if (materialRegistry == null) throw new IllegalStateException("Material registry is not bound");
		return materialRegistry.handle(materialId);
	}

	/** Imports a point fixture once into the common cell/MAC representation. */
	public void restoreParticleState(FlipParticleState state) {
		if (materialDefinitions != null) throw new IllegalStateException("Pure input cannot remove bound material identity");
		LiquidInitialInput.restore(domain(), state, null, null, null, null, null);
		chemicalParcels = null;
	}

	public void restoreMaterialParticleState(FlipMaterialParticleState state) {
		if (state == null) throw new IllegalArgumentException("Material input is missing");
		var registry = new FluidMaterialRegistry(state.materials());
		if (materialDefinitions != null) LiquidMaterialBinding.validate(domain(), registry, materialDefinitions);
		LiquidInitialInput.restore(domain(), state.particles(), registry, state.copyMaterialPaletteIndex(), null, null, null);
		chemicalParcels = null;
	}

	public void restoreChemicalParticleState(FlipChemicalParticleState state, ChemicalRegistry registry) {
		if (state == null || registry == null || !state.registryFingerprint().equals(registry.fingerprint()))
			throw new IllegalArgumentException("Chemical input registry mismatch");
		var incoming = state.materials();
		var palette = new FluidMaterialRegistry(incoming.materials());
		var table = state.runtimeMixtures(registry); int[] indices = state.copyMarkerMixtureIndex();
		var mixtures = new ArrayList<ChemicalMixtureRecord>();
		for (int index : indices) mixtures.add(table.get(index));
		var metadata = new ChemicalParcelStore(registry, unitScale, maximumParticleCount, cellCount);
		metadata.restoreAuditLedgers(state.externalSource(), state.escapedSink());
		LiquidInitialInput.restore(domain(), incoming.particles(), palette, incoming.copyMaterialPaletteIndex(), mixtures, state.copyIndicator(), registry);
		chemicalParcels = metadata;
		for (var stock : liquidCells.stocks()) chemicalParcels.markDirtyCell(stock.key().cell());
	}

	/** Exports an immutable CPU marker/velocity/solid handoff without serializing reconstructed density. */
	public FlipParticleState exportParticleState() { return carriers.exportParticleState(); }

	/** Exports exact multi-material marker state; runtime handles are replaced by the stable ordered palette. */
	public FlipMaterialParticleState exportMaterialParticleState() { return carriers.exportMaterialParticleState(); }

	/** Sets collision occupancy and returns the exact particle volume displaced. */
	public float setSolid(int x, int y, int z, boolean value) {
		return setSolidMicroMask(x, y, z, value ? FlipCutCellGeometry.FULL_MASK : 0);
	}

	/**
	 * Replaces one runtime 2x2x2 cut-cell mask and returns exact marker volume
	 * whose centers became occupied or exceeds the new open-cell capacity. This
	 * geometry is part of the authoritative in-memory/checkpoint collision state.
	 */
	public float setSolidMicroMask(int x, int y, int z, int mask) {
		ChemicalTransferLedger.Builder displaced = chemicalParcels == null ? null
				: ChemicalTransferLedger.builder(chemicalParcels.registry());
		float volume = setSolidMicroMaskInternal(x, y, z, mask, displaced, null, null);
		if (displaced != null) chemicalParcels.recordEscapedSink(displaced.build());
		return volume;
	}

	/** Exports canonical stable-ID chemical state; runtime mixture handles never cross this boundary. */
	public FlipChemicalParticleState exportChemicalParticleState() { return carriers.exportChemicalParticleState(); }

	/** Collision edit that returns the complete parcel ledger for conservative nearby restoration. */
	public ChemicalDisplacement setSolidMicroMaskChemical(int x, int y, int z, int mask) {
		if (chemicalParcels == null) throw new IllegalStateException("Chemical collision transfer requires chemistry");
		ChemicalTransferLedger.Builder displaced = ChemicalTransferLedger.builder(chemicalParcels.registry());
		RemovedThermalEnergy energy = new RemovedThermalEnergy();
		float volume = setSolidMicroMaskInternal(x, y, z, mask, displaced, null, energy);
		return new ChemicalDisplacement(volume, displaced.build(), energy.value().toDouble());
	}

	/** Collision edit that returns every displaced material ledger plus its independently carried U. */
	public MaterialDisplacement setSolidMicroMaskMaterial(int x, int y, int z, int mask) {
		if (materialRegistry == null || chemicalParcels != null) {
			throw new IllegalStateException("Material collision transfer requires non-chemical material state");
		}
		RemovedMaterialLedger material = new RemovedMaterialLedger(materialRegistry.size());
		RemovedThermalEnergy energy = new RemovedThermalEnergy();
		float volume = setSolidMicroMaskInternal(x, y, z, mask, null, material, energy);
		return new MaterialDisplacement(volume, material.build(), energy.value().toDouble());
	}

	/**
	 * Preflights a complete world collision refresh before any solver owner is mutated. Coherent
	 * solids reject the whole batch; granular parcels/deposits touching several edited cells are
	 * selected once and their composition/thermal sidecars are validated as one removal.
	 */
	public CollisionEditPlan planCollisionEdits(List<CollisionEdit> edits) {
		if (edits == null || edits.isEmpty()) throw new IllegalArgumentException("Collision edit batch is missing or empty");
		ensureGrid();
		ArrayList<CollisionEdit> canonical = new ArrayList<>(edits.size());
		int[] previousMasks = new int[edits.size()];
		boolean[] granularCells = granularState == null ? null : new boolean[cellCount];
		Set<Integer> uniqueCells = new HashSet<>();
		for (int editIndex = 0; editIndex < edits.size(); editIndex++) {
			CollisionEdit edit = edits.get(editIndex);
			if (edit == null || edit.mask() < 0 || edit.mask() > FlipCutCellGeometry.FULL_MASK) {
				throw new IllegalArgumentException("Collision edit is invalid");
			}
			int cell = cellIndexChecked(edit.x(), edit.y(), edit.z());
			if (!uniqueCells.add(cell)) throw new IllegalArgumentException("Collision batch edits one cell twice");
			int previous = geometry.cutCells.mask(cell);
			if (previous == edit.mask()) throw new IllegalArgumentException("Collision batch contains a no-op edit");
			previousMasks[editIndex] = previous;
			canonical.add(edit);
			if ((edit.mask() & ~previous) == 0) continue;
			if (thermalState != null && thermalState.coherentPhases().cellOccupiedSvq(cell) > 0L) {
				throw new CollisionEditBlockedException(
						"Collision edit intersects authoritative coherent phase inventory", null);
			}
			if (granularCells != null && (granularCoupling.solidSvq(cell) > 0L
					|| granularState.deposits.cellSolidVolumeSvq(cell) > 0L)) granularCells[cell] = true;
		}
		GranularCollisionPlan granularPlan;
		try {
			granularPlan = granularCells == null ? null : planGranularCollision(granularCells);
		} catch (RuntimeException failure) {
			throw new CollisionEditBlockedException("Granular collision displacement could not be planned", failure);
		}
		return new CollisionEditPlan((FluidDomain) this, List.copyOf(canonical), previousMasks, liquidCells.size(),
				Double.doubleToRawLongBits(totalVolumeExact()), totalMassUnits(), granularPlan,
				LiquidCollisionEdit.prepare(domain(), canonical, granularPlan));
	}

	/** Commits the already validated cross-owner prelude; the supplied masks may then be applied without refusal. */
	public void beginCollisionEdits(CollisionEditPlan plan) {
		if (plan == null || plan.owner != this || plan.consumed || activeCollisionPlan != null) {
			throw new IllegalArgumentException("Collision edit plan is missing, foreign or already consumed");
		}
		if (liquidCells.size() != plan.planMarkerCount
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
		if (plan.liquid.gas != null) gasTopologyCoordinator.requireBeforeCarrierCommit(plan.liquid.gas);
		if (thermalState != null) {
			var audit = thermalState.auditLedger().withExternalSinkEnergy(plan.liquid.removedEnergy);
			var gas = plan.liquid.gas == null ? null : plan.liquid.gas.thermalPlan();
			if (gas != null) audit.withExternalSourceEnergy(gas.sourceEnergy()).withExternalSinkEnergy(gas.sinkEnergy())
					.withMechanicalWork(gas.mechanicalWork()).withRoundoff(gas.roundoff()).expectedInventory();
		}
		if (plan.granular != null) try {
			commitGranularCollision(plan.granular);
		} catch (RuntimeException failure) {
			throw new CollisionEditBlockedException("Granular collision displacement could not be committed", failure);
		}
		plan.liquid.inventory.commit();
		mac.restoreMomentum(plan.liquid.momentum);
		geometry.cutCells.restoreMicroMasks(plan.liquid.masks);
		for (var edit : plan.edits) {
			int cell = cellIndex(edit.x(), edit.y(), edit.z());
			geometry.solid[cell] = edit.mask() == 255; projection.pressure[cell] = 0;
			if (chemicalParcels != null) chemicalParcels.markDirtyCell(cell);
			if (granularDepositDynamics != null) granularDepositDynamics.disturbCell(cell);
		}
		domain().markCellInventoryChanged();
		if (plan.liquid.gas != null) gasTopologyCoordinator.commit(plan.liquid.gas);
		if (thermalState != null) {
			thermalState.recordExternalSink(plan.liquid.removedEnergy);
			thermalState.recordRoundoff(plan.liquid.energyRoundoff);
		}
		plan.consumed = true;
		activeCollisionPlan = plan;
		activeCollisionEditIndex = 0;
	}

	float setSolidMicroMaskInternal(
			int x, int y, int z, int mask, ChemicalTransferLedger.Builder chemicalDisplacement,
			RemovedMaterialLedger materialDisplacement, RemovedThermalEnergy thermalDisplacement
	) {
		cellIndexChecked(x, y, z);
		if (activeCollisionPlan == null) {
			if (geometry.cutCells.mask(cellIndex(x, y, z)) == mask) return 0;
			beginCollisionEdits(planCollisionEdits(List.of(new CollisionEdit(x, y, z, mask))));
		}
		var expected = activeCollisionPlan.edits.get(activeCollisionEditIndex);
		if (expected.x() != x || expected.y() != y || expected.z() != z || expected.mask() != mask)
			throw new IllegalStateException("Collision edits differ from prepared batch order");
		float displaced = activeCollisionPlan.liquid.report(activeCollisionEditIndex, domain(), chemicalDisplacement, materialDisplacement, thermalDisplacement);
		if (++activeCollisionEditIndex == activeCollisionPlan.edits.size()) { activeCollisionPlan = null; activeCollisionEditIndex = 0; }
		return displaced;
	}

	/**
	 * Minecraft collision edits win. Every active parcel whose exact 8-cell stencil touches the
	 * edited cell, plus every deposit group in that cell, is planned into the escaped sink before
	 * runtime geometry changes. Whole entities are used so reactive composition ownership is never guessed or split.
	 */
	void displaceGranularForCollisionCell(int cell) {
		boolean[] cells = new boolean[cellCount];
		cells[cell] = true;
		GranularCollisionPlan plan = planGranularCollision(cells);
		if (plan == null) throw new IllegalStateException("Granular collision occupancy has no removable authoritative owner");
		commitGranularCollision(plan);
	}

	GranularCollisionPlan planGranularCollision(boolean[] affectedCells) {
		domain().exportGranularState();
		granularCoupling.rebuild(granularState.parcels, granularState.deposits);
		boolean[] selectedParcels = new boolean[granularState.parcels.count()];
		int selectedCount = 0;
		for (int parcel = 0; parcel < granularState.parcels.count(); parcel++) {
			GranularParcelStore.Parcel state = granularState.parcels.parcel(parcel);
			for (int cell = 0; cell < affectedCells.length; cell++) {
				if (!affectedCells[cell] || granularCoupling.parcelShareAtCell(
						parcelPosition(state), state.solidVolumeSvq(), cell) <= 0L) continue;
				selectedParcels[parcel] = true;
				selectedCount++;
				break;
			}
		}
		boolean[] selectedDepositCells = new boolean[cellCount];
		boolean anyDeposit = false;
		for (int cell = 0; cell < cellCount; cell++) if (affectedCells[cell]
				&& granularState.deposits.cellSolidVolumeSvq(cell) > 0L) {
			selectedDepositCells[cell] = true;
			anyDeposit = true;
		}
		if (selectedCount == 0 && !anyDeposit) return null;
		GranularAuditLedger removed = GranularAuditLedger.captureSelected(
				granularState.registry, granularState.parcels, parcel -> selectedParcels[parcel],
				granularState.deposits, selectedCell -> selectedDepositCells[selectedCell], granularState.compositions);
		GranularAuditLedger sinkAfter = granularState.escapedSink.plus(removed);
		ArrayList<CollisionDeposit> depositGroups = new ArrayList<>();
		if (anyDeposit) granularState.deposits.forEachGroup((groupCell, porosity, material, composition, svq, mass) -> {
			if (selectedDepositCells[groupCell]) depositGroups.add(new CollisionDeposit(groupCell,
					new GranularDepositStore.Group(material, composition, svq, mass)));
		});
		if (thermalState != null) {
			for (int parcel = 0; parcel < selectedParcels.length; parcel++) if (selectedParcels[parcel]) {
				GranularParcelStore.Parcel state = granularState.parcels.parcel(parcel);
				thermalState.previewActiveTake(parcel, state.massUnits(), state.massUnits());
			}
			for (CollisionDeposit deposit : depositGroups) {
				GranularDepositStore.Group group = deposit.group();
				thermalState.previewDepositTake(new GranularEnergyColumn.DepositKey(
						deposit.cell(), group.materialHandle(), group.solidCompositionHandle()),
						group.massUnits(), group.massUnits());
			}
		}

		long plannedSvq = 0L;
		long plannedMass = 0L;
		for (int parcel = 0; parcel < selectedParcels.length; parcel++) if (selectedParcels[parcel]) {
			GranularParcelStore.Parcel state = granularState.parcels.parcel(parcel);
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
				affectedCells.clone(), granularState.parcels.count(), granularState.inventory.totalSolidVolumeSvq(),
				granularState.inventory.totalMassUnits());
	}

	void commitGranularCollision(GranularCollisionPlan plan) {
		if (granularState.parcels.count() != plan.parcelCount()
				|| granularState.inventory.totalSolidVolumeSvq() != plan.totalSvq()
				|| granularState.inventory.totalMassUnits() != plan.totalMassUnits()) {
			throw new IllegalStateException("Granular inventory changed after collision planning");
		}
		for (int parcel = plan.selectedParcels().length - 1; parcel >= 0; parcel--) if (plan.selectedParcels()[parcel]) {
			GranularParcelStore.Parcel state = granularState.parcels.removeSwap(parcel);
			if (thermalState != null) thermalState.commitExternalActiveTake(
					parcel, state.massUnits(), state.massUnits());
			if (state.solidCompositionHandle() != 0) granularState.compositions.release(state.solidCompositionHandle());
		}
		for (CollisionDeposit deposit : plan.depositGroups()) {
			GranularDepositStore.Group group = deposit.group();
			GranularDepositStore.SolidPortion taken = granularState.deposits.take(
					deposit.cell(), group.materialHandle(), group.solidCompositionHandle(), group.solidVolumeSvq());
			if (thermalState != null) thermalState.commitExternalDepositTake(new GranularEnergyColumn.DepositKey(
					deposit.cell(), group.materialHandle(), group.solidCompositionHandle()),
					group.massUnits(), group.massUnits());
			if (taken.solidCompositionHandle() != 0) granularState.compositions.release(taken.solidCompositionHandle());
		}
		granularState.escapedSink = plan.sinkAfter();
		granularCoupling.rebuild(granularState.parcels, granularState.deposits);
		pressureGranularCoupling = domain().hasGranularInventory() ? granularCoupling : null;
		for (int cell = 0; cell < plan.affectedCells().length; cell++) {
			if (plan.affectedCells()[cell]) domain().markGranularExternalChange(cell);
		}
	}

	public boolean isSolid(int x, int y, int z) { return geometry.solid[cellIndexChecked(x, y, z)]; }
	public int solidMicroMask(int x, int y, int z) { return geometry.cutCells.mask(cellIndexChecked(x, y, z)); }
	public float solidVolumeFraction(int x, int y, int z) {
		return 1.0F - geometry.cutCells.openCellVolume(cellIndexChecked(x, y, z));
	}
	public float openFaceFractionX(int x, int y, int z) { return geometry.cutCells.xFaceOpen(x, y, z); }
	public float openFaceFractionY(int x, int y, int z) { return geometry.cutCells.yFaceOpen(x, y, z); }
	public float openFaceFractionZ(int x, int y, int z) { return geometry.cutCells.zFaceOpen(x, y, z); }

	public void setVolume(int x, int y, int z, float value) {
		transferPlanner.replaceCellVolumes(new int[]{cellIndexChecked(x,y,z)},new float[]{value},null);
	}

	/** Replaces all cell liquid targets in one inventory, momentum, thermal and gas transaction. */
	public void setVolumes(float[] cellVolumes) { setVolumes(cellVolumes,null); }

	/** Bulk material initialization uses the same quantity and energy transaction. */
	public void setVolumes(float[] cellVolumes,int[] materialHandles) {
		if (cellVolumes==null || cellVolumes.length!=cellCount || materialHandles!=null && materialHandles.length!=cellCount)
			throw new IllegalArgumentException("Cell volumes and materials must cover the entire grid");
		int[] cells=new int[cellCount];for(int cell=0;cell<cellCount;cell++) cells[cell]=cell;
		transferPlanner.replaceCellVolumes(cells,cellVolumes,materialHandles);
	}

	public float volume(int x, int y, int z) {
		ensureInventoryGrid();
		return volume[cellIndexChecked(x, y, z)];
	}

	public float pressure(int x, int y, int z) {
		int cell = cellIndexChecked(x, y, z);
		ensurePressureGeometry();
		return (float) pressureSystem.liquidPressureAt(cell, ~geometry.cutCells.mask(cell) & 255);
	}

	/** Package-private test accessor for the free-surface pressure jump {@code sigma * kappa} at a cell. */
	float surfaceTensionPressureAt(int x, int y, int z) {
		ensurePressureGeometry();
		return tension.surfaceTensionPressure[cellIndexChecked(x, y, z)];
	}

	public float totalVolume() { return metrics.totalVolume(); }

	/** Exact double sum of the float marker-volume truth, in stable marker ordinal order. */
	public double totalVolumeExact() { return metrics.totalVolumeExact(); }

	/** Total open cell volume across the domain; global capacity for exact bucket insertion. */
	public float totalOpenVolume() { return metrics.totalOpenVolume(); }

	public double totalOpenVolumeExact() { return metrics.totalOpenVolumeExact(); }

	double totalGeometricOpenVolumeExact() {
		double result = 0.0;
		for (int cell = 0; cell < cellCount; cell++) result += geometry.cutCells.openCellVolume(cell);
		return result;
	}

	/** Global complement of all authoritative condensed inventories, including displaced liquid. */
	double remainingCarrierVolumeExact() {
		long solid = Math.addExact(domain().totalGranularSolidVolumeSvq(), domain().totalCoherentSolidVolumeSvq());
		return totalGeometricOpenVolumeExact() - solid / (double) SolidVolume.SVQ_PER_CELL - totalVolumeExact();
	}

	boolean canAddCarrierVolume(double volume) {
		double remaining = remainingCarrierVolumeExact() - volume;
		return remaining > 0.0 || remaining == 0.0
				&& (!gasState.sealedDomainBoundary || gasState.network.sealedInventoryNanos() == 0L);
	}

	float effectiveOpenCellVolume(int cell) {
		float geometric = geometry.cutCells.openCellVolume(cell);
		return granularCoupling == null || granularCoupling.totalSolidSvq() == 0 ? geometric
				: granularCoupling.effectiveOpenCellVolume(cell, geometric);
	}

	/** Exact marker-mass inventory; reconstructed grid density is never used for this ledger. */
	public long totalMassUnits() { return metrics.totalMassUnits(); }
	public double totalMass() { return metrics.totalMass(); }

	/** Returns the material handle with the largest exact marker volume in the given cell. */
	public int dominantMaterialInCell(int x, int y, int z) { return metrics.dominantMaterialInCell(x, y, z); }

	/** Exact nearby marker volume for planning a partial container extraction; -1 selects all materials. */
	public double exactVolumeNear(int centerX, int centerY, int centerZ, int maxRadius, int materialHandle) { return metrics.exactVolumeNear(centerX, centerY, centerZ, maxRadius, materialHandle); }

	MaterialInventory materialInventory(int materialHandle) {
		double volume = 0, mass = 0, weightedY = 0;
		for (var stock : liquidCells.stocks()) if (stock.key().material() == materialHandle) {
			volume += stock.volumeFraction(); mass += unitScale.normalizedMass(stock.massUnits());
			weightedY += (stock.key().cell() / sizeX % sizeY + .5) * stock.volumeFraction();
		}
		return new MaterialInventory(volume, mass, volume == 0 ? Double.NaN : weightedY / volume);
	}

	int phaseCountInCell(int x, int y, int z) {
		ensureGrid();
		int result = 0;
		for (int entry = markerGrid.cellPhaseHead[cellIndexChecked(x, y, z)]; entry >= 0; entry = markerGrid.phaseEntryNext[entry]) result++;
		return result;
	}

	/** In-memory material assignment only; callers outside the fluid package cannot create world material state. */
	void setMaterialInCell(int x, int y, int z, FluidMaterialRegistry registry, int materialHandle) {
		int cell = cellIndexChecked(x, y, z);
		bindMaterialRegistry(registry);
		if (liquidCells.physicalStocksInCell(cell).isEmpty()) throw new IllegalArgumentException("Cannot assign material to empty cell");
		var keys = liquidCells.physicalStocksInCell(cell).stream().map(LiquidCellInventory.Stock::key).toList();
		for (var key : keys) assignStockMaterial(key, registry, materialHandle);
	}
	void setMarkerMaterial(int ordinal, FluidMaterialRegistry registry, int materialHandle) {
		bindMaterialRegistry(registry); assignStockMaterial(liquidCells.physicalStockAt(ordinal).key(), registry, materialHandle);
	}
	private void assignStockMaterial(LiquidCellInventory.Key key, FluidMaterialRegistry registry, int materialHandle) {
		if (chemicalParcels != null) throw new IllegalStateException("Material relabeling requires exact chemical transfer");
		var stock = liquidCells.stock(key);
		if (key.material() == materialHandle) return;
		long mass = unitScale.massUnitsForNormalized(stock.volumeFraction() * registry.material(materialHandle).referenceDensity());
		var nextKey = new LiquidCellInventory.Key(key.cell(), materialHandle, key.supportMask());
		var candidate = liquidCells.copy(); candidate.planEdits(List.of(new LiquidCellInventory.Edit(key, null))).commit();
		candidate.planAdditions(List.of(new LiquidCellInventory.Stock(nextKey, stock.volume(), mass, null, stock.internalEnergy(), stock.indicator()))).commit();
		var edits = new ArrayList<LiquidCellInventory.Edit>();
		edits.add(new LiquidCellInventory.Edit(key, null)); edits.add(new LiquidCellInventory.Edit(nextKey, candidate.stock(nextKey)));
		var plan = liquidCells.planEdits(edits); var momentum = cellMacStage.prepareExternalInventoryChange(plan);
		plan.commit(); mac.restoreMomentum(momentum); domain().markCellInventoryChanged();
	}

	public void bindMaterialRegistry(FluidMaterialRegistry registry) {
		if (registry == null) throw new IllegalArgumentException("Material registry must not be null");
		if (materialRegistry != null && materialRegistry != registry) {
			throw new IllegalArgumentException("A domain cannot mix runtime handles from different material registries");
		}
		if (materialRegistry != null) return;
		double defaultDensity = registry.material(0).referenceDensity();
		double tolerance = Math.max(1.0E-9, Math.abs(config.density()) * 1.0E-12);
		if (Math.abs(defaultDensity - config.density()) > tolerance) {
			throw new IllegalArgumentException("Material handle 0 must match the domain default density");
		}
		materialRegistry = registry;
	}

	void assignMarkerMaterial(int ordinal, FluidMaterialRegistry registry, int materialHandle) { setMarkerMaterial(ordinal, registry, materialHandle); }
	boolean hasNonDefaultMaterialState() { return materialRegistry != null; }

	/** Maximum marker speed used only as a scheduling observation; it is not a water-volume estimate. */
	public float maximumParticleSpeed() { return metrics.maximumParticleSpeed(); }

	public float maximumPressure() { return metrics.maximumPressure(); }

	public byte[] copyQuantizedVolume() { return carriers.copyQuantizedVolume(); }

	/** Binary mask: 1 where a cell is entirely solid. Not interchangeable with {@link #copyCollisionMicroMasks()}. */
	public byte[] copyBinarySolidMask() { return carriers.copyBinarySolidMask(); }

	/**
	 * The 2x2x2 cut-cell occupancy this domain actually resolves against. A particle state round-trips
	 * collision through this encoding, so handing it a binary mask silently drops every solid cell:
	 * the collision field is decoded as micro bits and nothing reads back as solid.
	 */
	public byte[] copyCollisionMicroMasks() { return carriers.copyCollisionMicroMasks(); }

	/** Actual pressure-phase occupancy, separate from the smooth P2G density. */
	public byte[] copyMarkerMask() { return carriers.copyMarkerMask(); }

	/** Read-only material rendering reconstruction; it contains no exact inventory and is not a wire format. */
	public FluidMaterialRenderSnapshot copyMaterialRenderSnapshot() { return carriers.copyMaterialRenderSnapshot(); }

	public VolumeDistribution volumeDistribution(float visibleThreshold) { return metrics.volumeDistribution(visibleThreshold); }

	public SolidDistribution solidDistribution() { return metrics.solidDistribution(); }

	public boolean containsFluidAt(float localX, float localY, float localZ) { return metrics.containsFluidAt(localX, localY, localZ); }
}
