package com.example.granular;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntToLongFunction;

/** Stable-ID, exact-inventory handoff used only by region-state v5 and transactional adapters. */
public final class GranularStateSnapshot {
	public record ActiveParcel(
			float x, float y, float z, float velocityX, float velocityY, float velocityZ,
			long solidVolumeSvq, double mass, int materialPaletteIndex, int solidCompositionIndex
	) { }

	public record DepositGroup(
			int cell, int porosityQ, int materialPaletteIndex, int solidCompositionIndex,
			long solidVolumeSvq, double mass
	) {
		private static final Comparator<DepositGroup> ORDER = Comparator.comparingInt(DepositGroup::cell)
				.thenComparingInt(DepositGroup::materialPaletteIndex)
				.thenComparingInt(DepositGroup::solidCompositionIndex);
	}

	private final int sizeX, sizeY, sizeZ;
	private final String registryFingerprint;
	private final List<String> materialPalette;
	private final List<ActiveParcel> activeParcels;
	private final List<DepositGroup> depositGroups;
	private final long totalSolidVolumeSvq;
	private final long totalMassBits;

	public GranularStateSnapshot(
			int sizeX, int sizeY, int sizeZ, String registryFingerprint, List<String> materialPalette,
			List<ActiveParcel> activeParcels, List<DepositGroup> depositGroups,
			long totalSolidVolumeSvq, long totalMassBits
	) {
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) throw new IllegalArgumentException("Invalid granular snapshot dimensions");
		if (registryFingerprint == null || !registryFingerprint.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid granular registry fingerprint");
		}
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		this.registryFingerprint = registryFingerprint;
		this.materialPalette = List.copyOf(materialPalette);
		this.activeParcels = List.copyOf(activeParcels);
		this.depositGroups = List.copyOf(depositGroups);
		if (this.materialPalette.isEmpty() || this.materialPalette.size() > GranularLimits.MAX_MATERIALS
				|| this.activeParcels.size() > GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION
				|| this.depositGroups.size() > GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION) {
			throw new IllegalArgumentException("Granular snapshot exceeds a hard inventory budget");
		}
		String previousId = null;
		for (String id : this.materialPalette) {
			GranularIds.requireId(id, "snapshot particulate material id");
			if (previousId != null && previousId.compareTo(id) >= 0) {
				throw new IllegalArgumentException("Granular material palette is not canonical");
			}
			previousId = id;
		}
		validateActive();
		validateDeposits();
		long computedVolume = 0L;
		double computedMass = 0.0;
		for (ActiveParcel parcel : this.activeParcels) {
			computedVolume = Math.addExact(computedVolume, parcel.solidVolumeSvq()); computedMass += parcel.mass();
		}
		for (DepositGroup group : this.depositGroups) {
			computedVolume = Math.addExact(computedVolume, group.solidVolumeSvq()); computedMass += group.mass();
		}
		if (computedVolume != totalSolidVolumeSvq
				|| Double.doubleToRawLongBits(computedMass) != totalMassBits) {
			throw new IllegalArgumentException("Granular snapshot total ledger mismatch");
		}
		this.totalSolidVolumeSvq = totalSolidVolumeSvq; this.totalMassBits = totalMassBits;
	}

	public static GranularStateSnapshot capture(
			int sizeX, int sizeY, int sizeZ, GranularRegistry registry,
			GranularParcelStore parcels, GranularDepositStore deposits
	) {
		List<String> palette = registry.materials().stream().map(ParticulateMaterial::id).toList();
		List<ActiveParcel> active = new ArrayList<>(parcels.count());
		for (int index = 0; index < parcels.count(); index++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index);
			requirePersistableComposition(parcel.solidCompositionHandle());
			active.add(new ActiveParcel(parcel.x(), parcel.y(), parcel.z(), parcel.velocityX(), parcel.velocityY(),
					parcel.velocityZ(), parcel.solidVolumeSvq(), parcel.mass(), parcel.materialHandle(), 0));
		}
		List<DepositGroup> deposit = new ArrayList<>(deposits.groupCount());
		deposits.forEachGroup((cell, porosity, material, composition, volume, mass) -> {
			requirePersistableComposition(composition);
			deposit.add(new DepositGroup(cell, porosity, material, 0, volume, mass));
		});
		deposit.sort(DepositGroup.ORDER);
		long volume = Math.addExact(parcels.totalSolidVolumeSvq(), deposits.totalSolidVolumeSvq());
		double mass = parcels.totalMass() + deposits.totalMass();
		return new GranularStateSnapshot(sizeX, sizeY, sizeZ, registry.fingerprint(), palette, active, deposit,
				volume, Double.doubleToRawLongBits(mass));
	}

	public void restoreInto(
			GranularRegistry registry, GranularParcelStore parcels, GranularDepositStore deposits,
			IntToLongFunction geometricOpenSvq
	) {
		if (parcels.count() != 0 || deposits.groupCount() != 0) throw new IllegalStateException("Granular restore target is not empty");
		if (!registryFingerprint.equals(registry.fingerprint())
				|| !materialPalette.equals(registry.materials().stream().map(ParticulateMaterial::id).toList())) {
			throw new IllegalArgumentException("Granular snapshot registry identity mismatch");
		}
		for (ActiveParcel parcel : activeParcels) {
			parcels.add(new GranularParcelStore.Parcel(parcel.x(), parcel.y(), parcel.z(), parcel.velocityX(),
					parcel.velocityY(), parcel.velocityZ(), parcel.solidVolumeSvq(), parcel.mass(),
					parcel.materialPaletteIndex(), 0, 0, (byte) 0));
		}
		int previousCell = -1, expectedPorosity = 0;
		for (DepositGroup group : depositGroups) {
			if (previousCell != group.cell() && previousCell >= 0 && deposits.porosityQ(previousCell) != expectedPorosity) {
				throw new IllegalArgumentException("Persisted deposit porosity is not registry-derived");
			}
			if (!deposits.tryAdd(group.cell(), new GranularDepositStore.SolidPortion(group.materialPaletteIndex(), 0,
					group.solidVolumeSvq(), group.mass()), geometricOpenSvq.applyAsLong(group.cell()))) {
				throw new IllegalArgumentException("Persisted granular deposit exceeds restored cell capacity");
			}
			previousCell = group.cell(); expectedPorosity = group.porosityQ();
		}
		if (previousCell >= 0 && deposits.porosityQ(previousCell) != expectedPorosity) {
			throw new IllegalArgumentException("Persisted deposit porosity is not registry-derived");
		}
		if (Math.addExact(parcels.totalSolidVolumeSvq(), deposits.totalSolidVolumeSvq()) != totalSolidVolumeSvq
				|| Double.doubleToRawLongBits(parcels.totalMass() + deposits.totalMass()) != totalMassBits) {
			throw new IllegalStateException("Restored granular inventory does not match snapshot ledger");
		}
	}

	private void validateActive() {
		for (ActiveParcel parcel : activeParcels) {
			if (!(Float.isFinite(parcel.x()) && Float.isFinite(parcel.y()) && Float.isFinite(parcel.z())
					&& parcel.x() >= 0.0F && parcel.x() < sizeX * 0.5F
					&& parcel.y() >= 0.0F && parcel.y() < sizeY * 0.5F
					&& parcel.z() >= 0.0F && parcel.z() < sizeZ * 0.5F
					&& Float.isFinite(parcel.velocityX()) && Float.isFinite(parcel.velocityY())
					&& Float.isFinite(parcel.velocityZ()) && parcel.solidVolumeSvq() > 0L
					&& Double.isFinite(parcel.mass()) && parcel.mass() > 0.0
					&& validMaterial(parcel.materialPaletteIndex()) && parcel.solidCompositionIndex() == 0)) {
				throw new IllegalArgumentException("Invalid active granular snapshot parcel");
			}
		}
	}

	private void validateDeposits() {
		DepositGroup previous = null;
		int groupsInCell = 0;
		for (DepositGroup group : depositGroups) {
			SolidVolume.requirePorosity(group.porosityQ());
			if (group.cell() < 0 || group.cell() >= sizeX * sizeY * sizeZ || !validMaterial(group.materialPaletteIndex())
					|| group.solidCompositionIndex() != 0 || group.solidVolumeSvq() <= 0L
					|| !(Double.isFinite(group.mass()) && group.mass() > 0.0)) {
				throw new IllegalArgumentException("Invalid granular deposit snapshot group");
			}
			if (previous != null && DepositGroup.ORDER.compare(previous, group) >= 0) {
				throw new IllegalArgumentException("Granular deposit groups are not canonical and unique");
			}
			groupsInCell = previous != null && previous.cell() == group.cell() ? groupsInCell + 1 : 1;
			if (groupsInCell > GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL) {
				throw new IllegalArgumentException("Granular deposit cell group budget exceeded");
			}
			if (previous != null && previous.cell() == group.cell() && previous.porosityQ() != group.porosityQ()) {
				throw new IllegalArgumentException("Granular deposit cell has conflicting porosity");
			}
			previous = group;
		}
	}

	private boolean validMaterial(int handle) { return handle >= 0 && handle < materialPalette.size(); }
	private static void requirePersistableComposition(int composition) {
		if (composition != 0) throw new IllegalStateException("Reactive solid composition persistence is not bound yet");
	}

	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
	public String registryFingerprint() { return registryFingerprint; }
	public List<String> materialPalette() { return materialPalette; }
	public List<ActiveParcel> activeParcels() { return activeParcels; }
	public List<DepositGroup> depositGroups() { return depositGroups; }
	public long totalSolidVolumeSvq() { return totalSolidVolumeSvq; }
	public long totalMassBits() { return totalMassBits; }
	public double totalMass() { return Double.longBitsToDouble(totalMassBits); }
}
