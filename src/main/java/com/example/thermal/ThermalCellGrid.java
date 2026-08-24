package com.example.thermal;

import java.util.Arrays;

/** Rebuildable LTE grid derived from authoritative carrier energy; it never owns persistent inventory. */
public final class ThermalCellGrid {
	public interface MarkerAccess {
		int markerCount();
		int cell(int marker);
		ThermalMaterialDefinition material(int marker);
		double mass(int marker);
		double volume(int marker);
		double baseEnergy(int marker);
		double internalEnergy(int marker);
		void setInternalEnergy(int marker, double energy);
	}

	private final int cellCount;
	private final int[] cellHead;
	private int[] markerNext = new int[0];
	private final int[] markerCount;
	private final double[] internalEnergy;
	private final double[] temperature;
	private final double[] heatCapacity;
	private final double[] conductivity;
	private final double[] totalVolume;
	private MarkerAccess markers;

	public ThermalCellGrid(int cellCount) {
		if (cellCount <= 0) throw new IllegalArgumentException("Thermal cell count must be positive");
		this.cellCount = cellCount;
		this.cellHead = new int[cellCount];
		this.markerCount = new int[cellCount];
		this.internalEnergy = new double[cellCount];
		this.temperature = new double[cellCount];
		this.heatCapacity = new double[cellCount];
		this.conductivity = new double[cellCount];
		this.totalVolume = new double[cellCount];
	}

	public void rebuild(MarkerAccess markerAccess) {
		if (markerAccess == null) throw new IllegalArgumentException("Thermal marker access is missing");
		this.markers = markerAccess;
		if (markerAccess.markerCount() > markerNext.length) markerNext = new int[markerAccess.markerCount()];
		Arrays.fill(cellHead, -1);
		Arrays.fill(markerCount, 0);
		Arrays.fill(internalEnergy, 0.0);
		Arrays.fill(temperature, Double.NaN);
		Arrays.fill(heatCapacity, 0.0);
		Arrays.fill(conductivity, 0.0);
		Arrays.fill(totalVolume, 0.0);
		for (int marker = markerAccess.markerCount() - 1; marker >= 0; marker--) {
			int cell = markerAccess.cell(marker);
			if (cell < 0 || cell >= cellCount) throw new IllegalArgumentException("Thermal marker cell is outside the grid");
			markerNext[marker] = cellHead[cell];
			cellHead[cell] = marker;
			markerCount[cell]++;
			double energy = markerAccess.internalEnergy(marker);
			double volume = markerAccess.volume(marker);
			if (!Double.isFinite(energy) || !(Double.isFinite(volume) && volume > 0.0)) {
				throw new IllegalArgumentException("Thermal marker inventory is invalid");
			}
			internalEnergy[cell] += energy;
			totalVolume[cell] += volume;
			conductivity[cell] += volume * markerAccess.material(marker).conductivityEuPerSecondMeterK();
		}
		for (int cell = 0; cell < cellCount; cell++) if (markerCount[cell] > 0) {
			conductivity[cell] /= totalVolume[cell];
			solveCell(cell, internalEnergy[cell]);
		}
	}

	public void setCellInternalEnergies(double[] targetEnergy) {
		if (markers == null) throw new IllegalStateException("Thermal cell grid has not been rebuilt");
		if (targetEnergy == null || targetEnergy.length != cellCount) {
			throw new IllegalArgumentException("Thermal cell energy array length changed");
		}
		for (int cell = 0; cell < cellCount; cell++) {
			if (markerCount[cell] == 0) {
				if (targetEnergy[cell] != 0.0) throw new IllegalArgumentException("Empty thermal cell received energy");
				continue;
			}
			double requested = targetEnergy[cell];
			if (!Double.isFinite(requested)) throw new IllegalArgumentException("Thermal cell energy is not finite");
			solveCell(cell, requested);
			int last = -1;
			double assigned = 0.0;
			for (int marker = cellHead[cell]; marker >= 0; marker = markerNext[marker]) last = marker;
			for (int marker = cellHead[cell]; marker >= 0; marker = markerNext[marker]) {
				if (marker == last) continue;
				double value = modeledEnergy(marker, temperature[cell]);
				markers.setInternalEnergy(marker, value);
				assigned += value;
			}
			double remainder = requested - assigned;
			if (!Double.isFinite(remainder)) throw new IllegalArgumentException("Thermal marker energy remainder is invalid");
			markers.setInternalEnergy(last, remainder);
			validateMarkerAtCellTemperature(last, temperature[cell]);
			internalEnergy[cell] = requested;
		}
	}

	public int cellCount() { return cellCount; }
	public boolean occupied(int cell) { return markerCount[cell] > 0; }
	public double internalEnergy(int cell) { return internalEnergy[cell]; }
	public double temperature(int cell) { return temperature[cell]; }
	public double heatCapacity(int cell) { return heatCapacity[cell]; }
	public double conductivity(int cell) { return conductivity[cell]; }
	public double[] copyInternalEnergy() { return internalEnergy.clone(); }

	private void solveCell(int cell, double targetEnergy) {
		double minimum = ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
		double maximum = Double.POSITIVE_INFINITY;
		for (int marker = cellHead[cell]; marker >= 0; marker = markerNext[marker]) {
			ThermalMaterialDefinition material = markers.material(marker);
			minimum = Math.max(minimum, material.minimumTemperatureK());
			maximum = Math.min(maximum, material.maximumTemperatureK());
		}
		if (!(maximum >= minimum)) throw new IllegalArgumentException("Thermal cell carriers have no common validity range");
		double energyAtMinimum = modeledCellEnergy(cell, minimum);
		double energyAtMaximum = modeledCellEnergy(cell, maximum);
		double tolerance = energyTolerance(targetEnergy, energyAtMinimum, energyAtMaximum);
		if (targetEnergy < energyAtMinimum - tolerance || targetEnergy > energyAtMaximum + tolerance) {
			throw new IllegalArgumentException("Thermal cell energy is outside the common validity range");
		}
		if (targetEnergy <= energyAtMinimum) {
			temperature[cell] = minimum;
			heatCapacity[cell] = modeledCellHeatCapacity(cell, minimum);
			return;
		}
		if (targetEnergy >= energyAtMaximum) {
			temperature[cell] = maximum;
			heatCapacity[cell] = modeledCellHeatCapacity(cell, maximum);
			return;
		}

		double cursor = minimum;
		double cursorEnergy = energyAtMinimum;
		while (cursor < maximum) {
			double next = maximum;
			for (int marker = cellHead[cell]; marker >= 0; marker = markerNext[marker]) {
				for (ThermalMaterialDefinition.CaloricSegment segment : markers.material(marker).caloricSegments()) {
					if (segment.maximumTemperatureK() > cursor && segment.maximumTemperatureK() < next) {
						next = segment.maximumTemperatureK();
					}
				}
			}
			double middle = cursor + (next - cursor) * 0.5;
			double capacity = modeledCellHeatCapacity(cell, middle);
			double intervalEnergy = capacity * (next - cursor);
			if (targetEnergy <= cursorEnergy + intervalEnergy + tolerance) {
				double solved = cursor + (targetEnergy - cursorEnergy) / capacity;
				temperature[cell] = Math.max(cursor, Math.min(next, solved));
				heatCapacity[cell] = modeledCellHeatCapacity(cell, temperature[cell]);
				return;
			}
			cursorEnergy += intervalEnergy;
			cursor = next;
		}
		throw new IllegalArgumentException("Thermal cell state inversion failed");
	}

	private double modeledCellEnergy(int cell, double temperatureK) {
		double total = 0.0;
		for (int marker = cellHead[cell]; marker >= 0; marker = markerNext[marker]) {
			total += modeledEnergy(marker, temperatureK);
		}
		return total;
	}

	private double modeledCellHeatCapacity(int cell, double temperatureK) {
		double total = 0.0;
		for (int marker = cellHead[cell]; marker >= 0; marker = markerNext[marker]) {
			total += markers.mass(marker) * markers.material(marker).specificHeatAt(temperatureK);
		}
		if (!(Double.isFinite(total) && total > 0.0)) throw new IllegalArgumentException("Thermal cell heat capacity is invalid");
		return total;
	}

	private double modeledEnergy(int marker, double temperatureK) {
		ThermalMaterialDefinition material = markers.material(marker);
		double value = markers.baseEnergy(marker) + markers.mass(marker) * material.sensibleSpecificEnergyAt(temperatureK);
		if (!Double.isFinite(value)) throw new IllegalArgumentException("Thermal marker modeled energy is invalid");
		return value;
	}

	private void validateMarkerAtCellTemperature(int marker, double cellTemperature) {
		ThermalMaterialDefinition material = markers.material(marker);
		double sensible = (markers.internalEnergy(marker) - markers.baseEnergy(marker)) / markers.mass(marker);
		double restored = material.temperatureForSensibleSpecificEnergy(sensible);
		double tolerance = Math.max(1.0E-9, Math.max(Math.ulp(restored), Math.ulp(cellTemperature)) * 16.0);
		if (Math.abs(restored - cellTemperature) > tolerance) {
			throw new IllegalArgumentException("Thermal marker remainder left local equilibrium");
		}
	}

	private static double energyTolerance(double... values) {
		double tolerance = 1.0E-9;
		for (double value : values) tolerance = Math.max(tolerance, Math.ulp(value) * 16.0);
		return tolerance;
	}
}
