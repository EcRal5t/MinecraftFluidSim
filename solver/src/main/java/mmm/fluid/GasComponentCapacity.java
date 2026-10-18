package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;

/** Actual gas capacity belongs to each coarse fixed-pore support and shares unchanged stock rows. */
final class GasComponentCapacity {
    record Component(int cell,int supportMask,SignedDyadic poreVolumeNormalized,SignedDyadic availableVolumeNormalized){ }
    private GasComponentCapacity() { }
    static List<Component> capture(FluidDomain domain,GasComplementGeometry geometry,PhaseLiquidGeometryEdit edit){
        if(domain==null)throw new IllegalArgumentException("Gas component domain is missing");
        long[] occupied=domain.granularCoupling==null?new long[domain.cellCount]:domain.granularCoupling.copySolidSvq();
        return capture(domain,geometry,edit,occupied);
    }
    /** Isolated geometric edits supply their one changed row directly. */
    static List<Component> capture(FluidDomain domain,GasComplementGeometry geometry,PhaseLiquidGeometryEdit edit,long[] occupied){
        return capture(domain,geometry,occupied,domain.liquidCells.snapshot(),null,edit);
    }
    static List<Component> capture(FluidDomain domain,GasComplementGeometry geometry,long[] occupied,
                                   LiquidCellInventory.Snapshot cells,GasRuntimeGeometry source){
        return capture(domain,geometry,occupied,cells,source,null);
    }
    private static List<Component> capture(FluidDomain domain,GasComplementGeometry geometry,long[] occupied,
                                          LiquidCellInventory.Snapshot cells,GasRuntimeGeometry source,PhaseLiquidGeometryEdit edit){
        if(domain==null||geometry==null||geometry.sizeX()!=domain.sizeX||geometry.sizeY()!=domain.sizeY||geometry.sizeZ()!=domain.sizeZ
                ||occupied==null||occupied.length!=domain.cellCount||cells==null||cells.cellCount()!=domain.cellCount)
            throw new IllegalArgumentException("Gas capacity inputs differ in shape");
        double h=domain.config.cellSize();
        if(geometry.cellVolumeCubicMeters()!=h*h*h)throw new IllegalArgumentException("Gas capacity metric differs from the domain");
        var result=new ArrayList<Component>();var previous=source==null?List.<Component>of():source.capacity().components();int end=0;
        for(int cell=0;cell<domain.cellCount;cell++){
            int start=end;while(end<previous.size()&&previous.get(end).cell()==cell)end++;
            if((edit==null||edit.cell()!=cell)&&sameCell(cell,geometry,occupied,cells,source)){
                for(int i=start;i<end;i++)result.add(previous.get(i));
            }else appendCell(geometry,cell,occupied[cell],cells.rows().get(cell),edit!=null&&edit.cell()==cell?edit:null,result);
        }
        return List.copyOf(result);
    }
    private static void appendCell(GasComplementGeometry geometry,int cell,long occupied,List<LiquidCellInventory.Stock> row,
                                   PhaseLiquidGeometryEdit edit,List<Component> result){
        int mask=geometry.collisionMask(cell);SignedDyadic open=SignedDyadic.of(Integer.bitCount(~mask&255),-3),solid=SignedDyadic.of(occupied,-24);
        if(solid.signum()<0||solid.compareTo(open)>0)throw blocked("Solid inventory exceeds fixed open capacity",cell);
        if(!geometry.poreVolume(cell).equals(open.subtract(solid)))throw blocked("Gas geometry pore capacity differs from occupancy",cell);
        if(edit!=null&&edit.collisionMask()!=mask)throw new IllegalArgumentException("Geometric edit collision mask differs");
        int[] supports=CellRemapMicroTopology.components(mask);var capacities=CellRemapMicroTopology.capacities(mask,open.subtract(solid));
        for(int i=0;i<supports.length;i++){
            int support=supports[i];SignedDyadic liquid=SignedDyadic.ZERO;
            if(edit==null){for(var stock:row)if(stock.key().supportMask()==support)liquid=liquid.add(stock.volume());}
            else liquid=edit.components().get(i).volume();
            var available=capacities[i].subtract(liquid);if(available.signum()<0)throw blocked("Liquid inventory exceeds its open component capacity",cell);
            result.add(new Component(cell,support,capacities[i],available));
        }
    }
    private static boolean sameCell(int cell,GasComplementGeometry geometry,long[] occupied,LiquidCellInventory.Snapshot cells,GasRuntimeGeometry source){
        if(source==null||source.liquidCells().cellCount()!=cells.cellCount()||source.occupiedSvq(cell)!=occupied[cell])return false;
        var previous=source.geometry();
        if(previous.collisionMask(cell)!=geometry.collisionMask(cell)||!previous.poreVolume(cell).equals(geometry.poreVolume(cell)))return false;
        var old=source.liquidCells().rows().get(cell);var next=cells.rows().get(cell);
        if(old!=next){
            if(old.size()!=next.size())return false;
            for(int i=0;i<old.size();i++)if(!old.get(i).key().equals(next.get(i).key())||!old.get(i).volume().equals(next.get(i).volume()))return false;
        }
        return true;
    }
    private static GasTopologyBlockedException blocked(String message,int cell){return new GasTopologyBlockedException(message+" at cell "+cell);}
}
