package mmm.simulation;

import mmm.fluid.FluidDomainContracts;

import mmm.application.ContainerRecoveryCoordinator;
import mmm.simulation.persistence.ContainerJournalFileStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import mmm.transfer.ContainerFluidLedger;
import mmm.transfer.ChemicalContents;
import mmm.transfer.ContainerTransferActor;
import mmm.transfer.ContainerTransferJournal;
import mmm.transfer.CreativeSourceDescriptor;
import mmm.transfer.FluidMixture;
import mmm.transfer.GranularContents;
import mmm.transfer.ThermalContents;
import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalLimits;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.SolidCompositionRecord;
import mmm.granular.SolidVolume;
import mmm.fluid.HostFluidState;
import mmm.fluid.FluidStepRequest;
import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.FlipMaterialParticleState;
import mmm.fluid.FlipParticleState;
import mmm.fluid.FlipThermalParticleState;
import mmm.fluid.FluidMaterial;
import mmm.fluid.FluidSolverConfig;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalStateFunction;
import mmm.thermal.ThermalAuditLedger;
import mmm.transfer.LiquidContainerThermodynamics;
import mmm.units.SignedDyadic;
import mmm.recovery.CrossDomainRecovery;
import mmm.simulation.controller.FluidRegionFootprint;

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
		if (args.length == 1 && args[0].equals("--journal-thermal")) {
			testRoundTrip(); testThermalWal(); testPublicTransfers(); testActorCodecs();
			System.out.println("Thermal container WAL and public transfer validation passed"); return;
		}

		if(args.length==1 && args[0].equals("--public-transfers")) {
			testPublicTransfers();System.out.println("Public transfer durability passed");return;
		}


		Path root = Files.createTempDirectory("mmm-container-wal-test");
		try {
			run("round-trip and atomic replace", ContainerTransferStoreValidation::testRoundTrip);
			run("thermal WAL couples item and region energy", ContainerTransferStoreValidation::testThermalWal);
			run("creative gas WAL preserves direction and actual settlement", ContainerTransferStoreValidation::testGasWal);
			run("player and dispenser actor codecs", ContainerTransferStoreValidation::testActorCodecs);
			run("public transfer actor generations and durable replay receipts",ContainerTransferStoreValidation::testPublicTransfers);
			run("invalid generations and dimensions are rejected", ContainerTransferStoreValidation::testValidation);
			run("delete removes the transaction", ContainerTransferStoreValidation::testDelete);
			run("recovery state machine locks every ambiguous commit boundary", ContainerTransferStoreValidation::testRecoveryStateMachine);
			run("unknown and staged files lock without deletion", ContainerTransferStoreValidation::testUnknownLayout);
			run("corrupt named WAL locks without deletion", ContainerTransferStoreValidation::testCorruptWal);
			run("nanovolume and solver cell-volume conversion", ContainerTransferStoreValidation::testNanosCellConversion);
			run("non-float-friendly transfers settle actual marker volume", ContainerTransferStoreValidation::testActualMarkerSettlement);
			run("material service settlement preserves component residuals", ContainerTransferStoreValidation::testMaterialSettlement);
			run("tiny thermal item remainder and actual insertion audit agree", ContainerTransferStoreValidation::testTinyThermalMaterialSettlement);
			run("chemical insertion preserves complete integer mass and residual columns", ContainerTransferStoreValidation::testChemicalInsertionMassConservation);
			run("existing partial bucket rounds actual extracted energy only at final item", ContainerTransferStoreValidation::testExistingBucketThermalSettlement);
			run("partial outlet requests only available marker volume", ContainerTransferStoreValidation::testPartialOutlet);
            run("small chemical sample retains exact inventory", ContainerTransferStoreValidation::testSmallChemicalSample);
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

	private static void testPublicTransfers() throws Exception {
		double waterDensity=mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material().referenceDensity();
		var root=Files.createTempDirectory("mmm-public-transfer-");
		var receipts=new mmm.simulation.persistence.PublicTransferReceiptStore(root);
		var address=new mmm.api.transfer.BlockInventoryAddress("minecraft:overworld",1,2,3,0);
		var ref=address.actorRef("first");var instance=UUID.randomUUID();var region=UUID.randomUUID();var operation=UUID.randomUUID();
		var binding=new mmm.simulation.persistence.PublicTransferReceiptStore.Binding(ref,address,instance);
		receipts.bind(binding);
		var actor=new ContainerTransferActor.BlockInventory(address.dimensionId(),1,2,3,0,instance,3);
		var before=new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.WATER_BUCKET,-1,null);
		var after=new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.PARTIAL,750_000_000L,null);
		var request=new mmm.api.transfer.TransferRequest(operation,ref,3,region,address.dimensionId(),2.25,3.25,4.25,
				mmm.api.transfer.TransferRequest.Direction.INSERT,new mmm.api.transfer.TransferQuantity.Liquid(250_000_000L,java.util.OptionalDouble.empty()));
		var preview=new ContainerTransferJournal(operation,region,address.dimensionId(),4,5,ContainerTransferJournal.Operation.INSERT,
				250_000_000L,actor,before,after,0,ContainerTransferJournal.Phase.REGION_COMMITTED);
		receipts.prepare(request,binding,preview);
		var manifest=mmm.transfer.ContainerTransferManifests.from(preview,waterDensity);
		check(manifest.components().getFirst().amount()==250_000_000L
				&& manifest.components().getFirst().massUnits()==mmm.units.Mass.fromKilograms(.25*waterDensity),"Public manifest lost actual partial inventory");
		check(MinecraftContainerActorAdapter.classifyInventory(before,new mmm.transfer.TransferActorState(instance,3),preview,false)==CrossDomainRecovery.ExternalTruth.BEFORE,
				"Actor before generation was not recognized");
		check(MinecraftContainerActorAdapter.classifyInventory(after,new mmm.transfer.TransferActorState(instance,4),preview,true)==CrossDomainRecovery.ExternalTruth.AFTER,
				"Actor after generation was not recognized");
		check(MinecraftContainerActorAdapter.classifyInventory(after,new mmm.transfer.TransferActorState(instance,3),preview,false)==CrossDomainRecovery.ExternalTruth.THIRD
				&& MinecraftContainerActorAdapter.classifyInventory(after,new mmm.transfer.TransferActorState(UUID.randomUUID(),4),preview,false)==CrossDomainRecovery.ExternalTruth.THIRD,
				"Old generation or replaced inventory was accepted as committed actor");
		var wal=new ContainerJournalFileStore(root);long[] head={4};boolean[] afterSaved={false},failReceipt={true};int[] writes={0},events={0};
		var recoveryPort=new ContainerRecoveryCoordinator.RecoveryPort() {
			public void validate(ContainerTransferJournal journal)throws IOException { receipts.validate(journal); }
			public long committedGeneration(UUID id) { return head[0]; }
			public long committedManifestChecksum(UUID id) { return 1234; }
			public CrossDomainRecovery.ExternalTruth classifyActor(ContainerTransferJournal journal) { return afterSaved[0]?CrossDomainRecovery.ExternalTruth.AFTER:CrossDomainRecovery.ExternalTruth.BEFORE; }
			public ContainerRecoveryCoordinator.Convergence applyAfterItem(ContainerTransferJournal journal) { afterSaved[0]=true;writes[0]++;return ContainerRecoveryCoordinator.Convergence.APPLIED; }
			public void persistAfterItem(ContainerTransferActor ignored) { }
			public CrossDomainRecovery.ExternalTruth verifyAfterItem(ContainerTransferJournal journal) { return afterSaved[0]?CrossDomainRecovery.ExternalTruth.AFTER:CrossDomainRecovery.ExternalTruth.BEFORE; }
			public void adoptRecoveredCheckpoint(ContainerTransferJournal journal) { }
			public void persistReceipt(ContainerTransferJournal journal,boolean committed)throws IOException {
				if(failReceipt[0]) { failReceipt[0]=false;throw new IOException("Injected durable receipt publication failure"); }
				receipts.complete(journal,committed);
			}
			public void publishCommitted(ContainerTransferJournal journal) { events[0]++; }
			public void report(ContainerRecoveryCoordinator.Report report,String detail,Throwable failure) { }
		};
		var recovery=new ContainerRecoveryCoordinator(wal,recoveryPort,java.util.Map.of(),Set.of());
		var coordinator=new mmm.application.ContainerTransferCoordinator(recovery,new mmm.application.ContainerTransferCoordinator.RegionPort() {
			public mmm.application.ContainerTransferCoordinator.Execution execute(mmm.application.ContainerTransferUseCase.Command command) {
				return new mmm.application.ContainerTransferCoordinator.Execution(true,after,"Partial transfer",250_000_000L,null);
			}
			public mmm.application.ContainerTransferCoordinator.StagedCheckpoint stage(UUID transaction,UUID id,long generation) { return ()->1234; }
			public void abort(mmm.application.ContainerTransferCoordinator.StagedCheckpoint staged) { }
			public long commit(mmm.application.ContainerTransferCoordinator.StagedCheckpoint staged) { head[0]=5;return 5; }
			public void lock(UUID id,String detail) { throw new AssertionError(detail); }
		});
		var outcome=new mmm.application.ContainerTransferUseCase(coordinator).execute(new mmm.application.ContainerTransferUseCase.Command(
				region,address.dimensionId(),4,false,PROFILE,null,actor,before,before,ContainerTransferJournal.Operation.INSERT,
				ContainerTransferJournal.ResourceKind.FLUID_NANOS,250_000_000L,0,operation));
		check(outcome.committed() && operation.equals(outcome.transactionId()) && wal.read(operation).transactionId().equals(operation),
				"Caller operation UUID did not traverse the existing coordinator and WAL");
		recovery.retryTransaction(operation);
		check(recovery.hasPending(region) && wal.read(operation)!=null && writes[0]==1 && events[0]==0
				&& receipts.entry(operation).state()==mmm.simulation.persistence.PublicTransferReceiptStore.State.PENDING,
				"Receipt I/O failure deleted WAL, repeated inventory writes, or published completion");
		recovery.retryTransaction(operation);
		check(!recovery.hasPending(region) && writes[0]==1 && events[0]==1,"Receipt retry did not finish exactly one inventory commit");
		var restarted=new mmm.simulation.persistence.PublicTransferReceiptStore(root);
		check(restarted.entry(operation).receipt(waterDensity).manifest().equals(manifest) && restarted.entry(operation).receipt(waterDensity).actorAfterGeneration()==4,
				"Restart lost the actual manifest or durable actor generation");
		var next=UUID.randomUUID();var actor2=new ContainerTransferActor.BlockInventory(address.dimensionId(),1,2,3,0,instance,4);
		var after2=new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.PARTIAL,500_000_000L,null);
		var request2=new mmm.api.transfer.TransferRequest(next,ref,4,region,address.dimensionId(),2.25,3.25,4.25,request.direction(),request.quantity());
		var settlement2=new ContainerTransferJournal(next,region,address.dimensionId(),5,6,ContainerTransferJournal.Operation.INSERT,250_000_000L,
				actor2,after,after2,1235,ContainerTransferJournal.Phase.REGION_COMMITTED);
		restarted.prepare(request2,binding,settlement2);restarted.complete(settlement2,true);
		Files.writeString(root.resolve("mmm/public-transfer/unpublished.operation.nbt.tmp"),"unpublished");
		var bounded=new mmm.simulation.persistence.PublicTransferReceiptStore(root);
		check(bounded.entry(operation)==null && bounded.entry(next).receipt(waterDensity).committed() && bounded.entries().size()==1,
				"Latest receipt retention grew operation history or forgot the latest result");
		var pendingId=UUID.randomUUID();
		var pendingRequest=new mmm.api.transfer.TransferRequest(pendingId,ref,4,region,address.dimensionId(),2.25,3.25,4.25,request.direction(),request.quantity());
		var pendingSettlement=new ContainerTransferJournal(pendingId,region,address.dimensionId(),5,6,ContainerTransferJournal.Operation.INSERT,250_000_000L,
				actor2,after,after2,1235,ContainerTransferJournal.Phase.REGION_COMMITTED);
		bounded.prepare(pendingRequest,binding,pendingSettlement);
		var damagedRecovery=new ContainerRecoveryCoordinator(wal,recoveryPort,java.util.Map.of(),Set.of("unreadable container WAL"));
		FabricTransferApi.reconcilePendingReceipts(bounded,damagedRecovery);
		check(bounded.entry(pendingId).state()==mmm.simulation.persistence.PublicTransferReceiptStore.State.PENDING,
				"Damaged WAL discovery misclassified a pending receipt");
		FabricTransferApi.reconcilePendingReceipts(bounded,recovery);
		check(bounded.entry(pendingId).state()==mmm.simulation.persistence.PublicTransferReceiptStore.State.NOT_COMMITTED,
				"Healthy WAL discovery did not resolve an absent transaction");
		var replacement=new mmm.simulation.persistence.PublicTransferReceiptStore.Binding(address.actorRef("replacement"),address,UUID.randomUUID());
		bounded.bind(replacement);
		check(bounded.binding(ref).equals(binding) && bounded.binding(replacement.actor()).equals(replacement),"New inventory rebound the old actor ref");
		checkThrows(IOException.class,()->bounded.bind(new mmm.simulation.persistence.PublicTransferReceiptStore.Binding(ref,address,UUID.randomUUID())),
				"Existing actor ref was rebound to a different incarnation");
	}

	private static void testGasWal() throws Exception {
		Path directory = Files.createTempDirectory("mmm-gas-wal");
		ContainerJournalFileStore store = new ContainerJournalFileStore(directory);
		var actor = ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN);
		var source = new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.CREATIVE_SOURCE,
				-1L, -1L, new CreativeSourceDescriptor("mmm:source/gas/co2", 1_000_000_000L, 310.0).encode(), -1L, null, null);
		for (var operation : ContainerTransferJournal.Operation.values()) {
			UUID tx = UUID.randomUUID(), region = UUID.randomUUID();
			var prepared = new ContainerTransferJournal(tx, region, "minecraft:overworld", 3, 4,
					operation, ContainerTransferJournal.ResourceKind.GAS_STANDARD_NANOS, 1_000_000_000L, -1,
					new ContainerTransferJournal.ThermalTransfer(PROFILE, 0.0), actor, source, source, -1,
					ContainerTransferJournal.Phase.PREPARED);
			store.write(prepared);
			check(store.read(tx).equals(prepared), "gas PREPARED identity did not round-trip");
			var committed = new ContainerTransferJournal(tx, region, "minecraft:overworld", 3, 4,
					operation, ContainerTransferJournal.ResourceKind.GAS_STANDARD_NANOS, 400_000_000L, -1,
					new ContainerTransferJournal.ThermalTransfer(PROFILE, 1234.5), actor, source, source, 0x1188,
					ContainerTransferJournal.Phase.REGION_COMMITTED);
			store.write(committed);
			check(store.read(tx).equals(committed), "gas actual direction/volume/energy did not round-trip");
			check(store.read(tx).beforeItem().equals(store.read(tx).afterItem()),
					"creative gas invented item-side inventory");
			store.delete(tx);
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
		var vanilla = new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.WATER_BUCKET, -1L, null);
		UUID vanillaTransaction = UUID.randomUUID();
		var vanillaPrepared = new ContainerTransferJournal(vanillaTransaction, region, "minecraft:overworld", 7L, 8L,
				ContainerTransferJournal.Operation.INSERT, ContainerTransferJournal.ResourceKind.FLUID_NANOS,
				ContainerFluidLedger.NANOS_PER_BLOCK, -1L, transfer, prepared.actor(), vanilla, vanilla, -1L,
				ContainerTransferJournal.Phase.PREPARED, before);
		store.write(vanillaPrepared);
		check(store.read(vanillaTransaction).equals(vanillaPrepared), "Vanilla bucket PREPARED lost its room-temperature source");
		var vanillaCommitted = new ContainerTransferJournal(vanillaTransaction, region, "minecraft:overworld", 7L, 8L,
				ContainerTransferJournal.Operation.INSERT, ContainerTransferJournal.ResourceKind.FLUID_NANOS,
				ContainerFluidLedger.NANOS_PER_BLOCK, -1L, transfer, prepared.actor(), vanilla, after, 0x7711L,
				ContainerTransferJournal.Phase.REGION_COMMITTED, before);
		store.write(vanillaCommitted);
		check(store.read(vanillaTransaction).equals(vanillaCommitted), "Vanilla bucket COMMITTED lost its source or actor identity");
			ThermalContents.EnergySplit creativeSplit = contents.splitHomogeneousEnergy(500_000_000L,
				ContainerFluidLedger.NANOS_PER_BLOCK);
		double creativeEnergy = creativeSplit.coupledEnergy(contents.internalEnergy());
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
				"mmm:source/granular/fine_sand", SolidVolume.SVQ_PER_CUBIC_METER, null);
		ContainerTransferJournal.ItemFingerprint source = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.CREATIVE_SOURCE, -1L, -1L,
				descriptor.encode(), -1L, null, null);
		UUID sourceTx = UUID.randomUUID();
		ContainerTransferJournal sourceCommitted = ContainerTransferJournal.granular(
				sourceTx, region, "minecraft:overworld", 5L, 6L,
				ContainerTransferJournal.Operation.INSERT, SolidVolume.SVQ_PER_CUBIC_METER, actor,
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
				ContainerTransferActor.blockInventory("minecraft:the_nether", 3, 4, 5, 2),
				before, before, ContainerTransferJournal.Phase.PREPARED
		);
		store.write(dispenser);
		check(dispenser.equals(store.read(tx2)), "dispenser actor WAL did not round-trip");
		checkThrows(IllegalArgumentException.class,
				() -> ContainerTransferActor.player(UUID.randomUUID(), null),
				"unknown player hand was accepted and would fall back to main hand");
		checkThrows(IllegalArgumentException.class,
				() -> ContainerTransferActor.blockInventory("minecraft:the_nether", 3, 4, 5, 256),
				"out-of-range block inventory slot was accepted");
		ContainerTransferJournal crossDimensionDispenser = new ContainerTransferJournal(
				UUID.randomUUID(), region, "minecraft:overworld", 1L, 2L,
				ContainerTransferJournal.Operation.EXTRACT, 250_000_000L,
				ContainerTransferActor.blockInventory("minecraft:the_nether", 3, 4, 5, 2),
				before, before, ContainerTransferJournal.Phase.PREPARED);
		checkThrows(IOException.class, () -> store.write(crossDimensionDispenser),
				"cross-dimension dispenser actor was accepted");

		UUID tx3 = UUID.randomUUID();
		ContainerTransferJournal.ItemFingerprint materialBefore = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.MATERIAL_BUCKET, 1_000_000_000L, -1L,
				new FluidMixture(List.of(
						new FluidMixture.Component("mmm:oil", 400_000_000L, mmm.units.Mass.fromKilograms(320.0)),
						new FluidMixture.Component("mmm:water", 600_000_000L, mmm.units.Mass.fromKilograms(600.0)))).encode(),
				mmm.units.Mass.fromKilograms(920.0), null, PROFILE);
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
				"mmm:water", mmm.units.Mass.fromKilograms(1_000.0), null, PROFILE);
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

		ChemicalContents chemicalContents = new ChemicalContents(mmm.fluid.TestFluidDefinitions.load().chemistry().fingerprint(),
				ChemicalLimits.QUANTA_PER_MOL, List.of(new ChemicalContents.Group(
						"mmm:water", ContainerFluidLedger.NANOS_PER_BLOCK,
						mmm.units.Mass.fromKilograms(18.0), mmm.units.Mass.fromKilograms(0.0),
						List.of(new ChemicalContents.SpeciesAmount(
								BuiltinChemicalDefinitions.H2O, 1_000 * ChemicalLimits.QUANTA_PER_MOL)))));
		ContainerTransferJournal.ItemFingerprint chemicalBefore = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.CHEMICAL_BUCKET, ContainerFluidLedger.NANOS_PER_BLOCK, -1L,
				chemicalContents.encode(), chemicalContents.totalMassUnits(), null, PROFILE);
		UUID tx5 = UUID.randomUUID();
		ContainerTransferJournal chemical = new ContainerTransferJournal(
				tx5, region, "minecraft:overworld", 4L, 5L,
				ContainerTransferJournal.Operation.INSERT, ContainerFluidLedger.NANOS_PER_BLOCK,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN),
				chemicalBefore, materialAfter, 0x5abcL, ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(chemical);
		check(chemical.equals(store.read(tx5)), "chemical bucket WAL fingerprint did not round-trip");
		check(chemical.requiresChemicalRegistry(), "chemical WAL did not declare its registry dependency");
		chemical.validateChemicalRegistry(mmm.fluid.TestFluidDefinitions.load().chemistry());
		ChemicalContents foreignContents = new ChemicalContents("0".repeat(64), ChemicalLimits.QUANTA_PER_MOL,
				chemicalContents.groups());
		ContainerTransferJournal.ItemFingerprint foreignChemical = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.CHEMICAL_BUCKET, ContainerFluidLedger.NANOS_PER_BLOCK, -1L,
				foreignContents.encode(), foreignContents.totalMassUnits(), null, PROFILE);
		ContainerTransferJournal foreignJournal = new ContainerTransferJournal(
				UUID.randomUUID(), region, "minecraft:overworld", 4L, 5L,
				ContainerTransferJournal.Operation.INSERT, ContainerFluidLedger.NANOS_PER_BLOCK,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN),
				foreignChemical, materialAfter, ContainerTransferJournal.Phase.PREPARED);
		checkThrows(IllegalArgumentException.class,
				() -> foreignJournal.validateChemicalRegistry(mmm.fluid.TestFluidDefinitions.load().chemistry()),
				"foreign chemical WAL fingerprint was accepted by recovery validation");
		ChemicalContents.Split chemicalPartial = chemicalContents.split(333_333_333L,
				mmm.fluid.TestFluidDefinitions.load().chemistry());
		ContainerTransferJournal.ItemFingerprint chemicalAfter = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.CHEMICAL_BUCKET, chemicalPartial.remaining().totalVolumeNanos(), -1L,
				chemicalPartial.remaining().encode(), chemicalPartial.remaining().totalMassUnits(),
				null, PROFILE);
		UUID tx6 = UUID.randomUUID();
		ContainerTransferJournal chemicalPartialJournal = new ContainerTransferJournal(
				tx6, region, "minecraft:overworld", 5L, 6L,
				ContainerTransferJournal.Operation.INSERT, chemicalPartial.inserted().totalVolumeNanos(),
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN),
				chemicalBefore, chemicalAfter, 0x6abcL, ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(chemicalPartialJournal);
		check(chemicalPartialJournal.equals(store.read(tx6)), "partial chemical WAL settlement did not round-trip");
		var tinyBefore = new ChemicalContents(chemicalContents.registryFingerprint(), ChemicalLimits.QUANTA_PER_MOL,
				List.of(new ChemicalContents.Group("mmm:water",21,195239,35279,List.of(
						new ChemicalContents.SpeciesAmount(BuiltinChemicalDefinitions.ETHANOL,66),
						new ChemicalContents.SpeciesAmount(BuiltinChemicalDefinitions.H2O,720)))));
		var tinyAfter = new ChemicalContents(chemicalContents.registryFingerprint(), ChemicalLimits.QUANTA_PER_MOL,
				List.of(new ChemicalContents.Group("mmm:water",24,213239,35279,List.of(
						new ChemicalContents.SpeciesAmount(BuiltinChemicalDefinitions.ETHANOL,66),
						new ChemicalContents.SpeciesAmount(BuiltinChemicalDefinitions.H2O,820)))));
		var tinyBeforeItem = new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.CHEMICAL_BUCKET,
				21,-1,tinyBefore.encode(),tinyBefore.totalMassUnits(),ThermalContents.forChemical(PROFILE,tinyBefore,10.0).encode(),PROFILE);
		var tinyAfterItem = new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.CHEMICAL_BUCKET,
				24,-1,tinyAfter.encode(),tinyAfter.totalMassUnits(),ThermalContents.forChemical(PROFILE,tinyAfter,13.0).encode(),PROFILE);
		var tinyActor=ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN);
		UUID tinyTx=UUID.randomUUID();
		var tinyPrepared=ContainerTransferJournal.thermalFluid(tinyTx,region,"minecraft:overworld",6,7,
				ContainerTransferJournal.Operation.EXTRACT,3,new ContainerTransferJournal.ThermalTransfer(PROFILE,0.0),
				tinyActor,tinyBeforeItem,tinyBeforeItem,-1,ContainerTransferJournal.Phase.PREPARED);
		store.write(tinyPrepared);
		check(tinyPrepared.equals(store.read(tinyTx)),"partially filled chemical bucket PREPARED did not round-trip");
		var tinyCommitted=ContainerTransferJournal.thermalFluid(tinyTx,region,"minecraft:overworld",6,7,
				ContainerTransferJournal.Operation.EXTRACT,3,new ContainerTransferJournal.ThermalTransfer(PROFILE,3.0),
				tinyActor,tinyBeforeItem,tinyAfterItem,0x6123,ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(tinyCommitted);
		check(tinyCommitted.equals(store.read(tinyTx)),"partially filled chemical bucket settlement did not round-trip");
		var receipt=mmm.transfer.ContainerTransferManifests.from(tinyCommitted,1000);
		check(receipt.components().size()==1 && receipt.components().getFirst().amount()==3
				&& receipt.components().getFirst().massUnits()==18000
				&& receipt.components().getFirst().species().equals(List.of(new mmm.api.transfer.TransferManifest.Species(BuiltinChemicalDefinitions.H2O,100)))
				&& receipt.internalEnergyJoules().orElseThrow()==3.0,"chemical receipt lost actual mass, species or energy");
		var tinyWrong=ContainerTransferJournal.thermalFluid(UUID.randomUUID(),region,"minecraft:overworld",6,7,
				ContainerTransferJournal.Operation.EXTRACT,3,new ContainerTransferJournal.ThermalTransfer(PROFILE,0.0),
				tinyActor,tinyBeforeItem,tinyBeforeItem,0x6123,ContainerTransferJournal.Phase.REGION_COMMITTED);
		checkThrows(IOException.class,()->store.write(tinyWrong),"unchanged chemical bucket accepted a committed extraction");

		ContainerTransferJournal wrongFamily = new ContainerTransferJournal(
				UUID.randomUUID(), region, "minecraft:overworld", 4L, 5L,
				ContainerTransferJournal.Operation.INSERT, 1_000_000_000L,
				ContainerTransferActor.player(UUID.randomUUID(), ContainerTransferActor.Hand.MAIN),
				materialBefore, before, ContainerTransferJournal.Phase.REGION_COMMITTED);
		checkThrows(IOException.class, () -> store.write(wrongFamily),
				"material insertion must not converge to a vanilla empty bucket");

		var granularRegistry = mmm.fluid.TestFluidDefinitions.load().granular();
		long inertSvq = SolidVolume.SVQ_PER_CELL;
		long reactiveSvq = SolidVolume.SVQ_PER_CELL / 2L;
		SolidCompositionRecord reactive = SolidCompositionRecord.fromStableAmounts(
				mmm.fluid.TestFluidDefinitions.load().chemistry(), java.util.Map.of(
						BuiltinChemicalDefinitions.NA_PLUS, 64L,
						BuiltinChemicalDefinitions.CL_MINUS, 64L), 0L);
		GranularContents granularContents = new GranularContents(granularRegistry.fingerprint(),
				SolidVolume.SVQ_PER_CUBIC_METER, List.of(
				new GranularContents.Group(BuiltinGranularDefinitions.FINE_SAND, inertSvq, mmm.units.Mass.fromKilograms(650.0)),
				new GranularContents.Group(BuiltinGranularDefinitions.PRECIPITATE, reactiveSvq,
						mmm.units.Mass.fromKilograms(reactive.totalMass(mmm.fluid.TestFluidDefinitions.load().chemistry())),
						reactive.massResidualUnits(), reactive.stableAmounts(mmm.fluid.TestFluidDefinitions.load().chemistry())
						.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
						.map(entry -> new GranularContents.SpeciesAmount(
								entry.getKey(), entry.getValue())).toList())));
		ContainerTransferJournal.ItemFingerprint granularBefore = new ContainerTransferJournal.ItemFingerprint(
				ContainerTransferJournal.ItemKind.GRANULAR_CONTAINER, -1L,
				granularContents.totalSolidVolumeSvq(), granularContents.encode(),
				granularContents.totalMassUnits(), null, PROFILE);
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
		granular.validateGranularRegistry(granularRegistry, mmm.fluid.TestFluidDefinitions.load().chemistry());
		var granularPart=granularContents.split(inertSvq / 2,mmm.fluid.TestFluidDefinitions.load().chemistry()).inserted();
		var granularPartItem=new ContainerTransferJournal.ItemFingerprint(ContainerTransferJournal.ItemKind.GRANULAR_CONTAINER,
				-1,granularPart.totalSolidVolumeSvq(),granularPart.encode(),granularPart.totalMassUnits(),null,PROFILE);
		long addedSolid=granularContents.totalSolidVolumeSvq()-granularPart.totalSolidVolumeSvq();
		UUID granularCollectTx=UUID.randomUUID();
		var granularCollectPrepared=ContainerTransferJournal.granular(granularCollectTx,region,"minecraft:overworld",6,7,
				ContainerTransferJournal.Operation.EXTRACT,addedSolid,tinyActor,granularPartItem,granularPartItem,-1,
				ContainerTransferJournal.Phase.PREPARED);
		store.write(granularCollectPrepared);
		var granularCollectCommitted=ContainerTransferJournal.granular(granularCollectTx,region,"minecraft:overworld",6,7,
				ContainerTransferJournal.Operation.EXTRACT,addedSolid,tinyActor,granularPartItem,granularBefore,0x6124,
				ContainerTransferJournal.Phase.REGION_COMMITTED);
		store.write(granularCollectCommitted);
		check(granularCollectCommitted.equals(store.read(granularCollectTx)),"partially filled granular container settlement did not round-trip");
		checkThrows(IllegalArgumentException.class,
				() -> granular.validateGranularRegistry(mmm.fluid.TestFluidDefinitions.load().granular(), null),
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

		var base = new CrossDomainRecovery.Boundary(4, 0, 4, 5, true, 0x6789,
				CrossDomainRecovery.JournalPhase.PREPARED, false);
		check(CrossDomainRecovery.decide(base, List.of(CrossDomainRecovery.ExternalTruth.BEFORE,
				CrossDomainRecovery.ExternalTruth.AFTER), CrossDomainRecovery.CommitOrder.EXTERNAL_FIRST).outcome()
				== CrossDomainRecovery.Outcome.ROLLBACK_BEFORE, "external-first partial mutation did not request rollback");
		check(CrossDomainRecovery.decide(base, List.of(CrossDomainRecovery.ExternalTruth.UNAVAILABLE),
				CrossDomainRecovery.CommitOrder.EXTERNAL_FIRST).outcome() == CrossDomainRecovery.Outcome.WAIT_UNAVAILABLE,
				"unreadable external-first member did not retain the transaction");
		check(CrossDomainRecovery.decide(base, List.of(CrossDomainRecovery.ExternalTruth.THIRD),
				CrossDomainRecovery.CommitOrder.EXTERNAL_FIRST).outcome() == CrossDomainRecovery.Outcome.LOCKED,
				"unrecognized external-first state did not retain evidence");
		check(CrossDomainRecovery.classify(true, true, true, false) == CrossDomainRecovery.ExternalTruth.BEFORE
				&& CrossDomainRecovery.classify(true, true, true, true) == CrossDomainRecovery.ExternalTruth.AFTER
				&& CrossDomainRecovery.classify(false, true, true, true) == CrossDomainRecovery.ExternalTruth.UNAVAILABLE,
				"unchanged external state classification lost mutation/readback ordering");

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
		var units = mmm.units.SolverUnitScale.forCellSize(0.5F);
		check(MinecraftContainerTransferOperations.nanosToCellVolume(units, 1_000_000_000L) == 8.0F,
				"one bucket must convert to eight solver cell volumes");
		check(MinecraftContainerTransferOperations.cellVolumeToNanos(units, 8.0) == 1_000_000_000L,
				"eight solver cell volumes must convert back to one bucket");
		check(MinecraftContainerTransferOperations.nanosToCellVolume(units, 500_000_000L) == 4.0F,
				"half a bucket must convert to four solver cell volumes");
		check(MinecraftContainerTransferOperations.cellVolumeToNanos(units, 1.0) == 125_000_000L,
				"one solver cell volume must be one eighth of a bucket");
	}

	private static void testActualMarkerSettlement() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 2.0E-3F);
		for (long requested : new long[] {1L, 59L, 333_333_333L, 999_999_999L, 1_000_000_000L}) {
			HostFluidState domain = new HostFluidState(6, 4, 6, config, mmm.fluid.TestFluidDefinitions.load());
			float plannedVolume = MinecraftContainerTransferOperations.nanosToCellVolumeAtMost(domain.unitScale(), requested);
			long plannedNanos = MinecraftContainerTransferOperations.cellVolumeToNanos(domain.unitScale(), plannedVolume);
			check(plannedNanos > 0L && plannedNanos <= requested,
					"downward request quantization exceeded its item balance for " + requested);

			FluidDomainContracts.ExactVolumeTransfer inserted = domain.tryInsertExactVolume(3, 2, 3, plannedVolume, 3);
			check(inserted.transferred(), "edge-volume insertion failed for " + requested);
			long insertedNanos = MinecraftContainerTransferOperations.settledNanos(domain.unitScale(), inserted.volume(), requested);
			check(insertedNanos > 0L && insertedNanos <= requested,
					"actual insertion exceeded the source item for " + requested);

			long extractRequest = MinecraftContainerTransferOperations.extractableNanos(
					domain, new int[] {3, 2, 3}, ContainerFluidLedger.NANOS_PER_BLOCK, 3, 0);
			FluidDomainContracts.ExactVolumeTransfer extracted = domain.tryExtractExactVolume(
					3, 2, 3, MinecraftContainerTransferOperations.nanosToCellVolumeAtMost(domain.unitScale(), extractRequest), 3, 0);
			check(extracted.transferred(), "edge-volume extraction failed for " + requested);
			long extractedNanos = MinecraftContainerTransferOperations.settledNanos(domain.unitScale(), extracted.volume(), extractRequest);
			check(extractedNanos == insertedNanos && domain.totalVolumeExact() == 0.0,
					"actual marker settlement did not round-trip for " + requested + ": inserted="
							+ insertedNanos + ", extracted=" + extractedNanos + ", residual=" + domain.totalVolumeExact());
		}
	}

	private static void testMaterialSettlement() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 2.0E-3F);
		HostFluidState domain = new HostFluidState(6, 4, 6, config, mmm.fluid.TestFluidDefinitions.load());
		domain.setVolume(1, 1, 1, 0.125F);
		FlipParticleState pure = domain.exportParticleState();
		List<FluidMaterial> materials = List.of(
				mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material(),
				mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:oil").material());
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(),
				domain.totalVolumeExact(), mmm.units.SolverUnitScale.forCellSize(pure.cellSize()).massUnitsForNormalized(125.0), materials, pure.copyPositionVolume(),
				pure.copyParticleVelocities(), pure.copySolidMask(), pure.copyMassUnits(), new int[pure.particleCount()]));
		domain.removeAllWaterExact();

		FluidMixture source = new FluidMixture(List.of(
				new FluidMixture.Component("mmm:oil", 333_333_333L, mmm.units.Mass.fromKilograms(266.6666664)),
				new FluidMixture.Component("mmm:water", 666_666_666L, mmm.units.Mass.fromKilograms(666.666666))));
		List<FluidDomainContracts.MaterialPortion> planned = MinecraftContainerTransferOperations.planMaterialInsertion(domain, source, 250_000_000L);
		FluidDomainContracts.ExactMixtureTransfer inserted = domain.tryInsertExactMixture(3, 2, 3, planned, 3);
		check(inserted.transferred(), "non-float-friendly material insertion failed");
		MinecraftContainerTransferOperations.MaterialInsertionSettlement settlement =
				MinecraftContainerTransferOperations.settleMaterialInsertion(domain, source, inserted.portions());
		check(settlement.insertedNanos() > 0L && settlement.insertedNanos() <= 250_000_000L,
				"material insertion exceeded its source mixture");
		long remainingNanos = settlement.remaining() == null ? 0L : settlement.remaining().totalVolumeNanos();
		check(settlement.insertedNanos() + remainingNanos == source.totalVolumeNanos(),
				"material insertion did not retain every component residual quantum");

		long request = MinecraftContainerTransferOperations.extractableNanos(
				domain, new int[] {3, 2, 3}, ContainerFluidLedger.NANOS_PER_BLOCK, 3, -1);
		FluidDomainContracts.ExactMixtureTransfer extracted = domain.tryExtractExactMixture(
				3, 2, 3, MinecraftContainerTransferOperations.nanosToCellVolumeAtMost(domain.unitScale(), request), 3);
		check(extracted.transferred(), "material extraction failed after service-planned insertion");
		FluidMixture bucket = MinecraftContainerTransferOperations.mixtureFromExtraction(domain, extracted.portions(), request);
		check(bucket.totalVolumeNanos() == settlement.insertedNanos() && domain.totalVolumeExact() == 0.0,
				"material extraction credited the request instead of actual per-material marker deltas");
		check(Math.abs(bucket.totalMass() + (settlement.remaining() == null ? 0.0 : settlement.remaining().totalMass())
				- source.totalMass()) <= 1.0E-12,
				"material insertion/extraction lost the independent mass ledger");
	}

	private static void testTinyThermalMaterialSettlement() {
		var config = new FluidSolverConfig(0.5F, 0.0F, 0.05F, 48, 1.0E-4F, 2.0E-3F);
		var liquids = new mmm.fluid.FluidMaterialRegistry(List.of(
				mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material()));
		var definition = new ThermalMaterialDefinition("test:thermal/water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"mmm:water", 293.15, 0.0, 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 4184.0)));
		var definitions = mmm.fluid.TestFluidDefinitions.withThermal(mmm.fluid.ThermalValidationSupport.thermalRegistry(List.of(definition)));
        var registry = definitions.thermal();
		var state = new ThermalStateFunction(registry);
		var domain = new HostFluidState(3, 3, 3, config, definitions);
		domain.bindThermal(registry, liquids, 300.0);
		var source = new FluidMixture(List.of(new FluidMixture.Component("mmm:water", 999_999_999L, mmm.units.Mass.fromKilograms(999.0))));
		var planned = MinecraftContainerTransferOperations.planMaterialInsertion(domain, source);
		var settlement = MinecraftContainerTransferOperations.settleMaterialInsertion(domain, source, planned);
		check(settlement.remaining().totalVolumeNanos() == 59L, "normal float insertion no longer leaves the covered tiny remainder");
		double sourceEnergy = state.internalEnergyAtTemperature(0, source.totalMass(), java.util.Map.of(), 1.0E-8);
		var thermal = ThermalContents.forMixture(registry.fingerprint(), source, sourceEnergy);
		var split = LiquidContainerThermodynamics.split(registry, thermal, source, settlement.inserted(), settlement.remaining());
		double sourceT = LiquidContainerThermodynamics.temperatureK(registry, thermal, source);
		double modeledRemaining = state.internalEnergyAtTemperature(0, settlement.remaining().totalMass(), java.util.Map.of(), sourceT);
		check(split.remainingEnergy() == modeledRemaining, "59 nano remaining item did not preserve its own caloric energy");
		var inserted = domain.tryInsertExactMixture(1, 1, 1, planned, 3, false, split.selectedEnergy());
		check(inserted.transferred(), "legal cold thermal insertion failed");
		check(MinecraftContainerTransferOperations.settleMaterialInsertion(domain, source, inserted.portions()).equals(settlement),
				"actual inventory diverged from the thermal split plan");
		long[] markerMassUnits = domain.exportMaterialParticleState().copyParticleMassUnits();
		check(mmm.units.Mass.sum(markerMassUnits) == planned.getFirst().massUnits(),
				"Marker insertion changed integer mass inventory");

		double debit = split.coupledEnergy(sourceEnergy);
		var before = domain.thermalAudit();
		var actualEnergy = domain.exactInternalEnergy();
		domain.settleExternalContainerInsertion(split.selectedEnergy(), debit);
		var after = domain.thermalAudit();
		SignedDyadic correction = SignedDyadic.of(debit).subtract(SignedDyadic.of(split.selectedEnergy()));
		check(after.externalSource().subtract(before.externalSource()).equals(correction)
				&& after.roundoffResidual().subtract(before.roundoffResidual()).equals(correction.negate()),
				"item debit/received energy difference was not assigned to thermal roundoff");
		check(domain.exactInternalEnergy().equals(actualEnergy) && after.expectedInventory().equals(actualEnergy),
				"container settlement changed carrier U or failed exact audit closure");
	}

	private static void testChemicalInsertionMassConservation() {
		var config = new FluidSolverConfig(0.5F, 0.0F, 0.05F, 48, 1.0E-4F, 2.0E-3F);
		for (double sourceMass : new double[] {1.0, 18.0, 999.0}) {
			var domain = new HostFluidState(3, 3, 3, config, mmm.fluid.TestFluidDefinitions.load());
			domain.bindGranular(mmm.fluid.TestFluidDefinitions.load().granular(),
					new mmm.fluid.FluidMaterialRegistry(mmm.simulation.ProductionRegistries.palette()), mmm.fluid.TestFluidDefinitions.load().material());
			domain.bindChemistry(mmm.fluid.TestFluidDefinitions.load().chemistry());
			double sourceResidual = sourceMass - 18.0;
			var contents = new ChemicalContents(mmm.fluid.TestFluidDefinitions.load().chemistry().fingerprint(), ChemicalLimits.QUANTA_PER_MOL,
					List.of(new ChemicalContents.Group("mmm:water", 999_999_999L,
							mmm.units.Mass.fromKilograms(sourceMass), mmm.units.Mass.fromKilograms(sourceResidual),
							List.of(new ChemicalContents.SpeciesAmount(BuiltinChemicalDefinitions.H2O, 1_000 * ChemicalLimits.QUANTA_PER_MOL)))));
			contents.validateAgainst(mmm.fluid.TestFluidDefinitions.load().chemistry());
			var requested = contents.toLedger(mmm.fluid.TestFluidDefinitions.load().chemistry(), domain.unitScale());
			var result = domain.tryInsertExactChemical(1, 1, 1, requested, 3, false);
			check(result.transferred(), "representable chemical insertion was refused");
			long mass = 0L, residual = 0L, species = 0L;
			for (var stock : domain.exportLiquidCellState().stocks()) {
				mass = Math.addExact(mass, stock.massUnits());
				var record = stock.mixture();
				residual = Math.addExact(residual, record.massResidualUnits());
				species = Math.addExact(species, record.totalQuanta());
			}
			check(mass == mmm.units.Mass.fromKilograms(sourceMass),
					"Chemical insertion changed integer mass inventory");
			check(species == 1_000 * ChemicalLimits.QUANTA_PER_MOL && residual == mmm.units.Mass.fromKilograms(sourceResidual),
					"Chemical insertion changed species or integer mass residual");
		}
	}

	private static void testExistingBucketThermalSettlement() {
		var config = new FluidSolverConfig(0.5F, 0.0F, 0.05F, 48, 1.0E-4F, 2.0E-3F);
		var liquids = new mmm.fluid.FluidMaterialRegistry(List.of(
				mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material()));
		var definition = new ThermalMaterialDefinition("test:thermal/water", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"mmm:water", 293.15, 0.0, 0.0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250.0, 600.0, 4184.0)));
		var definitions = mmm.fluid.TestFluidDefinitions.withThermal(mmm.fluid.ThermalValidationSupport.thermalRegistry(List.of(definition)));
        var registry = definitions.thermal();
		for (double sign : new double[] {-1.0, 1.0}) {
			var source = new HostFluidState(3, 3, 3, config, definitions);
			source.setVolume(1, 1, 1, 0.125F);
			source.setVolume(2, 1, 1, 0.125F);
			source.bindThermal(registry, liquids, 300.0);
			var domain = new HostFluidState(3, 3, 3, config, definitions);
			domain.restoreMaterialParticleState(source.exportMaterialParticleState());
			double[] energy = new double[domain.particleCount()];
			energy[0] = sign * 16_384.0;
			energy[energy.length - 1] = sign * Math.scalb(1.0, -40);
			SignedDyadic exact = SignedDyadic.sum(energy);
			domain.restoreThermalParticleState(new FlipThermalParticleState(3, 3, 3, 0.5F, domain.particleCount(),
					registry.fingerprint(), 300.0, energy, ThermalAuditLedger.initialized(exact)), registry);
			var snapshotBefore = domain.exportThermalParticleState();
			SignedDyadic before = domain.exactInternalEnergy();
			var auditBefore = domain.thermalAudit();
			check(before.subtract(domain.exactGasInternalEnergy()).equals(exact) && domain.thermalAudit().equals(auditBefore)
					&& java.util.Arrays.equals(snapshotBefore.copyInternalEnergy(), domain.exportThermalParticleState().copyInternalEnergy()),
					"exact inventory observation changed raw thermal state: total=" + before + ", gas=" + domain.exactGasInternalEnergy() + ", liquid=" + exact + ", audit=" + domain.thermalAudit() + ", prior=" + auditBefore);
			var gasBefore = domain.exactGasInternalEnergy();
			var extracted = domain.tryExtractExactVolume(1, 1, 1, 0.25F, 1, 0);
			check(extracted.transferred() && extracted.internalEnergy() == sign * 16_384.0,
					"regression no longer exercises the rounded transfer receipt");
			double base = -sign * 16_384.0;
			var state = new ThermalStateFunction(registry);
			state.stateForInternalEnergy(0, 500.0, java.util.Map.of(), base);
			double finalEnergy = MinecraftContainerTransferOperations.mergedExtractionEnergy(base,
					before.subtract(gasBefore).subtract(domain.exactInternalEnergy().subtract(domain.exactGasInternalEnergy())));
			check(finalEnergy == sign * Math.scalb(1.0, -40), "existing bucket lost a representable final energy through intermediate rounding");
			var item = ThermalContents.forPureWater(registry.fingerprint(), 531_250_000L, finalEnergy);
			check(item.internalEnergy() == finalEnergy, "final item failed to retain raw energy");
			double coupled = SignedDyadic.of(finalEnergy).subtract(SignedDyadic.of(base)).toDouble();
			domain.settleExternalContainerExtraction(extracted.internalEnergy(), coupled);
			check(domain.thermalAudit().externalSink().equals(SignedDyadic.of(coupled))
					&& domain.thermalAudit().roundoffResidual().equals(auditBefore.roundoffResidual().add(SignedDyadic.of(coupled).subtract(exact)))
					&& domain.thermalAudit().expectedInventory().equals(domain.exactInternalEnergy())
					&& before.subtract(gasBefore).add(SignedDyadic.of(base)).equals(domain.exactInternalEnergy().subtract(domain.exactGasInternalEnergy()).add(SignedDyadic.of(finalEnergy))),
					"final bucket/coupled receipt/region audit do not describe the same transfer");
		}
	}

	private static void testSmallChemicalSample() {
		try {
			var definitions = FullCapabilityTestFixture.load();
			var domain = new HostFluidState(4, 4, 4, new FluidSolverConfig(.5F, 0, .05F, 48, 1e-4F, .002F), mmm.fluid.TestFluidDefinitions.load());
			FullCapabilityTestFixture.bindEmptyMaterialState(domain, definitions.liquids());
			domain.setVolume(1, 1, 1, 7.983301677e-7F);
			FullCapabilityTestFixture.bindFull(domain, definitions, 293.15, 8);
			var before = domain.exportLiquidCellState().stocks().getFirst();
			long nanos = MinecraftContainerTransferOperations.extractableNanos(domain, new int[]{1,1,1},
					ContainerFluidLedger.NANOS_PER_BLOCK, 0, -1);
			check(nanos == 99, "sample preview must retain the fractional nanovolume");
			var taken = domain.tryExtractExactChemical(1, 1, 1,
					MinecraftContainerTransferOperations.nanosToCellVolumeAtMost(domain.unitScale(), nanos), 0,
					ledger -> { ChemicalContents.fromLedger(ledger, mmm.fluid.TestFluidDefinitions.load().chemistry(), nanos); return true; });
			check(taken.transferred(), "representable chemical sample was refused");
			var remainder = domain.exportLiquidCellState().stocks().getFirst();
			var group = taken.ledger().groups().getFirst();
			check(before.massUnits() == remainder.massUnits() + group.massUnits(), "sample lost integer mass");
			check(before.mixture().totalQuanta() == remainder.mixture().totalQuanta()
					+ group.speciesQuanta().values().stream().mapToLong(Long::longValue).sum(), "sample lost species");
			check(Math.abs(before.internalEnergy() - remainder.internalEnergy() - taken.internalEnergy()) < 1e-7,
					"sample lost liquid internal energy");
		} catch (Exception failure) { throw new AssertionError("Small chemical sample fixture failed", failure); }
	}

	private static void testPartialOutlet() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 2.0E-3F);
		HostFluidState domain = new HostFluidState(4, 4, 4, config, mmm.fluid.TestFluidDefinitions.load());
		domain.tryInsertExactVolume(2, 2, 2, 0.5F, 1);
		long requested = MinecraftContainerTransferOperations.extractableNanos(domain, new int[] {2, 2, 2},
				mmm.transfer.ContainerFluidLedger.NANOS_PER_BLOCK, 1, 0);
		check(requested == 62_500_000L, "partial outlet did not quantize the available half-cell volume");
		FluidDomainContracts.ExactVolumeTransfer transfer = domain.tryExtractExactVolume(
				2, 2, 2, MinecraftContainerTransferOperations.nanosToCellVolume(domain.unitScale(), requested), 1, 0);
		check(transfer.transferred() && domain.totalVolumeExact() == 0.0,
				"partial outlet request could not be settled by the exact primitive");
	}

	private static void testOwnershipCheckpointLock() throws Exception {
		Path root = Files.createTempDirectory("mmm-ownership-checkpoint-lock");
		try {
			FluidSolverConfig config = new FluidSolverConfig(
					0.5F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 2.0E-3F);
			UUID regionId = UUID.randomUUID();
			BlockPos origin = BlockPos.ZERO;
			BlockPos controller = new BlockPos(-1, 0, 0);
			FluidRegionFootprint footprint = new FluidRegionFootprint(
					controller, origin, origin, Set.of(origin.asLong()), Set.of(controller.asLong()),
					Set.of(ChunkPos.pack(0, 0)));
			HostFluidState domain = new HostFluidState(2, 2, 2, config, mmm.fluid.TestFluidDefinitions.load());
			domain.setVolume(1, 1, 1, 0.25F);
			FluidRegion region = new FluidRegion(regionId, DIMENSION,
					origin, domain, footprint);
			RegionRepositoryAdapter persistence = new RegionRepositoryAdapter(root, TestProfiles.materials(), mmm.fluid.TestFluidDefinitions.load().chemistry(),
                    mmm.fluid.TestFluidDefinitions.load().granular(), mmm.fluid.TestFluidDefinitions.load().thermal(),
                    mmm.fluid.TestFluidDefinitions.load().gas(), TestProfiles.materialRegistry());
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
					0.5F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 2.0E-3F);
			UUID regionId = UUID.randomUUID();
			BlockPos origin = BlockPos.ZERO;
			BlockPos controller = new BlockPos(-1, 0, 0);
			FluidRegionFootprint footprint = new FluidRegionFootprint(
					controller, origin, origin, Set.of(origin.asLong()), Set.of(controller.asLong()),
					Set.of(ChunkPos.pack(0, 0)));
			HostFluidState pure = new HostFluidState(2, 2, 2, config, mmm.fluid.TestFluidDefinitions.load());
			pure.setVolume(1, 1, 1, 0.25F);
			FlipParticleState particles = pure.exportParticleState();
			float[] positionVolume = particles.copyPositionVolume();
			double[] masses = new double[particles.particleCount()];
			int[] materials = new int[particles.particleCount()];
			double totalMass = 0.0;
			for (int marker = 0; marker < masses.length; marker++) {
				masses[marker] = positionVolume[marker * 4 + 3] * mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material().referenceDensity();
				totalMass += masses[marker];
			}
			HostFluidState base = new HostFluidState(2, 2, 2, config, mmm.fluid.TestFluidDefinitions.load());
			base.restoreMaterialParticleState(new FlipMaterialParticleState(
					2, 2, 2, config.cellSize(), particles.particleCount(), particles.totalVolume(), mmm.units.SolverUnitScale.forCellSize(config.cellSize()).massUnitsForNormalized(totalMass),
					mmm.simulation.ProductionRegistries.palette(), positionVolume, particles.copyParticleVelocities(),
					particles.copySolidMask(), java.util.Arrays.stream(masses).mapToLong(m -> mmm.units.SolverUnitScale.forCellSize(config.cellSize()).massUnitsForNormalized(m)).toArray(), materials));
			FluidRegion region = new FluidRegion(regionId, DIMENSION, origin, base, footprint);
			base.sealDomainBoundary();
			base.captureAuthoritativeState();
			base.synchronizeGasTopologyForPersistence();
			check(base.gasBoundary().topologyInitialized() && base.gasBoundary().auditClosed()
					&& base.gasBoundary().sealedInventoryNanos() > 0,
					"private-target fixture did not initialize conserved sealed gas inventory: nodes=" + base.gasBoundary().nodeCount());
			RegionRepositoryAdapter persistence = new RegionRepositoryAdapter(
					root, TestProfiles.materials(), mmm.fluid.TestFluidDefinitions.load().chemistry(),
                    mmm.fluid.TestFluidDefinitions.load().granular(), mmm.fluid.TestFluidDefinitions.load().thermal(),
                    mmm.fluid.TestFluidDefinitions.load().gas(), TestProfiles.materialRegistry());
			long baseGeneration = persistence.checkpoint(region);
			HostFluidState candidate = HostFluidState.restoreAuthoritativeState(base.captureAuthoritativeState());
			check(candidate.tryInsertExactVolume(1, 1, 1, 0.125F, 1).transferred(),
					"fixture could not form a distinct private container target");
			double targetVolume = candidate.totalVolumeExact();
			long targetGeneration = baseGeneration + 1L;
			RegionRepositoryAdapter.StagedCheckpoint staged = persistence.stageCandidateCheckpoint(
					region, candidate, targetGeneration, region.paused());
			check(persistence.commitStagedLifecycleCheckpoint(staged) == targetGeneration,
					"fixture did not publish the private target HEAD");
			check(region.checkpointGeneration() == baseGeneration
					&& region.editableHost().totalVolumeExact() != targetVolume,
					"HEAD publication prematurely changed the live base solver");
			long solverGenerationBefore = region.solverGeneration();
			persistence.adoptCommittedContainerCheckpoint(region, baseGeneration, targetGeneration);
			check(region.checkpointGeneration() == targetGeneration
					&& region.editableHost().totalVolumeExact() == targetVolume
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

