package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Cell inventory, free-surface physics and persistent MAC momentum scenarios. */
public final class FlipFluidFreeSurfaceValidation {
	private FlipFluidFreeSurfaceValidation() { }
	public static void main(String[] args) {
		var selected = Arrays.asList(args);
		planeVolumeInversionIsAccurate();
		liquidGeometryRetainsInventoryAndOpenSupport();
		axisAlignedInterfaceDistanceUsesExactCellLedger();
		if (selected.contains("--geometry")) {
			System.out.println("Cell liquid geometry validation passed"); return;
		}
		cellMassLedgerTracksVolume();
		closedComponentsUseActualCapacity();
		collisionWallsRetainInventory();
		persistentMacMomentumRetainsSmoothVortex();
		bodyOverlapResolvesContinuousSubmergence();
		fractionalHydrostaticColumnsBalanceGravity();
		thinLayerRegularizationRemainsStable();
		resolvedLiquidReceivesGravity();
		if (!selected.contains("--fast")) {
			longRunHydrostaticSurfaceDoesNotSink();
			MarkerDistributionExperiment.damDistribution();
			waterDescendsAStairSlope();
		}
		System.out.println("Cell/MAC free-surface validation passed" + (selected.contains("--fast") ? " (long hydrostatic, dam and stair scenarios skipped)" : ""));
	}

	private static void liquidGeometryRetainsInventoryAndOpenSupport() {
		for (int mask : new int[]{0, 0xAA, 0xFE}) {
			var domain = new FluidDomain(3,3,3,strictConfig());
			int cell = domain.cellIndex(1,1,1);
			domain.geometry.cutCells.replaceMask(cell,mask);
			for (double fill : new double[]{Math.scalb(1.0,-25),.0625,.125}) {
				double[] volume = new double[27]; volume[cell]=fill;
				var phase = new FlipPressurePhaseGeometry(3,3,3);
				phase.rebuild(new boolean[27],volume,domain.geometry.cutCells,null,new boolean[27]);
				double[] integral=new double[4];
				new LiquidFluxGeometry(domain.geometry.cutCells).sweptMoments(cell,14,0,1,true,
						phase.planeNormal(cell,0),phase.planeNormal(cell,1),phase.planeNormal(cell,2),phase.planeOffset(cell),integral);
				check(Math.abs(integral[0]-fill)<2e-14,"Cell geometry changed retained liquid volume");
				for(int axis=0;axis<3;axis++) {
					double centre=integral[axis+1]/integral[0]+.5;
					check(centre>=0&&centre<=1,"Liquid centroid escaped its cell support");
				}
			}
		}
	}

	private static void cellMassLedgerTracksVolume() {
		var domain=new FluidDomain(4,4,4,FluidSolverConfig.prototypeDefaults());
		domain.setVolume(1,1,1,1);domain.setVolume(2,1,1,.375F);
		check(Math.abs(domain.totalMass()-domain.config.density()*domain.totalVolume())<=1e-9,"Water inventory density changed");
		domain.removeVolumeNear(2,1,1,.125F,0);
		var volume=volume(domain);long mass=mass(domain);
		domain.step();
		assertInventory(domain,volume,mass);
		var restored=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		assertInventory(restored,volume,mass);
		check(domain.exportLiquidCellState().stocks().equals(restored.exportLiquidCellState().stocks()),"Backend handoff changed cell quantities");
		check(Arrays.equals(domain.exportMacMomentum().copyX(),restored.exportMacMomentum().copyX())
				&&Arrays.equals(domain.exportMacMomentum().copyY(),restored.exportMacMomentum().copyY())
				&&Arrays.equals(domain.exportMacMomentum().copyZ(),restored.exportMacMomentum().copyZ()),"Backend handoff changed MAC momentum");
	}

	private static void closedComponentsUseActualCapacity() {
		var domain=new FluidDomain(4,4,2,FluidSolverConfig.prototypeDefaults());
		for(int z=0;z<2;z++)for(int y=0;y<4;y++){
			domain.setSolid(2,y,z,true);
			for(int x=0;x<2;x++)domain.setVolume(x,y,z,1);
			domain.setVolume(3,y,z,.25F);
		}
		domain.ensureGrid();
		for(int z=0;z<2;z++)for(int y=0;y<4;y++)for(int x=0;x<4;x++){
			int cell=domain.cellIndex(x,y,z);
			check(domain.projection.pressureVolume[cell]==(x<2?1:x==3?.25:0),"Pressure preparation moved inventory across a wall");
		}
		var capacity=domain.availableCellVolumes();
		check(capacity[domain.cellIndex(0,0,0)].signum()==0&&capacity[domain.cellIndex(3,0,0)].equals(SignedDyadic.of(.75)),"Separate cell capacity changed");
	}

	private static void collisionWallsRetainInventory() {
		var config=new FluidSolverConfig(.5F,1000,0,.01F,300,1e-7F,.4F,.002F,0);
		var domain=new FluidDomain(4,4,2,config);
		for(int z=0;z<2;z++)for(int y=0;y<4;y++){
			domain.setSolid(2,y,z,true);
			for(int x=0;x<2;x++)domain.setVolume(x,y,z,1);
		}
		domain.ensureGrid();
		for(int f=0;f<domain.mac.velocityX.length;f++)if(domain.geometry.cutCells.openFaceField(0)[f]>0)domain.mac.velocityX[f]=.1F;
		domain.cellMacStage.publishMomentum();
		var volume=volume(domain);long mass=mass(domain);
		for(int step=0;step<4;step++)domain=advance(domain).claimPreparedCpuDomain();
		assertInventory(domain,volume,mass);domain.ensureGrid();
		for(var stock:domain.exportLiquidCellState().stocks())check(stock.key().cell()%4<2,"Liquid crossed a collision wall");
		for(int f=0;f<domain.mac.velocityX.length;f++)if(domain.geometry.cutCells.openFaceField(0)[f]==0)
			check(domain.mac.velocityX[f]==0,"Fixed collision face acquired normal velocity");
	}

	private static void persistentMacMomentumRetainsSmoothVortex() {
		var config=new FluidSolverConfig(.5F,1000,0,.001F,300,1e-7F,.4F,.002F,0);
		var domain=new FluidDomain(8,8,2,config);
		for(int z=0;z<2;z++)for(int y=0;y<8;y++)for(int x=0;x<8;x++)domain.setVolume(x,y,z,1);
		domain.ensureGrid();
		// The discrete stream function gives zero face divergence and zero normal wall flux.
		for(int z=0;z<2;z++)for(int y=0;y<8;y++)for(int x=0;x<=8;x++)
			domain.mac.velocityX[(z*8+y)*9+x]=(float)((stream(x,y+1)-stream(x,y))/.5);
		for(int z=0;z<2;z++)for(int y=0;y<=8;y++)for(int x=0;x<8;x++)
			domain.mac.velocityY[(z*9+y)*8+x]=(float)(-(stream(x+1,y)-stream(x,y))/.5);
		domain.cellMacStage.publishMomentum();
		double initial=domain.particleKineticEnergyJoules();var volume=volume(domain);long mass=mass(domain);
		for(int step=0;step<40;step++)domain=advance(domain).claimPreparedCpuDomain();
		double ratio=domain.particleKineticEnergyJoules()/initial;
		check(initial>0&&ratio>=.98&&ratio<=1+64*Math.ulp(1.0F),"Unforced vortex exceeded its 2% coarse-grid energy budget: "+ratio);
		assertInventory(domain,volume,mass);
	}
	private static double stream(int x,int y){return .02*4/Math.PI*Math.sin(Math.PI*x/8)*Math.sin(Math.PI*y/8);}

	private static void resolvedLiquidReceivesGravity() {
		for(boolean pool:new boolean[]{false,true}){
			var config=new FluidSolverConfig(.5F,1000,9.81F,.025F,300,1e-7F,.4F,.002F,0);
			var domain=new FluidDomain(4,12,4,config);
			if(pool)for(int z=0;z<4;z++)for(int x=0;x<4;x++)domain.setVolume(x,0,z,1);
			for(int z=1;z<3;z++)for(int y=8;y<10;y++)for(int x=1;x<3;x++)domain.setVolume(x,y,z,1);
			var inventory=volume(domain);long mass=mass(domain);double initial=upperCentre(domain);
			for(int step=0;step<4;step++)domain=advance(domain).claimPreparedCpuDomain();
			domain.ensureGrid();double momentum=0,upperMass=0;
			for(int z=0;z<4;z++)for(int y=5;y<=12;y++)for(int x=0;x<4;x++)momentum+=domain.mac.momentumY[(z*13+y)*4+x];
			for(var stock:domain.exportLiquidCellState().stocks())if(stock.key().cell()/4%12>=5)upperMass+=stock.massKilograms();
			check(Math.abs(momentum/upperMass+config.gravity()*config.timeStep()*4)<2e-5,"Resolved falling liquid lost gravity impulse");
			check(upperCentre(domain)<initial,"Resolved liquid did not fall above its pool");
			assertInventory(domain,inventory,mass);
		}
	}
	private static double upperCentre(FluidDomain domain){
		double weighted=0,volume=0;
		for(var stock:domain.exportLiquidCellState().stocks()){
			int y=stock.key().cell()/domain.sizeX%domain.sizeY;
			if(y>=5){weighted+=(y+.5)*stock.volumeFraction();volume+=stock.volumeFraction();}
		}
		check(volume>0,"Falling liquid disappeared");return domain.config.cellSize()*weighted/volume;
	}

	private static void planeVolumeInversionIsAccurate() {
		float[][] normals = {
				{1.0F, 0.0F, 0.0F},
				{0.0F, -1.0F, 0.0F},
				{0.70710677F, 0.70710677F, 0.0F},
				{0.125F, -1.0F, 0.0F},
				{0.0F, -0.125F, 1.0F},
				{-1.0F, 0.0F, 0.125F},
				{-0.26726124F, 0.5345225F, 0.8017837F}
		};
		float[] fractions = {0.001F, 0.05F, 0.0625F, 0.10F, 0.25F, 0.50F, 0.75F, 0.90F, 0.9375F, 0.95F, 0.999F};
		for (float[] normal : normals) for (float fraction : fractions) {
			float offset = FlipFreeSurfaceGeometry.solvePlaneOffset(normal[0], normal[1], normal[2], fraction);
			float reconstructed = FlipFreeSurfaceGeometry.unitCubeLiquidFraction(
					normal[0], normal[1], normal[2], offset
			);
			check(Math.abs(reconstructed - fraction) < 2.0E-6F,
					"PLIC inversion drifted: expected=" + fraction + ", reconstructed=" + reconstructed);
		}
	}

	private static void axisAlignedInterfaceDistanceUsesExactCellLedger() {
		for (float fraction : new float[] {0.60F, 0.75F, 1.0F}) {
			int sizeX = 5, sizeY = 4, sizeZ = 5;
			int cellCount = sizeX * sizeY * sizeZ;
			boolean[] solid = new boolean[cellCount];
			FlipPressurePhaseGeometry geometry = new FlipPressurePhaseGeometry(sizeX, sizeY, sizeZ);
			FlipCutCellGeometry cutCells = new FlipCutCellGeometry(sizeX, sizeY, sizeZ);
			double[] exactVolume = new double[cellCount];
			boolean[] pressureFluid = new boolean[cellCount];
			for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
				int lower = index(sizeX, sizeY, x, 0, z);
				int surface = index(sizeX, sizeY, x, 1, z);
				exactVolume[lower] = 1.0F;
				exactVolume[surface] = fraction;
			}
			geometry.rebuild(solid, exactVolume, cutCells, null, pressureFluid);
			int surface = index(sizeX, sizeY, 2, 1, 2);
			float expected = fraction >= 1.0F - 1.0E-6F ? 0.5F : fraction - 0.5F;
			float actual = geometry.interfaceTheta(surface, 0, 1, 0);
			check(Math.abs(actual - expected) < 2.0E-5F,
					"axis-aligned theta did not follow the exact cell fraction: fraction=" + fraction + ", theta=" + actual);
			check(Math.abs(geometry.liquidFraction(surface) - fraction) < 1.0E-7F,
					"geometry did not retain the exact per-cell marker sum");
		}
	}

	private static void bodyOverlapResolvesContinuousSubmergence() {
		FluidSolverConfig config = FluidSolverConfig.prototypeDefaults();
		FluidDomain domain = new FluidDomain(6, 6, 6, config, 6 * 6 * 6 * 8);
		for (int z = 0; z < 6; z++) for (int x = 0; x < 6; x++) {
			domain.setVolume(x, 0, z, 1.0F);
			domain.setVolume(x, 1, z, 1.0F);
			domain.setVolume(x, 2, z, 1.0F);
		}
		double width = 0.60;
		double height = 1.80;
		double expectedFraction = 0.72;
		double surfaceY = 3.0 * config.cellSize();
		double minimumY = surfaceY - height * expectedFraction;
		FluidDomain.BodyFluidSample sample = domain.sampleAxisAlignedBody(
				1.20, minimumY, 1.20, 1.20 + width, minimumY + height, 1.20 + width);
		double bodyVolume = width * height * width;
		check(Math.abs(sample.displacedVolumeCubicMetres() / bodyVolume - expectedFraction) < 1.0E-6,
				"body overlap quantized continuous neutral submergence: "
						+ sample.displacedVolumeCubicMetres() / bodyVolume);
		check(sample.centerYMetres() >= minimumY && sample.centerYMetres() <= surfaceY,
				"body overlap centroid escaped the wet AABB segment: " + sample.centerYMetres());

		FluidDomain.BodyFluidSample shifted = domain.sampleAxisAlignedBody(
				1.20, minimumY + 0.01, 1.20, 1.20 + width, minimumY + height + 0.01, 1.20 + width);
		check(Math.abs((sample.displacedVolumeCubicMetres() - shifted.displacedVolumeCubicMetres())
				- width * width * 0.01) < 1.0E-6,
				"body overlap did not respond continuously to a sub-sample vertical displacement");
	}

	private static void fractionalHydrostaticColumnsBalanceGravity() {
		for(float fraction:new float[]{.60F,.75F,1}){
			var config=strictConfig();var domain=new FluidDomain(2,5,2,config);
			for(int z=0;z<2;z++)for(int y=0;y<3;y++)for(int x=0;x<2;x++)domain.setVolume(x,y,z,y==2?fraction:1);
			var volume=volume(domain);long mass=mass(domain);
			for(int step=0;step<200;step++)domain=advance(domain).claimPreparedCpuDomain();
			float theta=fraction==1?.5F:fraction-.5F;
			for(int y=0;y<=2;y++){
				float expected=config.density()*config.gravity()*config.cellSize()*(2-y+theta),actual=domain.pressure(0,y,0);
				check(Math.abs(actual-expected)/Math.max(1,expected)<2e-4F,"Fractional hydrostatic pressure missed its analytic column: fraction="+fraction+", y="+y+", expected="+expected+", actual="+actual);
			}
			check(domain.maximumParticleSpeed()<2e-4F,"Hydrostatic column retained parasitic motion");assertInventory(domain,volume,mass);
		}
	}

	private static void thinLayerRegularizationRemainsStable() {
		FluidSolverConfig config = strictConfig();
		FluidDomain domain = new FluidDomain(3, 3, 3, config);
		for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) domain.setVolume(x, 0, z, 0.25F);
		var exactVolume=volume(domain);long exactMass=mass(domain);
		for (int step = 0; step < 40; step++) {
			var candidate = advance(domain);
			domain = candidate.claimPreparedCpuDomain();
			var stats = candidate.stats();
			check(Float.isFinite(stats.pressureResidual()), "thin-layer pressure residual became non-finite");
			check(stats.relativeVolumeError() < 1.0E-6F, "thin-layer step changed liquid volume");
		}
		assertInventory(domain,exactVolume,exactMass);
		check(domain.maximumParticleSpeed() < 2.0E-3F,
				"theta regularization did not keep a sub-center thin layer stable: " + domain.maximumParticleSpeed());
	}

	private static void longRunHydrostaticSurfaceDoesNotSink(){
		var domain=new FluidDomain(4,8,4,strictConfig());
		for(int z=0;z<4;z++)for(int x=0;x<4;x++)domain.setSolid(x,0,z,true);
		for(int z=0;z<4;z++)for(int y=1;y<=4;y++)for(int x=0;x<4;x++)domain.setVolume(x,y,z,1);
		var volume=volume(domain);long mass=mass(domain);double initial=centreY(domain);
		for(int step=0;step<5000;step++)domain=advance(domain).claimPreparedCpuDomain();
		assertInventory(domain,volume,mass);
		check(Math.abs(centreY(domain)-initial)<5e-4,"Hydrostatic surface sank over 5000 steps");
		double[] layers=layers(domain);
		for(int y=1;y<=4;y++)check(Math.abs(layers[y]-16)<1e-7,"Hydrostatic layer changed volume at y="+y+": "+layers[y]);
		check(domain.maximumParticleSpeed()<1e-4,"Hydrostatic surface retained persistent motion");
	}

	private static void waterDescendsAStairSlope() {
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				200, 1.0E-5F, 0.35F, 2.0E-3F, 0.80F
		);
		FluidDomain domain = new FluidDomain(12, 8, 4, config);
		for (int z = 0; z < 4; z++) for (int x = 0; x < 12; x++) {
			int topSolid = x < 2 ? 3 : x < 4 ? 2 : 1;
			for (int y = 0; y <= topSolid; y++) domain.setSolid(x, y, z, true);
		}
		for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
			int firstFluid = x < 2 ? 4 : 3;
			for (int y = firstFluid; y <= 6; y++) domain.setVolume(x, y, z, 1.0F);
		}
		var exactVolume=volume(domain);long exactMass=mass(domain);
		float initialCenter = domain.centerOfMassX();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		for (int step = 0; step < 300; step++) {
			var candidate = backend.evaluate(new FluidStepRequest(step, domain.captureAuthoritativeState(),
					java.util.List.of(), false, 0));
			FluidDomain.StepStats stats = candidate.stats();
			domain = candidate.claimPreparedCpuDomain();
			check(Float.isFinite(stats.pressureResidual()), "stair-slope pressure residual became non-finite");
			check(stats.relativeVolumeError() < 2.0E-6F, "stair-slope flow changed liquid volume");
		}
		check(domain.centerOfMassX() > initialCenter + 1.0F,
				"water did not descend the fixed stair slope: before=" + initialCenter + ", after=" + domain.centerOfMassX());
		assertInventory(domain,exactVolume,exactMass);
	}

	private static FluidSolverConfig strictConfig() {
		return new FluidSolverConfig(
				1.0F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				300, 1.0E-7F, 0.40F, 2.0E-3F, 0.80F
		);
	}

	private static int index(int sizeX, int sizeY, int x, int y, int z) {
		return (z * sizeY + y) * sizeX + x;
	}

	private static SignedDyadic volume(FluidDomain domain){var value=SignedDyadic.ZERO;for(var stock:domain.exportLiquidCellState().stocks())value=value.add(stock.volume());return value;}
	private static long mass(FluidDomain domain){long value=0;for(var stock:domain.exportLiquidCellState().stocks())value=Math.addExact(value,stock.massUnits());return value;}
	private static void assertInventory(FluidDomain domain,SignedDyadic expectedVolume,long expectedMass){
		check(volume(domain).equals(expectedVolume),"Liquid volume inventory changed");check(mass(domain)==expectedMass,"Liquid integer mass changed");
		var available=domain.availableCellVolumes();for(var capacity:available)check(capacity.signum()>=0,"Liquid exceeded joint cell capacity");
	}
	private static double[] layers(FluidDomain domain){double[] result=new double[domain.sizeY];for(var stock:domain.exportLiquidCellState().stocks())result[stock.key().cell()/domain.sizeX%domain.sizeY]+=stock.volumeFraction();return result;}
	private static double centreY(FluidDomain domain){double weighted=0,volume=0;var layers=layers(domain);for(int y=0;y<layers.length;y++){volume+=layers[y];weighted+=(y+.5)*layers[y];}return domain.config.cellSize()*weighted/volume;}
	private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
	/** One complete private production candidate, including supported mechanical time refinement. */
	private static FluidStepCandidate advance(FluidDomain domain) {
		return new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0, domain.captureAuthoritativeState(), List.of(), false, 0));
	}

}
