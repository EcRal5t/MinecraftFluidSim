package mmm.transfer;

import mmm.api.StableId;

import java.util.UUID;

/** Loader-neutral durable identity of the external state changed by a container transaction. */
public sealed interface ContainerTransferActor
		permits ContainerTransferActor.PlayerHand, ContainerTransferActor.Dispenser {
	Kind kind();

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
			String dimensionId, int blockX, int blockY, int blockZ, int slot
	) implements ContainerTransferActor {
		public Dispenser {
			StableId.require(dimensionId, 128, "dimension id");
			if (slot < 0 || slot >= 9) {
				throw new IllegalArgumentException("Dispenser container transfer actor is invalid");
			}
		}

		@Override public Kind kind() { return Kind.DISPENSER; }
	}
}
