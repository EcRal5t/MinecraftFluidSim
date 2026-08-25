package cc.ecisr.mmm.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-side ordering gate. Tombstones prevent delayed snapshots from resurrecting removed regions. */
public final class FluidSnapshotLedger {
	private final Map<UUID, Cursor> cursors = new HashMap<>();

	public Decision evaluate(FluidSnapshotPayload payload, int negotiatedVersion) {
		if (payload.protocolVersion() != negotiatedVersion || negotiatedVersion != FluidProtocol.CURRENT_VERSION) {
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
		if (payload.isSnapshot() && payload.paletteVersion() != FluidProtocol.PURE_WATER_PALETTE_VERSION) {
			return Decision.REJECT_PALETTE;
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
		REJECT_PROTOCOL,
		REJECT_PALETTE
	}

	private record Cursor(long generation, long sequence) { }
}
