package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;

import java.util.List;
import java.util.Map;

/**
 * The thermal view of every carrier inventory in one region: liquid markers, granular active
 * parcels and deposit groups, plus the solid composition pool backing composition handles.
 * Thermal state owners read authoritative truth through this interface and never reach into
 * the fluid facade.
 */
public interface ThermalCarrierContext {
	int markerCount();
	int cell(int marker);
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
