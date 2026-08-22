package com.example.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Partial pure-water bucket: the exact volume lives in the {@code realwater:water_volume}
 * component as long nanovolume quanta, strictly between empty and one full bucket. Emptying it
 * canonicalizes to a vanilla bucket, filling it to exactly one bucket canonicalizes to a vanilla
 * water bucket, and the durability bar is a read-only display of the fraction.
 */
public final class PartialWaterBucketItem extends Item {
	private static final int BAR_COLOR = 0x3F76E4;

	public PartialWaterBucketItem(Item.Properties properties) {
		super(properties);
	}

	/** Returns the exact nanovolume, or -1 when the component is missing or out of range. */
	public static long volume(ItemStack stack) {
		Long value = stack.get(RealWaterComponents.WATER_VOLUME);
		if (value == null || !ContainerFluidLedger.isPartialBucket(value)) return -1L;
		return value;
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		return volume(stack) > 0L;
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		return ContainerFluidLedger.barWidth(volume(stack));
	}

	@Override
	public int getBarColor(ItemStack stack) {
		return BAR_COLOR;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
			java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
		long nanos = volume(stack);
		if (nanos > 0L) {
			tooltip.accept(Component.translatable("item.realwater.partial_water_bucket.volume",
					String.format("%.2f%% (%.9f m³)", 100.0 * nanos / ContainerFluidLedger.NANOS_PER_BLOCK,
							ContainerFluidLedger.nanosToVolume(nanos)))
					.withStyle(ChatFormatting.GRAY));
		}
	}
}
