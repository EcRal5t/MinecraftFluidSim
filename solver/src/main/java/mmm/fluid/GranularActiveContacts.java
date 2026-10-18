package mmm.fluid;

import mmm.granular.GranularMechanics;
import mmm.granular.GranularLimits;
import mmm.granular.GranularParcelStore;
import mmm.granular.GranularRegistry;
import mmm.granular.ParticulateMaterial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;

/** Predictive sphere contacts with bounded geometric passes and a common finite-mass impulse solve. */
final class GranularActiveContacts {
	private record Constraint(GranularContactCompression.Row row, double minimum, double tolerance) { }
	private final GranularParcelStore parcels;
	private final GranularSphereCollision fixed;
	private final int[] sizes;
	private final double duration, h, indexSize;
	private final double[][] initial, predicted, velocity;
	private final double[] mass;
	private final float[] radius;
	private final ParticulateMaterial[] materials;
	private final BitSet[] cells;
	private final int[][] registrations;
	private final BitSet candidates = new BitSet();
	private final ArrayList<Constraint> constraints = new ArrayList<>();
	private final HashSet<Long> pairs = new HashSet<>();
	private int operations;
	private String operationStage = "prediction";
	private double maximumPositionCorrection, positionCorrectionMassY;

	GranularActiveContacts(GranularParcelStore parcels, GranularRegistry registry, GranularSphereCollision fixed,
	                      int sizeX, int sizeY, int sizeZ, float h, float duration, float courant) {
		this.parcels = parcels; this.fixed = fixed; this.duration = duration; this.h = h;
		int divisions = FlipCutCellGeometry.MICRO_CELLS_PER_AXIS;
		int count = parcels.count();
		sizes = new int[]{sizeX * divisions, sizeY * divisions, sizeZ * divisions}; indexSize = h / (double) divisions;
		cells = new BitSet[Math.multiplyExact(Math.multiplyExact(sizes[0], sizes[1]), sizes[2])];
		registrations = new int[count][];
		initial = new double[count][3]; predicted = new double[count][3]; velocity = new double[count][3];
		mass = new double[count]; radius = new float[count]; materials = new ParticulateMaterial[count];
		for (int i = 0; i < count; i++) {
			var p = parcels.parcel(i);
			double distance = (Math.abs((double) p.velocityX()) + Math.abs(p.velocityY()) + Math.abs(p.velocityZ())) * duration;
			if (distance > h * courant * (1 + 8 * Math.ulp(1F)))
				throw new MechanicalTimeRefinement(2, "Initial granular prediction exceeds CFL support");
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
		double[][] source = Arrays.stream(predicted).map(double[]::clone).toArray(double[][]::new);
		double[][] points = source;
		boolean pendingFixed = false;
		// New constraints are discovered geometrically, with no collision event queue or repeated zero-time events.
		for (int pass = 0; pass < 8; pass++) {
			int before = constraints.size();
			index(points);
			for (int i = 0; i < parcels.count(); i++) {
				candidates.clear();
				for (int cell : registrations[i]) candidates.or(cells[cell]);
				for (int j = candidates.nextSetBit(0); j >= 0 && j < i; j = candidates.nextSetBit(j + 1)) {
					spend(); addPair(j, i, points);
				}
			}
			if (before == constraints.size() && pass > 0 && !pendingFixed) break;
			operationStage = "position projection";
			points = source.clone();
			for (var component : components()) {
				var input = component.vectors(source);
				var projected = GranularContactCompression.positions(component.masses(), input, component.rows(),
						component.minimum(), component.tolerance(), this::spend);
				for (int i = 0; i < component.members().length; i++) points[component.members()[i]] = projected[i];
			}
			boolean fixedChanged = false;
			for (int i = 0; i < points.length; i++) {
				if (points[i] == source[i]) continue;
				double[] old = points[i]; float[] v = new float[3];
				for (int a = 0; a < 3; a++) v[a] = (float) ((old[a] - initial[i][a]) / duration);
				var clipped = fixed.preview(new float[]{(float) initial[i][0], (float) initial[i][1], (float) initial[i][2]},
						v, radius[i], (float) duration, materials[i], GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP - operations);
				operations += clipped.operations();
				fixedChanged |= addFixedPath(i);
				double[] at = {clipped.x(), clipped.y(), clipped.z()};
				if ((float) at[0] != (float) old[0] || (float) at[1] != (float) old[1] || (float) at[2] != (float) old[2]) {
					fixedChanged |= addFixed(i, at);
					points[i] = at;
				}
			}
			pendingFixed = fixedChanged;
		}
		operationStage = "velocity response";
		double[][] outgoing = velocity.clone();
		for (var component : components()) {
			double[][] input = component.vectors(velocity);
			var localMaterials = new ParticulateMaterial[input.length];
			for (int i = 0; i < input.length; i++) localMaterials[i] = materials[component.members()[i]];
			var response = new GranularComponentResponse(h, fixed, this::spend)
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

	private record Component(int[] members, double[] masses, GranularContactCompression.Row[] rows,
	                         double[] minimum, double[] tolerance) {
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
			double[] weights = new double[members.length], minimum = new double[group.size()], tolerance = new double[group.size()];
			var rows = new GranularContactCompression.Row[group.size()];
			for (int i = 0; i < members.length; i++) weights[i] = mass[members[i]];
			for (int i = 0; i < rows.length; i++) {
				var constraint = group.get(i); var row = constraint.row();
				rows[i] = new GranularContactCompression.Row(local[row.first()], row.second() < 0 ? -1 : local[row.second()], row.normal());
				minimum[i] = constraint.minimum(); tolerance[i] = constraint.tolerance();
			}
			result.add(new Component(members, weights, rows, minimum, tolerance));
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
		boolean duplicate = false;
		for (var old : constraints) if (old.row().first() == i && old.row().second() < 0
				&& Arrays.equals(old.row().normal(), normal) && Math.abs(old.minimum() - minimum) <= Math.ulp(minimum) * 8) {
			duplicate = true; break;
		}
		if (!duplicate) {
			// A fixed surface must round to a legal stored pose. Leave a fraction of its coordinate quantum.
			constraints.add(new Constraint(new GranularContactCompression.Row(i, -1, normal), minimum,
					.125 * Math.ulp((float) Math.max(Math.abs(minimum), radius[i]))));
		}
		return !duplicate;
	}

	private void addPair(int i, int j, double[][] points) {
		long key = ((long) i << 32) | j;
		if (pairs.contains(key)) return;
		double[] r = new double[3], d = new double[3];
		for (int a = 0; a < 3; a++) {
			r[a] = initial[j][a] - initial[i][a];
			d[a] = (points[j][a] - initial[j][a]) - (points[i][a] - initial[i][a]);
		}
		double sum = radius[i] + (double) radius[j], tolerance = GranularSpawnAdmission.positionTolerance(radius[i], radius[j]);
		double c = dot(r, r) - sum * sum, time;
		if (Math.sqrt(dot(r, r)) <= sum + tolerance) time = 0;
		else {
			double a = dot(d, d), b = dot(r, d), disc = Math.fma(-a, c, b * b);
			if (!(a > 0) || b >= 0 || disc < 0) return;
			time = c / (-b + Math.sqrt(disc));
			if (!(time >= 0 && time <= 1)) return;
		}
		double[] normal = new double[3]; double length = 0;
		for (int a = 0; a < 3; a++) { normal[a] = Math.fma(time, d[a], r[a]); length = Math.hypot(length, normal[a]); }
		if (!(length > 0)) throw new IllegalStateException("Coincident predicted representative centers");
		for (int a = 0; a < 3; a++) normal[a] /= length;
		double incoming = 0;
		for (int a = 0; a < 3; a++) incoming += normal[a] * (velocity[j][a] - velocity[i][a]);
		double e = Math.min(materials[i].restitution(), materials[j].restitution());
		if (-e * incoming <= h * .125) e = 0;
		double opening = Math.max(0, -e * incoming * duration * (1 - time));
		constraints.add(new Constraint(new GranularContactCompression.Row(i, j, normal), sum + opening, tolerance * .25));
		pairs.add(key);
	}

	private void index(double[][] points) {
		Arrays.fill(cells, null);
		for (int i = 0; i < points.length; i++) {
			int[] lo = new int[3], hi = new int[3];
			for (int a = 0; a < 3; a++) {
				double reach = radius[i] + GranularSpawnAdmission.positionTolerance(radius[i], radius[i]);
				lo[a] = coordinate(Math.min(initial[i][a], points[i][a]) - reach, a);
				hi[a] = coordinate(Math.max(initial[i][a], points[i][a]) + reach, a);
			}
			int[] occupied = new int[(hi[0] - lo[0] + 1) * (hi[1] - lo[1] + 1) * (hi[2] - lo[2] + 1)];
			int n = 0;
			for (int z = lo[2]; z <= hi[2]; z++) for (int y = lo[1]; y <= hi[1]; y++) for (int x = lo[0]; x <= hi[0]; x++) {
				int cell = (z * sizes[1] + y) * sizes[0] + x; occupied[n++] = cell;
				if (cells[cell] == null) cells[cell] = new BitSet(points.length);
				cells[cell].set(i);
			}
			registrations[i] = occupied;
		}
	}
	private int coordinate(double p, int axis) { return Math.max(0, Math.min(sizes[axis] - 1, (int) Math.floor(p / indexSize))); }
	private static double dot(double[] a, double[] b) { return Math.fma(a[0], b[0], Math.fma(a[1], b[1], a[2] * b[2])); }
	private void spend() {
		if (++operations > GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP)
			throw new MechanicalTimeRefinement(2, "Predictive granular contact operations: stage=" + operationStage
					+ ", particles=" + mass.length + ", constraints=" + constraints.size());
	}
}
