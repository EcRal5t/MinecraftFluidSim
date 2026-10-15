package mmm.fluid;

import java.util.ArrayList;
import java.util.List;
import mmm.chemistry.ExactRational;
import mmm.fluid.GasGeometryOverlap.Point;
import mmm.units.SignedDyadic;

/** Independent inverse-image and inertia checks for positive moving-simplex intersections. */
final class LiquidMappedTetrahedronValidation {
	private LiquidMappedTetrahedronValidation() { }
	static void run() {
		var source = List.of(point(2, 2, 2), point(3, 2, 2), point(2, 3, 2), point(2, 2, 3));
		for (int motion = 0; motion < 4; motion++) {
			int selected = motion; var arrival = source.stream().map(p -> move(p, selected)).toList();
			var simplex = new LiquidMappedTetrahedron(source, arrival);
			var pieces = simplex.partition(86, 6, 6, 6, 4096);
			var before = zeros(); var after = zeros();
			for (var piece : pieces) {
				check(piece.arrivalMoments().getFirst().signum() > 0 && piece.sourceMoments().getFirst().signum() > 0,
						"Moving simplex has a nonpositive piece");
				var independent = zeros();
				for (int micro = 0; micro < 8; micro++) add(independent,
						GasGeometryOverlap.normalizedSecondMoments(micro, piece.sourceSupport()));
				check(List.of(independent).equals(piece.sourceMoments()), "Inverse-image moments differ from independent source clipping");
				add(before, independent);
				add(after, global(piece.arrivalMoments(), piece.receiver()));
			}
			check(before[0].equals(ExactRational.ONE.divide(6)), "Moving source lost volume");
			for (int a = 0; a < 3; a++) check(before[a + 1].equals(ExactRational.ONE.divide(-24)), "Moving source lost its first moment");
			for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++)
				check(before[index(a, b)].equals(ExactRational.ONE.divide(a == b ? 60 : 120)), "Moving source lost its second moment");
			check(List.of(after).equals(analytic(arrival, simplex.arrivalVolume())), "Moving arrival differs from analytic simplex integration");
			check(simplex.jacobian().equals(motion == 3 ? ExactRational.of(3).divide(4) : ExactRational.ONE),
					"Moving simplex concealed its local volume change");
		}
		rigidRotationKeepsGeometricInertia(); invalidGeometryRejects(source);
		commonMeshKeepsVolumeAndFaceMomentRate(); exactMeshEmbeddingChecksEdgeAxes();
	}
	public static void main(String[] args) { run(); System.out.println("Positive mapped liquid simplex validation passed"); }
	static void capturedField(FluidDomain domain) {
		var field = domain.pressureSystem.markerVelocityField(); var geometry = LiquidPressureGeometry.capture(domain);
		var boxes = new ArrayList<LiquidMovingMesh.SourceBox>(); var first = new double[3]; var bulk = new double[3];
		for (var shape : domain.liquidShapes) {
			for (var box : shape.boxes()) {
				boxes.add(new LiquidMovingMesh.SourceBox(shape.cell(), box));
				int[] cell = {shape.cell() % domain.sizeX, shape.cell() / domain.sizeX % domain.sizeY, shape.cell() / (domain.sizeX * domain.sizeY)};
				for (int a = 0; a < 3; a++) first[a] += box.volume().toDouble() * (cell[a] + box.lower(a).add(box.upper(a)).toDouble() / 2);
			}
			var mean = field.meanWetVelocity(geometry, shape.cell(), 255);
			for (int a = 0; a < 3; a++) bulk[a] += shape.volume().toDouble() * mean[a];
		}
		double previous = Double.POSITIVE_INFINITY;
		for (int halves = 0; halves < 3; halves++) {
			var time = ExactRational.ONE.divide(128L << halves); long start = System.nanoTime();
			var mesh = LiquidMovingMesh.prepare(boxes, domain.sizeX, domain.sizeY, domain.sizeZ,
					(cell, p) -> new Point(exact(field.value(0, cell, p.x().toDouble(), p.y().toDouble(), p.z().toDouble())),
							exact(field.value(1, cell, p.x().toDouble(), p.y().toDouble(), p.z().toDouble())),
							exact(field.value(2, cell, p.x().toDouble(), p.y().toDouble(), p.z().toDouble()))), time, 4096, 131072);
			checkMeshVolume(mesh, 3); var error = first.clone();
			for (int a = 0; a < 3; a++) error[a] = -error[a] - time.toDouble() * bulk[a];
			for (var simplex : mesh.simplices()) for (var p : simplex.map().arrivalVertices()) for (int a = 0; a < 3; a++)
				error[a] += simplex.map().arrivalVolume().toDouble() * p.component(a).toDouble() / 4;
			double maximum = 0; for (double value : error) maximum = Math.max(maximum, Math.abs(value));
			check(maximum * 3 < previous, "Actual captured field lost first-moment time consistency"); previous = maximum;
			System.out.printf("Common mesh reference dt=%g maps=%d M1=%g geometryMs=%g%n", time.toDouble(), mesh.simplices().size(), maximum, (System.nanoTime() - start) / 1e6);
			if (halves == 0) checkMechanicalSupport(domain, mesh);
		}
	}
	private static void checkMechanicalSupport(FluidDomain domain, LiquidMovingMesh.Mesh mesh) {
		var source = new CellRemapSpatialMoments();
		for (int p = 0; p < domain.particleCount(); p++) {
			int cell = domain.particleCellIndex(p);
			source.add(domain, p, ExactRational.ONE, cell % domain.sizeX + .5, cell / domain.sizeX % domain.sizeY + .5, cell / (domain.sizeX * domain.sizeY) + .5);
		}
		var mass = new double[mesh.simplices().size()]; var positions = new double[mass.length][3]; var preferred = new double[mass.length][3];
		var field = domain.pressureSystem.markerVelocityField();
		for (int i = 0; i < mass.length; i++) {
			var simplex = mesh.simplices().get(i); var before = new double[3]; mass[i] = simplex.map().arrivalVolume().toDouble() * 1000;
			for (int a = 0; a < 3; a++) {
				for (var p : simplex.map().arrivalVertices()) positions[i][a] += p.component(a).toDouble() / 4;
				positions[i][a] = (float) positions[i][a];
				for (var p : simplex.map().sourceVertices()) before[a] += p.component(a).toDouble() / 4;
			}
			for (int a = 0; a < 3; a++) preferred[i][a] = field.value(a, simplex.sourceCell(), before[0], before[1], before[2]);
		}
		check(source.fitMechanical(mass, positions, preferred) != null, "Connected L mesh lacks its original mechanical support before quantity allocation");
	}
	private static ExactRational exact(double value) { return PlicComponentGeometry.ratio(SignedDyadic.of(value), SignedDyadic.of(1)); }

	private static void rigidRotationKeepsGeometricInertia() {
		var moments = zeros(); var geometric = new ArrayList<LiquidGeometricAllocation.Piece>();
		for (int[] order : new int[][]{{0,1,2},{0,2,1},{1,0,2},{1,2,0},{2,0,1},{2,1,0}}) {
			var source = new ArrayList<Point>(); var coordinate = new long[]{2, 2, 2}; source.add(point(2, 2, 2));
			for (int a : order) { coordinate[a]++; source.add(point(coordinate[0], coordinate[1], coordinate[2])); }
			if (GasGeometryOverlap.determinant(source.get(1).subtract(source.get(0)), source.get(2).subtract(source.get(0)),
					source.get(3).subtract(source.get(0))).signum() < 0) java.util.Collections.swap(source, 1, 2);
			var simplex = new LiquidMappedTetrahedron(source, source.stream().map(p -> move(p, 2)).toList());
			for (var piece : simplex.partition(86, 6, 6, 6, 4096)) {
				add(moments, global(piece.arrivalMoments(), piece.receiver()));
				geometric.add(new LiquidGeometricAllocation.Piece(0, piece.receiver(), piece.sourceMoments().getFirst()));
			}
		}
		var capacities = new SignedDyadic[216]; java.util.Arrays.fill(capacities, SignedDyadic.of(1));
		var allocated = LiquidGeometricAllocation.allocate(new SignedDyadic[]{SignedDyadic.of(1)}, capacities, geometric);
		var volume = SignedDyadic.ZERO;
		for (var quantity : allocated.quantities()) volume = volume.add(quantity);
		check(volume.equals(SignedDyadic.of(1)), "Rational rotating pieces lost raw volume during joint allocation");
		var inertia = moments[4].add(moments[7]).subtract(moments[1].add(moments[2]).multiply(5))
				.add(moments[0].multiply(ExactRational.of(25).divide(2)));
		check(inertia.equals(ExactRational.ONE.divide(6)), "Rigid rotation changed its exact axial inertia");
		var angular = ExactRational.ONE.divide(6);
		var minimum = divide(angular.multiply(angular), inertia.multiply(2));
		check(minimum.equals(ExactRational.ONE.divide(12)), "Rigid rotation requires invented kinetic energy");
	}
	private static void invalidGeometryRejects(List<Point> source) {
		var reversed = new ArrayList<>(source); java.util.Collections.swap(reversed, 1, 2); boolean rejected = false;
		try { new LiquidMappedTetrahedron(source, reversed); } catch (GasTopologyBlockedException expected) { rejected = true; }
		check(rejected, "Reversed moving tetrahedron must reject before publication"); rejected = false;
		try { new LiquidMappedTetrahedron(source, source).partition(86, 6, 6, 6, 1); }
		catch (GasTopologyBlockedException expected) { rejected = true; }
		check(rejected, "Moving tetrahedron must respect its explicit intersection budget");
	}
	private static void commonMeshKeepsVolumeAndFaceMomentRate() {
		var one = SignedDyadic.of(1); var zero = SignedDyadic.ZERO;
		var box = new LiquidCellShape.Box(zero, zero, zero, one, one, one);
		for (int motion = 0; motion < 4; motion++) {
			int mode = motion;
			var mesh = LiquidMovingMesh.prepare(List.of(new LiquidMovingMesh.SourceBox(86, box)), 6, 6, 6, (cell, p) -> {
				var x = p.x().subtract(ExactRational.of(5).divide(2)); var y = p.y().subtract(ExactRational.of(5).divide(2));
				return switch (mode) {
					case 0 -> point(1, 1, 0);
					case 1 -> new Point(y, ExactRational.ZERO, ExactRational.ZERO);
					case 2 -> new Point(x, y.multiply(-1), ExactRational.ZERO);
					default -> new Point(y.multiply(-1), x, ExactRational.ZERO);
				};
			}, ExactRational.ONE.divide(8), 128, 4096);
			checkMeshVolume(mesh, 1);
			check(mesh.maximumFluxCorrection().isZero(), "Exact divergence-free motion required a source flux correction");
		}
		double previous = Double.POSITIVE_INFINITY;
		for (int halves = 0; halves < 3; halves++) {
			var time = ExactRational.ONE.divide(64L << halves);
			var mesh = LiquidMovingMesh.prepare(List.of(new LiquidMovingMesh.SourceBox(86, box),
					new LiquidMovingMesh.SourceBox(85, box), new LiquidMovingMesh.SourceBox(80, box)), 6, 6, 6,
					(cell, p) -> point(cell == 80 ? -1 : 1, cell == 85 ? -1 : 1, 0), time, 512, 16384);
			checkMeshVolume(mesh, 3);
			var first = new double[]{-6.5 - time.toDouble(), -6.5 - time.toDouble(), -7.5};
			for (var simplex : mesh.simplices()) for (var vertex : simplex.map().arrivalVertices()) for (int a = 0; a < 3; a++)
				first[a] += simplex.map().arrivalVolume().toDouble() * vertex.component(a).toDouble() / 4;
			double error = Math.max(Math.abs(first[0]), Math.abs(first[1]));
			check(error * 3 < previous && Math.abs(first[2]) < 1e-12,
					"Shared face geometry lost first-moment time consistency at a tangential velocity jump");
			previous = error;
		}
	}
	private static void checkMeshVolume(LiquidMovingMesh.Mesh mesh, long expected) {
		var source = ExactRational.ZERO; var arrival = ExactRational.ZERO;
		for (var simplex : mesh.simplices()) {
			source = source.add(simplex.map().sourceVolume()); arrival = arrival.add(simplex.map().arrivalVolume());
			check(simplex.map().jacobian().equals(ExactRational.ONE), "Local conservative remapping changed volume");
		}
		check(source.equals(ExactRational.of(expected)) && arrival.equals(source), "Common moving mesh lost positive volume");
	}
	private static void exactMeshEmbeddingChecksEdgeAxes() {
		var first = new ArrayList<>(List.of(point(-2, 1, -3), point(-1, 2, -1), point(1, -2, 0), point(-1, 2, -3)));
		var second = new ArrayList<>(List.of(point(0, -1, 1), point(0, -2, -3), point(-3, -1, -1), point(-1, -3, 2)));
		var pair = new ArrayList<LiquidMappedTetrahedron>();
		for (var vertices : List.of(first, second)) {
			if (GasGeometryOverlap.determinant(vertices.get(1).subtract(vertices.get(0)), vertices.get(2).subtract(vertices.get(0)),
					vertices.get(3).subtract(vertices.get(0))).signum() < 0) java.util.Collections.swap(vertices, 1, 2);
			pair.add(new LiquidMappedTetrahedron(vertices, vertices));
		}
		LiquidMeshEmbedding.requireDisjoint(pair, 1);
		boolean rejected = false;
		try { LiquidMeshEmbedding.requireDisjoint(pair, 0); } catch (GasTopologyBlockedException expected) { rejected = true; }
		check(rejected, "Exact mesh embedding ignored its pair budget"); rejected = false;
		try { LiquidMeshEmbedding.requireDisjoint(List.of(pair.getFirst(), pair.getFirst()), 1); }
		catch (GasTopologyBlockedException expected) { rejected = true; }
		check(rejected, "Exact mesh embedding allowed a positive-volume overlap");
	}
	private static Point move(Point p, int motion) {
		var x = p.x().subtract(ExactRational.of(2)); var y = p.y().subtract(ExactRational.of(2));
		return switch (motion) {
			case 0 -> new Point(p.x().add(y.divide(4)), p.y(), p.z());
			case 1 -> new Point(ExactRational.of(2).add(x.multiply(5).divide(4)), ExactRational.of(2).add(y.multiply(4).divide(5)), p.z());
			case 2 -> {
				var half = ExactRational.ONE.divide(2); x = x.subtract(half); y = y.subtract(half);
				yield new Point(ExactRational.of(5).divide(2).add(x.multiply(3).subtract(y.multiply(4)).divide(5)),
						ExactRational.of(5).divide(2).add(x.multiply(4).add(y.multiply(3)).divide(5)), p.z());
			}
			default -> new Point(ExactRational.of(2).add(x.multiply(3).divide(4)), p.y(), p.z());
		};
	}
	private static List<ExactRational> analytic(List<Point> vertices, ExactRational volume) {
		var result = zeros(); result[0] = volume; var sum = new ExactRational[]{ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO};
		for (var point : vertices) for (int a = 0; a < 3; a++) sum[a] = sum[a].add(point.component(a));
		for (int a = 0; a < 3; a++) result[a + 1] = sum[a].multiply(volume).divide(4);
		for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) {
			var product = sum[a].multiply(sum[b]);
			for (var point : vertices) product = product.add(point.component(a).multiply(point.component(b)));
			result[index(a, b)] = product.multiply(volume).divide(20);
		}
		return List.of(result);
	}
	private static ExactRational[] global(List<ExactRational> moments, int cell) {
		var centre = new ExactRational[]{ExactRational.of(2L * (cell % 6) + 1).divide(2),
				ExactRational.of(2L * (cell / 6 % 6) + 1).divide(2), ExactRational.of(2L * (cell / 36) + 1).divide(2)};
		var result = moments.toArray(ExactRational[]::new);
		for (int a = 0; a < 3; a++) result[a + 1] = result[a + 1].add(centre[a].multiply(moments.getFirst()));
		for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) result[index(a, b)] = result[index(a, b)]
				.add(centre[a].multiply(moments.get(b + 1))).add(centre[b].multiply(moments.get(a + 1)))
				.add(centre[a].multiply(centre[b]).multiply(moments.getFirst()));
		return result;
	}
	private static Point point(long x, long y, long z) { return new Point(ExactRational.of(x), ExactRational.of(y), ExactRational.of(z)); }
	private static ExactRational[] zeros() { var result = new ExactRational[10]; java.util.Arrays.fill(result, ExactRational.ZERO); return result; }
	private static void add(ExactRational[] target, ExactRational[] addition) { for (int i = 0; i < target.length; i++) target[i] = target[i].add(addition[i]); }
	private static int index(int a, int b) { int low = Math.min(a, b), high = Math.max(a, b); return low == 0 ? 4 + high : low == 1 ? 6 + high : 9; }
	private static ExactRational divide(ExactRational a, ExactRational b) { return new ExactRational(a.numerator().multiply(b.denominator()), a.denominator().multiply(b.numerator())); }
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
