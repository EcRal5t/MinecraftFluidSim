package com.example.granular;

import java.util.Arrays;
import java.util.function.Predicate;

/** Sparse primitive deposit inventory. Bulk volume is derived; only skeleton SVQ and mass are conserved here. */
public final class GranularDepositStore {
	public record SolidPortion(int materialHandle, int solidCompositionHandle, long solidVolumeSvq, double mass) {
		public SolidPortion {
			if (materialHandle < 0 || solidCompositionHandle < 0 || solidVolumeSvq <= 0L
					|| !(Double.isFinite(mass) && mass > 0.0)) {
				throw new IllegalArgumentException("Invalid granular solid portion");
			}
		}
	}
	public record Group(int materialHandle, int solidCompositionHandle, long solidVolumeSvq, double mass) { }

	private final GranularRegistry registry;
	private final int[] headByCell, porosityQByCell;
	private final int[] materialHandle, solidCompositionHandle, next, freeEntries;
	private final long[] solidVolumeSvq;
	private final double[] mass;
	private int nextUnused, freeCount, groupCount, occupiedCellCount;

	public GranularDepositStore(GranularRegistry registry, int cellCount, int maximumGroups) {
		if (registry == null) throw new IllegalArgumentException("Granular registry is missing");
		if (cellCount <= 0) throw new IllegalArgumentException("Deposit cell count must be positive");
		if (maximumGroups <= 0 || maximumGroups > GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION) {
			throw new IllegalArgumentException("Deposit group capacity is invalid");
		}
		this.registry = registry;
		this.headByCell = new int[cellCount]; Arrays.fill(headByCell, -1);
		this.porosityQByCell = new int[cellCount];
		this.materialHandle = new int[maximumGroups]; this.solidCompositionHandle = new int[maximumGroups];
		this.solidVolumeSvq = new long[maximumGroups]; this.mass = new double[maximumGroups];
		this.next = new int[maximumGroups]; Arrays.fill(next, -1);
		this.freeEntries = new int[maximumGroups];
	}

	public int cellCount() { return headByCell.length; }
	public int groupCount() { return groupCount; }
	public int occupiedCellCount() { return occupiedCellCount; }
	public int porosityQ(int cell) { requireCell(cell); return porosityQByCell[cell]; }
	public long cellBulkVolumeSvq(int cell) {
		long skeleton = cellSolidVolumeSvq(cell);
		return skeleton == 0L ? 0L : SolidVolume.bulkVolumeCeil(skeleton, porosityQByCell[cell]);
	}

	public Group dominantGroup(int cell) {
		requireCell(cell);
		int best = -1;
		for (int entry = headByCell[cell]; entry >= 0; entry = next[entry]) {
			if (best < 0 || solidVolumeSvq[entry] > solidVolumeSvq[best]
					|| solidVolumeSvq[entry] == solidVolumeSvq[best] && compare(entry, best) < 0) best = entry;
		}
		return best < 0 ? null : new Group(materialHandle[best], solidCompositionHandle[best],
				solidVolumeSvq[best], mass[best]);
	}

	/** Finds one canonical in-cell group without exposing the sparse entry representation. */
	public Group matchingGroup(int cell, int material, Predicate<Group> predicate) {
		requireCell(cell);
		if (predicate == null) throw new IllegalArgumentException("Deposit group predicate is missing");
		registry.material(material);
		for (int entry = headByCell[cell]; entry >= 0; entry = next[entry]) {
			if (materialHandle[entry] != material) continue;
			Group group = new Group(materialHandle[entry], solidCompositionHandle[entry],
					solidVolumeSvq[entry], mass[entry]);
			if (predicate.test(group)) return group;
		}
		return null;
	}

	public boolean tryMove(
			int fromCell, int toCell, int material, int composition, long requestedSvq, long targetOpenSvq
	) {
		if (fromCell == toCell) throw new IllegalArgumentException("Deposit move requires two cells");
		SolidPortion planned;
		try { planned = peekTake(fromCell, material, composition, requestedSvq); }
		catch (IllegalArgumentException unavailable) { return false; }
		if (!tryAdd(toCell, planned, targetOpenSvq)) return false;
		SolidPortion removed = take(fromCell, material, composition, requestedSvq);
		if (!removed.equals(planned)) throw new IllegalStateException("Deposit move plan changed before commit");
		return true;
	}

	public boolean tryAdd(int cell, SolidPortion portion, long geometricOpenSvq) {
		requireCell(cell);
		validate(portion);
		if (geometricOpenSvq <= 0L || geometricOpenSvq > SolidVolume.SVQ_PER_CELL) {
			throw new IllegalArgumentException("Deposit geometric capacity is invalid");
		}
		long oldVolume = cellSolidVolumeSvq(cell);
		long newVolume = Math.addExact(oldVolume, portion.solidVolumeSvq());
		int newPorosity = porosityAfterAdding(cell, portion, newVolume);
		if (newVolume > geometricOpenSvq || SolidVolume.bulkVolumeCeil(newVolume, newPorosity) > geometricOpenSvq) return false;

		int existing = find(cell, portion.materialHandle(), portion.solidCompositionHandle());
		if (existing < 0 && (groupsInCell(cell) >= GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL
				|| groupCount == materialHandle.length)) return false;
		if (existing >= 0) {
			double newMass = mass[existing] + portion.mass();
			if (!Double.isFinite(newMass)) throw new ArithmeticException("Deposit mass overflow");
			solidVolumeSvq[existing] = Math.addExact(solidVolumeSvq[existing], portion.solidVolumeSvq());
			mass[existing] = newMass;
		} else {
			int entry = allocateEntry();
			materialHandle[entry] = portion.materialHandle();
			solidCompositionHandle[entry] = portion.solidCompositionHandle();
			solidVolumeSvq[entry] = portion.solidVolumeSvq(); mass[entry] = portion.mass();
			insertCanonical(cell, entry);
			groupCount++;
			if (oldVolume == 0L) occupiedCellCount++;
		}
		porosityQByCell[cell] = newPorosity;
		return true;
	}

	/** Removes up to requested SVQ from one exact group, preserving floating mass by remainder. */
	public SolidPortion take(int cell, int material, int composition, long requestedSvq) {
		return takeInternal(cell, material, composition, requestedSvq, false);
	}

	/** Caller owns the corresponding composition split plan and commits it in the same transaction. */
	public SolidPortion takeCompositionAware(int cell, int material, int composition, long requestedSvq) {
		return takeInternal(cell, material, composition, requestedSvq, true);
	}

	private SolidPortion takeInternal(int cell, int material, int composition, long requestedSvq, boolean allowReactivePartial) {
		SolidPortion result = peekTakeInternal(cell, material, composition, requestedSvq, allowReactivePartial);
		requireCell(cell);
		int previous = -1, entry = headByCell[cell];
		while (entry >= 0 && (materialHandle[entry] != material || solidCompositionHandle[entry] != composition)) {
			previous = entry; entry = next[entry];
		}
		if (entry < 0) throw new IllegalArgumentException("Unknown deposit material group");
		if (result.solidVolumeSvq() == solidVolumeSvq[entry]) {
			unlinkAndRelease(cell, previous, entry);
		} else {
			solidVolumeSvq[entry] -= result.solidVolumeSvq();
			mass[entry] -= result.mass();
		}
		recomputePorosity(cell);
		return result;
	}

	public SolidPortion peekTake(int cell, int material, int composition, long requestedSvq) {
		return peekTakeInternal(cell, material, composition, requestedSvq, false);
	}

	public SolidPortion peekTakeCompositionAware(int cell, int material, int composition, long requestedSvq) {
		return peekTakeInternal(cell, material, composition, requestedSvq, true);
	}

	private SolidPortion peekTakeInternal(
			int cell, int material, int composition, long requestedSvq, boolean allowReactivePartial
	) {
		requireCell(cell);
		if (requestedSvq <= 0L) throw new IllegalArgumentException("Requested deposit volume must be positive");
		int entry = find(cell, material, composition);
		if (entry < 0) throw new IllegalArgumentException("Unknown deposit material group");
		long takenVolume = Math.min(requestedSvq, solidVolumeSvq[entry]);
		if (!allowReactivePartial && composition != 0 && takenVolume != solidVolumeSvq[entry]) {
			throw new IllegalArgumentException("Reactive deposit requires a composition-aware split plan");
		}
		double takenMass = takenVolume == solidVolumeSvq[entry] ? mass[entry]
				: mass[entry] * ((double) takenVolume / solidVolumeSvq[entry]);
		if (!(takenMass > 0.0 && Double.isFinite(takenMass))) {
			throw new IllegalArgumentException("Deposit partial mass is not representable");
		}
		return new SolidPortion(material, composition, takenVolume, takenMass);
	}

	public long cellSolidVolumeSvq(int cell) {
		requireCell(cell);
		long total = 0L;
		for (int entry = headByCell[cell]; entry >= 0; entry = next[entry]) {
			total = Math.addExact(total, solidVolumeSvq[entry]);
		}
		return total;
	}

	public long totalSolidVolumeSvq() {
		long total = 0L;
		for (int cell = 0; cell < headByCell.length; cell++) total = Math.addExact(total, cellSolidVolumeSvq(cell));
		return total;
	}

	public double totalMass() {
		double total = 0.0;
		for (int entry = 0; entry < nextUnused; entry++) if (solidVolumeSvq[entry] > 0L) total += mass[entry];
		return total;
	}

	public void forEachCell(CellConsumer consumer) {
		if (consumer == null) throw new IllegalArgumentException("Deposit cell consumer is missing");
		for (int cell = 0; cell < headByCell.length; cell++) {
			long volume = cellSolidVolumeSvq(cell);
			if (volume > 0L) consumer.accept(cell, volume, porosityQByCell[cell]);
		}
	}

	public void forEachGroup(GroupConsumer consumer) {
		if (consumer == null) throw new IllegalArgumentException("Deposit group consumer is missing");
		for (int cell = 0; cell < headByCell.length; cell++) {
			for (int entry = headByCell[cell]; entry >= 0; entry = next[entry]) {
				consumer.accept(cell, porosityQByCell[cell], materialHandle[entry], solidCompositionHandle[entry],
						solidVolumeSvq[entry], mass[entry]);
			}
		}
	}

	private void validate(SolidPortion portion) {
		if (portion == null) throw new IllegalArgumentException("Granular solid portion is missing");
		ParticulateMaterial material = registry.material(portion.materialHandle());
		if (!material.reactive() && portion.solidCompositionHandle() != 0) {
			throw new IllegalArgumentException("Inert deposit cannot carry solid composition");
		}
		if (material.reactive() && portion.solidCompositionHandle() == 0) {
			throw new IllegalArgumentException("Reactive deposit requires solid composition");
		}
	}

	private int allocateEntry() {
		if (freeCount > 0) return freeEntries[--freeCount];
		if (nextUnused == materialHandle.length) throw new IllegalStateException("Deposit group budget exhausted after planning");
		return nextUnused++;
	}

	private void insertCanonical(int cell, int inserted) {
		int previous = -1, current = headByCell[cell];
		while (current >= 0 && compare(current, inserted) < 0) { previous = current; current = next[current]; }
		next[inserted] = current;
		if (previous < 0) headByCell[cell] = inserted; else next[previous] = inserted;
	}

	private int compare(int left, int right) {
		int material = Integer.compare(materialHandle[left], materialHandle[right]);
		return material != 0 ? material : Integer.compare(solidCompositionHandle[left], solidCompositionHandle[right]);
	}

	private int find(int cell, int material, int composition) {
		for (int entry = headByCell[cell]; entry >= 0; entry = next[entry]) {
			if (materialHandle[entry] == material && solidCompositionHandle[entry] == composition) return entry;
		}
		return -1;
	}

	private int groupsInCell(int cell) {
		int count = 0;
		for (int entry = headByCell[cell]; entry >= 0; entry = next[entry]) count++;
		return count;
	}

	private void unlinkAndRelease(int cell, int previous, int entry) {
		if (previous < 0) headByCell[cell] = next[entry]; else next[previous] = next[entry];
		materialHandle[entry] = solidCompositionHandle[entry] = 0;
		solidVolumeSvq[entry] = 0L; mass[entry] = 0.0; next[entry] = -1;
		freeEntries[freeCount++] = entry; groupCount--;
		if (headByCell[cell] < 0) { porosityQByCell[cell] = 0; occupiedCellCount--; }
	}

	private void recomputePorosity(int cell) {
		long total = cellSolidVolumeSvq(cell);
		if (total == 0L) { porosityQByCell[cell] = 0; return; }
		long weighted = 0L;
		for (int entry = headByCell[cell]; entry >= 0; entry = next[entry]) {
			weighted = Math.addExact(weighted, Math.multiplyExact(solidVolumeSvq[entry],
					registry.material(materialHandle[entry]).restPorosityQ()));
		}
		porosityQByCell[cell] = (int) ((weighted + total / 2L) / total);
	}

	private int porosityAfterAdding(int cell, SolidPortion portion, long total) {
		long weighted = Math.multiplyExact(portion.solidVolumeSvq(),
				registry.material(portion.materialHandle()).restPorosityQ());
		for (int entry = headByCell[cell]; entry >= 0; entry = next[entry]) {
			weighted = Math.addExact(weighted, Math.multiplyExact(solidVolumeSvq[entry],
					registry.material(materialHandle[entry]).restPorosityQ()));
		}
		return (int) ((weighted + total / 2L) / total);
	}

	private void requireCell(int cell) {
		if (cell < 0 || cell >= headByCell.length) throw new IllegalArgumentException("Unknown deposit cell " + cell);
	}

	@FunctionalInterface
	public interface CellConsumer {
		void accept(int cell, long solidVolumeSvq, int porosityQ);
	}

	@FunctionalInterface
	public interface GroupConsumer {
		void accept(int cell, int porosityQ, int materialHandle, int solidCompositionHandle,
		            long solidVolumeSvq, double mass);
	}
}
