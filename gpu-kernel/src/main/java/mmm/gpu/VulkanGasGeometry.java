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
        OCCUPIED(7),
        CELLS(8),
        PLANES(9),
        MICRO(10),
        TOTAL_OFFSETS(11),
        TOTAL_SIGNS(12),
        TOTAL_EXPONENTS(13),
        TOTAL_LIMBS(14),
        PRESENCE(16),
        DRY_VOLUMES(17),
        DRY_MASKS(18),
        COMPACT(19),
        FACE_AREAS(20),
        FULL_CONNECTIONS(21),
        PARENTS(22),
        EXTERIOR_REACHABLE(23),
        CELL_NODES(24),
        CHANGED(25),
        COMPONENT_COUNT(26),
        ROOT_FLAGS(27),
        ROOT_OFFSETS(28),
        COMPONENT_COUNTS(29),
        COMPONENT_OFFSETS(30),
        COMPONENT_CURSORS(31),
        COMPONENT_MEMBERS(32),
        MICRO_FRACTIONS(33),
        CELL_SIZE(34),
        LIQUID_FACES(36),
        MOMENTUM_X(37),
        MOMENTUM_Y(38),
        MOMENTUM_Z(39),
        CUT_FACES(40),
        CONNECTED_FACES(41),
        PRESENT_CELLS(42),
        DISPATCH(43);
        final int word;
        Header(int word) { this.word = word; }
    }
    static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define GEO_H_").append(field.name()).append(' ').append(field.word).append('\n');
        return result.toString();
    }

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
            data[Header.SIZE_X.word] = totals.source().sizeX(); data[Header.SIZE_Y.word] = totals.source().sizeY(); data[Header.SIZE_Z.word] = totals.source().sizeZ();
            data[Header.CELL_COUNT.word] = cells(); data[Header.FACE_COUNT.word] = faces(); data[Header.SEALED_EXTERIOR.word] = sealedExterior ? 1 : 0;
            data[Header.MASKS.word] = masksWordOffset; data[Header.OCCUPIED.word] = occupiedWordOffset; data[Header.CELLS.word] = cellsWordOffset;
            data[Header.PLANES.word] = planesWordOffset; data[Header.MICRO.word] = microWordOffset;
            data[Header.TOTAL_OFFSETS.word] = word(totals.totalOffsets()); data[Header.TOTAL_SIGNS.word] = word(totals.totalSigns());
            data[Header.TOTAL_EXPONENTS.word] = word(totals.totalExponents()); data[Header.TOTAL_LIMBS.word] = word(totals.totalLimbs());
            data[Header.PRESENCE.word] = word(presence); data[Header.DRY_VOLUMES.word] = word(dryVolumes); data[Header.DRY_MASKS.word] = word(dryMasks); data[Header.COMPACT.word] = word(compact);
            data[Header.FACE_AREAS.word] = word(faceAreas); data[Header.FULL_CONNECTIONS.word] = word(fullConnections); data[Header.PARENTS.word] = word(parents);
            data[Header.EXTERIOR_REACHABLE.word] = word(exteriorReachable); data[Header.CELL_NODES.word] = word(cellNodes); data[Header.CHANGED.word] = word(changed); data[Header.COMPONENT_COUNT.word] = word(componentCount);
            data[Header.ROOT_FLAGS.word] = word(rootFlags); data[Header.ROOT_OFFSETS.word] = word(rootOffsets); data[Header.COMPONENT_COUNTS.word] = word(componentCounts);
            data[Header.COMPONENT_OFFSETS.word] = word(componentOffsets); data[Header.COMPONENT_CURSORS.word] = word(componentCursors); data[Header.COMPONENT_MEMBERS.word] = word(componentMembers);
            data[Header.CUT_FACES.word]=word(cutFaces);data[Header.CONNECTED_FACES.word]=word(connectedFaces);data[Header.PRESENT_CELLS.word]=word(presentCells);data[Header.DISPATCH.word]=word(dispatch);
            data[Header.MICRO_FRACTIONS.word] = word(microFractions);data[Header.LIQUID_FACES.word]=liquidFacesWordOffset;
            data[Header.MOMENTUM_X.word]=word(totals.source().column(VulkanLiquidStateLayout.Field.MOMENTUM_X));
            data[Header.MOMENTUM_Y.word]=word(totals.source().column(VulkanLiquidStateLayout.Field.MOMENTUM_Y));
            data[Header.MOMENTUM_Z.word]=word(totals.source().column(VulkanLiquidStateLayout.Field.MOMENTUM_Z));
            for (int i = 0; i < data.length; i++) target.putInt(4 * i, data[i]);
            target.putDouble(4 * Header.CELL_SIZE.word, cellSize);return target;
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
                resource("gas-geometry.comp").replace("// HOST_LAYOUT", shaderLayout()).replace("// DYADIC_LIBRARY", resource("liquid-dyadic.glsl")).replace("// PLIC_FACE_LIBRARY", resource("plic-face.glsl")), 20);
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
