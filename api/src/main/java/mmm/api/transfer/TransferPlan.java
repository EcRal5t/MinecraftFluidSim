package mmm.api.transfer;

import java.util.UUID;

/** Immutable preview; token/session and captured versions are revalidated during commit. */
public record TransferPlan(
		UUID planId, UUID sessionId, long expiresAtServerTick, TransferRequest request,
		UUID actorInstanceId, long actorGeneration, long regionCheckpointGeneration,
		TransferManifest manifest
) {
	public TransferPlan {
		if (planId == null || sessionId == null || expiresAtServerTick < 0 || request == null
				|| actorInstanceId == null || actorGeneration < 0 || regionCheckpointGeneration < 0 || manifest == null)
			throw new IllegalArgumentException("Invalid transfer plan");
	}
}
