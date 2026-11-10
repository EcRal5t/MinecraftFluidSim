package mmm.simulation;

import mmm.item.ContainerItemStacks;
import mmm.item.GranularContainerItem;
import mmm.item.MaterialBucketItem;
import mmm.item.MmmComponents;
import mmm.transfer.ChemicalContents;
import mmm.transfer.ContainerFluidLedger;
import mmm.transfer.ContainerTransferActor;
import mmm.transfer.ContainerTransferJournal;
import mmm.transfer.FluidMixture;
import mmm.transfer.GranularContents;
import mmm.transfer.ThermalContents;
import mmm.granular.SolidVolume;
import mmm.worldprofile.WorldCapability;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** One resolved item-to-solver transfer plan shared by player and dispenser actor adapters. */
final class MinecraftContainerTransferPlan {
	private final MinecraftContainerTransactionAdapter transactions;
	private final CommittedWorldRegistries world;
	private final FluidRegion region;
	private final int[] cell;
	private mmm.fluid.HostFluidState edit;
	private final long editGeneration;
	private ItemStack sourceStack;
	private final MinecraftContainerTransferOperations.TransferKind kind;
	private final ContainerTransferJournal.Operation operation;
	private final ContainerTransferJournal.ResourceKind resourceKind;
	private final long requestedAmount;
	private final long baseAmount;
	private final boolean externalSource;
	private final int transferRadius;
	private final FluidMixture mixture;
	private final ChemicalContents chemical;
	private final GranularContents granular;
	private final ThermalContents thermal;
	private ItemStack collectionBase;
	private long collectionSpace=Long.MAX_VALUE;
	private boolean directed;

	private MinecraftContainerTransferPlan(
			MinecraftContainerTransactionAdapter transactions,
			CommittedWorldRegistries world,
			FluidRegion region,
			int[] cell,
			mmm.fluid.HostFluidState edit,
			ItemStack sourceStack,
			MinecraftContainerTransferOperations.TransferKind kind,
			ContainerTransferJournal.Operation operation,
			ContainerTransferJournal.ResourceKind resourceKind,
			long requestedAmount,
			long baseAmount,
			boolean externalSource, int transferRadius,
			FluidMixture mixture,
			ChemicalContents chemical,
			GranularContents granular,
			ThermalContents thermal
	) {
		this.transactions = transactions;
		this.world = world;
		this.region = region;
		this.cell = cell;
		this.edit = edit;
		this.editGeneration = region.solverGeneration();
		this.sourceStack = sourceStack;
		this.kind = kind;
		this.operation = operation;
		this.resourceKind = resourceKind;
		this.requestedAmount = requestedAmount;
		this.baseAmount = baseAmount;
		this.externalSource = externalSource;
		this.transferRadius = transferRadius;
		this.mixture = mixture;
		this.chemical = chemical;
		this.granular = granular;
		this.thermal = thermal;
	}

	static Resolution resolve(
			MinecraftContainerTransactionAdapter transactions,
			CommittedWorldRegistries world,
			FluidRegion region,
			int[] cell,
			mmm.fluid.HostFluidState edit,
			ItemStack sourceStack,
			boolean externalSource, int transferRadius
	) {
		MinecraftContainerTransferOperations.TransferKind kind =
				MinecraftContainerTransferOperations.classify(sourceStack);
		if (kind == MinecraftContainerTransferOperations.TransferKind.NONE) {
			return new Resolution(null, "该物品没有可转移的容器库存");
		}
		if ((kind == MinecraftContainerTransferOperations.TransferKind.GAS_POUR || kind == MinecraftContainerTransferOperations.TransferKind.MATERIAL_POUR
				|| kind == MinecraftContainerTransferOperations.TransferKind.GRANULAR_POUR)
				&& !world.acceptsActualContainerProfile(sourceStack)) {
			return new Resolution(null, "FOREIGN_PROFILE：actual container 不属于当前世界；库存保持不变");
		}
		String thermalFailure = MinecraftContainerTransferOperations.thermalTransferFailure(
				world, region, sourceStack, kind);
		if (thermalFailure != null) return new Resolution(null, thermalFailure);
		// Vanilla water has no temperature component; its initial source temperature is room temperature.
		// Keep the actor's actual stack unchanged and use this source copy for the energy ledger.
		if (kind == MinecraftContainerTransferOperations.TransferKind.POUR
				&& sourceStack.is(net.minecraft.world.item.Items.WATER_BUCKET) && region.hasThermalState()
				&& MinecraftContainerTransferOperations.thermalContents(sourceStack) == null) {
			sourceStack = sourceStack.copy();
			try { world.attachLiquidThermalContents(sourceStack, mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K); }
			catch (RuntimeException invalid) { return new Resolution(null, "无法建立标准温度水桶热状态：" + invalid.getMessage()); }
		}

		ContainerTransferJournal.Operation operation = switch (kind) {
			case POUR, MATERIAL_POUR, GRANULAR_POUR, GAS_POUR -> ContainerTransferJournal.Operation.INSERT;
			case SCOOP, MATERIAL_SCOOP, GRANULAR_SCOOP, GAS_SCOOP -> ContainerTransferJournal.Operation.EXTRACT;
			case NONE -> throw new IllegalStateException("NONE handled above");
		};
		ContainerTransferJournal.ResourceKind resourceKind =
				kind == MinecraftContainerTransferOperations.TransferKind.GAS_POUR || kind == MinecraftContainerTransferOperations.TransferKind.GAS_SCOOP
						? ContainerTransferJournal.ResourceKind.GAS_STANDARD_NANOS : kind == MinecraftContainerTransferOperations.TransferKind.GRANULAR_POUR
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
					requested = MinecraftContainerTransferOperations.extractableNanos(edit, cell,
							MinecraftContainerTransferOperations.heldFreeNanos(sourceStack), transferRadius, waterMaterial(region));
					if (requested <= 0L) return new Resolution(null, "准星处没有可装入的水体");
				}
				case MATERIAL_POUR -> {
					if (!world.hasCapability(WorldCapability.MATERIALS)) {
						return new Resolution(null, "材料功能门未开启");
					}
					if (!region.hasMaterialState()) return new Resolution(null, "该区域不是材料区域");
					if (sourceStack.get(MmmComponents.CHEMICAL_CONTENTS) != null) {
						if (!world.hasCapability(WorldCapability.CHEMISTRY)
								|| !region.hasChemistryState()) {
							return new Resolution(null, "化学桶只能倒入启用相同 registry 的化学区域");
						}
						chemical = MaterialBucketItem.chemicalContents(sourceStack, world.chemicalRegistry());
						if (chemical == null) return new Resolution(null, "化学桶内容损坏、双重表示或 registry 不匹配");
						requested = chemical.totalVolumeNanos();
					} else {
						mixture = MaterialBucketItem.mixture(sourceStack);
						if (mixture == null) return new Resolution(null, "材料桶内容损坏或 registry 不匹配");
						requested = mixture.totalVolumeNanos();
					}
				}
				case MATERIAL_SCOOP -> {
					if (!world.hasCapability(WorldCapability.MATERIALS)) {
						return new Resolution(null, "材料功能门未开启");
					}
					if (!region.hasMaterialState()) return new Resolution(null, "该区域不是材料区域");
					requested = MinecraftContainerTransferOperations.extractableNanos(edit, cell,
							ContainerFluidLedger.NANOS_PER_BLOCK, transferRadius, -1);
					if (requested <= 0L) return new Resolution(null, "准星处没有可装入的材料流体");
				}
				case GRANULAR_POUR -> {
					if (!world.hasCapability(WorldCapability.GRANULAR)) {
						return new Resolution(null, "颗粒功能门本次启动未开启；干式容器保持不变");
					}
					if (!region.controllerManaged()) return new Resolution(null, "颗粒容器只允许在闭壳控制器区域转移");
					if (!region.hasGranularState()) return new Resolution(null, "该区域不是颗粒区域");
					granular = ContainerItemStacks.granularContents(sourceStack, world.granularRegistry(),
							world.hasCapability(WorldCapability.CHEMISTRY) ? world.chemicalRegistry() : null);
					if (granular == null) return new Resolution(null, "颗粒容器没有有效库存");
					requested = granular.totalSolidVolumeSvq();
				}
				case GRANULAR_SCOOP -> {
					if (!world.hasCapability(WorldCapability.GRANULAR)) {
						return new Resolution(null, "颗粒功能门本次启动未开启；干式容器保持不变");
					}
					if (!region.controllerManaged()) return new Resolution(null, "颗粒容器只允许在闭壳控制器区域转移");
					if (!region.hasGranularState() || !GranularContainerItem.isEmpty(sourceStack)) {
						return new Resolution(null, "干式容器不为空或该区域不是颗粒区域");
					}
					requested = SolidVolume.SVQ_PER_CUBIC_METER;
				}
				case GAS_POUR -> {
					var gas=mmm.item.GasContainerItem.contents(sourceStack);
					if(gas==null || !gas.gasRegistryFingerprint().equals(world.gasRegistry().fingerprint())) return new Resolution(null,"气体容器材料定义不匹配");
					requested=gas.inventory().standardVolumeNanos();
				}
				case GAS_SCOOP -> requested=mmm.item.GasContainerItem.CAPACITY_NANOS;
				case NONE -> throw new IllegalStateException("NONE handled above");
			}
		} catch (RuntimeException invalidContents) {
			return new Resolution(null, "容器内容损坏、双重表示或 registry 不匹配");
		}
		if (requested <= 0L) return new Resolution(null, "容器转移请求没有正库存");
		ThermalContents thermal = MinecraftContainerTransferOperations.thermalContents(sourceStack);
		return new Resolution(new MinecraftContainerTransferPlan(transactions, world, region, cell, edit, sourceStack, kind,
				operation, resourceKind, requested, base, externalSource, transferRadius,
				mixture, chemical, granular, thermal), null);
	}

	static Resolution resolveDirected(MinecraftContainerTransactionAdapter transactions,CommittedWorldRegistries world,
			FluidRegion region,int[] cell,mmm.fluid.HostFluidState edit,ItemStack item,boolean extract) {
		if(!extract) {
			var result=resolve(transactions,world,region,cell,edit,item,false,0);
			if(result.resolved())result.plan().directed=true;
			return result;
		}
		boolean solid=item.getItem() instanceof GranularContainerItem, gas=item.getItem() instanceof mmm.item.GasContainerItem;
		var empty=new ItemStack(gas?mmm.item.MmmItems.GAS_CONTAINER:solid?mmm.item.MmmItems.GRANULAR_CONTAINER:mmm.item.MmmItems.MATERIAL_BUCKET);
		var result=resolve(transactions,world,region,cell,edit,empty,false,0);
		if(result.resolved()){
			var plan=result.plan();plan.directed=true;plan.collectionBase=item.copy();plan.sourceStack=item.copy();
			long used=gas?(mmm.item.GasContainerItem.contents(item)==null?0:mmm.item.GasContainerItem.contents(item).inventory().standardVolumeNanos()):solid?(GranularContainerItem.unvalidatedContents(item)==null?0:GranularContainerItem.unvalidatedContents(item).totalSolidVolumeSvq()):Math.max(0,MaterialBucketItem.volume(item));
			plan.collectionSpace=(gas?mmm.item.GasContainerItem.CAPACITY_NANOS:solid?SolidVolume.SVQ_PER_CUBIC_METER:ContainerFluidLedger.NANOS_PER_BLOCK)-used;
			if(plan.collectionSpace<=0)return new Resolution(null,"批次已满");
		}
		return result;
	}

	MinecraftContainerTransferOperations.TransferOutcome executeExact(FluidRegion targetRegion,long requested,long base) {
		if(directed && operation==ContainerTransferJournal.Operation.INSERT && resourceKind!=ContainerTransferJournal.ResourceKind.GAS_STANDARD_NANOS) {
			var host=targetRegion.editableHost();double room=host.cellCondensedRoom(cell[0],cell[1],cell[2]);
			long capacity=resourceKind==ContainerTransferJournal.ResourceKind.GRANULAR_SVQ
					?(long)Math.floor(room*SolidVolume.SVQ_PER_CELL)
					:(long)Math.floor(host.unitScale().cubicMeters(room)*ContainerFluidLedger.NANOS_PER_BLOCK);
			requested=Math.min(requested,capacity);
			if(requested<=0)return null;
		}
		var outcome=executeTransfer(targetRegion,Math.min(requested,collectionSpace),base);
		if(collectionBase==null||outcome==null||!outcome.transferred())return outcome;
		var merged=ContainerItemStacks.merge(collectionBase,world.bindActualContainerProfile(outcome.stack()));
        double coupled=outcome.thermalEnergy();
        if(targetRegion.hasThermalState()){
            coupled=mmm.units.SignedDyadic.of(batchEnergy(merged)).subtract(mmm.units.SignedDyadic.of(batchEnergy(collectionBase))).toDouble();
            targetRegion.editableHost().settleExternalContainerExtraction(outcome.thermalEnergy(),coupled);
        }
        return new MinecraftContainerTransferOperations.TransferOutcome(true,false,merged,outcome.message(),outcome.resourceAmount(),coupled);
	}

    private static double batchEnergy(ItemStack item){
        if(item.getItem() instanceof mmm.item.GasContainerItem){var contents=mmm.item.GasContainerItem.contents(item);return contents==null?0:contents.internalEnergy();}
        var heat=ContainerItemStacks.thermalContents(item);return heat==null?0:heat.internalEnergy();
    }

	private MinecraftContainerTransferOperations.TransferOutcome executeTransfer(
			FluidRegion targetRegion, long requested, long base
	) {
		return switch (kind) {
			case POUR -> MinecraftContainerTransferOperations.pourIntoCell(
					world, targetRegion, cell, MinecraftContainerTransferOperations.heldVolumeNanos(sourceStack),
						requested, transferRadius, waterMaterial(targetRegion), externalSource, thermal);
			case SCOOP -> MinecraftContainerTransferOperations.scoopFromCell(
					world, targetRegion, cell, requested, base, transferRadius, waterMaterial(targetRegion), thermal);
			case MATERIAL_POUR -> chemical == null
					? MinecraftContainerTransferOperations.pourMaterialIntoCell(
							world, targetRegion, cell, mixture, requested, transferRadius, externalSource, thermal)
					: MinecraftContainerTransferOperations.pourChemicalIntoCell(
							world, targetRegion, cell, chemical, requested, transferRadius, externalSource, thermal);
			case MATERIAL_SCOOP -> chemistryEnabled()
					? MinecraftContainerTransferOperations.scoopChemicalIntoCell(
							world, targetRegion, cell, requested, transferRadius, world.chemicalRegistry())
					: MinecraftContainerTransferOperations.scoopMaterialIntoCell(
							world, targetRegion, cell, requested, transferRadius);
			case GRANULAR_POUR -> MinecraftContainerTransferOperations.pourGranularIntoCell(
					world, targetRegion, cell, granular, requested, transferRadius, externalSource, thermal);
			case GRANULAR_SCOOP -> MinecraftContainerTransferOperations.scoopGranularIntoCell(
					world, targetRegion, cell, requested, transferRadius);
			case GAS_POUR -> MinecraftContainerTransferOperations.pourGas(world,targetRegion,cell,mmm.item.GasContainerItem.contents(sourceStack),requested);
			case GAS_SCOOP -> MinecraftContainerTransferOperations.scoopGas(world,targetRegion,cell,requested);
			case NONE -> null;
		};
	}

	MinecraftContainerTransferOperations.TransferOutcome executeUnmanaged(long maximum) {
		long generation = editGeneration;
		var candidate = region.lifecycleCandidate(edit, region.paused());
		var outcome = executeExact(candidate, Math.min(maximum, requestedAmount()), baseAmount);
		if (outcome != null && outcome.transferred()) {
			region.commitHostEdit(generation, edit);
			region.wakeForActivity();
			if (outcome.thermalEnergy() != null) region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		}
		return outcome;
	}

	MinecraftContainerTransactionAdapter.Result executeManaged(
			ServerLevel level,
			ContainerTransferActor actor,
			ItemStack actorBefore,
			boolean descriptorActor
	) {
		if (descriptorActor) {
			return transactions.executeCreativeSource(region, level, actor,
					actorBefore, sourceStack, resourceKind, requestedAmount,
					this::executeExact, (rawAfter, before) -> before.copy(), edit, editGeneration);
		}
		return transactions.executeResolvedSource(region, level, actor, actorBefore, sourceStack,
				operation, resourceKind, requestedAmount, baseAmount, this::executeExact,
				(rawAfter, before) -> externalSource && operation == ContainerTransferJournal.Operation.INSERT ? before.copy() : rawAfter, edit, editGeneration);
	}

	/** Uses the same durable transaction as the world adapter after the region target is resolved. */
	MinecraftContainerTransactionAdapter.Result executeManagedForRegion(ContainerTransferActor actor, ItemStack actorBefore) {
		return transactions.executeForRegion(region, actor, actorBefore, sourceStack, operation, resourceKind,
				requestedAmount, baseAmount, this::executeExact,
				(rawAfter, before) -> externalSource && operation == ContainerTransferJournal.Operation.INSERT ? before.copy() : rawAfter, null, edit, editGeneration);
	}

	boolean filled() { return operation == ContainerTransferJournal.Operation.INSERT; }
	private static int waterMaterial(FluidRegion region) {
		return region.hasMaterialState() ? region.materialHandle("mmm:water") : 0;
	}
	long requestedAmount() { return Math.min(requestedAmount,collectionSpace); }
	long baseAmount() { return baseAmount; }
	void discardEdit() { edit = null; }
	mmm.fluid.HostFluidState edit() { return edit; }
	long editGeneration() { return editGeneration; }
	ItemStack sourceStack() { return sourceStack.copy(); }
	ContainerTransferJournal.ResourceKind resourceKind() { return resourceKind; }

	private boolean chemistryEnabled() {
		return world.hasCapability(WorldCapability.CHEMISTRY)
				&& region.hasChemistryState();
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
