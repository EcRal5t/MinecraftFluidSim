package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.item.ContainerItemStacks;
import cc.ecisr.mmm.item.CreativeSourceItem;
import cc.ecisr.mmm.item.GranularContainerItem;
import cc.ecisr.mmm.item.MaterialBucketItem;
import cc.ecisr.mmm.item.MmmComponents;
import cc.ecisr.mmm.item.MmmItems;
import cc.ecisr.mmm.item.PartialWaterBucketItem;
import cc.ecisr.mmm.transfer.ChemicalContents;
import cc.ecisr.mmm.transfer.ContainerTransferJournal;
import cc.ecisr.mmm.transfer.FluidMixture;
import cc.ecisr.mmm.transfer.GranularContents;
import cc.ecisr.mmm.transfer.CreativeSourceDescriptor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Minecraft item mapping for the data-only before/after snapshots stored in a container WAL. */
final class ContainerTransferItems {
	private ContainerTransferItems() { }

	static ContainerTransferJournal.ItemFingerprint fingerprint(ItemStack stack) {
		if (stack.getCount() != 1) {
			throw new IllegalArgumentException("控制器容器事务一次只接受 1 个物品；堆叠桶保持不变");
		}
		if (stack.is(Items.BUCKET)) return withThermal(stack, snapshot(ContainerTransferJournal.ItemKind.BUCKET));
		if (stack.is(Items.WATER_BUCKET)) {
			return withThermal(stack, snapshot(ContainerTransferJournal.ItemKind.WATER_BUCKET));
		}
		if (stack.getItem() instanceof PartialWaterBucketItem) {
			long nanos = PartialWaterBucketItem.volume(stack);
			if (nanos <= 0L) throw new IllegalArgumentException("Partial bucket has no valid volume");
			return withThermal(stack, new ContainerTransferJournal.ItemFingerprint(
					ContainerTransferJournal.ItemKind.PARTIAL, nanos, null));
		}
		if (stack.getItem() instanceof MaterialBucketItem) return materialFingerprint(stack);
		if (stack.getItem() instanceof GranularContainerItem) return granularFingerprint(stack);
		if (stack.getItem() instanceof CreativeSourceItem) {
			CreativeSourceDescriptor descriptor = CreativeSourceItem.descriptor(stack);
			if (descriptor == null) throw new IllegalArgumentException("Creative source descriptor is invalid");
			return new ContainerTransferJournal.ItemFingerprint(
					ContainerTransferJournal.ItemKind.CREATIVE_SOURCE, -1L, -1L,
					descriptor.encode(), -1L, null, null);
		}
		throw new IllegalArgumentException("Unsupported container item for transfer WAL: " + stack.getItem());
	}

	static ItemStack toStack(ContainerTransferJournal.ItemFingerprint snapshot) {
		ItemStack stack = switch (snapshot.kind()) {
			case BUCKET -> new ItemStack(Items.BUCKET);
			case WATER_BUCKET -> new ItemStack(Items.WATER_BUCKET);
			case PARTIAL -> PartialWaterBucketItem.withVolume(snapshot.nanos());
			case EMPTY_MATERIAL_BUCKET -> new ItemStack(MmmItems.MATERIAL_BUCKET);
			case MATERIAL_BUCKET -> materialStack(snapshot);
			case CHEMICAL_BUCKET -> chemicalStack(snapshot);
			case EMPTY_GRANULAR_CONTAINER -> new ItemStack(MmmItems.GRANULAR_CONTAINER);
			case GRANULAR_CONTAINER -> granularStack(snapshot);
			case CREATIVE_SOURCE -> CreativeSourceItem.stack(CreativeSourceDescriptor.decode(snapshot.contents()));
		};
		if (snapshot.profileFingerprint() != null) {
			stack.set(MmmComponents.PROFILE_FINGERPRINT, snapshot.profileFingerprint());
		}
		if (snapshot.thermalContents() != null) {
			stack.set(MmmComponents.THERMAL_CONTENTS, snapshot.thermalContents());
			ContainerItemStacks.thermalContents(stack);
		}
		return stack;
	}

	private static ContainerTransferJournal.ItemFingerprint materialFingerprint(ItemStack stack) {
		if (MaterialBucketItem.isEmptyMaterialBucket(stack)) {
			return withThermal(stack, snapshot(ContainerTransferJournal.ItemKind.EMPTY_MATERIAL_BUCKET));
		}
		long nanos = MaterialBucketItem.volume(stack);
		String chemicalEncoded = stack.get(MmmComponents.CHEMICAL_CONTENTS);
		if (chemicalEncoded != null) {
			ChemicalContents contents = ChemicalContents.decode(chemicalEncoded);
			Long persistedMass = stack.get(MmmComponents.FLUID_MASS_BITS);
			if (stack.get(MmmComponents.MATERIAL_ID) != null
					|| stack.get(MmmComponents.MIXTURE_COMPONENTS) != null
					|| nanos != contents.totalVolumeNanos() || persistedMass == null
					|| persistedMass != Double.doubleToRawLongBits(contents.totalMass())) {
				throw new IllegalArgumentException("Chemical bucket has conflicting or mismatched ledgers");
			}
			return withThermal(stack, actualFingerprint(stack, ContainerTransferJournal.ItemKind.CHEMICAL_BUCKET,
					nanos, -1L, chemicalEncoded, persistedMass));
		}
		FluidMixture mixture = MaterialBucketItem.mixture(stack);
		double mass = MaterialBucketItem.mass(stack);
		if (nanos <= 0L || mixture == null || !(mass > 0.0)) {
			throw new IllegalArgumentException("Material bucket has no valid volume/material/mass ledger");
		}
		return withThermal(stack, actualFingerprint(stack, ContainerTransferJournal.ItemKind.MATERIAL_BUCKET,
				nanos, -1L, mixture.encode(), Double.doubleToRawLongBits(mass)));
	}

	private static ContainerTransferJournal.ItemFingerprint granularFingerprint(ItemStack stack) {
		if (GranularContainerItem.isEmpty(stack)) {
			return withThermal(stack, snapshot(ContainerTransferJournal.ItemKind.EMPTY_GRANULAR_CONTAINER));
		}
		GranularContents contents = GranularContainerItem.unvalidatedContents(stack);
		if (contents == null) {
			throw new IllegalArgumentException("Granular container has invalid or conflicting contents");
		}
		return withThermal(stack, actualFingerprint(stack, ContainerTransferJournal.ItemKind.GRANULAR_CONTAINER,
				-1L, contents.totalSolidVolumeSvq(), contents.encode(), Double.doubleToRawLongBits(contents.totalMass())));
	}

	private static ContainerTransferJournal.ItemFingerprint actualFingerprint(
			ItemStack stack, ContainerTransferJournal.ItemKind kind, long nanos, long svq, String contents, long massBits
	) {
		String profile = ContainerItemStacks.profileFingerprint(stack);
		if (profile == null) throw new IllegalArgumentException("Filled typed container lacks world profile identity");
		return new ContainerTransferJournal.ItemFingerprint(kind, nanos, svq, contents, massBits, null, profile);
	}

	private static ContainerTransferJournal.ItemFingerprint withThermal(
			ItemStack stack, ContainerTransferJournal.ItemFingerprint base
	) {
		String encoded = stack.get(MmmComponents.THERMAL_CONTENTS);
		if (encoded == null) return base;
		ContainerItemStacks.thermalContents(stack);
		return new ContainerTransferJournal.ItemFingerprint(base.kind(), base.nanos(), base.solidVolumeSvq(),
				base.contents(), base.massBits(), encoded, base.profileFingerprint());
	}

	private static ContainerTransferJournal.ItemFingerprint snapshot(ContainerTransferJournal.ItemKind kind) {
		return new ContainerTransferJournal.ItemFingerprint(kind, -1L, -1L, null, -1L);
	}

	private static ItemStack materialStack(ContainerTransferJournal.ItemFingerprint snapshot) {
		FluidMixture mixture = FluidMixture.decode(snapshot.contents());
		if (mixture.totalVolumeNanos() != snapshot.nanos()
				|| Double.doubleToRawLongBits(mixture.totalMass()) != snapshot.massBits()) {
			throw new IllegalStateException("Material WAL fingerprint ledgers disagree");
		}
		return ContainerItemStacks.toStack(mixture);
	}

	private static ItemStack chemicalStack(ContainerTransferJournal.ItemFingerprint snapshot) {
		ChemicalContents contents = ChemicalContents.decode(snapshot.contents());
		if (contents.totalVolumeNanos() != snapshot.nanos()
				|| Double.doubleToRawLongBits(contents.totalMass()) != snapshot.massBits()) {
			throw new IllegalStateException("Chemical WAL fingerprint ledgers disagree");
		}
		return ContainerItemStacks.toStack(contents);
	}

	private static ItemStack granularStack(ContainerTransferJournal.ItemFingerprint snapshot) {
		GranularContents contents = GranularContents.decode(snapshot.contents());
		if (contents.totalSolidVolumeSvq() != snapshot.solidVolumeSvq()
				|| Double.doubleToRawLongBits(contents.totalMass()) != snapshot.massBits()) {
			throw new IllegalStateException("Granular WAL fingerprint ledgers disagree");
		}
		return ContainerItemStacks.toStack(contents);
	}
}
