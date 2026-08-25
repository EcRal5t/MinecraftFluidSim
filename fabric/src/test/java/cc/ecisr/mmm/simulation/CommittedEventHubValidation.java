package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.api.event.ContainerTransferCommittedEvent;
import cc.ecisr.mmm.api.event.MmmCommittedEvent;
import cc.ecisr.mmm.api.event.MmmEventApi;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

/** Subscription lifecycle and listener-isolation checks for committed events. */
public final class CommittedEventHubValidation {
	private CommittedEventHubValidation() { }

	public static void main(String[] args) {
		CommittedEventHub hub = new CommittedEventHub();
		List<MmmCommittedEvent> received = new ArrayList<>();
		MmmEventApi.Subscription subscription = hub.subscribe(received::add);
		ContainerTransferCommittedEvent first = event(1L, OptionalDouble.of(125.0));
		hub.publish(first);
		require(received.equals(List.of(first)), "subscriber did not receive the committed event exactly once");

		subscription.close();
		subscription.close();
		hub.publish(event(2L, OptionalDouble.empty()));
		require(received.equals(List.of(first)), "closed subscription still received events");

		MmmEventApi.Subscription retained = hub.subscribe(received::add);
		hub.clear();
		hub.publish(event(3L, OptionalDouble.empty()));
		require(received.equals(List.of(first)), "cleared server event hub retained listeners");
		retained.close();
		System.out.println("Committed event hub validation passed");
	}

	private static ContainerTransferCommittedEvent event(long generation, OptionalDouble energy) {
		return new ContainerTransferCommittedEvent(new UUID(1L, generation), "mmm:event_test",
				new UUID(2L, 3L), generation, ContainerTransferCommittedEvent.Operation.INSERT,
				ContainerTransferCommittedEvent.ResourceKind.LIQUID_NANOVOLUME, 17L, energy);
	}

	private static void require(boolean condition, String failure) {
		if (!condition) throw new AssertionError(failure);
	}
}
