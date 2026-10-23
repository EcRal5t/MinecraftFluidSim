package mmm.gpuprobe;

import mmm.fluid.FixedGeometrySnapshot;
import mmm.fluid.MacMomentumState;
import mmm.inventory.DeviceLiquidInventory;
import mmm.units.DeviceDyadicArray;
import mmm.units.Mass;

import java.util.Arrays;
import java.util.Locale;

/** Geometry-stage cost on resident material inventories; deliberately excludes other mechanical stages. */
public final class GpuLiquidGeometryBenchmark {
    private GpuLiquidGeometryBenchmark() { }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int device = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int samples = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        boolean project = args.length > 2 && Boolean.parseBoolean(args[2]);
        var parameters = new VulkanLiquidProjection.Parameters(.25f,.05f,9.81f,1e-5f,200);
        if (samples < 1) throw new IllegalArgumentException("At least one sample required");
        try (var context = new VulkanDeviceContext(device, true, true);
             var geometry = new VulkanLiquidGeometry(context);
             var projection = new VulkanLiquidProjection(context);
             var pressure = new VulkanPressureKernel(context,4096);
             var multigrid = new VulkanPressureMultigrid(context)) {
            System.out.println("Device: " + context.deviceName());
            System.out.println(project ? "Resident geometry + ambient liquid projection: device gravity, physical CSR assembly, pressure, face response and PLIC swept flux preferences."
                    : "Resident geometry only: mixed stocks -> cell totals -> coarse PLIC/micro geometry -> MAC face geometry.");
            System.out.println("Includes generation copy/commit and status readback; authoritative inventory/momentum are retained. Transport, finite-gas coupling and thermochemistry are pending.");
            if (project) System.out.println("Each solve starts from zero pressure; repeated identical warm-start solutions cannot hide solver cost.");
            System.out.println("scene,grid,stocks,median_ms,p95_ms,max_ms,gpu_mean_ms,upload_B_per_step,readback_B_per_step,device_MiB,staging_MiB");
            for (int dimension : args.length>3?Arrays.stream(args[3].split(",")).mapToInt(Integer::parseInt).toArray():new int[]{16, 32, 64}) for (boolean cut : new boolean[]{false, true}) {
                var fixture = fixture(dimension, cut);
                try (var region = new VulkanLiquidRegion(context, geometry, projection, pressure, multigrid, fixture.inventory, fixture.momentum,
                        fixture.fixed, fixture.occupied)) {
                    for (int warm = 0; warm < 5; warm++) {
                        if(project)region.prepareMechanicalFields(parameters,true);else region.rebuildGeometry();
                    }
                    var before = region.metrics();
                    long[] nanos = new long[samples];
                    for (int sample = 0; sample < samples; sample++) {
                        long start = System.nanoTime();
                        if(project)region.prepareMechanicalFields(parameters,true);else region.rebuildGeometry();
                        nanos[sample] = System.nanoTime() - start;
                    }
                    var after = region.metrics();
                    Arrays.sort(nanos);
                    System.out.printf("%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%d,%d,%.1f,%.1f%n",
                            cut ? "cut-partial-mixture" : "dam-mixture", dimension, fixture.inventory.stockCount(),
                            nanos[samples / 2] * 1e-6, nanos[(int) Math.ceil(samples * 0.95) - 1] * 1e-6,
                            nanos[samples - 1] * 1e-6, (after.gpuNanos() - before.gpuNanos()) * 1e-6 / samples,
                            (after.uploadBytes() - before.uploadBytes()) / samples,
                            (after.readbackBytes() - before.readbackBytes()) / samples,
                            after.deviceBytes() / 1048576.0, after.stagingBytes() / 1048576.0);
                    if (project) {
                        var diagnostics = region.pressureDiagnostics();
                        System.out.printf("pressure_result scene=%s grid=%d iterations=%d rms=%.9g success=%s%n",
                                cut?"cut-partial-mixture":"dam-mixture",dimension,(int)diagnostics[0],diagnostics[1],diagnostics[2]==1);
                    }
                }
            }
        }
    }

    record Fixture(DeviceLiquidInventory inventory, MacMomentumState momentum,
                   FixedGeometrySnapshot fixed, long[] occupied) { }

    static Fixture fixture(int size, boolean cut) {
        return fixture(size,cut,.25);
    }
    static Fixture fixture(int size,boolean cut,double cellSize){
        int cells = size * size * size;
        byte[] masks = new byte[cells];
        long[] occupied = new long[cells];
        int[] cellOffsets = new int[cells + 1];
        for (int c = 0; c < cells; c++) {
            int x = c % size, y = c / size % size, z = c / (size * size);
            boolean wet = cut || x < size / 2 && y < 3 * size / 4;
            cellOffsets[c + 1] = cellOffsets[c] + (wet ? 2 : 0);
            if (cut && (x + z) % 4 == 0) { masks[c] = 0x33; occupied[c] = 1 << 20; }
        }
        int stocks = cellOffsets[cells];
        int[] materials = new int[stocks], supports = new int[stocks], signs = new int[stocks],
                exponents = new int[stocks], limbs = new int[stocks], volumeOffsets = new int[stocks + 1];
        long[] mass = new long[stocks], energy = new long[stocks];
        for (int c = 0; c < cells; c++) {
            int x = c % size, y = c / size % size, z = c / (size * size);
            int volume = cut ? 16 + (x + 2 * y + 3 * z) % 24 : y == 3 * size / 4 - 1 ? 80 : 128;
            for (int row = cellOffsets[c]; row < cellOffsets[c + 1]; row++) {
                int material = row - cellOffsets[c], numerator = material == 0 ? volume / 3 : volume - volume / 3;
                int trailing = Integer.numberOfTrailingZeros(numerator);
                materials[row] = material; supports[row] = ~masks[c] & 255;
                volumeOffsets[row] = row; signs[row] = 1;
                exponents[row] = -7 + trailing; limbs[row] = numerator >> trailing;
                double kilograms = numerator / 128.0 * (material == 0 ? 1000 : 800) * cellSize * cellSize * cellSize;
                mass[row] = Mass.fromKilograms(kilograms);
                energy[row] = Double.doubleToRawLongBits(kilograms * 4200 * 293.15);
            }
        }
        volumeOffsets[stocks] = stocks;
        var inventory = new DeviceLiquidInventory(cellOffsets, materials, supports,
                new DeviceDyadicArray(volumeOffsets, signs, exponents, limbs), mass, energy, new int[stocks],
                new int[stocks + 1], new int[0], new long[0], new long[stocks]);
        var momentum = new MacMomentumState(size, size, size,
                new double[(size + 1) * size * size], new double[size * (size + 1) * size], new double[size * size * (size + 1)]);
        return new Fixture(inventory, momentum, FixedGeometrySnapshot.fromMasks(size, size, size, masks), occupied);
    }
}
