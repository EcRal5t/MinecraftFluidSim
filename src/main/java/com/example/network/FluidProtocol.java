package com.example.network;

import java.util.Objects;

/** Version and capability negotiation shared by configuration payloads and standalone codec tests. */
public final class FluidProtocol {
	public static final int MIN_VERSION = 1;
	public static final int MAX_VERSION = 5;
	/** Live play traffic remains v1 until the M3 feature gate registers and selects v2. */
	public static final int CURRENT_VERSION = 1;
	public static final int MATERIAL_SNAPSHOT_VERSION = 2;
	public static final int CHEMICAL_SNAPSHOT_VERSION = 3;
	public static final int GRANULAR_SNAPSHOT_VERSION = 4;
	public static final int THERMAL_SNAPSHOT_VERSION = 5;
	public static final int PURE_WATER_PALETTE_VERSION = 1;
	public static final int MATERIAL_PALETTE_VERSION = 1;
	public static final int GRANULAR_PALETTE_VERSION = 1;

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

	public static Offer serverOffer() {
		return new Offer(MIN_VERSION, CURRENT_VERSION, REQUIRED_CAPABILITIES, 0L);
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

	/** Standalone M3 offer; the live server does not use it until material gameplay is enabled. */
	public static Offer materialServerOffer() {
		return new Offer(MATERIAL_SNAPSHOT_VERSION, MATERIAL_SNAPSHOT_VERSION,
				REQUIRED_CAPABILITIES | CAP_MATERIAL_SNAPSHOT, 0L);
	}

	public static Offer chemicalServerOffer() {
		return new Offer(CHEMICAL_SNAPSHOT_VERSION, CHEMICAL_SNAPSHOT_VERSION,
				REQUIRED_CAPABILITIES | CAP_MATERIAL_SNAPSHOT | CAP_CHEMICAL_SNAPSHOT, 0L);
	}

	public static Offer granularServerOffer(boolean chemistryEnabled) {
		long capabilities = REQUIRED_CAPABILITIES | CAP_MATERIAL_SNAPSHOT | CAP_GRANULAR_SNAPSHOT;
		if (chemistryEnabled) capabilities |= CAP_CHEMICAL_SNAPSHOT;
		return new Offer(GRANULAR_SNAPSHOT_VERSION, GRANULAR_SNAPSHOT_VERSION, capabilities, 0L);
	}

	public static Offer thermalServerOffer(boolean chemistryEnabled, boolean granularEnabled) {
		long capabilities = REQUIRED_CAPABILITIES | CAP_MATERIAL_SNAPSHOT | CAP_THERMAL_SNAPSHOT;
		if (chemistryEnabled) capabilities |= CAP_CHEMICAL_SNAPSHOT;
		if (granularEnabled) capabilities |= CAP_GRANULAR_SNAPSHOT;
		return new Offer(THERMAL_SNAPSHOT_VERSION, THERMAL_SNAPSHOT_VERSION, capabilities, 0L);
	}

	public static Negotiation negotiate(Offer offer) {
		Objects.requireNonNull(offer, "offer");
		int lower = Math.max(MIN_VERSION, offer.minimumVersion());
		int upper = Math.min(MAX_VERSION, offer.maximumVersion());
		if (lower > upper) {
			return Negotiation.rejected("Real Water protocol ranges do not overlap: server "
					+ offer.minimumVersion() + "-" + offer.maximumVersion() + ", client "
					+ MIN_VERSION + "-" + MAX_VERSION);
		}
		long missing = offer.requiredCapabilities() & ~SUPPORTED_CAPABILITIES;
		if (missing != 0L) {
			return Negotiation.rejected("Real Water client is missing required capabilities 0x"
					+ Long.toHexString(missing));
		}
		long selectedCapabilities = SUPPORTED_CAPABILITIES
				& (offer.requiredCapabilities() | offer.optionalCapabilities());
		return Negotiation.accepted(upper, selectedCapabilities);
	}

	public static Negotiation validateResponse(Offer offer, FluidProtocolResponsePayload response) {
		Objects.requireNonNull(offer, "offer");
		Objects.requireNonNull(response, "response");
		if (!response.accepted()) return Negotiation.rejected(response.failure());
		if (response.selectedVersion() < offer.minimumVersion() || response.selectedVersion() > offer.maximumVersion()
				|| response.selectedVersion() < MIN_VERSION || response.selectedVersion() > MAX_VERSION) {
			return Negotiation.rejected("Client selected unsupported Real Water protocol " + response.selectedVersion());
		}
		long missing = offer.requiredCapabilities() & ~response.capabilities();
		if (missing != 0L) {
			return Negotiation.rejected("Client omitted required Real Water capabilities 0x" + Long.toHexString(missing));
		}
		long advertised = offer.requiredCapabilities() | offer.optionalCapabilities();
		if ((response.capabilities() & ~advertised) != 0L) {
			return Negotiation.rejected("Client selected capabilities not offered by the server");
		}
		if ((response.capabilities() & ~SUPPORTED_CAPABILITIES) != 0L) {
			return Negotiation.rejected("Client selected capabilities unsupported by this server");
		}
		return Negotiation.accepted(response.selectedVersion(), response.capabilities());
	}

	public record Offer(int minimumVersion, int maximumVersion, long requiredCapabilities, long optionalCapabilities) {
		public Offer {
			if (minimumVersion <= 0 || maximumVersion < minimumVersion) {
				throw new IllegalArgumentException("Invalid protocol version range");
			}
			if ((requiredCapabilities & optionalCapabilities) != 0L) {
				throw new IllegalArgumentException("Required and optional capabilities must be disjoint");
			}
		}
	}

	public record Negotiation(boolean accepted, int selectedVersion, long capabilities, String failure) {
		public Negotiation {
			if (accepted && (selectedVersion <= 0 || failure != null)) {
				throw new IllegalArgumentException("Accepted negotiation must select a version without failure");
			}
			if (!accepted && (selectedVersion != 0 || capabilities != 0L || failure == null || failure.isBlank())) {
				throw new IllegalArgumentException("Rejected negotiation must contain only a readable failure");
			}
		}

		private static Negotiation accepted(int selectedVersion, long capabilities) {
			return new Negotiation(true, selectedVersion, capabilities, null);
		}

		private static Negotiation rejected(String failure) {
			return new Negotiation(false, 0, 0L, Objects.requireNonNullElse(failure, "incompatible protocol"));
		}
	}
}
