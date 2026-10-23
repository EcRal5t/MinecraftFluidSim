package mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mmm.world.MovingBodyFluidCoupling;

/** Prescribed-body occupation, shared pore capacity and pressure incidence. */
final class EntityBodyCoupling {
  private final FluidDomain domain;
  private EntityBodyState source;
  private FixedGeometrySnapshot fixed;
  private long[] occupied;
  private double[][] bodyCells = new double[0][];
  private double[][] bodyFaceArea = new double[0][];
  private double[] faceArea;
  private int[] starts = new int[0], rows = new int[64];
  private double[] coefficients = new double[64], affine = new double[0];
  private int entries;
  private MovingBodyFluidCoupling.Result result = MovingBodyFluidCoupling.Result.NONE;

  EntityBodyCoupling(FluidDomain domain) {
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
    var target = new EntityBodyState(requests);
    result = MovingBodyFluidCoupling.Result.NONE;
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
      domain.cellMacStage.publishTransport(
          displacement.energy,
          displacement.momentum,
          displacement.gasTopology,
          next,
          () -> {
            domain.entityBodies = target;
            restoreGeometry(nextGeometry);
          });
    else {
      domain.entityBodies = target;
      restoreGeometry(nextGeometry);
      domain.markCellInventoryChanged();
    }
    result = MovingBodyFluidCoupling.Result.NONE;
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

  /** Local tangential shear uses the same wet body geometry and physical liquid viscosity. */
  void applyShear(float dt) {
    if (domain.entityBodies.bodies().isEmpty()) return;
    ensure();
    if (source.bodies().isEmpty() || domain.materialDefinitions == null) return;
    var graph = domain.pressureSystem.faces;
    double h = domain.config.cellSize();
    for (int b = 0; b < source.bodies().size(); b++) {
      var body = source.bodies().get(b);
      var wet = MovingBodyWetGeometry.capture(domain, body.bounds());
      if (!(wet.sample().wetSurfaceAreaSquareMetres() > 0)) continue;
      double[] target = {
        body.velocityXMetresPerSecond(),
        body.velocityYMetresPerSecond(),
        body.velocityZMetresPerSecond()
      };
      double[] impulse = new double[3];
      double work = 0;
      for (int axis = 0; axis < 3; axis++) {
        double area = wet.sample().wetSurfaceAreaSquareMetres() - wet.wetAreaByNormal()[axis];
        double total = 0;
        for (int face = graph.offsets[axis];
            face < (axis == 2 ? graph.faceCount : graph.offsets[axis + 1]);
            face++) total += bodyFaceArea[b][face];
        if (!(total > 0 && area > 0)) continue;
        float[] velocity =
            axis == 0
                ? domain.mac.velocityX
                : axis == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
        double[] masses =
            axis == 0
                ? domain.cellMacStage.massX
                : axis == 1 ? domain.cellMacStage.massY : domain.cellMacStage.massZ;
        for (int face = graph.offsets[axis];
            face < (axis == 2 ? graph.faceCount : graph.offsets[axis + 1]);
            face++) {
          if (!(bodyFaceArea[b][face] > 0)) continue;
          int local = graph.localFace[face],
              first = graph.firstCell[face],
              second = graph.secondCell[face];
          if (first < 0 || second < 0 || !(masses[local] > 0)) continue;
          double mu = 0;
          int samples = 0;
          if (domain.carrierPropertiesWorkspace.hasLiquid(first)) {
            mu += domain.carrierPropertiesWorkspace.viscosityPascalSeconds(first);
            samples++;
          }
          if (domain.carrierPropertiesWorkspace.hasLiquid(second)) {
            mu += domain.carrierPropertiesWorkspace.viscosityPascalSeconds(second);
            samples++;
          }
          if (samples == 0 || mu == 0) continue;
          double conductance = mu / samples * area / h * bodyFaceArea[b][face] / total;
          double before = velocity[local], blend = -Math.expm1(-dt * conductance / masses[local]);
          float after = (float) (before + blend * (target[axis] - before));
          double delta = (double) after - before;
          double delivered = masses[local] * delta;
          velocity[local] = after;
          impulse[axis] += delivered;
          work += target[axis] * delivered;
          domain.cellMacStage.addMechanicalHeatAtFace(
              axis, local, masses[local] * delta * (target[axis] - before - .5 * delta));
        }
      }
      var reaction =
          new MovingBodyFluidCoupling.BodyImpulse(
              body.bodyId(), -impulse[0], -impulse[1], -impulse[2]);
      result =
          result.plus(
              new MovingBodyFluidCoupling.Result(
                  1,
                  wet.sample().displacedVolumeCubicMetres(),
                  impulse[0],
                  impulse[1],
                  impulse[2],
                  work,
                  List.of(reaction)));
    }
  }

  void preparePressure(double datum) {
    if (domain.entityBodies.bodies().isEmpty()) {
      source = domain.entityBodies;
      starts = new int[1];
      entries = 0;
      return;
    }
    ensure();
    int contacts = 3 * source.bodies().size();
    starts = new int[contacts + 1];
    affine = new double[contacts];
    entries = 0;
    var graph = domain.pressureSystem.faces;
    double h = domain.config.cellSize();
    for (int b = 0; b < source.bodies().size(); b++)
      for (int axis = 0; axis < 3; axis++) {
        int contact = 3 * b + axis;
        starts[contact] = entries;
        for (int face = graph.offsets[axis];
            face < (axis == 2 ? graph.faceCount : graph.offsets[axis + 1]);
            face++) {
          double total = faceArea[face];
          if (total <= 0 || bodyFaceArea[b][face] <= 0) continue;
          int first = graph.firstCell[face], second = graph.secondCell[face];
          if (first < 0 || second < 0) continue;
          double geometric = domain.geometry.cutCells.openFaceField(axis)[graph.localFace[face]];
          double area = Math.min(geometric, total) * bodyFaceArea[b][face] / total,
              weight = area / h;
          graph.forEachSide(
              first, second, (r, w, fixed) -> add(contact, r, weight * w, fixed), ignored -> {});
          graph.forEachSide(
              second, first, (r, w, fixed) -> add(contact, r, -weight * w, fixed), ignored -> {});
        }
        if (datum != 0) {
          double sum = 0;
          for (int e = starts[contact]; e < entries; e++) sum += coefficients[e];
          affine[contact] += datum * sum;
        }
      }
    starts[contacts] = entries;
  }

  private void add(int contact, int row, double weight, double fixedPressure) {
    affine[contact] += weight * fixedPressure;
    if (row < 0 || weight == 0) return;
    for (int e = starts[contact]; e < entries; e++)
      if (rows[e] == row) {
        coefficients[e] += weight;
        return;
      }
    if (entries == rows.length) {
      rows = Arrays.copyOf(rows, 2 * entries);
      coefficients = Arrays.copyOf(coefficients, rows.length);
    }
    rows[entries] = row;
    coefficients[entries++] = weight;
  }

  void addDivergence(double[] rhs, double factor) {
    for (int b = 0; b < source.bodies().size(); b++) {
      var body = source.bodies().get(b);
      double[] velocity = {
        body.velocityXMetresPerSecond(),
        body.velocityYMetresPerSecond(),
        body.velocityZMetresPerSecond()
      };
      for (int axis = 0; axis < 3; axis++) {
        int c = 3 * b + axis;
        for (int e = starts[c]; e < starts[c + 1]; e++)
          rhs[rows[e]] += factor * coefficients[e] * velocity[axis];
      }
    }
  }

  void markGasRows(boolean[] active) {
    for (int e = 0; e < entries; e++)
      if (rows[e] >= domain.cellCount && coefficients[e] != 0)
        active[rows[e] - domain.cellCount] = true;
  }

  void finishPressure(double[] pressure, double dt) {
    var reactions = new ArrayList<MovingBodyFluidCoupling.BodyImpulse>();
    double[] impulse = new double[3];
    double work = 0, h = domain.config.cellSize(), h3 = h * h * h;
    for (int b = 0; b < source.bodies().size(); b++) {
      var body = source.bodies().get(b);
      double[] bodyImpulse = new double[3];
      double bodyWork = 0;
      double[] velocity = {
        body.velocityXMetresPerSecond(),
        body.velocityYMetresPerSecond(),
        body.velocityZMetresPerSecond()
      };
      for (int axis = 0; axis < 3; axis++) {
        int c = 3 * b + axis;
        double force = affine[c];
        for (int e = starts[c]; e < starts[c + 1]; e++)
          force += coefficients[e] * pressure[rows[e]];
        double reaction = -dt * h3 * force;
        impulse[axis] += reaction;
        bodyImpulse[axis] = -reaction;
        bodyWork += reaction * velocity[axis];
      }
      work += bodyWork;
      reactions.add(
          new MovingBodyFluidCoupling.BodyImpulse(
              body.bodyId(), bodyImpulse[0], bodyImpulse[1], bodyImpulse[2]));
      double total = 0;
      for (int cell = 0; cell < domain.cellCount; cell++)
        if (domain.liquidGrid.stockCount[cell] > 0) total += bodyCells[b][cell];
      if (total > 0)
        for (int cell = 0; cell < domain.cellCount; cell++)
          if (domain.liquidGrid.stockCount[cell] > 0 && bodyCells[b][cell] > 0)
            domain.mechanicalEnergy.recordExternalBodyWork(
                cell, bodyWork * bodyCells[b][cell] / total);
    }
    result =
        result.plus(
            new MovingBodyFluidCoupling.Result(
                source.bodies().size(), 0, impulse[0], impulse[1], impulse[2], work, reactions));
  }

  MovingBodyFluidCoupling.Result result() {
    return result;
  }
}
