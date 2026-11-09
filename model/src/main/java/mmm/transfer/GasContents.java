package mmm.transfer;

import java.io.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.air.GasComponentAmount;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.thermal.GasThermalModel;

/** Finite gas mixture with exact component columns and its total internal energy. */
public record GasContents(String gasRegistryFingerprint, String chemicalRegistryFingerprint,
                          GasInventory inventory, double internalEnergy) {
	private static final int MAGIC = 0x4d474153;
	public GasContents {
		if (gasRegistryFingerprint == null || !gasRegistryFingerprint.matches("[0-9a-f]{64}")
				|| chemicalRegistryFingerprint == null || !(chemicalRegistryFingerprint.isEmpty() || chemicalRegistryFingerprint.matches("[0-9a-f]{64}"))
				|| inventory == null || inventory.isEmpty() || !Double.isFinite(internalEnergy))
			throw new IllegalArgumentException("Invalid gas container contents");
	}
	public Split split(long nanos, GasRegistry registry, ChemicalRegistry chemistry, GasThermalModel thermal) {
		if (!gasRegistryFingerprint.equals(registry.fingerprint()) || !chemicalRegistryFingerprint.equals(chemistry == null ? "" : chemistry.fingerprint()))
			throw new IllegalArgumentException("Gas container registry differs from the region");
		if (nanos <= 0 || nanos > inventory.standardVolumeNanos()) throw new IllegalArgumentException("Invalid gas transfer amount");
		if (nanos == inventory.standardVolumeNanos()) return new Split(this, null);
		var portions = inventory.splitTotal(nanos, registry, chemistry);
		double[] energies = thermal.partitionEnergy(inventory, internalEnergy, List.of(portions.first(), portions.second()));
		return new Split(new GasContents(gasRegistryFingerprint, chemicalRegistryFingerprint, portions.first(), energies[0]),
				new GasContents(gasRegistryFingerprint, chemicalRegistryFingerprint, portions.second(), energies[1]));
	}
	public record Split(GasContents selected, GasContents remaining) { }
	public String encode() {
		try {
			var bytes = new ByteArrayOutputStream();
			try (var out = new DataOutputStream(bytes)) {
				out.writeInt(MAGIC); out.writeUTF(gasRegistryFingerprint); out.writeUTF(chemicalRegistryFingerprint);
				out.writeDouble(internalEnergy); out.writeInt(inventory.components().size());
				for (var component : inventory.components()) {
					out.writeUTF(component.componentId()); out.writeLong(component.standardVolumeNanos());
					out.writeLong(component.massUnits()); out.writeLong(component.massResidualUnits()); out.writeInt(component.species().size());
					for (var species : component.species()) { out.writeUTF(species.speciesId()); out.writeLong(species.amountQuanta()); }
				}
			}
			return Base64.getEncoder().encodeToString(bytes.toByteArray());
		} catch (IOException impossible) { throw new IllegalStateException(impossible); }
	}
	public static GasContents decode(String encoded) {
		try {
			if (encoded == null || encoded.length() > 65536) throw new IllegalArgumentException("Invalid gas contents size");
			try (var in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
				if (in.readInt() != MAGIC) throw new IllegalArgumentException("Invalid gas contents encoding");
				String gas = in.readUTF(), chemistry = in.readUTF(); double energy = in.readDouble();
				int count = in.readInt();
				if (count <= 0 || count > mmm.fluid.air.GasLimits.MAX_COMPONENTS_PER_NODE) throw new IllegalArgumentException("Invalid gas component count");
				var components = new ArrayList<GasComponentAmount>();
				for (int i = 0; i < count; i++) {
					String id = in.readUTF(); long volume = in.readLong(), mass = in.readLong(), residual = in.readLong();
					int speciesCount = in.readInt();
					if (speciesCount < 0 || speciesCount > mmm.fluid.air.GasLimits.MAX_SPECIES_PER_COMPONENT) throw new IllegalArgumentException("Invalid gas species count");
					var species = new ArrayList<GasComponentAmount.SpeciesAmount>();
					for (int j = 0; j < speciesCount; j++) species.add(new GasComponentAmount.SpeciesAmount(in.readUTF(), in.readLong()));
					components.add(new GasComponentAmount(id, volume, mass, species, residual));
				}
				if (in.read() != -1) throw new IllegalArgumentException("Trailing gas contents");
				var result = new GasContents(gas, chemistry, new GasInventory(components), energy);
				if (!result.encode().equals(encoded)) throw new IllegalArgumentException("Noncanonical gas contents");
				return result;
			}
		} catch (IOException invalid) { throw new IllegalArgumentException("Invalid gas contents", invalid); }
	}
}
