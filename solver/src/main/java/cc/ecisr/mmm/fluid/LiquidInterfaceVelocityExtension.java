package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** Minimum-norm kinematic extension of submitted liquid face fluxes through the resolved gas space. */
final class LiquidInterfaceVelocityExtension {
	record Result(double[] normalVelocity, double[] cellVelocity, double[] gasDilatation, int iterations,
			double divergenceResidual, double divergenceRoundoffBound) { }
	private LiquidInterfaceVelocityExtension() { }

	static Result extend(LiquidControlVolumeMesh mesh, int[] liquid, double[] submittedFaces, double[] submittedCells,
			double divergenceTolerance, int maximumIterations) {
		if (liquid.length != mesh.cells.size() || submittedFaces.length != mesh.faces.size() || submittedCells.length != 3 * liquid.length
				|| !(divergenceTolerance > 0) || !Double.isFinite(divergenceTolerance) || maximumIterations <= 0)
			throw new IllegalArgumentException("Invalid interface velocity extension request");
		for (double velocity : submittedCells) if (!Double.isFinite(velocity)) throw new IllegalArgumentException("Non-finite submitted cell velocity");
		for (double velocity : submittedFaces) if (!Double.isFinite(velocity)) throw new IllegalArgumentException("Non-finite submitted face velocity");
		int[] gas = new int[liquid.length]; Arrays.fill(gas, -1); int count = 0;
		for (int c = 0; c < gas.length; c++) if (liquid[c] < 0) gas[c] = count++;
		int[] root = new int[count]; for (int g = 0; g < count; g++) root[g] = g;
		for (var face : mesh.faces) if (face.second() >= 0 && gas[face.first()] >= 0 && gas[face.second()] >= 0)
			root[find(root, gas[face.second()])] = find(root, gas[face.first()]);
		for (int g = 0; g < count; g++) root[g] = find(root, g);
		double[] volume = new double[count], sourceVolume = volume.clone(), prescribedFlux = volume.clone(); boolean[] interfaceAdjacent = new boolean[count];
		double[] contactArea = volume.clone(), contactVelocity = new double[3 * count]; boolean[] ambient = new boolean[count];
		for (int c = 0; c < gas.length; c++) if (gas[c] >= 0) volume[gas[c]] = mesh.cells.get(c).geometry().moments().volume();
		for (int f = 0; f < mesh.faces.size(); f++) {
			var face = mesh.faces.get(f); int first = gas[face.first()], second = face.second() >= 0 ? gas[face.second()] : -1;
			if (first < 0 && second < 0) continue;
			if (first >= 0 && second >= 0) continue;
			int g = first >= 0 ? first : second, component = root[g]; double sign = first >= 0 ? 1 : -1, area = face.surface().area();
			if (face.second() == -1) { ambient[component] = true; continue; }
			double velocity = face.second() == -2 ? face.prescribed() : submittedFaces[f];
			if (!Double.isFinite(velocity)) throw new IllegalArgumentException("Non-finite submitted interface velocity");
			prescribedFlux[component] += sign * area * velocity;
			if (face.second() >= 0) {
				interfaceAdjacent[g] = true;
				int source = first >= 0 ? face.second() : face.first(); contactArea[component] += area;
				for (int a = 0; a < 3; a++) {
					if (!Double.isFinite(submittedCells[3 * source + a])) throw new IllegalArgumentException("Non-finite submitted liquid cell velocity");
					contactVelocity[3 * component + a] += area * submittedCells[3 * source + a];
				}
			}
		}
		// The velocity support touching liquid is incompressible. Finite gas displacement is
		// distributed through the remaining gas volume, outside the moving-interface support.
		for (int g = 0; g < count; g++) if (!interfaceAdjacent[g]) sourceVolume[root[g]] += volume[g];
		for (int g = 0; g < count; g++) if (g == root[g] && !ambient[g] && prescribedFlux[g] != 0 && !(sourceVolume[g] > 0))
			throw new IllegalStateException("Conservative interface velocity support requires finer gas geometry");
		double[] dilatation = new double[count], reference = new double[3 * count];
		boolean[] anchor = new boolean[count];
		for (int g = 0; g < count; g++) {
			int component = root[g]; dilatation[g] = ambient[component] || interfaceAdjacent[g] || sourceVolume[component] == 0 ? 0 : prescribedFlux[component] / sourceVolume[component];
			anchor[g] = g == component && !ambient[component];
			for (int a = 0; a < 3; a++) reference[3 * g + a] = contactArea[component] > 0 ? contactVelocity[3 * component + a] / contactArea[component] : 0;
			if (!Double.isFinite(dilatation[g])) throw new IllegalStateException("Gas extension dilatation exceeds numerical range");
			for (int a = 0; a < 3; a++) if (!Double.isFinite(reference[3 * g + a])) throw new IllegalStateException("Gas extension velocity exceeds numerical range");
		}
		double[] velocities = submittedFaces.clone(), conductance = new double[mesh.faces.size()], diagonal = new double[count], rhs = new double[count], rhsMagnitude = new double[count];
		for (int f = 0; f < mesh.faces.size(); f++) {
			var face = mesh.faces.get(f); int first = gas[face.first()], second = face.second() >= 0 ? gas[face.second()] : -1;
			if (first < 0 && second < 0) continue;
			if (first >= 0 && second >= 0 || first >= 0 && face.second() == -1) {
				var center = mesh.cells.get(face.first()).centroid(); var normal = face.surface().areaVector(); double area = face.surface().area();
				double distance = normal.dot(face.surface().centroid().subtract(center)) / area;
				if (second >= 0) distance += normal.dot(mesh.cells.get(face.second()).centroid().subtract(face.surface().centroid())) / area;
				if (!(distance > 0)) throw new IllegalArgumentException("Gas extension face has invalid centroid distance");
				conductance[f] = area / distance;
				if (!Double.isFinite(conductance[f])) throw new IllegalStateException("Interface extension exceeds numerical range");
				velocities[f] = 0; for (int a = 0; a < 3; a++) velocities[f] += reference[3 * first + a] * normal.coordinate(a) / area;
				diagonal[first] += conductance[f]; if (second >= 0) diagonal[second] += conductance[f];
			} else if (face.second() == -2) velocities[f] = face.prescribed();
			double flux = face.surface().area() * velocities[f];
			if (first >= 0) { rhs[first] += flux; rhsMagnitude[first] += Math.abs(flux); }
			if (second >= 0) { rhs[second] -= flux; rhsMagnitude[second] += Math.abs(flux); }
		}
		for (int g = 0; g < count; g++) { rhs[g] -= dilatation[g] * volume[g]; rhsMagnitude[g] += Math.abs(dilatation[g] * volume[g]); }
		double[] potential = new double[count], residual = rhs.clone(), direction = new double[count], product = new double[count];
		double rz = 0; for (int g = 0; g < count; g++) { direction[g] = !anchor[g] && diagonal[g] > 0 ? residual[g] / diagonal[g] : 0; rz += residual[g] * direction[g]; }
		int iterations = 0;
		while (!converged(residual, potential, rhsMagnitude, diagonal, volume, divergenceTolerance, mesh.faces.size())) {
			if (iterations++ >= maximumIterations || !(rz > 0)) throw new IllegalStateException("Interface velocity extension did not converge");
			multiply(mesh, gas, conductance, direction, product); double denominator = dot(direction, product);
			if (!(denominator > 0) || !Double.isFinite(denominator)) throw new IllegalStateException("Invalid interface extension search direction");
			double alpha = rz / denominator; for (int g = 0; g < count; g++) potential[g] += alpha * direction[g];
			multiply(mesh, gas, conductance, potential, product); double next = 0;
			for (int g = 0; g < count; g++) { residual[g] = rhs[g] - product[g]; if (!anchor[g] && diagonal[g] > 0) next += residual[g] * residual[g] / diagonal[g]; }
			for (int g = 0; g < count; g++) direction[g] = (!anchor[g] && diagonal[g] > 0 ? residual[g] / diagonal[g] : 0) + next / rz * direction[g];
			rz = next;
		}
		for (int f = 0; f < mesh.faces.size(); f++) if (conductance[f] > 0) {
			var face = mesh.faces.get(f); int first = gas[face.first()], second = face.second() >= 0 ? gas[face.second()] : -1;
			velocities[f] -= conductance[f] * (potential[first] - (second >= 0 ? potential[second] : 0)) / face.surface().area();
		}
		double[] cellVelocity = submittedCells.clone(), gasDilatation = new double[liquid.length], fluxes = new double[count];
		for (int c = 0; c < gas.length; c++) if (gas[c] >= 0) {
			gasDilatation[c] = dilatation[gas[c]]; var center = mesh.cells.get(c).centroid();
			for (int a = 0; a < 3; a++) cellVelocity[3 * c + a] = 0;
			for (int f : mesh.cellFaces[c]) {
				var face = mesh.faces.get(f); double flux = (face.first() == c ? 1 : -1) * face.surface().area() * velocities[f];
				fluxes[gas[c]] += flux;
				for (int a = 0; a < 3; a++) cellVelocity[3 * c + a] += face.surface().centroid().subtract(center).coordinate(a) * flux / volume[gas[c]];
			}
		}
		double error = 0, bound = 0; double[] roundoff = roundoff(potential, rhsMagnitude, diagonal, mesh.faces.size());
		for (int g = 0; g < count; g++) {
			double mismatch = Math.abs(fluxes[g] - volume[g] * dilatation[g]);
			if (!(mismatch <= divergenceTolerance * volume[g] + roundoff[g])) throw new IllegalStateException("Extended interface velocity does not close its gas control volume");
			error = Math.max(error, mismatch / volume[g]); bound = Math.max(bound, roundoff[g] / volume[g]);
		}
		return new Result(velocities, cellVelocity, gasDilatation, iterations, error, bound);
	}
	private static void multiply(LiquidControlVolumeMesh mesh, int[] gas, double[] conductance, double[] value, double[] output) {
		Arrays.fill(output, 0);
		for (int f = 0; f < conductance.length; f++) if (conductance[f] > 0) {
			var face = mesh.faces.get(f); int a = gas[face.first()], b = face.second() >= 0 ? gas[face.second()] : -1;
			double flux = conductance[f] * (value[a] - (b >= 0 ? value[b] : 0)); output[a] += flux; if (b >= 0) output[b] -= flux;
		}
	}
	private static boolean converged(double[] residual, double[] value, double[] rhs, double[] diagonal, double[] volume, double tolerance, int operations) {
		double[] error = roundoff(value, rhs, diagonal, operations);
		for (int g = 0; g < residual.length; g++) if (!(Math.abs(residual[g]) <= tolerance * volume[g] + error[g])) return false;
		return true;
	}
	private static double[] roundoff(double[] value, double[] rhs, double[] diagonal, int operations) {
		double magnitude = 0; for (double v : value) magnitude = Math.max(magnitude, Math.abs(v));
		double epsilon = Math.scalb(8.0 * operations + 16, -53), gamma = epsilon / (1 - epsilon);
		double[] result = new double[value.length];
		for (int g = 0; g < result.length; g++) result[g] = gamma * (rhs[g] + 2 * diagonal[g] * magnitude);
		return result;
	}
	private static int find(int[] root, int g) { while (root[g] != g) { root[g] = root[root[g]]; g = root[g]; } return g; }
	private static double dot(double[] a, double[] b) { double sum = 0; for (int i = 0; i < a.length; i++) sum += a[i] * b[i]; return sum; }
}
