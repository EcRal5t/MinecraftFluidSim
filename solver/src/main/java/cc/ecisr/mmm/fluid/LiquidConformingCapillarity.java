package cc.ecisr.mmm.fluid;

import java.util.ArrayList;

/** Surface-area forces pulled back through the complete velocity that transports their vertices. */
final class LiquidConformingCapillarity {
	final LiquidAreaEnergy energy;
	final LiquidConformingVelocity.Load load;
	final double[] acceleration, pressureJump;

	LiquidConformingCapillarity(LiquidConformingVelocity field, double[] coefficient, double[] density) {
		if (coefficient.length != field.mesh.faces.size() || density.length != field.mesh.cells.size())
			throw new IllegalArgumentException("Incomplete geometric capillary properties");
		var polygons = new ArrayList<LiquidExactPolygon>(); var coefficients = new ArrayList<Double>();
		var incident = new int[coefficient.length]; java.util.Arrays.fill(incident, -1);
		for (var tet : field.tetrahedra) {
			if (tet.face() < 0) continue;
			// A shared interface contributes its material energy once.
			if (incident[tet.face()] < 0) incident[tet.face()] = tet.cell();
			if (incident[tet.face()] != tet.cell()) continue;
			polygons.add(LiquidExactPolygon.tetrahedron(tet.vertices()).getFirst()); coefficients.add(coefficient[tet.face()]);
		}
		energy = new LiquidAreaEnergy(polygons, coefficients.stream().mapToDouble(Double::doubleValue).toArray());
		load = field.load(energy.force);
		acceleration = new double[3 * density.length]; pressureJump = new double[coefficient.length];
		for (int c = 0; c < density.length; c++) for (int a = 0; a < 3; a++) {
			var force = load.cellForce().get(3 * c + a); if (force.signum() == 0) continue;
			double mass = density[c] * field.mesh.cells.get(c).geometry().moments().volume();
			if (!(mass > 0) || !Double.isFinite(mass)) throw new IllegalArgumentException("Capillary force has no physical liquid inertia");
			acceleration[3 * c + a] = force.rounded() / mass;
			if (!Double.isFinite(acceleration[3 * c + a])) throw new IllegalStateException("Capillary acceleration exceeds numerical range");
		}
		for (int f = 0; f < pressureJump.length; f++) {
			pressureJump[f] = -load.faceForce().get(f).rounded();
			if (!Double.isFinite(pressureJump[f])) throw new IllegalStateException("Capillary pressure load exceeds numerical range");
		}
	}
}
