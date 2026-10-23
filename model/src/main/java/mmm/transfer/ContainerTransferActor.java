package mmm.transfer;

import mmm.api.StableId;

import java.util.UUID;

/** Loader-neutral durable identity of the external state changed by a container transaction. */
public sealed interface ContainerTransferActor
		permits ContainerTransferActor.PlayerHand, ContainerTransferActor.Dispenser {
	Kind kind();
	default boolean sameInventory(ContainerTransferActor other) {
		if (this instanceof Dispenser first && other instanceof Dispenser second)
			return first.dimensionId().equals(second.dimensionId()) && first.blockX()==second.blockX()
					&& first.blockY()==second.blockY() && first.blockZ()==second.blockZ() && first.slot()==second.slot();
		return equals(other);
	}

	static PlayerHand player(UUID playerId, Hand hand) {
		return new PlayerHand(playerId, hand);
	}

	static Dispenser dispenser(
			String dimensionId, int blockX, int blockY, int blockZ, int slot
	) {
		return new Dispenser(dimensionId, blockX, blockY, blockZ, slot);
	}

	enum Kind { PLAYER_HAND, DISPENSER }

	enum Hand { MAIN, OFF }

	record PlayerHand(UUID playerId, Hand hand) implements ContainerTransferActor {
		public PlayerHand {
			if (playerId == null || playerId.equals(new UUID(0L, 0L)) || hand == null) {
				throw new IllegalArgumentException("Player container transfer actor is invalid");
			}
		}

		@Override public Kind kind() { return Kind.PLAYER_HAND; }
	}

	record Dispenser(
			String dimensionId, int blockX, int blockY, int blockZ, int slot, UUID inventoryId, long generation
	) implements ContainerTransferActor {
		public Dispenser(String dimensionId,int blockX,int blockY,int blockZ,int slot) {
			this(dimensionId,blockX,blockY,blockZ,slot,null,0);
		}
		public Dispenser {
			StableId.require(dimensionId, 128, "dimension id");
			if (slot < 0 || slot >= 9 || generation<0 || generation==Long.MAX_VALUE
					|| inventoryId==null && generation!=0) {
				throw new IllegalArgumentException("Dispenser container transfer actor is invalid");
			}
		}

		@Override public Kind kind() { return Kind.DISPENSER; }
	}
}
