package mmm.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import java.util.ArrayList;
import java.util.List;

/** The complete occupied volume and shell used by the controller debug command. */
public record ControllerDemoLayout(BlockPos minimum, BlockPos controller, BlockPos port,
                                   Direction portOutward, List<BlockPos> occupied, List<BlockPos> shell) {
    public static ControllerDemoLayout at(BlockPos player, Direction facing) {
        if (facing.getAxis() == Direction.Axis.Y) throw new IllegalArgumentException("Controller direction must be horizontal");
        int outer = 6;
        BlockPos near = player.relative(facing, 4).above(2);
        BlockPos minimum, controller;
        if (facing.getAxis() == Direction.Axis.X) {
            int x = facing == Direction.EAST ? near.getX() : near.getX() - outer + 1;
            minimum = new BlockPos(x, player.getY(), near.getZ() - 2);
            controller = new BlockPos(near.getX(), minimum.getY() + 2, minimum.getZ() + 2);
        } else {
            int z = facing == Direction.SOUTH ? near.getZ() : near.getZ() - outer + 1;
            minimum = new BlockPos(near.getX() - 2, player.getY(), z);
            controller = new BlockPos(minimum.getX() + 2, minimum.getY() + 2, near.getZ());
        }
        BlockPos port = controller.relative(facing.getClockWise());
        List<BlockPos> occupied = new ArrayList<>(), shell = new ArrayList<>();
        for (int z = 0; z < outer; z++) for (int y = 0; y < outer; y++) for (int x = 0; x < outer; x++) {
            BlockPos pos = minimum.offset(x, y, z);
            occupied.add(pos);
            if (x == 0 || x == outer - 1 || y == 0 || y == outer - 1 || z == 0 || z == outer - 1) shell.add(pos);
        }
        return new ControllerDemoLayout(minimum, controller, port, facing.getOpposite(), List.copyOf(occupied), List.copyOf(shell));
    }
}
