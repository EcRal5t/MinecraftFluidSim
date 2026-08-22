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
import java.util.List;
import java.util.UUID;

/**
 * Narrow file boundary for container-transfer WALs. Files are tiny and short-lived; they are
 * atomically replaced when the actual after-item is known, and deleted after the item mutation.
 */
public final class ContainerTransferStore {
private static final String ROOT_DIRECTORY_NAME = "container-transfer";
private static final int FORMAT_VERSION = 1;
private static final long MAX_COMPRESSED_NBT_BYTES = 4L * 1024L * 1024L;
private static final String TEMP_SUFFIX = ".tmp";

private final Path directory;

public ContainerTransferStore(Path worldRoot) {
this.directory = worldRoot.toAbsolutePath().normalize()
.resolve("realwater")
.resolve(ROOT_DIRECTORY_NAME);
}

public boolean hasAny() throws IOException {
if (!Files.exists(directory)) return false;
try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.nbt")) {
return entries.iterator().hasNext();
}
}

public List<UUID> listTransactions() throws IOException {
List<UUID> ids = new ArrayList<>();
if (!Files.exists(directory)) return ids;
try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, "*.nbt")) {
for (Path entry : entries) {
String name = entry.getFileName().toString();
if (!name.endsWith(".nbt")) continue;
try {
ids.add(UUID.fromString(name.substring(0, name.length() - 4)));
} catch (IllegalArgumentException ignored) {
// Unknown files are reported as locked by the caller; never silently delete them.
}
}
}
return List.copyOf(ids);
}

public ContainerTransferJournal read(UUID transactionId) throws IOException {
Path path = path(transactionId);
if (!Files.exists(path)) throw new IOException("Container transfer WAL does not exist at " + path);
CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_COMPRESSED_NBT_BYTES));
return decode(root);
}

public void write(ContainerTransferJournal journal) throws IOException {
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
deleteIfExists(path(transactionId));
Path temporary = path(transactionId).resolveSibling(path(transactionId).getFileName() + TEMP_SUFFIX);
deleteIfExists(temporary);
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
CompoundTag root = new CompoundTag();
root.putInt("Version", FORMAT_VERSION);
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
if (journal.volumeNanos() <= 0L) throw new IOException("Container transfer WAL volume must be positive");
root.putLong("VolumeNanos", journal.volumeNanos());

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

putFingerprint(root, "Before", journal.beforeItem());
putFingerprint(root, "After", journal.afterItem());
root.putString("Phase", phaseName(journal.phase()));
return root;
}

private static ContainerTransferJournal decode(CompoundTag root) throws IOException {
requireType(root, "Version", Tag.TAG_INT);
int version = root.getIntOr("Version", -1);
if (version != FORMAT_VERSION) throw new IOException("Unsupported container transfer WAL version " + version);

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

requireType(root, "VolumeNanos", Tag.TAG_LONG);
long volumeNanos = root.getLongOr("VolumeNanos", -1L);
if (volumeNanos <= 0L) throw new IOException("Container transfer WAL volume is missing or invalid");

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

ContainerTransferJournal.ItemFingerprint beforeItem = getFingerprint(root, "Before");
ContainerTransferJournal.ItemFingerprint afterItem = getFingerprint(root, "After");

requireType(root, "Phase", Tag.TAG_STRING);
ContainerTransferJournal.Phase phase = parsePhase(root.getStringOr("Phase", ""));

return new ContainerTransferJournal(transactionId, regionId, dimensionId, baseGeneration, targetGeneration,
operation, volumeNanos, actor, beforeItem, afterItem, phase);
}

private static void putFingerprint(CompoundTag root, String prefix, ContainerTransferJournal.ItemFingerprint item) throws IOException {
root.putString(prefix + "Kind", itemKindName(item.kind()));
root.putLong(prefix + "Nanos", item.nanos());
}

private static ContainerTransferJournal.ItemFingerprint getFingerprint(CompoundTag root, String prefix) throws IOException {
requireType(root, prefix + "Kind", Tag.TAG_STRING);
requireType(root, prefix + "Nanos", Tag.TAG_LONG);
ContainerTransferJournal.ItemKind kind = parseItemKind(root.getStringOr(prefix + "Kind", ""));
long nanos = root.getLongOr(prefix + "Nanos", 0L);
if (kind == ContainerTransferJournal.ItemKind.PARTIAL) {
if (!com.example.item.ContainerFluidLedger.isPartialBucket(nanos)) {
throw new IOException(prefix + " partial bucket nanovolume is invalid: " + nanos);
}
} else if (nanos != -1L) {
throw new IOException(prefix + " non-partial bucket must not carry a nanovolume");
}
return new ContainerTransferJournal.ItemFingerprint(kind, nanos);
}

private static void validateDimension(String dimensionId) throws IOException {
if (Identifier.tryParse(dimensionId) == null) throw new IOException("Invalid container transfer WAL dimension: " + dimensionId);
}

private static void validateGeneration(long base, long target) throws IOException {
if (base < 0L || target != base + 1L) {
throw new IOException("Container transfer WAL generations are inconsistent: base " + base + ", target " + target);
}
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
};
}

private static ContainerTransferJournal.ItemKind parseItemKind(String name) throws IOException {
return switch (name) {
case "BUCKET" -> ContainerTransferJournal.ItemKind.BUCKET;
case "WATER_BUCKET" -> ContainerTransferJournal.ItemKind.WATER_BUCKET;
case "PARTIAL" -> ContainerTransferJournal.ItemKind.PARTIAL;
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
