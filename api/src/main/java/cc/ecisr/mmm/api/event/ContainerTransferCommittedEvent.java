package cc.ecisr.mmm.api.event;

import cc.ecisr.mmm.api.StableId;
import cc.ecisr.mmm.api.transfer.TransferQuantity;

import java.util.UUID;

/** Durable completion of a controller transfer and its external item actor. */
public record ContainerTransferCommittedEvent(
		UUID transactionId,
		String dimensionId,
		UUID regionId,
		long checkpointGeneration,
		Operation operation,
		TransferQuantity quantity
) implements MmmCommittedEvent {
	public ContainerTransferCommittedEvent {
		if (transactionId == null || regionId == null || checkpointGeneration <= 0L
				|| operation == null || quantity == null) {
			throw new IllegalArgumentException("Committed container transfer is incomplete");
		}
		StableId.require(dimensionId, 128, "dimension id");
	}

	/** Direction is relative to the controller region. */
	public enum Operation {
		INSERT,
		EXTRACT
	}
}
