package mmm.item;

import mmm.MmmMod;
import mmm.transfer.ContainerFluidLedger;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.NonNullList;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * Dynamic two-bucket merge recipe. The exact nanovolume ledger is conserved: the first partial
 * bucket in slot order is promoted to the output, the second one leaves the overflow (or an empty
 * bucket) as the recipe remainder.
 */
public final class MmmRecipes {
	private MmmRecipes() { }

	public static final PartialWaterBucketMergeRecipe PARTIAL_WATER_BUCKET_MERGE_RECIPE = new PartialWaterBucketMergeRecipe();
	public static final RecipeSerializer<PartialWaterBucketMergeRecipe> PARTIAL_WATER_BUCKET_MERGE_SERIALIZER =
			new RecipeSerializer<>(
					MapCodec.unit(PARTIAL_WATER_BUCKET_MERGE_RECIPE),
					StreamCodec.unit(PARTIAL_WATER_BUCKET_MERGE_RECIPE)
			);

	public static void register() {
		Registry.register(
				BuiltInRegistries.RECIPE_SERIALIZER,
				MmmMod.id("partial_water_bucket_merge"),
				PARTIAL_WATER_BUCKET_MERGE_SERIALIZER
		);
	}

	public static class PartialWaterBucketMergeRecipe implements CraftingRecipe {
		private static final String GROUP = "mmm:partial_water_bucket_merge";

		@Override
		public boolean matches(CraftingInput input, Level level) {
			return partialVolumes(input) != null;
		}

		@Override
		public ItemStack assemble(CraftingInput input) {
			long[] volumes = partialVolumes(input);
			if (volumes == null) return ItemStack.EMPTY;
			return PartialWaterBucketItem.withVolume(ContainerFluidLedger.mergeOutput(volumes[0], volumes[1]));
		}

		@Override
		public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
			NonNullList<ItemStack> remainders = NonNullList.withSize(input.size(), ItemStack.EMPTY);
			int[] slots = partialSlots(input);
			if (slots == null) return remainders;
			long[] volumes = partialVolumes(input);
			if (volumes == null) return remainders;
			remainders.set(slots[1], PartialWaterBucketItem.withVolume(
					ContainerFluidLedger.mergeRemainder(volumes[0], volumes[1])));
			return remainders;
		}

		private static long[] partialVolumes(CraftingInput input) {
			long first = -1L;
			long second = -1L;
			for (int i = 0; i < input.size(); i++) {
				ItemStack stack = input.getItem(i);
				if (stack.isEmpty()) continue;
				if (!(stack.getItem() instanceof PartialWaterBucketItem)) return null;
				// Crafting has no region/WAL roundoff owner. Never erase authoritative U while merging items.
				if (stack.get(MmmComponents.THERMAL_CONTENTS) != null) return null;
				long volume = PartialWaterBucketItem.volume(stack);
				if (volume <= 0L) return null;
				if (first < 0L) first = volume;
				else if (second < 0L) second = volume;
				else return null;
			}
			return first > 0L && second > 0L ? new long[] {first, second} : null;
		}

		private static int[] partialSlots(CraftingInput input) {
			int first = -1;
			int second = -1;
			for (int i = 0; i < input.size(); i++) {
				ItemStack stack = input.getItem(i);
				if (stack.isEmpty()) continue;
				if (!(stack.getItem() instanceof PartialWaterBucketItem)) return null;
				if (PartialWaterBucketItem.volume(stack) <= 0L) return null;
				if (first < 0) first = i;
				else if (second < 0) second = i;
				else return null;
			}
			return first >= 0 && second >= 0 ? new int[] {first, second} : null;
		}

		@Override
		public boolean isSpecial() {
			return true;
		}

		@Override
		public boolean showNotification() {
			return true;
		}

		@Override
		public String group() {
			return GROUP;
		}

		@Override
		public RecipeSerializer<? extends CraftingRecipe> getSerializer() {
			return PARTIAL_WATER_BUCKET_MERGE_SERIALIZER;
		}

		@Override
		public PlacementInfo placementInfo() {
			return PlacementInfo.NOT_PLACEABLE;
		}

		@Override
		public CraftingBookCategory category() {
			return CraftingBookCategory.MISC;
		}
	}
}
