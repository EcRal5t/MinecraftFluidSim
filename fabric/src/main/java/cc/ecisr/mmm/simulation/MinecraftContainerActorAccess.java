package cc.ecisr.mmm.simulation;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;

import java.io.IOException;

/** Locates, converges, and durably saves the Minecraft inventory named by a transfer actor. */
final class MinecraftContainerActorAccess {
	Convergence convergeAfterItem(MinecraftServer server, ContainerTransferJournal journal) {
		return switch (journal.actor()) {
			case ContainerTransferActor.PlayerHand player -> convergePlayer(server, player, journal);
			case ContainerTransferActor.Dispenser dispenser -> convergeDispenser(server, dispenser, journal);
		};
	}

	/** The WAL may be deleted only after this forces the actor's owning save domain. */
	void persistAfterItem(MinecraftServer server, ContainerTransferActor actor) throws IOException {
		switch (actor) {
			case ContainerTransferActor.PlayerHand ignored -> server.getPlayerList().saveAll();
			case ContainerTransferActor.Dispenser dispenser -> {
				ServerLevel level = level(server, dispenser.dimensionId());
				if (level == null) throw new IOException("Container transfer actor dimension is not loaded");
				// Minecraft exposes only a dimension-wide public flush here. A non-flushing autosave
				// cannot justify deleting the WAL after a dispenser inventory mutation.
				level.save(null, true, false);
			}
		}
	}

	private static Convergence convergePlayer(
			MinecraftServer server,
			ContainerTransferActor.PlayerHand actor,
			ContainerTransferJournal journal
	) {
		ServerPlayer player = server.getPlayerList().getPlayer(actor.playerId());
		if (player == null) return Convergence.UNAVAILABLE;
		InteractionHand hand = switch (actor.hand()) {
			case "MAIN_HAND" -> InteractionHand.MAIN_HAND;
			case "OFF_HAND" -> InteractionHand.OFF_HAND;
			default -> throw new IllegalStateException("Validated player actor has an unknown hand");
		};
		ItemStack current = player.getItemInHand(hand);
		if (!matches(current, journal.beforeItem()) && !matches(current, journal.afterItem())) {
			return Convergence.CONFLICT;
		}
		player.setItemInHand(hand, ContainerTransferItems.toStack(journal.afterItem()));
		player.getInventory().setChanged();
		return Convergence.APPLIED;
	}

	private static Convergence convergeDispenser(
			MinecraftServer server,
			ContainerTransferActor.Dispenser actor,
			ContainerTransferJournal journal
	) {
		ServerLevel level = level(server, actor.dimensionId());
		if (level == null || !level.hasChunkAt(actor.position())) return Convergence.UNAVAILABLE;
		if (!(level.getBlockEntity(actor.position()) instanceof DispenserBlockEntity dispenser)
				|| actor.slot() >= dispenser.getContainerSize()) {
			return Convergence.CONFLICT;
		}
		ItemStack current = dispenser.getItem(actor.slot());
		if (!matches(current, journal.beforeItem()) && !matches(current, journal.afterItem())) {
			return Convergence.CONFLICT;
		}
		dispenser.setItem(actor.slot(), ContainerTransferItems.toStack(journal.afterItem()));
		dispenser.setChanged();
		return Convergence.APPLIED;
	}

	private static ServerLevel level(MinecraftServer server, String dimension) {
		Identifier id = Identifier.tryParse(dimension);
		return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
	}

	private static boolean matches(ItemStack stack, ContainerTransferJournal.ItemFingerprint fingerprint) {
		try {
			return ContainerTransferItems.fingerprint(stack).equals(fingerprint);
		} catch (IllegalArgumentException invalid) {
			return false;
		}
	}

	enum Convergence {
		APPLIED,
		UNAVAILABLE,
		CONFLICT
	}
}
