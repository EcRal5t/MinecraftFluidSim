package cc.ecisr.mmm.api.event;

import cc.ecisr.mmm.api.StableId;

import java.util.OptionalDouble;
import java.util.UUID;

/** Durable completion of a controller transfer and its external item actor. */
public record ContainerTransferCommittedEvent(
		UUID transactionId,
		String dimensionId,
		UUID regionId,
		long checkpointGeneration,
		Operation operation,
		ResourceKind resourceKind,
		long amount,
		OptionalDouble internalEnergyJoules
) implements MmmCommittedEvent {
	public ContainerTransferCommittedEvent {
		if (transactionId == null || regionId == null || checkpointGeneration <= 0L
				|| operation == null || resourceKind == null || amount <= 0L
				|| internalEnergyJoules == null) {
			throw new IllegalArgumentException("Committed container transfer is incomplete");
		}
		StableId.require(dimensionId, 128, "dimension id");
		if (internalEnergyJoules.isPresent()
				&& !Double.isFinite(internalEnergyJoules.getAsDouble())) {
			throw new IllegalArgumentException("Committed container energy must be finite");
		}
		if (resourceKind == ResourceKind.GRANULAR_SOLID_VOLUME_QUANTA
				&& internalEnergyJoules.isPresent()) {
			throw new IllegalArgumentException("Granular item transfers do not carry liquid internal energy");
		}
	}

	/** Direction is relative to the controller region. */
	public enum Operation {
		INSERT,
		EXTRACT
	}

	public enum ResourceKind {
		/** One amount unit is 10^-9 cubic metres. */
		LIQUID_NANOVOLUME,
		/** One amount unit is 2^-40 block-volume. */
		GRANULAR_SOLID_VOLUME_QUANTA
	}
}
