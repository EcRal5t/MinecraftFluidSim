package mmm.model.region;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One complete Minecraft-aligned solver slice plus the markers owned by that slice. */
public record RegionSectionState(
		RegionStateLayout layout,
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
		int[] chemicalMixtureIndex,
		byte[] indicator,
		long[] chemicalSpeciesTotals,
		long chemicalResidualLedgerBits,
		long checksum
) {
	public RegionSectionState {
		Objects.requireNonNull(layout, "layout");
		Objects.requireNonNull(regionId, "regionId");
		Objects.requireNonNull(dimensionId, "dimensionId");
		Objects.requireNonNull(markerOrdinals, "markerOrdinals");
		Objects.requireNonNull(positionVolume, "positionVolume");
		Objects.requireNonNull(velocities, "velocities");
		Objects.requireNonNull(markerMass, "markerMass");
		Objects.requireNonNull(materialPaletteIndex, "materialPaletteIndex");
		Objects.requireNonNull(solidMask, "solidMask");
		Objects.requireNonNull(chemicalMixtureIndex, "chemicalMixtureIndex");
		Objects.requireNonNull(indicator, "indicator");
		Objects.requireNonNull(chemicalSpeciesTotals, "chemicalSpeciesTotals");
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
		if (!layout.hasMaterials()) {
			if (markerMass.length != 0 || materialPaletteIndex.length != 0
					|| chemicalMixtureIndex.length != 0 || indicator.length != 0
					|| chemicalSpeciesTotals.length != 0 || chemicalResidualLedgerBits != 0L) {
				throw new IllegalArgumentException("Pure region state cannot contain material state");
			}
		} else {
			if (markerMass.length != markerOrdinals.length || materialPaletteIndex.length != markerOrdinals.length) {
				throw new IllegalArgumentException("Section material arrays disagree");
			}
			for (int marker = 0; marker < markerMass.length; marker++) {
				if (!(Double.isFinite(markerMass[marker]) && markerMass[marker] > 0.0)
						|| materialPaletteIndex[marker] < 0) {
					throw new IllegalArgumentException("Invalid section marker material state");
				}
			}
			boolean chemicalState = layout.requiresChemistry()
					|| layout.permitsOptionalChemistry() && chemicalSpeciesTotals.length > 0;
			if (!chemicalState) {
				if (chemicalMixtureIndex.length != 0 || indicator.length != 0
						|| chemicalSpeciesTotals.length != 0 || chemicalResidualLedgerBits != 0L) {
					throw new IllegalArgumentException("Non-chemical region state cannot contain chemical state");
				}
			} else {
				if (chemicalMixtureIndex.length != markerOrdinals.length || indicator.length != markerOrdinals.length
						|| chemicalSpeciesTotals.length == 0
						|| !Double.isFinite(Double.longBitsToDouble(chemicalResidualLedgerBits))) {
					throw new IllegalArgumentException("Section chemical arrays disagree");
				}
				for (int marker = 0; marker < markerOrdinals.length; marker++) {
					if (chemicalMixtureIndex[marker] < 0 || (indicator[marker] & ~1) != 0) {
						throw new IllegalArgumentException("Invalid section chemical marker state");
					}
				}
			}
		}
		markerOrdinals = markerOrdinals.clone();
		positionVolume = positionVolume.clone();
		velocities = velocities.clone();
		markerMass = markerMass.clone();
		materialPaletteIndex = materialPaletteIndex.clone();
		chemicalMixtureIndex = chemicalMixtureIndex.clone();
		indicator = indicator.clone();
		chemicalSpeciesTotals = chemicalSpeciesTotals.clone();
		solidMask = solidMask.clone();
	}

	/** Convenience constructor for the pure-water feature layout. */
	public RegionSectionState(
			UUID regionId, String dimensionId, long checkpointGeneration, int sectionX, int sectionY, int sectionZ,
			int localStartX, int localStartY, int localStartZ, int sizeX, int sizeY, int sizeZ,
			int[] markerOrdinals, float[] positionVolume, float[] velocities, byte[] solidMask,
			List<RegionExtension> extensions, long checksum
	) {
		this(RegionStateLayout.PURE, regionId, dimensionId, checkpointGeneration, sectionX, sectionY, sectionZ,
				localStartX, localStartY, localStartZ, sizeX, sizeY, sizeZ, markerOrdinals, positionVolume, velocities,
				new double[0], new int[0], solidMask, extensions, new int[0], new byte[0], new long[0], 0L, checksum);
	}

	/** Convenience constructor for non-chemical feature layouts. */
	public RegionSectionState(
			RegionStateLayout layout, UUID regionId, String dimensionId, long checkpointGeneration,
			int sectionX, int sectionY, int sectionZ, int localStartX, int localStartY, int localStartZ,
			int sizeX, int sizeY, int sizeZ, int[] markerOrdinals, float[] positionVolume, float[] velocities,
			double[] markerMass, int[] materialPaletteIndex, byte[] solidMask,
			List<RegionExtension> extensions, long checksum
	) {
		this(layout, regionId, dimensionId, checkpointGeneration, sectionX, sectionY, sectionZ,
				localStartX, localStartY, localStartZ, sizeX, sizeY, sizeZ, markerOrdinals, positionVolume, velocities,
				markerMass, materialPaletteIndex, solidMask, extensions, new int[0], new byte[0], new long[0], 0L, checksum);
	}

	@Override public int[] markerOrdinals() { return markerOrdinals.clone(); }
	@Override public float[] positionVolume() { return positionVolume.clone(); }
	@Override public float[] velocities() { return velocities.clone(); }
	@Override public double[] markerMass() { return markerMass.clone(); }
	@Override public int[] materialPaletteIndex() { return materialPaletteIndex.clone(); }
	@Override public int[] chemicalMixtureIndex() { return chemicalMixtureIndex.clone(); }
	@Override public byte[] indicator() { return indicator.clone(); }
	@Override public long[] chemicalSpeciesTotals() { return chemicalSpeciesTotals.clone(); }
	@Override public byte[] solidMask() { return solidMask.clone(); }

	public int markerCount() { return markerOrdinals.length; }
	public boolean hasChemicalState() {
		return layout.requiresChemistry() || layout.permitsOptionalChemistry() && chemicalSpeciesTotals.length > 0;
	}

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

