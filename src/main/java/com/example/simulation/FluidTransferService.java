package com.example.simulation;

import com.example.RealWaterMod;
import com.example.fluid.FlipFluidDomain;
import com.example.item.ContainerFluidLedger;
import com.example.item.PartialWaterBucketItem;
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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.util.UUID;

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
				? pour(service, region, player, hand, held)
				: scoop(service, region, player, hand, held);
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
		if (region.controllerManaged()) {
			int slot = findDispenserSlot(source, stack);
			if (slot < 0) return stack;
			ContainerTransferJournal.Actor actor = ContainerTransferJournal.Actor.dispenser(
					level.dimension().identifier().toString(), source.pos(), slot);
			ControllerTransfer transaction = kind == TransferKind.POUR
					? executeControllerTransfer(service, region, level, actor, stack,
							ContainerTransferJournal.Operation.INSERT, heldVolumeNanos(stack), 0L, cell,
							(rawAfter, before) -> rawAfter)
					: executeControllerTransfer(service, region, level, actor, stack,
							ContainerTransferJournal.Operation.EXTRACT, heldFreeNanos(stack), heldBaseVolumeNanos(stack), cell,
							(rawAfter, before) -> rawAfter);
			if (!transaction.outcome().transferred()) return stack;
			source.blockEntity().setItem(slot, transaction.outcome().stack());
			source.blockEntity().setChanged();
			level.save(null, false, false);
			if (transaction.transactionId() != null) deleteQuietly(service, transaction.transactionId());
			service.sendSnapshot(level, region);
			return transaction.outcome().stack();
		}
		TransferOutcome outcome = kind == TransferKind.POUR
				? pourIntoCell(service, region, cell, heldVolumeNanos(stack), 6)
				: scoopFromCell(service, region, cell, heldFreeNanos(stack), heldBaseVolumeNanos(stack), 6);
		if (outcome == null || !outcome.transferred()) return stack;
		service.sendSnapshot(level, region);
		return outcome.stack();
	}


	private static TransferOutcome pour(
			FluidSimulationService service, FluidRegion region, ServerPlayer player, InteractionHand hand, ItemStack held
	) {
		Long volume = heldVolumeNanos(held);
		if (volume == null || volume <= 0L) return null;
		int[] cell = playerPourTargetCell(service, region, player);
		if (cell == null) return null;
		if (region.controllerManaged()) return pourController(service, region, player, hand, held, volume, cell);
		TransferOutcome outcome = pourIntoCell(service, region, cell, volume, 6);
		if (!outcome.transferred()) return new TransferOutcome(false, true, held, outcome.message());
		if (player.hasInfiniteMaterials() && held.is(Items.WATER_BUCKET)) {
			service.sendSnapshotFor(player);
			return new TransferOutcome(true, true, held.copy(), outcome.message());
		}
		service.sendSnapshotFor(player);
		return outcome;
	}

	private static TransferOutcome scoop(
			FluidSimulationService service, FluidRegion region, ServerPlayer player, InteractionHand hand, ItemStack held
	) {
		long capacity = heldFreeNanos(held);
		if (capacity <= 0L) return null;
		int[] cell = service.aimedCellNear(player, true);
		if (cell == null) return null;
		if (region.controllerManaged()) return scoopController(service, region, player, hand, held, capacity, cell);
		TransferOutcome outcome = scoopFromCell(service, region, cell, capacity, heldBaseVolumeNanos(held), 6);
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

	private static TransferOutcome pourController(
			FluidSimulationService service, FluidRegion region, ServerPlayer player, InteractionHand hand, ItemStack held,
			long volumeNanos, int[] cell
	) {
		ContainerTransferJournal.Actor actor = ContainerTransferJournal.Actor.player(player.getUUID(), hand.name());
		ControllerTransfer transaction = executeControllerTransfer(
				service, region, player.level(), actor, held,
				ContainerTransferJournal.Operation.INSERT, volumeNanos, 0L, cell,
				(rawAfter, before) -> player.hasInfiniteMaterials() && before.is(Items.WATER_BUCKET) ? before.copy() : rawAfter
		);
		if (!transaction.outcome().transferred()) return transaction.outcome();
		player.setItemInHand(hand, transaction.outcome().stack());
		player.getInventory().setChanged();
		player.level().getServer().getPlayerList().saveAll();
		if (transaction.transactionId() != null) deleteQuietly(service, transaction.transactionId());
		service.sendSnapshotFor(player);
		return transaction.outcome();
	}

	private static TransferOutcome scoopController(
			FluidSimulationService service, FluidRegion region, ServerPlayer player, InteractionHand hand, ItemStack held,
			long capacityNanos, int[] cell
	) {
		ContainerTransferJournal.Actor actor = ContainerTransferJournal.Actor.player(player.getUUID(), hand.name());
		ControllerTransfer transaction = executeControllerTransfer(
				service, region, player.level(), actor, held,
				ContainerTransferJournal.Operation.EXTRACT, capacityNanos, heldBaseVolumeNanos(held), cell,
				(rawAfter, before) -> player.hasInfiniteMaterials() && before.is(Items.BUCKET) ? before.copy() : rawAfter
		);
		if (!transaction.outcome().transferred()) return transaction.outcome();
		if (player.hasInfiniteMaterials() && held.is(Items.BUCKET)) {
			ItemStack filled = PartialWaterBucketItem.withVolume(ContainerFluidLedger.NANOS_PER_BLOCK);
			if (!player.getInventory().contains(filled)) {
				player.getInventory().add(filled);
			}
		}
		player.setItemInHand(hand, transaction.outcome().stack());
		player.getInventory().setChanged();
		player.level().getServer().getPlayerList().saveAll();
		if (transaction.transactionId() != null) deleteQuietly(service, transaction.transactionId());
		service.sendSnapshotFor(player);
		return transaction.outcome();
	}

	private static ControllerTransfer executeControllerTransfer(
			FluidSimulationService service,
			FluidRegion region,
			ServerLevel level,
			ContainerTransferJournal.Actor actor,
			ItemStack beforeStack,
			ContainerTransferJournal.Operation operation,
			long requestedNanos,
			long baseVolumeNanos,
			int[] cell,
			FinalItemResolver finalItemResolver
	) {
		if (service.hasPendingContainerTransfer(region.id())) {
			return new ControllerTransfer(
					new TransferOutcome(false, operation == ContainerTransferJournal.Operation.INSERT, beforeStack,
							"区域存在未完成容器转移 WAL，已锁定"),
					null);
		}
		if (requestedNanos <= 0L) return new ControllerTransfer(null, null);
		service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.BEFORE_PREPARE);
		long baseGeneration = region.checkpointGeneration();
		long targetGeneration = Math.addExact(baseGeneration, 1L);
		UUID transactionId = UUID.randomUUID();
		ContainerTransferJournal.ItemFingerprint beforeFingerprint = ContainerTransferJournal.ItemFingerprint.of(beforeStack);
		ContainerTransferJournal prepared = new ContainerTransferJournal(
				transactionId, region.id(), level.dimension().identifier().toString(),
				baseGeneration, targetGeneration, operation, requestedNanos, actor,
				beforeFingerprint, beforeFingerprint, ContainerTransferJournal.Phase.PREPARED
		);
		try {
			service.containerTransfers().write(prepared);
		} catch (IOException exception) {
			return new ControllerTransfer(
					new TransferOutcome(false, operation == ContainerTransferJournal.Operation.INSERT, beforeStack,
							"容器转移 WAL 发布失败：" + exception.getMessage()),
					null);
		}
		service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_PREPARE);

		TransferOutcome rawOutcome = operation == ContainerTransferJournal.Operation.INSERT
				? pourIntoCell(service, region, cell, requestedNanos, 6)
				: scoopFromCell(service, region, cell, requestedNanos, baseVolumeNanos, 6);
		if (rawOutcome == null || !rawOutcome.transferred()) {
			deleteQuietly(service, transactionId);
			return new ControllerTransfer(rawOutcome == null
					? new TransferOutcome(false, operation == ContainerTransferJournal.Operation.INSERT, beforeStack, "转移未执行")
					: rawOutcome, null);
		}
		service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_SOLVER);

		ItemStack finalAfter = finalItemResolver.resolve(rawOutcome.stack(), beforeStack);
		ContainerTransferJournal committed = new ContainerTransferJournal(
				transactionId, region.id(), level.dimension().identifier().toString(),
				baseGeneration, targetGeneration, operation, requestedNanos, actor,
				beforeFingerprint, ContainerTransferJournal.ItemFingerprint.of(finalAfter),
				ContainerTransferJournal.Phase.REGION_COMMITTED
		);
		try {
			service.containerTransfers().write(committed);
		} catch (IOException exception) {
			region.setPaused(true);
			region.setAvailabilityFailure("容器转移 WAL 更新失败，区域已锁定；重启后恢复：" + exception.getMessage());
			return new ControllerTransfer(
					new TransferOutcome(false, operation == ContainerTransferJournal.Operation.INSERT, beforeStack,
							region.availabilityFailure()),
					null);
		}
		service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_WAL);



		long publishedGeneration;
		try {
			publishedGeneration = service.checkpointControllerRegion(region);
		} catch (IOException | RuntimeException exception) {
			region.setPaused(true);
			region.setAvailabilityFailure("容器转移检查点失败，区域已锁定；重启后恢复：" + exception.getMessage());
			return new ControllerTransfer(
					new TransferOutcome(false, operation == ContainerTransferJournal.Operation.INSERT, beforeStack,
							region.availabilityFailure()),
					transactionId);
		}
		if (publishedGeneration != targetGeneration) {
			region.setPaused(true);
			region.setAvailabilityFailure("容器转移检查点代次不一致，区域已锁定；重启后恢复");
			return new ControllerTransfer(
					new TransferOutcome(false, operation == ContainerTransferJournal.Operation.INSERT, beforeStack,
							region.availabilityFailure()),
					transactionId);
		}
		service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_CHECKPOINT);



		return new ControllerTransfer(new TransferOutcome(true, operation == ContainerTransferJournal.Operation.INSERT,
				finalAfter, rawOutcome.message()), transactionId);
	}

	private static int findDispenserSlot(BlockSource source, ItemStack stack) {
		var dispenser = source.blockEntity();
		int fallback = -1;
		int fallbackCount = 0;
		for (int i = 0; i < dispenser.getContainerSize(); i++) {
			ItemStack candidate = dispenser.getItem(i);
			if (candidate == stack) return i;
			if (ItemStack.matches(candidate, stack)) {
				fallbackCount++;
				fallback = i;
			}
		}
		return fallbackCount == 1 ? fallback : -1;
	}

	private static void deleteQuietly(FluidSimulationService service, UUID transactionId) {
		try {
			service.containerTransfers().delete(transactionId);
		} catch (IOException exception) {
			RealWaterMod.LOGGER.warn("Could not delete container transfer WAL {}", transactionId, exception);
		}
	}

	private record ControllerTransfer(TransferOutcome outcome, UUID transactionId) { }

	@FunctionalInterface
	private interface FinalItemResolver {
		ItemStack resolve(ItemStack rawAfter, ItemStack before);
	}


	private static TransferOutcome pourIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long volumeNanos, int maxRadius
	) {
		if (volumeNanos <= 0L) return null;
		float requestedCellVolume = nanosToCellVolume(volumeNanos);
		FlipFluidDomain domain = region.domain();
		if (domain.totalOpenVolume() - domain.totalVolume() + 1.0E-6F < requestedCellVolume) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "空间或 marker 预算不足，未能注入");
		}
		FlipFluidDomain.ExactVolumeTransfer inserted = domain.tryInsertExactVolume(
				cell[0], cell[1], cell[2], requestedCellVolume, maxRadius);
		if (!inserted.transferred()) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "空间或 marker 预算不足，未能注入");
		}
		long poured = Math.min(cellVolumeToNanos(inserted.volume()), volumeNanos);
		region.wakeForActivity();
		return new TransferOutcome(true, true, withVolume(volumeNanos - poured),
				String.format("已注入 %.2f%% 桶", 100.0 * poured / ContainerFluidLedger.NANOS_PER_BLOCK));
	}

	private static TransferOutcome scoopFromCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long capacityNanos, long baseVolumeNanos, int maxRadius
	) {
		if (capacityNanos <= 0L) return null;
		FlipFluidDomain.ExactVolumeTransfer extracted = region.domain().tryExtractExactVolume(
				cell[0], cell[1], cell[2], nanosToCellVolume(capacityNanos), maxRadius);
		if (!extracted.transferred() || extracted.volume() <= 0.0) {
			return new TransferOutcome(false, false, withVolume(baseVolumeNanos), "准星处没有足够水体");
		}
		long taken = Math.min(cellVolumeToNanos(extracted.volume()), capacityNanos);
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
		return PartialWaterBucketItem.withVolume(nanos);
	}

	static float nanosToCellVolume(long nanos) {
		return (float) (ContainerFluidLedger.nanosToVolume(nanos) * FluidSimulationService.CELL_VOLUMES_PER_BLOCK);
	}

	static long cellVolumeToNanos(double cellVolume) {
		return ContainerFluidLedger.volumeToNanos(cellVolume / FluidSimulationService.CELL_VOLUMES_PER_BLOCK);
	}

	private static int[] playerPourTargetCell(
			FluidSimulationService service, FluidRegion region, ServerPlayer player
	) {
		BlockHitResult hit = (BlockHitResult) player.pick(5.0, 1.0F, false);
		if (hit.getType() != HitResult.Type.BLOCK) return null;
		BlockPos hitPos = hit.getBlockPos();
		BlockPos targetBlock = player.level().getBlockState(hitPos).canBeReplaced()
				? hitPos
				: hitPos.relative(hit.getDirection());
		return service.cellAtBlock(player.level(), region, targetBlock);
	}

	private record TransferOutcome(boolean transferred, boolean filled, ItemStack stack, String message) { }
}
