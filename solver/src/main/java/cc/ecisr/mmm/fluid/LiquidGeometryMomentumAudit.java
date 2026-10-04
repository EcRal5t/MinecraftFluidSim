package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import java.util.List;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Compares resolved momentum with the inventory carrier observation at candidate handoff. */
final class LiquidGeometryMomentumAudit {
	private LiquidGeometryMomentumAudit() { }

	static void requireCompatible(LiquidGeometryState state, List<LiquidGeometryRefinement.Cell> cells, LiquidGeometryInventoryView inventory) {
		int[] markers = state.copyMarkerCells(), owners = state.copyFragmentOwners(); int count = cells.size();
		var volume = new Fraction[count]; var mass = new Fraction[count]; var integrated = new Fraction[3 * count]; var observed = new Fraction[3 * count];
		Arrays.fill(volume, Fraction.ZERO); Arrays.fill(mass, Fraction.ZERO); Arrays.fill(integrated, Fraction.ZERO); Arrays.fill(observed, Fraction.ZERO);
		var errors = new Fraction[3 * count]; Arrays.fill(errors, Fraction.ZERO); var velocityErrors = new double[3 * count];
		for (int f = 0; f < count; f++) {
			int owner = owners[f]; var v = LiquidConvexRegion.of(cells.get(f).geometry()).measure().volume(); var u = state.cells().get(f).copyVelocity();
			volume[owner] = volume[owner].add(v);
			for (int a = 0; a < 3; a++) {
				integrated[3 * owner + a] = integrated[3 * owner + a].add(v.multiply(Fraction.of(u[a])));
				velocityErrors[3 * owner + a] = Math.max(velocityErrors[3 * owner + a], Math.ulp(u[a]));
			}
		}
		for (int p = 0; p < markers.length; p++) {
			int owner = markers[p]; var exactMass = Fraction.of(inventory.mass(p)); mass[owner] = mass[owner].add(exactMass);
			for (int a = 0; a < 3; a++) {
				float u = (float) inventory.velocity(p, a); observed[3 * owner + a] = observed[3 * owner + a].add(exactMass.multiply(Fraction.of(u)));
				var error = Fraction.of(Math.ulp(u)).add(Fraction.of(velocityErrors[3 * owner + a]).multiply(Fraction.of(8)));
				errors[3 * owner + a] = errors[3 * owner + a].add(exactMass.multiply(error));
			}
		}
		for (int c = 0; c < count; c++) if (owners[c] == c) for (int a = 0; a < 3; a++) {
			var expected = integrated[3 * c + a].divide(volume[c]).multiply(mass[c]);
			if (expected.subtract(observed[3 * c + a]).absolute().subtract(errors[3 * c + a]).signum() > 0)
				throw new IllegalArgumentException("Liquid geometric momentum differs from inventory quadrature");
		}
	}
}
