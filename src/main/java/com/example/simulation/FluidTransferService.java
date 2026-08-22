package com.example.simulation;

import com.example.fluid.FlipFluidDomain;
import com.example.item.ContainerFluidLedger;
import com.example.item.PartialWaterBucketItem;
import com.example.item.RealWaterComponents;
import com.example.item.RealWaterItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DispenserBlock;

/**
 * Single server-side entry point for player and dispenser bucket transfers with owned regions.
 * Every path settles on the exact primitives: insertion is all-or-nothing against the container's
 * balance, extraction credits at most the container's free space, and the item is only replaced
 * after the region mutation succeeded. Full and empty pure-water states canonicalize to vanilla
 * buckets; partial volumes always live in the Real Water component.
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
					outcome.filled() ? SoundEvents.BUCKET_EMPTY : SoundEvents.BUCKET_FILL,
					SoundSource.BLOCKS, 1.0F, 1.0F);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.FAIL;
	}

	/**
	 * Dispenser path. Returns {@code null} only for vanilla bucket items when the dispenser should
	 * fall through to vanilla behaviour; partial buckets never have a vanilla fallback and always
	 * return a stack.
	 */
	public static ItemStack handleDispenserUse(BlockSource source, ItemStack stack) {
		FluidSimulationService service = FluidSimulationService.get(source.level().getServer());
		boolean vanillaItem = stack.is(Items.WATER_BUCKET) || stack.is(Items.BUCKET);
		if (!service.worldBridgeFeatureEnabled()) return vanillaItem ? null : stack;
		TransferKind kind = classify(stack);
		if (kind == TransferKind.NONE) return stack;
		ServerLevel level = source.level();
		BlockPos target = source.pos().relative(source.state().getValue(DispenserBlock.FACING));
		FluidRegion region = service.regionAtBlock(level, target);
		if (region == null) return vanillaItem ? null : stack;
		if (!service.ensureRegionAvailable(level, region)) return stack;
		int[] cell = service.cellAtBlock(level, region, target);
		if (cell == null) return stack;
		TransferOutcome outcome = kind == TransferKind.POUR
				? pourIntoCell(service, region, cell, heldVolumeNanos(stack))
				: scoopFromCell(service, region, cell, heldFreeNanos(stack), heldBaseVolumeNanos(stack));
		if (outcome == null || !outcome.transferred()) return stack;
		service.sendSnapshot(level, region);
		return outcome.stack();
	}


	private static TransferOutcome pour(
			FluidSimulationService service, FluidRegion region, ServerPlayer player, ItemStack held
	) {
		Long volume = heldVolumeNanos(held);
		if (volume == null || volume <= 0L) return null;
		int[] cell = service.aimedCellNear(player, false);
		if (cell == null) return null;
		TransferOutcome outcome = pourIntoCell(service, region, cell, volume);
		if (!outcome.transferred()) return new TransferOutcome(false, true, held, outcome.message());
		if (player.hasInfiniteMaterials() && held.is(Items.WATER_BUCKET)) {
			service.sendSnapshotFor(player);
			return new TransferOutcome(true, true, held.copy(), outcome.message());
		}
		service.sendSnapshotFor(player);
		return outcome;
	}

	private static TransferOutcome scoop(
			FluidSimulationService service, FluidRegion region, ServerPlayer player, ItemStack held
	) {
		long capacity = heldFreeNanos(held);
		if (capacity <= 0L) return null;
		int[] cell = service.aimedCellNear(player, true);
		if (cell == null) return null;
		TransferOutcome outcome = scoopFromCell(service, region, cell, capacity, heldBaseVolumeNanos(held));
		if (!outcome.transferred()) return new TransferOutcome(false, false, held, outcome.message());
		if (player.hasInfiniteMaterials() && held.is(Items.BUCKET)) {
			ItemStack filled = outcome.stack();
			if (!player.getInventory().contains(filled)) {
				player.getInventory().add(filled);
			}
			service.sendSnapshotFor(player);
			return new TransferOutcome(true, false, held.copy(), outcome.message());
		}
		service.sendSnapshotFor(player);
		return outcome;
	}

	private static TransferOutcome pourIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long volumeNanos
	) {
		if (volumeNanos <= 0L) return null;
		FlipFluidDomain.ExactVolumeTransfer inserted = region.domain().tryInsertExactVolume(
				cell[0], cell[1], cell[2], ContainerFluidLedger.nanosToFloatVolume(volumeNanos), 6);
		if (!inserted.transferred()) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "空间或 marker 预算不足，未能注入");
		}
		long poured = Math.min(ContainerFluidLedger.volumeToNanos(inserted.volume()), volumeNanos);
		region.wakeForActivity();
		return new TransferOutcome(true, true, withVolume(volumeNanos - poured),
				String.format("已注入 %.2f%% 桶", 100.0 * poured / ContainerFluidLedger.NANOS_PER_BLOCK));
	}

	private static TransferOutcome scoopFromCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long capacityNanos, long baseVolumeNanos
	) {
		if (capacityNanos <= 0L) return null;
		FlipFluidDomain.ExactVolumeTransfer extracted = region.domain().tryExtractExactVolume(
				cell[0], cell[1], cell[2], ContainerFluidLedger.nanosToFloatVolume(capacityNanos), 6);
		if (!extracted.transferred() || extracted.volume() <= 0.0) {
			return new TransferOutcome(false, false, withVolume(baseVolumeNanos), "准星处没有足够水体");
		}
		long taken = Math.min(ContainerFluidLedger.volumeToNanos(extracted.volume()), capacityNanos);
		region.wakeForActivity();
		return new TransferOutcome(true, false, withVolume(baseVolumeNanos + taken),
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
