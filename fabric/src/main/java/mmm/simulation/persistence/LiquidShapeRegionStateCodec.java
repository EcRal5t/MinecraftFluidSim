package mmm.simulation.persistence;

import mmm.fluid.LiquidCellShape;
import mmm.model.region.RegionSectionState;
import mmm.units.SignedDyadic;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Sparse section-local payload of positive liquid boxes and their published raw moment binding. */
final class LiquidShapeRegionStateCodec {
	static final String SECTION_EXTENSION = "mmm:liquid_shapes";
	private static final int MAGIC = 0x4c534850; // LSHP
	private static final int MAX_BYTES = 16 * 1_048_576;
	byte[] encode(List<LiquidCellShape> shapes) throws IOException {
		long size = 8; for (var shape : shapes) size += shape.cpuBytes();
		if (shapes.isEmpty() || size > MAX_BYTES) throw new IOException("Liquid shape section is empty or over budget");
		var bytes = new ByteArrayOutputStream();
		try (var out = new DataOutputStream(new java.io.FilterOutputStream(bytes) {
			@Override public void write(int value) throws IOException { requireSpace(1); bytes.write(value); }
			@Override public void write(byte[] value, int offset, int length) throws IOException {
				java.util.Objects.checkFromIndexSize(offset, length, value.length); requireSpace(length); bytes.write(value, offset, length);
			}
			private void requireSpace(int length) throws IOException {
				if (length > MAX_BYTES - bytes.size()) throw new IOException("Liquid shape payload is over budget");
			}
		})) {
			out.writeInt(MAGIC); out.writeInt(shapes.size());
			for (var shape : shapes) {
				out.writeInt(shape.cell()); out.writeInt(shape.boxes().size());
				for (int a = 0; a < 3; a++) GasRegionStateCodec.writeDyadic(out, shape.firstMoment(a));
				for (var box : shape.boxes()) for (int a = 0; a < 3; a++) {
					GasRegionStateCodec.writeDyadic(out, box.lower(a)); GasRegionStateCodec.writeDyadic(out, box.upper(a));
				}
			}
		}
		byte[] payload = bytes.toByteArray();
		if (payload.length > MAX_BYTES) throw new IOException("Liquid shape payload is over budget");
		return payload;
	}
	List<LiquidCellShape> decode(RegionSectionState section, int sizeX, int sizeY, int sizeZ, float h) throws IOException {
		byte[] payload = null;
		for (var extension : section.extensions()) if (extension.type().equals(SECTION_EXTENSION)) {
			if (payload != null) throw new IOException("Duplicate liquid shape section extension");
			payload = extension.payload();
		}
		if (payload == null) return List.of();
		if (payload.length < 8 || payload.length > MAX_BYTES) throw new IOException("Invalid liquid shape section size");
		try (var input = new DataInputStream(new ByteArrayInputStream(payload))) {
			if (input.readInt() != MAGIC) throw new IOException("Unknown liquid shape payload");
			int count = input.readInt();
			if (count <= 0 || count > section.solidMask().length || count > payload.length / 32)
				throw new IOException("Liquid shape count exceeds section coverage or payload");
			var shapes = new ArrayList<LiquidCellShape>(count);
			for (int i = 0; i < count; i++) {
				int cell = input.readInt(), boxes = input.readInt();
				localCell(section, cell, sizeX, sizeY, sizeZ);
				if (boxes <= 0 || boxes > LiquidCellShape.MAX_BOXES_PER_CELL) throw new IOException("Liquid box count is over budget");
				var first = new SignedDyadic[3]; for (int a = 0; a < 3; a++) first[a] = GasRegionStateCodec.readDyadic(input);
				var values = new ArrayList<LiquidCellShape.Box>(boxes);
				for (int b = 0; b < boxes; b++) {
					var lower = new SignedDyadic[3]; var upper = new SignedDyadic[3];
					for (int a = 0; a < 3; a++) { lower[a] = GasRegionStateCodec.readDyadic(input); upper[a] = GasRegionStateCodec.readDyadic(input); }
					values.add(new LiquidCellShape.Box(lower[0], lower[1], lower[2], upper[0], upper[1], upper[2]));
				}
				shapes.add(new LiquidCellShape(cell, values, first[0], first[1], first[2]));
			}
			if (input.read() != -1) throw new IOException("Trailing liquid shape payload");
			byte[] masks = section.solidMask();
			LiquidCellShape.requireMatches(shapes, sizeX, sizeY, sizeZ, h, section.positionVolume(),
					cell -> Byte.toUnsignedInt(masks[localCellUnchecked(section, cell, sizeX, sizeY)]));
			return List.copyOf(shapes);
		} catch (IllegalArgumentException | ArithmeticException invalid) { throw new IOException("Invalid liquid shape binding: " + invalid.getMessage(), invalid); }
	}
	private static int localCell(RegionSectionState section, int cell, int sizeX, int sizeY, int sizeZ) throws IOException {
		int x = cell % sizeX, y = cell / sizeX % sizeY, z = cell / (sizeX * sizeY);
		if (cell < 0 || z >= sizeZ || x < section.localStartX() || x >= section.localStartX() + section.sizeX()
				|| y < section.localStartY() || y >= section.localStartY() + section.sizeY()
				|| z < section.localStartZ() || z >= section.localStartZ() + section.sizeZ())
			throw new IOException("Liquid shape belongs to another section or lies outside the region");
		return localCellUnchecked(section, cell, sizeX, sizeY);
	}
	private static int localCellUnchecked(RegionSectionState s, int cell, int sizeX, int sizeY) {
		return cell % sizeX - s.localStartX() + s.sizeX() * (cell / sizeX % sizeY - s.localStartY()
				+ s.sizeY() * (cell / (sizeX * sizeY) - s.localStartZ()));
	}
}
