package mmm.fluid;

import java.util.Arrays;
import java.util.List;
import mmm.units.Mass;

/** Actual SI dual-face inertia, one-time initial velocity input and persistent MAC momentum. */
public final class MarkerVelocityTransferValidation {
	private MarkerVelocityTransferValidation() { }
	public static void main(String[] args) { validate(); System.out.println("Cell/MAC inertia validation passed"); }
	public static void validate() {
		initialInputKeepsActualMassAndMomentum();
		adjacentCellsSupplyActualFaceMass();
		closedFacesStopNormalMomentum();
	}
	private static FluidDomain domain() {
		return new FluidDomain(4,4,4,new FluidSolverConfig(.5F,1000,0,.001F,400,1e-5F,.002F,.8F),2048);
	}
	private static void initialInputKeepsActualMassAndMomentum() {
		for (double scale : new double[]{125, 1.25e8}) {
			var d=domain(); long[] masses={Mass.fromKilograms(scale),Mass.fromKilograms(3*scale)};
			float[] velocity={1,-2,3, 5,2,-1};
			d.restoreMaterialParticleState(new FlipMaterialParticleState(4,4,4,.5F,2,.25,
					Math.addExact(masses[0],masses[1]),List.of(new FluidMaterial("test:liquid",1000,"test:liquid",0,Math.PI/2)),
					new float[]{.625F,.625F,.625F,.125F, .625F,.625F,.625F,.125F},velocity,new byte[64],masses,new int[2]));
			d.domainFlowFields.ensureGrid();
			near(d.totalMassUnits(),Math.addExact(masses[0],masses[1]),0,"Integer input mass");
			double totalMass=Mass.kilograms(masses[0])+Mass.kilograms(masses[1]);
			double kineticInput=0;
			for(int axis=0;axis<3;axis++) {
				double p=Mass.kilograms(masses[0])*velocity[axis]+Mass.kilograms(masses[1])*velocity[3+axis];
				double[] inertia=axis==0?d.cellMacStage.massX:axis==1?d.cellMacStage.massY:d.cellMacStage.massZ;
				double[] momentum=axis==0?d.mac.momentumX:axis==1?d.mac.momentumY:d.mac.momentumZ;
				float[] speed=axis==0?d.mac.velocityX:axis==1?d.mac.velocityY:d.mac.velocityZ;
				near(Arrays.stream(inertia).sum(),totalMass,8*Math.ulp(totalMass),"SI dual-face mass");
				near(Arrays.stream(momentum).sum(),p,8*Math.ulp(Math.max(1,Math.abs(p))),"Input momentum");
				for(int face=0;face<inertia.length;face++) if(inertia[face]>0)
					near(speed[face],p/totalMass,4*Math.ulp((float)(p/totalMass)),"Mass-weighted initial velocity");
				kineticInput+=.5*Mass.kilograms(masses[0])*velocity[axis]*velocity[axis]
						+.5*Mass.kilograms(masses[1])*velocity[3+axis]*velocity[3+axis];
			}
			if(d.liquidKineticEnergyJoules()>kineticInput+16*Math.ulp(kineticInput))throw new AssertionError("Initial averaging amplified kinetic energy");
			var state=d.captureAuthoritativeState();var restored=FluidDomain.restoreAuthoritativeState(state);
			if(!state.liquid().stocks().equals(restored.exportLiquidCellState().stocks())
					||!Arrays.equals(state.momentum().copyX(),restored.exportMacMomentum().copyX())
					||!Arrays.equals(state.momentum().copyY(),restored.exportMacMomentum().copyY())
					||!Arrays.equals(state.momentum().copyZ(),restored.exportMacMomentum().copyZ()))
				throw new AssertionError("Typed handoff changed actual inventory or momentum");
		}
	}
	private static void adjacentCellsSupplyActualFaceMass() {
		var d=domain();d.bindMaterialRegistry(new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER)));
		long first=Mass.fromKilograms(25),second=Mass.fromKilograms(100);
		if(!d.domainInventory.tryInsertExactMaterial(1,1,1,.2F,first,0,0).transferred()
				||!d.domainInventory.tryInsertExactMaterial(2,1,1,.2F,second,0,0).transferred())throw new AssertionError("Variable-density input was refused");
		d.domainFlowFields.ensureGrid();
		near(d.cellMacStage.massX[d.domainFlowFields.xFaceIndex(2,1,1)],.5*(Mass.kilograms(first)+Mass.kilograms(second)),0,"Shared face actual inertia");
		near(d.cellMacStage.massX[d.domainFlowFields.xFaceIndex(1,1,1)],.5*Mass.kilograms(first),0,"Liquid/gas face actual inertia");
	}
	private static void closedFacesStopNormalMomentum() {
		var d=domain();d.setSolid(1,1,1,true);d.setVolume(2,1,1,.2F);d.domainFlowFields.ensureGrid();
		int face=d.domainFlowFields.xFaceIndex(2,1,1);d.mac.velocityX[face]=-7;
		d.bodyForceStage.enforceSolidFaceVelocities();d.cellMacStage.publishMomentum();
		near(d.mac.velocityX[face],0,0,"Closed face normal velocity");
		near(d.mac.momentumX[face],0,0,"Closed face normal momentum");
	}
	private static void near(double actual,double expected,double tolerance,String label) {
		if(!(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance))throw new AssertionError(label+": "+actual+" != "+expected);
	}
}
