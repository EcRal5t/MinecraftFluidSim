package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.geometry.ResolvedControlVolume;

import cc.ecisr.mmm.fluid.LiquidGeometryState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;

/** Canonical, bounded encoding of exact interfaces, inventory ownership and resolved velocity. */
final class LiquidGeometryStateCodec {
	static final String MANIFEST_EXTENSION = "mmm:liquid_geometry";
	private static final int MAGIC = 0x4c47454f;
	private static final int MAX_BYTES = 16 * 1_048_576;

	byte[] encode(LiquidGeometryState state) throws IOException {
		long bytes = 24L + 4L * state.markerCount() + 8L * state.cells().size() + cellBytes(state.cells());
		if (bytes > MAX_BYTES) throw new IOException("Liquid geometry exceeds checkpoint payload budget");
		var buffer = new ByteArrayOutputStream((int) bytes);
		try (var out = new DataOutputStream(buffer)) {
			out.writeInt(MAGIC); out.writeDouble(state.rootWidthMetres());
			writeInts(out, state.copyMarkerCells()); writeInts(out, state.copyFragmentOwners()); writeInts(out, state.copyControlCells());
			writeCells(out, state.cells());
		}
		if (buffer.size() != bytes) throw new IllegalStateException("Liquid geometry wire size differs from its admission estimate");
		return buffer.toByteArray();
	}

	LiquidGeometryState decode(byte[] payload) throws IOException {
		if (payload.length > MAX_BYTES) throw new IOException("Liquid geometry exceeds checkpoint payload budget");
		try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
			if (in.readInt() != MAGIC) throw new IOException("Unknown liquid geometry encoding");
			double width = in.readDouble();
			int[] markers = readInts(in), owners = readInts(in), controls = readInts(in);
			var cells = readCells(in);
			if (owners.length != cells.size()) throw new IOException("Liquid geometry fragment count differs from ownership");
			if (in.available() != 0) throw new IOException("Trailing liquid geometry bytes");
			return new LiquidGeometryState(width, cells, markers, owners, controls);
		} catch (IllegalArgumentException | ArithmeticException invalid) {
			throw new IOException("Invalid liquid geometry: " + invalid.getMessage(), invalid);
		}
	}

	static long cellBytes(java.util.List<ResolvedControlVolume> cells) throws IOException {
		long bytes = 4;
		for (var cell : cells) {
			bytes += 56L + 4L * (cell.copyFaceOffsets().length + cell.copyFaceVertices().length);
			for (var value : cell.copyVertices()) bytes += 4L + Math.max(1L, (value.bitLength() + 8L) / 8);
			for (var value : cell.copyPlanes()) bytes += 4L + Math.max(1L, (value.bitLength() + 8L) / 8);
			if (bytes > MAX_BYTES) throw new IOException("Convex geometry exceeds checkpoint payload budget");
		}
		return bytes;
	}
	static void writeCells(DataOutputStream out, java.util.List<ResolvedControlVolume> cells) throws IOException {
		out.writeInt(cells.size());
		for (var cell : cells) {
			out.writeInt(cell.level()); out.writeInt(cell.x()); out.writeInt(cell.y()); out.writeInt(cell.z());
			writeBigInts(out, cell.copyVertices()); writeInts(out, cell.copyFaceOffsets());
			writeInts(out, cell.copyFaceVertices()); writeBigInts(out, cell.copyPlanes());
			for (double velocity : cell.copyVelocity()) out.writeDouble(velocity);
		}
	}
	static java.util.List<ResolvedControlVolume> readCells(DataInputStream in) throws IOException {
		int count = readCount(in, 284); var cells = new ArrayList<ResolvedControlVolume>(count);
		for (int c = 0; c < count; c++) {
			int level = in.readInt(), x = in.readInt(), y = in.readInt(), z = in.readInt();
			BigInteger[] vertices = readBigInts(in);
			int[] offsets = readInts(in), indices = readInts(in);
			BigInteger[] planes = readBigInts(in);
			double[] velocity = {in.readDouble(), in.readDouble(), in.readDouble()};
			cells.add(new ResolvedControlVolume(level, x, y, z, vertices, offsets, indices, planes, velocity));
		}
		return java.util.List.copyOf(cells);
	}
	static void writeInts(DataOutputStream out, int[] values) throws IOException {
		out.writeInt(values.length); for (int value : values) out.writeInt(value);
	}
	static int[] readInts(DataInputStream in) throws IOException {
		int[] values = new int[readCount(in, 4)];
		for (int i = 0; i < values.length; i++) values[i] = in.readInt();
		return values;
	}
	private static void writeBigInts(DataOutputStream out, BigInteger[] values) throws IOException {
		out.writeInt(values.length);
		for (var value : values) { byte[] bytes = value.toByteArray(); out.writeInt(bytes.length); out.write(bytes); }
	}
	private static BigInteger[] readBigInts(DataInputStream in) throws IOException {
		var values = new BigInteger[readCount(in, 5)];
		for (int i = 0; i < values.length; i++) {
			int length = readCount(in, 1);
			if (length == 0) throw new IOException("Empty liquid geometry integer");
			byte[] bytes = in.readNBytes(length); var value = new BigInteger(bytes);
			if (!Arrays.equals(bytes, value.toByteArray())) throw new IOException("Non-canonical liquid geometry integer");
			values[i] = value;
		}
		return values;
	}
	private static int readCount(DataInputStream in, int minimumBytes) throws IOException {
		int count = in.readInt();
		if (count < 0 || count > in.available() / minimumBytes) throw new IOException("Liquid geometry length exceeds payload");
		return count;
	}
}
