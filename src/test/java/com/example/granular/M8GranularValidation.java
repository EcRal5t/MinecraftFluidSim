package com.example.granular;

import java.util.Arrays;
import java.util.List;

/** M8 granular definition and exact inventory gate. Mechanics are added to this selector incrementally. */
public final class M8GranularValidation {
	private M8GranularValidation() { }

	public static void main(String[] args) {
		boolean fast = Arrays.asList(args).contains("--fast");
		registryIsCanonicalAndBounded();
		solidVolumeConversionsAreConservative();
		parcelSplitMergePreservesInventory();
		depositCapacityAndGroupBudgetAreAtomic();
		parcelDepositCyclesDoNotDrift(fast ? 1_000 : 10_000);
		System.out.println("M8 granular core gate passed: canonical registry, exact SVQ and atomic parcel/deposit inventory");
	}

	private static void registryIsCanonicalAndBounded() {
		GranularRegistry builtins = BuiltinGranularDefinitions.createRegistry();
		GranularRegistry reordered = new GranularRegistry(
				List.of(builtins.materials().get(2), builtins.materials().get(0), builtins.materials().get(1)),
				List.of(builtins.carriers().get(1), builtins.carriers().get(2), builtins.carriers().get(0)));
		require(builtins.fingerprint().equals(reordered.fingerprint()), "registry fingerprint depends on input order");
		expectFailure(() -> new GranularRegistry(List.of(builtins.materials().get(0), builtins.materials().get(0)),
				builtins.carriers()), "duplicate material accepted");
		expectFailure(() -> new ParticulateMaterial("BAD", 1.0, 0.001, 400_000, 0.5,
				0.5, 0.4, 0.1, 0.05, 1L, 1L, 0, "realwater:bad", false), "bad id accepted");
	}

	private static void solidVolumeConversionsAreConservative() {
		for (long svq : new long[] {0L, 1L, SolidVolume.SVQ_PER_CELL / 8L, SolidVolume.SVQ_PER_CELL,
				SolidVolume.SVQ_PER_BV}) {
			require(SolidVolume.fromCellVolumeExact(SolidVolume.toCellVolume(svq)) == svq,
					"SVQ/cell-volume conversion drifted");
		}
		for (long nanos : new long[] {0L, 1L, 100_000L, 123_456_789L, SolidVolume.NANOS_PER_BV}) {
			long svq = SolidVolume.nanosToSvqAtMost(nanos);
			long settled = SolidVolume.svqToNanosAtMost(svq);
			require(settled <= nanos && nanos - settled <= 8L, "SVQ/nanovolume conversion exceeded bound");
		}
	}

	private static void parcelSplitMergePreservesInventory() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int material = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		GranularParcelStore store = new GranularParcelStore(registry, 4);
		long volume = registry.material(material).maximumActiveParcelSvq();
		double mass = 7.25;
		store.add(parcel(material, volume, mass));
		int split = store.split(0, volume / 3L);
		require(store.totalSolidVolumeSvq() == volume, "parcel split changed SVQ");
		require(rawClose(store.totalMass(), mass), "parcel split changed mass");
		store.merge(0, split);
		require(store.count() == 1 && store.totalSolidVolumeSvq() == volume, "parcel merge changed SVQ");
		require(rawClose(store.totalMass(), mass), "parcel merge changed mass");
	}

	private static void depositCapacityAndGroupBudgetAreAtomic() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int reactive = registry.materialHandle(BuiltinGranularDefinitions.PRECIPITATE);
		GranularDepositStore deposits = new GranularDepositStore(registry, 2, 8);
		long portion = SolidVolume.SVQ_PER_CELL / 32L;
		for (int composition = 1; composition <= GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL; composition++) {
			require(deposits.tryAdd(0, new GranularDepositStore.SolidPortion(reactive, composition, portion, 1.0),
					SolidVolume.SVQ_PER_CELL), "valid deposit group rejected");
		}
		long beforeVolume = deposits.totalSolidVolumeSvq();
		double beforeMass = deposits.totalMass();
		require(!deposits.tryAdd(0, new GranularDepositStore.SolidPortion(reactive, 9, portion, 1.0),
				SolidVolume.SVQ_PER_CELL), "ninth deposit group accepted");
		require(deposits.totalSolidVolumeSvq() == beforeVolume
				&& Double.doubleToRawLongBits(deposits.totalMass()) == Double.doubleToRawLongBits(beforeMass),
				"deposit group rejection left residue");

		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		GranularDepositStore full = new GranularDepositStore(registry, 1, 8);
		long tooMuchSkeleton = SolidVolume.SVQ_PER_CELL * 3L / 4L;
		require(!full.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, tooMuchSkeleton, 5.0),
				SolidVolume.SVQ_PER_CELL), "bulk-capacity overflow accepted");
		require(full.groupCount() == 0 && full.totalSolidVolumeSvq() == 0L, "capacity rejection left residue");
	}

	private static void parcelDepositCyclesDoNotDrift(int iterations) {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int material = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		GranularParcelStore parcels = new GranularParcelStore(registry, 2);
		GranularDepositStore deposits = new GranularDepositStore(registry, 1, 2);
		GranularInventory inventory = new GranularInventory(parcels, deposits);
		long volume = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = 2.75;
		parcels.add(parcel(material, volume, mass));
		for (int iteration = 0; iteration < iterations; iteration++) {
			require(inventory.depositParcel(0, 0, SolidVolume.SVQ_PER_CELL), "parcel deposit failed");
			require(inventory.wake(0, material, 0, volume, 0.5F, 0.5F, 0.5F, 0.0F, 0.0F, 0.0F),
					"deposit wake failed");
		}
		require(inventory.totalSolidVolumeSvq() == volume, "parcel/deposit cycles changed SVQ");
		require(rawClose(inventory.totalMass(), mass), "parcel/deposit cycles changed mass");
	}

	private static GranularParcelStore.Parcel parcel(int material, long volume, double mass) {
		return new GranularParcelStore.Parcel(0.5F, 0.5F, 0.5F, 0.25F, -0.5F, 0.125F,
				volume, mass, material, 0, 0, (byte) 0);
	}

	private static boolean rawClose(double actual, double expected) {
		return Math.abs(actual - expected) <= Math.max(1.0e-12, 8.0 * Math.ulp(expected));
	}

	private static void expectFailure(Runnable action, String message) {
		try { action.run(); } catch (IllegalArgumentException expected) { return; }
		throw new AssertionError(message);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
