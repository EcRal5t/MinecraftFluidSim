package mmm.simulation.persistence;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import mmm.fluid.ScreenFace;
import mmm.spatial.SpatialGrid;

/** Stores shared screen faces by stable grid coordinates. */
final class ScreenFaceCodec {
	static final String MANIFEST_EXTENSION = "mmm:screen_faces";
	byte[] encode(SpatialGrid grid, List<ScreenFace> faces) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var out = new DataOutputStream(bytes)) {
			out.writeInt(faces.size());
			for (var screen : faces) {
				int face = screen.face();
				out.writeInt(grid.faceAxis(face));
				for (int axis = 0; axis < 3; axis++) out.writeInt(grid.faceCoordinate(face, axis));
				out.writeDouble(screen.poreDiameter()); out.writeDouble(screen.openFraction());
			}
		}
		return bytes.toByteArray();
	}
	List<ScreenFace> decode(SpatialGrid grid, byte[] bytes) throws IOException {
		try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
			int count = in.readInt();
			if (count < 0 || count > grid.faceCount() || bytes.length != 4L + 32L * count)
				throw new IOException("Invalid screen face count");
			var faces = new ArrayList<ScreenFace>(count);
			for (int i = 0; i < count; i++) {
				int axis = in.readInt(), x = in.readInt(), y = in.readInt(), z = in.readInt();
				if (axis < 0 || axis > 2) throw new IOException("Invalid screen face axis");
				faces.add(new ScreenFace(grid.faceAt(x, y, z, axis), in.readDouble(), in.readDouble()));
			}
			return List.copyOf(faces);
		} catch (IllegalArgumentException invalid) { throw new IOException("Invalid screen face", invalid); }
	}
}
