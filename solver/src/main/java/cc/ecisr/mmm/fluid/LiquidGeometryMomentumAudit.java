package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import java.util.List;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Compares resolved momentum with the inventory carrier observation at candidate handoff. */
final class LiquidGeometryMomentumAudit {
	private LiquidGeometryMomentumAudit() { }

	static void requireCompatible(LiquidGeometryState state, List<LiquidGeometryRefinement.Cell> cells, FluidDomain domain) {
		int[] markers = state.copyMarkerCells(), owners = state.copyFragmentOwners(); int count = cells.size();
		var volume = new Fraction[count]; var mass = new Fraction[count]; var integrated = new Fraction[3 * count]; var observed = new Fraction[3 * count];
		Arrays.fill(volume, Fraction.ZERO); Arrays.fill(mass, Fraction.ZERO); Arrays.fill(integrated, Fraction.ZERO); Arrays.fill(observed, Fraction.ZERO);
		var errors = new double[3 * count]; var velocityErrors = new double[3 * count];
		for (int f = 0; f < count; f++) {
			int owner = owners[f]; var v = LiquidConvexRegion.of(cells.get(f).geometry()).measure().volume(); var u = state.cells().get(f).copyVelocity();
			volume[owner] = volume[owner].add(v);
			for (int a = 0; a < 3; a++) {
				integrated[3 * owner + a] = integrated[3 * owner + a].add(v.multiply(Fraction.of(u[a])));
				velocityErrors[3 * owner + a] = Math.max(velocityErrors[3 * owner + a], 8 * Math.ulp(u[a]));
			}
		}
		for (int p = 0; p < markers.length; p++) {
			int owner = markers[p]; double m = domain.particles.mass(p); var exactMass = Fraction.of(m); mass[owner] = mass[owner].add(exactMass);
			float[] u = {domain.particles.velocityX(p), domain.particles.velocityY(p), domain.particles.velocityZ(p)};
			for (int a = 0; a < 3; a++) {
				observed[3 * owner + a] = observed[3 * owner + a].add(exactMass.multiply(Fraction.of(u[a])));
				errors[3 * owner + a] += m * (Math.ulp(u[a]) + velocityErrors[3 * owner + a]);
			}
		}
		for (int c = 0; c < count; c++) if (owners[c] == c) for (int a = 0; a < 3; a++) {
			var expected = integrated[3 * c + a].divide(volume[c]).multiply(mass[c]);
			if (expected.subtract(observed[3 * c + a]).absolute().rounded() > errors[3 * c + a])
				throw new IllegalArgumentException("Liquid geometric momentum differs from inventory quadrature");
		}
	}
}
