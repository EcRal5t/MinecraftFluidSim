package mmm.thermal;

import mmm.units.SignedDyadic;

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
		default int supportMask(int carrier) { return 0; }
		ThermalMaterialDefinition material(int carrier);
		double mass(int carrier);
		double volume(int carrier);
		double baseEnergy(int carrier);
		default SignedDyadic exactBaseEnergy(int carrier) { return SignedDyadic.of(baseEnergy(carrier)); }
		double internalEnergy(int carrier);
		default SignedDyadic exactInternalEnergy(int carrier) { return SignedDyadic.of(internalEnergy(carrier)); }
		default SignedDyadic exactStoredEnergy() {
			SignedDyadic sum=SignedDyadic.ZERO;
			for(int carrier=0;carrier<carrierCount();carrier++)sum=sum.add(exactInternalEnergy(carrier));
			return sum;
		}
		/** Stores the supplied finite double unchanged; prepared caloric samples share this value. */
		void setInternalEnergy(int carrier, double energy);
		/** Bulk publication follows the same unchanged-double contract as individual writes. */
		default void setInternalEnergies(double[] energies) {
			if(energies.length!=carrierCount()) throw new IllegalArgumentException("Thermal energy layout differs");
			for(int carrier=0;carrier<energies.length;carrier++) setInternalEnergy(carrier,energies[carrier]);
		}
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
			for (int cell = 0; cell < cellCount; cell++) {
				if (carrierCount[cell] > 0) {
					conductivity[cell] /= totalVolume[cell];
					solveCarrierCell(cell);
				}
			}
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
				assignCellEnergy(cell, targetEnergy);
			} else {
				if (previousCarrierCount[cell] == 0 || !Double.isFinite(previousTemperature[cell])) {
					throw new IllegalArgumentException("Untouched thermal phase cell has no prepared LTE state");
				}
				temperature[cell] = previousTemperature[cell];
					internalEnergy[cell] = exactCarrierEnergyInCell(cell).toDouble();
				heatCapacity[cell] = ThermalCaloricInversion.heatCapacity(caloricInventory(cell), temperature[cell]);
				updateLinearTemperatureInterval(cell);
			}
		}
		updateObservedTemperatureRange();
	}

	/** Publishes derived state for an already prevalidated phase assignment; remote LTE states are retained. */
	void rebuildAssignedPhaseCell(CarrierAccess carrierAccess, int targetCell,
			SignedDyadic actualEnergy, ThermalStateFunction.State actualState) {
		double[] oldTemperature = temperature.clone(), oldCapacity = heatCapacity.clone();
		double[] oldMinimum = minimumTemperature.clone(), oldMaximum = maximumTemperature.clone();
		rebuildTopology(carrierAccess, targetCell, false);
		for (int cell = 0; cell < cellCount; cell++) if (carrierCount[cell] > 0) {
			conductivity[cell] /= totalVolume[cell];
			if (cell == targetCell) {
				internalEnergy[cell] = actualEnergy.toDouble();
				temperature[cell] = actualState.temperatureK();
				heatCapacity[cell] = actualState.heatCapacityJPerK();
				updateLinearTemperatureInterval(cell);
			} else {
				internalEnergy[cell] = exactCarrierEnergyInCell(cell).toDouble();
				temperature[cell] = oldTemperature[cell]; heatCapacity[cell] = oldCapacity[cell];
				minimumTemperature[cell] = oldMinimum[cell]; maximumTemperature[cell] = oldMaximum[cell];
			}
		}
		updateObservedTemperatureRange();
	}

	private void rebuildTopology(CarrierAccess carrierAccess, int prospectiveCell) {
		rebuildTopology(carrierAccess, prospectiveCell, true);
	}

	private void rebuildTopology(CarrierAccess carrierAccess, int prospectiveCell, boolean validateUntouched) {
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
			conductivity[cell] += volume * carrierMaterial[carrier].conductivityWPerMeterK();
		}
		// Bind derived reference caches before independent carrier validation.
		if (topologyCarrierCount > 0) carrierAccess.baseEnergy(0);
		if (validateUntouched)
			for (int carrier = 0; carrier < topologyCarrierCount; carrier++) if (carrierAccess.cell(carrier) != prospectiveCell)
				validateCarrierEnergy(carrier, carrierAccess.internalEnergy(carrier));
	}

	public void setCellInternalEnergies(double[] targetEnergy) {
		var plans = prepareCellInternalEnergies(targetEnergy);
		for (int cell = 0; cell < cellCount; cell++) if (plans[cell] != null) applyCellEnergy(cell, plans[cell]);
		updateObservedTemperatureRange();
	}

	/** Repartitions one edited cell while preserving every remote carrier's original U. */
	public void setCellInternalEnergy(int cell, double targetEnergy) {
		setCellInternalEnergy(cell, SignedDyadic.of(targetEnergy));
	}

	public void setCellInternalEnergy(int cell, SignedDyadic targetEnergy) {
		var plan = prepareCellInternalEnergy(cell, targetEnergy);
		if (plan == null) return;
		applyCellEnergy(cell, plan);
		updateObservedTemperatureRange();
	}

	CarrierAccess carrierAccess() { return carriers; }
	int firstCarrier(int cell) { return cellHead[cell]; }
	int nextCarrier(int carrier) { return carrierNext[carrier]; }

	private void assignCellEnergy(int cell, SignedDyadic requested) {
		applyCellEnergy(cell, prepareCellEnergy(cell, requested));
	}

	private void applyCellEnergy(int cell, ThermalCellEnergyAssignment.Plan plan) {
		for (ThermalCellEnergyAssignment.Entry entry : plan.entries()) carriers.setInternalEnergy(entry.item(), entry.energy());
		internalEnergy[cell] = plan.actualEnergy().toDouble();
		temperature[cell] = plan.actualState().temperatureK();
		heatCapacity[cell] = plan.actualState().heatCapacityJPerK();
		updateLinearTemperatureInterval(cell);
	}

	private ThermalCellEnergyAssignment.Plan prepareCellEnergy(int cell, SignedDyadic requested) {
		return ThermalCellEnergyAssignment.prepare(new ThermalCellEnergyAssignment.Inventory() {
			public int first() { return cellHead[cell]; }
			public int next(int carrier) { return carrierNext[carrier]; }
			public ThermalMaterialDefinition material(int carrier) { return carrierMaterial[carrier]; }
			public double mass(int carrier) { return carrierMass[carrier]; }
			public double baseEnergy(int carrier) { return cachedBaseEnergy(carrier); }
			public SignedDyadic exactBaseEnergy(int carrier) { return carriers.exactBaseEnergy(carrier); }
		}, requested);
	}

	/** Preflights one exact LTE target while preserving carriers and the current derived cell state. */
	void validateCellInternalEnergy(int cell, SignedDyadic targetEnergy) {
		prepareCellInternalEnergy(cell, targetEnergy);
	}

	private ThermalCellEnergyAssignment.Plan prepareCellInternalEnergy(int cell, SignedDyadic targetEnergy) {
		if (carriers == null || cell < 0 || cell >= cellCount || targetEnergy == null
				|| !Double.isFinite(targetEnergy.toDouble())) throw new IllegalArgumentException("Thermal cell energy edit is invalid");
		if (carrierCount[cell] == 0) {
			if (!targetEnergy.equals(SignedDyadic.ZERO)) throw new IllegalArgumentException("Empty thermal cell received energy");
			return null;
		}
		return prepareCellEnergy(cell, targetEnergy);
	}

	/** Validates a complete prospective LTE energy assignment without changing any authoritative carrier. */
	public void validateCellInternalEnergies(double[] targetEnergy) { prepareCellInternalEnergies(targetEnergy); }

	private ThermalCellEnergyAssignment.Plan[] prepareCellInternalEnergies(double[] targetEnergy) {
		if (carriers == null) throw new IllegalStateException("Thermal cell grid has not been rebuilt");
		if (targetEnergy == null || targetEnergy.length != cellCount)
			throw new IllegalArgumentException("Thermal cell energy array length changed");
		var plans = new ThermalCellEnergyAssignment.Plan[cellCount];
		for (int cell = 0; cell < cellCount; cell++) plans[cell] = prepareCellInternalEnergy(cell, SignedDyadic.of(targetEnergy[cell]));
		return plans;
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
				try { if (carriers.internalEnergy(carrier) == modeledEnergy(carrier, temperature[cell])) continue; }
				catch (ThermalStateFunction.EnergyRepresentationException overflow) { /* Validate the stored remainder directly. */ }
				ThermalMaterialDefinition material = carrierMaterial[carrier];
				double restored = ThermalCaloricInversion.owner(material, carrierMass[carrier], carriers.internalEnergy(carrier), cachedBaseEnergy(carrier)).temperatureK();
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
			result = result.add(carriers.exactInternalEnergy(carrier));
		}
		return result;
	}


	private void solveCarrierCell(int cell) {
		var target=new ThermalCaloricInversion.Sum();
		for(int carrier=cellHead[cell];carrier>=0;carrier=carrierNext[carrier])target.add(carriers.internalEnergy(carrier));
		internalEnergy[cell] = target.value();
		if (!Double.isFinite(internalEnergy[cell])) throw new IllegalArgumentException("Thermal cell energy is not finite");
		solveCell(cell, internalEnergy[cell]);
	}

	private void solveCell(int cell, SignedDyadic target) { solveCell(cell,target.toDouble()); }
	private void solveCell(int cell, double target) {
		var reference=new ThermalCaloricInversion.Sum();
		for (int carrier = cellHead[cell]; carrier >= 0; carrier = carrierNext[carrier])
			reference.add(cachedBaseEnergy(carrier));
		var state = ThermalCaloricInversion.solve(caloricInventory(cell), target-reference.value());
		temperature[cell] = state.temperatureK(); heatCapacity[cell] = state.heatCapacityJPerK();
		updateLinearTemperatureInterval(cell);
	}

	private ThermalCaloricInversion.Inventory caloricInventory(int cell) {
		return new ThermalCaloricInversion.Inventory() {
			public int first() { return cellHead[cell]; } public int next(int carrier) { return carrierNext[carrier]; }
			public ThermalMaterialDefinition material(int carrier) { return carrierMaterial[carrier]; }
			public double mass(int carrier) { return carrierMass[carrier]; }
		};
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

	private double modeledEnergy(int carrier, double temperatureK) {
		ThermalMaterialDefinition material = carrierMaterial[carrier];
		double value = ThermalCaloricInversion.energyAt(material, carrierMass[carrier], cachedBaseEnergy(carrier), temperatureK);
		if (!Double.isFinite(value)) throw new IllegalArgumentException("Thermal carrier modeled energy is invalid");
		return value;
	}

	double cachedBaseEnergy(int carrier) {
		double value = carrierBaseEnergy[carrier];
		if (Double.isNaN(value)) {
			value = carriers.baseEnergy(carrier);
			if (!Double.isFinite(value)) throw new IllegalArgumentException("Thermal carrier base energy is invalid");
			carrierBaseEnergy[carrier] = value;
		}
		return value;
	}

	private void validateCarrierEnergy(int carrier, double energy) {
		try {
			ThermalCaloricInversion.owner(carrierMaterial[carrier],carrierMass[carrier],energy,cachedBaseEnergy(carrier));
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

}
