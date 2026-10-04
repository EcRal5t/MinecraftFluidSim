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
			double mass = density * geometry.moments().volume();
			if (!(mass > 0) || !Double.isFinite(mass) || !Double.isFinite(1 / density))
				throw new IllegalArgumentException("Liquid control-volume inertia exceeds numerical range");
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
	/** Positive volume compliance is -dV/dp in m^3/Pa, evaluated by the gas equation of state. */
	record GasNode(double pressure, double volumeCompliance) {
		GasNode {
			if (!Double.isFinite(pressure) || !(volumeCompliance > 0) || !Double.isFinite(volumeCompliance))
				throw new IllegalArgumentException("Invalid compliant gas pressure node");
		}
	}
	record Result(double[] pressure, double[] normalVelocity, int iterations, double fluxResidual,
			double[] gasPressure, double[] gasVolumeChange, LiquidPolyhedron.Point[] pressureGradient,
			double[] cellVelocity, double pressureDissipationJoules, double boundaryWorkJoules,
			LiquidPolyhedron.Point boundaryImpulse, double divergenceResidual, double divergenceRoundoffBound) { }
	private final List<Cell> cells;
	private final List<Face> faces;
	private final Stencil[] stencils;
	private final LiquidPolyhedron.Point[] normal;
	private final int[] faceUnknown;
	private final boolean[] anchor;
	private final double[] reference, diagonal;
	private final double[] residualVolume;
	private final double[] rowNormBound, evaluationOperations;
	private final int maximumIncidence;
	private final List<GasNode> gasNodes;
	private final int[] faceGasNode;
	private final int gasBase;

	LiquidControlVolumeProjection(List<Cell> cells, List<Face> faces) {
		this(cells, faces, List.of(), fixedGasFaces(faces.size()));
	}
	LiquidControlVolumeProjection(List<Cell> cells, List<Face> faces, List<GasNode> gasNodes, int[] faceGasNode) {
		if (faceGasNode.length != faces.size()) throw new IllegalArgumentException("Gas pressure faces have invalid cardinality");
		this.gasNodes = List.copyOf(gasNodes); this.faceGasNode = faceGasNode.clone();
		this.cells = List.copyOf(cells); this.faces = List.copyOf(faces);
		normal = new LiquidPolyhedron.Point[faces.size()]; faceUnknown = new int[faces.size()];
		anchor = new boolean[cells.size()]; reference = new double[cells.size()];
		int[] root = new int[cells.size()];
		var incidence = new ArrayList<ArrayList<Integer>>();
		for (int c = 0; c < root.length; c++) { root[c] = c; incidence.add(new ArrayList<>()); }
		int unknowns = cells.size();
		for (var face : faces) if (face.second != -1) unknowns++;
		gasBase = unknowns; unknowns += gasNodes.size();
		int nextFace = cells.size();
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f);
			if (face.first >= cells.size() || face.second >= cells.size()) throw new IllegalArgumentException("Pressure face has no control volume");
			var vector = face.surface.areaVector(); double area = face.surface.area();
			normal[f] = new LiquidPolyhedron.Point(vector.x() / area, vector.y() / area, vector.z() / area);
			int node = faceGasNode[f];
			if (node < -1 || node >= gasNodes.size() || node >= 0 && face.second != -1)
				throw new IllegalArgumentException("Gas pressure node has invalid face support");
			faceUnknown[f] = face.second == -1 ? node < 0 ? -1 : gasBase + node : nextFace++;
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
		rowNormBound = diagonal.clone(); evaluationOperations = diagonal.clone();
		residualVolume = new double[unknowns]; Arrays.fill(residualVolume, Double.POSITIVE_INFINITY);
		for (int c = 0; c < cells.size(); c++) residualVolume[c] = cells.get(c).geometry().moments().volume();
		for (int f = 0; f < faces.size(); f++) if (faceUnknown[f] >= 0) {
			var face = faces.get(f); double volume = residualVolume[face.first];
			if (face.second >= 0) volume = Math.min(volume, residualVolume[face.second]);
			residualVolume[faceUnknown[f]] = Math.min(residualVolume[faceUnknown[f]], volume);
		}
		int maximum = 0;
		for (int c = 0; c < cells.size(); c++) {
			stencils[c] = new Stencil(c, incidence.get(c)); maximum = Math.max(maximum, incidence.get(c).size());
			var stencil = stencils[c];
			var localUnknowns = new ArrayList<Integer>(); var energies = new ArrayList<Double>();
			localUnknowns.add(c); energies.add(stencil.basisEnergy(-1));
			for (int i = 0; i < stencil.indices.length; i++) {
				int unknown = faceUnknown[stencil.indices[i]];
				boolean first = true; for (int j = 0; j < i; j++) first &= stencil.unknowns[j] != unknown;
				if (unknown >= 0 && first) {
					localUnknowns.add(unknown); energies.add(stencil.mobility * stencil.geometry.basisEnergy(stencil.unknowns, unknown));
				}
			}
			double rootSum = 0; for (double energy : energies) rootSum += Math.sqrt(energy);
			for (int i = 0; i < localUnknowns.size(); i++) {
				int unknown = localUnknowns.get(i); double energy = energies.get(i);
				if (unknown != c || !anchor[c]) diagonal[unknown] += energy;
				rowNormBound[unknown] += Math.sqrt(energy) * rootSum;
				evaluationOperations[unknown] += 64 * stencil.indices.length + localUnknowns.size();
			}
		}
		maximumIncidence = maximum;
	}

	/** divergenceTolerance is inverse seconds; each algebraic row is normalized by its supporting liquid volume. */
	Result project(double[] incoming, LiquidPolyhedron.Point gravity, double dt, double divergenceTolerance, int maximumIterations) {
		if (incoming.length != 3 * cells.size() || !(dt > 0) || !Double.isFinite(dt) || !(divergenceTolerance > 0) || !Double.isFinite(divergenceTolerance) || maximumIterations <= 0)
			throw new IllegalArgumentException("Invalid liquid control-volume projection request");
		double[] predicted = incoming.clone(), velocity = new double[faces.size()], rhs = new double[diagonal.length], pressure = rhs.clone();
		for (int c = 0; c < cells.size(); c++) for (int a = 0; a < 3; a++) {
			int id = 3 * c + a; predicted[id] += dt * gravity.coordinate(a);
			if (!Double.isFinite(predicted[id])) throw new IllegalArgumentException("Non-finite liquid momentum predictor");
		}
		double[] storage = new double[gasNodes.size()];
		for (int node = 0; node < storage.length; node++) {
			storage[node] = gasNodes.get(node).volumeCompliance / (dt * dt);
			if (!(storage[node] > 0) || !Double.isFinite(storage[node])) throw new IllegalStateException("Gas pressure compliance exceeds numerical range");
		}
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f);
			double first = normalVelocity(predicted, face.first, normal[f]);
			velocity[f] = first;
			if (face.second >= 0) {
				double second = normalVelocity(predicted, face.second, normal[f]); velocity[f] = 0.5 * (first + second);
				rhs[faceUnknown[f]] += face.surface.area() * (first - second) / dt;
			} else if (face.second == -2) rhs[faceUnknown[f]] += face.surface.area() * (first - face.prescribed) / dt;
			else if (faceGasNode[f] >= 0) rhs[gasBase + faceGasNode[f]] += face.surface.area() * first / dt;
		}
		// Cell momentum is the shared inertial variable. Local pressure corrections close every
		// control volume; face equations impose one normal velocity on both adjacent liquids.
		for (int c = 0; c < cells.size(); c++) if (anchor[c]) rhs[c] = 0;
		double[] residual = rhs.clone(), direction = rhs.clone(), product = rhs.clone(), preconditioned = rhs.clone();
		multiply(pressure, product, true, storage);
		double rz = 0;
		for (int i = 0; i < rhs.length; i++) {
			residual[i] -= product[i]; double weight = diagonal[i] + (i >= gasBase ? storage[i - gasBase] : 0);
			preconditioned[i] = weight > 0 ? residual[i] / weight : 0;
			direction[i] = preconditioned[i]; rz += residual[i] * preconditioned[i];
		}
		int iterations = 0;
		double target = divergenceTolerance / (dt * (1 + maximumIncidence));
		while (!converged(residual, pressure, rhs, storage, target)) {
			if (iterations++ >= maximumIterations || !(rz > 0) || !Double.isFinite(rz)) throw new IllegalStateException("Liquid control-volume pressure did not converge");
			multiply(direction, product, false, storage);
			double denominator = dot(direction, product);
			if (!(denominator > 0) || !Double.isFinite(denominator)) throw new IllegalStateException("Invalid liquid pressure search direction");
			double alpha = rz / denominator;
			for (int i = 0; i < rhs.length; i++) pressure[i] += alpha * direction[i];
			multiply(pressure, product, true, storage);
			double next = 0;
			for (int i = 0; i < rhs.length; i++) {
				residual[i] = rhs[i] - product[i]; double weight = diagonal[i] + (i >= gasBase ? storage[i - gasBase] : 0);
				preconditioned[i] = weight > 0 ? residual[i] / weight : 0;
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
		double[] gasPressure = new double[gasNodes.size()], gasVolumeChange = new double[gasNodes.size()];
		for (int f = 0; f < faces.size(); f++) {
			var face = faces.get(f);
			velocity[f] = face.second == -2 ? face.prescribed : velocity[f] - dt * correction[f] / (face.surface.area() * (face.second >= 0 ? 2 : 1));
			flux[face.first] += face.surface.area() * velocity[f];
			if (face.second >= 0) flux[face.second] -= face.surface.area() * velocity[f];
			else if (faceGasNode[f] >= 0) gasVolumeChange[faceGasNode[f]] -= dt * face.surface.area() * velocity[f];
		}
		for (int c = 0; c < cells.size(); c++) physicalPressure[c] = pressure[c] + reference[c];
		double error = maximum(flux); double[] area = new double[cells.size()]; double velocityMagnitude = maximum(predicted);
		for (var face : faces) { area[face.first] += face.surface.area(); if (face.second >= 0) area[face.second] += face.surface.area(); }
		double[] backwardError = backwardError(pressure, rhs, storage);
		double divergenceResidual = 0, divergenceRoundoffBound = 0;
		for (int c = 0; c < cells.size(); c++) {
			double allowance = divergenceTolerance * residualVolume[c] + 32 * Math.ulp(velocityMagnitude) * area[c] + dt * backwardError[c];
			for (int f : stencils[c].indices) if (faces.get(f).second >= 0 || faces.get(f).second == -2)
				allowance += dt * backwardError[faceUnknown[f]];
			if (!(Math.abs(flux[c]) <= allowance))
			throw new IllegalStateException("Projected liquid face flux does not close in its control volume: " + c);
			divergenceResidual = Math.max(divergenceResidual, Math.abs(flux[c]) / residualVolume[c]);
			divergenceRoundoffBound = Math.max(divergenceRoundoffBound, allowance / residualVolume[c] - divergenceTolerance);
		}
		var gradient = new LiquidPolyhedron.Point[cells.size()]; double[] projected = predicted.clone();
		double dissipation = 0, boundaryWork = 0; double[] boundaryImpulse = new double[3];
		for (int c = 0; c < cells.size(); c++) {
			var stencil = stencils[c]; gradient[c] = stencil.gradient(pressure);
			for (int a = 0; a < 3; a++) {
				double change = -dt * stencil.mobility * gradient[c].coordinate(a); projected[3 * c + a] += change;
				dissipation += 0.5 * cells.get(c).density * stencil.volume * change * change;
			}
			for (int i = 0; i < stencil.indices.length; i++) {
				int f = stencil.indices[i]; double difference = faceValue(f, c, pressure, true) - (anchor[c] ? 0 : pressure[c]);
				double mismatch = difference - stencil.displacement[i].dot(gradient[c]);
				dissipation += dt * dt * stencil.mobility * stencil.weight[i] * mismatch * mismatch;
			}
		}
		for (int f = 0; f < faces.size(); f++) if (faces.get(f).second < 0) {
			var face = faces.get(f); double boundaryPressure = faceValue(f, face.first, pressure, true) + reference[face.first];
			boundaryWork -= dt * boundaryPressure * face.surface.area() * velocity[f];
			for (int a = 0; a < 3; a++) boundaryImpulse[a] -= dt * boundaryPressure * face.surface.areaVector().coordinate(a);
		}
		for (int node = 0; node < gasPressure.length; node++) {
			gasPressure[node] = gasNodes.get(node).pressure + pressure[gasBase + node];
			double mismatch = gasVolumeChange[node] + gasNodes.get(node).volumeCompliance * pressure[gasBase + node];
			if (Math.abs(mismatch) > dt * divergenceTolerance * residualVolume[gasBase + node] + dt * dt * backwardError[gasBase + node]
					+ 32 * Math.ulp(Math.abs(gasVolumeChange[node])))
				throw new IllegalStateException("Gas pressure and displaced liquid volume do not close");
		}
		return new Result(physicalPressure, velocity, iterations, error, gasPressure, gasVolumeChange, gradient, projected, dissipation, boundaryWork,
				new LiquidPolyhedron.Point(boundaryImpulse[0], boundaryImpulse[1], boundaryImpulse[2]), divergenceResidual, divergenceRoundoffBound);
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
		final ControlVolumeGradient geometry;
		final int[] unknowns;
		Stencil(int cell, List<Integer> incidence) {
			this.cell = cell; geometry = new ControlVolumeGradient(cell, cells, faces, incidence);
			indices = geometry.indices; sign = geometry.sign; basis = geometry.basis; displacement = geometry.displacement; weight = geometry.weight;
			unknowns = new int[indices.length]; for (int i = 0; i < indices.length; i++) unknowns[i] = faceUnknown[indices[i]];
			difference = new double[indices.length]; error = difference.clone(); result = difference.clone();
			volume = geometry.volume; mobility = 1 / cells.get(cell).density;
		}
		double basisEnergy(int selected) {
			return mobility * geometry.basisEnergy(selected);
		}
		double[] flux(double[] pressure, boolean boundary) {
			double gx = 0, gy = 0, gz = 0;
			for (int i = 0; i < indices.length; i++) {
				int f = indices[i];
				double value = faceValue(f, cell, pressure, boundary);
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
		LiquidPolyhedron.Point gradient(double[] pressure) {
			double gx = 0, gy = 0, gz = 0;
			for (int i = 0; i < indices.length; i++) {
				double difference = faceValue(indices[i], cell, pressure, true) - (anchor[cell] ? 0 : pressure[cell]);
				gx += basis[i].x() * difference; gy += basis[i].y() * difference; gz += basis[i].z() * difference;
			}
			return new LiquidPolyhedron.Point(gx, gy, gz);
		}
	}
	private double faceValue(int f, int cell, double[] pressure, boolean boundary) {
		int unknown = faceUnknown[f]; double value = unknown >= 0 ? pressure[unknown] : 0;
		if (boundary && faces.get(f).second == -1) value += faces.get(f).prescribed - reference[cell];
		return value;
	}
	private void multiply(double[] value, double[] output, boolean boundary, double[] storage) {
		Arrays.fill(output, 0);
		for (var stencil : stencils) {
			var flux = stencil.flux(value, boundary);
			for (int i = 0; i < flux.length; i++) {
				if (!anchor[stencil.cell] || boundary) output[stencil.cell] -= flux[i];
				int unknown = faceUnknown[stencil.indices[i]]; if (unknown >= 0) output[unknown] += flux[i];
			}
		}
		for (int node = 0; node < storage.length; node++) output[gasBase + node] += storage[node] * value[gasBase + node];
	}
	private static int[] fixedGasFaces(int count) { int[] result = new int[count]; Arrays.fill(result, -1); return result; }
	private static double normalVelocity(double[] value, int cell, LiquidPolyhedron.Point normal) {
		return value[3 * cell] * normal.x() + value[3 * cell + 1] * normal.y() + value[3 * cell + 2] * normal.z();
	}
	private boolean converged(double[] residual, double[] pressure, double[] rhs, double[] storage, double target) {
		double[] error = backwardError(pressure, rhs, storage);
		for (int i = 0; i < residual.length; i++) if (!(Math.abs(residual[i]) <= target * residualVolume[i] + error[i])) return false;
		return true;
	}
	private double[] backwardError(double[] pressure, double[] rhs, double[] storage) {
		double magnitude = maximum(pressure);
		for (int f = 0; f < faces.size(); f++) if (faces.get(f).second == -1)
			magnitude = Math.max(magnitude, Math.abs(faces.get(f).prescribed - reference[faces.get(f).first]));
		double[] error = new double[pressure.length];
		for (int i = 0; i < error.length; i++) {
			double operations = evaluationOperations[i] + 4, epsilon = Math.scalb(operations, -53), gamma = epsilon / (1 - epsilon);
			error[i] = gamma * (Math.abs(rhs[i]) + rowNormBound[i] * magnitude
					+ (i >= gasBase ? storage[i - gasBase] * Math.abs(pressure[i]) : 0));
		}
		return error;
	}
	private static int find(int[] root, int cell) { while (root[cell] != cell) { root[cell] = root[root[cell]]; cell = root[cell]; } return cell; }
	private static double dot(double[] a, double[] b) { double sum = 0; for (int c = 0; c < a.length; c++) sum += a[c] * b[c]; return sum; }
	private static double maximum(double[] values) { double max = 0; for (double value : values) max = Math.max(max, Math.abs(value)); return max; }
}
