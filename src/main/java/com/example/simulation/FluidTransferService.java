package com.example.simulation;

import com.example.fluid.FlipFluidDomain;
import com.example.item.ContainerFluidLedger;
import com.example.item.PartialWaterBucketItem;
import com.example.item.RealWaterComponents;
import com.example.item.RealWaterItems;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Single server-side entry point for player bucket transfers with owned regions. Every path
 * settles on the exact primitives: insertion is all-or-nothing against the container's balance,
 * extraction credits at most the container's free space, and the item is only replaced after the
 * region mutation succeeded. Full and empty pure-water states canonicalize to vanilla buckets;
 * partial volumes always live in the Real Water component.
 */
public final class FluidTransferService {
	private FluidTransferService() { }

	/** Returns null when the transfer does not apply, so vanilla behaviour stays untouched. */
	public static InteractionResult handlePlayerUse(ServerPlayer player, InteractionHand hand) {
		FluidSimulationService service = FluidSimulationService.get(player.level().getServer());
		if (!service.worldBridgeFeatureEnabled()) return null;
		ItemStack held = player.getItemInHand(hand);
		if (held.isEmpty()) return null;

		TransferKind kind = classify(held);
		if (kind == TransferKind.NONE) return null;
		ServerLevel level = player.level();
		FluidRegion region = service.selectedRegionIn(level);
		if (region == null) return null;
		if (!service.ensureRegionAvailableFor(player, region)) {
			return InteractionResult.FAIL;
		}

		TransferOutcome outcome = kind == TransferKind.POUR
				? pour(service, region, player, held)
				: scoop(service, region, player, held);
		if (outcome == null) return null;

		if (!outcome.message().isEmpty()) {
			player.sendSystemMessage(Component.literal(outcome.message()), true);
		}
		if (outcome.transferred()) {
			player.setItemInHand(hand, outcome.stack());
			level.playSound(null, player.getX(), player.getY() + 1.0, player.getZ(),
					outcome.filled() ? SoundEvents.BUCKET_FILL : SoundEvents.BUCKET_EMPTY,
					SoundSource.BLOCKS, 1.0F, 1.0F);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.FAIL;
	}

	private static TransferOutcome pour(
			FluidSimulationService service, FluidRegion region, ServerPlayer player, ItemStack held
	) {
		Long volume = heldVolumeNanos(held);
		if (volume == null || volume <= 0L) return null;
		int[] cell = service.aimedCellNear(player, false);
		if (cell == null) return null;
		FlipFluidDomain.ExactVolumeTransfer inserted = region.domain().tryInsertExactVolume(
				cell[0], cell[1], cell[2], ContainerFluidLedger.nanosToFloatVolume(volume), 6);
		if (!inserted.transferred()) {
			return new TransferOutcome(false, true, held, "空间或 marker 预算不足，未能注入");
		}
		long poured = Math.min(ContainerFluidLedger.volumeToNanos(inserted.volume()), volume);
		region.wakeForActivity();
		service.sendSnapshotFor(player);
		ItemStack result = withVolume(volume - poured);
		return new TransferOutcome(true, true, result,
				String.format("已注入 %.2f%% 桶", 100.0 * poured / ContainerFluidLedger.NANOS_PER_BLOCK));
	}

	private static TransferOutcome scoop(
			FluidSimulationService service, FluidRegion region, ServerPlayer player, ItemStack held
	) {
		long capacity = heldFreeNanos(held);
		if (capacity <= 0L) return null;
		int[] cell = service.aimedCellNear(player, true);
		if (cell == null) return null;
		FlipFluidDomain.ExactVolumeTransfer extracted = region.domain().tryExtractExactVolume(
				cell[0], cell[1], cell[2], ContainerFluidLedger.nanosToFloatVolume(capacity), 6);
		if (!extracted.transferred() || extracted.volume() <= 0.0) {
			return new TransferOutcome(false, false, held, "准星处没有足够水体");
		}
		long taken = Math.min(ContainerFluidLedger.volumeToNanos(extracted.volume()), capacity);
		region.wakeForActivity();
		service.sendSnapshotFor(player);
		ItemStack result = withVolume(heldBaseVolumeNanos(held) + taken);
		return new TransferOutcome(true, false, result,
				String.format("已舀取 %.2f%% 桶", 100.0 * taken / ContainerFluidLedger.NANOS_PER_BLOCK));
	}

	private enum TransferKind {
		NONE,
		POUR,
		SCOOP
	}

	private static TransferKind classify(ItemStack stack) {
		if (stack.is(Items.WATER_BUCKET)) return TransferKind.POUR;
		if (stack.is(Items.BUCKET)) return TransferKind.SCOOP;
		if (stack.getItem() instanceof PartialWaterBucketItem) {
			return PartialWaterBucketItem.volume(stack) > 0L ? TransferKind.POUR : TransferKind.NONE;
		}
		return TransferKind.NONE;
	}

	private static Long heldVolumeNanos(ItemStack stack) {
		if (stack.is(Items.WATER_BUCKET)) return ContainerFluidLedger.NANOS_PER_BLOCK;
		long volume = PartialWaterBucketItem.volume(stack);
		return volume > 0L ? volume : null;
	}

	private static long heldBaseVolumeNanos(ItemStack stack) {
		if (stack.is(Items.BUCKET)) return 0L;
		return Math.max(0L, PartialWaterBucketItem.volume(stack));
	}

	private static long heldFreeNanos(ItemStack stack) {
		if (stack.is(Items.BUCKET)) return ContainerFluidLedger.NANOS_PER_BLOCK;
		long volume = PartialWaterBucketItem.volume(stack);
		return volume < 0L ? 0L : ContainerFluidLedger.NANOS_PER_BLOCK - volume;
	}

	/** Canonicalizes to a vanilla bucket, a vanilla water bucket or a partial bucket. */
	private static ItemStack withVolume(long nanos) {
		if (nanos <= 0L) return new ItemStack(Items.BUCKET);
		if (nanos >= ContainerFluidLedger.NANOS_PER_BLOCK) return new ItemStack(Items.WATER_BUCKET);
		ItemStack stack = new ItemStack(RealWaterItems.PARTIAL_WATER_BUCKET);
		stack.set(RealWaterComponents.WATER_VOLUME, nanos);
		return stack;
	}

	private record TransferOutcome(boolean transferred, boolean filled, ItemStack stack, String message) { }
}
