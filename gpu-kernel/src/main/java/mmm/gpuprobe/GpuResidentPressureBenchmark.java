package mmm.gpuprobe;

import mmm.fluid.FluidSolverConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/** Same manufactured CSR with matrix, input and workspace retained in a device generation. */
public final class GpuResidentPressureBenchmark {
    private GpuResidentPressureBenchmark() { }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int device = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int dimension = args.length > 1 ? Integer.parseInt(args[1]) : 64;
        int samples = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        if (samples < 1) throw new IllegalArgumentException("At least one sample is required");
        var config = FluidSolverConfig.prototypeDefaults();
        var fixture = GpuPressureScaleBenchmark.manufacture(dimension, config.pressureTolerance(), config.maxPressureIterations());
        int n = fixture.size(), edges = fixture.values().length;
        long[] lengths = {4L * (n + 1), 4L * edges, 8L * edges, 4L * n, 8L * n, 8L * n,
                40L * n, 8L * n, 24L * n + 8, 128, 32L * ((n + 255) / 256)};
        try (var context = new VulkanDeviceContext(device, true, true);
             var pressure = new VulkanPressureKernel(context, 4096)) {
            long[] offsets = new long[lengths.length];
            long total = 0, alignment = Math.max(8, context.storageOffsetAlignment());
            for (int i = 0; i < lengths.length; i++) {
                offsets[i] = (total + alignment - 1) / alignment * alignment;
                total = offsets[i] + lengths[i];
            }
            ByteBuffer initial = ByteBuffer.allocate(Math.toIntExact(total)).order(ByteOrder.nativeOrder());
            initial.slice(Math.toIntExact(offsets[0]), Math.toIntExact(lengths[0])).order(ByteOrder.nativeOrder()).asIntBuffer().put(fixture.starts());
            initial.slice(Math.toIntExact(offsets[1]), Math.toIntExact(lengths[1])).order(ByteOrder.nativeOrder()).asIntBuffer().put(fixture.columns());
            initial.slice(Math.toIntExact(offsets[2]), Math.toIntExact(lengths[2])).order(ByteOrder.nativeOrder()).asDoubleBuffer().put(fixture.values());
            initial.slice(Math.toIntExact(offsets[3]), Math.toIntExact(lengths[3])).order(ByteOrder.nativeOrder()).asIntBuffer().put(fixture.rows());
            initial.slice(Math.toIntExact(offsets[8]), Math.toIntExact(lengths[8])).order(ByteOrder.nativeOrder()).asDoubleBuffer().put(fixture.rhs());
            initial.putDouble(Math.toIntExact(offsets[8] + 24L * n), fixture.tolerance());
            try (var storage = new VulkanRegionStorage(context, total, 1024 * 1024)) {
                storage.initialize(initial, 0);
                for (int warm = 0; warm < 5; warm++) advance(storage, pressure, fixture, offsets, lengths);
                var before = storage.metrics();
                long[] nanos = new long[samples];
                for (int sample = 0; sample < samples; sample++) {
                    long start = System.nanoTime();
                    advance(storage, pressure, fixture, offsets, lengths);
                    nanos[sample] = System.nanoTime() - start;
                }
                var after = storage.metrics();
                Arrays.sort(nanos);
                System.out.println("Device: " + context.deviceName());
                System.out.println("Resident pressure only: fixed manufactured CSR, zero initial pressure each solve, original tolerance/iterations; excludes fluid geometry, transport and thermochemistry.");
                System.out.println("Includes candidate device copy, host command recording, whole pressure solve, status readback and generation adoption.");
                System.out.println("grid,median_ms,p95_ms,max_ms,gpu_mean_ms,upload_B_per_solve,readback_B_per_solve,copy_B_per_solve,device_MiB,staging_MiB");
                System.out.printf("%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%.1f,%.1f%n", dimension,
                        nanos[samples / 2] * 1e-6, nanos[(int) Math.ceil(samples * 0.95) - 1] * 1e-6,
                        nanos[samples - 1] * 1e-6, (after.gpuNanos() - before.gpuNanos()) * 1e-6 / samples,
                        (after.uploadBytes() - before.uploadBytes()) / samples,
                        (after.readbackBytes() - before.readbackBytes()) / samples,
                        (after.copiedBytes() - before.copiedBytes()) / samples,
                        after.deviceBytes() / 1048576.0, after.stagingBytes() / 1048576.0);
            }
        }
    }

    private static void advance(VulkanRegionStorage storage, VulkanPressureKernel pressure,
                                GpuPressureScaleBenchmark.Fixture fixture, long[] offsets, long[] lengths) {
        try (var source = storage.capture(); var candidate = storage.prepare(source)) {
            int error = candidate.execute(0, (command, input, output, errors, staging, stack) -> {
                var ranges = new ArrayList<FlipKernelBindings.BufferRange>(12);
                for (int i = 0; i < lengths.length; i++)
                    ranges.add(new FlipKernelBindings.BufferRange(output, offsets[i], lengths[i]));
                ranges.add(new FlipKernelBindings.BufferRange(errors));
                pressure.rebindRanges(ranges);
                pressure.record(command, fixture.size(), fixture.size(), fixture.maximumIterations());
            });
            if (error != 0) throw new IllegalStateException("Resident pressure did not converge: status=" + error);
            candidate.commit();
        }
    }
}
