package mmm.item;

import mmm.transfer.CreativeSourceDescriptor;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** A server-resolved creative template. The stack contains no physical inventory. */
public final class CreativeSourceItem extends Item {
	public CreativeSourceItem(Properties properties) { super(properties); }

	public static ItemStack stack(CreativeSourceDescriptor descriptor) {
		ItemStack stack = new ItemStack(MmmItems.CREATIVE_SOURCE);
		stack.set(MmmComponents.CREATIVE_SOURCE, descriptor.encode());
		return stack;
	}

	public static CreativeSourceDescriptor descriptor(ItemStack stack) {
		if (!(stack.getItem() instanceof CreativeSourceItem) || stack.getCount() != 1) return null;
		String encoded = stack.get(MmmComponents.CREATIVE_SOURCE);
		if (encoded == null) return null;
		try { return CreativeSourceDescriptor.decode(encoded); }
		catch (IllegalArgumentException invalid) { return null; }
	}

	@Override public Component getName(ItemStack stack) {
		CreativeSourceDescriptor descriptor = descriptor(stack);
		if (descriptor == null) return Component.translatable("item.mmm.creative_source.invalid");
		String path = descriptor.templateStableId().substring(descriptor.templateStableId().indexOf(':') + 1)
				.replace('/', '.');
		return Component.translatable("item.mmm.creative_source." + path);
	}

	@Override public void appendHoverText(ItemStack stack, TooltipContext context,
			net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
			java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
		CreativeSourceDescriptor descriptor = descriptor(stack);
		if (descriptor == null) {
			tooltip.accept(Component.translatable("item.mmm.creative_source.malformed").withStyle(ChatFormatting.RED));
			return;
		}
		tooltip.accept(Component.translatable("item.mmm.creative_source.template").withStyle(ChatFormatting.AQUA));
		tooltip.accept(Component.translatable("item.mmm.creative_source.amount",
				Long.toString(descriptor.requestedTypedAmount())).withStyle(ChatFormatting.GRAY));
	}
}
