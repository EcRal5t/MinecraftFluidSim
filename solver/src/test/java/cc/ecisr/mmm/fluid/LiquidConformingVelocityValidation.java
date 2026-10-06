package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared full velocity traces, exact moments and surface work under continuous shear. */
final class LiquidConformingVelocityValidation {
	static void validate() {
		stationaryRationalGeometrySurvivesMotionRounding();
		affineFieldSurvivesAdaptiveFaces();
		supportedAffineFieldsSurviveObliqueCells();
		certifiedAffineFitsContainExactLeastSquares();
		separatedLiquidsKeepIndependentVelocityTraces();
		arbitraryProjectedRatesPreserveMeansAndWalls();
		continuousShearPreservesExteriorAreaAndWork();
		spaceTimeSweepsPreserveVolumeAndSharedFaces();
		capillaryLoadsShareMechanicalInertiaAndSurfaceWork();
		implicitCapillarityUsesActualPhaseOrdering();
		LiquidWetWallValidation.validate();
	}
	private static void stationaryRationalGeometrySurvivesMotionRounding() {
		var shape = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(1, 2, 3, 1);
		var mesh = new LiquidControlVolumeMesh(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, shape)), 1, 1000,
				(c, face) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var rates = new Fraction[mesh.faces.size()]; Arrays.fill(rates, Fraction.ZERO);
		var field = new LiquidConformingVelocity(mesh, rates, new double[3]);
		var motion = new LiquidConformingTransport(mesh, field, rates, 1.0 / 128);
		for (var entry : motion.positions.entrySet()) if (!entry.getKey().equals(entry.getValue()))
			throw new AssertionError("Binary64 increments changed stationary rational geometry");
		if (motion.maximumPositionRoundoffMetres != 0) throw new AssertionError("Stationary geometry acquired a motion roundoff bound");
	}
	private static void implicitCapillarityUsesActualPhaseOrdering() {
		var cells = LiquidGeometryRefinement.children(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.625, 0.75)));
		var state = LiquidGeometryStateMapper.capture(cells, 1, new int[]{0}, new int[cells.size()]);
		var domain = new LiquidGeometryDomainPartition(2, 2, 2, 0.5F, new byte[8], cells, 256);
		var input = new LiquidGeometryPhaseInput(state, domain, false, 256);
		var order = java.util.stream.IntStream.range(0, input.regions.size()).boxed().sorted(java.util.Comparator.comparingInt(c -> input.fragments[c] >= 0 ? 1 : 0)).toList();
		var regions = order.stream().map(input.regions::get).toList(); var liquid = order.stream().mapToInt(c -> input.fragments[c]).toArray();
		var mesh = new LiquidControlVolumeMesh(regions, 1, 1000, (c, face) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var density = new double[liquid.length]; Arrays.fill(density, 1000); var nodes = new int[liquid.length]; Arrays.fill(nodes, -1);
		var sigma = new double[mesh.faces.size()];
		for (int f = 0; f < sigma.length; f++) {
			var face = mesh.faces.get(f); if (face.second() >= 0 && (liquid[face.first()] >= 0) != (liquid[face.second()] >= 0)) sigma[f] = 0.125;
		}
		var rate = new Fraction[sigma.length]; Arrays.fill(rate, Fraction.ZERO);
		var reference = new LiquidConformingVelocity(mesh, rate, new double[3 * liquid.length], liquid);
		var capillary = new LiquidConformingCapillarity(reference, sigma, density);
		var surfaces = reference.tetrahedra.stream().filter(t -> sigma[t.face()] > 0).map(t -> LiquidExactPolygon.tetrahedron(t.vertices()).getFirst()).toList();
		var coefficients = new double[surfaces.size()]; Arrays.fill(coefficients, 0.125);
		var surface = new LiquidPressureFaceEnergy(reference, capillary.energy, new LiquidSurfaceMetric(surfaces, coefficients));
		var mechanics = new LiquidPhaseMechanics(mesh, liquid, density, new double[liquid.length], List.of(), nodes, (f, gas) -> 0, FluidSolverConfig.WallCondition.FREE_SLIP);
		var result = mechanics.advance(new double[3 * liquid.length], new double[3 * mesh.faces.size()], new double[3 * liquid.length],
				1.0 / 128, 1e-11, 1e-11, 512, surface);
		var motion = new LiquidConformingTransport(mesh, result.conformingVelocity(), result.extension().volumeRate(), 1.0 / 128, liquid);
		var after = motion.images.stream().filter(i -> sigma[i.face()] > 0).map(i -> i.geometry().faces().getFirst()).toList();
		double kinetic = 0; var momentum = new double[3]; var volume = Fraction.ZERO;
		for (var image : motion.images) volume = volume.add(image.geometry().measure().volume());
		for (int c = 0; c < liquid.length; c++) if (liquid[c] >= 0) for (int a = 0; a < 3; a++) {
			double mass = density[c] * mesh.cells.get(c).geometry().moments().volume(), u = result.extension().cellVelocity()[3 * c + a];
			kinetic += 0.5 * mass * u * u; momentum[a] += mass * u;
		}
		if (!(kinetic > 0 && kinetic + new LiquidAreaEnergy(after, coefficients).joules < capillary.energy.joules)
				|| !volume.equals(Fraction.of(0.09375))) throw new AssertionError("Implicit capillarity lost phase geometry, inertia or energy");
		for (int a = 0; a < 3; a++) {
			double impulse = result.pressure().boundaryImpulse().coordinate(a) + result.pressure().internalInterfaceImpulse().coordinate(a);
			if (Math.abs(momentum[a] - impulse) > result.pressure().momentumStationarityBound().coordinate(a))
				throw new AssertionError("Gas-first capillary momentum exceeds the pressure stationarity error: " + Arrays.toString(momentum));
			if (Math.abs(impulse) > 1e-14) throw new AssertionError("Closed capillary forces have nonzero resultant");
		}
	}
	private static void capillaryLoadsShareMechanicalInertiaAndSurfaceWork() {
		var cells = LiquidGeometryRefinement.children(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.625, 0.75)));
		var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var zeroRate = new Fraction[mesh.faces.size()]; Arrays.fill(zeroRate, Fraction.ZERO);
		var density = new double[cells.size()]; Arrays.fill(density, 1000);
		var sigma = new double[mesh.faces.size()]; for (int f = 0; f < sigma.length; f++) if (mesh.faces.get(f).second() < 0) sigma[f] = 0.125;
		var reference = new LiquidConformingVelocity(mesh, zeroRate, new double[3 * cells.size()]);
		var capillary = new LiquidConformingCapillarity(reference, sigma, density);
		var surface = reference.tetrahedra.stream().filter(t -> mesh.faces.get(t.face()).second() < 0).map(t -> LiquidExactPolygon.tetrahedron(t.vertices()).getFirst()).toList();
		var coefficients = new double[surface.size()]; Arrays.fill(coefficients, 0.125); var metric = new LiquidSurfaceMetric(surface, coefficients);
		var positions = new java.util.LinkedHashMap<LiquidExactPolygon.Vertex, List<Fraction>>();
		reference.velocity.keySet().forEach(point -> positions.put(point, List.of(point.coordinates())));
		if (Math.abs(metric.energy(positions).rounded() - capillary.energy.joules) > 2 * Math.ulp(capillary.energy.joules)) throw new AssertionError("Reference metric changed geometric surface energy");
		for (var entry : metric.gradient(positions).entrySet()) for (int a = 0; a < 3; a++)
			if (entry.getValue().get(a).add(capillary.energy.force.get(entry.getKey()).get(a)).absolute().rounded() > 1e-14)
				throw new AssertionError("Implicit surface metric changed the initial area force");
		var translation = new java.util.LinkedHashMap<LiquidExactPolygon.Vertex, List<Fraction>>();
		positions.keySet().forEach(point -> translation.put(point, List.of(Fraction.of(0.125), Fraction.of(-0.25), Fraction.of(0.5))));
		if (metric.energy(translation).signum() != 0) throw new AssertionError("Implicit surface metric damps rigid translation");
		var implicit = new LiquidPressureFaceEnergy(reference, capillary.energy, metric);
		var trial = new double[zeroRate.length]; for (int f = 0; f < trial.length; f++) trial[f] = (f % 7 - 3) * 0.03125;
		var trialExact = Arrays.stream(trial).mapToObj(Fraction::of).toArray(Fraction[]::new);
		requireSurfaceActionCertificate(implicit, reference.rateLoad(metric.gradient(reference.rateVelocity(trialExact))), reference.rateLoad(capillary.energy.force), trial);
		for (double dt : new double[]{1.0 / 128, 4}) {
			var projected = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[3 * cells.size()],
					new LiquidPolyhedron.Point(0, 0, 0), dt, 1e-11, 512, implicit);
			var field = new LiquidConformingVelocity(mesh, projected.volumeRate(), projected.cellVelocity());
			var motion = new LiquidConformingTransport(mesh, field, projected.volumeRate(), dt);
			var boundary = motion.images.stream().filter(i -> mesh.faces.get(i.face()).second() < 0).map(i -> i.geometry().faces().getFirst()).toList();
			double after = new LiquidAreaEnergy(boundary, coefficients).joules, kinetic = 0; var momentum = new double[3];
			for (int c = 0; c < cells.size(); c++) for (int a = 0; a < 3; a++) {
				double mass = density[c] * mesh.cells.get(c).geometry().moments().volume(), u = projected.cellVelocity()[3 * c + a];
				kinetic += 0.5 * mass * u * u; momentum[a] += mass * u;
			}
			if (!(kinetic > 0 && kinetic + after <= capillary.energy.joules)) throw new AssertionError("Implicit capillary pressure injected mechanical energy");
			for (double component : momentum) if (Math.abs(component) > 1e-10) throw new AssertionError("Implicit capillary pressure changed total momentum");
			double accounted = projected.boundaryWorkJoules() + projected.internalInterfaceWorkJoules() - projected.pressureDissipationJoules();
			if (Math.abs(kinetic - accounted) > 1e-10) throw new AssertionError("Implicit capillary pressure lost its mechanical work");
		}
		// The mimetic inverse maps a constant acceleration to its exact cell-force virtual work.
		var gradients = ControlVolumeGradient.build(mesh.cells, mesh.faces);
		for (int c = 0; c < cells.size(); c++) {
			var g = gradients[c]; var fluxMetric = new ControlVolumeFluxMetric(g, density[c]); var rate = new double[g.indices.length];
			for (int i = 0; i < rate.length; i++) for (int a = 0; a < 3; a++) rate[i] += g.exactBasis[i][a].rounded() * g.volume * capillary.acceleration[3 * c + a];
			var dual = fluxMetric.multiply(rate);
			for (int i = 0; i < rate.length; i++) {
				double expected = 0; for (int a = 0; a < 3; a++) expected += g.displacement[i].coordinate(a) * capillary.load.cellForce().get(3 * c + a).rounded() / g.volume;
				if (Math.abs(dual[i] - expected) > 1e-12) throw new AssertionError("Capillary acceleration and pressure use different inertia");
			}
		}
		var liquid = new int[cells.size()]; var gas = new int[cells.size()]; Arrays.fill(gas, -1);
		double previous = Double.POSITIVE_INFINITY;
		for (double dt : new double[]{1.0 / 128, 1.0 / 256}) {
			var mechanics = new LiquidPhaseMechanics(mesh, liquid, density, new double[cells.size()], List.of(), gas, (f, c) -> 0,
					FluidSolverConfig.WallCondition.FREE_SLIP, capillary.pressureJump);
			var result = mechanics.advance(new double[3 * cells.size()], new double[3 * mesh.faces.size()], capillary.acceleration, dt, 1e-11, 1e-11, 512);
			var field = result.conformingVelocity(); var motion = new LiquidConformingTransport(mesh, field, result.extension().volumeRate(), dt);
			var faces = new ArrayList<LiquidExactPolygon>();
			for (var image : motion.images) if (mesh.faces.get(image.face()).second() < 0) faces.add(image.geometry().faces().getFirst());
			var after = new LiquidAreaEnergy(faces, coefficients);
			var moved = new java.util.LinkedHashMap<LiquidExactPolygon.Vertex, List<Fraction>>(); motion.positions.forEach((point, next) -> moved.put(point, List.of(next.coordinates())));
			if (metric.energy(moved).rounded() + 4 * Math.ulp(after.joules) < after.joules) throw new AssertionError("Implicit reference metric failed to bound actual transported area");
			if (metric.energy(field.velocity).signum() < 0) throw new AssertionError("Implicit capillary operator has negative deformation energy");
			double change = after.joules - capillary.energy.joules, power = 0, kinetic = 0;
			var momentum = new double[3];
			for (int c = 0; c < cells.size(); c++) for (int a = 0; a < 3; a++) {
				double u = result.extension().cellVelocity()[3 * c + a], mass = density[c] * mesh.cells.get(c).geometry().moments().volume();
				power += capillary.load.cellForce().get(3 * c + a).rounded() * u; kinetic += 0.5 * mass * u * u; momentum[a] += mass * u;
			}
			for (int f = 0; f < sigma.length; f++) power += capillary.load.faceForce().get(f).rounded() * result.extension().volumeRate()[f].rounded();
			for (double component : momentum) if (Math.abs(component) > 1e-12) throw new AssertionError("Closed capillary surface changed total momentum");
			if (!(kinetic > 0 && change < 0 && kinetic + change < 0 && power > 0)) throw new AssertionError("Area force failed to relax a resolved free surface");
			double error = Math.abs(change + dt * power);
			if (!(error < previous / 3)) throw new AssertionError("Capillary surface work fails time convergence"); previous = error;
		}
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
		var measures = new LiquidExactPolygon.Measure[cells.size()]; Arrays.fill(measures, LiquidConvexRegion.EMPTY.measure());
		var boundaries = new ArrayList<ArrayList<LiquidExactPolygon>>(); for (int c = 0; c < cells.size(); c++) boundaries.add(new ArrayList<>());
		var paired = new java.util.HashMap<List<LiquidExactPolygon.Vertex>, Integer>();
		for (int i = 0; i < motion.images.size(); i++) {
			var image = motion.images.get(i); var source = field.tetrahedra.get(i);
			var mapped = LiquidConvexRegion.ofFaces(LiquidExactPolygon.tetrahedron(source.vertices())).transformed(image.affineMap());
			if (!new java.util.HashSet<>(mapped.faces().stream().map(LiquidExactPolygon::vertexKey).toList())
					.equals(new java.util.HashSet<>(image.geometry().faces().stream().map(LiquidExactPolygon::vertexKey).toList())))
				throw new AssertionError("Continuous affine image map differs from transported vertices");
			volume[image.cell()] = volume[image.cell()].add(image.geometry().measure().volume());
			measures[image.cell()] = measures[image.cell()].add(image.geometry().measure()); boundaries.get(image.cell()).add(image.geometry().faces().getFirst());
			if (mesh.faces.get(image.face()).second() >= 0) paired.merge(image.geometry().faces().getFirst().vertexKey(), 1, Integer::sum);
		}
		for (var v : volume) if (!v.equals(Fraction.of(0.125))) throw new AssertionError("Finite-step incompressible transport changed cell volume");
		for (int c = 0; c < cells.size(); c++) if (!measures[c].same(LiquidExactPolygon.measure(boundaries.get(c))))
			throw new AssertionError("Auxiliary fan apex changed exterior volume or spatial moments");
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
		requireAffineField(mesh, LiquidConformingVelocityValidation::affine);
	}
	private static void supportedAffineFieldsSurviveObliqueCells() {
		for (int rank = 1; rank <= 2; rank++) {
			var cells = new ArrayList<LiquidGeometryRefinement.Cell>();
			for (int x = 0; x < 2; x++) for (int y = 0; y < rank; y++) {
				var box = LiquidPolyhedron.box(0.5 * x, 0.5 * y, 0, 0.5 * (x + 1), 0.5 * (y + 1), 0.5);
				var shape = box.transformed(new double[]{1, 0, 0, 0.25, 1, 0, 0.5, -0.25, 1}, new LiquidPolyhedron.Point(0, 0, 0), new LiquidPolyhedron.Point(0, 0, 0));
				cells.add(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, shape));
			}
			int span = rank;
			var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			requireAffineField(mesh, point -> new Fraction[]{point[0].add(point[1].multiply(Fraction.of(0.25))).add(point[2].multiply(Fraction.of(0.5))).multiply(Fraction.of(0.125)),
					span == 2 ? point[1].subtract(point[2].multiply(Fraction.of(0.25))).multiply(Fraction.of(0.125)) : Fraction.ZERO, Fraction.of(0.25)});
			var sealed = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
			var rates = new Fraction[sealed.faces.size()]; Arrays.fill(rates, Fraction.ZERO);
			var fixed = new LiquidConformingVelocity(sealed, rates, new double[3 * cells.size()]);
			var forces = new java.util.LinkedHashMap<LiquidExactPolygon.Vertex, List<Fraction>>();
			fixed.velocity.keySet().forEach(point -> forces.put(point, List.of(point.coordinates()))); requireObservedRateLoad(fixed, forces);
		}
	}
	private static void requireAffineField(LiquidControlVolumeMesh mesh, java.util.function.Function<Fraction[], Fraction[]> affine) {
		var mean = new double[3 * mesh.cells.size()]; var rates = new Fraction[mesh.faces.size()];
		for (int c = 0; c < mesh.cells.size(); c++) {
			var u = affine.apply(mesh.cells.get(c).geometry().centroidCoordinates()); for (int a = 0; a < 3; a++) mean[3 * c + a] = u[a].rounded();
		}
		for (int f = 0; f < rates.length; f++) rates[f] = dot(mesh.faces.get(f).areaVector(), affine.apply(mesh.faces.get(f).centroid().toArray(Fraction[]::new)));
		var field = new LiquidConformingVelocity(mesh, rates, mean);
		requireObservedRateLoad(field, field.velocity);
		for (var node : field.velocity.entrySet()) if (!node.getValue().equals(List.of(affine.apply(node.getKey().coordinates()))))
			throw new AssertionError("Continuous velocity lost affine completeness at " + node.getKey());
		requireIntegralConstraints(mesh, field, rates, mean);
	}
	private static Fraction[] affine(Fraction[] x) {
		return new Fraction[]{x[0].multiply(Fraction.of(0.125)).add(x[1].multiply(Fraction.of(0.25))).add(Fraction.of(0.5)),
				x[0].multiply(Fraction.of(-0.25)).add(x[2].multiply(Fraction.of(0.125))).subtract(Fraction.of(0.25)),
				x[2].multiply(Fraction.of(-0.125)).add(Fraction.of(0.125))};
	}
	private static void certifiedAffineFitsContainExactLeastSquares() {
		for (int pattern = 0; pattern < 6; pattern++) {
			int rank = Math.min(pattern, 3); var small = new Fraction(java.math.BigInteger.ONE, java.math.BigInteger.ONE.shiftLeft(80));
			var samples = new Fraction[8][rank]; var target = new Fraction[rank];
			var centers = new ArrayList<Fraction[]>(); var weights = new ArrayList<Fraction>();
			for (int a = 0; a < rank; a++) target[a] = rational(1, 2 * a + 7);
			for (int i = 0; i < samples.length; i++) {
				for (int a = 0; a < rank; a++) samples[i][a] = rank == 1 ? rational(i, 21) : rational(i >>> a & 1, 2 * a + 3);
				if (pattern == 4 && i > 1 && i < 4) samples[i] = new Fraction[]{rational(1, 3), small, i == 3 ? small : Fraction.ZERO};
				if (pattern == 5) samples[i][2] = samples[i][2].multiply(small);
				centers.add(obliqueCoordinates(samples[i])); weights.add(rational(i + 1, i + 7));
			}
			if (pattern == 5) target[2] = target[2].multiply(small);
			var point = LiquidExactPolygon.vertex(obliqueCoordinates(target)); var fit = new LiquidAffineFit(centers, weights);
			var exact = leastSquaresWeights(samples, weights, target); var sum = Fraction.ZERO; var moment = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO}; var errors = fit.weightErrors(point);
			for (int i = 0; i < samples.length; i++) {
				var weight = fit.shape[i].evaluate(point); double error = errors[i];
				if (weight.subtract(exact[i]).absolute().subtract(Fraction.of(error)).signum() > 0 || !(error < Math.ulp((float) exact[i].rounded())))
					throw new AssertionError("Certified affine fit lost its exact least-squares reference or observation precision, rank=" + rank);
				sum = sum.add(weight); for (int a = 0; a < 3; a++) moment[a] = moment[a].add(weight.multiply(centers.get(i)[a]));
			}
			if (!sum.equals(Fraction.of(1)) || !Arrays.equals(moment, point.coordinates())) throw new AssertionError("Certified affine completion lost exact coordinate moments");
			if (!fit.contains(point)) throw new AssertionError("Affine support certificate rejected an exact supported point");
			var outside = point.coordinates(); outside[2] = outside[2].add(Fraction.of(0.125));
			if (fit.contains(LiquidExactPolygon.vertex(outside)) != (rank == 3)) throw new AssertionError("Affine support certificate changed the exact spatial span");
		}
		var common = java.math.BigInteger.ONE.shiftLeft(8192);
		for (var value : List.of(rational(1, 3), rational(-1, 7), Fraction.of(Double.MIN_VALUE), new Fraction(java.math.BigInteger.ONE.negate(), java.math.BigInteger.ONE.shiftLeft(1075))))
			if (Double.doubleToRawLongBits(value.rounded()) != Double.doubleToRawLongBits(LiquidExactPolygon.roundedRatio(value.numerator().multiply(common), value.denominator().multiply(common))))
				throw new AssertionError("Unreduced affine observation changed rational rounding");
	}
	private static Fraction rational(long numerator, long denominator) { return new Fraction(java.math.BigInteger.valueOf(numerator), java.math.BigInteger.valueOf(denominator)); }
	private static Fraction[] obliqueCoordinates(Fraction[] sample) {
		var point = new Fraction[]{rational(1, 3), rational(2, 7), rational(1, 5)};
		if (sample.length > 0) { point[0] = point[0].add(sample[0]); point[1] = point[1].add(sample[0].multiply(Fraction.of(0.25))); point[2] = point[2].add(sample[0].multiply(Fraction.of(0.5))); }
		if (sample.length > 1) { point[1] = point[1].add(sample[1]); point[2] = point[2].subtract(sample[1].multiply(Fraction.of(0.25))); }
		if (sample.length > 2) point[2] = point[2].add(sample[2]); return point;
	}
	/** Independent exact normal-equation reference in known affine sample coordinates. */
	private static Fraction[] leastSquaresWeights(Fraction[][] sample, List<Fraction> weights, Fraction[] target) {
		int rank = target.length; var total = Fraction.ZERO; var mean = new Fraction[rank]; Arrays.fill(mean, Fraction.ZERO);
		for (int i = 0; i < sample.length; i++) { total = total.add(weights.get(i)); for (int a = 0; a < rank; a++) mean[a] = mean[a].add(weights.get(i).multiply(sample[i][a])); }
		for (int a = 0; a < rank; a++) mean[a] = mean[a].divide(total);
		var matrix = new Fraction[rank][rank + 1];
		for (int a = 0; a < rank; a++) {
			Arrays.fill(matrix[a], Fraction.ZERO); matrix[a][rank] = target[a].subtract(mean[a]);
			for (int b = 0; b < rank; b++) for (int i = 0; i < sample.length; i++) matrix[a][b] = matrix[a][b].add(weights.get(i).multiply(sample[i][a].subtract(mean[a])).multiply(sample[i][b].subtract(mean[b])));
		}
		for (int a = 0; a < rank; a++) {
			var divisor = matrix[a][a]; for (int b = a; b <= rank; b++) matrix[a][b] = matrix[a][b].divide(divisor);
			for (int i = 0; i < rank; i++) if (i != a) { var factor = matrix[i][a]; for (int b = a; b <= rank; b++) matrix[i][b] = matrix[i][b].subtract(factor.multiply(matrix[a][b])); }
		}
		var result = new Fraction[sample.length];
		for (int i = 0; i < sample.length; i++) { result[i] = weights.get(i).divide(total); for (int a = 0; a < rank; a++) result[i] = result[i].add(weights.get(i).multiply(sample[i][a].subtract(mean[a])).multiply(matrix[a][rank])); }
		return result;
	}
	private static void arbitraryProjectedRatesPreserveMeansAndWalls() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		arbitraryProjectedRatesPreserveMeansAndWalls(cube);
		arbitraryProjectedRatesPreserveMeansAndWalls(cube.clip(1, 2, 3, 3.2));
	}
	private static void arbitraryProjectedRatesPreserveMeansAndWalls(LiquidPolyhedron shape) {
		var cells = LiquidGeometryRefinement.children(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, shape));
		var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var input = new double[3 * cells.size()]; for (int i = 0; i < input.length; i++) input[i] = Math.sin(i + 0.5);
		var projected = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(input, new LiquidPolyhedron.Point(0, 0, 0), 0.01, 1e-10, 512);
		var fresh = new LiquidConformingVelocity(mesh, projected.volumeRate(), projected.cellVelocity());
		var zeroRate = new Fraction[mesh.faces.size()]; Arrays.fill(zeroRate, Fraction.ZERO); var liquid = new int[cells.size()];
		var reference = new LiquidConformingVelocity(mesh, zeroRate, input);
		var field = reference.reconstruct(projected.volumeRate(), projected.cellVelocity(), liquid, FluidSolverConfig.WallCondition.FREE_SLIP);
		if (!field.velocity.equals(fresh.velocity) || Double.doubleToLongBits(field.maximumFittingVelocityErrorMetresPerSecond)
				!= Double.doubleToLongBits(fresh.maximumFittingVelocityErrorMetresPerSecond)) throw new AssertionError("Geometry reuse changed nodal velocity or its fitting certificate");
		requireIntegralConstraints(mesh, field, projected.volumeRate(), projected.cellVelocity());
		var time = Fraction.of(0.0001);
		for (var node : field.velocity.entrySet()) {
			var observedPosition = field.advectedPosition(node.getKey(), time); var errorSquared = Fraction.ZERO;
			for (int a = 0; a < 3; a++) {
				var exactPosition = node.getKey().coordinates()[a].add(node.getValue().get(a).multiply(time));
				var error = observedPosition.point().coordinates()[a].subtract(exactPosition); errorSquared = errorSquared.add(error.multiply(error));
			}
			if (errorSquared.subtract(observedPosition.roundoffSquared()).signum() > 0)
				throw new AssertionError("Motion observation certificate lost the actual exact velocity increment or wall projection");
		}
		var motion = new LiquidConformingTransport(mesh, field, projected.volumeRate(), 0.0001);
		if (!(motion.maximumPositionRoundoffMetres > 0 && motion.maximumPositionRoundoffMetres < Math.ulp(0.0001)))
			throw new AssertionError("Motion roundoff is not bounded at the binary64 increment scale");
		for (int f = 0; f < mesh.faces.size(); f++) if (mesh.faces.get(f).second() == -2) {
			var plane = mesh.exactPolygons.get(f).coefficients();
			var points = new ArrayList<>(mesh.exactPolygons.get(f).vertices()); points.add(field.faceCenters.get(f));
			for (var point : points) if (motion.positions.get(point).distance(plane).signum() != 0)
				throw new AssertionError("Binary64 increments changed the exact solid plane constraint");
		}
		var forces = new java.util.LinkedHashMap<LiquidExactPolygon.Vertex, List<Fraction>>(); var power = Fraction.ZERO;
		for (var node : field.velocity.entrySet()) {
			var x = node.getKey().coordinates(); var force = List.of(x[0].multiply(x[0]).subtract(x[1]), x[2].add(x[0]), x[1].multiply(x[2]));
			forces.put(node.getKey(), force); power = power.add(dot(force, node.getValue().toArray(Fraction[]::new)));
		}
		var load = field.load(forces); var actual = Fraction.ZERO;
		for (int i = 0; i < load.cellForce().size(); i++) actual = actual.add(load.cellForce().get(i).multiply(Fraction.of(projected.cellVelocity()[i])));
		for (int f = 0; f < mesh.faces.size(); f++) actual = actual.add(load.faceForce().get(f).multiply(projected.volumeRate()[f]));
		if (!actual.equals(power) || load.prescribedPower().signum() != 0) throw new AssertionError("Continuous force adjoint lost work at cell, face or wall nodes");
		var virtualVelocity = field.rateVelocity(projected.volumeRate()); var rateLoad = field.rateLoad(forces); var nodePower = Fraction.ZERO; var ratePower = Fraction.ZERO;
		var observedVelocity = field.rateOperator().velocity(Arrays.stream(projected.volumeRate()).map(LiquidBoundedArithmetic.Value::of).toArray(LiquidBoundedArithmetic.Value[]::new));
		for (var entry : virtualVelocity.entrySet()) for (int a = 0; a < 3; a++) {
			var observed = observedVelocity.get(entry.getKey())[a]; var expected = entry.getValue().get(a);
			if (Fraction.of(observed.value()).subtract(expected).absolute().subtract(Fraction.of(observed.error())).signum() > 0)
				throw new AssertionError("Forward pressure velocity escaped its exact geometry/error certificate");
		}
		if (!Arrays.equals(rateLoad, reference.rateLoad(forces))) throw new AssertionError("Surface virtual-work operator depends on the reference velocity values");
		requireObservedRateLoad(field, forces);
		for (var entry : forces.entrySet()) nodePower = nodePower.add(dot(entry.getValue(), virtualVelocity.get(entry.getKey()).toArray(Fraction[]::new)));
		for (int f = 0; f < mesh.faces.size(); f++) ratePower = ratePower.add(rateLoad[f].multiply(projected.volumeRate()[f]));
		if (!nodePower.equals(ratePower)) throw new AssertionError("Pressure-compatible capillary pullback lost exact virtual work");
		for (int f = 0; f < mesh.faces.size(); f++) if (mesh.faces.get(f).second() == -2) {
			var face = mesh.faces.get(f); var points = new ArrayList<>(mesh.exactPolygons.get(f).vertices()); points.add(field.faceCenters.get(f));
			for (var point : points) if (dot(face.areaVector(), field.velocity.get(point).toArray(Fraction[]::new)).signum() != 0)
				throw new AssertionError("Continuous velocity penetrates a fixed wall");
		}
		var noSlip = new LiquidConformingVelocity(mesh, zeroRate, input, liquid, FluidSolverConfig.WallCondition.NO_SLIP)
				.reconstruct(projected.volumeRate(), projected.cellVelocity(), liquid, FluidSolverConfig.WallCondition.NO_SLIP);
		requireIntegralConstraints(mesh, noSlip, projected.volumeRate(), projected.cellVelocity());
		requireObservedRateLoad(noSlip, forces);
		for (int f = 0; f < mesh.faces.size(); f++) if (mesh.faces.get(f).second() == -2) {
			var points = new ArrayList<>(mesh.exactPolygons.get(f).vertices()); points.add(noSlip.faceCenters.get(f));
			for (var point : points) for (var value : noSlip.velocity.get(point)) if (value.signum() != 0) throw new AssertionError("No-slip continuous wall retained tangential motion");
		}
		var changedPhase = liquid.clone(); changedPhase[0] = -1;
		try { reference.reconstruct(projected.volumeRate(), projected.cellVelocity(), changedPhase, FluidSolverConfig.WallCondition.FREE_SLIP); throw new AssertionError("Interpolation reused obsolete phase support"); }
		catch (IllegalArgumentException expected) { }
		try { reference.reconstruct(projected.volumeRate(), projected.cellVelocity(), liquid, FluidSolverConfig.WallCondition.NO_SLIP); throw new AssertionError("Interpolation reused different wall constraints"); }
		catch (IllegalArgumentException expected) { }
	}
	static void requireSurfaceActionCertificate(LiquidPressureFaceEnergy surface, Fraction[] exactAction, Fraction[] exactForce, double[] flow) {
		double dt = 0.3; var time = Fraction.of(dt);
		for (boolean includeForce : new boolean[]{false, true}) {
			var target = new double[flow.length]; var error = target.clone();
			surface.add(flow, new double[flow.length], dt, includeForce, target, error);
			for (int f = 0; f < flow.length; f++) {
				var expected = exactAction[f].multiply(time).multiply(time);
				if (includeForce) expected = expected.subtract(exactForce[f].multiply(time));
				if (Fraction.of(target[f]).subtract(expected).absolute().subtract(Fraction.of(error[f])).signum() > 0)
					throw new AssertionError("Surface operator action escaped its exact reference/error certificate at face " + f);
				if (!(error[f] < Math.ulp((float) Math.max(1, Math.abs(expected.rounded()))))) throw new AssertionError("Surface action certificate lost mechanical observation precision");
			}
		}
	}
	private static void requireObservedRateLoad(LiquidConformingVelocity field, java.util.Map<LiquidExactPolygon.Vertex, List<Fraction>> forces) {
		var exact = field.rateLoad(forces); var observed = field.observedRateLoad(forces); double scale = 1;
		for (var value : exact) scale = Math.max(scale, Math.abs(value.rounded()));
		for (int f = 0; f < exact.length; f++) {
			if (Fraction.of(observed[f].value()).subtract(exact[f]).absolute().subtract(Fraction.of(observed[f].error())).signum() > 0)
				throw new AssertionError("Numerical surface transpose escaped its exact coefficient/error certificate");
			if (!(observed[f].error() < Math.ulp((float) scale))) throw new AssertionError("Surface transpose certificate lost existing mechanical observation precision");
		}
	}
	private static void separatedLiquidsKeepIndependentVelocityTraces() {
		var cells = List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 0.25, 1, 1)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.75, 0, 0, 1, 1, 1)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.25, 0, 0, 0.75, 1, 1)));
		var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		int[] liquid = {0, 1, -1}; double[] mean = {0.125, 0.03125, -0.0625, -0.0625, -0.125, 0.03125, 0, 0, 0};
		var rate = new Fraction[mesh.faces.size()];
		for (int f = 0; f < rate.length; f++) {
			var face = mesh.faces.get(f); int c = liquid[face.first()] >= 0 ? face.first() : face.second() >= 0 && liquid[face.second()] >= 0 ? face.second() : -1;
			rate[f] = Fraction.ZERO; if (c >= 0) for (int a = 0; a < 3; a++) rate[f] = rate[f].add(face.areaVector().get(a).multiply(Fraction.of(mean[3 * c + a])));
		}
		var field = new LiquidConformingVelocity(mesh, rate, mean, liquid);
		requireObservedRateLoad(field, field.velocity);
		for (var tet : field.tetrahedra) for (var point : tet.vertices()) for (int a = 0; a < 3; a++)
			if (!field.velocity.get(point).get(a).equals(Fraction.of(mean[3 * tet.cell() + a])))
				throw new AssertionError("Gas geometry coupled the velocity traces of independent liquid regions");
		var motion = new LiquidConformingTransport(mesh, field, rate, 1.0 / 128, liquid);
		for (int i = 0; i < field.tetrahedra.size(); i++) {
			var tet = field.tetrahedra.get(i); var image = motion.images.get(i);
			for (var point : tet.vertices()) {
				var expected = point.coordinates(); for (int a = 0; a < 3; a++) expected[a] = expected[a].add(Fraction.of(mean[3 * tet.cell() + a]).multiply(Fraction.of(1.0 / 128)));
				if (!image.geometry().faces().stream().flatMap(polygon -> polygon.vertices().stream()).toList().contains(LiquidExactPolygon.vertex(expected)))
					throw new AssertionError("Independent liquid translation changed its shape");
			}
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
		for (int c = 0; c < mesh.cells.size(); c++) for (int a = 0; a < 3; a++) {
			if (!field.meanVelocity(c)[a].equals(Fraction.of(mean[3 * c + a]))
					|| !momentum[c][a].equals(mesh.cells.get(c).geometry().measure().volume().multiply(field.meanVelocity(c)[a])))
				throw new AssertionError("Continuous velocity changed cell mean momentum");
		}
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
		requireObservedRateLoad(field, initial.force);
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
