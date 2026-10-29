package mmm.gpu;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Transports MAC momentum with accepted integer material fluxes and records local dissipation. */
final class VulkanLiquidMomentum implements AutoCloseable {
    /** Word positions shared by the host encoder and shader. */
    private enum Header {
        SIZE_X(0),
        SIZE_Y(1),
        SIZE_Z(2),
        CELL_COUNT(3),
        FACE_COUNT(4),
        PROJECTED_FACES(5),
        TARGET_FACES(6),
        FLAGS(7),
        CELL_MASSES(8),
        CELL_VOLUMES(9),
        PRIMAL_FLUX(10),
        DUAL_MASS(11),
        TRANSPORTED_MOMENTUM(12),
        FACE_HEAT(13),
        ROOTS(14),
        NEXT_ROOTS(15),
        GROUP_MASS(16),
        GROUP_MOMENTUM(17),
        WALL_IMPULSE(18),
        CELL_HEAT(19),
        ROUTE_MASS(58),
        HEAT_POSTING_REMAINDERS(59),
        HEAT_ALLOCATION_REMAINDERS(60),
        STOCK_CAPACITY(61),
        SOURCE_OFFSETS(62),
        SOURCE_MASKS(63),
        ACTIVITY_CELLS(64),
        ACTIVITY_FACES(65);
        final int word;
        Header(int word) { this.word = word; }
    }
    private static final int SOURCE_FIELDS = 20, TARGET_FIELDS = 39;
    private static String headerDefines() {
        var result = new StringBuilder();
        for (var field : Header.values())
            result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define SOURCE_FIELDS ").append(SOURCE_FIELDS).append('\n');
        result.append("#define TARGET_FIELDS ").append(TARGET_FIELDS).append('\n');
        return result + VulkanShaderLayout.fields("STOCK_", VulkanLiquidStateLayout.Field.values());
    }

    record Layout(VulkanLiquidStateLayout source, VulkanLiquidStateLayout output, VulkanLiquidRouteLayout routes,
                  int projectedFacesWordOffset, int targetFacesWordOffset, int flagsWordOffset, int activityCellsWordOffset, int activityFacesWordOffset,
                  VulkanLiquidStateLayout.Column header, VulkanLiquidStateLayout.Column cellMasses,
                  VulkanLiquidStateLayout.Column cellVolumes, VulkanLiquidStateLayout.Column primalFlux,
                  VulkanLiquidStateLayout.Column dualMass, VulkanLiquidStateLayout.Column transportedMomentum,
                  VulkanLiquidStateLayout.Column faceHeat, VulkanLiquidStateLayout.Column roots,
                  VulkanLiquidStateLayout.Column nextRoots, VulkanLiquidStateLayout.Column groupMass,
                  VulkanLiquidStateLayout.Column groupMomentum, VulkanLiquidStateLayout.Column wallImpulse,
                  VulkanLiquidStateLayout.Column cellHeat, VulkanLiquidStateLayout.Column heatPostingRemainders,
                  VulkanLiquidStateLayout.Column heatAllocationRemainders, long endOffset) {
        int faces() { return primalFlux.count(); }
        int headerWordOffset() { return word(header); }
        void initializeHeader(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
        }
        ByteBuffer headerBytes() { return headerBytes(source); }
        ByteBuffer headerBytes(VulkanLiquidStateLayout source) {
            int[] values=new int[66];
            values[Header.SIZE_X.word]=output.sizeX();values[Header.SIZE_Y.word]=output.sizeY();values[Header.SIZE_Z.word]=output.sizeZ();
            values[Header.CELL_COUNT.word]=output.cellCount();values[Header.FACE_COUNT.word]=faces();
            values[Header.PROJECTED_FACES.word]=projectedFacesWordOffset;values[Header.TARGET_FACES.word]=targetFacesWordOffset;values[Header.FLAGS.word]=flagsWordOffset;
            VulkanLiquidStateLayout.Column[] columns={cellMasses,cellVolumes,primalFlux,dualMass,transportedMomentum,
                    faceHeat,roots,nextRoots,groupMass,groupMomentum,wallImpulse,cellHeat};
            for(int i=0;i<columns.length;i++)values[Header.CELL_MASSES.word+i]=word(columns[i]);
            for(var field:VulkanLiquidStateLayout.Field.values()) {
                values[SOURCE_FIELDS+field.ordinal()]=word(source.column(field));
                values[TARGET_FIELDS+field.ordinal()]=word(output.column(field));
            }
            values[Header.ROUTE_MASS.word]=word(routes.column(VulkanLiquidRouteLayout.Field.MASS_UNITS));
            values[Header.HEAT_POSTING_REMAINDERS.word]=word(heatPostingRemainders);values[Header.HEAT_ALLOCATION_REMAINDERS.word]=word(heatAllocationRemainders);
            values[Header.STOCK_CAPACITY.word]=output.stockCapacity();
            values[Header.SOURCE_OFFSETS.word]=word(routes.column(VulkanLiquidRouteLayout.Field.SOURCE_OFFSETS));values[Header.SOURCE_MASKS.word]=word(routes.column(VulkanLiquidRouteLayout.Field.SOURCE_MASKS));
            values[Header.ACTIVITY_CELLS.word]=activityCellsWordOffset;values[Header.ACTIVITY_FACES.word]=activityFacesWordOffset;
            var target=ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<values.length;i++)target.putInt(Math.toIntExact(0+4L*i),values[i]);

            return target;
        }
    }

    /** Face fields use geometry's packed x/y/z dvec4 columns. Optional flags: bit 0 active, bit 1 constrained. */
    static Layout layout(VulkanLiquidStateLayout source,VulkanLiquidStateLayout output,VulkanLiquidRouteLayout routes,
                         int projectedFacesWordOffset,int targetFacesWordOffset,int flagsWordOffset,long scratchBase,long alignment) {
        return layout(source,output,routes,projectedFacesWordOffset,targetFacesWordOffset,flagsWordOffset,-1,-1,scratchBase,alignment);
    }
    static Layout layout(VulkanLiquidStateLayout source,VulkanLiquidStateLayout output,VulkanLiquidRouteLayout routes,
                         int projectedFacesWordOffset,int targetFacesWordOffset,int flagsWordOffset,
                         int activityCellsWordOffset,int activityFacesWordOffset,long scratchBase,long alignment) {
        if(source.sizeX()!=output.sizeX()||source.sizeY()!=output.sizeY()||source.sizeZ()!=output.sizeZ()
                ||routes.sourceStockCapacity()<source.stockCapacity()||scratchBase<output.bytes()
                ||scratchBase<routes.endOffset()||alignment<8||(alignment&(alignment-1))!=0)
            throw new IllegalArgumentException("Liquid momentum layouts differ or overlap");
        int cells=output.cellCount(),faces=0;
        for(var field:new VulkanLiquidStateLayout.Field[]{VulkanLiquidStateLayout.Field.MOMENTUM_X,
                VulkanLiquidStateLayout.Field.MOMENTUM_Y,VulkanLiquidStateLayout.Field.MOMENTUM_Z})
            faces=Math.addExact(faces,output.column(field).count());
        long[] cursor={scratchBase};
        var header=column(cursor,66,4,alignment);
        var masses=column(cursor,Math.multiplyExact(cells,2),8,alignment);
        var volumes=column(cursor,cells,8,alignment);
        var flux=column(cursor,faces,8,alignment);
        var dual=column(cursor,faces,8,alignment);
        var momentum=column(cursor,faces,8,alignment);
        var heat=column(cursor,faces,8,alignment);
        var roots=column(cursor,faces,4,alignment);
        var next=column(cursor,faces,4,alignment);
        var groupMass=column(cursor,faces,8,alignment);
        var groupMomentum=column(cursor,faces,8,alignment);
        var impulse=column(cursor,faces,8,alignment);
        var cellHeat=column(cursor,cells,8,alignment);
        var postingRemainders=column(cursor,Math.multiplyExact(output.stockCapacity(),2),8,alignment);
        var allocationRemainders=column(cursor,Math.multiplyExact(cells,3),8,alignment);
        return new Layout(source,output,routes,projectedFacesWordOffset,targetFacesWordOffset,flagsWordOffset,activityCellsWordOffset,activityFacesWordOffset,
                header,masses,volumes,flux,dual,momentum,heat,roots,next,groupMass,groupMomentum,impulse,cellHeat,
                postingRemainders,allocationRemainders,align(cursor[0],alignment));
    }

    private final FlipKernelBindings bindings;
    VulkanLiquidMomentum(VulkanDeviceContext context) { bindings=new FlipKernelBindings(context,3,shader(),16); }
    void rebindRanges(FlipKernelBindings.BufferRange source,FlipKernelBindings.BufferRange candidate,FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(source,candidate,errors));
    }
    /** Run after inventory gather, before rebuilding target face geometry overwrites projected velocities. */
    void recordTransport(VkCommandBuffer command,Layout layout) {
        stage(command,layout,0,layout.output().cellCount(),0);
        stage(command,layout,1,layout.faces(),0);
        stage(command,layout,2,layout.faces(),0);
    }
    /** Run after target face geometry is ready; heat and wall impulse remain resident for the shared ledgers. */
    void recordResolve(VkCommandBuffer command,Layout layout) {
        stage(command,layout,3,layout.faces(),0);
        int longest=Math.max(layout.output().sizeX(),Math.max(layout.output().sizeY(),layout.output().sizeZ()))+1;
        int parity=0;
        for(int span=1;span<longest;span*=2) { stage(command,layout,4,layout.faces(),parity);parity^=1; }
        int x=layout.output().sizeX(),y=layout.output().sizeY(),z=layout.output().sizeZ();
        stage(command,layout,5,Math.addExact(Math.addExact(x*y,x*z),y*z),parity);
        stage(command,layout,6,layout.faces(),parity);
        stage(command,layout,7,layout.output().cellCount(),0);
    }
    /** Adds resolved mechanical cell heat to stock energy and retains every allocation/addition roundoff term. */
    void recordHeatPosting(VkCommandBuffer command,Layout layout) {
        stage(command,layout,8,layout.output().stockCapacity(),0);
        stage(command,layout,9,layout.output().cellCount(),0);
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count,int parity) {
        if(count==0)return;
        int groups=(int)(((long)count+127)/128);
        try(var stack=stackPush()) {
            bindings.bind(command,stack);
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,
                    stack.ints(layout.headerWordOffset(),stage,count,parity));
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column) { return Math.toIntExact(column.offset()/4); }
    private static long align(long value,long alignment) { return Math.addExact(value,alignment-1)&-alignment; }
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width,long alignment) {
        var column=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,width);
        cursor[0]=Math.addExact(column.offset(),column.bindingBytes());return column;
    }
    private static String shader() {
        try(var stream=VulkanLiquidMomentum.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-momentum.comp")) {
            if(stream==null)throw new IllegalStateException("Liquid momentum shader missing");
            return new String(stream.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", headerDefines());
        }catch(IOException failure){throw new IllegalStateException("Liquid momentum shader unavailable",failure);}
    }
    @Override public void close(){bindings.close();}
}
