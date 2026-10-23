package mmm.fluid;

import java.util.ArrayList;
import java.util.List;
import mmm.fluid.FluidSurfaceInput.Plane;

/**
 * Triangulates the union of bounded convex liquid pieces with one oriented copy of each material
 * contact.
 */
public final class FluidMaterialSurfaceMesher {
  private FluidMaterialSurfaceMesher() {}

  public static Mesh build(FluidMaterialRenderSnapshot snapshot) {
    var input = snapshot.geometry();
    var pieces = input.pieces();
    int[] heads = new int[8 * snapshot.cellCount()];
    java.util.Arrays.fill(heads, -1);
    int[] next = new int[pieces.size()];
    for (int i = pieces.size() - 1; i >= 0; i--) {
      int micro = 8 * pieces.get(i).cell() + pieces.get(i).micro();
      next[i] = heads[micro];
      heads[micro] = i;
    }
    var surfaces = new ArrayList<SurfaceTriangle>();
    var outer = new ArrayList<Triangle>();
    var interfaces = new ArrayList<InterfaceTriangle>();
    for (int i = 0; i < pieces.size(); i++) {
      var piece = pieces.get(i);
      if (simpleBox(input, i, heads, next, surfaces, outer, interfaces)) continue;
      var candidates = candidates(input, piece, heads, next);
      var candidatePlanes = new ArrayList<List<Plane>>(candidates.size());
      for (int otherIndex : candidates)
        candidatePlanes.add(relativePlanes(input, piece, pieces.get(otherIndex)));
      for (var face : SurfacePolyhedron.build(piece)) {
        List<List<Point>> fragments = new ArrayList<>();
        fragments.add(face.points());
        for (int candidate = 0; candidate < candidates.size(); candidate++) {
          int otherIndex = candidates.get(candidate);
          if (otherIndex == i || fragments.isEmpty()) continue;
          var other = pieces.get(otherIndex);
          var clips = candidatePlanes.get(candidate);
          if (!overlapsFace(face.points(), clips)) continue;
          var remaining = new ArrayList<List<Point>>();
          for (var fragment : fragments) {
            boolean covered = true;
            for (var plane : clips) {
              for (var point : fragment)
                if (SurfacePolyhedron.distance(plane, point) > 0) {
                  covered = false;
                  break;
                }
              if (!covered) break;
            }
            if (covered) {
              if (i < otherIndex && piece.phase() != other.phase())
                emit(
                    input,
                    piece,
                    other.material(),
                    other.phase(),
                    fragment,
                    surfaces,
                    outer,
                    interfaces);
              continue;
            }
            List<Point> inside = fragment;
            for (var plane : clips) {
              var outside =
                  SurfacePolyhedron.clipPolygon(inside, SurfacePolyhedron.inverse(plane), null);
              // A polygon lying in a plane belongs to the covered side alone.
              boolean positive = false;
              for (var point : inside)
                if (SurfacePolyhedron.distance(plane, point) > 0) {
                  positive = true;
                  break;
                }
              if (positive && outside.size() >= 3) remaining.add(outside);
              inside = SurfacePolyhedron.clipPolygon(inside, plane, null);
              if (inside.size() < 3) break;
            }
            if (i < otherIndex && piece.phase() != other.phase() && inside.size() >= 3)
              emit(
                  input,
                  piece,
                  other.material(),
                  other.phase(),
                  inside,
                  surfaces,
                  outer,
                  interfaces);
          }
          fragments = remaining;
        }
        for (var polygon : fragments)
          emit(input, piece, -1, -1, polygon, surfaces, outer, interfaces);
      }
    }
    return new Mesh(outer, interfaces, 0, 0, 0, surfaces);
  }

  private static boolean overlapsFace(List<Point> points, List<Plane> planes) {
    int dimensions = 0;
    for (int axis = 0; axis < 3; axis++) {
      double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
      for (var point : points) {
        double value = axis == 0 ? point.x() : axis == 1 ? point.y() : point.z();
        minimum = Math.min(minimum, value);
        maximum = Math.max(maximum, value);
      }
      double lower = -planes.get(2 * axis).offset(), upper = planes.get(2 * axis + 1).offset();
      double extent = Math.min(maximum, upper) - Math.max(minimum, lower);
      if (extent < 0) return false;
      if (extent > 0) dimensions++;
    }
    return dimensions >= 2;
  }

  /** Full boxes decide visibility from the six adjacent boxes before allocating a polygon. */
  private static boolean simpleBox(
      FluidSurfaceInput input,
      int ordinal,
      int[] heads,
      int[] next,
      List<SurfaceTriangle> surfaces,
      List<Triangle> outer,
      List<InterfaceTriangle> interfaces) {
    var piece = input.pieces().get(ordinal);
    if (!piece.clips().isEmpty() || next[heads[8 * piece.cell() + piece.micro()]] >= 0)
      return false;
    for (int face = 0; face < 6; face++) {
      int neighbor = neighbor(input, piece, face);
      int other = neighbor < 0 ? -1 : heads[neighbor];
      if (other >= 0 && (next[other] >= 0 || !input.pieces().get(other).clips().isEmpty()))
        return false;
    }
    for (int face = 0; face < 6; face++) {
      int neighbor = neighbor(input, piece, face);
      int otherIndex = neighbor < 0 ? -1 : heads[neighbor];
      int otherMaterial = -1, otherPhase = -1;
      if (otherIndex >= 0) {
        var other = input.pieces().get(otherIndex);
        if (piece.phase() == other.phase() || ordinal > otherIndex) continue;
        otherMaterial = other.material();
        otherPhase = other.phase();
      }
      emit(
          input,
          piece,
          otherMaterial,
          otherPhase,
          boxFace(piece.micro(), face),
          surfaces,
          outer,
          interfaces);
    }
    return true;
  }

  private static List<Point> boxFace(int micro, int face) {
    double x = .5 * (micro & 1), y = .5 * (micro >> 1 & 1), z = .5 * (micro >> 2 & 1);
    int axis = face / 2;
    double coordinate = (axis == 0 ? x : axis == 1 ? y : z) + .5 * (face & 1);
    Point a, b, c, d;
    if (axis == 0) {
      a = new Point(coordinate, y, z);
      b = new Point(coordinate, y + .5, z);
      c = new Point(coordinate, y + .5, z + .5);
      d = new Point(coordinate, y, z + .5);
    } else if (axis == 1) {
      a = new Point(x, coordinate, z);
      b = new Point(x, coordinate, z + .5);
      c = new Point(x + .5, coordinate, z + .5);
      d = new Point(x + .5, coordinate, z);
    } else {
      a = new Point(x, y, coordinate);
      b = new Point(x + .5, y, coordinate);
      c = new Point(x + .5, y + .5, coordinate);
      d = new Point(x, y + .5, coordinate);
    }
    return (face & 1) == 1 ? List.of(a, b, c, d) : List.of(a, d, c, b);
  }

  /** Packed microbox identity on one face, with -1 for the region exterior. */
  private static int neighbor(FluidSurfaceInput input, FluidSurfaceInput.Piece piece, int face) {
    int axis = face / 2, sign = (face & 1) == 0 ? -1 : 1;
    int bit = piece.micro() >> axis & 1;
    if (bit == 0 && sign == 1 || bit == 1 && sign == -1)
      return 8 * piece.cell() + (piece.micro() ^ 1 << axis);
    int coordinate =
        axis == 0
            ? piece.cell() % input.sizeX()
            : axis == 1
                ? piece.cell() / input.sizeX() % input.sizeY()
                : piece.cell() / (input.sizeX() * input.sizeY());
    int length = axis == 0 ? input.sizeX() : axis == 1 ? input.sizeY() : input.sizeZ();
    if (coordinate + sign < 0 || coordinate + sign >= length) return -1;
    int stride = axis == 0 ? 1 : axis == 1 ? input.sizeX() : input.sizeX() * input.sizeY();
    return 8 * (piece.cell() + sign * stride) + (piece.micro() ^ 1 << axis);
  }

  private static List<Integer> candidates(
      FluidSurfaceInput input, FluidSurfaceInput.Piece piece, int[] heads, int[] next) {
    var result = new ArrayList<Integer>();
    for (int face = -1; face < 6; face++) {
      int micro = face < 0 ? 8 * piece.cell() + piece.micro() : neighbor(input, piece, face);
      if (micro < 0) continue;
      for (int entry = heads[micro]; entry >= 0; entry = next[entry]) result.add(entry);
    }
    return result;
  }

  private static List<Plane> relativePlanes(
      FluidSurfaceInput input, FluidSurfaceInput.Piece first, FluidSurfaceInput.Piece second) {
    int dx = second.cell() % input.sizeX() - first.cell() % input.sizeX();
    int dy =
        second.cell() / input.sizeX() % input.sizeY()
            - first.cell() / input.sizeX() % input.sizeY();
    int dz =
        second.cell() / (input.sizeX() * input.sizeY())
            - first.cell() / (input.sizeX() * input.sizeY());
    var result = new ArrayList<Plane>();
    for (var p : SurfacePolyhedron.planes(second))
      result.add(
          new Plane(p.nx(), p.ny(), p.nz(), p.offset() + p.nx() * dx + p.ny() * dy + p.nz() * dz));
    return result;
  }

  private static void emit(
      FluidSurfaceInput input,
      FluidSurfaceInput.Piece piece,
      int otherMaterial,
      int otherPhase,
      List<Point> polygon,
      List<SurfaceTriangle> surfaces,
      List<Triangle> outer,
      List<InterfaceTriangle> interfaces) {
    for (int i = 1; i + 1 < polygon.size(); i++) {
      Triangle triangle =
          new Triangle(
              world(input, piece.cell(), polygon.getFirst()),
              world(input, piece.cell(), polygon.get(i)),
              world(input, piece.cell(), polygon.get(i + 1)));
      if (!(triangle.areaSquared() > 0)) continue;
      surfaces.add(
          new SurfaceTriangle(
              triangle, piece.material(), otherMaterial, piece.phase(), otherPhase, piece.cell()));
      if (otherMaterial < 0) outer.add(triangle);
      else if (piece.phase() != otherPhase) {
        boolean forward = piece.phase() < otherPhase;
        interfaces.add(
            new InterfaceTriangle(
                Math.min(piece.phase(), otherPhase),
                Math.max(piece.phase(), otherPhase),
                forward ? triangle : new Triangle(triangle.a(), triangle.c(), triangle.b())));
      }
    }
  }

  private static Point world(FluidSurfaceInput input, int cell, Point p) {
    return new Point(
        (cell % input.sizeX() + p.x()) * input.cellSize(),
        (cell / input.sizeX() % input.sizeY() + p.y()) * input.cellSize(),
        (cell / (input.sizeX() * input.sizeY()) + p.z()) * input.cellSize());
  }

  public static int dominantMaterialAt(FluidMaterialRenderSnapshot snapshot, Point point) {
    return snapshot.geometry().materialAt(point.x(), point.y(), point.z());
  }

  public static double liquidLevelAt(FluidMaterialRenderSnapshot snapshot, Point point) {
    return dominantMaterialAt(snapshot, point) >= 0 ? 1 : 0;
  }

  public record Mesh(
      List<Triangle> outerTriangles,
      List<InterfaceTriangle> interfaceTriangles,
      int duplicateOuterTriangles,
      int duplicateInterfaceTriangles,
      int overlappingCategoryTriangles,
      List<SurfaceTriangle> surfaces) {
    public Mesh {
      outerTriangles = List.copyOf(outerTriangles);
      interfaceTriangles = List.copyOf(interfaceTriangles);
      surfaces = List.copyOf(surfaces);
    }
  }

  /** Normal points from the first liquid into the second liquid or gas (-1). */
  public record SurfaceTriangle(
      Triangle triangle,
      int firstMaterial,
      int secondMaterial,
      int firstPhase,
      int secondPhase,
      int cell) {}

  public record InterfaceTriangle(int firstPhase, int secondPhase, Triangle triangle) {}

  public record Triangle(Point a, Point b, Point c) {
    public Point normal() {
      return b.subtract(a).cross(c.subtract(a));
    }

    public double areaSquared() {
      return normal().lengthSquared() * .25;
    }

    public Point centroid() {
      return a.add(b).add(c).scale(1.0 / 3);
    }
  }

  public record Point(double x, double y, double z) {
    public Point add(Point p) {
      return new Point(x + p.x, y + p.y, z + p.z);
    }

    public Point subtract(Point p) {
      return new Point(x - p.x, y - p.y, z - p.z);
    }

    public Point scale(double value) {
      return new Point(x * value, y * value, z * value);
    }

    public Point lerp(Point p, double t) {
      return new Point(x + (p.x - x) * t, y + (p.y - y) * t, z + (p.z - z) * t);
    }

    public Point cross(Point p) {
      return new Point(y * p.z - z * p.y, z * p.x - x * p.z, x * p.y - y * p.x);
    }

    public double dot(Point p) {
      return x * p.x + y * p.y + z * p.z;
    }

    public double lengthSquared() {
      return dot(this);
    }

    public Point normalized() {
      double length = Math.sqrt(lengthSquared());
      return length == 0 ? this : scale(1 / length);
    }
  }
}
