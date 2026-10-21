package mmm.fluid;

import mmm.granular.GranularMechanics;
import mmm.granular.GranularLimits;
import mmm.granular.GranularParcelStore;
import mmm.granular.GranularRegistry;
import mmm.granular.ParticulateMaterial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;

/** Local predictive contact manifolds with a common finite-mass impulse solve. */
final class GranularActiveContacts {
	private static final int GEOMETRIC_PASSES = 8;
	private static final int LOCAL_CANDIDATES = 12;
	private static final int CONTACT_DIRECTIONS = 6;
	private record Constraint(GranularContactCompression.Row row, double minimum, double tolerance, double time) { }
	private final GranularParcelStore parcels;
	private final GranularSphereCollision fixed;
	private final double h;
	private final double[][] initial, predicted, velocity;
	private final double[] mass;
	private final float[] radius;
	private final ParticulateMaterial[] materials;
	private final double[][] lower, upper;
	private final int[] order, leftChild, rightChild, member, heapNode, contactParent;
	private final double[] heapTime, heapDistance;
	private final Constraint[] manifold = new Constraint[CONTACT_DIRECTIONS];
	private final ArrayList<ArrayList<Constraint>> fixedByParcel;
	private final ArrayList<Constraint> constraints = new ArrayList<>();
	private final HashSet<Long> pairs = new HashSet<>();
	private int operations, nodeCount, heapSize;
	private double maximumPositionCorrection, positionCorrectionMassY;

	GranularActiveContacts(GranularParcelStore parcels, GranularRegistry registry, GranularSphereCollision fixed,
	                      float h, float duration) {
		this.parcels = parcels; this.fixed = fixed; this.h = h;
		int count = parcels.count();
		int treeCapacity = 2 * count;
		lower = new double[3][treeCapacity]; upper = new double[3][treeCapacity];
		order = new int[count]; leftChild = new int[treeCapacity]; rightChild = new int[treeCapacity];
		member = new int[treeCapacity]; heapNode = new int[treeCapacity];
		contactParent = new int[count];
		heapTime = new double[treeCapacity]; heapDistance = new double[treeCapacity];
		fixedByParcel = new ArrayList<>(count);
		initial = new double[count][]; predicted = new double[count][]; velocity = new double[count][];
		mass = new double[count]; radius = new float[count]; materials = new ParticulateMaterial[count];
		for (int i = 0; i < count; i++) {
			contactParent[i] = i;
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
		for (int pass = 0; pass < GEOMETRIC_PASSES; pass++) {
			findContacts(points, pass);
			if (pass > 0 && positionsResolved(points)) break;
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
		double[][] outgoing = velocity.clone();
		for (var component : components(points)) {
			double[][] input = component.vectors(velocity);
			var localMaterials = new ParticulateMaterial[input.length];
			for (int i = 0; i < input.length; i++) localMaterials[i] = materials[component.members()[i]];
			var response = new GranularComponentResponse(h, () -> { })
					.respond(component.masses(), input, localMaterials, component.rows());
			if (!component.fixedContact()) retainRigidMotion(component, points, input, response.values());
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
	int contactComponent(int index) { return root(contactParent, index); }

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

	private record Component(int[] members, double[] masses, GranularContactCompression.Row[] rows, boolean fixedContact) {
		double[][] vectors(double[][] global) {
			double[][] result = new double[members.length][];
			for (int i = 0; i < members.length; i++) result[i] = global[members[i]];
			return result;
		}
	}

	private ArrayList<Component> components(double[][] points) {
		var groups = new java.util.TreeMap<Integer, ArrayList<Constraint>>();
		for (var constraint : constraints)
			groups.computeIfAbsent(root(contactParent, constraint.row().first()), ignored -> new ArrayList<>()).add(constraint);
		int[] local = new int[mass.length];
		for (int i = 0; i < mass.length; i++) local[root(contactParent, i)]++;
		var memberGroups = new java.util.TreeMap<Integer, ArrayList<Integer>>();
		for (int i = 0; i < mass.length; i++) {
			int component = root(contactParent, i);
			if (local[component] > 1 || groups.containsKey(component))
				memberGroups.computeIfAbsent(component, ignored -> new ArrayList<>()).add(i);
		}
		var result = new ArrayList<Component>();
		Arrays.fill(local, -1);
		for (var entry : memberGroups.entrySet()) {
			var group = groups.get(entry.getKey());
			var indices = entry.getValue();
			if (group == null && indices.size() == 1) continue;
			if (group == null) group = new ArrayList<>();
			int[] members = indices.stream().mapToInt(Integer::intValue).toArray();
			double[] weights = new double[members.length];
			var rows = new GranularContactCompression.Row[group.size()];
			boolean fixedContact = false;
			for (int i = 0; i < members.length; i++) {
				weights[i] = mass[members[i]];
				local[members[i]] = i;
			}
			for (int i = 0; i < rows.length; i++) {
				var constraint = group.get(i); var row = constraint.row();
				double[] normal = row.normal();
				if (row.second() < 0) fixedContact = true;
				else {
					double[] line = new double[3];
					double length = 0;
					for (int a = 0; a < 3; a++) {
						line[a] = points[row.second()][a] - points[row.first()][a];
						length = Math.hypot(length, line[a]);
					}
					if (length > 0) {
						for (int a = 0; a < 3; a++) line[a] /= length;
						normal = line;
					}
				}
				rows[i] = new GranularContactCompression.Row(local[row.first()], row.second() < 0 ? -1 : local[row.second()], normal);
			}
			result.add(new Component(members, weights, rows, fixedContact));
			for (int member : members) local[member] = -1;
		}
		return result;
	}

	/** Preserve free-component translation and spin; only the remaining deformation modes dissipate. */
	private void retainRigidMotion(Component component, double[][] points, double[][] input, double[][] output) {
		int count = component.members().length;
		double totalMass = 0;
		double[] center = new double[3], sourceCenter = new double[3], translation = new double[3];
		for (int i = 0; i < count; i++) {
			int global = component.members()[i];
			double m = component.masses()[i];
			totalMass += m;
			for (int a = 0; a < 3; a++) {
				center[a] += m * points[global][a];
				sourceCenter[a] += m * initial[global][a];
				translation[a] += m * input[i][a];
			}
		}
		for (int a = 0; a < 3; a++) {
			center[a] /= totalMass;
			sourceCenter[a] /= totalMass;
			translation[a] /= totalMass;
		}
		var inertia = new DMatrixRMaj(3, 3);
		double[][] relative = new double[count][3], deformation = new double[count][3];
		double[] sourceAngular = new double[3], currentAngular = new double[3];
		double energy = 0;
		for (int i = 0; i < count; i++) {
			int global = component.members()[i];
			double m = component.masses()[i], squaredRadius = 0;
			for (int a = 0; a < 3; a++) {
				relative[i][a] = points[global][a] - center[a];
				squaredRadius += relative[i][a] * relative[i][a];
				double velocity = input[i][a] - translation[a];
				energy += .5 * m * velocity * velocity;
				int b = (a + 1) % 3, c = (a + 2) % 3;
				sourceAngular[a] += m * ((initial[global][b] - sourceCenter[b]) * (input[i][c] - translation[c])
						- (initial[global][c] - sourceCenter[c]) * (input[i][b] - translation[b]));
			}
			for (int a = 0; a < 3; a++) {
				int b = (a + 1) % 3, c = (a + 2) % 3;
				currentAngular[a] += m * (relative[i][b] * (output[i][c] - translation[c])
						- relative[i][c] * (output[i][b] - translation[b]));
				for (int bAxis = 0; bAxis < 3; bAxis++)
					inertia.add(a, bAxis, m * ((a == bAxis ? squaredRadius : 0) - relative[i][a] * relative[i][bAxis]));
			}
		}
		// A point pair has no inertia about its own line. The ordinary 3x3
		// pseudoinverse retains precisely the rotational modes represented by its centers.
		var inverse = new DMatrixRMaj(3, 3);
		CommonOps_DDRM.pinv(inertia, inverse);
		double[] spin = new double[3], oldSpin = new double[3];
		for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
			spin[a] += inverse.get(a, b) * sourceAngular[b];
			oldSpin[a] += inverse.get(a, b) * currentAngular[b];
		}
		double rotationalEnergy = Math.max(0, .5 * dot(sourceAngular, spin));
		if (rotationalEnergy > energy) {
			// Collapsed unresolved geometry can carry only the spin supported by its actual kinetic budget.
			double scale = Math.sqrt(energy / rotationalEnergy);
			for (int a = 0; a < 3; a++) spin[a] *= scale;
			rotationalEnergy = energy;
		}
		double[] meanDeformation = new double[3];
		for (int i = 0; i < count; i++) for (int a = 0; a < 3; a++) {
			int b = (a + 1) % 3, c = (a + 2) % 3;
			deformation[i][a] = output[i][a] - translation[a]
					- (oldSpin[b] * relative[i][c] - oldSpin[c] * relative[i][b]);
			meanDeformation[a] += component.masses()[i] * deformation[i][a] / totalMass;
		}
		double deformationEnergy = 0;
		for (int i = 0; i < count; i++) for (int a = 0; a < 3; a++) {
			deformation[i][a] -= meanDeformation[a];
			deformationEnergy += .5 * component.masses()[i] * deformation[i][a] * deformation[i][a];
		}
		double scale = deformationEnergy > 0
				? Math.min(1, Math.sqrt(Math.max(0, energy - rotationalEnergy) / deformationEnergy)) : 1;
		for (int i = 0; i < count; i++) for (int a = 0; a < 3; a++) {
			int b = (a + 1) % 3, c = (a + 2) % 3;
			output[i][a] = translation[a] + spin[b] * relative[i][c] - spin[c] * relative[i][b]
					+ scale * deformation[i][a];
		}
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
		var constraint = new Constraint(new GranularContactCompression.Row(i, -1, normal), minimum, tolerance, 0);
		fixedContacts.add(constraint);
		constraints.add(constraint);
		return true;
	}

	private Constraint candidate(int i, int j, double[][] points) {
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
			if (!(a > 0) || b >= 0 || disc < 0) return null;
			time = c / (-b + Math.sqrt(disc));
			if (!(time >= 0 && time <= 1)) return null;
		}
		double[] normal = new double[3]; double length = 0;
		normal[0] = Math.fma(time, dx, rx); normal[1] = Math.fma(time, dy, ry); normal[2] = Math.fma(time, dz, rz);
		for (double value : normal) length = Math.hypot(length, value);
		if (length > 0) for (int a = 0; a < 3; a++) normal[a] /= length;
		else normal[(i + j) % 3] = 1;
		// Restitution is a velocity impulse. Position contact resolves the representative radii once.
		return new Constraint(new GranularContactCompression.Row(i, j, normal), sum, tolerance, time);
	}

	/** Each pass replaces the soft pair manifold; fixed shell and bed planes remain complete. */
	private void findContacts(double[][] points, int pass) {
		constraints.clear();
		pairs.clear();
		for (var fixedContacts : fixedByParcel) if (fixedContacts != null) constraints.addAll(fixedContacts);
		for (int i = 0; i < order.length; i++) order[i] = i;
		nodeCount = 0;
		if (order.length == 0) return;
		buildTree(0, order.length, points);
		int budget = (GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP - operations) / (GEOMETRIC_PASSES - pass);
		for (int visit = 0; visit < order.length; visit++) {
			int remaining = order.length - visit;
			int quota = Math.min(LOCAL_CANDIDATES, (budget + remaining - 1) / remaining);
			// Reverse visits between passes so a coarse work allocation does not favour one end of the tree.
			int i = order[(pass & 1) == 0 ? visit : order.length - 1 - visit];
			budget -= queryContacts(i, points, quota);
		}
	}

	private int buildTree(int first, int end, double[][] points) {
		int node = nodeCount++, axis = 0;
		double widest = -1;
		for (int a = 0; a < 3; a++) {
			double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
			for (int visit = first; visit < end; visit++) {
				int i = order[visit];
				double reach = radius[i] + GranularSpawnAdmission.positionTolerance(radius[i], radius[i]);
				lo = Math.min(lo, Math.min(initial[i][a], points[i][a]) - reach);
				hi = Math.max(hi, Math.max(initial[i][a], points[i][a]) + reach);
			}
			lower[a][node] = lo;
			upper[a][node] = hi;
			if (hi - lo > widest) {
				widest = hi - lo;
				axis = a;
			}
		}
		if (end - first == 1) {
			member[node] = order[first];
			return node;
		}
		member[node] = -1;
		int middle = (first + end) >>> 1;
		selectMedian(first, end - 1, middle, axis, points);
		leftChild[node] = buildTree(first, middle, points);
		rightChild[node] = buildTree(middle, end, points);
		return node;
	}

	private void selectMedian(int first, int last, int middle, int axis, double[][] points) {
		while (first < last) {
			int left = first, right = last, pivot = order[(first + last) >>> 1];
			while (left <= right) {
				while (compareCenter(order[left], pivot, axis, points) < 0) left++;
				while (compareCenter(order[right], pivot, axis, points) > 0) right--;
				if (left <= right) {
					int value = order[left];
					order[left++] = order[right];
					order[right--] = value;
				}
			}
			if (middle <= right) last = right;
			else if (middle >= left) first = left;
			else return;
		}
	}

	private int compareCenter(int first, int second, int axis, double[][] points) {
		int comparison = Double.compare(initial[first][axis] + points[first][axis],
				initial[second][axis] + points[second][axis]);
		return comparison == 0 ? Integer.compare(first, second) : comparison;
	}

	/** Nearest-time tree traversal bounds both candidate tests and branches in a dense contact cloud. */
	private int queryContacts(int i, double[][] points, int quota) {
		if (quota == 0) return 0;
		Arrays.fill(manifold, null);
		heapSize = 0;
		pushNode(0, i, points);
		int tested = 0, visited = 0;
		int depth = Integer.SIZE - Integer.numberOfLeadingZeros(order.length);
		int branches = 4 * quota + 2 * depth;
		while (heapSize > 0 && tested < quota && visited++ < branches) {
			int node = popNode(), j = member[node];
			if (j < 0) {
				pushNode(leftChild[node], i, points);
				pushNode(rightChild[node], i, points);
			} else if (j != i) {
				tested++;
				var contact = candidate(Math.min(i, j), Math.max(i, j), points);
				if (contact != null) retainContact(i, contact, points);
			}
		}
		operations += tested;
		for (var contact : manifold) if (contact != null) {
			var row = contact.row();
			long key = ((long) row.first() << 32) | row.second();
			if (pairs.add(key)) {
				constraints.add(contact);
				int first = root(contactParent, row.first()), second = root(contactParent, row.second());
				contactParent[Math.max(first, second)] = Math.min(first, second);
			}
		}
		return tested;
	}

	private void retainContact(int i, Constraint candidate, double[][] points) {
		var row = candidate.row();
		int axis = 0;
		for (int a = 1; a < 3; a++) if (Math.abs(row.normal()[a]) > Math.abs(row.normal()[axis])) axis = a;
		double orientation = i == row.first() ? row.normal()[axis] : -row.normal()[axis];
		int direction = 2 * axis + (orientation < 0 ? 0 : 1);
		var previous = manifold[direction];
		if (previous == null || candidate.time() < previous.time()
				|| (candidate.time() == previous.time() && relativeGap(candidate, points) < relativeGap(previous, points))) {
			manifold[direction] = candidate;
		}
	}

	private static double relativeGap(Constraint contact, double[][] points) {
		var row = contact.row();
		double gap = 0;
		for (int a = 0; a < 3; a++) gap = Math.fma(row.normal()[a], points[row.second()][a] - points[row.first()][a], gap);
		return gap / contact.minimum();
	}

	private void pushNode(int node, int i, double[][] points) {
		if (member[node] == i) return;
		double entry = 0, exit = 1, distance = 0;
		double reach = radius[i] + GranularSpawnAdmission.positionTolerance(radius[i], radius[i]);
		for (int a = 0; a < 3; a++) {
			double position = initial[i][a], motion = points[i][a] - position;
			double lo = lower[a][node] - reach, hi = upper[a][node] + reach;
			if (motion == 0) {
				if (position < lo || position > hi) return;
			} else {
				double first = (lo - position) / motion, last = (hi - position) / motion;
				entry = Math.max(entry, Math.min(first, last));
				exit = Math.min(exit, Math.max(first, last));
				if (entry > exit) return;
			}
		}
		for (int a = 0; a < 3; a++) {
			double motion = points[i][a] - initial[i][a];
			double atEntry = Math.fma(entry, motion, initial[i][a]);
			double separation = atEntry - .5 * (lower[a][node] + upper[a][node]);
			distance = Math.fma(separation, separation, distance);
		}
		int index = heapSize++;
		while (index > 0) {
			int parent = (index - 1) >>> 1;
			if (!before(entry, distance, node, heapTime[parent], heapDistance[parent], heapNode[parent])) break;
			heapNode[index] = heapNode[parent];
			heapTime[index] = heapTime[parent];
			heapDistance[index] = heapDistance[parent];
			index = parent;
		}
		heapNode[index] = node;
		heapTime[index] = entry;
		heapDistance[index] = distance;
	}

	private int popNode() {
		int result = heapNode[0], last = --heapSize;
		int node = heapNode[last];
		double time = heapTime[last], distance = heapDistance[last];
		int index = 0;
		while (2 * index + 1 < heapSize) {
			int child = 2 * index + 1;
			if (child + 1 < heapSize && before(heapTime[child + 1], heapDistance[child + 1], heapNode[child + 1],
					heapTime[child], heapDistance[child], heapNode[child])) child++;
			if (!before(heapTime[child], heapDistance[child], heapNode[child], time, distance, node)) break;
			heapNode[index] = heapNode[child];
			heapTime[index] = heapTime[child];
			heapDistance[index] = heapDistance[child];
			index = child;
		}
		if (heapSize > 0) {
			heapNode[index] = node;
			heapTime[index] = time;
			heapDistance[index] = distance;
		}
		return result;
	}

	private static boolean before(double time, double distance, int node, double otherTime, double otherDistance, int otherNode) {
		return time < otherTime || time == otherTime && (distance < otherDistance || distance == otherDistance && node < otherNode);
	}

	private static double dot(double[] a, double[] b) { return Math.fma(a[0], b[0], Math.fma(a[1], b[1], a[2] * b[2])); }
}
