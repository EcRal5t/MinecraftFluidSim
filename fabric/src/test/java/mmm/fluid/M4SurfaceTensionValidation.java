package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import java.util.List;

/** M4 active-solver surface-tension gates: pressure-jump wiring, static droplet Laplace jump, no sustained parasitic flow and ledger conservation. */
public final class M4SurfaceTensionValidation {
private M4SurfaceTensionValidation() { }

public static void main(String[] args) {
	if (args.length == 1 && args[0].equals("--liquid-pair")) {
		validateLiquidPairPressureJump();
		validateLiquidPairIdentity();
		System.out.println("M4 liquid-pair validation passed");
		return;
	}
	if (args.length == 1 && args[0].equals("--wetting")) {
		validateContactAngleSpreading();
		validateLiquidPairPressureJump();
		validateLiquidPairIdentity();
		System.out.println("M4 wetting and liquid-pair validation passed");
		return;
	}
	SurfaceTensionCurvatureExperiment.validateResolvedGeometry();
	if (args.length == 1 && args[0].equals("--curvature")) return;
	validateSigmaZeroDegeneration();
	validateActiveSingleCellPressureJump();
	validateStaticDropletPressureJump();
	validateNoSustainedParasiticFlow();
	validateMergeBreakupConservation();
	validateContactAngleSpreading();
	validateLiquidPairPressureJump();
	validateLiquidPairIdentity();
	System.out.println("M4 surface-tension solver gate passed: static droplet Laplace jump, "
			+ "contact-angle spreading, liquid-pair pressure jump, no sustained parasitic flow and exact marker conservation");
}

private static void validateSigmaZeroDegeneration() {
FluidSolverConfig config = strictConfig();
FluidDomain pure = new FluidDomain(4, 4, 4, config, 64);
FluidDomain zeroTension = new FluidDomain(4, 4, 4, config, 64);
for (int y = 0; y < 3; y++) {
pure.setVolume(1, y, 1, y == 2 ? 0.5F : 1.0F);
zeroTension.setVolume(1, y, 1, y == 2 ? 0.5F : 1.0F);
}
FluidMaterialRegistry registry = new FluidMaterialRegistry(List.of(
new FluidMaterial("mmm:test", 1_000.0, "mmm:test", 0.0, Math.PI / 2.0)));
for (int particle = 0; particle < zeroTension.particleCount(); particle++) {
zeroTension.domainCollision.setMarkerMaterial(particle, registry, 0);
}
pure.domainStep.step();
zeroTension.domainStep.step();
check(pure.liquidCells.exactVolume().equals(zeroTension.liquidCells.exactVolume()),
"zero surface tension changed exact liquid volume");
check(pure.totalMassUnits() == zeroTension.totalMassUnits(),
"zero surface tension changed exact marker mass");
for (int z = 0; z < pure.sizeZ(); z++) for (int y = 0; y < pure.sizeY(); y++) {
for (int x = 0; x < pure.sizeX(); x++) {
check(Float.floatToRawIntBits(pure.pressure(x, y, z)) == Float.floatToRawIntBits(zeroTension.pressure(x, y, z)),
"zero surface tension changed pressure at " + x + "," + y + "," + z);
}
}
}

private static void validateActiveSingleCellPressureJump() {
FluidSolverConfig config = new FluidSolverConfig(
1.0F, 1_000.0F, 0.0F, 1.0F / 20.0F,
100, 1.0E-6F, 2.0E-3F, 0.80F
);
FluidDomain domain = new FluidDomain(4, 4, 4, config, 64);
domain.setVolume(1, 1, 1, 1.0F);
FluidMaterialRegistry registry = new FluidMaterialRegistry(List.of(
new FluidMaterial("mmm:test", 1_000.0, "mmm:test", 0.0728, Math.PI / 2.0)));
for (int particle = 0; particle < domain.particleCount(); particle++) {
domain.domainCollision.setMarkerMaterial(particle, registry, 0);
}
domain.domainStep.step();
float jump = domain.domainCollision.surfaceTensionPressureAt(1, 1, 1);
check(jump > 0.0F, "single-cell surface tension pressure was not built");
check(Float.isFinite(domain.pressure(1, 1, 1)) && domain.pressure(1, 1, 1) > 0,
"single-cell interface did not retain a positive physical capillary pressure");
FluidPhysicalAssertions.staticMotion(domain,jump,FluidPhysicalAssertions.SETTLED_ENERGY_FRACTION,
"single-cell capillary rest");
}

private static void validateStaticDropletPressureJump() {
FluidSolverConfig config = new FluidSolverConfig(
0.5F, 1_000.0F, 0.0F, 1.0F / 20.0F,
500, 1.0E-6F, 2.0E-3F, 0.80F
);
int size = 24;
double radius = 4.0;
double sigma = 0.0728;
FluidDomain domain = sphereDroplet(size, config, radius, sigma);
var exactVolume = domain.liquidCells.exactVolume(); long exactMass = domain.totalMassUnits();
FluidDomain.StepStats stats = domain.domainStep.step();
double analyticJump = 2.0 * sigma / radius;
double measuredJump = averageFluidPressure(domain);
double jumpError = Math.abs(measuredJump - analyticJump) / analyticJump;
System.out.printf("  static droplet sigma=%.4f analyticJump=%.5f measuredJump=%.5f error=%.3g "
+ "PCG=%d residual=%.3g maxSpeed=%.3g%n",
sigma, analyticJump, measuredJump, jumpError,
stats.pressureIterations(), stats.pressureResidual(), domain.maximumParticleSpeed());
check(Double.isFinite(measuredJump) && jumpError <= .15,
"static droplet Laplace pressure differs from its resolved radius: actual="+measuredJump+", expected="+analyticJump);
check(Float.isFinite(stats.pressureResidual()), "static droplet pressure residual became non-finite");
check(domain.liquidCells.exactVolume().equals(exactVolume) && domain.totalMassUnits() == exactMass,
"static droplet surface tension changed exact mass or volume");
}

private static void validateNoSustainedParasiticFlow() {
FluidSolverConfig config = new FluidSolverConfig(
0.5F, 1_000.0F, 0.0F, 1.0F / 20.0F,
500, 1.0E-6F, 2.0E-3F, 0.80F
);
int size = 24;
double radius = 4.0;
double sigma = 0.0728;
FluidDomain domain = sphereDroplet(size, config, radius, sigma);
var exactVolume = domain.liquidCells.exactVolume(); long exactMass = domain.totalMassUnits();
for (int step = 0; step < 10; step++) {
FluidDomain.StepStats stats = domain.domainStep.step();
check(Float.isFinite(stats.pressureResidual()), "static droplet pressure residual became non-finite");
check(domain.liquidCells.exactVolume().equals(exactVolume) && domain.totalMassUnits() == exactMass,
"static droplet changed exact liquid mass or volume");
FluidPhysicalAssertions.staticMotion(domain,2*sigma/radius,FluidPhysicalAssertions.SETTLED_ENERGY_FRACTION,
"static droplet capillary rest at step " + step);
}
}


/** A vertical planar contact line has zero geometric curvature. Young's prescribed
 * angle contributes -cos(theta)/h at the lower wall, so a hydrophilic wall lowers
 * the local liquid pressure and pulls fluid toward the contact line. */
private static void validateContactAngleCurvatureDirection() {
 double[] curvature = new double[3];
 double[] angles = {Math.PI / 6, Math.PI / 2, 2 * Math.PI / 3};
 for (int a = 0; a < angles.length; a++) {
  FluidDomain domain = new FluidDomain(10, 6, 4,
    new FluidSolverConfig(1, 1000, 0, .025F, 300, 1e-6F, .002F, .8F), 256);
  for (int z = 0; z < domain.sizeZ; z++) for (int x = 0; x < domain.sizeX; x++)
   domain.setSolid(x, 0, z, true);
  domain.bindMaterialRegistry(new FluidMaterialRegistry(List.of(
    new FluidMaterial("mmm:contact-plane", 1000, "mmm:contact-plane", 100, angles[a]))));
  float[] fractions = new float[domain.cellCount];
  for (int z = 0; z < domain.sizeZ; z++) for (int y = 1; y < domain.sizeY; y++)
   for (int x = 0; x <= 4; x++) fractions[domain.domainFlowFields.cellIndex(x,y,z)] = x == 4 ? .5F : 1;
  domain.setVolumes(fractions);
  domain.domainFlowFields.ensureGrid();
  int cell = domain.domainFlowFields.cellIndex(4,1,2);
  check(Math.abs(domain.tension.surfaceTensionNormalY[cell]) < 1e-6,
    "contact angle rotated the measured planar interface normal");
  curvature[a] = domain.tension.liquidGasMeanCurvature(4,1,2);
  check(Math.abs(curvature[a] + Math.cos(angles[a])) < 1e-6,
    "planar contact-line curvature has the wrong Young pressure sign: theta="
      + Math.toDegrees(angles[a]) + ", curvature=" + curvature[a]);
 }
 check(curvature[0] < curvature[1] && curvature[1] < curvature[2],
   "wall curvature did not order the contact-line pressure response");
}

private static void validateContactAngleSpreading() {
 validateContactAngleCurvatureDirection();
	FluidSolverConfig config = new FluidSolverConfig(
			1.0F, 1_000.0F, 9.81F, 1.0F / 40.0F,
			300, 1.0E-6F, 2.0E-3F, 0.80F
	);
DropletMetrics hydrophilic = runDroplet(config, Math.toRadians(30.0), 100.0, 1200);
DropletMetrics neutral = runDroplet(config, Math.toRadians(90.0), 100.0, 1200);
DropletMetrics hydrophobic = runDroplet(config, Math.toRadians(120.0), 100.0, 1200);
System.out.printf("  contact-angle spreading varianceX=%g/%g/%g m^2; peakK=%g/%g/%g J; finalRMS=%g/%g/%g m/s%n",
hydrophilic.varianceX(),neutral.varianceX(),hydrophobic.varianceX(),
hydrophilic.maximumKinetic(),neutral.maximumKinetic(),hydrophobic.maximumKinetic(),
hydrophilic.rmsSpeed(),neutral.rmsSpeed(),hydrophobic.rmsSpeed());
check(hydrophilic.varianceX() > neutral.varianceX() && neutral.varianceX() > hydrophobic.varianceX(),
"contact angle did not order the macroscopic spreading response");
}

private static DropletMetrics runDroplet(FluidSolverConfig config, double contactAngleRadians, double sigma, int steps) {
FluidDomain domain = new FluidDomain(16, 6, 4, config, 16 * 6 * 4 * 8);
for (int z = 0; z < 4; z++) for (int x = 0; x < 16; x++) domain.setSolid(x, 0, z, true);
FluidMaterialRegistry registry = new FluidMaterialRegistry(List.of(
new FluidMaterial("mmm:test", config.density(), "mmm:test", sigma, contactAngleRadians)));
domain.bindMaterialRegistry(registry);
float[] fractions = new float[domain.cellCount];
for (int z = 1; z <= 2; z++) for (int y = 1; y <= 2; y++) for (int x = 5; x <= 6; x++)
fractions[domain.domainFlowFields.cellIndex(x,y,z)] = 1;
domain.setVolumes(fractions);
var initialVolume = domain.liquidCells.exactVolume(); long initialMass = domain.totalMassUnits();
domain.domainFlowFields.ensureGrid();
double potential = 0, freeArea = 0, h = config.cellSize();
for (var stock : domain.liquidCells.physicalStocks())
potential += stock.massKilograms()*config.gravity()*(stock.key().cell()/domain.sizeX%domain.sizeY+.5)*h;
// This initial binary block has exactly exposed coarse faces; its internal
// liquid faces and the wetted floor are not part of liquid-gas surface energy.
for(var stock:domain.liquidCells.physicalStocks()){
int cell=stock.key().cell(),x=cell%domain.sizeX,y=cell/domain.sizeX%domain.sizeY,z=cell/(domain.sizeX*domain.sizeY);
for(int axis=0;axis<3;axis++)for(int side=0;side<2;side++){
int nx=x+(axis==0?2*side-1:0),ny=y+(axis==1?2*side-1:0),nz=z+(axis==2?2*side-1:0);
if(!domain.domainFlowFields.inside(nx,ny,nz))continue;
int neighbor=domain.domainFlowFields.cellIndex(nx,ny,nz);
if(!domain.geometry.solid[neighbor]&&domain.liquidGrid.stockCount[neighbor]==0)
freeArea+=domain.pressurePhaseGeometry.liquidFaceArea(cell,axis,side,domain.geometry.cutCells)*h*h;
}}
double initialMechanicalAndSurface=potential+sigma*freeArea;
double peakKinetic = 0, gravityWork = 0, pressureWork = 0, transportWork = 0, fixedWallWork = 0, surfaceWork = 0;
for (int step = 0; step < steps; step++) {
var audit = domain.domainStep.stepWithMechanicalAudit(false,0).mechanicalAudit();
gravityWork += audit.gravityWorkJoules();
pressureWork += audit.pressureProjectionWorkJoules();
transportWork += audit.volumeTransportTransferJoules()+audit.carrierInventoryTransferJoules();
fixedWallWork += audit.fixedBoundaryWorkJoules();
surfaceWork += audit.surfaceTensionWorkJoules();
check(domain.liquidCells.exactVolume().equals(initialVolume) && domain.totalMassUnits() == initialMass,
"contact-angle spreading changed exact mass or volume");
double kinetic = domain.liquidKineticEnergyJoules();
domain.domainFlowFields.ensureGrid();
double currentPotential=0,wettedFloor=0;
for(var stock:domain.liquidCells.physicalStocks()){
int cell=stock.key().cell();
currentPotential+=stock.massKilograms()*config.gravity()*(cell/domain.sizeX%domain.sizeY+.5)*h;
}
for(int z=0;z<domain.sizeZ;z++)for(int x=0;x<domain.sizeX;x++)
wettedFloor+=domain.pressurePhaseGeometry.liquidFaceArea(domain.domainFlowFields.cellIndex(x,1,z),1,0,domain.geometry.cutCells)*h*h;
// Young adhesion can release at most sigma cos(theta) times actual wetted
// floor area. Retain the same mid-cell potential allowance for partial cells.
double potentialUncertainty=0;
for(var stock:domain.liquidCells.physicalStocks())potentialUncertainty+=stock.massKilograms()*config.gravity()*h*.5*(1-Math.clamp(stock.volumeFraction(),0,1));
double energyBudget=initialMechanicalAndSurface+sigma*Math.max(0,Math.cos(contactAngleRadians))*wettedFloor+potentialUncertainty;
if (!Double.isFinite(kinetic) || kinetic+currentPotential > energyBudget) {
double mass = 0, weightedY = 0;
for (var stock : domain.liquidCells.physicalStocks()) {
double amount = stock.massKilograms();
mass += amount;
weightedY += amount*(stock.key().cell()/domain.sizeX%domain.sizeY+.5)*h;
}
throw new AssertionError("wetting motion exceeded the available gravity and surface energy: step="+(step+1)
+", theta="+Math.toDegrees(contactAngleRadians)+", K="+kinetic+", PE="+currentPotential+", budget="+energyBudget
+", RMS="+FluidPhysicalAssertions.motion(domain).rmsSpeed()+", COMy="+weightedY/mass
+", cumulativeWork[J]={gravity="+gravityWork+", pressure="+pressureWork+", transport="+transportWork
+", fixedWall="+fixedWallWork+", surface="+surfaceWork+"}, lastAudit="+audit);
}
peakKinetic = Math.max(peakKinetic,kinetic);
}
double weightedX = 0, weightedX2 = 0, volume = 0;
for (var stock : domain.liquidCells.physicalStocks()) {
double x = (stock.key().cell()%domain.sizeX+.5)*h, amount = stock.volumeFraction();
weightedX += x*amount; weightedX2 += x*x*amount; volume += amount;
}
double meanX = weightedX/volume;
return new DropletMetrics(weightedX2/volume-meanX*meanX,peakKinetic,
FluidPhysicalAssertions.motion(domain).rmsSpeed());
}

private record DropletMetrics(double varianceX, double maximumKinetic, double rmsSpeed) { }

	private static void validateLiquidPairPressureJump() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 0.0F, 1.0F / 20.0F,
				800, 1.0E-6F, 2.0E-3F, 0.80F
		);
		int size = 20;
		double radius = 4.0;
		// Resolve the pressure response above the production geometric pressure
		// scale; a water-scale 0.04 Pa signal cannot distinguish pressure from zero.
		double sigma12 = 100;
		FluidDomain domain = liquidPairDroplet(size, config, radius, sigma12);
		domain.domainFlowFields.ensureGrid();
		var gasColor=domain.tension.surfaceTensionColor.clone();
		var gasNormal=domain.tension.surfaceTensionNormalY.clone();
		domain.tension.prepareLiquidPair(domain.materialRegistry.phaseHandle(0),domain.materialRegistry.phaseHandle(1));
		check(java.util.Arrays.equals(gasColor,domain.tension.surfaceTensionColor)
				&&java.util.Arrays.equals(gasNormal,domain.tension.surfaceTensionNormalY),"liquid-pair preparation overwrote the retained liquid-gas interface");
		var exactVolume = domain.liquidCells.exactVolume(); long exactMass = domain.totalMassUnits();
		FluidDomain.StepStats stats = domain.domainStep.step();
		double measuredJump = averagePressureInside(domain, size, radius) - averagePressureOutside(domain, size, radius);
		double expectedJump = sigma12 * SurfaceTensionCurvatureExperiment.interfaceMeanCurvature(
				binarySphereColour(size, radius, config.cellSize()), size, config.cellSize());
		double jumpError = Math.abs(measuredJump - expectedJump) / expectedJump;
		System.out.printf("  liquid-pair droplet sigma12=%.4f expectedJump=%.5f measuredJump=%.5f error=%.3g "
				+ "PCG=%d residual=%.3g maxSpeed=%.3g%n",
				sigma12, expectedJump, measuredJump, jumpError,
				stats.pressureIterations(), stats.pressureResidual(), domain.maximumParticleSpeed());
		check(Double.isFinite(measuredJump) && jumpError<=.2,
				"liquid-pair Laplace pressure differs from its resolved interface: actual="+measuredJump+", expected="+expectedJump);
		check(Float.isFinite(stats.pressureResidual()), "liquid-pair pressure residual became non-finite");
		check(domain.liquidCells.exactVolume().equals(exactVolume) && domain.totalMassUnits() == exactMass,
				"liquid-pair surface tension changed exact mass or volume");
		for (int step = 0; step < 5; step++) {
			FluidDomain.StepStats noFlowStats = domain.domainStep.step();
			check(Float.isFinite(noFlowStats.pressureResidual()), "liquid-pair droplet pressure residual became non-finite");
			check(domain.liquidCells.exactVolume().equals(exactVolume) && domain.totalMassUnits() == exactMass,
					"liquid-pair surface tension changed exact mass or volume");
			FluidPhysicalAssertions.staticMotion(domain,expectedJump,FluidPhysicalAssertions.SETTLED_ENERGY_FRACTION,
					"liquid-pair capillary rest at step " + step);
		}
	}

	private static void validateLiquidPairIdentity() {
		FluidSolverConfig config = new FluidSolverConfig(.5F, 1000, 0, .025F, 800, 1E-6F, .002F, .8F);
		FluidDomain domain = liquidPairDroplet(8, config, 1, .0728, true);
		domain.domainStep.step();
		check(domain.maximumPressure() == 0, "a liquid pair acted on an interface with its first phase absent");
		check(domain.maximumParticleSpeed() == 0, "an unrelated liquid pair created liquid motion");
		FluidDomain separated = liquidPairDroplet(12, config, .5, .0728, true);
		separated.domainCollision.setMaterialInCell(0,0,0,separated.materialRegistry,1);
		separated.domainStep.step();
		check(separated.maximumPressure() == 0, "a remote phase changed an unrelated liquid interface");
		check(separated.maximumParticleSpeed() == 0, "disjoint liquid-pair supports created motion");
		FluidDomain full = liquidPairDroplet(8, config, 1, .0728, true);
		FluidDomain diluted = liquidPairDroplet(8, config, 1, .0728, true);
		replaceAbsentFirstPhase(full,1);replaceAbsentFirstPhase(diluted,.125);
		double fullWork=full.domainStep.stepWithMechanicalAudit(false,0).mechanicalAudit().surfaceTensionWorkJoules();
		double dilutedWork=diluted.domainStep.stepWithMechanicalAudit(false,0).mechanicalAudit().surfaceTensionWorkJoules();
		check(Double.isFinite(fullWork)&&Double.isFinite(dilutedWork)&&fullWork>0&&dilutedWork>0&&dilutedWork<fullWork,
				"liquid-pair force response did not decrease with the participating phase fraction");
		check(diluted.maximumPressure()<=full.maximumPressure()+pressureResolution(config),
				"liquid-pair pressure response reversed beyond the cell-volume resolution");
	}

	/** delta V = dt^2 delta p/(rho h^2), at the production geometry's cell-volume resolution. */
	private static double pressureResolution(FluidSolverConfig config){
		return config.density()*config.cellSize()*config.cellSize()
				*FlipPressurePhaseGeometry.CELL_VOLUME_RESOLUTION/(config.timeStep()*(double)config.timeStep());
	}

	private static FluidDomain liquidPairDroplet(int size, FluidSolverConfig config, double radius, double sigma12) {
		return liquidPairDroplet(size, config, radius, sigma12, false);
	}

	private static FluidDomain liquidPairDroplet(int size, FluidSolverConfig config, double radius, double sigma12,
			boolean absentFirstPhase) {
		var domain=new FluidDomain(size,size,size,config,size*size*size*8);
		FluidMaterial a=new FluidMaterial("mmm:a",config.density(),"mmm:a",0,Math.PI/2);
		FluidMaterial b=new FluidMaterial("mmm:b",config.density(),"mmm:b",0,Math.PI/2);
		FluidMaterial c=new FluidMaterial("mmm:c",config.density(),"mmm:c",0,Math.PI/2);
		var registry=absentFirstPhase?new FluidMaterialRegistry(List.of(a,b,c),List.of(new FluidMaterialRegistry.LiquidPairTension(1,2,sigma12)))
				:new FluidMaterialRegistry(List.of(a,b),List.of(new FluidMaterialRegistry.LiquidPairTension(0,1,sigma12)));
		domain.bindMaterialRegistry(registry);
		double[] colour=SurfaceTensionCurvatureExperiment.sphereColour(size,radius,config.cellSize());
		float[] volume=new float[domain.cellCount];java.util.Arrays.fill(volume,1);int[] material=new int[domain.cellCount];
		for(int cell=0;cell<material.length;cell++)material[cell]=colour[cell]>=.5?(absentFirstPhase?2:1):0;
		domain.setVolumes(volume,material);
		return domain;
	}

	private static void replaceAbsentFirstPhase(FluidDomain domain,double fraction) {
		var edits=new java.util.ArrayList<LiquidCellInventory.Edit>();
		for(var stock:domain.liquidCells.stocks())if(stock.key().material()==0) {
			var first=new LiquidCellInventory.Key(stock.key().cell(),1,stock.key().supportMask());
			var volume=mmm.units.SignedDyadic.of(stock.volumeFraction()*fraction);
			long mass=Math.round(stock.massUnits()*fraction);
			edits.add(new LiquidCellInventory.Edit(stock.key(),fraction==1?null:new LiquidCellInventory.Stock(stock.key(),stock.volume().subtract(volume),stock.massUnits()-mass,null,0,false)));
			edits.add(new LiquidCellInventory.Edit(first,new LiquidCellInventory.Stock(first,volume,mass,null,0,false)));
		}
		domain.liquidCells.planEdits(edits).commit();domain.markCellInventoryChanged();
	}

	private static double[] binarySphereColour(int size, double radius, double cellSize) {
		double[] colour = SurfaceTensionCurvatureExperiment.sphereColour(size, radius, cellSize);
		for (int i = 0; i < colour.length; i++) colour[i] = colour[i] >= 0.5 ? 1.0 : 0.0;
		return colour;
	}

	private static double averagePressureInside(FluidDomain domain, int size, double radius) {
		byte[] mask = domain.copyMarkerMask();
		double sum = 0.0;
		int cells = 0;
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			if (mask[(z * size + y) * size + x] == 0) continue;
			double dx = (x + 0.5) * domain.config().cellSize() - 0.5 * (size - 1) * domain.config().cellSize();
			double dy = (y + 0.5) * domain.config().cellSize() - 0.5 * (size - 1) * domain.config().cellSize();
			double dz = (z + 0.5) * domain.config().cellSize() - 0.5 * (size - 1) * domain.config().cellSize();
			// CSF smooths a three-cell stencil. Measure its bulk pressure plateau,
			// leaving the diffuse interface itself out of the Laplace jump sample.
			double coreRadius=radius-2*domain.config().cellSize();
			if (dx * dx + dy * dy + dz * dz <= coreRadius * coreRadius) {
				sum += domain.pressure(x, y, z);
				cells++;
			}
		}
		return cells == 0 ? 0.0 : sum / cells;
	}

	private static double averagePressureOutside(FluidDomain domain, int size, double radius) {
		byte[] mask = domain.copyMarkerMask();
		double sum = 0.0;
		int cells = 0;
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			if (mask[(z * size + y) * size + x] == 0) continue;
			double dx = (x + 0.5) * domain.config().cellSize() - 0.5 * (size - 1) * domain.config().cellSize();
			double dy = (y + 0.5) * domain.config().cellSize() - 0.5 * (size - 1) * domain.config().cellSize();
			double dz = (z + 0.5) * domain.config().cellSize() - 0.5 * (size - 1) * domain.config().cellSize();
			double outerRadius=radius+2*domain.config().cellSize();
			if (dx * dx + dy * dy + dz * dz >= outerRadius * outerRadius) {
				sum += domain.pressure(x, y, z);
				cells++;
			}
		}
		return cells == 0 ? 0.0 : sum / cells;
	}

private static void validateMergeBreakupConservation() {
FluidSolverConfig config = new FluidSolverConfig(
1.0F, 1_000.0F, 0.0F, 1.0F / 20.0F,
200, 1.0E-6F, 2.0E-3F, 0.80F
);
FluidDomain domain = new FluidDomain(8, 8, 8, config, 8 * 8 * 8 * 8);
FluidDomain zeroTension = new FluidDomain(8, 8, 8, config, 8 * 8 * 8 * 8);
FluidMaterialRegistry registry = new FluidMaterialRegistry(List.of(
new FluidMaterial("mmm:test", 1_000.0, "mmm:test", 0.0728, Math.PI / 2.0)));
domain.setVolume(2, 2, 3, 1.0F);
domain.setVolume(3, 2, 3, 1.0F);
domain.setVolume(2, 3, 3, 0.5F);
domain.setVolume(3, 3, 3, 0.5F);
domain.setVolume(5, 2, 3, 1.0F);
domain.setVolume(6, 2, 3, 1.0F);
domain.setVolume(5, 3, 3, 0.5F);
domain.setVolume(6, 3, 3, 0.5F);
for(var stock:domain.liquidCells.stocks()){
int cell=stock.key().cell();zeroTension.setVolume(cell%8,cell/8%8,cell/64,(float)stock.volumeFraction());
}
for (int particle = 0; particle < domain.particleCount(); particle++) {
domain.domainCollision.setMarkerMaterial(particle, registry, 0);
}
double exactVolume = domain.totalVolume();
double exactMass = domain.totalMass();
double peakCapillaryKinetic=0,peakZeroKinetic=0;
for (int step = 0; step < 20; step++) {
domain.domainStep.step();zeroTension.domainStep.step();
check(domain.totalVolume() == exactVolume && domain.totalMass() == exactMass,
"surface-tension merge/breakup changed physical ledgers");
check(zeroTension.totalVolume()==exactVolume&&zeroTension.totalMass()==exactMass,"zero-tension control changed physical ledgers");
peakCapillaryKinetic=Math.max(peakCapillaryKinetic,domain.liquidKineticEnergyJoules());
peakZeroKinetic=Math.max(peakZeroKinetic,zeroTension.liquidKineticEnergyJoules());
}
check(Double.isFinite(peakCapillaryKinetic)&&peakCapillaryKinetic>peakZeroKinetic,
"A nonuniform-curvature interface had no mechanical response relative to its zero-tension control");
}

/** Cell inventories of the binary spherical reference used by the pressure and rest scenarios. */
private static FluidDomain sphereDroplet(int size, FluidSolverConfig config, double radius, double sigma) {
FluidDomain domain = new FluidDomain(size, size, size, config, size * size * size * 8);
double[] colour = SurfaceTensionCurvatureExperiment.sphereColour(size, radius, config.cellSize());
FluidMaterialRegistry registry = new FluidMaterialRegistry(List.of(
new FluidMaterial("mmm:test", config.density(), "mmm:test", sigma, Math.PI / 2.0)));
domain.bindMaterialRegistry(registry);
float[] fractions = new float[colour.length];
for (int cell = 0; cell < fractions.length; cell++) fractions[cell] = colour[cell] >= .5 ? 1 : 0;
domain.setVolumes(fractions);
return domain;
}

private static double averageFluidPressure(FluidDomain domain) {
double sum = 0.0;
int cells = 0;
for (int z = 0; z < domain.sizeZ(); z++) for (int y = 0; y < domain.sizeY(); y++) {
for (int x = 0; x < domain.sizeX(); x++) {
if (!domain.domainFlowFields.isFluid(domain.domainFlowFields.cellIndex(x, y, z))) continue;
sum += domain.pressure(x, y, z);
cells++;
}
}
return cells == 0 ? 0.0 : sum / cells;
}

private static FluidSolverConfig strictConfig() {
return new FluidSolverConfig(
1.0F, 1_000.0F, 0.0F, 1.0F / 40.0F,
300, 1.0E-7F, 2.0E-3F, 0.80F
);
}

private static void check(boolean condition, String message) {
if (!condition) throw new AssertionError(message);
}
}
