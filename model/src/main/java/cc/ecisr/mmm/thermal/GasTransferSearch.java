package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.air.GasComponentDefinition;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongPredicate;

/** Exact integer search with caloric interval bounds for non-monotone mixture rounding. */
final class GasTransferSearch {
	private final ThermalRegistry thermal;
	private final AirNetwork network;
	private final int source, target;
	private final double sourceTemperature, sourceEnergy, targetEnergy;
	private final GasInventory sourceInventory, targetInventory;
	private final LongPredicate permits;
	private int intervals;

	GasTransferSearch(ThermalRegistry thermal, AirNetwork network, int source, int target,
	                  double sourceTemperature, double sourceEnergy, double targetEnergy, LongPredicate permits) {
		this.thermal = thermal; this.network = network; this.source = source; this.target = target;
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
			double minimumCarried = energy(moved, sourceTemperature, false);
			minimumCarried = down(minimumCarried + flowWorkBound(low, sourceTemperature, false));
			double maximumSourcePressure;
			if (source == AirNetwork.AMBIENT_NODE) maximumSourcePressure = GasRegistry.STANDARD_PRESSURE_PA;
			else {
				double temperature = temperatureBound(remaining, up(sourceEnergy - minimumCarried), true);
				maximumSourcePressure = up(up(gasConstantBound(sourceInventory.standardVolumeNanos() - low, true)
						* temperature) / network.availableVolume(source));
			}
			double minimumTargetPressure;
			if (target == AirNetwork.AMBIENT_NODE) minimumTargetPressure = GasRegistry.STANDARD_PRESSURE_PA;
			else {
				TreeMap<String, PortionRange> combined = new TreeMap<>();
				for (PortionRange portion : moved) combined.put(portion.material.carrierId(), portion);
				for (GasComponentAmount component : targetInventory.components()) {
					combined.merge(component.componentId(), range(component, component), this::merge);
				}
				double temperature = temperatureBound(List.copyOf(combined.values()), down(targetEnergy + minimumCarried), false);
				minimumTargetPressure = down(down(gasConstantBound(Math.addExact(targetInventory.standardVolumeNanos(), low), false)
						* temperature) / network.availableVolume(target));
			}
			return maximumSourcePressure < minimumTargetPressure;
		} catch (IllegalArgumentException | ArithmeticException unresolvedBounds) {
			// An unrepresentable interval bound supplies no evidence for pruning.
			return false;
		}
	}

	private double temperatureBound(List<PortionRange> portions, double targetEnergy, boolean upper) {
		double low = ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
		double high = Math.max(1.0, sourceTemperature);
		if (chartEnergy(portions, low, targetEnergy, !upper) >= targetEnergy) return low;
		while (chartEnergy(portions, high, targetEnergy, !upper) < targetEnergy) {
			if (high >= Double.MAX_VALUE / 2.0) return upper ? Double.POSITIVE_INFINITY : high;
			high *= 2.0;
		}
		// The bracket remains outward-facing even when its root is too close to resolve.
		for (int iteration = 0; iteration < 64 && Math.nextUp(low) < high; iteration++) {
			double middle = low + (high - low) / 2.0;
			if (chartEnergy(portions, middle, targetEnergy, !upper) < targetEnergy) low = middle;
			else high = middle;
		}
		return upper ? high : low;
	}

	/** Bounds the same piecewise-linear inversion chart used by ThermalStateFunction. */
	private double chartEnergy(List<PortionRange> portions, double temperature, double targetEnergy, boolean upper) {
		double cursor = ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
		double result = energy(portions, cursor, upper);
		double largestUlp = Math.max(Math.ulp(result), Math.ulp(targetEnergy));
		TreeSet<Double> ends = new TreeSet<>();
		for (PortionRange portion : portions) for (ThermalMaterialDefinition.CaloricSegment segment : portion.material.caloricSegments()) {
			if (segment.maximumTemperatureK() < temperature) ends.add(segment.maximumTemperatureK());
		}
		ends.add(temperature);
		for (double end : ends) {
			double capacity = 0.0;
			for (PortionRange portion : portions) {
				double mass = upper ? portion.massHigh : portion.massLow;
				double term = mass * portion.material.specificHeatAt(cursor + (end - cursor) * 0.5);
				capacity = outward(capacity + outward(term, upper), upper);
			}
			result = outward(result + outward(capacity * (end - cursor), upper), upper);
			// State inversion accepts at most 16 ulps around each chart endpoint.
			largestUlp = Math.max(largestUlp, Math.ulp(result));
			result = outward(result + (upper ? 1.0 : -1.0) * largestUlp * 16.0, upper);
			cursor = end;
		}
		return result;
	}

	private double energy(List<PortionRange> portions, double temperature, boolean upper) {
		double result = 0.0;
		for (PortionRange portion : portions) {
			double reference = portion.material.referenceSpecificEnergyEuPerMu();
			double sensible = portion.material.sensibleSpecificEnergyAt(temperature);
			double base = productRange(portion.massLow, portion.massHigh, reference, upper);
			int handle = thermal.materialHandle(portion.material.id());
			for (var entry : portion.species.entrySet()) {
				ThermalSpeciesOffset offset = thermal.speciesOffset(handle, entry.getKey());
				double first = thermal.referenceEnergyCache().energy(offset, entry.getValue()[0]);
				double last = thermal.referenceEnergyCache().energy(offset, entry.getValue()[1]);
				base = outward(base + (upper ? Math.max(first, last) : Math.min(first, last)), upper);
			}
			double specific = productRange(portion.massLow, portion.massHigh, sensible, upper);
			result = outward(result + outward(base + specific, upper), upper);
		}
		return result;
	}

	private PortionRange range(GasComponentAmount low, GasComponentAmount high) {
		GasComponentAmount any = high == null ? low : high;
		int handle = thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS, any.componentId());
		TreeMap<String, long[]> species = new TreeMap<>();
		if (low != null) for (GasComponentAmount.SpeciesAmount entry : low.species()) species.computeIfAbsent(entry.speciesId(), ignored -> new long[2])[0] = entry.amountQuanta();
		if (high != null) for (GasComponentAmount.SpeciesAmount entry : high.species()) species.computeIfAbsent(entry.speciesId(), ignored -> new long[2])[1] = entry.amountQuanta();
		return new PortionRange(thermal.material(handle), low == null ? 0.0 : low.mass(),
				high == null ? 0.0 : high.mass(), species);
	}

	private PortionRange merge(PortionRange first, PortionRange second) {
		TreeMap<String, long[]> species = new TreeMap<>();
		first.species.forEach((id, amounts) -> species.put(id, amounts.clone()));
		second.species.forEach((id, amounts) -> species.merge(id, amounts.clone(), (a, b) ->
				new long[] {Math.addExact(a[0], b[0]), Math.addExact(a[1], b[1])}));
		return new PortionRange(first.material, Math.max(0.0, down(first.massLow + second.massLow)),
				up(first.massHigh + second.massHigh), species);
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
	private static double productRange(double low, double high, double factor, boolean upper) {
		return outward((factor >= 0.0 == upper ? high : low) * factor, upper);
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
	private record PortionRange(ThermalMaterialDefinition material, double massLow, double massHigh,
	                            TreeMap<String, long[]> species) { }
}
