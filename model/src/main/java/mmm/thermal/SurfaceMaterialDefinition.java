package mmm.thermal;

import java.util.HashSet;
import java.util.List;

/** Fixed surface properties loaded with the world's material definitions. */
public record SurfaceMaterialDefinition(
		String id, double referenceTemperatureK, double referenceSpecificEnergyJPerKg,
		double specificHeatJPerKgK, double effectiveAreaFactor, double siteCapacityMolesPerKg,
		double exchangeMolesPerSquareMeterSecond, double minimumTemperatureK,
		double maximumTemperatureK, double maximumPressurePa,
		List<Adsorbate> adsorbates, List<Catalysis> catalysis
) {
	public record Adsorbate(String componentId, String speciesId, double affinityPerPa,
			double referenceTemperatureK, double adsorptionHeatJPerMol,
			double referenceEnergyJPerMol, double heatCapacityJPerMolK) {
		public Adsorbate {
			ThermalIds.requireId(componentId, "adsorbate gas component id");
			ThermalIds.requireId(speciesId, "adsorbate species id");
			if (!(Double.isFinite(affinityPerPa) && affinityPerPa > 0
					&& Double.isFinite(referenceTemperatureK) && referenceTemperatureK > 0
					&& Double.isFinite(adsorptionHeatJPerMol) && adsorptionHeatJPerMol >= 0
					&& Double.isFinite(referenceEnergyJPerMol)
					&& Double.isFinite(heatCapacityJPerMolK) && heatCapacityJPerMolK > 0))
				throw new IllegalArgumentException("Invalid adsorbate properties");
		}
	}

	/** A catalyst changes both directions of the named reaction by the same area-scaled factor. */
	public record Catalysis(String reactionId, double multiplierPerSquareMeter) {
		public Catalysis {
			ThermalIds.requireId(reactionId, "catalysed reaction id");
			if (!(Double.isFinite(multiplierPerSquareMeter) && multiplierPerSquareMeter >= 0))
				throw new IllegalArgumentException("Invalid catalytic multiplier");
		}
	}

	public SurfaceMaterialDefinition {
		ThermalIds.requireId(id, "surface material id");
		if (!(Double.isFinite(referenceTemperatureK) && referenceTemperatureK > 0
				&& Double.isFinite(referenceSpecificEnergyJPerKg)
				&& Double.isFinite(specificHeatJPerKgK) && specificHeatJPerKgK > 0
				&& Double.isFinite(effectiveAreaFactor) && effectiveAreaFactor > 0
				&& Double.isFinite(siteCapacityMolesPerKg) && siteCapacityMolesPerKg >= 0
				&& Double.isFinite(exchangeMolesPerSquareMeterSecond) && exchangeMolesPerSquareMeterSecond >= 0
				&& Double.isFinite(minimumTemperatureK) && minimumTemperatureK > 0
				&& Double.isFinite(maximumTemperatureK) && maximumTemperatureK > minimumTemperatureK
				&& Double.isFinite(maximumPressurePa) && maximumPressurePa > 0))
			throw new IllegalArgumentException("Invalid fixed surface properties");
		adsorbates = adsorbates.stream().sorted(java.util.Comparator.comparing(Adsorbate::speciesId)).toList();
		catalysis = catalysis.stream().sorted(java.util.Comparator.comparing(Catalysis::reactionId)).toList();
		var species = new HashSet<String>();
		for (var value : adsorbates)
			if (!species.add(value.speciesId())) throw new IllegalArgumentException("Duplicate adsorbate species");
		var reactions = new HashSet<String>();
		for (var value : catalysis)
			if (!reactions.add(value.reactionId())) throw new IllegalArgumentException("Duplicate catalyst reaction");
		if (!adsorbates.isEmpty() && (siteCapacityMolesPerKg == 0 || exchangeMolesPerSquareMeterSecond == 0))
			throw new IllegalArgumentException("Adsorption requires capacity and exchange rate");
	}
}
