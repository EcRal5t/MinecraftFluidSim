package mmm.fluid;

import java.util.List;

/** M4 active-solver surface-tension gates: pressure-jump wiring, static droplet Laplace jump, no sustained parasitic flow and ledger conservation. */
public final class M4SurfaceTensionValidation {
private M4SurfaceTensionValidation() { }

public static void main(String[] args) {
	validateCurvatureGeometry();
	SurfaceTensionCurvatureExperiment.validateResolvedGeometry();
	if (args.length == 1 && args[0].equals("--curvature")) return;
	CapillaryNeckValidation.validate();
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

/** Analytic geometry checks formerly held by a separate pre-integration M4 task. */
private static void validateCurvatureGeometry() {
	int planarSize = 16;
	double cellSize = 0.5;
	double[] slab = SurfaceTensionCurvatureExperiment.smooth(
			SurfaceTensionCurvatureExperiment.slabColour(planarSize, cellSize, 8.0 * cellSize), planarSize);
	double planar = SurfaceTensionCurvatureExperiment.meanCurvature(slab, planarSize, 8, 8, 8, cellSize);
	check(Math.abs(planar) <= 1.0E-6, "planar interface curvature must vanish, got " + planar);

	for (double radius : new double[] {3.0, 4.0, 5.0}) {
		double[] colour = SurfaceTensionCurvatureExperiment.sphereColour(24, radius, cellSize);
		double curvature = SurfaceTensionCurvatureExperiment.interfaceMeanCurvature(colour, 24, cellSize);
		double analytic = 2.0 / radius;
		check(Math.abs(curvature - analytic) / analytic <= 0.06,
				"sphere R=" + radius + " curvature missed 2/R: " + curvature + " vs " + analytic);
	}
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
zeroTension.setMarkerMaterial(particle, registry, 0);
}
pure.step();
zeroTension.step();
check(Float.floatToRawIntBits(pure.totalVolume()) == Float.floatToRawIntBits(zeroTension.totalVolume()),
"zero surface tension changed exact marker volume");
check(Double.doubleToRawLongBits(pure.totalMass()) == Double.doubleToRawLongBits(zeroTension.totalMass()),
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
100, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F
);
FluidDomain domain = new FluidDomain(4, 4, 4, config, 64);
domain.setVolume(1, 1, 1, 1.0F);
FluidMaterialRegistry registry = new FluidMaterialRegistry(List.of(
new FluidMaterial("mmm:test", 1_000.0, "mmm:test", 0.0728, Math.PI / 2.0)));
for (int particle = 0; particle < domain.particleCount(); particle++) {
domain.setMarkerMaterial(particle, registry, 0);
}
domain.step();
float jump = domain.surfaceTensionPressureAt(1, 1, 1);
check(jump > 0.0F, "single-cell surface tension pressure was not built");
check(Math.abs(domain.pressure(1, 1, 1) - jump) <= 1.0E-6F,
"active pressure solve did not impose the single-cell Laplace jump: pressure="
+ domain.pressure(1, 1, 1) + ", jump=" + jump);
check(domain.maximumParticleSpeed() <= 1.0E-8F,
"single-cell surface tension projection left parasitic velocity: " + domain.maximumParticleSpeed());
}

private static void validateStaticDropletPressureJump() {
FluidSolverConfig config = new FluidSolverConfig(
0.5F, 1_000.0F, 0.0F, 1.0F / 20.0F,
500, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F
);
int size = 24;
double radius = 4.0;
double sigma = 0.0728;
FluidDomain domain = sphereDroplet(size, config, radius, sigma);
double exactVolume = domain.totalVolume();
FluidDomain.StepStats stats = domain.step();
double analyticJump = 2.0 * sigma / radius;
double measuredJump = averageFluidPressure(domain);
double jumpError = Math.abs(measuredJump - analyticJump) / analyticJump;
System.out.printf("  static droplet sigma=%.4f analyticJump=%.5f measuredJump=%.5f error=%.3g "
+ "PCG=%d residual=%.3g maxSpeed=%.3g%n",
sigma, analyticJump, measuredJump, jumpError,
stats.pressureIterations(), stats.pressureResidual(), domain.maximumParticleSpeed());
check(jumpError <= 0.06, "static droplet missed the Laplace pressure jump: measured="
+ measuredJump + " analytic=" + analyticJump);
check(stats.pressureIterations() > 0 && stats.pressureIterations() <= 500,
"static droplet PCG iteration count escaped the bound");
check(stats.pressureResidual() <= 1.0E-3F, "static droplet PCG residual is too large");
check(Math.abs(domain.totalVolume() - exactVolume) <= 1.0E-6F,
"static droplet surface tension changed exact marker volume");
}

private static void validateNoSustainedParasiticFlow() {
FluidSolverConfig config = new FluidSolverConfig(
0.5F, 1_000.0F, 0.0F, 1.0F / 20.0F,
500, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F
);
int size = 24;
double radius = 4.0;
double sigma = 0.0728;
FluidDomain domain = sphereDroplet(size, config, radius, sigma);
double exactVolume = domain.totalVolume();
float previousMaximum = Float.POSITIVE_INFINITY;
for (int step = 0; step < 10; step++) {
FluidDomain.StepStats stats = domain.step();
check(Float.isFinite(stats.pressureResidual()), "static droplet pressure residual became non-finite");
check(Math.abs(domain.totalVolume() - exactVolume) <= 1.0E-6F,
"static droplet changed exact marker volume");
float maximum = domain.maximumParticleSpeed();
check(maximum <= 5.0E-4F, "static droplet developed sustained parasitic marker velocity: "
+ maximum + " at step " + step);
previousMaximum = maximum;
}
check(previousMaximum <= 5.0E-4F, "static droplet parasitic velocity did not remain bounded");
}


private static void validateContactAngleSpreading() {
	FluidSolverConfig config = new FluidSolverConfig(
			1.0F, 1_000.0F, 9.81F, 1.0F / 40.0F,
			300, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F
	);
DropletMetrics hydrophilic = runDroplet(config, Math.toRadians(30.0), 100.0, 1200);
DropletMetrics neutral = runDroplet(config, Math.toRadians(90.0), 100.0, 1200);
DropletMetrics hydrophobic = runDroplet(config, Math.toRadians(120.0), 100.0, 1200);
System.out.printf("  contact-angle wall moments theta30=%.6f theta90=%.6f theta120=%.6f "
		+ "maxSpeed=%.3g/%.3g/%.3g%n",
hydrophilic.varianceX(), neutral.varianceX(), hydrophobic.varianceX(),
hydrophilic.maximumSpeed(), neutral.maximumSpeed(), hydrophobic.maximumSpeed());
check(hydrophilic.varianceX() > neutral.varianceX() && neutral.varianceX() > hydrophobic.varianceX(),
"Young wall energy did not monotonically widen the continuous liquid footprint");
}

private static DropletMetrics runDroplet(FluidSolverConfig config, double contactAngleRadians, double sigma, int steps) {
FluidDomain domain = new FluidDomain(16, 6, 4, config, 16 * 6 * 4 * 8);
for (int z = 0; z < 4; z++) for (int x = 0; x < 16; x++) domain.setSolid(x, 0, z, true);
int particleCount = 8 * 2 * 2 * 2;
float[] positionVolume = new float[particleCount * 4];
float[] velocities = new float[particleCount * 3];
float totalVolume = 0.0F;
int particle = 0;
for (int z = 1; z <= 2; z++) for (int y = 1; y <= 1; y++) for (int x = 4; x <= 7; x++) {
for (int slot = 0; slot < 8; slot++) {
int base = particle * 4;
positionVolume[base] = (x + ((slot & 1) == 0 ? 0.25F : 0.75F)) * config.cellSize();
positionVolume[base + 1] = (y + ((slot & 2) == 0 ? 0.25F : 0.75F)) * config.cellSize();
positionVolume[base + 2] = (z + ((slot & 4) == 0 ? 0.25F : 0.75F)) * config.cellSize();
positionVolume[base + 3] = 0.125F;
totalVolume += 0.125F;
particle++;
}
}
domain.restoreParticleState(new FlipParticleState(16, 6, 4, config.cellSize(), particleCount,
totalVolume, positionVolume, velocities, domain.copyCollisionMicroMasks()));
FluidMaterialRegistry registry = new FluidMaterialRegistry(List.of(
new FluidMaterial("mmm:test", config.density(), "mmm:test", sigma, contactAngleRadians)));
for (int marker = 0; marker < domain.particleCount(); marker++) {
domain.setMarkerMaterial(marker, registry, 0);
}
for (int step = 0; step < steps; step++) domain.step();
FlipMaterialParticleState state = domain.exportMaterialParticleState();
float[] positionVolumeCopy = state.copyPositionVolume();
double weightedX = 0.0, weightedX2 = 0.0, volume = 0.0;
for (int offset = 0; offset < positionVolumeCopy.length; offset += 4) {
weightedX += positionVolumeCopy[offset] * positionVolumeCopy[offset + 3];
weightedX2 += positionVolumeCopy[offset] * positionVolumeCopy[offset] * positionVolumeCopy[offset + 3];
volume += positionVolumeCopy[offset + 3];
}
double meanX = weightedX / volume;
return new DropletMetrics(weightedX2 / volume - meanX * meanX, domain.maximumParticleSpeed());
}

private record DropletMetrics(double varianceX, float maximumSpeed) { }

	private static void validateLiquidPairPressureJump() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 0.0F, 1.0F / 20.0F,
				800, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F
		);
		int size = 20;
		double radius = 4.0;
		double sigma12 = 0.0728;
		FluidDomain domain = liquidPairDroplet(size, config, radius, sigma12);
		double exactVolume = domain.totalVolume();
		FluidDomain.StepStats stats = domain.step();
		double measuredJump = averagePressureInside(domain, size, radius) - averagePressureOutside(domain, size, radius);
		double expectedJump = sigma12 * SurfaceTensionCurvatureExperiment.interfaceMeanCurvature(
				binarySphereColour(size, radius, config.cellSize()), size, config.cellSize());
		double jumpError = Math.abs(measuredJump - expectedJump) / expectedJump;
		System.out.printf("  liquid-pair droplet sigma12=%.4f expectedJump=%.5f measuredJump=%.5f error=%.3g "
				+ "PCG=%d residual=%.3g maxSpeed=%.3g%n",
				sigma12, expectedJump, measuredJump, jumpError,
				stats.pressureIterations(), stats.pressureResidual(), domain.maximumParticleSpeed());
		check(jumpError <= 0.25, "liquid-pair droplet missed the Laplace pressure jump: measured="
				+ measuredJump + " expected=" + expectedJump);
		check(stats.pressureIterations() > 0 && stats.pressureIterations() <= 800,
				"liquid-pair droplet PCG iteration count escaped the bound");
		check(stats.pressureResidual() <= 1.0E-3F, "liquid-pair droplet PCG residual is too large");
		check(Math.abs(domain.totalVolume() - exactVolume) <= 1.0E-6F,
				"liquid-pair surface tension changed exact marker volume");
		for (int step = 0; step < 5; step++) {
			FluidDomain.StepStats noFlowStats = domain.step();
			check(Float.isFinite(noFlowStats.pressureResidual()), "liquid-pair droplet pressure residual became non-finite");
			check(Math.abs(domain.totalVolume() - exactVolume) <= 1.0E-6F,
					"liquid-pair surface tension changed exact marker volume");
			check(domain.maximumParticleSpeed() <= 5.0E-4F,
					"liquid-pair droplet developed sustained parasitic velocity: "
							+ domain.maximumParticleSpeed() + " at step " + step);
		}
	}

	private static void validateLiquidPairIdentity() {
		FluidSolverConfig config = new FluidSolverConfig(.5F, 1000, 0, .025F, 800, 1E-6F, .45F, .002F, .8F);
		FluidDomain domain = liquidPairDroplet(8, config, 1, .0728, true);
		domain.step();
		check(domain.maximumPressure() == 0, "a liquid pair acted on an interface with its first phase absent");
		check(domain.maximumParticleSpeed() == 0, "an unrelated liquid pair created liquid motion");
		FluidDomain separated = liquidPairDroplet(12, config, .5, .0728, true);
		for (int p = 0; p < 8; p++) separated.setMarkerMaterial(p, separated.materialRegistry, 1);
		separated.step();
		check(separated.maximumPressure() == 0, "a remote phase changed an unrelated liquid interface");
		check(separated.maximumParticleSpeed() == 0, "disjoint liquid-pair supports created motion");
		FluidDomain full = liquidPairDroplet(8, config, 1, .0728, true);
		FluidDomain diluted = liquidPairDroplet(8, config, 1, .0728, true);
		for (int p = 0; p < full.particleCount(); p++) if (full.particles.materialHandle(p) == 0) {
			full.setMarkerMaterial(p, full.materialRegistry, 1);
			if (p % 8 == 0) diluted.setMarkerMaterial(p, diluted.materialRegistry, 1);
		}
		full.step(); diluted.step();
		check(full.maximumPressure() > 0, "active pair failed to create a capillary pressure");
		check(Math.abs(diluted.maximumPressure() / full.maximumPressure() - .125) < .002,
				"pair force did not decay continuously with its first phase's local fraction");
	}

	private static FluidDomain liquidPairDroplet(int size, FluidSolverConfig config, double radius, double sigma12) {
		return liquidPairDroplet(size, config, radius, sigma12, false);
	}

	private static FluidDomain liquidPairDroplet(int size, FluidSolverConfig config, double radius, double sigma12,
			boolean absentFirstPhase) {
		FluidDomain domain = new FluidDomain(size, size, size, config, size * size * size * 8);
		int particleCount = size * size * size * 8;
		float[] positionVolume = new float[particleCount * 4];
		float[] velocities = new float[particleCount * 3];
		float totalVolume = 0.0F;
		int particle = 0;
		float h = config.cellSize();
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			for (int slot = 0; slot < 8; slot++) {
				int base = particle * 4;
				positionVolume[base] = (x + 0.5F) * h;
				positionVolume[base + 1] = (y + 0.5F) * h;
				positionVolume[base + 2] = (z + 0.5F) * h;
				positionVolume[base + 3] = 0.125F;
				totalVolume += 0.125F;
				particle++;
			}
		}
		domain.restoreParticleState(new FlipParticleState(size, size, size, h, particleCount,
				totalVolume, positionVolume, velocities, domain.copyCollisionMicroMasks()));
		FluidMaterial a = new FluidMaterial("mmm:a", config.density(), "mmm:a", 0.0, Math.PI / 2.0);
		FluidMaterial b = new FluidMaterial("mmm:b", config.density(), "mmm:b", 0.0, Math.PI / 2.0);
		FluidMaterial c = new FluidMaterial("mmm:c", config.density(), "mmm:c", 0, Math.PI / 2);
		FluidMaterialRegistry registry = absentFirstPhase
				? new FluidMaterialRegistry(List.of(a, b, c), List.of(new FluidMaterialRegistry.LiquidPairTension(1, 2, sigma12)))
				: new FluidMaterialRegistry(List.of(a, b), List.of(new FluidMaterialRegistry.LiquidPairTension(0, 1, sigma12)));
		double[] colour = SurfaceTensionCurvatureExperiment.sphereColour(size, radius, h);
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			int x = Math.min(size - 1, (int) Math.floor(positionVolume[marker * 4] / h));
			int y = Math.min(size - 1, (int) Math.floor(positionVolume[marker * 4 + 1] / h));
			int z = Math.min(size - 1, (int) Math.floor(positionVolume[marker * 4 + 2] / h));
			int materialHandle = colour[(z * size + y) * size + x] >= 0.5 ? (absentFirstPhase ? 2 : 1) : 0;
			domain.setMarkerMaterial(marker, registry, materialHandle);
		}
		return domain;
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
			if (dx * dx + dy * dy + dz * dz <= radius * radius) {
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
			if (dx * dx + dy * dy + dz * dz > radius * radius) {
				sum += domain.pressure(x, y, z);
				cells++;
			}
		}
		return cells == 0 ? 0.0 : sum / cells;
	}

private static void validateMergeBreakupConservation() {
FluidSolverConfig config = new FluidSolverConfig(
1.0F, 1_000.0F, 0.0F, 1.0F / 20.0F,
200, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F
);
FluidDomain domain = new FluidDomain(8, 8, 8, config, 8 * 8 * 8 * 8);
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
for (int particle = 0; particle < domain.particleCount(); particle++) {
domain.setMarkerMaterial(particle, registry, 0);
}
double exactVolume = domain.totalVolume();
double exactMass = domain.totalMass();
for (int step = 0; step < 20; step++) {
FluidDomain.StepStats stats = domain.step();
check(stats.relativeVolumeError() <= 1.0E-7F, "surface-tension merge/breakup changed exact volume");
check(stats.relativeMassError() <= 1.0E-12, "surface-tension merge/breakup changed exact mass");
check(domain.totalVolume() == exactVolume && domain.totalMass() == exactMass,
"surface-tension merge/breakup changed physical ledgers");
}
}

/** Full-cell spherical droplet; all marker cells are exactly full, so the capacity constraint stays inactive. */
private static FluidDomain sphereDroplet(int size, FluidSolverConfig config, double radius, double sigma) {
FluidDomain domain = new FluidDomain(size, size, size, config, size * size * size * 8);
double[] colour = SurfaceTensionCurvatureExperiment.sphereColour(size, radius, config.cellSize());
int particleCount = 0;
for (double fraction : colour) {
if (fraction >= 0.5) particleCount += 8;
}
float[] positionVolume = new float[particleCount * 4];
float[] velocities = new float[particleCount * 3];
float totalVolume = 0.0F;
int particle = 0;
float h = config.cellSize();
for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
double fraction = colour[(z * size + y) * size + x];
if (fraction < 0.5) continue;
for (int slot = 0; slot < 8; slot++) {
int base = particle * 4;
positionVolume[base] = (x + 0.5F) * h;
positionVolume[base + 1] = (y + 0.5F) * h;
positionVolume[base + 2] = (z + 0.5F) * h;
positionVolume[base + 3] = 0.125F;
totalVolume += 0.125F;
particle++;
}
}
domain.restoreParticleState(new FlipParticleState(size, size, size, h, particleCount,
totalVolume, positionVolume, velocities, domain.copyCollisionMicroMasks()));
FluidMaterialRegistry registry = new FluidMaterialRegistry(List.of(
new FluidMaterial("mmm:test", config.density(), "mmm:test", sigma, Math.PI / 2.0)));
for (int marker = 0; marker < domain.particleCount(); marker++) {
domain.setMarkerMaterial(marker, registry, 0);
}
return domain;
}

private static double averageFluidPressure(FluidDomain domain) {
double sum = 0.0;
int cells = 0;
for (int z = 0; z < domain.sizeZ(); z++) for (int y = 0; y < domain.sizeY(); y++) {
for (int x = 0; x < domain.sizeX(); x++) {
if (!domain.isFluid(domain.cellIndex(x, y, z))) continue;
sum += domain.pressure(x, y, z);
cells++;
}
}
return cells == 0 ? 0.0 : sum / cells;
}

private static FluidSolverConfig strictConfig() {
return new FluidSolverConfig(
1.0F, 1_000.0F, 0.0F, 1.0F / 40.0F,
300, 1.0E-7F, 0.40F, 2.0E-3F, 0.80F
);
}

private static void check(boolean condition, String message) {
if (!condition) throw new AssertionError(message);
}
}
