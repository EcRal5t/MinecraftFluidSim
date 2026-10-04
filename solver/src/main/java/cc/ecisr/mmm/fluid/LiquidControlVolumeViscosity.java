package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import java.util.List;

/** Implicit Newtonian symmetric strain on the same cell/face geometry as liquid pressure. */
final class LiquidControlVolumeViscosity {
	record Result(double[] cellVelocity, double[] faceVelocity, int iterations,
			double dissipatedJoules, double temporalDissipationJoules,
			LiquidPolyhedron.Point boundaryImpulse, double boundaryWorkJoules) { }
	private final List<LiquidControlVolumeProjection.Cell> cells;
	private final List<LiquidControlVolumeProjection.Face> faces;
	private final ControlVolumeGradient[] geometry;
	private final double[] viscosity, mass, diagonal, physical;
	private final LiquidPolyhedron.Point[] normal;
	private final FluidSolverConfig.WallCondition wall;
	private final double[][] difference, residual;
	private final double[] gradient = new double[9], consistency = new double[9];
	private final int faceBase;

	LiquidControlVolumeViscosity(List<LiquidControlVolumeProjection.Cell> cells,
			List<LiquidControlVolumeProjection.Face> faces, double[] viscosity, FluidSolverConfig.WallCondition wall) {
		if (viscosity.length != cells.size() || wall == null) throw new IllegalArgumentException("Invalid viscous control-volume properties");
		this.cells = List.copyOf(cells); this.faces = List.copyOf(faces); this.viscosity = viscosity.clone(); this.wall = wall;
		geometry = ControlVolumeGradient.build(cells, faces); faceBase = 3 * cells.size();
		mass = new double[faceBase + 3 * faces.size()]; diagonal = mass.clone(); physical = mass.clone();
		normal = new LiquidPolyhedron.Point[faces.size()]; difference = new double[cells.size()][]; residual = difference.clone();
		for (int f = 0; f < faces.size(); f++) {
			var surface = faces.get(f).surface(); var vector = surface.areaVector();
			normal[f] = new LiquidPolyhedron.Point(vector.x() / surface.area(), vector.y() / surface.area(), vector.z() / surface.area());
		}
		for (int c = 0; c < cells.size(); c++) {
			if (!(viscosity[c] >= 0) || !Double.isFinite(viscosity[c])) throw new IllegalArgumentException("Invalid liquid viscosity");
			var g = geometry[c]; difference[c] = new double[3 * g.indices.length]; residual[c] = difference[c].clone();
			for (int a = 0; a < 3; a++) mass[3 * c + a] = cells.get(c).density() * g.volume;
			for (int selected = -1; selected < g.indices.length; selected++) {
				double[] basis = new double[3];
				for (int i = 0; i < g.indices.length; i++) for (int a = 0; a < 3; a++)
					basis[a] += g.basis[i].coordinate(a) * (selected < 0 ? -1 : i == selected ? 1 : 0);
				int f = selected < 0 ? -1 : g.indices[selected];
				for (int a = 0; a < 3; a++) {
					double[] vector = {0, 0, 0}; vector[a] = 1;
					if (f >= 0) projectVector(vector, normal[f], faces.get(f).second() == -2);
					double norm = dot(vector, vector), component = dot(basis, vector);
					int id = selected < 0 ? 3 * c + a : faceBase + 3 * f + a;
					diagonal[id] += viscosity[c] * (g.basisEnergy(selected) * norm + g.volume * component * component);
				}
			}
		}
	}

	Result advance(double[] cellVelocity, double[] faceVelocity, double dt, double momentumTolerance, int maximumIterations) {
		if (cellVelocity.length != faceBase || faceVelocity.length != physical.length - faceBase
				|| !(dt > 0) || !Double.isFinite(dt) || !(momentumTolerance > 0) || !Double.isFinite(momentumTolerance) || maximumIterations <= 0)
			throw new IllegalArgumentException("Invalid viscous control-volume step");
		double[] value = new double[physical.length], rhs = value.clone(), product = value.clone(), error = value.clone(), direction = value.clone(), z = value.clone();
		System.arraycopy(cellVelocity, 0, value, 0, faceBase); System.arraycopy(faceVelocity, 0, value, faceBase, faceVelocity.length);
		for (double velocity : value) if (!Double.isFinite(velocity)) throw new IllegalArgumentException("Non-finite viscous velocity");
		projectFaces(value, false);
		for (int i = 0; i < faceBase; i++) rhs[i] = mass[i] * cellVelocity[i] / dt;
		apply(value, product, dt, true);
		for (int i = 0; i < value.length; i++) error[i] = rhs[i] - product[i];
		precondition(error, z, dt); System.arraycopy(z, 0, direction, 0, z.length);
		double rz = dot(error, z), target = momentumTolerance / (dt * value.length);
		int iterations = 0;
		while (maximum(error) > target) {
			if (iterations++ >= maximumIterations || !(rz > 0) || !Double.isFinite(rz)) throw new IllegalStateException("Liquid viscous control-volume solve did not converge");
			apply(direction, product, dt, false);
			double denominator = dot(direction, product);
			if (!(denominator > 0) || !Double.isFinite(denominator)) throw new IllegalStateException("Invalid viscous search direction");
			double alpha = rz / denominator;
			for (int i = 0; i < value.length; i++) value[i] += alpha * direction[i];
			apply(value, product, dt, true);
			for (int i = 0; i < value.length; i++) error[i] = rhs[i] - product[i];
			precondition(error, z, dt); double next = dot(error, z);
			for (int i = 0; i < value.length; i++) direction[i] = z[i] + next / rz * direction[i];
			rz = next;
		}
		// Recover prescribed wall components and retain the complete stress/energy audit.
		System.arraycopy(value, 0, physical, 0, value.length); projectFaces(physical, true);
		double[] finalVelocity = physical.clone(), stress = value.clone(); Arrays.fill(stress, 0);
		double dissipation = stress(finalVelocity, stress), temporal = 0, work = 0;
		if (!Double.isFinite(dissipation)) throw new IllegalStateException("Liquid viscous dissipation overflow");
		double[] impulse = new double[3];
		for (int i = 0; i < faceBase; i++) {
			double change = finalVelocity[i] - cellVelocity[i]; temporal += 0.5 * mass[i] * change * change;
		}
		for (int f = 0; f < faces.size(); f++) if (faces.get(f).second() == -2) for (int a = 0; a < 3; a++) {
			int id = faceBase + 3 * f + a; impulse[a] += dt * stress[id]; work += dt * finalVelocity[id] * stress[id];
		}
		return new Result(Arrays.copyOf(finalVelocity, faceBase), Arrays.copyOfRange(finalVelocity, faceBase, finalVelocity.length), iterations,
				dt * dissipation, temporal, new LiquidPolyhedron.Point(impulse[0], impulse[1], impulse[2]), work);
	}

	private void precondition(double[] error, double[] result, double dt) {
		for (int i = 0; i < error.length; i++) {
			double weight = diagonal[i] + mass[i] / dt; result[i] = weight > 0 ? error[i] / weight : 0;
		}
		projectFaces(result, false);
	}
	private void apply(double[] value, double[] output, double dt, boolean boundary) {
		System.arraycopy(value, 0, physical, 0, value.length); projectFaces(physical, boundary);
		Arrays.fill(output, 0); stress(physical, output);
		for (int i = 0; i < faceBase; i++) output[i] += mass[i] * physical[i] / dt;
		projectFaces(output, false);
	}
	private double stress(double[] value, double[] output) {
		double dissipation = 0;
		for (int c = 0; c < cells.size(); c++) {
			var g = geometry[c]; Arrays.fill(gradient, 0);
			for (int i = 0; i < g.indices.length; i++) for (int a = 0; a < 3; a++) {
				double delta = value[faceBase + 3 * g.indices[i] + a] - value[3 * c + a]; difference[c][3 * i + a] = delta;
				for (int b = 0; b < 3; b++) gradient[3 * a + b] += delta * g.basis[i].coordinate(b);
			}
			for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) {
				double strain = 0.5 * (gradient[3 * a + b] + gradient[3 * b + a]);
				consistency[3 * a + b] = 2 * g.volume * strain; dissipation += 2 * viscosity[c] * g.volume * strain * strain;
			}
			for (int i = 0; i < g.indices.length; i++) for (int a = 0; a < 3; a++) {
				double strainError = difference[c][3 * i + a];
				for (int b = 0; b < 3; b++) strainError -= gradient[3 * a + b] * g.displacement[i].coordinate(b);
				residual[c][3 * i + a] = strainError;
				dissipation += viscosity[c] * g.weight[i] * strainError * strainError;
				for (int b = 0; b < 3; b++) consistency[3 * a + b] -= g.weight[i] * strainError * g.displacement[i].coordinate(b);
			}
			for (int i = 0; i < g.indices.length; i++) for (int a = 0; a < 3; a++) {
				double force = g.weight[i] * residual[c][3 * i + a];
				for (int b = 0; b < 3; b++) force += g.basis[i].coordinate(b) * consistency[3 * a + b];
				force *= viscosity[c]; output[3 * c + a] -= force; output[faceBase + 3 * g.indices[i] + a] += force;
			}
		}
		return dissipation;
	}
	private void projectFaces(double[] value, boolean boundary) {
		for (int f = 0; f < faces.size(); f++) if (faces.get(f).second() == -2) {
			var n = normal[f]; int id = faceBase + 3 * f;
			double component = wall == FluidSolverConfig.WallCondition.NO_SLIP ? 0 : n.x() * value[id] + n.y() * value[id + 1] + n.z() * value[id + 2];
			for (int a = 0; a < 3; a++) value[id + a] = (wall == FluidSolverConfig.WallCondition.NO_SLIP ? 0 : value[id + a] - n.coordinate(a) * component)
					+ (boundary ? n.coordinate(a) * faces.get(f).prescribed() : 0);
		}
	}
	private void projectVector(double[] value, LiquidPolyhedron.Point n, boolean solid) {
		if (!solid) return;
		double component = n.x() * value[0] + n.y() * value[1] + n.z() * value[2];
		for (int a = 0; a < 3; a++) value[a] = wall == FluidSolverConfig.WallCondition.NO_SLIP ? 0 : value[a] - n.coordinate(a) * component;
	}
	private static double dot(double[] a, double[] b) { double sum = 0; for (int i = 0; i < a.length; i++) sum += a[i] * b[i]; return sum; }
	private static double maximum(double[] values) {
		double result = 0;
		for (double value : values) {
			if (!Double.isFinite(value)) throw new IllegalStateException("Liquid viscous residual overflow");
			result = Math.max(result, Math.abs(value));
		}
		return result;
	}
}
