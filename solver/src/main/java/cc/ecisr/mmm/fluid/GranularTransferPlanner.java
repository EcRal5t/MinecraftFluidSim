package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularAuditLedger;
import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.granular.GranularDepositStore;
import cc.ecisr.mmm.granular.GranularParcelStore;
import cc.ecisr.mmm.granular.GranularTransferLedger;
import cc.ecisr.mmm.granular.SolidCompositionRecord;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.thermal.GranularEnergyColumn;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;
import cc.ecisr.mmm.units.SignedDyadic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Plans and commits the exact dry-container granular transfers against shared collision
 * capacity before any parcel, deposit, composition or audit truth changes: ledger piece
 * derivation, nearby cell selection, loose packing/capacity planning and the thermal energy
 * settlement are all resolved first, and the commit loops only apply pre-validated pieces.
 * The inventory-only variant serves fixtures and transactional service adapters.
 */
final class GranularTransferPlanner {
	private final FluidDomain domain;

	GranularTransferPlanner(FluidDomain domain) {
		this.domain = domain;
	}

	/** Inventory-only exact insertion used by fixtures and transactional service adapters. */
	FluidDomain.GranularInsertion insertExactInventory(
			int cellX, int cellY, int cellZ, int materialHandle, int compositionHandle,
			long solidVolumeSvq, double mass
	) {
		GranularDomainState granular = domain.granularState;
		if (granular == null) return FluidDomain.GranularInsertion.rejected("Granular state is not bound");
		int cell = domain.cellIndexChecked(cellX, cellY, cellZ);
		if (solidVolumeSvq <= 0L || !(Double.isFinite(mass) && mass > 0.0)) {
			throw new IllegalArgumentException("Granular insertion inventory is invalid");
		}
		granular.registry.material(materialHandle);
		if (compositionHandle != 0 && (granular.compositions == null || !granular.compositions.isLive(compositionHandle))) {
			throw new IllegalArgumentException("Granular insertion has no live solid composition");
		}
		domain.ensureGrid();
		domain.granularCoupling.rebuild(granular.parcels, granular.deposits);
		long[] plannedSolid = domain.granularCoupling.copySolidSvq();
		GranularCouplingFields.Position position = null;
		for (int attempt = 0; attempt < 16 && position == null; attempt++) {
			GranularCouplingFields.Position candidate = domain.granularCollision.spawnPosition(
					cellX, cellY, cellZ, solidVolumeSvq, granular.parcels.count() + attempt * 16_384L,
					Float.NaN, Float.NaN, Float.NaN);
			if (candidate != null && domain.granularCoupling.tryPlanParcel(plannedSolid, candidate, solidVolumeSvq,
					this::combinedInsertionCapacity)) position = candidate;
		}
		if (position == null) return FluidDomain.GranularInsertion.rejected("Target cell has no collision-valid solid capacity");
		if (!domain.canAddCarrierVolume(solidVolumeSvq / (double) SolidVolume.SVQ_PER_CELL)) {
			return FluidDomain.GranularInsertion.rejected("Sealed region has no combined fluid/solid capacity");
		}
		GranularParcelStore.Parcel parcel = new GranularParcelStore.Parcel(
				position.x(), position.y(), position.z(), 0.0F, 0.0F, 0.0F,
				solidVolumeSvq, mass, materialHandle, compositionHandle, 0, (byte) 0);
		if (!granular.parcels.canAdd(parcel)) return FluidDomain.GranularInsertion.rejected("Active granular parcel budget exceeded");
		double thermalEnergy = domain.thermalState == null ? 0.0 : domain.thermalState.planExternalGranularEnergy(
				materialHandle, compositionHandle, mass, domain.thermalMarkerContext());
		granular.parcels.add(parcel);
		if (domain.thermalState != null) domain.thermalState.commitExternalActiveInsertions(new double[]{thermalEnergy});
		domain.gridDirty = true;
		return new FluidDomain.GranularInsertion(true, solidVolumeSvq, mass, null);
	}

	/** All-or-nothing dry-container insertion. The ledger is already stable-ID validated by the item adapter. */
	FluidDomain.GranularInsertion insertExact(
			int cellX, int cellY, int cellZ, GranularTransferLedger ledger, int maxRadius, boolean externalSource
	) {
		GranularDomainState granular = domain.granularState;
		if (granular == null) return FluidDomain.GranularInsertion.rejected("Granular state is not bound");
		if (ledger == null) throw new IllegalArgumentException("Granular insertion ledger is missing");
		if (maxRadius < 0) throw new IllegalArgumentException("Granular insertion radius is invalid");
		domain.cellIndexChecked(cellX, cellY, cellZ); domain.ensureGrid();
		domain.granularCoupling.rebuild(granular.parcels, granular.deposits);
		long insertedSvq = ledger.totalSolidVolumeSvq(); double insertedMass = ledger.totalMass();
		if (!domain.canAddCarrierVolume(insertedSvq / (double) SolidVolume.SVQ_PER_CELL)) {
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
				if (granular.compositions == null || !FluidDomain.massClose(group.composition().totalMass(granular.compositions.registry()), group.mass())) {
					throw new IllegalArgumentException("Granular transfer composition/mass mismatch");
				}
			}
			long remainingSvq = group.solidVolumeSvq(); double remainingMass = group.mass();
			while (remainingSvq > 0L) {
				long pieceSvq = Math.min(remainingSvq, material.maximumActiveParcelSvq());
				double pieceMass = pieceSvq == remainingSvq ? remainingMass
						: group.mass() * ((double) pieceSvq / group.solidVolumeSvq());
				if (!(Double.isFinite(pieceMass) && pieceMass > 0.0 && pieceMass <= remainingMass)) {
					return FluidDomain.GranularInsertion.rejected("Granular transfer mass split is not representable");
				}
				SolidCompositionRecord pieceComposition = group.composition() == null ? null
						: splitSolidComposition(group.composition(), group.solidVolumeSvq(), pieceSvq, pieceMass);
				drafts.add(new GranularParcelDraft(materialHandle, pieceSvq, pieceMass, pieceComposition));
				if (pieceComposition != null) reactiveDrafts++;
				remainingSvq -= pieceSvq; remainingMass -= pieceMass;
			}
		}
		List<FluidDomain.CellCandidate> insertionCells = domain.nearbyCells(cellX, cellY, cellZ, maxRadius);
		// A dry pour is a loose release envelope, not a vertical stack of mutually blocking
		// numerical parcels. Prefer nearby horizontal cells; vertical cells remain bounded
		// fallbacks when the port neighborhood or exact occupancy requires them.
		insertionCells.sort(Comparator
				.comparingInt((FluidDomain.CellCandidate candidate) -> insertionDistance(candidate, cellY))
				.thenComparingInt(FluidDomain.CellCandidate::distanceSquared)
				.thenComparingInt(FluidDomain.CellCandidate::index));
		int releaseCellCount = releaseCellCount(insertionCells, ledger);
		long[] plannedSolid = domain.granularCoupling.copySolidSvq();
		int[] plannedParcelCounts = new int[domain.cellCount];
		for (int parcel = 0; parcel < granular.parcels.count(); parcel++) {
			plannedParcelCounts[domain.granularParcelCell(granular.parcels.parcel(parcel))]++;
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
					GranularCouplingFields.Position spawn = domain.granularCollision.spawnPosition(
							candidateX, candidateY, candidateZ, draft.solidVolumeSvq(), sequence,
							Float.NaN, Float.NaN, Float.NaN);
					if (spawn != null && domain.granularCoupling.tryPlanParcel(plannedSolid, spawn, draft.solidVolumeSvq(),
							this::combinedInsertionCapacity)) {
						selected = candidate.index(); selectedPosition = spawn; break;
					}
				}
				if (selected >= 0) break;
			}
			if (selected < 0) return FluidDomain.GranularInsertion.rejected("Nearby cells have no collision-valid solid capacity");
			plannedParcelCounts[selected]++;
			positioned.add(draft.at(selected, selectedPosition));
		}
		drafts = positioned;
		if (!granular.parcels.hasCapacity(drafts.size())
				|| reactiveDrafts > 0 && !granular.compositions.hasCapacity(reactiveDrafts)) {
			return FluidDomain.GranularInsertion.rejected("Active granular parcel/composition budget exceeded");
		}
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
		try {
			for (int index = 0; index < drafts.size(); index++) {
				GranularParcelDraft draft = drafts.get(index);
				if (draft.composition() != null) {
					compositionByDraft[index] = granular.compositions.create(draft.composition());
					createdCompositions.add(compositionByDraft[index]);
				}
			}
			if (domain.thermalState != null) thermalInsertions = domain.thermalState.planExternalActiveInsertions(
					originalCount, drafts.size(),
					new DraftParcelContext(domain.thermalMarkerContext(), drafts, compositionByDraft));
		} catch (RuntimeException failure) {
			for (int index = createdCompositions.size() - 1; index >= 0; index--) {
				int handle = createdCompositions.get(index);
				if (granular.compositions.isLive(handle)) granular.compositions.release(handle);
			}
			throw failure;
		}
		for (int index = 0; index < drafts.size(); index++) {
			GranularParcelDraft draft = drafts.get(index);
			granular.parcels.add(new GranularParcelStore.Parcel(
					draft.x(), draft.y(), draft.z(), 0.0F, 0.0F, 0.0F,
					draft.solidVolumeSvq(), draft.mass(), draft.materialHandle(), compositionByDraft[index], 0, (byte) 0));
		}
		if (domain.thermalState != null) domain.thermalState.commitExternalActiveInsertions(thermalInsertions);
		boolean[] changedCells = new boolean[domain.cellCount];
		for (GranularParcelDraft draft : drafts) {
			int changed = draft.cell();
			if (!changedCells[changed]) { domain.markGranularExternalChange(changed); changedCells[changed] = true; }
		}
		granular.externalSource = sourceAfter;
		return new FluidDomain.GranularInsertion(true, insertedSvq, insertedMass, null);
	}

	private long combinedInsertionCapacity(int cell) {
		return Math.max(0, domain.granularGeometricOpenSvq(cell)
				- JointPhaseCapacity.liquidSvqCeil(domain.markerGrid.exactCellVolumeInventory[cell]));
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
			if (nearby[domain.granularParcelCell(existing)]) {
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
		if (domain.hasPendingPhaseVolumeWork()) {
			return FluidDomain.GranularExtraction.rejected("Pending phase mechanical work must be projected before extraction");
		}
		if (itemAcceptance == null) throw new IllegalArgumentException("Granular item acceptance is missing");
		if (maximumSolidVolumeSvq <= 0L || maximumSolidVolumeSvq > SolidVolume.SVQ_PER_BV) {
			throw new IllegalArgumentException("Granular extraction capacity is invalid");
		}
		if (maxRadius < 0) throw new IllegalArgumentException("Granular extraction radius is invalid");
		domain.cellIndexChecked(cellX, cellY, cellZ); long remaining = maximumSolidVolumeSvq;
		List<FluidDomain.CellCandidate> extractionCells = domain.nearbyCells(cellX, cellY, cellZ, maxRadius);
		boolean[] near = new boolean[domain.cellCount]; for (FluidDomain.CellCandidate candidate : extractionCells) near[candidate.index()] = true;
		ArrayList<GranularRemoval> removals = new ArrayList<>();
		GranularLedgerBuilder ledger = new GranularLedgerBuilder();
		for (int parcelIndex = 0; parcelIndex < granular.parcels.count() && remaining > 0L; parcelIndex++) {
			GranularParcelStore.Parcel parcel = granular.parcels.parcel(parcelIndex);
			int parcelCell = domain.granularParcelCell(parcel);
			if (!near[parcelCell] || !ledger.canAccept(granular.registry.material(parcel.materialHandle()).id())) continue;
			long taken = Math.min(remaining, parcel.solidVolumeSvq());
			if (parcel.solidCompositionHandle() != 0 && taken != parcel.solidVolumeSvq()) continue;
			double mass = taken == parcel.solidVolumeSvq() ? parcel.mass()
					: parcel.mass() * ((double) taken / parcel.solidVolumeSvq());
			SolidCompositionRecord composition = parcel.solidCompositionHandle() == 0 ? null
					: granular.compositions.record(parcel.solidCompositionHandle());
			ledger.add(granular.registry.material(parcel.materialHandle()).id(), taken, mass, composition);
			double energy = domain.thermalState == null ? 0.0
					: domain.thermalState.previewActiveTake(parcelIndex, taken, parcel.solidVolumeSvq());
			removals.add(GranularRemoval.parcel(parcelIndex, parcelCell, parcel, taken, mass, energy)); remaining -= taken;
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
					ledger.add(granular.registry.material(exposed.materialHandle()).id(), taken, preview.mass(), composition);
					double energy = domain.thermalState == null ? 0.0 : domain.thermalState.previewDepositTake(
							new GranularEnergyColumn.DepositKey(cell, exposed.materialHandle(),
									exposed.solidCompositionHandle()), taken, exposed.solidVolumeSvq());
					removals.add(GranularRemoval.deposit(cell, exposed, taken, preview.mass(), energy));
					remaining -= taken;
				}
			}
		}
		if (removals.isEmpty()) return FluidDomain.GranularExtraction.rejected("Target cell has no accessible granular inventory");
		GranularTransferLedger transfer = ledger.build();
		if (!itemAcceptance.test(transfer)) {
			return FluidDomain.GranularExtraction.rejected("Accessible granular inventory cannot be represented by the dry container");
		}
		GranularAuditLedger sinkAfter = escapedSink ? granular.escapedSink.plus(
				transfer, granular.registry, granular.compositions == null ? null : granular.compositions.registry()) : granular.escapedSink;
		// Every fallible aggregation completes before the first granular or thermal mutation:
		// the removed thermal energy is summed from the previews so an overflow can still reject
		// the whole extraction instead of firing after parcels and deposits already changed.
		double removedEnergy = 0.0;
		for (GranularRemoval removal : removals) removedEnergy += removal.internalEnergy();
		if (!Double.isFinite(removedEnergy)) throw new ArithmeticException("Granular extraction thermal energy overflow");
		for (GranularRemoval removal : removals) if (removal.deposit()) {
			granular.deposits.take(removal.cell(), removal.materialHandle(), removal.compositionHandle(), removal.solidVolumeSvq());
			if (domain.thermalState != null) domain.thermalState.commitExternalDepositTake(new GranularEnergyColumn.DepositKey(
					removal.cell(), removal.materialHandle(), removal.compositionHandle()),
					removal.solidVolumeSvq(), removal.originalSolidVolumeSvq());
			if (removal.compositionHandle() != 0) granular.compositions.release(removal.compositionHandle());
		}
		for (GranularRemoval removal : removals) if (!removal.deposit()
				&& removal.solidVolumeSvq() != removal.originalSolidVolumeSvq()) {
			GranularParcelStore.Parcel parcel = granular.parcels.parcel(removal.index());
			granular.parcels.overwrite(removal.index(), new GranularParcelStore.Parcel(parcel.x(), parcel.y(), parcel.z(),
					parcel.velocityX(), parcel.velocityY(), parcel.velocityZ(),
					parcel.solidVolumeSvq() - removal.solidVolumeSvq(), parcel.mass() - removal.mass(),
					parcel.materialHandle(), 0, parcel.contactAge(), parcel.flags()));
			if (domain.thermalState != null) domain.thermalState.commitExternalActiveTake(
					removal.index(), removal.solidVolumeSvq(), removal.originalSolidVolumeSvq());
		}
		removals.stream().filter(removal -> !removal.deposit()
				&& removal.solidVolumeSvq() == removal.originalSolidVolumeSvq())
				.sorted(Comparator.comparingInt(GranularRemoval::index).reversed()).forEach(removal -> {
					GranularParcelStore.Parcel removed = granular.parcels.removeSwap(removal.index());
					if (domain.thermalState != null) domain.thermalState.commitExternalActiveTake(
							removal.index(), removal.solidVolumeSvq(), removal.originalSolidVolumeSvq());
					if (removed.solidCompositionHandle() != 0) granular.compositions.release(removed.solidCompositionHandle());
				});
		boolean[] changedCells = new boolean[domain.cellCount];
		for (GranularRemoval removal : removals) if (!changedCells[removal.cell()]) {
			domain.markGranularExternalChange(removal.cell()); changedCells[removal.cell()] = true;
		}
		granular.escapedSink = sinkAfter;
		return new FluidDomain.GranularExtraction(true, transfer, removedEnergy, null);
	}

	private SolidCompositionRecord splitSolidComposition(
			SolidCompositionRecord source, long sourceSvq, long pieceSvq, double pieceMass
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
		SolidCompositionRecord exact = new SolidCompositionRecord(species, amounts, Double.doubleToRawLongBits(0.0));
		double residual = pieceMass - exact.exactSpeciesMass(domain.granularState.compositions.registry()).toDouble();
		return new SolidCompositionRecord(species, amounts, Double.doubleToRawLongBits(residual));
	}

	private record GranularParcelDraft(
			int materialHandle, long solidVolumeSvq, double mass, SolidCompositionRecord composition,
			int cell, float x, float y, float z
	) {
		GranularParcelDraft(int materialHandle, long solidVolumeSvq, double mass, SolidCompositionRecord composition) {
			this(materialHandle, solidVolumeSvq, mass, composition, -1, Float.NaN, Float.NaN, Float.NaN);
		}
		GranularParcelDraft at(int cell, GranularCouplingFields.Position position) {
			return new GranularParcelDraft(materialHandle, solidVolumeSvq, mass, composition,
					cell, position.x(), position.y(), position.z());
		}
	}

	private static final class GranularLedgerBuilder {
		private final TreeMap<String, MutableGranularGroup> groups = new TreeMap<>();
		boolean canAccept(String materialId) {
			return groups.containsKey(materialId) || groups.size() < GranularTransferLedger.MAX_GROUPS;
		}
		void add(String materialId, long svq, double mass, SolidCompositionRecord composition) {
			groups.computeIfAbsent(materialId, ignored -> new MutableGranularGroup()).add(svq, mass, composition);
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
		private SignedDyadic mass = SignedDyadic.ZERO, residual = SignedDyadic.ZERO;
		private TreeMap<Integer, Long> species;
		void add(long addedSvq, double addedMass, SolidCompositionRecord composition) {
			svq = Math.addExact(svq, addedSvq); mass = mass.add(SignedDyadic.of(addedMass));
			if (composition == null) {
				if (species != null) throw new IllegalArgumentException("Mixed inert/reactive granular material group");
				return;
			}
			if (species == null) species = new TreeMap<>();
			residual = residual.add(SignedDyadic.of(composition.massResidual()));
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
						handles, amounts, Double.doubleToRawLongBits(residual.toDouble()));
			}
			return new GranularTransferLedger.Group(materialId, svq, mass.toDouble(), composition);
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
					: drafts.get(draftOrdinal(ordinal)).mass();
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
			long solidVolumeSvq, long originalSolidVolumeSvq, double mass, double internalEnergy
	) {
		static GranularRemoval parcel(
				int index, int cell, GranularParcelStore.Parcel parcel, long svq, double mass, double energy
		) {
			return new GranularRemoval(false, index, cell, parcel.materialHandle(), parcel.solidCompositionHandle(),
					svq, parcel.solidVolumeSvq(), mass, energy);
		}
		static GranularRemoval deposit(
				int cell, GranularDepositStore.Group group, long svq, double mass, double energy
		) {
			return new GranularRemoval(true, -1, cell, group.materialHandle(), group.solidCompositionHandle(),
					svq, group.solidVolumeSvq(), mass, energy);
		}
	}
}
