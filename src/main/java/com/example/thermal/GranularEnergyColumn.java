package com.example.thermal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Orthogonal raw-U column aligned with M8 active ordinals and canonical deposit identities. */
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
		if (maximumActive <= 0 || maximumActive > com.example.granular.GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION
				|| maximumDeposits <= 0
				|| maximumDeposits > com.example.granular.GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION) {
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
	public void setActiveEnergy(int index, double energy) {
		requireActive(index); requireEnergy(energy); activeEnergy[index] = energy;
	}
	public void setDepositEnergy(DepositKey key, double energy) {
		requireEnergy(energy);
		if (!depositEnergy.containsKey(key)) throw new IllegalArgumentException("Unknown granular thermal deposit group");
		depositEnergy.put(key, energy);
	}

	public int addActive(double energy) {
		requireAddActive(energy);
		ensureActiveCapacity(activeCount + 1);
		activeEnergy[activeCount] = energy;
		return activeCount++;
	}
	public void requireAddActive(double energy) {
		requireEnergy(energy);
		if (activeCount == maximumActive) throw new IllegalStateException("Granular thermal active budget exceeded");
	}

	/** Mirrors M8 swap removal and returns the removed carrier energy. */
	public double removeActiveSwap(int index) {
		requireActive(index);
		double removed = activeEnergy[index]; int last = --activeCount;
		if (index != last) activeEnergy[index] = activeEnergy[last];
		activeEnergy[last] = 0.0;
		return removed;
	}

	/** Adds the second energy into the first, then mirrors the M8 second-index swap removal. */
	public void mergeActive(int first, int second) {
		double merged = requireMergeActive(first, second);
		int last = activeCount - 1;
		removeActiveSwap(second);
		if (first == last && second != last) activeEnergy[second] = merged;
		else activeEnergy[first] = merged;
	}
	public double requireMergeActive(int first, int second) {
		requireActive(first); requireActive(second);
		if (first == second) throw new IllegalArgumentException("Cannot merge one granular thermal ordinal with itself");
		return finiteAdd(activeEnergy[first], activeEnergy[second]);
	}

	/** Proportionally removes energy without changing the active ordinal cardinality. */
	public double takeActivePortion(int index, long takenSvq, long totalSvq) {
		requireActive(index);
		double removed = previewActiveTake(index, takenSvq, totalSvq);
		if (takenSvq == totalSvq) throw new IllegalArgumentException("Full active take must use swap removal");
		activeEnergy[index] -= removed;
		return removed;
	}
	public double previewActiveTake(int index, long takenSvq, long totalSvq) {
		requireActive(index);
		return proportionalTake(activeEnergy[index], takenSvq, totalSvq);
	}

	public void addDeposit(DepositKey key, double energy) {
		requireAddDeposit(key, energy);
		Double current = depositEnergy.get(key);
		if (current == null) {
			if (depositEnergy.size() == maximumDeposits) {
				throw new IllegalStateException("Granular thermal deposit budget exceeded");
			}
			depositEnergy.put(key, energy);
		} else depositEnergy.put(key, finiteAdd(current, energy));
	}
	public void requireAddDeposit(DepositKey key, double energy) {
		if (key == null) throw new IllegalArgumentException("Granular thermal deposit key is missing");
		requireEnergy(energy);
		Double current = depositEnergy.get(key);
		if (current == null && depositEnergy.size() == maximumDeposits) {
			throw new IllegalStateException("Granular thermal deposit budget exceeded");
		}
		if (current != null) finiteAdd(current, energy);
	}

	/** Proportional exact-SVQ take; caller supplies the M8 group's pre-commit SVQ truth. */
	public double takeDeposit(DepositKey key, long takenSvq, long totalSvq) {
		double current = depositEnergy(key);
		double removed = previewDepositTake(key, takenSvq, totalSvq);
		if (takenSvq == totalSvq) depositEnergy.remove(key);
		else depositEnergy.put(key, current - removed);
		return removed;
	}
	public double previewDepositTake(DepositKey key, long takenSvq, long totalSvq) {
		return proportionalTake(depositEnergy(key), takenSvq, totalSvq);
	}

	public void moveDeposit(
			DepositKey source, DepositKey target, long movedSvq, long sourceTotalSvq
	) {
		if (source == null || target == null || source.equals(target)) {
			throw new IllegalArgumentException("Granular thermal deposit move requires distinct groups");
		}
		double moved = proportionalTake(depositEnergy(source), movedSvq, sourceTotalSvq);
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

	public void restore(double[] active, List<DepositEnergy> deposits) {
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
	public void replace(double[] active, List<DepositEnergy> deposits) {
		Arrays.fill(activeEnergy, 0, activeCount, 0.0); activeCount = 0; depositEnergy.clear();
		restore(active, deposits);
	}

	public SignedDyadic exactEnergy() {
		SignedDyadic result = SignedDyadic.sum(copyActiveEnergy());
		for (double energy : depositEnergy.values()) result = result.add(SignedDyadic.of(energy));
		return result;
	}

	public SignedDyadic clear() {
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
	private static double proportionalTake(double energy, long takenSvq, long totalSvq) {
		if (takenSvq <= 0L || totalSvq <= 0L || takenSvq > totalSvq) {
			throw new IllegalArgumentException("Granular thermal split volume is invalid");
		}
		if (takenSvq == totalSvq) return energy;
		double removed = energy * ((double) takenSvq / totalSvq);
		if (!Double.isFinite(removed) || !Double.isFinite(energy - removed)) {
			throw new IllegalArgumentException("Granular thermal split is not representable");
		}
		return removed;
	}
	private static double finiteAdd(double first, double second) {
		double result = first + second;
		if (!Double.isFinite(result)) throw new ArithmeticException("Granular internal energy overflow");
		return result;
	}
}
