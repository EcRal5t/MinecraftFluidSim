package mmm.gpuprobe;

import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.FluidMaterialRegistry;
import mmm.granular.GranularRegistry;
import mmm.units.SignedDyadic;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Integer saturation exchange over a private granular successor and the current liquid generation. */
final class VulkanSolidLiquidReactions implements AutoCloseable {
    enum Field { HEADER(4),PHASES(4),POROSITIES(4),HEAD(4),NEXT(4),CELL(4),LIQUID_SPECIES(8),SOLID_SPECIES(8),
        LIQUID_MASS(8),LIQUID_ENERGY(8),LIQUID_DELTA(8),LIQUID_OLD_ENERGY(8),SOLID_OLD_ENERGY(8),SOLID_NEW_ENERGY(8),
        SOLID_COUNTS(4),SOLID_OFFSETS(4),SOLID_HANDLES(4),SOLID_AMOUNTS(8),
        DELTA_LIMBS(4),SCAN(4),CONTROL(4),
        CELL_OLD_MASS(8),CELL_NEW_MASS(8),POTENTIAL_WORK(8),PROPOSAL_HEAD(4),PROPOSAL_NEXT(4),FACE_MOMENTUM(8),FACE_HEAT(8),WALL_IMPULSE(8),
        FACE_OLD_K(8),FACE_NEW_K(8),SOLID_OLD_K(8),SOLID_NEW_K(8),CELL_OLD_PE(8),CELL_NEW_PE(8),SOLID_SOURCE_MASS(8),SOLID_WITHDRAWN_MASS(8);
        final int width;Field(int width){this.width=width;}}
    static final class Layout {
        final VulkanLiquidStateLayout liquid;final VulkanGranularStateLayout solid;final VulkanReactionDefinitions.Layout definitions;
        final VulkanLiquidThermodynamics.Layout liquidCaloric,solidCaloric;final VulkanReactionProgressLayout progress;
        final VulkanLiquidPhaseRows.Layout phaseRows;
        final int species,faces,faceGeometryWord;final int[] phases,porosities;final double cellSize,gravity;final boolean precipitationEnabled;final SignedDyadic volumeUnit;
        final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);final long endOffset;
        Layout(VulkanLiquidStateLayout liquid,VulkanGranularStateLayout solid,VulkanReactionDefinitions.Layout definitions,
               VulkanLiquidThermodynamics.Layout liquidCaloric,VulkanLiquidThermodynamics.Layout solidCaloric,
               VulkanReactionProgressLayout progress,FluidMaterialRegistry fluid,ChemicalRegistry chemical,GranularRegistry granular,
               double cellSize,double gravity,int faceGeometryWord,long base,long alignment){
            this(liquid,solid,definitions,liquidCaloric,solidCaloric,progress,fluid,chemical,granular,cellSize,gravity,faceGeometryWord,true,base,alignment);
        }
        Layout(VulkanLiquidStateLayout liquid,VulkanGranularStateLayout solid,VulkanReactionDefinitions.Layout definitions,
               VulkanLiquidThermodynamics.Layout liquidCaloric,VulkanLiquidThermodynamics.Layout solidCaloric,
               VulkanReactionProgressLayout progress,FluidMaterialRegistry fluid,ChemicalRegistry chemical,GranularRegistry granular,
               double cellSize,double gravity,int faceGeometryWord,boolean precipitationEnabled,long base,long alignment){
            if(base<Math.max(progress.endOffset(),Math.max(liquidCaloric.endOffset(),solidCaloric.endOffset()))||cellSize<=0||!Double.isFinite(cellSize)
                    ||alignment<8||(alignment&(alignment-1))!=0||definitions.speciesCount()>256
                    ||solid.definitions().speciesIds().size()!=chemical.species().size()||liquidCaloric.speciesStride()<chemical.species().size()||solidCaloric.speciesStride()<chemical.species().size()
                    ||!solid.definitions().materialIds().equals(definitions.identity().granularMaterials())||!Double.isFinite(gravity)||gravity<0||faceGeometryWord< -1)throw new IllegalArgumentException("Invalid solid/liquid reaction layout");
            this.liquid=liquid;this.solid=solid;this.definitions=definitions;this.liquidCaloric=liquidCaloric;this.solidCaloric=solidCaloric;this.progress=progress;
            this.cellSize=cellSize;this.gravity=gravity;this.precipitationEnabled=precipitationEnabled;this.faceGeometryWord=faceGeometryWord;faces=liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_X).count()+liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_Y).count()+liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_Z).count();
            species=definitions.speciesCount();volumeUnit=SignedDyadic.of(Math.scalb(1.0,-27)/(cellSize*cellSize*cellSize));
            if(volumeUnit.significand().bitLength()>63)throw new IllegalArgumentException("SVQ conversion exceeds exact phase multiplier");
            phases=new int[fluid.size()];java.util.Arrays.fill(phases,-1);for(int m=0;m<fluid.size();m++)for(int p=0;p<chemical.phases().size();p++)
                if(fluid.material(m).id().equals(chemical.phases().get(p).materialId()))phases[m]=p;
            porosities=new int[solid.definitions().materialIds().size()];for(int m=0;m<porosities.length;m++)porosities[m]=Float.floatToRawIntBits((float)granular.material(granular.materialHandle(solid.definitions().materialIds().get(m))).restPorosityQ()/mmm.granular.GranularLimits.POROSITY_SCALE);
            for(int s=0;s<species;s++)if(!solid.definitions().speciesIds().get(s).equals(chemical.species(s).id()))throw new IllegalArgumentException("Solid/liquid species handles differ");
            phaseRows=new VulkanLiquidPhaseRows.Layout(liquid,definitions,cellSize,base,alignment);
            long cursor=phaseRows.endOffset;for(var f:Field.values()){
                var shared=switch(f){case LIQUID_SPECIES->VulkanLiquidPhaseRows.Field.SPECIES;case LIQUID_MASS->VulkanLiquidPhaseRows.Field.MASS;case LIQUID_ENERGY->VulkanLiquidPhaseRows.Field.ENERGY;case LIQUID_DELTA->VulkanLiquidPhaseRows.Field.DELTA_SVQ;case LIQUID_OLD_ENERGY->VulkanLiquidPhaseRows.Field.OLD_ENERGY;default->null;};
                if(shared!=null){columns.put(f,phaseRows.column(shared));continue;}int count=switch(f){
                case HEADER->192;case PHASES->phases.length;case POROSITIES->porosities.length;case HEAD,CELL_OLD_MASS,CELL_NEW_MASS,POTENTIAL_WORK,PROPOSAL_HEAD,CELL_OLD_PE,CELL_NEW_PE->liquid.cellCount();
                case NEXT,CELL,SOLID_OLD_ENERGY,SOLID_NEW_ENERGY,SOLID_COUNTS,SOLID_OLD_K,SOLID_NEW_K,SOLID_SOURCE_MASS,SOLID_WITHDRAWN_MASS->solid.rowCapacity();
                case PROPOSAL_NEXT->progress.proposalCapacity();case FACE_MOMENTUM,FACE_OLD_K,FACE_NEW_K->faces;case FACE_HEAT->Math.multiplyExact(2,faces);case WALL_IMPULSE->Math.multiplyExact(3,liquid.cellCount());
                case LIQUID_SPECIES->Math.multiplyExact(liquid.slotCount(),species);case SOLID_SPECIES->Math.multiplyExact(solid.rowCapacity(),species);
                case SOLID_OFFSETS->solid.rowCapacity()+1;
                case SOLID_HANDLES,SOLID_AMOUNTS->solid.speciesCapacity();
                case DELTA_LIMBS->Math.multiplyExact(liquid.slotCount(),4);
                case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(Math.max(liquid.slotCount(),solid.rowCapacity()))/4);case CONTROL->12;
                default->liquid.slotCount();};
                long start=align(cursor,alignment);var c=new VulkanLiquidStateLayout.Column(start,count,f.width);columns.put(f,c);cursor=start+c.bindingBytes();
            }endOffset=align(cursor,alignment);
        }
        VulkanLiquidStateLayout.Column column(Field f){return columns.get(f);}int word(Field f){return Math.toIntExact(column(f).offset()/4);}
        long feedbackOffset(){return column(Field.CONTROL).offset();}
        ByteBuffer headerBytes(){var out=ByteBuffer.allocateDirect(768).order(ByteOrder.LITTLE_ENDIAN);int[] h=new int[192];
            h[0]=liquid.cellCount();h[1]=liquid.slotCount();h[2]=solid.rowCapacity();h[3]=species;h[4]=definitions.word();
            h[5]=Math.toIntExact(liquidCaloric.header().offset()/4);h[6]=Math.toIntExact(solidCaloric.header().offset()/4);h[7]=solid.particleCapacity();h[8]=solid.depositCapacity();
            h[9]=liquid.sizeX();h[10]=liquid.sizeY();h[11]=liquid.sizeZ();h[12]=liquid.speciesCapacity();h[13]=solid.speciesCapacity();h[14]=liquid.limbCapacity();
            h[15]=progress.proposalCapacity();for(var f:Field.values())h[16+f.ordinal()]=word(f);
            for(var f:VulkanLiquidStateLayout.Field.values())h[64+f.ordinal()]=Math.toIntExact(liquid.column(f).offset()/4);
            for(var f:VulkanGranularStateLayout.Field.values())h[88+f.ordinal()]=solid.word(f);
            for(var f:VulkanReactionProgressLayout.Field.values())h[120+f.ordinal()]=progress.word(f);
            h[172]=phaseRows.headerWord();h[171]=precipitationEnabled?1:0;h[169]=liquid.materialCount();h[170]=liquid.stockCapacity();h[163]=faceGeometryWord;h[168]=faces;for(int i=0;i<h.length;i++)out.putInt(4*i,h[i]);
            out.putLong(4*160,volumeUnit.significand().longValueExact()).putInt(4*162,volumeUnit.exponent()).putDouble(4*164,cellSize).putDouble(4*166,gravity);return out;}
        List<VulkanGasThermodynamics.TableChunk> tableChunks(){var a=ByteBuffer.allocateDirect(4*phases.length).order(ByteOrder.LITTLE_ENDIAN);
            var b=ByteBuffer.allocateDirect(4*porosities.length).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<phases.length;i++)a.putInt(4*i,phases[i]);for(int i=0;i<porosities.length;i++)b.putInt(4*i,porosities[i]);
            return List.of(new VulkanGasThermodynamics.TableChunk(column(Field.PHASES).offset(),a),new VulkanGasThermodynamics.TableChunk(column(Field.POROSITIES).offset(),b));}
        void initialize(ByteBuffer arena){phaseRows.initializeHeader(arena);arena.duplicate().position(Math.toIntExact(column(Field.HEADER).offset())).put(headerBytes());for(var c:tableChunks())arena.duplicate().position(Math.toIntExact(c.offset())).put(c.bytes().duplicate());}
        List<VulkanEnergyLedger.Stream> roundoffStreams(){return List.of(stream(Field.LIQUID_ENERGY,1),stream(Field.SOLID_NEW_ENERGY,1),stream(Field.LIQUID_OLD_ENERGY,-1),stream(Field.SOLID_OLD_ENERGY,-1),
                stream(Field.FACE_NEW_K,1),stream(Field.FACE_OLD_K,-1),stream(Field.SOLID_NEW_K,1),stream(Field.SOLID_OLD_K,-1),stream(Field.CELL_NEW_PE,1),stream(Field.CELL_OLD_PE,-1));}
        List<VulkanEnergyLedger.Stream> mechanicalWorkStreams(){return List.of(stream(Field.FACE_OLD_K,1),stream(Field.FACE_NEW_K,-1),
                stream(Field.SOLID_OLD_K,1),stream(Field.SOLID_NEW_K,-1),stream(Field.CELL_OLD_PE,1),stream(Field.CELL_NEW_PE,-1));}
        private VulkanEnergyLedger.Stream stream(Field f,int sign){return new VulkanEnergyLedger.Stream(1,word(f),column(f).count(),2,sign);}
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;private final VulkanLiquidPhaseRows phaseKernel;
    VulkanSolidLiquidReactions(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,3,resource("solid-liquid-reactions.comp").replace("// DYADIC_LIBRARY",resource("liquid-dyadic.glsl")),12);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError e){bindings.close();throw e;}
        try{phaseKernel=new VulkanLiquidPhaseRows(context);}catch(RuntimeException|LinkageError e){scan.close();bindings.close();throw e;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors,arena));scan.rebindRanges(arena,arena,arena,errors);phaseKernel.rebindRanges(arena,errors);}
    /** The caller first copies granular owners to its transition layout's private source. */
    void recordPrepare(VkCommandBuffer command,Layout l){
        stage(command,l,0,Math.max(l.progress.proposalCapacity(),Math.max(1,Math.max(l.liquid.cellCount(),Math.max(l.liquid.slotCount(),l.solid.rowCapacity())))));
        phaseKernel.recordImport(command,l.phaseRows);
        stage(command,l,1,Math.max(l.liquid.slotCount(),l.solid.rowCapacity()));stage(command,l,2,l.liquid.cellCount());
        stage(command,l,3,Math.max(l.liquid.slotCount(),l.solid.rowCapacity()));
        scan.record(command,l.solid.rowCapacity(),l.word(Field.SOLID_COUNTS),l.word(Field.SOLID_OFFSETS),l.word(Field.SCAN));

        stage(command,l,4,1);stage(command,l,5,l.solid.rowCapacity());
        stage(command,l,8,l.faces);stage(command,l,9,l.liquid.cellCount());stage(command,l,10,l.solid.rowCapacity());
        phaseKernel.recordAssemble(command,l.phaseRows);stage(command,l,11,1);
    }
    /** Publishes liquid columns and the private solid composition, then the caller reassembles solid owners. */
    void recordPublish(VkCommandBuffer command,Layout l){phaseKernel.recordPublish(command,l.phaseRows);stage(command,l,7,Math.max(l.faces,Math.max(l.solid.rowCapacity()+1,l.solid.speciesCapacity())));}
    private void stage(VkCommandBuffer command,Layout l,int stage,int n){if(n==0)return;try(var stack=stackPush()){
        bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(l.word(Field.HEADER),stage,n));int g=(n+127)/128;vkCmdDispatch(command,Math.min(g,65535),(g+65534)/65535,1);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    private static long align(long x,long a){return Math.addExact(x,a-1)&-a;}
    private static String resource(String n){try(var in=VulkanSolidLiquidReactions.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/"+n)){if(in==null)throw new IllegalStateException("Missing reaction shader "+n);return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(IOException e){throw new IllegalStateException(e);}}
    @Override public void close(){try{phaseKernel.close();}finally{scan.close();bindings.close();}}
}
