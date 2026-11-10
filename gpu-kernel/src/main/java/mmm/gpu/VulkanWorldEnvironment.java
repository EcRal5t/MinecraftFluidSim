package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import mmm.fluid.FluidDefinitions;
import mmm.thermal.*;
import mmm.units.Mass;
import mmm.units.SignedDyadic;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.*;

/** One dimension's resident world thermal graph. */
final class VulkanWorldEnvironment implements AutoCloseable {
    private final EnvironmentThermalState initial;
    private final VulkanEnvironmentThermal kernel;
    private final VulkanEnergyLedger ledger;
    private final VulkanEnvironmentThermal.Layout layout;
    private final VulkanEnvironmentThermal.Accounting accounting;
    private final long alignment;
    private final VulkanRegionStorage storage;
    private record SurfaceKey(int node, FiniteThermalDeviceDefinition.LocalFace face, String species) {}
    private final java.util.Map<SurfaceKey,Integer> surfaceIndices;
    private final java.util.Map<EnvironmentThermalState.Key,Integer> nodeIndices;
    private final java.util.Set<GpuBuffer> initializedBuffers=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final java.util.Map<mmm.fluid.EnvironmentBinding,int[]> bindingNodes = new java.util.IdentityHashMap<>();
    private final java.util.Map<VulkanThermalDeviceLayout,java.util.Map<mmm.fluid.EnvironmentBinding,Mapping>> mappings = new java.util.WeakHashMap<>();
    private record Mapping(int[] rows, VulkanEnergyLedger.Layout importLedger, VulkanEnergyLedger.Layout exportLedger,
                           VulkanEnvironmentProjection projection) {}
    private final FluidDefinitions definitions;
    private final VulkanDeviceContext context;
    private final java.util.Map<mmm.fluid.EnvironmentBinding,VulkanGasEnvironment> gasMappings=new java.util.IdentityHashMap<>();
    private final java.util.List<VulkanEnvironmentProjection> projections=new java.util.ArrayList<>();
    private double queuedDuration;

    VulkanWorldEnvironment(VulkanDeviceContext context, EnvironmentThermalState state, FluidDefinitions definitions) {
        if (!state.registryFingerprint().equals(definitions.thermal().fingerprint()))
            throw new IllegalArgumentException("Environment thermal definition fingerprint differs");
        initial=state;this.definitions=definitions;this.context=context;
        var indexByKey=new java.util.HashMap<EnvironmentThermalState.Key,Integer>();
        for(int i=0;i<state.nodes().size();i++)indexByKey.put(state.nodes().get(i).key(),i);
        nodeIndices=java.util.Map.copyOf(indexByKey);
        alignment=Math.max(8,context.storageOffsetAlignment());
        if (state.nodes().isEmpty()) { kernel=null; ledger=null; layout=null; accounting=null; storage=null;surfaceIndices=java.util.Map.of(); return; }
        var nodes=new ArrayList<VulkanEnvironmentThermal.Node>();
        for (var node:state.nodes()) nodes.add(caloric(node,state,definitions));
        var edges=state.contacts().stream().map(c->new VulkanEnvironmentThermal.Edge(c.a(),c.b(),c.conductanceWPerK(),c.farTemperatureK())).toList();
        var indices=new java.util.HashMap<SurfaceKey,Integer>();
        for(int i=0;i<state.nodes().size();i++)for(var surface:state.nodes().get(i).surfaces())
            for(var adsorbate:definitions.thermal().surfaceMaterial(surface.materialId()).adsorbates())
                indices.put(new SurfaceKey(i,surface.face(),adsorbate.speciesId()),indices.size());
        surfaceIndices=java.util.Map.copyOf(indices);
        layout=VulkanEnvironmentThermal.layout(nodes,edges,indices.size(),0,alignment);
        accounting=VulkanEnvironmentThermal.accounting(layout,alignment);
        var bytes=ByteBuffer.allocate(Math.toIntExact(accounting.bytes())).order(ByteOrder.LITTLE_ENDIAN);
        layout.initialize(bytes,nodes,edges);accounting.initialize(bytes);
        for(int i=0;i<state.nodes().size();i++){
            double cp=0,reference=0;
            for(var surface:state.nodes().get(i).surfaces()){
                var material=definitions.thermal().surfaceMaterial(surface.materialId());
                cp+=Mass.kilograms(surface.loadingMassUnits())*material.specificHeatJPerKgK();
                for(var adsorbate:material.adsorbates()){
                    long amount=surface.speciesQuanta().getOrDefault(adsorbate.speciesId(),0L);
                    double residual=surface.residualQuanta().getOrDefault(adsorbate.speciesId(),0.0);
                    int index=indices.get(new SurfaceKey(i,surface.face(),adsorbate.speciesId()));
                    bytes.putLong(Math.toIntExact(layout.offset(VulkanEnvironmentThermal.Field.SURFACE_AMOUNTS)+8L*index),amount);
                    bytes.putDouble(Math.toIntExact(layout.offset(VulkanEnvironmentThermal.Field.SURFACE_RESIDUALS)+8L*index),residual);
                    cp+=amount/(double)mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL*adsorbate.heatCapacityJPerMolK();
                }
                reference+=SurfaceThermalState.surfaceEnergyAtTemperature(definitions.thermal(),surface,293.15);
            }
            bytes.putDouble(Math.toIntExact(layout.offset(VulkanEnvironmentThermal.Field.SURFACE_CAPACITY)+8L*i),cp);
            bytes.putDouble(Math.toIntExact(layout.offset(VulkanEnvironmentThermal.Field.SURFACE_INTERCEPT)+8L*i),reference-cp*293.15);
        }
        VulkanEnvironmentThermal createdKernel=null; VulkanEnergyLedger createdLedger=null; VulkanRegionStorage createdStorage=null;
        try {
            createdKernel=new VulkanEnvironmentThermal(context);createdLedger=new VulkanEnergyLedger(context);
            createdStorage=new VulkanRegionStorage(context,accounting.bytes(),1024*1024);createdStorage.initialize(bytes,state.generation());
        } catch(RuntimeException|Error failure) {
            if(createdStorage!=null)createdStorage.close();if(createdLedger!=null)createdLedger.close();if(createdKernel!=null)createdKernel.close();throw failure;
        }
        kernel=createdKernel;ledger=createdLedger;storage=createdStorage;
        try(var capture=storage.capture()){initializedBuffers.add(capture.buffer());}
    }
    private void copyPersistent(VkCommandBuffer command,GpuBuffer source,GpuBuffer target,MemoryStack stack){
        if(!initializedBuffers.contains(target)) VulkanRegionStorage.copy(command,source,0,target,0,accounting.bytes(),stack);
        else {
            var fields=List.of(VulkanEnvironmentThermal.Field.ENERGY,VulkanEnvironmentThermal.Field.SURFACE_INTERCEPT,
                    VulkanEnvironmentThermal.Field.SURFACE_CAPACITY,VulkanEnvironmentThermal.Field.SURFACE_AMOUNTS,VulkanEnvironmentThermal.Field.SURFACE_RESIDUALS);
            var copies=VkBufferCopy.calloc(fields.size()+4,stack);int count=0;
            for(var field:fields){var column=layout.column(field);if(column.bytes()>0)copies.get(count++).srcOffset(column.offset()).dstOffset(column.offset()).size(column.bytes());}
            for(var ledger:List.of(accounting.source(),accounting.sink(),accounting.roundoff(),accounting.region())){
                var column=ledger.result();copies.get(count++).srcOffset(column.offset()).dstOffset(column.offset()).size(column.bytes());
            }
            copies.limit(count);vkCmdCopyBuffer(command,source.handle(),target.handle(),copies);
        }
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
    }
    long generation(){if(storage==null)return initial.generation();try(var capture=storage.capture()){return capture.generation();}}
    Pending prepare(mmm.fluid.EnvironmentBinding binding,boolean projectionCurrent) {
        if (!initial.dimensionId().equals(binding.dimensionId()) || storage == null)
            throw new IllegalArgumentException("Region environment differs from its dimension");
        int[] nodes=bindingNodes.computeIfAbsent(binding,value->{
            int[] found=new int[value.devices().size()];var seen=new java.util.HashSet<Integer>();
            for(int i=0;i<found.length;i++) {
                var key=value.devices().get(i).node();Integer index=nodeIndices.get(key);
                if(index==null||key.kind()!=EnvironmentThermalState.Kind.SOLID||!seen.add(index))
                    throw new IllegalArgumentException("Invalid shared environment wall: "+key);
                found[i]=index;
            }return found;
        });
        var gas=binding.gas().isEmpty()?null:gasMappings.computeIfAbsent(binding,b->new VulkanGasEnvironment(context,b,nodeIndices));
        var capture=storage.capture();
        try{return new Pending(binding,nodes,capture,storage.prepare(capture),queuedDuration,gas,projectionCurrent);}catch(RuntimeException|Error failure){capture.close();throw failure;}
    }
    final class Pending implements AutoCloseable {
        final mmm.fluid.EnvironmentBinding binding;final int[] nodes;final VulkanRegionStorage.Capture capture;final VulkanRegionStorage.Candidate candidate;final double duration;final VulkanGasEnvironment gas;final boolean projectionCurrent;GpuBuffer target;
        int activeDepth,restoreDepth=-1;boolean saved;
        Pending(mmm.fluid.EnvironmentBinding binding,int[] nodes,VulkanRegionStorage.Capture capture,VulkanRegionStorage.Candidate candidate,double duration,VulkanGasEnvironment gas,boolean projectionCurrent){
            this.binding=binding;this.nodes=nodes;this.capture=capture;this.candidate=candidate;this.duration=duration;this.gas=gas;this.projectionCurrent=projectionCurrent;
        }
        private int[] rows(VulkanThermalDeviceLayout devices){
            if(nodes.length==0)return new int[0];
            if(devices==null)throw new IllegalArgumentException("Shared environment has no region devices");
            var identities=devices.definitions().identities();var lookup=new java.util.HashMap<String,Integer>();
            for(int i=0;i<identities.size();i++){var id=identities.get(i);lookup.put(id.providerId()+"\0"+id.objectId(),i);}
            int[] result=new int[nodes.length];
            for(int i=0;i<nodes.length;i++){
                var reference=binding.devices().get(i);Integer row=lookup.get(reference.providerId()+"\0"+reference.objectId());
                if(row==null)throw new IllegalArgumentException("Shared environment wall has no device row");result[i]=row;
            }return result;
        }
        private List<long[]> surfaceCopies(VulkanThermalDeviceLayout devices,int[] rows){
            var copies=new ArrayList<long[]>();
            for(int i=0;i<rows.length;i++){
                int node=nodes[i],row=rows[i];
                copies.add(new long[]{layout.offset(VulkanEnvironmentThermal.Field.SURFACE_INTERCEPT)+8L*node,
                        devices.column(VulkanThermalDeviceLayout.Field.SURFACE_ENERGY_INTERCEPT).offset()+8L*row});
                copies.add(new long[]{layout.offset(VulkanEnvironmentThermal.Field.SURFACE_CAPACITY)+8L*node,
                        devices.column(VulkanThermalDeviceLayout.Field.SURFACE_HEAT_CAPACITY).offset()+8L*row});
                int speciesIndex=0,matched=0;
                for(var slot:devices.definitions().surfaces()){
                    if(slot.device()==row){
                        var surface=initial.nodes().get(node).surfaces().stream().filter(s->s.face()==slot.face()).findFirst().orElseThrow(
                                ()->new IllegalArgumentException("Region surface is absent from world wall"));
                        if(!surface.materialId().equals(slot.materialId())||surface.loadingMassUnits()!=slot.loadingMassUnits())
                            throw new IllegalArgumentException("Region and world surface definitions differ");
                        matched++;
                        for(int j=0;j<slot.adsorbates().size();j++){
                            var adsorbate=slot.adsorbates().get(j);
                            Integer worldIndex=surfaceIndices.get(new SurfaceKey(node,slot.face(),adsorbate.speciesId()));
                            if(worldIndex==null)throw new IllegalArgumentException("Region and world surface species differ");
                            copies.add(new long[]{layout.offset(VulkanEnvironmentThermal.Field.SURFACE_AMOUNTS)+8L*worldIndex,
                                    devices.column(VulkanThermalDeviceLayout.Field.SURFACE_AMOUNTS).offset()+8L*(speciesIndex+j)});
                            copies.add(new long[]{layout.offset(VulkanEnvironmentThermal.Field.SURFACE_RESIDUALS)+8L*worldIndex,
                                    devices.column(VulkanThermalDeviceLayout.Field.SURFACE_RESIDUALS).offset()+8L*(speciesIndex+j)});
                        }
                    }
                    speciesIndex+=slot.adsorbates().size();
                }
                if(matched!=initial.nodes().get(node).surfaces().size())throw new IllegalArgumentException("World surface is absent from region wall");
            }
            return copies;
        }
        private Mapping mapping(VulkanThermalDeviceLayout devices){
            return mappings.computeIfAbsent(devices,ignored->new java.util.IdentityHashMap<>()).computeIfAbsent(binding,ignored->{
                int[] rows=rows(devices);
                int air=gas==null?0:gas.airNodes.length,total=rows.length+air;
                var old=new VulkanEnergyLedger.Stream(1,layout.word(VulkanEnvironmentThermal.Field.REGION_BEFORE),total,2,-1);
                var next=new VulkanEnergyLedger.Stream(1,layout.word(VulkanEnvironmentThermal.Field.REGION_AFTER),total,2,1);
                var active=VulkanEnergyLedger.layout(List.of(old,next),accounting.region().header().offset(),alignment);
                var importOld=new VulkanEnergyLedger.Stream(1,layout.word(VulkanEnvironmentThermal.Field.REGION_BEFORE),rows.length,2,-1);
                var importNew=new VulkanEnergyLedger.Stream(1,layout.word(VulkanEnvironmentThermal.Field.REGION_AFTER),rows.length,2,1);
                var imported=VulkanEnergyLedger.layout(List.of(importOld,importNew),accounting.region().header().offset(),alignment);
                if(active.endOffset()>accounting.region().endOffset())throw new IllegalStateException("Environment exchange workspace is too small");
                var surfaces=surfaceCopies(devices,rows);int n=rows.length;var walls=new ArrayList<long[]>(n);long[] airWords=new long[air];
                for(int i=0;i<n;i++){
                    long env=layout.offset(VulkanEnvironmentThermal.Field.ENERGY)+8L*nodes[i];
                    long device=devices.column(VulkanThermalDeviceLayout.Field.INTERNAL_ENERGY).offset()+8L*rows[i];
                    walls.add(new long[]{env,device});
                }
                for(int i=0;i<air;i++)airWords[i]=layout.offset(VulkanEnvironmentThermal.Field.ENERGY)+8L*gas.airNodes[i];
                var projection=new VulkanEnvironmentProjection(context,walls,surfaces,airWords);projections.add(projection);
                return new Mapping(rows,imported,active,projection);
            });
        }
        void recordImport(VkCommandBuffer command,GpuBuffer output,VulkanThermalDeviceLayout devices,GpuBuffer errors,MemoryStack stack){
            var mapping=mapping(devices);int[] rows=mapping.rows();
            target=candidate.beginExternal();copyPersistent(command,capture.buffer(),target,stack);
            if(rows.length==0||projectionCurrent)return;
            mapping.projection().record(command,capture.buffer(),output,target,layout,false);
            vkCmdUpdateBuffer(command,target.handle(),mapping.importLedger().header().offset(),mapping.importLedger().headerBytes());
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
            ledger.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(target),new FlipKernelBindings.BufferRange(errors));
            ledger.record(command,mapping.importLedger());
            ledger.recordAccumulate(command,mapping.importLedger(),Math.toIntExact(devices.audit(VulkanThermalDeviceLayout.Audit.INITIAL_INVENTORY).offset()/4));
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
            VulkanRegionStorage.copy(command,target,mapping.importLedger().result().offset(),output,
                    devices.audit(VulkanThermalDeviceLayout.Audit.INITIAL_INVENTORY).offset(),mapping.importLedger().result().bytes(),stack);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
        void beginLeaf(int depth){activeDepth=depth;saved=false;}
        void retryLeaf(int depth){if(gas!=null&&saved)restoreDepth=depth;saved=false;}
        void recordGas(VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,VulkanLocalGasTransport.ProductionLayout transport,MemoryStack stack){
            if(gas!=null){
                var environment=candidate.externalBuffer();
                if(restoreDepth>=0){gas.recordCheckpoint(command,environment,layout,restoreDepth,true,stack);restoreDepth=-1;}
                if(!saved){gas.recordCheckpoint(command,environment,layout,activeDepth,false,stack);saved=true;}
                gas.record(command,output,environment,errors,transport,layout);
            }
        }
        void recordExport(VkCommandBuffer command,GpuBuffer output,VulkanThermalDeviceLayout devices,GpuBuffer errors,MemoryStack stack){
            var mapping=mapping(devices);int[] rows=mapping.rows();var target=candidate.externalBuffer();
            mapping.projection().record(command,capture.buffer(),output,target,layout,true);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
            vkCmdUpdateBuffer(command,target.handle(),mapping.exportLedger().header().offset(),mapping.exportLedger().headerBytes());
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
            ledger.rebindRanges(new FlipKernelBindings.BufferRange(capture.buffer()),new FlipKernelBindings.BufferRange(target),new FlipKernelBindings.BufferRange(errors));
            ledger.record(command,mapping.exportLedger());
            ledger.recordAccumulate(command,mapping.exportLedger(),Math.toIntExact(accounting.region().result().offset()/4));
            if(mapping.exportLedger().result().offset()!=accounting.region().result().offset()){
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                VulkanRegionStorage.copy(command,target,mapping.exportLedger().result().offset(),target,accounting.region().result().offset(),accounting.region().result().bytes(),stack);
            }
            if(duration>0){
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                kernel.record(command,layout,target,errors,duration);
                kernel.recordAccounting(command,accounting,ledger,capture.buffer(),target,errors);
            }
        }
        void finish(){candidate.finishExternal();initializedBuffers.add(target);}
        boolean canCommit(){return candidate.canCommit();}
        VulkanWorldEnvironment owner(){return VulkanWorldEnvironment.this;}
        void commit(){candidate.commit();queuedDuration-=duration;}
        @Override public void close(){try{candidate.close();}finally{capture.close();}}
    }
    void queueAdvance(double duration){
        if(!(Double.isFinite(duration)&&duration>0))throw new IllegalArgumentException("Invalid environment duration");
        queuedDuration+=duration;if(!Double.isFinite(queuedDuration))throw new IllegalArgumentException("Environment duration exceeds representation");
    }
    void retainQueuedFrom(VulkanWorldEnvironment previous){queuedDuration=previous.queuedDuration;}
    void flushAdvance(){if(queuedDuration>0){double duration=queuedDuration;advance(duration);queuedDuration-=duration;}}
    void advance(double duration) {
        if (!(Double.isFinite(duration)&&duration>0)) throw new IllegalArgumentException("Invalid environment duration");
        if(storage==null)return;
        try(var capture=storage.capture();var candidate=storage.prepareReallocated(capture,accounting.bytes())) {
            GpuBuffer[] used=new GpuBuffer[1];
            int error=candidate.execute(0,(command,input,output,errors,staging,stack)->{
                used[0]=output;copyPersistent(command,input,output,stack);
                kernel.record(command,layout,output,errors,duration);
                kernel.recordAccounting(command,accounting,ledger,input,output,errors);
            });
            if(error!=0)throw new IllegalStateException("Environment thermal candidate failed: "+error);
            initializedBuffers.add(used[0]);
            candidate.commit();
        }
    }
    EnvironmentThermalState capture() {
        if(storage==null)return initial;
        try(var captured=storage.capture()) {
            var values=ByteBuffer.allocate(Math.multiplyExact(initial.nodes().size(),8)).order(ByteOrder.LITTLE_ENDIAN);
            storage.read(captured,layout.offset(VulkanEnvironmentThermal.Field.ENERGY),values);
            var amounts=ByteBuffer.allocate(8*surfaceIndices.size()).order(ByteOrder.LITTLE_ENDIAN);
            var residuals=ByteBuffer.allocate(8*surfaceIndices.size()).order(ByteOrder.LITTLE_ENDIAN);
            if(!surfaceIndices.isEmpty()) { storage.read(captured,layout.offset(VulkanEnvironmentThermal.Field.SURFACE_AMOUNTS),amounts);
                storage.read(captured,layout.offset(VulkanEnvironmentThermal.Field.SURFACE_RESIDUALS),residuals); }
            var nodes=new ArrayList<EnvironmentThermalState.Node>();
            for(int i=0;i<initial.nodes().size();i++){
                var old=initial.nodes().get(i);var surfaces=new ArrayList<FixedSurfaceState>();
                for(var surface:old.surfaces()){
                    var species=new java.util.TreeMap<String,Long>();var remainder=new java.util.TreeMap<String,Double>();
                    for(var adsorbate:definitions.thermal().surfaceMaterial(surface.materialId()).adsorbates()){
                        int at=surfaceIndices.get(new SurfaceKey(i,surface.face(),adsorbate.speciesId()));
                        long amount=amounts.getLong(8*at);double residual=residuals.getDouble(8*at);
                        if(amount>0)species.put(adsorbate.speciesId(),amount);if(residual!=0)remainder.put(adsorbate.speciesId(),residual);
                    }
                    surfaces.add(new FixedSurfaceState(surface.face(),surface.materialId(),surface.loadingMassUnits(),species,remainder));
                }
                nodes.add(new EnvironmentThermalState.Node(old.key(),old.materialId(),old.massUnits(),old.volumeCubicMetres(),old.gas(),values.getDouble(i*8),surfaces));
            }
            var old=initial.audit();
            var audit=new EnvironmentThermalState.Audit(old.initial(),old.source().add(readLedger(captured,accounting.source().result())),
                    old.sink().add(readLedger(captured,accounting.sink().result())),old.regionExchange().add(readLedger(captured,accounting.region().result())),
                    old.roundoff().add(readLedger(captured,accounting.roundoff().result())));
            return new EnvironmentThermalState(initial.dimensionId(),captured.generation(),initial.registryFingerprint(),nodes,initial.contacts(),audit);
        }
    }
    private SignedDyadic readLedger(VulkanRegionStorage.Capture capture,VulkanLiquidStateLayout.Column column){
        var bytes=ByteBuffer.allocate(Math.toIntExact(column.bytes())).order(ByteOrder.LITTLE_ENDIAN);storage.read(capture,column.offset(),bytes);
        int sign=bytes.getInt(0),length=bytes.getInt(8);var magnitude=java.math.BigInteger.ZERO;
        for(int i=length-1;i>=0;i--)magnitude=magnitude.shiftLeft(32).add(java.math.BigInteger.valueOf(Integer.toUnsignedLong(bytes.getInt(16+4*i))));
        return sign==0?SignedDyadic.ZERO:new SignedDyadic(sign<0?magnitude.negate():magnitude,bytes.getInt(4));
    }
    private static VulkanEnvironmentThermal.Node caloric(EnvironmentThermalState.Node node,EnvironmentThermalState state,FluidDefinitions definitions){
        var registry=definitions.thermal();var knots=new TreeSet<Double>();
        java.util.function.DoubleUnaryOperator energy;
        java.util.function.DoubleUnaryOperator capacity;
        if(node.key().kind()==EnvironmentThermalState.Kind.AIR){
            var gas=new GasThermalModel(registry,definitions.gas());
            energy=t->gas.internalEnergyAtTemperature(node.gas(),t);
            capacity=t->{double result=0;for(var component:node.gas().components()){
                var material=registry.material(registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS,component.componentId()));
                result+=component.mass()*material.specificHeatAt(t);
            }return result;};
            for(var component:node.gas().components())addKnots(knots,registry.material(registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS,component.componentId())));
        } else {
            var material=registry.material(registry.materialHandle(node.materialId()));addKnots(knots,material);
            var function=new ThermalStateFunction(registry);int handle=registry.materialHandle(node.materialId());double mass=Mass.kilograms(node.massUnits());
            energy=t->function.internalEnergyAtTemperature(handle,mass,java.util.Map.of(),t);
            capacity=t->mass*material.specificHeatAt(t);
        }
        double[] bounds=knots.stream().mapToDouble(Double::doubleValue).toArray();
        double[] intercept=new double[bounds.length+1], capacities=new double[bounds.length+1], upper=new double[bounds.length+1];
        for(int i=0;i<capacities.length;i++){
            double temperature=i==0?(bounds.length==0?293.15:Math.max(1.0,bounds[0]*.5)):i==bounds.length?bounds[i-1]+1:(bounds[i-1]+bounds[i])*.5;
            capacities[i]=capacity.applyAsDouble(temperature);intercept[i]=energy.applyAsDouble(temperature)-capacities[i]*temperature;
            upper[i]=i<bounds.length?energy.applyAsDouble(bounds[i]):Double.POSITIVE_INFINITY;
        }
        double[] temperatures=new double[capacities.length];System.arraycopy(bounds,0,temperatures,0,bounds.length);
        return new VulkanEnvironmentThermal.Node(node.internalEnergyJ(),intercept,capacities,upper,temperatures);
    }
    private static void addKnots(TreeSet<Double> knots,ThermalMaterialDefinition material){
        var segments=material.caloricSegments();for(int i=0;i+1<segments.size();i++)knots.add(segments.get(i).maximumTemperatureK());
    }
    @Override public void close(){for(var projection:projections)projection.close();projections.clear();for(var gas:gasMappings.values())gas.close();gasMappings.clear();if(storage!=null){try{storage.close();}finally{try{ledger.close();}finally{kernel.close();}}}}
}
