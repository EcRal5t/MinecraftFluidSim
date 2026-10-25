package mmm.fluid;

import java.util.List;

/**
 * Bounded convex liquid pieces in region-local coordinates. Clips use cell-local unit coordinates.
 */
public record FluidSurfaceInput(
    int sizeX, int sizeY, int sizeZ, float cellSize, List<Piece> pieces) {
  public static final int MAX_PIECES = 1 << 18;
  public static final int MAX_CLIPS = 3;

  public FluidSurfaceInput {
    if ((sizeX <= 0 || sizeY <= 0 || sizeZ <= 0)
            && !(sizeX == 0 && sizeY == 0 && sizeZ == 0 && pieces.isEmpty())
        || !(cellSize > 0)
        || !Float.isFinite(cellSize))
      throw new IllegalArgumentException("Invalid surface dimensions");
    int cells = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
    if (pieces instanceof CompactFluidSurface compact) {
      if(compact.cells()!=cells)throw new IllegalArgumentException("Compact surface dimensions differ");
    } else {
    pieces =
        pieces.stream()
            .sorted(
                java.util.Comparator.comparingInt(Piece::cell)
                    .thenComparingInt(Piece::micro)
                    .thenComparingInt(Piece::material)
                    .thenComparingInt(Piece::phase))
            .toList();
    if (pieces.size() > MAX_PIECES)
      throw new IllegalArgumentException("Surface piece limit exceeded");
    for (var piece : pieces)
      if (piece.cell() >= cells) throw new IllegalArgumentException("Surface cell exceeds region");
    }
  }

  /** Material containing the camera point, in region-local metres; -1 denotes gas or exterior. */
  public int materialAt(double x, double y, double z) {
    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return -1;
    int cx = (int) Math.floor(x / cellSize),
        cy = (int) Math.floor(y / cellSize),
        cz = (int) Math.floor(z / cellSize);
    if (cx < 0 || cy < 0 || cz < 0 || cx >= sizeX || cy >= sizeY || cz >= sizeZ) return -1;
    int cell = cx + sizeX * (cy + sizeY * cz);
    var point =
        new FluidMaterialSurfaceMesher.Point(
            x / cellSize - cx, y / cellSize - cy, z / cellSize - cz);
    int lower = 0, upper = pieces.size();
    while (lower < upper) {
      int middle = (lower + upper) >>> 1;
      if (pieces.get(middle).cell() < cell) lower = middle + 1;
      else upper = middle;
    }
    for (int i = lower; i < pieces.size() && pieces.get(i).cell() == cell; i++) {
      var piece = pieces.get(i);
      boolean inside = true;
      for (var plane : SurfacePolyhedron.planes(piece))
        if (SurfacePolyhedron.distance(plane, point) > 0) {
          inside = false;
          break;
        }
      if (inside) return piece.material();
    }
    return -1;
  }

  /** The implicit box is the selected 2x2x2 microbox, intersected with every clip. */
  public record Piece(int cell, int micro, int material, int phase, List<Plane> clips) {
    public Piece {
      if (cell < 0 || micro < 0 || micro > 7 || material < 0 || phase < 0)
        throw new IllegalArgumentException("Invalid surface piece identity");
      clips = List.copyOf(clips);
      if (clips.size() > MAX_CLIPS)
        throw new IllegalArgumentException("Surface clip limit exceeded");
    }
  }

  /** Closed half-space nx*x + ny*y + nz*z <= offset. */
  public record Plane(double nx, double ny, double nz, double offset) {
    public Plane {
      if (!Double.isFinite(nx)
          || !Double.isFinite(ny)
          || !Double.isFinite(nz)
          || !Double.isFinite(offset)
          || nx == 0 && ny == 0 && nz == 0)
        throw new IllegalArgumentException("Invalid surface plane");
    }
  }
}
