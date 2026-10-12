package mmm.api.event;

import java.util.UUID;

/** A notification emitted only after the event's durable commit boundary has completed. */
public interface MmmCommittedEvent {
	String dimensionId();

	UUID regionId();

	long checkpointGeneration();
}
