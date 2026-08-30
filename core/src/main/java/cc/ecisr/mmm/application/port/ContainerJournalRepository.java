package cc.ecisr.mmm.application.port;

import cc.ecisr.mmm.transfer.ContainerTransferActor;
import cc.ecisr.mmm.transfer.ContainerTransferJournal;

import java.io.IOException;
import java.util.UUID;

/** Durable container WAL access plus the in-memory recovery index that shadows it. */
public interface ContainerJournalRepository {
	boolean hasPending(UUID regionId, ContainerTransferActor actor);

	void write(ContainerTransferJournal journal) throws IOException;

	void delete(UUID transactionId) throws IOException;

	void markSolverDirty(UUID transactionId);

	void markSolverClean(UUID transactionId);

	void markRegionCommitted(UUID transactionId);
}
