package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.air.GasComponentDefinition;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.thermal.ThermalCaloricBounds.PortionRange;
import cc.ecisr.mmm.thermal.ThermalCaloricBounds.QuantaRange;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.LongPredicate;

/** Exact integer search with caloric interval bounds for non-monotone mixture rounding. */
final class GasTransferSearch {
	private final ThermalRegistry thermal;
	private final ThermalCaloricBounds caloric;
	private final AirNetwork network;
	private final int source, target;
	private final double sourceTemperature, sourceEnergy, targetEnergy;
	private final GasInventory sourceInventory, targetInventory;
	private final LongPredicate permits;
	private int intervals;

	GasTransferSearch(ThermalRegistry thermal, AirNetwork network, int source, int target,
	                  double sourceTemperature, double sourceEnergy, double targetEnergy, LongPredicate permits) {
		this.thermal = thermal; this.caloric = new ThermalCaloricBounds(thermal); this.network = network; this.source = source; this.target = target;
		this.sourceTemperature = sourceTemperature; this.sourceEnergy = sourceEnergy;
		this.targetEnergy = targetEnergy; this.permits = permits;
		this.sourceInventory = source == AirNetwork.AMBIENT_NODE ? GasInventory.EMPTY : network.inventory(source);
		this.targetInventory = target == AirNetwork.AMBIENT_NODE ? GasInventory.EMPTY : network.inventory(target);
	}

	long maximum(long requested) {
		if (requested <= 0L) return 0L;
		if (permits.test(requested)) return requested;
		return search(1L, requested - 1L);
	}

	private long search(long low, long high) {
		if (low > high) return 0L;
		if (++intervals > ThermalLimits.MAX_GAS_TRANSFER_SEARCH_INTERVALS) {
			throw new GasEnergyStore.TransferSearchBudgetExceededException();
		}
		if (low == high) return permits.test(low) ? low : 0L;
		if (excluded(low, high)) return 0L;
		long middle = low + (high - low) / 2L;
		long upper = search(middle + 1L, high);
		return upper > 0L ? upper : search(low, middle);
	}

	/** Largest-remainder allocations lie between floor(low*w/W) and ceil(high*w/W). */
	private boolean excluded(long low, long high) {
		try {
			ArrayList<PortionRange> moved = new ArrayList<>(), remaining = new ArrayList<>();
			if (source == AirNetwork.AMBIENT_NODE) {
				for (GasRegistry.AmbientFraction fraction : network.registry().ambient()) {
					long first = quota(low, fraction.fraction().numerator(), fraction.fraction().denominator(), false);
					long last = quota(high, fraction.fraction().numerator(), fraction.fraction().denominator(), true);
					moved.add(range(ambientComponent(fraction.componentId(), first), ambientComponent(fraction.componentId(), last)));
				}
			} else {
				BigInteger total = BigInteger.valueOf(sourceInventory.standardVolumeNanos());
				for (GasComponentAmount component : sourceInventory.components()) {
					BigInteger weight = BigInteger.valueOf(component.standardVolumeNanos());
					long first = quota(low, weight, total, false);
					long last = quota(high, weight, total, true);
					GasInventory.Split least = sourceInventory.splitComponent(component.componentId(), first);
					GasInventory.Split most = sourceInventory.splitComponent(component.componentId(), last);
					moved.add(range(least.first().component(component.componentId()), most.first().component(component.componentId())));
					remaining.add(range(most.second().component(component.componentId()), least.second().component(component.componentId())));
				}
			}
			double minimumCarried = caloric.energyAtTemperature(moved, sourceTemperature, false);
			minimumCarried = down(minimumCarried + flowWorkBound(low, sourceTemperature, false));
			if (!Double.isFinite(minimumCarried)) return false;
			double maximumSourcePressure;
			if (source == AirNetwork.AMBIENT_NODE) maximumSourcePressure = GasRegistry.STANDARD_PRESSURE_PA;
			else {
				double temperature = caloric.temperatureBound(remaining, up(sourceEnergy - minimumCarried), true, sourceTemperature);
				maximumSourcePressure = up(up(gasConstantBound(sourceInventory.standardVolumeNanos() - low, true)
						* temperature) / network.availableVolume(source));
			}
			double minimumTargetPressure;
			if (target == AirNetwork.AMBIENT_NODE) minimumTargetPressure = GasRegistry.STANDARD_PRESSURE_PA;
			else {
				TreeMap<String, PortionRange> combined = new TreeMap<>();
				for (PortionRange portion : moved) combined.put(thermal.material(portion.materialHandle()).carrierId(), portion);
				for (GasComponentAmount component : targetInventory.components()) {
					combined.merge(component.componentId(), range(component, component), caloric::merge);
				}
				double temperature = caloric.temperatureBound(List.copyOf(combined.values()), down(targetEnergy + minimumCarried), false, sourceTemperature);
				minimumTargetPressure = down(down(gasConstantBound(Math.addExact(targetInventory.standardVolumeNanos(), low), false)
						* temperature) / network.availableVolume(target));
			}
			return maximumSourcePressure < minimumTargetPressure;
		} catch (IllegalArgumentException | ArithmeticException unresolvedBounds) {
			// An unrepresentable interval bound supplies no evidence for pruning.
			return false;
		}
	}

	private PortionRange range(GasComponentAmount low, GasComponentAmount high) {
		GasComponentAmount any = high == null ? low : high;
		int handle = thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS, any.componentId());
		TreeMap<String, long[]> species = new TreeMap<>();
		if (low != null) for (GasComponentAmount.SpeciesAmount entry : low.species()) species.computeIfAbsent(entry.speciesId(), ignored -> new long[2])[0] = entry.amountQuanta();
		if (high != null) for (GasComponentAmount.SpeciesAmount entry : high.species()) species.computeIfAbsent(entry.speciesId(), ignored -> new long[2])[1] = entry.amountQuanta();
		TreeMap<String, QuantaRange> ranges = new TreeMap<>();
		species.forEach((id, values) -> ranges.put(id, new QuantaRange(values[0], values[1])));
		return new PortionRange(handle, low == null ? 0.0 : low.mass(),
				high == null ? 0.0 : high.mass(), ranges);
	}

	private GasComponentAmount ambientComponent(String id, long nanos) {
		if (nanos == 0L) return null;
		GasComponentDefinition definition = network.registry().component(id);
		ArrayList<GasComponentAmount.SpeciesAmount> species = new ArrayList<>();
		for (GasComponentDefinition.DefaultSpecies entry : definition.defaultSpeciesPerCubicMeter()) {
			long quanta = quota(nanos, entry.quantaPerCubicMeter().numerator(),
					entry.quantaPerCubicMeter().denominator().multiply(BigInteger.valueOf(1_000_000_000L)), false);
			if (quanta > 0L) species.add(new GasComponentAmount.SpeciesAmount(entry.speciesId(), quanta));
		}
		return new GasComponentAmount(id, nanos, definition.standardMassPerCubicMeter() * AirNetwork.cubicMeters(nanos),
				species, definition.defaultMassResidualPerCubicMeter() * AirNetwork.cubicMeters(nanos));
	}

	private static long quota(long amount, BigInteger numerator, BigInteger denominator, boolean ceiling) {
		BigInteger[] divided = numerator.multiply(BigInteger.valueOf(amount)).divideAndRemainder(denominator);
		return divided[0].add(ceiling && divided[1].signum() != 0 ? BigInteger.ONE : BigInteger.ZERO).longValueExact();
	}
	private static double outward(double value, boolean upper) { return upper ? up(value) : down(value); }
	private static double gasConstantBound(long nanos, boolean upper) {
		double volume = outward(nanos * 1.0e-9, upper);
		return outward(outward(GasRegistry.STANDARD_PRESSURE_PA * volume, upper)
				/ GasRegistry.STANDARD_TEMPERATURE_K, upper);
	}
	private static double flowWorkBound(long nanos, double temperature, boolean upper) {
		double volume = outward(nanos * 1.0e-9, upper);
		return outward(outward(outward(GasRegistry.STANDARD_PRESSURE_PA * volume, upper) * temperature, upper)
				/ GasRegistry.STANDARD_TEMPERATURE_K, upper);
	}
	private static double up(double value) { return Math.nextUp(value); }
	private static double down(double value) { return Math.nextDown(value); }
}
