package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.Arrays;

/** Integrates P0 kinetic energy and gravitational potential over actual inventory-group support. */
final class LiquidGeometryMechanicalEnergy {
	record Energy(double kineticJoules, double gravitationalJoules) { }
	private LiquidGeometryMechanicalEnergy() { }
	static Energy measure(FluidDomain domain) {
		var state = domain.liquidGeometryState; int[] markers = state.copyMarkerCells(), owners = state.copyFragmentOwners();
		if (markers.length != domain.particles.count()) throw new IllegalStateException("Geometric mechanical energy has inconsistent inventory");
		var cells = LiquidGeometryStateMapper.restore(state); int count = cells.size();
		var volume = new Fraction[count]; var mass = new Fraction[count]; var squared = new Fraction[count]; var height = new Fraction[count];
		Arrays.fill(volume, Fraction.ZERO); Arrays.fill(mass, Fraction.ZERO); Arrays.fill(squared, Fraction.ZERO); Arrays.fill(height, Fraction.ZERO);
		var scale = Fraction.of(domain.unitScale.cubicMetersPerNormalizedVolume());
		for (int p = 0; p < markers.length; p++) mass[markers[p]] = mass[markers[p]].add(Fraction.of(domain.particles.mass(p)).multiply(scale));
		for (int f = 0; f < count; f++) {
			int group = owners[f]; var measure = LiquidConvexRegion.of(cells.get(f).geometry()).measure(); var velocity = state.cells().get(f).copyVelocity();
			volume[group] = volume[group].add(measure.volume()); height[group] = height[group].add(measure.firstMoment(1));
			for (int a = 0; a < 3; a++) squared[group] = squared[group].add(measure.volume().multiply(Fraction.of(velocity[a]).multiply(Fraction.of(velocity[a]))));
		}
		var kinetic = Fraction.ZERO; var gravitational = Fraction.ZERO;
		var factor = Fraction.of(state.rootWidthMetres()).multiply(Fraction.of(domain.config.gravity()));
		for (int f = 0; f < count; f++) if (owners[f] == f) {
			var density = mass[f].divide(volume[f]);
			kinetic = kinetic.add(density.multiply(squared[f]).divide(Fraction.of(2)));
			gravitational = gravitational.add(density.multiply(height[f]).multiply(factor));
		}
		double k = kinetic.rounded(), p = gravitational.rounded();
		if (!(Double.isFinite(k) && k >= 0 && Double.isFinite(p) && p >= 0)) throw new ArithmeticException("Geometric mechanical energy exceeds numerical range");
		return new Energy(k, p);
	}
}
