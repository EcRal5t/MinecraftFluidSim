package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.Arrays;

/** Integrates P0 kinetic energy and gravitational potential over actual inventory-group support. */
final class LiquidGeometryMechanicalEnergy {
	record Energy(double kineticJoules, double gravitationalJoules) { }
	private LiquidGeometryMechanicalEnergy() { }
	static Energy measure(FluidDomain domain) {
		var support = new Support(domain); return new Energy(support.kinetic(), support.gravitational());
	}
	static double kinetic(FluidDomain domain) { return new Support(domain).kinetic(); }
	static double gravitational(FluidDomain domain) { return new Support(domain).gravitational(); }
	private static final class Support {
		private final FluidDomain domain;
		private final LiquidGeometryState state;
		private final java.util.List<LiquidGeometryRefinement.Cell> cells;
		private final java.util.List<LiquidGeometryMomentumAudit.VelocityVolume> fields;
		private final int[] owners;
		private final Fraction[] volume, mass;
		Support(FluidDomain domain) {
			this.domain = domain;
			state = domain.liquidGeometryState; int[] markers = state.copyMarkerCells(); owners = state.copyFragmentOwners();
			if (markers.length != domain.particles.count()) throw new IllegalStateException("Geometric mechanical energy has inconsistent inventory");
			cells = LiquidGeometryStateMapper.restore(state); int count = cells.size();
			volume = new Fraction[count]; mass = new Fraction[count];
			Arrays.fill(volume, Fraction.ZERO); Arrays.fill(mass, Fraction.ZERO);
			var scale = Fraction.of(domain.unitScale.cubicMetersPerNormalizedVolume());
			for (int p = 0; p < markers.length; p++) mass[markers[p]] = mass[markers[p]].add(Fraction.of(domain.particles.exactMass(p)).multiply(scale));
			fields = LiquidGeometryMomentumAudit.volumesByVelocity(state, cells);
			for (var field : fields) volume[field.owner()] = volume[field.owner()].add(field.volume());
		}
		double kinetic() {
			var squared = new Fraction[cells.size()]; Arrays.fill(squared, Fraction.ZERO);
			for (var field : fields) {
				int group = field.owner();
				for (int a = 0; a < 3; a++) { var u = Fraction.of(field.velocity().coordinate(a)); squared[group] = squared[group].add(field.volume().multiply(u.multiply(u))); }
			}
			var energy = Fraction.ZERO;
			for (int f = 0; f < owners.length; f++) if (owners[f] == f) energy = energy.add(mass[f].divide(volume[f]).multiply(squared[f]).divide(Fraction.of(2)));
			return observe(energy);
		}
		double gravitational() {
			var height = new Fraction[cells.size()]; Arrays.fill(height, Fraction.ZERO);
			for (int f = 0; f < cells.size(); f++) {
				int group = owners[f]; var measure = cells.get(f).geometry().measure(); height[group] = height[group].add(measure.firstMoment(1));
			}
			var energy = Fraction.ZERO;
			var factor = Fraction.of(state.rootWidthMetres()).multiply(Fraction.of(domain.config.gravity()));
			for (int f = 0; f < owners.length; f++) if (owners[f] == f) energy = energy.add(mass[f].divide(volume[f]).multiply(height[f]).multiply(factor));
			return observe(energy);
		}
	}
	private static double observe(Fraction energy) {
		double result = energy.rounded();
		if (!(Double.isFinite(result) && result >= 0)) throw new ArithmeticException("Geometric mechanical energy exceeds numerical range");
		return result;
	}
}
