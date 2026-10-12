package mmm.api.event;

/**
 * Ephemeral committed-event stream. Callbacks run on the server thread and are not an inventory
 * log; consumers recover missed state through the Query API.
 */
public interface MmmEventApi {
	Subscription subscribe(Listener listener);

	@FunctionalInterface
	interface Listener {
		void onCommitted(MmmCommittedEvent event);
	}

	@FunctionalInterface
	interface Subscription extends AutoCloseable {
		@Override void close();
	}
}
