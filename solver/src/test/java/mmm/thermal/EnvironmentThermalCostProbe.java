package mmm.thermal;

import java.util.Arrays;
import java.util.Locale;

/** Synthetic CPU cost probe for heat exchange in a rectangular halo. */
public final class EnvironmentThermalCostProbe {
    private static final double DT = 0.5;
    private static final double IRON_CAPACITY = 3.9e6;
    private static final double AIR_CAPACITY = 1200.0;
    // Constructed coarse-grid approximations in W/K for a one-square-metre face.
    private static final double IRON_CONDUCTANCE = 80.0;
    private static final double AIR_EFFECTIVE_CONDUCTANCE = 0.2;
    private static final double SOLID_AIR_CONDUCTANCE = 5.0;

    private static final class Halo {
        final int nodes;
        final int faces;
        final int[] first;
        final int[] second;
        final double[] conductance;
        final double[] capacity;
        final double[] temperature;
        final double[] rate;
        double[] energy;
        double[] nextEnergy;

        Halo(int size, int layers) {
            int extent = size + 2 * layers;
            int[] indices = new int[extent * extent * extent];
            Arrays.fill(indices, -1);
            nodes = indices.length - size * size * size;
            capacity = new double[nodes];
            energy = new double[nodes];
            nextEnergy = new double[nodes];
            temperature = new double[nodes];
            rate = new double[nodes];
            int count = 0;
            for (int z = 0; z < extent; z++) {
                for (int y = 0; y < extent; y++) {
                    for (int x = 0; x < extent; x++) {
                        if (inside(x, y, z, size, layers)) continue;
                        int id = count++;
                        indices[(z * extent + y) * extent + x] = id;
                        // Alternating three-block slabs give continuous iron and air regions.
                        capacity[id] = (x / 3) % 2 == 0 ? IRON_CAPACITY : AIR_CAPACITY;
                        energy[id] = capacity[id] * (280.0 + x % 20);
                    }
                }
            }
            int[] firstBuffer = new int[nodes * 3];
            int[] secondBuffer = new int[nodes * 3];
            double[] conductanceBuffer = new double[nodes * 3];
            int countFaces = 0;
            for (int z = 0; z < extent; z++) {
                for (int y = 0; y < extent; y++) {
                    for (int x = 0; x < extent; x++) {
                        int position = (z * extent + y) * extent + x;
                        int i = indices[position];
                        if (i < 0) continue;
                        for (int axis = 0; axis < 3; axis++) {
                            if ((axis == 0 && x + 1 == extent) || (axis == 1 && y + 1 == extent)
                                    || (axis == 2 && z + 1 == extent)) continue;
                            int offset = axis == 0 ? 1 : axis == 1 ? extent : extent * extent;
                            int j = indices[position + offset];
                            if (j < 0) continue;
                            boolean ironI = capacity[i] == IRON_CAPACITY;
                            boolean ironJ = capacity[j] == IRON_CAPACITY;
                            double g = ironI && ironJ ? IRON_CONDUCTANCE
                                    : !ironI && !ironJ ? AIR_EFFECTIVE_CONDUCTANCE : SOLID_AIR_CONDUCTANCE;
                            firstBuffer[countFaces] = i;
                            secondBuffer[countFaces] = j;
                            conductanceBuffer[countFaces++] = g;
                            rate[i] += g / capacity[i];
                            rate[j] += g / capacity[j];
                        }
                    }
                }
            }
            faces = countFaces;
            first = Arrays.copyOf(firstBuffer, faces);
            second = Arrays.copyOf(secondBuffer, faces);
            conductance = Arrays.copyOf(conductanceBuffer, faces);
        }

        private static boolean inside(int x, int y, int z, int size, int layers) {
            return x >= layers && x < layers + size && y >= layers && y < layers + size
                    && z >= layers && z < layers + size;
        }

        void step() {
            System.arraycopy(energy, 0, nextEnergy, 0, nodes);
            for (int i = 0; i < nodes; i++) temperature[i] = energy[i] / capacity[i];
            for (int face = 0; face < faces; face++) {
                int i = first[face], j = second[face];
                double q = DT * conductance[face] * (temperature[j] - temperature[i])
                        / (1.0 + DT * (rate[i] + rate[j]));
                nextEnergy[i] += q;
                nextEnergy[j] -= q;
            }
            double[] previous = energy;
            energy = nextEnergy;
            nextEnergy = previous;
        }

        long arrayBytes() {
            return 8L * (5L * nodes + faces) + 8L * faces;
        }
    }

    private static void checkChain() {
        double[] c = {4.18e6, IRON_CAPACITY, AIR_CAPACITY};
        double[] initialTemperature = {350.0, 300.0, 280.0};
        double[] g = {100.0, SOLID_AIR_CONDUCTANCE};
        double[] rate = {g[0] / c[0], (g[0] + g[1]) / c[1], g[1] / c[2]};
        double[] energy = new double[3];
        for (int i = 0; i < 3; i++) energy[i] = c[i] * initialTemperature[i];
        double before = Arrays.stream(energy).sum();
        double[] result = energy.clone();
        for (int i = 0; i < 2; i++) {
            int j = i + 1;
            double q = DT * g[i] * (initialTemperature[j] - initialTemperature[i])
                    / (1.0 + DT * (rate[i] + rate[j]));
            result[i] += q;
            result[j] -= q;
        }
        double error = Arrays.stream(result).sum() - before;
        if (!(result[0] < energy[0] && result[1] > energy[1] && result[2] > energy[2])
                || Math.abs(error) > 1e-5) throw new AssertionError("Chain heat exchange failed: " + error);
        System.out.printf(Locale.ROOT, "chain delta K: water=%.9g iron=%.9g air=%.9g; energy error J=%.9g%n",
                result[0] / c[0] - initialTemperature[0], result[1] / c[1] - initialTemperature[1],
                result[2] / c[2] - initialTemperature[2], error);
    }

    private static void measure(int size, int layers) {
        long start = System.nanoTime();
        Halo halo = new Halo(size, layers);
        double buildMs = (System.nanoTime() - start) / 1e6;
        for (int i = 0; i < 300; i++) halo.step();
        long[] samples = new long[300];
        for (int i = 0; i < samples.length; i++) {
            start = System.nanoTime();
            halo.step();
            samples[i] = System.nanoTime() - start;
        }
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT,
                "box=%d halo=%d nodes=%d faces=%d build_ms=%.3f arrays_MiB=%.3f p50_ms=%.4f p95_ms=%.4f max_ms=%.4f%n",
                size, layers, halo.nodes, halo.faces, buildMs, halo.arrayBytes() / 1048576.0,
                samples[149] / 1e6, samples[284] / 1e6, samples[299] / 1e6);
    }

    public static void main(String[] args) {
        System.out.println("Synthetic CPU halo: iron/air slabs, 0.5 s timestep, 300 warmup and 300 measured steps.");
        System.out.println("Measured step: copy, temperature, face flux, buffer swap; excludes Minecraft sampling, GPU transfer and main simulation.");
        System.out.println("Face conductances W/K: iron=80, solid-air=5, effective air mixing=0.2 (constructed approximation).");
        checkChain();
        measure(32, 2);
        measure(32, 4);
        measure(64, 4);
    }
}
