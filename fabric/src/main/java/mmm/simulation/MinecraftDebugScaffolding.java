package mmm.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/** Owns the reversible glass blocks of transient demonstration scenes. */
final class MinecraftDebugScaffolding {
	private final Map<BlockPos, BlockState> replacedBlocks = new HashMap<>();
	private ResourceKey<Level> dimension;

	record Saved(ResourceKey<Level> dimension, Map<BlockPos, BlockState> blocks) { }

	boolean active() { return dimension != null; }

	Saved snapshot() { return new Saved(dimension, Map.copyOf(replacedBlocks)); }

	void restore(MinecraftServer server) {
		if (dimension == null) return;
		ServerLevel level = server.getLevel(dimension);
		if (level == null) throw new IllegalStateException("演示脚手架所在维度未加载");
		var iterator = replacedBlocks.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			if (level.getBlockState(entry.getKey()).is(Blocks.GLASS)
					&& !level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL)) {
				throw new IllegalStateException("无法恢复演示脚手架 " + entry.getKey().toShortString());
			}
			iterator.remove();
		}
		dimension = null;
	}

	void reapply(MinecraftServer server, Saved saved) {
		if (saved.blocks().isEmpty()) return;
		ServerLevel level = server.getLevel(saved.dimension());
		if (level == null) throw new IllegalStateException("演示脚手架所在维度未加载");
		dimension = saved.dimension();
		for (var entry : saved.blocks().entrySet()) {
			if (replacedBlocks.containsKey(entry.getKey())) continue;
			BlockState current = level.getBlockState(entry.getKey());
			if (!current.isAir()) continue;
			if (!level.setBlock(entry.getKey(), Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL))
				throw new IllegalStateException("无法恢复原演示脚手架 " + entry.getKey().toShortString());
			replacedBlocks.put(entry.getKey(), entry.getValue());
		}
	}

	void placeAirDemo(ServerLevel level, BlockPos origin) {
		if (dimension != null) throw new IllegalStateException("上一个演示脚手架尚未恢复");
		dimension = level.dimension();
		BlockState glass = Blocks.GLASS.defaultBlockState();
		for (int by = 0; by < MinecraftDebugFixtures.BLOCK_SIZE_Y; by++) {
			for (int bz = 0; bz < MinecraftDebugFixtures.BLOCK_SIZE_Z; bz++) {
				for (int bx = 0; bx < MinecraftDebugFixtures.BLOCK_SIZE_X; bx++) {
					if (!isScaffoldBlock(bx, by, bz)) continue;
					BlockPos position = origin.offset(bx, by, bz);
					BlockState previous = level.getBlockState(position);
					if (previous.isAir() && level.setBlock(position, glass, Block.UPDATE_ALL)) {
						replacedBlocks.put(position, previous);
					}
				}
			}
		}
	}

	void requireAir(ServerLevel level, BlockPos origin, int sx, int sy, int sz) {
		requireAir(level, origin, sx, sy, sz, position -> false);
	}
	void requireAir(ServerLevel level, BlockPos origin, int sx, int sy, int sz, java.util.function.Predicate<BlockPos> willClear) {
		for (int z = 0; z < sz; z++) for (int y = 0; y < sy; y++) for (int x = 0; x < sx; x++)
			requireAir(level, origin.offset(x, y, z), willClear);
	}
	private void requireAir(ServerLevel level, BlockPos position) {
		requireAir(level, position, ignored -> false);
	}
	void requireAir(ServerLevel level, BlockPos position, java.util.function.Predicate<BlockPos> willClear) {
		if (!level.isInWorldBounds(position) || !level.isInsideBuildHeight(position)
				|| level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) == null)
			throw new IllegalStateException("演示需要已加载的空地：" + position.toShortString());
		if (!level.getBlockState(position).isAir() && !willClear.test(position))
			throw new IllegalStateException("演示位置被占用：" + position.toShortString());
	}
	boolean restoresAir(ServerLevel level, BlockPos position) {
		BlockState original = replacedBlocks.get(position);
		return dimension != null && dimension.equals(level.dimension()) && original != null && original.isAir()
				&& level.getBlockState(position).is(Blocks.GLASS);
	}

	void placeFlowDemo(ServerLevel level, BlockPos origin) {
		if (dimension != null) throw new IllegalStateException("上一个演示尚未移除");
		requireAir(level, origin, DemoSceneLayout.FLOW_SIZE_X, DemoSceneLayout.FLOW_SIZE_Y, DemoSceneLayout.FLOW_SIZE_Z);
		for (BlockPos step : DemoSceneLayout.entrySteps()) requireAir(level, origin.offset(step));
		dimension = level.dimension();
		for (int z = 0; z < DemoSceneLayout.FLOW_SIZE_Z; z++) for (int y = 0; y < DemoSceneLayout.FLOW_SIZE_Y; y++)
			for (int x = 0; x < DemoSceneLayout.FLOW_SIZE_X; x++)
				if (DemoSceneLayout.isFlowBlock(x, y, z)) placeGlass(level, origin.offset(x, y, z));
		for (BlockPos step : DemoSceneLayout.entrySteps()) placeGlass(level, origin.offset(step));
	}
	private void placeGlass(ServerLevel level, BlockPos position) {
		BlockState previous = level.getBlockState(position);
		if (!level.setBlock(position, Blocks.GLASS.defaultBlockState(), Block.UPDATE_ALL))
			throw new IllegalStateException("无法放置演示池壁：" + position.toShortString());
		replacedBlocks.put(position.immutable(), previous);
	}

	/** Places a real floor so later world collision refreshes preserve the liquid contact surface. */
	void placeTensionFloor(ServerLevel level, BlockPos origin) {
		if (dimension != null) throw new IllegalStateException("上一个演示脚手架尚未恢复");
		dimension = level.dimension();
		BlockState glass = Blocks.GLASS.defaultBlockState();
		for (int z = 0; z < MinecraftDebugFixtures.BLOCK_SIZE_Z; z++) {
			for (int x = 0; x < MinecraftDebugFixtures.BLOCK_SIZE_X; x++) {
				BlockPos position = origin.offset(x, 0, z);
				BlockState previous = level.getBlockState(position);
				if (previous.isAir() && level.setBlock(position, glass, Block.UPDATE_ALL)) {
					replacedBlocks.put(position, previous);
				}
			}
		}
	}

	static boolean isScaffoldBlock(int bx, int by, int bz) {
		boolean shell = bx == 0 || bx == MinecraftDebugFixtures.BLOCK_SIZE_X - 1
				|| bz == 0 || bz == MinecraftDebugFixtures.BLOCK_SIZE_Z - 1 || by == 0;
		boolean roof = by == MinecraftDebugFixtures.BLOCK_SIZE_Y - 1 && bx <= 6;
		boolean divider = bx == 6 && by >= 3;
		return shell || roof || divider;
	}
}
