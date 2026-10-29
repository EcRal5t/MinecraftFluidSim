package mmm.gpu;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;

import static org.lwjgl.vulkan.VK10.vkCmdFillBuffer;

/** Measures generation management only; no fluid equations are executed by this benchmark. */
public final class GpuRegionStorageBenchmark {
    private GpuRegionStorageBenchmark() { }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int device = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int samples = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        if (samples < 1) throw new IllegalArgumentException("At least one sample is required");
        try (var context = new VulkanDeviceContext(device, true, true)) {
            System.out.println("Device: " + context.deviceName());
            System.out.println("Generation copy/adoption cost only; excludes fluid computation. One old capture stays pinned.");
            System.out.println("state_MiB,median_ms,p95_ms,max_ms,gpu_mean_ms,upload_B_per_step,readback_B_per_step,copy_B_per_step,allocated_generations,staging_MiB");
            for (int mib : new int[]{1, 16, 64}) {
                int bytes = mib * 1024 * 1024;
                try (var storage = new VulkanRegionStorage(context, bytes, 1024 * 1024)) {
                    storage.initialize(ByteBuffer.allocate(bytes), 0);
                    try (var retained = storage.capture()) {
                        for (int step = 0; step < 20; step++) advance(storage, bytes);
                        var before = storage.metrics();
                        long[] nanos = new long[samples];
                        for (int step = 0; step < samples; step++) {
                            long start = System.nanoTime();
                            advance(storage, bytes);
                            nanos[step] = System.nanoTime() - start;
                        }
                        var after = storage.metrics();
                        Arrays.sort(nanos);
                        System.out.printf("%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,%.1f%n", mib,
                                nanos[samples / 2] * 1e-6, nanos[(int) Math.ceil(samples * 0.95) - 1] * 1e-6,
                                nanos[samples - 1] * 1e-6, (after.gpuNanos() - before.gpuNanos()) * 1e-6 / samples,
                                (after.uploadBytes() - before.uploadBytes()) / samples,
                                (after.readbackBytes() - before.readbackBytes()) / samples,
                                (after.copiedBytes() - before.copiedBytes()) / samples,
                                after.allocatedGenerations(), after.stagingBytes() / 1048576.0);
                    }
                }
            }
        }
    }

    private static void advance(VulkanRegionStorage storage, int bytes) {
        try (var source = storage.capture(); var candidate = storage.prepare(source)) {
            int value = (int) (source.generation() + 1);
            candidate.execute(0, (command, input, output, errors, staging, stack) -> {
                VulkanRegionStorage.barrier(command,
                        org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                        org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT, stack);
                vkCmdFillBuffer(command, output.handle(), bytes - 4L, 4, value);
            });
            candidate.commit();
        }
    }
}
