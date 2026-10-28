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

/** Owns the reversible world blocks used only by the sealed-gas debug fixture. */
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
