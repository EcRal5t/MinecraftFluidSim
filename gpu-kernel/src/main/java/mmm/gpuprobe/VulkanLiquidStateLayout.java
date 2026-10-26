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
 * Cell/species offsets use CSR. Volume offsets and counts independently address
 * four-word inline slots or the shared overflow tail; offsets[stockCapacity] holds total demand.
 */
public final class VulkanLiquidStateLayout {
    public enum Field {
        COUNTS(4), CELL_OFFSETS(4), MATERIALS(4), SUPPORT_MASKS(4),
        VOLUME_OFFSETS(4), VOLUME_COUNTS(4), VOLUME_EXPONENTS(4), VOLUME_LIMBS(4),
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

    private final int sizeX, sizeY, sizeZ, stockCapacity, speciesCapacity, limbCapacity, materialCount;
    private final long alignment, bytes;
    private final EnumMap<Field, Column> columns = new EnumMap<>(Field.class);

    public VulkanLiquidStateLayout(int sizeX, int sizeY, int sizeZ, int stockCapacity,
                                   int speciesCapacity, int limbCapacity, long storageAlignment, int materialCount) {
        if (sizeX < 2 || sizeY < 2 || sizeZ < 2 || stockCapacity < 0 || speciesCapacity < 0
                || materialCount < 1 || limbCapacity < 0 || storageAlignment <= 0 || (storageAlignment & (storageAlignment - 1)) != 0)
            throw new IllegalArgumentException("Invalid resident liquid shape or storage alignment");
        this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
        this.materialCount=materialCount;
        stockCapacity=Math.max(stockCapacity,Math.multiplyExact(cellCount(),materialCount));
        this.stockCapacity = stockCapacity; this.speciesCapacity = speciesCapacity; this.limbCapacity = Math.max(limbCapacity, Math.multiplyExact(4, stockCapacity));
        alignment = Math.max(8, storageAlignment);
        long cursor = 0;
        for (Field field : Field.values()) {
            int count = switch (field) {
                case COUNTS -> 3;
                case CELL_OFFSETS -> Math.addExact(cellCount(), 1);
                case VOLUME_OFFSETS, SPECIES_OFFSETS -> Math.addExact(stockCapacity, 1);
                case VOLUME_LIMBS -> this.limbCapacity;
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
        int materials=1;for(int material:inventory.materials())materials=Math.max(materials,material+1);
        return forState(inventory,momentum,storageAlignment,materials);
    }
    public static VulkanLiquidStateLayout forState(DeviceLiquidInventory inventory,
                                                   MacMomentumState momentum,long storageAlignment,int materialCount) {
        int slots=Math.multiplyExact(inventory.cellCount(),materialCount);
        var provisional=new VulkanLiquidStateLayout(momentum.sizeX(),momentum.sizeY(),momentum.sizeZ(),slots,
                inventory.speciesHandles().length,Math.multiplyExact(4,slots),storageAlignment,materialCount);
        var slotted=provisional.slotInventory(inventory);
        return new VulkanLiquidStateLayout(momentum.sizeX(),momentum.sizeY(),momentum.sizeZ(),slots,
                slotted.speciesHandles().length,requiredVolumeCapacity(slotted,slots),storageAlignment,materialCount);
    }
    public int materialCount(){return materialCount;}
    public int slotCount(){return Math.multiplyExact(cellCount(),materialCount);}

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
        inventory=slotInventory(inventory);
        if (inventory.cellCount() != cellCount() || inventory.stockCount() > stockCapacity
                || inventory.speciesHandles().length > speciesCapacity || inventory.volumes().limbs().length > limbCapacity
                || momentum.sizeX() != sizeX || momentum.sizeY() != sizeY || momentum.sizeZ() != sizeZ)
            throw new IllegalArgumentException("Liquid import shape differs from resident layout");
        ByteBuffer out = view(destination);
        var dyadic = inventory.volumes();
        var encoded = encodeVolumes(inventory);
        put(out, Field.COUNTS, new int[]{inventory.stockCount(), inventory.speciesHandles().length, encoded.used()});
        put(out, Field.CELL_OFFSETS, inventory.cellOffsets());
        put(out, Field.MATERIALS, inventory.materials());
        put(out, Field.SUPPORT_MASKS, inventory.supportMasks());
        put(out, Field.VOLUME_OFFSETS, encoded.offsets());
        put(out, Field.VOLUME_COUNTS, encoded.counts());
        put(out, Field.VOLUME_EXPONENTS, dyadic.exponents());
        put(out, Field.VOLUME_LIMBS, encoded.limbs());
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

    /** Expands the cold transfer inventory into permanent cell/material slots. */
    public DeviceLiquidInventory slotInventory(DeviceLiquidInventory inventory) {
        if(inventory.cellCount()!=cellCount())throw new IllegalArgumentException("Liquid slot grid differs");
        int slots=slotCount();
        int[] oldCells=inventory.cellOffsets(),oldMaterials=inventory.materials();
        if(inventory.stockCount()==slots){
            boolean fixed=true;
            for(int c=0;c<=cellCount();c++)if(oldCells[c]!=c*materialCount){fixed=false;break;}
            for(int row=0;fixed&&row<slots;row++)if(oldMaterials[row]!=row%materialCount)fixed=false;
            if(fixed)return inventory;
        }
        int[] cells=new int[cellCount()+1],materials=new int[slots],supports=new int[slots],present=new int[slots],speciesOffsets=new int[slots+1];
        long[] mass=new long[slots],energy=new long[slots],residual=new long[slots];
        var volumes=new java.util.ArrayList<mmm.units.SignedDyadic>(java.util.Collections.nCopies(slots,mmm.units.SignedDyadic.ZERO));
        var mixtures=new java.util.HashMap<Integer,java.util.TreeMap<Integer,Long>>();
        int[] oldSupports=inventory.supportMasks(),oldPresent=inventory.mixturePresent(),oldOffsets=inventory.speciesOffsets(),oldHandles=inventory.speciesHandles();
        long[] oldMass=inventory.massUnits(),oldEnergy=inventory.energyBits(),oldResidual=inventory.massResidualUnits(),oldAmounts=inventory.speciesAmounts();var oldVolumes=inventory.volumes();
        for(int c=0;c<cellCount();c++){
            cells[c+1]=(c+1)*materialCount;
            for(int m=0;m<materialCount;m++)materials[c*materialCount+m]=m;
            for(int row=oldCells[c];row<oldCells[c+1];row++){
                int material=oldMaterials[row];if(material<0||material>=materialCount)throw new IllegalArgumentException("Liquid material exceeds bound palette");
                int slot=c*materialCount+material;
                volumes.set(slot,volumes.get(slot).add(oldVolumes.valueAt(row)));supports[slot]|=oldSupports[row];
                mass[slot]=Math.addExact(mass[slot],oldMass[row]);residual[slot]=Math.addExact(residual[slot],oldPresent[row]!=0?oldResidual[row]:oldMass[row]);present[slot]|=oldPresent[row];
                energy[slot]=Double.doubleToRawLongBits(Double.longBitsToDouble(energy[slot])+Double.longBitsToDouble(oldEnergy[row]));
                for(int item=oldOffsets[row];item<oldOffsets[row+1];item++)if(oldAmounts[item]!=0){
                    var mix=mixtures.computeIfAbsent(slot,ignored->new java.util.TreeMap<>());
                    mix.merge(oldHandles[item],oldAmounts[item],Math::addExact);
                }
            }
        }
        for(int slot=0;slot<slots;slot++){
            if(present[slot]==0)residual[slot]=0;
            var mix=mixtures.get(slot);speciesOffsets[slot+1]=speciesOffsets[slot]+(mix==null?0:mix.size());
        }
        int[] handles=new int[speciesOffsets[slots]];long[] amounts=new long[handles.length];
        for(var entry:mixtures.entrySet()){int at=speciesOffsets[entry.getKey()];for(var item:entry.getValue().entrySet()){handles[at]=item.getKey();amounts[at++]=item.getValue();}}
        return new DeviceLiquidInventory(cells,materials,supports,DeviceDyadicArray.encode(volumes),mass,energy,present,speciesOffsets,handles,amounts,residual);
    }

    /** CPU inventories contain positive-volume stocks and positive species entries. */
    private DeviceLiquidInventory compactInventory(DeviceLiquidInventory inventory) {
        int[] oldCells=inventory.cellOffsets(),oldMaterials=inventory.materials(),oldSupports=inventory.supportMasks(),oldPresent=inventory.mixturePresent(),oldSpecies=inventory.speciesOffsets(),oldHandles=inventory.speciesHandles();
        long[] oldMass=inventory.massUnits(),oldEnergy=inventory.energyBits(),oldResidual=inventory.massResidualUnits(),oldAmounts=inventory.speciesAmounts();var oldVolumes=inventory.volumes();
        int[] signs=oldVolumes.signs();int live=0,entries=0;for(int row=0;row<inventory.stockCount();row++)if(signs[row]>0){live++;for(int at=oldSpecies[row];at<oldSpecies[row+1];at++)if(oldAmounts[at]>0)entries++;}
        int[] cells=new int[cellCount()+1],materials=new int[live],supports=new int[live],present=new int[live],offsets=new int[live+1],handles=new int[entries];
        long[] mass=new long[live],energy=new long[live],residual=new long[live],amounts=new long[entries];var volumes=new java.util.ArrayList<mmm.units.SignedDyadic>(live);
        int output=0,item=0;for(int c=0;c<cellCount();c++){
            for(int row=oldCells[c];row<oldCells[c+1];row++)if(oldVolumes.valueAt(row).signum()>0){
                materials[output]=oldMaterials[row];supports[output]=oldSupports[row];present[output]=oldPresent[row];mass[output]=oldMass[row];energy[output]=oldEnergy[row];residual[output]=oldResidual[row];volumes.add(oldVolumes.valueAt(row));
                for(int at=oldSpecies[row];at<oldSpecies[row+1];at++)if(oldAmounts[at]>0){handles[item]=oldHandles[at];amounts[item++]=oldAmounts[at];}
                offsets[++output]=item;
            }cells[c+1]=output;
        }
        return new DeviceLiquidInventory(cells,materials,supports,DeviceDyadicArray.encode(volumes),mass,energy,present,offsets,handles,amounts,residual);
    }

    public record VolumeColumns(int[] offsets,int[] counts,int[] exponents,int[] limbs,int used) { }
    public static int requiredVolumeCapacity(DeviceLiquidInventory inventory,int stockCapacity) {
        int used=Math.multiplyExact(4,stockCapacity);int[] offsets=inventory.volumes().offsets();
        for(int i=0;i<inventory.stockCount();i++){int count=offsets[i+1]-offsets[i];if(count>4)used=Math.addExact(used,count);}
        return used;
    }
    /** Includes exact same-material cold merging before measuring overflow storage. */
    public static int requiredVolumeCapacity(DeviceLiquidInventory inventory,int stockCapacity,int materialCount) {
        int used=Math.multiplyExact(4,Math.max(stockCapacity,Math.multiplyExact(inventory.cellCount(),materialCount)));
        var merged=new java.util.HashMap<Integer,mmm.units.SignedDyadic>();
        int[] cells=inventory.cellOffsets(),materials=inventory.materials();var volumes=inventory.volumes();
        for(int c=0;c<inventory.cellCount();c++)for(int row=cells[c];row<cells[c+1];row++){
            int material=materials[row];if(material<0||material>=materialCount)throw new IllegalArgumentException("Liquid material exceeds bound palette");
            merged.merge(c*materialCount+material,volumes.valueAt(row),mmm.units.SignedDyadic::add);
        }
        for(var volume:merged.values()){int count=(volume.significand().bitLength()+31)/32;if(count>4)used=Math.addExact(used,count);}
        return used;
    }
    /** Four inline words per slot and a shared overflow tail; offsets are independent addresses. */
    public VolumeColumns encodeVolumes(DeviceLiquidInventory inventory) {
        inventory=slotInventory(inventory);
        var v=inventory.volumes();int n=inventory.stockCount(),used=Math.multiplyExact(4,stockCapacity);
        int[] offsets=new int[stockCapacity+1],counts=new int[n],starts=v.offsets(),raw=v.limbs();
        for(int i=0;i<n;i++){counts[i]=starts[i+1]-starts[i];offsets[i]=counts[i]<=4?4*i:used;if(counts[i]>4)used=Math.addExact(used,counts[i]);}
        if(used>limbCapacity)throw new IllegalArgumentException("Liquid inline/overflow volume capacity exceeded: "+used);
        int[] limbs=new int[used];for(int i=0;i<n;i++)System.arraycopy(raw,starts[i],limbs,offsets[i],counts[i]);
        offsets[stockCapacity]=used;return new VolumeColumns(offsets,counts,v.exponents(),limbs,used);
    }

    /** Reconstructs exact authoritative columns after an explicit device checkpoint read. */
    public State exportState(ByteBuffer source) {
        ByteBuffer in = view(source);
        int[] counts = ints(in, Field.COUNTS, 3);
        int stocks = counts[0], species = counts[1], limbs = counts[2];
        if (stocks < 0 || stocks > stockCapacity || species < 0 || species > speciesCapacity
                || limbs < 0 || limbs > limbCapacity)
            throw new IllegalArgumentException("Device liquid counts exceed resident capacity");
        int[] addresses=ints(in,Field.VOLUME_OFFSETS,stocks),lengths=ints(in,Field.VOLUME_COUNTS,stocks),packedOffsets=new int[stocks+1],signs=new int[stocks];
        for(int i=0;i<stocks;i++){packedOffsets[i+1]=Math.addExact(packedOffsets[i],lengths[i]);signs[i]=lengths[i]>0?1:0;}
        int[] packed=new int[packedOffsets[stocks]];
        int[] rawLimbs=ints(in,Field.VOLUME_LIMBS,limbs);
        for(int i=0;i<stocks;i++)System.arraycopy(rawLimbs,addresses[i],packed,packedOffsets[i],lengths[i]);
        int[] exponents=ints(in,Field.VOLUME_EXPONENTS,stocks);
        for(int i=0;i<stocks;i++)if(lengths[i]==0)exponents[i]=0;
        var volumes = new DeviceDyadicArray(packedOffsets,signs,exponents,packed);
        var inventory = new DeviceLiquidInventory(ints(in, Field.CELL_OFFSETS, cellCount() + 1), ints(in, Field.MATERIALS, stocks),
                ints(in, Field.SUPPORT_MASKS, stocks), volumes, longs(in, Field.MASS_UNITS, stocks), longs(in, Field.ENERGY_BITS, stocks),
                ints(in, Field.MIXTURE_PRESENT, stocks), ints(in, Field.SPECIES_OFFSETS, stocks + 1), ints(in, Field.SPECIES_HANDLES, species),
                longs(in, Field.SPECIES_AMOUNTS, species), longs(in, Field.MASS_RESIDUAL_UNITS, stocks));
        return new State(compactInventory(inventory), new MacMomentumState(sizeX, sizeY, sizeZ,
                doubles(in, Field.MOMENTUM_X), doubles(in, Field.MOMENTUM_Y), doubles(in, Field.MOMENTUM_Z)));
    }

    /** Records column relocation during capacity growth; caller supplies transfer synchronization. */
    void copyColumns(VkCommandBuffer command, VulkanLiquidStateLayout oldLayout, GpuBuffer source,
                     GpuBuffer output, MemoryStack stack) {
        if (sizeX != oldLayout.sizeX || sizeY != oldLayout.sizeY || sizeZ != oldLayout.sizeZ
                || materialCount != oldLayout.materialCount || stockCapacity < oldLayout.stockCapacity || speciesCapacity < oldLayout.speciesCapacity
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
