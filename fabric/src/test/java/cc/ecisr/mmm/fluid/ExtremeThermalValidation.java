package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.simulation.FullCapabilityTestFixture;
import cc.ecisr.mmm.thermal.GasThermalModel;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.units.SignedDyadic;
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
					240, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
			FluidDomain domain = new FluidDomain(4, 4, 4, config, 4096);
			domain.sealDomainBoundary();
			domain.setVolume(1, 1, 1, 0.5F);
			for (int marker = 0; marker < domain.particleCount(); marker++) {
				domain.setMarkerMaterial(marker, registries.liquids(), 0);
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
			domain.setThermalTemperatureInCell(1, 1, 1, temperature);
			var snapshot = domain.exportThermalParticleState();
			double gasEnergy = new GasThermalModel(registries.thermal(), registries.gas())
					.internalEnergyAtTemperature(domain.gasBoundary().inventory(1), temperature);
			var delta = SignedDyadic.of(gasEnergy)
					.subtract(SignedDyadic.sum(snapshot.copyGasNodeEnergy()));
			FluidThermalBinding.restore(domain.thermalState, new FlipThermalParticleState(4, 4, 4, 0.5F, domain.particleCount(),
					registries.thermal().fingerprint(), snapshot.copyInternalEnergy(), new double[0], new double[0],
					List.of(), new double[] {gasEnergy}, snapshot.audit().withHeatExchange(delta)), domain.thermalMarkerContext(), domain.gasState.network);
			double actualPressure = GasRegistry.STANDARD_PRESSURE_PA + domain.thermalState.gasGaugePressure(1, domain.gasState.network);
			ThermalValidationSupport.require(actualPressure > 0.5 * pressure && actualPressure < 1.5 * pressure,
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
