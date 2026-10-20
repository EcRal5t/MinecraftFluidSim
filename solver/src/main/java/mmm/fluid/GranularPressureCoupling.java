package mmm.fluid;

import java.util.Arrays;
import mmm.granular.GranularParcelStore;

/** Solid volume incidence and its transpose in the common finite-inertia pressure projection. */
final class GranularPressureCoupling {
    private final FluidDomain domain;
    private int contacts, entries;
    private int[] start = new int[0], row = new int[64];
    private double[] coefficient = new double[64], inverseMass = new double[0], affine = new double[0];

    GranularPressureCoupling(FluidDomain domain) { this.domain = domain; }

    void prepare(float dt) {
        contacts = entries = 0;
        if (domain.granularState == null) return;
        int parcels = domain.granularState.parcels.count();
        contacts = 3 * parcels;
        if (start.length <= contacts) {
            start = new int[contacts + 1];
            inverseMass = new double[contacts];
            affine = new double[contacts];
        }
        var graph = domain.pressureSystem.faces;
        var solid = domain.pressureSystem.solidFaces;
        double h = domain.config.cellSize(), cellVolume = h * h * h;
        for (int p = 0; p < parcels; p++) {
            var parcel = domain.granularState.parcels.parcel(p);
            for (int axis = 0; axis < 3; axis++) {
                int contact = 3 * p + axis;
                start[contact] = entries;
                inverseMass[contact] = cellVolume / parcel.mass();
                affine[contact] = 0;
                for (int slot = 0; slot < solid.parcelFaceCount(p, axis); slot++) {
                    int face = solid.parcelFace(p, axis, slot);
                    double area = solid.solidArea(face) * solid.parcelPressureShare(p, axis, slot);
                    int first = graph.firstCell[face], second = graph.secondCell[face];
                    if (!(area > 0) || first < 0 || second < 0) continue;
                    double weight = area / h;
                    graph.forEachSide(first, second, (r, w, fixed) -> add(contact, r, weight * w, fixed));
                    graph.forEachSide(second, first, (r, w, fixed) -> add(contact, r, -weight * w, fixed));
                }
            }
        }
        start[contacts] = entries;
        for (int p = 0; p < parcels; p++) {
            var parcel = domain.granularState.parcels.parcel(p);
            write(p, parcel, parcel.velocityX() + dt * inverseMass[3 * p] * affine[3 * p],
                    parcel.velocityY() + dt * inverseMass[3 * p + 1] * affine[3 * p + 1],
                    parcel.velocityZ() + dt * inverseMass[3 * p + 2] * affine[3 * p + 2]);
        }
    }

    private void add(int contact, int pressureRow, double weight, double fixed) {
        affine[contact] += weight * fixed;
        if (pressureRow < 0 || weight == 0) return;
        for (int e = start[contact]; e < entries; e++) if (row[e] == pressureRow) {
            coefficient[e] += weight;
            return;
        }
        if (entries == row.length) {
            row = Arrays.copyOf(row, 2 * entries);
            coefficient = Arrays.copyOf(coefficient, row.length);
        }
        row[entries] = pressureRow;
        coefficient[entries++] = weight;
    }

    /** B_s v has units of normalized volume per second. */
    void addDivergence(double[] result, double factor) {
        if (contacts == 0) return;
        for (int p = 0; p < contacts / 3; p++) {
            var parcel = domain.granularState.parcels.parcel(p);
            for (int axis = 0; axis < 3; axis++) {
                int c = 3 * p + axis;
                double speed = axis == 0 ? parcel.velocityX() : axis == 1 ? parcel.velocityY() : parcel.velocityZ();
                for (int e = start[c]; e < start[c + 1]; e++) result[row[e]] += factor * coefficient[e] * speed;
            }
        }
    }

    /** Adds rho B_s (h^3 M_s^-1) B_s^T with primitive gather/scatter. */
    void applyMatrix(double[] pressure, double[] result, double factor) {
        for (int c = 0; c < contacts; c++) {
            double force = 0;
            for (int e = start[c]; e < start[c + 1]; e++) force += coefficient[e] * pressure[row[e]];
            double response = factor * inverseMass[c] * force;
            for (int e = start[c]; e < start[c + 1]; e++) result[row[e]] += coefficient[e] * response;
        }
    }

    void addDiagonal() {
        double h = domain.config.cellSize(), density = domain.config.density();
        var p = domain.projection;
        for (int c = 0; c < contacts; c++) for (int e = start[c]; e < start[c + 1]; e++) {
            double value = density * inverseMass[c] * coefficient[e] * coefficient[e];
            if (row[e] < domain.cellCount) p.matrixDiagonal[row[e]] += value * h * h;
            else p.gasMatrixDiagonal[row[e] - domain.cellCount] += value;
        }
    }

    void project(float dt) { project(dt,null); }

    void project(float dt,double[] pressures) {
        if (contacts == 0) return;
        for (int p = 0; p < contacts / 3; p++) {
            var parcel = domain.granularState.parcels.parcel(p);
            double[] change = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                int c = 3 * p + axis;
                double force = 0;
                for (int e = start[c]; e < start[c + 1]; e++) {
                    int r = row[e];
                    double pressure=pressures!=null?pressures[r]:r<domain.cellCount
                            ?domain.projection.pressure[r]:domain.projection.gasPressure[r-domain.cellCount];
                    force += coefficient[e] * pressure;
                }
                change[axis] = dt * inverseMass[c] * force;
            }
            write(p, parcel, parcel.velocityX() + change[0], parcel.velocityY() + change[1], parcel.velocityZ() + change[2]);
        }
    }

    private void write(int index, GranularParcelStore.Parcel parcel, double vx, double vy, double vz) {
        domain.granularState.parcels.overwrite(index, new GranularParcelStore.Parcel(parcel.x(), parcel.y(), parcel.z(),
                (float) vx, (float) vy, (float) vz, parcel.solidVolumeSvq(), parcel.massUnits(), parcel.materialHandle(),
                parcel.solidCompositionHandle(), parcel.contactAge(), parcel.flags(), parcel.ceilMask()));
        domain.lastGranularBoundaryImpulseX += parcel.mass() * (vx - (float) vx);
        domain.lastGranularBoundaryImpulseY += parcel.mass() * (vy - (float) vy);
        domain.lastGranularBoundaryImpulseZ += parcel.mass() * (vz - (float) vz);
    }
}
