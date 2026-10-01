package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import java.util.Arrays;
import java.util.List;

/** Quantifies the production transport response; the Ra growth criterion remains an open acceptance item. */
public final class LiquidConvectionExperiment {
	private static final double DELTA_T = 10, T_REF = 293.15, DIFFUSION_RATE = 0.01;
	private record Measurement(double rayleigh, double prandtl, double growth, double viscous, double p2g, double g2p) { }
	private LiquidConvectionExperiment() { }

	public static void main(String[] args) {
		double a = Math.PI / 2, q2 = Math.PI * Math.PI + a * a;
		double critical = q2 * q2 * q2 / (a * a);
		for (int grid : new int[] {8, 16}) for (double ratio : new double[] {0.8, 1.2}) {
			double predicted = -q2 + Math.sqrt(ratio * critical * a * a / q2);
			for (double amplitude : new double[] {0.01, 0.02}) {
				Measurement actual = run(grid, ratio * critical, amplitude, predicted);
				System.out.printf("Convection N=%d Ra=%.6f Ra/Rac=%.2f Pr=%.6f A=%.4f K normalizedGrowth=%.6f predicted=%.6f%n",
						grid, actual.rayleigh(), actual.rayleigh() / critical, actual.prandtl(), amplitude, actual.growth(),
						-q2 + Math.sqrt(actual.rayleigh() * a * a / q2));
				System.out.printf("  mechanical energy: viscous=%g P2G=%g G2P=%g J%n", actual.viscous(), actual.p2g(), actual.g2p());
			}
		}
	}

	private static Measurement run(int n, double ra, double amplitude, double growth) {
		double height = 0.5 * n, kappa = DIFFUSION_RATE * height * height;
		var water = BuiltinFluidMaterials.WATER;
		var baseline = BuiltinFluidMaterials.createRegistry();
		var expansion = baseline.entry(water.id()).thermalExpansion();
		double gravity = ra * kappa * kappa / (expansion.volumetricCoefficientPerK() * DELTA_T * height * height * height);
		var config = new FluidSolverConfig(0.5F, 1000, (float) gravity, 0.05F, 1200, 1e-7F, 0.4F, 0.002F, 0);
		var domain = new FluidDomain(2 * n, n, 2, config, 2 * n * n * 2 * 16);
		for (int z = 0; z < 2; z++) for (int y = 0; y < n; y++) for (int x = 0; x < 2 * n; x++) domain.setVolume(x, y, z, 1);
		var liquids = new FluidMaterialRegistry(List.of(water));
		domain.setMarkerMaterial(0, liquids, 0);
		var definitions = new MaterialRegistry(List.of(new MaterialRegistry.MaterialEntry(water,
				List.of(new MaterialRegistry.ViscositySegment(250, 400, 1000 * kappa, 1000 * kappa)), expansion)), List.of());
		domain.bindMaterialDefinitions(definitions);
		var thermal = new ThermalMaterialDefinition("test:thermal/convection", ThermalMaterialDefinition.CarrierKind.LIQUID,
				water.id(), T_REF, 0, 1000 * 4184 * kappa,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250, 400, 4184)));
		domain.bindThermal(new ThermalRegistry(List.of(thermal)), liquids, T_REF);
		double rho = domain.densityInCell(0, 0, 0);
		double nu = definitions.dynamicViscosityAt(water.id(), T_REF) / rho;
		double alpha = thermal.conductivityEuPerSecondMeterK() / (rho * thermal.specificHeatAt(T_REF));
		double actualRa = config.gravity() * expansion.volumetricCoefficientPerK() * DELTA_T * height * height * height / (nu * alpha);
		double[] boundary = new double[domain.cellCount * 6];
		Arrays.fill(boundary, Double.NaN);
		for (int z = 0; z < 2; z++) for (int y = 0; y < n; y++) for (int x = 0; x < 2 * n; x++) {
			double xx = (x + 0.5) / n, yy = (y + 0.5) / n;
			domain.setThermalTemperatureInCell(x, y, z, T_REF + DELTA_T * (0.5 - yy)
					+ amplitude * Math.cos(Math.PI * xx / 2) * Math.sin(Math.PI * yy));
			int cell = domain.cellIndex(x, y, z);
			if (y == 0) boundary[6 * cell + 2] = T_REF + DELTA_T / 2;
			if (y == n - 1) boundary[6 * cell + 3] = T_REF - DELTA_T / 2;
		}
		domain.replaceThermalBoundaryTemperatures(boundary);
		double q2 = 1.25 * Math.PI * Math.PI;
		double velocity = kappa / height * (growth + q2) * amplitude / DELTA_T;
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			double x = domain.particles.positionX(marker) / height, y = domain.particles.positionY(marker) / height;
			domain.particles.setVelocityX(marker, (float) (-2 * velocity * Math.sin(Math.PI * x / 2) * Math.cos(Math.PI * y)));
			domain.particles.setVelocityY(marker, (float) (velocity * Math.cos(Math.PI * x / 2) * Math.sin(Math.PI * y)));
		}
		domain.gridDirty = true;
		double volumeBefore = domain.totalVolume(), massBefore = domain.totalMass();
		double first = Double.NaN, last = Double.NaN, viscous = 0, p2g = 0, g2p = 0;
		for (int step = 0; step < 100; step++) {
			var stats = domain.stepWithMechanicalAudit(false, 0);
			viscous += stats.viscosity().dissipatedJoules();
			p2g += stats.mechanicalAudit().particleToGridTransferJoules();
			g2p += stats.mechanicalAudit().gridToParticleTransferJoules();
			if (domain.totalVolume() != volumeBefore || Math.abs(domain.totalMass() - massBefore) > 1e-8
					|| !domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
					|| Math.abs(stats.mechanicalAudit().closureResidualJoules()) > 1e-6)
				throw new AssertionError("Convection experiment lost volume, mass or energy closure");
			last = temperatureMode(domain, n);
			if (step == 19) first = last;
		}
		return new Measurement(actualRa, nu / alpha,
				Math.log(Math.abs(last / first)) / (80 * config.timeStep() * alpha / (height * height)), viscous, p2g, g2p);
	}

	private static double temperatureMode(FluidDomain domain, int n) {
		domain.thermalState.preparePhaseScan(domain.thermalMarkerContext());
		double sum = 0, norm = 0;
		for (int z = 0; z < 2; z++) for (int y = 0; y < n; y++) for (int x = 0; x < 2 * n; x++) {
			double yy = (y + 0.5) / n;
			double mode = Math.cos(Math.PI * (x + 0.5) / (2 * n)) * Math.sin(Math.PI * yy);
			double temperature = domain.thermalState.phaseScanTemperatureInCell(domain.cellIndex(x, y, z));
			sum += mode * (temperature - T_REF - DELTA_T * (0.5 - yy));
			norm += mode * mode;
		}
		return sum / norm;
	}
}
