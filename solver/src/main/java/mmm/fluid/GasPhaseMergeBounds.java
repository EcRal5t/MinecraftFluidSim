package mmm.fluid;

import mmm.fluid.air.GasInventory;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasRegistry;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalCaloricBounds;
import mmm.thermal.ThermalCaloricBounds.PortionRange;
import mmm.thermal.ThermalCaloricBounds.QuantaRange;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Bounds any subset of certified whole-stock successors entering the phase source's successor. */
final class GasPhaseMergeBounds {
	record Stock(GasInventory inventory, double energy, SignedDyadic fixedVolume) { }
	private final ThermalRegistry registry;
	private final GasThermalModel gas;
	private final ThermalCaloricBounds caloric;
	private final List<Stock> possible;

	GasPhaseMergeBounds(ThermalRegistry registry, GasThermalModel gas, List<Stock> possible) {
		this.registry = registry; this.gas = gas;
		this.caloric = new ThermalCaloricBounds(registry); this.possible = List.copyOf(possible);
	}

	double temperatureLower(GasInventory mandatory, double energy) {
		try {
			var stocks = new ArrayList<Stock>(); stocks.add(new Stock(mandatory, energy, SignedDyadic.ZERO)); stocks.addAll(possible);
			double initial = Double.POSITIVE_INFINITY;
			for (var stock : stocks) initial = Math.min(initial, gas.state(stock.inventory(), stock.energy()).temperatureK());
			SignedDyadic error = SignedDyadic.ZERO, energyMagnitude = SignedDyadic.ZERO;
			var masses = new TreeMap<Integer, SignedDyadic>();
			var counts = new TreeMap<Integer, Integer>();
			var species = new TreeMap<Integer, TreeMap<String, QuantaRange>>();
			for (var stock : stocks) {
				var portions = portions(stock.inventory());
				// Positive deficits enclose every subset, including none of the optional stocks.
				SignedDyadic deficit = SignedDyadic.of(caloric.curveEnergyAtTemperature(portions, initial, true))
						.subtract(SignedDyadic.of(stock.energy()));
				if (deficit.signum() > 0) error = error.add(deficit);
				energyMagnitude = energyMagnitude.add(SignedDyadic.of(Math.abs(stock.energy())));
				for (var portion : portions) {
					int handle = portion.materialHandle();
					masses.merge(handle, SignedDyadic.of(portion.massHigh()), SignedDyadic::add);
					counts.merge(handle, 1, Integer::sum);
					var amounts = species.computeIfAbsent(handle, ignored -> new TreeMap<>());
					portion.species().forEach((id, q) -> amounts.merge(id, new QuantaRange(0, q.high()),
							(a, b) -> new QuantaRange(0, Math.addExact(a.high(), b.high()))));
					error = error.add(SignedDyadic.of(caloric.referenceRoundingBound(portion)));
				}
			}
			for (var entry : masses.entrySet()) {
				int handle = entry.getKey();
				double sumUpper = Math.nextUp(entry.getValue().toDouble());
				// Fewer than 2^31 positive additions keep partial rounded sums below twice
				// the exact total; this also covers a rounding across its final binade.
				SignedDyadic massError = SignedDyadic.of(Math.ulp(2 * sumUpper))
						.multiply(SignedDyadic.of(counts.get(handle), 0));
				double massUpper = Math.nextUp(entry.getValue().add(massError).toDouble());
				var combined = new PortionRange(handle, 0, massUpper, species.get(handle));
				SignedDyadic specific = caloric.specificCurveEnergy(handle, initial);
				if (specific.signum() < 0) specific = specific.negate();
				error = error.add(massError.multiply(specific))
						.add(SignedDyadic.of(caloric.referenceRoundingBound(combined)));
			}
			// planAfterPhase defers volume work: complete source energies are summed exactly,
			// and only the destination raw U is rounded. Whole-stock partition keeps each U.
			error = error.add(SignedDyadic.of(Math.ulp(Math.nextUp(energyMagnitude.toDouble()))));
			return caloric.temperatureWithEnergyError(portions(mandatory), initial, Math.nextUp(error.toDouble()), false);
		} catch (IllegalArgumentException | ArithmeticException unresolved) { return Double.NaN; }
	}

	private List<PortionRange> portions(GasInventory inventory) {
		var result = new ArrayList<PortionRange>();
		for (var component : inventory.components()) {
			int handle = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS, component.componentId());
			var species = new TreeMap<String, QuantaRange>();
			for (var amount : component.species()) species.put(amount.speciesId(),
					new QuantaRange(amount.amountQuanta(), amount.amountQuanta()));
			result.add(new PortionRange(handle, component.mass(), component.mass(), species));
		}
		return List.copyOf(result);
	}

	double partialPressureLower(GasInventory mandatory, String vaporId, double temperature, double baseVolume) {
		var vapor = mandatory.component(vaporId);
		if (vapor == null) return 0;
		double lower = partialPressureLower(vapor.standardVolumeNanos(), temperature, baseVolume);
		for (var stock : possible) {
			var optional = stock.inventory().component(vaporId);
			if (optional == null || stock.fixedVolume().signum() == 0) continue;
			double volume = Math.nextUp(stock.fixedVolume().toDouble());
			lower = Math.min(lower, partialPressureLower(optional.standardVolumeNanos(), temperature, volume));
		}
		// A positive weighted mixture's ratio is at least its least component ratio;
		// zero-vapor fixed capacity is already included in the mandatory base.
		return lower;
	}

	static double partialPressureLower(long mandatoryNanos, double temperature, double volume) {
		if (!(mandatoryNanos > 0 && Double.isFinite(temperature) && temperature > 0
				&& Double.isFinite(volume) && volume > 0)) return Double.NaN;
		double partial = down(down(down(down(down((double) mandatoryNanos) * AirNetwork.cubicMeters(1))
				* GasRegistry.STANDARD_PRESSURE_PA) / GasRegistry.STANDARD_TEMPERATURE_K) * temperature);
		if (!(Double.isFinite(partial) && partial > 16 * Double.MIN_NORMAL)) return Double.NaN;
		partial = down(partial / volume);
		// The actual total-nanos pressure and vapor fraction cancel in exact arithmetic.
		// The same converted total appears in cubicMeters(total) and vapor/total.
		// Seven normal binary64 roundings lose at most (1-2^-52)^7. One more covers
		// conversion of the final summed integer vapor count; each paired numerator
		// above is rounded down against its exact integer before taking the ratio.
		if (!(Double.isFinite(partial) && partial > 16 * Double.MIN_NORMAL)) return Double.NaN;
		double factor = 1;
		for (int operation = 0; operation < 8; operation++) factor = down(factor * (1 - Math.scalb(1.0, -52)));
		return down(partial * factor);
	}

	private static double down(double value) { return Math.max(0, Math.nextDown(value)); }
}
