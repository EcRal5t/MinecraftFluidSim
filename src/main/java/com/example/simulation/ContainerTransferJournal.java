package com.example.simulation;

import com.example.item.MaterialBucketItem;
import com.example.item.FluidMixture;
import com.example.item.ChemicalContents;
import com.example.item.RealWaterComponents;
import com.example.item.PartialWaterBucketItem;
import com.example.chemistry.ChemicalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Short-lived WAL record for one player/dispenser bucket transfer against a controller region.
 * The region-state HEAD remains the only commit point; this WAL only bridges the item side to
 * that commit and is deleted only after the actor's after-item has been durably saved.
 */
public record ContainerTransferJournal(
UUID transactionId,
UUID regionId,
String dimensionId,
long baseGeneration,
long targetGeneration,
Operation operation,
long volumeNanos,
Actor actor,
ItemFingerprint beforeItem,
ItemFingerprint afterItem,
long targetManifestChecksum,
Phase phase
) {
public ContainerTransferJournal(
UUID transactionId, UUID regionId, String dimensionId, long baseGeneration, long targetGeneration,
Operation operation, long volumeNanos, Actor actor, ItemFingerprint beforeItem, ItemFingerprint afterItem, Phase phase
) {
this(transactionId, regionId, dimensionId, baseGeneration, targetGeneration, operation, volumeNanos,
actor, beforeItem, afterItem, -1L, phase);
}
public enum Operation { INSERT, EXTRACT }

public enum Phase { PREPARED, REGION_COMMITTED }

public enum ActorType { PLAYER_HAND, DISPENSER }

public record Actor(ActorType type, UUID playerId, String hand, String actorDimensionId, BlockPos dispenserPos, int dispenserSlot) {
public static Actor player(UUID playerId, String hand) {
return new Actor(ActorType.PLAYER_HAND, playerId, hand, null, null, -1);
}

public static Actor dispenser(String dimensionId, BlockPos pos, int slot) {
return new Actor(ActorType.DISPENSER, null, null, dimensionId, pos, slot);
}
}

public enum ItemKind { BUCKET, WATER_BUCKET, PARTIAL, EMPTY_MATERIAL_BUCKET, MATERIAL_BUCKET, CHEMICAL_BUCKET }

public boolean requiresChemicalRegistry() {
return beforeItem.kind() == ItemKind.CHEMICAL_BUCKET || afterItem.kind() == ItemKind.CHEMICAL_BUCKET;
}

public void validateChemicalRegistry(ChemicalRegistry registry) {
if (!requiresChemicalRegistry()) return;
if (registry == null) throw new IllegalArgumentException("Authoritative chemical registry is unavailable");
beforeItem.validateChemicalRegistry(registry);
afterItem.validateChemicalRegistry(registry);
}

public record ItemFingerprint(ItemKind kind, long nanos, String materialId, long massBits) {
public ItemFingerprint(ItemKind kind, long nanos, String materialId) {
this(kind, nanos, materialId, -1L);
}

public static ItemFingerprint of(ItemStack stack) {
if (stack.getCount() != 1) {
throw new IllegalArgumentException("控制器容器事务一次只接受 1 个物品；堆叠桶保持不变");
}
if (stack.is(Items.BUCKET)) return new ItemFingerprint(ItemKind.BUCKET, -1L, null, -1L);
if (stack.is(Items.WATER_BUCKET)) return new ItemFingerprint(ItemKind.WATER_BUCKET, -1L, null, -1L);
if (stack.getItem() instanceof PartialWaterBucketItem) {
long nanos = PartialWaterBucketItem.volume(stack);
if (nanos <= 0L) throw new IllegalArgumentException("Partial bucket has no valid volume");
return new ItemFingerprint(ItemKind.PARTIAL, nanos, null, -1L);
}
if (stack.getItem() instanceof MaterialBucketItem) {
if (MaterialBucketItem.isEmptyMaterialBucket(stack)) {
return new ItemFingerprint(ItemKind.EMPTY_MATERIAL_BUCKET, -1L, null, -1L);
}
long nanos = MaterialBucketItem.volume(stack);
String chemicalEncoded = stack.get(RealWaterComponents.CHEMICAL_CONTENTS);
if (chemicalEncoded != null) {
ChemicalContents contents = ChemicalContents.decode(chemicalEncoded);
Long persistedMass = stack.get(RealWaterComponents.FLUID_MASS_BITS);
if (stack.get(RealWaterComponents.MATERIAL_ID) != null
|| stack.get(RealWaterComponents.MIXTURE_COMPONENTS) != null
|| nanos != contents.totalVolumeNanos() || persistedMass == null
|| persistedMass != Double.doubleToRawLongBits(contents.totalMass())) {
throw new IllegalArgumentException("Chemical bucket has conflicting or mismatched ledgers");
}
return new ItemFingerprint(ItemKind.CHEMICAL_BUCKET, nanos, chemicalEncoded, persistedMass);
}
FluidMixture mixture = MaterialBucketItem.mixture(stack);
double mass = MaterialBucketItem.mass(stack);
if (nanos <= 0L || mixture == null || !(mass > 0.0)) {
throw new IllegalArgumentException("Material bucket has no valid volume/material/mass ledger");
}
return new ItemFingerprint(ItemKind.MATERIAL_BUCKET, nanos, mixture.encode(), Double.doubleToRawLongBits(mass));
}
throw new IllegalArgumentException("Unsupported container item for transfer WAL: " + stack.getItem());
}

public void validateChemicalRegistry(ChemicalRegistry registry) {
if (kind != ItemKind.CHEMICAL_BUCKET) return;
ChemicalContents contents = ChemicalContents.decode(materialId);
contents.validateAgainst(registry);
}

public ItemStack toStack() {
return switch (kind) {
case BUCKET -> new ItemStack(Items.BUCKET);
case WATER_BUCKET -> new ItemStack(Items.WATER_BUCKET);
case PARTIAL -> PartialWaterBucketItem.withVolume(nanos);
case EMPTY_MATERIAL_BUCKET -> new ItemStack(com.example.item.RealWaterItems.MATERIAL_BUCKET);
case MATERIAL_BUCKET -> {
FluidMixture mixture;
if (materialId != null && materialId.startsWith("v1;")) {
mixture = FluidMixture.decode(materialId);
} else {
mixture = new FluidMixture(java.util.List.of(
new FluidMixture.Component(materialId, nanos, massBits)));
}
if (mixture.totalVolumeNanos() != nanos
|| Double.doubleToRawLongBits(mixture.totalMass()) != massBits) {
throw new IllegalStateException("Material WAL fingerprint ledgers disagree");
}
yield mixture.toStack();
}
case CHEMICAL_BUCKET -> {
ChemicalContents contents = ChemicalContents.decode(materialId);
if (contents.totalVolumeNanos() != nanos
|| Double.doubleToRawLongBits(contents.totalMass()) != massBits) {
throw new IllegalStateException("Chemical WAL fingerprint ledgers disagree");
}
yield contents.toStack();
}
};
}
}
}
