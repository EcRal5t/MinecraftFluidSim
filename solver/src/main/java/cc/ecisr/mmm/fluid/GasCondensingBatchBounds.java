package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.thermal.GasLiquidPhaseModel;
import cc.ecisr.mmm.thermal.ThermalCaloricBounds;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.thermal.ThermalPhaseTransition;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read-only envelopes for explicit condensation mass columns and every possible liquid receiver. */
final class GasCondensingBatchBounds {
	/** Limit the number of separate root solves; larger receiver sets use one wider envelope. */
	private static final int MAX_SEPARATE_RECEIVERS = 3;
	private record Liquid(ThermalCaloricBounds.PortionRange original, double solverMass, boolean receiver) { }
	private final PhaseChangeStageSupport support;
	private final int node, gasHandle, liquidHandle;
	private final String gasCarrier;
	private final ThermalPhaseTransition transition;
	private final ThermalDomainState.PhaseCandidateCell beforeCell;
	private final GasLiquidPhaseModel phase;
	private final ThermalCaloricBounds caloric;
	private final GasInventory oldGas;
	private final GasComponentAmount vapor;
	private final double oldGasEnergy, gasTemperature, liquidTemperature, gasVolume;
	private final long nanosPerBatch;
	private final List<Liquid> liquids = new ArrayList<>();
	private final List<ThermalCaloricBounds.PortionRange> solids = new ArrayList<>(), otherGas = new ArrayList<>();
	private final int receiverCount;

	GasCondensingBatchBounds(PhaseChangeStageSupport support, int cell, int node, int liquidHandle,
	                         ThermalPhaseTransition transition, ThermalDomainState.PhaseCandidateCell beforeCell,
	                         GasLiquidPhaseModel phase) {
		this.support = support; this.node = node; this.transition = transition;
		this.beforeCell = beforeCell; this.phase = phase;
		var thermal = support.thermal();
		this.caloric = new ThermalCaloricBounds(thermal.registry());
		this.gasHandle = thermal.registry().materialHandle(transition.upperEnergyMaterialId());
		this.liquidHandle = thermal.thermalMaterialHandleForFluid(liquidHandle);
		this.gasCarrier = thermal.registry().material(gasHandle).carrierId();
		this.oldGas = support.gasState().network.inventory(node);
		this.vapor = oldGas.component(gasCarrier);
		this.oldGasEnergy = thermal.gasNodeEnergy(node, support.gasState().network);
		this.gasTemperature = thermal.gasTemperature(node, support.gasState().network);
		this.liquidTemperature = thermal.phaseScanTemperatureInCell(cell);
		this.gasVolume = support.gasState().network.availableVolume(node);
		this.nanosPerBatch = phase.gasBatch(transition, 1).standardVolumeNanos();
		var particles = support.state().particles();
		var chemistry = support.chemistry();
		int receivers = 0;
		for (int marker = support.state().markerGrid().cellMarkerHead[cell]; marker >= 0;
		     marker = support.state().markerGrid().particleCellNext[marker]) {
			boolean receiver = particles.materialHandle(marker) == liquidHandle
					&& (chemistry == null || !chemistry.indicator(marker));
			if (receiver) receivers++;
			liquids.add(new Liquid(point(thermal.thermalMaterialHandleForFluid(particles.materialHandle(marker)),
					support.state().unitScale().kilograms(particles.mass(marker)),
					chemistry == null ? Map.of() : chemistry.record(marker).stableAmounts(chemistry.registry())),
					particles.mass(marker), receiver));
		}
		this.receiverCount = receivers;
		for (ThermalStateFunction.InventoryPortion portion : beforeCell.unchangedSolids()) {
			solids.add(point(portion.materialHandle(), portion.massMu(), portion.speciesQuanta()));
		}
		for (GasComponentAmount component : oldGas.components()) if (!component.componentId().equals(gasCarrier)) {
			otherGas.add(point(thermal.registry().materialHandle(
					cc.ecisr.mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS, component.componentId()),
					component.mass(), amounts(component)));
		}
	}

	boolean excluded(long low, long high) {
		if (low <= 0 || high < low) throw new IllegalArgumentException("Invalid condensation search interval");
		try {
			long firstNanos = Math.multiplyExact(low, nanosPerBatch), lastNanos = Math.multiplyExact(high, nanosPerBatch);
			// Vacuum has no temperature; it remains an exact candidate rather than a finite bound.
			if (vapor == null || lastNanos > vapor.standardVolumeNanos()
					|| lastNanos == oldGas.standardVolumeNanos()) return false;
			GasComponentAmount first = support.gasState().network.previewComponentTake(node, gasCarrier, firstNanos)
					.first().component(gasCarrier);
			GasComponentAmount last = support.gasState().network.previewComponentTake(node, gasCarrier, lastNanos)
					.first().component(gasCarrier);
			double requestedLow = transition.massMuPerBatch().multiply(low).toDouble();
			double requestedHigh = transition.massMuPerBatch().multiply(high).toDouble();
			double movedLow = requestedLow, movedHigh = requestedHigh;
			// A whole component take preserves its stored columns, including historical mass rounding.
			if (lastNanos == vapor.standardVolumeNanos()) {
				movedLow = Math.min(movedLow, vapor.mass()); movedHigh = Math.max(movedHigh, vapor.mass());
			}
			if (firstNanos == vapor.standardVolumeNanos()) movedLow = movedHigh = vapor.mass();
			// Two-child largest remainder is nearest integer Q*n/N, with half ties to the first child.
			// It is monotone in n, so actual endpoint splits bound every interior species column.
			Map<String, ThermalCaloricBounds.QuantaRange> movedSpecies = speciesRange(first, last);
			var batch = new ThermalCaloricBounds.PortionRange(gasHandle, movedLow, movedHigh, movedSpecies);
			double batchLower = caloric.energyAtTemperature(List.of(batch), gasTemperature, false);
			double batchUpper = caloric.energyAtTemperature(List.of(batch), gasTemperature, true);
			double gasLower = Math.nextDown(oldGasEnergy - batchUpper);
			double gasUpper = Math.nextUp(oldGasEnergy - batchLower);
			if (!Double.isFinite(gasLower) || !Double.isFinite(gasUpper)) return false;
			ArrayList<ThermalCaloricBounds.PortionRange> gas = new ArrayList<>(otherGas);
			if (firstNanos < vapor.standardVolumeNanos()) {
				double retainedHigh = vapor.mass() - requestedLow;
				double retainedLow = lastNanos == vapor.standardVolumeNanos() ? 0 : vapor.mass() - requestedHigh;
				if (!(retainedHigh > 0)) return false;
				TreeMap<String, ThermalCaloricBounds.QuantaRange> retainedSpecies = new TreeMap<>();
				for (var amount : vapor.species()) {
					var taken = movedSpecies.get(amount.speciesId());
					long least = amount.amountQuanta() - (taken == null ? 0 : taken.high());
					long most = amount.amountQuanta() - (taken == null ? 0 : taken.low());
					if (most > 0) retainedSpecies.put(amount.speciesId(), new ThermalCaloricBounds.QuantaRange(least, most));
				}
				gas.add(new ThermalCaloricBounds.PortionRange(gasHandle, Math.max(0, retainedLow), retainedHigh, retainedSpecies));
			}
			double gasUpperTemperature = caloric.temperatureBound(gas, gasUpper, true, gasTemperature);
			if (!Double.isFinite(gasUpperTemperature)) return false;
			SignedDyadic cellLower = SignedDyadic.sum(beforeCell.cellEnergy(), SignedDyadic.of(oldGasEnergy),
					SignedDyadic.of(gasUpper).negate());
			SignedDyadic cellUpper = SignedDyadic.sum(beforeCell.cellEnergy(), SignedDyadic.of(oldGasEnergy),
					SignedDyadic.of(gasLower).negate());
			double liquidLower = liquidLowerTemperature(batch, cellLower, cellUpper);
			double saturationLower = phase.saturationPressureBound(transition, liquidLower, false);
			long totalLow = oldGas.standardVolumeNanos() - lastNanos;
			long totalHigh = oldGas.standardVolumeNanos() - firstNanos;
			long vaporHigh = vapor.standardVolumeNanos() - firstNanos;
			// Preserve the production pressure and partial-fraction operation order, with outward rounding.
			double constant = up(up(GasRegistry.STANDARD_PRESSURE_PA * AirNetwork.cubicMeters(totalHigh))
					/ GasRegistry.STANDARD_TEMPERATURE_K);
			double pressureUpper = up(up(constant * gasUpperTemperature) / gasVolume);
			double ratioUpper = Math.min(1.0, up((double) vaporHigh / totalLow));
			double partialUpper = up(pressureUpper * ratioUpper);
			return Double.isFinite(saturationLower) && Double.isFinite(partialUpper) && saturationLower > partialUpper;
		} catch (IllegalArgumentException | ArithmeticException unresolved) {
			// Invalid or wide envelopes provide no evidence of infeasibility for an integer candidate.
			return false;
		}
	}

	private double liquidLowerTemperature(ThermalCaloricBounds.PortionRange batch, SignedDyadic low, SignedDyadic high) {
		if (receiverCount <= MAX_SEPARATE_RECEIVERS) {
			// Separate alternatives keep the usual few-marker interval tight; unavailable alternatives only widen it.
			double lower = caloric.assignedTemperatureBound(liquidPortions(batch, -1, false), low, high, false, liquidTemperature);
			for (int target = 0; target < liquids.size(); target++) if (liquids.get(target).receiver()) {
				lower = Math.min(lower, caloric.assignedTemperatureBound(liquidPortions(batch, target, false), low, high, false, liquidTemperature));
			}
			return lower;
		}
		// More receivers use one relaxed box: each may receive zero to the entire incoming column.
		return caloric.assignedTemperatureBound(liquidPortions(batch, -1, true), low, high, false, liquidTemperature);
	}

	private List<ThermalCaloricBounds.PortionRange> liquidPortions(
			ThermalCaloricBounds.PortionRange batch, int target, boolean relaxed) {
		ArrayList<ThermalCaloricBounds.PortionRange> result = new ArrayList<>(liquids.size() + solids.size() + 1);
		for (int index = 0; index < liquids.size(); index++) {
			Liquid liquid = liquids.get(index);
			if (index == target || relaxed && liquid.receiver()) {
				double lowMass = relaxed ? liquid.original().massLow() : mergedMass(liquid, batch.massLow());
				double highMass = mergedMass(liquid, batch.massHigh());
				TreeMap<String, ThermalCaloricBounds.QuantaRange> species = new TreeMap<>(liquid.original().species());
				batch.species().forEach((id, amount) -> species.merge(id,
						new ThermalCaloricBounds.QuantaRange(relaxed ? 0 : amount.low(), amount.high()),
						(a, b) -> new ThermalCaloricBounds.QuantaRange(Math.addExact(a.low(), b.low()), Math.addExact(a.high(), b.high()))));
				result.add(new ThermalCaloricBounds.PortionRange(liquid.original().materialHandle(), lowMass, highMass, species));
			} else result.add(liquid.original());
		}
		result.addAll(solids);
		if (target < 0) {
			TreeMap<String, ThermalCaloricBounds.QuantaRange> species = new TreeMap<>();
			batch.species().forEach((id, amount) -> species.put(id,
					new ThermalCaloricBounds.QuantaRange(relaxed ? 0 : amount.low(), amount.high())));
			result.add(new ThermalCaloricBounds.PortionRange(liquidHandle, relaxed ? 0 : batch.massLow(), batch.massHigh(), species));
		}
		return result;
	}

	private double mergedMass(Liquid liquid, double addedMass) {
		return support.state().unitScale().kilograms(liquid.solverMass() + support.state().unitScale().solverMass(addedMass));
	}

	private static Map<String, Long> amounts(GasComponentAmount component) {
		TreeMap<String, Long> result = new TreeMap<>();
		for (var amount : component.species()) result.put(amount.speciesId(), amount.amountQuanta());
		return result;
	}

	private static Map<String, ThermalCaloricBounds.QuantaRange> speciesRange(GasComponentAmount first, GasComponentAmount last) {
		Map<String, Long> firstAmounts = amounts(first), lastAmounts = amounts(last);
		TreeMap<String, ThermalCaloricBounds.QuantaRange> result = new TreeMap<>();
		for (var amount : lastAmounts.entrySet()) result.put(amount.getKey(),
				new ThermalCaloricBounds.QuantaRange(firstAmounts.getOrDefault(amount.getKey(), 0L), amount.getValue()));
		return result;
	}

	private static ThermalCaloricBounds.PortionRange point(int handle, double mass, Map<String, Long> species) {
		TreeMap<String, ThermalCaloricBounds.QuantaRange> ranges = new TreeMap<>();
		species.forEach((id, amount) -> ranges.put(id, new ThermalCaloricBounds.QuantaRange(amount, amount)));
		return new ThermalCaloricBounds.PortionRange(handle, mass, mass, ranges);
	}

	private static double up(double value) { return Math.nextUp(value); }
}
