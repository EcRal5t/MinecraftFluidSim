package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** One donor pass shares the liquid quantity transfers and their local mixing heat. */
final class CellMomentumTransport {
    record Prepared(MacMomentumState momentum,Map<Integer,SignedDyadic> heatByCell,CellMacStage.WallImpulse wallImpulse) { }
    private final FluidDomain domain;
    private final long[][] massFlux;
    private final long[] before,after;
    private final double[] heat;
    private final double[][] velocity;
    private final double[] volume;
    private final int[] anchors;
    private final MacMomentumAdvection[] components=new MacMomentumAdvection[3];
    CellMomentumTransport(FluidDomain domain){
        this.domain=domain;massFlux=new long[][]{new long[domain.mac.velocityX.length],new long[domain.mac.velocityY.length],new long[domain.mac.velocityZ.length]};
        before=new long[domain.cellCount];after=before.clone();heat=new double[domain.cellCount];
        volume=new double[domain.cellCount];anchors=new int[domain.cellCount];
        velocity=new double[][]{new double[domain.mac.velocityX.length],new double[domain.mac.velocityY.length],new double[domain.mac.velocityZ.length]};
        for(int axis=0;axis<3;axis++)components[axis]=new MacMomentumAdvection(domain.sizeX,domain.sizeY,domain.sizeZ,axis);
    }
    Prepared prepare(CellMacStage.PreparedLiquid liquid){
        return prepare(liquid,new float[][]{domain.mac.velocityX,domain.mac.velocityY,domain.mac.velocityZ});
    }
    Prepared prepare(CellMacStage.PreparedLiquid liquid,float[][] initial){
        cellMass(liquid.sourceInventory(),before);cellMass(liquid.inventory().future(),after);
        for(int cell=0;cell<volume.length;cell++)volume[cell]=liquid.inventory().future().exactCellVolume(cell).toDouble();
        return prepare(liquid.volumeFlux(),liquid.inventory().transfers(),before,after,initial);
    }
    Prepared prepare(ConservativeLiquidFlux flux,LiquidCellInventory.TransportQuantities quantities){
        System.arraycopy(quantities.afterVolume(),0,volume,0,volume.length);
        return prepare(flux,quantities.transfers(),quantities.beforeMass(),quantities.afterMass(),
                new float[][]{domain.mac.velocityX,domain.mac.velocityY,domain.mac.velocityZ});
    }
    private Prepared prepare(ConservativeLiquidFlux flux,java.util.List<LiquidCellInventory.Transfer> transfers,long[] beforeMass,long[] afterMass,float[][] initial){
        for(int axis=0;axis<3;axis++)for(int i=0;i<velocity[axis].length;i++)velocity[axis][i]=initial[axis][i];
        Arrays.fill(heat,0);double[][] result=new double[3][];
        for(var column:massFlux)Arrays.fill(column,0);
        for(var transfer:transfers)if(transfer.massUnits()>0){
            int face=transfer.face(),axis=flux.axis[face],right=flux.right[face],x=right%domain.sizeX,y=right/domain.sizeX%domain.sizeY,z=right/(domain.sizeX*domain.sizeY);
            int index=(z*(domain.sizeY+(axis==1?1:0))+y)*(domain.sizeX+(axis==0?1:0))+x;
            massFlux[axis][index]+=transfer.massUnits()*(transfer.source().cell()==flux.left[face]?1:-1);
        }
        for(int axis=0;axis<3;axis++){
            result[axis]=components[axis].transport(beforeMass,afterMass,massFlux,velocity[axis]);
            prepareMergeAnchors(afterMass,axis);
            components[axis].mixUnresolved(afterMass,volume,anchors,domain.geometry.cutCells.openFaceField(axis),
                    domain.pressurePhaseGeometry,domain.geometry.cutCells,domain.cellMacStage.constrainedNormalFaces(axis));
            components[axis].addHeatToCells(afterMass,heat);
        }
        var walls=domain.cellMacStage.stopWallMomentum(result,afterMass,heat,null);
        var exactHeat=new TreeMap<Integer,SignedDyadic>();for(int cell=0;cell<heat.length;cell++)if(heat[cell]>0)exactHeat.put(cell,SignedDyadic.of(heat[cell]));
        return new Prepared(new MacMomentumState(domain.sizeX,domain.sizeY,domain.sizeZ,result[0],result[1],result[2]),Map.copyOf(exactHeat),walls);
    }
    /** Same-cell parallel faces share a finite control volume; split supports retain separate motion. */
    private void prepareMergeAnchors(long[] mass,int axis){
        Arrays.fill(anchors,-1);
        int stride=axis==0?1:axis==1?domain.sizeX:domain.sizeX*domain.sizeY;
        for(int cell=0;cell<anchors.length;cell++){
            if(mass[cell]<=0||CellRemapMicroTopology.components(domain.geometry.cutCells.mask(cell)).length!=1)continue;
            int x=cell%domain.sizeX,y=cell/domain.sizeX%domain.sizeY,z=cell/(domain.sizeX*domain.sizeY);
            long largest=-1;
            for(int side=0;side<2;side++){
                int xx=x+(axis==0?side:0),yy=y+(axis==1?side:0),zz=z+(axis==2?side:0);
                int coordinate=axis==0?xx:axis==1?yy:zz,size=axis==0?domain.sizeX:axis==1?domain.sizeY:domain.sizeZ;
                if(coordinate==0||coordinate==size)continue;
                int face=axis==0?domain.domainFlowFields.xFaceIndex(xx,yy,zz):axis==1?domain.domainFlowFields.yFaceIndex(xx,yy,zz):domain.domainFlowFields.zFaceIndex(xx,yy,zz);
                if(domain.cellMacStage.isUnresolvedWallFace(axis,face))continue;
                if(!(domain.pressureSystem.solidFaces.fixedPoreOpen(axis,face)>0))continue;
                long dual=mass[cell]+mass[cell+(side==0?-stride:stride)];
                if(dual>largest){largest=dual;anchors[cell]=face;}
            }
        }
    }
    private void cellMass(LiquidCellInventory inventory,long[] result){for(int cell=0;cell<result.length;cell++){long mass=0;for(var stock:inventory.stocksInCell(cell))mass=Math.addExact(mass,stock.massUnits());result[cell]=mass;}}
}
