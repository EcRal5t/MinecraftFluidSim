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
        final FixedGeometrySnapshot fixedGeometry;
        final byte[] masks;
        final SignedDyadic[] pores;
        final int[] offsets;
        final List<Component> components;
        final double[] dry;
        Prepared(LiquidCellInventory.Snapshot cells,FixedGeometrySnapshot fixedGeometry,SignedDyadic[] pores,int[] offsets,List<Component> components,double[] dry){
            this.cells=cells;this.fixedGeometry=fixedGeometry;this.masks=fixedGeometry.masksView();this.pores=pores;this.offsets=offsets;this.components=List.copyOf(components);this.dry=dry;
        }
    }
    private static final SignedDyadic[] OPEN_PORE=openPores();
    private static SignedDyadic[] openPores(){var values=new SignedDyadic[9];for(int n=0;n<=8;n++)values[n]=SignedDyadic.of(n,-3);return values;}
    private GasComponentCapacity() { }
    static Prepared prepare(FlipCutCellGeometry cut,FlipPressurePhaseGeometry phase,LiquidCellInventory.Snapshot cells,Prepared source){
        return prepare(cut,phase,null,null,cells,source);
    }
    /** The accepted transport already owns each exact fixed-support pore capacity. */
    static Prepared prepare(FlipCutCellGeometry cut,SignedDyadic[] capacities,LiquidCellInventory.Snapshot cells,Prepared source){
        return prepare(cut,null,capacities,null,cells,source);
    }
    static Prepared prepare(FlipCutCellGeometry cut,long[] occupied,LiquidCellInventory.Snapshot cells,Prepared source){
        return prepare(cut,null,null,occupied,cells,source);
    }
    private static Prepared prepare(FlipCutCellGeometry cut,FlipPressurePhaseGeometry phase,SignedDyadic[] preparedPores,long[] occupied,
                                    LiquidCellInventory.Snapshot cells,Prepared source){
        int count=cells.cellCount();var components=new ArrayList<Component>(source==null?count:source.components.size());
        var fixedGeometry=cut.snapshot();byte[] masks=fixedGeometry.masksView();var pores=new SignedDyadic[count];int[] offsets=new int[count+1];double[] dry=new double[count];
        boolean unchanged=source!=null;
        for(int cell=0;cell<count;cell++){
            int mask=Byte.toUnsignedInt(masks[cell]),support=~mask&255;
            var pore=phase!=null?phase.poreVolume(cell):occupied==null?SignedDyadic.ZERO
                    :occupied[cell]==0?OPEN_PORE[Integer.bitCount(~mask&255)]
                    :OPEN_PORE[Integer.bitCount(~mask&255)].subtract(SignedDyadic.of(occupied[cell],-24));
            if(preparedPores!=null)pore=preparedPores[cell];
            if(pore.signum()<0)throw blocked("Solid occupation exceeds actual open pore",cell);
            pores[cell]=pore;offsets[cell]=components.size();
            if(source!=null&&source.masks[cell]==masks[cell]&&source.pores[cell].equals(pore)
                    &&sameVolumes(source.cells.rows().get(cell),cells.rows().get(cell))){
                for(int i=source.offsets[cell];i<source.offsets[cell+1];i++){
                    var component=source.components.get(i);components.add(component);
                    dry[cell]=source.dry[cell];
                }
            }else{
                unchanged=false;
                if(support!=0) {
                    var available=availableVolume(cell,support,pore,cells.rows().get(cell));
                    components.add(new Component(cell,support,pore,available));
                    dry[cell]=available.toDouble();
                }
            }
        }
        if(unchanged)return source;
        offsets[count]=components.size();return new Prepared(cells,fixedGeometry,pores,offsets,components,dry);
    }
    /** Shared stock-derived endpoint for both dry geometry presence and actual chamber capacity. */
    private static SignedDyadic availableVolume(int cell,int support,SignedDyadic capacity,List<LiquidCellInventory.Stock> row){
        var liquid=SignedDyadic.ZERO;
        for(var stock:row)if(stock.key().supportMask()==support)liquid=liquid.add(stock.volume());
        var available=capacity.subtract(liquid);
        if(available.signum()<0){
            if(available.negate().compareTo(SignedDyadic.of(mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION))>0)
                throw blocked("Liquid inventory exceeds its open component capacity",cell);
            return SignedDyadic.ZERO;
        }
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
