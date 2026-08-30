package cc.ecisr.mmm.model.region;

import cc.ecisr.mmm.fluid.FluidSolverConfig;

import java.util.Objects;
import java.util.Set;

/** Loader-neutral solver geometry and controller ownership footprint. */
public record RegionGeometry(
		BlockPosition origin,
		int sizeX,
		int sizeY,
		int sizeZ,
		FluidSolverConfig solverConfig,
		long reservedMarkerCapacity,
		ControllerFootprint footprint
) {
	public RegionGeometry {
		Objects.requireNonNull(origin, "origin");
		Objects.requireNonNull(solverConfig, "solverConfig");
		Objects.requireNonNull(footprint, "footprint");
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
				|| reservedMarkerCapacity <= 0L || reservedMarkerCapacity > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Invalid region checkpoint geometry");
		}
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

	/** Immutable block-space topology represented only by stable coordinates and packed membership. */
	public record ControllerFootprint(
			BlockPosition controllerPosition,
			BlockPosition minimumInterior,
			BlockPosition maximumInterior,
			Set<Long> interiorBlocks,
			Set<Long> boundaryBlocks,
			Set<Long> requiredChunks
	) {
		public ControllerFootprint {
			Objects.requireNonNull(controllerPosition, "controllerPosition");
			Objects.requireNonNull(minimumInterior, "minimumInterior");
			Objects.requireNonNull(maximumInterior, "maximumInterior");
			interiorBlocks = Set.copyOf(interiorBlocks);
			boundaryBlocks = Set.copyOf(boundaryBlocks);
			requiredChunks = Set.copyOf(requiredChunks);
			if (interiorBlocks.isEmpty() || !boundaryBlocks.contains(controllerPosition.packed())
					|| requiredChunks.isEmpty() || minimumInterior.x() > maximumInterior.x()
					|| minimumInterior.y() > maximumInterior.y() || minimumInterior.z() > maximumInterior.z()) {
				throw new IllegalArgumentException("Invalid controller footprint");
			}
			for (int chunkZ = minimumInterior.z() >> 4; chunkZ <= maximumInterior.z() >> 4; chunkZ++) {
				for (int chunkX = minimumInterior.x() >> 4; chunkX <= maximumInterior.x() >> 4; chunkX++) {
					long packedChunk = (long) chunkX & 0xffffffffL | ((long) chunkZ & 0xffffffffL) << 32;
					if (!requiredChunks.contains(packedChunk)) {
						throw new IllegalArgumentException("Required chunks must cover the complete dense solver bounds");
					}
				}
			}
		}

		public int blockSizeX() { return Math.addExact(Math.subtractExact(maximumInterior.x(), minimumInterior.x()), 1); }
		public int blockSizeY() { return Math.addExact(Math.subtractExact(maximumInterior.y(), minimumInterior.y()), 1); }
		public int blockSizeZ() { return Math.addExact(Math.subtractExact(maximumInterior.z(), minimumInterior.z()), 1); }
	}
}
