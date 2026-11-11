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
        if(steps<=0)throw new IllegalArgumentException("Process steps must be positive");
        if(scenario.startsWith("industrial") && !scenario.contains(",")) {
            System.out.println("process_start scenario="+scenario);
            try {
                switch(scenario) {
                    case "industrial" -> industrial(steps);
                    case "industrial_valve" -> industrialValve(steps);
                    case "industrial_heat" -> industrialHeat(steps);
                    case "industrial_heat_metal" -> counterflowRun(steps,false,false);
                    case "industrial_heat_stone" -> counterflowRun(steps,true,false);
                    case "industrial_heat_probe" -> counterflowRun(steps,false,true);
                    case "industrial_heat_stone_probe" -> counterflowRun(steps,true,true);
                    case "industrial_adsorption" -> industrialAdsorption(steps);
                    case "industrial_catalysis" -> industrialCatalysis(steps);
                    case "industrial_screen" -> industrialScreen(steps);
                    case "industrial_crystallization" -> industrialCrystallization(steps);
                    case "industrial_distillation" -> industrialDistillation(steps);
                    case "industrial_distillation_single" -> industrialDistillationSingle(steps);
                    case "industrial_distillation_cascade" -> industrialDistillation(steps);
                    case "industrial_distillation_warm" -> distillationRun(IndustrialLayout.Scenario.DISTILLATION_WARM,steps,true,null);
                    case "industrial_distillation_probe" -> distillationRun(IndustrialLayout.Scenario.DISTILLATION_SINGLE,steps,true,null);
                    default -> throw new IllegalArgumentException("Unknown industrial scenario: "+scenario);
                }
                System.out.println((scenario.endsWith("_probe")?"process_diagnostic_complete scenario=":"process_complete scenario=")+scenario);
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
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.SCREENING);
        var region=fixture.region;var screens=region.hostSnapshot().captureAuthoritativeState().fixedGeometry().screens();
        if(screens.isEmpty())throw new AssertionError("Screen blocks did not configure shared faces");
        ItemStack remainder=fixture.transferBoxDurable(new ItemStack(Items.WATER_BUCKET),fixture.portCell,Direction.WEST,false,50_000_000L);
        long accepted=1_000_000_000L-mmm.item.PartialWaterBucketItem.volume(remainder);
        var host=region.hostSnapshot();long liquid=host.totalMassUnits();
        long actual=MinecraftContainerTransferOperations.cellVolumeToNanos(host.unitScale(),host.totalVolumeExact());
        long expectedMass=Mass.fromKilograms(host.unitScale().cubicMeters(host.totalVolumeExact())*world.resolveMaterial("mmm:water").referenceDensity());
        if(accepted<=0 || accepted>50_000_000L || actual!=accepted || liquid!=expectedMass)
            throw new AssertionError("Box input settlement differs: requested=50000000 accepted="+accepted+" actual="+actual+" mass="+liquid+" expected="+expectedMass);
        var gas=fixture.transferBoxDurable(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),fixture.portCell,Direction.WEST,true,10_000_000L);
        var gasContents=mmm.item.GasContainerItem.contents(gas);
        if(gasContents==null||gasContents.inventory().isEmpty())throw new AssertionError("Box output did not retain the gas mixture");
        fixture.transferBoxDurable(gas,fixture.portCell,Direction.WEST,false,gasContents.inventory().standardVolumeNanos());
        long initialMass=processMass(region.hostSnapshot().captureAuthoritativeState());

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
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.TWO_TANKS);
        var region=fixture.region;var layout=IndustrialLayout.at(new BlockPos(14,64,20),IndustrialLayout.Scenario.TWO_TANKS);
        var valves=layout.blocks().entrySet().stream().filter(entry->entry.getValue().is(mmm.block.MmmBlocks.VALVE)).toList();
        var faceList=new ArrayList<Integer>();
        for(var valve:valves) {
            int bx=2*(valve.getKey().getX()-region.origin().getX());
            int by=2*(valve.getKey().getY()-region.origin().getY());
            int bz=2*(valve.getKey().getZ()-region.origin().getZ());
            for(int y=0;y<2;y++)for(int z=0;z<2;z++)faceList.add(region.grid().faceAt(bx+1,by+y,bz+z,0));
        }
        int[] faces=faceList.stream().mapToInt(Integer::intValue).toArray();
        if(closed) {
            var state=mmm.block.MmmBlocks.VALVE.defaultBlockState().setValue(mmm.block.ValveBlock.FACING,Direction.EAST).setValue(mmm.block.ValveBlock.OPENING,0);
            var generation=region.solverGeneration();var candidate=region.beginHostEdit();var edits=new ArrayList<FluidDomainContracts.CollisionEdit>();
            for(var valve:valves)for(int x=0;x<2;x++)for(int y=0;y<2;y++)for(int z=0;z<2;z++) {
                int cx=2*(valve.getKey().getX()-region.origin().getX())+x;
                int cy=2*(valve.getKey().getY()-region.origin().getY())+y;
                int cz=2*(valve.getKey().getZ()-region.origin().getZ())+z;
                int mask=FluidRegionWorldInteraction.sampleCutCellMask(mmm.block.ValveBlock.shutter(state),x,y,z,2,1e-7);
                if(candidate.solidMicroMask(cx,cy,cz)!=mask)edits.add(new FluidDomainContracts.CollisionEdit(cx,cy,cz,mask));
            }
            if(edits.isEmpty())throw new AssertionError("Closing the valve did not change its collision geometry");
            candidate.applyCollisionEdits(edits);region.adoptCollisionCandidate(generation,candidate);
        }
        ItemStack batch=new ItemStack(Items.WATER_BUCKET);double flow=0;long output=0,inputMass=0,outputMass=0;
        long initialMass=processMass(region.hostSnapshot().captureAuthoritativeState());

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            for(int step=0;step<steps;step++) {
                progress(closed?"valve_closed":"valve_open",step,steps);
                {
                    motion.settlePendingPhaseWork(region);
                    if(batch.is(Items.BUCKET))batch=new ItemStack(Items.WATER_BUCKET);
                    long beforeInput=processMass(region.hostSnapshot().captureAuthoritativeState());
                    batch=fixture.transferBox(batch,fixture.portCell,Direction.WEST,false,IndustrialOperations.tickQuota(50_000_000L,step));available(region);
                    inputMass=Math.addExact(inputMass,processMass(region.hostSnapshot().captureAuthoritativeState())-beforeInput);
                }
                motion.settlePendingPhaseWork(region);
                int[] outlet=industrialPortCell(region,layout.ports().get(1));long quota=IndustrialOperations.tickQuota(50_000_000L,step);
                var block=region.origin().offset(outlet[0]/2,outlet[1]/2,outlet[2]/2);
                var contact=FluidWorldTargetResolver.transferCell(region,block,Direction.EAST,new mmm.api.transfer.TransferQuantity.Liquid(quota,OptionalDouble.empty()),true);
                if(contact!=null && MinecraftContainerTransferOperations.extractableNanos(region.hostSnapshot(),contact,quota,0,-1)>0) {
                    long beforeOutput=processMass(region.hostSnapshot().captureAuthoritativeState());
                    var product=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),outlet,Direction.EAST,true,quota);
                    long accepted=mmm.item.MaterialBucketItem.volume(product);if(accepted>0)output=Math.addExact(output,accepted);available(region);
                    outputMass=Math.addExact(outputMass,beforeOutput-processMass(region.hostSnapshot().captureAuthoritativeState()));
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Valve process step rejected");
                flow+=Math.abs(region.observeLiquidFlow(faces));
            }
            long finalMass=processMass(region.hostSnapshot().captureAuthoritativeState());
            System.out.println("valve_control_volume closed="+closed+" initial="+initialMass+" input="+inputMass+" output="+outputMass+" final="+finalMass);
            if(inputMass<0 || outputMass<0 || finalMass!=Math.subtractExact(Math.addExact(initialMass,inputMass),outputMass))
                throw new AssertionError("Valve control-volume mass did not close");
            return new ValveResult(flow,output);
        }
    }
    private static void industrialHeat(int steps) throws Exception {
        var metal=counterflowRun(steps,false,false);
        var stone=counterflowRun(metal.ticks(),true,false);
        if(!(metal.temperature()>stone.temperature()))throw new AssertionError("Copper wall did not transfer more heat than stone: "+metal+" / "+stone);
    }
    private record CounterflowResult(double temperature,int ticks) { }
    private static CounterflowResult counterflowRun(int steps,boolean stone,boolean diagnostic) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-counterflow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,stone?IndustrialLayout.Scenario.COUNTERFLOW_STONE:IndustrialLayout.Scenario.COUNTERFLOW);
        var region=fixture.region;
        var layout=IndustrialLayout.at(new BlockPos(14,64,20),stone?IndustrialLayout.Scenario.COUNTERFLOW_STONE:IndustrialLayout.Scenario.COUNTERFLOW);
        var hotInputPort=layout.ports().get(0);var hotOutputPort=layout.ports().get(1);
        var coldInputPort=layout.ports().get(2);var coldOutputPort=layout.ports().get(3);
        var hotDrainPort=layout.ports().get(4);
        int[] hotIn=industrialPortCell(region,hotInputPort),hotOut=industrialPortCell(region,hotOutputPort);
        int[] coldIn=industrialPortCell(region,coldInputPort),coldOut=industrialPortCell(region,coldOutputPort);
        ItemStack hot=saltedWater(world,360),cold=pureWater(world,280);
        var hotProducts=new ArrayList<ItemStack>();var coldProducts=new ArrayList<ItemStack>();
        var vented=new ArrayList<ItemStack>();
        var transfers=new TransferEnergyTotals();
        boolean hotFlow=false,coldFlow=false;
        double hotInputEnergy=0,coldInputEnergy=0,referenceColdHeat=0;
        int operatingSteps=0;
        long hotCharge=0,coldCharge=0;
        long hotCapacity=layout.counterflowChargeNanos(Direction.NORTH),coldCapacity=layout.counterflowChargeNanos(Direction.SOUTH);
        double startupHot=Double.NaN,startupCold=Double.NaN;
        long initial=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(hot,world)+chemicalMass(cold,world),supplied=0;

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            int runSteps=diagnostic?steps:steps>20?steps:6000;
            int completedSteps=0;
            for(int step=0;step<runSteps;step++) {
                progress("counterflow",step,runSteps);
                double coldTransferred=0;
                {
                    motion.settlePendingPhaseWork(region);
                    fixture.beginTransfers();
                    var transferAudit=fixture.transferState().thermalAudit();
                    counterflowVent(fixture,region,layout,vented,step,!hotFlow,!coldFlow);
                    {
                        if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(hot)){hot=saltedWater(world,360);supplied=Math.addExact(supplied,chemicalMass(hot,world));}
                        double before=liquidBatchEnergy(hot);
                        long volumeBefore=counterflowBatchVolume(hot,world);
                        long inputQuota=hotFlow&&coldFlow?IndustrialOperations.tickQuota(2_000_000L,step):Math.min(hotCapacity-hotCharge,IndustrialOperations.tickQuota(hotInputPort.rate(),step));
                        if(inputQuota>0)hot=fixture.transferBox(hot,hotIn,hotInputPort.outward(),false,inputQuota);
                        if(!hotFlow||!coldFlow)hotCharge+=volumeBefore-counterflowBatchVolume(hot,world);
                        hotInputEnergy+=before-liquidBatchEnergy(hot);
                    }
                    {
                        if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(cold)){cold=pureWater(world,280);supplied=Math.addExact(supplied,chemicalMass(cold,world));}
                        double before=liquidBatchEnergy(cold);
                        long volumeBefore=counterflowBatchVolume(cold,world);
                        long inputQuota=hotFlow&&coldFlow?IndustrialOperations.tickQuota(2_000_000L,step):Math.min(coldCapacity-coldCharge,IndustrialOperations.tickQuota(coldInputPort.rate(),step));
                        if(inputQuota>0)cold=fixture.transferBox(cold,coldIn,coldInputPort.outward(),false,inputQuota);
                        if(!hotFlow||!coldFlow)coldCharge+=volumeBefore-counterflowBatchVolume(cold,world);
                        coldInputEnergy+=before-liquidBatchEnergy(cold);
                    }
                    long quota=IndustrialOperations.tickQuota(hotFlow&&coldFlow?2_000_000L:1_000_000_000L,step);
                    hotFlow=hotCharge>=hotCapacity;
                    coldFlow=coldCharge>=coldCapacity;
                    if(hotFlow && coldFlow && Double.isNaN(startupHot)) {
                        var snapshot=fixture.transferState();
                        startupHot=counterflowLiquidVolume(snapshot,region,layout,Direction.NORTH);
                        startupCold=counterflowLiquidVolume(snapshot,region,layout,Direction.SOUTH);
                    }
                    if(hotFlow && coldFlow)quota=IndustrialOperations.tickQuota(2_000_000L,step);
                    if(hotFlow && coldFlow) {
                        var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),hotOut,hotOutputPort.outward(),true,quota);
                        if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(batch))hotProducts.add(batch);
                    }
                    if(hotFlow && coldFlow) {
                        var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),coldOut,coldOutputPort.outward(),true,quota);
                        if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(batch)){coldProducts.add(batch);coldTransferred=Mass.kilograms(chemicalMass(batch,world));}
                    }
                    transfers.add(transferAudit,fixture.transferState().thermalAudit());fixture.commitTransfers();available(region);
                }
                if(hotFlow&&coldFlow)operatingSteps++;
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Counterflow process step rejected");
                if(hotFlow&&coldFlow)referenceColdHeat+=counterflowColdPower(region,world,layout,stone,coldTransferred*20)*.05;
                completedSteps=step+1;
                if(completedSteps%100==0)System.out.println("counterflow_progress seconds="+(completedSteps/20.0)+" charged_nanos="+hotCharge+"/"+coldCharge
                        +" operating="+(hotFlow&&coldFlow)+" latest_cold_K="+(coldProducts.isEmpty()?0:counterflowProducts(List.of(coldProducts.getLast()),world).temperature()));
                if(!diagnostic && !stone && !coldProducts.isEmpty()
                        && counterflowProducts(List.of(coldProducts.getLast()),world).temperature()>=285)break;
            }
            motion.settlePendingPhaseWork(region);
            if(!diagnostic && (hotProducts.isEmpty()||coldProducts.isEmpty()))throw new AssertionError("Counterflow streams did not reach their output ports");
            var hotOutput=counterflowProducts(hotProducts,world);
            var coldOutput=counterflowProducts(coldProducts,world);
            double hotTemperature=hotOutput.temperature();
            double coldTemperature=coldOutput.temperature();
            double latestCold=coldProducts.isEmpty()?Double.NaN:counterflowProducts(List.of(coldProducts.getLast()),world).temperature();
            var finalHost=region.hostSnapshot();
            double hotHold=counterflowLiquidVolume(finalHost,region,layout,Direction.NORTH);
            double coldHold=counterflowLiquidVolume(finalHost,region,layout,Direction.SOUTH);
            double seconds=operatingSteps/20.0;
            double hotRate=seconds>0?hotProducts.stream().mapToLong(batch->counterflowBatchVolume(batch,world)).sum()*1e-9/seconds:0;
            double coldRate=seconds>0?coldProducts.stream().mapToLong(batch->counterflowBatchVolume(batch,world)).sum()*1e-9/seconds:0;
            System.out.printf(Locale.ROOT,"counterflow_operation startup_s=%.3f operation_s=%.3f target_m3=%.9g/%.9g charged_m3=%.9g/%.9g startup_hold_m3=%.9g/%.9g final_hold_m3=%.9g/%.9g output_m3_per_s=%.9g/%.9g residence_s=%.9g/%.9g latest_cold_K=%.9g latest_cold_rise_K=%.9g%n",
                    (completedSteps-operatingSteps)/20.0,seconds,hotCapacity*1e-9,coldCapacity*1e-9,hotCharge*1e-9,coldCharge*1e-9,startupHot,startupCold,hotHold,coldHold,hotRate,coldRate,
                    hotRate>0?hotHold/hotRate:Double.NaN,coldRate>0?coldHold/coldRate:Double.NaN,latestCold,latestCold-280);
            long hotSalt=hotOutput.salt(),coldSalt=coldOutput.salt();
            System.out.println("counterflow_batches stone="+stone+" hot_input_u="+hotInputEnergy+" cold_input_u="+coldInputEnergy
                    +" hot="+hotOutput+" cold="+coldOutput);
            counterflowTheory(region,world,layout,stone,Math.max(operatingSteps,1),hotOutput,coldOutput);
            System.out.printf(Locale.ROOT,"counterflow_heat_window stone=%s full_wet_reference_J=%.9g product_sensible_J=%.9g%n",
                    stone,referenceColdHeat,coldOutput.mass()*world.thermalRegistry().material(world.thermalRegistry().materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water")).specificHeatAt(280)*(coldOutput.temperature()-280));
            if(!diagnostic && !stone && !(latestCold>=285))throw new AssertionError("Counterflow cold outlet rise below 5 K: "+latestCold);
            if(!diagnostic && !(hotTemperature<360 && (stone || coldTemperature>280) && hotSalt>0 && coldSalt==0))
                throw new AssertionError("Counterflow output: hot="+hotTemperature+" cold="+coldTemperature+" salt="+hotSalt+"/"+coldSalt);
            long productMass=0;for(var batch:hotProducts)productMass=Math.addExact(productMass,chemicalMass(batch,world));for(var batch:coldProducts)productMass=Math.addExact(productMass,chemicalMass(batch,world));
            long gasMass=0;double gasEnergy=0;var gasComponents=new TreeMap<String,Long>();
            for(var batch:vented){var contents=mmm.item.GasContainerItem.contents(batch);gasMass=Math.addExact(gasMass,contents.inventory().totalMassUnits());gasEnergy+=contents.internalEnergy();for(var component:contents.inventory().components())gasComponents.merge(component.componentId(),component.standardVolumeNanos(),Math::addExact);}
            System.out.println("counterflow_vent mass="+gasMass+" energy="+gasEnergy+" components="+gasComponents);
            double productEnergy=hotOutput.energy()+coldOutput.energy();
            var finalState=region.hostSnapshot();
            long after=processMass(finalState.captureAuthoritativeState())+chemicalMass(hot,world)+chemicalMass(cold,world)+productMass+gasMass;
            if(after!=Math.addExact(initial,supplied))throw new AssertionError("Counterflow changed combined mass");
            if(!finalState.thermalAudit().acceptsInventory(finalState.exactInternalEnergy()))throw new AssertionError("Counterflow fluid energy audit did not close");
            requireEnergyTotal(transfers.source.toDouble(),hotInputEnergy+coldInputEnergy,"Counterflow incoming batch energy");
            requireEnergyTotal(transfers.sink.toDouble(),productEnergy+gasEnergy,"Counterflow outgoing batch energy");
            if(diagnostic)return new CounterflowResult(coldTemperature,completedSteps);
            double beforeStop=counterflowWallEnergy(region,world,layout);
            double peakWallEnergy=beforeStop;
            var activeDrain=new IndustrialLayout.Port(hotDrainPort.position(),hotDrainPort.outward(),mmm.block.FluidPortBlockEntity.Mode.OUTPUT,
                    hotDrainPort.phase(),hotDrainPort.rate(),hotDrainPort.redstone());
            for(int step=0;step<600;step++) {
                progress("counterflow_after_stop",step,600);
                motion.settlePendingPhaseWork(region);
                fixture.beginTransfers();
                var transferAudit=fixture.transferState().thermalAudit();
                long quota=IndustrialOperations.tickQuota(2_000_000L,step);
                if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(cold)){cold=pureWater(world,280);supplied=Math.addExact(supplied,chemicalMass(cold,world));}
                double before=liquidBatchEnergy(cold);
                cold=fixture.transferBox(cold,coldIn,coldInputPort.outward(),false,quota);
                coldInputEnergy+=before-liquidBatchEnergy(cold);
                for(var port:List.of(activeDrain,hotOutputPort,coldOutputPort)) {
                    var cell=industrialPortCell(region,port);
                    var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),cell,port.outward(),true,port==activeDrain?IndustrialOperations.tickQuota(activeDrain.rate(),step):quota);
                    productMass=Math.addExact(productMass,chemicalMass(batch,world));productEnergy+=liquidBatchEnergy(batch);
                }
                transfers.add(transferAudit,fixture.transferState().thermalAudit());fixture.commitTransfers();available(region);
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Counterflow after hot feed stopped rejected");
                peakWallEnergy=Math.max(peakWallEnergy,counterflowWallEnergy(region,world,layout));
            }
            motion.settlePendingPhaseWork(region);
            double afterStop=counterflowWallEnergy(region,world,layout);
            System.out.println("counterflow_after_stop stone="+stone+" wall_energy_initial="+beforeStop
                    +" wall_energy_peak="+peakWallEnergy+" wall_energy_final="+afterStop+" released="+(peakWallEnergy-afterStop));
            if(!(afterStop<peakWallEnergy))throw new AssertionError("Finite wall did not release stored heat after hot feed stopped");
            gasMass=0;for(var batch:vented)gasMass=Math.addExact(gasMass,gasMass(batch));
            var stoppedState=region.hostSnapshot();
            long stopped=processMass(stoppedState.captureAuthoritativeState())+chemicalMass(hot,world)+chemicalMass(cold,world)+productMass+gasMass;
            if(stopped!=Math.addExact(initial,supplied))throw new AssertionError("Counterflow after hot feed stopped changed combined mass");
            if(!stoppedState.thermalAudit().acceptsInventory(stoppedState.exactInternalEnergy()))throw new AssertionError("Counterflow after hot feed stopped energy audit did not close");
            requireEnergyTotal(transfers.source.toDouble(),hotInputEnergy+coldInputEnergy,"Counterflow incoming batch energy after stop");
            requireEnergyTotal(transfers.sink.toDouble(),productEnergy+gasEnergy,"Counterflow outgoing batch energy after stop");
            return new CounterflowResult(coldTemperature,completedSteps);
        }
    }
    private static double counterflowWallEnergy(FluidRegion region,CommittedWorldRegistries world,IndustrialLayout layout) {
        var wallIds=layout.walls().stream().map(wall->wall.position().asLong()+"@").toList();
        return region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream()
                .filter(device->(device.providerId().equals(WorldThermalEnvironment.PROVIDER)
                        && wallIds.stream().anyMatch(device.objectId()::startsWith))
                        || (device.providerId().equals(FiniteHeatExchangerOperations.PROVIDER)
                        && layout.walls().stream().anyMatch(wall->device.objectId().equals(Long.toString(wall.position().asLong())))))
                .mapToDouble(device->device.body().internalEnergyJ()).sum();
    }
    private static void counterflowVent(GpuControllerFixture fixture,FluidRegion region,IndustrialLayout layout,List<ItemStack> products,int tick,boolean hotOpen,boolean coldOpen) throws Exception {
        for(var port:layout.ports())if(port.phase()==mmm.block.FluidPortBlockEntity.Phase.GAS) {
            if(port.position().getZ()==layout.ports().getFirst().position().getZ()?!hotOpen:!coldOpen)continue;
            int[] cell=industrialPortCell(region,port);
            var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),cell,port.outward(),true,IndustrialOperations.tickQuota(port.rate(),tick));
            if(mmm.item.GasContainerItem.contents(batch)!=null)products.add(batch);
        }
    }
    private static long counterflowBatchVolume(ItemStack batch,CommittedWorldRegistries world) {
        return mmm.item.MaterialBucketItem.isEmptyMaterialBucket(batch)?0:
                mmm.item.ContainerItemStacks.chemicalContents(batch,world.chemicalRegistry()).totalVolumeNanos();
    }
    private static double counterflowLiquidVolume(mmm.fluid.HostFluidState host,FluidRegion region,IndustrialLayout layout,Direction side) {
        double volume=0;int scale=FluidRegionFactory.CELLS_PER_BLOCK;
        for(var wall:layout.walls()) {
            var block=wall.position().relative(side);
            int x0=(block.getX()-region.origin().getX())*scale,z0=(block.getZ()-region.origin().getZ())*scale;
            for(int x=x0;x<x0+scale;x++)for(int z=z0;z<z0+scale;z++)for(int y=0;y<region.sizeY();y++) {
                var liquid=host.observeCellInventory(x,y,z);
                if(liquid!=null)volume+=liquid.liquidVolumeCubicMetres();
            }
        }
        return volume;
    }
    private static double liquidBatchEnergy(ItemStack batch) {
        var thermal=mmm.item.ContainerItemStacks.thermalContents(batch);return thermal==null?0:thermal.internalEnergy();
    }
    private record CounterflowProducts(double mass,double temperature,double energy,long salt) {}
    private static void counterflowTheory(FluidRegion region,CommittedWorldRegistries world,IndustrialLayout layout,boolean stone,int steps,
                                          CounterflowProducts hot,CounterflowProducts cold) {
        var registry=world.thermalRegistry();
        String wallId=stone?"mmm:ordinary_stone_wall":FiniteHeatExchangerOperations.PROVIDER;
        var wall=registry.finiteDevices().stream().filter(device->device.id().equals(wallId)).findFirst().orElseThrow();
        var wallMaterial=registry.material(registry.materialHandle(wall.thermalMaterialId()));
        var water=registry.material(registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water"));
        var north=wall.ports().stream().filter(port->port.localFace()==FiniteThermalDeviceDefinition.LocalFace.NORTH).findFirst().orElseThrow();
        var south=wall.ports().stream().filter(port->port.localFace()==FiniteThermalDeviceDefinition.LocalFace.SOUTH).findFirst().orElseThrow();
        double area=layout.walls().size();
        double seconds=steps/20.0,cp=water.specificHeatAt(320),hotRate=hot.mass()/seconds*cp,coldRate=cold.mass()/seconds*cp;
        double wallT=counterflowWallTemperature(region,world,layout,wall,wallMaterial);
        double hotFilm=counterflowHeatCoefficient(region,world,layout,360,wallT,hot.mass()/seconds);
        double coldFilm=counterflowHeatCoefficient(region,world,layout,280,wallT,cold.mass()/seconds);
        double hotResistance=north.wallThicknessMeters()/wallMaterial.conductivityWPerMeterK()+1/hotFilm;
        double coldResistance=south.wallThicknessMeters()/wallMaterial.conductivityWPerMeterK()+1/coldFilm;
        double hotG=area/hotResistance,coldG=area/coldResistance,ua=area/(hotResistance+coldResistance);
        double cmin=Math.min(hotRate,coldRate),cmax=Math.max(hotRate,coldRate),effectiveness=0;
        if(cmin>0) {
            double ratio=cmin/cmax,ntu=ua/cmin;
            if(Math.abs(1-ratio)<1e-12)effectiveness=ntu/(1+ntu);
            else {double change=-Math.expm1(-ntu*(1-ratio));effectiveness=change/(1-ratio+ratio*change);}
        }
        double steadyColdRise=coldRate>0?effectiveness*cmin*80/coldRate:0;
        double wallCapacity=layout.walls().size()*Mass.kilograms(wall.bodyMassUnits())*wallMaterial.specificHeatAt(320);
        double coldPower=coldG*(wallT-280);
        System.out.printf(Locale.ROOT,"counterflow_theory stone=%s area_full_m2=%.3f wall_temperature_K=%.9g hot_film_W_per_m2_K=%.9g cold_film_W_per_m2_K=%.9g hot_resistance=%.9g cold_resistance=%.9g UA_W_per_K=%.9g hot_window_capacity_W_per_K=%.9g cold_window_capacity_W_per_K=%.9g steady_cold_rise_K=%.9g wall_capacity_J_per_K=%.9g wall_tau_s=%.9g wall_to_cold_W=%.9g observed_cold_rise_K=%.9g%n",
                stone,area,wallT,hotFilm,coldFilm,hotResistance,coldResistance,ua,hotRate,coldRate,steadyColdRise,wallCapacity,wallCapacity/(hotG+coldG),coldPower,cold.temperature()-280);
    }
    private static double counterflowWallTemperature(FluidRegion region,CommittedWorldRegistries world,IndustrialLayout layout,
                                                      FiniteThermalDeviceDefinition wall,ThermalMaterialDefinition material) {
        double specific=counterflowWallEnergy(region,world,layout)/(layout.walls().size()*Mass.kilograms(wall.bodyMassUnits()));
        return material.referenceTemperatureK()+(specific-material.referenceSpecificEnergyJPerKg())/material.specificHeatAt(320);
    }
    private static double counterflowHeatCoefficient(FluidRegion region,CommittedWorldRegistries world,IndustrialLayout layout,
                                                      double liquidT,double wallT,double massRate) {
        var registry=world.thermalRegistry();var water=registry.material(registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water"));
        var fluid=world.materialRegistry().entry("mmm:water");double rho=fluid.material().referenceDensity(),mu=fluid.dynamicViscosityAt(liquidT);
        double cp=water.specificHeatAt(liquidT),k=water.conductivityWPerMeterK(),length=region.solverConfig().cellSize();
        var expansion=fluid.thermalExpansion();double beta=expansion.volumetricCoefficientPerK()/(1+expansion.volumetricCoefficientPerK()*(liquidT-expansion.referenceTemperatureK()));
        int minX=layout.walls().stream().mapToInt(w->w.position().getX()).min().orElseThrow(),maxX=layout.walls().stream().mapToInt(w->w.position().getX()).max().orElseThrow();
        double crossSection=.25*layout.walls().size()/(double)(maxX-minX+1),velocity=massRate/(rho*crossSection);
        double pr=mu*cp/k,ra=region.solverConfig().gravity()*Math.abs(wallT-liquidT)*beta*length*length*length*rho*rho*cp/(mu*k);
        double natural=ra>0?Math.pow(.825+.387*Math.pow(ra,1.0/6)/Math.pow(1+Math.pow(.492/pr,9.0/16),8.0/27),2):0;
        double re=rho*velocity*length/mu,critical=500000,forced=re<=critical?.664*Math.sqrt(re)*Math.cbrt(pr):
                (.664*Math.sqrt(critical)+.037*(Math.pow(re,.8)-Math.pow(critical,.8)))*Math.cbrt(pr);
        return Math.max(k/(length/2),k*Math.cbrt(natural*natural*natural+forced*forced*forced)/length);
    }
    private static double counterflowColdPower(FluidRegion region,CommittedWorldRegistries world,IndustrialLayout layout,boolean stone,double coldRate) {
        var registry=world.thermalRegistry();String id=stone?"mmm:ordinary_stone_wall":FiniteHeatExchangerOperations.PROVIDER;
        var wall=registry.finiteDevices().stream().filter(device->device.id().equals(id)).findFirst().orElseThrow();
        var material=registry.material(registry.materialHandle(wall.thermalMaterialId()));
        var port=wall.ports().stream().filter(value->value.localFace()==FiniteThermalDeviceDefinition.LocalFace.SOUTH).findFirst().orElseThrow();
        double wallT=counterflowWallTemperature(region,world,layout,wall,material);
        double film=counterflowHeatCoefficient(region,world,layout,280,wallT,coldRate);
        return layout.walls().size()*(wallT-280)/(port.wallThicknessMeters()/material.conductivityWPerMeterK()+1/film);
    }
    private static CounterflowProducts counterflowProducts(List<ItemStack> products,CommittedWorldRegistries world) {
        double weighted=0,mass=0,energy=0;long salt=0;
        for(var batch:products) {
            var contents=mmm.item.ContainerItemStacks.chemicalContents(batch,world.chemicalRegistry());
            salt=Math.addExact(salt,chemicalAmount(contents,BuiltinChemicalDefinitions.NACL)+chemicalAmount(contents,BuiltinChemicalDefinitions.NA_PLUS));
            var thermal=mmm.item.ContainerItemStacks.thermalContents(batch);double kilograms=Mass.kilograms(contents.totalMassUnits());
            weighted+=kilograms*LiquidContainerThermodynamics.temperatureK(world.thermalRegistry(),thermal,contents);mass+=kilograms;energy+=thermal.internalEnergy();
        }
        return new CounterflowProducts(mass,weighted/mass,energy,salt);
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
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.ABSORPTION);
        var region=fixture.region;
        var layout=IndustrialLayout.at(new BlockPos(14,64,20),IndustrialLayout.Scenario.ABSORPTION);
        int[] treatedIn=industrialPortCell(region,layout.ports().get(0)),treatedOut=industrialPortCell(region,layout.ports().get(1));
        int[] controlIn=industrialPortCell(region,layout.ports().get(2)),controlOut=industrialPortCell(region,layout.ports().get(3));
        ItemStack treated=gasFeed(world),control=gasFeed(world);
        ItemStack treatedProduct=null,controlProduct=null;
        var products=new ArrayList<ItemStack>();
        double peakSeparation=0;
        long initial=surfaceProcessMass(region,world)+gasMass(treated)+gasMass(control),supplied=0;

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
                    treated=fixture.transferBox(treated,treatedIn,Direction.WEST,false,quota);
                    control=fixture.transferBox(control,controlIn,Direction.WEST,false,quota);
                    {
                        var gasRequest=new mmm.api.transfer.TransferQuantity.Gas(quota,0);
                        var treatedBlock=region.origin().offset(treatedOut[0]/2,treatedOut[1]/2,treatedOut[2]/2);
                        var controlBlock=region.origin().offset(controlOut[0]/2,controlOut[1]/2,controlOut[2]/2);
                        ItemStack a=null,b=null;
                        if(FluidWorldTargetResolver.transferCell(region,treatedBlock,Direction.EAST,gasRequest,true)!=null) {
                            var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),treatedOut,Direction.EAST,true,quota);
                            if(mmm.item.GasContainerItem.contents(batch)!=null){a=batch;products.add(batch);}
                        }
                        if(FluidWorldTargetResolver.transferCell(region,controlBlock,Direction.EAST,gasRequest,true)!=null) {
                            var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),controlOut,Direction.EAST,true,quota);
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
            var adsorbent=world.thermalRegistry().surfaceMaterial("mmm:co2_adsorbent");
            var affinity=adsorbent.adsorbates().stream().filter(a->a.componentId().equals(BuiltinGasDefinitions.CO2)).findFirst().orElseThrow();
            double referencePartial=mmm.fluid.air.GasRegistry.STANDARD_PRESSURE_PA/2;
            double weight=affinity.affinityPerPa()*referencePartial;
            double referenceFraction=weight/(1+weight);
            double referenceTime=adsorbent.siteCapacityMolesPerKg()/(adsorbent.exchangeMolesPerSquareMeterSecond()*adsorbent.effectiveAreaFactor());
            System.out.println("adsorption_reference temperature_k="+affinity.referenceTemperatureK()+" partial_pa="+referencePartial
                    +" equilibrium_fraction="+referenceFraction+" total_capacity_mol="+capacity/ChemicalLimits.QUANTA_PER_MOL
                    +" relaxation_s_per_kg_per_m2="+referenceTime+" actual_quanta="+adsorbed);
            adsorptionSurfaceTargets(region,world,"feed");
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
                    recovered.add(fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),treatedOut,Direction.EAST,true,quota));available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Adsorbent regeneration step rejected");
            }
            motion.settlePendingPhaseWork(region);
            long remaining=region.finiteThermalDevices(world.thermalRegistryFingerprint()).adsorbedSpeciesQuanta().getOrDefault(BuiltinChemicalDefinitions.CO2,0L);
            adsorptionSurfaceTargets(region,world,"regenerated");
            if(remaining>=adsorbed)throw new AssertionError("Lower gas pressure did not release adsorbed CO2: "+adsorbed+" / "+remaining);
            long recoveredMass=0;for(var batch:recovered)recoveredMass=Math.addExact(recoveredMass,gasMass(batch));
            long regenerated=surfaceProcessMass(region,world)+gasMass(treated)+gasMass(control)+productMass+recoveredMass;
            if(regenerated!=Math.addExact(initial,supplied))throw new AssertionError("Regeneration changed gas and adsorbed mass");
            for(int step=0;step<600;step++) {
                progress("readsorption",step,600);
                motion.settlePendingPhaseWork(region);
                if(mmm.item.GasContainerItem.contents(treated)==null){treated=gasFeed(world);supplied=Math.addExact(supplied,gasMass(treated));}
                treated=fixture.transferBox(treated,treatedIn,Direction.WEST,false,IndustrialOperations.tickQuota(1_000_000_000L,step));
                available(region);
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Adsorbent reuse step rejected");
            }
            motion.settlePendingPhaseWork(region);
            long reused=region.finiteThermalDevices(world.thermalRegistryFingerprint()).adsorbedSpeciesQuanta().getOrDefault(BuiltinChemicalDefinitions.CO2,0L);
            adsorptionSurfaceTargets(region,world,"reused");
            if(reused<=remaining)throw new AssertionError("Regenerated adsorbent did not take up CO2 again: "+remaining+" / "+reused);
            long reusedMass=surfaceProcessMass(region,world)+gasMass(treated)+gasMass(control)+productMass+recoveredMass;
            if(reusedMass!=Math.addExact(initial,supplied))throw new AssertionError("Adsorbent reuse changed gas and adsorbed mass");
        }
    }
    private static void adsorptionSurfaceTargets(FluidRegion region,CommittedWorldRegistries world,String phase) {
        var host=region.hostSnapshot();var gas=host.captureAuthoritativeState().spatialGas();
        double actual=0,capacity=0;int surfaces=0;
        for(var device:region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices())for(var surface:device.surfaces()) {
            var material=world.thermalRegistry().surfaceMaterial(surface.materialId());
            if(material.adsorbates().isEmpty())continue;
            double temperature=mmm.thermal.SurfaceThermalState.temperature(world.thermalRegistry(),device);
            var direction=Direction.valueOf(surface.face().name());
            int axis=direction.getAxis()==Direction.Axis.X?0:direction.getAxis()==Direction.Axis.Y?1:2;
            int portDirection=axis*2+(direction.getAxisDirection()==Direction.AxisDirection.POSITIVE?0:1);
            double sites=Mass.kilograms(surface.loadingMassUnits())*material.siteCapacityMolesPerKg();
            int contacts=0;
            for(var port:WorldThermalEnvironment.ports(region,device,world.thermalRegistry())) {
                if(port.direction()!=portDirection)continue;
                int cell=region.grid().cell(port.cellX(),port.cellY(),port.cellZ());
                var inventory=gas.cells().get(cell);double weight=0;
                var weights=new TreeMap<String,Double>();var partials=new TreeMap<String,Double>();
                for(var adsorbate:material.adsorbates()) {
                    var component=inventory.component(adsorbate.componentId());
                    double partial=component==null || inventory.standardVolumeNanos()==0?0:host.pressure(port.cellX(),port.cellY(),port.cellZ())*component.standardVolumeNanos()/(double)inventory.standardVolumeNanos();
                    double affinity=adsorbate.affinityPerPa()*Math.exp(adsorbate.adsorptionHeatJPerMol()/8.31446261815324*(1/temperature-1/adsorbate.referenceTemperatureK()));
                    double value=affinity*partial;weight+=value;
                    weights.put(adsorbate.speciesId(),value);partials.put(adsorbate.speciesId(),partial);
                }
                for(var entry:weights.entrySet()) {
                    double amount=surface.speciesQuanta().getOrDefault(entry.getKey(),0L)/(double)ChemicalLimits.QUANTA_PER_MOL;
                    double target=sites*entry.getValue()/(1+weight);
                    System.out.println("adsorption_port phase="+phase+" device="+device.objectId()+" face="+surface.face()
                            +" cell="+port.cellX()+","+port.cellY()+","+port.cellZ()+" species="+entry.getKey()+" temperature_k="+temperature
                            +" partial_pa="+partials.get(entry.getKey())+" adsorbed_mol="+amount+" local_equilibrium_mol="+target+" capacity_mol="+sites);
                }
                contacts++;
            }
            if(contacts==0)continue;
            capacity+=sites;
            double amount=surface.speciesQuanta().values().stream().mapToLong(Long::longValue).sum()/(double)ChemicalLimits.QUANTA_PER_MOL;
            actual+=amount;
            surfaces++;
        }
        System.out.println("adsorption_inventory phase="+phase+" surfaces="+surfaces+" adsorbed_mol="+actual+" capacity_mol="+capacity);
        if(!(surfaces>0 && actual>=0 && actual<=capacity))throw new AssertionError("Adsorption surface inventory is outside its capacity");
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
        var definitions=ProductionRegistries.load();
        var reaction=definitions.chemical().reactions().stream().filter(value->value.id().equals("mmm:co2_hydration")).findFirst().orElseThrow();
        var kinetics=reaction.kinetics();
        var catalyst=definitions.thermal().surfaceMaterial("mmm:hydration_catalyst").catalysis().stream()
                .filter(value->value.reactionId().equals(reaction.id())).findFirst().orElseThrow();
        System.out.println("catalysis_reference temperature_k="+kinetics.referenceTemperatureK()+" forward="+kinetics.forwardRate()
                +" reverse="+kinetics.reverseRate()+" equilibrium_ratio="+(kinetics.forwardRate()/kinetics.reverseRate())
                +" multiplier_per_m2="+catalyst.multiplierPerSquareMeter());
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
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,scenario);
        var region=fixture.region;
        var layout=IndustrialLayout.at(new BlockPos(14,64,20),scenario);
        int[] treatedIn=industrialPortCell(region,layout.ports().get(0)),treatedOut=industrialPortCell(region,layout.ports().get(1));
        int[] controlIn=industrialPortCell(region,layout.ports().get(2)),controlOut=industrialPortCell(region,layout.ports().get(3));
        ItemStack treated=catalysisFeed(world),control=catalysisFeed(world);
        int earlyStep=-1;var products=new ArrayList<ItemStack>();
        long sampleVolume=ContainerFluidLedger.NANOS_PER_BLOCK;
        var treatedSamples=new ArrayList<ChemicalContents>();var controlSamples=new ArrayList<ChemicalContents>();long treatedVolume=0,controlVolume=0;
        long initial=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(treated,world)+chemicalMass(control,world),supplied=0;
        long catalyst=region.finiteThermalDevices(world.thermalRegistryFingerprint()).surfaceLoadingMassUnits();

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
                        var a=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),treatedOut,Direction.EAST,true,quota);
                        if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(a)) {
                            products.add(a);var contents=mmm.item.ContainerItemStacks.chemicalContents(a,world.chemicalRegistry());
                            treatedSamples.add(contents);treatedVolume=Math.addExact(treatedVolume,contents.totalVolumeNanos());
                        }
                    }
                    if(c!=null && MinecraftContainerTransferOperations.extractableNanos(host,c,quota,0,-1)>0) {
                        var b=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),controlOut,Direction.EAST,true,quota);
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
                        treated=fixture.transferBox(treated,treatedIn,Direction.WEST,false,quota);
                    if(FluidWorldTargetResolver.transferCell(region,inB,Direction.WEST,request,false)!=null)
                        control=fixture.transferBox(control,controlIn,Direction.WEST,false,quota);
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
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.CATALYSIS);
        var region=fixture.region;var layout=IndustrialLayout.at(new BlockPos(14,64,20),IndustrialLayout.Scenario.CATALYSIS);
        int[] treatedIn=industrialPortCell(region,layout.ports().get(0)),controlIn=industrialPortCell(region,layout.ports().get(2));
        ItemStack treated=catalysisFeed(world),control=catalysisFeed(world);
        long initial=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(treated,world)+chemicalMass(control,world);
        long catalyst=region.finiteThermalDevices(world.thermalRegistryFingerprint()).surfaceLoadingMassUnits();

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            for(int step=0;step<600 && (!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(treated)||!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(control));step++) {
                progress("catalysis_closed_fill",step,600);motion.settlePendingPhaseWork(region);
                long quota=IndustrialOperations.tickQuota(1_000_000_000L,step);
                if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(treated))treated=fixture.transferBox(treated,treatedIn,Direction.WEST,false,quota);
                if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(control))control=fixture.transferBox(control,controlIn,Direction.WEST,false,quota);
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
            hydrationReactionBalance(late,region,world,true);
            hydrationReactionBalance(late,region,world,false);
            if(difference>0.01)throw new AssertionError("Closed catalytic batches differ by more than one percentage point: "+difference);
            if(processMass(late)!=initial || region.finiteThermalDevices(world.thermalRegistryFingerprint()).surfaceLoadingMassUnits()!=catalyst)
                throw new AssertionError("Closed catalysis changed total mass or catalyst inventory");
        }
    }
    private static void hydrationReactionBalance(FluidDomainState state,FluidRegion region,CommittedWorldRegistries world,boolean treated) {
        var reaction=world.chemicalRegistry().reactions().stream().filter(value->value.id().equals("mmm:co2_hydration")).findFirst().orElseThrow();
        var kinetics=reaction.kinetics();var thermal=new ThermalStateFunction(world.thermalRegistry());
        int material=world.thermalRegistry().materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water");
        double forward=0,reverse=0,h=region.solverConfig().cellSize();
        for(var stock:state.liquid().physicalStocks()) {
            if((region.grid().z(stock.key().cell())<region.sizeZ()/2)!=treated || stock.mixture()==null || stock.massUnits()==0)continue;
            var amounts=stock.mixture().stableAmounts(world.chemicalRegistry());
            double volume=stock.volumeFraction()*h*h*h;
            double temperature=thermal.stateForInternalEnergy(material,stock.massKilograms(),amounts,stock.internalEnergy()).temperatureK();
            double f=kinetics.forwardRate()*Math.exp(-kinetics.forwardActivationJMol()/8.314462618*(1/temperature-1/kinetics.referenceTemperatureK()))*volume;
            double r=kinetics.reverseRate()*Math.exp(-kinetics.reverseActivationJMol()/8.314462618*(1/temperature-1/kinetics.referenceTemperatureK()))*volume;
            for(var term:reaction.reactants().entrySet())f*=Math.pow(amounts.getOrDefault(term.getKey(),0L)/(volume*ChemicalLimits.QUANTA_PER_MOL),term.getValue());
            for(var term:reaction.products().entrySet())r*=Math.pow(amounts.getOrDefault(term.getKey(),0L)/(volume*ChemicalLimits.QUANTA_PER_MOL),term.getValue());
            forward+=f;reverse+=r;
        }
        System.out.println("catalysis_reaction_balance treated="+treated+" forward_mol_s="+forward+" reverse_mol_s="+reverse
                +" quotient_over_equilibrium="+(forward>0?reverse/forward:Double.NaN));
        if(!(Double.isFinite(forward)&&Double.isFinite(reverse)&&forward>=0&&reverse>=0))throw new AssertionError("Invalid hydration reaction balance");
    }
    private record Hydration(long carbon,long bicarbonate) { double fraction(){return bicarbonate/(double)carbon;} }
    private static Hydration hydrationInventory(FluidDomainState state,FluidRegion region,boolean treated) {
        long co2=0,bicarbonate=0;
        for(var stock:state.liquid().physicalStocks()) {
            int z=region.grid().z(stock.key().cell());if((z<region.sizeZ()/2)!=treated || stock.mixture()==null)continue;
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
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.SCREENING);
        var region=fixture.region;
        var layout=IndustrialLayout.at(new BlockPos(14,64,20),IndustrialLayout.Scenario.SCREENING);
        int[] solidInput=industrialPortCell(region,layout.ports().get(2)),solidOutput=industrialPortCell(region,layout.ports().get(3));
        double pore=layout.screens().getFirst().poreDiameter();
        double fineDiameter=world.granularRegistry().material(world.granularRegistry().materialHandle(BuiltinGranularDefinitions.FINE_SILT)).primaryGrainDiameterMeters();
        double coarseDiameter=world.granularRegistry().material(world.granularRegistry().materialHandle(BuiltinGranularDefinitions.FINE_SAND)).primaryGrainDiameterMeters();
        if(!(fineDiameter<=pore && coarseDiameter>pore))throw new AssertionError("Screen feed no longer spans the pore size");
        System.out.println("screen_reference fine_m="+fineDiameter+" pore_m="+pore+" coarse_m="+coarseDiameter);
        var source=screenFeed(world);long initial=mmm.item.GranularContainerItem.unvalidatedContents(source).totalMassUnits();
        ItemStack remaining=source;boolean seeded=false;
        ItemStack water=saltedWater(world,293.15);

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);
            double beforeFlow=0,beforePressure=0;int[] screenFaces=region.fixedGeometry().screens().stream().mapToInt(ScreenFace::face).toArray();
            int[] cloggedFaces=null;
            int duration=Math.max(800,steps);
            for(int step=0;step<duration;step++) {
                progress("screening",step,duration);
                {
                    motion.settlePendingPhaseWork(region);
                    if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(water))water=saltedWater(world,293.15);
                    water=fixture.transferBox(water,fixture.portCell,Direction.WEST,false,IndustrialOperations.tickQuota(50_000_000L,step));available(region);
                }
                if(!seeded) {
                    var solidBlock=region.origin().offset(solidInput[0]/2,solidInput[1]/2,solidInput[2]/2);
                    var solidContact=FluidWorldTargetResolver.transferCell(region,solidBlock,Direction.NORTH,new mmm.api.transfer.TransferQuantity.Granular(262_144L),false);
                    if(solidContact!=null && region.hostSnapshot().volume(solidContact[0],solidContact[1],solidContact[2])>0) {
                        motion.settlePendingPhaseWork(region);
                        remaining=fixture.transferBox(source,solidInput,Direction.NORTH,false,262_144L);seeded=true;available(region);
                    }
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Screen process step rejected");
                if(step==duration-40) {
                    var deposits=region.hostSnapshot().exportGranularState().depositGroups().stream().map(deposit->deposit.cell()).collect(java.util.stream.Collectors.toSet());
                    cloggedFaces=java.util.Arrays.stream(screenFaces).filter(face->deposits.contains(region.grid().faceLeft(face)) || deposits.contains(region.grid().faceRight(face))).toArray();
                    if(cloggedFaces.length==0)throw new AssertionError("Screen has no deposited cake to clean");
                }
                if(step>=duration-40){beforeFlow+=Math.abs(region.observeLiquidFlow(cloggedFaces));beforePressure+=screenPressureDifference(region,cloggedFaces);}
            }
            motion.settlePendingPhaseWork(region);
            if(!seeded)throw new AssertionError("Liquid did not reach the screening feed port");
            var state=region.hostSnapshot().exportGranularState();
            for(var deposit:state.depositGroups())System.out.println("screen_deposit material="+state.materialPalette().get(deposit.materialPaletteIndex())
                    +" cell="+region.grid().x(deposit.cell())+","+region.grid().y(deposit.cell())+","+region.grid().z(deposit.cell())+" svq="+deposit.solidVolumeSvq());
            int plane=region.grid().faceCoordinate(screenFaces[0],1);
            long fine=downstreamSolid(state,region,BuiltinGranularDefinitions.FINE_SILT,plane);
            long coarse=downstreamSolid(state,region,BuiltinGranularDefinitions.FINE_SAND,plane);
            if(!(fine>0 && coarse==0))throw new AssertionError("Screen did not follow its particle-size cutoff: fine="+fine+" coarse="+coarse);
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
                    water=fixture.transferBox(water,fixture.portCell,Direction.WEST,false,IndustrialOperations.tickQuota(50_000_000L,step));available(region);
                }
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Cleaned screen process step rejected");
                afterFlow+=Math.abs(region.observeLiquidFlow(cloggedFaces));afterPressure+=screenPressureDifference(region,cloggedFaces);
            }
            if(!(beforePressure>0 && afterPressure>0 && afterFlow/afterPressure>beforeFlow/beforePressure))
                throw new AssertionError("Screen cleaning did not restore conductance: before="+beforeFlow+"/"+beforePressure+" after="+afterFlow+"/"+afterPressure);
            motion.settlePendingPhaseWork(region);
            var solution=fixture.transferBox(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET),industrialPortCell(region,layout.ports().get(1)),Direction.EAST,true,50_000_000L);
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
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
        var fixture=GpuControllerFixture.createIndustrial(startup,world,manager,operations,IndustrialLayout.Scenario.SCREENING_CRYSTALLIZATION);
        var region=fixture.region;var layout=IndustrialLayout.at(new BlockPos(14,64,20),IndustrialLayout.Scenario.SCREENING_CRYSTALLIZATION);
        var wall=layout.walls().getFirst();
        int[] solidInput=industrialPortCell(region,layout.ports().get(2)),solidOutput=solidInput;
        ItemStack feed=coolableSaltWater(world,310,wall.temperatureK(),2_000_000L),seed=saltSeed(world,2);
        var reaction=world.granularRegistry().reactions().stream().filter(r->r.solidMaterialId().equals(BuiltinGranularDefinitions.PRECIPITATE)).findFirst().orElseThrow();
        var solubility=reaction.solubility();
        double hotRatio=solubility.ratio()+solubility.slopePerK()*(310-solubility.referenceTemperatureK());
        double coldRatio=solubility.ratio()+solubility.slopePerK()*(wall.temperatureK()-solubility.referenceTemperatureK());
        long water=Math.round(waterQuanta(world)*0.002);
        long salt=Math.round(water*(hotRatio-(hotRatio-coldRatio)*0.02));
        long coefficient=reaction.aqueousStoichiometry().values().iterator().next();
        long maximumGrowth=(salt/(coefficient*reaction.solidExtentPerBatch()))*reaction.solidVolumeSvqPerBatch();
        double coldEquilibriumGrowth=Math.max(0,salt-water*coldRatio)/(coefficient*reaction.solidExtentPerBatch())*reaction.solidVolumeSvqPerBatch();
        System.out.println("crystallization_reference hot_k=310.0 cold_k="+wall.temperatureK()+" hot_salt_per_water="+hotRatio+" cold_salt_per_water="+coldRatio
                +" cold_equilibrium_growth_svq="+coldEquilibriumGrowth+" available_salt_limit_svq="+maximumGrowth);
        long seedMass=mmm.item.GranularContainerItem.unvalidatedContents(seed).totalMassUnits();
        long initial=processMass(region.hostSnapshot().captureAuthoritativeState())+chemicalMass(feed,world)+seedMass,supplied=0,seedVolume=0;
        boolean seeded=false,grown=false;double bulkTemperature=310;
        String wallPrefix=wall.position().asLong()+"@";
        var coldWall=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream().filter(device->device.objectId().startsWith(wallPrefix)).findFirst().orElseThrow();
        double wallEnergy=coldWall.body().internalEnergyJ();
        long coolingBudget=Math.max(4000,steps);

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
            long grownVolume=region.hostSnapshot().exportGranularState().totalSolidVolumeSvq()-seedVolume;
            if(!seeded || grownVolume<=0 || grownVolume>maximumGrowth)throw new AssertionError("Cold crystal growth exceeds available salt or did not begin: growth="+grownVolume+" limit="+maximumGrowth);
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
            var generation=region.solverGeneration();var candidate=region.beginHostEdit();var edits=new ArrayList<FluidDomainContracts.CollisionEdit>();
            for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
                int cx=(valve.getX()-region.origin().getX())*2+x,cy=(valve.getY()-region.origin().getY())*2+y,cz=(valve.getZ()-region.origin().getZ())*2+z;
                int mask=FluidRegionWorldInteraction.sampleCutCellMask(mmm.block.ValveBlock.shutter(open),x,y,z,2,1e-7);
                if(candidate.solidMicroMask(cx,cy,cz)!=mask)edits.add(new FluidDomainContracts.CollisionEdit(cx,cy,cz,mask));
            }
            if(edits.isEmpty())throw new AssertionError("Opening the valve did not change its collision geometry");
            candidate.applyCollisionEdits(edits);region.adoptCollisionCandidate(generation,candidate);available(region);
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

    private record Distillate(ItemStack product,double ethanolFraction,double bottomsFraction,double seconds,double heatIn) { }
    private static final class DistillationInventory {
        final Map<String,Long> species=new TreeMap<>();
        double energy;
        void addSpecies(String id,long amount){if(amount!=0)species.merge(id,amount,Math::addExact);}
        void addLiquid(ItemStack stack,CommittedWorldRegistries world){
            if(mmm.item.MaterialBucketItem.isEmptyMaterialBucket(stack))return;
            var contents=mmm.item.ContainerItemStacks.chemicalContents(stack,world.chemicalRegistry());
            for(var group:contents.groups())for(var item:group.species())addSpecies(item.speciesId(),item.amountQuanta());
            var thermal=mmm.item.ContainerItemStacks.thermalContents(stack);
            if(thermal==null)throw new AssertionError("Distillation liquid batch has no thermal inventory");
            energy+=thermal.internalEnergy();
        }
        void addGas(mmm.transfer.GasContents gas){
            for(var component:gas.inventory().components())for(var item:component.species())addSpecies(item.speciesId(),item.amountQuanta());
            energy+=gas.internalEnergy();
        }
        void addRegion(FluidDomainState state){
            for(var stock:state.liquid().physicalStocks())if(stock.mixture()!=null)
                stock.mixture().stableAmounts(state.chemicalRegistry()).forEach(this::addSpecies);
            for(var gas:state.spatialGas().cells())for(var component:gas.components())for(var item:component.species())addSpecies(item.speciesId(),item.amountQuanta());
            for(var group:state.thermalOwner().coherentGroups())if(group.composition()!=null)
                group.composition().stableAmounts().forEach(this::addSpecies);
        }
        Map<String,Long> elements(ChemicalRegistry registry){
            var result=new TreeMap<String,Long>();
            species.forEach((id,amount)->{
                var definition=registry.species(registry.speciesHandle(id));
                definition.composition().forEach((element,count)->result.merge(element,Math.multiplyExact(amount,count.longValue()),Math::addExact));
                result.merge("@charge",Math.multiplyExact(amount,(long)definition.charge()),Math::addExact);
            });
            result.values().removeIf(value->value==0);return result;
        }
    }
    private static final class TransferEnergyTotals {
        mmm.units.SignedDyadic source=mmm.units.SignedDyadic.ZERO,sink=mmm.units.SignedDyadic.ZERO;
        void add(mmm.thermal.ThermalAuditLedger before,mmm.thermal.ThermalAuditLedger after){
            source=source.add(after.externalSource().subtract(before.externalSource()));
            sink=sink.add(after.externalSink().subtract(before.externalSink()));
        }
    }
    private static void requireEnergyTotal(double actual,double expected,String description){
        double scale=Math.max(Math.abs(actual),Math.abs(expected));
        if(!Double.isFinite(actual)||!Double.isFinite(expected)||Math.abs(actual-expected)>Math.max(1e-9,1e-10*scale))
            throw new AssertionError(description+": actual="+actual+" expected="+expected);
    }
    private static void industrialDistillation(int steps) throws Exception {
        var first=distillationRun(IndustrialLayout.Scenario.DISTILLATION_SINGLE,Math.max(2400,steps),false,null);
        var second=distillationRun(IndustrialLayout.Scenario.DISTILLATION,Math.max(2400,steps),false,first.product());
        System.out.println("distillation_cascade first_x="+first.ethanolFraction()+" second_x="+second.ethanolFraction()
                +" first_nanos="+Math.max(0,mmm.item.MaterialBucketItem.volume(first.product()))+" second_nanos="+Math.max(0,mmm.item.MaterialBucketItem.volume(second.product()))
                +" seconds="+(first.seconds()+second.seconds())+" heat_in_J="+(first.heatIn()+second.heatIn()));
        if(second.ethanolFraction()<first.ethanolFraction()+.10)
            throw new AssertionError("Second distillation needs a visible enrichment: "+first.ethanolFraction()+" -> "+second.ethanolFraction());
    }
    private static void industrialDistillationSingle(int steps) throws Exception {
        var cold=distillationRun(IndustrialLayout.Scenario.DISTILLATION_SINGLE,Math.max(2400,steps),false,null);
        var warm=distillationRun(IndustrialLayout.Scenario.DISTILLATION_WARM,(int)Math.round(cold.seconds()*20),true,null);
        if(Math.max(0,mmm.item.MaterialBucketItem.volume(warm.product()))>=Math.max(0,mmm.item.MaterialBucketItem.volume(cold.product())))
            throw new AssertionError("Warm condenser must collect less liquid");
    }
    private static Distillate distillationRun(IndustrialLayout.Scenario scenario,int maximumTicks,boolean diagnostic,ItemStack suppliedFeed) throws Exception {
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-distillation-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);
        try(var backend=MmmRuntimeFactory.createSolverBackend()) {
            MmmRuntimeFactory.prepareSolverBackend(backend,startup,world);manager.solverBackend(backend);
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
                        .mapToDouble(IndustrialLayout.Wall::temperatureK).findFirst().orElse(Double.NaN));
        ItemStack feed=suppliedFeed==null?ethanolSolution(world,370,.10):suppliedFeed.copy();
        ItemStack product=new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET);
        var feedContents=mmm.item.ContainerItemStacks.chemicalContents(feed,world.chemicalRegistry());
        long feedVolume=feedContents.totalVolumeNanos(),targetVolume=feedVolume/5;
        double feedFraction=liquidEthanolFraction(feedContents);

        var productPort=layout.ports().get(1);int[] productCell=industrialPortCell(region,productPort);
        var initialState=region.hostSnapshot().captureAuthoritativeState();
        long initialMass=processMass(initialState)+chemicalMass(feed,world),ventedMass=0,ventedVolume=0;
        var inputs=new DistillationInventory();inputs.addRegion(initialState);inputs.addLiquid(feed,world);
        var outputs=new DistillationInventory();var transfers=new TransferEnergyTotals();
        var initialAudit=initialState.thermalOwner().audit();
        double initialWallEnergy=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream().mapToDouble(value->value.body().internalEnergyJ()).sum();
        int tick=0;

            var app=new mmm.application.RegionApplicationService(backend,new FluidRegionRepository(manager),operations);
            var motion=new MinecraftMotionExecutor(app,world,new MinecraftRuntimePolicy(startup,manager));
            region.setPaused(false);available(region);region.replaceThermalBoundaryTemperatures(temperatures);
            while(tick<maximumTicks && (diagnostic || Math.max(0,mmm.item.MaterialBucketItem.volume(product))<targetVolume)) {
                motion.settlePendingPhaseWork(region);
                if(tick%4==0)for(var sensor:sensors)IndustrialOperations.observeSensor(region,sensor,sensor.first(),sensor.second());
                fixture.beginTransfers();var before=fixture.transferState().thermalAudit();
                for(var vent:layout.ports())if(vent.phase()==mmm.block.FluidPortBlockEntity.Phase.GAS && industrialPortPowered(vent,sensors)) {
                    var batch=fixture.transferBox(new ItemStack(mmm.item.MmmItems.GAS_CONTAINER),industrialPortCell(region,vent),vent.outward(),true,IndustrialOperations.tickQuota(vent.rate(),tick));
                    var contents=mmm.item.GasContainerItem.contents(batch);
                    if(contents!=null){outputs.addGas(contents);ventedMass=Math.addExact(ventedMass,contents.inventory().totalMassUnits());ventedVolume+=contents.inventory().standardVolumeNanos();}
                }
                var inlet=layout.ports().getFirst();
                if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(feed))
                    feed=fixture.transferBox(feed,fixture.portCell,inlet.outward(),false,IndustrialOperations.tickQuota(inlet.rate(),tick));
                if(industrialPortPowered(productPort,sensors)) {
                    long quota=IndustrialOperations.tickQuota(productPort.rate(),tick);
                    product=fixture.transferBox(product,productCell,productPort.outward(),true,quota);
                }
                transfers.add(before,fixture.transferState().thermalAudit());fixture.commitTransfers();available(region);
                if(!motion.step(region,List.of()).accepted())throw new AssertionError("Distillation physical step rejected");
                tick++;
                if(tick%100==0) {
                    var state=region.hostSnapshot().captureAuthoritativeState();
                    long e=0,w=0;
                    if(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(product)) {
                        var contents=mmm.item.ContainerItemStacks.chemicalContents(product,world.chemicalRegistry());
                        e=chemicalAmount(contents,BuiltinChemicalDefinitions.ETHANOL);w=chemicalAmount(contents,BuiltinChemicalDefinitions.H2O);
                    }
                    requireDistillationCondensate(state.spatialGas(),e,w);
                    if(diagnostic) {
                        for(var port:List.of(layout.ports().getFirst(),productPort)) {
                            var block=port.position().relative(port.outward().getOpposite());double mass=0,temperature=0,volume=0;long vapor=0,gasTotal=0;
                            var host=region.hostSnapshot();
                            for(int dx=0;dx<2;dx++)for(int dy=0;dy<2;dy++)for(int dz=0;dz<2;dz++) {
                                var liquid=host.observeCellInventory(2*(block.getX()-region.origin().getX())+dx,2*(block.getY()-region.origin().getY())+dy,2*(block.getZ()-region.origin().getZ())+dz);
                                int cell=region.grid().cell(2*(block.getX()-region.origin().getX())+dx,2*(block.getY()-region.origin().getY())+dy,2*(block.getZ()-region.origin().getZ())+dz);
                                var gas=state.spatialGas().cells().get(cell);gasTotal+=gas.standardVolumeNanos();
                                for(var component:gas.components())if(component.componentId().equals(BuiltinGasDefinitions.ETHANOL_VAPOR)||component.componentId().equals(BuiltinGasDefinitions.WATER_VAPOR))vapor+=component.standardVolumeNanos();
                                if(liquid==null)continue;
                                mass+=liquid.liquidMassKilograms();volume+=liquid.liquidVolumeCubicMetres();
                                if(liquid.temperatureKelvin().isPresent())temperature+=liquid.temperatureKelvin().getAsDouble()*liquid.liquidMassKilograms();
                            }
                            System.out.println("distillation_liquid port="+port.mode()+" volume_m3="+volume+" mass_kg="+mass+" temperature_K="+(mass>0?temperature/mass:0)+" vapor_nanos="+vapor+" gas_nanos="+gasTotal);
                        }
                        for(var sensor:sensors)System.out.println("distillation_control measure="+sensor.measure()+" signal="+sensor.output());
                        System.out.println("distillation_condensed ethanol="+gasPhaseSinkQuanta(state.spatialGas(),BuiltinGasDefinitions.ETHANOL_VAPOR,BuiltinChemicalDefinitions.ETHANOL)
                                +" water="+gasPhaseSinkQuanta(state.spatialGas(),BuiltinGasDefinitions.WATER_VAPOR,BuiltinChemicalDefinitions.H2O));
                    }
                    System.out.println("distillation_progress scenario="+scenario+" seconds="+(tick/20.0)+" product_nanos="+Math.max(0,mmm.item.MaterialBucketItem.volume(product))
                            +" target_nanos="+targetVolume+" product_x="+(e+w>0?e/(double)(e+w):0)
                            +" heat_in_J="+state.thermalOwner().audit().externalSource().subtract(initialAudit.externalSource()).subtract(transfers.source).toDouble());
                }
            }
            motion.settlePendingPhaseWork(region);
            var finalState=region.hostSnapshot().captureAuthoritativeState();
            long productMass=chemicalMass(product,world),productVolume=Math.max(0,mmm.item.MaterialBucketItem.volume(product));
            long after=processMass(finalState)+chemicalMass(feed,world)+productMass+ventedMass;
            if(after!=initialMass)throw new AssertionError("Distillation changed combined mass");
            var remaining=new DistillationInventory();remaining.addLiquid(feed,world);
            outputs.addLiquid(product,world);outputs.addRegion(finalState);remaining.species.forEach(outputs::addSpecies);
            if(!inputs.elements(world.chemicalRegistry()).equals(outputs.elements(world.chemicalRegistry())))
                throw new AssertionError("Distillation changed combined elements");
            if(!Objects.equals(inputs.species.getOrDefault(BuiltinChemicalDefinitions.ETHANOL,0L),outputs.species.getOrDefault(BuiltinChemicalDefinitions.ETHANOL,0L)))
                throw new AssertionError("Distillation changed combined ethanol inventory");
            var host=region.hostSnapshot();
            if(!host.thermalAudit().acceptsInventory(host.exactInternalEnergy()))throw new AssertionError("Distillation fluid energy audit did not close");
            requireEnergyTotal(transfers.source.toDouble(),inputs.energy-remaining.energy,"Distillation incoming batch energy");
            requireEnergyTotal(transfers.sink.toDouble(),outputs.energy,"Distillation outgoing batch energy");
            double wallEnergy=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream().mapToDouble(value->value.body().internalEnergyJ()).sum();
            double deviceExchange=finalState.thermalOwner().audit().finiteDeviceExchange().subtract(initialAudit.finiteDeviceExchange()).toDouble();
            requireEnergyTotal(wallEnergy+deviceExchange,initialWallEnergy,"Distillation finite wall energy");
            long ethanol=0,water=0,bottomEthanol=0,bottomWater=0;
            if(productVolume>0) {
                var contents=mmm.item.ContainerItemStacks.chemicalContents(product,world.chemicalRegistry());
                ethanol=chemicalAmount(contents,BuiltinChemicalDefinitions.ETHANOL);water=chemicalAmount(contents,BuiltinChemicalDefinitions.H2O);
            }
            for(var stock:finalState.liquid().physicalStocks())if(stock.mixture()!=null) {
                var amounts=stock.mixture().stableAmounts(world.chemicalRegistry());
                bottomEthanol+=amounts.getOrDefault(BuiltinChemicalDefinitions.ETHANOL,0L);bottomWater+=amounts.getOrDefault(BuiltinChemicalDefinitions.H2O,0L);
            }
            double fraction=ethanol+water>0?ethanol/(double)(ethanol+water):0;
            double bottomFraction=bottomEthanol+bottomWater>0?bottomEthanol/(double)(bottomEthanol+bottomWater):0;
            double heatIn=finalState.thermalOwner().audit().externalSource().subtract(initialAudit.externalSource()).subtract(transfers.source).toDouble();
            requireDistillationCondensate(finalState.spatialGas(),ethanol,water);
            double idealFraction=batchDistillationReference(world,feedContents,productVolume/(double)feedVolume);
            System.out.println("distillation_batch scenario="+scenario+" seconds="+(tick/20.0)+" feed_nanos="+feedVolume+" feed_x="+feedFraction
                    +" product_nanos="+productVolume+" product_x="+fraction+" ideal_batch_x="+idealFraction+" bottoms_x="+bottomFraction
                    +" volume_recovery="+(productVolume/(double)feedVolume)+" heat_in_J="+heatIn+" wall_to_fluid_J="+deviceExchange+" vented_standard_m3="+(ventedVolume*1e-9));
            if(!diagnostic && (productVolume<targetVolume || fraction<feedFraction+.10 || bottomFraction>=feedFraction))
                throw new AssertionError("Distillation did not meet finite-batch yield and enrichment within "+(maximumTicks/20.0)+" s");
            return new Distillate(product,fraction,bottomFraction,tick/20.0,heatIn);
        }
    }
    private static double liquidEthanolFraction(ChemicalContents contents) {
        long e=chemicalAmount(contents,BuiltinChemicalDefinitions.ETHANOL),w=chemicalAmount(contents,BuiltinChemicalDefinitions.H2O);
        return e/(double)(e+w);
    }
    private static double batchDistillationReference(CommittedWorldRegistries world,ChemicalContents feed,double volumeRecovery) {
        var thermal=world.thermalRegistry();var model=new GasLiquidPhaseModel(thermal,world.gasRegistry());
        var wt=thermal.transitions().stream().filter(t->BuiltinChemicalDefinitions.H2O.equals(t.speciesId())&&t.interfaceRequirement()==ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS).findFirst().orElseThrow();
        var et=thermal.transitions().stream().filter(t->BuiltinChemicalDefinitions.ETHANOL.equals(t.speciesId())&&t.interfaceRequirement()==ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS).findFirst().orElseThrow();
        double ev=world.chemicalRegistry().species(world.chemicalRegistry().speciesHandle(BuiltinChemicalDefinitions.ETHANOL)).effectiveVolumeM3PerMol();
        double wv=world.chemicalRegistry().species(world.chemicalRegistry().speciesHandle(BuiltinChemicalDefinitions.H2O)).effectiveVolumeM3PerMol();
        double e=chemicalAmount(feed,BuiltinChemicalDefinitions.ETHANOL)/1e6,w=chemicalAmount(feed,BuiltinChemicalDefinitions.H2O)/1e6,de=0,dw=0;
        double dv=feed.totalVolumeNanos()*1e-9*volumeRecovery/512;
        for(int sample=0;sample<512;sample++) {
            double x=e/(e+w),low=250,high=450;
            for(int iteration=0;iteration<40;iteration++) {
                double t=(low+high)/2;
                double pe=model.saturation(et,t,mmm.fluid.air.GasInventory.EMPTY,0).saturationPressurePa();
                double pw=model.saturation(wt,t,mmm.fluid.air.GasInventory.EMPTY,0).saturationPressurePa();
                if(x*pe+(1-x)*pw>101325)high=t;else low=t;
            }
            double t=(low+high)/2,pe=model.saturation(et,t,mmm.fluid.air.GasInventory.EMPTY,0).saturationPressurePa();
            double pw=model.saturation(wt,t,mmm.fluid.air.GasInventory.EMPTY,0).saturationPressurePa();
            double y=x*pe/(x*pe+(1-x)*pw),n=dv/(y*ev+(1-y)*wv);
            e-=n*y;w-=n*(1-y);de+=n*y;dw+=n*(1-y);
        }
        return de/(de+dw);
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
    private static int[] industrialPortCell(FluidRegion region,IndustrialLayout.Port port) {
        var interior=port.position().relative(port.outward().getOpposite());
        return new int[]{2*(interior.getX()-region.origin().getX())+Math.max(0,port.outward().getStepX()),
                2*(interior.getY()-region.origin().getY())+Math.max(0,port.outward().getStepY()),
                2*(interior.getZ()-region.origin().getZ())+Math.max(0,port.outward().getStepZ())};
    }
    private static void requireDistillationCondensate(mmm.fluid.air.SpatialGasState gas,long ethanol,long water) {
        long condensedEthanol=gasPhaseSinkQuanta(gas,BuiltinGasDefinitions.ETHANOL_VAPOR,BuiltinChemicalDefinitions.ETHANOL);
        long condensedWater=gasPhaseSinkQuanta(gas,BuiltinGasDefinitions.WATER_VAPOR,BuiltinChemicalDefinitions.H2O);
        if(ethanol>condensedEthanol || water>condensedWater)
            throw new AssertionError("Distillate exceeds condensed species: ethanol="+ethanol+"/"+condensedEthanol+" water="+water+"/"+condensedWater);
    }
    private static int[] coldWallSample(FluidRegion region,CommittedWorldRegistries world,String object){
        var wall=region.finiteThermalDevices(world.thermalRegistryFingerprint()).devices().stream()
                .filter(value->value.objectId().equals(object)).findFirst().orElseThrow();
        var state=region.hostSnapshot().captureAuthoritativeState();int[] selected={11,4,1};
        if(WorldThermalEnvironment.ports(region,wall,world.thermalRegistry()).stream().noneMatch(port->
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
                .filter(p->region.footprint().interiorBlocks().contains(p.asLong())).toList()
                :region.sizeX()>4?List.of(region.origin().offset(5,1,0)):
                region.footprint().interiorBlocks().stream().map(BlockPos::of)
                .sorted(Comparator.comparingDouble((BlockPos p)->p.distSqr(inside)).reversed()).toList();
        FiniteThermalDeviceState selected=null;
        for(var pos:positions){
            var candidate=new FiniteThermalDeviceState(WorldThermalEnvironment.PROVIDER,pos.asLong()+(excluded==null?"@minecraft:stone":"@minecraft:iron_block"),def.id(),body);
            if(!candidate.objectId().equals(excluded)&&!WorldThermalEnvironment.ports(region,candidate,registry).isEmpty()){selected=candidate;break;}
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
        var wall=new FiniteThermalDeviceState(WorldThermalEnvironment.PROVIDER,pos.asLong()+(stone?"@minecraft:stone":"@minecraft:iron_block"),def.id(),body);
        if(WorldThermalEnvironment.ports(region,wall,registry).isEmpty())throw new IllegalStateException("Cooling wall has no gas contact");
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
