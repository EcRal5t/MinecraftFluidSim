package mmm.gpu;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Cold adapter for timing a captured liquid/gas pressure system without replaying earlier steps. */
public final class GpuCommonPressureReplay {
    private GpuCommonPressureReplay() { }
    record Result(VulkanPressureKernel.Progress progress,long graphNanos,long solveNanos) { }
    record Fixture(int[] starts,int[] columns,double[] values,int[] bounded,double[] rhs,double[] lower,double[] warm) {
        int size(){return rhs.length;}
    }
    private static int[] ints(ByteBuffer source,int count){int[] values=new int[count];source.asIntBuffer().get(values);source.position(source.position()+4*count);return values;}
    private static double[] doubles(ByteBuffer source,int count){double[] values=new double[count];source.asDoubleBuffer().get(values);source.position(source.position()+8*count);return values;}
    static Fixture read(Path path)throws IOException{
        var b=ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);byte[] magic=new byte[8];b.get(magic);
        if(!Arrays.equals(magic,"MMMPRE01".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new IllegalArgumentException("Unsupported common pressure dump");
        int cells=Math.multiplyExact(Math.multiplyExact(b.getInt(),b.getInt()),b.getInt()),n=b.getInt(),gas=b.getInt(),nnz=b.getInt(),edges=b.getInt();b.getDouble();b.getDouble();
        int[] starts=ints(b,n+1),columns=ints(b,nnz);double[] values=doubles(b,nnz);
        int[] identities=ints(b,n),bounded=ints(b,n);ints(b,n);
        double[] rhs=doubles(b,n);doubles(b,n);double[] lower=doubles(b,n),warm=doubles(b,n);
        int[] offsets=ints(b,gas+1),peers=ints(b,edges);double[] weights=doubles(b,edges);doubles(b,gas);double[] diagonal=doubles(b,gas);
        int[] compact=new int[n];Arrays.fill(compact,-1);int count=0;
        for(int i=0;i<n;i++)if(identities[i]>=0)compact[i]=count++;
        int[] targetStarts=new int[count+1];
        for(int i=0;i<n;i++)if(compact[i]>=0){int row=compact[i],length=0;
            if(i<cells){for(int at=starts[i];at<starts[i+1];at++)if(values[at]!=0&&compact[columns[at]]>=0)length++;}
            else{length=1;for(int at=offsets[i-cells];at<offsets[i-cells+1];at++)if(peers[at]>=0&&compact[peers[at]]>=0&&weights[at]!=0)length++;}
            targetStarts[row+1]=Math.addExact(targetStarts[row],length);
        }
        int[] targetColumns=new int[targetStarts[count]],targetBounded=new int[count];double[] targetValues=new double[targetColumns.length],targetRhs=new double[count],targetLower=new double[count],targetWarm=new double[count];
        for(int i=0;i<n;i++)if(compact[i]>=0){int row=compact[i],cursor=targetStarts[row];targetBounded[row]=bounded[i];targetRhs[row]=rhs[i];targetLower[row]=lower[i];targetWarm[row]=warm[i];
            if(i<cells){for(int at=starts[i];at<starts[i+1];at++)if(values[at]!=0&&compact[columns[at]]>=0){targetColumns[cursor]=compact[columns[at]];targetValues[cursor++]=values[at];}}
            else{targetColumns[cursor]=row;targetValues[cursor++]=diagonal[i-cells];for(int at=offsets[i-cells];at<offsets[i-cells+1];at++)if(peers[at]>=0&&compact[peers[at]]>=0&&weights[at]!=0){targetColumns[cursor]=compact[peers[at]];targetValues[cursor++]=-weights[at];}}
        }
        return new Fixture(targetStarts,targetColumns,targetValues,targetBounded,targetRhs,targetLower,targetWarm);
    }
    private record Blocks(int[] rows,int[] begins,int[] longRows,int[] offsets) {
        static Blocks create(Fixture f){
            var rows=new ArrayList<Integer>();var begins=new ArrayList<Integer>();var longRows=new ArrayList<Integer>();var offsets=new ArrayList<Integer>();offsets.add(0);
            for(int row=0;row<f.size();row++)if(f.starts[row+1]-f.starts[row]>32){longRows.add(row);for(int at=f.starts[row];at<f.starts[row+1];at+=128){rows.add(row);begins.add(at);}offsets.add(rows.size());}
            return new Blocks(array(rows),array(begins),array(longRows),array(offsets));
        }
        private static int[] array(List<Integer> values){return values.stream().mapToInt(Integer::intValue).toArray();}
    }
    private record Layout(List<VulkanLiquidStateLayout.Column> columns,VulkanLiquidStateLayout.Column feedback,long bytes,Blocks blocks){
        VulkanLiquidStateLayout.Column column(int i){return columns.get(i);}
        static Layout create(Fixture f,long alignment){int n=f.size(),e=f.values.length;var blocks=Blocks.create(f);int[] counts={n+1,e,e,n,n,n,7*n,2*n,3*n+1,16,4*((n+255)/256),blocks.rows.length,blocks.begins.length,blocks.rows.length,blocks.longRows.length,blocks.offsets.length};int[] widths={4,4,8,4,8,8,8,4,8,8,8,4,4,8,4,4};
            var columns=new ArrayList<VulkanLiquidStateLayout.Column>();long cursor=0;for(int i=0;i<counts.length;i++){cursor=(cursor+alignment-1)&-alignment;var c=new VulkanLiquidStateLayout.Column(cursor,counts[i],widths[i]);columns.add(c);cursor+=c.bindingBytes();}
            cursor=(cursor+alignment-1)&-alignment;var feedback=new VulkanLiquidStateLayout.Column(cursor,2,4);return new Layout(List.copyOf(columns),feedback,(cursor+8+alignment-1)&-alignment,blocks);
        }
        List<FlipKernelBindings.BufferRange> bindings(GpuBuffer arena,GpuBuffer errors){var list=new ArrayList<FlipKernelBindings.BufferRange>();for(var c:columns.subList(0,11))list.add(new FlipKernelBindings.BufferRange(arena,c.offset(),c.bindingBytes()));list.add(new FlipKernelBindings.BufferRange(errors));return list;}
        VulkanPressureGraphPreconditioner.Source graphSource(int n){return new VulkanPressureGraphPreconditioner.Source(n,column(0),column(1),column(2),column(3),column(6),column(7),column(9),feedback);}
        void initialize(VulkanRegionStorage storage,Fixture f){storage.initialize(bytes,0,out->{
            out.put(column(0),f.starts);out.put(column(1),f.columns);out.put(column(2),f.values);int[] identities=new int[f.size()];Arrays.setAll(identities,i->i);out.put(column(3),identities);
            int[] meta=new int[2*f.size()];System.arraycopy(f.bounded,0,meta,0,f.size());out.put(column(7),meta);
            double[] problem=new double[3*f.size()+1];System.arraycopy(f.rhs,0,problem,0,f.size());System.arraycopy(f.lower,0,problem,f.size(),f.size());System.arraycopy(f.warm,0,problem,2*f.size(),f.size());problem[3*f.size()]=1e-5f;out.put(column(8),problem);
            out.put(column(11),blocks.rows);out.put(column(12),blocks.begins);out.put(column(14),blocks.longRows);out.put(column(15),blocks.offsets);
        });}
    }
    /** The cold adapter uses the production short-row / parallel long-row product strategy. */
    private static final class Product implements VulkanPressureKernel.PressureOperator,AutoCloseable {
        private final FlipKernelBindings bindings;private final Layout layout;private final int rows;
        Product(VulkanDeviceContext context,Layout layout,int rows)throws IOException{
            this.layout=layout;this.rows=rows;
            try(var in=GpuCommonPressureReplay.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/replay-pressure-product.comp")){
                if(in==null)throw new IOException("Missing pressure replay product shader");
                bindings=new FlipKernelBindings(context,11,new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),20);
            }
        }
        void bind(GpuBuffer arena){var ranges=new ArrayList<FlipKernelBindings.BufferRange>();for(int index:new int[]{0,1,2,5,6,9,11,12,13,14,15}){var c=layout.column(index);ranges.add(new FlipKernelBindings.BufferRange(arena,c.offset(),c.bindingBytes()));}bindings.rebindRanges(ranges);}
        public int liquidCount(){return rows;}
        public int matrixFreeFromRow(){return 0;}
        public void recordProduct(VkCommandBuffer command,boolean direction){
            try(var stack=stackPush()){bindings.bind(command,stack);for(int stage=0;stage<3;stage++){
                vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(rows,layout.blocks.rows.length,layout.blocks.longRows.length,direction?1:0,stage));
                int groups=stage==0?(rows+127)/128:stage==1?layout.blocks.rows.length:(layout.blocks.longRows.length+127)/128;
                vkCmdDispatch(command,groups,1,1);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
            }}
        }
        public void close(){bindings.close();}
    }
    public static void main(String[] args)throws IOException{
        Locale.setDefault(Locale.ROOT);int device=Integer.parseInt(args[0]),samples=args.length>2?Integer.parseInt(args[2]):3;Fixture f=read(Path.of(args[1]));
        try(var context=new VulkanDeviceContext(device,true,true);var pressure=new VulkanPressureKernel(context,4096);var graph=new VulkanPressureGraphPreconditioner(context)){
            Layout l=Layout.create(f,Math.max(8,context.storageOffsetAlignment()));try(var storage=new VulkanRegionStorage(context,l.bytes,1024*1024);var product=new Product(context,l,f.size());var templates=new VulkanCommandTemplates(context)){
                l.initialize(storage,f);advance(storage,pressure,graph,product,templates,l,f.size());var before=storage.metrics();long beforeUploads=graph.uploadBytes();long[] times=new long[samples];
                for(int i=0;i<samples;i++){long start=System.nanoTime();var result=advance(storage,pressure,graph,product,templates,l,f.size());times[i]=System.nanoTime()-start;System.out.printf("sample=%d wall_ms=%.3f graph_ms=%.3f solve_ms=%.3f iterations=%d rms=%.9g%n",i,times[i]*1e-6,result.graphNanos()*1e-6,result.solveNanos()*1e-6,result.progress().iterations(),result.progress().residualRms());}
                var after=storage.metrics();Arrays.sort(times);System.out.printf("device=%s rows=%d entries=%d median_ms=%.3f max_ms=%.3f gpu_mean_ms=%.3f upload_B_per_step=%.1f readback_B_per_step=%.1f device_MiB=%.3f%n",context.deviceName(),f.size(),f.values.length,times[samples/2]*1e-6,times[samples-1]*1e-6,(after.gpuNanos()-before.gpuNanos())*1e-6/samples,
                        (after.uploadBytes()-before.uploadBytes()+graph.uploadBytes()-beforeUploads)/(double)samples,(after.readbackBytes()-before.readbackBytes())/(double)samples,(after.deviceBytes()+graph.deviceBytes())/1048576.0);
                System.out.println("Captured pressure system only; cold CSR conversion is outside timing. Includes candidate copy, graph preparation, GPU solve and adoption; no CPU reference solve.");
            }
        }
    }
    private static Result advance(VulkanRegionStorage storage,VulkanPressureKernel pressure,VulkanPressureGraphPreconditioner graph,Product product,VulkanCommandTemplates templates,Layout l,int n){
        long graphStart=System.nanoTime();
        try(var source=storage.capture();var candidate=storage.prepare(source)){
            var status=candidate.beginBatch((command,input,output,errors,staging,stack)->{pressure.rebindRanges(l.bindings(output,errors));product.bind(output);graph.recordCount(command,output,errors,l.graphSource(n));},l.feedback.offset(),8);
            if(status.error()!=0)throw new IllegalStateException("Captured pressure graph count failed: status="+status.error());
            int error=graph.build(candidate,status.feedback().getInt(0),status.feedback().getInt(4));if(error!=0)throw new IllegalStateException("Captured pressure graph build failed: status="+error);
            long graphNanos=System.nanoTime()-graphStart,solveStart=System.nanoTime();
            var operator=graph.operator(product);
            status=candidate.continueBatch((command,input,output,errors,staging,stack)->{pressure.recordInitialize(command,n,n,200,null,null,operator);recordIterations(command,output,errors,pressure,operator,templates,n,16);},l.column(9).offset(),128);
            int scheduled=16;while(status.error()==0&&status.feedback().getDouble(24)!=0&&scheduled<200){int count=Math.min(16,200-scheduled);status=candidate.continueBatch((command,input,output,errors,staging,stack)->recordIterations(command,output,errors,pressure,operator,templates,n,count),l.column(9).offset(),128);scheduled+=count;}
            if(status.error()==0)status=candidate.finishBatch((command,input,output,errors,staging,stack)->pressure.recordFinish(command,n,n,200,null,null,operator),l.column(9).offset(),128);
            if(status.error()!=0)throw new IllegalStateException("Captured pressure solve failed: status="+status.error()+" control="+VulkanPressureKernel.Progress.read(status.feedback()));
            var progress=VulkanPressureKernel.Progress.read(status.feedback());candidate.commit();long solveNanos=System.nanoTime()-solveStart;graph.reportTimings();return new Result(progress,graphNanos,solveNanos);
        }
    }
    private static void recordIterations(VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,VulkanPressureKernel pressure,
                                         VulkanPressureKernel.PressureOperator operator,VulkanCommandTemplates templates,int n,int count){
        if(Boolean.getBoolean("mmm.gpuPressureGraphProfile"))pressure.recordIterations(command,n,n,200,null,null,count,operator);
        else templates.record(command,new VulkanCommandTemplates.Key(0,output,errors,count,200,operator.preconditionerKey()),
                secondary->pressure.recordIterations(secondary,n,n,200,null,null,count,operator));
    }
}
