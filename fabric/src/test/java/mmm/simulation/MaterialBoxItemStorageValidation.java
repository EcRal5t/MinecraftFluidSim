package mmm.simulation;

import java.util.ArrayList;
import mmm.block.MaterialBoxBlockEntity;
import mmm.block.MmmBlocks;
import mmm.item.MmmComponents;
import mmm.item.PartialWaterBucketItem;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Real Fabric transactions over the material box storage. */
public final class MaterialBoxItemStorageValidation {
    private MaterialBoxItemStorageValidation() { }
    public static void main(String[] args) {
        GpuControllerFixture.initializeItems(); GpuControllerFixture.initializeIndustrialBlocks();
        registeredStorageAndNoWorld(); committedItemsAndComponents(); rollbackAndNestedTransactions(); slotLocksAndUnavailable(); capacityAndTypes(); waterComponents(); itemRecoveryComponents(); phasePayloads(); simulatedCrossBoxTransfer();
        System.out.println("MaterialBoxItemStorageValidation passed");
    }
    private static MaterialBoxBlockEntity box() {
        return new MaterialBoxBlockEntity(BlockPos.ZERO,MmmBlocks.MATERIAL_BOX.defaultBlockState());
    }
    private static ItemStack sample() {
        ItemStack stack=PartialWaterBucketItem.withVolume(123_456_789L);
        stack.set(DataComponents.CUSTOM_NAME,Component.literal("批次 α"));
        return stack;
    }
    private static void registeredStorageAndNoWorld() {
        MaterialBoxItemStorage.register();
        var box=box(); var state=box.getBlockState();
        var provider=net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.getProvider(MmmBlocks.MATERIAL_BOX);
        check(provider!=null,"material box missing ItemStorage registration");
        var missing=provider.find(null,box.getBlockPos(),state,null,null);
        check(missing!=null && !missing.iterator().hasNext(),"missing block entity did not suppress fallback storage");
        for(var side:net.minecraft.core.Direction.values())
            check(provider.find(null,box.getBlockPos(),state,box,side)==box.itemStorage(),"registered side did not return real storage");
        check(provider.find(null,box.getBlockPos(),state,box,null)==box.itemStorage(),"unsided lookup did not return real storage");
        try(var outer=Transaction.openOuter()) {
            check(box.itemStorage().insert(ItemVariant.of(Items.BUCKET),1,outer)==0,"box without server world accepted insertion");
            outer.commit();
        }
        check(box.isEmpty() && !box.transferBusy(),"missing runtime changed box or locked it");
        box.setRemoved();
        try(var outer=Transaction.openOuter()) {
            check(box.itemStorage().insert(ItemVariant.of(Items.BUCKET),1,outer)==0,"removed box accepted insertion");
            outer.commit();
        }
    }
    private static void committedItemsAndComponents() {
        var box=box(); var storage=new MaterialBoxItemStorage(box,slot->true); var original=sample(); var resource=ItemVariant.of(original);
        try(var outer=Transaction.openOuter()) {
            check(!box.transferBusy(),"opening a transaction alone must not lock the box");
            check(storage.insert(resource,1,outer)==1,"sample insert");
            check(box.transferBusy(),"in-flight insertion must lock player and port operations");
            try { box.setItem(8,new ItemStack(Items.BUCKET)); throw new AssertionError("direct write bypassed transaction lock"); }
            catch(IllegalStateException expected) { }
            outer.commit();
        }
        check(!box.transferBusy(),"outer commit releases box");
        var stored=box.getItem(0);
        check(stored.get(MmmComponents.TRANSFER_ACTOR)!=null,"inserted item has no internal identity");
        var visible=contents(storage);
        check(visible.size()==1 && visible.getFirst().equals(resource),"view changed item components or exposed internal identity");
        try(var outer=Transaction.openOuter()) {
            check(storage.extract(resource,1,outer)==1,"extract the external variant");
            check(box.transferBusy(),"in-flight extraction must lock player and port operations");
            outer.commit();
        }
        check(box.isEmpty() && !box.transferBusy(),"committed extraction or unlock");
        check(original.get(MmmComponents.TRANSFER_ACTOR)==null,"source stack acquired internal identity");
    }
    private static void rollbackAndNestedTransactions() {
        var box=box(); var storage=new MaterialBoxItemStorage(box,slot->true); var resource=ItemVariant.of(sample());
        try(var outer=Transaction.openOuter()) { check(storage.insert(resource,1,outer)==1,"insert before abort"); }
        check(box.isEmpty() && !box.transferBusy(),"outer abort did not restore empty slot");
        try(var outer=Transaction.openOuter()) {
            try(var nested=outer.openNested()) { check(storage.insert(resource,1,nested)==1,"nested insert"); nested.commit(); }
            check(box.transferBusy(),"nested commit released the outer lock");
        }
        check(box.isEmpty() && !box.transferBusy(),"outer abort did not undo nested commit");
        try(var outer=Transaction.openOuter()) {
            try(var nested=outer.openNested()) { check(storage.insert(resource,1,nested)==1,"nested abort insert"); }
            check(box.isEmpty() && box.transferBusy(),"nested abort did not restore state or retained lock");
            outer.commit();
        }
        check(box.isEmpty() && !box.transferBusy(),"outer commit after nested abort");
        try(var outer=Transaction.openOuter()) { check(storage.insert(resource,1,outer)==1,"fixture insertion"); outer.commit(); }
        var before=box.getItem(0).copy();
        try(var outer=Transaction.openOuter()) { check(storage.extract(resource,1,outer)==1,"extract before abort"); }
        check(ItemStack.matches(before,box.getItem(0)) && !box.transferBusy(),"extraction abort lost item or identity");
    }
    private static void slotLocksAndUnavailable() {
        var box=box(); boolean[] allowed=new boolean[9]; java.util.Arrays.fill(allowed,true); allowed[0]=false;
        var storage=new MaterialBoxItemStorage(box,slot->allowed[slot]); var resource=ItemVariant.of(sample());
        try(var outer=Transaction.openOuter()) {
            check(storage.getSlot(0).insert(resource,1,outer)==0,"slot view bypassed pending WAL");
            check(storage.insert(resource,1,outer)==1,"unlocked slot not usable"); outer.commit();
        }
        check(box.getItem(0).isEmpty() && !box.getItem(1).isEmpty(),"pending WAL did not protect exact empty slot");
        allowed[1]=false;
        try(var outer=Transaction.openOuter()) { check(storage.extract(resource,1,outer)==0,"pending WAL allowed extraction"); outer.commit(); }
        check(!box.getItem(1).isEmpty(),"locked batch changed");
        java.util.Arrays.fill(allowed,false);
        try(var outer=Transaction.openOuter()) {
            check(storage.insert(resource,1,outer)==0 && storage.extract(resource,1,outer)==0,"unavailable storage accepted mutation");
            outer.commit();
        }
        check(contents(storage).contains(resource) && !box.transferBusy(),"slot lock changed read-only inventory or became busy");
        boolean[] available={true};
        var guarded=new MaterialBoxItemStorage(box,()->available[0],slot->true);
        check(contents(guarded).contains(resource),"available view hid contents");
        available[0]=false;
        check(contents(guarded).isEmpty(),"unavailable world exposed stale contents");
        try(var outer=Transaction.openOuter()) {
            check(guarded.insert(resource,1,outer)==0 && guarded.extract(resource,1,outer)==0,"unavailable world accepted mutation");
            outer.commit();
        }
        available[0]=true;
        check(contents(guarded).contains(resource),"restored world lost contents");
    }
    private static void capacityAndTypes() {
        var box=box(); var storage=new MaterialBoxItemStorage(box,slot->true); var bucket=ItemVariant.of(Items.BUCKET);
        var spoofed=sample(); spoofed.set(MmmComponents.TRANSFER_ACTOR,"external identity");
        try(var outer=Transaction.openOuter()) {
            check(storage.insert(ItemVariant.of(spoofed),1,outer)==0,"external internal identity accepted");
            check(storage.insert(ItemVariant.of(Items.STONE),1,outer)==0,"unsupported item accepted");
            check(storage.insert(bucket,12,outer)==9,"box did not limit inserts to nine containers"); outer.commit();
        }
        var identities=new java.util.HashSet<String>();
        for(int slot=0;slot<9;slot++) {
            var item=box.getItem(slot); check(item.getCount()==1,"each slot must contain one container");
            check(identities.add(item.get(MmmComponents.TRANSFER_ACTOR)),"batches share an identity");
        }
        try(var outer=Transaction.openOuter()) { check(storage.insert(bucket,1,outer)==0,"full box accepted item"); outer.commit(); }
        try(var outer=Transaction.openOuter()) { check(storage.extract(bucket,4,outer)==4,"multi-slot extraction count"); outer.commit(); }
        int occupied=0; for(int i=0;i<9;i++) if(!box.getItem(i).isEmpty()) occupied++;
        check(occupied==5,"multi-slot extraction removed incorrect count");
    }
    private static void waterComponents() {
        var box=box(); var storage=new MaterialBoxItemStorage(box,slot->true);
        var plain=new ItemStack(Items.WATER_BUCKET); var plainResource=ItemVariant.of(plain);
        try(var outer=Transaction.openOuter()) { check(storage.insert(plainResource,1,outer)==1,"plain water insertion"); outer.commit(); }
        check(contents(storage).equals(java.util.List.of(plainResource)),"plain water gained or lost components");
        check(box.getItem(0).get(MmmComponents.THERMAL_CONTENTS)==null,"storage initialized water thermal contents");
        try(var outer=Transaction.openOuter()) { check(storage.extract(plainResource,1,outer)==1,"plain water extraction"); outer.commit(); }
        var thermal=new ItemStack(Items.WATER_BUCKET);
        mmm.item.ContainerItemStacks.attachThermal(thermal,"0".repeat(64),12345.25);
        var thermalResource=ItemVariant.of(thermal);
        try(var outer=Transaction.openOuter()) { check(storage.insert(thermalResource,1,outer)==1,"thermal water insertion"); outer.commit(); }
        check(contents(storage).equals(java.util.List.of(thermalResource)),"thermal water components changed");
        try(var outer=Transaction.openOuter()) { check(storage.extract(thermalResource,1,outer)==1,"thermal water extraction"); outer.commit(); }
        check(box.isEmpty(),"water extraction left inventory");
    }
    private static void itemRecoveryComponents() {
        var before=new ItemStack(Items.WATER_BUCKET);
        before.set(DataComponents.CUSTOM_NAME,Component.literal("标记水桶"));
        before.remove(DataComponents.MAX_STACK_SIZE);
        mmm.item.ContainerItemStacks.attachThermal(before,"0".repeat(64),12345.25);
        before.set(MmmComponents.WATER_VOLUME,55L);
        before.set(MmmComponents.TRANSFER_ACTOR,"obsolete actor");
        var empty=new mmm.transfer.ContainerTransferJournal.ItemFingerprint(
                mmm.transfer.ContainerTransferJournal.ItemKind.BUCKET,-1,null);
        var rebuilt=mmm.item.ContainerItemMapper.toStack(empty,before);
        check(rebuilt.is(Items.BUCKET) && Component.literal("标记水桶").equals(rebuilt.get(DataComponents.CUSTOM_NAME)),
                "recovery did not preserve custom name");
        check(rebuilt.getComponentsPatch().entrySet().stream().anyMatch(entry ->
                entry.getKey()==DataComponents.MAX_STACK_SIZE && entry.getValue().isEmpty()),"recovery lost removed component patch");
        check(rebuilt.get(MmmComponents.WATER_VOLUME)==null && rebuilt.get(MmmComponents.THERMAL_CONTENTS)==null
                && rebuilt.get(MmmComponents.TRANSFER_ACTOR)==null,"old inventory, energy or identity leaked into recovery");
        var repeated=mmm.item.ContainerItemMapper.toStack(empty,rebuilt);
        check(ItemVariant.of(rebuilt).equals(ItemVariant.of(repeated)),"replaying after item changed visible components");
        var partial=new mmm.transfer.ContainerTransferJournal.ItemFingerprint(
                mmm.transfer.ContainerTransferJournal.ItemKind.PARTIAL,123_456_789L,null);
        var next=mmm.item.ContainerItemMapper.toStack(partial,before);
        check(PartialWaterBucketItem.volume(next)==123_456_789L && next.get(MmmComponents.THERMAL_CONTENTS)==null
                && next.get(MmmComponents.TRANSFER_ACTOR)==null,"after-state inventory did not replace old fields");
        check(Component.literal("标记水桶").equals(next.get(DataComponents.CUSTOM_NAME)),"partial transfer lost custom name");
    }
    private static void phasePayloads() {
        var liquid=new mmm.transfer.FluidMixture(java.util.List.of(
                new mmm.transfer.FluidMixture.Component("mmm:water",250_000_000L,123_456_789L)));
        var granular=new mmm.transfer.GranularContents("0".repeat(64),mmm.granular.SolidVolume.SVQ_PER_CUBIC_METER,
                java.util.List.of(new mmm.transfer.GranularContents.Group(mmm.granular.BuiltinGranularDefinitions.FINE_SAND,1000L,2000L)));
        var gasInventory=new mmm.fluid.air.GasInventory(java.util.List.of(
                new mmm.fluid.air.GasComponentAmount(mmm.fluid.air.BuiltinGasDefinitions.NITROGEN,100_000_000L,3000L,java.util.List.of(),3000L)));
        var gas=new mmm.transfer.GasContents("1".repeat(64),"",gasInventory,123.5);
        for(var stack:java.util.List.of(mmm.item.ContainerItemStacks.toStack(liquid),
                mmm.item.ContainerItemStacks.toStack(granular),mmm.item.ContainerItemStacks.toStack(gas))) {
            mmm.item.ContainerItemStacks.bindProfile(stack,"2".repeat(64));
            var box=box(); var storage=new MaterialBoxItemStorage(box,slot->true); var resource=ItemVariant.of(stack);
            try(var tx=Transaction.openOuter()) { check(storage.insert(resource,1,tx)==1,"phase payload insertion"); tx.commit(); }
            check(contents(storage).equals(java.util.List.of(resource)),"phase payload components changed");
            try(var tx=Transaction.openOuter()) { check(storage.extract(resource,1,tx)==1,"phase payload extraction"); tx.commit(); }
            check(box.isEmpty(),"phase payload remained in box");
        }
    }
    private static void simulatedCrossBoxTransfer() {
        var source=box(); var target=box();
        var from=new MaterialBoxItemStorage(source,slot->true); var to=new MaterialBoxItemStorage(target,slot->true);
        var resource=ItemVariant.of(sample());
        try(var outer=Transaction.openOuter()) { check(from.insert(resource,1,outer)==1,"source setup"); outer.commit(); }
        var original=source.getItem(0).copy();
        for(int attempt=0;attempt<2;attempt++) {
            try(var outer=Transaction.openOuter()) {
                check(from.extract(resource,1,outer)==1 && to.insert(resource,1,outer)==1,"simulated cross-box transfer");
                check(source.transferBusy() && target.transferBusy(),"both participants must stay locked");
            }
            check(ItemStack.matches(original,source.getItem(0)) && target.isEmpty(),"simulation changed either box");
            check(!source.transferBusy() && !target.transferBusy(),"simulation retained box locks");
        }
        try(var outer=Transaction.openOuter()) {
            check(from.extract(resource,1,outer)==1 && to.insert(resource,1,outer)==1,"committed cross-box transfer");
            outer.commit();
        }
        check(source.isEmpty() && contents(to).equals(java.util.List.of(resource)),"committed transfer lost external components");
        check(!source.transferBusy() && !target.transferBusy(),"commit retained box locks");
        check(!original.get(MmmComponents.TRANSFER_ACTOR).equals(target.getItem(0).get(MmmComponents.TRANSFER_ACTOR)),
                "destination reused a previous actor identity");
    }
    private static java.util.List<ItemVariant> contents(Storage<ItemVariant> storage) {
        var values=new ArrayList<ItemVariant>();
        for(var view:storage) if(!view.isResourceBlank()) values.add(view.getResource());
        return values;
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
