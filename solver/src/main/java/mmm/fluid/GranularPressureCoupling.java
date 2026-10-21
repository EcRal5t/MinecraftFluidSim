package mmm.fluid;

import java.util.Arrays;
import mmm.granular.GranularParcelStore;

/** Solid volume incidence and its transpose in the common finite-inertia pressure projection. */
final class GranularPressureCoupling {
    private final FluidDomain domain;
    private int contacts, entries;
    private double[] initialMechanicalEnergy=new double[0];
    private boolean mechanicalStepPrepared;
    private int[] start = new int[0], row = new int[64];
    private double[] coefficient = new double[64], inverseMass = new double[0], affine = new double[0],referenceAffine=new double[0];

    GranularPressureCoupling(FluidDomain domain) { this.domain = domain; }

    /** Capture true float velocity and position before the gravity prediction. */
    void beginMechanicalStep(){
        int count=domain.granularState==null?0:domain.granularState.parcels.count();
        domain.mechanicalEnergy.beginGranularForceStep(count);
        if(initialMechanicalEnergy.length<count)initialMechanicalEnergy=new double[count];
        for(int p=0;p<count;p++){
            var parcel=domain.granularState.parcels.parcel(p);
            initialMechanicalEnergy[p]=mechanicalEnergy(parcel,parcel.y());
        }
        mechanicalStepPrepared=true;
    }
    private double mechanicalEnergy(GranularParcelStore.Parcel parcel,double y){
        double vx=parcel.velocityX(),vy=parcel.velocityY(),vz=parcel.velocityZ();
        return parcel.mass()*(.5*(vx*vx+vy*vy+vz*vz)+domain.config.gravity()*y);
    }
    /** Collision velocities and the accepted position close the same local total-energy step. */
    void finishMechanicalStep(){
        if(!mechanicalStepPrepared)return;
        int count=domain.granularState==null?0:domain.granularState.parcels.count();
        for(int p=0;p<count;p++){
            var parcel=domain.granularState.parcels.parcel(p);
            double finalY=domain.granularMechanicalStage.plannedPositionY(p);
            double exchange=initialMechanicalEnergy[p]-mechanicalEnergy(parcel,finalY);
            domain.mechanicalEnergy.recordGranularExchange(p,exchange);
        }
        mechanicalStepPrepared=false;
    }

    void prepare(float dt,double pressureDatum) {
        contacts = entries = 0;
        if (domain.granularState == null) return;
        int parcels = domain.granularState.parcels.count();
        contacts = 3 * parcels;
        if (start.length <= contacts) {
            start = new int[contacts + 1];
            inverseMass = new double[contacts];
            affine = new double[contacts];
            referenceAffine = new double[contacts];
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
                referenceAffine[contact] = 0;
                for (int slot = 0; slot < solid.parcelFaceCount(p, axis); slot++) {
                    int face = solid.parcelFace(p, axis, slot);
                    double area = solid.solidArea(face) * solid.parcelPressureShare(p, axis, slot);
                    int first = graph.firstCell[face], second = graph.secondCell[face];
                    if (!(area > 0) || first < 0 || second < 0) continue;
                    double weight = area / h;
                    graph.forEachSide(first, second, (r, w, fixed) -> add(contact, r, weight * w, fixed),
                            reference->referenceAffine[contact]+=weight*reference);
                    graph.forEachSide(second, first, (r, w, fixed) -> add(contact, r, -weight * w, fixed),
                            reference->referenceAffine[contact]-=weight*reference);
                }
            }
        }
        start[contacts] = entries;
        if(pressureDatum!=0)for(int c=0;c<contacts;c++){
            double sum=0;for(int e=start[c];e<start[c+1];e++)sum+=coefficient[e];
            affine[c]+=pressureDatum*sum;
        }
        for (int p = 0; p < parcels; p++) {
            var parcel = domain.granularState.parcels.parcel(p);
            write(p, parcel, parcel.velocityX() + dt * inverseMass[3 * p] * affine[3 * p],
                    parcel.velocityY() + dt * inverseMass[3 * p + 1] * affine[3 * p + 1],
                    parcel.velocityZ() + dt * inverseMass[3 * p + 2] * affine[3 * p + 2]);
            // The hydrostatic column's fixed support supplies this reference
            // impulse. Residual pressure and capillary coupling keep their paired rows.
            domain.lastGranularBoundaryImpulseX+=dt*cellVolume*referenceAffine[3*p];
            domain.lastGranularBoundaryImpulseY+=dt*cellVolume*referenceAffine[3*p+1];
            domain.lastGranularBoundaryImpulseZ+=dt*cellVolume*referenceAffine[3*p+2];
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

    /** Includes gas pressure rows coupled through an actual solid volume response. */
    void markGasPressureContacts(boolean[] coupled) {
        for(int i=0;i<entries;i++)if(row[i]>=domain.cellCount&&coefficient[i]!=0)
            coupled[row[i]-domain.cellCount]=true;
    }
    /** Assemble rho B_s (h^3 M_s^-1) B_s^T once into the common pressure matrix. */
    void assembleMatrix(PressureSparseMatrix matrix, double factor) {
        for (int c = 0; c < contacts; c++) {
            double response = factor * inverseMass[c];
            for (int i = start[c]; i < start[c + 1]; i++) {
                if (!matrix.active(row[i])) continue;
                double weight = response * coefficient[i];
                for (int j = start[c]; j < start[c + 1]; j++) {
                    matrix.add(row[i], row[j], weight * coefficient[j]);
                }
            }
        }
    }

    /** The known prediction and residual projection share this pressure transpose. */
    void project(float dt,double[] pressures) {
        if (contacts == 0) return;
        for (int p = 0; p < contacts / 3; p++) {
            var parcel = domain.granularState.parcels.parcel(p);
            double vx=parcel.velocityX(),vy=parcel.velocityY(),vz=parcel.velocityZ();
            for (int axis = 0; axis < 3; axis++) {
                int c = 3 * p + axis;
                double force = 0;
                for (int e = start[c]; e < start[c + 1]; e++) {
                    int r = row[e];
                    force += coefficient[e] * pressures[r];
                }
                double change=dt*inverseMass[c]*force;
                if(axis==0)vx+=change;else if(axis==1)vy+=change;else vz+=change;
            }
            write(p,parcel,vx,vy,vz);

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
