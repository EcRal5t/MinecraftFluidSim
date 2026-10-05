package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.fluid.air.GasGeometryState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Bounded exact gas support and chamber membership, independent of base-cell cardinality. */
final class GasGeometryStateCodec {
	static final String MANIFEST_EXTENSION = "mmm:gas_geometry";
	private static final int MAGIC = 0x4747454f;
	private static final int MAX_BYTES = 16 * 1_048_576;
	byte[] encode(GasGeometryState state) throws IOException {
		long bytes = 21L + 4L * state.cells().size() + LiquidGeometryStateCodec.cellBytes(state.cells());
		if (bytes > MAX_BYTES) throw new IOException("Gas geometry exceeds checkpoint payload budget");
		var buffer = new ByteArrayOutputStream((int) bytes);
		try (var out = new DataOutputStream(buffer)) {
			out.writeInt(MAGIC); out.writeDouble(state.rootWidthMetres()); out.writeInt(state.nodeCount());
			out.writeByte(state.sealedExterior() ? 1 : 0);
			LiquidGeometryStateCodec.writeInts(out, state.nodeOrdinals()); LiquidGeometryStateCodec.writeCells(out, state.cells());
		}
		if (buffer.size() != bytes) throw new IllegalStateException("Gas geometry wire size differs from its admission estimate");
		return buffer.toByteArray();
	}
	GasGeometryState decode(byte[] payload) throws IOException {
		if (payload.length > MAX_BYTES) throw new IOException("Gas geometry exceeds checkpoint payload budget");
		try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
			if (in.readInt() != MAGIC) throw new IOException("Unknown gas geometry encoding");
			double width = in.readDouble(); int count = in.readInt(), sealed = in.readUnsignedByte();
			if (sealed > 1) throw new IOException("Invalid gas exterior condition");
			int[] nodes = LiquidGeometryStateCodec.readInts(in); var cells = LiquidGeometryStateCodec.readCells(in);
			if (in.available() != 0) throw new IOException("Trailing gas geometry bytes");
			return new GasGeometryState(width, cells, nodes, count, sealed == 1);
		} catch (IllegalArgumentException | ArithmeticException invalid) {
			throw new IOException("Invalid gas geometry: " + invalid.getMessage(), invalid);
		}
	}
}
