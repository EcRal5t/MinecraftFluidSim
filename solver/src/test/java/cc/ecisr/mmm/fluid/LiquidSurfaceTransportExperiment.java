package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.List;

/** Measures actual transported exterior area against the analytic affine shear surface. */
final class LiquidSurfaceTransportExperiment {
	private static final double SIGMA = 0.125, SHEAR = 0.125;
	public static void main(String[] args) {
		for (int level : new int[]{2, 3}) measure(level);
	}
	private static void measure(int level) {
		List<LiquidGeometryRefinement.Cell> cells = List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0,
				LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75)));
		for (int l = 0; l < level; l++) cells = cells.stream().flatMap(c -> LiquidGeometryRefinement.children(c).stream()).toList();
		var incoming = new double[3 * cells.size()];
		for (int c = 0; c < cells.size(); c++) incoming[3 * c] = SHEAR * (cells.get(c).geometry().centroid().y() - 0.5);
		var state = LiquidGeometryStateMapper.capture(cells, 1, new int[]{0}, new int[cells.size()], incoming);
		var partition = new LiquidGeometryDomainPartition(2, 2, 2, 0.5F, new byte[8], cells, 4096);
		var input = new LiquidGeometryPhaseInput(state, partition, false, 4096);
		var rates = new Fraction[input.mesh.faces.size()]; var primary = new double[3 * input.fragments.length];
		for (int c = 0; c < input.fragments.length; c++) primary[3 * c] = SHEAR * (input.mesh.cells.get(c).geometry().centroid().y() - 0.5);
		for (int f = 0; f < rates.length; f++) {
			var face = input.mesh.faces.get(f);
			rates[f] = face.areaVector().getFirst().multiply(face.centroid().get(1).subtract(Fraction.of(0.5))).multiply(Fraction.of(SHEAR));
		}
		var energy = new LiquidSurfaceEnergy(input.mesh, coefficients(input)); var work = Fraction.ZERO;
		for (int f = 0; f < rates.length; f++) work = work.subtract(energy.pressureJump[f].multiply(rates[f]));
		var velocity = LiquidControlVolumeVelocity.reconstruct(input.mesh, rates, primary);
		for (double dt : new double[]{1.0 / 128, 1.0 / 256}) {
			var motion = new LiquidGeometryMotionPlan(state, input.mesh, input.fragments, velocity, partition.leaves, dt, 65536, 128);
			var next = new LiquidControlVolumeMesh(LiquidGeometryStateMapper.restore(motion.geometry), 1, 1, (c, s) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			var sigma = new double[next.faces.size()];
			for (int f = 0; f < sigma.length; f++) if (next.faces.get(f).second() < 0) sigma[f] = SIGMA;
			var after = new LiquidSurfaceEnergy(next, sigma); double x = SHEAR * dt;
			double analyticPower = 0.5 * SIGMA * x * x / (Math.sqrt(1 + x * x) + 1) / dt;
			System.out.println("level=" + level + " dt=" + dt + " transportedPower=" + ((after.joules - energy.joules) / dt)
					+ " forcePower=" + work.rounded() + " analyticPower=" + analyticPower + " fragments=" + motion.geometry.cells().size());
		}
	}
	private static double[] coefficients(LiquidGeometryPhaseInput input) {
		var sigma = new double[input.mesh.faces.size()];
		for (int f = 0; f < sigma.length; f++) {
			var face = input.mesh.faces.get(f); boolean a = input.fragments[face.first()] >= 0, b = face.second() >= 0 && input.fragments[face.second()] >= 0;
			if (a != b) sigma[f] = SIGMA;
		}
		return sigma;
	}
}
