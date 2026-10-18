package mmm.fluid;

import java.util.List;

/** Macroscopic transport diagnostics over authoritative cell inventories and MAC momentum. */
public final class MarkerDistributionExperiment {
	private MarkerDistributionExperiment() { }
	public static void main(String[] args) {
		for(String scenario:args.length==0?new String[]{"transport","dam"}:args) switch(scenario){
			case "transport" -> LiquidMacroscopicMotionValidation.main(new String[]{"transport-moments"});
			case "rotation" -> LiquidMacroscopicMotionValidation.main(new String[]{"joint-moments"});
			case "dam" -> damDistribution();
			default -> throw new IllegalArgumentException("Expected transport, rotation or dam");
		}
	}
	static void damDistribution() {
		var domain=new FluidDomain(12,8,6,new FluidSolverConfig(.5F,1000,9.81F,.025F,160,1e-5F,.4F,.002F,.8F),12*8*6*8);
		for(int z=0;z<6;z++)for(int x=0;x<12;x++)domain.setSolid(x,0,z,true);
		for(int z=0;z<6;z++)for(int y=1;y<=6;y++)for(int x=0;x<6;x++)domain.setVolume(x,y,z,1);
		var volume=domain.liquidCells.exactVolume();long mass=domain.totalMassUnits();
		double initialCentre=domain.centerOfMassX();var backend=new CpuFluidSolverBackend();
		for(int step=1;step<=5000;step++){
			domain=backend.evaluate(new FluidStepRequest(step,domain.captureAuthoritativeState(),List.of(),false,0)).claimPreparedCpuDomain();
			if(!domain.liquidCells.exactVolume().equals(volume)||domain.totalMassUnits()!=mass)throw new AssertionError("Dam flow changed exact volume or integer mass");
			domain.ensureGrid();
			for(var available:domain.availableCellVolumes())if(available.signum()<0)throw new AssertionError("Dam flow exceeded actual cell capacity");
			if(!Float.isFinite(domain.maximumParticleSpeed())||!domain.gasBoundary().auditClosed())throw new AssertionError("Dam flow lost finite motion or gas conservation");
			if(step%1000==0){
				domain=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
				System.out.printf("dam step=%d stocks=%d centreX=%g speed=%g%n",step,domain.liquidCells.size(),domain.centerOfMassX(),domain.maximumParticleSpeed());
			}
		}
		if(domain.centerOfMassX()<=initialCentre+.5)throw new AssertionError("Water did not spread into the initially empty half");
		System.out.println("Dam 5000-step exact inventory, capacity and recovery validation passed");
	}
}
