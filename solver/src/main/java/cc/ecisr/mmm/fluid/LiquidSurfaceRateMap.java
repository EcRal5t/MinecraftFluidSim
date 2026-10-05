package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Request-owned sparse nodal velocity derivatives reused by all surface-energy terms. */
final class LiquidSurfaceRateMap {
	private final LiquidConformingVelocity field;
	private final Map<Vertex, List<Map<Integer, Fraction>>> nodes = new LinkedHashMap<>();
	LiquidSurfaceRateMap(LiquidConformingVelocity field) { this.field = field; }
	LiquidPressureFaceEnergy.Row row(Map<Vertex, List<Fraction>> forces, double scale) {
		var result = new java.util.TreeMap<Integer, Fraction>();
		for (var entry : forces.entrySet()) for (int a = 0; a < 3; a++) {
			var value = entry.getValue().get(a); if (value.signum() == 0) continue;
			var derivatives = nodes.computeIfAbsent(entry.getKey(), this::derive).get(a);
			for (var term : derivatives.entrySet()) result.merge(term.getKey(), term.getValue().multiply(value), Fraction::add);
		}
		result.entrySet().removeIf(entry -> entry.getValue().signum() == 0);
		return new LiquidPressureFaceEnergy.Row(result.keySet().stream().mapToInt(Integer::intValue).toArray(), result.values().stream().mapToDouble(value -> scale * value.rounded()).toArray());
	}
	LiquidPressureFaceEnergy.Row axis(Vertex node, int axis) {
		var force = new Fraction[3]; Arrays.fill(force, Fraction.ZERO); force[axis] = Fraction.of(1);
		return row(Map.of(node, List.of(force)), 1);
	}
	private List<Map<Integer, Fraction>> derive(Vertex node) {
		var result = new java.util.ArrayList<Map<Integer, Fraction>>();
		for (int a = 0; a < 3; a++) {
			var force = new Fraction[3]; Arrays.fill(force, Fraction.ZERO); force[a] = Fraction.of(1);
			var load = field.rateLoad(Map.of(node, List.of(force))); var sparse = new LinkedHashMap<Integer, Fraction>();
			for (int f = 0; f < load.length; f++) if (load[f].signum() != 0 && field.mesh.faces.get(f).second() != -2) sparse.put(f, load[f]);
			result.add(java.util.Collections.unmodifiableMap(sparse));
		}
		return List.copyOf(result);
	}
}
