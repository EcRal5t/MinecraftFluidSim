package mmm.item;

import mmm.granular.SolidVolume;
import mmm.transfer.GranularContents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Dry granular container. Tooltip and durability bar are display-only views of canonical granular contents. */
public final class GranularContainerItem extends Item {
	private static final int BAR_COLOR = 0xBFA56A;

	public GranularContainerItem(Properties properties) { super(properties); }

	public static boolean isEmpty(ItemStack stack) {
		return stack.getItem() instanceof GranularContainerItem
				&& stack.get(MmmComponents.GRANULAR_CONTENTS) == null
				&& stack.get(MmmComponents.PROFILE_FINGERPRINT) == null
				&& !ContainerItemStacks.hasLiquidComponents(stack);
	}

	public static GranularContents unvalidatedContents(ItemStack stack) {
		if (!(stack.getItem() instanceof GranularContainerItem) || ContainerItemStacks.hasLiquidComponents(stack)) return null;
		String encoded = stack.get(MmmComponents.GRANULAR_CONTENTS);
		if (encoded == null) return null;
		try { return GranularContents.decode(encoded); }
		catch (IllegalArgumentException invalid) { return null; }
	}

	@Override public boolean isBarVisible(ItemStack stack) {
		GranularContents contents = unvalidatedContents(stack);
		return contents != null && contents.totalSolidVolumeSvq() < SolidVolume.SVQ_PER_BV;
	}

	@Override public int getBarWidth(ItemStack stack) {
		GranularContents contents = unvalidatedContents(stack);
		if (contents == null) return 1;
		return Math.max(1, Math.min(13, (int) Math.round(13.0 * contents.totalSolidVolumeSvq() / SolidVolume.SVQ_PER_BV)));
	}

	@Override public int getBarColor(ItemStack stack) { return BAR_COLOR; }

	@Override public void appendHoverText(ItemStack stack, TooltipContext context,
			net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
			java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
		GranularContents contents = unvalidatedContents(stack);
		if (contents == null) {
			if (isEmpty(stack)) tooltip.accept(Component.translatable("item.mmm.granular_container.empty")
					.withStyle(ChatFormatting.GRAY));
			return;
		}
		tooltip.accept(Component.translatable("item.mmm.granular_container.contents",
				contents.groups().size(), String.format("%.2f%%", 100.0 * contents.totalSolidVolumeSvq() / SolidVolume.SVQ_PER_BV))
				.withStyle(ChatFormatting.GRAY));
		for (GranularContents.Group group : contents.groups()) {
			tooltip.accept(Component.translatable("item.mmm.granular_container.group", group.materialId(),
					Long.toString(group.solidVolumeSvq()), String.format("%.12g", group.mass()))
					.withStyle(ChatFormatting.DARK_GRAY));
			if (group.reactive()) tooltip.accept(Component.translatable("item.mmm.granular_container.reactive",
					group.composition().size()).withStyle(ChatFormatting.DARK_GRAY));
		}
		String profile = ContainerItemStacks.profileFingerprint(stack);
		if (profile != null) tooltip.accept(Component.translatable("item.mmm.container.profile",
				profile.substring(0, 12)).withStyle(ChatFormatting.DARK_GRAY));
		else if (stack.get(MmmComponents.PROFILE_FINGERPRINT) != null) {
			tooltip.accept(Component.translatable("item.mmm.container.profile_invalid").withStyle(ChatFormatting.RED));
		}
	}
}
