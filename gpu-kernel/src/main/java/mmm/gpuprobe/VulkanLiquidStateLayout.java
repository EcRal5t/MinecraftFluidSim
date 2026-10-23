package mmm.gpuprobe;

import mmm.fluid.MacMomentumState;
import mmm.inventory.DeviceLiquidInventory;
import mmm.units.DeviceDyadicArray;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

/** Aligned structure-of-arrays schema for a device-resident liquid generation.
 * Integers use little endian words; double columns use IEEE binary64. Dyadic limbs
 * are unsigned uint32, least significant first, with signed int32 exponents/signs.
 * Offsets in both CSR tables and the limb table count elements, not bytes.
 */
public final class VulkanLiquidStateLayout {
    public enum Field {
        COUNTS(4), CELL_OFFSETS(4), MATERIALS(4), SUPPORT_MASKS(4),
        VOLUME_OFFSETS(4), VOLUME_SIGNS(4), VOLUME_EXPONENTS(4), VOLUME_LIMBS(4),
        MASS_UNITS(8), ENERGY_BITS(8), MIXTURE_PRESENT(4),
        SPECIES_OFFSETS(4), SPECIES_HANDLES(4), SPECIES_AMOUNTS(8), MASS_RESIDUAL_UNITS(8),
        NUMERIC_VOLUMES(8), MOMENTUM_X(8), MOMENTUM_Y(8), MOMENTUM_Z(8);
        private final int elementBytes;
        Field(int elementBytes) { this.elementBytes = elementBytes; }
        public int elementBytes() { return elementBytes; }
    }

    /** Empty columns reserve one element so every descriptor range remains valid. */
    public record Column(long offset, int count, int elementBytes) {
        public long bytes() { return (long) count * elementBytes; }
        public long bindingBytes() { return Math.max(elementBytes, bytes()); }
    }

    public record State(DeviceLiquidInventory inventory, MacMomentumState momentum) { }

    private final int sizeX, sizeY, sizeZ, stockCapacity, speciesCapacity, limbCapacity;
    private final long alignment, bytes;
    private final EnumMap<Field, Column> columns = new EnumMap<>(Field.class);

    public VulkanLiquidStateLayout(int sizeX, int sizeY, int sizeZ, int stockCapacity,
                                   int speciesCapacity, int limbCapacity, long storageAlignment) {
        if (sizeX < 2 || sizeY < 2 || sizeZ < 2 || stockCapacity < 0 || speciesCapacity < 0
                || limbCapacity < 0 || storageAlignment <= 0 || (storageAlignment & (storageAlignment - 1)) != 0)
            throw new IllegalArgumentException("Invalid resident liquid shape or storage alignment");
        this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
        this.stockCapacity = stockCapacity; this.speciesCapacity = speciesCapacity; this.limbCapacity = limbCapacity;
        alignment = Math.max(8, storageAlignment);
        long cursor = 0;
        for (Field field : Field.values()) {
            int count = switch (field) {
                case COUNTS -> 3;
                case CELL_OFFSETS -> Math.addExact(cellCount(), 1);
                case VOLUME_OFFSETS, SPECIES_OFFSETS -> Math.addExact(stockCapacity, 1);
                case VOLUME_LIMBS -> limbCapacity;
                case SPECIES_HANDLES, SPECIES_AMOUNTS -> speciesCapacity;
                case MOMENTUM_X -> product(sizeX + 1, sizeY, sizeZ);
                case MOMENTUM_Y -> product(sizeX, sizeY + 1, sizeZ);
                case MOMENTUM_Z -> product(sizeX, sizeY, sizeZ + 1);
                default -> stockCapacity;
            };
            cursor = align(cursor);
            var column = new Column(cursor, count, field.elementBytes);
            columns.put(field, column);
            cursor = Math.addExact(cursor, column.bindingBytes());
        }
        bytes = align(cursor);
    }

    public static VulkanLiquidStateLayout forState(DeviceLiquidInventory inventory,
                                                   MacMomentumState momentum, long storageAlignment) {
        return new VulkanLiquidStateLayout(momentum.sizeX(), momentum.sizeY(), momentum.sizeZ(),
                inventory.stockCount(), inventory.speciesHandles().length, inventory.volumes().limbs().length,
                storageAlignment);
    }

    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public int cellCount() { return product(sizeX, sizeY, sizeZ); }
    public int stockCapacity() { return stockCapacity; }
    public int speciesCapacity() { return speciesCapacity; }
    public int limbCapacity() { return limbCapacity; }
    public long bytes() { return bytes; }
    public long alignment() { return alignment; }
    public Column column(Field field) { return columns.get(field); }

    List<FlipKernelBindings.BufferRange> ranges(GpuBuffer buffer, Field... fields) {
        return java.util.Arrays.stream(fields).map(field -> {
            var column = column(field);
            return new FlipKernelBindings.BufferRange(buffer, column.offset(), column.bindingBytes());
        }).toList();
    }

    /** Writes one-time initialization/checkpoint data; destination position is preserved. */
    public void importState(ByteBuffer destination, DeviceLiquidInventory inventory, MacMomentumState momentum) {
        if (inventory.cellCount() != cellCount() || inventory.stockCount() > stockCapacity
                || inventory.speciesHandles().length > speciesCapacity || inventory.volumes().limbs().length > limbCapacity
                || momentum.sizeX() != sizeX || momentum.sizeY() != sizeY || momentum.sizeZ() != sizeZ)
            throw new IllegalArgumentException("Liquid import shape differs from resident layout");
        ByteBuffer out = view(destination);
        var dyadic = inventory.volumes();
        put(out, Field.COUNTS, new int[]{inventory.stockCount(), inventory.speciesHandles().length, dyadic.limbs().length});
        put(out, Field.CELL_OFFSETS, inventory.cellOffsets());
        put(out, Field.MATERIALS, inventory.materials());
        put(out, Field.SUPPORT_MASKS, inventory.supportMasks());
        put(out, Field.VOLUME_OFFSETS, dyadic.offsets());
        put(out, Field.VOLUME_SIGNS, dyadic.signs());
        put(out, Field.VOLUME_EXPONENTS, dyadic.exponents());
        put(out, Field.VOLUME_LIMBS, dyadic.limbs());
        put(out, Field.MASS_UNITS, inventory.massUnits());
        put(out, Field.ENERGY_BITS, inventory.energyBits());
        put(out, Field.MIXTURE_PRESENT, inventory.mixturePresent());
        put(out, Field.SPECIES_OFFSETS, inventory.speciesOffsets());
        put(out, Field.SPECIES_HANDLES, inventory.speciesHandles());
        put(out, Field.SPECIES_AMOUNTS, inventory.speciesAmounts());
        put(out, Field.MASS_RESIDUAL_UNITS, inventory.massResidualUnits());
        double[] numeric = new double[inventory.stockCount()];
        for (int i = 0; i < numeric.length; i++) numeric[i] = dyadic.valueAt(i).toDouble();
        put(out, Field.NUMERIC_VOLUMES, numeric);
        put(out, Field.MOMENTUM_X, momentum.copyX());
        put(out, Field.MOMENTUM_Y, momentum.copyY());
        put(out, Field.MOMENTUM_Z, momentum.copyZ());
    }

    /** Reconstructs exact authoritative columns after an explicit device checkpoint read. */
    public State exportState(ByteBuffer source) {
        ByteBuffer in = view(source);
        int[] counts = ints(in, Field.COUNTS, 3);
        int stocks = counts[0], species = counts[1], limbs = counts[2];
        if (stocks < 0 || stocks > stockCapacity || species < 0 || species > speciesCapacity
                || limbs < 0 || limbs > limbCapacity)
            throw new IllegalArgumentException("Device liquid counts exceed resident capacity");
        var volumes = new DeviceDyadicArray(ints(in, Field.VOLUME_OFFSETS, stocks + 1), ints(in, Field.VOLUME_SIGNS, stocks),
                ints(in, Field.VOLUME_EXPONENTS, stocks), ints(in, Field.VOLUME_LIMBS, limbs));
        var inventory = new DeviceLiquidInventory(ints(in, Field.CELL_OFFSETS, cellCount() + 1), ints(in, Field.MATERIALS, stocks),
                ints(in, Field.SUPPORT_MASKS, stocks), volumes, longs(in, Field.MASS_UNITS, stocks), longs(in, Field.ENERGY_BITS, stocks),
                ints(in, Field.MIXTURE_PRESENT, stocks), ints(in, Field.SPECIES_OFFSETS, stocks + 1), ints(in, Field.SPECIES_HANDLES, species),
                longs(in, Field.SPECIES_AMOUNTS, species), longs(in, Field.MASS_RESIDUAL_UNITS, stocks));
        return new State(inventory, new MacMomentumState(sizeX, sizeY, sizeZ,
                doubles(in, Field.MOMENTUM_X), doubles(in, Field.MOMENTUM_Y), doubles(in, Field.MOMENTUM_Z)));
    }

    /** Records column relocation during capacity growth; caller supplies transfer synchronization. */
    void copyColumns(VkCommandBuffer command, VulkanLiquidStateLayout oldLayout, GpuBuffer source,
                     GpuBuffer output, MemoryStack stack) {
        if (sizeX != oldLayout.sizeX || sizeY != oldLayout.sizeY || sizeZ != oldLayout.sizeZ
                || stockCapacity < oldLayout.stockCapacity || speciesCapacity < oldLayout.speciesCapacity
                || limbCapacity < oldLayout.limbCapacity)
            throw new IllegalArgumentException("Liquid growth must retain the source grid and column capacities");
        var copies = VkBufferCopy.calloc(Field.values().length, stack);
        int count = 0;
        for (Field field : Field.values()) {
            Column oldColumn = oldLayout.column(field), nextColumn = column(field);
            long copyBytes = Math.min(oldColumn.bytes(), nextColumn.bytes());
            if (copyBytes != 0) copies.get(count++).srcOffset(oldColumn.offset()).dstOffset(nextColumn.offset()).size(copyBytes);
        }
        copies.limit(count);
        vkCmdCopyBuffer(command, source.handle(), output.handle(), copies);
    }

    private ByteBuffer view(ByteBuffer buffer) {
        if (bytes > buffer.remaining()) throw new IllegalArgumentException("Resident liquid buffer is too small");
        return buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
    }
    private ByteBuffer at(ByteBuffer buffer, Field field, int count) {
        var column = column(field);
        if (count < 0 || count > column.count()) throw new IllegalArgumentException("Liquid column count differs: " + field);
        return buffer.slice(Math.toIntExact(column.offset()), Math.toIntExact((long) count * column.elementBytes())).order(ByteOrder.LITTLE_ENDIAN);
    }
    private void put(ByteBuffer out, Field field, int[] values) { at(out, field, values.length).asIntBuffer().put(values); }
    private void put(ByteBuffer out, Field field, long[] values) { at(out, field, values.length).asLongBuffer().put(values); }
    private void put(ByteBuffer out, Field field, double[] values) { at(out, field, values.length).asDoubleBuffer().put(values); }
    private int[] ints(ByteBuffer in, Field field, int count) {
        int[] values = new int[count]; at(in, field, values.length).asIntBuffer().get(values); return values;
    }
    private long[] longs(ByteBuffer in, Field field, int count) {
        long[] values = new long[count]; at(in, field, values.length).asLongBuffer().get(values); return values;
    }
    private double[] doubles(ByteBuffer in, Field field) {
        double[] values = new double[column(field).count()]; at(in, field, values.length).asDoubleBuffer().get(values); return values;
    }
    private long align(long value) { return Math.addExact(value, alignment - 1) & -alignment; }
    private static int product(int x, int y, int z) { return Math.multiplyExact(Math.multiplyExact(x, y), z); }
}
