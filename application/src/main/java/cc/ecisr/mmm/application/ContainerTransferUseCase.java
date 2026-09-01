package cc.ecisr.mmm.application;

import cc.ecisr.mmm.transfer.ContainerTransferActor;
import cc.ecisr.mmm.transfer.ContainerTransferJournal;
import cc.ecisr.mmm.transfer.ThermalContents;

import java.util.Objects;
import java.util.UUID;

/** Validates loader-neutral container truth before entering the durable transfer coordinator. */
public final class ContainerTransferUseCase {
	private final ContainerTransferCoordinator coordinator;

	public ContainerTransferUseCase(ContainerTransferCoordinator coordinator) {
		this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
	}

	public Result execute(Command command) {
		Objects.requireNonNull(command, "command");
		String invalid = validateProfile(command.beforeItem(), command.expectedProfileFingerprint());
		if (invalid == null) invalid = validateProfile(command.sourceItem(), command.expectedProfileFingerprint());
		if (invalid != null) return Result.rejected(command, invalid);
		if (command.resourceKind() == ContainerTransferJournal.ResourceKind.GRANULAR_SVQ
				&& (command.sourceItem().thermalContents() != null
				|| command.beforeItem().thermalContents() != null)) {
			return Result.rejected(command, "颗粒容器不能携带液体 ThermalContents");
		}

		Double preparedEnergy = null;
		if (command.resourceKind() == ContainerTransferJournal.ResourceKind.FLUID_NANOS
				&& command.regionHasThermalState()) {
			preparedEnergy = 0.0;
			if (command.operation() == ContainerTransferJournal.Operation.INSERT) {
				String encoded = command.sourceItem().thermalContents();
				if (encoded == null) {
					return Result.rejected(command,
							"热区域注入缺少 ThermalContents；拒绝发布不完整 WAL");
				}
				try {
					ThermalContents source = ThermalContents.decode(encoded);
					source.validateRegistryFingerprint(command.thermalRegistryFingerprint());
					long totalAmount = liquidAmount(command.sourceItem());
					ThermalContents.EnergySplit split = source.splitHomogeneousEnergy(
							command.requestedAmount(), totalAmount);
					preparedEnergy = source.internalEnergy() - split.remainingEnergy();
				} catch (RuntimeException invalidThermal) {
					return Result.rejected(command,
							"容器热状态损坏或与当前 registry/库存不一致；转移保持不变");
				}
			}
		} else if (command.sourceItem().thermalContents() != null
				|| command.beforeItem().thermalContents() != null) {
			return Result.rejected(command, "带热真值的容器不能转入未启用热状态的区域");
		}

		return coordinator.execute(new Plan(command, preparedEnergy));
	}

	private static String validateProfile(
			ContainerTransferJournal.ItemFingerprint item, String expectedProfile
	) {
		if (item.profileFingerprint() == null) return null;
		return item.profileFingerprint().equals(expectedProfile) ? null
				: "FOREIGN_PROFILE：actual container 不属于当前世界；库存保持不变";
	}

	private static long liquidAmount(ContainerTransferJournal.ItemFingerprint item) {
		return switch (item.kind()) {
			case WATER_BUCKET -> cc.ecisr.mmm.transfer.ContainerFluidLedger.NANOS_PER_BLOCK;
			case PARTIAL, MATERIAL_BUCKET, CHEMICAL_BUCKET -> item.nanos();
			case BUCKET, EMPTY_MATERIAL_BUCKET, EMPTY_GRANULAR_CONTAINER,
					GRANULAR_CONTAINER, CREATIVE_SOURCE ->
					throw new IllegalArgumentException("Thermal source item has no liquid inventory");
		};
	}

	public record Command(
			UUID regionId,
			String dimensionId,
			long baseGeneration,
			boolean regionHasThermalState,
			String expectedProfileFingerprint,
			String thermalRegistryFingerprint,
			ContainerTransferActor actor,
			ContainerTransferJournal.ItemFingerprint beforeItem,
			ContainerTransferJournal.ItemFingerprint sourceItem,
			ContainerTransferJournal.Operation operation,
			ContainerTransferJournal.ResourceKind resourceKind,
			long requestedAmount,
			long baseAmount
	) {
		public Command {
			Objects.requireNonNull(regionId, "regionId");
			if (dimensionId == null || dimensionId.isBlank() || baseGeneration < 0L
					|| expectedProfileFingerprint == null
					|| !expectedProfileFingerprint.matches("[0-9a-f]{64}")
					|| actor == null || beforeItem == null || sourceItem == null
					|| operation == null || resourceKind == null || requestedAmount <= 0L
					|| baseAmount < 0L) {
				throw new IllegalArgumentException("Container transfer command is invalid");
			}
			if (regionHasThermalState
					&& (thermalRegistryFingerprint == null
					|| !thermalRegistryFingerprint.matches("[0-9a-f]{64}"))) {
				throw new IllegalArgumentException("Thermal container transfer lacks registry identity");
			}
		}
	}

	record Plan(Command command, Double preparedThermalEnergy) { }

	public record Result(
			Status status,
			boolean filled,
			ContainerTransferJournal.ItemFingerprint afterItem,
			String diagnostic,
			long actualAmount,
			Double thermalEnergy,
			long committedGeneration,
			UUID transactionId
	) {
		public Result {
			Objects.requireNonNull(status, "status");
			Objects.requireNonNull(afterItem, "afterItem");
			diagnostic = diagnostic == null ? "" : diagnostic;
			if (actualAmount < 0L || committedGeneration < -1L) {
				throw new IllegalArgumentException("Container transfer result settlement is invalid");
			}
		}

		static Result rejected(Command command, String diagnostic) {
			return new Result(Status.REJECTED,
					command.operation() == ContainerTransferJournal.Operation.INSERT,
					command.beforeItem(), diagnostic, 0L, null, -1L, null);
		}

		public boolean committed() { return status == Status.COMMITTED; }
	}

	public enum Status { COMMITTED, NOT_TRANSFERRED, REJECTED, LOCKED }
}
