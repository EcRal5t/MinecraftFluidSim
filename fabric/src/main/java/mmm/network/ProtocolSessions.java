package mmm.network;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One server runtime's negotiated play sessions; it never keys state by MinecraftServer. */
public final class ProtocolSessions {
	private final Map<UUID, FluidProtocol.Negotiation> sessions = new HashMap<>();

	public synchronized void accept(UUID playerId, FluidProtocol.Negotiation negotiation) {
		Objects.requireNonNull(playerId, "Player identity");
		Objects.requireNonNull(negotiation, "Protocol negotiation");
		if (!negotiation.accepted()) throw new IllegalArgumentException("Rejected negotiation cannot become a session");
		sessions.put(playerId, negotiation);
	}

	public synchronized boolean isNegotiated(UUID playerId) {
		return negotiated(playerId) != null;
	}

	public synchronized boolean isNegotiated(UUID playerId, FluidProtocol.Layout layout) {
		FluidProtocol.Negotiation negotiation = negotiated(playerId);
		return negotiation != null && negotiation.selectedLayout() == layout;
	}

	public synchronized void remove(UUID playerId) {
		sessions.remove(playerId);
	}

	public synchronized void clear() {
		sessions.clear();
	}

	private FluidProtocol.Negotiation negotiated(UUID playerId) {
		FluidProtocol.Negotiation negotiation = sessions.get(playerId);
		if (negotiation == null || !negotiation.accepted()
				|| (negotiation.capabilities() & FluidProtocol.REQUIRED_CAPABILITIES)
				!= FluidProtocol.REQUIRED_CAPABILITIES) return null;
		long required = switch (negotiation.selectedLayout()) {
			case PURE -> 0L;
			case MATERIAL -> FluidProtocol.CAP_MATERIAL_SNAPSHOT;
			case CHEMICAL -> FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_CHEMICAL_SNAPSHOT;
			case GRANULAR -> FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_GRANULAR_SNAPSHOT;
			case THERMAL -> FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_THERMAL_SNAPSHOT;
		};
		return (negotiation.capabilities() & required) == required ? negotiation : null;
	}
}
