package mmm.fluid;

import java.util.ArrayList;
import java.util.List;
import mmm.fluid.FluidMaterialSurfaceMesher.Point;
import mmm.fluid.FluidSurfaceInput.Plane;

/** Small convex-polyhedron operations shared by display projection and triangulation. */
final class SurfacePolyhedron {
  record Face(Plane plane, List<Point> points) {}

  static List<Plane> planes(FluidSurfaceInput.Piece piece) {
    var result = new ArrayList<Plane>(9);
    for (int axis = 0; axis < 3; axis++) {
      double lower = .5 * (piece.micro() >> axis & 1);
      result.add(plane(axis, -1, -lower));
      result.add(plane(axis, 1, lower + .5));
    }
    result.addAll(piece.clips());
    return result;
  }

  static Plane plane(int axis, double sign, double offset) {
    return new Plane(axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0, offset);
  }

  static double distance(Plane plane, Point p) {
    return plane.nx() * p.x() + plane.ny() * p.y() + plane.nz() * p.z() - plane.offset();
  }

  static List<Face> build(FluidSurfaceInput.Piece piece) {
    double x = .5 * (piece.micro() & 1),
        y = .5 * (piece.micro() >> 1 & 1),
        z = .5 * (piece.micro() >> 2 & 1);
    Point[] p = {
      new Point(x, y, z),
      new Point(x + .5, y, z),
      new Point(x + .5, y + .5, z),
      new Point(x, y + .5, z),
      new Point(x, y, z + .5),
      new Point(x + .5, y, z + .5),
      new Point(x + .5, y + .5, z + .5),
      new Point(x, y + .5, z + .5)
    };
    int[][] ids = {
      {0, 4, 7, 3}, {1, 2, 6, 5}, {0, 1, 5, 4}, {3, 7, 6, 2}, {0, 3, 2, 1}, {4, 5, 6, 7}
    };
    var planes = planes(piece);
    List<Face> faces = new ArrayList<>(6);
    for (int i = 0; i < 6; i++) {
      var points = new ArrayList<Point>(4);
      for (int id : ids[i]) points.add(p[id]);
      faces.add(new Face(planes.get(i), points));
    }
    for (var clip : piece.clips()) faces = clip(faces, clip);
    return faces;
  }

  static List<Face> clip(List<Face> faces, Plane plane) {
    // A plane touching a box only along a face or an edge leaves no volume.
    // Keeping that contact polygon would render a full-area, zero-thickness sheet.
    boolean hasInterior = false;
    for (var face : faces)
      for (var point : face.points())
        if (distance(plane, point) < 0) {
          hasInterior = true;
          break;
        }
    if (!hasInterior) return List.of();
    var result = new ArrayList<Face>();
    var cap = new ArrayList<Point>();
    for (var face : faces) {
      var polygon = clipPolygon(face.points(), plane, cap);
      if (polygon.size() >= 3) result.add(new Face(face.plane(), polygon));
    }
    if (cap.size() >= 3) {
      sort(cap, new Point(plane.nx(), plane.ny(), plane.nz()));
      result.add(new Face(plane, List.copyOf(cap)));
    }
    return result;
  }

  static List<Point> clipPolygon(List<Point> points, Plane plane, List<Point> cap) {
    if (points.isEmpty()) return List.of();
    var result = new ArrayList<Point>();
    Point previous = points.getLast();
    double pd = distance(plane, previous);
    for (Point current : points) {
      double cd = distance(plane, current);
      if ((pd > 0) != (cd > 0)) {
        Point intersection = previous.lerp(current, pd / (pd - cd));
        add(result, intersection);
        if (cap != null) add(cap, intersection);
      }
      if (cd <= 0) add(result, current);
      previous = current;
      pd = cd;
    }
    if (result.size() > 1 && result.getFirst().equals(result.getLast())) result.removeLast();
    return result;
  }

  static Plane inverse(Plane plane) {
    return new Plane(-plane.nx(), -plane.ny(), -plane.nz(), -plane.offset());
  }

  private static void add(List<Point> points, Point point) {
    if (!points.contains(point)) points.add(point);
  }

  private static void sort(List<Point> points, Point normal) {
    Point center = new Point(0, 0, 0);
    for (var point : points) center = center.add(point);
    center = center.scale(1.0 / points.size());
    Point axis = Math.abs(normal.x()) < .8 ? new Point(1, 0, 0) : new Point(0, 1, 0);
    axis = axis.subtract(normal.scale(axis.dot(normal) / normal.lengthSquared())).normalized();
    Point side = normal.normalized().cross(axis), c = center, a = axis;
    points.sort(
        java.util.Comparator.comparingDouble(
            point -> Math.atan2(point.subtract(c).dot(side), point.subtract(c).dot(a))));
  }

  static double volume(List<Face> faces) {
    if (faces.isEmpty()) return 0;
    Point center = new Point(0, 0, 0);
    int count = 0;
    for (var face : faces)
      for (var p : face.points()) {
        center = center.add(p);
        count++;
      }
    center = center.scale(1.0 / count);
    double volume = 0;
    for (var face : faces) {
      var p = face.points();
      for (int i = 1; i + 1 < p.size(); i++)
        volume +=
            Math.abs(
                    p.getFirst()
                        .subtract(center)
                        .dot(p.get(i).subtract(center).cross(p.get(i + 1).subtract(center))))
                / 6;
    }
    return volume;
  }
}
