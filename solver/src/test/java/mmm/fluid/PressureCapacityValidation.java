package mmm.fluid;

/** Short checks of the common capacity/gas incidence before continuous scenario validation. */
public final class PressureCapacityValidation {
	public static void main(String[] args) {
		if (args.length>1 || args.length==1&&!args[0].equals("--algebra")) throw new IllegalArgumentException("Expected --algebra or no selector");
		checkGraph(fixture(true)); checkGraph(fixture(false));
		fixedPorePressurePort(); vacuumCapacityClosure(); unresolvedGapKeepsVacuumPressureContact();
		disconnectedSupportsKeepTheirOwnVerticalLoad();
		if (args.length==1) { System.out.println("Common pressure incidence and transpose PASS"); return; }
		shallowHydrostatics(false); shallowHydrostatics(true);
		cutHydrostatics();
		System.out.println("Capacity/gas pressure adjoint, positive operator and shallow equilibrium PASS");
	}
	private static void fixedPorePressurePort() {
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(1, 1000, 0, .05F, 120, 1e-5F, .002F));
		domain.setVolume(1, 0, 0, .5F); domain.domainFlowFields.preparePhysicalState();
		var graph = domain.pressureSystem.faces; domain.pressureSystem.prepareOperator();
		int face=graph.offsets[0]+domain.domainFlowFields.xFaceIndex(1,0,0);
		double port=graph.fluidArea(face);
		domain.mac.velocityX[1]=-.25F; domain.pressureSystem.prepareOperator();
		close(graph.fluidArea(face),port,"Mixture pressure aperture changed with the transport donor direction");
		domain.mac.velocityX[1]=.25F; domain.pressureSystem.prepareOperator();
		close(graph.fluidArea(face),port,"Mixture pressure aperture changed after flow reversal");
		close(port,1,"A partial liquid cell lost its actual fixed pore port");
	}

	private static void vacuumCapacityClosure() {
		float dt=.05F;
		var d=new FluidDomain(2,2,2,new FluidSolverConfig(.5F,1000,0,dt,120,1e-5F,.002F));
		double cellCubicMeters=d.unitScale.cubicMetersPerNormalizedVolume();
		d.sealDomainBoundary();d.setVolumes(new float[]{.5F,1,1,1,1,1,1,.5F});d.domainFlowFields.preparePhysicalState();
		if(d.gasBoundary().nodeCount()!=2)throw new AssertionError("Capacity fixture requires two separated vacuum chambers");
		for(int node=1;node<=2;node++)d.setGasNodeStandardVolume(node,0);
		var liquids=new FluidMaterialRegistry(java.util.List.of(BuiltinFluidMaterials.WATER));
		d.materialRegistry=liquids;
		var thermal=new mmm.thermal.ThermalRegistry(java.util.List.of(
				new mmm.thermal.ThermalMaterialDefinition("test:capacity_water",mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,
						BuiltinFluidMaterials.WATER.id(),300,0,0,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1,2000,4184)))));
		d.bindThermal(thermal,liquids,300);d.domainFlowFields.preparePhysicalState();
		d.mac.velocityX[d.domainFlowFields.xFaceIndex(1,0,0)]=-100;d.cellMacStage.publishMomentum();
		var graph=d.pressureSystem.faces;d.pressureSystem.prepareOperator();
		var predicted=new double[d.cellCount+3];d.pressureSystem.addDivergence(faceVelocities(d,graph),predicted,1);
		double[] initialVolume={0,d.gasBoundary().availableVolume(1),d.gasBoundary().availableVolume(2)};
		boolean overrun=false;
		for(int node=1;node<=2;node++)overrun|=initialVolume[node]+dt*cellCubicMeters*predicted[d.cellCount+node]<0;
		if(!overrun)throw new AssertionError("Vacuum predictor did not exercise finite capacity overrun");
		double initialK=d.liquidKineticEnergyJoules();var initialU=d.exactInternalEnergy();var audit=d.thermalAudit();
		var stock=d.liquidCells.snapshot();var gas=d.gasBoundary().sealedInventory();
		var gasSource=d.gasBoundary().externalSource();var gasSink=d.gasBoundary().escapedSink();
		var result=d.pressureSystem.solveProjection(dt);
		if(!result.converged())throw new AssertionError("Finite vacuum capacity projection did not converge: "+result);
		d.pressureSystem.projectVelocities(dt);d.cellMacStage.publishMomentum();
		var corrected=new double[d.cellCount+3];d.pressureSystem.addDivergence(faceVelocities(d,graph),corrected,1);
		double volumeError=FlipPressurePhaseGeometry.CELL_VOLUME_RESOLUTION*cellCubicMeters;
		double pressureError=2*Math.ulp((float)mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE);boolean closed=false;
		for(int node=1;node<=2;node++) {
			double finalVolume=initialVolume[node]+dt*cellCubicMeters*corrected[d.cellCount+node];
			double absolute=d.pressureSystem.gasPressure[node]+mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE;
			if(finalVolume < -volumeError||absolute < -pressureError)
				throw new AssertionError("Vacuum projection left negative capacity or absolute pressure");
			if(finalVolume>volumeError&&Math.abs(absolute)>pressureError)
				throw new AssertionError("Positive vacuum volume acquired a nonzero contact pressure");
			closed|=finalVolume<=volumeError&&absolute>pressureError;
		}
		if(!closed)throw new AssertionError("Finite vacuum closure did not oppose the overshooting liquid predictor");
		if(d.liquidKineticEnergyJoules()>initialK*(1+8*Math.ulp(1F)))
			throw new AssertionError("Closed vacuum pressure projection generated kinetic energy without external work");
		var afterStock=d.liquidCells.snapshot();
		if(stock.size()!=afterStock.size())throw new AssertionError("Vacuum projection changed liquid stock keys");
		for(int row=0;row<stock.size();row++){
			var before=stock.stockAt(row);var after=afterStock.stockAt(row);
			if(!before.key().equals(after.key())||!before.volume().equals(after.volume())||before.massUnits()!=after.massUnits()
					||!java.util.Objects.equals(before.mixture(),after.mixture()))
				throw new AssertionError("Vacuum projection changed liquid mass, actual volume or species: "+before.key());
		}
		if(!d.exactGasInternalEnergy().equals(mmm.units.SignedDyadic.ZERO)
				||!d.gasBoundary().sealedInventory().equals(gas)||!d.gasBoundary().auditClosed()
				||!d.gasBoundary().externalSource().equals(gasSource)||!d.gasBoundary().escapedSink().equals(gasSink))
			throw new AssertionError("Vacuum projection changed gas inventory, energy or external transfers");
		double finalK=d.liquidKineticEnergyJoules();var finalU=d.exactInternalEnergy();var afterAudit=d.thermalAudit();
		var heat=finalU.subtract(initialU);double kineticLoss=initialK-finalK;
		// MAC velocities are binary32. Measure actual K and stored U independently of the heat plan.
		double energyError=8*Math.ulp(1F)*Math.max(1,Math.max(initialK,finalK));
		if(Math.abs(heat.toDouble()-kineticLoss)>energyError
				||Math.abs((finalU.toDouble()+finalK)-(initialU.toDouble()+initialK))
					>energyError+2*Math.ulp(Math.max(Math.abs(initialU.toDouble()),Math.abs(finalU.toDouble()))))
			throw new AssertionError("Vacuum closure failed K+U conservation: deltaU="+heat+" kineticLoss="+kineticLoss);
		var work=afterAudit.mechanicalWork().subtract(audit.mechanicalWork());
		var roundoff=afterAudit.roundoffResidual().subtract(audit.roundoffResidual());
		if(!heat.equals(work.add(roundoff))||Math.abs(work.toDouble()-kineticLoss)>energyError
				||!afterAudit.initialInventory().equals(audit.initialInventory())
				||!afterAudit.externalSource().equals(audit.externalSource())||!afterAudit.externalSink().equals(audit.externalSink())
				||!afterAudit.finiteDeviceExchange().equals(audit.finiteDeviceExchange())
				||!finalU.equals(afterAudit.expectedInventory()))
			throw new AssertionError("Vacuum closure mechanical exchange or storage roundoff entered the wrong audit source");
	}
	/** A subcell dry seam transmits weight while a half-cell cavity still permits free fall. */
	private static void unresolvedGapKeepsVacuumPressureContact() {
		for(float fill:new float[]{1-1F/128,.5F}) {
			var d=new FluidDomain(2,4,2,new FluidSolverConfig(1,1000,9.81F,.05F,120,1e-5F,.002F));
			float[] volumes=new float[d.cellCount];
			for(int z=0;z<2;z++)for(int x=0;x<2;x++)for(int y=0;y<3;y++)
				volumes[d.domainFlowFields.cellIndex(x,y,z)]=y==1?fill:1;
			d.sealDomainBoundary();d.setVolumes(volumes);d.domainFlowFields.preparePhysicalState();
			for(int node:d.gasState.network.nodeIds())d.setGasNodeStandardVolume(node,0);
			var graph=d.pressureSystem.faces;d.pressureSystem.prepareOperator();
			int middle=d.domainFlowFields.cellIndex(0,1,0),node=d.gasState.frame.commonCellNode(middle);
			if(node<=0||graph.pressureSupports[middle]!=0)
				throw new AssertionError("Hydrostatic support removed a real vacuum pressure degree of freedom");
			int interfaceFace=graph.offsets[1]+d.domainFlowFields.yFaceIndex(0,2,0);
			boolean contact=false;
			for(int entry=graph.start[interfaceFace];entry<graph.start[interfaceFace+1];entry++)
				contact|=graph.rowAt(entry)==d.cellCount+node&&graph.coefficientAt(entry)!=0;
			if(!contact)throw new AssertionError("Thin-seam support lost the cavity C/CT pressure contact");
			int topFace=graph.offsets[1]+d.domainFlowFields.yFaceIndex(0,3,0);
			double expected=fill>.9F?0:-d.config.gravity();
			if(Math.abs(graph.freeFallAcceleration(topFace)-expected)>1e-6)
				throw new AssertionError("Dry-seam support confused an unresolved layer with a resolved vacuum cavity");
		}
	}

	/** Separate fixed compartments contain a supported upper pocket and an airborne lower pocket. */
	private static void disconnectedSupportsKeepTheirOwnVerticalLoad(){
		var d=new FluidDomain(5,3,3,new FluidSolverConfig(1,1000,9.81F,.05F,120,1e-5F,.002F));
		byte[] masks=new byte[d.cellCount];java.util.Arrays.fill(masks,(byte)255);
		int supported=d.domainFlowFields.cellIndex(1,1,1),airborne=d.domainFlowFields.cellIndex(3,1,1);
		masks[supported]=(byte)(255^(1<<2));masks[airborne]=(byte)(255^(1<<5));
		masks[airborne-d.sizeX]=(byte)(255^(1<<7));masks[supported+d.sizeX]=(byte)(255^(1<<0));
		d.geometry.cutCells.restoreMicroMasks(masks);
		d.sealDomainBoundary();d.setVolume(1,1,1,.125F);d.setVolume(3,1,1,.125F);d.domainFlowFields.preparePhysicalState();
		var stock=d.liquidCells.snapshot();var graph=d.pressureSystem.faces;d.pressureSystem.prepareOperator();
		int lower=d.domainFlowFields.yFaceIndex(3,1,1),upper=d.domainFlowFields.yFaceIndex(1,2,1);
		close(graph.freeFallAcceleration(graph.offsets[1]+upper),0,"Airborne stock accelerated a disconnected supported pocket");
		close(graph.freeFallAcceleration(graph.offsets[1]+lower),-d.config.gravity(),"Supported stock held up a disconnected airborne pocket");
		d.bodyForceStage.applyGravity(d.config.timeStep());
		if(d.mac.velocityY[upper]!=0||Math.abs(d.mac.velocityY[lower]+d.config.gravity()*d.config.timeStep())>1e-6)
			throw new AssertionError("Disconnected vertical loads did not reach their own MAC faces");
		if(!stock.rows().equals(d.liquidCells.snapshot().rows()))throw new AssertionError("Gravity changed the component material inventory");
	}

	private static double[] faceVelocities(FluidDomain d,PressureFaceGraph graph) {
		double[] velocity=new double[graph.faceCount];
		for(int face=0;face<velocity.length;face++)velocity[face]=switch(graph.axis[face]) {
			case 0->d.mac.velocityX[graph.localFace[face]];case 1->d.mac.velocityY[graph.localFace[face]];default->d.mac.velocityZ[graph.localFace[face]];
		};
		return velocity;
	}

	private static FluidDomain fixture(boolean full) {
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(.5F, 1000, 0, .05F, 120, 1e-5F, .002F));
		float amount = full ? 1 : .5F; float[] points = new float[32];
		for (int cell = 0; cell < 8; cell++) {
			points[4 * cell] = (cell % 2 + .5F) * .5F; points[4 * cell + 1] = (cell / 2 % 2 + .5F) * .5F;
			points[4 * cell + 2] = (cell / 4 + .5F) * .5F; points[4 * cell + 3] = !full && cell / 2 % 2 == 0 ? 1 : amount;
		}
		domain.restoreParticleState(new FlipParticleState(2, 2, 2, .5F, 8, full ? 8 : 6, points, new float[24],
				mmm.units.Mass.createLiquid(.5F, 1000, points), new byte[8]));
		domain.sealDomainBoundary(); domain.domainFlowFields.preparePhysicalState(); return domain;
	}
	private static void checkGraph(FluidDomain domain) {
		var graph = domain.pressureSystem.faces; domain.pressureSystem.prepareOperator();
		int rows = domain.cellCount + domain.gasState.network.nodeCount() + 1;
		double[] p = new double[rows], q = new double[rows], velocity = new double[graph.faceCount];
		for (int i = 0; i < rows; i++) { p[i] = Math.sin(i + .3); q[i] = Math.cos(i + .7); }
		for (int i = 0; i < velocity.length; i++) velocity[i] = Math.sin(i * .7);
		double[] divergence = new double[rows], gradient = new double[velocity.length];
		domain.pressureSystem.addDivergence(velocity, divergence, 1); domain.pressureSystem.pressureVelocity(p, gradient);
		double first = dot(p, divergence), second = 0;
		for (int face = 0; face < velocity.length; face++) if (graph.inverseMass[face] > 0)
			second += velocity[face] * gradient[face] / graph.inverseMass[face];
		close(first, second, "Volume incidence and pressure force are not transposes");
		double[] ap = apply(domain.pressureSystem, p), aq = apply(domain.pressureSystem, q);
		close(dot(p, aq), dot(q, ap), "Pressure operator is asymmetric");
		if (dot(p, ap) < -1e-12) throw new AssertionError("Pressure inertia is not positive");
	}
	private static double[] apply(PressureSystem pressure, double[] source) {
		var graph=pressure.faces;
		double[] response = new double[graph.faceCount], result = new double[source.length];
		pressure.pressureVelocity(source, response); pressure.addDivergence(response, result, 1); return result;
	}
	private static void shallowHydrostatics(boolean column) {
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(.5F, 1000, 9.81F, .025F, 120, 1e-5F, .002F));
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
	static void cutHydrostatics(){
		float h=.5F,dt=.025F,amount=.2F;
		var domain=new FluidDomain(2,2,2,new FluidSolverConfig(h,1000,9.81F,dt,120,1e-5F,.002F));
		float[] points=new float[32];byte[] masks=new byte[8];
		for(int cell=0;cell<8;cell++){
			int y=cell/2%2;points[4*cell]=(cell%2+.5F)*h;points[4*cell+1]=(y==0?.5F:1.1F)*h;
			points[4*cell+2]=(cell/4+.5F)*h;points[4*cell+3]=y==0?1:amount;
			if(y==1)masks[cell]=(byte)0xcc; // Only the bottom half is open; wet depth is amount*h.
		}
		domain.sealDomainBoundary();domain.restoreParticleState(new FlipParticleState(2,2,2,h,8,4+4*amount,points,new float[24],
				mmm.units.Mass.createLiquid(h,1000,points),masks));
		long mass=domain.totalMassUnits();var volume=domain.liquidCells.exactVolume();
		for(int step=0;step<3;step++){
			domain.stepMechanicsOnly();
			double partial=1000*9.81*h*.5*amount,below=1000*9.81*h*(.5+amount);
			if(Math.abs(domain.pressure(0,1,0)-partial)>.1||Math.abs(domain.pressure(0,0,0)-below)>.1)
				throw new AssertionError("Bottom-half cut hydrostatic pressure used open fraction as wet height");
			if(domain.maximumParticleSpeed()>2*mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION*h/dt)
				throw new AssertionError("A stationary horizontal cut layer acquired resolved motion");
			if(domain.totalMassUnits()!=mass||!domain.liquidCells.exactVolume().equals(volume))
				throw new AssertionError("Cut hydrostatic projection changed liquid inventory");
		}
	}
	private static void close(double first, double second, String reason) {
		if (Math.abs(first - second) > 1e-11 * Math.max(1, Math.max(Math.abs(first), Math.abs(second)))) throw new AssertionError(reason + ": " + first + " != " + second);
	}
	private static double dot(double[] a, double[] b) { double total = 0; for (int i = 0; i < a.length; i++) total += a[i] * b[i]; return total; }
}
