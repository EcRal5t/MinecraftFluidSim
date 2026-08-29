package cc.ecisr.mmm.item;

import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.transfer.ChemicalContents;
import cc.ecisr.mmm.transfer.ContainerFluidLedger;
import cc.ecisr.mmm.transfer.FluidMixture;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Matter, Motion & Mechanics material container. The exact volume lives in {@code mmm:fluid_volume} as
 * long nanovolume quanta and the stable material id lives in {@code mmm:material_id}.
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
		Long value = stack.get(MmmComponents.FLUID_VOLUME);
		if (value == null || value <= 0L || value > ContainerFluidLedger.NANOS_PER_BLOCK) return -1L;
		return value;
	}

	/** Returns the stable material id, or null when missing. */
	public static String materialId(ItemStack stack) {
		String value = stack.get(MmmComponents.MATERIAL_ID);
		if (value == null || value.isBlank()) return null;
		return value;
	}

	/** Returns the independent exact material mass, or -1 when missing/invalid. */
	public static double mass(ItemStack stack) {
		Long bits = stack.get(MmmComponents.FLUID_MASS_BITS);
		if (bits == null) return -1.0;
		double value = Double.longBitsToDouble(bits);
		return Double.isFinite(value) && value > 0.0 ? value : -1.0;
	}

	public static FluidMixture mixture(ItemStack stack) {
		try {
			return ContainerItemStacks.fluidMixture(stack);
		} catch (IllegalArgumentException invalid) {
			return null;
		}
	}

	public static ChemicalContents chemicalContents(ItemStack stack, ChemicalRegistry registry) {
		try { return ContainerItemStacks.chemicalContents(stack, registry); }
		catch (IllegalArgumentException invalid) { return null; }
	}

	/** Returns true when this material bucket has neither volume nor material id and can scoop. */
	public static boolean isEmptyMaterialBucket(ItemStack stack) {
		return stack.getItem() instanceof MaterialBucketItem
				&& volume(stack) < 0L
				&& materialId(stack) == null
				&& mass(stack) < 0.0
				&& stack.get(MmmComponents.MIXTURE_COMPONENTS) == null
				&& stack.get(MmmComponents.CHEMICAL_CONTENTS) == null
				&& stack.get(MmmComponents.THERMAL_CONTENTS) == null
				&& stack.get(MmmComponents.PROFILE_FINGERPRINT) == null;
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
		if (isEmptyMaterialBucket(stack)) {
			tooltip.accept(Component.translatable("item.mmm.material_bucket.empty")
					.withStyle(ChatFormatting.GRAY));
			return;
		}
		FluidMixture mixture = mixture(stack);
		ChemicalContents chemical = null;
		String chemicalEncoded = stack.get(MmmComponents.CHEMICAL_CONTENTS);
		if (chemicalEncoded != null && stack.get(MmmComponents.MATERIAL_ID) == null
				&& stack.get(MmmComponents.MIXTURE_COMPONENTS) == null) {
			try {
				ChemicalContents decoded = ChemicalContents.decode(chemicalEncoded);
				Long massBits = stack.get(MmmComponents.FLUID_MASS_BITS);
				if (decoded.totalVolumeNanos() == nanos && massBits != null
						&& massBits == Double.doubleToRawLongBits(decoded.totalMass())) chemical = decoded;
			} catch (IllegalArgumentException ignored) { }
		}
		if (nanos > 0L && chemical != null) {
			tooltip.accept(Component.translatable("item.mmm.material_bucket.chemical",
					chemical.groups().size(), chemical.totalQuanta()).withStyle(ChatFormatting.GRAY));
			for (ChemicalContents.Group group : chemical.groups()) {
				tooltip.accept(Component.literal((group.indicator() ? "[indicator] " : "") + group.materialId()
						+ " · " + String.format("%.9f m³", ContainerFluidLedger.nanosToVolume(group.volumeNanos())))
						.withStyle(ChatFormatting.DARK_GRAY));
				for (ChemicalContents.SpeciesAmount species : group.species()) {
					tooltip.accept(Component.translatable("item.mmm.material_bucket.species",
							species.speciesId(), formatAu(species.amountQuanta(), chemical.auScale()),
							Long.toString(species.amountQuanta())).withStyle(ChatFormatting.DARK_GRAY));
				}
			}
			appendProfileTooltip(stack, tooltip);
			return;
		}
		if (nanos > 0L && mixture != null) {
			Component material = mixture.components().size() == 1
					? Component.literal(mixture.components().getFirst().materialId())
					: Component.translatable("item.mmm.material_bucket.mixture", mixture.components().size());
			double mass = mass(stack);
			tooltip.accept(Component.translatable("item.mmm.material_bucket.volume",
					material, String.format("%.2f%% (%.9f m³)", 100.0 * nanos / ContainerFluidLedger.NANOS_PER_BLOCK,
							ContainerFluidLedger.nanosToVolume(nanos)))
					.withStyle(ChatFormatting.GRAY));
			if (mass > 0.0) tooltip.accept(Component.translatable("item.mmm.material_bucket.mass",
					String.format("%.12g", mass))
					.withStyle(ChatFormatting.DARK_GRAY));
			if (mixture.components().size() > 1) {
				for (FluidMixture.Component fluid : mixture.components()) {
					String componentVolume = String.format("%.2f%% (%.9f m³)",
							100.0 * fluid.volumeNanos() / ContainerFluidLedger.NANOS_PER_BLOCK,
							ContainerFluidLedger.nanosToVolume(fluid.volumeNanos()));
					tooltip.accept(Component.translatable("item.mmm.material_bucket.component",
							fluid.materialId(), componentVolume, String.format("%.12g", fluid.mass()))
							.withStyle(ChatFormatting.DARK_GRAY));
				}
			}
			appendProfileTooltip(stack, tooltip);
		}
	}

	private static void appendProfileTooltip(ItemStack stack, java.util.function.Consumer<Component> tooltip) {
		String profile = ContainerItemStacks.profileFingerprint(stack);
		if (profile != null) tooltip.accept(Component.translatable("item.mmm.container.profile",
				profile.substring(0, 12)).withStyle(ChatFormatting.DARK_GRAY));
		else if (stack.get(MmmComponents.PROFILE_FINGERPRINT) != null) {
			tooltip.accept(Component.translatable("item.mmm.container.profile_invalid").withStyle(ChatFormatting.RED));
		}
	}

	/** Exact, locale-independent display of the canonical quanta/AU ratio. */
	private static String formatAu(long quanta, long auScale) {
		return java.math.BigDecimal.valueOf(quanta).divide(java.math.BigDecimal.valueOf(auScale))
				.stripTrailingZeros().toPlainString();
	}
}
