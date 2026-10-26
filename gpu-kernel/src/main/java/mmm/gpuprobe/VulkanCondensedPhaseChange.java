package mmm.gpuprobe;

import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.FluidMaterialRegistry;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalLimits;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Cell-local integer freezing/melting with a stationary solid and one conserved pair energy. */
final class VulkanCondensedPhaseChange implements AutoCloseable {
    record Inputs(VulkanLiquidStateLayout liquid,VulkanCoherentStateLayout coherent,
                  VulkanLiquidThermodynamics.Layout liquidCaloric,VulkanLiquidThermodynamics.Layout coherentCaloric,
                  VulkanReactionDefinitions.Layout definitions,VulkanReactionProgressLayout progress,
                  int pressureWord,int fixedMasksWord,int occupiedAllSvqWord,double cellSize,double gravity) { }
    static final class CapacityGrowth extends IllegalStateException {
        final int liquidSpecies,liquidLimbs,coherentGroups,coherentSpecies;
        CapacityGrowth(int a,int b,int c,int d){liquidSpecies=a;liquidLimbs=b;coherentGroups=c;coherentSpecies=d;}
    }
    enum Field {
        HEADER(4),RULES(4),CARRIERS(4),SVQ(8),MASS(8),ENERGY(8),SPECIES(8),MIXTURE(4),LIVE(4),
        GROUP_OFFSETS(4),SPECIES_COUNTS(4),SPECIES_OFFSETS(4),SPECIES_HANDLES(4),SPECIES_AMOUNTS(8),MASS_RESIDUAL(8),
        OLD_ENERGY(8),NEW_ENERGY(8),CELL_OLD_MASS(8),CELL_NEW_MASS(8),CELL_REMOVED_MASS(8),CELL_BASE_OCCUPIED(8),
        CELL_CHANGED(4),PAIR_LIQUID(4),PAIR_COHERENT(4),PAIR_ENERGY(8),FACE_MOMENTUM(8),FACE_HEAT(8),FACE_OLD_K(8),FACE_NEW_K(8),SCAN(4),CONTROL(4);
        final int width;Field(int width){this.width=width;}
    }
    static final class Layout {
        final Inputs input;final VulkanLiquidPhaseRows.Layout phaseRows;
        final int coherentMaterials,rows,species,faces;final int[] rules,carriers;
        final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);final long endOffset;
        Layout(Inputs input,FluidMaterialRegistry fluids,ChemicalRegistry chemistry,ThermalRegistry thermal,long base,long alignment){
            this.input=input;var liquid=input.liquid();var coherent=input.coherent();
            if(input.cellSize()<=0||!Double.isFinite(input.cellSize())||input.gravity()<0||!Double.isFinite(input.gravity())
                    ||input.fixedMasksWord()<0||input.occupiedAllSvqWord()< -1||input.pressureWord()< -1
                    ||liquid.cellCount()!=coherent.cellCount()||alignment<8||(alignment&(alignment-1))!=0)
                throw new IllegalArgumentException("Invalid condensed phase inputs");
            species=input.definitions().speciesCount();coherentMaterials=coherent.definitions().phaseIds().size();rows=Math.multiplyExact(liquid.cellCount(),coherentMaterials);
            if(!coherent.definitions().speciesIds().equals(input.definitions().identity().species()))throw new IllegalArgumentException("Condensed species handles differ");
            faces=liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_X).count()+liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_Y).count()+liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_Z).count();
            var ruleList=new ArrayList<Integer>();var data=input.definitions().bytes().order(ByteOrder.LITTLE_ENDIAN);
            int directory=data.getInt(20),count=data.getInt(16);
            for(int i=0;i<count;i++){
                int entry=directory+2*i;if(data.getInt(4*entry)!=VulkanReactionDefinitions.Kind.THERMAL_PHASE.ordinal())continue;
                int payload=data.getInt(4*(entry+1));if(data.getInt(4*(payload+2))!=0)continue;
                var lower=thermal.material(data.getInt(4*payload));var upper=thermal.material(data.getInt(4*(payload+1)));
                if(lower.carrierKind()!=ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID||upper.carrierKind()!=ThermalMaterialDefinition.CarrierKind.LIQUID)continue;
                int solid=coherent.definitions().phaseIds().indexOf(lower.id()),material=-1;
                for(int m=0;m<fluids.size();m++)if(fluids.material(m).id().equals(upper.carrierId()))material=m;
                if(solid<0||material<0)throw new IllegalArgumentException("Thermal transition carrier is absent from device inventory");
                ruleList.add(solid);ruleList.add(material);ruleList.add(payload);
            }
            rules=ruleList.stream().mapToInt(Integer::intValue).toArray();carriers=new int[Math.multiplyExact(liquid.materialCount(),species)];
            for(int m=0;m<fluids.size();m++)for(var phase:chemistry.phases())if(phase.materialId().equals(fluids.material(m).id()))
                for(String id:phase.defaultConcentrationMolPerCubicMeter().keySet())carriers[m*species+chemistry.speciesHandle(id)]=1;
            phaseRows=new VulkanLiquidPhaseRows.Layout(liquid,input.definitions(),input.cellSize(),base,alignment);
            long cursor=phaseRows.endOffset;
            for(var field:Field.values()){
                int n=switch(field){
                    case HEADER->160;case RULES->rules.length;case CARRIERS->carriers.length;
                    case SPECIES->Math.multiplyExact(rows,species);case GROUP_OFFSETS,SPECIES_OFFSETS->rows+1;
                    case SPECIES_HANDLES,SPECIES_AMOUNTS->coherent.speciesCapacity();case OLD_ENERGY->coherent.groupCapacity();
                    case CELL_OLD_MASS,CELL_NEW_MASS,CELL_REMOVED_MASS,CELL_BASE_OCCUPIED,CELL_CHANGED,PAIR_LIQUID,PAIR_COHERENT,PAIR_ENERGY->liquid.cellCount();
                    case FACE_MOMENTUM,FACE_OLD_K,FACE_NEW_K->faces;case FACE_HEAT->2*faces;
                    case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(Math.max(1,rows))/4);case CONTROL->10;default->rows;};
                var column=new VulkanLiquidStateLayout.Column(align(cursor,alignment),n,field.width);columns.put(field,column);cursor=column.offset()+column.bindingBytes();
            }
            endOffset=align(cursor,alignment);Math.toIntExact(endOffset/4);
        }
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}int word(Field field){return Math.toIntExact(column(field).offset()/4);}
        long feedbackOffset(){return column(Field.CONTROL).offset();}long endOffset(){return endOffset;}boolean enabled(){return rules.length>0;}
        ByteBuffer headerBytes(){var result=ByteBuffer.allocateDirect(640).order(ByteOrder.LITTLE_ENDIAN);int[] h=new int[160];
            h[0]=input.liquid().cellCount();h[1]=input.liquid().materialCount();h[2]=coherentMaterials;h[3]=species;h[4]=rules.length/3;h[5]=input.coherent().groupCapacity();h[6]=input.coherent().speciesCapacity();h[7]=phaseRows.headerWord();
            for(var field:Field.values())h[8+field.ordinal()]=word(field);
            for(var field:VulkanCoherentStateLayout.Field.values())h[64+field.ordinal()]=input.coherent().word(field);
            for(var field:VulkanLiquidStateLayout.Field.values())h[80+field.ordinal()]=Math.toIntExact(input.liquid().column(field).offset()/4);
            h[100]=input.definitions().word();h[101]=Math.toIntExact(input.liquidCaloric().header().offset()/4);h[102]=Math.toIntExact(input.coherentCaloric().header().offset()/4);
            h[103]=input.pressureWord();h[104]=input.fixedMasksWord();h[105]=input.occupiedAllSvqWord();h[106]=input.progress().word(VulkanReactionProgressLayout.Field.PHASE_MOTION_CELLS);h[107]=input.progress().word(VulkanReactionProgressLayout.Field.PHASE_PENDING_SUMMARY);h[108]=input.progress().word(VulkanReactionProgressLayout.Field.CURSOR);
            h[109]=input.liquid().sizeX();h[110]=input.liquid().sizeY();h[111]=input.liquid().sizeZ();h[119]=rows;h[120]=faces;
            for(int i=0;i<h.length;i++)result.putInt(4*i,h[i]);result.putDouble(448,input.cellSize()).putDouble(456,input.gravity());return result;
        }
        List<VulkanGasThermodynamics.TableChunk> headers(){return List.of(new VulkanGasThermodynamics.TableChunk(column(Field.HEADER).offset(),headerBytes()),new VulkanGasThermodynamics.TableChunk(phaseRows.column(VulkanLiquidPhaseRows.Field.HEADER).offset(),phaseRows.headerBytes()));}
        List<VulkanGasThermodynamics.TableChunk> tableChunks(){return List.of(intTable(Field.RULES,rules),intTable(Field.CARRIERS,carriers));}
        private VulkanGasThermodynamics.TableChunk intTable(Field field,int[] data){var b=ByteBuffer.allocateDirect(4*data.length).order(ByteOrder.LITTLE_ENDIAN);b.asIntBuffer().put(data);return new VulkanGasThermodynamics.TableChunk(column(field).offset(),b);}
        List<VulkanEnergyLedger.Stream> roundoffStreams(){var result=new ArrayList<>(phaseRows.energyChangeStreams());result.add(stream(Field.NEW_ENERGY,1));result.add(stream(Field.OLD_ENERGY,-1));result.add(stream(Field.FACE_NEW_K,1));result.add(stream(Field.FACE_OLD_K,-1));return List.copyOf(result);}
        List<VulkanEnergyLedger.Stream> mechanicalWorkStreams(){return List.of(stream(Field.FACE_OLD_K,1),stream(Field.FACE_NEW_K,-1));}
        private VulkanEnergyLedger.Stream stream(Field field,int sign){return new VulkanEnergyLedger.Stream(1,word(field),column(field).count(),2,sign);}
    }
    private final FlipKernelBindings bindings;private final VulkanLiquidPhaseRows liquidRows;private final VulkanPrefixScan scan;private int completedOperations;
    VulkanCondensedPhaseChange(VulkanDeviceContext context){
        bindings=new FlipKernelBindings(context,2,VulkanLiquidPhaseRows.resource("condensed-phase-change.comp").replace("// PHASE_ROWS_LIBRARY",VulkanLiquidPhaseRows.resource("liquid-phase-rows.glsl")).replace("// DYADIC_LIBRARY",VulkanLiquidPhaseRows.resource("liquid-dyadic.glsl")),16);
        liquidRows=new VulkanLiquidPhaseRows(context);scan=new VulkanPrefixScan(context);
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors));liquidRows.rebindRanges(arena,errors);scan.rebindRanges(arena,arena,arena,errors);}
    int completedOperations(){return completedOperations;}
    void recordPrepare(VkCommandBuffer command,Layout l,int maximum){
        liquidRows.recordImport(command,l.phaseRows);stage(command,l,0,Math.max(l.faces,Math.max(l.rows,l.input.coherent().groupCapacity())),maximum);
        stage(command,l,1,l.input.coherent().groupCapacity(),maximum);stage(command,l,2,l.input.liquid().cellCount(),maximum);stage(command,l,3,l.input.liquid().cellCount(),maximum);
        stage(command,l,4,l.faces,maximum);stage(command,l,5,l.input.liquid().cellCount(),maximum);stage(command,l,6,l.rows,maximum);
        scan.record(command,l.rows,l.word(Field.LIVE),l.word(Field.GROUP_OFFSETS),l.word(Field.SCAN));scan.record(command,l.rows,l.word(Field.SPECIES_COUNTS),l.word(Field.SPECIES_OFFSETS),l.word(Field.SCAN));
        liquidRows.recordAssemble(command,l.phaseRows);stage(command,l,7,1,maximum);stage(command,l,8,l.rows,maximum);
    }
    void recordPublish(VkCommandBuffer command,Layout l){liquidRows.recordPublish(command,l.phaseRows);stage(command,l,9,Math.max(l.input.coherent().speciesCapacity(),Math.max(l.faces,Math.max(l.rows,l.input.liquid().cellCount()))),0);}
    int execute(VulkanRegionStorage.Candidate candidate,Layout l,int maximum){
        if(maximum<0||maximum>ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP)throw new IllegalArgumentException("Invalid phase operation budget");
        var result=candidate.continueBatch((command,input,output,errors,staging,stack)->{rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));recordPrepare(command,l,maximum);},l.feedbackOffset(),36);
        if(result.error()!=0)return result.error();var feedback=result.feedback().order(ByteOrder.LITTLE_ENDIAN);
        if(feedback.getInt(0)==1)throw new CapacityGrowth(feedback.getInt(4),feedback.getInt(8),feedback.getInt(12),feedback.getInt(16));
        if(feedback.getInt(0)!=2)throw new IllegalStateException("Condensed phase successor is incomplete");completedOperations=feedback.getInt(32);
        return candidate.continueBatch((command,input,output,errors,staging,stack)->recordPublish(command,l),0,0).error();
    }
    private void stage(VkCommandBuffer command,Layout l,int stage,int count,int maximum){if(count==0)return;try(var stack=stackPush()){
        bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(l.word(Field.HEADER),stage,count,maximum));int groups=(count+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private static long align(long value,long alignment){return Math.addExact(value,alignment-1)&-alignment;}
    @Override public void close(){try{scan.close();}finally{try{liquidRows.close();}finally{bindings.close();}}}
}
