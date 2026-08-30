package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.transfer.ContainerTransferActor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.dispenser.BlockSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DispenserBlock;

/** Minecraft dispenser target/slot/output adapter for the shared container transaction path. */
public final class DispenserContainerTransferAdapter {
	private DispenserContainerTransferAdapter() { }

	public static ItemStack handle(BlockSource source, ItemStack stack) {
		FluidSimulationService service = MmmRuntimeRegistry.require(source.level().getServer()).simulation();
		boolean vanillaItem = stack.is(Items.WATER_BUCKET) || stack.is(Items.BUCKET);
		if (!service.authoritativeWritesReady()) return vanillaItem ? null : stack;
		MinecraftContainerTransferOperations.TransferKind kind =
				MinecraftContainerTransferOperations.classify(stack);
		if (kind == MinecraftContainerTransferOperations.TransferKind.NONE) return stack;
		if ((kind == MinecraftContainerTransferOperations.TransferKind.MATERIAL_POUR
				|| kind == MinecraftContainerTransferOperations.TransferKind.GRANULAR_POUR)
				&& !service.acceptsActualContainerProfile(stack)) return stack;

		ServerLevel level = source.level();
		var facing = source.state().getValue(DispenserBlock.FACING);
		BlockPos target = source.pos().relative(facing);
		FluidSimulationService.TransferTarget transferTarget = service.transferTargetAtBlock(level, target, facing);
		FluidRegion region = transferTarget == null ? null : transferTarget.region();
		if (region == null) return vanillaItem ? null : stack;
		if (stack.getCount() != 1 || !service.ensureRegionAvailable(level, region)) return stack;
		if (MinecraftContainerTransferOperations.thermalTransferFailure(service, region, stack, kind) != null) {
			return stack;
		}
		int[] cell = service.cellAtBlock(level, region, transferTarget.interiorBlock());
		if (cell == null) return stack;

		MinecraftContainerTransferPlan.Resolution resolution = MinecraftContainerTransferPlan.resolve(
				service, region, cell, stack, false);
		if (!resolution.resolved()) return stack;
		MinecraftContainerTransferPlan plan = resolution.plan();

		if (!region.controllerManaged()) {
			MinecraftContainerTransferOperations.TransferOutcome outcome = plan.executeUnmanaged();
			if (outcome == null || !outcome.transferred()) return stack;
			service.sendSnapshot(level, region);
			return service.bindActualContainerProfile(outcome.stack());
		}

		int slot = findSlot(source, stack);
		if (slot < 0) return stack;
		BlockPos actorPosition = source.pos();
		ContainerTransferActor actor = ContainerTransferActor.dispenser(
				level.dimension().identifier().toString(), actorPosition.getX(), actorPosition.getY(),
				actorPosition.getZ(), slot);
		MinecraftContainerTransactionAdapter.Result transaction = plan.executeManaged(
				level, actor, stack, false);
		if (transaction == null || !transaction.outcome().transferred()) return stack;
		source.blockEntity().setItem(slot, transaction.outcome().stack());
		source.blockEntity().setChanged();
		service.containerTransactions().completeCommitted(transaction.transactionId());
		service.sendSnapshot(level, region);
		return service.bindActualContainerProfile(transaction.outcome().stack());
	}

	private static int findSlot(BlockSource source, ItemStack stack) {
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

}
