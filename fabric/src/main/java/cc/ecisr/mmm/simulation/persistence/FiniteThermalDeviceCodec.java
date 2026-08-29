package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.thermal.FiniteThermalBodyState;
import cc.ecisr.mmm.thermal.FiniteThermalDeviceInventory;
import cc.ecisr.mmm.thermal.FiniteThermalDeviceState;
import cc.ecisr.mmm.thermal.FiniteThermalDeviceAudit;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalRegistry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;

/** Strict manifest-only codec for region-owned finite thermal device truth. */
final class FiniteThermalDeviceCodec {
	static final String MANIFEST_EXTENSION = "mmm:finite_thermal_devices";
	private static final int MAGIC = 0x52574644; // RWFD
	private static final int FORMAT = 2;
	private static final int MAX_DYADIC_BYTES = SignedDyadic.MAX_SIGNIFICAND_BITS / 8 + 2;

	byte[] encode(FiniteThermalDeviceInventory inventory) throws IOException {
		if (inventory == null || inventory.devices().isEmpty()) {
			throw new IllegalArgumentException("Empty finite thermal device inventory is represented by extension absence");
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			output.writeInt(MAGIC);
			output.writeInt(FORMAT);
			output.writeUTF(inventory.thermalRegistryFingerprint());
			output.writeInt(inventory.devices().size());
			writeAudit(output, inventory.audit());
			for (FiniteThermalDeviceState device : inventory.devices()) {
				FiniteThermalBodyState body = device.body();
				output.writeUTF(device.providerId());
				output.writeUTF(device.objectId());
				output.writeUTF(device.deviceTypeId());
				output.writeLong(body.generation());
				output.writeUTF(body.thermalMaterialId());
				output.writeLong(Double.doubleToRawLongBits(body.massKilograms()));
				output.writeLong(Double.doubleToRawLongBits(body.internalEnergyEu()));
			}
		}
		byte[] payload = bytes.toByteArray();
		if (payload.length > ThermalLimits.MAX_SERIALIZED_EXTENSION_BYTES) {
			throw new IOException("Finite thermal device extension is over budget");
		}
		return payload;
	}

	FiniteThermalDeviceInventory decode(byte[] payload, ThermalRegistry registry) throws IOException {
		if (payload == null || payload.length == 0
				|| payload.length > ThermalLimits.MAX_SERIALIZED_EXTENSION_BYTES) {
			throw new IOException("Finite thermal device extension is missing or over budget");
		}
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
			if (input.readInt() != MAGIC || input.readInt() != FORMAT) {
				throw new IOException("Unknown finite thermal device payload");
			}
			String fingerprint = input.readUTF();
			int count = input.readInt();
			if (count <= 0 || count > ThermalLimits.MAX_FINITE_DEVICES_PER_REGION) {
				throw new IOException("Finite thermal device count is invalid");
			}
			FiniteThermalDeviceAudit audit = readAudit(input);
			ArrayList<FiniteThermalDeviceState> devices = new ArrayList<>(count);
			for (int index = 0; index < count; index++) {
				String providerId = input.readUTF();
				String objectId = input.readUTF();
				String deviceTypeId = input.readUTF();
				long generation = input.readLong();
				String materialId = input.readUTF();
				double mass = Double.longBitsToDouble(input.readLong());
				double energy = Double.longBitsToDouble(input.readLong());
				devices.add(new FiniteThermalDeviceState(providerId, objectId, deviceTypeId,
						new FiniteThermalBodyState(generation, fingerprint, materialId, mass, energy)));
			}
			if (input.read() != -1) throw new IOException("Trailing finite thermal device bytes");
			FiniteThermalDeviceInventory inventory = new FiniteThermalDeviceInventory(fingerprint, devices, audit);
			if (registry != null) inventory.requireCompatible(registry);
			return inventory;
		} catch (EOFException truncated) {
			throw new IOException("Truncated finite thermal device payload", truncated);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid finite thermal device payload: " + invalid.getMessage(), invalid);
		}
	}

	private static void writeAudit(DataOutputStream output, FiniteThermalDeviceAudit audit) throws IOException {
		writeDyadic(output, audit.initialInventory());
		writeDyadic(output, audit.externalSource());
		writeDyadic(output, audit.externalSink());
		writeDyadic(output, audit.solverExchange());
		writeDyadic(output, audit.roundoffResidual());
	}

	private static FiniteThermalDeviceAudit readAudit(DataInputStream input) throws IOException {
		return new FiniteThermalDeviceAudit(readDyadic(input), readDyadic(input), readDyadic(input),
				readDyadic(input), readDyadic(input));
	}

	private static void writeDyadic(DataOutputStream output, SignedDyadic value) throws IOException {
		byte[] significand = value.significand().toByteArray();
		output.writeInt(value.exponent());
		output.writeInt(significand.length);
		output.write(significand);
	}

	private static SignedDyadic readDyadic(DataInputStream input) throws IOException {
		int exponent = input.readInt();
		int length = input.readInt();
		if (length <= 0 || length > MAX_DYADIC_BYTES) {
			throw new IOException("Finite thermal device dyadic length is invalid");
		}
		byte[] bytes = input.readNBytes(length);
		if (bytes.length != length) throw new EOFException("Truncated finite thermal device dyadic");
		SignedDyadic result = new SignedDyadic(new BigInteger(bytes), exponent);
		if (result.exponent() != exponent
				|| !java.util.Arrays.equals(result.significand().toByteArray(), bytes)) {
			throw new IOException("Finite thermal device dyadic encoding is non-canonical");
		}
		return result;
	}
}
