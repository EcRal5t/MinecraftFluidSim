package mmm.fluid;

import java.util.Map;
import java.util.TreeMap;
import mmm.inventory.LiquidCellInventory;
import mmm.simulation.FullCapabilityTestFixture;
import mmm.thermal.ThermalTransport;

/** Real thermal candidates exchange heat locally while an isolated equilibrium chamber sleeps. */
public final class PhysicalActivityThermalValidation {
	private PhysicalActivityThermalValidation() { }

	public static void main(String[] args) throws Exception {
		FluidDomain domain = compartments();
		for (int step = 0; step < 210; step++) domain = new CpuFluidSolverBackend().evaluate(
				new FluidStepRequest(step, domain.captureAuthoritativeState(), java.util.List.of(), false, 0)).claimPreparedCpuDomain();
		var asleep = domain.activityStats();
		require(asleep.groupCount() == 2 && asleep.sleepingGroups() == 2,
				"Equal-temperature compartments did not sleep: " + asleep);
		var right = rightEnergyBits(domain);
		domain.setThermalTemperatureInCell(1, 1, 1, 303.15);
		double heatedBefore = domain.thermalTemperatureInCell(1, 1, 1);
		double neighbourBefore = domain.thermalTemperatureInCell(2, 1, 1);
		var beforeHeat = domain.exactInternalEnergy();
		var beforeRoundoff = domain.thermalAudit().roundoffResidual();
		var source = domain.thermalAudit().externalSource();
		double transferred = 0;
		for (int step = 0; step < 4; step++) {
			var result = advance(domain, 210 + step);
			domain = result.domain();
			transferred += result.stats().transport().maximumAbsoluteTransferJ();
			require(domain.activityStats().sleepingGroups() == 1,
					"Local heating woke the isolated equilibrium chamber: " + domain.activityStats());
			require(rightEnergyBits(domain).equals(right), "Sleeping chamber raw internal energy changed");
			require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"Local thermal advance opened the total internal energy audit");
		}
		require(transferred > 0 && domain.thermalTemperatureInCell(1, 1, 1) < heatedBefore
				&& domain.thermalTemperatureInCell(2, 1, 1) > neighbourBefore,
				"Awake thermal group did not exchange real carrier heat");
		require(domain.thermalAudit().externalSource().equals(source)
				&& domain.exactInternalEnergy().subtract(beforeHeat).equals(
						domain.thermalAudit().roundoffResidual().subtract(beforeRoundoff)),
				"Closed conduction changed energy beyond its actual storage rounding");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.activityStats().equals(domain.activityStats()), "Thermal handoff lost independent sleep");
		domain = advance(restored, 214).domain();
		require(domain.activityStats().sleepingGroups() == 1 && rightEnergyBits(domain).equals(right),
				"Backend successor changed the sleeping chamber after handoff");
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"Thermal backend successor opened the energy audit");
		System.out.println("Independent thermal sleep, local heat exchange and backend handoff passed");
	}

	private static FluidDomain compartments() throws Exception {
		var production = FullCapabilityTestFixture.load();
		var domain = new FluidDomain(9, 4, 4, new FluidSolverConfig(.5F, 1000, 0, .025F, 120, 1e-5F, .002F));
		var liquids = FullCapabilityTestFixture.bindEmptyMaterialState(domain, production.liquids());
		domain.sealDomainBoundary();
		for (int z = 0; z < 4; z++) for (int y = 0; y < 4; y++) domain.setSolid(4, y, z, true);
		float[] volume = new float[domain.cellCount];
		int[] materials = new int[domain.cellCount];
		java.util.Arrays.fill(materials, liquids.handle(BuiltinFluidMaterials.WATER.id()));
		for (int cell = 0; cell < volume.length; cell++) if (cell % 9 != 4) volume[cell] = 1;
		domain.setVolumes(volume, materials);
		FullCapabilityTestFixture.bindFull(domain, production, 293.15, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		return domain;
	}

	private record Advance(FluidDomain domain, FluidDomain.ThermalAxisStats stats) { }
	private static Advance advance(FluidDomain domain, long generation) {
		var candidate = new CpuFluidSolverBackend().evaluateThermal(
				new FluidThermalStepRequest(generation, domain.captureAuthoritativeState(), false, 0));
		return new Advance(candidate.claimPreparedCpuDomain(), candidate.stats());
	}
	private static Map<LiquidCellInventory.Key, Long> rightEnergyBits(FluidDomain domain) {
		var result = new TreeMap<LiquidCellInventory.Key, Long>();
		for (var stock : domain.liquidCells.physicalStocks()) if (stock.key().cell() % 9 > 4)
			result.put(stock.key(), Double.doubleToRawLongBits(stock.internalEnergy()));
		return Map.copyOf(result);
	}
	private static void require(boolean accepted, String message) { if (!accepted) throw new AssertionError(message); }
}
