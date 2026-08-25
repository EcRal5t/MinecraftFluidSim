package cc.ecisr.mmm.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-side ordering gate. Tombstones prevent delayed snapshots from resurrecting removed regions. */
public final class FluidSnapshotLedger {
	private final Map<UUID, Cursor> cursors = new HashMap<>();

	public Decision evaluate(FluidSnapshotPayload payload, FluidProtocol.Layout negotiatedLayout) {
		if (payload.layout() != negotiatedLayout || negotiatedLayout != FluidProtocol.Layout.PURE) {
			return Decision.REJECT_PROTOCOL;
		}
		if (payload.clearsAll()) {
			cursors.clear();
			return Decision.CLEAR_ALL;
		}
		Cursor current = cursors.get(payload.regionId());
		if (current != null && (payload.generation() < current.generation()
				|| payload.generation() == current.generation() && payload.sequence() <= current.sequence())) {
			return Decision.IGNORE_STALE;
		}
		cursors.put(payload.regionId(), new Cursor(payload.generation(), payload.sequence()));
		return payload.removesRegion() ? Decision.REMOVE_REGION : Decision.ACCEPT_SNAPSHOT;
	}

	public void clear() {
		cursors.clear();
	}

	public enum Decision {
		ACCEPT_SNAPSHOT,
		REMOVE_REGION,
		CLEAR_ALL,
		IGNORE_STALE,
		REJECT_PROTOCOL
	}

	private record Cursor(long generation, long sequence) { }
}
