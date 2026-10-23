package mmm.api.transfer;

import mmm.api.StableId;
import java.util.UUID;

/** Requested amount; source composition and internal energy are read from the actual actor. */
public record TransferRequest(
		UUID operationId, ActorRef actor, long expectedActorGeneration,
		UUID regionId, String dimensionId,
		double worldXMetres, double worldYMetres, double worldZMetres,
		Direction direction, TransferQuantity quantity
) {
	public TransferRequest {
		StableId.require(dimensionId, 128, "dimension id");
		if (operationId == null || actor == null || expectedActorGeneration < 0 || regionId == null
				|| !Double.isFinite(worldXMetres) || !Double.isFinite(worldYMetres)
				|| !Double.isFinite(worldZMetres) || direction == null || quantity == null
				|| quantity instanceof TransferQuantity.Gas
				|| quantity instanceof TransferQuantity.Liquid liquid && liquid.internalEnergyJoules().isPresent())
			throw new IllegalArgumentException("Invalid transfer request");
	}

	/** Direction relative to the region inventory. */
	public enum Direction { INSERT, EXTRACT }
}
