package mmm.transfer;

import mmm.api.StableId;

import java.util.UUID;

/** Loader-neutral durable identity of the external state changed by a container transaction. */
public sealed interface ContainerTransferActor
		permits ContainerTransferActor.PlayerHand, ContainerTransferActor.BlockInventory {
	Kind kind();
	default boolean sameInventory(ContainerTransferActor other) {
		if (this instanceof BlockInventory first && other instanceof BlockInventory second)
			return first.dimensionId().equals(second.dimensionId()) && first.blockX()==second.blockX()
					&& first.blockY()==second.blockY() && first.blockZ()==second.blockZ() && first.slot()==second.slot();
		return equals(other);
	}

	static PlayerHand player(UUID playerId, Hand hand) {
		return new PlayerHand(playerId, hand);
	}

	static BlockInventory blockInventory(
			String dimensionId, int blockX, int blockY, int blockZ, int slot
	) {
		return new BlockInventory(dimensionId, blockX, blockY, blockZ, slot);
	}

	enum Kind { PLAYER_HAND, BLOCK_INVENTORY }

	enum Hand { MAIN, OFF }

	record PlayerHand(UUID playerId, Hand hand) implements ContainerTransferActor {
		public PlayerHand {
			if (playerId == null || playerId.equals(new UUID(0L, 0L)) || hand == null) {
				throw new IllegalArgumentException("Player container transfer actor is invalid");
			}
		}

		@Override public Kind kind() { return Kind.PLAYER_HAND; }
	}

	record BlockInventory(
			String dimensionId, int blockX, int blockY, int blockZ, int slot, UUID inventoryId, long generation
	) implements ContainerTransferActor {
		public BlockInventory(String dimensionId,int blockX,int blockY,int blockZ,int slot) {
			this(dimensionId,blockX,blockY,blockZ,slot,null,0);
		}
		public BlockInventory {
			StableId.require(dimensionId, 128, "dimension id");
			if (slot < 0 || slot >= 256 || generation<0 || generation==Long.MAX_VALUE
					|| inventoryId==null && generation!=0) {
				throw new IllegalArgumentException("Block inventory transfer actor is invalid");
			}
		}

		@Override public Kind kind() { return Kind.BLOCK_INVENTORY; }
	}
}
