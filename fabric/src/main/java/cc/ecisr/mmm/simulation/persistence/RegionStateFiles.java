package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.recovery.CrossDomainRecovery;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

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
	private static final String REMOVED = "removed.nbt";
	private static final String SLOT_A = "slot-a";
	private static final String SLOT_B = "slot-b";
	private static final long MAX_NBT_BYTES = 64L * 1024L * 1024L;
	private static final long REMOVAL_QUARANTINE_OVERHEAD_ENTRIES = 6L;
	private static final long REMOVAL_QUARANTINE_MANIFEST_BUDGET_BYTES = 4_096L;

	private final Path root;
	private final RegionStateCodec codec;
	private final AtomicMover atomicMover;
	private final ChemicalRegistry chemicalRegistry;
	private final GranularRegistry granularRegistry;
	private final ThermalRegistry thermalRegistry;
	private final GasRegistry gasRegistry;

	public RegionStateFiles(Path worldRoot) {
		this(worldRoot, RegionStateFiles::atomicMove, null, null, null, null);
	}

	public RegionStateFiles(Path worldRoot, ChemicalRegistry chemicalRegistry) {
		this(worldRoot, RegionStateFiles::atomicMove, chemicalRegistry, null, null, null);
	}

	public RegionStateFiles(Path worldRoot, ChemicalRegistry chemicalRegistry, GranularRegistry granularRegistry) {
		this(worldRoot, RegionStateFiles::atomicMove, chemicalRegistry, granularRegistry, null, null);
	}

	public RegionStateFiles(
			Path worldRoot, ChemicalRegistry chemicalRegistry, GranularRegistry granularRegistry,
			ThermalRegistry thermalRegistry
	) {
		this(worldRoot, RegionStateFiles::atomicMove, chemicalRegistry, granularRegistry, thermalRegistry, null);
	}

	public RegionStateFiles(
			Path worldRoot, ChemicalRegistry chemicalRegistry, GranularRegistry granularRegistry,
			ThermalRegistry thermalRegistry, GasRegistry gasRegistry
	) {
		this(worldRoot, RegionStateFiles::atomicMove, chemicalRegistry, granularRegistry, thermalRegistry, gasRegistry);
	}

	RegionStateFiles(Path worldRoot, AtomicMover atomicMover) {
		this(worldRoot, atomicMover, null, null, null, null);
	}

	RegionStateFiles(Path worldRoot, AtomicMover atomicMover, ChemicalRegistry chemicalRegistry) {
		this(worldRoot, atomicMover, chemicalRegistry, null, null, null);
	}

	RegionStateFiles(
			Path worldRoot, AtomicMover atomicMover, ChemicalRegistry chemicalRegistry, GranularRegistry granularRegistry
	) {
		this(worldRoot, atomicMover, chemicalRegistry, granularRegistry, null, null);
	}

	RegionStateFiles(
			Path worldRoot, AtomicMover atomicMover, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, ThermalRegistry thermalRegistry
	) {
		this(worldRoot, atomicMover, chemicalRegistry, granularRegistry, thermalRegistry, null);
	}

	RegionStateFiles(
			Path worldRoot, AtomicMover atomicMover, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, ThermalRegistry thermalRegistry, GasRegistry gasRegistry
	) {
		this.root = worldRoot.toAbsolutePath().normalize().resolve("mmm").resolve("region-state");
		this.codec = new RegionStateCodec();
		this.atomicMover = atomicMover;
		this.chemicalRegistry = chemicalRegistry;
		this.granularRegistry = granularRegistry;
		this.thermalRegistry = thermalRegistry;
		this.gasRegistry = gasRegistry;
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
			DurableFileOperations.forceDirectory(root);
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
		DurableFileOperations.forceDirectory(temporary);
		injector.check(FaultPoint.AFTER_MANIFEST_WRITE, manifestPath);
		RegionState verified = readSlot(temporary);
		verified.validate();
		validateReconstruction(verified);
		injector.check(FaultPoint.AFTER_TEMP_VALIDATION, temporary);
		atomicMover.move(temporary, inactivePath, false);
		if (!Files.isDirectory(inactivePath) || Files.exists(temporary)) throw new IOException("Atomic slot publisher did not publish " + inactivePath);
		DurableFileOperations.forceDirectory(region);
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
		Head next = new Head(id, prepared.generation(), HeadKind.ACTIVE, prepared.slot(), prepared.manifestChecksum());
		Path headTemporary = region.resolve(HEAD + ".tmp");
		NbtIo.writeCompressed(encodeHead(next), headTemporary);
		forceFile(headTemporary);
		decodeHead(NbtIo.readCompressed(headTemporary, NbtAccounter.create(MAX_NBT_BYTES)), id);
		injector.check(FaultPoint.BEFORE_HEAD_PUBLISH, headTemporary);
		atomicMover.move(headTemporary, region.resolve(HEAD), true);
		DurableFileOperations.forceDirectory(region);
		readGeneration(region, readHead(region, id));
		injector.check(FaultPoint.AFTER_HEAD_PUBLISH, region.resolve(HEAD));
		return next.generation();
	}

	/** Stages a terminal tombstone in the inactive slot after re-reading the exact ACTIVE base. */
	public PreparedRemoval prepareRemoval(
			UUID id, long expectedBaseGeneration, long expectedManifestChecksum,
			long networkTombstoneGeneration, UUID quarantineTransactionId,
			String capabilityProfileFingerprint, String registryAggregateFingerprint,
			long maximumQuarantineEntries, long maximumQuarantineBytes
	) throws IOException {
		Path region = regionPath(id);
		Head current = readHead(region, id);
		if (current.kind() != HeadKind.ACTIVE || current.generation() != expectedBaseGeneration
				|| current.manifestChecksum() != expectedManifestChecksum) {
			throw new IOException("ACTIVE region HEAD moved before removal staging");
		}
		readGeneration(region, current);
		long target = GenerationSequence.next(expectedBaseGeneration, "Removed region tombstone");
		RemovedRegionManifest unsigned = new RemovedRegionManifest(id, capabilityProfileFingerprint,
				registryAggregateFingerprint, expectedBaseGeneration, target, expectedManifestChecksum,
				networkTombstoneGeneration, quarantineTransactionId,
				RemovedRegionManifest.ZERO_INVENTORY_DIGEST, 0L);
		RemovedRegionManifest removed = withRemovedChecksum(unsigned);
		String inactive = current.slot().equals(SLOT_B) ? SLOT_A : SLOT_B;
		Path inactivePath = region.resolve(inactive), temporary = region.resolve(inactive + ".tmp");
		Path quarantineDestination = removedQuarantinePath(id, target);
		if (Files.exists(quarantineDestination)) {
			throw new IOException("Removed-region quarantine target already exists");
		}
		requireRemovalQuarantineCapacity(region.resolve(current.slot()), maximumQuarantineEntries, maximumQuarantineBytes);
		deleteTree(temporary); deleteTree(inactivePath); Files.createDirectory(temporary);
		Path removedPath = temporary.resolve(REMOVED);
		NbtIo.writeCompressed(encodeRemoved(removed), removedPath); forceFile(removedPath);
		DurableFileOperations.forceDirectory(temporary);
		if (!readRemovedSlot(temporary, id).equals(removed)) throw new IOException("Removed tombstone readback changed");
		atomicMover.move(temporary, inactivePath, false); DurableFileOperations.forceDirectory(region);
		return new PreparedRemoval(removed, inactive, current.slot(), maximumQuarantineEntries, maximumQuarantineBytes);
	}

	/** Publishes the REMOVED HEAD as the sole terminal commit point. */
	public RemovedRegionManifest commitRemoval(PreparedRemoval prepared) throws IOException {
		RemovedRegionManifest removed = prepared.manifest();
		Path region = regionPath(removed.regionId());
		Head current = readHead(region, removed.regionId());
		if (current.kind() == HeadKind.REMOVED) return readRemovedGeneration(region, current);
		if (current.kind() != HeadKind.ACTIVE || current.generation() != removed.baseGeneration()
				|| current.manifestChecksum() != removed.oldManifestChecksum()
				|| !current.slot().equals(prepared.activeSlot())) {
			throw new IOException("ACTIVE region HEAD moved before REMOVED commit");
		}
		RemovedRegionManifest verified = readRemovedSlot(region.resolve(prepared.tombstoneSlot()), removed.regionId());
		if (!verified.equals(removed)) throw new IOException("Staged REMOVED tombstone changed before commit");
		Head next = new Head(removed.regionId(), removed.targetGeneration(), HeadKind.REMOVED,
				prepared.tombstoneSlot(), removed.checksum());
		Path temporary = region.resolve(HEAD + ".tmp");
		NbtIo.writeCompressed(encodeHead(next), temporary); forceFile(temporary);
		decodeHead(readNbt(temporary), removed.regionId());
		atomicMover.move(temporary, region.resolve(HEAD), true); DurableFileOperations.forceDirectory(region);
		return readRemovedGeneration(region, readHead(region, removed.regionId()));
	}

	public void abortRemoval(PreparedRemoval prepared) throws IOException {
		RemovedRegionManifest removed = prepared.manifest(); Path region = regionPath(removed.regionId());
		Head current = readHead(region, removed.regionId());
		if (current.kind() != HeadKind.ACTIVE || current.generation() != removed.baseGeneration()
				|| current.manifestChecksum() != removed.oldManifestChecksum()) {
			throw new IOException("Region HEAD moved while aborting REMOVED staging");
		}
		deleteTree(region.resolve(prepared.tombstoneSlot()));
		deleteTree(region.resolve(prepared.tombstoneSlot() + ".tmp"));
	}

	public void requireRemovalQuarantineCapacity(UUID id, long maximumEntries, long maximumBytes) throws IOException {
		Path region = regionPath(id); Head head = readHead(region, id);
		if (head.kind() != HeadKind.ACTIVE) throw new IOException("Region is not ACTIVE");
		long target = GenerationSequence.next(head.generation(), "Removed region tombstone");
		if (Files.exists(removedQuarantinePath(id, target))) {
			throw new IOException("Removed-region quarantine target already exists");
		}
		requireRemovalQuarantineCapacity(region.resolve(head.slot()), maximumEntries, maximumBytes);
	}

	/** Moves the no-longer-referenced ACTIVE slot into durable quarantine and verifies its manifest. */
	public Path convergeRemovalQuarantine(
			UUID id, long maximumQuarantineEntries, long maximumQuarantineBytes
	) throws IOException {
		Path region = regionPath(id); Head head = readHead(region, id);
		RemovedRegionManifest removed = readRemovedGeneration(region, head);
		String activeSlot = head.slot().equals(SLOT_A) ? SLOT_B : SLOT_A;
		Path source = region.resolve(activeSlot), destination = removedQuarantinePath(id, removed.targetGeneration());
		Path payload = destination.resolve("payload"), manifestPath = destination.resolve("quarantine.nbt");
		CrossDomainRecovery.ExternalTruth truth = classifyRemovedQuarantine(
				source, destination, payload, manifestPath, removed);
		CrossDomainRecovery.Decision decision = removalDecision(head, removed,
				CrossDomainRecovery.JournalPhase.REGION_COMMITTED, truth, false);
		if (decision.outcome() == CrossDomainRecovery.Outcome.LOCKED) {
			throw new IOException("REMOVED quarantine recovery is LOCKED: " + decision.reason());
		}
		if (decision.outcome() != CrossDomainRecovery.Outcome.CONVERGE_AFTER) {
			throw new IOException("REMOVED quarantine recovery produced " + decision.outcome());
		}
		if (truth == CrossDomainRecovery.ExternalTruth.AFTER) return destination;
		if (!Files.exists(destination)) {
			DurableFileOperations.createDirectories(destination); DurableFileOperations.forceDirectory(destination.getParent());
		}
		if (Files.exists(source)) {
			atomicMover.move(source, payload, false); DurableFileOperations.forceDirectory(destination);
		}
		validateRemovedPayload(payload, removed);
		if (!Files.exists(manifestPath)) {
			Path temporary = destination.resolve("quarantine.nbt.tmp");
			if (Files.exists(temporary)) {
				RemovedRegionManifest staged = decodeRemoved(readNbt(temporary), id);
				if (!staged.equals(removed)) throw new IOException("Staged quarantine manifest differs from REMOVED HEAD");
			} else {
				NbtIo.writeCompressed(encodeRemoved(removed), temporary); forceFile(temporary);
			}
			atomicMover.move(temporary, manifestPath, false); DurableFileOperations.forceDirectory(destination);
		}
		if (classifyRemovedQuarantine(source, destination, payload, manifestPath, removed)
				!= CrossDomainRecovery.ExternalTruth.AFTER) {
			throw new IOException("Removed-region quarantine did not reach exact AFTER truth");
		}
		return destination;
	}

	public RemovedRegionManifest readRemoved(UUID id) throws IOException {
		Path region = regionPath(id);
		return readRemovedGeneration(region, readHead(region, id));
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
	 * Reads the inactive slot only when it is the immediately preceding complete generation.
	 * This is an operator-recovery probe; ordinary discovery and restore continue to follow HEAD
	 * exclusively and never fall back automatically.
	 */
	public PreviousGeneration readPrevious(UUID id, long expectedCurrentGeneration) throws IOException {
		Path region = regionPath(id);
		Head current = readHead(region, id);
		if (current.generation() != expectedCurrentGeneration) {
			throw new IOException("Region HEAD moved; expected generation " + expectedCurrentGeneration
					+ " but found " + current.generation());
		}
		String previousSlot = current.slot().equals(SLOT_A) ? SLOT_B : SLOT_A;
		RegionState previous = readSlot(region.resolve(previousSlot));
		previous.validate();
		validateReconstruction(previous);
		RegionManifest manifest = previous.manifest();
		if (!manifest.regionId().equals(id)
				|| manifest.checkpointGeneration() != expectedCurrentGeneration - 1L) {
			throw new IOException("Inactive slot is not the immediately preceding generation");
		}
		return new PreviousGeneration(id, current.generation(), current.manifestChecksum(), previousSlot, previous);
	}

	/**
	 * Atomically moves only HEAD to a caller-probed previous generation. The rejected current slot
	 * remains byte-for-byte available until the next successful publication overwrites it. This
	 * exceptional operation requires an explicit expected generation and is never called by startup.
	 */
	public long activatePrevious(PreviousGeneration previous) throws IOException {
		if (previous == null) throw new IOException("Previous-generation recovery plan is missing");
		Path region = regionPath(previous.id());
		Head current = readHead(region, previous.id());
		if (current.generation() != previous.currentGeneration()
				|| current.manifestChecksum() != previous.currentManifestChecksum()) {
			throw new IOException("Region HEAD moved after previous-generation validation");
		}
		String expectedPreviousSlot = current.slot().equals(SLOT_A) ? SLOT_B : SLOT_A;
		if (!expectedPreviousSlot.equals(previous.slot())) {
			throw new IOException("Previous-generation slot changed after validation");
		}
		RegionState verified = readSlot(region.resolve(previous.slot()));
		verified.validate();
		validateReconstruction(verified);
		if (verified.manifest().checkpointGeneration() != current.generation() - 1L
				|| verified.manifest().checksum() != previous.state().manifest().checksum()
				|| !verified.manifest().regionId().equals(previous.id())) {
			throw new IOException("Previous generation changed after validation");
		}
		Head restored = new Head(previous.id(), verified.manifest().checkpointGeneration(),
				HeadKind.ACTIVE, previous.slot(), verified.manifest().checksum());
		Path headTemporary = region.resolve(HEAD + ".tmp");
		Files.deleteIfExists(headTemporary);
		NbtIo.writeCompressed(encodeHead(restored), headTemporary);
		forceFile(headTemporary);
		decodeHead(NbtIo.readCompressed(headTemporary, NbtAccounter.create(MAX_NBT_BYTES)), previous.id());
		atomicMover.move(headTemporary, region.resolve(HEAD), true);
		DurableFileOperations.forceDirectory(region);
		readGeneration(region, readHead(region, previous.id()));
		return restored.generation();
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

	public long committedManifestChecksum(UUID id) throws IOException {
		Path region = regionPath(id);
		if (!Files.exists(region.resolve(HEAD))) return -1L;
		return readHead(region, id).manifestChecksum();
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

	/** Fully validates committed generations during discovery. Locked paths are retained as issues. */
	public Discovery discover() throws IOException {
		return discover(true);
	}

	/**
	 * Validates HEAD, every committed file, CRC and format-local ledgers without resolving stable
	 * registry ids. This is used only while deciding whether a missing world registry may be first
	 * published; ordinary admission must use {@link #discover()}.
	 */
	public Discovery discoverCommittedStructure() throws IOException {
		return discover(false);
	}

	private Discovery discover(boolean reconstruct) throws IOException {
		if (!Files.exists(root)) return new Discovery(List.of(), List.of(), Map.of());
		if (!Files.isDirectory(root)) throw new IOException("Region-state root is not a directory: " + root);
		List<RegionManifest> manifests = new ArrayList<>();
		List<RemovedRegionManifest> removed = new ArrayList<>();
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
					Head head = readHead(entry, id);
					recoverUncommittedRemovalStaging(entry, head);
					if (head.kind() == HeadKind.REMOVED) {
						removed.add(readRemovedGeneration(entry, head));
					} else {
						RegionState committed = readGeneration(entry, head);
						if (reconstruct) validateReconstruction(committed);
						manifests.add(committed.manifest());
					}
				} catch (IllegalArgumentException | IOException exception) {
					issues.put(entry, exception.getMessage());
				}
			}
		}
		manifests.sort(Comparator.comparing(manifest -> manifest.regionId().toString()));
		removed.sort(Comparator.comparing(manifest -> manifest.regionId().toString()));
		return new Discovery(manifests, removed, issues);
	}

	/** An ACTIVE HEAD proves that an exact inactive REMOVED candidate never committed. */
	private void recoverUncommittedRemovalStaging(Path region, Head active) throws IOException {
		if (active.kind() != HeadKind.ACTIVE) return;
		String inactiveName = active.slot().equals(SLOT_A) ? SLOT_B : SLOT_A;
		Path inactive = region.resolve(inactiveName), temporary = region.resolve(inactiveName + ".tmp");
		boolean inactiveRemoval = Files.exists(inactive.resolve(REMOVED));
		boolean temporaryRemoval = Files.exists(temporary.resolve(REMOVED));
		if (!inactiveRemoval && !temporaryRemoval) return;
		if (inactiveRemoval && temporaryRemoval) {
			throw new IOException("REMOVED staging exists in both inactive and temporary slots");
		}
		Path stagedPath = inactiveRemoval ? inactive : temporary;
		RemovedRegionManifest staged = readRemovedSlot(stagedPath, active.id());
		if (staged.baseGeneration() != active.generation()
				|| staged.oldManifestChecksum() != active.manifestChecksum()) {
			throw new IOException("Uncommitted REMOVED staging does not bind the ACTIVE HEAD");
		}
		readGeneration(region, active);
		Path quarantine = removedQuarantinePath(active.id(), staged.targetGeneration());
		CrossDomainRecovery.ExternalTruth quarantineTruth = Files.exists(quarantine)
				? CrossDomainRecovery.ExternalTruth.THIRD : CrossDomainRecovery.ExternalTruth.BEFORE;
		CrossDomainRecovery.Decision decision = removalDecision(active, staged,
				CrossDomainRecovery.JournalPhase.PREPARED, quarantineTruth, false);
		if (decision.outcome() != CrossDomainRecovery.Outcome.DISCARD_UNCOMMITTED) {
			throw new IOException("Uncommitted REMOVED recovery is LOCKED: " + decision.reason());
		}
		Path headTemporary = region.resolve(HEAD + ".tmp");
		if (Files.exists(headTemporary)) {
			Head candidate = decodeHead(readNbt(headTemporary), active.id());
			if (candidate.kind() != HeadKind.REMOVED || candidate.generation() != staged.targetGeneration()
					|| !candidate.slot().equals(inactiveName)
					|| candidate.manifestChecksum() != staged.checksum()) {
				throw new IOException("Uncommitted REMOVED HEAD staging does not bind its tombstone");
			}
		}
		deleteTree(stagedPath);
		deleteTree(headTemporary);
		DurableFileOperations.forceDirectory(region);
	}

	private void discardKnownStagedLeftovers(Path region) throws IOException {
		Set<String> known = Set.of(SLOT_A, SLOT_B, SLOT_A + ".tmp", SLOT_B + ".tmp", HEAD + ".tmp");
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(region)) {
			for (Path entry : entries) {
				if (!known.contains(entry.getFileName().toString())) return;
				String name = entry.getFileName().toString();
				try {
					if (name.equals(HEAD + ".tmp")) {
						UUID expectedId = UUID.fromString(region.getFileName().toString());
						decodeHead(readNbt(entry), expectedId);
					} else {
						readSlot(entry);
					}
				} catch (IllegalArgumentException | IOException invalid) {
					// A wrong node type, corrupt partial write or foreign nested file is ambiguous.
					// Preserve the whole tree so discovery can report it as LOCKED.
					return;
				}
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
		if (head.kind() != HeadKind.ACTIVE) throw new IOException("Region HEAD is a REMOVED tombstone");
		RegionState state = readSlot(region.resolve(head.slot()));
		validateHeadBinding(head, state.manifest());
		state.validate();
		return state;
	}

	private RemovedRegionManifest readRemovedGeneration(Path region, Head head) throws IOException {
		if (head.kind() != HeadKind.REMOVED) throw new IOException("Region HEAD is not REMOVED");
		RemovedRegionManifest removed = readRemovedSlot(region.resolve(head.slot()), head.id());
		if (removed.targetGeneration() != head.generation() || removed.checksum() != head.manifestChecksum()) {
			throw new IOException("REMOVED HEAD does not bind its tombstone manifest");
		}
		return removed;
	}

	private RemovedRegionManifest readRemovedSlot(Path slot, UUID expectedId) throws IOException {
		if (!Files.isDirectory(slot)) throw new IOException("Missing REMOVED tombstone slot");
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(slot)) {
			int count = 0;
			for (Path entry : entries) {
				count++;
				if (!Files.isRegularFile(entry) || !entry.getFileName().toString().equals(REMOVED)) {
					throw new IOException("Unexpected REMOVED tombstone slot content");
				}
			}
			if (count != 1) throw new IOException("Incomplete REMOVED tombstone slot");
		}
		return decodeRemoved(readNbt(slot.resolve(REMOVED)), expectedId);
	}

	private static CompoundTag encodeRemoved(RemovedRegionManifest value) {
		CompoundTag tag = new CompoundTag(); tag.putString("Schema", "removed-region");
		tag.putLong("UuidMost", value.regionId().getMostSignificantBits()); tag.putLong("UuidLeast", value.regionId().getLeastSignificantBits());
		tag.putString("ProfileFingerprint", value.capabilityProfileFingerprint());
		tag.putString("RegistryAggregateFingerprint", value.registryAggregateFingerprint());
		tag.putLong("BaseGeneration", value.baseGeneration()); tag.putLong("TargetGeneration", value.targetGeneration());
		tag.putLong("OldManifestChecksum", value.oldManifestChecksum());
		tag.putLong("NetworkTombstoneGeneration", value.networkTombstoneGeneration());
		tag.putLong("QuarantineMost", value.quarantineTransactionId().getMostSignificantBits());
		tag.putLong("QuarantineLeast", value.quarantineTransactionId().getLeastSignificantBits());
		tag.putLong("ZeroInventoryDigest", value.zeroInventoryDigest()); tag.putLong("Checksum", value.checksum());
		return tag;
	}

	private static RemovedRegionManifest decodeRemoved(CompoundTag tag, UUID expectedId) throws IOException {
		try {
			StrictNbtFields.requireExact(tag, Map.ofEntries(
					Map.entry("Schema", Tag.TAG_STRING),
					Map.entry("UuidMost", Tag.TAG_LONG),
					Map.entry("UuidLeast", Tag.TAG_LONG),
					Map.entry("ProfileFingerprint", Tag.TAG_STRING),
					Map.entry("RegistryAggregateFingerprint", Tag.TAG_STRING),
					Map.entry("BaseGeneration", Tag.TAG_LONG),
					Map.entry("TargetGeneration", Tag.TAG_LONG),
					Map.entry("OldManifestChecksum", Tag.TAG_LONG),
					Map.entry("NetworkTombstoneGeneration", Tag.TAG_LONG),
					Map.entry("QuarantineMost", Tag.TAG_LONG),
					Map.entry("QuarantineLeast", Tag.TAG_LONG),
					Map.entry("ZeroInventoryDigest", Tag.TAG_LONG),
					Map.entry("Checksum", Tag.TAG_LONG)), "REMOVED region manifest");
			if (!tag.getStringOr("Schema", "").equals("removed-region")) throw new IOException("Unknown REMOVED manifest schema");
			RemovedRegionManifest value = new RemovedRegionManifest(
					new UUID(tag.getLongOr("UuidMost", 0L), tag.getLongOr("UuidLeast", 0L)),
					tag.getStringOr("ProfileFingerprint", ""), tag.getStringOr("RegistryAggregateFingerprint", ""),
					tag.getLongOr("BaseGeneration", -1L), tag.getLongOr("TargetGeneration", -1L),
					tag.getLongOr("OldManifestChecksum", -1L), tag.getLongOr("NetworkTombstoneGeneration", -1L),
					new UUID(tag.getLongOr("QuarantineMost", 0L), tag.getLongOr("QuarantineLeast", 0L)),
					tag.getLongOr("ZeroInventoryDigest", -1L), tag.getLongOr("Checksum", -1L));
			if (!value.regionId().equals(expectedId) || value.checksum() != removedChecksum(value)) {
				throw new IOException("REMOVED manifest checksum or identity mismatch");
			}
			return value;
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid REMOVED manifest", invalid);
		}
	}

	private static RemovedRegionManifest withRemovedChecksum(RemovedRegionManifest value) {
		return new RemovedRegionManifest(value.regionId(), value.capabilityProfileFingerprint(),
				value.registryAggregateFingerprint(), value.baseGeneration(), value.targetGeneration(),
				value.oldManifestChecksum(), value.networkTombstoneGeneration(), value.quarantineTransactionId(),
				value.zeroInventoryDigest(), removedChecksum(value));
	}

	private static long removedChecksum(RemovedRegionManifest value) {
		CRC32C crc = new CRC32C(); updateLong(crc, value.regionId().getMostSignificantBits());
		updateLong(crc, value.regionId().getLeastSignificantBits());
		crc.update(value.capabilityProfileFingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		crc.update(value.registryAggregateFingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		updateLong(crc, value.baseGeneration()); updateLong(crc, value.targetGeneration());
		updateLong(crc, value.oldManifestChecksum()); updateLong(crc, value.networkTombstoneGeneration());
		updateLong(crc, value.quarantineTransactionId().getMostSignificantBits());
		updateLong(crc, value.quarantineTransactionId().getLeastSignificantBits());
		updateLong(crc, value.zeroInventoryDigest()); return crc.getValue();
	}

	private Path removedQuarantinePath(UUID id, long targetGeneration) {
		return root.getParent().resolve("quarantine").resolve("removed-region")
				.resolve(id.toString()).resolve(Long.toString(targetGeneration));
	}

	private void requireRemovalQuarantineCapacity(Path incoming, long maximumEntries, long maximumBytes) throws IOException {
		if (maximumEntries < 0L || maximumBytes < 0L) throw new IOException("Removed-region quarantine budget is invalid");
		long[] existing = treeStats(root.getParent().resolve("quarantine"));
		long[] addition = treeStats(incoming);
		try {
			if (Math.addExact(existing[0], Math.addExact(addition[0], REMOVAL_QUARANTINE_OVERHEAD_ENTRIES)) > maximumEntries
					|| Math.addExact(existing[1], Math.addExact(addition[1], REMOVAL_QUARANTINE_MANIFEST_BUDGET_BYTES)) > maximumBytes) {
				throw new IOException("Removed-region quarantine is over its admission budget");
			}
		} catch (ArithmeticException overflow) { throw new IOException("Removed-region quarantine accounting overflow", overflow); }
	}

	private static long[] treeStats(Path path) throws IOException {
		if (!Files.exists(path)) return new long[]{0L, 0L};
		long entries = 0L, bytes = 0L;
		try (var stream = Files.walk(path)) {
			for (Path entry : stream.toList()) {
				entries = Math.addExact(entries, 1L);
				if (Files.isRegularFile(entry)) bytes = Math.addExact(bytes, Files.size(entry));
			}
		} catch (ArithmeticException overflow) { throw new IOException("Quarantine tree accounting overflow", overflow); }
		return new long[]{entries, bytes};
	}

	private static CrossDomainRecovery.Decision removalDecision(
			Head head, RemovedRegionManifest removed, CrossDomainRecovery.JournalPhase phase,
			CrossDomainRecovery.ExternalTruth quarantineTruth, boolean baseCandidateDirty
	) {
		return CrossDomainRecovery.decide(new CrossDomainRecovery.Boundary(
				head.generation(), head.manifestChecksum(), removed.baseGeneration(), removed.targetGeneration(),
				true, removed.checksum(), phase, baseCandidateDirty), List.of(quarantineTruth));
	}

	private CrossDomainRecovery.ExternalTruth classifyRemovedQuarantine(
			Path source, Path destination, Path payload, Path manifestPath, RemovedRegionManifest removed
	) throws IOException {
		boolean sourceExists = Files.exists(source), payloadExists = Files.exists(payload);
		if (sourceExists && payloadExists) return CrossDomainRecovery.ExternalTruth.THIRD;
		if (sourceExists) {
			validateRemovedPayload(source, removed);
			if (!Files.exists(destination)) return CrossDomainRecovery.ExternalTruth.BEFORE;
			if (!Files.isDirectory(destination) || !entryNames(destination).isEmpty()) {
				return CrossDomainRecovery.ExternalTruth.THIRD;
			}
			return CrossDomainRecovery.ExternalTruth.BEFORE;
		}
		if (!payloadExists) return CrossDomainRecovery.ExternalTruth.THIRD;
		try {
			validateRemovedPayload(payload, removed);
			Set<String> entries = entryNames(destination);
			Path temporary = destination.resolve("quarantine.nbt.tmp");
			if (!Files.exists(manifestPath)) {
				if (!(entries.equals(Set.of("payload"))
						|| entries.equals(Set.of("payload", "quarantine.nbt.tmp")))) {
					return CrossDomainRecovery.ExternalTruth.THIRD;
				}
				if (Files.exists(temporary)
						&& !decodeRemoved(readNbt(temporary), removed.regionId()).equals(removed)) {
					return CrossDomainRecovery.ExternalTruth.THIRD;
				}
				return CrossDomainRecovery.ExternalTruth.BEFORE;
			}
			if (!entries.equals(Set.of("payload", "quarantine.nbt"))) {
				return CrossDomainRecovery.ExternalTruth.THIRD;
			}
			return decodeRemoved(readNbt(manifestPath), removed.regionId()).equals(removed)
					? CrossDomainRecovery.ExternalTruth.AFTER : CrossDomainRecovery.ExternalTruth.THIRD;
		} catch (IOException | RuntimeException invalid) {
			return CrossDomainRecovery.ExternalTruth.THIRD;
		}
	}

	private void validateRemovedPayload(Path payload, RemovedRegionManifest removed) throws IOException {
		RegionState previous = readSlot(payload);
		previous.validate();
		validateReconstruction(previous);
		RegionManifest manifest = previous.manifest();
		if (!manifest.regionId().equals(removed.regionId())
				|| manifest.checkpointGeneration() != removed.baseGeneration()
				|| manifest.checksum() != removed.oldManifestChecksum()) {
			throw new IOException("Removed-region quarantine payload does not bind the old ACTIVE manifest");
		}
	}

	private static Set<String> entryNames(Path directory) throws IOException {
		if (!Files.isDirectory(directory)) throw new IOException("Expected directory " + directory);
		HashSet<String> names = new HashSet<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) names.add(entry.getFileName().toString());
		}
		return Set.copyOf(names);
	}

	private void validateReconstruction(RegionState state) throws IOException {
		if (state.manifest().layout() == RegionStateLayout.PURE) state.reconstruct();
		else if (state.manifest().layout() == RegionStateLayout.MATERIAL) state.reconstructMaterial();
		else if (state.manifest().layout() == RegionStateLayout.CHEMICAL) {
			if (chemicalRegistry == null) throw new IOException("Chemical region verification requires the authoritative registry");
			state.reconstructChemical(chemicalRegistry);
		}
		else if (state.manifest().layout() == RegionStateLayout.GRANULAR) {
			if (granularRegistry == null) throw new IOException("Granular region verification requires the authoritative registry");
			if (state.manifest().hasChemicalState()) {
				if (chemicalRegistry == null) throw new IOException("Chemical granular-region verification requires the authoritative registry");
				state.reconstructChemical(chemicalRegistry);
			} else state.reconstructMaterial();
			var granular = state.reconstructGranular(granularRegistry);
			if (granular.requiresChemistry()) {
				if (chemicalRegistry == null) throw new IOException("Reactive granular-region verification requires the authoritative chemical registry");
			}
			try { granular.validateAgainst(granularRegistry, chemicalRegistry); }
			catch (IllegalArgumentException invalid) {
				throw new IOException("Granular region inventory/audit validation failed", invalid);
			}
		}
		else if (state.manifest().layout() == RegionStateLayout.THERMAL) {
			if (thermalRegistry == null) {
				throw new IOException("Thermal region verification requires the authoritative thermal registry");
			}
			if (gasRegistry == null) {
				throw new IOException("Thermal region verification requires the authoritative gas registry");
			}
			if (state.manifest().hasChemicalState()) {
				if (chemicalRegistry == null) {
					throw new IOException("Chemical thermal region verification requires the authoritative chemical registry");
				}
				state.reconstructChemical(chemicalRegistry);
			} else state.reconstructMaterial();
			boolean hasGranular = state.manifest().extensions().stream()
					.anyMatch(extension -> GranularRegionStateCodec.MANIFEST_EXTENSION.equals(extension.type()));
			if (hasGranular) {
				if (granularRegistry == null) {
					throw new IOException("Granular thermal region verification requires the authoritative granular registry");
				}
				var granular = state.reconstructGranular(granularRegistry);
				if (granular.requiresChemistry() && chemicalRegistry == null) {
					throw new IOException("Reactive thermal region verification requires the authoritative chemical registry");
				}
				try { granular.validateAgainst(granularRegistry, chemicalRegistry); }
				catch (IllegalArgumentException invalid) {
					throw new IOException("Thermal region granular inventory/audit validation failed", invalid);
				}
			}
			state.reconstructThermal(thermalRegistry, gasRegistry, chemicalRegistry);
			state.reconstructFiniteThermalDevices(thermalRegistry);
		}
		else throw new IOException("Unsupported region-state layout " + state.manifest().layout());
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
		try (DirectoryStream<Path> files = Files.newDirectoryStream(slot)) {
			int actual = 0;
			for (Path file : files) {
				if (!Files.isRegularFile(file)) throw new IOException("Unexpected non-file content " + file);
				if (file.getFileName().toString().equals(MANIFEST)) continue;
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
		tag.putString("Schema", "region-head"); tag.putLong("UuidMost", head.id().getMostSignificantBits()); tag.putLong("UuidLeast", head.id().getLeastSignificantBits());
		tag.putLong("Generation", head.generation()); tag.putString("Kind", head.kind().name());
		tag.putString("Slot", head.slot()); tag.putLong("ManifestChecksum", head.manifestChecksum());
		tag.putLong("Checksum", headChecksum(head.id(), head.generation(), head.kind(), head.slot(), head.manifestChecksum()));
		return tag;
	}

	private static Head decodeHead(CompoundTag tag, UUID expectedId) throws IOException {
		StrictNbtFields.requireExact(tag, Map.of(
				"Schema", Tag.TAG_STRING,
				"UuidMost", Tag.TAG_LONG,
				"UuidLeast", Tag.TAG_LONG,
				"Generation", Tag.TAG_LONG,
				"Kind", Tag.TAG_STRING,
				"Slot", Tag.TAG_STRING,
				"ManifestChecksum", Tag.TAG_LONG,
				"Checksum", Tag.TAG_LONG), "region-state HEAD");
		String schema = tag.getStringOr("Schema", "");
		UUID id = new UUID(tag.getLongOr("UuidMost", 0L), tag.getLongOr("UuidLeast", 0L));
		long generation = tag.getLongOr("Generation", -1L), manifestChecksum = tag.getLongOr("ManifestChecksum", -1L);
		String slot = tag.getStringOr("Slot", "");
		HeadKind kind;
		try { kind = HeadKind.valueOf(tag.getStringOr("Kind", "")); }
		catch (IllegalArgumentException invalid) { throw new IOException("Invalid region-state HEAD kind for " + expectedId); }
		if (!schema.equals("region-head") || !id.equals(expectedId) || generation <= 0L || (!slot.equals(SLOT_A) && !slot.equals(SLOT_B))) {
			throw new IOException("Invalid region-state HEAD for " + expectedId);
		}
		if (tag.getLongOr("Checksum", -1L) != headChecksum(id, generation, kind, slot, manifestChecksum)) {
			throw new IOException("Region-state HEAD checksum mismatch");
		}
		return new Head(id, generation, kind, slot, manifestChecksum);
	}

	private static long headChecksum(UUID id, long generation, HeadKind kind, String slot, long manifestChecksum) {
		CRC32C crc = new CRC32C();
		updateLong(crc, id.getMostSignificantBits()); updateLong(crc, id.getLeastSignificantBits());
		updateLong(crc, generation); crc.update(kind.name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		crc.update(slot.getBytes(java.nio.charset.StandardCharsets.UTF_8)); updateLong(crc, manifestChecksum);
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
	public record PreparedRemoval(
			RemovedRegionManifest manifest, String tombstoneSlot, String activeSlot,
			long maximumQuarantineEntries, long maximumQuarantineBytes
	) { }

	public record Discovery(
			List<RegionManifest> manifests, List<RemovedRegionManifest> removed, Map<Path, String> issues
	) {
		public Discovery {
			manifests = List.copyOf(manifests); removed = List.copyOf(removed); issues = Map.copyOf(issues);
		}
	}
	public record PreviousGeneration(
			UUID id,
			long currentGeneration,
			long currentManifestChecksum,
			String slot,
			RegionState state
	) {
		public PreviousGeneration {
			java.util.Objects.requireNonNull(id, "id");
			java.util.Objects.requireNonNull(slot, "slot");
			java.util.Objects.requireNonNull(state, "state");
		}
	}
	private enum HeadKind { ACTIVE, REMOVED }
	private record Head(UUID id, long generation, HeadKind kind, String slot, long manifestChecksum) { }
}
