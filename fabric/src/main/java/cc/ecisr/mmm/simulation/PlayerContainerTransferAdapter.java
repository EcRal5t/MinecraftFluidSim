package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.item.CreativeSourceItem;
import cc.ecisr.mmm.transfer.ContainerTransferActor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.UUID;

/** Minecraft player hit/hand/output adapter for the shared container transaction path. */
public final class PlayerContainerTransferAdapter {
	private final WorldStartupCoordinator.Result startup;
	private final CommittedWorldRegistries world;
	private final FluidWorldTargetResolver targets;
	private final MinecraftRegionAvailability availability;
	private final MinecraftPendingPhaseWorkBarrier phaseWorkBarrier;
	private final MinecraftContainerTransactionAdapter transactions;
	private final FluidSnapshotPublisher snapshots;

	PlayerContainerTransferAdapter(
			WorldStartupCoordinator.Result startup,
			CommittedWorldRegistries world,
			FluidWorldTargetResolver targets,
			MinecraftRegionAvailability availability,
			MinecraftPendingPhaseWorkBarrier phaseWorkBarrier,
			MinecraftContainerTransactionAdapter transactions,
			FluidSnapshotPublisher snapshots
	) {
		this.startup = startup;
		this.world = world;
		this.targets = targets;
		this.availability = availability;
		this.phaseWorkBarrier = phaseWorkBarrier;
		this.transactions = transactions;
		this.snapshots = snapshots;
	}

	public static InteractionResult handle(ServerPlayer player, InteractionHand hand) {
		return MmmRuntimeRegistry.require(player.level().getServer()).playerContainerTransfers()
				.handlePlayer(player, hand);
	}

	private InteractionResult handlePlayer(ServerPlayer player, InteractionHand hand) {
		if (!startup.authoritativeWritesReady()) return null;
		ItemStack held = player.getItemInHand(hand);
		if (held.isEmpty()) return null;
		if (held.getItem() instanceof CreativeSourceItem) {
			return handleCreativeSource(player, hand, held);
		}

		MinecraftContainerTransferOperations.TransferKind kind =
				MinecraftContainerTransferOperations.classify(held);
		if (kind == MinecraftContainerTransferOperations.TransferKind.NONE) return null;
		if ((kind == MinecraftContainerTransferOperations.TransferKind.MATERIAL_POUR
				|| kind == MinecraftContainerTransferOperations.TransferKind.GRANULAR_POUR)
				&& !world.acceptsActualContainerProfile(held)) {
			message(player, "FOREIGN_PROFILE：actual container 不属于当前世界；库存保持不变");
			return InteractionResult.FAIL;
		}
		ServerLevel level = player.level();
		boolean pouring = kind == MinecraftContainerTransferOperations.TransferKind.POUR
				|| kind == MinecraftContainerTransferOperations.TransferKind.MATERIAL_POUR
				|| kind == MinecraftContainerTransferOperations.TransferKind.GRANULAR_POUR;
		BlockHitResult hit = playerBlockHit(player);
		BlockPos targetBlock = pouring ? playerPourTargetBlock(player, hit) : null;
		if (pouring && targetBlock == null) return null;
		FluidWorldTargetResolver.TransferTarget target = pouring
				? targets.transferTargetAtBlock(level, targetBlock, null)
				: hit != null && MmmBlocks.isBoundary(level.getBlockState(hit.getBlockPos()))
						? targets.transferTargetAtBlock(level, hit.getBlockPos(), null) : null;
		FluidRegion region = pouring
				? target == null ? null : target.region()
				: targets.aimedRegion(player);
		if (region == null) return null;
		if (held.getCount() != 1) {
			message(player, "物质、运动与力学转移一次只接受 1 个桶；堆叠保持不变");
			return InteractionResult.FAIL;
		}
		if (!ensureAvailableFor(player, region, !pouring)) return InteractionResult.FAIL;
		if (!phaseWorkBarrier.settle(level, region)) return InteractionResult.FAIL;
		String thermalFailure = MinecraftContainerTransferOperations.thermalTransferFailure(
				world, region, held, kind);
		if (thermalFailure != null) {
			message(player, thermalFailure);
			return InteractionResult.FAIL;
		}
		int[] cell = target != null
				? targets.cellAtBlock(level, region, target.interiorBlock())
				: kind == MinecraftContainerTransferOperations.TransferKind.GRANULAR_SCOOP
						? targets.aimedGranularCellNear(player, region)
						: targets.aimedCellNear(player, region, true);
		if (cell == null) return null;

		MinecraftContainerTransferPlan.Resolution resolution = MinecraftContainerTransferPlan.resolve(
				transactions, world, region, cell, held, player.hasInfiniteMaterials());
		if (!resolution.resolved()) {
			message(player, resolution.diagnostic());
			return InteractionResult.FAIL;
		}
		MinecraftContainerTransferPlan plan = resolution.plan();
		Attempt attempt;
		if (region.controllerManaged()) {
			MinecraftContainerTransactionAdapter.Result transaction = plan.executeManaged(
					level, ContainerTransferActor.player(player.getUUID(), actorHand(hand)), held, false);
			attempt = new Attempt(transaction.outcome(), transaction.transactionId());
		} else {
			MinecraftContainerTransferOperations.TransferOutcome outcome = plan.executeUnmanaged();
			if (outcome != null && outcome.transferred() && player.hasInfiniteMaterials()) {
				outcome = new MinecraftContainerTransferOperations.TransferOutcome(
						true, plan.filled(), held.copy(), outcome.message(), outcome.resourceAmount(),
						outcome.thermalEnergy());
			}
			attempt = new Attempt(outcome, null);
		}
		if (attempt.outcome() == null) return null;
		MinecraftContainerTransferOperations.TransferOutcome outcome = attempt.outcome();
		if (!outcome.message().isEmpty()) message(player, outcome.message());
		if (!outcome.transferred()) return InteractionResult.FAIL;
		player.setItemInHand(hand, world.bindActualContainerProfile(outcome.stack()));
		player.getInventory().setChanged();
		transactions.completeCommitted(attempt.transactionId());
		snapshots.sendTo(player, region);
		level.playSound(null, player.getX(), player.getY() + 1.0, player.getZ(),
				outcome.filled() ? SoundEvents.BUCKET_EMPTY : SoundEvents.BUCKET_FILL,
				SoundSource.BLOCKS, 1.0F, 1.0F);
		return InteractionResult.SUCCESS;
	}

	private InteractionResult handleCreativeSource(
			ServerPlayer player, InteractionHand hand, ItemStack descriptorStack
	) {
		if (!player.hasInfiniteMaterials() || player.getClass() != ServerPlayer.class) {
			message(player, "创造 source 模板只接受真实创造模式玩家");
			return InteractionResult.FAIL;
		}
		var descriptor = CreativeSourceItem.descriptor(descriptorStack);
		if (descriptor == null) {
			message(player, "创造 source descriptor 损坏；未修改区域");
			return InteractionResult.FAIL;
		}
		CreativeSourceCatalog.Resolved resolved;
		try {
			resolved = CreativeSourceCatalog.resolve(world, descriptor);
		} catch (RuntimeException invalid) {
			message(player, "当前世界拒绝该创造 source：" + invalid.getMessage());
			return InteractionResult.FAIL;
		}
		ServerLevel level = player.level();
		BlockHitResult hit = playerBlockHit(player);
		BlockPos targetBlock = playerPourTargetBlock(player, hit);
		if (targetBlock == null) return InteractionResult.FAIL;
		FluidWorldTargetResolver.TransferTarget target = targets.transferTargetAtBlock(level, targetBlock, null);
		FluidRegion region = target == null ? null : target.region();
		if (region == null || !region.controllerManaged()
				|| !ensureAvailableFor(player, region)) return InteractionResult.FAIL;
		if (!phaseWorkBarrier.settle(level, region)) return InteractionResult.FAIL;
		int[] cell = targets.cellAtBlock(level, region, target.interiorBlock());
		if (cell == null) return InteractionResult.FAIL;

		ContainerTransferActor actor = ContainerTransferActor.player(player.getUUID(), actorHand(hand));
		ItemStack actual = resolved.actualStack();
		MinecraftContainerTransferPlan.Resolution resolution = MinecraftContainerTransferPlan.resolve(
				transactions, world, region, cell, actual, true);
		if (!resolution.resolved()) {
			message(player, resolution.diagnostic());
			return InteractionResult.FAIL;
		}
		if (resolution.plan().requestedAmount() != resolved.typedAmount()) {
			message(player, "创造 source catalog 与 actual container 的转移量不一致；未修改区域");
			return InteractionResult.FAIL;
		}
		MinecraftContainerTransactionAdapter.Result transaction = resolution.plan().executeManaged(
				level, actor, descriptorStack, true);
		MinecraftContainerTransferOperations.TransferOutcome outcome = transaction.outcome();
		if (!outcome.message().isEmpty()) message(player, outcome.message());
		if (!outcome.transferred()) return InteractionResult.FAIL;
		player.setItemInHand(hand, outcome.stack());
		player.getInventory().setChanged();
		transactions.completeCommitted(transaction.transactionId());
		snapshots.sendTo(player, region);
		level.playSound(null, player.getX(), player.getY() + 1.0, player.getZ(),
				SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
		return InteractionResult.SUCCESS;
	}

	private static void message(ServerPlayer player, String text) {
		player.sendSystemMessage(Component.literal(text), true);
	}

	private boolean ensureAvailableFor(ServerPlayer player, FluidRegion region) {
		return ensureAvailableFor(player, region, false);
	}

	private boolean ensureAvailableFor(ServerPlayer player, FluidRegion region, boolean inventoryDecrease) {
		boolean available = inventoryDecrease
				? availability.ensureForInventoryDecrease(player.level(), region)
				: availability.ensure(player.level(), region);
		if (!available) {
			message(player, "物质、运动与力学目标区域不可用：" + region.availabilityFailure());
		}
		return available;
	}

	private static ContainerTransferActor.Hand actorHand(InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND
				? ContainerTransferActor.Hand.MAIN : ContainerTransferActor.Hand.OFF;
	}

	private static BlockHitResult playerBlockHit(ServerPlayer player) {
		HitResult hit = player.pick(5.0, 1.0F, false);
		return hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK ? blockHit : null;
	}

	private static BlockPos playerPourTargetBlock(ServerPlayer player, BlockHitResult hit) {
		if (hit == null) return null;
		BlockPos hitPos = hit.getBlockPos();
		if (MmmBlocks.isBoundary(player.level().getBlockState(hitPos))) return hitPos;
		return player.level().getBlockState(hitPos).canBeReplaced()
				? hitPos : hitPos.relative(hit.getDirection());
	}

	private record Attempt(
			MinecraftContainerTransferOperations.TransferOutcome outcome, UUID transactionId
	) { }
}
