package mmm.fluid;

import mmm.simulation.FullCapabilityTestFixture;
import mmm.thermal.ThermalTransport;
import mmm.world.MovingBodyFluidCoupling;

/** Actual body work, stationary-wall reaction and local heat share one momentum publication. */
public final class MovingBodyCouplingValidation {
	public static void main(String[] args) throws Exception {
		wetGeometryAndHydrostaticPressure();
		var registries=FullCapabilityTestFixture.load();
		var d=new FluidDomain(5,4,4,new FluidSolverConfig(.5F,1000,0,.025F,120,1e-5F,.002F));
		FullCapabilityTestFixture.bindEmptyMaterialState(d,registries.liquids());
		d.sealDomainBoundary();
		for(int z=0;z<4;z++)for(int y=0;y<4;y++)for(int x=0;x<5;x++) {
			if(x==2)d.setSolid(x,y,z,true);else d.setVolume(x,y,z,.75F);
		}
		FullCapabilityTestFixture.bindFull(d,registries,293.15,ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		var volume=d.liquidCells.exactVolume();long mass=d.totalMassUnits();
		var gas=d.gasBoundary().sealedInventory();double wallImpulse=0;
		for(int step=0;step<32;step++) {
			d.domainFlowFields.ensureVelocityGrid();
			double kinetic=kinetic(d);var beforeU=d.exactInternalEnergy();var beforeRoundoff=d.thermalAudit().roundoffResidual();
			double storageAllowance=energyStorageAllowance(d);
			double[] beforeP=momentum(d),beforeWall=wall(d);
			double[] velocity=step<8?new double[]{.6,-.2,.3}:step<16?new double[3]
					:step<24?new double[]{-.4,.1,-.2}:new double[3];
			var result=d.applyMovingBodyCoupling(MovingBodyFluidCoupling.prescribedCube(.85F,.75F,.75F,
					velocity[0],velocity[1],velocity[2],.1));
			double heat=d.exactInternalEnergy().subtract(beforeU).toDouble();
			storageAllowance+=energyStorageAllowance(d);
			require(Math.abs(d.thermalAudit().roundoffResidual().subtract(beforeRoundoff).toDouble())<=storageAllowance,
					"Body heat was hidden in a roundoff term larger than actual energy storage precision");
			double afterK=kinetic(d),scale=Math.max(1,kinetic+afterK+Math.abs(result.externalWorkJoules()));
			// Velocity publication uses float. Keep a fixed representability allowance per operation.
			double tolerance=16*Math.ulp((float)scale)+storageAllowance;
			require(result.coupledMarkers()>0&&heat>=-tolerance
					&&Math.abs(afterK-kinetic+heat-result.externalWorkJoules())<=tolerance,
					"Body/wall work did not become actual kinetic energy and heat: step="+step
							+", dK="+(afterK-kinetic)+", heat="+heat+", W="+result.externalWorkJoules());
			double[] afterP=momentum(d),afterWall=wall(d);
			double[] impulse={result.impulseXKilogramMetresPerSecond(),result.impulseYKilogramMetresPerSecond(),result.impulseZKilogramMetresPerSecond()};
			for(int axis=0;axis<3;axis++) {
				double reaction=afterWall[axis]-beforeWall[axis];wallImpulse+=Math.abs(reaction);
				double impulseScale=Math.max(1,Math.abs(beforeP[axis])+Math.abs(impulse[axis])+Math.abs(reaction));
				require(Math.abs(afterP[axis]-beforeP[axis]-impulse[axis]-reaction)<=16*Math.ulp((float)impulseScale),
						"Body and fixed-wall impulses did not account for actual MAC momentum");
			}
			require(d.totalMassUnits()==mass&&d.liquidCells.exactVolume().equals(volume)
					&&d.gasBoundary().sealedInventory().equals(gas)&&d.gasBoundary().auditClosed()
					&&d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),
					"Body/wall publication changed inventory or opened its exact thermal ledger");
		}
		require(wallImpulse>0,"Body fixture did not exercise actual fixed-wall momentum retirement");
		var saved=d.captureAuthoritativeState();var restored=FluidDomain.restoreAuthoritativeState(saved);
		require(saved.liquid().stocks().equals(restored.liquidCells.stocks())
				&&java.util.Arrays.equals(saved.momentum().copyX(),restored.exportMacMomentum().copyX())
				&&restored.exactInternalEnergy().equals(d.exactInternalEnergy()),
				"Body/wall energy or momentum changed during recovery");
		System.out.println("Moving-body work, fixed-wall impulse, actual heat and recovery passed");
	}
	private static void wetGeometryAndHydrostaticPressure() {
		var domain = new FluidDomain(4,4,4,new FluidSolverConfig(.5F,1000,9.81F,.025F,120,1e-5F,.002F));
		domain.sealDomainBoundary();
		float[] volumes = new float[64]; java.util.Arrays.fill(volumes, 1);
		domain.setVolumes(volumes);
		var sample = domain.sampleAxisAlignedBody(.6,.1,.6,.9,.4,.9);
		double expectedVolume=.3*.3*.3;
		require(Math.abs(sample.displacedVolumeCubicMetres()-expectedVolume)<1e-12
				&&Math.abs(sample.wetSurfaceAreaSquareMetres()-.54)<1e-12,
				"Body/PLIC intersection lost submerged volume or wet surface area");
		require(Math.abs(sample.pressureForceYNewtons()-1000.0*domain.config().gravity()*expectedVolume)<1e-7
				&&Math.abs(sample.pressureForceXNewtons())<1e-7&&Math.abs(sample.pressureForceZNewtons())<1e-7,
				"Wet body pressure integral disagrees with displaced hydrostatic weight: " + sample);
		var empty = new FluidDomain(3,3,3,new FluidSolverConfig(.5F,1000,9.81F,.025F,120,1e-5F,.002F));
		empty.setVolume(1,1,1,.1F);
		var dry=empty.sampleAxisAlignedBody(.55,.8,.55,.7,.95,.7);
		require(dry.displacedVolumeCubicMetres()==0&&dry.wetSurfaceAreaSquareMetres()==0,
				"Body above the actual PLIC surface acquired artificial immersion");
	}
	private static double[] momentum(FluidDomain d){
		return new double[]{java.util.Arrays.stream(d.mac.momentumX).sum(),java.util.Arrays.stream(d.mac.momentumY).sum(),java.util.Arrays.stream(d.mac.momentumZ).sum()};
	}
	private static double[] wall(FluidDomain d){return new double[]{d.lastLiquidBoundaryImpulseX,d.lastLiquidBoundaryImpulseY,d.lastLiquidBoundaryImpulseZ};}
	private static double energyStorageAllowance(FluidDomain d){
		double allowance=0;for(var stock:d.liquidCells.physicalStocks())allowance+=2*Math.ulp(stock.internalEnergy());return allowance;
	}
	private static double kinetic(FluidDomain d){
		double value=0;double[][] p={d.mac.momentumX,d.mac.momentumY,d.mac.momentumZ};
		double[][] mass={d.cellMacStage.massX,d.cellMacStage.massY,d.cellMacStage.massZ};
		for(int axis=0;axis<3;axis++)for(int i=0;i<p[axis].length;i++)if(mass[axis][i]>0)value+=.5*p[axis][i]*p[axis][i]/mass[axis][i];
		return value;
	}
	private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
