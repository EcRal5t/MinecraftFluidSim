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
		var domain=new FluidDomain(12,8,6,new FluidSolverConfig(.5F,1000,9.81F,.025F,160,1e-5F,.002F));
		for(int z=0;z<6;z++)for(int x=0;x<12;x++)domain.setSolid(x,0,z,true);
		for(int z=0;z<6;z++)for(int y=1;y<=6;y++)for(int x=0;x<6;x++)domain.setVolume(x,y,z,1);
		var volume=domain.liquidCells.exactVolume();long mass=domain.totalMassUnits();
		double initialPotential=domain.domainStep.gravitationalPotentialEnergyJoules();
		double initialCentre=domain.centerOfMassX();var backend=new CpuFluidSolverBackend();
		for(int step=1;step<=5000;step++){
			domain=backend.evaluate(new FluidStepRequest(step,domain.captureAuthoritativeState(),List.of(),false,0)).claimPreparedCpuDomain();
			if(!domain.liquidCells.exactVolume().equals(volume)||domain.totalMassUnits()!=mass)throw new AssertionError("Dam flow changed exact volume or integer mass");
			domain.domainFlowFields.preparePhysicalState();
			for(var available:domain.domainFlowFields.availableCellVolumes())if(available.signum()<0)throw new AssertionError("Dam flow exceeded actual cell capacity");
			if(!Float.isFinite(domain.maximumParticleSpeed())||!domain.gasBoundary().auditClosed())throw new AssertionError("Dam flow lost finite motion or gas conservation");
			var motion=motion(domain);
			if(!Double.isFinite(motion.energy)||motion.energy>1.25*initialPotential+1)
				throw new AssertionError("Dam kinetic energy exceeds its initial gravitational energy budget: "+motion.energy);
			// After many gravity times the closed tank is a resting flow. Retain the
			// shared macroscopic one-cell energy allowance throughout the long tail.
			if(step>=1000)FluidPhysicalAssertions.staticMotion(domain,0,
					FluidPhysicalAssertions.TRANSIENT_ENERGY_FRACTION,"Dam settled tail step="+step);
			if(step%1000==0){
				domain=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
				System.out.printf("dam step=%d stocks=%d centreX=%g speed=%g K=%g RMS=%g resolvedMax=%g tailK=%g potential=%g%n",step,domain.liquidCells.size(),domain.centerOfMassX(),domain.maximumParticleSpeed(),motion.energy,Math.sqrt(2*motion.energy/mmm.units.Mass.kilograms(mass)),motion.resolvedMaximum,motion.tailEnergy,domain.domainStep.gravitationalPotentialEnergyJoules());
			}
		}
		if(domain.centerOfMassX()<=initialCentre+.5)throw new AssertionError("Water did not spread into the initially empty half");
		System.out.println("Dam 5000-step exact inventory, capacity and recovery validation passed");
	}
	private record Motion(double energy,double resolvedMaximum,double tailEnergy) { }
	private static Motion motion(FluidDomain domain){
		double energy=0,resolved=0,tail=0;
		for(int axis=0;axis<3;axis++){
			float[] velocity=axis==0?domain.mac.velocityX:axis==1?domain.mac.velocityY:domain.mac.velocityZ;
			double[] faceMass=axis==0?domain.cellMacStage.massX:axis==1?domain.cellMacStage.massY:domain.cellMacStage.massZ;
			int nx=domain.sizeX+(axis==0?1:0),ny=domain.sizeY+(axis==1?1:0);
			for(int face=0;face<velocity.length;face++)if(faceMass[face]>0){
				double kinetic=.5*faceMass[face]*velocity[face]*(double)velocity[face];energy+=kinetic;
				int x=face%nx,y=face/nx%ny,z=face/(nx*ny),coordinate=axis==0?x:axis==1?y:z,size=axis==0?domain.sizeX:axis==1?domain.sizeY:domain.sizeZ;
				int a=coordinate>0?domain.domainFlowFields.cellIndex(x-(axis==0?1:0),y-(axis==1?1:0),z-(axis==2?1:0)):-1,b=coordinate<size?domain.domainFlowFields.cellIndex(x,y,z):-1;
				boolean visible=a>=0&&domain.volume[a]>mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION||b>=0&&domain.volume[b]>mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION;
				if(visible)resolved=Math.max(resolved,Math.abs(velocity[face]));else tail+=kinetic;
			}
		}
		return new Motion(energy,resolved,tail);
	}
}
