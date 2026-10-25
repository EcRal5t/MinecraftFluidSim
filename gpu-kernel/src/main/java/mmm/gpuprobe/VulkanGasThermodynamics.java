package mmm.gpuprobe;

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
import static mmm.gpuprobe.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Derived ideal-gas states with heterogeneous piecewise-constant caloric models. Inventory U is unchanged. */
final class VulkanGasThermodynamics implements AutoCloseable {
    static final int STATE_WIDTH = 9;
    enum Quantity { TEMPERATURE, PRESSURE, DENSITY, HEAT_CAPACITY, GAS_CONSTANT, GAMMA, BULK_MODULUS, SOUND_SPEED, COMPLIANCE }
    record Tables(VulkanGasStateLayout.Definitions definitions, int[] segmentOffsets, double[] segmentUpper,
                  double[] segmentHeatCapacity, double[] specificAnchorEnergy, double[] speciesReference,boolean isothermal) {
        Tables(VulkanGasStateLayout.Definitions definitions,int[] offsets,double[] upper,double[] capacity,double[] anchor,double[] reference){this(definitions,offsets,upper,capacity,anchor,reference,false);}
        static Tables isothermal(VulkanGasStateLayout.Definitions definitions){int n=definitions.componentIds().size();int[] offsets=new int[n+1];double[] upper=new double[n],capacity=new double[n];for(int i=0;i<n;i++){offsets[i+1]=i+1;upper[i]=1e100;capacity[i]=1000;}
            return new Tables(definitions,offsets,upper,capacity,new double[n],new double[n*definitions.speciesIds().size()],true);}
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
                  VulkanLiquidStateLayout.Column states, VulkanLiquidStateLayout.Column longVolumes,
                  VulkanLiquidStateLayout.Column volumeDispatch, long endOffset) {
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
            data[0] = gas.nodeCapacity(); data[1] = tables.specificAnchorEnergy().length;
            data[2] = tables.definitions().speciesIds().size(); data[3] = nodeVolumesWordOffset;
            data[4] = word(segmentOffsets); data[5] = word(segmentUpper); data[6] = word(segmentHeatCapacity);
            data[7] = word(specificAnchorEnergy); data[8] = word(speciesReference); data[9] = word(states);
            data[10]=word(longVolumes);data[11]=word(volumeDispatch);
            for (var field : VulkanGasStateLayout.Field.values()) data[16 + field.ordinal()] = Math.toIntExact(gas.column(field).offset() / 4);
            for (int i = 0; i < data.length; i++) target.putInt(4 * i, data[i]);
            target.putDouble(4 * 32, GasRegistry.STANDARD_TEMPERATURE_K);
            target.putDouble(4 * 34, ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);
            target.putDouble(4 * 36, GasRegistry.STANDARD_PRESSURE_PA * 1e-9 / GasRegistry.STANDARD_TEMPERATURE_K);target.putInt(4*38,tables.isothermal()?1:0);
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
        var longVolumes=column(cursor,gas.nodeCapacity(),4,alignment);var dispatch=column(cursor,4,4,alignment);
        return new Layout(gas, tables, nodeVolumesWordOffset, header, offsets, upper, capacity, anchor, species, states,longVolumes,dispatch, align(cursor[0], alignment));
    }
    private final FlipKernelBindings bindings;
    private FlipKernelBindings.BufferRange arena;
    VulkanGasThermodynamics(VulkanDeviceContext context) { bindings = new FlipKernelBindings(context, 2, shader(), 32); }
    void rebindRanges(FlipKernelBindings.BufferRange candidate, FlipKernelBindings.BufferRange errors) {
        arena=candidate;bindings.rebindRanges(List.of(candidate, errors));
    }
    void record(VkCommandBuffer command, Layout layout) { stage(command, layout, 1, null); }
    /** Inventory topology keys and geometry must describe the same generation. This only sums volumes;
     * changing chamber membership requires gas inventory remapping and new topology keys first.
     */
    void recordVolumes(VkCommandBuffer command, Layout layout, VulkanGasGeometry.Layout geometry) {
        stage(command,layout,2,geometry);
        stage(command,layout,3,geometry);
        stage(command,layout,4,geometry);
        stage(command, layout, 0, geometry);
    }
    private void stage(VkCommandBuffer command, Layout layout, int stage, VulkanGasGeometry.Layout geometry) {
        int count = layout.gas().nodeCapacity();if (count == 0) return;
        int groups = stage==2||stage==4?1:(int) (((long) count + 127) / 128);
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                    stack.ints(layout.headerWordOffset(), stage, count,
                            geometry == null ? 0 : word(geometry.componentOffsets()), geometry == null ? 0 : word(geometry.componentMembers()),
                            geometry == null ? 0 : word(geometry.dryVolumes()), geometry == null ? 0 : word(geometry.cellNodes()),
                            geometry == null ? 0 : geometry.cells()));
            if(stage==0){
                barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+layout.volumeDispatch().offset());
            }else vkCmdDispatch(command, Math.min(groups, 65535), (groups + 65534) / 65535, 1);
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
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Gas thermodynamics shader unavailable", failure); }
    }
    @Override public void close() { bindings.close(); }
}
