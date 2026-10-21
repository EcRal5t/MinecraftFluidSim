package mmm.fluid;

import java.util.Arrays;
import mmm.granular.GranularParcelStore;

/** Solid volume incidence and its transpose in the common finite-inertia pressure projection. */
final class GranularPressureCoupling {
    private final FluidDomain domain;
    private int contacts, entries;
    private double[] initialKineticEnergy=new double[0];
    private float[] initialPositionY=new float[0];
    private boolean mechanicalStepPrepared;
    private int[] start = new int[0], row = new int[64];
    private double[] coefficient = new double[64], inverseMass = new double[0], affine = new double[0],referenceAffine=new double[0];

    GranularPressureCoupling(FluidDomain domain) { this.domain = domain; }

    /** Capture true float velocity and position before the gravity prediction. */
    void beginMechanicalStep(){
        int count=domain.granularState==null?0:domain.granularState.parcels.count();
        domain.mechanicalEnergy.beginGranularForceStep(count);
        if(initialKineticEnergy.length<count){initialKineticEnergy=new double[count];initialPositionY=new float[count];}
        for(int p=0;p<count;p++){
            var parcel=domain.granularState.parcels.parcel(p);
            initialKineticEnergy[p]=kineticEnergy(parcel);initialPositionY[p]=parcel.y();
        }
        mechanicalStepPrepared=true;
    }
    private double kineticEnergy(GranularParcelStore.Parcel parcel){
        double vx=parcel.velocityX(),vy=parcel.velocityY(),vz=parcel.velocityZ();
        return .5*parcel.mass()*(vx*vx+vy*vy+vz*vz);
    }
    /** Collision velocities and the accepted position close the same local total-energy step. */
    void finishMechanicalStep(){
        if(!mechanicalStepPrepared)return;
        int count=domain.granularState==null?0:domain.granularState.parcels.count();
        for(int p=0;p<count;p++){
            var parcel=domain.granularState.parcels.parcel(p);
            double finalY=domain.granularMechanicalStage.plannedPositionY(p);
            double kinetic=initialKineticEnergy[p]-kineticEnergy(parcel);
            double potential=parcel.mass()*domain.config.gravity()*(initialPositionY[p]-finalY);
            domain.mechanicalEnergy.recordGranularExchange(p,kinetic,potential);
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
        for(int p=0;p<parcels;p++){
            // The hydrostatic support's actual reaction is recorded once per prepared step.
            domain.lastGranularBoundaryImpulseX+=dt*cellVolume*referenceAffine[3*p];
            domain.lastGranularBoundaryImpulseY+=dt*cellVolume*referenceAffine[3*p+1];
            domain.lastGranularBoundaryImpulseZ+=dt*cellVolume*referenceAffine[3*p+2];
        }
    }

    void addAffineVelocity(float dt,double[] velocity,int offset){
        for(int c=0;c<contacts;c++)velocity[offset+c]+=dt*inverseMass[c]*affine[c];
    }
    void pressureVelocity(double[] pressures,double[] velocity,int offset){
        for(int c=0;c<contacts;c++){
            double force=0;
            for(int e=start[c];e<start[c+1];e++)force+=coefficient[e]*pressures[row[e]];
            velocity[offset+c]=inverseMass[c]*force;
        }
    }
    void addDivergence(double[] velocity,int offset,double[] result,double factor){
        for(int c=0;c<contacts;c++)for(int e=start[c];e<start[c+1];e++)
            result[row[e]]+=factor*coefficient[e]*velocity[offset+c];
    }
    void publishVelocity(double[] velocity,int offset){
        int count=domain.granularState==null?0:domain.granularState.parcels.count();
        for(int p=0;p<count;p++){
            var parcel=domain.granularState.parcels.parcel(p);int index=offset+3*p;
            write(p,parcel,velocity[index],velocity[index+1],velocity[index+2]);
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

    /** Includes gas pressure rows coupled through an actual solid volume response. */
    void markGasPressureContacts(boolean[] coupled) {
        for(int i=0;i<entries;i++)if(row[i]>=domain.cellCount&&coefficient[i]!=0)
            coupled[row[i]-domain.cellCount]=true;
    }
    /** Assemble the solid no-drag contribution to the common pressure preconditioner. */
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

    private void write(int index, GranularParcelStore.Parcel parcel, double vx, double vy, double vz) {
        domain.granularState.parcels.overwrite(index, new GranularParcelStore.Parcel(parcel.x(), parcel.y(), parcel.z(),
                (float) vx, (float) vy, (float) vz, parcel.solidVolumeSvq(), parcel.massUnits(), parcel.materialHandle(),
                parcel.solidCompositionHandle(), parcel.contactAge(), parcel.flags(), parcel.ceilMask()));
        domain.lastGranularBoundaryImpulseX += parcel.mass() * (vx - (float) vx);
        domain.lastGranularBoundaryImpulseY += parcel.mass() * (vy - (float) vy);
        domain.lastGranularBoundaryImpulseZ += parcel.mass() * (vz - (float) vz);
    }
}
