package mmm.fluid;

/** Donor momentum transport conserves impulse and converts local velocity variance to heat. */
public final class MacMomentumAdvectionValidation {
	public static void main(String[] args) {
		double[] filled = {1, 2, 1, 3, 2, 1, 4, 1};
		double[][] circulation = {new double[12], new double[12], new double[12]};
		circulation[0][1] = .1; circulation[0][4] = -.1;
		circulation[1][3] = .1; circulation[1][2] = -.1;
		check(filled, filled, circulation, false);
		double[] source = {1, 0, 0, 0, 0, 0, 0, 0}, future = {.75, .25, 0, 0, 0, 0, 0, 0};
		double[][] outward = {new double[12], new double[12], new double[12]}; outward[0][1] = .25;
		check(source, future, outward, true);
		System.out.println("MAC donor mass, momentum and mixing heat PASS");
	}
	private static void check(double[] before, double[] after, double[][] flux, boolean uniform) {
		long[] source=new long[before.length],future=new long[after.length];long[][] massFlux=new long[flux.length][];
		for(int i=0;i<source.length;i++){source[i]=mmm.units.Mass.fromKilograms(before[i]);future[i]=mmm.units.Mass.fromKilograms(after[i]);}
		for(int a=0;a<flux.length;a++){massFlux[a]=new long[flux[a].length];for(int i=0;i<flux[a].length;i++)massFlux[a][i]=mmm.units.Mass.fromKilograms(flux[a][i]);}
		for (int axis = 0; axis < 3; axis++) {
			double[] m0 = masses(before, axis), m1 = masses(after, axis);
			double[] velocity = new double[12];
			for (int i = 0; i < velocity.length; i++) velocity[i] = m0[i] == 0 ? 0 : uniform ? 1 : (float) Math.sin(i + axis);
			var transport=new MacMomentumAdvection(2,2,2,axis);
			double[] momentum = transport.transport(source, future, massFlux, velocity);
			double[] heat=new double[before.length];transport.addHeatToCells(future,heat);double dissipated=0;for(double value:heat)dissipated+=value;
			double p0 = 0, p1 = 0, k0 = 0, k1 = 0;
			for (int i = 0; i < momentum.length; i++) {
				p0 += m0[i] * velocity[i]; p1 += momentum[i]; k0 += .5 * m0[i] * velocity[i] * velocity[i];
				if (m1[i] == 0) {
					if (momentum[i] != 0) throw new AssertionError("Dry face retained momentum");
				} else {
					k1 += .5 * momentum[i] * momentum[i] / m1[i];
					if (uniform && Math.abs(momentum[i] / m1[i] - 1) > 1e-9) throw new AssertionError("Uniform translation changed");
				}
			}
			if (Math.abs(p1 - p0) > 1e-9 * Math.max(1, Math.abs(p0))) throw new AssertionError("Momentum changed: " + (p1 - p0));
			if (k1>k0+1e-9*Math.max(1,k0)||Math.abs(k1+dissipated-k0)>1e-9*Math.max(1,k0))throw new AssertionError("Momentum mixing heat differs from its kinetic loss");
		}
	}
	private static double[] masses(double[] cell, int axis) {
		int nx = 2 + (axis == 0 ? 1 : 0), ny = 2 + (axis == 1 ? 1 : 0), nz = 2 + (axis == 2 ? 1 : 0);
		double[] result = new double[12];
		for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++)
			result[(z * ny + y) * nx + x] = .5 * (value(cell, x, y, z) + value(cell, x - (axis == 0 ? 1 : 0), y - (axis == 1 ? 1 : 0), z - (axis == 2 ? 1 : 0)));
		return result;
	}
	private static double value(double[] cell, int x, int y, int z) { return x < 0 || y < 0 || z < 0 || x >= 2 || y >= 2 || z >= 2 ? 0 : cell[(z * 2 + y) * 2 + x]; }
}
