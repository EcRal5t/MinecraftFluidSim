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
            highSpeedBoundedFlux(backend);
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
            double transferError=Math.abs(inside.transported-across.transported)
                    /Math.max(inside.transported,across.transported);
            if(inside.mass!=across.mass||volumeError>.01||temperatureError>.1||transferError>.1)
                throw new AssertionError("Block placement changed channel physics: volume="+volumeError+", temperature="
                        +temperatureError+", relativeTransfer="+transferError+", transfers="+inside.transported+","+across.transported);
            System.out.printf("Sparse channel: cells=%d boundingCells=%d volumeError=%.6g temperatureError=%.6g transfers=%.6g,%.6g relativeTransfer=%.6g%n",
                    sparse.cellCount(),24*6*16,volumeError,temperatureError,inside.transported,across.transported,transferError);
        }
    }

    /** A closed four-face circulation transports composition with a Courant number of two. */
    private static void highSpeedBoundedFlux(VulkanFluidSolverBackend backend) {
        var definitions=TestFluidDefinitions.load(); var grid=SpatialGrid.rectangular(8,2,8);
        var fluids=new FluidMaterialRegistry(List.of(definitions.material().entry("mmm:water").material()));
        var host=new HostFluidState(grid,new FluidSolverConfig(.5f,0,.1f,200,1e-5f,.002f),definitions);
        host.bindMaterialRegistry(fluids);host.sealDomainBoundary();
        float[] volume=new float[grid.cellCount()];java.util.Arrays.fill(volume,1f);host.setVolumes(volume);
        host.bindChemistry(definitions.chemistry());host.bindGasRegistry(definitions.gas());
        host.bindThermal(definitions.thermal(),fluids,310);
        var stocks=host.exportLiquidCellState().physicalStocks();
        int source=grid.cell(3,0,3), ordinal=-1;
        for(int i=0;i<stocks.size();i++)if(stocks.get(i).key().cell()==source){ordinal=i;break;}
        if(ordinal<0)throw new AssertionError("High-speed fixture has no source stock");
        var mixture=mmm.chemistry.ChemicalMixtureRecord.fromSolutesAndSolventMass(definitions.chemistry(),
                java.util.Map.of("mmm:ethanol",1_000_000L),"mmm:h2o",stocks.get(ordinal).massUnits());
        host.setMarkerChemical(ordinal,mixture.stableAmounts(definitions.chemistry()));
        var before=host.captureAuthoritativeState();
        double[] px=new double[grid.faceCount(0)],py=new double[grid.faceCount(1)],pz=new double[grid.faceCount(2)];
        double momentum=10.0*mmm.units.Mass.kilograms(stocks.get(ordinal).massUnits());
        px[grid.faceAt(4,0,3,0)-grid.faceOffset(0)]=momentum;
        pz[grid.faceAt(4,0,4,2)-grid.faceOffset(2)]=momentum;
        px[grid.faceAt(4,0,4,0)-grid.faceOffset(0)]=-momentum;
        pz[grid.faceAt(3,0,4,2)-grid.faceOffset(2)]=-momentum;
        host.restoreMacMomentum(new MacMomentumState(grid,px,py,pz));
        before=host.captureAuthoritativeState();
        var initial=totals(before); double initialEnergy=host.exactInternalEnergy().toDouble()+kinetic(before);
        try(var candidate=backend.evaluate(new FluidStepRequest(0,before,List.of(),false,0,false,false));
            var prepared=candidate.claimPreparedDeviceState();var current=prepared.commit()) {
            if(candidate.stats().substeps()!=1)throw new AssertionError("High-speed liquid was split into "+candidate.stats().substeps()+" substeps");
            var after=current.exportState();var result=totals(after);
            if(initial.mass!=result.mass || !initial.volume.equals(result.volume) || !initial.species.equals(result.species))
                throw new AssertionError("Bounded high-speed flux changed liquid inventory");
            int ethanol=definitions.chemistry().speciesHandle("mmm:ethanol");
            if(result.rows.stream().noneMatch(stock -> stock.key().cell()!=source && stock.mixture()!=null
                    && stock.mixture().amount(ethanol)>0))
                throw new AssertionError("High-speed circulation did not transport composition");
            var restored=HostFluidState.restoreAuthoritativeState(after);
            double totalEnergy=restored.exactInternalEnergy().toDouble()+kinetic(after);
            if(!((ResidentFluidState)current).observation().thermalAuditClosed()
                    || Math.abs(totalEnergy-initialEnergy)>1e-7*Math.max(1,Math.abs(initialEnergy)))
                throw new AssertionError("High-speed circulation changed total energy: before="+initialEnergy+" after="+totalEnergy);
            System.out.println("Bounded high-speed flux: courant=2 substeps=1 mass="+initial.mass);
        }
    }

    private record Inventory(long mass,mmm.units.SignedDyadic volume,java.util.Map<Integer,Long> species,
                             java.util.List<mmm.inventory.LiquidCellInventory.Stock> rows) { }
    private static Inventory totals(FluidDomainState state) {
        long mass=0;var volume=mmm.units.SignedDyadic.ZERO;var species=new java.util.TreeMap<Integer,Long>();
        var rows=state.liquid().physicalStocks();
        for(var stock:rows) {
            if(stock.volume().signum()<0 || stock.massUnits()<0)throw new AssertionError("Negative liquid inventory");
            mass=Math.addExact(mass,stock.massUnits()); volume=volume.add(stock.volume());
            if(stock.mixture()!=null)for(int i=0;i<stock.mixture().entryCount();i++) {
                long amount=stock.mixture().amountAt(i);if(amount<0)throw new AssertionError("Negative species inventory");
                species.merge(stock.mixture().speciesHandle(i),amount,Math::addExact);
            }
        }
        return new Inventory(mass,volume,species,rows);
    }
    private static double kinetic(FluidDomainState state) {
        var grid=state.grid();long[] mass=new long[grid.cellCount()];
        for(var stock:state.liquid().physicalStocks()) mass[stock.key().cell()]=Math.addExact(mass[stock.key().cell()],stock.massUnits());
        if(state.spatialGas()!=null)for(int c=0;c<mass.length;c++)mass[c]=Math.addExact(mass[c],state.spatialGas().cells().get(c).totalMassUnits());
        double[][] momentum={state.momentum().copyX(),state.momentum().copyY(),state.momentum().copyZ()};double energy=0;
        for(int f=0;f<grid.faceCount();f++) {
            int axis=grid.faceAxis(f),left=grid.faceLeft(f),right=grid.faceRight(f);
            double dual=.5*mmm.units.Mass.kilograms((left<0?0:mass[left])+(right<0?0:mass[right]));
            double value=momentum[axis][f-grid.faceOffset(axis)];
            if(dual>0)energy+=.5*value*value/dual;
        }
        return energy;
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
        var initialInventory=totals(current.exportState());
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
            var finalInventory=totals(current.exportState());
            if(!initialInventory.volume.equals(finalInventory.volume)||!initialInventory.species.equals(finalInventory.species))
                throw new AssertionError("Cross-block transport changed liquid volume or species inventory");
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
                throw new AssertionError("Paused cross-block group changed inventory, energy or momentum: liquid="
                        +firstDifference(before.liquid().stocks(),after.liquid().stocks())
                        +", gas="+firstDifference(before.spatialGas().cells(),after.spatialGas().cells())
                        +", gasEnergy="+firstDifference(before.spatialGas().internalEnergy(),after.spatialGas().internalEnergy())
                        +", momentumX="+firstDifference(before.momentum().copyX(),after.momentum().copyX())
                        +", momentumY="+firstDifference(before.momentum().copyY(),after.momentum().copyY())
                        +", momentumZ="+firstDifference(before.momentum().copyZ(),after.momentum().copyZ()));
        }
    }

    private static String firstDifference(Object a,Object b) {
        int na=a instanceof List<?> list?list.size():java.lang.reflect.Array.getLength(a);
        int nb=b instanceof List<?> list?list.size():java.lang.reflect.Array.getLength(b);
        for(int i=0;i<Math.min(na,nb);i++) {
            Object av=a instanceof List<?> list?list.get(i):java.lang.reflect.Array.get(a,i);
            Object bv=b instanceof List<?> list?list.get(i):java.lang.reflect.Array.get(b,i);
            if(!java.util.Objects.equals(av,bv))return i+":"+av+" -> "+bv;
        }
        return na==nb?"unchanged":"size "+na+" -> "+nb;
    }
}
