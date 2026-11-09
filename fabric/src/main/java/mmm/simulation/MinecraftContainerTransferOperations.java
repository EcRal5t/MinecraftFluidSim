package mmm.simulation;

import mmm.fluid.FluidDomainContracts;

import mmm.units.SolverUnitScale;
import mmm.fluid.HostFluidState;
import mmm.transfer.ContainerFluidLedger;
import mmm.transfer.ChemicalContents;
import mmm.transfer.FluidMixture;
import mmm.item.GranularContainerItem;
import mmm.transfer.GranularContents;
import mmm.item.MaterialBucketItem;
import mmm.item.PartialWaterBucketItem;
import mmm.item.MmmItems;
import mmm.item.MmmComponents;
import mmm.item.ContainerItemStacks;
import mmm.transfer.LiquidContainerThermodynamics;
import mmm.transfer.ThermalContents;
import mmm.units.SignedDyadic;
import mmm.granular.GranularTransferLedger;
import mmm.granular.SolidVolume;
import mmm.worldprofile.WorldCapability;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;


/**
 * Minecraft-side exact transfer primitives shared by the player and dispenser adapters.
 * Actor targeting and output are deliberately outside this class.
 */
final class MinecraftContainerTransferOperations {
	private MinecraftContainerTransferOperations() { }

	static TransferOutcome pourIntoCell(
			CommittedWorldRegistries world, FluidRegion region, int[] cell, long volumeNanos, int maxRadius,
			int materialHandle, boolean externalSource, ThermalContents thermalContents
	) {
		return pourIntoCell(world, region, cell, volumeNanos, volumeNanos, maxRadius, materialHandle, externalSource, thermalContents);
	}

	static TransferOutcome pourIntoCell(
			CommittedWorldRegistries world, FluidRegion region, int[] cell, long volumeNanos, long maximumNanos,
			int maxRadius, int materialHandle, boolean externalSource, ThermalContents thermalContents
	) {
		if (volumeNanos <= 0L) return null;
		HostFluidState domain = region.editableHost();
		float requestedCellVolume = nanosToCellVolumeAtMost(domain.unitScale(), Math.min(volumeNanos, maximumNanos));
		if (requestedCellVolume <= 0.0F) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "容器余量小于 solver 可表示体积，未能注入");
		}
		if (domain.totalOpenVolume() - domain.totalVolume() + 1.0E-6F < requestedCellVolume) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "空间或 marker 预算不足，未能注入");
		}
		long plannedNanos = cellVolumeToNanos(domain.unitScale(), requestedCellVolume);
		ThermalContents.EnergySplit thermalSplit = thermalContents == null ? null
				: thermalContents.splitHomogeneousEnergy(plannedNanos, volumeNanos);
		Double coupledEnergy = thermalSplit == null ? null
				: thermalSplit.coupledEnergy(thermalContents.internalEnergy());
		FluidDomainContracts.ExactVolumeTransfer inserted = thermalSplit == null
				? domain.tryInsertExactVolume(cell[0], cell[1], cell[2], requestedCellVolume, maxRadius,
						materialHandle, externalSource)
				: domain.tryInsertExactVolume(cell[0], cell[1], cell[2], requestedCellVolume, maxRadius,
						materialHandle, externalSource, thermalSplit.selectedEnergy());
		if (!inserted.transferred()) {
			return new TransferOutcome(false, true, withVolume(volumeNanos), "空间或 marker 预算不足，未能注入");
		}
		long poured = settledNanos(domain.unitScale(), inserted.volume(), volumeNanos);
		if (poured != plannedNanos) throw new IllegalStateException("Thermal insertion settlement changed after planning");
		if (thermalSplit != null) domain.settleExternalContainerInsertion(thermalSplit.selectedEnergy(), coupledEnergy);
		ItemStack after = withVolume(volumeNanos - poured);
		if (thermalSplit != null && volumeNanos > poured) ContainerItemStacks.attachThermal(
				after, world.thermalRegistryFingerprint(), thermalSplit.remainingEnergy());
		region.wakeForActivity();
		if (thermalSplit != null) region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		return new TransferOutcome(true, true, after,
				String.format("已注入 %.2f%% 桶", 100.0 * poured / ContainerFluidLedger.NANOS_PER_BLOCK), poured,
				coupledEnergy);
	}

	static TransferOutcome scoopFromCell(
			CommittedWorldRegistries world, FluidRegion region, int[] cell, long capacityNanos, long baseVolumeNanos,
			int maxRadius, int materialHandle, ThermalContents baseThermal
	) {
		if (capacityNanos <= 0L) return null;
		HostFluidState domain = region.editableHost();
		float requestedCellVolume = nanosToCellVolumeAtMost(domain.unitScale(), capacityNanos);
		if (requestedCellVolume <= 0.0F) {
			return new TransferOutcome(false, false, withVolume(baseVolumeNanos), "容器余量小于 solver 可表示体积");
		}
		SignedDyadic energyBefore = domain.hasThermalState() ? domain.exactInternalEnergy().subtract(domain.exactGasInternalEnergy()) : null;
		FluidDomainContracts.ExactVolumeTransfer extracted = domain.tryExtractExactVolume(
				cell[0], cell[1], cell[2], requestedCellVolume, maxRadius, materialHandle);
		if (!extracted.transferred() || extracted.volume() <= 0.0) {
			return new TransferOutcome(false, false, withVolume(baseVolumeNanos), "准星处没有足够水体");
		}
		long taken = settledNanos(domain.unitScale(), extracted.volume(), capacityNanos);
		ItemStack after = withVolume(baseVolumeNanos + taken);
		Double thermalEnergy = null;
		if (domain.hasThermalState()) {
			double baseEnergy = baseThermal == null ? 0.0 : baseThermal.internalEnergy();
			double finalEnergy = mergedExtractionEnergy(baseEnergy, energyBefore.subtract(domain.exactInternalEnergy().subtract(domain.exactGasInternalEnergy())));
			ContainerItemStacks.attachThermal(after, world.thermalRegistryFingerprint(), finalEnergy);
			thermalEnergy = SignedDyadic.of(finalEnergy).subtract(SignedDyadic.of(baseEnergy)).toDouble();
			domain.settleExternalContainerExtraction(extracted.internalEnergy(), thermalEnergy);
		}
		region.wakeForActivity();
		if (thermalEnergy != null) region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		return new TransferOutcome(true, false, after,
				String.format("已舀取 %.2f%% 桶", 100.0 * taken / ContainerFluidLedger.NANOS_PER_BLOCK), taken,
				thermalEnergy);
	}


	static double mergedExtractionEnergy(double baseEnergy, SignedDyadic removedCarrierEnergy) {
		return SignedDyadic.of(baseEnergy).add(removedCarrierEnergy).toDouble();
	}

	static TransferOutcome pourMaterialIntoCell(
			CommittedWorldRegistries world, FluidRegion region, int[] cell, FluidMixture mixture, int maxRadius,
			boolean externalSource, ThermalContents thermalContents
	) {
		return pourMaterialIntoCell(world, region, cell, mixture, mixture.totalVolumeNanos(), maxRadius, externalSource, thermalContents);
	}

	static TransferOutcome pourMaterialIntoCell(
			CommittedWorldRegistries world, FluidRegion region, int[] cell, FluidMixture mixture, long maximumNanos,
			int maxRadius, boolean externalSource, ThermalContents thermalContents
	) {
		if (mixture == null) return null;
		HostFluidState domain = region.editableHost();
		java.util.List<FluidDomainContracts.MaterialPortion> portions;
		try {
			portions = planMaterialInsertion(domain, mixture, maximumNanos);
		} catch (IllegalArgumentException unknownMaterial) {
			return new TransferOutcome(false, true, ContainerItemStacks.toStack(mixture),
					"区域材料 registry 不接受该混合物：" + unknownMaterial.getMessage());
		}
		MaterialInsertionSettlement plannedSettlement = settleMaterialInsertion(domain, mixture, portions);
		long plannedNanos = plannedSettlement.insertedNanos();
		ThermalContents.EnergySplit thermalSplit = thermalContents == null ? null
				: LiquidContainerThermodynamics.split(world.thermalRegistry(), world.hasCapability(mmm.worldprofile.WorldCapability.CHEMISTRY) ? world.chemicalRegistry() : null, thermalContents, mixture,
						plannedSettlement.inserted(), plannedSettlement.remaining());
		Double coupledEnergy = thermalSplit == null ? null
				: thermalSplit.coupledEnergy(thermalContents.internalEnergy());
		FluidDomainContracts.ExactMixtureTransfer inserted = thermalSplit == null
				? domain.tryInsertExactMixture(cell[0], cell[1], cell[2], portions, maxRadius, externalSource)
				: domain.tryInsertExactMixture(cell[0], cell[1], cell[2], portions, maxRadius, externalSource,
						thermalSplit.selectedEnergy());
		if (!inserted.transferred()) {
			return new TransferOutcome(false, true, ContainerItemStacks.toStack(mixture), "空间或 marker 预算不足，未能注入混合物");
		}
		MaterialInsertionSettlement settlement = settleMaterialInsertion(domain, mixture, inserted.portions());
		if (!settlement.equals(plannedSettlement)) {
			throw new IllegalStateException("Thermal material settlement changed after planning");
		}
		if (thermalSplit != null) domain.settleExternalContainerInsertion(thermalSplit.selectedEnergy(), coupledEnergy);
		ItemStack after = settlement.remaining() == null
				? new ItemStack(MmmItems.MATERIAL_BUCKET)
				: ContainerItemStacks.toStack(settlement.remaining());
		if (thermalSplit != null && settlement.remaining() != null) ContainerItemStacks.attachThermal(
				after, world.thermalRegistryFingerprint(), thermalSplit.remainingEnergy());
		region.wakeForActivity();
		if (thermalSplit != null) region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		return new TransferOutcome(true, true, after,
				String.format("已注入 %.2f%% 桶（%d 种材料）",
						100.0 * settlement.insertedNanos() / ContainerFluidLedger.NANOS_PER_BLOCK,
						mixture.components().size()), settlement.insertedNanos(),
				coupledEnergy);
	}

	static TransferOutcome pourChemicalIntoCell(
			CommittedWorldRegistries world, FluidRegion region, int[] cell, ChemicalContents contents, int maxRadius,
			boolean externalSource, ThermalContents thermalContents
	) {
		return pourChemicalIntoCell(world, region, cell, contents, contents.totalVolumeNanos(), maxRadius, externalSource, thermalContents);
	}

	static TransferOutcome pourChemicalIntoCell(
			CommittedWorldRegistries world, FluidRegion region, int[] cell, ChemicalContents contents, long maximumNanos,
			int maxRadius, boolean externalSource, ThermalContents thermalContents
	) {
		HostFluidState domain = region.editableHost();
		if (!domain.hasChemistryState()) return new TransferOutcome(false, true, ContainerItemStacks.toStack(contents), "该区域不是化学区域");
		try {
			contents.validateAgainst(domain.chemistry().registry());
		} catch (IllegalArgumentException invalid) {
			return new TransferOutcome(false, true, ContainerItemStacks.toStack(contents), "化学桶 registry 或账本不合法：" + invalid.getMessage());
		}
		double spatialCellVolume = Math.max(0.0, domain.totalOpenVolumeExact() - domain.totalVolumeExact());
		long candidate = Math.min(maximumNanos, Math.min(contents.totalVolumeNanos(), cellVolumeToNanos(domain.unitScale(), spatialCellVolume)));
		while (candidate > 0L) {
			ChemicalContents.Split split;
			try {
				split = contents.split(candidate, domain.chemistry().registry());
			} catch (IllegalArgumentException belowResolution) {
				candidate /= 2L;
				continue;
			}
			mmm.chemistry.ChemicalTransferLedger requested = split.inserted().toLedger(
					domain.chemistry().registry(), domain.unitScale());
			ThermalContents.EnergySplit thermalSplit = thermalContents == null ? null
					: LiquidContainerThermodynamics.split(world.thermalRegistry(), thermalContents, contents,
							split.inserted(), split.remaining());
			Double coupledEnergy = thermalSplit == null ? null
					: thermalSplit.coupledEnergy(thermalContents.internalEnergy());
			FluidDomainContracts.ExactChemicalTransfer inserted = thermalSplit == null
					? domain.tryInsertExactChemical(cell[0], cell[1], cell[2], requested, maxRadius, externalSource)
					: domain.tryInsertExactChemical(cell[0], cell[1], cell[2], requested, maxRadius,
							externalSource, thermalSplit.selectedEnergy());
			if (!inserted.transferred()) {
				candidate /= 2L;
				continue;
			}
			if (thermalSplit != null) domain.settleExternalContainerInsertion(thermalSplit.selectedEnergy(), coupledEnergy);
			region.wakeForActivity();
			ItemStack after = split.remaining() == null
					? new ItemStack(MmmItems.MATERIAL_BUCKET) : ContainerItemStacks.toStack(split.remaining());
			if (thermalSplit != null && split.remaining() != null) ContainerItemStacks.attachThermal(
					after, world.thermalRegistryFingerprint(), thermalSplit.remainingEnergy());
			if (thermalSplit != null) region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
			return new TransferOutcome(true, true, after,
					String.format("已注入 %.2f%% 桶化学流体", 100.0 * candidate
							/ ContainerFluidLedger.NANOS_PER_BLOCK), candidate,
					coupledEnergy);
		}
		return new TransferOutcome(false, true, ContainerItemStacks.toStack(contents), "空间、marker 或化学预算不足，未能注入");
	}

	static TransferOutcome scoopChemicalIntoCell(
			CommittedWorldRegistries world, FluidRegion region, int[] cell, long capacityNanos, int maxRadius,
			mmm.chemistry.ChemicalRegistry registry
	) {
		if (capacityNanos <= 0L) return null;
		HostFluidState domain = region.editableHost();
		float requested = nanosToCellVolumeAtMost(domain.unitScale(), capacityNanos);
		if (requested <= 0.0F) return new TransferOutcome(false, false,
				new ItemStack(MmmItems.MATERIAL_BUCKET), "容器余量小于 solver 可表示体积");
		ChemicalContents[] plannedContents = new ChemicalContents[1];
		long[] plannedNanos = new long[1];
		String[] rejected = new String[1];
		FluidDomainContracts.ExactChemicalTransfer extracted = domain.tryExtractExactChemical(
				cell[0], cell[1], cell[2], requested, maxRadius, ledger -> {
					try {
						plannedNanos[0] = settledNanos(domain.unitScale(), ledger.totalNormalizedVolume(), capacityNanos);
						plannedContents[0] = ChemicalContents.fromLedger(ledger, registry, plannedNanos[0]);
						return true;
					} catch (IllegalArgumentException | ArithmeticException invalidBucket) {
						rejected[0] = invalidBucket.getMessage();
						return false;
					}
				});
		if (!extracted.transferred() || extracted.ledger().isEmpty()) return new TransferOutcome(false, false,
				new ItemStack(MmmItems.MATERIAL_BUCKET), "准星处没有足够或可由当前容器表示的化学流体"
						+ (rejected[0] == null ? "" : "：" + rejected[0]));
		long taken = plannedNanos[0];
		ChemicalContents contents = plannedContents[0];
		Double thermalEnergy = domain.hasThermalState() ? extracted.internalEnergy() : null;
		ItemStack after = ContainerItemStacks.toStack(contents);
		if (thermalEnergy != null) ContainerItemStacks.attachThermal(after, world.thermalRegistryFingerprint(), thermalEnergy);
		region.wakeForActivity();
		if (thermalEnergy != null) region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		return new TransferOutcome(true, false, after,
				String.format("已舀取 %.2f%% 桶化学流体（%d 组）", 100.0 * taken
						/ ContainerFluidLedger.NANOS_PER_BLOCK, contents.groups().size()), taken, thermalEnergy);
	}

	static TransferOutcome scoopMaterialIntoCell(
			CommittedWorldRegistries world, FluidRegion region, int[] cell,
			long capacityNanos, int maxRadius
	) {
		if (capacityNanos <= 0L) return null;
		HostFluidState domain = region.editableHost();
		float requestedCellVolume = nanosToCellVolumeAtMost(domain.unitScale(), capacityNanos);
		if (requestedCellVolume <= 0.0F) {
			return new TransferOutcome(false, false, new ItemStack(MmmItems.MATERIAL_BUCKET),
					"容器余量小于 solver 可表示体积");
		}
		FluidDomainContracts.ExactMixtureTransfer extracted = domain.tryExtractExactMixture(
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
			ContainerItemStacks.attachThermal(after, world.thermalRegistryFingerprint(), thermalEnergy);
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
			CommittedWorldRegistries world, FluidRegion region, int[] cell, GranularContents contents,
			int maxRadius, boolean externalSource
	) {
		return pourGranularIntoCell(world, region, cell, contents, contents.totalSolidVolumeSvq(), maxRadius, externalSource, null);
	}

	static TransferOutcome pourGranularIntoCell(
			CommittedWorldRegistries world, FluidRegion region, int[] cell, GranularContents contents,
			long maximumSvq, int maxRadius, boolean externalSource, ThermalContents thermalContents
	) {
		var chemistry = world.hasCapability(WorldCapability.CHEMISTRY) ? world.chemicalRegistry() : null;
		GranularContents.Split split;
		GranularTransferLedger ledger;
		ThermalContents.EnergySplit energies;
		try {
			split = contents.split(Math.min(maximumSvq, contents.totalSolidVolumeSvq()), chemistry);
			ledger = split.inserted().toLedger(world.granularRegistry(), chemistry);
			energies = thermalContents == null ? null : LiquidContainerThermodynamics.split(
					world.thermalRegistry(), thermalContents, contents, split.inserted(), split.remaining());
		} catch (RuntimeException invalid) {
			return new TransferOutcome(false, true, ContainerItemStacks.toStack(contents),
					"颗粒容器 registry、组成或热状态无法表示本次分量：" + invalid.getMessage());
		}
		var domain = region.editableHost();
		FluidDomainContracts.GranularInsertion inserted = energies == null
				? domain.tryInsertExactGranular(cell[0], cell[1], cell[2], ledger, maxRadius, externalSource)
				: domain.tryInsertExactGranular(cell[0], cell[1], cell[2], ledger, maxRadius, externalSource, energies.selectedEnergy());
		if (!inserted.inserted()) {
			return new TransferOutcome(false, true, ContainerItemStacks.toStack(contents),
					"空间、颗粒 parcel 或组成预算不足，未能注入：" + inserted.failure());
		}
		if (inserted.solidVolumeSvq() != split.inserted().totalSolidVolumeSvq()
				|| inserted.massUnits() != split.inserted().totalMassUnits())
			throw new IllegalStateException("颗粒注入没有结算完整独立库存");
		ItemStack after = split.remaining() == null ? new ItemStack(MmmItems.GRANULAR_CONTAINER)
				: ContainerItemStacks.toStack(split.remaining());
		Double coupled = energies == null ? null : energies.coupledEnergy(thermalContents.internalEnergy());
		if (energies != null) {
			domain.settleExternalContainerInsertion(energies.selectedEnergy(), coupled);
			if (split.remaining() != null) ContainerItemStacks.attachThermal(after, world.thermalRegistryFingerprint(), energies.remainingEnergy());
			region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		}
		region.wakeForActivity();
		return new TransferOutcome(true, true, after,
				String.format("已注入 %.2f%% 干式容器（%d 种材料）",
						100.0 * inserted.solidVolumeSvq() / SolidVolume.SVQ_PER_CUBIC_METER, split.inserted().groups().size()),
				inserted.solidVolumeSvq(), coupled);
	}

	static TransferOutcome scoopGranularIntoCell(
			CommittedWorldRegistries world, FluidRegion region, int[] cell, long capacitySvq, int maxRadius
	) {
		GranularContents[] planned = new GranularContents[1];
		FluidDomainContracts.GranularExtraction extracted = region.editableHost().tryExtractExactGranular(
				cell[0], cell[1], cell[2], capacitySvq, maxRadius, false, ledger -> {
					try {
						planned[0] = GranularContents.fromLedger(ledger, world.granularRegistry(),
								world.hasCapability(WorldCapability.CHEMISTRY) ? world.chemicalRegistry() : null);
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
		ItemStack after = ContainerItemStacks.toStack(planned[0]);
		Double carriedEnergy = region.editableHost().hasThermalState() ? extracted.internalEnergy() : null;
		if (carriedEnergy != null) {
			ContainerItemStacks.attachThermal(after, world.thermalRegistryFingerprint(), carriedEnergy);
			region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		}
		region.wakeForActivity();
		return new TransferOutcome(true, false, after,
				String.format("已舀取 %.2f%% 干式容器（%d 种材料）",
						100.0 * taken / SolidVolume.SVQ_PER_CUBIC_METER, planned[0].groups().size()), taken, carriedEnergy);
	}

	static TransferOutcome pourGas(CommittedWorldRegistries world, FluidRegion region, int[] cell, mmm.transfer.GasContents contents, long requested) {
		var chemistry=world.hasCapability(WorldCapability.CHEMISTRY)?world.chemicalRegistry():null;
		var thermal=new mmm.thermal.GasThermalModel(world.thermalRegistry(),world.gasRegistry());
		var split=contents.split(Math.min(requested,contents.inventory().standardVolumeNanos()),world.gasRegistry(),chemistry,thermal);
		var selected=split.selected();
		if(selected==null)return new TransferOutcome(false,true,ContainerItemStacks.toStack(contents),"当前配额小于可分配气体库存");
		var domain=region.editableHost();int index=domain.grid().cell(cell[0],cell[1],cell[2]);
		if(index<0)return new TransferOutcome(false,true,ContainerItemStacks.toStack(contents),"目标气格不可用");
		var moved=domain.insertGas(index+1,selected.inventory(),selected.internalEnergy());
		double remaining=split.remaining()==null?0:split.remaining().internalEnergy();
		double coupled=SignedDyadic.of(contents.internalEnergy()).subtract(SignedDyadic.of(remaining)).toDouble();
		domain.settleExternalContainerInsertion(selected.internalEnergy(),coupled);
		ItemStack after=split.remaining()==null?new ItemStack(MmmItems.GAS_CONTAINER):ContainerItemStacks.toStack(split.remaining());
		region.wakeForActivity();region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		return new TransferOutcome(true,true,after,"已注入气体",moved.moved().standardVolumeNanos(),coupled);
	}
	static TransferOutcome scoopGas(CommittedWorldRegistries world, FluidRegion region, int[] cell, long requested) {
		var domain=region.editableHost();int index=domain.grid().cell(cell[0],cell[1],cell[2]);
		if(index<0)return new TransferOutcome(false,false,new ItemStack(MmmItems.GAS_CONTAINER),"目标气格不可用");
		var moved=domain.editGas(index+1,null,requested,0,true);
		if(moved.moved().isEmpty())return new TransferOutcome(false,false,new ItemStack(MmmItems.GAS_CONTAINER),"目标气格没有气体");
		var contents=new mmm.transfer.GasContents(world.gasRegistry().fingerprint(),world.hasCapability(WorldCapability.CHEMISTRY)?world.chemicalRegistry().fingerprint():"",moved.moved(),moved.internalEnergy().toDouble());
		region.wakeForActivity();region.wakeThermal(ThermalActivity.WakeReason.CONTAINER_TRANSFER);
		return new TransferOutcome(true,false,ContainerItemStacks.toStack(contents),"已装取气体",moved.moved().standardVolumeNanos(),contents.internalEnergy());
	}

	/** Deterministic largest-remainder-style apportionment; component masses remain exact doubles. */
	static FluidMixture mixtureFromExtraction(
			HostFluidState domain, java.util.List<FluidDomainContracts.MaterialPortion> portions, long capacityNanos
	) {
		double totalVolume = 0.0;
		for (FluidDomainContracts.MaterialPortion portion : portions) totalVolume += portion.volume();
		long totalNanos = settledNanos(domain.unitScale(), totalVolume, capacityNanos);
		long remainingNanos = totalNanos;
		double remainingVolume = totalVolume;
		java.util.List<FluidMixture.Component> components = new java.util.ArrayList<>(portions.size());
		for (int i = 0; i < portions.size(); i++) {
			FluidDomainContracts.MaterialPortion portion = portions.get(i);
			int componentsLeft = portions.size() - i;
			long nanos = componentsLeft == 1 ? remainingNanos
					: Math.max(1L, Math.min(remainingNanos - (componentsLeft - 1L),
					Math.round(remainingNanos * portion.volume() / remainingVolume)));
			String materialId = domain.materialRegistry().material(portion.materialHandle()).id();
			components.add(new FluidMixture.Component(
					materialId, nanos, portion.massUnits()));
			remainingNanos -= nanos;
			remainingVolume -= portion.volume();
		}
		return new FluidMixture(components);
	}

	static java.util.List<FluidDomainContracts.MaterialPortion> planMaterialInsertion(
			HostFluidState domain, FluidMixture mixture
	) {
		return planMaterialInsertion(domain, mixture, mixture.totalVolumeNanos());
	}

	static java.util.List<FluidDomainContracts.MaterialPortion> planMaterialInsertion(
			HostFluidState domain, FluidMixture mixture, long maximumNanos
	) {
		long selected = Math.min(maximumNanos, mixture.totalVolumeNanos());
		double[] weights = mixture.components().stream().mapToDouble(FluidMixture.Component::volumeNanos).toArray();
		int[] ids = new int[weights.length];
		for (int i = 0; i < ids.length; i++) ids[i] = i;
		long[] nanos = mmm.chemistry.BinaryVolumeAllocator.allocate(selected, weights, ids);
		int index = 0;
		java.util.List<FluidDomainContracts.MaterialPortion> portions = new java.util.ArrayList<>(mixture.components().size());
		for (FluidMixture.Component component : mixture.components()) {
			int handle = domain.materialRegistry().handle(component.materialId());
			float plannedVolume = nanosToCellVolumeAtMost(domain.unitScale(), nanos[index++]);
			long plannedNanos = cellVolumeToNanos(domain.unitScale(), plannedVolume);
			if (plannedNanos <= 0L) throw new IllegalArgumentException(
					"材料组分小于 solver 可表示体积：" + component.materialId());
			long plannedMassUnits = mmm.units.Mass.portion(component.massUnits(), plannedNanos, component.volumeNanos());
			portions.add(new FluidDomainContracts.MaterialPortion(
					handle, plannedVolume, plannedMassUnits));
		}
		return java.util.List.copyOf(portions);
	}

	static MaterialInsertionSettlement settleMaterialInsertion(
			HostFluidState domain, FluidMixture sourceMixture,
			java.util.List<FluidDomainContracts.MaterialPortion> insertedPortions
	) {
		if (insertedPortions.size() != sourceMixture.components().size()) {
			throw new IllegalStateException("材料注入返回的组分数与请求不一致");
		}
		java.util.List<FluidMixture.Component> inserted = new java.util.ArrayList<>();
		java.util.List<FluidMixture.Component> remaining = new java.util.ArrayList<>();
		long insertedNanos = 0L;
		for (int i = 0; i < sourceMixture.components().size(); i++) {
			FluidMixture.Component source = sourceMixture.components().get(i);
			FluidDomainContracts.MaterialPortion actual = insertedPortions.get(i);
			long componentNanos = settledNanos(domain.unitScale(), actual.volume(), source.volumeNanos());
			insertedNanos = Math.addExact(insertedNanos, componentNanos);
			long remainingNanos = source.volumeNanos() - componentNanos;
			long actualMassUnits = actual.massUnits();
			inserted.add(new FluidMixture.Component(source.materialId(), componentNanos, actualMassUnits));
			long remainingMass = Math.subtractExact(source.massUnits(), actualMassUnits);
			if (remainingNanos == 0L) {
				if (actualMassUnits != source.massUnits()) {
					throw new IllegalStateException("完整材料组分注入未结算其全部质量：" + source.materialId());
				}
			} else {
				if (remainingMass <= 0L) {
					throw new IllegalStateException("材料组分余量没有正有限质量：" + source.materialId());
				}
				remaining.add(new FluidMixture.Component(source.materialId(), remainingNanos, remainingMass));
			}
		}
		return new MaterialInsertionSettlement(insertedNanos, new FluidMixture(inserted),
				remaining.isEmpty() ? null : new FluidMixture(remaining));
	}

	record MaterialInsertionSettlement(long insertedNanos, FluidMixture inserted, FluidMixture remaining) { }
	enum TransferKind {
		NONE,
		POUR,
		SCOOP,
		MATERIAL_POUR,
		MATERIAL_SCOOP,
		GRANULAR_POUR,
		GRANULAR_SCOOP
		, GAS_POUR, GAS_SCOOP
	}
	static mmm.api.transfer.TransferQuantity contactQuantity(TransferKind kind) {
		return switch(kind) {
			case GAS_POUR, GAS_SCOOP -> new mmm.api.transfer.TransferQuantity.Gas(1,0);
			case GRANULAR_POUR, GRANULAR_SCOOP -> new mmm.api.transfer.TransferQuantity.Granular(1);
			default -> new mmm.api.transfer.TransferQuantity.Liquid(1,java.util.OptionalDouble.empty());
		};
	}

	static String thermalTransferFailure(
			CommittedWorldRegistries world, FluidRegion region, ItemStack stack, TransferKind kind
	) {
		if (kind == TransferKind.NONE) return null;
		if (kind == TransferKind.GAS_POUR || kind == TransferKind.GAS_SCOOP) return region.hasThermalState()?null:"气体转移需要热区域";
		ThermalContents contents;
		try {
			contents = ContainerItemStacks.thermalContents(stack);
		} catch (RuntimeException invalid) {
			return "容器热状态损坏或与物质库存不一致；转移保持不变";
		}
		if (!region.hasThermalState()) {
			return contents == null ? null : "带热真值的容器不能转入未启用热状态的区域";
		}
		boolean sourceRequired = kind == TransferKind.POUR || kind == TransferKind.MATERIAL_POUR || kind == TransferKind.GRANULAR_POUR
				|| kind == TransferKind.SCOOP && heldBaseVolumeNanos(stack) > 0L;
		if (sourceRequired && contents == null && !stack.is(Items.WATER_BUCKET)) {
			return "热区域只接受携带 ThermalContents 的液体容器；拒绝猜测初始温度";
		}
		if (contents != null) try {
			contents.validateRegistryFingerprint(world.thermalRegistryFingerprint());
		} catch (RuntimeException foreign) {
			return "容器 thermal registry 与当前世界不一致；转移保持不变";
		}
		return null;
	}

	static ThermalContents thermalContents(ItemStack stack) {
		return ContainerItemStacks.thermalContents(stack);
	}

	static TransferKind classify(ItemStack stack) {
		if (stack.getItem() instanceof mmm.item.GasContainerItem) return mmm.item.GasContainerItem.isEmpty(stack) ? TransferKind.GAS_SCOOP : TransferKind.GAS_POUR;
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

	static float nanosToCellVolume(SolverUnitScale units, long nanos) {
		return (float) units.normalizedVolume(ContainerFluidLedger.nanosToCubicMeters(nanos));
	}

	/** Largest float cell volume whose nanovolume settlement does not exceed the source/capacity. */
	static float nanosToCellVolumeAtMost(SolverUnitScale units, long nanos) {
		if (nanos <= 0L) return 0.0F;
		float volume = nanosToCellVolume(units, nanos);
		while (volume > 0.0F && cellVolumeToNanos(units, volume) > nanos) volume = Math.nextDown(volume);
		return volume;
	}

	static long cellVolumeToNanos(SolverUnitScale units, double cellVolume) {
		return ContainerFluidLedger.cubicMetersToNanos(units.cubicMeters(cellVolume));
	}

	/** Converts the primitive's authoritative marker delta and rejects impossible item overshoot. */
	static long settledNanos(SolverUnitScale units, double actualCellVolume, long maximumNanos) {
		if (maximumNanos <= 0L) throw new IllegalArgumentException("Settlement limit must be positive");
		long actualNanos = cellVolumeToNanos(units, actualCellVolume);
		if (actualNanos <= 0L || actualNanos > maximumNanos) {
			throw new IllegalStateException("Solver transfer settled " + actualNanos
					+ " nanovolume quanta outside 1.." + maximumNanos);
		}
		return actualNanos;
	}

	static long extractableNanos(
			HostFluidState domain, int[] cell, long capacityNanos, int maxRadius, int materialHandle
	) {
		if (capacityNanos <= 0L) return 0L;
		double availableCellVolume = domain.containerExtractableVolumeNear(
				cell[0], cell[1], cell[2], maxRadius, materialHandle);
		if (!(availableCellVolume > 0.0)) return 0L;
		long availableNanos = cellVolumeToNanos(domain.unitScale(), availableCellVolume);
		long request = Math.min(capacityNanos, availableNanos);
		// The transaction stores nanos and reconstructs the float volume during execution.
		// Check that reconstructed value against inventory, retaining any smaller residue in the region.
		while (request > 0L && nanosToCellVolumeAtMost(domain.unitScale(), request) > availableCellVolume) request--;
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
