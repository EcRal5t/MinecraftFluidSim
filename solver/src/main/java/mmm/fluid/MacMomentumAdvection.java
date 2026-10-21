package mmm.fluid;

import java.util.Arrays;

/** Positive donor transport on MAC dual cells, using actual integer mass transfers. */
final class MacMomentumAdvection {
    private final int nx, ny, nz, count, component, sx, sy, sz;
    private final int[] firstCell, secondCell, mergeTarget;
    private final long[] remaining, dualMass;
    private final double[] velocity, momentum, heat, mergedMass, mergedMomentum;
    private int[] flowFrom = new int[0], flowTo = new int[0];
    private long[] flowAmount = new long[0];
    private int flows;

    MacMomentumAdvection(int nx, int ny, int nz, int component) {
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.component = component;
        sx = nx + (component == 0 ? 1 : 0);
        sy = ny + (component == 1 ? 1 : 0);
        sz = nz + (component == 2 ? 1 : 0);
        count = sx * sy * sz;
        firstCell = new int[count];
        secondCell = new int[count];
        remaining = new long[count];
        dualMass = new long[count];
        velocity = new double[count];
        momentum = new double[count];
        heat = new double[count];
        mergeTarget = new int[count];
        mergedMass = new double[count];
        mergedMomentum = new double[count];
        for (int z = 0; z < sz; z++) for (int y = 0; y < sy; y++) for (int x = 0; x < sx; x++) {
            int face = (z * sy + y) * sx + x;
            firstCell[face] = cell(x, y, z);
            secondCell[face] = cell(x - (component == 0 ? 1 : 0),
                    y - (component == 1 ? 1 : 0), z - (component == 2 ? 1 : 0));
        }
    }

    /** Integer columns are doubled dual masses; a dual interface carries half two primal fluxes. */
    double[] transport(long[] before, long[] after, long[][] primalFlux, float[] inputVelocity) {
        flows = 0;
        for (int face = 0; face < count; face++) {
            remaining[face] = value(before, firstCell[face]) + value(before, secondCell[face]);
            dualMass[face] = value(after, firstCell[face]) + value(after, secondCell[face]);
        }
        // The regular mesh defines the dual interfaces directly. Only nonzero
        // actual mass transfers need a retained work item for the momentum and heat passes.
        for (int z = 0; z < sz; z++) for (int y = 0; y < sy; y++) for (int x = 0; x < sx; x++) {
            int face = (z * sy + y) * sx + x;
            for (int axis = 0; axis < 3; axis++) {
                int dx = axis == 0 ? 1 : 0, dy = axis == 1 ? 1 : 0, dz = axis == 2 ? 1 : 0;
                if (x + dx >= sx || y + dy >= sy || z + dz >= sz) continue;
                int first = primal(axis, x + dx, y + dy, z + dz);
                int second = axis == component ? primal(axis, x, y, z)
                        : primal(axis, x + dx - (component == 0 ? 1 : 0),
                        y + dy - (component == 1 ? 1 : 0), z + dz - (component == 2 ? 1 : 0));
                long amount = value(primalFlux[axis], first) + value(primalFlux[axis], second);
                if (amount == 0) continue;
                int neighbor = ((z + dz) * sy + y + dy) * sx + x + dx;
                appendFlow(amount > 0 ? face : neighbor, amount > 0 ? neighbor : face, Math.abs(amount));
            }
        }
        for (int face = 0; face < count; face++) momentum[face] = remaining[face] * (double) inputVelocity[face];
        for (int flow = 0; flow < flows; flow++) {
            momentum[flowTo[flow]] += flowAmount[flow] * (double) inputVelocity[flowFrom[flow]];
        }
        double kgPerDoubledUnit = .5 / mmm.units.Mass.UNITS_PER_KILOGRAM;
        for (int face = 0; face < count; face++) {
            velocity[face] = dualMass[face] > 0 ? momentum[face] / dualMass[face] : 0;
            double difference = inputVelocity[face] - velocity[face];
            heat[face] = .5 * kgPerDoubledUnit * remaining[face] * difference * difference;
            momentum[face] *= kgPerDoubledUnit;
        }
        for (int flow = 0; flow < flows; flow++) {
            int target = flowTo[flow];
            double difference = inputVelocity[flowFrom[flow]] - velocity[target];
            heat[target] += .5 * kgPerDoubledUnit * flowAmount[flow] * difference * difference;
        }
        return momentum;
    }

    private void appendFlow(int from, int to, long amount) {
        if (flows == flowAmount.length) {
            int capacity = Math.max(256, 2 * flows);
            flowFrom = Arrays.copyOf(flowFrom, capacity);
            flowTo = Arrays.copyOf(flowTo, capacity);
            flowAmount = Arrays.copyOf(flowAmount, capacity);
        }
        flowFrom[flows] = from;
        flowTo[flows] = to;
        flowAmount[flows++] = amount;
        remaining[from] -= amount;
    }

    /** Same-cell parallel faces share a finite control volume; split supports retain separate motion. */
    void prepareMergeAnchors(long[] after, int[] anchors, GranularFaceVolumes solid,
                             FlipCutCellGeometry cut, boolean[] constrained) {
        Arrays.fill(anchors, -1);
        for (int face = 0; face < count; face++) {
            mergeTarget[face] = face;
            mergedMass[face] = 0;
            mergedMomentum[face] = 0;
            int a = firstCell[face], b = secondCell[face];
            if (a < 0 || b < 0 || dualMass[face] <= 0 || !(solid.fixedPoreOpen(component, face) > 0)
                    || constrained != null && constrained[face]) continue;
            for (int side = 0; side < 2; side++) {
                int cell = side == 0 ? a : b;
                if (after[cell] <= 0 || CellRemapMicroTopology.components(cut.mask(cell)).length != 1) continue;
                int previous = anchors[cell];
                if (previous < 0 || dualMass[face] > dualMass[previous]) anchors[cell] = face;
            }
        }
    }

    /** Dry-face inertia shares its actual finite mass and impulse with a same-cell parallel face. */
    void mixUnresolved(double[] volume, int[] anchors, float[] open,
                       FlipPressurePhaseGeometry phase, FlipCutCellGeometry cut, boolean[] constrained) {
        double scale = .5 / mmm.units.Mass.UNITS_PER_KILOGRAM;
        for (int face = 0; face < count; face++) {
            int a = firstCell[face], b = secondCell[face];
            long mass = dualMass[face];
            if (a < 0 || b < 0 || mass <= 0 || !(open[face] > 0) || constrained != null && constrained[face]) continue;
            boolean tiny = volume[a] <= mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION
                    && volume[b] <= mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION;
            if (!tiny && (phase.liquidFractionExact(a) >= 1 && phase.liquidFractionExact(b) >= 1
                    || phase.sharedLiquidFaceArea(a, b, component, cut) > mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION)) continue;
            int target = face;
            long largest = mass;
            for (int side = 0; side < 2; side++) {
                int candidate = anchors[side == 0 ? a : b];
                if (candidate < 0 || constrained != null && constrained[candidate]) continue;
                long candidateMass = dualMass[candidate];
                if (candidateMass > largest || candidateMass == largest && candidate < target) {
                    target = candidate;
                    largest = candidateMass;
                }
            }
            if (target != face) mergeTarget[root(face)] = root(target);
        }
        for (int face = 0; face < count; face++) {
            int group = root(face);
            mergedMass[group] += scale * dualMass[face];
            mergedMomentum[group] += momentum[face];
        }
        for (int face = 0; face < count; face++) {
            int group = root(face);
            double mass = scale * dualMass[face];
            if (!(mass > 0) || mergedMass[group] == mass) continue;
            double mixed = mergedMomentum[group] / mergedMass[group];
            double difference = velocity[face] - mixed;
            heat[face] += .5 * mass * difference * difference;
            velocity[face] = mixed;
            momentum[face] = mass * mixed;
        }
    }

    private int root(int face) {
        while (mergeTarget[face] != face) {
            mergeTarget[face] = mergeTarget[mergeTarget[face]];
            face = mergeTarget[face];
        }
        return face;
    }

    void addHeatToCells(long[] after, double[] cells) {
        for (int face = 0; face < count; face++) {
            if (!(heat[face] > 0)) continue;
            int a = firstCell[face], b = secondCell[face];
            long ma = value(after, a);
            double first = dualMass[face] > 0 ? heat[face] * (ma / (double) dualMass[face]) : 0;
            if (a >= 0) cells[a] += first;
            if (b >= 0) cells[b] += heat[face] - first;
        }
    }

    private int cell(int x, int y, int z) {
        return x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz ? -1 : (z * ny + y) * nx + x;
    }

    private int primal(int axis, int x, int y, int z) {
        int sx = nx + (axis == 0 ? 1 : 0), sy = ny + (axis == 1 ? 1 : 0), sz = nz + (axis == 2 ? 1 : 0);
        return x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz ? -1 : (z * sy + y) * sx + x;
    }

    private static long value(long[] values, int index) { return index < 0 ? 0 : values[index]; }
}
