package mmm.fluid;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CPU-only material surface validation for total-liquid and singly-owned partitioned phase
 * surfaces.
 */
final class M3MaterialSurfaceValidation {
  private M3MaterialSurfaceValidation() {}

  static Summary validate() {
    FluidMaterialRegistry registry = registry();
    int water = registry.handle("mmm:water");
    int miscible = registry.handle("mmm:miscible");
    int oil = registry.handle("mmm:oil");
    int heavy = registry.handle("mmm:heavy");
    validateBoundedPieces(registry, water);

    FluidDomain immiscibleDomain = layeredDomain(registry, water, oil);
    double volumeBefore = immiscibleDomain.totalVolume(), massBefore = immiscibleDomain.totalMass();
    FluidMaterialRenderSnapshot layeredSnapshot = immiscibleDomain.copyMaterialRenderSnapshot();
    FluidMaterialSurfaceMesher.Mesh layered = FluidMaterialSurfaceMesher.build(layeredSnapshot);
    check(
        !layered.outerTriangles().isEmpty(),
        "material mesher omitted the total-liquid outer surface");
    check(
        !layered.interfaceTriangles().isEmpty(),
        "material mesher omitted a two-phase internal surface");
    check(
        layered.duplicateOuterTriangles() == 0 && layered.duplicateInterfaceTriangles() == 0,
        "material mesher emitted duplicate shared triangles");
    check(
        layered.overlappingCategoryTriangles() == 0,
        "liquid/liquid interface duplicated a liquid/air outer triangle");
    double outerArea =
        layered.outerTriangles().stream()
            .mapToDouble(triangle -> Math.sqrt(triangle.areaSquared()))
            .sum();
    double contactArea =
        layered.interfaceTriangles().stream()
            .mapToDouble(face -> Math.sqrt(face.triangle().areaSquared()))
            .sum();
    check(
        Math.abs(outerArea - 96) < 1.0E-8 && Math.abs(contactArea - 16) < 1.0E-8,
        "shared cell faces contributed duplicate surface area");
    int waterPhase = registry.phaseHandle(water), oilPhase = registry.phaseHandle(oil);
    double maximumPlaneError = 0.0,
        minimumCentroidX = Double.POSITIVE_INFINITY,
        maximumCentroidX = Double.NEGATIVE_INFINITY;
    for (FluidMaterialSurfaceMesher.InterfaceTriangle triangle : layered.interfaceTriangles()) {
      check(
          triangle.firstPhase() == Math.min(waterPhase, oilPhase)
              && triangle.secondPhase() == Math.max(waterPhase, oilPhase),
          "two-phase surface has unstable ownership");
      double centroidX = triangle.triangle().centroid().x();
      minimumCentroidX = Math.min(minimumCentroidX, centroidX);
      maximumCentroidX = Math.max(maximumCentroidX, centroidX);
      maximumPlaneError = Math.max(maximumPlaneError, Math.abs(centroidX - 3.0));
    }
    check(
        maximumPlaneError <= 1.0E-6,
        "planar liquid/liquid interface moved off the material bisector: x="
            + minimumCentroidX
            + ".."
            + maximumCentroidX
            + ", error="
            + maximumPlaneError);
    check(
        immiscibleDomain.totalVolume() == volumeBefore
            && immiscibleDomain.totalMass() == massBefore,
        "read-only material meshing changed physical inventories");
    int outerColourChecked = validateOuterSurfaceColouring(layeredSnapshot, layered, water, oil);
    double minimumInterfaceLevel = validateInterfaceStaysInsideLiquid(layeredSnapshot, layered);

    FluidMaterialSurfaceMesher.Mesh mixed =
        FluidMaterialSurfaceMesher.build(
            layeredDomain(registry, water, miscible).copyMaterialRenderSnapshot());
    check(
        mixed.interfaceTriangles().isEmpty(),
        "miscible materials produced a liquid/liquid interface");
    check(
        mixed.outerTriangles().equals(layered.outerTriangles()),
        "material labels changed the total-liquid outer surface");

    FluidMaterialSurfaceMesher.Mesh junction =
        FluidMaterialSurfaceMesher.build(
            threePhaseDomain(registry, water, oil, heavy).copyMaterialRenderSnapshot());
    Set<PhasePair> observedPairs = new HashSet<>();
    for (FluidMaterialSurfaceMesher.InterfaceTriangle triangle : junction.interfaceTriangles()) {
      observedPairs.add(new PhasePair(triangle.firstPhase(), triangle.secondPhase()));
    }
    int heavyPhase = registry.phaseHandle(heavy);
    Set<PhasePair> expectedPairs =
        Set.of(
            PhasePair.of(waterPhase, oilPhase),
            PhasePair.of(waterPhase, heavyPhase),
            PhasePair.of(oilPhase, heavyPhase));
    check(
        observedPairs.equals(expectedPairs),
        "three-phase junction did not expose every singly-owned phase pair: " + observedPairs);
    check(
        junction.duplicateInterfaceTriangles() == 0 && junction.overlappingCategoryTriangles() == 0,
        "three-phase junction duplicated an interface or outer surface");
    System.out.printf(
        "  material surfaces outer=%d interface=%d junction=%d planeError=%.3g "
            + "colourChecked=%d minInterfaceLevel=%.3f%n",
        layered.outerTriangles().size(),
        layered.interfaceTriangles().size(),
        junction.interfaceTriangles().size(),
        maximumPlaneError,
        outerColourChecked,
        minimumInterfaceLevel);
    return new Summary(
        layered.outerTriangles().size(),
        layered.interfaceTriangles().size(),
        junction.interfaceTriangles().size());
  }

  private static void validateBoundedPieces(FluidMaterialRegistry registry, int material) {
    var contactOnly = new FluidSurfaceInput.Piece(
        0, 2, material, registry.phaseHandle(material),
        List.of(new FluidSurfaceInput.Plane(0, 1, 0, .5)));
    check(SurfacePolyhedron.build(contactOnly).isEmpty(),
        "a plane touching only a microbox face produced a zero-volume surface");
    var contactInput = new FluidSurfaceInput(1, 1, 1, 1, List.of(contactOnly));
    check(contactInput.materialAt(.25, .5, .25) == -1,
        "a zero-volume surface reported a liquid camera medium");
    var thinPositive = new FluidSurfaceInput.Piece(
        0, 2, material, registry.phaseHandle(material),
        List.of(new FluidSurfaceInput.Plane(0, 1, 0, .5 + 1.0E-10)));
    check(SurfacePolyhedron.volume(SurfacePolyhedron.build(thinPositive)) > 0,
        "a positive thin layer disappeared from the geometry");
    var full = new FluidSurfaceInput.Piece(0, 0, material, registry.phaseHandle(material), List.of());
    var adjoining = FluidMaterialRenderSnapshot.fromDisplayFields(
        1, 1, 1, 1, registry.materials(), registry.phaseGroups(),
        new byte[registry.size()][1], new byte[registry.phaseCount()][1],
        new FluidSurfaceInput(1, 1, 1, 1, List.of(full, contactOnly)));
    double adjoiningArea = FluidMaterialSurfaceMesher.build(adjoining).outerTriangles().stream()
        .mapToDouble(triangle -> Math.sqrt(triangle.areaSquared())).sum();
    check(Math.abs(adjoiningArea - 1.5) < 1.0E-12,
        "a zero-volume neighbor removed a real liquid face or produced a sheet");
    var domain =
        new FluidDomain(4, 4, 4, new FluidSolverConfig(1, 1_000, 0, .025F, 200, 1.0E-5F, 2.0E-3F));
    domain.setVolume(1, 1, 1, 1.0E-6F);
    domain.domainCollision.setMaterialInCell(1, 1, 1, registry, material);
    var thin = domain.copyMaterialRenderSnapshot();
    var thinMesh = FluidMaterialSurfaceMesher.build(thin);
    check(!thinMesh.surfaces().isEmpty(), "thin liquid disappeared from the committed geometry");
    for (var face : thinMesh.surfaces())
      for (var point : List.of(face.triangle().a(), face.triangle().b(), face.triangle().c())) {
        check(point.y() >= 1 && point.y() <= 1.000002, "thin liquid was thickened beyond its PLIC");
      }
    check(
        thin.geometry().materialAt(1.25, 1.00000025, 1.25) == material
            && thin.geometry().materialAt(1.25, 1.001, 1.25) == -1,
        "camera medium disagrees with the thin liquid");
    var pieces = new java.util.ArrayList<FluidSurfaceInput.Piece>();
    var plane = new FluidSurfaceInput.Plane(1, 1, 1, .75);
    for (int micro = 0; micro < 8; micro++)
      pieces.add(
          new FluidSurfaceInput.Piece(
              0, micro, material, registry.phaseHandle(material), List.of(plane)));
    var input = new FluidSurfaceInput(1, 1, 1, 1, pieces);
    var snapshot =
        FluidMaterialRenderSnapshot.fromDisplayFields(
            1,
            1,
            1,
            1,
            registry.materials(),
            registry.phaseGroups(),
            new byte[registry.size()][1],
            new byte[registry.phaseCount()][1],
            input);
    var mesh = FluidMaterialSurfaceMesher.build(snapshot);
    double volume = 0;
    for (var face : mesh.surfaces()) {
      var triangle = face.triangle();
      volume += triangle.a().dot(triangle.b().cross(triangle.c())) / 6;
    }
    check(
        Math.abs(volume - .75 * .75 * .75 / 6) < 1.0E-12,
        "oblique clipped cell lost volume or outward orientation");
    check(
        mesh.equals(FluidMaterialSurfaceMesher.build(snapshot)),
        "repeated geometry reconstruction changed its ordering");
  }

  private static int validateOuterSurfaceColouring(
      FluidMaterialRenderSnapshot snapshot,
      FluidMaterialSurfaceMesher.Mesh mesh,
      int water,
      int oil) {
    int checked = 0;
    for (var surface : mesh.surfaces())
      if (surface.secondMaterial() < 0) {
        double x = surface.triangle().centroid().x();
        if (Math.abs(x - 3) <= 1.0E-9) continue;
        check(
            surface.firstMaterial() == (x < 3 ? water : oil),
            "outer face lost its supplying material");
        checked++;
      }
    check(checked > 0, "outer material metadata was never exercised");
    return checked;
  }

  private static double validateInterfaceStaysInsideLiquid(
      FluidMaterialRenderSnapshot snapshot, FluidMaterialSurfaceMesher.Mesh mesh) {
    int checked = 0;
    for (var surface : mesh.surfaces())
      if (surface.secondMaterial() >= 0) {
        check(surface.firstPhase() != surface.secondPhase(), "shared face has no phase transition");
        var triangle = surface.triangle();
        var n = triangle.normal().normalized();
        var c = triangle.centroid();
        double e = snapshot.cellSize() * 1.0E-7;
        check(
            snapshot.geometry().materialAt(c.x() - n.x() * e, c.y() - n.y() * e, c.z() - n.z() * e)
                == surface.firstMaterial(),
            "shared face normal points into its first liquid");
        check(
            snapshot.geometry().materialAt(c.x() + n.x() * e, c.y() + n.y() * e, c.z() + n.z() * e)
                == surface.secondMaterial(),
            "shared face normal does not reach its second liquid");
        checked++;
      }
    check(checked > 0, "shared face point queries were never exercised");
    return 1;
  }

  private static FluidDomain layeredDomain(
      FluidMaterialRegistry registry, int leftMaterial, int rightMaterial) {
    FluidDomain domain = filledDomain();
    for (int z = 1; z <= 4; z++)
      for (int y = 1; y <= 4; y++)
        for (int x = 1; x <= 4; x++) {
          domain.domainCollision.setMaterialInCell(
              x, y, z, registry, x <= 2 ? leftMaterial : rightMaterial);
        }
    return domain;
  }

  private static FluidDomain threePhaseDomain(
      FluidMaterialRegistry registry,
      int leftMaterial,
      int lowRightMaterial,
      int highRightMaterial) {
    FluidDomain domain = filledDomain();
    for (int z = 1; z <= 4; z++)
      for (int y = 1; y <= 4; y++)
        for (int x = 1; x <= 4; x++) {
          int material = x <= 2 ? leftMaterial : z <= 2 ? lowRightMaterial : highRightMaterial;
          domain.domainCollision.setMaterialInCell(x, y, z, registry, material);
        }
    return domain;
  }

  private static FluidDomain filledDomain() {
    FluidSolverConfig config =
        new FluidSolverConfig(1.0F, 1_000.0F, 0.0F, 1.0F / 40.0F, 200, 1.0E-5F, 2.0E-3F);
    FluidDomain domain = new FluidDomain(6, 6, 6, config);
    for (int z = 1; z <= 4; z++)
      for (int y = 1; y <= 4; y++)
        for (int x = 1; x <= 4; x++) {
          domain.setVolume(x, y, z, 1.0F);
        }
    return domain;
  }

  private static FluidMaterialRegistry registry() {
    return new FluidMaterialRegistry(
        List.of(
            new FluidMaterial("mmm:water", 1_000.0, "mmm:aqueous", 0.0, Math.PI / 2.0),
            new FluidMaterial("mmm:miscible", 1_050.0, "mmm:aqueous", 0.0, Math.PI / 2.0),
            new FluidMaterial("mmm:oil", 800.0, "mmm:oil", 0.0, Math.PI / 2.0),
            new FluidMaterial("mmm:heavy", 13_600.0, "mmm:heavy", 0.0, Math.PI / 2.0)));
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  record Summary(int outerTriangles, int twoPhaseTriangles, int junctionTriangles) {}

  private record PhasePair(int first, int second) {
    private static PhasePair of(int one, int two) {
      return new PhasePair(Math.min(one, two), Math.max(one, two));
    }
  }
}
