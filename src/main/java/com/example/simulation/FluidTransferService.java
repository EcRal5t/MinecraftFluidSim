package com.example.simulation;

import com.example.RealWaterMod;
import com.example.fluid.FlipFluidDomain;
import com.example.item.ContainerFluidLedger;
import com.example.item.ChemicalContents;
import com.example.item.FluidMixture;
import com.example.item.MaterialBucketItem;
import com.example.item.PartialWaterBucketItem;
import com.example.item.RealWaterItems;
import com.example.item.RealWaterComponents;
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
		boolean pouring = kind == TransferKind.POUR || kind == TransferKind.MATERIAL_POUR;
		BlockPos targetBlock = pouring ? playerPourTargetBlock(player) : null;
		if (pouring && targetBlock == null) return null;
		FluidRegion region = pouring ? service.regionAtBlock(level, targetBlock) : service.aimedRegion(player);
		if (region == null) return null;
		if (held.getCount() != 1) {
			player.sendSystemMessage(Component.literal("真实水体转移一次只接受 1 个桶；堆叠保持不变"), true);
			return InteractionResult.FAIL;
		}
		if (!service.ensureRegionAvailableFor(player, region)) {
			return InteractionResult.FAIL;
		}
		int[] cell = pouring
				? service.cellAtBlock(level, region, targetBlock)
				: service.aimedCellNear(player, region, true);
		if (cell == null) return null;

		TransferOutcome outcome = switch (kind) {
			case POUR -> pour(service, region, player, hand, held, cell);
			case SCOOP -> scoop(service, region, player, hand, held, cell);
			case MATERIAL_POUR -> pourMaterial(service, region, player, hand, held, cell);
			case MATERIAL_SCOOP -> scoopMaterial(service, region, player, hand, held, cell);
			case NONE -> null;
		};
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
		if (stack.getCount() != 1) return stack;
		if (!service.ensureRegionAvailable(level, region)) return stack;
		int[] cell = service.cellAtBlock(level, region, target);
		if (cell == null) return stack;
		FluidMixture heldMixture = null;
		ChemicalContents heldChemical = null;
		if (kind == TransferKind.MATERIAL_POUR) {
			if (!service.materialFeatureEnabled() || !region.domain().hasMaterialState()) return stack;
			if (stack.get(RealWaterComponents.CHEMICAL_CONTENTS) != null) {
				if (!service.chemistryFeatureEnabled() || !region.domain().hasChemistryState()) return stack;
				heldChemical = MaterialBucketItem.chemicalContents(stack, service.chemicalRegistry());
				if (heldChemical == null) return stack;
			} else {
				heldMixture = MaterialBucketItem.mixture(stack);
				if (heldMixture == null) return stack;
			}
		} else if (kind == TransferKind.MATERIAL_SCOOP) {
			if (!service.materialFeatureEnabled() || !region.domain().hasMaterialState()) return stack;
		}
		FluidMixture resolvedMixture = heldMixture;
		ChemicalContents resolvedChemical = heldChemical;
		long extractionRequest = switch (kind) {
			case SCOOP -> extractableNanos(region.domain(), cell, heldFreeNanos(stack), 6, 0);
			case MATERIAL_SCOOP -> extractableNanos(region.domain(), cell,
					ContainerFluidLedger.NANOS_PER_BLOCK, 6, -1);
			default -> 0L;
		};
		if ((kind == TransferKind.SCOOP || kind == TransferKind.MATERIAL_SCOOP) && extractionRequest <= 0L) {
			return stack;
		}

		if (region.controllerManaged()) {
			int slot = findDispenserSlot(source, stack);
			if (slot < 0) return stack;
			ContainerTransferJournal.Actor actor = ContainerTransferJournal.Actor.dispenser(
					level.dimension().identifier().toString(), source.pos(), slot);
			ControllerTransfer transaction = switch (kind) {
				case POUR -> executeControllerTransfer(service, region, level, actor, stack,
						ContainerTransferJournal.Operation.INSERT, heldVolumeNanos(stack), 0L, cell,
						(requested, base) -> pourIntoCell(service, region, cell, requested, 6, 0),
						(rawAfter, before) -> rawAfter);
				case SCOOP -> executeControllerTransfer(service, region, level, actor, stack,
						ContainerTransferJournal.Operation.EXTRACT, extractionRequest, heldBaseVolumeNanos(stack), cell,
						(requested, base) -> scoopFromCell(service, region, cell, requested, base, 6, 0),
						(rawAfter, before) -> rawAfter);
				case MATERIAL_POUR -> executeControllerTransfer(service, region, level, actor, stack,
						ContainerTransferJournal.Operation.INSERT, MaterialBucketItem.volume(stack), 0L, cell,
						(requested, base) -> resolvedChemical == null
								? pourMaterialIntoCell(service, region, cell, resolvedMixture, 6)
								: pourChemicalIntoCell(region, cell, resolvedChemical, 6),
						(rawAfter, before) -> rawAfter);
				case MATERIAL_SCOOP -> executeControllerTransfer(service, region, level, actor, stack,
						ContainerTransferJournal.Operation.EXTRACT, extractionRequest, 0L, cell,
						(requested, base) -> service.chemistryFeatureEnabled() && region.domain().hasChemistryState()
								? scoopChemicalIntoCell(region, cell, requested, 6, service.chemicalRegistry())
								: scoopMaterialIntoCell(service, region, cell, requested, 6),
						(rawAfter, before) -> rawAfter);
				case NONE -> null;
			};
			if (!transaction.outcome().transferred()) return stack;
			source.blockEntity().setItem(slot, transaction.outcome().stack());
			source.blockEntity().setChanged();
			service.sendSnapshot(level, region);
			return transaction.outcome().stack();
		}
		TransferOutcome outcome = switch (kind) {
			case POUR -> pourIntoCell(service, region, cell, heldVolumeNanos(stack), 6, 0);
			case SCOOP -> scoopFromCell(service, region, cell, extractionRequest, heldBaseVolumeNanos(stack), 6, 0);
			case MATERIAL_POUR -> resolvedChemical == null
					? pourMaterialIntoCell(service, region, cell, resolvedMixture, 6)
					: pourChemicalIntoCell(region, cell, resolvedChemical, 6);
			case MATERIAL_SCOOP -> service.chemistryFeatureEnabled() && region.domain().hasChemistryState()
					? scoopChemicalIntoCell(region, cell, extractionRequest, 6, service.chemicalRegistry())
					: scoopMaterialIntoCell(service, region, cell, extractionRequest, 6);
			case NONE -> null;
		};
		if (outcome == null || !outcome.transferred()) return stack;
		service.sendSnapshot(level, region);
		return outcome.stack();
	}


	private static TransferOutcome pour(
			FluidSimulationService service, FluidRegion region, ServerPlayer player, InteractionHand hand, ItemStack held,
			int[] cell
	) {
		Long volume = heldVolumeNanos(held);
		if (volume == null || volume <= 0L) return null;
		if (region.controllerManaged()) return pourController(service, region, player, hand, held, volume, cell);
		TransferOutcome outcome = pourIntoCell(service, region, cell, volume, 6, 0);
		if (!outcome.transferred()) return new TransferOutcome(false, true, held, outcome.message());
		if (player.hasInfiniteMaterials()) {
			service.sendSnapshotFor(player, region);
			return new TransferOutcome(true, true, held.copy(), outcome.message());
		}
		service.sendSnapshotFor(player, region);
		return outcome;
	}


	private static TransferOutcome pourMaterial(
			FluidSimulationService service, FluidRegion region, ServerPlayer player, InteractionHand hand, ItemStack held,
			int[] cell
	) {
		ChemicalContents chemical = held.get(RealWaterComponents.CHEMICAL_CONTENTS) == null ? null
				: service.chemistryFeatureEnabled() ? MaterialBucketItem.chemicalContents(held, service.chemicalRegistry()) : null;
		FluidMixture mixture = chemical == null ? MaterialBucketItem.mixture(held) : null;
		if (mixture == null && chemical == null) {
			return new TransferOutcome(false, true, held, "化学桶内容损坏、双重表示或 registry 不匹配");
		}
		long volumeNanos = chemical == null ? mixture.totalVolumeNanos() : chemical.totalVolumeNanos();
		if (!service.materialFeatureEnabled()) {
			return new TransferOutcome(false, true, held, "材料功能门未开启");
		}
		if (!region.domain().hasMaterialState()) {
			return new TransferOutcome(false, true, held, "该区域不是材料区域");
		}
		if (chemical != null && (!service.chemistryFeatureEnabled() || !region.domain().hasChemistryState())) {
			return new TransferOutcome(false, true, held, "化学桶只能倒入启用相同 registry 的化学区域");
		}
		if (region.controllerManaged()) {
			ContainerTransferJournal.Actor actor = ContainerTransferJournal.Actor.player(player.getUUID(), hand.name());
			ControllerTransfer transaction = executeControllerTransfer(
					service, region, player.level(), actor, held,
					ContainerTransferJournal.Operation.INSERT, volumeNanos, 0L, cell,
					(requested, base) -> chemical == null ? pourMaterialIntoCell(service, region, cell, mixture, 6)
							: pourChemicalIntoCell(region, cell, chemical, 6),
					(rawAfter, before) -> player.hasInfiniteMaterials() ? before.copy() : rawAfter
			);
			if (!transaction.outcome().transferred()) return transaction.outcome();
			player.setItemInHand(hand, transaction.outcome().stack());
			player.getInventory().setChanged();
			service.sendSnapshotFor(player, region);
			return transaction.outcome();
		}
		TransferOutcome outcome = chemical == null ? pourMaterialIntoCell(service, region, cell, mixture, 6)
				: pourChemicalIntoCell(region, cell, chemical, 6);
		if (!outcome.transferred()) return new TransferOutcome(false, true, held, outcome.message());
		if (player.hasInfiniteMaterials()) {
			service.sendSnapshotFor(player, region);
			return new TransferOutcome(true, true, held.copy(), outcome.message());
		}
		service.sendSnapshotFor(player, region);
		return outcome;
	}

private static TransferOutcome scoop(
FluidSimulationService service, FluidRegion region, ServerPlayer player, InteractionHand hand, ItemStack held, int[] cell
) {
long capacity = heldFreeNanos(held);
if (capacity <= 0L) return null;
long requested = extractableNanos(region.domain(), cell, capacity, 6, 0);
if (requested <= 0L) return new TransferOutcome(false, false, held, "准星处没有可装入的水体");
if (region.controllerManaged()) return scoopController(service, region, player, hand, held, requested, cell);
TransferOutcome outcome = scoopFromCell(service, region, cell, requested, heldBaseVolumeNanos(held), 6, 0);
if (!outcome.transferred()) return new TransferOutcome(false, false, held, outcome.message());
// Creative pour is an explicit external source, but scoop always replaces the hand. Treating
// scoop as a hidden sink when an equivalent filled bucket already exists loses real solver water
// and cannot be represented by the controller WAL.
service.sendSnapshotFor(player, region);
return outcome;
}

private static TransferOutcome scoopMaterial(
FluidSimulationService service, FluidRegion region, ServerPlayer player, InteractionHand hand, ItemStack held, int[] cell
) {
if (!service.materialFeatureEnabled()) {
return new TransferOutcome(false, false, held, "材料功能门未开启");
}
if (!region.domain().hasMaterialState()) {
return new TransferOutcome(false, false, held, "该区域不是材料区域");
}
long capacityNanos = ContainerFluidLedger.NANOS_PER_BLOCK;
long requestedNanos = extractableNanos(region.domain(), cell, capacityNanos, 6, -1);
if (requestedNanos <= 0L) return new TransferOutcome(false, false, held, "准星处没有可装入的材料流体");
if (region.controllerManaged()) {
ContainerTransferJournal.Actor actor = ContainerTransferJournal.Actor.player(player.getUUID(), hand.name());
ControllerTransfer transaction = executeControllerTransfer(
service, region, player.level(), actor, held,
ContainerTransferJournal.Operation.EXTRACT, requestedNanos, 0L, cell,
(requested, base) -> service.chemistryFeatureEnabled() && region.domain().hasChemistryState()
? scoopChemicalIntoCell(region, cell, requested, 6, service.chemicalRegistry())
: scoopMaterialIntoCell(service, region, cell, requested, 6),
(rawAfter, before) -> rawAfter
);
if (!transaction.outcome().transferred()) return transaction.outcome();
player.setItemInHand(hand, transaction.outcome().stack());
player.getInventory().setChanged();
service.sendSnapshotFor(player, region);
return transaction.outcome();
}
TransferOutcome outcome = service.chemistryFeatureEnabled() && region.domain().hasChemistryState()
? scoopChemicalIntoCell(region, cell, requestedNanos, 6, service.chemicalRegistry())
: scoopMaterialIntoCell(service, region, cell, requestedNanos, 6);
if (!outcome.transferred()) return new TransferOutcome(false, false, held, outcome.message());
service.sendSnapshotFor(player, region);
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
				(requested, base) -> pourIntoCell(service, region, cell, requested, 6, 0),
				(rawAfter, before) -> player.hasInfiniteMaterials() ? before.copy() : rawAfter
		);
		if (!transaction.outcome().transferred()) return transaction.outcome();
		player.setItemInHand(hand, transaction.outcome().stack());
		player.getInventory().setChanged();
		service.sendSnapshotFor(player, region);
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
				(requested, base) -> scoopFromCell(service, region, cell, requested, base, 6, 0),
				(rawAfter, before) -> rawAfter
		);
		if (!transaction.outcome().transferred()) return transaction.outcome();
		player.setItemInHand(hand, transaction.outcome().stack());
		player.getInventory().setChanged();
		service.sendSnapshotFor(player, region);
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
			TransferExecutor executor,
			FinalItemResolver finalItemResolver
	) {
		if (service.hasPendingContainerTransfer(region.id())) {
			return new ControllerTransfer(
					new TransferOutcome(false, operation == ContainerTransferJournal.Operation.INSERT, beforeStack,
							"区域存在未完成容器转移 WAL，已锁定"),
					null);
		}
		if (requestedNanos <= 0L) return new ControllerTransfer(null, null);
		try {
			service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.BEFORE_PREPARE);
		} catch (RuntimeException injected) {
			return failedControllerTransfer(operation, beforeStack, injected.getMessage(), null);
		}
		long baseGeneration = region.checkpointGeneration();
		long targetGeneration = Math.addExact(baseGeneration, 1L);
		UUID transactionId = UUID.randomUUID();
		ContainerTransferJournal.ItemFingerprint beforeFingerprint;
		try {
			beforeFingerprint = ContainerTransferJournal.ItemFingerprint.of(beforeStack);
		} catch (IllegalArgumentException invalidItem) {
			return failedControllerTransfer(operation, beforeStack, invalidItem.getMessage(), null);
		}
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
		try {
			service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_PREPARE);
		} catch (RuntimeException injected) {
			return failedControllerTransfer(operation, beforeStack, injected.getMessage(), transactionId);
		}

		service.markContainerTransferSolverDirty(transactionId);
		TransferOutcome rawOutcome;
		try {
			rawOutcome = executor.execute(requestedNanos, baseVolumeNanos);
		} catch (RuntimeException transferFailure) {
			String failure = "容器转移执行异常，区域已锁定；重启后从 base checkpoint 恢复："
					+ transferFailure.getMessage();
			service.lockContainerTransferRegion(region, failure);
			return failedControllerTransfer(operation, beforeStack, failure, transactionId);
		}
		if (rawOutcome == null || !rawOutcome.transferred()) {
			service.markContainerTransferSolverClean(transactionId);
			deleteQuietly(service, transactionId);
			return new ControllerTransfer(rawOutcome == null
					? new TransferOutcome(false, operation == ContainerTransferJournal.Operation.INSERT, beforeStack, "转移未执行")
					: rawOutcome, null);
		}
		try {
			service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_SOLVER);
		} catch (RuntimeException injected) {
			String failure = injected.getMessage() + "；区域已锁定，重启后从 base checkpoint 恢复";
			service.lockContainerTransferRegion(region, failure);
			return failedControllerTransfer(operation, beforeStack,
					failure, transactionId);
		}

		ItemStack finalAfter;
		ContainerTransferJournal.ItemFingerprint afterFingerprint;
		try {
			finalAfter = finalItemResolver.resolve(rawOutcome.stack(), beforeStack);
			afterFingerprint = ContainerTransferJournal.ItemFingerprint.of(finalAfter);
		} catch (RuntimeException invalidAfterItem) {
			region.setPaused(true);
			region.setAvailabilityFailure("容器转移 after-item 无法写入 WAL，区域已锁定；重启后恢复："
					+ invalidAfterItem.getMessage());
			return failedControllerTransfer(operation, beforeStack, region.availabilityFailure(), transactionId);
		}
		FluidRegionPersistence.StagedCheckpoint staged;
		try {
			staged = service.stageControllerRegionCheckpoint(region, targetGeneration);
		} catch (IOException | RuntimeException stagingFailure) {
			region.setPaused(true);
			region.setAvailabilityFailure("容器转移 v3 checkpoint staging 失败，区域已锁定；重启后恢复："
					+ stagingFailure.getMessage());
			return failedControllerTransfer(operation, beforeStack, region.availabilityFailure(), transactionId);
		}
		try {
			service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_STAGE);
		} catch (RuntimeException injected) {
			String failure = injected.getMessage() + "；staged generation 尚未发布，重启后从 base checkpoint 恢复";
			service.lockContainerTransferRegion(region, failure);
			return failedControllerTransfer(operation, beforeStack, failure, transactionId);
		}
		ContainerTransferJournal committed = new ContainerTransferJournal(
				transactionId, region.id(), level.dimension().identifier().toString(),
				baseGeneration, targetGeneration, operation, rawOutcome.volumeNanos(), actor,
				beforeFingerprint, afterFingerprint, staged.manifestChecksum(),
				ContainerTransferJournal.Phase.REGION_COMMITTED
		);
		try {
			service.containerTransfers().write(committed);
		} catch (IOException exception) {
			try { service.abortControllerRegionCheckpoint(staged); }
			catch (IOException abortFailure) { exception.addSuppressed(abortFailure); }
			region.setPaused(true);
			region.setAvailabilityFailure("容器转移 WAL 更新失败，区域已锁定；重启后恢复：" + exception.getMessage());
			return new ControllerTransfer(
					new TransferOutcome(false, operation == ContainerTransferJournal.Operation.INSERT, beforeStack,
							region.availabilityFailure()),
					null);
		}
		try {
			service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_WAL);
		} catch (RuntimeException injected) {
			String failure = injected.getMessage() + "；区域已锁定，重启后恢复";
			service.lockContainerTransferRegion(region, failure);
			return failedControllerTransfer(operation, beforeStack,
					failure, transactionId);
		}



		long publishedGeneration;
		try {
			publishedGeneration = service.commitControllerRegionCheckpoint(region, staged);
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
		service.markContainerTransferRegionCommitted(transactionId);
		try {
			service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_CHECKPOINT);
		} catch (RuntimeException injected) {
			return failedControllerTransfer(operation, beforeStack,
					injected.getMessage() + "；已提交区域，WAL 将收敛物品", transactionId);
		}



		return new ControllerTransfer(new TransferOutcome(true, operation == ContainerTransferJournal.Operation.INSERT,
				finalAfter, rawOutcome.message(), rawOutcome.volumeNanos()), transactionId);
	}

	private static ControllerTransfer failedControllerTransfer(
			ContainerTransferJournal.Operation operation,
			ItemStack beforeStack,
			String message,
			UUID transactionId
	) {
		return new ControllerTransfer(new TransferOutcome(false,
				operation == ContainerTransferJournal.Operation.INSERT, beforeStack, message), transactionId);
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

	@FunctionalInterface
	private interface TransferExecutor {
		TransferOutcome execute(long requestedNanos, long baseVolumeNanos);
	}


	private static TransferOutcome pourIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long volumeNanos, int maxRadius, int materialHandle
	) {
		if (volumeNanos <= 0L) return null;
		float requestedCellVolume = nanosToCellVolumeAtMost(volumeNanos);
		if (requestedCellVolume <= 0.0F) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "容器余量小于 solver 可表示体积，未能注入");
		}
		FlipFluidDomain domain = region.domain();
		int firstInsertedMarker = domain.particleCount();
		if (domain.totalOpenVolume() - domain.totalVolume() + 1.0E-6F < requestedCellVolume) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "空间或 marker 预算不足，未能注入");
		}
		FlipFluidDomain.ExactVolumeTransfer inserted = domain.tryInsertExactVolume(
				cell[0], cell[1], cell[2], requestedCellVolume, maxRadius, materialHandle);
		if (!inserted.transferred()) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "空间或 marker 预算不足，未能注入");
		}
		if (domain.hasChemistryState()) {
			domain.chemicalParcels().recordExternalSource(
					domain.chemicalLedgerForMarkerRange(firstInsertedMarker, domain.particleCount()));
		}
		long poured = settledNanos(inserted.volume(), volumeNanos);
		region.wakeForActivity();
		return new TransferOutcome(true, true, withVolume(volumeNanos - poured),
				String.format("已注入 %.2f%% 桶", 100.0 * poured / ContainerFluidLedger.NANOS_PER_BLOCK), poured);
	}

	private static TransferOutcome scoopFromCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long capacityNanos, long baseVolumeNanos, int maxRadius, int materialHandle
	) {
		if (capacityNanos <= 0L) return null;
		float requestedCellVolume = nanosToCellVolumeAtMost(capacityNanos);
		if (requestedCellVolume <= 0.0F) {
			return new TransferOutcome(false, false, withVolume(baseVolumeNanos), "容器余量小于 solver 可表示体积");
		}
		FlipFluidDomain.ExactVolumeTransfer extracted = region.domain().tryExtractExactVolume(
				cell[0], cell[1], cell[2], requestedCellVolume, maxRadius, materialHandle);
		if (!extracted.transferred() || extracted.volume() <= 0.0) {
			return new TransferOutcome(false, false, withVolume(baseVolumeNanos), "准星处没有足够水体");
		}
		long taken = settledNanos(extracted.volume(), capacityNanos);
		region.wakeForActivity();
		return new TransferOutcome(true, false, withVolume(baseVolumeNanos + taken),
				String.format("已舀取 %.2f%% 桶", 100.0 * taken / ContainerFluidLedger.NANOS_PER_BLOCK), taken);
	}


	private static TransferOutcome pourMaterialIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, FluidMixture mixture, int maxRadius
	) {
		if (mixture == null) return null;
		FlipFluidDomain domain = region.domain();
		int firstInsertedMarker = domain.particleCount();
		java.util.List<FlipFluidDomain.MaterialPortion> portions;
		try {
			portions = planMaterialInsertion(domain, mixture);
		} catch (IllegalArgumentException unknownMaterial) {
			return new TransferOutcome(false, true, mixture.toStack(),
					"区域材料 registry 不接受该混合物：" + unknownMaterial.getMessage());
		}
		FlipFluidDomain.ExactMixtureTransfer inserted = domain.tryInsertExactMixture(
				cell[0], cell[1], cell[2], portions, maxRadius);
		if (!inserted.transferred()) {
			return new TransferOutcome(false, true, mixture.toStack(), "空间或 marker 预算不足，未能注入混合物");
		}
		if (domain.hasChemistryState()) {
			domain.chemicalParcels().recordExternalSource(
					domain.chemicalLedgerForMarkerRange(firstInsertedMarker, domain.particleCount()));
		}
		MaterialInsertionSettlement settlement = settleMaterialInsertion(mixture, inserted.portions());
		ItemStack after = settlement.remaining() == null
				? new ItemStack(RealWaterItems.MATERIAL_BUCKET)
				: settlement.remaining().toStack();
		region.wakeForActivity();
		return new TransferOutcome(true, true, after,
				String.format("已注入 %.2f%% 桶（%d 种材料）",
						100.0 * settlement.insertedNanos() / ContainerFluidLedger.NANOS_PER_BLOCK,
						mixture.components().size()), settlement.insertedNanos());
	}

	private static TransferOutcome pourChemicalIntoCell(
			FluidRegion region, int[] cell, ChemicalContents contents, int maxRadius
	) {
		FlipFluidDomain domain = region.domain();
		if (!domain.hasChemistryState()) return new TransferOutcome(false, true, contents.toStack(), "该区域不是化学区域");
		com.example.chemistry.ChemicalTransferLedger requested;
		try {
			contents.validateAgainst(domain.chemicalParcels().registry());
			requested = contents.toLedger(domain.chemicalParcels().registry(), FluidSimulationService.CELL_VOLUMES_PER_BLOCK);
		} catch (IllegalArgumentException invalid) {
			return new TransferOutcome(false, true, contents.toStack(), "化学桶 registry 或账本不合法：" + invalid.getMessage());
		}
		FlipFluidDomain.ExactChemicalTransfer inserted = domain.tryInsertExactChemical(
				cell[0], cell[1], cell[2], requested, maxRadius);
		if (!inserted.transferred()) {
			return new TransferOutcome(false, true, contents.toStack(), "空间、marker 或化学预算不足，未能注入");
		}
		domain.chemicalParcels().recordExternalSource(inserted.ledger());
		region.wakeForActivity();
		return new TransferOutcome(true, true, new ItemStack(RealWaterItems.MATERIAL_BUCKET),
				String.format("已注入 %.2f%% 桶化学流体", 100.0 * contents.totalVolumeNanos()
						/ ContainerFluidLedger.NANOS_PER_BLOCK), contents.totalVolumeNanos());
	}

	private static TransferOutcome scoopChemicalIntoCell(
			FluidRegion region, int[] cell, long capacityNanos, int maxRadius,
			com.example.chemistry.ChemicalRegistry registry
	) {
		if (capacityNanos <= 0L) return null;
		float requested = nanosToCellVolumeAtMost(capacityNanos);
		if (requested <= 0.0F) return new TransferOutcome(false, false,
				new ItemStack(RealWaterItems.MATERIAL_BUCKET), "容器余量小于 solver 可表示体积");
		FlipFluidDomain.ExactChemicalTransfer extracted = region.domain().tryExtractExactChemical(
				cell[0], cell[1], cell[2], requested, maxRadius);
		if (!extracted.transferred() || extracted.ledger().isEmpty()) return new TransferOutcome(false, false,
				new ItemStack(RealWaterItems.MATERIAL_BUCKET), "准星处没有足够化学流体");
		long taken = settledNanos(extracted.ledger().totalVolume(), capacityNanos);
		ChemicalContents contents = ChemicalContents.fromLedger(extracted.ledger(), registry, taken);
		region.wakeForActivity();
		return new TransferOutcome(true, false, contents.toStack(),
				String.format("已舀取 %.2f%% 桶化学流体（%d 组）", 100.0 * taken
						/ ContainerFluidLedger.NANOS_PER_BLOCK, contents.groups().size()), taken);
	}

private static TransferOutcome scoopMaterialIntoCell(
FluidSimulationService service, FluidRegion region, int[] cell, long capacityNanos, int maxRadius
) {
if (capacityNanos <= 0L) return null;
FlipFluidDomain domain = region.domain();
float requestedCellVolume = nanosToCellVolumeAtMost(capacityNanos);
if (requestedCellVolume <= 0.0F) {
return new TransferOutcome(false, false, new ItemStack(RealWaterItems.MATERIAL_BUCKET),
"容器余量小于 solver 可表示体积");
}
FlipFluidDomain.ExactMixtureTransfer extracted = domain.tryExtractExactMixture(
cell[0], cell[1], cell[2], requestedCellVolume, maxRadius);
if (!extracted.transferred() || extracted.portions().isEmpty()) {
return new TransferOutcome(false, false, new ItemStack(RealWaterItems.MATERIAL_BUCKET), "准星处没有足够流体");
}
FluidMixture mixture = mixtureFromExtraction(domain, extracted.portions(), capacityNanos);
long taken = mixture.totalVolumeNanos();
region.wakeForActivity();
return new TransferOutcome(true, false, mixture.toStack(),
String.format("已舀取 %.2f%% 桶（%d 种材料）",
100.0 * taken / ContainerFluidLedger.NANOS_PER_BLOCK, mixture.components().size()), taken);
}

	/** Deterministic largest-remainder-style apportionment; component masses remain exact doubles. */
	static FluidMixture mixtureFromExtraction(
			FlipFluidDomain domain, java.util.List<FlipFluidDomain.MaterialPortion> portions, long capacityNanos
	) {
		double totalVolume = 0.0;
		for (FlipFluidDomain.MaterialPortion portion : portions) totalVolume += portion.volume();
		long totalNanos = settledNanos(totalVolume, capacityNanos);
		long remainingNanos = totalNanos;
		double remainingVolume = totalVolume;
		java.util.List<FluidMixture.Component> components = new java.util.ArrayList<>(portions.size());
		for (int i = 0; i < portions.size(); i++) {
			FlipFluidDomain.MaterialPortion portion = portions.get(i);
			int componentsLeft = portions.size() - i;
			long nanos = componentsLeft == 1 ? remainingNanos
					: Math.max(1L, Math.min(remainingNanos - (componentsLeft - 1L),
					Math.round(remainingNanos * portion.volume() / remainingVolume)));
			String materialId = domain.materialRegistry().material(portion.materialHandle()).id();
			components.add(new FluidMixture.Component(materialId, nanos, portion.mass()));
			remainingNanos -= nanos;
			remainingVolume -= portion.volume();
		}
		return new FluidMixture(components);
	}

	static java.util.List<FlipFluidDomain.MaterialPortion> planMaterialInsertion(
			FlipFluidDomain domain, FluidMixture mixture
	) {
		java.util.List<FlipFluidDomain.MaterialPortion> portions = new java.util.ArrayList<>(mixture.components().size());
		for (FluidMixture.Component component : mixture.components()) {
			int handle = domain.materialRegistry().handle(component.materialId());
			float plannedVolume = nanosToCellVolumeAtMost(component.volumeNanos());
			long plannedNanos = cellVolumeToNanos(plannedVolume);
			if (plannedNanos <= 0L) throw new IllegalArgumentException(
					"材料组分小于 solver 可表示体积：" + component.materialId());
			double plannedMass = plannedNanos == component.volumeNanos()
					? component.mass()
					: component.mass() * plannedNanos / component.volumeNanos();
			portions.add(new FlipFluidDomain.MaterialPortion(handle, plannedVolume, plannedMass));
		}
		return java.util.List.copyOf(portions);
	}

	static MaterialInsertionSettlement settleMaterialInsertion(
			FluidMixture sourceMixture, java.util.List<FlipFluidDomain.MaterialPortion> insertedPortions
	) {
		if (insertedPortions.size() != sourceMixture.components().size()) {
			throw new IllegalStateException("材料注入返回的组分数与请求不一致");
		}
		java.util.List<FluidMixture.Component> remaining = new java.util.ArrayList<>();
		long insertedNanos = 0L;
		for (int i = 0; i < sourceMixture.components().size(); i++) {
			FluidMixture.Component source = sourceMixture.components().get(i);
			FlipFluidDomain.MaterialPortion actual = insertedPortions.get(i);
			long componentNanos = settledNanos(actual.volume(), source.volumeNanos());
			insertedNanos = Math.addExact(insertedNanos, componentNanos);
			long remainingNanos = source.volumeNanos() - componentNanos;
			double remainingMass = source.mass() - actual.mass();
			if (remainingNanos == 0L) {
				if (Double.doubleToRawLongBits(actual.mass()) != source.massBits()) {
					throw new IllegalStateException("完整材料组分注入未结算其全部质量：" + source.materialId());
				}
			} else {
				if (!(Double.isFinite(remainingMass) && remainingMass > 0.0)) {
					throw new IllegalStateException("材料组分余量没有正有限质量：" + source.materialId());
				}
				remaining.add(new FluidMixture.Component(source.materialId(), remainingNanos, remainingMass));
			}
		}
		return new MaterialInsertionSettlement(insertedNanos,
				remaining.isEmpty() ? null : new FluidMixture(remaining));
	}

	record MaterialInsertionSettlement(long insertedNanos, FluidMixture remaining) { }
	private enum TransferKind {
		NONE,
		POUR,
		SCOOP,
		MATERIAL_POUR,
		MATERIAL_SCOOP
	}

	private static TransferKind classify(ItemStack stack) {
		if (stack.is(Items.WATER_BUCKET)) return TransferKind.POUR;
		if (stack.is(Items.BUCKET)) return TransferKind.SCOOP;
		if (stack.getItem() instanceof PartialWaterBucketItem) {
			return PartialWaterBucketItem.volume(stack) > 0L ? TransferKind.POUR : TransferKind.NONE;
		}
		if (stack.getItem() instanceof MaterialBucketItem) {
			if (MaterialBucketItem.isEmptyMaterialBucket(stack)) return TransferKind.MATERIAL_SCOOP;
			return (stack.get(RealWaterComponents.CHEMICAL_CONTENTS) != null || MaterialBucketItem.mixture(stack) != null)
					? TransferKind.MATERIAL_POUR
					: TransferKind.NONE;
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

	/** Largest float cell volume whose nanovolume settlement does not exceed the source/capacity. */
	static float nanosToCellVolumeAtMost(long nanos) {
		if (nanos <= 0L) return 0.0F;
		float volume = nanosToCellVolume(nanos);
		while (volume > 0.0F && cellVolumeToNanos(volume) > nanos) volume = Math.nextDown(volume);
		return volume;
	}

	static long cellVolumeToNanos(double cellVolume) {
		return ContainerFluidLedger.volumeToNanos(cellVolume / FluidSimulationService.CELL_VOLUMES_PER_BLOCK);
	}

	/** Converts the primitive's authoritative marker delta and rejects impossible item overshoot. */
	static long settledNanos(double actualCellVolume, long maximumNanos) {
		if (maximumNanos <= 0L) throw new IllegalArgumentException("Settlement limit must be positive");
		long actualNanos = cellVolumeToNanos(actualCellVolume);
		if (actualNanos <= 0L || actualNanos > maximumNanos) {
			throw new IllegalStateException("Solver transfer settled " + actualNanos
					+ " nanovolume quanta outside 1.." + maximumNanos);
		}
		return actualNanos;
	}

	static long extractableNanos(
			FlipFluidDomain domain, int[] cell, long capacityNanos, int maxRadius, int materialHandle
	) {
		if (capacityNanos <= 0L) return 0L;
		double availableCellVolume = domain.exactVolumeNear(
				cell[0], cell[1], cell[2], maxRadius, materialHandle);
		if (!(availableCellVolume > 0.0)) return 0L;
		long availableNanos = cellVolumeToNanos(availableCellVolume);
		long request = Math.min(capacityNanos, availableNanos);
		float requestCellVolume = nanosToCellVolumeAtMost(request);
		while (requestCellVolume > availableCellVolume) requestCellVolume = Math.nextDown(requestCellVolume);
		request = requestCellVolume <= 0.0F ? 0L : cellVolumeToNanos(requestCellVolume);
		// A material mixture must assign at least one nanovolume quantum to every possible
		// component. Below that bound the independent per-material volume ledger cannot be encoded.
		if (materialHandle < 0 && domain.hasMaterialState()
				&& request < domain.materialRegistry().size()) return 0L;
		return request;
	}

	private static BlockPos playerPourTargetBlock(ServerPlayer player) {
		BlockHitResult hit = (BlockHitResult) player.pick(5.0, 1.0F, false);
		if (hit.getType() != HitResult.Type.BLOCK) return null;
		BlockPos hitPos = hit.getBlockPos();
		return player.level().getBlockState(hitPos).canBeReplaced()
				? hitPos
				: hitPos.relative(hit.getDirection());
	}

	private record TransferOutcome(boolean transferred, boolean filled, ItemStack stack, String message, long volumeNanos) {
		private TransferOutcome(boolean transferred, boolean filled, ItemStack stack, String message) {
			this(transferred, filled, stack, message, 0L);
		}
	}
}
