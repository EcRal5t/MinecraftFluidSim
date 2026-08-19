package com.example.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Protocol negotiation, codec compatibility and stale-snapshot rejection matrix. */
public final class FluidNetworkProtocolValidation {
	private static final Identifier DIMENSION = Identifier.fromNamespaceAndPath("realwater", "protocol_test");
	private static final UUID REGION = new UUID(17L, 31L);

	private FluidNetworkProtocolValidation() { }

	public static void main(String[] args) {
		validateNegotiationMatrix();
		validateConfigurationCodecs();
		validateSnapshotCodecAndOptionalSections();
		validateGenerationAndSequenceOrdering();
		validateMaterialSnapshotV2();
		System.out.println("Fluid network protocol validation passed");
	}

	private static void validateNegotiationMatrix() {
		FluidProtocol.Offer offer = FluidProtocol.serverOffer();
		FluidProtocol.Negotiation accepted = FluidProtocol.negotiate(offer);
		require(accepted.accepted() && accepted.selectedVersion() == FluidProtocol.CURRENT_VERSION
				&& accepted.capabilities() == FluidProtocol.REQUIRED_CAPABILITIES,
				"matching protocol/capability offer was not selected exactly");

		FluidProtocol.Negotiation noVersion = FluidProtocol.negotiate(new FluidProtocol.Offer(3, 4, 0L, 0L));
		require(!noVersion.accepted() && noVersion.failure().contains("do not overlap"),
				"non-overlapping version range was not rejected readably");
		long unknown = 1L << 40;
		FluidProtocol.Negotiation missingRequired = FluidProtocol.negotiate(
				new FluidProtocol.Offer(1, 1, unknown, 0L)
		);
		require(!missingRequired.accepted() && missingRequired.failure().contains("0x"),
				"unknown required capability was accepted");
		FluidProtocol.Negotiation ignoredOptional = FluidProtocol.negotiate(
				new FluidProtocol.Offer(1, 1, FluidProtocol.REQUIRED_CAPABILITIES, unknown)
		);
		require(ignoredOptional.accepted() && (ignoredOptional.capabilities() & unknown) == 0L,
				"unknown optional capability was not safely ignored");

		FluidProtocol.Negotiation forgedVersion = FluidProtocol.validateResponse(
				offer, new FluidProtocolResponsePayload(true, 9, FluidProtocol.REQUIRED_CAPABILITIES, "")
		);
		require(!forgedVersion.accepted(), "client-selected out-of-range protocol was accepted");
		FluidProtocol.Negotiation missingResponseCapability = FluidProtocol.validateResponse(
				offer, new FluidProtocolResponsePayload(true, 1, FluidProtocol.CAP_REGION_IDENTITY, "")
		);
		require(!missingResponseCapability.accepted(), "response omitted a required capability");
		FluidProtocol.Offer futureOptionalOffer = new FluidProtocol.Offer(
				1, 1, FluidProtocol.REQUIRED_CAPABILITIES, unknown
		);
		FluidProtocol.Negotiation forgedOptionalCapability = FluidProtocol.validateResponse(
				futureOptionalOffer,
				new FluidProtocolResponsePayload(true, 1, FluidProtocol.REQUIRED_CAPABILITIES | unknown, "")
		);
		require(!forgedOptionalCapability.accepted(), "client selected a capability unsupported by this server");
	}

	private static void validateConfigurationCodecs() {
		FluidProtocolOfferPayload offer = new FluidProtocolOfferPayload(FluidProtocol.serverOffer());
		FluidProtocolOfferPayload decodedOffer;
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidProtocolOfferPayload.CODEC.encode(buffer, offer);
			decodedOffer = FluidProtocolOfferPayload.CODEC.decode(buffer);
		} finally {
			buffer.release();
		}
		require(decodedOffer.equals(offer), "configuration offer codec changed fields");

		FluidProtocolResponsePayload response = FluidProtocolResponsePayload.from(FluidProtocol.negotiate(offer.offer()));
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidProtocolResponsePayload.CODEC.encode(buffer, response);
			require(FluidProtocolResponsePayload.CODEC.decode(buffer).equals(response),
					"configuration response codec changed fields");
		} finally {
			buffer.release();
		}
	}

	private static void validateSnapshotCodecAndOptionalSections() {
		FluidSnapshotPayload payload = snapshot(2L, 7L, FluidProtocol.PURE_WATER_PALETTE_VERSION,
				List.of(new FluidSnapshotPayload.Extension(77, new byte[] {9, 8, 7})));
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		FluidSnapshotPayload decoded;
		try {
			FluidSnapshotPayload.CODEC.encode(buffer, payload);
			decoded = FluidSnapshotPayload.CODEC.decode(buffer);
		} finally {
			buffer.release();
		}
		require(decoded.protocolVersion() == payload.protocolVersion() && decoded.kind() == payload.kind()
				&& decoded.dimension().equals(DIMENSION) && decoded.regionId().equals(REGION)
				&& decoded.generation() == 2L && decoded.sequence() == 7L
				&& decoded.paletteVersion() == FluidProtocol.PURE_WATER_PALETTE_VERSION,
				"snapshot identity/version header did not round-trip");
		require(Arrays.equals(decoded.volume(), payload.volume()) && Arrays.equals(decoded.markers(), payload.markers())
				&& Arrays.equals(decoded.solids(), payload.solids()), "snapshot reconstruction arrays changed in codec");
		require(decoded.extensions().size() == 1 && decoded.extensions().getFirst().type() == 77
				&& Arrays.equals(decoded.extensions().getFirst().data(), new byte[] {9, 8, 7}),
				"unknown length-delimited optional section was not skippable");

		FluidSnapshotPayload removal = FluidSnapshotPayload.removeRegion(DIMENSION, REGION, 2L, 8L);
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidSnapshotPayload.CODEC.encode(buffer, removal);
			require(FluidSnapshotPayload.CODEC.decode(buffer).removesRegion(), "region removal codec changed kind");
		} finally {
			buffer.release();
		}
	}

	private static void validateGenerationAndSequenceOrdering() {
		FluidSnapshotLedger ledger = new FluidSnapshotLedger();
		require(ledger.evaluate(snapshot(1L, 1L), 1) == FluidSnapshotLedger.Decision.ACCEPT_SNAPSHOT,
				"first snapshot was rejected");
		require(ledger.evaluate(snapshot(1L, 1L), 1) == FluidSnapshotLedger.Decision.IGNORE_STALE,
				"duplicate sequence was accepted");
		require(ledger.evaluate(snapshot(1L, 2L), 1) == FluidSnapshotLedger.Decision.ACCEPT_SNAPSHOT,
				"newer sequence was rejected");
		require(ledger.evaluate(snapshot(2L, 1L), 1) == FluidSnapshotLedger.Decision.ACCEPT_SNAPSHOT,
				"new generation did not reset sequence ordering");
		require(ledger.evaluate(snapshot(1L, 99L), 1) == FluidSnapshotLedger.Decision.IGNORE_STALE,
				"older generation overrode the current state");
		require(ledger.evaluate(FluidSnapshotPayload.removeRegion(DIMENSION, REGION, 2L, 2L), 1)
				== FluidSnapshotLedger.Decision.REMOVE_REGION, "ordered removal was rejected");
		require(ledger.evaluate(snapshot(2L, 1L), 1) == FluidSnapshotLedger.Decision.IGNORE_STALE,
				"removal tombstone allowed delayed resurrection");
		require(ledger.evaluate(snapshot(3L, 1L), 1) == FluidSnapshotLedger.Decision.ACCEPT_SNAPSHOT,
				"new generation could not replace removal tombstone");
		require(ledger.evaluate(snapshot(4L, 1L, 2, List.of()), 1)
				== FluidSnapshotLedger.Decision.REJECT_PALETTE, "unsupported palette was accepted");
		require(ledger.evaluate(snapshot(4L, 2L), 9) == FluidSnapshotLedger.Decision.REJECT_PROTOCOL,
				"snapshot bypassed negotiated protocol version");
		require(ledger.evaluate(FluidSnapshotPayload.clearAll(), 1) == FluidSnapshotLedger.Decision.CLEAR_ALL,
				"clear-all control was rejected");
	}

	private static void validateMaterialSnapshotV2() {
		FluidProtocol.Offer offer = FluidProtocol.materialServerOffer();
		FluidProtocol.Negotiation negotiation = FluidProtocol.negotiate(offer);
		require(negotiation.accepted() && negotiation.selectedVersion() == FluidProtocol.MATERIAL_SNAPSHOT_VERSION
				&& (negotiation.capabilities() & FluidProtocol.CAP_MATERIAL_SNAPSHOT) != 0L,
				"material v2 capability was not negotiated explicitly");
		FluidProtocol.Negotiation omittedCapability = FluidProtocol.validateResponse(
				offer, new FluidProtocolResponsePayload(true, FluidProtocol.MATERIAL_SNAPSHOT_VERSION,
						FluidProtocol.REQUIRED_CAPABILITIES, ""));
		require(!omittedCapability.accepted(), "material v2 response omitted the required material capability");

		FluidMaterialSnapshotPayload payload = materialSnapshot(4L, 9L, FluidProtocol.MATERIAL_PALETTE_VERSION);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		FluidMaterialSnapshotPayload decoded;
		try {
			FluidMaterialSnapshotPayload.CODEC.encode(buffer, payload);
			decoded = FluidMaterialSnapshotPayload.CODEC.decode(buffer);
		} finally {
			buffer.release();
		}
		require(decoded.protocolVersion() == 2 && decoded.generation() == 4L && decoded.sequence() == 9L
				&& decoded.materials().equals(payload.materials()) && decoded.phaseGroups().equals(payload.phaseGroups()),
				"material v2 identity/palette did not round-trip");
		require(Arrays.equals(decoded.totalVolume(), payload.totalVolume())
				&& Arrays.deepEquals(decoded.materialFractions(), payload.materialFractions())
				&& Arrays.deepEquals(decoded.phaseFractions(), payload.phaseFractions()),
				"material v2 reconstruction fields did not round-trip");
		require(decoded.extensions().size() == 1 && decoded.extensions().getFirst().type() == 91
				&& Arrays.equals(decoded.extensions().getFirst().data(), new byte[] {4, 5, 6}),
				"material v2 unknown optional section was not length-delimited");

		FluidMaterialSnapshotLedger ledger = new FluidMaterialSnapshotLedger();
		require(ledger.evaluate(materialSnapshot(1L, 1L, 1), negotiation)
				== FluidMaterialSnapshotLedger.Decision.ACCEPT_SNAPSHOT, "first material snapshot was rejected");
		require(ledger.evaluate(materialSnapshot(1L, 1L, 1), negotiation)
				== FluidMaterialSnapshotLedger.Decision.IGNORE_STALE, "duplicate material sequence was accepted");
		require(ledger.evaluate(FluidMaterialSnapshotPayload.removeRegion(DIMENSION, REGION, 1L, 2L), negotiation)
				== FluidMaterialSnapshotLedger.Decision.REMOVE_REGION, "material tombstone was rejected");
		require(ledger.evaluate(materialSnapshot(1L, 1L, 1), negotiation)
				== FluidMaterialSnapshotLedger.Decision.IGNORE_STALE, "material tombstone allowed delayed resurrection");
		require(ledger.evaluate(materialSnapshot(2L, 1L, 2), negotiation)
				== FluidMaterialSnapshotLedger.Decision.REJECT_PALETTE, "unknown material palette was accepted");
		require(ledger.evaluate(materialSnapshot(2L, 2L, 1), omittedCapability)
				== FluidMaterialSnapshotLedger.Decision.REJECT_PROTOCOL, "material snapshot bypassed capability negotiation");
		require(ledger.evaluate(FluidMaterialSnapshotPayload.clearAll(), negotiation)
				== FluidMaterialSnapshotLedger.Decision.CLEAR_ALL, "material clear-all was rejected");
	}

	private static FluidSnapshotPayload snapshot(long generation, long sequence) {
		return snapshot(generation, sequence, FluidProtocol.PURE_WATER_PALETTE_VERSION, List.of());
	}

	private static FluidSnapshotPayload snapshot(
			long generation,
			long sequence,
			int paletteVersion,
			List<FluidSnapshotPayload.Extension> extensions
	) {
		return new FluidSnapshotPayload(
				FluidProtocol.CURRENT_VERSION, FluidSnapshotPayload.Kind.SNAPSHOT, DIMENSION, REGION,
				generation, sequence, paletteVersion, new BlockPos(2, 3, 4), 0.5F, 2, 2, 2,
				new byte[] {1, 2, 3, 4, 5, 6, 7, 8},
				new byte[] {1, 0, 1, 0, 1, 0, 1, 0},
				new byte[] {0, 0, 0, 1, 0, 0, 0, 0}, false, extensions
		);
	}

	private static FluidMaterialSnapshotPayload materialSnapshot(long generation, long sequence, int paletteVersion) {
		byte[] markers = {1, 1, 1, 1, 1, 1, 1, 1};
		byte[][] fractions = {
				{(byte) 255, (byte) 200, (byte) 128, 0, (byte) 255, (byte) 100, 10, 1},
				{0, 55, 127, (byte) 255, 0, (byte) 155, (byte) 245, (byte) 254}
		};
		return new FluidMaterialSnapshotPayload(
				FluidProtocol.MATERIAL_SNAPSHOT_VERSION, FluidMaterialSnapshotPayload.Kind.SNAPSHOT,
				DIMENSION, REGION, generation, sequence, paletteVersion, new BlockPos(2, 3, 4), 0.5F, 2, 2, 2,
				List.of(
						new FluidMaterialSnapshotPayload.MaterialEntry("realwater:water", 1_000.0, "realwater:aqueous", 0xff197ae8),
						new FluidMaterialSnapshotPayload.MaterialEntry("realwater:oil", 800.0, "realwater:oil", 0xffc89a32)),
				List.of("realwater:aqueous", "realwater:oil"),
				new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 220, (byte) 220, (byte) 180, (byte) 120},
				markers, new byte[] {0, 0, 0, 1, 0, 0, 0, 0}, fractions, fractions, false,
				List.of(new FluidMaterialSnapshotPayload.Extension(91, new byte[] {4, 5, 6}))
		);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
