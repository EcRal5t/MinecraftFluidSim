package com.example.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** CPU reference mesher for one total-liquid surface plus singly-owned partitioned liquid/liquid interfaces. */
public final class FluidMaterialSurfaceMesher {
	private static final double ISO_LEVEL = 0.5;
	private static final double MARKER_INTERIOR_LEVEL = 0.65;
	private static final int SURFACE_THRESHOLD = 8;
	/** Keeps liquid/liquid geometry strictly inside the free surface so the two categories never render coplanar. */
	private static final double INTERFACE_INSET = 0.06;
	/** Prunes noise-level smoothed phase tails that would otherwise emit partition planes far from real material. */
	private static final double MIN_PHASE_PRESENCE = 0.05;
	private static final int MAX_MATERIAL_SAMPLE_RADIUS = 2;
	private static final double EPSILON = 1.0E-9;
	private static final int[][] CUBE_TETRAHEDRA = {
			{0, 5, 1, 6}, {0, 1, 2, 6}, {0, 2, 3, 6},
			{0, 3, 7, 6}, {0, 7, 4, 6}, {0, 4, 5, 6}
	};
	private static final int[][] TETRAHEDRON_EDGES = {
			{0, 1}, {0, 2}, {0, 3}, {1, 2}, {1, 3}, {2, 3}
	};
	private static final int[][] CUBE_OFFSETS = {
			{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {0, 1, 0},
			{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}
	};

	private FluidMaterialSurfaceMesher() { }

	public static Mesh build(FluidMaterialRenderSnapshot snapshot) {
		if (snapshot == null) throw new IllegalArgumentException("Material snapshot must not be null");
		int nodeSizeX = snapshot.sizeX() + 2;
		int nodeSizeY = snapshot.sizeY() + 2;
		int nodeSizeZ = snapshot.sizeZ() + 2;
		double[] liquid = new double[Math.multiplyExact(Math.multiplyExact(nodeSizeX, nodeSizeY), nodeSizeZ)];
		for (int z = -1; z <= snapshot.sizeZ(); z++) for (int y = -1; y <= snapshot.sizeY(); y++) {
			for (int x = -1; x <= snapshot.sizeX(); x++) {
				liquid[nodeIndex(nodeSizeX, nodeSizeY, x, y, z)] = liquidScalar(snapshot, x, y, z);
			}
		}

		List<Triangle> outer = new ArrayList<>();
		List<InterfaceTriangle> interfaces = new ArrayList<>();
		Set<TriangleKey> outerKeys = new HashSet<>();
		Set<OwnedTriangleKey> interfaceKeys = new HashSet<>();
		int duplicateOuter = 0, duplicateInterfaces = 0;
		Point[] cubePoints = new Point[8];
		double[] cubeLiquid = new double[8];
		double[][] cubePhases = new double[snapshot.phaseGroups().size()][8];
		for (int z = -1; z < snapshot.sizeZ(); z++) for (int y = -1; y < snapshot.sizeY(); y++) {
			for (int x = -1; x < snapshot.sizeX(); x++) {
				setCube(snapshot, liquid, nodeSizeX, nodeSizeY, x, y, z, cubePoints, cubeLiquid, cubePhases);
				for (int[] tetrahedron : CUBE_TETRAHEDRA) {
					for (Triangle triangle : outerTriangles(cubePoints, cubeLiquid, tetrahedron)) {
						TriangleKey key = TriangleKey.of(triangle);
						if (outerKeys.add(key)) outer.add(triangle);
						else duplicateOuter++;
					}
					for (InterfaceTriangle triangle : interfaceTriangles(cubePoints, cubeLiquid, cubePhases, tetrahedron)) {
						OwnedTriangleKey key = OwnedTriangleKey.of(triangle);
						if (interfaceKeys.add(key)) interfaces.add(triangle);
						else duplicateInterfaces++;
					}
				}
			}
		}
		int overlaps = 0;
		for (InterfaceTriangle triangle : interfaces) if (outerKeys.contains(TriangleKey.of(triangle.triangle()))) overlaps++;
		return new Mesh(outer, interfaces, duplicateOuter, duplicateInterfaces, overlaps);
	}

	/**
	 * Display-only dominant material at a surface point. Only marker-bearing cells vote, so a sample that lands in the
	 * empty cell outside the free surface cannot silently default to palette entry 0. Returns -1 when no material is
	 * resolvable, which callers must skip rather than paint with an arbitrary colour.
	 */
	public static int dominantMaterialAt(FluidMaterialRenderSnapshot snapshot, Point point) {
		if (snapshot == null || point == null) throw new IllegalArgumentException("Material sample needs a snapshot and point");
		double[] weightByMaterial = new double[snapshot.materials().size()];
		for (int radius = 1; radius <= MAX_MATERIAL_SAMPLE_RADIUS; radius++) {
			Arrays.fill(weightByMaterial, 0.0);
			double support = radius * snapshot.cellSize();
			int baseX = (int) Math.floor(point.x / snapshot.cellSize());
			int baseY = (int) Math.floor(point.y / snapshot.cellSize());
			int baseZ = (int) Math.floor(point.z / snapshot.cellSize());
			for (int dz = -radius; dz <= radius; dz++) for (int dy = -radius; dy <= radius; dy++) {
				for (int dx = -radius; dx <= radius; dx++) {
					int x = baseX + dx, y = baseY + dy, z = baseZ + dz;
					if (!inside(snapshot, x, y, z) || solid(snapshot, x, y, z)) continue;
					int cell = index(snapshot, x, y, z);
					if (snapshot.markerField()[cell] == 0) continue;
					double weight = supportWeight(point.x, x, snapshot.cellSize(), support)
							* supportWeight(point.y, y, snapshot.cellSize(), support)
							* supportWeight(point.z, z, snapshot.cellSize(), support);
					if (weight <= 0.0) continue;
					for (int material = 0; material < weightByMaterial.length; material++) {
						weightByMaterial[material] += weight * snapshot.materialFraction(material, cell);
					}
				}
			}
			int best = -1;
			double bestWeight = 0.0;
			for (int material = 0; material < weightByMaterial.length; material++) {
				if (weightByMaterial[material] > bestWeight) { best = material; bestWeight = weightByMaterial[material]; }
			}
			if (best >= 0) return best;
		}
		return -1;
	}

	/** Trilinear total-liquid level at an arbitrary point, using the same cell-centre nodes the surface is built from. */
	public static double liquidLevelAt(FluidMaterialRenderSnapshot snapshot, Point point) {
		if (snapshot == null || point == null) throw new IllegalArgumentException("Liquid sample needs a snapshot and point");
		double nodeX = point.x / snapshot.cellSize() - 0.5;
		double nodeY = point.y / snapshot.cellSize() - 0.5;
		double nodeZ = point.z / snapshot.cellSize() - 0.5;
		int baseX = (int) Math.floor(nodeX), baseY = (int) Math.floor(nodeY), baseZ = (int) Math.floor(nodeZ);
		double fractionX = nodeX - baseX, fractionY = nodeY - baseY, fractionZ = nodeZ - baseZ;
		double result = 0.0;
		for (int dz = 0; dz <= 1; dz++) for (int dy = 0; dy <= 1; dy++) for (int dx = 0; dx <= 1; dx++) {
			double weight = (dx == 0 ? 1.0 - fractionX : fractionX)
					* (dy == 0 ? 1.0 - fractionY : fractionY)
					* (dz == 0 ? 1.0 - fractionZ : fractionZ);
			if (weight <= 0.0) continue;
			result += weight * liquidScalar(snapshot, baseX + dx, baseY + dy, baseZ + dz);
		}
		return result;
	}

	private static double supportWeight(double coordinate, int cell, double cellSize, double support) {
		double center = (cell + 0.5) * cellSize;
		return Math.max(0.0, 1.0 - Math.abs(coordinate - center) / support);
	}

	private static void setCube(
			FluidMaterialRenderSnapshot snapshot, double[] liquid, int nodeSizeX, int nodeSizeY,
			int x, int y, int z, Point[] points, double[] values, double[][] phases
	) {
		for (int vertex = 0; vertex < 8; vertex++) {
			int sampleX = x + CUBE_OFFSETS[vertex][0];
			int sampleY = y + CUBE_OFFSETS[vertex][1];
			int sampleZ = z + CUBE_OFFSETS[vertex][2];
			points[vertex] = new Point(
					(sampleX + 0.5) * snapshot.cellSize(),
					(sampleY + 0.5) * snapshot.cellSize(),
					(sampleZ + 0.5) * snapshot.cellSize()
			);
			values[vertex] = liquid[nodeIndex(nodeSizeX, nodeSizeY, sampleX, sampleY, sampleZ)];
			for (int phase = 0; phase < phases.length; phase++) {
				phases[phase][vertex] = phaseFraction(snapshot, phase, sampleX, sampleY, sampleZ);
			}
		}
	}

	private static List<Triangle> outerTriangles(Point[] points, double[] values, int[] tetrahedron) {
		int[] inside = new int[4], outside = new int[4];
		int insideCount = 0, outsideCount = 0;
		for (int vertex : tetrahedron) {
			if (values[vertex] >= ISO_LEVEL) inside[insideCount++] = vertex;
			else outside[outsideCount++] = vertex;
		}
		if (insideCount == 0 || insideCount == 4) return List.of();
		List<Triangle> result = new ArrayList<>(2);
		if (insideCount == 1 || insideCount == 3) {
			int lone = insideCount == 1 ? inside[0] : outside[0];
			int[] others = insideCount == 1 ? outside : inside;
			addTriangle(result, new Triangle(
					isoPoint(points, values, lone, others[0]),
					isoPoint(points, values, lone, others[1]),
					isoPoint(points, values, lone, others[2])
			));
		} else {
			Point ac = isoPoint(points, values, inside[0], outside[0]);
			Point ad = isoPoint(points, values, inside[0], outside[1]);
			Point bc = isoPoint(points, values, inside[1], outside[0]);
			Point bd = isoPoint(points, values, inside[1], outside[1]);
			addTriangle(result, new Triangle(ac, ad, bd));
			addTriangle(result, new Triangle(ac, bd, bc));
		}
		return result;
	}

	private static List<InterfaceTriangle> interfaceTriangles(
			Point[] points, double[] liquid, double[][] phases, int[] tetrahedron
	) {
		boolean[] present = new boolean[phases.length];
		for (int phase = 0; phase < phases.length; phase++) for (int vertex : tetrahedron) {
			if (phases[phase][vertex] >= MIN_PHASE_PRESENCE && liquid[vertex] >= ISO_LEVEL) present[phase] = true;
		}
		List<InterfaceTriangle> result = new ArrayList<>();
		for (int first = 0; first < phases.length; first++) if (present[first]) {
			for (int second = first + 1; second < phases.length; second++) if (present[second]) {
				result.addAll(partitionPair(points, liquid, phases, tetrahedron, first, second));
			}
		}
		return result;
	}

	private static List<InterfaceTriangle> partitionPair(
			Point[] points, double[] liquid, double[][] phases, int[] tetrahedron, int first, int second
	) {
		PartitionVertex[] vertices = new PartitionVertex[4];
		double minimumDifference = Double.POSITIVE_INFINITY, maximumDifference = Double.NEGATIVE_INFINITY;
		for (int local = 0; local < 4; local++) {
			int cubeVertex = tetrahedron[local];
			double[] fractions = new double[phases.length];
			for (int phase = 0; phase < phases.length; phase++) fractions[phase] = phases[phase][cubeVertex];
			vertices[local] = new PartitionVertex(points[cubeVertex], liquid[cubeVertex], fractions);
			double difference = fractions[first] - fractions[second];
			minimumDifference = Math.min(minimumDifference, difference);
			maximumDifference = Math.max(maximumDifference, difference);
		}
		if (maximumDifference - minimumDifference <= EPSILON || minimumDifference > EPSILON || maximumDifference < -EPSILON) {
			return List.of();
		}
		List<PartitionVertex> polygon = new ArrayList<>(4);
		for (int[] edge : TETRAHEDRON_EDGES) {
			PartitionVertex one = vertices[edge[0]], two = vertices[edge[1]];
			double firstDifference = one.fractions[first] - one.fractions[second];
			double secondDifference = two.fractions[first] - two.fractions[second];
			if (Math.abs(firstDifference) <= EPSILON) addUnique(polygon, one);
			if (Math.abs(secondDifference) <= EPSILON) addUnique(polygon, two);
			if (firstDifference * secondDifference < -EPSILON * EPSILON) {
				addUnique(polygon, interpolate(one, two, firstDifference / (firstDifference - secondDifference)));
			}
		}
		if (polygon.size() < 3) return List.of();
		sortPolygon(polygon);
		polygon = clip(polygon, vertex -> vertex.liquid - (ISO_LEVEL + INTERFACE_INSET));
		for (int phase = 0; phase < phases.length && polygon.size() >= 3; phase++) {
			if (phase == first || phase == second) continue;
			int competitor = phase;
			polygon = clip(polygon, vertex -> vertex.fractions[first] - vertex.fractions[competitor]);
		}
		if (polygon.size() < 3) return List.of();

		Point firstCenter = weightedCenter(vertices, first);
		Point secondCenter = weightedCenter(vertices, second);
		Point direction = secondCenter.subtract(firstCenter);
		List<InterfaceTriangle> result = new ArrayList<>(polygon.size() - 2);
		for (int index = 1; index + 1 < polygon.size(); index++) {
			Triangle triangle = new Triangle(polygon.getFirst().point, polygon.get(index).point, polygon.get(index + 1).point);
			if (triangle.areaSquared() <= EPSILON * EPSILON) continue;
			if (triangle.normal().dot(direction) < 0.0) triangle = new Triangle(triangle.a(), triangle.c(), triangle.b());
			result.add(new InterfaceTriangle(first, second, triangle));
		}
		return result;
	}

	private static List<PartitionVertex> clip(List<PartitionVertex> polygon, Scalar scalar) {
		if (polygon.isEmpty()) return List.of();
		List<PartitionVertex> result = new ArrayList<>(polygon.size() + 1);
		PartitionVertex previous = polygon.getLast();
		double previousValue = scalar.value(previous);
		boolean previousInside = previousValue >= -EPSILON;
		for (PartitionVertex current : polygon) {
			double currentValue = scalar.value(current);
			boolean currentInside = currentValue >= -EPSILON;
			if (currentInside != previousInside) {
				double denominator = previousValue - currentValue;
				double fraction = Math.abs(denominator) <= EPSILON ? 0.5 : previousValue / denominator;
				result.add(interpolate(previous, current, fraction));
			}
			if (currentInside) result.add(current);
			previous = current;
			previousValue = currentValue;
			previousInside = currentInside;
		}
		return result;
	}

	private static void sortPolygon(List<PartitionVertex> polygon) {
		Point center = new Point(0.0, 0.0, 0.0);
		for (PartitionVertex vertex : polygon) center = center.add(vertex.point);
		center = center.scale(1.0 / polygon.size());
		Point normal = null;
		for (int one = 0; one < polygon.size() && normal == null; one++) for (int two = one + 1; two < polygon.size(); two++) {
			Point candidate = polygon.get(one).point.subtract(center).cross(polygon.get(two).point.subtract(center));
			if (candidate.lengthSquared() > EPSILON * EPSILON) normal = candidate.normalized();
		}
		if (normal == null) return;
		Point axis = polygon.getFirst().point.subtract(center).normalized();
		Point perpendicular = normal.cross(axis);
		Point finalCenter = center, finalAxis = axis, finalPerpendicular = perpendicular;
		polygon.sort((left, right) -> Double.compare(
				Math.atan2(left.point.subtract(finalCenter).dot(finalPerpendicular), left.point.subtract(finalCenter).dot(finalAxis)),
				Math.atan2(right.point.subtract(finalCenter).dot(finalPerpendicular), right.point.subtract(finalCenter).dot(finalAxis))
		));
	}

	private static Point weightedCenter(PartitionVertex[] vertices, int phase) {
		Point result = new Point(0.0, 0.0, 0.0);
		double weight = 0.0;
		for (PartitionVertex vertex : vertices) {
			result = result.add(vertex.point.scale(vertex.fractions[phase]));
			weight += vertex.fractions[phase];
		}
		return weight <= EPSILON ? vertices[0].point : result.scale(1.0 / weight);
	}

	private static PartitionVertex interpolate(PartitionVertex one, PartitionVertex two, double fraction) {
		double t = Math.max(0.0, Math.min(1.0, fraction));
		double[] fields = new double[one.fractions.length];
		for (int field = 0; field < fields.length; field++) fields[field] = lerp(one.fractions[field], two.fractions[field], t);
		return new PartitionVertex(one.point.lerp(two.point, t), lerp(one.liquid, two.liquid, t), fields);
	}

	private static void addUnique(List<PartitionVertex> vertices, PartitionVertex candidate) {
		for (PartitionVertex vertex : vertices) if (vertex.point.subtract(candidate.point).lengthSquared() <= EPSILON * EPSILON) return;
		vertices.add(candidate);
	}

	private static Point isoPoint(Point[] positions, double[] values, int first, int second) {
		double denominator = values[second] - values[first];
		double fraction = Math.abs(denominator) <= EPSILON ? 0.5 : (ISO_LEVEL - values[first]) / denominator;
		return positions[first].lerp(positions[second], fraction);
	}

	private static void addTriangle(List<Triangle> triangles, Triangle triangle) {
		if (triangle.areaSquared() > EPSILON * EPSILON) triangles.add(triangle);
	}

	private static double liquidScalar(FluidMaterialRenderSnapshot snapshot, int x, int y, int z) {
		if (!inside(snapshot, x, y, z) || solid(snapshot, x, y, z)) return 0.0;
		double weighted = 0.0, weightSum = 0.0;
		for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
			int sampleX = x + dx, sampleY = y + dy, sampleZ = z + dz;
			if (!inside(snapshot, sampleX, sampleY, sampleZ) || solid(snapshot, sampleX, sampleY, sampleZ)) continue;
			double weight = kernelWeight(dx) * kernelWeight(dy) * kernelWeight(dz);
			int raw = Byte.toUnsignedInt(snapshot.totalVolumeField()[index(snapshot, sampleX, sampleY, sampleZ)]);
			double density = raw < SURFACE_THRESHOLD ? 0.0 : raw / 255.0;
			weighted += density * weight;
			weightSum += weight;
		}
		double result = weightSum <= EPSILON ? 0.0 : weighted / weightSum;
		if (snapshot.markerField()[index(snapshot, x, y, z)] != 0) result = Math.max(result, MARKER_INTERIOR_LEVEL);
		return result;
	}

	private static double phaseFraction(FluidMaterialRenderSnapshot snapshot, int phase, int x, int y, int z) {
		double weightedPhase = 0.0, weightSum = 0.0;
		for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
			int sampleX = x + dx, sampleY = y + dy, sampleZ = z + dz;
			if (!inside(snapshot, sampleX, sampleY, sampleZ) || solid(snapshot, sampleX, sampleY, sampleZ)) continue;
			int cell = index(snapshot, sampleX, sampleY, sampleZ);
			if (snapshot.markerField()[cell] == 0) continue;
			double fraction = Byte.toUnsignedInt(snapshot.phaseFractionField(phase)[cell]) / 255.0;
			double weight = kernelWeight(dx) * kernelWeight(dy) * kernelWeight(dz);
			weightedPhase += fraction * weight;
			weightSum += weight;
		}
		return weightSum <= EPSILON ? 0.0 : weightedPhase / weightSum;
	}

	private static int kernelWeight(int offset) { return offset == 0 ? 2 : 1; }
	private static double lerp(double one, double two, double t) { return one + (two - one) * t; }
	private static boolean inside(FluidMaterialRenderSnapshot snapshot, int x, int y, int z) {
		return x >= 0 && x < snapshot.sizeX() && y >= 0 && y < snapshot.sizeY() && z >= 0 && z < snapshot.sizeZ();
	}
	private static boolean solid(FluidMaterialRenderSnapshot snapshot, int x, int y, int z) {
		return snapshot.solidField()[index(snapshot, x, y, z)] != 0;
	}
	private static int index(FluidMaterialRenderSnapshot snapshot, int x, int y, int z) {
		return x + snapshot.sizeX() * (y + snapshot.sizeY() * z);
	}
	private static int nodeIndex(int sizeX, int sizeY, int x, int y, int z) {
		return (x + 1) + sizeX * ((y + 1) + sizeY * (z + 1));
	}

	public record Mesh(
			List<Triangle> outerTriangles,
			List<InterfaceTriangle> interfaceTriangles,
			int duplicateOuterTriangles,
			int duplicateInterfaceTriangles,
			int overlappingCategoryTriangles
	) {
		public Mesh {
			outerTriangles = List.copyOf(outerTriangles);
			interfaceTriangles = List.copyOf(interfaceTriangles);
		}
	}

	public record InterfaceTriangle(int firstPhase, int secondPhase, Triangle triangle) {
		public InterfaceTriangle {
			if (firstPhase < 0 || secondPhase <= firstPhase || triangle == null) {
				throw new IllegalArgumentException("Interface ownership must be a sorted phase pair");
			}
		}
	}

	public record Triangle(Point a, Point b, Point c) {
		public Point normal() { return b.subtract(a).cross(c.subtract(a)); }
		public double areaSquared() { return normal().lengthSquared() * 0.25; }
		public Point centroid() { return a.add(b).add(c).scale(1.0 / 3.0); }
	}

	public record Point(double x, double y, double z) {
		Point add(Point other) { return new Point(x + other.x, y + other.y, z + other.z); }
		Point subtract(Point other) { return new Point(x - other.x, y - other.y, z - other.z); }
		Point scale(double value) { return new Point(x * value, y * value, z * value); }
		Point lerp(Point other, double t) { return new Point(FluidMaterialSurfaceMesher.lerp(x, other.x, t), FluidMaterialSurfaceMesher.lerp(y, other.y, t), FluidMaterialSurfaceMesher.lerp(z, other.z, t)); }
		Point cross(Point other) { return new Point(y * other.z - z * other.y, z * other.x - x * other.z, x * other.y - y * other.x); }
		double dot(Point other) { return x * other.x + y * other.y + z * other.z; }
		double lengthSquared() { return dot(this); }
		Point normalized() { double length = Math.sqrt(lengthSquared()); return length <= EPSILON ? this : scale(1.0 / length); }
	}

	private record PartitionVertex(Point point, double liquid, double[] fractions) { }
	@FunctionalInterface private interface Scalar { double value(PartitionVertex vertex); }

	private record PointKey(long x, long y, long z) implements Comparable<PointKey> {
		static PointKey of(Point point) { return new PointKey(quantize(point.x), quantize(point.y), quantize(point.z)); }
		private static long quantize(double value) { return Math.round(value * 1_000_000.0); }
		@Override public int compareTo(PointKey other) {
			int xOrder = Long.compare(x, other.x); if (xOrder != 0) return xOrder;
			int yOrder = Long.compare(y, other.y); return yOrder != 0 ? yOrder : Long.compare(z, other.z);
		}
	}

	private record TriangleKey(PointKey a, PointKey b, PointKey c) {
		static TriangleKey of(Triangle triangle) {
			PointKey[] points = {PointKey.of(triangle.a()), PointKey.of(triangle.b()), PointKey.of(triangle.c())};
			Arrays.sort(points);
			return new TriangleKey(points[0], points[1], points[2]);
		}
	}

	private record OwnedTriangleKey(int firstPhase, int secondPhase, TriangleKey triangle) {
		static OwnedTriangleKey of(InterfaceTriangle interfaceTriangle) {
			return new OwnedTriangleKey(interfaceTriangle.firstPhase(), interfaceTriangle.secondPhase(), TriangleKey.of(interfaceTriangle.triangle()));
		}
	}
}
