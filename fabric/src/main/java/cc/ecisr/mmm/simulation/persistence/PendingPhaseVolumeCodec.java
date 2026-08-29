package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.fluid.PendingPhaseVolumeState;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.thermal.ThermalLimits;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;

/** Sparse manifest-only thermal record for phase volume awaiting exactly one mechanical projection. */
final class PendingPhaseVolumeCodec {
	static final String MANIFEST_EXTENSION = "mmm:pending_phase_volume";
	private static final int MAGIC = 0x52575056; // RWPV
	private static final int VERSION = 1;
	private static final int MAX_DYADIC_BYTES = SignedDyadic.MAX_SIGNIFICAND_BITS / 8 + 2;

	byte[] encode(PendingPhaseVolumeState state) throws IOException {
		if (state == null || state.isEmpty()) {
			throw new IllegalArgumentException("Empty pending phase volume is represented by extension absence");
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(state.cellCount());
			out.writeInt(state.entries().size());
			for (PendingPhaseVolumeState.Entry entry : state.entries()) {
				out.writeInt(entry.cell()); writeDyadic(out, entry.signedCellVolume());
			}
		}
		byte[] payload = bytes.toByteArray();
		if (payload.length > ThermalLimits.MAX_SERIALIZED_EXTENSION_BYTES) {
			throw new IOException("Pending phase-volume extension is over budget");
		}
		return payload;
	}

	PendingPhaseVolumeState decode(byte[] payload, int expectedCellCount) throws IOException {
		if (payload == null || payload.length > ThermalLimits.MAX_SERIALIZED_EXTENSION_BYTES) {
			throw new IOException("Pending phase-volume extension is missing or over budget");
		}
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
			if (input.readInt() != MAGIC || input.readInt() != VERSION) {
				throw new IOException("Unknown pending phase-volume payload");
			}
			int cellCount = input.readInt(), count = input.readInt();
			if (cellCount != expectedCellCount || count <= 0 || count > cellCount) {
				throw new IOException("Pending phase-volume shape disagrees with region-state");
			}
			ArrayList<PendingPhaseVolumeState.Entry> entries = new ArrayList<>(count);
			for (int index = 0; index < count; index++) {
				entries.add(new PendingPhaseVolumeState.Entry(input.readInt(), readDyadic(input)));
			}
			if (input.read() != -1) throw new IOException("Trailing pending phase-volume bytes");
			return new PendingPhaseVolumeState(cellCount, entries);
		} catch (EOFException truncated) {
			throw new IOException("Truncated pending phase-volume payload", truncated);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid pending phase-volume payload: " + invalid.getMessage(), invalid);
		}
	}

	private static void writeDyadic(DataOutputStream out, SignedDyadic value) throws IOException {
		byte[] significand = value.significand().toByteArray();
		out.writeInt(value.exponent()); out.writeInt(significand.length); out.write(significand);
	}

	private static SignedDyadic readDyadic(DataInputStream input) throws IOException {
		int exponent = input.readInt(), length = input.readInt();
		if (length <= 0 || length > MAX_DYADIC_BYTES) {
			throw new IOException("Pending phase-volume dyadic significand length is invalid");
		}
		byte[] bytes = input.readNBytes(length);
		if (bytes.length != length) throw new EOFException("Truncated pending phase-volume dyadic");
		SignedDyadic result = new SignedDyadic(new BigInteger(bytes), exponent);
		if (result.exponent() != exponent
				|| !java.util.Arrays.equals(result.significand().toByteArray(), bytes)) {
			throw new IOException("Pending phase-volume dyadic encoding is non-canonical");
		}
		return result;
	}
}
