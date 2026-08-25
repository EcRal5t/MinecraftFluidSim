package cc.ecisr.mmm.compat.jade;

import cc.ecisr.mmm.api.StableId;
import cc.ecisr.mmm.api.query.FluidCellObservation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Bounded display-only projection; it is not an inventory or transfer payload. */
record JadeFluidSummary(
		double volumeCubicMetres,
		double massKilograms,
		double densityKilogramsPerCubicMetre,
		Double temperatureKelvin,
		int materialCount,
		List<Material> materials,
		int speciesCount,
		List<Species> species
) {
	private static final int DISPLAY_LIMIT = 4;

	JadeFluidSummary {
		if (!(Double.isFinite(volumeCubicMetres) && volumeCubicMetres > 0.0)
				|| !(Double.isFinite(massKilograms) && massKilograms > 0.0)
				|| !(Double.isFinite(densityKilogramsPerCubicMetre) && densityKilogramsPerCubicMetre > 0.0)
				|| temperatureKelvin != null && !(Double.isFinite(temperatureKelvin) && temperatureKelvin > 0.0)
				|| materialCount < materials.size() || speciesCount < species.size()
				|| materials.size() > DISPLAY_LIMIT || species.size() > DISPLAY_LIMIT) {
			throw new IllegalArgumentException("Invalid Jade fluid summary");
		}
		materials = List.copyOf(materials);
		species = List.copyOf(species);
	}

	static JadeFluidSummary from(FluidCellObservation observation) {
		List<Material> materials = observation.materials().stream()
				.sorted(Comparator.comparingDouble(FluidCellObservation.MaterialAmount::volumeCubicMetres).reversed())
				.limit(DISPLAY_LIMIT)
				.map(value -> new Material(value.materialId(), value.volumeCubicMetres()))
				.toList();
		List<Species> species = observation.species().stream()
				.sorted(Comparator.comparingLong(FluidCellObservation.SpeciesAmount::amountQuanta).reversed())
				.limit(DISPLAY_LIMIT)
				.map(value -> new Species(value.speciesId(), value.amountQuanta()))
				.toList();
		return new JadeFluidSummary(observation.liquidVolumeCubicMetres(), observation.liquidMassKilograms(),
				observation.densityKilogramsPerCubicMetre(),
				observation.temperatureKelvin().isPresent() ? observation.temperatureKelvin().getAsDouble() : null,
				observation.materials().size(), materials, observation.species().size(), species);
	}

	CompoundTag encode() {
		CompoundTag tag = new CompoundTag();
		tag.putLong("VolumeBits", Double.doubleToRawLongBits(volumeCubicMetres));
		tag.putLong("MassBits", Double.doubleToRawLongBits(massKilograms));
		tag.putLong("DensityBits", Double.doubleToRawLongBits(densityKilogramsPerCubicMetre));
		if (temperatureKelvin != null) tag.putLong("TemperatureBits", Double.doubleToRawLongBits(temperatureKelvin));
		tag.putInt("MaterialCount", materialCount);
		ListTag encodedMaterials = new ListTag();
		for (Material material : materials) {
			CompoundTag value = new CompoundTag();
			value.putString("Id", material.id());
			value.putLong("VolumeBits", Double.doubleToRawLongBits(material.volumeCubicMetres()));
			encodedMaterials.add(value);
		}
		tag.put("Materials", encodedMaterials);
		tag.putInt("SpeciesCount", speciesCount);
		ListTag encodedSpecies = new ListTag();
		for (Species entry : species) {
			CompoundTag value = new CompoundTag();
			value.putString("Id", entry.id());
			value.putLong("Quanta", entry.quanta());
			encodedSpecies.add(value);
		}
		tag.put("Species", encodedSpecies);
		return tag;
	}

	static Optional<JadeFluidSummary> decode(CompoundTag root) {
		try {
			double volume = value(root, "VolumeBits");
			double mass = value(root, "MassBits");
			double density = value(root, "DensityBits");
			Double temperature = root.contains("TemperatureBits") ? value(root, "TemperatureBits") : null;
			List<Material> materials = new ArrayList<>();
			ListTag encodedMaterials = root.getListOrEmpty("Materials");
			if (encodedMaterials.size() > DISPLAY_LIMIT) return Optional.empty();
			for (int index = 0; index < encodedMaterials.size(); index++) {
				CompoundTag tag = encodedMaterials.getCompoundOrEmpty(index);
				materials.add(new Material(tag.getStringOr("Id", ""), value(tag, "VolumeBits")));
			}
			List<Species> species = new ArrayList<>();
			ListTag encodedSpecies = root.getListOrEmpty("Species");
			if (encodedSpecies.size() > DISPLAY_LIMIT) return Optional.empty();
			for (int index = 0; index < encodedSpecies.size(); index++) {
				CompoundTag tag = encodedSpecies.getCompoundOrEmpty(index);
				species.add(new Species(tag.getStringOr("Id", ""), tag.getLongOr("Quanta", -1L)));
			}
			return Optional.of(new JadeFluidSummary(volume, mass, density, temperature,
					root.getIntOr("MaterialCount", -1), materials,
					root.getIntOr("SpeciesCount", -1), species));
		} catch (IllegalArgumentException invalid) {
			return Optional.empty();
		}
	}

	private static double value(CompoundTag tag, String field) {
		return Double.longBitsToDouble(tag.getLongOr(field, 0L));
	}

	record Material(String id, double volumeCubicMetres) {
		Material {
			StableId.require(id, 128, "Jade material id");
			if (!(Double.isFinite(volumeCubicMetres) && volumeCubicMetres > 0.0)) {
				throw new IllegalArgumentException("Invalid Jade material volume");
			}
		}
	}

	record Species(String id, long quanta) {
		Species {
			StableId.require(id, 128, "Jade species id");
			if (quanta <= 0L) throw new IllegalArgumentException("Invalid Jade species amount");
		}
	}
}
