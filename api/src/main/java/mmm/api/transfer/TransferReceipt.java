package mmm.api.transfer;

import java.util.UUID;

/** Saved with the actor after-state before WAL deletion and committed-event publication. */
public record TransferReceipt(
		UUID operationId, ActorRef actor, UUID actorInstanceId, long actorAfterGeneration,
		UUID regionId, long checkpointGeneration, boolean committed, TransferManifest manifest
) {
	public TransferReceipt {
		if (operationId == null || actor == null || actorInstanceId == null || actorAfterGeneration < 0
				|| regionId == null || checkpointGeneration < 0 || committed != (manifest != null))
			throw new IllegalArgumentException("Invalid transfer receipt");
	}
}
