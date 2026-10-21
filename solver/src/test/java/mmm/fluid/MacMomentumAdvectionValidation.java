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
		wallImpactPublishesImpulseHeatAndRecoverableMomentum();
		System.out.println("MAC donor mass, momentum and mixing heat PASS");
	}
	private static void wallImpactPublishesImpulseHeatAndRecoverableMomentum(){
		var d=new FluidDomain(2,2,2,new FluidSolverConfig(1,1,0,.1F,80,1e-5F,.002F,.8F),64);
		d.setVolumes(new float[]{.625F,.375F,0,0,0,0,0,0});d.domainFlowFields.ensureGrid();
		long[] before=new long[8],after=new long[8];
		before[0]=before[1]=mmm.units.Mass.fromKilograms(.5);after[0]=mmm.units.Mass.fromKilograms(.625);after[1]=mmm.units.Mass.fromKilograms(.375);
		long[][] flux={new long[12],new long[12],new long[12]};flux[0][d.domainFlowFields.xFaceIndex(1,0,0)]=-mmm.units.Mass.fromKilograms(.125);
		float[] velocity=new float[12];velocity[d.domainFlowFields.xFaceIndex(1,0,0)]=-2;
		var donor=new MacMomentumAdvection(2,2,2);donor.beginComponent(0);double[] x=donor.transport(before,after,flux,velocity);
		if(!(x[d.domainFlowFields.xFaceIndex(0,0,0)]<0))throw new AssertionError("Incoming donor flow did not exercise the closed wall momentum path");
		double[] heat=new double[8];donor.addHeatToCells(after,heat);double[][] p={x,new double[12],new double[12]};
		var wall=d.cellMacStage.stopWallMomentum(p,after,heat,null);
		var byCell=new java.util.TreeMap<Integer,mmm.units.SignedDyadic>();double totalHeat=0;
		for(int cell=0;cell<heat.length;cell++)if(heat[cell]>0){byCell.put(cell,mmm.units.SignedDyadic.of(heat[cell]));totalHeat+=heat[cell];}
		double totalP=0,kinetic=0;var mass=masses(new double[]{.625,.375,0,0,0,0,0,0},0);
		for(int face=0;face<x.length;face++){totalP+=x[face];if(mass[face]>0)kinetic+=.5*x[face]*x[face]/mass[face];}
		if(Math.abs(totalP+1-wall.x())>1e-10||!(wall.x()>0)||Math.abs(kinetic+totalHeat-1)>1e-10)
			throw new AssertionError("Wall stopping lost impulse or failed to transfer kinetic energy into local heat");
		var inventory=d.liquidCells.planEdits(java.util.List.of()).withMechanicalHeat(byCell);inventory.commit();
		d.cellMacStage.commitMomentum(new CellMomentumTransport.Prepared(new MacMomentumState(2,2,2,p[0],p[1],p[2]),byCell,wall));d.markCellInventoryChanged();
		var saved=d.captureAuthoritativeState();var restored=FluidDomain.restoreAuthoritativeState(saved);restored.domainFlowFields.ensureGrid();
		if(saved.momentum().copyX()[d.domainFlowFields.xFaceIndex(0,0,0)]!=0||restored.mac.velocityX[d.domainFlowFields.xFaceIndex(0,0,0)]!=0
				||!saved.liquid().stocks().equals(restored.liquidCells.stocks()))throw new AssertionError("Wall momentum or its heat changed during capture and restore");
	}
	private static void check(double[] before, double[] after, double[][] flux, boolean uniform) {
		long[] source=new long[before.length],future=new long[after.length];long[][] massFlux=new long[flux.length][];
		for(int i=0;i<source.length;i++){source[i]=mmm.units.Mass.fromKilograms(before[i]);future[i]=mmm.units.Mass.fromKilograms(after[i]);}
		for(int a=0;a<flux.length;a++){massFlux[a]=new long[flux[a].length];for(int i=0;i<flux[a].length;i++)massFlux[a][i]=mmm.units.Mass.fromKilograms(flux[a][i]);}
		for (int axis = 0; axis < 3; axis++) {
			double[] m0 = masses(before, axis), m1 = masses(after, axis);
			float[] velocity = new float[12];
			for (int i = 0; i < velocity.length; i++) velocity[i] = m0[i] == 0 ? 0 : uniform ? 1 : (float) Math.sin(i + axis);
			var transport=new MacMomentumAdvection(2,2,2);transport.beginComponent(axis);
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
