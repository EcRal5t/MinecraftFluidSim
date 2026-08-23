package com.example.granular;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded, quantized, read-only client view. No exact SVQ, mass or solid composition leaves the server. */
public record GranularDisplaySnapshot(
		int sizeX, int sizeY, int sizeZ, float cellSize, String registryFingerprint,
		List<MaterialEntry> materials, byte[] depositBulkFill, byte[] depositPorosity,
		short[] depositTopMaterial, List<ParcelSample> activeParcels
) {
	public GranularDisplaySnapshot {
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || !(Float.isFinite(cellSize) && cellSize > 0.0F)
				|| registryFingerprint == null || !registryFingerprint.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid granular display identity/geometry");
		}
		materials = List.copyOf(Objects.requireNonNull(materials, "materials"));
		depositBulkFill = Objects.requireNonNull(depositBulkFill, "depositBulkFill").clone();
		depositPorosity = Objects.requireNonNull(depositPorosity, "depositPorosity").clone();
		depositTopMaterial = Objects.requireNonNull(depositTopMaterial, "depositTopMaterial").clone();
		activeParcels = List.copyOf(Objects.requireNonNull(activeParcels, "activeParcels"));
		int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		if (materials.isEmpty() || materials.size() > GranularLimits.MAX_MATERIALS
				|| depositBulkFill.length != cells || depositPorosity.length != cells
				|| depositTopMaterial.length != cells
				|| activeParcels.size() > GranularLimits.MAX_DISPLAY_PARCELS_PER_REGION) {
			throw new IllegalArgumentException("Granular display fields exceed their bounds");
		}
		String previous = null;
		for (MaterialEntry material : materials) {
			if (previous != null && previous.compareTo(material.id()) >= 0) {
				throw new IllegalArgumentException("Granular display palette is not canonical");
			}
			previous = material.id();
		}
		for (int cell = 0; cell < cells; cell++) {
			int fill = Byte.toUnsignedInt(depositBulkFill[cell]);
			int porosity = Byte.toUnsignedInt(depositPorosity[cell]);
			int material = depositTopMaterial[cell];
			if (fill == 0 && (porosity != 0 || material != -1)
					|| fill > 0 && (porosity == 0 || material < 0 || material >= materials.size())) {
				throw new IllegalArgumentException("Granular deposit display fields disagree");
			}
		}
		for (ParcelSample sample : activeParcels) if (sample.materialIndex() >= materials.size()) {
			throw new IllegalArgumentException("Granular parcel sample references an unknown material");
		}
	}

	@Override public byte[] depositBulkFill() { return depositBulkFill.clone(); }
	@Override public byte[] depositPorosity() { return depositPorosity.clone(); }
	@Override public short[] depositTopMaterial() { return depositTopMaterial.clone(); }

	public static GranularDisplaySnapshot build(
			int sizeX, int sizeY, int sizeZ, float cellSize, GranularRegistry registry,
			GranularParcelStore parcels, GranularDepositStore deposits
	) {
		if (registry == null || parcels == null || deposits == null) {
			throw new IllegalArgumentException("Granular display source is missing");
		}
		int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		if (deposits.cellCount() != cells) throw new IllegalArgumentException("Granular display dimensions changed");
		List<MaterialEntry> palette = registry.materials().stream()
				.map(material -> new MaterialEntry(material.id(), material.baseArgb())).toList();
		byte[] bulk = new byte[cells], porosity = new byte[cells]; short[] top = new short[cells];
		java.util.Arrays.fill(top, (short) -1);
		deposits.forEachCell((cell, ignored, porosityQ) -> {
			bulk[cell] = (byte) Math.max(1,
					quantizeUnit(deposits.cellBulkVolumeSvq(cell) / (double) SolidVolume.SVQ_PER_CELL));
			porosity[cell] = (byte) Math.max(1, quantizeUnit(porosityQ / (double) GranularLimits.POROSITY_SCALE));
			top[cell] = (short) deposits.dominantGroup(cell).materialHandle();
		});

		ArrayList<Candidate> candidates = new ArrayList<>(parcels.count());
		for (int ordinal = 0; ordinal < parcels.count(); ordinal++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(ordinal);
			long key = sampleKey(parcel);
			candidates.add(new Candidate(key, ordinal, new ParcelSample(
					quantizePosition(parcel.x(), sizeX * cellSize),
					quantizePosition(parcel.y(), sizeY * cellSize),
					quantizePosition(parcel.z(), sizeZ * cellSize),
					Math.max(1, quantizeUnit(Math.cbrt(parcel.solidVolumeSvq()
							/ (double) SolidVolume.SVQ_PER_BV) / cellSize)),
					parcel.materialHandle())));
		}
		candidates.sort(Comparator.comparingLong(Candidate::key).thenComparingInt(Candidate::ordinal));
		int count = Math.min(candidates.size(), GranularLimits.MAX_DISPLAY_PARCELS_PER_REGION);
		ArrayList<ParcelSample> samples = new ArrayList<>(count);
		for (int index = 0; index < count; index++) samples.add(candidates.get(index).sample());
		return new GranularDisplaySnapshot(sizeX, sizeY, sizeZ, cellSize, registry.fingerprint(),
				palette, bulk, porosity, top, samples);
	}

	private static int quantizeUnit(double value) {
		return (int) Math.round(Math.max(0.0, Math.min(1.0, value)) * 255.0);
	}

	private static int quantizePosition(float value, float extent) {
		return (int) Math.round(Math.max(0.0, Math.min(1.0, value / extent)) * 65_535.0);
	}

	private static long sampleKey(GranularParcelStore.Parcel parcel) {
		long value = Integer.toUnsignedLong(Float.floatToRawIntBits(parcel.x()));
		value = mix(value ^ Integer.toUnsignedLong(Float.floatToRawIntBits(parcel.y())) << 1);
		value = mix(value ^ Integer.toUnsignedLong(Float.floatToRawIntBits(parcel.z())) << 2);
		return mix(value ^ (long) parcel.materialHandle() << 32);
	}

	private static long mix(long value) {
		value ^= value >>> 33; value *= 0xff51afd7ed558ccdl;
		value ^= value >>> 33; value *= 0xc4ceb9fe1a85ec53l;
		return value ^ value >>> 33;
	}

	public record MaterialEntry(String id, int argb) {
		public MaterialEntry { GranularIds.requireId(id, "granular display material id"); }
	}

	public record ParcelSample(int xQ, int yQ, int zQ, int diameterQ, int materialIndex) {
		public ParcelSample {
			if (xQ < 0 || xQ > 65_535 || yQ < 0 || yQ > 65_535 || zQ < 0 || zQ > 65_535
					|| diameterQ <= 0 || diameterQ > 255 || materialIndex < 0
					|| materialIndex >= GranularLimits.MAX_MATERIALS) {
				throw new IllegalArgumentException("Invalid granular parcel display sample");
			}
		}
	}

	private record Candidate(long key, int ordinal, ParcelSample sample) { }
}
