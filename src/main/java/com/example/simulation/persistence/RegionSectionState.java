package com.example.simulation.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One complete Minecraft-aligned solver slice plus the markers owned by that slice. */
public record RegionSectionState(
		int formatVersion,
		UUID regionId,
		String dimensionId,
		long checkpointGeneration,
		int sectionX,
		int sectionY,
		int sectionZ,
		int localStartX,
		int localStartY,
		int localStartZ,
		int sizeX,
		int sizeY,
		int sizeZ,
		int[] markerOrdinals,
		float[] positionVolume,
		float[] velocities,
		double[] markerMass,
		int[] materialPaletteIndex,
		byte[] solidMask,
		List<RegionExtension> extensions,
		long checksum
) {
	public RegionSectionState {
		Objects.requireNonNull(regionId, "regionId");
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(markerOrdinals, "markerOrdinals");
		Objects.requireNonNull(positionVolume, "positionVolume");
		Objects.requireNonNull(velocities, "velocities");
		Objects.requireNonNull(markerMass, "markerMass");
		Objects.requireNonNull(materialPaletteIndex, "materialPaletteIndex");
		Objects.requireNonNull(solidMask, "solidMask");
		extensions = List.copyOf(extensions);
		if (checkpointGeneration <= 0L || sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
			throw new IllegalArgumentException("Invalid section identity or dimensions");
		}
		if (positionVolume.length != Math.multiplyExact(markerOrdinals.length, 4)
				|| velocities.length != Math.multiplyExact(markerOrdinals.length, 3)) {
			throw new IllegalArgumentException("Section marker arrays disagree");
		}
		if (solidMask.length != Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ)) {
			throw new IllegalArgumentException("Section solid slice has the wrong size");
		}
		if (formatVersion == 1) {
			if (markerMass.length != 0 || materialPaletteIndex.length != 0) {
				throw new IllegalArgumentException("region-state v1 cannot contain material state");
			}
		} else if (formatVersion == 2) {
			if (markerMass.length != markerOrdinals.length || materialPaletteIndex.length != markerOrdinals.length) {
				throw new IllegalArgumentException("Section material arrays disagree");
			}
			for (int marker = 0; marker < markerMass.length; marker++) {
				if (!(Double.isFinite(markerMass[marker]) && markerMass[marker] > 0.0)
						|| materialPaletteIndex[marker] < 0) {
					throw new IllegalArgumentException("Invalid section marker material state");
				}
			}
		} else {
			throw new IllegalArgumentException("Unsupported region-state version " + formatVersion);
		}
		markerOrdinals = markerOrdinals.clone();
		positionVolume = positionVolume.clone();
		velocities = velocities.clone();
		markerMass = markerMass.clone();
		materialPaletteIndex = materialPaletteIndex.clone();
		solidMask = solidMask.clone();
	}

	/** Compatibility constructor for the byte-stable pure-water v1 format. */
	public RegionSectionState(
			UUID regionId, String dimensionId, long checkpointGeneration, int sectionX, int sectionY, int sectionZ,
			int localStartX, int localStartY, int localStartZ, int sizeX, int sizeY, int sizeZ,
			int[] markerOrdinals, float[] positionVolume, float[] velocities, byte[] solidMask,
			List<RegionExtension> extensions, long checksum
	) {
		this(1, regionId, dimensionId, checkpointGeneration, sectionX, sectionY, sectionZ,
				localStartX, localStartY, localStartZ, sizeX, sizeY, sizeZ, markerOrdinals, positionVolume, velocities,
				new double[0], new int[0], solidMask, extensions, checksum);
	}

	@Override public int[] markerOrdinals() { return markerOrdinals.clone(); }
	@Override public float[] positionVolume() { return positionVolume.clone(); }
	@Override public float[] velocities() { return velocities.clone(); }
	@Override public double[] markerMass() { return markerMass.clone(); }
	@Override public int[] materialPaletteIndex() { return materialPaletteIndex.clone(); }
	@Override public byte[] solidMask() { return solidMask.clone(); }

	public int markerCount() { return markerOrdinals.length; }

	public SectionKey key() { return new SectionKey(sectionX, sectionY, sectionZ); }

	public record SectionKey(int x, int y, int z) implements Comparable<SectionKey> {
		@Override public int compareTo(SectionKey other) {
			int byX = Integer.compare(x, other.x);
			if (byX != 0) return byX;
			int byY = Integer.compare(y, other.y);
			return byY != 0 ? byY : Integer.compare(z, other.z);
		}
	}
}
