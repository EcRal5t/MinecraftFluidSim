package com.example.simulation.persistence;

import com.example.chemistry.ChemicalRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32C;

/** Two-slot storage whose only commit point is an atomically replaced HEAD. */
public final class RegionStateFiles {
	private static final String HEAD = "HEAD.nbt";
	private static final String MANIFEST = "manifest.nbt";
	private static final String SLOT_A = "slot-a";
	private static final String SLOT_B = "slot-b";
	private static final long MAX_NBT_BYTES = 64L * 1024L * 1024L;

	private final Path root;
	private final RegionStateCodec codec;
	private final AtomicMover atomicMover;
	private final ChemicalRegistry chemicalRegistry;

	public RegionStateFiles(Path worldRoot) {
		this(worldRoot, RegionStateFiles::atomicMove, null);
	}

	public RegionStateFiles(Path worldRoot, ChemicalRegistry chemicalRegistry) {
		this(worldRoot, RegionStateFiles::atomicMove, chemicalRegistry);
	}

	RegionStateFiles(Path worldRoot, AtomicMover atomicMover) {
		this(worldRoot, atomicMover, null);
	}

	RegionStateFiles(Path worldRoot, AtomicMover atomicMover, ChemicalRegistry chemicalRegistry) {
		this.root = worldRoot.toAbsolutePath().normalize().resolve("realwater").resolve("region-state");
		this.codec = new RegionStateCodec();
		this.atomicMover = atomicMover;
		this.chemicalRegistry = chemicalRegistry;
	}

	public long publish(RegionState state) throws IOException {
		return publish(state, (point, path) -> { });
	}

	public long publish(RegionState state, FaultInjector injector) throws IOException {
		return commit(prepare(state, injector), injector);
	}

	/**
	 * Stages a fully verified generation in the inactive slot without publishing HEAD. Ownership
	 * transactions interleave their world WAL and world mutation between this and {@link #commit}.
	 */
	public PreparedGeneration prepare(RegionState state, FaultInjector injector) throws IOException {
		state.validate();
		UUID id = state.manifest().regionId();
		Path region = regionPath(id);
		Files.createDirectories(root);
		Head current = null;
		if (Files.exists(region)) {
			if (!Files.isDirectory(region)) {
				throw new IOException("Uncommitted or unknown region-state data locks " + region);
			}
			if (!Files.exists(region.resolve(HEAD))) {
				// A first publication that crashed before its HEAD publish leaves only our own staged
				// slots behind; those are discarded so the retry can proceed. Anything else stays locked.
				discardKnownStagedLeftovers(region);
				if (!isEmptyDirectory(region)) throw new IOException("Uncommitted or unknown region-state data locks " + region);
			} else {
				current = readHead(region, id);
				RegionState committed = readGeneration(region, current);
				if (current.generation() == state.manifest().checkpointGeneration()) {
					if (current.manifestChecksum() != state.manifest().checksum()) {
						throw new IOException("Checkpoint generation collision for " + id);
					}
					return PreparedGeneration.alreadyCommitted(id, current.generation());
				}
				if (state.manifest().checkpointGeneration() <= current.generation()) {
					throw new IOException("Checkpoint generation did not advance for " + id);
				}
				committed.validate();
			}
		}
		if (current == null) {
			Files.createDirectories(region);
		}

		String inactive = current == null || current.slot().equals(SLOT_B) ? SLOT_A : SLOT_B;
		Path inactivePath = region.resolve(inactive);
		Path temporary = region.resolve(inactive + ".tmp");
		injector.check(FaultPoint.BEFORE_SLOT_WRITE, temporary);
		deleteTree(temporary);
		deleteTree(inactivePath);
		Files.createDirectory(temporary);
		List<RegionSectionState> sections = new ArrayList<>(state.sections());
		sections.sort(Comparator.comparing(RegionSectionState::key));
		for (RegionSectionState section : sections) {
			Path path = temporary.resolve(sectionFile(section.key()));
			NbtIo.writeCompressed(codec.encodeSection(section), path);
			forceFile(path);
			injector.check(FaultPoint.AFTER_SECTION_WRITE, path);
		}
		Path manifestPath = temporary.resolve(MANIFEST);
		NbtIo.writeCompressed(codec.encodeManifest(state.manifest()), manifestPath);
		forceFile(manifestPath);
		injector.check(FaultPoint.AFTER_MANIFEST_WRITE, manifestPath);
		RegionState verified = readSlot(temporary);
		verified.validate();
		validateReconstruction(verified);
		injector.check(FaultPoint.AFTER_TEMP_VALIDATION, temporary);
		atomicMover.move(temporary, inactivePath, false);
		if (!Files.isDirectory(inactivePath) || Files.exists(temporary)) throw new IOException("Atomic slot publisher did not publish " + inactivePath);
		injector.check(FaultPoint.AFTER_SLOT_PUBLISH, inactivePath);
		return new PreparedGeneration(id, state.manifest().checkpointGeneration(), inactive, state.manifest().checksum(),
				current == null ? null : current.generation(), current == null ? null : current.manifestChecksum());
	}

	/** Publishes HEAD for a staged generation; this is the sole commit point. */
	public long commit(PreparedGeneration prepared, FaultInjector injector) throws IOException {
		if (prepared.alreadyCommitted()) return prepared.generation();
		UUID id = prepared.id();
		Path region = regionPath(id);
		validateBaseUnchanged(prepared, region);
		Head next = new Head(id, prepared.generation(), prepared.slot(), prepared.manifestChecksum());
		Path headTemporary = region.resolve(HEAD + ".tmp");
		NbtIo.writeCompressed(encodeHead(next), headTemporary);
		forceFile(headTemporary);
		decodeHead(NbtIo.readCompressed(headTemporary, NbtAccounter.create(MAX_NBT_BYTES)), id);
		injector.check(FaultPoint.BEFORE_HEAD_PUBLISH, headTemporary);
		atomicMover.move(headTemporary, region.resolve(HEAD), true);
		readGeneration(region, readHead(region, id));
		injector.check(FaultPoint.AFTER_HEAD_PUBLISH, region.resolve(HEAD));
		return next.generation();
	}

	/** Discards a staged generation; the base committed generation stays authoritative. */
	public void abort(PreparedGeneration prepared) throws IOException {
		if (prepared.alreadyCommitted()) return;
		Path region = regionPath(prepared.id());
		deleteTree(region.resolve(HEAD + ".tmp"));
		deleteTree(region.resolve(prepared.slot()));
		if (prepared.baseGeneration() == null && !Files.exists(region.resolve(HEAD))) {
			deleteTree(region);
		}
	}

	/**
	 * Removes the inactive generation after WAL recovery has proved that HEAD still names the base.
	 * The active slot is never touched. For an aborted first publication there is no HEAD, so only
	 * the implementation's known staged filenames are eligible for deletion.
	 */
	public void discardUncommittedAfterRollback(UUID id, long expectedBaseGeneration) throws IOException {
		Path region = regionPath(id);
		if (!Files.exists(region)) return;
		if (expectedBaseGeneration == 0L) {
			if (Files.exists(region.resolve(HEAD))) {
				throw new IOException("A region HEAD appeared while discarding first-publication staging for " + id);
			}
			discardKnownStagedLeftovers(region);
			if (!isEmptyDirectory(region)) {
				throw new IOException("Unknown content prevents staged rollback cleanup for " + id);
			}
			Files.delete(region);
			return;
		}
		Head current = readHead(region, id);
		if (current.generation() != expectedBaseGeneration) {
			throw new IOException("Region HEAD moved while discarding rolled-back staging for " + id);
		}
		String inactive = current.slot().equals(SLOT_A) ? SLOT_B : SLOT_A;
		deleteTree(region.resolve(inactive));
		deleteTree(region.resolve(inactive + ".tmp"));
		deleteTree(region.resolve(HEAD + ".tmp"));
	}

	private void validateBaseUnchanged(PreparedGeneration prepared, Path region) throws IOException {
		if (prepared.baseGeneration() == null) {
			if (Files.exists(region.resolve(HEAD))) throw new IOException("Base HEAD appeared for " + prepared.id());
			return;
		}
		Head current = readHead(region, prepared.id());
		if (current.generation() != prepared.baseGeneration() || current.manifestChecksum() != prepared.baseManifestChecksum()) {
			throw new IOException("Base HEAD moved before commit for " + prepared.id());
		}
	}

	public RegionManifest readManifest(UUID id) throws IOException {
		Path region = regionPath(id);
		Head head = readHead(region, id);
		Path manifestPath = region.resolve(head.slot()).resolve(MANIFEST);
		RegionManifest manifest = codec.decodeManifest(readNbt(manifestPath));
		validateHeadBinding(head, manifest);
		if (RegionStateIntegrity.manifestChecksum(manifest) != manifest.checksum()) throw new IOException("Manifest checksum mismatch");
		return manifest;
	}

	public RegionState read(UUID id) throws IOException {
		Path region = regionPath(id);
		return readGeneration(region, readHead(region, id));
	}

	/**
	 * HEAD generation of a committed region, or 0 when no generation was ever committed. A present
	 * but corrupt HEAD still throws; whether staged leftovers may be retried is decided by prepare.
	 */
	public long committedGeneration(UUID id) throws IOException {
		Path region = regionPath(id);
		if (!Files.exists(region) || !Files.exists(region.resolve(HEAD))) return 0L;
		return readHead(region, id).generation();
	}

	/**
	 * Removes only a never-committed first generation whose directory contains the exact staged
	 * filenames produced by this implementation and no HEAD. This recovery must run before
	 * metadata discovery: after a crash before the first HEAD publish there is no admitted UUID
	 * through which a controller retry could otherwise reach {@link #prepare}.
	 *
	 * <p>Unknown names, non-directories and any directory with a HEAD are left untouched. Discovery
	 * will report them as locked.</p>
	 */
	public void recoverKnownFirstPublicationStaging() throws IOException {
		if (!Files.exists(root)) return;
		if (!Files.isDirectory(root)) throw new IOException("Region-state root is not a directory: " + root);
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
			for (Path region : entries) {
				if (!Files.isDirectory(region) || region.getFileName().toString().startsWith(".")) continue;
				try {
					UUID.fromString(region.getFileName().toString());
				} catch (IllegalArgumentException unknownName) {
					continue;
				}
				if (Files.exists(region.resolve(HEAD))) continue;
				discardKnownStagedLeftovers(region);
				if (isEmptyDirectory(region)) Files.delete(region);
			}
		}
	}

	/** Reads only committed manifests. Locked paths are retained and returned as issues. */
	public Discovery discover() throws IOException {
		if (!Files.exists(root)) return new Discovery(List.of(), Map.of());
		if (!Files.isDirectory(root)) throw new IOException("Region-state root is not a directory: " + root);
		List<RegionManifest> manifests = new ArrayList<>();
		Map<Path, String> issues = new HashMap<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
			for (Path entry : entries) {
				if (entry.getFileName().toString().startsWith(".")) continue;
				if (!Files.isDirectory(entry)) {
					issues.put(entry, "Unknown non-directory content in region-state root");
					continue;
				}
				try {
					UUID id = UUID.fromString(entry.getFileName().toString());
					manifests.add(readManifest(id));
				} catch (IllegalArgumentException | IOException exception) {
					issues.put(entry, exception.getMessage());
				}
			}
		}
		manifests.sort(Comparator.comparing(manifest -> manifest.regionId().toString()));
		return new Discovery(manifests, issues);
	}

	/** Atomically makes a checkpoint undiscoverable before best-effort physical cleanup. */
	public Path quarantine(UUID id) throws IOException {
		Path region = regionPath(id);
		if (!Files.exists(region)) return null;
		readHead(region, id);
		Path quarantine = root.resolve(".quarantine-" + id + "-" + UUID.randomUUID());
		atomicMover.move(region, quarantine, false);
		try { deleteTree(quarantine); } catch (IOException ignored) { }
		return quarantine;
	}

	private static void discardKnownStagedLeftovers(Path region) throws IOException {
		Set<String> known = Set.of(SLOT_A, SLOT_B, SLOT_A + ".tmp", SLOT_B + ".tmp", HEAD + ".tmp");
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(region)) {
			for (Path entry : entries) {
				if (!known.contains(entry.getFileName().toString())) return;
			}
		}
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(region)) {
			for (Path entry : entries) deleteTree(entry);
		}
	}

	private static boolean isEmptyDirectory(Path region) throws IOException {
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(region)) {
			return !entries.iterator().hasNext();
		}
	}

	private RegionState readGeneration(Path region, Head head) throws IOException {
		RegionState state = readSlot(region.resolve(head.slot()));
		validateHeadBinding(head, state.manifest());
		state.validate();
		return state;
	}

	private void validateReconstruction(RegionState state) throws IOException {
		if (state.manifest().formatVersion() == 1) state.reconstruct();
		else if (state.manifest().formatVersion() == 2) state.reconstructMaterial();
		else if (state.manifest().formatVersion() == 3) {
			if (chemicalRegistry == null) throw new IOException("region-state v3 verification requires the authoritative chemical registry");
			state.reconstructChemical(chemicalRegistry);
		}
		else throw new IOException("Unsupported region-state version " + state.manifest().formatVersion());
	}

	private RegionState readSlot(Path slot) throws IOException {
		if (!Files.isDirectory(slot)) throw new IOException("Missing committed region slot " + slot);
		RegionManifest manifest = codec.decodeManifest(readNbt(slot.resolve(MANIFEST)));
		Map<RegionSectionState.SectionKey, RegionSectionState> sections = new HashMap<>();
		Set<String> expectedFiles = new HashSet<>();
		for (RegionManifest.SectionDescriptor descriptor : manifest.sections()) {
			String file = sectionFile(descriptor.key());
			if (!expectedFiles.add(file)) throw new IOException("Duplicate section filename in manifest");
			RegionSectionState section = codec.decodeSection(readNbt(slot.resolve(file)));
			if (sections.put(section.key(), section) != null) throw new IOException("Duplicate section coordinate");
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(slot, "*.nbt")) {
			int actual = 0;
			for (Path file : files) if (!file.getFileName().toString().equals(MANIFEST)) {
				actual++;
				if (!expectedFiles.contains(file.getFileName().toString())) throw new IOException("Unexpected section file " + file);
			}
			if (actual != expectedFiles.size()) throw new IOException("Missing or extra section files");
		}
		return new RegionState(manifest, new ArrayList<>(sections.values()));
	}

	private Head readHead(Path region, UUID expectedId) throws IOException {
		if (!Files.isDirectory(region)) throw new IOException("Missing region-state directory " + region);
		return decodeHead(readNbt(region.resolve(HEAD)), expectedId);
	}

	private static CompoundTag encodeHead(Head head) {
		CompoundTag tag = new CompoundTag();
		tag.putInt("Version", 1); tag.putLong("UuidMost", head.id().getMostSignificantBits()); tag.putLong("UuidLeast", head.id().getLeastSignificantBits());
		tag.putLong("Generation", head.generation()); tag.putString("Slot", head.slot()); tag.putLong("ManifestChecksum", head.manifestChecksum());
		tag.putLong("Checksum", headChecksum(head.id(), head.generation(), head.slot(), head.manifestChecksum()));
		return tag;
	}

	private static Head decodeHead(CompoundTag tag, UUID expectedId) throws IOException {
		int version = tag.getIntOr("Version", -1);
		UUID id = new UUID(tag.getLongOr("UuidMost", 0L), tag.getLongOr("UuidLeast", 0L));
		long generation = tag.getLongOr("Generation", -1L), manifestChecksum = tag.getLongOr("ManifestChecksum", -1L);
		String slot = tag.getStringOr("Slot", "");
		if (version != 1 || !id.equals(expectedId) || generation <= 0L || (!slot.equals(SLOT_A) && !slot.equals(SLOT_B))) {
			throw new IOException("Invalid region-state HEAD for " + expectedId);
		}
		if (tag.getLongOr("Checksum", -1L) != headChecksum(id, generation, slot, manifestChecksum)) {
			throw new IOException("Region-state HEAD checksum mismatch");
		}
		return new Head(id, generation, slot, manifestChecksum);
	}

	private static long headChecksum(UUID id, long generation, String slot, long manifestChecksum) {
		CRC32C crc = new CRC32C();
		updateLong(crc, id.getMostSignificantBits()); updateLong(crc, id.getLeastSignificantBits());
		updateLong(crc, generation); crc.update(slot.getBytes(java.nio.charset.StandardCharsets.UTF_8)); updateLong(crc, manifestChecksum);
		return crc.getValue();
	}

	private static void updateLong(CRC32C crc, long value) { for (int shift = 56; shift >= 0; shift -= 8) crc.update((int) (value >>> shift) & 0xff); }
	private static void validateHeadBinding(Head head, RegionManifest manifest) throws IOException {
		if (!head.id().equals(manifest.regionId()) || head.generation() != manifest.checkpointGeneration()
				|| head.manifestChecksum() != manifest.checksum()) throw new IOException("HEAD does not bind the active manifest");
	}

	private static CompoundTag readNbt(Path path) throws IOException {
		if (!Files.isRegularFile(path)) throw new IOException("Missing region-state file " + path);
		return NbtIo.readCompressed(path, NbtAccounter.create(MAX_NBT_BYTES));
	}

	private Path regionPath(UUID id) { return root.resolve(id.toString()); }
	private static String sectionFile(RegionSectionState.SectionKey key) { return "section_" + key.x() + "_" + key.y() + "_" + key.z() + ".nbt"; }

	private static void forceFile(Path path) throws IOException {
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
	}

	private static void deleteTree(Path path) throws IOException {
		if (!Files.exists(path)) return;
		if (!Files.isDirectory(path)) { Files.delete(path); return; }
		try (var stream = Files.walk(path)) {
			for (Path entry : stream.sorted(Comparator.reverseOrder()).toList()) Files.delete(entry);
		}
	}

	/** Windows antivirus/indexing can briefly hold handles inside freshly written directories. */
	private static void atomicMove(Path source, Path target, boolean replace) throws IOException {
		StandardCopyOption[] options = replace
				? new StandardCopyOption[] {StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING}
				: new StandardCopyOption[] {StandardCopyOption.ATOMIC_MOVE};
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

	public enum FaultPoint { BEFORE_SLOT_WRITE, AFTER_SECTION_WRITE, AFTER_MANIFEST_WRITE, AFTER_TEMP_VALIDATION, AFTER_SLOT_PUBLISH, BEFORE_HEAD_PUBLISH, AFTER_HEAD_PUBLISH }
	@FunctionalInterface public interface FaultInjector { void check(FaultPoint point, Path path) throws IOException; }
	@FunctionalInterface interface AtomicMover { void move(Path source, Path target, boolean replace) throws IOException; }

	/**
	 * A staged-but-uncommitted generation. {@code slot == null} marks an idempotent no-op whose
	 * generation is already committed; {@code baseGeneration == null} marks a first publication.
	 */
	public record PreparedGeneration(
			UUID id,
			long generation,
			String slot,
			long manifestChecksum,
			Long baseGeneration,
			Long baseManifestChecksum
	) {
		public PreparedGeneration {
			java.util.Objects.requireNonNull(id, "id");
		}

		static PreparedGeneration alreadyCommitted(UUID id, long generation) {
			return new PreparedGeneration(id, generation, null, 0L, null, null);
		}

		public boolean alreadyCommitted() {
			return slot == null;
		}
	}

	public record Discovery(List<RegionManifest> manifests, Map<Path, String> issues) {
		public Discovery { manifests = List.copyOf(manifests); issues = Map.copyOf(issues); }
	}
	private record Head(UUID id, long generation, String slot, long manifestChecksum) { }
}
