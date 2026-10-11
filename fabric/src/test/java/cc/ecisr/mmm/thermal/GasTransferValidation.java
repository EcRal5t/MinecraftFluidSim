package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.air.GasComponentDefinition;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Exhaustive exact portions checked against an independent constant-Cv caloric/EOS oracle. */
public final class GasTransferValidation {
	private static final double[] CV = {100.0, 1_000.0, 10_000.0};
	private static final double R_PER_NANO = GasRegistry.STANDARD_PRESSURE_PA * 1.0E-9
			/ GasRegistry.STANDARD_TEMPERATURE_K;
	private GasTransferValidation() { }

	public static void validate() {
		ArrayList<GasComponentDefinition> definitions = new ArrayList<>();
		ArrayList<ThermalMaterialDefinition> materials = new ArrayList<>();
		for (int index = 0; index < CV.length; index++) {
			String id = "test:gas" + index;
			definitions.add(new GasComponentDefinition(id, 1.0, 1.0, List.of(), List.of()));
			materials.add(new ThermalMaterialDefinition("test:thermal/gas" + index,
					ThermalMaterialDefinition.CarrierKind.GAS, id, 300.0, 0.0, 0.0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(10.0, 2_000.0, CV[index]))));
		}
		GasRegistry gas = new GasRegistry(definitions,
				List.of(new GasRegistry.AmbientFraction("test:gas0", ExactRational.ONE)));
		GasThermalModel model = new GasThermalModel(new ThermalRegistry(materials), gas);
		validateLargeTransferIsReadOnly(gas, model);
		Random random = new Random(20_261_011L);
		for (int sample = 0; sample < 256; sample++) {
			int[] source = {1 + random.nextInt(25), 1 + random.nextInt(25), 1 + random.nextInt(25)};
			int[] target = {1 + random.nextInt(25), 1 + random.nextInt(25), 1 + random.nextInt(25)};
			double sourceTemperature = 50.0 + random.nextDouble() * 950.0;
			double targetTemperature = 50.0 + random.nextDouble() * 950.0;
			double targetVolume = 1.0E-7 * sum(target) * targetTemperature / (sum(source) * sourceTemperature)
					* (1.0001 + random.nextDouble() * 4.0);
			validateMaximum(gas, model, source, target, sourceTemperature, targetTemperature, 1.0E-7, targetVolume);
		}
		Fixture holes = validateMaximum(gas, model, new int[] {24, 23, 20}, new int[] {1, 24, 4},
				53.39535978004694, 246.8989771402024, 1.0E-7, 2.102679657646761E-7);
		holes.network.addOrMergeEdge(1, 2, 0.1, 0.65);
		AirNetwork.NodeThermodynamics thermodynamics = holes.energy.thermodynamics(holes.network);
		require(thermodynamics.limitTransferNanos(1, 2, 1L) == 0L
				&& thermodynamics.limitTransferNanos(1, 2, 2L) == 0L
				&& thermodynamics.limitTransferNanos(1, 2, 3L) == 3L
				&& holes.network.hasPressureDrivenFlow(thermodynamics),
				"one rejected nanos portion hid a larger valid mixture transfer from gas activity");
		SignedDyadic before = holes.energy.exactGasEnergy();
		holes.network.stepEdges(1.0, thermodynamics, (source, target, moved, sourceAfter, targetAfter) ->
				holes.energy.planTransfer(source, target, moved, sourceAfter, targetAfter, holes.network));
		require(holes.network.standardVolumeNanos(1) == 64L
				&& holes.energy.exactGasEnergy().equals(before.add(holes.audit.ledger().roundoffResidual())),
				"non-monotone mixture flow did not jointly conserve inventory and internal energy");
	}

	private static void validateLargeTransferIsReadOnly(GasRegistry gas, GasThermalModel model) {
		long scale = Long.MAX_VALUE / 96L;
		GasInventory source = inventory(new long[] {24L * scale, 23L * scale, 20L * scale});
		GasInventory target = inventory(new long[] {scale, 24L * scale, 4L * scale});
		double sourceTemperature = 53.39535978004694, targetTemperature = 246.8989771402024;
		double sourceEnergy = capacity(source) * (sourceTemperature - 300.0);
		double targetEnergy = capacity(target) * (targetTemperature - 300.0);
		AirNetwork network = new AirNetwork(gas);
		network.addNode(1.0E-7 * scale, 0.0); network.addNode(2.102679657646761E-7 * scale, 0.0);
		network.commitPhaseSource(1, source); network.commitPhaseSource(2, target);
		long totalNanos = Math.addExact(source.standardVolumeNanos(), target.standardVolumeNanos());
		require(network.sealedInventoryNanos() == totalNanos && network.drawnFromAmbientNanos() == totalNanos,
				"large mixture fixture must fit both the region inventory and its cumulative source audit");
		network.addOrMergeEdge(1, 2, 0.1, 0.65);
		ThermalAuditState audit = new ThermalAuditState(ThermalAuditLedger.initialized(
				SignedDyadic.of(sourceEnergy).add(SignedDyadic.of(targetEnergy))));
		GasEnergyStore energy = new GasEnergyStore(model, 300.0, audit, network);
		energy.commitNodeEnergy(1, sourceEnergy); energy.commitNodeEnergy(2, targetEnergy);
		long revision = network.revision(), energyRevision = energy.revision();
		SignedDyadic before = energy.exactGasEnergy();
		try {
			long amount = energy.thermodynamics(network).limitTransferNanos(1, 2, Long.MAX_VALUE);
			require(amount > 0L && amount <= Long.MAX_VALUE - target.standardVolumeNanos(),
					"large mixture search silently reported equilibrium or exceeded exact target capacity");
		} catch (GasEnergyStore.TransferSearchBudgetExceededException boundedSearch) {
			// A flat binary64 pressure interval retains the original state for a numerical pause.
		}
		require(network.hasPressureDrivenFlow(energy.thermodynamics(network)),
				"unresolved large mixture activity escaped the read-only query or authorized sleep");
		require(network.revision() == revision && energy.revision() == energyRevision
				&& network.inventory(1).equals(source) && network.inventory(2).equals(target)
				&& energy.exactGasEnergy().equals(before) && audit.ledger().roundoffResidual().equals(SignedDyadic.ZERO),
				"large mixture search or its budget rejection mutated inventory, U or audit");
	}

	private static Fixture validateMaximum(GasRegistry gas, GasThermalModel model, int[] source, int[] target,
	                                      double sourceTemperature, double targetTemperature,
	                                      double sourceVolume, double targetVolume) {
		GasInventory a = inventory(source), b = inventory(target);
		double sourceEnergy = capacity(a) * (sourceTemperature - 300.0);
		double targetEnergy = capacity(b) * (targetTemperature - 300.0);
		AirNetwork network = new AirNetwork(gas);
		network.addNode(sourceVolume, 0.0); network.addNode(targetVolume, 0.0);
		network.commitPhaseSource(1, a); network.commitPhaseSource(2, b);
		ThermalAuditState audit = new ThermalAuditState(ThermalAuditLedger.initialized(
				SignedDyadic.of(sourceEnergy).add(SignedDyadic.of(targetEnergy))));
		GasEnergyStore energy = new GasEnergyStore(model, 300.0, audit, network);
		energy.commitNodeEnergy(1, sourceEnergy); energy.commitNodeEnergy(2, targetEnergy);
		long expected = 0L;
		for (long amount = 1L; amount <= a.standardVolumeNanos(); amount++) {
			GasInventory.Split split = a.splitTotal(amount);
			double carried = capacity(split.first()) * (sourceTemperature - 300.0) + R_PER_NANO * amount * sourceTemperature;
			if (pressure(split.second(), sourceEnergy - carried, sourceVolume)
					>= pressure(b.merge(split.first()), targetEnergy + carried, targetVolume)) expected = amount;
		}
		long actual = energy.thermodynamics(network).limitTransferNanos(1, 2, a.standardVolumeNanos());
		require(actual == expected, "thermal mixture maximum disagrees with exhaustive caloric oracle: "
				+ actual + " versus " + expected);
		return new Fixture(network, energy, audit);
	}

	private static GasInventory inventory(int[] amounts) {
		return inventory(new long[] {amounts[0], amounts[1], amounts[2]});
	}
	private static GasInventory inventory(long[] amounts) {
		ArrayList<GasComponentAmount> components = new ArrayList<>();
		for (int index = 0; index < amounts.length; index++) {
			components.add(new GasComponentAmount("test:gas" + index, amounts[index], amounts[index] * 1.0E-9,
					List.of(), amounts[index] * 1.0E-9));
		}
		return new GasInventory(components);
	}
	private static double capacity(GasInventory inventory) {
		double result = 0.0;
		for (GasComponentAmount component : inventory.components()) {
			result += component.mass() * CV[component.componentId().charAt(8) - '0'];
		}
		return result;
	}
	private static double pressure(GasInventory inventory, double energy, double volume) {
		if (inventory.isEmpty()) return energy == 0.0 ? 0.0 : Double.NaN;
		double temperature = 300.0 + energy / capacity(inventory);
		return temperature > 0.0 ? R_PER_NANO * inventory.standardVolumeNanos() * temperature / volume : Double.NaN;
	}
	private static int sum(int[] values) { return values[0] + values[1] + values[2]; }
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	private record Fixture(AirNetwork network, GasEnergyStore energy, ThermalAuditState audit) { }
}
