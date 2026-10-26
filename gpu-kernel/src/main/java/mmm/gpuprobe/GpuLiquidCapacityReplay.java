package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Replays captured real capacity inputs and checks every accepted source, receiver and finite group. */
public final class GpuLiquidCapacityReplay {
    private GpuLiquidCapacityReplay() { }
    public static void main(String[] args) throws java.io.IOException {
        Locale.setDefault(Locale.ROOT);int device=Integer.parseInt(args[0]);var file=Path.of(args[1]);int maximum=Integer.parseInt(args[2]);
        var saved=ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        if(saved.getLong(0)!=0x4d4d4d4341503031L)throw new IllegalArgumentException("Not a capacity failure capture");
        long oldBase=saved.getLong(8);int oldHeader=Math.toIntExact(32+saved.getLong(24)-oldBase);
        int sx=saved.getInt(oldHeader),sy=saved.getInt(oldHeader+4),sz=saved.getInt(oldHeader+8),groups=saved.getInt(oldHeader+16);
        try(var context=new VulkanDeviceContext(device,true,true);var solver=new VulkanLiquidCapacity(context)) {
            System.out.println("Device: "+context.deviceName());
            long alignment=Math.max(8,context.storageOffsetAlignment());var layout=new VulkanLiquidCapacityLayout(sx,sy,sz,groups,0,alignment);
            var solve=new VulkanLiquidCapacity.Layout(layout,layout.endOffset(),alignment);
            var initial=ByteBuffer.allocate(Math.toIntExact(solve.endOffset())).order(ByteOrder.LITTLE_ENDIAN);layout.initializeHeader(initial);solve.initializeHeader(initial);
            for(var field:new VulkanLiquidCapacityLayout.Field[]{VulkanLiquidCapacityLayout.Field.K,VulkanLiquidCapacityLayout.Field.LOWER,VulkanLiquidCapacityLayout.Field.UPPER,
                    VulkanLiquidCapacityLayout.Field.GROUP_ID,VulkanLiquidCapacityLayout.Field.GROUP_CAPACITY,VulkanLiquidCapacityLayout.Field.PREFERRED,VulkanLiquidCapacityLayout.Field.OPEN_NEIGHBORS}) {
                var column=layout.column(field);int from=Math.toIntExact(32+4L*saved.getInt(oldHeader+4*(8+field.ordinal()))-oldBase);
                initial.put(Math.toIntExact(column.offset()),saved.array(),from,Math.toIntExact(column.bytes()));
            }
            try(var storage=new VulkanRegionStorage(context,solve.endOffset(),1024*1024)) {
                storage.initialize(initial,0);var before=storage.metrics();long begin=System.nanoTime();
                try(var capture=storage.capture();var candidate=storage.prepare(capture)) {
                    var result=candidate.beginBatch((command,input,output,errors,staging,stack)->{
                        solver.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                        solver.recordInitialize(command,solve,maximum);solver.recordIterations(command,solve,Math.min(32,maximum),3);
                    },layout.column(VulkanLiquidCapacityLayout.Field.CONTROL).offset(),12);
                    int status=result.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0),rounds=result.feedback().getInt(4);
                    while(result.error()==0&&status==VulkanLiquidCapacity.RUNNING&&rounds<maximum){
                        int count=Math.min(32,maximum-rounds),phase=result.feedback().getInt(8);
                        result=candidate.continueBatch((command,input,output,errors,staging,stack)->solver.recordIterations(command,solve,count,phase),layout.column(VulkanLiquidCapacityLayout.Field.CONTROL).offset(),12);
                        status=result.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0);rounds=result.feedback().getInt(4);
                    }
                    if(result.error()!=0||status!=VulkanLiquidCapacity.FEASIBLE){
                        var debug=ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);candidate.read(solve.column(VulkanLiquidCapacity.Field.STATE).offset(),debug);
                        System.out.println("solver_state="+java.util.Arrays.toString(java.util.stream.IntStream.range(0,9).map(i->debug.getInt(4*i)).toArray()));
                        int vertices=2*layout.cells()+layout.groups()+1;
                        var balances=ByteBuffer.allocate(8*vertices).order(ByteOrder.LITTLE_ENDIAN);
                        var labels=ByteBuffer.allocate(4*vertices).order(ByteOrder.LITTLE_ENDIAN);
                        candidate.read(solve.column(VulkanLiquidCapacity.Field.EXCESS).offset(),balances);
                        candidate.read(solve.column(VulkanLiquidCapacity.Field.DISTANCE).offset(),labels);
                        long positive=0;int count=0,maximumHeight=0;int[] kinds=new int[4];
                        for(int v=0;v<vertices;v++)if(balances.getLong(8*v)>0){positive=Math.addExact(positive,balances.getLong(8*v));count++;maximumHeight=Math.max(maximumHeight,labels.getInt(4*v));kinds[v<layout.cells()?0:v<2*layout.cells()?1:v==vertices-1?3:2]++;}
                        System.out.printf("positive_vertices=%d positive_quanta=%d maximum_height=%d donor_receiver_group_sink=%s%n",count,positive,maximumHeight,java.util.Arrays.toString(kinds));
                    }
                    if(result.error()!=0||status!=VulkanLiquidCapacity.FEASIBLE)throw new IllegalStateException("Capacity replay failed status="+status+", rounds="+rounds+", error="+result.error());
                    var solveState=ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
                    candidate.read(solve.column(VulkanLiquidCapacity.Field.STATE).offset(),solveState);
                    System.out.printf("residual_bfs_rounds=%d blocking_flow_rounds=%d%n",solveState.getInt(24),solveState.getInt(28));
                    int error=candidate.finishBatch((command,input,output,errors,staging,stack)->solver.recordFinish(command,solve));
                    if(error!=0)throw new IllegalStateException("Capacity replay finish error="+error);candidate.commit();
                    var after=storage.metrics();System.out.printf("capture=%s cells=%d rounds=%d wall_ms=%.3f gpu_ms=%.3f device_MiB=%.1f%n",file,layout.cells(),rounds,(System.nanoTime()-begin)*1e-6,(after.gpuNanos()-before.gpuNanos())*1e-6,after.deviceBytes()/1048576.0);
                } finally { solver.printStageTimings(); }
                var accepted=ByteBuffer.allocate(Math.toIntExact(layout.column(VulkanLiquidCapacityLayout.Field.ACCEPTED).bytes())).order(ByteOrder.LITTLE_ENDIAN);
                try(var capture=storage.capture()){storage.read(capture,layout.column(VulkanLiquidCapacityLayout.Field.ACCEPTED).offset(),accepted);}
                long moved=verify(layout,initial,accepted,sx,sy,sz);
                System.out.println("All source quotas, receiver bounds and finite-group capacities passed; moved_quanta="+moved);
            }
        }
    }
    static long verify(VulkanLiquidCapacityLayout layout,ByteBuffer initial,ByteBuffer accepted,int sx,int sy,int sz){
        int groups=layout.gasGroups();
        long[] received=new long[layout.cells()],groupTotals=new long[groups];long moved=0;
        for(int c=0;c<layout.cells();c++){
            long sum=0;for(int slot=0;slot<7;slot++){
                long amount=accepted.getLong(8*(7*c+slot));if(amount<0)throw new AssertionError("Negative route at "+c);
                sum=Math.addExact(sum,amount);int target=slot==0?c:neighbor(c,slot-1,sx,sy,sz);
                if(amount>0&&target<0)throw new AssertionError("Route outside domain");
                if(target>=0)received[target]=Math.addExact(received[target],amount);if(slot>0)moved=Math.addExact(moved,amount);
            }
            if(sum!=value(initial,layout,VulkanLiquidCapacityLayout.Field.K,c))throw new AssertionError("Source quota at "+c);
        }
        for(int c=0;c<layout.cells();c++){
            if(received[c]<value(initial,layout,VulkanLiquidCapacityLayout.Field.LOWER,c)||received[c]>value(initial,layout,VulkanLiquidCapacityLayout.Field.UPPER,c))throw new AssertionError("Receiver bounds at "+c);
            int group=initial.getInt(Math.toIntExact(layout.column(VulkanLiquidCapacityLayout.Field.GROUP_ID).offset()+4L*c));
            if(group>=0)groupTotals[group]=Math.addExact(groupTotals[group],received[c]);
        }
        for(int g=0;g<groups;g++)if(groupTotals[g]>value(initial,layout,VulkanLiquidCapacityLayout.Field.GROUP_CAPACITY,g))throw new AssertionError("Finite group capacity at "+g);
        if(moved==0)throw new AssertionError("Replay removed all transport");
        return moved;
    }
    private static long value(ByteBuffer bytes,VulkanLiquidCapacityLayout layout,VulkanLiquidCapacityLayout.Field field,int index){return bytes.getLong(Math.toIntExact(layout.column(field).offset()+8L*index));}
    private static int neighbor(int c,int side,int sx,int sy,int sz){int[] xyz={c%sx,c/sx%sy,c/(sx*sy)},shape={sx,sy,sz};int axis=side/2;xyz[axis]+=(side&1)==0?-1:1;
        return xyz[axis]<0||xyz[axis]>=shape[axis]?-1:(xyz[2]*sy+xyz[1])*sx+xyz[0];}
}
