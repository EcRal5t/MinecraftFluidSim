package mmm.gpuprobe;

import mmm.fluid.FixedGeometrySnapshot;
import mmm.fluid.MacMomentumState;
import mmm.inventory.DeviceLiquidInventory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;

import static mmm.gpuprobe.VulkanLiquidStateLayout.Field.*;
import static org.lwjgl.vulkan.VK10.*;

/** Owns resident liquid inventory, momentum and the geometry derived from the same generation. */
final class VulkanLiquidRegion implements AutoCloseable {
    enum GeometryField { FIXED_MASKS, OCCUPIED_SOLID, CELLS, PLANES, LIQUID_MICRO, FACES, FLUXES }

    private enum PressureField { STARTS, COLUMNS, VALUES, ROWS, INPUT, PRESSURE, WORK, META, PROBLEM, CONTROL, PARTIALS }
    private final EnumMap<PressureField, VulkanLiquidStateLayout.Column> pressureColumns = new EnumMap<>(PressureField.class);
    private final VulkanLiquidProjection projection;
    private final VulkanPressureKernel pressure;
    private final VulkanPressureMultigrid multigrid;
    private final VulkanPressureMultigrid.Layout hierarchy;
    private final VulkanLiquidStateLayout.Column multigridColumn;
    private final VulkanLiquidStateLayout liquid;
    private final EnumMap<GeometryField, VulkanLiquidStateLayout.Column> geometry = new EnumMap<>(GeometryField.class);
    private final VulkanRegionStorage storage;
    private final VulkanLiquidGeometry kernel;

    /** The backend owns the device and compiled kernels; a region owns its storage generations. */
    VulkanLiquidRegion(VulkanDeviceContext context, VulkanLiquidGeometry kernel,
                       VulkanLiquidProjection projection, VulkanPressureKernel pressure, VulkanPressureMultigrid multigrid,
                       DeviceLiquidInventory inventory, MacMomentumState momentum,
                       FixedGeometrySnapshot fixed, long[] occupiedSolid) {
        liquid = VulkanLiquidStateLayout.forState(inventory, momentum, context.storageOffsetAlignment());
        if (!fixed.matchesShape(liquid.sizeX(), liquid.sizeY(), liquid.sizeZ())
                || occupiedSolid.length != liquid.cellCount())
            throw new IllegalArgumentException("Resident liquid geometry differs from its grid");
        this.kernel = kernel; this.projection = projection; this.pressure = pressure; this.multigrid = multigrid;
        hierarchy = VulkanPressureMultigrid.layout(liquid.sizeX(), liquid.sizeY(), liquid.sizeZ());
        int cells = liquid.cellCount();
        int faces = Math.addExact(Math.addExact(liquid.column(MOMENTUM_X).count(),
                liquid.column(MOMENTUM_Y).count()), liquid.column(MOMENTUM_Z).count());
        long cursor = liquid.bytes();
        for (GeometryField field : GeometryField.values()) {
            int width = field == GeometryField.FIXED_MASKS ? 4 : 8;
            int count = switch (field) {
                case FIXED_MASKS, OCCUPIED_SOLID -> cells;
                case CELLS, PLANES -> Math.multiplyExact(4, cells);
                case LIQUID_MICRO -> Math.multiplyExact(8, cells);
                case FACES -> Math.multiplyExact(4, faces);
                case FLUXES -> Math.multiplyExact(2, faces);
            };
            cursor = align(cursor);
            var column = new VulkanLiquidStateLayout.Column(cursor, count, width);
            geometry.put(field, column);
            cursor = Math.addExact(cursor, column.bytes());
        }
        for (PressureField field : PressureField.values()) {
            int width = switch (field) { case STARTS, COLUMNS, ROWS, META -> 4; default -> 8; };
            int count = switch (field) {
                case STARTS -> Math.addExact(cells, 1);
                case COLUMNS, VALUES -> Math.multiplyExact(cells, 7);
                case ROWS, PRESSURE -> cells;
                case INPUT -> 1;
                case WORK -> Math.multiplyExact(cells, 5);
                case META -> Math.multiplyExact(cells, 2);
                case PROBLEM -> Math.addExact(Math.multiplyExact(cells, 3), 1);
                case CONTROL -> 16;
                case PARTIALS -> Math.multiplyExact((cells + 255) / 256, 4);
            };
            cursor = align(cursor);
            var column = new VulkanLiquidStateLayout.Column(cursor, count, width);
            pressureColumns.put(field, column);
            cursor = Math.addExact(cursor, column.bytes());
        }
        multigridColumn = new VulkanLiquidStateLayout.Column(align(cursor), hierarchy.workspaceDoubles(), 8);
        long bytes = align(multigridColumn.offset() + multigridColumn.bytes());
        var initial = ByteBuffer.allocate(Math.toIntExact(bytes)).order(ByteOrder.LITTLE_ENDIAN);
        liquid.importState(initial, inventory, momentum);
        var masks = geometry.get(GeometryField.FIXED_MASKS);
        var occupied = geometry.get(GeometryField.OCCUPIED_SOLID);
        for (int cell = 0; cell < cells; cell++) {
            long capacity = (long) Integer.bitCount(~fixed.mask(cell) & 255) << 21;
            if (occupiedSolid[cell] < 0 || occupiedSolid[cell] > capacity)
                throw new IllegalArgumentException("Solid inventory exceeds fixed open cell capacity");
            initial.putInt(Math.toIntExact(masks.offset() + 4L * cell), fixed.mask(cell));
            initial.putLong(Math.toIntExact(occupied.offset() + 8L * cell), occupiedSolid[cell]);
        }
        storage = new VulkanRegionStorage(context, bytes, 1024 * 1024);
        try { storage.initialize(initial, 0); }
        catch (RuntimeException | LinkageError failure) {
            try { storage.close(); } catch (RuntimeException | LinkageError closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    /** This stage prepares geometry only. The mechanical pipeline appends force/pressure/transport stages. */
    long rebuildGeometry() { return prepareFields(null, false); }

    /** Ambient-pressure liquid projection fields; finite gas and material transport join before a full step can publish. */
    long prepareMechanicalFields(VulkanLiquidProjection.Parameters parameters) { return prepareFields(parameters, false); }

    long prepareMechanicalFields(VulkanLiquidProjection.Parameters parameters, boolean zeroInitialPressure) {
        return prepareFields(parameters, zeroInitialPressure);
    }

    private long prepareFields(VulkanLiquidProjection.Parameters parameters, boolean zeroInitialPressure) {
        try (var source = storage.capture(); var candidate = storage.prepare(source)) {
            VulkanRegionStorage.ComputeCommands prepare = (command, input, output, errors, staging, stack) -> {
                var buffers = new ArrayList<FlipKernelBindings.BufferRange>(14);
                buffers.addAll(liquid.ranges(output, CELL_OFFSETS, NUMERIC_VOLUMES, MASS_UNITS));
                buffers.add(range(output, GeometryField.FIXED_MASKS));
                buffers.add(range(output, GeometryField.OCCUPIED_SOLID));
                buffers.addAll(liquid.ranges(output, MOMENTUM_X, MOMENTUM_Y, MOMENTUM_Z));
                buffers.add(range(output, GeometryField.CELLS));
                buffers.add(range(output, GeometryField.PLANES));
                buffers.add(range(output, GeometryField.LIQUID_MICRO));
                buffers.add(range(output, GeometryField.FACES));
                buffers.add(new FlipKernelBindings.BufferRange(errors));
                buffers.add(range(output, GeometryField.FLUXES));
                kernel.rebindRanges(buffers);
                int sx=liquid.sizeX(),sy=liquid.sizeY(),sz=liquid.sizeZ();
                kernel.record(command, sx, sy, sz);
                if(parameters!=null) {
                    if (zeroInitialPressure) {
                        var column = pressureColumns.get(PressureField.PRESSURE);
                        VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                VK_ACCESS_TRANSFER_WRITE_BIT, stack);
                        vkCmdFillBuffer(command, output.handle(), column.offset(), column.bytes(), 0);
                        VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
                    }
                    var assembly = new ArrayList<FlipKernelBindings.BufferRange>(10);
                    assembly.add(range(output, GeometryField.CELLS));
                    assembly.add(range(output, GeometryField.FACES));
                    for (PressureField field : new PressureField[]{PressureField.STARTS,PressureField.COLUMNS,PressureField.VALUES,
                            PressureField.ROWS,PressureField.META,PressureField.PROBLEM,PressureField.PRESSURE})
                        assembly.add(range(output,field));
                    assembly.add(new FlipKernelBindings.BufferRange(errors));
                    projection.rebindRanges(assembly);
                    projection.prepare(command,sx,sy,sz,parameters);
                    var solve = new ArrayList<FlipKernelBindings.BufferRange>(12);
                    for(PressureField field:PressureField.values())solve.add(range(output,field));
                    solve.add(new FlipKernelBindings.BufferRange(errors));
                    pressure.rebindRanges(solve);
                    var coarse = new ArrayList<FlipKernelBindings.BufferRange>(8);
                    for(PressureField field : new PressureField[]{PressureField.STARTS,PressureField.COLUMNS,PressureField.VALUES,
                            PressureField.ROWS,PressureField.META,PressureField.WORK,PressureField.CONTROL})
                        coarse.add(range(output,field));
                    coarse.add(new FlipKernelBindings.BufferRange(output,multigridColumn.offset(),multigridColumn.bindingBytes()));
                    multigrid.rebindRanges(coarse);
                    pressure.recordInitialize(command,liquid.cellCount(),liquid.cellCount(),parameters.iterations(),multigrid,hierarchy);
                    pressure.recordIterations(command,liquid.cellCount(),liquid.cellCount(),parameters.iterations(),multigrid,hierarchy,
                            Math.min(8,parameters.iterations()));
                }
            };
            int error;
            if (parameters == null) error = candidate.execute(0, prepare);
            else {
                long runOffset = pressureColumns.get(PressureField.CONTROL).offset() + 24;
                var result = candidate.beginBatch(prepare, runOffset, 8);
                int scheduled = Math.min(8, parameters.iterations());
                while (result.error() == 0 && result.feedback().getDouble(0) != 0.0 && scheduled < parameters.iterations()) {
                    int batch = Math.min(8, parameters.iterations() - scheduled);
                    result = candidate.continueBatch((command, input, output, errors, staging, stack) ->
                            pressure.recordIterations(command, liquid.cellCount(), liquid.cellCount(), parameters.iterations(),
                                    multigrid, hierarchy, batch), runOffset, 8);
                    scheduled += batch;
                }
                error = result.error();
                if (error == 0) error = candidate.finishBatch((command, input, output, errors, staging, stack) -> {
                    pressure.recordFinish(command, liquid.cellCount(), liquid.cellCount(), parameters.iterations(),multigrid,hierarchy);
                    projection.project(command, liquid.sizeX(), liquid.sizeY(), liquid.sizeZ(), parameters);
                    kernel.recordTransportPreferences(command, liquid.sizeX(), liquid.sizeY(), liquid.sizeZ(),
                            parameters.cellSize(), parameters.duration());
                });
            }
            if (error != 0) {
                if ((error & 4) != 0 && parameters != null) {
                    var diagnostics = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                    candidate.read(pressureColumns.get(PressureField.CONTROL).offset(), diagnostics);
                    throw new IllegalStateException("Resident liquid pressure failed: iterations=" + (int)diagnostics.getDouble(0)
                            + ", residualRms=" + diagnostics.getDouble(8) + ", target=" + parameters.tolerance() + ", status=" + error);
                }
                throw new IllegalStateException("Resident liquid geometry failed: status=" + error);
            }
            return candidate.commit();
        }
    }

    /** Liquid inventory/momentum export is explicit and retains exact dyadic and integer columns. */
    VulkanLiquidStateLayout.State checkpointLiquid() {
        try (var capture = storage.capture()) {
            ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(liquid.bytes()));
            storage.read(capture, 0, bytes);
            return liquid.exportState(bytes.flip());
        }
    }

    VulkanRegionStorage.Metrics metrics() { return storage.metrics(); }
    double[] pressureDiagnostics() {
        try (var capture = storage.capture()) {
            var output = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            storage.read(capture, pressureColumns.get(PressureField.CONTROL).offset(), output);
            return new double[]{output.getDouble(0),output.getDouble(8),output.getDouble(16)};
        }
    }
    VulkanLiquidStateLayout layout() { return liquid; }

    private FlipKernelBindings.BufferRange range(GpuBuffer buffer, GeometryField field) {
        var column = geometry.get(field);
        return new FlipKernelBindings.BufferRange(buffer, column.offset(), column.bindingBytes());
    }
    private FlipKernelBindings.BufferRange range(GpuBuffer buffer, PressureField field) {
        var column = pressureColumns.get(field);
        return new FlipKernelBindings.BufferRange(buffer, column.offset(), column.bindingBytes());
    }
    private long align(long value) { return Math.addExact(value, liquid.alignment() - 1) & -liquid.alignment(); }
    @Override public void close() { storage.close(); }
}
