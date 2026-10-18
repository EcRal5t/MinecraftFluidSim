package mmm.inventory;

/** Persistent liquid occupation of the fixed eight microboxes, in whole-cell normalized volume. */
public final class PhaseOccupancyState {
    /** Geometry allowance for the sixteen-step coarse PLIC inversion; inventory remains exact. */
    public static final double VOLUME_RESOLUTION = 4e-5;
    private final double[] liquid;
    public PhaseOccupancyState(double[] normalizedLiquidMicroVolumes){
        if(normalizedLiquidMicroVolumes==null||normalizedLiquidMicroVolumes.length==0||normalizedLiquidMicroVolumes.length%8!=0)
            throw new IllegalArgumentException("Phase occupation requires eight micro volumes per cell");
        liquid=normalizedLiquidMicroVolumes.clone();
        for(double volume:liquid)if(!Double.isFinite(volume)||volume<0||volume>.125)
            throw new IllegalArgumentException("Liquid micro volume is outside its geometric box");
    }
    public int cellCount(){return liquid.length/8;}
    public double liquidVolume(int piece){return liquid[piece];}
    public double[] copyLiquidMicroVolumes(){return liquid.clone();}
    /** Immutable backend continuations share this identity without scanning the phase column. */
    public Object geometryIdentity(){return this;}
}
