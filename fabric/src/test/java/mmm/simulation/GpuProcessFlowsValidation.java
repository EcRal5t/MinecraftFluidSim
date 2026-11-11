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
import static mmm.simulation.ProcessFlowSupport.*;
import static mmm.simulation.IndustrialPortFlows.*;
import static mmm.simulation.CounterflowFlows.*;
import static mmm.simulation.SurfaceProcessFlows.*;
import static mmm.simulation.ScreeningFlows.*;
import static mmm.simulation.DistillationFlows.*;

/**
 * Controller processes using actual containers, finite carriers, solver and checkpoint paths.
 * {@code -PgpuProcessScenario} selects a scenario; industrial apparatus runs live in the per-family classes.
 */
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
        if(scenario.equals("evaporation")){
            System.out.println("process_start scenario=evaporation");
            industrialDistillationSingle(steps);
            System.out.println("process_complete scenario=evaporation");
            return;
        }
        if(!Set.of("heat","absorption","precipitation","carbonation","drive").contains(scenario)||steps<2)throw new IllegalArgumentException("Expected heat, absorption, precipitation, evaporation, carbonation, drive or all and at least two steps");
        var definitions=ProductionRegistries.load();
        var startup=WorldStartupCoordinator.start(Files.createTempDirectory("mmm-process-flow-"),()->new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                EnumSet.allOf(WorldCapability.class),definitions.material(),definitions.chemical(),definitions.granular(),definitions.gas(),definitions.thermal()));
        if(startup.creationLocked())throw new IllegalStateException(startup.blockingSummary());
        var world=new CommittedWorldRegistries(startup);var manager=new FluidRegionManager(startup.config().limits());
        var operations=new FluidRegionOperations(startup.worldRoot(),manager,startup);var fixture=GpuControllerFixture.createCrossBlockProcess(startup,world,manager,operations);
        var region=fixture.region;String wallObject=null;double wallInitial=0;
        if(scenario.equals("heat")){
            var wall=finiteWall(region,fixture.portCell,world.thermalRegistry(),350);
            var installed=new ArrayList<FiniteThermalDeviceState>();installed.add(wall);
            wallObject=wall.objectId();wallInitial=wall.body().internalEnergyJ();
            installFiniteWalls(region,world.thermalRegistry(),installed);
        }
        fixture.transfer(source(scenario,world));
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
            int motionSteps=scenario.equals("drive")?Math.max(40,steps):steps;
            long[] motionNanos=new long[motionSteps+(scenario.equals("absorption")?steps:0)];int timedMotions=0;
            if(scenario.equals("drive")){
                var stock=region.hostSnapshot().exportLiquidCellState().physicalStocks().stream().findFirst().orElseThrow();
                int c=stock.key().cell(),x=region.grid().x(c),y=region.grid().y(c),z=region.grid().z(c);
                drive=new MinecraftProcessOperations(startup.worldRoot(),manager,null,startup);
                drive.setDrive(region.id(),new MinecraftProcessOperations.Drive(region.tick(),region.tick()+motionSteps,x,y,z,region.solverConfig().cellSize(),0,null));
                region.wakeForActivity();
            }
            for(int step=0;step<motionSteps;step++){
                progress(scenario,step,motionSteps);
                if(step==motionSteps/3){
                    motion.settlePendingPhaseWork(region);
                    long carbonBeforeFeed=scenario.equals("absorption")?liquidInorganicCarbon(region.hostSnapshot().captureAuthoritativeState()):0;
                    fixture.transfer(source(scenario,world));available(region);
                    if(scenario.equals("absorption"))feedCarbon=Math.addExact(feedCarbon,liquidInorganicCarbon(region.hostSnapshot().captureAuthoritativeState())-carbonBeforeFeed);
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
            if(scenario.equals("heat")){
                var walls=region.finiteThermalDevices(world.thermalRegistryFingerprint());
                String selectedWall=wallObject;
                var body=walls.devices().stream().filter(value->value.objectId().equals(selectedWall)).findFirst().orElseThrow();
                if(!(body.body().internalEnergyJ()<wallInitial))throw new AssertionError("Finite wall did not heat its fluid: "+selectedWall+" initial="+wallInitial+" after="+body.body().internalEnergyJ());
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
            motion.settlePendingPhaseWork(region);long prior=region.hostSnapshot().totalMassUnits();
            if(scenario.equals("heat"))fixture.scoop();
            else if(scenario.equals("carbonation"))fixture.scoopMixtureAt(new int[]{x,y,z});
            else fixture.scoopMixture();
            if(region.hostSnapshot().totalMassUnits()>=prior)throw new AssertionError("Process output container did not receive liquid");
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
            fixture.restoreThermal();
            region.ensureDeviceResident();region.setPaused(false);available(region);
            if(!motion.step(region,List.of()).accepted())throw new AssertionError("Restored process did not continue");
            System.out.println("process_complete scenario="+scenario+" checkpoint="+saved+" mass="+savedMass);
        }
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

    static ItemStack source(String scenario,CommittedWorldRegistries world){
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

    private static FiniteThermalDeviceState finiteWall(FluidRegion region,int[] port,ThermalRegistry registry,double temperature){
        int n=(int)Math.round(1.0/region.solverConfig().cellSize());
        var inside=region.origin().offset(port[0]/n,port[1]/n,port[2]/n);
        var def=registry.finiteDevice("mmm:ordinary_stone_wall");double energy=new ThermalStateFunction(registry).internalEnergyAtTemperature(registry.materialHandle(def.thermalMaterialId()),def.bodyMassKilograms(),Map.of(),temperature);
        var body=new FiniteThermalBodyState(0,registry.fingerprint(),def.thermalMaterialId(),def.bodyMassUnits(),energy);
        var positions=Arrays.stream(Direction.values()).map(inside::relative)
                .filter(p->region.footprint().interiorBlocks().contains(p.asLong())).toList();
        FiniteThermalDeviceState selected=null;
        for(var pos:positions){
            var candidate=new FiniteThermalDeviceState(WorldThermalEnvironment.PROVIDER,pos.asLong()+"@minecraft:stone",def.id(),body);
            if(!WorldThermalEnvironment.ports(region,candidate,registry).isEmpty()){selected=candidate;break;}
        }
        if(selected==null)throw new IllegalStateException("No exposed finite wall");
        return selected;
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
}
