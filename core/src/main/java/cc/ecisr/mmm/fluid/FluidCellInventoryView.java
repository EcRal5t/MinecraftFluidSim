package cc.ecisr.mmm.fluid;

import java.util.List;
import java.util.OptionalDouble;

/** Loader-neutral, immutable cell observation built directly from authoritative marker columns. */
public record FluidCellInventoryView(
		double liquidVolumeCubicMetres,
		double liquidMassKilograms,
		double densityKilogramsPerCubicMetre,
		OptionalDouble temperatureKelvin,
		List<MaterialAmount> materials,
		List<SpeciesAmount> species
) {
	public FluidCellInventoryView {
		if (!(Double.isFinite(liquidVolumeCubicMetres) && liquidVolumeCubicMetres > 0.0)
				|| !(Double.isFinite(liquidMassKilograms) && liquidMassKilograms > 0.0)
				|| !(Double.isFinite(densityKilogramsPerCubicMetre) && densityKilogramsPerCubicMetre > 0.0)
				|| temperatureKelvin == null || temperatureKelvin.isPresent()
				&& !(Double.isFinite(temperatureKelvin.getAsDouble()) && temperatureKelvin.getAsDouble() >= 0.0)) {
			throw new IllegalArgumentException("Cell inventory view is invalid");
		}
		materials = List.copyOf(materials);
		species = List.copyOf(species);
	}

	public record MaterialAmount(String materialId, double volumeCubicMetres, double massKilograms) { }
	public record SpeciesAmount(String speciesId, long amountQuanta) { }
}
