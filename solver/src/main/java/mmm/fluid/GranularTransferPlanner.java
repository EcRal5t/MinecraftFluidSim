package mmm.fluid;

import mmm.granular.GranularAuditLedger;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositStore;
import mmm.granular.GranularParcelStore;
import mmm.granular.GranularKernel;
import mmm.granular.GranularIntegerAllocation;
import mmm.granular.GranularTransferLedger;
import mmm.granular.SolidCompositionRecord;
import mmm.granular.SolidVolume;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalRegistry;
import mmm.thermal.GranularEnergyColumn;
import mmm.thermal.ThermalCarrierContext;
import mmm.thermal.ThermalAuditLedger;
import mmm.units.SignedDyadic;
import mmm.units.ProportionalDoubleSplit;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Exact dry-container transfers with prepared collision, integer occupancy, gas and energy successors. */
final class GranularTransferPlanner {
	private final FluidDomain domain;
	private final GranularKernel insertionKernel;

	GranularTransferPlanner(FluidDomain domain) {
		this.domain = domain;
		insertionKernel = new GranularKernel(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
	}

	/** Inventory-only exact insertion used by fixtures and transactional service adapters. */
	FluidDomain.GranularInsertion insertExactInventory(
			int cellX, int cellY, int cellZ, int materialHandle, int compositionHandle,
			long solidVolumeSvq, long massUnits
	) {
		GranularDomainState granular = domain.granularState;
		if (granular == null) return FluidDomain.GranularInsertion.rejected("Granular state is not bound");
		int cell = domain.domainFlowFields.cellIndexChecked(cellX, cellY, cellZ);
		if (solidVolumeSvq <= 0L || massUnits <= 0L) {
			throw new IllegalArgumentException("Granular insertion inventory is invalid");
		}
		try {
			Math.addExact(granular.inventory.totalMassUnits(), massUnits);
		} catch (ArithmeticException overflow) {
			return FluidDomain.GranularInsertion.rejected("Granular mass capacity exceeded");
		}
		granular.registry.material(materialHandle);
		if (compositionHandle != 0 && (granular.compositions == null || !granular.compositions.isLive(compositionHandle))) {
			throw new IllegalArgumentException("Granular insertion has no live solid composition");
		}
		domain.domainFlowFields.preparePhysicalState();
		domain.granularCoupling.rebuild(granular.parcels, granular.deposits);
		long[] previousSolid = domain.entityBodyCoupling.addTo(domain.granularCoupling.copySolidSvq());
		long[] plannedSolid = previousSolid.clone();
		var admission = new GranularSpawnAdmission(domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.config.cellSize(), granular.parcels, 1);
		GranularCouplingFields.Position position = null;
		for (int attempt = 0; attempt < 16 && position == null; attempt++) {
			GranularCouplingFields.Position candidate = domain.granularCollision.planSpawnPosition(
					cellX, cellY, cellZ, solidVolumeSvq, granular.parcels.count() + attempt * 16_384L,
					Float.NaN, Float.NaN, Float.NaN, point -> admission.allows(point.x(), point.y(), point.z(), solidVolumeSvq)
							? planInsertionPosition(point, solidVolumeSvq, plannedSolid) : null);
			if (candidate != null) position = candidate;
		}
		if (position == null) return FluidDomain.GranularInsertion.rejected("Target cell has no collision-valid solid capacity");
		if (!domain.domainCollision.canAddCarrierVolume(solidVolumeSvq / (double) SolidVolume.SVQ_PER_CELL)) {
			return FluidDomain.GranularInsertion.rejected("Sealed region has no combined fluid/solid capacity");
		}
		GranularParcelStore.Parcel parcel = new GranularParcelStore.Parcel(
				position.x(), position.y(), position.z(), 0.0F, 0.0F, 0.0F,
				solidVolumeSvq, massUnits, materialHandle, compositionHandle, 0, (byte) 0, position.ceilMask());
		if (!granular.parcels.canAdd(parcel)) return FluidDomain.GranularInsertion.rejected("Active granular parcel budget exceeded");
		double thermalEnergy = domain.thermalState == null ? 0.0 : domain.thermalState.planExternalGranularEnergy(
				materialHandle, compositionHandle, mmm.units.Mass.kilograms(massUnits), domain.domainCapabilities.thermalMarkerContext());
		GranularLiquidDisplacement.Prepared displacement;
		try { displacement = GranularLiquidDisplacement.prepareInsertion(domain, previousSolid, plannedSolid); }
		catch (GranularLiquidDisplacement.CapacityUnavailable unavailable) {
			return FluidDomain.GranularInsertion.rejected(unavailable.getMessage());
		}
		var gas = displacement == null ? prepareInsertionGas(plannedSolid)
				: displacement.gasTopology;
		requireInsertionSuccessor(gas, domain.thermalState == null ? null : new double[]{thermalEnergy}, displacement);
		Runnable publishSolid = () -> {
			granular.parcels.add(parcel);
			if (domain.thermalState != null) domain.thermalState.commitExternalActiveInsertions(new double[]{thermalEnergy});
			domain.domainPhaseInventory.markGranularExternalChange(cell);
		};
		if (displacement != null) GranularLiquidDisplacement.publish(domain, displacement, publishSolid);
		else { publishSolid.run(); if (gas != null) domain.gasTopologyCoordinator.commit(gas); }

		return new FluidDomain.GranularInsertion(true, solidVolumeSvq, massUnits, null);
	}

	/** All-or-nothing dry-container insertion. The ledger is already stable-ID validated by the item adapter. */
	FluidDomain.GranularInsertion insertExact(
			int cellX, int cellY, int cellZ, GranularTransferLedger ledger, int maxRadius, boolean externalSource
	) {
		return insertExact(cellX, cellY, cellZ, ledger, maxRadius, externalSource, null);
	}

	FluidDomain.GranularInsertion insertExact(
			int cellX, int cellY, int cellZ, GranularTransferLedger ledger, int maxRadius, boolean externalSource,
			Double carriedEnergy
	) {
		if (carriedEnergy != null && (domain.thermalState == null || !Double.isFinite(carriedEnergy)))
			throw new IllegalArgumentException("Carried granular energy requires thermal state");
		GranularDomainState granular = domain.granularState;
		if (granular == null) return FluidDomain.GranularInsertion.rejected("Granular state is not bound");
		if (ledger == null) throw new IllegalArgumentException("Granular insertion ledger is missing");
		if (maxRadius < 0) throw new IllegalArgumentException("Granular insertion radius is invalid");
		domain.domainFlowFields.cellIndexChecked(cellX, cellY, cellZ); domain.domainFlowFields.preparePhysicalState();
		domain.granularCoupling.rebuild(granular.parcels, granular.deposits);
		long insertedSvq = ledger.totalSolidVolumeSvq(); long insertedMass = ledger.totalMassUnits();
		try {
			Math.addExact(granular.inventory.totalMassUnits(), insertedMass);
		} catch (ArithmeticException overflow) {
			return FluidDomain.GranularInsertion.rejected("Granular mass capacity exceeded");
		}
		if (!domain.domainCollision.canAddCarrierVolume(insertedSvq / (double) SolidVolume.SVQ_PER_CELL)) {
			return FluidDomain.GranularInsertion.rejected("Sealed region has no combined fluid/solid capacity");
		}
		ArrayList<GranularParcelDraft> drafts = new ArrayList<>(); int reactiveDrafts = 0;
		for (GranularTransferLedger.Group group : ledger.groups()) {
			int materialHandle = granular.registry.materialHandle(group.materialId());
			var material = granular.registry.material(materialHandle);
			if (material.reactive() != (group.composition() != null)) {
				throw new IllegalArgumentException("Granular transfer material/composition kind mismatch");
			}
			if (group.composition() != null) {
				if (granular.compositions == null || group.composition().massUnits(granular.compositions.registry()) != group.massUnits()) {
					throw new IllegalArgumentException("Granular transfer composition/massUnits mismatch");
				}
			}
			long remainingSvq = group.solidVolumeSvq(); long remainingMass = group.massUnits();
			while (remainingSvq > 0L) {
				long pieceSvq = Math.min(remainingSvq, material.maximumActiveParcelSvq());
				long pieceMass = pieceSvq == remainingSvq ? remainingMass
						: mmm.units.Mass.portion(group.massUnits(), pieceSvq, group.solidVolumeSvq());
				if (!(pieceMass > 0L && pieceMass <= remainingMass)) {
					return FluidDomain.GranularInsertion.rejected("Granular transfer massUnits split is not representable");
				}
				SolidCompositionRecord pieceComposition = group.composition() == null ? null
						: splitSolidComposition(group.composition(), group.solidVolumeSvq(), pieceSvq, pieceMass);
				drafts.add(new GranularParcelDraft(materialHandle, pieceSvq, pieceMass, pieceComposition));
				if (pieceComposition != null) reactiveDrafts++;
				remainingSvq -= pieceSvq; remainingMass -= pieceMass;
			}
		}
		if (!granular.parcels.hasCapacity(drafts.size())
				|| reactiveDrafts > 0 && !granular.compositions.hasCapacity(reactiveDrafts)) {
			return FluidDomain.GranularInsertion.rejected("Active granular parcel/composition budget exceeded");
		}
		List<FluidDomain.CellCandidate> insertionCells = domain.domainFlowFields.nearbyCells(cellX, cellY, cellZ, maxRadius);
		// A dry pour is a loose release envelope, not a vertical stack of mutually blocking
		// numerical parcels. Prefer nearby horizontal cells; vertical cells remain bounded
		// fallbacks when the port neighborhood or exact occupancy requires them.
		insertionCells.sort(Comparator
				.comparingInt((FluidDomain.CellCandidate candidate) -> insertionDistance(candidate, cellY))
				.thenComparingInt(FluidDomain.CellCandidate::distanceSquared)
				.thenComparingInt(FluidDomain.CellCandidate::index));
		int releaseCellCount = releaseCellCount(insertionCells, ledger);
		long[] previousSolid = domain.entityBodyCoupling.addTo(domain.granularCoupling.copySolidSvq());
		long[] plannedSolid = previousSolid.clone();
		var admission = new GranularSpawnAdmission(domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.config.cellSize(), granular.parcels, drafts.size());
		int[] plannedParcelCounts = new int[domain.cellCount];
		for (int parcel = 0; parcel < granular.parcels.count(); parcel++) {
			plannedParcelCounts[domain.domainStep.granularParcelCell(granular.parcels.parcel(parcel))]++;
		}
		ArrayList<GranularParcelDraft> positioned = new ArrayList<>(drafts.size());
		for (GranularParcelDraft draft : drafts) {
			int selected = -1; GranularCouplingFields.Position selectedPosition = null;
			boolean[] attemptedReleaseCells = new boolean[releaseCellCount];
			for (int candidateAttempt = 0; candidateAttempt < insertionCells.size(); candidateAttempt++) {
				int candidateOrdinal;
				if (candidateAttempt < releaseCellCount) {
					candidateOrdinal = -1;
					for (int ordinal = 0; ordinal < releaseCellCount; ordinal++) {
						if (!attemptedReleaseCells[ordinal] && (candidateOrdinal < 0
								|| plannedParcelCounts[insertionCells.get(ordinal).index()]
								< plannedParcelCounts[insertionCells.get(candidateOrdinal).index()])) {
							candidateOrdinal = ordinal;
						}
					}
					attemptedReleaseCells[candidateOrdinal] = true;
				} else {
					candidateOrdinal = candidateAttempt;
				}
				FluidDomain.CellCandidate candidate = insertionCells.get(candidateOrdinal);
				int candidateX = candidate.index() % domain.sizeX, candidateYz = candidate.index() / domain.sizeX;
				int candidateY = candidateYz % domain.sizeY, candidateZ = candidateYz / domain.sizeY;
				for (int attempt = 0; attempt < 16; attempt++) {
					long sequence = plannedParcelCounts[candidate.index()] + attempt * 16_384L;
					GranularCouplingFields.Position spawn = domain.granularCollision.planSpawnPosition(
							candidateX, candidateY, candidateZ, draft.solidVolumeSvq(), sequence,
							Float.NaN, Float.NaN, Float.NaN, point ->
									admission.allows(point.x(), point.y(), point.z(), draft.solidVolumeSvq())
									? planInsertionPosition(point, draft.solidVolumeSvq(), plannedSolid) : null);
					if (spawn != null) {
						selected = candidate.index(); selectedPosition = spawn; break;
					}
				}
				if (selected >= 0) break;
			}
			if (selected < 0) return FluidDomain.GranularInsertion.rejected("Nearby cells have no collision-valid solid capacity");
			plannedParcelCounts[selected]++;
			admission.append(selectedPosition.x(), selectedPosition.y(), selectedPosition.z(), draft.solidVolumeSvq());
			positioned.add(draft.at(selected, selectedPosition));
		}
		drafts = positioned;
		GranularAuditLedger sourceAfter = externalSource ? granular.externalSource.plus(
				ledger, granular.registry, granular.compositions == null ? null : granular.compositions.registry()) : granular.externalSource;
		int originalCount = granular.parcels.count();
		// Planning must not touch the live parcel store: a failed candidate would still advance
		// the parcel mutation revision and invalidate unrelated in-flight plans. Compositions are
		// reserved first (their pool has no revision, so create/release rollback is invisible),
		// the thermal insertion plan runs against a draft overlay of the carrier context, and the
		// parcel store only mutates in the rejection-free commit phase below.
		int[] compositionByDraft = new int[drafts.size()];
		ArrayList<Integer> createdCompositions = new ArrayList<>(reactiveDrafts);
		double[] thermalInsertions = null;
		GasTopologyCoordinator.GasTopologyPlan gas;
		GranularLiquidDisplacement.Prepared displacement;
		try {
			for (int index = 0; index < drafts.size(); index++) {
				GranularParcelDraft draft = drafts.get(index);
				if (draft.composition() != null) {
					compositionByDraft[index] = granular.compositions.create(draft.composition());
					createdCompositions.add(compositionByDraft[index]);
				}
			}
			if (domain.thermalState != null) {
				var context = new DraftParcelContext(domain.domainCapabilities.thermalMarkerContext(), drafts, compositionByDraft);
				thermalInsertions = carriedEnergy == null
						? domain.thermalState.planExternalActiveInsertions(originalCount, drafts.size(), context)
						: domain.thermalState.planExternalActiveInsertions(originalCount, drafts.size(), context, carriedEnergy);
			}
			displacement = GranularLiquidDisplacement.prepareInsertion(domain, previousSolid, plannedSolid);
			gas = displacement == null ? prepareInsertionGas(plannedSolid) : displacement.gasTopology;
			requireInsertionSuccessor(gas, thermalInsertions, displacement);
		} catch (RuntimeException failure) {
			for (int index = createdCompositions.size() - 1; index >= 0; index--) {
				int handle = createdCompositions.get(index);
				if (granular.compositions.isLive(handle)) granular.compositions.release(handle);
			}
			if (failure instanceof GranularLiquidDisplacement.CapacityUnavailable)
				return FluidDomain.GranularInsertion.rejected(failure.getMessage());
			throw failure;
		}
		var preparedDrafts = drafts; var preparedEnergies = thermalInsertions;
		Runnable publishSolid = () -> {
			for (int index = 0; index < preparedDrafts.size(); index++) {
				GranularParcelDraft draft = preparedDrafts.get(index);
				granular.parcels.add(new GranularParcelStore.Parcel(
						draft.x(), draft.y(), draft.z(), 0.0F, 0.0F, 0.0F,
						draft.solidVolumeSvq(), draft.massUnits(), draft.materialHandle(), compositionByDraft[index], 0, (byte) 0, draft.ceilMask()));
			}
			if (domain.thermalState != null) {
				if (carriedEnergy == null) domain.thermalState.commitExternalActiveInsertions(preparedEnergies);
				else domain.thermalState.commitExternalActiveInsertions(preparedEnergies, carriedEnergy);
			}
			boolean[] changedCells = new boolean[domain.cellCount];
			for (GranularParcelDraft draft : preparedDrafts) if (!changedCells[draft.cell()]) {
				domain.domainPhaseInventory.markGranularExternalChange(draft.cell()); changedCells[draft.cell()] = true;
			}
			granular.externalSource = sourceAfter;
		};
		if (displacement != null) GranularLiquidDisplacement.publish(domain, displacement, publishSolid);
		else { publishSolid.run(); if (gas != null) domain.gasTopologyCoordinator.commit(gas); }

		return new FluidDomain.GranularInsertion(true, insertedSvq, insertedMass, null);
	}

	private long solidInsertionCapacity(int cell) {
		return domain.domainStep.granularGeometricOpenSvq(cell);
	}

	private GranularCouplingFields.Position planInsertionPosition(GranularCouplingFields.Position position,
	                                                            long quantity, long[] planned) {
		insertionKernel.capture(position.x(), position.y(), position.z(), quantity);
		var canonical = new GranularCouplingFields.Position(position.x(), position.y(), position.z(), insertionKernel.canonicalMask());
		if (domain.granularCoupling.tryPlanParcel(planned, canonical, quantity, this::solidInsertionCapacity)) return canonical;
		int[] masks = GranularIntegerAllocation.admissibleMasks(insertionKernel,
				cell -> solidInsertionCapacity(cell) - planned[cell]);
		if (masks.length == 0) return null;
		var selected = new GranularCouplingFields.Position(position.x(), position.y(), position.z(), masks[0]);
		if (!domain.granularCoupling.tryPlanParcel(planned, selected, quantity, this::solidInsertionCapacity))
			throw new IllegalStateException("Granular insertion differs from its prepared integer allocation");
		return selected;
	}

	private GasTopologyCoordinator.GasTopologyPlan prepareInsertionGas(long[] solid) {
		var gas = domain.gasState.frame == null ? null
				: domain.gasTopologyCoordinator.planAfterCarrierEdit(domain.liquidCells, solid);
		return gas;
	}

	private void requireInsertionSuccessor(GasTopologyCoordinator.GasTopologyPlan gas, double[] energies, GranularLiquidDisplacement.Prepared displacement) {
		if (displacement != null) displacement.inventory.requireCurrent();
		if (energies != null) {
			var after = domain.thermalState.auditLedger().withExternalSourceEnergy(SignedDyadic.sum(energies));
			if (displacement != null) after = after.withMechanicalWork(displacement.inventory.mechanicalHeat())
					.withRoundoff(displacement.energyRoundoff);
			var thermal = gas == null ? null : gas.thermalPlan();
			if (thermal != null) after = after.withExternalSourceEnergy(thermal.sourceEnergy()).withExternalSinkEnergy(thermal.sinkEnergy())
					.withMechanicalWork(thermal.mechanicalWork()).withRoundoff(thermal.roundoff());
			after.expectedInventory();
		}
		if (gas != null) domain.gasTopologyCoordinator.requireBeforeCarrierCommit(gas);
	}

	/** Number of nearby cells needed to release the local inventory at its material-defined loose packing. */
	private int releaseCellCount(List<FluidDomain.CellCandidate> insertionCells, GranularTransferLedger ledger) {
		GranularDomainState granular = domain.granularState;
		if (insertionCells.isEmpty()) return 0;
		boolean[] nearby = new boolean[domain.cellCount];
		long looseBulkSvq = 0L;
		for (FluidDomain.CellCandidate candidate : insertionCells) {
			nearby[candidate.index()] = true;
			looseBulkSvq = Math.addExact(looseBulkSvq,
					granular.deposits.cellBulkVolumeSvq(candidate.index()));
		}
		for (int parcel = 0; parcel < granular.parcels.count(); parcel++) {
			GranularParcelStore.Parcel existing = granular.parcels.parcel(parcel);
			if (nearby[domain.domainStep.granularParcelCell(existing)]) {
				looseBulkSvq = Math.addExact(looseBulkSvq, SolidVolume.bulkVolumeCeil(
						existing.solidVolumeSvq(), granular.registry.material(existing.materialHandle()).restPorosityQ()));
			}
		}
		for (GranularTransferLedger.Group group : ledger.groups()) {
			var material = granular.registry.material(granular.registry.materialHandle(group.materialId()));
			looseBulkSvq = Math.addExact(looseBulkSvq,
					SolidVolume.bulkVolumeCeil(group.solidVolumeSvq(), material.restPorosityQ()));
		}
		long cells = Math.addExact(looseBulkSvq, SolidVolume.SVQ_PER_CELL - 1L) / SolidVolume.SVQ_PER_CELL;
		return (int) Math.min(insertionCells.size(), Math.max(1L, cells));
	}

	private int insertionDistance(FluidDomain.CellCandidate candidate, int centerY) {
		int yz = candidate.index() / domain.sizeX;
		int deltaY = yz % domain.sizeY - centerY;
		return candidate.distanceSquared() + 8 * deltaY * deltaY;
	}

	/** Extracts only same-cell active parcels and the one exposed deposit group, never deep inventory. */
	FluidDomain.GranularExtraction extractExact(
			int cellX, int cellY, int cellZ, long maximumSolidVolumeSvq, int maxRadius, boolean escapedSink,
			java.util.function.Predicate<GranularTransferLedger> itemAcceptance
	) {
		GranularDomainState granular = domain.granularState;
		if (granular == null) return FluidDomain.GranularExtraction.rejected("Granular state is not bound");
		if (domain.hasPendingPhaseMotionWork()) {
			return FluidDomain.GranularExtraction.rejected("Pending phase mechanical work must be projected before extraction");
		}
		if (itemAcceptance == null) throw new IllegalArgumentException("Granular item acceptance is missing");
		if (maximumSolidVolumeSvq <= 0L || maximumSolidVolumeSvq > SolidVolume.SVQ_PER_CUBIC_METER) {
			throw new IllegalArgumentException("Granular extraction capacity is invalid");
		}
		if (maxRadius < 0) throw new IllegalArgumentException("Granular extraction radius is invalid");
		domain.domainFlowFields.cellIndexChecked(cellX, cellY, cellZ); long remaining = maximumSolidVolumeSvq;
		List<FluidDomain.CellCandidate> extractionCells = domain.domainFlowFields.nearbyCells(cellX, cellY, cellZ, maxRadius);
		boolean[] near = new boolean[domain.cellCount]; for (FluidDomain.CellCandidate candidate : extractionCells) near[candidate.index()] = true;
		ArrayList<GranularRemoval> removals = new ArrayList<>();
		GranularLedgerBuilder ledger = new GranularLedgerBuilder();
		for (int parcelIndex = 0; parcelIndex < granular.parcels.count() && remaining > 0L; parcelIndex++) {
			GranularParcelStore.Parcel parcel = granular.parcels.parcel(parcelIndex);
			int parcelCell = domain.domainStep.granularParcelCell(parcel);
			if (!near[parcelCell] || !ledger.canAccept(granular.registry.material(parcel.materialHandle()).id())) continue;
			long taken = Math.min(remaining, parcel.solidVolumeSvq());
			if (parcel.solidCompositionHandle() != 0 && taken != parcel.solidVolumeSvq()) continue;
			long massUnits = mmm.units.Mass.portion(parcel.massUnits(), taken, parcel.solidVolumeSvq());
			long remainingMass = Math.subtractExact(parcel.massUnits(), massUnits);
			if (!(massUnits > 0.0) || taken != parcel.solidVolumeSvq() && !(remainingMass > 0.0)) {
				return FluidDomain.GranularExtraction.rejected("Granular extraction massUnits split is not representable");
			}
			SolidCompositionRecord composition = parcel.solidCompositionHandle() == 0 ? null
					: granular.compositions.record(parcel.solidCompositionHandle());
			ledger.add(granular.registry.material(parcel.materialHandle()).id(), taken, massUnits, composition);
			double energy = domain.thermalState == null ? 0.0
					: domain.thermalState.previewActiveTake(parcelIndex, massUnits, parcel.massUnits());
			removals.add(GranularRemoval.parcel(parcelIndex, parcelCell, parcel, taken, massUnits, energy)); remaining -= taken;
		}
		for (FluidDomain.CellCandidate candidate : extractionCells) if (remaining > 0L) {
			int cell = candidate.index(); GranularDepositStore.Group exposed = granular.deposits.dominantGroup(cell);
			if (exposed != null && ledger.canAccept(granular.registry.material(exposed.materialHandle()).id())) {
				long taken = Math.min(remaining, exposed.solidVolumeSvq());
				if (exposed.solidCompositionHandle() == 0 || taken == exposed.solidVolumeSvq()) {
					GranularDepositStore.SolidPortion preview = granular.deposits.peekTake(cell, exposed.materialHandle(),
							exposed.solidCompositionHandle(), taken);
					SolidCompositionRecord composition = exposed.solidCompositionHandle() == 0 ? null
							: granular.compositions.record(exposed.solidCompositionHandle());
					ledger.add(granular.registry.material(exposed.materialHandle()).id(), taken, preview.massUnits(), composition);
					double energy = domain.thermalState == null ? 0.0 : domain.thermalState.previewDepositTake(
							new GranularEnergyColumn.DepositKey(cell, exposed.materialHandle(),
									exposed.solidCompositionHandle()), preview.massUnits(), exposed.massUnits());
					removals.add(GranularRemoval.deposit(cell, exposed, taken, preview.massUnits(), energy));
					remaining -= taken;
				}
			}
		}
		if (removals.isEmpty()) return FluidDomain.GranularExtraction.rejected("Target cell has no accessible granular inventory");
		GranularTransferLedger transfer = ledger.build();
		if (!itemAcceptance.test(transfer)) {
			return FluidDomain.GranularExtraction.rejected("Accessible granular inventory cannot be represented by the dry container");
		}
		domain.domainFlowFields.ensureInventoryGrid(); domain.granularCoupling.rebuild(granular.parcels, granular.deposits);
		long[] futureSolid = domain.entityBodyCoupling.addTo(domain.granularCoupling.copySolidSvq());
		var remainingParcels = new java.util.HashMap<Integer, GranularParcelStore.Parcel>();
		for (var removal : removals) {
			if (removal.deposit()) { futureSolid[removal.cell()] -= removal.solidVolumeSvq(); continue; }
			var source = granular.parcels.parcel(removal.index());
			domain.granularCoupling.removePlannedParcel(futureSolid, DomainStep.parcelPosition(source), source.solidVolumeSvq());
			if (removal.solidVolumeSvq() == removal.originalSolidVolumeSvq()) continue;
			var next = granular.parcels.prepareOverwrite(removal.index(), new GranularParcelStore.Parcel(source.x(), source.y(), source.z(),
					source.velocityX(), source.velocityY(), source.velocityZ(), source.solidVolumeSvq() - removal.solidVolumeSvq(),
					Math.subtractExact(source.massUnits(), removal.massUnits()),
					source.materialHandle(), 0, source.contactAge(), source.flags()));
			remainingParcels.put(removal.index(), next);
		}
		GranularAuditLedger sinkAfter = escapedSink ? granular.escapedSink.plus(
				transfer, granular.registry, granular.compositions == null ? null : granular.compositions.registry()) : granular.escapedSink;
		// Match the commit order so every intermediate audit chain is checked before inventory edits.
		removals.sort(Comparator.comparingInt((GranularRemoval removal) -> removal.deposit() ? 0
				: removal.solidVolumeSvq() != removal.originalSolidVolumeSvq() ? 1 : 2)
				.thenComparingInt(removal -> removal.deposit() ? removal.cell()
						: removal.solidVolumeSvq() != removal.originalSolidVolumeSvq() ? removal.index() : -removal.index()));
		double removedEnergy = 0.0;
		SignedDyadic bookedEnergy = SignedDyadic.ZERO;
		ThermalAuditLedger thermalAfter = null;
		if (domain.thermalState != null) {
			thermalAfter = domain.thermalState.auditLedger();
			SignedDyadic carrierRemoval = SignedDyadic.ZERO;
			GranularEnergyColumn energy = domain.thermalState.granularColumn();
			for (GranularRemoval removal : removals) {
				double before = removal.deposit() ? energy.depositEnergy(new GranularEnergyColumn.DepositKey(
						removal.cell(), removal.materialHandle(), removal.compositionHandle())) : energy.activeEnergy(removal.index());
				double after = removal.deposit() ? energy.previewDepositRemaining(new GranularEnergyColumn.DepositKey(
						removal.cell(), removal.materialHandle(), removal.compositionHandle()),
						removal.massUnits(), removal.originalMassUnits())
						: energy.previewActiveRemaining(removal.index(), removal.massUnits(), removal.originalMassUnits());
				if (removal.solidVolumeSvq() != removal.originalSolidVolumeSvq()) {
					var context = domain.domainCapabilities.thermalMarkerContext();
					double remainingMass = removal.deposit() ? mmm.units.Mass.kilograms(Math.subtractExact(granular.deposits.matchingGroup(removal.cell(), removal.materialHandle(), group -> group.solidCompositionHandle() == removal.compositionHandle()).massUnits(), removal.massUnits()))
							: remainingParcels.get(removal.index()).mass();
					int material = domain.thermalState.registry().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.GRANULAR,
							granular.registry.material(removal.materialHandle()).id());
					domain.thermalState.stateFunction().stateForInternalEnergy(material, remainingMass, context.solidComposition(removal.compositionHandle()), after);
				}
				SignedDyadic actualRemoval = SignedDyadic.of(before).subtract(SignedDyadic.of(after));
				SignedDyadic exported = SignedDyadic.of(removal.internalEnergy());
				carrierRemoval = carrierRemoval.add(actualRemoval);
				bookedEnergy = bookedEnergy.add(exported);
				thermalAfter = thermalAfter.withExternalSinkEnergy(exported)
						.withRoundoff(SignedDyadic.of(after).add(exported).subtract(SignedDyadic.of(before)));
			}
			removedEnergy = carrierRemoval.toDouble();
			if (!Double.isFinite(removedEnergy)) throw new ArithmeticException("Granular extraction thermal energy overflow");
			SignedDyadic settlement = SignedDyadic.of(removedEnergy).subtract(bookedEnergy);
			thermalAfter = thermalAfter.withExternalSinkEnergy(settlement).withRoundoff(settlement);
			thermalAfter.expectedInventory();
		}
		GasTopologyCoordinator.GasTopologyPlan gas = null;
		if (remainingParcels.isEmpty()) gas = prepareExtractionGas(futureSolid, thermalAfter);
		else {
			// A request ends at its first partial take; all other selected active carriers are removed whole.
			if (remainingParcels.size() != 1) throw new IllegalStateException("Granular extraction has multiple partial active carriers");
			var entry = remainingParcels.entrySet().iterator().next(); int ordinal = entry.getKey();
			var next = entry.getValue(); var source = granular.parcels.parcel(ordinal);
			var kernel = new GranularKernel(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
			kernel.capture(next.x(), next.y(), next.z(), next.solidVolumeSvq());
			java.util.function.IntToLongFunction available = cell -> domain.domainStep.granularGeometricOpenSvq(cell)
					- mmm.granular.SolidVolume.cellVolumeSvqCeil(domain.liquidGrid.exactCellVolumeInventory[cell]) - futureSolid[cell];
			int[] nonIncreasing = GranularIntegerAllocation.admissibleMasks(kernel,
					cell -> Math.min(available.applyAsLong(cell), domain.granularCoupling.parcelShareAtCell(
							DomainStep.parcelPosition(source), source.solidVolumeSvq(), cell)));
			int[] admissible = GranularIntegerAllocation.admissibleMasks(kernel, available);
			if (admissible.length == 0) return FluidDomain.GranularExtraction.rejected("Remaining granular floor/ceil choices cannot share the current liquid capacity");
			boolean[] tried = new boolean[256]; boolean selected = false; RuntimeException lastFailure = null;
			for (int[] choices : new int[][]{nonIncreasing, admissible}) {
				for (int mask : choices) {
					if (tried[mask]) continue;
					tried[mask] = true; var candidate = next.withCeilMask(mask);
					if (!domain.granularCoupling.tryPlanParcel(futureSolid, DomainStep.parcelPosition(candidate), candidate.solidVolumeSvq(),
							cell -> domain.domainStep.granularGeometricOpenSvq(cell) - mmm.granular.SolidVolume.cellVolumeSvqCeil(domain.liquidGrid.exactCellVolumeInventory[cell]))) continue;
					try {
						gas = prepareExtractionGas(futureSolid, thermalAfter);
						remainingParcels.put(ordinal, candidate); selected = true; break;
					} catch (GasTopologyBlockedException | IllegalArgumentException | ArithmeticException unavailable) {
						lastFailure = unavailable;
					}
					domain.granularCoupling.removePlannedParcel(futureSolid, DomainStep.parcelPosition(candidate), candidate.solidVolumeSvq());
				}
				if (selected) break;
			}
			if (!selected) {
				if (lastFailure != null) throw lastFailure;
				return FluidDomain.GranularExtraction.rejected("Remaining granular choices have no certified successor at the current liquid inventory");
			}
		}
		if (gas != null) domain.gasTopologyCoordinator.requireBeforeCarrierCommit(gas);
		for (GranularRemoval removal : removals) if (removal.deposit()) {
			granular.deposits.take(removal.cell(), removal.materialHandle(), removal.compositionHandle(), removal.solidVolumeSvq());
			if (domain.thermalState != null) domain.thermalState.commitExternalDepositTake(new GranularEnergyColumn.DepositKey(
					removal.cell(), removal.materialHandle(), removal.compositionHandle()),
					removal.massUnits(), removal.originalMassUnits());
			if (removal.compositionHandle() != 0) granular.compositions.release(removal.compositionHandle());
		}
		for (GranularRemoval removal : removals) if (!removal.deposit()
				&& removal.solidVolumeSvq() != removal.originalSolidVolumeSvq()) {
			granular.parcels.overwrite(removal.index(), remainingParcels.get(removal.index()));
			if (domain.thermalState != null) domain.thermalState.commitExternalActiveTake(
					removal.index(), removal.massUnits(), removal.originalMassUnits());
		}
		removals.stream().filter(removal -> !removal.deposit()
				&& removal.solidVolumeSvq() == removal.originalSolidVolumeSvq())
				.sorted(Comparator.comparingInt(GranularRemoval::index).reversed()).forEach(removal -> {
					GranularParcelStore.Parcel removed = granular.parcels.removeSwap(removal.index());
					if (domain.thermalState != null) domain.thermalState.commitExternalActiveTake(
							removal.index(), removal.massUnits(), removal.originalMassUnits());
					if (removed.solidCompositionHandle() != 0) granular.compositions.release(removed.solidCompositionHandle());
				});
		if (domain.thermalState != null) domain.thermalState.settleExternalExtraction(bookedEnergy, removedEnergy);
		boolean[] changedCells = new boolean[domain.cellCount];
		for (GranularRemoval removal : removals) if (!changedCells[removal.cell()]) {
			domain.domainPhaseInventory.markGranularExternalChange(removal.cell()); changedCells[removal.cell()] = true;
		}
		if (gas != null) domain.gasTopologyCoordinator.commit(gas);
		granular.escapedSink = sinkAfter;
		return new FluidDomain.GranularExtraction(true, transfer, removedEnergy, null);
	}

	private GasTopologyCoordinator.GasTopologyPlan prepareExtractionGas(long[] futureSolid, ThermalAuditLedger afterTake) {
		if (domain.gasState.frame == null) return null;
		var gas = domain.gasTopologyCoordinator.planAfterCarrierEdit(domain.liquidCells, futureSolid);
		if (gas.thermalPlan() != null) {
			var remap = gas.thermalPlan();
			afterTake.withExternalSourceEnergy(remap.sourceEnergy()).withExternalSinkEnergy(remap.sinkEnergy())
					.withMechanicalWork(remap.mechanicalWork()).withRoundoff(remap.roundoff()).expectedInventory();
		}
		return gas;
	}

	private SolidCompositionRecord splitSolidComposition(
			SolidCompositionRecord source, long sourceSvq, long pieceSvq, long pieceMass
	) {
		int[] species = source.copySpeciesHandles(); long[] amounts = source.copyAmounts();
		for (int entry = 0; entry < amounts.length; entry++) {
			BigInteger[] division = BigInteger.valueOf(amounts[entry]).multiply(BigInteger.valueOf(pieceSvq))
					.divideAndRemainder(BigInteger.valueOf(sourceSvq));
			if (division[1].signum() != 0 || division[0].signum() <= 0) {
				throw new IllegalArgumentException("Reactive granular container split is below composition resolution");
			}
			amounts[entry] = division[0].longValueExact();
		}
		SolidCompositionRecord exact = new SolidCompositionRecord(species, amounts, 0L);
		long residual = Math.subtractExact(pieceMass, exact.speciesMassUnits(domain.granularState.compositions.registry()));
		return new SolidCompositionRecord(species, amounts, residual);
	}

	private record GranularParcelDraft(
			int materialHandle, long solidVolumeSvq, long massUnits, SolidCompositionRecord composition,
			int cell, float x, float y, float z, int ceilMask
	) {
		GranularParcelDraft(int materialHandle, long solidVolumeSvq, long massUnits, SolidCompositionRecord composition) {
			this(materialHandle, solidVolumeSvq, massUnits, composition, -1, Float.NaN, Float.NaN, Float.NaN, -1);
		}
		GranularParcelDraft at(int cell, GranularCouplingFields.Position position) {
			return new GranularParcelDraft(materialHandle, solidVolumeSvq, massUnits, composition,
					cell, position.x(), position.y(), position.z(), position.ceilMask());
		}
	}

	private static final class GranularLedgerBuilder {
		private final TreeMap<String, MutableGranularGroup> groups = new TreeMap<>();
		boolean canAccept(String materialId) {
			return groups.containsKey(materialId) || groups.size() < GranularTransferLedger.MAX_GROUPS;
		}
		void add(String materialId, long svq, long massUnits, SolidCompositionRecord composition) {
			groups.computeIfAbsent(materialId, ignored -> new MutableGranularGroup()).add(svq, massUnits, composition);
		}
		GranularTransferLedger build() {
			ArrayList<GranularTransferLedger.Group> result = new ArrayList<>(groups.size());
			for (Map.Entry<String, MutableGranularGroup> entry : groups.entrySet()) {
				result.add(entry.getValue().build(entry.getKey()));
			}
			return new GranularTransferLedger(result);
		}
	}

	private static final class MutableGranularGroup {
		private long svq;
		private long massUnits, residual;
		private TreeMap<Integer, Long> species;
		void add(long addedSvq, long addedMass, SolidCompositionRecord composition) {
			svq = Math.addExact(svq, addedSvq); massUnits = Math.addExact(massUnits, addedMass);
			if (composition == null) {
				if (species != null) throw new IllegalArgumentException("Mixed inert/reactive granular material group");
				return;
			}
			if (species == null) species = new TreeMap<>();
			residual = Math.addExact(residual, composition.massResidualUnits());
			for (int entry = 0; entry < composition.entryCount(); entry++) species.merge(
					composition.speciesHandle(entry), composition.amountAt(entry), Math::addExact);
		}
		GranularTransferLedger.Group build(String materialId) {
			SolidCompositionRecord composition = null;
			if (species != null) {
				int[] handles = new int[species.size()]; long[] amounts = new long[species.size()]; int index = 0;
				for (Map.Entry<Integer, Long> entry : species.entrySet()) {
					handles[index] = entry.getKey(); amounts[index++] = entry.getValue();
				}
				composition = new SolidCompositionRecord(
						handles, amounts, residual);
			}
			return new GranularTransferLedger.Group(materialId, svq, massUnits, composition);
		}
	}

	/**
	 * Thermal carrier context that overlays the not-yet-inserted granular drafts onto the live
	 * marker context, so external-insertion planning can prevalidate the parcels before the
	 * parcel store itself mutates. Everything else delegates to the live context.
	 */
	private record DraftParcelContext(
			ThermalCarrierContext base, List<GranularParcelDraft> drafts, int[] compositionByDraft
	) implements ThermalCarrierContext {
		private int draftOrdinal(int ordinal) {
			int live = base.activeParcelCount();
			if (ordinal < live || ordinal >= live + drafts.size()) {
				throw new IllegalArgumentException("Granular draft overlay ordinal is outside the plan");
			}
			return ordinal - live;
		}
		@Override public int markerCount() { return base.markerCount(); }
		@Override public double liquidInternalEnergy(int ordinal) { return base.liquidInternalEnergy(ordinal); }
		@Override public void setLiquidInternalEnergy(int ordinal, double energy) { base.setLiquidInternalEnergy(ordinal, energy); }
		@Override public void setLiquidInternalEnergies(double[] energies) { base.setLiquidInternalEnergies(energies); }
		@Override public long liquidInventoryRevision() { return base.liquidInventoryRevision(); }
		@Override public int cell(int marker) { return base.cell(marker); }
		@Override public int fluidMaterialHandle(int marker) { return base.fluidMaterialHandle(marker); }
		@Override public double mass(int marker) { return base.mass(marker); }
		@Override public double volume(int marker) { return base.volume(marker); }
		@Override public ChemicalMixtureRecord mixture(int marker) { return base.mixture(marker); }
		@Override public ChemicalRegistry chemicalRegistry() { return base.chemicalRegistry(); }
		@Override public int granularMaterialCount() { return base.granularMaterialCount(); }
		@Override public String granularRegistryFingerprint() { return base.granularRegistryFingerprint(); }
		@Override public String granularMaterialId(int materialHandle) { return base.granularMaterialId(materialHandle); }
		@Override public int activeParcelCount() { return base.activeParcelCount() + drafts.size(); }
		@Override public int activeParcelCell(int ordinal) {
			return ordinal < base.activeParcelCount() ? base.activeParcelCell(ordinal)
					: drafts.get(draftOrdinal(ordinal)).cell();
		}
		@Override public int activeParcelMaterial(int ordinal) {
			return ordinal < base.activeParcelCount() ? base.activeParcelMaterial(ordinal)
					: drafts.get(draftOrdinal(ordinal)).materialHandle();
		}
		@Override public int activeParcelComposition(int ordinal) {
			return ordinal < base.activeParcelCount() ? base.activeParcelComposition(ordinal)
					: compositionByDraft[draftOrdinal(ordinal)];
		}
		@Override public long activeParcelSvq(int ordinal) {
			return ordinal < base.activeParcelCount() ? base.activeParcelSvq(ordinal)
					: drafts.get(draftOrdinal(ordinal)).solidVolumeSvq();
		}
		@Override public double activeParcelMass(int ordinal) {
			return ordinal < base.activeParcelCount() ? base.activeParcelMass(ordinal)
					: mmm.units.Mass.kilograms(drafts.get(draftOrdinal(ordinal)).massUnits());
		}
		@Override public List<GranularEnergyColumn.DepositKey> depositKeys() { return base.depositKeys(); }
		@Override public long depositSvq(GranularEnergyColumn.DepositKey key) { return base.depositSvq(key); }
		@Override public double depositMass(GranularEnergyColumn.DepositKey key) { return base.depositMass(key); }
		@Override public Map<String, Long> solidComposition(int compositionHandle) {
			return base.solidComposition(compositionHandle);
		}
	}

	private record GranularRemoval(
			boolean deposit, int index, int cell, int materialHandle, int compositionHandle,
			long solidVolumeSvq, long originalSolidVolumeSvq, long massUnits, long originalMassUnits, double internalEnergy
	) {
		static GranularRemoval parcel(
				int index, int cell, GranularParcelStore.Parcel parcel, long svq, long massUnits, double energy
		) {
			return new GranularRemoval(false, index, cell, parcel.materialHandle(), parcel.solidCompositionHandle(),
					svq, parcel.solidVolumeSvq(), massUnits, parcel.massUnits(), energy);
		}
		static GranularRemoval deposit(
				int cell, GranularDepositStore.Group group, long svq, long massUnits, double energy
		) {
			return new GranularRemoval(true, -1, cell, group.materialHandle(), group.solidCompositionHandle(),
					svq, group.solidVolumeSvq(), massUnits, group.massUnits(), energy);
		}
	}
}
