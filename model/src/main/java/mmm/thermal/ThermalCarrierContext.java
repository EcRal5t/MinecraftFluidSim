package mmm.thermal;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalRegistry;

import java.util.List;
import java.util.Map;

/**
 * The thermal view of every carrier inventory in one region: liquid markers, granular active
 * parcels and deposit groups, plus the solid composition pool backing composition handles.
 * Thermal state owners read authoritative truth through this interface and never reach into
 * the fluid facade.
 */
public interface ThermalCarrierContext {
	record GasSupport(int cell, int node, int supportMask, double normalizedVolume) { }
	default mmm.fluid.air.AirNetwork gasNetwork() { return null; }
	default List<GasSupport> gasThermalSupports() { return List.of(); }
	int markerCount();
	/** Internal energy belongs to the authoritative cell/material/support inventory row. */
	double liquidInternalEnergy(int ordinal);
	void setLiquidInternalEnergy(int ordinal, double energy);
	long liquidInventoryRevision();
	default void setLiquidInternalEnergies(double[] energies) {
		if (energies == null || energies.length != markerCount())
			throw new IllegalArgumentException("Liquid energy count differs from inventory");
		for (int ordinal = 0; ordinal < energies.length; ordinal++) setLiquidInternalEnergy(ordinal, energies[ordinal]);
	}
	int cell(int marker);
	@FunctionalInterface
	interface SupportConsumer { void accept(int cell, double weight); }
	/** Prepares current carrier geometry once per observation batch. */
	default void prepareObservationSupport() { }
	/** Positive partition of unity for spatial observations; inventory membership remains {@link #cell}. */
	default void observationSupport(int marker, SupportConsumer consumer) { consumer.accept(cell(marker), 1.0); }
	/** Ascending marker ordinals in one cell; callers start with after = -1. */
	default int nextMarkerInCell(int cell, int after) {
		for (int marker = after + 1; marker < markerCount(); marker++) if (cell(marker) == cell) return marker;
		return -1;
	}
	int fluidMaterialHandle(int marker);
	double mass(int marker);
	double volume(int marker);
	ChemicalMixtureRecord mixture(int marker);
	ChemicalRegistry chemicalRegistry();
	default int granularMaterialCount() { return 0; }
	default String granularRegistryFingerprint() { return ""; }
	default String granularMaterialId(int materialHandle) { throw new IllegalArgumentException("No granular state"); }
	default int activeParcelCount() { return 0; }
	default int activeParcelCell(int ordinal) { throw new IllegalArgumentException("No granular state"); }
	default int activeParcelMaterial(int ordinal) { throw new IllegalArgumentException("No granular state"); }
	default int activeParcelComposition(int ordinal) { throw new IllegalArgumentException("No granular state"); }
	default long activeParcelSvq(int ordinal) { throw new IllegalArgumentException("No granular state"); }
	default double activeParcelMass(int ordinal) { throw new IllegalArgumentException("No granular state"); }
	default List<GranularEnergyColumn.DepositKey> depositKeys() { return List.of(); }
	default List<GranularEnergyColumn.DepositKey> depositKeys(int cell) {
		return depositKeys().stream().filter(key -> key.cell() == cell).toList();
	}
	default long depositSvq(GranularEnergyColumn.DepositKey key) { throw new IllegalArgumentException("No granular state"); }
	default double depositMass(GranularEnergyColumn.DepositKey key) { throw new IllegalArgumentException("No granular state"); }
	default Map<String, Long> solidComposition(int compositionHandle) {
		if (compositionHandle == 0) return Map.of();
		throw new IllegalArgumentException("No solid composition state");
	}
}
