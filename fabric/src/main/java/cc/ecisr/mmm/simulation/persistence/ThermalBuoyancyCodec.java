package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.thermal.LiquidThermalExpansion;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/** Manifest-only identity binding for the thermal-region Boussinesq definition set. */
final class ThermalBuoyancyCodec {
	static final String MANIFEST_EXTENSION = "mmm:thermal_buoyancy_v1";
	private static final int MAGIC = 0x52574259; // RWBY
	private static final int PAYLOAD_VERSION = 1;

	byte[] encodeCurrent() throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeInt(MAGIC);
			output.writeInt(PAYLOAD_VERSION);
			output.writeUTF(LiquidThermalExpansion.MODEL_ID);
			output.writeUTF(LiquidThermalExpansion.fingerprint());
		}
		return bytes.toByteArray();
	}

	void requireCurrent(byte[] payload) throws IOException {
		if (payload == null || payload.length == 0 || payload.length > 256) {
			throw new IOException("Thermal buoyancy identity payload size is invalid");
		}
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
			if (input.readInt() != MAGIC || input.readInt() != PAYLOAD_VERSION
					|| !LiquidThermalExpansion.MODEL_ID.equals(input.readUTF())
					|| !LiquidThermalExpansion.fingerprint().equals(input.readUTF()) || input.read() != -1) {
				throw new IOException("Thermal buoyancy model identity does not match this runtime");
			}
		} catch (EOFException truncated) {
			throw new IOException("Truncated thermal buoyancy identity payload", truncated);
		}
	}
}
