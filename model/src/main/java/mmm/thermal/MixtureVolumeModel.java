package mmm.thermal;

/** Local finite-compressibility closure for liquid reference inventory and a gas mixture.
 * Volumes are physical m³, liquid mass is kg, pressure is absolute Pa and energy is J. */
public final class MixtureVolumeModel {
    public static final double LIQUID_BULK_PA = 20_000_000.0;
    public static final double REFERENCE_PRESSURE_PA = 101_325.0;
    public static final double PRESSURE_LIMIT_PA = LIQUID_BULK_PA + REFERENCE_PRESSURE_PA;
    private MixtureVolumeModel() { }

    public record State(double absolutePressurePa, double liquidVolumeM3, double gasVolumeM3,
                        double liquidScale, double liquidDensityKgPerM3,
                        double pressureStorage, double liquidElasticEnergyJ) { }

    /** Gas constant is the inventory's total R in J/K, not the universal molar constant.
     * pressureStorage is normalized for J M^-1 J^T, in the same units as the common GPU operator. */
    public static State evaluate(double capacityM3,double referenceLiquidM3,double liquidMassKg,
            double gasConstantJPerK,double gasTemperatureK,double gasGamma,double durationSeconds,double cellSizeM) {
        if (!(Double.isFinite(capacityM3)&&capacityM3>=0 && Double.isFinite(referenceLiquidM3)&&referenceLiquidM3>=0
                && Double.isFinite(liquidMassKg)&&liquidMassKg>=0 && Double.isFinite(gasConstantJPerK)&&gasConstantJPerK>=0
                && Double.isFinite(gasTemperatureK)&&gasTemperatureK>0 && Double.isFinite(gasGamma)&&gasGamma>=1
                && Double.isFinite(durationSeconds)&&durationSeconds>0 && Double.isFinite(cellSizeM)&&cellSizeM>0))
            throw new IllegalArgumentException("Invalid local mixture state");
        if (capacityM3 == 0) {
            if (referenceLiquidM3 != 0 || liquidMassKg != 0 || gasConstantJPerK != 0)
                throw new IllegalArgumentException("Occupied material has no available volume");
            return new State(0,0,0,1,0,0,0);
        }
        if (referenceLiquidM3 == 0 && liquidMassKg != 0)
            throw new IllegalArgumentException("Liquid mass has no reference volume");
        double r=gasConstantJPerK*gasTemperatureK,pressure,scale;
        if (!Double.isFinite(r)) throw new IllegalArgumentException("Gas pressure product exceeds representation");
        double b=capacityM3*LIQUID_BULK_PA+r-PRESSURE_LIMIT_PA*referenceLiquidM3;
        if (r == 0) {
            pressure=referenceLiquidM3==0||b>=0 ? 0 : -b/referenceLiquidM3;
        } else if (referenceLiquidM3 == 0) {
            pressure=PRESSURE_LIMIT_PA*r/(capacityM3*LIQUID_BULK_PA+r);
        } else {
            double norm=Math.hypot(b,2*Math.sqrt(referenceLiquidM3)*Math.sqrt(PRESSURE_LIMIT_PA*r));
            pressure=b>=0?2*PRESSURE_LIMIT_PA*r/(b+norm):(norm-b)/(2*referenceLiquidM3);
        }
        if (referenceLiquidM3 == 0) scale=1;
        else if (r == 0) scale=pressure>0?capacityM3/referenceLiquidM3:PRESSURE_LIMIT_PA/LIQUID_BULK_PA;
        else {
            double k=capacityM3*LIQUID_BULK_PA+PRESSURE_LIMIT_PA*referenceLiquidM3+r;
            double norm=Math.hypot(b,2*Math.sqrt(referenceLiquidM3)*Math.sqrt(PRESSURE_LIMIT_PA*r));
            scale=2*capacityM3*PRESSURE_LIMIT_PA/(k+norm);
        }
        double liquid=referenceLiquidM3*scale;
        double gasScale=referenceLiquidM3>0?scale:capacityM3*PRESSURE_LIMIT_PA/(capacityM3*LIQUID_BULK_PA+r);
        double gas=r>0?r*gasScale/pressure:Math.max(0,capacityM3-liquid);
        double density=liquid>0?liquidMassKg/liquid:0;
        double gasCompliance=r>0?r*PRESSURE_LIMIT_PA/(LIQUID_BULK_PA*pressure*pressure*(1+(gasGamma-1)*gasScale)):0;
        double compliance=referenceLiquidM3/LIQUID_BULK_PA+gasCompliance;
        double h3=cellSizeM*cellSizeM*cellSizeM,storage=compliance/(durationSeconds*durationSeconds*h3);
        double displacement=scale-1,elastic=.5*LIQUID_BULK_PA*referenceLiquidM3*displacement*displacement;
        if (!(Double.isFinite(pressure)&&pressure>=0&&Double.isFinite(liquid)&&liquid>=0
                &&Double.isFinite(gas)&&gas>=0&&Double.isFinite(scale)&&scale>0&&Double.isFinite(density)
                &&Double.isFinite(storage)&&storage>=0&&Double.isFinite(elastic)&&elastic>=0))
            throw new IllegalArgumentException("Local mixture state exceeds representation");
        return new State(pressure,liquid,gas,scale,density,storage,elastic);
    }
    /** Joint finite-step gas work and mixture EOS with one frozen caloric derivative. */
    public record GasWorkState(double temperatureK,double absolutePressurePa,double gasVolumeM3) { }

    public static GasWorkState gasWorkState(double capacity,double liquidReference,double gasConstant,
                                             double heatCapacity,double temperature,double advectedVolume) {
        if (!(Double.isFinite(capacity)&&capacity>0&&Double.isFinite(liquidReference)&&liquidReference>=0
                &&Double.isFinite(gasConstant)&&gasConstant>0&&Double.isFinite(heatCapacity)&&heatCapacity>0
                &&Double.isFinite(temperature)&&temperature>0&&Double.isFinite(advectedVolume)&&advectedVolume>0))
            throw new IllegalArgumentException("Invalid joint gas work state");
        double bulk=LIQUID_BULK_PA,limit=PRESSURE_LIMIT_PA;
        double low=liquidReference>0?Math.max(0,limit-bulk*capacity/liquidReference):0,high=limit;
        double pressure=evaluate(capacity,liquidReference,0,gasConstant,temperature,1,1,1).absolutePressurePa();
        if (!(pressure>low&&pressure<high)) pressure=(low+high)*.5;
        double a=heatCapacity*bulk/(limit*gasConstant);
        double b=heatCapacity*gasConstant/(heatCapacity*bulk+limit*gasConstant);
        double referenceVolume=advectedVolume+b*temperature;
        double next=temperature,gas=advectedVolume;
        for(int i=0;i<48;i++){
            double scale=(limit-pressure)/bulk;
            gas=capacity-liquidReference*scale;
            next=pressure*gas/(gasConstant*scale);
            double residual=Math.log((gas+b*next)/referenceVolume)+a*Math.log(next/temperature);
            if(Math.abs(residual)<=1e-7||i==47)break;
            if(residual<0)low=pressure;else high=pressure;
            double dTemperature=next*(1/pressure+liquidReference/(bulk*gas)+1/(bulk*scale));
            double derivative=(liquidReference/bulk+b*dTemperature)/(gas+b*next)+a*dTemperature/next;
            double candidate=pressure-residual/derivative;
            pressure=candidate>low&&candidate<high?candidate:(low+high)*.5;
        }
        if (!(Double.isFinite(next)&&next>0&&Double.isFinite(gas)&&gas>0))
            throw new IllegalArgumentException("Joint gas work exceeds representation");
        return new GasWorkState(next,pressure,gas);
    }

    public static double gasPressure(double gasConstant,double temperature,double volume) {
        double r=gasConstant*temperature;
        return PRESSURE_LIMIT_PA*r/(LIQUID_BULK_PA*volume+r);
    }
}
