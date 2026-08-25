package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.api.event.MmmCommittedEvent;
import cc.ecisr.mmm.api.event.MmmEventApi;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe subscription registry; publication is owned by the Minecraft server thread. */
final class CommittedEventHub implements MmmEventApi {
	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

	@Override public Subscription subscribe(Listener listener) {
		Listener registered = java.util.Objects.requireNonNull(listener, "listener");
		listeners.add(registered);
		AtomicBoolean active = new AtomicBoolean(true);
		return () -> {
			if (active.compareAndSet(true, false)) listeners.remove(registered);
		};
	}

	void publish(MmmCommittedEvent event) {
		if (event == null) throw new IllegalArgumentException("Committed event is missing");
		for (Listener listener : listeners) try {
			listener.onCommitted(event);
		} catch (RuntimeException listenerFailure) {
			MmmMod.LOGGER.error("Matter, Motion & Mechanics committed-event listener failed", listenerFailure);
		}
	}

	void clear() {
		listeners.clear();
	}
}
