package mmm.thermal;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalRegistry;
import mmm.units.SolverUnitScale;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Caloric functions and derived caches over authoritative liquid inventory rows.
 */
public final class LiquidEnergyColumn {
	private final ThermalRegistry registry;
	private final ThermalStateFunction stateFunction;
	private final SolverUnitScale unitScale;
	private final int[] materialByFluidHandle;
	private final double insertionTemperatureK;
	private final ThermalAuditState audit;
	private final ThermalCarrierContext owners;
	private double[] cachedBaseEnergy;
	private long[] cachedMassKilogramsBits;
	private int[] cachedMaterial;
	private ChemicalMixtureRecord[] cachedMixture;
	private double[] cachedTemperature;
	private long[] cachedEnergyBits;
	private record CaloricState(int material, long mass, long base, long energy, ChemicalMixtureRecord mixture, double temperature) { }
	private volatile CaloricState commonState;
	private ChemicalRegistry cachedChemistry;

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
		this(registry,stateFunction,unitScale,materialByFluidHandle,insertionTemperatureK,audit,initialCapacity,markers,restoredEnergy,true);
	}
	LiquidEnergyColumn(ThermalRegistry registry, ThermalStateFunction stateFunction, SolverUnitScale unitScale,
			int[] materialByFluidHandle, double insertionTemperatureK, ThermalAuditState audit,
			int initialCapacity, ThermalCarrierContext markers, double[] restoredEnergy, boolean initializeOwners) {
		this.registry = registry;
		this.stateFunction = stateFunction;
		this.unitScale = unitScale;
		this.materialByFluidHandle = materialByFluidHandle;
		this.insertionTemperatureK = insertionTemperatureK;
		this.audit = audit;
		this.owners = Objects.requireNonNull(markers, "Liquid thermal owners");
		cachedBaseEnergy = new double[initialCapacity]; Arrays.fill(cachedBaseEnergy, Double.NaN);
		cachedMassKilogramsBits = new long[initialCapacity]; cachedMaterial = new int[initialCapacity];
		cachedMixture = new ChemicalMixtureRecord[initialCapacity];
		cachedTemperature = new double[initialCapacity]; Arrays.fill(cachedTemperature, Double.NaN);
		cachedEnergyBits = new long[initialCapacity];
		if (!initializeOwners && restoredEnergy == null) return;
		if (restoredEnergy != null) {
			if (restoredEnergy.length != markers.markerCount()) {
				throw new IllegalArgumentException("Restored liquid energy count differs from marker truth");
			}
			owners.setLiquidInternalEnergies(restoredEnergy);
			return;
		}
		double[] initialEnergy = new double[markers.markerCount()];
		for (int marker = 0; marker < markers.markerCount(); marker++) {
			int thermalHandle = materialByFluidHandle[markers.fluidMaterialHandle(marker)];
			double physicalMass = unitScale.kilograms(markers.mass(marker));
			ChemicalMixtureRecord mixture = markers.mixture(marker);
			double base = baseEnergy(thermalHandle, physicalMass, mixture, markers.chemicalRegistry());
			double energy = ThermalCaloricInversion.energyAt(stateFunction.materialForComposition(thermalHandle,physicalMass,mixture,markers.chemicalRegistry()), physicalMass, base, insertionTemperatureK);
			if (!Double.isFinite(energy)) {
				throw new IllegalArgumentException("Thermal marker modeled energy is invalid");
			}
			ThermalCaloricInversion.owner(stateFunction.materialForComposition(thermalHandle,physicalMass,mixture,markers.chemicalRegistry()), physicalMass,
					energy, base);
			initialEnergy[marker] = energy;
		}
		owners.setLiquidInternalEnergies(initialEnergy);
	}

	public long revision() { return owners.liquidInventoryRevision(); }

	public int thermalMaterialHandleForFluid(int fluidHandle) {
		if (fluidHandle < 0 || fluidHandle >= materialByFluidHandle.length) {
			throw new IllegalArgumentException("Unknown liquid phase carrier");
		}
		return materialByFluidHandle[fluidHandle];
	}

	public double energy(int ordinal) { return owners.liquidInternalEnergy(ordinal); }

	/** Carrier-access write path: validates finiteness exactly like the composed carrier view. */
	void setEnergy(int ordinal, double energy) {
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal carrier energy is not finite");
		owners.setLiquidInternalEnergy(ordinal, energy);
	}

	public double[] copyEnergies(int count) {
		if (count < 0 || count > owners.markerCount()) throw new IllegalArgumentException("Liquid inventory count differs");
		double[] energies = new double[count];
		for (int ordinal = 0; ordinal < count; ordinal++) energies[ordinal] = energy(ordinal);
		return energies;
	}

	void adoptRestored(double[] restored) {
		ensureCapacity(restored.length);
		owners.setLiquidInternalEnergies(restored);
	}

	void ensureCapacity(int capacity) {
		if (capacity <= cachedBaseEnergy.length) return;
		int previous = cachedBaseEnergy.length;
		cachedBaseEnergy = Arrays.copyOf(cachedBaseEnergy, capacity);
		Arrays.fill(cachedBaseEnergy, previous, capacity, Double.NaN);
		cachedMassKilogramsBits = Arrays.copyOf(cachedMassKilogramsBits, capacity);
		cachedMaterial = Arrays.copyOf(cachedMaterial, capacity);
		cachedMixture = Arrays.copyOf(cachedMixture, capacity);
		cachedTemperature = Arrays.copyOf(cachedTemperature, capacity);
		Arrays.fill(cachedTemperature, previous, capacity, Double.NaN);
		cachedEnergyBits = Arrays.copyOf(cachedEnergyBits, capacity);
	}

	public double totalEnergy(int count) {
		double total = 0.0;
		for (int marker = 0; marker < count; marker++) total += energy(marker);
		return total;
	}

	public SignedDyadic exactLiquidEnergy(int count) {
		SignedDyadic total = SignedDyadic.ZERO;
		for (int ordinal = 0; ordinal < count; ordinal++) total = total.add(SignedDyadic.of(energy(ordinal)));
		return total;
	}

	public double temperature(int marker, ThermalCarrierContext markers) {
		double base = baseEnergy(marker, markers);
		long energy = Double.doubleToRawLongBits(energy(marker));
		if (!Double.isNaN(cachedTemperature[marker]) && cachedEnergyBits[marker] == energy)
			return cachedTemperature[marker];
		ThermalMaterialDefinition material = material(marker, markers);
		long mass = cachedMassKilogramsBits[marker], baseBits = Double.doubleToRawLongBits(base);
		CaloricState common = commonState;
		double temperature;
		if (common != null && common.material() == cachedMaterial[marker] && common.mass() == mass
				&& common.base() == baseBits && common.energy() == energy && Objects.equals(common.mixture(),cachedMixture[marker])) temperature = common.temperature();
		else {
			temperature = ThermalCaloricInversion.owner(material, Double.longBitsToDouble(mass), energy(marker), base).temperatureK();
			commonState = new CaloricState(cachedMaterial[marker], mass, baseBits, energy, cachedMixture[marker], temperature);
		}
		cachedTemperature[marker] = temperature; cachedEnergyBits[marker] = energy;
		return temperature;
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
			material = stateFunction.materialForComposition(handle,mass,mixture,chemistry); base = baseEnergy(handle, mass, mixture, chemistry);
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
		return ThermalCaloricInversion.owner(stateFunction.materialForComposition(handle,physicalMass,mixture,chemistry), physicalMass, energy,
				baseEnergy(handle, physicalMass, mixture, chemistry)).temperatureK();
	}

	/** Roundoff in a prospective caloric evaluation, including temperature and mass storage. */
	public double modeledEnergyRoundoff(int fluidMaterialHandle, double mass, ChemicalMixtureRecord mixture,
			double temperature, ChemicalRegistry chemistry) {
		int handle = thermalMaterialHandleForFluid(fluidMaterialHandle);
		double physicalMass = unitScale.kilograms(mass);
		var material = stateFunction.materialForComposition(handle,physicalMass,mixture,chemistry);
		double base = baseEnergy(handle, physicalMass, mixture, chemistry);
		return stateFunction.baseEnergyRoundoff(handle, physicalMass, mixture, chemistry)
				+ ThermalCaloricInversion.modeledRoundoff(material, physicalMass, base, temperature);
	}

	public void requireReactionState(
			int marker, ChemicalMixtureRecord candidateMixture, double candidateMass, ThermalCarrierContext markers
	) {
		int thermalHandle = materialByFluidHandle[markers.fluidMaterialHandle(marker)];
		double physicalMass = unitScale.kilograms(candidateMass);
		ThermalMaterialDefinition material = stateFunction.materialForComposition(thermalHandle,physicalMass,candidateMixture,markers.chemicalRegistry());
		double base = baseEnergy(
				thermalHandle, physicalMass, candidateMixture, markers.chemicalRegistry());
		ThermalCaloricInversion.owner(material, physicalMass, energy(marker), base);
	}

	void addExternalMarker(int marker, ThermalCarrierContext markers) {
		ensureCapacity(marker + 1);
		setEnergy(marker, modeledEnergy(marker, markers.mass(marker), markers.mixture(marker), insertionTemperatureK, markers));
		audit.update(ledger -> ledger.withExternalSourceEnergy(SignedDyadic.of(energy(marker))));
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

	void commitExternalLiquidInsertions(int firstOrdinal, ExternalLiquidInsertionPlan plan) {
		if (plan == null || plan.owner != this || firstOrdinal < 0
				|| firstOrdinal > owners.markerCount() - plan.energies.length) {
			throw new IllegalArgumentException("Thermal liquid insertion ordinals are incompatible");
		}
		if (audit.ledger() != plan.before)
			throw new IllegalStateException("Explicit thermal insertion audit changed after planning");
		for (int index = 0; index < plan.energies.length; index++) {
			if (Double.doubleToRawLongBits(energy(firstOrdinal + index)) != Double.doubleToRawLongBits(0.0))
				throw new IllegalStateException("Explicit thermal insertion did not reserve an empty energy ordinal");
		}
		for (int index = 0; index < plan.energies.length; index++) setEnergy(firstOrdinal + index, plan.energies[index]);
		audit.replace(plan.after);
	}

	ThermalAuditLedger requireExternalInsertionCurrent(ExternalLiquidInsertionPlan plan) {
		if (plan == null || plan.owner != this || plan.before != audit.ledger())
			throw new IllegalStateException("Explicit thermal insertion audit changed after planning");
		return plan.after;
	}

	void removeExternalAndSwap(int index, int last) {
		audit.update(ledger -> ledger.withExternalSinkEnergy(SignedDyadic.of(energy(index))));
		if (index != last) setEnergy(index, energy(last));
	}

	void addInternalMarker(int marker, double energy) {
		ensureCapacity(marker + 1);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Internal phase marker energy is not finite");
		setEnergy(marker, energy);
	}

	double removeInternalAndSwap(int index, int last) {
		double removed = energy(index);
		if (index != last) setEnergy(index, energy(last));
		return removed;
	}

	void addLiquidEnergy(int marker, double energy) {
		double result = energy(marker) + energy;
		if (!Double.isFinite(result)) throw new IllegalArgumentException("Internal phase energy transfer overflow");
		setEnergy(marker, result);
	}

	public Truncation planTruncate(
			int marker, double newMass, ChemicalMixtureRecord keptMixture,
			double previousTemperatureK, ThermalCarrierContext markers
	) {
		double previousEnergy = energy(marker);
		double newEnergy = newMass == 0.0 ? 0.0
				: modeledEnergy(marker, newMass, keptMixture, previousTemperatureK, markers);
		return new Truncation(newEnergy, previousEnergy - newEnergy);
	}

	void commitTruncate(int marker, Truncation plan) {
		if (plan == null) return;
		SignedDyadic carrierEnergy = SignedDyadic.of(energy(marker))
				.subtract(SignedDyadic.of(plan.newEnergy()));
		setEnergy(marker, plan.newEnergy());
		audit.update(ledger -> ledger.withExternalSinkEnergy(carrierEnergy));
	}


	void resetMarkerTemperature(int marker, double temperatureK, ThermalCarrierContext markers) {
		setEnergy(marker, modeledEnergy(marker, markers.mass(marker), markers.mixture(marker), temperatureK, markers));
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
		double physicalMass = unitScale.kilograms(mass);
		ThermalMaterialDefinition material = stateFunction.materialForComposition(thermalHandle,physicalMass,mixture,chemicalRegistry);
		double base = baseEnergy(thermalHandle, physicalMass, mixture, chemicalRegistry);
		double energy = ThermalCaloricInversion.energyAt(material, physicalMass, base, temperatureK);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Thermal marker modeled energy is invalid");
		return energy;
	}

	double baseEnergy(int marker, ThermalCarrierContext markers) {
		ensureCapacity(markers.markerCount());
		int material = materialByFluidHandle[markers.fluidMaterialHandle(marker)];
		double mass = unitScale.kilograms(markers.mass(marker));
		ChemicalMixtureRecord mixture = markers.mixture(marker);
		ChemicalRegistry chemistry = markers.chemicalRegistry();
		if (cachedChemistry != chemistry) {
			Arrays.fill(cachedBaseEnergy, Double.NaN); cachedChemistry = chemistry;
			Arrays.fill(cachedTemperature, Double.NaN);
		}
		long massKilogramsBits = Double.doubleToRawLongBits(mass);
		if (!Double.isNaN(cachedBaseEnergy[marker]) && cachedMassKilogramsBits[marker] == massKilogramsBits
				&& cachedMaterial[marker] == material && Objects.equals(cachedMixture[marker], mixture))
			return cachedBaseEnergy[marker];
		double value = baseEnergy(material, mass, mixture, chemistry);
		cachedTemperature[marker] = Double.NaN;
		cachedBaseEnergy[marker] = value; cachedMassKilogramsBits[marker] = massKilogramsBits;
		cachedMaterial[marker] = material; cachedMixture[marker] = mixture;
		return value;
	}

	private double baseEnergy(int material, double mass, ChemicalMixtureRecord mixture, ChemicalRegistry chemistry) {
		return stateFunction.baseEnergy(material, mass, mixture, chemistry);
	}

	private ThermalMaterialDefinition material(int marker, ThermalCarrierContext markers) {
		return stateFunction.materialForComposition(materialByFluidHandle[markers.fluidMaterialHandle(marker)],
				unitScale.kilograms(markers.mass(marker)),markers.mixture(marker),markers.chemicalRegistry());
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
