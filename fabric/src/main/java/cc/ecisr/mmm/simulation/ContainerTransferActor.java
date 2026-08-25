package cc.ecisr.mmm.simulation;

import net.minecraft.core.BlockPos;

import java.util.UUID;

/** Type-safe durable identity of the Minecraft state changed by a container transaction. */
public sealed interface ContainerTransferActor permits ContainerTransferActor.PlayerHand, ContainerTransferActor.Dispenser {
	Kind kind();

	static PlayerHand player(UUID playerId, String hand) {
		return new PlayerHand(playerId, hand);
	}

	static Dispenser dispenser(String dimensionId, BlockPos position, int slot) {
		return new Dispenser(dimensionId, position, slot);
	}

	enum Kind { PLAYER_HAND, DISPENSER }

	record PlayerHand(UUID playerId, String hand) implements ContainerTransferActor {
		public PlayerHand {
			if (playerId == null || playerId.equals(new UUID(0L, 0L))
					|| !("MAIN_HAND".equals(hand) || "OFF_HAND".equals(hand))) {
				throw new IllegalArgumentException("Player container transfer actor is invalid");
			}
		}

		@Override public Kind kind() { return Kind.PLAYER_HAND; }
	}

	record Dispenser(String dimensionId, BlockPos position, int slot) implements ContainerTransferActor {
		public Dispenser {
			if (dimensionId == null || position == null || slot < 0 || slot >= 9) {
				throw new IllegalArgumentException("Dispenser container transfer actor is invalid");
			}
			position = position.immutable();
		}

		@Override public Kind kind() { return Kind.DISPENSER; }
	}
}
