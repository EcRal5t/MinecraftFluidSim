package cc.ecisr.mmm.network;

import cc.ecisr.mmm.fluid.FluidMaterialRenderSnapshot;
import cc.ecisr.mmm.fluid.FluidMaterialSurfaceMesher;
import cc.ecisr.mmm.granular.GranularDisplaySnapshot;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Protocol negotiation, codec compatibility and stale-snapshot rejection matrix. */
public final class FluidNetworkProtocolValidation {
	private static final Identifier DIMENSION = Identifier.fromNamespaceAndPath("mmm", "protocol_test");
	private static final UUID REGION = new UUID(17L, 31L);

	private FluidNetworkProtocolValidation() { }

	public static void main(String[] args) {
		validateNegotiationMatrix();
		validateConfigurationCodecs();
		validateSnapshotCodecAndOptionalSections();
		validateGenerationAndSequenceOrdering();
		validateMaterialSnapshotV2();
		validateChemicalSnapshotV3();
		validateGranularSnapshotV4();
		validateThermalSnapshotV5();
		System.out.println("Fluid network protocol validation passed");
	}

	private static void validateNegotiationMatrix() {
		FluidProtocol.Offer offer = FluidProtocol.serverOffer();
		require(FluidProtocol.serverOffer(false).equals(offer)
				&& FluidProtocol.serverOffer(true).equals(FluidProtocol.materialServerOffer())
				&& FluidProtocol.serverOffer(true, true).equals(FluidProtocol.chemicalServerOffer())
				&& FluidProtocol.serverOffer(true, false, true).equals(FluidProtocol.granularServerOffer(false))
				&& FluidProtocol.serverOffer(true, true, true).equals(FluidProtocol.granularServerOffer(true))
				&& FluidProtocol.serverOffer(true, true, true, true).equals(
						FluidProtocol.thermalServerOffer(true, true)),
				"feature gates did not select strict v1/v2/v3/v4/v5 server offers");
		FluidProtocol.Negotiation accepted = FluidProtocol.negotiate(offer);
		require(accepted.accepted() && accepted.selectedVersion() == FluidProtocol.CURRENT_VERSION
				&& accepted.capabilities() == FluidProtocol.REQUIRED_CAPABILITIES,
				"matching protocol/capability offer was not selected exactly");

		FluidProtocol.Negotiation noVersion = FluidProtocol.negotiate(new FluidProtocol.Offer(6, 7, 0L, 0L));
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

	private static void validateThermalSnapshotV5() {
		FluidProtocol.Offer offer = FluidProtocol.thermalServerOffer(false, false);
		FluidProtocol.Negotiation negotiation = FluidProtocol.negotiate(offer);
		long required = FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_THERMAL_SNAPSHOT;
		require(negotiation.accepted() && negotiation.selectedVersion() == FluidProtocol.THERMAL_SNAPSHOT_VERSION
				&& (negotiation.capabilities() & required) == required,
				"thermal v5 did not negotiate material+thermal capabilities strictly");
		FluidProtocol.Negotiation omitted = FluidProtocol.validateResponse(offer,
				new FluidProtocolResponsePayload(true, 5,
						FluidProtocol.REQUIRED_CAPABILITIES | FluidProtocol.CAP_MATERIAL_SNAPSHOT, ""));
		require(!omitted.accepted(), "thermal v5 response omitted the thermal capability");

		FluidThermalSnapshotPayload payload = thermalSnapshot(11L, 17L, "e".repeat(64));
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		FluidThermalSnapshotPayload decoded;
		try {
			FluidThermalSnapshotPayload.CODEC.encode(buffer, payload);
			decoded = FluidThermalSnapshotPayload.CODEC.decode(buffer);
		} finally { buffer.release(); }
		require(decoded.material().generation() == 11L && decoded.material().sequence() == 17L
				&& decoded.registryFingerprint().equals(payload.registryFingerprint())
				&& Arrays.equals(decoded.cellTemperatureQ(), payload.cellTemperatureQ())
				&& Arrays.equals(decoded.coherentFraction(), payload.coherentFraction())
				&& Arrays.equals(decoded.debugFlags(), payload.debugFlags())
				&& decoded.gasNodes().equals(payload.gasNodes()),
				"thermal v5 display payload did not round-trip");

		FluidThermalSnapshotLedger ledger = new FluidThermalSnapshotLedger();
		require(ledger.evaluate(thermalSnapshot(1L, 1L, "e".repeat(64)), negotiation)
				== FluidThermalSnapshotLedger.Decision.ACCEPT_SNAPSHOT, "first thermal snapshot was rejected");
		require(ledger.evaluate(thermalSnapshot(1L, 1L, "e".repeat(64)), negotiation)
				== FluidThermalSnapshotLedger.Decision.IGNORE_STALE, "duplicate thermal sequence was accepted");
		require(ledger.evaluate(thermalSnapshot(1L, 2L, "f".repeat(64)), negotiation)
				== FluidThermalSnapshotLedger.Decision.REJECT_REGISTRY, "foreign thermal fingerprint was accepted");
		require(ledger.evaluate(FluidThermalSnapshotPayload.removeRegion(
				FluidMaterialSnapshotPayload.removeRegion(DIMENSION, REGION, 1L, 3L)), negotiation)
				== FluidThermalSnapshotLedger.Decision.REMOVE_REGION, "thermal tombstone was rejected");
		require(ledger.evaluate(FluidThermalSnapshotPayload.clearAll(), negotiation)
				== FluidThermalSnapshotLedger.Decision.CLEAR_ALL, "thermal clear-all was rejected");

		FluidProtocol.Negotiation granularNegotiation = FluidProtocol.negotiate(
				FluidProtocol.thermalServerOffer(true, true));
		FluidGranularSnapshotPayload granular = granularSnapshot(2L, 1L, "c".repeat(64), true);
		FluidThermalSnapshotPayload composed = thermalSnapshot(
				granular.material(), 2L, 1L, "e".repeat(64), null, granular);
		require(new FluidThermalSnapshotLedger().evaluate(composed, granularNegotiation)
				== FluidThermalSnapshotLedger.Decision.ACCEPT_SNAPSHOT,
				"thermal v5 rejected composable v4 granular/v3 chemical display layers");

		FluidGranularSnapshotPayload defaultGranular = defaultSizedGranularSnapshot();
		int cells = 9_216; short[] temperatures = new short[cells];
		Arrays.fill(temperatures, (short) (300 * 16));
		FluidThermalSnapshotPayload defaultSized = new FluidThermalSnapshotPayload(5,
				defaultGranular.material(), 1, "e".repeat(64), temperatures, new byte[cells], new byte[cells],
				List.of(new FluidThermalSnapshotPayload.GasNodeDisplay(1, 300 * 16, 0)), null, defaultGranular);
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidThermalSnapshotPayload.CODEC.encode(buffer, defaultSized);
			require(buffer.writerIndex() <= 512 * 1024,
					"default 9,216-cell composed thermal snapshot exceeded 512 KiB: " + buffer.writerIndex());
		} finally { buffer.release(); }
	}

	private static void validateGranularSnapshotV4() {
		FluidProtocol.Offer offer = FluidProtocol.granularServerOffer(false);
		FluidProtocol.Negotiation negotiation = FluidProtocol.negotiate(offer);
		long required = FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_GRANULAR_SNAPSHOT;
		require(negotiation.accepted() && negotiation.selectedVersion() == FluidProtocol.GRANULAR_SNAPSHOT_VERSION
				&& (negotiation.capabilities() & required) == required
				&& (negotiation.capabilities() & FluidProtocol.CAP_CHEMICAL_SNAPSHOT) == 0L,
				"granular v4 did not negotiate material+granular capabilities strictly");
		FluidProtocol.Negotiation omitted = FluidProtocol.validateResponse(offer,
				new FluidProtocolResponsePayload(true, 4,
						FluidProtocol.REQUIRED_CAPABILITIES | FluidProtocol.CAP_MATERIAL_SNAPSHOT, ""));
		require(!omitted.accepted(), "granular v4 response omitted the granular capability");

		FluidGranularSnapshotPayload payload = granularSnapshot(9L, 13L, "c".repeat(64), false);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer()); FluidGranularSnapshotPayload decoded;
		try {
			FluidGranularSnapshotPayload.CODEC.encode(buffer, payload);
			decoded = FluidGranularSnapshotPayload.CODEC.decode(buffer);
		} finally { buffer.release(); }
		require(decoded.material().generation() == 9L && decoded.material().sequence() == 13L
				&& decoded.registryFingerprint().equals(payload.registryFingerprint())
				&& decoded.granularMaterials().equals(payload.granularMaterials())
				&& Arrays.equals(decoded.depositBulkFill(), payload.depositBulkFill())
				&& Arrays.equals(decoded.depositPorosity(), payload.depositPorosity())
				&& Arrays.equals(decoded.depositTopMaterial(), payload.depositTopMaterial())
				&& decoded.activeParcels().equals(payload.activeParcels()) && decoded.chemical() == null,
				"granular v4 display payload did not round-trip");

		FluidGranularSnapshotLedger ledger = new FluidGranularSnapshotLedger();
		require(ledger.evaluate(granularSnapshot(1L, 1L, "c".repeat(64), false), negotiation)
				== FluidGranularSnapshotLedger.Decision.ACCEPT_SNAPSHOT, "first granular snapshot was rejected");
		require(ledger.evaluate(granularSnapshot(1L, 1L, "c".repeat(64), false), negotiation)
				== FluidGranularSnapshotLedger.Decision.IGNORE_STALE, "duplicate granular sequence was accepted");
		require(ledger.evaluate(granularSnapshot(1L, 2L, "d".repeat(64), false), negotiation)
				== FluidGranularSnapshotLedger.Decision.REJECT_REGISTRY, "foreign granular fingerprint was accepted");
		require(ledger.evaluate(FluidGranularSnapshotPayload.removeRegion(
				FluidMaterialSnapshotPayload.removeRegion(DIMENSION, REGION, 1L, 3L)), negotiation)
				== FluidGranularSnapshotLedger.Decision.REMOVE_REGION, "granular tombstone was rejected");
		require(ledger.evaluate(granularSnapshot(1L, 2L, "c".repeat(64), false), negotiation)
				== FluidGranularSnapshotLedger.Decision.IGNORE_STALE, "granular tombstone allowed resurrection");
		require(ledger.evaluate(FluidGranularSnapshotPayload.clearAll(), negotiation)
				== FluidGranularSnapshotLedger.Decision.CLEAR_ALL, "granular clear-all was rejected");

		FluidProtocol.Negotiation chemicalNegotiation = FluidProtocol.negotiate(FluidProtocol.granularServerOffer(true));
		require((chemicalNegotiation.capabilities() & FluidProtocol.CAP_CHEMICAL_SNAPSHOT) != 0L,
				"chemical granular v4 omitted the chemical display capability");
		FluidGranularSnapshotLedger chemicalLedger = new FluidGranularSnapshotLedger();
		require(chemicalLedger.evaluate(granularSnapshot(1L, 1L, "c".repeat(64), true), chemicalNegotiation)
				== FluidGranularSnapshotLedger.Decision.ACCEPT_SNAPSHOT,
				"granular v4 chemical layer was rejected");
		require(new FluidGranularSnapshotLedger().evaluate(
				granularSnapshot(1L, 1L, "c".repeat(64), false), chemicalNegotiation)
				== FluidGranularSnapshotLedger.Decision.REJECT_PROTOCOL,
				"chemical granular negotiation accepted an omitted chemical layer");

		FluidGranularSnapshotPayload defaultSized = defaultSizedGranularSnapshot();
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidGranularSnapshotPayload.CODEC.encode(buffer, defaultSized);
			require(buffer.writerIndex() <= 512 * 1024,
					"default 9,216-cell granular snapshot exceeded 512 KiB: " + buffer.writerIndex());
		} finally { buffer.release(); }
	}

	private static void validateChemicalSnapshotV3() {
		FluidProtocol.Offer offer = FluidProtocol.chemicalServerOffer();
		FluidProtocol.Negotiation negotiation = FluidProtocol.negotiate(offer);
		long required = FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_CHEMICAL_SNAPSHOT;
		require(negotiation.accepted() && negotiation.selectedVersion() == FluidProtocol.CHEMICAL_SNAPSHOT_VERSION
				&& (negotiation.capabilities() & required) == required,
				"chemical v3 did not negotiate material+chemistry capabilities strictly");
		FluidProtocol.Negotiation omitted = FluidProtocol.validateResponse(offer,
				new FluidProtocolResponsePayload(true, 3,
						FluidProtocol.REQUIRED_CAPABILITIES | FluidProtocol.CAP_MATERIAL_SNAPSHOT, ""));
		require(!omitted.accepted(), "chemical v3 response omitted the chemistry capability");

		FluidChemicalSnapshotPayload payload = chemicalSnapshot(7L, 11L, "a".repeat(64));
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		FluidChemicalSnapshotPayload decoded;
		try {
			FluidChemicalSnapshotPayload.CODEC.encode(buffer, payload);
			decoded = FluidChemicalSnapshotPayload.CODEC.decode(buffer);
		} finally { buffer.release(); }
		require(decoded.material().generation() == 7L && decoded.material().sequence() == 11L
				&& decoded.registryFingerprint().equals(payload.registryFingerprint())
				&& decoded.species().equals(payload.species())
				&& Arrays.equals(decoded.topSpecies(), payload.topSpecies())
				&& Arrays.equals(decoded.topWeights(), payload.topWeights())
				&& Arrays.equals(decoded.residualWeights(), payload.residualWeights())
				&& Arrays.equals(decoded.ph(), payload.ph())
				&& Arrays.equals(decoded.indicatorFraction(), payload.indicatorFraction()),
				"chemical v3 display payload did not round-trip");
		for (int cell = 0; cell < decoded.ph().length; cell++) {
			int sum = Byte.toUnsignedInt(decoded.residualWeights()[cell]);
			for (int slot = 0; slot < 4; slot++) sum += Byte.toUnsignedInt(decoded.topWeights()[cell * 4 + slot]);
			require(sum == 255, "chemical Top-4 quantization did not close");
		}

		FluidChemicalSnapshotLedger ledger = new FluidChemicalSnapshotLedger();
		require(ledger.evaluate(chemicalSnapshot(1L, 1L, "a".repeat(64)), negotiation)
				== FluidChemicalSnapshotLedger.Decision.ACCEPT_SNAPSHOT, "first chemical snapshot was rejected");
		require(ledger.evaluate(chemicalSnapshot(1L, 1L, "a".repeat(64)), negotiation)
				== FluidChemicalSnapshotLedger.Decision.IGNORE_STALE, "duplicate chemical sequence was accepted");
		require(ledger.evaluate(chemicalSnapshot(1L, 2L, "b".repeat(64)), negotiation)
				== FluidChemicalSnapshotLedger.Decision.REJECT_REGISTRY, "foreign chemical fingerprint was accepted");
		require(ledger.evaluate(FluidChemicalSnapshotPayload.removeRegion(
				FluidMaterialSnapshotPayload.removeRegion(DIMENSION, REGION, 1L, 3L)), negotiation)
				== FluidChemicalSnapshotLedger.Decision.REMOVE_REGION, "chemical tombstone was rejected");
		require(ledger.evaluate(chemicalSnapshot(1L, 2L, "a".repeat(64)), negotiation)
				== FluidChemicalSnapshotLedger.Decision.IGNORE_STALE, "chemical tombstone allowed resurrection");
		require(ledger.evaluate(FluidChemicalSnapshotPayload.clearAll(), negotiation)
				== FluidChemicalSnapshotLedger.Decision.CLEAR_ALL, "chemical clear-all was rejected");

		FluidChemicalSnapshotPayload defaultSized = defaultSizedChemicalSnapshot();
		buffer = new FriendlyByteBuf(Unpooled.buffer());
		try {
			FluidChemicalSnapshotPayload.CODEC.encode(buffer, defaultSized);
			require(buffer.writerIndex() <= 512 * 1024,
					"default 9,216-cell chemical snapshot exceeded 512 KiB: " + buffer.writerIndex());
		} finally { buffer.release(); }
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
		FluidMaterialRenderSnapshot renderSnapshot = decoded.toRenderSnapshot();
		require(renderSnapshot.materials().size() == decoded.materials().size()
				&& Arrays.equals(renderSnapshot.copyTotalVolume(), decoded.totalVolume()),
				"material v2 payload did not rebuild CPU mesher fields");
		FluidMaterialSurfaceMesher.Mesh mesh = FluidMaterialSurfaceMesher.build(renderSnapshot);
		require(!mesh.outerTriangles().isEmpty() && !mesh.interfaceTriangles().isEmpty()
				&& mesh.overlappingCategoryTriangles() == 0,
				"material v2 payload lost its partitioned outer/internal surfaces");
		FluidMaterialSnapshotPayload generated = FluidMaterialSnapshotPayload.snapshot(
				DIMENSION, REGION, 5L, 1L, decoded.origin(), renderSnapshot, false);
		require(Arrays.equals(generated.totalVolume(), decoded.totalVolume())
				&& Arrays.deepEquals(generated.materialFractions(), decoded.materialFractions())
				&& Arrays.deepEquals(generated.phaseFractions(), decoded.phaseFractions()),
				"server material snapshot factory changed quantized reconstruction fields");

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
						new FluidMaterialSnapshotPayload.MaterialEntry("mmm:water", 1_000.0, "mmm:aqueous", 0xff197ae8),
						new FluidMaterialSnapshotPayload.MaterialEntry("mmm:oil", 800.0, "mmm:oil", 0xffc89a32)),
				List.of("mmm:aqueous", "mmm:oil"),
				new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255, (byte) 220, (byte) 220, (byte) 180, (byte) 120},
				markers, new byte[] {0, 0, 0, 1, 0, 0, 0, 0}, fractions, fractions, false,
				List.of(new FluidMaterialSnapshotPayload.Extension(91, new byte[] {4, 5, 6}))
		);
	}

	private static FluidChemicalSnapshotPayload chemicalSnapshot(long generation, long sequence, String fingerprint) {
		short[] top = new short[8 * 4]; Arrays.fill(top, (short) -1);
		byte[] weights = new byte[top.length];
		for (int cell = 0; cell < 8; cell++) { top[cell * 4] = 0; weights[cell * 4] = (byte) 255; }
		byte[] ph = new byte[8]; Arrays.fill(ph, (byte) 128);
		byte[] indicator = new byte[8]; indicator[0] = (byte) 255;
		return new FluidChemicalSnapshotPayload(3, materialSnapshot(generation, sequence, 1), fingerprint,
				List.of(new FluidChemicalSnapshotPayload.SpeciesEntry("mmm:h2o", 0xff197ae8)),
				top, weights, new byte[8], ph, indicator);
	}

	private static FluidThermalSnapshotPayload thermalSnapshot(long generation, long sequence, String fingerprint) {
		return thermalSnapshot(materialSnapshot(generation, sequence, 1), generation, sequence, fingerprint, null, null);
	}

	private static FluidThermalSnapshotPayload thermalSnapshot(
			FluidMaterialSnapshotPayload material, long generation, long sequence, String fingerprint,
			FluidChemicalSnapshotPayload chemical, FluidGranularSnapshotPayload granular
	) {
		short[] temperature = new short[8]; Arrays.fill(temperature, (short) (300 * 16));
		temperature[7] = (short) FluidThermalSnapshotPayload.EMPTY_TEMPERATURE_Q;
		byte[] coherent = new byte[8]; coherent[0] = (byte) 128;
		byte[] flags = new byte[8]; flags[1] = 1;
		return new FluidThermalSnapshotPayload(5, material, 1, fingerprint, temperature, coherent, flags,
				List.of(new FluidThermalSnapshotPayload.GasNodeDisplay(1, 310 * 16, -123)), chemical, granular);
	}

	private static FluidChemicalSnapshotPayload defaultSizedChemicalSnapshot() {
		int cells = 9_216;
		byte[] full = new byte[cells]; Arrays.fill(full, (byte) 255);
		byte[] markers = new byte[cells]; Arrays.fill(markers, (byte) 1);
		FluidMaterialSnapshotPayload material = new FluidMaterialSnapshotPayload(2,
				FluidMaterialSnapshotPayload.Kind.SNAPSHOT, DIMENSION, REGION, 1L, 1L, 1, BlockPos.ZERO,
				0.5F, 24, 16, 24, List.of(new FluidMaterialSnapshotPayload.MaterialEntry(
				"mmm:water", 1_000.0, "mmm:aqueous", 0xff197ae8)),
				List.of("mmm:aqueous"), full, markers, new byte[cells],
				new byte[][] {full}, new byte[][] {full}, false, List.of());
		short[] top = new short[cells * 4]; Arrays.fill(top, (short) -1);
		byte[] weights = new byte[cells * 4];
		for (int cell = 0; cell < cells; cell++) { top[cell * 4] = 0; weights[cell * 4] = (byte) 255; }
		byte[] ph = new byte[cells]; Arrays.fill(ph, (byte) 128);
		return new FluidChemicalSnapshotPayload(3, material, "a".repeat(64),
				List.of(new FluidChemicalSnapshotPayload.SpeciesEntry("mmm:h2o", 0xff197ae8)),
				top, weights, new byte[cells], ph, new byte[cells]);
	}

	private static FluidGranularSnapshotPayload granularSnapshot(
			long generation, long sequence, String fingerprint, boolean withChemical
	) {
		byte[] bulk = {(byte) 160, 0, 0, 0, 0, 0, 0, 0};
		byte[] porosity = {(byte) 102, 0, 0, 0, 0, 0, 0, 0};
		short[] top = {0, -1, -1, -1, -1, -1, -1, -1};
		FluidChemicalSnapshotPayload chemical = withChemical
				? chemicalSnapshot(generation, sequence, "a".repeat(64)) : null;
		FluidGranularSnapshotPayload.ChemicalLayer layer = chemical == null ? null
				: new FluidGranularSnapshotPayload.ChemicalLayer(chemical.registryFingerprint(), chemical.species(),
				chemical.topSpecies(), chemical.topWeights(), chemical.residualWeights(), chemical.ph(),
				chemical.indicatorFraction());
		return new FluidGranularSnapshotPayload(4, materialSnapshot(generation, sequence, 1), 1, fingerprint,
				List.of(new GranularDisplaySnapshot.MaterialEntry("mmm:fine_sand", 0xffc2a065)),
				bulk, porosity, top, List.of(new GranularDisplaySnapshot.ParcelSample(12_000, 24_000,
				36_000, 32, 0)), layer);
	}

	private static FluidGranularSnapshotPayload defaultSizedGranularSnapshot() {
		FluidChemicalSnapshotPayload chemical = defaultSizedChemicalSnapshot(); int cells = 9_216;
		byte[] bulk = new byte[cells], porosity = new byte[cells]; short[] top = new short[cells];
		Arrays.fill(top, (short) -1); bulk[0] = (byte) 128; porosity[0] = (byte) 102; top[0] = 0;
		return new FluidGranularSnapshotPayload(4, chemical.material(), 1, "c".repeat(64),
				List.of(new GranularDisplaySnapshot.MaterialEntry("mmm:fine_sand", 0xffc2a065)),
				bulk, porosity, top, List.of(new GranularDisplaySnapshot.ParcelSample(1, 2, 3, 16, 0)),
				new FluidGranularSnapshotPayload.ChemicalLayer(chemical.registryFingerprint(), chemical.species(),
						chemical.topSpecies(), chemical.topWeights(), chemical.residualWeights(), chemical.ph(),
						chemical.indicatorFraction()));
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
