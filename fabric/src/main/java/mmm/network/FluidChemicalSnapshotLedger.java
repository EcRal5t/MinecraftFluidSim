package mmm.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-side chemical ordering, capability and authoritative registry identity gate. */
public final class FluidChemicalSnapshotLedger {
	private final Map<UUID, Cursor> cursors = new HashMap<>();
	private String registryFingerprint;

	public Decision evaluate(FluidChemicalSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		if (negotiation == null || !negotiation.accepted()
				|| negotiation.selectedLayout() != FluidProtocol.Layout.CHEMICAL
				|| (negotiation.capabilities() & (FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_CHEMICAL_SNAPSHOT))
				!= (FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_CHEMICAL_SNAPSHOT)
				|| payload.layout() != negotiation.selectedLayout()) return Decision.REJECT_PROTOCOL;
		if (payload.clearsAll()) {
			clear();
			return Decision.CLEAR_ALL;
		}
		FluidMaterialSnapshotPayload material = payload.material();
		Cursor current = cursors.get(material.regionId());
		if (current != null && (material.generation() < current.generation
				|| material.generation() == current.generation && material.sequence() <= current.sequence)) {
			return Decision.IGNORE_STALE;
		}
		if (payload.isSnapshot()) {
			if (registryFingerprint != null && !registryFingerprint.equals(payload.registryFingerprint())) {
				return Decision.REJECT_REGISTRY;
			}
			registryFingerprint = payload.registryFingerprint();
		}
		cursors.put(material.regionId(), new Cursor(material.generation(), material.sequence()));
		return payload.removesRegion() ? Decision.REMOVE_REGION : Decision.ACCEPT_SNAPSHOT;
	}

	public void clear() {
		cursors.clear(); registryFingerprint = null;
	}

	public enum Decision {
		ACCEPT_SNAPSHOT, REMOVE_REGION, CLEAR_ALL, IGNORE_STALE,
		REJECT_PROTOCOL, REJECT_REGISTRY
	}

	private record Cursor(long generation, long sequence) { }
}
