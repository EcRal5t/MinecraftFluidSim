package mmm.gpuprobe;

import java.math.BigInteger;
import java.util.Arrays;
import static mmm.gpuprobe.VulkanInventoryTransport.Edge;

/** Hardware validation for resident exact transport, rejection and subsequent reuse. */
public final class GpuInventoryTransportValidation {
    private GpuInventoryTransportValidation() { }
    public static void main(String[] args) {
        int device=args.length==0?0:Integer.parseInt(args[0]);
        privateSubsteps(device);
        try(var gpu=new VulkanInventoryTransport(device,5,12)) {
            long[] initial={Long.MAX_VALUE-100,70,30,0,0};
            gpu.initialize(initial);
            var first=gpu.transport(new Edge[]{new Edge(0,3,123456789012345L),new Edge(1,2,20),new Edge(2,4,30)});
            expect(first.committed(),"Valid transfer rejected");
            expect(Arrays.equals(first.inventory(),new long[]{initial[0]-123456789012345L,50,20,123456789012345L,30}),"Incorrect integer transport");
            expect(total(initial).equals(total(first.inventory())),"Integer total changed");
            long[] saved=first.inventory().clone();
            reject(gpu,new Edge[]{new Edge(1,4,51)},saved);
            reject(gpu,new Edge[]{new Edge(3,0,123456789012346L)},saved);
            reject(gpu,new Edge[]{new Edge(1,9,1)},saved);
            reject(gpu,new Edge[]{new Edge(1,2,-1)},saved);
            var second=gpu.transport(new Edge[]{new Edge(3,0,123456789012345L),new Edge(4,1,30)});
            expect(second.committed(),"Reuse after rejected plan failed");
            expect(Arrays.equals(second.inventory(),new long[]{initial[0],80,20,0,0}),"Repeated resident dispatch differs");
            expect(total(initial).equals(total(second.inventory())),"Repeated total changed");
            gpu.initialize(new long[]{Long.MAX_VALUE,1,0,0,0});
            reject(gpu,new Edge[]{new Edge(1,0,1)},gpu.snapshot());
            gpu.initialize(new long[]{1,0,0,0,0});
            reject(gpu,new Edge[]{new Edge(0,1,1),new Edge(0,2,1)},gpu.snapshot());
            var self=gpu.transport(new Edge[]{new Edge(0,0,Long.MAX_VALUE)});
            expect(self.committed() && self.inventory()[0]==1,"Self route changed inventory");
            System.out.println("GPU inventory PASS device="+gpu.deviceName()+" metrics="+gpu.metrics());
        }
        VulkanInventoryTransport.Checkpoint checkpoint;
        try(var gpu=new VulkanInventoryTransport(device,3,2)) {
            gpu.initialize(new long[]{100,0,7});
            expect(gpu.generation()==0,"Initialization must begin at generation zero");
            long readback=gpu.metrics().readbackBytes();
            for(int step=1;step<=3;step++) {
                var receipt=gpu.transportResident(new Edge[]{new Edge(0,1,10)});
                expect(receipt.committed() && receipt.error()==0 && receipt.generation()==step,"Resident generation differs");
            }
            expect(gpu.metrics().readbackBytes()-readback==12,"Resident advancement read more than one error word per step");
            long generation=gpu.generation();
            var rejected=gpu.transportResident(new Edge[]{new Edge(0,1,71)});
            expect(!rejected.committed() && rejected.generation()==generation,"Rejected resident plan advanced generation");
            expect(gpu.metrics().readbackBytes()-readback==16,"Rejected resident plan read full inventory");
            long submissions=gpu.metrics().submissions();
            try {
                gpu.transportResident(new Edge[]{new Edge(0,1,1),new Edge(0,1,1),new Edge(0,1,1)});
                throw new AssertionError("Edge capacity was ignored");
            } catch(IllegalArgumentException expected) {
                expect(gpu.generation()==generation && gpu.metrics().submissions()==submissions,"Capacity rejection changed device state");
            }
            checkpoint=gpu.checkpoint();
            expect(checkpoint.generation()==3 && Arrays.equals(checkpoint.inventory(),new long[]{70,30,7}),"Checkpoint differs from committed state");
            long[] exported=checkpoint.inventory(); exported[0]=0;
            expect(checkpoint.inventory()[0]==70,"Checkpoint exposes mutable inventory");
            System.out.println("GPU inventory resident PASS device="+gpu.deviceName()+" metrics="+gpu.metrics());
        }
        // Recreate resources and restore a complete explicit checkpoint; device loss is not injected.
        try(var recovered=new VulkanInventoryTransport(device,3,2)) {
            recovered.restore(checkpoint);
            expect(recovered.generation()==3 && Arrays.equals(recovered.snapshot(),checkpoint.inventory()),"Checkpoint restore differs");
            var receipt=recovered.transportResident(new Edge[]{new Edge(1,2,30)});
            expect(receipt.committed() && receipt.generation()==4 && Arrays.equals(recovered.snapshot(),new long[]{70,0,37}),"Restored resident state cannot advance");
            recovered.initialize(new long[]{1,2,3});
            expect(recovered.generation()==0,"Fresh initialization retained old generation");
        }
        // Exercise several workgroups and exact values above float/double integer precision.
        int rows=4097;
        try(var gpu=new VulkanInventoryTransport(device,rows,rows-1)) {
            long[] initial=new long[rows]; Arrays.fill(initial,9_007_199_254_740_993L);
            gpu.initialize(initial);
            Edge[] edges=new Edge[rows-1];
            for(int i=0;i<edges.length;i++) edges[i]=new Edge(i,i+1,123456789L+i);
            var result=gpu.transport(edges);
            expect(result.committed(),"Multi-workgroup transport failed");
            expect(total(initial).equals(total(result.inventory())),"Multi-workgroup total changed");
            for(int i=0;i<rows;i++) {
                long expected=initial[i]-(i<edges.length?edges[i].amount():0)+(i>0?edges[i-1].amount():0);
                expect(result.inventory()[i]==expected,"Multi-workgroup row differs: "+i);
            }
            System.out.println("GPU inventory multi-workgroup PASS device="+gpu.deviceName()+" metrics="+gpu.metrics());
        }
    }

    /** A later failed substep must leave the request's original committed generation untouched. */
    private static void privateSubsteps(int device){
        try(var context=new VulkanDeviceContext(device,true,true);var storage=new VulkanRegionStorage(context,4,4)){
            storage.initialize(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(0,10),5);
            try(var original=storage.capture()){
                try(var first=storage.prepare(original)){
                    expect(first.execute(0,(cmd,input,output,errors,staging,stack)->fill(cmd,output,20,stack))==0,"Private first substep failed");
                    try(var intermediate=first.captureCompleted();var second=storage.prepare(intermediate)){
                        expect(intermediate.generation()==6,"Private logical generation drifted");
                        expect(second.execute(0,(cmd,input,output,errors,staging,stack)->fill(cmd,errors,32,stack))==32,"Injected substep rejection disappeared");
                    }
                }
                try(var current=storage.capture()){
                    var value=java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN);storage.read(current,0,value);
                    expect(current.generation()==5&&value.getInt(0)==10,"Failed private chain published a partial request");
                }
                try(var first=storage.prepare(original)){
                    expect(first.execute(0,(cmd,input,output,errors,staging,stack)->fill(cmd,output,20,stack))==0,"Private retry first substep failed");
                    try(var intermediate=first.captureCompleted();var second=storage.prepare(intermediate)){
                        expect(second.execute(0,(cmd,input,output,errors,staging,stack)->fill(cmd,output,30,stack))==0,"Private retry second substep failed");
                        expect(second.commit(original)==6,"Private chain advanced more than one logical generation");
                    }
                }
            }
            try(var current=storage.capture()){
                var value=java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN);storage.read(current,0,value);
                expect(current.generation()==6&&value.getInt(0)==30,"Private chain final state differs");
            }
            System.out.println("GPU private substeps PASS rollback, retry and one logical commit");
        }
    }
    private static void fill(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer target,int value,org.lwjgl.system.MemoryStack stack){
        VulkanRegionStorage.barrier(command,org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,org.lwjgl.vulkan.VK10.VK_ACCESS_MEMORY_WRITE_BIT,
                org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT,stack);
        org.lwjgl.vulkan.VK10.vkCmdFillBuffer(command,target.handle(),0,4,value);
    }
    private static void reject(VulkanInventoryTransport gpu,Edge[] edges,long[] saved) {
        var result=gpu.transport(edges);
        expect(!result.committed() && result.error()!=0,"Invalid plan accepted");
        expect(Arrays.equals(result.inventory(),saved) && Arrays.equals(gpu.snapshot(),saved),"Rejected plan changed committed inventory");
    }
    private static BigInteger total(long[] inventory) {
        BigInteger result=BigInteger.ZERO; for(long value:inventory) result=result.add(BigInteger.valueOf(value)); return result;
    }
    private static void expect(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
