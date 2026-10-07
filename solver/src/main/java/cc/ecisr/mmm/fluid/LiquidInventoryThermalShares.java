package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.thermal.LiquidEnergyColumn;
import java.util.ArrayList;

/** Caloric reconstruction preserves source temperatures within state-function roundoff. */
final class LiquidInventoryThermalShares {
	private LiquidInventoryThermalShares() { }
	static double prepare(FluidDomain domain, ArrayList<LiquidInventoryRepartitionPlan.Marker> markers) {
		if (domain.thermalState == null) return 0;
		double maximumChange = 0;
		for (int start = 0; start < markers.size();) {
			int source = markers.get(start).source(), end = start + 1;
			while (end < markers.size() && markers.get(end).source() == source) end++;
			double temperature = domain.thermalState.temperature(source, domain.thermalMarkers);
			var chemistry = domain.chemicalParcels == null ? null : domain.chemicalParcels.registry();
			var caloric = new LiquidEnergyColumn.CaloricInventory[end - start];
			var residual = SignedDyadic.of(domain.thermalState.liquidInternalEnergy(source)); int largest = start;
			double rounding = domain.thermalState.liquid().modeledEnergyRoundoff(domain.particles.materialHandle(source), domain.particles.mass(source),
					domain.chemicalParcels == null ? null : domain.chemicalParcels.record(source), temperature, chemistry);
			for (int q = start; q < end; q++) {
				var marker = markers.get(q);
				caloric[q - start] = domain.thermalState.liquid().caloricInventory(domain.particles.materialHandle(source), marker.mass(), marker.mixture(), chemistry);
				double energy = caloric[q - start].energy(temperature); rounding = Math.nextUp(rounding + caloric[q - start].roundoff(temperature));
				markers.set(q, marker.withEnergy(energy)); residual = residual.subtract(SignedDyadic.of(energy));
				if (marker.mass() > markers.get(largest).mass()) largest = q;
			}
			if (!Double.isFinite(rounding) || Math.abs(residual.toDouble()) > rounding)
				throw new IllegalStateException("Local caloric reconstruction exceeds state-function roundoff");
			var marker = markers.get(largest); double energy = SignedDyadic.of(marker.energy()).add(residual).toDouble();
			markers.set(largest, marker.withEnergy(energy));
			for (int q = start; q < end; q++) maximumChange = Math.max(maximumChange, Math.abs(caloric[q - start].temperature(markers.get(q).energy()) - temperature));
			start = end;
		}
		return maximumChange;
	}
}
