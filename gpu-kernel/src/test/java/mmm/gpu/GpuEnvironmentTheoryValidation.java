package mmm.gpu;

import java.util.List;
import java.util.Map;
import mmm.fluid.*;
import mmm.fluid.air.GasInventory;
import mmm.thermal.*;
import mmm.units.Mass;

/** Production finite wall contact compared with independent finite-capacity heat curves. */
public final class GpuEnvironmentTheoryValidation {
    private record Result(double q, double error) { }
    public static void main(String[] args) {
        int device=Integer.parseInt(args[0]);
        for(boolean air:new boolean[]{false,true}) {
            Result coarse=run(device,air,1f),fine=run(device,air,.5f);
            if(coarse.error()>1e-4 && fine.error()>=coarse.error()*.75)
                throw new AssertionError("Step refinement did not reduce heat error: coarse="+coarse+" fine="+fine);
        }
        Result microCoarse=run(device,false,1f,true),microFine=run(device,false,.5f,true);
        if(microCoarse.error()>1e-4 && microFine.error()>=microCoarse.error()*.75)throw new AssertionError("Internal micro-face refinement failed");
        verifyDiffusion(device);
        verifySteadySeries(device);
    }
    private static Result run(int device,boolean air,float dt) { return run(device,air,dt,false); }
    private static Result run(int device,boolean air,float dt,boolean micro) {
        var defs=TestFluidDefinitions.load();var thermal=defs.thermal();
        var fluids=new FluidMaterialRegistry(List.of(defs.material().entry("mmm:water").material()));
        var domain=new HostFluidState(3,3,3,new FluidSolverConfig(.5f,0,dt,200,1e-5f,.002f),defs);
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(defs.material());domain.sealDomainBoundary();
        for(int z=0;z<3;z++)for(int y=0;y<3;y++)for(int x=0;x<3;x++)if(x!=1||y!=1||z!=1)domain.setSolid(x,y,z,true);
        float[] volumes=new float[27];volumes[13]=1;domain.setVolumes(volumes);
        domain.bindGasRegistry(defs.gas());domain.bindThermal(thermal,fluids,350);
        var definition=thermal.finiteDevice("mmm:ordinary_metal_wall");long mass=Mass.fromKilograms(19.5);
        var fn=new ThermalStateFunction(thermal);int handle=thermal.materialHandle(definition.thermalMaterialId());
        double u=fn.internalEnergyAtTemperature(handle,Mass.kilograms(mass),Map.of(),293.15);
        double cw=fn.stateForInternalEnergy(handle,Mass.kilograms(mass),Map.of(),u).heatCapacityJPerK();
        var wall=new FiniteThermalDeviceState("mmm:theory","wall",definition.id(),new FiniteThermalBodyState(0,thermal.fingerprint(),definition.thermalMaterialId(),mass,u));
        var devices=new FiniteThermalDeviceInventory(thermal.fingerprint(),List.of(wall));
        var port=new FluidStateAccess.DevicePort(wall.providerId(),wall.objectId(),micro?new FluidDomainContracts.ThermalPortRequest(1,1,1,0,.005,50,.125,1,true):new FluidDomainContracts.ThermalPortRequest(1,1,1,0,.005,50,.125));
        var key=new EnvironmentThermalState.Key(0,1,1,EnvironmentThermalState.Kind.SOLID,0);
        var gas=defs.gas().createAmbient(1_000_000_000L);var gasModel=new GasThermalModel(thermal,defs.gas());
        double gasU=gasModel.internalEnergyAtTemperature(gas,293.15),ca=gasModel.state(gas,gasU).heatCapacityJPerK();
        var wallNode=new EnvironmentThermalState.Node(key,definition.thermalMaterialId(),mass,.0025,GasInventory.EMPTY,u,List.of());
        var airKey=new EnvironmentThermalState.Key(-1,1,1,EnvironmentThermalState.Kind.AIR,0);
        var nodes=air?List.of(wallNode,new EnvironmentThermalState.Node(airKey,"mmm:air",gas.totalMassUnits(),1,gas,gasU,List.of())):List.of(wallNode);
        var edges=air?List.of(new EnvironmentThermalState.Contact(0,1,5,0)):List.<EnvironmentThermalState.Contact>of();
        var world=EnvironmentThermalState.initialized("mmm:theory",thermal.fingerprint(),nodes,edges);
        var binding=new EnvironmentBinding(world.dimensionId(),List.of(new EnvironmentBinding.DeviceReference(wall.providerId(),wall.objectId(),key)));
        double conductance=ThermalTheoryOracle.seriesHeatRate(1,0,micro?.0625:.25,new double[]{.005,.25},new double[]{50,.598});
        try(var backend=new VulkanFluidSolverBackend(device)) {
            backend.configureEnvironment(world,defs);
            try(var imported=backend.prepareState(null,domain.captureAuthoritativeState(),devices,List.of(port),binding)) {
                FluidStateAccess current=imported.commit();try {
                    var cell=((ResidentFluidState)current).observeCellInventory(1,1,1);
                    double cl=cell.liquidMassKilograms()*thermal.material(thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water")).specificHeatAt(350);
                    double firstQ=0;int count=Math.round(20/dt);
                    for(int i=0;i<count;i++) {
                        try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(i,current,false,0));var prepared=candidate.claimPreparedDeviceState()) {
                            var next=prepared.commit();current.close();current=next;
                        }
                        if(i==0)firstQ=backend.captureEnvironment(world.dimensionId()).nodes().getFirst().internalEnergyJ()-u;
                        if(air)backend.advanceEnvironment(world.dimensionId(),dt);
                    }
                    var saved=backend.captureEnvironment(world.dimensionId());
                    double tl=((ResidentFluidState)current).observeCellInventory(1,1,1).temperatureKelvin().orElseThrow();
                    double tw=fn.stateForInternalEnergy(handle,Mass.kilograms(mass),Map.of(),saved.nodes().getFirst().internalEnergyJ()).temperatureK();
                    double ta=air?gasModel.state(gas,saved.nodes().get(1).internalEnergyJ()).temperatureK():0;
                    double[] theoretical=air?ThermalTheoryOracle.threeNodes(new double[]{cl,cw,ca},new double[]{conductance,5},new double[]{350,293.15,293.15},20):ThermalTheoryOracle.twoNodes(cl,cw,conductance,350,293.15,20);
                    double q=cl*(350-tl),qExpected=air?cl*(350-theoretical[0]):ThermalTheoryOracle.twoNodeHeat(cl,cw,conductance,350,293.15,20);
                    double initialRate=conductance*(350-293.15),rateError=Math.abs(firstQ/dt-initialRate)/initialRate;
                    double absoluteError=Math.abs(q-qExpected);
                    double relative=Math.abs(qExpected)>1e-9?absoluteError/Math.abs(qExpected):0;
                    double tempError=ThermalTheoryOracle.maximumError(air?new double[]{tl,tw,ta}:new double[]{tl,tw},theoretical);
                    System.out.printf("theory air=%s micro=%s dt=%.3f first_rate_W=%.9g theory_rate_W=%.9g rate_error=%.6g Q_J=%.9g theory_Q_J=%.9g relative_Q_error=%.6g temp_error_K=%.9g temperatures=%s expected=%s%n",air,micro,dt,firstQ/dt,initialRate,rateError,q,qExpected,relative,tempError,java.util.Arrays.toString(air?new double[]{tl,tw,ta}:new double[]{tl,tw}),java.util.Arrays.toString(theoretical));
                    if(!Double.isFinite(firstQ) || !Double.isFinite(q) || !Double.isFinite(qExpected)
                            || !Double.isFinite(rateError) || rateError>.01 || tempError>.01
                            || (Math.abs(qExpected)>1e-9 ? relative>.01 : absoluteError>1e-7))
                        throw new AssertionError("Production heat rate differs from theory");
                    return new Result(q,absoluteError);
                }finally{current.close();}
            }
        }
    }
    /** World diffusion at 2 Hz, including series resistance, area scaling and an insulated node. */
    private static void verifyDiffusion(int device) {
        var defs=TestFluidDefinitions.load();var thermal=defs.thermal();var def=thermal.finiteDevice("mmm:ordinary_metal_wall");
        var fn=new ThermalStateFunction(thermal);int handle=thermal.materialHandle(def.thermalMaterialId());long mass=Mass.fromKilograms(19.5);
        double hot=fn.internalEnergyAtTemperature(handle,Mass.kilograms(mass),Map.of(),350);
        double capacity=fn.stateForInternalEnergy(handle,Mass.kilograms(mass),Map.of(),hot).heatCapacityJPerK();
        var gas=defs.gas().createAmbient(1_000_000_000L);var model=new GasThermalModel(thermal,defs.gas());double cold=model.internalEnergyAtTemperature(gas,293.15);
        double gasCapacity=model.state(gas,cold).heatCapacityJPerK();
        double[] area={.25,.125,0};var nodes=new java.util.ArrayList<EnvironmentThermalState.Node>();var contacts=new java.util.ArrayList<EnvironmentThermalState.Contact>();
        for(int i=0;i<area.length;i++) {
            var solid=new EnvironmentThermalState.Key(3*i,0,0,EnvironmentThermalState.Kind.SOLID,0);
            var air=new EnvironmentThermalState.Key(3*i+1,0,0,EnvironmentThermalState.Kind.AIR,0);
            nodes.add(new EnvironmentThermalState.Node(solid,def.thermalMaterialId(),mass,.0025,GasInventory.EMPTY,hot,List.of()));
            nodes.add(new EnvironmentThermalState.Node(air,"mmm:air",gas.totalMassUnits(),1,gas,cold,List.of()));
            if(area[i]>0)contacts.add(new EnvironmentThermalState.Contact(2*i,2*i+1,area[i]/(.005/50+1.0/5),0));
        }
        var world=EnvironmentThermalState.initialized("mmm:diffusion_theory",thermal.fingerprint(),nodes,contacts);
        try(var backend=new VulkanFluidSolverBackend(device)) {
            backend.configureEnvironment(world,defs);backend.advanceEnvironment(world.dimensionId(),.5);
            var after=backend.captureEnvironment(world.dimensionId());double[] rates=new double[3];
            for(int i=0;i<area.length;i++) {
                double q=after.nodes().get(2*i+1).internalEnergyJ()-cold;rates[i]=q/.5;
                double g=area[i]==0?0:ThermalTheoryOracle.seriesHeatRate(1,0,area[i],new double[]{.005,1},new double[]{50,5});
                double expected=ThermalTheoryOracle.twoNodeHeat(capacity,gasCapacity,g,350,293.15,.5);
                double error=Math.abs(q-expected);
                if(error>Math.max(1e-7,Math.abs(expected)*.001))throw new AssertionError("2 Hz diffusion differs from theory: Q="+q+" expected="+expected);
                System.out.printf("diffusion area_m2=%.3f Q_J=%.9g theory_Q_J=%.9g error_J=%.9g%n",area[i],q,expected,error);
            }
            if(Math.abs(rates[0]-2*rates[1])>rates[0]*.001 || rates[2]!=0)throw new AssertionError("Diffusion area or insulation differs");
            if(!after.exactEnergy().equals(after.audit().expectedEnergy()))throw new AssertionError("Diffusion world audit differs");
        }
    }

    private static void verifySteadySeries(int device) {
        var defs=TestFluidDefinitions.load();var thermal=defs.thermal();var def=thermal.finiteDevice("mmm:ordinary_metal_wall");
        var fn=new ThermalStateFunction(thermal);int material=thermal.materialHandle(def.thermalMaterialId());
        double[] resistance={.5,1.0/.7,.25};double flow=ThermalTheoryOracle.seriesHeatRate(350,290,1,resistance,new double[]{1,1,1});
        double[] temperature={350-flow*resistance[0],290+flow*resistance[2]};long[] mass={Mass.fromKilograms(19.5),Mass.fromKilograms(780)};
        var nodes=new java.util.ArrayList<EnvironmentThermalState.Node>();
        for(int i=0;i<2;i++)nodes.add(new EnvironmentThermalState.Node(new EnvironmentThermalState.Key(i,0,0,EnvironmentThermalState.Kind.SOLID,0),def.thermalMaterialId(),mass[i],Mass.kilograms(mass[i])/7800,GasInventory.EMPTY,fn.internalEnergyAtTemperature(material,Mass.kilograms(mass[i]),Map.of(),temperature[i]),List.of()));
        var edges=List.of(new EnvironmentThermalState.Contact(0,-1,2,350),new EnvironmentThermalState.Contact(0,1,.7,0),new EnvironmentThermalState.Contact(1,-1,4,290));
        var initial=EnvironmentThermalState.initialized("mmm:steady",thermal.fingerprint(),nodes,edges);
        try(var backend=new VulkanFluidSolverBackend(device)) {
            backend.configureEnvironment(initial,defs);for(int i=0;i<4;i++)backend.advanceEnvironment(initial.dimensionId(),.5);
            var after=backend.captureEnvironment(initial.dimensionId());double maxDrift=0;
            for(int i=0;i<2;i++)maxDrift=Math.max(maxDrift,Math.abs(fn.stateForInternalEnergy(material,Mass.kilograms(mass[i]),Map.of(),after.nodes().get(i).internalEnergyJ()).temperatureK()-temperature[i]));
            double measured=after.audit().source().toDouble()/2,relative=Math.abs(measured-flow)/flow;
            System.out.printf("steady_series theory_W=%.9g observed_W=%.9g relative_error=%.9g drift_K=%.9g%n",flow,measured,relative,maxDrift);
            if(maxDrift>1e-9||relative>.001||!after.exactEnergy().equals(after.audit().expectedEnergy()))throw new AssertionError("Series steady state differs");
        }
    }

}
