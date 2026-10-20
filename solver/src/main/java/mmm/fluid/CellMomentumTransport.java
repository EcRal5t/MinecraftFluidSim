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
    private final double[] volume;
    private final int[] nearest,queue,anchors;
    private final MacMomentumAdvection[] components=new MacMomentumAdvection[3];
    CellMomentumTransport(FluidDomain domain){
        this.domain=domain;massFlux=new long[][]{new long[domain.mac.velocityX.length],new long[domain.mac.velocityY.length],new long[domain.mac.velocityZ.length]};
        before=new long[domain.cellCount];after=before.clone();heat=new double[domain.cellCount];
        volume=new double[domain.cellCount];nearest=new int[domain.cellCount];queue=new int[domain.cellCount];anchors=new int[domain.cellCount];
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
        boolean tails=prepareUnresolvedNeighborhood(liquid.inventory().future());
        for(int axis=0;axis<3;axis++){
            result[axis]=components[axis].transport(before,after,massFlux,velocity[axis]);
            if(tails){
                for(int cell=0;cell<anchors.length;cell++)anchors[cell]=nearest[cell]<0?-1:resolvedFace(nearest[cell],axis);
                components[axis].mixUnresolved(after,volume,anchors,domain.geometry.cutCells.openFaceField(axis));
            }
            components[axis].addHeatToCells(after,heat);
        }
        var exactHeat=new TreeMap<Integer,SignedDyadic>();for(int cell=0;cell<heat.length;cell++)if(heat[cell]>0)exactHeat.put(cell,SignedDyadic.of(heat[cell]));
        return new Prepared(new MacMomentumState(domain.sizeX,domain.sizeY,domain.sizeZ,result[0],result[1],result[2]),Map.copyOf(exactHeat));
    }
    /** Unresolved films share motion with the nearest resolved liquid through fixed open cells. */
    private boolean prepareUnresolvedNeighborhood(LiquidCellInventory inventory){
        boolean tails=false;int end=0;Arrays.fill(nearest,-1);
        for(int cell=0;cell<volume.length;cell++){
            volume[cell]=inventory.exactCellVolume(cell).toDouble();
            if(after[cell]<=0)continue;
            if(volume[cell]>mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION){nearest[cell]=cell;queue[end++]=cell;}
            else tails=true;
        }
        if(!tails)return false;
        var cut=domain.geometry.cutCells;
        for(int head=0;head<end;head++){
            int cell=queue[head],x=cell%domain.sizeX,y=cell/domain.sizeX%domain.sizeY,z=cell/(domain.sizeX*domain.sizeY);
            // Split cut cells retain their separate motion rather than joining liquid across an internal wall.
            if(CellRemapMicroTopology.components(cut.mask(cell)).length!=1)continue;
            for(int direction=0;direction<6;direction++){
                int axis=direction/2,sign=(direction&1)==0?-1:1;
                int xx=x+(axis==0?sign:0),yy=y+(axis==1?sign:0),zz=z+(axis==2?sign:0);
                if(xx<0||yy<0||zz<0||xx>=domain.sizeX||yy>=domain.sizeY||zz>=domain.sizeZ)continue;
                int next=domain.cellIndex(xx,yy,zz);if(nearest[next]>=0||CellRemapMicroTopology.components(cut.mask(next)).length!=1)continue;
                int fx=x+(axis==0&&sign>0?1:0),fy=y+(axis==1&&sign>0?1:0),fz=z+(axis==2&&sign>0?1:0);
                int face=axis==0?domain.xFaceIndex(fx,fy,fz):axis==1?domain.yFaceIndex(fx,fy,fz):domain.zFaceIndex(fx,fy,fz);
                if(!(domain.pressureSystem.solidFaces.fixedPoreOpen(axis,face)>0))continue;
                nearest[next]=nearest[cell];queue[end++]=next;
            }
        }
        return true;
    }
    private int resolvedFace(int cell,int axis){
        int x=cell%domain.sizeX,y=cell/domain.sizeX%domain.sizeY,z=cell/(domain.sizeX*domain.sizeY);
        for(int side=0;side<2;side++){
            int xx=x+(axis==0?side:0),yy=y+(axis==1?side:0),zz=z+(axis==2?side:0);
            int coordinate=axis==0?xx:axis==1?yy:zz,size=axis==0?domain.sizeX:axis==1?domain.sizeY:domain.sizeZ;
            if(coordinate==0||coordinate==size)continue;
            int face=axis==0?domain.xFaceIndex(xx,yy,zz):axis==1?domain.yFaceIndex(xx,yy,zz):domain.zFaceIndex(xx,yy,zz);
            if(domain.pressureSystem.solidFaces.open(axis,face)>0)return face;
        }
        return -1;
    }
    private void cellMass(LiquidCellInventory inventory,long[] result){for(int cell=0;cell<result.length;cell++){long mass=0;for(var stock:inventory.stocksInCell(cell))mass=Math.addExact(mass,stock.massUnits());result[cell]=mass;}}
}
