package com.example.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Narrow file boundary for container-transfer WALs. Files are tiny and short-lived; they are
 * atomically replaced when the actual after-item is known, and deleted after the item mutation.
 */
public final class ContainerTransferStore {
private static final String ROOT_DIRECTORY_NAME = "container-transfer";
private static final int FORMAT_VERSION = 5;
private static final long MAX_COMPRESSED_NBT_BYTES = 4L * 1024L * 1024L;
private static final String TEMP_SUFFIX = ".tmp";

private final Path directory;

public ContainerTransferStore(Path worldRoot) {
this.directory = worldRoot.toAbsolutePath().normalize()
.resolve("realwater")
.resolve(ROOT_DIRECTORY_NAME);
}

public boolean hasAny() throws IOException {
return scan().hasAny();
}

public List<UUID> listTransactions() throws IOException {
return scan().transactions();
}

/**
 * Strict discovery for this short-lived recovery store. Any foreign name, directory, staged
 * temporary, corrupt WAL, or filename/payload mismatch is retained and reported. Callers treat
 * even one issue as a global lock because its region cannot be identified safely.
 */
public Scan scan() throws IOException {
if (!Files.exists(directory)) return new Scan(List.of(), List.of());
if (!Files.isDirectory(directory)) {
return new Scan(List.of(), List.of("container-transfer root is not a directory: " + directory));
}
List<UUID> ids = new ArrayList<>();
List<String> issues = new ArrayList<>();
try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
for (Path entry : entries) {
String name = entry.getFileName().toString();
if (!Files.isRegularFile(entry) || !name.endsWith(".nbt")) {
issues.add("unknown container-transfer content is preserved: " + entry);
continue;
}
UUID id;
try {
id = UUID.fromString(name.substring(0, name.length() - 4));
} catch (IllegalArgumentException invalidName) {
issues.add("unknown container-transfer WAL name is preserved: " + entry);
continue;
}
try {
ContainerTransferJournal journal = read(id);
if (!journal.transactionId().equals(id)) {
issues.add("container-transfer WAL filename/payload mismatch: " + entry);
continue;
}
ids.add(id);
} catch (IOException | RuntimeException invalidWal) {
issues.add("unreadable container-transfer WAL is preserved: " + entry + " (" + invalidWal.getMessage() + ")");
}
}
}
ids.sort(Comparator.comparing(UUID::toString));
issues.sort(String::compareTo);
return new Scan(List.copyOf(ids), List.copyOf(issues));
}

public ContainerTransferJournal read(UUID transactionId) throws IOException {
Path path = path(transactionId);
if (!Files.exists(path)) throw new IOException("Container transfer WAL does not exist at " + path);
CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_COMPRESSED_NBT_BYTES));
return decode(root);
}

public void write(ContainerTransferJournal journal) throws IOException {
assertHealthyLayout();
Files.createDirectories(directory);
Path path = path(journal.transactionId());
Path temporary = path.resolveSibling(path.getFileName() + TEMP_SUFFIX);
deleteIfExists(temporary);
try {
NbtIo.writeCompressed(encode(journal), temporary);
forceFile(temporary);
ContainerTransferJournal reread = decode(NbtIo.readCompressed(temporary, NbtAccounter.create(MAX_COMPRESSED_NBT_BYTES)));
if (!reread.equals(journal)) {
throw new IOException("Staged container transfer WAL did not read back identically");
}
moveWithRetry(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
if (!Files.exists(path)) throw new IOException("Atomic publisher did not create " + path);
} catch (IOException exception) {
try {
deleteIfExists(temporary);
} catch (IOException cleanupFailure) {
exception.addSuppressed(cleanupFailure);
}
throw exception;
}
}

public void delete(UUID transactionId) throws IOException {
assertHealthyLayout();
deleteIfExists(path(transactionId));
Path temporary = path(transactionId).resolveSibling(path(transactionId).getFileName() + TEMP_SUFFIX);
deleteIfExists(temporary);
}

private void assertHealthyLayout() throws IOException {
Scan scan = scan();
if (!scan.issues().isEmpty()) {
throw new IOException("Container-transfer store is locked: " + String.join("; ", scan.issues()));
}
}

Path directoryPath() {
return directory;
}

public record Scan(List<UUID> transactions, List<String> issues) {
public boolean hasAny() {
return !transactions.isEmpty() || !issues.isEmpty();
}
}

private Path path(UUID transactionId) {
return directory.resolve(transactionId + ".nbt");
}

private static void deleteIfExists(Path path) throws IOException {
Files.deleteIfExists(path);
}

private static void forceFile(Path path) throws IOException {
try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
channel.force(true);
}
}

private static void moveWithRetry(Path source, Path target, StandardCopyOption... options) throws IOException {
IOException denied = null;
for (int attempt = 0; attempt < 5; attempt++) {
try {
Files.move(source, target, options);
return;
} catch (java.nio.file.AccessDeniedException accessDenied) {
denied = accessDenied;
try {
Thread.sleep(25L << attempt);
} catch (InterruptedException interrupted) {
Thread.currentThread().interrupt();
throw accessDenied;
}
}
}
throw denied;
}

private static CompoundTag encode(ContainerTransferJournal journal) throws IOException {
validateTransferShape(journal, true);
CompoundTag root = new CompoundTag();
int version = journal.requiresThermalRegistry() ? 5 : 4;
root.putInt("Version", version);
root.putLong("TxUuidMost", journal.transactionId().getMostSignificantBits());
root.putLong("TxUuidLeast", journal.transactionId().getLeastSignificantBits());
root.putLong("RegionUuidMost", journal.regionId().getMostSignificantBits());
root.putLong("RegionUuidLeast", journal.regionId().getLeastSignificantBits());
validateDimension(journal.dimensionId());
root.putString("Dimension", journal.dimensionId());
root.putLong("BaseGeneration", journal.baseGeneration());
root.putLong("TargetGeneration", journal.targetGeneration());
validateGeneration(journal.baseGeneration(), journal.targetGeneration());
root.putString("Operation", operationName(journal.operation()));
root.putString("ResourceKind", resourceKindName(journal.resourceKind()));
switch (journal.resourceKind()) {
case FLUID_NANOS -> root.putLong("VolumeNanos", journal.volumeNanos());
case GRANULAR_SVQ -> root.putLong("SolidVolumeSvq", journal.solidVolumeSvq());
}
if (version == 5) {
root.putString("ThermalRegistryFingerprint", journal.thermalTransfer().registryFingerprint());
root.putLong("ThermalEnergyBits", journal.thermalTransfer().internalEnergyBits());
}

ContainerTransferJournal.Actor actor = journal.actor();
root.putString("ActorType", actorTypeName(actor.type()));
switch (actor.type()) {
case PLAYER_HAND -> {
if (actor.playerId() == null || actor.hand() == null) throw new IOException("Player actor is incomplete");
root.putLong("PlayerUuidMost", actor.playerId().getMostSignificantBits());
root.putLong("PlayerUuidLeast", actor.playerId().getLeastSignificantBits());
root.putString("Hand", actor.hand());
}
case DISPENSER -> {
if (actor.actorDimensionId() == null || actor.dispenserPos() == null || actor.dispenserSlot() < 0) {
throw new IOException("Dispenser actor is incomplete");
}
validateDimension(actor.actorDimensionId());
root.putString("ActorDimension", actor.actorDimensionId());
root.putInt("DispenserX", actor.dispenserPos().getX());
root.putInt("DispenserY", actor.dispenserPos().getY());
root.putInt("DispenserZ", actor.dispenserPos().getZ());
root.putInt("DispenserSlot", actor.dispenserSlot());
}
}

putFingerprint(root, "Before", journal.beforeItem(), version);
putFingerprint(root, "After", journal.afterItem(), version);
root.putString("Phase", phaseName(journal.phase()));
root.putLong("TargetManifestChecksum", journal.targetManifestChecksum());
return root;
}

private static ContainerTransferJournal decode(CompoundTag root) throws IOException {
requireType(root, "Version", Tag.TAG_INT);
int version = root.getIntOr("Version", -1);
if (version < 1 || version > FORMAT_VERSION) {
throw new IOException("Unsupported container transfer WAL version " + version);
}

requireType(root, "TxUuidMost", Tag.TAG_LONG);
requireType(root, "TxUuidLeast", Tag.TAG_LONG);
UUID transactionId = new UUID(root.getLongOr("TxUuidMost", 0L), root.getLongOr("TxUuidLeast", 0L));

requireType(root, "RegionUuidMost", Tag.TAG_LONG);
requireType(root, "RegionUuidLeast", Tag.TAG_LONG);
UUID regionId = new UUID(root.getLongOr("RegionUuidMost", 0L), root.getLongOr("RegionUuidLeast", 0L));

requireType(root, "Dimension", Tag.TAG_STRING);
String dimensionId = root.getStringOr("Dimension", "");
validateDimension(dimensionId);

requireType(root, "BaseGeneration", Tag.TAG_LONG);
requireType(root, "TargetGeneration", Tag.TAG_LONG);
long baseGeneration = root.getLongOr("BaseGeneration", Long.MIN_VALUE);
long targetGeneration = root.getLongOr("TargetGeneration", Long.MIN_VALUE);
validateGeneration(baseGeneration, targetGeneration);

requireType(root, "Operation", Tag.TAG_STRING);
ContainerTransferJournal.Operation operation = parseOperation(root.getStringOr("Operation", ""));

ContainerTransferJournal.ResourceKind resourceKind;
long volumeNanos = -1L;
long solidVolumeSvq = -1L;
ContainerTransferJournal.ThermalTransfer thermalTransfer = null;
if (version < 4) {
resourceKind = ContainerTransferJournal.ResourceKind.FLUID_NANOS;
requireType(root, "VolumeNanos", Tag.TAG_LONG);
volumeNanos = root.getLongOr("VolumeNanos", -1L);
if (volumeNanos <= 0L) throw new IOException("Container transfer WAL volume is missing or invalid");
} else {
requireType(root, "ResourceKind", Tag.TAG_STRING);
resourceKind = parseResourceKind(root.getStringOr("ResourceKind", ""));
if (resourceKind == ContainerTransferJournal.ResourceKind.FLUID_NANOS) {
requireType(root, "VolumeNanos", Tag.TAG_LONG);
if (root.get("SolidVolumeSvq") != null) throw new IOException("Fluid WAL must not carry solid-volume truth");
volumeNanos = root.getLongOr("VolumeNanos", -1L);
} else {
requireType(root, "SolidVolumeSvq", Tag.TAG_LONG);
if (root.get("VolumeNanos") != null) throw new IOException("Granular WAL must not carry liquid-volume truth");
solidVolumeSvq = root.getLongOr("SolidVolumeSvq", -1L);
}
}

if (version >= 5) {
requireType(root, "ThermalRegistryFingerprint", Tag.TAG_STRING);
requireType(root, "ThermalEnergyBits", Tag.TAG_LONG);
try {
thermalTransfer = new ContainerTransferJournal.ThermalTransfer(
root.getStringOr("ThermalRegistryFingerprint", ""), root.getLongOr("ThermalEnergyBits", 0L));
} catch (IllegalArgumentException invalidThermal) {
throw new IOException("Container transfer WAL thermal transfer is invalid", invalidThermal);
}
} else if (root.get("ThermalRegistryFingerprint") != null || root.get("ThermalEnergyBits") != null) {
throw new IOException("Legacy container transfer WAL carries v5 thermal fields");
}

requireType(root, "ActorType", Tag.TAG_STRING);
ContainerTransferJournal.ActorType actorType = parseActorType(root.getStringOr("ActorType", ""));
ContainerTransferJournal.Actor actor;
switch (actorType) {
case PLAYER_HAND -> {
requireType(root, "PlayerUuidMost", Tag.TAG_LONG);
requireType(root, "PlayerUuidLeast", Tag.TAG_LONG);
requireType(root, "Hand", Tag.TAG_STRING);
actor = ContainerTransferJournal.Actor.player(
new UUID(root.getLongOr("PlayerUuidMost", 0L), root.getLongOr("PlayerUuidLeast", 0L)),
root.getStringOr("Hand", "")
);
}
case DISPENSER -> {
requireType(root, "ActorDimension", Tag.TAG_STRING);
requireType(root, "DispenserX", Tag.TAG_INT);
requireType(root, "DispenserY", Tag.TAG_INT);
requireType(root, "DispenserZ", Tag.TAG_INT);
requireType(root, "DispenserSlot", Tag.TAG_INT);
actor = ContainerTransferJournal.Actor.dispenser(
root.getStringOr("ActorDimension", ""),
new BlockPos(root.getIntOr("DispenserX", 0), root.getIntOr("DispenserY", 0), root.getIntOr("DispenserZ", 0)),
root.getIntOr("DispenserSlot", -1)
);
}
default -> throw new IOException("Unknown actor type " + actorType);
}

ContainerTransferJournal.ItemFingerprint beforeItem = getFingerprint(root, "Before", version);
ContainerTransferJournal.ItemFingerprint afterItem = getFingerprint(root, "After", version);

requireType(root, "Phase", Tag.TAG_STRING);
ContainerTransferJournal.Phase phase = parsePhase(root.getStringOr("Phase", ""));
long targetManifestChecksum = -1L;
if (version >= 3) {
requireType(root, "TargetManifestChecksum", Tag.TAG_LONG);
targetManifestChecksum = root.getLongOr("TargetManifestChecksum", -1L);
}

ContainerTransferJournal journal = new ContainerTransferJournal(
transactionId, regionId, dimensionId, baseGeneration, targetGeneration,
operation, resourceKind, volumeNanos, solidVolumeSvq, thermalTransfer,
actor, beforeItem, afterItem, targetManifestChecksum, phase);
validateTransferShape(journal, version >= 3);
return journal;
}

private static void putFingerprint(
CompoundTag root, String prefix, ContainerTransferJournal.ItemFingerprint item, int version
) throws IOException {
root.putString(prefix + "Kind", itemKindName(item.kind()));
root.putLong(prefix + "Nanos", item.nanos());
root.putLong(prefix + "SolidVolumeSvq", item.solidVolumeSvq());
if (item.materialId() != null) root.putString(prefix + "MaterialId", item.materialId());
root.putLong(prefix + "MassBits", item.massBits());
if (version >= 5 && item.thermalContents() != null) {
root.putString(prefix + "ThermalContents", item.thermalContents());
}
}

private static ContainerTransferJournal.ItemFingerprint getFingerprint(CompoundTag root, String prefix, int version) throws IOException {
requireType(root, prefix + "Kind", Tag.TAG_STRING);
requireType(root, prefix + "Nanos", Tag.TAG_LONG);
ContainerTransferJournal.ItemKind kind = parseItemKind(root.getStringOr(prefix + "Kind", ""));
long nanos = root.getLongOr(prefix + "Nanos", 0L);
long solidVolumeSvq = -1L;
if (version >= 4) {
requireType(root, prefix + "SolidVolumeSvq", Tag.TAG_LONG);
solidVolumeSvq = root.getLongOr(prefix + "SolidVolumeSvq", -1L);
}
String materialId = root.getStringOr(prefix + "MaterialId", null);
long massBits;
if (version >= 2) {
requireType(root, prefix + "MassBits", Tag.TAG_LONG);
massBits = root.getLongOr(prefix + "MassBits", -1L);
} else {
if (kind == ContainerTransferJournal.ItemKind.MATERIAL_BUCKET) {
throw new IOException("Legacy v1 material-bucket WAL has no independent mass ledger; manual recovery is required");
}
massBits = -1L;
}
String thermalContents = null;
if (version >= 5) thermalContents = root.getStringOr(prefix + "ThermalContents", null);
else if (root.get(prefix + "ThermalContents") != null) {
throw new IOException("Legacy item fingerprint carries v5 thermal contents");
}
if (kind == ContainerTransferJournal.ItemKind.PARTIAL) {
if (!com.example.item.ContainerFluidLedger.isPartialBucket(nanos)
|| materialId != null || solidVolumeSvq != -1L || massBits != -1L) {
throw new IOException(prefix + " partial bucket nanovolume is invalid: " + nanos);
}
} else if (kind == ContainerTransferJournal.ItemKind.MATERIAL_BUCKET) {
double mass = Double.longBitsToDouble(massBits);
if (materialId == null || nanos <= 0L
|| nanos > com.example.item.ContainerFluidLedger.NANOS_PER_BLOCK
|| solidVolumeSvq != -1L
|| !(Double.isFinite(mass) && mass > 0.0)) {
throw new IOException(prefix + " material bucket fingerprint is invalid");
}
try {
com.example.item.FluidMixture mixture = materialId.startsWith("v1;")
? com.example.item.FluidMixture.decode(materialId)
: new com.example.item.FluidMixture(java.util.List.of(
new com.example.item.FluidMixture.Component(materialId, nanos, massBits)));
if (mixture.totalVolumeNanos() != nanos
|| Double.doubleToRawLongBits(mixture.totalMass()) != massBits) {
throw new IllegalArgumentException("mixture totals disagree with fingerprint");
}
} catch (IllegalArgumentException invalidMixture) {
throw new IOException(prefix + " material bucket mixture is invalid", invalidMixture);
}
} else if (kind == ContainerTransferJournal.ItemKind.CHEMICAL_BUCKET) {
if (version < 3) throw new IOException("Chemical bucket fingerprint requires container-transfer WAL v3");
try {
com.example.item.ChemicalContents contents = com.example.item.ChemicalContents.decode(materialId);
if (contents.totalVolumeNanos() != nanos || Double.doubleToRawLongBits(contents.totalMass()) != massBits
|| solidVolumeSvq != -1L) {
throw new IllegalArgumentException("chemical totals disagree with fingerprint");
}
} catch (RuntimeException invalidChemical) {
throw new IOException(prefix + " chemical bucket fingerprint is invalid", invalidChemical);
}
} else if (kind == ContainerTransferJournal.ItemKind.GRANULAR_CONTAINER) {
if (version < 4) throw new IOException("Granular container fingerprint requires container-transfer WAL v4");
try {
com.example.item.GranularContents contents = com.example.item.GranularContents.decode(materialId);
if (contents.totalSolidVolumeSvq() != solidVolumeSvq
|| Double.doubleToRawLongBits(contents.totalMass()) != massBits || nanos != -1L) {
throw new IllegalArgumentException("granular totals disagree with fingerprint");
}
} catch (RuntimeException invalidGranular) {
throw new IOException(prefix + " granular container fingerprint is invalid", invalidGranular);
}
} else if (materialId != null || nanos != -1L || solidVolumeSvq != -1L || massBits != -1L) {
throw new IOException(prefix + " non-material bucket must not carry material id or nanovolume");
}
ContainerTransferJournal.ItemFingerprint result = new ContainerTransferJournal.ItemFingerprint(
kind, nanos, solidVolumeSvq, materialId, massBits, thermalContents);
if (thermalContents != null) {
try {
result.validateThermalRegistry(com.example.item.ThermalContents.decode(thermalContents).registryFingerprint());
} catch (RuntimeException invalidThermal) {
throw new IOException(prefix + " item thermal contents are invalid", invalidThermal);
}
}
return result;
}
private static void validateDimension(String dimensionId) throws IOException {
if (Identifier.tryParse(dimensionId) == null) throw new IOException("Invalid container transfer WAL dimension: " + dimensionId);
}

private static void validateGeneration(long base, long target) throws IOException {
if (base < 0L || target != base + 1L) {
throw new IOException("Container transfer WAL generations are inconsistent: base " + base + ", target " + target);
}
}

/** Rejects syntactically valid WALs whose item delta cannot settle the declared solver delta. */
private static void validateTransferShape(ContainerTransferJournal journal, boolean requireTargetChecksum) throws IOException {
if (journal.operation() == null || journal.phase() == null
|| journal.beforeItem() == null || journal.afterItem() == null) {
throw new IOException("Container transfer WAL is incomplete");
}
if (journal.resourceKind() == null) throw new IOException("Container transfer WAL resource kind is missing");
if (journal.phase() == ContainerTransferJournal.Phase.PREPARED
&& !journal.beforeItem().equals(journal.afterItem())) {
throw new IOException("Prepared container transfer must not claim an after-item mutation");
}
if (journal.phase() == ContainerTransferJournal.Phase.PREPARED) {
if (journal.targetManifestChecksum() != -1L) throw new IOException("Prepared container transfer cannot bind a target checksum");
} else if (requireTargetChecksum && journal.targetManifestChecksum() < 0L) {
throw new IOException("Committed container-transfer WAL v3 must bind the staged manifest checksum");
}

if (journal.resourceKind() == ContainerTransferJournal.ResourceKind.GRANULAR_SVQ) {
if (journal.thermalTransfer() != null || journal.beforeItem().thermalContents() != null
|| journal.afterItem().thermalContents() != null) {
throw new IOException("Granular WAL must not carry thermal liquid truth");
}
validateGranularTransferShape(journal);
return;
}
if (journal.solidVolumeSvq() != -1L) throw new IOException("Fluid WAL carries a solid-volume ledger");
validateThermalTransferShape(journal);
long bucket = com.example.item.ContainerFluidLedger.NANOS_PER_BLOCK;
long amount = journal.volumeNanos();
if (amount <= 0L || amount > bucket) {
throw new IOException("Container transfer WAL volume is outside one bucket: " + amount);
}
long beforePure = pureVolume(journal.beforeItem());
long afterPure = pureVolume(journal.afterItem());
long beforeMaterial = materialVolume(journal.beforeItem());
long afterMaterial = materialVolume(journal.afterItem());
boolean prepared = journal.phase() == ContainerTransferJournal.Phase.PREPARED;
boolean valid;
if (journal.operation() == ContainerTransferJournal.Operation.INSERT) {
boolean pure = beforePure >= amount && beforePure > 0L
&& (prepared || afterPure == beforePure - amount || journal.afterItem().equals(journal.beforeItem()));
boolean material = beforeMaterial >= amount && beforeMaterial > 0L
&& (prepared || afterMaterial == beforeMaterial - amount || journal.afterItem().equals(journal.beforeItem()));
valid = pure || material;
} else {
boolean pure = beforePure >= 0L && beforePure <= bucket - amount
&& (prepared || afterPure == beforePure + amount);
boolean material = beforeMaterial == 0L
&& (prepared || afterMaterial == amount);
valid = pure || material;
}
if (!valid) {
throw new IOException("Container transfer item delta does not match " + journal.operation()
+ " volume " + amount + ": " + journal.beforeItem() + " -> " + journal.afterItem());
}
}

private static void validateThermalTransferShape(ContainerTransferJournal journal) throws IOException {
ContainerTransferJournal.ThermalTransfer transfer = journal.thermalTransfer();
boolean beforeThermal = journal.beforeItem().thermalContents() != null;
boolean afterThermal = journal.afterItem().thermalContents() != null;
if (transfer == null) {
if (beforeThermal || afterThermal) throw new IOException("v4 WAL item cannot carry ThermalContents v1");
return;
}
try {
journal.validateThermalRegistry(transfer.registryFingerprint());
} catch (RuntimeException invalidThermal) {
throw new IOException("Container transfer thermal identity is invalid", invalidThermal);
}
if (journal.phase() == ContainerTransferJournal.Phase.PREPARED) {
if (journal.operation() == ContainerTransferJournal.Operation.INSERT && !beforeThermal) {
throw new IOException("Prepared thermal insertion has no source item energy");
}
return;
}
if (journal.operation() == ContainerTransferJournal.Operation.INSERT && !beforeThermal
|| journal.operation() == ContainerTransferJournal.Operation.EXTRACT && !afterThermal) {
throw new IOException("Committed thermal transfer is missing the item-side energy owner");
}
double before = beforeThermal
? com.example.item.ThermalContents.decode(journal.beforeItem().thermalContents()).internalEnergy() : 0.0;
double after = afterThermal
? com.example.item.ThermalContents.decode(journal.afterItem().thermalContents()).internalEnergy() : 0.0;
double moved = journal.operation() == ContainerTransferJournal.Operation.INSERT ? before - after : after - before;
if (Double.doubleToRawLongBits(moved) != transfer.internalEnergyBits()) {
throw new IOException("Container transfer item energy delta does not match WAL coupled energy");
}
}

private static void validateGranularTransferShape(ContainerTransferJournal journal) throws IOException {
long amount = journal.solidVolumeSvq();
if (journal.volumeNanos() != -1L || amount <= 0L || amount > com.example.granular.SolidVolume.SVQ_PER_BV) {
throw new IOException("Container transfer WAL solid volume is outside one dry container: " + amount);
}
long before = granularVolume(journal.beforeItem());
long after = granularVolume(journal.afterItem());
boolean prepared = journal.phase() == ContainerTransferJournal.Phase.PREPARED;
boolean valid;
if (journal.operation() == ContainerTransferJournal.Operation.INSERT) {
valid = before == amount
&& (prepared || after == 0L || journal.afterItem().equals(journal.beforeItem()));
} else {
valid = before == 0L && (prepared || after == amount);
}
if (!valid) {
throw new IOException("Container transfer item delta does not match " + journal.operation()
+ " solid volume " + amount + ": " + journal.beforeItem() + " -> " + journal.afterItem());
}
}

private static long pureVolume(ContainerTransferJournal.ItemFingerprint item) {
return switch (item.kind()) {
case BUCKET -> 0L;
case WATER_BUCKET -> com.example.item.ContainerFluidLedger.NANOS_PER_BLOCK;
case PARTIAL -> item.nanos();
case EMPTY_MATERIAL_BUCKET, MATERIAL_BUCKET, CHEMICAL_BUCKET,
EMPTY_GRANULAR_CONTAINER, GRANULAR_CONTAINER -> -1L;
};
}

private static long materialVolume(ContainerTransferJournal.ItemFingerprint item) {
return switch (item.kind()) {
case EMPTY_MATERIAL_BUCKET -> 0L;
case MATERIAL_BUCKET, CHEMICAL_BUCKET -> item.nanos();
case BUCKET, WATER_BUCKET, PARTIAL, EMPTY_GRANULAR_CONTAINER, GRANULAR_CONTAINER -> -1L;
};
}

private static long granularVolume(ContainerTransferJournal.ItemFingerprint item) {
return switch (item.kind()) {
case EMPTY_GRANULAR_CONTAINER -> 0L;
case GRANULAR_CONTAINER -> item.solidVolumeSvq();
case BUCKET, WATER_BUCKET, PARTIAL, EMPTY_MATERIAL_BUCKET, MATERIAL_BUCKET, CHEMICAL_BUCKET -> -1L;
};
}

private static String operationName(ContainerTransferJournal.Operation operation) {
return operation == ContainerTransferJournal.Operation.INSERT ? "INSERT" : "EXTRACT";
}

private static ContainerTransferJournal.Operation parseOperation(String name) throws IOException {
return switch (name) {
case "INSERT" -> ContainerTransferJournal.Operation.INSERT;
case "EXTRACT" -> ContainerTransferJournal.Operation.EXTRACT;
default -> throw new IOException("Unknown container transfer operation: " + name);
};
}

private static String resourceKindName(ContainerTransferJournal.ResourceKind kind) {
return switch (kind) {
case FLUID_NANOS -> "FLUID_NANOS";
case GRANULAR_SVQ -> "GRANULAR_SVQ";
};
}

private static ContainerTransferJournal.ResourceKind parseResourceKind(String name) throws IOException {
return switch (name) {
case "FLUID_NANOS" -> ContainerTransferJournal.ResourceKind.FLUID_NANOS;
case "GRANULAR_SVQ" -> ContainerTransferJournal.ResourceKind.GRANULAR_SVQ;
default -> throw new IOException("Unknown container transfer resource kind: " + name);
};
}

private static String actorTypeName(ContainerTransferJournal.ActorType type) {
return type == ContainerTransferJournal.ActorType.PLAYER_HAND ? "PLAYER_HAND" : "DISPENSER";
}

private static ContainerTransferJournal.ActorType parseActorType(String name) throws IOException {
return switch (name) {
case "PLAYER_HAND" -> ContainerTransferJournal.ActorType.PLAYER_HAND;
case "DISPENSER" -> ContainerTransferJournal.ActorType.DISPENSER;
default -> throw new IOException("Unknown container transfer actor type: " + name);
};
}

private static String itemKindName(ContainerTransferJournal.ItemKind kind) {
return switch (kind) {
case BUCKET -> "BUCKET";
case WATER_BUCKET -> "WATER_BUCKET";
case PARTIAL -> "PARTIAL";
case EMPTY_MATERIAL_BUCKET -> "EMPTY_MATERIAL_BUCKET";
case MATERIAL_BUCKET -> "MATERIAL_BUCKET";
case CHEMICAL_BUCKET -> "CHEMICAL_BUCKET";
case EMPTY_GRANULAR_CONTAINER -> "EMPTY_GRANULAR_CONTAINER";
case GRANULAR_CONTAINER -> "GRANULAR_CONTAINER";
};
}

private static ContainerTransferJournal.ItemKind parseItemKind(String name) throws IOException {
return switch (name) {
case "BUCKET" -> ContainerTransferJournal.ItemKind.BUCKET;
case "WATER_BUCKET" -> ContainerTransferJournal.ItemKind.WATER_BUCKET;
case "PARTIAL" -> ContainerTransferJournal.ItemKind.PARTIAL;
case "EMPTY_MATERIAL_BUCKET" -> ContainerTransferJournal.ItemKind.EMPTY_MATERIAL_BUCKET;
case "MATERIAL_BUCKET" -> ContainerTransferJournal.ItemKind.MATERIAL_BUCKET;
case "CHEMICAL_BUCKET" -> ContainerTransferJournal.ItemKind.CHEMICAL_BUCKET;
case "EMPTY_GRANULAR_CONTAINER" -> ContainerTransferJournal.ItemKind.EMPTY_GRANULAR_CONTAINER;
case "GRANULAR_CONTAINER" -> ContainerTransferJournal.ItemKind.GRANULAR_CONTAINER;
default -> throw new IOException("Unknown container transfer item kind: " + name);
};
}
private static String phaseName(ContainerTransferJournal.Phase phase) {
return phase == ContainerTransferJournal.Phase.PREPARED ? "PREPARED" : "REGION_COMMITTED";
}

private static ContainerTransferJournal.Phase parsePhase(String name) throws IOException {
return switch (name) {
case "PREPARED" -> ContainerTransferJournal.Phase.PREPARED;
case "REGION_COMMITTED" -> ContainerTransferJournal.Phase.REGION_COMMITTED;
default -> throw new IOException("Unknown container transfer phase: " + name);
};
}

private static void requireType(CompoundTag tag, String name, byte type) throws IOException {
Tag value = tag.get(name);
if (value == null || value.getId() != type) {
throw new IOException("Container transfer WAL field '" + name + "' is missing or has the wrong NBT type");
}
}
}
