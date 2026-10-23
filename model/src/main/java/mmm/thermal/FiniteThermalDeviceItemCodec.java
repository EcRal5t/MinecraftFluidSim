package mmm.thermal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Base64;
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
			var state = new FiniteThermalDeviceState(provider, object, type, body);
			if (input.read() != -1 || !encode(state).equals(encoded)) throw new IllegalArgumentException("Finite device item encoding is not canonical");
			return state;
		} catch (IOException invalid) { throw new IllegalArgumentException("Invalid finite device item encoding", invalid); }
	}
}
