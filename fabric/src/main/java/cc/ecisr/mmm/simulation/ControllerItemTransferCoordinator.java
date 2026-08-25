package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.item.ContainerItemStacks;
import cc.ecisr.mmm.simulation.persistence.GenerationSequence;
import cc.ecisr.mmm.transfer.ThermalContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.UUID;

/**
 * Commits one controller-region mutation together with a player-hand or dispenser item.
 * Region HEAD is the commit decision; the durable WAL retains the authoritative after-item until
 * Minecraft has saved its actor. Numerical transfer primitives remain supplied by the caller.
 */
final class ControllerItemTransferCoordinator {
	private ControllerItemTransferCoordinator() { }

	static Result execute(
			FluidSimulationService service, FluidRegion region, ServerLevel level,
			ContainerTransferJournal.Actor actor, ItemStack beforeStack,
			ContainerTransferJournal.Operation operation, long requestedNanos, long baseVolumeNanos,
			TransferExecutor executor, FinalItemResolver finalItemResolver
	) {
		return execute(service, region, level, actor, beforeStack, operation,
				ContainerTransferJournal.ResourceKind.FLUID_NANOS, requestedNanos, baseVolumeNanos,
				executor, finalItemResolver);
	}

	static Result execute(
			FluidSimulationService service, FluidRegion region, ServerLevel level,
			ContainerTransferJournal.Actor actor, ItemStack beforeStack,
			ContainerTransferJournal.Operation operation, ContainerTransferJournal.ResourceKind resourceKind,
			long requestedAmount, long baseAmount,
			TransferExecutor executor, FinalItemResolver finalItemResolver
	) {
		if (service.hasPendingContainerTransfer(region.id())) {
			return new Result(new FluidTransferService.TransferOutcome(false,
					operation == ContainerTransferJournal.Operation.INSERT, beforeStack,
					"区域存在未完成容器转移 WAL，已锁定"), null);
		}
		if (requestedAmount <= 0L) return new Result(null, null);
		try {
			service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.BEFORE_PREPARE);
		} catch (RuntimeException injected) {
			return failed(operation, beforeStack, injected.getMessage(), null);
		}

		long baseGeneration = region.checkpointGeneration();
		long targetGeneration;
		try {
			targetGeneration = GenerationSequence.next(baseGeneration, "Container transfer checkpoint");
		} catch (IOException exhausted) {
			service.lockContainerTransferRegion(region, exhausted.getMessage());
			return failed(operation, beforeStack, exhausted.getMessage(), null);
		}
		UUID transactionId = UUID.randomUUID();
		ContainerTransferJournal.ItemFingerprint beforeFingerprint;
		try {
			beforeFingerprint = ContainerTransferItems.fingerprint(beforeStack);
		} catch (IllegalArgumentException invalidItem) {
			return failed(operation, beforeStack, invalidItem.getMessage(), null);
		}

		Double preparedThermalEnergy = null;
		if (resourceKind == ContainerTransferJournal.ResourceKind.FLUID_NANOS
				&& region.domain().hasThermalState()) {
			preparedThermalEnergy = 0.0;
			if (operation == ContainerTransferJournal.Operation.INSERT) {
				ThermalContents source = ContainerItemStacks.thermalContents(beforeStack);
				if (source == null) return failed(operation, beforeStack,
						"热区域注入缺少 ThermalContents；拒绝发布不完整 WAL", null);
				preparedThermalEnergy = FluidTransferService.coupledSplitEnergy(source, requestedAmount,
						FluidTransferService.liquidVolumeNanos(beforeStack));
			}
		}

		ContainerTransferJournal prepared = journal(transactionId, region, level, baseGeneration,
				targetGeneration, operation, resourceKind, requestedAmount, actor, beforeFingerprint,
				beforeFingerprint, preparedThermalEnergy,
				preparedThermalEnergy == null ? null : service.thermalRegistryFingerprint(), -1L,
				ContainerTransferJournal.Phase.PREPARED);
		try {
			service.writeContainerTransfer(prepared);
		} catch (IOException exception) {
			return new Result(new FluidTransferService.TransferOutcome(false,
					operation == ContainerTransferJournal.Operation.INSERT, beforeStack,
					"容器转移 WAL 发布失败：" + exception.getMessage()), null);
		}
		try {
			service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_PREPARE);
		} catch (RuntimeException injected) {
			return failed(operation, beforeStack, injected.getMessage(), transactionId);
		}

		service.markContainerTransferSolverDirty(transactionId);
		FluidTransferService.TransferOutcome rawOutcome;
		try {
			rawOutcome = executor.execute(requestedAmount, baseAmount);
		} catch (RuntimeException transferFailure) {
			String failure = "容器转移执行异常，区域已锁定；重启后从 base checkpoint 恢复："
					+ transferFailure.getMessage();
			service.lockContainerTransferRegion(region, failure);
			return failed(operation, beforeStack, failure, transactionId);
		}
		if (rawOutcome == null || !rawOutcome.transferred()) {
			service.markContainerTransferSolverClean(transactionId);
			deleteQuietly(service, transactionId);
			return new Result(rawOutcome == null
					? new FluidTransferService.TransferOutcome(false,
							operation == ContainerTransferJournal.Operation.INSERT, beforeStack, "转移未执行")
					: rawOutcome, null);
		}
		boolean thermalTransfer = resourceKind == ContainerTransferJournal.ResourceKind.FLUID_NANOS
				&& region.domain().hasThermalState();
		if (thermalTransfer != (rawOutcome.thermalEnergy() != null)) {
			String failure = "容器转移 solver 未返回与区域热状态一致的 coupled energy；区域已锁定";
			service.lockContainerTransferRegion(region, failure);
			return failed(operation, beforeStack, failure, transactionId);
		}
		try {
			service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_SOLVER);
		} catch (RuntimeException injected) {
			String failure = injected.getMessage() + "；区域已锁定，重启后从 base checkpoint 恢复";
			service.lockContainerTransferRegion(region, failure);
			return failed(operation, beforeStack, failure, transactionId);
		}

		ItemStack finalAfter;
		ContainerTransferJournal.ItemFingerprint afterFingerprint;
		try {
			finalAfter = finalItemResolver.resolve(rawOutcome.stack(), beforeStack);
			afterFingerprint = ContainerTransferItems.fingerprint(finalAfter);
		} catch (RuntimeException invalidAfterItem) {
			region.setPaused(true);
			region.setAvailabilityFailure("容器转移 after-item 无法写入 WAL，区域已锁定；重启后恢复："
					+ invalidAfterItem.getMessage());
			return failed(operation, beforeStack, region.availabilityFailure(), transactionId);
		}

		FluidRegionPersistence.StagedCheckpoint staged;
		try {
			staged = service.stageControllerRegionCheckpoint(region, targetGeneration);
		} catch (IOException | RuntimeException stagingFailure) {
			region.setPaused(true);
			region.setAvailabilityFailure("容器转移 checkpoint staging 失败，区域已锁定；重启后恢复："
					+ stagingFailure.getMessage());
			return failed(operation, beforeStack, region.availabilityFailure(), transactionId);
		}
		try {
			service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_STAGE);
		} catch (RuntimeException injected) {
			String failure = injected.getMessage() + "；staged generation 尚未发布，重启后从 base checkpoint 恢复";
			service.lockContainerTransferRegion(region, failure);
			return failed(operation, beforeStack, failure, transactionId);
		}

		ContainerTransferJournal committed = journal(transactionId, region, level, baseGeneration,
				targetGeneration, operation, resourceKind, rawOutcome.resourceAmount(), actor,
				beforeFingerprint, afterFingerprint, rawOutcome.thermalEnergy(),
				rawOutcome.thermalEnergy() == null ? null : service.thermalRegistryFingerprint(),
				staged.manifestChecksum(), ContainerTransferJournal.Phase.REGION_COMMITTED);
		try {
			service.writeContainerTransfer(committed);
		} catch (IOException exception) {
			try {
				service.abortControllerRegionCheckpoint(staged);
			} catch (IOException abortFailure) {
				exception.addSuppressed(abortFailure);
			}
			region.setPaused(true);
			region.setAvailabilityFailure("容器转移 WAL 更新失败，区域已锁定；重启后恢复："
					+ exception.getMessage());
			return new Result(new FluidTransferService.TransferOutcome(false,
					operation == ContainerTransferJournal.Operation.INSERT, beforeStack,
					region.availabilityFailure()), null);
		}
		try {
			service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_WAL);
		} catch (RuntimeException injected) {
			String failure = injected.getMessage() + "；区域已锁定，重启后恢复";
			service.lockContainerTransferRegion(region, failure);
			return failed(operation, beforeStack, failure, transactionId);
		}

		long publishedGeneration;
		try {
			publishedGeneration = service.commitControllerRegionCheckpoint(region, staged);
		} catch (IOException | RuntimeException exception) {
			region.setPaused(true);
			region.setAvailabilityFailure("容器转移检查点失败，区域已锁定；重启后恢复："
					+ exception.getMessage());
			return new Result(new FluidTransferService.TransferOutcome(false,
					operation == ContainerTransferJournal.Operation.INSERT, beforeStack,
					region.availabilityFailure()), transactionId);
		}
		if (publishedGeneration != targetGeneration) {
			region.setPaused(true);
			region.setAvailabilityFailure("容器转移检查点代次不一致，区域已锁定；重启后恢复");
			return new Result(new FluidTransferService.TransferOutcome(false,
					operation == ContainerTransferJournal.Operation.INSERT, beforeStack,
					region.availabilityFailure()), transactionId);
		}
		service.markContainerTransferRegionCommitted(transactionId);
		try {
			service.injectContainerTransferFault(FluidSimulationService.ContainerTransferFaultPoint.AFTER_CHECKPOINT);
		} catch (RuntimeException injected) {
			return failed(operation, beforeStack,
					injected.getMessage() + "；已提交区域，WAL 将收敛物品", transactionId);
		}

		return new Result(new FluidTransferService.TransferOutcome(true,
				operation == ContainerTransferJournal.Operation.INSERT, finalAfter,
				rawOutcome.message(), rawOutcome.resourceAmount()), transactionId);
	}

	private static ContainerTransferJournal journal(
			UUID transactionId, FluidRegion region, ServerLevel level,
			long baseGeneration, long targetGeneration,
			ContainerTransferJournal.Operation operation, ContainerTransferJournal.ResourceKind resourceKind,
			long amount, ContainerTransferJournal.Actor actor,
			ContainerTransferJournal.ItemFingerprint before, ContainerTransferJournal.ItemFingerprint after,
			Double thermalEnergy, String thermalFingerprint, long checksum,
			ContainerTransferJournal.Phase phase
	) {
		if (resourceKind == ContainerTransferJournal.ResourceKind.GRANULAR_SVQ) {
			return ContainerTransferJournal.granular(transactionId, region.id(),
					level.dimension().identifier().toString(), baseGeneration, targetGeneration,
					operation, amount, actor, before, after, checksum, phase);
		}
		if (thermalEnergy != null) return ContainerTransferJournal.thermalFluid(
				transactionId, region.id(), level.dimension().identifier().toString(),
				baseGeneration, targetGeneration, operation, amount,
				new ContainerTransferJournal.ThermalTransfer(thermalFingerprint, thermalEnergy),
				actor, before, after, checksum, phase);
		return new ContainerTransferJournal(transactionId, region.id(),
				level.dimension().identifier().toString(), baseGeneration, targetGeneration,
				operation, amount, actor, before, after, checksum, phase);
	}

	private static Result failed(
			ContainerTransferJournal.Operation operation, ItemStack beforeStack,
			String message, UUID transactionId
	) {
		return new Result(new FluidTransferService.TransferOutcome(false,
				operation == ContainerTransferJournal.Operation.INSERT, beforeStack, message), transactionId);
	}

	private static void deleteQuietly(FluidSimulationService service, UUID transactionId) {
		try {
			service.deleteContainerTransfer(transactionId);
		} catch (IOException exception) {
			MmmMod.LOGGER.warn("Could not delete container transfer WAL {}", transactionId, exception);
		}
	}

	record Result(FluidTransferService.TransferOutcome outcome, UUID transactionId) { }

	@FunctionalInterface
	interface FinalItemResolver {
		ItemStack resolve(ItemStack rawAfter, ItemStack before);
	}

	@FunctionalInterface
	interface TransferExecutor {
		FluidTransferService.TransferOutcome execute(long requestedAmount, long baseAmount);
	}
}
