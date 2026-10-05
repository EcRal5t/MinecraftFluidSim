package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared full velocity traces, exact moments and surface work under continuous shear. */
final class LiquidConformingVelocityValidation {
	static void validate() {
		affineFieldSurvivesAdaptiveFaces();
		arbitraryProjectedRatesPreserveMeansAndWalls();
		continuousShearPreservesExteriorAreaAndWork();
		spaceTimeSweepsPreserveVolumeAndSharedFaces();
	}
	private static void spaceTimeSweepsPreserveVolumeAndSharedFaces() {
		var cells = LiquidGeometryRefinement.children(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1)));
		var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var mean = new double[3 * cells.size()]; var rate = new Fraction[mesh.faces.size()];
		for (int c = 0; c < cells.size(); c++) {
			var u = affine(mesh.cells.get(c).geometry().centroidCoordinates()); for (int a = 0; a < 3; a++) mean[3 * c + a] = u[a].rounded();
		}
		for (int f = 0; f < rate.length; f++) rate[f] = dot(mesh.faces.get(f).areaVector(), affine(mesh.faces.get(f).centroid().toArray(Fraction[]::new)));
		var field = new LiquidConformingVelocity(mesh, rate, mean); var motion = new LiquidConformingTransport(mesh, field, rate, 1.0 / 128);
		var volume = new Fraction[cells.size()]; Arrays.fill(volume, Fraction.ZERO);
		var paired = new java.util.HashMap<List<LiquidExactPolygon.Vertex>, Integer>();
		for (int i = 0; i < motion.images.size(); i++) {
			var image = motion.images.get(i); var source = field.tetrahedra.get(i);
			var mapped = LiquidConvexRegion.ofFaces(LiquidExactPolygon.tetrahedron(source.vertices())).transformed(image.affineMap());
			if (!new java.util.HashSet<>(mapped.faces().stream().map(LiquidExactPolygon::vertexKey).toList())
					.equals(new java.util.HashSet<>(image.geometry().faces().stream().map(LiquidExactPolygon::vertexKey).toList())))
				throw new AssertionError("Continuous affine image map differs from transported vertices");
			volume[image.cell()] = volume[image.cell()].add(image.geometry().measure().volume());
			if (mesh.faces.get(image.face()).second() >= 0) paired.merge(image.geometry().faces().getFirst().vertexKey(), 1, Integer::sum);
		}
		for (var v : volume) if (!v.equals(Fraction.of(0.125))) throw new AssertionError("Finite-step incompressible transport changed cell volume");
		for (int count : paired.values()) if (count != 2) throw new AssertionError("Finite-step shared velocity opened an internal geometric face");
		for (int f = 0; f < rate.length; f++) if (!motion.sweptVolume[f].equals(rate[f].multiply(Fraction.of(1.0 / 128))))
			throw new AssertionError("Swept volume does not represent pressure face flux");
		// Compliant gas has a finite divergence; its prescribed volume change uses the same sweep.
		var expandingRate = rate.clone(); int exterior = java.util.stream.IntStream.range(0, rate.length).filter(f -> mesh.faces.get(f).second() < 0).findFirst().orElseThrow();
		expandingRate[exterior] = expandingRate[exterior].add(Fraction.of(0.0625));
		var expanded = new LiquidConformingTransport(mesh, new LiquidConformingVelocity(mesh, expandingRate, mean), expandingRate, 1.0 / 128);
		var total = Fraction.ZERO; for (var image : expanded.images) total = total.add(image.geometry().measure().volume());
		if (!total.equals(Fraction.of(1).add(Fraction.of(0.0625 / 128)))) throw new AssertionError("Compliant volume source was changed by shared transport");
		try {
			new LiquidConformingTransport(mesh, field, rate, 64);
			throw new AssertionError("An inverted continuous candidate was accepted");
		} catch (LiquidCharacteristicTimeResolutionRequired expected) { }
	}
	private static void affineFieldSurvivesAdaptiveFaces() {
		var cells = new ArrayList<>(LiquidGeometryRefinement.children(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1))));
		cells.addAll(LiquidGeometryRefinement.children(cells.removeFirst()));
		var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var mean = new double[3 * cells.size()]; var rates = new Fraction[mesh.faces.size()];
		for (int c = 0; c < cells.size(); c++) {
			var u = affine(mesh.cells.get(c).geometry().centroidCoordinates()); for (int a = 0; a < 3; a++) mean[3 * c + a] = u[a].rounded();
		}
		for (int f = 0; f < rates.length; f++) rates[f] = dot(mesh.faces.get(f).areaVector(), affine(mesh.faces.get(f).centroid().toArray(Fraction[]::new)));
		var field = new LiquidConformingVelocity(mesh, rates, mean);
		for (var node : field.velocity.entrySet()) if (!node.getValue().equals(List.of(affine(node.getKey().coordinates()))))
			throw new AssertionError("Continuous velocity lost affine completeness at " + node.getKey());
		requireIntegralConstraints(mesh, field, rates, mean);
	}
	private static Fraction[] affine(Fraction[] x) {
		return new Fraction[]{x[0].multiply(Fraction.of(0.125)).add(x[1].multiply(Fraction.of(0.25))).add(Fraction.of(0.5)),
				x[0].multiply(Fraction.of(-0.25)).add(x[2].multiply(Fraction.of(0.125))).subtract(Fraction.of(0.25)),
				x[2].multiply(Fraction.of(-0.125)).add(Fraction.of(0.125))};
	}
	private static void arbitraryProjectedRatesPreserveMeansAndWalls() {
		var cells = LiquidGeometryRefinement.children(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1)));
		var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var input = new double[3 * cells.size()]; for (int i = 0; i < input.length; i++) input[i] = Math.sin(i + 0.5);
		var projected = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(input, new LiquidPolyhedron.Point(0, 0, 0), 0.01, 1e-10, 512);
		var field = new LiquidConformingVelocity(mesh, projected.volumeRate(), projected.cellVelocity());
		requireIntegralConstraints(mesh, field, projected.volumeRate(), projected.cellVelocity());
		var forces = new java.util.LinkedHashMap<LiquidExactPolygon.Vertex, List<Fraction>>(); var power = Fraction.ZERO;
		for (var node : field.velocity.entrySet()) {
			var x = node.getKey().coordinates(); var force = List.of(x[0].multiply(x[0]).subtract(x[1]), x[2].add(x[0]), x[1].multiply(x[2]));
			forces.put(node.getKey(), force); power = power.add(dot(force, node.getValue().toArray(Fraction[]::new)));
		}
		var load = field.load(forces); var actual = Fraction.ZERO;
		for (int i = 0; i < load.cellForce().size(); i++) actual = actual.add(load.cellForce().get(i).multiply(Fraction.of(projected.cellVelocity()[i])));
		for (int f = 0; f < mesh.faces.size(); f++) actual = actual.add(load.faceForce().get(f).multiply(projected.volumeRate()[f]));
		if (!actual.equals(power) || load.prescribedPower().signum() != 0) throw new AssertionError("Continuous force adjoint lost work at cell, face or wall nodes");
		for (int f = 0; f < mesh.faces.size(); f++) if (mesh.faces.get(f).second() == -2) {
			var face = mesh.faces.get(f); var points = new ArrayList<>(mesh.exactPolygons.get(f).vertices()); points.add(field.faceCenters.get(f));
			for (var point : points) if (dot(face.areaVector(), field.velocity.get(point).toArray(Fraction[]::new)).signum() != 0)
				throw new AssertionError("Continuous velocity penetrates a fixed wall");
		}
		var noSlip = new LiquidConformingVelocity(mesh, projected.volumeRate(), projected.cellVelocity(), new int[cells.size()], FluidSolverConfig.WallCondition.NO_SLIP);
		requireIntegralConstraints(mesh, noSlip, projected.volumeRate(), projected.cellVelocity());
		for (int f = 0; f < mesh.faces.size(); f++) if (mesh.faces.get(f).second() == -2) {
			var points = new ArrayList<>(mesh.exactPolygons.get(f).vertices()); points.add(noSlip.faceCenters.get(f));
			for (var point : points) for (var value : noSlip.velocity.get(point)) if (value.signum() != 0) throw new AssertionError("No-slip continuous wall retained tangential motion");
		}
	}
	private static void requireIntegralConstraints(LiquidControlVolumeMesh mesh, LiquidConformingVelocity field, Fraction[] rates, double[] mean) {
		var flux = new Fraction[mesh.faces.size()]; Arrays.fill(flux, Fraction.ZERO);
		var momentum = new Fraction[mesh.cells.size()][3]; for (var row : momentum) Arrays.fill(row, Fraction.ZERO);
		for (var tet : field.tetrahedra) {
			var u = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
			for (var point : tet.vertices()) for (int a = 0; a < 3; a++) u[a] = u[a].add(field.velocity.get(point).get(a));
			for (int a = 0; a < 3; a++) momentum[tet.cell()][a] = momentum[tet.cell()][a].add(u[a].multiply(tet.volume()).divide(Fraction.of(4)));
			if (mesh.faces.get(tet.face()).first() == tet.cell()) {
				var polygon = LiquidExactPolygon.tetrahedron(tet.vertices()).getFirst();
				var trace = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
				for (int v = 1; v < 4; v++) for (int a = 0; a < 3; a++) trace[a] = trace[a].add(field.velocity.get(tet.vertices().get(v)).get(a));
				flux[tet.face()] = flux[tet.face()].add(dot(List.of(polygon.areaVector()), trace).divide(Fraction.of(3)));
			}
		}
		if (!Arrays.equals(flux, rates)) throw new AssertionError("Continuous velocity changed integrated shared face rates");
		for (int c = 0; c < mesh.cells.size(); c++) for (int a = 0; a < 3; a++)
			if (!momentum[c][a].equals(mesh.cells.get(c).geometry().measure().volume().multiply(Fraction.of(mean[3 * c + a]))))
				throw new AssertionError("Continuous velocity changed cell mean momentum");
	}
	private static void continuousShearPreservesExteriorAreaAndWork() {
		List<LiquidGeometryRefinement.Cell> cells = List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75)));
		for (int l = 0; l < 3; l++) cells = cells.stream().flatMap(c -> LiquidGeometryRefinement.children(c).stream()).toList();
		var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var mean = new double[3 * cells.size()]; var rates = new Fraction[mesh.faces.size()];
		for (int c = 0; c < cells.size(); c++) mean[3 * c] = 0.125 * (cells.get(c).geometry().centroid().y() - 0.5);
		for (int f = 0; f < rates.length; f++) {
			var face = mesh.faces.get(f); rates[f] = face.areaVector().getFirst().multiply(face.centroid().get(1).subtract(Fraction.of(0.5))).multiply(Fraction.of(0.125));
		}
		var field = new LiquidConformingVelocity(mesh, rates, mean); requireIntegralConstraints(mesh, field, rates, mean);
		var initial = exterior(mesh, field, 0); var power = Fraction.ZERO;
		for (var node : initial.force.entrySet()) for (int a = 0; a < 3; a++) power = power.subtract(node.getValue().get(a).multiply(field.velocity.get(node.getKey()).get(a)));
		if (power.signum() != 0) throw new AssertionError("Initial smooth shear has spurious surface work");
		double previous = Double.POSITIVE_INFINITY;
		for (double dt : new double[]{1.0 / 128, 1.0 / 256}) {
			var motion = new LiquidConformingTransport(mesh, field, rates, dt); var boundary = new ArrayList<LiquidExactPolygon>();
			for (var image : motion.images) if (mesh.faces.get(image.face()).second() < 0) boundary.add(image.geometry().faces().getFirst());
			var sigma = new double[boundary.size()]; Arrays.fill(sigma, 0.125);
			var after = new LiquidSurfaceEnergy(boundary, sigma); double actual = after.joules - initial.joules; double x = 0.125 * dt;
			double expected = 0.0625 * x * x / (Math.sqrt(1 + x * x) + 1);
			if (Math.abs(actual - expected) > 4 * Math.ulp(initial.joules)) throw new AssertionError("Shared shear created nonphysical exterior area: " + actual + " / " + expected);
			if (actual / dt > 0.501 * previous) throw new AssertionError("Surface work error did not converge with time refinement"); previous = actual / dt;
			var volume = Fraction.ZERO;
			for (var image : motion.images) volume = volume.add(image.geometry().measure().volume());
			if (!volume.equals(Fraction.of(0.125))) throw new AssertionError("Continuous affine shear changed liquid volume");
		}
	}
	private static LiquidSurfaceEnergy exterior(LiquidControlVolumeMesh mesh, LiquidConformingVelocity field, double dt) {
		var faces = new ArrayList<LiquidExactPolygon>();
		for (var tet : field.tetrahedra) if (mesh.faces.get(tet.face()).second() < 0)
			faces.add(LiquidExactPolygon.tetrahedron(tet.vertices().stream().map(p -> moved(field, p, dt)).toList()).getFirst());
		var sigma = new double[faces.size()]; Arrays.fill(sigma, 0.125); return new LiquidSurfaceEnergy(faces, sigma);
	}
	private static LiquidExactPolygon.Vertex moved(LiquidConformingVelocity field, LiquidExactPolygon.Vertex point, double dt) {
		var x = point.coordinates(); for (int a = 0; a < 3; a++) x[a] = x[a].add(field.velocity.get(point).get(a).multiply(Fraction.of(dt))); return LiquidExactPolygon.vertex(x);
	}
	private static Fraction dot(List<Fraction> a, Fraction[] b) { var value = Fraction.ZERO; for (int i = 0; i < 3; i++) value = value.add(a.get(i).multiply(b[i])); return value; }
	public static void main(String[] args) { validate(); System.out.println("Continuous velocity, shared moments and shear surface work validation passed"); }
}
