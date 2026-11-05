package mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mmm.world.MovingBodyFluidCoupling;

/** Prescribed-body geometry and pore capacity for host inventory edits. */
final class EntityBodyCoupling {
  private final HostFluidState domain;
  private EntityBodyState source;
  private FixedGeometrySnapshot fixed;
  private long[] occupied;
  private double[][] bodyCells = new double[0][];
  private double[][] bodyFaceArea = new double[0][];
  private double[] faceArea;

  EntityBodyCoupling(HostFluidState domain) {
    this.domain = domain;
  }

  void ensure() {
    if (source == domain.entityBodies && fixed == domain.geometry.cutCells.snapshot()) return;
    rebuild(domain.entityBodies);
  }

  private void rebuild(EntityBodyState state) {
    source = state;
    fixed = domain.geometry.cutCells.snapshot();
    occupied = new long[domain.cellCount];
    bodyCells = new double[state.bodies().size()][domain.cellCount];
    double h = domain.config.cellSize();
    for (int b = 0; b < state.bodies().size(); b++) {
      var bounds = state.bodies().get(b).bounds();
      int firstX = Math.max(0, (int) Math.floor(bounds.minimumX() / h)),
          lastX = Math.min(domain.sizeX - 1, (int) Math.ceil(bounds.maximumX() / h) - 1);
      int firstY = Math.max(0, (int) Math.floor(bounds.minimumY() / h)),
          lastY = Math.min(domain.sizeY - 1, (int) Math.ceil(bounds.maximumY() / h) - 1);
      int firstZ = Math.max(0, (int) Math.floor(bounds.minimumZ() / h)),
          lastZ = Math.min(domain.sizeZ - 1, (int) Math.ceil(bounds.maximumZ() / h) - 1);
      for (int z = firstZ; z <= lastZ; z++)
        for (int y = firstY; y <= lastY; y++)
          for (int x = firstX; x <= lastX; x++) {
            int cell = x + domain.sizeX * (y + domain.sizeY * z);
            double volume = 0;
            for (int micro = 0; micro < 8; micro++)
              if ((fixed.mask(cell) & 1 << micro) == 0) {
                double dx =
                    Math.max(
                        0,
                        Math.min(bounds.maximumX() / h, x + .5 * (micro & 1) + .5)
                            - Math.max(bounds.minimumX() / h, x + .5 * (micro & 1)));
                double dy =
                    Math.max(
                        0,
                        Math.min(bounds.maximumY() / h, y + .5 * (micro >> 1 & 1) + .5)
                            - Math.max(bounds.minimumY() / h, y + .5 * (micro >> 1 & 1)));
                double dz =
                    Math.max(
                        0,
                        Math.min(bounds.maximumZ() / h, z + .5 * (micro >> 2 & 1) + .5)
                            - Math.max(bounds.minimumZ() / h, z + .5 * (micro >> 2 & 1)));
                volume += dx * dy * dz;
              }
            bodyCells[b][cell] = volume;
          }
    }
    for (int cell = 0; cell < domain.cellCount; cell++) {
      double sum = 0;
      int contributors = 0;
      for (var body : bodyCells) {
        sum += body[cell];
        if (body[cell] > 0) contributors++;
      }
      if (sum == 0) continue;
      if (contributors == 1) {
        occupied[cell] = Math.round(sum * (1L << 24));
        continue;
      }
      int x = cell % domain.sizeX,
          y = cell / domain.sizeX % domain.sizeY,
          z = cell / (domain.sizeX * domain.sizeY);
      double union = 0;
      for (int micro = 0; micro < 8; micro++)
        if ((fixed.mask(cell) & 1 << micro) == 0) {
          double[] low = {
            x + .5 * (micro & 1), y + .5 * (micro >> 1 & 1), z + .5 * (micro >> 2 & 1)
          };
          var boxes = new ArrayList<double[]>();
          for (int body = 0; body < bodyCells.length; body++)
            if (bodyCells[body][cell] > 0) {
              var b = state.bodies().get(body).bounds();
              double[] clipped = {
                Math.max(low[0], b.minimumX() / h),
                Math.max(low[1], b.minimumY() / h),
                Math.max(low[2], b.minimumZ() / h),
                Math.min(low[0] + .5, b.maximumX() / h),
                Math.min(low[1] + .5, b.maximumY() / h),
                Math.min(low[2] + .5, b.maximumZ() / h)
              };
              if (clipped[3] > clipped[0] && clipped[4] > clipped[1] && clipped[5] > clipped[2]) {
                boolean duplicate = false;
                for (var box : boxes)
                  if (Arrays.equals(box, clipped)) {
                    duplicate = true;
                    break;
                  }
                if (!duplicate) boxes.add(clipped);
              }
            }
          union += unionVolume(boxes);
        }
      occupied[cell] = Math.round(union * (1L << 24));
      for (var body : bodyCells) body[cell] *= union / sum;
    }
    prepareFaces();
  }

  private static double unionVolume(List<double[]> boxes) {
    if (boxes.isEmpty()) return 0;
    if (boxes.size() == 1) {
      var b = boxes.getFirst();
      return (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
    }
    double[] xs = new double[2 * boxes.size()], ys = new double[xs.length];
    int index = 0;
    for (var b : boxes) {
      xs[index] = b[0];
      ys[index++] = b[1];
      xs[index] = b[3];
      ys[index++] = b[4];
    }
    Arrays.sort(xs);
    Arrays.sort(ys);
    double volume = 0;
    for (int xi = 1; xi < xs.length; xi++)
      if (xs[xi] > xs[xi - 1])
        for (int yi = 1; yi < ys.length; yi++)
          if (ys[yi] > ys[yi - 1]) {
            var intervals = new ArrayList<double[]>();
            for (var b : boxes)
              if (b[0] <= xs[xi - 1] && b[3] >= xs[xi] && b[1] <= ys[yi - 1] && b[4] >= ys[yi])
                intervals.add(new double[] {b[2], b[5]});
            intervals.sort(java.util.Comparator.comparingDouble(b -> b[0]));
            double length = 0, end = Double.NEGATIVE_INFINITY;
            for (var interval : intervals) {
              if (interval[1] > end) {
                length += interval[1] - Math.max(end, interval[0]);
                end = interval[1];
              }
            }
            volume += (xs[xi] - xs[xi - 1]) * (ys[yi] - ys[yi - 1]) * length;
          }
    return volume;
  }

  private void prepareFaces() {
    var graph = domain.pressureSystem.faces;
    faceArea = new double[graph.faceCount];
    bodyFaceArea = new double[bodyCells.length][graph.faceCount];
    for (int b = 0; b < bodyCells.length; b++)
      for (int cell = 0; cell < domain.cellCount; cell++) {
        double volume = bodyCells[b][cell];
        if (!(volume > 0)) continue;
        int x = cell % domain.sizeX,
            y = cell / domain.sizeX % domain.sizeY,
            z = cell / (domain.sizeX * domain.sizeY);
        for (int axis = 0; axis < 3; axis++) {
          int first =
              graph.offsets[axis]
                  + (axis == 0
                      ? domain.domainFlowFields.xFaceIndex(x, y, z)
                      : axis == 1
                          ? domain.domainFlowFields.yFaceIndex(x, y, z)
                          : domain.domainFlowFields.zFaceIndex(x, y, z));
          int second =
              first + (axis == 0 ? 1 : axis == 1 ? domain.sizeX : domain.sizeX * domain.sizeY);
          double a = domain.geometry.cutCells.openFaceField(axis)[graph.localFace[first]],
              c = domain.geometry.cutCells.openFaceField(axis)[graph.localFace[second]],
              sum = a + c;
          if (sum == 0) continue;
          bodyFaceArea[b][first] += volume * a / sum;
          bodyFaceArea[b][second] += volume * c / sum;
        }
      }
    for (int face = 0; face < faceArea.length; face++)
      for (var body : bodyFaceArea) faceArea[face] += body[face];
  }

  /** Adds occupation to a caller-owned capacity array (all callers supply fresh copies). */
  long[] addTo(long[] granular) {
    if (domain.entityBodies.bodies().isEmpty()) return granular;
    ensure();
    for (int cell = 0; cell < granular.length; cell++)
      granular[cell] = Math.addExact(granular[cell], occupied[cell]);
    return granular;
  }

  long occupied(int cell) {
    if (domain.entityBodies.bodies().isEmpty()) return 0;
    ensure();
    return occupied[cell];
  }

  double area(int face) {
    if (domain.entityBodies.bodies().isEmpty()) return 0;
    ensure();
    return faceArea[face];
  }

  boolean active() {
    if (domain.entityBodies.bodies().isEmpty()) return false;
    ensure();
    return !source.bodies().isEmpty();
  }

  void replace(List<MovingBodyFluidCoupling.Request> requests) {
    var accepted = new ArrayList<MovingBodyFluidCoupling.Request>();
    var retained = new java.util.HashSet<java.util.UUID>();
    for(var old:domain.entityBodies.bodies())if(!domain.activityGroups.bodyAvailable(old.bounds())) {
      accepted.add(old);retained.add(old.bodyId());
    }
    for(var request:requests)if(!retained.contains(request.bodyId())&&domain.activityGroups.bodyAvailable(request.bounds()))accepted.add(request);
    var target = new EntityBodyState(accepted);
    if (target.equals(domain.entityBodies)) return;
    domain.domainFlowFields.preparePhysicalState();
    ensure();
    var previous = domain.occupiedSolidSvq();
    var old = domain.entityBodies;
    var previousGeometry = geometry();
    Geometry nextGeometry;
    long[] next;
    try {
      domain.entityBodies = target;
      rebuild(target);
      next = domain.occupiedSolidSvq();
      nextGeometry = geometry();
    } finally {
      domain.entityBodies = old;
      restoreGeometry(previousGeometry);
    }
    var displacement = GranularLiquidDisplacement.prepareExternal(domain, previous, next);
    if (displacement != null)
      GranularLiquidDisplacement.publish(domain, displacement,
          () -> {
            domain.entityBodies = target;
            restoreGeometry(nextGeometry);
          });
    else {
      domain.entityBodies = target;
      restoreGeometry(nextGeometry);
      domain.markCellInventoryChanged();
    }
  }

  /** Geometry is immutable between pose changes; retain both candidates through displacement planning. */
  private record Geometry(
      EntityBodyState source,
      FixedGeometrySnapshot fixed,
      long[] occupied,
      double[][] bodyCells,
      double[][] bodyFaceArea,
      double[] faceArea) {}

  private Geometry geometry() {
    return new Geometry(source, fixed, occupied, bodyCells, bodyFaceArea, faceArea);
  }

  private void restoreGeometry(Geometry geometry) {
    source = geometry.source();
    fixed = geometry.fixed();
    occupied = geometry.occupied();
    bodyCells = geometry.bodyCells();
    bodyFaceArea = geometry.bodyFaceArea();
    faceArea = geometry.faceArea();
  }

}
