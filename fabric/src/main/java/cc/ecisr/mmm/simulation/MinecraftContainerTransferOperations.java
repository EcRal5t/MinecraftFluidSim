package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.transfer.ContainerFluidLedger;
import cc.ecisr.mmm.transfer.ChemicalContents;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;


/**
 * Minecraft-side exact transfer primitives shared by the player and dispenser adapters.
 * Actor targeting and output are deliberately outside this class.
 */
final class MinecraftContainerTransferOperations {
	private MinecraftContainerTransferOperations() { }

	static TransferOutcome pourIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long volumeNanos, int maxRadius,
			int materialHandle, boolean externalSource, ThermalContents thermalContents
	) {
		if (volumeNanos <= 0L) return null;
		float requestedCellVolume = nanosToCellVolumeAtMost(volumeNanos);
		if (requestedCellVolume <= 0.0F) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "容器余量小于 solver 可表示体积，未能注入");
		}
		FluidDomain domain = region.domain();
		if (domain.totalOpenVolume() - domain.totalVolume() + 1.0E-6F < requestedCellVolume) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "空间或 marker 预算不足，未能注入");
		}
		long plannedNanos = cellVolumeToNanos(requestedCellVolume);
		ThermalContents.EnergySplit thermalSplit = thermalContents == null ? null
				: thermalContents.splitEnergy(plannedNanos, volumeNanos);
		Double coupledEnergy = thermalSplit == null ? null
				: thermalContents.internalEnergy() - thermalSplit.remainingEnergy();
		FluidDomain.ExactVolumeTransfer inserted = thermalSplit == null
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

	static TransferOutcome scoopFromCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long capacityNanos, long baseVolumeNanos,
			int maxRadius, int materialHandle, ThermalContents baseThermal
	) {
		if (capacityNanos <= 0L) return null;
		float requestedCellVolume = nanosToCellVolumeAtMost(capacityNanos);
		if (requestedCellVolume <= 0.0F) {
			return new TransferOutcome(false, false, withVolume(baseVolumeNanos), "容器余量小于 solver 可表示体积");
		}
		FluidDomain.ExactVolumeTransfer extracted = region.domain().tryExtractExactVolume(
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


	static TransferOutcome pourMaterialIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, FluidMixture mixture, int maxRadius,
			boolean externalSource, ThermalContents thermalContents
	) {
		if (mixture == null) return null;
		FluidDomain domain = region.domain();
		java.util.List<FluidDomain.MaterialPortion> portions;
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
		FluidDomain.ExactMixtureTransfer inserted = thermalSplit == null
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

	static TransferOutcome pourChemicalIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, ChemicalContents contents, int maxRadius,
			boolean externalSource, ThermalContents thermalContents
	) {
		FluidDomain domain = region.domain();
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
			FluidDomain.ExactChemicalTransfer inserted = thermalSplit == null
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

	static TransferOutcome scoopChemicalIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long capacityNanos, int maxRadius,
			cc.ecisr.mmm.chemistry.ChemicalRegistry registry
	) {
		if (capacityNanos <= 0L) return null;
		float requested = nanosToCellVolumeAtMost(capacityNanos);
		if (requested <= 0.0F) return new TransferOutcome(false, false,
				new ItemStack(MmmItems.MATERIAL_BUCKET), "容器余量小于 solver 可表示体积");
		ChemicalContents[] plannedContents = new ChemicalContents[1];
		long[] plannedNanos = new long[1];
		FluidDomain.ExactChemicalTransfer extracted = region.domain().tryExtractExactChemical(
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

	static TransferOutcome scoopMaterialIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell,
			long capacityNanos, int maxRadius
	) {
		if (capacityNanos <= 0L) return null;
		FluidDomain domain = region.domain();
		float requestedCellVolume = nanosToCellVolumeAtMost(capacityNanos);
		if (requestedCellVolume <= 0.0F) {
			return new TransferOutcome(false, false, new ItemStack(MmmItems.MATERIAL_BUCKET),
					"容器余量小于 solver 可表示体积");
		}
		FluidDomain.ExactMixtureTransfer extracted = domain.tryExtractExactMixture(
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

	static TransferOutcome pourGranularIntoCell(
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
		FluidDomain.GranularInsertion inserted = region.domain().tryInsertExactGranular(
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

	static TransferOutcome scoopGranularIntoCell(
			FluidSimulationService service, FluidRegion region, int[] cell, long capacitySvq, int maxRadius
	) {
		GranularContents[] planned = new GranularContents[1];
		FluidDomain.GranularExtraction extracted = region.domain().tryExtractExactGranular(
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
			FluidDomain domain, java.util.List<FluidDomain.MaterialPortion> portions, long capacityNanos
	) {
		double totalVolume = 0.0;
		for (FluidDomain.MaterialPortion portion : portions) totalVolume += portion.volume();
		long totalNanos = settledNanos(totalVolume, capacityNanos);
		long remainingNanos = totalNanos;
		double remainingVolume = totalVolume;
		java.util.List<FluidMixture.Component> components = new java.util.ArrayList<>(portions.size());
		for (int i = 0; i < portions.size(); i++) {
			FluidDomain.MaterialPortion portion = portions.get(i);
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

	static java.util.List<FluidDomain.MaterialPortion> planMaterialInsertion(
			FluidDomain domain, FluidMixture mixture
	) {
		java.util.List<FluidDomain.MaterialPortion> portions = new java.util.ArrayList<>(mixture.components().size());
		for (FluidMixture.Component component : mixture.components()) {
			int handle = domain.materialRegistry().handle(component.materialId());
			float plannedVolume = nanosToCellVolumeAtMost(component.volumeNanos());
			long plannedNanos = cellVolumeToNanos(plannedVolume);
			if (plannedNanos <= 0L) throw new IllegalArgumentException(
					"材料组分小于 solver 可表示体积：" + component.materialId());
			double plannedMassKilograms = plannedNanos == component.volumeNanos()
					? component.mass()
					: component.mass() * plannedNanos / component.volumeNanos();
			portions.add(new FluidDomain.MaterialPortion(
					handle, plannedVolume, domain.solverMassForKilograms(plannedMassKilograms)));
		}
		return java.util.List.copyOf(portions);
	}

	static MaterialInsertionSettlement settleMaterialInsertion(
			FluidDomain domain, FluidMixture sourceMixture,
			java.util.List<FluidDomain.MaterialPortion> insertedPortions
	) {
		if (insertedPortions.size() != sourceMixture.components().size()) {
			throw new IllegalStateException("材料注入返回的组分数与请求不一致");
		}
		java.util.List<FluidMixture.Component> remaining = new java.util.ArrayList<>();
		long insertedNanos = 0L;
		for (int i = 0; i < sourceMixture.components().size(); i++) {
			FluidMixture.Component source = sourceMixture.components().get(i);
			FluidDomain.MaterialPortion actual = insertedPortions.get(i);
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
	enum TransferKind {
		NONE,
		POUR,
		SCOOP,
		MATERIAL_POUR,
		MATERIAL_SCOOP,
		GRANULAR_POUR,
		GRANULAR_SCOOP
	}

	static String thermalTransferFailure(
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

	static ThermalContents thermalContents(ItemStack stack) {
		return ContainerItemStacks.thermalContents(stack);
	}

	static TransferKind classify(ItemStack stack) {
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

	static Long heldVolumeNanos(ItemStack stack) {
		if (stack.is(Items.WATER_BUCKET)) return ContainerFluidLedger.NANOS_PER_BLOCK;
		long volume = PartialWaterBucketItem.volume(stack);
		return volume > 0L ? volume : null;
	}

	static long heldBaseVolumeNanos(ItemStack stack) {
		if (stack.is(Items.BUCKET)) return 0L;
		return Math.max(0L, PartialWaterBucketItem.volume(stack));
	}

	static long heldFreeNanos(ItemStack stack) {
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
			FluidDomain domain, int[] cell, long capacityNanos, int maxRadius, int materialHandle
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
