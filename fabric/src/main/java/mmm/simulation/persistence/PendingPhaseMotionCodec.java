package mmm.simulation.persistence;

import mmm.fluid.PendingPhaseMotionState;
import mmm.thermal.ThermalLimits;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;

/** Sparse manifest-only thermal record for phase cells awaiting a mechanical step. */
final class PendingPhaseMotionCodec {
	static final String MANIFEST_EXTENSION = "mmm:pending_phase_motion";
	private static final int MAGIC = 0x5257504d; // RWPM
	private static final int VERSION = 1;

	byte[] encode(PendingPhaseMotionState state) throws IOException {
		if (state == null || state.isEmpty()) {
			throw new IllegalArgumentException("Empty pending phase motion is represented by extension absence");
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(state.cellCount());
			out.writeInt(state.entries().size());
			for (PendingPhaseMotionState.Entry entry : state.entries()) {
				out.writeInt(entry.cell());
			}
		}
		byte[] payload = bytes.toByteArray();
		if (payload.length > ThermalLimits.MAX_SERIALIZED_EXTENSION_BYTES) {
			throw new IOException("Pending phase-motion extension is over budget");
		}
		return payload;
	}

	PendingPhaseMotionState decode(byte[] payload, int expectedCellCount) throws IOException {
		if (payload == null || payload.length > ThermalLimits.MAX_SERIALIZED_EXTENSION_BYTES) {
			throw new IOException("Pending phase-motion extension is missing or over budget");
		}
		try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
			if (input.readInt() != MAGIC || input.readInt() != VERSION) {
				throw new IOException("Unknown pending phase-motion payload");
			}
			int cellCount = input.readInt(), count = input.readInt();
			if (cellCount != expectedCellCount || count <= 0 || count > cellCount) {
				throw new IOException("Pending phase-motion shape disagrees with region-state");
			}
			ArrayList<PendingPhaseMotionState.Entry> entries = new ArrayList<>(count);
			for (int index = 0; index < count; index++) {
				entries.add(new PendingPhaseMotionState.Entry(input.readInt()));
			}
			if (input.read() != -1) throw new IOException("Trailing pending phase-motion bytes");
			return new PendingPhaseMotionState(cellCount, entries);
		} catch (EOFException truncated) {
			throw new IOException("Truncated pending phase-motion payload", truncated);
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid pending phase-motion payload: " + invalid.getMessage(), invalid);
		}
	}

}
