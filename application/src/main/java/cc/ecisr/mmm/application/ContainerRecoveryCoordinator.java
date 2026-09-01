package cc.ecisr.mmm.application;

import cc.ecisr.mmm.application.port.ContainerJournalRepository;
import cc.ecisr.mmm.recovery.CrossDomainRecovery;
import cc.ecisr.mmm.transfer.ContainerTransferActor;
import cc.ecisr.mmm.transfer.ContainerTransferJournal;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/** Owns the boot-indexed container WAL set and event-driven external-actor recovery state. */
public final class ContainerRecoveryCoordinator implements ContainerJournalRepository {
	private final JournalStore store;
	private final RecoveryPort port;
	private final Map<UUID, ContainerTransferJournal> indexed = new HashMap<>();
	private final Set<UUID> audited = new HashSet<>();
	private final Set<UUID> awaitingActors = new HashSet<>();
	private final Map<UUID, String> reportedLocks = new HashMap<>();
	private final Set<String> storeIssues;
	private Set<String> reportedStoreIssues = Set.of();

	public ContainerRecoveryCoordinator(
			JournalStore store,
			RecoveryPort port,
			Map<UUID, ContainerTransferJournal> indexed,
			Set<String> storeIssues
	) {
		this.store = Objects.requireNonNull(store, "store");
		this.port = Objects.requireNonNull(port, "port");
		this.indexed.putAll(Objects.requireNonNull(indexed, "indexed"));
		this.storeIssues = Set.copyOf(Objects.requireNonNull(storeIssues, "storeIssues"));
	}

	@Override
	public boolean hasPending(UUID regionId, ContainerTransferActor actor) {
		Objects.requireNonNull(regionId, "regionId");
		Objects.requireNonNull(actor, "actor");
		if (!storeIssues.isEmpty()) return true;
		return indexed.values().stream().anyMatch(journal ->
				journal.regionId().equals(regionId) || journal.actor().equals(actor));
	}

	public boolean hasPending(UUID regionId) {
		Objects.requireNonNull(regionId, "regionId");
		if (!storeIssues.isEmpty()) return true;
		return indexed.values().stream().anyMatch(journal -> journal.regionId().equals(regionId));
	}

	public boolean hasAnyLock() { return !storeIssues.isEmpty() || !indexed.isEmpty(); }

	public boolean hasStoreIssues() { return !storeIssues.isEmpty(); }

	@Override
	public void write(ContainerTransferJournal journal) throws IOException {
		Objects.requireNonNull(journal, "journal");
		store.write(journal);
		indexed.put(journal.transactionId(), journal);
		audited.remove(journal.transactionId());
		awaitingActors.remove(journal.transactionId());
	}

	@Override
	public void delete(UUID transactionId) throws IOException {
		store.delete(transactionId);
		indexed.remove(transactionId);
		audited.remove(transactionId);
		awaitingActors.remove(transactionId);
		reportedLocks.remove(transactionId);
	}

	/** Processes only boot-indexed or newly committed entries; unavailable actors remain event-driven. */
	public void recoverIndexed() {
		if (!storeIssues.isEmpty()) {
			for (String issue : storeIssues) if (!reportedStoreIssues.contains(issue)) {
				port.report(Report.ERROR, "Container-transfer recovery store is globally LOCKED: " + issue, null);
			}
			reportedStoreIssues = storeIssues;
			return;
		}
		reportedStoreIssues = Set.of();
		reportedLocks.keySet().retainAll(indexed.keySet());
		for (UUID transactionId : List.copyOf(indexed.keySet())) {
			if (!awaitingActors.contains(transactionId) && !audited.contains(transactionId)) {
				process(transactionId);
			}
		}
	}

	public void retryPlayer(UUID playerId) {
		Objects.requireNonNull(playerId, "playerId");
		retry(journal -> journal.actor() instanceof ContainerTransferActor.PlayerHand player
				&& player.playerId().equals(playerId));
	}

	public void retryDispenserChunk(String dimensionId, int chunkX, int chunkZ) {
		if (dimensionId == null || dimensionId.isBlank()) return;
		retry(journal -> journal.actor() instanceof ContainerTransferActor.Dispenser dispenser
				&& dispenser.dimensionId().equals(dimensionId)
				&& Math.floorDiv(dispenser.blockX(), 16) == chunkX
				&& Math.floorDiv(dispenser.blockZ(), 16) == chunkZ);
	}

	/** Completes the exact just-committed transaction after its adapter applied the after item. */
	public void retryTransaction(UUID transactionId) {
		if (transactionId == null || !indexed.containsKey(transactionId)) return;
		awaitingActors.remove(transactionId);
		audited.remove(transactionId);
		process(transactionId);
	}

	private void retry(Predicate<ContainerTransferJournal> becameAvailable) {
		for (UUID transactionId : List.copyOf(awaitingActors)) {
			ContainerTransferJournal journal = indexed.get(transactionId);
			if (journal == null || !becameAvailable.test(journal)) continue;
			awaitingActors.remove(transactionId);
			process(transactionId);
		}
	}

	private void process(UUID transactionId) {
		ContainerTransferJournal journal = indexed.get(transactionId);
		if (journal == null) return;
		try {
			port.validate(journal);
			long head = port.committedGeneration(journal.regionId());
			long checksum = port.committedManifestChecksum(journal.regionId());
			CrossDomainRecovery.ExternalTruth actorTruth = port.classifyActor(journal);
			CrossDomainRecovery.Decision decision = decide(head, checksum, journal, actorTruth);
			switch (decision.outcome()) {
				case DISCARD_UNCOMMITTED -> {
					delete(transactionId);
					port.report(Report.WARNING,
							"Discarded uncommitted container transfer WAL " + transactionId
									+ " for region " + journal.regionId(), null);
				}
				case CONVERGE_AFTER -> converge(journal, decision.beforeMembers().contains(0));
				case WAIT_UNAVAILABLE -> awaitingActors.add(transactionId);
				case LOCKED -> lockOnce(transactionId,
						"Container transfer WAL " + transactionId
								+ " is locked by cross-domain recovery: " + decision.reason()
								+ " (region=" + journal.regionId() + ", head=" + head
								+ ", base=" + journal.baseGeneration() + ", target="
								+ journal.targetGeneration() + ", actor=" + actorTruth + ")");
			}
		} catch (IOException | RuntimeException failure) {
			audited.add(transactionId);
			port.report(Report.ERROR, "Could not recover container transfer WAL " + transactionId, failure);
		}
	}

	private void converge(ContainerTransferJournal journal, boolean applyAfter) throws IOException {
		if (applyAfter) {
			Convergence applied = port.applyAfterItem(journal);
			if (applied == Convergence.UNAVAILABLE) {
				awaitingActors.add(journal.transactionId());
				return;
			}
			if (applied == Convergence.CONFLICT) {
				lockActorConflict(journal);
				return;
			}
		}
		port.persistAfterItem(journal.actor());
		CrossDomainRecovery.ExternalTruth readback = port.verifyAfterItem(journal);
		if (readback == CrossDomainRecovery.ExternalTruth.UNAVAILABLE) {
			awaitingActors.add(journal.transactionId());
			return;
		}
		if (readback != CrossDomainRecovery.ExternalTruth.AFTER) {
			lockActorConflict(journal);
			return;
		}
		port.adoptRecoveredCheckpoint(journal);
		delete(journal.transactionId());
		port.publishCommitted(journal);
		port.report(Report.WARNING,
				"Converged container transfer WAL " + journal.transactionId()
						+ " to a durably saved and verified after item", null);
	}

	private void lockActorConflict(ContainerTransferJournal journal) {
		awaitingActors.remove(journal.transactionId());
		lockOnce(journal.transactionId(),
				"Container transfer WAL " + journal.transactionId() + " is locked: actor " + journal.actor()
						+ " changed during convergence or did not read back the authoritative after item");
	}

	private void lockOnce(UUID transactionId, String message) {
		audited.add(transactionId);
		if (!message.equals(reportedLocks.put(transactionId, message))) {
			port.report(Report.ERROR, message, null);
		}
	}

	public static CrossDomainRecovery.Decision decide(
			long head,
			long headManifestChecksum,
			ContainerTransferJournal journal,
			CrossDomainRecovery.ExternalTruth actorTruth
	) {
		CrossDomainRecovery.Boundary boundary = new CrossDomainRecovery.Boundary(
				head, headManifestChecksum, journal.baseGeneration(), journal.targetGeneration(),
				journal.targetManifestChecksum() != -1L, journal.targetManifestChecksum(),
				journal.phase() == ContainerTransferJournal.Phase.REGION_COMMITTED
						? CrossDomainRecovery.JournalPhase.REGION_COMMITTED
						: CrossDomainRecovery.JournalPhase.PREPARED,
				false);
		return CrossDomainRecovery.decide(boundary, List.of(actorTruth));
	}

	public interface JournalStore {
		void write(ContainerTransferJournal journal) throws IOException;

		void delete(UUID transactionId) throws IOException;
	}

	public interface RecoveryPort {
		void validate(ContainerTransferJournal journal);

		long committedGeneration(UUID regionId) throws IOException;

		long committedManifestChecksum(UUID regionId) throws IOException;

		CrossDomainRecovery.ExternalTruth classifyActor(ContainerTransferJournal journal);

		Convergence applyAfterItem(ContainerTransferJournal journal);

		void persistAfterItem(ContainerTransferActor actor) throws IOException;

		CrossDomainRecovery.ExternalTruth verifyAfterItem(ContainerTransferJournal journal);

		void adoptRecoveredCheckpoint(ContainerTransferJournal journal) throws IOException;

		void publishCommitted(ContainerTransferJournal journal);

		void report(Report level, String message, Throwable failure);
	}

	public enum Convergence { APPLIED, UNAVAILABLE, CONFLICT }

	public enum Report { WARNING, ERROR }
}
