package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Resolves Minecraft positions and player rays without owning or mutating region state. */
final class FluidWorldTargetResolver {
	private static final double AIM_DISTANCE = 16.0;

	private final FluidRegionManager regionManager;

	FluidWorldTargetResolver(FluidRegionManager regionManager) {
		this.regionManager = regionManager;
	}

	FluidRegion selectedRegionIn(ServerLevel level) {
		FluidRegion selected = regionManager.selected();
		return selected != null && selected.dimension().equals(level.dimension()) ? selected : null;
	}

	/** Returns the sole region whose actual interior contains the block position. */
	FluidRegion regionAtBlock(ServerLevel level, BlockPos position) {
		if (position == null) return null;
		for (FluidRegion region : regionManager.regions()) {
			if (!region.dimension().equals(level.dimension())) continue;
			FluidRegionFootprint footprint = region.footprint();
			if (footprint != null) {
				if (footprint.containsInterior(position)) return region;
				continue;
			}
			BlockPos origin = region.origin();
			double cellSize = region.solverConfig().cellSize();
			if (position.getX() >= origin.getX() && position.getX() < origin.getX() + region.sizeX() * cellSize
					&& position.getY() >= origin.getY() && position.getY() < origin.getY() + region.sizeY() * cellSize
					&& position.getZ() >= origin.getZ() && position.getZ() < origin.getZ() + region.sizeZ() * cellSize) {
				return region;
			}
		}
		return null;
	}

	/**
	 * Resolves an interior or visible controller shell block. A shell is a port only when it
	 * identifies one face-adjacent interior in one region; shared, edge and corner shells fail closed.
	 */
	TransferTarget transferTargetAtBlock(
			ServerLevel level, BlockPos position, Direction boundaryInteriorDirection
	) {
		if (position == null) return null;
		FluidRegion direct = regionAtBlock(level, position);
		if (direct != null) return new TransferTarget(direct, position.immutable());
		if (!MmmBlocks.isBoundary(level.getBlockState(position))) return null;

		TransferTarget resolved = null;
		for (FluidRegion region : regionManager.regions()) {
			FluidRegionFootprint footprint = region.footprint();
			if (footprint == null || !region.dimension().equals(level.dimension())) continue;
			BlockPos interior = boundaryInteriorDirection == null
					? footprint.uniqueAdjacentInterior(position)
					: footprint.adjacentInteriorAlong(position, boundaryInteriorDirection);
			if (interior == null) continue;
			if (resolved != null) return null;
			resolved = new TransferTarget(region, interior);
		}
		return resolved;
	}

	/** First region volume intersected by the player's unobstructed ray. */
	FluidRegion aimedRegion(ServerPlayer player) {
		Vec3 start = player.getEyePosition();
		Vec3 direction = player.getLookAngle();
		double visibleDistance = visibleAimDistance(player);
		for (double distance = 0.0; distance <= visibleDistance; distance += 0.125) {
			Vec3 point = start.add(direction.scale(distance));
			for (FluidRegion region : regionManager.regions()) {
				if (!region.dimension().equals(player.level().dimension())) continue;
				FluidRegionFootprint footprint = region.footprint();
				if (footprint != null && !footprint.containsInterior(BlockPos.containing(point))) continue;
				BlockPos origin = region.origin();
				double cellSize = region.solverConfig().cellSize();
				if (point.x >= origin.getX() && point.x < origin.getX() + region.sizeX() * cellSize
						&& point.y >= origin.getY() && point.y < origin.getY() + region.sizeY() * cellSize
						&& point.z >= origin.getZ() && point.z < origin.getZ() + region.sizeZ() * cellSize) {
					return region;
				}
			}
		}
		return null;
	}

	/** Exact targeting semantics used by the development pour/drain commands. */
	CellTarget aimedCellForEdit(ServerPlayer player, FluidRegion region, boolean requireFluid) {
		if (!validPlayerRegion(player, region)) return null;
		FlipFluidDomain domain = region.domain();
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		double visibleDistance = visibleAimDistance(player);
		double step = domain.config().cellSize() * 0.25;
		CellTarget previousOpen = null;
		CellTarget previousSample = null;
		boolean enteredDomain = false;

		for (double distance = 0.0; distance <= visibleDistance; distance += step) {
			CellTarget cell = worldToCell(region, eye.add(look.scale(distance)));
			if (cell == null) {
				if (enteredDomain && !requireFluid) return previousOpen;
				continue;
			}
			enteredDomain = true;
			if (cell.equals(previousSample)) continue;
			previousSample = cell;
			if (requireFluid) {
				if (hasFluid(domain, cell)) return cell;
			} else if (domain.isSolid(cell.x(), cell.y(), cell.z())) {
				return previousOpen;
			} else if (hasFluid(domain, cell)) {
				return previousOpen == null ? cell : previousOpen;
			} else {
				previousOpen = cell;
			}
		}
		return requireFluid ? null : previousOpen;
	}

	/** Resolves the solver cell containing the center of an interior block. */
	int[] cellAtBlock(ServerLevel level, FluidRegion region, BlockPos position) {
		if (!level.dimension().equals(region.dimension()) || !region.solverLoaded()) return null;
		if (region.footprint() != null && !region.footprint().containsInterior(position)) return null;
		FlipFluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		double cellSize = domain.config().cellSize();
		int x = Mth.floor((position.getX() + 0.5 - origin.getX()) / cellSize);
		int y = Mth.floor((position.getY() + 0.5 - origin.getY()) / cellSize);
		int z = Mth.floor((position.getZ() + 0.5 - origin.getZ()) / cellSize);
		if (!inBounds(domain, x, y, z)) return null;
		return new int[] {x, y, z};
	}

	/** Resolves the transfer target cell near the player's aim; requireFluid selects scoop semantics. */
	int[] aimedCellNear(ServerPlayer player, FluidRegion region, boolean requireFluid) {
		if (!validPlayerRegion(player, region)) return null;
		Vec3 start = player.getEyePosition();
		Vec3 direction = player.getLookAngle();
		FlipFluidDomain domain = region.domain();
		double visibleDistance = visibleAimDistance(player);
		double step = domain.config().cellSize() * 0.25;
		CellTarget previousOpen = null;
		CellTarget previousSample = null;
		for (double distance = 0.0; distance <= visibleDistance; distance += step) {
			CellTarget cell = worldToCell(region, start.add(direction.scale(distance)));
			if (cell == null) {
				if (!requireFluid && previousOpen != null) return previousOpen.toArray();
				continue;
			}
			if (cell.equals(previousSample)) continue;
			previousSample = cell;
			if (requireFluid) {
				if (hasFluid(domain, cell)) return cell.toArray();
			} else if (domain.isSolid(cell.x(), cell.y(), cell.z())) {
				return previousOpen == null ? null : previousOpen.toArray();
			} else if (hasFluid(domain, cell)) {
				return (previousOpen == null ? cell : previousOpen).toArray();
			} else {
				previousOpen = cell;
			}
		}
		return null;
	}

	/** First ray-intersected cell carrying active or exposed/deep granular inventory truth. */
	int[] aimedGranularCellNear(ServerPlayer player, FluidRegion region) {
		if (!validPlayerRegion(player, region) || !region.domain().hasGranularState()) return null;
		Vec3 start = player.getEyePosition();
		Vec3 direction = player.getLookAngle();
		FlipFluidDomain domain = region.domain();
		double visibleDistance = visibleAimDistance(player);
		double step = domain.config().cellSize() * 0.25;
		CellTarget previous = null;
		for (double distance = 0.0; distance <= visibleDistance; distance += step) {
			CellTarget cell = worldToCell(region, start.add(direction.scale(distance)));
			if (cell == null || cell.equals(previous)) continue;
			previous = cell;
			if (domain.granularSolidVolumeSvq(cell.x(), cell.y(), cell.z()) > 0L) return cell.toArray();
		}
		return null;
	}

	/** First condensed cell after entering a uniquely resolved shell interior, using exact grid-boundary DDA. */
	int[] firstCondensedCellAlong(FluidRegion region, Vec3 interiorPoint, Vec3 direction) {
		if (region == null || !region.solverLoaded() || interiorPoint == null || direction == null) return null;
		FlipFluidDomain domain = region.domain();
		double h = domain.config().cellSize();
		return firstMatchingCell(
				(interiorPoint.x - region.origin().getX()) / h,
				(interiorPoint.y - region.origin().getY()) / h,
				(interiorPoint.z - region.origin().getZ()) / h,
				direction.x, direction.y, direction.z,
				domain.sizeX(), domain.sizeY(), domain.sizeZ(),
				domain::hasCondensedInventoryInCell);
	}

	static int[] firstMatchingCell(
			double startX, double startY, double startZ,
			double directionX, double directionY, double directionZ,
			int sizeX, int sizeY, int sizeZ, CellPredicate predicate
	) {
		if (!(Double.isFinite(startX) && Double.isFinite(startY) && Double.isFinite(startZ)
				&& Double.isFinite(directionX) && Double.isFinite(directionY) && Double.isFinite(directionZ))
				|| sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || predicate == null) return null;
		int x = Mth.floor(startX), y = Mth.floor(startY), z = Mth.floor(startZ);
		if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) return null;
		int stepX = directionX > 0.0 ? 1 : directionX < 0.0 ? -1 : 0;
		int stepY = directionY > 0.0 ? 1 : directionY < 0.0 ? -1 : 0;
		int stepZ = directionZ > 0.0 ? 1 : directionZ < 0.0 ? -1 : 0;
		if (stepX == 0 && stepY == 0 && stepZ == 0) return null;
		double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(directionX);
		double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(directionY);
		double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(directionZ);
		double tMaxX = firstBoundaryDistance(startX, x, directionX, stepX);
		double tMaxY = firstBoundaryDistance(startY, y, directionY, stepY);
		double tMaxZ = firstBoundaryDistance(startZ, z, directionZ, stepZ);
		int maximumVisited = Math.addExact(Math.addExact(sizeX, sizeY), Math.addExact(sizeZ, 1));
		for (int visited = 0; visited < maximumVisited; visited++) {
			if (predicate.matches(x, y, z)) return new int[] {x, y, z};
			double next = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
			if (sameBoundary(tMaxX, next)) { x += stepX; tMaxX += tDeltaX; }
			if (sameBoundary(tMaxY, next)) { y += stepY; tMaxY += tDeltaY; }
			if (sameBoundary(tMaxZ, next)) { z += stepZ; tMaxZ += tDeltaZ; }
			if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) return null;
		}
		return null;
	}

	private static double firstBoundaryDistance(double start, int cell, double direction, int step) {
		if (step == 0) return Double.POSITIVE_INFINITY;
		double boundary = step > 0 ? cell + 1.0 : cell;
		return (boundary - start) / direction;
	}

	private static boolean sameBoundary(double candidate, double minimum) {
		if (candidate == minimum) return true;
		return Double.isFinite(candidate) && Double.isFinite(minimum)
				&& Math.abs(candidate - minimum) <= Math.max(Math.ulp(candidate), Math.ulp(minimum)) * 2.0;
	}

	private static boolean validPlayerRegion(ServerPlayer player, FluidRegion region) {
		return region != null && region.solverLoaded() && player.level().dimension().equals(region.dimension());
	}

	private static boolean hasFluid(FlipFluidDomain domain, CellTarget cell) {
		return domain.volume(cell.x(), cell.y(), cell.z()) > domain.config().fluidEpsilon();
	}

	private static CellTarget worldToCell(FluidRegion region, Vec3 point) {
		if (region == null || !region.solverLoaded()) return null;
		FluidRegionFootprint footprint = region.footprint();
		if (footprint != null && !footprint.containsInterior(BlockPos.containing(point))) return null;
		FlipFluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		float cellSize = domain.config().cellSize();
		int x = Mth.floor((point.x - origin.getX()) / cellSize);
		int y = Mth.floor((point.y - origin.getY()) / cellSize);
		int z = Mth.floor((point.z - origin.getZ()) / cellSize);
		return inBounds(domain, x, y, z) ? new CellTarget(x, y, z) : null;
	}

	private static boolean inBounds(FlipFluidDomain domain, int x, int y, int z) {
		return x >= 0 && x < domain.sizeX() && y >= 0 && y < domain.sizeY()
				&& z >= 0 && z < domain.sizeZ();
	}

	/** Stops simulation-space targeting at the first Minecraft collision hit. */
	private static double visibleAimDistance(ServerPlayer player) {
		Vec3 eye = player.getEyePosition();
		Vec3 hit = player.pick(AIM_DISTANCE, 1.0F, false).getLocation();
		return Math.min(AIM_DISTANCE, eye.distanceTo(hit) + 1.0E-6);
	}

	record CellTarget(int x, int y, int z) {
		int[] toArray() {
			return new int[] {x, y, z};
		}
	}

	record TransferTarget(FluidRegion region, BlockPos interiorBlock) { }

	@FunctionalInterface
	interface CellPredicate { boolean matches(int x, int y, int z); }
}
