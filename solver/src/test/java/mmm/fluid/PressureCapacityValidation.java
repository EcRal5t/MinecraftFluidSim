package mmm.fluid;

/** Short checks of the common capacity/gas incidence before continuous scenario validation. */
public final class PressureCapacityValidation {
	public static void main(String[] args) {
		if (args.length>1 || args.length==1&&!args[0].equals("--algebra")) throw new IllegalArgumentException("Expected --algebra or no selector");
		checkGraph(fixture(true)); checkGraph(fixture(false));
		fixedPorePressurePort();
		if (args.length==1) { System.out.println("Common pressure incidence and transpose PASS"); return; }
		shallowHydrostatics(false); shallowHydrostatics(true);
		System.out.println("Capacity/gas pressure adjoint, positive operator and shallow equilibrium PASS");
	}
	private static void fixedPorePressurePort() {
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(1, 1000, 0, .05F, 120, 1e-5F, .4F, .002F, 0));
		domain.setVolume(1, 0, 0, .5F); domain.ensureGrid();
		var graph = domain.pressureSystem.faces; graph.rebuild(.05F);
		double port=graph.totalPortCoefficient(0,1,0,255,255);
		domain.mac.velocityX[1]=-.25F; graph.rebuild(.05F);
		close(graph.totalPortCoefficient(0,1,0,255,255),port,"Mixture pressure aperture changed with the transport donor direction");
		domain.mac.velocityX[1]=.25F; graph.rebuild(.05F);
		close(graph.totalPortCoefficient(0,1,0,255,255),port,"Mixture pressure aperture changed after flow reversal");
		close(port,1,"A partial liquid cell lost its actual fixed pore port");
	}

	private static FluidDomain fixture(boolean full) {
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(.5F, 1000, 0, .05F, 120, 1e-5F, .4F, .002F, .8F));
		float amount = full ? 1 : .5F; float[] points = new float[32];
		for (int cell = 0; cell < 8; cell++) {
			points[4 * cell] = (cell % 2 + .5F) * .5F; points[4 * cell + 1] = (cell / 2 % 2 + .5F) * .5F;
			points[4 * cell + 2] = (cell / 4 + .5F) * .5F; points[4 * cell + 3] = !full && cell / 2 % 2 == 0 ? 1 : amount;
		}
		domain.restoreParticleState(new FlipParticleState(2, 2, 2, .5F, 8, full ? 8 : 6, points, new float[24],
				mmm.units.Mass.createLiquid(.5F, 1000, points), new byte[8]));
		domain.sealDomainBoundary(); domain.ensureGrid(); return domain;
	}
	private static void checkGraph(FluidDomain domain) {
		var graph = domain.pressureSystem.faces; graph.rebuild(.05F);
		int rows = domain.cellCount + domain.gasState.network.nodeCount() + 1;
		double[] p = new double[rows], q = new double[rows], velocity = new double[graph.faceCount];
		for (int i = 0; i < rows; i++) { p[i] = Math.sin(i + .3); q[i] = Math.cos(i + .7); }
		for (int i = 0; i < velocity.length; i++) velocity[i] = Math.sin(i * .7);
		double[] divergence = new double[rows], gradient = new double[velocity.length];
		graph.addDivergence(velocity, divergence, 1); graph.pressureVelocity(p, gradient);
		double first = dot(p, divergence), second = 0;
		for (int face = 0; face < velocity.length; face++) if (graph.inverseMass[face] > 0)
			second += velocity[face] * gradient[face] / graph.inverseMass[face];
		close(first, second, "Volume incidence and pressure force are not transposes");
		double[] ap = apply(graph, p), aq = apply(graph, q);
		close(dot(p, aq), dot(q, ap), "Pressure operator is asymmetric");
		if (dot(p, ap) < -1e-12) throw new AssertionError("Pressure inertia is not positive");
	}
	private static double[] apply(PressureFaceGraph graph, double[] source) {
		double[] response = new double[graph.faceCount], result = new double[source.length];
		graph.pressureVelocity(source, response); graph.addDivergence(response, result, 1); return result;
	}
	private static void shallowHydrostatics(boolean column) {
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(.5F, 1000, 9.81F, .025F, 120, 1e-5F, .4F, .002F, .8F));
		for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
			domain.setVolume(x, 0, z, column ? 1 : .2F); if (column) domain.setVolume(x, 1, z, .2F);
		}
		long mass = domain.totalMassUnits(); var volume = domain.liquidCells.exactVolume();
		for (int step = 0; step < 3; step++) {
			domain.stepMechanicsOnly();
			if (domain.totalMassUnits() != mass || !domain.liquidCells.exactVolume().equals(volume)) throw new AssertionError("Shallow inventory changed");
			if (column && Math.abs(domain.pressure(0, 0, 0) - 1000 * 9.81 * .5 * .7) > .01) throw new AssertionError("Centroid hydrostatic pressure differs: " + domain.pressure(0, 0, 0));
			if (domain.maximumParticleSpeed() > 1e-5) throw new AssertionError("Shallow layer accelerated: " + domain.maximumParticleSpeed());
		}
	}
	private static void close(double first, double second, String reason) {
		if (Math.abs(first - second) > 1e-11 * Math.max(1, Math.max(Math.abs(first), Math.abs(second)))) throw new AssertionError(reason + ": " + first + " != " + second);
	}
	private static double dot(double[] a, double[] b) { double total = 0; for (int i = 0; i < a.length; i++) total += a[i] * b[i]; return total; }
}
