package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.SolidVolume;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.granular.GranularAuditLedger;
import cc.ecisr.mmm.granular.GranularDepositStore;
import cc.ecisr.mmm.granular.GranularParcelStore;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.thermal.GranularEnergyColumn;

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

	public void setMarkerChemical(int marker, Map<String, Long> stableAmounts, boolean indicator) {
		if (marker < 0 || marker >= particles.count()) throw new IllegalArgumentException("Unknown marker " + marker);
		ChemicalMixtureRecord zeroResidual = ChemicalMixtureRecord.fromStableAmounts(
				domain().chemicalParcels().registry(), stableAmounts, 0.0);
		ChemicalMixtureRecord record = ChemicalMixtureRecord.fromStableAmounts(
				chemicalParcels.registry(), stableAmounts,
				unitScale.kilograms(particles.mass(marker))
						- zeroResidual.exactSpeciesMassKilograms(chemicalParcels.registry()).toDouble());
		chemicalParcels.requireCompatible(record,
				materialRegistry.material(particles.materialHandle(marker)).id(), indicator);
		if (thermalState != null) thermalState.requireReactionState(
				marker, record, particles.mass(marker), domain().thermalMarkerContext());
		chemicalParcels.assign(marker, record, indicator);
		chemicalParcels.markDirtyCell(particleCellIndex(marker));
	}

	/** Resolves a stable material id to the runtime handle of the bound registry. */
	public int materialHandle(String materialId) {
		if (materialRegistry == null) throw new IllegalStateException("Material registry is not bound");
		return materialRegistry.handle(materialId);
	}

	/** Replaces this domain from one validated, immutable in-memory marker state. */
	public void restoreParticleState(FlipParticleState state) {
		if (materialDefinitions != null) throw new IllegalStateException("Pure restore cannot remove bound liquid material identity");
		restoreParticleColumns(state);
	}

	private void restoreParticleColumns(FlipParticleState state) {
		if (state == null) throw new IllegalArgumentException("Particle state must not be null");
		state.requireCompatible(sizeX, sizeY, sizeZ, config.cellSize());
		int restoredCount = state.particleCount();
		if (restoredCount > maximumParticleCount) throw new IllegalArgumentException("Particle state exceeds domain marker budget");
		ensureParticleCapacity(restoredCount);
		float[] positionVolume = state.positionVolume();
		float[] particleVelocities = state.particleVelocities();
		byte[] collisionMasks = state.collisionMicroMasks();
		geometry.cutCells.restoreMicroMasks(collisionMasks);
		for (int i = 0; i < cellCount; i++) geometry.solid[i] = Byte.toUnsignedInt(collisionMasks[i]) == 0xff;
		for (int particle = 0; particle < restoredCount; particle++) {
			int positionBase = particle * 4, velocityBase = particle * 3;
			particles.setPositionX(particle, positionVolume[positionBase]);
			particles.setPositionY(particle, positionVolume[positionBase + 1]);
			particles.setPositionZ(particle, positionVolume[positionBase + 2]);
			particles.setVolume(particle, positionVolume[positionBase + 3]);
			particles.setVelocityX(particle, particleVelocities[velocityBase]);
			particles.setVelocityY(particle, particleVelocities[velocityBase + 1]);
			particles.setVelocityZ(particle, particleVelocities[velocityBase + 2]);
			setDefaultMaterialState(particle);
		}
		coordinator.adoptWholesaleCount(
				CarrierMutationPlan.wholesaleCount(carrierMutationRevision(), particles.count()), restoredCount);
		domain().clearThermalBinding();
		materialRegistry = null;
		chemicalParcels = null;
		chemicalPreviousCells = null;
		chemicalMovingMarkers = null;
		Arrays.fill(projection.pressure, 0.0F);
		Arrays.fill(projection.divergence, 0.0F);
		pressureAnchor = -1;
		lastPressureIterations = 0;
		lastPressureResidual = 0.0F;
		gridDirty = true;
	}

	/** Replaces this domain from a validated multi-material CPU handoff without changing world state. */
	public void restoreMaterialParticleState(FlipMaterialParticleState state) {
		if (state == null) throw new IllegalArgumentException("Material particle state must not be null");
		state.requireCompatible(sizeX, sizeY, sizeZ, config.cellSize());
		if (state.particleCount() > maximumParticleCount) {
			throw new IllegalArgumentException("Material particle state exceeds domain marker budget");
		}
		FluidMaterialRegistry registry = new FluidMaterialRegistry(state.materials());
		if (materialDefinitions != null) LiquidMaterialBinding.validate(domain(), registry, materialDefinitions);
		double defaultDensity = registry.material(0).referenceDensity();
		double tolerance = Math.max(1.0E-9, Math.abs(config.density()) * 1.0E-12);
		if (Math.abs(defaultDensity - config.density()) > tolerance) {
			throw new IllegalArgumentException("Material handle 0 must match the domain default density");
		}
		restoreParticleColumns(state.particles());
		double[] restoredMass = state.particleMass();
		int[] restoredMaterial = state.materialPaletteIndex();
		for (int particle = 0; particle < particles.count(); particle++) {
			particles.setMass(particle, restoredMass[particle]);
			particles.setMaterialHandle(particle, restoredMaterial[particle]);
			particles.setPhaseLabel(particle, registry.phaseHandle(restoredMaterial[particle]));
		}
		materialRegistry = registry;
		gridDirty = true;
	}

	/** Restores a validated chemical handoff against the authoritative world registry. */
	public void restoreChemicalParticleState(FlipChemicalParticleState state, ChemicalRegistry registry) {
		if (state == null || registry == null) throw new IllegalArgumentException("Chemical restore state/registry is missing");
		if (!state.registryFingerprint().equals(registry.fingerprint())
				|| !state.speciesPalette().equals(registry.species().stream()
				.map(cc.ecisr.mmm.chemistry.ChemicalSpecies::id).toList())) {
			throw new IllegalArgumentException("Chemical handoff registry identity mismatch");
		}
		restoreMaterialParticleState(state.materials());
		for (FluidMaterial material : materialRegistry.materials()) registry.phaseHandleForMaterial(material.id());
		ChemicalParcelStore restored = new ChemicalParcelStore(
				registry, unitScale, maximumParticleCount, cellCount);
		int[] tableIndex = state.copyMarkerMixtureIndex();
		byte[] indicator = state.copyIndicator();
		List<ChemicalMixtureRecord> runtimeMixtures = state.runtimeMixtures(registry);
		restored.pool().reserveRecords(particles.count());
		for (int marker = 0; marker < particles.count(); marker++) {
			ChemicalMixtureRecord mixture = runtimeMixtures.get(tableIndex[marker]);
			restored.requireCompatible(mixture, materialRegistry.material(particles.materialHandle(marker)).id(), indicator[marker] != 0);
			restored.assign(marker, mixture, indicator[marker] != 0);
		}
		if (!Arrays.equals(restored.speciesTotals(particles.count()), state.copySpeciesTotals())) {
			throw new IllegalArgumentException("Restored chemical species ledger mismatch");
		}
		chemicalParcels = restored;
		chemicalParcels.restoreAuditLedgers(state.externalSource(), state.escapedSink());
		// A cold checkpoint restore has no trusted scheduler cursor. Re-evaluate every occupied
		// cell once; an in-memory backend handoff immediately replaces this with its exact dirty set.
		for (int marker = 0; marker < particles.count(); marker++) {
			chemicalParcels.markDirtyCell(particleCellIndex(marker));
		}
		chemicalPreviousCells = new int[maximumParticleCount];
		chemicalMovingMarkers = new int[maximumParticleCount];
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
		return new CollisionEditPlan((FluidDomain) this, List.copyOf(canonical), previousMasks, particles.count(),
				Double.doubleToRawLongBits(totalVolumeExact()), Double.doubleToRawLongBits(totalMass()), granularPlan);
	}

	/** Commits the already validated cross-owner prelude; the supplied masks may then be applied without refusal. */
	public void beginCollisionEdits(CollisionEditPlan plan) {
		if (plan == null || plan.owner != this || plan.consumed || activeCollisionPlan != null) {
			throw new IllegalArgumentException("Collision edit plan is missing, foreign or already consumed");
		}
		if (particles.count() != plan.planMarkerCount
				|| Double.doubleToRawLongBits(totalVolumeExact()) != plan.totalVolumeBits
				|| Double.doubleToRawLongBits(totalMass()) != plan.totalMassBits) {
			throw new IllegalStateException("Liquid inventory changed after collision planning");
		}
		for (int index = 0; index < plan.edits.size(); index++) {
			CollisionEdit edit = plan.edits.get(index);
			if (solidMicroMask(edit.x(), edit.y(), edit.z()) != plan.previousMasks[index]) {
				throw new IllegalStateException("Collision geometry changed after planning");
			}
		}
		if (plan.granular != null) try {
			commitGranularCollision(plan.granular);
		} catch (RuntimeException failure) {
			throw new CollisionEditBlockedException("Granular collision displacement could not be committed", failure);
		}
		plan.consumed = true;
		activeCollisionPlan = plan;
		activeCollisionEditIndex = 0;
	}

	float setSolidMicroMaskInternal(
			int x, int y, int z, int mask, ChemicalTransferLedger.Builder chemicalDisplacement,
			RemovedMaterialLedger materialDisplacement, RemovedThermalEnergy thermalDisplacement
	) {
		int index = cellIndexChecked(x, y, z);
		boolean plannedBatch = activeCollisionPlan != null;
		if (plannedBatch) {
			CollisionEdit expected = activeCollisionPlan.edits.get(activeCollisionEditIndex);
			if (expected.x() != x || expected.y() != y || expected.z() != z || expected.mask() != mask) {
				throw new IllegalStateException("Collision edits differ from their committed batch order");
			}
		}
		int previous = geometry.cutCells.mask(index);
		if (previous == mask) return 0.0F;
		int newlyOccupied = mask & ~previous;
		if (newlyOccupied != 0 && granularCoupling != null && !plannedBatch) {
			ensureGrid();
			if (thermalState != null && thermalState.coherentPhases().cellOccupiedSvq(index) > 0L) {
				throw new IllegalStateException("Collision edit intersects authoritative coherent phase inventory");
			}
			if (granularState != null
					&& (granularCoupling.solidSvq(index) > 0L || granularState.deposits.cellSolidVolumeSvq(index) > 0L)) {
				try { displaceGranularForCollisionCell(index); }
				catch (RuntimeException failure) {
					throw new CollisionEditBlockedException(
							"Granular collision displacement could not be committed", failure);
				}
			}
		}
		geometry.cutCells.replaceMask(index, mask);
		geometry.solid[index] = mask == FlipCutCellGeometry.FULL_MASK;
		projection.pressure[index] = 0.0F;
		float displaced = newlyOccupied == 0 ? 0.0F
				: removeParticlesInMicroMask(x, y, z, newlyOccupied, chemicalDisplacement,
						materialDisplacement, thermalDisplacement);
		float excess = Math.max(0.0F, exactVolumeInCell(x, y, z) - effectiveOpenCellVolume(index));
		if (excess > 1.0E-6F) displaced += removeParticlesInCell(x, y, z, excess, chemicalDisplacement,
				materialDisplacement, thermalDisplacement);
		volume[index] = 0.0F;
		if (granularDepositDynamics != null) granularDepositDynamics.disturbCell(index);
		gridDirty = true;
		if (plannedBatch && ++activeCollisionEditIndex == activeCollisionPlan.edits.size()) {
			activeCollisionPlan = null;
			activeCollisionEditIndex = 0;
		}
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
				thermalState.previewActiveTake(parcel, state.solidVolumeSvq(), state.solidVolumeSvq());
			}
			for (CollisionDeposit deposit : depositGroups) {
				GranularDepositStore.Group group = deposit.group();
				thermalState.previewDepositTake(new GranularEnergyColumn.DepositKey(
						deposit.cell(), group.materialHandle(), group.solidCompositionHandle()),
						group.solidVolumeSvq(), group.solidVolumeSvq());
			}
		}

		long plannedSvq = 0L;
		double plannedMass = 0.0;
		for (int parcel = 0; parcel < selectedParcels.length; parcel++) if (selectedParcels[parcel]) {
			GranularParcelStore.Parcel state = granularState.parcels.parcel(parcel);
			plannedSvq = Math.addExact(plannedSvq, state.solidVolumeSvq());
			plannedMass += state.mass();
		}
		for (CollisionDeposit deposit : depositGroups) {
			plannedSvq = Math.addExact(plannedSvq, deposit.group().solidVolumeSvq());
			plannedMass += deposit.group().mass();
		}
		long auditSvq = 0L;
		double auditMass = 0.0;
		for (GranularAuditLedger.Entry entry : removed.entries()) {
			auditSvq = Math.addExact(auditSvq, entry.solidVolumeSvq());
			auditMass += entry.mass();
		}
		if (auditSvq != plannedSvq || !massClose(auditMass, plannedMass)) {
			throw new IllegalStateException("Granular collision plan differs from its preflight audit");
		}
		return new GranularCollisionPlan(selectedParcels, List.copyOf(depositGroups), sinkAfter,
				affectedCells.clone(), granularState.parcels.count(), granularState.inventory.totalSolidVolumeSvq(),
				Double.doubleToRawLongBits(granularState.inventory.totalMass()));
	}

	void commitGranularCollision(GranularCollisionPlan plan) {
		if (granularState.parcels.count() != plan.parcelCount()
				|| granularState.inventory.totalSolidVolumeSvq() != plan.totalSvq()
				|| Double.doubleToRawLongBits(granularState.inventory.totalMass()) != plan.totalMassBits()) {
			throw new IllegalStateException("Granular inventory changed after collision planning");
		}
		for (int parcel = plan.selectedParcels().length - 1; parcel >= 0; parcel--) if (plan.selectedParcels()[parcel]) {
			GranularParcelStore.Parcel state = granularState.parcels.removeSwap(parcel);
			if (thermalState != null) thermalState.commitExternalActiveTake(
					parcel, state.solidVolumeSvq(), state.solidVolumeSvq());
			if (state.solidCompositionHandle() != 0) granularState.compositions.release(state.solidCompositionHandle());
		}
		for (CollisionDeposit deposit : plan.depositGroups()) {
			GranularDepositStore.Group group = deposit.group();
			GranularDepositStore.SolidPortion taken = granularState.deposits.take(
					deposit.cell(), group.materialHandle(), group.solidCompositionHandle(), group.solidVolumeSvq());
			if (thermalState != null) thermalState.commitExternalDepositTake(new GranularEnergyColumn.DepositKey(
					deposit.cell(), group.materialHandle(), group.solidCompositionHandle()),
					group.solidVolumeSvq(), group.solidVolumeSvq());
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
		if (granularCoupling != null) ensureGrid();
		int index = cellIndexChecked(x, y, z);
		float requested = clamp01(value);
		if (geometry.solid[index] && requested > 0.0F) throw new IllegalStateException("Cannot place fluid in a solid cell");
		float clamped = Math.min(requested, effectiveOpenCellVolume(index));
		int existingParticles = particleCountInCell(x, y, z);
		int requiredParticles = particlesRequired(clamped);
		if ((long) particles.count() - existingParticles + requiredParticles > maximumParticleCount) {
			throw new ParticleBudgetExceededException(requiredParticles, maximumParticleCount - particles.count() + existingParticles);
		}
		removeParticlesInCell(x, y, z, Float.POSITIVE_INFINITY);
		spawnCellVolume(x, y, z, clamped);
		gridDirty = true;
	}

	public float volume(int x, int y, int z) {
		ensureGrid();
		return volume[cellIndexChecked(x, y, z)];
	}

	public float pressure(int x, int y, int z) {
		int cell = cellIndexChecked(x, y, z);
		return isFluid(cell) ? pressureReference + projection.pressure[cell] : projection.pressure[cell];
	}

	/** Package-private test accessor for the free-surface pressure jump {@code sigma * kappa} at a cell. */
	float surfaceTensionPressureAt(int x, int y, int z) { return tension.surfaceTensionPressure[cellIndexChecked(x, y, z)]; }

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
		return granularCoupling == null ? geometric : granularCoupling.effectiveOpenCellVolume(cell, geometric);
	}

	/** Exact marker-mass inventory; reconstructed grid density is never used for this ledger. */
	public double totalMass() { return metrics.totalMass(); }

	/** Returns the material handle with the largest exact marker volume in the given cell. */
	public int dominantMaterialInCell(int x, int y, int z) { return metrics.dominantMaterialInCell(x, y, z); }

	/** Exact nearby marker volume for planning a partial container extraction; -1 selects all materials. */
	public double exactVolumeNear(int centerX, int centerY, int centerZ, int maxRadius, int materialHandle) { return metrics.exactVolumeNear(centerX, centerY, centerZ, maxRadius, materialHandle); }

	MaterialInventory materialInventory(int materialHandle) {
		double volume = 0.0, mass = 0.0, weightedY = 0.0;
		for (int particle = 0; particle < particles.count(); particle++) {
			if (particles.materialHandle(particle) != materialHandle) continue;
			volume += particles.volume(particle);
			mass += particles.mass(particle);
			weightedY += particles.positionY(particle) / config.cellSize() * particles.volume(particle);
		}
		return new MaterialInventory(volume, mass, volume == 0.0 ? Double.NaN : weightedY / volume);
	}

	int phaseCountInCell(int x, int y, int z) {
		ensureGrid();
		int result = 0;
		for (int entry = markerGrid.cellPhaseHead[cellIndexChecked(x, y, z)]; entry >= 0; entry = markerGrid.phaseEntryNext[entry]) result++;
		return result;
	}

	/** In-memory material assignment only; callers outside the fluid package cannot create world material state. */
	void setMaterialInCell(int x, int y, int z, FluidMaterialRegistry registry, int materialHandle) {
		if (registry == null) throw new IllegalArgumentException("Material registry must not be null");
		registry.material(materialHandle);
		int cell = cellIndexChecked(x, y, z);
		boolean found = false;
		for (int particle = 0; particle < particles.count(); particle++) {
			if (particleCellIndex(particle) == cell) found = true;
		}
		if (!found) throw new IllegalArgumentException("Cannot assign material to an empty pressure cell");
		bindMaterialRegistry(registry);
		for (int particle = 0; particle < particles.count(); particle++) {
			if (particleCellIndex(particle) == cell) assignMarkerMaterial(particle, registry, materialHandle);
		}
		gridDirty = true;
	}

	/** Marker-granular package test injection; indices are intentionally not exposed as a public state contract. */
	void setMarkerMaterial(int particle, FluidMaterialRegistry registry, int materialHandle) {
		if (particle < 0 || particle >= particles.count()) throw new IllegalArgumentException("Unknown marker index " + particle);
		if (registry == null) throw new IllegalArgumentException("Material registry must not be null");
		registry.material(materialHandle);
		bindMaterialRegistry(registry);
		assignMarkerMaterial(particle, registry, materialHandle);
		gridDirty = true;
	}

	void bindMaterialRegistry(FluidMaterialRegistry registry) {
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

	void assignMarkerMaterial(int particle, FluidMaterialRegistry registry, int materialHandle) {
		if (chemicalParcels != null) {
			throw new IllegalStateException("Material relabeling requires an exact chemical transfer");
		}
		double previousTemperature = thermalState == null ? Double.NaN : domain().thermalMarkerTemperature(particle);
		SignedDyadic thermalBefore = thermalState == null ? SignedDyadic.ZERO : domain().exactInternalEnergy();
		FluidMaterial material = registry.material(materialHandle);
		double mass = (double) particles.volume(particle) * material.referenceDensity();
		if (!(mass <= Float.MAX_VALUE)) throw new IllegalArgumentException("Material mass exceeds P2G range");
		particles.setMass(particle, mass);
		particles.setMaterialHandle(particle, materialHandle);
		particles.setPhaseLabel(particle, registry.phaseHandle(materialHandle));
		if (thermalState != null) thermalState.resetMarkerTemperature(
				particle, previousTemperature, domain().thermalMarkerContext(), thermalBefore);
	}

	boolean hasNonDefaultMaterialState() {
		if (materialRegistry != null) return true;
		for (int particle = 0; particle < particles.count(); particle++) {
			if (particles.materialHandle(particle) != 0 || particles.phaseLabel(particle) != 0) return true;
			float referenceMass = particles.volume(particle) * config.density();
			float tolerance = Math.max(1.0E-5F, Math.ulp(referenceMass) * 8.0F);
			if (Math.abs(particles.mass(particle) - referenceMass) > tolerance) return true;
		}
		return false;
	}

	boolean usesMassWeightedP2G() {
		for (int particle = 0; particle < particles.count(); particle++) {
			double referenceMass = (double) particles.volume(particle) * config.density();
			double tolerance = Math.max(1.0E-9, Math.abs(referenceMass) * 1.0E-12);
			if (Math.abs(particles.mass(particle) - referenceMass) > tolerance) return true;
		}
		return false;
	}

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
