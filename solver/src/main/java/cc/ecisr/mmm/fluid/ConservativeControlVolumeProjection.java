package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.Arrays;
import java.util.List;

/** Variational pressure solve in conservative face-rate coordinates. */
final class ConservativeControlVolumeProjection {
	private record Evaluation(double[] gradient, double[] error, double[][] dual) { }
	private final List<LiquidControlVolumeProjection.Cell> cells;
	private final List<LiquidControlVolumeProjection.Face> faces;
	private final List<LiquidControlVolumeProjection.GasNode> nodes;
	private final int[] faceNode;
	private final double[] reference, vertexVolume;
	private final ControlVolumeFluxMetric[] metrics;
	private final ConservativeFluxCoordinates coordinates;
	private final double[] zeroSource;
	private final Fraction[] exactZeroSource;

	ConservativeControlVolumeProjection(List<LiquidControlVolumeProjection.Cell> cells, List<LiquidControlVolumeProjection.Face> faces,
			List<LiquidControlVolumeProjection.GasNode> nodes, int[] faceNode) {
		if (faceNode.length != faces.size()) throw new IllegalArgumentException("Invalid gas face cardinality");
		this.cells = List.copyOf(cells); this.faces = List.copyOf(faces); this.nodes = List.copyOf(nodes); this.faceNode = faceNode.clone();
		int n = cells.size(), vertices = n + nodes.size() + 1; int[] first = new int[faces.size()], second = first.clone(), root = new int[n];
		for (int c = 0; c < n; c++) root[c] = c;
		Fraction[] fixed = new Fraction[faces.size()]; boolean[] constrained = new boolean[vertices]; Arrays.fill(constrained, 0, n, true);
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f); int node = faceNode[f];
			if (face.first() >= n || face.second() >= n || node < -1 || node >= nodes.size() || node >= 0 && face.second() != -1)
				throw new IllegalArgumentException("Invalid conservative pressure face support");
			first[f] = face.first(); second[f] = face.second() >= 0 ? face.second() : node >= 0 ? n + node : vertices - 1;
			if (face.second() >= 0) root[find(root, face.second())] = find(root, face.first());
			if (face.second() == -2) fixed[f] = Fraction.of(face.surface().area() * face.prescribed());
		}
		reference = new double[n]; boolean[] prescribed = new boolean[n];
		for (var face : faces) if (face.second() == -1) {
			int component = find(root, face.first()); if (!prescribed[component]) reference[component] = face.prescribed(); prescribed[component] = true;
		}
		for (int c = 0; c < n; c++) reference[c] = reference[find(root, c)];
		coordinates = new ConservativeFluxCoordinates(vertices, first, second, constrained, fixed);
		zeroSource = new double[vertices]; exactZeroSource = new Fraction[vertices]; Arrays.fill(exactZeroSource, Fraction.ZERO);
		vertexVolume = new double[vertices]; Arrays.fill(vertexVolume, Double.POSITIVE_INFINITY);
		var gradients = ControlVolumeGradient.build(cells, faces); metrics = new ControlVolumeFluxMetric[n];
		for (int c = 0; c < n; c++) { metrics[c] = new ControlVolumeFluxMetric(gradients[c], cells.get(c).density()); vertexVolume[c] = gradients[c].volume; }
	}

	LiquidControlVolumeProjection.Result project(double[] incoming, LiquidPolyhedron.Point gravity, double dt, double tolerance, int maximumIterations) {
		return project(incoming, gravity, dt, tolerance, maximumIterations, null);
	}
	LiquidControlVolumeProjection.Result project(double[] incoming, LiquidPolyhedron.Point gravity, double dt, double tolerance, int maximumIterations, LiquidPressureFaceEnergy surface) {
		if (incoming.length != 3 * cells.size() || !(dt > 0) || !Double.isFinite(dt) || !(tolerance > 0) || !Double.isFinite(tolerance) || maximumIterations <= 0)
			throw new IllegalArgumentException("Invalid conservative pressure request");
		if (surface != null && surface.hasSignedMetric()) {
			double curvature = surface.relativeCurvatureBound(metrics);
			if (!Double.isFinite(curvature)) throw new IllegalStateException("Wet-wall curvature has no finite numerical bound");
			if (Fraction.of(dt).multiply(Fraction.of(dt)).multiply(Fraction.of(curvature)).compareTo(Fraction.of(1)) >= 0) {
				double interval = Math.nextDown(1 / Math.nextUp(Math.sqrt(curvature)));
				throw new LiquidCharacteristicTimeResolutionRequired("Wet-wall curvature requires a shorter positive-inertia time step: maximumSeconds="
						+ interval + ", relativeCurvatureUpper=" + curvature, interval);
			}
		}
		double[] predicted = incoming.clone();
		for (int c = 0; c < cells.size(); c++) for (int a = 0; a < 3; a++) {
			predicted[3 * c + a] += dt * gravity.coordinate(a);
			if (!Double.isFinite(predicted[3 * c + a])) throw new IllegalArgumentException("Non-finite pressure predictor");
		}
		double[][] predictor = new double[cells.size()][], predictorError = new double[cells.size()][];
		for (int c = 0; c < cells.size(); c++) {
			var g = metrics[c].geometry; predictor[c] = new double[g.indices.length]; predictorError[c] = predictor[c].clone();
			for (int i = 0; i < g.indices.length; i++) {
				var rate = faceRate(faces.get(g.indices[i]), predicted, c); if (g.sign[i] < 0) rate = rate.negate();
				predictor[c][i] = rate.rounded(); predictorError[c][i] = Math.ulp(predictor[c][i]);
			}
		}
		double[] storage = new double[nodes.size()], faceWeight = new double[faces.size()];
		for (int node = 0; node < nodes.size(); node++) {
			storage[node] = dt * dt / nodes.get(node).volumeCompliance();
			if (!(storage[node] > 0) || !Double.isFinite(storage[node])) throw new IllegalStateException("Gas compliance exceeds numerical range");
		}
		for (var metric : metrics) for (int i = 0; i < metric.geometry.indices.length; i++) faceWeight[metric.geometry.indices[i]] += metric.matrix[i][i];
		for (int f = 0; f < faceWeight.length; f++) if (faceNode[f] >= 0) faceWeight[f] += storage[faceNode[f]];
		if (surface != null) {
			if (surface.gradient.length != faces.size()) throw new IllegalArgumentException("Surface energy differs from the pressure faces");
			var extra = surface.diagonal(dt); for (int f = 0; f < faceWeight.length; f++) faceWeight[f] += extra[f];
		}
		var coordinates = this.coordinates.weighted(faceWeight);
		double[] diagonal = coordinates.cycleWeights(faceWeight), support = coordinates.cycleMinimum(vertexVolume), variable = new double[coordinates.size()];
		Fraction[] initial = new Fraction[variable.length]; Arrays.fill(initial, Fraction.ZERO); var offset = new ConservativeFluxOffset(coordinates, initial, exactZeroSource);
		var observation = offset.observe(variable);
		var evaluation = evaluate(observation.values(), observation.error(), predictor, predictorError, storage, dt, surface, dt);
		double[] residual = negate(coordinates.transpose(evaluation.gradient));
		var primaryReference = new double[predicted.length];
		// Choose a feasible kinetic predictor when the zero-rate state has a resolved variational force.
		// A numerically certified stationary zero state retains its exact rates.
		if (!converged(residual, coordinates.transposeError(evaluation.gradient, evaluation.error), diagonal, support, 0)) {
			System.arraycopy(predicted, 0, primaryReference, 0, predicted.length);
			for (int i = 0; i < variable.length; i++) { var face = faces.get(coordinates.face(i)); initial[i] = faceRate(face, predicted, face.first()); }
			offset = new ConservativeFluxOffset(coordinates, initial, exactZeroSource);
			observation = offset.observe(variable);
			evaluation = evaluate(observation.values(), observation.error(), predictor, predictorError, storage, dt, surface, dt);
			residual = negate(coordinates.transpose(evaluation.gradient));
		}
		var directions = new ConservativeConjugateDirections(diagonal);
		var residualError = coordinates.transposeError(evaluation.gradient, evaluation.error);
		int iterations = 0;
		while (!converged(residual, residualError, diagonal, support, tolerance)) {
			if (iterations >= maximumIterations) {
				var error = coordinates.transposeError(evaluation.gradient, evaluation.error); double unresolved = 0;
				for (int i = 0; i < residual.length; i++) unresolved = Math.max(unresolved, Math.max(0, Math.abs(residual[i]) - error[i]) / diagonal[i] / support[i]);
				throw new IllegalStateException("Conservative pressure solve did not converge: iterations=" + iterations + " coordinates=" + variable.length + " unresolvedRateResidual=" + unresolved + " tolerance=" + tolerance);
			}
			iterations++; var direction = directions.direction(residual, residualError);
			var product = coordinates.transpose(evaluate(coordinates.lift(direction, zeroSource, false), null, null, null, storage, 0, surface, dt).gradient);
			double denominator = dot(direction, product); if (!(denominator > 0) || !Double.isFinite(denominator)) throw new IllegalStateException("Invalid conservative pressure search direction");
			directions.remember(direction, product);
			double alpha = dot(residual, direction) / denominator; for (int i = 0; i < variable.length; i++) variable[i] += alpha * direction[i];
			observation = offset.observe(variable);
			evaluation = evaluate(observation.values(), observation.error(), predictor, predictorError, storage, dt, surface, dt);
			residual = negate(coordinates.transpose(evaluation.gradient));
			residualError = coordinates.transposeError(evaluation.gradient, evaluation.error);
		}
		return observeRates(coordinates, predicted, primaryReference, predictor, predictorError, storage, dt, iterations, tolerance,
				surface, diagonal, support, offset.exact(variable));
	}

	/** Reuses the fixed inertia, surface energy and gas compliance for the represented rates. */
	private LiquidControlVolumeProjection.Result observeRates(ConservativeFluxCoordinates coordinates,
			double[] predicted, double[] primaryReference, double[][] predictor, double[][] predictorError, double[] storage,
			double dt, int iterations, double tolerance, LiquidPressureFaceEnergy surface, double[] diagonal, double[] support, Fraction[] rates) {
		if (rates.length != faces.size()) throw new IllegalArgumentException("Represented rates differ from the pressure faces");
		var independent = new Fraction[coordinates.size()];
		for (int i = 0; i < independent.length; i++) independent[i] = rates[coordinates.face(i)];
		if (!Arrays.equals(rates, coordinates.liftExact(independent, exactZeroSource)))
			throw new IllegalArgumentException("Represented pressure rates changed exact continuity or prescribed solid motion");
		double[] flow = new double[rates.length], conversionError = flow.clone();
		for (int f = 0; f < flow.length; f++) { flow[f] = rates[f].toDouble(); conversionError[f] = flow[f] == 0 ? 0 : Math.ulp(flow[f]); }
		var evaluation = evaluate(flow, conversionError, predictor, predictorError, storage, dt, surface, dt);
		if (!converged(negate(coordinates.transpose(evaluation.gradient)), coordinates.transposeError(evaluation.gradient, evaluation.error), diagonal, support, tolerance))
			throw new LiquidCoordinateResolutionRequired("Represented conservative rates exceed pressure solve accuracy");
		var stationarityError = coordinates.transposeError(evaluation.gradient, evaluation.error); var momentumBound = new double[3];
		for (int i = 0; i < diagonal.length; i++) for (int a = 0; a < 3; a++)
			momentumBound[a] += Math.abs(faces.get(coordinates.face(i)).areaVector().get(a).rounded()) * (stationarityError[i] + tolerance * diagonal[i] * support[i]);
		return result(coordinates, predicted, primaryReference, rates, flow, conversionError, evaluation, dt, iterations, tolerance,
				surface == null ? new double[faces.size()] : surface.pressure(flow, dt), new LiquidPolyhedron.Point(momentumBound[0], momentumBound[1], momentumBound[2]),
				next -> observeRates(coordinates, predicted, primaryReference, predictor, predictorError, storage, dt, iterations, tolerance, surface, diagonal, support, next));
	}

	private static Fraction faceRate(LiquidControlVolumeProjection.Face face, double[] velocity, int cell) {
		var rate = Fraction.ZERO;
		for (int a = 0; a < 3; a++) rate = rate.add(face.areaVector().get(a).multiply(Fraction.of(velocity[3 * cell + a]))); return rate;
	}

	private Evaluation evaluate(double[] flow, double[] flowError, double[][] predictor, double[][] predictorError, double[] storage, double dt, LiquidPressureFaceEnergy surface, double surfaceDt) {
		double[] gradient = new double[faces.size()], error = gradient.clone(); double[][] dual = new double[cells.size()][];
		for (int c = 0; c < cells.size(); c++) {
			var metric = metrics[c]; var g = metric.geometry; double[] local = new double[g.indices.length], localError = local.clone();
			for (int i = 0; i < local.length; i++) {
				int f = g.indices[i]; local[i] = g.sign[i] * flow[f] - (predictor == null ? 0 : predictor[c][i]);
				if (flowError != null) localError[i] = flowError[f] + (predictorError == null ? 0 : predictorError[c][i]) + Math.ulp(local[i]);
			}
			dual[c] = metric.multiply(local); var localBound = flowError == null ? localError : metric.evaluationError(local, dual[c], localError);
			for (int i = 0; i < local.length; i++) {
				int f = g.indices[i]; gradient[f] += g.sign[i] * dual[c][i]; if (flowError != null) error[f] += localBound[i] + Math.ulp(gradient[f]);
			}
		}
		double[] gasRate = new double[nodes.size()], gasError = gasRate.clone();
		for (int f = 0; f < faces.size(); f++) if (faceNode[f] >= 0) {
			int node = faceNode[f]; gasRate[node] += flow[f]; if (flowError != null) gasError[node] += flowError[f] + Math.ulp(gasRate[node]);
		}
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f);
			if (faceNode[f] >= 0) {
				int node = faceNode[f]; double term = storage[node] * gasRate[node]; gradient[f] += term;
				if (flowError != null) error[f] += storage[node] * gasError[node] + Math.ulp(term) + Math.ulp(gradient[f]);
			}
			if (dt > 0 && face.second() == -1) {
				double term = dt * (face.prescribed() - reference[face.first()]); gradient[f] += term;
				if (flowError != null) error[f] += Math.ulp(term) + Math.ulp(gradient[f]);
			}
			if (dt > 0 && face.second() >= 0) {
				double term = dt * face.prescribed(); gradient[f] += term;
				if (flowError != null) error[f] += Math.ulp(term) + Math.ulp(gradient[f]);
			}
		}
		if (surface != null) surface.add(flow, flowError, surfaceDt, dt > 0, gradient, error);
		return new Evaluation(gradient, error, dual);
	}

	private LiquidControlVolumeProjection.Result result(ConservativeFluxCoordinates coordinates, double[] predicted, double[] primaryReference, Fraction[] rates, double[] flow, double[] conversionError,
			Evaluation evaluation, double dt, int iterations, double tolerance, double[] surfacePressure, LiquidPolyhedron.Point momentumBound,
			LiquidControlVolumeProjection.RateRepresentation representation) {
		double[] pressure = new double[cells.size()], primary = primaryReference.clone(), normal = new double[faces.size()];
		double[] gasPressure = new double[nodes.size()], gasChange = gasPressure.clone(), pressureChange = gasPressure.clone(), boundaryPressure = normal.clone();
		Fraction[] gasRate = new Fraction[nodes.size()]; Arrays.fill(gasRate, Fraction.ZERO);
		for (int f = 0; f < rates.length; f++) if (faceNode[f] >= 0) gasRate[faceNode[f]] = gasRate[faceNode[f]].add(rates[f]);
		for (int node = 0; node < nodes.size(); node++) {
			double rate = gasRate[node].toDouble(); gasChange[node] = -dt * rate;
			pressureChange[node] = dt * rate / nodes.get(node).volumeCompliance(); gasPressure[node] = nodes.get(node).pressure() + pressureChange[node];
		}
		var potential = coordinates.potential(evaluation.gradient); var gradients = new LiquidPolyhedron.Point[cells.size()]; double dissipation = 0;
		for (int c = 0; c < cells.size(); c++) {
			var metric = metrics[c]; var g = metric.geometry; pressure[c] = potential[c] / dt + reference[c]; double[] dualGradient = new double[3];
			for (int i = 0; i < g.indices.length; i++) {
				int f = g.indices[i];
				double referenceRate = faceRate(faces.get(f), primaryReference, c).rounded();
				double correction = g.sign[i] * (flow[f] - referenceRate);
				for (int a = 0; a < 3; a++) {
					primary[3 * c + a] += g.displacement[i].coordinate(a) * correction / g.volume;
					dualGradient[a] += g.basis[i].coordinate(a) * evaluation.dual[c][i];
				}
				if (faces.get(f).second() == -2) boundaryPressure[f] = potential[c] / dt - evaluation.dual[c][i] / dt;
			}
			gradients[c] = new LiquidPolyhedron.Point(-dualGradient[0] / dt, -dualGradient[1] / dt, -dualGradient[2] / dt);
			for (int a = 0; a < 3; a++) { double change = primary[3 * c + a] - predicted[3 * c + a]; dissipation += 0.5 * cells.get(c).density() * g.volume * change * change; }
			for (int i = 0; i < g.indices.length; i++) {
				double residual = evaluation.dual[c][i]; for (int a = 0; a < 3; a++) residual -= g.displacement[i].coordinate(a) * dualGradient[a];
				dissipation += metric.mobility * g.weight[i] * residual * residual;
			}
		}
		double boundaryWork = 0, internalWork = 0; double[] impulse = new double[3], internalImpulse = impulse.clone(), closure = new double[cells.size()], closureBound = closure.clone();
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f); normal[f] = flow[f] / face.surface().area(); double converted = face.surface().area() * normal[f];
			double bound = conversionError[f] + Math.ulp(converted) + face.surface().area() * Math.ulp(normal[f]);
			closure[face.first()] += converted; closureBound[face.first()] += bound + Math.ulp(closure[face.first()]);
			if (face.second() >= 0) {
				closure[face.second()] -= converted; closureBound[face.second()] += bound + Math.ulp(closure[face.second()]);
				internalWork -= dt * (face.prescribed() + surfacePressure[f]) * flow[f];
				for (int a = 0; a < 3; a++) internalImpulse[a] -= dt * (face.prescribed() + surfacePressure[f]) * face.surface().areaVector().coordinate(a);
			}
			else {
				if (face.second() == -1) boundaryPressure[f] = face.prescribed() - reference[face.first()] + surfacePressure[f] + (faceNode[f] >= 0 ? pressureChange[faceNode[f]] : 0);
				// Constant pressure contributes zero work and resultant on each closed liquid component.
				// Evaluate the relative traction directly so a small resolved gradient survives a large absolute pressure.
				boundaryWork -= dt * boundaryPressure[f] * flow[f];
				for (int a = 0; a < 3; a++) impulse[a] -= dt * boundaryPressure[f] * face.surface().areaVector().coordinate(a);
			}
		}
		double fluxResidual = 0, divergenceResidual = 0, divergenceBound = 0;
		for (int c = 0; c < cells.size(); c++) {
			fluxResidual = Math.max(fluxResidual, Math.abs(closure[c])); divergenceResidual = Math.max(divergenceResidual, Math.abs(closure[c]) / vertexVolume[c]);
			divergenceBound = Math.max(divergenceBound, closureBound[c] / vertexVolume[c]);
			if (!(Math.abs(closure[c]) <= closureBound[c] + tolerance * vertexVolume[c])) throw new IllegalStateException("Floating flux observation exceeds its exact continuity representation");
		}
		return new LiquidControlVolumeProjection.Result(pressure, normal, iterations, fluxResidual, gasPressure, gasChange, gradients, primary,
				dissipation, boundaryWork, new LiquidPolyhedron.Point(impulse[0], impulse[1], impulse[2]), divergenceResidual, divergenceBound, rates, pressureChange,
				internalWork, new LiquidPolyhedron.Point(internalImpulse[0], internalImpulse[1], internalImpulse[2]), momentumBound, representation);
	}
	private static boolean converged(double[] residual, double[] error, double[] diagonal, double[] volume, double tolerance) {
		for (int i = 0; i < residual.length; i++) if (!(Math.abs(residual[i]) <= error[i] + tolerance * diagonal[i] * volume[i])) return false;
		return true;
	}
	private static double[] negate(double[] value) { for (int i = 0; i < value.length; i++) value[i] = -value[i]; return value; }
	private static double dot(double[] a, double[] b) { return ConservativeConjugateDirections.dot(a, b); }
	private static int find(int[] root, int c) { while (root[c] != c) { root[c] = root[root[c]]; c = root[c]; } return c; }
}
