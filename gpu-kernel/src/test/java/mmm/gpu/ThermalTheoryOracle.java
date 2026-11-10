package mmm.gpu;

import java.util.Locale;

/** Independent closed-system heat curves and steady series-resistance references. */
public final class ThermalTheoryOracle {
    private ThermalTheoryOracle() { }

    /** Exact temperatures for two finite heat capacities joined by a constant conductance. */
    public static double[] twoNodes(double c1, double c2, double conductance,
                                    double t1, double t2, double seconds) {
        requirePositive(c1, c2);
        requireFinite(t1, t2);
        if (!(Double.isFinite(conductance) && conductance >= 0 && Double.isFinite(seconds) && seconds >= 0))
            throw new IllegalArgumentException("Invalid conductance or time");
        double heat = twoNodeHeat(c1, c2, conductance, t1, t2, seconds);
        return new double[] {t1 - heat / c1, t2 + heat / c2};
    }

    /** Signed heat transferred from node 1 to node 2; expm1 preserves short-time precision. */
    public static double twoNodeHeat(double c1, double c2, double conductance,
                                     double t1, double t2, double seconds) {
        requirePositive(c1, c2);
        requireFinite(t1, t2);
        if (!(Double.isFinite(conductance) && conductance >= 0 && Double.isFinite(seconds) && seconds >= 0))
            throw new IllegalArgumentException("Invalid conductance or time");
        double inverseCapacity = 1 / c1 + 1 / c2;
        return (t1 - t2) * -Math.expm1(-conductance * inverseCapacity * seconds) / inverseCapacity;
    }

    /** Exact three-node chain via eigenvectors of the symmetric capacity-weighted Laplacian. */
    public static double[] threeNodes(double[] capacities, double[] conductances,
                                      double[] initialTemperatures, double seconds) {
        if (capacities.length != 3 || conductances.length != 2 || initialTemperatures.length != 3
                || !Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("Invalid three-node inputs");
        for (double capacity : capacities) requirePositive(capacity);
        requireFinite(initialTemperatures);
        for (double conductance : conductances)
            if (!Double.isFinite(conductance) || conductance < 0) throw new IllegalArgumentException("Invalid conductance");
        if (seconds == 0 || conductances[0] == 0 && conductances[1] == 0) return initialTemperatures.clone();
        if (conductances[0] == 0) {
            var pair = twoNodes(capacities[1], capacities[2], conductances[1],
                    initialTemperatures[1], initialTemperatures[2], seconds);
            return new double[] {initialTemperatures[0], pair[0], pair[1]};
        }
        if (conductances[1] == 0) {
            var pair = twoNodes(capacities[0], capacities[1], conductances[0],
                    initialTemperatures[0], initialTemperatures[1], seconds);
            return new double[] {pair[0], pair[1], initialTemperatures[2]};
        }
        double[][] matrix = new double[3][3];
        for (int edge = 0; edge < 2; edge++) {
            int next = edge + 1;
            double g = conductances[edge];
            matrix[edge][edge] += g / capacities[edge];
            matrix[next][next] += g / capacities[next];
            matrix[edge][next] = matrix[next][edge] = -g / Math.sqrt(capacities[edge] * capacities[next]);
        }
        double[][] vectors = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        for (int iteration = 0; iteration < 64; iteration++) {
            int p = 0, q = 1;
            if (Math.abs(matrix[0][2]) > Math.abs(matrix[p][q])) { p = 0; q = 2; }
            if (Math.abs(matrix[1][2]) > Math.abs(matrix[p][q])) { p = 1; q = 2; }
            double off = matrix[p][q];
            if (Math.abs(off) <= 1e-15 * Math.max(1e-30, maxDiagonal(matrix))) break;
            double tau = (matrix[q][q] - matrix[p][p]) / (2 * off);
            double tangent = Math.copySign(1.0, tau) / (Math.abs(tau) + Math.hypot(1.0, tau));
            double cosine = 1 / Math.sqrt(1 + tangent * tangent), sine = tangent * cosine;
            double pp = matrix[p][p], qq = matrix[q][q];
            matrix[p][p] = pp - tangent * off;
            matrix[q][q] = qq + tangent * off;
            matrix[p][q] = matrix[q][p] = 0;
            for (int row = 0; row < 3; row++) {
                if (row != p && row != q) {
                    double a = matrix[row][p], b = matrix[row][q];
                    matrix[row][p] = matrix[p][row] = cosine * a - sine * b;
                    matrix[row][q] = matrix[q][row] = sine * a + cosine * b;
                }
                double a = vectors[row][p], b = vectors[row][q];
                vectors[row][p] = cosine * a - sine * b;
                vectors[row][q] = sine * a + cosine * b;
            }
        }
        double[] result = new double[3];
        double referenceTemperature = initialTemperatures[0];
        int zeroMode = 0;
        for (int mode = 1; mode < 3; mode++) if (matrix[mode][mode] < matrix[zeroMode][zeroMode]) zeroMode = mode;
        for (int mode = 0; mode < 3; mode++) {
            double projection = 0;
            for (int node = 0; node < 3; node++)
                projection += vectors[node][mode] * Math.sqrt(capacities[node]) * (initialTemperatures[node] - referenceTemperature);
            double rate = mode == zeroMode ? 0 : Math.max(0, matrix[mode][mode]);
            double decayed = projection * Math.exp(-rate * seconds);
            for (int node = 0; node < 3; node++) result[node] += vectors[node][mode] * decayed / Math.sqrt(capacities[node]);
        }
        for (int node = 0; node < 3; node++) result[node] += referenceTemperature;
        return result;
    }

    /** Steady heat rate through layers between two fixed-temperature reservoirs. */
    public static double seriesHeatRate(double hotK, double coldK, double area,
                                        double[] thickness, double[] conductivity) {
        requirePositive(area);
        requireFinite(hotK, coldK);
        if (thickness.length == 0 || thickness.length != conductivity.length) throw new IllegalArgumentException("Invalid layers");
        double resistance = 0;
        for (int layer = 0; layer < thickness.length; layer++) {
            requirePositive(thickness[layer]);
            if (!Double.isFinite(conductivity[layer]) || conductivity[layer] < 0) throw new IllegalArgumentException("Invalid conductivity");
            if (conductivity[layer] == 0) return 0;
            resistance += thickness[layer] / (conductivity[layer] * area);
        }
        return (hotK - coldK) / resistance;
    }

    public static double maximumError(double[] measured, double[] reference) {
        if (measured.length != reference.length) throw new IllegalArgumentException("Temperature vectors differ");
        double maximum = 0;
        for (int i = 0; i < measured.length; i++) {
            requireFinite(measured[i], reference[i]);
            maximum = Math.max(maximum, Math.abs(measured[i] - reference[i]));
        }
        return maximum;
    }

    private static double maxDiagonal(double[][] matrix) {
        return Math.max(Math.abs(matrix[0][0]), Math.max(Math.abs(matrix[1][1]), Math.abs(matrix[2][2])));
    }
    private static void requirePositive(double... values) {
        for (double value : values) if (!(Double.isFinite(value) && value > 0)) throw new IllegalArgumentException("Expected positive finite value");
    }
    private static void requireFinite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) throw new IllegalArgumentException("Expected finite value");
    }

    public static void main(String[] args) {
        double[] capacities = {4.18e6, 3.9e6, 1200}, conductances = {100, 5}, initial = {350, 300, 280};
        double[] pair = twoNodes(capacities[0], capacities[1], conductances[0], initial[0], initial[1], 600);
        double[] disconnected = threeNodes(capacities, new double[] {conductances[0], 0}, initial, 600);
        if (Math.abs(pair[0] - disconnected[0]) > 1e-10 || Math.abs(pair[1] - disconnected[1]) > 1e-10
                || Math.abs(disconnected[2] - initial[2]) > 1e-10)
            throw new AssertionError("Three-node eigen solution disagrees with the independent two-node solution");
        double amplitude = 20 * Math.exp(-.1 * 7);
        double[] symmetric = threeNodes(new double[] {10, 10, 10}, new double[] {1, 1},
                new double[] {320, 300, 280}, 7);
        if (maximumError(symmetric, new double[] {300 + amplitude, 300, 300 - amplitude}) > 1e-10)
            throw new AssertionError("Three-node eigen solution disagrees with the symmetric analytic mode");
        double[] reference = threeNodes(capacities, conductances, initial, 21600);
        double energyChange = 0;
        for (int node = 0; node < 3; node++) energyChange += capacities[node] * (reference[node] - initial[node]);
        if (Math.abs(energyChange) > 1e-5) throw new AssertionError("Three-node reference does not conserve energy");
        System.out.println("seconds,water_K,iron_K,air_K");
        for (double time : new double[] {0, 60, 600, 3600, 21600}) {
            double[] curve = threeNodes(capacities, conductances, initial, time);
            System.out.printf(Locale.ROOT, "%.0f,%.9f,%.9f,%.9f%n", time, curve[0], curve[1], curve[2]);
        }
        double q = seriesHeatRate(350, 280, 1, new double[] {.5, .25, .5}, new double[] {80, 1, 5});
        if (Math.abs(q - 70 / (.5 / 80 + .25 + .5 / 5)) > 1e-12
                || seriesHeatRate(350, 280, 1, new double[] {.5, .1}, new double[] {80, 0}) != 0)
            throw new AssertionError("Series resistance reference");
        System.out.printf(Locale.ROOT, "series_W=%.9f insulated_W=0%n", q);
        System.out.println("Compare max absolute node-temperature error at the same physical times; halve dt and require error to decrease toward the reference.");
    }
}
