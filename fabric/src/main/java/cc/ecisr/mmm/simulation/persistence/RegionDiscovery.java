package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.model.region.RegionManifest;
import cc.ecisr.mmm.recovery.CrossDomainRecovery;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static cc.ecisr.mmm.simulation.persistence.RegionHeadStore.HEAD;
import static cc.ecisr.mmm.simulation.persistence.RegionHeadStore.SLOT_A;
import static cc.ecisr.mmm.simulation.persistence.RegionHeadStore.SLOT_B;
import static cc.ecisr.mmm.simulation.persistence.RemovedGenerationStore.REMOVED;

/** Startup classification and recovery of only writer-owned, exactly verifiable staging. */
final class RegionDiscovery {
	private final Path root;
	private final RegionHeadStore headStore;
	private final ActiveGenerationStore activeStore;
	private final RemovedGenerationStore removedStore;
	private final RemovedQuarantineStore quarantineStore;

	RegionDiscovery(
			Path root,
			RegionHeadStore headStore,
			ActiveGenerationStore activeStore,
			RemovedGenerationStore removedStore,
			RemovedQuarantineStore quarantineStore
	) {
		this.root = root;
		this.headStore = headStore;
		this.activeStore = activeStore;
		this.removedStore = removedStore;
		this.quarantineStore = quarantineStore;
	}

	void recoverKnownFirstPublicationStaging() throws IOException {
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

	RegionGenerationStore.Discovery discover(boolean reconstruct) throws IOException {
		if (!Files.exists(root)) return new RegionGenerationStore.Discovery(List.of(), List.of(), Map.of());
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
					RegionHeadStore.Head head = headStore.read(entry, id);
					recoverUncommittedRemovalStaging(entry, head);
					if (head.kind() == RegionHeadStore.HeadKind.REMOVED) {
						removed.add(removedStore.readGeneration(entry, head));
					} else {
						RegionState committed = activeStore.readGeneration(entry, head);
						if (reconstruct) activeStore.validateReconstruction(committed);
						manifests.add(committed.manifest());
					}
				} catch (IllegalArgumentException | IOException exception) {
					issues.put(entry, exception.getMessage());
				}
			}
		}
		manifests.sort(Comparator.comparing(manifest -> manifest.regionId().toString()));
		removed.sort(Comparator.comparing(manifest -> manifest.regionId().toString()));
		return new RegionGenerationStore.Discovery(manifests, removed, issues);
	}

	void clearInactiveForPublication(Path region, RegionHeadStore.Head current) throws IOException {
		recoverUncommittedRemovalStaging(region, current);
		String inactiveName = current.slot().equals(SLOT_A) ? SLOT_B : SLOT_A;
		Path inactive = region.resolve(inactiveName);
		Path temporary = region.resolve(inactiveName + ".tmp");
		Path headTemporary = region.resolve(HEAD + ".tmp");

		if (Files.exists(inactive)) validateActiveSlot(inactive, current.id(), null, null);
		if (Files.exists(temporary)) validateActiveSlot(temporary, current.id(), null, null);
		if (Files.exists(headTemporary)) {
			RegionHeadStore.Head candidate = headStore.readTemporary(headTemporary, current.id());
			if (candidate.kind() != RegionHeadStore.HeadKind.ACTIVE
					|| !candidate.slot().equals(inactiveName)) {
				throw new IOException("Uncommitted ACTIVE HEAD staging does not bind the inactive slot");
			}
			RegionState staged = activeStore.readGeneration(region, candidate);
			activeStore.validateReconstruction(staged);
		}
		deleteKnown(headTemporary);
		deleteKnown(temporary);
		deleteKnown(inactive);
	}

	void discardMatchingHeadTemporary(Path region, RegionHeadStore.Head expected) throws IOException {
		Path headTemporary = region.resolve(HEAD + ".tmp");
		if (!Files.exists(headTemporary)) return;
		RegionHeadStore.Head actual = headStore.readTemporary(headTemporary, expected.id());
		if (!actual.equals(expected)) {
			throw new IOException("Existing HEAD staging does not match the requested recovery publication");
		}
		deleteKnown(headTemporary);
	}

	void discardPrepared(Path region, RegionGenerationStore.PreparedGeneration prepared) throws IOException {
		Path slot = region.resolve(prepared.slot());
		Path headTemporary = region.resolve(HEAD + ".tmp");
		if (Files.exists(slot)) {
			validateActiveSlot(slot, prepared.id(), prepared.generation(), prepared.manifestChecksum());
		}
		if (Files.exists(headTemporary)) {
			RegionHeadStore.Head candidate = headStore.readTemporary(headTemporary, prepared.id());
			if (candidate.kind() != RegionHeadStore.HeadKind.ACTIVE
					|| candidate.generation() != prepared.generation()
					|| !candidate.slot().equals(prepared.slot())
					|| candidate.manifestChecksum() != prepared.manifestChecksum()) {
				throw new IOException("Prepared ACTIVE HEAD staging changed before abort");
			}
		}
		deleteKnown(headTemporary);
		deleteKnown(slot);
	}

	void discardPreparedRemoval(
			Path region,
			RegionGenerationStore.PreparedRemoval prepared
	) throws IOException {
		Path slot = region.resolve(prepared.tombstoneSlot());
		Path temporary = region.resolve(prepared.tombstoneSlot() + ".tmp");
		Path headTemporary = region.resolve(HEAD + ".tmp");
		if (Files.exists(slot)
				&& !removedStore.readSlot(slot, prepared.manifest().regionId()).equals(prepared.manifest())) {
			throw new IOException("Prepared REMOVED tombstone changed before abort");
		}
		if (Files.exists(temporary)
				&& !removedStore.readSlot(temporary, prepared.manifest().regionId()).equals(prepared.manifest())) {
			throw new IOException("Temporary REMOVED tombstone changed before abort");
		}
		if (Files.exists(headTemporary)) {
			RegionHeadStore.Head candidate = headStore.readTemporary(headTemporary, prepared.manifest().regionId());
			if (candidate.kind() != RegionHeadStore.HeadKind.REMOVED
					|| candidate.generation() != prepared.manifest().targetGeneration()
					|| !candidate.slot().equals(prepared.tombstoneSlot())
					|| candidate.manifestChecksum() != prepared.manifest().checksum()) {
				throw new IOException("Prepared REMOVED HEAD staging changed before abort");
			}
		}
		deleteKnown(headTemporary);
		deleteKnown(temporary);
		deleteKnown(slot);
	}

	void discardRollback(Path region, UUID id, long expectedBaseGeneration) throws IOException {
		String inactiveName;
		if (expectedBaseGeneration == 0L) {
			discardKnownStagedLeftovers(region);
			return;
		}
		RegionHeadStore.Head current = headStore.read(region, id);
		if (current.generation() != expectedBaseGeneration) {
			throw new IOException("Region HEAD moved while discarding rolled-back staging for " + id);
		}
		inactiveName = current.slot().equals(SLOT_A) ? SLOT_B : SLOT_A;
		Path inactive = region.resolve(inactiveName);
		Path temporary = region.resolve(inactiveName + ".tmp");
		Path headTemporary = region.resolve(HEAD + ".tmp");
		Long target = GenerationSequence.next(expectedBaseGeneration, "Rolled-back region staging");
		if (Files.exists(inactive)) validateActiveSlot(inactive, id, target, null);
		if (Files.exists(temporary)) validateActiveSlot(temporary, id, target, null);
		if (Files.exists(headTemporary)) {
			RegionHeadStore.Head candidate = headStore.readTemporary(headTemporary, id);
			if (candidate.kind() != RegionHeadStore.HeadKind.ACTIVE
					|| candidate.generation() != target || !candidate.slot().equals(inactiveName)) {
				throw new IOException("Rolled-back ACTIVE HEAD staging does not bind its target slot");
			}
		}
		deleteKnown(headTemporary);
		deleteKnown(temporary);
		deleteKnown(inactive);
	}

	void discardKnownStagedLeftovers(Path region) throws IOException {
		Set<String> names;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(region)) {
			java.util.HashSet<String> found = new java.util.HashSet<>();
			for (Path entry : entries) found.add(entry.getFileName().toString());
			names = Set.copyOf(found);
		}
		if (!(names.isEmpty() || names.equals(Set.of(SLOT_A + ".tmp"))
				|| names.equals(Set.of(SLOT_A)) || names.equals(Set.of(SLOT_A, HEAD + ".tmp")))) {
			return;
		}
		UUID expectedId;
		try {
			expectedId = UUID.fromString(region.getFileName().toString());
		} catch (IllegalArgumentException invalid) {
			return;
		}
		try {
			Path slot = names.contains(SLOT_A) ? region.resolve(SLOT_A) : region.resolve(SLOT_A + ".tmp");
			RegionState staged = names.isEmpty() ? null : validateActiveSlot(slot, expectedId, 1L, null);
			if (names.contains(HEAD + ".tmp")) {
				RegionHeadStore.Head candidate = headStore.readTemporary(region.resolve(HEAD + ".tmp"), expectedId);
				if (staged == null || candidate.kind() != RegionHeadStore.HeadKind.ACTIVE
						|| candidate.generation() != 1L || !candidate.slot().equals(SLOT_A)
						|| candidate.manifestChecksum() != staged.manifest().checksum()) return;
			}
		} catch (IllegalArgumentException | IOException invalid) {
			return;
		}
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(region)) {
			for (Path entry : entries) deleteKnown(entry);
		}
	}

	boolean isEmptyDirectory(Path region) throws IOException {
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(region)) {
			return !entries.iterator().hasNext();
		}
	}

	private void recoverUncommittedRemovalStaging(
			Path region,
			RegionHeadStore.Head active
	) throws IOException {
		if (active.kind() != RegionHeadStore.HeadKind.ACTIVE) return;
		String inactiveName = active.slot().equals(SLOT_A) ? SLOT_B : SLOT_A;
		Path inactive = region.resolve(inactiveName);
		Path temporary = region.resolve(inactiveName + ".tmp");
		boolean inactiveRemoval = Files.exists(inactive.resolve(REMOVED));
		boolean temporaryRemoval = Files.exists(temporary.resolve(REMOVED));
		if (!inactiveRemoval && !temporaryRemoval) return;
		if (inactiveRemoval && temporaryRemoval) {
			throw new IOException("REMOVED staging exists in both inactive and temporary slots");
		}
		Path stagedPath = inactiveRemoval ? inactive : temporary;
		RemovedRegionManifest staged = removedStore.readSlot(stagedPath, active.id());
		if (staged.baseGeneration() != active.generation()
				|| staged.oldManifestChecksum() != active.manifestChecksum()) {
			throw new IOException("Uncommitted REMOVED staging does not bind the ACTIVE HEAD");
		}
		activeStore.readGeneration(region, active);
		Path quarantine = quarantineStore.path(active.id(), staged.targetGeneration());
		CrossDomainRecovery.ExternalTruth truth = Files.exists(quarantine)
				? CrossDomainRecovery.ExternalTruth.THIRD : CrossDomainRecovery.ExternalTruth.BEFORE;
		CrossDomainRecovery.Decision decision = RemovedQuarantineStore.decision(active, staged,
				CrossDomainRecovery.JournalPhase.PREPARED, truth, false);
		if (decision.outcome() != CrossDomainRecovery.Outcome.DISCARD_UNCOMMITTED) {
			throw new IOException("Uncommitted REMOVED recovery is LOCKED: " + decision.reason());
		}
		Path headTemporary = region.resolve(HEAD + ".tmp");
		if (Files.exists(headTemporary)) {
			RegionHeadStore.Head candidate = headStore.readTemporary(headTemporary, active.id());
			if (candidate.kind() != RegionHeadStore.HeadKind.REMOVED
					|| candidate.generation() != staged.targetGeneration()
					|| !candidate.slot().equals(inactiveName)
					|| candidate.manifestChecksum() != staged.checksum()) {
				throw new IOException("Uncommitted REMOVED HEAD staging does not bind its tombstone");
			}
		}
		deleteKnown(stagedPath);
		deleteKnown(headTemporary);
		DurableFileOperations.forceDirectory(region);
	}

	private void deleteKnown(Path path) throws IOException {
		DurableFileOperations.deleteTreeWithin(root, path);
	}

	private RegionState validateActiveSlot(
			Path slot,
			UUID expectedId,
			Long expectedGeneration,
			Long expectedChecksum
	) throws IOException {
		RegionState state = activeStore.readSlot(slot);
		state.validate();
		activeStore.validateReconstruction(state);
		RegionManifest manifest = state.manifest();
		if (!manifest.regionId().equals(expectedId)
				|| expectedGeneration != null && manifest.checkpointGeneration() != expectedGeneration
				|| expectedChecksum != null && manifest.checksum() != expectedChecksum) {
			throw new IOException("Writer-owned ACTIVE staging identity changed");
		}
		return state;
	}
}
