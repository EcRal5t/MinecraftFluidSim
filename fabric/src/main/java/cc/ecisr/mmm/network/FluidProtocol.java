package cc.ecisr.mmm.network;

import java.util.Objects;

/** Exact snapshot-layout and capability negotiation shared by configuration payloads and codec tests. */
public final class FluidProtocol {
	public static final long CAP_REGION_IDENTITY = 1L;
	public static final long CAP_GENERATION_SEQUENCE = 1L << 1;
	public static final long CAP_OPTIONAL_SECTIONS = 1L << 2;
	public static final long CAP_MULTI_REGION_RENDER = 1L << 3;
	public static final long CAP_MATERIAL_SNAPSHOT = 1L << 4;
	public static final long CAP_CHEMICAL_SNAPSHOT = 1L << 5;
	public static final long CAP_GRANULAR_SNAPSHOT = 1L << 6;
	public static final long CAP_THERMAL_SNAPSHOT = 1L << 7;
	public static final long SUPPORTED_CAPABILITIES = CAP_REGION_IDENTITY
			| CAP_GENERATION_SEQUENCE | CAP_OPTIONAL_SECTIONS | CAP_MULTI_REGION_RENDER
			| CAP_MATERIAL_SNAPSHOT | CAP_CHEMICAL_SNAPSHOT | CAP_GRANULAR_SNAPSHOT | CAP_THERMAL_SNAPSHOT;
	public static final long REQUIRED_CAPABILITIES = CAP_REGION_IDENTITY
			| CAP_GENERATION_SEQUENCE | CAP_OPTIONAL_SECTIONS | CAP_MULTI_REGION_RENDER;

	private FluidProtocol() { }

	public enum Layout { PURE, MATERIAL, CHEMICAL, GRANULAR, THERMAL }

	public static Offer serverOffer() {
		return new Offer(Layout.PURE, REQUIRED_CAPABILITIES, 0L);
	}

	public static Offer serverOffer(boolean materialsEnabled) {
		return materialsEnabled ? materialServerOffer() : serverOffer();
	}

	public static Offer serverOffer(boolean materialsEnabled, boolean chemistryEnabled) {
		if (chemistryEnabled && !materialsEnabled) throw new IllegalArgumentException("Chemistry protocol requires materials");
		return chemistryEnabled ? chemicalServerOffer() : serverOffer(materialsEnabled);
	}

	public static Offer serverOffer(boolean materialsEnabled, boolean chemistryEnabled, boolean granularEnabled) {
		if (granularEnabled && !materialsEnabled) throw new IllegalArgumentException("Granular protocol requires materials");
		return granularEnabled ? granularServerOffer(chemistryEnabled) : serverOffer(materialsEnabled, chemistryEnabled);
	}

	public static Offer serverOffer(
			boolean materialsEnabled, boolean chemistryEnabled, boolean granularEnabled, boolean thermalEnabled
	) {
		if (thermalEnabled && !materialsEnabled) throw new IllegalArgumentException("Thermal protocol requires materials");
		return thermalEnabled ? thermalServerOffer(chemistryEnabled, granularEnabled)
				: serverOffer(materialsEnabled, chemistryEnabled, granularEnabled);
	}

	public static Offer materialServerOffer() {
		return new Offer(Layout.MATERIAL, REQUIRED_CAPABILITIES | CAP_MATERIAL_SNAPSHOT, 0L);
	}

	public static Offer chemicalServerOffer() {
		return new Offer(Layout.CHEMICAL,
				REQUIRED_CAPABILITIES | CAP_MATERIAL_SNAPSHOT | CAP_CHEMICAL_SNAPSHOT, 0L);
	}

	public static Offer granularServerOffer(boolean chemistryEnabled) {
		long capabilities = REQUIRED_CAPABILITIES | CAP_MATERIAL_SNAPSHOT | CAP_GRANULAR_SNAPSHOT;
		if (chemistryEnabled) capabilities |= CAP_CHEMICAL_SNAPSHOT;
		return new Offer(Layout.GRANULAR, capabilities, 0L);
	}

	public static Offer thermalServerOffer(boolean chemistryEnabled, boolean granularEnabled) {
		long capabilities = REQUIRED_CAPABILITIES | CAP_MATERIAL_SNAPSHOT | CAP_THERMAL_SNAPSHOT;
		if (chemistryEnabled) capabilities |= CAP_CHEMICAL_SNAPSHOT;
		if (granularEnabled) capabilities |= CAP_GRANULAR_SNAPSHOT;
		return new Offer(Layout.THERMAL, capabilities, 0L);
	}

	public static Negotiation negotiate(Offer offer) {
		Objects.requireNonNull(offer, "offer");
		long missing = offer.requiredCapabilities() & ~SUPPORTED_CAPABILITIES;
		if (missing != 0L) {
			return Negotiation.rejected("Matter, Motion & Mechanics client is missing required capabilities 0x"
					+ Long.toHexString(missing));
		}
		long selectedCapabilities = SUPPORTED_CAPABILITIES
				& (offer.requiredCapabilities() | offer.optionalCapabilities());
		return Negotiation.accepted(offer.layout(), selectedCapabilities);
	}

	public static Negotiation validateResponse(Offer offer, FluidProtocolResponsePayload response) {
		Objects.requireNonNull(offer, "offer");
		Objects.requireNonNull(response, "response");
		if (!response.accepted()) return Negotiation.rejected(response.failure());
		if (response.selectedLayout() != offer.layout()) {
			return Negotiation.rejected("Client selected a snapshot layout not offered by the server");
		}
		long missing = offer.requiredCapabilities() & ~response.capabilities();
		if (missing != 0L) {
			return Negotiation.rejected("Client omitted required Matter, Motion & Mechanics capabilities 0x" + Long.toHexString(missing));
		}
		long advertised = offer.requiredCapabilities() | offer.optionalCapabilities();
		if ((response.capabilities() & ~advertised) != 0L) {
			return Negotiation.rejected("Client selected capabilities not offered by the server");
		}
		if ((response.capabilities() & ~SUPPORTED_CAPABILITIES) != 0L) {
			return Negotiation.rejected("Client selected capabilities unsupported by this server");
		}
		return Negotiation.accepted(response.selectedLayout(), response.capabilities());
	}

	public record Offer(Layout layout, long requiredCapabilities, long optionalCapabilities) {
		public Offer {
			Objects.requireNonNull(layout, "layout");
			if ((requiredCapabilities & optionalCapabilities) != 0L) {
				throw new IllegalArgumentException("Required and optional capabilities must be disjoint");
			}
		}
	}

	public record Negotiation(boolean accepted, Layout selectedLayout, long capabilities, String failure) {
		public Negotiation {
			if (accepted && (selectedLayout == null || failure != null)) {
				throw new IllegalArgumentException("Accepted negotiation must select a layout without failure");
			}
			if (!accepted && (selectedLayout != null || capabilities != 0L || failure == null || failure.isBlank())) {
				throw new IllegalArgumentException("Rejected negotiation must contain only a readable failure");
			}
		}

		private static Negotiation accepted(Layout selectedLayout, long capabilities) {
			return new Negotiation(true, selectedLayout, capabilities, null);
		}

		private static Negotiation rejected(String failure) {
			return new Negotiation(false, null, 0L, Objects.requireNonNullElse(failure, "incompatible protocol"));
		}
	}
}
