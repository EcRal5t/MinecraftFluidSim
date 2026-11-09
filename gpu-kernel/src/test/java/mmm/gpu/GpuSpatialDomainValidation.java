package mmm.gpu;

import java.util.List;
import mmm.fluid.*;
import mmm.spatial.SpatialGrid;

/** The same enclosed channel inside one block and across sparse blocks, including checkpoint continuation. */
public final class GpuSpatialDomainValidation {
    private record Result(double[] volume,double[] temperature,long mass,double transported) { }

    public static void main(String[] args) {
        int device=args.length==0?0:Integer.parseInt(args[0]);
        try(var backend=new VulkanFluidSolverBackend(device)) {
            var inside=run(backend,SpatialGrid.rectangular(8,6,6),0);
            var sparse=new SpatialGrid(24,6,16,List.of(new SpatialGrid.Block(0,0,0),
                    new SpatialGrid.Block(1,0,0),new SpatialGrid.Block(1,0,1)));
            var across=run(backend,sparse,6);
            double volumeError=0,temperatureError=0;
            for(int i=0;i<inside.volume.length;i++) {
                volumeError=Math.max(volumeError,Math.abs(inside.volume[i]-across.volume[i]));
                if(inside.volume[i]>.01&&across.volume[i]>.01)
                    temperatureError=Math.max(temperatureError,Math.abs(inside.temperature[i]-across.temperature[i]));
            }
            if(inside.mass!=across.mass||volumeError>.01||temperatureError>.1)
                throw new AssertionError("Block placement changed channel physics: volume="+volumeError+", temperature="+temperatureError);
            System.out.printf("Sparse channel: cells=%d boundingCells=%d volumeError=%.6g temperatureError=%.6g transferred=%.6g%n",
                    sparse.cellCount(),24*6*16,volumeError,temperatureError,across.transported);
        }
    }

    private static Result run(VulkanFluidSolverBackend backend,SpatialGrid grid,int shift) {
        var definitions=TestFluidDefinitions.load();
        var water=definitions.material().entry("mmm:water").material();
        var fluids=new FluidMaterialRegistry(List.of(water));
        var domain=new HostFluidState(grid,new FluidSolverConfig(.5f,9.81f,.05f,200,1e-5f,.002f),definitions);
        domain.bindMaterialRegistry(fluids);domain.sealDomainBoundary();
        for(int c=0;c<grid.cellCount();c++) {
            int x=grid.x(c)-shift,y=grid.y(c),z=grid.z(c);
            boolean channel=x>=1&&x<=6&&y>=1&&y<=4&&z>=1&&z<=2;
            domain.setSolid(grid.x(c),y,z,!channel);
            if(channel&&y<=(x<=3?3:1))domain.setVolume(grid.x(c),y,z,.8f);
        }
        domain.bindGasRegistry(definitions.gas());
        domain.bindThermal(definitions.thermal(),fluids,300);
        for(int x=1;x<=6;x++)for(int y=1;y<=(x<=3?3:1);y++)for(int z=1;z<=2;z++)
            domain.setThermalTemperatureInCell(x+shift,y,z,x<=3?310:290);
        long mass=domain.totalMassUnits();
        FluidStateAccess current=domain.captureAuthoritativeState();
        try {
            for(int step=0;step<8;step++) {
                try(var candidate=backend.evaluate(new FluidStepRequest(step,current,List.of(),false,0,false,false));
                    var prepared=candidate.claimPreparedDeviceState()) {
                    var next=prepared.commit();current.close();current=next;
                }
                if(!((ResidentFluidState)current).observation().thermalAuditClosed())
                    throw new AssertionError("Cross-block channel thermal audit did not close");
                if(step==3) {
                    if(shift>0)checkUnavailableGroup(backend,(ResidentFluidState)current,step+1,shift);
                    var saved=current.exportState();
                    if(!saved.grid().equals(grid))throw new AssertionError("Checkpoint changed spatial membership");
                    var restored=HostFluidState.restoreAuthoritativeState(saved).captureAuthoritativeState();
                    current.close();current=restored;
                }
            }
            var host=HostFluidState.restoreAuthoritativeState(current.exportState());
            if(host.totalMassUnits()!=mass)throw new AssertionError("Cross-block transport changed liquid mass");
            double[] volume=new double[48],temperature=new double[48];int index=0;double right=0;
            for(int z=1;z<=2;z++)for(int y=1;y<=4;y++)for(int x=1;x<=6;x++) {
                volume[index]=host.volume(x+shift,y,z);
                temperature[index]=host.thermalTemperatureInCell(x+shift,y,z);
                if(x>3)right+=volume[index];index++;
            }
            double transported=right-6*.8;
            if(!(transported>.01))throw new AssertionError("Channel did not carry liquid across the shared face");
            return new Result(volume,temperature,mass,transported);
        } finally {current.close();}
    }

    private static void checkUnavailableGroup(VulkanFluidSolverBackend backend,ResidentFluidState current,long generation,int shift) {
        var before=current.exportState();var grid=before.grid();
        boolean[] world=new boolean[grid.cellCount()];java.util.Arrays.fill(world,true);
        world[grid.cell(shift+5,2,1)]=false;
        boolean[] active=current.activityGraph().available(world);
        if(active[grid.cell(shift+1,1,1)]||active[grid.cell(shift+6,1,1)])
            throw new AssertionError("Unavailable chunk did not pause both sides of the connected channel");
        try(var candidate=backend.evaluate(new FluidStepRequest(generation,current,List.of(),false,0,false,false,active))) {
            var after=candidate.state();
            if(!before.liquid().stocks().equals(after.liquid().stocks())
                    ||!before.spatialGas().cells().equals(after.spatialGas().cells())
                    ||!java.util.Arrays.equals(before.spatialGas().internalEnergy(),after.spatialGas().internalEnergy())
                    ||!java.util.Arrays.equals(before.momentum().copyX(),after.momentum().copyX())
                    ||!java.util.Arrays.equals(before.momentum().copyY(),after.momentum().copyY())
                    ||!java.util.Arrays.equals(before.momentum().copyZ(),after.momentum().copyZ()))
                throw new AssertionError("Paused cross-block group changed inventory, energy or momentum");
        }
    }
}
