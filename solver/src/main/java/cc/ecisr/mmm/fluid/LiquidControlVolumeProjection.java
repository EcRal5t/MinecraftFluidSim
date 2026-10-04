package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Hybrid pressure projection with affine consistency on convex liquid control volumes. */
final class LiquidControlVolumeProjection {
	record Cell(LiquidPolyhedron geometry, double density) {
		Cell {
			if (geometry == null || !(geometry.moments().volume() > 0) || !(density > 0) || !Double.isFinite(density))
				throw new IllegalArgumentException("Invalid liquid pressure control volume");
		}
		LiquidPolyhedron.Point centroid() {
			var m = geometry.moments(); return new LiquidPolyhedron.Point(m.centroid(0), m.centroid(1), m.centroid(2));
		}
	}
	/** second >= 0: liquid neighbor; -1: prescribed gas pressure; -2: prescribed solid velocity. */
	record Face(int first, int second, LiquidPolyhedron.Surface surface, double prescribed) {
		Face {
			if (first < 0 || second < -2 || first == second || surface == null || !(surface.area() > 0) || !Double.isFinite(prescribed))
				throw new IllegalArgumentException("Invalid liquid pressure face");
		}
	}
	record Result(double[] pressure, double[] normalVelocity, int iterations, double fluxResidual) { }
	private final List<Cell> cells;
	private final List<Face> faces;
	private final Stencil[] stencils;
	private final LiquidPolyhedron.Point[] normal;
	private final int[] faceUnknown;
	private final boolean[] anchor;
	private final double[] reference, diagonal;
	private final int maximumIncidence;

	LiquidControlVolumeProjection(List<Cell> cells, List<Face> faces) {
		this.cells = List.copyOf(cells); this.faces = List.copyOf(faces);
		normal = new LiquidPolyhedron.Point[faces.size()]; faceUnknown = new int[faces.size()];
		anchor = new boolean[cells.size()]; reference = new double[cells.size()];
		int[] root = new int[cells.size()];
		var incidence = new ArrayList<ArrayList<Integer>>();
		for (int c = 0; c < root.length; c++) { root[c] = c; incidence.add(new ArrayList<>()); }
		int unknowns = cells.size();
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f);
			if (face.first >= cells.size() || face.second >= cells.size()) throw new IllegalArgumentException("Pressure face has no control volume");
			var vector = face.surface.areaVector(); double area = face.surface.area();
			normal[f] = new LiquidPolyhedron.Point(vector.x() / area, vector.y() / area, vector.z() / area);
			faceUnknown[f] = face.second == -1 ? -1 : unknowns++;
			incidence.get(face.first).add(f);
			if (face.second >= 0) {
				incidence.get(face.second).add(f); root[find(root, face.second)] = find(root, face.first);
			}
		}
		boolean[] prescribed = new boolean[cells.size()];
		for (var face : faces) if (face.second == -1) {
			int component = find(root, face.first);
			if (!prescribed[component]) reference[component] = face.prescribed;
			prescribed[component] = true;
		}
		for (int c = 0; c < cells.size(); c++) {
			int component = find(root, c);
			anchor[c] = component == c && !prescribed[component]; reference[c] = reference[component];
		}
		diagonal = new double[unknowns]; stencils = new Stencil[cells.size()];
		int maximum = 0;
		for (int c = 0; c < cells.size(); c++) {
			stencils[c] = new Stencil(c, incidence.get(c)); maximum = Math.max(maximum, incidence.get(c).size());
			var stencil = stencils[c];
			if (!anchor[c]) diagonal[c] += stencil.basisEnergy(-1);
			for (int i = 0; i < stencil.indices.length; i++) {
				int unknown = faceUnknown[stencil.indices[i]];
				if (unknown >= 0) diagonal[unknown] += stencil.basisEnergy(i);
			}
		}
		maximumIncidence = maximum;
	}

	Result project(double[] incoming, LiquidPolyhedron.Point gravity, double dt, double fluxTolerance, int maximumIterations) {
		if (incoming.length != faces.size() || !(dt > 0) || !Double.isFinite(dt) || !(fluxTolerance > 0) || !Double.isFinite(fluxTolerance) || maximumIterations <= 0)
			throw new IllegalArgumentException("Invalid liquid control-volume projection request");
		double[] velocity = incoming.clone(), rhs = new double[diagonal.length], pressure = rhs.clone();
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f);
			if (!Double.isFinite(velocity[f])) throw new IllegalArgumentException("Non-finite liquid face velocity");
			velocity[f] += dt * normal[f].dot(gravity);
			double flux = face.surface.area() * velocity[f] / dt;
			rhs[face.first] -= flux;
			if (face.second >= 0) rhs[face.second] += flux;
			else if (face.second == -2) rhs[faceUnknown[f]] += face.surface.area() * (velocity[f] - face.prescribed) / dt;
		}
		for (int c = 0; c < cells.size(); c++) if (anchor[c]) rhs[c] = 0;
		double[] residual = rhs.clone(), direction = rhs.clone(), product = rhs.clone(), preconditioned = rhs.clone();
		multiply(pressure, product, true);
		double rz = 0;
		for (int i = 0; i < rhs.length; i++) {
			residual[i] -= product[i]; preconditioned[i] = diagonal[i] > 0 ? residual[i] / diagonal[i] : 0;
			direction[i] = preconditioned[i]; rz += residual[i] * preconditioned[i];
		}
		int iterations = 0;
		double target = fluxTolerance / (dt * (1 + maximumIncidence));
		while (maximum(residual) > target) {
			if (iterations++ >= maximumIterations || !(rz > 0) || !Double.isFinite(rz)) throw new IllegalStateException("Liquid control-volume pressure did not converge");
			multiply(direction, product, false);
			double denominator = dot(direction, product);
			if (!(denominator > 0) || !Double.isFinite(denominator)) throw new IllegalStateException("Invalid liquid pressure search direction");
			double alpha = rz / denominator;
			for (int i = 0; i < rhs.length; i++) pressure[i] += alpha * direction[i];
			multiply(pressure, product, true);
			double next = 0;
			for (int i = 0; i < rhs.length; i++) {
				residual[i] = rhs[i] - product[i]; preconditioned[i] = diagonal[i] > 0 ? residual[i] / diagonal[i] : 0;
				next += residual[i] * preconditioned[i];
			}
			for (int i = 0; i < rhs.length; i++) direction[i] = preconditioned[i] + next / rz * direction[i];
			rz = next;
		}
		double[] correction = new double[faces.size()];
		for (var stencil : stencils) {
			var flux = stencil.flux(pressure, true);
			for (int i = 0; i < flux.length; i++) correction[stencil.indices[i]] += stencil.sign[i] * flux[i];
		}
		double[] flux = new double[cells.size()], physicalPressure = new double[cells.size()];
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f);
			velocity[f] = face.second == -2 ? face.prescribed : velocity[f] - dt * correction[f] / (face.surface.area() * (face.second >= 0 ? 2 : 1));
			flux[face.first] += face.surface.area() * velocity[f];
			if (face.second >= 0) flux[face.second] -= face.surface.area() * velocity[f];
		}
		for (int c = 0; c < cells.size(); c++) physicalPressure[c] = pressure[c] + reference[c];
		double error = maximum(flux), area = 0; for (var face : faces) area += face.surface.area();
		if (!(error <= fluxTolerance + 32 * Math.ulp(maximum(velocity)) * area)) throw new IllegalStateException("Projected liquid face flux does not close: " + error);
		return new Result(physicalPressure, velocity, iterations, error);
	}

	private final class Stencil {
		// E = (V |grad p|^2 + sum(A/d) (p_face-p_cell-grad p dot displacement)^2) / (2 rho).
		// The consistency term reproduces affine pressure; the residual term controls the remaining face modes.
		final int cell;
		final int[] indices, sign;
		final LiquidPolyhedron.Point[] basis, displacement;
		final double[] weight;
		final double[] difference, error, result;
		final double volume, mobility;
		Stencil(int cell, List<Integer> incidence) {
			this.cell = cell; indices = incidence.stream().mapToInt(Integer::intValue).toArray();
			sign = new int[indices.length]; basis = new LiquidPolyhedron.Point[indices.length]; displacement = basis.clone(); weight = new double[indices.length];
			difference = new double[indices.length]; error = difference.clone(); result = difference.clone();
			volume = cells.get(cell).geometry.moments().volume(); mobility = 1 / cells.get(cell).density;
			var center = cells.get(cell).centroid();
			for (int i = 0; i < indices.length; i++) {
				int f = indices[i]; var face = faces.get(f); sign[i] = face.first == cell ? 1 : -1;
				displacement[i] = face.surface.centroid().subtract(center);
				basis[i] = scale(face.surface.areaVector(), sign[i] / volume);
				double distance = sign[i] * normal[f].dot(displacement[i]);
				if (!(distance > 0)) throw new IllegalArgumentException("Pressure centroid must lie inside its liquid face");
				weight[i] = face.surface.area() / distance;
			}
		}
		double basisEnergy(int selected) {
			var gradient = new LiquidPolyhedron.Point(0, 0, 0);
			for (int i = 0; i < indices.length; i++) gradient = add(gradient, scale(basis[i], selected < 0 ? -1 : i == selected ? 1 : 0));
			double energy = volume * gradient.dot(gradient);
			for (int i = 0; i < indices.length; i++) {
				double error = (selected < 0 ? -1 : i == selected ? 1 : 0) - displacement[i].dot(gradient);
				energy += weight[i] * error * error;
			}
			return mobility * energy;
		}
		double[] flux(double[] pressure, boolean boundary) {
			double gx = 0, gy = 0, gz = 0;
			for (int i = 0; i < indices.length; i++) {
				int f = indices[i], unknown = faceUnknown[f];
				double value = unknown >= 0 ? pressure[unknown] : boundary ? faces.get(f).prescribed - reference[cell] : 0;
				difference[i] = value - (anchor[cell] ? 0 : pressure[cell]);
				gx += basis[i].x() * difference[i]; gy += basis[i].y() * difference[i]; gz += basis[i].z() * difference[i];
			}
			double cx = volume * gx, cy = volume * gy, cz = volume * gz;
			for (int i = 0; i < indices.length; i++) {
				var d = displacement[i];
				error[i] = difference[i] - (d.x() * gx + d.y() * gy + d.z() * gz);
				cx -= weight[i] * error[i] * d.x(); cy -= weight[i] * error[i] * d.y(); cz -= weight[i] * error[i] * d.z();
			}
			for (int i = 0; i < indices.length; i++) {
				var b = basis[i]; result[i] = mobility * (b.x() * cx + b.y() * cy + b.z() * cz + weight[i] * error[i]);
			}
			return result;
		}
	}
	private void multiply(double[] value, double[] output, boolean boundary) {
		Arrays.fill(output, 0);
		for (var stencil : stencils) {
			var flux = stencil.flux(value, boundary);
			for (int i = 0; i < flux.length; i++) {
				if (!anchor[stencil.cell]) output[stencil.cell] -= flux[i];
				int unknown = faceUnknown[stencil.indices[i]]; if (unknown >= 0) output[unknown] += flux[i];
			}
		}
	}
	private static LiquidPolyhedron.Point scale(LiquidPolyhedron.Point p, double s) { return new LiquidPolyhedron.Point(p.x() * s, p.y() * s, p.z() * s); }
	private static LiquidPolyhedron.Point add(LiquidPolyhedron.Point a, LiquidPolyhedron.Point b) { return new LiquidPolyhedron.Point(a.x() + b.x(), a.y() + b.y(), a.z() + b.z()); }
	private static int find(int[] root, int cell) { while (root[cell] != cell) { root[cell] = root[root[cell]]; cell = root[cell]; } return cell; }
	private static double dot(double[] a, double[] b) { double sum = 0; for (int c = 0; c < a.length; c++) sum += a[c] * b[c]; return sum; }
	private static double maximum(double[] values) { double max = 0; for (double value : values) max = Math.max(max, Math.abs(value)); return max; }
}
