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
		if (incoming.length != 3 * cells.size() || !(dt > 0) || !Double.isFinite(dt) || !(tolerance > 0) || !Double.isFinite(tolerance) || maximumIterations <= 0)
			throw new IllegalArgumentException("Invalid conservative pressure request");
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
		double[] diagonal = coordinates.cycleWeights(faceWeight), support = coordinates.cycleMinimum(vertexVolume), variable = new double[coordinates.size()];
		Fraction[] initial = new Fraction[variable.length]; Arrays.fill(initial, Fraction.ZERO); var offset = new ConservativeFluxOffset(coordinates, initial, exactZeroSource);
		var evaluation = evaluate(offset.values(variable), offset.error(variable), predictor, predictorError, storage, dt);
		double[] residual = negate(coordinates.transpose(evaluation.gradient));
		var primaryReference = new double[predicted.length];
		// Choose a feasible kinetic predictor when the zero-rate state has a resolved variational force.
		// A numerically certified stationary zero state retains its exact rates.
		if (!converged(residual, coordinates.transposeError(evaluation.gradient, evaluation.error), diagonal, support, 0)) {
			System.arraycopy(predicted, 0, primaryReference, 0, predicted.length);
			for (int i = 0; i < variable.length; i++) { var face = faces.get(coordinates.face(i)); initial[i] = faceRate(face, predicted, face.first()); }
			offset = new ConservativeFluxOffset(coordinates, initial, exactZeroSource);
			evaluation = evaluate(offset.values(variable), offset.error(variable), predictor, predictorError, storage, dt);
			residual = negate(coordinates.transpose(evaluation.gradient));
		}
		double[] direction = residual.clone(); double rz = 0;
		for (int i = 0; i < residual.length; i++) {
			if (!(diagonal[i] > 0) || !Double.isFinite(diagonal[i])) throw new IllegalStateException("Invalid conservative pressure metric");
			direction[i] /= diagonal[i]; rz += residual[i] * direction[i];
		}
		int iterations = 0;
		while (!converged(residual, coordinates.transposeError(evaluation.gradient, evaluation.error), diagonal, support, tolerance)) {
			if (iterations++ >= maximumIterations || !(rz > 0) || !Double.isFinite(rz)) throw new IllegalStateException("Conservative pressure solve did not converge");
			var product = coordinates.transpose(evaluate(coordinates.lift(direction, zeroSource, false), null, null, null, storage, 0).gradient);
			double denominator = dot(direction, product); if (!(denominator > 0) || !Double.isFinite(denominator)) throw new IllegalStateException("Invalid conservative pressure search direction");
			double alpha = rz / denominator; for (int i = 0; i < variable.length; i++) variable[i] += alpha * direction[i];
			evaluation = evaluate(offset.values(variable), offset.error(variable), predictor, predictorError, storage, dt);
			residual = negate(coordinates.transpose(evaluation.gradient)); double next = 0;
			for (int i = 0; i < residual.length; i++) next += residual[i] * residual[i] / diagonal[i];
			for (int i = 0; i < direction.length; i++) direction[i] = residual[i] / diagonal[i] + next / rz * direction[i]; rz = next;
		}
		var rates = offset.exact(variable); double[] flow = new double[rates.length], conversionError = flow.clone();
		for (int f = 0; f < flow.length; f++) { flow[f] = rates[f].toDouble(); conversionError[f] = flow[f] == 0 ? 0 : Math.ulp(flow[f]); }
		evaluation = evaluate(flow, conversionError, predictor, predictorError, storage, dt);
		if (!converged(negate(coordinates.transpose(evaluation.gradient)), coordinates.transposeError(evaluation.gradient, evaluation.error), diagonal, support, tolerance))
			throw new IllegalStateException("Exact conservative rates exceed pressure solve accuracy");
		return result(predicted, primaryReference, rates, flow, conversionError, evaluation, dt, iterations, tolerance);
	}

	private static Fraction faceRate(LiquidControlVolumeProjection.Face face, double[] velocity, int cell) {
		var rate = Fraction.ZERO;
		for (int a = 0; a < 3; a++) rate = rate.add(face.areaVector().get(a).multiply(Fraction.of(velocity[3 * cell + a]))); return rate;
	}

	private Evaluation evaluate(double[] flow, double[] flowError, double[][] predictor, double[][] predictorError, double[] storage, double dt) {
		double[] gradient = new double[faces.size()], error = gradient.clone(); double[][] dual = new double[cells.size()][];
		for (int c = 0; c < cells.size(); c++) {
			var metric = metrics[c]; var g = metric.geometry; double[] local = new double[g.indices.length], localError = local.clone();
			for (int i = 0; i < local.length; i++) {
				int f = g.indices[i]; local[i] = g.sign[i] * flow[f] - (predictor == null ? 0 : predictor[c][i]);
				if (flowError != null) localError[i] = flowError[f] + (predictorError == null ? 0 : predictorError[c][i]) + Math.ulp(local[i]);
			}
			dual[c] = metric.multiply(local); var localBound = flowError == null ? localError : metric.evaluationError(local, localError);
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
		}
		return new Evaluation(gradient, error, dual);
	}

	private LiquidControlVolumeProjection.Result result(double[] predicted, double[] primaryReference, Fraction[] rates, double[] flow, double[] conversionError,
			Evaluation evaluation, double dt, int iterations, double tolerance) {
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
		double boundaryWork = 0; double[] impulse = new double[3], closure = new double[cells.size()], closureBound = closure.clone();
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f); normal[f] = flow[f] / face.surface().area(); double converted = face.surface().area() * normal[f];
			double bound = conversionError[f] + Math.ulp(converted) + face.surface().area() * Math.ulp(normal[f]);
			closure[face.first()] += converted; closureBound[face.first()] += bound + Math.ulp(closure[face.first()]);
			if (face.second() >= 0) { closure[face.second()] -= converted; closureBound[face.second()] += bound + Math.ulp(closure[face.second()]); }
			else {
				if (face.second() == -1) boundaryPressure[f] = face.prescribed() - reference[face.first()] + (faceNode[f] >= 0 ? pressureChange[faceNode[f]] : 0);
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
				dissipation, boundaryWork, new LiquidPolyhedron.Point(impulse[0], impulse[1], impulse[2]), divergenceResidual, divergenceBound, rates, pressureChange);
	}
	private static boolean converged(double[] residual, double[] error, double[] diagonal, double[] volume, double tolerance) {
		for (int i = 0; i < residual.length; i++) if (!(Math.abs(residual[i]) <= error[i] + tolerance * diagonal[i] * volume[i])) return false;
		return true;
	}
	private static double[] negate(double[] value) { for (int i = 0; i < value.length; i++) value[i] = -value[i]; return value; }
	private static double dot(double[] a, double[] b) { double value = 0; for (int i = 0; i < a.length; i++) value += a[i] * b[i]; return value; }
	private static int find(int[] root, int c) { while (root[c] != c) { root[c] = root[root[c]]; c = root[c]; } return c; }
}
