package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import mmm.chemistry.AqueousDiffusion;
import mmm.units.Mass;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Conservative same-phase diffusion, with one cell pair per parity batch. */
final class VulkanLiquidDiffusion implements AutoCloseable {
    private enum Header {
        CELLS(0),RULES(1),ROWS(2),GEOMETRY(3),CALORIC(4),PENDING_COUNT(5),PENDING_CELL(6),
        PENDING_MATERIAL(7),PENDING_SUPPORT(8),PENDING_RULE(9),PENDING_SECONDS(10),PENDING_RESIDUAL(11),
        PENDING_CAPACITY(12),SPECIES(13),MATERIALS(14),FACES(15),CELL_SIZE(16),DURATION(18),GRAVITY(20),
        SIZE_X(22),SIZE_Y(23),SIZE_Z(24),WATER(25),ACTIVE_CELLS(26),ACTIVE_FACES(27);
        final int word; Header(int word){this.word=word;}
    }
    private static final int WORK_FIELDS=32;
    private static String shaderLayout(){
        var b=new StringBuilder(VulkanLiquidThermodynamics.shaderLayout()).append(VulkanLiquidThermodynamics.shaderStateFunction());
        for(var h:Header.values())b.append("#define H_").append(h.name()).append(' ').append(h.word).append('\n');
        return b.append("#define WORK_FIELDS ").append(WORK_FIELDS).append('\n').append(VulkanShaderLayout.fields("FIELD_",Field.values())).toString();
    }
    enum Field { HEADER(4), RULES(4), PENDING_INDEX(4), MASS_CHANGE(8), MOMENTUM_CHANGE(8), OLD_K(8), NEW_K(8), FACE_P(8), FACE_HEAT(8), PE_CHANGE(8), EMPTY_TARGET(4), HEAT_DEPOSIT(8);
        final int width;Field(int width){this.width=width;}}
    static final class Layout {
        final VulkanLiquidPhaseRows.Layout rows;
        final VulkanLiquidThermodynamics.Layout caloric;
        final VulkanReactionProgressLayout progress;
        final int cells,faces,rules,geometryWord,water,activeCells,activeFaces;
        final double cellSize,gravity;
        final ByteBuffer ruleBytes;
        final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        final long endOffset;
        Layout(VulkanRegionReactions.Configuration config,VulkanLiquidPhaseRows.Layout rows,VulkanLiquidThermodynamics.Layout caloric,
               VulkanReactionProgressLayout progress,int geometryWord,double cellSize,int activeCells,int activeFaces,long base,long alignment){
            this.rows=rows;this.caloric=caloric;this.progress=progress;this.geometryWord=geometryWord;this.cellSize=cellSize;
            this.gravity=config.gravity();this.activeCells=activeCells;this.activeFaces=activeFaces;
            var liquid=rows.source;cells=liquid.cellCount();faces=liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_X).count()
                    +liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_Y).count()+liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_Z).count();
            var selected=supportedRules(config);rules=selected.size();
            water=config.chemistry().speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.H2O);
            ruleBytes=ByteBuffer.allocateDirect(80*rules).order(ByteOrder.LITTLE_ENDIAN);
            for(var rule:selected){
                var phase=config.chemistry().phase(config.chemistry().phaseHandle(rule.phaseId()));
                int material=config.fluids().handle(phase.materialId()),ordinal=config.definitions().identity().rules().indexOf(rule.id());
                if(ordinal<0)throw new IllegalArgumentException("Missing diffusion rule: "+rule.id());
                ruleBytes.putInt(material).putInt(rule.first()).putInt(rule.second()).putInt(rule.firstCount()).putInt(rule.secondCount())
                        .putInt(rule.axis()).putInt(ordinal).putInt(0).putLong(mass(config,rule.first())).putLong(rule.second()<0?0:mass(config,rule.second()))
                        .putLong(mass(config,water)).putDouble(rule.diffusivityM2PerS()).putDouble(rule.solventMolesPerExtent()).putLong(0);
            }ruleBytes.flip();
            long cursor=base;
            for(var f:Field.values()){
                int n=switch(f){case HEADER->96;case RULES->20*rules;case PENDING_INDEX->Math.multiplyExact(cells,rules);
                    case MOMENTUM_CHANGE->Math.multiplyExact(3,cells);case OLD_K,NEW_K,FACE_P->faces;case FACE_HEAT->2*faces;default->cells;};
                var col=new VulkanLiquidStateLayout.Column(align(cursor,alignment),n,f.width);columns.put(f,col);cursor=col.offset()+col.bindingBytes();
            }endOffset=align(cursor,alignment);
        }
        private static long mass(VulkanRegionReactions.Configuration c,int species){var molar=c.chemistry().species(species).molarMassKgPerMol().divide(mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL);return Mass.exactUnits(molar.numerator(),molar.denominator());}
        int word(Field f){return Math.toIntExact(column(f).offset()/4);}VulkanLiquidStateLayout.Column column(Field f){return columns.get(f);}
        ByteBuffer headerBytes(double duration){
            var b=ByteBuffer.allocateDirect(384).order(ByteOrder.LITTLE_ENDIAN);var h=new int[96];
            h[0]=cells;h[1]=rules;h[2]=rows.headerWord();h[3]=geometryWord;h[4]=Math.toIntExact(caloric.header().offset()/4);
            h[5]=progress.word(VulkanReactionProgressLayout.Field.PENDING_COUNT);h[6]=progress.word(VulkanReactionProgressLayout.Field.PENDING_CELL);
            h[7]=progress.word(VulkanReactionProgressLayout.Field.PENDING_MATERIAL);h[8]=progress.word(VulkanReactionProgressLayout.Field.PENDING_SUPPORT);
            h[9]=progress.word(VulkanReactionProgressLayout.Field.PENDING_RULE);h[10]=progress.word(VulkanReactionProgressLayout.Field.PENDING_SECONDS);
            h[11]=progress.word(VulkanReactionProgressLayout.Field.RESIDUAL_MOLES);h[12]=progress.pendingCapacity();h[13]=rows.species;h[14]=rows.source.materialCount();h[15]=faces;
            h[22]=rows.source.sizeX();h[23]=rows.source.sizeY();h[24]=rows.source.sizeZ();h[25]=water;h[26]=activeCells;h[27]=activeFaces;
            for(var f:Field.values())h[WORK_FIELDS+f.ordinal()]=word(f);
            for(int i=0;i<h.length;i++)b.putInt(4*i,h[i]);b.putDouble(4*16,cellSize);b.putDouble(4*18,duration);b.putDouble(4*20,gravity);return b;
        }
        List<VulkanGasThermodynamics.TableChunk> headers(){return List.of(new VulkanGasThermodynamics.TableChunk(column(Field.HEADER).offset(),headerBytes(0)),new VulkanGasThermodynamics.TableChunk(rows.column(VulkanLiquidPhaseRows.Field.HEADER).offset(),rows.headerBytes()));}
        List<VulkanGasThermodynamics.TableChunk> tables(){return List.of(new VulkanGasThermodynamics.TableChunk(column(Field.RULES).offset(),ruleBytes.duplicate()));}
        private VulkanEnergyLedger.Stream stream(Field f,int sign){return new VulkanEnergyLedger.Stream(1,word(f),column(f).count(),2,sign);}
        List<VulkanEnergyLedger.Stream> roundoffStreams(){var list=new ArrayList<>(rows.energyChangeStreams());list.add(stream(Field.OLD_K,-1));list.add(stream(Field.NEW_K,1));list.add(stream(Field.PE_CHANGE,1));return List.copyOf(list);}
        List<VulkanEnergyLedger.Stream> mechanicalWorkStreams(){return List.of(stream(Field.OLD_K,1),stream(Field.NEW_K,-1),stream(Field.PE_CHANGE,-1));}
    }
    static List<AqueousDiffusion.Rule> supportedRules(VulkanRegionReactions.Configuration config){
        return AqueousDiffusion.rules(config.chemistry()).stream().filter(rule->{
            String material=config.chemistry().phase(config.chemistry().phaseHandle(rule.phaseId())).materialId();
            return config.fluids().materials().stream().anyMatch(value->value.id().equals(material));
        }).toList();
    }
    private final FlipKernelBindings bindings;private final VulkanLiquidPhaseRows rows;
    VulkanLiquidDiffusion(VulkanDeviceContext context){
        bindings=new FlipKernelBindings(context,2,VulkanLiquidPhaseRows.resource("liquid-diffusion.comp")
                .replace("// HOST_LAYOUT",shaderLayout()).replace("// PHASE_ROWS_LIBRARY",VulkanLiquidPhaseRows.resource("liquid-phase-rows.glsl")),16);
        try{rows=new VulkanLiquidPhaseRows(context);}catch(RuntimeException|Error e){bindings.close();throw e;}
    }
    int execute(VulkanRegionStorage.Candidate candidate,Layout l,double duration){
        var status=candidate.continueBatch((command,input,output,errors,staging,stack)->{
            var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            bindings.rebindRanges(List.of(arena,err));rows.rebindRanges(arena,err);
            vkCmdUpdateBuffer(command,output.handle(),l.column(Field.HEADER).offset(),l.headerBytes(duration));
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT,stack);
            rows.recordImport(command,l.rows);stage(command,l,0,Math.max(Math.multiplyExact(l.cells,l.rules),Math.max(l.cells,l.faces)),0);
            stage(command,l,1,l.progress.pendingCapacity(),0);
            for(int axis=0;axis<3;axis++)for(int parity=0;parity<2;parity++)stage(command,l,2,l.cells,axis*2+parity);
            stage(command,l,3,l.faces,0);stage(command,l,4,l.cells,0);stage(command,l,6,l.cells,0);rows.recordAssemble(command,l.rows);
        },l.rows.feedbackOffset(),16);
        if(status.error()!=0)return status.error();
        if(status.feedback().getInt(0)==1)throw new VulkanRegionReactions.CapacityGrowth(status.feedback().getInt(8),status.feedback().getInt(12),0,0,0);
        if(status.feedback().getInt(0)!=2)throw new IllegalStateException("Diffusion successor is incomplete");
        return candidate.continueBatch((command,input,output,errors,staging,stack)->{rows.recordPublish(command,l.rows);stage(command,l,5,l.faces,0);},0,0).error();
    }
    private void stage(VkCommandBuffer c,Layout l,int stage,int count,int parity){if(count==0)return;try(var stack=stackPush()){
        bindings.bind(c,stack);vkCmdPushConstants(c,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(l.word(Field.HEADER),stage,count,parity));
        int groups=(count+127)/128;vkCmdDispatch(c,Math.min(groups,65535),(groups+65534)/65535,1);
        VulkanRegionStorage.barrier(c,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
    }}
    private static long align(long x,long a){return Math.addExact(x,a-1)&-a;}
    @Override public void close(){rows.close();bindings.close();}
}
