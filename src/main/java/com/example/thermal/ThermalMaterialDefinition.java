package com.example.thermal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stable caloric model for one material carrier; temperature is always derived from total internal energy. */
public record ThermalMaterialDefinition(
		String id,
		CarrierKind carrierKind,
		String carrierId,
		double referenceTemperatureK,
		double referenceSpecificEnergyEuPerMu,
		double conductivityEuPerSecondMeterK,
		List<CaloricSegment> caloricSegments
) {
	public enum CarrierKind { LIQUID, GRANULAR, COHERENT_SOLID, GAS }

	public record CaloricSegment(double minimumTemperatureK, double maximumTemperatureK,
	                              double specificHeatEuPerMuK) {
		public CaloricSegment {
			if (!(Double.isFinite(minimumTemperatureK) && Double.isFinite(maximumTemperatureK)
					&& minimumTemperatureK >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K
					&& maximumTemperatureK > minimumTemperatureK)) {
				throw new IllegalArgumentException("Thermal caloric segment has an invalid temperature interval");
			}
			if (!(Double.isFinite(specificHeatEuPerMuK) && specificHeatEuPerMuK > 0.0)) {
				throw new IllegalArgumentException("Thermal specific heat must be finite and positive");
			}
		}
	}

	public ThermalMaterialDefinition {
		ThermalIds.requireId(id, "thermal material id");
		if (carrierKind == null) throw new IllegalArgumentException("Thermal carrier kind is missing");
		ThermalIds.requireId(carrierId, "thermal carrier id");
		if (!Double.isFinite(referenceTemperatureK)) {
			throw new IllegalArgumentException("Thermal reference temperature must be finite");
		}
		if (!Double.isFinite(referenceSpecificEnergyEuPerMu)) {
			throw new IllegalArgumentException("Thermal reference specific energy must be finite");
		}
		if (!(Double.isFinite(conductivityEuPerSecondMeterK) && conductivityEuPerSecondMeterK >= 0.0)) {
			throw new IllegalArgumentException("Thermal conductivity must be finite and non-negative");
		}
		if (caloricSegments == null || caloricSegments.isEmpty()
				|| caloricSegments.size() > ThermalLimits.MAX_SEGMENTS_PER_MATERIAL) {
			throw new IllegalArgumentException("Thermal caloric segments are empty or over budget");
		}
		ArrayList<CaloricSegment> canonical = new ArrayList<>(caloricSegments);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Thermal caloric segments contain null");
		}
		canonical.sort(Comparator.comparingDouble(CaloricSegment::minimumTemperatureK));
		for (int index = 1; index < canonical.size(); index++) {
			if (Double.doubleToRawLongBits(canonical.get(index - 1).maximumTemperatureK())
					!= Double.doubleToRawLongBits(canonical.get(index).minimumTemperatureK())) {
				throw new IllegalArgumentException("Thermal caloric segments must be exactly contiguous");
			}
		}
		caloricSegments = List.copyOf(canonical);
		if (referenceTemperatureK < caloricSegments.getFirst().minimumTemperatureK()
				|| referenceTemperatureK > caloricSegments.getLast().maximumTemperatureK()) {
			throw new IllegalArgumentException("Thermal reference temperature is outside the caloric model");
		}
	}

	public double minimumTemperatureK() { return caloricSegments.getFirst().minimumTemperatureK(); }
	public double maximumTemperatureK() { return caloricSegments.getLast().maximumTemperatureK(); }

	public double specificHeatAt(double temperatureK) {
		requireTemperature(temperatureK);
		for (int index = 0; index < caloricSegments.size(); index++) {
			CaloricSegment segment = caloricSegments.get(index);
			if (temperatureK < segment.maximumTemperatureK() || index + 1 == caloricSegments.size()) {
				return segment.specificHeatEuPerMuK();
			}
		}
		throw new IllegalStateException("Thermal segment lookup failed");
	}

	/** Integral of specific heat from the frozen reference temperature to {@code temperatureK}. */
	public double sensibleSpecificEnergyAt(double temperatureK) {
		requireTemperature(temperatureK);
		if (temperatureK == referenceTemperatureK) return 0.0;
		double low = Math.min(referenceTemperatureK, temperatureK);
		double high = Math.max(referenceTemperatureK, temperatureK);
		double total = 0.0;
		for (CaloricSegment segment : caloricSegments) {
			double overlap = Math.min(high, segment.maximumTemperatureK())
					- Math.max(low, segment.minimumTemperatureK());
			if (overlap > 0.0) total += overlap * segment.specificHeatEuPerMuK();
		}
		if (!Double.isFinite(total)) throw new IllegalArgumentException("Thermal sensible energy is not finite");
		return temperatureK < referenceTemperatureK ? -total : total;
	}

	/** Closed-form inverse of the continuous piecewise-linear caloric model. */
	public double temperatureForSensibleSpecificEnergy(double sensibleSpecificEnergy) {
		if (!Double.isFinite(sensibleSpecificEnergy)) {
			throw new IllegalArgumentException("Thermal sensible energy must be finite");
		}
		if (sensibleSpecificEnergy == 0.0) return referenceTemperatureK;
		if (sensibleSpecificEnergy > 0.0) {
			double remaining = sensibleSpecificEnergy;
			double cursor = referenceTemperatureK;
			for (CaloricSegment segment : caloricSegments) {
				if (segment.maximumTemperatureK() <= cursor) continue;
				double start = Math.max(cursor, segment.minimumTemperatureK());
				double capacity = (segment.maximumTemperatureK() - start) * segment.specificHeatEuPerMuK();
				if (remaining <= capacity) return start + remaining / segment.specificHeatEuPerMuK();
				remaining -= capacity;
				cursor = segment.maximumTemperatureK();
			}
		} else {
			double remaining = -sensibleSpecificEnergy;
			double cursor = referenceTemperatureK;
			for (int index = caloricSegments.size() - 1; index >= 0; index--) {
				CaloricSegment segment = caloricSegments.get(index);
				if (segment.minimumTemperatureK() >= cursor) continue;
				double end = Math.min(cursor, segment.maximumTemperatureK());
				double capacity = (end - segment.minimumTemperatureK()) * segment.specificHeatEuPerMuK();
				if (remaining <= capacity) return end - remaining / segment.specificHeatEuPerMuK();
				remaining -= capacity;
				cursor = segment.minimumTemperatureK();
			}
		}
		throw new IllegalArgumentException("Internal energy is outside the thermal material validity range");
	}

	private void requireTemperature(double temperatureK) {
		if (!(Double.isFinite(temperatureK) && temperatureK >= minimumTemperatureK()
				&& temperatureK <= maximumTemperatureK())) {
			throw new IllegalArgumentException("Temperature " + temperatureK + " K is outside thermal material "
					+ id + " validity [" + minimumTemperatureK() + ", " + maximumTemperatureK() + "] K");
		}
	}
}
