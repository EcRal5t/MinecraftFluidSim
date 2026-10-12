package mmm.granular;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import mmm.units.ProportionalDoubleSplit;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
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
	private final long[] bulkVolumeSvqByCell;
	private final int[] materialHandle, solidCompositionHandle, next, freeEntries;
	private final long[] solidVolumeSvq;
	private final double[] mass;
	private int nextUnused, freeCount, groupCount, occupiedCellCount;
	private long mutationRevision;

	public GranularDepositStore(GranularRegistry registry, int cellCount, int maximumGroups) {
		if (registry == null) throw new IllegalArgumentException("Granular registry is missing");
		if (cellCount <= 0) throw new IllegalArgumentException("Deposit cell count must be positive");
		if (maximumGroups <= 0 || maximumGroups > GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION) {
			throw new IllegalArgumentException("Deposit group capacity is invalid");
		}
		this.registry = registry;
		this.headByCell = new int[cellCount]; Arrays.fill(headByCell, -1);
		this.porosityQByCell = new int[cellCount];
		this.bulkVolumeSvqByCell = new long[cellCount];
		this.materialHandle = new int[maximumGroups]; this.solidCompositionHandle = new int[maximumGroups];
		this.solidVolumeSvq = new long[maximumGroups]; this.mass = new double[maximumGroups];
		this.next = new int[maximumGroups]; Arrays.fill(next, -1);
		this.freeEntries = new int[maximumGroups];
	}

	public int cellCount() { return headByCell.length; }
	public int groupCount() { return groupCount; }
	public int occupiedCellCount() { return occupiedCellCount; }
	/** Monotonic counter over every structural mutation; plan consumers re-verify it before commit. */
	public long mutationRevision() { return mutationRevision; }
	public int porosityQ(int cell) { requireCell(cell); return porosityQByCell[cell]; }
	public long cellBulkVolumeSvq(int cell) {
		requireCell(cell);
		return bulkVolumeSvqByCell[cell];
	}

	/** Derived bulk height for an uncommitted add; no sparse inventory is changed. */
	public long previewCellBulkVolumeAfterAdd(int cell, SolidPortion portion) {
		requireCell(cell);
		validate(portion);
		return packing(cell, portion).bulkVolumeSvq();
	}

	/** Exact mixed packing after a whole-group or inert partial take; inventory stays unchanged. */
	public long previewCellBulkVolumeAfterTake(int cell, int material, int composition, long requestedSvq) {
		SolidPortion removed = peekTake(cell, material, composition, requestedSvq);
		return packing(cell, null, removed).bulkVolumeSvq();
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
		if (newVolume > geometricOpenSvq) return false;
		CellPacking plannedPacking = packing(cell, portion);
		if (plannedPacking.bulkVolumeSvq() > geometricOpenSvq) return false;

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
		porosityQByCell[cell] = plannedPacking.porosityQ();
		bulkVolumeSvqByCell[cell] = plannedPacking.bulkVolumeSvq();
		mutationRevision++;
		return true;
	}

	/** Immutable complete in-cell addition; all group and packing limits are checked together. */
	public static final class AdditionsPlan {
		private final GranularDepositStore owner;
		private final long revision;
		private final int cell;
		private final List<Group> groups;
		private final int[] entries;
		private final CellPacking packing;
		private final SignedDyadic massStorageDelta, massStorageBound;
		private final SignedDyadic oldMass;
		private final long oldBulk;
		private AdditionsPlan(GranularDepositStore owner, int cell, List<Group> groups, int[] entries,
		                      CellPacking packing, SignedDyadic delta, SignedDyadic bound, SignedDyadic oldMass) {
			this.owner = owner; this.revision = owner.mutationRevision; this.cell = cell;
			this.groups = List.copyOf(groups); this.entries = entries; this.packing = packing;
			this.massStorageDelta = delta; this.massStorageBound = bound;
			this.oldMass = oldMass; this.oldBulk = owner.cellBulkVolumeSvq(cell);
		}
		public int cell() { return cell; }
		public SignedDyadic oldMass() { return oldMass; }
		public long oldBulkVolumeSvq() { return oldBulk; }
		public List<Group> groups() { return groups; }
		public long bulkVolumeSvq() { return packing.bulkVolumeSvq(); }
		public SignedDyadic massStorageDelta() { return massStorageDelta; }
		public SignedDyadic massStorageBound() { return massStorageBound; }
	}

	public record CellAddition(int cell, List<SolidPortion> incoming, long geometricOpenSvq) {
		public CellAddition { incoming = List.copyOf(incoming); }
	}
	/** All receiving cells share one region group preflight and one commit revision. */
	public static final class AdditionsBatchPlan {
		private final GranularDepositStore owner;
		private final List<AdditionsPlan> cells;
		private final SignedDyadic delta, bound;
		private AdditionsBatchPlan(GranularDepositStore owner, List<AdditionsPlan> cells) {
			this.owner = owner; this.cells = List.copyOf(cells);
			SignedDyadic delta = SignedDyadic.ZERO, bound = SignedDyadic.ZERO;
			for (var cell : cells) { delta = delta.add(cell.massStorageDelta()); bound = bound.add(cell.massStorageBound()); }
			this.delta = delta; this.bound = bound;
		}
		public List<AdditionsPlan> cells() { return cells; }
		public SignedDyadic massStorageDelta() { return delta; }
		public SignedDyadic massStorageBound() { return bound; }
	}

	public AdditionsBatchPlan planAdditions(List<CellAddition> incoming) {
		if (incoming == null || incoming.isEmpty()) throw new IllegalArgumentException("Deposit receiving cells are missing");
		TreeMap<Integer, CellAddition> sorted = new TreeMap<>();
		for (var cell : incoming) if (sorted.putIfAbsent(cell.cell(), cell) != null) {
			throw new IllegalArgumentException("Deposit receiving cell is duplicated");
		}
		ArrayList<AdditionsPlan> cells = new ArrayList<>(sorted.size()); int newGroups = 0;
		for (var cell : sorted.values()) {
			var plan = planCellAdditions(cell.cell(), cell.incoming(), cell.geometricOpenSvq());
			if (plan == null) return null;
			for (int entry : plan.entries) if (entry < 0) newGroups++;
			if (newGroups > materialHandle.length - groupCount) return null;
			cells.add(plan);
		}
		return new AdditionsBatchPlan(this, cells);
	}

	private record GroupKey(int material, int composition) implements Comparable<GroupKey> {
		@Override public int compareTo(GroupKey other) {
			int order = Integer.compare(material, other.material);
			return order == 0 ? Integer.compare(composition, other.composition) : order;
		}
	}
	private static final class Addition {
		final int entry;
		long volume;
		SignedDyadic mass;
		Addition(int entry, long volume, double mass) {
			this.entry = entry; this.volume = volume; this.mass = SignedDyadic.of(mass);
		}
	}

	/** Capacity-limited candidates return null without changing sparse entries or revisions. */
	public AdditionsPlan planAdditions(int cell, List<SolidPortion> incoming, long geometricOpenSvq) {
		var plan = planAdditions(List.of(new CellAddition(cell, incoming, geometricOpenSvq)));
		return plan == null ? null : plan.cells.getFirst();
	}
	private AdditionsPlan planCellAdditions(int cell, List<SolidPortion> incoming, long geometricOpenSvq) {
		requireCell(cell);
		if (incoming == null || incoming.isEmpty() || geometricOpenSvq <= 0
				|| geometricOpenSvq > SolidVolume.SVQ_PER_CELL) {
			throw new IllegalArgumentException("Deposit additions are missing or capacity is invalid");
		}
		TreeMap<GroupKey, Addition> combined = new TreeMap<>();
		SignedDyadic oldMass = SignedDyadic.ZERO;
		for (int entry = headByCell[cell]; entry >= 0; entry = next[entry]) {
			oldMass = oldMass.add(SignedDyadic.of(mass[entry]));
			combined.put(new GroupKey(materialHandle[entry], solidCompositionHandle[entry]),
					new Addition(entry, solidVolumeSvq[entry], mass[entry]));
		}
		for (SolidPortion portion : incoming) {
			validate(portion);
			GroupKey key = new GroupKey(portion.materialHandle(), portion.solidCompositionHandle());
			Addition addition = combined.get(key);
			if (addition == null) combined.put(key, new Addition(-1, portion.solidVolumeSvq(), portion.mass()));
			else {
				addition.volume = Math.addExact(addition.volume, portion.solidVolumeSvq());
				addition.mass = addition.mass.add(SignedDyadic.of(portion.mass()));
			}
		}
		if (combined.size() > GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL) return null;
		int addedGroups = 0, index = 0;
		long skeleton = 0;
		ExactRational bulk = ExactRational.ZERO;
		SignedDyadic delta = SignedDyadic.ZERO, bound = SignedDyadic.ZERO;
		ArrayList<Group> groups = new ArrayList<>(combined.size());
		int[] entries = new int[combined.size()];
		for (var entry : combined.entrySet()) {
			GroupKey key = entry.getKey(); Addition addition = entry.getValue();
			double storedMass = addition.mass.toDouble();
			if (!(Double.isFinite(storedMass) && storedMass > 0.0)) return null;
			SignedDyadic groupDelta = SignedDyadic.of(storedMass).subtract(addition.mass);
			SignedDyadic ulp = SignedDyadic.of(Math.ulp(storedMass));
			SignedDyadic groupBound = new SignedDyadic(ulp.significand(), ulp.exponent() - 1);
			if ((groupDelta.signum() < 0 ? groupDelta.negate() : groupDelta).compareTo(groupBound) > 0) {
				throw new IllegalStateException("Deposit mass exceeds one-rounding storage error");
			}
			delta = delta.add(groupDelta); bound = bound.add(groupBound);
			entries[index++] = addition.entry;
			if (addition.entry < 0) addedGroups++;
			skeleton = Math.addExact(skeleton, addition.volume);
			bulk = bulk.add(restBulk(addition.volume, key.material));
			groups.add(new Group(key.material, key.composition, addition.volume, storedMass));
		}
		if (addedGroups > materialHandle.length - groupCount || skeleton > geometricOpenSvq) return null;
		CellPacking packing = mixedPacking(skeleton, bulk);
		if (packing.bulkVolumeSvq() > geometricOpenSvq) return null;
		return new AdditionsPlan(this, cell, groups, entries, packing, delta, bound, oldMass);
	}

	public void requireCurrent(AdditionsPlan plan) {
		if (plan == null || plan.owner != this || plan.revision != mutationRevision) {
			throw new IllegalStateException("Deposit inventory changed after addition planning");
		}
	}

	/** Applies only previously checked group values and the single final mixed packing. */
	public void commitAdditions(AdditionsPlan plan) {
		commitAdditions(new AdditionsBatchPlan(this, List.of(plan)));
	}
	public void requireCurrent(AdditionsBatchPlan plan) {
		if (plan == null || plan.owner != this) throw new IllegalStateException("Deposit addition plan owner changed");
		for (var cell : plan.cells) requireCurrent(cell);
	}
	public void commitAdditions(AdditionsBatchPlan plan) {
		requireCurrent(plan);
		for (var cell : plan.cells) applyCheckedAdditions(cell);
		mutationRevision++;
	}
	private void applyCheckedAdditions(AdditionsPlan plan) {
		boolean wasEmpty = headByCell[plan.cell] < 0;
		for (int index = 0; index < plan.groups.size(); index++) {
			Group group = plan.groups.get(index);
			int entry = plan.entries[index];
			if (entry < 0) {
				entry = allocateEntry();
				materialHandle[entry] = group.materialHandle();
				solidCompositionHandle[entry] = group.solidCompositionHandle();
				insertCanonical(plan.cell, entry); groupCount++;
			}
			solidVolumeSvq[entry] = group.solidVolumeSvq(); mass[entry] = group.mass();
		}
		if (wasEmpty) occupiedCellCount++;
		porosityQByCell[plan.cell] = plan.packing.porosityQ();
		bulkVolumeSvqByCell[plan.cell] = plan.packing.bulkVolumeSvq();
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
		CellPacking remainingPacking = packing(cell, null, result);
		requireCell(cell);
		int previous = -1, entry = headByCell[cell];
		while (entry >= 0 && (materialHandle[entry] != material || solidCompositionHandle[entry] != composition)) {
			previous = entry; entry = next[entry];
		}
		if (entry < 0) throw new IllegalArgumentException("Unknown deposit material group");
		if (result.solidVolumeSvq() == solidVolumeSvq[entry]) {
			unlinkAndRelease(cell, previous, entry);
		} else {
			double remainingMass = ProportionalDoubleSplit.of(mass[entry], result.solidVolumeSvq(), solidVolumeSvq[entry]).remaining();
			solidVolumeSvq[entry] -= result.solidVolumeSvq();
			mass[entry] = remainingMass;
		}
		porosityQByCell[cell] = remainingPacking.porosityQ();
		bulkVolumeSvqByCell[cell] = remainingPacking.bulkVolumeSvq();
		mutationRevision++;
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
		ProportionalDoubleSplit split = ProportionalDoubleSplit.of(mass[entry], takenVolume, solidVolumeSvq[entry]);
		double takenMass = split.removed();
		if (!(takenMass > 0.0 && Double.isFinite(takenMass))) {
			throw new IllegalArgumentException("Deposit partial mass is not representable");
		}
		if (takenVolume != solidVolumeSvq[entry] && !(split.remaining() > 0.0)) {
			throw new IllegalArgumentException("Remaining deposit mass is not representable");
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
		return exactMass().toDouble();
	}

	/** Order-independent exact aggregate; released sparse entries are maintained as positive zero. */
	public SignedDyadic exactMass() { return SignedDyadic.sum(mass, nextUnused); }

	public void forEachCell(CellConsumer consumer) {
		if (consumer == null) throw new IllegalArgumentException("Deposit cell consumer is missing");
		for (int cell = 0; cell < headByCell.length; cell++) {
			long volume = cellSolidVolumeSvq(cell);
			if (volume > 0L) consumer.accept(cell, volume, porosityQByCell[cell]);
		}
	}

	public void forEachGroup(GroupConsumer consumer) {
		if (consumer == null) throw new IllegalArgumentException("Deposit group consumer is missing");
		if (groupCount == 0) return;
		for (int cell = 0; cell < headByCell.length; cell++) {
			for (int entry = headByCell[cell]; entry >= 0; entry = next[entry]) {
				consumer.accept(cell, porosityQByCell[cell], materialHandle[entry], solidCompositionHandle[entry],
						solidVolumeSvq[entry], mass[entry]);
			}
		}
	}

	/** Visits the canonical sparse groups owned by one cell without scanning unrelated cells. */
	public void forEachGroupInCell(int cell, GroupConsumer consumer) {
		requireCell(cell);
		if (consumer == null) throw new IllegalArgumentException("Deposit group consumer is missing");
		for (int entry = headByCell[cell]; entry >= 0; entry = next[entry]) {
			consumer.accept(cell, porosityQByCell[cell], materialHandle[entry], solidCompositionHandle[entry],
					solidVolumeSvq[entry], mass[entry]);
		}
	}

	/** Clears the complete sparse store after reporting every exact group to its inventory owner. */
	public void clear(GroupConsumer removed) {
		if (removed == null) throw new IllegalArgumentException("Deposit clear consumer is missing");
		forEachGroup(removed);
		Arrays.fill(headByCell, -1); Arrays.fill(porosityQByCell, 0);
		Arrays.fill(bulkVolumeSvqByCell, 0L);
		Arrays.fill(materialHandle, 0); Arrays.fill(solidCompositionHandle, 0);
		Arrays.fill(solidVolumeSvq, 0L); Arrays.fill(mass, 0.0); Arrays.fill(next, -1);
		nextUnused = 0; freeCount = 0; groupCount = 0; occupiedCellCount = 0;
		mutationRevision++;
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

	private record CellPacking(long bulkVolumeSvq, int porosityQ) { }

	/** Independent rest bulk volumes add exactly; rounding porosity never sets the bed height. */
	private CellPacking packing(int cell, SolidPortion addition) {
		return packing(cell, addition, null);
	}
	private CellPacking packing(int cell, SolidPortion addition, SolidPortion removal) {
		ExactRational bulk = ExactRational.ZERO;
		long skeleton = 0L;
		for (int entry = headByCell[cell]; entry >= 0; entry = next[entry]) {
			long volume = solidVolumeSvq[entry];
			if (removal != null && materialHandle[entry] == removal.materialHandle()
					&& solidCompositionHandle[entry] == removal.solidCompositionHandle()) volume -= removal.solidVolumeSvq();
			skeleton = Math.addExact(skeleton, volume);
			bulk = bulk.add(restBulk(volume, materialHandle[entry]));
		}
		if (addition != null) {
			skeleton = Math.addExact(skeleton, addition.solidVolumeSvq());
			bulk = bulk.add(restBulk(addition.solidVolumeSvq(), addition.materialHandle()));
		}
		return mixedPacking(skeleton, bulk);
	}

	private static CellPacking mixedPacking(long skeleton, ExactRational bulk) {
		if (skeleton == 0L) return new CellPacking(0L, 0);
		BigInteger numerator = bulk.numerator(), denominator = bulk.denominator();
		long ceilBulk = numerator.add(denominator.subtract(BigInteger.ONE)).divide(denominator).longValueExact();
		BigInteger poreNumerator = numerator.subtract(denominator.multiply(BigInteger.valueOf(skeleton)))
				.multiply(BigInteger.valueOf(GranularLimits.POROSITY_SCALE));
		int porosity = poreNumerator.add(numerator.divide(BigInteger.TWO)).divide(numerator).intValueExact();
		return new CellPacking(ceilBulk, porosity);
	}

	private ExactRational restBulk(long skeleton, int material) {
		return ExactRational.of(skeleton).multiply(GranularLimits.POROSITY_SCALE)
				.divide(GranularLimits.POROSITY_SCALE - registry.material(material).restPorosityQ());
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
