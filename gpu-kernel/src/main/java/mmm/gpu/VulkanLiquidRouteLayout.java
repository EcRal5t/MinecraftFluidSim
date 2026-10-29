package mmm.gpu;

import java.util.EnumMap;
import java.util.List;

/** Accepted-direction candidates grouped by source stock. SOURCE_OFFSETS and SOURCE_MASKS
 * address the shared material scratch and quantity rows. VOLUME_DESCRIPTORS borrows
 * immutable source/accepted limbs or references the candidate's own material workspace.
 * Species amounts use seven real direction slots per immutable source species entry;
 * their handles and CSR ranges remain in the source inventory.
 * Zero-volume candidates carry zero quantities and are skipped by destination gathering.
 */
public final class VulkanLiquidRouteLayout {
    public static final int ROUTES_PER_STOCK = 7;

    public enum Field {
        VOLUME_DESCRIPTORS(4),
        MASS_UNITS(8), ENERGY_BITS(8), MIXTURE_PRESENT(4),
        SPECIES_AMOUNTS(8), SOURCE_OFFSETS(4), SLOTS(4), COUNTS(4), SOURCE_MASKS(4), SPECIES_REQUIRED(4), INTEGER_VOLUMES(8);
        private final int elementBytes;
        Field(int elementBytes) { this.elementBytes = elementBytes; }
        public int elementBytes() { return elementBytes; }
    }

    private final int sourceStockCapacity, routeCapacity, speciesCapacity;
    private final long baseOffset, alignment, endOffset;
    private final EnumMap<Field, VulkanLiquidStateLayout.Column> columns = new EnumMap<>(Field.class);

    public VulkanLiquidRouteLayout(int sourceStockCapacity, int speciesCapacity,
                                   long baseOffset, long storageAlignment) {
        if (sourceStockCapacity < 0 || speciesCapacity < 0 || baseOffset < 0
                || storageAlignment <= 0 || (storageAlignment & (storageAlignment - 1)) != 0)
            throw new IllegalArgumentException("Invalid liquid route capacity or storage alignment");
        this.sourceStockCapacity = sourceStockCapacity;
        this.speciesCapacity = speciesCapacity;
        routeCapacity = Math.multiplyExact(sourceStockCapacity, ROUTES_PER_STOCK);
        alignment = Math.max(8, storageAlignment);
        this.baseOffset = align(baseOffset);
        long cursor = this.baseOffset;
        for (Field field : Field.values()) {
            int count = switch (field) {
                case SOURCE_OFFSETS -> Math.addExact(sourceStockCapacity,1);
                case COUNTS -> 2;
                case SPECIES_REQUIRED -> 1;
                case VOLUME_DESCRIPTORS -> Math.multiplyExact(routeCapacity,5);
                case SOURCE_MASKS -> sourceStockCapacity;
                case SPECIES_AMOUNTS -> speciesCapacity;
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
