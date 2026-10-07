package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Inventory-group material properties on the resolved geometric mechanical support. */
final class LiquidGeometryCarrierProperties {
	final double[] density, dynamicViscosity, acceleration, surfaceCoefficient;

	LiquidGeometryCarrierProperties(FluidDomain domain, LiquidGeometryState state, LiquidGeometryPhaseInput input) {
		if (state.markerCount() != domain.particles.count()) throw new IllegalArgumentException("Geometric carrier inventory differs from mechanical input");
		int count = state.cells().size(); int[] markers = state.copyMarkerCells(), owners = state.copyFragmentOwners();
		var volume = zeros(count); var mass = zeros(count); var rawVolume = zeros(count); var viscosity = zeros(count); var buoyancyMass = zeros(count);
		var phase = new int[count]; Arrays.fill(phase, -1);
		var restored = LiquidGeometryStateMapper.restore(state); var width = Fraction.of(state.rootWidthMetres()); var rootVolume = width.multiply(width).multiply(width);
		var scale = Fraction.of(domain.unitScale.cubicMetersPerNormalizedVolume());
		for (int f = 0; f < count; f++) volume[owners[f]] = volume[owners[f]].add(LiquidConvexRegion.of(restored.get(f).geometry()).measure().volume().multiply(rootVolume));
		for (int p = 0; p < markers.length; p++) {
			int group = markers[p]; var m = Fraction.of(domain.particles.exactMass(p)); var v = Fraction.of(domain.particles.exactVolume(p));
			mass[group] = mass[group].add(m.multiply(scale)); rawVolume[group] = rawVolume[group].add(v);
			int materialPhase = domain.materialRegistry == null ? 0 : domain.materialRegistry.phaseHandle(domain.particles.materialHandle(p));
			if (phase[group] >= 0 && phase[group] != materialPhase) throw new IllegalArgumentException("Immiscible liquid inventory requires distinct phase geometry");
			phase[group] = materialPhase;
			if (domain.materialDefinitions != null || domain.thermalState != null) {
				String id = domain.materialRegistry.material(domain.particles.materialHandle(p)).id();
				double temperature = domain.thermalState == null ? MaterialRegistry.STANDARD_TEMPERATURE_K : domain.thermalState.temperature(p, domain.thermalMarkers);
				if (domain.materialDefinitions != null) viscosity[group] = viscosity[group].add(v.multiply(Fraction.of(domain.materialDefinitions.dynamicViscosityExtendingAt(id, temperature))));
				if (domain.thermalState != null) {
					var expansion = domain.materialDefinitions == null ? cc.ecisr.mmm.thermal.LiquidThermalExpansion.require(id) : domain.materialDefinitions.entry(id).thermalExpansion();
					if (expansion == null) throw new IllegalStateException("Liquid thermal carrier has no Boussinesq definition: " + id);
					buoyancyMass[group] = buoyancyMass[group].add(m.multiply(scale).multiply(Fraction.of(expansion.relativeBuoyancy(temperature))));
				}
			}
		}
		density = new double[input.fragments.length]; dynamicViscosity = density.clone(); acceleration = new double[3 * density.length];
		for (int c = 0; c < density.length; c++) if (input.fragments[c] >= 0) {
			int group = owners[input.fragments[c]];
			density[c] = mass[group].divide(volume[group]).rounded(); dynamicViscosity[c] = viscosity[group].divide(rawVolume[group]).rounded();
			acceleration[3 * c + 1] = -domain.config.gravity() * (1 - buoyancyMass[group].divide(mass[group]).rounded());
			if (!(density[c] > 0) || !Double.isFinite(density[c]) || !Double.isFinite(acceleration[3 * c + 1]))
				throw new IllegalStateException("Geometric liquid properties exceed mechanical numerical range");
		}
		surfaceCoefficient = surfaceCoefficients(domain, input, owners, phase);
	}
	private static double[] surfaceCoefficients(FluidDomain domain, LiquidGeometryPhaseInput input, int[] owners, int[] phase) {
		var result = new double[input.mesh.faces.size()]; if (domain.materialRegistry == null) return result;
		var surface = new double[domain.materialRegistry.phaseCount()]; var wall = surface.clone(); var present = new boolean[surface.length];
		// The current material contract assigns constant surface properties to each miscibility phase.
		for (int handle = 0; handle < domain.materialRegistry.size(); handle++) {
			int p = domain.materialRegistry.phaseHandle(handle); if (present[p]) continue;
			var material = domain.materialRegistry.material(handle); surface[p] = material.surfaceTension();
			wall[p] = -material.surfaceTension() * Math.cos(material.contactAngleRadians()); present[p] = true;
		}
		for (int f = 0; f < result.length; f++) {
			var face = input.mesh.faces.get(f); int a = input.fragments[face.first()], b = face.second() >= 0 ? input.fragments[face.second()] : -1;
			if (a < 0 && b < 0) continue;
			int first = phase[owners[a >= 0 ? a : b]];
			result[f] = a >= 0 && b >= 0 ? domain.materialRegistry.liquidPairTension(first, phase[owners[b]])
					: face.second() == -2 ? wall[first] : surface[first];
		}
		return result;
	}
	private static Fraction[] zeros(int count) { var result = new Fraction[count]; Arrays.fill(result, Fraction.ZERO); return result; }
}
