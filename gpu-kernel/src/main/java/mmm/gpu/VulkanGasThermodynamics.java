package mmm.gpu;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.air.GasRegistry;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static mmm.gpu.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Derived local gas states with heterogeneous piecewise-constant caloric models. */
final class VulkanGasThermodynamics implements AutoCloseable {
    private enum Header {
        NODE_CAPACITY(0),
        COMPONENT_COUNT(1),
        SPECIES_STRIDE(2),
        NODE_VOLUMES(3),
        SEGMENT_OFFSETS(4),
        SEGMENT_UPPER(5),
        SEGMENT_CAPACITY(6),
        ANCHOR_ENERGY(7),
        SPECIES_REFERENCE(8),
        STATES(9),
        CALORIC_CACHE(10),
        STANDARD_TEMPERATURE(32),
        MINIMUM_TEMPERATURE(34),
        PRESSURE_FACTOR(36),
        ISOTHERMAL(38);
        final int word;
        Header(int word) { this.word = word; }
    }
    static final int GAS_FIELDS = 16;
    static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define GAS_H_").append(field.name()).append(' ').append(field.word).append('\n');
        result.append("#define GAS_FIELDS ").append(GAS_FIELDS).append('\n');
        return result + VulkanShaderLayout.fields("GAS_", VulkanGasStateLayout.Field.values())
                + "\n#define GAS_MIXTURE_BULK " + mmm.thermal.MixtureVolumeModel.LIQUID_BULK_PA + "LF\n#define GAS_MIXTURE_LIMIT "
                + mmm.thermal.MixtureVolumeModel.PRESSURE_LIMIT_PA + "LF\n";
    }

    static final int STATE_WIDTH = 9;
    enum Quantity { TEMPERATURE, PRESSURE, DENSITY, HEAT_CAPACITY, GAS_CONSTANT, GAMMA, BULK_MODULUS, SOUND_SPEED, COMPLIANCE }
    record Tables(VulkanGasStateLayout.Definitions definitions, int[] segmentOffsets, double[] segmentUpper,
                  double[] segmentHeatCapacity, double[] specificAnchorEnergy, double[] speciesReference,boolean isothermal) {
        Tables(VulkanGasStateLayout.Definitions definitions,int[] offsets,double[] upper,double[] capacity,double[] anchor,double[] reference){this(definitions,offsets,upper,capacity,anchor,reference,false);}
        Tables asIsothermal(){return new Tables(definitions,segmentOffsets,segmentUpper,segmentHeatCapacity,specificAnchorEnergy,speciesReference,true);}
    }

    /** Coefficients retain registry component/species identities; absent declarations become errors only when used. */
    static Tables tables(VulkanGasStateLayout.Definitions definitions, GasRegistry gas,
                         ThermalRegistry thermal, ChemicalRegistry chemistry) {
        int components = definitions.componentIds().size(), species = definitions.speciesIds().size();
        int[] offsets = new int[components + 1];
        double[] anchor = new double[components], references = new double[Math.multiplyExact(components, species)];
        Arrays.fill(anchor, Double.NaN);Arrays.fill(references, Double.NaN);
        var upper = new ArrayList<Double>();var heatCapacity = new ArrayList<Double>();
        for (int component = 0; component < components; component++) {
            String id = definitions.componentIds().get(component);
            if (gas.hasComponent(id) && thermal.hasMaterial(ThermalMaterialDefinition.CarrierKind.GAS, id)) {
                int materialHandle = thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS, id);
                var material = thermal.material(materialHandle);
                anchor[component] = material.referenceSpecificEnergyJPerKg()
                        + material.sensibleSpecificEnergyAt(GasRegistry.STANDARD_TEMPERATURE_K);
                for (var segment : material.caloricSegments()) {
                    upper.add(segment.maximumTemperatureK());heatCapacity.add(segment.specificHeatJPerKgK());
                }
                for (int s = 0; s < species; s++) {
                    String speciesId = definitions.speciesIds().get(s);
                    if (chemistry.hasSpecies(speciesId) && gas.component(id).allowedSpecies().contains(speciesId)
                            && thermal.hasSpeciesOffset(materialHandle, speciesId))
                        references[component * species + s] = thermal.speciesOffset(materialHandle, speciesId)
                                .referenceInternalEnergyJPerMol().divide(ChemicalLimits.QUANTA_PER_MOL).toDouble();
                }
            }
            offsets[component + 1] = upper.size();
        }
        return new Tables(definitions, offsets, upper.stream().mapToDouble(Double::doubleValue).toArray(),
                heatCapacity.stream().mapToDouble(Double::doubleValue).toArray(), anchor, references);
    }

    record Layout(VulkanGasStateLayout gas, Tables tables, int nodeVolumesWordOffset,
                  VulkanLiquidStateLayout.Column header, VulkanLiquidStateLayout.Column segmentOffsets,
                  VulkanLiquidStateLayout.Column segmentUpper, VulkanLiquidStateLayout.Column segmentHeatCapacity,
                  VulkanLiquidStateLayout.Column specificAnchorEnergy, VulkanLiquidStateLayout.Column speciesReference,
                  VulkanLiquidStateLayout.Column states, VulkanLiquidStateLayout.Column caloricCache, long endOffset) {
        int headerWordOffset() { return word(header); }
        long bytes() { return endOffset - header.offset(); }
        long stateOffset(int node, Quantity quantity) {
            if (node < 0 || node >= gas.nodeCapacity()) throw new IndexOutOfBoundsException(node);
            return states.offset() + 8L * (STATE_WIDTH * (long) node + quantity.ordinal());
        }
        void initializeHeaderAndTables(ByteBuffer destination) {
            destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
            for (var chunk : tableChunks()) destination.duplicate().position(Math.toIntExact(chunk.offset())).put(chunk.bytes().duplicate());
        }
        ByteBuffer headerBytes() {
            var target = ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);int[] data = new int[64];
            data[Header.NODE_CAPACITY.word] = gas.nodeCapacity(); data[Header.COMPONENT_COUNT.word] = tables.specificAnchorEnergy().length;
            data[Header.SPECIES_STRIDE.word] = tables.definitions().speciesIds().size(); data[Header.NODE_VOLUMES.word] = nodeVolumesWordOffset;
            data[Header.SEGMENT_OFFSETS.word] = word(segmentOffsets); data[Header.SEGMENT_UPPER.word] = word(segmentUpper); data[Header.SEGMENT_CAPACITY.word] = word(segmentHeatCapacity);
            data[Header.ANCHOR_ENERGY.word] = word(specificAnchorEnergy); data[Header.SPECIES_REFERENCE.word] = word(speciesReference); data[Header.STATES.word] = word(states); data[Header.CALORIC_CACHE.word] = word(caloricCache);
            for (var field : VulkanGasStateLayout.Field.values()) data[GAS_FIELDS + field.ordinal()] = Math.toIntExact(gas.column(field).offset() / 4);
            for (int i = 0; i < data.length; i++) target.putInt(4 * i, data[i]);
            target.putDouble(4 * Header.STANDARD_TEMPERATURE.word, GasRegistry.STANDARD_TEMPERATURE_K);
            target.putDouble(4 * Header.MINIMUM_TEMPERATURE.word, ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
            target.putDouble(4 * Header.PRESSURE_FACTOR.word, GasRegistry.STANDARD_PRESSURE_PA * 1e-9 / GasRegistry.STANDARD_TEMPERATURE_K);target.putInt(4 * Header.ISOTHERMAL.word,tables.isothermal()?1:0);
            return target;
        }
        /** Direct, at-most-64 KiB table chunks for vkCmdUpdateBuffer during device arena growth. */
        List<TableChunk> tableChunks() {
            var result = new ArrayList<TableChunk>();
            for (int first = 0; first < tables.segmentOffsets().length; first += 16384) {
                int count = Math.min(16384, tables.segmentOffsets().length - first);
                var data = ByteBuffer.allocateDirect(4 * count).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < count; i++) data.putInt(4 * i, tables.segmentOffsets()[first + i]);
                result.add(new TableChunk(segmentOffsets.offset() + 4L * first, data));
            }
            appendChunks(result, segmentUpper, tables.segmentUpper());
            appendChunks(result, segmentHeatCapacity, tables.segmentHeatCapacity());
            appendChunks(result, specificAnchorEnergy, tables.specificAnchorEnergy());
            appendChunks(result, speciesReference, tables.speciesReference());
            return List.copyOf(result);
        }

    }
    record TableChunk(long offset, ByteBuffer bytes) { }
    private static void appendChunks(List<TableChunk> result, VulkanLiquidStateLayout.Column column, double[] values) {
        for (int first = 0; first < values.length; first += 8192) {
            int count = Math.min(8192, values.length - first);
            var data = ByteBuffer.allocateDirect(8 * count).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) data.putDouble(8 * i, values[first + i]);
            result.add(new TableChunk(column.offset() + 8L * first, data));
        }
    }
    static Layout layout(VulkanGasStateLayout gas, Tables tables, int nodeVolumesWordOffset, long scratchBase, long alignment) {
        if (!gas.definitions().equals(tables.definitions()) || nodeVolumesWordOffset < 0 || scratchBase < gas.endOffset()
                || alignment < 8 || (alignment & (alignment - 1)) != 0)
            throw new IllegalArgumentException("Invalid gas thermodynamics layout");
        long[] cursor = {scratchBase};
        var header = column(cursor, 64, 4, alignment);
        var offsets = column(cursor, tables.segmentOffsets().length, 4, alignment);
        var upper = column(cursor, tables.segmentUpper().length, 8, alignment);
        var capacity = column(cursor, tables.segmentHeatCapacity().length, 8, alignment);
        var anchor = column(cursor, tables.specificAnchorEnergy().length, 8, alignment);
        var species = column(cursor, tables.speciesReference().length, 8, alignment);
        var states = column(cursor, Math.multiplyExact(gas.nodeCapacity(), STATE_WIDTH), 8, alignment);
        var cache = column(cursor, Math.multiplyExact(gas.nodeCapacity(), 4), 8, alignment);
        return new Layout(gas, tables, nodeVolumesWordOffset, header, offsets, upper, capacity, anchor, species, states, cache, align(cursor[0], alignment));
    }
    private final FlipKernelBindings bindings;
    VulkanGasThermodynamics(VulkanDeviceContext context) { bindings = new FlipKernelBindings(context, 2, shader(), 32); }
    void rebindRanges(FlipKernelBindings.BufferRange candidate, FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(candidate, errors));
    }
    void record(VkCommandBuffer command, Layout layout) { stage(command, layout, 1); }
    /** Derive temperature and caloric properties at unit volume before mixture closure. */
    void recordCaloric(VkCommandBuffer command, Layout layout) { stage(command, layout, 5); }
    /** Update caloric state after energy changes with unchanged gas composition. */
    void recordEnergy(VkCommandBuffer command, Layout layout) { stage(command, layout, 6); }
    private void stage(VkCommandBuffer command, Layout layout, int stage) {
        int count = layout.gas().nodeCapacity();if (count == 0) return;
        int groups = (int) (((long) count + 127) / 128);
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(layout.headerWordOffset(), stage, count,
                            0, 0, 0, 0, 0));
            vkCmdDispatch(command, Math.min(groups, 65535), (groups + 65534) / 65535, 1);
            barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column) { return Math.toIntExact(column.offset() / 4); }
    private static long align(long value, long alignment) { return Math.addExact(value, alignment - 1) & -alignment; }
    private static VulkanLiquidStateLayout.Column column(long[] cursor, int count, int width, long alignment) {
        var result = new VulkanLiquidStateLayout.Column(align(cursor[0], alignment), count, width);
        cursor[0] = Math.addExact(result.offset(), result.bindingBytes());return result;
    }
    private static String shader() {
        try (var input = VulkanGasThermodynamics.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/gas-thermodynamics.comp")) {
            if (input == null) throw new IllegalStateException("Gas thermodynamics shader missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout());
        } catch (IOException failure) { throw new IllegalStateException("Gas thermodynamics shader unavailable", failure); }
    }
    @Override public void close() { bindings.close(); }
}
