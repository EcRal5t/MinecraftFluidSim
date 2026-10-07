package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Native caloric publication preserves complete liquid stocks, geometry, gas and cold handoff. */
final class ResolvedLiquidConductionValidation {
	static void validate(FluidDomain source) {
		cooccupiedInventoryPublication(source);
		var domain = FluidDomain.restoreAuthoritativeState(source.captureAuthoritativeState());
		domain.carrierMutations().publishGeometricRepartition(new LiquidInventoryRepartitionPlan(domain));
		domain.thermalState.resetMarkerTemperature(0, 270, domain.thermalMarkers, domain.exactInternalEnergy());
		domain.thermalState.resetMarkerTemperature(1, 350, domain.thermalMarkers, domain.exactInternalEnergy());
		var before = domain.captureAuthoritativeState(); var geometry = domain.liquidGeometryState; var gasEnergy = domain.thermalState.exactGasEnergy();
		var support = LiquidGeometryStateMapper.restore(geometry);
		if (domain.particles.count() != 2 || support.size() != 2) throw new AssertionError("Native conduction fixture has a different physical inventory");
		var mesh = new LiquidControlVolumeMesh(support, geometry.rootWidthMetres(), 1, (c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var captured = domain.thermalState.prepareLiquidConduction(domain.thermalMarkers);
		var coefficients = captured.bodies().stream().mapToDouble(body -> body.material().conductivityEuPerSecondMeterK()).toArray();
		var prescribed = new double[mesh.faces.size()]; Arrays.fill(prescribed, Double.NaN);
		var result = new ControlVolumeConduction(mesh, coefficients).advance(captured.bodies(), prescribed, 10, 1e-8, 512);
		var changes = result.energyChange().clone();
		var plan = captured.plan(changes, result.reservoirEnergy(), result.roundoffBound()); Arrays.fill(changes, Double.NaN);
		var quantities = new SignedDyadic[]{domain.particles.exactVolume(0), domain.particles.exactVolume(1), domain.particles.exactMass(0), domain.particles.exactMass(1)};
		var species = List.of(domain.chemicalParcels.record(0), domain.chemicalParcels.record(1));
		domain.thermalState.commitLiquidConduction(plan, domain.thermalMarkers);
		if (!(domain.thermalState.temperature(0, domain.thermalMarkers) > 270 && domain.thermalState.temperature(1, domain.thermalMarkers) < 350)
				|| !domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()) || domain.liquidGeometryState != geometry
				|| !before.gas().nodes().equals(domain.captureAuthoritativeState().gas().nodes())
				|| before.gas().geometry() != domain.gasState.geometry || !gasEnergy.equals(domain.thermalState.exactGasEnergy())
				|| !Arrays.equals(quantities, new SignedDyadic[]{domain.particles.exactVolume(0), domain.particles.exactVolume(1), domain.particles.exactMass(0), domain.particles.exactMass(1)})
				|| !species.equals(List.of(domain.chemicalParcels.record(0), domain.chemicalParcels.record(1))))
			throw new AssertionError("Resolved conduction publication changed an extensive stock or its caloric/gas audit");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		if (!restored.exactInternalEnergy().equals(domain.exactInternalEnergy()) || !restored.thermalAudit().equals(domain.thermalAudit())
				|| !Arrays.equals(restored.thermalState.liquid().copyEnergies(restored.particleCount()), domain.thermalState.liquid().copyEnergies(domain.particleCount())))
			throw new AssertionError("Published resolved conduction energy did not survive cold handoff");
		try { domain.thermalState.commitLiquidConduction(plan, domain.thermalMarkers); throw new AssertionError("Conduction plan published twice"); }
		catch (IllegalStateException expected) { }
		// Hidden quantity edits must invalidate a plan even when both scalar observations remain unchanged.
		var unchanged = new double[domain.particles.count()];
		var mass = domain.particles.exactMass(0); var volume = domain.particles.exactVolume(0);
		var stale = domain.thermalState.prepareLiquidConduction(domain.thermalMarkers).plan(unchanged, SignedDyadic.ZERO, SignedDyadic.ZERO);
		domain.particles.setQuantities(0, volume, mass.add(SignedDyadic.of(1, -100)));
		var ledger = domain.thermalAudit(); var energy = domain.thermalState.liquid().copyEnergies(domain.particles.count());
		try { domain.thermalState.commitLiquidConduction(stale, domain.thermalMarkers); throw new AssertionError("Hidden native mass edit retained a conduction plan"); }
		catch (IllegalStateException expected) { }
		if (!ledger.equals(domain.thermalAudit()) || !Arrays.equals(energy, domain.thermalState.liquid().copyEnergies(domain.particles.count())))
			throw new AssertionError("Stale conduction rejection wrote partial energy or audit state");
		domain.particles.setQuantities(0, volume, mass);
		var invalid = domain.thermalState.prepareLiquidConduction(domain.thermalMarkers); var invalidChanges = new double[2];
		invalidChanges[0] = -1e12; invalidChanges[1] = 1e12;
		try { invalid.plan(invalidChanges, SignedDyadic.ZERO, SignedDyadic.ZERO); throw new AssertionError("Invalid caloric successor reached publication"); }
		catch (IllegalArgumentException expected) { }
		if (!ledger.equals(domain.thermalAudit()) || !Arrays.equals(energy, domain.thermalState.liquid().copyEnergies(domain.particles.count())))
			throw new AssertionError("Caloric preflight edited the source energy");
	}
	private static void cooccupiedInventoryPublication(FluidDomain source) {
		var domain = FluidDomain.restoreAuthoritativeState(source.captureAuthoritativeState());
		domain.chemicalParcels.assign(1, domain.chemicalParcels.record(1), true);
		domain.carrierMutations().publishGeometricRepartition(new LiquidInventoryRepartitionPlan(domain));
		var geometry = domain.liquidGeometryState; var support = LiquidGeometryStateMapper.restore(geometry);
		var mesh = new LiquidControlVolumeMesh(support, geometry.rootWidthMetres(), 1, (c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		int n = domain.particles.count(); int[] markers = geometry.copyMarkerCells(), owners = geometry.copyFragmentOwners();
		if (n != 4 || mesh.cells.size() != 2) throw new AssertionError("Native cooccupied fixture lost its independent stocks");
		var totals = new java.util.TreeMap<Integer, SignedDyadic>(); var before = new double[n];
		var volumes = new SignedDyadic[n]; var masses = volumes.clone(); var mixtures = new cc.ecisr.mmm.chemistry.ChemicalMixtureRecord[n]; var indicators = new boolean[n];
		for (int p = 0; p < n; p++) {
			volumes[p] = domain.particles.exactVolume(p); masses[p] = domain.particles.exactMass(p); mixtures[p] = domain.chemicalParcels.record(p); indicators[p] = domain.chemicalParcels.indicator(p);
			totals.merge(markers[p], volumes[p], SignedDyadic::add); before[p] = domain.thermalState.temperature(p, domain.thermalMarkers);
		}
		var captured = domain.thermalState.prepareLiquidConduction(domain.thermalMarkers); var portions = new java.util.ArrayList<ControlVolumeThermalMetric.Portion>();
		for (int c = 0; c < owners.length; c++) for (int p = 0; p < n; p++) if (markers[p] == owners[c]) {
			double k = LiquidExactPolygon.Fraction.of(volumes[p]).divide(LiquidExactPolygon.Fraction.of(totals.get(markers[p])))
					.multiply(LiquidExactPolygon.Fraction.of(captured.bodies().get(p).material().conductivityEuPerSecondMeterK())).rounded();
			portions.add(new ControlVolumeThermalMetric.Portion(c, p, k));
		}
		var prescribed = new double[mesh.faces.size()]; Arrays.fill(prescribed, Double.NaN);
		var result = new ControlVolumeConduction(mesh, n, portions).advance(captured.bodies(), prescribed, 10, 1e-8, 512);
		var gasEnergy = domain.thermalState.exactGasEnergy(); var gasGeometry = domain.gasState.geometry;
		domain.thermalState.commitLiquidConduction(captured.plan(result.energyChange(), result.reservoirEnergy(), result.roundoffBound()), domain.thermalMarkers);
		for (int p = 0; p < n; p++) {
			double after = domain.thermalState.temperature(p, domain.thermalMarkers);
			if ((before[p] < 300 ? after <= before[p] : after >= before[p])
					|| !domain.particles.exactVolume(p).equals(volumes[p]) || !domain.particles.exactMass(p).equals(masses[p])
						|| !domain.chemicalParcels.record(p).equals(mixtures[p]) || domain.chemicalParcels.indicator(p) != indicators[p])
				throw new AssertionError("Native cooccupied conduction lost a caloric inventory or its provenance");
		}
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		if (domain.liquidGeometryState != geometry || domain.gasState.geometry != gasGeometry || !gasEnergy.equals(domain.thermalState.exactGasEnergy())
				|| !domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				|| !restored.thermalAudit().equals(domain.thermalAudit()) || !restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
				|| !Arrays.equals(restored.thermalState.liquid().copyEnergies(n), domain.thermalState.liquid().copyEnergies(n)))
			throw new AssertionError("Native cooccupied conduction failed joint energy publication or cold handoff");
	}
}
