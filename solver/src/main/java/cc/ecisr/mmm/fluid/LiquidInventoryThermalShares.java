package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.ExactVolume;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Transports extensive internal energy; caloric inversion observes each complete local stock. */
final class LiquidInventoryThermalShares {
	private LiquidInventoryThermalShares() { }
	static void accumulate(FluidDomain domain, int marker, ExactVolume[] weights, LiquidInventoryStock[] targets) {
		if (domain.thermalState == null) return;
		var volume = Fraction.of(domain.particles.exactVolume(marker));
		var energy = Fraction.of(domain.thermalState.liquidInternalEnergy(marker));
		double temperature = domain.thermalState.temperature(marker, domain.thermalMarkers);
		for (int q = 0; q < targets.length; q++) {
			var stock = targets[q]; stock.energy = stock.energy.add(energy.multiply(Fraction.of(weights[q])).divide(volume));
			stock.minimumTemperature = Math.min(stock.minimumTemperature, temperature);
			stock.maximumTemperature = Math.max(stock.maximumTemperature, temperature);
		}
	}
	static double temperatureChange(FluidDomain domain, LiquidInventoryStock stock, LiquidInventoryRepartitionPlan.Marker marker) {
		if (domain.thermalState == null) return 0;
		var chemistry = domain.chemicalParcels == null ? null : domain.chemicalParcels.registry();
		double temperature = domain.thermalState.liquid().temperatureForInventory(marker.material(), marker.mass(), marker.mixture(), marker.energy(), chemistry);
		return Math.max(Math.abs(temperature - stock.minimumTemperature), Math.abs(temperature - stock.maximumTemperature));
	}
}
