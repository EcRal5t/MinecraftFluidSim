package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Paired contact impulses with a component-local rebound energy budget and fixed-wall Coulomb traction.
 * The caller supplies contacts generated from the trial geometry. This stage updates candidate
 * velocities and FINAL_K; position/support publication and FINAL_Y follow joint volume acceptance.
 * Dissipation is settled by the shared mechanical energy ledger using source/final kinetic energy.
 */
final class VulkanGranularContacts implements AutoCloseable {
    enum Field {
        CONTROL(4),FIRST(4),SECOND(4),NORMAL(8),WALL_VELOCITY(8),RESTITUTION(8),STATIC_FRICTION(8),DYNAMIC_FRICTION(8),
        CONTACT_COMPONENT(4),COUNTS(4),OFFSETS(4),CURSORS(4),ENTRIES(4),SOURCE_VELOCITY(8),VELOCITY(8),COMPRESSED_VELOCITY(8),
        NORMAL_IMPULSE(8),TANGENT_IMPULSE(8),COMPRESSED_IMPULSE(8),COMPONENT_COUNTS(4),COMPONENT_OFFSETS(4),COMPONENT_CURSORS(4),
        COMPONENT_MEMBERS(4),ENERGY_TERMS(8),REBOUND_SCALE(8),FIXED_REACTION(8),FIXED_WORK(8),SCAN(4),PARTICLE_EXTERNAL_WORK(8),
        INTERVAL_COMPONENT(4),INTERVAL_PARTICLE_WORK(8),INTERVAL_FIXED_WORK(8);
        final int width;Field(int width){this.width=width;}
    }
    static final class Layout {
        private final VulkanGranularStateLayout state;
        private final VulkanLiquidStateLayout.Column finalK;
        private final int contacts;private final long endOffset;
        private final VulkanLiquidStateLayout.Column header;
        private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        Layout(VulkanGranularStateLayout state,VulkanGranularPressure.Layout pressure,int contactCapacity,long scratchBase,long alignment){
            this(state,pressure.column(VulkanGranularPressure.Field.FINAL_K),contactCapacity,scratchBase,alignment);
            if(scratchBase<pressure.endOffset())throw new IllegalArgumentException("Contact scratch overlaps pressure");
        }
        Layout(VulkanGranularStateLayout state,VulkanLiquidStateLayout.Column finalK,int contactCapacity,long scratchBase,long alignment){
            if(contactCapacity<0||scratchBase<state.endOffset()||scratchBase<finalK.offset()+finalK.bytes()
                    ||finalK.count()!=state.particleCapacity()||finalK.elementBytes()!=8||alignment<8||(alignment&(alignment-1))!=0)
                throw new IllegalArgumentException("Invalid granular contact layout");
            this.state=state;this.finalK=finalK;contacts=contactCapacity;
            header=new VulkanLiquidStateLayout.Column(align(scratchBase,alignment),64,4);long cursor=header.offset()+header.bytes();
            for(Field field:Field.values()){
                int count=switch(field){
                    case CONTROL->4;
                    case FIRST,SECOND,RESTITUTION,STATIC_FRICTION,DYNAMIC_FRICTION,NORMAL_IMPULSE,COMPRESSED_IMPULSE,FIXED_WORK,INTERVAL_FIXED_WORK->contacts;
                    case NORMAL,WALL_VELOCITY,TANGENT_IMPULSE,FIXED_REACTION->Math.multiplyExact(3,contacts);
                    case SOURCE_VELOCITY,VELOCITY,COMPRESSED_VELOCITY,ENERGY_TERMS->Math.multiplyExact(3,state.particleCapacity());
                    case OFFSETS,COMPONENT_OFFSETS->state.particleCapacity()+1;
                    case ENTRIES->Math.multiplyExact(2,contacts);
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(state.particleCapacity())/4);
                    default->state.particleCapacity();
                };
                var column=new VulkanLiquidStateLayout.Column(align(cursor,alignment),count,field.width);columns.put(field,column);cursor=column.offset()+column.bindingBytes();
            }endOffset=align(cursor,alignment);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
        VulkanLiquidStateLayout.Column header(){return header;}
        int word(Field field){return Math.toIntExact(column(field).offset()/4);}int headerWordOffset(){return Math.toIntExact(header.offset()/4);}
        int contactCapacity(){return contacts;}long endOffset(){return endOffset;}
        List<VulkanEnergyLedger.Stream> externalWorkRoundoffStreams(){
            return List.of(new VulkanEnergyLedger.Stream(1,word(Field.INTERVAL_PARTICLE_WORK),state.particleCapacity(),2,1),
                    new VulkanEnergyLedger.Stream(1,word(Field.INTERVAL_FIXED_WORK),contacts,2,-1));
        }
        ByteBuffer headerBytes(){
            var result=ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
            result.putInt(0,state.particleCapacity()).putInt(4,contacts).putInt(8,state.word(VulkanGranularStateLayout.Field.COUNTS))
                    .putInt(12,state.word(VulkanGranularStateLayout.Field.VELOCITY)).putInt(16,state.word(VulkanGranularStateLayout.Field.MASS_UNITS))
                    .putInt(20,Math.toIntExact(finalK.offset()/4));
            for(var field:Field.values())result.putInt(4*(16+field.ordinal()),word(field));return result;
        }
        void initializeHeader(ByteBuffer arena){arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());}
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;
    VulkanGranularContacts(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),16);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){
        bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);}
    /** CONTROL[0] is the device-generated actual contact count. Normals are unit vectors:
     * fixed contact n.v(first), particle pair n.(v(second)-v(first)). */
    void recordPrepare(VkCommandBuffer command,Layout l){
        stage(command,l,0,l.state.particleCapacity(),0);stage(command,l,1,l.contacts,0);
        scan.record(command,l.state.particleCapacity(),l.word(Field.COUNTS),l.word(Field.OFFSETS),l.word(Field.SCAN));
        stage(command,l,2,l.contacts,0);stage(command,l,3,l.state.particleCapacity(),0);
        scan.record(command,l.state.particleCapacity(),l.word(Field.COMPONENT_COUNTS),l.word(Field.COMPONENT_OFFSETS),l.word(Field.SCAN));
        stage(command,l,4,l.state.particleCapacity(),0);
    }
    /** Bounded Jacobi contact relaxation has no cross-contact velocity writes. */
    void recordCompression(VkCommandBuffer command,Layout l,int sweeps){
        if(sweeps<0)throw new IllegalArgumentException("Negative contact sweep count");
        for(int i=0;i<sweeps;i++){stage(command,l,5,l.contacts,0);stage(command,l,6,l.state.particleCapacity(),0);}
    }
    void recordRebound(VkCommandBuffer command,Layout l,int sweeps){
        if(sweeps<0)throw new IllegalArgumentException("Negative contact sweep count");
        stage(command,l,7,Math.max(l.contacts,l.state.particleCapacity()),0);
        for(int i=0;i<sweeps;i++){stage(command,l,5,l.contacts,1);stage(command,l,6,l.state.particleCapacity(),0);}
        stage(command,l,8,l.state.particleCapacity(),0);stage(command,l,9,l.state.particleCapacity(),0);
        stage(command,l,10,l.contacts,0);stage(command,l,6,l.state.particleCapacity(),1);
    }
    /** Initialize once before the first motion segment of a mechanical interval. */
    void recordBeginInterval(VkCommandBuffer command,Layout l){
        stage(command,l,11,Math.max(l.state.particleCapacity(),l.contacts),0);
    }
    /** A rejected trial has not published positions; restore its pre-contact velocity and kinetic energy. */
    void recordRestoreTrial(VkCommandBuffer command,Layout l){stage(command,l,12,l.state.particleCapacity(),0);}
    /** Accumulate only accepted segments; the final component array links every contact in the interval. */
    void recordAcceptTrial(VkCommandBuffer command,Layout l){
        stage(command,l,13,Math.max(l.state.particleCapacity(),l.contacts),0);
    }
    void recordFinishInterval(VkCommandBuffer command,Layout l){stage(command,l,14,l.state.particleCapacity(),0);}
    private void stage(VkCommandBuffer command,Layout l,int stage,int count,int argument){
        if(count==0)return;try(var stack=stackPush()){
            bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,
                    stack.ints(l.headerWordOffset(),stage,count,argument));int groups=(count+127)/128;
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static long align(long v,long a){return Math.addExact(v,a-1)&-a;}
    private static String shader(){try(var input=VulkanGranularContacts.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/granular-contacts.comp")){
        if(input==null)throw new IllegalStateException("Granular contacts shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8);
    }catch(IOException failure){throw new IllegalStateException("Granular contacts shader unavailable",failure);}}
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}
