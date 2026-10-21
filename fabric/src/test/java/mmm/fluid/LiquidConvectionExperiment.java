package mmm.fluid;

import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import java.util.Arrays;
import java.util.List;

/** Macroscopic free-slip convection growth and complete transport ledgers on two meshes. */
public final class LiquidConvectionExperiment {
	private static final double DELTA_T = 10, T_REF = 293.15, DIFFUSION_RATE = 0.01;
	private record Measurement(double rayleigh,double prandtl,double growth,double viscous) { }
	private LiquidConvectionExperiment() { }

	public static void main(String[] args) {
		boolean verifyGrowth = Arrays.asList(args).contains("--verify-growth");
		double a = Math.PI / 2, q2 = Math.PI * Math.PI + a * a;
		double critical = q2 * q2 * q2 / (a * a);
		for (int grid : new int[] {8, 16}) for (double ratio : new double[] {0.8, 1.2}) {
			double predicted = -q2 + Math.sqrt(ratio * critical * a * a / q2);
			double amplitude = 0.02;
			Measurement actual = run(grid, ratio * critical, amplitude, predicted);
			System.out.printf("Convection N=%d Ra=%.6f Ra/Rac=%.2f Pr=%.6f A=%.4f K normalizedGrowth=%.6f predicted=%.6f%n",
					grid, actual.rayleigh(), actual.rayleigh() / critical, actual.prandtl(), amplitude, actual.growth(),
					-q2 + Math.sqrt(actual.rayleigh() * a * a / q2));
			System.out.printf("  mechanical energy: viscous=%g J%n",actual.viscous());
			if (verifyGrowth && (!Double.isFinite(actual.growth()) || actual.growth() * predicted <= 0))
				throw new AssertionError("Rayleigh mode has no finite growth with the expected direction");
			// Compare dimensionless growth on each mesh against its spatial truncation
			// and common first-order physical time error; the mode remains macroscopic.
			double temporalError = q2 * q2 * 0.05 * DIFFUSION_RATE;
			double bound = q2 * q2 / (6 * grid * grid) + temporalError;
			double error = Math.abs(actual.growth() - predicted);
			if (verifyGrowth && error > bound) throw new AssertionError("Rayleigh growth exceeds the mesh/time error bound: " + error);
		}
		System.out.println(verifyGrowth ? "Macroscopic convection growth validation passed" : "Convection growth measurements complete");
	}

	private static Measurement run(int n, double ra, double amplitude, double growth) {
		double height = 0.5 * n, kappa = DIFFUSION_RATE * height * height;
		var water = BuiltinFluidMaterials.WATER;
		var baseline = BuiltinFluidMaterials.createRegistry();
		var expansion = baseline.entry(water.id()).thermalExpansion();
		double gravity = ra * kappa * kappa / (expansion.volumetricCoefficientPerK() * DELTA_T * height * height * height);
		var config = new FluidSolverConfig(0.5F, 1000, (float) gravity, 0.05F, 1200, 1e-7F, 0.002F, 0);
		var domain = new FluidDomain(2 * n, n, 2, config, 2 * n * n * 2 * 16);
		var liquids = new FluidMaterialRegistry(List.of(water));domain.bindMaterialRegistry(liquids);
		float[] filled=new float[domain.cellCount];Arrays.fill(filled,1);domain.setVolumes(filled);
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
		double alpha = thermal.conductivityWPerMeterK() / (rho * thermal.specificHeatAt(T_REF));
		double actualRa = config.gravity() * expansion.volumetricCoefficientPerK() * DELTA_T * height * height * height / (nu * alpha);
		double[] boundary = new double[domain.cellCount * 6];
		Arrays.fill(boundary, Double.NaN);
		for (int z = 0; z < 2; z++) for (int y = 0; y < n; y++) for (int x = 0; x < 2 * n; x++) {
			int cell = domain.domainFlowFields.cellIndex(x, y, z);
			if (y == 0) boundary[6 * cell + 2] = T_REF + DELTA_T / 2;
			if (y == n - 1) boundary[6 * cell + 3] = T_REF - DELTA_T / 2;
		}
		domain.replaceThermalBoundaryTemperatures(boundary);
		// Initialize the actual carrier field, including its stationary affine conduction profile.
		var context = domain.domainCapabilities.thermalMarkerContext();
		var energyBefore = domain.exactInternalEnergy();
		double[] energy = domain.exportThermalParticleState().copyInternalEnergy();
		for (int p = 0; p < domain.particleCount(); p++) {
			int cell=domain.liquidCells.physicalStockAt(p).key().cell();
			double x=(cell%domain.sizeX+.5)*domain.config.cellSize()/height,y=(cell/domain.sizeX%domain.sizeY+.5)*domain.config.cellSize()/height;
			double temperature = T_REF + DELTA_T * (0.5 - y) + amplitude * Math.cos(Math.PI * x / 2) * Math.sin(Math.PI * y);
			energy[p] = domain.thermalState.liquid().modeledEnergy(p, context.mass(p), context.mixture(p), temperature, context);
		}
		domain.thermalState.restoreLiquidEnergies(energy);
		domain.thermalState.restoreAuditLedger(domain.thermalAudit().withHeatExchange(domain.exactInternalEnergy().subtract(energyBefore)));
		double q2 = 1.25 * Math.PI * Math.PI;
		double velocity = kappa / height * (growth + q2) * amplitude / DELTA_T;
		domain.domainFlowFields.ensureGrid();double h=domain.config.cellSize();
		for(int z=0;z<2;z++)for(int y=0;y<n;y++)for(int x=0;x<=2*n;x++)
			domain.mac.velocityX[domain.domainFlowFields.xFaceIndex(x,y,z)]=(float)(-2*velocity*Math.sin(Math.PI*x*h/(2*height))*Math.cos(Math.PI*(y+.5)*h/height));
		for(int z=0;z<2;z++)for(int y=0;y<=n;y++)for(int x=0;x<2*n;x++)
			domain.mac.velocityY[domain.domainFlowFields.yFaceIndex(x,y,z)]=(float)(velocity*Math.cos(Math.PI*(x+.5)*h/(2*height))*Math.sin(Math.PI*y*h/height));
		domain.bodyForceStage.enforceSolidFaceVelocities();domain.cellMacStage.publishMomentum();
		var volumeBefore = domain.liquidCells.exactVolume();
		long massBefore = domain.liquidCells.massUnits();
		long[] speciesBefore = LiquidStratificationValidation.speciesTotals(domain);
		double first=Double.NaN,last=Double.NaN,viscous=0;
		for (int step = 0; step < 100; step++) {
			var stepEnergyBefore = domain.exactInternalEnergy();
			var auditBefore = domain.thermalAudit();
			var physicalBefore=LiquidStratificationValidation.Energy.capture(domain);
			var candidate = advance(domain);
			domain = candidate.claimPreparedCpuDomain();
			var stats = candidate.stats();
			viscous += stats.viscosity().dissipatedJoules();
			if (!domain.liquidCells.exactVolume().equals(volumeBefore) || domain.liquidCells.massUnits() != massBefore
					|| !Arrays.equals(speciesBefore, LiquidStratificationValidation.speciesTotals(domain))
					|| !domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
					|| Math.abs(stats.mechanicalAudit().closureResidualJoules()) > 1e-6)
				throw new AssertionError("Convection experiment lost volume, material mass, species or energy closure");
			var audit = domain.thermalAudit();
			var transport=domain.lastThermalTransportStats();
			var heat = transport.reservoirExchange();
			var conversionHeat = audit.mechanicalWork().subtract(auditBefore.mechanicalWork());
			var wallAudit=auditBefore.withHeatExchange(heat);
			if (!audit.externalSource().equals(wallAudit.externalSource())
						|| !audit.externalSink().equals(wallAudit.externalSink())
						|| !audit.finiteDeviceExchange().equals(auditBefore.finiteDeviceExchange())
						|| !Arrays.equals(boundary,domain.thermalBoundaryTemperatureK)
					|| !domain.exactInternalEnergy().subtract(stepEnergyBefore).equals(heat.add(conversionHeat).add(
								audit.roundoffResidual().subtract(auditBefore.roundoffResidual()))))
				throw new AssertionError("Convection wall heat, roundoff and carrier U do not close separately");
			var physicalAfter=LiquidStratificationValidation.Energy.capture(domain);
			double mechanicalAllowance=physicalBefore.arithmeticAllowance()+physicalAfter.arithmeticAllowance()
					+stats.substeps()*(physicalBefore.velocitySamplingAllowance()+physicalAfter.velocitySamplingAllowance());
			double storageAllowance=(stats.substeps()+transport.substeps())
					*(physicalBefore.storageAllowance()+physicalAfter.storageAllowance());
			// The wall supplies the only external energy. Conservative force work can draw U;
			// measure raw K + PE + U against the actual heat without subtracting that work column.
			double residual=physicalAfter.total().subtract(physicalBefore.total()).subtract(heat).toDouble();
			if(Math.abs(residual)>mechanicalAllowance+storageAllowance
					|| Math.abs(audit.roundoffResidual().subtract(auditBefore.roundoffResidual()).toDouble())>storageAllowance)
				throw new AssertionError("Convection changed independently measured K + PE + U beyond wall heat and IEEE rounding: residual="
						+residual+", allowance="+(mechanicalAllowance+storageAllowance)+", step="+step+", N="+n);
			last = temperatureMode(domain, n);
			if (step == 19) first = last;
		}
		return new Measurement(actualRa, nu / alpha,
				Math.log(Math.abs(last / first)) / (80 * config.timeStep() * alpha / (height * height)),viscous);
	}

	private static FluidStepCandidate advance(FluidDomain domain) {
		return new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0, domain.captureAuthoritativeState(), List.of(), false, 0, true));
	}

	private static double temperatureMode(FluidDomain domain, int n) {
		double sum = 0, norm = 0;
		double height = domain.config().cellSize() * n;
		for (int p = 0; p < domain.particleCount(); p++) {
			int cell=domain.liquidCells.physicalStockAt(p).key().cell();
			double x=(cell%domain.sizeX+.5)*domain.config.cellSize()/height,y=(cell/domain.sizeX%domain.sizeY+.5)*domain.config.cellSize()/height;
			double mode = Math.cos(Math.PI * x / 2) * Math.sin(Math.PI * y);
			double temperature = domain.thermalState.temperature(p, domain.domainCapabilities.thermalMarkerContext());
			double volume = domain.liquidCells.physicalStockAt(p).volumeFraction();
			sum += volume * mode * (temperature - T_REF - DELTA_T * (0.5 - y));
			norm += volume * mode * mode;
		}
		return sum / norm;
	}
}
