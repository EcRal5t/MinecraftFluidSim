package mmm.gpuprobe;

import java.util.EnumMap;
import java.util.List;

/** Compact accepted routes grouped by source stock. SOURCE_OFFSETS addresses actual
 * route rows, SLOTS identifies retain(0) or one of six neighbours(1..6).
 * Capacity reserves the seven possible destinations; only live rows carry inventory.
 */
public final class VulkanLiquidRouteLayout {
    public static final int ROUTES_PER_STOCK = 7;

    public enum Field {
        VOLUME_OFFSETS(4), VOLUME_SIGNS(4), VOLUME_EXPONENTS(4), VOLUME_LIMBS(4),
        MASS_UNITS(8), ENERGY_BITS(8), MIXTURE_PRESENT(4),
        SPECIES_OFFSETS(4), SPECIES_HANDLES(4), SPECIES_AMOUNTS(8),
        SOURCE_OFFSETS(4), SLOTS(4), SOURCE_STOCKS(4), COUNTS(4), SOURCE_MASKS(4), DISPATCH(4);
        private final int elementBytes;
        Field(int elementBytes) { this.elementBytes = elementBytes; }
        public int elementBytes() { return elementBytes; }
    }

    private final int sourceStockCapacity, routeCapacity, speciesCapacity, limbCapacity;
    private final long baseOffset, alignment, endOffset;
    private final EnumMap<Field, VulkanLiquidStateLayout.Column> columns = new EnumMap<>(Field.class);

    public VulkanLiquidRouteLayout(int sourceStockCapacity, int speciesCapacity, int limbCapacity,
                                   long baseOffset, long storageAlignment) {
        if (sourceStockCapacity < 0 || speciesCapacity < 0 || limbCapacity < 0 || baseOffset < 0
                || storageAlignment <= 0 || (storageAlignment & (storageAlignment - 1)) != 0)
            throw new IllegalArgumentException("Invalid liquid route capacity or storage alignment");
        this.sourceStockCapacity = sourceStockCapacity;
        this.speciesCapacity = speciesCapacity; this.limbCapacity = limbCapacity;
        routeCapacity = Math.multiplyExact(sourceStockCapacity, ROUTES_PER_STOCK);
        alignment = Math.max(8, storageAlignment);
        this.baseOffset = align(baseOffset);
        long cursor = this.baseOffset;
        for (Field field : Field.values()) {
            int count = switch (field) {
                case VOLUME_OFFSETS, SPECIES_OFFSETS -> Math.addExact(routeCapacity, 1);
                case SOURCE_OFFSETS -> Math.addExact(sourceStockCapacity,1);
                case COUNTS -> 1;
                case DISPATCH -> 3;
                case SOURCE_MASKS -> sourceStockCapacity;
                case VOLUME_LIMBS -> limbCapacity;
                case SPECIES_HANDLES, SPECIES_AMOUNTS -> speciesCapacity;
                default -> routeCapacity;
            };
            cursor = align(cursor);
            var column = new VulkanLiquidStateLayout.Column(cursor, count, field.elementBytes);
            columns.put(field, column);
            cursor = Math.addExact(cursor, column.bindingBytes());
        }
        endOffset = align(cursor);
    }

    public int sourceStockCapacity() { return sourceStockCapacity; }
    public int routeCapacity() { return routeCapacity; }
    public int speciesCapacity() { return speciesCapacity; }
    public int limbCapacity() { return limbCapacity; }
    public long baseOffset() { return baseOffset; }
    public long bytes() { return endOffset - baseOffset; }
    public long endOffset() { return endOffset; }
    public long alignment() { return alignment; }
    public VulkanLiquidStateLayout.Column column(Field field) { return columns.get(field); }

    List<FlipKernelBindings.BufferRange> ranges(GpuBuffer buffer, Field... fields) {
        return java.util.Arrays.stream(fields).map(field -> {
            var column = column(field);
            return new FlipKernelBindings.BufferRange(buffer, column.offset(), column.bindingBytes());
        }).toList();
    }

    private long align(long value) { return Math.addExact(value, alignment - 1) & -alignment; }
}
