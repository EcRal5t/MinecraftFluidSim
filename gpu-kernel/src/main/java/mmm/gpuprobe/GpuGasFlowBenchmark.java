package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import static mmm.gpuprobe.VulkanRegionStorage.*;
import static org.lwjgl.vulkan.VK10.*;

/** Continuous finite-aperture inventory transport, including a large chamber shared by many openings. */
public final class GpuGasFlowBenchmark {
    private GpuGasFlowBenchmark() { }
    private record Tail(VulkanGasFlow.Layout flow,VulkanGasStateLayout retained,VulkanGasStateLayout future,
                        VulkanGasRemap.Layout remap,VulkanGasThermodynamics.Layout retainedEOS,
                        VulkanGasAdiabaticWork.Layout work,long bytes) { }
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int device=Integer.parseInt(args[0]),grid=Integer.parseInt(args[1]),warm=Integer.parseInt(args[2]),samples=Integer.parseInt(args[3]);
        try(var context=new VulkanDeviceContext(device,true,true);var flow=new VulkanGasFlow(context);
            var eos=new VulkanGasThermodynamics(context);var pressure=new VulkanPressureKernel(context,4096);
            var remap=new VulkanGasRemap(context);var work=new VulkanGasAdiabaticWork(context);var timings=new VulkanStageTimings(context,256)) {
            long a=Math.max(8,context.storageOffsetAlignment());int cells=grid*grid*grid;
            var fixture=GpuLiquidGeometryBenchmark.fixture(grid,false);
            var liquid=VulkanLiquidStateLayout.forState(fixture.inventory(),fixture.momentum(),a);
            var g=GpuGasGeometryBenchmark.geometry(liquid,a);
            var totals=VulkanLiquidCellVolumes.layout(liquid,2*cells,-40,g.endOffset(),a);
            var geometry=VulkanGasGeometry.layout(totals,word(g.masks()),word(g.occupied()),word(g.cells()),word(g.planes()),word(g.micro()),true,.5,totals.endOffset(),a);
            int leaves=(grid/2)*(grid/2)*(grid/2),nodes=leaves+1;
            var definitions=GpuGasGeometryBenchmark.gasDefinitions();
            var gas=new VulkanGasStateLayout(grid,grid,grid,nodes,2*(nodes+2),2*(nodes+2),definitions.definitions(),geometry.endOffset(),a);
            var tables=VulkanGasThermodynamics.tables(gas.definitions(),definitions.gas(),definitions.thermal(),mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY);
            var sourceEOS=VulkanGasThermodynamics.layout(gas,tables,Math.toIntExact(gas.endOffset()/4),gas.endOffset()+8L*nodes,a);
            var ambientRegistry=new mmm.fluid.air.GasRegistry(definitions.gas().components(),List.of(
                    new mmm.fluid.air.GasRegistry.AmbientFraction(mmm.fluid.air.BuiltinGasDefinitions.NITROGEN,mmm.chemistry.ExactRational.parse(".7")),
                    new mmm.fluid.air.GasRegistry.AmbientFraction(mmm.fluid.air.BuiltinGasDefinitions.OXYGEN,mmm.chemistry.ExactRational.parse(".3"))));
            var ambient=VulkanGasRemap.ambientTables(ambientRegistry,definitions.definitions(),293.15);
            Tail tail=tail(sourceEOS,geometry,tables,ambient,1,a);
            var initial=ByteBuffer.allocate(Math.toIntExact(tail.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            geometry.initializeHeader(initial);sourceEOS.initializeHeaderAndTables(initial);
            initializeGeometry(initial,geometry,grid,nodes);
            try(var storage=new VulkanRegionStorage(context,tail.bytes(),1024*1024)) {
                storage.initialize(initial,0);
                try(var captured=storage.capture()){GpuGasGeometryBenchmark.seed(storage,captured,initial,gas,geometry,definitions);}
                // The seed supplies actual registry inventories; alternating chamber pressures drive finite transport.
                for(int node=0;node<nodes;node++){
                    double scale=node==0?1.5:.5;
                    initial.putDouble(Math.toIntExact(gas.column(VulkanGasStateLayout.Field.INTERNAL_ENERGY).offset()+8L*node),
                            initial.getDouble(Math.toIntExact(gas.column(VulkanGasStateLayout.Field.INTERNAL_ENERGY).offset()+8L*node))*scale);
                }
                storage.initialize(initial,1);boolean upload=true;long growth=0;
                System.out.printf("Device: %s; continuous finite hub grid=%d nodes=%d, up to six faces per chamber pair%n",context.deviceName(),grid,nodes);
                long[] times=new long[samples];VulkanRegionStorage.Metrics before=null;
                for(int step=0;step<warm+samples;step++) {
                    if(step==warm)before=storage.metrics();long begin=System.nanoTime();
                    try(var captured=storage.capture()) {
                        while(true) {
                            int required=advance(storage,captured,flow,eos,pressure,remap,work,sourceEOS,tail,step,upload,timings);
                            if(required==0){if(samples==1)for(var item:timings.read())System.out.printf("stage %s %.3f ms%n",item.stage(),item.milliseconds());break;}
                            if(required<=tail.flow().pairs)throw new IllegalStateException("Gas flow capacity did not grow");
                            tail=tail(sourceEOS,geometry,tables,ambient,required,a);upload=true;growth++;
                        }
                    }
                    upload=false;if(step>=warm)times[step-warm]=System.nanoTime()-begin;
                }
                var after=storage.metrics();Arrays.sort(times);
                System.out.println("grid,nodes,pairs,median_ms,max_ms,gpu_mean_ms,upload_B,readback_B,device_MiB,growths");
                System.out.printf("%d,%d,%d,%.3f,%.3f,%.3f,%d,%d,%.1f,%d%n",grid,nodes,tail.flow().pairs,times[samples/2]*1e-6,times[samples-1]*1e-6,
                        (after.gpuNanos()-before.gpuNanos())*1e-6/samples,(after.uploadBytes()-before.uploadBytes())/samples,
                        (after.readbackBytes()-before.readbackBytes())/samples,after.deviceBytes()/1048576.,growth);
                try(var captured=storage.capture()){
                    var result=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                    storage.read(captured,gas.column(VulkanGasStateLayout.Field.STANDARD_VOLUME_NANOS).offset(),result);
                    long previous=initial.getLong(Math.toIntExact(gas.column(VulkanGasStateLayout.Field.STANDARD_VOLUME_NANOS).offset()));
                    System.out.printf("hub_first_component_standard_nanos initial=%d final=%d delta=%d committed_steps=%d%n",previous,result.getLong(0),result.getLong(0)-previous,warm+samples);
                }
            }
        }
    }
    private static Tail tail(VulkanGasThermodynamics.Layout source,VulkanGasGeometry.Layout geometry,VulkanGasThermodynamics.Tables tables,
                             VulkanGasRemap.AmbientTables ambient,int pairs,long a) {
        var flow=VulkanGasFlow.layout(source,geometry,pairs,source.endOffset(),a);var gas=source.gas();
        var retained=new VulkanGasStateLayout(gas.sizeX(),gas.sizeY(),gas.sizeZ(),gas.nodeCapacity(),gas.componentCapacity(),gas.speciesCapacity(),gas.definitions(),flow.endOffset(),a);
        var future=new VulkanGasStateLayout(gas.sizeX(),gas.sizeY(),gas.sizeZ(),gas.nodeCapacity(),gas.componentCapacity(),gas.speciesCapacity(),gas.definitions(),retained.endOffset(),a);
        var remap=VulkanGasRemap.transportLayout(flow.routes(),source,retained,future,ambient,flow.word(VulkanGasFlow.Field.STEP),2*flow.routes,2*flow.routes,future.endOffset(),a);
        var retainedEOS=VulkanGasThermodynamics.selfPartsLayout(remap,tables,flow.word(VulkanGasFlow.Field.RETAINED_VOLUME),remap.endOffset(),a);
        var work=VulkanGasAdiabaticWork.layout(retainedEOS,source.nodeVolumesWordOffset(),flow.word(VulkanGasFlow.Field.MODES),retainedEOS.endOffset(),a);
        return new Tail(flow,retained,future,remap,retainedEOS,work,work.endOffset());
    }
    private static int advance(VulkanRegionStorage storage,VulkanRegionStorage.Capture source,VulkanGasFlow flow,VulkanGasThermodynamics eos,
                               VulkanPressureKernel pressure,VulkanGasRemap remap,VulkanGasAdiabaticWork work,
                               VulkanGasThermodynamics.Layout sourceEOS,Tail t,long step,boolean upload,VulkanStageTimings timings) {
        var q=VulkanGasFlow.parameters(.05,.6,t.remap().ambient,step);var l=t.flow();int n=l.nodes;var operator=flow.pressureOperator(l,q);
        try(var candidate=storage.prepareReallocated(source,t.bytes())) {
            long uploaded=upload?headers(t).stream().mapToLong(x->x.bytes().remaining()).sum():0;
            var status=candidate.beginBatch(uploaded,(command,input,output,errors,staging,stack)->{
                timings.reset(command);copy(command,input,0,output,0,sourceEOS.endOffset(),stack);
                if(upload)for(var chunk:headers(t))vkCmdUpdateBuffer(command,output.handle(),chunk.offset(),chunk.bytes());
                else copy(command,input,sourceEOS.endOffset(),output,sourceEOS.endOffset(),t.bytes()-sourceEOS.endOffset(),stack);
                barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                flow.rebindRanges(dst,err);eos.rebindRanges(dst,err);remap.rebindRanges(dst,err);work.rebindRanges(dst,err);
                pressure.rebindRanges(l.pressureRanges(dst,err));timings.record(command,"source-eos",()->{eos.recordVolumes(command,sourceEOS,l.geometry);eos.record(command,sourceEOS);});timings.record(command,"flow-assemble",()->flow.recordAssemble(command,l,q));
            },l.requiredPairsOffset(),4);
            if(status.error()==64)return status.feedback().getInt(0);
            if(status.error()!=0)throw new AssertionError("Flow assembly="+status.error());
            var batch=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                timings.record(command,"flow-pcg-initialize",()->pressure.recordInitialize(command,n,n,200,null,null,operator));timings.record(command,"flow-pcg-batch",()->pressure.recordIterations(command,n,n,200,null,null,8,operator));
            },l.column(VulkanGasFlow.Field.CONTROL).offset()+24,8);
            int recorded=8;
            while(batch.error()==0&&batch.feedback().getDouble(0)!=0){if(recorded>=200)throw new AssertionError("Flow PCG limit");
                batch=candidate.continueBatch((command,input,output,errors,staging,stack)->timings.record(command,"flow-pcg-batch",()->pressure.recordIterations(command,n,n,200,null,null,8,operator)),l.column(VulkanGasFlow.Field.CONTROL).offset()+24,8);recorded+=8;}
            if(batch.error()!=0)throw new AssertionError("Flow PCG="+batch.error());
            int error=candidate.finishBatch((command,input,output,errors,staging,stack)->{
                timings.record(command,"flow-pcg-finish",()->pressure.recordFinish(command,n,n,200,null,null,operator));flow.recordRoutes(command,l,q);remap.recordPrepare(command,t.remap(),timings);
                timings.record(command,"flow-retained-eos-work",()->{remap.recordTransportVolumes(command,t.remap(),l);eos.record(command,t.retainedEOS());work.record(command,t.work());});remap.recordFinish(command,t.remap(),t.work(),timings);
                barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                sourceEOS.gas().copyColumns(command,output,t.future(),output,stack);
            });
            if(error!=0)throw new AssertionError("Flow transport="+error);candidate.commit();return 0;
        }
    }
    private static List<VulkanGasThermodynamics.TableChunk> headers(Tail t){
        var out=new java.util.ArrayList<VulkanGasThermodynamics.TableChunk>();
        out.add(new VulkanGasThermodynamics.TableChunk(t.flow().header.offset(),t.flow().headerBytes()));
        out.add(new VulkanGasThermodynamics.TableChunk(t.remap().header.offset(),t.remap().headerBytes()));out.addAll(t.remap().tableChunks());
        out.add(new VulkanGasThermodynamics.TableChunk(t.retainedEOS().header().offset(),t.retainedEOS().headerBytes()));out.addAll(t.retainedEOS().tableChunks());
        out.add(new VulkanGasThermodynamics.TableChunk(t.work().header().offset(),t.work().headerBytes()));return out;
    }
    private static void initializeGeometry(ByteBuffer b,VulkanGasGeometry.Layout g,int grid,int nodes){
        int cells=grid*grid*grid;int[] node=new int[cells],counts=new int[nodes+1],roots=new int[nodes+1];int next=2;
        for(int z=0;z<grid;z++)for(int y=0;y<grid;y++)for(int x=0;x<grid;x++){
            int cell=(z*grid+y)*grid+x,id=((x&1)==1&&(y&1)==1&&(z&1)==1)?next++:1;
            node[cell]=id;counts[id]++;if(roots[id]==0)roots[id]=cell+1;
            b.putInt(Math.toIntExact(g.cellNodes().offset()+4L*cell),id);b.putInt(Math.toIntExact(g.dryMasks().offset()+4L*cell),255);
            b.putDouble(Math.toIntExact(g.dryVolumes().offset()+8L*cell),.125);
            b.putInt(Math.toIntExact(g.parents().offset()+4L*(cell+1)),roots[id]);
        }
        b.putInt(Math.toIntExact(g.componentCount().offset()),nodes);int offset=0;int[] cursor=new int[nodes+1];
        for(int id=0;id<=nodes;id++){b.putInt(Math.toIntExact(g.componentOffsets().offset()+4L*id),offset);cursor[id]=offset;offset+=counts[id];}
        b.putInt(Math.toIntExact(g.componentOffsets().offset()+4L*(nodes+1)),offset);
        for(int c=0;c<cells;c++)b.putInt(Math.toIntExact(g.componentMembers().offset()+4L*cursor[node[c]]++),c);
        int face=0;
        for(int axis=0;axis<3;axis++){int sx=grid+(axis==0?1:0),sy=grid+(axis==1?1:0),sz=grid+(axis==2?1:0);
            for(int z=0;z<sz;z++)for(int y=0;y<sy;y++)for(int x=0;x<sx;x++,face++){
                int q=axis==0?x:axis==1?y:z;if(q==0||q==grid)continue;
                int right=(z*grid+y)*grid+x,left=right-(axis==0?1:axis==1?grid:grid*grid);
                boolean same=node[left]==node[right];b.putDouble(Math.toIntExact(g.faceAreas().offset()+8L*face),same?.25:.0001);
                b.putInt(Math.toIntExact(g.fullConnections().offset()+4L*face),same?1:0);
            }
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
}
