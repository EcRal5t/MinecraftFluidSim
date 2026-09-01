package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.application.ContainerRecoveryCoordinator;
import cc.ecisr.mmm.simulation.persistence.ContainerJournalFileStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import cc.ecisr.mmm.transfer.ContainerFluidLedger;
import cc.ecisr.mmm.transfer.ChemicalContents;
import cc.ecisr.mmm.transfer.ContainerTransferActor;
import cc.ecisr.mmm.transfer.ContainerTransferJournal;
import cc.ecisr.mmm.transfer.CreativeSourceDescriptor;
import cc.ecisr.mmm.transfer.FluidMixture;
import cc.ecisr.mmm.transfer.GranularContents;
import cc.ecisr.mmm.transfer.ThermalContents;
import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.SolidCompositionRecord;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.recovery.CrossDomainRecovery;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Standalone codec/file fault gate for the short-lived container-transfer WAL. */
public final class ContainerTransferStoreValidation {
	private static final ResourceKey<Registry<Level>> DIMENSION_REGISTRY = ResourceKey.createRegistryKey(
			Identifier.fromNamespaceAndPath("mmm", "container_transfer_validation_registry"));
	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(DIMENSION_REGISTRY,
			Identifier.fromNamespaceAndPath("mmm", "container_transfer_validation"));
	private static final String PROFILE = "0".repeat(64);
	private static int passed;

	private ContainerTransferStoreValidation() { }

	public static void main(String[] args) throws Exception {
		Path root = Files.createTempDirectory("mmm-container-wal-test");
		try {
			run("round-trip and atomic replace", ContainerTransferStoreValidation::testRoundTrip);
			run("thermal WAL couples item and region energy", ContainerTransferStoreValidation::testThermalWal);
			run("player and dispenser actor codecs", ContainerTransferStoreValidation::testActorCodecs);
			run("invalid generations and dimensions are rejected", ContainerTransferStoreValidation::testValidation);
			run("delete removes the transaction", ContainerTransferStoreValidation::testDelete);
			run("recovery state machine locks every ambiguous commit boundary", ContainerTransferStoreValidation::testRecoveryStateMachine);
			run("unknown and staged files lock without deletion", ContainerTransferStoreValidation::testUnknownLayout);
			run("corrupt named WAL locks without deletion", ContainerTransferStoreValidation::testCorruptWal);
			run("nanovolume and solver cell-volume conversion", ContainerTransferStoreValidation::testNanosCellConversion);
			run("non-float-friendly transfers settle actual marker volume", ContainerTransferStoreValidation::testActualMarkerSettlement);
			run("material service settlement preserves component residuals", ContainerTransferStoreValidation::testMaterialSettlement);
			run("partial outlet requests only available marker volume", ContainerTransferStoreValidation::testPartialOutlet);
			run("ownership sidecar lock blocks loaded checkpoints", ContainerTransferStoreValidation::testOwnershipCheckpointLock);
			run("committed private target replaces an in-process base solver",
					ContainerTransferStoreValidation::testCommittedTargetAdoption);
			System.out.println("ContainerTransferStoreValidation passed " + passed + " scenarios");
		} finally {
			try (var paths = Files.walk(root)) {
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					Files.deleteIfExists(path);
				}
			}
		}
	}

	private static void testThermalWal() throws Exception {
		Path root = Files.createTempDirectory("mmm-wal-thermal");
		ContainerJournalFileStore store = new ContainerJournalFileStore(root);
		UUID transaction = UUID.randomUUID(), region = UUID.randomUUID();
		String fingerprint = "b".repeat(64);
		double energy = -12_345.25;
		ThermalContents contents = ThermalContents.forPureWater(
				fingerprint, ContainerFluidLedger.NANOS_PER_BLOCK, energy);
		ContainerTransferJournal.ItemFingerprint before = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.WATER_BUCKET, -1L, -1L, null, -1L, contents.encode());
		ContainerTransferJournal.ItemFingerprint after = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L, null);
		ContainerTransferJournal.ThermalTransfer transfer =
				new ContainerTransferJournal.ThermalTransfer(fingerprint, energy);
		ContainerTransferJournal prepared = ContainerTransferJournal.thermalFluid(
				transaction, region, "minecraft:overworld", 7L, 8L,
				ContainerTransferJournal.Operation.INSERT, ContainerFluidLedger.NANOS_PER_BLOCK, transfer,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN), before, before, -1L,
				ContainerTransferJournal.Phase.PREPARED);
		store.write(prepared);
		check(store.read(transaction).equals(prepared), "prepared thermal WAL did not round-trip");
		CompoundTag encoded = NbtIo.readCompressed(store.directoryPath().resolve(transaction + ".nbt"),
				NbtAccounter.create(4L * 1024L * 1024L));
		check(encoded.getStringOr("Layout", "").equals("thermal") && encoded.get("ThermalEnergyBits") != null,
				"thermal transaction did not use the thermal WAL layout");

		ContainerTransferJournal committed = ContainerTransferJournal.thermalFluid(
				transaction, region, "minecraft:overworld", 7L, 8L,
				ContainerTransferJournal.Operation.INSERT, ContainerFluidLedger.NANOS_PER_BLOCK, transfer,
				prepared.actor(), before, after, 0x7711L, ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(committed);
		check(store.read(transaction).equals(committed), "committed thermal WAL did not round-trip");
			ThermalContents.EnergySplit creativeSplit = contents.splitHomogeneousEnergy(500_000_000L,
				ContainerFluidLedger.NANOS_PER_BLOCK);
		double creativeEnergy = contents.internalEnergy() - creativeSplit.remainingEnergy();
		ContainerTransferJournal creative = ContainerTransferJournal.thermalFluid(
				UUID.randomUUID(), region, "minecraft:overworld", 8L, 9L,
				ContainerTransferJournal.Operation.INSERT, 500_000_000L,
				new ContainerTransferJournal.ThermalTransfer(fingerprint, creativeEnergy), committed.actor(),
				before, before, 0x7712L, ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(creative);
		check(store.read(creative.transactionId()).equals(creative),
				"creative unchanged-item WAL did not preserve explicit external source energy");
		CreativeSourceDescriptor descriptor = new CreativeSourceDescriptor(
				"mmm:source/material/water", 500_000_000L, 330.0);
		ContainerTransferJournal.ItemFingerprint descriptorItem = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.CREATIVE_SOURCE, -1L, -1L,
				descriptor.encode(), -1L, null, null);
		ContainerTransferJournal descriptorThermal = ContainerTransferJournal.thermalFluid(
				UUID.randomUUID(), region, "minecraft:overworld", 9L, 10L,
				ContainerTransferJournal.Operation.INSERT, 500_000_000L,
				new ContainerTransferJournal.ThermalTransfer(fingerprint, creativeEnergy), committed.actor(),
				descriptorItem, descriptorItem, 0x7713L, ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(descriptorThermal);
		check(store.read(descriptorThermal.transactionId()).equals(descriptorThermal),
				"creative thermal descriptor WAL did not preserve separate actor and source truth");
		ContainerTransferJournal wrongEnergy = ContainerTransferJournal.thermalFluid(
				UUID.randomUUID(), region, "minecraft:overworld", 10L, 11L,
				ContainerTransferJournal.Operation.INSERT, ContainerFluidLedger.NANOS_PER_BLOCK,
				new ContainerTransferJournal.ThermalTransfer(fingerprint, energy + 1.0), committed.actor(),
				before, after, 0x7714L, ContainerTransferJournal.Phase.REGION_COMMITTED);
		try {
			store.write(wrongEnergy);
			throw new AssertionError("Expected mismatched WAL item/energy rejection");
		} catch (IOException expected) {
			// expected
		}
	}

	private static void testRoundTrip() throws Exception {
		Path root = Files.createTempDirectory("mmm-wal-roundtrip");
		ContainerJournalFileStore store = new ContainerJournalFileStore(root);
		UUID tx = UUID.randomUUID();
		UUID region = UUID.randomUUID();
		ContainerTransferActor actor = ContainerTransferActor.player(
				UUID.randomUUID(), ContainerTransferActor.Hand.MAIN);
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
		check(store.discover().transactions().equals(List.of(tx)), "prepared WAL was not listed");

		ContainerTransferJournal committed = new ContainerTransferJournal(
				tx, region, "minecraft:overworld", 3L, 4L,
				ContainerTransferJournal.Operation.INSERT, 1_000_000_000L, actor, before, after,
				0x1234L,
				ContainerTransferJournal.Phase.REGION_COMMITTED
		);
		store.write(committed);
		check(committed.equals(store.read(tx)), "committed WAL did not atomically replace prepared");
		store.delete(tx);
		check(!store.discover().transactions().contains(tx), "deleted WAL was still listed");

		UUID partialTx = UUID.randomUUID();
		ContainerTransferJournal.ItemFingerprint partialBefore = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.PARTIAL, 999_999_999L, null);
		ContainerTransferJournal partialPrepared = new ContainerTransferJournal(
				partialTx, region, "minecraft:overworld", 4L, 5L,
				ContainerTransferJournal.Operation.INSERT, 999_999_999L, actor, partialBefore, partialBefore,
				ContainerTransferJournal.Phase.PREPARED);
		store.write(partialPrepared);
		ContainerTransferJournal partialCommitted = new ContainerTransferJournal(
				partialTx, region, "minecraft:overworld", 4L, 5L,
				ContainerTransferJournal.Operation.INSERT, 999_999_940L, actor, partialBefore,
				new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.PARTIAL, 59L, null),
				0x2345L,
				ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(partialCommitted);
		check(partialCommitted.equals(store.read(partialTx)),
				"committed WAL did not accept the primitive's actual partial-pour settlement");
		store.delete(partialTx);

		CreativeSourceDescriptor descriptor = new CreativeSourceDescriptor(
				"mmm:source/granular/fine_sand", SolidVolume.SVQ_PER_BV, null);
		ContainerTransferJournal.ItemFingerprint source = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.CREATIVE_SOURCE, -1L, -1L,
				descriptor.encode(), -1L, null, null);
		UUID sourceTx = UUID.randomUUID();
		ContainerTransferJournal sourceCommitted = ContainerTransferJournal.granular(
				sourceTx, region, "minecraft:overworld", 5L, 6L,
				ContainerTransferJournal.Operation.INSERT, SolidVolume.SVQ_PER_BV, actor,
				source, source, 0x3456L, ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(sourceCommitted);
		check(sourceCommitted.equals(store.read(sourceTx)),
				"creative source WAL did not preserve its unchanged descriptor actor state");
	}

	private static void testActorCodecs() throws Exception {
		Path root = Files.createTempDirectory("mmm-wal-actors");
		ContainerJournalFileStore store = new ContainerJournalFileStore(root);
		UUID tx = UUID.randomUUID();
		UUID region = UUID.randomUUID();
		ContainerTransferJournal.ItemFingerprint before = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L, null);
		ContainerTransferJournal.ItemFingerprint after = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.PARTIAL, 500_000_000L, null);

		ContainerTransferJournal player = new ContainerTransferJournal(
				tx, region, "minecraft:overworld", 0L, 1L,
				ContainerTransferJournal.Operation.EXTRACT, 500_000_000L,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.OFF), before, after,
				0x3456L,
				ContainerTransferJournal.Phase.REGION_COMMITTED
		);
		store.write(player);
		check(player.equals(store.read(tx)), "player actor WAL did not round-trip");

		UUID tx2 = UUID.randomUUID();
		ContainerTransferJournal dispenser = new ContainerTransferJournal(
				tx2, region, "minecraft:the_nether", 1L, 2L,
				ContainerTransferJournal.Operation.EXTRACT, 250_000_000L,
				ContainerTransferActor.dispenser("minecraft:the_nether", 3, 4, 5, 2),
				before, before, ContainerTransferJournal.Phase.PREPARED
		);
		store.write(dispenser);
		check(dispenser.equals(store.read(tx2)), "dispenser actor WAL did not round-trip");
		checkThrows(IllegalArgumentException.class,
				() -> ContainerTransferActor.player(UUID.randomUUID(), null),
				"unknown player hand was accepted and would fall back to main hand");
		checkThrows(IllegalArgumentException.class,
				() -> ContainerTransferActor.dispenser("minecraft:the_nether", 3, 4, 5, 9),
				"out-of-range dispenser slot was accepted");
		ContainerTransferJournal crossDimensionDispenser = new ContainerTransferJournal(
				UUID.randomUUID(), region, "minecraft:overworld", 1L, 2L,
				ContainerTransferJournal.Operation.EXTRACT, 250_000_000L,
				ContainerTransferActor.dispenser("minecraft:the_nether", 3, 4, 5, 2),
				before, before, ContainerTransferJournal.Phase.PREPARED);
		checkThrows(IOException.class, () -> store.write(crossDimensionDispenser),
				"cross-dimension dispenser actor was accepted");

		UUID tx3 = UUID.randomUUID();
		ContainerTransferJournal.ItemFingerprint materialBefore = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.MATERIAL_BUCKET, 1_000_000_000L, -1L,
				new FluidMixture(List.of(
						new FluidMixture.Component("mmm:oil", 400_000_000L, 320.0),
						new FluidMixture.Component("mmm:water", 600_000_000L, 600.0))).encode(),
				Double.doubleToRawLongBits(920.0), null, PROFILE);
		ContainerTransferJournal.ItemFingerprint materialAfter = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.EMPTY_MATERIAL_BUCKET, -1L, null);
		ContainerTransferJournal material = new ContainerTransferJournal(
				tx3, region, "minecraft:overworld", 2L, 3L,
				ContainerTransferJournal.Operation.INSERT, 1_000_000_000L,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN),
				materialBefore, materialAfter, 0x4567L, ContainerTransferJournal.Phase.REGION_COMMITTED
		);
		store.write(material);
		check(material.equals(store.read(tx3)), "material bucket fingerprint did not round-trip");
		material.validateProfileFingerprint(PROFILE);
		checkThrows(IllegalArgumentException.class,
				() -> material.validateProfileFingerprint("f".repeat(64)),
				"foreign-profile material WAL was accepted by the world admission check");
		ContainerTransferJournal.ItemFingerprint incompleteMaterial = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.MATERIAL_BUCKET, 1_000_000_000L, -1L,
				"mmm:water", Double.doubleToRawLongBits(1_000.0), null, PROFILE);
		ContainerTransferJournal incompleteMaterialJournal = new ContainerTransferJournal(
				UUID.randomUUID(), region, "minecraft:overworld", 3L, 4L,
				ContainerTransferJournal.Operation.INSERT, 1_000_000_000L,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN),
				incompleteMaterial, materialAfter, 0x4568L, ContainerTransferJournal.Phase.REGION_COMMITTED);
		checkThrows(IOException.class, () -> store.write(incompleteMaterialJournal),
				"material WAL without complete mixture truth was accepted");

		UUID tx4 = UUID.randomUUID();
		ContainerTransferJournal emptyMaterial = new ContainerTransferJournal(
				tx4, region, "minecraft:overworld", 3L, 4L,
				ContainerTransferJournal.Operation.EXTRACT, 1_000_000_000L,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN),
				new ContainerTransferJournal.ItemFingerprint(
						ContainerTransferJournal.ItemKind.EMPTY_MATERIAL_BUCKET, -1L, null),
				materialBefore, 0x5678L, ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(emptyMaterial);
		check(emptyMaterial.equals(store.read(tx4)), "empty material bucket fingerprint did not round-trip");

		ChemicalContents chemicalContents = new ChemicalContents(BuiltinChemicalDefinitions.REGISTRY.fingerprint(),
				ChemicalLimits.QUANTA_PER_AU, List.of(new ChemicalContents.Group(
						"mmm:water", true, ContainerFluidLedger.NANOS_PER_BLOCK,
						Double.doubleToRawLongBits(18.0), Double.doubleToRawLongBits(0.0),
						List.of(new ChemicalContents.SpeciesAmount(
								BuiltinChemicalDefinitions.H2O, ChemicalLimits.QUANTA_PER_AU)))));
		ContainerTransferJournal.ItemFingerprint chemicalBefore = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.CHEMICAL_BUCKET, ContainerFluidLedger.NANOS_PER_BLOCK, -1L,
				chemicalContents.encode(), Double.doubleToRawLongBits(chemicalContents.totalMass()), null, PROFILE);
		UUID tx5 = UUID.randomUUID();
		ContainerTransferJournal chemical = new ContainerTransferJournal(
				tx5, region, "minecraft:overworld", 4L, 5L,
				ContainerTransferJournal.Operation.INSERT, ContainerFluidLedger.NANOS_PER_BLOCK,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN),
				chemicalBefore, materialAfter, 0x5abcL, ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(chemical);
		check(chemical.equals(store.read(tx5)), "chemical bucket WAL fingerprint did not round-trip");
		check(chemical.requiresChemicalRegistry(), "chemical WAL did not declare its registry dependency");
		chemical.validateChemicalRegistry(BuiltinChemicalDefinitions.REGISTRY);
		ChemicalContents foreignContents = new ChemicalContents("0".repeat(64), ChemicalLimits.QUANTA_PER_AU,
				chemicalContents.groups());
		ContainerTransferJournal.ItemFingerprint foreignChemical = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.CHEMICAL_BUCKET, ContainerFluidLedger.NANOS_PER_BLOCK, -1L,
				foreignContents.encode(), Double.doubleToRawLongBits(foreignContents.totalMass()), null, PROFILE);
		ContainerTransferJournal foreignJournal = new ContainerTransferJournal(
				UUID.randomUUID(), region, "minecraft:overworld", 4L, 5L,
				ContainerTransferJournal.Operation.INSERT, ContainerFluidLedger.NANOS_PER_BLOCK,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN),
				foreignChemical, materialAfter, ContainerTransferJournal.Phase.PREPARED);
		checkThrows(IllegalArgumentException.class,
				() -> foreignJournal.validateChemicalRegistry(BuiltinChemicalDefinitions.REGISTRY),
				"foreign chemical WAL fingerprint was accepted by recovery validation");
		ChemicalContents.Split chemicalPartial = chemicalContents.split(333_333_333L,
				BuiltinChemicalDefinitions.REGISTRY);
		ContainerTransferJournal.ItemFingerprint chemicalAfter = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.CHEMICAL_BUCKET, chemicalPartial.remaining().totalVolumeNanos(), -1L,
				chemicalPartial.remaining().encode(), Double.doubleToRawLongBits(chemicalPartial.remaining().totalMass()),
				null, PROFILE);
		UUID tx6 = UUID.randomUUID();
		ContainerTransferJournal chemicalPartialJournal = new ContainerTransferJournal(
				tx6, region, "minecraft:overworld", 5L, 6L,
				ContainerTransferJournal.Operation.INSERT, chemicalPartial.inserted().totalVolumeNanos(),
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN),
				chemicalBefore, chemicalAfter, 0x6abcL, ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(chemicalPartialJournal);
		check(chemicalPartialJournal.equals(store.read(tx6)), "partial chemical WAL settlement did not round-trip");

		ContainerTransferJournal wrongFamily = new ContainerTransferJournal(
				UUID.randomUUID(), region, "minecraft:overworld", 4L, 5L,
				ContainerTransferJournal.Operation.INSERT, 1_000_000_000L,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN),
				materialBefore, before, ContainerTransferJournal.Phase.REGION_COMMITTED);
		checkThrows(IOException.class, () -> store.write(wrongFamily),
				"material insertion must not converge to a vanilla empty bucket");

		var granularRegistry = BuiltinGranularDefinitions.createRegistry();
		long inertSvq = SolidVolume.SVQ_PER_CELL;
		long reactiveSvq = SolidVolume.SVQ_PER_CELL / 2L;
		SolidCompositionRecord reactive = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(
						BuiltinChemicalDefinitions.NA_PLUS, 64L,
						BuiltinChemicalDefinitions.CL_MINUS, 64L), 0.0);
		GranularContents granularContents = new GranularContents(granularRegistry.fingerprint(),
				SolidVolume.SVQ_PER_BV, List.of(
				new GranularContents.Group(BuiltinGranularDefinitions.FINE_SAND, inertSvq, 650.0),
				new GranularContents.Group(BuiltinGranularDefinitions.PRECIPITATE, reactiveSvq,
						Double.doubleToRawLongBits(reactive.totalMass(BuiltinChemicalDefinitions.REGISTRY)),
						reactive.massResidualBits(), reactive.stableAmounts(BuiltinChemicalDefinitions.REGISTRY)
						.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
						.map(entry -> new GranularContents.SpeciesAmount(
								entry.getKey(), entry.getValue())).toList())));
		ContainerTransferJournal.ItemFingerprint granularBefore = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.GRANULAR_CONTAINER, -1L,
				granularContents.totalSolidVolumeSvq(), granularContents.encode(),
				Double.doubleToRawLongBits(granularContents.totalMass()), null, PROFILE);
		ContainerTransferJournal.ItemFingerprint granularAfter = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.EMPTY_GRANULAR_CONTAINER, -1L, -1L, null, -1L);
		UUID tx7 = UUID.randomUUID();
		ContainerTransferJournal granular = ContainerTransferJournal.granular(
				tx7, region, "minecraft:overworld", 6L, 7L,
				ContainerTransferJournal.Operation.INSERT, granularContents.totalSolidVolumeSvq(),
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN),
				granularBefore, granularAfter, 0x7abcL,
				ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(granular);
		check(granular.equals(store.read(tx7)), "granular container WAL fingerprint did not round-trip");
		check(granular.requiresGranularRegistry() && granular.requiresChemicalRegistry(),
				"reactive granular WAL did not declare both registry dependencies");
		granular.validateGranularRegistry(granularRegistry, BuiltinChemicalDefinitions.REGISTRY);
		checkThrows(IllegalArgumentException.class,
				() -> granular.validateGranularRegistry(BuiltinGranularDefinitions.createRegistry(), null),
				"reactive granular WAL was accepted without chemistry");

		ContainerTransferJournal wrongGranularFamily = ContainerTransferJournal.granular(
				UUID.randomUUID(), region, "minecraft:overworld", 7L, 8L,
				ContainerTransferJournal.Operation.INSERT, granularContents.totalSolidVolumeSvq(),
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN), granularBefore,
				materialAfter, -1L, ContainerTransferJournal.Phase.PREPARED);
		checkThrows(IOException.class, () -> store.write(wrongGranularFamily),
				"granular insertion must not converge to a liquid container family");

	}

	private static void testValidation() throws Exception {
		Path root = Files.createTempDirectory("mmm-wal-validation");
		ContainerJournalFileStore store = new ContainerJournalFileStore(root);
		ContainerTransferJournal.ItemFingerprint item = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L, null);
		ContainerTransferActor actor = ContainerTransferActor.player(
				UUID.randomUUID(), ContainerTransferActor.Hand.MAIN);

		ContainerTransferJournal badGeneration = new ContainerTransferJournal(
				UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 0L, 2L,
				ContainerTransferJournal.Operation.INSERT, 1L, actor, item, item,
				ContainerTransferJournal.Phase.PREPARED
		);
		checkThrows(IOException.class, () -> store.write(badGeneration), "bad generation must be rejected");
		ContainerTransferJournal overflowGeneration = new ContainerTransferJournal(
				UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", Long.MAX_VALUE, Long.MIN_VALUE,
				ContainerTransferJournal.Operation.INSERT, 1L, actor, item, item,
				ContainerTransferJournal.Phase.PREPARED
		);
		checkThrows(IOException.class, () -> store.write(overflowGeneration),
				"overflowed generation pair must be rejected");

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

		ContainerTransferJournal valid = journal(ContainerTransferJournal.Phase.PREPARED);
		store.write(valid);
		Path validPath = store.directoryPath().resolve(valid.transactionId() + ".nbt");
		CompoundTag encoded = NbtIo.readCompressed(validPath, NbtAccounter.create(4L * 1024L * 1024L));
		encoded.putLong("FutureInventory", 1L);
		NbtIo.writeCompressed(encoded, validPath);
		checkThrows(IOException.class, () -> store.read(valid.transactionId()),
				"unknown WAL fields must be preserved as unreadable instead of ignored");
	}

	private static void testDelete() throws Exception {
		Path root = Files.createTempDirectory("mmm-wal-delete");
		ContainerJournalFileStore store = new ContainerJournalFileStore(root);
		UUID tx = UUID.randomUUID();
		ContainerTransferJournal.ItemFingerprint item = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L, null);
		ContainerTransferJournal journal = new ContainerTransferJournal(
				tx, UUID.randomUUID(), "minecraft:overworld", 0L, 1L,
				ContainerTransferJournal.Operation.EXTRACT, 1L,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN), item, item,
				ContainerTransferJournal.Phase.PREPARED
		);
		store.write(journal);
		store.delete(tx);
		check(!store.discover().hasAny(), "store should be empty after deleting the only transaction");
	}

	private static void testRecoveryStateMachine() {
		ContainerTransferJournal prepared = journal(ContainerTransferJournal.Phase.PREPARED);
		ContainerTransferJournal committed = new ContainerTransferJournal(
				prepared.transactionId(), prepared.regionId(), prepared.dimensionId(),
				prepared.baseGeneration(), prepared.targetGeneration(), prepared.operation(), prepared.volumeNanos(),
				prepared.actor(), prepared.beforeItem(),
				new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.WATER_BUCKET, -1L, null),
				0x6789L,
				ContainerTransferJournal.Phase.REGION_COMMITTED);
		check(ContainerRecoveryCoordinator.decide(4L, 0L, prepared,
				CrossDomainRecovery.ExternalTruth.BEFORE).outcome()
				== CrossDomainRecovery.Outcome.DISCARD_UNCOMMITTED,
				"clean base HEAD with an exact before actor must discard an uncommitted WAL");
		check(ContainerRecoveryCoordinator.decide(4L, 0L, committed,
				CrossDomainRecovery.ExternalTruth.AFTER).reason()
				== CrossDomainRecovery.Reason.BASE_EXTERNAL_CHANGED,
				"base HEAD accepted an actor that had already changed to after");
		check(ContainerRecoveryCoordinator.decide(5L, 0x6789L, committed,
				CrossDomainRecovery.ExternalTruth.BEFORE).outcome()
				== CrossDomainRecovery.Outcome.CONVERGE_AFTER,
				"target HEAD plus committed journal did not converge a before actor");
		check(ContainerRecoveryCoordinator.decide(5L, 0x6788L, committed,
				CrossDomainRecovery.ExternalTruth.AFTER).reason()
				== CrossDomainRecovery.Reason.TARGET_CHECKSUM_MISMATCH,
				"target HEAD with a different manifest checksum did not lock");
		check(CrossDomainRecovery.decide(new CrossDomainRecovery.Boundary(
				5L, 0x6789L, 4L, 5L, false, -1L,
				CrossDomainRecovery.JournalPhase.REGION_COMMITTED, false),
				List.of(CrossDomainRecovery.ExternalTruth.AFTER)).reason()
				== CrossDomainRecovery.Reason.TARGET_CHECKSUM_MISSING,
				"target HEAD without a journal checksum did not lock as missing evidence");

		int combinations = 0;
		for (CrossDomainRecovery.JournalPhase phase : CrossDomainRecovery.JournalPhase.values()) {
			for (int headCase = 0; headCase < 4; headCase++) {
				for (boolean dirty : new boolean[] {false, true}) {
					for (CrossDomainRecovery.ExternalTruth first : CrossDomainRecovery.ExternalTruth.values()) {
						for (CrossDomainRecovery.ExternalTruth second : CrossDomainRecovery.ExternalTruth.values()) {
							long head = headCase == 0 ? 4L : headCase <= 2 ? 5L : 6L;
							long checksum = headCase == 2 ? 0x6788L : 0x6789L;
							CrossDomainRecovery.Decision actual = CrossDomainRecovery.decide(
									new CrossDomainRecovery.Boundary(head, checksum, 4L, 5L,
											phase == CrossDomainRecovery.JournalPhase.REGION_COMMITTED, 0x6789L,
											phase, dirty), List.of(first, second));
							CrossDomainRecovery.Outcome expected;
							if (headCase == 3 || headCase > 0 && phase == CrossDomainRecovery.JournalPhase.PREPARED
									|| headCase == 2 || headCase == 0 && dirty) {
								expected = CrossDomainRecovery.Outcome.LOCKED;
							} else if (headCase == 0 && (first == CrossDomainRecovery.ExternalTruth.AFTER
									|| first == CrossDomainRecovery.ExternalTruth.THIRD
									|| second == CrossDomainRecovery.ExternalTruth.AFTER
									|| second == CrossDomainRecovery.ExternalTruth.THIRD)) {
								expected = CrossDomainRecovery.Outcome.LOCKED;
							} else if (headCase == 1 && (first == CrossDomainRecovery.ExternalTruth.THIRD
									|| second == CrossDomainRecovery.ExternalTruth.THIRD)) {
								expected = CrossDomainRecovery.Outcome.LOCKED;
							} else if (first == CrossDomainRecovery.ExternalTruth.UNAVAILABLE
									|| second == CrossDomainRecovery.ExternalTruth.UNAVAILABLE) {
								expected = CrossDomainRecovery.Outcome.WAIT_UNAVAILABLE;
							} else {
								expected = headCase == 0 ? CrossDomainRecovery.Outcome.DISCARD_UNCOMMITTED
									: CrossDomainRecovery.Outcome.CONVERGE_AFTER;
							}
							check(actual.outcome() == expected, "cross-domain matrix mismatch for "
									+ phase + "/head=" + headCase + "/dirty=" + dirty + "/" + first + "/" + second);
							if (actual.outcome() == CrossDomainRecovery.Outcome.CONVERGE_AFTER) {
								List<Integer> expectedBefore = first == CrossDomainRecovery.ExternalTruth.BEFORE
										? second == CrossDomainRecovery.ExternalTruth.BEFORE ? List.of(0, 1) : List.of(0)
										: second == CrossDomainRecovery.ExternalTruth.BEFORE ? List.of(1) : List.of();
								List<Integer> expectedAfter = first == CrossDomainRecovery.ExternalTruth.AFTER
										? second == CrossDomainRecovery.ExternalTruth.AFTER ? List.of(0, 1) : List.of(0)
										: second == CrossDomainRecovery.ExternalTruth.AFTER ? List.of(1) : List.of();
								check(actual.beforeMembers().equals(expectedBefore) && actual.afterMembers().equals(expectedAfter),
										"convergence decision changed canonical member order");
							}
							combinations++;
						}
					}
				}
			}
		}
		check(combinations == 256, "cross-domain recovery matrix was incomplete");
	}

	private static void testUnknownLayout() throws Exception {
		Path root = Files.createTempDirectory("mmm-wal-unknown");
		ContainerJournalFileStore store = new ContainerJournalFileStore(root);
		ContainerTransferJournal journal = journal(ContainerTransferJournal.Phase.PREPARED);
		store.write(journal);
		Path unknown = store.directoryPath().resolve("future-format.bin");
		Files.writeString(unknown, "foreign data");
		ContainerJournalFileStore.Scan scan = store.discover();
		check(scan.transactions().equals(List.of(journal.transactionId())) && !scan.issues().isEmpty(),
				"strict scan did not retain the known WAL while reporting foreign content");
		checkThrows(IOException.class, () -> store.write(journal), "unknown content must refuse WAL replacement");
		checkThrows(IOException.class, () -> store.delete(journal.transactionId()), "unknown content must refuse deletion");
		check(Files.exists(unknown), "unknown content was deleted");

		Files.delete(unknown);
		Path staged = store.directoryPath().resolve(journal.transactionId() + ".nbt.tmp");
		Files.writeString(staged, "interrupted replacement");
		check(!store.discover().issues().isEmpty() && Files.exists(staged),
				"interrupted WAL replacement was ignored or deleted");
	}

	private static void testCorruptWal() throws Exception {
		Path root = Files.createTempDirectory("mmm-wal-corrupt");
		ContainerJournalFileStore store = new ContainerJournalFileStore(root);
		UUID transactionId = UUID.randomUUID();
		Files.createDirectories(store.directoryPath());
		Path corrupt = store.directoryPath().resolve(transactionId + ".nbt");
		byte[] bytes = {1, 3, 3, 7};
		Files.write(corrupt, bytes);
		ContainerJournalFileStore.Scan scan = store.discover();
		check(scan.transactions().isEmpty() && !scan.issues().isEmpty() && scan.hasAny(),
				"corrupt named WAL did not become a global lock");
		check(java.util.Arrays.equals(bytes, Files.readAllBytes(corrupt)), "corrupt WAL was rewritten");
	}

	private static ContainerTransferJournal journal(ContainerTransferJournal.Phase phase) {
		ContainerTransferJournal.ItemFingerprint item = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.BUCKET, -1L, null);
		return new ContainerTransferJournal(
				UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", 4L, 5L,
				ContainerTransferJournal.Operation.EXTRACT, 1_000_000_000L,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN), item, item, phase);
	}

	private static void testNanosCellConversion() {
		check(MinecraftContainerTransferOperations.nanosToCellVolume(1_000_000_000L) == 8.0F,
				"one bucket must convert to eight solver cell volumes");
		check(MinecraftContainerTransferOperations.cellVolumeToNanos(8.0) == 1_000_000_000L,
				"eight solver cell volumes must convert back to one bucket");
		check(MinecraftContainerTransferOperations.nanosToCellVolume(500_000_000L) == 4.0F,
				"half a bucket must convert to four solver cell volumes");
		check(MinecraftContainerTransferOperations.cellVolumeToNanos(1.0) == 125_000_000L,
				"one solver cell volume must be one eighth of a bucket");
	}

	private static void testActualMarkerSettlement() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
		for (long requested : new long[] {1L, 59L, 333_333_333L, 999_999_999L, 1_000_000_000L}) {
			float plannedVolume = MinecraftContainerTransferOperations.nanosToCellVolumeAtMost(requested);
			long plannedNanos = MinecraftContainerTransferOperations.cellVolumeToNanos(plannedVolume);
			check(plannedNanos > 0L && plannedNanos <= requested,
					"downward request quantization exceeded its item balance for " + requested);

			FluidDomain domain = new FluidDomain(6, 4, 6, config, 1_024);
			FluidDomain.ExactVolumeTransfer inserted = domain.tryInsertExactVolume(3, 2, 3, plannedVolume, 3);
			check(inserted.transferred(), "edge-volume insertion failed for " + requested);
			long insertedNanos = MinecraftContainerTransferOperations.settledNanos(inserted.volume(), requested);
			check(insertedNanos > 0L && insertedNanos <= requested,
					"actual insertion exceeded the source item for " + requested);

			long extractRequest = MinecraftContainerTransferOperations.extractableNanos(
					domain, new int[] {3, 2, 3}, ContainerFluidLedger.NANOS_PER_BLOCK, 3, 0);
			FluidDomain.ExactVolumeTransfer extracted = domain.tryExtractExactVolume(
					3, 2, 3, MinecraftContainerTransferOperations.nanosToCellVolumeAtMost(extractRequest), 3, 0);
			check(extracted.transferred(), "edge-volume extraction failed for " + requested);
			long extractedNanos = MinecraftContainerTransferOperations.settledNanos(extracted.volume(), extractRequest);
			check(extractedNanos == insertedNanos && domain.totalVolumeExact() == 0.0,
					"actual marker settlement did not round-trip for " + requested + ": inserted="
							+ insertedNanos + ", extracted=" + extractedNanos + ", residual=" + domain.totalVolumeExact());
		}
	}

	private static void testMaterialSettlement() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(6, 4, 6, config, 1_024);
		domain.setVolume(1, 1, 1, 0.125F);
		FlipParticleState pure = domain.exportParticleState();
		List<FluidMaterial> materials = List.of(
				new FluidMaterial("mmm:water", 1_000.0, "mmm:aqueous", 0.0, Math.PI / 2.0),
				new FluidMaterial("mmm:oil", 800.0, "mmm:oil", 0.0, Math.PI / 2.0));
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(),
				domain.totalVolumeExact(), 125.0, materials, pure.copyPositionVolume(),
				pure.copyParticleVelocities(), pure.copySolidMask(), new double[] {125.0}, new int[] {0}));
		domain.removeAllWaterExact();

		FluidMixture source = new FluidMixture(List.of(
				new FluidMixture.Component("mmm:oil", 333_333_333L, 266.6666664),
				new FluidMixture.Component("mmm:water", 666_666_666L, 666.666666)));
		List<FluidDomain.MaterialPortion> planned = MinecraftContainerTransferOperations.planMaterialInsertion(domain, source);
		FluidDomain.ExactMixtureTransfer inserted = domain.tryInsertExactMixture(3, 2, 3, planned, 3);
		check(inserted.transferred(), "non-float-friendly material insertion failed");
		MinecraftContainerTransferOperations.MaterialInsertionSettlement settlement =
				MinecraftContainerTransferOperations.settleMaterialInsertion(domain, source, inserted.portions());
		check(settlement.insertedNanos() > 0L && settlement.insertedNanos() <= source.totalVolumeNanos(),
				"material insertion exceeded its source mixture");
		long remainingNanos = settlement.remaining() == null ? 0L : settlement.remaining().totalVolumeNanos();
		check(settlement.insertedNanos() + remainingNanos == source.totalVolumeNanos(),
				"material insertion did not retain every component residual quantum");

		long request = MinecraftContainerTransferOperations.extractableNanos(
				domain, new int[] {3, 2, 3}, ContainerFluidLedger.NANOS_PER_BLOCK, 3, -1);
		FluidDomain.ExactMixtureTransfer extracted = domain.tryExtractExactMixture(
				3, 2, 3, MinecraftContainerTransferOperations.nanosToCellVolumeAtMost(request), 3);
		check(extracted.transferred(), "material extraction failed after service-planned insertion");
		FluidMixture bucket = MinecraftContainerTransferOperations.mixtureFromExtraction(domain, extracted.portions(), request);
		check(bucket.totalVolumeNanos() == settlement.insertedNanos() && domain.totalVolumeExact() == 0.0,
				"material extraction credited the request instead of actual per-material marker deltas");
		check(Math.abs(bucket.totalMass() + (settlement.remaining() == null ? 0.0 : settlement.remaining().totalMass())
				- source.totalMass()) <= 1.0E-12,
				"material insertion/extraction lost the independent mass ledger");
	}

	private static void testPartialOutlet() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(4, 4, 4, config, 64);
		domain.tryInsertExactVolume(2, 2, 2, 0.5F, 1);
		long requested = MinecraftContainerTransferOperations.extractableNanos(domain, new int[] {2, 2, 2},
				cc.ecisr.mmm.transfer.ContainerFluidLedger.NANOS_PER_BLOCK, 1, 0);
		check(requested == 62_500_000L, "partial outlet did not quantize the available half-cell volume");
		FluidDomain.ExactVolumeTransfer transfer = domain.tryExtractExactVolume(
				2, 2, 2, MinecraftContainerTransferOperations.nanosToCellVolume(requested), 1, 0);
		check(transfer.transferred() && domain.totalVolumeExact() == 0.0,
				"partial outlet request could not be settled by the exact primitive");
	}

	private static void testOwnershipCheckpointLock() throws Exception {
		Path root = Files.createTempDirectory("mmm-ownership-checkpoint-lock");
		try {
			FluidSolverConfig config = new FluidSolverConfig(
					0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
			UUID regionId = UUID.randomUUID();
			BlockPos origin = BlockPos.ZERO;
			BlockPos controller = new BlockPos(-1, 0, 0);
			FluidRegionFootprint footprint = new FluidRegionFootprint(
					controller, origin, origin, Set.of(origin.asLong()), Set.of(controller.asLong()),
					Set.of(ChunkPos.pack(0, 0)));
			FluidDomain domain = new FluidDomain(2, 2, 2, config, 64);
			domain.setVolume(1, 1, 1, 0.25F);
			FluidRegion region = new FluidRegion(regionId, DIMENSION,
					origin, domain, 64L, footprint);
			RegionRepositoryAdapter persistence = new RegionRepositoryAdapter(root, TestProfiles.materials(), null, null, null, null);
			check(persistence.checkpoint(region) == 1L, "fixture did not publish its first checkpoint");

			Path ownershipDirectory = root.resolve("mmm").resolve("ownership").resolve(regionId.toString());
			Files.createDirectories(ownershipDirectory);
			Files.writeString(ownershipDirectory.resolve("WARNING.txt"), "unfinished ownership transition");
			check(persistence.ownershipLockDescription(region) != null,
					"loaded region did not expose its ownership sidecar lock");
			checkThrows(IOException.class, () -> persistence.checkpoint(region),
					"loaded region published a checkpoint through an ownership lock");
			check(region.checkpointGeneration() == 1L && persistence.committedGeneration(regionId) == 1L,
					"refused checkpoint advanced the live or durable generation");
		} finally {
			try (var paths = Files.walk(root)) {
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}

	private static void testCommittedTargetAdoption() throws Exception {
		Path root = Files.createTempDirectory("mmm-container-target-adoption");
		try {
			FluidSolverConfig config = new FluidSolverConfig(
					0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
			UUID regionId = UUID.randomUUID();
			BlockPos origin = BlockPos.ZERO;
			BlockPos controller = new BlockPos(-1, 0, 0);
			FluidRegionFootprint footprint = new FluidRegionFootprint(
					controller, origin, origin, Set.of(origin.asLong()), Set.of(controller.asLong()),
					Set.of(ChunkPos.pack(0, 0)));
			FluidDomain pure = new FluidDomain(2, 2, 2, config, 64);
			pure.setVolume(1, 1, 1, 0.25F);
			FlipParticleState particles = pure.exportParticleState();
			float[] positionVolume = particles.copyPositionVolume();
			double[] masses = new double[particles.particleCount()];
			int[] materials = new int[particles.particleCount()];
			double totalMass = 0.0;
			for (int marker = 0; marker < masses.length; marker++) {
				masses[marker] = positionVolume[marker * 4 + 3] * config.density();
				totalMass += masses[marker];
			}
			FluidDomain base = new FluidDomain(2, 2, 2, config, 64);
			base.restoreMaterialParticleState(new FlipMaterialParticleState(
					2, 2, 2, config.cellSize(), particles.particleCount(), particles.totalVolume(), totalMass,
					BuiltinFluidMaterials.DEFAULT_PALETTE, positionVolume, particles.copyParticleVelocities(),
					particles.copySolidMask(), masses, materials));
			FluidRegion region = new FluidRegion(regionId, DIMENSION, origin, base, 64L, footprint);
			RegionRepositoryAdapter persistence = new RegionRepositoryAdapter(
					root, TestProfiles.materials(), null, null, null, null);
			long baseGeneration = persistence.checkpoint(region);
			FluidDomain candidate = FluidDomain.restoreAuthoritativeState(base.captureAuthoritativeState());
			check(candidate.tryInsertExactVolume(1, 1, 1, 0.125F, 1).transferred(),
					"fixture could not form a distinct private container target");
			double targetVolume = candidate.totalVolumeExact();
			long targetGeneration = baseGeneration + 1L;
			RegionRepositoryAdapter.StagedCheckpoint staged = persistence.stageCandidateCheckpoint(
					region, candidate, targetGeneration, region.paused());
			check(persistence.commitStagedLifecycleCheckpoint(staged) == targetGeneration,
					"fixture did not publish the private target HEAD");
			check(region.checkpointGeneration() == baseGeneration
					&& region.domain().totalVolumeExact() != targetVolume,
					"HEAD publication prematurely changed the live base solver");
			long solverGenerationBefore = region.solverGeneration();
			persistence.adoptCommittedContainerCheckpoint(region, baseGeneration, targetGeneration);
			check(region.checkpointGeneration() == targetGeneration
					&& region.domain().totalVolumeExact() == targetVolume
					&& region.solverGeneration() == solverGenerationBefore + 1L,
					"recovery did not replace live base with the exact committed target");
		} finally {
			try (var paths = Files.walk(root)) {
				for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
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
