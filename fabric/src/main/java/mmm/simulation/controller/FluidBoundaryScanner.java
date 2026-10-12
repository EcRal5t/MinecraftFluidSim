package mmm.simulation.controller;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded six-neighbour flood fill for the interior next to a visible controller boundary.
 * The supplied world view must perform non-loading chunk checks before exposing block state.
 */
public final class FluidBoundaryScanner {
	private FluidBoundaryScanner() { }

	public static ScanResult scan(BlockPos controllerPosition, ScanWorld world, int maximumInteriorBlocks) {
		Objects.requireNonNull(controllerPosition, "controllerPosition");
		Objects.requireNonNull(world, "world");
		if (maximumInteriorBlocks <= 0) throw new IllegalArgumentException("maximumInteriorBlocks must be positive");

		List<Component> closedComponents = new ArrayList<>();
		int incompleteComponents = 0;
		int oversizedComponents = 0;
		int openComponents = 0;
		for (Direction direction : Direction.values()) {
			BlockPos seed = controllerPosition.relative(direction);
			ComponentScan componentScan = scanComponent(controllerPosition, seed, world, maximumInteriorBlocks);
			switch (componentScan.status()) {
				case CLOSED -> {
					Component component = componentScan.component();
					boolean duplicate = closedComponents.stream()
							.anyMatch(existing -> existing.interior().contains(seed.asLong()));
					if (!duplicate) closedComponents.add(component);
				}
				case INCOMPLETE_CHUNKS -> incompleteComponents++;
				case TOO_LARGE -> oversizedComponents++;
				case OPEN_WORLD_BOUNDARY -> openComponents++;
				case BOUNDARY_SEED -> { }
			}
		}

		if (closedComponents.size() > 1) {
			return ScanResult.failed(Status.AMBIGUOUS_INTERIORS,
					"controller touches " + closedComponents.size() + " distinct closed interiors");
		}
		if (closedComponents.size() == 1) {
			return ScanResult.success(toFootprint(controllerPosition, closedComponents.getFirst()));
		}
		if (incompleteComponents > 0) {
			return ScanResult.failed(Status.INCOMPLETE_CHUNKS,
					"no closed interior was provable without entering an unloaded chunk");
		}
		if (oversizedComponents > 0) {
			return ScanResult.failed(Status.BUDGET_EXCEEDED,
					"no closed interior was found within the " + maximumInteriorBlocks + " block scan budget");
		}
		if (openComponents > 0) {
			return ScanResult.failed(Status.OPEN_BOUNDARY, "the candidate interior reaches the world build boundary");
		}
		return ScanResult.failed(Status.NO_INTERIOR, "every face of the controller touches another boundary block");
	}

	/** Revalidates the atomic runtime load/boundary guard without discovering a new topology. */
	public static AvailabilityResult validateAvailability(FluidRegionFootprint footprint, ScanWorld world) {
		Objects.requireNonNull(footprint, "footprint");
		Objects.requireNonNull(world, "world");
		for (long packedChunk : footprint.requiredChunks()) {
			ChunkPos chunk = ChunkPos.unpack(packedChunk);
			if (!world.isChunkLoaded(chunk.x(), chunk.z())) {
				return new AvailabilityResult(
						AvailabilityStatus.INCOMPLETE_CHUNKS,
						"required chunk " + chunk.x() + "," + chunk.z() + " is not fully loaded"
				);
			}
		}
		for (long packedBoundary : footprint.boundaryBlocks()) {
			BlockPos position = BlockPos.of(packedBoundary);
			if (!world.isBoundary(position)) {
				return new AvailabilityResult(
						AvailabilityStatus.OPEN_BOUNDARY,
						"visible boundary is open or replaced at " + position.toShortString()
				);
			}
		}
		return AvailabilityResult.READY;
	}

	private static ComponentScan scanComponent(
			BlockPos controllerPosition,
			BlockPos seed,
			ScanWorld world,
			int maximumInteriorBlocks
	) {
		CellAccess seedAccess = access(seed, world);
		if (seedAccess == CellAccess.OPEN_WORLD_BOUNDARY) return ComponentScan.failed(seedAccess);
		if (seedAccess == CellAccess.INCOMPLETE_CHUNKS) return ComponentScan.failed(seedAccess);
		if (seedAccess == CellAccess.BOUNDARY_SEED) return ComponentScan.failed(seedAccess);

		ArrayDeque<BlockPos> pending = new ArrayDeque<>();
		Set<Long> interior = new HashSet<>();
		Set<Long> boundary = new HashSet<>();
		Set<Long> chunks = new HashSet<>();
		pending.add(seed.immutable());
		interior.add(seed.asLong());
		addChunk(chunks, seed);

		int minimumX = seed.getX(), minimumY = seed.getY(), minimumZ = seed.getZ();
		int maximumX = seed.getX(), maximumY = seed.getY(), maximumZ = seed.getZ();
		while (!pending.isEmpty()) {
			BlockPos position = pending.removeFirst();
			minimumX = Math.min(minimumX, position.getX());
			minimumY = Math.min(minimumY, position.getY());
			minimumZ = Math.min(minimumZ, position.getZ());
			maximumX = Math.max(maximumX, position.getX());
			maximumY = Math.max(maximumY, position.getY());
			maximumZ = Math.max(maximumZ, position.getZ());

			for (Direction direction : Direction.values()) {
				BlockPos neighbour = position.relative(direction);
				CellAccess neighbourAccess = access(neighbour, world);
				if (neighbourAccess == CellAccess.OPEN_WORLD_BOUNDARY
						|| neighbourAccess == CellAccess.INCOMPLETE_CHUNKS) {
					return ComponentScan.failed(neighbourAccess);
				}
				addChunk(chunks, neighbour);
				if (neighbourAccess == CellAccess.BOUNDARY_SEED) {
					boundary.add(neighbour.asLong());
					continue;
				}
				if (!interior.add(neighbour.asLong())) continue;
				if (interior.size() > maximumInteriorBlocks) return ComponentScan.failed(CellAccess.TOO_LARGE);
				pending.addLast(neighbour.immutable());
			}
		}

		if (!boundary.contains(controllerPosition.asLong())) {
			throw new IllegalStateException("A scanned controller-adjacent component lost its controller boundary");
		}
		for (int chunkZ = minimumZ >> 4; chunkZ <= maximumZ >> 4; chunkZ++) {
			for (int chunkX = minimumX >> 4; chunkX <= maximumX >> 4; chunkX++) {
				if (!world.isChunkLoaded(chunkX, chunkZ)) {
					return ComponentScan.failed(CellAccess.INCOMPLETE_CHUNKS);
				}
				chunks.add(ChunkPos.pack(chunkX, chunkZ));
			}
		}
		return ComponentScan.closed(new Component(
				Set.copyOf(interior), Set.copyOf(boundary), Set.copyOf(chunks),
				new BlockPos(minimumX, minimumY, minimumZ), new BlockPos(maximumX, maximumY, maximumZ)
		));
	}

	private static CellAccess access(BlockPos position, ScanWorld world) {
		if (!world.isInsideBuildHeight(position)) return CellAccess.OPEN_WORLD_BOUNDARY;
		int chunkX = position.getX() >> 4;
		int chunkZ = position.getZ() >> 4;
		if (!world.isChunkLoaded(chunkX, chunkZ)) return CellAccess.INCOMPLETE_CHUNKS;
		return world.isBoundary(position) ? CellAccess.BOUNDARY_SEED : CellAccess.INTERIOR;
	}

	private static void addChunk(Set<Long> chunks, BlockPos position) {
		chunks.add(ChunkPos.pack(position.getX() >> 4, position.getZ() >> 4));
	}

	private static FluidRegionFootprint toFootprint(BlockPos controllerPosition, Component component) {
		return new FluidRegionFootprint(
				controllerPosition, component.minimum(), component.maximum(),
				component.interior(), component.boundary(), component.chunks()
		);
	}

	public interface ScanWorld {
		boolean isInsideBuildHeight(BlockPos position);

		boolean isChunkLoaded(int chunkX, int chunkZ);

		/** Called only after {@link #isChunkLoaded(int, int)} returned true for this position. */
		boolean isBoundary(BlockPos position);
	}

	public enum Status {
		SUCCESS,
		NO_INTERIOR,
		OPEN_BOUNDARY,
		INCOMPLETE_CHUNKS,
		BUDGET_EXCEEDED,
		AMBIGUOUS_INTERIORS
	}

	public record ScanResult(Status status, FluidRegionFootprint footprint, String failure) {
		private static ScanResult success(FluidRegionFootprint footprint) {
			return new ScanResult(Status.SUCCESS, footprint, null);
		}

		private static ScanResult failed(Status status, String failure) {
			return new ScanResult(status, null, failure);
		}

		public boolean succeeded() {
			return status == Status.SUCCESS;
		}
	}

	public enum AvailabilityStatus {
		READY,
		INCOMPLETE_CHUNKS,
		OPEN_BOUNDARY
	}

	public record AvailabilityResult(AvailabilityStatus status, String failure) {
		private static final AvailabilityResult READY = new AvailabilityResult(AvailabilityStatus.READY, null);

		public boolean ready() {
			return status == AvailabilityStatus.READY;
		}
	}

	private enum CellAccess {
		INTERIOR,
		BOUNDARY_SEED,
		OPEN_WORLD_BOUNDARY,
		INCOMPLETE_CHUNKS,
		TOO_LARGE
	}

	private enum ComponentStatus {
		CLOSED,
		BOUNDARY_SEED,
		OPEN_WORLD_BOUNDARY,
		INCOMPLETE_CHUNKS,
		TOO_LARGE
	}

	private record Component(
			Set<Long> interior,
			Set<Long> boundary,
			Set<Long> chunks,
			BlockPos minimum,
			BlockPos maximum
	) { }

	private record ComponentScan(ComponentStatus status, Component component) {
		private static ComponentScan closed(Component component) {
			return new ComponentScan(ComponentStatus.CLOSED, component);
		}

		private static ComponentScan failed(CellAccess status) {
			ComponentStatus componentStatus = switch (status) {
				case BOUNDARY_SEED -> ComponentStatus.BOUNDARY_SEED;
				case OPEN_WORLD_BOUNDARY -> ComponentStatus.OPEN_WORLD_BOUNDARY;
				case INCOMPLETE_CHUNKS -> ComponentStatus.INCOMPLETE_CHUNKS;
				case TOO_LARGE -> ComponentStatus.TOO_LARGE;
				case INTERIOR -> throw new IllegalArgumentException("An interior cell is not a component failure");
			};
			return new ComponentScan(componentStatus, null);
		}
	}
}
