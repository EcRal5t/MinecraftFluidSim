package mmm.thermal;

import mmm.chemistry.ExactRational;

/** Stable species formation-energy offset within one host thermal material. */
public record ThermalSpeciesOffset(
		String id,
		String speciesId,
		String hostThermalMaterialId,
		ExactRational referenceInternalEnergyEuPerAu
) {
	public ThermalSpeciesOffset {
		ThermalIds.requireId(id, "thermal species offset id");
		ThermalIds.requireId(speciesId, "thermal species id");
		ThermalIds.requireId(hostThermalMaterialId, "thermal species host id");
		if (referenceInternalEnergyEuPerAu == null) {
			throw new IllegalArgumentException("Thermal species reference energy is missing");
		}
	}
}
