package mmm.fluid;

/** Assigns physical fixture velocity through the authoritative parcel store. */
public final class PipeRegionTestAccess {
    private PipeRegionTestAccess() { }
    public static void setFirstParcelVelocity(HostFluidState host,float x,float y,float z) {
        var store=host.granularState.parcels;var p=store.parcel(0);
        store.overwrite(0,new mmm.granular.GranularParcelStore.Parcel(p.x(),p.y(),p.z(),x,y,z,p.solidVolumeSvq(),p.massUnits(),
                p.materialHandle(),p.solidCompositionHandle(),p.contactAge(),p.flags(),p.ceilMask()));
    }
}
