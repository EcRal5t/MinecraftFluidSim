package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Sparse surface-energy Gram rows acting on conservative pressure volume rates. */
final class LiquidPressureFaceEnergy {
	record Row(int[] faces, double[] values) {
		Row {
			faces = faces.clone(); values = values.clone();
			if (faces.length != values.length) throw new IllegalArgumentException("Incomplete capillary energy row");
			for (double value : values) if (!Double.isFinite(value)) throw new IllegalStateException("Capillary energy row exceeds numerical range");
		}
	}
	final double[] gradient;
	final LiquidControlVolumeMesh mesh;
	private final List<Row> rows;
	LiquidPressureFaceEnergy(LiquidConformingVelocity field, LiquidAreaEnergy energy, LiquidSurfaceMetric metric) {
		mesh = field.mesh; gradient = new double[field.mesh.faces.size()]; var load = field.rateLoad(energy.force);
		for (int f = 0; f < gradient.length; f++) {
			if (field.mesh.faces.get(f).second() == -2 && field.mesh.faces.get(f).prescribed() != 0)
				throw new IllegalArgumentException("Capillary rate energy requires stationary prescribed walls");
			gradient[f] = -load[f].rounded(); if (!Double.isFinite(gradient[f])) throw new IllegalStateException("Capillary pressure energy exceeds numerical range");
		}
		rows = metric.pressureRows(field);
	}
	private LiquidPressureFaceEnergy(LiquidControlVolumeMesh mesh, double[] gradient, List<Row> rows) { this.mesh = mesh; this.gradient = gradient; this.rows = List.copyOf(rows); }
	/** Retains the phase projection's actual face ordering and orientation. */
	LiquidPressureFaceEnergy select(int[] faces, int[] signs) {
		var selected = new double[faces.length]; var inverse = new int[gradient.length]; Arrays.fill(inverse, -1);
		for (int i = 0; i < faces.length; i++) { selected[i] = signs[i] * gradient[faces[i]]; inverse[faces[i]] = i; }
		var result = new ArrayList<Row>();
		for (var row : rows) {
			var ids = new ArrayList<Integer>(); var values = new ArrayList<Double>();
			for (int i = 0; i < row.faces.length; i++) {
				int face = inverse[row.faces[i]];
				if (face < 0) throw new IllegalArgumentException("Capillary force has no liquid pressure face");
				ids.add(face); values.add(signs[face] * row.values[i]);
			}
			result.add(new Row(ids.stream().mapToInt(Integer::intValue).toArray(), values.stream().mapToDouble(Double::doubleValue).toArray()));
		}
		return new LiquidPressureFaceEnergy(mesh, selected, result);
	}
	double[] diagonal(double dt) {
		var result = new double[gradient.length];
		for (var row : rows) for (int i = 0; i < row.faces.length; i++) result[row.faces[i]] += dt * dt * row.values[i] * row.values[i];
		for (double value : result) if (!Double.isFinite(value)) throw new IllegalStateException("Capillary time-step inertia exceeds numerical range");
		return result;
	}
	void add(double[] flow, double[] flowError, double dt, boolean includeForce, double[] target, double[] error) {
		for (var row : rows) {
			double value = 0, magnitude = 0, bound = 0;
			for (int i = 0; i < row.faces.length; i++) {
				double term = row.values[i] * flow[row.faces[i]]; value += term; magnitude += Math.abs(term);
				if (flowError != null) bound += Math.abs(row.values[i]) * flowError[row.faces[i]];
			}
			double epsilon = Math.scalb(2.0 * row.faces.length, -53); bound += epsilon / (1 - epsilon) * magnitude;
			for (int i = 0; i < row.faces.length; i++) {
				int face = row.faces[i]; double coefficient = dt * dt * row.values[i], term = coefficient * value; target[face] += term;
				if (flowError != null) error[face] += Math.abs(coefficient) * bound + 4 * Math.ulp(term) + Math.ulp(target[face]);
			}
		}
		if (includeForce) for (int f = 0; f < gradient.length; f++) {
			double term = dt * gradient[f]; target[f] += term;
			if (flowError != null) error[f] += Math.ulp(term) + Math.ulp(target[f]);
		}
	}
	double[] pressure(double[] flow, double dt) {
		var result = new double[gradient.length]; add(flow, null, dt, true, result, null);
		for (int f = 0; f < result.length; f++) result[f] /= dt;
		return result;
	}
}
