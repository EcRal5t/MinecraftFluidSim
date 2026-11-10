package mmm.simulation;

import java.io.IOException;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.UUID;
import mmm.api.transfer.BlockInventoryAddress;
import mmm.application.ContainerTransferCoordinator.FaultPoint;
import mmm.application.ContainerTransferUseCase.Status;
import mmm.item.MmmComponents;
import mmm.item.PartialWaterBucketItem;
import mmm.simulation.persistence.PublicTransferReceiptStore;
import mmm.transfer.TransferActorState;
import mmm.worldprofile.WorldCapability;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Production internal port, real WAL and checkpoint with a full public receipt store. */
public final class IndustrialPortValidation {
    private IndustrialPortValidation() { }
    public static void main(String[] args) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-port-capacity-"),
                ()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                        EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),
                        definitions.granular(),definitions.gas(),definitions.thermal()));
        check(!startup.creationLocked(),"world definitions");
        var world=new CommittedWorldRegistries(startup); var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.SCREENING);
        var receipts=new PublicTransferReceiptStore(startup.worldRoot());
        PublicTransferReceiptStore.Binding first=null;
        for(int i=0;i<1024;i++) {
            var address=new BlockInventoryAddress("minecraft:overworld",i,64,0,0); var id=UUID.randomUUID();
            var binding=new PublicTransferReceiptStore.Binding(address.actorRef(id.toString()),address,id);
            if(first==null)first=binding;
            receipts.bind(binding);
        }
        var excessAddress=new BlockInventoryAddress("minecraft:overworld",1024,64,0,0); var excessId=UUID.randomUUID();
        try { receipts.bind(new PublicTransferReceiptStore.Binding(excessAddress.actorRef(excessId.toString()),excessAddress,excessId));
            throw new AssertionError("public actor budget did not reach 1024");
        } catch(IOException expected) { check(expected.getMessage().contains("budget"),"unexpected public binding error"); }
        var tinyContents=new mmm.transfer.ChemicalContents(world.chemicalRegistry().fingerprint(),
                mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL,java.util.List.of(new mmm.transfer.ChemicalContents.Group(
                "mmm:water",1000L,180L,0L,java.util.List.of(new mmm.transfer.ChemicalContents.SpeciesAmount(
                mmm.chemistry.BuiltinChemicalDefinitions.H2O,1L)))));
        var tiny=world.bindActualContainerProfile(mmm.item.ContainerItemStacks.toStack(tinyContents));
        world.attachLiquidThermalContents(tiny,293.15);
        long beforePreviewHead=operations.committedGeneration(fixture.region.id());
        var beforePreview=fixture.region.hostSnapshot(); long beforePreviewMass=beforePreview.totalMassUnits();
        var beforePreviewEnergy=beforePreview.exactInternalEnergy(); double beforePreviewVolume=beforePreview.totalVolumeExact();
        int[] saves={0};
        var untransferable=fixture.transferBoxAttempt(tiny,fixture.portCell,Direction.SOUTH,false,1L,FaultPoint.NONE,false,saves);
        check(untransferable!=null && MinecraftContainerTransferPlan.resolveDirected(untransferable.adapter(),world,fixture.region,
                untransferable.cell(),fixture.region.beginHostEdit(),untransferable.actor().current,false).resolved(),"small chemical item did not resolve");
        check(saves[0]==0 && (untransferable.result().transaction()==null
                || untransferable.result().transaction().status()==Status.NOT_TRANSFERRED),"zero settlement forced a save");
        var afterPreview=fixture.region.hostSnapshot();
        check(operations.committedGeneration(fixture.region.id())==beforePreviewHead && fixture.journal.discover().transactions().isEmpty()
                && afterPreview.totalMassUnits()==beforePreviewMass && afterPreview.totalVolumeExact()==beforePreviewVolume
                && afterPreview.exactInternalEnergy().equals(beforePreviewEnergy),"zero settlement changed HEAD, WAL or inventory");
        long initialHeads=operations.committedGeneration(fixture.region.id());
        try {
            fixture.transferBoxAttempt(new ItemStack(Items.WATER_BUCKET),fixture.portCell,Direction.SOUTH,false,50_000_000L,FaultPoint.NONE,true);
            throw new AssertionError("before-save failure was swallowed");
        } catch(IOException expected) { check(expected.getMessage().contains("before-save"),"unexpected save failure"); }
        check(operations.committedGeneration(fixture.region.id())==initialHeads && fixture.journal.discover().transactions().isEmpty(),
                "failed before-save published region or WAL");
        var water=new ItemStack(Items.WATER_BUCKET);
        water.set(DataComponents.CUSTOM_NAME,Component.literal("工艺样品"));
        var partial=transferCommitted(fixture,water);
        long afterFirst=PartialWaterBucketItem.volume(partial);
        check(afterFirst<1_000_000_000L && afterFirst>=950_000_000L,"first quota");
        var firstState=TransferActorState.decode(partial.get(MmmComponents.TRANSFER_ACTOR));
        check(firstState.generation()==1 && Component.literal("工艺样品").equals(partial.get(DataComponents.CUSTOM_NAME)),
                "first actor generation or metadata");
        var partialAgain=transferCommitted(fixture,partial);
        long afterSecond=PartialWaterBucketItem.volume(partialAgain);
        var secondState=TransferActorState.decode(partialAgain.get(MmmComponents.TRANSFER_ACTOR));
        check(afterSecond<afterFirst && afterFirst-afterSecond<=50_000_000L
                && secondState.instanceId().equals(firstState.instanceId()) && secondState.generation()==2,
                "second partial quota or generation");
        var replacement=transferCommitted(fixture,new ItemStack(Items.WATER_BUCKET));
        var replacementState=TransferActorState.decode(replacement.get(MmmComponents.TRANSFER_ACTOR));
        check(!replacementState.instanceId().equals(firstState.instanceId()) && replacementState.generation()==1,
                "replacement batch identity");
        var failed=fixture.transferBoxAttempt(new ItemStack(Items.WATER_BUCKET),fixture.portCell,Direction.SOUTH,false,
                50_000_000L,FaultPoint.AFTER_CHECKPOINT,false);
        check(failed!=null && failed.result().transaction()!=null && failed.result().transaction().status()==Status.LOCKED
                && failed.result().pending(),"after-HEAD fault did not leave a pending WAL");
        var transactionId=failed.result().transaction().transactionId(); var journal=fixture.journal.read(transactionId);
        long head=operations.committedGeneration(fixture.region.id());
        check(head==journal.targetGeneration(),"fault was not after committed HEAD");
        var again=IndustrialOperations.transferBatch(failed.adapter(),failed.recovery(),world,fixture.region,failed.cell(),fixture.region.beginHostEdit(),
                failed.actor().current,failed.identity(),false,50_000_000L,()->{throw new AssertionError("pending transfer attempted save");});
        check(again.pending() && again.transaction()==null && operations.committedGeneration(fixture.region.id())==head,
                "pending batch was submitted twice");
        failed.recovery().retryTransaction(transactionId);
        check(!failed.recovery().hasPending(fixture.region.id(),failed.identity()) && fixture.journal.discover().transactions().isEmpty(),
                "after-HEAD recovery did not settle WAL");
        var recovered=failed.actor().current; var recoveredState=TransferActorState.decode(recovered.get(MmmComponents.TRANSFER_ACTOR));
        check(mmm.item.ContainerItemMapper.fingerprint(recovered).equals(journal.afterItem())
                && recoveredState.instanceId().equals(failed.identity().inventoryId())
                && recoveredState.generation()==failed.identity().generation()+1,"recovered inventory or actor generation differs from WAL");
        check(operations.committedGeneration(fixture.region.id())==head,"recovery repeated HEAD publication");
        check(receipts.binding(first.actor()).equals(first) && receipts.entries().isEmpty(),"internal port changed public transfer records");
        var directory=startup.worldRoot().resolve("mmm/public-transfer");
        try(var files=Files.list(directory)) { check(files.filter(path->path.getFileName().toString().endsWith(".actor.nbt")).count()==1024,
                "internal port grew public actor bindings"); }
        System.out.println("IndustrialPortValidation passed: 1024 public bindings, partial batches and after-HEAD recovery");
    }
    private static ItemStack transferCommitted(GpuControllerFixture fixture,ItemStack item) throws IOException {
        var attempt=fixture.transferBoxAttempt(item,fixture.portCell,Direction.SOUTH,false,50_000_000L,FaultPoint.NONE,false);
        check(attempt!=null && attempt.result().transaction()!=null
                && attempt.result().transaction().status()==Status.COMMITTED && !attempt.result().pending(),"port transaction did not commit");
        return attempt.actor().current.copy();
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
