package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.BinaryVolumeAllocator;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.thermal.GasLiquidPhaseModel;
import cc.ecisr.mmm.thermal.ThermalCaloricBounds;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.thermal.ThermalPhaseTransition;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read-only envelopes for the actual ordinal boiling allocation; unresolved intervals remain searchable. */
final class GasBoilingBatchBounds {
	private record Marker(int ordinal, float volume, double solverMass, int thermalHandle,
	                      ChemicalMixtureRecord chemistry, ThermalCaloricBounds.PortionRange original) { }
	private record Donor(Marker marker, long volumeSvq, long capacity, long end, double prefixMass) { }
	private record Cut(ThermalCaloricBounds.PortionRange kept, Map<String, ThermalCaloricBounds.QuantaRange> moved,
	                   boolean noCarrier) { }

	private final PhaseChangeStageSupport support;
	private final ThermalPhaseTransition transition;
	private final ThermalDomainState.PhaseCandidateCell beforeCell;
	private final GasLiquidPhaseModel phase;
	private final ThermalCaloricBounds caloric;
	private final ChemicalParcelStore chemistry;
	private final boolean[] volatileSpecies;
	private final double liquidTemperature, oldGasEnergy, gasVolume;
	private final GasInventory oldGas;
	private final int gasThermalHandle;
	private final String gasCarrier;
	private final long nanosPerBatch;
	private final List<Marker> markers = new ArrayList<>();
	private final List<Donor> donors = new ArrayList<>();
	private final List<ThermalCaloricBounds.PortionRange> solidPortions = new ArrayList<>();

	GasBoilingBatchBounds(PhaseChangeStageSupport support, int cell, int node, int liquidHandle,
	                     ThermalPhaseTransition transition, ThermalDomainState.PhaseCandidateCell beforeCell,
	                     boolean[] volatileSpecies, double liquidTemperature, GasLiquidPhaseModel phase) {
		this.support = support; this.transition = transition; this.beforeCell = beforeCell;
		this.phase = phase; this.liquidTemperature = liquidTemperature;
		this.chemistry = support.chemistry();
		this.volatileSpecies = volatileSpecies == null ? null : volatileSpecies.clone();
		this.caloric = new ThermalCaloricBounds(support.thermal().registry());
		this.oldGas = support.gasState().network.inventory(node);
		this.oldGasEnergy = support.thermal().gasNodeEnergy(node, support.gasState().network);
		this.gasVolume = support.gasState().network.availableVolume(node);
		this.gasThermalHandle = support.thermal().registry().materialHandle(transition.upperEnergyMaterialId());
		this.gasCarrier = support.thermal().registry().material(gasThermalHandle).carrierId();
		this.nanosPerBatch = phase.gasBatch(transition, 1).standardVolumeNanos();
		LiquidParticleStore particles = support.state().particles();
		Map<Integer, Marker> byOrdinal = new HashMap<>();
		for (int ordinal = support.state().markerGrid().cellMarkerHead[cell]; ordinal >= 0;
		     ordinal = support.state().markerGrid().particleCellNext[ordinal]) {
			int handle = support.thermal().thermalMaterialHandleForFluid(particles.materialHandle(ordinal));
			ChemicalMixtureRecord mixture = chemistry == null ? null : chemistry.record(ordinal);
			Map<String, Long> species = mixture == null ? Map.of() : mixture.stableAmounts(chemistry.registry());
			Marker marker = new Marker(ordinal, particles.volume(ordinal), particles.mass(ordinal), handle, mixture,
					point(handle, support.state().unitScale().kilograms(particles.mass(ordinal)), species));
			markers.add(marker); byOrdinal.put(ordinal, marker);
		}
		long end = 0;
		for (int ordinal = support.state().markerGrid().cellMarkerTail[cell]; ordinal >= 0;
		     ordinal = support.state().markerGrid().particleCellPrevious[ordinal]) {
			if (particles.materialHandle(ordinal) != liquidHandle || chemistry != null && chemistry.indicator(ordinal)) continue;
			Marker marker = byOrdinal.get(ordinal);
			long volumeSvq = SolidVolume.cellVolumeSvqAtMost(marker.volume());
			if (volumeSvq == 0) continue;
			boolean volatilePresent = chemistry == null, nonVolatile = false;
			if (chemistry != null) for (int entry = 0; entry < marker.chemistry().entryCount(); entry++) {
				if (this.volatileSpecies[marker.chemistry().speciesHandle(entry)]) volatilePresent = true;
				else nonVolatile = true;
			}
			if (!volatilePresent || nonVolatile && volumeSvq <= 1) continue;
			long capacity = volumeSvq - (nonVolatile ? 1 : 0);
			end = Math.addExact(end, capacity);
			double prefixMass = support.state().unitScale().kilograms(marker.solverMass()) * ((double) capacity / volumeSvq);
			donors.add(new Donor(marker, volumeSvq, capacity, end, prefixMass));
		}
		for (ThermalStateFunction.InventoryPortion portion : beforeCell.unchangedSolids()) {
			solidPortions.add(point(portion.materialHandle(), portion.massMu(), portion.speciesQuanta()));
		}
	}

	boolean excluded(long low, long high) {
		if (low <= 0 || high < low) throw new IllegalArgumentException("Invalid boiling search interval");
		try {
			long firstVolume = Math.multiplyExact(low, transition.lowerVolumeSvqPerBatch());
			long lastVolume = Math.multiplyExact(high, transition.lowerVolumeSvqPerBatch());
			int first = donor(firstVolume), last = donor(lastVolume);
			if (first < 0) return true;
			// Cross-donor and complete-removal endpoints require separate envelopes or exact probes.
			if (first != last || low == high || lastVolume == donors.get(last).end()) return false;
			return excludedInsideDonor(low, high, firstVolume, lastVolume, last);
		} catch (IllegalArgumentException | ArithmeticException unresolved) {
			// A failed bound is not evidence that any candidate in the interval is unavailable.
			return false;
		}
	}

	private boolean excludedInsideDonor(long low, long high, long firstVolume, long lastVolume, int last) {
		ArrayList<ThermalCaloricBounds.PortionRange> remaining = new ArrayList<>(markers.size() + solidPortions.size());
		TreeMap<Integer, Cut> cuts = new TreeMap<>();
		TreeMap<String, ThermalCaloricBounds.QuantaRange> moved = new TreeMap<>();
		double lowMass = transition.massMuPerBatch().multiply(low).toDouble();
		double highMass = transition.massMuPerBatch().multiply(high).toDouble();
		for (int index = 0; index < last; index++) {
			Donor donor = donors.get(index);
			Cut cut = cut(donor, donor.capacity(), donor.capacity(), donor.prefixMass(), donor.prefixMass());
			if (cut.noCarrier()) return true;
			cuts.put(donor.marker().ordinal(), cut); mergeSpecies(moved, cut.moved());
			// Preserve the production sequence of subtraction from the requested batch mass.
			lowMass -= donor.prefixMass(); highMass -= donor.prefixMass();
		}
		Donor donor = donors.get(last);
		long previous = donor.end() - donor.capacity();
		if (!(highMass > 0)) return true;
		Cut tail = cut(donor, firstVolume - previous, lastVolume - previous, Math.max(0, lowMass), highMass);
		if (tail.noCarrier()) return true;
		cuts.put(donor.marker().ordinal(), tail); mergeSpecies(moved, tail.moved());
		for (Marker marker : markers) {
			Cut cut = cuts.get(marker.ordinal());
			if (cut == null) remaining.add(marker.original());
			else if (cut.kept() != null) remaining.add(cut.kept());
		}
		remaining.addAll(solidPortions);
		if (remaining.isEmpty()) return false;
		var batch = new ThermalCaloricBounds.PortionRange(gasThermalHandle,
				transition.massMuPerBatch().multiply(low).toDouble(), transition.massMuPerBatch().multiply(high).toDouble(), moved);
		double batchLower = caloric.energyAtTemperature(List.of(batch), liquidTemperature, false);
		double batchUpper = caloric.energyAtTemperature(List.of(batch), liquidTemperature, true);
		double gasLower = Math.nextDown(oldGasEnergy + batchLower);
		double gasUpper = Math.nextUp(oldGasEnergy + batchUpper);
		if (!Double.isFinite(gasLower) || !Double.isFinite(gasUpper)) return false;
		SignedDyadic cellUpper = SignedDyadic.sum(beforeCell.cellEnergy(), SignedDyadic.of(oldGasEnergy), SignedDyadic.of(gasLower).negate());
		List<ThermalCaloricBounds.PortionRange> gas = gasPortions(batch);
		double liquidUpper = caloric.temperatureBound(remaining, cellUpper, true, liquidTemperature);
		if (!Double.isFinite(liquidUpper)) return false;
		double gasTemperatureLower = caloric.temperatureBound(gas, gasLower, false, liquidTemperature);
		return pressureExcludes(low, high, liquidUpper, gasTemperatureLower);
	}

	private boolean pressureExcludes(long low, long high, double liquidUpper, double gasTemperatureLower) {
		long totalLower = Math.addExact(oldGas.standardVolumeNanos(), Math.multiplyExact(low, nanosPerBatch));
		long totalUpper = Math.addExact(oldGas.standardVolumeNanos(), Math.multiplyExact(high, nanosPerBatch));
		GasComponentAmount vapor = oldGas.component(gasCarrier);
		long vaporLower = Math.addExact(vapor == null ? 0 : vapor.standardVolumeNanos(), Math.multiplyExact(low, nanosPerBatch));
		// These are the actual pressure and ratio operation sequences, evaluated at bounding inputs.
		double constant = GasRegistry.STANDARD_PRESSURE_PA * AirNetwork.cubicMeters(totalLower) / GasRegistry.STANDARD_TEMPERATURE_K;
		double pressureLower = Math.nextDown(constant * gasTemperatureLower / gasVolume);
		double partialLower = Math.max(0, Math.nextDown(Math.max(0, pressureLower) * ((double) vaporLower / totalUpper)));
		double saturationUpper = phase.saturationPressureBound(transition, liquidUpper, true);
		return saturationUpper < partialLower;
	}

	private Cut cut(Donor donor, long takenLow, long takenHigh, double massLow, double massHigh) {
		Marker marker = donor.marker();
		boolean full = takenLow == donor.volumeSvq() && takenHigh == donor.volumeSvq()
				&& donor.volumeSvq() == SolidVolume.cellVolumeSvqAtLeast(marker.volume());
		float keptLow = full ? 0 : SolidVolume.addCellVolumeSvqExact(marker.volume(), -takenHigh);
		float keptHigh = full ? 0 : SolidVolume.addCellVolumeSvqExact(marker.volume(), -takenLow);
		TreeMap<String, ThermalCaloricBounds.QuantaRange> moved = new TreeMap<>(), kept = new TreeMap<>();
		long movedMaximum = 0, keptMaximum = 0;
		if (marker.chemistry() != null) for (int entry = 0; entry < marker.chemistry().entryCount(); entry++) {
			int species = marker.chemistry().speciesHandle(entry);
			long original = marker.chemistry().amountAt(entry), first = 0, last = 0;
			if (volatileSpecies[species]) {
				first = full ? original : allocated(original, marker.ordinal(), keptHigh, takenLow);
				last = full ? original : allocated(original, marker.ordinal(), keptLow, takenHigh);
			}
			String id = chemistry.registry().species(species).id();
			if (last > 0) moved.put(id, new ThermalCaloricBounds.QuantaRange(first, last));
			if (original - first > 0) kept.put(id, new ThermalCaloricBounds.QuantaRange(original - last, original - first));
			movedMaximum = Math.addExact(movedMaximum, last); keptMaximum = Math.addExact(keptMaximum, original - first);
		}
		boolean noCarrier = chemistry != null && (movedMaximum == 0 || !full && keptMaximum == 0);
		if (full) return new Cut(null, moved, noCarrier);
		// Only positive, finite retained masses can be candidates; zero broadens the lower envelope.
		double retainedLow = marker.solverMass() - support.state().unitScale().solverMass(massHigh);
		double retainedHigh = marker.solverMass() - support.state().unitScale().solverMass(massLow);
		if (!(retainedHigh > 0)) return new Cut(null, moved, true);
		return new Cut(new ThermalCaloricBounds.PortionRange(marker.thermalHandle(),
				Math.max(0, support.state().unitScale().kilograms(retainedLow)),
				support.state().unitScale().kilograms(retainedHigh), kept), moved, noCarrier);
	}

	private static long allocated(long original, int ordinal, float kept, long movedSvq) {
		return BinaryVolumeAllocator.allocate(original, new float[]{kept, SolidVolume.toCellVolume(movedSvq)},
				new int[]{ordinal, Integer.MAX_VALUE})[1];
	}

	private int donor(long volume) {
		for (int index = 0; index < donors.size(); index++) if (volume <= donors.get(index).end()) return index;
		return -1;
	}

	private List<ThermalCaloricBounds.PortionRange> gasPortions(ThermalCaloricBounds.PortionRange batch) {
		ArrayList<ThermalCaloricBounds.PortionRange> result = new ArrayList<>();
		boolean merged = false;
		for (GasComponentAmount component : oldGas.components()) {
			int handle = support.thermal().registry().materialHandle(
					cc.ecisr.mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS, component.componentId());
			TreeMap<String, Long> species = new TreeMap<>();
			for (GasComponentAmount.SpeciesAmount amount : component.species()) species.put(amount.speciesId(), amount.amountQuanta());
			var range = point(handle, component.mass(), species);
			if (component.componentId().equals(gasCarrier)) { range = caloric.merge(range, batch); merged = true; }
			result.add(range);
		}
		if (!merged) result.add(batch);
		return result;
	}

	private static ThermalCaloricBounds.PortionRange point(int handle, double mass, Map<String, Long> species) {
		TreeMap<String, ThermalCaloricBounds.QuantaRange> ranges = new TreeMap<>();
		species.forEach((id, amount) -> ranges.put(id, new ThermalCaloricBounds.QuantaRange(amount, amount)));
		return new ThermalCaloricBounds.PortionRange(handle, mass, mass, ranges);
	}

	private static void mergeSpecies(Map<String, ThermalCaloricBounds.QuantaRange> target,
	                                 Map<String, ThermalCaloricBounds.QuantaRange> added) {
		added.forEach((id, amounts) -> target.merge(id, amounts, (a, b) -> new ThermalCaloricBounds.QuantaRange(
				Math.addExact(a.low(), b.low()), Math.addExact(a.high(), b.high()))));
	}
}
