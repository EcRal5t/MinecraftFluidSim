package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import mmm.thermal.GasLiquidPhaseModel;
import mmm.thermal.ThermalPhaseTransition;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Finite-chamber thermal phase batches, with a single liquid proposal per cell. */
final class VulkanGasPhaseChange implements AutoCloseable {
    enum Field { HEADER(4),RULES(4),CONTROL(4),HEAD(4),NEXT(4),RULE(4),NODE(4),BOIL(4),LIMIT(8),DESIRED(4),OFFSETS(4),BATCHES(4),
        SCORE(8),SATURATION(8),PARTIAL(8),RESPONSE(8),GAS_RESPONSE(8),KG(8),LOWER(8),UPPER(8),TEMP(8),GAS_TEMP(8),
        OLD_MASS(8),NEW_MASS(8),OLD_GAS_U(8),NEW_GAS_U(8),GAS_TRANSFER(8),OLD_K(8),NEW_K(8),FACE_HEAT(8),FACE_P(8),PE_WORK(8),BIRTH_NODE(4),SCAN(4);
        final int width;Field(int width){this.width=width;}}
    record Inputs(VulkanGasThermodynamics.Layout gas,VulkanGasGeometry.Layout geometry,VulkanLiquidThermodynamics.Layout liquid,double gravity){}
    static final class Layout {
        final VulkanLiquidPhaseRows.Layout rows;final Inputs inputs;final VulkanReactionProgressLayout progress;
        final int rules,groups,cells,faces;final ByteBuffer ruleBytes;
        final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);final long endOffset;
        Layout(VulkanRegionReactions.Configuration config,VulkanLiquidPhaseRows.Layout rows,Inputs inputs,VulkanReactionProgressLayout progress,long base,long alignment){
            this.rows=rows;this.inputs=inputs;this.progress=progress;cells=rows.source.cellCount();
            faces=rows.source.column(VulkanLiquidStateLayout.Field.MOMENTUM_X).count()+rows.source.column(VulkanLiquidStateLayout.Field.MOMENTUM_Y).count()+rows.source.column(VulkanLiquidStateLayout.Field.MOMENTUM_Z).count();
            var selected=config.thermal().transitions().stream().filter(t->t.interfaceRequirement()==ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS).toList();rules=selected.size();
            ruleBytes=ByteBuffer.allocateDirect(48*rules).order(ByteOrder.LITTLE_ENDIAN);var model=new GasLiquidPhaseModel(config.thermal(),config.gas());
            for(var t:selected){var liquid=config.thermal().material(config.thermal().materialHandle(t.lowerEnergyMaterialId()));var vapor=config.thermal().material(config.thermal().materialHandle(t.upperEnergyMaterialId()));
                var batch=model.gasBatch(t,1).components().getFirst();double ref=t.equilibriumTemperatureK(101325);
                int component=inputs.gas().gas().definitions().componentIds().indexOf(vapor.carrierId());
                if(component<0)throw new IllegalArgumentException("Phase vapor component is missing: "+vapor.carrierId());
                Math.multiplyExact(batch.massUnits(),mmm.thermal.ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP);
                Math.multiplyExact(batch.standardVolumeNanos(),mmm.thermal.ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP);
                Math.multiplyExact(t.lowerVolumeSvqPerBatch(),mmm.thermal.ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP);
                ruleBytes.putInt(config.fluids().handle(liquid.carrierId())).putInt(component)
                        .putLong(batch.massUnits()).putLong(t.lowerVolumeSvqPerBatch()).putLong(batch.standardVolumeNanos()).putDouble(ref)
                        .putDouble(model.saturationSlopePaPerKelvin(t,ref,101325)*ref*ref/101325);
            }ruleBytes.flip();groups=Math.multiplyExact(inputs.gas().gas().nodeCapacity(),inputs.gas().gas().definitions().componentIds().size());
            long cursor=base;for(var f:Field.values()){int count=switch(f){case HEADER->128;case RULES->12*rules;case CONTROL->8;case HEAD->groups;
                case OFFSETS->cells+1;case OLD_GAS_U,NEW_GAS_U->inputs.gas().gas().nodeCapacity();case OLD_K,NEW_K,FACE_P->faces;case FACE_HEAT->2*faces;
                case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(cells)/4);default->cells;};
                var c=new VulkanLiquidStateLayout.Column(align(cursor,alignment),count,f.width);columns.put(f,c);cursor=c.offset()+c.bindingBytes();}endOffset=align(cursor,alignment);
        }
        VulkanLiquidStateLayout.Column column(Field f){return columns.get(f);}int word(Field f){return Math.toIntExact(column(f).offset()/4);}
        long feedbackOffset(){return column(Field.CONTROL).offset();}
        ByteBuffer headerBytes(){var b=ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);int[] h=new int[128];
            h[0]=cells;h[1]=rules;h[2]=rows.headerWord();h[3]=inputs.gas().headerWordOffset();h[4]=Math.toIntExact(inputs.liquid().header().offset()/4);
            h[5]=inputs.geometry().headerWordOffset();h[6]=progress.word(VulkanReactionProgressLayout.Field.PHASE_MOTION_CELLS);h[7]=progress.word(VulkanReactionProgressLayout.Field.PHASE_PENDING_SUMMARY);
            h[8]=groups;h[9]=faces;h[10]=rows.source.materialCount();h[11]=rows.species;h[12]=progress.word(VulkanReactionProgressLayout.Field.CONTROL);
            for(var f:Field.values())h[32+f.ordinal()]=word(f);for(int i=0;i<h.length;i++)b.putInt(4*i,h[i]);b.putDouble(4*16,inputs.geometry().cellSize()).putDouble(4*18,inputs.gravity());return b;
        }
        List<VulkanGasThermodynamics.TableChunk> headers(){return List.of(new VulkanGasThermodynamics.TableChunk(column(Field.HEADER).offset(),headerBytes()),new VulkanGasThermodynamics.TableChunk(rows.column(VulkanLiquidPhaseRows.Field.HEADER).offset(),rows.headerBytes()));}
        List<VulkanGasThermodynamics.TableChunk> tables(){return List.of(new VulkanGasThermodynamics.TableChunk(column(Field.RULES).offset(),ruleBytes.duplicate()));}
        List<VulkanEnergyLedger.Stream> roundoffStreams(){var result=new ArrayList<>(rows.energyChangeStreams());result.add(stream(Field.NEW_GAS_U,1));result.add(stream(Field.OLD_GAS_U,-1));result.add(stream(Field.NEW_K,1));result.add(stream(Field.OLD_K,-1));result.add(stream(Field.PE_WORK,-1));return List.copyOf(result);}
        List<VulkanEnergyLedger.Stream> mechanicalWorkStreams(){return List.of(stream(Field.OLD_K,1),stream(Field.NEW_K,-1),stream(Field.PE_WORK,1));}
        private VulkanEnergyLedger.Stream stream(Field f,int sign){return new VulkanEnergyLedger.Stream(1,word(f),column(f).count(),2,sign);}
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;private final VulkanLiquidPhaseRows rows;
    VulkanGasPhaseChange(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,3,VulkanLiquidPhaseRows.resource("gas-phase-change.comp").replace("// PHASE_ROWS_LIBRARY",VulkanLiquidPhaseRows.resource("liquid-phase-rows.glsl")),16);
        VulkanPrefixScan s=null;try{s=new VulkanPrefixScan(context);rows=new VulkanLiquidPhaseRows(context);scan=s;}catch(RuntimeException|Error e){if(s!=null)s.close();bindings.close();throw e;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors,arena));scan.rebindRanges(arena,arena,arena,errors);rows.rebindRanges(arena,errors);}
    private int completedOperations;
    int completedOperations(){return completedOperations;}
    int execute(VulkanRegionStorage.Candidate candidate,Layout l,int maximum,VulkanGasThermodynamics.Layout eos,VulkanGasGeometry.Layout geometry){
        var result=candidate.continueBatch((command,input,output,errors,staging,stack)->{
            rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
            var header=l.headerBytes();header.putInt(12,eos.headerWordOffset()).putInt(20,geometry.headerWordOffset());
            vkCmdUpdateBuffer(command,output.handle(),l.column(Field.HEADER).offset(),header);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,stack);
            recordPrepare(command,l,maximum);
        },l.rows.feedbackOffset(),16);
        if(result.error()!=0)return result.error();
        if(result.feedback().getInt(0)==1)throw new VulkanRegionReactions.CapacityGrowth(result.feedback().getInt(8),result.feedback().getInt(12),0,0,0);
        if(result.feedback().getInt(0)!=2)throw new IllegalStateException("Gas phase liquid successor is incomplete");
        var published=candidate.continueBatch((command,input,output,errors,staging,stack)->recordPublish(command,l,maximum),l.feedbackOffset(),12);
        if(published.error()==0)completedOperations=published.feedback().getInt(0);return published.error();
    }
    void recordPrepare(VkCommandBuffer command,Layout l,int maximum){
        rows.recordImport(command,l.rows);stage(command,l,0,Math.max(l.groups,Math.max(l.cells,l.inputs.gas().gas().nodeCapacity())),maximum);
        stage(command,l,1,l.cells,maximum);stage(command,l,2,l.groups,maximum);
        scan.record(command,l.cells,l.word(Field.DESIRED),l.word(Field.OFFSETS),l.word(Field.SCAN));stage(command,l,3,l.cells,maximum);
        stage(command,l,4,l.inputs.gas().gas().nodeCapacity(),maximum);stage(command,l,5,l.faces,maximum);stage(command,l,6,l.cells,maximum);
        stage(command,l,7,l.inputs.gas().gas().nodeCapacity(),maximum);rows.recordAssemble(command,l.rows);
    }
    void recordPublish(VkCommandBuffer command,Layout l,int maximum){rows.recordPublish(command,l.rows);stage(command,l,8,Math.max(l.faces,l.cells),maximum);}
    private void stage(VkCommandBuffer command,Layout l,int stage,int n,int maximum){if(n==0)return;try(var stack=stackPush()){
        bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(l.word(Field.HEADER),stage,n,maximum));int groups=(n+127)/128;
        vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private static long align(long x,long a){return Math.addExact(x,a-1)&-a;}
    @Override public void close(){try{rows.close();}finally{try{scan.close();}finally{bindings.close();}}}
}
