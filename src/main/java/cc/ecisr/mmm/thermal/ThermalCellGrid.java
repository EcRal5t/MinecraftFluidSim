package cc.ecisr.mmm.thermal;

import java.util.Arrays;

/** Rebuildable LTE grid derived from authoritative carrier energy; it never owns persistent inventory. */
public final class ThermalCellGrid {
	public interface CarrierAccess {
		int carrierCount();
		int cell(int carrier);
		ThermalMaterialDefinition material(int carrier);
		double mass(int carrier);
		double volume(int carrier);
		double baseEnergy(int carrier);
		double internalEnergy(int carrier);
		void setInternalEnergy(int carrier, double energy);
	}

	private final int cellCount;
	private final int[] cellHead;
	private int[] carrierNext = new int[0];
	private final int[] carrierCount;
	private final double[] internalEnergy;
	private final double[] temperature;
	private final double[] heatCapacity;
	private final double[] conductivity;
	private final double[] totalVolume;
	private CarrierAccess carriers;

	public ThermalCellGrid(int cellCount) {
		if (cellCount <= 0) throw new IllegalArgumentException("Thermal cell count must be positive");
		this.cellCount = cellCount;
		this.cellHead = new int[cellCount];
		this.carrierCount = new int[cellCount];
		this.internalEnergy = new double[cellCount];
		this.temperature = new double[cellCount];
		this.heatCapacity = new double[cellCount];
		this.conductivity = new double[cellCount];
		this.totalVolume = new double[cellCount];
	}

	public void rebuild(CarrierAccess carrierAccess) {
		rebuildTopology(carrierAccess);
		for (int cell = 0; cell < cellCount; cell++) if (carrierCount[cell] > 0) {
			conductivity[cell] /= totalVolume[cell];
			solveCell(cell, internalEnergy[cell]);
		}
	}

	/** Rebuilds changed carrier topology while solving one cell against its pre-commit conserved U. */
	public void rebuildWithCellEnergy(CarrierAccess carrierAccess, int targetCell, double targetEnergy) {
		if (targetCell < 0 || targetCell >= cellCount || !Double.isFinite(targetEnergy)) {
			throw new IllegalArgumentException("Thermal rebuild override is invalid");
		}
		rebuildTopology(carrierAccess);
		if (carrierCount[targetCell] == 0) throw new IllegalArgumentException("Thermal reaction emptied its carrier cell");
		internalEnergy[targetCell] = targetEnergy;
		for (int cell = 0; cell < cellCount; cell++) if (carrierCount[cell] > 0) {
			conductivity[cell] /= totalVolume[cell];
			solveCell(cell, internalEnergy[cell]);
		}
	}

	private void rebuildTopology(CarrierAccess carrierAccess) {
		if (carrierAccess == null) throw new IllegalArgumentException("Thermal carrier access is missing");
		this.carriers = carrierAccess;
		if (carrierAccess.carrierCount() > carrierNext.length) carrierNext = new int[carrierAccess.carrierCount()];
		Arrays.fill(cellHead, -1);
		Arrays.fill(carrierCount, 0);
		Arrays.fill(internalEnergy, 0.0);
		Arrays.fill(temperature, Double.NaN);
		Arrays.fill(heatCapacity, 0.0);
		Arrays.fill(conductivity, 0.0);
		Arrays.fill(totalVolume, 0.0);
		for (int carrier = carrierAccess.carrierCount() - 1; carrier >= 0; carrier--) {
			int cell = carrierAccess.cell(carrier);
			if (cell < 0 || cell >= cellCount) throw new IllegalArgumentException("Thermal carrier cell is outside the grid");
			carrierNext[carrier] = cellHead[cell];
			cellHead[cell] = carrier;
			carrierCount[cell]++;
			double energy = carrierAccess.internalEnergy(carrier);
			double volume = carrierAccess.volume(carrier);
			if (!Double.isFinite(energy) || !(Double.isFinite(volume) && volume > 0.0)) {
				throw new IllegalArgumentException("Thermal carrier inventory is invalid");
			}
			internalEnergy[cell] += energy;
			totalVolume[cell] += volume;
			conductivity[cell] += volume * carrierAccess.material(carrier).conductivityEuPerSecondMeterK();
		}
	}

	public void setCellInternalEnergies(double[] targetEnergy) {
		if (carriers == null) throw new IllegalStateException("Thermal cell grid has not been rebuilt");
		validateCellInternalEnergies(targetEnergy);
		for (int cell = 0; cell < cellCount; cell++) {
			if (carrierCount[cell] == 0) {
				continue;
			}
			double requested = targetEnergy[cell];
			solveCell(cell, requested);
			int remainderCarrier = -1;
			double largestHeatCapacity = Double.NEGATIVE_INFINITY;
			double assigned = 0.0;
			// Put the exact floating-point conservation remainder on the best-conditioned carrier. Phase commits
			// append very small coherent batches after much larger liquid parcels; assigning the remainder to that
			// list tail amplifies an unavoidable cell-sum roundoff into a measurable temperature mismatch.
			for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
				double capacity = carriers.mass(carrier) * carriers.material(carrier).specificHeatAt(temperature[cell]);
				if (capacity > largestHeatCapacity) {
					largestHeatCapacity = capacity;
					remainderCarrier = carrier;
				}
			}
			for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
				if (carrier == remainderCarrier) continue;
				double value = modeledEnergy(carrier, temperature[cell]);
				carriers.setInternalEnergy(carrier, value);
				assigned += value;
			}
			double remainder = requested - assigned;
			if (!Double.isFinite(remainder)) throw new IllegalArgumentException("Thermal carrier energy remainder is invalid");
			carriers.setInternalEnergy(remainderCarrier, remainder);
			validateCarrierAtCellTemperature(remainderCarrier, temperature[cell]);
			internalEnergy[cell] = requested;
		}
	}

	/** Validates a complete prospective LTE energy assignment without changing any authoritative carrier. */
	public void validateCellInternalEnergies(double[] targetEnergy) {
		if (carriers == null) throw new IllegalStateException("Thermal cell grid has not been rebuilt");
		if (targetEnergy == null || targetEnergy.length != cellCount) {
			throw new IllegalArgumentException("Thermal cell energy array length changed");
		}
		// Validate the entire candidate before changing any authoritative marker energy.
		for (int cell = 0; cell < cellCount; cell++) {
			if (carrierCount[cell] == 0) {
				if (targetEnergy[cell] != 0.0) throw new IllegalArgumentException("Empty thermal cell received energy");
			} else {
				if (!Double.isFinite(targetEnergy[cell])) throw new IllegalArgumentException("Thermal cell energy is not finite");
				solveCell(cell, targetEnergy[cell]);
			}
		}
	}

	public int cellCount() { return cellCount; }
	public boolean occupied(int cell) { return carrierCount[cell] > 0; }
	public double internalEnergy(int cell) { return internalEnergy[cell]; }
	public double temperature(int cell) { return temperature[cell]; }
	public double heatCapacity(int cell) { return heatCapacity[cell]; }
	public double conductivity(int cell) { return conductivity[cell]; }
	public double[] copyInternalEnergy() { return internalEnergy.clone(); }
	public double[] copyCarrierInternalEnergy() {
		if (carriers == null) throw new IllegalStateException("Thermal cell grid has not been rebuilt");
		double[] result = new double[carriers.carrierCount()];
		for (int carrier = 0; carrier < result.length; carrier++) result[carrier] = carriers.internalEnergy(carrier);
		return result;
	}

	public void restoreCarrierInternalEnergy(double[] snapshot) {
		if (carriers == null || snapshot == null || snapshot.length != carriers.carrierCount()) {
			throw new IllegalArgumentException("Thermal carrier rollback snapshot is incompatible");
		}
		for (int carrier = 0; carrier < snapshot.length; carrier++) carriers.setInternalEnergy(carrier, snapshot[carrier]);
		rebuild(carriers);
	}

	private void solveCell(int cell, double targetEnergy) {
		double minimum = ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
		double maximum = Double.POSITIVE_INFINITY;
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
			ThermalMaterialDefinition material = carriers.material(carrier);
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
			for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
				for (ThermalMaterialDefinition.CaloricSegment segment : carriers.material(carrier).caloricSegments()) {
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
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
			total += modeledEnergy(carrier, temperatureK);
		}
		return total;
	}

	private double modeledCellHeatCapacity(int cell, double temperatureK) {
		double total = 0.0;
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
			total += carriers.mass(carrier) * carriers.material(carrier).specificHeatAt(temperatureK);
		}
		if (!(Double.isFinite(total) && total > 0.0)) throw new IllegalArgumentException("Thermal cell heat capacity is invalid");
		return total;
	}

	private double modeledEnergy(int carrier, double temperatureK) {
		ThermalMaterialDefinition material = carriers.material(carrier);
		double value = carriers.baseEnergy(carrier) + carriers.mass(carrier) * material.sensibleSpecificEnergyAt(temperatureK);
		if (!Double.isFinite(value)) throw new IllegalArgumentException("Thermal carrier modeled energy is invalid");
		return value;
	}

	private void validateCarrierAtCellTemperature(int carrier, double cellTemperature) {
		ThermalMaterialDefinition material = carriers.material(carrier);
		double sensible = (carriers.internalEnergy(carrier) - carriers.baseEnergy(carrier)) / carriers.mass(carrier);
		double restored = material.temperatureForSensibleSpecificEnergy(sensible);
		double tolerance = Math.max(1.0E-9, Math.max(Math.ulp(restored), Math.ulp(cellTemperature)) * 16.0);
		if (Math.abs(restored - cellTemperature) > tolerance) {
			double residual = carriers.internalEnergy(carrier) - modeledEnergy(carrier, cellTemperature);
			throw new IllegalArgumentException("Thermal carrier remainder left local equilibrium"
					+ " (carrier=" + carrier + ", cellTemperature=" + cellTemperature
					+ ", restoredTemperature=" + restored + ", energyResidual=" + residual
					+ ", mass=" + carriers.mass(carrier) + ")");
		}
	}

	private static double energyTolerance(double... values) {
		double tolerance = 1.0E-9;
		for (double value : values) tolerance = Math.max(tolerance, Math.ulp(value) * 16.0);
		return tolerance;
	}
}
