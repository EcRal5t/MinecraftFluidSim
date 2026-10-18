package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** One donor pass shares the liquid quantity transfers and their local mixing heat. */
final class CellMomentumTransport {
    record Prepared(MacMomentumState momentum,Map<Integer,SignedDyadic> heatByCell) { }
    private final FluidDomain domain;
    private final long[][] massFlux;
    private final long[] before,after;
    private final double[] heat;
    private final double[][] velocity;
    private final MacMomentumAdvection[] components=new MacMomentumAdvection[3];
    CellMomentumTransport(FluidDomain domain){
        this.domain=domain;massFlux=new long[][]{new long[domain.mac.velocityX.length],new long[domain.mac.velocityY.length],new long[domain.mac.velocityZ.length]};
        before=new long[domain.cellCount];after=before.clone();heat=new double[domain.cellCount];
        velocity=new double[][]{new double[domain.mac.velocityX.length],new double[domain.mac.velocityY.length],new double[domain.mac.velocityZ.length]};
        for(int axis=0;axis<3;axis++)components[axis]=new MacMomentumAdvection(domain.sizeX,domain.sizeY,domain.sizeZ,axis);
    }
    Prepared prepare(CellLiquidVolumeTransport.Prepared liquid){
        return prepare(liquid,new float[][]{domain.mac.velocityX,domain.mac.velocityY,domain.mac.velocityZ});
    }
    Prepared prepare(CellLiquidVolumeTransport.Prepared liquid,float[][] initial){
        for(int axis=0;axis<3;axis++)for(int i=0;i<velocity[axis].length;i++)velocity[axis][i]=initial[axis][i];
        Arrays.fill(heat,0);double[][] result=new double[3][];
        for(var column:massFlux)Arrays.fill(column,0);var flux=liquid.volumeFlux();
        for(var transfer:liquid.inventory().transfers())if(transfer.massUnits()>0){
            int face=transfer.face(),axis=flux.axis[face],right=flux.right[face],x=right%domain.sizeX,y=right/domain.sizeX%domain.sizeY,z=right/(domain.sizeX*domain.sizeY);
            int index=(z*(domain.sizeY+(axis==1?1:0))+y)*(domain.sizeX+(axis==0?1:0))+x;
            massFlux[axis][index]+=transfer.massUnits()*(transfer.source().cell()==flux.left[face]?1:-1);
        }
        cellMass(liquid.sourceInventory(),before);cellMass(liquid.inventory().future(),after);
        for(int axis=0;axis<3;axis++){
            result[axis]=components[axis].transport(before,after,massFlux,velocity[axis]);
            components[axis].addHeatToCells(after,heat);
        }
        var exactHeat=new TreeMap<Integer,SignedDyadic>();for(int cell=0;cell<heat.length;cell++)if(heat[cell]>0)exactHeat.put(cell,SignedDyadic.of(heat[cell]));
        return new Prepared(new MacMomentumState(domain.sizeX,domain.sizeY,domain.sizeZ,result[0],result[1],result[2]),Map.copyOf(exactHeat));
    }
    private void cellMass(LiquidCellInventory inventory,long[] result){for(int cell=0;cell<result.length;cell++){long mass=0;for(var stock:inventory.stocksInCell(cell))mass=Math.addExact(mass,stock.massUnits());result[cell]=mass;}}
}
