package com.example.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-side protocol-v4 ordering, capability and registry identity gate. */
public final class FluidGranularSnapshotLedger {
	private final Map<UUID, Cursor> cursors = new HashMap<>();
	private String granularRegistryFingerprint, chemicalRegistryFingerprint;

	public Decision evaluate(FluidGranularSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		long required = FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_GRANULAR_SNAPSHOT;
		if (negotiation == null || !negotiation.accepted()
				|| negotiation.selectedVersion() != FluidProtocol.GRANULAR_SNAPSHOT_VERSION
				|| (negotiation.capabilities() & required) != required
				|| payload.protocolVersion() != negotiation.selectedVersion()) return Decision.REJECT_PROTOCOL;
		if (payload.clearsAll()) { clear(); return Decision.CLEAR_ALL; }
		FluidMaterialSnapshotPayload material = payload.material();
		Cursor current = cursors.get(material.regionId());
		if (current != null && (material.generation() < current.generation
				|| material.generation() == current.generation && material.sequence() <= current.sequence)) {
			return Decision.IGNORE_STALE;
		}
		if (payload.isSnapshot()) {
			if (payload.paletteVersion() != FluidProtocol.GRANULAR_PALETTE_VERSION
					|| material.paletteVersion() != FluidProtocol.MATERIAL_PALETTE_VERSION) return Decision.REJECT_PALETTE;
			boolean chemicalNegotiated = (negotiation.capabilities() & FluidProtocol.CAP_CHEMICAL_SNAPSHOT) != 0L;
			if (chemicalNegotiated != (payload.chemical() != null)) return Decision.REJECT_PROTOCOL;
			if (granularRegistryFingerprint != null
					&& !granularRegistryFingerprint.equals(payload.registryFingerprint())) return Decision.REJECT_REGISTRY;
			if (payload.chemical() != null && chemicalRegistryFingerprint != null
					&& !chemicalRegistryFingerprint.equals(payload.chemical().registryFingerprint())) {
				return Decision.REJECT_REGISTRY;
			}
			granularRegistryFingerprint = payload.registryFingerprint();
			if (payload.chemical() != null) chemicalRegistryFingerprint = payload.chemical().registryFingerprint();
		}
		cursors.put(material.regionId(), new Cursor(material.generation(), material.sequence()));
		return payload.removesRegion() ? Decision.REMOVE_REGION : Decision.ACCEPT_SNAPSHOT;
	}

	public void clear() {
		cursors.clear(); granularRegistryFingerprint = null; chemicalRegistryFingerprint = null;
	}

	public enum Decision {
		ACCEPT_SNAPSHOT, REMOVE_REGION, CLEAR_ALL, IGNORE_STALE,
		REJECT_PROTOCOL, REJECT_PALETTE, REJECT_REGISTRY
	}

	private record Cursor(long generation, long sequence) { }
}
