package mmm.api.transfer;

import mmm.api.StableId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalDouble;

/** Actual carried inventory. Mass and residual units are 10^-10 kg; species quanta are micromoles. */
public record TransferManifest(
		TransferQuantity quantity, List<Component> components,
		String chemicalRegistryFingerprint, String granularRegistryFingerprint,
		OptionalDouble internalEnergyJoules
) {
	public TransferManifest {
		if (quantity == null || components == null || components.isEmpty()
				|| internalEnergyJoules == null
				|| internalEnergyJoules.isPresent() && !Double.isFinite(internalEnergyJoules.getAsDouble())
				|| quantity instanceof TransferQuantity.Liquid liquid
						&& !liquid.internalEnergyJoules().equals(internalEnergyJoules))
			throw new IllegalArgumentException("Invalid transfer manifest");
		components = components.stream().sorted(Comparator.comparing(Component::materialId)).toList();
		var materials = new HashSet<String>();
		long amount = 0;
		for (var component : components) {
			if (!materials.add(component.materialId())) throw new IllegalArgumentException("Duplicate material component");
			amount = Math.addExact(amount, component.amount());
		}
		long expected = quantity instanceof TransferQuantity.Liquid liquid ? liquid.volumeNanos()
				: quantity instanceof TransferQuantity.Gas gas ? gas.standardVolumeNanos() : ((TransferQuantity.Granular) quantity).solidVolumeSvq();
		if (amount != expected) throw new IllegalArgumentException("Manifest component amounts disagree with quantity");
		requireFingerprint(chemicalRegistryFingerprint);
		requireFingerprint(granularRegistryFingerprint);
	}

	private static void requireFingerprint(String value) {
		if (value != null && !value.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Invalid registry fingerprint");
	}

	/** Amount uses the manifest quantity's nanos or SVQ unit; residual preserves exact composition mass. */
	public record Component(
			String materialId, long amount, long massUnits, long massResidualUnits, List<Species> species
	) {
		public Component {
			StableId.require(materialId, 128, "material id");
			if (amount <= 0 || massUnits <= 0 || species == null)
				throw new IllegalArgumentException("Invalid material component");
			species = species.stream().sorted(Comparator.comparing(Species::speciesId)).toList();
			var identities = new HashSet<String>();
			for (var entry : species) if (!identities.add(entry.speciesId()))
				throw new IllegalArgumentException("Duplicate species component");
		}
	}

	public record Species(String speciesId, long amountQuanta) {
		public Species {
			StableId.require(speciesId, 128, "species id");
			if (amountQuanta <= 0) throw new IllegalArgumentException("Invalid species quantity");
		}
	}
}
