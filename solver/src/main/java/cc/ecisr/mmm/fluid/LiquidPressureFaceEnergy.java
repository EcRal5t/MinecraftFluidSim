package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import java.util.List;

/** Sparse surface-energy Gram rows acting on conservative pressure volume rates. */
final class LiquidPressureFaceEnergy {
	record Row(int[] faces, double[] values) {
		Row {
			faces = faces.clone(); values = values.clone();
			if (faces.length != values.length) throw new IllegalArgumentException("Incomplete capillary energy row");
			for (int i = 0; i < faces.length; i++) if (faces[i] < 0 || i > 0 && faces[i] <= faces[i - 1]) throw new IllegalArgumentException("Capillary row faces must be unique and ordered");
			for (double value : values) if (!Double.isFinite(value)) throw new IllegalStateException("Capillary energy row exceeds numerical range");
		}
	}
	record Cross(Row first, Row second, double weight) {
		Cross { if (!Double.isFinite(weight)) throw new IllegalStateException("Wet-wall metric exceeds numerical range"); }
	}
	final double[] gradient;
	final LiquidControlVolumeMesh mesh;
	private final List<Row> rows;
	private final List<Cross> crosses;
	LiquidPressureFaceEnergy(LiquidConformingVelocity field, LiquidAreaEnergy energy, LiquidSurfaceMetric metric) {
		this(field, energy, metric, null);
	}
	LiquidPressureFaceEnergy(LiquidConformingVelocity field, LiquidAreaEnergy energy, LiquidSurfaceMetric metric, LiquidWetWallEnergy wall) {
		mesh = field.mesh; gradient = new double[field.mesh.faces.size()]; var load = field.rateLoad(energy.force);
		for (int f = 0; f < gradient.length; f++) {
			if (field.mesh.faces.get(f).second() == -2 && field.mesh.faces.get(f).prescribed() != 0)
				throw new IllegalArgumentException("Capillary rate energy requires stationary prescribed walls");
			gradient[f] = -load[f].rounded(); if (!Double.isFinite(gradient[f])) throw new IllegalStateException("Capillary pressure energy exceeds numerical range");
		}
		var map = new LiquidSurfaceRateMap(field); rows = metric.pressureRows(map); crosses = wall == null ? List.of() : wall.pressureCrosses(map);
	}
	private LiquidPressureFaceEnergy(LiquidControlVolumeMesh mesh, double[] gradient, List<Row> rows, List<Cross> crosses) {
		this.mesh = mesh; this.gradient = gradient; this.rows = List.copyOf(rows); this.crosses = List.copyOf(crosses);
	}
	boolean hasSignedMetric() { return !crosses.isEmpty(); }
	/** Row-sum bound on the signed wall Hessian's negative curvature. */
	double negativeCurvatureBound() {
		var bound = new double[gradient.length];
		for (var cross : crosses) {
			double firstNorm = 0, secondNorm = 0;
			for (double value : cross.first.values) firstNorm = Math.nextUp(firstNorm + Math.abs(value));
			for (double value : cross.second.values) secondNorm = Math.nextUp(secondNorm + Math.abs(value));
			for (int i = 0; i < cross.first.faces.length; i++) bound[cross.first.faces[i]] = Math.nextUp(bound[cross.first.faces[i]] + Math.nextUp(Math.nextUp(Math.abs(cross.weight * cross.first.values[i])) * secondNorm));
			for (int i = 0; i < cross.second.faces.length; i++) bound[cross.second.faces[i]] = Math.nextUp(bound[cross.second.faces[i]] + Math.nextUp(Math.nextUp(Math.abs(cross.weight * cross.second.values[i])) * firstNorm));
		}
		return Arrays.stream(bound).max().orElse(0);
	}
	/** Retains the phase projection's actual face ordering and orientation. */
	LiquidPressureFaceEnergy select(int[] faces, int[] signs) {
		var selected = new double[faces.length]; var inverse = new int[gradient.length]; Arrays.fill(inverse, -1);
		for (int i = 0; i < faces.length; i++) { selected[i] = signs[i] * gradient[faces[i]]; inverse[faces[i]] = i; }
		var result = rows.stream().map(row -> select(row, inverse, signs)).toList();
		var pairs = crosses.stream().map(cross -> new Cross(select(cross.first, inverse, signs), select(cross.second, inverse, signs), cross.weight)).toList();
		return new LiquidPressureFaceEnergy(mesh, selected, result, pairs);
	}
	private static Row select(Row row, int[] inverse, int[] signs) {
		var values = new java.util.TreeMap<Integer, Double>();
		for (int i = 0; i < row.faces.length; i++) {
			int face = inverse[row.faces[i]]; if (face < 0) throw new IllegalArgumentException("Capillary force has no liquid pressure face");
			values.put(face, signs[face] * row.values[i]);
		}
		return new Row(values.keySet().stream().mapToInt(Integer::intValue).toArray(), values.values().stream().mapToDouble(Double::doubleValue).toArray());
	}
	double[] diagonal(double dt) {
		var result = new double[gradient.length];
		for (var row : rows) for (int i = 0; i < row.faces.length; i++) result[row.faces[i]] += dt * dt * row.values[i] * row.values[i];
		for (var cross : crosses) {
			int i = 0, j = 0;
			while (i < cross.first.faces.length && j < cross.second.faces.length) {
				int first = cross.first.faces[i], second = cross.second.faces[j];
				if (first == second) result[first] += 2 * dt * dt * cross.weight * cross.first.values[i] * cross.second.values[j];
				if (first <= second) i++; if (second <= first) j++;
			}
		}
		for (double value : result) if (!Double.isFinite(value)) throw new IllegalStateException("Capillary time-step inertia exceeds numerical range");
		return result;
	}
	void add(double[] flow, double[] flowError, double dt, boolean includeForce, double[] target, double[] error) {
		for (var row : rows) {
			add(row, dot(row, flow, flowError), dt * dt, target, flowError == null ? null : error);
		}
		for (var cross : crosses) {
			add(cross.first, dot(cross.second, flow, flowError), dt * dt * cross.weight, target, flowError == null ? null : error);
			add(cross.second, dot(cross.first, flow, flowError), dt * dt * cross.weight, target, flowError == null ? null : error);
		}
		if (includeForce) for (int f = 0; f < gradient.length; f++) {
			double term = dt * gradient[f]; target[f] += term;
			if (flowError != null) error[f] += Math.ulp(term) + Math.ulp(target[f]);
		}
	}
	private record Dot(double value, double error) { }
	private static Dot dot(Row row, double[] flow, double[] error) {
		double value = 0, magnitude = 0, bound = 0;
		for (int i = 0; i < row.faces.length; i++) {
			double term = row.values[i] * flow[row.faces[i]]; value += term; magnitude += Math.abs(term);
			if (error != null) bound += Math.abs(row.values[i]) * error[row.faces[i]];
		}
		double epsilon = Math.scalb(2.0 * row.faces.length, -53);
		return new Dot(value, bound + epsilon / (1 - epsilon) * magnitude);
	}
	private static void add(Row row, Dot value, double scale, double[] target, double[] error) {
		for (int i = 0; i < row.faces.length; i++) {
			int face = row.faces[i]; double coefficient = scale * row.values[i], term = coefficient * value.value; target[face] += term;
			if (error != null) error[face] += Math.abs(coefficient) * value.error + 4 * Math.ulp(term) + Math.ulp(target[face]);
		}
	}
	double[] pressure(double[] flow, double dt) {
		var result = new double[gradient.length]; add(flow, null, dt, true, result, null);
		for (int f = 0; f < result.length; f++) result[f] /= dt;
		return result;
	}
}
