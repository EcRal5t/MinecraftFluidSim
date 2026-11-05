package mmm.gpu;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import mmm.fluid.CellCarrierInventoryView;
import mmm.fluid.FluidCellInventoryView;
import mmm.units.Mass;

/** Compact, read-only liquid and carrier observations for one committed cell. */
final class VulkanCellObservation implements AutoCloseable {
    private enum Header {
        CELL,
        MATERIAL_COUNT,
        SPECIES_COUNT,
        CELL_OFFSETS,
        MATERIALS,
        VOLUMES,
        MASS_UNITS,
        SPECIES_OFFSETS,
        SPECIES_HANDLES,
        SPECIES_AMOUNTS,
        LIQUID_THERMAL,
        GRAIN_COUNTS,
        GRAIN_CAPACITY,
        GRAIN_POSITION,
        DEPOSIT_CELL,
        GRAIN_THERMAL,
        COHERENT_COUNTS,
        COHERENT_CELL,
        COHERENT_THERMAL,
        SIZE_X,
        SIZE_Y,
        SIZE_Z,
        ERROR,
        GRAIN_MATERIAL,
        GRAIN_VOLUME,
        GRAIN_MASS,
        COHERENT_MATERIAL,
        COHERENT_VOLUME,
        COHERENT_MASS,
        GAS_CELL_NODES,
        GAS_THERMAL,
        GAS_COMPONENT_OFFSETS,
        CELL_SIZE_LOW,
        CELL_SIZE_HIGH,
        CELL_VOLUME_LOW,
        CELL_VOLUME_HIGH,
        GAS_VOLUME,
        GAS_MASS,
        GAS_COMPONENT,
        GAS_MIXTURE,
        GRAIN_MATERIAL_COUNT,
        COHERENT_MATERIAL_COUNT,
        GAS_COMPONENT_COUNT,
        SOLID_OUTPUT,
        GAS_OUTPUT,
        GRAIN_SUPPORT_CELLS,
        GRAIN_SUPPORT_QUOTAS,
        RESERVED_END
    }
    private enum Output {
        GAS_NODE(48), GAS_PRESSURE(50), GAS_TEMPERATURE(52), GRANULAR_OCCUPIED(54),
        LIQUID_VOLUME(64), LIQUID_MASS(66), LIQUID_TEMPERATURE(68), MATERIAL_ROWS(72);
        final int word;
        Output(int word) { this.word = word; }
    }
    private static String shaderLayout() {
        var source = new StringBuilder();
        for (var field : Header.values())
            source.append("#define H_").append(field.name()).append(" ").append(field.ordinal()).append("\n");
        for (var field : Output.values())
            source.append("#define O_").append(field.name()).append(' ').append(field.word).append('\n');
        return source + VulkanLiquidThermodynamics.shaderLayout();
    }
    record Cell(FluidCellInventoryView liquid, CellCarrierInventoryView carriers, boolean granularOccupied) {}
    private final VulkanDeviceContext context;
    private final FlipGpuResources resources;
    private final FlipKernelBindings bindings;
    private GpuStepScheduler scheduler;
    private GpuBuffer output;
    private long capacity;

    VulkanCellObservation(VulkanDeviceContext context) {
        this.context = context;
        resources = new FlipGpuResources(context);
        try (var in = getClass().getResourceAsStream("/assets/mmm_gpu_probe/shaders/cell-observation.comp")) {
            if (in == null)
                throw new IllegalStateException("Cell observation shader missing");
            bindings = new FlipKernelBindings(context, 2,
                new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout()), 16);
        } catch (java.io.IOException | RuntimeException | Error failure) {
            resources.close();
            throw new IllegalStateException(failure);
        }
    }

    synchronized Cell capture(VulkanLiquidRegion.Capture capture, VulkanFluidStateCodec codec, int x, int y, int z) {
        var in = capture.readoutInputs();
        var liquid = in.liquid();
        if (x < 0 || y < 0 || z < 0 || x >= liquid.sizeX() || y >= liquid.sizeY() || z >= liquid.sizeZ())
            throw new IllegalArgumentException("Cell observation lies outside region");
        var chemical = codec.template().chemicalRegistry();
        int materials = codec.fluids().size(), species = chemical == null ? 0 : chemical.species().size();
        var granular = in.granular();
        var coherent = in.coherent();
        var gas = in.gas();
        int grains = granular == null ? 0 : granular.definitions().materialIds().size();
        int phases = coherent == null ? 0 : coherent.definitions().phaseIds().size();
        int gases = gas == null ? 0 : gas.definitions().componentIds().size();
        int solidWord = 72 + 4 * materials + 2 * species;
        int gasWord = solidWord + 4 * (2 * grains + phases);
        long bytes = 4L * (gasWord + 4 * gases);
        if (bytes > capacity) {
            if (scheduler != null)
                scheduler.close();
            if (output != null)
                resources.destroy(output);
            output = resources.addDeviceStorage(bytes);
            scheduler = new GpuStepScheduler(context, resources, bytes);
            capacity = bytes;
        }
        int[] h = new int[Header.values().length];
        java.util.Arrays.fill(h, -1);
        h[Header.CELL.ordinal()] = x + liquid.sizeX() * (y + liquid.sizeY() * z);
        h[Header.MATERIAL_COUNT.ordinal()] = materials;
        h[Header.SPECIES_COUNT.ordinal()] = species;
        h[Header.CELL_OFFSETS.ordinal()] = word(liquid.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS));
        h[Header.MATERIALS.ordinal()] = word(liquid.column(VulkanLiquidStateLayout.Field.MATERIALS));
        h[Header.VOLUMES.ordinal()] = word(liquid.column(VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES));
        h[Header.MASS_UNITS.ordinal()] = word(liquid.column(VulkanLiquidStateLayout.Field.MASS_UNITS));
        h[Header.SPECIES_OFFSETS.ordinal()] = word(liquid.column(VulkanLiquidStateLayout.Field.SPECIES_OFFSETS));
        h[Header.SPECIES_HANDLES.ordinal()] = word(liquid.column(VulkanLiquidStateLayout.Field.SPECIES_HANDLES));
        h[Header.SPECIES_AMOUNTS.ordinal()] = word(liquid.column(VulkanLiquidStateLayout.Field.SPECIES_AMOUNTS));
        h[Header.LIQUID_THERMAL.ordinal()] =
            codec.template().thermalRegistry() == null ? -1 : word(in.liquidThermal().header());
        if (granular != null) {
            h[Header.GRAIN_COUNTS.ordinal()] = granular.word(VulkanGranularStateLayout.Field.COUNTS);
            h[Header.GRAIN_CAPACITY.ordinal()] = granular.particleCapacity();
            h[Header.GRAIN_POSITION.ordinal()] = granular.word(VulkanGranularStateLayout.Field.POSITION);
            h[Header.DEPOSIT_CELL.ordinal()] = granular.word(VulkanGranularStateLayout.Field.DEPOSIT_CELL);
            h[Header.GRAIN_THERMAL.ordinal()] = word(in.granularThermal().header());
            h[Header.GRAIN_MATERIAL.ordinal()] = granular.word(VulkanGranularStateLayout.Field.MATERIAL);
            h[Header.GRAIN_VOLUME.ordinal()] = granular.word(VulkanGranularStateLayout.Field.SOLID_VOLUME_SVQ);
            h[Header.GRAIN_MASS.ordinal()] = granular.word(VulkanGranularStateLayout.Field.MASS_UNITS);
            h[Header.GRAIN_SUPPORT_CELLS.ordinal()] = granular.word(VulkanGranularStateLayout.Field.SUPPORT_CELLS);
            h[Header.GRAIN_SUPPORT_QUOTAS.ordinal()] = granular.word(VulkanGranularStateLayout.Field.SUPPORT_QUOTAS);
        }
        if (coherent != null) {
            h[Header.COHERENT_COUNTS.ordinal()] = coherent.word(VulkanCoherentStateLayout.Field.COUNTS);
            h[Header.COHERENT_CELL.ordinal()] = coherent.word(VulkanCoherentStateLayout.Field.CELL);
            h[Header.COHERENT_THERMAL.ordinal()] = word(in.coherentThermal().header());
            h[Header.COHERENT_MATERIAL.ordinal()] = coherent.word(VulkanCoherentStateLayout.Field.MATERIAL);
            h[Header.COHERENT_VOLUME.ordinal()] = coherent.word(VulkanCoherentStateLayout.Field.OCCUPIED_VOLUME_SVQ);
            h[Header.COHERENT_MASS.ordinal()] = coherent.word(VulkanCoherentStateLayout.Field.MASS_UNITS);
        }
        h[Header.SIZE_X.ordinal()] = liquid.sizeX();
        h[Header.SIZE_Y.ordinal()] = liquid.sizeY();
        h[Header.SIZE_Z.ordinal()] = liquid.sizeZ();
        h[Header.ERROR.ordinal()] = 0;
        if (gas != null) {
            h[Header.GAS_CELL_NODES.ordinal()] = word(in.gasGeometry().cellNodes());
            h[Header.GAS_THERMAL.ordinal()] = word(in.gasThermal().states());
            h[Header.GAS_COMPONENT_OFFSETS.ordinal()] =
                word(gas.column(VulkanGasStateLayout.Field.ROW_COMPONENT_OFFSETS));
            h[Header.GAS_VOLUME.ordinal()] = word(gas.column(VulkanGasStateLayout.Field.STANDARD_VOLUME_NANOS));
            h[Header.GAS_MASS.ordinal()] = word(gas.column(VulkanGasStateLayout.Field.MASS_UNITS));
            h[Header.GAS_COMPONENT.ordinal()] = word(gas.column(VulkanGasStateLayout.Field.COMPONENT_HANDLES));
        }
        h[Header.GAS_MIXTURE.ordinal()] = in.mixture()==null?-1:in.mixture().statesWord();
        h[Header.GRAIN_MATERIAL_COUNT.ordinal()] = grains;
        h[Header.COHERENT_MATERIAL_COUNT.ordinal()] = phases;
        h[Header.GAS_COMPONENT_COUNT.ordinal()] = gases;
        h[Header.SOLID_OUTPUT.ordinal()] = solidWord;
        h[Header.GAS_OUTPUT.ordinal()] = gasWord;
        double size = codec.template().config().cellSize();
        var header = ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < h.length; i++)
            header.putInt(4 * i, h[i]);
        header.putDouble(4 * Header.CELL_SIZE_LOW.ordinal(), size)
            .putDouble(4 * Header.CELL_VOLUME_LOW.ordinal(), size * size * size);
        bindings.rebindRanges(
            List.of(new FlipKernelBindings.BufferRange(capture.buffer()), new FlipKernelBindings.BufferRange(output)));
        try (var stack = stackPush()) {
            var submission = scheduler.acquire();
            var command = scheduler.begin(submission, "cell-observation");
            vkCmdUpdateBuffer(command, output.handle(), 0, header);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT, stack);
            int count = Math.max(1, Math.max(Math.max(materials, species), Math.max(Math.max(grains, phases), gases)));
            bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                stack.ints(materials, species, 0, 0));
            vkCmdDispatch(command, (count + 127) / 128, 1, 1);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT, stack);
            VulkanRegionStorage.copy(command, output, 0, submission.stagingBuffer(), 0, bytes, stack);
            scheduler.submitAndWait(stack, submission, "cell-observation");
            var b = submission.stagingBuffer().bytes().order(ByteOrder.LITTLE_ENDIAN);
            if (b.getInt(4 * Header.ERROR.ordinal()) != 0)
                throw new ArithmeticException("Cell observation inventory overflow or invalid thermal state");
            double volume = b.getDouble(4 * Output.LIQUID_VOLUME.word), mass = Mass.kilograms(b.getLong(4 * Output.LIQUID_MASS.word));
            FluidCellInventoryView liquidView = null;
            if (volume > 0 && mass > 0) {
                var mat = new ArrayList<FluidCellInventoryView.MaterialAmount>();
                for (int i = 0; i < materials; i++) {
                    int at = 4 * Output.MATERIAL_ROWS.word + 16 * i;
                    double value = b.getDouble(at);
                    if (value > 0)
                        mat.add(new FluidCellInventoryView.MaterialAmount(
                            codec.fluids().material(i).id(), value, Mass.kilograms(b.getLong(at + 8))));
                }
                mat.sort(Comparator.comparing(FluidCellInventoryView.MaterialAmount::materialId));
                var amounts = new ArrayList<FluidCellInventoryView.SpeciesAmount>();
                for (int i = 0; i < species; i++) {
                    long amount = b.getLong(4 * Output.MATERIAL_ROWS.word + 16 * materials + 8 * i);
                    if (amount > 0)
                        amounts.add(new FluidCellInventoryView.SpeciesAmount(chemical.species(i).id(), amount));
                }
                amounts.sort(Comparator.comparing(FluidCellInventoryView.SpeciesAmount::speciesId));
                liquidView = new FluidCellInventoryView(volume, mass, mass / volume,
                    h[Header.LIQUID_THERMAL.ordinal()] < 0 ? OptionalDouble.empty()
                                                           : OptionalDouble.of(b.getDouble(4 * Output.LIQUID_TEMPERATURE.word)),
                    mat, amounts);
            }
            var solids = new ArrayList<CellCarrierInventoryView.SolidAmount>();
            for (int i = 0; i < grains; i++) {
                String id = granular.definitions().materialIds().get(i);
                addSolid(solids, b, 4 * (solidWord + 4 * i), CellCarrierInventoryView.SolidKind.ACTIVE, id);
                addSolid(solids, b, 4 * (solidWord + 4 * (grains + i)), CellCarrierInventoryView.SolidKind.DEPOSIT, id);
            }
            for (int i = 0; i < phases; i++)
                addSolid(solids, b, 4 * (solidWord + 4 * (2 * grains + i)), CellCarrierInventoryView.SolidKind.COHERENT,
                    coherent.definitions().phaseIds().get(i));
            solids.sort(Comparator.comparing((CellCarrierInventoryView.SolidAmount v) -> v.kind().name())
                    .thenComparing(CellCarrierInventoryView.SolidAmount::stableId));
            int node = b.getInt(4 * Output.GAS_NODE.word);
            CellCarrierInventoryView.GasAmount gasView = null;
            if (node >= 0) {
                var components = new ArrayList<CellCarrierInventoryView.ComponentAmount>();
                for (int i = 0; i < gases; i++) {
                    int at = 4 * (gasWord + 4 * i);
                    long nanos = b.getLong(at);
                    if (nanos > 0)
                        components.add(new CellCarrierInventoryView.ComponentAmount(
                            gas.definitions().componentIds().get(i), nanos, Mass.kilograms(b.getLong(at + 8))));
                }
                components.sort(Comparator.comparing(CellCarrierInventoryView.ComponentAmount::componentId));
                gasView = new CellCarrierInventoryView.GasAmount(
                    node == 0, node, b.getDouble(4 * Output.GAS_PRESSURE.word), b.getDouble(4 * Output.GAS_TEMPERATURE.word), components);
            }
            var carriers = solids.isEmpty() && gasView == null ? null : new CellCarrierInventoryView(solids, gasView);
            return new Cell(liquidView, carriers, b.getInt(4 * Output.GRANULAR_OCCUPIED.word) != 0);
        }
    }
    private static void addSolid(List<CellCarrierInventoryView.SolidAmount> result, ByteBuffer b, int at,
        CellCarrierInventoryView.SolidKind kind, String id) {
        long volume = b.getLong(at);
        if (volume > 0)
            result.add(new CellCarrierInventoryView.SolidAmount(kind, id, volume, Mass.kilograms(b.getLong(at + 8))));
    }
    private static int word(VulkanLiquidStateLayout.Column c) {
        return Math.toIntExact(c.offset() / 4);
    }
    private static int word(VulkanGasStateLayout.Column c) {
        return Math.toIntExact(c.offset() / 4);
    }
    @Override
    public synchronized void close() {
        try {
            bindings.close();
        } finally {
            try {
                if (scheduler != null)
                    scheduler.close();
            } finally {
                resources.close();
            }
        }
    }
}
