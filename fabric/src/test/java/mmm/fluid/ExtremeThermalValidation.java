package mmm.fluid;

import mmm.fluid.air.GasRegistry;
import mmm.simulation.FullCapabilityTestFixture;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalLimits;
import mmm.units.SignedDyadic;
import java.util.List;

/** Production thermodynamic definitions across extreme temperature and pressure combinations. */
public final class ExtremeThermalValidation {
	private ExtremeThermalValidation() { }

	public static void main(String[] args) throws Exception {
		var registries = FullCapabilityTestFixture.load();
		for (double[] state : new double[][] {{1.0E7, 10.0}, {10.0, 1.0E12}, {150.0, 1.0E12}, {1.0E7, 1.0E12}}) {
			double temperature = state[0], pressure = state[1];
			// Microgravity isolates the extreme caloric/EoS/phase domain from the local Boussinesq approximation.
			var config = new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F,
					240, 1.0E-4F, 2.0E-3F);
			FluidDomain domain = new FluidDomain(4, 4, 4, config);
			domain.sealDomainBoundary();
			// A connected four-cell chamber retains a liquid pressure field while
			// keeping the cold high-pressure gas mass inside the integer inventory range.
			if (pressure >= 1.0E12) {
				for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
					domain.setSolid(x, y, z, y != 1 || x < 1 || x > 2 || z < 1 || z > 2);
				}
			}
			int side=pressure>=1.0E12?2:1;
			for(int z=1;z<=side;z++)for(int x=1;x<=side;x++)domain.setVolume(x,1,z,0.5F);
			for (int marker = 0; marker < domain.particleCount(); marker++) {
				domain.domainCollision.setMarkerMaterial(marker, registries.liquids(), 0);
			}
			domain.bindGasRegistry(registries.gas());
			domain.bootstrapGasState("");
			double capacity = domain.gasBoundary().availableVolume(1);
			domain.gasState.network.setStandardVolume(1, pressure * capacity
					* GasRegistry.STANDARD_TEMPERATURE_K / (GasRegistry.STANDARD_PRESSURE_PA * temperature));
			domain.bindChemistry(registries.chemical());
			domain.bindGranular(registries.granular(), registries.liquids(), registries.materials());
			domain.setPrecipitationEnabled(true);
			domain.bindThermal(registries.thermal(), registries.liquids(), 293.15, 8);
			for(int z=1;z<=side;z++)for(int x=1;x<=side;x++)domain.setThermalTemperatureInCell(x,1,z,temperature);
			var snapshot = domain.exportThermalParticleState();
			double gasEnergy = new GasThermalModel(registries.thermal(), registries.gas())
					.internalEnergyAtTemperature(domain.gasBoundary().inventory(1), temperature);
			var delta = SignedDyadic.of(gasEnergy)
					.subtract(SignedDyadic.sum(snapshot.copyGasNodeEnergy()));
			FluidThermalBinding.restore(domain.thermalState, new FlipThermalParticleState(4, 4, 4, config.cellSize(), domain.particleCount(),
					registries.thermal().fingerprint(), snapshot.insertionTemperatureK(), snapshot.copyInternalEnergy(), new double[0], new double[0],
					List.of(), new double[] {gasEnergy}, snapshot.audit().withHeatExchange(delta)), domain.domainCapabilities.thermalMarkerContext(), domain.gasState.network);
			double actualPressure = GasRegistry.STANDARD_PRESSURE_PA + domain.thermalState.gasGaugePressure(1, domain.gasState.network);
			ThermalValidationSupport.require(Math.abs(actualPressure-pressure)<=0.01*pressure,
					"extreme fixture did not establish the requested pressure");

			var backend = new CpuFluidSolverBackend();
			long phaseOperations = 0, frozenCells = 0, boiledCells = 0;
			for (int step = 0; step < 12; step++) {
				var candidate = backend.evaluate(new FluidStepRequest(step,
						domain.captureAuthoritativeState(), List.of(), true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));
				phaseOperations += candidate.stats().condensedPhase().operations() + candidate.stats().gasPhase().operations();
				frozenCells += candidate.stats().condensedPhase().frozenCells();
				boiledCells += candidate.stats().gasPhase().boiledCells();
				domain = candidate.claimPreparedCpuDomain();
				ThermalValidationSupport.require(domain.gasBoundary().auditClosed()
						&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
						"extreme step failed gas or thermal conservation");
				domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			}
			// At 10 K, endpoint caloric continuation admits no energy-feasible freezing batch.
			// Keep that case for conservative continuation; 150 K separately exercises freezing.
			ThermalValidationSupport.require((temperature != 150.0 || frozenCells > 0)
					&& (pressure != 10.0 || boiledCells > 0),
					"extreme fixture did not exercise the expected phase transition");
			System.out.println("Extreme thermal PASS: T=" + temperature + " K, initial p="
					+ actualPressure + " Pa, phase-enabled steps=12, phase operations=" + phaseOperations);
		}
	}
}
