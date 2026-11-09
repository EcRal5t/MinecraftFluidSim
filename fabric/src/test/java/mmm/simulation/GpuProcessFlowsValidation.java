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
        if(scenario.equals("all")||scenario.contains(",")){
            var failures=new ArrayList<Throwable>();
            var selected=scenario.equals("all")?List.of("heat","absorption","precipitation","evaporation","carbonation","drive"):List.of(scenario.split(","));
            for(String flow:selected)try {
                main(new String[]{flow,Integer.toString(steps)});
            } catch(Exception|AssertionError failure) {
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
            if(scenario.equals("evaporation"))System.out.println("distillation_duration steps="+motionSteps+" seconds="+(motionSteps*region.solverConfig().timeStep()));
            if(scenario.equals("drive")){
                var stock=region.hostSnapshot().exportLiquidCellState().physicalStocks().stream().findFirst().orElseThrow();
                int c=stock.key().cell(),x=region.grid().x(c),y=region.grid().y(c),z=region.grid().z(c);
                drive=new MinecraftProcessOperations(startup.worldRoot(),manager,null,null,startup);
                drive.setDrive(region.id(),new MinecraftProcessOperations.Drive(region.tick(),region.tick()+motionSteps,x,y,z,region.solverConfig().cellSize(),0,null));
                region.wakeForActivity();
            }
            for(int step=0;step<motionSteps;step++){
                if(scenario.equals("evaporation")&&step<300&&step%20==0){
                    motion.settlePendingPhaseWork(region);
                    fixture.transfer(ethanolSolution(world,380.0,.05));available(region);
                }else if(!scenario.equals("evaporation")&&step==motionSteps/3){
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
                long condensedEthanol=gasPhaseSinkQuanta(gasBeforeOutput,BuiltinGasDefinitions.ETHANOL_VAPOR,BuiltinChemicalDefinitions.ETHANOL);
                long condensedWater=gasPhaseSinkQuanta(gasBeforeOutput,BuiltinGasDefinitions.WATER_VAPOR,BuiltinChemicalDefinitions.H2O);
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
            var amounts=Map.of(BuiltinChemicalDefinitions.H2O,55_555_555_556L,BuiltinChemicalDefinitions.CO2,1_000_000L);
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
        var amounts=Map.of(BuiltinChemicalDefinitions.H2O,Math.round(27_777_777_777L*volume),BuiltinChemicalDefinitions.ETHANOL,Math.round(8_576_086_956L*volume));
        long mass=Mass.fromKilograms(894.5*volume);var chemistry=world.chemicalRegistry();
        var mixture=ChemicalMixtureRecord.fromStableAmounts(chemistry,amounts,0);
        var ledger=new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group("mmm:water",1.0,mass,mass-mixture.speciesMassUnits(chemistry),amounts)));
        var item=mmm.item.ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger,chemistry,Math.round(ContainerFluidLedger.NANOS_PER_BLOCK*volume)));
        world.bindActualContainerProfile(item);world.attachLiquidThermalContents(item,temperature);return item;
    }
    private static ItemStack saltSeed(CommittedWorldRegistries world){
        long batches=1000,quanta=281L*64*batches,svq=50*batches;
        var amounts=Map.of(BuiltinChemicalDefinitions.NA_PLUS,quanta,BuiltinChemicalDefinitions.CL_MINUS,quanta);
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
        var def=registry.finiteDevice("mmm:ordinary_metal_wall");
        double energy=new ThermalStateFunction(registry).internalEnergyAtTemperature(registry.materialHandle(def.thermalMaterialId()),def.bodyMassKilograms(),Map.of(),temperature);
        var body=new FiniteThermalBodyState(0,registry.fingerprint(),def.thermalMaterialId(),def.bodyMassUnits(),energy);
        var wall=new FiniteThermalDeviceState(OrdinaryThermalWalls.PROVIDER,pos.asLong()+"@minecraft:iron_block",def.id(),body);
        if(OrdinaryThermalWalls.ports(region,wall,registry).isEmpty())throw new IllegalStateException("Cooling wall has no gas contact");
        return wall;
    }
    private static void installFiniteWalls(FluidRegion region,ThermalRegistry registry,List<FiniteThermalDeviceState> devices){
        var inventory=FiniteThermalDeviceInventory.empty(registry.fingerprint());
        for(var device:devices)inventory=inventory.insertExternal(device);
        region.restoreFiniteThermalDevices(inventory,registry,region.checkpointGeneration());
        var attached=new ArrayList<FluidStateAccess.DevicePort>();var connections=new ArrayList<int[]>();
        for(var device:inventory.devices()){
            var ports=OrdinaryThermalWalls.ports(region,device,registry);
            attached.addAll(ports.stream().map(p->new FluidStateAccess.DevicePort(device.providerId(),device.objectId(),p)).toList());
            connections.add(ports.stream().mapToInt(p->region.grid().cell(p.cellX(),p.cellY(),p.cellZ())).distinct().toArray());
        }
        region.replaceDevicePorts(attached,connections);
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
