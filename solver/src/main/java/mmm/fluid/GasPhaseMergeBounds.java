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
import mmm.chemistry.ExactRational;
import java.math.BigInteger;
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
		return temperatureLower(mandatory, energy, null, Double.NaN, 0);
	}

	double temperatureLower(GasInventory mandatory, double energy, PortionRange born, double bornTemperature, double bornError) {
		try {
			var stocks = new ArrayList<Stock>();
			if (!mandatory.isEmpty()) stocks.add(new Stock(mandatory, energy, SignedDyadic.ZERO));
			stocks.addAll(possible);
			double initial = born == null ? Double.POSITIVE_INFINITY : bornTemperature;
			for (var stock : stocks) initial = Math.min(initial, gas.state(stock.inventory(), stock.energy()).temperatureK());
			SignedDyadic error = SignedDyadic.ZERO, energyMagnitude = SignedDyadic.ZERO, gain = SignedDyadic.ZERO;
			var masses = new TreeMap<Integer, SignedDyadic>();
			var counts = new TreeMap<Integer, Integer>();
			var species = new TreeMap<Integer, TreeMap<String, QuantaRange>>();
			var ranges = new ArrayList<List<PortionRange>>();
			for (var stock : stocks) ranges.add(portions(stock.inventory()));
			if (born != null) ranges.add(List.of(born));
			for (int index = 0; index < ranges.size(); index++) {
				var portions = ranges.get(index);
				// Positive deficits enclose every subset, including none of the optional stocks.
				if (index < stocks.size()) {
					var stock = stocks.get(index);
					SignedDyadic deficit = SignedDyadic.of(caloric.curveEnergyAtTemperature(portions, initial, true))
							.subtract(SignedDyadic.of(stock.energy()));
					if (deficit.signum() > 0) error = error.add(deficit);
					else if (index == 0 && !mandatory.isEmpty()) gain = gain.subtract(deficit);
					energyMagnitude = energyMagnitude.add(SignedDyadic.of(Math.abs(stock.energy())));
				} else {
					gain = gain.add(SignedDyadic.of(born.massLow()).multiply(caloric.specificCurveEnergy(born.materialHandle(), bornTemperature)
							.subtract(caloric.specificCurveEnergy(born.materialHandle(), initial))));
					error = error.add(SignedDyadic.of(bornError));
					energyMagnitude = energyMagnitude.add(SignedDyadic.of(Math.nextUp(Math.max(
							Math.abs(caloric.energyAtTemperature(portions, bornTemperature, false)),
							Math.abs(caloric.energyAtTemperature(portions, bornTemperature, true))))));
				}
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
			var mergeErrors = new TreeMap<Integer, Double>();
			var maximumSlope = new ArrayList<PortionRange>();
			for (var entry : masses.entrySet()) {
				int handle = entry.getKey();
				double sumUpper = Math.nextUp(entry.getValue().toDouble());
				// Fewer than 2^31 positive additions keep partial rounded sums below twice
				// the exact total; this also covers a rounding across its final binade.
				SignedDyadic massError = SignedDyadic.of(Math.ulp(2 * sumUpper))
						.multiply(SignedDyadic.of(counts.get(handle), 0));
				mergeErrors.put(handle, Math.nextUp(massError.toDouble()));
				double massUpper = Math.nextUp(entry.getValue().add(massError).toDouble());
				var combined = new PortionRange(handle, 0, massUpper, species.get(handle));
				maximumSlope.add(combined);
				SignedDyadic specific = caloric.specificCurveEnergy(handle, initial);
				if (specific.signum() < 0) specific = specific.negate();
				error = error.add(massError.multiply(specific))
						.add(SignedDyadic.of(caloric.referenceRoundingBound(combined)));
			}
			// planAfterPhase defers volume work: complete source energies are summed exactly,
			// and only the destination raw U is rounded. Whole-stock partition keeps each U.
			error = error.add(SignedDyadic.of(Math.ulp(Math.nextUp(energyMagnitude.toDouble()))));
			var mandatorySlope = new ArrayList<PortionRange>(portions(mandatory));
			if (born != null) {
				mandatorySlope.add(born);
				mandatorySlope.replaceAll(portion -> new PortionRange(portion.materialHandle(), Math.max(0,
						Math.nextDown(portion.massLow() - mergeErrors.get(portion.materialHandle()))), portion.massHigh(), portion.species()));
			}
			double lower = caloric.temperatureWithEnergyError(mandatorySlope, initial, Math.nextUp(error.toDouble()), false);
			SignedDyadic surplus = gain.subtract(error);
			return surplus.signum() <= 0 ? lower : Math.max(lower, caloric.temperatureWithEnergyGainLower(
					maximumSlope, initial, Math.max(0, Math.nextDown(surplus.toDouble()))));
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

	/** Bounds a positive child of the actual common-temperature inventory/U partition. */
	double partitionTemperatureLower(GasInventory inventory, double energy, ExactRational share, int maximumChildren) {
		try {
			if (maximumChildren < 1 || share.signum() <= 0 || share.compareTo(ExactRational.ONE) > 0) return Double.NaN;
			double temperature = gas.state(inventory, energy).temperatureK();
			var source = portions(inventory); var minimum = new ArrayList<PortionRange>();
			SignedDyadic raw = SignedDyadic.of(energy);
			SignedDyadic error = abs(raw.subtract(SignedDyadic.of(caloric.curveEnergyAtTemperature(source, temperature, false))));
			SignedDyadic other = abs(raw.subtract(SignedDyadic.of(caloric.curveEnergyAtTemperature(source, temperature, true))));
			if (other.compareTo(error) > 0) error = other;
			SignedDyadic magnitude = SignedDyadic.ZERO, hostForward = SignedDyadic.ZERO;
			for (int index = 0; index < source.size(); index++) {
				var portion = source.get(index); var component = inventory.components().get(index);
				long nanos = share.numerator().multiply(BigInteger.valueOf(component.standardVolumeNanos()))
						.divide(share.denominator()).longValueExact();
				double massUlp = Math.ulp(2 * component.mass());
				// Integer casts, division and multiplication each round once; the mass pivot
				// also carries all other children's observation errors before its final RN.
				double fraction = Math.max(0, Math.nextDown(Math.nextDown((double) nanos) / Math.nextUp((double) component.standardVolumeNanos())));
				SignedDyadic massError = SignedDyadic.of(massUlp).multiply(SignedDyadic.of(4L * maximumChildren + 1, 0));
				double least = Math.max(0, Math.nextDown(SignedDyadic.of(Math.max(0, Math.nextDown(component.mass() * fraction)))
						.subtract(massError).toDouble()));
				minimum.add(new PortionRange(portion.materialHandle(), least, 2 * component.mass(), java.util.Map.of()));
				var species = new TreeMap<String, QuantaRange>();
				portion.species().forEach((id, q) -> species.put(id, new QuantaRange(0, q.high())));
				var child = new PortionRange(portion.materialHandle(), 0, 2 * component.mass(), species);
				hostForward = hostForward.add(SignedDyadic.of(caloric.forwardRoundingBound(child, temperature)));
				// Species quanta conserve exactly. Only the component's mass-pivot RN
				// and each child's raw reference column perturb the summed caloric curve.
				error = error.add(SignedDyadic.of(massUlp).multiply(abs(caloric.specificCurveEnergy(portion.materialHandle(), temperature))))
						.add(SignedDyadic.of(caloric.referenceRoundingBound(portion)))
						.add(SignedDyadic.of(caloric.referenceRoundingBound(child)).multiply(SignedDyadic.of(maximumChildren, 0)));
				magnitude = magnitude.add(SignedDyadic.of(Math.max(Math.abs(caloric.curveEnergyAtTemperature(List.of(child), temperature, false)),
						Math.abs(caloric.curveEnergyAtTemperature(List.of(child), temperature, true)))));
			}
			// Host sensible evaluation and the final forward U each round; the pivot
			// carries their sum before its exact reduction's final RN.
			SignedDyadic forward = hostForward.add(SignedDyadic.of(Math.ulp(Math.nextUp(magnitude.add(hostForward).toDouble()))))
					.multiply(SignedDyadic.of(maximumChildren, 0));
			error = error.add(forward).add(SignedDyadic.of(Math.ulp(Math.nextUp(abs(raw)
					.add(magnitude.multiply(SignedDyadic.of(maximumChildren, 0))).add(forward).toDouble()))));
			return caloric.temperatureWithEnergyError(minimum, temperature, Math.nextUp(error.toDouble()), false);
		} catch (IllegalArgumentException | ArithmeticException unresolved) { return Double.NaN; }
	}

	private static SignedDyadic abs(SignedDyadic value) { return value.signum() < 0 ? value.negate() : value; }

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
