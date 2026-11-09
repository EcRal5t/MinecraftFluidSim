package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;

/** Static liquid edits and solid displacement preserve the exact inventory. */
public final class LiquidVolumeTransportValidation {
    public static void main(String[] args) {
        externalQuantityTransactions();
        sharedCapacityDisplacement();
        componentCapacityMovesItsOwnStock();
        System.out.println("Static liquid inventory edits and displacement passed");
    }
    private static HostFluidState domain(int x, int y, int z) {
        return new HostFluidState(x, y, z, new FluidSolverConfig(.5F, 0, .05F, 100, 1e-5F, .002F), mmm.fluid.TestFluidDefinitions.load());
    }
	private static void externalQuantityTransactions() {
		var d = domain(4,3,2); d.setVolume(1,1,1,.5F);
		var startVolume = d.liquidCells.exactVolume(); long mass = d.liquidCells.massUnits();
		var removed = d.tryExtractExactMixture(1,1,1,.125F,0);
		require(removed.transferred() && d.liquidCells.exactVolume().equals(startVolume.subtract(SignedDyadic.of(.125))), "Exact mixture extraction stalled");
		var inserted = d.domainInventory.tryInsertExactMixture(1,1,1,removed.portions(),0,true);
		require(inserted.transferred() && d.liquidCells.exactVolume().equals(startVolume) && d.liquidCells.massUnits() == mass, "External mixture cycle changed integer mass or volume");
		var prior = d.liquidCells.snapshot();
		var full = d.domainInventory.tryInsertExactMaterial(1,1,1,1,mass,0,0);
		require(!full.transferred() && d.liquidCells.snapshot().rows().equals(prior.rows()), "Failed capacity preparation published a partial insertion");
		d.setVolume(1,1,1,.25F); require(d.liquidCells.exactVolume().equals(SignedDyadic.of(.25)), "setVolume bypassed common cell inventory");
		d.removeAllWaterExact(); require(d.liquidCells.size() == 0 && d.liquidCells.massUnits() == 0, "Liquid clear left persistent quantities");
	}
	private static void sharedCapacityDisplacement() {
		var d=domain(3,2,2);byte[] masks=new byte[d.cellCount];java.util.Arrays.fill(masks,(byte)255);
		masks[0]=masks[1]=masks[2]=0;d.geometry.cutCells.restoreMicroMasks(masks);
		var initial=new float[d.cellCount];initial[0]=initial[1]=1;d.setVolumes(initial);d.domainFlowFields.preparePhysicalState();
		var previous=new long[d.cellCount];var next=previous.clone();next[0]=mmm.granular.SolidVolume.SVQ_PER_CELL/2;
		var before=d.liquidCells.snapshot();var prepared=GranularLiquidDisplacement.prepareInsertion(d,previous,next);
		require(prepared!=null,"Solid insertion did not displace its overfilled liquid");
		var future=prepared.inventory.future();
		for(int cell=0;cell<3;cell++)require(future.exactCellVolume(cell).signum()>=0
				&&future.exactCellVolume(cell).compareTo(SignedDyadic.of(cell==0?.5:1))<=0,"Shared displacement exceeded actual pore capacity");
		require(future.exactVolume().equals(SignedDyadic.of(2))&&future.massUnits()==d.liquidCells.massUnits()
				&&before.rows().equals(d.liquidCells.snapshot().rows()),"Shared displacement changed exact inventory or modified its source");
	}
	private static void componentCapacityMovesItsOwnStock() {
		var d=domain(3,3,3);byte[] masks=new byte[d.cellCount];java.util.Arrays.fill(masks,(byte)255);
		masks[13]=(byte)254;masks[12]=(byte)253;d.geometry.cutCells.restoreMicroMasks(masks);
		var key=new LiquidCellInventory.Key(13,0,1);var volume=SignedDyadic.of(.125);
		d.liquidCells.planAdditions(java.util.List.of(new LiquidCellInventory.Stock(key,volume,d.unitScale.massUnitsForNormalized(125),null,0))).commit();d.markCellInventoryChanged();d.domainFlowFields.preparePhysicalState();
		var source=d.liquidCells.snapshot();var previous=new long[d.cellCount];var next=previous.clone();next[13]=mmm.granular.SolidVolume.SVQ_PER_CELL/16;
		var prepared=GranularLiquidDisplacement.prepareInsertion(d,previous,next);
		require(prepared!=null,"Component overfill was hidden by whole-cell spare capacity");
		var future=prepared.inventory.future();
		require(future.stock(key).volume().equals(SignedDyadic.of(1,-4))
				&&future.stock(new LiquidCellInventory.Key(12,0,2)).volume().equals(SignedDyadic.of(1,-4))
				&&future.stock(new LiquidCellInventory.Key(13,0,128))==null,"Shared component projection crossed a closed support or missed its open port");
		require(future.exactVolume().equals(volume)&&future.massUnits()==d.liquidCells.massUnits()
				&&source.rows().equals(d.liquidCells.snapshot().rows()),"Component displacement changed source or exact inventory");
	}
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}