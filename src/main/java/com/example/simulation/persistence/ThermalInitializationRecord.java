package com.example.simulation.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Canonical evidence for the one explicit old-region to v6 temperature initialization. */
public record ThermalInitializationRecord(
		int sourceFormatVersion,
		long initialTemperatureBits,
		GasBootstrap gasBootstrap,
		String operatorMetadata
) {
	public static final String MANIFEST_EXTENSION = "realwater:thermal_initialization_v1";
	private static final int MAGIC = 0x52575449; // RWTI
	private static final int PAYLOAD_VERSION = 1;
	private static final int MAX_OPERATOR_METADATA_BYTES = 256;

	public ThermalInitializationRecord {
		if (sourceFormatVersion != 1 && sourceFormatVersion != 2
				&& sourceFormatVersion != 3 && sourceFormatVersion != 5) {
			throw new IllegalArgumentException("Thermal initialization source must be region-state v1/v2/v3/v5");
		}
		double temperature = Double.longBitsToDouble(initialTemperatureBits);
		if (!(Double.isFinite(temperature) && temperature > 0.0)) {
			throw new IllegalArgumentException("Thermal initialization temperature must be finite and positive");
		}
		Objects.requireNonNull(gasBootstrap, "gasBootstrap");
		Objects.requireNonNull(operatorMetadata, "operatorMetadata");
		int metadataBytes = operatorMetadata.getBytes(StandardCharsets.UTF_8).length;
		if (operatorMetadata.isBlank() || metadataBytes > MAX_OPERATOR_METADATA_BYTES) {
			throw new IllegalArgumentException("Thermal initialization operator metadata is invalid");
		}
		for (int index = 0; index < operatorMetadata.length(); index++) {
			if (Character.isISOControl(operatorMetadata.charAt(index))) {
				throw new IllegalArgumentException("Thermal initialization operator metadata contains control characters");
			}
		}
	}

	public ThermalInitializationRecord(
			int sourceFormatVersion, double initialTemperatureK,
			GasBootstrap gasBootstrap, String operatorMetadata
	) {
		this(sourceFormatVersion, Double.doubleToRawLongBits(initialTemperatureK), gasBootstrap, operatorMetadata);
	}

	public double initialTemperatureK() {
		return Double.longBitsToDouble(initialTemperatureBits);
	}

	byte[] encode() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeInt(MAGIC);
			output.writeInt(PAYLOAD_VERSION);
			output.writeInt(sourceFormatVersion);
			output.writeLong(initialTemperatureBits);
			output.writeUTF(gasBootstrap.id());
			output.writeUTF(operatorMetadata);
		}
		return bytes.toByteArray();
	}

	static ThermalInitializationRecord decode(byte[] payload) throws IOException {
		if (payload == null || payload.length == 0 || payload.length > 1_024) {
			throw new IOException("Thermal initialization payload size is invalid");
		}
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
			if (input.readInt() != MAGIC || input.readInt() != PAYLOAD_VERSION) {
				throw new IOException("Unknown thermal initialization payload");
			}
			int sourceVersion = input.readInt();
			long temperatureBits = input.readLong();
			GasBootstrap bootstrap = GasBootstrap.fromId(input.readUTF());
			String operator = input.readUTF();
			if (input.read() != -1) throw new IOException("Trailing thermal initialization payload bytes");
			return new ThermalInitializationRecord(sourceVersion, temperatureBits, bootstrap, operator);
		} catch (EOFException truncated) {
			throw new IOException("Truncated thermal initialization payload", truncated);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid thermal initialization payload: " + invalid.getMessage(), invalid);
		}
	}

	public enum GasBootstrap {
		CURRENT_GEOMETRY_AMBIENT_V1("current_geometry_ambient_v1");

		private final String id;

		GasBootstrap(String id) { this.id = id; }
		public String id() { return id; }

		static GasBootstrap fromId(String id) throws IOException {
			for (GasBootstrap value : values()) if (value.id.equals(id)) return value;
			throw new IOException("Unknown thermal initialization gas bootstrap " + id);
		}
	}
}
