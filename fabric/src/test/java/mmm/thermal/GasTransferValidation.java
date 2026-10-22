package mmm.thermal;

import mmm.chemistry.ExactRational;
import mmm.fluid.air.*;
import mmm.units.SignedDyadic;
import java.util.List;

/** First-order simultaneous cavity flow: macroscopic pressure response and exact shared inventories. */
public final class GasTransferValidation {
	private GasTransferValidation() { }
	public static void main(String[] args) {
		if (args.length == 1 && args[0].equals("--eos")) {
			pressureVolumeIsentropes(); System.out.println("Gas pressure-volume EOS validation passed"); return;
		}
		if (args.length != 0) throw new IllegalArgumentException("Expected no arguments or --eos");
		validate(); System.out.println("Global gas flow validation passed");
	}
	public static void validate() {
		pressureVolumeIsentropes();
		isothermalBackwardEulerResponse();
		sharedDonorIsIndependentOfPortOrder();
		pressurePropagatesAcrossAnInitiallyBalancedPort();
		thermalEnthalpyAndPositiveTemperature();
		unequalTemperatureBlowdown();
		ambientAndLiquidSeal();
	}

	/** Independent ideal-gas entropy integrals, including a heat-capacity transition. */
	private static void pressureVolumeIsentropes() {
		var gas = new GasRegistry(List.of(new GasComponentDefinition("test:gas", 1, 1, List.of(), List.of())),
				List.of(new GasRegistry.AmbientFraction("test:gas", ExactRational.ONE)));
		var inventory = gas.createAmbient(1_000_000_000L);
		double volume0 = .75, temperature0 = 300;
		double r = GasRegistry.STANDARD_PRESSURE_PA / GasRegistry.STANDARD_TEMPERATURE_K;
		double pressure0 = r * temperature0 / volume0, mass = inventory.totalMass();
		var constant = caloricModel(gas, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 2000, 718)));
		double energy0 = constant.internalEnergyAtTemperature(inventory, temperature0);
		double gamma = 1 + r / (mass * 718);
		var curve = constant.pressureVolumeCurve(inventory, volume0, energy0);
		for (double ratio : new double[]{.2, .5, 1, 2, 8}) {
			double pressure = pressure0 * ratio;
			double expectedVolume = volume0 * Math.pow(1 / ratio, 1 / gamma);
			var result = curve.atPressure(pressure);
			close(result.volume(), expectedVolume, "Constant-Cv curve differs from p V^gamma");
			close(result.compliance(), expectedVolume / (gamma * pressure), "Constant-Cv pressure compliance differs from the isentrope");
		}

		var variable = caloricModel(gas, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 400, 718),
				new ThermalMaterialDefinition.CaloricSegment(400, 2000, 900)));
		energy0 = variable.internalEnergyAtTemperature(inventory, temperature0);
		curve = variable.pressureVolumeCurve(inventory, volume0, energy0);
		for (double temperature : new double[]{180, 350, 500, 900}) {
			double entropy = mass * (temperature <= 400 ? 718 * Math.log(temperature / temperature0)
					: 718 * Math.log(400 / temperature0) + 900 * Math.log(temperature / 400));
			double expectedVolume = volume0 * Math.exp(-entropy / r);
			double pressure = r * temperature / expectedVolume;
			var result = curve.atPressure(pressure);
			close(result.volume(), expectedVolume, "Variable-Cv curve differs from the integrated entropy relation");
			double energy = variable.internalEnergyAtTemperature(inventory, temperature);
			var state = variable.hydrodynamicState(inventory, result.volume(), energy);
			close(state.temperatureK(), temperature, "Pressure-volume endpoint disagrees with caloric temperature");
			close(state.absolutePressurePa(), pressure, "Pressure-volume endpoint disagrees with the inventory/U EOS");
			close(result.compliance(), expectedVolume / ((1 + r / (mass * (temperature < 400 ? 718 : 900))) * pressure),
					"Variable-Cv pressure compliance disagrees with endpoint heat capacity");
			var compressed = variable.compressAdiabatically(inventory, energy0, volume0, result.volume());
			close(compressed.finalTemperatureK(), temperature, "Pressure and volume isentropes disagree on temperature");
			close(compressed.finalInternalEnergy(), energy, "Pressure and volume isentropes disagree on energy");
			close(variable.pressureVolumeCurve(inventory, result.volume(), energy).atPressure(pressure0).volume(), volume0,
					"Reversed pressure isentrope did not recover the original volume");
			close(variable.compressAdiabatically(inventory, energy, result.volume(), volume0).finalInternalEnergy(), energy0,
					"Reversed isentropic work did not recover the original energy");
		}
	}
	private static GasThermalModel caloricModel(GasRegistry gas, List<ThermalMaterialDefinition.CaloricSegment> segments) {
		return new GasThermalModel(new ThermalRegistry(List.of(new ThermalMaterialDefinition("test:thermal/gas",
				ThermalMaterialDefinition.CarrierKind.GAS, "test:gas", 300, 10_000, 0, segments))), gas);
	}
	private static void close(double actual, double expected, String message) {
		require(Double.isFinite(actual) && Math.abs(actual - expected) <= 1e-9 * Math.max(Math.abs(expected), 1e-12),
				message + ": " + actual + " versus " + expected);
	}

	private static void isothermalBackwardEulerResponse() {
		AirNetwork network = new AirNetwork();
		int a = network.addNode(1, 1.1), b = network.addNode(1, .9);
		network.addOrMergeEdge(a, b, .001, .65);
		double difference = network.gaugePressure(a) - network.gaugePressure(b), dt = .01;
		double g = .65 * .001 * Math.sqrt(2 / network.density(a)) / Math.sqrt(difference) * 1.1;
		double expected = difference / (1 + 2 * dt * g * AirNetwork.REFERENCE_PRESSURE);
		long stock = network.sealedInventoryNanos(); network.stepEdges(dt);
		double actual = network.gaugePressure(a) - network.gaugePressure(b);
		require(Math.abs(actual - expected) <= 4 * AirNetwork.REFERENCE_PRESSURE * 1e-9,
				"Frozen orifice/storage step differs from its backward-Euler pressure response");
		require(network.sealedInventoryNanos() == stock && network.auditClosed(), "Isothermal flow changed exact inventory");
	}

	private static void sharedDonorIsIndependentOfPortOrder() {
		AirNetwork first = star(false), reverse = star(true);
		first.stepEdges(.05); reverse.stepEdges(.05);
		for (int id : first.nodeIds()) require(first.inventory(id).equals(reverse.inventory(id)), "Port order changed simultaneous donor allocation");
		require(first.inventory(2).equals(first.inventory(3)) && first.standardVolumeNanos(1) > 0
				&& first.auditClosed() && reverse.auditClosed(), "Shared donor lost symmetry, positivity or inventory");
	}
	private static AirNetwork star(boolean reverse) {
		AirNetwork network = new AirNetwork(); network.addNode(1, 1.5); network.addNode(1, .5); network.addNode(1, .5);
		network.addOrMergeEdge(1, reverse ? 3 : 2, .01, .65); network.addOrMergeEdge(1, reverse ? 2 : 3, .01, .65);
		return network;
	}

	private static void pressurePropagatesAcrossAnInitiallyBalancedPort() {
		AirNetwork network = new AirNetwork(); network.addNode(1, 1.5); network.addNode(1, 1); network.addNode(1, 1);
		network.addOrMergeEdge(1, 2, .01, .65); network.addOrMergeEdge(2, 3, .01, .65);
		network.stepEdges(.05);
		require(network.standardVolumeNanos(3) > 1_000_000_000L && network.standardVolumeNanos(1) < 1_500_000_000L
				&& network.auditClosed(), "Global pressure solve omitted a port with zero initial pressure gradient");
	}

	private static void thermalEnthalpyAndPositiveTemperature() {
		var fixture = thermal(1.5, .5, .5);
		fixture.network.addOrMergeEdge(1, 2, .01, .65); fixture.network.addOrMergeEdge(1, 3, .01, .65);
		double beforeDifference = fixture.energy.gaugePressure(1, fixture.network) - fixture.energy.gaugePressure(2, fixture.network);
		fixture.step(.05);
		require(fixture.energy.temperature(1, fixture.network) < 300 && fixture.energy.temperature(2, fixture.network) > 300,
				"Source enthalpy did not cool the donor and heat the receivers");
		require(fixture.energy.gaugePressure(1, fixture.network) - fixture.energy.gaugePressure(2, fixture.network) < beforeDifference,
				"Finite thermal flow did not relax the macroscopic pressure contrast");
		for (int i = 0; i < 4; i++) fixture.step(.05);
		fixture.checkAudit();
	}

	private static void unequalTemperatureBlowdown() {
		for (double dt : new double[]{.05, .005}) {
			var fixture = thermal(.125 * 1.1 * GasRegistry.STANDARD_TEMPERATURE_K / 900,
					.125 * .9 * GasRegistry.STANDARD_TEMPERATURE_K / 300);
			fixture.network.updateAvailableVolume(1, .125); fixture.network.updateAvailableVolume(2, .125);
			var inventories = fixture.network.nodeIds().stream().map(fixture.network::inventory).toList();
			var model = new GasThermalModel(new ThermalRegistry(List.of(new ThermalMaterialDefinition("test:thermal/gas",
					ThermalMaterialDefinition.CarrierKind.GAS, "test:gas", 300, 0, 0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(1, 2000, 718))))), fixture.network.registry());
			double[] energies = {model.internalEnergyAtTemperature(inventories.get(0), 900), model.internalEnergyAtTemperature(inventories.get(1), 300)};
			double initialEnergy = fixture.energy.nodeEnergy(1, fixture.network) + fixture.energy.nodeEnergy(2, fixture.network);
			fixture.energy.commitInventoryEnergy(fixture.energy.planInventoryEnergy(fixture.network, fixture.network.nodeIds(), inventories,
					energies, SignedDyadic.of(energies[0] + energies[1] - initialEnergy), SignedDyadic.ZERO, SignedDyadic.ZERO));
			fixture.network.addOrMergeEdge(1, 2, .0625, .65);
			double pressure1 = fixture.energy.gaugePressure(1, fixture.network) + AirNetwork.REFERENCE_PRESSURE;
			double pressure2 = fixture.energy.gaugePressure(2, fixture.network) + AirNetwork.REFERENCE_PRESSURE;
			double total = fixture.energy.exactGasEnergy().toDouble();
			for (int step = 0; step < Math.round(.5 / dt); step++) {
				fixture.step(dt);
				require(fixture.energy.gaugePressure(1, fixture.network) - fixture.energy.gaugePressure(2, fixture.network) >= -.02,
						"Hot-to-cold orifice flow reversed its actual pressure difference");
			}
			double gamma = 1 + AirNetwork.REFERENCE_PRESSURE / GasRegistry.STANDARD_TEMPERATURE_K / 718;
			double expected = 900 * Math.pow((pressure1 + pressure2) / (2 * pressure1), (gamma - 1) / gamma);
			require(Math.abs(fixture.energy.temperature(1, fixture.network) - expected) < .05,
					"Coarse hot-cavity discharge changed the isentropic equilibrium temperature");
			require(Math.abs(fixture.energy.exactGasEnergy().toDouble() - total) < 1e-7, "Closed hot/cold cavities changed total U");
			System.out.printf("  hot/cold orifice dt=%.3f s: donor T=%.6f K, isentropic equilibrium=%.6f K, deltaP=%.6g Pa%n", dt,
					fixture.energy.temperature(1, fixture.network), expected, fixture.energy.gaugePressure(1, fixture.network) - fixture.energy.gaugePressure(2, fixture.network));
			fixture.checkAudit();
		}
	}

	private static void ambientAndLiquidSeal() {
		var vent = thermal(1.1); vent.network.addOrMergeEdge(1, 0, .001, .65); vent.step(.01);
		require(vent.audit.ledger().externalSink().compareTo(SignedDyadic.ZERO) > 0, "Ambient outflow lost its enthalpy sink");
		var draw = thermal(.5); draw.network.addOrMergeEdge(1, 0, .001, .65); draw.step(.01);
		require(draw.audit.ledger().externalSource().compareTo(SignedDyadic.ZERO) > 0, "Ambient inflow lost its enthalpy source");
		var sealed = thermal(1.1, .9); var before = sealed.network.sealedInventory();
		long gasRevision = sealed.network.revision(), energyRevision = sealed.energy.revision();
		sealed.step(.01);
		require(before.equals(sealed.network.sealedInventory()) && gasRevision == sealed.network.revision()
				&& energyRevision == sealed.energy.revision(), "Liquid-sealed cavities exchanged inventory or energy");
		vent.checkAudit(); draw.checkAudit(); sealed.checkAudit();
	}

	private static Fixture thermal(double... volumes) {
		var gas = new GasRegistry(List.of(new GasComponentDefinition("test:gas", 1, 1, List.of(), List.of())),
				List.of(new GasRegistry.AmbientFraction("test:gas", ExactRational.ONE)));
		var thermal = new ThermalRegistry(List.of(new ThermalMaterialDefinition("test:thermal/gas",
				ThermalMaterialDefinition.CarrierKind.GAS, "test:gas", 300, 0, 0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(1, 2000, 718)))));
		var network = new AirNetwork(gas); for (double volume : volumes) network.addNode(1, volume);
		var audit = new ThermalAuditState(ThermalAuditLedger.initialized(SignedDyadic.ZERO));
		return new Fixture(network, new GasEnergyStore(new GasThermalModel(thermal, gas), 300, audit, network), audit);
	}
	private record Fixture(AirNetwork network, GasEnergyStore energy, ThermalAuditState audit) {
		void step(double dt) { network.stepEdges(dt, energy.thermodynamics(network), batch -> energy.planFlow(batch, network)); }
		void checkAudit() {
			require(network.auditClosed() && energy.exactGasEnergy().equals(audit.ledger().expectedInventory()), "Gas flow inventory/energy audit is open");
			for (int id : network.nodeIds()) require(energy.temperature(id, network) > 0, "Gas flow produced nonpositive temperature");
		}
	}
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
