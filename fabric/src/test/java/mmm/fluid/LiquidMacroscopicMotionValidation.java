package mmm.fluid;

import java.util.Arrays;
import java.util.List;
import mmm.fluid.air.GasAuditLedger;
import mmm.units.SignedDyadic;

/** Normal conservative transport, macroscopic motion and complete backend recovery. */
public final class LiquidMacroscopicMotionValidation {
	private LiquidMacroscopicMotionValidation() { }

	public static void main(String[] args) {
		if (args.length > 1) throw new IllegalArgumentException("Expected one transport validation selector");
		String entry = args.length == 0 ? "all" : args[0];
		switch (entry) {
			case "common-mesh" -> generalFieldContinuesAfterRestore();
			case "transport-moments" -> { generalFieldContinuesAfterRestore(); translationAndMaterialTransport(); }
			case "joint-moments", "footprint-only" -> { rotationRetainsMacroscopicInertia(); materialTransportKeepsInventories(); }
			case "sweep-only" -> translationAndMaterialTransport();
			case "all" -> { generalFieldContinuesAfterRestore(); translationAndMaterialTransport(); rotationRetainsMacroscopicInertia(); }
			default -> throw new IllegalArgumentException("Unknown remap validation entry: " + entry);
		}
		System.out.println("Conservative liquid transport validation passed: " + entry);
	}

	/** Original L-shaped source: h=1, dt=.125, three full cells and velocity (1,1,0). */
	static FluidDomain lShapedTranslationSource() {
		var domain = new FluidDomain(5, 5, 5,
				new FluidSolverConfig(1, 1000, 0, .125F, 200, 1e-5F, .002F, 0), 4096);
		domain.sealDomainBoundary();
		domain.setVolume(2, 2, 2, 1); domain.setVolume(1, 2, 2, 1); domain.setVolume(2, 1, 2, 1);
		uniformVelocity(domain,1,1,0);
		domain.bootstrapGasState(null);
		return domain;
	}

	private static void generalFieldContinuesAfterRestore() {
		var domain = lShapedTranslationSource(); var before = Motion.capture(domain);
		domain = runSteps(domain, 8, false, 0, "L-shape");
		var after = Motion.capture(domain);
		// Eight steps move one cell; first-order volume transport may smear the interface within a cell.
		check(after.volumeCenter[0] > before.volumeCenter[0] + .25
				&& after.volumeCenter[1] > before.volumeCenter[1] + .25,
				"The restored L-shaped liquid did not continue moving in both prescribed directions");
		check(after.kinetic <= 1.05 * before.kinetic, "Unforced L-shaped transport amplified kinetic energy");
	}

	private static void translationAndMaterialTransport() {
		for (float h : new float[]{.5F, 1}) for (int axes : new int[]{1, 3, 7}) for (int sign : new int[]{-1, 1}) {
			var domain = new FluidDomain(7, 7, 7,
					new FluidSolverConfig(h, 1000, 0, .125F, 200, 1e-5F, .002F, .8F), 4096);
			domain.sealDomainBoundary();
			domain.setVolume(3, 3, 3, 1);
			uniformVelocity(domain,(axes&1)==0?0:sign*h,(axes&2)==0?0:sign*h,(axes&4)==0?0:sign*h);
			domain.bootstrapGasState(null);
			var before = Motion.capture(domain); domain = runSteps(domain, 4, false, 0, "translation-" + axes + "-" + sign + "-h" + h);
			var after = Motion.capture(domain);
			for (int axis = 0; axis < 3; axis++) {
				double displacement = after.volumeCenter[axis] - before.volumeCenter[axis];
				if ((axes & 1 << axis) == 0) check(Math.abs(displacement) <= .05 * h, "Translation gained transverse motion");
				else check(sign * displacement > .2 * h, "Uniform liquid translation stalled after recovery");
			}
			check(after.kinetic <= before.kinetic * 1.05, "Uniform translation gained kinetic energy");
		}
		materialTransportKeepsInventories();
	}

	private static void materialTransportKeepsInventories() {
		var domain = new FluidDomain(7, 5, 5,
				new FluidSolverConfig(1, 1000, 0, .05F, 200, 1e-5F, .002F, .8F), 4096);
		domain.sealDomainBoundary();
		domain.setVolume(2, 2, 2, 1); domain.setVolume(3, 2, 2, 1);
		var materials = new FluidMaterialRegistry(List.of(
				new FluidMaterial("test:transport_dense", 1000, "test:transport", 0, Math.PI / 2),
				new FluidMaterial("test:transport_light", 2.5, "test:transport", 0, Math.PI / 2)));
		for (int ordinal=0;ordinal<domain.liquidCells.physicalSize();ordinal++)
			domain.domainCollision.setMarkerMaterial(ordinal,materials,domain.liquidCells.physicalStockAt(ordinal).key().cell()%7<3?0:1);
		uniformVelocity(domain,.5F,0,0);
		domain.bootstrapGasState(null);
		var materialVolume = materialVolume(domain); var materialMass = materialMass(domain);
		domain = runSteps(domain, 4, false, 0, "two-density");
		check(Arrays.equals(materialVolume, materialVolume(domain)), "Transport exchanged volume between material ledgers");
		var after = materialMass(domain);
		for (int material = 0; material < after.length; material++)
			close(after[material].toDouble(), materialMass[material].toDouble(), 1e-11, "Per-material mass");
	}

	private static void rotationRetainsMacroscopicInertia() {
		for (int axis = 0; axis < 3; axis++) {
			var domain = new FluidDomain(7, 7, 7,
					new FluidSolverConfig(1, 1000, 0, .025F, 200, 1e-5F, .002F, .8F), 4096);
			domain.sealDomainBoundary();
			for (int z = 2; z < 5; z++) for (int y = 2; y < 5; y++) for (int x = 2; x < 5; x++) domain.setVolume(x, y, z, 1);
			domain.domainFlowFields.ensureGrid();
			for (int component=0;component<3;component++) {
				float[] velocity=velocities(domain,component);
				int nx=domain.sizeX+(component==0?1:0),ny=domain.sizeY+(component==1?1:0);
				for(int face=0;face<velocity.length;face++) {
					double[] position={(face%nx+(component==0?0:.5)),(face/nx%ny+(component==1?0:.5)),(face/(nx*ny)+(component==2?0:.5))};
					int first=(axis+1)%3,second=(axis+2)%3;
					velocity[face]=(float)(component==first?-.1*(position[second]-3.5):component==second?.1*(position[first]-3.5):0);
				}
			}
			domain.cellMacStage.publishMomentum();
			domain.bootstrapGasState(null);
			var before = Motion.capture(domain); domain = runSteps(domain, 4, false, 0, "rotation-" + axis); var after = Motion.capture(domain);
			// A 3-cell-radius diameter is coarse: quantify accumulated first-order transport dissipation.
			check(after.angular[axis] > .8 * before.angular[axis] && after.angular[axis] <= 1.05 * before.angular[axis],
					"Resolved macroscopic rotation exceeded the 20% transport-decay budget");
			check(after.kinetic <= 1.05 * before.kinetic, "Unforced rotation gained kinetic energy");
		}
	}

	/** Captures source, restores a private backend candidate, publishes once, then captures and restores the result. */
	static FluidDomain runSteps(FluidDomain domain, int steps, boolean phase, int maximumOperations, String label) {
		var inventory = Inventory.capture(domain); var backend = new CpuFluidSolverBackend();
		for (int step = 0; step < steps; step++) {
			long started = System.nanoTime(); var source = domain.captureAuthoritativeState();
			var candidate = backend.evaluate(new FluidStepRequest(step, source, List.of(), phase, maximumOperations, true));
			domain = candidate.claimPreparedCpuDomain();
			domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			double fullMs = (System.nanoTime() - started) / 1e6;
			inventory.requireUnchanged(domain, label + " step=" + step);
			System.out.printf("TRANSPORT %s step=%d fullMs=%.3f substeps=%d pressureIterations=%d residual=%g markers=%d gasNodes=%d K=%g%n",
					label, step, fullMs, candidate.stats().substeps(), candidate.stats().pressureIterations(),
					(double) candidate.stats().pressureResidual(), domain.particleCount(), domain.gasState.network.nodeCount(), Motion.capture(domain).kinetic);
		}
		return domain;
	}

	record Inventory(SignedDyadic volume, long mass, long[] species, GasAuditLedger gas, double gasCapacity) {
		static Inventory capture(FluidDomain domain) {
			domain.synchronizeGasTopologyForPersistence();
			return new Inventory(rawVolume(domain), domain.liquidCells.massUnits(), domain.chemicalParcels == null ? null
					: speciesTotals(domain), domain.gasState.network.sealedInventory(), LiquidMacroscopicMotionValidation.gasCapacity(domain));
		}
		void requireUnchanged(FluidDomain domain, String context) {
			domain.domainFlowFields.ensureInventoryGrid();
			check(rawVolume(domain).equals(volume), context + ": liquid volume changed");
			check(domain.liquidCells.massUnits()==mass, context + ": liquid mass");
			if (species != null) check(Arrays.equals(species, speciesTotals(domain)), context + ": species changed");
			var actualGas = domain.gasState.network.sealedInventory();
			check(actualGas.standardVolumeNanos() == gas.standardVolumeNanos(), context + ": sealed gas inventory changed");
			for (var component : gas.entries()) {
				var after = actualGas.component(component.componentId());
				check(after != null && after.standardVolumeNanos() == component.standardVolumeNanos()
						&& after.species().equals(component.species()), context + ": gas component inventory changed");
			}
			check(domain.gasBoundary().auditClosed(), context + ": gas audit does not close");
			close(LiquidMacroscopicMotionValidation.gasCapacity(domain), gasCapacity, 2e-12, context + ": closed gas capacity");
			for (int cell = 0; cell < domain.cellCount; cell++) {
				double amount = domain.liquidGrid.exactCellVolumeInventory[cell].toDouble();
				check(amount >= 0 && amount <= domain.domainCollision.effectiveOpenCellVolume(cell) + 8 * Math.ulp(1F), context + ": cell capacity exceeded at " + cell);
			}
			for (var stock : domain.liquidCells.physicalStocks()) check(stock.massUnits()>0 && Double.isFinite(stock.internalEnergy()),context+": invalid physical cell inventory");
			for(int axis=0;axis<3;axis++)for(float velocity:velocities(domain,axis))check(Float.isFinite(velocity),context+": nonfinite MAC velocity");
			if (domain.thermalState != null) check(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()), context + ": thermal audit does not close");
		}
	}

	record Motion(double[] volumeCenter, double[] momentum, double[] angular, double kinetic) {
		static Motion capture(FluidDomain domain) {
			domain.domainFlowFields.ensureGrid(); double h=domain.config.cellSize(),total=0,kinetic=0;
			var center=new double[3];var momentum=new double[3];var angular=new double[3];
			for(var stock:domain.liquidCells.stocks()) {
				int cell=stock.key().cell();double volume=stock.volumeFraction();total+=volume;
				center[0]+=volume*(cell%domain.sizeX+.5)*h;center[1]+=volume*(cell/domain.sizeX%domain.sizeY+.5)*h;center[2]+=volume*(cell/(domain.sizeX*domain.sizeY)+.5)*h;
			}
			for(int axis=0;axis<3;axis++) {
				float[] velocity=velocities(domain,axis);double[] mass=axis==0?domain.cellMacStage.massX:axis==1?domain.cellMacStage.massY:domain.cellMacStage.massZ;
				int nx=domain.sizeX+(axis==0?1:0),ny=domain.sizeY+(axis==1?1:0);
				for(int face=0;face<velocity.length;face++) {
					double p=mass[face]*velocity[face];momentum[axis]+=p;kinetic+=.5*p*velocity[face];
					double[] x={(face%nx+(axis==0?0:.5))*h,(face/nx%ny+(axis==1?0:.5))*h,(face/(nx*ny)+(axis==2?0:.5))*h};
					angular[(axis+2)%3]-=x[(axis+1)%3]*p;angular[(axis+1)%3]+=x[(axis+2)%3]*p;
				}
			}
			for(int axis=0;axis<3;axis++)center[axis]/=total;
			return new Motion(center,momentum,angular,kinetic);
		}
	}
	private static SignedDyadic rawVolume(FluidDomain domain) { return domain.liquidCells.exactVolume(); }
	private static SignedDyadic[] materialVolume(FluidDomain domain) {
		var result=new SignedDyadic[]{SignedDyadic.ZERO,SignedDyadic.ZERO};
		for(var stock:domain.liquidCells.stocks())result[stock.key().material()]=result[stock.key().material()].add(stock.volume());return result;
	}
	private static SignedDyadic[] materialMass(FluidDomain domain) {
		var result=new SignedDyadic[]{SignedDyadic.ZERO,SignedDyadic.ZERO};
		for(var stock:domain.liquidCells.stocks())result[stock.key().material()]=result[stock.key().material()].add(SignedDyadic.of(stock.massUnits(),0));return result;
	}
	private static long[] speciesTotals(FluidDomain domain) {
		var chemistry=domain.chemicalParcels.registry();long[] result=new long[chemistry.species().size()];
		for(var stock:domain.liquidCells.stocks())if(stock.mixture()!=null)for(int i=0;i<stock.mixture().entryCount();i++)result[stock.mixture().speciesHandle(i)]=Math.addExact(result[stock.mixture().speciesHandle(i)],stock.mixture().amountAt(i));return result;
	}
	private static float[] velocities(FluidDomain d,int axis){return axis==0?d.mac.velocityX:axis==1?d.mac.velocityY:d.mac.velocityZ;}
	private static void uniformVelocity(FluidDomain d,float x,float y,float z){d.domainFlowFields.ensureGrid();Arrays.fill(d.mac.velocityX,x);Arrays.fill(d.mac.velocityY,y);Arrays.fill(d.mac.velocityZ,z);d.cellMacStage.publishMomentum();}

	private static double gasCapacity(FluidDomain domain) {
		double total = 0; for (int node = 1; node <= domain.gasState.network.nodeCount(); node++) total += domain.gasState.network.availableVolume(node); return total;
	}
	private static void close(double actual, double expected, double relative, String context) { check(Math.abs(actual - expected) <= relative * Math.max(1, Math.abs(expected)), context + ": expected=" + expected + " actual=" + actual); }
	private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
