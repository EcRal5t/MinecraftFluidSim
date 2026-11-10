package mmm.simulation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.BooleanSupplier;
import mmm.block.MaterialBoxBlockEntity;
import mmm.block.MmmBlocks;
import mmm.item.ContainerItemMapper;
import mmm.item.CreativeSourceItem;
import mmm.item.MmmComponents;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Transactional external access to the box's real container slots. */
public final class MaterialBoxItemStorage extends SnapshotParticipant<ItemStack[]> implements SlottedStorage<ItemVariant> {
    private final MaterialBoxBlockEntity box;
    private final IntPredicate permitted;
    private final BooleanSupplier available;
    private final List<SingleSlotStorage<ItemVariant>> slots;
    private boolean active;

    public MaterialBoxItemStorage(MaterialBoxBlockEntity box) {
        this(box, () -> available(box), slot -> permitted(box, slot));
    }
    MaterialBoxItemStorage(MaterialBoxBlockEntity box, IntPredicate permitted) {
        this(box, () -> true, permitted);
    }
    MaterialBoxItemStorage(MaterialBoxBlockEntity box, BooleanSupplier available, IntPredicate permitted) {
        this.box=java.util.Objects.requireNonNull(box);
        this.available=java.util.Objects.requireNonNull(available);
        this.permitted=java.util.Objects.requireNonNull(permitted);
        var entries=new ArrayList<SingleSlotStorage<ItemVariant>>(box.getContainerSize());
        for(int i=0;i<box.getContainerSize();i++)entries.add(new Slot(i));
        slots=List.copyOf(entries);
    }
    public static void register() {
        ItemStorage.SIDED.registerForBlocks((level, position, state, entity, side) ->
                entity instanceof MaterialBoxBlockEntity box ? box.itemStorage() : net.fabricmc.fabric.api.transfer.v1.storage.Storage.empty(),
                MmmBlocks.MATERIAL_BOX);
    }
    private static boolean available(MaterialBoxBlockEntity box) {
        if(box.isRemoved() || !(box.getLevel() instanceof ServerLevel level)
                || !level.getServer().isSameThread() || !level.hasChunkAt(box.getBlockPos())
                || level.getBlockEntity(box.getBlockPos())!=box) return false;
        var runtime=MmmRuntimeRegistry.find(level.getServer());
        return runtime!=null && runtime.startup().authoritativeWritesReady();
    }
    private static boolean permitted(MaterialBoxBlockEntity box,int slot) {
        var level=(ServerLevel)box.getLevel();
        return MmmRuntimeRegistry.find(level.getServer()).industrial().mayAutomateBoxSlot(level,box,slot);
    }
    private boolean writable(int slot) { return available.getAsBoolean() && permitted.test(slot) && (!box.transferBusy() || active); }
    private void prepare(TransactionContext transaction) {
        if(active) { updateSnapshots(transaction); return; }
        box.beginStorageTransfer(); active=true;
        try {
            updateSnapshots(transaction);
            transaction.addOuterCloseCallback(result -> { active=false; box.endStorageTransfer(); });
        } catch(RuntimeException failure) {
            active=false; box.endStorageTransfer(); throw failure;
        }
    }
    @Override protected ItemStack[] createSnapshot() { return box.snapshotStorage(); }
    @Override protected void readSnapshot(ItemStack[] snapshot) { box.restoreStorage(snapshot); }
    @Override protected void onFinalCommit() { box.setChanged(); }
    @Override public int getSlotCount() { return slots.size(); }
    @Override public SingleSlotStorage<ItemVariant> getSlot(int slot) { return slots.get(slot); }
    @Override public Iterator<StorageView<ItemVariant>> iterator() {
        return slots.stream().<StorageView<ItemVariant>>map(slot -> slot).iterator();
    }
    @Override public long insert(ItemVariant resource,long maximum,TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource,maximum);
        long inserted=0;
        for(var slot:slots) { if(inserted==maximum)break; inserted+=slot.insert(resource,maximum-inserted,transaction); }
        return inserted;
    }
    @Override public long extract(ItemVariant resource,long maximum,TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource,maximum);
        long extracted=0;
        for(var slot:slots) { if(extracted==maximum)break; extracted+=slot.extract(resource,maximum-extracted,transaction); }
        return extracted;
    }
    private static ItemStack external(ItemStack item) {
        if(item.isEmpty())return ItemStack.EMPTY;
        var result=item.copy(); result.remove(MmmComponents.TRANSFER_ACTOR); return result;
    }
    static boolean supported(ItemStack item) {
        if(item.getItem() instanceof CreativeSourceItem || item.get(MmmComponents.TRANSFER_ACTOR)!=null) return false;
        try { ContainerItemMapper.fingerprint(item); return true; }
        catch(IllegalArgumentException invalid) { return false; }
    }
    private final class Slot implements SingleSlotStorage<ItemVariant> {
        private final int index;
        Slot(int index) { this.index=index; }
        @Override public long insert(ItemVariant resource,long maximum,TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource,maximum);
            if(maximum==0 || !writable(index) || !box.getItem(index).isEmpty())return 0;
            var item=resource.toStack(1);
            if(!supported(item))return 0;
            prepare(transaction);
            box.setStorageSlot(index,MaterialBoxBlockEntity.newBatch(item));
            return 1;
        }
        @Override public long extract(ItemVariant resource,long maximum,TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource,maximum);
            if(maximum==0 || !writable(index))return 0;
            var current=external(box.getItem(index));
            if(current.isEmpty() || !resource.matches(current))return 0;
            prepare(transaction);
            box.setStorageSlot(index,ItemStack.EMPTY);
            return 1;
        }
        @Override public boolean isResourceBlank() { return !available.getAsBoolean() || box.getItem(index).isEmpty(); }
        @Override public ItemVariant getResource() { return available.getAsBoolean() ? ItemVariant.of(external(box.getItem(index))) : ItemVariant.blank(); }
        @Override public long getAmount() { return isResourceBlank()?0:1; }
        @Override public long getCapacity() { return 1; }
    }
}
