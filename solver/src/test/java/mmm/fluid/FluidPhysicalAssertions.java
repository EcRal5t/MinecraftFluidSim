package mmm.fluid;

import mmm.units.Mass;

/** Macroscopic physical acceptance shared by liquid, gas-contact and porous-bed scenarios. */
public final class FluidPhysicalAssertions {
	private FluidPhysicalAssertions() { }
	public static final double HEAD_CELL_FRACTION=.05;
	public static final double TRANSIENT_ENERGY_FRACTION=.01;
	public static final double SETTLED_ENERGY_FRACTION=.001;

	public record Motion(double massKilograms,double kineticJoules) {
		public double rmsSpeed(){return massKilograms>0?Math.sqrt(2*kineticJoules/massKilograms):0;}
	}
	public static Motion motion(FluidDomain domain){
		return new Motion(Mass.kilograms(domain.totalMassUnits()),domain.liquidKineticEnergyJoules());
	}
	/** Pressure error corresponds to at most five percent of one cell's water head. */
	public static void hydrostaticPressure(FluidDomain domain,double actual,double expected,String context){
		double allowance=HEAD_CELL_FRACTION*domain.config.density()*Math.abs(domain.config.gravity())*domain.config.cellSize();
		require(Double.isFinite(actual)&&Math.abs(actual-expected)<=allowance,
				context+": pressure="+actual+", expected="+expected+", allowancePa="+allowance);
	}
	/** Resolved hydrostatic pressure differences stay within two percent of their expected gradient. */
	public static void hydrostaticGradient(double actual,double expected,String context){
		require(Double.isFinite(actual)&&Math.abs(actual-expected)<=.02*Math.abs(expected),
				context+": gradient="+actual+", expected="+expected);
	}
	/** Gravity provides Mgh; a zero-gravity interface uses its capillary pressure-volume scale. */
	public static Motion staticMotion(FluidDomain domain,double capillaryPressure,double energyFraction,String context){
		var motion=motion(domain);double gravity=Math.abs(domain.config.gravity());
		double scale=gravity>0?motion.massKilograms()*gravity*domain.config.cellSize()
				:Math.abs(capillaryPressure)*domain.unitScale.cubicMeters(domain.totalVolumeExact());
		require(Double.isFinite(motion.kineticJoules())&&motion.kineticJoules()<=energyFraction*scale,
				context+": RMS="+motion.rmsSpeed()+" m/s, kinetic="+motion.kineticJoules()+" J, budget="+energyFraction*scale+" J");
		return motion;
	}
	/** Cell-volume centroid monitors accumulated macroscopic movement of a resting inventory. */
	public static double inventoryCenterY(FluidDomain domain){
		double volume=0,weighted=0;
		for(var stock:domain.exportLiquidCellState().stocks()){
			double amount=stock.volumeFraction();int y=stock.key().cell()/domain.sizeX%domain.sizeY;
			volume+=amount;weighted+=(y+.5)*amount;
		}
		return domain.config.cellSize()*weighted/volume;
	}
	public static void hydrostaticRest(FluidDomain domain,double initialCenter,String context){
		staticMotion(domain,0,SETTLED_ENERGY_FRACTION,context);
		double drift=Math.abs(inventoryCenterY(domain)-initialCenter);
		require(drift<=HEAD_CELL_FRACTION*domain.config.cellSize(),context+": accumulated vertical drift="+drift+" m");
	}
	private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
