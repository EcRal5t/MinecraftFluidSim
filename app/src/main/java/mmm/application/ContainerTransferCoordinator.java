package mmm.application;

import mmm.application.port.ContainerJournalRepository;
import mmm.transfer.ContainerTransferJournal;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Owns PREPARED WAL -> private exact candidate -> staged checkpoint -> REGION_COMMITTED WAL -> HEAD. */
public final class ContainerTransferCoordinator {
	private final ContainerJournalRepository journals;
	private final RegionPort regions;
	private final FaultControl faults;

	public ContainerTransferCoordinator(ContainerJournalRepository journals, RegionPort regions) {
		this(journals, regions, new FaultControl());
	}

	public ContainerTransferCoordinator(
			ContainerJournalRepository journals, RegionPort regions, FaultControl faults
	) {
		this.journals = Objects.requireNonNull(journals, "journals");
		this.regions = Objects.requireNonNull(regions, "regions");
		this.faults = Objects.requireNonNull(faults, "faults");
	}

	ContainerTransferUseCase.Result execute(ContainerTransferUseCase.Plan plan) {
		ContainerTransferUseCase.Command command = plan.command();
		if (journals.hasPending(command.regionId(), command.actor())) {
			return result(ContainerTransferUseCase.Status.LOCKED, command, command.beforeItem(),
					"区域或目标 inventory 存在未完成容器转移 WAL，已锁定", 0L, null, -1L, null);
		}
		try {
			inject(FaultPoint.BEFORE_PREPARE);
		} catch (RuntimeException injected) {
			return result(ContainerTransferUseCase.Status.REJECTED, command, command.beforeItem(),
					injected.getMessage(), 0L, null, -1L, null);
		}

		long targetGeneration;
		try {
			targetGeneration = Math.addExact(command.baseGeneration(), 1L);
		} catch (ArithmeticException exhausted) {
			String failure = "Container transfer checkpoint generation is exhausted";
			regions.lock(command.regionId(), failure);
			return result(ContainerTransferUseCase.Status.LOCKED, command, command.beforeItem(),
					failure, 0L, null, -1L, null);
		}
		UUID transactionId = UUID.randomUUID();
		ContainerTransferJournal prepared = journal(transactionId, command, targetGeneration,
				command.requestedAmount(), command.beforeItem(), plan.preparedThermalEnergy(), -1L,
				ContainerTransferJournal.Phase.PREPARED);
		try {
			journals.write(prepared);
		} catch (IOException failure) {
			return result(ContainerTransferUseCase.Status.REJECTED, command, command.beforeItem(),
					"容器转移 WAL 发布失败：" + failure.getMessage(), 0L, null, -1L, null);
		}
		try {
			inject(FaultPoint.AFTER_PREPARE);
		} catch (RuntimeException injected) {
			return result(ContainerTransferUseCase.Status.LOCKED, command, command.beforeItem(),
					injected.getMessage(), 0L, null, -1L, transactionId);
		}

		Execution execution;
		try {
			execution = regions.execute(command);
		} catch (RuntimeException failure) {
			return discardPrivateCandidate(command, transactionId,
					"容器转移私有候选执行异常；在线区域保持 base 不变：" + failure.getMessage(),
					ContainerTransferUseCase.Status.REJECTED);
		}
		if (execution == null || !execution.transferred()) {
			try {
				journals.delete(transactionId);
			} catch (IOException cleanupFailure) {
				return result(ContainerTransferUseCase.Status.LOCKED, command, command.beforeItem(),
						"转移未执行，PREPARED WAL 清理失败，将由恢复流程收敛："
								+ cleanupFailure.getMessage(), 0L, null, -1L, transactionId);
			}
			String diagnostic = execution == null ? "转移未执行" : execution.diagnostic();
			return result(ContainerTransferUseCase.Status.NOT_TRANSFERRED, command, command.beforeItem(),
					diagnostic, 0L, null, -1L, null);
		}
		if (execution.afterItem() == null || execution.actualAmount() <= 0L
				|| execution.actualAmount() > command.requestedAmount()) {
			return discardPrivateCandidate(command, transactionId,
					"容器转移私有候选返回无效 actual settlement；在线区域保持不变",
					ContainerTransferUseCase.Status.REJECTED);
		}
		try {
			String afterProfile = execution.afterItem().profileFingerprint();
			if (afterProfile != null && !afterProfile.equals(command.expectedProfileFingerprint())) {
				throw new IllegalArgumentException("after item belongs to a foreign world profile");
			}
			if (execution.afterItem().thermalContents() != null) {
				if (!command.regionHasThermalState()) {
					throw new IllegalArgumentException("non-thermal region produced thermal item truth");
				}
				execution.afterItem().validateThermalRegistry(command.thermalRegistryFingerprint());
			}
		} catch (RuntimeException invalidAfter) {
			return discardPrivateCandidate(command, transactionId,
					"容器转移 after-item 真值无效；在线区域保持不变：" + invalidAfter.getMessage(),
					ContainerTransferUseCase.Status.REJECTED);
		}
		boolean thermalTransfer = command.resourceKind() == ContainerTransferJournal.ResourceKind.FLUID_NANOS
				&& command.regionHasThermalState();
		if (thermalTransfer != (execution.thermalEnergy() != null)) {
			return discardPrivateCandidate(command, transactionId,
					"容器转移私有候选未返回与区域热状态一致的 coupled energy；在线区域保持不变",
					ContainerTransferUseCase.Status.REJECTED);
		}
		try {
			inject(FaultPoint.AFTER_SOLVER);
		} catch (RuntimeException injected) {
			String diagnostic = injected.getMessage() + "；区域已锁定，重启后从 base checkpoint 恢复";
			regions.lock(command.regionId(), diagnostic);
			return result(ContainerTransferUseCase.Status.LOCKED, command, command.beforeItem(),
					diagnostic, 0L, null, -1L, transactionId);
		}

		StagedCheckpoint staged;
		try {
			staged = regions.stage(transactionId, command.regionId(), targetGeneration);
		} catch (IOException | RuntimeException failure) {
			String diagnostic = "容器转移 checkpoint staging 失败，区域已锁定；重启后恢复："
					+ failure.getMessage();
			regions.lock(command.regionId(), diagnostic);
			return result(ContainerTransferUseCase.Status.LOCKED, command, command.beforeItem(),
					diagnostic, 0L, null, -1L, transactionId);
		}
		try {
			inject(FaultPoint.AFTER_STAGE);
		} catch (RuntimeException injected) {
			String diagnostic = injected.getMessage()
					+ "；staged generation 尚未发布，重启后从 base checkpoint 恢复";
			regions.lock(command.regionId(), diagnostic);
			return result(ContainerTransferUseCase.Status.LOCKED, command, command.beforeItem(),
					diagnostic, 0L, null, -1L, transactionId);
		}

		ContainerTransferJournal committed = journal(transactionId, command, targetGeneration,
				execution.actualAmount(), execution.afterItem(), execution.thermalEnergy(),
				staged.manifestChecksum(), ContainerTransferJournal.Phase.REGION_COMMITTED);
		try {
			journals.write(committed);
		} catch (IOException failure) {
			try { regions.abort(staged); } catch (IOException abortFailure) { failure.addSuppressed(abortFailure); }
			String diagnostic = "容器转移 WAL 更新失败，区域已锁定；重启后恢复：" + failure.getMessage();
			regions.lock(command.regionId(), diagnostic);
			return result(ContainerTransferUseCase.Status.LOCKED, command, command.beforeItem(),
					diagnostic, 0L, null, -1L, null);
		}
		try {
			inject(FaultPoint.AFTER_WAL);
		} catch (RuntimeException injected) {
			String diagnostic = injected.getMessage() + "；区域已锁定，重启后恢复";
			regions.lock(command.regionId(), diagnostic);
			return result(ContainerTransferUseCase.Status.LOCKED, command, command.beforeItem(),
					diagnostic, 0L, null, -1L, transactionId);
		}

		long publishedGeneration;
		try {
			publishedGeneration = regions.commit(staged);
		} catch (IOException | RuntimeException failure) {
			String diagnostic = "容器转移检查点失败，区域已锁定；重启后恢复：" + failure.getMessage();
			regions.lock(command.regionId(), diagnostic);
			return result(ContainerTransferUseCase.Status.LOCKED, command, command.beforeItem(),
					diagnostic, 0L, null, -1L, transactionId);
		}
		if (publishedGeneration != targetGeneration) {
			String diagnostic = "容器转移检查点代次不一致，区域已锁定；重启后恢复";
			regions.lock(command.regionId(), diagnostic);
			return result(ContainerTransferUseCase.Status.LOCKED, command, command.beforeItem(),
					diagnostic, 0L, null, -1L, transactionId);
		}
		try {
			inject(FaultPoint.AFTER_CHECKPOINT);
		} catch (RuntimeException injected) {
			return result(ContainerTransferUseCase.Status.LOCKED, command, command.beforeItem(),
					injected.getMessage() + "；已提交区域，WAL 将收敛物品",
					0L, null, publishedGeneration, transactionId);
		}
		return result(ContainerTransferUseCase.Status.COMMITTED, command, execution.afterItem(),
				execution.diagnostic(), execution.actualAmount(), execution.thermalEnergy(),
				publishedGeneration, transactionId);
	}

	private ContainerTransferUseCase.Result discardPrivateCandidate(
			ContainerTransferUseCase.Command command,
			UUID transactionId,
			String diagnostic,
			ContainerTransferUseCase.Status cleanStatus
	) {
		try {
			journals.delete(transactionId);
			return result(cleanStatus, command, command.beforeItem(), diagnostic, 0L, null, -1L, null);
		} catch (IOException cleanupFailure) {
			String locked = diagnostic + "；PREPARED WAL 清理失败，将由恢复流程按 base HEAD 收敛："
					+ cleanupFailure.getMessage();
			regions.lock(command.regionId(), locked);
			return result(ContainerTransferUseCase.Status.LOCKED, command, command.beforeItem(),
					locked, 0L, null, -1L, transactionId);
		}
	}

	private static ContainerTransferUseCase.Result result(
			ContainerTransferUseCase.Status status,
			ContainerTransferUseCase.Command command,
			ContainerTransferJournal.ItemFingerprint after,
			String diagnostic,
			long amount,
			Double energy,
			long generation,
			UUID transactionId
	) {
		return new ContainerTransferUseCase.Result(status,
				command.operation() == ContainerTransferJournal.Operation.INSERT,
				after, diagnostic, amount, energy, generation, transactionId);
	}

	private static ContainerTransferJournal journal(
			UUID transactionId,
			ContainerTransferUseCase.Command command,
			long targetGeneration,
			long amount,
			ContainerTransferJournal.ItemFingerprint after,
			Double thermalEnergy,
			long checksum,
			ContainerTransferJournal.Phase phase
	) {
		if (command.resourceKind() == ContainerTransferJournal.ResourceKind.GRANULAR_SVQ) {
			return ContainerTransferJournal.granular(transactionId, command.regionId(), command.dimensionId(),
					command.baseGeneration(), targetGeneration, command.operation(), amount, command.actor(),
					command.beforeItem(), after, checksum, phase);
		}
		if (thermalEnergy != null) {
			return ContainerTransferJournal.thermalFluid(transactionId, command.regionId(), command.dimensionId(),
					command.baseGeneration(), targetGeneration, command.operation(), amount,
					new ContainerTransferJournal.ThermalTransfer(command.thermalRegistryFingerprint(), thermalEnergy),
					command.actor(), command.beforeItem(), after, checksum, phase);
		}
		return new ContainerTransferJournal(transactionId, command.regionId(), command.dimensionId(),
				command.baseGeneration(), targetGeneration, command.operation(), amount, command.actor(),
				command.beforeItem(), after, checksum, phase);
	}

	public void setFaultPoint(FaultPoint faultPoint) {
		faults.set(faultPoint);
	}

	public FaultPoint faultPoint() { return faults.get(); }

	private void inject(FaultPoint point) {
		faults.inject(point);
	}

	public enum FaultPoint {
		NONE,
		BEFORE_PREPARE,
		AFTER_PREPARE,
		AFTER_SOLVER,
		AFTER_STAGE,
		AFTER_WAL,
		AFTER_CHECKPOINT
	}

	/** Shared fault state lets short-lived actor adapters exercise one durable coordinator sequence. */
	public static final class FaultControl {
		private FaultPoint point = FaultPoint.NONE;

		public void set(FaultPoint point) { this.point = point == null ? FaultPoint.NONE : point; }

		public FaultPoint get() { return point; }

		private void inject(FaultPoint expected) {
			if (point == expected) throw new RuntimeException("Injected container transfer fault: " + expected);
		}
	}

	public record Execution(
			boolean transferred,
			ContainerTransferJournal.ItemFingerprint afterItem,
			String diagnostic,
			long actualAmount,
			Double thermalEnergy
	) {
		public Execution {
			diagnostic = diagnostic == null ? "" : diagnostic;
		}
	}

	public interface StagedCheckpoint {
		long manifestChecksum();
	}

	public interface RegionPort {
		Execution execute(ContainerTransferUseCase.Command command);

		StagedCheckpoint stage(UUID transactionId, UUID regionId, long targetGeneration) throws IOException;

		void abort(StagedCheckpoint staged) throws IOException;

		long commit(StagedCheckpoint staged) throws IOException;

		void lock(UUID regionId, String diagnostic);
	}
}
