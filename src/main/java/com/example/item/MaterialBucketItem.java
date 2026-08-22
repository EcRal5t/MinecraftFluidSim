package com.example.item;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Real Water material container. The exact volume lives in {@code realwater:fluid_volume} as
 * long nanovolume quanta and the stable material id lives in {@code realwater:material_id}.
 * The material id is a key, not a runtime handle; runtime handles are resolved by the server
 * against the active material registry.
 */
public final class MaterialBucketItem extends Item {
	private static final int BAR_COLOR = 0x8A6D3B;

	public MaterialBucketItem(Item.Properties properties) {
		super(properties);
	}

	/** Returns the exact nanovolume, or -1 when the component is missing or out of range. */
	public static long volume(ItemStack stack) {
		Long value = stack.get(RealWaterComponents.FLUID_VOLUME);
		if (value == null || value <= 0L || value > ContainerFluidLedger.NANOS_PER_BLOCK) return -1L;
		return value;
	}

	/** Returns the stable material id, or null when missing. */
	public static String materialId(ItemStack stack) {
		String value = stack.get(RealWaterComponents.MATERIAL_ID);
		if (value == null || value.isBlank()) return null;
		return value;
	}

	/** Returns true when this material bucket has neither volume nor material id and can scoop. */
	public static boolean isEmptyMaterialBucket(ItemStack stack) {
		return stack.getItem() instanceof MaterialBucketItem
				&& volume(stack) < 0L
				&& materialId(stack) == null;
	}

	@Override
	public boolean isBarVisible(ItemStack stack) {
		long nanos = volume(stack);
		return nanos > 0L && nanos < ContainerFluidLedger.NANOS_PER_BLOCK;
	}

	@Override
	public int getBarWidth(ItemStack stack) {
		long nanos = volume(stack);
		if (nanos <= 0L) return 1;
		return ContainerFluidLedger.barWidth(nanos);
	}

	@Override
	public int getBarColor(ItemStack stack) {
		return BAR_COLOR;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
			java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
		long nanos = volume(stack);
		String material = materialId(stack);
		if (nanos > 0L && material != null) {
			tooltip.accept(Component.translatable("item.realwater.material_bucket.volume",
					material, String.format("%.2f%% (%.9f m³)", 100.0 * nanos / ContainerFluidLedger.NANOS_PER_BLOCK,
							ContainerFluidLedger.nanosToVolume(nanos)))
					.withStyle(ChatFormatting.GRAY));
		}
	}
}
