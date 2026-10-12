package mmm.simulation.persistence;

import mmm.simulation.persistence.RegionHeadStore.Head;
import mmm.simulation.persistence.RegionHeadStore.HeadKind;

import static mmm.simulation.persistence.RegionHeadStore.HEAD;
import static mmm.simulation.persistence.RegionHeadStore.SLOT_A;
import static mmm.simulation.persistence.RegionHeadStore.SLOT_B;

import mmm.model.region.RegionManifest;

import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.air.GasRegistry;
import mmm.granular.GranularRegistry;
import mmm.thermal.ThermalRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Two-slot storage whose only commit point is an atomically replaced HEAD. */
public final class RegionGenerationStore {
	private final Path root;
	private final AtomicMover atomicMover;
	private final RegionHeadStore headStore;
	private final ActiveGenerationStore activeStore;
	private final RemovedGenerationStore removedStore;
	private final RemovedQuarantineStore quarantineStore;
	private final RegionDiscovery discovery;

	public RegionGenerationStore(Path worldRoot) {
		this(worldRoot, RegionGenerationStore::atomicMove, null, null, null, null);
	}

	public RegionGenerationStore(Path worldRoot, ChemicalRegistry chemicalRegistry) {
		this(worldRoot, RegionGenerationStore::atomicMove, chemicalRegistry, null, null, null);
	}

	public RegionGenerationStore(Path worldRoot, ChemicalRegistry chemicalRegistry, GranularRegistry granularRegistry) {
		this(worldRoot, RegionGenerationStore::atomicMove, chemicalRegistry, granularRegistry, null, null);
	}

	public RegionGenerationStore(
			Path worldRoot, ChemicalRegistry chemicalRegistry, GranularRegistry granularRegistry,
			ThermalRegistry thermalRegistry
	) {
		this(worldRoot, RegionGenerationStore::atomicMove, chemicalRegistry, granularRegistry, thermalRegistry, null);
	}

	public RegionGenerationStore(
			Path worldRoot, ChemicalRegistry chemicalRegistry, GranularRegistry granularRegistry,
			ThermalRegistry thermalRegistry, GasRegistry gasRegistry
	) {
		this(worldRoot, RegionGenerationStore::atomicMove, chemicalRegistry, granularRegistry, thermalRegistry, gasRegistry);
	}

	RegionGenerationStore(Path worldRoot, AtomicMover atomicMover) {
		this(worldRoot, atomicMover, null, null, null, null);
	}

	RegionGenerationStore(Path worldRoot, AtomicMover atomicMover, ChemicalRegistry chemicalRegistry) {
		this(worldRoot, atomicMover, chemicalRegistry, null, null, null);
	}

	RegionGenerationStore(
			Path worldRoot, AtomicMover atomicMover, ChemicalRegistry chemicalRegistry, GranularRegistry granularRegistry
	) {
		this(worldRoot, atomicMover, chemicalRegistry, granularRegistry, null, null);
	}

	RegionGenerationStore(
			Path worldRoot, AtomicMover atomicMover, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, ThermalRegistry thermalRegistry
	) {
		this(worldRoot, atomicMover, chemicalRegistry, granularRegistry, thermalRegistry, null);
	}

	RegionGenerationStore(
			Path worldRoot, AtomicMover atomicMover, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, ThermalRegistry thermalRegistry, GasRegistry gasRegistry
	) {
		this.root = worldRoot.toAbsolutePath().normalize().resolve("mmm").resolve("region-state");
		this.atomicMover = atomicMover;
		this.headStore = new RegionHeadStore(atomicMover);
		this.activeStore = new ActiveGenerationStore(chemicalRegistry, granularRegistry, thermalRegistry, gasRegistry);
		this.removedStore = new RemovedGenerationStore();
		this.quarantineStore = new RemovedQuarantineStore(root, atomicMover, activeStore, removedStore);
		this.discovery = new RegionDiscovery(root, headStore, activeStore, removedStore, quarantineStore);
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
				discovery.discardKnownStagedLeftovers(region);
				if (!discovery.isEmptyDirectory(region)) {
					throw new IOException("Uncommitted or unknown region-state data locks " + region);
				}
			} else {
				current = headStore.read(region, id);
				RegionState committed = activeStore.readGeneration(region, current);
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
		if (current != null) discovery.clearInactiveForPublication(region, current);
		activeStore.writeTemporary(state, temporary, injector);
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
		Head next = RegionHeadStore.active(id, prepared.generation(), prepared.slot(), prepared.manifestChecksum());
		Path headTemporary = headStore.writeTemporary(region, next);
		injector.check(FaultPoint.BEFORE_HEAD_PUBLISH, headTemporary);
		headStore.publishTemporary(region, headTemporary);
		activeStore.readGeneration(region, headStore.read(region, id));
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
		Head current = headStore.read(region, id);
		if (current.kind() != HeadKind.ACTIVE || current.generation() != expectedBaseGeneration
				|| current.manifestChecksum() != expectedManifestChecksum) {
			throw new IOException("ACTIVE region HEAD moved before removal staging");
		}
		activeStore.readGeneration(region, current);
		long target = GenerationSequence.next(expectedBaseGeneration, "Removed region tombstone");
		RemovedRegionManifest removed = removedStore.create(id, capabilityProfileFingerprint,
				registryAggregateFingerprint, expectedBaseGeneration, target, expectedManifestChecksum,
				networkTombstoneGeneration, quarantineTransactionId);
		String inactive = current.slot().equals(SLOT_B) ? SLOT_A : SLOT_B;
		Path inactivePath = region.resolve(inactive);
		Path temporary = region.resolve(inactive + ".tmp");
		Path quarantineDestination = quarantineStore.path(id, target);
		if (Files.exists(quarantineDestination)) {
			throw new IOException("Removed-region quarantine target already exists");
		}
		quarantineStore.requireCapacity(region.resolve(current.slot()), maximumQuarantineEntries, maximumQuarantineBytes);
		discovery.clearInactiveForPublication(region, current);
		Files.createDirectory(temporary);
		removedStore.writeSlot(temporary, removed);
		if (!removedStore.readSlot(temporary, id).equals(removed)) {
			throw new IOException("Removed tombstone readback changed");
		}
		atomicMover.move(temporary, inactivePath, false); DurableFileOperations.forceDirectory(region);
		return new PreparedRemoval(removed, inactive, current.slot());
	}

	/** Publishes the REMOVED HEAD as the sole terminal commit point. */
	public RemovedRegionManifest commitRemoval(PreparedRemoval prepared) throws IOException {
		RemovedRegionManifest removed = prepared.manifest();
		Path region = regionPath(removed.regionId());
		Head current = headStore.read(region, removed.regionId());
		if (current.kind() == HeadKind.REMOVED) return removedStore.readGeneration(region, current);
		if (current.kind() != HeadKind.ACTIVE || current.generation() != removed.baseGeneration()
				|| current.manifestChecksum() != removed.oldManifestChecksum()
				|| !current.slot().equals(prepared.activeSlot())) {
			throw new IOException("ACTIVE region HEAD moved before REMOVED commit");
		}
		RemovedRegionManifest verified = removedStore.readSlot(region.resolve(prepared.tombstoneSlot()), removed.regionId());
		if (!verified.equals(removed)) throw new IOException("Staged REMOVED tombstone changed before commit");
		Head next = RegionHeadStore.removed(removed.regionId(), removed.targetGeneration(),
				prepared.tombstoneSlot(), removed.checksum());
		Path temporary = headStore.writeTemporary(region, next);
		headStore.publishTemporary(region, temporary);
		return removedStore.readGeneration(region, headStore.read(region, removed.regionId()));
	}

	public void abortRemoval(PreparedRemoval prepared) throws IOException {
		RemovedRegionManifest removed = prepared.manifest(); Path region = regionPath(removed.regionId());
		Head current = headStore.read(region, removed.regionId());
		if (current.kind() != HeadKind.ACTIVE || current.generation() != removed.baseGeneration()
				|| current.manifestChecksum() != removed.oldManifestChecksum()) {
			throw new IOException("Region HEAD moved while aborting REMOVED staging");
		}
		discovery.discardPreparedRemoval(region, prepared);
	}

	public void requireRemovalQuarantineCapacity(UUID id, long maximumEntries, long maximumBytes) throws IOException {
		Path region = regionPath(id); Head head = headStore.read(region, id);
		if (head.kind() != HeadKind.ACTIVE) throw new IOException("Region is not ACTIVE");
		long target = GenerationSequence.next(head.generation(), "Removed region tombstone");
		if (Files.exists(quarantineStore.path(id, target))) {
			throw new IOException("Removed-region quarantine target already exists");
		}
		quarantineStore.requireCapacity(region.resolve(head.slot()), maximumEntries, maximumBytes);
	}

	/** Moves the no-longer-referenced ACTIVE slot into durable quarantine and verifies its manifest. */
	public Path convergeRemovalQuarantine(UUID id) throws IOException {
		Path region = regionPath(id);
		Head head = headStore.read(region, id);
		RemovedRegionManifest removed = removedStore.readGeneration(region, head);
		return quarantineStore.converge(region, head, removed);
	}

	public RemovedRegionManifest readRemoved(UUID id) throws IOException {
		Path region = regionPath(id);
		return removedStore.readGeneration(region, headStore.read(region, id));
	}

	/** Discards a staged generation; the base committed generation stays authoritative. */
	public void abort(PreparedGeneration prepared) throws IOException {
		if (prepared.alreadyCommitted()) return;
		Path region = regionPath(prepared.id());
		discovery.discardPrepared(region, prepared);
		if (prepared.baseGeneration() == null && !Files.exists(region.resolve(HEAD))) {
			if (!discovery.isEmptyDirectory(region)) {
				throw new IOException("Unknown content prevents prepared generation cleanup for " + prepared.id());
			}
			deleteKnown(region);
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
			discovery.discardRollback(region, id, expectedBaseGeneration);
			if (!discovery.isEmptyDirectory(region)) {
				throw new IOException("Unknown content prevents staged rollback cleanup for " + id);
			}
			deleteKnown(region);
			return;
		}
		discovery.discardRollback(region, id, expectedBaseGeneration);
	}

	private void validateBaseUnchanged(PreparedGeneration prepared, Path region) throws IOException {
		if (prepared.baseGeneration() == null) {
			if (Files.exists(region.resolve(HEAD))) throw new IOException("Base HEAD appeared for " + prepared.id());
			return;
		}
		Head current = headStore.read(region, prepared.id());
		if (current.generation() != prepared.baseGeneration() || current.manifestChecksum() != prepared.baseManifestChecksum()) {
			throw new IOException("Base HEAD moved before commit for " + prepared.id());
		}
	}

	public RegionManifest readManifest(UUID id) throws IOException {
		Path region = regionPath(id);
		return activeStore.readManifest(region, headStore.read(region, id));
	}

	public RegionState read(UUID id) throws IOException {
		Path region = regionPath(id);
		return activeStore.readGeneration(region, headStore.read(region, id));
	}

	/**
	 * Reads the inactive slot only when it is the immediately preceding complete generation.
	 * This is an operator-recovery probe; ordinary discovery and restore continue to follow HEAD
	 * exclusively and never fall back automatically.
	 */
	public PreviousGeneration readPrevious(UUID id, long expectedCurrentGeneration) throws IOException {
		Path region = regionPath(id);
		Head current = headStore.read(region, id);
		if (current.generation() != expectedCurrentGeneration) {
			throw new IOException("Region HEAD moved; expected generation " + expectedCurrentGeneration
					+ " but found " + current.generation());
		}
		String previousSlot = current.slot().equals(SLOT_A) ? SLOT_B : SLOT_A;
		RegionState previous = activeStore.readSlot(region.resolve(previousSlot));
		previous.validate();
		activeStore.validateReconstruction(previous);
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
		Head current = headStore.read(region, previous.id());
		if (current.generation() != previous.currentGeneration()
				|| current.manifestChecksum() != previous.currentManifestChecksum()) {
			throw new IOException("Region HEAD moved after previous-generation validation");
		}
		String expectedPreviousSlot = current.slot().equals(SLOT_A) ? SLOT_B : SLOT_A;
		if (!expectedPreviousSlot.equals(previous.slot())) {
			throw new IOException("Previous-generation slot changed after validation");
		}
		RegionState verified = activeStore.readSlot(region.resolve(previous.slot()));
		verified.validate();
		activeStore.validateReconstruction(verified);
		if (verified.manifest().checkpointGeneration() != current.generation() - 1L
				|| verified.manifest().checksum() != previous.state().manifest().checksum()
				|| !verified.manifest().regionId().equals(previous.id())) {
			throw new IOException("Previous generation changed after validation");
		}
		Head restored = RegionHeadStore.active(previous.id(), verified.manifest().checkpointGeneration(),
				previous.slot(), verified.manifest().checksum());
		discovery.discardMatchingHeadTemporary(region, restored);
		Path headTemporary = headStore.writeTemporary(region, restored);
		headStore.publishTemporary(region, headTemporary);
		activeStore.readGeneration(region, headStore.read(region, previous.id()));
		return restored.generation();
	}

	/**
	 * HEAD generation of a committed region, or 0 when no generation was ever committed. A present
	 * but corrupt HEAD still throws; whether staged leftovers may be retried is decided by prepare.
	 */
	public long committedGeneration(UUID id) throws IOException {
		Path region = regionPath(id);
		if (!Files.exists(region) || !Files.exists(region.resolve(HEAD))) return 0L;
		return headStore.read(region, id).generation();
	}

	public long committedManifestChecksum(UUID id) throws IOException {
		Path region = regionPath(id);
		if (!Files.exists(region.resolve(HEAD))) return -1L;
		return headStore.read(region, id).manifestChecksum();
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
		discovery.recoverKnownFirstPublicationStaging();
	}

	/** Fully validates committed generations during discovery. Locked paths are retained as issues. */
	public Discovery discover() throws IOException {
		return discovery.discover(true);
	}

	/**
	 * Validates HEAD, every committed file, CRC and format-local ledgers without resolving stable
	 * registry ids. This is used only while deciding whether a missing world registry may be first
	 * published; ordinary admission must use {@link #discover()}.
	 */
	public Discovery discoverCommittedStructure() throws IOException {
		return discovery.discover(false);
	}

	private Path regionPath(UUID id) { return root.resolve(id.toString()); }

	private void deleteKnown(Path path) throws IOException {
		DurableFileOperations.deleteTreeWithin(root, path);
	}

	private static void atomicMove(Path source, Path target, boolean replace) throws IOException {
		if (replace) DurableFileOperations.atomicMoveReplacing(source, target);
		else DurableFileOperations.atomicMove(source, target);
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
	public record PreparedRemoval(RemovedRegionManifest manifest, String tombstoneSlot, String activeSlot) { }

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
}
