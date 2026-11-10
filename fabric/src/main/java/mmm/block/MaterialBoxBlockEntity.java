package mmm.block;

import mmm.item.MmmComponents;
import mmm.transfer.TransferActorState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Nine finite material batches, each using the shared container payload and transaction identity. */
public final class MaterialBoxBlockEntity extends BlockEntity implements Container {
    private final ItemStack[] batches = new ItemStack[9];
    private int selected;

    public MaterialBoxBlockEntity(BlockPos position, BlockState state) {
        super(MmmBlocks.MATERIAL_BOX_ENTITY, position, state);
        java.util.Arrays.fill(batches, ItemStack.EMPTY);
    }
    public static ItemStack newBatch(ItemStack stack) {
        var batch=stack.copy();
        batch.set(MmmComponents.TRANSFER_ACTOR,new TransferActorState(java.util.UUID.randomUUID(),0).encode());
        return batch;
    }
    public void select(int slot){if(slot<0||slot>=batches.length)throw new IllegalArgumentException("批次范围为 1—9");selected=slot;setChanged();}
    public int selected() { return selected; }
    public void selectNext() { selected=(selected+1)%batches.length;setChanged(); }
    @Override public int getContainerSize() { return batches.length; }
    @Override public boolean isEmpty() {
        for(var item:batches) if(!item.isEmpty()) return false;
        return true;
    }
    @Override public ItemStack getItem(int slot) { return batches[slot]; }
    @Override public ItemStack removeItem(int slot,int amount) { return amount>0?removeItemNoUpdate(slot):ItemStack.EMPTY; }
    @Override public ItemStack removeItemNoUpdate(int slot) {
        var result=batches[slot];batches[slot]=ItemStack.EMPTY;setChanged();return result;
    }
    @Override public void setItem(int slot,ItemStack item) { batches[slot]=item;setChanged(); }
    @Override public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this,player); }
    @Override public boolean canPlaceItem(int slot,ItemStack item) { return false; }
    @Override public boolean canTakeItem(Container target,int slot,ItemStack item) { return false; }
    @Override public void clearContent() {
        java.util.Arrays.fill(batches, ItemStack.EMPTY);
        setChanged();
    }
    @Override public void preRemoveSideEffects(BlockPos position,BlockState state) {
        if(level!=null&&!level.isClientSide()) for(var item:batches) if(!item.isEmpty())
            item.remove(MmmComponents.TRANSFER_ACTOR);
        super.preRemoveSideEffects(position,state);
    }
    @Override protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);selected=input.getIntOr("selected",0);
        for(int slot=0;slot<batches.length;slot++) batches[slot]=input.read("batch_"+slot,ItemStack.CODEC).orElse(ItemStack.EMPTY);
    }
    @Override protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);output.putInt("selected",selected);
        for(int slot=0;slot<batches.length;slot++) if(!batches[slot].isEmpty()) output.store("batch_"+slot,ItemStack.CODEC,batches[slot]);
    }
}
