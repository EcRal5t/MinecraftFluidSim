package mmm.simulation.persistence;

import java.io.*;
import java.util.ArrayList;
import mmm.fluid.FixedGeometrySnapshot;
import mmm.fluid.ScreenFace;
import mmm.spatial.SpatialGrid;

/** Stores screen faces and exterior wall masks by stable grid coordinates. */
final class FixedFaceGeometryCodec {
    static final String MANIFEST_EXTENSION = "mmm:fixed_faces";

    byte[] encode(FixedGeometrySnapshot geometry) throws IOException {
        var grid = geometry.grid();
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(geometry.screens().size());
            for (var screen : geometry.screens()) {
                int face = screen.face();
                out.writeInt(grid.faceAxis(face));
                for (int axis = 0; axis < 3; axis++) out.writeInt(grid.faceCoordinate(face, axis));
                out.writeDouble(screen.poreDiameter()); out.writeDouble(screen.openFraction());
            }
            int[] exterior = geometry.copyExteriorMasks();
            int count = 0;
            for (int mask : exterior) if (mask != 0) count++;
            out.writeInt(count);
            for (int cell = 0; cell < exterior.length; cell++) if (exterior[cell] != 0) {
                out.writeInt(grid.x(cell)); out.writeInt(grid.y(cell)); out.writeInt(grid.z(cell));
                out.writeInt(exterior[cell]);
            }
        }
        return bytes.toByteArray();
    }

    FixedGeometrySnapshot decode(SpatialGrid grid, byte[] masks, byte[] bytes) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int count = in.readInt();
            if (count < 0 || count > grid.faceCount()) throw new IOException("Invalid screen face count");
            var faces = new ArrayList<ScreenFace>(count);
            for (int i = 0; i < count; i++) {
                int axis = in.readInt(), x = in.readInt(), y = in.readInt(), z = in.readInt();
                if (axis < 0 || axis > 2) throw new IOException("Invalid screen face axis");
                faces.add(new ScreenFace(grid.faceAt(x, y, z, axis), in.readDouble(), in.readDouble()));
            }
            int exteriorCount = in.readInt();
            if (exteriorCount < 0 || exteriorCount > grid.cellCount() || in.available() != 16L * exteriorCount)
                throw new IOException("Invalid exterior wall count");
            int[] exterior = new int[grid.cellCount()];
            for (int i = 0; i < exteriorCount; i++) {
                int cell = grid.cell(in.readInt(), in.readInt(), in.readInt()), mask = in.readInt();
                if (cell < 0 || mask <= 0 || (mask & ~0xffffff) != 0 || exterior[cell] != 0)
                    throw new IOException("Invalid exterior wall mask");
                exterior[cell] = mask;
            }
            return FixedGeometrySnapshot.fromMasks(grid, masks, exterior, faces);
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid fixed face geometry", invalid); }
    }
}
