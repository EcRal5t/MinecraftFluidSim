package mmm.api.transfer;

import mmm.api.StableId;

/** Stable provider-owned identity; registration durably binds it to one actor instance. */
public record ActorRef(String providerId, String objectId) {
	public ActorRef {
		StableId.require(providerId, 128, "actor provider id");
		if (objectId == null || objectId.isBlank() || objectId.length() > 256)
			throw new IllegalArgumentException("Invalid actor object id");
	}
}
