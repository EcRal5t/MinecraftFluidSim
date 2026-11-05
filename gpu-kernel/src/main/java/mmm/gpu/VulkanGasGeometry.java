package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static mmm.gpu.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Exact dry presence, PLIC dry ports, and chamber membership for one resident generation. */
final class VulkanGasGeometry implements AutoCloseable {
    private enum Header {
        SIZE_X(0),
        SIZE_Y(1),
        SIZE_Z(2),
        CELL_COUNT(3),
        FACE_COUNT(4),
        SEALED_EXTERIOR(5),
        MASKS(6),
        CELLS(8),
        PLANES(9),
        MICRO(10),
        PRESENCE(16),
        DRY_VOLUMES(17),
        DRY_MASKS(18),
        FACE_AREAS(20),
        CELL_NODES(24),
        MICRO_FRACTIONS(33),
        CELL_SIZE(34),
        LIQUID_FACES(36),
        MOMENTUM_X(37),
        MOMENTUM_Y(38),
        MOMENTUM_Z(39),
        MIXTURE_STATES(44);
        final int word;
        Header(int word) { this.word = word; }
    }
    static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define GEO_H_").append(field.name()).append(' ').append(field.word).append('\n');
        return result.toString();
    }

    record Layout(VulkanLiquidCellVolumes.Layout totals, int masksWordOffset, int occupiedWordOffset,
                  int cellsWordOffset, int planesWordOffset, int microWordOffset, boolean sealedExterior,
                  double cellSize, int liquidFacesWordOffset, int mixtureStatesWordOffset,
                  VulkanLiquidStateLayout.Column header, VulkanLiquidStateLayout.Column presence,
                  VulkanLiquidStateLayout.Column dryVolumes, VulkanLiquidStateLayout.Column dryMasks,
                  VulkanLiquidStateLayout.Column faceAreas, VulkanLiquidStateLayout.Column cellNodes,
                  VulkanLiquidStateLayout.Column microFractions, long endOffset) {
        Layout withLiquidFaces(int wordOffset) {
            return new Layout(totals,masksWordOffset,occupiedWordOffset,cellsWordOffset,planesWordOffset,microWordOffset,
                    sealedExterior,cellSize,wordOffset,mixtureStatesWordOffset,header,presence,dryVolumes,dryMasks,
                    faceAreas,cellNodes,microFractions,endOffset);
        }
        Layout withMixture(VulkanMixtureState.Layout mixture) {
            if(mixture.inputs().cells()!=cells())throw new IllegalArgumentException("Mixture and gas geometry dimensions differ");
            return new Layout(totals,masksWordOffset,occupiedWordOffset,cellsWordOffset,planesWordOffset,microWordOffset,
                    sealedExterior,cellSize,liquidFacesWordOffset,mixture.statesWord(),header,presence,dryVolumes,dryMasks,
                    faceAreas,cellNodes,microFractions,endOffset);
        }
        int headerWordOffset() { return word(header); }
        int cells() { return totals.source().cellCount(); }
        int faces() { return faceAreas.count(); }
        long bytes() { return endOffset-header.offset(); }
        void initializeHeader(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
        }
        ByteBuffer headerBytes() {
            var target=ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            int[] data=new int[64];
            data[Header.SIZE_X.word]=totals.source().sizeX();data[Header.SIZE_Y.word]=totals.source().sizeY();data[Header.SIZE_Z.word]=totals.source().sizeZ();
            data[Header.CELL_COUNT.word]=cells();data[Header.FACE_COUNT.word]=faces();data[Header.SEALED_EXTERIOR.word]=sealedExterior?1:0;
            data[Header.MASKS.word]=masksWordOffset;data[Header.CELLS.word]=cellsWordOffset;data[Header.PLANES.word]=planesWordOffset;data[Header.MICRO.word]=microWordOffset;
            data[Header.PRESENCE.word]=word(presence);data[Header.DRY_VOLUMES.word]=word(dryVolumes);data[Header.DRY_MASKS.word]=word(dryMasks);
            data[Header.FACE_AREAS.word]=word(faceAreas);data[Header.CELL_NODES.word]=word(cellNodes);data[Header.MICRO_FRACTIONS.word]=word(microFractions);
            data[Header.LIQUID_FACES.word]=liquidFacesWordOffset;data[Header.MIXTURE_STATES.word]=mixtureStatesWordOffset;
            data[Header.MOMENTUM_X.word]=word(totals.source().column(VulkanLiquidStateLayout.Field.MOMENTUM_X));
            data[Header.MOMENTUM_Y.word]=word(totals.source().column(VulkanLiquidStateLayout.Field.MOMENTUM_Y));
            data[Header.MOMENTUM_Z.word]=word(totals.source().column(VulkanLiquidStateLayout.Field.MOMENTUM_Z));
            for(int i=0;i<data.length;i++)target.putInt(4*i,data[i]);target.putDouble(4*Header.CELL_SIZE.word,cellSize);return target;
        }
    }
    static Layout layout(VulkanLiquidCellVolumes.Layout totals, int masksWordOffset, int occupiedWordOffset,
                         int cellsWordOffset, int planesWordOffset, int microWordOffset, boolean sealedExterior,
                         double cellSize, long scratchBase, long alignment) {
        double h3=cellSize*cellSize*cellSize;
        if(scratchBase<totals.endOffset()||alignment<8||(alignment&(alignment-1))!=0||!(cellSize>0&&h3>0&&Double.isFinite(h3)))
            throw new IllegalArgumentException("Invalid gas geometry layout");
        int cells=totals.source().cellCount(),sx=totals.source().sizeX(),sy=totals.source().sizeY(),sz=totals.source().sizeZ();
        int faces=Math.toIntExact((long)(sx+1)*sy*sz+(long)sx*(sy+1)*sz+(long)sx*sy*(sz+1));
        long[] cursor={scratchBase};
        var header=column(cursor,64,4,alignment);var presence=column(cursor,cells,4,alignment);
        var volumes=column(cursor,cells,8,alignment);var masks=column(cursor,cells,4,alignment);
        var areas=column(cursor,faces,8,alignment);var nodes=column(cursor,cells,4,alignment);
        var fractions=column(cursor,Math.multiplyExact(cells,8),4,alignment);
        return new Layout(totals,masksWordOffset,occupiedWordOffset,cellsWordOffset,planesWordOffset,microWordOffset,
                sealedExterior,cellSize,-1,-1,header,presence,volumes,masks,areas,nodes,fractions,align(cursor[0],alignment));
    }
    private final FlipKernelBindings bindings;
    VulkanGasGeometry(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 2,
                resource("gas-geometry.comp").replace("// HOST_LAYOUT", shaderLayout()).replace("// PLIC_FACE_LIBRARY", resource("plic-face.glsl")), 20);
    }
    void rebindRanges(FlipKernelBindings.BufferRange candidate, FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(candidate, errors));
    }
    /** Local gas addresses are fixed by cell; no chamber graph is built. */
    void recordLocalGeometry(VkCommandBuffer command,Layout layout){
        if(layout.mixtureStatesWordOffset()<0)throw new IllegalArgumentException("Local gas geometry requires mixture volumes");
        stage(command,layout,13,layout.cells());stage(command,layout,14,layout.faces());
    }
    private void stage(VkCommandBuffer command, Layout layout, int stage, int count) {
        stage(command,layout,stage,count,0,0);
    }
    private void stage(VkCommandBuffer command, Layout layout, int stage, int count,int gasPieces,int gasCounts) {
        int groups = (int) (((long) count + 127) / 128);
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(layout.headerWordOffset(), stage, count,gasPieces,gasCounts));
            vkCmdDispatch(command, Math.min(groups, 65535), (groups + 65534) / 65535, 1);
            barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_INDIRECT_COMMAND_READ_BIT, stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column) { return Math.toIntExact(column.offset() / 4); }
    private static long align(long value, long alignment) { return Math.addExact(value, alignment - 1) & -alignment; }
    private static VulkanLiquidStateLayout.Column column(long[] cursor, int count, int width, long alignment) {
        var result = new VulkanLiquidStateLayout.Column(align(cursor[0], alignment), count, width);
        cursor[0] = Math.addExact(result.offset(), result.bindingBytes());return result;
    }
    private static String resource(String name) {
        try (var input = VulkanGasGeometry.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/" + name)) {
            if (input == null) throw new IllegalStateException("Gas geometry shader missing: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Gas geometry shader unavailable", failure); }
    }
    @Override public void close() { bindings.close(); }
}
