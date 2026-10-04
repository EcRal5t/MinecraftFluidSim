package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;

/** Cycle coordinates for integrated face volume rates (m^3/s), with exact cell continuity. */
final class ConservativeFluxCoordinates {
	private final int vertices;
	private final int[] first, second, parent, parentFace, order, independent;
	private final boolean[] constrained;
	private final SignedDyadic[] fixed;

	/** A null fixed entry denotes a free face. Unconstrained vertices carry prescribed-pressure or compliant gas nodes. */
	ConservativeFluxCoordinates(int vertices, int[] first, int[] second, boolean[] constrained, SignedDyadic[] fixed) {
		if (vertices < 1 || first.length != second.length || first.length != fixed.length || constrained.length != vertices)
			throw new IllegalArgumentException("Invalid conservative flux graph");
		this.vertices = vertices; this.first = first.clone(); this.second = second.clone();
		this.constrained = constrained.clone(); this.fixed = fixed.clone();
		var incidence = new ArrayList<ArrayList<Integer>>(); for (int v = 0; v < vertices; v++) incidence.add(new ArrayList<>());
		for (int f = 0; f < first.length; f++) {
			if (first[f] < 0 || first[f] >= vertices || second[f] < 0 || second[f] >= vertices || first[f] == second[f])
				throw new IllegalArgumentException("Flux face has invalid support");
			if (fixed[f] == null) { incidence.get(first[f]).add(f); incidence.get(second[f]).add(f); }
		}
		parent = new int[vertices]; parentFace = new int[vertices]; order = new int[vertices];
		Arrays.fill(parent, -1); Arrays.fill(parentFace, -1); boolean[] visited = new boolean[vertices]; int tail = 0, head = 0;
		for (int v = 0; v < vertices; v++) if (!constrained[v]) { visited[v] = true; order[tail++] = v; }
		for (;;) {
			while (head < tail) {
				int v = order[head++];
				for (int f : incidence.get(v)) {
					int other = first[f] == v ? second[f] : first[f];
					if (!visited[other]) { visited[other] = true; parent[other] = v; parentFace[other] = f; order[tail++] = other; }
				}
			}
			int next = 0; while (next < vertices && visited[next]) next++;
			if (next == vertices) break;
			visited[next] = true; order[tail++] = next;
		}
		boolean[] tree = new boolean[first.length]; for (int f : parentFace) if (f >= 0) tree[f] = true;
		independent = java.util.stream.IntStream.range(0, first.length).filter(f -> fixed[f] == null && !tree[f]).toArray();
	}
	int size() { return independent.length; }
	int face(int coordinate) { return independent[coordinate]; }

	/** Floating evaluation used inside a variational solve; its final coordinates are lifted exactly. */
	double[] lift(double[] coordinates, double[] source, boolean particular) {
		if (coordinates.length != size() || source.length != vertices) throw new IllegalArgumentException("Invalid flux coordinates");
		double[] result = new double[first.length], balance = new double[vertices];
		if (particular) for (int v = 0; v < vertices; v++) if (constrained[v]) balance[v] = source[v];
		for (int c = 0; c < independent.length; c++) result[independent[c]] = coordinates[c];
		for (int f = 0; f < fixed.length; f++) {
			if (particular && fixed[f] != null) result[f] = fixed[f].toDouble();
			balance[first[f]] -= result[f]; balance[second[f]] += result[f];
		}
		for (int i = order.length - 1; i >= 0; i--) {
			int v = order[i]; if (parentFace[v] < 0) continue;
			result[parentFace[v]] = first[parentFace[v]] == v ? balance[v] : -balance[v];
			balance[parent[v]] += balance[v];
		}
		return result;
	}

	/** Every constrained vertex satisfies sum(outgoing volume rates) == source exactly. */
	SignedDyadic[] liftExact(SignedDyadic[] coordinates, SignedDyadic[] source) {
		if (coordinates.length != size() || source.length != vertices) throw new IllegalArgumentException("Invalid exact flux coordinates");
		SignedDyadic[] result = new SignedDyadic[first.length], balance = new SignedDyadic[vertices];
		Arrays.fill(result, SignedDyadic.ZERO);
		for (int v = 0; v < vertices; v++) {
			if (source[v] == null) throw new IllegalArgumentException("Absent flux volume source");
			balance[v] = constrained[v] ? source[v] : SignedDyadic.ZERO;
		}
		for (int c = 0; c < independent.length; c++) {
			if (coordinates[c] == null) throw new IllegalArgumentException("Absent flux coordinate");
			result[independent[c]] = coordinates[c];
		}
		for (int f = 0; f < fixed.length; f++) {
			if (fixed[f] != null) result[f] = fixed[f];
			balance[first[f]] = balance[first[f]].subtract(result[f]); balance[second[f]] = balance[second[f]].add(result[f]);
		}
		for (int i = order.length - 1; i >= 0; i--) {
			int v = order[i];
			if (parentFace[v] >= 0) {
				result[parentFace[v]] = first[parentFace[v]] == v ? balance[v] : balance[v].negate();
				balance[parent[v]] = balance[parent[v]].add(balance[v]);
			} else if (constrained[v] && balance[v].signum() != 0) throw new IllegalArgumentException("Closed flux component has incompatible volume sources");
		}
		return result;
	}

	/** Transpose of the homogeneous lift; required by the variational gradient and Hessian. */
	double[] transpose(double[] faceValues) {
		if (faceValues.length != first.length) throw new IllegalArgumentException("Invalid flux dual values");
		double[] potential = potential(faceValues);
		double[] result = new double[independent.length];
		for (int c = 0; c < result.length; c++) {
			int f = independent[c]; result[c] = faceValues[f] - potential[first[f]] + potential[second[f]];
		}
		return result;
	}
	double[] potential(double[] faceValues) {
		if (faceValues.length != first.length) throw new IllegalArgumentException("Invalid flux dual values");
		double[] potential = new double[vertices];
		for (int v : order) if (parentFace[v] >= 0)
			potential[v] = potential[parent[v]] + (first[parentFace[v]] == v ? 1 : -1) * faceValues[parentFace[v]];
		return potential;
	}
	double[] transposeError(double[] values, double[] errors) {
		if (values.length != first.length || errors.length != values.length) throw new IllegalArgumentException("Invalid flux dual errors");
		double[] potential = new double[vertices], error = new double[vertices];
		for (int v : order) if (parentFace[v] >= 0) {
			int f = parentFace[v]; potential[v] = potential[parent[v]] + (first[f] == v ? 1 : -1) * values[f];
			error[v] = error[parent[v]] + errors[f] + Math.ulp(potential[v]);
		}
		double[] result = new double[independent.length];
		for (int c = 0; c < result.length; c++) {
			int f = independent[c]; double partial = values[f] - potential[first[f]];
			result[c] = errors[f] + error[first[f]] + error[second[f]] + Math.ulp(partial) + Math.ulp(partial + potential[second[f]]);
		}
		return result;
	}

	/** Forward error for the tree evaluation relative to the exact dyadic lift. */
	double[] liftError(double[] coordinates, double[] source, boolean particular) {
		double[] result = new double[first.length], balance = new double[vertices], error = new double[vertices];
		if (particular) for (int v = 0; v < vertices; v++) if (constrained[v]) {
			balance[v] = source[v]; error[v] = source[v] == 0 ? 0 : Math.ulp(source[v]);
		}
		for (int c = 0; c < independent.length; c++) result[independent[c]] = coordinates[c];
		for (int f = 0; f < fixed.length; f++) {
			if (particular && fixed[f] != null) result[f] = fixed[f].toDouble();
			balance[first[f]] -= result[f]; balance[second[f]] += result[f];
			double conversion = particular && fixed[f] != null && result[f] != 0 ? 0.5 * Math.ulp(result[f]) : 0;
			error[first[f]] += conversion + Math.ulp(balance[first[f]]); error[second[f]] += conversion + Math.ulp(balance[second[f]]);
		}
		Arrays.fill(result, 0);
		if (particular) for (int f = 0; f < fixed.length; f++) if (fixed[f] != null && fixed[f].signum() != 0)
			result[f] = Math.ulp(fixed[f].toDouble());
		for (int i = order.length - 1; i >= 0; i--) {
			int v = order[i]; if (parentFace[v] < 0) continue;
			result[parentFace[v]] = error[v]; balance[parent[v]] += balance[v]; error[parent[v]] += error[v] + Math.ulp(balance[parent[v]]);
		}
		return result;
	}

	double[] cycleWeights(double[] faceWeights) {
		if (faceWeights.length != first.length) throw new IllegalArgumentException("Invalid flux metric weights");
		int[] depth = depths(); double[] result = new double[independent.length];
		for (int c = 0; c < result.length; c++) {
			int f = independent[c], a = first[f], b = second[f]; result[c] = faceWeights[f];
			while (a != b) {
				if (a >= 0 && (b < 0 || depth[a] >= depth[b])) { if (parentFace[a] >= 0) result[c] += faceWeights[parentFace[a]]; a = parent[a]; }
				else { if (parentFace[b] >= 0) result[c] += faceWeights[parentFace[b]]; b = parent[b]; }
			}
		}
		return result;
	}
	double[] cycleMinimum(double[] vertexValues) {
		if (vertexValues.length != vertices) throw new IllegalArgumentException("Invalid flux vertex measures");
		int[] depth = depths(); double[] result = new double[independent.length];
		for (int c = 0; c < result.length; c++) {
			int f = independent[c], a = first[f], b = second[f]; result[c] = Math.min(vertexValues[a], vertexValues[b]);
			while (a != b) {
				if (a >= 0 && (b < 0 || depth[a] >= depth[b])) { result[c] = Math.min(result[c], vertexValues[a]); a = parent[a]; }
				else { result[c] = Math.min(result[c], vertexValues[b]); b = parent[b]; }
			}
			if (a >= 0) result[c] = Math.min(result[c], vertexValues[a]);
		}
		return result;
	}
	private int[] depths() {
		int[] depth = new int[vertices]; for (int v : order) if (parent[v] >= 0) depth[v] = depth[parent[v]] + 1; return depth;
	}
}
