package mmm.fluid;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** CPU-only M3 geometry gate for total-liquid and singly-owned partitioned phase surfaces. */
final class M3MaterialSurfaceValidation {
	private M3MaterialSurfaceValidation() { }

	static Summary validate() {
		FluidMaterialRegistry registry = registry();
		int water = registry.handle("mmm:water");
		int miscible = registry.handle("mmm:miscible");
		int oil = registry.handle("mmm:oil");
		int heavy = registry.handle("mmm:heavy");

		FluidDomain immiscibleDomain = layeredDomain(registry, water, oil);
		double volumeBefore = immiscibleDomain.totalVolume(), massBefore = immiscibleDomain.totalMass();
		FluidMaterialRenderSnapshot layeredSnapshot = immiscibleDomain.copyMaterialRenderSnapshot();
		FluidMaterialSurfaceMesher.Mesh layered = FluidMaterialSurfaceMesher.build(layeredSnapshot);
		check(!layered.outerTriangles().isEmpty(), "material mesher omitted the total-liquid outer surface");
		check(!layered.interfaceTriangles().isEmpty(), "material mesher omitted a two-phase internal surface");
		check(layered.duplicateOuterTriangles() == 0 && layered.duplicateInterfaceTriangles() == 0,
				"material mesher emitted duplicate shared triangles");
		check(layered.overlappingCategoryTriangles() == 0,
				"liquid/liquid interface duplicated a liquid/air outer triangle");
		int waterPhase = registry.phaseHandle(water), oilPhase = registry.phaseHandle(oil);
		double maximumPlaneError = 0.0, minimumCentroidX = Double.POSITIVE_INFINITY, maximumCentroidX = Double.NEGATIVE_INFINITY;
		for (FluidMaterialSurfaceMesher.InterfaceTriangle triangle : layered.interfaceTriangles()) {
			check(triangle.firstPhase() == Math.min(waterPhase, oilPhase)
					&& triangle.secondPhase() == Math.max(waterPhase, oilPhase),
					"two-phase surface has unstable ownership");
			double centroidX = triangle.triangle().centroid().x();
			minimumCentroidX = Math.min(minimumCentroidX, centroidX);
			maximumCentroidX = Math.max(maximumCentroidX, centroidX);
			maximumPlaneError = Math.max(maximumPlaneError, Math.abs(centroidX - 3.0));
		}
		check(maximumPlaneError <= 1.0E-6, "planar liquid/liquid interface moved off the material bisector: x="
				+ minimumCentroidX + ".." + maximumCentroidX + ", error=" + maximumPlaneError);
		check(immiscibleDomain.totalVolume() == volumeBefore && immiscibleDomain.totalMass() == massBefore,
				"read-only material meshing changed physical inventories");
		int outerColourChecked = validateOuterSurfaceColouring(layeredSnapshot, layered, water, oil);
		double minimumInterfaceLevel = validateInterfaceStaysInsideLiquid(layeredSnapshot, layered);

		FluidMaterialSurfaceMesher.Mesh mixed = FluidMaterialSurfaceMesher.build(
				layeredDomain(registry, water, miscible).copyMaterialRenderSnapshot());
		check(mixed.interfaceTriangles().isEmpty(), "miscible materials produced a liquid/liquid interface");
		check(mixed.outerTriangles().equals(layered.outerTriangles()),
				"material labels changed the total-liquid outer surface");

		FluidMaterialSurfaceMesher.Mesh junction = FluidMaterialSurfaceMesher.build(
				threePhaseDomain(registry, water, oil, heavy).copyMaterialRenderSnapshot());
		Set<PhasePair> observedPairs = new HashSet<>();
		for (FluidMaterialSurfaceMesher.InterfaceTriangle triangle : junction.interfaceTriangles()) {
			observedPairs.add(new PhasePair(triangle.firstPhase(), triangle.secondPhase()));
		}
		int heavyPhase = registry.phaseHandle(heavy);
		Set<PhasePair> expectedPairs = Set.of(
				PhasePair.of(waterPhase, oilPhase), PhasePair.of(waterPhase, heavyPhase), PhasePair.of(oilPhase, heavyPhase));
		check(observedPairs.equals(expectedPairs), "three-phase junction did not expose every singly-owned phase pair: " + observedPairs);
		check(junction.duplicateInterfaceTriangles() == 0 && junction.overlappingCategoryTriangles() == 0,
				"three-phase junction duplicated an interface or outer surface");
		System.out.printf("  material surfaces outer=%d interface=%d junction=%d planeError=%.3g "
						+ "colourChecked=%d minInterfaceLevel=%.3f%n",
				layered.outerTriangles().size(), layered.interfaceTriangles().size(),
				junction.interfaceTriangles().size(), maximumPlaneError, outerColourChecked, minimumInterfaceLevel);
		return new Summary(layered.outerTriangles().size(), layered.interfaceTriangles().size(), junction.interfaceTriangles().size());
	}

	/**
	 * The outer surface must take its colour from real nearby material. A sample that lands in the empty cell just
	 * outside the free surface used to resolve to palette entry 0, which painted oil surfaces water-blue.
	 */
	private static int validateOuterSurfaceColouring(
			FluidMaterialRenderSnapshot snapshot, FluidMaterialSurfaceMesher.Mesh mesh, int water, int oil
	) {
		int checked = 0;
		for (FluidMaterialSurfaceMesher.Triangle triangle : mesh.outerTriangles()) {
			double centroidX = triangle.centroid().x();
			int resolved = FluidMaterialSurfaceMesher.dominantMaterialAt(snapshot, triangle.centroid());
			check(resolved >= 0, "outer surface triangle at x=" + centroidX + " resolved to no material");
			if (Math.abs(centroidX - 3.0) <= 1.0E-9) continue;
			int expected = centroidX < 3.0 ? water : oil;
			check(resolved == expected, "outer surface at x=" + centroidX + " coloured material " + resolved
					+ " instead of " + expected);
			checked++;
		}
		check(checked > 0, "outer surface colouring was never exercised");
		return checked;
	}

	/**
	 * Liquid/liquid geometry must stay strictly inside the free surface; coplanar categories z-fight in the client.
	 * ISO_LEVEL is 0.5 and the mesher insets the interface, so every centroid must sample clearly above the isolevel.
	 */
	private static double validateInterfaceStaysInsideLiquid(
			FluidMaterialRenderSnapshot snapshot, FluidMaterialSurfaceMesher.Mesh mesh
	) {
		double minimumLevel = Double.POSITIVE_INFINITY;
		for (FluidMaterialSurfaceMesher.InterfaceTriangle triangle : mesh.interfaceTriangles()) {
			double level = FluidMaterialSurfaceMesher.liquidLevelAt(snapshot, triangle.triangle().centroid());
			minimumLevel = Math.min(minimumLevel, level);
		}
		check(minimumLevel >= 0.52, "liquid/liquid interface touched the free surface: minimum level " + minimumLevel);
		return minimumLevel;
	}

	private static FluidDomain layeredDomain(FluidMaterialRegistry registry, int leftMaterial, int rightMaterial) {
		FluidDomain domain = filledDomain();
		for (int z = 1; z <= 4; z++) for (int y = 1; y <= 4; y++) for (int x = 1; x <= 4; x++) {
			domain.setMaterialInCell(x, y, z, registry, x <= 2 ? leftMaterial : rightMaterial);
		}
		return domain;
	}

	private static FluidDomain threePhaseDomain(
			FluidMaterialRegistry registry, int leftMaterial, int lowRightMaterial, int highRightMaterial
	) {
		FluidDomain domain = filledDomain();
		for (int z = 1; z <= 4; z++) for (int y = 1; y <= 4; y++) for (int x = 1; x <= 4; x++) {
			int material = x <= 2 ? leftMaterial : z <= 2 ? lowRightMaterial : highRightMaterial;
			domain.setMaterialInCell(x, y, z, registry, material);
		}
		return domain;
	}

	private static FluidDomain filledDomain() {
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, 1_000.0F, 0.0F, 1.0F / 40.0F,
				200, 1.0E-5F, 0.40F, 2.0E-3F, 0.80F
		);
		FluidDomain domain = new FluidDomain(6, 6, 6, config, 1_024);
		for (int z = 1; z <= 4; z++) for (int y = 1; y <= 4; y++) for (int x = 1; x <= 4; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		return domain;
	}

	private static FluidMaterialRegistry registry() {
		return new FluidMaterialRegistry(List.of(
				new FluidMaterial("mmm:water", 1_000.0, "mmm:aqueous", 0.0, Math.PI / 2.0),
				new FluidMaterial("mmm:miscible", 1_050.0, "mmm:aqueous", 0.0, Math.PI / 2.0),
				new FluidMaterial("mmm:oil", 800.0, "mmm:oil", 0.0, Math.PI / 2.0),
				new FluidMaterial("mmm:heavy", 13_600.0, "mmm:heavy", 0.0, Math.PI / 2.0)
		));
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	record Summary(int outerTriangles, int twoPhaseTriangles, int junctionTriangles) { }
	private record PhasePair(int first, int second) {
		private static PhasePair of(int one, int two) { return new PhasePair(Math.min(one, two), Math.max(one, two)); }
	}
}
