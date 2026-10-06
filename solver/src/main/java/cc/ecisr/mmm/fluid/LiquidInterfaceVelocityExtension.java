package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.Arrays;

/** Minimum-norm extension in exactly conservative face-rate coordinates through resolved gas geometry. */
final class LiquidInterfaceVelocityExtension {
	record Result(double[] normalVelocity, double[] cellVelocity, double[] gasDilatation, int iterations,
			double divergenceResidual, double divergenceRoundoffBound, Fraction[] volumeRate,
			Fraction[] volumeSource) { }
	private record Gradient(double[] value, double[] error) { }
	private LiquidInterfaceVelocityExtension() { }

	static Result extend(LiquidControlVolumeMesh mesh, int[] liquid, Fraction[] submittedRates, double[] submittedCells,
			double divergenceTolerance, int maximumIterations) {
		int count = liquid.length, faces = mesh.faces.size();
		if (count != mesh.cells.size() || submittedRates.length != faces || submittedCells.length != 3 * count
				|| !(divergenceTolerance > 0) || !Double.isFinite(divergenceTolerance) || maximumIterations <= 0)
			throw new IllegalArgumentException("Invalid interface velocity extension request");
		for (double v : submittedCells) if (!Double.isFinite(v)) throw new IllegalArgumentException("Non-finite submitted cell velocity");
		for (var rate : submittedRates) if (rate == null || !Double.isFinite(rate.toDouble())) throw new IllegalArgumentException("Invalid submitted face rate");
		int[] root = LiquidPhasePartition.gasComponents(mesh, liquid);
		boolean[] adjacent = new boolean[count], ambient = adjacent.clone(), constrained = new boolean[count + 1];
		Arrays.fill(constrained, 0, count, true);
		int[] first = new int[faces], second = first.clone(); var fixed = new Fraction[faces];
		double[] contactArea = new double[count], contactVelocity = new double[3 * count], contactReference = new double[3 * count], volume = new double[count + 1];
		boolean[] contactPresent = new boolean[count];
		volume[count] = Double.POSITIVE_INFINITY; var displacement = new Fraction[count]; Arrays.fill(displacement, Fraction.ZERO);
		for (int c = 0; c < count; c++) volume[c] = mesh.cells.get(c).geometry().moments().volume();
		for (int f = 0; f < faces; f++) {
			var face = mesh.faces.get(f); first[f] = face.first(); second[f] = face.second() >= 0 ? face.second() : count;
			boolean a = liquid[first[f]] < 0, b = face.second() >= 0 && liquid[second[f]] < 0;
			if (face.second() == -2) {
				fixed[f] = Fraction.of(face.surface().area() * face.prescribed());
				if (!a && !fixed[f].equals(submittedRates[f])) throw new IllegalArgumentException("Submitted liquid wall rate differs from solid motion");
			} else if (!(a && (b || face.second() == -1))) fixed[f] = submittedRates[f];
			if (a && face.second() == -1) ambient[root[first[f]]] = true;
			if (a && b || !a && !b) continue;
			int gas = a ? first[f] : second[f], component = root[gas];
			if (fixed[f] != null) displacement[component] = displacement[component].add(a ? fixed[f] : fixed[f].negate());
			if (face.second() >= 0) {
				adjacent[gas] = true; int source = a ? second[f] : first[f]; double area = face.surface().area();
				if (!contactPresent[component]) { System.arraycopy(submittedCells, 3 * source, contactReference, 3 * component, 3); contactPresent[component] = true; }
				contactArea[component] += area;
				for (int axis = 0; axis < 3; axis++) contactVelocity[3 * component + axis] += area * (submittedCells[3 * source + axis] - contactReference[3 * component + axis]);
			}
		}
		var source = new Fraction[count + 1]; Arrays.fill(source, Fraction.ZERO);
		var componentSupport = new java.util.ArrayList<java.util.ArrayList<Integer>>();
		for (int c = 0; c < count; c++) componentSupport.add(new java.util.ArrayList<>());
		for (int c = 0; c < count; c++) if (liquid[c] < 0 && !adjacent[c]) componentSupport.get(root[c]).add(c);
		// Gas compression is distributed by the exact supporting volumes away from liquid.
		for (int component = 0; component < count; component++) if (liquid[component] < 0 && root[component] == component
				&& !ambient[component] && displacement[component].signum() != 0) {
			var indices = componentSupport.get(component); int supports = indices.size(); var weights = new Fraction[supports];
			for (int i = 0; i < supports; i++) weights[i] = LiquidConvexRegion.of(mesh.cells.get(indices.get(i)).geometry()).measure().volume();
			if (supports == 0) {
				int target = component;
				throw new LiquidInterfaceResolutionRequired(java.util.stream.IntStream.range(0, count)
						.filter(c -> liquid[c] < 0 && root[c] == target).toArray());
			}
			var allocation = LiquidFluxAllocation.proportional(displacement[component], weights);
			for (int i = 0; i < supports; i++) {
				int c = indices.get(i); source[c] = allocation[i];
			}
		}
		double[] resistance = new double[faces], referenceCell = new double[3 * count];
		var exactReference = new Fraction[faces]; Arrays.fill(exactReference, Fraction.ZERO);
		for (int c = 0; c < count; c++) if (liquid[c] < 0) for (int axis = 0; axis < 3; axis++)
			referenceCell[3 * c + axis] = contactArea[root[c]] > 0 ? contactReference[3 * root[c] + axis] + contactVelocity[3 * root[c] + axis] / contactArea[root[c]] : 0;
		for (int f = 0; f < faces; f++) {
			var face = mesh.faces.get(f); var vector = face.surface().areaVector(); double area = face.surface().area();
			if (fixed[f] == null) {
				double distance = face.distance(mesh.cells.get(first[f]).geometry(), 1);
				if (second[f] < count) distance += face.distance(mesh.cells.get(second[f]).geometry(), -1);
				resistance[f] = distance / area;
				if (!(resistance[f] > 0) || !Double.isFinite(resistance[f])) throw new IllegalStateException("Interface extension metric exceeds numerical range");
			}
			int gas = liquid[first[f]] < 0 ? first[f] : second[f] < count && liquid[second[f]] < 0 ? second[f] : -1;
			if (gas < 0) continue;
			for (int axis = 0; axis < 3; axis++) {
				double velocity = referenceCell[3 * gas + axis];
				exactReference[f] = exactReference[f].add(face.areaVector().get(axis).multiply(Fraction.of(velocity)));
			}
			if (!Double.isFinite(exactReference[f].rounded())) throw new IllegalStateException("Interface extension reference exceeds numerical range");
		}
		var coordinates = new ConservativeFluxCoordinates(count + 1, first, second, constrained, fixed, resistance);
		double[] observedSource = new double[source.length]; for (int c = 0; c < source.length; c++) observedSource[c] = source[c].toDouble();
		double[] diagonal = coordinates.cycleWeights(resistance), support = coordinates.cycleMinimum(volume), variable = new double[coordinates.size()];
		var exact = new Fraction[variable.length];
		for (int i = 0; i < exact.length; i++) exact[i] = exactReference[coordinates.face(i)];
		var offset = new ConservativeFluxOffset(coordinates, exact, source).relativeTo(exactReference);
		var gradient = gradient(offset.values(variable), offset.error(variable), resistance);
		double[] residual = negate(coordinates.transpose(gradient.value)); var directions = new ConservativeConjugateDirections(diagonal);
		int iterations = 0;
		while (!converged(residual, coordinates.transposeError(gradient.value, gradient.error), diagonal, support, divergenceTolerance)) {
			if (iterations >= maximumIterations) throw new IllegalStateException("Interface velocity extension did not converge: iterations=" + iterations + " coordinates=" + variable.length);
			iterations++; var direction = directions.direction(residual);
			double[] lifted = coordinates.lift(direction, observedSource, false); for (int f = 0; f < faces; f++) lifted[f] *= resistance[f];
			var product = coordinates.transpose(lifted); double denominator = dot(direction, product);
			if (!(denominator > 0) || !Double.isFinite(denominator)) throw new IllegalStateException("Invalid interface extension search direction");
			directions.remember(direction, product);
			double alpha = dot(residual, direction) / denominator; for (int i = 0; i < variable.length; i++) variable[i] += alpha * direction[i];
			gradient = gradient(offset.values(variable), offset.error(variable), resistance);
			residual = negate(coordinates.transpose(gradient.value));
		}
		var rates = offset.exact(variable); double[] flow = new double[faces], difference = flow.clone(), error = flow.clone();
		for (int f = 0; f < faces; f++) {
			flow[f] = rates[f].toDouble(); var observed = LiquidBoundedArithmetic.Value.of(rates[f].subtract(exactReference[f]));
			difference[f] = observed.value(); error[f] = observed.error();
		}
		gradient = gradient(difference, error, resistance);
		if (!converged(negate(coordinates.transpose(gradient.value)), coordinates.transposeError(gradient.value, gradient.error), diagonal, support, divergenceTolerance))
			throw new IllegalStateException("Exact interface rates exceed extension solve accuracy");
		return result(mesh, liquid, submittedCells, rates, flow, source, iterations, referenceCell, exactReference);
	}
	private static Gradient gradient(double[] difference, double[] error, double[] resistance) {
		double[] value = new double[difference.length], bound = value.clone();
		for (int f = 0; f < difference.length; f++) if (resistance[f] > 0) {
			value[f] = resistance[f] * difference[f];
			bound[f] = Math.nextUp(Math.nextUp(resistance[f] * error[f]) + Math.ulp(value[f]));
		}
		return new Gradient(value, bound);
	}
	private static Result result(LiquidControlVolumeMesh mesh, int[] liquid, double[] submittedCells, Fraction[] rates,
			double[] flow, Fraction[] source, int iterations, double[] referenceCell, Fraction[] reference) {
		double[] normal = new double[rates.length], primary = submittedCells.clone(), dilatation = new double[liquid.length];
		double error = 0, bound = 0;
		for (int f = 0; f < rates.length; f++) normal[f] = flow[f] / mesh.faces.get(f).surface().area();
		for (int c = 0; c < liquid.length; c++) {
			double volume = mesh.cells.get(c).geometry().moments().volume(), target = source[c].toDouble();
			dilatation[c] = target / volume; double balance = -target, roundoff = target == 0 ? 0 : Math.ulp(target);
			if (liquid[c] < 0) System.arraycopy(referenceCell, 3 * c, primary, 3 * c, 3);
			for (int f : mesh.cellFaces[c]) {
				var face = mesh.faces.get(f); double sign = face.first() == c ? 1 : -1, rate = sign * face.surface().area() * normal[f];
				balance += rate; roundoff += Math.ulp(flow[f]) + face.surface().area() * Math.ulp(normal[f]) + Math.ulp(rate) + Math.ulp(balance);
				if (liquid[c] < 0) for (int a = 0; a < 3; a++) primary[3 * c + a] += face.displacement(mesh.cells.get(c).geometry()).coordinate(a) * sign * rates[f].subtract(reference[f]).toDouble() / volume;
			}
			if (!(Math.abs(balance) <= roundoff)) throw new IllegalStateException("Interface flux observation exceeds exact continuity representation");
			error = Math.max(error, Math.abs(balance) / volume); bound = Math.max(bound, roundoff / volume);
		}
		return new Result(normal, primary, dilatation, iterations, error, bound, rates, Arrays.copyOf(source, liquid.length));
	}
	private static boolean converged(double[] residual, double[] error, double[] diagonal, double[] support, double tolerance) {
		for (int i = 0; i < residual.length; i++) if (!(Math.abs(residual[i]) <= error[i] + tolerance * diagonal[i] * support[i])) return false;
		return true;
	}
	private static double[] negate(double[] values) { for (int i = 0; i < values.length; i++) values[i] = -values[i]; return values; }
	private static double dot(double[] a, double[] b) { return ConservativeConjugateDirections.dot(a, b); }
}
