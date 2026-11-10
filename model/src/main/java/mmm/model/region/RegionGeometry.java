package mmm.model.region;

import mmm.fluid.FluidSolverConfig;

import java.util.Objects;
import java.util.Set;

/** Loader-neutral solver geometry and controller ownership footprint. */
public record RegionGeometry(
		BlockPosition origin,
		int sizeX,
		int sizeY,
		int sizeZ,
		FluidSolverConfig solverConfig,
		SelectedVolume footprint
) {
	public RegionGeometry {
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(solverConfig, "solverConfig");
		Objects.requireNonNull(footprint, "footprint");
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
			throw new IllegalArgumentException("Invalid region checkpoint geometry");
		}
	}

	/** Integer world origins keep the full precision of solver-local coordinates. */
	public BlockPosition worldBlockPosition(double localX, double localY, double localZ) {
		return new BlockPosition(Math.addExact(origin.x(), (int) Math.floor(localX)),
				Math.addExact(origin.y(), (int) Math.floor(localY)),
				Math.addExact(origin.z(), (int) Math.floor(localZ)));
	}

	/** Stable block coordinate with the existing checkpoint packing kept explicit. */
	public record BlockPosition(int x, int y, int z) {
		public int getX() { return x; }
		public int getY() { return y; }
		public int getZ() { return z; }
		public long asLong() { return packed(); }

		public long packed() {
			return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | (long) y & 0xfffL;
		}

		public static BlockPosition fromPacked(long packed) {
			return new BlockPosition((int) (packed << 0 >> 38), (int) (packed << 52 >> 52), (int) (packed << 26 >> 38));
		}
	}

	public enum RegionKind { SELECTED, POOL }

    /** Selected world blocks and pool ownership metadata. */
    public record SelectedVolume(BlockPosition minimumInterior, BlockPosition maximumInterior,
            Set<Long> interiorBlocks, Set<Long> requiredChunks, RegionKind kind,
            Set<Long> poolShellBlocks, Set<Long> atmosphereBlocks, Set<Long> returnedBlocks, String name) {
        public SelectedVolume {
            Objects.requireNonNull(minimumInterior); Objects.requireNonNull(maximumInterior);
            interiorBlocks = Set.copyOf(interiorBlocks); requiredChunks = Set.copyOf(requiredChunks);
            poolShellBlocks = Set.copyOf(poolShellBlocks); atmosphereBlocks = Set.copyOf(atmosphereBlocks);
            returnedBlocks = Set.copyOf(returnedBlocks); Objects.requireNonNull(kind);
            if (name == null || name.isBlank() || name.length() > 64) throw new IllegalArgumentException("Invalid region name");
            if (interiorBlocks.isEmpty() || requiredChunks.isEmpty() || !interiorBlocks.containsAll(returnedBlocks)
                    || minimumInterior.x() > maximumInterior.x() || minimumInterior.y() > maximumInterior.y()
                    || minimumInterior.z() > maximumInterior.z()) throw new IllegalArgumentException("Invalid selected volume");
        }
        public int blockSizeX() { return Math.addExact(Math.subtractExact(maximumInterior.x(), minimumInterior.x()), 1); }
        public int blockSizeY() { return Math.addExact(Math.subtractExact(maximumInterior.y(), minimumInterior.y()), 1); }
        public int blockSizeZ() { return Math.addExact(Math.subtractExact(maximumInterior.z(), minimumInterior.z()), 1); }
    }
}
