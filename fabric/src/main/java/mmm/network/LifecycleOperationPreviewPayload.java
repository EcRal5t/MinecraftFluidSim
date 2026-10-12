package mmm.network;

import mmm.MmmMod;
import mmm.controller.ControllerSummary;
import mmm.controller.LifecycleOperation;
import mmm.controller.LifecycleOperationPreview;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.UUID;

/** Clientbound bounded preview plus a fresh ordinary-console token for cancellation. */
public record LifecycleOperationPreviewPayload(
		BlockPos controllerPosition, LifecycleOperationPreview preview, ControllerSummary refreshedSummary
) implements CustomPacketPayload {
	public static final Type<LifecycleOperationPreviewPayload> TYPE = new Type<>(MmmMod.id("lifecycle_preview"));
	public static final StreamCodec<FriendlyByteBuf, LifecycleOperationPreviewPayload> CODEC = StreamCodec.of(
			LifecycleOperationPreviewPayload::encode, LifecycleOperationPreviewPayload::decode);

	public LifecycleOperationPreviewPayload {
		if (controllerPosition == null || preview == null || refreshedSummary == null
				|| !preview.regionId().equals(refreshedSummary.regionId())) {
			throw new IllegalArgumentException("Lifecycle preview payload identity is invalid");
		}
		controllerPosition = controllerPosition.immutable();
	}

	@Override public Type<LifecycleOperationPreviewPayload> type() { return TYPE; }

	private static void encode(FriendlyByteBuf buffer, LifecycleOperationPreviewPayload payload) {
		buffer.writeBlockPos(payload.controllerPosition);
		LifecycleOperationPreview value = payload.preview;
		buffer.writeUUID(value.nonce()); buffer.writeUUID(value.playerId()); buffer.writeUUID(value.regionId());
		buffer.writeVarLong(value.regionGeneration()); buffer.writeEnum(value.operation());
		buffer.writeEnum(value.permissionDecision()); buffer.writeVarLong(value.expiresAtServerTick());
		writeRemoval(buffer, value.removal());
		LifecycleOperationPreview.GasState gas = value.gasState();
		buffer.writeVarLong(gas.standardVolumeNanos()); buffer.writeDouble(gas.massKilograms());
		buffer.writeVarLong(gas.speciesQuanta()); buffer.writeVarInt(gas.sealedNodes());
		buffer.writeBoolean(gas.ambientBoundaryPresent());
		buffer.writeVarInt(value.ownershipBlocks()); buffer.writeVarInt(value.blockers().size());
		for (LifecycleOperationPreview.Blocker blocker : value.blockers()) buffer.writeEnum(blocker);
		ControllerSummaryPayload.writeSummary(buffer, payload.refreshedSummary);
	}

	private static LifecycleOperationPreviewPayload decode(FriendlyByteBuf buffer) {
		BlockPos position = buffer.readBlockPos(); UUID nonce = buffer.readUUID(); UUID player = buffer.readUUID();
		UUID region = buffer.readUUID(); long generation = buffer.readVarLong();
		LifecycleOperation operation = buffer.readEnum(LifecycleOperation.class);
		LifecycleOperationPreview.PermissionDecision permission = buffer.readEnum(
				LifecycleOperationPreview.PermissionDecision.class);
		long expiry = buffer.readVarLong(); LifecycleOperationPreview.Removal removal = readRemoval(buffer);
		LifecycleOperationPreview.GasState gas = new LifecycleOperationPreview.GasState(
				buffer.readVarLong(), buffer.readDouble(), buffer.readVarLong(), buffer.readVarInt(), buffer.readBoolean());
		int ownership = buffer.readVarInt(), blockerCount = buffer.readVarInt();
		if (blockerCount < 0 || blockerCount > LifecycleOperationPreview.MAX_BLOCKERS) {
			throw new IllegalArgumentException("Lifecycle preview blocker count is invalid");
		}
		ArrayList<LifecycleOperationPreview.Blocker> blockers = new ArrayList<>(blockerCount);
		for (int index = 0; index < blockerCount; index++) blockers.add(buffer.readEnum(LifecycleOperationPreview.Blocker.class));
		LifecycleOperationPreview preview = new LifecycleOperationPreview(nonce, player, region, generation,
				operation, permission, expiry, removal, gas, ownership, blockers);
		return new LifecycleOperationPreviewPayload(position, preview, ControllerSummaryPayload.readSummary(buffer));
	}

	private static void writeRemoval(FriendlyByteBuf buffer, LifecycleOperationPreview.Removal value) {
		buffer.writeVarLong(value.liquidVolumeNanos()); buffer.writeDouble(value.liquidMassKilograms());
		buffer.writeVarLong(value.liquidSpeciesQuanta()); buffer.writeVarLong(value.granularSolidVolumeSvq());
		buffer.writeDouble(value.granularMassKilograms()); buffer.writeVarLong(value.coherentSolidVolumeSvq());
		buffer.writeDouble(value.coherentMassKilograms()); buffer.writeVarLong(value.sealedGasStandardVolumeNanos());
		buffer.writeDouble(value.sealedGasMassKilograms()); buffer.writeVarLong(value.sealedGasSpeciesQuanta());
		buffer.writeBoolean(value.internalEnergyAvailable()); buffer.writeDouble(value.internalEnergyEu());
	}

	private static LifecycleOperationPreview.Removal readRemoval(FriendlyByteBuf buffer) {
		return new LifecycleOperationPreview.Removal(buffer.readVarLong(), buffer.readDouble(), buffer.readVarLong(),
				buffer.readVarLong(), buffer.readDouble(), buffer.readVarLong(), buffer.readDouble(),
				buffer.readVarLong(), buffer.readDouble(), buffer.readVarLong(), buffer.readBoolean(), buffer.readDouble());
	}
}
