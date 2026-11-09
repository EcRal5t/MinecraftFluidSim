package mmm.thermal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/** Complete finite device inventory carried by an item or an external transaction snapshot. */
public final class FiniteThermalDeviceItemCodec {
	public static final int MAX_ENCODED_LENGTH = 4096;
	private static final int MAGIC = 0x4d4d4644;
	private FiniteThermalDeviceItemCodec() { }

	public static String encode(FiniteThermalDeviceState state) {
		Objects.requireNonNull(state, "Finite device item state is missing");
		try {
			var bytes = new ByteArrayOutputStream();
			try (var output = new DataOutputStream(bytes)) {
				output.writeInt(MAGIC);
				output.writeUTF(state.providerId()); output.writeUTF(state.objectId()); output.writeUTF(state.deviceTypeId());
				var body = state.body();
				output.writeLong(body.generation()); output.writeUTF(body.thermalRegistryFingerprint());
				output.writeUTF(body.thermalMaterialId()); output.writeLong(body.massUnits());
				output.writeLong(Double.doubleToRawLongBits(body.internalEnergyJ()));
				writeSurfaces(output, state.surfaces());
			}
			String result = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
			if (result.length() > MAX_ENCODED_LENGTH) throw new IllegalArgumentException("Finite device item exceeds encoded budget");
			return result;
		} catch (IOException impossible) { throw new IllegalStateException("Cannot encode in-memory finite device item", impossible); }
	}

	public static FiniteThermalDeviceState decode(String encoded) {
		if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_ENCODED_LENGTH)
			throw new IllegalArgumentException("Finite device item is missing or exceeds encoded budget");
		try (var input = new DataInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(encoded)))) {
			if (input.readInt() != MAGIC) throw new IllegalArgumentException("Unknown finite device item encoding");
			String provider = input.readUTF(), object = input.readUTF(), type = input.readUTF();
			var body = new FiniteThermalBodyState(input.readLong(), input.readUTF(), input.readUTF(), input.readLong(),
					Double.longBitsToDouble(input.readLong()));
			var state = new FiniteThermalDeviceState(provider, object, type, body, readSurfaces(input));
			if (input.read() != -1 || !encode(state).equals(encoded)) throw new IllegalArgumentException("Finite device item encoding is not canonical");
			return state;
		} catch (IOException invalid) { throw new IllegalArgumentException("Invalid finite device item encoding", invalid); }
	}

	public static void writeSurfaces(DataOutputStream output, java.util.List<FixedSurfaceState> surfaces) throws IOException {
		output.writeInt(surfaces.size());
		for (var surface : surfaces) {
			output.writeInt(surface.face().ordinal());
			output.writeUTF(surface.materialId());
			output.writeLong(surface.loadingMassUnits());
			output.writeInt(surface.speciesQuanta().size());
			for (var entry : surface.speciesQuanta().entrySet()) {
				output.writeUTF(entry.getKey());
				output.writeLong(entry.getValue());
			}
			output.writeInt(surface.residualQuanta().size());
			for (var entry : surface.residualQuanta().entrySet()) {
				output.writeUTF(entry.getKey());
				output.writeLong(Double.doubleToRawLongBits(entry.getValue()));
			}
		}
	}

	public static java.util.List<FixedSurfaceState> readSurfaces(DataInputStream input) throws IOException {
		int count = input.readInt();
		if (count < 0 || count > FiniteThermalDeviceDefinition.LocalFace.values().length)
			throw new IOException("Invalid surface face count");
		var result = new java.util.ArrayList<FixedSurfaceState>(count);
		for (int i = 0; i < count; i++) {
			int face = input.readInt();
			if (face < 0 || face >= FiniteThermalDeviceDefinition.LocalFace.values().length)
				throw new IOException("Invalid surface face");
			String material = input.readUTF();
			long loading = input.readLong();
			int speciesCount = input.readInt();
			if (speciesCount < 0 || speciesCount > mmm.chemistry.ChemicalLimits.MAX_SPECIES) throw new IOException("Invalid adsorbed species count");
			var species = new java.util.TreeMap<String, Long>();
			for (int s = 0; s < speciesCount; s++)
				if (species.put(input.readUTF(), input.readLong()) != null)
					throw new IOException("Duplicate adsorbed species");
			int residualCount = input.readInt();
			if (residualCount < 0 || residualCount > mmm.chemistry.ChemicalLimits.MAX_SPECIES) throw new IOException("Invalid surface residual count");
			var residuals = new java.util.TreeMap<String, Double>();
			for (int s = 0; s < residualCount; s++)
				if (residuals.put(input.readUTF(), Double.longBitsToDouble(input.readLong())) != null)
					throw new IOException("Duplicate surface residual");
			result.add(new FixedSurfaceState(FiniteThermalDeviceDefinition.LocalFace.values()[face], material, loading, species, residuals));
		}
		return List.copyOf(result);
	}
}
