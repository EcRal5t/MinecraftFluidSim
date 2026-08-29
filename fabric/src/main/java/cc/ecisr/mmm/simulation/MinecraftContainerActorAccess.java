package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.recovery.CrossDomainRecovery;
import cc.ecisr.mmm.transfer.ContainerTransferActor;
import cc.ecisr.mmm.transfer.ContainerTransferJournal;
import net.minecraft.core.BlockPos;
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
	CrossDomainRecovery.ExternalTruth classify(MinecraftServer server, ContainerTransferJournal journal) {
		ItemStack current = currentItem(server, journal.actor());
		if (current == null) return CrossDomainRecovery.ExternalTruth.UNAVAILABLE;
		if (matches(current, journal.beforeItem())) return CrossDomainRecovery.ExternalTruth.BEFORE;
		if (matches(current, journal.afterItem())) return CrossDomainRecovery.ExternalTruth.AFTER;
		return CrossDomainRecovery.ExternalTruth.THIRD;
	}

	Convergence applyAfterItem(MinecraftServer server, ContainerTransferJournal journal) {
		return switch (journal.actor()) {
			case ContainerTransferActor.PlayerHand player -> convergePlayer(server, player, journal);
			case ContainerTransferActor.Dispenser dispenser -> convergeDispenser(server, dispenser, journal);
		};
	}

	CrossDomainRecovery.ExternalTruth verifyAfterItem(MinecraftServer server, ContainerTransferJournal journal) {
		ItemStack current = currentItem(server, journal.actor());
		if (current == null) return CrossDomainRecovery.ExternalTruth.UNAVAILABLE;
		if (matches(current, journal.afterItem())) return CrossDomainRecovery.ExternalTruth.AFTER;
		if (matches(current, journal.beforeItem())) return CrossDomainRecovery.ExternalTruth.BEFORE;
		return CrossDomainRecovery.ExternalTruth.THIRD;
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
			case MAIN -> InteractionHand.MAIN_HAND;
			case OFF -> InteractionHand.OFF_HAND;
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
		BlockPos position = new BlockPos(actor.blockX(), actor.blockY(), actor.blockZ());
		if (level == null || !level.hasChunkAt(position)) return Convergence.UNAVAILABLE;
		if (!(level.getBlockEntity(position) instanceof DispenserBlockEntity dispenser)
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

	private static ItemStack currentItem(MinecraftServer server, ContainerTransferActor actor) {
		return switch (actor) {
			case ContainerTransferActor.PlayerHand player -> {
				ServerPlayer owner = server.getPlayerList().getPlayer(player.playerId());
				if (owner == null) yield null;
				InteractionHand hand = player.hand() == ContainerTransferActor.Hand.MAIN
						? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
				yield owner.getItemInHand(hand);
			}
			case ContainerTransferActor.Dispenser dispenser -> {
				ServerLevel owner = level(server, dispenser.dimensionId());
				BlockPos position = new BlockPos(dispenser.blockX(), dispenser.blockY(), dispenser.blockZ());
				if (owner == null || !owner.hasChunkAt(position)) yield null;
				if (!(owner.getBlockEntity(position) instanceof DispenserBlockEntity inventory)
						|| dispenser.slot() >= inventory.getContainerSize()) yield ItemStack.EMPTY;
				yield inventory.getItem(dispenser.slot());
			}
		};
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
