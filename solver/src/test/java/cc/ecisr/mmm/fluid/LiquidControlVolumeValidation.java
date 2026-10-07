package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Pressure behavior is tied to liquid geometry, including controls thinner than half a base cell. */
final class LiquidControlVolumeValidation {
	private static final LiquidPolyhedron.Point GRAVITY = new LiquidPolyhedron.Point(0, -9.81, 0);
	static void validate() {
		boundedArithmeticContainsExactEndpointValues();
		pressureMetricCertificatesContainExactCoefficients();
		pressureInertiaSurvivesTinyAuxiliaryFaceAreas();
		for (double size : new double[] {0.5, 0.125, 1.0 / 1024}) freeLiquidReceivesGravity(size);
		for (double height : new double[] {0.5, 0.125, 1.0 / 1024}) thinHydrostaticColumn(height);
		for (double size : new double[] {0.125, 1e-4, 1e-7}) smallHydrostaticCube(size);
		densityJumpUsesBothLiquidDistances();
		refinedHydrostaticColumn();
		refinedAffinePressure();
		scalarDiffusionPreservesAffineFluxAndRefinement();
		sharedFaceHeatFluxClosesOnObliqueAndRefinedCells();
		refinedViscosityPreservesRigidMotionAndMomentum();
		finiteGasPressureSharesLiquidProjection();
		pressureAndViscosityShareMomentumAndEnergy();
		sharedVelocityReconstructionPreservesTranslation();
		vanishingContactAreaRemovesInteractionContinuously();
		movingSolidPressureClosesWorkAndImpulse();
		sharedVelocityTrajectoriesCrossRefinementContinuously();
		interfaceExtensionPreservesLiquidTracesAndGasVolume();
		conservativeCoordinatesPreserveConstraintsAndVirtualWork();
		pressureReferencePreservesSmallResolvedForces();
		interfacePressureJumpPreservesGasPressure();
		internalInterfacePressureJumpPreservesEquilibriumAndWork();
		polygonSurfaceEnergyPreservesGeometricInvariants();
		obliqueSurfaceEnergyPreservesVirtualWork();
		variableSurfaceEnergyRetainsTangentialForce();
		LiquidConformingVelocityValidation.validate();
		LiquidCharacteristicValidation.validate();
		System.out.println("Liquid control-volume pressure and viscosity validation passed");
	}
	private static void scalarDiffusionPreservesAffineFluxAndRefinement() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var mesh = new LiquidControlVolumeMesh(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube.clip(1, 1, 0, 1)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube.clip(-1, -1, 0, -1))), 1, 1,
				(c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var metrics = ControlVolumeGradient.build(mesh.cells, mesh.faces);
		var shared = new double[mesh.faces.size()];
		for (int c = 0; c < metrics.length; c++) {
			var g = metrics[c]; var operator = new ControlVolumeDiffusion(g);
			double k = c == 0 ? 3 : 6;
			// T is continuous at x+y=1; k grad(T).n is identical on both sides.
			double[] slope = {1 + 12 / k, -1 + 12 / k, 2};
			var delta = new double[g.indices.length]; var flux = delta.clone(); var gradient = new double[3];
			for (int i = 0; i < delta.length; i++) for (int a = 0; a < 3; a++) delta[i] += slope[a] * g.displacement[i].coordinate(a);
			double energy = operator.apply(delta, flux, 1, 0, gradient);
			close(energy, g.volume * (slope[0] * slope[0] + slope[1] * slope[1] + slope[2] * slope[2]), 1e-12);
			for (int a = 0; a < 3; a++) close(gradient[a], slope[a], 1e-12);
			double cell = 0;
			for (int i = 0; i < delta.length; i++) {
				int f = g.indices[i]; var area = mesh.faces.get(f).surface().areaVector();
				double exact = g.sign[i] * (area.x() * slope[0] + area.y() * slope[1] + area.z() * slope[2]);
				close(flux[i], exact, 1e-12); cell -= k * flux[i]; shared[f] += k * flux[i];
			}
			close(cell, 0, 1e-12);
			var first = delta.clone(); var second = delta.clone(); var aFlux = flux.clone(); var bFlux = flux.clone();
			for (int i = 0; i < delta.length; i++) { first[i] = Math.sin(i + 1); second[i] = Math.cos(2 * i + 1); }
			double positive = operator.apply(first, aFlux, 1, 0, gradient);
			operator.apply(second, bFlux, 1, 0, gradient);
			double work = 0, ab = 0, ba = 0;
			for (int i = 0; i < delta.length; i++) { work += first[i] * aFlux[i]; ab += first[i] * bFlux[i]; ba += second[i] * aFlux[i]; }
			if (!(positive > 0)) throw new AssertionError("Scalar diffusion has non-positive energy");
			close(work, positive, 1e-12); close(ab, ba, 1e-12);
			java.util.Arrays.fill(delta, 0);
			if (operator.apply(delta, flux, 1, 0, gradient) != 0) throw new AssertionError("Uniform scalar field diffuses");
		}
		for (int f = 0; f < mesh.faces.size(); f++) if (mesh.faces.get(f).second() >= 0) close(shared[f], 0, 1e-12);
		double previousError = Double.POSITIVE_INFINITY;
		for (int n : new int[]{2, 4, 8}) {
			double h = 1.0 / n; int level = Integer.numberOfTrailingZeros(n);
			var leaves = new ArrayList<LiquidGeometryRefinement.Cell>();
			for (int x = 0; x < n; x++) leaves.add(new LiquidGeometryRefinement.Cell(level, x, 0, 0, LiquidPolyhedron.box(x * h, 0, 0, (x + 1) * h, h, h)));
			var strip = new LiquidControlVolumeMesh(leaves, 1, 1, (c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
			var field = new double[n]; for (int c = 0; c < n; c++) field[c] = Math.cos(Math.PI * strip.cells.get(c).geometry().centroid().x());
			var faceField = new double[strip.faces.size()];
			for (int f = 0; f < faceField.length; f++) { var face = strip.faces.get(f); faceField[f] = face.second() < 0 ? field[face.first()] : 0.5 * (field[face.first()] + field[face.second()]); }
			var incidence = ControlVolumeGradient.build(strip.cells, strip.faces); double sum = 0;
			double eigenvalue = 4 / (h * h) * Math.pow(Math.sin(Math.PI * h / 2), 2);
			for (int c = 0; c < n; c++) {
				var g = incidence[c]; var delta = new double[g.indices.length]; var flux = delta.clone();
				for (int i = 0; i < delta.length; i++) delta[i] = faceField[g.indices[i]] - field[c];
				new ControlVolumeDiffusion(g).apply(delta, flux, 1, 0, new double[3]);
				double loss = 0; for (double value : flux) loss -= value;
				close(loss / g.volume, eigenvalue * field[c], 1e-10); sum += loss;
			}
			close(sum, 0, 1e-12);
			double error = Math.abs(eigenvalue - Math.PI * Math.PI);
			if (error >= previousError * 0.3) throw new AssertionError("Resolved scalar diffusion fails second-order Fourier refinement");
			previousError = error;
		}
	}
	private static void sharedFaceHeatFluxClosesOnObliqueAndRefinedCells() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var mesh = new LiquidControlVolumeMesh(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube.clip(1, 1, 0, 1)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube.clip(-1, -1, 0, -1))), 1, 1,
				(c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		double[] k = {3, 6}; var temperature = new double[2]; var prescribed = new double[mesh.faces.size()]; java.util.Arrays.fill(prescribed, Double.NaN);
		for (int c = 0; c < 2; c++) {
			var p = mesh.cells.get(c).geometry().centroid(); temperature[c] = 300 + p.x() - p.y() + 2 * p.z() + 12 / k[c] * (p.x() + p.y() - 1);
		}
		for (int f = 0; f < prescribed.length; f++) {
			var face = mesh.faces.get(f); if (face.second() >= 0) continue;
			var p = face.surface().centroid(); prescribed[f] = 300 + p.x() - p.y() + 2 * p.z() + 12 / k[face.first()] * (p.x() + p.y() - 1);
		}
		var operator = new ControlVolumeHeatFlux(mesh, k);
		var result = operator.solve(temperature, prescribed, 1e-11, 256);
		for (int f = 0; f < prescribed.length; f++) if (mesh.faces.get(f).second() >= 0) {
			var p = mesh.faces.get(f).surface().centroid(); close(result.faceTemperature()[f], 300 + p.x() - p.y() + 2 * p.z(), 1e-11);
			close(result.faceResidual()[f], 0, 1e-11);
		}
		for (double rate : result.cellHeating()) close(rate, 0, 1e-11);
		close(result.reservoirHeating(), 0, 1e-11);
		java.util.Arrays.fill(prescribed, Double.NaN);
		result = operator.solve(new double[]{290, 310}, prescribed, 1e-10, 256);
		if (!(result.cellHeating()[0] > 0 && result.cellHeating()[1] < 0 && result.diffusionEnergy() > 0))
			throw new AssertionError("Shared oblique thermal flux reversed the temperature difference");
		close(result.cellHeating()[0] + result.cellHeating()[1], 0, 1e-9);
		close(result.reservoirHeating(), 0, 0);
		for (double residual : result.faceResidual()) close(residual, 0, 1e-10);
		var shifted = operator.solve(new double[]{290 + 1e10, 310 + 1e10}, prescribed, 1e-10, 256);
		if (!java.util.Arrays.equals(result.cellHeating(), shifted.cellHeating()))
			throw new AssertionError("Absolute temperature reference obscured a resolved heat flux");
		for (int f = 0; f < prescribed.length; f++) if (mesh.faces.get(f).second() < 0) prescribed[f] = 330;
		var reservoir = operator.solve(new double[]{290, 310}, prescribed, 1e-10, 256);
		if (!(reservoir.cellHeating()[0] > 0 && reservoir.cellHeating()[1] > 0 && reservoir.reservoirHeating() > 0))
			throw new AssertionError("Fixed-temperature walls failed to heat finite controls");
		close(reservoir.cellHeating()[0] + reservoir.cellHeating()[1], reservoir.reservoirHeating(), 1e-10);
		java.util.Arrays.fill(prescribed, Double.NaN);
		result = new ControlVolumeHeatFlux(mesh, new double[]{3, 0}).solve(new double[]{290, 310}, prescribed, 1e-10, 256);
		for (double rate : result.cellHeating()) close(rate, 0, 1e-9);
		result = new ControlVolumeHeatFlux(mesh, new double[]{0, 0}).solve(temperature, prescribed, 1e-10, 256);
		if (result.iterations() != 0 || result.diffusionEnergy() != 0) throw new AssertionError("Insulating controls retained diffusion work");
		var leaves = new ArrayList<LiquidGeometryRefinement.Cell>();
		leaves.add(new LiquidGeometryRefinement.Cell(1, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 0.5, 0.5, 0.5)));
		leaves.addAll(LiquidGeometryRefinement.children(new LiquidGeometryRefinement.Cell(1, 1, 0, 0, LiquidPolyhedron.box(0.5, 0, 0, 1, 0.5, 0.5))));
		var refined = new LiquidControlVolumeMesh(leaves, 1, 1, (c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var conductivities = new double[leaves.size()]; java.util.Arrays.fill(conductivities, 5);
		temperature = new double[leaves.size()]; prescribed = new double[refined.faces.size()];
		for (int c = 0; c < temperature.length; c++) { var p = refined.cells.get(c).geometry().centroid(); temperature[c] = 300 + p.x() + 2 * p.y() - 3 * p.z(); }
		for (int f = 0; f < prescribed.length; f++) { var face = refined.faces.get(f); var p = face.surface().centroid(); prescribed[f] = face.second() >= 0 ? Double.NaN : 300 + p.x() + 2 * p.y() - 3 * p.z(); }
		result = new ControlVolumeHeatFlux(refined, conductivities).solve(temperature, prescribed, 1e-10, 256);
		for (int f = 0; f < prescribed.length; f++) { var p = refined.faces.get(f).surface().centroid(); close(result.faceTemperature()[f], 300 + p.x() + 2 * p.y() - 3 * p.z(), 1e-10); }
		for (double rate : result.cellHeating()) close(rate, 0, 1e-9);
		if (result.iterations() == 0) throw new AssertionError("Coarse/fine face test skipped its non-orthogonal temperature solve");
	}
	private static void pressureInertiaSurvivesTinyAuxiliaryFaceAreas() {
		conjugateDirectionsResolveAnisotropicEnergy();
		pressureInertiaKeepsNearKernelAffineEnergy();
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var split = java.util.List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube.clip(1, 0, 0, Math.scalb(1.0, -60))),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube.clip(-1, 0, 0, -Math.scalb(1.0, -60))));
		var geometry = LiquidPolyhedron.restoredExact(LiquidExactPolygon.splitEdges(LiquidExteriorGeometry.of(split)), cube.measure());
		var mesh = new LiquidControlVolumeMesh(java.util.List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, geometry)), 1, 1000,
				(c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		if (mesh.faces.size() != 6) throw new AssertionError("Auxiliary coplanar fragments became independent pressure faces");
		for (var gradient : ControlVolumeGradient.build(mesh.cells, mesh.faces)) {
			var metric = new ControlVolumeFluxMetric(gradient, 1000);
			if (!(metric.minimumEigenvalue() > 0)) throw new AssertionError("Auxiliary face area defeated a resolved positive pressure inertia");
		}
		var result = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[3], GRAVITY, 0.01, 1e-10, 512);
		close(result.cellVelocity()[1], -0.0981, 1e-12);
	}
	private static void pressureInertiaKeepsNearKernelAffineEnergy() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1); var surfaces = new ArrayList<LiquidExactPolygon>();
		var apex = LiquidExactPolygon.vertex(new LiquidExactPolygon.Fraction[]{LiquidExactPolygon.Fraction.of(0.5), LiquidExactPolygon.Fraction.of(0.5),
				LiquidExactPolygon.Fraction.of(Math.nextUp(Math.sqrt(2) - 1))});
		for (var face : LiquidExactPolygon.faces(cube)) {
			if (face.areaVector()[2].signum() <= 0) surfaces.add(face);
			else for (int e = 0; e < face.vertices().size(); e++) surfaces.add(LiquidExactPolygon.triangle(apex, face.vertices().get(e), face.vertices().get((e + 1) % face.vertices().size())));
		}
		var shape = LiquidPolyhedron.restoredExact(surfaces); var mesh = new LiquidControlVolumeMesh(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, shape)),
				1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		var gradient = ControlVolumeGradient.build(mesh.cells, mesh.faces)[0]; var metric = new ControlVolumeFluxMetric(gradient, 1000);
		var rate = new double[gradient.indices.length]; for (int i = 0; i < rate.length; i++) rate[i] = gradient.exactBasis[i][2].multiply(gradient.exactVolume).rounded();
		var action = metric.multiply(rate); var error = metric.evaluationError(action, new double[rate.length]);
		var energy = LiquidExactPolygon.Fraction.ZERO; var bound = energy;
		for (int i = 0; i < rate.length; i++) {
			var flow = LiquidExactPolygon.Fraction.of(rate[i]); energy = energy.add(flow.multiply(LiquidExactPolygon.Fraction.of(action[i])));
			bound = bound.add(flow.absolute().multiply(LiquidExactPolygon.Fraction.of(error[i])));
		}
		if (!(metric.minimumEigenvalue() > 0) || energy.signum() <= 0
				|| energy.subtract(gradient.exactVolume.multiply(LiquidExactPolygon.Fraction.of(1000))).absolute().compareTo(bound) > 0)
			throw new AssertionError("Near-kernel surface lost positive affine kinetic energy in pressure matrix cancellation");
	}
	/** Known orthogonal eigenmodes, with face-coordinate scales spanning the native sliver range. */
	private static void conjugateDirectionsResolveAnisotropicEnergy() {
		var certified = new ConservativeConjugateDirections(new double[]{1, 1});
		var remaining = certified.direction(new double[]{1e-16, 1e-80}, new double[]{1e-16, 0});
		double resolvedForce = ConservativeConjugateDirections.dot(new double[]{1e-16, 1e-80}, remaining);
		double resolvedInertia = ConservativeConjugateDirections.dot(remaining, remaining);
		if (remaining[0] != 0 || resolvedForce / resolvedInertia * remaining[1] != 1e-80)
			throw new AssertionError("Resolved gradient observation obscured a smaller representable physical force");
		for (double magnitude : new double[]{1e-200, 1e200}) {
			var scalar = new ConservativeConjugateDirections(new double[]{1, 1});
			for (int correction = 0; correction < 2; correction++) {
				var direction = scalar.direction(new double[]{magnitude, 0}); scalar.remember(direction, direction);
				double solved = ConservativeConjugateDirections.dot(new double[]{magnitude, 0}, direction)
						/ ConservativeConjugateDirections.dot(direction, direction) * direction[0];
				if (solved != magnitude) throw new AssertionError("Conjugate correction lost a representable force within its evaluated basis");
			}
		}
		int n = 32; var matrix = new double[n][n]; var diagonal = new double[n]; var expected = new double[n];
		for (int i = 0; i < n; i++) { diagonal[i] = Math.scalb(1.0, 16 * i); expected[i] = (i % 5 - 2) / Math.sqrt(diagonal[i]); }
		for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
			double first = Math.scalb(1.0, -i), second = Math.scalb(1.0, -j), total = 2 - Math.scalb(1.0, 1 - n);
			matrix[i][j] = ((i == j ? first : 0) - (first + second) / 16 + total / 256) * Math.sqrt(diagonal[i]) * Math.sqrt(diagonal[j]);
		}
		var rhs = conjugateAction(matrix, expected); var solution = new double[n]; var directions = new ConservativeConjugateDirections(diagonal);
		for (int iteration = 0; iteration <= n; iteration++) {
			var evaluated = conjugateAction(matrix, solution); var residual = rhs.clone(); boolean resolved = true;
			for (int i = 0; i < n; i++) {
				residual[i] -= evaluated[i]; double magnitude = Math.abs(rhs[i]);
				for (int j = 0; j < n; j++) magnitude += Math.abs(matrix[i][j] * solution[j]);
				resolved &= Math.abs(residual[i]) <= 2 * n * Math.ulp(magnitude);
			}
			if (resolved) return;
			if (iteration == n) throw new AssertionError("Conjugate directions lost resolved anisotropic energy modes");
			var direction = directions.direction(residual); var action = conjugateAction(matrix, direction); double force = 0, inertia = 0;
			for (int i = 0; i < n; i++) { force += residual[i] * direction[i]; inertia += direction[i] * action[i]; }
			directions.remember(direction, action); for (int i = 0; i < n; i++) solution[i] += force / inertia * direction[i];
		}
	}
	private static double[] conjugateAction(double[][] matrix, double[] value) {
		var result = new double[value.length]; for (int i = 0; i < result.length; i++) for (int j = 0; j < result.length; j++) result[i] += matrix[i][j] * value[j]; return result;
	}
	private static void boundedArithmeticContainsExactEndpointValues() {
		var samples = List.of(LiquidBoundedArithmetic.Value.of(LiquidExactPolygon.Fraction.of(1).divide(LiquidExactPolygon.Fraction.of(7))),
				LiquidBoundedArithmetic.Value.of(LiquidExactPolygon.Fraction.of(-1).divide(LiquidExactPolygon.Fraction.of(3))),
				new LiquidBoundedArithmetic.Value(1.3, 0.6), new LiquidBoundedArithmetic.Value(3, 2.9),
				new LiquidBoundedArithmetic.Value(1e-200, Math.ulp(1e-200)), new LiquidBoundedArithmetic.Value(-1e-200, Math.ulp(1e-200)));
		for (var a : samples) for (var b : samples) for (int operation = 0; operation < 3; operation++) {
			var result = operation == 0 ? a.add(b) : operation == 1 ? a.multiply(b) : a.divide(b);
			for (int first : new int[]{-1, 1}) for (int second : new int[]{-1, 1}) {
				var x = LiquidExactPolygon.Fraction.of(a.value()).add(LiquidExactPolygon.Fraction.of(a.error()).multiply(LiquidExactPolygon.Fraction.of(first)));
				var y = LiquidExactPolygon.Fraction.of(b.value()).add(LiquidExactPolygon.Fraction.of(b.error()).multiply(LiquidExactPolygon.Fraction.of(second)));
				var exact = operation == 0 ? x.add(y) : operation == 1 ? x.multiply(y) : x.divide(y);
				if (exact.subtract(LiquidExactPolygon.Fraction.of(result.value())).absolute().subtract(LiquidExactPolygon.Fraction.of(result.error())).signum() > 0)
					throw new AssertionError("Bounded arithmetic lost an exact interval endpoint, operation=" + operation);
			}
		}
	}
	private static void pressureMetricCertificatesContainExactCoefficients() {
		for (var shape : List.of(LiquidPolyhedron.box(0, 0, 0, 0.5, 0.5, 0.5),
				LiquidPolyhedron.box(0, 0, 0, 0.5, 1.0 / 1024, 0.5),
				LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(1, 2, 3, 3).clip(-1, -2, -3, -3 + Math.scalb(1.0, -24)),
				LiquidPolyhedron.box(0, 0, 0, 0.5, 0.5, 0.5).clip(-2, 3, 1, 0.7))) {
			var mesh = new LiquidControlVolumeMesh(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, shape)), 1, 1000,
					(c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			for (var gradient : ControlVolumeGradient.build(mesh.cells, mesh.faces)) {
				var observed = LiquidPressureEnergyInverse.build(gradient, 1000); var exact = LiquidPressureEnergyInverseOracle.build(gradient, 1000);
				for (int i = 0; i < exact.length; i++) for (int j = 0; j < exact.length; j++) {
					if (!(Math.abs(observed.matrix()[i][j] - exact[i][j]) <= observed.error()[i][j] + Math.ulp(exact[i][j])))
						throw new AssertionError("Pressure metric coefficient escaped its exact-reference error certificate");
				}
				var metric = new ControlVolumeFluxMetric(gradient, 1000);
				for (double scale : new double[]{1, Double.MIN_VALUE, Math.scalb(1.0, -600), Math.scalb(1.0, 600)}) {
					var input = new double[exact.length]; for (int i = 0; i < input.length; i++) input[i] = scale * Math.sin(i + 0.25);
					var actual = metric.multiply(input); var error = metric.evaluationError(actual, new double[input.length]);
					for (int i = 0; i < exact.length; i++) {
						double reference = 0, rounding = 0;
						for (int j = 0; j < exact.length; j++) { reference += exact[i][j] * input[j]; rounding += Math.ulp(exact[i][j] * input[j]) + Math.ulp(reference); }
						if (!(Math.abs(actual[i] - reference) <= error[i] + rounding)) throw new AssertionError("Pressure action escaped its propagated coefficient error");
					}
				}
			}
		}
	}
	private static void variableSurfaceEnergyRetainsTangentialForce() {
		var cells = List.of(new LiquidGeometryRefinement.Cell(1, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 0.5, 0.5, 0.5)),
				new LiquidGeometryRefinement.Cell(1, 1, 0, 0, LiquidPolyhedron.box(0.5, 0, 0, 1, 0.5, 0.5)));
		var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		double[] sigma = new double[mesh.faces.size()];
		for (int f = 0; f < sigma.length; f++) if (mesh.faces.get(f).surface().areaVector().y() > 0) sigma[f] = mesh.faces.get(f).first() == 0 ? 0.125 : 0.25;
		var energy = new LiquidSurfaceEnergy(mesh, sigma); boolean sharedForce = false;
		for (var node : energy.tangentialForce.entrySet()) {
			var position = node.getKey().rounded();
			if (position.x() == 0.5 && position.y() == 0.5) {
				if (!(node.getValue().getFirst().signum() > 0)) throw new AssertionError("Surface coefficient gradient reversed its tangential force");
				sharedForce = true;
			}
		}
		if (!sharedForce) throw new AssertionError("Surface coefficient gradient omitted its shared vertices");
		for (var jump : energy.pressureJump) if (jump.signum() != 0) throw new AssertionError("Planar tangential stress became curvature pressure");
	}
	private static void obliqueSurfaceEnergyPreservesVirtualWork() {
		var origin = new LiquidPolyhedron.Point(0.5, 0.5, 0.5);
		var geometry = LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75)
				.transformed(new double[]{1, 0.25, 0, 0, 1, 0.125, 0, 0, 1}, origin, new LiquidPolyhedron.Point(0, 0, 0));
		var mesh = new LiquidControlVolumeMesh(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, geometry)), 1, 1000,
				(c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		double[] sigma = new double[mesh.faces.size()]; java.util.Arrays.fill(sigma, 0.125); var energy = new LiquidSurfaceEnergy(mesh, sigma);
		var rates = new LiquidExactPolygon.Fraction[mesh.faces.size()];
		for (int f = 0; f < rates.length; f++) rates[f] = LiquidExactPolygon.Fraction.of(Math.sin(f + 0.5));
		var velocity = energy.vertexVelocity(rates); var pointWork = LiquidExactPolygon.Fraction.ZERO; var faceWork = pointWork;
		for (var node : energy.force.entrySet()) for (int a = 0; a < 3; a++) pointWork = pointWork.add(node.getValue().get(a).multiply(velocity.get(node.getKey()).get(a)));
		for (int f = 0; f < rates.length; f++) faceWork = faceWork.subtract(energy.pressureJump[f].multiply(rates[f]));
		if (!pointWork.equals(faceWork)) throw new AssertionError("Oblique surface pressure lost exact virtual work");
		double derivative = 0;
		for (var node : energy.force.entrySet()) for (int a = 0; a < 3; a++)
			derivative -= node.getValue().get(a).rounded() * (node.getKey().rounded().coordinate(a) - 0.5) * new double[]{0.125, -0.0625, -0.0625}[a];
		double previous = Double.POSITIVE_INFINITY;
		for (double epsilon : new double[]{1.0 / 128, 1.0 / 256}) {
			double[] diagonal = {0.125, -0.0625, -0.0625}; double[] areas = new double[2];
			for (int side = 0; side < 2; side++) {
				double sign = side == 0 ? -1 : 1;
				var transformed = geometry.transformed(new double[]{1 + sign * epsilon * diagonal[0], 0, 0, 0, 1 + sign * epsilon * diagonal[1], 0, 0, 0, 1 + sign * epsilon * diagonal[2]}, origin, new LiquidPolyhedron.Point(0, 0, 0));
				for (var surface : transformed.surfaces()) areas[side] += 0.125 * surface.area();
			}
			double error = Math.abs((areas[1] - areas[0]) / (2 * epsilon) - derivative);
			if (!(error < previous / 3)) throw new AssertionError("Area-gradient force does not converge to the geometric energy derivative");
			previous = error;
		}
	}
	private static void internalInterfacePressureJumpPreservesEquilibriumAndWork() {
		var cells = List.of(new LiquidGeometryRefinement.Cell(1, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 0.5, 0.5, 0.5)),
				new LiquidGeometryRefinement.Cell(1, 1, 0, 0, LiquidPolyhedron.box(0.5, 0, 0, 1, 0.5, 0.5)));
		for (double reference : new double[]{0, 100000}) for (boolean equilibrium : new boolean[]{true, false}) {
			var mesh = new LiquidControlVolumeMesh(cells, 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(s.areaVector().x() == 0,
					s.areaVector().x() == 0 ? 0 : reference + (equilibrium && c == 0 ? 64 : 0)));
			var faces = mesh.faces.stream().map(f -> new LiquidControlVolumeProjection.Face(f.first(), f.second(), f.surface(),
					f.second() >= 0 ? 64 : f.prescribed(), f.areaVector(), f.centroid())).toList();
			var result = new LiquidControlVolumeProjection(mesh.cells, faces).project(new double[6], new LiquidPolyhedron.Point(0, 0, 0), 0.025, 1e-11, 128);
			requireExactContinuity(2, faces, result);
			if (equilibrium) {
				close(result.pressure()[0], reference + 64, 1e-9); close(result.pressure()[1], reference, 1e-9);
				for (var rate : result.volumeRate()) if (rate.signum() != 0) throw new AssertionError("Internal equilibrium pressure jump created flow");
			} else {
				double kinetic = 0, momentum = 0;
				for (int c = 0; c < 2; c++) for (int a = 0; a < 3; a++) {
					double mass = 1000 * mesh.cells.get(c).geometry().moments().volume(), velocity = result.cellVelocity()[3 * c + a];
					kinetic += 0.5 * mass * velocity * velocity; if (a == 0) momentum += mass * velocity;
				}
				close(kinetic, result.boundaryWorkJoules() + result.internalInterfaceWorkJoules() - result.pressureDissipationJoules(), 1e-12);
				close(momentum, result.boundaryImpulse().x() + result.internalInterfaceImpulse().x(), 1e-12);
			}
		}
	}
	private static void polygonSurfaceEnergyPreservesGeometricInvariants() {
		var cell = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75));
		var mesh = new LiquidControlVolumeMesh(List.of(cell), 1, 1000, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		double[] coefficients = new double[mesh.faces.size()]; java.util.Arrays.fill(coefficients, 0.125);
		var energy = new LiquidSurfaceEnergy(mesh, coefficients); close(energy.joules, 0.1875, 0);
		for (var jump : energy.pressureJump) close(jump.rounded(), 1, 0);
		var resultant = new LiquidExactPolygon.Fraction[]{LiquidExactPolygon.Fraction.ZERO, LiquidExactPolygon.Fraction.ZERO, LiquidExactPolygon.Fraction.ZERO};
		var torque = resultant.clone();
		for (var node : energy.force.entrySet()) {
			var x = node.getKey().coordinates(); var force = node.getValue();
			for (int a = 0; a < 3; a++) {
				resultant[a] = resultant[a].add(force.get(a));
				torque[a] = torque[a].add(x[(a + 1) % 3].multiply(force.get((a + 2) % 3)).subtract(x[(a + 2) % 3].multiply(force.get((a + 1) % 3))));
			}
		}
		for (int a = 0; a < 3; a++) if (resultant[a].signum() != 0 || torque[a].signum() != 0) throw new AssertionError("Area-gradient force lost translation or rotation invariance");
		for (var force : energy.tangentialForce.values()) for (var component : force) if (component.signum() != 0) throw new AssertionError("Uniform surface energy acquired tangential residual");
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (int f = 0; f < mesh.faces.size(); f++) { var face = mesh.faces.get(f); faces.add(new LiquidControlVolumeProjection.Face(face.first(), face.second(), face.surface(), energy.pressureJump[f].rounded(), face.areaVector(), face.centroid())); }
		var result = new LiquidControlVolumeProjection(mesh.cells, faces).project(new double[3], new LiquidPolyhedron.Point(0, 0, 0), 0.025, 1e-11, 128);
		for (var rate : result.volumeRate()) if (rate.signum() != 0) throw new AssertionError("Constant discrete surface pressure created parasitic flow");
	}
	private static void interfacePressureJumpPreservesGasPressure() {
		var geometry = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var faces = geometry.surfaces().stream().map(s -> new LiquidControlVolumeProjection.Face(0, -1, s, 102000)).toList();
		var result = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces,
				List.of(new LiquidControlVolumeProjection.GasNode(100000, 1e-6)), new int[faces.size()])
				.project(new double[3], new LiquidPolyhedron.Point(0, 0, 0), 0.025, 1e-11, 128);
		close(result.pressure()[0], 102000, 1e-9); close(result.gasPressure()[0], 100000, 0);
		close(result.gasPressureChange()[0], 0, 0); close(result.gasVolumeChange()[0], 0, 0);
		for (double velocity : result.cellVelocity()) close(velocity, 0, 0); requireExactContinuity(1, faces, result);
	}

	private static void pressureReferencePreservesSmallResolvedForces() {
		for (double size : new double[] {0.125, 1e-4, 1e-7, 1e-10, 1e-12}) for (double reference : new double[] {0, 100000}) {
			var geometry = LiquidPolyhedron.box(0, 0, 0, size, size, size); var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
			for (var surface : geometry.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0,
					surface.areaVector().y() > 0 ? -1 : -2, surface, surface.areaVector().y() > 0 ? reference : 0));
			var result = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces)
					.project(new double[3], GRAVITY, 0.025, 1e-11, 128);
			requireExactContinuity(1, faces, result);
			for (double velocity : result.cellVelocity()) close(velocity, 0, 0);
			close(result.pressureGradient()[0].y(), 1000 * GRAVITY.y(), 1e-9, "Absolute gas pressure destroyed a resolved hydrostatic gradient");
			double expected = -1000 * geometry.moments().volume() * GRAVITY.y() * 0.025;
			close(result.boundaryImpulse().y() / expected, 1, 1e-11, "Absolute gas pressure destroyed a small resolved pressure impulse");
			close(result.boundaryWorkJoules(), 0, 0);
		}
	}

	private static void conservativeCoordinatesPreserveConstraintsAndVirtualWork() {
		var zero = LiquidExactPolygon.Fraction.ZERO;
		int[] first = {0, 1, 2, 0, 0, 1, 2}, second = {1, 2, 0, 3, 4, 4, 4};
		var fixed = new LiquidExactPolygon.Fraction[first.length]; fixed[6] = LiquidExactPolygon.Fraction.of(0.125);
		var metric = new double[first.length]; java.util.Arrays.fill(metric, 1); metric[3] = 1e24;
		var basis = new ConservativeFluxCoordinates(5, first, second, new boolean[] {true, true, true, false, false}, fixed, metric);
		var cycleWeights = basis.cycleWeights(metric); int stiff = 0;
		for (int i = 0; i < cycleWeights.length; i++) {
			if (basis.face(i) == 3) stiff++; else if (!(cycleWeights[i] < 10)) throw new AssertionError("Flux tree spread one small-face stiffness into unrelated cycles");
		}
		if (stiff != 1) throw new AssertionError("Flux tree eliminated the actual stiff face degree of freedom");
		var source = new LiquidExactPolygon.Fraction[] {zero, zero, zero, zero, zero};
		var exact = new LiquidExactPolygon.Fraction[basis.size()]; double[] variable = new double[basis.size()], dual = new double[first.length];
		for (int c = 0; c < exact.length; c++) { exact[c] = new LiquidExactPolygon.Fraction(java.math.BigInteger.valueOf(c % 2 == 0 ? 1 : -1), java.math.BigInteger.ONE.shiftLeft(100 * c).multiply(java.math.BigInteger.valueOf(3))); variable[c] = Math.sin(c + 0.7); }
		var rates = basis.liftExact(exact, source); var divergence = source.clone();
		for (int f = 0; f < rates.length; f++) { divergence[first[f]] = divergence[first[f]].add(rates[f]); divergence[second[f]] = divergence[second[f]].subtract(rates[f]); dual[f] = Math.cos(f + 0.3); }
		for (int c = 0; c < 3; c++) if (divergence[c].signum() != 0) throw new AssertionError("Flux coordinates lost continuity across disparate exact rates");
		if (!rates[6].equals(fixed[6])) throw new AssertionError("Flux coordinates changed a prescribed wall rate");
		var lifted = basis.lift(variable, new double[5], false); var transpose = basis.transpose(dual); double firstWork = 0, secondWork = 0;
		for (int f = 0; f < dual.length; f++) firstWork += lifted[f] * dual[f];
		for (int c = 0; c < variable.length; c++) secondWork += variable[c] * transpose[c];
		close(firstWork, secondWork, 1e-14, "Conservative flux gradient changed virtual work");
		var closed = new ConservativeFluxCoordinates(2, new int[] {0}, new int[] {1}, new boolean[] {true, true}, new LiquidExactPolygon.Fraction[] {fixed[6]});
		try {
			closed.liftExact(new LiquidExactPolygon.Fraction[0], new LiquidExactPolygon.Fraction[] {zero, zero});
			throw new AssertionError("Closed component accepted an incompatible prescribed volume rate");
		} catch (IllegalArgumentException expected) { }
		var compatible = closed.liftExact(new LiquidExactPolygon.Fraction[0], new LiquidExactPolygon.Fraction[] {fixed[6], fixed[6].negate()});
		if (!compatible[0].equals(fixed[6])) throw new AssertionError("Prescribed volume source changed its compatible face rate");
		var free = new ConservativeFluxCoordinates(2, new int[]{0}, new int[]{1}, new boolean[2], new LiquidExactPolygon.Fraction[1]);
		var large = LiquidExactPolygon.Fraction.of(1e100); var small = LiquidExactPolygon.Fraction.of(1).divide(LiquidExactPolygon.Fraction.of(7));
		var offset = new ConservativeFluxOffset(free, new LiquidExactPolygon.Fraction[]{large}, new LiquidExactPolygon.Fraction[]{zero, zero});
		var relative = offset.relativeTo(new LiquidExactPolygon.Fraction[]{large.add(small)}); var observed = relative.values(new double[1]);
		if (LiquidExactPolygon.Fraction.of(observed[0]).add(small).absolute().subtract(LiquidExactPolygon.Fraction.of(relative.error(new double[1])[0])).signum() > 0 || observed[0] == 0)
			throw new AssertionError("Flux reference subtraction lost a small exact rate difference");
		if (!java.util.Arrays.equals(offset.exact(new double[1]), relative.exact(new double[1]))) throw new AssertionError("Reference subtraction changed actual conservative rates");
		var cancellation = new ConservativeFluxOffset(free, new LiquidExactPolygon.Fraction[]{large.add(small)}, new LiquidExactPolygon.Fraction[]{zero, zero})
				.observe(new double[]{-1e100});
		if (cancellation.values()[0] == 0 || LiquidExactPolygon.Fraction.of(cancellation.values()[0]).subtract(small).absolute()
				.subtract(LiquidExactPolygon.Fraction.of(cancellation.error()[0])).signum() > 0)
			throw new AssertionError("Lifted rate observation lost the exact remainder of a cancelling increment");
		var shifted = new ConservativeFluxOffset(basis, exact, source); var observedField = shifted.observe(variable); var actualField = shifted.exact(variable);
		for (int f = 0; f < actualField.length; f++) if (LiquidExactPolygon.Fraction.of(observedField.values()[f]).subtract(actualField[f]).absolute()
				.subtract(LiquidExactPolygon.Fraction.of(observedField.error()[f])).signum() > 0)
			throw new AssertionError("Lifted rate observation certificate differs from actual prescribed-wall and conservative rates");
		exactInventoryAllocationPreservesShares();
	}

	private static void exactInventoryAllocationPreservesShares() {
		var zero = cc.ecisr.mmm.units.SignedDyadic.ZERO;
		var weights = new cc.ecisr.mmm.units.SignedDyadic[] {cc.ecisr.mmm.units.SignedDyadic.of(1), cc.ecisr.mmm.units.SignedDyadic.of(2), cc.ecisr.mmm.units.SignedDyadic.of(4)};
		var shares = ExactVolumeAllocation.allocate(cc.ecisr.mmm.units.SignedDyadic.of(1), weights, -60); var total = zero;
		for (int i = 0; i < shares.length; i++) {
			total = total.add(shares[i]); var units = shares[i].significand().shiftLeft(shares[i].exponent() + 60);
			var numeratorError = units.multiply(java.math.BigInteger.valueOf(7)).subtract(java.math.BigInteger.ONE.shiftLeft(60).multiply(java.math.BigInteger.valueOf(1 << i))).abs();
			if (numeratorError.compareTo(java.math.BigInteger.valueOf(7)) >= 0) throw new AssertionError("Inventory allocation exceeded its declared quantum");
		}
		if (!total.equals(cc.ecisr.mmm.units.SignedDyadic.of(1))) throw new AssertionError("Inventory allocation lost exact amount");
		var wide = new cc.ecisr.mmm.units.SignedDyadic(java.math.BigInteger.ONE.shiftLeft(56).add(java.math.BigInteger.ONE), -60);
		var equal = ExactVolumeAllocation.allocate(wide, new cc.ecisr.mmm.units.SignedDyadic[] {weights[0], weights[0]}, wide.exponent() - 1);
		var half = new cc.ecisr.mmm.units.SignedDyadic(wide.significand(), wide.exponent() - 1);
		if (!equal[0].equals(half) || !equal[1].equals(half)) throw new AssertionError("Rate allocation rounded a representable exact proportion");
	}

	private static LiquidExactPolygon.Fraction[] faceRates(LiquidControlVolumeMesh mesh, double[] traces) {
		var rates = new LiquidExactPolygon.Fraction[traces.length];
		for (int f = 0; f < rates.length; f++) rates[f] = LiquidExactPolygon.Fraction.of(mesh.faces.get(f).surface().area() * traces[f]);
		return rates;
	}
	private static void requireExactExtensionContinuity(LiquidControlVolumeMesh mesh, LiquidInterfaceVelocityExtension.Result result) {
		var balance = new LiquidExactPolygon.Fraction[mesh.cells.size()]; java.util.Arrays.fill(balance, LiquidExactPolygon.Fraction.ZERO);
		for (int f = 0; f < mesh.faces.size(); f++) {
			var face = mesh.faces.get(f); balance[face.first()] = balance[face.first()].add(result.volumeRate()[f]);
			if (face.second() >= 0) balance[face.second()] = balance[face.second()].subtract(result.volumeRate()[f]);
		}
		for (int c = 0; c < balance.length; c++) if (!balance[c].equals(result.volumeSource()[c]))
			throw new AssertionError("Interface extension lost exact control-volume continuity");
		if (!(result.divergenceResidual() <= result.divergenceRoundoffBound())) throw new AssertionError("Observed extension exceeded roundoff bound");
	}
	private static void requireSharedVelocityTriangles(LiquidControlVolumeMesh mesh, LiquidControlVolumeVelocity.Result[] velocity) {
		var first = new ArrayList<java.util.HashSet<List<LiquidPolyhedron.Point>>>(); var second = new ArrayList<java.util.HashSet<List<LiquidPolyhedron.Point>>>();
		var firstFlow = new ArrayList<java.util.HashMap<List<LiquidPolyhedron.Point>, LiquidExactPolygon.Fraction>>();
		var secondFlow = new ArrayList<java.util.HashMap<List<LiquidPolyhedron.Point>, LiquidExactPolygon.Fraction>>();
		for (int f = 0; f < mesh.faces.size(); f++) { first.add(new java.util.HashSet<>()); second.add(new java.util.HashSet<>()); }
		for (int f = 0; f < mesh.faces.size(); f++) { firstFlow.add(new java.util.HashMap<>()); secondFlow.add(new java.util.HashMap<>()); }
		for (int c = 0; c < velocity.length; c++) for (var tetra : velocity[c].tetrahedra()) {
			var key = new ArrayList<>(tetra.vertices().subList(1, 4)); key.sort(java.util.Comparator.comparingDouble(LiquidPolyhedron.Point::x)
					.thenComparingDouble(LiquidPolyhedron.Point::y).thenComparingDouble(LiquidPolyhedron.Point::z));
			int f = tetra.boundaryFace(); (mesh.faces.get(f).first() == c ? first : second).get(f).add(List.copyOf(key));
			(mesh.faces.get(f).first() == c ? firstFlow : secondFlow).get(f).put(List.copyOf(key), tetra.outwardFlux().getFirst());
			var balance = LiquidExactPolygon.Fraction.ZERO; for (var rate : tetra.outwardFlux()) balance = balance.add(rate);
			if (balance.signum() == 0) close(tetra.isotropicGradient(), 0, 0, "Incompressible tetrahedron acquired a dilatation");
		}
		for (int f = 0; f < mesh.faces.size(); f++) if (mesh.faces.get(f).second() >= 0) {
			if (!first.get(f).equals(second.get(f))) throw new AssertionError("Shared velocity face has different triangles on its two sides");
			for (var key : first.get(f)) if (!firstFlow.get(f).get(key).equals(secondFlow.get(f).get(key).negate()))
				throw new AssertionError("Shared velocity triangle lost its exact common volume rate");
		}
	}
	private static void requireVelocityRateClosure(LiquidControlVolumeMesh mesh, LiquidExactPolygon.Fraction[] submitted,
			LiquidControlVolumeVelocity.Result[] velocity) {
		for (int c = 0; c < velocity.length; c++) {
			var faces = new LiquidExactPolygon.Fraction[mesh.faces.size()]; java.util.Arrays.fill(faces, LiquidExactPolygon.Fraction.ZERO);
			for (var tetra : velocity[c].tetrahedra()) faces[tetra.boundaryFace()] = faces[tetra.boundaryFace()].add(tetra.outwardFlux().getFirst());
			for (int f : mesh.cellFaces[c]) if (!faces[f].equals(mesh.faces.get(f).first() == c ? submitted[f] : submitted[f].negate()))
				throw new AssertionError("Tetrahedral reconstruction changed a submitted control-volume rate");
		}
	}
	private static void interfaceExtensionPreservesLiquidTracesAndGasVolume() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube);
		var regions = LiquidPhasePartition.build(root, List.of(LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75)), 64);
		int[] liquid = regions.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray();
		for (double scale : new double[] {1, 0.125, 1.0 / 1024}) for (double speed : new double[] {0, 1}) {
			var mesh = new LiquidControlVolumeMesh(regions.stream().map(LiquidPhasePartition.Region::cell).toList(), scale, 1000,
					(c, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			var pressureFaces = new ArrayList<LiquidControlVolumeProjection.Face>(); var sharedFaces = new ArrayList<Integer>();
			for (int f = 0; f < mesh.faces.size(); f++) {
				var face = mesh.faces.get(f); boolean firstLiquid = liquid[face.first()] >= 0;
				if (!firstLiquid && !(face.second() >= 0 && liquid[face.second()] >= 0)) continue;
				var surface = face.surface(); var vector = surface.areaVector();
				if (!firstLiquid) surface = new LiquidPolyhedron.Surface(surface.area(), new LiquidPolyhedron.Point(-vector.x(), -vector.y(), -vector.z()), surface.centroid());
				pressureFaces.add(new LiquidControlVolumeProjection.Face(0, -1, surface, 100000)); sharedFaces.add(f);
			}
			var projected = new LiquidControlVolumeProjection(List.of(mesh.cells.getFirst()), pressureFaces)
					.project(new double[3], new LiquidPolyhedron.Point(speed, 0, 0), 1, 1e-11, 128);
			var submitted = new LiquidExactPolygon.Fraction[mesh.faces.size()]; java.util.Arrays.fill(submitted, LiquidExactPolygon.Fraction.ZERO);
			double[] primary = new double[3 * regions.size()]; System.arraycopy(projected.cellVelocity(), 0, primary, 0, 3);
			for (int i = 0; i < sharedFaces.size(); i++) {
				int f = sharedFaces.get(i); submitted[f] = liquid[mesh.faces.get(f).first()] >= 0 ? projected.volumeRate()[i] : projected.volumeRate()[i].negate();
			}
			var extended = LiquidInterfaceVelocityExtension.extend(mesh, liquid, submitted, primary, 1e-11, 512);
			requireExactExtensionContinuity(mesh, extended);
			for (int f = 0; f < submitted.length; f++) {
				var face = mesh.faces.get(f); double expected = speed * face.surface().areaVector().x() / face.surface().area();
				close(extended.normalVelocity()[f], expected, 1e-10, "Interface extension changed rigid translation");
				if (liquid[face.first()] >= 0 || face.second() >= 0 && liquid[face.second()] >= 0)
					if (!extended.volumeRate()[f].equals(submitted[f])) throw new AssertionError("Interface extension changed a submitted pressure rate");
			}
			for (int c = 0; c < regions.size(); c++) for (int a = 0; a < 3; a++) close(extended.cellVelocity()[3 * c + a], a == 0 ? speed : 0,
					1e-10, "Interface extension changed cell translation");
			var velocity = LiquidControlVolumeVelocity.reconstruct(mesh, extended.volumeRate(), extended.cellVelocity());
			requireSharedVelocityTriangles(mesh, velocity);
			requireVelocityRateClosure(mesh, extended.volumeRate(), velocity);
			var trajectory = new LiquidVelocityTrajectory(mesh, velocity); var start = new LiquidPolyhedron.Point(0.6 * scale, 0.45 * scale, 0.4 * scale);
			for (double duration : new double[] {0, 1e-6 * scale, 0.3 * scale}) {
				var moved = trajectory.advance(0, start, duration, 256);
				for (int a = 0; a < 3; a++) close(moved.position().coordinate(a) / scale, start.coordinate(a) / scale + (a == 0 ? speed * duration / scale : 0),
						1e-10, "Liquid characteristic failed across its free interface");
				if (speed > 0 && duration == 0.3 * scale && liquid[moved.cell()] >= 0) throw new AssertionError("Liquid characteristic did not enter gas velocity support");
			}
		}
		var sheet = cube.clip(0, 1, 0, 0.501).clip(0, -1, 0, -0.499);
		var sealed = new ArrayList<LiquidPhasePartition.Region>();
		var leaves = new ArrayList<LiquidGeometryRefinement.Cell>();
		for (var parent : LiquidGeometryRefinement.children(root)) leaves.addAll(LiquidGeometryRefinement.children(parent));
		for (var leaf : leaves) {
			var local = sheet;
			for (int a = 0; a < 3; a++) local = local.clip(a == 0 ? 1 : 0, a == 1 ? 1 : 0, a == 2 ? 1 : 0, leaf.minimum(a) + leaf.width())
					.clip(a == 0 ? -1 : 0, a == 1 ? -1 : 0, a == 2 ? -1 : 0, -leaf.minimum(a));
			sealed.addAll(LiquidPhasePartition.build(leaf, local.moments().volume() > 0 ? List.of(local) : List.of(), 16));
		}
		var mesh = new LiquidControlVolumeMesh(sealed.stream().map(LiquidPhasePartition.Region::cell).toList(), 1, 1000,
				(c, surface) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		int[] labels = sealed.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray();
		double[] traces = new double[mesh.faces.size()], primary = new double[3 * sealed.size()];
		for (int c = 0; c < sealed.size(); c++) if (labels[c] >= 0) primary[3 * c + 1] = mesh.cells.get(c).centroid().x() < 0.5 ? 0.05 : 0.15;
		for (int f = 0; f < traces.length; f++) {
			var face = mesh.faces.get(f);
			if (face.second() >= 0 && (labels[face.first()] >= 0 || labels[face.second()] >= 0)) {
				int source = labels[face.first()] >= 0 ? face.first() : face.second();
				traces[f] = primary[3 * source + 1] * face.surface().areaVector().y() / face.surface().area();
			}
		}
		var submittedSheetRates = faceRates(mesh, traces);
		var extended = LiquidInterfaceVelocityExtension.extend(mesh, labels, submittedSheetRates, primary, 1e-11, 512);
		requireExactExtensionContinuity(mesh, extended);
		if (extended.iterations() == 0) throw new AssertionError("Nonuniform interface displacement did not redistribute gas extension flux");
		double lowerMoment = 0, upperMoment = 0, lowerDisplacement = 0, upperDisplacement = 0;
		for (int c = 0; c < sealed.size(); c++) if (labels[c] < 0) {
			var cell = mesh.cells.get(c); double y = cell.centroid().y(), volume = cell.geometry().moments().volume();
			double expected = y < 0.25 ? 0.4 : y > 0.75 ? -0.4 : 0;
			close(extended.gasDilatation()[c], expected, 1e-10, "Closed gas velocity support lost its submitted volume displacement");
			if (y < 0.5) { lowerMoment += volume * extended.cellVelocity()[3 * c + 1]; lowerDisplacement += volume * extended.gasDilatation()[c]; }
			else { upperMoment += volume * extended.cellVelocity()[3 * c + 1]; upperDisplacement += volume * extended.gasDilatation()[c]; }
			for (int f : mesh.cellFaces[c]) {
				var face = mesh.faces.get(f); int neighbor = face.first() == c ? face.second() : face.first();
				if (neighbor >= 0 && labels[neighbor] >= 0) {
					if (extended.gasDilatation()[c] != 0) throw new AssertionError("Gas compression entered conservative liquid velocity support");
					if (!extended.volumeRate()[f].equals(submittedSheetRates[f])) throw new AssertionError("Gas extension changed a submitted liquid trace");
				}
			}
		}
		close(lowerMoment, 0.0374, 1e-10, "Lower gas first moment does not match interface displacement");
		close(upperMoment, 0.0374, 1e-10, "Upper gas first moment does not match interface displacement");
		close(lowerDisplacement, 0.1, 1e-10, "Lower gas displacement does not close");
		close(upperDisplacement, -0.1, 1e-10, "Upper gas displacement does not close");
		var velocity = LiquidControlVolumeVelocity.reconstruct(mesh, extended.volumeRate(), extended.cellVelocity());
		requireSharedVelocityTriangles(mesh, velocity);
		requireVelocityRateClosure(mesh, extended.volumeRate(), velocity);
		for (int c = 0; c < velocity.length; c++) for (var tetra : velocity[c].tetrahedra())
			close(3 * tetra.isotropicGradient(), extended.gasDilatation()[c], 1e-9, "Extended tetrahedral velocity lost its prescribed gas dilatation");
		var trajectory = new LiquidVelocityTrajectory(mesh, velocity);
		var start = new LiquidPolyhedron.Point(0.375, 0.5005, 0.125);
		int source = -1;
		for (int c = 0; c < sealed.size(); c++) if (labels[c] >= 0 && mesh.cells.get(c).centroid().x() == 0.375
				&& mesh.cells.get(c).centroid().y() > 0.5 && mesh.cells.get(c).centroid().z() == 0.125) source = c;
		if (source < 0) throw new AssertionError("Missing resolved liquid support");
		var moved = trajectory.advance(source, start, 0.05, 256);
		if (labels[moved.cell()] >= 0 || extended.gasDilatation()[moved.cell()] != 0)
			throw new AssertionError("Moving liquid interface entered gas compression support");
		var coarse = LiquidPhasePartition.build(root, List.of(sheet), 16);
		var unresolved = new LiquidControlVolumeMesh(coarse.stream().map(LiquidPhasePartition.Region::cell).toList(), 1, 1000,
				(c, surface) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		double[] coarseTraces = new double[unresolved.faces.size()], coarseVelocity = new double[3 * coarse.size()]; coarseVelocity[1] = 0.1;
		for (int f = 0; f < coarseTraces.length; f++) if (unresolved.faces.get(f).second() >= 0)
			coarseTraces[f] = 0.1 * unresolved.faces.get(f).surface().areaVector().y() / unresolved.faces.get(f).surface().area();
		try {
			LiquidInterfaceVelocityExtension.extend(unresolved, coarse.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray(), faceRates(unresolved, coarseTraces), coarseVelocity, 1e-11, 512);
			throw new AssertionError("Unresolved gas compression was allowed into liquid transport support");
		} catch (IllegalStateException expected) { }
	}

	private static void finiteGasPressureSharesLiquidProjection() {
		var geometry = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>(); var nodes = new ArrayList<Integer>();
		for (var surface : geometry.surfaces()) {
			int node = surface.areaVector().x() < 0 ? 0 : surface.areaVector().x() > 0 ? 1 : -1;
			faces.add(new LiquidControlVolumeProjection.Face(0, node < 0 ? -2 : -1, surface, node == 0 ? 120_000 : node == 1 ? 100_000 : 0));
			nodes.add(node);
		}
		double dt = 0.01, compliance = 1e-6;
		var gas = List.of(new LiquidControlVolumeProjection.GasNode(120_000, compliance), new LiquidControlVolumeProjection.GasNode(100_000, compliance));
		var projection = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces,
				gas, nodes.stream().mapToInt(Integer::intValue).toArray());
		var result = projection.project(new double[3], new LiquidPolyhedron.Point(0, 0, 0), dt, 1e-11, 256);
		requireExactContinuity(1, faces, result);
		double velocity = dt * 20_000 / (1000 + dt * dt * 2 / compliance);
		close(result.pressure()[0], 110_000, 1e-7);
		close(result.gasPressure()[0], 120_000 - dt * velocity / compliance, 1e-7);
		close(result.gasPressure()[1], 100_000 + dt * velocity / compliance, 1e-7);
		close(result.gasVolumeChange()[0], dt * velocity, 1e-12); close(result.gasVolumeChange()[1], -dt * velocity, 1e-12);
		for (int f = 0; f < faces.size(); f++) close(result.normalVelocity()[f], velocity * faces.get(f).surface().areaVector().x(), 1e-10);
		close(-dt * result.pressureGradient()[0].x() / 1000, velocity, 1e-10);
	}

	private static void refinedViscosityPreservesRigidMotionAndMomentum() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		var refined = leaves.removeFirst(); leaves.addAll(LiquidGeometryRefinement.children(refined));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000, (cell, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		double[] viscosity = new double[mesh.cells.size()]; java.util.Arrays.fill(viscosity, 20);
		var operator = new LiquidControlVolumeViscosity(mesh.cells, mesh.faces, viscosity, FluidSolverConfig.WallCondition.FREE_SLIP);
		for (int mode = 0; mode < 3; mode++) {
			double[] incoming = new double[3 * mesh.cells.size()], faces = new double[3 * mesh.faces.size()];
			for (int c = 0; c < mesh.cells.size(); c++) velocity(incoming, c, mesh.cells.get(c).centroid(), mode);
			for (int f = 0; f < mesh.faces.size(); f++) velocity(faces, f, mesh.faces.get(f).surface().centroid(), mode);
			var result = operator.advance(incoming, faces, 0.05, 1e-9, 1024);
			double before = 0, after = 0; double[] momentum = new double[3];
			for (int c = 0; c < mesh.cells.size(); c++) {
				double mass = 1000 * mesh.cells.get(c).geometry().moments().volume();
				for (int a = 0; a < 3; a++) {
					int id = 3 * c + a; double next = result.cellVelocity()[id];
					before += 0.5 * mass * incoming[id] * incoming[id]; after += 0.5 * mass * next * next;
					momentum[a] += mass * (next - incoming[id]);
					if (mode < 2) close(next, incoming[id], 1e-11);
				}
			}
			for (double change : momentum) close(change, 0, 1e-8);
			close(before - after, result.dissipatedJoules() + result.temporalDissipationJoules(), 1e-8);
			if (mode < 2) close(result.dissipatedJoules(), 0, 1e-20);
			else if (!(result.dissipatedJoules() > 0 && after < before)) throw new AssertionError("Liquid viscous shear failed to dissipate");
		}
	}

	private static void velocity(double[] target, int index, LiquidPolyhedron.Point p, int mode) {
		target[3 * index] = mode == 0 ? 3 : mode == 1 ? 3 - 2 * p.y() : p.y();
		target[3 * index + 1] = mode == 1 ? -2 + 2 * p.x() : mode == 0 ? -2 : 0;
		target[3 * index + 2] = mode < 2 ? 1 : 0;
	}

	private static void sharedVelocityReconstructionPreservesTranslation() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0,
				LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(1, 0.7, 0.3, 1.21));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		leaves.addAll(LiquidGeometryRefinement.children(leaves.removeFirst()));
		for (double scale : new double[] {1, 0.125, 1.0 / 1024}) {
			var mesh = new LiquidControlVolumeMesh(leaves, scale, 1000,
					(cell, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			var translation = new LiquidPolyhedron.Point(3, -2, 1); double[] traces = new double[mesh.faces.size()];
			for (int f = 0; f < traces.length; f++) traces[f] = translation.dot(mesh.faces.get(f).surface().areaVector()) / mesh.faces.get(f).surface().area();
			for (int c = 0; c < mesh.cells.size(); c++) {
				var field = LiquidControlVolumeVelocity.reconstruct(mesh, c, faceRates(mesh, traces), translation);
				for (var tetra : field.tetrahedra()) {
					if (!tetra.vertices().get(0).equals(mesh.cells.get(c).centroid())
							|| !tetra.vertices().get(1).equals(mesh.faces.get(tetra.boundaryFace()).surface().centroid()))
						throw new AssertionError("Velocity reconstruction changed submitted control-volume geometry");
					for (var vertex : tetra.vertices().subList(2, 4)) {
						boolean present = false; for (int f : mesh.cellFaces[c]) present |= mesh.polygons.get(f).contains(vertex);
						if (!present) throw new AssertionError("Velocity reconstruction changed a submitted surface vertex");
					}
				}
				for (var tetra : field.tetrahedra()) for (int a = 0; a < 3; a++) close(tetra.velocity().coordinate(a), translation.coordinate(a), 1e-10);
				close(field.squaredVelocityVariation(), 0, 1e-20);
			}
		}
	}

	private static void vanishingContactAreaRemovesInteractionContinuously() {
		double previous = Double.POSITIVE_INFINITY;
		for (double overlap : new double[] {0.125, 0.03125, 0.0078125, 0.001953125, 0}) {
			var leaves = List.of(new LiquidGeometryRefinement.Cell(1, 0, 0, 0,
					LiquidPolyhedron.box(0.25, 0, 0.125, 0.5, 0.25, 0.375)),
					new LiquidGeometryRefinement.Cell(1, 1, 0, 0,
							LiquidPolyhedron.box(0.5, 0.25 - overlap, 0.125, 0.75, 0.5 - overlap, 0.375)));
			var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000,
					(cell, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			double area = 0; for (var face : mesh.faces) if (face.second() >= 0) area += face.surface().area();
			close(area, 0.25 * overlap, 1e-14);
			double[] incoming = {1, 0, 0, -1, 0, 0};
			var projected = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(incoming,
					new LiquidPolyhedron.Point(0, 0, 0), 0.025, 1e-11, 1024);
			double change = Math.abs(projected.cellVelocity()[0] - incoming[0]);
			if (!(change < previous)) throw new AssertionError("Liquid contact impulse failed to vanish with its shared area");
			previous = change;
			if (overlap == 0) close(change, 0, 1e-12);
			else if (!(change > 0)) throw new AssertionError("Resolved liquid contact transmitted no pressure impulse");
			close(projected.cellVelocity()[0] + projected.cellVelocity()[3], 0, 1e-10);
		}
	}

	private static void movingSolidPressureClosesWorkAndImpulse() {
		var geometry = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1); var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var surface : geometry.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0,
				surface.areaVector().x() < 0 ? -1 : -2, surface, surface.areaVector().x() < 0 ? 100_000 : surface.areaVector().x() > 0 ? 1 : 0));
		var result = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces)
				.project(new double[3], new LiquidPolyhedron.Point(0, 0, 0), 0.025, 1e-11, 128);
		close(result.cellVelocity()[0], 1, 1e-12); close(result.cellVelocity()[1], 0, 1e-12); close(result.cellVelocity()[2], 0, 1e-12);
		close(result.pressure()[0], 80_000, 1e-7); close(result.boundaryImpulse().x(), 1000, 1e-7);
		close(result.boundaryImpulse().y(), 0, 1e-10); close(result.boundaryImpulse().z(), 0, 1e-10);
		close(result.boundaryWorkJoules(), 1000, 1e-7); close(result.pressureDissipationJoules(), 500, 1e-7);
	}

	private static void sharedVelocityTrajectoriesCrossRefinementContinuously() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		leaves.addAll(LiquidGeometryRefinement.children(leaves.removeFirst()));
		var velocity = new LiquidPolyhedron.Point(1, -0.1, 0.2);
		for (double scale : new double[] {1, 0.125, 1.0 / 1024}) {
			var mesh = new LiquidControlVolumeMesh(leaves, scale, 1000,
					(cell, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			double[] traces = new double[mesh.faces.size()], cellVelocity = new double[3 * mesh.cells.size()];
			for (int f = 0; f < traces.length; f++) traces[f] = velocity.dot(mesh.faces.get(f).surface().areaVector()) / mesh.faces.get(f).surface().area();
			for (int c = 0; c < mesh.cells.size(); c++) for (int a = 0; a < 3; a++) cellVelocity[3 * c + a] = velocity.coordinate(a);
			var fields = LiquidControlVolumeVelocity.reconstruct(mesh, faceRates(mesh, traces), cellVelocity); requireSharedVelocityTriangles(mesh, fields);
			var trajectory = new LiquidVelocityTrajectory(mesh, fields);
			int source = 0; for (int c = 0; c < leaves.size(); c++) if (leaves.get(c).level() == 2 && leaves.get(c).x() == 1
					&& leaves.get(c).y() == 1 && leaves.get(c).z() == 1) source = c;
			var initial = new LiquidPolyhedron.Point(0.35 * scale, 0.3 * scale, 0.4 * scale);
			for (double duration : new double[] {0, 1e-6, 0.01, 0.3}) {
				double dt = duration * scale; var result = trajectory.advance(source, initial, dt, 512);
				for (int a = 0; a < 3; a++) close(result.position().coordinate(a), initial.coordinate(a) + dt * velocity.coordinate(a), 1e-11 * scale);
				if (duration == 0.3 && !(result.faceEvents() > 0 && result.cell() != source)) throw new AssertionError("Liquid trajectory failed to cross its refined support");
			}
		}
	}

	private static void pressureAndViscosityShareMomentumAndEnergy() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0,
				LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(1, 0.7, 0.3, 1.21));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		leaves.addAll(LiquidGeometryRefinement.children(leaves.removeFirst()));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000,
				(cell, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		double[] incoming = new double[3 * mesh.cells.size()], viscosity = new double[mesh.cells.size()];
		java.util.Arrays.fill(viscosity, 20);
		for (int c = 0; c < mesh.cells.size(); c++) for (int a = 0; a < 3; a++)
			incoming[3 * c + a] = Math.sin(0.7 + 3.1 * c + 1.3 * a);
		var viscous = new LiquidControlVolumeViscosity(mesh.cells, mesh.faces, viscosity,
				FluidSolverConfig.WallCondition.FREE_SLIP).advance(incoming, new double[3 * mesh.faces.size()], 0.025, 1e-9, 2048);
		var projected = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(viscous.cellVelocity(),
				new LiquidPolyhedron.Point(0, 0, 0), 0.025, 1e-11, 2048);
		requireExactContinuity(mesh.cells.size(), mesh.faces, projected);
		double before = 0, after = 0; double[] momentum = new double[3];
		for (int c = 0; c < mesh.cells.size(); c++) for (int a = 0; a < 3; a++) {
			int id = 3 * c + a; double mass = mesh.cells.get(c).density() * mesh.cells.get(c).geometry().moments().volume();
			before += 0.5 * mass * incoming[id] * incoming[id];
			after += 0.5 * mass * projected.cellVelocity()[id] * projected.cellVelocity()[id];
			momentum[a] += mass * (projected.cellVelocity()[id] - incoming[id]);
		}
		for (double change : momentum) close(change, 0, 1e-8);
		close(before - after + projected.boundaryWorkJoules() + viscous.boundaryWorkJoules(),
				viscous.dissipatedJoules() + viscous.temporalDissipationJoules() + projected.pressureDissipationJoules(), 1e-8);
		if (!(projected.pressureDissipationJoules() > 0)) throw new AssertionError("Unresolved pressure modes have no dissipative energy");
		for (int c = 0; c < mesh.cells.size(); c++) {
			var target = new LiquidPolyhedron.Point(projected.cellVelocity()[3 * c], projected.cellVelocity()[3 * c + 1], projected.cellVelocity()[3 * c + 2]);
			var field = LiquidControlVolumeVelocity.reconstruct(mesh, c, projected.volumeRate(), target);
			double volume = 0; double[] mean = new double[3];
			for (var tetra : field.tetrahedra()) {
				volume += tetra.volume();
				for (int a = 0; a < 3; a++) mean[a] += tetra.volume() * tetra.velocity().coordinate(a);
				var triangle = LiquidPolyhedron.surface(tetra.vertices().subList(1, 4));
				var face = mesh.faces.get(tetra.boundaryFace());
				close(tetra.sample(triangle.centroid()).dot(face.surface().areaVector()) / face.surface().area(),
						projected.normalVelocity()[tetra.boundaryFace()], 1e-9);
			}
			close(volume, mesh.cells.get(c).geometry().moments().volume(), 1e-12);
			for (int a = 0; a < 3; a++) close(mean[a] / volume, target.coordinate(a), 1e-9);
			close(field.divergenceResidual(), 0, 1e-9);
		}
	}

	private static void refinedHydrostaticColumn() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		var refined = leaves.removeFirst();
		var children = new ArrayList<>(LiquidGeometryRefinement.children(refined));
		var smallest = children.removeFirst(); leaves.addAll(children); leaves.addAll(LiquidGeometryRefinement.children(smallest));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000, (cell, surface) ->
				new LiquidControlVolumeMesh.Boundary(!(surface.areaVector().y() > 0 && surface.centroid().y() == 1), 0));
		var result = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[3 * mesh.cells.size()], GRAVITY, 0.025, 1e-11, 512);
		for (int c = 0; c < mesh.cells.size(); c++) close(result.pressure()[c], 1000 * 9.81 * (1 - mesh.cells.get(c).centroid().y()), 1e-7);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 1e-10);
	}

	private static void refinedAffinePressure() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0,
				LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(1, 0.7, 0.3, 1.21));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		var refined = leaves.removeFirst(); leaves.addAll(LiquidGeometryRefinement.children(refined));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000, (cell, surface) ->
				new LiquidControlVolumeMesh.Boundary(false, 1000 * 9.81 * (1 - surface.centroid().y())));
		var result = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[3 * mesh.cells.size()], GRAVITY, 0.025, 1e-11, 1024);
		for (int c = 0; c < mesh.cells.size(); c++) close(result.pressure()[c], 1000 * 9.81 * (1 - mesh.cells.get(c).centroid().y()), 1e-7);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 1e-10);
	}

	private static void freeLiquidReceivesGravity(double size) {
		var geometry = LiquidPolyhedron.box(0.125, 0.25, 0.375, 0.125 + size, 0.25 + size, 0.375 + size);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var face : geometry.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0, -1, face, 100_000));
		var projection = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces);
		double dt = 0.025;
		var result = projection.project(new double[3], GRAVITY, dt, 1e-12, 64);
		close(result.pressure()[0], 100_000, 1e-9);
		for (int f = 0; f < faces.size(); f++) {
			var surface = faces.get(f).surface();
			close(result.normalVelocity()[f], dt * surface.areaVector().dot(GRAVITY) / surface.area(), 1e-10);
		}
	}

	private static void thinHydrostaticColumn(double height) {
		var geometry = LiquidPolyhedron.box(0, 0, 0, 1, height, 1);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var surface : geometry.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0,
				surface.areaVector().y() > 0 ? -1 : -2, surface, 0));
		var projection = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces);
		var result = projection.project(new double[3], GRAVITY, 0.025, 1e-12, 64);
		close(result.pressure()[0], 1000 * 9.81 * height / 2, 1e-10);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 1e-12);
	}

	private static void smallHydrostaticCube(double size) {
		var geometry = LiquidPolyhedron.box(0, 0, 0, size, size, size);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var surface : geometry.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0,
				surface.areaVector().y() > 0 ? -1 : -2, surface, 0));
		var result = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces)
				.project(new double[3], GRAVITY, 0.025, 1e-10, 128);
		close(result.pressure()[0] / size, 1000 * 9.81 / 2, 1e-6);
		for (double velocity : result.cellVelocity()) close(velocity, 0, 0);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 0);
	}

	private static void densityJumpUsesBothLiquidDistances() {
		var lower = LiquidPolyhedron.box(0, 0, 0, 1, 0.25, 1);
		var upper = LiquidPolyhedron.box(0, 0.25, 0, 1, 0.375, 1);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var surface : lower.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0,
				surface.areaVector().y() > 0 ? 1 : -2, surface, 0));
		for (var surface : upper.surfaces()) if (!(surface.areaVector().y() < 0))
			faces.add(new LiquidControlVolumeProjection.Face(1, surface.areaVector().y() > 0 ? -1 : -2, surface, 0));
		var cells = List.of(new LiquidControlVolumeProjection.Cell(lower, 2000), new LiquidControlVolumeProjection.Cell(upper, 1000));
		var result = new LiquidControlVolumeProjection(cells, faces).project(new double[3 * cells.size()], GRAVITY, 0.025, 1e-12, 64);
		close(result.pressure()[0], 9.81 * (2000 * 0.125 + 1000 * 0.125), 1e-10);
		close(result.pressure()[1], 9.81 * 1000 * 0.0625, 1e-10);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 1e-12);
	}

	private static void close(double actual, double expected, double tolerance) {
		if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance)
			throw new AssertionError("Liquid control-volume value: actual=" + actual + " expected=" + expected);
	}
	private static void requireExactContinuity(int count, List<LiquidControlVolumeProjection.Face> faces, LiquidControlVolumeProjection.Result result) {
		var rates = result.volumeRate(); var divergence = new LiquidExactPolygon.Fraction[count];
		java.util.Arrays.fill(divergence, LiquidExactPolygon.Fraction.ZERO);
		if (rates.length != faces.size()) throw new AssertionError("Conservative pressure returned incomplete face rates");
		for (int f = 0; f < rates.length; f++) {
			var face = faces.get(f); divergence[face.first()] = divergence[face.first()].add(rates[f]);
			if (face.second() >= 0) divergence[face.second()] = divergence[face.second()].subtract(rates[f]);
		}
		for (var rate : divergence) if (rate.signum() != 0) throw new AssertionError("Pressure face rates violate exact control-volume continuity");
	}
	private static void close(double actual, double expected, double tolerance, String message) {
		if (!(Math.abs(actual - expected) <= tolerance)) throw new AssertionError(message + ": actual=" + actual + " expected=" + expected);
	}
	public static void main(String[] args) {
		if (args.length == 1 && args[0].equals("surface-energy")) {
			internalInterfacePressureJumpPreservesEquilibriumAndWork();
			polygonSurfaceEnergyPreservesGeometricInvariants();
			obliqueSurfaceEnergyPreservesVirtualWork();
			variableSurfaceEnergyRetainsTangentialForce();
			System.out.println("Geometric surface energy and internal pressure jump validation passed");
		} else validate();
	}
}
