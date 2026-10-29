package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Exercises gas reservation and actual displacement through the production input and capacity kernels. */
public final class GpuLiquidCapacityInputsValidation {
    private static final long Q = 1L << 40;
    private static final int CELLS = 8;
    private static final int GROUPS = 2;
    private static final int MAX_ROUNDS = 512;
    private GpuLiquidCapacityInputsValidation() { }

    public static void main(String[] args) {
        int device = args.length == 0 ? 0 : Integer.parseInt(args[0]);
        try (var context = new VulkanDeviceContext(device, true, true);
             var inputs = new VulkanLiquidCapacityInputs(context);
             var solver = new VulkanLiquidCapacity(context)) {
            run(context, inputs, solver, false);
            run(context, inputs, solver, true);
        }
        System.out.println("Capacity inputs: finite gas reserve, displaced liquid and exhausted space passed");
    }

    private static void run(VulkanDeviceContext context, VulkanLiquidCapacityInputs inputs,
                            VulkanLiquidCapacity solver, boolean insufficient) {
        long alignment = Math.max(8, context.storageOffsetAlignment());
        var liquid = new VulkanLiquidStateLayout(2, 2, 2, 0, 0, 0, alignment, 1);
        var volumes = VulkanLiquidCellVolumes.layout(liquid, CELLS, -40, liquid.bytes(), alignment);
        var capacity = new VulkanLiquidCapacityLayout(2, 2, 2, GROUPS, volumes.endOffset(), alignment);
        long[] next = {capacity.endOffset()};
        int masks = alloc(next, CELLS * 4);
        int occupied = alloc(next, CELLS * 8);
        int faces = alloc(next, 36 * 8 * 4);
        int fluxes = alloc(next, 36 * 4 * 4);
        int nodes = alloc(next, CELLS * 4);
        int offsets = alloc(next, 4 * 4);
        int members = alloc(next, CELLS * 4);
        int counts = alloc(next, 4);
        int rows = alloc(next, 3 * 4);
        int stock = alloc(next, GROUPS * 8);
        int gasVolumes = alloc(next, GROUPS * 8);
        int gasStates = alloc(next, GROUPS * 18 * 4);
        int pressure = alloc(next, GROUPS * 8);
        int mode = alloc(next, GROUPS * 4);
        int header = alloc(next, 40 * 4);
        int dispatch = alloc(next, 3 * 4);
        long scratch = (next[0] + alignment - 1) & -alignment;
        var inputLayout = new VulkanLiquidCapacityInputs.Layout(volumes, capacity, masks, occupied, faces, fluxes,
                -1, -1, null, null, null, null,
                new VulkanLiquidStateLayout.Column(4L * header, 40, 4),
                new VulkanLiquidStateLayout.Column(4L * dispatch, 3, 4), scratch);
        var solve = new VulkanLiquidCapacity.Layout(capacity, scratch, alignment);
        var data = ByteBuffer.allocate(Math.toIntExact(solve.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
        capacity.initializeHeader(data);
        solve.initializeHeader(data);
        int[] inputHeader = new int[40];
        inputHeader[0] = 2;
        inputHeader[1] = 2;
        inputHeader[2] = 2;
        inputHeader[3] = CELLS;
        inputHeader[4] = -40;
        inputHeader[5] = word(volumes.quantized());
        inputHeader[6] = word(volumes.totalSigns());
        inputHeader[7] = word(volumes.totalExponents());
        inputHeader[8] = masks;
        inputHeader[9] = occupied;
        inputHeader[10] = faces;
        inputHeader[11] = fluxes;
        inputHeader[12] = gasVolumes;
        inputHeader[13] = gasStates;
        inputHeader[14] = pressure;
        inputHeader[15] = mode;
        inputHeader[16] = capacity.word(VulkanLiquidCapacityLayout.Field.K);
        inputHeader[17] = capacity.word(VulkanLiquidCapacityLayout.Field.LOWER);
        inputHeader[18] = capacity.word(VulkanLiquidCapacityLayout.Field.UPPER);
        inputHeader[19] = capacity.word(VulkanLiquidCapacityLayout.Field.GROUP_ID);
        inputHeader[20] = capacity.word(VulkanLiquidCapacityLayout.Field.PREFERRED);
        inputHeader[21] = capacity.word(VulkanLiquidCapacityLayout.Field.OPEN_NEIGHBORS);
        inputHeader[22] = GROUPS;
        inputHeader[23] = nodes;
        inputHeader[24] = offsets;
        inputHeader[25] = members;
        inputHeader[26] = counts;
        inputHeader[27] = rows;
        inputHeader[28] = stock;
        inputHeader[29] = capacity.word(VulkanLiquidCapacityLayout.Field.GROUP_CAPACITY);
        inputHeader[30] = dispatch;
        inputHeader[34] = word(volumes.totalOffsets());
        inputHeader[35] = word(volumes.totalLimbs());
        inputHeader[36] = -1;
        inputHeader[37] = -1;
        for (int i = 0; i < inputHeader.length; i++) {
            putInt(data, header + i, inputHeader[i]);
        }
        putDouble(data, header + 32, .125);
        long[] source = insufficient
                ? new long[]{Q, Q, Q, Q, Q, Q, Q, Q}
                : new long[]{Q, 0, Q, Q, Q / 2, Q / 2, Q / 2, Q / 2};
        for (int cell = 0; cell < CELLS; cell++) {
            putLong(data, word(volumes.quantized()) + 2 * cell, source[cell]);
            putInt(data, word(volumes.totalOffsets()) + cell, cell);
            putInt(data, word(volumes.totalSigns()) + cell, source[cell] == 0 ? 0 : 1);
            putInt(data, word(volumes.totalExponents()) + cell, -2);
            putInt(data, word(volumes.totalLimbs()) + cell, (int) (source[cell] / (Q / 4)));
            putInt(data, nodes + cell, cell / 4 + 1);
            putInt(data, members + cell, cell);
        }
        putInt(data, word(volumes.totalOffsets()) + CELLS, CELLS);
        // The first cell loses one quarter of its pore space to a stationary body.
        putLong(data, occupied, 1L << 22);
        for (int face = 0; face < 36; face++) {
            putDouble(data, faces + 8 * face + 4, 1.0);
        }
        putInt(data, counts, GROUPS);
        putInt(data, offsets + 1, 0);
        putInt(data, offsets + 2, 4);
        putInt(data, offsets + 3, 8);
        for (int group = 0; group < GROUPS; group++) {
            putInt(data, rows + group, group);
            putLong(data, stock + 2 * group, 1);
            // No predicted pressure rise: old dry space is larger than future dry space in group 0.
            putDouble(data, gasVolumes + 2 * group, (group == 0 ? 1.0 : 2.0) * .125);
        }
        putInt(data, rows + GROUPS, GROUPS);
        try (var storage = new VulkanRegionStorage(context, solve.endOffset(), 1024 * 1024)) {
            storage.initialize(data, 0);
            try (var sourceState = storage.capture(); var candidate = storage.prepare(sourceState)) {
                var feedback = candidate.beginBatch((command, input, output, errors, staging, stack) -> {
                    var arena = new FlipKernelBindings.BufferRange(output);
                    var errorBuffer = new FlipKernelBindings.BufferRange(errors);
                    inputs.rebindRanges(arena, errorBuffer);
                    solver.rebindRanges(arena, errorBuffer);
                    inputs.recordDisplacement(command, inputLayout);
                    solver.recordInitialize(command, solve, MAX_ROUNDS);
                    solver.recordIterations(command, solve, 32, 3);
                }, capacity.column(VulkanLiquidCapacityLayout.Field.CONTROL).offset(), 12);
                int status = feedback.feedback().getInt(0);
                int rounds = feedback.feedback().getInt(4);
                int error = feedback.error();
                while (error == 0 && status == VulkanLiquidCapacity.RUNNING && rounds < MAX_ROUNDS) {
                    int count = Math.min(32, MAX_ROUNDS - rounds);
                    int phase = feedback.feedback().getInt(8);
                    feedback = candidate.continueBatch(
                            (command, input, output, errors, staging, stack) ->
                                    solver.recordIterations(command, solve, count, phase),
                            capacity.column(VulkanLiquidCapacityLayout.Field.CONTROL).offset(), 12);
                    status = feedback.feedback().getInt(0);
                    rounds = feedback.feedback().getInt(4);
                    error = feedback.error();
                }
                int expected = insufficient ? VulkanLiquidCapacity.INFEASIBLE : VulkanLiquidCapacity.FEASIBLE;
                if (error != 0 || status != expected) {
                    throw new AssertionError("Capacity status=" + status + " error=" + error
                            + " insufficient=" + insufficient);
                }
                var result = ByteBuffer.allocate(Math.toIntExact(capacity.bytes())).order(ByteOrder.LITTLE_ENDIAN);
                candidate.read(capacity.baseOffset(), result);
                if (!insufficient) {
                    long firstGroupLimit = getLong(result, capacity, VulkanLiquidCapacityLayout.Field.GROUP_CAPACITY, 0);
                    long secondGroupLimit = getLong(result, capacity, VulkanLiquidCapacityLayout.Field.GROUP_CAPACITY, 1);
                    if (firstGroupLimit != 3 * Q || secondGroupLimit != 2 * Q) {
                        throw new AssertionError("Gas limits do not preserve source: "
                                + firstGroupLimit + ", " + secondGroupLimit);
                    }
                    if (firstGroupLimit >= 15 * Q / 4 || secondGroupLimit >= 4 * Q) {
                        throw new AssertionError("Finite gas lost positive volume");
                    }
                    if (getLong(result, capacity, VulkanLiquidCapacityLayout.Field.UPPER, 0) != 3 * Q / 4) {
                        throw new AssertionError("Future body occupancy lost");
                    }
                    int finishError = candidate.finishBatch((command, input, output, errors, staging, stack) ->
                            solver.recordFinish(command, solve));
                    if (finishError != 0) {
                        throw new AssertionError("Capacity finish failed");
                    }
                    var acceptedColumn = capacity.column(VulkanLiquidCapacityLayout.Field.ACCEPTED);
                    var accepted = ByteBuffer.allocate(Math.toIntExact(acceptedColumn.bytes())).order(ByteOrder.LITTLE_ENDIAN);
                    candidate.read(acceptedColumn.offset(), accepted);
                    data.duplicate().position(Math.toIntExact(capacity.baseOffset())).put(result.duplicate().clear());
                    GpuLiquidCapacityReplay.verify(capacity, data, accepted, 2, 2, 2);
                    long retained = accepted.getLong(0);
                    if (retained > 3 * Q / 4) {
                        throw new AssertionError("Displaced liquid did not leave cell");
                    }
                    long total = 0;
                    for (int slot = 0; slot < 7; slot++) {
                        total += accepted.getLong(8 * slot);
                    }
                    if (total != Q) {
                        throw new AssertionError("Displacement did not conserve source liquid");
                    }
                }
            }
        }
    }

    private static int alloc(long[] next, int bytes) {
        next[0] = (next[0] + 7) & -8L;
        int result = Math.toIntExact(next[0] / 4);
        next[0] += bytes;
        return result;
    }

    private static int word(VulkanLiquidStateLayout.Column column) {
        return Math.toIntExact(column.offset() / 4);
    }

    private static void putInt(ByteBuffer buffer, int word, int value) {
        buffer.putInt(4 * word, value);
    }

    private static void putLong(ByteBuffer buffer, int word, long value) {
        buffer.putLong(4 * word, value);
    }

    private static void putDouble(ByteBuffer buffer, int word, double value) {
        buffer.putDouble(4 * word, value);
    }

    private static long getLong(ByteBuffer buffer, VulkanLiquidCapacityLayout layout,
                                VulkanLiquidCapacityLayout.Field field, int index) {
        return buffer.getLong(Math.toIntExact(layout.column(field).offset() - layout.baseOffset() + 8L * index));
    }
}
