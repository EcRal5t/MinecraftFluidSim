package mmm.fluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mmm.inventory.LiquidCellInventory;

/** Material layers and clipped faces on committed physical PLIC geometry. */
final class GasLiquidContactGeometry {
  private GasLiquidContactGeometry() {}
  record Layer(LiquidCellInventory.Stock stock, double lower, double upper) {}

  record Point(double x, double y, double z) {
    Point minus(Point p) {
      return new Point(x - p.x, y - p.y, z - p.z);
    }

    Point plus(Point p) {
      return new Point(x + p.x, y + p.y, z + p.z);
    }

    Point times(double t) {
      return new Point(x * t, y * t, z * t);
    }

    double dot(Point p) {
      return x * p.x + y * p.y + z * p.z;
    }

    Point cross(Point p) {
      return new Point(y * p.z - z * p.y, z * p.x - x * p.z, x * p.y - y * p.x);
    }
  }

  static List<Layer> layers(
      HostFluidState domain,
      FlipPressurePhaseGeometry phase,
      int cell,
      int support,
      List<LiquidCellInventory.Stock> stocks) {
    if (stocks.size() == 1) return List.of(new Layer(stocks.getFirst(), 0, 1));
    stocks.sort(
        Comparator.<LiquidCellInventory.Stock>comparingDouble(
                s -> -domain.materialRegistry.material(s.key().material()).referenceDensity())
            .thenComparingInt(s -> s.key().material()));
    double total = 0;
    for (var stock : stocks) total += stock.volumeFraction();
    double geometric = volumeBelow(phase, cell, support, 1), cumulative = 0, lower = 0;
    var result = new ArrayList<Layer>();
    for (int i = 0; i < stocks.size(); i++) {
      var stock = stocks.get(i);
      cumulative += stock.volumeFraction();
      double upper = 1;
      if (i + 1 < stocks.size()) {
        double low = 0, high = 1, target = geometric * cumulative / total;
        for (int iteration = 0; iteration < 24; iteration++) {
          double middle = .5 * (low + high);
          if (volumeBelow(phase, cell, support, middle) < target) low = middle;
          else high = middle;
        }
        upper = .5 * (low + high);
      }
      result.add(new Layer(stock, lower, upper));
      lower = upper;
    }
    return result;
  }

  private static double volumeBelow(
      FlipPressurePhaseGeometry phase, int cell, int support, double height) {
    double volume = 0;
    for (int bits = support; bits != 0; bits &= bits - 1) {
      int micro = Integer.numberOfTrailingZeros(bits);
      var p = phase.componentPlane(cell, micro);
      if (p == null || p.fraction() <= 0) continue;
      var planes = new ArrayList<GasGeometryOverlap.HalfSpace>(2);
      planes.add(new GasGeometryOverlap.HalfSpace(0, -1, 0, -height));
      if (p.fraction() < 1)
        planes.add(new GasGeometryOverlap.HalfSpace(-p.nx(), -p.ny(), -p.nz(), -p.offset()));
      volume += GasGeometryOverlap.normalizedVolume(micro, planes);
    }
    return volume;
  }

  static List<Point> face(int micro, int axis, double coordinate) {
    int a = (axis + 1) % 3, b = (axis + 2) % 3;
    double[] p = {.5 * (micro & 1), .5 * (micro >> 1 & 1), .5 * (micro >> 2 & 1)};
    p[axis] = coordinate;
    var result = new ArrayList<Point>(4);
    for (int corner : new int[] {0, 1, 3, 2}) {
      double[] q = p.clone();
      q[a] += .5 * (corner & 1);
      q[b] += .5 * (corner >> 1 & 1);
      result.add(new Point(q[0], q[1], q[2]));
    }
    return List.copyOf(result);
  }

  static List<Point> clip(List<Point> polygon, Point n, double offset) {
    if (polygon.isEmpty()) return polygon;
    int inside = 0;
    for (var point : polygon) if (n.dot(point) <= offset) inside++;
    if (inside == polygon.size()) return polygon;
    if (inside == 0) return List.of();
    var result = new ArrayList<Point>();
    var previous = polygon.getLast();
    double pd = n.dot(previous) - offset;
    for (var current : polygon) {
      double cd = n.dot(current) - offset;
      if ((pd > 0) != (cd > 0))
        result.add(interpolate(previous, current, pd / (pd - cd)));
      if (cd <= 0) result.add(current);
      previous = current;
      pd = cd;
    }
    return result;
  }

  static double area(List<Point> polygon) {
    if (polygon.size() < 3) return 0;
    var first = polygon.getFirst();
    double x = 0, y = 0, z = 0;
    for (int i = 1; i + 1 < polygon.size(); i++) {
      var a = polygon.get(i); var b = polygon.get(i + 1);
      double ax=a.x-first.x, ay=a.y-first.y, az=a.z-first.z;
      double bx=b.x-first.x, by=b.y-first.y, bz=b.z-first.z;
      x+=ay*bz-az*by; y+=az*bx-ax*bz; z+=ax*by-ay*bx;
    }
    return .5 * Math.sqrt(x*x+y*y+z*z);
  }

  private static Point interpolate(Point a, Point b, double t) {
    return new Point(a.x+(b.x-a.x)*t, a.y+(b.y-a.y)*t, a.z+(b.z-a.z)*t);
  }
}
