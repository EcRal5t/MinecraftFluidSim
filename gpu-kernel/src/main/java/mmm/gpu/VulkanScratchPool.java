package mmm.gpu;

import java.util.ArrayList;
import java.util.List;

/** Reuses sequential operators' temporary columns without overlapping columns within an operator. */
final class VulkanScratchPool {
    static final int LIQUID = 1, SOLID = 2, CONDENSED = 4, GAS = 8, DIFFUSION = 16, THERMAL = 32;
    static final int PHASE_ROWS = LIQUID | SOLID | CONDENSED | GAS | DIFFUSION;
    private record Range(long start, long end, int stages) { }
    private final List<Range> ranges = new ArrayList<>();

    VulkanScratchPool(List<VulkanLiquidStateLayout.Column> reusable) {
        for (var column : reusable)
            if (column.bytes() > 0) ranges.add(new Range(column.offset(), column.offset() + column.bytes(), 0));
        coalesce();
    }

    private void coalesce() {
        ranges.sort(java.util.Comparator.comparingLong(Range::start));
        for (int i = 1; i < ranges.size();) {
            var left = ranges.get(i - 1);
            var right = ranges.get(i);
            if (left.end == right.start && left.stages == right.stages) {
                ranges.set(i - 1, new Range(left.start, right.end, left.stages));
                ranges.remove(i);
            } else i++;
        }
    }

    VulkanLiquidStateLayout.Column allocate(int count, int width, int stage, long cursor, long alignment) {
        long size = Math.max(width, Math.multiplyExact((long) count, width));
        for (int i = 0; i < ranges.size(); i++) {
            var range = ranges.get(i);
            long start = (range.start + 7) & -8L;
            if ((range.stages & stage) != 0 || start > range.end - size) continue;
            ranges.remove(i);
            if (range.start < start) ranges.add(new Range(range.start, start, range.stages));
            ranges.add(new Range(start, start + size, range.stages | stage));
            if (start + size < range.end) ranges.add(new Range(start + size, range.end, range.stages));
            coalesce();
            return new VulkanLiquidStateLayout.Column(start, count, width);
        }
        long start = Math.addExact(cursor, alignment - 1) & -alignment;
        ranges.add(new Range(start, Math.addExact(start, size), stage));
        coalesce();
        return new VulkanLiquidStateLayout.Column(start, count, width);
    }
}
