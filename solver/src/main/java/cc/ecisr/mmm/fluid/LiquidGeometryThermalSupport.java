package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;
import cc.ecisr.mmm.units.SignedDyadic;

/** Read-only caloric support integrated over the submitted liquid inventory geometry. */
final class LiquidGeometryThermalSupport {
	private record Portion(int cell, double weight) { }
	private LiquidGeometryState input;
	private int[] markers = new int[0];
	private Map<Integer, java.util.List<Portion>> support = Map.of();

	void prepare(FluidDomainStepCoordinator domain) {
		var state = domain.liquidGeometryState;
		if (state == input) return;
		if (state == null) { input = null; markers = new int[0]; support = Map.of(); return; }
		var volumes = LiquidGeometryInventorySupport.volumesByOwner(domain);
		var next = new HashMap<Integer, java.util.List<Portion>>();
		for (var group : volumes.entrySet()) {
			var total = Fraction.ZERO; for (var volume : group.getValue().values()) total = total.add(volume);
			var portions = new ArrayList<Portion>();
			var sum = SignedDyadic.ZERO; var roundoff = SignedDyadic.ZERO;
			for (var entry : group.getValue().entrySet()) {
				double weight = entry.getValue().divide(total).rounded();
				if (!(weight > 0) || !Double.isFinite(weight))
					throw new IllegalStateException("Geometric thermal support exceeds its observation precision");
				portions.add(new Portion(entry.getKey(), weight));
				sum = sum.add(SignedDyadic.of(weight)); roundoff = roundoff.add(SignedDyadic.of(Math.ulp(weight)));
			}
			var error = sum.subtract(SignedDyadic.of(1));
			if (error.compareTo(roundoff) > 0 || error.compareTo(roundoff.negate()) < 0)
				throw new IllegalStateException("Geometric caloric weights exceed their observation roundoff");
			next.put(group.getKey(), java.util.List.copyOf(portions));
		}
		markers = state.copyMarkerCells(); support = Map.copyOf(next); input = state;
	}
	int size(int marker) { return support.get(markers[marker]).size(); }
	void observe(int marker, ThermalCarrierContext.SupportConsumer consumer) {
		for (var part : support.get(markers[marker])) consumer.accept(part.cell, part.weight);
	}
}
