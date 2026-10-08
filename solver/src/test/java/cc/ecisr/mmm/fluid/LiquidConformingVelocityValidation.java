package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared full velocity traces, exact moments and surface work under continuous shear. */
final class LiquidConformingVelocityValidation {
	static void validate() {
		nonStarControlRetainsOneMechanicalMean();
		integerPlaneCoordinatesPreserveConstraintAndClosestDistance();
		stationaryRationalGeometrySurvivesMotionRounding();
		affineFieldSurvivesAdaptiveFaces();
		continuousFitPreservesMomentsAndItsExactReference();
		finiteAffineWeightsPreserveMomentsAndErrorBounds();
		supportedAffineFieldsSurviveObliqueCells();
		certifiedAffineFitsContainExactLeastSquares();
		separatedLiquidsKeepIndependentVelocityTraces();
		arbitraryProjectedRatesPreserveMeansAndWalls();
		continuousShearPreservesExteriorAreaAndWork();
		spaceTimeSweepsPreserveVolumeAndSharedFaces();
		signedPartitionsPreserveNonStarShapedVolumes();
		capillaryLoadsShareMechanicalInertiaAndSurfaceWork();
		implicitCapillarityUsesActualPhaseOrdering();
		LiquidWetWallValidation.validate();
	}
	private static void nonStarControlRetainsOneMechanicalMean() {
		var support = List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 0.25, 0.25)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0.25, 0, 0.25, 1, 0.25)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.75, 0.25, 0, 1, 1, 0.25)));
		var shape = LiquidStarControlPartition.assemble(support);
		if (shape.kernelPoint() != null) throw new AssertionError("Concave fixture unexpectedly admits one positive fan");
		var transform = List.of(Fraction.of(-1), Fraction.ZERO, Fraction.ZERO, Fraction.of(1),
				Fraction.ZERO, Fraction.of(-1), Fraction.ZERO, shape.centroidCoordinates()[1].multiply(Fraction.of(2)),
				Fraction.ZERO, Fraction.ZERO, Fraction.of(1), Fraction.ZERO);
		var reversed = LiquidConvexRegion.of(shape).transformed(transform).geometry();
		if (!shape.measure().same(reversed.measure())) throw new AssertionError("Invalid-support fixture changed its extensive moments");
		try {
			new LiquidControlVolumeMesh(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, reversed)), List.of(support), 1, 1000,
					(c, face) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			throw new AssertionError("Equal volume and first moments concealed a different occupied region");
		} catch (IllegalArgumentException expected) { }
		var mesh = new LiquidControlVolumeMesh(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, shape)), List.of(support), 1, 1000,
				(c, face) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		if (mesh.cells.size() != 1 || mesh.subdivision.size() < 2 || mesh.faces.stream().anyMatch(face -> face.second() >= 0))
			throw new AssertionError("Positive auxiliary cuts acquired independent mechanical cells or rates");
		double[] mean = {0.125, -0.0625, 0.03125}; var rates = new Fraction[mesh.faces.size()];
		for (int f = 0; f < rates.length; f++) { rates[f] = Fraction.ZERO;
			for (int a = 0; a < 3; a++) rates[f] = rates[f].add(mesh.faces.get(f).areaVector().get(a).multiply(Fraction.of(mean[a])));
		}
		var field = new LiquidConformingVelocity(mesh, rates, mean); requireIntegralConstraints(mesh, field, rates, mean);
		if (field.surfaceWeights.isEmpty()) throw new AssertionError("Concave fixture did not exercise restrictions across merged physical faces");
		if (field.cellApexes.getFirst().size() != mesh.subdivision.size()) throw new AssertionError("Auxiliary positive support was lost");
		for (var value : field.velocity.values()) for (int a = 0; a < 3; a++)
			if (!value.get(a).equals(Fraction.of(mean[a]))) throw new AssertionError("Multi-fan reconstruction changed a rigid translation");
		var forces = new java.util.LinkedHashMap<LiquidExactPolygon.Vertex, List<Fraction>>(); int ordinal = 1;
		for (var point : field.velocity.keySet()) { forces.put(point, List.of(Fraction.of(ordinal), Fraction.of(-ordinal * 2), Fraction.of(ordinal % 3))); ordinal++; }
		requireObservedRateLoad(field, forces);
		var virtual = rates.clone(); for (int f = 0; f < virtual.length; f++) virtual[f] = Fraction.of((f % 7 - 3) * 0.125);
		var nodes = field.rateVelocity(virtual); var load = field.rateLoad(forces); var power = Fraction.ZERO;
		for (var entry : forces.entrySet()) for (int a = 0; a < 3; a++) power = power.add(entry.getValue().get(a).multiply(nodes.get(entry.getKey()).get(a)));
		for (int f = 0; f < virtual.length; f++) power = power.subtract(load[f].multiply(virtual[f]));
		if (power.signum() != 0) throw new AssertionError("Multi-fan rate velocity and force transpose lost exact virtual work");
		var observed = field.rateOperator().velocity(Arrays.stream(virtual).map(LiquidBoundedArithmetic.Value::of).toArray(LiquidBoundedArithmetic.Value[]::new));
		for (var entry : nodes.entrySet()) for (int a = 0; a < 3; a++) {
			var value = observed.get(entry.getKey())[a];
			if (Fraction.of(value.value()).subtract(entry.getValue().get(a)).absolute().compareTo(Fraction.of(value.error())) > 0)
				throw new AssertionError("Multi-fan numerical velocity escaped its exact reference certificate");
		}
		var motion = new LiquidConformingTransport(mesh, field, rates, 1.0 / 128); var volume = Fraction.ZERO;
		for (var image : motion.images) volume = volume.add(image.geometry().measure().volume());
		if (!volume.equals(shape.measure().volume())) throw new AssertionError("Concave transport changed exact volume");
		var deformed = field.reconstruct(virtual, mean, new int[]{0}, FluidSolverConfig.WallCondition.FREE_SLIP);
		requireIntegralConstraints(mesh, deformed, virtual, mean);
		var deformation = new LiquidConformingTransport(mesh, deformed, virtual, 1.0 / 4096).representCoordinates(28);
		var expectedVolume = shape.measure().volume(); for (var swept : deformation.sweptVolume) expectedVolume = expectedVolume.add(swept);
		var actualVolume = Fraction.ZERO; for (var image : deformation.images) actualVolume = actualVolume.add(image.geometry().measure().volume());
		if (!actualVolume.equals(expectedVolume)) throw new AssertionError("Restricted auxiliary traces disagreed with physical face volume constraints");
		var sigma = new double[rates.length]; Arrays.fill(sigma, 0.125);
		var surface = new LiquidConformingCapillarity(field, sigma, new double[]{1000});
		var expected = new LiquidAreaEnergy(mesh.exactPolygons, sigma);
		if (Math.abs(surface.energy.joules - expected.joules) > 4 * Math.ulp(expected.joules))
			throw new AssertionError("Auxiliary internal faces acquired physical surface energy");
		var controls = new ArrayList<LiquidGeometryRefinement.Cell>(); controls.add(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, shape));
		var subdivision = new ArrayList<List<LiquidGeometryRefinement.Cell>>(); subdivision.add(support);
		for (var box : List.of(LiquidPolyhedron.box(0.25, 0.25, 0, 0.75, 1, 0.25), LiquidPolyhedron.box(0, 0, 0.25, 1, 1, 0.5),
				LiquidPolyhedron.box(1, 0, 0, 1.5, 1, 0.5))) {
			var cell = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, box); controls.add(cell); subdivision.add(List.of(cell));
		}
		controls.replaceAll(cell -> new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cell.geometry().scaled(0.5)));
		subdivision.replaceAll(group -> group.stream().map(cell -> new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cell.geometry().scaled(0.5))).toList());
		var connected = new LiquidControlVolumeMesh(controls, subdivision, 1, 1000, (c, face) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		requireAffineField(connected, point -> new Fraction[]{point[2].multiply(Fraction.of(0.125)), point[0].multiply(Fraction.of(0.0625)),
				point[0].multiply(Fraction.of(-0.125))});
	}
	private static void integerPlaneCoordinatesPreserveConstraintAndClosestDistance() {
		for (int[] normal : new int[][]{{1, 0, 0}, {0, -1, 0}, {0, 0, 3}, {2, 4, 6}, {3, -5, 7}, {-3, 2, -4}, {6, 10, 15}, {3, 3, 3}})
			for (int target = -2; target <= 2; target++) {
				var area = Arrays.stream(normal).mapToObj(Fraction::of).toArray(Fraction[]::new); var center = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
				var represented = LiquidIntegerPlaneProjection.correct(center, area, Fraction.of(target).divide(Fraction.of(3)), 0);
				if (!dot(List.of(area), represented).equals(Fraction.of(target))) throw new AssertionError("Integer plane correction lost exact swept volume");
				var denominator = java.math.BigInteger.ONE;
				for (var value : represented) denominator = denominator.multiply(value.denominator()).divide(denominator.gcd(value.denominator()));
				var distance = dot(List.of(represented), represented).multiply(new Fraction(denominator.multiply(denominator), java.math.BigInteger.ONE));
				for (int x = -8; x <= 8; x++) for (int y = -8; y <= 8; y++) for (int z = -8; z <= 8; z++)
					if (normal[0] * x + normal[1] * y + normal[2] * z == target * denominator.intValueExact()
							&& distance.compareTo(Fraction.of(x * x + y * y + z * z)) > 0)
						throw new AssertionError("Integer plane correction missed a closer feasible lattice point");
			}
		var large = java.math.BigInteger.ONE.shiftLeft(160).add(java.math.BigInteger.valueOf(3));
		var area = new Fraction[]{Fraction.of(1), new Fraction(large, java.math.BigInteger.ONE), new Fraction(large.add(java.math.BigInteger.ONE), java.math.BigInteger.ONE)};
		var center = new Fraction[]{Fraction.of(0.375), Fraction.of(0.25), Fraction.of(0.4375)}; var swept = Fraction.of(Math.scalb(1.0, -40)).divide(Fraction.of(3));
		var represented = LiquidIntegerPlaneProjection.correct(center, area, swept, 128); var norm = dot(List.of(area), area);
		var error = Fraction.ZERO;
		for (int a = 0; a < 3; a++) {
			if (represented[a].denominator().bitCount() != 1 || represented[a].denominator().bitLength() > 128 + large.bitLength() + 3)
				throw new AssertionError("Integer coordinate projection introduced an independent normal-square denominator");
			var ideal = center[a].add(area[a].multiply(swept).multiply(Fraction.of(3)).divide(norm)); var delta = represented[a].subtract(ideal);
			error = error.add(delta.multiply(delta));
		}
		var bound = new Fraction(java.math.BigInteger.ONE, java.math.BigInteger.ONE.shiftLeft(258));
		if (!dot(List.of(area), represented).subtract(dot(List.of(area), center)).equals(swept.multiply(Fraction.of(3))) || error.compareTo(bound) > 0)
			throw new AssertionError("Integer plane correction lost volume or its finite-grid covering-radius bound");
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
		var represented = new LiquidCoordinateProjection(mesh, field, motion.positions, new Fraction[]{shape.measure().volume()}, 20);
		if (!represented.positions.equals(motion.positions) || represented.maximumPositionErrorMetres != 0)
			throw new AssertionError("Finite coordinate representation changed a stationary rational surface");
	}
	private static void finiteAffineWeightsPreserveMomentsAndErrorBounds() {
		for (double height : new double[]{1, Math.scalb(1.0, -80)}) {
			var centers = new ArrayList<LiquidExactPolygon.Vertex>(); var coordinates = new ArrayList<Fraction[]>();
			for (int c = 0; c < 8; c++) {
				var denominator = java.math.BigInteger.ONE.shiftLeft(220).add(java.math.BigInteger.valueOf(2 * c + 1));
				var point = new Fraction[]{Fraction.of((c & 1) == 0 ? 0.25 : 0.75).add(new Fraction(java.math.BigInteger.ONE, denominator)),
						Fraction.of((c & 2) == 0 ? 0.25 : 0.75), Fraction.of(((c & 4) == 0 ? 0.25 : 0.75) * height)};
				coordinates.add(point); centers.add(LiquidExactPolygon.vertex(point));
			}
			var basis = new LiquidAffineFit.Basis(coordinates); var target = LiquidExactPolygon.vertex(new Fraction[]{Fraction.of(0.375), Fraction.of(0.625), Fraction.of(0.375 * height)});
			var observed = new java.util.LinkedHashMap<Integer, LiquidBoundedArithmetic.Value>(); var exact = new java.util.LinkedHashMap<Integer, Fraction>();
			for (int c = 0; c < centers.size(); c++) {
				var value = new Fraction(java.math.BigInteger.ONE.shiftLeft(200).add(java.math.BigInteger.valueOf(c + 1)), java.math.BigInteger.ONE.shiftLeft(204).add(java.math.BigInteger.valueOf(2 * c + 3)));
				exact.put(c, value); observed.put(c, LiquidBoundedArithmetic.Value.of(value));
			}
			var patch = java.util.stream.IntStream.range(0, centers.size()).boxed().toList();
			var result = LiquidAffineWeightCoordinates.complete(target, basis, patch, centers, observed);
			var anchors = new java.util.HashSet<Integer>(); for (int a : basis.anchor) anchors.add(a);
			for (int a = 0; a < basis.anchor.length; a++) {
				var value = basis.shape[a].evaluate(target);
				for (var entry : exact.entrySet()) if (!anchors.contains(entry.getKey())) value = value.subtract(entry.getValue().multiply(basis.shape[a].evaluate(centers.get(entry.getKey()))));
				exact.put(basis.anchor[a], value);
			}
			var sum = Fraction.ZERO; var moment = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
			for (var entry : result.weights().entrySet()) {
				int c = entry.getKey(); var value = entry.getValue(); sum = sum.add(value);
				for (int a = 0; a < 3; a++) moment[a] = moment[a].add(value.multiply(coordinates.get(c)[a]));
				if (value.subtract(exact.get(c)).absolute().compareTo(Fraction.of(result.errors().get(c))) > 0
						|| !anchors.contains(c) && value.denominator().bitCount() != 1)
					throw new AssertionError("Finite interpolation lost its independent binary coordinates or actual error certificate");
			}
			if (!sum.equals(Fraction.of(1)) || !Arrays.equals(moment, target.coordinates()))
				throw new AssertionError("Finite interpolation displaced constant or affine moments in thin rational support");
		}
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
		var reference = LiquidConformingTransport.prepareReference(mesh, field, rate, 1.0 / 128, new int[mesh.cells.size()]);
		var represented = reference.representCoordinates(64); var eager = motion.representCoordinates(64);
		if (!reference.positions.equals(motion.positions) || !java.util.Arrays.equals(reference.sweptVolume, motion.sweptVolume)
				|| !represented.positions.equals(eager.positions) || !java.util.Arrays.equals(represented.sweptVolume, eager.sweptVolume)
				|| !reference.surfaces.stream().map(surface -> surface.geometry().vertexKey()).toList()
						.equals(motion.surfaces.stream().map(surface -> surface.geometry().vertexKey()).toList()) || reference.images.size() != motion.images.size())
			throw new AssertionError("Deferred reference changed exact sweeps, surfaces or represented transport");
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
		try {
			LiquidConformingTransport.prepareReference(mesh, field, rate, 64, new int[mesh.cells.size()]).representCoordinates(64);
			throw new AssertionError("An inverted reference bypassed the actual time-refinement request");
		} catch (LiquidCharacteristicTimeResolutionRequired expected) { }
	}
	private static void signedPartitionsPreserveNonStarShapedVolumes() {
		for (double height : new double[]{1, Math.scalb(1.0, -80)}) {
			var source = List.of(LiquidExactPolygon.vertex(new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO}),
					LiquidExactPolygon.vertex(new Fraction[]{Fraction.of(1), Fraction.ZERO, Fraction.ZERO}),
					LiquidExactPolygon.vertex(new Fraction[]{Fraction.ZERO, Fraction.of(1), Fraction.ZERO}),
					LiquidExactPolygon.vertex(new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.of(height)}));
			var moved = new java.util.LinkedHashMap<LiquidExactPolygon.Vertex, LiquidExactPolygon.Vertex>();
			for (var p : source) { var x = p.coordinates(); x[0] = x[0].negate(); moved.put(p, LiquidExactPolygon.vertex(x)); }
			var tet = new LiquidConformingVelocity.Tetrahedron(0, 0, source, LiquidExactPolygon.tetrahedronVolume(source));
			var witness = LiquidExactPolygon.vertex(new Fraction[]{Fraction.of(-0.125), Fraction.of(0.125), Fraction.of(height / 8)});
			if (!LiquidConformingTransport.negativeWinding(List.of(tet), 0, moved, witness)
					|| LiquidConformingTransport.negativeWinding(List.of(tet), 0, moved, source.getFirst())
					|| LiquidConformingTransport.negativeWinding(List.of(tet), 1, moved, witness))
				throw new AssertionError("Reference winding certificate lost a negative interior or accepted a surface or different cell");
			var target = source.stream().map(moved::get).toList(); for (var p : target) moved.put(p, p);
			var covering = new LiquidConformingVelocity.Tetrahedron(0, 1, target, LiquidExactPolygon.tetrahedronVolume(target));
			if (LiquidConformingTransport.negativeWinding(List.of(tet, covering), 0, moved, witness))
				throw new AssertionError("Reference winding certificate ignored its positive covering volume");
			moved.put(source.get(1), source.getFirst());
			if (LiquidConformingTransport.negativeWinding(List.of(tet), 0, moved, witness))
				throw new AssertionError("Degenerate reference supplied a strict negative-winding certificate");
			var cells = List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 0.25, 1, height)),
					new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.75, 0, 0, 1, 1, height)),
					new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.25, 0, 0, 0.75, 0.25, height)));
			var faces = LiquidExteriorGeometry.of(cells); var expected = LiquidConvexRegion.EMPTY.measure();
			for (var cell : cells) expected = expected.add(cell.geometry().measure());
			var point = new Fraction[]{Fraction.of(0.5), Fraction.of(0.5), Fraction.of(height / 2)};
			var apex = LiquidExactPolygon.vertex(point); var positive = new ArrayList<LiquidConvexRegion>(); var negative = new ArrayList<LiquidConvexRegion>();
			for (var face : faces) for (int e = 0; e < face.vertices().size(); e++) {
				var region = LiquidConvexRegion.ofTetrahedron(face.tetrahedronVertices(point, e));
				if (!region.hasVolume()) continue;
				(apex.distance(face.coefficients()).signum() < 0 ? positive : negative).add(region);
			}
			if (negative.isEmpty()) throw new AssertionError("Non-star-shaped surface lost its signed auxiliary volumes");
			var partition = LiquidSignedVolumePartition.cancel(positive, negative, 4096); var actual = LiquidConvexRegion.EMPTY.measure();
			for (var region : partition) {
				actual = actual.add(region.measure());
				var retained = region.faces();
				for (var plane : LiquidExactPolygon.faces(LiquidPolyhedron.box(0.25, 0.25, 0, 0.75, 1, height))) retained = LiquidExactPolygon.clipVolume(retained, plane.coefficients(), 1);
				if (!retained.isEmpty()) throw new AssertionError("Signed partition filled the non-star-shaped cavity");
			}
			if (!actual.same(expected) || !actual.same(LiquidExactPolygon.measure(faces)))
				throw new AssertionError("Signed partition changed exact volume or first moments");
		}
		var region = LiquidConvexRegion.of(LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var uncovered = LiquidConvexRegion.of(LiquidPolyhedron.box(0.75, 0.75, 0.75, 1.5, 1.5, 1.5));
		try { LiquidSignedVolumePartition.cancel(List.of(region), List.of(uncovered), 16, 6); throw new AssertionError("Uncovered negative interior was accepted"); }
		catch (LiquidCharacteristicTimeResolutionRequired expected) { if (expected.negativeWinding == null) throw new AssertionError("Negative winding rejection lost its exact interior witness"); }
		var partiallyCovered = LiquidConvexRegion.of(LiquidPolyhedron.box(0.5, 0.5, 0.5, 1.25, 1.25, 1.25));
		try { LiquidSignedVolumePartition.cancel(List.of(region), List.of(partiallyCovered), 64); throw new AssertionError("Partially uncovered negative volume was accepted"); }
		catch (LiquidCharacteristicTimeResolutionRequired expected) {
			if (expected.negativeWinding == null || region.faces().stream().noneMatch(face -> expected.negativeWinding.point().distance(face.coefficients()).signum() > 0))
				throw new AssertionError("Residual negative volume lacks an exact uncovered interior witness");
		}
		try { LiquidSignedVolumePartition.cancel(List.of(region), List.of(region), 16, 1); throw new AssertionError("Unbounded signed partition work was accepted"); }
		catch (IllegalStateException expected) { }
		try { LiquidSignedVolumePartition.cancel(List.of(region, region), List.of(), 16); throw new AssertionError("Double material winding was accepted"); }
		catch (LiquidCharacteristicTimeResolutionRequired expected) { }
		try { LiquidSignedVolumePartition.cancel(List.of(region), List.of(region, region), 16); throw new AssertionError("Negative material winding was accepted"); }
		catch (LiquidCharacteristicTimeResolutionRequired expected) { }
	}
	private static void affineFieldSurvivesAdaptiveFaces() {
		var cells = new ArrayList<>(LiquidGeometryRefinement.children(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1))));
		cells.addAll(LiquidGeometryRefinement.children(cells.removeFirst()));
		var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		requireAffineField(mesh, LiquidConformingVelocityValidation::affine);
		var chain = new ArrayList<LiquidGeometryRefinement.Cell>();
		for (int[] at : new int[][]{{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}})
			chain.add(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.25 * at[0], 0.25 * at[1], 0.25 * at[2],
					0.25 * (at[0] + 1), 0.25 * (at[1] + 1), 0.25 * (at[2] + 1))));
		// Its first one-ring patch is one-dimensional; the connected component supplies exact three-dimensional support.
		requireAffineField(new LiquidControlVolumeMesh(chain, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0)),
				LiquidConformingVelocityValidation::affine);
	}
	private static void continuousFitPreservesMomentsAndItsExactReference() {
		continuousFitPreservesMomentsAndItsExactReference(Fraction.ZERO);
		continuousFitPreservesMomentsAndItsExactReference(new Fraction(java.math.BigInteger.ONE, java.math.BigInteger.ONE.shiftLeft(220).add(java.math.BigInteger.valueOf(7))));
	}
	private static void continuousFitPreservesMomentsAndItsExactReference(Fraction shift) {
		var original = LiquidGeometryRefinement.children(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1)));
		var cells = original.stream().map(cell -> {
			var faces = new ArrayList<LiquidExactPolygon>();
			for (var face : LiquidExactPolygon.faces(cell.geometry())) {
				var vertices = face.vertices().stream().map(point -> {
					var coordinates = point.coordinates(); coordinates[0] = coordinates[0].add(shift); return LiquidExactPolygon.vertex(coordinates);
				}).toList();
				faces.add(LiquidExactPolygon.restored(vertices, LiquidExactPolygon.triangle(vertices.get(0), vertices.get(1), vertices.get(2)).coefficients()));
			}
			return new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.restoredExact(faces));
		}).toList();
		var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var interpolation = new LiquidVelocityInterpolation(mesh, new int[cells.size()]); var center = Fraction.of(0.5);
		var baseline = LiquidExactPolygon.vertex(new Fraction[]{center.add(shift), center, center});
		var initial = interpolation.at(baseline, 0); var reference = partitionReference(mesh, baseline);
		Fraction firstChange = null;
		for (int bits : new int[]{16, 32, 48}) {
			var spacing = Fraction.of(Math.scalb(1.0, -bits)); var point = LiquidExactPolygon.vertex(new Fraction[]{center.add(shift).add(spacing), center, center});
			var actual = interpolation.at(point, 0); var otherIncidence = interpolation.at(point, 7); var exact = partitionReference(mesh, point);
			if (!actual.weights.equals(otherIncidence.weights)) throw new AssertionError("Continuous fitting changed at an incident-cell transition");
			var sum = Fraction.ZERO; var moment = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
			for (int c = 0; c < cells.size(); c++) {
				var magnitude = new double[cells.size()]; magnitude[c] = 1;
				if (actual.weights.get(c).subtract(exact[c]).absolute().compareTo(Fraction.of(actual.error(magnitude))) > 0)
					throw new AssertionError("Continuous fitting escaped its independent exact partition-of-unity coefficient");
				var weight = actual.weights.get(c); sum = sum.add(weight);
				for (int a = 0; a < 3; a++) moment[a] = moment[a].add(weight.multiply(cells.get(c).geometry().centroidCoordinates()[a]));
			}
			if (!sum.equals(Fraction.of(1)) || !Arrays.equals(moment, point.coordinates()) || !actual.affineComplete)
				throw new AssertionError("Continuous fitting lost exact affine moments");
			if (bits == 16) firstChange = exact[0].subtract(reference[0]).absolute();
			var magnitude = new double[cells.size()]; magnitude[0] = 1;
			var error = Fraction.of(actual.error(magnitude)).add(Fraction.of(initial.error(magnitude)));
			var limit = firstChange.multiply(Fraction.of(Math.scalb(2.0, 16 - bits))).add(error);
			if (actual.weights.get(0).subtract(initial.weights.get(0)).absolute().compareTo(limit) > 0)
				throw new AssertionError("Adjacent-node fitting retained a finite jump as spacing decreased");
		}
	}
	/** Independent exact local normal equations and exact polynomial kernels on the eight-cell cube. */
	private static Fraction[] partitionReference(LiquidControlVolumeMesh mesh, LiquidExactPolygon.Vertex point) {
		int n = mesh.cells.size(); var kernel = new Fraction[n]; var total = Fraction.ZERO; var local = new Fraction[n][];
		var patches = new ArrayList<List<Integer>>();
		for (int c = 0; c < n; c++) {
			var patch = new java.util.TreeSet<Integer>(); patch.add(c);
			for (int f : mesh.cellFaces[c]) { var face = mesh.faces.get(f); patch.add(face.first()); if (face.second() >= 0) patch.add(face.second()); }
			var cells = List.copyOf(patch); patches.add(cells);
			var centers = cells.stream().map(k -> mesh.cells.get(k).geometry().centroidCoordinates()).toArray(Fraction[][]::new);
			local[c] = leastSquaresWeights(centers, cells.stream().map(k -> Fraction.of(0.125)).toList(), point.coordinates());
			var d = new Fraction[3]; var center = mesh.cells.get(c).geometry().centroidCoordinates();
			for (int a = 0; a < 3; a++) d[a] = point.coordinates()[a].subtract(center[a]);
			var base = Fraction.of(1).subtract(dot(List.of(d), d).divide(Fraction.of(0.75))); var square = base.multiply(base);
			kernel[c] = base.signum() > 0 ? square.multiply(square) : Fraction.ZERO; total = total.add(kernel[c]);
		}
		var result = new Fraction[n]; Arrays.fill(result, Fraction.ZERO);
		for (int c = 0; c < n; c++) for (int i = 0; i < patches.get(c).size(); i++) {
			int cell = patches.get(c).get(i); result[cell] = result[cell].add(local[c][i].multiply(kernel[c]).divide(total));
		}
		return result;
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
			throw new AssertionError("Continuous velocity lost affine completeness at " + node.getKey() + ": " + node.getValue() + " / " + List.of(affine.apply(node.getKey().coordinates())));
		if (mesh.subdivision != null) for (int c = 0; c < field.cellApexes.size(); c++) for (var point : field.cellApexes.get(c)) {
			var moved = field.partitionPosition(c, point, Fraction.of(0.125)); var velocity = affine.apply(point.coordinates());
			for (int a = 0; a < 3; a++) if (!moved[a].equals(point.coordinates()[a].add(velocity[a].multiply(Fraction.of(0.125)))))
				throw new AssertionError("Auxiliary positive subdivision lost affine endpoint motion");
		}
		requireIntegralConstraints(mesh, field, rates, mean);
	}
	private static Fraction[] affine(Fraction[] x) {
		return new Fraction[]{x[0].multiply(Fraction.of(0.125)).add(x[1].multiply(Fraction.of(0.25))).add(Fraction.of(0.5)),
				x[0].multiply(Fraction.of(-0.25)).add(x[2].multiply(Fraction.of(0.125))).subtract(Fraction.of(0.25)),
				x[2].multiply(Fraction.of(-0.125)).add(Fraction.of(0.125))};
	}
	private static void certifiedAffineFitsContainExactLeastSquares() {
		for (int pattern = 0; pattern < 8; pattern++) {
			int rank = Math.min(pattern, 3); var small = new Fraction(java.math.BigInteger.ONE, java.math.BigInteger.ONE.shiftLeft(80));
			var samples = new Fraction[8][rank]; var target = new Fraction[rank];
			var centers = new ArrayList<Fraction[]>(); var weights = new ArrayList<Fraction>();
			for (int a = 0; a < rank; a++) target[a] = rational(1, 2 * a + 7);
			for (int i = 0; i < samples.length; i++) {
				for (int a = 0; a < rank; a++) samples[i][a] = rank == 1 ? rational(i, 21) : rational(i >>> a & 1, 2 * a + 3);
				if (pattern == 4 && i > 1 && i < 4) samples[i] = new Fraction[]{rational(1, 3), small, i == 3 ? small : Fraction.ZERO};
				if (pattern >= 6) samples[i][0] = samples[i][0].add(new Fraction(java.math.BigInteger.ONE,
						java.math.BigInteger.ONE.shiftLeft(220).add(java.math.BigInteger.valueOf(2 * i + 1))));
				if (pattern == 5 || pattern == 7) samples[i][2] = samples[i][2].multiply(small);
				centers.add(obliqueCoordinates(samples[i])); weights.add(rational(i + 1, i + 7));
			}
			if (pattern == 5 || pattern == 7) target[2] = target[2].multiply(small);
			var point = LiquidExactPolygon.vertex(obliqueCoordinates(target)); var fit = new LiquidAffineFit(centers, weights);
			var exact = leastSquaresWeights(samples, weights, target); var sum = Fraction.ZERO; var moment = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
			var observed = new java.util.LinkedHashMap<Integer, LiquidBoundedArithmetic.Value>(); var local = fit.weights(point);
			for (int i = 0; i < local.length; i++) {
				observed.put(i, local[i]);
				if (Fraction.of(local[i].value()).subtract(exact[i]).absolute().compareTo(Fraction.of(local[i].error())) > 0)
					throw new AssertionError("Bounded local fit lost its original least-squares reference");
			}
			var completed = LiquidAffineWeightCoordinates.complete(point, fit.basis, java.util.stream.IntStream.range(0, samples.length).boxed().toList(),
					centers.stream().map(LiquidExactPolygon::vertex).toList(), observed);
			for (int i = 0; i < samples.length; i++) {
				var weight = completed.weights().get(i); double error = completed.errors().get(i);
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
		var same = projected.observeRates(projected.volumeRate());
		if (!Arrays.equals(projected.cellVelocity(), same.cellVelocity()) || !Arrays.equals(projected.pressure(), same.pressure())
				|| Double.doubleToLongBits(projected.boundaryWorkJoules()) != Double.doubleToLongBits(same.boundaryWorkJoules()))
			throw new AssertionError("Rate certification changed an identical pressure result");
		var invalid = projected.volumeRate().clone(); invalid[0] = invalid[0].add(Fraction.of(0.125));
		try { projected.observeRates(invalid); throw new AssertionError("Pressure representation accepted a new volume source"); }
		catch (IllegalArgumentException expected) { }
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
		requireFiniteCoordinateConstraints(mesh, field, motion);
		try { projected.observeRates(motion.representCoordinates(20).volumeRate()); throw new AssertionError("Coarse coordinates bypassed pressure stationarity"); }
		catch (LiquidCoordinateResolutionRequired expected) { }
		int precision = LiquidCoordinateProjection.fractionalBits(mesh, 0.0001, 1e-10);
		if (LiquidCoordinateProjection.fractionalBits(mesh, 0.0001 / 16, 1e-10) != precision + 4
				|| LiquidCoordinateProjection.fractionalBits(mesh, 0.0001, 1e-10 / 2) != precision + 1)
			throw new AssertionError("Coordinate spacing lost its time and rate dimensions");
		LiquidConformingTransport represented; LiquidControlVolumeProjection.Result certified;
		for (;;) {
			try { represented = motion.representCoordinates(precision); certified = projected.observeRates(represented.volumeRate()); break; }
			catch (LiquidCoordinateResolutionRequired finer) { if (precision >= 1074) throw finer; precision = Math.min(1074, 2 * precision); }
		}
		try { represented.withField(field); throw new AssertionError("Transport accepted the rates from an earlier geometric representation"); }
		catch (IllegalArgumentException expected) { }
		var transported = represented.withField(field.reconstruct(certified.volumeRate(), certified.cellVelocity(), liquid, FluidSolverConfig.WallCondition.FREE_SLIP));
		if (!(transported.maximumCoordinateErrorMetres > 0 && transported.maximumRateRepresentationErrorPerSecond > 0)
				|| transported.field == field || !Arrays.equals(transported.volumeRate(), certified.volumeRate()))
			throw new AssertionError("Coordinate transport lost the actual certified rates or transported mean field");
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
	private static void requireFiniteCoordinateConstraints(LiquidControlVolumeMesh mesh, LiquidConformingVelocity field, LiquidConformingTransport motion) {
		var volume = mesh.cells.stream().map(c -> c.geometry().measure().volume()).toArray(Fraction[]::new);
		var reference = transportedMeasures(field, motion.positions);
		double previousMomentError = Double.POSITIVE_INFINITY, previousPositionError = Double.POSITIVE_INFINITY;
		for (int bits : new int[]{20, 32, 44}) {
			var represented = new LiquidCoordinateProjection(mesh, field, motion.positions, volume, bits);
			var actual = transportedMeasures(field, represented.positions); double momentError = 0;
			for (int c = 0; c < volume.length; c++) {
				if (!actual[c].volume().equals(volume[c])) throw new AssertionError("Finite coordinates changed liquid volume");
				for (int a = 0; a < 3; a++) momentError = Math.max(momentError, actual[c].firstMoment(a).subtract(reference[c].firstMoment(a)).absolute().rounded());
			}
			for (int f = 0; f < mesh.faces.size(); f++) if (mesh.faces.get(f).second() == -2) {
				var points = new ArrayList<>(mesh.exactPolygons.get(f).vertices()); points.add(field.faceCenters.get(f));
				for (var point : points) if (represented.positions.get(point).distance(mesh.exactPolygons.get(f).coefficients()).signum() != 0)
					throw new AssertionError("Finite coordinate representation crossed a solid plane");
			}
			for (var entry : represented.positions.entrySet()) {
				var x = entry.getValue().coordinates(); var y = motion.positions.get(entry.getKey()).coordinates(); var squared = Fraction.ZERO;
				for (int a = 0; a < 3; a++) { var delta = x[a].subtract(y[a]); squared = squared.add(delta.multiply(delta)); }
				var bound = Fraction.of(represented.maximumPositionErrorMetres);
				if (squared.subtract(bound.multiply(bound)).signum() > 0) throw new AssertionError("Finite coordinate displacement escaped its measured bound");
			}
			if (!(momentError < previousMomentError / 1024 && represented.maximumPositionErrorMetres < previousPositionError / 1024))
				throw new AssertionError("Coordinate and first-moment errors failed precision convergence");
			previousMomentError = momentError; previousPositionError = represented.maximumPositionErrorMetres;
		}
	}
	private static LiquidExactPolygon.Measure[] transportedMeasures(LiquidConformingVelocity field, java.util.Map<LiquidExactPolygon.Vertex, LiquidExactPolygon.Vertex> positions) {
		var result = new LiquidExactPolygon.Measure[field.mesh.cells.size()]; Arrays.fill(result, LiquidConvexRegion.EMPTY.measure());
		for (var tet : field.tetrahedra) result[tet.cell()] = result[tet.cell()].add(LiquidExactPolygon.tetrahedronMeasure(tet.vertices().stream().map(positions::get).toList()));
		return result;
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
			if (tet.face() >= 0 && mesh.faces.get(tet.face()).first() == tet.cell()) {
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
