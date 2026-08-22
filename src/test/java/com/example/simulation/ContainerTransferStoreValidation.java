package com.example.simulation;

import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Standalone codec/file fault gate for the short-lived container-transfer WAL. */
public final class ContainerTransferStoreValidation {
	private static int passed;

	private ContainerTransferStoreValidation() { }

	public static void main(String[] args) throws Exception {
		Path root = Files.createTempDirectory("realwater-container-wal-test");
		try {
			run("round-trip and atomic replace", ContainerTransferStoreValidation::testRoundTrip);
			run("player and dispenser actor codecs", ContainerTransferStoreValidation::testActorCodecs);
			run("invalid generations and dimensions are rejected", ContainerTransferStoreValidation::testValidation);
			run("delete removes the transaction", ContainerTransferStoreValidation::testDelete);
			System.out.println("ContainerTransferStoreValidation passed " + passed + " scenarios");
		} finally {
			try (var paths = Files.walk(root)) {
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	private static void testRoundTrip() throws Exception {
		Path root = Files.createTempDirectory("realwater-wal-roundtrip");
		ContainerTransferStore store = new ContainerTransferStore(root);
		UUID tx = UUID.randomUUID();
		UUID region = UUID.randomUUID();
		ContainerTransferJournal.Actor actor = ContainerTransferJournal.Actor.player(UUID.randomUUID(), "MAIN_HAND");
		ContainerTransferJournal.ItemFingerprint before = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.WATER_BUCKET, -1L);
		ContainerTransferJournal.ItemFingerprint after = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L);
		ContainerTransferJournal prepared = new ContainerTransferJournal(
				tx, region, "minecraft:overworld", 3L, 4L,
				ContainerTransferJournal.Operation.INSERT, 1_000_000_000L, actor, before, before,
				ContainerTransferJournal.Phase.PREPARED
		);
		store.write(prepared);
		check(prepared.equals(store.read(tx)), "prepared WAL did not round-trip");
		check(store.listTransactions().equals(List.of(tx)), "prepared WAL was not listed");

		ContainerTransferJournal committed = new ContainerTransferJournal(
				tx, region, "minecraft:overworld", 3L, 4L,
				ContainerTransferJournal.Operation.INSERT, 1_000_000_000L, actor, before, after,
				ContainerTransferJournal.Phase.REGION_COMMITTED
		);
		store.write(committed);
		check(committed.equals(store.read(tx)), "committed WAL did not atomically replace prepared");
		store.delete(tx);
		check(!store.listTransactions().contains(tx), "deleted WAL was still listed");
	}

	private static void testActorCodecs() throws Exception {
		Path root = Files.createTempDirectory("realwater-wal-actors");
		ContainerTransferStore store = new ContainerTransferStore(root);
		UUID tx = UUID.randomUUID();
		UUID region = UUID.randomUUID();
		ContainerTransferJournal.ItemFingerprint before = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L);
		ContainerTransferJournal.ItemFingerprint after = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.PARTIAL, 500_000_000L);

		ContainerTransferJournal player = new ContainerTransferJournal(
				tx, region, "minecraft:overworld", 0L, 1L,
				ContainerTransferJournal.Operation.EXTRACT, 500_000_000L,
				ContainerTransferJournal.Actor.player(UUID.randomUUID(), "OFF_HAND"), before, after,
				ContainerTransferJournal.Phase.REGION_COMMITTED
		);
		store.write(player);
		check(player.equals(store.read(tx)), "player actor WAL did not round-trip");

		UUID tx2 = UUID.randomUUID();
		ContainerTransferJournal dispenser = new ContainerTransferJournal(
				tx2, region, "minecraft:the_nether", 1L, 2L,
				ContainerTransferJournal.Operation.EXTRACT, 250_000_000L,
				ContainerTransferJournal.Actor.dispenser("minecraft:the_nether", new BlockPos(3, 4, 5), 2),
				before, after, ContainerTransferJournal.Phase.PREPARED
		);
		store.write(dispenser);
		check(dispenser.equals(store.read(tx2)), "dispenser actor WAL did not round-trip");
	}

	private static void testValidation() throws Exception {
		Path root = Files.createTempDirectory("realwater-wal-validation");
		ContainerTransferStore store = new ContainerTransferStore(root);
		ContainerTransferJournal.ItemFingerprint item = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L);
		ContainerTransferJournal.Actor actor = ContainerTransferJournal.Actor.player(UUID.randomUUID(), "MAIN_HAND");

		ContainerTransferJournal badGeneration = new ContainerTransferJournal(
				UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 0L, 2L,
				ContainerTransferJournal.Operation.INSERT, 1L, actor, item, item,
				ContainerTransferJournal.Phase.PREPARED
		);
		checkThrows(IOException.class, () -> store.write(badGeneration), "bad generation must be rejected");

		ContainerTransferJournal badDimension = new ContainerTransferJournal(
				UUID.randomUUID(), UUID.randomUUID(), "not a dimension", 0L, 1L,
				ContainerTransferJournal.Operation.INSERT, 1L, actor, item, item,
				ContainerTransferJournal.Phase.PREPARED
		);
		checkThrows(IOException.class, () -> store.write(badDimension), "bad dimension must be rejected");

		ContainerTransferJournal badPartial = new ContainerTransferJournal(
				UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 0L, 1L,
				ContainerTransferJournal.Operation.INSERT, 1L, actor,
				new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.PARTIAL, -1L),
				item, ContainerTransferJournal.Phase.PREPARED
		);
		checkThrows(IOException.class, () -> store.write(badPartial), "invalid partial volume must be rejected");
	}

	private static void testDelete() throws Exception {
		Path root = Files.createTempDirectory("realwater-wal-delete");
		ContainerTransferStore store = new ContainerTransferStore(root);
		UUID tx = UUID.randomUUID();
		ContainerTransferJournal.ItemFingerprint item = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L);
		ContainerTransferJournal journal = new ContainerTransferJournal(
				tx, UUID.randomUUID(), "minecraft:overworld", 0L, 1L,
				ContainerTransferJournal.Operation.EXTRACT, 1L,
				ContainerTransferJournal.Actor.player(UUID.randomUUID(), "MAIN_HAND"), item, item,
				ContainerTransferJournal.Phase.PREPARED
		);
		store.write(journal);
		store.delete(tx);
		check(!store.hasAny(), "store should be empty after deleting the only transaction");
	}

	private static void run(String name, ThrowingRunnable scenario) throws Exception {
		scenario.run();
		passed++;
		System.out.println("PASS " + name);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void checkThrows(Class<? extends Exception> type, ThrowingRunnable scenario, String message) throws Exception {
		try {
			scenario.run();
		} catch (Exception exception) {
			if (!type.isInstance(exception)) {
				throw new AssertionError(message + " (wrong exception " + exception + ")", exception);
			}
			return;
		}
		throw new AssertionError(message);
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}
}
