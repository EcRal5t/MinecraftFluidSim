package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mmm.fluid.EnvironmentBinding;
import mmm.thermal.EnvironmentThermalState;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Device-side finite heat exchange between actual exterior gas packets and world air. */
final class VulkanGasEnvironment implements AutoCloseable {
    final int[] airNodes;
    private final int references, nodeGroups, cellGroups;
    private final int refsWord,nodeGroupsWord,nodeRefsWord,cellGroupsWord,cellRefsWord,scratchWord;
    private final long backupOffset;
    private final org.lwjgl.vulkan.VkBufferCopy.Buffer[] saveCopies=new org.lwjgl.vulkan.VkBufferCopy.Buffer[17],restoreCopies=new org.lwjgl.vulkan.VkBufferCopy.Buffer[17];
    private final FlipGpuResources resources;
    private final GpuStepScheduler scheduler;
    private final GpuBuffer mapping;
    private final FlipKernelBindings bindings;
    VulkanGasEnvironment(VulkanDeviceContext context, EnvironmentBinding binding, Map<EnvironmentThermalState.Key,Integer> nodeIndices) {
        var refs=binding.gas();references=refs.size();
        var byNode=new TreeMap<Integer,List<Integer>>();var byCell=new TreeMap<Integer,List<Integer>>();
        for(int i=0;i<refs.size();i++){
            var r=refs.get(i);Integer node=nodeIndices.get(r.node());if(node==null)throw new IllegalArgumentException("Outside gas has no environment air node");
            byNode.computeIfAbsent(node,k->new ArrayList<>()).add(i);byCell.computeIfAbsent(r.cell(),k->new ArrayList<>()).add(i);
        }
        nodeGroups=byNode.size();cellGroups=byCell.size();airNodes=byNode.keySet().stream().mapToInt(Integer::intValue).toArray();
        refsWord=4;nodeGroupsWord=refsWord+4*references;nodeRefsWord=nodeGroupsWord+3*nodeGroups;
        cellGroupsWord=nodeRefsWord+references;cellRefsWord=cellGroupsWord+3*cellGroups;scratchWord=(cellRefsWord+references+1)&~1;
        int baseWords=Math.addExact(scratchWord,Math.multiplyExact(10,references));backupOffset=4L*baseWords;
        int words=Math.addExact(baseWords,Math.multiplyExact(34,nodeGroups));
        var data=ByteBuffer.allocateDirect(Math.max(4,4*words)).order(ByteOrder.LITTLE_ENDIAN);
        data.putInt(0,references).putInt(4,nodeGroups).putInt(8,cellGroups);
        for(int i=0;i<refs.size();i++){
            var r=refs.get(i);int at=4*(refsWord+4*i);data.putInt(at,r.cell());data.putInt(at+4,r.direction());data.putInt(at+8,nodeIndices.get(r.node()));data.putFloat(at+12,(float)r.areaFraction());
        }
        putGroups(data,byNode,nodeGroupsWord,nodeRefsWord);putGroups(data,byCell,cellGroupsWord,cellRefsWord);
        resources=new FlipGpuResources(context);GpuStepScheduler madeScheduler=null;FlipKernelBindings madeBindings=null;GpuBuffer madeMapping=null;
        try{
            madeScheduler=new GpuStepScheduler(context,resources,1024*1024);madeMapping=resources.addDeviceStorage(data.capacity());
            madeBindings=new FlipKernelBindings(context,4,shader(),64);
            try(var stack=stackPush()){
                var submission=madeScheduler.acquire();var command=madeScheduler.begin(submission,"environment-gas-topology");
                for(int offset=0;offset<data.capacity();offset+=65536){int size=Math.min(65536,data.capacity()-offset);vkCmdUpdateBuffer(command,madeMapping.handle(),offset,data.slice(offset,size));}
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                madeScheduler.submitAndWait(stack,submission,"environment-gas-topology");
            }
        }catch(RuntimeException|Error failure){if(madeBindings!=null)madeBindings.close();if(madeScheduler!=null)madeScheduler.close();resources.close();throw failure;}
        scheduler=madeScheduler;mapping=madeMapping;bindings=madeBindings;
    }
    private static void putGroups(ByteBuffer data,TreeMap<Integer,List<Integer>> groups,int groupWord,int refsWord){
        int group=0,cursor=0;for(var entry:groups.entrySet()){
            int at=4*(groupWord+3*group++);data.putInt(at,entry.getKey());data.putInt(at+4,cursor);
            for(int index:entry.getValue())data.putInt(4*(refsWord+cursor++),index);
            data.putInt(at+8,cursor);
        }
    }
    void record(VkCommandBuffer command,GpuBuffer region,GpuBuffer environment,GpuBuffer errors,
                VulkanLocalGasTransport.ProductionLayout gas,VulkanEnvironmentThermal.Layout world){
        bindings.rebind(List.of(region,environment,mapping,errors));
        stage(command,0,references,gas,world);stage(command,1,nodeGroups,gas,world);stage(command,2,cellGroups,gas,world);
    }
    void recordCheckpoint(VkCommandBuffer command,GpuBuffer environment,VulkanEnvironmentThermal.Layout world,int depth,boolean restore,org.lwjgl.system.MemoryStack stack){
        if(depth<0||depth>=saveCopies.length)throw new IllegalArgumentException("Gas checkpoint depth exceeds refinement budget");
        var list=restore?restoreCopies:saveCopies;
        if(list[depth]==null){var copies=org.lwjgl.vulkan.VkBufferCopy.create(nodeGroups);for(int i=0;i<nodeGroups;i++){
            long energy=world.offset(VulkanEnvironmentThermal.Field.ENERGY)+8L*airNodes[i],saved=backupOffset+8L*(depth*nodeGroups+i);
            copies.get(i).srcOffset(restore?saved:energy).dstOffset(restore?energy:saved).size(8);
        }list[depth]=copies;}
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_ACCESS_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
        vkCmdCopyBuffer(command,restore?mapping.handle():environment.handle(),restore?environment.handle():mapping.handle(),list[depth]);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_READ_BIT,stack);
    }
    private void stage(VkCommandBuffer command,int stage,int count,VulkanLocalGasTransport.ProductionLayout gas,VulkanEnvironmentThermal.Layout world){
        if(count==0)return;
        try(var stack=stackPush()){
            bindings.bind(command,stack);
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(stage,count,gas.word(VulkanLocalGasTransport.Field.HEADER),
                    world.word(VulkanEnvironmentThermal.Field.ENERGY),world.word(VulkanEnvironmentThermal.Field.INTERCEPT),world.word(VulkanEnvironmentThermal.Field.CAPACITY),
                    world.word(VulkanEnvironmentThermal.Field.UPPER_ENERGY),world.word(VulkanEnvironmentThermal.Field.UPPER_TEMPERATURE),world.word(VulkanEnvironmentThermal.Field.SEGMENT_COUNTS),
                    world.word(VulkanEnvironmentThermal.Field.SURFACE_INTERCEPT),world.word(VulkanEnvironmentThermal.Field.SURFACE_CAPACITY),world.segments,
                    refsWord,nodeGroupsWord,nodeRefsWord,cellGroupsWord));
            int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static String shader(){return VulkanLiquidPhaseRows.resource("environment-gas.comp")
            .replace("// HOST_LAYOUT",VulkanShaderLayout.fields("F_",VulkanLocalGasTransport.Field.values())+VulkanShaderLayout.fields("G_",VulkanGasStateLayout.Field.values())+VulkanGasThermodynamics.shaderLayout())
            .replace("// GAS_CALORIC_MATERIAL",VulkanLiquidPhaseRows.resource("gas-caloric-material.glsl"));}
    @Override public void close(){try{bindings.close();}finally{try{scheduler.close();}finally{resources.close();}}}
}
