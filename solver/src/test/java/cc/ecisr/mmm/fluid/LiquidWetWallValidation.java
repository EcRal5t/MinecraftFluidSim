package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Signed Young energy, symmetric pressure pullback, wall contact and finite-step conservation. */
final class LiquidWetWallValidation {
	static void validate() {
		var coarse = new LiquidControlVolumeMesh(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75))), 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var q = new Fraction[coarse.faces.size()]; Arrays.fill(q, Fraction.ZERO); var coefficient = new double[q.length]; Arrays.fill(coefficient, 0.125);
		try { new LiquidConformingVelocity(coarse, q, new double[3]).requireSurfaceResolution(coefficient); throw new AssertionError("One cell admitted unresolved capillary deformation"); }
		catch (LiquidInterfaceResolutionRequired expected) { if (!Arrays.equals(expected.cells(), new int[]{0})) throw new AssertionError("Refinement lost actual liquid support"); }
		for (double wallCoefficient : new double[]{-0.0625, 0.0625}) validate(wallCoefficient);
	}
	private static void validate(double wallCoefficient) {
		var cells = LiquidGeometryRefinement.children(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.25, 0, 0.25, 0.75, 0.625, 0.75)));
		var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(s.centroid().y() == 0, 0));
		var rate = new Fraction[mesh.faces.size()]; Arrays.fill(rate, Fraction.ZERO);
		var field = new LiquidConformingVelocity(mesh, rate, new double[3 * cells.size()]);
		var walls = field.tetrahedra.stream().filter(t -> mesh.faces.get(t.face()).second() == -2).map(t -> LiquidExactPolygon.tetrahedron(t.vertices()).getFirst()).toList();
		var wallValues = new double[walls.size()]; Arrays.fill(wallValues, wallCoefficient);
		var wall = new LiquidWetWallEnergy(walls, wallValues); var original = new LinkedHashMap<Vertex, List<Fraction>>(); var moved = new LinkedHashMap<Vertex, List<Fraction>>();
		for (var node : field.velocity.keySet()) {
			var x = node.coordinates(); original.put(node, List.of(x));
			moved.put(node, List.of(x[0].multiply(Fraction.of(1.125)), x[1], x[2].multiply(Fraction.of(0.875))));
		}
		var actual = new LiquidAreaEnergy(walls, wallValues);
		close(wall.energy(original).rounded(), actual.joules, 2 * Math.ulp(actual.joules), "Signed wall energy changed area");
		for (var entry : wall.gradient(original).entrySet()) for (int a = 0; a < 3; a++)
			if (entry.getValue().get(a).add(actual.force.get(entry.getKey()).get(a)).signum() != 0) throw new AssertionError("Young wall force differs from actual area gradient");
		var movedPolygons = walls.stream().map(p -> LiquidExactPolygon.restored(p.vertices().stream().map(v -> LiquidExactPolygon.vertex(moved.get(v).toArray(Fraction[]::new))).toList(), p.coefficients())).toList();
		close(wall.energy(moved).rounded(), new LiquidAreaEnergy(movedPolygons, wallValues).joules, 2 * Math.ulp(actual.joules), "Wall quadratic changed displaced area");
		var signed = new LiquidPressureFaceEnergy(field, new LiquidAreaEnergy(List.of(), new double[0]), new LiquidSurfaceMetric(List.of(), new double[0]), wall);
		var q = new double[rate.length]; var r = new double[rate.length];
		for (int f = 0; f < q.length; f++) if (mesh.faces.get(f).second() != -2) { q[f] = (f % 5 - 2) * 0.125; r[f] = (f % 7 - 3) * 0.0625; }
		var hq = new double[q.length]; var hr = new double[q.length]; signed.add(q, null, 1, false, hq, null); signed.add(r, null, 1, false, hr, null);
		double rq = 0, qr = 0, energy = 0;
		for (int f = 0; f < q.length; f++) { rq += r[f] * hq[f]; qr += q[f] * hr[f]; energy += 0.5 * q[f] * hq[f]; }
		close(rq, qr, 1e-12, "Signed wall pullback lost symmetry");
		var exactQ = Arrays.stream(q).mapToObj(Fraction::of).toArray(Fraction[]::new);
		LiquidConformingVelocityValidation.requireSurfaceActionCertificate(signed, field.rateLoad(wall.gradient(field.rateVelocity(exactQ))), rate, q);
		close(energy, wall.energy(field.rateVelocity(exactQ)).rounded(), 1e-12, "Signed wall pressure metric changed nodal work");
		var sigma = new double[rate.length]; for (int f = 0; f < sigma.length; f++) if (mesh.faces.get(f).second() < 0) sigma[f] = mesh.faces.get(f).second() == -2 ? wallCoefficient : 0.125;
		field.requireSurfaceResolution(sigma);
		var surface = new LiquidGeometricSurfaceEnergy(field, sigma); var projection = new LiquidControlVolumeProjection(mesh.cells, mesh.faces);
		var gradients = ControlVolumeGradient.build(mesh.cells, mesh.faces);
		var metrics = java.util.Arrays.stream(gradients).map(gradient -> new ControlVolumeFluxMetric(gradient, 1000)).toArray(ControlVolumeFluxMetric[]::new);
		double relative = surface.pressure.relativeCurvatureBound(metrics);
		var physicalWalls = java.util.stream.IntStream.range(0, mesh.faces.size()).filter(f -> mesh.faces.get(f).second() == -2).mapToObj(mesh.exactPolygons::get).toList();
		var physicalValues = new double[physicalWalls.size()]; Arrays.fill(physicalValues, wallCoefficient);
		var uncutWall = new LiquidWetWallEnergy(physicalWalls, physicalValues);
		var uncut = new LiquidPressureFaceEnergy(field, new LiquidAreaEnergy(List.of(), new double[0]), new LiquidSurfaceMetric(List.of(), new double[0]), uncutWall);
		if (uncut.relativeCurvatureBound(metrics) != signed.relativeCurvatureBound(metrics)) throw new AssertionError("Auxiliary wall triangles changed the curvature certificate");
		var rateEnergy = Fraction.ZERO; var observation = Fraction.ZERO;
		for (var metric : metrics) {
			var local = new double[metric.geometry.indices.length];
			for (int i = 0; i < local.length; i++) local[i] = metric.geometry.sign[i] * q[metric.geometry.indices[i]];
			var action = metric.multiply(local); var errors = metric.evaluationError(action, new double[local.length]);
			for (int i = 0; i < local.length; i++) {
				rateEnergy = rateEnergy.add(Fraction.of(local[i]).multiply(Fraction.of(action[i])));
				observation = observation.add(Fraction.of(Math.abs(local[i])).multiply(Fraction.of(errors[i])));
			}
		}
		if (Fraction.of(Math.abs(2 * energy)).compareTo(Fraction.of(signed.relativeCurvatureBound(metrics)).multiply(rateEnergy.subtract(observation))) > 0)
			throw new AssertionError("Signed wall energy escaped its relative physical inertia bound");
		double excessiveDt = 2 / Math.sqrt(relative);
		try { projection.project(new double[3 * cells.size()], new LiquidPolyhedron.Point(0, 0, 0), excessiveDt, 1e-11, 512, surface.pressure); throw new AssertionError("Indefinite wet-wall step was admitted"); }
		catch (LiquidCharacteristicTimeResolutionRequired expected) {
			int required = expected.minimumSubsteps(excessiveDt, excessiveDt);
			var interval = Fraction.of(excessiveDt / required);
			if (required < 2 || interval.multiply(interval).multiply(Fraction.of(relative)).compareTo(Fraction.of(1)) >= 0)
				throw new AssertionError("Certified wet-wall interval did not request sufficient time resolution");
			if (new LiquidCharacteristicTimeResolutionRequired("Unrepresentable work count", 1e-300).minimumSubsteps(1, 1) != Integer.MAX_VALUE)
				throw new AssertionError("Geometric time resolution overflowed its finite work count");
		}
		for (double dt : new double[]{1.0 / 128, 1.0 / 64}) {
			var result = projection.project(new double[3 * cells.size()], new LiquidPolyhedron.Point(0, 0, 0), dt, 1e-11, 512, surface.pressure);
			var motionField = new LiquidConformingVelocity(mesh, result.volumeRate(), result.cellVelocity());
			var motion = new LiquidConformingTransport(mesh, motionField, result.volumeRate(), dt);
			var exterior = motion.images.stream().filter(i -> sigma[i.face()] != 0).toList();
			double after = new LiquidAreaEnergy(exterior.stream().map(i -> i.geometry().faces().getFirst()).toList(), exterior.stream().mapToDouble(i -> sigma[i.face()]).toArray()).joules;
			double kinetic = 0; var volume = Fraction.ZERO;
			for (var image : motion.images) volume = volume.add(image.geometry().measure().volume());
			for (int c = 0; c < cells.size(); c++) for (int a = 0; a < 3; a++) kinetic += 0.5 * 1000 * mesh.cells.get(c).geometry().moments().volume() * Math.pow(result.cellVelocity()[3 * c + a], 2);
			if (!(kinetic > 0 && kinetic + after < surface.area.joules) || !volume.equals(Fraction.of(0.15625))) throw new AssertionError("Wet-wall step lost energy or exact liquid volume");
			for (var node : motionField.velocity.entrySet()) if (node.getKey().coordinates()[1].signum() == 0 && node.getValue().get(1).signum() != 0) throw new AssertionError("Wetting penetrated stationary wall");
		}
	}
	private static void close(double actual, double expected, double tolerance, String message) {
		if (!(Math.abs(actual - expected) <= tolerance)) throw new AssertionError(message + ": " + actual + " vs " + expected);
	}
}
