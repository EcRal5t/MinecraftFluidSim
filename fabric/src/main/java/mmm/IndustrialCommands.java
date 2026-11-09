package mmm;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;

final class IndustrialCommands {
    private IndustrialCommands(){}
    static LiteralArgumentBuilder<CommandSourceStack> node(){
        return Commands.literal("industrial").then(Commands.argument("position",BlockPosArgument.blockPos())
            .then(Commands.argument("settings",StringArgumentType.greedyString()).executes(context->{
                var player=context.getSource().getPlayerOrException();
                var position=BlockPosArgument.getLoadedBlockPos(context,"position");
                if(player.distanceToSqr(position.getX()+.5,position.getY()+.5,position.getZ()+.5)>256)return 0;
                String message=mmm.simulation.MmmRuntimeRegistry.require(player.level().getServer()).industrial().configure(player,position,StringArgumentType.getString(context,"settings"));
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));return 1;
            })));
    }
}
