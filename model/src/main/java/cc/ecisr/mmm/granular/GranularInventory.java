package cc.ecisr.mmm.granular;

import cc.ecisr.mmm.units.SignedDyadic;

import java.util.Arrays;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.math.BigInteger;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import java.util.function.IntUnaryOperator;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/** Atomic inventory-only parcel/deposit transitions. Mechanics and scheduling are layered above this class. */
public final class GranularInventory {
	private final GranularParcelStore parcels;
	private final GranularDepositStore deposits;
	private final int[] activeHeadByCell, activeNext, mergeFirst, mergeSecond;
	private final boolean[] mergePaired;
	private final ActiveMerge[] plannedMerge;
	private SolidCompositionPool compositions;
	private EnergyAdapter thermalEnergy;

	public GranularInventory(GranularParcelStore parcels, GranularDepositStore deposits) {
		if (parcels == null || deposits == null) throw new IllegalArgumentException("Granular stores are missing");
		this.parcels = parcels;
		this.deposits = deposits;
		this.activeHeadByCell = new int[deposits.cellCount()];
		this.activeNext = new int[parcels.capacity()];
		this.mergeFirst = new int[parcels.capacity() / 2];
		this.mergeSecond = new int[parcels.capacity() / 2];
		this.mergePaired = new boolean[parcels.capacity()];
		this.plannedMerge = new ActiveMerge[parcels.capacity() / 2];
	}

	public void bindSolidCompositions(SolidCompositionPool pool) {
		if (pool == null) throw new IllegalArgumentException("Solid composition pool is missing");
		if (compositions != null && compositions != pool) {
			throw new IllegalStateException("Granular inventory cannot change its solid composition pool");
		}
		compositions = pool;
	}

	public void bindThermalEnergy(EnergyAdapter adapter) {
		if (adapter == null) throw new IllegalArgumentException("Granular thermal adapter is missing");
		if (thermalEnergy != null && thermalEnergy != adapter) {
			throw new IllegalStateException("Granular inventory cannot change its thermal adapter");
		}
		thermalEnergy = adapter;
	}

	public boolean depositParcel(int parcelIndex, int cell, long geometricOpenSvq) {
		SettlementPlan plan = planSettlement(new int[] {parcelIndex}, cell, geometricOpenSvq);
		if (plan == null) return false;
		commitSettlement(plan);
		return true;
	}

	public record SettlementTarget(int cell, long addedSolidVolumeSvq, long bulkVolumeSvq,
	                               SignedDyadic oldBedMass, long oldBedBulkVolumeSvq, SignedDyadic addedMass) { }

	/** Whole-parcel conversion with one complete multi-cell inventory and energy preflight. */
	public static final class SettlementPlan {
		private final GranularInventory owner;
		private final long parcelRevision;
		private final int[] indices;
		private final int[] targetCells;
		private final List<SettlementTarget> targets;
		private final List<GranularParcelStore.Parcel> parcels;
		private final long totalSvq;
		private final GranularDepositStore.AdditionsBatchPlan additions;
		private final SolidCompositionPool pool;
		private final Map<Integer, SolidCompositionRecord> before, after;
		private final int[] released;
		private final EnergyAdapter adapter;
		private final EnergyPlan energy;
		private SettlementPlan(GranularInventory owner, int[] indices, int[] targetCells, List<SettlementTarget> targets,
		                       List<GranularParcelStore.Parcel> parcels, long totalSvq,
		                       GranularDepositStore.AdditionsBatchPlan additions,
		                       Map<Integer, SolidCompositionRecord> before, Map<Integer, SolidCompositionRecord> after,
		                       int[] released, EnergyPlan energy) {
			this.owner = owner; this.parcelRevision = owner.parcels.mutationRevision();
			this.indices = indices; this.targetCells = targetCells; this.targets = List.copyOf(targets);
			this.parcels = List.copyOf(parcels); this.totalSvq = totalSvq;
			this.additions = additions; this.pool = owner.compositions;
			this.before = Map.copyOf(before); this.after = Map.copyOf(after); this.released = released;
			this.adapter = owner.thermalEnergy; this.energy = energy;
		}
		private SettlementTarget onlyTarget() {
			if (targets.size() != 1) throw new IllegalStateException("Settlement plan has multiple receiving cells");
			return targets.getFirst();
		}
		public int cell() { return onlyTarget().cell(); }
		public List<SettlementTarget> targets() { return targets; }
		public int[] targetCells() { return targetCells.clone(); }
		public int[] parcelIndices() { return indices.clone(); }
		/** Source snapshots align with the descending original ordinals. */
		public List<GranularParcelStore.Parcel> parcels() { return parcels; }
		public long totalSolidVolumeSvq() { return totalSvq; }
		public long bulkVolumeSvq() { return onlyTarget().bulkVolumeSvq(); }
		public SignedDyadic oldBedMass() { return onlyTarget().oldBedMass(); }
		public long oldBedBulkVolumeSvq() { return onlyTarget().oldBedBulkVolumeSvq(); }
		public SignedDyadic massStorageDelta() { return additions.massStorageDelta(); }
		public SignedDyadic massStorageBound() { return additions.massStorageBound(); }
	}

	private static final class SettlementComposition {
		final int material;
		final boolean existing;
		final SolidCompositionRecord prototype;
		final long prototypeVolume;
		int retained;
		final List<Integer> sourceSlots = new ArrayList<>();
		final List<SolidCompositionRecord> records = new ArrayList<>();
		SettlementComposition(int material, int retained, boolean existing, SolidCompositionRecord record, long volume) {
			this.material = material; this.retained = retained; this.existing = existing;
			this.prototype = record; this.prototypeVolume = volume;
			if (existing) records.add(record);
		}
	}

	/** Returns null for a jointly unrepresentable cohort; planning never reserves or releases handles. */
	public SettlementPlan planSettlement(int[] parcelIndices, int cell, long geometricOpenSvq) {
		return planSettlement(parcelIndices, cell, geometricOpenSvq, ignored -> true);
	}

	/** The shared logical budget includes selected-source reads and reactive ownership scans. */
	public SettlementPlan planSettlement(int[] parcelIndices, int cell, long geometricOpenSvq, IntPredicate workBudget) {
		if (parcelIndices == null) throw new IllegalArgumentException("Settlement parcel selection is missing");
		int[] targetCells = new int[parcelIndices.length]; Arrays.fill(targetCells, cell);
		long[] open = new long[parcelIndices.length]; Arrays.fill(open, geometricOpenSvq);
		return planSettlement(parcelIndices, targetCells, open, workBudget);
	}

	/** Source ordinals, receiving cells and capacities are paired in caller order. */
	public SettlementPlan planSettlement(int[] parcelIndices, int[] targetCells, long[] targetOpenSvq, IntPredicate workBudget) {
		if (parcelIndices == null || parcelIndices.length == 0 || parcelIndices.length > parcels.count()) {
			throw new IllegalArgumentException("Settlement parcel selection is invalid");
		}
		if (workBudget == null || targetCells == null || targetOpenSvq == null
				|| targetCells.length != parcelIndices.length || targetOpenSvq.length != parcelIndices.length) {
			throw new IllegalArgumentException("Settlement target arrays or planning budget are invalid");
		}
		long[] order = new long[parcelIndices.length]; TreeMap<Integer, Long> openByCell = new TreeMap<>();
		for (int slot = 0; slot < order.length; slot++) {
			int index = parcelIndices[slot], cell = targetCells[slot]; long open = targetOpenSvq[slot];
			if (index < 0 || index >= parcels.count() || cell < 0 || cell >= deposits.cellCount()
					|| open <= 0 || open > SolidVolume.SVQ_PER_CELL) throw new IllegalArgumentException("Settlement source or target is invalid");
			Long previous = openByCell.putIfAbsent(cell, open);
			if (previous != null && previous != open) throw new IllegalArgumentException("Settlement target capacities disagree");
			order[slot] = (long) index << 32 | slot;
		}
		Arrays.sort(order); int[] indices = new int[order.length], cells = new int[order.length];
		for (int slot = 0; slot < order.length; slot++) {
			long entry = order[order.length - 1 - slot]; indices[slot] = (int) (entry >>> 32);
			cells[slot] = targetCells[(int) entry];
			if (slot > 0 && indices[slot] == indices[slot - 1]) throw new IllegalArgumentException("Settlement contains a duplicate parcel");
		}
		if (!workBudget.test(indices.length)) return null;
		ArrayList<GranularParcelStore.Parcel> sources = new ArrayList<>(indices.length);
		TreeMap<Integer, SolidCompositionRecord> before = new TreeMap<>(), after = new TreeMap<>();
		TreeSet<Integer> released = new TreeSet<>();
		HashSet<Integer> selectedHandles = new HashSet<>();
		long totalSvq = 0;
		for (int index : indices) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index); sources.add(parcel);
			totalSvq = Math.addExact(totalSvq, parcel.solidVolumeSvq());
			if (parcel.solidCompositionHandle() != 0) {
				if (compositions == null || !selectedHandles.add(parcel.solidCompositionHandle())) {
					throw new IllegalStateException("Settlement has missing or shared composition ownership");
				}
				before.put(parcel.solidCompositionHandle(), compositions.record(parcel.solidCompositionHandle()));
				requireCompositionMass(before.get(parcel.solidCompositionHandle()), parcel.mass());
			}
		}
		if (!selectedHandles.isEmpty()) {
			long ownershipWork = parcels.count() + (long) deposits.groupCount() + compositions.liveCount()
					+ (deposits.groupCount() == 0 ? 0 : deposits.cellCount());
			if (ownershipWork > Integer.MAX_VALUE || !workBudget.test((int) ownershipWork)) return null;
			requireCanClear();
		}
		int[] targetHandle = new int[indices.length];
		TreeMap<Integer, ArrayList<SettlementComposition>> groupedByCell = new TreeMap<>();
		for (int slot = 0; slot < sources.size(); slot++) {
			int cell = cells[slot];
			GranularParcelStore.Parcel parcel = sources.get(slot);
			if (parcel.solidCompositionHandle() == 0) continue;
			var grouped = groupedByCell.computeIfAbsent(cell, ignored -> new ArrayList<>());
			SolidCompositionRecord record = before.get(parcel.solidCompositionHandle());
			SettlementComposition match = null;
			for (SettlementComposition group : grouped) if (group.material == parcel.materialHandle()
					&& record.proportionalTo(group.prototype, parcel.solidVolumeSvq(), group.prototypeVolume)) {
				match = group; break;
			}
			if (match == null) {
				GranularDepositStore.Group existing = deposits.matchingGroup(cell, parcel.materialHandle(), group ->
						group.solidCompositionHandle() != 0 && record.proportionalTo(
								compositions.record(group.solidCompositionHandle()), parcel.solidVolumeSvq(), group.solidVolumeSvq()));
				int retained = existing == null ? parcel.solidCompositionHandle() : existing.solidCompositionHandle();
				if (existing != null && selectedHandles.contains(retained)) {
					throw new IllegalStateException("Settlement shares an active and deposit composition handle");
				}
				SolidCompositionRecord prototype = existing == null ? record : compositions.record(retained);
				if (existing != null) requireCompositionMass(prototype, existing.mass());
				match = new SettlementComposition(parcel.materialHandle(), retained, existing != null, prototype,
						existing == null ? parcel.solidVolumeSvq() : existing.solidVolumeSvq());
				grouped.add(match); if (existing != null) before.put(retained, prototype);
				if (grouped.size() > GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL) return null;
			} else if (!match.existing) match.retained = Math.min(match.retained, parcel.solidCompositionHandle());
			match.sourceSlots.add(slot); match.records.add(record);
		}
		for (var grouped : groupedByCell.values()) for (SettlementComposition group : grouped) {
			SolidCompositionRecord merged = combineSettlementComposition(group.records);
			if (merged == null) return null;
			compositions.requireOverwrite(group.retained, merged); after.put(group.retained, merged);
			for (int slot : group.sourceSlots) {
				targetHandle[slot] = group.retained;
				int handle = sources.get(slot).solidCompositionHandle(); if (handle != group.retained) released.add(handle);
			}
		}
		TreeMap<Integer, ArrayList<GranularDepositStore.SolidPortion>> additions = new TreeMap<>();
		TreeMap<Integer, SignedDyadic> addedMass = new TreeMap<>(); TreeMap<Integer, Long> addedSvq = new TreeMap<>();
		ArrayList<DepositTransfer> transfers = new ArrayList<>(indices.length);
		for (int slot = 0; slot < indices.length; slot++) {
			int cell = cells[slot];
			GranularParcelStore.Parcel parcel = sources.get(slot);
			additions.computeIfAbsent(cell, ignored -> new ArrayList<>()).add(new GranularDepositStore.SolidPortion(parcel.materialHandle(), targetHandle[slot],
					parcel.solidVolumeSvq(), parcel.mass()));
			addedMass.merge(cell, SignedDyadic.of(parcel.mass()), SignedDyadic::add);
			addedSvq.merge(cell, parcel.solidVolumeSvq(), Math::addExact);
			transfers.add(new DepositTransfer(indices[slot], cell, parcel.materialHandle(), targetHandle[slot]));
		}
		ArrayList<GranularDepositStore.CellAddition> receiving = new ArrayList<>(additions.size());
		for (var entry : additions.entrySet()) receiving.add(new GranularDepositStore.CellAddition(entry.getKey(), entry.getValue(), openByCell.get(entry.getKey())));
		GranularDepositStore.AdditionsBatchPlan depositPlan = deposits.planAdditions(receiving);
		if (depositPlan == null) return null;
		for (var cellPlan : depositPlan.cells()) for (GranularDepositStore.Group group : cellPlan.groups()) {
			SolidCompositionRecord composition = after.get(group.solidCompositionHandle());
			if (composition != null && !compositionMassClose(composition.totalMass(compositions.registry()), group.mass())) return null;
		}
		EnergyPlan energy = thermalEnergy == null ? null : thermalEnergy.planActiveToDeposits(List.copyOf(transfers));
		if (thermalEnergy != null && energy == null) return null;
		ArrayList<SettlementTarget> targets = new ArrayList<>(depositPlan.cells().size());
		for (var cellPlan : depositPlan.cells()) targets.add(new SettlementTarget(cellPlan.cell(), addedSvq.get(cellPlan.cell()),
				cellPlan.bulkVolumeSvq(), cellPlan.oldMass(), cellPlan.oldBulkVolumeSvq(), addedMass.get(cellPlan.cell())));
		return new SettlementPlan(this, indices, cells, targets, sources, totalSvq, depositPlan, before, after,
				released.stream().mapToInt(Integer::intValue).toArray(), energy);
	}

	private SolidCompositionRecord combineSettlementComposition(List<SolidCompositionRecord> records) {
		BigInteger totalQuanta = BigInteger.ZERO;
		for (SolidCompositionRecord record : records) totalQuanta = totalQuanta.add(BigInteger.valueOf(record.totalQuanta()));
		if (totalQuanta.compareTo(BigInteger.valueOf(ChemicalLimits.MAX_REGION_TOTAL_QUANTA)) > 0) return null;
		long[] amounts = new long[records.getFirst().entryCount()];
		SignedDyadic residual = SignedDyadic.ZERO;
		for (SolidCompositionRecord record : records) {
			for (int entry = 0; entry < amounts.length; entry++) amounts[entry] = Math.addExact(amounts[entry], record.amountAt(entry));
			residual = residual.add(SignedDyadic.of(record.massResidual()));
		}
		double storedResidual = residual.toDouble(); if (!Double.isFinite(storedResidual)) return null;
		SolidCompositionRecord combined = new SolidCompositionRecord(records.getFirst().copySpeciesHandles(), amounts,
				Double.doubleToRawLongBits(storedResidual));
		double mass = combined.totalMass(compositions.registry());
		return Double.isFinite(mass) && mass > 0 ? combined : null;
	}

	private void requireCompositionMass(SolidCompositionRecord record, double mass) {
		if (!compositionMassClose(record.totalMass(compositions.registry()), mass)) {
			throw new IllegalStateException("Settlement source composition does not match its mechanical mass");
		}
	}
	private static boolean compositionMassClose(double left, double right) {
		return Math.abs(left - right) <= Math.max(1.0E-9, Math.max(Math.ulp(left), Math.ulp(right)) * 8.0);
	}

	/** Checks every inventory, composition and thermal dependency before any participant is changed. */
	public void requireCurrent(SettlementPlan plan) {
		if (plan == null || plan.owner != this || plan.parcelRevision != parcels.mutationRevision()
				|| plan.pool != compositions || plan.adapter != thermalEnergy) {
			throw new IllegalStateException("Granular inventory changed after settlement planning");
		}
		deposits.requireCurrent(plan.additions);
		for (int slot = 0; slot < plan.indices.length; slot++) if (!parcels.parcel(plan.indices[slot]).equals(plan.parcels.get(slot))) {
			throw new IllegalStateException("Settlement source parcel changed after planning");
		}
		for (var entry : plan.before.entrySet()) if (!compositions.isLive(entry.getKey())
				|| !compositions.record(entry.getKey()).equals(entry.getValue())) {
			throw new IllegalStateException("Settlement composition changed after planning");
		}
		if (plan.energy != null) plan.energy.requireCurrent();
	}

	/** Commit follows the same descending swap removals in every ordinal-aligned side column. */
	public void commitSettlement(SettlementPlan plan) {
		requireCurrent(plan);
		deposits.commitAdditions(plan.additions);
		for (var entry : plan.after.entrySet()) compositions.overwrite(entry.getKey(), entry.getValue());
		for (int handle : plan.released) compositions.release(handle);
		for (int index : plan.indices) parcels.removeSwap(index);
		if (plan.energy != null) plan.energy.commit();
	}

	/** Bounded same-cell compaction for representative active parcels, including reactive composition ownership. */
	public CompactionStats compactActive(
			IntUnaryOperator cellOfParcel, Predicate<GranularParcelStore.Parcel> geometryValid,
			float maximumVelocityDelta, int maximumEvaluations
	) {
		if (geometryValid == null) throw new IllegalArgumentException("Active granular geometry predicate is missing");
		return compactActive(cellOfParcel, (first, second, merged) -> geometryValid.test(merged),
				maximumVelocityDelta, maximumEvaluations);
	}

	public CompactionStats compactActive(
			IntUnaryOperator cellOfParcel, ActiveMergeAcceptance mergeAcceptance,
			float maximumVelocityDelta, int maximumEvaluations
	) {
		if (cellOfParcel == null || mergeAcceptance == null || !(Float.isFinite(maximumVelocityDelta)
				&& maximumVelocityDelta >= 0.0F) || maximumEvaluations < 0) {
			throw new IllegalArgumentException("Active granular compaction input is invalid");
		}
		Arrays.fill(activeHeadByCell, -1); Arrays.fill(mergePaired, 0, parcels.count(), false);
		int pairs = 0, evaluations = 0;
		for (int second = 0; second < parcels.count() && evaluations < maximumEvaluations; second++) {
			GranularParcelStore.Parcel candidate = parcels.parcel(second);
			if (candidate.solidVolumeSvq() >= parcels.maximumActiveParcelSvq(candidate.materialHandle())) continue;
			int cell = cellOfParcel.applyAsInt(second);
			if (cell < 0 || cell >= activeHeadByCell.length) {
				throw new IllegalArgumentException("Active granular parcel maps outside its deposit grid");
			}
			for (int first = activeHeadByCell[cell]; first >= 0 && evaluations < maximumEvaluations;
					first = activeNext[first]) {
				evaluations++;
				if (mergePaired[first]) continue;
				ActiveMerge merge = activeMerge(first, second, maximumVelocityDelta);
				if (merge == null || !mergeAcceptance.test(parcels.parcel(first), candidate, merge.parcel())) continue;
				mergeFirst[pairs] = first; mergeSecond[pairs] = second; pairs++;
				plannedMerge[pairs - 1] = merge;
				mergePaired[first] = true; mergePaired[second] = true;
				break;
			}
			activeNext[second] = activeHeadByCell[cell]; activeHeadByCell[cell] = second;
		}
		for (int pair = pairs - 1; pair >= 0; pair--) {
			ActiveMerge merge = activeMerge(mergeFirst[pair], mergeSecond[pair], maximumVelocityDelta);
			if (merge == null || !merge.equals(plannedMerge[pair])) {
				throw new IllegalStateException("Active granular merge plan changed before commit");
			}
			if (thermalEnergy != null) thermalEnergy.requireActiveMerge(mergeFirst[pair], mergeSecond[pair]);
			if (merge.composition() != null) {
				compositions.overwrite(merge.parcel().solidCompositionHandle(), merge.composition());
				compositions.release(parcels.parcel(mergeSecond[pair]).solidCompositionHandle());
			}
			parcels.overwrite(mergeFirst[pair], merge.parcel());
			parcels.removeSwap(mergeSecond[pair]);
			if (thermalEnergy != null) thermalEnergy.commitActiveMerge(mergeFirst[pair], mergeSecond[pair]);
			plannedMerge[pair] = null;
		}
		return new CompactionStats(pairs, evaluations);
	}

	private ActiveMerge activeMerge(int firstIndex, int secondIndex, float maximumVelocityDelta) {
		GranularParcelStore.Parcel first = parcels.parcel(firstIndex), second = parcels.parcel(secondIndex);
		if (first.materialHandle() != second.materialHandle()) return null;
		long combinedSvq = Math.addExact(first.solidVolumeSvq(), second.solidVolumeSvq());
		if (combinedSvq > parcels.maximumActiveParcelSvq(first.materialHandle())) return null;
		float deltaX = first.velocityX() - second.velocityX(), deltaY = first.velocityY() - second.velocityY();
		float deltaZ = first.velocityZ() - second.velocityZ();
		if (deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
				> maximumVelocityDelta * maximumVelocityDelta) return null;
		SolidCompositionRecord combinedComposition = null;
		if (first.solidCompositionHandle() != 0 || second.solidCompositionHandle() != 0) {
			if (compositions == null || first.solidCompositionHandle() == 0 || second.solidCompositionHandle() == 0
					|| first.solidCompositionHandle() == second.solidCompositionHandle()) return null;
			SolidCompositionRecord left = compositions.record(first.solidCompositionHandle());
			SolidCompositionRecord right = compositions.record(second.solidCompositionHandle());
			if (!left.proportionalTo(right, first.solidVolumeSvq(), second.solidVolumeSvq())) return null;
			combinedComposition = left.plus(right);
		}
		double combinedMass = first.mass() + second.mass();
		GranularParcelStore.Parcel merged = new GranularParcelStore.Parcel(
				weighted(first.x(), first.mass(), second.x(), second.mass(), combinedMass),
				weighted(first.y(), first.mass(), second.y(), second.mass(), combinedMass),
				weighted(first.z(), first.mass(), second.z(), second.mass(), combinedMass),
				weighted(first.velocityX(), first.mass(), second.velocityX(), second.mass(), combinedMass),
				weighted(first.velocityY(), first.mass(), second.velocityY(), second.mass(), combinedMass),
				weighted(first.velocityZ(), first.mass(), second.velocityZ(), second.mass(), combinedMass),
				combinedSvq, combinedMass, first.materialHandle(), first.solidCompositionHandle(),
				0, (byte) ((first.flags() & second.flags()) & ~GranularParcelStore.FLAG_SUPPORTED));
		return new ActiveMerge(merged, combinedComposition);
	}

	private static float weighted(float left, double leftWeight, float right, double rightWeight, double total) {
		return (float) ((left * leftWeight + right * rightWeight) / total);
	}

	public boolean wake(
			int cell, int materialHandle, int compositionHandle, long requestedSvq,
			float x, float y, float z, float velocityX, float velocityY, float velocityZ
	) {
		GranularDepositStore.SolidPortion planned = deposits.peekTake(
				cell, materialHandle, compositionHandle, requestedSvq);
		GranularParcelStore.Parcel parcel = new GranularParcelStore.Parcel(x, y, z,
				velocityX, velocityY, velocityZ, planned.solidVolumeSvq(), planned.mass(),
				planned.materialHandle(), planned.solidCompositionHandle(), 0, (byte) 0);
		if (!parcels.canAdd(parcel)) return false;
		GranularDepositStore.Group source = deposits.matchingGroup(cell, materialHandle,
				group -> group.solidCompositionHandle() == compositionHandle);
		if (source == null) throw new IllegalStateException("Granular wake source changed before commit");
		if (thermalEnergy != null) thermalEnergy.requireDepositWake(cell, materialHandle, compositionHandle,
				planned.solidVolumeSvq(), source.solidVolumeSvq());
		GranularDepositStore.SolidPortion committed = deposits.take(
				cell, materialHandle, compositionHandle, requestedSvq);
		parcels.add(parcel);
		if (thermalEnergy != null) thermalEnergy.commitDepositWake(cell, materialHandle, compositionHandle,
				planned.solidVolumeSvq(), source.solidVolumeSvq());
		if (!committed.equals(planned)) throw new IllegalStateException("Granular wake plan changed before commit");
		return true;
	}

	public long totalSolidVolumeSvq() {
		return Math.addExact(parcels.totalSolidVolumeSvq(), deposits.totalSolidVolumeSvq());
	}

	public SignedDyadic exactMass() { return parcels.exactMass().add(deposits.exactMass()); }
	public double totalMass() { return exactMass().toDouble(); }

	/** Validates unique, complete composition ownership without changing either inventory store. */
	public void requireCanClear() {
		HashSet<Integer> compositionHandles = new HashSet<>();
		for (int index = 0; index < parcels.count(); index++) {
			int handle = parcels.parcel(index).solidCompositionHandle();
			if (handle != 0 && !compositionHandles.add(handle)) {
				throw new IllegalStateException("Granular clear found shared solid composition ownership");
			}
		}
		deposits.forEachGroup((cell, porosity, material, handle, volume, groupMass) -> {
			if (handle != 0 && !compositionHandles.add(handle)) {
				throw new IllegalStateException("Granular clear found shared solid composition ownership");
			}
		});
		if ((!compositionHandles.isEmpty() && compositions == null)
				|| (compositions != null && (compositionHandles.size() != compositions.liveCount()
				|| compositionHandles.stream().anyMatch(handle -> !compositions.isLive(handle))))) {
			throw new IllegalStateException("Granular clear found invalid solid composition ownership");
		}
	}

	/** Releases every active/deposit composition exactly once and clears both inventory stores. */
	public ClearStats clear() {
		requireCanClear();
		long solidVolumeSvq = totalSolidVolumeSvq(); double mass = totalMass();
		int activeParcels = parcels.count(), depositGroups = deposits.groupCount();
		while (parcels.count() > 0) {
			GranularParcelStore.Parcel removed = parcels.removeSwap(parcels.count() - 1);
			if (removed.solidCompositionHandle() != 0) compositions.release(removed.solidCompositionHandle());
		}
		deposits.clear((cell, porosity, material, handle, volume, groupMass) -> {
			if (handle != 0) compositions.release(handle);
		});
		if (compositions != null && compositions.liveCount() != 0) {
			throw new IllegalStateException("Granular composition pool did not clear");
		}
		if (thermalEnergy != null) thermalEnergy.clearExternal();
		return new ClearStats(solidVolumeSvq, mass, activeParcels, depositGroups);
	}

	public record ClearStats(long solidVolumeSvq, double mass, int activeParcels, int depositGroups) { }

	public record CompactionStats(int merges, int evaluations) { }
	@FunctionalInterface
	public interface ActiveMergeAcceptance {
		boolean test(GranularParcelStore.Parcel first, GranularParcelStore.Parcel second,
		             GranularParcelStore.Parcel merged);
	}
	public interface EnergyAdapter {
		EnergyPlan planActiveToDeposits(List<DepositTransfer> transfers);
		void requireActiveMerge(int first, int second);
		void commitActiveMerge(int first, int second);
		void requireDepositWake(int cell, int material, int composition, long takenSvq, long totalSvq);
		void commitDepositWake(int cell, int material, int composition, long takenSvq, long totalSvq);
		void clearExternal();
	}
	public record DepositTransfer(int active, int cell, int material, int composition) { }
	public interface EnergyPlan {
		void requireCurrent();
		void commit();
	}
	private record ActiveMerge(GranularParcelStore.Parcel parcel, SolidCompositionRecord composition) { }
}
