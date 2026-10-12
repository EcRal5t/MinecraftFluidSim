package mmm.api.query;

import mmm.api.StableId;

import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

/** Immutable SI observation of authoritative inventory in one solver cell. */
public record FluidCellObservation(
		String dimensionId,
		UUID regionId,
		long regionNetworkGeneration,
		int cellX,
		int cellY,
		int cellZ,
		double cellSizeMetres,
		double liquidVolumeCubicMetres,
		double liquidMassKilograms,
		double densityKilogramsPerCubicMetre,
		OptionalDouble temperatureKelvin,
		List<MaterialAmount> materials,
		List<SpeciesAmount> species
) {
	public FluidCellObservation {
		StableId.require(dimensionId, 128, "dimension id");
		if (regionId == null || regionNetworkGeneration <= 0L || cellX < 0 || cellY < 0 || cellZ < 0
				|| !(Double.isFinite(cellSizeMetres) && cellSizeMetres > 0.0)
				|| !(Double.isFinite(liquidVolumeCubicMetres) && liquidVolumeCubicMetres > 0.0)
				|| !(Double.isFinite(liquidMassKilograms) && liquidMassKilograms > 0.0)
				|| !(Double.isFinite(densityKilogramsPerCubicMetre) && densityKilogramsPerCubicMetre > 0.0)
				|| temperatureKelvin == null || temperatureKelvin.isPresent()
				&& !(Double.isFinite(temperatureKelvin.getAsDouble()) && temperatureKelvin.getAsDouble() >= 0.0)
				|| materials == null || species == null) {
			throw new IllegalArgumentException("Fluid cell observation is invalid");
		}
		materials = List.copyOf(materials);
		species = List.copyOf(species);
	}

	public record MaterialAmount(String materialId, double volumeCubicMetres, double massKilograms) {
		public MaterialAmount {
			StableId.require(materialId, 128, "material id");
			if (!(Double.isFinite(volumeCubicMetres) && volumeCubicMetres > 0.0)
					|| !(Double.isFinite(massKilograms) && massKilograms > 0.0)) {
				throw new IllegalArgumentException("Material amount is invalid");
			}
		}
	}

	public record SpeciesAmount(String speciesId, long amountQuanta) {
		public SpeciesAmount {
			StableId.require(speciesId, 128, "species id");
			if (amountQuanta <= 0L) throw new IllegalArgumentException("Species amount must be positive");
		}
	}

}
