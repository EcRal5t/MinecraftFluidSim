package mmm.fluid;

import mmm.granular.ParticulateMaterial;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import static mmm.fluid.GranularContactCompression.*;

/** Common-source contact response using mass-weighted normal impulses and Coulomb friction. */
final class GranularComponentResponse {
	private final double h;
	private final GranularSphereCollision fixed;
	private final Runnable spend;

	GranularComponentResponse(double h, GranularSphereCollision fixed, Runnable spend) {
		this.h = h; this.fixed = fixed; this.spend = spend;
	}

	Result respond(double[] mass, double[][] v, ParticulateMaterial[] materials, Row[] rows) {
		var normalCompression = solve(mass, v, rows, spend);
		double[][] velocityError = new double[mass.length][3];
		var fixedRows = new ArrayList<ArrayList<Integer>>(mass.length);
		for (int i = 0; i < mass.length; i++) fixedRows.add(new ArrayList<>());
		for (int row = 0; row < rows.length; row++) {
			spend.run(); if (rows[row].second() < 0) fixedRows.get(rows[row].first()).add(row);
		}
		var velocitySupported = loadedSupport(mass.length, rows, normalCompression.impulses());
		double[] restitution = new double[rows.length];
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
			double velocityGap = 0;
			for (int axis = 0; axis < 3; axis++) {
				velocityGap = Math.fma(contact.normal()[axis], v[second][axis] - v[first][axis], velocityGap);
			}
			boolean velocityLoaded = normalCompression.impulses()[row] > 0 && velocitySupported.get(first);
			if ((!velocityLoaded || -e * velocityGap > h * .125) && !admits(contact, v, velocityError)) restitution[row] = e;
		}
		var response = GranularContactCompression.respond(mass, v, rows, normalCompression, restitution, spend);
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
		if (frictionChanged) response = solve(mass, frictionInput, rows, spend);
		return response;
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

}
