package com.example.simulation;

import com.example.item.MaterialBucketItem;
import com.example.item.FluidMixture;
import com.example.item.ChemicalContents;
import com.example.item.GranularContainerItem;
import com.example.item.GranularContents;
import com.example.item.ThermalContents;
import com.example.item.RealWaterComponents;
import com.example.item.PartialWaterBucketItem;
import com.example.chemistry.ChemicalRegistry;
import com.example.granular.GranularRegistry;
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
ResourceKind resourceKind,
long volumeNanos,
long solidVolumeSvq,
ThermalTransfer thermalTransfer,
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
this(transactionId, regionId, dimensionId, baseGeneration, targetGeneration, operation,
ResourceKind.FLUID_NANOS, volumeNanos, -1L,
null, actor, beforeItem, afterItem, -1L, phase);
}
public ContainerTransferJournal(
UUID transactionId, UUID regionId, String dimensionId, long baseGeneration, long targetGeneration,
Operation operation, long volumeNanos, Actor actor, ItemFingerprint beforeItem, ItemFingerprint afterItem,
long targetManifestChecksum, Phase phase
) {
this(transactionId, regionId, dimensionId, baseGeneration, targetGeneration, operation,
ResourceKind.FLUID_NANOS, volumeNanos, -1L, null, actor, beforeItem, afterItem, targetManifestChecksum, phase);
}
public static ContainerTransferJournal thermalFluid(
UUID transactionId, UUID regionId, String dimensionId, long baseGeneration, long targetGeneration,
Operation operation, long volumeNanos, ThermalTransfer thermalTransfer, Actor actor,
ItemFingerprint beforeItem, ItemFingerprint afterItem, long targetManifestChecksum, Phase phase
) {
return new ContainerTransferJournal(transactionId, regionId, dimensionId, baseGeneration, targetGeneration,
operation, ResourceKind.FLUID_NANOS, volumeNanos, -1L, thermalTransfer, actor, beforeItem, afterItem,
targetManifestChecksum, phase);
}
public static ContainerTransferJournal granular(
UUID transactionId, UUID regionId, String dimensionId, long baseGeneration, long targetGeneration,
Operation operation, long solidVolumeSvq, Actor actor, ItemFingerprint beforeItem, ItemFingerprint afterItem,
long targetManifestChecksum, Phase phase
) {
return new ContainerTransferJournal(transactionId, regionId, dimensionId, baseGeneration, targetGeneration,
operation, ResourceKind.GRANULAR_SVQ, -1L, solidVolumeSvq, null, actor, beforeItem, afterItem,
targetManifestChecksum, phase);
}
public enum Operation { INSERT, EXTRACT }

public enum ResourceKind { FLUID_NANOS, GRANULAR_SVQ }

public enum Phase { PREPARED, REGION_COMMITTED }

public enum ActorType { PLAYER_HAND, DISPENSER }

public record ThermalTransfer(String registryFingerprint, long internalEnergyBits) {
public ThermalTransfer {
if (registryFingerprint == null || !registryFingerprint.matches("[0-9a-f]{64}")
|| !Double.isFinite(Double.longBitsToDouble(internalEnergyBits))) {
throw new IllegalArgumentException("Invalid container thermal transfer");
}
}
public ThermalTransfer(String registryFingerprint, double internalEnergy) {
this(registryFingerprint, Double.doubleToRawLongBits(internalEnergy));
}
public double internalEnergy() { return Double.longBitsToDouble(internalEnergyBits); }
}

public record Actor(ActorType type, UUID playerId, String hand, String actorDimensionId, BlockPos dispenserPos, int dispenserSlot) {
public static Actor player(UUID playerId, String hand) {
return new Actor(ActorType.PLAYER_HAND, playerId, hand, null, null, -1);
}

public static Actor dispenser(String dimensionId, BlockPos pos, int slot) {
return new Actor(ActorType.DISPENSER, null, null, dimensionId, pos, slot);
}
}

public enum ItemKind {
BUCKET, WATER_BUCKET, PARTIAL, EMPTY_MATERIAL_BUCKET, MATERIAL_BUCKET, CHEMICAL_BUCKET,
EMPTY_GRANULAR_CONTAINER, GRANULAR_CONTAINER
}

public boolean requiresChemicalRegistry() {
return beforeItem.requiresChemicalRegistry() || afterItem.requiresChemicalRegistry();
}

public void validateChemicalRegistry(ChemicalRegistry registry) {
if (!requiresChemicalRegistry()) return;
if (registry == null) throw new IllegalArgumentException("Authoritative chemical registry is unavailable");
beforeItem.validateChemicalRegistry(registry);
afterItem.validateChemicalRegistry(registry);
}

public boolean requiresGranularRegistry() {
return resourceKind == ResourceKind.GRANULAR_SVQ
|| beforeItem.kind() == ItemKind.GRANULAR_CONTAINER || afterItem.kind() == ItemKind.GRANULAR_CONTAINER;
}

public void validateGranularRegistry(GranularRegistry registry, ChemicalRegistry chemistry) {
if (!requiresGranularRegistry()) return;
if (registry == null) throw new IllegalArgumentException("Authoritative granular registry is unavailable");
beforeItem.validateGranularRegistry(registry, chemistry); afterItem.validateGranularRegistry(registry, chemistry);
}

public boolean requiresThermalRegistry() {
return thermalTransfer != null || beforeItem.requiresThermalRegistry() || afterItem.requiresThermalRegistry();
}

public void validateThermalRegistry(String fingerprint) {
if (!requiresThermalRegistry()) return;
if (thermalTransfer == null || fingerprint == null
|| !thermalTransfer.registryFingerprint().equals(fingerprint)) {
throw new IllegalArgumentException("Authoritative thermal registry is unavailable or differs from WAL v5");
}
beforeItem.validateThermalRegistry(fingerprint); afterItem.validateThermalRegistry(fingerprint);
}

public record ItemFingerprint(
ItemKind kind, long nanos, long solidVolumeSvq, String materialId, long massBits, String thermalContents
) {
public ItemFingerprint(ItemKind kind, long nanos, String materialId) {
this(kind, nanos, -1L, materialId, -1L, null);
}
public ItemFingerprint(ItemKind kind, long nanos, String materialId, long massBits) {
this(kind, nanos, -1L, materialId, massBits, null);
}
public ItemFingerprint(ItemKind kind, long nanos, long solidVolumeSvq, String materialId, long massBits) {
this(kind, nanos, solidVolumeSvq, materialId, massBits, null);
}

public static ItemFingerprint of(ItemStack stack) {
if (stack.getCount() != 1) {
throw new IllegalArgumentException("控制器容器事务一次只接受 1 个物品；堆叠桶保持不变");
}
if (stack.is(Items.BUCKET)) return withThermal(stack, new ItemFingerprint(ItemKind.BUCKET, -1L, -1L, null, -1L));
if (stack.is(Items.WATER_BUCKET)) return withThermal(stack,
new ItemFingerprint(ItemKind.WATER_BUCKET, -1L, -1L, null, -1L));
if (stack.getItem() instanceof PartialWaterBucketItem) {
long nanos = PartialWaterBucketItem.volume(stack);
if (nanos <= 0L) throw new IllegalArgumentException("Partial bucket has no valid volume");
return withThermal(stack, new ItemFingerprint(ItemKind.PARTIAL, nanos, -1L, null, -1L));
}
if (stack.getItem() instanceof MaterialBucketItem) {
if (MaterialBucketItem.isEmptyMaterialBucket(stack)) {
return withThermal(stack, new ItemFingerprint(ItemKind.EMPTY_MATERIAL_BUCKET, -1L, -1L, null, -1L));
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
return withThermal(stack, new ItemFingerprint(
ItemKind.CHEMICAL_BUCKET, nanos, -1L, chemicalEncoded, persistedMass));
}
FluidMixture mixture = MaterialBucketItem.mixture(stack);
double mass = MaterialBucketItem.mass(stack);
if (nanos <= 0L || mixture == null || !(mass > 0.0)) {
throw new IllegalArgumentException("Material bucket has no valid volume/material/mass ledger");
}
return withThermal(stack, new ItemFingerprint(
ItemKind.MATERIAL_BUCKET, nanos, -1L, mixture.encode(), Double.doubleToRawLongBits(mass)));
}
if (stack.getItem() instanceof GranularContainerItem) {
if (GranularContainerItem.isEmpty(stack)) {
return withThermal(stack, new ItemFingerprint(ItemKind.EMPTY_GRANULAR_CONTAINER, -1L, -1L, null, -1L));
}
GranularContents contents = GranularContainerItem.unvalidatedContents(stack);
if (contents == null) throw new IllegalArgumentException("Granular container has invalid or conflicting contents");
return withThermal(stack, new ItemFingerprint(ItemKind.GRANULAR_CONTAINER, -1L, contents.totalSolidVolumeSvq(),
contents.encode(), Double.doubleToRawLongBits(contents.totalMass())));
}
throw new IllegalArgumentException("Unsupported container item for transfer WAL: " + stack.getItem());
}

private static ItemFingerprint withThermal(ItemStack stack, ItemFingerprint base) {
String encoded = stack.get(RealWaterComponents.THERMAL_CONTENTS);
if (encoded == null) return base;
ThermalContents.fromStack(stack);
return new ItemFingerprint(base.kind, base.nanos, base.solidVolumeSvq, base.materialId, base.massBits, encoded);
}

public void validateChemicalRegistry(ChemicalRegistry registry) {
if (kind == ItemKind.CHEMICAL_BUCKET) ChemicalContents.decode(materialId).validateAgainst(registry);
else if (kind == ItemKind.GRANULAR_CONTAINER && GranularContents.decode(materialId).groups().stream()
.anyMatch(GranularContents.Group::reactive) && registry == null) {
throw new IllegalArgumentException("Reactive granular WAL item requires chemical registry");
}
}

public boolean requiresChemicalRegistry() {
return kind == ItemKind.CHEMICAL_BUCKET || kind == ItemKind.GRANULAR_CONTAINER
&& GranularContents.decode(materialId).groups().stream().anyMatch(GranularContents.Group::reactive);
}

public void validateGranularRegistry(GranularRegistry registry, ChemicalRegistry chemistry) {
if (kind != ItemKind.GRANULAR_CONTAINER) return;
GranularContents.decode(materialId).validateAgainst(registry, chemistry);
}

public boolean requiresThermalRegistry() { return thermalContents != null; }

public void validateThermalRegistry(String fingerprint) {
if (thermalContents == null) return;
ThermalContents contents = ThermalContents.decode(thermalContents);
contents.validateRegistryFingerprint(fingerprint);
ThermalContents expected = switch (kind) {
case WATER_BUCKET -> ThermalContents.forPureWater(fingerprint,
com.example.item.ContainerFluidLedger.NANOS_PER_BLOCK, contents.internalEnergy());
case PARTIAL -> ThermalContents.forPureWater(fingerprint, nanos, contents.internalEnergy());
case MATERIAL_BUCKET -> ThermalContents.forMixture(fingerprint,
materialId.startsWith("v1;") ? FluidMixture.decode(materialId) : new FluidMixture(java.util.List.of(
new FluidMixture.Component(materialId, nanos, massBits))), contents.internalEnergy());
case CHEMICAL_BUCKET -> ThermalContents.forChemical(fingerprint,
ChemicalContents.decode(materialId), contents.internalEnergy());
case BUCKET, EMPTY_MATERIAL_BUCKET, EMPTY_GRANULAR_CONTAINER, GRANULAR_CONTAINER ->
throw new IllegalArgumentException("Non-liquid WAL item carries thermal contents");
};
if (expected.underlyingKind() != contents.underlyingKind()
|| !expected.underlyingHash().equals(contents.underlyingHash())) {
throw new IllegalArgumentException("WAL thermal contents do not match item truth");
}
}

public ItemStack toStack() {
ItemStack stack = switch (kind) {
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
case EMPTY_GRANULAR_CONTAINER -> new ItemStack(com.example.item.RealWaterItems.GRANULAR_CONTAINER);
case GRANULAR_CONTAINER -> {
GranularContents contents = GranularContents.decode(materialId);
if (contents.totalSolidVolumeSvq() != solidVolumeSvq
|| Double.doubleToRawLongBits(contents.totalMass()) != massBits) {
throw new IllegalStateException("Granular WAL fingerprint ledgers disagree");
}
yield contents.toStack();
}
};
if (thermalContents != null) {
stack.set(RealWaterComponents.THERMAL_CONTENTS, thermalContents);
ThermalContents.fromStack(stack);
}
return stack;
}
}
}
