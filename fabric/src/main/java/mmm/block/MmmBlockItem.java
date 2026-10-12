package mmm.block;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;

/** Shared discoverability text for the three formal controller-shell blocks. */
final class MmmBlockItem extends BlockItem {
	MmmBlockItem(Block block, Properties properties) { super(block, properties); }

	@Override
	public void appendHoverText(
			ItemStack stack, TooltipContext context,
			net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
			java.util.function.Consumer<Component> tooltip, TooltipFlag flag
	) {
		tooltip.accept(Component.translatable(getDescriptionId() + ".tooltip")
				.withStyle(ChatFormatting.GRAY));
	}
}
