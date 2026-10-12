package mmm.fluid;

import mmm.thermal.CoherentPhaseStore;
import mmm.thermal.ThermalCaloricBounds;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalPhaseTransition;
import mmm.thermal.ThermalStateFunction;
import mmm.units.ProportionalDoubleSplit;
import mmm.units.SignedDyadic;
import mmm.units.SolverUnitScale;
import mmm.granular.SolidVolume;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Conservative temperatures for the stage's inert, single-source condensed candidates. */
final class CondensedPhaseBatchBounds {
	private final ThermalCaloricBounds caloric;
	private final PhaseChangeStageSupport.PhaseCellInventory before;
	private final ThermalPhaseTransition transition;
	private final boolean freezing;
	private final double initialTemperature;
	private final int lowerHandle, upperHandle;
	private final List<ThermalCaloricBounds.PortionRange> fixed = new ArrayList<>();
	private final SignedDyadic energy;
	private final CoherentPhaseStore.Group source;
	private final boolean enabled;
	private final SolverUnitScale units;
	private double sourceSolverMass;
	private long sourceLiquidSvq, sourceLiquidCeilSvq;

	CondensedPhaseBatchBounds(PhaseChangeStageSupport support, int cell, int liquidHandle,
	                         ThermalPhaseTransition transition,
	                         PhaseChangeStageSupport.PhaseCellInventory before,
	                         List<ThermalStateFunction.InventoryPortion> unchanged,
	                         boolean freezing, double initialTemperature) {
		this.before = before;
		this.transition = transition;
		this.freezing = freezing;
		this.initialTemperature = initialTemperature;
		units = support.state().unitScale();
		var thermal = support.thermal();
		caloric = new ThermalCaloricBounds(thermal.registry());
		lowerHandle = thermal.registry().materialHandle(transition.lowerEnergyMaterialId());
		upperHandle = thermal.thermalMaterialHandleForFluid(liquidHandle);
		int sourceIndex = thermal.coherentPhases().find(cell, transition.lowerEnergyMaterialId());
		source = sourceIndex < 0 ? null : thermal.coherentPhases().group(sourceIndex);
		int liquidSources = 0;
		for (int marker = 0; marker < support.state().particles().count(); marker++) {
			if (support.particleCell(marker) != cell) continue;
			if (support.state().particles().materialHandle(marker) != liquidHandle) continue;
			var particles = support.state().particles();
			if (freezing) {
				liquidSources++;
				sourceSolverMass = particles.mass(marker);
				sourceLiquidSvq = SolidVolume.cellVolumeSvqAtMost(particles.volume(marker));
				sourceLiquidCeilSvq = SolidVolume.cellVolumeSvqAtLeast(particles.volume(marker));
			} else fixed.add(new ThermalCaloricBounds.PortionRange(upperHandle,
					units.kilograms(particles.mass(marker)), units.kilograms(particles.mass(marker)), Map.of()));
		}
		enabled = support.chemistry() == null && (freezing ? liquidSources == 1
				: source != null && source.composition() == null);
		if (!enabled) { energy = SignedDyadic.ZERO; return; }
		var prepared = thermal.preparePhaseCandidateCell(cell, support.state().thermalMarkers());
		energy = prepared.cellEnergy();
		for (var portion : unchanged) fixed.add(point(portion));
		for (var portion : prepared.unchangedSolids()) {
			if (thermal.registry().material(portion.materialHandle()).carrierKind()
					== ThermalMaterialDefinition.CarrierKind.GRANULAR) fixed.add(point(portion));
		}
	}

	boolean excluded(long low, long high, double acceptedTemperature, double equilibrium) {
		if (!enabled || low <= 0 || high < low) return false;
		double lowerMin, lowerMax, upperMin, upperMax;
		if (freezing) {
			double small = transition.massMuPerBatch().toDouble() * low;
			double large = transition.massMuPerBatch().toDouble() * high;
			lowerMin = before.lowerMass() + small;
			lowerMax = before.lowerMass() + large;
			upperMin = retainedLiquidMass(high, large);
			upperMax = retainedLiquidMass(low, small);
		} else {
			long first = Math.multiplyExact(low, transition.lowerVolumeSvqPerBatch());
			long last = Math.multiplyExact(high, transition.lowerVolumeSvqPerBatch());
			if (last > source.occupiedVolumeSvq()) return false;
			var small = ProportionalDoubleSplit.of(source.mass(), first, source.occupiedVolumeSvq());
			var large = ProportionalDoubleSplit.of(source.mass(), last, source.occupiedVolumeSvq());
			lowerMin = large.remaining(); lowerMax = small.remaining();
			upperMin = units.kilograms(units.solverMass(small.removed()));
			upperMax = units.kilograms(units.solverMass(large.removed()));
		}
		if (!Double.isFinite(lowerMax) || !Double.isFinite(upperMax)) return false;
		var portions = new ArrayList<ThermalCaloricBounds.PortionRange>(fixed.size() + 2);
		if (lowerMax > 0) portions.add(new ThermalCaloricBounds.PortionRange(
				lowerHandle, lowerMin, lowerMax, Map.of()));
		if (upperMax > 0) portions.add(new ThermalCaloricBounds.PortionRange(
				upperHandle, upperMin, upperMax, Map.of()));
		portions.addAll(fixed);
		double minimum = caloric.temperatureBound(portions, energy, false, initialTemperature);
		double maximum = caloric.temperatureBound(portions, energy, true, initialTemperature);
		if (!(Double.isFinite(minimum) && Double.isFinite(maximum) && minimum <= maximum)) return false;
		// The actual assignment rounds one exact energy remainder. Bound its magnitude
		// from all modeled owner energies, then include that single-rounding error in
		// the target. The final raw inventory, rather than its requested LTE root, ranks candidates.
		double remainderMagnitude = Math.nextUp(Math.abs(energy.toDouble()));
		for (var portion : portions) {
			double lowEnergy = caloric.energyAtTemperature(List.of(portion), minimum, false);
			double highEnergy = caloric.energyAtTemperature(List.of(portion), maximum, true);
			remainderMagnitude = Math.nextUp(remainderMagnitude + Math.max(Math.abs(lowEnergy), Math.abs(highEnergy)));
		}
		if (!Double.isFinite(remainderMagnitude)) return false;
		var roundoff = SignedDyadic.of(Math.ulp(remainderMagnitude)).multiply(SignedDyadic.of(0.5));
		minimum = caloric.temperatureBound(portions, energy.subtract(roundoff), false, initialTemperature);
		maximum = caloric.temperatureBound(portions, energy.add(roundoff), true, initialTemperature);
		if (!(Double.isFinite(minimum) && Double.isFinite(maximum) && minimum <= maximum)) return false;
		double distance = minimum > equilibrium ? Math.nextDown(minimum - equilibrium)
				: maximum < equilibrium ? Math.nextDown(equilibrium - maximum) : 0.0;
		// The subtraction is rounded downward. Strict improvement requires an even
		// smaller distance by its additional acceptance threshold.
		return distance >= Math.abs(acceptedTemperature - equilibrium);
	}

	private double retainedLiquidMass(long batches, double requestedMass) {
		long requestedSvq = Math.multiplyExact(batches, transition.upperVolumeSvqPerBatch());
		if (requestedSvq == sourceLiquidSvq && sourceLiquidSvq == sourceLiquidCeilSvq) return 0.0;
		return units.kilograms(Math.max(0.0, sourceSolverMass - units.solverMass(requestedMass)));
	}

	private static ThermalCaloricBounds.PortionRange point(ThermalStateFunction.InventoryPortion portion) {
		var species = new TreeMap<String, ThermalCaloricBounds.QuantaRange>();
		portion.speciesQuanta().forEach((id, amount) -> species.put(id,
				new ThermalCaloricBounds.QuantaRange(amount, amount)));
		return new ThermalCaloricBounds.PortionRange(portion.materialHandle(),
				portion.massMu(), portion.massMu(), species);
	}
}
