package com.example.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-side v2 ordering/capability gate, kept separate from the live v1 ledger until M3 activation. */
public final class FluidMaterialSnapshotLedger {
	private final Map<UUID, Cursor> cursors = new HashMap<>();

	public Decision evaluate(FluidMaterialSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		if (negotiation == null || !negotiation.accepted()
				|| negotiation.selectedVersion() != FluidProtocol.MATERIAL_SNAPSHOT_VERSION
				|| (negotiation.capabilities() & FluidProtocol.CAP_MATERIAL_SNAPSHOT) == 0L
				|| payload.protocolVersion() != negotiation.selectedVersion()) {
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
		if (payload.isSnapshot() && payload.paletteVersion() != FluidProtocol.MATERIAL_PALETTE_VERSION) {
			return Decision.REJECT_PALETTE;
		}
		cursors.put(payload.regionId(), new Cursor(payload.generation(), payload.sequence()));
		return payload.removesRegion() ? Decision.REMOVE_REGION : Decision.ACCEPT_SNAPSHOT;
	}

	public void clear() { cursors.clear(); }

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
