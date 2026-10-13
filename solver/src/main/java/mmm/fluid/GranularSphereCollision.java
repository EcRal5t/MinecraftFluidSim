package mmm.fluid;

import mmm.granular.ParticulateMaterial;
import mmm.granular.GranularDepositStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Local swept sphere contacts; position drift and material impulses use independent vectors. */
final class GranularSphereCollision {
	record Motion(float x, float y, float z, float vx, float vy, float vz, boolean supported, int operations) { }
	private record Hit(double lower, double upper, double[] normal, double[] minimum, double[] maximum, double envelope, int axis) { }
	private final int[] sizes;
	private final double h;
	private final GranularCollisionGeometry geometry;
	private int remainingOperations;
	private static double gamma(int operations) {
		double error = operations * Math.ulp(1.0) / 2;
		return error / (1 - error);
	}
	GranularSphereCollision(int x, int y, int z, float h, FlipCutCellGeometry geometry, GranularDepositStore deposits) {
		this.sizes = new int[]{x, y, z}; this.h = h; this.geometry = new GranularCollisionGeometry(x, y, z, h, geometry, deposits);
	}
	Motion preview(float[] initial, float[] velocity, float radius, float dt, ParticulateMaterial material, int budget) {
		remainingOperations = budget;
		double[] p = {initial[0], initial[1], initial[2]}, v = {velocity[0], velocity[1], velocity[2]}, d = new double[3];
		for (int a = 0; a < 3; a++) d[a] = (float) (initial[a] + velocity[a] * dt) - (double) initial[a];
		List<Hit> history = touching(p, radius, new ArrayList<>());
		if (!history.isEmpty()) {
			double[][] normals = history.stream().map(Hit::normal).toArray(double[][]::new);
			double[] drift = project(d, normals), response = impulse(v, normals, material);
			if (!Arrays.equals(d, drift) || !Arrays.equals(v, response)) operation();
			d = drift; v = response;
		}
		while (norm2(d) > 0) {
			List<Hit> hits = first(p, d, radius);
			if (hits.isEmpty()) { for (int a = 0; a < 3; a++) p[a] += d[a]; break; }
			operation();
			double time = hits.stream().mapToDouble(Hit::lower).min().orElseThrow();
			for (int a = 0; a < 3; a++) { p[a] += time * d[a]; d[a] *= 1 - time; }
			for (Hit hit : hits) {
				double gap = hit.axis >= 0 ? Math.abs(p[hit.axis] - hit.minimum[0]) : Math.abs(norm2(boxNormal(p, hit.minimum, hit.maximum)) - radius * radius);
				history.add(new Hit(0, 0, hit.normal, hit.minimum, hit.maximum, Math.max(hit.envelope, gap), hit.axis));
			}
			history = touching(p, radius, history);
			double[][] normals = history.stream().map(Hit::normal).toArray(double[][]::new);
			d = project(d, normals); v = impulse(v, normals, material);
		}
		history = touching(p, radius, history);
		float[] endpoint = roundOutside(p, radius);
		boolean supported = velocity[1] <= 0 && history.stream().anyMatch(hit -> hit.normal[1] > 0);
		return new Motion(endpoint[0], endpoint[1], endpoint[2], (float) v[0], (float) v[1], (float) v[2], supported, budget - remainingOperations);
	}
	/** Cold support at a stored resting pose, using the same coordinate rounding envelope as motion. */
	boolean supportsAtRest(float x, float y, float z, float radius) {
		return touching(new double[]{x, y, z}, radius, new ArrayList<>()).stream().anyMatch(hit -> hit.normal[1] > 0);
	}
	record Contact(double time, double[][] normals) { }
	Contact nextContact(double[] position, double[] velocity, double[] responseVelocity, float radius, double duration) {
		double[] displacement = new double[3];
		for (int axis = 0; axis < 3; axis++) displacement[axis] = velocity[axis] * duration;
		var contacts = touching(position, radius, new ArrayList<>());
		if (contacts.stream().anyMatch(hit -> dot(velocity, hit.normal) < 0 || dot(responseVelocity, hit.normal) < 0))
			return new Contact(0, contacts.stream().map(Hit::normal).toArray(double[][]::new));
		var hits = first(position, displacement, radius);
		if (hits.isEmpty()) return null;
		double time = hits.stream().mapToDouble(Hit::lower).min().orElseThrow();
		double[] atHit = new double[3];
		for (int axis = 0; axis < 3; axis++) atHit[axis] = Math.fma(time, displacement[axis], position[axis]);
		for (var hit : hits) {
			double gap = hit.axis >= 0 ? Math.abs(atHit[hit.axis] - hit.minimum[0])
					: Math.abs(norm2(boxNormal(atHit, hit.minimum, hit.maximum)) - radius * radius);
			contacts.add(new Hit(0, 0, hit.normal, hit.minimum, hit.maximum, Math.max(hit.envelope, gap), hit.axis));
		}
		return new Contact(duration * time, touching(atHit, radius, contacts).stream().map(Hit::normal).toArray(double[][]::new));
	}
	double[] compress(double[] velocity, double[][] normals) { return project(velocity, normals); }
	double[] respond(double[] velocity, double[][] normals, ParticulateMaterial material) {
		return impulse(velocity, normals, material);
	}
	float[] roundContactPoint(double[] position, float radius) { return roundOutside(position, radius); }
	private void operation() { if (--remainingOperations < 0) throw new MechanicalTimeRefinement(2, "Granular sphere contact operations"); }
	private List<Hit> first(double[] p, double[] d, double radius) {
		List<Hit> candidates = new ArrayList<>();
		for (int a = 0; a < 3; a++) if (d[a] != 0) {
			double plane = d[a] > 0 ? sizes[a] * h - radius : radius;
			double[] normal = new double[3]; normal[a] = d[a] > 0 ? -1 : 1;
			planeHit(candidates, (plane - p[a]) / d[a], gamma(3) * (Math.abs(plane) + Math.abs(p[a])) / Math.abs(d[a]), normal, a, plane, d[a]);
		}
		// CFL bounds the initial L1 drift by h; projection cannot increase L2.
		// Local queries cover at most 4^3 micro boxes and 3^3 bed cells.
		double[] lo = new double[3], hi = new double[3];
		for (int a = 0; a < 3; a++) { lo[a] = Math.min(p[a], p[a] + d[a]) - radius; hi[a] = Math.max(p[a], p[a] + d[a]) + radius; }
		geometry.forEachBox(lo, hi, (minimum, maximum) -> {
			Hit hit = box(p, d, minimum, maximum, radius); if (hit != null) candidates.add(hit);
		});
		if (candidates.isEmpty()) return candidates;
		double earliestUpper = candidates.stream().mapToDouble(Hit::upper).min().orElseThrow();
		List<Hit> group = new ArrayList<>();
		for (Hit hit : candidates) if (hit.lower <= earliestUpper) {
			boolean duplicate = false; for (Hit old : group) duplicate |= Arrays.equals(old.normal, hit.normal);
			if (!duplicate) group.add(hit);
		}
		return group;
	}
	private static void planeHit(List<Hit> hits, double time, double error, double[] normal, int axis, double plane, double speed) {
		if (time >= 0 && time <= 1) hits.add(new Hit(Math.max(0, Math.nextDown(time - error)), Math.min(1, Math.nextUp(time + error)), normal, new double[]{plane, 0, 0}, null, error * Math.abs(speed), axis));
	}
	private static Hit box(double[] p, double[] d, double[] lo, double[] hi, double radius) {
		double[] knots = new double[8]; int count = 2; knots[0] = 0; knots[1] = 1;
		for (int a = 0; a < 3; a++) if (d[a] != 0) for (double plane : new double[]{lo[a], hi[a]}) {
			double time = (plane - p[a]) / d[a]; if (time > 0 && time < 1) knots[count++] = time;
		}
		Arrays.sort(knots, 0, count);
		for (int k = 0; k < count - 1; k++) {
			double start = knots[k], end = knots[k + 1]; if (start == end) continue;
			double midpoint = (start + end) / 2, A = 0, B = 0, squares = 0, coordinateError = 0;
			for (int a = 0; a < 3; a++) {
				double at = p[a] + midpoint * d[a]; if (at >= lo[a] && at <= hi[a]) continue;
				double plane = at < lo[a] ? lo[a] : hi[a], offset = p[a] + start * d[a] - plane;
				double error = gamma(3) * (Math.abs(p[a]) + Math.abs(start * d[a]) + Math.abs(plane));
				coordinateError += 2 * error * (Math.abs(offset) + (end - start) * Math.abs(d[a])) + error * error;
				A += d[a] * d[a]; B += offset * d[a]; squares += offset * offset;
			}
			double C = squares - radius * radius, disc = B * B - A * C;
			if (A == 0 || B >= 0 || disc <= 0) continue;
			double root = C <= 0 ? 0 : C / (-B + Math.sqrt(disc));
			if (root < 0 || root > end - start) continue;
			// Sixteen dependent scalar operations cover coefficient sums and the stable root.
			// The coefficient envelope gives an earlier/later root without a contact skin parameter.
			double span = end - start;
			double error = coordinateError + gamma(16) * (A * span * span + 2 * Math.abs(B) * span + squares + radius * radius);
			double lowerC = C - error, upperC = C + error;
			double lower = lowerC <= 0 ? 0 : lowerC / (-B + Math.sqrt(B * B - A * lowerC));
			double upperDisc = B * B - A * upperC;
			double upper = upperDisc <= 0 ? -B / A : upperC / (-B + Math.sqrt(upperDisc));
			double time = Math.max(start, Math.nextDown(start + lower)); double[] normal = new double[3];
			for (int a = 0; a < 3; a++) { double at = p[a] + time * d[a]; normal[a] = at - Math.max(lo[a], Math.min(hi[a], at)); }
			double magnitude = Math.sqrt(norm2(normal)); if (magnitude == 0) throw new IllegalStateException("Initial granular sphere penetration");
			for (int a = 0; a < 3; a++) normal[a] /= magnitude;
			if (dot(normal, d) < 0) return new Hit(time, Math.min(1, Math.nextUp(start + upper)), normal, lo, hi, error, -1);
		}
		return null;
	}
	private List<Hit> touching(double[] p, double radius, List<Hit> history) {
		List<Hit> candidates = new ArrayList<>(history), active = new ArrayList<>();
		double[] quantum = new double[3]; for (int a = 0; a < 3; a++) { float stored = (float) p[a]; quantum[a] = Math.max(Math.nextUp(stored) - (double) stored, stored - (double) Math.nextDown(stored)); }
		for (int a = 0; a < 3; a++) for (int side : new int[]{-1, 1}) {
			double[] normal = new double[3]; normal[a] = side; double plane = side > 0 ? radius : sizes[a] * h - radius;
			candidates.add(new Hit(0, 0, normal, new double[]{plane, 0, 0}, null, 0, a));
		}
		double[] lo = new double[3], hi = new double[3];
		for (int a = 0; a < 3; a++) { lo[a] = p[a] - radius - quantum[a]; hi[a] = p[a] + radius + quantum[a]; }
		geometry.forEachBox(lo, hi, (minimum, maximum) -> candidates.add(new Hit(0, 0, new double[3], minimum, maximum, 0, -1)));
		for (Hit hit : candidates) {
			double[] normal = hit.normal;
			if (hit.axis >= 0) {
				if (Math.abs(p[hit.axis] - hit.minimum[0]) > hit.envelope + quantum[hit.axis] + gamma(3) * (Math.abs(p[hit.axis]) + Math.abs(hit.minimum[0]))) continue;
			} else {
				normal = new double[3]; double error = 0;
				for (int a = 0; a < 3; a++) { double plane = Math.max(hit.minimum[a], Math.min(hit.maximum[a], p[a])); normal[a] = p[a] - plane; double round = quantum[a] + gamma(1) * (Math.abs(p[a]) + Math.abs(plane)); error += 2 * Math.abs(normal[a]) * round + round * round; }
				double squared = norm2(normal); if (squared == 0 || Math.abs(squared - radius * radius) > hit.envelope + error + gamma(6) * (squared + radius * radius)) continue;
				double length = Math.sqrt(squared); for (int a = 0; a < 3; a++) normal[a] /= length;
			}
			boolean duplicate = false; for (Hit old : active) duplicate |= Arrays.equals(old.normal, normal);
			if (!duplicate) active.add(new Hit(0, 0, normal, hit.minimum, hit.maximum, hit.envelope, hit.axis));
		}
		return active;
	}
	private double[] impulse(double[] incoming, double[][] normals, ParticulateMaterial material) {
		double[] compressed = project(incoming, normals), impulse = new double[3], predicted = new double[3];
		for (int a = 0; a < 3; a++) impulse[a] = compressed[a] - incoming[a];
		double restitution = material.restitution(); if (restitution * Math.sqrt(norm2(impulse)) <= h * .125) restitution = 0;
		for (int a = 0; a < 3; a++) predicted[a] = compressed[a] + restitution * impulse[a];
		double[] result = project(predicted, normals);
		for (int a = 0; a < 3; a++) impulse[a] = result[a] - incoming[a];
		double[] tangent = tangent(result, normals); double speed = Math.sqrt(norm2(tangent)), load = Math.sqrt(norm2(impulse));
		double scale = speed == 0 || speed <= material.staticFriction() * load ? 0 : Math.max(0, 1 - material.dynamicFriction() * load / speed);
		for (int a = 0; a < 3; a++) result[a] += (scale - 1) * tangent[a];
		return result;
	}
	private double[] project(double[] v, double[][] normals) {
		// Diameter < one micro box: at most eight micro boxes, eight bed boxes,
		// and three shell planes. At most 1159 active subsets in 3D.
		if (normals.length > 19) throw new MechanicalTimeRefinement(2, "Granular contact support");
		if (feasible(v, normals, new double[3])) return v.clone();
		double[] best = null; double cost = Double.POSITIVE_INFINITY; int n = normals.length;
		for (int a = 0; a < n; a++) for (int b = -1; b < n; b++) for (int c = -1; c < n; c++) {
			if (b >= 0 && b <= a || c >= 0 && (b < 0 || c <= b)) continue;
			int[] indices = c >= 0 ? new int[]{a, b, c} : b >= 0 ? new int[]{a, b} : new int[]{a}; int count = indices.length;
			double[][] matrix = new double[count][count + 1];
			for (int i = 0; i < count; i++) {
				for (int j = 0; j < count; j++) matrix[i][j] = dot(normals[indices[i]], normals[indices[j]]);
				matrix[i][count] = -dot(normals[indices[i]], v);
			}
			double[] lambda = solve(matrix); if (lambda == null) continue;
			double[] candidate = v.clone(), formation = new double[3]; boolean positive = true;
			for (int j = 0; j < 3; j++) formation[j] = Math.abs(v[j]);
			for (int i = 0; i < count; i++) {
				if (lambda[i] < 0) { positive = false; break; }
				for (int j = 0; j < 3; j++) { double term = lambda[i] * normals[indices[i]][j]; candidate[j] += term; formation[j] += Math.abs(term); }
			}
			if (!positive || !feasible(candidate, normals, formation)) continue;
			boolean zero = true; for (int j = 0; j < 3; j++) zero &= Math.abs(candidate[j]) <= gamma(6) * formation[j];
			if (zero) Arrays.fill(candidate, 0);
			double change = 0; for (int j = 0; j < 3; j++) change += (candidate[j] - v[j]) * (candidate[j] - v[j]);
			if (change < cost) { cost = change; best = candidate; }
		}
		if (best == null) throw new MechanicalTimeRefinement(2, "Granular contact cone conditioning");
		if (normals.length == 1) {
			int pivot = 0; for (int a = 1; a < 3; a++) if (Math.abs(normals[0][a]) > Math.abs(normals[0][pivot])) pivot = a;
			if (dot(best, normals[0]) < 0) {
				double others = 0, magnitude = 0;
				for (int a = 0; a < 3; a++) if (a != pivot) { double term = best[a] * normals[0][a]; others += term; magnitude += Math.abs(term); }
				// Direct dot-product error compensation avoids walking through subnormal
				// or cancellation-scale ULPs of a nearly zero projected component.
				best[pivot] = (-others + gamma(6) * magnitude) / normals[0][pivot];
				best[pivot] = Math.nextAfter(best[pivot], normals[0][pivot] > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
				if (dot(best, normals[0]) < 0) throw new MechanicalTimeRefinement(2, "Granular contact projection rounding");
			}
		}
		return best;
	}
	private static double[] boxNormal(double[] p, double[] lo, double[] hi) { double[] n = new double[3]; for (int a = 0; a < 3; a++) n[a] = p[a] - Math.max(lo[a], Math.min(hi[a], p[a])); return n; }
	private static boolean feasible(double[] v, double[][] normals, double[] formation) {
		for (double[] normal : normals) {
			double sum = 0, formed = 0; for (int a = 0; a < 3; a++) { sum += Math.abs(v[a] * normal[a]); formed += Math.abs(normal[a]) * formation[a]; }
			if (dot(v, normal) < -gamma(3) * sum - gamma(6) * formed) return false;
		}
		return true;
	}
	private static double[] solve(double[][] matrix) {
		int n = matrix.length;
		for (int j = 0; j < n; j++) {
			int pivot = j; for (int i = j + 1; i < n; i++) if (Math.abs(matrix[i][j]) > Math.abs(matrix[pivot][j])) pivot = i;
			if (matrix[pivot][j] == 0) return null; double[] swap = matrix[j]; matrix[j] = matrix[pivot]; matrix[pivot] = swap;
			double scale = matrix[j][j]; for (int k = j; k <= n; k++) matrix[j][k] /= scale;
			for (int i = 0; i < n; i++) if (i != j) { double factor = matrix[i][j]; for (int k = j; k <= n; k++) matrix[i][k] -= factor * matrix[j][k]; }
		}
		double[] result = new double[n]; for (int j = 0; j < n; j++) result[j] = matrix[j][n]; return result;
	}
	private static double[] tangent(double[] v, double[][] normals) {
		double[] cross = null; double largest = 0;
		for (int a = 0; a < normals.length; a++) for (int b = a + 1; b < normals.length; b++) {
			double[] candidate = cross(normals[a], normals[b]); double squared = norm2(candidate); if (squared > largest) { largest = squared; cross = candidate; }
		}
		if (cross == null) { double[] result = v.clone(); double normal = dot(v, normals[0]); for (int a = 0; a < 3; a++) result[a] -= normal * normals[0][a]; return result; }
		double length = Math.sqrt(largest); for (int a = 0; a < 3; a++) cross[a] /= length;
		for (double[] normal : normals) { double sum = 0; for (int a = 0; a < 3; a++) sum += Math.abs(normal[a] * cross[a]); if (Math.abs(dot(normal, cross)) > gamma(3) * sum) return new double[3]; }
		double scale = dot(v, cross); for (int a = 0; a < 3; a++) cross[a] *= scale; return cross;
	}
	private float[] roundOutside(double[] point, float radius) {
		float[] nearest = {(float) point[0], (float) point[1], (float) point[2]};
		// Independent nearest-coordinate rounding minimizes the Euclidean distance.
		if (validEndpoint(nearest, radius)) return nearest;
		float[][] choices = new float[3][3];
		for (int a = 0; a < 3; a++) { float center = (float) point[a]; choices[a] = new float[]{Math.nextDown(center), center, Math.nextUp(center)}; }
		float[] best = null; double error = Double.POSITIVE_INFINITY;
		for (float x : choices[0]) for (float y : choices[1]) for (float z : choices[2]) {
			float[] candidate = {x, y, z}; double squared = 0;
			for (int a = 0; a < 3; a++) squared += (candidate[a] - point[a]) * (candidate[a] - point[a]);
			if (squared >= error || !validEndpoint(candidate, radius)) continue;
			best = candidate; error = squared;
		}
		if (best == null) throw new MechanicalTimeRefinement(2, "No representable granular sphere endpoint");
		return best;
	}
	private boolean validEndpoint(float[] p, float radius) {
		for (int a = 0; a < 3; a++) if (!(p[a] >= radius && p[a] <= sizes[a] * h - radius)) return false;
		if (geometry.sphereTouchesSolid(p[0], p[1], p[2], radius)) return false;
		return true;
	}
	private static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
	private static double norm2(double[] a) { return dot(a, a); }
	private static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
}
