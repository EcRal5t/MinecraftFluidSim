package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Native caloric publication preserves complete liquid stocks, geometry, gas and cold handoff. */
final class ResolvedLiquidConductionValidation {
	static void validate(FluidDomain source) {
		jointGasPublication(source);
		cooccupiedInventoryPublication(source);
		var domain = FluidDomain.restoreAuthoritativeState(source.captureAuthoritativeState());
		domain.carrierMutations().publishGeometricRepartition(new LiquidInventoryRepartitionPlan(domain));
		domain.thermalState.resetMarkerTemperature(0, 270, domain.thermalMarkers, domain.exactInternalEnergy());
		domain.thermalState.resetMarkerTemperature(1, 350, domain.thermalMarkers, domain.exactInternalEnergy());
		var before = domain.captureAuthoritativeState(); var geometry = domain.liquidGeometryState; var gasEnergy = domain.thermalState.exactGasEnergy();
		var support = LiquidGeometryStateMapper.restore(geometry);
		if (domain.particles.count() != 2 || support.size() != 2) throw new AssertionError("Native conduction fixture has a different physical inventory");
		var mesh = new LiquidControlVolumeMesh(support, geometry.rootWidthMetres(), 1, (c, s) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var captured = domain.thermalState.prepareConduction(domain.thermalMarkers, domain.gasState.network);
		var coefficients = captured.liquidBodies().stream().mapToDouble(body -> body.material().conductivityEuPerSecondMeterK()).toArray();
		var prescribed = new double[mesh.faces.size()]; Arrays.fill(prescribed, Double.NaN);
		var result = new ControlVolumeConduction(mesh, coefficients).advance(captured.liquidBodies(), prescribed, 10, 1e-8, 512);
		var changes = Arrays.copyOf(result.energyChange(), captured.bodies().size());
		var plan = captured.plan(changes, result.reservoirEnergy(), result.roundoffBound()); Arrays.fill(changes, Double.NaN);
		var quantities = new SignedDyadic[]{domain.particles.exactVolume(0), domain.particles.exactVolume(1), domain.particles.exactMass(0), domain.particles.exactMass(1)};
		var species = List.of(domain.chemicalParcels.record(0), domain.chemicalParcels.record(1));
		domain.thermalState.commitConduction(plan, domain.thermalMarkers, domain.gasState.network);
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
		try { domain.thermalState.commitConduction(plan, domain.thermalMarkers, domain.gasState.network); throw new AssertionError("Conduction plan published twice"); }
		catch (IllegalStateException expected) { }
		// Hidden quantity edits must invalidate a plan even when both scalar observations remain unchanged.
		var unchanged = new double[domain.thermalState.prepareConduction(domain.thermalMarkers, domain.gasState.network).bodies().size()];
		var mass = domain.particles.exactMass(0); var volume = domain.particles.exactVolume(0);
		var stale = domain.thermalState.prepareConduction(domain.thermalMarkers, domain.gasState.network).plan(unchanged, SignedDyadic.ZERO, SignedDyadic.ZERO);
		domain.particles.setQuantities(0, volume, mass.add(SignedDyadic.of(1, -100)));
		var ledger = domain.thermalAudit(); var energy = domain.thermalState.liquid().copyEnergies(domain.particles.count());
		try { domain.thermalState.commitConduction(stale, domain.thermalMarkers, domain.gasState.network); throw new AssertionError("Hidden native mass edit retained a conduction plan"); }
		catch (IllegalStateException expected) { }
		if (!ledger.equals(domain.thermalAudit()) || !Arrays.equals(energy, domain.thermalState.liquid().copyEnergies(domain.particles.count())))
			throw new AssertionError("Stale conduction rejection wrote partial energy or audit state");
		domain.particles.setQuantities(0, volume, mass);
		var invalid = domain.thermalState.prepareConduction(domain.thermalMarkers, domain.gasState.network); var invalidChanges = new double[invalid.bodies().size()];
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
		var captured = domain.thermalState.prepareConduction(domain.thermalMarkers, domain.gasState.network); var portions = new java.util.ArrayList<ControlVolumeThermalMetric.Portion>();
		for (int c = 0; c < owners.length; c++) for (int p = 0; p < n; p++) if (markers[p] == owners[c]) {
			double k = LiquidExactPolygon.Fraction.of(volumes[p]).divide(LiquidExactPolygon.Fraction.of(totals.get(markers[p])))
					.multiply(LiquidExactPolygon.Fraction.of(captured.liquidBodies().get(p).material().conductivityEuPerSecondMeterK())).rounded();
			portions.add(new ControlVolumeThermalMetric.Portion(c, p, k));
		}
		var prescribed = new double[mesh.faces.size()]; Arrays.fill(prescribed, Double.NaN);
		var result = new ControlVolumeConduction(mesh, n, portions).advance(captured.liquidBodies(), prescribed, 10, 1e-8, 512);
		var gasEnergy = domain.thermalState.exactGasEnergy(); var gasGeometry = domain.gasState.geometry;
		domain.thermalState.commitConduction(captured.plan(Arrays.copyOf(result.energyChange(), captured.bodies().size()), result.reservoirEnergy(), result.roundoffBound()), domain.thermalMarkers, domain.gasState.network);
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
	private static void jointGasPublication(FluidDomain source) {
		var domain = FluidDomain.restoreAuthoritativeState(source.captureAuthoritativeState());
		domain.carrierMutations().publishGeometricRepartition(new LiquidInventoryRepartitionPlan(domain));
		for (int p = 0; p < domain.particleCount(); p++)
			domain.thermalState.resetMarkerTemperature(p, 350, domain.thermalMarkers, domain.exactInternalEnergy());
		var geometry = domain.liquidGeometryState; var gasGeometry = domain.gasState.geometry; var network = domain.gasState.network;
		var before = domain.captureAuthoritativeState(); var beforeGas = domain.thermalState.gas().nodeEnergies();
		var captured = domain.thermalState.prepareConduction(domain.thermalMarkers, network);
		var support = LiquidGeometryStateMapper.restore(geometry);
		// Resolve one chamber in several geometric cells while retaining one complete gas heat capacity.
		var requests = new java.util.ArrayList<>(support);
		requests.add(new LiquidGeometryRefinement.Cell(1, 0, 0, 1, LiquidPolyhedron.box(0, 0, .5, .5, .5, 1)));
		var partition = new LiquidGeometryDomainPartition(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.geometry.cutCells, requests, 128);
		var phase = new LiquidGeometryPhaseInput(geometry, partition, gasGeometry, 128);
		int[] owner = geometry.copyFragmentOwners(), markers = geometry.copyMarkerCells();
		var portions = new java.util.ArrayList<ControlVolumeThermalMetric.Portion>(); int gasCells = 0;
		for (int c = 0; c < phase.fragments.length; c++) {
			if (phase.fragments[c] < 0) {
				int ordinal = captured.gasOrdinal(phase.storedGasNodes[c]);
				if (ordinal < 0) throw new AssertionError("Joint conduction fixture has a prescribed or vacuum gas cell");
				portions.add(new ControlVolumeThermalMetric.Portion(c, ordinal, 1)); gasCells++;
			} else for (int p = 0; p < markers.length; p++) if (markers[p] == owner[phase.fragments[c]])
				portions.add(new ControlVolumeThermalMetric.Portion(c, p, captured.liquidBodies().get(p).material().conductivityEuPerSecondMeterK()));
		}
		if (gasCells <= network.nodeCount() || captured.bodies().size() != domain.particleCount() + network.nodeCount())
			throw new AssertionError("Joint conduction duplicated a chamber heat capacity or omitted geometric support");
		var prescribed = new double[phase.mesh.faces.size()]; Arrays.fill(prescribed, Double.NaN);
		double temperatureObservation = captured.bodies().stream().mapToDouble(body ->
				body.heatCapacityEuPerK() * Math.ulp(body.temperatureK()) * .5).sum();
		var result = new ControlVolumeConduction(phase.mesh, captured.bodies().size(), portions)
				.advance(captured.bodies(), prescribed, 1, Math.max(1e-8, temperatureObservation), 1024);
		domain.thermalState.commitConduction(captured.plan(result.energyChange(), result.reservoirEnergy(), result.roundoffBound()), domain.thermalMarkers, network);
		for (int p = 0; p < domain.particleCount(); p++) if (!(domain.thermalState.temperature(p, domain.thermalMarkers) < 350))
			throw new AssertionError("Joint conduction failed to cool the hotter liquid inventory");
		for (int n = 1; n <= network.nodeCount(); n++) if (!(domain.thermalState.gas().temperature(n, network) > captured.bodies().get(captured.gasOrdinal(n)).temperatureK()
				&& domain.thermalState.gas().nodeEnergy(n, network) > beforeGas[n - 1] && domain.thermalState.gas().gaugePressure(n, network) > 0))
			throw new AssertionError("Joint conduction failed to heat and pressurize the finite chamber");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		if (domain.liquidGeometryState != geometry || domain.gasState.geometry != gasGeometry
				|| !before.gas().nodes().equals(domain.captureAuthoritativeState().gas().nodes())
				|| !domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				|| !restored.thermalAudit().equals(domain.thermalAudit()) || !restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
				|| !Arrays.equals(restored.thermalState.gas().nodeEnergies(), domain.thermalState.gas().nodeEnergies()))
			throw new AssertionError("Joint conduction altered spatial stocks or failed conservative cold handoff");
		captured = domain.thermalState.prepareConduction(domain.thermalMarkers, network);
		var stale = captured.plan(new double[captured.bodies().size()], SignedDyadic.ZERO, SignedDyadic.ZERO);
		var liquidEnergy = domain.thermalState.liquid().copyEnergies(domain.particleCount()); var gasEnergy = domain.thermalState.gas().nodeEnergies(); var ledger = domain.thermalAudit();
		double volume = network.availableVolume(1); network.updateAvailableVolume(1, volume * 2);
		try { domain.thermalState.commitConduction(stale, domain.thermalMarkers, network); throw new AssertionError("Changed gas support retained a conduction candidate"); }
		catch (IllegalStateException expected) { }
		network.updateAvailableVolume(1, volume);
		captured = domain.thermalState.prepareConduction(domain.thermalMarkers, network);
		var invalid = new double[captured.bodies().size()]; invalid[0] = 1e12; invalid[captured.gasOrdinal(1)] = -1e12;
		try { captured.plan(invalid, SignedDyadic.ZERO, SignedDyadic.ZERO); throw new AssertionError("Invalid gas caloric successor reached publication"); }
		catch (IllegalArgumentException expected) { }
		if (!ledger.equals(domain.thermalAudit()) || !Arrays.equals(liquidEnergy, domain.thermalState.liquid().copyEnergies(domain.particleCount()))
				|| !Arrays.equals(gasEnergy, domain.thermalState.gas().nodeEnergies()))
			throw new AssertionError("Rejected joint conduction wrote a partial caloric inventory");
		domain.commitOperatorVentSealedGas(domain.planOperatorVentSealedGas());
		captured = domain.thermalState.prepareConduction(domain.thermalMarkers, network);
		if (captured.bodies().size() != domain.particleCount() || captured.gasOrdinal(0) != -1)
			throw new AssertionError("Vacuum or ambient acquired a finite conduction heat capacity");
		for (int n = 1; n <= network.nodeCount(); n++) if (captured.gasOrdinal(n) != -1)
			throw new AssertionError("Vacuum chamber acquired a finite caloric unknown");
		domain.thermalState.commitConduction(captured.plan(new double[captured.bodies().size()], SignedDyadic.ZERO, SignedDyadic.ZERO), domain.thermalMarkers, network);
		if (domain.thermalState.exactGasEnergy().signum() != 0 || !domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()))
			throw new AssertionError("Vacuum conduction publication created internal energy");
	}
}
