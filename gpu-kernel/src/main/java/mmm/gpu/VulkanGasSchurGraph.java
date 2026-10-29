package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;

/** Compact physical pressure graph copied between exact Schur layers. */
final class VulkanGasSchurGraph {
    enum Field{HEADER(4),COUNTS(4),SOFT(8),SOURCE(8),ROOT(8),INITIAL(8),RESULT(8),PAIR_A(4),PAIR_B(4),WEIGHTS(8),
        NODE_COUNTS(4),NODE_OFFSETS(4),NODE_CURSORS(4),NODE_PAIRS(4),LONG_ROWS(4),LONG_COUNTS(4),DISPATCH(4),CONTROL(8),TARGET(8),SCAN(4);
        final int width;Field(int width){this.width=width;}}
    final int nodes,pairs;final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);final long endOffset;
    VulkanGasSchurGraph(int nodes,int pairs,long alignment){this.nodes=nodes;this.pairs=pairs;long cursor=0;
        for(var f:Field.values()){int count=switch(f){case HEADER->128;case COUNTS->2;case PAIR_A,PAIR_B,WEIGHTS->pairs;case NODE_OFFSETS->nodes+1;case NODE_PAIRS->Math.multiplyExact(2,pairs);case LONG_COUNTS->3;case DISPATCH->24;case CONTROL->48;case TARGET->1;case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(nodes)/4);default->nodes;};
            long at=align(cursor,alignment);var c=new VulkanLiquidStateLayout.Column(at,count,f.width);columns.put(f,c);cursor=at+c.bindingBytes();}endOffset=align(cursor,alignment);}
    VulkanLiquidStateLayout.Column column(Field f){return columns.get(f);}int word(Field f){return Math.toIntExact(column(f).offset()/4);}
    ByteBuffer headerBytes(){var b=ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);int[] h=new int[128];h[VulkanGasFlow.Header.NODE_CAPACITY.word]=nodes;h[VulkanGasFlow.Header.PAIR_CAPACITY.word]=pairs;
        h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.PAIR_A.ordinal()]=word(Field.PAIR_A);h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.PAIR_B.ordinal()]=word(Field.PAIR_B);h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.PAIR_G.ordinal()]=word(Field.WEIGHTS);
        h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.NODE_COUNTS.ordinal()]=word(Field.NODE_COUNTS);h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.NODE_OFFSETS.ordinal()]=word(Field.NODE_OFFSETS);h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.NODE_CURSORS.ordinal()]=word(Field.NODE_CURSORS);h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.NODE_PAIRS.ordinal()]=word(Field.NODE_PAIRS);
        h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.STORAGE.ordinal()]=word(Field.SOFT);h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.DIAGONAL_ROOT.ordinal()]=word(Field.ROOT);h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.PRESSURE.ordinal()]=word(Field.RESULT);
        h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.LONG_ROWS.ordinal()]=word(Field.LONG_ROWS);h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.LONG_COUNTS.ordinal()]=word(Field.LONG_COUNTS);h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.DISPATCH.ordinal()]=word(Field.DISPATCH);h[VulkanGasFlow.WORK_FIELDS+VulkanGasFlow.Field.CONTROL.ordinal()]=word(Field.CONTROL);
        for(int i=0;i<h.length;i++)b.putInt(4*i,h[i]);return b;}
    private static long align(long value,long alignment){return Math.addExact(value,alignment-1)&-alignment;}
}
