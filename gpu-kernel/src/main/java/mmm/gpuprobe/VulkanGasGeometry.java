package mmm.gpuprobe;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static mmm.gpuprobe.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Exact dry presence, PLIC dry ports, and chamber membership for one resident generation. */
final class VulkanGasGeometry implements AutoCloseable {
    record Layout(VulkanLiquidCellVolumes.Layout totals, int masksWordOffset, int occupiedWordOffset,
                  int cellsWordOffset, int planesWordOffset, int microWordOffset, boolean sealedExterior, double cellSize, int liquidFacesWordOffset,
                  VulkanLiquidStateLayout.Column header, VulkanLiquidStateLayout.Column presence,
                  VulkanLiquidStateLayout.Column dryVolumes, VulkanLiquidStateLayout.Column dryMasks,
                  VulkanLiquidStateLayout.Column compact, VulkanLiquidStateLayout.Column faceAreas,
                  VulkanLiquidStateLayout.Column fullConnections, VulkanLiquidStateLayout.Column parents,
                  VulkanLiquidStateLayout.Column exteriorReachable, VulkanLiquidStateLayout.Column cellNodes,
                  VulkanLiquidStateLayout.Column changed, VulkanLiquidStateLayout.Column componentCount,
                  VulkanLiquidStateLayout.Column rootFlags, VulkanLiquidStateLayout.Column rootOffsets,
                  VulkanLiquidStateLayout.Column componentCounts, VulkanLiquidStateLayout.Column componentOffsets,
                  VulkanLiquidStateLayout.Column componentCursors, VulkanLiquidStateLayout.Column componentMembers,
                  VulkanLiquidStateLayout.Column scanWorkspace, VulkanLiquidStateLayout.Column microFractions,
                  VulkanLiquidStateLayout.Column cutFaces,VulkanLiquidStateLayout.Column connectedFaces,
                  VulkanLiquidStateLayout.Column presentCells,VulkanLiquidStateLayout.Column dispatch,long endOffset) {
        /** Shares PLIC port clipping with the liquid MAC face construction, without another workspace. */
        Layout withLiquidFaces(int wordOffset){
            return new Layout(totals,masksWordOffset,occupiedWordOffset,cellsWordOffset,planesWordOffset,microWordOffset,sealedExterior,cellSize,wordOffset,
                    header,presence,dryVolumes,dryMasks,compact,faceAreas,fullConnections,parents,exteriorReachable,cellNodes,changed,componentCount,
                    rootFlags,rootOffsets,componentCounts,componentOffsets,componentCursors,componentMembers,scanWorkspace,microFractions,cutFaces,connectedFaces,presentCells,dispatch,endOffset);
        }
        int headerWordOffset() { return word(header); }
        int cells() { return totals.source().cellCount(); }
        int faces() { return faceAreas.count(); }
        long bytes() { return endOffset - header.offset(); }
        /** Carries a completed chamber geometry into the next source layout. Headers stay local. */
        void copyDerived(VulkanBufferCopyPlan.Builder plan, Layout source) {
            if(cells()!=source.cells()||faces()!=source.faces()||sealedExterior!=source.sealedExterior||Double.compare(cellSize,source.cellSize)!=0)
                throw new IllegalArgumentException("Chamber geometry transfer changes the grid");
            var from=List.of(source.presence,source.dryVolumes,source.dryMasks,source.compact,source.faceAreas,
                    source.fullConnections,source.parents,source.exteriorReachable,source.cellNodes,source.changed,
                    source.componentCount,source.rootFlags,source.rootOffsets,source.componentCounts,source.componentOffsets,source.componentMembers,
                    source.cutFaces,source.connectedFaces,source.presentCells,source.dispatch);
            var to=List.of(presence,dryVolumes,dryMasks,compact,faceAreas,fullConnections,parents,exteriorReachable,cellNodes,changed,
                    componentCount,rootFlags,rootOffsets,componentCounts,componentOffsets,componentMembers,cutFaces,connectedFaces,presentCells,dispatch);
            for(int i=0;i<from.size();i++)plan.copy(from.get(i),to.get(i));
        }
        /** cellNodes: -1 no gas, 0 ambient, 1..componentCount finite chambers.
         * Component CSR includes ambient row 0; unused rows after componentCount are empty.
         * dryVolumes and faceAreas use SI units. Exact dry capacity remains pore minus totals.
         * A finite root r has parents[r]==r and ordinal=rootOffsets[r]+1. Its canonical
         * topology key is 8*(r-1)+numberOfTrailingZeros(dryMasks[r-1]). Members are unsorted.
         */
        void initializeHeader(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
        }
        ByteBuffer headerBytes() {
            var target = ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            int[] data = new int[64];
            data[0] = totals.source().sizeX(); data[1] = totals.source().sizeY(); data[2] = totals.source().sizeZ();
            data[3] = cells(); data[4] = faces(); data[5] = sealedExterior ? 1 : 0;
            data[6] = masksWordOffset; data[7] = occupiedWordOffset; data[8] = cellsWordOffset;
            data[9] = planesWordOffset; data[10] = microWordOffset;
            data[11] = word(totals.totalOffsets()); data[12] = word(totals.totalSigns());
            data[13] = word(totals.totalExponents()); data[14] = word(totals.totalLimbs());
            data[16] = word(presence); data[17] = word(dryVolumes); data[18] = word(dryMasks); data[19] = word(compact);
            data[20] = word(faceAreas); data[21] = word(fullConnections); data[22] = word(parents);
            data[23] = word(exteriorReachable); data[24] = word(cellNodes); data[25] = word(changed); data[26] = word(componentCount);
            data[27] = word(rootFlags); data[28] = word(rootOffsets); data[29] = word(componentCounts);
            data[30] = word(componentOffsets); data[31] = word(componentCursors); data[32] = word(componentMembers);
            data[40]=word(cutFaces);data[41]=word(connectedFaces);data[42]=word(presentCells);data[43]=word(dispatch);
            data[33] = word(microFractions);data[36]=liquidFacesWordOffset;
            data[37]=word(totals.source().column(VulkanLiquidStateLayout.Field.MOMENTUM_X));
            data[38]=word(totals.source().column(VulkanLiquidStateLayout.Field.MOMENTUM_Y));
            data[39]=word(totals.source().column(VulkanLiquidStateLayout.Field.MOMENTUM_Z));
            for (int i = 0; i < data.length; i++) target.putInt(4 * i, data[i]);
            target.putDouble(4 * 34, cellSize);return target;
        }
    }
    static Layout layout(VulkanLiquidCellVolumes.Layout totals, int masksWordOffset, int occupiedWordOffset,
                         int cellsWordOffset, int planesWordOffset, int microWordOffset, boolean sealedExterior,
                         double cellSize, long scratchBase, long alignment) {
        double h3 = cellSize * cellSize * cellSize;
        if (scratchBase < totals.endOffset() || alignment < 8 || (alignment & (alignment - 1)) != 0
                || !(cellSize > 0 && h3 > 0 && Double.isFinite(h3)))
            throw new IllegalArgumentException("Invalid gas geometry layout");
        int cells = totals.source().cellCount(), roots = Math.addExact(cells, 1);
        int sx = totals.source().sizeX(), sy = totals.source().sizeY(), sz = totals.source().sizeZ();
        int faces = Math.toIntExact((long) (sx + 1) * sy * sz + (long) sx * (sy + 1) * sz + (long) sx * sy * (sz + 1));
        long[] cursor = {scratchBase};
        var header = column(cursor, 64, 4, alignment);
        var presence = column(cursor, cells, 4, alignment);
        var volumes = column(cursor, cells, 8, alignment);
        var masks = column(cursor, cells, 4, alignment);
        var compact = column(cursor, cells, 4, alignment);
        var areas = column(cursor, faces, 8, alignment);
        var connections = column(cursor, faces, 4, alignment);
        var parents = column(cursor, roots, 4, alignment);
        var exterior = column(cursor, cells, 4, alignment);
        var nodes = column(cursor, cells, 4, alignment);
        var changed = column(cursor, 1, 4, alignment);
        var count = column(cursor, 1, 4, alignment);
        var flags = column(cursor, roots, 4, alignment);
        var rootOffsets = column(cursor, roots + 1, 4, alignment);
        var componentCounts = column(cursor, roots, 4, alignment);
        var offsets = column(cursor, roots + 1, 4, alignment);
        var cursors = column(cursor, roots, 4, alignment);
        var members = column(cursor, cells, 4, alignment);
        var scan = column(cursor, Math.toIntExact(VulkanPrefixScan.workspaceBytes(roots) / 4), 4, alignment);
        var fractions = column(cursor, Math.multiplyExact(cells,8), 4, alignment);
        var cutFaces=column(cursor,faces,4,alignment);var connectedFaces=column(cursor,faces,4,alignment);
        var presentCells=column(cursor,cells,4,alignment);var dispatch=column(cursor,12,4,alignment);
        return new Layout(totals, masksWordOffset, occupiedWordOffset, cellsWordOffset, planesWordOffset, microWordOffset,
                sealedExterior, cellSize, -1, header, presence, volumes, masks, compact, areas, connections, parents,
                exterior, nodes, changed, count, flags, rootOffsets, componentCounts, offsets, cursors, members,
                scan, fractions,cutFaces,connectedFaces,presentCells,dispatch,align(cursor[0], alignment));
    }
    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    private FlipKernelBindings.BufferRange arena;
    VulkanGasGeometry(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 2,
                resource("gas-geometry.comp").replace("// DYADIC_LIBRARY", resource("liquid-dyadic.glsl")), 20);
        try { scan = new VulkanPrefixScan(context); }
        catch (RuntimeException | LinkageError failure) {
            try { bindings.close(); } catch (RuntimeException | LinkageError closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }
    void rebindRanges(FlipKernelBindings.BufferRange candidate, FlipKernelBindings.BufferRange errors) {
        arena=candidate;bindings.rebindRanges(List.of(candidate, errors));scan.rebindRanges(candidate, candidate, candidate, errors);
    }
    void recordGeometry(VkCommandBuffer command, Layout layout) {
        stage(command, layout, 0, layout.cells() + 1);
        stage(command, layout, 1, layout.cells());
        stage(command, layout, 2, layout.faces());
        stage(command,layout,9,1);stage(command,layout,10,layout.faces());stage(command,layout,11,1);
    }
    /** Root-CAS closes every edge before the stage barrier; one compression publishes final roots. */
    void recordConnectivity(VkCommandBuffer command, Layout layout) {
        stage(command, layout, 3, 1);
        stage(command, layout, 4, layout.faces());
        stage(command, layout, 5, layout.cells());
    }
    /** Requires a completed connectivity batch whose changed value is zero; otherwise reports error 8192. */
    void recordComponents(VkCommandBuffer command, Layout layout) {
        stage(command, layout, 6, layout.cells() + 1);
        scan.record(command, layout.cells() + 1, word(layout.rootFlags()), word(layout.rootOffsets()), word(layout.scanWorkspace()));
        stage(command, layout, 7, layout.cells());
        scan.record(command, layout.cells() + 1, word(layout.componentCounts()), word(layout.componentOffsets()), word(layout.scanWorkspace()));
        stage(command, layout, 8, layout.cells());
    }
    /** Source ownership comes from the committed inventory, not a fresh PLIC partition. */
    void recordSourceComponents(VkCommandBuffer command, Layout layout, VulkanGasStateLayout gas) {
        stage(command,layout,12,layout.cells(),Math.toIntExact(gas.column(VulkanGasStateLayout.Field.PIECE_NODE_ORDINAL).offset()/4),
                Math.toIntExact(gas.column(VulkanGasStateLayout.Field.COUNTS).offset()/4));
        scan.record(command,layout.cells()+1,word(layout.componentCounts()),word(layout.componentOffsets()),word(layout.scanWorkspace()));
        stage(command,layout,8,layout.cells());
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
            if(stage==4||stage==5||stage==10){int slot=stage==10?0:stage==4?1:2;
                vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+layout.dispatch().offset()+12L+12L*slot);
            }else vkCmdDispatch(command, Math.min(groups, 65535), (groups + 65534) / 65535, 1);
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
    @Override public void close() { try { scan.close(); } finally { bindings.close(); } }
}
