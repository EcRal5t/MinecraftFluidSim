package mmm.fluid;

import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import java.util.Arrays;
import java.util.List;

/** Registry-derived free-slip Rayleigh growth and complete transport ledgers on two meshes. */
public final class LiquidConvectionExperiment {
	private static final double DELTA_T = 10, T_REF = 293.15, DIFFUSION_RATE = 0.01;
	private record Measurement(double rayleigh, double prandtl, double growth, double viscous, double p2g, double g2p) { }
	private LiquidConvectionExperiment() { }

	public static void main(String[] args) {
		boolean verifyGrowth = Arrays.asList(args).contains("--verify-growth");
		double a = Math.PI / 2, q2 = Math.PI * Math.PI + a * a;
		double critical = q2 * q2 * q2 / (a * a);
		double[][] coarseError = new double[2][2];
		for (int grid : new int[] {8, 16}) for (double ratio : new double[] {0.8, 1.2}) {
			double predicted = -q2 + Math.sqrt(ratio * critical * a * a / q2);
			double firstGrowth = Double.NaN;
			for (double amplitude : new double[] {0.01, 0.02}) {
				Measurement actual = run(grid, ratio * critical, amplitude, predicted);
				System.out.printf("Convection N=%d Ra=%.6f Ra/Rac=%.2f Pr=%.6f A=%.4f K normalizedGrowth=%.6f predicted=%.6f%n",
						grid, actual.rayleigh(), actual.rayleigh() / critical, actual.prandtl(), amplitude, actual.growth(),
						-q2 + Math.sqrt(actual.rayleigh() * a * a / q2));
				System.out.printf("  mechanical energy: viscous=%g P2G=%g G2P=%g J%n", actual.viscous(), actual.p2g(), actual.g2p());
				if (verifyGrowth && actual.growth() * predicted <= 0) throw new AssertionError("Rayleigh mode has the wrong growth sign");
				if (verifyGrowth && Double.isFinite(firstGrowth) && Math.abs(actual.growth() - firstGrowth) > 0.03 * Math.abs(firstGrowth))
					throw new AssertionError("Convection amplitudes left the declared 3% linear-response domain");
				firstGrowth = actual.growth();
				double error = Math.abs(actual.growth() - predicted);
				int row = ratio < 1 ? 0 : 1, column = amplitude < 0.02 ? 0 : 1;
				// Spatial consistency plus the common first-order physical time integration.
				double bound = q2 * q2 / (6 * grid * grid) + q2 * q2 * 0.05 * DIFFUSION_RATE;
				if (verifyGrowth && error > bound) throw new AssertionError("Rayleigh growth exceeds the mesh/time error bound: " + error);
				if (grid == 8) coarseError[row][column] = error;
				else if (verifyGrowth && error >= 0.6 * coarseError[row][column]) throw new AssertionError("Rayleigh growth failed mesh refinement");
			}
		}
		System.out.println(verifyGrowth ? "Rayleigh growth validation passed" : "Rayleigh measurements complete; growth acceptance was not requested");
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
		var chemistry = mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY;
		domain.bindChemistry(chemistry);
		var thermal = new ThermalMaterialDefinition("test:thermal/convection", ThermalMaterialDefinition.CarrierKind.LIQUID,
				water.id(), T_REF, 0, 1000 * 4184 * kappa,
				List.of(new ThermalMaterialDefinition.CaloricSegment(250, 400, 4184)));
		var offset = new mmm.thermal.ThermalSpeciesOffset("test:offset/convection_water",
				mmm.chemistry.BuiltinChemicalDefinitions.H2O, thermal.id(),
				mmm.chemistry.ExactRational.of(-50));
		domain.bindThermal(new ThermalRegistry(List.of(thermal), List.of(offset), List.of(), chemistry.fingerprint(), ""), liquids, T_REF);
		double rho = domain.densityInCell(0, 0, 0);
		double nu = definitions.dynamicViscosityAt(water.id(), T_REF) / rho;
		double alpha = thermal.conductivityEuPerSecondMeterK() / (rho * thermal.specificHeatAt(T_REF));
		double actualRa = config.gravity() * expansion.volumetricCoefficientPerK() * DELTA_T * height * height * height / (nu * alpha);
		double[] boundary = new double[domain.cellCount * 6];
		Arrays.fill(boundary, Double.NaN);
		for (int z = 0; z < 2; z++) for (int y = 0; y < n; y++) for (int x = 0; x < 2 * n; x++) {
			int cell = domain.cellIndex(x, y, z);
			if (y == 0) boundary[6 * cell + 2] = T_REF + DELTA_T / 2;
			if (y == n - 1) boundary[6 * cell + 3] = T_REF - DELTA_T / 2;
		}
		domain.replaceThermalBoundaryTemperatures(boundary);
		// Initialize the actual carrier field, including its stationary affine conduction profile.
		var context = domain.thermalMarkerContext();
		var energyBefore = domain.exactInternalEnergy();
		double[] energy = domain.exportThermalParticleState().copyInternalEnergy();
		for (int p = 0; p < domain.particleCount(); p++) {
			double x = domain.particles.positionX(p) / height, y = domain.particles.positionY(p) / height;
			double temperature = T_REF + DELTA_T * (0.5 - y) + amplitude * Math.cos(Math.PI * x / 2) * Math.sin(Math.PI * y);
			energy[p] = domain.thermalState.liquid().modeledEnergy(p, context.mass(p), context.mixture(p), temperature, context);
		}
		domain.thermalState.restoreLiquidEnergies(energy);
		domain.thermalState.restoreAuditLedger(domain.thermalAudit().withHeatExchange(domain.exactInternalEnergy().subtract(energyBefore)));
		double q2 = 1.25 * Math.PI * Math.PI;
		double velocity = kappa / height * (growth + q2) * amplitude / DELTA_T;
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			double x = domain.particles.positionX(marker) / height, y = domain.particles.positionY(marker) / height;
			domain.particles.setVelocityX(marker, (float) (-2 * velocity * Math.sin(Math.PI * x / 2) * Math.cos(Math.PI * y)));
			domain.particles.setVelocityY(marker, (float) (velocity * Math.cos(Math.PI * x / 2) * Math.sin(Math.PI * y)));
		}
		domain.gridDirty = true;
		double volumeBefore = domain.totalVolume(), massBefore = domain.totalMass();
		long[] speciesBefore = domain.chemicalParcels().speciesTotals(domain.particleCount());
		double first = Double.NaN, last = Double.NaN, viscous = 0, p2g = 0, g2p = 0;
		for (int step = 0; step < 100; step++) {
			var stepEnergyBefore = domain.exactInternalEnergy();
			var auditBefore = domain.thermalAudit();
			var stats = domain.stepWithMechanicalAudit(false, 0);
			viscous += stats.viscosity().dissipatedJoules();
			p2g += stats.mechanicalAudit().particleToGridTransferJoules();
			g2p += stats.mechanicalAudit().gridToParticleTransferJoules();
			if (domain.totalVolume() != volumeBefore || Math.abs(domain.totalMass() - massBefore) > 1e-8
					|| !Arrays.equals(speciesBefore, domain.chemicalParcels().speciesTotals(domain.particleCount()))
					|| !domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
					|| Math.abs(stats.mechanicalAudit().closureResidualJoules()) > 1e-6)
				throw new AssertionError("Convection experiment lost volume, material mass, species or energy closure");
			var audit = domain.thermalAudit();
			var heat = domain.lastThermalTransportStats().reservoirExchange();
			if (!audit.externalSource().subtract(auditBefore.externalSource())
					.subtract(audit.externalSink().subtract(auditBefore.externalSink())).equals(heat)
					|| !domain.exactInternalEnergy().subtract(stepEnergyBefore).equals(heat.add(
							audit.roundoffResidual().subtract(auditBefore.roundoffResidual())))
					|| !audit.mechanicalWork().equals(auditBefore.mechanicalWork()))
				throw new AssertionError("Convection wall heat, roundoff and carrier U do not close separately");
			last = temperatureMode(domain, n);
			if (step == 19) first = last;
		}
		return new Measurement(actualRa, nu / alpha,
				Math.log(Math.abs(last / first)) / (80 * config.timeStep() * alpha / (height * height)), viscous, p2g, g2p);
	}

	private static double temperatureMode(FluidDomain domain, int n) {
		double sum = 0, norm = 0;
		double height = domain.config.cellSize() * n;
		for (int p = 0; p < domain.particleCount(); p++) {
			double x = domain.particles.positionX(p) / height, y = domain.particles.positionY(p) / height;
			double mode = Math.cos(Math.PI * x / 2) * Math.sin(Math.PI * y);
			double temperature = domain.thermalState.temperature(p, domain.thermalMarkerContext());
			double volume = domain.particles.volume(p);
			sum += volume * mode * (temperature - T_REF - DELTA_T * (0.5 - y));
			norm += volume * mode * mode;
		}
		return sum / norm;
	}
}
