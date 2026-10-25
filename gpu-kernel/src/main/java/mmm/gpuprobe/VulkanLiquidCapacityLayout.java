package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;

/** Resident six-neighbor transport and finite-group volume budgets.
 * Receiver bounds are absolute integer volumes after removing the retained cell tail.
 * Group -1 is unrestricted; other group IDs address GROUP_CAPACITY.
 */
final class VulkanLiquidCapacityLayout {
    enum Field {
        K(8), LOWER(8), UPPER(8), GROUP_ID(4), GROUP_CAPACITY(8), PREFERRED(8), OPEN_NEIGHBORS(4),
        ACCEPTED(8), CONTROL(4), HEADER(4), FLOW(8);
        final int width;Field(int width){this.width=width;}
    }
    static final int HEADER_WORDS = 32, CONTROL_WORDS = 12;
    private final int sx, sy, sz, cells, gasGroups, groups, vertices, edges;
    private final long baseOffset, endOffset;
    private final EnumMap<Field, VulkanLiquidStateLayout.Column> columns = new EnumMap<>(Field.class);

    VulkanLiquidCapacityLayout(int sx, int sy, int sz, int gasGroups, long baseOffset, long alignment) {
        if (sx < 1 || sy < 1 || sz < 1 || gasGroups < 0 || baseOffset < 0 || alignment < 8
                || (alignment & (alignment - 1)) != 0)
            throw new IllegalArgumentException("Invalid resident liquid capacity layout");
        this.sx = sx; this.sy = sy; this.sz = sz; this.gasGroups = gasGroups;
        cells = Math.multiplyExact(Math.multiplyExact(sx, sy), sz);
        if (gasGroups > cells) throw new IllegalArgumentException("More finite groups than cells");
        groups = Math.addExact(gasGroups, 1);
        vertices = cells;
        edges = Math.multiplyExact(7, cells);
        this.baseOffset = align(baseOffset, alignment);
        long cursor = this.baseOffset;
        for (Field field : Field.values()) {
            int count = switch (field) {
                case GROUP_CAPACITY -> gasGroups;
                case PREFERRED, ACCEPTED, FLOW -> Math.multiplyExact(7,cells);
                case CONTROL -> CONTROL_WORDS;
                case HEADER -> HEADER_WORDS;
                default -> cells;
            };
            cursor = align(cursor, alignment);
            var column = new VulkanLiquidStateLayout.Column(cursor, count, field.width);
            columns.put(field, column);
            cursor = Math.addExact(cursor, column.bindingBytes());
        }
        endOffset = align(cursor, alignment);
        Math.toIntExact(endOffset / 4);
    }

    VulkanLiquidStateLayout.Column column(Field field) { return columns.get(field); }
    int word(Field field) { return Math.toIntExact(column(field).offset() / 4); }
    int cells() { return cells; }
    int groups() { return groups; }
    int gasGroups() { return gasGroups; }
    int vertices() { return vertices; }
    int edges() { return edges; }
    long baseOffset() { return baseOffset; }
    long bytes() { return endOffset - baseOffset; }
    long endOffset() { return endOffset; }
    long maximumCellQuantity() { return 0x3fffffffffffffffL / cells; }

    /** Header and input columns are uploaded once or populated by earlier device kernels. */
    void initializeHeader(ByteBuffer arena) {
        arena.duplicate().position(Math.toIntExact(column(Field.HEADER).offset())).put(headerBytes());
    }
    ByteBuffer headerBytes() {
        var output = ByteBuffer.allocateDirect(Math.toIntExact(column(Field.HEADER).bytes())).order(ByteOrder.LITTLE_ENDIAN);
        int[] header = new int[HEADER_WORDS];
        header[0] = sx; header[1] = sy; header[2] = sz; header[3] = cells;
        header[4] = gasGroups; header[5] = groups; header[6] = vertices; header[7] = edges;
        for (Field field : Field.values()) header[8 + field.ordinal()] = word(field);
        for (int i = 0; i < header.length; i++)
            output.putInt(Math.toIntExact(0 + 4L * i), header[i]);
    
        return output;
    }

    private static long align(long value, long alignment) { return Math.addExact(value, alignment - 1) & -alignment; }
}
