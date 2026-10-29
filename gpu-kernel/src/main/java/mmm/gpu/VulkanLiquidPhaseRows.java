package mmm.gpu;

import mmm.units.SignedDyadic;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Mutable private phase rows support newly nucleated liquid and exact-volume canonical publication. */
final class VulkanLiquidPhaseRows implements AutoCloseable {
    private enum Header {
        CELL_COUNT(0),
        SLOT_COUNT(1),
        RESERVED(2),
        SPECIES_COUNT(3),
        ROW_COUNT(4),
        SPECIES_CAPACITY(5),
        LIMB_CAPACITY(6),
        VOLUME_SIGNIFICAND(60),
        VOLUME_EXPONENT(62),
        SIZE_X(64),
        SIZE_Y(65),
        SIZE_Z(66),
        DEFINITIONS(68),
        MATERIAL_COUNT(69),
        STOCK_CAPACITY(70);
        final int word;
        Header(int word) { this.word = word; }
    }
    private static final int PHASE_FIELDS = 8, STOCK_FIELDS = 40;
    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values())
            result.append("#define LP_H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define LP_PHASE_FIELDS ").append(PHASE_FIELDS).append('\n');
        result.append("#define LP_STOCK_FIELDS ").append(STOCK_FIELDS).append('\n');
        return result + VulkanShaderLayout.fields("LP_FIELD_", Field.values())
            + VulkanShaderLayout.fields("LP_STOCK_", VulkanLiquidStateLayout.Field.values());
    }

    enum Field { HEADER(4),CELL(4),MATERIAL(4),SUPPORT(4),MASS(8),ENERGY(8),DELTA_SVQ(8),SPECIES(8),LIVE(4),
        SPECIES_COUNTS(4),SPECIES_OFFSETS(4),SPECIES_HANDLES(4),SPECIES_AMOUNTS(8),
        VOLUME_RESULTS(4),VOLUME_COUNTS(4),VOLUME_OFFSETS(4),VOLUME_LIMBS(4),DELTA_LIMBS(4),SCAN(4),CONTROL(4),OLD_ENERGY(8),NEW_ENERGY(8),MIXTURE(4),BASE_VOLUME(8),MASS_RESIDUAL(8),NUMERIC_VOLUME(8);
        final int width;Field(int width){this.width=width;}}
    static final class Layout {
        final VulkanLiquidStateLayout source;final VulkanReactionDefinitions.Layout definitions;final int rows,species;final SignedDyadic svqVolume;
        final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);final long endOffset;
        Layout(VulkanLiquidStateLayout source,VulkanReactionDefinitions.Layout definitions,double cellSize,long base,long alignment){
            if(base<Math.max(source.bytes(),definitions.endOffset())||alignment<8||(alignment&(alignment-1))!=0||!(Double.isFinite(cellSize)&&cellSize>0))throw new IllegalArgumentException("Invalid phase row workspace");
            this.source=source;this.definitions=definitions;rows=source.slotCount();species=definitions.speciesCount();
            svqVolume=SignedDyadic.of(Math.scalb(1.0,-27)/(cellSize*cellSize*cellSize));if(svqVolume.signum()<=0||svqVolume.significand().bitLength()>63)throw new IllegalArgumentException("Phase volume multiplier exceeds int64");
            long cursor=base;for(var f:Field.values()){int n=switch(f){case HEADER->80;case CONTROL->8;
                case SPECIES->Math.multiplyExact(rows,species);case SPECIES_COUNTS,VOLUME_COUNTS,OLD_ENERGY,NEW_ENERGY->source.slotCount();
                case SPECIES_OFFSETS,VOLUME_OFFSETS->source.slotCount()+1;case SPECIES_HANDLES,SPECIES_AMOUNTS->source.speciesCapacity();case VOLUME_LIMBS->source.limbCapacity();
                case VOLUME_RESULTS->Math.multiplyExact(8,rows);case DELTA_LIMBS->Math.multiplyExact(4,rows);
                case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(Math.max(source.cellCount(),source.slotCount()))/4);default->rows;};
                var c=new VulkanLiquidStateLayout.Column(align(cursor,alignment),n,f.width);columns.put(f,c);cursor=c.offset()+c.bindingBytes();}endOffset=align(cursor,alignment);
        }
        VulkanLiquidStateLayout.Column column(Field f){return columns.get(f);}int word(Field f){return Math.toIntExact(column(f).offset()/4);}int headerWord(){return word(Field.HEADER);}
        long feedbackOffset(){return column(Field.CONTROL).offset();}
        ByteBuffer headerBytes(){var b=ByteBuffer.allocateDirect(320).order(ByteOrder.LITTLE_ENDIAN);int[] h=new int[80];
            h[Header.CELL_COUNT.word]=source.cellCount();h[Header.SLOT_COUNT.word]=source.slotCount();h[Header.RESERVED.word]=0;h[Header.SPECIES_COUNT.word]=species;h[Header.ROW_COUNT.word]=rows;h[Header.SPECIES_CAPACITY.word]=source.speciesCapacity();h[Header.LIMB_CAPACITY.word]=source.limbCapacity();
            for(var f:Field.values())h[PHASE_FIELDS+f.ordinal()]=word(f);for(var f:VulkanLiquidStateLayout.Field.values())h[STOCK_FIELDS+f.ordinal()]=Math.toIntExact(source.column(f).offset()/4);
            h[Header.VOLUME_EXPONENT.word]=svqVolume.exponent();h[Header.SIZE_X.word]=source.sizeX();h[Header.SIZE_Y.word]=source.sizeY();h[Header.SIZE_Z.word]=source.sizeZ();h[Header.DEFINITIONS.word]=definitions.word();h[Header.MATERIAL_COUNT.word]=source.materialCount();h[Header.STOCK_CAPACITY.word]=source.stockCapacity();
            for(int i=0;i<h.length;i++)b.putInt(4*i,h[i]);b.putLong(4 * Header.VOLUME_SIGNIFICAND.word,svqVolume.significand().longValueExact());return b;}
        void initializeHeader(ByteBuffer arena){arena.duplicate().position(Math.toIntExact(column(Field.HEADER).offset())).put(headerBytes());}
        List<VulkanEnergyLedger.Stream> energyChangeStreams(){return List.of(new VulkanEnergyLedger.Stream(1,word(Field.NEW_ENERGY),source.slotCount(),2,1),new VulkanEnergyLedger.Stream(1,word(Field.OLD_ENERGY),source.slotCount(),2,-1));}
    }
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;
    VulkanLiquidPhaseRows(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,resource("liquid-phase-rows.comp").replace("// PHASE_ROWS_LIBRARY",resource("liquid-phase-rows.glsl")).replace("// DYADIC_LIBRARY",resource("liquid-dyadic.glsl")),12);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError e){bindings.close();throw e;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);}
    void recordImport(VkCommandBuffer command,Layout l){stage(command,l,0,Math.max(1,Math.max(l.rows,l.source.cellCount())));stage(command,l,1,l.source.cellCount());}
    /** Call after all cell-owned edits, including mechanical heat, have finished.
     * CONTROL: status (1=capacity, 2=ready), fixed rows, required species, required volume limbs. */
    void recordAssemble(VkCommandBuffer command,Layout l){stage(command,l,2,l.rows);stage(command,l,4,1);stage(command,l,6,l.source.slotCount());
        scan.record(command,l.source.slotCount(),l.word(Field.SPECIES_COUNTS),l.word(Field.SPECIES_OFFSETS),l.word(Field.SCAN));
        stage(command,l,7,1);stage(command,l,8,l.source.slotCount());}
    void recordPublish(VkCommandBuffer command,Layout l){stage(command,l,9,Math.max(l.source.cellCount()+1,Math.max(l.source.stockCapacity()+1,Math.max(l.source.speciesCapacity(),l.source.limbCapacity()))));}
    private void stage(VkCommandBuffer command,Layout l,int stage,int n){if(n==0)return;try(var stack=stackPush()){
        bindings.bind(command,stack);vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,stack.ints(l.headerWord(),stage,n));int groups=(n+127)/128;vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}}
    static String resource(String n){try(var in=VulkanLiquidPhaseRows.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/"+n)){if(in==null)throw new IllegalStateException("Missing phase resource "+n);var text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return n.equals("liquid-phase-rows.glsl") ? text.replace("// HOST_LAYOUT", shaderLayout()) : text;}catch(IOException e){throw new IllegalStateException(e);}}
    private static long align(long x,long a){return Math.addExact(x,a-1)&-a;}
    @Override public void close(){scan.close();bindings.close();}
}
