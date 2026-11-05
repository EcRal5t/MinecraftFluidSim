package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import mmm.thermal.MixtureVolumeModel;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Per-cell liquid/gas volume EOS derived from the same generation of inventories. */
final class VulkanMixtureState implements AutoCloseable {
    enum Quantity { PRESSURE, LIQUID_VOLUME, GAS_VOLUME, LIQUID_SCALE, LIQUID_DENSITY, PRESSURE_STORAGE, ELASTIC_ENERGY }
    /** Addresses are absolute arena words; strides count doubles. Liquid and pore volumes are normalized by h³, mass is kg. */
    record Inputs(int cells,int referenceWord,int referenceStride,int massWord,int massStride,
                  int poreWord,int poreStride,VulkanGasThermodynamics.Layout gas,double cellSize,double duration,int geometryCellsWord) {
        Inputs(int cells,int referenceWord,int referenceStride,int massWord,int massStride,int poreWord,int poreStride,VulkanGasThermodynamics.Layout gas,double cellSize,double duration){
            this(cells,referenceWord,referenceStride,massWord,massStride,poreWord,poreStride,gas,cellSize,duration,-1);
        }
        Inputs {
            if(cells<1||referenceWord<0||massWord<0||poreWord<0||referenceStride<1||massStride<1||poreStride<1
                    ||(gas!=null&&gas.gas().nodeCapacity()<cells)||!(Double.isFinite(cellSize)&&cellSize>0)
                    ||!(Double.isFinite(duration)&&duration>0))throw new IllegalArgumentException("Invalid mixture inputs");
        }
    }
    record Layout(Inputs inputs,VulkanLiquidStateLayout.Column header,VulkanLiquidStateLayout.Column states,long endOffset) {
        int headerWord(){return Math.toIntExact(header.offset()/4);}
        int statesWord(){return Math.toIntExact(states.offset()/4);}
        long stateOffset(int cell,Quantity q){if(cell<0||cell>=inputs.cells())throw new IndexOutOfBoundsException(cell);return states.offset()+8L*(cell*Quantity.values().length+q.ordinal());}
        ByteBuffer headerBytes(){
            int[] data=new int[32];data[0]=inputs.cells();data[1]=inputs.referenceWord();data[2]=inputs.referenceStride();data[3]=inputs.massWord();data[4]=inputs.massStride();
            data[5]=inputs.poreWord();data[6]=inputs.poreStride();data[7]=inputs.gas()==null?-1:Math.toIntExact(inputs.gas().states().offset()/4);data[8]=statesWord();
            data[9]=inputs.geometryCellsWord();data[10]=inputs.gas()==null?-1:inputs.gas().nodeVolumesWordOffset();
            var b=ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<data.length;i++)b.putInt(4*i,data[i]);
            b.putDouble(4*12,inputs.cellSize());b.putDouble(4*14,inputs.duration());b.putDouble(4*16,MixtureVolumeModel.LIQUID_BULK_PA);b.putDouble(4*18,MixtureVolumeModel.REFERENCE_PRESSURE_PA);return b;
        }
        void initializeHeader(ByteBuffer output){output.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());}
    }
    static Layout layout(Inputs inputs,long base,long alignment){
        if((inputs.gas()!=null&&base<inputs.gas().endOffset())||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Invalid mixture layout");
        long h=(base+alignment-1)&-alignment;var header=new VulkanLiquidStateLayout.Column(h,32,4);
        long s=(h+header.bindingBytes()+alignment-1)&-alignment;var states=new VulkanLiquidStateLayout.Column(s,Math.multiplyExact(inputs.cells(),Quantity.values().length),8);
        return new Layout(inputs,header,states,(s+states.bindingBytes()+alignment-1)&-alignment);
    }
    private final FlipKernelBindings bindings;
    VulkanMixtureState(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,
            VulkanLiquidPhaseRows.resource("mixture-state.comp").replace("// HOST_LAYOUT",VulkanShaderLayout.fields("Q_",Quantity.values())),28);}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors));}
    void record(VkCommandBuffer command,Layout layout){record(command,layout,layout.inputs().duration());}
    void record(VkCommandBuffer command,Layout layout,double duration){record(command,layout,layout.inputs().cellSize(),duration);}
    void record(VkCommandBuffer command,Layout layout,double cellSize,double duration){
        if(!(Double.isFinite(duration)&&duration>0&&Double.isFinite(cellSize)&&cellSize>0))throw new IllegalArgumentException("Invalid mixture cell size or duration");
        long bits=Double.doubleToRawLongBits(duration),sizeBits=Double.doubleToRawLongBits(cellSize);
        try(var stack=stackPush()){
            bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,
                    stack.ints(layout.headerWord(),layout.inputs().cells(),0,(int)bits,(int)(bits>>>32),(int)sizeBits,(int)(sizeBits>>>32)));
            int groups=(layout.inputs().cells()+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    @Override public void close(){bindings.close();}
}
