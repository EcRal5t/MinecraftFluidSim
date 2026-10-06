package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import java.util.List;
import cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.Value;
import static cc.ecisr.mmm.fluid.LiquidBoundedArithmetic.*;

/** Sparse surface-energy Gram rows acting on conservative pressure volume rates. */
final class LiquidPressureFaceEnergy {
	record Row(int[] faces, double[] values, double[] errors) {
		Row {
			faces = faces.clone(); values = values.clone(); errors = errors.clone();
			if (faces.length != values.length || faces.length != errors.length) throw new IllegalArgumentException("Incomplete capillary energy row");
			for (int i = 0; i < faces.length; i++) if (faces[i] < 0 || i > 0 && faces[i] <= faces[i - 1]) throw new IllegalArgumentException("Capillary row faces must be unique and ordered");
			for (int i = 0; i < values.length; i++) new Value(values[i], errors[i]);
		}
		Value coefficient(int i) { return new Value(values[i], errors[i]); }
	}
	record Cross(Row first, Row second, Value weight) { }
	final double[] gradient;
	final double[] gradientError;
	final LiquidControlVolumeMesh mesh;
	final LiquidConformingVelocity reference;
	private final List<Row> rows;
	private final List<Cross> crosses;
	private final LiquidSurfaceMetric metric;
	private final LiquidWetWallEnergy wall;
	private final int[] projectionFaces, projectionSigns;
	LiquidPressureFaceEnergy(LiquidConformingVelocity field, LiquidAreaEnergy energy, LiquidSurfaceMetric metric) {
		this(field, energy, metric, null);
	}
	LiquidPressureFaceEnergy(LiquidConformingVelocity field, LiquidAreaEnergy energy, LiquidSurfaceMetric metric, LiquidWetWallEnergy wall) {
		this.metric = metric; this.wall = wall;
		mesh = field.mesh; reference = field; gradient = new double[field.mesh.faces.size()]; gradientError = gradient.clone(); var load = field.observedRateLoad(energy.force);
		projectionFaces = java.util.stream.IntStream.range(0, gradient.length).toArray(); projectionSigns = new int[gradient.length]; Arrays.fill(projectionSigns, 1);
		for (int f = 0; f < gradient.length; f++) {
			if (field.mesh.faces.get(f).second() == -2 && field.mesh.faces.get(f).prescribed() != 0)
				throw new IllegalArgumentException("Capillary rate energy requires stationary prescribed walls");
			gradient[f] = -load[f].value(); gradientError[f] = load[f].error();
		}
		var map = new LiquidSurfaceRateMap(field); rows = metric.pressureRows(map); crosses = wall == null ? List.of() : wall.pressureCrosses(map);
	}
	private LiquidPressureFaceEnergy(LiquidControlVolumeMesh mesh, double[] gradient, double[] gradientError, List<Row> rows, List<Cross> crosses, LiquidConformingVelocity reference,
			LiquidSurfaceMetric metric, LiquidWetWallEnergy wall, int[] projectionFaces, int[] projectionSigns) {
		this.mesh = mesh; this.reference = reference; this.gradient = gradient; this.gradientError = gradientError; this.rows = List.copyOf(rows); this.crosses = List.copyOf(crosses);
		this.metric = metric; this.wall = wall; this.projectionFaces = projectionFaces; this.projectionSigns = projectionSigns;
	}
	boolean hasSignedMetric() { return !crosses.isEmpty(); }
	/** Row-sum bound on the signed wall Hessian's negative curvature. */
	double negativeCurvatureBound() {
		var bound = new double[gradient.length];
		for (var cross : crosses) {
			double firstNorm = 0, secondNorm = 0;
			for (int i = 0; i < cross.first.values.length; i++) firstNorm = up(firstNorm + cross.first.coefficient(i).magnitude());
			for (int i = 0; i < cross.second.values.length; i++) secondNorm = up(secondNorm + cross.second.coefficient(i).magnitude());
			for (int i = 0; i < cross.first.faces.length; i++) bound[cross.first.faces[i]] = up(bound[cross.first.faces[i]] + up(up(cross.weight.magnitude() * cross.first.coefficient(i).magnitude()) * secondNorm));
			for (int i = 0; i < cross.second.faces.length; i++) bound[cross.second.faces[i]] = up(bound[cross.second.faces[i]] + up(up(cross.weight.magnitude() * cross.second.coefficient(i).magnitude()) * firstNorm));
		}
		return Arrays.stream(bound).max().orElse(0);
	}
	/** Retains the phase projection's actual face ordering and orientation. */
	LiquidPressureFaceEnergy select(int[] faces, int[] signs) {
		var selected = new double[faces.length]; var selectedError = selected.clone(); var inverse = new int[gradient.length]; Arrays.fill(inverse, -1);
		var originalFaces = new int[faces.length]; var originalSigns = originalFaces.clone();
		for (int i = 0; i < faces.length; i++) { selected[i] = signs[i] * gradient[faces[i]]; selectedError[i] = gradientError[faces[i]]; inverse[faces[i]] = i; }
		for (int i = 0; i < faces.length; i++) { originalFaces[i] = projectionFaces[faces[i]]; originalSigns[i] = signs[i] * projectionSigns[faces[i]]; }
		var result = rows.stream().map(row -> select(row, inverse, signs)).toList();
		var pairs = crosses.stream().map(cross -> new Cross(select(cross.first, inverse, signs), select(cross.second, inverse, signs), cross.weight)).toList();
		return new LiquidPressureFaceEnergy(mesh, selected, selectedError, result, pairs, reference, metric, wall, originalFaces, originalSigns);
	}
	private static Row select(Row row, int[] inverse, int[] signs) {
		var values = new java.util.TreeMap<Integer, Value>();
		for (int i = 0; i < row.faces.length; i++) {
			int face = inverse[row.faces[i]]; if (face < 0) throw new IllegalArgumentException("Capillary force has no liquid pressure face");
			values.put(face, new Value(signs[face] * row.values[i], row.errors[i]));
		}
		return new Row(values.keySet().stream().mapToInt(Integer::intValue).toArray(), values.values().stream().mapToDouble(Value::value).toArray(), values.values().stream().mapToDouble(Value::error).toArray());
	}
	double[] diagonal(double dt) {
		var result = new double[gradient.length];
		for (var row : rows) for (int i = 0; i < row.faces.length; i++) result[row.faces[i]] += dt * dt * row.values[i] * row.values[i];
		for (var cross : crosses) {
			int i = 0, j = 0;
			while (i < cross.first.faces.length && j < cross.second.faces.length) {
				int first = cross.first.faces[i], second = cross.second.faces[j];
				if (first == second) result[first] += 2 * dt * dt * cross.weight.value() * cross.first.values[i] * cross.second.values[j];
				if (first <= second) i++; if (second <= first) j++;
			}
		}
		for (double value : result) if (!Double.isFinite(value)) throw new IllegalStateException("Capillary time-step inertia exceeds numerical range");
		return result;
	}
	void add(double[] flow, double[] flowError, double dt, boolean includeForce, double[] target, double[] error) {
		var time = new Value(dt, 0); var square = time.multiply(time); var bounds = flowError == null ? null : error;
		var rate = new Value[mesh.faces.size()]; Arrays.fill(rate, ZERO);
		for (int i = 0; i < flow.length; i++) rate[projectionFaces[i]] = new Value(projectionSigns[i] * flow[i], flowError == null ? 0 : flowError[i]);
		var operator = reference.rateOperator(); var velocity = operator.velocity(rate); var forces = metric.observedGradient(velocity);
		if (wall != null) wall.observedGradient(velocity).forEach((point, force) -> {
			var combined = forces.computeIfAbsent(point, ignored -> new Value[]{ZERO, ZERO, ZERO});
			for (int a = 0; a < 3; a++) combined[a] = combined[a].add(force[a]);
		});
		var load = operator.applyObserved(forces);
		for (int i = 0; i < flow.length; i++) {
			var value = load[projectionFaces[i]]; if (projectionSigns[i] < 0) value = value.negate();
			accumulate(i, square.multiply(value), target, bounds);
			if (includeForce) accumulate(i, time.multiply(new Value(gradient[i], gradientError[i])), target, bounds);
		}
	}
	private static void accumulate(int face, Value term, double[] target, double[] error) {
		if (error == null) target[face] += term.value();
		else { var result = new Value(target[face], error[face]).add(term); target[face] = result.value(); error[face] = result.error(); }
	}
	double[] pressure(double[] flow, double dt) {
		var result = new double[gradient.length]; add(flow, null, dt, true, result, null);
		for (int f = 0; f < result.length; f++) result[f] /= dt;
		return result;
	}
}
