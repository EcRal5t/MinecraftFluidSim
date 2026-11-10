package mmm.gpu;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import mmm.fluid.*;
import mmm.fluid.air.GasInventory;
import mmm.thermal.*;
import mmm.units.SignedDyadic;

/** Production resident fluid, projected world wall and finite environmental air. */
public final class GpuEnvironmentProductionValidation {
    private GpuEnvironmentProductionValidation() {}
    public static void main(String[] args) {
        int device=Integer.parseInt(args[0]),warm=Integer.parseInt(args[1]),samples=Integer.parseInt(args[2]),wallCount=Integer.parseInt(args[3]);
        var definitions=TestFluidDefinitions.load();var thermal=definitions.thermal();
        var fluids=new FluidMaterialRegistry(List.of(definitions.material().entry("mmm:water").material()));
        var domain=new HostFluidState(8,8,8,new FluidSolverConfig(.5f,9.81f,.05f,200,1e-5f,.002f),definitions);
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(definitions.material());domain.sealDomainBoundary();
        domain.setSolid(0,1,1,true);float[] volumes=new float[512];volumes[(1*8+1)*8+1]=.8f;domain.setVolumes(volumes);
        domain.bindGasRegistry(definitions.gas());domain.bindThermal(thermal,fluids,350);
        var initial=domain.captureAuthoritativeState();var definition=thermal.finiteDevice("mmm:ordinary_metal_wall");
        double wallU=new ThermalStateFunction(thermal).internalEnergyAtTemperature(thermal.materialHandle(definition.thermalMaterialId()),definition.bodyMassKilograms(),Map.of(),293.15);
        var body=new FiniteThermalBodyState(0,thermal.fingerprint(),definition.thermalMaterialId(),definition.bodyMassUnits(),wallU);
        var wall=new FiniteThermalDeviceState("mmm:environment_wall","wall","mmm:ordinary_metal_wall",body);
        var port=new FluidStateAccess.DevicePort(wall.providerId(),wall.objectId(),new FluidDomainContracts.ThermalPortRequest(1,1,1,0,.5,50,.125));
        var key=new EnvironmentThermalState.Key(0,1,1,EnvironmentThermalState.Kind.SOLID,0);
        var airKey=new EnvironmentThermalState.Key(-2,1,1,EnvironmentThermalState.Kind.AIR,0);
        var gas=definitions.gas().createAmbient(1_000_000_000L);var gasModel=new GasThermalModel(thermal,definitions.gas());
        double airU=gasModel.internalEnergyAtTemperature(gas,293.15);
        int side=40;int[] indices=new int[side*side*side];Arrays.fill(indices,-1);
        var nodes=new ArrayList<EnvironmentThermalState.Node>();var contacts=new ArrayList<EnvironmentThermalState.Contact>();
        int wallIndex=-1,airIndex=-1;
        for(int z=0;z<side;z++)for(int y=0;y<side;y++)for(int x=0;x<side;x++){
            if(x>=4&&x<36&&y>=4&&y<36&&z>=4&&z<36)continue;
            int index=nodes.size();indices[(z*side+y)*side+x]=index;
            if(x==3&&y==5&&z==5){wallIndex=index;nodes.add(new EnvironmentThermalState.Node(key,definition.thermalMaterialId(),definition.bodyMassUnits(),1,GasInventory.EMPTY,wallU,List.of()));}
            else {var airNodeKey=new EnvironmentThermalState.Key(x-4,y-4,z-4,EnvironmentThermalState.Kind.AIR,0);
                if(airNodeKey.equals(airKey))airIndex=index;
                nodes.add(new EnvironmentThermalState.Node(airNodeKey,"mmm:air",gas.totalMassUnits(),1,gas,airU,List.of()));}
        }
        for(int z=0;z<side;z++)for(int y=0;y<side;y++)for(int x=0;x<side;x++){
            int a=indices[(z*side+y)*side+x];if(a<0)continue;
            if(x+1<side)addContact(contacts,a,indices[(z*side+y)*side+x+1],wallIndex);
            if(y+1<side)addContact(contacts,a,indices[(z*side+y+1)*side+x],wallIndex);
            if(z+1<side)addContact(contacts,a,indices[((z+1)*side+y)*side+x],wallIndex);
        }
        var bodies=new ArrayList<FiniteThermalDeviceState>();bodies.add(wall);var references=new ArrayList<EnvironmentBinding.DeviceReference>();
        references.add(new EnvironmentBinding.DeviceReference(wall.providerId(),wall.objectId(),key));
        for(int i=0;i<nodes.size()&&bodies.size()<wallCount;i++){
            var node=nodes.get(i);if(i==airIndex||node.key().kind()!=EnvironmentThermalState.Kind.AIR)continue;
            var old=node.key();var nodeKey=new EnvironmentThermalState.Key(old.x(),old.y(),old.z(),EnvironmentThermalState.Kind.SOLID,0);
            if(nodeKey.equals(key))continue;
            nodes.set(i,new EnvironmentThermalState.Node(nodeKey,definition.thermalMaterialId(),definition.bodyMassUnits(),1,GasInventory.EMPTY,wallU,List.of()));
            String id="wall-"+bodies.size();bodies.add(new FiniteThermalDeviceState(wall.providerId(),id,wall.deviceTypeId(),body));
            references.add(new EnvironmentBinding.DeviceReference(wall.providerId(),id,nodeKey));
        }
        if(bodies.size()!=wallCount)throw new IllegalArgumentException("Wall count exceeds fixture capacity");
        var devices=new FiniteThermalDeviceInventory(thermal.fingerprint(),bodies);
        int carriedIndex=nodes.size();
        var carried=new EnvironmentThermalState.Node(new EnvironmentThermalState.Key(70,71,72,
                EnvironmentThermalState.Kind.CARRIED,0),definition.thermalMaterialId(),definition.bodyMassUnits(),1,
                GasInventory.EMPTY,wallU+4321.125,List.of(),"testowner");
        nodes.add(carried);
        var world=EnvironmentThermalState.initialized("mmm:test",thermal.fingerprint(),nodes,contacts);
        var binding=new EnvironmentBinding(world.dimensionId(),references);
        try(var backend=new VulkanFluidSolverBackend(device)) {
            backend.configureEnvironment(world,definitions);
            requireCarried(backend.captureEnvironment(world.dimensionId()),carriedIndex,carried);
            try(var baselineImport=backend.prepareState(null,initial,devices,List.of(port),null);
                var imported=backend.prepareState(null,initial,devices,List.of(port),binding)) {
                FluidStateAccess current=imported.commit(),control=baselineImport.commit();
                try {
                    try(var ignored=backend.evaluateThermal(new FluidThermalStepRequest(0,current,false))) { }
                    if(backend.captureEnvironment(world.dimensionId()).generation()!=0)throw new AssertionError("Rejected region published environment");
                    requireCarried(backend.captureEnvironment(world.dimensionId()),carriedIndex,carried);
                    long[] times=new long[samples],baseline=new long[samples];var environmentTimes=new ArrayList<Long>();
                    long[] coupledGpu=new long[samples],baselineGpu=new long[samples],coupledRecord=new long[samples],baselineRecord=new long[samples];
                    long[] coupledWait=new long[samples],baselineWait=new long[samples],coupledSubmissions=new long[samples],baselineSubmissions=new long[samples];
                    for(int step=0;step<warm+samples;step++){
                        Measurement c,e;
                        if((step&1)==0){c=measureStep(backend,control,step);control=c.next();e=measureStep(backend,current,step);current=e.next();}
                        else{e=measureStep(backend,current,step);current=e.next();c=measureStep(backend,control,step);control=c.next();}
                        if(step>=warm){int i=step-warm;times[i]=e.wall();baseline[i]=c.wall();coupledGpu[i]=e.gpu();baselineGpu[i]=c.gpu();
                            coupledRecord[i]=e.record();baselineRecord[i]=c.record();coupledWait[i]=e.waiting();baselineWait[i]=c.waiting();
                            coupledSubmissions[i]=e.submissions();baselineSubmissions[i]=c.submissions();}
                        if(step%10==9){long start=System.nanoTime();backend.advanceEnvironment(world.dimensionId(),.5);if(step>=warm)environmentTimes.add(System.nanoTime()-start);}
                    }
                    var finished=backend.captureEnvironment(world.dimensionId());
                    requireCarried(finished,carriedIndex,carried);
                    if(!(finished.nodes().get(wallIndex).internalEnergyJ()>wallU && finished.nodes().get(airIndex).internalEnergyJ()>airU))
                        throw new AssertionError("Water did not warm the wall and finite air: wall="+(finished.nodes().get(wallIndex).internalEnergyJ()-wallU)+" air="+(finished.nodes().get(airIndex).internalEnergyJ()-airU));
                    if(!finished.exactEnergy().equals(finished.audit().expectedEnergy()))
                        throw new AssertionError("World energy audit does not close: delta="+finished.exactEnergy().subtract(finished.audit().expectedEnergy())+" audit="+finished.audit());
                    if(!((ResidentFluidState)current).observation().thermalAuditClosed())throw new AssertionError("Region thermal audit does not close");
                    if(((ResidentFluidState)current).inventorySummary().deviceInternalEnergyJoules()!=0.0)
                        throw new AssertionError("World-owned wall was counted as a region-owned device");
                    var exported=current.finiteThermalDevices();
                    if(exported.devices().getFirst().body().internalEnergyJ()!=finished.nodes().get(wallIndex).internalEnergyJ())
                        throw new AssertionError("World wall projection differs from committed environment");
                    backend.queueEnvironmentAdvance(world.dimensionId(),.5);
                    try(var ignored=backend.evaluateThermal(new FluidThermalStepRequest(warm+samples,current,false))) { }
                    if(backend.captureEnvironment(world.dimensionId()).generation()!=finished.generation())
                        throw new AssertionError("Rejected region published queued environment diffusion");
                    requireCarried(backend.captureEnvironment(world.dimensionId()),carriedIndex,carried);
                    current=advance(backend,current,warm+samples);
                    var queued=backend.captureEnvironment(world.dimensionId());
                    requireCarried(queued,carriedIndex,carried);
                    if(queued.generation()!=finished.generation()+1||!queued.exactEnergy().equals(queued.audit().expectedEnergy()))
                        throw new AssertionError("Queued diffusion was not committed with region");
                    backend.flushEnvironmentAdvance(world.dimensionId());
                    if(backend.captureEnvironment(world.dimensionId()).generation()!=queued.generation())
                        throw new AssertionError("Committed diffusion remained queued");
                    var secondDomain=HostFluidState.restoreAuthoritativeState(initial);secondDomain.setThermalTemperatureInCell(1,1,1,280);
                    try(var secondImport=backend.prepareState(null,secondDomain.captureAuthoritativeState(),devices,List.of(port),binding)){
                        FluidStateAccess second=secondImport.commit();try{
                            try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(0,second,false));var prepared=candidate.claimPreparedDeviceState()){
                                var next=prepared.commit();second.close();second=next;
                            }
                            var afterSecond=backend.captureEnvironment(world.dimensionId());
                            if(!afterSecond.exactEnergy().equals(afterSecond.audit().expectedEnergy()))throw new AssertionError("Shared environment audit differs after second region");
                            if(second.finiteThermalDevices().devices().getFirst().body().internalEnergyJ()!=afterSecond.nodes().get(wallIndex).internalEnergyJ())
                                throw new AssertionError("Second region lost shared wall state");
                            try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(warm+samples,current,false));var prepared=candidate.claimPreparedDeviceState()){
                                var next=prepared.commit();current.close();current=next;
                            }
                            var shared=backend.captureEnvironment(world.dimensionId());
                            if(!shared.exactEnergy().equals(shared.audit().expectedEnergy()))throw new AssertionError("Shared environment audit differs after first region resumes");
                            if(current.finiteThermalDevices().devices().getFirst().body().internalEnergyJ()!=shared.nodes().get(wallIndex).internalEnergyJ())
                                throw new AssertionError("First region lost shared wall state");
                        }finally{second.close();}
                    }
                    var beforeEdit=backend.captureEnvironment(world.dimensionId());var beforeDevices=current.finiteThermalDevices();
                    var oldWall=beforeDevices.devices().getFirst();double newEnergy=oldWall.body().internalEnergyJ()+100.0;
                    var changedWall=new FiniteThermalDeviceState(oldWall.providerId(),oldWall.objectId(),oldWall.deviceTypeId(),
                            new FiniteThermalBodyState(oldWall.body().generation()+1,oldWall.body().thermalRegistryFingerprint(),oldWall.body().thermalMaterialId(),oldWall.body().massUnits(),newEnergy),oldWall.surfaces());
                    var delta=SignedDyadic.of(newEnergy).subtract(SignedDyadic.of(oldWall.body().internalEnergyJ()));var d=beforeDevices.audit();
                    var changedDevices=new ArrayList<>(beforeDevices.devices());changedDevices.set(0,changedWall);
                    var editedDevices=new FiniteThermalDeviceInventory(thermal.fingerprint(),changedDevices,
                            new FiniteThermalDeviceAudit(d.initialInventory(),d.externalSource().add(delta),d.externalSink(),d.solverExchange(),d.roundoffResidual()));
                    var changedNodes=new ArrayList<>(beforeEdit.nodes());changedNodes.set(wallIndex,changedNodes.get(wallIndex).withEnergy(newEnergy));
                    var a=beforeEdit.audit();var editedWorld=new EnvironmentThermalState(beforeEdit.dimensionId(),beforeEdit.generation(),beforeEdit.registryFingerprint(),changedNodes,beforeEdit.contacts(),
                            new EnvironmentThermalState.Audit(a.initial(),a.source().add(delta),a.sink(),a.regionExchange(),a.roundoff()));
                    try(var envEdit=backend.prepareEnvironment(editedWorld,definitions);var regionEdit=backend.prepareState(current,current.exportState(),editedDevices,List.of(port),binding)){
                        regionEdit.attachEnvironment(envEdit);
                    }
                    if(backend.captureEnvironment(world.dimensionId()).generation()!=beforeEdit.generation())throw new AssertionError("Rejected world edit changed its generation");
                    requireCarried(backend.captureEnvironment(world.dimensionId()),carriedIndex,carried);
                    try(var envEdit=backend.prepareEnvironment(editedWorld,definitions);var regionEdit=backend.prepareState(current,current.exportState(),editedDevices,List.of(port),binding)){
                        regionEdit.attachEnvironment(envEdit);var next=regionEdit.commit();current.close();current=next;
                    }
                    var edited=backend.captureEnvironment(world.dimensionId());
                    requireCarried(edited,carriedIndex,carried);
                    if(edited.generation()!=beforeEdit.generation()+1 || edited.nodes().get(wallIndex).internalEnergyJ()!=newEnergy || !edited.exactEnergy().equals(edited.audit().expectedEnergy()))
                        throw new AssertionError("Joint world edit did not commit the same inventory");
                    var insulated=new FluidStateAccess.DevicePort(wall.providerId(),wall.objectId(),new FluidDomainContracts.ThermalPortRequest(1,1,1,0,.5,0,.125));
                    try(var insulation=backend.prepareState(current,current.exportState(),current.finiteThermalDevices(),List.of(insulated),binding)){
                        var next=insulation.commit();current.close();current=next;
                    }
                    current=advance(backend,current,warm+samples+2);
                    var noHeat=backend.captureEnvironment(world.dimensionId());
                    if(noHeat.nodes().get(wallIndex).internalEnergyJ()!=newEnergy || !noHeat.exactEnergy().equals(noHeat.audit().expectedEnergy()))
                        throw new AssertionError("Zero-conductivity wall exchanged heat");
                    long sum=0,baseSum=0;long[] differences=new long[samples];for(int i=0;i<samples;i++){sum+=times[i];baseSum+=baseline[i];differences[i]=times[i]-baseline[i];}Arrays.sort(times);Arrays.sort(baseline);Arrays.sort(differences);
                    long[] events=environmentTimes.stream().mapToLong(Long::longValue).sorted().toArray();long eventSum=0;for(long value:events)eventSum+=value;
                    System.out.printf("production water_wall_air nodes=%d contacts=%d projected_walls=%d active_ports=1 fluid_grid=8 accepted=%d wall_gain_J=%.9g air_gain_J=%.9g baseline_mean_ms=%.4f baseline_p95_ms=%.4f coupled_mean_ms=%.4f coupled_p95_ms=%.4f paired_delta_mean_ms=%.4f paired_delta_p95_ms=%.4f environment_events=%d environment_mean_ms=%.4f environment_p95_ms=%.4f%n",
                            nodes.size(),contacts.size(),wallCount,warm+samples,finished.nodes().get(wallIndex).internalEnergyJ()-wallU,finished.nodes().get(airIndex).internalEnergyJ()-airU,
                            baseSum*1e-6/samples,baseline[(int)Math.ceil(.95*samples)-1]*1e-6,sum*1e-6/samples,times[(int)Math.ceil(.95*samples)-1]*1e-6,(sum-baseSum)*1e-6/samples,differences[(int)Math.ceil(.95*samples)-1]*1e-6,
                            events.length,events.length==0?Double.NaN:eventSum*1e-6/events.length,events.length==0?Double.NaN:events[(int)Math.ceil(.95*events.length)-1]*1e-6);
                    System.out.printf("paired_delta gpu_mean_ms=%.4f recording_mean_ms=%.4f waiting_mean_ms=%.4f submissions_mean=%.4f%n",
                            deltaMean(coupledGpu,baselineGpu)*1e-6,deltaMean(coupledRecord,baselineRecord)*1e-6,
                            deltaMean(coupledWait,baselineWait)*1e-6,deltaMean(coupledSubmissions,baselineSubmissions));
                } finally {current.close();control.close();}
            }
        }
        verifyOpenGas(device,definitions);
        verifySharedSurface(device,definitions);
    }
    private static void requireCarried(EnvironmentThermalState actual,int index,EnvironmentThermalState.Node expected) {
        var carried=actual.nodes().get(index);
        if(!carried.equals(expected) || !carried.owner().equals("testowner")
                || Double.doubleToRawLongBits(carried.internalEnergyJ())!=Double.doubleToRawLongBits(expected.internalEnergyJ()))
            throw new AssertionError("Carried environment inventory changed during candidate or GPU capture");
        if(actual.contacts().stream().anyMatch(contact->contact.a()==index || contact.b()==index))
            throw new AssertionError("Carried environment inventory gained a thermal contact");
    }
    private static void addContact(List<EnvironmentThermalState.Contact> contacts,int a,int b,int wall){
        if(b>=0)contacts.add(new EnvironmentThermalState.Contact(a,b,a==wall||b==wall?5:.026,0));
    }
    private static FluidStateAccess advance(VulkanFluidSolverBackend backend,FluidStateAccess current,int step){
        try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(step,current,false));var prepared=candidate.claimPreparedDeviceState()){
            var next=prepared.commit();current.close();return next;
        }
    }
    private record Measurement(FluidStateAccess next,long wall,long gpu,long record,long waiting,long submissions) {}
    private static Measurement measureStep(VulkanFluidSolverBackend backend,FluidStateAccess source,int step){
        var before=backend.regionMetrics(source);long start=System.nanoTime();var next=advance(backend,source,step);long wall=System.nanoTime()-start;
        var after=backend.regionMetrics(next);return new Measurement(next,wall,after.gpuNanos()-before.gpuNanos(),
                after.recordingNanos()-before.recordingNanos(),after.waitingNanos()-before.waitingNanos(),after.submissions()-before.submissions());
    }
    private static double deltaMean(long[] values,long[] baseline){long sum=0;for(int i=0;i<values.length;i++)sum+=values[i]-baseline[i];return sum/(double)values.length;}
    private static void verifyOpenGas(int device,FluidDefinitions definitions){
        var thermal=definitions.thermal();var fluids=new FluidMaterialRegistry(List.of(definitions.material().entry("mmm:water").material()));
        var domain=new HostFluidState(3,3,3,new FluidSolverConfig(.5f,0,.05f,200,1e-5f,.002f),definitions);
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(definitions.material());
        for(int z=0;z<3;z++)for(int y=0;y<3;y++)for(int x=0;x<3;x++)if(x!=0||y!=1||z!=1)domain.setSolid(x,y,z,true);
        domain.bindGasRegistry(definitions.gas());domain.bindThermal(thermal,fluids,350);
        var gas=definitions.gas().createAmbient(1_000_000_000L);double airU=new GasThermalModel(thermal,definitions.gas()).internalEnergyAtTemperature(gas,293.15);
        var key=new EnvironmentThermalState.Key(-1,1,1,EnvironmentThermalState.Kind.AIR,0);
        var world=EnvironmentThermalState.initialized("mmm:gas",thermal.fingerprint(),List.of(
                new EnvironmentThermalState.Node(key,"mmm:air",gas.totalMassUnits(),1,gas,airU,List.of())),List.of());
        var binding=new EnvironmentBinding(world.dimensionId(),List.of(),List.of(new EnvironmentBinding.GasReference(12,0,key,1)));
        var body=new mmm.world.MovingBodyFluidCoupling.Request(java.util.UUID.fromString("a7184c41-7a9c-4c56-8b2a-e7d7b474ca23"),
                new mmm.world.MovingBodyFluidCoupling.Bounds(0,.5,.5,.5,1,1),0,0,0);
        try(var backend=new VulkanFluidSolverBackend(device)){
            backend.configureEnvironment(world,definitions);
            try(var imported=backend.prepareState(null,domain.captureAuthoritativeState(),null,List.of(),binding)){
                FluidStateAccess current=imported.commit();try{
                    try(var candidate=backend.evaluate(new FluidStepRequest(0,current,List.of(body),false,false,false));var prepared=candidate.claimPreparedDeviceState()){
                        var next=prepared.commit();current.close();current=next;
                    }
                    var after=backend.captureEnvironment(world.dimensionId());
                    if(!(after.nodes().getFirst().internalEnergyJ()>airU))throw new AssertionError("Outgoing hot gas did not warm finite air");
                    if(!after.exactEnergy().equals(after.audit().expectedEnergy()))throw new AssertionError("Open gas world energy audit differs");
                    if(!((ResidentFluidState)current).observation().thermalAuditClosed())throw new AssertionError("Open gas region energy audit differs");
                    double afterOut=after.nodes().getFirst().internalEnergyJ();
                    double warmAir=new GasThermalModel(thermal,definitions.gas()).internalEnergyAtTemperature(gas,450);
                    var a=after.audit();var warmed=new EnvironmentThermalState(after.dimensionId(),after.generation(),after.registryFingerprint(),
                            List.of(after.nodes().getFirst().withEnergy(warmAir)),after.contacts(),new EnvironmentThermalState.Audit(a.initial(),
                            a.source().add(SignedDyadic.of(warmAir).subtract(SignedDyadic.of(afterOut))),a.sink(),a.regionExchange(),a.roundoff()));
                    backend.configureEnvironment(warmed,definitions);
                    for(int step=1;step<=6;step++)try(var candidate=backend.evaluate(new FluidStepRequest(step,current,List.of(),false,false,false));
                            var prepared=candidate.claimPreparedDeviceState()){
                        var next=prepared.commit();current.close();current=next;
                    }
                    var afterIn=backend.captureEnvironment(world.dimensionId());
                    var source=current.exportState().spatialGas().externalSource();
                    if(source.entries().isEmpty() || !(afterIn.nodes().getFirst().internalEnergyJ()<warmAir))
                        throw new AssertionError("Incoming gas did not receive heat from finite air: source="+source.entries()+" airDelta="+(afterIn.nodes().getFirst().internalEnergyJ()-warmAir));
                    if(!afterIn.exactEnergy().equals(afterIn.audit().expectedEnergy())||!((ResidentFluidState)current).observation().thermalAuditClosed())
                        throw new AssertionError("Incoming gas energy audit differs");
                    System.out.printf("open_gas air_gain_J=%.9g warm_air_loss_J=%.9g%n",afterOut-airU,warmAir-afterIn.nodes().getFirst().internalEnergyJ());
                }finally{current.close();}
            }
        }
    }
    private static void verifySharedSurface(int device,FluidDefinitions definitions){
        var thermal=definitions.thermal();var fluids=new FluidMaterialRegistry(List.of(definitions.material().entry("mmm:water").material()));
        var domain=new HostFluidState(3,3,3,new FluidSolverConfig(.5f,0,.05f,200,1e-5f,.002f),definitions);
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(definitions.material());domain.sealDomainBoundary();
        domain.setSolid(0,1,1,true);domain.bindChemistry(definitions.chemistry());domain.bindGasRegistry(definitions.gas());domain.bindThermal(thermal,fluids,300);
        var initial=domain.captureAuthoritativeState();var def=thermal.finiteDevice("mmm:ordinary_metal_wall");
        var surface=new FixedSurfaceState(FiniteThermalDeviceDefinition.LocalFace.EAST,"mmm:co2_adsorbent",mmm.units.Mass.fromKilograms(1),Map.of(),Map.of());
        double base=new ThermalStateFunction(thermal).internalEnergyAtTemperature(thermal.materialHandle(def.thermalMaterialId()),def.bodyMassKilograms(),Map.of(),300);
        var temporary=new FiniteThermalDeviceState("mmm:surface","wall",def.id(),new FiniteThermalBodyState(0,thermal.fingerprint(),def.thermalMaterialId(),def.bodyMassUnits(),base),List.of(surface));
        double total=SurfaceThermalState.energyAtTemperature(thermal,temporary,300);
        var wall=new FiniteThermalDeviceState(temporary.providerId(),temporary.objectId(),temporary.deviceTypeId(),
                new FiniteThermalBodyState(0,thermal.fingerprint(),def.thermalMaterialId(),def.bodyMassUnits(),total),List.of(surface));
        var devices=new FiniteThermalDeviceInventory(thermal.fingerprint(),List.of(wall));
        var key=new EnvironmentThermalState.Key(0,1,1,EnvironmentThermalState.Kind.SOLID,0);
        var world=EnvironmentThermalState.initialized("mmm:surface",thermal.fingerprint(),List.of(
                new EnvironmentThermalState.Node(key,def.thermalMaterialId(),def.bodyMassUnits(),1,GasInventory.EMPTY,total,List.of(surface))),List.of());
        var binding=new EnvironmentBinding(world.dimensionId(),List.of(new EnvironmentBinding.DeviceReference(wall.providerId(),wall.objectId(),key)));
        var port=new FluidStateAccess.DevicePort(wall.providerId(),wall.objectId(),new FluidDomainContracts.ThermalPortRequest(1,1,1,0,.5,50,.125));
        try(var backend=new VulkanFluidSolverBackend(device)){
            backend.configureEnvironment(world,definitions);
            try(var firstImport=backend.prepareState(null,initial,devices,List.of(port),binding);
                var secondImport=backend.prepareState(null,initial,devices,List.of(port),binding)){
                FluidStateAccess first=firstImport.commit(),second=secondImport.commit();try{
                    for(int step=0;step<20;step++){
                        FluidStateAccess current=(step&1)==0?first:second;
                        try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(step,current,true));var prepared=candidate.claimPreparedDeviceState()){
                            var next=prepared.commit();current.close();if((step&1)==0)first=next;else second=next;
                        }
                    }
                    var saved=backend.captureEnvironment(world.dimensionId());var captured=saved.nodes().getFirst().surfaces().getFirst();
                    if(captured.speciesQuanta().getOrDefault("mmm:co2",0L)<=0)throw new AssertionError("Shared surface did not adsorb CO2");
                    if(!captured.equals(second.finiteThermalDevices().devices().getFirst().surfaces().getFirst()))
                        throw new AssertionError("Shared surface inventory differs after second region");
                    if(!saved.exactEnergy().equals(saved.audit().expectedEnergy()))throw new AssertionError("Shared surface world thermal audit differs");
                    System.out.printf("shared_surface co2_quanta=%d%n",captured.speciesQuanta().get("mmm:co2"));
                }finally{first.close();second.close();}
            }
        }
    }
}
