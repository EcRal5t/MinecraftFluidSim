package mmm.fluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mmm.world.MovingBodyFluidCoupling.Bounds;

/** Physical PLIC intersection with an axis-aligned body and pressure integrals on its wet faces. */
final class MovingBodyWetGeometry {
  record Capture(
      FluidDomain.BodyFluidSample sample, double[] cellVolumes, double[] wetAreaByNormal) {}

  private record Point(double x, double y, double z) {
    Point add(Point p) {
      return new Point(x + p.x, y + p.y, z + p.z);
    }

    Point minus(Point p) {
      return new Point(x - p.x, y - p.y, z - p.z);
    }

    Point times(double s) {
      return new Point(x * s, y * s, z * s);
    }

    double dot(Point p) {
      return x * p.x + y * p.y + z * p.z;
    }

    Point cross(Point p) {
      return new Point(y * p.z - z * p.y, z * p.x - x * p.z, x * p.y - y * p.x);
    }
  }

  private record Plane(Point normal, double offset, int bodyFace) {}

  private record Face(Plane plane, List<Point> vertices) {}

  private record Moment(double volume, Point center) {}

  static Capture capture(FluidDomain domain, Bounds bounds) {
    domain.domainFlowFields.preparePhysicalState();
    double h = domain.config.cellSize(), h2 = h * h, h3 = h2 * h;
    var frame = domain.gasState.frame;
    var phase = frame.phase();
    double[] occupied = new double[domain.cellCount];
    double[] wetAreaByNormal = new double[3];
    double volume = 0, mass = 0, wetArea = 0, waterVolume = 0;
    Point center = new Point(0, 0, 0), velocity = center, force = center;
    int[] start = {
      Math.max(0, (int) Math.floor(bounds.minimumX() / h)),
      Math.max(0, (int) Math.floor(bounds.minimumY() / h)),
      Math.max(0, (int) Math.floor(bounds.minimumZ() / h))
    };
    int[] end = {
      Math.min(domain.sizeX - 1, (int) Math.ceil(bounds.maximumX() / h) - 1),
      Math.min(domain.sizeY - 1, (int) Math.ceil(bounds.maximumY() / h) - 1),
      Math.min(domain.sizeZ - 1, (int) Math.ceil(bounds.maximumZ() / h) - 1)
    };
    for (int z = start[2]; z <= end[2]; z++)
      for (int y = start[1]; y <= end[1]; y++)
        for (int x = start[0]; x <= end[0]; x++) {
          int cell = x + domain.sizeX * (y + domain.sizeY * z);
          if (domain.liquidGrid.cellStockVolume[cell] <= 0) continue;
          double density = domain.liquidGrid.cellDensity[cell];
          double wetVolume = 0, wetMoment = 0;
          for (int micro = 0; micro < 8; micro++) {
            var p = phase.componentPlane(cell, micro);
            if (p == null || p.fraction() <= 0) continue;
            double scale = p.porosity();
            double v = phase.liquidFractionExact(cell, micro) * .125 * scale;
            wetVolume += v;
            wetMoment +=
                scale
                    * (p.fraction() >= 1
                        ? .125 * (.5 * (micro >> 1 & 1) + .25)
                        : MicroBoxPlaneVolume.verticalMoment(
                            p.nx(), p.ny(), p.nz(), p.offset(), micro));
          }
          double liquidCenterY = wetVolume > 0 ? wetMoment / wetVolume : .5;
          double pressure =
              domain.pressureSystem.liquidPressureAt(
                  cell, ~domain.geometry.cutCells.mask(cell) & 255);
          List<Plane> body = bodyPlanes(bounds, h, x, y, z);
          var waterLayers = new java.util.HashMap<Integer, List<GasLiquidContactGeometry.Layer>>();
          for (int micro = 0; micro < 8; micro++) {
            if ((domain.geometry.cutCells.mask(cell) & 1 << micro) != 0) continue;
            var p = phase.componentPlane(cell, micro);
            if (p == null || p.fraction() <= 0) continue;
            List<Face> faces = box(micro);
            if (p.fraction() < 1)
              faces = clip(faces, new Plane(new Point(p.nx(), p.ny(), p.nz()), p.offset(), -1));
            for (var plane : body) faces = clip(faces, plane);
            Moment moment = moment(faces);
            if (!(moment.volume() > 0)) continue;
            double overlap = moment.volume() * p.porosity() * h3;
            var layers = waterLayers.get(p.support());
            if (layers == null) {
              var stocks = new ArrayList<mmm.inventory.LiquidCellInventory.Stock>();
              for (var stock : domain.liquidCells.stocksInCell(cell))
                if (stock.key().supportMask() == p.support()) stocks.add(stock);
              boolean hasWater = stocks.stream().anyMatch(stock -> isWater(domain, stock.key().material()));
              layers = hasWater ? GasLiquidContactGeometry.layers(domain, frame, cell, p.support(), stocks)
                  .stream().filter(layer -> isWater(domain, layer.stock().key().material())).toList() : List.of();
              waterLayers.put(p.support(), layers);
            }
            for (var layer : layers) {
              var selected = faces;
              if (layer.lower() > 0) selected = clip(selected, new Plane(new Point(0, -1, 0), -layer.lower(), -1));
              if (layer.upper() < 1) selected = clip(selected, new Plane(new Point(0, 1, 0), layer.upper(), -1));
              waterVolume += selected == faces ? overlap : moment(selected).volume() * p.porosity() * h3;
            }
            occupied[cell] += overlap / h3;
            volume += overlap;
            mass += overlap * density;
            Point c =
                new Point(
                    (x + moment.center.x) * h,
                    (y + moment.center.y) * h,
                    (z + moment.center.z) * h);
            center = center.add(c.times(overlap));
            var flow = domain.sampleVelocity((float) c.x, (float) c.y, (float) c.z);
            velocity = velocity.add(new Point(flow.x(), flow.y(), flow.z()).times(overlap));
            for (var face : faces)
              if (face.plane.bodyFace >= 0) {
                var polygon = face.vertices;
                Point first = polygon.getFirst();
                for (int i = 1; i + 1 < polygon.size(); i++) {
                  Point a = polygon.get(i), b = polygon.get(i + 1);
                  Point cross = a.minus(first).cross(b.minus(first));
                  double area = .5 * Math.sqrt(cross.dot(cross)) * h2 * p.porosity();
                  double py = (first.y + a.y + b.y) / 3;
                  double load =
                      pressure + density * domain.config.gravity() * h * (liquidCenterY - py);
                  wetArea += area;
                  wetAreaByNormal[face.plane.bodyFace / 2] += area;
                  force = force.add(face.plane.normal.times(-load * area));
                }
              }
          }
        }
    if (!(volume > 0))
      return new Capture(FluidDomain.BodyFluidSample.EMPTY, occupied, wetAreaByNormal);
    force = force.add(gasSurfaceForce(domain, bounds, start, end));
    center = center.times(1 / volume);
    velocity = velocity.times(1 / volume);
    return new Capture(
        new FluidDomain.BodyFluidSample(
            volume,
            center.x,
            center.y,
            center.z,
            velocity.x,
            velocity.y,
            velocity.z,
            wetArea,
            mass,
            force.x,
            force.y,
            force.z,
            waterVolume),
        occupied,
        wetAreaByNormal);
  }

  private static boolean isWater(FluidDomain domain, int material) {
    return domain.materialRegistry == null ? material == 0
        : domain.materialRegistry.material(material).id().equals(BuiltinFluidMaterials.WATER.id());
  }

  /**
   * Dry body faces carry their own cavity pressure; uniform gas pressure cancels over a closed
   * body.
   */
  private static Point gasSurfaceForce(FluidDomain domain, Bounds bounds, int[] start, int[] end) {
    var frame = domain.gasState.frame;
    double h = domain.config.cellSize();
    Point force = new Point(0, 0, 0);
    for (int z = start[2]; z <= end[2]; z++)
      for (int y = start[1]; y <= end[1]; y++)
        for (int x = start[0]; x <= end[0]; x++) {
          int cell = x + domain.sizeX * (y + domain.sizeY * z);
          var body = bodyPlanes(bounds, h, x, y, z);
          for (int micro = 0; micro < 8; micro++) {
            int id = 8 * cell + micro, node = frame.nodeOfPiece(id);
            if (node <= 0) continue;
            var piece = frame.geometry().piece(id);
            if (piece == null) continue;
            double pressure = domain.pressureBoundaryView.gasPressureOfNode(node);
            if (pressure == 0) continue;
            var faces = box(micro);
            var dry = piece.dryPlane();
            if (dry != null)
              faces =
                  clip(
                      faces,
                      new Plane(new Point(-dry.nx(), -dry.ny(), -dry.nz()), -dry.threshold(), -1));
            for (var plane : body) faces = clip(faces, plane);
            if (!(moment(faces).volume() > 0)) continue;
            var p = frame.phase().componentPlane(cell, micro);
            double porosity = p == null ? 1 : p.porosity();
            for (var face : faces)
              if (face.plane.bodyFace >= 0) {
                var polygon = face.vertices;
                Point first = polygon.getFirst();
                for (int i = 1; i + 1 < polygon.size(); i++) {
                  Point cross = polygon.get(i).minus(first).cross(polygon.get(i + 1).minus(first));
                  double area = .5 * Math.sqrt(cross.dot(cross)) * h * h * porosity;
                  force = force.add(face.plane.normal.times(-pressure * area));
                }
              }
          }
        }
    return force;
  }

  private static List<Plane> bodyPlanes(Bounds b, double h, int x, int y, int z) {
    return List.of(
        new Plane(new Point(-1, 0, 0), x - b.minimumX() / h, 0),
        new Plane(new Point(1, 0, 0), b.maximumX() / h - x, 1),
        new Plane(new Point(0, -1, 0), y - b.minimumY() / h, 2),
        new Plane(new Point(0, 1, 0), b.maximumY() / h - y, 3),
        new Plane(new Point(0, 0, -1), z - b.minimumZ() / h, 4),
        new Plane(new Point(0, 0, 1), b.maximumZ() / h - z, 5));
  }

  private static List<Face> box(int micro) {
    double x = .5 * (micro & 1), y = .5 * (micro >> 1 & 1), z = .5 * (micro >> 2 & 1);
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
    var result = new ArrayList<Face>();
    for (int face = 0; face < 6; face++) {
      int axis = face / 2;
      double sign = (face & 1) == 0 ? -1 : 1;
      double coordinate = (axis == 0 ? x : axis == 1 ? y : z) + ((face & 1) == 1 ? .5 : 0);
      var plane =
          new Plane(
              new Point(axis == 0 ? sign : 0, axis == 1 ? sign : 0, axis == 2 ? sign : 0),
              sign * coordinate,
              -1);
      var points = new ArrayList<Point>();
      for (int id : ids[face]) points.add(p[id]);
      result.add(new Face(plane, points));
    }
    return result;
  }

  private static List<Face> clip(List<Face> faces, Plane plane) {
    var result = new ArrayList<Face>();
    var cap = new ArrayList<Point>();
    for (var face : faces) {
      var points = new ArrayList<Point>();
      var previous = face.vertices.getLast();
      double pd = plane.normal.dot(previous) - plane.offset;
      for (var current : face.vertices) {
        double cd = plane.normal.dot(current) - plane.offset;
        if ((pd > 0) != (cd > 0)) {
          var intersection = previous.add(current.minus(previous).times(pd / (pd - cd)));
          if (!points.contains(intersection)) points.add(intersection);
          if (!cap.contains(intersection)) cap.add(intersection);
        }
        if (cd <= 0 && !points.contains(current)) points.add(current);
        previous = current;
        pd = cd;
      }
      if (points.size() >= 3) {
        boolean coplanar = plane.bodyFace >= 0;
        for (var point : points) coplanar &= plane.normal.dot(point) == plane.offset;
        result.add(new Face(coplanar ? plane : face.plane, points));
      }
    }
    if (cap.size() >= 3) {
      Point c = new Point(0, 0, 0);
      for (var p : cap) c = c.add(p);
      c = c.times(1.0 / cap.size());
      Point n = plane.normal, axis = Math.abs(n.x) < .8 ? new Point(1, 0, 0) : new Point(0, 1, 0);
      axis = axis.minus(n.times(axis.dot(n) / n.dot(n)));
      Point side = n.cross(axis), center = c, u = axis;
      cap.sort(
          Comparator.comparingDouble(
              p -> Math.atan2(p.minus(center).dot(side), p.minus(center).dot(u))));
      result.add(new Face(plane, cap));
    }
    return result;
  }

  private static Moment moment(List<Face> faces) {
    Point origin = new Point(0, 0, 0);
    int count = 0;
    for (var face : faces)
      for (var p : face.vertices) {
        origin = origin.add(p);
        count++;
      }
    if (count == 0) return new Moment(0, origin);
    origin = origin.times(1.0 / count);
    double volume = 0;
    Point center = new Point(0, 0, 0);
    for (var face : faces) {
      var p = face.vertices;
      for (int i = 1; i + 1 < p.size(); i++) {
        double v =
            Math.abs(
                    p.getFirst()
                        .minus(origin)
                        .dot(p.get(i).minus(origin).cross(p.get(i + 1).minus(origin))))
                / 6;
        volume += v;
        center =
            center.add(origin.add(p.getFirst()).add(p.get(i)).add(p.get(i + 1)).times(v * .25));
      }
    }
    return new Moment(volume, volume > 0 ? center.times(1 / volume) : center);
  }
}
