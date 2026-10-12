package mmm.simulation.persistence;

import mmm.model.region.RegionManifest;
import mmm.recovery.CrossDomainRecovery;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Admission, classification and exact convergence of removed ACTIVE payloads into quarantine. */
final class RemovedQuarantineStore {
	private static final long MAX_NBT_BYTES = 64L * 1024L * 1024L;
	private static final long OVERHEAD_ENTRIES = 6L;
	private static final long MANIFEST_BUDGET_BYTES = 4_096L;

	private final Path regionRoot;
	private final RegionGenerationStore.AtomicMover atomicMover;
	private final ActiveGenerationStore activeStore;
	private final RemovedGenerationStore removedStore;

	RemovedQuarantineStore(
			Path regionRoot,
			RegionGenerationStore.AtomicMover atomicMover,
			ActiveGenerationStore activeStore,
			RemovedGenerationStore removedStore
	) {
		this.regionRoot = regionRoot;
		this.atomicMover = atomicMover;
		this.activeStore = activeStore;
		this.removedStore = removedStore;
	}

	Path path(java.util.UUID id, long targetGeneration) {
		return regionRoot.getParent().resolve("quarantine").resolve("removed-region")
				.resolve(id.toString()).resolve(Long.toString(targetGeneration));
	}

	void requireCapacity(Path incoming, long maximumEntries, long maximumBytes) throws IOException {
		if (maximumEntries < 0L || maximumBytes < 0L) {
			throw new IOException("Removed-region quarantine budget is invalid");
		}
		long[] existing = treeStats(regionRoot.getParent().resolve("quarantine"));
		long[] addition = treeStats(incoming);
		try {
			if (Math.addExact(existing[0], Math.addExact(addition[0], OVERHEAD_ENTRIES)) > maximumEntries
					|| Math.addExact(existing[1], Math.addExact(addition[1], MANIFEST_BUDGET_BYTES)) > maximumBytes) {
				throw new IOException("Removed-region quarantine is over its admission budget");
			}
		} catch (ArithmeticException overflow) {
			throw new IOException("Removed-region quarantine accounting overflow", overflow);
		}
	}

	Path converge(
			Path region,
			RegionHeadStore.Head head,
			RemovedRegionManifest removed
	) throws IOException {
		String activeSlot = head.slot().equals(RegionHeadStore.SLOT_A)
				? RegionHeadStore.SLOT_B : RegionHeadStore.SLOT_A;
		Path source = region.resolve(activeSlot);
		Path destination = path(removed.regionId(), removed.targetGeneration());
		Path payload = destination.resolve("payload");
		Path manifestPath = destination.resolve("quarantine.nbt");
		CrossDomainRecovery.ExternalTruth truth = classify(source, destination, payload, manifestPath, removed);
		CrossDomainRecovery.Decision decision = decision(head, removed,
				CrossDomainRecovery.JournalPhase.REGION_COMMITTED, truth, false);
		if (decision.outcome() == CrossDomainRecovery.Outcome.LOCKED) {
			throw new IOException("REMOVED quarantine recovery is LOCKED: " + decision.reason());
		}
		if (decision.outcome() != CrossDomainRecovery.Outcome.CONVERGE_AFTER) {
			throw new IOException("REMOVED quarantine recovery produced " + decision.outcome());
		}
		if (truth == CrossDomainRecovery.ExternalTruth.AFTER) return destination;
		if (!Files.exists(destination)) {
			DurableFileOperations.createDirectories(destination);
			DurableFileOperations.forceDirectory(destination.getParent());
		}
		if (Files.exists(source)) {
			atomicMover.move(source, payload, false);
			DurableFileOperations.forceDirectory(destination);
		}
		validatePayload(payload, removed);
		if (!Files.exists(manifestPath)) {
			Path temporary = destination.resolve("quarantine.nbt.tmp");
			if (Files.exists(temporary)) {
				RemovedRegionManifest staged = removedStore.decode(readNbt(temporary), removed.regionId());
				if (!staged.equals(removed)) {
					throw new IOException("Staged quarantine manifest differs from REMOVED HEAD");
				}
			} else {
				NbtIo.writeCompressed(removedStore.encode(removed), temporary);
				DurableFileOperations.forceFile(temporary);
			}
			atomicMover.move(temporary, manifestPath, false);
			DurableFileOperations.forceDirectory(destination);
		}
		if (classify(source, destination, payload, manifestPath, removed)
				!= CrossDomainRecovery.ExternalTruth.AFTER) {
			throw new IOException("Removed-region quarantine did not reach exact AFTER truth");
		}
		return destination;
	}

	CrossDomainRecovery.ExternalTruth classify(
			Path source,
			Path destination,
			Path payload,
			Path manifestPath,
			RemovedRegionManifest removed
	) throws IOException {
		boolean sourceExists = Files.exists(source);
		boolean payloadExists = Files.exists(payload);
		if (sourceExists && payloadExists) return CrossDomainRecovery.ExternalTruth.THIRD;
		if (sourceExists) {
			validatePayload(source, removed);
			if (!Files.exists(destination)) return CrossDomainRecovery.ExternalTruth.BEFORE;
			if (!Files.isDirectory(destination) || !entryNames(destination).isEmpty()) {
				return CrossDomainRecovery.ExternalTruth.THIRD;
			}
			return CrossDomainRecovery.ExternalTruth.BEFORE;
		}
		if (!payloadExists) return CrossDomainRecovery.ExternalTruth.THIRD;
		try {
			validatePayload(payload, removed);
			Set<String> entries = entryNames(destination);
			Path temporary = destination.resolve("quarantine.nbt.tmp");
			if (!Files.exists(manifestPath)) {
				if (!(entries.equals(Set.of("payload"))
						|| entries.equals(Set.of("payload", "quarantine.nbt.tmp")))) {
					return CrossDomainRecovery.ExternalTruth.THIRD;
				}
				if (Files.exists(temporary)
						&& !removedStore.decode(readNbt(temporary), removed.regionId()).equals(removed)) {
					return CrossDomainRecovery.ExternalTruth.THIRD;
				}
				return CrossDomainRecovery.ExternalTruth.BEFORE;
			}
			if (!entries.equals(Set.of("payload", "quarantine.nbt"))) {
				return CrossDomainRecovery.ExternalTruth.THIRD;
			}
			return removedStore.decode(readNbt(manifestPath), removed.regionId()).equals(removed)
					? CrossDomainRecovery.ExternalTruth.AFTER : CrossDomainRecovery.ExternalTruth.THIRD;
		} catch (IOException | RuntimeException invalid) {
			return CrossDomainRecovery.ExternalTruth.THIRD;
		}
	}

	static CrossDomainRecovery.Decision decision(
			RegionHeadStore.Head head,
			RemovedRegionManifest removed,
			CrossDomainRecovery.JournalPhase phase,
			CrossDomainRecovery.ExternalTruth quarantineTruth,
			boolean baseCandidateDirty
	) {
		return CrossDomainRecovery.decide(new CrossDomainRecovery.Boundary(
				head.generation(), head.manifestChecksum(), removed.baseGeneration(), removed.targetGeneration(),
				true, removed.checksum(), phase, baseCandidateDirty), List.of(quarantineTruth));
	}

	private void validatePayload(Path payload, RemovedRegionManifest removed) throws IOException {
		RegionState previous = activeStore.readSlot(payload);
		previous.validate();
		activeStore.validateReconstruction(previous);
		RegionManifest manifest = previous.manifest();
		if (!manifest.regionId().equals(removed.regionId())
				|| manifest.checkpointGeneration() != removed.baseGeneration()
				|| manifest.checksum() != removed.oldManifestChecksum()) {
			throw new IOException("Removed-region quarantine payload does not bind the old ACTIVE manifest");
		}
	}

	private static long[] treeStats(Path path) throws IOException {
		if (!Files.exists(path)) return new long[]{0L, 0L};
		long entries = 0L;
		long bytes = 0L;
		try (var stream = Files.walk(path)) {
			for (Path entry : stream.toList()) {
				entries = Math.addExact(entries, 1L);
				if (Files.isRegularFile(entry)) bytes = Math.addExact(bytes, Files.size(entry));
			}
		} catch (ArithmeticException overflow) {
			throw new IOException("Quarantine tree accounting overflow", overflow);
		}
		return new long[]{entries, bytes};
	}

	private static Set<String> entryNames(Path directory) throws IOException {
		if (!Files.isDirectory(directory)) throw new IOException("Expected directory " + directory);
		HashSet<String> names = new HashSet<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
			for (Path entry : entries) names.add(entry.getFileName().toString());
		}
		return Set.copyOf(names);
	}

	private static net.minecraft.nbt.CompoundTag readNbt(Path path) throws IOException {
		if (!Files.isRegularFile(path)) throw new IOException("Missing region-state file " + path);
		return NbtIo.readCompressed(path, NbtAccounter.create(MAX_NBT_BYTES));
	}

}
