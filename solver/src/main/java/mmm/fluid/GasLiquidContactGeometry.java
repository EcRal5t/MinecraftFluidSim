package mmm.fluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import mmm.inventory.LiquidCellInventory;

/** Material-resolved gas contacts on the committed physical PLIC and fixed open microboxes. */
final class GasLiquidContactGeometry {
  private GasLiquidContactGeometry() {}

  record Contact(LiquidCellInventory.Key key, int node, double areaM2, double liquidVolumeM3) {}

  private record Layer(LiquidCellInventory.Stock stock, double lower, double upper) {}

  private record Owner(LiquidCellInventory.Key key, int node) implements Comparable<Owner> {
    public int compareTo(Owner other) {
      int order = key.compareTo(other.key);
      return order == 0 ? Integer.compare(node, other.node) : order;
    }
  }

  private record Point(double x, double y, double z) {
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

  /** Caller prepares physical state first; capture reads the same immutable frame throughout. */
  static List<Contact> capture(FluidDomain domain) {
    var frame = domain.gasState.frame;
    if (frame == null) return List.of();
    var areas = new TreeMap<Owner, Double>();
    var volumes = new TreeMap<LiquidCellInventory.Key, Double>();
    double h = domain.config.cellSize();
    for (int cell = 0; cell < domain.cellCount; cell++) {
      var row = domain.liquidCells.stocksInCell(cell);
      if (row.isEmpty()) continue;
      for (int support :
          LiquidCellInventory.connectedSupports(frame.geometry().collisionMask(cell))) {
        var stocks = new ArrayList<LiquidCellInventory.Stock>();
        for (var stock : row)
          if (stock.key().supportMask() == support) {
            stocks.add(stock);
            volumes.put(stock.key(), stock.volumeFraction() * h * h * h);
          }
        if (stocks.isEmpty()) continue;
        var layers = layers(domain, frame, cell, support, stocks);
        for (int bits = support; bits != 0; bits &= bits - 1) {
          int micro = Integer.numberOfTrailingZeros(bits);
          double fraction = frame.liquidFractionExact(cell, micro);
          if (!(fraction > 0)) continue;
          var p = frame.phase().componentPlane(cell, micro);
          if (fraction < 1) {
            int node = frame.topology().nodeOfComponent(cell, support);
            if (node >= 0) add(areas, layers, node, section(micro, p), h);
          }
          for (int axis = 0; axis < 3; axis++)
            for (int sign = -1; sign <= 1; sign += 2) {
              int peer = neighbor(domain, cell, micro, axis, sign);
              int node = peer < 0 ? frame.sealedExterior() ? -1 : 0 : frame.nodeOfPiece(peer);
              if (node < 0) continue;
              double coordinate = .5 * (micro >> axis & 1) + (sign > 0 ? .5 : 0);
              var polygon = face(micro, axis, coordinate);
              if (fraction < 1)
                polygon = clip(polygon, new Point(p.nx(), p.ny(), p.nz()), p.offset());
              if (peer >= 0) {
                int peerCell = peer / 8, peerMicro = peer & 7;
                if ((frame.geometry().collisionMask(peerCell) & 1 << peerMicro) != 0) continue;
                double peerLiquid = frame.liquidFractionExact(peerCell, peerMicro);
                if (peerLiquid >= 1) continue;
                if (peerLiquid > 0) {
                  var dry = frame.phase().componentPlane(peerCell, peerMicro);
                  int shift = peerCell == cell ? 0 : sign;
                  double offset = dry.offset() + shift * dry.normal(axis);
                  polygon = clip(polygon, new Point(-dry.nx(), -dry.ny(), -dry.nz()), -offset);
                }
              }
              add(areas, layers, node, polygon, h);
            }
        }
      }
    }
    var result = new ArrayList<Contact>(areas.size());
    for (var entry : areas.entrySet())
      if (entry.getValue() > 0)
        result.add(
            new Contact(
                entry.getKey().key(),
                entry.getKey().node(),
                entry.getValue(),
                volumes.get(entry.getKey().key())));
    return List.copyOf(result);
  }

  private static List<Layer> layers(
      FluidDomain domain,
      GasRuntimeGeometry frame,
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
    double geometric = volumeBelow(frame, cell, support, 1), cumulative = 0, lower = 0;
    var result = new ArrayList<Layer>();
    for (int i = 0; i < stocks.size(); i++) {
      var stock = stocks.get(i);
      cumulative += stock.volumeFraction();
      double upper = 1;
      if (i + 1 < stocks.size()) {
        double low = 0, high = 1, target = geometric * cumulative / total;
        for (int iteration = 0; iteration < 24; iteration++) {
          double middle = .5 * (low + high);
          if (volumeBelow(frame, cell, support, middle) < target) low = middle;
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
      GasRuntimeGeometry frame, int cell, int support, double height) {
    double volume = 0;
    for (int bits = support; bits != 0; bits &= bits - 1) {
      int micro = Integer.numberOfTrailingZeros(bits);
      var p = frame.phase().componentPlane(cell, micro);
      if (p == null || p.fraction() <= 0) continue;
      var planes = new ArrayList<GasGeometryOverlap.HalfSpace>(2);
      planes.add(new GasGeometryOverlap.HalfSpace(0, -1, 0, -height));
      if (p.fraction() < 1)
        planes.add(new GasGeometryOverlap.HalfSpace(-p.nx(), -p.ny(), -p.nz(), -p.offset()));
      volume += GasGeometryOverlap.normalizedVolume(micro, planes);
    }
    return volume;
  }

  private static void add(
      TreeMap<Owner, Double> areas, List<Layer> layers, int node, List<Point> polygon, double h) {
    if (polygon.size() < 3) return;
    for (var layer : layers) {
      var selected = polygon;
      if (layer.lower() > 0) selected = clip(selected, new Point(0, -1, 0), -layer.lower());
      if (layer.upper() < 1) selected = clip(selected, new Point(0, 1, 0), layer.upper());
      double area = area(selected) * h * h;
      if (area > 0) areas.merge(new Owner(layer.stock().key(), node), area, Double::sum);
    }
  }

  private static int neighbor(FluidDomain domain, int cell, int micro, int axis, int sign) {
    int bit = micro >> axis & 1;
    if (bit == 0 && sign == 1 || bit == 1 && sign == -1) return 8 * cell + (micro ^ 1 << axis);
    int coordinate =
        axis == 0
            ? cell % domain.sizeX
            : axis == 1 ? cell / domain.sizeX % domain.sizeY : cell / (domain.sizeX * domain.sizeY);
    int size = axis == 0 ? domain.sizeX : axis == 1 ? domain.sizeY : domain.sizeZ;
    if (coordinate + sign < 0 || coordinate + sign >= size) return -1;
    int stride = axis == 0 ? 1 : axis == 1 ? domain.sizeX : domain.sizeX * domain.sizeY;
    return 8 * (cell + sign * stride) + (micro ^ 1 << axis);
  }

  private static List<Point> face(int micro, int axis, double coordinate) {
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
    return result;
  }

  private static List<Point> section(int micro, PlicComponentGeometry.Plane plane) {
    var points = new ArrayList<Point>(6);
    var n = new Point(plane.nx(), plane.ny(), plane.nz());
    for (int axis = 0; axis < 3; axis++)
      for (int a = 0; a < 2; a++)
        for (int b = 0; b < 2; b++) {
          double[] p = {.5 * (micro & 1), .5 * (micro >> 1 & 1), .5 * (micro >> 2 & 1)};
          p[(axis + 1) % 3] += .5 * a;
          p[(axis + 2) % 3] += .5 * b;
          var first = new Point(p[0], p[1], p[2]);
          p[axis] += .5;
          var second = new Point(p[0], p[1], p[2]);
          double d1 = n.dot(first) - plane.offset(), d2 = n.dot(second) - plane.offset();
          if (d1 == 0 && !points.contains(first)) points.add(first);
          if (d2 == 0 && !points.contains(second)) points.add(second);
          if (d1 * d2 < 0) {
            var intersection = first.plus(second.minus(first).times(d1 / (d1 - d2)));
            if (!points.contains(intersection)) points.add(intersection);
          }
        }
    if (points.size() < 3) return List.of();
    Point center = new Point(0, 0, 0);
    for (var point : points) center = center.plus(point);
    center = center.times(1.0 / points.size());
    Point axis = Math.abs(n.x()) < .8 ? new Point(1, 0, 0) : new Point(0, 1, 0);
    axis = axis.minus(n.times(axis.dot(n) / n.dot(n)));
    Point side = n.cross(axis), c = center, u = axis;
    points.sort(
        Comparator.comparingDouble(p -> Math.atan2(p.minus(c).dot(side), p.minus(c).dot(u))));
    return points;
  }

  private static List<Point> clip(List<Point> polygon, Point n, double offset) {
    if (polygon.isEmpty()) return polygon;
    var result = new ArrayList<Point>();
    var previous = polygon.getLast();
    double pd = n.dot(previous) - offset;
    for (var current : polygon) {
      double cd = n.dot(current) - offset;
      if ((pd > 0) != (cd > 0))
        result.add(previous.plus(current.minus(previous).times(pd / (pd - cd))));
      if (cd <= 0) result.add(current);
      previous = current;
      pd = cd;
    }
    return result;
  }

  private static double area(List<Point> polygon) {
    if (polygon.size() < 3) return 0;
    var first = polygon.getFirst();
    Point sum = new Point(0, 0, 0);
    for (int i = 1; i + 1 < polygon.size(); i++)
      sum = sum.plus(polygon.get(i).minus(first).cross(polygon.get(i + 1).minus(first)));
    return .5 * Math.sqrt(sum.dot(sum));
  }
}
