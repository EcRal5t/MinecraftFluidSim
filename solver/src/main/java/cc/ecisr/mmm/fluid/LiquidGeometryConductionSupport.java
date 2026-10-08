package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.thermal.ConductionInventory;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;
import cc.ecisr.mmm.thermal.ThermalContactExchange;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.ExactVolume;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Immutable liquid/gas material support for the shared control-volume conduction equation. */
record LiquidGeometryConductionSupport(List<ThermalContactExchange.Body> bodies,
		List<ControlVolumeThermalMetric.Portion> portions, int finiteInventories) {
	LiquidGeometryConductionSupport { bodies = List.copyOf(bodies); portions = List.copyOf(portions); }

	static LiquidGeometryConductionSupport map(LiquidGeometryPhaseInput phase, ConductionInventory captured,
			ThermalCarrierContext markers, ThermalRegistry materials, AirNetwork network, double ambientTemperature) {
		if (phase.gasGeometry == null || network == null || phase.gasGeometry.nodeCount() != network.nodeCount()
				|| markers.markerCount() != captured.liquidBodies().size())
			throw new IllegalArgumentException("Incomplete geometric conduction inventory");
		var labels = ResolvedGasGeometry.requireCompatible(phase.gasGeometry, phase, 0);
		int[] markerOwners = phase.liquidGeometry.copyMarkerCells(), fragmentOwners = phase.liquidGeometry.copyFragmentOwners();
		if (markerOwners.length != markers.markerCount()) throw new IllegalArgumentException("Conduction marker geometry is incomplete");
		var inventories = new TreeMap<Integer, ArrayList<Integer>>(); var volumes = new TreeMap<Integer, ExactVolume>();
		for (int p = 0; p < markerOwners.length; p++) {
			var volume = markers.exactVolume(p);
			if (volume == null || volume.signum() <= 0) throw new IllegalArgumentException("Conduction material volume is invalid");
			inventories.computeIfAbsent(markerOwners[p], ignored -> new ArrayList<>()).add(p);
			volumes.merge(markerOwners[p], volume, ExactVolume::add);
		}
		var bodies = new ArrayList<>(captured.bodies()); var portions = new ArrayList<ControlVolumeThermalMetric.Portion>();
		int ambient = -1;
		for (int c = 0; c < phase.fragments.length; c++) {
			int fragment = phase.fragments[c];
			if (fragment >= 0) {
				int owner = fragmentOwners[fragment]; var stocks = inventories.get(owner);
				if (stocks == null) throw new IllegalArgumentException("Liquid conduction geometry lacks caloric stock");
				var total = Fraction.of(volumes.get(owner));
				for (int p : stocks) {
					double conductivity = Fraction.of(markers.exactVolume(p)).divide(total)
							.multiply(Fraction.of(captured.liquidBodies().get(p).material().conductivityEuPerSecondMeterK())).rounded();
					portions.add(new ControlVolumeThermalMetric.Portion(c, p, conductivity));
				}
			} else {
				int node = labels[c];
				if (node == AirNetwork.AMBIENT_NODE) {
					if (ambient < 0) { ambient = bodies.size(); bodies.add(new ThermalContactExchange.Reservoir(ambientTemperature)); }
					for (var part : network.registry().ambient())
						materialPortion(c, ambient, part.componentId(), new Fraction(part.fraction().numerator(), part.fraction().denominator()), materials, portions);
				} else {
					var inventory = network.inventory(node);
					if (inventory.isEmpty()) { portions.add(new ControlVolumeThermalMetric.Portion(c, -1, 0)); continue; }
					int ordinal = captured.gasOrdinal(node);
					if (ordinal < 0) throw new IllegalArgumentException("Finite gas conduction stock is missing");
					var total = Fraction.of(SignedDyadic.of(inventory.standardVolumeNanos(), 0));
					for (var component : inventory.components())
						materialPortion(c, ordinal, component.componentId(), Fraction.of(SignedDyadic.of(component.standardVolumeNanos(), 0)).divide(total), materials, portions);
				}
			}
		}
		return new LiquidGeometryConductionSupport(bodies, portions, captured.bodies().size());
	}
	private static void materialPortion(int cell, int ordinal, String component, Fraction fraction, ThermalRegistry materials,
			List<ControlVolumeThermalMetric.Portion> portions) {
		var material = materials.material(materials.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS, component));
		portions.add(new ControlVolumeThermalMetric.Portion(cell, ordinal, fraction.multiply(Fraction.of(material.conductivityEuPerSecondMeterK())).rounded()));
	}
}
