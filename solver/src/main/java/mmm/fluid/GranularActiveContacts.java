package mmm.fluid;

import mmm.granular.GranularMechanics;
import mmm.granular.GranularLimits;
import mmm.granular.GranularParcelStore;
import mmm.granular.GranularRegistry;
import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.PriorityQueue;

/** Finite-mass sphere contacts on one common drift timeline, before carrier publication. */
final class GranularActiveContacts {
	private record Event(double time, int first, int second, int firstVersion, int secondVersion, double[][] normals) { }
	private final GranularParcelStore parcels;
	private final GranularRegistry registry;
	private final GranularSphereCollision fixed;
	private final double duration;
	private final int sizeX, sizeY, sizeZ;
	private final double h;
	private final double[][] position, trajectoryOrigin, drift, velocity;
	private final double[][] driftError, velocityError;
	private final double[] updated, mass, distanceLimit;
	private final float[] radius;
	private final int[] versions;
	private final BitSet[] sweptCells;
	private final int[][] registrations;
	private final double[][] sweptMinimum, sweptMaximum;
	private final BitSet candidates = new BitSet();
	private final PriorityQueue<Event> events = new PriorityQueue<>(Comparator.comparingDouble(Event::time)
			.thenComparingInt(Event::first).thenComparingInt(Event::second));
	private int operations;
	private int processedEvents, zeroTimeEvents, pairSchedules, fixedSchedules;
	private int lastFirst = -1, lastSecond = -1;
	private double eventTime;

	GranularActiveContacts(GranularParcelStore parcels, GranularRegistry registry, GranularSphereCollision fixed,
	                      int sizeX, int sizeY, int sizeZ, float h, float duration, float courant) {
		this.parcels = parcels; this.registry = registry; this.fixed = fixed;
		if (!(Float.isFinite(courant) && courant > 0)) throw new IllegalArgumentException("Invalid active contact CFL limit");
		this.duration = duration; this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; this.h = h;
		int count = parcels.count(); position = new double[count][3]; trajectoryOrigin = new double[count][3]; drift = new double[count][3]; velocity = new double[count][3];
		updated = new double[count]; mass = new double[count]; distanceLimit = new double[count]; radius = new float[count]; versions = new int[count];
		driftError = new double[count][3]; velocityError = new double[count][3];
		sweptCells = new BitSet[sizeX * sizeY * sizeZ]; registrations = new int[count][];
		sweptMinimum = new double[count][3]; sweptMaximum = new double[count][3];
		var admission = new GranularSpawnAdmission(sizeX, sizeY, sizeZ, h, count);
		for (int i = 0; i < count; i++) {
			var p = parcels.parcel(i); position[i] = new double[]{p.x(), p.y(), p.z()};
			trajectoryOrigin[i] = position[i].clone();
			velocity[i] = new double[]{p.velocityX(), p.velocityY(), p.velocityZ()};
			double speed = Math.abs((double) p.velocityX()) + Math.abs((double) p.velocityY()) + Math.abs((double) p.velocityZ());
			distanceLimit[i] = h * (double) courant * (1 + 8 * Math.ulp(1.0F));
			if (speed * duration > distanceLimit[i]) throw new MechanicalTimeRefinement(2, "Initial granular contact drift exceeds CFL support");
			for (int axis = 0; axis < 3; axis++) {
				float endpoint = (float) (position[i][axis] + velocity[i][axis] * duration);
				drift[i][axis] = (endpoint - position[i][axis]) / duration;
				distanceLimit[i] += .5 * Math.ulp(endpoint);
			}
			mass[i] = p.mass(); radius[i] = GranularMechanics.representativeRadius(p.solidVolumeSvq(), h);
			int overlapping = admission.firstOverlap(p.x(), p.y(), p.z(), p.solidVolumeSvq(), this::spend);
			if (overlapping >= 0) throw new IllegalStateException("Active granular contact starts with overlapping spheres " + overlapping + "," + i);
			admission.append(p.x(), p.y(), p.z(), p.solidVolumeSvq());
		}
		for (int i = 0; i < count; i++) registerSweep(i, 0);
	}

	void preview(float[] x, float[] y, float[] z, float[] vx, float[] vy, float[] vz, boolean[] supported) {
		for (int i = 0; i < parcels.count(); i++) {
			scheduleFixed(i, 0);
			sweepCandidates(i);
			for (int j = candidates.nextSetBit(0); j >= 0 && j < i; j = candidates.nextSetBit(j + 1)) {
				spend(); if (sweepsIntersect(i, j)) schedulePair(j, i, 0);
			}
		}
		while (!events.isEmpty()) {
			var event = events.remove(); int first = event.first(), second = event.second();
			if (event.firstVersion() != versions[first] || second >= 0 && event.secondVersion() != versions[second]) continue;
			spend(); processedEvents++; if (event.time() == eventTime) zeroTimeEvents++;
			eventTime = event.time(); lastFirst = first; lastSecond = second;
			advance(first, event.time()); if (second >= 0) advance(second, event.time());
			// A new drift starts at this contact; it cannot be applied to the already travelled segment.
			trajectoryOrigin[first] = position[first].clone(); updated[first] = event.time();
			if (second >= 0) { trajectoryOrigin[second] = position[second].clone(); updated[second] = event.time(); }
			double[] fixedImpact = new double[3];
			if (second < 0) {
				double[] compression = fixed.compress(drift[first], event.normals());
				double[] response = fixed.respondNormal(velocity[first], event.normals(), registry.material(parcels.parcel(first).materialHandle()));
				for (int axis = 0; axis < 3; axis++) fixedImpact[axis] = response[axis] - velocity[first][axis];
				drift[first] = compression; velocity[first] = response;
			} else {
				double[] normal = event.normals()[0];
				double restitution = Math.min(registry.material(parcels.parcel(first).materialHandle()).restitution(),
						registry.material(parcels.parcel(second).materialHandle()).restitution());
				compressPair(first, second, normal, drift, restitution);
				compressPair(first, second, normal, velocity, restitution);
			}
			BitSet projected = compressComponent(event, fixedImpact);
			// Every changed trajectory in the component is visible before any new pair query.
			for (int i = projected.nextSetBit(0); i >= 0; i = projected.nextSetBit(i + 1)) {
				versions[i]++; registerSweep(i, event.time());
			}
			for (int i = projected.nextSetBit(0); i >= 0; i = projected.nextSetBit(i + 1)) reschedule(i, event.time(), projected);
		}
		for (int i = 0; i < parcels.count(); i++) advance(i, duration);
		var endpoints = new GranularSpawnAdmission(sizeX, sizeY, sizeZ, (float) h, parcels.count());
		int[] heads = new int[sweptCells.length], next = new int[parcels.count()], supportQueue = new int[parcels.count()];
		Arrays.fill(heads, -1); int supportCount = 0;
		for (int i = 0; i < parcels.count(); i++) {
			float[] rounded = rounded(i, endpoints);
			x[i] = rounded[0]; y[i] = rounded[1]; z[i] = rounded[2];
			endpoints.append(x[i], y[i], z[i], parcels.parcel(i).solidVolumeSvq());
			int cell = centerCell(x[i], y[i], z[i]); next[i] = heads[cell]; heads[cell] = i;
			vx[i] = (float) velocity[i][0]; vy[i] = (float) velocity[i][1]; vz[i] = (float) velocity[i][2];
			if (!Float.isFinite(vx[i]) || !Float.isFinite(vy[i]) || !Float.isFinite(vz[i]))
				throw new IllegalStateException("Unrepresentable active contact velocity");
			supported[i] = fixed.supportsAtRest(x[i], y[i], z[i], radius[i]);
			if (supported[i]) supportQueue[supportCount++] = i;
		}
		// Only a real contact chain connected to fixed support can carry a resting upper parcel.
		for (int visit = 0; visit < supportCount; visit++) {
			int j = supportQueue[visit]; pointCandidates(x[j], y[j], z[j], heads, next);
			for (int i = candidates.nextSetBit(0); i >= 0; i = candidates.nextSetBit(i + 1)) {
				spend();
				if (!supported[i] && y[i] > y[j] && touching(x[i], y[i], z[i], radius[i], x[j], y[j], z[j], radius[j])) {
					supported[i] = true; supportQueue[supportCount++] = i;
				}
			}
		}
	}

	private BitSet compressComponent(Event event, double[] fixedImpact) {
		var members = new ArrayList<Integer>(); var rows = new ArrayList<GranularContactCompression.Row>();
		int[] local = new int[parcels.count()]; Arrays.fill(local, -1);
		local[event.first()] = 0; members.add(event.first());
		if (event.second() >= 0) {
			local[event.second()] = 1; members.add(event.second());
			rows.add(new GranularContactCompression.Row(0, 1, event.normals()[0]));
		} else for (var normal : event.normals()) rows.add(new GranularContactCompression.Row(0, -1, normal));
		for (int visit = 0; visit < members.size(); visit++) {
			int i = members.get(visit); advance(i, event.time()); spend();
			if (event.second() >= 0 || i != event.first())
				for (var normal : fixed.contactNormals(position[i], radius[i])) rows.add(new GranularContactCompression.Row(local[i], -1, normal));
			sweepCandidates(i);
			for (int j = candidates.nextSetBit(0); j >= 0; j = candidates.nextSetBit(j + 1)) if (j != i) {
				if (local[j] >= 0 && local[j] < visit || event.second() >= 0
						&& (i == event.first() && j == event.second() || i == event.second() && j == event.first())) continue;
				spend(); advance(j, event.time());
				if (!touching(position[i], radius[i], position[j], radius[j])) continue;
				if (local[j] < 0) { local[j] = members.size(); members.add(j); }
				double[] normal = new double[3]; double length = 0;
				for (int axis = 0; axis < 3; axis++) { normal[axis] = position[j][axis] - position[i][axis]; length = Math.hypot(length, normal[axis]); }
				if (!(length > 0)) throw new IllegalStateException("Granular contact component has coincident centers");
				for (int axis = 0; axis < 3; axis++) normal[axis] /= length;
				rows.add(new GranularContactCompression.Row(local[i], local[j], normal));
			}
		}
		double[] weights = new double[members.size()]; double[][] d = new double[members.size()][], v = new double[members.size()][];
		for (int i = 0; i < members.size(); i++) { int global = members.get(i); weights[i] = mass[global]; d[i] = drift[global]; v[i] = velocity[global]; }
		var constraints = rows.toArray(GranularContactCompression.Row[]::new);
		var compression = GranularContactCompression.solve(weights, d, constraints, this::spend);
		var response = Arrays.deepEquals(d, v) ? compression : GranularContactCompression.solve(weights, v, constraints, this::spend, compression.impulses());
		var fixedRows = new ArrayList<ArrayList<Integer>>(members.size());
		for (int i = 0; i < members.size(); i++) fixedRows.add(new ArrayList<>());
		for (int row = 0; row < constraints.length; row++) {
			spend(); if (constraints[row].second() < 0) fixedRows.get(constraints[row].first()).add(row);
		}
		var frictionInput = response.values().clone(); boolean frictionChanged = false;
		for (int i = 0; i < members.size(); i++) {
			var normals = new ArrayList<double[]>(); double[] load = new double[3];
			if (event.second() < 0 && members.get(i) == event.first()) load = fixedImpact.clone();
			for (int row : fixedRows.get(i)) {
				spend(); var normal = constraints[row].normal(); normals.add(normal);
				for (int axis = 0; axis < 3; axis++) load[axis] = Math.fma(response.impulses()[row] / weights[i], normal[axis], load[axis]);
			}
			if (normals.isEmpty()) continue;
			frictionInput[i] = fixed.friction(response.values()[i], normals.toArray(double[][]::new),
					Math.hypot(Math.hypot(load[0], load[1]), load[2]), registry.material(parcels.parcel(members.get(i)).materialHandle()));
			frictionChanged |= !Arrays.equals(frictionInput[i], response.values()[i]);
		}
		// Tangential wall impulses can close an oblique pair; recheck the same joint normal constraints.
		if (frictionChanged) response = GranularContactCompression.solve(weights, frictionInput, constraints, this::spend);
		BitSet projected = new BitSet(parcels.count());
		for (int i = 0; i < members.size(); i++) {
			int global = members.get(i);
			projected.set(global);
			drift[global] = compression.values()[i]; velocity[global] = response.values()[i];
			driftError[global] = compression.arithmeticError()[i]; velocityError[global] = response.arithmeticError()[i];
		}
		return projected;
	}

	private void reschedule(int i, double time, BitSet projected) {
		scheduleFixed(i, time); sweepCandidates(i);
		for (int j = candidates.nextSetBit(0); j >= 0; j = candidates.nextSetBit(j + 1)) if (j != i && (i < j || !projected.get(j))) {
			spend(); if (sweepsIntersect(i, j)) schedulePair(Math.min(i, j), Math.max(i, j), time);
		}
	}
	private void registerSweep(int i, double time) {
		var source = parcels.parcel(i); double distance = 0;
		double[] initial = {source.x(), source.y(), source.z()};
		for (int axis = 0; axis < 3; axis++) distance += Math.abs(position[i][axis] - initial[axis]) + Math.abs(drift[i][axis]) * (duration - time);
		if (distance > distanceLimit[i]) throw new MechanicalTimeRefinement(2, "Granular contact drift exceeds CFL support");
		trajectoryOrigin[i] = position[i].clone(); updated[i] = time;
		if (registrations[i] != null) for (int cell : registrations[i]) sweptCells[cell].clear(i);
		int[] lower = new int[3], upper = new int[3]; int[] sizes = {sizeX, sizeY, sizeZ};
		for (int axis = 0; axis < 3; axis++) {
			double endpoint = Math.fma(duration - time, drift[i][axis], position[i][axis]);
			sweptMinimum[i][axis] = Math.nextDown(Math.min(position[i][axis], Math.nextDown(endpoint)) - radius[i]);
			sweptMaximum[i][axis] = Math.nextUp(Math.max(position[i][axis], Math.nextUp(endpoint)) + radius[i]);
			lower[axis] = coordinate(Math.nextDown(sweptMinimum[i][axis] / h), sizes[axis]);
			upper[axis] = coordinate(Math.nextUp(sweptMaximum[i][axis] / h), sizes[axis]);
		}
		int[] cells = new int[(upper[0] - lower[0] + 1) * (upper[1] - lower[1] + 1) * (upper[2] - lower[2] + 1)];
		int count = 0;
		for (int z = lower[2]; z <= upper[2]; z++) for (int y = lower[1]; y <= upper[1]; y++) for (int x = lower[0]; x <= upper[0]; x++) {
			int cell = (z * sizeY + y) * sizeX + x; cells[count++] = cell;
			if (sweptCells[cell] == null) sweptCells[cell] = new BitSet(parcels.count());
			sweptCells[cell].set(i);
		}
		registrations[i] = cells;
	}
	private void sweepCandidates(int i) {
		candidates.clear(); for (int cell : registrations[i]) candidates.or(sweptCells[cell]);
	}
	private boolean sweepsIntersect(int i, int j) {
		for (int axis = 0; axis < 3; axis++)
			if (sweptMinimum[i][axis] > sweptMaximum[j][axis] || sweptMinimum[j][axis] > sweptMaximum[i][axis]) return false;
		return true;
	}
	private void pointCandidates(float x, float y, float z, int[] heads, int[] next) {
		candidates.clear(); int cell = centerCell(x, y, z), cx = cell % sizeX, cy = cell / sizeX % sizeY, cz = cell / (sizeX * sizeY);
		for (int iz = Math.max(0, cz - 1); iz <= Math.min(sizeZ - 1, cz + 1); iz++)
			for (int iy = Math.max(0, cy - 1); iy <= Math.min(sizeY - 1, cy + 1); iy++)
				for (int ix = Math.max(0, cx - 1); ix <= Math.min(sizeX - 1, cx + 1); ix++)
					for (int index = heads[(iz * sizeY + iy) * sizeX + ix]; index >= 0; index = next[index]) candidates.set(index);
	}
	private int centerCell(float x, float y, float z) {
		return (coordinate(z / h, sizeZ) * sizeY + coordinate(y / h, sizeY)) * sizeX + coordinate(x / h, sizeX);
	}
	private static int coordinate(double normalized, int size) {
		return Math.max(0, Math.min(size - 1, (int) Math.floor(normalized)));
	}
	private void scheduleFixed(int i, double time) {
		fixedSchedules++; spend(); advance(i, time);
		var contact = fixed.nextContact(position[i], drift[i], velocity[i], radius[i], duration - time, driftError[i], velocityError[i]);
		if (contact != null) events.add(new Event(time + contact.time(), i, -1, versions[i], -1, contact.normals()));
	}
	private void schedulePair(int i, int j, double time) {
		pairSchedules++; spend(); advance(i, time); advance(j, time);
		double[] r = new double[3], v = new double[3]; double a = 0, b = 0, c = 0;
		for (int axis = 0; axis < 3; axis++) {
			r[axis] = position[j][axis] - position[i][axis]; v[axis] = drift[j][axis] - drift[i][axis];
			a = Math.fma(v[axis], v[axis], a); b = Math.fma(r[axis], v[axis], b); c = Math.fma(r[axis], r[axis], c);
		}
		double sum = (double) radius[i] + radius[j], squares = c; c = Math.fma(-sum, sum, c);
		if (touching(position[i], radius[i], position[j], radius[j])) {
			double length = Math.hypot(Math.hypot(r[0], r[1]), r[2]);
			if (!(length > 0)) throw new IllegalStateException("Granular contact has coincident centers");
			double[] normal = {r[0] / length, r[1] / length, r[2] / length};
			if (closingPair(i, j, normal, drift, driftError) || closingPair(i, j, normal, velocity, velocityError))
				events.add(new Event(time, i, j, versions[i], versions[j], new double[][]{normal}));
			return;
		}
		if (!(a > 0) || b >= 0) return;
		double discriminant = Math.fma(-a, c, b * b);
		double error = 12 * 0x1.0p-53;
		if (Math.abs(discriminant) <= error / (1 - error) * (b * b + a * (squares + sum * sum))) {
			SignedDyadic exactA = exactDot(v, v), exactB = exactDot(r, v);
			SignedDyadic exactC = exactDot(r, r).subtract(SignedDyadic.of(sum).multiply(SignedDyadic.of(sum)));
			discriminant = exactB.multiply(exactB).subtract(exactA.multiply(exactC)).toDouble();
		}
		if (discriminant < 0) return;
		double elapsed = c <= 0 ? 0 : c / (-b + Math.sqrt(discriminant));
		if (elapsed > duration - time) return;
		double[] normal = new double[3]; double length = 0;
		for (int axis = 0; axis < 3; axis++) { normal[axis] = Math.fma(elapsed, v[axis], r[axis]); length = Math.hypot(length, normal[axis]); }
		if (!(length > 0)) throw new IllegalStateException("Granular contact has no separating normal");
		for (int axis = 0; axis < 3; axis++) normal[axis] /= length;
		events.add(new Event(time + elapsed, i, j, versions[i], versions[j], new double[][]{normal}));
	}
	private static boolean closingPair(int first, int second, double[] normal, double[][] values, double[][] error) {
		return !GranularContactCompression.admits(new GranularContactCompression.Row(first, second, normal), values, error);
	}
	private boolean compressPair(int i, int j, double[] normal, double[][] values, double restitution) {
		double closing = 0; for (int axis = 0; axis < 3; axis++) closing += normal[axis] * (values[j][axis] - values[i][axis]);
		if (closing >= 0) return false;
		double largest = Math.max(mass[i], mass[j]), firstWeight = mass[i] / largest, secondWeight = mass[j] / largest;
		double total = firstWeight + secondWeight, response = -(1 + restitution) * closing;
		boolean changed = false;
		double[] firstValues = values[i].clone(), secondValues = values[j].clone();
		for (int axis = 0; axis < 3; axis++) {
			double first = values[i][axis] - response * (secondWeight / total) * normal[axis];
			double second = values[j][axis] + response * (firstWeight / total) * normal[axis];
			changed |= (float) first != (float) values[i][axis] || (float) second != (float) values[j][axis];
			firstValues[axis] = first; secondValues[axis] = second;
		}
		if (changed) { values[i] = firstValues; values[j] = secondValues; }
		return changed;
	}
	private void advance(int i, double time) {
		// Queries of an unchanged trajectory must not accumulate position-rounding drift.
		for (int axis = 0; axis < 3; axis++) position[i][axis] = Math.fma(time - updated[i], drift[i][axis], trajectoryOrigin[i][axis]);
	}
	private float[] rounded(int i, GranularSpawnAdmission endpoints) {
		float[] nearest = fixed.roundContactPoint(position[i], radius[i]);
		for (int candidate = 0; candidate < 27; candidate++) {
			float[] point = nearest.clone(); int key = candidate;
			for (int axis = 0; axis < 3; axis++) { int side = key % 3; key /= 3; if (side == 1) point[axis] = Math.nextDown(point[axis]); else if (side == 2) point[axis] = Math.nextUp(point[axis]); }
			if (!Arrays.equals(point, fixed.roundContactPoint(new double[]{point[0], point[1], point[2]}, radius[i]))) continue;
			if (endpoints.allows(point[0], point[1], point[2], parcels.parcel(i).solidVolumeSvq())) return point;
		}
		throw new MechanicalTimeRefinement(2, "Joint granular contact endpoint lacks a binary32 placement: parcel=" + i
				+ ", position=" + Arrays.toString(position[i]) + ", nearest=" + Arrays.toString(nearest)
				+ ", radius=" + radius[i] + ", time=" + eventTime + "/" + duration);
	}
	private static SignedDyadic exactDot(double[] first, double[] second) {
		SignedDyadic value = SignedDyadic.ZERO;
		for (int axis = 0; axis < 3; axis++) value = value.add(SignedDyadic.of(first[axis]).multiply(SignedDyadic.of(second[axis])));
		return value;
	}
	private static boolean touching(float x, float y, float z, float r, float ox, float oy, float oz, float s) {
		double rounding = Math.hypot(Math.hypot((double) Math.ulp(x) + Math.ulp(ox), (double) Math.ulp(y) + Math.ulp(oy)), (double) Math.ulp(z) + Math.ulp(oz));
		return Math.hypot(Math.hypot((double) x - ox, (double) y - oy), (double) z - oz) <= (double) r + s + rounding;
	}
	private static boolean touching(double[] p, float r, double[] q, float s) {
		double lower = 0;
		for (int axis = 0; axis < 3; axis++) {
			double difference = Math.abs(p[axis] - q[axis]);
			double error = Math.ulp(p[axis]) + Math.ulp(q[axis]) + Math.ulp(difference);
			double distance = Math.max(0, Math.nextDown(difference - error));
			lower = Math.nextDown(lower + Math.nextDown(distance * distance));
		}
		double sum = (double) r + s;
		return lower <= Math.nextUp(sum * sum);
	}
	private void spend() {
		if (++operations > GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP)
			throw new MechanicalTimeRefinement(2, "Granular active contact operations: processedEvents=" + processedEvents
					+ ", zeroTimeEvents=" + zeroTimeEvents + ", time=" + eventTime + "/" + duration
					+ ", queued=" + events.size() + ", pairSchedules=" + pairSchedules + ", fixedSchedules=" + fixedSchedules
					+ ", lastPair=" + lastFirst + "/" + lastSecond);
	}
}
