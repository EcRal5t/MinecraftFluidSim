package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;

/** Actual gas capacity belongs to each coarse fixed-pore support and shares unchanged stock rows. */
final class GasComponentCapacity {
    record Component(int cell,int supportMask,SignedDyadic poreVolumeNormalized,SignedDyadic availableVolumeNormalized){ }
    /** One immutable stock-derived preparation shared by dry pieces, topology and thermodynamics. */
    static final class Prepared {
        final LiquidCellInventory.Snapshot cells;
        final byte[] masks;
        final SignedDyadic[] pores;
        final int[] offsets;
        final List<Component> components;
        final double[] dry;
        Prepared(LiquidCellInventory.Snapshot cells,byte[] masks,SignedDyadic[] pores,int[] offsets,List<Component> components,double[] dry){
            this.cells=cells;this.masks=masks;this.pores=pores;this.offsets=offsets;this.components=List.copyOf(components);this.dry=dry;
        }
    }
    private GasComponentCapacity() { }
    static Prepared prepare(FlipCutCellGeometry cut,FlipPressurePhaseGeometry phase,LiquidCellInventory.Snapshot cells,Prepared source){
        int count=cells.cellCount();var components=new ArrayList<Component>(source==null?count:source.components.size());
        byte[] masks=cut.copyMicroMasks();var pores=new SignedDyadic[count];int[] offsets=new int[count+1];double[] dry=new double[8*count];
        boolean unchanged=source!=null;
        for(int cell=0;cell<count;cell++){
            int mask=Byte.toUnsignedInt(masks[cell]);var pore=phase.poreVolume(cell);pores[cell]=pore;offsets[cell]=components.size();
            if(source!=null&&source.masks[cell]==masks[cell]&&source.pores[cell].equals(pore)
                    &&sameVolumes(source.cells.rows().get(cell),cells.rows().get(cell))){
                for(int i=source.offsets[cell];i<source.offsets[cell+1];i++){
                    var component=source.components.get(i);components.add(component);
                    int slot=8*cell+Integer.numberOfTrailingZeros(component.supportMask());dry[slot]=source.dry[slot];
                }
            }else{
                unchanged=false;int[] supports=CellRemapMicroTopology.components(mask);
                var capacities=CellRemapMicroTopology.capacities(mask,pore);
                for(int i=0;i<supports.length;i++){
                    var available=availableVolume(cell,supports[i],capacities[i],cells.rows().get(cell));
                    components.add(new Component(cell,supports[i],capacities[i],available));
                    dry[8*cell+Integer.numberOfTrailingZeros(supports[i])]=available.toDouble();
                }
            }
        }
        if(unchanged)return source;
        offsets[count]=components.size();return new Prepared(cells,masks,pores,offsets,components,dry);
    }
    /** Shared stock-derived endpoint for both dry geometry presence and actual chamber capacity. */
    private static SignedDyadic availableVolume(int cell,int support,SignedDyadic capacity,List<LiquidCellInventory.Stock> row){
        var liquid=SignedDyadic.ZERO;
        for(var stock:row)if(stock.key().supportMask()==support)liquid=liquid.add(stock.volume());
        var available=capacity.subtract(liquid);
        if(available.signum()<0)throw blocked("Liquid inventory exceeds its open component capacity",cell);
        return available;
    }
    private static boolean sameVolumes(List<LiquidCellInventory.Stock> first,List<LiquidCellInventory.Stock> second){
        if(first==second)return true;
        if(first.size()!=second.size())return false;
        for(int i=0;i<first.size();i++)if(!first.get(i).key().equals(second.get(i).key())||!first.get(i).volume().equals(second.get(i).volume()))return false;
        return true;
    }
    private static GasTopologyBlockedException blocked(String message,int cell){return new GasTopologyBlockedException(message+" at cell "+cell);}
}
