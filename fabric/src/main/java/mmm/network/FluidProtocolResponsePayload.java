package mmm.network;

import mmm.MmmMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Objects;

/** Client-to-server configuration-phase negotiation result. */
public record FluidProtocolResponsePayload(
		boolean accepted,
		FluidProtocol.Layout selectedLayout,
		long capabilities,
		String failure
) implements CustomPacketPayload {
	private static final int MAX_FAILURE_LENGTH = 512;

	public static final Type<FluidProtocolResponsePayload> TYPE = new Type<>(MmmMod.id("protocol_response"));
	public static final StreamCodec<FriendlyByteBuf, FluidProtocolResponsePayload> CODEC = StreamCodec.of(
			FluidProtocolResponsePayload::encode,
			FluidProtocolResponsePayload::decode
	);

	public FluidProtocolResponsePayload {
		failure = Objects.requireNonNullElse(failure, "");
		if (failure.length() > MAX_FAILURE_LENGTH) throw new IllegalArgumentException("Protocol failure text is too long");
		if (accepted && (selectedLayout == null || !failure.isEmpty())) {
			throw new IllegalArgumentException("Accepted protocol response must select a layout without failure text");
		}
		if (!accepted && (selectedLayout != null || capabilities != 0L || failure.isBlank())) {
			throw new IllegalArgumentException("Rejected protocol response must contain only a readable failure");
		}
	}

	public static FluidProtocolResponsePayload from(FluidProtocol.Negotiation negotiation) {
		return negotiation.accepted()
				? new FluidProtocolResponsePayload(true, negotiation.selectedLayout(), negotiation.capabilities(), "")
				: new FluidProtocolResponsePayload(false, null, 0L, negotiation.failure());
	}

	@Override
	public Type<FluidProtocolResponsePayload> type() {
		return TYPE;
	}

	private static void encode(FriendlyByteBuf buffer, FluidProtocolResponsePayload payload) {
		buffer.writeBoolean(payload.accepted);
		buffer.writeBoolean(payload.selectedLayout != null);
		if (payload.selectedLayout != null) buffer.writeEnum(payload.selectedLayout);
		buffer.writeVarLong(payload.capabilities);
		buffer.writeUtf(payload.failure, MAX_FAILURE_LENGTH);
	}

	private static FluidProtocolResponsePayload decode(FriendlyByteBuf buffer) {
		boolean accepted = buffer.readBoolean();
		FluidProtocol.Layout layout = buffer.readBoolean() ? buffer.readEnum(FluidProtocol.Layout.class) : null;
		return new FluidProtocolResponsePayload(accepted, layout, buffer.readVarLong(), buffer.readUtf(MAX_FAILURE_LENGTH));
	}
}
