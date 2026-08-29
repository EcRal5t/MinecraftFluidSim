package cc.ecisr.mmm.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Stateless entry to the client-local quick-start screen. */
final class QuickStartGuideItem extends Item {
	QuickStartGuideItem(Properties properties) { super(properties); }

	@Override
	public void appendHoverText(
			ItemStack stack, TooltipContext context,
			net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
			java.util.function.Consumer<Component> tooltip, TooltipFlag flag
	) {
		tooltip.accept(Component.translatable("item.mmm.quick_start_guide.tooltip")
				.withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("item.mmm.quick_start_guide.tooltip_open")
				.withStyle(ChatFormatting.DARK_GRAY));
	}
}
