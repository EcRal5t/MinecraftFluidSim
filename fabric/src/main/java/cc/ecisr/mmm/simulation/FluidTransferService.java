package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.transfer.ContainerFluidLedger;
import cc.ecisr.mmm.transfer.ChemicalContents;
import cc.ecisr.mmm.transfer.ContainerTransferActor;
import cc.ecisr.mmm.transfer.ContainerTransferJournal;
import cc.ecisr.mmm.transfer.FluidMixture;
import cc.ecisr.mmm.item.GranularContainerItem;
import cc.ecisr.mmm.transfer.GranularContents;
import cc.ecisr.mmm.item.MaterialBucketItem;
import cc.ecisr.mmm.item.PartialWaterBucketItem;
import cc.ecisr.mmm.item.MmmItems;
import cc.ecisr.mmm.item.MmmComponents;
import cc.ecisr.mmm.item.ContainerItemStacks;
import cc.ecisr.mmm.transfer.ThermalContents;
import cc.ecisr.mmm.granular.GranularTransferLedger;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.worldprofile.WorldCapability;
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


/**
 * Single server-side entry point for player and dispenser bucket transfers with owned regions.
 * Every path settles on the exact primitives: insertion is all-or-nothing against the container's
 * balance, extraction credits at most the container's free space, and the item is only replaced
 * after the region mutation succeeded. Full and empty pure-water states canonicalize to vanilla
 * buckets; partial volumes always live in the Matter, Motion & Mechanics component.
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
		boolean pouring = kind == TransferKind.POUR || kind == TransferKind.MATERIAL_POUR
				|| kind == TransferKind.GRANULAR_POUR;
		BlockHitResult blockHit = playerBlockHit(player);
		BlockPos targetBlock = pouring ? playerPourTargetBlock(player, blockHit) : null;
		if (pouring && targetBlock == null) return null;
		FluidSimulationService.TransferTarget transferTarget = pouring
				? service.transferTargetAtBlock(level, targetBlock)
				: blockHit != null && MmmBlocks.isBoundary(level.getBlockState(blockHit.getBlockPos()))
						? service.transferTargetAtBlock(level, blockHit.getBlockPos()) : null;
		FluidRegion region = pouring
				? transferTarget == null ? null : transferTarget.region()
				: service.aimedRegion(player);
		if (region == null) return null;
		if (held.getCount() != 1) {
			player.sendSystemMessage(Component.literal("物质、运动与力学转移一次只接受 1 个桶；堆叠保持不变"), true);
			return InteractionResult.FAIL;
		}
		if (!service.ensureRegionAvailableFor(player, region)) {
			return InteractionResult.FAIL;
		}
		String thermalFailure = thermalTransferFailure(service, region, held, kind);
		if (thermalFailure != null) {
			player.sendSystemMessage(Component.literal(thermalFailure), true);
			return InteractionResult.FAIL;
		}
		int[] cell = transferTarget != null
				? service.cellAtBlock(level, region, transferTarget.interiorBlock())
				: kind == TransferKind.GRANULAR_SCOOP
						? service.aimedGranularCellNear(player, region)
						: service.aimedCellNear(player, region, true);
		if (cell == null) return null;

		TransferOutcome outcome = switch (kind) {
			case POUR -> pour(service, region, player, hand, held, cell);
			case SCOOP -> scoop(service, region, player, hand, held, cell);
			case MATERIAL_POUR -> pourMaterial(service, region, player, hand, held, cell);
			case MATERIAL_SCOOP -> scoopMaterial(service, region, player, hand, held, cell);
			case GRANULAR_POUR -> pourGranular(service, region, player, hand, held, cell);
			case GRANULAR_SCOOP -> scoopGranular(service, region, player, hand, held, cell);
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
		var facing = source.state().getValue(DispenserBlock.FACING);
		BlockPos target = source.pos().relative(facing);
		FluidSimulationService.TransferTarget transferTarget = service.transferTargetAtBlock(level, target, facing);
		FluidRegion region = transferTarget == null ? null : transferTarget.region();
		if (region == null) return vanillaItem ? null : stack;
		if (stack.getCount() != 1) return stack;
		if (!service.ensureRegionAvailable(level, region)) return stack;
		if (thermalTransferFailure(service, region, stack, kind) != null) return stack;
		int[] cell = service.cellAtBlock(level, region, transferTarget.interiorBlock());
		if (cell == null) return stack;
		FluidMixture heldMixture = null;
		ChemicalContents heldChemical = null;
		GranularContents heldGranular = null;
		if (kind == TransferKind.MATERIAL_POUR) {
			if (!service.worldCapabilityProfile().hasCapability(WorldCapability.MATERIALS) || !region.domain().hasMaterialState()) return stack;
			if (stack.get(MmmComponents.CHEMICAL_CONTENTS) != null) {
				if (!service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY) || !region.domain().hasChemistryState()) return stack;
				heldChemical = MaterialBucketItem.chemicalContents(stack, service.chemicalRegistry());
				if (heldChemical == null) return stack;
			} else {
				heldMixture = MaterialBucketItem.mixture(stack);
				if (heldMixture == null) return stack;
			}
		} else if (kind == TransferKind.MATERIAL_SCOOP) {
			if (!service.worldCapabilityProfile().hasCapability(WorldCapability.MATERIALS) || !region.domain().hasMaterialState()) return stack;
		} else if (kind == TransferKind.GRANULAR_POUR) {
			if (!service.worldCapabilityProfile().hasCapability(WorldCapability.GRANULAR) || !region.domain().hasGranularState()
					|| !region.controllerManaged()) return stack;
			try {
				heldGranular = ContainerItemStacks.granularContents(stack, service.granularRegistry(),
						service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY) ? service.chemicalRegistry() : null);
			} catch (RuntimeException invalid) {
				return stack;
			}
			if (heldGranular == null) return stack;
		} else if (kind == TransferKind.GRANULAR_SCOOP) {
			if (!service.worldCapabilityProfile().hasCapability(WorldCapability.GRANULAR) || !region.domain().hasGranularState()
					|| !region.controllerManaged()) return stack;
		}
		FluidMixture resolvedMixture = heldMixture;
		ChemicalContents resolvedChemical = heldChemical;
		GranularContents resolvedGranular = heldGranular;
		ThermalContents resolvedThermal = kind == TransferKind.GRANULAR_POUR
				|| kind == TransferKind.GRANULAR_SCOOP ? null : thermalContents(stack);
		long extractionRequest = switch (kind) {
			case SCOOP -> extractableNanos(region.domain(), cell, heldFreeNanos(stack), 6, 0);
			case MATERIAL_SCOOP -> extractableNanos(region.domain(), cell,
					ContainerFluidLedger.NANOS_PER_BLOCK, 6, -1);
			case GRANULAR_SCOOP -> SolidVolume.SVQ_PER_BV;
			default -> 0L;
		};
		if ((kind == TransferKind.SCOOP || kind == TransferKind.MATERIAL_SCOOP
				|| kind == TransferKind.GRANULAR_SCOOP) && extractionRequest <= 0L) {
			return stack;
		}

		if (region.controllerManaged()) {
			int slot = findDispenserSlot(source, stack);
			if (slot < 0) return stack;
			BlockPos actorPosition = source.pos();
			ContainerTransferActor actor = ContainerTransferActor.dispenser(
					level.dimension().identifier().toString(), actorPosition.getX(), actorPosition.getY(),
					actorPosition.getZ(), slot);
			ControllerItemTransferCoordinator.Result transaction = switch (kind) {
				case POUR -> ControllerItemTransferCoordinator.execute(service, region, level, actor, stack,
						ContainerTransferJournal.Operation.INSERT, heldVolumeNanos(stack), 0L,
						(requested, base) -> pourIntoCell(service, region, cell, requested, 6, 0, false,
								resolvedThermal),
						(rawAfter, before) -> rawAfter);
				case SCOOP -> ControllerItemTransferCoordinator.execute(service, region, level, actor, stack,
						ContainerTransferJournal.Operation.EXTRACT, extractionRequest, heldBaseVolumeNanos(stack),
						(requested, base) -> scoopFromCell(service, region, cell, requested, base, 6, 0,
								resolvedThermal),
						(rawAfter, before) -> rawAfter);
				case MATERIAL_POUR -> ControllerItemTransferCoordinator.execute(service, region, level, actor, stack,
						ContainerTransferJournal.Operation.INSERT, MaterialBucketItem.volume(stack), 0L,
						(requested, base) -> resolvedChemical == null
								? pourMaterialIntoCell(service, region, cell, resolvedMixture, 6, false, resolvedThermal)
								: pourChemicalIntoCell(service, region, cell, resolvedChemical, 6, false, resolvedThermal),
						(rawAfter, before) -> rawAfter);
				case MATERIAL_SCOOP -> ControllerItemTransferCoordinator.execute(service, region, level, actor, stack,
						ContainerTransferJournal.Operation.EXTRACT, extractionRequest, 0L,
						(requested, base) -> service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY) && region.domain().hasChemistryState()
								? scoopChemicalIntoCell(service, region, cell, requested, 6, service.chemicalRegistry())
								: scoopMaterialIntoCell(service, region, cell, requested, 6),
						(rawAfter, before) -> rawAfter);
				case GRANULAR_POUR -> ControllerItemTransferCoordinator.execute(service, region, level, actor, stack,
						ContainerTransferJournal.Operation.INSERT,
						ContainerTransferJournal.ResourceKind.GRANULAR_SVQ,
						resolvedGranular.totalSolidVolumeSvq(), 0L,
						(requested, base) -> pourGranularIntoCell(service, region, cell, resolvedGranular, 6, false),
						(rawAfter, before) -> rawAfter);
				case GRANULAR_SCOOP -> ControllerItemTransferCoordinator.execute(service, region, level, actor, stack,
						ContainerTransferJournal.Operation.EXTRACT,
						ContainerTransferJournal.ResourceKind.GRANULAR_SVQ,
						extractionRequest, 0L,
						(requested, base) -> scoopGranularIntoCell(service, region, cell, requested, 6),
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
			case POUR -> pourIntoCell(service, region, cell, heldVolumeNanos(stack), 6, 0, false, resolvedThermal);
			case SCOOP -> scoopFromCell(service, region, cell, extractionRequest, heldBaseVolumeNanos(stack), 6, 0,
					resolvedThermal);
			case MATERIAL_POUR -> resolvedChemical == null
					? pourMaterialIntoCell(service, region, cell, resolvedMixture, 6, false, resolvedThermal)
					: pourChemicalIntoCell(service, region, cell, resolvedChemical, 6, false, resolvedThermal);
			case MATERIAL_SCOOP -> service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY) && region.domain().hasChemistryState()
					? scoopChemicalIntoCell(service, region, cell, extractionRequest, 6, service.chemicalRegistry())
					: scoopMaterialIntoCell(service, region, cell, extractionRequest, 6);
			case GRANULAR_POUR, GRANULAR_SCOOP -> null;
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
		boolean externalSource = player.hasInfiniteMaterials();
		if (region.controllerManaged()) return pourController(service, region, player, hand, held, volume, cell);
		TransferOutcome outcome = pourIntoCell(service, region, cell, volume, 6, 0, externalSource,
				thermalContents(held));
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
		boolean hasChemicalContents = held.get(MmmComponents.CHEMICAL_CONTENTS) != null;
		if (hasChemicalContents && !service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY)) {
			return new TransferOutcome(false, true, held, "化学功能门本次启动未开启；化学桶保持不变");
		}
		ChemicalContents chemical = hasChemicalContents
				? MaterialBucketItem.chemicalContents(held, service.chemicalRegistry()) : null;
		FluidMixture mixture = chemical == null ? MaterialBucketItem.mixture(held) : null;
		if (mixture == null && chemical == null) {
			return new TransferOutcome(false, true, held, "化学桶内容损坏、双重表示或 registry 不匹配");
		}
		long volumeNanos = chemical == null ? mixture.totalVolumeNanos() : chemical.totalVolumeNanos();
		if (!service.worldCapabilityProfile().hasCapability(WorldCapability.MATERIALS)) {
			return new TransferOutcome(false, true, held, "材料功能门未开启");
		}
		if (!region.domain().hasMaterialState()) {
			return new TransferOutcome(false, true, held, "该区域不是材料区域");
		}
		if (chemical != null && (!service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY) || !region.domain().hasChemistryState())) {
			return new TransferOutcome(false, true, held, "化学桶只能倒入启用相同 registry 的化学区域");
		}
		boolean externalSource = player.hasInfiniteMaterials();
		ThermalContents thermal = thermalContents(held);
		if (region.controllerManaged()) {
			ContainerTransferActor actor = ContainerTransferActor.player(player.getUUID(), actorHand(hand));
			ControllerItemTransferCoordinator.Result transaction = ControllerItemTransferCoordinator.execute(
					service, region, player.level(), actor, held,
					ContainerTransferJournal.Operation.INSERT, volumeNanos, 0L,
					(requested, base) -> chemical == null ? pourMaterialIntoCell(service, region, cell, mixture, 6,
							externalSource, thermal)
							: pourChemicalIntoCell(service, region, cell, chemical, 6, externalSource, thermal),
					(rawAfter, before) -> player.hasInfiniteMaterials() ? before.copy() : rawAfter
			);
			if (!transaction.outcome().transferred()) return transaction.outcome();
			player.setItemInHand(hand, transaction.outcome().stack());
			player.getInventory().setChanged();
			service.sendSnapshotFor(player, region);
			return transaction.outcome();
		}
		TransferOutcome outcome = chemical == null ? pourMaterialIntoCell(service, region, cell, mixture, 6,
				externalSource, thermal)
				: pourChemicalIntoCell(service, region, cell, chemical, 6, externalSource, thermal);
		if (!outcome.transferred()) return new TransferOutcome(false, true, held, outcome.message());
		if (player.hasInfiniteMaterials()) {
			service.sendSnapshotFor(player, region);
			return new TransferOutcome(true, true, held.copy(), outcome.message());
		}
		service.sendSnapshotFor(player, region);
		return outcome;
	}

	private static TransferOutcome scoop(
			FluidSimulationService service, FluidRegion region, ServerPlayer player,
			InteractionHand hand, ItemStack held, int[] cell
	) {
		long capacity = heldFreeNanos(held);
		if (capacity <= 0L) return null;
		long requested = extractableNanos(region.domain(), cell, capacity, 6, 0);
		if (requested <= 0L) return new TransferOutcome(false, false, held, "准星处没有可装入的水体");
		if (region.controllerManaged()) {
			return scoopController(service, region, player, hand, held, requested, cell);
		}
		TransferOutcome outcome = scoopFromCell(service, region, cell, requested,
				heldBaseVolumeNanos(held), 6, 0, thermalContents(held));
		if (!outcome.transferred()) return new TransferOutcome(false, false, held, outcome.message());
		// Creative scoop still replaces the hand. Treating a pre-existing filled bucket as equivalent
		// would delete solver inventory through a hidden sink that the controller WAL cannot represent.
		service.sendSnapshotFor(player, region);
		return outcome;
	}

	private static TransferOutcome scoopMaterial(
			FluidSimulationService service, FluidRegion region, ServerPlayer player,
			InteractionHand hand, ItemStack held, int[] cell
	) {
		if (!service.worldCapabilityProfile().hasCapability(WorldCapability.MATERIALS)) {
			return new TransferOutcome(false, false, held, "材料功能门未开启");
		}
		if (!region.domain().hasMaterialState()) {
			return new TransferOutcome(false, false, held, "该区域不是材料区域");
		}
		long capacityNanos = ContainerFluidLedger.NANOS_PER_BLOCK;
		long requestedNanos = extractableNanos(region.domain(), cell, capacityNanos, 6, -1);
		if (requestedNanos <= 0L) {
			return new TransferOutcome(false, false, held, "准星处没有可装入的材料流体");
		}
		if (region.controllerManaged()) {
			ContainerTransferActor actor = ContainerTransferActor.player(player.getUUID(), actorHand(hand));
			ControllerItemTransferCoordinator.Result transaction = ControllerItemTransferCoordinator.execute(
					service, region, player.level(), actor, held,
					ContainerTransferJournal.Operation.EXTRACT, requestedNanos, 0L,
					(requested, base) -> service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY)
							&& region.domain().hasChemistryState()
							? scoopChemicalIntoCell(service, region, cell, requested, 6,
									service.chemicalRegistry())
							: scoopMaterialIntoCell(service, region, cell, requested, 6),
					(rawAfter, before) -> rawAfter
			);
			if (!transaction.outcome().transferred()) return transaction.outcome();
			player.setItemInHand(hand, transaction.outcome().stack());
			player.getInventory().setChanged();
			service.sendSnapshotFor(player, region);
			return transaction.outcome();
		}
		TransferOutcome outcome = service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY) && region.domain().hasChemistryState()
				? scoopChemicalIntoCell(service, region, cell, requestedNanos, 6, service.chemicalRegistry())
				: scoopMaterialIntoCell(service, region, cell, requestedNanos, 6);
		if (!outcome.transferred()) return new TransferOutcome(false, false, held, outcome.message());
		service.sendSnapshotFor(player, region);
		return outcome;
	}

	private static TransferOutcome pourGranular(
			FluidSimulationService service, FluidRegion region, ServerPlayer player, InteractionHand hand,
			ItemStack held, int[] cell
	) {
		if (!service.worldCapabilityProfile().hasCapability(WorldCapability.GRANULAR)) {
			return new TransferOutcome(false, true, held, "颗粒功能门本次启动未开启；干式容器保持不变");
		}
		if (!region.controllerManaged()) {
			return new TransferOutcome(false, true, held, "颗粒容器只允许在闭壳控制器区域转移");
		}
		if (!region.domain().hasGranularState()) {
			return new TransferOutcome(false, true, held, "该区域不是颗粒区域");
		}
		GranularContents contents;
		try {
			contents = ContainerItemStacks.granularContents(held, service.granularRegistry(),
					service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY) ? service.chemicalRegistry() : null);
		} catch (RuntimeException invalid) {
			return new TransferOutcome(false, true, held, "颗粒容器内容损坏、双重表示或 registry 不匹配");
		}
		if (contents == null) return new TransferOutcome(false, true, held, "颗粒容器没有有效库存");
		ContainerTransferActor actor = ContainerTransferActor.player(player.getUUID(), actorHand(hand));
		ControllerItemTransferCoordinator.Result transaction = ControllerItemTransferCoordinator.execute(
				service, region, player.level(), actor, held,
				ContainerTransferJournal.Operation.INSERT, ContainerTransferJournal.ResourceKind.GRANULAR_SVQ,
				contents.totalSolidVolumeSvq(), 0L,
				(requested, base) -> pourGranularIntoCell(service, region, cell, contents, 6,
						player.hasInfiniteMaterials()),
				(rawAfter, before) -> player.hasInfiniteMaterials() ? before.copy() : rawAfter);
		if (!transaction.outcome().transferred()) return transaction.outcome();
		player.setItemInHand(hand, transaction.outcome().stack());
		player.getInventory().setChanged();
		service.sendSnapshotFor(player, region);
		return transaction.outcome();
	}

	private static TransferOutcome scoopGranular(
			FluidSimulationService service, FluidRegion region, ServerPlayer player, InteractionHand hand,
			ItemStack held, int[] cell
	) {
		if (!service.worldCapabilityProfile().hasCapability(WorldCapability.GRANULAR)) {
			return new TransferOutcome(false, false, held, "颗粒功能门本次启动未开启；干式容器保持不变");
		}
		if (!region.controllerManaged()) {
			return new TransferOutcome(false, false, held, "颗粒容器只允许在闭壳控制器区域转移");
		}
		if (!region.domain().hasGranularState() || !GranularContainerItem.isEmpty(held)) {
			return new TransferOutcome(false, false, held, "干式容器不为空或该区域不是颗粒区域");
		}
		ContainerTransferActor actor = ContainerTransferActor.player(player.getUUID(), actorHand(hand));
		ControllerItemTransferCoordinator.Result transaction = ControllerItemTransferCoordinator.execute(
				service, region, player.level(), actor, held,
				ContainerTransferJournal.Operation.EXTRACT, ContainerTransferJournal.ResourceKind.GRANULAR_SVQ,
				SolidVolume.SVQ_PER_BV, 0L,
				(requested, base) -> scoopGranularIntoCell(service, region, cell, requested, 6),
				(rawAfter, before) -> rawAfter);
		if (!transaction.outcome().transferred()) return transaction.outcome();
		player.setItemInHand(hand, transaction.outcome().stack());
		player.getInventory().setChanged();
		service.sendSnapshotFor(player, region);
		return transaction.outcome();
	}

	private static TransferOutcome pourController(
			FluidSimulationService service, FluidRegion region, ServerPlayer player, InteractionHand hand, ItemStack held,
			long volumeNanos, int[] cell
	) {
		ContainerTransferActor actor = ContainerTransferActor.player(player.getUUID(), actorHand(hand));
		ControllerItemTransferCoordinator.Result transaction = ControllerItemTransferCoordinator.execute(
				service, region, player.level(), actor, held,
				ContainerTransferJournal.Operation.INSERT, volumeNanos, 0L,
				(requested, base) -> pourIntoCell(service, region, cell, requested, 6, 0,
						player.hasInfiniteMaterials(), thermalContents(held)),
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
		ContainerTransferActor actor = ContainerTransferActor.player(player.getUUID(), actorHand(hand));
		ControllerItemTransferCoordinator.Result transaction = ControllerItemTransferCoordinator.execute(
				service, region, player.level(), actor, held,
				ContainerTransferJournal.Operation.EXTRACT, capacityNanos, heldBaseVolumeNanos(held),
				(requested, base) -> scoopFromCell(service, region, cell, requested, base, 6, 0,
						thermalContents(held)),
				(rawAfter, before) -> rawAfter
		);
		if (!transaction.outcome().transferred()) return transaction.outcome();
		player.setItemInHand(hand, transaction.outcome().stack());
		player.getInventory().setChanged();
		service.sendSnapshotFor(player, region);
		return transaction.outcome();
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

	private static TransferOutcome pourIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long volumeNanos, int maxRadius,
			int materialHandle, boolean externalSource, ThermalContents thermalContents
	) {
		if (volumeNanos <= 0L) return null;
		float requestedCellVolume = nanosToCellVolumeAtMost(volumeNanos);
		if (requestedCellVolume <= 0.0F) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "容器余量小于 solver 可表示体积，未能注入");
		}
		FlipFluidDomain domain = region.domain();
		if (domain.totalOpenVolume() - domain.totalVolume() + 1.0E-6F < requestedCellVolume) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "空间或 marker 预算不足，未能注入");
		}
		long plannedNanos = cellVolumeToNanos(requestedCellVolume);
		ThermalContents.EnergySplit thermalSplit = thermalContents == null ? null
				: thermalContents.splitEnergy(plannedNanos, volumeNanos);
		Double coupledEnergy = thermalSplit == null ? null
				: thermalContents.internalEnergy() - thermalSplit.remainingEnergy();
		FlipFluidDomain.ExactVolumeTransfer inserted = thermalSplit == null
				? domain.tryInsertExactVolume(cell[0], cell[1], cell[2], requestedCellVolume, maxRadius,
						materialHandle, externalSource)
				: domain.tryInsertExactVolume(cell[0], cell[1], cell[2], requestedCellVolume, maxRadius,
						materialHandle, externalSource, coupledEnergy);
		if (!inserted.transferred()) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "空间或 marker 预算不足，未能注入");
		}
		long poured = settledNanos(inserted.volume(), volumeNanos);
		if (poured != plannedNanos) throw new IllegalStateException("Thermal insertion settlement changed after planning");
		ItemStack after = withVolume(volumeNanos - poured);
		if (thermalSplit != null && volumeNanos > poured) ContainerItemStacks.attachThermal(
				after, service.thermalRegistryFingerprint(), thermalSplit.remainingEnergy());
		region.wakeForActivity();
		if (thermalSplit != null) region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		return new TransferOutcome(true, true, after,
				String.format("已注入 %.2f%% 桶", 100.0 * poured / ContainerFluidLedger.NANOS_PER_BLOCK), poured,
				coupledEnergy);
	}

	private static TransferOutcome scoopFromCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long capacityNanos, long baseVolumeNanos,
			int maxRadius, int materialHandle, ThermalContents baseThermal
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
		ItemStack after = withVolume(baseVolumeNanos + taken);
		Double thermalEnergy = null;
		if (region.domain().hasThermalState()) {
			double baseEnergy = baseThermal == null ? 0.0 : baseThermal.internalEnergy();
			double finalEnergy = baseEnergy + extracted.internalEnergy();
			ContainerItemStacks.attachThermal(after, service.thermalRegistryFingerprint(), finalEnergy);
			thermalEnergy = finalEnergy - baseEnergy;
			region.domain().settleExternalContainerExtraction(extracted.internalEnergy(), thermalEnergy);
		}
		region.wakeForActivity();
		if (thermalEnergy != null) region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		return new TransferOutcome(true, false, after,
				String.format("已舀取 %.2f%% 桶", 100.0 * taken / ContainerFluidLedger.NANOS_PER_BLOCK), taken,
				thermalEnergy);
	}


	private static TransferOutcome pourMaterialIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, FluidMixture mixture, int maxRadius,
			boolean externalSource, ThermalContents thermalContents
	) {
		if (mixture == null) return null;
		FlipFluidDomain domain = region.domain();
		java.util.List<FlipFluidDomain.MaterialPortion> portions;
		try {
			portions = planMaterialInsertion(domain, mixture);
		} catch (IllegalArgumentException unknownMaterial) {
			return new TransferOutcome(false, true, ContainerItemStacks.toStack(mixture),
					"区域材料 registry 不接受该混合物：" + unknownMaterial.getMessage());
		}
		long plannedNanos = 0L;
		for (int index = 0; index < portions.size(); index++) plannedNanos = Math.addExact(plannedNanos,
				settledNanos(portions.get(index).volume(), mixture.components().get(index).volumeNanos()));
		ThermalContents.EnergySplit thermalSplit = thermalContents == null ? null
				: thermalContents.splitEnergy(plannedNanos, mixture.totalVolumeNanos());
		Double coupledEnergy = thermalSplit == null ? null
				: thermalContents.internalEnergy() - thermalSplit.remainingEnergy();
		FlipFluidDomain.ExactMixtureTransfer inserted = thermalSplit == null
				? domain.tryInsertExactMixture(cell[0], cell[1], cell[2], portions, maxRadius, externalSource)
				: domain.tryInsertExactMixture(cell[0], cell[1], cell[2], portions, maxRadius, externalSource,
						coupledEnergy);
		if (!inserted.transferred()) {
			return new TransferOutcome(false, true, ContainerItemStacks.toStack(mixture), "空间或 marker 预算不足，未能注入混合物");
		}
		MaterialInsertionSettlement settlement = settleMaterialInsertion(domain, mixture, inserted.portions());
		if (settlement.insertedNanos() != plannedNanos) {
			throw new IllegalStateException("Thermal material settlement changed after planning");
		}
		ItemStack after = settlement.remaining() == null
				? new ItemStack(MmmItems.MATERIAL_BUCKET)
				: ContainerItemStacks.toStack(settlement.remaining());
		if (thermalSplit != null && settlement.remaining() != null) ContainerItemStacks.attachThermal(
				after, service.thermalRegistryFingerprint(), thermalSplit.remainingEnergy());
		region.wakeForActivity();
		if (thermalSplit != null) region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		return new TransferOutcome(true, true, after,
				String.format("已注入 %.2f%% 桶（%d 种材料）",
						100.0 * settlement.insertedNanos() / ContainerFluidLedger.NANOS_PER_BLOCK,
						mixture.components().size()), settlement.insertedNanos(),
				coupledEnergy);
	}

	private static TransferOutcome pourChemicalIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, ChemicalContents contents, int maxRadius,
			boolean externalSource, ThermalContents thermalContents
	) {
		FlipFluidDomain domain = region.domain();
		if (!domain.hasChemistryState()) return new TransferOutcome(false, true, ContainerItemStacks.toStack(contents), "该区域不是化学区域");
		try {
			contents.validateAgainst(domain.chemicalParcels().registry());
		} catch (IllegalArgumentException invalid) {
			return new TransferOutcome(false, true, ContainerItemStacks.toStack(contents), "化学桶 registry 或账本不合法：" + invalid.getMessage());
		}
		double spatialCellVolume = Math.max(0.0, domain.totalOpenVolumeExact() - domain.totalVolumeExact());
		double markerCellVolume = Math.max(0, domain.maximumParticleCount() - domain.particleCount()) * 0.125;
		long candidate = Math.min(contents.totalVolumeNanos(), Math.min(
				cellVolumeToNanos(spatialCellVolume), cellVolumeToNanos(markerCellVolume)));
		while (candidate > 0L) {
			ChemicalContents.Split split;
			try {
				split = contents.split(candidate, domain.chemicalParcels().registry());
			} catch (IllegalArgumentException belowResolution) {
				candidate /= 2L;
				continue;
			}
			cc.ecisr.mmm.chemistry.ChemicalTransferLedger requested = split.inserted().toLedger(
					domain.chemicalParcels().registry(), FluidSimulationService.CELL_VOLUMES_PER_BLOCK);
			ThermalContents.EnergySplit thermalSplit = thermalContents == null ? null
					: thermalContents.splitEnergy(candidate, contents.totalVolumeNanos());
			Double coupledEnergy = thermalSplit == null ? null
					: thermalContents.internalEnergy() - thermalSplit.remainingEnergy();
			FlipFluidDomain.ExactChemicalTransfer inserted = thermalSplit == null
					? domain.tryInsertExactChemical(cell[0], cell[1], cell[2], requested, maxRadius, externalSource)
					: domain.tryInsertExactChemical(cell[0], cell[1], cell[2], requested, maxRadius,
							externalSource, coupledEnergy);
			if (!inserted.transferred()) {
				candidate /= 2L;
				continue;
			}
			region.wakeForActivity();
			ItemStack after = split.remaining() == null
					? new ItemStack(MmmItems.MATERIAL_BUCKET) : ContainerItemStacks.toStack(split.remaining());
			if (thermalSplit != null && split.remaining() != null) ContainerItemStacks.attachThermal(
					after, service.thermalRegistryFingerprint(), thermalSplit.remainingEnergy());
			if (thermalSplit != null) region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
			return new TransferOutcome(true, true, after,
					String.format("已注入 %.2f%% 桶化学流体", 100.0 * candidate
							/ ContainerFluidLedger.NANOS_PER_BLOCK), candidate,
					coupledEnergy);
		}
		return new TransferOutcome(false, true, ContainerItemStacks.toStack(contents), "空间、marker 或化学预算不足，未能注入");
	}

	private static TransferOutcome scoopChemicalIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long capacityNanos, int maxRadius,
			cc.ecisr.mmm.chemistry.ChemicalRegistry registry
	) {
		if (capacityNanos <= 0L) return null;
		float requested = nanosToCellVolumeAtMost(capacityNanos);
		if (requested <= 0.0F) return new TransferOutcome(false, false,
				new ItemStack(MmmItems.MATERIAL_BUCKET), "容器余量小于 solver 可表示体积");
		ChemicalContents[] plannedContents = new ChemicalContents[1];
		long[] plannedNanos = new long[1];
		FlipFluidDomain.ExactChemicalTransfer extracted = region.domain().tryExtractExactChemical(
				cell[0], cell[1], cell[2], requested, maxRadius, ledger -> {
					try {
						plannedNanos[0] = settledNanos(ledger.totalVolume(), capacityNanos);
						plannedContents[0] = ChemicalContents.fromLedger(ledger, registry, plannedNanos[0]);
						return true;
					} catch (IllegalArgumentException | ArithmeticException invalidBucket) {
						return false;
					}
				});
		if (!extracted.transferred() || extracted.ledger().isEmpty()) return new TransferOutcome(false, false,
				new ItemStack(MmmItems.MATERIAL_BUCKET), "准星处没有足够或可由当前容器表示的化学流体");
		long taken = plannedNanos[0];
		ChemicalContents contents = plannedContents[0];
		Double thermalEnergy = region.domain().hasThermalState() ? extracted.internalEnergy() : null;
		ItemStack after = ContainerItemStacks.toStack(contents);
		if (thermalEnergy != null) ContainerItemStacks.attachThermal(after, service.thermalRegistryFingerprint(), thermalEnergy);
		region.wakeForActivity();
		if (thermalEnergy != null) region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		return new TransferOutcome(true, false, after,
				String.format("已舀取 %.2f%% 桶化学流体（%d 组）", 100.0 * taken
						/ ContainerFluidLedger.NANOS_PER_BLOCK, contents.groups().size()), taken, thermalEnergy);
	}

	private static TransferOutcome scoopMaterialIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell,
			long capacityNanos, int maxRadius
	) {
		if (capacityNanos <= 0L) return null;
		FlipFluidDomain domain = region.domain();
		float requestedCellVolume = nanosToCellVolumeAtMost(capacityNanos);
		if (requestedCellVolume <= 0.0F) {
			return new TransferOutcome(false, false, new ItemStack(MmmItems.MATERIAL_BUCKET),
					"容器余量小于 solver 可表示体积");
		}
		FlipFluidDomain.ExactMixtureTransfer extracted = domain.tryExtractExactMixture(
				cell[0], cell[1], cell[2], requestedCellVolume, maxRadius);
		if (!extracted.transferred() || extracted.portions().isEmpty()) {
			return new TransferOutcome(false, false, new ItemStack(MmmItems.MATERIAL_BUCKET),
					"准星处没有足够流体");
		}
		FluidMixture mixture = mixtureFromExtraction(domain, extracted.portions(), capacityNanos);
		long taken = mixture.totalVolumeNanos();
		Double thermalEnergy = domain.hasThermalState() ? extracted.internalEnergy() : null;
		ItemStack after = ContainerItemStacks.toStack(mixture);
		if (thermalEnergy != null) {
			ContainerItemStacks.attachThermal(after, service.thermalRegistryFingerprint(), thermalEnergy);
		}
		region.wakeForActivity();
		if (thermalEnergy != null) {
			region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		}
		return new TransferOutcome(true, false, after,
				String.format("已舀取 %.2f%% 桶（%d 种材料）",
						100.0 * taken / ContainerFluidLedger.NANOS_PER_BLOCK,
						mixture.components().size()), taken, thermalEnergy);
	}

	private static TransferOutcome pourGranularIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, GranularContents contents,
			int maxRadius, boolean externalSource
	) {
		GranularTransferLedger ledger;
		try {
			ledger = contents.toLedger(service.granularRegistry(),
					service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY) ? service.chemicalRegistry() : null);
		} catch (RuntimeException invalid) {
			return new TransferOutcome(false, true, ContainerItemStacks.toStack(contents),
					"颗粒容器 registry 或账本不合法：" + invalid.getMessage());
		}
		FlipFluidDomain.GranularInsertion inserted = region.domain().tryInsertExactGranular(
				cell[0], cell[1], cell[2], ledger, maxRadius, externalSource);
		if (!inserted.inserted()) {
			return new TransferOutcome(false, true, ContainerItemStacks.toStack(contents),
					"空间、颗粒 parcel 或组成预算不足，未能注入：" + inserted.failure());
		}
		if (inserted.solidVolumeSvq() != contents.totalSolidVolumeSvq()
				|| Double.doubleToRawLongBits(inserted.mass()) != Double.doubleToRawLongBits(contents.totalMass())) {
			throw new IllegalStateException("颗粒注入没有结算完整独立库存");
		}
		region.wakeForActivity();
		return new TransferOutcome(true, true, new ItemStack(MmmItems.GRANULAR_CONTAINER),
				String.format("已注入 %.2f%% 干式容器（%d 种材料）",
						100.0 * inserted.solidVolumeSvq() / SolidVolume.SVQ_PER_BV, contents.groups().size()),
				inserted.solidVolumeSvq());
	}

	private static TransferOutcome scoopGranularIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long capacitySvq, int maxRadius
	) {
		GranularContents[] planned = new GranularContents[1];
		FlipFluidDomain.GranularExtraction extracted = region.domain().tryExtractExactGranular(
				cell[0], cell[1], cell[2], capacitySvq, maxRadius, false, ledger -> {
					try {
						planned[0] = GranularContents.fromLedger(ledger, service.granularRegistry(),
								service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY) ? service.chemicalRegistry() : null);
						return true;
					} catch (RuntimeException unrepresentable) {
						return false;
					}
				});
		if (!extracted.transferred() || planned[0] == null) {
			return new TransferOutcome(false, false, new ItemStack(MmmItems.GRANULAR_CONTAINER),
					"准星处没有可访问且能由当前干式容器表示的颗粒库存");
		}
		long taken = extracted.ledger().totalSolidVolumeSvq();
		if (taken != planned[0].totalSolidVolumeSvq()) throw new IllegalStateException("颗粒舀取 SVQ 账本不一致");
		region.wakeForActivity();
		return new TransferOutcome(true, false, ContainerItemStacks.toStack(planned[0]),
				String.format("已舀取 %.2f%% 干式容器（%d 种材料）",
						100.0 * taken / SolidVolume.SVQ_PER_BV, planned[0].groups().size()), taken);
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
			components.add(new FluidMixture.Component(
					materialId, nanos, domain.physicalMassKilograms(portion.mass())));
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
			double plannedMassKilograms = plannedNanos == component.volumeNanos()
					? component.mass()
					: component.mass() * plannedNanos / component.volumeNanos();
			portions.add(new FlipFluidDomain.MaterialPortion(
					handle, plannedVolume, domain.solverMassForKilograms(plannedMassKilograms)));
		}
		return java.util.List.copyOf(portions);
	}

	static MaterialInsertionSettlement settleMaterialInsertion(
			FlipFluidDomain domain, FluidMixture sourceMixture,
			java.util.List<FlipFluidDomain.MaterialPortion> insertedPortions
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
			double actualMassKilograms = domain.physicalMassKilograms(actual.mass());
			double remainingMass = source.mass() - actualMassKilograms;
			if (remainingNanos == 0L) {
				if (Double.doubleToRawLongBits(actualMassKilograms) != source.massBits()) {
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
		MATERIAL_SCOOP,
		GRANULAR_POUR,
		GRANULAR_SCOOP
	}

	private static String thermalTransferFailure(
			FluidSimulationService service, FluidRegion region, ItemStack stack, TransferKind kind
	) {
		if (kind == TransferKind.NONE || kind == TransferKind.GRANULAR_POUR
				|| kind == TransferKind.GRANULAR_SCOOP) return null;
		ThermalContents contents;
		try {
			contents = ContainerItemStacks.thermalContents(stack);
		} catch (RuntimeException invalid) {
			return "容器热状态损坏或与物质库存不一致；转移保持不变";
		}
		if (!region.domain().hasThermalState()) {
			return contents == null ? null : "带热真值的容器不能转入未启用热状态的区域";
		}
		boolean sourceRequired = kind == TransferKind.POUR || kind == TransferKind.MATERIAL_POUR
				|| kind == TransferKind.SCOOP && heldBaseVolumeNanos(stack) > 0L;
		if (sourceRequired && contents == null) {
			return "热区域只接受携带 ThermalContents 的液体容器；拒绝猜测初始温度";
		}
		if (contents != null) try {
			contents.validateRegistryFingerprint(service.thermalRegistryFingerprint());
		} catch (RuntimeException foreign) {
			return "容器 thermal registry 与当前世界不一致；转移保持不变";
		}
		return null;
	}

	private static ThermalContents thermalContents(ItemStack stack) {
		return ContainerItemStacks.thermalContents(stack);
	}

	static double coupledSplitEnergy(ThermalContents contents, long selectedNanos, long totalNanos) {
		ThermalContents.EnergySplit split = contents.splitEnergy(selectedNanos, totalNanos);
		return contents.internalEnergy() - split.remainingEnergy();
	}

	private static TransferKind classify(ItemStack stack) {
		if (stack.is(Items.WATER_BUCKET)) return TransferKind.POUR;
		if (stack.is(Items.BUCKET)) return TransferKind.SCOOP;
		if (stack.getItem() instanceof PartialWaterBucketItem) {
			return PartialWaterBucketItem.volume(stack) > 0L ? TransferKind.POUR : TransferKind.NONE;
		}
		if (stack.getItem() instanceof MaterialBucketItem) {
			if (MaterialBucketItem.isEmptyMaterialBucket(stack)) return TransferKind.MATERIAL_SCOOP;
			return (stack.get(MmmComponents.CHEMICAL_CONTENTS) != null || MaterialBucketItem.mixture(stack) != null)
					? TransferKind.MATERIAL_POUR
					: TransferKind.NONE;
		}
		if (stack.getItem() instanceof GranularContainerItem) {
			if (GranularContainerItem.isEmpty(stack)) return TransferKind.GRANULAR_SCOOP;
			return GranularContainerItem.unvalidatedContents(stack) == null
					? TransferKind.NONE : TransferKind.GRANULAR_POUR;
		}
		return TransferKind.NONE;
	}

	private static Long heldVolumeNanos(ItemStack stack) {
		if (stack.is(Items.WATER_BUCKET)) return ContainerFluidLedger.NANOS_PER_BLOCK;
		long volume = PartialWaterBucketItem.volume(stack);
		return volume > 0L ? volume : null;
	}

	static long liquidVolumeNanos(ItemStack stack) {
		Long pure = heldVolumeNanos(stack);
		if (pure != null) return pure;
		if (stack.getItem() instanceof MaterialBucketItem) {
			long material = MaterialBucketItem.volume(stack);
			if (material > 0L) return material;
		}
		throw new IllegalArgumentException("Thermal source item has no liquid volume ledger");
	}

	private static ContainerTransferActor.Hand actorHand(InteractionHand hand) {
		return hand == InteractionHand.MAIN_HAND
				? ContainerTransferActor.Hand.MAIN : ContainerTransferActor.Hand.OFF;
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

	private static BlockHitResult playerBlockHit(ServerPlayer player) {
		HitResult hit = player.pick(5.0, 1.0F, false);
		return hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK ? blockHit : null;
	}

	private static BlockPos playerPourTargetBlock(ServerPlayer player, BlockHitResult hit) {
		if (hit == null) return null;
		BlockPos hitPos = hit.getBlockPos();
		if (MmmBlocks.isBoundary(player.level().getBlockState(hitPos))) return hitPos;
		return player.level().getBlockState(hitPos).canBeReplaced()
				? hitPos
				: hitPos.relative(hit.getDirection());
	}

	record TransferOutcome(
			boolean transferred, boolean filled, ItemStack stack, String message, long resourceAmount, Double thermalEnergy
	) {
		TransferOutcome(boolean transferred, boolean filled, ItemStack stack, String message) {
			this(transferred, filled, stack, message, 0L, null);
		}
		TransferOutcome(boolean transferred, boolean filled, ItemStack stack, String message, long resourceAmount) {
			this(transferred, filled, stack, message, resourceAmount, null);
		}
	}
}
