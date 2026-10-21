package mmm.fluid;

import mmm.granular.GranularMechanics;
import mmm.granular.GranularLimits;
import mmm.granular.GranularParcelStore;
import mmm.granular.GranularRegistry;
import mmm.granular.ParticulateMaterial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

/** Predictive sphere contacts with bounded geometric passes and a common finite-mass impulse solve. */
final class GranularActiveContacts {
	private record Constraint(GranularContactCompression.Row row, double minimum, double tolerance) { }
	private final GranularParcelStore parcels;
	private final GranularSphereCollision fixed;
	private final double h;
	private final double[][] initial, predicted, velocity;
	private final double[] mass;
	private final float[] radius;
	private final ParticulateMaterial[] materials;
	private final double[][] lower, upper;
	private final int[] sweepOrder;
	private final ArrayList<ArrayList<Constraint>> fixedByParcel;
	private final ArrayList<Constraint> constraints = new ArrayList<>();
	private final HashSet<Long> pairs = new HashSet<>();
	private final HashSet<Long> discovered = new HashSet<>();
	private int operations;
	private String operationStage = "prediction";
	private double maximumPositionCorrection, positionCorrectionMassY;

	GranularActiveContacts(GranularParcelStore parcels, GranularRegistry registry, GranularSphereCollision fixed,
	                      float h, float duration) {
		this.parcels = parcels; this.fixed = fixed; this.h = h;
		int count = parcels.count();
		lower = new double[3][count]; upper = new double[3][count]; sweepOrder = new int[count];
		fixedByParcel = new ArrayList<>(count);
		initial = new double[count][]; predicted = new double[count][]; velocity = new double[count][];
		mass = new double[count]; radius = new float[count]; materials = new ParticulateMaterial[count];
		for (int i = 0; i < count; i++) {
			fixedByParcel.add(null);
			var p = parcels.parcel(i);
			initial[i] = new double[]{p.x(), p.y(), p.z()}; mass[i] = p.mass();
			radius[i] = GranularMechanics.representativeRadius(p.solidVolumeSvq(), h);
			materials[i] = registry.material(p.materialHandle());
			var motion = fixed.preview(new float[]{p.x(), p.y(), p.z()},
					new float[]{p.velocityX(), p.velocityY(), p.velocityZ()}, radius[i], duration,
					materials[i], GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP - operations);
			operations += motion.operations();
			predicted[i] = new double[]{motion.x(), motion.y(), motion.z()};
			velocity[i] = new double[]{motion.vx(), motion.vy(), motion.vz()};
			addFixedPath(i);
			addFixed(i, predicted[i]);
		}
	}

	void preview(float[] x, float[] y, float[] z, float[] vx, float[] vy, float[] vz, boolean[] supported) {
		double[][] source = predicted;
		double[][] points = Arrays.stream(source).map(double[]::clone).toArray(double[][]::new);
		// New constraints are discovered geometrically, with no collision event queue or repeated zero-time events.
		for (int pass = 0; pass < 8; pass++) {
			int before = constraints.size();
			operationStage = "swept broad phase";
			findContacts(points);
			if (before == constraints.size() && pass > 0 && positionsResolved(points)) break;
			operationStage = "position projection";
			// Local mass-weighted PBD shares the contact rows with the velocity response.
			// Four sweeps per geometric pass replace a complete component solve and CCD replay.
			for (int sweep = 0; sweep < 4; sweep++) {
				for (int visit = 0; visit < constraints.size(); visit++) {
					int k = ((pass + sweep) & 1) == 0 ? visit : constraints.size() - 1 - visit;
					projectPosition(constraints.get(k), points);
				}
			}
			for (int i = 0; i < points.length; i++) {
				for (var plane : fixed.projectPosition(points[i], radius[i]))
					addFixedPlane(i, plane.normal(), plane.minimum());
			}
		}
		operationStage = "velocity response";
		double[][] outgoing = velocity.clone();
		for (var component : components()) {
			double[][] input = component.vectors(velocity);
			var localMaterials = new ParticulateMaterial[input.length];
			for (int i = 0; i < input.length; i++) localMaterials[i] = materials[component.members()[i]];
			var response = new GranularComponentResponse(h, () -> { })
					.respond(component.masses(), input, localMaterials, component.rows());
			for (int i = 0; i < input.length; i++) outgoing[component.members()[i]] = response.values()[i];
		}
		for (int i = 0; i < points.length; i++) {
			float[] p = fixed.roundContactPoint(points[i], radius[i]);
			x[i] = p[0]; y[i] = p[1]; z[i] = p[2];
			vx[i] = (float) outgoing[i][0]; vy[i] = (float) outgoing[i][1]; vz[i] = (float) outgoing[i][2];
			double correction = 0;
			for (int a = 0; a < 3; a++) correction = Math.hypot(correction, p[a] - source[i][a]);
			maximumPositionCorrection = Math.max(maximumPositionCorrection, correction);
			positionCorrectionMassY += mass[i] * (p[1] - source[i][1]);
			supported[i] = fixed.supportsAtRest(x[i], y[i], z[i], radius[i]);
		}
		// Every actual upward edge is visited once when support reaches its lower representative.
		int[] head = new int[mass.length]; Arrays.fill(head, -1);
		int[] upper = new int[constraints.size()], next = new int[constraints.size()]; int edges = 0;
		for (var constraint : constraints) {
			var row = constraint.row(); int i = row.first(), j = row.second();
			if (j < 0 || y[i] == y[j]) continue;
			double distance = Math.hypot(Math.hypot((double) x[i] - x[j], (double) y[i] - y[j]), (double) z[i] - z[j]);
			if (distance > radius[i] + (double) radius[j] + 2 * GranularSpawnAdmission.positionTolerance(radius[i], radius[j])) continue;
			int low = y[i] < y[j] ? i : j;
			upper[edges] = low == i ? j : i; next[edges] = head[low]; head[low] = edges++;
		}
		int[] queue = new int[mass.length]; int count = 0;
		for (int i = 0; i < mass.length; i++) if (supported[i]) queue[count++] = i;
		for (int visit = 0; visit < count; visit++) for (int edge = head[queue[visit]]; edge >= 0; edge = next[edge]) {
			int i = upper[edge];
			if (!supported[i]) { supported[i] = true; queue[count++] = i; }
		}
	}

	double maximumPositionCorrection() { return maximumPositionCorrection; }
	double positionCorrectionMassY() { return positionCorrectionMassY; }

	private void projectPosition(Constraint constraint, double[][] points) {
		var row = constraint.row();
		int i = row.first(), j = row.second();
		double gap = 0, norm = 0;
		for (int a = 0; a < 3; a++) {
			double n = row.normal()[a];
			gap = Math.fma(n, j < 0 ? points[i][a] : points[j][a] - points[i][a], gap);
			norm = Math.fma(n, n, norm);
		}
		double correction = constraint.minimum() - (j < 0 ? 0 : .5 * constraint.tolerance()) - gap;
		if (correction <= 0) return;
		double inverse = 1 / mass[i] + (j < 0 ? 0 : 1 / mass[j]);
		double impulse = correction / (norm * inverse);
		for (int a = 0; a < 3; a++) {
			double move = impulse * row.normal()[a];
			points[i][a] += (j < 0 ? move : -move) / mass[i];
			if (j >= 0) points[j][a] += move / mass[j];
		}
	}

	private boolean positionsResolved(double[][] points) {
		for (var constraint : constraints) {
			var row = constraint.row(); double gap = 0;
			for (int axis=0;axis<3;axis++) gap=Math.fma(row.normal()[axis],row.second()<0
					? points[row.first()][axis] : points[row.second()][axis]-points[row.first()][axis],gap);
			if(gap<constraint.minimum()-constraint.tolerance())return false;
		}
		return true;
	}

	private record Component(int[] members, double[] masses, GranularContactCompression.Row[] rows) {
		double[][] vectors(double[][] global) {
			double[][] result = new double[members.length][];
			for (int i = 0; i < members.length; i++) result[i] = global[members[i]];
			return result;
		}
	}

	private ArrayList<Component> components() {
		int[] parent = new int[mass.length];
		for (int i = 0; i < parent.length; i++) parent[i] = i;
		for (var constraint : constraints) {
			var row = constraint.row();
			if (row.second() >= 0) parent[root(parent, row.first())] = root(parent, row.second());
		}
		var groups = new java.util.TreeMap<Integer, ArrayList<Constraint>>();
		for (var constraint : constraints) groups.computeIfAbsent(root(parent, constraint.row().first()), ignored -> new ArrayList<>()).add(constraint);
		var result = new ArrayList<Component>();
		int[] local = new int[mass.length]; Arrays.fill(local, -1);
		for (var group : groups.values()) {
			var indices = new ArrayList<Integer>();
			for (var constraint : group) {
				var row = constraint.row();
				if (local[row.first()] < 0) { local[row.first()] = indices.size(); indices.add(row.first()); }
				if (row.second() >= 0 && local[row.second()] < 0) { local[row.second()] = indices.size(); indices.add(row.second()); }
			}
			int[] members = indices.stream().mapToInt(Integer::intValue).toArray();
			double[] weights = new double[members.length];
			var rows = new GranularContactCompression.Row[group.size()];
			for (int i = 0; i < members.length; i++) weights[i] = mass[members[i]];
			for (int i = 0; i < rows.length; i++) {
				var constraint = group.get(i); var row = constraint.row();
				rows[i] = new GranularContactCompression.Row(local[row.first()], row.second() < 0 ? -1 : local[row.second()], row.normal());
			}
			result.add(new Component(members, weights, rows));
			for (int member : members) local[member] = -1;
		}
		return result;
	}
	private static int root(int[] parent, int i) {
		while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
		return i;
	}

	private boolean addFixed(int i, double[] at) {
		boolean changed = false;
		for (double[] normal : fixed.contactNormals(at, radius[i])) {
			changed |= addFixedPlane(i, normal, dot(normal, at));
		}
		return changed;
	}
	private boolean addFixedPath(int i) {
		boolean changed = false;
		for (var plane : fixed.lastPathPlanes()) changed |= addFixedPlane(i, plane.normal(), plane.minimum());
		return changed;
	}
	private boolean addFixedPlane(int i, double[] normal, double minimum) {
		double tolerance=GranularSpawnAdmission.positionTolerance(radius[i],radius[i]);
		var fixedContacts = fixedByParcel.get(i);
		if (fixedContacts == null) {
			fixedContacts = new ArrayList<>();
			fixedByParcel.set(i, fixedContacts);
		}
		for (var old : fixedContacts) if (Arrays.equals(old.row().normal(), normal)
				&& Math.abs(old.minimum() - minimum) <= tolerance) return false;
		var constraint = new Constraint(new GranularContactCompression.Row(i, -1, normal), minimum, tolerance);
		fixedContacts.add(constraint);
		constraints.add(constraint);
		return true;
	}

	private void addPair(int i, int j, double[][] points) {
		long key = ((long) i << 32) | j;
		if (pairs.contains(key)) return;
		// Repeated PBD scans of the same spatial pair are bounded arithmetic work.
		// Only discovering another spatial relation spends the geometric work budget.
		if (discovered.add(key)) spend();
		double rx = initial[j][0] - initial[i][0], ry = initial[j][1] - initial[i][1], rz = initial[j][2] - initial[i][2];
		double dx = (points[j][0] - initial[j][0]) - (points[i][0] - initial[i][0]);
		double dy = (points[j][1] - initial[j][1]) - (points[i][1] - initial[i][1]);
		double dz = (points[j][2] - initial[j][2]) - (points[i][2] - initial[i][2]);
		double sum = radius[i] + (double) radius[j], tolerance = GranularSpawnAdmission.positionTolerance(radius[i], radius[j]);
		double r2 = Math.fma(rx, rx, Math.fma(ry, ry, rz * rz));
		double c = r2 - sum * sum, time;
		if (Math.sqrt(r2) <= sum + tolerance) time = 0;
		else {
			double a = Math.fma(dx, dx, Math.fma(dy, dy, dz * dz));
			double b = Math.fma(rx, dx, Math.fma(ry, dy, rz * dz)), disc = Math.fma(-a, c, b * b);
			if (!(a > 0) || b >= 0 || disc < 0) return;
			time = c / (-b + Math.sqrt(disc));
			if (!(time >= 0 && time <= 1)) return;
		}
		double[] normal = new double[3]; double length = 0;
		normal[0] = Math.fma(time, dx, rx); normal[1] = Math.fma(time, dy, ry); normal[2] = Math.fma(time, dz, rz);
		for (double value : normal) length = Math.hypot(length, value);
		if (length > 0) for (int a = 0; a < 3; a++) normal[a] /= length;
		else normal[(i + j) % 3] = 1;
		// Restitution is a velocity impulse. Position contact resolves the representative radii once.
		constraints.add(new Constraint(new GranularContactCompression.Row(i, j, normal), sum, tolerance));
		pairs.add(key);
	}

	/** Tight swept boxes keep the candidate scale tied to representative size and actual motion. */
	private void findContacts(double[][] points) {
		int axis = 0;
		double widest = -1;
		for (int a = 0; a < 3; a++) {
			double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
			for (int i = 0; i < points.length; i++) {
				double reach = radius[i] + GranularSpawnAdmission.positionTolerance(radius[i], radius[i]);
				lower[a][i] = Math.min(initial[i][a], points[i][a]) - reach;
				upper[a][i] = Math.max(initial[i][a], points[i][a]) + reach;
				minimum = Math.min(minimum, lower[a][i]);
				maximum = Math.max(maximum, lower[a][i]);
			}
			if (maximum - minimum > widest) {
				widest = maximum - minimum;
				axis = a;
			}
		}
		for (int i = 0; i < sweepOrder.length; i++) sweepOrder[i] = i;
		sortSweep(0, sweepOrder.length - 1, axis);
		int b = (axis + 1) % 3, c = (axis + 2) % 3;
		for (int visit = 0; visit < sweepOrder.length; visit++) {
			int i = sweepOrder[visit];
			for (int next = visit + 1; next < sweepOrder.length; next++) {
				int j = sweepOrder[next];
				if (lower[axis][j] > upper[axis][i]) break;
				if (lower[b][j] > upper[b][i] || upper[b][j] < lower[b][i]
						|| lower[c][j] > upper[c][i] || upper[c][j] < lower[c][i]) continue;
				addPair(Math.min(i, j), Math.max(i, j), points);
			}
		}
	}

	private void sortSweep(int first, int last, int axis) {
		if (first >= last) return;
		int left = first, right = last, pivot = sweepOrder[(first + last) >>> 1];
		while (left <= right) {
			while (compareSweep(sweepOrder[left], pivot, axis) < 0) left++;
			while (compareSweep(sweepOrder[right], pivot, axis) > 0) right--;
			if (left <= right) {
				int value = sweepOrder[left];
				sweepOrder[left++] = sweepOrder[right];
				sweepOrder[right--] = value;
			}
		}
		if (first < right) sortSweep(first, right, axis);
		if (left < last) sortSweep(left, last, axis);
	}

	private int compareSweep(int first, int second, int axis) {
		int comparison = Double.compare(lower[axis][first], lower[axis][second]);
		return comparison == 0 ? Integer.compare(first, second) : comparison;
	}
	private static double dot(double[] a, double[] b) { return Math.fma(a[0], b[0], Math.fma(a[1], b[1], a[2] * b[2])); }
	private void spend() {
		if (++operations > GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP)
			throw new IllegalStateException("Predictive granular contact operations: stage=" + operationStage
					+ ", particles=" + mass.length + ", constraints=" + constraints.size());
	}
}
