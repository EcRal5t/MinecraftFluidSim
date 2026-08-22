package com.example.simulation;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import com.example.item.FluidMixture;
import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FluidSolverConfig;

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
			run("recovery state machine locks every ambiguous commit boundary", ContainerTransferStoreValidation::testRecoveryStateMachine);
			run("unknown and staged files lock without deletion", ContainerTransferStoreValidation::testUnknownLayout);
			run("corrupt named WAL locks without deletion", ContainerTransferStoreValidation::testCorruptWal);
			run("v1 pure WAL migrates while v1 material WAL locks", ContainerTransferStoreValidation::testLegacyMigration);
			run("nanovolume and solver cell-volume conversion", ContainerTransferStoreValidation::testNanosCellConversion);
			run("partial outlet requests only available marker volume", ContainerTransferStoreValidation::testPartialOutlet);
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
				ContainerTransferJournal.ItemKind.WATER_BUCKET, -1L, null);
		ContainerTransferJournal.ItemFingerprint after = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L, null);
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
				ContainerTransferJournal.ItemKind.BUCKET, -1L, null);
		ContainerTransferJournal.ItemFingerprint after = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.PARTIAL, 500_000_000L, null);

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
				before, before, ContainerTransferJournal.Phase.PREPARED
		);
		store.write(dispenser);
		check(dispenser.equals(store.read(tx2)), "dispenser actor WAL did not round-trip");

		UUID tx3 = UUID.randomUUID();
		ContainerTransferJournal.ItemFingerprint materialBefore = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.MATERIAL_BUCKET, 1_000_000_000L,
				new FluidMixture(List.of(
						new FluidMixture.Component("realwater:oil", 400_000_000L, 320.0),
						new FluidMixture.Component("realwater:water", 600_000_000L, 600.0))).encode(),
				Double.doubleToRawLongBits(920.0));
		ContainerTransferJournal.ItemFingerprint materialAfter = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.EMPTY_MATERIAL_BUCKET, -1L, null);
		ContainerTransferJournal material = new ContainerTransferJournal(
				tx3, region, "minecraft:overworld", 2L, 3L,
				ContainerTransferJournal.Operation.INSERT, 1_000_000_000L,
				ContainerTransferJournal.Actor.player(UUID.randomUUID(), "MAIN_HAND"),
				materialBefore, materialAfter, ContainerTransferJournal.Phase.REGION_COMMITTED
		);
		store.write(material);
		check(material.equals(store.read(tx3)), "material bucket fingerprint did not round-trip");

		UUID tx4 = UUID.randomUUID();
		ContainerTransferJournal emptyMaterial = new ContainerTransferJournal(
				tx4, region, "minecraft:overworld", 3L, 4L,
				ContainerTransferJournal.Operation.EXTRACT, 1_000_000_000L,
				ContainerTransferJournal.Actor.player(UUID.randomUUID(), "MAIN_HAND"),
				new ContainerTransferJournal.ItemFingerprint(
						ContainerTransferJournal.ItemKind.EMPTY_MATERIAL_BUCKET, -1L, null),
				materialBefore, ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(emptyMaterial);
		check(emptyMaterial.equals(store.read(tx4)), "empty material bucket fingerprint did not round-trip");

		ContainerTransferJournal wrongFamily = new ContainerTransferJournal(
				UUID.randomUUID(), region, "minecraft:overworld", 4L, 5L,
				ContainerTransferJournal.Operation.INSERT, 1_000_000_000L,
				ContainerTransferJournal.Actor.player(UUID.randomUUID(), "MAIN_HAND"),
				materialBefore, before, ContainerTransferJournal.Phase.REGION_COMMITTED);
		checkThrows(IOException.class, () -> store.write(wrongFamily),
				"material insertion must not converge to a vanilla empty bucket");

	}

	private static void testValidation() throws Exception {
		Path root = Files.createTempDirectory("realwater-wal-validation");
		ContainerTransferStore store = new ContainerTransferStore(root);
		ContainerTransferJournal.ItemFingerprint item = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L, null);
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
				new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.PARTIAL, -1L, null),
				item, ContainerTransferJournal.Phase.PREPARED
		);
		checkThrows(IOException.class, () -> store.write(badPartial), "invalid partial volume must be rejected");
	}

	private static void testDelete() throws Exception {
		Path root = Files.createTempDirectory("realwater-wal-delete");
		ContainerTransferStore store = new ContainerTransferStore(root);
		UUID tx = UUID.randomUUID();
		ContainerTransferJournal.ItemFingerprint item = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L, null);
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

	private static void testRecoveryStateMachine() {
		ContainerTransferJournal prepared = journal(ContainerTransferJournal.Phase.PREPARED);
		ContainerTransferJournal committed = new ContainerTransferJournal(
				prepared.transactionId(), prepared.regionId(), prepared.dimensionId(),
				prepared.baseGeneration(), prepared.targetGeneration(), prepared.operation(), prepared.volumeNanos(),
				prepared.actor(), prepared.beforeItem(),
				new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.WATER_BUCKET, -1L, null),
				ContainerTransferJournal.Phase.REGION_COMMITTED);
		check(ContainerTransferRecovery.decide(4L, prepared, false)
				== ContainerTransferRecovery.Decision.DISCARD_UNCOMMITTED,
				"clean base HEAD must discard an uncommitted WAL");
		check(ContainerTransferRecovery.decide(4L, committed, true)
				== ContainerTransferRecovery.Decision.LOCK_LIVE_SOLVER_DIRTY,
				"current-process dirty solver must never be rolled back as if restarted");
		check(ContainerTransferRecovery.decide(5L, committed, false)
				== ContainerTransferRecovery.Decision.CONVERGE_AFTER_ITEM,
				"target HEAD plus committed after-item must converge");
		check(ContainerTransferRecovery.decide(5L, prepared, false)
				== ContainerTransferRecovery.Decision.LOCK_TARGET_WITHOUT_AFTER_ITEM,
				"target HEAD without an authoritative after-item must lock");
		check(ContainerTransferRecovery.decide(6L, committed, false)
				== ContainerTransferRecovery.Decision.LOCK_GENERATION_MISMATCH,
				"foreign HEAD generation must lock");
	}

	private static void testUnknownLayout() throws Exception {
		Path root = Files.createTempDirectory("realwater-wal-unknown");
		ContainerTransferStore store = new ContainerTransferStore(root);
		ContainerTransferJournal journal = journal(ContainerTransferJournal.Phase.PREPARED);
		store.write(journal);
		Path unknown = store.directoryPath().resolve("future-format.bin");
		Files.writeString(unknown, "foreign data");
		ContainerTransferStore.Scan scan = store.scan();
		check(scan.transactions().equals(List.of(journal.transactionId())) && !scan.issues().isEmpty(),
				"strict scan did not retain the known WAL while reporting foreign content");
		checkThrows(IOException.class, () -> store.write(journal), "unknown content must refuse WAL replacement");
		checkThrows(IOException.class, () -> store.delete(journal.transactionId()), "unknown content must refuse deletion");
		check(Files.exists(unknown), "unknown content was deleted");

		Files.delete(unknown);
		Path staged = store.directoryPath().resolve(journal.transactionId() + ".nbt.tmp");
		Files.writeString(staged, "interrupted replacement");
		check(!store.scan().issues().isEmpty() && Files.exists(staged),
				"interrupted WAL replacement was ignored or deleted");
	}

	private static void testCorruptWal() throws Exception {
		Path root = Files.createTempDirectory("realwater-wal-corrupt");
		ContainerTransferStore store = new ContainerTransferStore(root);
		UUID transactionId = UUID.randomUUID();
		Files.createDirectories(store.directoryPath());
		Path corrupt = store.directoryPath().resolve(transactionId + ".nbt");
		byte[] bytes = {1, 3, 3, 7};
		Files.write(corrupt, bytes);
		ContainerTransferStore.Scan scan = store.scan();
		check(scan.transactions().isEmpty() && !scan.issues().isEmpty() && scan.hasAny(),
				"corrupt named WAL did not become a global lock");
		check(java.util.Arrays.equals(bytes, Files.readAllBytes(corrupt)), "corrupt WAL was rewritten");
	}

	private static void testLegacyMigration() throws Exception {
		Path pureRoot = Files.createTempDirectory("realwater-wal-v1-pure");
		ContainerTransferStore pureStore = new ContainerTransferStore(pureRoot);
		ContainerTransferJournal pure = journal(ContainerTransferJournal.Phase.PREPARED);
		pureStore.write(pure);
		Path purePath = pureStore.directoryPath().resolve(pure.transactionId() + ".nbt");
		CompoundTag pureNbt = NbtIo.readCompressed(purePath, NbtAccounter.create(4L * 1024L * 1024L));
		pureNbt.putInt("Version", 1);
		pureNbt.remove("BeforeMassBits");
		pureNbt.remove("AfterMassBits");
		NbtIo.writeCompressed(pureNbt, purePath);
		check(pureStore.read(pure.transactionId()).equals(pure) && pureStore.scan().issues().isEmpty(),
				"legacy v1 pure-water WAL did not migrate in memory");

		Path materialRoot = Files.createTempDirectory("realwater-wal-v1-material");
		ContainerTransferStore materialStore = new ContainerTransferStore(materialRoot);
		ContainerTransferJournal.ItemFingerprint material = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.MATERIAL_BUCKET, 1_000_000_000L, "realwater:oil",
				Double.doubleToRawLongBits(800.0));
		ContainerTransferJournal legacyMaterial = new ContainerTransferJournal(
				UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 0L, 1L,
				ContainerTransferJournal.Operation.INSERT, 1_000_000_000L,
				ContainerTransferJournal.Actor.player(UUID.randomUUID(), "MAIN_HAND"), material, material,
				ContainerTransferJournal.Phase.PREPARED);
		materialStore.write(legacyMaterial);
		Path materialPath = materialStore.directoryPath().resolve(legacyMaterial.transactionId() + ".nbt");
		CompoundTag materialNbt = NbtIo.readCompressed(materialPath, NbtAccounter.create(4L * 1024L * 1024L));
		materialNbt.putInt("Version", 1);
		materialNbt.remove("BeforeMassBits");
		materialNbt.remove("AfterMassBits");
		NbtIo.writeCompressed(materialNbt, materialPath);
		check(!materialStore.scan().issues().isEmpty() && Files.exists(materialPath),
				"legacy v1 material WAL was guessed or deleted instead of locking");
	}

	private static ContainerTransferJournal journal(ContainerTransferJournal.Phase phase) {
		ContainerTransferJournal.ItemFingerprint item = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L, null);
		return new ContainerTransferJournal(
				UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 4L, 5L,
				ContainerTransferJournal.Operation.EXTRACT, 1_000_000_000L,
				ContainerTransferJournal.Actor.player(UUID.randomUUID(), "MAIN_HAND"), item, item, phase);
	}

	private static void testNanosCellConversion() {
		check(FluidTransferService.nanosToCellVolume(1_000_000_000L) == 8.0F,
				"one bucket must convert to eight solver cell volumes");
		check(FluidTransferService.cellVolumeToNanos(8.0) == 1_000_000_000L,
				"eight solver cell volumes must convert back to one bucket");
		check(FluidTransferService.nanosToCellVolume(500_000_000L) == 4.0F,
				"half a bucket must convert to four solver cell volumes");
		check(FluidTransferService.cellVolumeToNanos(1.0) == 125_000_000L,
				"one solver cell volume must be one eighth of a bucket");
	}

	private static void testPartialOutlet() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
		FlipFluidDomain domain = new FlipFluidDomain(4, 4, 4, config, 64);
		domain.tryInsertExactVolume(2, 2, 2, 0.5F, 1);
		long requested = FluidTransferService.extractableNanos(domain, new int[] {2, 2, 2},
				com.example.item.ContainerFluidLedger.NANOS_PER_BLOCK, 1, 0);
		check(requested == 62_500_000L, "partial outlet did not quantize the available half-cell volume");
		FlipFluidDomain.ExactVolumeTransfer transfer = domain.tryExtractExactVolume(
				2, 2, 2, FluidTransferService.nanosToCellVolume(requested), 1, 0);
		check(transfer.transferred() && domain.totalVolumeExact() == 0.0,
				"partial outlet request could not be settled by the exact primitive");
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
