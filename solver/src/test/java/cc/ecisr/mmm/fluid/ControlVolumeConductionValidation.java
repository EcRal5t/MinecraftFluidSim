package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalContactExchange;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Caloric closure, conservative face exchange and time/space refinement on resolved volumes. */
final class ControlVolumeConductionValidation {
	static void validate() {
		var leaves = List.of(new LiquidGeometryRefinement.Cell(1, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 0.5, 0.5, 0.5)),
				new LiquidGeometryRefinement.Cell(1, 1, 0, 0, LiquidPolyhedron.box(0.5, 0, 0, 1, 0.5, 0.5)));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1, (c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var prescribed = new double[mesh.faces.size()]; Arrays.fill(prescribed, Double.NaN);
		var first = material("test:thermal/first", 3, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 400, 2)));
		var second = material("test:thermal/second", 6, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 400, 3)));
		var bodies = List.of(carrier(first, 280), carrier(second, 320));
		prescribedInventoryAndVacuum(mesh, first, prescribed);
		independentCaloricPrecision(first, second);
		var operator = new ControlVolumeConduction(mesh, new double[]{3, 6});
		var result = operator.advance(bodies, prescribed, 1, 1e-10, 256);
		// Harmonic conductivity 4, area 1/4, centre distance 1/2: G = 2.
		double q = 2 * 40 / (1 + 2 * (0.5 + 1.0 / 3));
		close(result.energyChange()[0], q, 1e-9); close(result.energyChange()[1], -q, 1e-9);
		close(result.temperature()[0], 280 + q / 2, 1e-9); close(result.temperature()[1], 320 - q / 3, 1e-9);
		closure(result);
		var permuted = new ControlVolumeConduction(mesh, new int[]{1, 0}, 2, new double[]{3, 6})
				.advance(List.of(bodies.get(1), bodies.get(0)), prescribed, 1, 1e-10, 256);
		close(permuted.energyChange()[1], result.energyChange()[0], 1e-9);
		close(permuted.energyChange()[0], result.energyChange()[1], 1e-9); closure(permuted);
		sharedCaloricOwnerRetainsOneCapacity(mesh, first);
		try { operator.advance(bodies, prescribed, 1, 1e-10, 1); throw new AssertionError("Insufficient conduction budget committed a partial solve"); }
		catch (IllegalStateException expected) { }
		var retried = operator.advance(bodies, prescribed, 1, 1e-10, 256);
		if (!Arrays.equals(result.energyChange(), retried.energyChange())) throw new AssertionError("Rejected thermal work changed the next candidate");
		var segmented = material("test:thermal/segmented", 3, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 300, 2),
				new ThermalMaterialDefinition.CaloricSegment(300, 400, 8)));
		var hot = material("test:thermal/hot", 6, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 310, 3),
				new ThermalMaterialDefinition.CaloricSegment(310, 400, 6)));
		result = operator.advance(List.of(carrier(segmented, 280), carrier(hot, 320)), prescribed, 10, 1e-10, 512);
		q = 700 / (1 + 20 * (1.0 / 8 + 1.0 / 3));
		close(result.energyChange()[0], q, 1e-9); close(result.energyChange()[1], -q, 1e-9);
		close(result.temperature()[0], 300 + (q - 40) / 8, 1e-9); close(result.temperature()[1], 310 - (q - 60) / 3, 1e-9); closure(result);
		for (int f = 0; f < prescribed.length; f++) if (mesh.faces.get(f).second() < 0) prescribed[f] = 350;
		result = operator.advance(List.of(carrier(segmented, 280), carrier(hot, 340)), prescribed, 2, 1e-10, 512);
		if (!(result.reservoirEnergy().signum() > 0 && result.temperature()[0] > 280 && result.temperature()[1] > 340
				&& result.temperature()[0] <= 350 && result.temperature()[1] <= 350)) throw new AssertionError("Resolved thermal reservoir failed finite caloric closure");
		closure(result);
		Arrays.fill(prescribed, Double.NaN);
		result = new ControlVolumeConduction(mesh, new double[]{0, 0}).advance(bodies, prescribed, 1, 1e-10, 256);
		if (result.iterations() != 0 || result.energyChange()[0] != 0 || result.energyChange()[1] != 0) throw new AssertionError("Insulating caloric cells gained heat");
		result = new ControlVolumeConduction(mesh, new double[]{0, 6}).advance(bodies, prescribed, 1, 1e-10, 256);
		if (result.energyChange()[0] != 0 || result.energyChange()[1] != 0)
			throw new AssertionError("Face residual correction exchanged heat with an insulating neighbour");
		cooccupiedCaloricInventories();
		obliqueCaloricInterfacePreservesSteadyFlux();
		 temporalAndSpatialFourierRefinement();
	}
	private static void independentCaloricPrecision(ThermalMaterialDefinition first, ThermalMaterialDefinition second) {
		var regions = List.of(new LiquidGeometryRefinement.Cell(2, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, .25, .25, .25)),
				new LiquidGeometryRefinement.Cell(2, 1, 0, 0, LiquidPolyhedron.box(.25, 0, 0, .5, .25, .25)),
				new LiquidGeometryRefinement.Cell(2, 3, 0, 0, LiquidPolyhedron.box(.75, 0, 0, 1, .25, .25)));
		var mesh = new LiquidControlVolumeMesh(regions, 2, 1, (c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var insulated = material("test:thermal/large_insulated", 0, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 400, 1e20)));
		var bodies = List.of(carrier(first, 280), carrier(second, 320), carrier(insulated, 300));
		var prescribed = new double[mesh.faces.size()]; Arrays.fill(prescribed, Double.NaN);
		var result = new ControlVolumeConduction(mesh, new double[]{3, 6, 0})
				.advanceInventories(bodies, prescribed, 1, new double[]{1e-10, 1e-10, 1e8}, 512);
		double q = 80 / (1 + 2 * (.5 + 1.0 / 3));
		close(result.energyChange()[0], q, 1e-9); close(result.energyChange()[1], -q, 1e-9);
		if (result.energyChange()[2] != 0) throw new AssertionError("Insulated large inventory weakened another inventory's caloric precision");
		closure(result);
	}
	private static void prescribedInventoryAndVacuum(LiquidControlVolumeMesh mesh, ThermalMaterialDefinition first, double[] prescribed) {
		var result = new ControlVolumeConduction(mesh, new double[]{3, 6})
				.advance(List.of(carrier(first, 280), new ThermalContactExchange.Reservoir(320)), prescribed, 1, 1e-10, 256);
		// G = 2 W/K and the finite capacity is 2 J/K; the reservoir remains at 320 K.
		close(result.energyChange()[0], 40, 1e-9); close(result.energyChange()[1], 0, 0);
		close(result.temperature()[0], 300, 1e-9); close(result.temperature()[1], 320, 0);
		close(result.reservoirEnergy().toDouble(), 40, 1e-9); closure(result);
		result = new ControlVolumeConduction(mesh, new int[]{0, -1}, 1, new double[]{3, 0})
				.advance(List.of(carrier(first, 280)), prescribed, 1, 1e-10, 256);
		if (result.energyChange()[0] != 0 || result.temperature()[0] != 280)
			throw new AssertionError("Conduction exchanged heat with a vacuum without a material heat capacity");
		for (int f = 0; f < mesh.faces.size(); f++) if (mesh.faces.get(f).first() == 1 && mesh.faces.get(f).second() < 0
				&& !Double.isNaN(result.faceTemperature()[f])) throw new AssertionError("Vacuum surface acquired a material temperature");
		closure(result);
		result = new ControlVolumeConduction(mesh, new int[]{-1, -1}, 0, new double[]{0, 0})
				.advance(List.of(), prescribed, 1, 1e-10, 256);
		if (result.energyChange().length != 0 || result.iterations() != 0
				|| Arrays.stream(result.faceTemperature()).anyMatch(Double::isFinite))
			throw new AssertionError("Pure vacuum acquired finite caloric unknowns or surface temperatures");
		closure(result);
		try { new ControlVolumeThermalMetric.Portion(0, -1, 1); throw new AssertionError("Vacuum acquired material conductivity"); }
		catch (IllegalArgumentException expected) { }
	}
	private static void cooccupiedCaloricInventories() {
		var mesh = new LiquidControlVolumeMesh(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1))),
				1, 1, (c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var cold = material("test:thermal/mixed_cold", 12, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 400, 2)));
		var hot = material("test:thermal/mixed_hot", 8, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 400, 3)));
		var bodies = List.of(carrier(cold, 280), carrier(hot, 320));
		var prescribed = new double[mesh.faces.size()]; Arrays.fill(prescribed, Double.NaN);
		// Occupied fractions 1/4 and 3/4 give conductivity portions 3 and 6.
		var portions = List.of(new ControlVolumeThermalMetric.Portion(0, 0, 3), new ControlVolumeThermalMetric.Portion(0, 1, 6));
		var operator = new ControlVolumeConduction(mesh, 2, portions);
		var result = operator.advance(bodies, prescribed, 1, 1e-10, 512);
		// Six unit-area faces at centre distance 1/2 give 12 * (3*6)/(3+6) = 24 W/K.
		double q = 24 * 40 / (1 + 24 * (0.5 + 1.0 / 3));
		close(result.energyChange()[0], q, 1e-9); close(result.energyChange()[1], -q, 1e-9); closure(result);
		double previous = Double.POSITIVE_INFINITY;
		for (int steps : new int[]{1, 2, 4, 8}) {
			var current = new java.util.ArrayList<>(bodies);
			for (int step = 0; step < steps; step++) {
				result = operator.advance(current, prescribed, 0.02 / steps, 1e-10, 512); closure(result);
				for (int p = 0; p < current.size(); p++) {
					var old = current.get(p); current.set(p, new ThermalContactExchange.Carrier(old.material(), old.massKilograms(), old.baseEnergyEu(), old.internalEnergyEu() + result.energyChange()[p]));
				}
			}
			double difference = current.get(1).temperatureK() - current.get(0).temperatureK();
			double error = Math.abs(difference - 40 * Math.exp(-20 * 0.02));
			if (error >= previous * 0.65) throw new AssertionError("Cooccupied caloric inventories failed time refinement"); previous = error;
		}
		for (double scale : new double[]{0, 1e-6, 1e-5}) {
			result = new ControlVolumeConduction(mesh, 2, List.of(new ControlVolumeThermalMetric.Portion(0, 0, 3 * scale),
					new ControlVolumeThermalMetric.Portion(0, 1, 6 * scale))).advance(bodies, prescribed, 1, 1e-10, 512);
			close(result.energyChange()[0], 24 * scale * 40 / (1 + 20 * scale), 1e-9); closure(result);
			if (scale == 0 && (result.energyChange()[0] != 0 || result.energyChange()[1] != 0))
				throw new AssertionError("Zero conductivity erased independent cooccupied temperatures");
		}
		Arrays.fill(prescribed, 350);
		var whole = carrier(cold, 280);
		var fractionBodies = List.of(new ThermalContactExchange.Carrier(cold, 0.25, 256, whole.internalEnergyEu() * 0.25),
				new ThermalContactExchange.Carrier(cold, 0.75, 768, whole.internalEnergyEu() * 0.75));
		// This comparison uses identical material properties in both constituent stocks.
		var sameMaterial = new ControlVolumeConduction(mesh, 2, List.of(new ControlVolumeThermalMetric.Portion(0, 0, 3),
				new ControlVolumeThermalMetric.Portion(0, 1, 9))).advance(fractionBodies, prescribed, 0.2, 1e-10, 512);
		var aggregate = new ControlVolumeConduction(mesh, new double[]{12}).advance(List.of(whole), prescribed, 0.2, 1e-10, 512);
		close(sameMaterial.energyChange()[0] + sameMaterial.energyChange()[1], aggregate.energyChange()[0], 1e-9);
		for (double temperature : sameMaterial.temperature()) close(temperature, aggregate.temperature()[0], 1e-9);
		closure(sameMaterial); closure(aggregate);
	}
	private static void sharedCaloricOwnerRetainsOneCapacity(LiquidControlVolumeMesh mesh, ThermalMaterialDefinition material) {
		var prescribed = new double[mesh.faces.size()]; Arrays.fill(prescribed, Double.NaN);
		for (int f = 0; f < prescribed.length; f++) if (mesh.faces.get(f).second() < 0) prescribed[f] = 350;
		var owner = carrier(material, 280);
		var half = new ThermalContactExchange.Carrier(material, 0.5, owner.baseEnergyEu() / 2, owner.internalEnergyEu() / 2);
		var independent = new ControlVolumeConduction(mesh, new double[]{3, 3})
				.advance(List.of(half, half), prescribed, 0.2, 1e-10, 256);
		int[] support = {0, 0}; var jointOperator = new ControlVolumeConduction(mesh, support, 1, new double[]{3, 3});
		Arrays.fill(support, 17);
		var joint = jointOperator.advance(List.of(owner), prescribed, 0.2, 1e-10, 256);
		if (joint.energyChange().length != 1) throw new AssertionError("One caloric inventory acquired multiple heat capacities");
		close(joint.energyChange()[0], independent.energyChange()[0] + independent.energyChange()[1], 1e-9);
		close(joint.temperature()[0], independent.temperature()[0], 1e-9);
		close(joint.temperature()[0], independent.temperature()[1], 1e-9); closure(joint); closure(independent);
		Arrays.fill(prescribed, Double.NaN);
		joint = jointOperator.advance(List.of(owner), prescribed, 10, 1e-10, 256);
		if (joint.energyChange()[0] != 0 || joint.iterations() != 0 || joint.reservoirEnergy().signum() != 0)
			throw new AssertionError("Auxiliary support exchanged heat inside a uniform caloric owner");
		try { new ControlVolumeConduction(mesh, new int[]{0, 0}, 2, new double[]{3, 3}); throw new AssertionError("Caloric inventory lost all geometric support"); }
		catch (IllegalArgumentException expected) { }
	}
	private static void obliqueCaloricInterfacePreservesSteadyFlux() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var mesh = new LiquidControlVolumeMesh(List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube.clip(1, 1, 0, 1)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube.clip(-1, -1, 0, -1))), 1, 1,
				(c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var k = new double[]{3, 6}; var bodies = new java.util.ArrayList<ThermalContactExchange.Carrier>();
		for (int c = 0; c < k.length; c++) {
			var p = mesh.cells.get(c).geometry().centroid();
			double temperature = 300 + p.x() - p.y() + 2 * p.z() + 12 / k[c] * (p.x() + p.y() - 1);
			bodies.add(carrier(material("test:thermal/oblique_" + c, k[c], List.of(new ThermalMaterialDefinition.CaloricSegment(250, 400, c + 2))), temperature));
		}
		var prescribed = new double[mesh.faces.size()]; Arrays.fill(prescribed, Double.NaN);
		for (int f = 0; f < prescribed.length; f++) {
			var face = mesh.faces.get(f); if (face.second() >= 0) continue;
			var p = face.surface().centroid(); prescribed[f] = 300 + p.x() - p.y() + 2 * p.z() + 12 / k[face.first()] * (p.x() + p.y() - 1);
		}
		var result = new ControlVolumeConduction(mesh, k).advance(bodies, prescribed, 0.5, 1e-10, 512);
		for (int c = 0; c < k.length; c++) { close(result.energyChange()[c], 0, 1e-8); close(result.temperature()[c], bodies.get(c).temperatureK(), 1e-8); }
		closure(result);
	}
	private static void temporalAndSpatialFourierRefinement() {
		var constant = material("test:thermal/fourier", 1, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 400, 1)));
		double previous = Double.POSITIVE_INFINITY;
		for (int n : new int[]{2, 4, 8}) {
			double h = 1.0 / n; int level = Integer.numberOfTrailingZeros(n); var leaves = new java.util.ArrayList<LiquidGeometryRefinement.Cell>();
			for (int x = 0; x < n; x++) leaves.add(new LiquidGeometryRefinement.Cell(level, x, 0, 0, LiquidPolyhedron.box(x * h, 0, 0, (x + 1) * h, h, h)));
			var mesh = new LiquidControlVolumeMesh(leaves, 1, 1, (c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
			var k = new double[n]; Arrays.fill(k, 1); var prescribed = new double[mesh.faces.size()]; Arrays.fill(prescribed, Double.NaN);
			var bodies = new java.util.ArrayList<ThermalContactExchange.Carrier>();
			for (int c = 0; c < n; c++) { double t = 300 + Math.cos(Math.PI * (c + 0.5) * h); bodies.add(new ThermalContactExchange.Carrier(constant, h * h * h, 0, h * h * h * constant.sensibleSpecificEnergyAt(t))); }
			var operator = new ControlVolumeConduction(mesh, k); double eigenvalue = 4 / (h * h) * Math.pow(Math.sin(Math.PI * h / 2), 2);
			var result = operator.advance(bodies, prescribed, 0.01, 1e-11, 1024);
			for (int c = 0; c < n; c++) close(result.temperature()[c], 300 + Math.cos(Math.PI * (c + 0.5) * h) / (1 + 0.01 * eigenvalue), 1e-8);
			closure(result);
			var mixtureBodies = new java.util.ArrayList<ThermalContactExchange.Carrier>();
			var portions = new java.util.ArrayList<ControlVolumeThermalMetric.Portion>();
			for (int c = 0; c < n; c++) for (double fraction : new double[]{0.25, 0.75}) {
				var old = bodies.get(c); portions.add(new ControlVolumeThermalMetric.Portion(c, mixtureBodies.size(), fraction));
				mixtureBodies.add(new ThermalContactExchange.Carrier(constant, old.massKilograms() * fraction, old.baseEnergyEu() * fraction, old.internalEnergyEu() * fraction));
			}
			var mixed = new ControlVolumeConduction(mesh, mixtureBodies.size(), portions).advance(mixtureBodies, prescribed, 0.01, 1e-11, 1024);
			for (int c = 0; c < n; c++) {
				close(mixed.energyChange()[2 * c] + mixed.energyChange()[2 * c + 1], result.energyChange()[c], 1e-10);
				close(mixed.temperature()[2 * c], result.temperature()[c], 1e-8); close(mixed.temperature()[2 * c + 1], result.temperature()[c], 1e-8);
			}
			closure(mixed); double error = Math.abs(eigenvalue - Math.PI * Math.PI);
			if (error >= previous * 0.3) throw new AssertionError("Caloric conduction failed spatial Fourier refinement"); previous = error;
			if (n == 4) {
				double previousTimeError = Double.POSITIVE_INFINITY;
				for (int steps : new int[]{1, 2, 4, 8}) {
					var current = new java.util.ArrayList<>(bodies);
					for (int step = 0; step < steps; step++) {
						result = operator.advance(current, prescribed, 0.1 / steps, 1e-11, 1024); closure(result);
						for (int c = 0; c < n; c++) { var old = current.get(c); current.set(c, new ThermalContactExchange.Carrier(constant, old.massKilograms(), old.baseEnergyEu(), old.internalEnergyEu() + result.energyChange()[c])); }
					}
					double timeError = Math.abs(result.temperature()[0] - 300 - Math.cos(Math.PI * h / 2) * Math.exp(-0.1 * eigenvalue));
					if (timeError >= previousTimeError * 0.65) throw new AssertionError("Caloric conduction failed backward-Euler time refinement"); previousTimeError = timeError;
				}
			}
		}
	}
	private static void closure(ControlVolumeConduction.Result result) {
		if (!SignedDyadic.sum(result.energyChange()).equals(result.reservoirEnergy().add(result.roundoff()))
				|| result.roundoff().compareTo(result.roundoffBound()) > 0 || result.roundoff().compareTo(result.roundoffBound().negate()) < 0
				|| result.maximumCaloricResidual() > 1e-8 || result.maximumFluxCorrection() > 1e-8)
			throw new AssertionError("Resolved conduction broke exact energy audit or its caloric/face residual");
	}
	private static ThermalMaterialDefinition material(String id, double conductivity, List<ThermalMaterialDefinition.CaloricSegment> segments) {
		return new ThermalMaterialDefinition(id, ThermalMaterialDefinition.CarrierKind.LIQUID, "test:liquid", 300, 0, conductivity, segments);
	}
	private static ThermalContactExchange.Carrier carrier(ThermalMaterialDefinition material, double temperature) {
		return new ThermalContactExchange.Carrier(material, 1, 1024, 1024 + material.sensibleSpecificEnergyAt(temperature));
	}
	private static void close(double actual, double expected, double tolerance) {
		if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance) throw new AssertionError("Caloric conduction: " + actual + " expected " + expected);
	}
}
