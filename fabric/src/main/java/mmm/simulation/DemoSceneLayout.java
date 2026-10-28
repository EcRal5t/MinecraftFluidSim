package mmm.simulation;

import java.util.List;
import net.minecraft.core.BlockPos;

/** Shared block geometry for the interactive flow demonstration and its offline replay. */
public final class DemoSceneLayout {
	public static final int FLOW_SIZE_X = 8, FLOW_SIZE_Y = 4, FLOW_SIZE_Z = 6;
	public static final int AIR_SIZE_X = 7, AIR_SIZE_Y = 5, AIR_SIZE_Z = 3;

	private DemoSceneLayout() { }

	/** A closed left arm and an open right arm, connected below the central wall. */
	public static boolean isAirBlock(int x, int y, int z) {
		return y == 0 || x == 0 || x == AIR_SIZE_X - 1 || z == 0 || z == AIR_SIZE_Z - 1
				|| (x == 3 && y >= 2) || (y == AIR_SIZE_Y - 1 && x <= 3);
	}

	public static boolean isFlowBlock(int x, int y, int z) {
		return y == 0 || x == 0 || x == FLOW_SIZE_X - 1 || z == 0 || z == FLOW_SIZE_Z - 1
				|| (x == 4 && z == 3 && y <= 2);
	}

	/** Walkable stairs outside the south wall, in block coordinates relative to the pool origin. */
	public static List<BlockPos> entrySteps() {
		return List.of(new BlockPos(2, 0, 8),
				new BlockPos(2, 0, 7), new BlockPos(2, 1, 7),
				new BlockPos(2, 0, 6), new BlockPos(2, 1, 6), new BlockPos(2, 2, 6));
	}
}
