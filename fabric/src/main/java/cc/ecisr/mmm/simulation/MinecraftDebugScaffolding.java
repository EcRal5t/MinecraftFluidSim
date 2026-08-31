package cc.ecisr.mmm.simulation;

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

	void restore(MinecraftServer server) {
		if (dimension == null) return;
		ServerLevel level = server.getLevel(dimension);
		if (level != null) for (Map.Entry<BlockPos, BlockState> entry : replacedBlocks.entrySet()) {
			if (level.getBlockState(entry.getKey()).is(Blocks.GLASS)) {
				level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
			}
		}
		replacedBlocks.clear();
		dimension = null;
	}

	void placeAirDemo(ServerLevel level, BlockPos origin) {
		replacedBlocks.clear();
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

	private static boolean isScaffoldBlock(int bx, int by, int bz) {
		boolean shell = bx == 0 || bx == MinecraftDebugFixtures.BLOCK_SIZE_X - 1
				|| bz == 0 || bz == MinecraftDebugFixtures.BLOCK_SIZE_Z - 1 || by == 0;
		boolean roof = by == MinecraftDebugFixtures.BLOCK_SIZE_Y - 1 && bx <= 6;
		boolean divider = bx == 6 && by >= 3;
		return shell || roof || divider;
	}
}
