package mmm.fluid;

import java.util.Arrays;
import java.util.BitSet;

/** Positive donor transport on MAC dual cells, using actual integer mass transfers. */
final class MacMomentumAdvection {
    private final int nx, ny, nz;
    private int count, component, sx, sy, sz;
    private final int[] firstCell, secondCell, mergeTarget;
    private final long[] remaining, dualMass;
    private final double[] velocity, momentum, heat, mergedMass, mergedMomentum;
    private final long[] interfaceFlux;
    private final BitSet interfaces = new BitSet();
    private final BitSet occupiedFaces = new BitSet();
    private int[] flowFrom = new int[0], flowTo = new int[0];
    private long[] flowAmount = new long[0];
    private int flows;

    MacMomentumAdvection(int nx, int ny, int nz) {
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        int capacity = Math.max((nx + 1) * ny * nz,
                Math.max(nx * (ny + 1) * nz, nx * ny * (nz + 1)));
        firstCell = new int[capacity];
        secondCell = new int[capacity];
        remaining = new long[capacity];
        dualMass = new long[capacity];
        velocity = new double[capacity];
        momentum = new double[capacity];
        heat = new double[capacity];
        mergeTarget = new int[capacity];
        mergedMass = new double[capacity];
        mergedMomentum = new double[capacity];
        interfaceFlux = new long[capacity];
    }

    /** The three staggered components use one scratch arena and retain only their final momentum. */
    void beginComponent(int component) {
        this.component = component;
        sx = nx + (component == 0 ? 1 : 0);
        sy = ny + (component == 1 ? 1 : 0);
        sz = nz + (component == 2 ? 1 : 0);
        count = sx * sy * sz;
        for (int z = 0; z < sz; z++) for (int y = 0; y < sy; y++) for (int x = 0; x < sx; x++) {
            int face = (z * sy + y) * sx + x;
            firstCell[face] = cell(x, y, z);
            secondCell[face] = cell(x - (component == 0 ? 1 : 0),
                    y - (component == 1 ? 1 : 0), z - (component == 2 ? 1 : 0));
        }
    }

    double[] copyMomentum() { return Arrays.copyOf(momentum, count); }

    /** Integer columns are doubled dual masses; a dual interface carries half two primal fluxes. */
    double[] transport(long[] before, long[] after, long[][] primalFlux, BitSet[] primalFaces, float[] inputVelocity) {
        return transport(before,after,primalFlux,primalFaces,inputVelocity,null,null);
    }

    double[] transport(long[] before, long[] after, long[][] primalFlux, BitSet[] primalFaces, float[] inputVelocity,
                       double[] retainedMomentum, java.util.function.IntPredicate activeFace) {
        flows = 0;
        occupiedFaces.clear();
        for (int face = 0; face < count; face++) {
            if(activeFace!=null&&!activeFace.test(face)) {
                momentum[face]=retainedMomentum[face];
                velocity[face]=heat[face]=0;
                continue;
            }
            remaining[face] = value(before, firstCell[face]) + value(before, secondCell[face]);
            dualMass[face] = value(after, firstCell[face]) + value(after, secondCell[face]);
            momentum[face] = velocity[face] = heat[face] = 0;
            if (remaining[face] > 0 || dualMass[face] > 0) occupiedFaces.set(face);
        }
        // Each accepted primal face contributes to exactly two parallel dual
        // interfaces: F(q + axis) + F(q + axis - component). Merge those integer
        // contributions before choosing a donor, including opposite primal flows.
        for (int axis = 0; axis < 3; axis++) {
            int px = nx + (axis == 0 ? 1 : 0), py = ny + (axis == 1 ? 1 : 0);
            int dx = axis == 0 ? 1 : 0, dy = axis == 1 ? 1 : 0, dz = axis == 2 ? 1 : 0;
            for (int primal = primalFaces[axis].nextSetBit(0); primal >= 0;
                 primal = primalFaces[axis].nextSetBit(primal + 1)) {
                long amount = primalFlux[axis][primal];
                if (amount == 0) continue;
                int x = primal % px - dx, y = primal / px % py - dy, z = primal / (px * py) - dz;
                addInterface(x, y, z, axis, amount);
                addInterface(x + (component == 0 ? 1 : 0), y + (component == 1 ? 1 : 0),
                        z + (component == 2 ? 1 : 0), axis, amount);
            }
            int stride = axis == 0 ? 1 : axis == 1 ? sx : sx * sy;
            for (int face = interfaces.nextSetBit(0); face >= 0; face = interfaces.nextSetBit(face + 1)) {
                long amount = interfaceFlux[face]; interfaceFlux[face] = 0;
                if (amount != 0) appendFlow(amount > 0 ? face : face + stride,
                        amount > 0 ? face + stride : face, Math.abs(amount));
            }
            interfaces.clear();
        }
        for (int face = occupiedFaces.nextSetBit(0); face >= 0; face = occupiedFaces.nextSetBit(face + 1))
            momentum[face] = remaining[face] * (double) inputVelocity[face];
        for (int flow = 0; flow < flows; flow++) {
            momentum[flowTo[flow]] += flowAmount[flow] * (double) inputVelocity[flowFrom[flow]];
        }
        double kgPerDoubledUnit = .5 / mmm.units.Mass.UNITS_PER_KILOGRAM;
        for (int face = occupiedFaces.nextSetBit(0); face >= 0; face = occupiedFaces.nextSetBit(face + 1)) {
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

    private void addInterface(int x, int y, int z, int axis, long amount) {
        if (x < 0 || y < 0 || z < 0 || x >= sx - (axis == 0 ? 1 : 0)
                || y >= sy - (axis == 1 ? 1 : 0) || z >= sz - (axis == 2 ? 1 : 0)) return;
        int face = (z * sy + y) * sx + x;
        interfaceFlux[face] += amount;
        interfaces.set(face);
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
        for (int face = occupiedFaces.nextSetBit(0); face >= 0; face = occupiedFaces.nextSetBit(face + 1)) {
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
        for (int face = occupiedFaces.nextSetBit(0); face >= 0; face = occupiedFaces.nextSetBit(face + 1)) {
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
        for (int face = occupiedFaces.nextSetBit(0); face >= 0; face = occupiedFaces.nextSetBit(face + 1)) {
            int group = root(face);
            mergedMass[group] += scale * dualMass[face];
            mergedMomentum[group] += momentum[face];
        }
        for (int face = occupiedFaces.nextSetBit(0); face >= 0; face = occupiedFaces.nextSetBit(face + 1)) {
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
        for (int face = occupiedFaces.nextSetBit(0); face >= 0; face = occupiedFaces.nextSetBit(face + 1)) {
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

    private static long value(long[] values, int index) { return index < 0 ? 0 : values[index]; }
}
