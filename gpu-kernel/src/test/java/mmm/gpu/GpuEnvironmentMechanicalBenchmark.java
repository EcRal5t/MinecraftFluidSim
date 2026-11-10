package mmm.gpu;

import java.util.*;
import mmm.fluid.*;
import mmm.fluid.air.GasInventory;
import mmm.thermal.*;

/** Interleaved full production steps on a 64-cubed grid and its world shell. */
public final class GpuEnvironmentMechanicalBenchmark {
    private record Sample(FluidStateAccess next,long time,long gpu,long record,long waiting,long submits) {}
    public static void main(String[] args) {
        int device=Integer.parseInt(args[0]),warm=Integer.parseInt(args[1]),samples=Integer.parseInt(args[2]);
        var defs=TestFluidDefinitions.load();var thermal=defs.thermal();int n=64,cells=n*n*n;
        var fluids=new FluidMaterialRegistry(List.of(defs.material().entry("mmm:water").material(),defs.material().entry("mmm:oil").material()));
        var domain=new HostFluidState(n,n,n,new FluidSolverConfig(.5f,9.81f,.05f,200,1e-5f,.002f),defs);
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(defs.material());domain.sealDomainBoundary();
        float[] volumes=new float[cells];int[] materials=new int[cells];
        for(int z=0;z<n;z++)for(int y=0;y<n;y++)for(int x=0;x<n;x++) {
            int cell=(z*n+y)*n+x;
            if(y<12 && x<32){volumes[cell]=.8f;materials[cell]=(z<n/2?0:1);}
        }
        domain.setVolumes(volumes,materials);domain.bindGranular(defs.granular(),fluids,defs.material());
        domain.bindChemistry(defs.chemistry());domain.bindGasRegistry(defs.gas());domain.bindThermal(thermal,fluids,300);
        var initial=domain.captureAuthoritativeState();var definition=thermal.finiteDevice("mmm:ordinary_metal_wall");
        double wallU=new ThermalStateFunction(thermal).internalEnergyAtTemperature(thermal.materialHandle(definition.thermalMaterialId()),definition.bodyMassKilograms(),Map.of(),293.15);
        var body=new FiniteThermalBodyState(0,thermal.fingerprint(),definition.thermalMaterialId(),definition.bodyMassUnits(),wallU);
        var gas=defs.gas().createAmbient(1_000_000_000L);double airU=new GasThermalModel(thermal,defs.gas()).internalEnergyAtTemperature(gas,293.15);
        int side=40;int[] indices=new int[side*side*side];Arrays.fill(indices,-1);
        var nodes=new ArrayList<EnvironmentThermalState.Node>();var refs=new ArrayList<EnvironmentBinding.DeviceReference>();
        var walls=new ArrayList<FiniteThermalDeviceState>();var ports=new ArrayList<FluidStateAccess.DevicePort>();
        for(int z=0;z<side;z++)for(int y=0;y<side;y++)for(int x=0;x<side;x++) {
            int wx=x-4,wy=y-4,wz=z-4;
            if(wx>=0&&wx<32&&wy>=0&&wy<32&&wz>=0&&wz<32)continue;
            indices[(z*side+y)*side+x]=nodes.size();
            int direction=wx==-1&&wy>=0&&wy<32&&wz>=0&&wz<32?0:wx==32&&wy>=0&&wy<32&&wz>=0&&wz<32?1:
                    wy==-1&&wx>=0&&wx<32&&wz>=0&&wz<32?2:wy==32&&wx>=0&&wx<32&&wz>=0&&wz<32?3:
                    wz==-1&&wx>=0&&wx<32&&wy>=0&&wy<32?4:wz==32&&wx>=0&&wx<32&&wy>=0&&wy<32?5:-1;
            if(direction<0) {
                var key=new EnvironmentThermalState.Key(wx,wy,wz,EnvironmentThermalState.Kind.AIR,0);
                nodes.add(new EnvironmentThermalState.Node(key,"mmm:air",gas.totalMassUnits(),1,gas,airU,List.of()));
            } else {
                var key=new EnvironmentThermalState.Key(wx,wy,wz,EnvironmentThermalState.Kind.SOLID,0);
                String id="wall-"+walls.size();var wall=new FiniteThermalDeviceState("mmm:benchmark",id,definition.id(),body);walls.add(wall);
                refs.add(new EnvironmentBinding.DeviceReference(wall.providerId(),id,key));
                nodes.add(new EnvironmentThermalState.Node(key,definition.thermalMaterialId(),definition.bodyMassUnits(),1,GasInventory.EMPTY,wallU,List.of()));
                for(int b=0;b<2;b++)for(int a=0;a<2;a++) {
                    int cx=direction<2?(direction==0?0:n-1):2*wx+a;
                    int cy=direction<2?2*wy+a:direction<4?(direction==2?0:n-1):2*wy+b;
                    int cz=direction<2?2*wz+b:direction<4?2*wz+b:(direction==4?0:n-1);
                    ports.add(new FluidStateAccess.DevicePort(wall.providerId(),id,new FluidDomainContracts.ThermalPortRequest(cx,cy,cz,direction,.5,50,.125)));
                }
            }
        }
        var edges=new ArrayList<EnvironmentThermalState.Contact>();
        for(int z=0;z<side;z++)for(int y=0;y<side;y++)for(int x=0;x<side;x++) {
            int a=indices[(z*side+y)*side+x];if(a<0)continue;
            if(x+1<side)add(edges,nodes,a,indices[(z*side+y)*side+x+1]);
            if(y+1<side)add(edges,nodes,a,indices[(z*side+y+1)*side+x]);
            if(z+1<side)add(edges,nodes,a,indices[((z+1)*side+y)*side+x]);
        }
        var world=EnvironmentThermalState.initialized("mmm:benchmark",thermal.fingerprint(),nodes,edges);
        var binding=new EnvironmentBinding(world.dimensionId(),refs);var inventory=new FiniteThermalDeviceInventory(thermal.fingerprint(),walls);
        System.out.printf("mechanical fixture grid=%d cells=%d walls=%d ports=%d nodes=%d edges=%d warm=%d pairs=%d%n",n,cells,walls.size(),ports.size(),nodes.size(),edges.size(),warm,samples);
        try(var backend=new VulkanFluidSolverBackend(device)) {
            System.out.println("Device: "+backend.executionDescription());
            backend.configureEnvironment(world,defs);
            try(var coupledImport=backend.prepareState(null,initial,inventory,ports,binding)) {
                FluidStateAccess coupled=coupledImport.commit();try {
                    var ordinary=new ArrayList<Long>();var events=new ArrayList<Long>();var baseTimes=new ArrayList<Long>();var coupledTimes=new ArrayList<Long>();var eventBase=new ArrayList<Long>();var eventCoupled=new ArrayList<Long>();long gpu=0,record=0,wait=0,submit=0;int regular=0;
                    for(int i=0;i<warm+samples;i++) {
                        boolean event=i%10==9;if(event)backend.queueEnvironmentAdvance(world.dimensionId(),.5);
                        Sample b,c;
                        b=baseline(backend,coupled,i);c=step(backend,coupled,i);coupled=c.next();
                        if(i>=warm){long delta=c.time()-b.time();(event?events:ordinary).add(delta);(event?eventBase:baseTimes).add(b.time());(event?eventCoupled:coupledTimes).add(c.time());
                            if(!event){gpu+=c.gpu()-b.gpu();record+=c.record()-b.record();wait+=c.waiting()-b.waiting();submit+=c.submits()-b.submits();regular++;}}
                    }
                    var state=backend.captureEnvironment(world.dimensionId());
                    if(!state.exactEnergy().equals(state.audit().expectedEnergy()))throw new AssertionError("World energy audit differs");
                    if(!((ResidentFluidState)coupled).observation().thermalAuditClosed())throw new AssertionError("Region energy audit differs");
                    report("ordinary_delta",ordinary);report("ordinary_baseline",baseTimes);report("ordinary_coupled",coupledTimes);report("diffusion_delta",events);report("diffusion_baseline",eventBase);report("diffusion_coupled",eventCoupled);
                    System.out.printf("ordinary component mean delta: gpu_ms=%.4f recording_ms=%.4f waiting_ms=%.4f submissions=%.4f%n",gpu*1e-6/regular,record*1e-6/regular,wait*1e-6/regular,submit/(double)regular);
                }finally{coupled.close();}
            }
        }
    }
    private static void add(List<EnvironmentThermalState.Contact> edges,List<EnvironmentThermalState.Node> nodes,int a,int b) {
        if(b>=0)edges.add(new EnvironmentThermalState.Contact(a,b,nodes.get(a).key().kind()==EnvironmentThermalState.Kind.SOLID?(nodes.get(b).key().kind()==EnvironmentThermalState.Kind.SOLID?50:5.0/1.05):(nodes.get(b).key().kind()==EnvironmentThermalState.Kind.SOLID?5.0/1.05:1),0));
    }
    /** Measure the same committed source without environment, then discard its candidate. */
    private static Sample baseline(VulkanFluidSolverBackend backend,FluidStateAccess state,int tick) {
        try {
            var field=state.getClass().getDeclaredField("binding");field.setAccessible(true);Object binding=field.get(state);
            var before=backend.regionMetrics(state);long start=System.nanoTime();
            try {field.set(state,null);try(var candidate=backend.evaluate(new FluidStepRequest(tick,state,List.of(),true,false,false))) { }}
            finally {field.set(state,binding);}
            long elapsed=System.nanoTime()-start;var after=backend.regionMetrics(state);
            return new Sample(state,elapsed,after.gpuNanos()-before.gpuNanos(),after.recordingNanos()-before.recordingNanos(),after.waitingNanos()-before.waitingNanos(),after.submissions()-before.submissions());
        } catch(ReflectiveOperationException failure){throw new IllegalStateException(failure);}
    }
    private static Sample step(VulkanFluidSolverBackend backend,FluidStateAccess state,int tick) {
        var before=backend.regionMetrics(state);long start=System.nanoTime();FluidStateAccess next;
        try(var candidate=backend.evaluate(new FluidStepRequest(tick,state,List.of(),true,false,false));var prepared=candidate.claimPreparedDeviceState()) {next=prepared.commit();}
        long elapsed=System.nanoTime()-start;var after=backend.regionMetrics(next);state.close();
        return new Sample(next,elapsed,after.gpuNanos()-before.gpuNanos(),after.recordingNanos()-before.recordingNanos(),after.waitingNanos()-before.waitingNanos(),after.submissions()-before.submissions());
    }
    private static void report(String label,List<Long> values) {
        long[] sorted=values.stream().mapToLong(Long::longValue).sorted().toArray();double mean=Arrays.stream(sorted).average().orElse(Double.NaN);
        System.out.printf("%s samples=%d mean_ms=%.4f p95_ms=%.4f%n",label,sorted.length,mean*1e-6,sorted.length==0?Double.NaN:sorted[(int)Math.ceil(.95*sorted.length)-1]*1e-6);
    }
}
