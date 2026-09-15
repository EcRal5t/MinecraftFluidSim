package cc.ecisr.mmm.application;

import cc.ecisr.mmm.application.port.ContainerJournalRepository;
import cc.ecisr.mmm.recovery.CrossDomainRecovery;
import cc.ecisr.mmm.transfer.ContainerFluidLedger;
import cc.ecisr.mmm.transfer.ContainerTransferActor;
import cc.ecisr.mmm.transfer.ContainerTransferJournal;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Characterizes the loader-neutral container transaction and event-driven recovery owners. */
public final class ContainerApplicationValidation {
	private static final String PROFILE = "a".repeat(64);
	private static int passed;

	private ContainerApplicationValidation() { }

	public static void main(String[] args) {
		run("fault matrix preserves WAL and HEAD boundaries", ContainerApplicationValidation::faultMatrix);
		run("foreign profile is rejected before PREPARED WAL", ContainerApplicationValidation::profileMismatch);
		run("response loss converges actor and publishes only after WAL deletion",
				ContainerApplicationValidation::responseLossRecovery);
		run("unavailable actor retries only on its matching availability event",
				ContainerApplicationValidation::unavailableActorRetry);
		run("a WAL published before its store failure stays pending",
				ContainerApplicationValidation::publishedWalStaysPending);
		System.out.println("ContainerApplicationValidation passed " + passed + " scenarios");
	}

	private static void faultMatrix() {
		for (ContainerTransferCoordinator.FaultPoint point : ContainerTransferCoordinator.FaultPoint.values()) {
			FakeJournals journals = new FakeJournals();
			FakeRegionPort regions = new FakeRegionPort();
			ContainerTransferCoordinator coordinator = new ContainerTransferCoordinator(journals, regions);
			coordinator.setFaultPoint(point);
			ContainerTransferUseCase.Result result = new ContainerTransferUseCase(coordinator).execute(command(PROFILE));
			if (point == ContainerTransferCoordinator.FaultPoint.NONE) {
				check(result.committed(), "no-fault transaction did not commit");
				check(result.actualAmount() == ContainerFluidLedger.NANOS_PER_BLOCK,
						"committed result lost exact actual settlement");
				check(regions.commitCount == 1 && journals.current.phase()
						== ContainerTransferJournal.Phase.REGION_COMMITTED,
						"no-fault transaction did not publish committed WAL before HEAD");
				continue;
			}
			check(!result.committed(), point + " unexpectedly reported success");
			switch (point) {
				case BEFORE_PREPARE -> check(journals.current == null && regions.executeCount == 0,
						"before-prepare fault changed durable or solver state");
				case AFTER_PREPARE -> check(journals.current.phase()
						== ContainerTransferJournal.Phase.PREPARED && regions.executeCount == 0,
						"after-prepare fault crossed the solver boundary");
				case AFTER_SOLVER -> check(journals.current.phase()
						== ContainerTransferJournal.Phase.PREPARED && regions.executeCount == 1
						&& regions.stageCount == 0,
						"after-solver fault did not retain clean PREPARED evidence");
				case AFTER_STAGE -> check(regions.stageCount == 1 && regions.commitCount == 0
						&& journals.current.phase() == ContainerTransferJournal.Phase.PREPARED,
						"after-stage fault crossed the committed-WAL boundary");
				case AFTER_WAL -> check(regions.stageCount == 1 && regions.commitCount == 0
						&& journals.current.phase() == ContainerTransferJournal.Phase.REGION_COMMITTED,
						"after-WAL fault changed HEAD");
				case AFTER_CHECKPOINT -> check(regions.commitCount == 1
						&& result.transactionId() != null
						&& result.committedGeneration() == 5L,
						"after-checkpoint response loss did not retain committed recovery identity");
				case NONE -> throw new AssertionError("handled above");
			}
		}
		FakeJournals journals = new FakeJournals();
		journals.failDelete = true;
		FakeRegionPort regions = new FakeRegionPort();
		regions.execution = new ContainerTransferCoordinator.Execution(
				false, null, "capacity exhausted", 0L, null);
		ContainerTransferUseCase.Result cleanupFailure = new ContainerTransferUseCase(
				new ContainerTransferCoordinator(journals, regions)).execute(command(PROFILE));
		check(cleanupFailure.status() == ContainerTransferUseCase.Status.LOCKED
				&& cleanupFailure.transactionId() != null && journals.current != null,
				"failed PREPARED cleanup hid durable recovery evidence");
	}

	private static void profileMismatch() {
		FakeJournals journals = new FakeJournals();
		FakeRegionPort regions = new FakeRegionPort();
		ContainerTransferUseCase.Result result = new ContainerTransferUseCase(
				new ContainerTransferCoordinator(journals, regions)).execute(command("b".repeat(64)));
		check(result.status() == ContainerTransferUseCase.Status.REJECTED,
				"foreign profile was not rejected");
		check(journals.current == null && regions.executeCount == 0,
				"foreign profile reached WAL or solver state");
	}

	private static void responseLossRecovery() {
		ContainerTransferJournal journal = committedJournal(ContainerTransferActor.player(
				UUID.randomUUID(), ContainerTransferActor.Hand.MAIN));
		FakeStore store = new FakeStore(journal);
		FakeRecoveryPort port = new FakeRecoveryPort(journal.targetGeneration(),
				journal.targetManifestChecksum(), CrossDomainRecovery.ExternalTruth.BEFORE);
		port.store = store;
		ContainerRecoveryCoordinator recovery = new ContainerRecoveryCoordinator(
				store, port, Map.of(journal.transactionId(), journal), Set.of());
		recovery.retryTransaction(journal.transactionId());
		check(port.applyCount == 1 && port.persistCount == 1 && port.adoptCount == 1 && port.publishCount == 1,
				"response-loss recovery did not converge and publish exactly once");
		check(!store.journals.containsKey(journal.transactionId())
				&& port.adoptedBeforeDelete && port.deletedBeforePublish,
				"response-loss recovery did not adopt HEAD before WAL deletion and event publication");
	}

	private static void unavailableActorRetry() {
		ContainerTransferActor.PlayerHand actor = ContainerTransferActor.player(
				UUID.randomUUID(), ContainerTransferActor.Hand.OFF);
		ContainerTransferJournal journal = committedJournal(actor);
		FakeStore store = new FakeStore(journal);
		FakeRecoveryPort port = new FakeRecoveryPort(journal.targetGeneration(),
				journal.targetManifestChecksum(), CrossDomainRecovery.ExternalTruth.UNAVAILABLE);
		port.store = store;
		ContainerRecoveryCoordinator recovery = new ContainerRecoveryCoordinator(
				store, port, Map.of(journal.transactionId(), journal), Set.of());
		recovery.recoverIndexed();
		check(port.persistCount == 0 && store.journals.containsKey(journal.transactionId()),
				"unavailable actor was polled into a mutation");
		port.truth = CrossDomainRecovery.ExternalTruth.AFTER;
		recovery.retryPlayer(UUID.randomUUID());
		check(port.persistCount == 0, "unrelated player event retried the actor");
		recovery.retryPlayer(actor.playerId());
		check(port.persistCount == 1 && port.publishCount == 1
				&& !store.journals.containsKey(journal.transactionId()),
				"matching player event did not converge the actor");
	}

	/**
	 * A store can publish the WAL file and still fail its own durability step. The coordinator must
	 * not read that failure as "nothing was written": the transaction stays indexed so the next
	 * recovery pass reads the WAL back from disk instead of letting further transfers proceed.
	 */
	private static void publishedWalStaysPending() {
		ContainerTransferJournal journal = committedJournal(ContainerTransferActor.player(
				UUID.randomUUID(), ContainerTransferActor.Hand.MAIN));
		FakeStore store = new FakeStore();
		store.failWriteAfterPublish = true;
		FakeRecoveryPort port = new FakeRecoveryPort(journal.targetGeneration(),
				journal.targetManifestChecksum(), CrossDomainRecovery.ExternalTruth.BEFORE);
		port.store = store;
		ContainerRecoveryCoordinator recovery = new ContainerRecoveryCoordinator(store, port, Map.of(), Set.of());
		check(!recovery.hasPending(journal.regionId()), "an empty index reported a pending WAL");
		try {
			recovery.write(journal);
			check(false, "a store write failure was swallowed");
		} catch (IOException expected) {
			check(store.journals.containsKey(journal.transactionId()),
					"the injected store did not publish before failing");
		}
		check(recovery.hasPending(journal.regionId()) && recovery.hasAnyLock(),
				"a WAL that may already be published was dropped from the online index");
	}

	private static ContainerTransferUseCase.Command command(String itemProfile) {
		ContainerTransferJournal.ItemFingerprint before = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.MATERIAL_BUCKET,
				ContainerFluidLedger.NANOS_PER_BLOCK, -1L,
				"mmm:water@1000000000@3ff0000000000000", Double.doubleToRawLongBits(1.0),
				null, itemProfile);
		return new ContainerTransferUseCase.Command(
				UUID.randomUUID(), "minecraft:overworld", 4L, false, PROFILE, null,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN),
				before, before, ContainerTransferJournal.Operation.INSERT,
				ContainerTransferJournal.ResourceKind.FLUID_NANOS,
				ContainerFluidLedger.NANOS_PER_BLOCK, 0L);
	}

	private static ContainerTransferJournal committedJournal(ContainerTransferActor actor) {
		UUID tx = UUID.randomUUID();
		ContainerTransferJournal.ItemFingerprint before = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.WATER_BUCKET, -1L, null);
		ContainerTransferJournal.ItemFingerprint after = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L, null);
		return new ContainerTransferJournal(tx, UUID.randomUUID(), "minecraft:overworld", 4L, 5L,
				ContainerTransferJournal.Operation.INSERT, ContainerFluidLedger.NANOS_PER_BLOCK,
				actor, before, after, 0x7788L, ContainerTransferJournal.Phase.REGION_COMMITTED);
	}

	private static void run(String name, Runnable scenario) {
		scenario.run();
		passed++;
		System.out.println("PASS " + name);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static final class FakeJournals implements ContainerJournalRepository {
		private ContainerTransferJournal current;
		private boolean failDelete;

		@Override public boolean hasPending(UUID regionId, ContainerTransferActor actor) { return current != null; }
		@Override public void write(ContainerTransferJournal journal) { current = journal; }
		@Override public void delete(UUID transactionId) throws IOException {
			if (failDelete) throw new IOException("injected delete failure");
			current = null;
		}
	}

	private static final class FakeRegionPort implements ContainerTransferCoordinator.RegionPort {
		private int executeCount;
		private int stageCount;
		private int commitCount;
		private ContainerTransferCoordinator.Execution execution =
				new ContainerTransferCoordinator.Execution(true,
						new ContainerTransferJournal.ItemFingerprint(
								ContainerTransferJournal.ItemKind.EMPTY_MATERIAL_BUCKET, -1L, null),
						"exact", ContainerFluidLedger.NANOS_PER_BLOCK, null);

		@Override
		public ContainerTransferCoordinator.Execution execute(ContainerTransferUseCase.Command command) {
			executeCount++;
			return execution;
		}

		@Override
		public ContainerTransferCoordinator.StagedCheckpoint stage(
				UUID transactionId, UUID regionId, long targetGeneration
		) {
			stageCount++;
			return () -> 0x7788L;
		}

		@Override public void abort(ContainerTransferCoordinator.StagedCheckpoint staged) { }

		@Override
		public long commit(ContainerTransferCoordinator.StagedCheckpoint staged) {
			commitCount++;
			return 5L;
		}

		@Override public void lock(UUID regionId, String diagnostic) { }
	}

	private static final class FakeStore implements ContainerRecoveryCoordinator.JournalStore {
		private final Map<UUID, ContainerTransferJournal> journals = new HashMap<>();
		private boolean failWriteAfterPublish;

		private FakeStore() { }
		private FakeStore(ContainerTransferJournal journal) { journals.put(journal.transactionId(), journal); }
		@Override public void write(ContainerTransferJournal journal) throws IOException {
			journals.put(journal.transactionId(), journal);
			if (failWriteAfterPublish) throw new IOException("WAL published but the directory sync failed");
		}
		@Override public void delete(UUID transactionId) { journals.remove(transactionId); }
	}

	private static final class FakeRecoveryPort implements ContainerRecoveryCoordinator.RecoveryPort {
		private final long head;
		private final long checksum;
		private CrossDomainRecovery.ExternalTruth truth;
		private int applyCount;
		private int persistCount;
		private int adoptCount;
		private int publishCount;
		private boolean adoptedBeforeDelete;
		private boolean deletedBeforePublish;
		private FakeStore store;

		private FakeRecoveryPort(long head, long checksum, CrossDomainRecovery.ExternalTruth truth) {
			this.head = head;
			this.checksum = checksum;
			this.truth = truth;
		}

		@Override public void validate(ContainerTransferJournal journal) { }
		@Override public long committedGeneration(UUID regionId) { return head; }
		@Override public long committedManifestChecksum(UUID regionId) { return checksum; }
		@Override public CrossDomainRecovery.ExternalTruth classifyActor(ContainerTransferJournal journal) { return truth; }
		@Override public ContainerRecoveryCoordinator.Convergence applyAfterItem(ContainerTransferJournal journal) {
			applyCount++;
			truth = CrossDomainRecovery.ExternalTruth.AFTER;
			return ContainerRecoveryCoordinator.Convergence.APPLIED;
		}
		@Override public void persistAfterItem(ContainerTransferActor actor) { persistCount++; }
		@Override public CrossDomainRecovery.ExternalTruth verifyAfterItem(ContainerTransferJournal journal) { return truth; }
		@Override public void adoptRecoveredCheckpoint(ContainerTransferJournal journal) {
			adoptCount++;
			adoptedBeforeDelete = store != null && store.journals.containsKey(journal.transactionId());
		}
		@Override public void publishCommitted(ContainerTransferJournal journal) {
			publishCount++;
			deletedBeforePublish = store != null && !store.journals.containsKey(journal.transactionId());
		}
		@Override public void report(ContainerRecoveryCoordinator.Report level, String message, Throwable failure) { }
	}
}
