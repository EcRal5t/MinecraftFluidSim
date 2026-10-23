package mmm.fluid;

import mmm.simulation.FullCapabilityTestFixture;
import mmm.thermal.ThermalTransport;
import mmm.world.MovingBodyFluidCoupling;

/** Actual body work, stationary-wall reaction and local heat share one momentum publication. */
public final class MovingBodyCouplingValidation {
	public static void main(String[] args) throws Exception {
		if (java.util.Arrays.asList(args).contains("--water-contact")) {
			wetGeometryAndHydrostaticPressure();
			contactWaterExtraction();
			System.out.println("Contact water extraction preserves full material/species/U inventories");
			return;
		}
		wetGeometryAndHydrostaticPressure();
		contactWaterExtraction();
		var registries=FullCapabilityTestFixture.load();
		var d=new FluidDomain(5,4,4,new FluidSolverConfig(.5F,1000,0,.025F,120,1e-5F,.002F));
		FullCapabilityTestFixture.bindEmptyMaterialState(d,registries.liquids());
		d.sealDomainBoundary();
		for(int z=0;z<4;z++)for(int y=0;y<4;y++)for(int x=0;x<5;x++) {
			if(x==2)d.setSolid(x,y,z,true);else d.setVolume(x,y,z,.75F);
		}
		FullCapabilityTestFixture.bindFull(d,registries,293.15,ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
        var volume=d.liquidCells.exactVolume();long mass=d.totalMassUnits();
        var gas=d.gasBoundary().sealedInventory();
        var id=java.util.UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        var bounds=new MovingBodyFluidCoupling.Bounds(.65,.55,.65,.85,.75,.85);
        var backend=new CpuFluidSolverBackend();
        d=backend.evaluate(new FluidStepRequest(0,d.captureAuthoritativeState(),java.util.List.of(new MovingBodyFluidCoupling.Request(id,bounds,0,0,0)),false,0)).claimPreparedCpuDomain();
        var initialU=d.exactInternalEnergy(); double sumWork=0, maximumTemperatureChange=0;
        double[] initialTemperature=new double[d.cellCount];
        for(int cell=0;cell<d.cellCount;cell++)if(!d.liquidCells.stocksInCell(cell).isEmpty())
            initialTemperature[cell]=d.thermalTemperatureInCell(cell%d.sizeX,cell/d.sizeX%d.sizeY,cell/(d.sizeX*d.sizeY));
        for(int step=0;step<32;step++) {
            d.domainFlowFields.ensureVelocityGrid();double beforeK=kinetic(d);var beforeU=d.exactInternalEnergy();
            double allowance=energyStorageAllowance(d);
            double vx=step<8?.2:step<16?0:step<24?-.2:0;
            var candidate=backend.evaluate(new FluidStepRequest(step+1,d.captureAuthoritativeState(),java.util.List.of(new MovingBodyFluidCoupling.Request(id,bounds,vx,0,0)),false,0));
            d=candidate.claimPreparedCpuDomain();var result=candidate.movingBodyCoupling();
            sumWork+=result.externalWorkJoules();
            for(int cell=0;cell<d.cellCount;cell++)if(initialTemperature[cell]>0&&!d.liquidCells.stocksInCell(cell).isEmpty())
                maximumTemperatureChange=Math.max(maximumTemperatureChange,Math.abs(d.thermalTemperatureInCell(cell%d.sizeX,cell/d.sizeX%d.sizeY,cell/(d.sizeX*d.sizeY))-initialTemperature[cell]));
            double deltaK=kinetic(d)-beforeK,deltaU=d.exactInternalEnergy().subtract(beforeU).toDouble();
            allowance+=energyStorageAllowance(d);
            double scale=Math.max(1,Math.abs(deltaK)+Math.abs(deltaU)+Math.abs(result.externalWorkJoules()));
            require(Math.abs(deltaK+deltaU-result.externalWorkJoules())<=16*Math.ulp((float)scale)+allowance,
                    "Prescribed pressure work did not close actual K/U: dK="+deltaK+", dU="+deltaU+", W="+result.externalWorkJoules());
            require(d.totalMassUnits()==mass&&d.liquidCells.exactVolume().equals(volume)&&d.gasBoundary().sealedInventory().equals(gas)
                    &&d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),"Body pressure opened inventory or energy audit");
            require(result.bodyImpulses().size()==1&&result.bodyImpulses().getFirst().bodyId().equals(id),"Body reaction lost stable identity");
        }
        System.out.printf("  prescribed body drive W=%.9g J dU=%.9g J maxDeltaT=%.9g K%n",
                sumWork,d.exactInternalEnergy().subtract(initialU).toDouble(),maximumTemperatureChange);

		var saved=d.captureAuthoritativeState();var restored=FluidDomain.restoreAuthoritativeState(saved);
		require(saved.liquid().stocks().equals(restored.liquidCells.stocks())
				&&java.util.Arrays.equals(saved.momentum().copyX(),restored.exportMacMomentum().copyX())
				&&restored.exactInternalEnergy().equals(d.exactInternalEnergy()),
				"Body/wall energy or momentum changed during recovery");
		System.out.println("Moving-body work, fixed-wall impulse, actual heat and recovery passed");
	}
	private static void contactWaterExtraction() throws Exception {
		var registries=FullCapabilityTestFixture.load();
		var domain=new FluidDomain(3,3,3,new FluidSolverConfig(.5F,1000,0,.025F,120,1e-5F,.002F));
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(domain,registries.liquids());
		domain.bindGasRegistry(registries.gas());
		domain.setVolume(1,1,1,.02F);
		domain.bindChemistry(registries.chemical());
		var stock=domain.liquidCells.physicalStockAt(0);
		var chemistry=registries.chemical();
		var composition=mmm.chemistry.ChemicalMixtureRecord.fromSolutesAndSolventMass(chemistry,
				java.util.Map.of(mmm.chemistry.BuiltinChemicalDefinitions.CO2,1000L),
				mmm.chemistry.BuiltinChemicalDefinitions.H2O,stock.massUnits());
		domain.setMarkerChemical(0,composition.stableAmounts(chemistry));
		domain.bindThermal(registries.thermal(),registries.liquids(),300,8);
		var side=new FluidDomain.WaterContactFace(0,.5,.5,.75,.5,.75,1);
		var duplicate=java.util.List.of(side,side);
		var contacts=domain.waterContacts(duplicate);
		require(contacts.size()==1&&contacts.getFirst().areaSquareMetres()>0
				&&Math.abs(contacts.getFirst().waterVolumeCubicMetres()-domain.unitScale.cubicMeters(domain.totalVolumeExact()))<1e-15,
				"Several soil faces counted a contacted water stock more than once");
		long beforeMass=domain.totalMassUnits();
		var beforeVolume=domain.liquidCells.exactVolume();
		var beforeAudit=domain.thermalAudit();
		var transfer=domain.extractContactWater(duplicate,1_000_000L);
		require(transfer.transferred()&&transfer.actualNanos()==1_000_000L
				&&domain.totalMassUnits()+transfer.portions().stream().mapToLong(FluidDomain.MaterialPortion::massUnits).sum()==beforeMass
				&&domain.liquidCells.exactVolume().add(mmm.units.SignedDyadic.of(
						transfer.portions().stream().mapToDouble(FluidDomain.MaterialPortion::volume).sum())).equals(beforeVolume)
				&&domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				&&domain.thermalAudit().externalSink().subtract(beforeAudit.externalSink()).equals(
						mmm.units.SignedDyadic.of(transfer.internalEnergy())),
				"One-litre soil uptake did not preserve actual liquid mass, volume and U settlement");
		for(var entry:composition.stableAmounts(chemistry).entrySet()) {
			long retained=0;
			for(var row:domain.liquidCells.stocks()) retained+=row.mixture().amount(chemistry.speciesHandle(entry.getKey()));
			long taken=transfer.ledger().groups().stream().mapToLong(group->group.speciesQuanta().getOrDefault(entry.getKey(),0L)).sum();
			require(retained+taken==entry.getValue(),"Soil uptake lost dissolved species "+entry.getKey());
		}
		var remaining=domain.liquidCells.exactVolume();var audit=domain.thermalAudit();long mass=domain.totalMassUnits();
		require(!domain.extractContactWater(duplicate,100_000_000L).transferred()
				&&domain.liquidCells.exactVolume().equals(remaining)&&domain.totalMassUnits()==mass&&domain.thermalAudit()==audit,
				"Insufficient contact water partially debited the source");
	}

	private static void wetGeometryAndHydrostaticPressure() {
		var domain = new FluidDomain(4,4,4,new FluidSolverConfig(.5F,1000,9.81F,.025F,120,1e-5F,.002F));
		domain.sealDomainBoundary();
		float[] volumes = new float[64]; java.util.Arrays.fill(volumes, 1);
		domain.setVolumes(volumes);
		var sample = domain.sampleAxisAlignedBody(.6,.1,.6,.9,.4,.9);
		double expectedVolume=.3*.3*.3;
		require(Math.abs(sample.displacedVolumeCubicMetres()-expectedVolume)<1e-12
				&&Math.abs(sample.wetSurfaceAreaSquareMetres()-.54)<1e-12
                &&Math.abs(sample.waterContactVolumeCubicMetres()-expectedVolume)<1e-12,
				"Body/PLIC intersection lost submerged volume or wet surface area");
		require(Math.abs(sample.pressureForceYNewtons()-1000.0*domain.config().gravity()*expectedVolume)<1e-7
				&&Math.abs(sample.pressureForceXNewtons())<1e-7&&Math.abs(sample.pressureForceZNewtons())<1e-7,
				"Wet body pressure integral disagrees with displaced hydrostatic weight: " + sample);
		var empty = new FluidDomain(3,3,3,new FluidSolverConfig(.5F,1000,9.81F,.025F,120,1e-5F,.002F));
		empty.setVolume(1,1,1,.1F);
		var dry=empty.sampleAxisAlignedBody(.55,.8,.55,.7,.95,.7);
		require(dry.displacedVolumeCubicMetres()==0&&dry.wetSurfaceAreaSquareMetres()==0&&dry.waterContactVolumeCubicMetres()==0,
				"Body above the actual PLIC surface acquired artificial immersion");
		var layered = new FluidDomain(3,3,3,new FluidSolverConfig(.5F,1000,9.81F,.025F,120,1e-5F,.002F));
		layered.bindMaterialRegistry(new FluidMaterialRegistry(java.util.List.of(
				new FluidMaterial("mmm:water",1000,"mmm:aqueous",0,Math.PI/2),
				new FluidMaterial("mmm:oil",800,"mmm:oil",0,Math.PI/2))));
		require(layered.tryInsertExactMixture(1,1,1,java.util.List.of(
				new FluidDomain.MaterialPortion(0,.5,mmm.units.Mass.fromKilograms(62.5)),
				new FluidDomain.MaterialPortion(1,.5,mmm.units.Mass.fromKilograms(50))),0).transferred(),
				"Water/oil body-contact fixture could not be inserted");
		var oilOnly = layered.sampleAxisAlignedBody(.6,.8,.6,.9,.9,.9);
		var waterAndOil = layered.sampleAxisAlignedBody(.6,.7,.6,.9,.8,.9);
		require(oilOnly.displacedVolumeCubicMetres()>0&&oilOnly.waterContactVolumeCubicMetres()==0
				&&waterAndOil.waterContactVolumeCubicMetres()>0
				&&waterAndOil.waterContactVolumeCubicMetres()<waterAndOil.displacedVolumeCubicMetres(),
				"Body wet contact confused oil with water or lost water at the material interface");
		var oilFace = new FluidDomain.WaterContactFace(0,.6,.8,.9,.6,.9,1);
		var mixedFace = new FluidDomain.WaterContactFace(0,.6,.7,.8,.6,.9,1);
		var beforeContact = layered.liquidCells.exactVolume();
		require(layered.waterContacts(java.util.List.of(oilFace)).isEmpty()
				&&!layered.extractContactWater(java.util.List.of(oilFace),1_000_000).transferred()
				&&!layered.waterContacts(java.util.List.of(mixedFace)).isEmpty()
				&&layered.liquidCells.exactVolume().equals(beforeContact),
				"Soil contact drew through oil or changed an observed water inventory");
		var partial = new FluidDomain(3,3,3,new FluidSolverConfig(.5F,1000,9.81F,.025F,120,1e-5F,.002F));
		partial.sealDomainBoundary(); partial.setVolume(1,1,1,.5F);
		var before = partial.sampleAxisAlignedBody(.6,.6,.6,.9,.9,.9);
		int node = partial.gasNodeAt(.625,.875,.625);
		partial.setGasNodeStandardVolume(node, 2 * partial.gasBoundary().standardVolume(node));
		var after = partial.sampleAxisAlignedBody(.6,.6,.6,.9,.9,.9);
		require(Math.abs(before.pressureForceYNewtons()-after.pressureForceYNewtons())<1e-7,
				"Uniform cavity pressure created a false force on a partially immersed body");
	}
	private static double[] momentum(FluidDomain d){
		return new double[]{java.util.Arrays.stream(d.mac.momentumX).sum(),java.util.Arrays.stream(d.mac.momentumY).sum(),java.util.Arrays.stream(d.mac.momentumZ).sum()};
	}
	private static double[] wall(FluidDomain d){return new double[]{d.lastLiquidBoundaryImpulseX,d.lastLiquidBoundaryImpulseY,d.lastLiquidBoundaryImpulseZ};}
	private static double energyStorageAllowance(FluidDomain d){
		double allowance=0;for(var stock:d.liquidCells.physicalStocks())allowance+=2*Math.ulp(stock.internalEnergy());return allowance;
	}
	private static double kinetic(FluidDomain d){
		double value=0;double[][] p={d.mac.momentumX,d.mac.momentumY,d.mac.momentumZ};
		double[][] mass={d.cellMacStage.massX,d.cellMacStage.massY,d.cellMacStage.massZ};
		for(int axis=0;axis<3;axis++)for(int i=0;i<p[axis].length;i++)if(mass[axis][i]>0)value+=.5*p[axis][i]*p[axis][i]/mass[axis][i];
		return value;
	}
	private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
