package com.example.network;

import com.example.RealWaterMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.Objects;

/** Client-to-server configuration-phase negotiation result. */
public record FluidProtocolResponsePayload(
		boolean accepted,
		int selectedVersion,
		long capabilities,
		String failure
) implements CustomPacketPayload {
	private static final int MAX_FAILURE_LENGTH = 512;

	public static final Type<FluidProtocolResponsePayload> TYPE = new Type<>(RealWaterMod.id("protocol_response"));
	public static final StreamCodec<FriendlyByteBuf, FluidProtocolResponsePayload> CODEC = StreamCodec.of(
			FluidProtocolResponsePayload::encode,
			FluidProtocolResponsePayload::decode
	);

	public FluidProtocolResponsePayload {
		failure = Objects.requireNonNullElse(failure, "");
		if (failure.length() > MAX_FAILURE_LENGTH) throw new IllegalArgumentException("Protocol failure text is too long");
		if (accepted && (selectedVersion <= 0 || !failure.isEmpty())) {
			throw new IllegalArgumentException("Accepted protocol response must select a version without failure text");
		}
		if (!accepted && (selectedVersion != 0 || capabilities != 0L || failure.isBlank())) {
			throw new IllegalArgumentException("Rejected protocol response must contain only a readable failure");
		}
	}

	public static FluidProtocolResponsePayload from(FluidProtocol.Negotiation negotiation) {
		return negotiation.accepted()
				? new FluidProtocolResponsePayload(true, negotiation.selectedVersion(), negotiation.capabilities(), "")
				: new FluidProtocolResponsePayload(false, 0, 0L, negotiation.failure());
	}

	@Override
	public Type<FluidProtocolResponsePayload> type() {
		return TYPE;
	}

	private static void encode(FriendlyByteBuf buffer, FluidProtocolResponsePayload payload) {
		buffer.writeBoolean(payload.accepted);
		buffer.writeVarInt(payload.selectedVersion);
		buffer.writeVarLong(payload.capabilities);
		buffer.writeUtf(payload.failure, MAX_FAILURE_LENGTH);
	}

	private static FluidProtocolResponsePayload decode(FriendlyByteBuf buffer) {
		return new FluidProtocolResponsePayload(
				buffer.readBoolean(), buffer.readVarInt(), buffer.readVarLong(), buffer.readUtf(MAX_FAILURE_LENGTH)
		);
	}
}
