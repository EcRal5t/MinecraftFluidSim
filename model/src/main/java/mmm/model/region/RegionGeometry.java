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
		ControllerFootprint footprint
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

	public enum RegionKind { CLOSED, POOL }

	/** Immutable block-space topology represented only by stable coordinates and packed membership. */
	public record ControllerFootprint(
			BlockPosition controllerPosition,
			BlockPosition minimumInterior,
			BlockPosition maximumInterior,
			Set<Long> interiorBlocks,
			Set<Long> boundaryBlocks,
			Set<Long> requiredChunks,
			RegionKind kind,
			Set<Long> atmosphereBlocks,
			Set<Long> returnedBlocks
	) {
		public ControllerFootprint(BlockPosition controllerPosition, BlockPosition minimumInterior, BlockPosition maximumInterior,
				Set<Long> interiorBlocks, Set<Long> boundaryBlocks, Set<Long> requiredChunks) {
			this(controllerPosition, minimumInterior, maximumInterior, interiorBlocks, boundaryBlocks, requiredChunks, RegionKind.CLOSED, Set.of(), Set.of());
		}

		public ControllerFootprint(BlockPosition controllerPosition, BlockPosition minimumInterior, BlockPosition maximumInterior,
				Set<Long> interiorBlocks, Set<Long> boundaryBlocks, Set<Long> requiredChunks, RegionKind kind, Set<Long> atmosphereBlocks) {
			this(controllerPosition, minimumInterior, maximumInterior, interiorBlocks, boundaryBlocks, requiredChunks, kind, atmosphereBlocks, Set.of());
		}

		public ControllerFootprint {
			Objects.requireNonNull(controllerPosition, "controllerPosition");
			Objects.requireNonNull(minimumInterior, "minimumInterior");
			Objects.requireNonNull(maximumInterior, "maximumInterior");
			interiorBlocks = Set.copyOf(interiorBlocks);
			boundaryBlocks = Set.copyOf(boundaryBlocks);
			requiredChunks = Set.copyOf(requiredChunks);
			Objects.requireNonNull(kind, "kind");
			atmosphereBlocks = Set.copyOf(atmosphereBlocks);
			returnedBlocks = Set.copyOf(returnedBlocks);
			if (!interiorBlocks.containsAll(returnedBlocks)) throw new IllegalArgumentException("Returned blocks must belong to the region");
			if (interiorBlocks.isEmpty() || kind == RegionKind.CLOSED && !boundaryBlocks.contains(controllerPosition.packed())
					|| requiredChunks.isEmpty() || minimumInterior.x() > maximumInterior.x()
					|| minimumInterior.y() > maximumInterior.y() || minimumInterior.z() > maximumInterior.z()) {
				throw new IllegalArgumentException("Invalid controller footprint");
			}
		}

		public int blockSizeX() { return Math.addExact(Math.subtractExact(maximumInterior.x(), minimumInterior.x()), 1); }
		public int blockSizeY() { return Math.addExact(Math.subtractExact(maximumInterior.y(), minimumInterior.y()), 1); }
		public int blockSizeZ() { return Math.addExact(Math.subtractExact(maximumInterior.z(), minimumInterior.z()), 1); }
	}
}
