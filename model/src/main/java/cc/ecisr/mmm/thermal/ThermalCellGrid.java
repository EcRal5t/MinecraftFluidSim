package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.compute.CpuWorkBatch;

import java.util.Arrays;
import java.util.List;

/** Rebuildable LTE grid derived from authoritative carrier energy; it never owns persistent inventory. */
public final class ThermalCellGrid {
	/** Last range derived from authoritative occupied carriers; absent until a successful rebuild/commit. */
	public record TemperatureRange(double minimumK, double maximumK) {
		public TemperatureRange {
			if (!(Double.isFinite(minimumK) && Double.isFinite(maximumK)
					&& minimumK >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K && maximumK >= minimumK)) {
				throw new IllegalArgumentException("Observed thermal temperature range is invalid");
			}
		}
	}

	/** Stable topology and inventory during a solve; independent ordinal reads can run concurrently. */
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
	private ThermalMaterialDefinition[] carrierMaterial = new ThermalMaterialDefinition[0];
	private double[] carrierMass = new double[0];
	private double[] carrierBaseEnergy = new double[0];
	private int topologyCarrierCount;
	private final int[] carrierCount;
	private final double[] internalEnergy;
	private final double[] temperature;
	private final double[] heatCapacity;
	private final double[] conductivity;
	private final double[] totalVolume;
	private final double[] minimumTemperature;
	private final double[] maximumTemperature;
	private CarrierAccess carriers;
	private TemperatureRange observedTemperatureRange;

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
		this.minimumTemperature = new double[cellCount];
		this.maximumTemperature = new double[cellCount];
	}

	public void rebuild(CarrierAccess carrierAccess) {
		rebuildTopology(carrierAccess, -1);
		CpuWorkBatch.run(cellCount, (from, until) -> {
			for (int cell = from; cell < until; cell++) {
				if (carrierCount[cell] > 0) {
					conductivity[cell] /= totalVolume[cell];
					solveCarrierCell(cell);
				}
			}
		});
		updateObservedTemperatureRange();
	}

	/** Refreshes U after a contact solve with unchanged carrier topology and caloric inventory. */
	void refreshCarrierEnergy() {
		if (carriers == null || carriers.carrierCount() != topologyCarrierCount)
			throw new IllegalStateException("Thermal contact solve changed carrier topology");
		Arrays.fill(internalEnergy, 0.0);
		for (int carrier = topologyCarrierCount - 1; carrier >= 0; carrier--) {
			double energy = carriers.internalEnergy(carrier);
			if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal carrier energy is not finite");
			internalEnergy[carriers.cell(carrier)] += energy;
		}
		CpuWorkBatch.run(topologyCarrierCount, (from, until) -> {
			for (int carrier = from; carrier < until; carrier++)
				validateCarrierEnergy(carrier, carriers.internalEnergy(carrier));
		});
		CpuWorkBatch.run(cellCount, (from, until) -> {
			for (int cell = from; cell < until; cell++) if (carrierCount[cell] > 0)
				solveCarrierCell(cell);
		});
		updateObservedTemperatureRange();
	}

	/** Prepares changed topology and a prospective conserved target; the subsequent setter publishes owner U. */
	public void rebuildWithCellEnergy(CarrierAccess carrierAccess, int targetCell, double targetEnergy) {
		rebuildWithCellEnergy(carrierAccess, targetCell, SignedDyadic.of(targetEnergy));
	}

	public void rebuildWithCellEnergy(CarrierAccess carrierAccess, int targetCell, SignedDyadic targetEnergy) {
		if (targetCell < 0 || targetCell >= cellCount || targetEnergy == null || !Double.isFinite(targetEnergy.toDouble())) {
			throw new IllegalArgumentException("Thermal rebuild override is invalid");
		}
		rebuildTopology(carrierAccess, targetCell);
		if (carrierCount[targetCell] == 0) throw new IllegalArgumentException("Thermal reaction emptied its carrier cell");
		internalEnergy[targetCell] = targetEnergy.toDouble();
		for (int cell = 0; cell < cellCount; cell++) if (carrierCount[cell] > 0) {
			conductivity[cell] /= totalVolume[cell];
			if (cell == targetCell) solveCell(cell, targetEnergy);
			else solveCarrierCell(cell);
		}
		updateObservedTemperatureRange();
	}

	/**
	 * Rebuilds carrier ordinals after a cell-local phase edit, retaining the already solved LTE state
	 * of every untouched cell and assigning only the target cell's conserved energy.
	 */
	public void rebuildPhaseCellWithEnergy(CarrierAccess carrierAccess, int targetCell, double targetEnergy) {
		rebuildPhaseCellWithEnergy(carrierAccess, targetCell, SignedDyadic.of(targetEnergy));
	}

	public void rebuildPhaseCellWithEnergy(CarrierAccess carrierAccess, int targetCell, SignedDyadic targetEnergy) {
		if (targetCell < 0 || targetCell >= cellCount || targetEnergy == null || !Double.isFinite(targetEnergy.toDouble())) {
			throw new IllegalArgumentException("Thermal phase-cell rebuild override is invalid");
		}
		double[] previousTemperature = temperature.clone();
		int[] previousCarrierCount = carrierCount.clone();
		rebuildTopology(carrierAccess, targetCell);
		if (carrierCount[targetCell] == 0 && !targetEnergy.equals(SignedDyadic.ZERO)) {
			throw new IllegalArgumentException("Empty thermal phase cell retains energy");
		}
		for (int cell = 0; cell < cellCount; cell++) if (carrierCount[cell] > 0) {
			conductivity[cell] /= totalVolume[cell];
			if (cell == targetCell) {
				internalEnergy[cell] = targetEnergy.toDouble();
				solveCell(cell, targetEnergy);
				assignSolvedCellEnergy(cell, targetEnergy);
			} else {
				if (previousCarrierCount[cell] == 0 || !Double.isFinite(previousTemperature[cell])) {
					throw new IllegalArgumentException("Untouched thermal phase cell has no prepared LTE state");
				}
				temperature[cell] = previousTemperature[cell];
					internalEnergy[cell] = exactCarrierEnergyInCell(cell).toDouble();
				heatCapacity[cell] = modeledCellHeatCapacity(cell, temperature[cell]);
				updateLinearTemperatureInterval(cell);
			}
		}
		updateObservedTemperatureRange();
	}

	/** Refreshes one edited cell when phase transfer retained every carrier ordinal and cell membership. */
	public void refreshPhaseCellWithEnergy(CarrierAccess carrierAccess, int targetCell, double targetEnergy) {
		refreshPhaseCellWithEnergy(carrierAccess, targetCell, SignedDyadic.of(targetEnergy));
	}

	public void refreshPhaseCellWithEnergy(CarrierAccess carrierAccess, int targetCell, SignedDyadic targetEnergy) {
		if (carrierAccess == null || targetCell < 0 || targetCell >= cellCount
				|| targetEnergy == null || !Double.isFinite(targetEnergy.toDouble())) {
			throw new IllegalArgumentException("Thermal phase-cell refresh override is invalid");
		}
		if (carrierAccess.carrierCount() != topologyCarrierCount || carrierCount[targetCell] == 0) {
			throw new IllegalArgumentException("Thermal phase-cell refresh changed carrier topology");
		}
		this.carriers = carrierAccess;
		internalEnergy[targetCell] = 0.0;
		conductivity[targetCell] = 0.0;
		totalVolume[targetCell] = 0.0;
		int refreshed = 0;
		for (int carrier = cellHead[targetCell]; carrier >= 0; carrier = carrierNext[carrier]) {
			if (carrierAccess.cell(carrier) != targetCell) {
				throw new IllegalArgumentException("Thermal phase-cell refresh changed carrier membership");
			}
			ThermalMaterialDefinition material = carrierAccess.material(carrier);
			double mass = carrierAccess.mass(carrier);
			double energy = carrierAccess.internalEnergy(carrier);
			double volume = carrierAccess.volume(carrier);
			if (!(Double.isFinite(mass) && mass > 0.0) || !Double.isFinite(energy)
					|| !(Double.isFinite(volume) && volume > 0.0)) {
				throw new IllegalArgumentException("Thermal phase-cell carrier inventory is invalid");
			}
			carrierMaterial[carrier] = material;
			carrierMass[carrier] = mass;
			carrierBaseEnergy[carrier] = Double.NaN;
			internalEnergy[targetCell] += energy;
			totalVolume[targetCell] += volume;
			conductivity[targetCell] += volume * material.conductivityEuPerSecondMeterK();
			refreshed++;
		}
		if (refreshed != carrierCount[targetCell]) {
			throw new IllegalArgumentException("Thermal phase-cell refresh changed carrier cardinality");
		}
		conductivity[targetCell] /= totalVolume[targetCell];
		internalEnergy[targetCell] = targetEnergy.toDouble();
		solveCell(targetCell, targetEnergy);
		assignSolvedCellEnergy(targetCell, targetEnergy);
		updateObservedTemperatureRange();
	}

	private void rebuildTopology(CarrierAccess carrierAccess, int prospectiveCell) {
		if (carrierAccess == null) throw new IllegalArgumentException("Thermal carrier access is missing");
		this.carriers = carrierAccess;
		topologyCarrierCount = carrierAccess.carrierCount();
		if (carrierAccess.carrierCount() > carrierNext.length) {
			int capacity = carrierAccess.carrierCount();
			carrierNext = new int[capacity];
			carrierMaterial = new ThermalMaterialDefinition[capacity];
			carrierMass = new double[capacity];
			carrierBaseEnergy = new double[capacity];
		}
		Arrays.fill(cellHead, -1);
		Arrays.fill(carrierCount, 0);
		Arrays.fill(internalEnergy, 0.0);
		Arrays.fill(temperature, Double.NaN);
		Arrays.fill(heatCapacity, 0.0);
		Arrays.fill(conductivity, 0.0);
		Arrays.fill(totalVolume, 0.0);
		Arrays.fill(minimumTemperature, Double.NaN);
		Arrays.fill(maximumTemperature, Double.NaN);
		Arrays.fill(carrierBaseEnergy, 0, carrierAccess.carrierCount(), Double.NaN);
		for (int carrier = carrierAccess.carrierCount() - 1; carrier >= 0; carrier--) {
			int cell = carrierAccess.cell(carrier);
			if (cell < 0 || cell >= cellCount) throw new IllegalArgumentException("Thermal carrier cell is outside the grid");
			carrierNext[carrier] = cellHead[cell];
			cellHead[cell] = carrier;
			carrierCount[cell]++;
			carrierMaterial[carrier] = carrierAccess.material(carrier);
			carrierMass[carrier] = carrierAccess.mass(carrier);
			if (!(Double.isFinite(carrierMass[carrier]) && carrierMass[carrier] > 0.0))
				throw new IllegalArgumentException("Thermal carrier mass must be finite and positive");
			double energy = carrierAccess.internalEnergy(carrier);
			double volume = carrierAccess.volume(carrier);
			if (!Double.isFinite(energy) || !(Double.isFinite(volume) && volume > 0.0)) {
				throw new IllegalArgumentException("Thermal carrier inventory is invalid");
			}
			internalEnergy[cell] += energy;
			totalVolume[cell] += volume;
			conductivity[cell] += volume * carrierMaterial[carrier].conductivityEuPerSecondMeterK();
		}
		// Bind derived reference caches before independent carrier validation.
		if (topologyCarrierCount > 0) carrierAccess.baseEnergy(0);
		CpuWorkBatch.run(topologyCarrierCount, (from, until) -> {
			for (int carrier = from; carrier < until; carrier++) if (carrierAccess.cell(carrier) != prospectiveCell)
				validateCarrierEnergy(carrier, carrierAccess.internalEnergy(carrier));
		});
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
			assignSolvedCellEnergy(cell, requested);
		}
		updateObservedTemperatureRange();
	}

	/** Repartitions one edited cell while preserving every remote carrier's original U. */
	public void setCellInternalEnergy(int cell, double targetEnergy) {
		setCellInternalEnergy(cell, SignedDyadic.of(targetEnergy));
	}

	public void setCellInternalEnergy(int cell, SignedDyadic targetEnergy) {
		validateCellInternalEnergy(cell, targetEnergy);
		if (carrierCount[cell] == 0) return;
		solveCell(cell, targetEnergy);
		assignSolvedCellEnergy(cell, targetEnergy);
		updateObservedTemperatureRange();
	}

	CarrierAccess carrierAccess() { return carriers; }
	int firstCarrier(int cell) { return cellHead[cell]; }
	int nextCarrier(int carrier) { return carrierNext[carrier]; }
	double carrierVolume(int cell) { return totalVolume[cell]; }

	private void assignSolvedCellEnergy(int cell, double requested) { assignSolvedCellEnergy(cell, SignedDyadic.of(requested)); }

	private void assignSolvedCellEnergy(int cell, SignedDyadic requested) {
		CellEnergyAssignment assignment = prepareSolvedCellEnergy(cell, requested);
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
			double value = carrier == assignment.remainderCarrier() ? assignment.remainder()
					: modeledEnergy(carrier, temperature[cell]);
			carriers.setInternalEnergy(carrier, value);
		}
		// The actual binary64 owner assignment is authoritative, including its booked roundoff.
		solveCarrierCell(cell);
	}

	private record CellEnergyAssignment(int remainderCarrier, double remainder) { }

	private CellEnergyAssignment prepareSolvedCellEnergy(int cell, double requested) { return prepareSolvedCellEnergy(cell, SignedDyadic.of(requested)); }

	private CellEnergyAssignment prepareSolvedCellEnergy(int cell, SignedDyadic requested) {
		CellEnergyAssignment assignment = prepareCellRemainder(cell, requested);
		SignedDyadic actual = SignedDyadic.ZERO;
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
			double value = carrier == assignment.remainderCarrier() ? assignment.remainder()
					: modeledEnergy(carrier, temperature[cell]);
			validateCarrierEnergy(carrier, value);
			actual = actual.add(SignedDyadic.of(value));
		}
		if (!Double.isFinite(actual.toDouble())) throw new IllegalArgumentException("Thermal assigned cell energy is not finite");
		double oldTemperature = temperature[cell], oldCapacity = heatCapacity[cell];
		double oldMinimum = minimumTemperature[cell], oldMaximum = maximumTemperature[cell];
		try { solveCell(cell, actual); }
		finally {
			temperature[cell] = oldTemperature; heatCapacity[cell] = oldCapacity;
			minimumTemperature[cell] = oldMinimum; maximumTemperature[cell] = oldMaximum;
		}
		return assignment;
	}

	private CellEnergyAssignment prepareCellRemainder(int cell, SignedDyadic requested) {
		int remainderCarrier = -1;
		double largestHeatCapacity = Double.NEGATIVE_INFINITY;
		SignedDyadic modeled = SignedDyadic.ZERO;
		// Put the exact floating-point conservation remainder on the best-conditioned carrier. Phase commits
		// append very small coherent batches after much larger liquid parcels; assigning the remainder to that
		// list tail amplifies an unavoidable cell-sum roundoff into a measurable temperature mismatch.
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
			double capacity = carrierMass[carrier] * carrierMaterial[carrier].specificHeatAt(temperature[cell]);
			if (capacity > largestHeatCapacity) {
				largestHeatCapacity = capacity;
				remainderCarrier = carrier;
			}
			modeled = modeled.add(SignedDyadic.of(modeledEnergy(carrier, temperature[cell])));
		}
		CarrierRemainderUnavailableException failure;
		try { return prepareCarrierRemainder(cell, requested, modeled, remainderCarrier); }
		catch (CarrierRemainderUnavailableException unavailable) { failure = unavailable; }
		// Reference-energy rounding may leave a different carrier's caloric bin able to hold
		// the same conserved remainder. All modeled contributions were validated before this search.
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
			if (carrier == remainderCarrier) continue;
			try { return prepareCarrierRemainder(cell, requested, modeled, carrier); }
			catch (CarrierRemainderUnavailableException unavailable) { failure = unavailable; }
		}
		throw failure;
	}

	private CellEnergyAssignment prepareCarrierRemainder(int cell, SignedDyadic requested, SignedDyadic modeled, int carrier) {
		SignedDyadic others = modeled.subtract(SignedDyadic.of(modeledEnergy(carrier, temperature[cell])));
		double remainder = requested.subtract(others).toDouble();
		if (!Double.isFinite(remainder)) throw new CarrierRemainderUnavailableException("Thermal carrier energy remainder is invalid", null);
		try { validateCarrierAtCellTemperature(carrier, remainder, temperature[cell]); }
		catch (IllegalArgumentException invalid) { throw new CarrierRemainderUnavailableException(invalid.getMessage(), invalid); }
		return new CellEnergyAssignment(carrier, remainder);
	}

	private static final class CarrierRemainderUnavailableException extends IllegalArgumentException {
		CarrierRemainderUnavailableException(String message, IllegalArgumentException cause) { super(message, cause); }
	}

	/** Preflights one exact LTE target while preserving carriers and the current derived cell state. */
	void validateCellInternalEnergy(int cell, SignedDyadic targetEnergy) {
		if (carriers == null || cell < 0 || cell >= cellCount || targetEnergy == null
				|| !Double.isFinite(targetEnergy.toDouble())) throw new IllegalArgumentException("Thermal cell energy edit is invalid");
		if (carrierCount[cell] == 0) {
			if (!targetEnergy.equals(SignedDyadic.ZERO)) throw new IllegalArgumentException("Empty thermal cell received energy");
			return;
		}
		double oldTemperature = temperature[cell], oldCapacity = heatCapacity[cell];
		double oldMinimum = minimumTemperature[cell], oldMaximum = maximumTemperature[cell];
		try {
			solveCell(cell, targetEnergy);
			prepareSolvedCellEnergy(cell, targetEnergy);
		} finally {
			temperature[cell] = oldTemperature; heatCapacity[cell] = oldCapacity;
			minimumTemperature[cell] = oldMinimum; maximumTemperature[cell] = oldMaximum;
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
			validateCellInternalEnergy(cell, SignedDyadic.of(targetEnergy[cell]));
		}
	}

	public int cellCount() { return cellCount; }
	public boolean occupied(int cell) { return carrierCount[cell] > 0; }
	public double internalEnergy(int cell) { return internalEnergy[cell]; }
	public double temperature(int cell) { return temperature[cell]; }
	public double heatCapacity(int cell) { return heatCapacity[cell]; }
	public double conductivity(int cell) { return conductivity[cell]; }
	public double minimumTemperature(int cell) { return minimumTemperature[cell]; }
	public double maximumTemperature(int cell) { return maximumTemperature[cell]; }
	/** Constant-time diagnostic accessor. It never rebuilds the derived grid. */
	public TemperatureRange observedTemperatureRange() { return observedTemperatureRange; }
	public double[] copyInternalEnergy() { return internalEnergy.clone(); }
	public boolean carriersMatchSolvedTemperature() {
		if (carriers == null) throw new IllegalStateException("Thermal cell grid has not been rebuilt");
		for (int cell = 0; cell < cellCount; cell++) if (carrierCount[cell] > 0) {
			for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
				if (carriers.internalEnergy(carrier) == modeledEnergy(carrier, temperature[cell])) continue;
				ThermalMaterialDefinition material = carrierMaterial[carrier];
				double sensible = (carriers.internalEnergy(carrier) - cachedBaseEnergy(carrier))
						/ carrierMass[carrier];
				double restored = material.temperatureForSensibleSpecificEnergy(sensible);
				double tolerance = Math.max(1.0E-9,
						Math.max(Math.ulp(restored), Math.ulp(temperature[cell])) * 16.0);
				if (Math.abs(restored - temperature[cell]) > tolerance) return false;
			}
		}
		return true;
	}
	public SignedDyadic exactCarrierEnergyInCell(int cell) {
		if (carriers == null || cell < 0 || cell >= cellCount) {
			throw new IllegalArgumentException("Thermal cell energy query is invalid");
		}
		SignedDyadic result = SignedDyadic.ZERO;
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
			result = result.add(SignedDyadic.of(carriers.internalEnergy(carrier)));
		}
		return result;
	}
	public double[] copyCarrierInternalEnergy() {
		if (carriers == null) throw new IllegalStateException("Thermal cell grid has not been rebuilt");
		double[] result = new double[carriers.carrierCount()];
		for (int carrier = 0; carrier < result.length; carrier++) result[carrier] = carriers.internalEnergy(carrier);
		return result;
	}
	public int[] copyCarrierCells() {
		if (carriers == null) throw new IllegalStateException("Thermal cell grid has not been rebuilt");
		int[] result = new int[carriers.carrierCount()];
		for (int carrier = 0; carrier < result.length; carrier++) result[carrier] = carriers.cell(carrier);
		return result;
	}

	public void restoreCarrierInternalEnergy(double[] snapshot) {
		if (carriers == null || snapshot == null || snapshot.length != carriers.carrierCount()) {
			throw new IllegalArgumentException("Thermal carrier rollback snapshot is incompatible");
		}
		for (int carrier = 0; carrier < snapshot.length; carrier++) carriers.setInternalEnergy(carrier, snapshot[carrier]);
		rebuild(carriers);
	}

	private void solveCarrierCell(int cell) {
		SignedDyadic target = exactCarrierEnergyInCell(cell);
		internalEnergy[cell] = target.toDouble();
		if (!Double.isFinite(internalEnergy[cell])) throw new IllegalArgumentException("Thermal cell energy is not finite");
		solveCell(cell, target);
	}

	private void solveCell(int cell, double targetEnergy) { solveCell(cell, SignedDyadic.of(targetEnergy)); }

	private void solveCell(int cell, SignedDyadic target) {
		SignedDyadic reference = SignedDyadic.ZERO;
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier])
			reference = reference.add(SignedDyadic.of(cachedBaseEnergy(carrier)));
		// Solve in sensible-energy coordinates; a large arbitrary reference must not round away
		// the caloric curve before subtracting it from the authoritative target.
		double targetEnergy = target.subtract(reference).toDouble();
		if (!Double.isFinite(targetEnergy)) throw new IllegalArgumentException("Thermal cell sensible energy is invalid");
		double minimum = ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
		double energyAtMinimum = modeledCellSensibleEnergy(cell, minimum);
		double tolerance = Double.isFinite(energyAtMinimum) ? energyTolerance(targetEnergy, energyAtMinimum) : 0;
		if (targetEnergy < energyAtMinimum - tolerance) {
			throw new IllegalArgumentException("Thermal cell energy is outside the positive-temperature domain");
		}
		if (targetEnergy <= energyAtMinimum) {
			temperature[cell] = minimum;
			heatCapacity[cell] = modeledCellHeatCapacity(cell, minimum);
			updateLinearTemperatureInterval(cell);
			return;
		}

		double cursor = minimum;
		double cursorEnergy = energyAtMinimum;
		while (true) {
			double next = Double.POSITIVE_INFINITY;
			for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
				double referenceTemperature = carrierMaterial[carrier].referenceTemperatureK();
				if (referenceTemperature > cursor && referenceTemperature < next) next = referenceTemperature;
				for (ThermalMaterialDefinition.CaloricSegment segment : carrierMaterial[carrier].caloricSegments()) {
					if (segment.maximumTemperatureK() > cursor && segment.maximumTemperatureK() < next) {
						next = segment.maximumTemperatureK();
					}
				}
			}
			if (next == Double.POSITIVE_INFINITY) {
				double capacity = modeledCellHeatCapacity(cell, cursor);
				double solved = cursor + (Double.isFinite(cursorEnergy) ? (targetEnergy - cursorEnergy) / capacity
						: ThermalContactExchange.temperatureDifference(SignedDyadic.of(targetEnergy)
						.subtract(modeledCellSensibleEnergyExact(cell, cursor)), capacity));
				if (!(Double.isFinite(solved) && solved >= cursor)) {
					throw new IllegalArgumentException("Thermal cell state inversion overflowed");
				}
				temperature[cell] = solved;
				heatCapacity[cell] = modeledCellHeatCapacity(cell, solved);
				updateLinearTemperatureInterval(cell);
				return;
			}
			double middle = cursor + (next - cursor) * 0.5;
			double nextEnergy = modeledCellSensibleEnergy(cell, next);
			if (targetEnergy <= nextEnergy) {
				double capacity = modeledCellHeatCapacity(cell, middle);
				double fromLower = targetEnergy - cursorEnergy, fromUpper = nextEnergy - targetEnergy;
				double solved;
				if (Double.isInfinite(fromLower) && Double.isInfinite(fromUpper)) {
					SignedDyadic lower = SignedDyadic.of(targetEnergy).subtract(modeledCellSensibleEnergyExact(cell, cursor));
					SignedDyadic upper = modeledCellSensibleEnergyExact(cell, next).subtract(SignedDyadic.of(targetEnergy));
					solved = lower.compareTo(upper) <= 0 ? cursor + ThermalContactExchange.temperatureDifference(lower, capacity)
							: next - ThermalContactExchange.temperatureDifference(upper, capacity);
				} else solved = fromLower <= fromUpper ? cursor + fromLower / capacity : next - fromUpper / capacity;
				if (!Double.isFinite(solved)) throw new IllegalArgumentException("Thermal cell state inversion overflowed");
				temperature[cell] = Math.max(cursor, Math.min(next, solved));
				heatCapacity[cell] = modeledCellHeatCapacity(cell, temperature[cell]);
				updateLinearTemperatureInterval(cell);
				return;
			}
			cursorEnergy = nextEnergy;
			cursor = next;
		}
	}

	private void updateLinearTemperatureInterval(int cell) {
		double minimum = ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
		double maximum = Double.POSITIVE_INFINITY;
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
			List<ThermalMaterialDefinition.CaloricSegment> segments = carrierMaterial[carrier].caloricSegments();
			ThermalMaterialDefinition.CaloricSegment selected = null;
			for (int index = 0; index < segments.size(); index++) {
				ThermalMaterialDefinition.CaloricSegment segment = segments.get(index);
				if (temperature[cell] < segment.maximumTemperatureK() || index + 1 == segments.size()) {
					selected = segment;
					break;
				}
			}
			if (selected == null) throw new IllegalStateException("Thermal cell caloric segment lookup failed");
			double carrierMinimum = selected.minimumTemperatureK();
			double carrierMaximum = selected.maximumTemperatureK();
			if (selected == segments.getFirst()) carrierMinimum = ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
			if (selected == segments.getLast()) carrierMaximum = Double.MAX_VALUE;
			minimum = Math.max(minimum, carrierMinimum);
			maximum = Math.min(maximum, carrierMaximum);
		}
		if (!(maximum > minimum && temperature[cell] >= minimum && temperature[cell] <= maximum)) {
			throw new IllegalArgumentException("Thermal cell has no common linear caloric interval");
		}
		minimumTemperature[cell] = minimum;
		maximumTemperature[cell] = maximum;
	}

	private double modeledCellSensibleEnergy(int cell, double temperatureK) {
		SignedDyadic total = SignedDyadic.ZERO;
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
			var material = carrierMaterial[carrier];
			double value = ThermalContactExchange.energyChangeBetween(material, carrierMass[carrier], material.referenceTemperatureK(), temperatureK);
			if (!Double.isFinite(value)) return modeledCellSensibleEnergyExact(cell, temperatureK).toDouble();
			total = total.add(SignedDyadic.of(value));
		}
		return total.toDouble();
	}

	private SignedDyadic modeledCellSensibleEnergyExact(int cell, double temperatureK) {
		SignedDyadic total = SignedDyadic.ZERO;
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
			var material = carrierMaterial[carrier];
			total = total.add(ThermalContactExchange.exactEnergyChangeBetween(material, carrierMass[carrier], material.referenceTemperatureK(), temperatureK));
		}
		return total;
	}

	private double modeledCellHeatCapacity(int cell, double temperatureK) {
		double total = 0.0, correction = 0.0;
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier]) {
			double value = carrierMass[carrier] * carrierMaterial[carrier].specificHeatAt(temperatureK), next = total + value;
			correction += Math.abs(total) >= Math.abs(value) ? (total - next) + value : (value - next) + total;
			total = next;
		}
		total += correction;
		if (!(Double.isFinite(total) && total > 0.0)) throw new IllegalArgumentException("Thermal cell heat capacity is invalid");
		return total;
	}

	private double modeledEnergy(int carrier, double temperatureK) {
		ThermalMaterialDefinition material = carrierMaterial[carrier];
		double value = cachedBaseEnergy(carrier)
				+ carrierMass[carrier] * material.sensibleSpecificEnergyAt(temperatureK);
		if (!Double.isFinite(value)) throw new IllegalArgumentException("Thermal carrier modeled energy is invalid");
		return value;
	}

	private void validateCarrierAtCellTemperature(int carrier, double energy, double cellTemperature) {
		if (energy == modeledEnergy(carrier, cellTemperature)) return;
		ThermalMaterialDefinition material = carrierMaterial[carrier];
		double sensible = (energy - cachedBaseEnergy(carrier)) / carrierMass[carrier];
		double restored = material.temperatureForSensibleSpecificEnergy(sensible);
		double tolerance = Math.max(1.0E-9, Math.max(Math.ulp(restored), Math.ulp(cellTemperature)) * 16.0);
		if (Math.abs(restored - cellTemperature) > tolerance) {
			double residual = energy - modeledEnergy(carrier, cellTemperature);
			throw new IllegalArgumentException("Thermal carrier remainder left local equilibrium"
					+ " (carrier=" + carrier + ", cellTemperature=" + cellTemperature
					+ ", restoredTemperature=" + restored + ", energyResidual=" + residual
					+ ", mass=" + carrierMass[carrier] + ")");
		}
	}

	private double cachedBaseEnergy(int carrier) {
		double value = carrierBaseEnergy[carrier];
		if (Double.isNaN(value)) {
			value = carriers.baseEnergy(carrier);
			if (!Double.isFinite(value)) throw new IllegalArgumentException("Thermal carrier base energy is invalid");
			carrierBaseEnergy[carrier] = value;
		}
		return value;
	}

	private void validateCarrierEnergy(int carrier, double energy) {
		double mass = carrierMass[carrier];
		try {
			carrierMaterial[carrier].temperatureForSensibleSpecificEnergy((energy - cachedBaseEnergy(carrier)) / mass);
		} catch (IllegalArgumentException failure) {
			throw new IllegalArgumentException("Thermal carrier energy is invalid (carrier=" + carrier
					+ ", cell=" + carriers.cell(carrier) + ", material=" + carrierMaterial[carrier].id() + ")", failure);
		}
	}

	private void updateObservedTemperatureRange() {
		double minimum = Double.POSITIVE_INFINITY;
		double maximum = Double.NEGATIVE_INFINITY;
		for (int cell = 0; cell < cellCount; cell++) if (carrierCount[cell] > 0) {
			minimum = Math.min(minimum, temperature[cell]);
			maximum = Math.max(maximum, temperature[cell]);
		}
		observedTemperatureRange = minimum == Double.POSITIVE_INFINITY
				? null : new TemperatureRange(minimum, maximum);
	}

	private static double energyTolerance(double... values) {
		double tolerance = 0.0;
		for (double value : values) tolerance = Math.max(tolerance, Math.ulp(value) * 16.0);
		return tolerance;
	}
}
