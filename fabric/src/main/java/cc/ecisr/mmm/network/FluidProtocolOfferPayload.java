package cc.ecisr.mmm.network;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Server-to-client configuration-phase protocol and capability offer. */
public record FluidProtocolOfferPayload(
		FluidProtocol.Layout layout,
		long requiredCapabilities,
		long optionalCapabilities,
		String worldProfileFingerprint,
		String regionLayoutId,
		List<String> worldCapabilities
) implements CustomPacketPayload {
	public static final Type<FluidProtocolOfferPayload> TYPE = new Type<>(MmmMod.id("protocol_offer"));
	public static final StreamCodec<FriendlyByteBuf, FluidProtocolOfferPayload> CODEC = StreamCodec.of(
			FluidProtocolOfferPayload::encode,
			FluidProtocolOfferPayload::decode
	);

	public FluidProtocolOfferPayload {
		new FluidProtocol.Offer(layout, requiredCapabilities, optionalCapabilities);
		if (worldProfileFingerprint == null || !worldProfileFingerprint.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid world capability profile fingerprint");
		}
		if (regionLayoutId == null || !regionLayoutId.equals(layout.name().toLowerCase(Locale.ROOT))) {
			throw new IllegalArgumentException("Protocol and world profile layouts disagree");
		}
		ArrayList<String> canonical = new ArrayList<>(worldCapabilities == null ? List.of() : worldCapabilities);
		canonical.forEach(WorldCapability::byStableId);
		canonical.sort(Comparator.naturalOrder());
		if (canonical.stream().distinct().count() != canonical.size()) {
			throw new IllegalArgumentException("Duplicate world capability in protocol offer");
		}
		worldCapabilities = List.copyOf(canonical);
	}

	public FluidProtocolOfferPayload(FluidProtocol.Offer offer, WorldCapabilityProfile profile) {
		this(offer.layout(), offer.requiredCapabilities(), offer.optionalCapabilities(), profile.profileFingerprint(),
				profile.regionLayoutId(), profile.capabilities().stream().map(WorldCapability::stableId).toList());
	}

	public FluidProtocol.Offer offer() {
		return new FluidProtocol.Offer(layout, requiredCapabilities, optionalCapabilities);
	}

	@Override
	public Type<FluidProtocolOfferPayload> type() {
		return TYPE;
	}

	private static void encode(FriendlyByteBuf buffer, FluidProtocolOfferPayload payload) {
		buffer.writeEnum(payload.layout);
		buffer.writeVarLong(payload.requiredCapabilities);
		buffer.writeVarLong(payload.optionalCapabilities);
		buffer.writeUtf(payload.worldProfileFingerprint, 64);
		buffer.writeUtf(payload.regionLayoutId, 16);
		buffer.writeVarInt(payload.worldCapabilities.size());
		for (String capability : payload.worldCapabilities) buffer.writeUtf(capability, 64);
	}

	private static FluidProtocolOfferPayload decode(FriendlyByteBuf buffer) {
		FluidProtocol.Layout layout = buffer.readEnum(FluidProtocol.Layout.class);
		long required = buffer.readVarLong(), optional = buffer.readVarLong();
		String profile = buffer.readUtf(64), regionLayout = buffer.readUtf(16);
		int count = buffer.readVarInt();
		if (count < 0 || count > WorldCapability.values().length) {
			throw new IllegalArgumentException("Invalid world capability count in protocol offer");
		}
		ArrayList<String> capabilities = new ArrayList<>(count);
		for (int index = 0; index < count; index++) capabilities.add(buffer.readUtf(64));
		return new FluidProtocolOfferPayload(layout, required, optional, profile, regionLayout, capabilities);
	}
}
