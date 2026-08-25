package cc.ecisr.mmm.network;

import cc.ecisr.mmm.MmmMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server-to-client configuration-phase protocol and capability offer. */
public record FluidProtocolOfferPayload(
		FluidProtocol.Layout layout,
		long requiredCapabilities,
		long optionalCapabilities
) implements CustomPacketPayload {
	public static final Type<FluidProtocolOfferPayload> TYPE = new Type<>(MmmMod.id("protocol_offer"));
	public static final StreamCodec<FriendlyByteBuf, FluidProtocolOfferPayload> CODEC = StreamCodec.of(
			FluidProtocolOfferPayload::encode,
			FluidProtocolOfferPayload::decode
	);

	public FluidProtocolOfferPayload {
		new FluidProtocol.Offer(layout, requiredCapabilities, optionalCapabilities);
	}

	public FluidProtocolOfferPayload(FluidProtocol.Offer offer) {
		this(offer.layout(), offer.requiredCapabilities(), offer.optionalCapabilities());
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
	}

	private static FluidProtocolOfferPayload decode(FriendlyByteBuf buffer) {
		return new FluidProtocolOfferPayload(buffer.readEnum(FluidProtocol.Layout.class),
				buffer.readVarLong(), buffer.readVarLong());
	}
}
