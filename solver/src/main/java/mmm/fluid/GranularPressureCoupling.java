package mmm.fluid;

import mmm.granular.GranularParcelStore;

/** Complementary face pressure loads on staggered parcels; this step's positions are already prepared. */
final class GranularPressureCoupling {
    private final FluidDomain domain;
    private double wallWork;
    GranularPressureCoupling(FluidDomain domain){this.domain=domain;}
    void prepare(float dt){wallWork=0;}
    void project(float dt){project(dt,null,null);}
    void project(float dt,double[] liquidPressure,double[] gasPressure){
        if(domain.granularState==null)return;
        var graph=domain.pressureSystem.faces;var solid=domain.pressureSystem.solidFaces;
        double[] pressure=new double[domain.cellCount+domain.gasState.network.nodeCount()+1];
        for(int c=0;c<domain.cellCount;c++)pressure[c]=liquidPressure==null?domain.projection.pressure[c]:liquidPressure[c];
        for(int n=1;n<=domain.gasState.network.nodeCount();n++)pressure[domain.cellCount+n]=gasPressure==null?domain.projection.gasPressure[n]:gasPressure[n];
        double[] impulse=new double[graph.faceCount];double h=domain.config.cellSize(),scale=dt*h*h;
        for(int f=0;f<impulse.length;f++)if(solid.solidArea(f)>0)
            impulse[f]=scale*solid.solidArea(f)*graph.pressureDifference(f,pressure);
        for(int p=0;p<domain.granularState.parcels.count();p++){
            var parcel=domain.granularState.parcels.parcel(p);double[] change=new double[3];
            for(int axis=0;axis<3;axis++)for(int slot=0;slot<solid.parcelFaceCount(p,axis);slot++)
                change[axis]+=impulse[solid.parcelFace(p,axis,slot)]*solid.parcelPressureShare(p,axis,slot);
            double vx=parcel.velocityX()+change[0]/parcel.mass(),vy=parcel.velocityY()+change[1]/parcel.mass(),vz=parcel.velocityZ()+change[2]/parcel.mass();
            domain.granularState.parcels.overwrite(p,new GranularParcelStore.Parcel(parcel.x(),parcel.y(),parcel.z(),
                (float)vx,(float)vy,(float)vz,parcel.solidVolumeSvq(),parcel.massUnits(),parcel.materialHandle(),parcel.solidCompositionHandle(),parcel.contactAge(),parcel.flags(),parcel.ceilMask()));
            domain.lastGranularBoundaryImpulseX+=parcel.mass()*(vx-(float)vx);
            domain.lastGranularBoundaryImpulseY+=parcel.mass()*(vy-(float)vy);
            domain.lastGranularBoundaryImpulseZ+=parcel.mass()*(vz-(float)vz);
        }
    }
    double wallWork(){return wallWork;}
}
