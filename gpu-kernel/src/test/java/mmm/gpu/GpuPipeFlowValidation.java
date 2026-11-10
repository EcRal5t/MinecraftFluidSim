package mmm.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mmm.fluid.*;
import mmm.inventory.DeviceLiquidInventory;
import mmm.thermal.*;
import mmm.units.*;

/** Real resident liquid steps through quarter-block wall geometry and a half-block bore. */
public final class GpuPipeFlowValidation {
    private record Block(int x,int z) { }
    private record Scenario(int length,boolean bend,boolean fork,boolean closed,double viscosity) { }
    private record Result(double received,long downstreamSpecies,DeviceLiquidInventory liquid,MacMomentumState momentum,ThermalAuditLedger audit) { }
    private GpuPipeFlowValidation() { }
    public static void main(String[] args) {
        int device=args.length>0?Integer.parseInt(args[0]):0, steps=args.length>1?Integer.parseInt(args[1]):16;
        try(var context=new VulkanDeviceContext(device,true,true);var geometry=new VulkanLiquidGeometry(context);
            var projection=new VulkanLiquidProjection(context);var pressure=new VulkanPressureKernel(context,4096);
            var multigrid=new VulkanPressureMultigrid(context)) {
            var straight=run(context,geometry,projection,pressure,multigrid,new Scenario(3,false,false,false,20),steps,null);
            var viscous=run(context,geometry,projection,pressure,multigrid,new Scenario(3,false,false,false,40),steps,null);
            var longer=run(context,geometry,projection,pressure,multigrid,new Scenario(5,false,false,false,20),steps,null);
            var bend=run(context,geometry,projection,pressure,multigrid,new Scenario(3,true,false,false,20),steps,null);
            var fork=run(context,geometry,projection,pressure,multigrid,new Scenario(3,false,true,false,20),steps,null);
            var blocked=run(context,geometry,projection,pressure,multigrid,new Scenario(3,false,false,true,20),steps,null);
            var opened=run(context,geometry,projection,pressure,multigrid,new Scenario(3,false,false,false,20),steps,blocked);
            System.out.printf("pipe volume straight=%g viscous=%g long=%g bend=%g fork=%g blocked=%g reopened=%g%n",
                    straight.received,viscous.received,longer.received,bend.received,fork.received,blocked.received,opened.received);
            require(straight.received>1e-8 && bend.received>1e-8 && fork.received>1e-8,"pipe did not pass liquid");
            require(viscous.received<straight.received && longer.received<straight.received,"viscosity or length trend differs");
            require(blocked.downstreamSpecies==0 && opened.downstreamSpecies>0,"closed valve leaked or reopened valve failed to flow");
        }
    }
    private static Result run(VulkanDeviceContext context,VulkanLiquidGeometry geometry,VulkanLiquidProjection projection,
            VulkanPressureKernel pressure,VulkanPressureMultigrid multigrid,Scenario scenario,int steps,Result prior) {
        int sx=2*scenario.length+8,sy=6,sz=8,n=sx*sy*sz,right=sx-4;
        var grid=mmm.spatial.SpatialGrid.rectangular(sx,sy,sz);
        var nodes=new HashSet<Block>();
        for(int x=2;x<2+scenario.length;x++)nodes.add(new Block(x,scenario.bend && x>=3?2:1));
        if(scenario.bend)nodes.add(new Block(2,2));
        if(scenario.fork)for(int x=2;x<2+scenario.length;x++)nodes.add(new Block(x,2));
        byte[] masks=new byte[n];int[] offsets=new int[n+1];var volumes=new ArrayList<SignedDyadic>();
        var supports=new ArrayList<Integer>();
        for(int c=0;c<n;c++) {
            int cx=grid.x(c),cy=grid.y(c),cz=grid.z(c),mask=0;
            for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
                int mx=cx*2+x,my=cy*2+y,mz=cz*2+z;
                boolean reservoir=(cx<4 || cx>=right) && cz>=1 && cz<=6;
                boolean bore=pipeOpen(nodes,mx,my,mz,right,scenario.closed);
                if(!reservoir&&!bore)mask|=1<<(x+2*y+4*z);
            }
            masks[c]=(byte)mask;
            int open=(~mask)&255;
            boolean wet=open!=0 && (cx<4?cy<4:cx>=right?cy<2:true);
            if(wet){supports.add(open);volumes.add(SignedDyadic.of(Integer.bitCount(open)/8.0));}
            offsets[c+1]=supports.size();
        }
        int stocks=supports.size();long[] masses=new long[stocks],energy=new long[stocks],amount=new long[stocks],residual=new long[stocks];
        int[] speciesOffsets=new int[stocks+1],present=new int[stocks];

        for(int i=0;i<stocks;i++) {
            double volume=volumes.get(i).toDouble();masses[i]=Mass.fromKilograms(125*volume);
            double u=125*volume*4200*293.15;energy[i]=Double.doubleToRawLongBits(u);
            amount[i]=masses[i]/180;residual[i]=masses[i]-amount[i]*180;present[i]=1;speciesOffsets[i+1]=i+1;
        }
        var initial=new DeviceLiquidInventory(offsets,new int[stocks],supports.stream().mapToInt(Integer::intValue).toArray(),
                DeviceDyadicArray.encode(volumes),masses,energy,present,speciesOffsets,new int[stocks],amount,residual);
        var momentum=new MacMomentumState(sx,sy,sz,new double[(sx+1)*sy*sz],new double[sx*(sy+1)*sz],new double[sx*sy*(sz+1)]);
        var inventory=prior==null?initial:prior.liquid; if(prior!=null)momentum=prior.momentum;
        var beforeMass=sum(inventory.massUnits());var beforeSpecies=sum(inventory.speciesAmounts());
        double beforeVolume=totalVolume(inventory),beforeRight=rightVolume(inventory,grid,right);
        long beforeDownstream=downstreamSpecies(inventory,grid,7);
        var fluids=new FluidMaterialRegistry(List.of(TestFluidDefinitions.load().material().entry("mmm:water").material()));
        var constitutive=TestFluidDefinitions.load().material();
        var caloric=List.of(new ThermalMaterialDefinition("mmm:water",ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water",
                293.15,4200*293.15,0,List.of(new ThermalMaterialDefinition.CaloricSegment(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K,10000,4200))));
        var definitions=new VulkanLiquidRegion.MechanicalDefinitions(n,4*n,4*n,4,-40,new int[]{fluids.phaseHandle(0)},new long[]{180},
                new double[]{4200*293.15},new double[]{0},1,VulkanLiquidThermodynamics.tables(caloric),
                new VulkanLiquidViscosity.Tables(new int[]{0,1},new double[]{273,1000,scenario.viscosity,scenario.viscosity}),
                VulkanLiquidForces.tables(fluids,constitutive),VulkanSurfaceTension.tables(fluids),true);
        var parameters=new VulkanLiquidProjection.Parameters(.5f,.05f,9.81f,1e-5f,200);
        try(var region=new VulkanLiquidRegion(context,geometry,projection,pressure,multigrid,inventory,momentum,
                FixedGeometrySnapshot.fromMasks(sx,sy,sz,masks),new long[n],definitions,null,null,null)) {
            var audit=prior==null?ThermalAuditLedger.initialized(SignedDyadic.sum(Arrays.stream(energy).mapToDouble(Double::longBitsToDouble).toArray())):prior.audit;
            for(int step=0;step<steps;step++)try(var source=region.captureState();var prepared=region.prepareMechanical(source,parameters);var successor=prepared.captureState()) {
                audit=successor.thermalPosting().apply(audit);
                require(audit.acceptsInventory(successor.exactThermalInventory()),"pipe energy audit changed at step "+step);
                prepared.commit();
            }
            var result=region.checkpointLiquid();
            require(sum(result.inventory().massUnits())==beforeMass,"pipe mass changed");
            require(sum(result.inventory().speciesAmounts())==beforeSpecies,"pipe species changed");
            require(Math.abs(totalVolume(result.inventory())-beforeVolume)<1e-8,"pipe volume changed");
            return new Result(rightVolume(result.inventory(),grid,right)-beforeRight,downstreamSpecies(result.inventory(),grid,7)-beforeDownstream,result.inventory(),result.momentum(),audit);
        }
    }
    private static boolean pipeOpen(Set<Block> nodes,int x,int y,int z,int right,boolean closed) {
        if(y<1||y>2)return false;
        int bx=x/4,bz=z/4,lx=x%4,lz=z%4;
        if(!nodes.contains(new Block(bx,bz)))return false;
        boolean centreX=lx==1||lx==2,centreZ=lz==1||lz==2;
        boolean east=nodes.contains(new Block(bx+1,bz)) || (bx+1)*2==right;
        boolean west=nodes.contains(new Block(bx-1,bz)) || bx==2;
        boolean north=nodes.contains(new Block(bx,bz-1)),south=nodes.contains(new Block(bx,bz+1));
        boolean open=(centreX&&centreZ)||(centreZ&&((lx==0&&west)||(lx==3&&east)))
                ||(centreX&&((lz==0&&north)||(lz==3&&south)));
        if(closed && bx==3 && lx==1)return false;
        return open;
    }
    private static long sum(long[] values){long sum=0;for(long value:values)sum=Math.addExact(sum,value);return sum;}
    private static double totalVolume(DeviceLiquidInventory inventory){double sum=0;for(int i=0;i<inventory.stockCount();i++)sum+=inventory.volumes().valueAt(i).toDouble();return sum;}
    private static double rightVolume(DeviceLiquidInventory inventory,mmm.spatial.SpatialGrid grid,int right){
        int[] offsets=inventory.cellOffsets();double sum=0;for(int c=0;c<grid.cellCount();c++)if(grid.x(c)>=right)
            for(int i=offsets[c];i<offsets[c+1];i++)sum+=inventory.volumes().valueAt(i).toDouble();return sum;
    }
    private static long downstreamSpecies(DeviceLiquidInventory inventory,mmm.spatial.SpatialGrid grid,int firstX) {
        int[] cells=inventory.cellOffsets(),species=inventory.speciesOffsets();long[] amounts=inventory.speciesAmounts();long sum=0;
        for(int cell=0;cell<grid.cellCount();cell++)if(grid.x(cell)>=firstX)
            for(int row=cells[cell];row<cells[cell+1];row++)for(int i=species[row];i<species[row+1];i++)sum=Math.addExact(sum,amounts[i]);
        return sum;
    }
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
