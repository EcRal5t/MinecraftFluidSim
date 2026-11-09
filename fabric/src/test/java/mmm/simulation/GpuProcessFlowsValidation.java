package mmm.simulation;

import mmm.chemistry.*;
import mmm.fluid.*;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.granular.*;
import mmm.thermal.*;
import mmm.transfer.*;
import mmm.units.Mass;
import mmm.worldprofile.WorldCapability;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.nio.file.Files;
import java.util.*;

/** Controller processes using actual containers, finite carriers, solver and checkpoint paths. */
public final class GpuProcessFlowsValidation {
    private GpuProcessFlowsValidation() {}
    public static void main(String[] args) throws Exception {
        String scenario=args.length==0?"heat":args[0];int steps=args.length<2?20:Integer.parseInt(args[1]);
        if(scenario.startsWith("industrial") && !scenario.contains(",")) {
            System.out.println("process_start scenario="+scenario);
            try {
                switch(scenario) {
                    case "industrial" -> industrial(steps);
                    case "industrial_valve" -> industrialValve(steps);
                    case "industrial_heat" -> industrialHeat(steps);
                    case "industrial_adsorption" -> industrialAdsorption(steps);
                    case "industrial_catalysis" -> industrialCatalysis(steps);
                    case "industrial_screen" -> industrialScreen(steps);
                    case "industrial_crystallization" -> industrialCrystallization(steps);
                    case "industrial_distillation" -> industrialDistillation(steps);
                    default -> throw new IllegalArgumentException("Unknown industrial scenario: "+scenario);
                }
                System.out.println("process_complete scenario="+scenario);
            } catch(Exception|AssertionError failure) { failure.printStackTrace(); throw failure; }
            return;
        }
        if(scenario.equals("all")||scenario.contains(",")){
            var failures=new ArrayList<Throwable>();
            var selected=scenario.equals("all")?List.of("heat","absorption","precipitation","evaporation","carbonation","drive"):List.of(scenario.split(","));
            for(String flow:selected)try {
                main(new String[]{flow,Integer.toString(steps)});
            } catch(Exception|AssertionError failure) {
                failure.printStackTrace();
                failures.add(new IllegalStateException("Process scenario "+flow+" failed",failure));
            }
            if(!failures.isEmpty()){
                var failure=new AssertionError("Process scenarios failed: "+failures.size());
                failures.forEach(failure::addSuppressed);throw failure;
            }
            return;
        }
        if(!Set.of("heat","absorption","precipitation","evaporation","carbonation","drive").contains(scenario)||steps<2)throw new IllegalArgumentException("Expected heat, absorption, precipitation, evaporation, carbonation, drive or all and at least two steps");
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-process-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        if(startup.creationLocked())throw new IllegalStateException(startup.blockingSummary());
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);var fixture=GpuControllerFixture.createCrossBlockProcess(startup,world,manager,operations,scenario.equals("evaporation"));
        var region=fixture.region;String wallObject=null,coldWallObject=null;double wallInitial=0,coldInitial=0;
        if(scenario.equals("evaporation"))fixture.ventGas();
        if(scenario.equals("heat")||scenario.equals("evaporation")){
            var wall=finiteWall(region,fixture.portCell,world.thermalRegistry(),scenario.equals("heat")?350:420,null);
            var installed=new ArrayList<FiniteThermalDeviceState>();installed.add(wall);
            wallObject=wall.objectId();wallInitial=wall.body().internalEnergyJ();
            if(scenario.equals("evaporation")){
                var cold=finiteWall(region,fixture.portCell,world.thermalRegistry(),285,wall.objectId());
                coldWallObject=cold.objectId();coldInitial=cold.body().internalEnergyJ();installed.add(cold);
                installed.add(finiteWallAt(region,world.thermalRegistry(),285,region.origin().offset(5,2,1)));
                installed.add(finiteWallAt(region,world.thermalRegistry(),285,region.origin().offset(6,2,0)));
                installed.add(finiteWallAt(region,world.thermalRegistry(),285,region.origin().offset(4,1,0)));
                installed.add(finiteWallAt(region,world.thermalRegistry(),285,region.origin().offset(4,2,1)));
            }
            installFiniteWalls(region,world.thermalRegistry(),installed);
        }
        if(!scenario.equals("evaporation"))fixture.transfer(source(scenario,world));
        long feedCarbon=scenario.equals("absorption")?liquidInorganicCarbon(region.hostSnapshot().captureAuthoritativeState()):0;
        long seedVolume=0;
        if(scenario.equals("precipitation")){
            // A harvested salt crystal provides real area for a practical batch crystallizer.
            fixture.transfer(saltSeed(world));
            seedVolume=region.hostSnapshot().exportGranularState().totalSolidVolumeSvq();
            if(seedVolume<=0)throw new AssertionError("Crystal feed did not enter the region");
        }
        if(scenario.equals("absorption")||scenario.equals("carbonation"))fixture.gasTransfer(firstGasCell(region,fixture.portCell),BuiltinGasDefinitions.CO2,false);
        try(var backend=MmmRuntimeFactory.createSolverBackend()){
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            MinecraftProcessOperations drive=null;double positiveDriveWork=0,negativeDriveWork=0;boolean drivenContact=false;
            int motionSteps=scenario.equals("drive")?Math.max(40,steps):scenario.equals("evaporation")?Math.max(1200,steps):steps;
            long[] motionNanos=new long[motionSteps+(scenario.equals("absorption")?steps:0)];int timedMotions=0;
            long collectionStartEthanol=0,collectionStartWater=0;
            if(scenario.equals("evaporation"))System.out.println("distillation_duration steps="+motionSteps+" seconds="+(motionSteps*region.solverConfig().timeStep()));
            if(scenario.equals("drive")){
                var stock=region.hostSnapshot().exportLiquidCellState().physicalStocks().stream().findFirst().orElseThrow();
                int c=stock.key().cell(),x=region.grid().x(c),y=region.grid().y(c),z=region.grid().z(c);
                drive=new MinecraftProcessOperations(startup.worldRoot(),manager,null,startup);
                drive.setDrive(region.id(),new MinecraftProcessOperations.Drive(region.tick(),region.tick()+motionSteps,x,y,z,region.solverConfig().cellSize(),0,null));
                region.wakeForActivity();
            }
            for(int step=0;step<motionSteps;step++){
                progress(scenario,step,motionSteps);
                if(scenario.equals("evaporation")&&step<300&&step%20==0){
                    motion.settlePendingPhaseWork(region);
                    fixture.transfer(ethanolSolution(world,380.0,.05));available(region);
                }else if(!scenario.equals("evaporation")&&step==motionSteps/3){
                    motion.settlePendingPhaseWork(region);
                    long carbonBeforeFeed=scenario.equals("absorption")?liquidInorganicCarbon(region.hostSnapshot().captureAuthoritativeState()):0;
                    fixture.transfer(source(scenario,world));available(region);
                    if(scenario.equals("absorption"))feedCarbon=Math.addExact(feedCarbon,liquidInorganicCarbon(region.hostSnapshot().captureAuthoritativeState())-carbonBeforeFeed);
                }
                // Drain startup entrainment through the output port before collecting the steady distillate.
                if(scenario.equals("evaporation")&&step==300){
                    motion.settlePendingPhaseWork(region);
                    fixture.scoopMixtureAt(new int[]{11,4,1});
                    var startupOutput=mmm.item.ContainerItemStacks.chemicalContents(fixture.actor.current,world.chemicalRegistry());
                    System.out.println("distillation_startup_output nanos="+startupOutput.totalVolumeNanos());
                    var gas=region.hostSnapshot().captureAuthoritativeState().spatialGas();
                    collectionStartEthanol=gasPhaseSinkQuanta(gas,BuiltinGasDefinitions.ETHANOL_VAPOR,BuiltinChemicalDefinitions.ETHANOL);
                    collectionStartWater=gasPhaseSinkQuanta(gas,BuiltinGasDefinitions.WATER_VAPOR,BuiltinChemicalDefinitions.H2O);
                    available(region);
                }
                var bodies=drive==null?List.<mmm.world.MovingBodyFluidCoupling.Request>of():drive.bodies(region,region.incrementTick());
                long motionStart=System.nanoTime();var result=motion.step(region,bodies);
                motionNanos[timedMotions++]=System.nanoTime()-motionStart;
                if(!result.accepted())throw new AssertionError("Process motion rejected at "+step);
                if(drive!=null){double work=result.movingBodyCoupling().externalWorkJoules();
                    positiveDriveWork+=Math.max(0,work);negativeDriveWork+=Math.min(0,work);
                    drivenContact|=result.movingBodyCoupling().coupledVolumeCubicMetres()>0;
                }
            }
            if(drive!=null){
                if(!drive.bodies(region,region.tick()).isEmpty()||drive.drive(region.id())!=null)throw new AssertionError("Expired drive did not stop");
                var momentum=region.hostSnapshot().captureAuthoritativeState().momentum();
                double maxMomentum=0;for(double[] axis:List.of(momentum.copyX(),momentum.copyY(),momentum.copyZ()))
                    for(double value:axis)maxMomentum=Math.max(maxMomentum,Math.abs(value));
                if(!drivenContact||!(positiveDriveWork>0)||!(negativeDriveWork<0)||!(maxMomentum>0))
                    throw new AssertionError("Prescribed drive did not exchange work and momentum: positive="+positiveDriveWork+" negative="+negativeDriveWork+" maxMomentum="+maxMomentum);
            }
            // Input is stopped here; the remaining state is sampled before a real container extraction.
            var before=region.hostSnapshot().captureAuthoritativeState();
            var first=before.liquid().physicalStocks().stream().filter(stock -> region.grid().x(stock.key().cell()) >= 8)
                    .filter(stock -> !scenario.equals("carbonation") || stock.mixture()!=null &&
                            stock.mixture().stableAmounts(before.chemicalRegistry()).getOrDefault(BuiltinChemicalDefinitions.HCO3_MINUS,0L)>0)
                    .findFirst().orElseThrow(() -> new AssertionError("Process did not transport liquid across the 8-cell block face"));int cell=first.key().cell();
            int x=region.grid().x(cell),y=region.grid().y(cell),z=region.grid().z(cell);
            var sample=region.observeCellInventory(x,y,z);if(sample==null)throw new AssertionError("Process sample lost liquid");
            System.out.println("process_sample scenario="+scenario+" "+sample);
            int[] coldSample=scenario.equals("evaporation")?coldWallSample(region,world,coldWallObject):null;
            if(scenario.equals("heat")||scenario.equals("evaporation")){
                var walls=region.finiteThermalDevices(world.thermalRegistryFingerprint());
                String selectedWall=wallObject;
                var body=walls.devices().stream().filter(value->value.objectId().equals(selectedWall)).findFirst().orElseThrow();
                if(!(body.body().internalEnergyJ()<wallInitial))throw new AssertionError("Finite wall did not heat its fluid: "+selectedWall+" initial="+wallInitial+" after="+body.body().internalEnergyJ());
                if(scenario.equals("evaporation")){
                    String selectedCold=coldWallObject;
                    var cold=walls.devices().stream().filter(value->value.objectId().equals(selectedCold)).findFirst().orElseThrow();
                    if(!(cold.body().internalEnergyJ()>coldInitial)){
                        coldWallSample(region,world,coldWallObject);
                        throw new AssertionError("Cold wall did not collect heat: initial="+coldInitial+" final="+cold.body().internalEnergyJ());
                    }
                }
            }
            if(scenario.equals("absorption")&&liquidInorganicCarbon(before)<=feedCarbon)
                throw new AssertionError("CO2 did not enter the liquid through its gas contact: feed="+feedCarbon+" liquid="+liquidInorganicCarbon(before));
            if(scenario.equals("carbonation")&&liquidSpecies(before,BuiltinChemicalDefinitions.HCO3_MINUS)<=0)
                throw new AssertionError("CO2 and the supplied alkali did not produce bicarbonate");
            if(scenario.equals("precipitation")&&region.hostSnapshot().exportGranularState().totalSolidVolumeSvq()<=seedVolume)
                throw new AssertionError("Concentrated salt did not grow the shared crystal inventory");
            if(scenario.equals("absorption")){
                long absorbed=liquidInorganicCarbon(before);motion.settlePendingPhaseWork(region);
                var gas=region.hostSnapshot().captureAuthoritativeState().spatialGas();
                for(int c=0;c<gas.cells().size();c++)if(gas.cells().get(c).components().stream().anyMatch(v->v.componentId().equals(BuiltinGasDefinitions.CO2)&&v.standardVolumeNanos()>0))
                    fixture.gasTransfer(c,BuiltinGasDefinitions.CO2,true);
                var afterVent=region.hostSnapshot().captureAuthoritativeState();
                printStripState("after_vent",afterVent);
                available(region);for(int step=0;step<steps;step++){
                    long motionStart=System.nanoTime();var result=motion.step(region,List.of());
                    motionNanos[timedMotions++]=System.nanoTime()-motionStart;
                    if(!result.accepted())throw new AssertionError("Gas stripping motion rejected");
                }
                var afterStrip=region.hostSnapshot().captureAuthoritativeState();long remaining=liquidInorganicCarbon(afterStrip);
                printStripState("after_steps",afterStrip);
                if(remaining>=absorbed)throw new AssertionError("Dissolved CO2 did not desorb after gas extraction");
            }
            Arrays.sort(motionNanos,0,timedMotions);
            System.out.printf(Locale.ROOT,"process_motion_cost scenario=%s steps=%d median_ms=%.3f p95_ms=%.3f max_ms=%.3f%n",
                    scenario,timedMotions,motionNanos[(timedMotions-1)/2]*1e-6,
                    motionNanos[(int)Math.ceil(.95*timedMotions)-1]*1e-6,motionNanos[timedMotions-1]*1e-6);
            if(scenario.equals("evaporation")){
                var phase=before.spatialGas();
                if(phase.phaseSource().component(BuiltinGasDefinitions.ETHANOL_VAPOR)==null||phase.phaseSink().component(BuiltinGasDefinitions.ETHANOL_VAPOR)==null)
                    throw new AssertionError("Heated mixture did not evaporate and condense after cooling");
            }
            motion.settlePendingPhaseWork(region);long prior=region.hostSnapshot().totalMassUnits();
            var gasBeforeOutput=scenario.equals("evaporation")?region.hostSnapshot().captureAuthoritativeState().spatialGas():null;
            if(scenario.equals("heat"))fixture.scoop();
            else if(scenario.equals("evaporation"))fixture.scoopMixtureAt(coldSample);
            else if(scenario.equals("carbonation"))fixture.scoopMixtureAt(new int[]{x,y,z});
            else fixture.scoopMixture();
            if(region.hostSnapshot().totalMassUnits()>=prior)throw new AssertionError("Process output container did not receive liquid");
            if(scenario.equals("evaporation")){
                var output=mmm.item.ContainerItemStacks.chemicalContents(fixture.actor.current,world.chemicalRegistry());
                if(output==null)throw new AssertionError("Cold-wall collection did not retain its composition");
                long ethanol=0,water=0;
                for(var group:output.groups())for(var species:group.species()){
                    if(species.speciesId().equals(BuiltinChemicalDefinitions.ETHANOL))ethanol=Math.addExact(ethanol,species.amountQuanta());
                    if(species.speciesId().equals(BuiltinChemicalDefinitions.H2O))water=Math.addExact(water,species.amountQuanta());
                }
                double collected=ethanol/(double)(ethanol+water),feed=8_576_086_956.0/(27_777_777_777.0+8_576_086_956.0);
                long condensedEthanol=gasPhaseSinkQuanta(gasBeforeOutput,BuiltinGasDefinitions.ETHANOL_VAPOR,BuiltinChemicalDefinitions.ETHANOL)-collectionStartEthanol;
                long condensedWater=gasPhaseSinkQuanta(gasBeforeOutput,BuiltinGasDefinitions.WATER_VAPOR,BuiltinChemicalDefinitions.H2O)-collectionStartWater;
                System.out.println("cold_wall_collection nanos="+output.totalVolumeNanos()+" ethanol="+ethanol+" water="+water+" condensed_ethanol="+condensedEthanol+" condensed_water="+condensedWater+" feed_fraction="+feed+" collected_fraction="+collected);
                if(output.totalVolumeNanos()<=0||ethanol<=0||ethanol>condensedEthanol||water>condensedWater||!(collected>feed))
                    throw new AssertionError("Cold-wall collection did not grow and enrich ethanol: feed="+feed+" collected="+collected+" ethanol="+ethanol+" water="+water+" condensed_ethanol="+condensedEthanol+" condensed_water="+condensedWater);
            }
            if(scenario.equals("carbonation")){
                var output=mmm.item.ContainerItemStacks.chemicalContents(fixture.actor.current,world.chemicalRegistry());
                if(output==null||output.groups().stream().flatMap(g->g.species().stream()).noneMatch(v->v.speciesId().equals(BuiltinChemicalDefinitions.HCO3_MINUS)&&v.amountQuanta()>0))
                    throw new AssertionError("Collected alkali solution did not retain bicarbonate");
            }
            long saved=operations.checkpoint(region);var savedMass=region.hostSnapshot().totalMassUnits();
            long savedBicarbonate=scenario.equals("carbonation")?liquidSpecies(region.hostSnapshot().captureAuthoritativeState(),BuiltinChemicalDefinitions.HCO3_MINUS):0;
            if(!region.releaseSolverAfterCheckpoint(saved))throw new AssertionError("Process checkpoint did not unload");
            var r=startup.registries();var repository=new RegionRepositoryAdapter(startup.worldRoot(),world.profile(),r.chemical(),r.granular(),r.thermal(),r.gas(),startup.config().maximumThermalSubstepsPerStep(),r.material());
            if(!repository.restoreIncrementally(region,0,Integer.MAX_VALUE).restored())throw new AssertionError("Process checkpoint did not restore");
            if(region.hostSnapshot().totalMassUnits()!=savedMass)throw new AssertionError("Process restore changed mass");
            if(scenario.equals("carbonation")&&liquidSpecies(region.hostSnapshot().captureAuthoritativeState(),BuiltinChemicalDefinitions.HCO3_MINUS)!=savedBicarbonate)
                throw new AssertionError("Process restore changed bicarbonate inventory");
            region.ensureDeviceResident();region.setPaused(false);available(region);
            if(!motion.step(region,List.of()).accepted())throw new AssertionError("Restored process did not continue");
            System.out.println("process_complete scenario="+scenario+" checkpoint="+saved+" mass="+savedMass);
        }
    }
    private static void industrial(int steps) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-industrial-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        if(startup.creationLocked())throw new IllegalStateException(startup.blockingSummary());
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.SCREENING);
        var region=fixture.region;var screens=region.hostSnapshot().captureAuthoritativeState().fixedGeometry().screens();
        if(screens.isEmpty())throw new AssertionError("Screen blocks did not configure shared faces");
        ItemStack remainder=fixture.transferBox(new ItemStack(Items.WATER_BUCKET),fixture.portCell,false,50_000_000L);
        long accepted=1_000_000_000L-mmm.item.PartialWaterBucketItem.volume(remainder);
        var host=region.hostSnapshot();long liquid=host.totalMassUnits();
        long actual=MinecraftContainerTransferOperations.cellVolumeToNanos(host.unitScale(),host.totalVolumeExact());
        long expectedMass=Mass.fromKilograms(host.unitScale().cubicMeters(host.totalVolumeExact())*world.resolveMaterial("mmm:water").referenceDensity());
        if(accepted<=0 || accepted>50_000_000L || actual!=accepted || liquid!=expectedMass)
            throw new AssertionError("Box input settlement differs: requested=50000000 accepted="+accepted+" actual="+actual+" mass="+liquid+" expected="+expectedMass);
        var gas=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),fixture.portCell,true,10_000_000L);
        var gasContents=mmm.item.GasContainerItem.contents(gas);
        if(gasContents==null||gasContents.inventory().isEmpty())throw new AssertionError("Box output did not retain the gas mixture");
        fixture.transferBox(gas,fixture.portCell,false,gasContents.inventory().standardVolumeNanos());
        long initialMass=processMass(region.hostSnapshot().captureAuthoritativeState());
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            for(int step=0;step<Math.max(2,steps);step++){progress("industrial",step,Math.max(2,steps));if(!motion.step(region,List.of()).accepted())throw new AssertionError("Industrial process step rejected");}
            if(processMass(region.hostSnapshot().captureAuthoritativeState())!=initialMass)throw new AssertionError("Industrial process changed total mass");
            motion.settlePendingPhaseWork(region);
            long saved=operations.checkpoint(region);
            if(!region.releaseSolverAfterCheckpoint(saved))throw new AssertionError("Industrial checkpoint did not unload");
            var r=startup.registries();var repository=new RegionRepositoryAdapter(startup.worldRoot(),world.profile(),r.chemical(),r.granular(),r.thermal(),r.gas(),startup.config().maximumThermalSubstepsPerStep(),r.material());
            if(!repository.restoreIncrementally(region,0,Integer.MAX_VALUE).restored())throw new AssertionError("Industrial checkpoint did not restore");
            if(!region.hostSnapshot().captureAuthoritativeState().fixedGeometry().screens().equals(screens))throw new AssertionError("Screen faces changed after restore");
        }
    }
    private static long processMass(FluidDomainState state) {
        long total=0;
        for(var stock:state.liquid().physicalStocks())total=Math.addExact(total,stock.massUnits());
        for(var gas:state.spatialGas().cells())total=Math.addExact(total,gas.totalMassUnits());
        if(state.granular()!=null)total=Math.addExact(total,state.granular().totalMassUnits());
        if(state.thermalOwner()!=null)for(var group:state.thermalOwner().coherentGroups())total=Math.addExact(total,group.massUnits());
        return total;
    }
    private static void industrialValve(int steps) throws Exception {
        var open=valveRun(false,Math.max(400,steps));
        var closed=valveRun(true,Math.max(400,steps));
        if(!(open.flow()>0 && open.output()>0 && closed.flow()==0 && closed.output()==0))
            throw new AssertionError("Valve flow and output: open="+open+" closed="+closed);
    }
    private record ValveResult(double flow,long output) { }
    private static ValveResult valveRun(boolean closed,int steps) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-valve-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.TWO_TANKS);
        var region=fixture.region;int plane=11;var faceList=new ArrayList<Integer>();
        for(int y=0;y<4;y++)for(int z=0;z<4;z++)faceList.add(region.grid().faceAt(plane,y,z,0));
        int[] faces=faceList.stream().mapToInt(Integer::intValue).toArray();
        if(closed) {
            var state=mmm.block.MmmBlocks.VALVE.defaultBlockState().setValue(mmm.block.ValveBlock.FACING,Direction.EAST).setValue(mmm.block.ValveBlock.OPENING,0);
            var edits=new ArrayList<FluidDomainContracts.CollisionEdit>();
            for(int by=0;by<2;by++)for(int bz=0;bz<2;bz++)for(int x=0;x<2;x++)for(int y=0;y<2;y++)for(int z=0;z<2;z++)
                edits.add(new FluidDomainContracts.CollisionEdit(10+x,2*by+y,2*bz+z,
                        FluidRegionWorldInteraction.sampleCutCellMask(mmm.block.ValveBlock.shutter(state),x,y,z,2,1e-7)));
            var generation=region.solverGeneration();var candidate=region.beginHostEdit();candidate.applyCollisionEdits(edits);region.adoptCollisionCandidate(generation,candidate);
        }
        ItemStack batch=new ItemStack(Items.WATER_BUCKET);double flow=0;long output=0;
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            for(int step=0;step<steps;step++) {
                progress(closed?"valve_closed":"valve_open",step,steps);
                {
                    motion.settlePendingPhaseWork(region);
                    if(batch.is(Items.BUCKET))batch=new ItemStack(Items.WATER_BUCKET);
                    batch=fixture.transferBox(batch,fixture.portCell,false,IndustrialOperations.tickQuota(50_000_000L,step));available(region);
                }
                motion.settlePendingPhaseWork(region);
                int[] outlet={region.sizeX()-1,0,0};long quota=IndustrialOperations.tickQuota(50_000_000L,step);
                var block=region.origin().offset(outlet[0]/2,outlet[1]/2,outlet[2]/2);
                var contact=FluidWorldTargetResolver.transferCell(region,block,Direction.EAST,new mmm.api.transfer.TransferQuantity.Liquid(quota,OptionalDouble.empty()),true);
                if(contact!=null && MinecraftContainerTransferOperations.extractableNanos(region.hostSnapshot(),contact,quota,0,-1)>0) {
                    var product=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),outlet,true,quota);
                    long accepted=mmm.item.MaterialBucketItem.volume(product);if(accepted>0)output=Math.addExact(output,accepted);available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Valve process step rejected");
                flow+=Math.abs(region.observeLiquidFlow(faces));
            }
        }
        return new ValveResult(flow,output);
    }
    private static void industrialHeat(int steps) throws Exception {
        double metal=counterflowRun(steps,false),stone=counterflowRun(steps,true);
        if(!(metal>stone))throw new AssertionError("Metal wall did not transfer more heat than stone: "+metal+" / "+stone);
    }
    private static double counterflowRun(int steps,boolean stone) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-counterflow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,stone?IndustrialLayout.Scenario.COUNTERFLOW_STONE:IndustrialLayout.Scenario.COUNTERFLOW);
        var region=fixture.region;
        var layout=IndustrialLayout.at(new BlockPos(14,64,20),stone?IndustrialLayout.Scenario.COUNTERFLOW_STONE:IndustrialLayout.Scenario.COUNTERFLOW);
        int[] hotIn=fixture.portCell,coldIn={region.sizeX()-1,0,8};
        int[] hotOut={region.sizeX()-1,2,4},coldOut={0,2,8};
        ItemStack hot=saltedWater(world,350),cold=pureWater(world,290);
        var hotProducts=new ArrayList<ItemStack>();var coldProducts=new ArrayList<ItemStack>();
        var vented=new ArrayList<ItemStack>();
        double hotInputEnergy=0,coldInputEnergy=0;
        long initial=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(hot,world)+chemicalMass(cold,world),supplied=0;
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            for(int step=0;step<Math.max(600,steps);step++) {
                progress("counterflow",step,Math.max(600,steps));
                {
                    motion.settlePendingPhaseWork(region);
                    counterflowVent(fixture,region,layout,vented,step,hotProducts.isEmpty(),coldProducts.isEmpty());
                    {
                        if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(hot)){hot=saltedWater(world,350);supplied=Math.addExact(supplied,chemicalMass(hot,world));}
                        double before=liquidBatchEnergy(hot);
                        hot=fixture.transferBox(hot,hotIn,false,IndustrialOperations.tickQuota(1_000_000_000L,step));
                        hotInputEnergy+=before-liquidBatchEnergy(hot);
                    }
                    {
                        if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(cold)){cold=pureWater(world,290);supplied=Math.addExact(supplied,chemicalMass(cold,world));}
                        double before=liquidBatchEnergy(cold);
                        cold=fixture.transferBox(cold,coldIn,false,IndustrialOperations.tickQuota(1_000_000_000L,step));
                        coldInputEnergy+=before-liquidBatchEnergy(cold);
                    }
                    long quota=IndustrialOperations.tickQuota(1_000_000_000L,step);
                    var request=new mmm.api.transfer.TransferQuantity.Liquid(quota,OptionalDouble.empty());
                    var hotBlock=region.origin().offset(hotOut[0]/2,hotOut[1]/2,hotOut[2]/2);
                    var coldBlock=region.origin().offset(coldOut[0]/2,coldOut[1]/2,coldOut[2]/2);
                    var h=FluidWorldTargetResolver.transferCell(region,hotBlock,Direction.EAST,request,true);
                    var c=FluidWorldTargetResolver.transferCell(region,coldBlock,Direction.WEST,request,true);var snapshot=region.hostSnapshot();
                    if(h!=null && MinecraftContainerTransferOperations.extractableNanos(snapshot,h,quota,0,-1)>0) {
                        var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),hotOut,true,quota);
                        if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(batch))hotProducts.add(batch);
                    }
                    if(c!=null && MinecraftContainerTransferOperations.extractableNanos(snapshot,c,quota,0,-1)>0) {
                        var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),coldOut,true,quota);
                        if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(batch))coldProducts.add(batch);
                    }
                    available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Counterflow process step rejected");
            }
            motion.settlePendingPhaseWork(region);
            if(hotProducts.isEmpty()||coldProducts.isEmpty())throw new AssertionError("Counterflow streams did not reach their output ports");
            var hotProduct=hotProducts.getLast();var coldProduct=coldProducts.getLast();
            var a=mmm.item.ContainerItemStacks.chemicalContents(hotProduct,world.chemicalRegistry());
            var b=mmm.item.ContainerItemStacks.chemicalContents(coldProduct,world.chemicalRegistry());
            var hotOutput=counterflowProducts(hotProducts,world);
            var coldOutput=counterflowProducts(coldProducts,world);
            double hotTemperature=hotOutput.temperature();
            double coldTemperature=coldOutput.temperature();
            long hotSalt=chemicalAmount(a,BuiltinChemicalDefinitions.NACL)+chemicalAmount(a,BuiltinChemicalDefinitions.NA_PLUS);
            long coldSalt=chemicalAmount(b,BuiltinChemicalDefinitions.NACL)+chemicalAmount(b,BuiltinChemicalDefinitions.NA_PLUS);
            System.out.println("counterflow_batches stone="+stone+" hot_input_u="+hotInputEnergy+" cold_input_u="+coldInputEnergy
                    +" hot="+hotOutput+" cold="+coldOutput);
            if(!(hotTemperature<350 && (stone || coldTemperature>290) && hotSalt>0 && coldSalt==0))
                throw new AssertionError("Counterflow output: hot="+hotTemperature+" cold="+coldTemperature+" salt="+hotSalt+"/"+coldSalt);
            long productMass=0;for(var batch:hotProducts)productMass=Math.addExact(productMass,chemicalMass(batch,world));for(var batch:coldProducts)productMass=Math.addExact(productMass,chemicalMass(batch,world));
            long gasMass=0;double gasEnergy=0;var gasComponents=new TreeMap<String,Long>();
            for(var batch:vented){var contents=mmm.item.GasContainerItem.contents(batch);gasMass=Math.addExact(gasMass,contents.inventory().totalMassUnits());gasEnergy+=contents.internalEnergy();for(var component:contents.inventory().components())gasComponents.merge(component.componentId(),component.standardVolumeNanos(),Math::addExact);}
            System.out.println("counterflow_vent mass="+gasMass+" energy="+gasEnergy+" components="+gasComponents);
            long after=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(hot,world)+chemicalMass(cold,world)+productMass+gasMass;
            if(after!=Math.addExact(initial,supplied))throw new AssertionError("Counterflow changed combined mass");
            double beforeStop=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream().mapToDouble(value->value.body().internalEnergyJ()).sum();
            for(int step=0;step<600;step++) {
                progress("counterflow_after_stop",step,600);
                motion.settlePendingPhaseWork(region);
                long quota=IndustrialOperations.tickQuota(1_000_000_000L,step);
                var request=new mmm.api.transfer.TransferQuantity.Liquid(quota,OptionalDouble.empty());
                if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(cold)){cold=pureWater(world,290);supplied=Math.addExact(supplied,chemicalMass(cold,world));}
                var coldInput=region.origin().offset(coldIn[0]/2,coldIn[1]/2,coldIn[2]/2);
                if(FluidWorldTargetResolver.transferCell(region,coldInput,Direction.EAST,request,false)!=null)
                    cold=fixture.transferBox(cold,coldIn,false,quota);
                for(var port:List.of(hotIn,hotOut,coldOut)) {
                    var position=region.origin().offset(port[0]/2,port[1]/2,port[2]/2);
                    var contact=FluidWorldTargetResolver.transferCell(region,position,port==hotOut?Direction.EAST:Direction.WEST,request,true);
                    if(contact!=null && MinecraftContainerTransferOperations.extractableNanos(region.hostSnapshot(),contact,quota,0,-1)>0)
                        productMass=Math.addExact(productMass,chemicalMass(fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),port,true,quota),world));
                }
                available(region);
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Counterflow after hot feed stopped rejected");
            }
            motion.settlePendingPhaseWork(region);
            double afterStop=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream().mapToDouble(value->value.body().internalEnergyJ()).sum();
            System.out.println("counterflow_after_stop stone="+stone+" wall_energy="+beforeStop+" / "+afterStop);
            if(!(afterStop<beforeStop))throw new AssertionError("Finite wall did not release stored heat after hot feed stopped");
            gasMass=0;for(var batch:vented)gasMass=Math.addExact(gasMass,gasMass(batch));
            long stopped=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(hot,world)+chemicalMass(cold,world)+productMass+gasMass;
            if(stopped!=Math.addExact(initial,supplied))throw new AssertionError("Counterflow after hot feed stopped changed combined mass");
            return coldTemperature;
        }
    }
    private static void counterflowVent(GpuControllerFixture fixture,FluidRegion region,IndustrialLayout layout,List<ItemStack> products,int tick,boolean hotOpen,boolean coldOpen) throws Exception {
        for(var port:layout.ports())if(port.phase()==mmm.block.FluidPortBlockEntity.Phase.GAS) {
            if(port.position().getZ()==layout.minimum().getZ()+3?!hotOpen:!coldOpen)continue;
            var interior=port.position().relative(port.outward().getOpposite());
            int[] cell={(interior.getX()-region.origin().getX())*2,(interior.getY()-region.origin().getY())*2+1,(interior.getZ()-region.origin().getZ())*2};
            var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),cell,port.outward(),true,IndustrialOperations.tickQuota(port.rate(),tick));
            if(mmm.item.GasContainerItem.contents(batch)!=null)products.add(batch);
        }
    }
    private static double liquidBatchEnergy(ItemStack batch) {
        var thermal=mmm.item.ContainerItemStacks.thermalContents(batch);return thermal==null?0:thermal.internalEnergy();
    }
    private record CounterflowProducts(double mass,double temperature,double energy) {}
    private static CounterflowProducts counterflowProducts(List<ItemStack> products,CommittedWorldRegistries world) {
        double weighted=0,mass=0,energy=0;
        for(var batch:products) {
            var contents=mmm.item.ContainerItemStacks.chemicalContents(batch,world.chemicalRegistry());
            var thermal=mmm.item.ContainerItemStacks.thermalContents(batch);double kilograms=Mass.kilograms(contents.totalMassUnits());
            weighted+=kilograms*LiquidContainerThermodynamics.temperatureK(world.thermalRegistry(),thermal,contents);mass+=kilograms;energy+=thermal.internalEnergy();
        }
        return new CounterflowProducts(mass,weighted/mass,energy);
    }
    private static long chemicalAmount(ChemicalContents contents,String species) {
        return contents.groups().stream().flatMap(group->group.species().stream()).filter(entry->entry.speciesId().equals(species)).mapToLong(ChemicalContents.SpeciesAmount::amountQuanta).sum();
    }
    private static ItemStack saltedWater(CommittedWorldRegistries world,double temperature) {
        var chemistry=world.chemicalRegistry();var amounts=Map.of(BuiltinChemicalDefinitions.H2O,waterQuanta(world),BuiltinChemicalDefinitions.NACL,ChemicalLimits.QUANTA_PER_MOL);
        var composition=ChemicalMixtureRecord.fromStableAmounts(chemistry,amounts,0);
        long mass=Math.addExact(Mass.fromKilograms(world.resolveMaterial("mmm:water").referenceDensity()),chemistry.speciesMassUnits(chemistry.speciesHandle(BuiltinChemicalDefinitions.NACL),ChemicalLimits.QUANTA_PER_MOL));
        var ledger=new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water",1.0,mass,mass-composition.speciesMassUnits(chemistry),amounts)));
        var result=mmm.item.ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger,chemistry,1_000_000_000L));
        world.bindActualContainerProfile(result);world.attachLiquidThermalContents(result,temperature);return result;
    }
    private static ItemStack pureWater(CommittedWorldRegistries world,double temperature) {
        var chemistry=world.chemicalRegistry();var amounts=Map.of(BuiltinChemicalDefinitions.H2O,waterQuanta(world));
        var composition=ChemicalMixtureRecord.fromStableAmounts(chemistry,amounts,0);long mass=Mass.fromKilograms(world.resolveMaterial("mmm:water").referenceDensity());
        var ledger=new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water",1.0,mass,mass-composition.speciesMassUnits(chemistry),amounts)));
        var result=mmm.item.ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger,chemistry,1_000_000_000L));
        world.bindActualContainerProfile(result);world.attachLiquidThermalContents(result,temperature);return result;
    }
    private static ItemStack coolableSaltWater(CommittedWorldRegistries world,double feedTemperature,double coldTemperature,long volumeNanos) {
        var solubility=world.granularRegistry().reactions().stream().filter(reaction->reaction.solidMaterialId().equals(BuiltinGranularDefinitions.PRECIPITATE))
                .map(SolidLiquidReaction::solubility).filter(Objects::nonNull).findFirst().orElseThrow();
        double feedLimit=solubility.ratio()+solubility.slopePerK()*(feedTemperature-solubility.referenceTemperatureK());
        double coldLimit=solubility.ratio()+solubility.slopePerK()*(coldTemperature-solubility.referenceTemperatureK());
        if(!(feedLimit>coldLimit))throw new IllegalStateException("Salt solubility does not increase between the cold wall and feed temperatures");
        double volume=volumeNanos/(double)ContainerFluidLedger.NANOS_PER_BLOCK;
        long water=Math.round(waterQuanta(world)*volume),salt=Math.round(water*(feedLimit-(feedLimit-coldLimit)*0.02));
        var amounts=Map.of(BuiltinChemicalDefinitions.H2O,water,BuiltinChemicalDefinitions.NACL,salt);
        var registry=world.chemicalRegistry();var mixture=ChemicalMixtureRecord.fromStableAmounts(registry,amounts,0);
        long waterMass=Mass.fromKilograms(world.resolveMaterial("mmm:water").referenceDensity()*volume);
        long mass=Math.addExact(waterMass,registry.speciesMassUnits(registry.speciesHandle(BuiltinChemicalDefinitions.NACL),salt));
        var ledger=new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water",1.0,mass,mass-mixture.speciesMassUnits(registry),amounts)));
        var result=mmm.item.ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger,registry,volumeNanos));
        world.bindActualContainerProfile(result);world.attachLiquidThermalContents(result,feedTemperature);return result;
    }
    private static long waterQuanta(CommittedWorldRegistries world) {
        var registry=world.chemicalRegistry();var phase=registry.phase(registry.phaseHandle(BuiltinChemicalDefinitions.AQUEOUS));
        return Math.round(phase.defaultConcentrationMolPerCubicMeter().get(BuiltinChemicalDefinitions.H2O).toDouble()*ChemicalLimits.QUANTA_PER_MOL);
    }
    private static void industrialAdsorption(int steps) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-adsorption-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.ABSORPTION);
        var region=fixture.region;
        int[] treatedIn=fixture.portCell,controlIn={0,0,8},treatedOut={region.sizeX()-1,0,0},controlOut={region.sizeX()-1,0,8};
        ItemStack treated=gasFeed(world),control=gasFeed(world);
        ItemStack treatedProduct=null,controlProduct=null;
        var products=new ArrayList<ItemStack>();
        double peakSeparation=0;
        long initial=surfaceProcessMass(region,world)+gasMass(treated)+gasMass(control),supplied=0;
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            int duration=Math.max(1000,steps);
            for(int step=0;step<duration;step++) {
                progress("adsorption",step,duration);
                {
                    motion.settlePendingPhaseWork(region);
                    if(mmm.item.GasContainerItem.contents(treated)==null){treated=gasFeed(world);supplied=Math.addExact(supplied,gasMass(treated));}
                    if(mmm.item.GasContainerItem.contents(control)==null){control=gasFeed(world);supplied=Math.addExact(supplied,gasMass(control));}
                    long quota=IndustrialOperations.tickQuota(1_000_000_000L,step);
                    treated=fixture.transferBox(treated,treatedIn,false,quota);
                    control=fixture.transferBox(control,controlIn,false,quota);
                    {
                        var gasRequest=new mmm.api.transfer.TransferQuantity.Gas(quota,0);
                        var treatedBlock=region.origin().offset(treatedOut[0]/2,treatedOut[1]/2,treatedOut[2]/2);
                        var controlBlock=region.origin().offset(controlOut[0]/2,controlOut[1]/2,controlOut[2]/2);
                        ItemStack a=null,b=null;
                        if(FluidWorldTargetResolver.transferCell(region,treatedBlock,Direction.EAST,gasRequest,true)!=null) {
                            var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),treatedOut,true,quota);
                            if(mmm.item.GasContainerItem.contents(batch)!=null){a=batch;products.add(batch);}
                        }
                        if(FluidWorldTargetResolver.transferCell(region,controlBlock,Direction.EAST,gasRequest,true)!=null) {
                            var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),controlOut,true,quota);
                            if(mmm.item.GasContainerItem.contents(batch)!=null){b=batch;products.add(batch);}
                        }
                        if(a!=null && b!=null) {
                            treatedProduct=a;controlProduct=b;
                            peakSeparation=Math.max(peakSeparation,gasFraction(controlProduct,BuiltinGasDefinitions.CO2)-gasFraction(treatedProduct,BuiltinGasDefinitions.CO2));
                        }
                    }
                    if(step%100==0) {
                        System.out.println("adsorption_state step="+step+" peak_separation="+peakSeparation+" adsorbed="
                                +region.finiteThermalDevices(world.thermalRegistryFingerprint()).adsorbedSpeciesQuanta().getOrDefault(BuiltinChemicalDefinitions.CO2,0L));
                    }
                    available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Adsorption process step rejected");
            }
            motion.settlePendingPhaseWork(region);
            long adsorbed=region.finiteThermalDevices(world.thermalRegistryFingerprint()).adsorbedSpeciesQuanta().getOrDefault(BuiltinChemicalDefinitions.CO2,0L);
            if(treatedProduct==null)throw new AssertionError("Gas feed did not reach the outlet");
            double treatedFraction=gasFraction(treatedProduct,BuiltinGasDefinitions.CO2),controlFraction=gasFraction(controlProduct,BuiltinGasDefinitions.CO2);
            var deviceInventory=region.finiteThermalDevices(world.thermalRegistryFingerprint());double capacity=0;
            for(var device:deviceInventory.devices())for(var surface:device.surfaces())capacity+=Mass.kilograms(surface.loadingMassUnits())*world.thermalRegistry().surfaceMaterial(surface.materialId()).siteCapacityMolesPerKg()*ChemicalLimits.QUANTA_PER_MOL;
            if(adsorbed<=0 || adsorbed>capacity || !(peakSeparation>0))throw new AssertionError("Adsorption did not retain a finite CO2 inventory: adsorbed="+adsorbed+" capacity="+capacity+" peak="+peakSeparation);
            if(!(controlFraction-treatedFraction<peakSeparation))throw new AssertionError("Adsorbent did not approach breakthrough: peak="+peakSeparation+" final="+(controlFraction-treatedFraction));
            long productMass=0;for(var batch:products)productMass=Math.addExact(productMass,gasMass(batch));
            long after=surfaceProcessMass(region,world)+gasMass(treated)+gasMass(control)+productMass;
            if(after!=Math.addExact(initial,supplied))throw new AssertionError("Gas and adsorbed mass changed: "+(initial+supplied)+" / "+after);
            var recovered=new ArrayList<ItemStack>();
            for(int step=0;step<600;step++) {
                progress("regeneration",step,600);
                motion.settlePendingPhaseWork(region);
                long quota=IndustrialOperations.tickQuota(1_000_000_000L,step);
                var outlet=region.origin().offset(treatedOut[0]/2,treatedOut[1]/2,treatedOut[2]/2);
                if(FluidWorldTargetResolver.transferCell(region,outlet,Direction.EAST,new mmm.api.transfer.TransferQuantity.Gas(quota,0),true)!=null) {
                    recovered.add(fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),treatedOut,true,quota));available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Adsorbent regeneration step rejected");
            }
            motion.settlePendingPhaseWork(region);
            long remaining=region.finiteThermalDevices(world.thermalRegistryFingerprint()).adsorbedSpeciesQuanta().getOrDefault(BuiltinChemicalDefinitions.CO2,0L);
            if(remaining>=adsorbed)throw new AssertionError("Lower gas pressure did not release adsorbed CO2: "+adsorbed+" / "+remaining);
            long recoveredMass=0;for(var batch:recovered)recoveredMass=Math.addExact(recoveredMass,gasMass(batch));
            long regenerated=surfaceProcessMass(region,world)+gasMass(treated)+gasMass(control)+productMass+recoveredMass;
            if(regenerated!=Math.addExact(initial,supplied))throw new AssertionError("Regeneration changed gas and adsorbed mass");
            for(int step=0;step<600;step++) {
                progress("readsorption",step,600);
                motion.settlePendingPhaseWork(region);
                if(mmm.item.GasContainerItem.contents(treated)==null){treated=gasFeed(world);supplied=Math.addExact(supplied,gasMass(treated));}
                treated=fixture.transferBox(treated,treatedIn,false,IndustrialOperations.tickQuota(1_000_000_000L,step));
                available(region);
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Adsorbent reuse step rejected");
            }
            motion.settlePendingPhaseWork(region);
            long reused=region.finiteThermalDevices(world.thermalRegistryFingerprint()).adsorbedSpeciesQuanta().getOrDefault(BuiltinChemicalDefinitions.CO2,0L);
            if(reused<=remaining)throw new AssertionError("Regenerated adsorbent did not take up CO2 again: "+remaining+" / "+reused);
            long reusedMass=surfaceProcessMass(region,world)+gasMass(treated)+gasMass(control)+productMass+recoveredMass;
            if(reusedMass!=Math.addExact(initial,supplied))throw new AssertionError("Adsorbent reuse changed gas and adsorbed mass");
        }
    }
    private static ItemStack gasFeed(CommittedWorldRegistries world) {
        var half=new ExactRational(java.math.BigInteger.ONE,java.math.BigInteger.TWO);
        var registry=world.gasRegistry();var gas=registry.createMixture(1_000_000_000L,List.of(
                new mmm.fluid.air.GasRegistry.AmbientFraction(BuiltinGasDefinitions.CO2,half),
                new mmm.fluid.air.GasRegistry.AmbientFraction(BuiltinGasDefinitions.NITROGEN,half)));
        double energy=new GasThermalModel(world.thermalRegistry(),registry).internalEnergyAtTemperature(gas,293.15);
        return world.bindActualContainerProfile(mmm.item.ContainerItemStacks.toStack(new GasContents(registry.fingerprint(),world.chemicalRegistry().fingerprint(),gas,energy)));
    }
    private static ItemStack catalysisFeed(CommittedWorldRegistries world) {
        var chemistry=world.chemicalRegistry();
        var transfer=chemistry.gasLiquidMassTransfers().stream()
                .filter(value->value.speciesId().equals(BuiltinChemicalDefinitions.CO2)
                        && value.gasComponentId().equals(BuiltinGasDefinitions.CO2)
                        && value.liquidPhaseId().equals(BuiltinChemicalDefinitions.AQUEOUS))
                .findFirst().orElseThrow();
        double ambientFraction=world.gasRegistry().ambient().stream()
                .filter(value->value.componentId().equals(BuiltinGasDefinitions.CO2))
                .findFirst().orElseThrow().fraction().toDouble();
        double temperature=mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K;
        double concentration=transfer.henrySolubilityAt(temperature)
                *mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA*ambientFraction;
        var amounts=Map.of(BuiltinChemicalDefinitions.H2O,waterQuanta(world),BuiltinChemicalDefinitions.CO2,
                Math.round(concentration*ChemicalLimits.QUANTA_PER_MOL));
        var mixture=ChemicalMixtureRecord.fromStableAmounts(chemistry,amounts,0);
        long mass=mixture.speciesMassUnits(chemistry);
        var ledger=new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water",1.0,mass,0,amounts)));
        var item=mmm.item.ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger,chemistry,ContainerFluidLedger.NANOS_PER_BLOCK));
        world.bindActualContainerProfile(item);world.attachLiquidThermalContents(item,temperature);return item;
    }
    private static long gasMass(ItemStack stack) { var gas=mmm.item.GasContainerItem.contents(stack);return gas==null?0:gas.inventory().totalMassUnits(); }
    private static double gasFraction(ItemStack stack,String component) {
        var gas=mmm.item.GasContainerItem.contents(stack).inventory();var value=gas.component(component);
        return value==null?0:value.standardVolumeNanos()/(double)gas.standardVolumeNanos();
    }
    private static long surfaceProcessMass(FluidRegion region,CommittedWorldRegistries world) {
        return Math.addExact(processMass(region.hostSnapshot().captureAuthoritativeState()),region.finiteThermalDevices(world.thermalRegistryFingerprint()).adsorbedMassUnits(world.chemicalRegistry()));
    }
    private static void industrialCatalysis(int steps) throws Exception {
        double full=catalysisRun(steps,IndustrialLayout.Scenario.CATALYSIS);
        double partial=catalysisRun(steps,IndustrialLayout.Scenario.CATALYSIS_PARTIAL);
        if(!(full>partial && partial>0))throw new AssertionError("Catalytic contact area did not change the early gain: full="+full+" partial="+partial);
        catalysisClosed();
    }
    private static double catalysisRun(int steps,IndustrialLayout.Scenario scenario) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-catalysis-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,scenario);
        var region=fixture.region;
        int[] treatedIn=fixture.portCell,controlIn={0,0,8},treatedOut={region.sizeX()-1,0,0},controlOut={region.sizeX()-1,0,8};
        ItemStack treated=catalysisFeed(world),control=catalysisFeed(world);
        int earlyStep=-1;var products=new ArrayList<ItemStack>();
        long sampleVolume=ContainerFluidLedger.NANOS_PER_BLOCK;
        var treatedSamples=new ArrayList<ChemicalContents>();var controlSamples=new ArrayList<ChemicalContents>();long treatedVolume=0,controlVolume=0;
        long initial=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(treated,world)+chemicalMass(control,world),supplied=0;
        long catalyst=region.finiteThermalDevices(world.thermalRegistryFingerprint()).surfaceLoadingMassUnits();
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            for(int step=0;step<Math.max(1000,steps);step++) {
                progress("catalysis",step,Math.max(1000,steps));
                {
                    motion.settlePendingPhaseWork(region);
                    long quota=IndustrialOperations.tickQuota(1_000_000_000L,step);
                    var request=new mmm.api.transfer.TransferQuantity.Liquid(quota,OptionalDouble.empty());
                    var treatedBlock=region.origin().offset(treatedOut[0]/2,treatedOut[1]/2,treatedOut[2]/2);
                    var controlBlock=region.origin().offset(controlOut[0]/2,controlOut[1]/2,controlOut[2]/2);
                    var t=FluidWorldTargetResolver.transferCell(region,treatedBlock,Direction.EAST,request,true);
                    var c=FluidWorldTargetResolver.transferCell(region,controlBlock,Direction.EAST,request,true);
                    var host=region.hostSnapshot();
                    if(t!=null && MinecraftContainerTransferOperations.extractableNanos(host,t,quota,0,-1)>0) {
                        var a=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),treatedOut,true,quota);
                        if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(a)) {
                            products.add(a);var contents=mmm.item.ContainerItemStacks.chemicalContents(a,world.chemicalRegistry());
                            treatedSamples.add(contents);treatedVolume=Math.addExact(treatedVolume,contents.totalVolumeNanos());
                        }
                    }
                    if(c!=null && MinecraftContainerTransferOperations.extractableNanos(host,c,quota,0,-1)>0) {
                        var b=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),controlOut,true,quota);
                        if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(b)) {
                            products.add(b);var contents=mmm.item.ContainerItemStacks.chemicalContents(b,world.chemicalRegistry());
                            controlSamples.add(contents);controlVolume=Math.addExact(controlVolume,contents.totalVolumeNanos());
                        }
                    }
                    if(earlyStep<0 && treatedVolume>=sampleVolume && controlVolume>=sampleVolume)earlyStep=step;
                    if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(treated)){treated=catalysisFeed(world);supplied=Math.addExact(supplied,chemicalMass(treated,world));}
                    if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(control)){control=catalysisFeed(world);supplied=Math.addExact(supplied,chemicalMass(control,world));}
                    var inA=region.origin().offset(treatedIn[0]/2,treatedIn[1]/2,treatedIn[2]/2);
                    var inB=region.origin().offset(controlIn[0]/2,controlIn[1]/2,controlIn[2]/2);
                    if(FluidWorldTargetResolver.transferCell(region,inA,Direction.WEST,request,false)!=null)
                        treated=fixture.transferBox(treated,treatedIn,false,quota);
                    if(FluidWorldTargetResolver.transferCell(region,inB,Direction.WEST,request,false)!=null)
                        control=fixture.transferBox(control,controlIn,false,quota);
                    available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Catalysis process step rejected");
                if(earlyStep>=0)break;
            }
            motion.settlePendingPhaseWork(region);
            if(earlyStep<0)throw new AssertionError("Catalysis streams did not produce a common sample volume");
            double converted=speciesAtVolume(treatedSamples,sampleVolume,BuiltinChemicalDefinitions.HCO3_MINUS,world.chemicalRegistry())/(double)sampleVolume;
            double baseline=speciesAtVolume(controlSamples,sampleVolume,BuiltinChemicalDefinitions.HCO3_MINUS,world.chemicalRegistry())/(double)sampleVolume;
            System.out.println("catalysis_product scenario="+scenario+" step="+earlyStep+" treated="+converted+" control="+baseline);
            if(!(converted>baseline))throw new AssertionError("Catalytic contact did not accelerate hydration at step "+earlyStep+": "+converted+" / "+baseline);
            if(region.finiteThermalDevices(world.thermalRegistryFingerprint()).surfaceLoadingMassUnits()!=catalyst)throw new AssertionError("Catalyst inventory changed");
            long productMass=0;for(var batch:products)productMass=Math.addExact(productMass,chemicalMass(batch,world));
            long after=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(treated,world)+chemicalMass(control,world)+productMass;
            if(after!=Math.addExact(initial,supplied))throw new AssertionError("Catalysis changed combined mass");
            return converted-baseline;
        }
    }
    private static void catalysisClosed() throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-catalysis-closed-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.CATALYSIS);
        var region=fixture.region;int[] treatedIn=fixture.portCell,controlIn={0,0,8};
        ItemStack treated=catalysisFeed(world),control=catalysisFeed(world);
        long initial=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(treated,world)+chemicalMass(control,world);
        long catalyst=region.finiteThermalDevices(world.thermalRegistryFingerprint()).surfaceLoadingMassUnits();
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            for(int step=0;step<600 && (!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(treated)||!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(control));step++) {
                progress("catalysis_closed_fill",step,600);motion.settlePendingPhaseWork(region);
                long quota=IndustrialOperations.tickQuota(1_000_000_000L,step);
                if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(treated))treated=fixture.transferBox(treated,treatedIn,false,quota);
                if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(control))control=fixture.transferBox(control,controlIn,false,quota);
                available(region);if(!motion.step(region,List.of()).accepted())throw new AssertionError("Closed catalysis filling rejected");
            }
            if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(treated)||!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(control))
                throw new AssertionError("Closed catalysis did not receive equal finite batches");
            for(int step=0;step<600;step++) {
                progress("catalysis_equilibrium",step,600);
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Catalysis equilibrium step rejected");
            }
            motion.settlePendingPhaseWork(region);var late=region.hostSnapshot().captureAuthoritativeState();
            var treatedState=hydrationInventory(late,region,true);var controlState=hydrationInventory(late,region,false);
            double difference=Math.abs(treatedState.fraction()-controlState.fraction());
            System.out.println("catalysis_equilibrium treated="+treatedState+" control="+controlState+" fraction_difference="+difference);
            if(difference>0.01)throw new AssertionError("Closed catalytic batches differ by more than one percentage point: "+difference);
            if(processMass(late)!=initial || region.finiteThermalDevices(world.thermalRegistryFingerprint()).surfaceLoadingMassUnits()!=catalyst)
                throw new AssertionError("Closed catalysis changed total mass or catalyst inventory");
        }
    }
    private record Hydration(long carbon,long bicarbonate) { double fraction(){return bicarbonate/(double)carbon;} }
    private static Hydration hydrationInventory(FluidDomainState state,FluidRegion region,boolean treated) {
        long co2=0,bicarbonate=0;
        for(var stock:state.liquid().physicalStocks()) {
            int z=region.grid().z(stock.key().cell());if((z<6)!=treated || stock.mixture()==null)continue;
            var species=stock.mixture().stableAmounts(state.chemicalRegistry());
            co2=Math.addExact(co2,species.getOrDefault(BuiltinChemicalDefinitions.CO2,0L));
            bicarbonate=Math.addExact(bicarbonate,species.getOrDefault(BuiltinChemicalDefinitions.HCO3_MINUS,0L));
        }
        if(co2+bicarbonate==0)throw new AssertionError("Catalysis comparison has no liquid carbon");
        return new Hydration(co2+bicarbonate,bicarbonate);
    }
    private static long chemicalMass(ItemStack stack,CommittedWorldRegistries world) {
        if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(stack))return 0;
        return mmm.item.ContainerItemStacks.chemicalContents(stack,world.chemicalRegistry()).totalMassUnits();
    }
    private static void industrialScreen(int steps) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-screen-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.SCREENING);
        var region=fixture.region;int[] solidInput={0,6,0},solidOutput={0,4,0};
        var source=screenFeed(world);long initial=mmm.item.GranularContainerItem.unvalidatedContents(source).totalMassUnits();
        ItemStack remaining=source;boolean seeded=false;
        ItemStack water=saltedWater(world,293.15);
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            double beforeFlow=0,beforePressure=0;int[] screenFaces=region.fixedGeometry().screens().stream().mapToInt(ScreenFace::face).toArray();
            int duration=Math.max(800,steps);
            for(int step=0;step<duration;step++) {
                progress("screening",step,duration);
                {
                    motion.settlePendingPhaseWork(region);
                    if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(water))water=saltedWater(world,293.15);
                    water=fixture.transferBox(water,fixture.portCell,false,IndustrialOperations.tickQuota(50_000_000L,step));available(region);
                }
                if(!seeded) {
                    var solidBlock=region.origin().offset(solidInput[0]/2,solidInput[1]/2,solidInput[2]/2);
                    var solidContact=FluidWorldTargetResolver.transferCell(region,solidBlock,Direction.NORTH,new mmm.api.transfer.TransferQuantity.Granular(262_144L),false);
                    if(solidContact!=null && region.hostSnapshot().volume(solidContact[0],solidContact[1],solidContact[2])>0) {
                        motion.settlePendingPhaseWork(region);
                        remaining=fixture.transferBox(source,solidInput,false,262_144L);seeded=true;available(region);
                    }
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Screen process step rejected");
                if(step>=duration-40){beforeFlow+=Math.abs(region.observeLiquidFlow(screenFaces));beforePressure+=screenPressureDifference(region,screenFaces);}
            }
            motion.settlePendingPhaseWork(region);
            if(!seeded)throw new AssertionError("Liquid did not reach the screening feed port");
            var state=region.hostSnapshot().exportGranularState();
            for(var deposit:state.depositGroups())System.out.println("screen_deposit material="+state.materialPalette().get(deposit.materialPaletteIndex())
                    +" cell="+region.grid().x(deposit.cell())+","+region.grid().y(deposit.cell())+","+region.grid().z(deposit.cell())+" svq="+deposit.solidVolumeSvq());
            long fine=downstreamSolid(state,region,BuiltinGranularDefinitions.FINE_SILT,5);
            long coarse=downstreamSolid(state,region,BuiltinGranularDefinitions.FINE_SAND,5);
            if(!(fine>coarse))throw new AssertionError("Screen did not separate particle sizes: fine="+fine+" coarse="+coarse);
            var output=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GRANULAR_CONTAINER),solidOutput,Direction.NORTH,true,262_144L);
            var collected=mmm.item.GranularContainerItem.unvalidatedContents(output);
            if(collected==null || mmm.item.ContainerItemStacks.thermalContents(output)==null)throw new AssertionError("Screen cake extraction lost its inventory or energy");
            if(collected.groups().stream().noneMatch(group->group.materialId().equals(BuiltinGranularDefinitions.FINE_SAND)))
                throw new AssertionError("Screen cake output did not collect the retained coarse material");
            long retained=mmm.item.GranularContainerItem.isEmpty(remaining)?0:mmm.item.GranularContainerItem.unvalidatedContents(remaining).totalMassUnits();
            long after=region.hostSnapshot().exportGranularState().totalMassUnits()+retained+collected.totalMassUnits();
            if(after!=initial)throw new AssertionError("Screen cake transfer changed solid mass");
            double afterFlow=0,afterPressure=0;
            for(int step=0;step<40;step++) {
                {
                    motion.settlePendingPhaseWork(region);if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(water))water=saltedWater(world,293.15);
                    water=fixture.transferBox(water,fixture.portCell,false,IndustrialOperations.tickQuota(50_000_000L,step));available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Cleaned screen process step rejected");
                afterFlow+=Math.abs(region.observeLiquidFlow(screenFaces));afterPressure+=screenPressureDifference(region,screenFaces);
            }
            if(!(beforePressure>0 && afterPressure>0 && afterFlow/afterPressure>beforeFlow/beforePressure))
                throw new AssertionError("Screen cleaning did not restore conductance: before="+beforeFlow+"/"+beforePressure+" after="+afterFlow+"/"+afterPressure);
            motion.settlePendingPhaseWork(region);
            var solution=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),new int[]{region.sizeX()-1,0,0},true,50_000_000L);
            var dissolved=mmm.item.ContainerItemStacks.chemicalContents(solution,world.chemicalRegistry());
            if(chemicalAmount(dissolved,BuiltinChemicalDefinitions.NACL)+chemicalAmount(dissolved,BuiltinChemicalDefinitions.NA_PLUS)<=0)
                throw new AssertionError("Dissolved salt did not pass through the screen");
        }
    }
    private static double screenPressureDifference(FluidRegion region,int[] faces) {
        var host=region.hostSnapshot();double sum=0;
        for(int face:faces) {
            int a=region.grid().faceLeft(face),b=region.grid().faceRight(face);
            sum+=Math.abs(host.pressure(region.grid().x(a),region.grid().y(a),region.grid().z(a))
                    -host.pressure(region.grid().x(b),region.grid().y(b),region.grid().z(b)));
        }
        return sum;
    }
    private static long downstreamSolid(GranularStateSnapshot state,FluidRegion region,String material,int plane) {
        int id=state.materialPalette().indexOf(material);long total=0;
        for(var parcel:state.activeParcels())if(parcel.materialPaletteIndex()==id && parcel.y()<plane*region.solverConfig().cellSize())total+=parcel.solidVolumeSvq();
        for(var deposit:state.depositGroups())if(deposit.materialPaletteIndex()==id && region.grid().y(deposit.cell())<plane)total+=deposit.solidVolumeSvq();
        return total;
    }
    private static ItemStack screenFeed(CommittedWorldRegistries world) {
        var registry=world.granularRegistry();var groups=new ArrayList<GranularTransferLedger.Group>();double energy=0;
        var function=new ThermalStateFunction(world.thermalRegistry());
        for(String id:List.of(BuiltinGranularDefinitions.FINE_SAND,BuiltinGranularDefinitions.FINE_SILT)) {
            var material=registry.material(registry.materialHandle(id));long volume=131_072L;
            long mass=Mass.fromKilograms(material.referenceDensity()*volume/(double)SolidVolume.SVQ_PER_CUBIC_METER);
            groups.add(new GranularTransferLedger.Group(id,volume,mass,null));
            int handle=world.thermalRegistry().materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR,id);
            energy+=function.internalEnergyAtTemperature(handle,Mass.kilograms(mass),Map.of(),293.15);
        }
        var contents=GranularContents.fromLedger(new GranularTransferLedger(groups),registry,world.chemicalRegistry());
        var item=world.bindActualContainerProfile(mmm.item.ContainerItemStacks.toStack(contents));
        mmm.item.ContainerItemStacks.attachThermal(item,world.thermalRegistryFingerprint(),energy);return item;
    }
    private static void industrialCrystallization(int steps) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-crystal-screen-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.SCREENING_CRYSTALLIZATION);
        var region=fixture.region;var layout=IndustrialLayout.at(new BlockPos(14,64,20),IndustrialLayout.Scenario.SCREENING_CRYSTALLIZATION);
        var wall=layout.walls().getFirst();
        int[] solidInput={0,2,0},solidOutput={0,2,0};ItemStack feed=coolableSaltWater(world,310,wall.temperatureK(),2_000_000L),seed=saltSeed(world,2);
        var reaction=world.granularRegistry().reactions().stream().filter(r->r.solidMaterialId().equals(BuiltinGranularDefinitions.PRECIPITATE)).findFirst().orElseThrow();
        long seedMass=mmm.item.GranularContainerItem.unvalidatedContents(seed).totalMassUnits();
        long initial=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(feed,world)+seedMass,supplied=0,seedVolume=0;
        boolean seeded=false,grown=false;double bulkTemperature=310;
        String wallPrefix=wall.position().asLong()+"@";
        var coldWall=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream().filter(device->device.objectId().startsWith(wallPrefix)).findFirst().orElseThrow();
        double wallEnergy=coldWall.body().internalEnergyJ();
        long coolingBudget=Math.max(4000,steps);
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            long coolingSteps=0;int growthSteps=0;
            while(!grown) {
                if(!seeded && coolingSteps++>=coolingBudget)throw new AssertionError("Finite batch did not cool within its heat-transfer budget: bulk="+bulkTemperature);
                if(seeded && growthSteps++>=Math.max(1000,steps))throw new AssertionError("Cooled seeded batch did not grow crystals");
                long step=seeded?growthSteps-1:coolingSteps-1;
                if(step%100==0)System.out.println("process_progress scenario="+(seeded?"crystallization_growth":"crystallization_cooling")+" step="+step);
                {
                    motion.settlePendingPhaseWork(region);
                    if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(feed)) {
                        feed=fixture.transferBox(feed,fixture.portCell,Direction.SOUTH,false,IndustrialOperations.tickQuota(50_000_000L,step));
                    }
                    if(!seeded) {
                        var block=region.origin().offset(solidInput[0]/2,solidInput[1]/2,solidInput[2]/2);
                        var contact=FluidWorldTargetResolver.transferCell(region,block,Direction.NORTH,new mmm.api.transfer.TransferQuantity.Granular(50_000L),false);
                        var liquid=contact==null?null:region.observeCellInventory(contact[0],contact[1],contact[2]);
                        if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(feed) && liquid!=null && liquid.temperatureKelvin().isPresent() && crystalExcess(region,world,reaction,contact,liquid.temperatureKelvin().getAsDouble())>=reaction.solidExtentPerBatch()*2) {
                            seed=fixture.transferBox(seed,solidInput,Direction.NORTH,false,50_000L);
                            if(mmm.item.GranularContainerItem.isEmpty(seed)){seedVolume=region.hostSnapshot().exportGranularState().totalSolidVolumeSvq();seeded=true;}
                        }
                    }
                    available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Crystallization process step rejected");
                if(step%20==0)bulkTemperature=crystalTemperature(region,world);
                if(step%100==0)System.out.println("crystallization_state step="+step+" seeded="+seeded
                        +" solid_svq="+region.hostSnapshot().exportGranularState().totalSolidVolumeSvq()+" temperature="+bulkTemperature);
                if(seeded && region.hostSnapshot().exportGranularState().totalSolidVolumeSvq()>seedVolume)grown=true;
            }
            motion.settlePendingPhaseWork(region);
            if(!seeded || region.hostSnapshot().exportGranularState().totalSolidVolumeSvq()<=seedVolume)throw new AssertionError("Cold saturated feed did not grow the crystal inventory");
            double afterWall=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream().filter(device->device.objectId().startsWith(wallPrefix)).findFirst().orElseThrow().body().internalEnergyJ();
            if(!(bulkTemperature<310 && afterWall>wallEnergy))throw new AssertionError("Cooling did not drive crystallization: bulk="+bulkTemperature+" wall="+wallEnergy+" / "+afterWall);
            var product=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GRANULAR_CONTAINER),solidOutput,Direction.NORTH,true,262_144L);
            var crystal=mmm.item.GranularContainerItem.unvalidatedContents(product);
            if(crystal==null || crystal.groups().stream().noneMatch(GranularContents.Group::reactive)
                    || mmm.item.ContainerItemStacks.thermalContents(product)==null)throw new AssertionError("Screen output did not retain crystal composition and energy");
            long seedRemaining=mmm.item.GranularContainerItem.isEmpty(seed)?0:mmm.item.GranularContainerItem.unvalidatedContents(seed).totalMassUnits();
            long after=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(feed,world)+seedRemaining+crystal.totalMassUnits();
            if(after!=Math.addExact(initial,supplied))throw new AssertionError("Crystallization and collected cake changed total mass");
            long cakeVolume=crystal.totalSolidVolumeSvq();
            product=fixture.transferBox(product,solidInput,Direction.NORTH,false,Math.max(reaction.solidVolumeSvqPerBatch(),cakeVolume/2));
            var productRemainder=mmm.item.GranularContainerItem.unvalidatedContents(product);
            if(productRemainder!=null && productRemainder.totalSolidVolumeSvq()==cakeVolume)throw new AssertionError("Collected crystals did not return to the washing chamber");
            var valve=layout.minimum().offset(3,2,1);
            var open=mmm.block.MmmBlocks.VALVE.defaultBlockState().setValue(mmm.block.ValveBlock.FACING,Direction.EAST).setValue(mmm.block.ValveBlock.OPENING,4);
            var edits=new ArrayList<FluidDomainContracts.CollisionEdit>();
            for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
                int cx=(valve.getX()-region.origin().getX())*2+x,cy=(valve.getY()-region.origin().getY())*2+y,cz=(valve.getZ()-region.origin().getZ())*2+z;
                edits.add(new FluidDomainContracts.CollisionEdit(cx,cy,cz,FluidRegionWorldInteraction.sampleCutCellMask(mmm.block.ValveBlock.shutter(open),x,y,z,2,1e-7)));
            }
            var generation=region.solverGeneration();var candidate=region.beginHostEdit();candidate.applyCollisionEdits(edits);region.adoptCollisionCandidate(generation,candidate);available(region);
            long crystalBefore=region.hostSnapshot().exportGranularState().totalMassUnits();
            var wash=pureWater(world,293.15);supplied=Math.addExact(supplied,chemicalMass(wash,world));
            for(int step=0;step<600;step++) {
                progress("crystal_dissolution",step,600);
                motion.settlePendingPhaseWork(region);
                {
                    if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(wash)){wash=pureWater(world,293.15);supplied=Math.addExact(supplied,chemicalMass(wash,world));}
                    wash=fixture.transferBox(wash,fixture.portCell,Direction.SOUTH,false,IndustrialOperations.tickQuota(50_000_000L,step));available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Crystal dissolution step rejected");
            }
            motion.settlePendingPhaseWork(region);
            long crystalAfter=region.hostSnapshot().exportGranularState().totalMassUnits();
            if(crystalAfter>=crystalBefore)throw new AssertionError("Fresh water did not dissolve retained crystals: "+crystalBefore+" / "+crystalAfter);
            long productMass=productRemainder==null?0:productRemainder.totalMassUnits();
            long washed=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(feed,world)+chemicalMass(wash,world)+seedRemaining+productMass;
            if(washed!=Math.addExact(initial,supplied))throw new AssertionError("Crystal dissolution changed total mass");
        }
    }
    private static long crystalExcess(FluidRegion region,CommittedWorldRegistries world,SolidLiquidReaction reaction,int[] cell,double temperature) {
        int index=region.grid().cell(cell[0],cell[1],cell[2]);var registry=world.chemicalRegistry();
        long water=0,extent=Long.MAX_VALUE;
        var amounts=new TreeMap<String,Long>();
        for(var stock:region.hostSnapshot().captureAuthoritativeState().liquid().physicalStocks())if(stock.key().cell()==index && stock.mixture()!=null)
            stock.mixture().stableAmounts(registry).forEach((key,value)->amounts.merge(key,value,Math::addExact));
        water=amounts.getOrDefault(BuiltinChemicalDefinitions.H2O,0L);
        for(var term:reaction.aqueousStoichiometry().entrySet())extent=Math.min(extent,amounts.getOrDefault(term.getKey(),0L)/term.getValue());
        var solubility=reaction.solubility();double ratio=solubility.ratio()+solubility.slopePerK()*(temperature-solubility.referenceTemperatureK());
        long coefficient=reaction.aqueousStoichiometry().values().iterator().next();
        long excess=extent==Long.MAX_VALUE?0:extent-(long)Math.floor(water*ratio/coefficient);
        return excess;
    }
    private static double crystalTemperature(FluidRegion region,CommittedWorldRegistries world) {
        var registry=world.chemicalRegistry();
        int material=world.thermalRegistry().materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water");
        var function=new ThermalStateFunction(world.thermalRegistry());double mass=0,weighted=0;
        for(var stock:region.hostSnapshot().captureAuthoritativeState().liquid().physicalStocks()) {
            if(stock.massUnits()==0)continue;
            var amounts=stock.mixture()==null?Map.<String,Long>of():stock.mixture().stableAmounts(registry);
            double temperature=function.stateForInternalEnergy(material,stock.massKilograms(),amounts,stock.internalEnergy()).temperatureK();
            mass+=stock.massKilograms();weighted+=stock.massKilograms()*temperature;
        }
        return weighted/mass;
    }

    private record Distillate(long volume,long feedVolume,double ethanolFraction,double bottomsFraction,double reflux,List<ChemicalContents> batches) { }
    private static void industrialDistillation(int steps) throws Exception {
        var chemistry=ProductionRegistries.load().chemical();
        double waterVolume=chemistry.species(chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O)).effectiveVolumeM3PerMol();
        double ethanolVolume=chemistry.species(chemistry.speciesHandle(BuiltinChemicalDefinitions.ETHANOL)).effectiveVolumeM3PerMol();
        double feed=waterVolume/(waterVolume+ethanolVolume);
        var single=distillationRun(IndustrialLayout.Scenario.DISTILLATION_SINGLE,Math.max(1200,steps));
        if(!(single.volume()>0 && single.ethanolFraction()>feed && single.bottomsFraction()>=0 && single.bottomsFraction()<feed))
            throw new AssertionError("Single-stage distillation did not separate the feed: "+single+" feed="+feed);
        var warm=distillationRun(IndustrialLayout.Scenario.DISTILLATION_WARM,Math.max(1200,steps));
        if(!(single.volume()>warm.volume()))throw new AssertionError("Warming the condenser did not reduce liquid collection");
        var trays=distillationRun(IndustrialLayout.Scenario.DISTILLATION,Math.max(1200,steps));
        if(!(trays.volume()>0 && trays.ethanolFraction()>feed && trays.bottomsFraction()>=0 && trays.bottomsFraction()<feed && trays.reflux()>0))
            throw new AssertionError("Tray distillation did not produce separated streams and reflux: "+trays);
        var bypass=distillationRun(IndustrialLayout.Scenario.DISTILLATION_BYPASS,Math.max(1200,steps));
        double recovered=trays.feedVolume()==0||bypass.feedVolume()==0?0:Math.min(trays.volume()/(double)trays.feedVolume(),bypass.volume()/(double)bypass.feedVolume());
        long refluxVolume=Math.min(trays.volume(),(long)Math.floor(recovered*trays.feedVolume()));
        long bypassVolume=Math.min(bypass.volume(),(long)Math.floor(recovered*bypass.feedVolume()));
        double withReflux=refluxVolume==0?0:distillateFractionAt(trays,refluxVolume,chemistry);
        double withoutReflux=bypassVolume==0?0:distillateFractionAt(bypass,bypassVolume,chemistry);
        System.out.println("distillation_reflux_comparison recovery="+recovered+" volumes="+refluxVolume+"/"+bypassVolume+" reflux="+withReflux+" closed="+withoutReflux);
        if(!(bypass.reflux()==0 && refluxVolume>0 && bypassVolume>0 && withReflux!=withoutReflux))
            throw new AssertionError("Distillation product or controls: single="+single+" warm="+warm+" trays="+trays+" bypass="+bypass+" feed="+feed);
    }
    private static Distillate distillationRun(IndustrialLayout.Scenario scenario,int steps) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-distillation-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,scenario);
        var region=fixture.region;var layout=IndustrialLayout.at(new BlockPos(14,64,20),scenario);
        var sensors=new ArrayList<mmm.block.ProcessSensorBlockEntity>();
        for(var configured:layout.sensors()) {
            var sensor=new mmm.block.ProcessSensorBlockEntity(configured.position(),layout.blocks().get(configured.position()));
            sensor.configure(configured.measure(),configured.low(),configured.high(),configured.below(),false,configured.first(),configured.second());
            sensors.add(sensor);
        }
        var temperatures=ThermalBoundaryResolver.sampleField(region.origin(),region.grid(),FluidRegionFactory.CELLS_PER_BLOCK,position ->
                layout.walls().stream().filter(wall->wall.position().equals(position)&&layout.blocks().get(position).is(mmm.block.MmmBlocks.THERMAL_RESERVOIR))
                        .mapToDouble(wall->380).findFirst().orElse(Double.NaN));
        int[] productCell={region.sizeX()-1,20,8};
        ItemStack feed=ethanolSolution(world,380,1),product=new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET);
        boolean trays=scenario==IndustrialLayout.Scenario.DISTILLATION || scenario==IndustrialLayout.Scenario.DISTILLATION_BYPASS;
        ItemStack[] priming=trays?new ItemStack[]{ethanolSolution(world,380,1),ethanolSolution(world,380,1),ethanolSolution(world,380,1)}:new ItemStack[0];
        long suppliedVolume=1_000_000_000L*(1+priming.length);
        var products=new ArrayList<ItemStack>();long bottomsMass=0,bottomsEthanol=0,bottomsWater=0;
        long initial=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(feed,world),supplied=0,ventedMass=0,ventedEthanol=0;
        for(var batch:priming)initial=Math.addExact(initial,chemicalMass(batch,world));
        double ventedEnergy=0,reflux=0;var ventedComponents=new TreeMap<String,Long>();
        var refluxFaces=new ArrayList<Integer>();
        for(int local=0;local<region.grid().faceCount(1);local++) {
            int face=region.grid().faceOffset(1)+local,left=region.grid().faceLeft(face),right=region.grid().faceRight(face);
            if(left>=0 && right>=0 && region.grid().y(left)==18
                    && region.grid().x(left)>=6 && region.grid().x(left)<8
                    && region.grid().z(left)>=8 && region.grid().z(left)<10)refluxFaces.add(face);
        }
        int[] refluxPlane=refluxFaces.stream().mapToInt(Integer::intValue).toArray();
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            int tick=0;
            long initialAir=noncondensableGasNanos(region);
            long ventRate=layout.ports().stream().filter(port->port.phase()==mmm.block.FluidPortBlockEntity.Phase.GAS).mapToLong(IndustrialLayout.Port::rate).sum();
            if(ventRate<=0)throw new AssertionError("Distillation has no startup gas outlet");
            long evacuationBudget=Math.max(20,40*((initialAir+ventRate-1)/ventRate));
            while(noncondensableGasNanos(region)>mmm.item.GasContainerItem.CAPACITY_NANOS) {
                if(tick>=evacuationBudget)throw new AssertionError("Startup air did not leave within the gas outlet capacity: remaining="+noncondensableGasNanos(region));
                if(tick%100==0)System.out.println("distillation_evacuation scenario="+scenario+" step="+tick+" remaining_nanos="+noncondensableGasNanos(region));
                motion.settlePendingPhaseWork(region);
                for(var vent:layout.ports())if(vent.phase()==mmm.block.FluidPortBlockEntity.Phase.GAS) {
                    var interior=vent.position().relative(vent.outward().getOpposite());
                    int[] cell={(interior.getX()-region.origin().getX())*2,(interior.getY()-region.origin().getY())*2+1,(interior.getZ()-region.origin().getZ())*2};
                    var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),cell,vent.outward(),true,IndustrialOperations.tickQuota(vent.rate(),tick));
                    var contents=mmm.item.GasContainerItem.contents(batch);if(contents==null)continue;
                    ventedMass=Math.addExact(ventedMass,contents.inventory().totalMassUnits());ventedEnergy+=contents.internalEnergy();
                    for(var component:contents.inventory().components())ventedComponents.merge(component.componentId(),component.standardVolumeNanos(),Math::addExact);
                    var ethanol=contents.inventory().component(BuiltinGasDefinitions.ETHANOL_VAPOR);
                    if(ethanol!=null)ventedEthanol=Math.addExact(ventedEthanol,ethanol.standardVolumeNanos());
                }
                available(region);if(!motion.step(region,List.of()).accepted())throw new AssertionError("Distillation startup air evacuation rejected");
                tick++;
            }
            System.out.println("distillation_evacuation_complete scenario="+scenario+" step="+tick+" remaining_nanos="+noncondensableGasNanos(region));
            motion.settlePendingPhaseWork(region);region.replaceThermalBoundaryTemperatures(temperatures);
            double[] upwardGas=new double[3],downwardLiquid=new double[3];
            int[][] gasFaces=trays?distillationStageFaces(region,true):new int[0][];
            int[][] liquidFaces=trays?distillationStageFaces(region,false):new int[0][];
            int operatingStep=0,startupStep=0;boolean primed=!trays,sealsMaintained=true;
            while(operatingStep<steps) {
                boolean filling=!primed;
                if(filling && startupStep++>=steps)throw new AssertionError("Distillation initial liquid seals did not fill");
                int step=filling?startupStep-1:operatingStep++;
                progress(filling?scenario.name()+"_fill":scenario.name(),step,steps);
                if(step%100==0)distillationState(region,world,layout,scenario,step,productCell);
                {
                    motion.settlePendingPhaseWork(region);
                    if(tick%4==0)for(var sensor:sensors)IndustrialOperations.observeSensor(region,sensor,sensor.first(),sensor.second());
                    for(int stage=0;stage<priming.length;stage++) {
                        if(!filling && stage!=1)continue;
                        if(distillationPoolFilled(region,stage))continue;
                        if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(priming[stage])){priming[stage]=ethanolSolution(world,380,1);supplied=Math.addExact(supplied,chemicalMass(priming[stage],world));suppliedVolume=Math.addExact(suppliedVolume,1_000_000_000L);}
                        int[] port={0,new int[]{6,12,16}[stage],6};long quota=IndustrialOperations.tickQuota(1_000_000_000L,tick);
                        var block=region.origin().offset(port[0]/2,port[1]/2,port[2]/2);
                        if(FluidWorldTargetResolver.transferCell(region,block,Direction.WEST,new mmm.api.transfer.TransferQuantity.Liquid(quota,OptionalDouble.empty()),false)!=null)
                            priming[stage]=fixture.transferBox(priming[stage],port,false,quota);
                    }
                    if(trays && filling && mmm.item.MaterialBucketItem.isEmptyMaterialBucket(feed)){feed=ethanolSolution(world,380,1);supplied=Math.addExact(supplied,chemicalMass(feed,world));suppliedVolume=Math.addExact(suppliedVolume,1_000_000_000L);}
                    if((!trays || filling) && !mmm.item.MaterialBucketItem.isEmptyMaterialBucket(feed) && industrialPortPowered(layout.ports().getFirst(),sensors))
                        feed=fixture.transferBox(feed,fixture.portCell,false,IndustrialOperations.tickQuota(layout.ports().getFirst().rate(),tick));
                    var outlet=region.origin().offset(productCell[0]/2,productCell[1]/2,productCell[2]/2);
                    long productQuota=IndustrialOperations.tickQuota(layout.ports().get(1).rate(),tick);
                    var contact=FluidWorldTargetResolver.transferCell(region,outlet,Direction.EAST,new mmm.api.transfer.TransferQuantity.Liquid(productQuota,OptionalDouble.empty()),true);
                    if(!filling && industrialPortPowered(layout.ports().get(1),sensors) && contact!=null) {
                        if(mmm.item.MaterialBucketItem.volume(product)>=950_000_000L){products.add(product);product=new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET);}
                        product=fixture.transferBox(product,productCell,true,productQuota);
                    }
                    int[] bottoms={region.sizeX()-1,0,2};
                    var bottomPosition=region.origin().offset(bottoms[0]/2,bottoms[1]/2,bottoms[2]/2);
                    var bottomContact=FluidWorldTargetResolver.transferCell(region,bottomPosition,Direction.EAST,new mmm.api.transfer.TransferQuantity.Liquid(productQuota,OptionalDouble.empty()),true);
                    long bottomQuota=IndustrialOperations.tickQuota(layout.ports().get(2).rate(),tick);
                    if(trays && !filling && bottomContact!=null && MinecraftContainerTransferOperations.extractableNanos(region.hostSnapshot(),bottomContact,bottomQuota,0,-1)>0) {
                        var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),bottoms,true,bottomQuota);
                        if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(batch)) {
                            bottomsMass=Math.addExact(bottomsMass,chemicalMass(batch,world));
                            var contents=mmm.item.ContainerItemStacks.chemicalContents(batch,world.chemicalRegistry());
                            bottomsEthanol=Math.addExact(bottomsEthanol,chemicalAmount(contents,BuiltinChemicalDefinitions.ETHANOL));
                            bottomsWater=Math.addExact(bottomsWater,chemicalAmount(contents,BuiltinChemicalDefinitions.H2O));
                        }
                    }
                    available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Distillation process step rejected");
                tick++;
                if(trays) {
                    boolean sealed=distillationSeal(region,0)&&distillationSeal(region,1)&&distillationSeal(region,2);
                    if(filling && sealed && distillationPoolFilled(region,2))primed=true;
                    if(!filling && !sealed)sealsMaintained=false;
                    if(!filling && sealed) {
                        reflux+=Math.max(0,-region.observeLiquidFlow(refluxPlane))/20.0;
                        for(int stage=0;stage<3;stage++) {
                            upwardGas[stage]+=Math.max(0,region.observeGasFlow(gasFaces[stage]))/20.0;
                            downwardLiquid[stage]+=Math.max(0,-region.observeLiquidFlow(liquidFaces[stage]))/20.0;
                        }
                    }
                    if(step%100==0)System.out.println("distillation_stages scenario="+scenario+" step="+step+" gas="+Arrays.toString(upwardGas)+" liquid="+Arrays.toString(downwardLiquid)
                            +" seals="+distillationSeal(region,0)+","+distillationSeal(region,1)+","+distillationSeal(region,2));
                }
            }
            motion.settlePendingPhaseWork(region);
            if(!trays) {
                int[] bottoms={region.sizeX()-1,0,2};long quota=50_000_000L;
                var position=region.origin().offset(bottoms[0]/2,bottoms[1]/2,bottoms[2]/2);
                var contact=FluidWorldTargetResolver.transferCell(region,position,Direction.EAST,new mmm.api.transfer.TransferQuantity.Liquid(quota,OptionalDouble.empty()),true);
                if(contact!=null && MinecraftContainerTransferOperations.extractableNanos(region.hostSnapshot(),contact,quota,0,-1)>0) {
                    var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),bottoms,true,quota);
                    if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(batch)) {
                        bottomsMass=chemicalMass(batch,world);var contents=mmm.item.ContainerItemStacks.chemicalContents(batch,world.chemicalRegistry());
                        bottomsEthanol=chemicalAmount(contents,BuiltinChemicalDefinitions.ETHANOL);bottomsWater=chemicalAmount(contents,BuiltinChemicalDefinitions.H2O);
                    }
                }
            }
            distillationState(region,world,layout,scenario,steps,productCell);
            System.out.println("distillation_vented scenario="+scenario+" mass="+ventedMass+" ethanol_nanos="+ventedEthanol+" components="+ventedComponents+" energy="+ventedEnergy);
            if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(product))products.add(product);
            long productMass=0,productVolume=0,ethanol=0,water=0;var compositions=new ArrayList<ChemicalContents>();
            for(var batch:products) {
                productMass=Math.addExact(productMass,chemicalMass(batch,world));
                var contents=mmm.item.ContainerItemStacks.chemicalContents(batch,world.chemicalRegistry());
                compositions.add(contents);
                productVolume=Math.addExact(productVolume,contents.totalVolumeNanos());
                ethanol=Math.addExact(ethanol,chemicalAmount(contents,BuiltinChemicalDefinitions.ETHANOL));
                water=Math.addExact(water,chemicalAmount(contents,BuiltinChemicalDefinitions.H2O));
            }
            long after=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(feed,world)+productMass+bottomsMass+ventedMass;
            long remainingVolume=Math.max(0,mmm.item.MaterialBucketItem.volume(feed));
            for(var batch:priming){after=Math.addExact(after,chemicalMass(batch,world));remainingVolume=Math.addExact(remainingVolume,Math.max(0,mmm.item.MaterialBucketItem.volume(batch)));}
            long feedVolume=Math.subtractExact(suppliedVolume,remainingVolume);
            if(after!=Math.addExact(initial,supplied))throw new AssertionError("Distillation changed combined mass");
            if(trays)for(int stage=0;stage<3;stage++)if(!sealsMaintained||!distillationSeal(region,stage)||upwardGas[stage]<=0||downwardLiquid[stage]<=0)
                throw new AssertionError("Distillation stage lacks sealed gas/liquid transport: stage="+stage+" gas="+upwardGas[stage]+" liquid="+downwardLiquid[stage]+" seal="+distillationSeal(region,stage));
            double bottomsFraction=bottomsEthanol+bottomsWater==0?-1:bottomsEthanol/(double)(bottomsEthanol+bottomsWater);
            if(productVolume==0)return new Distillate(0,feedVolume,0,bottomsFraction,reflux,List.of());
            var gas=region.hostSnapshot().captureAuthoritativeState().spatialGas();
            long condensedEthanol=gasPhaseSinkQuanta(gas,BuiltinGasDefinitions.ETHANOL_VAPOR,BuiltinChemicalDefinitions.ETHANOL);
            long condensedWater=gasPhaseSinkQuanta(gas,BuiltinGasDefinitions.WATER_VAPOR,BuiltinChemicalDefinitions.H2O);
            if(ethanol>condensedEthanol || water>condensedWater)
                throw new AssertionError("Distillate exceeds condensed species: ethanol="+ethanol+"/"+condensedEthanol+" water="+water+"/"+condensedWater);
            return new Distillate(productVolume,feedVolume,ethanol/(double)(ethanol+water),bottomsFraction,reflux,List.copyOf(compositions));
        }
    }
    private static long noncondensableGasNanos(FluidRegion region) {
        long total=0;
        for(var gas:region.hostSnapshot().captureAuthoritativeState().spatialGas().cells())for(var component:gas.components())
            if(!component.componentId().equals(BuiltinGasDefinitions.WATER_VAPOR) && !component.componentId().equals(BuiltinGasDefinitions.ETHANOL_VAPOR))
                total=Math.addExact(total,component.standardVolumeNanos());
        return total;
    }
    private static double distillateFractionAt(Distillate result,long volume,ChemicalRegistry registry) {
        long remaining=volume,ethanol=0,water=0;
        for(var batch:result.batches()) {
            long taken=Math.min(remaining,batch.totalVolumeNanos());
            if(taken==0)break;
            var portion=batch.split(taken,registry).inserted();
            ethanol=Math.addExact(ethanol,chemicalAmount(portion,BuiltinChemicalDefinitions.ETHANOL));
            water=Math.addExact(water,chemicalAmount(portion,BuiltinChemicalDefinitions.H2O));
            remaining-=taken;
        }
        if(remaining!=0 || ethanol+water==0)throw new IllegalStateException("Distillate comparison has no comparable product");
        return ethanol/(double)(ethanol+water);
    }
    private static long speciesAtVolume(List<ChemicalContents> batches,long volume,String species,ChemicalRegistry registry) {
        long remaining=volume,amount=0;
        for(var batch:batches) {
            long taken=Math.min(remaining,batch.totalVolumeNanos());if(taken==0)break;
            amount=Math.addExact(amount,chemicalAmount(batch.split(taken,registry).inserted(),species));remaining-=taken;
        }
        if(remaining!=0)throw new IllegalStateException("Product batches do not cover the common sample volume");
        return amount;
    }
    private static boolean industrialPortPowered(IndustrialLayout.Port port,List<mmm.block.ProcessSensorBlockEntity> sensors) {
        if(!port.redstone())return true;
        for(var sensor:sensors) {
            var a=sensor.getBlockPos();var b=port.position();
            if(Math.abs(a.getX()-b.getX())+Math.abs(a.getY()-b.getY())+Math.abs(a.getZ()-b.getZ())==1 && sensor.output()>0)return true;
        }
        return false;
    }
    private static int[][] distillationStageFaces(FluidRegion region,boolean gas) {
        int[] y={4,10,14},x=gas?new int[]{2,8,2}:new int[]{8,2,8};int z=gas?2:8;
        int[][] result=new int[3][];
        for(int stage=0;stage<3;stage++) {
            var faces=new ArrayList<Integer>();
            for(int dx=0;dx<2;dx++)for(int dz=0;dz<2;dz++) {
                int face=region.grid().faceAt(x[stage]+dx,y[stage],z+dz,1);if(face>=0)faces.add(face);
            }
            result[stage]=faces.stream().mapToInt(Integer::intValue).toArray();
        }
        return result;
    }
    private static boolean distillationSeal(FluidRegion region,int stage) {
        int[] x={8,2,8},y={0,6,12};var host=region.hostSnapshot();
        for(int dx=0;dx<2;dx++)for(int dy=0;dy<2;dy++)for(int dz=0;dz<2;dz++)
            if(host.volume(x[stage]+dx,y[stage]+dy,8+dz)<.999f)return false;
        return true;
    }
    private static boolean distillationPoolFilled(FluidRegion region,int stage) {
        int[] x={2,8,8},y={6,12,16};var host=region.hostSnapshot();
        for(int dx=0;dx<2;dx++)for(int dy=0;dy<2;dy++)for(int dz=0;dz<2;dz++)
            if(host.volume(x[stage]+dx,y[stage]+dy,8+dz)<.999f)return false;
        return true;
    }
    private static void distillationState(FluidRegion region,CommittedWorldRegistries world,IndustrialLayout layout,
            IndustrialLayout.Scenario scenario,int step,int[] productCell) {
        var state=region.hostSnapshot().captureAuthoritativeState();var gas=state.spatialGas();
        long vapor=0,upperVapor=0;
        int height=region.sizeY();long[] vaporByY=new long[height],gasMassByY=new long[height];
        double[] temperatureByY=new double[height],pressureByY=new double[height],cellMass=new double[region.grid().cellCount()];int[] gasCellsByY=new int[height];
        var gasThermal=new GasThermalModel(world.thermalRegistry(),world.gasRegistry());var host=region.hostSnapshot();
        for(int cell=0;cell<gas.cellCount();cell++) {var component=gas.cells().get(cell).component(BuiltinGasDefinitions.ETHANOL_VAPOR);
            int y=region.grid().y(cell);var inventory=gas.cells().get(cell);long mass=inventory.totalMassUnits();cellMass[cell]=Mass.kilograms(mass);
            if(component!=null){vapor=Math.addExact(vapor,component.standardVolumeNanos());vaporByY[y]=Math.addExact(vaporByY[y],component.standardVolumeNanos());if(y>=16)upperVapor=Math.addExact(upperVapor,component.standardVolumeNanos());}
            if(!inventory.isEmpty()) {gasMassByY[y]=Math.addExact(gasMassByY[y],mass);gasCellsByY[y]++;
                temperatureByY[y]+=gasThermal.state(inventory,gas.energyAt(cell)).temperatureK();
                pressureByY[y]+=host.pressure(region.grid().x(cell),y,region.grid().z(cell))+mmm.thermal.MixtureVolumeModel.REFERENCE_PRESSURE_PA;}}
        for(var stock:state.liquid().physicalStocks())cellMass[stock.key().cell()]+=stock.massKilograms();
        double[] maximumUp=new double[height],minimumDown=new double[height],momentum=state.momentum().copyY();
        for(int local=0;local<region.grid().faceCount(1);local++) {
            int face=region.grid().faceOffset(1)+local,left=region.grid().faceLeft(face),right=region.grid().faceRight(face);
            if(left<0||right<0)continue;int y=region.grid().y(left);double mass=.5*(cellMass[left]+cellMass[right]);
            if(mass>0){double speed=momentum[local]/mass;maximumUp[y]=Math.max(maximumUp[y],speed);minimumDown[y]=Math.min(minimumDown[y],speed);}
        }
        long evaporated=gasPhaseSinkQuanta(gas,BuiltinGasDefinitions.ETHANOL_VAPOR,BuiltinChemicalDefinitions.ETHANOL);
        var source=gas.phaseSource().component(BuiltinGasDefinitions.ETHANOL_VAPOR);
        long created=source==null?0:source.species().stream().filter(value->value.speciesId().equals(BuiltinChemicalDefinitions.ETHANOL)).mapToLong(value->value.amountQuanta()).sum();
        double bottom=0,upper=0,collector=0;
        for(var stock:state.liquid().physicalStocks()) {
            int cell=stock.key().cell(),y=region.grid().y(cell);
            if(y<4)bottom+=stock.volume().toDouble();
            if(y>=16)upper+=stock.volume().toDouble();
            if(region.grid().x(cell)==productCell[0] && region.grid().y(cell)/2==productCell[1]/2 && region.grid().z(cell)/2==productCell[2]/2)collector+=stock.volume().toDouble();
        }
        var cold=layout.walls().stream().filter(wall->wall.position().equals(layout.minimum().offset(6,12,5))).findFirst().orElseThrow();
        String prefix=cold.position().asLong()+"@";
        var wall=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream().filter(value->value.objectId().startsWith(prefix)).findFirst().orElseThrow();
        System.out.println("distillation_state scenario="+scenario+" step="+step+" vapor_nanos="+vapor+" upper_vapor_nanos="+upperVapor+" evaporated_quanta="+created+" condensed_quanta="+evaporated
                +" bottom_volume="+bottom+" upper_volume="+upper+" outlet_cell_volume="+collector+" wall_temperature="+SurfaceThermalState.temperature(world.thermalRegistry(),wall)
                +" wall_energy="+wall.body().internalEnergyJ()+" external_heat_and_feed="+state.thermalOwner().audit().externalSource().toDouble());
        var layers=new StringBuilder();
        for(int y=0;y<height;y++)if(gasCellsByY[y]>0)layers.append(" y").append(y).append(':').append(vaporByY[y]).append('/').append(Mass.kilograms(gasMassByY[y]))
                .append('/').append(temperatureByY[y]/gasCellsByY[y]).append('/').append(pressureByY[y]/gasCellsByY[y]).append('/').append(maximumUp[y]).append('/').append(minimumDown[y]);
        System.out.println("distillation_layers scenario="+scenario+" step="+step+" format=y:vapor_nanos/gas_kg/K/Pa/max_up_mps/max_down_mps"+layers);
    }
    private static int[] coldWallSample(FluidRegion region,CommittedWorldRegistries world,String object){
        var wall=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream()
                .filter(value->value.objectId().equals(object)).findFirst().orElseThrow();
        var state=region.hostSnapshot().captureAuthoritativeState();int[] selected={11,4,1};
        if(OrdinaryThermalWalls.ports(region,wall,world.thermalRegistry()).stream().noneMatch(port->
                port.cellX()==selected[0]&&port.cellY()==selected[1]&&port.cellZ()==selected[2]))
            throw new AssertionError("Collector is not in contact with the cold wall");
        int collector=region.grid().cell(selected[0],selected[1],selected[2]);long ethanol=0,water=0;
        for(var stock:state.liquid().physicalStocks())if(stock.key().cell()==collector&&stock.mixture()!=null){
            var amount=stock.mixture().stableAmounts(state.chemicalRegistry());
            ethanol=Math.addExact(ethanol,amount.getOrDefault(BuiltinChemicalDefinitions.ETHANOL,0L));
            water=Math.addExact(water,amount.getOrDefault(BuiltinChemicalDefinitions.H2O,0L));
        }
        if(ethanol+water==0)throw new AssertionError("Cold wall has no liquid to collect");
        double fraction=ethanol/(double)(ethanol+water);
        System.out.println("phase_sink_ethanol="+state.spatialGas().phaseSink().component(BuiltinGasDefinitions.ETHANOL_VAPOR)
                +" phase_sink_water="+state.spatialGas().phaseSink().component(BuiltinGasDefinitions.WATER_VAPOR));
        System.out.println("cold_wall_sample cell="+Arrays.toString(selected)+" ethanol_fraction="+fraction);
        return selected;
    }
    private static long gasPhaseSinkQuanta(mmm.fluid.air.SpatialGasState gas,String component,String species){
        var entry=gas.phaseSink().component(component);if(entry==null)return 0;
        return entry.species().stream().filter(value->value.speciesId().equals(species)).mapToLong(value->value.amountQuanta()).sum();
    }
    private static void progress(String scenario,int step,int total) {
        if(step%100==0)System.out.println("process_progress scenario="+scenario+" step="+step+"/"+total);
    }
    private static void printStripState(String stage,FluidDomainState state){
        var pending=state.chemicalMassTransferPending().stream().filter(value->value.definitionId().equals("mmm:co2_water")).toList();
        double residual=pending.stream().mapToDouble(RegionChemistry.MassTransferPending::residualMoles).sum();
        double seconds=pending.stream().mapToDouble(RegionChemistry.MassTransferPending::pendingSeconds).sum();
        System.out.println("process_strip "+stage+" gas_co2_nanos="+gasCo2(state)
                +" liquid_co2_quanta="+liquidSpecies(state,BuiltinChemicalDefinitions.CO2)
                +" liquid_hco3_quanta="+liquidSpecies(state,BuiltinChemicalDefinitions.HCO3_MINUS)
                +" pending_cells="+pending.size()+" pending_seconds="+seconds+" residual_moles="+residual);
    }
    private static long gasCo2(FluidDomainState state){
        long total=0;for(var inventory:state.spatialGas().cells()){
            var co2=inventory.component(BuiltinGasDefinitions.CO2);if(co2!=null)total=Math.addExact(total,co2.standardVolumeNanos());
        }return total;
    }
    private static ItemStack source(String scenario,CommittedWorldRegistries world){
        if(scenario.equals("absorption")){
            var chemistry=world.chemicalRegistry();
            var amounts=Map.of(BuiltinChemicalDefinitions.H2O,waterQuanta(world),BuiltinChemicalDefinitions.CO2,1_000_000L);
            var mixture=ChemicalMixtureRecord.fromStableAmounts(chemistry,amounts,0);long mass=mixture.speciesMassUnits(chemistry);
            var ledger=new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water",1.0,mass,0,amounts)));
            var item=mmm.item.ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger,chemistry,ContainerFluidLedger.NANOS_PER_BLOCK));
            world.bindActualContainerProfile(item);world.attachLiquidThermalContents(item,293.15);return item;
        }
        if(scenario.equals("carbonation"))return CreativeSourceCatalog.resolve(world,
                new CreativeSourceDescriptor("mmm:source/reagent/naoh",ContainerFluidLedger.NANOS_PER_BLOCK,293.15)).actualStack();
        if(!scenario.equals("precipitation"))return new ItemStack(Items.WATER_BUCKET);
        var registry=world.chemicalRegistry();var amounts=Map.of(BuiltinChemicalDefinitions.H2O,30_000_000_000L,BuiltinChemicalDefinitions.NACL,6_000_000_000L);
        long mass=Mass.fromKilograms(1000);var exact=ChemicalMixtureRecord.fromStableAmounts(registry,amounts,0);
        var ledger=new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water",1.0,mass,mass-exact.speciesMassUnits(registry),amounts)));
        var item=mmm.item.ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger,registry,ContainerFluidLedger.NANOS_PER_BLOCK));
        world.bindActualContainerProfile(item);world.attachDebugLiquidThermalContents(item);return item;
    }
    private static ItemStack ethanolSolution(CommittedWorldRegistries world,double temperature,double volume){
        var chemistry=world.chemicalRegistry();
        double waterVolume=chemistry.species(chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O)).effectiveVolumeM3PerMol();
        double ethanolVolume=chemistry.species(chemistry.speciesHandle(BuiltinChemicalDefinitions.ETHANOL)).effectiveVolumeM3PerMol();
        var amounts=Map.of(BuiltinChemicalDefinitions.H2O,Math.round(.5*volume/waterVolume*ChemicalLimits.QUANTA_PER_MOL),
                BuiltinChemicalDefinitions.ETHANOL,Math.round(.5*volume/ethanolVolume*ChemicalLimits.QUANTA_PER_MOL));
        var mixture=ChemicalMixtureRecord.fromStableAmounts(chemistry,amounts,0);
        long mass=mixture.speciesMassUnits(chemistry);
        var ledger=new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water",1.0,mass,0,amounts)));
        var item=mmm.item.ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger,chemistry,Math.round(ContainerFluidLedger.NANOS_PER_BLOCK*volume)));
        world.bindActualContainerProfile(item);world.attachLiquidThermalContents(item,temperature);return item;
    }
    private static ItemStack saltSeed(CommittedWorldRegistries world){
        return saltSeed(world,1000);
    }
    private static ItemStack saltSeed(CommittedWorldRegistries world,long batches){
        var reaction=world.granularRegistry().reactions().stream()
                .filter(value->value.solidMaterialId().equals(BuiltinGranularDefinitions.PRECIPITATE)).findFirst().orElseThrow();
        long extent=Math.multiplyExact(reaction.solidExtentPerBatch(),batches);
        long svq=Math.multiplyExact(reaction.solidVolumeSvqPerBatch(),batches);
        var amounts=new TreeMap<String,Long>();
        reaction.solidComposition().forEach((species,coefficient)->amounts.put(species,Math.multiplyExact(extent,coefficient)));
        var composition=SolidCompositionRecord.fromStableAmounts(world.chemicalRegistry(),amounts,0);
        long mass=composition.speciesMassUnits(world.chemicalRegistry());
        var ledger=new GranularTransferLedger(List.of(new GranularTransferLedger.Group(BuiltinGranularDefinitions.PRECIPITATE,svq,mass,composition)));
        var item=mmm.item.ContainerItemStacks.toStack(GranularContents.fromLedger(ledger,world.granularRegistry(),world.chemicalRegistry()));
        world.bindActualContainerProfile(item);
        var thermal=world.thermalRegistry();int material=thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR,BuiltinGranularDefinitions.PRECIPITATE);
        double energy=new ThermalStateFunction(thermal).internalEnergyAtTemperature(material,Mass.kilograms(mass),amounts,293.15);
        mmm.item.ContainerItemStacks.attachThermal(item,thermal.fingerprint(),energy);
        return item;
    }
    private static FiniteThermalDeviceState finiteWall(FluidRegion region,int[] port,ThermalRegistry registry,double temperature,String excluded){
        int n=(int)Math.round(1.0/region.solverConfig().cellSize());
        var inside=region.origin().offset(port[0]/n,port[1]/n,port[2]/n);
        String type=excluded==null?"mmm:ordinary_stone_wall":"mmm:ordinary_metal_wall";
        var def=registry.finiteDevice(type);double energy=new ThermalStateFunction(registry).internalEnergyAtTemperature(registry.materialHandle(def.thermalMaterialId()),def.bodyMassKilograms(),Map.of(),temperature);
        var body=new FiniteThermalBodyState(0,registry.fingerprint(),def.thermalMaterialId(),def.bodyMassUnits(),energy);
        var positions=excluded==null?Arrays.stream(Direction.values()).map(inside::relative)
                .filter(p->region.footprint().boundaryBlocks().contains(p.asLong())).toList()
                :region.sizeX()>4?List.of(region.origin().offset(5,1,0)):
                region.footprint().boundaryBlocks().stream().map(BlockPos::of)
                .sorted(Comparator.comparingDouble((BlockPos p)->p.distSqr(inside)).reversed()).toList();
        FiniteThermalDeviceState selected=null;
        for(var pos:positions){
            var candidate=new FiniteThermalDeviceState(OrdinaryThermalWalls.PROVIDER,pos.asLong()+(excluded==null?"@minecraft:stone":"@minecraft:iron_block"),def.id(),body);
            if(!candidate.objectId().equals(excluded)&&!OrdinaryThermalWalls.ports(region,candidate,registry).isEmpty()){selected=candidate;break;}
        }
        if(selected==null)throw new IllegalStateException("No exposed finite wall");
        return selected;
    }
    private static FiniteThermalDeviceState finiteWallAt(FluidRegion region,ThermalRegistry registry,double temperature,BlockPos pos){
        return finiteWallAt(region,registry,temperature,pos,false);
    }
    private static FiniteThermalDeviceState finiteWallAt(FluidRegion region,ThermalRegistry registry,double temperature,BlockPos pos,boolean stone){
        var def=registry.finiteDevice(stone?"mmm:ordinary_stone_wall":"mmm:ordinary_metal_wall");
        double energy=new ThermalStateFunction(registry).internalEnergyAtTemperature(registry.materialHandle(def.thermalMaterialId()),def.bodyMassKilograms(),Map.of(),temperature);
        var body=new FiniteThermalBodyState(0,registry.fingerprint(),def.thermalMaterialId(),def.bodyMassUnits(),energy);
        var wall=new FiniteThermalDeviceState(OrdinaryThermalWalls.PROVIDER,pos.asLong()+(stone?"@minecraft:stone":"@minecraft:iron_block"),def.id(),body);
        if(OrdinaryThermalWalls.ports(region,wall,registry).isEmpty())throw new IllegalStateException("Cooling wall has no gas contact");
        return wall;
    }
    private static void installFiniteWalls(FluidRegion region,ThermalRegistry registry,List<FiniteThermalDeviceState> devices){
        var inventory=region.finiteThermalDevices(registry.fingerprint());
        for(var device:devices) {
            if(inventory.devices().stream().anyMatch(existing->existing.sameObject(device)))
                inventory=inventory.extractExternal(device.providerId(),device.objectId());
            inventory=inventory.insertExternal(device);
        }
        region.restoreFiniteThermalDevices(inventory,registry,region.checkpointGeneration());
        GpuControllerFixture.attachOrdinaryWallPorts(region,registry);
    }
    private static int firstGasCell(FluidRegion region, int[] port){
        var host=region.hostSnapshot(); double h=host.config().cellSize(); var grid=region.grid();
        int best=-1, bestDistance=Integer.MAX_VALUE;
        for(int cell=0;cell<grid.cellCount();cell++) {
            int x=grid.x(cell),y=grid.y(cell),z=grid.z(cell);
            int node=host.gasNodeAt((x+.5)*h,(y+.5)*h,(z+.5)*h);
            if(node<=0) continue;
            boolean contact=host.volume(x,y,z)>0 || y>0 && grid.cell(x,y-1,z)>=0 && host.volume(x,y-1,z)>0;
            if(!contact) continue;
            int distance=Math.abs(x-(port[0]+1))+Math.abs(y-port[1])+Math.abs(z-port[2]);
            if(distance<bestDistance){bestDistance=distance;best=node-1;}
        }
        if(best<0) throw new IllegalStateException("No gas cell contacts the cross-block liquid feed");
        return best;
    }
    private static long liquidInorganicCarbon(FluidDomainState state){return Math.addExact(liquidSpecies(state,BuiltinChemicalDefinitions.CO2),liquidSpecies(state,BuiltinChemicalDefinitions.HCO3_MINUS));}
    private static long liquidSpecies(FluidDomainState state,String species){long total=0;for(var stock:state.liquid().physicalStocks())if(stock.mixture()!=null)total=Math.addExact(total,stock.mixture().stableAmounts(state.chemicalRegistry()).getOrDefault(species,0L));return total;}
    private static void available(FluidRegion region){boolean[] available=new boolean[region.grid().cellCount()];Arrays.fill(available,true);region.prepareGroupAvailability(available);}
}
