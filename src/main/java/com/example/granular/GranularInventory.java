package com.example.granular;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/** Atomic inventory-only parcel/deposit transitions. Mechanics and scheduling are layered above this class. */
public final class GranularInventory {
	private final GranularParcelStore parcels;
	private final GranularDepositStore deposits;
	private final int[] activeHeadByCell, activeNext, mergeFirst, mergeSecond;
	private final boolean[] mergePaired;
	private SolidCompositionPool compositions;

	public GranularInventory(GranularParcelStore parcels, GranularDepositStore deposits) {
		if (parcels == null || deposits == null) throw new IllegalArgumentException("Granular stores are missing");
		this.parcels = parcels;
		this.deposits = deposits;
		this.activeHeadByCell = new int[deposits.cellCount()];
		this.activeNext = new int[parcels.capacity()];
		this.mergeFirst = new int[parcels.capacity() / 2];
		this.mergeSecond = new int[parcels.capacity() / 2];
		this.mergePaired = new boolean[parcels.capacity()];
	}

	public void bindSolidCompositions(SolidCompositionPool pool) {
		if (pool == null) throw new IllegalArgumentException("Solid composition pool is missing");
		if (compositions != null && compositions != pool) {
			throw new IllegalStateException("Granular inventory cannot change its solid composition pool");
		}
		compositions = pool;
	}

	public boolean depositParcel(int parcelIndex, int cell, long geometricOpenSvq) {
		GranularParcelStore.Parcel parcel = parcels.parcel(parcelIndex);
		if (parcel.solidCompositionHandle() != 0) {
			if (compositions == null) throw new IllegalStateException("Reactive deposit requires a solid composition pool");
			SolidCompositionRecord incoming = compositions.record(parcel.solidCompositionHandle());
			GranularDepositStore.Group compatible = deposits.matchingGroup(cell, parcel.materialHandle(), group ->
					incoming.proportionalTo(compositions.record(group.solidCompositionHandle()),
							parcel.solidVolumeSvq(), group.solidVolumeSvq()));
			if (compatible != null) {
				SolidCompositionRecord combined = compositions.record(compatible.solidCompositionHandle()).plus(incoming);
				GranularDepositStore.SolidPortion merged = new GranularDepositStore.SolidPortion(
						parcel.materialHandle(), compatible.solidCompositionHandle(),
						parcel.solidVolumeSvq(), parcel.mass());
				if (!deposits.tryAdd(cell, merged, geometricOpenSvq)) return false;
				compositions.overwrite(compatible.solidCompositionHandle(), combined);
				compositions.release(parcel.solidCompositionHandle());
				parcels.removeSwap(parcelIndex);
				return true;
			}
		}
		GranularDepositStore.SolidPortion portion = new GranularDepositStore.SolidPortion(
				parcel.materialHandle(), parcel.solidCompositionHandle(), parcel.solidVolumeSvq(), parcel.mass());
		if (!deposits.tryAdd(cell, portion, geometricOpenSvq)) return false;
		parcels.removeSwap(parcelIndex);
		return true;
	}

	/** Bounded same-cell compaction for representative active parcels, including reactive composition ownership. */
	public CompactionStats compactActive(
			IntUnaryOperator cellOfParcel, Predicate<GranularParcelStore.Parcel> geometryValid,
			float maximumVelocityDelta, int maximumEvaluations
	) {
		if (cellOfParcel == null || geometryValid == null || !(Float.isFinite(maximumVelocityDelta)
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
				if (merge == null || !geometryValid.test(merge.parcel())) continue;
				mergeFirst[pairs] = first; mergeSecond[pairs] = second; pairs++;
				mergePaired[first] = true; mergePaired[second] = true;
				break;
			}
			activeNext[second] = activeHeadByCell[cell]; activeHeadByCell[cell] = second;
		}
		for (int pair = pairs - 1; pair >= 0; pair--) {
			ActiveMerge merge = activeMerge(mergeFirst[pair], mergeSecond[pair], maximumVelocityDelta);
			if (merge == null || !geometryValid.test(merge.parcel())) {
				throw new IllegalStateException("Active granular merge plan changed before commit");
			}
			if (merge.composition() != null) {
				compositions.overwrite(merge.parcel().solidCompositionHandle(), merge.composition());
				compositions.release(parcels.parcel(mergeSecond[pair]).solidCompositionHandle());
			}
			parcels.overwrite(mergeFirst[pair], merge.parcel());
			parcels.removeSwap(mergeSecond[pair]);
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
				weighted(first.x(), first.solidVolumeSvq(), second.x(), second.solidVolumeSvq(), combinedSvq),
				weighted(first.y(), first.solidVolumeSvq(), second.y(), second.solidVolumeSvq(), combinedSvq),
				weighted(first.z(), first.solidVolumeSvq(), second.z(), second.solidVolumeSvq(), combinedSvq),
				weighted(first.velocityX(), first.mass(), second.velocityX(), second.mass(), combinedMass),
				weighted(first.velocityY(), first.mass(), second.velocityY(), second.mass(), combinedMass),
				weighted(first.velocityZ(), first.mass(), second.velocityZ(), second.mass(), combinedMass),
				combinedSvq, combinedMass, first.materialHandle(), first.solidCompositionHandle(),
				Math.min(first.contactAge(), second.contactAge()), (byte) (first.flags() & second.flags()));
		return new ActiveMerge(merged, combinedComposition);
	}

	private static float weighted(float left, long leftWeight, float right, long rightWeight, long total) {
		return (float) (((double) left * leftWeight + (double) right * rightWeight) / total);
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
		GranularDepositStore.SolidPortion committed = deposits.take(
				cell, materialHandle, compositionHandle, requestedSvq);
		parcels.add(parcel);
		if (!committed.equals(planned)) throw new IllegalStateException("Granular wake plan changed before commit");
		return true;
	}

	public long totalSolidVolumeSvq() {
		return Math.addExact(parcels.totalSolidVolumeSvq(), deposits.totalSolidVolumeSvq());
	}

	public double totalMass() { return parcels.totalMass() + deposits.totalMass(); }

	public record CompactionStats(int merges, int evaluations) { }
	private record ActiveMerge(GranularParcelStore.Parcel parcel, SolidCompositionRecord composition) { }
}
