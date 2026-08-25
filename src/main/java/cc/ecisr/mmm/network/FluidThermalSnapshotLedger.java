package cc.ecisr.mmm.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-side protocol-v5 ordering, capability and registry-identity gate. */
public final class FluidThermalSnapshotLedger {
	private final Map<UUID, Cursor> cursors = new HashMap<>();
	private String thermalFingerprint, chemicalFingerprint, granularFingerprint;

	public Decision evaluate(FluidThermalSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		long required = FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_THERMAL_SNAPSHOT;
		if (negotiation == null || !negotiation.accepted()
				|| negotiation.selectedVersion() != FluidProtocol.THERMAL_SNAPSHOT_VERSION
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
			if (payload.paletteVersion() != FluidThermalSnapshotPayload.PALETTE_VERSION
					|| material.paletteVersion() != FluidProtocol.MATERIAL_PALETTE_VERSION) return Decision.REJECT_PALETTE;
			boolean chemical = (negotiation.capabilities() & FluidProtocol.CAP_CHEMICAL_SNAPSHOT) != 0L;
			boolean granular = (negotiation.capabilities() & FluidProtocol.CAP_GRANULAR_SNAPSHOT) != 0L;
			if (granular != (payload.granularLayer() != null)
					|| !granular && chemical != (payload.chemicalLayer() != null)
					|| granular && payload.chemicalLayer() != null
					|| granular && chemical != (payload.granularLayer().chemical() != null)) return Decision.REJECT_PROTOCOL;
			if (thermalFingerprint != null && !thermalFingerprint.equals(payload.registryFingerprint())) {
				return Decision.REJECT_REGISTRY;
			}
			String nextChemical = payload.chemicalLayer() == null
					? payload.granularLayer() == null || payload.granularLayer().chemical() == null ? null
					: payload.granularLayer().chemical().registryFingerprint()
					: payload.chemicalLayer().registryFingerprint();
			String nextGranular = payload.granularLayer() == null ? null : payload.granularLayer().registryFingerprint();
			if (nextChemical != null && chemicalFingerprint != null && !chemicalFingerprint.equals(nextChemical)
					|| nextGranular != null && granularFingerprint != null && !granularFingerprint.equals(nextGranular)) {
				return Decision.REJECT_REGISTRY;
			}
			thermalFingerprint = payload.registryFingerprint();
			if (nextChemical != null) chemicalFingerprint = nextChemical;
			if (nextGranular != null) granularFingerprint = nextGranular;
		}
		cursors.put(material.regionId(), new Cursor(material.generation(), material.sequence()));
		return payload.removesRegion() ? Decision.REMOVE_REGION : Decision.ACCEPT_SNAPSHOT;
	}

	public void clear() {
		cursors.clear(); thermalFingerprint = null; chemicalFingerprint = null; granularFingerprint = null;
	}

	public enum Decision {
		ACCEPT_SNAPSHOT, REMOVE_REGION, CLEAR_ALL, IGNORE_STALE,
		REJECT_PROTOCOL, REJECT_PALETTE, REJECT_REGISTRY
	}

	private record Cursor(long generation, long sequence) { }
}
