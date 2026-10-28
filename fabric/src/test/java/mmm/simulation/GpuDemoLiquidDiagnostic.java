package mmm.simulation;

import java.util.ArrayDeque;
import mmm.fluid.FluidDomainState;

/** Read-only inventory and MAC diagnostics for production demo replays. */
final class GpuDemoLiquidDiagnostic {
    private double[] previous;

    void print(FluidDomainState state, String scenario, int step) {
        int sx = state.sizeX(), sy = state.sizeY(), sz = state.sizeZ(), count = sx * sy * sz;
        double h = state.config().cellSize(), h3 = h * h * h;
        double[] volumes = new double[count], masses = new double[count];
        long[] massUnits = new long[count];
        for (var stock : state.liquid().physicalStocks()) {
            int c = stock.key().cell();
            volumes[c] += stock.volumeFraction();
            massUnits[c] = Math.addExact(massUnits[c], stock.massUnits());
            masses[c] += stock.massKilograms();
        }
        boolean[] seen = new boolean[count];
        int components = 0, detachedComponents = 0, detachedCells = 0, suspendedCells = 0, thinSuspendedCells = 0;
        double detachedVolume = 0, suspendedVolume = 0, thinSuspendedVolume = 0;
        long detachedMass = 0, suspendedMass = 0;
        var yMomentum = state.momentum().copyY();
        for (int start = 0; start < count; start++) {
            if (volumes[start] <= 0 || seen[start]) continue;
            components++;
            var queue = new ArrayDeque<Integer>();
            var cells = new java.util.ArrayList<Integer>();
            queue.add(start); seen[start] = true;
            boolean supported = false;
            double componentVolume = 0; long componentMass = 0;
            while (!queue.isEmpty()) {
                int c = queue.removeFirst(); cells.add(c);
                int x = c % sx, y = c / sx % sy, z = c / (sx * sy);
                componentVolume += volumes[c]; componentMass = Math.addExact(componentMass, massUnits[c]);
                if (y == 0 || state.fixedGeometry().mask(c - sx) != 0) supported = true;
                for (int axis = 0; axis < 3; axis++) for (int sign : new int[] {-1, 1}) {
                    int nx = x + (axis == 0 ? sign : 0), ny = y + (axis == 1 ? sign : 0), nz = z + (axis == 2 ? sign : 0);
                    if (nx < 0 || nx >= sx || ny < 0 || ny >= sy || nz < 0 || nz >= sz) continue;
                    int adjacent = (nz * sy + ny) * sx + nx;
                    if (volumes[adjacent] > 0 && !seen[adjacent]) { seen[adjacent] = true; queue.add(adjacent); }
                }
            }
            if (!supported) {
                detachedComponents++; detachedCells += cells.size(); detachedVolume += componentVolume;
                detachedMass = Math.addExact(detachedMass, componentMass);
            }
            for (int c : cells) {
                int x = c % sx, y = c / sx % sy, z = c / (sx * sy);
                if (y == 0 || volumes[c - sx] > 0 || state.fixedGeometry().mask(c - sx) != 0) continue;
                suspendedCells++; suspendedVolume += volumes[c]; suspendedMass = Math.addExact(suspendedMass, massUnits[c]);
                boolean thin = volumes[c] <= 4e-5;
                if (thin) { thinSuspendedCells++; thinSuspendedVolume += volumes[c]; }
                int face = (z * (sy + 1) + y) * sx + x;
                double lowerMass = .5 * (masses[c] + masses[c - sx]);
                double upperMass = .5 * (masses[c] + (y + 1 < sy ? masses[c + sx] : 0));
                double lowerVelocity = lowerMass > 0 ? yMomentum[face] / lowerMass : 0;
                double upperVelocity = y + 1 < sy && upperMass > 0 ? yMomentum[face + sx] / upperMass : 0;
                double change = previous == null ? 0 : (volumes[c] - previous[c]) * h3;
                System.out.printf("stage=liquid-cell scenario=%s step=%d cell=%d,%d,%d volume_m3=%.12g mass_units=%d delta_m3=%.12g lower_v_m_s=%.9g upper_v_m_s=%.9g detached=%s thin=%s%n",
                        scenario, step, x, y, z, volumes[c] * h3, massUnits[c], change, lowerVelocity, upperVelocity, !supported, thin);
            }
        }
        System.out.printf("stage=liquid-components scenario=%s step=%d components=%d detached_components=%d detached_cells=%d detached_volume_m3=%.12g detached_mass_units=%d suspended_cells=%d suspended_volume_m3=%.12g suspended_mass_units=%d thin_suspended_cells=%d thin_suspended_volume_m3=%.12g%n",
                scenario, step, components, detachedComponents, detachedCells, detachedVolume * h3, detachedMass,
                suspendedCells, suspendedVolume * h3, suspendedMass, thinSuspendedCells, thinSuspendedVolume * h3);
        previous = volumes;
    }
}
