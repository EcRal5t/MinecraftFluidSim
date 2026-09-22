package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.units.SolverUnitScale;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the per-liquid-marker internal energy column: ordinal storage, exact sums, modeled
 * energies from temperature, and every liquid-ordinal commit with its audit booking. Liquid
 * ordinals change only through these methods, so the column revision is the liquid thermal
 * truth token.
 */
public final class LiquidEnergyColumn {
	// Derived values are bounded and keyed by every input; ordinal edits need no cache invalidation.
	private final LinkedHashMap<BaseEnergyKey, Double> baseEnergies = new LinkedHashMap<>(128, 0.75F, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<BaseEnergyKey, Double> eldest) {
			return size() > 1024;
		}
	};
	private final ThermalRegistry registry;
	private final ThermalStateFunction stateFunction;
	private final SolverUnitScale unitScale;
	private final int[] materialByFluidHandle;
	private final double insertionTemperatureK;
	private final ThermalAuditState audit;
	private double[] internalEnergy;
	private long revision;

	public LiquidEnergyColumn(
			ThermalRegistry registry, ThermalStateFunction stateFunction, SolverUnitScale unitScale,
			int[] materialByFluidHandle, double insertionTemperatureK, ThermalAuditState audit,
			int initialCapacity, ThermalCarrierContext markers
	) {
		this(registry, stateFunction, unitScale, materialByFluidHandle, insertionTemperatureK,
				audit, initialCapacity, markers, null);
	}

	LiquidEnergyColumn(
			ThermalRegistry registry, ThermalStateFunction stateFunction, SolverUnitScale unitScale,
			int[] materialByFluidHandle, double insertionTemperatureK, ThermalAuditState audit,
			int initialCapacity, ThermalCarrierContext markers, double[] restoredEnergy
	) {
		this.registry = registry;
		this.stateFunction = stateFunction;
		this.unitScale = unitScale;
		this.materialByFluidHandle = materialByFluidHandle;
		this.insertionTemperatureK = insertionTemperatureK;
		this.audit = audit;
		this.internalEnergy = new double[initialCapacity];
		if (restoredEnergy != null) {
			if (restoredEnergy.length != markers.markerCount()) {
				throw new IllegalArgumentException("Restored liquid energy count differs from marker truth");
			}
			System.arraycopy(restoredEnergy, 0, internalEnergy, 0, restoredEnergy.length);
			return;
		}
		for (int marker = 0; marker < markers.markerCount(); marker++) {
			int thermalHandle = materialByFluidHandle[markers.fluidMaterialHandle(marker)];
			double physicalMass = unitScale.kilograms(markers.mass(marker));
			ChemicalMixtureRecord mixture = markers.mixture(marker);
			double base = baseEnergy(thermalHandle, physicalMass, mixture, markers.chemicalRegistry());
			internalEnergy[marker] = base + physicalMass
					* registry.material(thermalHandle).sensibleSpecificEnergyAt(insertionTemperatureK);
			if (!Double.isFinite(internalEnergy[marker])) {
				throw new IllegalArgumentException("Thermal marker modeled energy is invalid");
			}
		}
	}

	public long revision() { return revision; }

	public int thermalMaterialHandleForFluid(int fluidHandle) {
		if (fluidHandle < 0 || fluidHandle >= materialByFluidHandle.length) {
			throw new IllegalArgumentException("Unknown liquid phase carrier");
		}
		return materialByFluidHandle[fluidHandle];
	}

	public double energy(int ordinal) { return internalEnergy[ordinal]; }

	/** Carrier-access write path: validates finiteness exactly like the composed carrier view. */
	void setEnergy(int ordinal, double energy) {
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal carrier energy is not finite");
		internalEnergy[ordinal] = energy;
		revision++;
	}

	/** Raw paired write for reseed splits; values are produced by validated modeling upstream. */
	void assignReseededEnergies(int source, double sourceEnergy, int destination, double destinationEnergy) {
		internalEnergy[source] = sourceEnergy;
		internalEnergy[destination] = destinationEnergy;
		revision++;
	}

	void moveEnergy(int source, int destination) {
		internalEnergy[destination] = internalEnergy[source];
		revision++;
	}

	void mergeReseedEnergy(int target, int source) {
		internalEnergy[target] += internalEnergy[source];
		if (!Double.isFinite(internalEnergy[target])) {
			throw new IllegalArgumentException("Reseeded thermal merge energy is invalid");
		}
		revision++;
	}

	public double[] copyEnergies(int count) { return Arrays.copyOf(internalEnergy, count); }

	void adoptRestored(double[] restored) {
		ensureCapacity(restored.length);
		System.arraycopy(restored, 0, internalEnergy, 0, restored.length);
		revision++;
	}

	void ensureCapacity(int capacity) {
		if (capacity > internalEnergy.length) internalEnergy = Arrays.copyOf(internalEnergy, capacity);
	}

	public double totalEnergy(int count) {
		double total = 0.0;
		for (int marker = 0; marker < count; marker++) total += internalEnergy[marker];
		return total;
	}

	public SignedDyadic exactLiquidEnergy(int count) {
		return SignedDyadic.sum(internalEnergy, count);
	}

	public double temperature(int marker, ThermalCarrierContext markers) {
		ThermalMaterialDefinition material = material(marker, markers);
		double sensible = (internalEnergy[marker] - baseEnergy(marker, markers))
				/ unitScale.kilograms(markers.mass(marker));
		return material.temperatureForSensibleSpecificEnergy(sensible);
	}

	public void requireReactionState(
			int marker, ChemicalMixtureRecord candidateMixture, double candidateMass, ThermalCarrierContext markers
	) {
		ThermalMaterialDefinition material = material(marker, markers);
		int thermalHandle = materialByFluidHandle[markers.fluidMaterialHandle(marker)];
		double physicalMass = unitScale.kilograms(candidateMass);
		double base = baseEnergy(
				thermalHandle, physicalMass, candidateMixture, markers.chemicalRegistry());
		double sensible = (internalEnergy[marker] - base) / physicalMass;
		if (sensible < material.sensibleSpecificEnergyAt(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)) {
			throw new ThermalStateFunction.EnergyOutsideValidityException();
		}
		material.temperatureForSensibleSpecificEnergy(sensible);
	}

	void addExternalMarker(int marker, ThermalCarrierContext markers) {
		ensureCapacity(marker + 1);
		internalEnergy[marker] = modeledEnergy(marker, markers.mass(marker), markers.mixture(marker),
				insertionTemperatureK, markers);
		audit.update(ledger -> ledger.withExternalSourceEnergy(SignedDyadic.of(internalEnergy[marker])));
		revision++;
	}

	public ExternalLiquidInsertionPlan planExternalLiquidInsertions(
			List<LiquidInsertion> insertions, double totalEnergy, ThermalCarrierContext markers
	) {
		if (insertions == null || insertions.isEmpty() || !Double.isFinite(totalEnergy)) {
			throw new IllegalArgumentException("Explicit thermal liquid insertion is invalid");
		}
		ArrayList<ThermalStateFunction.InventoryPortion> inventory = new ArrayList<>(insertions.size());
		for (LiquidInsertion insertion : insertions) {
			if (insertion.fluidMaterialHandle() >= materialByFluidHandle.length) {
				throw new IllegalArgumentException("Unknown fluid material in thermal insertion");
			}
			inventory.add(new ThermalStateFunction.InventoryPortion(
					materialByFluidHandle[insertion.fluidMaterialHandle()],
					unitScale.kilograms(insertion.mass()),
					ThermalStateFunction.stableAmounts(insertion.mixture(), markers.chemicalRegistry())));
		}
		double temperatureK = stateFunction.stateForInventory(inventory, totalEnergy).temperatureK();
		double[] energies = new double[insertions.size()];
		for (int index = 0; index < energies.length; index++) {
			LiquidInsertion insertion = insertions.get(index);
			energies[index] = modeledEnergy(insertion.fluidMaterialHandle(), insertion.mass(),
					insertion.mixture(), temperatureK, markers.chemicalRegistry());
		}
		SignedDyadic source = SignedDyadic.of(totalEnergy);
		return new ExternalLiquidInsertionPlan(energies, source, SignedDyadic.sum(energies).subtract(source));
	}

	void commitExternalLiquidInsertions(int firstOrdinal, ExternalLiquidInsertionPlan plan) {
		if (plan == null || firstOrdinal < 0 || firstOrdinal + plan.energies.length > internalEnergy.length) {
			throw new IllegalArgumentException("Thermal liquid insertion ordinals are incompatible");
		}
		for (int index = 0; index < plan.energies.length; index++) {
			int ordinal = firstOrdinal + index;
			if (Double.doubleToRawLongBits(internalEnergy[ordinal]) != Double.doubleToRawLongBits(0.0)) {
				throw new IllegalStateException("Explicit thermal insertion did not reserve an empty energy ordinal");
			}
			internalEnergy[ordinal] = plan.energies[index];
		}
		audit.update(ledger -> ledger.withExternalSourceEnergy(plan.source()).withRoundoff(plan.roundoff()));
		revision++;
	}

	void removeExternalAndSwap(int index, int last) {
		audit.update(ledger -> ledger.withExternalSinkEnergy(SignedDyadic.of(internalEnergy[index])));
		if (index != last) internalEnergy[index] = internalEnergy[last];
		revision++;
	}

	void addInternalMarker(int marker, double energy) {
		ensureCapacity(marker + 1);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Internal phase marker energy is not finite");
		internalEnergy[marker] = energy;
		revision++;
	}

	double removeInternalAndSwap(int index, int last) {
		double removed = internalEnergy[index];
		if (index != last) internalEnergy[index] = internalEnergy[last];
		revision++;
		return removed;
	}

	void addLiquidEnergy(int marker, double energy) {
		double result = internalEnergy[marker] + energy;
		if (!Double.isFinite(result)) throw new IllegalArgumentException("Internal phase energy transfer overflow");
		internalEnergy[marker] = result;
		revision++;
	}

	public Truncation planTruncate(
			int marker, double newMass, ChemicalMixtureRecord keptMixture,
			double previousTemperatureK, ThermalCarrierContext markers
	) {
		double previousEnergy = internalEnergy[marker];
		double newEnergy = newMass == 0.0 ? 0.0
				: modeledEnergy(marker, newMass, keptMixture, previousTemperatureK, markers);
		return new Truncation(newEnergy, previousEnergy - newEnergy);
	}

	void commitTruncate(int marker, Truncation plan) {
		if (plan == null) return;
		SignedDyadic carrierEnergy = SignedDyadic.of(internalEnergy[marker])
				.subtract(SignedDyadic.of(plan.newEnergy()));
		internalEnergy[marker] = plan.newEnergy();
		audit.update(ledger -> ledger.withExternalSinkEnergy(carrierEnergy));
		revision++;
	}

	void commitInternalTruncate(int marker, Truncation plan) {
		if (plan == null) return;
		internalEnergy[marker] = plan.newEnergy();
		revision++;
	}

	void resetMarkerTemperature(int marker, double temperatureK, ThermalCarrierContext markers) {
		internalEnergy[marker] = modeledEnergy(marker, markers.mass(marker), markers.mixture(marker), temperatureK, markers);
		revision++;
	}

	public double modeledEnergy(
			int marker, double mass, ChemicalMixtureRecord mixture, double temperatureK, ThermalCarrierContext markers
	) {
		return modeledEnergy(markers.fluidMaterialHandle(marker), mass, mixture, temperatureK,
				markers.chemicalRegistry());
	}

	public double modeledEnergy(
			int fluidMaterialHandle, double mass, ChemicalMixtureRecord mixture, double temperatureK,
			ChemicalRegistry chemicalRegistry
	) {
		int thermalHandle = materialByFluidHandle[fluidMaterialHandle];
		ThermalMaterialDefinition material = registry.material(thermalHandle);
		double physicalMass = unitScale.kilograms(mass);
		double base = baseEnergy(thermalHandle, physicalMass, mixture, chemicalRegistry);
		double energy = base + physicalMass * material.sensibleSpecificEnergyAt(temperatureK);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal marker modeled energy is invalid");
		return energy;
	}

	private double baseEnergy(int marker, ThermalCarrierContext markers) {
		int material = materialByFluidHandle[markers.fluidMaterialHandle(marker)];
		double mass = unitScale.kilograms(markers.mass(marker));
		ChemicalMixtureRecord mixture = markers.mixture(marker);
		ChemicalRegistry chemistry = markers.chemicalRegistry();
		return baseEnergy(material, mass, mixture, chemistry);
	}

	private double baseEnergy(int material, double mass, ChemicalMixtureRecord mixture, ChemicalRegistry chemistry) {
		BaseEnergyKey key = new BaseEnergyKey(material, Double.doubleToRawLongBits(mass), mixture, chemistry);
		return baseEnergies.computeIfAbsent(key, ignored -> stateFunction.baseEnergy(material, mass, mixture, chemistry));
	}

	private ThermalMaterialDefinition material(int marker, ThermalCarrierContext markers) {
		return registry.material(materialByFluidHandle[markers.fluidMaterialHandle(marker)]);
	}

	private record BaseEnergyKey(int materialHandle, long massBits, ChemicalMixtureRecord mixture, ChemicalRegistry registry) { }

	public record Truncation(double newEnergy, double removedEnergy) {
		public Truncation {
			if (!Double.isFinite(newEnergy) || !Double.isFinite(removedEnergy)) {
				throw new IllegalArgumentException("Thermal truncation ledger is invalid");
			}
		}
	}
	public record LiquidInsertion(int fluidMaterialHandle, double mass, ChemicalMixtureRecord mixture) {
		public LiquidInsertion {
			if (fluidMaterialHandle < 0 || !(Double.isFinite(mass) && mass > 0.0)) {
				throw new IllegalArgumentException("Thermal liquid insertion inventory is invalid");
			}
		}
	}
	public record ExternalLiquidInsertionPlan(double[] energies, SignedDyadic source, SignedDyadic roundoff) {
		public ExternalLiquidInsertionPlan {
			if (energies == null || energies.length == 0 || source == null || roundoff == null) {
				throw new IllegalArgumentException("Thermal liquid insertion plan is incomplete");
			}
			energies = energies.clone();
		}
		@Override public double[] energies() { return energies.clone(); }
	}
}
