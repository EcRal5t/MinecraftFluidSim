package mmm.fluid;

import mmm.granular.ParticulateMaterial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import static mmm.fluid.GranularContactCompression.*;

/** Common-source contact response and exact, preview-local reuse of its certified mathematics. */
final class GranularComponentResponse {
	record Response(Result drift, Result velocity, Result beforeFriction) { }
	private static final long MAX_WORDS = 1_048_576 / Long.BYTES;
	private final double h;
	private final GranularSphereCollision fixed;
	private final Runnable spend;
	private final LinkedHashMap<Key, Response> entries = new LinkedHashMap<>(16, .75F, true);
	private long words;

	GranularComponentResponse(double h, GranularSphereCollision fixed, Runnable spend) {
		this.h = h; this.fixed = fixed; this.spend = spend;
	}

	Response respond(double[] mass, double[][] d, double[][] v, double[][] driftError, double[][] velocityError,
	                 int[] handles, ParticulateMaterial[] materials, Row[] rows) {
		if (3 + 35L * mass.length + 11L * rows.length > MAX_WORDS)
			return calculate(mass, d, v, driftError, velocityError, materials, rows);
		var key = new Key(3 + 17 * mass.length + 5 * rows.length);
		key.add(mass.length); key.add(rows.length); key.add(Double.doubleToRawLongBits(h));
		for (int i = 0; i < mass.length; i++) {
			spend.run(); key.add(Double.doubleToRawLongBits(mass[i]));
			for (double[] values : new double[][]{d[i], v[i], driftError[i], velocityError[i]})
				for (double value : values) key.add(Double.doubleToRawLongBits(value));
			key.add(handles[i]); var material = materials[i];
			key.add(Double.doubleToRawLongBits(material.restitution()));
			key.add(Double.doubleToRawLongBits(material.staticFriction()));
			key.add(Double.doubleToRawLongBits(material.dynamicFriction()));
		}
		for (var row : rows) {
			spend.run(); key.add(row.first()); key.add(row.second());
			for (double value : row.normal()) key.add(Double.doubleToRawLongBits(value));
		}
		spend.run(); var cached = entries.get(key);
		if (cached != null) {
			auditEnergy(mass, d, cached.drift(), spend); auditEnergy(mass, v, cached.beforeFriction(), spend);
			return copy(cached);
		}
		var result = calculate(mass, d, v, driftError, velocityError, materials, rows);
		long size = footprint(key, result);
		while (words + size > MAX_WORDS) {
			spend.run(); var iterator = entries.entrySet().iterator(); var oldest = iterator.next();
			words -= footprint(oldest.getKey(), oldest.getValue()); iterator.remove();
		}
		var owned = copy(result); spend.run(); entries.put(key, owned); words += size;
		return result;
	}

	private Response calculate(double[] mass, double[][] d, double[][] v, double[][] driftError,
	                           double[][] velocityError, ParticulateMaterial[] materials, Row[] rows) {
		var chain = new GranularContactChainSeed(mass, rows);
		var compression = solve(mass, d, rows, spend, null, chain);
		var normalCompression = Arrays.deepEquals(d, v) ? compression : solve(mass, v, rows, spend, compression.impulses(), chain);
		var fixedRows = new ArrayList<ArrayList<Integer>>(mass.length);
		for (int i = 0; i < mass.length; i++) fixedRows.add(new ArrayList<>());
		for (int row = 0; row < rows.length; row++) {
			spend.run(); if (rows[row].second() < 0) fixedRows.get(rows[row].first()).add(row);
		}
		var driftSupported = loadedSupport(mass.length, rows, compression.impulses());
		boolean sameSupport = normalCompression == compression;
		if (!sameSupport) {
			sameSupport = true;
			for (int row = 0; row < rows.length; row++) {
				spend.run(); if ((compression.impulses()[row] > 0) != (normalCompression.impulses()[row] > 0)) { sameSupport = false; break; }
			}
		}
		var velocitySupported = sameSupport ? driftSupported : loadedSupport(mass.length, rows, normalCompression.impulses());
		double[] restitution = new double[rows.length], driftRestitution = new double[rows.length];
		for (int i = 0; i < mass.length; i++) {
			double[] change = new double[3];
			for (int axis = 0; axis < 3; axis++) change[axis] = normalCompression.values()[i][axis] - v[i][axis];
			double e = materials[i].restitution();
			if (e * Math.hypot(Math.hypot(change[0], change[1]), change[2]) <= h * .125) e = 0;
			for (int row : fixedRows.get(i)) {
				spend.run(); if (GranularSphereCollision.closing(v[i], rows[row].normal(), velocityError[i])) restitution[row] = e;
			}
		}
		for (int row = 0; row < rows.length; row++) if (rows[row].second() >= 0) {
			spend.run(); var contact = rows[row]; int first = contact.first(), second = contact.second();
			double e = Math.min(materials[first].restitution(), materials[second].restitution());
			double velocityGap = 0, driftGap = 0;
			for (int axis = 0; axis < 3; axis++) {
				velocityGap = Math.fma(contact.normal()[axis], v[second][axis] - v[first][axis], velocityGap);
				driftGap = Math.fma(contact.normal()[axis], d[second][axis] - d[first][axis], driftGap);
			}
			boolean velocityLoaded = normalCompression.impulses()[row] > 0 && velocitySupported.get(first);
			boolean driftLoaded = compression.impulses()[row] > 0 && driftSupported.get(first);
			if ((!velocityLoaded || -e * velocityGap > h * .125) && !admits(contact, v, velocityError)) restitution[row] = e;
			if ((!driftLoaded || -e * driftGap > h * .125) && !admits(contact, d, driftError)) driftRestitution[row] = e;
		}
		var driftResponse = GranularContactCompression.respond(mass, d, rows, compression, driftRestitution, spend, chain);
		var response = GranularContactCompression.respond(mass, v, rows, normalCompression, restitution, spend, chain);
		var beforeFriction = response;
		var frictionInput = response.values().clone(); boolean frictionChanged = false;
		for (int i = 0; i < mass.length; i++) {
			var normals = new ArrayList<double[]>(); double[] load = new double[3];
			for (int row : fixedRows.get(i)) {
				spend.run(); var normal = rows[row].normal(); normals.add(normal);
				for (int axis = 0; axis < 3; axis++) load[axis] = Math.fma(response.impulses()[row] / mass[i], normal[axis], load[axis]);
			}
			if (normals.isEmpty()) continue;
			frictionInput[i] = fixed.friction(response.values()[i], normals.toArray(double[][]::new),
					Math.hypot(Math.hypot(load[0], load[1]), load[2]), materials[i]);
			frictionChanged |= !Arrays.equals(frictionInput[i], response.values()[i]);
		}
		if (frictionChanged) response = solve(mass, frictionInput, rows, spend, null, chain);
		return new Response(driftResponse, response, beforeFriction);
	}

	private BitSet loadedSupport(int count, Row[] rows, double[] impulses) {
		int[] parent = new int[count]; byte[] rank = new byte[count];
		for (int i = 0; i < count; i++) { spend.run(); parent[i] = i; }
		BitSet anchored = new BitSet(count), supported = new BitSet(count);
		for (int k = 0; k < rows.length; k++) {
			spend.run(); if (impulses[k] == 0) continue;
			var row = rows[k];
			if (row.second() < 0) { anchored.set(row.first()); continue; }
			int first = root(parent, row.first()), second = root(parent, row.second());
			if (first == second) continue;
			if (rank[first] < rank[second]) parent[first] = second;
			else { parent[second] = first; if (rank[first] == rank[second]) rank[first]++; }
		}
		for (int i = anchored.nextSetBit(0); i >= 0; i = anchored.nextSetBit(i + 1)) { spend.run(); supported.set(root(parent, i)); }
		BitSet result = new BitSet(count);
		for (int i = 0; i < count; i++) { spend.run(); if (supported.get(root(parent, i))) result.set(i); }
		return result;
	}
	private int root(int[] parent, int i) {
		while (parent[i] != i) { spend.run(); parent[i] = parent[parent[i]]; i = parent[i]; }
		return i;
	}

	private static long footprint(Key key, Response response) {
		return key.bits.length + (response.velocity() == response.beforeFriction() ? 2 : 3) * (6L * key.bits[0] + 2L * key.bits[1]);
	}
	private Response copy(Response source) {
		var drift = copy(source.drift()); var velocity = copy(source.velocity());
		return new Response(drift, velocity, source.velocity() == source.beforeFriction() ? velocity : copy(source.beforeFriction()));
	}
	private Result copy(Result source) {
		double[][] values = new double[source.values().length][], error = new double[values.length][];
		for (int i = 0; i < values.length; i++) { spend.run(); values[i] = source.values()[i].clone(); error[i] = source.arithmeticError()[i].clone(); }
		double[] impulses = new double[source.impulses().length], gaps = new double[impulses.length];
		for (int i = 0; i < impulses.length; i++) { spend.run(); impulses[i] = source.impulses()[i]; gaps[i] = source.gapError()[i]; }
		return new Result(values, impulses, gaps, error);
	}
	private final class Key {
		private final long[] bits;
		private int cursor, hash = 1;
		Key(int size) { bits = new long[size]; }
		void add(long value) { bits[cursor++] = value; hash = 31 * hash + Long.hashCode(value); }
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof GranularComponentResponse.Key other)) return false;
			spend.run(); if (!Arrays.equals(bits, 0, 3, other.bits, 0, 3)) return false;
			int bodies = (int) bits[0], rows = (int) bits[1], offset = 3;
			for (int i = 0; i < bodies + rows; i++) {
				spend.run(); int width = i < bodies ? 17 : 5;
				if (!Arrays.equals(bits, offset, offset + width, other.bits, offset, offset + width)) return false;
				offset += width;
			}
			return true;
		}
	}
}
