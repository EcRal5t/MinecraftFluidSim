package mmm.thermal;

import mmm.units.SignedDyadic;
import mmm.units.ProportionalDoubleSplit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mmm.granular.GranularInventory.DepositTransfer;

/** Orthogonal raw-U column aligned with active granular ordinals and canonical deposit identities. */
public final class GranularEnergyColumn {
	public record DepositKey(int cell, int materialHandle, int compositionHandle)
			implements Comparable<DepositKey> {
		public DepositKey {
			if (cell < 0 || materialHandle < 0 || compositionHandle < 0) {
				throw new IllegalArgumentException("Granular thermal deposit key is invalid");
			}
		}
		@Override public int compareTo(DepositKey other) {
			int order = Integer.compare(cell, other.cell);
			if (order == 0) order = Integer.compare(materialHandle, other.materialHandle);
			if (order == 0) order = Integer.compare(compositionHandle, other.compositionHandle);
			return order;
		}
	}

	public record DepositEnergy(DepositKey key, double internalEnergy) {
		public DepositEnergy {
			if (key == null || !Double.isFinite(internalEnergy)) {
				throw new IllegalArgumentException("Granular deposit energy is invalid");
			}
		}
	}

	private final int maximumActive, maximumDeposits;
	private double[] activeEnergy;
	private int activeCount;
	private final TreeMap<DepositKey, Double> depositEnergy = new TreeMap<>();

	public GranularEnergyColumn(int maximumActive, int maximumDeposits) {
		if (maximumActive <= 0 || maximumActive > mmm.granular.GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION
				|| maximumDeposits <= 0
				|| maximumDeposits > mmm.granular.GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION) {
			throw new IllegalArgumentException("Granular thermal column budget is invalid");
		}
		this.maximumActive = maximumActive; this.maximumDeposits = maximumDeposits;
		this.activeEnergy = new double[Math.min(maximumActive, 16)];
	}

	public int activeCount() { return activeCount; }
	public int depositCount() { return depositEnergy.size(); }
	public double activeEnergy(int index) { requireActive(index); return activeEnergy[index]; }
	public double depositEnergy(DepositKey key) {
		Double energy = depositEnergy.get(key);
		if (energy == null) throw new IllegalArgumentException("Unknown granular thermal deposit group");
		return energy;
	}
	void setActiveEnergy(int index, double energy) {
		requireActive(index); requireEnergy(energy); activeEnergy[index] = energy;
	}
	void setDepositEnergy(DepositKey key, double energy) {
		requireEnergy(energy);
		if (!depositEnergy.containsKey(key)) throw new IllegalArgumentException("Unknown granular thermal deposit group");
		depositEnergy.put(key, energy);
	}

	int addActive(double energy) {
		requireAddActive(energy);
		ensureActiveCapacity(activeCount + 1);
		activeEnergy[activeCount] = energy;
		return activeCount++;
	}
	void requireAddActive(double energy) {
		requireEnergy(energy);
		if (activeCount == maximumActive) throw new IllegalStateException("Granular thermal active budget exceeded");
	}

	/** Mirrors granular swap removal and returns the removed carrier energy. */
	double removeActiveSwap(int index) {
		requireActive(index);
		double removed = activeEnergy[index]; int last = --activeCount;
		if (index != last) activeEnergy[index] = activeEnergy[last];
		activeEnergy[last] = 0.0;
		return removed;
	}

	/** Adds the second energy into the first, then mirrors the granular second-index swap removal. */
	void mergeActive(int first, int second) {
		double merged = requireMergeActive(first, second);
		int last = activeCount - 1;
		removeActiveSwap(second);
		if (first == last && second != last) activeEnergy[second] = merged;
		else activeEnergy[first] = merged;
	}
	double requireMergeActive(int first, int second) {
		requireActive(first); requireActive(second);
		if (first == second) throw new IllegalArgumentException("Cannot merge one granular thermal ordinal with itself");
		return finiteAdd(activeEnergy[first], activeEnergy[second]);
	}

	/** Proportionally removes energy without changing the active ordinal cardinality. */
	double takeActivePortion(int index, long takenSvq, long totalSvq) {
		requireActive(index);
		if (takenSvq == totalSvq) throw new IllegalArgumentException("Full active take must use swap removal");
		ProportionalDoubleSplit split = proportionalSplit(activeEnergy[index], takenSvq, totalSvq);
		activeEnergy[index] = split.remaining();
		return split.removed();
	}
	double previewActiveTake(int index, long takenSvq, long totalSvq) {
		requireActive(index);
		return proportionalSplit(activeEnergy[index], takenSvq, totalSvq).removed();
	}
	public double previewActiveRemaining(int index, long takenSvq, long totalSvq) {
		requireActive(index);
		return proportionalSplit(activeEnergy[index], takenSvq, totalSvq).remaining();
	}

	void addDeposit(DepositKey key, double energy) {
		requireAddDeposit(key, energy);
		Double current = depositEnergy.get(key);
		if (current == null) {
			if (depositEnergy.size() == maximumDeposits) {
				throw new IllegalStateException("Granular thermal deposit budget exceeded");
			}
			depositEnergy.put(key, energy);
		} else depositEnergy.put(key, finiteAdd(current, energy));
	}
	void requireAddDeposit(DepositKey key, double energy) {
		if (key == null) throw new IllegalArgumentException("Granular thermal deposit key is missing");
		requireEnergy(energy);
		Double current = depositEnergy.get(key);
		if (current == null && depositEnergy.size() == maximumDeposits) {
			throw new IllegalStateException("Granular thermal deposit budget exceeded");
		}
		if (current != null) finiteAdd(current, energy);
	}

	static final class ActiveToDepositsPlan {
		final GranularEnergyColumn owner;
		final int activeCount;
		final int[] active;
		final long[] energies;
		final TreeMap<DepositKey, Double> before, after;
		final SignedDyadic roundoff;
		ActiveToDepositsPlan(GranularEnergyColumn owner, int[] active, long[] energies,
		                     TreeMap<DepositKey, Double> before, TreeMap<DepositKey, Double> after,
		                     SignedDyadic roundoff) {
			this.owner = owner; this.activeCount = owner.activeCount; this.active = active; this.energies = energies;
			this.before = before; this.after = after; this.roundoff = roundoff;
		}
	}

	/** All selected U and new target keys are budgeted together, without ordinal edits. */
	ActiveToDepositsPlan planActiveToDeposits(List<DepositTransfer> transfers) {
		if (transfers == null || transfers.isEmpty()) throw new IllegalArgumentException("Granular thermal transfers are missing");
		int[] active = new int[transfers.size()]; long[] energies = new long[active.length];
		TreeMap<DepositKey, Double> before = new TreeMap<>(), after = new TreeMap<>();
		TreeMap<DepositKey, SignedDyadic> sums = new TreeMap<>();
		int previous = activeCount, newKeys = 0;
		for (int slot = 0; slot < active.length; slot++) {
			DepositTransfer transfer = transfers.get(slot); requireActive(transfer.active());
			if (transfer.active() >= previous) throw new IllegalArgumentException("Thermal settlement ordinals must be unique and descending");
			previous = active[slot] = transfer.active();
			double energy = activeEnergy[active[slot]]; energies[slot] = Double.doubleToRawLongBits(energy);
			SignedDyadic incoming = SignedDyadic.of(energy);
			DepositKey key = new DepositKey(transfer.cell(), transfer.material(), transfer.composition());
			if (!sums.containsKey(key)) {
				Double current = depositEnergy.get(key); before.put(key, current);
				if (current == null) { newKeys++; sums.put(key, SignedDyadic.ZERO); }
				else sums.put(key, SignedDyadic.of(current));
			}
			sums.put(key, sums.get(key).add(incoming));
		}
		if (newKeys > maximumDeposits - depositEnergy.size()) return null;
		SignedDyadic roundoff = SignedDyadic.ZERO;
		for (var entry : sums.entrySet()) {
			double energy = entry.getValue().toDouble(); if (!Double.isFinite(energy)) return null;
			after.put(entry.getKey(), energy);
			roundoff = roundoff.add(SignedDyadic.of(energy).subtract(entry.getValue()));
		}
		return new ActiveToDepositsPlan(this, active, energies, before, after, roundoff);
	}

	void requireCurrent(ActiveToDepositsPlan plan) {
		if (plan == null || plan.owner != this || plan.activeCount != activeCount) {
			throw new IllegalStateException("Granular energy ordinals changed after settlement planning");
		}
		for (int slot = 0; slot < plan.active.length; slot++) if (Double.doubleToRawLongBits(activeEnergy[plan.active[slot]]) != plan.energies[slot]) {
			throw new IllegalStateException("Settlement source energy changed after planning");
		}
		for (var entry : plan.before.entrySet()) {
			Double current = depositEnergy.get(entry.getKey()), expected = entry.getValue();
			if (current == null ? expected != null : expected == null || Double.doubleToRawLongBits(current) != Double.doubleToRawLongBits(expected)) {
				throw new IllegalStateException("Settlement target energy changed after planning");
			}
		}
		if (depositEnergy.size() + plan.before.values().stream().filter(java.util.Objects::isNull).count() > maximumDeposits) {
			throw new IllegalStateException("Granular energy group budget changed after settlement planning");
		}
	}

	void commitActiveToDeposits(ActiveToDepositsPlan plan) {
		requireCurrent(plan);
		for (int ordinal : plan.active) removeActiveSwap(ordinal);
		depositEnergy.putAll(plan.after);
	}

	/** Proportional exact-SVQ take; caller supplies the group's pre-commit SVQ truth. */
	double takeDeposit(DepositKey key, long takenSvq, long totalSvq) {
		ProportionalDoubleSplit split = proportionalSplit(depositEnergy(key), takenSvq, totalSvq);
		if (takenSvq == totalSvq) depositEnergy.remove(key);
		else depositEnergy.put(key, split.remaining());
		return split.removed();
	}
	double previewDepositTake(DepositKey key, long takenSvq, long totalSvq) {
		return proportionalSplit(depositEnergy(key), takenSvq, totalSvq).removed();
	}
	static final class DepositWakePlan {
		final GranularEnergyColumn owner;
		final DepositKey source;
		final int ordinal;
		final double before, moved, remaining;
		final boolean whole;
		final SignedDyadic roundoff;
		DepositWakePlan(GranularEnergyColumn owner, DepositKey source, double before, ProportionalDoubleSplit split, boolean whole) {
			this.owner = owner; this.source = source; ordinal = owner.activeCount; this.before = before;
			moved = split.removed(); remaining = split.remaining(); this.whole = whole;
			roundoff = SignedDyadic.of(moved).add(SignedDyadic.of(remaining)).subtract(SignedDyadic.of(before));
		}
	}
	DepositWakePlan planDepositWake(DepositKey key, long takenSvq, long totalSvq) {
		double energy = depositEnergy(key); var split = proportionalSplit(energy, takenSvq, totalSvq);
		requireAddActive(split.removed()); ensureActiveCapacity(activeCount + 1);
		return new DepositWakePlan(this, key, energy, split, takenSvq == totalSvq);
	}
	void requireCurrent(DepositWakePlan plan) {
		if (plan == null || plan.owner != this || plan.ordinal != activeCount
				|| Double.doubleToRawLongBits(plan.before) != Double.doubleToRawLongBits(depositEnergy(plan.source)))
			throw new IllegalStateException("Granular thermal wake source changed after planning");
	}
	void commitDepositWake(DepositWakePlan plan) {
		requireCurrent(plan);
		if (plan.whole) depositEnergy.remove(plan.source); else depositEnergy.put(plan.source, plan.remaining);
		activeEnergy[activeCount++] = plan.moved;
	}
	public double previewDepositRemaining(DepositKey key, long takenSvq, long totalSvq) {
		return proportionalSplit(depositEnergy(key), takenSvq, totalSvq).remaining();
	}

	void moveDeposit(
			DepositKey source, DepositKey target, long movedSvq, long sourceTotalSvq
	) {
		if (source == null || target == null || source.equals(target)) {
			throw new IllegalArgumentException("Granular thermal deposit move requires distinct groups");
		}
		double moved = previewDepositTake(source, movedSvq, sourceTotalSvq);
		Double targetEnergy = depositEnergy.get(target);
		if (targetEnergy == null && depositEnergy.size() == maximumDeposits && movedSvq != sourceTotalSvq) {
			throw new IllegalStateException("Granular thermal deposit budget exceeded");
		}
		double merged = targetEnergy == null ? moved : finiteAdd(targetEnergy, moved);
		takeDeposit(source, movedSvq, sourceTotalSvq);
		depositEnergy.put(target, merged);
	}

	public double[] copyActiveEnergy() { return Arrays.copyOf(activeEnergy, activeCount); }
	public List<DepositEnergy> depositSnapshot() {
		ArrayList<DepositEnergy> result = new ArrayList<>(depositEnergy.size());
		for (Map.Entry<DepositKey, Double> entry : depositEnergy.entrySet()) {
			result.add(new DepositEnergy(entry.getKey(), entry.getValue()));
		}
		return List.copyOf(result);
	}

	void restore(double[] active, List<DepositEnergy> deposits) {
		if (activeCount != 0 || !depositEnergy.isEmpty() || active == null || deposits == null
				|| active.length > maximumActive || deposits.size() > maximumDeposits) {
			throw new IllegalArgumentException("Granular thermal restore target/snapshot is invalid");
		}
		ensureActiveCapacity(active.length);
		for (double energy : active) requireEnergy(energy);
		System.arraycopy(active, 0, activeEnergy, 0, active.length); activeCount = active.length;
		DepositKey previous = null;
		for (DepositEnergy deposit : deposits) {
			if (previous != null && previous.compareTo(deposit.key()) >= 0) {
				throw new IllegalArgumentException("Granular thermal deposits are not canonical and unique");
			}
			depositEnergy.put(deposit.key(), deposit.internalEnergy()); previous = deposit.key();
		}
	}
	void replace(double[] active, List<DepositEnergy> deposits) {
		Arrays.fill(activeEnergy, 0, activeCount, 0.0); activeCount = 0; depositEnergy.clear();
		restore(active, deposits);
	}

	public SignedDyadic exactEnergy() {
		SignedDyadic result = SignedDyadic.sum(copyActiveEnergy());
		for (double energy : depositEnergy.values()) result = result.add(SignedDyadic.of(energy));
		return result;
	}

	SignedDyadic clear() {
		SignedDyadic removed = exactEnergy();
		Arrays.fill(activeEnergy, 0, activeCount, 0.0); activeCount = 0; depositEnergy.clear();
		return removed;
	}

	private void ensureActiveCapacity(int required) {
		if (required <= activeEnergy.length) return;
		activeEnergy = Arrays.copyOf(activeEnergy, Math.min(maximumActive,
				Math.max(required, Math.max(1, activeEnergy.length * 2))));
	}
	private void requireActive(int index) {
		if (index < 0 || index >= activeCount) throw new IllegalArgumentException("Unknown granular thermal active ordinal");
	}
	private static void requireEnergy(double energy) {
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Granular internal energy is not finite");
	}
	private static ProportionalDoubleSplit proportionalSplit(double energy, long takenSvq, long totalSvq) {
		if (takenSvq <= 0L || totalSvq <= 0L || takenSvq > totalSvq) {
			throw new IllegalArgumentException("Granular thermal split volume is invalid");
		}
		return ProportionalDoubleSplit.of(energy, takenSvq, totalSvq);
	}
	private static double finiteAdd(double first, double second) {
		double result = first + second;
		if (!Double.isFinite(result)) throw new ArithmeticException("Granular internal energy overflow");
		return result;
	}
}
