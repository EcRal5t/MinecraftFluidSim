package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.units.SolverUnitScale;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Owns the per-liquid-marker internal energy column: ordinal storage, exact sums, modeled
 * energies from temperature, and every liquid-ordinal commit with its audit booking. Liquid
 * ordinals change only through these methods, so the column revision is the liquid thermal
 * truth token.
 */
public final class LiquidEnergyColumn {
	private final ThermalRegistry registry;
	private final ThermalStateFunction stateFunction;
	private final SolverUnitScale unitScale;
	private final int[] materialByFluidHandle;
	private final double insertionTemperatureK;
	private final ThermalAuditState audit;
	private double[] internalEnergy;
	private double[] cachedBaseEnergy;
	private long[] cachedMassBits;
	private int[] cachedMaterial;
	private ChemicalMixtureRecord[] cachedMixture;
	private ChemicalRegistry cachedChemistry;
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
		cachedBaseEnergy = new double[initialCapacity]; Arrays.fill(cachedBaseEnergy, Double.NaN);
		cachedMassBits = new long[initialCapacity]; cachedMaterial = new int[initialCapacity];
		cachedMixture = new ChemicalMixtureRecord[initialCapacity];
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
			internalEnergy[marker] = ThermalCaloricInversion.energyAt(registry.material(thermalHandle), physicalMass, base, insertionTemperatureK);
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
		if (capacity <= internalEnergy.length) return;
		int previous = internalEnergy.length;
		internalEnergy = Arrays.copyOf(internalEnergy, capacity);
		cachedBaseEnergy = Arrays.copyOf(cachedBaseEnergy, capacity);
		Arrays.fill(cachedBaseEnergy, previous, capacity, Double.NaN);
		cachedMassBits = Arrays.copyOf(cachedMassBits, capacity);
		cachedMaterial = Arrays.copyOf(cachedMaterial, capacity);
		cachedMixture = Arrays.copyOf(cachedMixture, capacity);
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
		return ThermalCaloricInversion.owner(material, unitScale.kilograms(markers.mass(marker)),
				internalEnergy[marker], baseEnergy(marker, markers)).temperatureK();
	}

	/** Prepares immutable caloric data for a prospective conservative liquid inventory. */
	public CaloricInventory caloricInventory(int fluidMaterialHandle, double mass, ChemicalMixtureRecord mixture,
			ChemicalRegistry chemistry) {
		int handle = thermalMaterialHandleForFluid(fluidMaterialHandle);
		double physicalMass = unitScale.kilograms(mass);
		if (!(Double.isFinite(physicalMass) && physicalMass > 0))
			throw new IllegalArgumentException("Prospective liquid mass is invalid");
		return new CaloricInventory(handle, physicalMass, mixture, chemistry);
	}

	/** Immutable prospective inventory: repeated caloric evaluations share one reference-energy calculation. */
	public final class CaloricInventory {
		private final int handle;
		private final double mass, base;
		private final ThermalMaterialDefinition material;
		private final ChemicalMixtureRecord mixture;
		private final ChemicalRegistry chemistry;
		private CaloricInventory(int handle, double mass, ChemicalMixtureRecord mixture, ChemicalRegistry chemistry) {
			this.handle = handle; this.mass = mass; this.mixture = mixture; this.chemistry = chemistry;
			material = registry.material(handle); base = baseEnergy(handle, mass, mixture, chemistry);
		}
		public double energy(double temperature) {
			double value = ThermalCaloricInversion.energyAt(material, mass, base, temperature);
			if (!Double.isFinite(value)) throw new IllegalArgumentException("Prospective liquid energy is invalid");
			return value;
		}
		public double temperature(double energy) {
			if (!Double.isFinite(energy)) throw new IllegalArgumentException("Prospective liquid energy is invalid");
			return temperature(SignedDyadic.of(energy));
		}
		public double temperature(SignedDyadic energy) {
			if (energy == null || !Double.isFinite(energy.toDouble()))
				throw new IllegalArgumentException("Prospective liquid energy is invalid");
			return ThermalCaloricInversion.single(material, mass, energy.subtract(SignedDyadic.of(base))).temperatureK();
		}
		public double roundoff(double temperature) {
			return stateFunction.baseEnergyRoundoff(handle, mass, mixture, chemistry)
					+ ThermalCaloricInversion.modeledRoundoff(material, mass, base, temperature);
		}
	}

	/** Pure inversion for a prospective conservative liquid inventory. */
	public double temperatureForInventory(int fluidMaterialHandle, double mass, ChemicalMixtureRecord mixture,
			double energy, ChemicalRegistry chemistry) {
		int handle = thermalMaterialHandleForFluid(fluidMaterialHandle);
		double physicalMass = unitScale.kilograms(mass);
		if (!(Double.isFinite(physicalMass) && physicalMass > 0 && Double.isFinite(energy)))
			throw new IllegalArgumentException("Prospective liquid energy or mass is invalid");
		return ThermalCaloricInversion.owner(registry.material(handle), physicalMass, energy,
				baseEnergy(handle, physicalMass, mixture, chemistry)).temperatureK();
	}

	/** Roundoff in a prospective caloric evaluation, including temperature and mass storage. */
	public double modeledEnergyRoundoff(int fluidMaterialHandle, double mass, ChemicalMixtureRecord mixture,
			double temperature, ChemicalRegistry chemistry) {
		int handle = thermalMaterialHandleForFluid(fluidMaterialHandle);
		double physicalMass = unitScale.kilograms(mass);
		var material = registry.material(handle);
		double base = baseEnergy(handle, physicalMass, mixture, chemistry);
		return stateFunction.baseEnergyRoundoff(handle, physicalMass, mixture, chemistry)
				+ ThermalCaloricInversion.modeledRoundoff(material, physicalMass, base, temperature);
	}

	public void requireReactionState(
			int marker, ChemicalMixtureRecord candidateMixture, double candidateMass, ThermalCarrierContext markers
	) {
		ThermalMaterialDefinition material = material(marker, markers);
		int thermalHandle = materialByFluidHandle[markers.fluidMaterialHandle(marker)];
		double physicalMass = unitScale.kilograms(candidateMass);
		double base = baseEnergy(
				thermalHandle, physicalMass, candidateMixture, markers.chemicalRegistry());
		ThermalCaloricInversion.owner(material, physicalMass, internalEnergy[marker], base);
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
			temperatureForInventory(insertion.fluidMaterialHandle(), insertion.mass(), insertion.mixture(),
					energies[index], markers.chemicalRegistry());
		}
		SignedDyadic source = SignedDyadic.of(totalEnergy);
		SignedDyadic roundoff = SignedDyadic.sum(energies).subtract(source);
		ThermalAuditLedger before = audit.ledger();
		ThermalAuditLedger after = before.withExternalSourceEnergy(source).withRoundoff(roundoff);
		after.expectedInventory();
		return new ExternalLiquidInsertionPlan(this, energies, before, after);
	}

	/** Preflights the configured insertion temperature separately for every stored carrier. */
	public double[] planDefaultLiquidInsertions(List<LiquidInsertion> insertions, ThermalCarrierContext markers) {
		if (insertions == null) throw new IllegalArgumentException("Liquid insertions are missing");
		double[] energies = new double[insertions.size()];
		for (int index = 0; index < energies.length; index++) {
			LiquidInsertion insertion = insertions.get(index);
			energies[index] = modeledEnergy(insertion.fluidMaterialHandle(), insertion.mass(),
					insertion.mixture(), insertionTemperatureK, markers.chemicalRegistry());
			temperatureForInventory(insertion.fluidMaterialHandle(), insertion.mass(), insertion.mixture(),
					energies[index], markers.chemicalRegistry());
		}
		return energies;
	}

	/** Writes already modeled energies into the empty ordinals reserved by the carrier coordinator. */
	void commitInternalLiquidInsertions(int firstOrdinal, double[] energies) {
		if (energies == null || firstOrdinal < 0 || firstOrdinal + energies.length > internalEnergy.length)
			throw new IllegalArgumentException("Thermal liquid insertion ordinals are incompatible");
		for (int index = 0; index < energies.length; index++) {
			if (!Double.isFinite(energies[index]) || internalEnergy[firstOrdinal + index] != 0.0)
				throw new IllegalStateException("Thermal liquid insertion did not reserve an empty energy ordinal");
		}
		System.arraycopy(energies, 0, internalEnergy, firstOrdinal, energies.length);
		if (energies.length > 0) revision++;
	}

	void commitExternalLiquidInsertions(int firstOrdinal, ExternalLiquidInsertionPlan plan) {
		if (plan == null || plan.owner != this || firstOrdinal < 0
				|| firstOrdinal > internalEnergy.length - plan.energies.length) {
			throw new IllegalArgumentException("Thermal liquid insertion ordinals are incompatible");
		}
		if (audit.ledger() != plan.before)
			throw new IllegalStateException("Explicit thermal insertion audit changed after planning");
		for (int index = 0; index < plan.energies.length; index++) {
			if (Double.doubleToRawLongBits(internalEnergy[firstOrdinal + index]) != Double.doubleToRawLongBits(0.0))
				throw new IllegalStateException("Explicit thermal insertion did not reserve an empty energy ordinal");
		}
		System.arraycopy(plan.energies, 0, internalEnergy, firstOrdinal, plan.energies.length);
		audit.replace(plan.after);
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
		double energy = ThermalCaloricInversion.energyAt(material, physicalMass, base, temperatureK);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal marker modeled energy is invalid");
		return energy;
	}

	double baseEnergy(int marker, ThermalCarrierContext markers) {
		int material = materialByFluidHandle[markers.fluidMaterialHandle(marker)];
		double mass = unitScale.kilograms(markers.mass(marker));
		ChemicalMixtureRecord mixture = markers.mixture(marker);
		ChemicalRegistry chemistry = markers.chemicalRegistry();
		if (cachedChemistry != chemistry) {
			Arrays.fill(cachedBaseEnergy, Double.NaN); cachedChemistry = chemistry;
		}
		long massBits = Double.doubleToRawLongBits(mass);
		if (!Double.isNaN(cachedBaseEnergy[marker]) && cachedMassBits[marker] == massBits
				&& cachedMaterial[marker] == material && Objects.equals(cachedMixture[marker], mixture))
			return cachedBaseEnergy[marker];
		double value = baseEnergy(material, mass, mixture, chemistry);
		cachedBaseEnergy[marker] = value; cachedMassBits[marker] = massBits;
		cachedMaterial[marker] = material; cachedMixture[marker] = mixture;
		return value;
	}

	private double baseEnergy(int material, double mass, ChemicalMixtureRecord mixture, ChemicalRegistry chemistry) {
		return stateFunction.baseEnergy(material, mass, mixture, chemistry);
	}

	private ThermalMaterialDefinition material(int marker, ThermalCarrierContext markers) {
		return registry.material(materialByFluidHandle[markers.fluidMaterialHandle(marker)]);
	}

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
	/** Validated stored energies and complete audit replacement bound to their planning owner. */
	public static final class ExternalLiquidInsertionPlan {
		private final LiquidEnergyColumn owner;
		private final double[] energies;
		private final ThermalAuditLedger before, after;
		private ExternalLiquidInsertionPlan(LiquidEnergyColumn owner, double[] energies,
				ThermalAuditLedger before, ThermalAuditLedger after) {
			this.owner = owner; this.energies = energies.clone();
			this.before = before; this.after = after;
		}
	}
}
