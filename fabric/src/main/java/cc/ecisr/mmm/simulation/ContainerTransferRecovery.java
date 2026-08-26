package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.recovery.CrossDomainRecovery;
import cc.ecisr.mmm.transfer.ContainerTransferJournal;

import java.util.List;

/** Thin container adapter for the shared region-HEAD/external-domain recovery algebra. */
final class ContainerTransferRecovery {
	private ContainerTransferRecovery() { }

	static CrossDomainRecovery.Decision decide(
			long head,
			long headManifestChecksum,
			ContainerTransferJournal journal,
			boolean liveSolverDirty,
			CrossDomainRecovery.ExternalTruth actorTruth
	) {
		CrossDomainRecovery.Boundary boundary = new CrossDomainRecovery.Boundary(
				head, headManifestChecksum, journal.baseGeneration(), journal.targetGeneration(),
				journal.targetManifestChecksum() != -1L, journal.targetManifestChecksum(),
				journal.phase() == ContainerTransferJournal.Phase.REGION_COMMITTED
						? CrossDomainRecovery.JournalPhase.REGION_COMMITTED
						: CrossDomainRecovery.JournalPhase.PREPARED,
				liveSolverDirty);
		return CrossDomainRecovery.decide(boundary, List.of(actorTruth));
	}
}
