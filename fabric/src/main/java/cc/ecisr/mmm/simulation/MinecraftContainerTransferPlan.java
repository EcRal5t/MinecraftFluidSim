package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.item.ContainerItemStacks;
import cc.ecisr.mmm.item.GranularContainerItem;
import cc.ecisr.mmm.item.MaterialBucketItem;
import cc.ecisr.mmm.item.MmmComponents;
import cc.ecisr.mmm.transfer.ChemicalContents;
import cc.ecisr.mmm.transfer.ContainerFluidLedger;
import cc.ecisr.mmm.transfer.ContainerTransferActor;
import cc.ecisr.mmm.transfer.ContainerTransferJournal;
import cc.ecisr.mmm.transfer.FluidMixture;
import cc.ecisr.mmm.transfer.GranularContents;
import cc.ecisr.mmm.transfer.ThermalContents;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** One resolved item-to-solver transfer plan shared by player and dispenser actor adapters. */
final class MinecraftContainerTransferPlan {
	private final FluidSimulationService service;
	private final FluidRegion region;
	private final int[] cell;
	private final ItemStack sourceStack;
	private final MinecraftContainerTransferOperations.TransferKind kind;
	private final ContainerTransferJournal.Operation operation;
	private final ContainerTransferJournal.ResourceKind resourceKind;
	private final long requestedAmount;
	private final long baseAmount;
	private final boolean externalSource;
	private final FluidMixture mixture;
	private final ChemicalContents chemical;
	private final GranularContents granular;
	private final ThermalContents thermal;

	private MinecraftContainerTransferPlan(
			FluidSimulationService service,
			FluidRegion region,
			int[] cell,
			ItemStack sourceStack,
			MinecraftContainerTransferOperations.TransferKind kind,
			ContainerTransferJournal.Operation operation,
			ContainerTransferJournal.ResourceKind resourceKind,
			long requestedAmount,
			long baseAmount,
			boolean externalSource,
			FluidMixture mixture,
			ChemicalContents chemical,
			GranularContents granular,
			ThermalContents thermal
	) {
		this.service = service;
		this.region = region;
		this.cell = cell;
		this.sourceStack = sourceStack;
		this.kind = kind;
		this.operation = operation;
		this.resourceKind = resourceKind;
		this.requestedAmount = requestedAmount;
		this.baseAmount = baseAmount;
		this.externalSource = externalSource;
		this.mixture = mixture;
		this.chemical = chemical;
		this.granular = granular;
		this.thermal = thermal;
	}

	static Resolution resolve(
			FluidSimulationService service,
			FluidRegion region,
			int[] cell,
			ItemStack sourceStack,
			boolean externalSource
	) {
		MinecraftContainerTransferOperations.TransferKind kind =
				MinecraftContainerTransferOperations.classify(sourceStack);
		if (kind == MinecraftContainerTransferOperations.TransferKind.NONE) {
			return new Resolution(null, "该物品没有可转移的容器库存");
		}
		if ((kind == MinecraftContainerTransferOperations.TransferKind.MATERIAL_POUR
				|| kind == MinecraftContainerTransferOperations.TransferKind.GRANULAR_POUR)
				&& !service.acceptsActualContainerProfile(sourceStack)) {
			return new Resolution(null, "FOREIGN_PROFILE：actual container 不属于当前世界；库存保持不变");
		}
		String thermalFailure = MinecraftContainerTransferOperations.thermalTransferFailure(
				service, region, sourceStack, kind);
		if (thermalFailure != null) return new Resolution(null, thermalFailure);

		ContainerTransferJournal.Operation operation = switch (kind) {
			case POUR, MATERIAL_POUR, GRANULAR_POUR -> ContainerTransferJournal.Operation.INSERT;
			case SCOOP, MATERIAL_SCOOP, GRANULAR_SCOOP -> ContainerTransferJournal.Operation.EXTRACT;
			case NONE -> throw new IllegalStateException("NONE handled above");
		};
		ContainerTransferJournal.ResourceKind resourceKind =
				kind == MinecraftContainerTransferOperations.TransferKind.GRANULAR_POUR
						|| kind == MinecraftContainerTransferOperations.TransferKind.GRANULAR_SCOOP
						? ContainerTransferJournal.ResourceKind.GRANULAR_SVQ
						: ContainerTransferJournal.ResourceKind.FLUID_NANOS;
		FluidMixture mixture = null;
		ChemicalContents chemical = null;
		GranularContents granular = null;
		long requested = 0L;
		long base = 0L;
		try {
			switch (kind) {
				case POUR -> {
					Long amount = MinecraftContainerTransferOperations.heldVolumeNanos(sourceStack);
					if (amount == null || amount <= 0L) return new Resolution(null, "液体容器没有有效库存");
					requested = amount;
				}
				case SCOOP -> {
					base = MinecraftContainerTransferOperations.heldBaseVolumeNanos(sourceStack);
					requested = MinecraftContainerTransferOperations.extractableNanos(region.domain(), cell,
							MinecraftContainerTransferOperations.heldFreeNanos(sourceStack), 6, 0);
					if (requested <= 0L) return new Resolution(null, "准星处没有可装入的水体");
				}
				case MATERIAL_POUR -> {
					if (!service.worldCapabilityProfile().hasCapability(WorldCapability.MATERIALS)) {
						return new Resolution(null, "材料功能门未开启");
					}
					if (!region.domain().hasMaterialState()) return new Resolution(null, "该区域不是材料区域");
					if (sourceStack.get(MmmComponents.CHEMICAL_CONTENTS) != null) {
						if (!service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY)
								|| !region.domain().hasChemistryState()) {
							return new Resolution(null, "化学桶只能倒入启用相同 registry 的化学区域");
						}
						chemical = MaterialBucketItem.chemicalContents(sourceStack, service.chemicalRegistry());
						if (chemical == null) return new Resolution(null, "化学桶内容损坏、双重表示或 registry 不匹配");
						requested = chemical.totalVolumeNanos();
					} else {
						mixture = MaterialBucketItem.mixture(sourceStack);
						if (mixture == null) return new Resolution(null, "材料桶内容损坏或 registry 不匹配");
						requested = mixture.totalVolumeNanos();
					}
				}
				case MATERIAL_SCOOP -> {
					if (!service.worldCapabilityProfile().hasCapability(WorldCapability.MATERIALS)) {
						return new Resolution(null, "材料功能门未开启");
					}
					if (!region.domain().hasMaterialState()) return new Resolution(null, "该区域不是材料区域");
					requested = MinecraftContainerTransferOperations.extractableNanos(region.domain(), cell,
							ContainerFluidLedger.NANOS_PER_BLOCK, 6, -1);
					if (requested <= 0L) return new Resolution(null, "准星处没有可装入的材料流体");
				}
				case GRANULAR_POUR -> {
					if (!service.worldCapabilityProfile().hasCapability(WorldCapability.GRANULAR)) {
						return new Resolution(null, "颗粒功能门本次启动未开启；干式容器保持不变");
					}
					if (!region.controllerManaged()) return new Resolution(null, "颗粒容器只允许在闭壳控制器区域转移");
					if (!region.domain().hasGranularState()) return new Resolution(null, "该区域不是颗粒区域");
					granular = ContainerItemStacks.granularContents(sourceStack, service.granularRegistry(),
							service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY)
									? service.chemicalRegistry() : null);
					if (granular == null) return new Resolution(null, "颗粒容器没有有效库存");
					requested = granular.totalSolidVolumeSvq();
				}
				case GRANULAR_SCOOP -> {
					if (!service.worldCapabilityProfile().hasCapability(WorldCapability.GRANULAR)) {
						return new Resolution(null, "颗粒功能门本次启动未开启；干式容器保持不变");
					}
					if (!region.controllerManaged()) return new Resolution(null, "颗粒容器只允许在闭壳控制器区域转移");
					if (!region.domain().hasGranularState() || !GranularContainerItem.isEmpty(sourceStack)) {
						return new Resolution(null, "干式容器不为空或该区域不是颗粒区域");
					}
					requested = SolidVolume.SVQ_PER_BV;
				}
				case NONE -> throw new IllegalStateException("NONE handled above");
			}
		} catch (RuntimeException invalidContents) {
			return new Resolution(null, "容器内容损坏、双重表示或 registry 不匹配");
		}
		if (requested <= 0L) return new Resolution(null, "容器转移请求没有正库存");
		ThermalContents thermal = resourceKind == ContainerTransferJournal.ResourceKind.FLUID_NANOS
				? MinecraftContainerTransferOperations.thermalContents(sourceStack) : null;
		return new Resolution(new MinecraftContainerTransferPlan(service, region, cell, sourceStack, kind,
				operation, resourceKind, requested, base, externalSource,
				mixture, chemical, granular, thermal), null);
	}

	MinecraftContainerTransferOperations.TransferOutcome executeExact(long requested, long base) {
		return switch (kind) {
			case POUR -> MinecraftContainerTransferOperations.pourIntoCell(
					service, region, cell, requested, 6, 0, externalSource, thermal);
			case SCOOP -> MinecraftContainerTransferOperations.scoopFromCell(
					service, region, cell, requested, base, 6, 0, thermal);
			case MATERIAL_POUR -> chemical == null
					? MinecraftContainerTransferOperations.pourMaterialIntoCell(
							service, region, cell, mixture, 6, externalSource, thermal)
					: MinecraftContainerTransferOperations.pourChemicalIntoCell(
							service, region, cell, chemical, 6, externalSource, thermal);
			case MATERIAL_SCOOP -> chemistryEnabled()
					? MinecraftContainerTransferOperations.scoopChemicalIntoCell(
							service, region, cell, requested, 6, service.chemicalRegistry())
					: MinecraftContainerTransferOperations.scoopMaterialIntoCell(
							service, region, cell, requested, 6);
			case GRANULAR_POUR -> MinecraftContainerTransferOperations.pourGranularIntoCell(
					service, region, cell, granular, 6, externalSource);
			case GRANULAR_SCOOP -> MinecraftContainerTransferOperations.scoopGranularIntoCell(
					service, region, cell, requested, 6);
			case NONE -> null;
		};
	}

	MinecraftContainerTransferOperations.TransferOutcome executeUnmanaged() {
		return executeExact(requestedAmount, baseAmount);
	}

	MinecraftContainerTransactionAdapter.Result executeManaged(
			ServerLevel level,
			ContainerTransferActor actor,
			ItemStack actorBefore,
			boolean descriptorActor
	) {
		if (descriptorActor) {
			return service.containerTransactions().executeCreativeSource(region, level, actor,
					actorBefore, sourceStack, resourceKind, requestedAmount,
					this::executeExact, (rawAfter, before) -> before.copy());
		}
		return service.containerTransactions().execute(region, level, actor, actorBefore,
				operation, resourceKind, requestedAmount, baseAmount, this::executeExact,
				(rawAfter, before) -> externalSource ? before.copy() : rawAfter);
	}

	boolean filled() { return operation == ContainerTransferJournal.Operation.INSERT; }
	long requestedAmount() { return requestedAmount; }

	private boolean chemistryEnabled() {
		return service.worldCapabilityProfile().hasCapability(WorldCapability.CHEMISTRY)
				&& region.domain().hasChemistryState();
	}

	record Resolution(MinecraftContainerTransferPlan plan, String diagnostic) {
		Resolution {
			if ((plan == null) == (diagnostic == null || diagnostic.isBlank())) {
				throw new IllegalArgumentException("Container plan resolution is ambiguous");
			}
		}

		boolean resolved() { return plan != null; }
	}
}
