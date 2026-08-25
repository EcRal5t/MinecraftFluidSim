package cc.ecisr.mmm.network;

import cc.ecisr.mmm.MmmMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server-to-client configuration-phase protocol and capability offer. */
public record FluidProtocolOfferPayload(
		int minimumVersion,
		int maximumVersion,
		long requiredCapabilities,
		long optionalCapabilities
) implements CustomPacketPayload {
	public static final Type<FluidProtocolOfferPayload> TYPE = new Type<>(MmmMod.id("protocol_offer"));
	public static final StreamCodec<FriendlyByteBuf, FluidProtocolOfferPayload> CODEC = StreamCodec.of(
			FluidProtocolOfferPayload::encode,
			FluidProtocolOfferPayload::decode
	);

	public FluidProtocolOfferPayload {
		new FluidProtocol.Offer(minimumVersion, maximumVersion, requiredCapabilities, optionalCapabilities);
	}

	public FluidProtocolOfferPayload(FluidProtocol.Offer offer) {
		this(offer.minimumVersion(), offer.maximumVersion(), offer.requiredCapabilities(), offer.optionalCapabilities());
	}

	public FluidProtocol.Offer offer() {
		return new FluidProtocol.Offer(minimumVersion, maximumVersion, requiredCapabilities, optionalCapabilities);
	}

	@Override
	public Type<FluidProtocolOfferPayload> type() {
		return TYPE;
	}

	private static void encode(FriendlyByteBuf buffer, FluidProtocolOfferPayload payload) {
		buffer.writeVarInt(payload.minimumVersion);
		buffer.writeVarInt(payload.maximumVersion);
		buffer.writeVarLong(payload.requiredCapabilities);
		buffer.writeVarLong(payload.optionalCapabilities);
	}

	private static FluidProtocolOfferPayload decode(FriendlyByteBuf buffer) {
		return new FluidProtocolOfferPayload(
				buffer.readVarInt(), buffer.readVarInt(), buffer.readVarLong(), buffer.readVarLong()
		);
	}
}
