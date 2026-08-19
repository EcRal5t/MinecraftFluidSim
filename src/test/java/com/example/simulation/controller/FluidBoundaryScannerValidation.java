package com.example.simulation.controller;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.Set;

/** Deterministic closed-shell, ambiguity, bounded-search and non-loading chunk regression. */
public final class FluidBoundaryScannerValidation {
	private FluidBoundaryScannerValidation() { }

	public static void main(String[] args) {
		validateClosedVisibleShell();
		validateBreachIsRejected();
		validateCrossChunkShell();
		validateMissingChunkFailsWithoutStateRead();
		validateRuntimeAvailabilityGuard();
		validateAmbiguousControllerIsRejected();
		validateScanBudget();
		System.out.println("Fluid boundary scanner validation passed");
	}

	private static void validateClosedVisibleShell() {
		FakeWorld world = new FakeWorld();
		world.loadChunk(0, 0);
		BlockPos controller = new BlockPos(0, 1, 1);
		world.addShell(new BlockPos(0, 0, 0), new BlockPos(2, 2, 2));
		FluidBoundaryScanner.ScanResult result = FluidBoundaryScanner.scan(controller, world, 32);
		require(result.succeeded(), "one-block closed interior was not found: " + result.failure());
		FluidRegionFootprint footprint = result.footprint();
		require(footprint.interiorBlocks().equals(Set.of(new BlockPos(1, 1, 1).asLong())),
				"closed scan included a boundary or outside block");
		require(footprint.boundaryBlocks().size() == 6 && footprint.requiredChunks().size() == 1,
				"closed scan did not retain its visible shell/chunk ownership");
	}

	private static void validateBreachIsRejected() {
		FakeWorld world = new FakeWorld();
		world.loadChunk(0, 0);
		BlockPos controller = new BlockPos(0, 1, 1);
		world.addShell(new BlockPos(0, 0, 0), new BlockPos(2, 2, 2));
		world.boundaries.remove(new BlockPos(2, 1, 1).asLong());
		FluidBoundaryScanner.ScanResult result = FluidBoundaryScanner.scan(controller, world, 32);
		require(!result.succeeded(), "a visible boundary breach was accepted");
	}

	private static void validateCrossChunkShell() {
		FakeWorld world = new FakeWorld();
		world.loadChunk(0, 0);
		world.loadChunk(1, 0);
		BlockPos controller = new BlockPos(14, 1, 1);
		world.addShell(new BlockPos(14, 0, 0), new BlockPos(18, 2, 2));
		FluidBoundaryScanner.ScanResult result = FluidBoundaryScanner.scan(controller, world, 64);
		require(result.succeeded(), "fully loaded cross-chunk shell was rejected: " + result.failure());
		require(result.footprint().interiorBlocks().size() == 3, "cross-chunk interior size changed");
		require(result.footprint().requiredChunks().equals(Set.of(ChunkPos.pack(0, 0), ChunkPos.pack(1, 0))),
				"cross-chunk ownership did not include both loaded chunks");
	}

	private static void validateMissingChunkFailsWithoutStateRead() {
		FakeWorld world = new FakeWorld();
		world.loadChunk(0, 0);
		BlockPos controller = new BlockPos(14, 1, 1);
		world.addShell(new BlockPos(14, 0, 0), new BlockPos(18, 2, 2));
		FluidBoundaryScanner.ScanResult result = FluidBoundaryScanner.scan(controller, world, 64);
		require(result.status() == FluidBoundaryScanner.Status.INCOMPLETE_CHUNKS,
				"missing associated chunk did not fail closed: " + result.status());
		require(world.unloadedStateReads == 0, "scanner queried block state in an unloaded chunk");
	}

	private static void validateRuntimeAvailabilityGuard() {
		FakeWorld world = new FakeWorld();
		world.loadChunk(0, 0);
		world.loadChunk(1, 0);
		BlockPos controller = new BlockPos(14, 1, 1);
		world.addShell(new BlockPos(14, 0, 0), new BlockPos(18, 2, 2));
		FluidRegionFootprint footprint = FluidBoundaryScanner.scan(controller, world, 64).footprint();
		require(FluidBoundaryScanner.validateAvailability(footprint, world).ready(),
				"newly scanned footprint was not runtime-ready");

		world.unloadChunk(1, 0);
		int readsBeforeMissingChunkCheck = world.boundaryStateReads;
		FluidBoundaryScanner.AvailabilityResult missing = FluidBoundaryScanner.validateAvailability(footprint, world);
		require(missing.status() == FluidBoundaryScanner.AvailabilityStatus.INCOMPLETE_CHUNKS,
				"runtime guard did not reject a missing associated chunk");
		require(world.boundaryStateReads == readsBeforeMissingChunkCheck,
				"runtime guard read boundary state before completing the all-chunk preflight");

		world.loadChunk(1, 0);
		world.boundaries.remove(new BlockPos(18, 1, 1).asLong());
		FluidBoundaryScanner.AvailabilityResult breached = FluidBoundaryScanner.validateAvailability(footprint, world);
		require(breached.status() == FluidBoundaryScanner.AvailabilityStatus.OPEN_BOUNDARY,
				"runtime guard did not reject a removed original boundary block");
	}

	private static void validateAmbiguousControllerIsRejected() {
		FakeWorld world = new FakeWorld();
		world.loadChunk(-1, 0);
		world.loadChunk(0, 0);
		BlockPos controller = new BlockPos(0, 1, 1);
		world.addShell(new BlockPos(-2, 0, 0), new BlockPos(0, 2, 2));
		world.addShell(new BlockPos(0, 0, 0), new BlockPos(2, 2, 2));
		FluidBoundaryScanner.ScanResult result = FluidBoundaryScanner.scan(controller, world, 32);
		require(result.status() == FluidBoundaryScanner.Status.AMBIGUOUS_INTERIORS,
				"controller touching two chambers did not fail as ambiguous: " + result.status());
	}

	private static void validateScanBudget() {
		FakeWorld world = new FakeWorld();
		for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
			for (int chunkX = -1; chunkX <= 1; chunkX++) world.loadChunk(chunkX, chunkZ);
		}
		BlockPos controller = new BlockPos(0, 1, 1);
		world.addShell(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3));
		FluidBoundaryScanner.ScanResult result = FluidBoundaryScanner.scan(controller, world, 4);
		require(result.status() == FluidBoundaryScanner.Status.BUDGET_EXCEEDED,
				"oversized interior did not stop at the hard scan budget: " + result.status());
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static final class FakeWorld implements FluidBoundaryScanner.ScanWorld {
		private final Set<Long> boundaries = new HashSet<>();
		private final Set<Long> loadedChunks = new HashSet<>();
		private int unloadedStateReads;
		private int boundaryStateReads;

		private void loadChunk(int chunkX, int chunkZ) {
			loadedChunks.add(ChunkPos.pack(chunkX, chunkZ));
		}

		private void unloadChunk(int chunkX, int chunkZ) {
			loadedChunks.remove(ChunkPos.pack(chunkX, chunkZ));
		}

		private void addShell(BlockPos minimum, BlockPos maximum) {
			for (int z = minimum.getZ(); z <= maximum.getZ(); z++) {
				for (int y = minimum.getY(); y <= maximum.getY(); y++) {
					for (int x = minimum.getX(); x <= maximum.getX(); x++) {
						if (x == minimum.getX() || x == maximum.getX()
								|| y == minimum.getY() || y == maximum.getY()
								|| z == minimum.getZ() || z == maximum.getZ()) {
							boundaries.add(new BlockPos(x, y, z).asLong());
						}
					}
				}
			}
		}

		@Override
		public boolean isInsideBuildHeight(BlockPos position) {
			return position.getY() >= -64 && position.getY() < 320;
		}

		@Override
		public boolean isChunkLoaded(int chunkX, int chunkZ) {
			return loadedChunks.contains(ChunkPos.pack(chunkX, chunkZ));
		}

		@Override
		public boolean isBoundary(BlockPos position) {
			if (!isChunkLoaded(position.getX() >> 4, position.getZ() >> 4)) {
				unloadedStateReads++;
				throw new AssertionError("unloaded block state access at " + position);
			}
			boundaryStateReads++;
			return boundaries.contains(position.asLong());
		}
	}
}
