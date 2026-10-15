package mmm.thermal;

import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/** One source's equilibrium-energy line and a common distance bound for integer phase extents. */
public record ThermalPhaseExtentCurve(double energyOffset, double energyPerMass,
                                     double maximumHeatCapacity, double energyAllowance) {
	public boolean beforeRoot(double movedMass) {
		double energy = Math.fma(movedMass, energyPerMass, energyOffset);
		return energyPerMass > 0 ? energy <= 0 : energy >= 0;
	}

	public double distanceLower(double movedMass) {
		double excess = Math.max(0, Math.nextDown(Math.abs(Math.fma(movedMass, energyPerMass, energyOffset)) - energyAllowance));
		return Math.max(0, Math.nextDown(excess / maximumHeatCapacity));
	}

	static ThermalPhaseExtentCurve prepare(ThermalRegistry registry, ThermalStateFunction state,
			ThermalDomainState.PhaseCandidateCell source, java.util.List<ThermalStateFunction.InventoryPortion> initialLiquid,
			int oldHandle, int newHandle, double equilibrium, double maximumMovedMass, double massError) {
		if (!(Double.isFinite(equilibrium) && equilibrium >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K
				&& Double.isFinite(maximumMovedMass) && maximumMovedMass > 0 && Double.isFinite(massError) && massError >= 0))
			throw new IllegalArgumentException("Phase extent curve source is invalid");
		var oldMaterial = registry.material(oldHandle); var newMaterial = registry.material(newHandle);
		var portions = new ArrayList<>(initialLiquid); portions.addAll(source.unchangedSolids());
		var bounds = new ThermalCaloricBounds(registry);
		var newSpecies = new TreeMap<String, Long>();
		SignedDyadic modeled = SignedDyadic.ZERO, totalMass = SignedDyadic.ZERO, maximumCapacity = SignedDyadic.ZERO;
		SignedDyadic absoluteReference = SignedDyadic.ZERO, coldMagnitude = SignedDyadic.ZERO;
		double referenceError = 0;
		SignedDyadic newMass = SignedDyadic.of(maximumMovedMass).add(SignedDyadic.of(massError));
		double minimumCv = minimumCv(newMaterial); int newOwners = 1;
		for (var portion : portions) {
			var material = registry.material(portion.materialHandle()); var mass = SignedDyadic.of(portion.massMu());
			totalMass = totalMass.add(mass);
			maximumCapacity = maximumCapacity.add(mass.multiply(SignedDyadic.of(maximumCv(material))));
			minimumCv = Math.min(minimumCv, minimumCv(material));
			double base = state.baseEnergy(portion.materialHandle(), portion.massMu(), portion.speciesQuanta());
			modeled = modeled.add(SignedDyadic.of(base)).add(ThermalContactExchange.exactEnergyChangeBetween(
					material, mass, material.referenceTemperatureK(), equilibrium));
			coldMagnitude = coldMagnitude.add(abs(ThermalContactExchange.exactEnergyChangeBetween(material, mass,
					material.referenceTemperatureK(), ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)));
			if (portion.materialHandle() != oldHandle && portion.materialHandle() != newHandle) {
				absoluteReference = absoluteReference.add(abs(SignedDyadic.of(base))); continue;
			}
			var range = range(portion.materialHandle(), portion.massMu(), portion.speciesQuanta());
			double reference = referenceMagnitude(bounds, range);
			if (!Double.isFinite(reference)) return null;
			absoluteReference = absoluteReference.add(SignedDyadic.of(reference));
			referenceError = add(referenceError, 2 * bounds.referenceRoundingBound(range));
			if (portion.materialHandle() == newHandle) { newMass = newMass.add(mass); newOwners++; }
			for (var entry : portion.speciesQuanta().entrySet()) {
				if (portion.materialHandle() == oldHandle && (!registry.hasSpeciesOffset(newHandle, entry.getKey())
						|| !registry.speciesOffset(oldHandle, entry.getKey()).referenceInternalEnergyEuPerAu()
						.equals(registry.speciesOffset(newHandle, entry.getKey()).referenceInternalEnergyEuPerAu()))) return null;
				newSpecies.merge(entry.getKey(), entry.getValue(), Math::addExact);
			}
		}
		double maximumNewMass = upper(newMass);
		if (!Double.isFinite(maximumNewMass)) return null;
		var addedMass = SignedDyadic.of(Math.nextUp(maximumMovedMass + massError));
		maximumCapacity = maximumCapacity.add(addedMass.multiply(SignedDyadic.of(maximumCv(newMaterial))));
		coldMagnitude = coldMagnitude.add(abs(ThermalContactExchange.exactEnergyChangeBetween(newMaterial, addedMass,
				newMaterial.referenceTemperatureK(), ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)));
		var newRange = range(newHandle, maximumNewMass, newSpecies);
		double newReference = referenceMagnitude(bounds, newRange);
		if (!Double.isFinite(newReference)) return null;
		absoluteReference = absoluteReference.add(SignedDyadic.of(newReference).multiply(SignedDyadic.of((long) newOwners, 0)));
		referenceError = add(referenceError, newOwners * bounds.referenceRoundingBound(newRange));

		SignedDyadic oldSpecific = specific(oldMaterial, equilibrium), newSpecific = specific(newMaterial, equilibrium);
		SignedDyadic exactSlope = newSpecific.subtract(oldSpecific), exactOffset = modeled.subtract(source.cellEnergy());
		double slope = exactSlope.toDouble(), offset = exactOffset.toDouble(), capacity = upper(maximumCapacity);
		if (!(Double.isFinite(slope) && slope != 0 && Double.isFinite(offset) && Double.isFinite(capacity) && capacity > 0)) return null;
		SignedDyadic lowerMass = totalMass.subtract(SignedDyadic.of(massError).multiply(SignedDyadic.of(2L, 0)));
		if (lowerMass.signum() <= 0) return null;
		double lowerCapacity = Math.nextDown(lowerMass.multiply(SignedDyadic.of(minimumCv)).toDouble());
		if (!(lowerCapacity > 0)) return null;
		// This source-wide positive-temperature bound also covers capacity-forced extents.
		double temperature = Math.nextUp(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K + Math.nextUp(
				upper(abs(source.cellEnergy()).add(absoluteReference).add(coldMagnitude)) / lowerCapacity));
		temperature = Math.nextUp(temperature);
		if (!Double.isFinite(temperature)) return null;
		SignedDyadic sensibleMagnitude = SignedDyadic.ZERO;
		for (var portion : portions) {
			var material = registry.material(portion.materialHandle()); var mass = SignedDyadic.of(portion.massMu());
			sensibleMagnitude = sensibleMagnitude.add(sensibleMagnitude(material, mass, temperature));
		}
		sensibleMagnitude = sensibleMagnitude.add(sensibleMagnitude(newMaterial, addedMass, temperature));
		SignedDyadic pivotMagnitude = abs(source.cellEnergy()).add(absoluteReference.add(sensibleMagnitude)
				.multiply(SignedDyadic.of(2L, 0))).add(SignedDyadic.of((long) portions.size() + 1, -1074));
		double error = add(referenceError, upper(SignedDyadic.of(massError).multiply(abs(oldSpecific).add(abs(newSpecific)))));
		error = add(error, Math.ulp(Math.nextUp(pivotMagnitude.toDouble())));
		// Include the common inverse's temperature rounding and its existing minimum-temperature allowance.
		error = add(error, Math.nextUp(capacity * (2 * Math.ulp(temperature))));
		error = add(error, 16 * Math.ulp(upper(absoluteReference.add(coldMagnitude))));
		SignedDyadic lineMagnitude = abs(exactOffset).add(SignedDyadic.of(maximumMovedMass).multiply(abs(exactSlope)));
		error = add(error, upper(abs(exactOffset.subtract(SignedDyadic.of(offset)))));
		error = add(error, upper(SignedDyadic.of(maximumMovedMass).multiply(abs(exactSlope.subtract(SignedDyadic.of(slope))))));
		error = add(error, Math.ulp(upper(lineMagnitude)));
		return Double.isFinite(error) ? new ThermalPhaseExtentCurve(offset, slope, capacity, error) : null;
	}

	private static SignedDyadic sensibleMagnitude(ThermalMaterialDefinition material, SignedDyadic mass, double high) {
		var cold = abs(ThermalContactExchange.exactEnergyChangeBetween(material, mass,
				material.referenceTemperatureK(), ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K));
		var hot = abs(ThermalContactExchange.exactEnergyChangeBetween(material, mass, material.referenceTemperatureK(), high));
		return cold.compareTo(hot) >= 0 ? cold : hot;
	}
	private static ThermalCaloricBounds.PortionRange range(int handle, double mass, Map<String, Long> species) {
		var ranges = new TreeMap<String, ThermalCaloricBounds.QuantaRange>();
		species.forEach((id, amount) -> ranges.put(id, new ThermalCaloricBounds.QuantaRange(0, amount)));
		return new ThermalCaloricBounds.PortionRange(handle, 0, mass, ranges);
	}
	private static double referenceMagnitude(ThermalCaloricBounds bounds, ThermalCaloricBounds.PortionRange range) {
		return Math.max(Math.abs(bounds.referenceEnergyBound(range, false)), Math.abs(bounds.referenceEnergyBound(range, true)));
	}
	private static SignedDyadic specific(ThermalMaterialDefinition material, double temperature) {
		return SignedDyadic.of(material.referenceSpecificEnergyEuPerMu()).add(ThermalContactExchange.exactEnergyChangeBetween(
				material, 1, material.referenceTemperatureK(), temperature));
	}
	private static double minimumCv(ThermalMaterialDefinition material) {
		return material.caloricSegments().stream().mapToDouble(ThermalMaterialDefinition.CaloricSegment::specificHeatEuPerMuK).min().orElseThrow();
	}
	private static double maximumCv(ThermalMaterialDefinition material) {
		return material.caloricSegments().stream().mapToDouble(ThermalMaterialDefinition.CaloricSegment::specificHeatEuPerMuK).max().orElseThrow();
	}
	private static SignedDyadic abs(SignedDyadic value) { return value.signum() < 0 ? SignedDyadic.ZERO.subtract(value) : value; }
	private static double upper(SignedDyadic value) { return Math.nextUp(value.toDouble()); }
	private static double add(double first, double second) { return Math.nextUp(first + second); }
}
