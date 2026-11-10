package mmm.simulation;

import mmm.block.MmmBlocks;
import mmm.block.FluidPortBlock;
import mmm.simulation.controller.FluidRegionFootprint;
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

	/** Exact controller identity lookup; boundary adjacency is deliberately not accepted. */
	FluidRegion controllerRegionAt(ServerLevel level, BlockPos position) {
		if (position == null) return null;
		for (FluidRegion region : regionManager.regions()) {
			FluidRegionFootprint footprint = region.footprint();
			if (footprint != null && region.dimension().equals(level.dimension())
					&& footprint.controllerPosition().equals(position)) return region;
		}
		return null;
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

	/** Containers cross a shell only through the outward face of an explicit I/O port. */
	TransferTarget transferTargetAtBlock(ServerLevel level, BlockPos position, Direction inwardDirection) {
		if (position == null || !level.hasChunkAt(position)) return null;
		var state = level.getBlockState(position);
		if (state.is(MmmBlocks.FLUID_PORT)) return resolvePortTarget(regionManager.regions(), level.dimension(), position,
				state.getValue(FluidPortBlock.FACING), inwardDirection);
		if (MmmBlocks.isBoundary(state)) return null;
		FluidRegion direct = regionAtBlock(level, position);
		return direct != null && direct.solverLoaded() ? new TransferTarget(direct, position.immutable(), null) : null;
	}

	static TransferTarget resolvePortTarget(java.util.List<FluidRegion> regions,
			net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
			BlockPos position, Direction outward, Direction inward) {
		if (position == null || outward == null || inward == null || inward != outward.getOpposite()) return null;
		TransferTarget resolved = null;
		for (FluidRegion region : regions) {
			var footprint = region.footprint();
			if (footprint == null || !region.solverLoaded() || !region.dimension().equals(dimension)) continue;
			BlockPos interior = footprint.adjacentInteriorAlong(position, inward);
			if (interior == null) continue;
			if (resolved != null) return null;
			resolved = new TransferTarget(region, interior, outward);
		}
		return resolved;
	}

	/** Creative gas sources retain their independent, real shell-face interaction. */
	TransferTarget gasShellTargetAtBlock(
			ServerLevel level, BlockPos position, Direction boundaryInteriorDirection
	) {
		if (position == null) return null;
		FluidRegion direct = regionAtBlock(level, position);
		if (direct != null) return new TransferTarget(direct, position.immutable(), null);
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
			resolved = new TransferTarget(region, interior, null);
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
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		double visibleDistance = visibleAimDistance(player);
		double step = region.solverConfig().cellSize() * 0.25;
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
				if (hasFluid(region, cell)) return cell;
			} else if (region.solidMicroMask(cell.x(), cell.y(), cell.z()) == 255) {
				return previousOpen;
			} else if (hasFluid(region, cell)) {
				return previousOpen == null ? cell : previousOpen;
			} else {
				previousOpen = cell;
			}
		}
		return requireFluid ? null : previousOpen;
	}

	/** Selects the cell adjacent to the given outward face; null selects the block center. */
	static int[] cellAtInteriorFace(FluidRegion region, BlockPos position, Direction outward) {
		if (!region.solverLoaded()) return null;
		if (region.footprint() != null && !region.footprint().containsInterior(position)) return null;
		BlockPos origin = region.origin();
		double h = region.solverConfig().cellSize();
		int[] block = {position.getX()-origin.getX(), position.getY()-origin.getY(), position.getZ()-origin.getZ()};
		int[] cell = {Mth.floor((block[0]+0.5)/h), Mth.floor((block[1]+0.5)/h), Mth.floor((block[2]+0.5)/h)};
		if (outward != null) {
			int axis = outward.getAxis() == Direction.Axis.X ? 0 : outward.getAxis() == Direction.Axis.Y ? 1 : 2;
			cell[axis] = outward.getAxisDirection() == Direction.AxisDirection.NEGATIVE
					? Mth.floor(block[axis]/h) : Mth.floor(Math.nextDown((block[axis]+1.0)/h));
		}
		if (!inBounds(region, cell[0], cell[1], cell[2])
				|| (region.grid().cell(cell[0],cell[1],cell[2]) < 0 || !region.groupAvailable(region.grid().cell(cell[0],cell[1],cell[2])))) return null;
		return cell;
	}

	/** Selects an actual contact cell on the port's block face for one bounded transfer. */
	static int[] transferCell(FluidRegion region, BlockPos position, Direction outward,
			mmm.api.transfer.TransferQuantity quantity, boolean extract) {
		if (outward == null || !region.solverLoaded()) return null;
		return transferCell(region, position, outward, quantity, extract, region.hostSnapshot());
	}

	static int[] transferCell(FluidRegion region, BlockPos position, Direction outward,
			mmm.api.transfer.TransferQuantity quantity, boolean extract, mmm.fluid.HostFluidState host) {
		if(outward==null || !region.solverLoaded())return null;
		int scale=Math.round(1.0f/region.solverConfig().cellSize());
		int axis=outward.getAxis()==Direction.Axis.X?0:outward.getAxis()==Direction.Axis.Y?1:2;
		var origin=region.origin();int[] base={(position.getX()-origin.getX())*scale,(position.getY()-origin.getY())*scale,(position.getZ()-origin.getZ())*scale};
		base[axis]+=outward.getAxisDirection()==Direction.AxisDirection.NEGATIVE?0:scale-1;
		var solids=extract && quantity instanceof mmm.api.transfer.TransferQuantity.Granular ? host.exportGranularState() : null;
		if(extract && quantity instanceof mmm.api.transfer.TransferQuantity.Granular && solids==null)return null;
		int lowBits=axis==0?0x55:axis==1?0x33:0x0f;
		int faceBits=outward.getAxisDirection()==Direction.AxisDirection.NEGATIVE?lowBits:(lowBits^0xff);
		boolean solidInput=!extract && quantity instanceof mmm.api.transfer.TransferQuantity.Granular;
		boolean gasInput=!extract && quantity instanceof mmm.api.transfer.TransferQuantity.Gas;
		int[] best=null;double bestVolume=Double.NEGATIVE_INFINITY,bestRoom=Double.NEGATIVE_INFINITY;
		for(int u=0;u<scale;u++)for(int v=0;v<scale;v++) {
			int[] cell=base.clone();cell[(axis+1)%3]+=u;cell[(axis+2)%3]+=v;
			int index=region.grid().cell(cell[0],cell[1],cell[2]);
			if(index<0 || !region.groupAvailable(index) || (host.solidMicroMask(cell[0],cell[1],cell[2])&faceBits)==faceBits)continue;
			if(!extract) {
				double volume=host.volume(cell[0],cell[1],cell[2]);
				double room=gasInput?host.cellGasVolume(cell[0],cell[1],cell[2]):host.cellCondensedRoom(cell[0],cell[1],cell[2]);
				if(room<=0)continue;
				if(solidInput ? volume>bestVolume || volume==bestVolume && room>bestRoom : room>bestRoom)
					{best=cell;bestVolume=volume;bestRoom=room;}
				continue;
			}
			double amount=0;
			if(solids!=null) {
				for(var group:solids.depositGroups())if(group.cell()==index)amount+=group.solidVolumeSvq();
				for(var parcel:solids.activeParcels()) {
					if((int)Math.floor(parcel.x()/region.solverConfig().cellSize())==cell[0]
							&& (int)Math.floor(parcel.y()/region.solverConfig().cellSize())==cell[1]
							&& (int)Math.floor(parcel.z()/region.solverConfig().cellSize())==cell[2])amount+=parcel.solidVolumeSvq();
				}
			} else if(quantity instanceof mmm.api.transfer.TransferQuantity.Gas) {
				amount=host.cellGasStandardVolumeNanos(index);
			} else amount=host.volume(cell[0],cell[1],cell[2]);
			if(amount>0 && amount>bestVolume){best=cell;bestVolume=amount;}
		}
		return best;
	}

	/** Resolves the transfer target cell near the player's aim; requireFluid selects scoop semantics. */
	int[] aimedCellNear(ServerPlayer player, FluidRegion region, boolean requireFluid) {
		if (!validPlayerRegion(player, region)) return null;
		Vec3 start = player.getEyePosition();
		Vec3 direction = player.getLookAngle();
		double visibleDistance = visibleAimDistance(player);
		double step = region.solverConfig().cellSize() * 0.25;
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
				if (hasFluid(region, cell)) return cell.toArray();
			} else if (region.solidMicroMask(cell.x(), cell.y(), cell.z()) == 255) {
				return previousOpen == null ? null : previousOpen.toArray();
			} else if (hasFluid(region, cell)) {
				return (previousOpen == null ? cell : previousOpen).toArray();
			} else {
				previousOpen = cell;
			}
		}
		return null;
	}

	/** First ray-intersected cell carrying active or exposed/deep granular inventory truth. */
	int[] aimedGranularCellNear(ServerPlayer player, FluidRegion region) {
		if (!validPlayerRegion(player, region) || !region.hasGranularState()) return null;
		Vec3 start = player.getEyePosition();
		Vec3 direction = player.getLookAngle();
		double visibleDistance = visibleAimDistance(player);
		double step = region.solverConfig().cellSize() * 0.25;
		CellTarget previous = null;
		for (double distance = 0.0; distance <= visibleDistance; distance += step) {
			CellTarget cell = worldToCell(region, start.add(direction.scale(distance)));
			if (cell == null || cell.equals(previous)) continue;
			previous = cell;
			if (region.hasGranularOccupancy(cell.x(), cell.y(), cell.z())) return cell.toArray();
		}
		return null;
	}

	/** First condensed cell after entering a uniquely resolved shell interior, using exact grid-boundary DDA. */
	int[] firstCondensedCellAlong(FluidRegion region, Vec3 interiorPoint, Vec3 direction) {
		if (region == null || !region.solverLoaded() || interiorPoint == null || direction == null) return null;
		double h = region.solverConfig().cellSize();
		return firstMatchingCell(
				(interiorPoint.x - region.origin().getX()) / h,
				(interiorPoint.y - region.origin().getY()) / h,
				(interiorPoint.z - region.origin().getZ()) / h,
				direction.x, direction.y, direction.z,
				region.sizeX(), region.sizeY(), region.sizeZ(),
				region::hasCondensedInventory);
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

	private static boolean hasFluid(FluidRegion region, CellTarget cell) {
		var inventory = region.observeCellInventory(cell.x(), cell.y(), cell.z());
		double h = region.solverConfig().cellSize();
		return inventory != null && inventory.liquidVolumeCubicMetres() > region.solverConfig().fluidEpsilon() * h * h * h;
	}

	private static CellTarget worldToCell(FluidRegion region, Vec3 point) {
		if (region == null || !region.solverLoaded()) return null;
		FluidRegionFootprint footprint = region.footprint();
		if (footprint != null && !footprint.containsInterior(BlockPos.containing(point))) return null;
		BlockPos origin = region.origin();
		float cellSize = region.solverConfig().cellSize();
		int x = Mth.floor((point.x - origin.getX()) / cellSize);
		int y = Mth.floor((point.y - origin.getY()) / cellSize);
		int z = Mth.floor((point.z - origin.getZ()) / cellSize);
		return region.grid().cell(x,y,z) >= 0 && region.groupAvailable(region.grid().cell(x,y,z)) ? new CellTarget(x, y, z) : null;
	}

	private static boolean inBounds(FluidRegion region, int x, int y, int z) {
		return x >= 0 && x < region.sizeX() && y >= 0 && y < region.sizeY()
				&& z >= 0 && z < region.sizeZ();
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

	record TransferTarget(FluidRegion region, BlockPos interiorBlock, Direction outward) {
		int[] cell() { return cellAtInteriorFace(region, interiorBlock, outward); }
		int[] cell(mmm.api.transfer.TransferQuantity quantity,boolean extract, mmm.fluid.HostFluidState host) { return transferCell(region,interiorBlock,outward,quantity,extract,host); }
	}

	@FunctionalInterface
	interface CellPredicate { boolean matches(int x, int y, int z); }
}
