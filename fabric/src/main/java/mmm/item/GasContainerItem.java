package mmm.item;

import mmm.transfer.GasContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** A finite container for a gas mixture at the registry standard volume. */
public final class GasContainerItem extends Item {
	public static final long CAPACITY_NANOS = 1_000_000_000L;
	public GasContainerItem(Properties properties) { super(properties); }
	public static boolean isEmpty(ItemStack stack) {
		return stack.getItem() instanceof GasContainerItem && stack.get(MmmComponents.GAS_CONTENTS) == null
				&& stack.get(MmmComponents.PROFILE_FINGERPRINT) == null;
	}
	public static GasContents contents(ItemStack stack) {
		if (!(stack.getItem() instanceof GasContainerItem)) return null;
		String encoded = stack.get(MmmComponents.GAS_CONTENTS);
		if (encoded == null) return null;
		var contents = GasContents.decode(encoded);
		if (contents.inventory().standardVolumeNanos() > CAPACITY_NANOS) throw new IllegalArgumentException("Gas container capacity exceeded");
		return contents;
	}
	@Override public void appendHoverText(ItemStack stack, TooltipContext context,
			net.minecraft.world.item.component.TooltipDisplay display,
			java.util.function.Consumer<net.minecraft.network.chat.Component> tooltip, net.minecraft.world.item.TooltipFlag flag) {
		tooltip.accept(net.minecraft.network.chat.Component.translatable("item.mmm.container.sample_analysis").withStyle(net.minecraft.ChatFormatting.GRAY));
		var contents = contents(stack);
		if (contents == null) return;
		for (var component : contents.inventory().components())
			tooltip.accept(net.minecraft.network.chat.Component.literal(component.componentId()+"  "+mmm.units.UnitFormat.volumeNanos(component.standardVolumeNanos())));
	}
}
