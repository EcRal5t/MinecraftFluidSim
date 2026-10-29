package mmm.thermal;

import mmm.chemistry.ExactRational;
import mmm.fluid.air.*;
import mmm.units.SignedDyadic;
import java.util.List;

/** Gas equation of state and external inventory edits. */
public final class GasTransferValidation {
	private GasTransferValidation() { }
	public static void main(String[] args) {
		if (args.length == 1 && args[0].equals("--external")) {
			localExternalGasEdits(); System.out.println("Local external gas editing validation passed"); return;
		}
		if (args.length == 1 && args[0].equals("--eos")) {
			pressureVolumeIsentropes(); System.out.println("Gas pressure-volume EOS validation passed"); return;
		}
		if (args.length != 0) throw new IllegalArgumentException("Expected no arguments, --eos or --external");
		validate(); System.out.println("Global gas flow validation passed");
	}
	public static void validate() {
		pressureVolumeIsentropes();
		localExternalGasEdits();
	}

	/** Player gas edits retain all integer columns and share the thermal source/sink transaction. */
	private static void localExternalGasEdits() {
		mmm.simulation.FullCapabilityTestFixture.Registries registries;
		try { registries = mmm.simulation.FullCapabilityTestFixture.load(); }
		catch (Exception failure) { throw new IllegalStateException("Cannot load gas edit fixture", failure); }
		var domain = new mmm.fluid.HostFluidState(4, 4, 4,
				new mmm.fluid.FluidSolverConfig(.5F, 1000, 0, .025F, 200, 1e-5F, .002F));
		domain.sealDomainBoundary();
		mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		domain.setVolume(1, 1, 1, .5F);
		mmm.simulation.FullCapabilityTestFixture.bindFull(domain, registries, 293.15, 8);
		domain.bootstrapGasState(registries.chemical().fingerprint());
		int node = domain.gasNodeAt(.625, .875, .625);
		require(node > 0 && domain.gasNodeAt(.625, .55, .625) == -1,
				"Local gas editing did not distinguish the dry and wet PLIC sides");
		long before = domain.gasBoundary().standardVolumeNanos(node);
		var sourceBefore = domain.thermalAudit().externalSource();
		var inserted = domain.editGas(node, BuiltinGasDefinitions.OXYGEN, 123_456_789, 500, false);
		require(inserted.moved().standardVolumeNanos() == 123_456_789
				&& domain.gasBoundary().standardVolumeNanos(node) == before + 123_456_789
				&& domain.thermalAudit().externalSource().subtract(sourceBefore).equals(inserted.internalEnergy()),
				"Gas insertion lost quantity or source energy");
		var inventory = domain.gasBoundary().inventory(node);
		var energy = domain.exactInternalEnergy();
		var audit = domain.thermalAudit();
		boolean rejected = false;
		try { domain.editGas(node, BuiltinGasDefinitions.OXYGEN, 100, Double.NaN, false); }
		catch (IllegalArgumentException expected) { rejected = true; }
		require(rejected && inventory.equals(domain.gasBoundary().inventory(node))
				&& domain.exactInternalEnergy().equals(energy) && domain.thermalAudit().equals(audit),
				"Rejected gas insertion published inventory or energy");
		var oxygen = inventory.component(BuiltinGasDefinitions.OXYGEN);
		var sinkBefore = audit.externalSink();
		var removed = domain.editGas(node, BuiltinGasDefinitions.OXYGEN, Long.MAX_VALUE, 293.15, true);
		require(removed.moved().components().equals(List.of(oxygen))
				&& domain.gasBoundary().inventory(node).component(BuiltinGasDefinitions.OXYGEN) == null
				&& domain.thermalAudit().externalSink().subtract(sinkBefore).equals(removed.internalEnergy()),
				"Component extraction exceeded its inventory or changed carried columns");
		var mixed = domain.gasBoundary().inventory(node);
		var emptied = domain.editGas(node, null, Long.MAX_VALUE, 293.15, true);
		require(emptied.moved().equals(mixed) && domain.gasBoundary().inventory(node).isEmpty()
				&& domain.exactGasInternalEnergy().equals(SignedDyadic.ZERO),
				"Complete mixture extraction did not leave a zero-energy vacuum");
		domain.editGas(node, null, 1_000_000_000, 293.15, false);
		require(domain.gasBoundary().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"Local gas source/sink and thermal audit do not close");
	}

	/** Disconnected cavities stay outside each port transaction; failed preparation publishes nothing. */

	/** Independent ideal-gas entropy integrals, including a heat-capacity transition. */
	private static void pressureVolumeIsentropes() {
		var gas = new GasRegistry(List.of(new GasComponentDefinition("test:gas", 1, List.of(), List.of())),
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

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
