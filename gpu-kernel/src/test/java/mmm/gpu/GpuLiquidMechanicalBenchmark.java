package mmm.gpu;

import java.util.Arrays;
import java.util.Locale;

/** Continuous liquid/gas mechanical candidates, including device capacity and actual inventory transport. */
public final class GpuLiquidMechanicalBenchmark {
    private GpuLiquidMechanicalBenchmark() { }
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);System.setProperty("mmm.gpuMechanicalGrowths","true");
        System.setProperty("mmm.gpuMechanicalPressureDiagnostics","true");
        if(args.length>6)System.setProperty("mmm.gpuCapacityFailureDump",args[6]);
        int device=args.length>0?Integer.parseInt(args[0]):0;
        int[] grids=Arrays.stream((args.length>1?args[1]:"32").split(",")).mapToInt(Integer::parseInt).toArray();
        int samples=args.length>2?Integer.parseInt(args[2]):10,warm=args.length>3?Integer.parseInt(args[3]):5;
        int granularCount=args.length>7?Integer.parseInt(args[7]):0;
        boolean production=args.length>9&&Boolean.parseBoolean(args[9]);
        boolean conduction=production||args.length>8&&Boolean.parseBoolean(args[8]);
        boolean gas=production||args.length>4&&Boolean.parseBoolean(args[4])||granularCount>0;
        var productionInputs=production?productionInputs(java.nio.file.Path.of(args[10])):null;
        boolean cut=args.length>5&&args[5].equals("cut");
        if(samples<1||warm<0)throw new IllegalArgumentException("Invalid measured or warmup step count");
        try(var context=new VulkanDeviceContext(device,true,true);var geometry=new VulkanLiquidGeometry(context);
            var projection=new VulkanLiquidProjection(context);var pressure=new VulkanPressureKernel(context,4096);
            var multigrid=new VulkanPressureMultigrid(context)) {
            System.out.println("Device: "+context.deviceName());
            System.out.println("Continuous resident mixture: geometry, viscosity, forces and pressure, donor-bounded transport, common momentum and energy, thermal exchange, commit.");
            System.out.println(gas?"Finite local gas enabled: N2/O2 fixture, mixture EOS, shared pressure, direct face transport and local pressure work.":"Ambient gas reservoir.");
            System.out.println(production?"Production material, gas, heat, reaction and phase-change definitions; resident candidate execution and commit. Backend budget input exceeds default region size and excludes Minecraft world and presentation costs.":"Consecutive commits; explicit fixture reference tables, q=2^-40. Excludes reactions, phase transfer, runtime/backend adoption, world and presentation costs.");
            System.out.println("warmup_steps="+warm+" measured_steps="+samples);
            System.out.println("thermal_conduction="+conduction+" (post-mechanical actual inventories, spatial contacts, bath exchange and exact ledgers)");
            System.out.println("grid,steps,median_ms,max_ms,gpu_mean_ms,upload_B_per_step,readback_B_per_step,device_MiB,staging_MiB");
            for(int grid:grids)measure(context,geometry,projection,pressure,multigrid,grid,warm,samples,gas,cut,granularCount,conduction,productionInputs);
        }
    }
    private static void measure(VulkanDeviceContext context,VulkanLiquidGeometry geometry,VulkanLiquidProjection projection,
                                VulkanPressureKernel pressure,VulkanPressureMultigrid multigrid,int grid,int warm,int samples,boolean gas,boolean cut,int granularCount,boolean conduction,ProductionInputs production) {
        if(granularCount>0&&cut)throw new IllegalArgumentException("Granular continuous fixture uses the dam scene");
        double h=production!=null||granularCount>0?.5:.25;
        var fixture=GpuLiquidGeometryBenchmark.fixture(grid,cut,h);
        System.out.println("scene="+(cut?"cut-partial-mixture":"dam-mixture")+" cell_size_m="+h+" extent_m="+(grid*h));
        if(production!=null&&cut)System.out.println("production cut grid uses 0.5 m cells; water/oil volumes and gas inventory scale with physical cell volume (8x the 0.25 m mechanical fixture).");
        int cells=grid*grid*grid,stocks=2*cells;
        // Explicit fixture caloric model: U=m*4200*T, anchored at the declared reference temperature.
        var caloric=new java.util.ArrayList<mmm.thermal.ThermalMaterialDefinition>();
        for(String id:new String[]{"mmm:water","mmm:oil"})caloric.add(new mmm.thermal.ThermalMaterialDefinition(id,
                mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,id,293.15,4200*293.15,0,
                java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K,10000,4200))));
        var fluidDefinitions=new mmm.fluid.FluidMaterialRegistry(java.util.List.of(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material(),mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:oil").material()));
        var constitutive=mmm.fluid.TestFluidDefinitions.load().material();
        var definitions=new VulkanLiquidRegion.MechanicalDefinitions(stocks,0,4*stocks,4,-40,
                new int[]{0,1},new long[]{0},new double[]{4200*293.15,4200*293.15},new double[]{0,0},1,VulkanLiquidThermodynamics.tables(caloric),VulkanLiquidViscosity.tables(fluidDefinitions,constitutive),
                VulkanLiquidForces.tables(fluidDefinitions,constitutive),VulkanSurfaceTension.tables(fluidDefinitions),true);
        var parameters=new VulkanLiquidProjection.Parameters((float)h,.05f,9.81f,1e-5f,200);
        if(production!=null) {
            var inventory=productionInventory(fixture.inventory(),production);
            fixture=new GpuLiquidGeometryBenchmark.Fixture(inventory,fixture.momentum(),fixture.fixed(),fixture.occupied());
            definitions=production.data().definitions().capacities(stocks,inventory.speciesHandles().length,4*stocks,4);
        }
        var gasConfig=gas?seedGas(context,fixture,h,production==null?GpuGasFixture.gasDefinitions():production.gas()):null;
        var granularConfig=granularCount>0?seedGranular(grid,granularCount,production):production==null?null:emptyProductionGranular(production);
        VulkanRegionThermal.Configuration thermalConfig=null;
        if(conduction){
            var liquidThermal=java.util.List.of(withConductivity(caloric.get(0),.6),withConductivity(caloric.get(1),.15));
            var gasThermal=new java.util.ArrayList<mmm.thermal.ThermalMaterialDefinition>();
            if(gas&&production==null){var gasFixture=GpuGasFixture.gasDefinitions();for(var id:gasConfig.tables().definitions().componentIds())
                gasThermal.add(withConductivity(gasFixture.thermal().material(gasFixture.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS,id)),.026));}
            var granularThermal=new java.util.ArrayList<mmm.thermal.ThermalMaterialDefinition>();
            if(granularConfig!=null)for(var id:granularConfig.initial().definitions().materialIds())
                granularThermal.add(new mmm.thermal.ThermalMaterialDefinition(id,mmm.thermal.ThermalMaterialDefinition.CarrierKind.GRANULAR,id,293.15,800*293.15,1.2,
                        java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K,10000,800))));
            double[] bath=new double[6*cells];java.util.Arrays.fill(bath,Double.NaN);for(int c=0;c<cells;c++)if(fixture.momentum().grid().x(c)==0)bath[6*c]=330;
            if(production!=null){
                liquidThermal=production.fluids().materials().stream().map(m->production.thermal().material(production.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,m.id()))).toList();
                gasThermal.clear();for(var id:gasConfig.tables().definitions().componentIds())gasThermal.add(production.thermal().material(production.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS,id)));
                granularThermal.clear();if(granularConfig!=null)for(var id:granularConfig.initial().definitions().materialIds())granularThermal.add(production.thermal().material(production.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.GRANULAR,id)));
            }
            var coherentThermal=production==null?java.util.List.<mmm.thermal.ThermalMaterialDefinition>of():production.data().coherent().definitions().phaseIds().stream().map(id->production.thermal().material(production.thermal().materialHandle(id))).toList();
            thermalConfig=VulkanRegionThermal.Configuration.fromMaterials(liquidThermal,gasThermal,granularThermal,coherentThermal,h,9.81,bath,Math.max(1,8*cells));
        }
        try(var region=new VulkanLiquidRegion(context,geometry,projection,pressure,multigrid,fixture.inventory(),fixture.momentum(),
                fixture.fixed(),fixture.occupied(),definitions,gasConfig,granularConfig,thermalConfig,
                production==null?null:production.data().coherent(),null,
                production==null?null:production.data().reactions(),null)) {
            if(production!=null)printProductionLayout(region);
            System.out.println("Constitutive inputs: data-pack water/oil viscosity and expansion; fixture sigma=0, pair tension=0, neutral contact angle; static no-slip walls, no prescribed body contacts.");
            for(int step=0;step<warm;step++)try{advance(region,parameters,production!=null);printPressureProgress("warmup",step,region.pressureProgress());}
                catch(IllegalStateException failure){throw new IllegalStateException("Mechanical warmup step="+step+" failed",failure);}
            var before=region.metrics();long[] times=new long[samples],gpuTimes=new long[samples],uploads=new long[samples],reads=new long[samples],recordings=new long[samples],waits=new long[samples],submissions=new long[samples];
            var pressureProgress=new VulkanPressureKernel.Progress[samples];
            int[] substeps=new int[samples];
            for(int step=0;step<samples;step++) {
                var stepBefore=region.metrics();long start=System.nanoTime();
                try{substeps[step]=advance(region,parameters,production!=null);}catch(IllegalStateException failure){throw new IllegalStateException("Mechanical sample step="+step+" failed",failure);}
                times[step]=System.nanoTime()-start;
                var stepAfter=region.metrics();gpuTimes[step]=stepAfter.gpuNanos()-stepBefore.gpuNanos();uploads[step]=stepAfter.uploadBytes()-stepBefore.uploadBytes();reads[step]=stepAfter.readbackBytes()-stepBefore.readbackBytes();recordings[step]=stepAfter.recordingNanos()-stepBefore.recordingNanos();waits[step]=stepAfter.waitingNanos()-stepBefore.waitingNanos();submissions[step]=stepAfter.submissions()-stepBefore.submissions();
                pressureProgress[step]=region.pressureProgress();

            }
            var after=region.metrics();long[] sorted=times.clone();Arrays.sort(sorted);
            System.out.printf("%d,%d,%.3f,%.3f,%.3f,%.1f,%.1f,%.1f,%.1f%n",grid,samples,sorted[samples/2]*1e-6,sorted[samples-1]*1e-6,
                    (after.gpuNanos()-before.gpuNanos())*1e-6/samples,(double)(after.uploadBytes()-before.uploadBytes())/samples,
                    (double)(after.readbackBytes()-before.readbackBytes())/samples,after.deviceBytes()/1048576.0,after.stagingBytes()/1048576.0);
            for(int step=0;step<samples;step++)System.out.printf("sample=%d wall_ms=%.3f gpu_ms=%.3f upload_B=%d readback_B=%d record_ms=%.3f wait_ms=%.3f submissions=%d%n",step,times[step]*1e-6,gpuTimes[step]*1e-6,uploads[step],reads[step],recordings[step]*1e-6,waits[step]*1e-6,submissions[step]);
            for(int step=0;step<samples;step++)printPressureProgress("sample",step,pressureProgress[step]);
            if(production!=null)System.out.println("production_substeps="+Arrays.toString(substeps));
            if(!region.growthDiagnostics().isEmpty())System.out.println("growth capacity_order=liquidStocks,liquidSpecies,liquidLimbs,windowLimbs,gasComponents,gasSpecies; required_values=stage-specific");
            for(var growth:region.growthDiagnostics())System.out.printf("growth generation=%d stage=%s before=%s after=%s required=%s%n",growth.generation(),growth.stage(),Arrays.toString(growth.before()),Arrays.toString(growth.after()),Arrays.toString(growth.required()));
            System.out.println("final inventory_counts="+Arrays.toString(region.inventoryCounts())+" pressure_iterations_rms_status="+Arrays.toString(region.pressureDiagnostics()));
            if(gas)System.out.println("final gas_counts="+Arrays.toString(region.gasInventoryCounts()));
            if(granularCount>0){var finalGranular=region.checkpointGranular();double sourceY=granularConfig.initial().particles().stream().mapToDouble(VulkanGranularStateLayout.Particle::y).sum();
                double finalY=finalGranular.particles().stream().mapToDouble(VulkanGranularStateLayout.Particle::y).sum();
                System.out.println("final granular_active="+finalGranular.particles().size()+" deposits="+finalGranular.deposits().size()+" summed_vertical_displacement="+(finalY-sourceY));}
        }
    }
    private static void printProductionLayout(VulkanLiquidRegion region) {
        try {
            var field=VulkanLiquidRegion.class.getDeclaredField("workspace");field.setAccessible(true);
            var workspace=(VulkanLiquidWorkspace)field.get(region);var t=workspace.transport;
            long gas=t.gas==null?0:t.gas.endOffset-t.gas.state.baseOffset();
            long granular=t.granular==null?0:t.granular.endOffset-t.granular.state.baseOffset();
            long reaction=t.reactions==null?0:t.reactions.endOffset()-4L*t.reactions.definitions().word();
            long pending=t.reactions==null?0:t.reactions.progress().bytes();
            System.out.printf("production_layout workspace_bytes=%d descriptor_limit_bytes=%d gas_bytes=%d granular_bytes=%d reaction_bytes=%d progress_bytes=%d%n",
                    workspace.bytes,region.maximumRange,gas,granular,reaction,pending);
            if(t.reactions!=null){
                var r=t.reactions;long start=r.progress().endOffset(),liquid=r.liquid().endOffset()-start;
                long solid=r.solid()==null?0:r.solid().endOffset-r.liquid().endOffset();
                long afterSolid=r.solid()==null?r.liquid().endOffset():r.solid().endOffset;
                long condensed=r.condensedPhase()==null?0:r.condensedPhase().endOffset()-afterSolid;
                long afterCondensed=r.condensedPhase()==null?afterSolid:r.condensedPhase().endOffset();
                long gasTransfer=r.massTransfer()==null?0:r.massTransfer().endOffset-afterCondensed;
                long afterGas=r.massTransfer()==null?afterCondensed:r.massTransfer().endOffset;
                long diffusion=r.diffusion()==null?0:r.diffusion().endOffset-afterGas;
                System.out.printf("production_reaction_layout liquid_bytes=%d solid_bytes=%d condensed_bytes=%d gas_transfer_bytes=%d diffusion_bytes=%d%n",liquid,solid,condensed,gasTransfer,diffusion);
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot inspect production workspace",failure); }
    }
    private static int advance(VulkanLiquidRegion region,VulkanLiquidProjection.Parameters parameters,boolean production) {
        if(!production){region.advanceMechanical(parameters);return 0;}
        var request=new VulkanLiquidRegion.RequestInputs(mmm.fluid.EntityBodyState.EMPTY,true);
        try(var source=region.captureState();var candidate=region.prepareMechanical(source,parameters,request)){int substeps=candidate.substeps();candidate.commit();return substeps;}
    }
    private static void printPressureProgress(String phase,int step,VulkanPressureKernel.Progress progress){
        System.out.printf("common_pressure phase=%s step=%d iterations=%d residual_rms=%.9g residual_refreshes_before_final=%d%n",
                phase,step,progress.iterations(),progress.residualRms(),progress.residualRefreshes());
    }
    private static mmm.thermal.ThermalMaterialDefinition withConductivity(mmm.thermal.ThermalMaterialDefinition source,double conductivity){
        return new mmm.thermal.ThermalMaterialDefinition(source.id(),source.carrierKind(),source.carrierId(),source.referenceTemperatureK(),
                source.referenceSpecificEnergyJPerKg(),conductivity,source.caloricSegments());
    }
    private record ProductionInputs(mmm.chemistry.ChemicalRegistry chemistry,mmm.thermal.ThermalRegistry thermal,
                                    mmm.fluid.FluidMaterialRegistry fluids,VulkanFluidStateCodec.ImportData data,
                                    GpuGasFixture.FixtureGas gas) { }
    private static ProductionInputs productionInputs(java.nio.file.Path path) throws Exception {
        var chemistry=mmm.fluid.TestFluidDefinitions.load().chemistry();
        var granular=mmm.fluid.TestFluidDefinitions.load().granular();
        var gas=mmm.fluid.TestFluidDefinitions.load().gas();
        var thermal=new mmm.thermal.ThermalRegistry(
                GpuThermalPhaseValidation.read(path,"thermal_materials",mmm.thermal.ThermalDefinitionCodec::decodeMaterial),
                GpuThermalPhaseValidation.read(path,"thermal_species",mmm.thermal.ThermalDefinitionCodec::decodeSpecies),
                GpuThermalPhaseValidation.read(path,"phase_transitions",mmm.thermal.ThermalDefinitionCodec::decodeTransition),
                java.util.List.of(),java.util.List.of(),chemistry.fingerprint(),granular.fingerprint(),gas.fingerprint());
        var fluids=new mmm.fluid.FluidMaterialRegistry(java.util.List.of(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material(),mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:oil").material()));
        var host=new mmm.fluid.HostFluidState(2,2,2,new mmm.fluid.FluidSolverConfig(.5f,9.81f,.05f,200,1e-5f,.002f), mmm.fluid.TestFluidDefinitions.withThermal(thermal));
        host.bindMaterialRegistry(fluids);host.bindMaterialDefinitions(mmm.fluid.TestFluidDefinitions.load().material());
        host.bindGranular(granular,fluids,mmm.fluid.TestFluidDefinitions.load().material());
        host.bindChemistry(chemistry);host.bindGasRegistry(gas);host.bindThermal(host.definitions().thermal(),fluids,293.15);
        var data=new VulkanFluidStateCodec(host.captureAuthoritativeState()).importData();
        return new ProductionInputs(chemistry,thermal,fluids,data,new GpuGasFixture.FixtureGas(gas,thermal,data.gas().tables().definitions()));
    }
    private static mmm.inventory.DeviceLiquidInventory productionInventory(mmm.inventory.DeviceLiquidInventory source,ProductionInputs production) {
        int count=source.stockCount(),water=production.chemistry().speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.H2O);
        long waterUnit=production.chemistry().speciesMassUnits(water,1);
        int[] materials=source.materials(),present=new int[count],offsets=new int[count+1];
        long[] mass=source.massUnits(),energy=new long[count],residual=new long[count];
        int waterRows=0;for(int i=0;i<count;i++){if(materials[i]==0)waterRows++;offsets[i+1]=waterRows;}
        int[] handles=new int[waterRows];long[] amounts=new long[waterRows];
        var caloric=new mmm.thermal.ThermalStateFunction(production.thermal());
        var energyCache=new java.util.HashMap<String,Double>();int entry=0;
        for(int i=0;i<count;i++){
            java.util.Map<String,Long> species=java.util.Map.of();
            if(materials[i]==0){long q=mass[i]/waterUnit;present[i]=1;handles[entry]=water;amounts[entry++]=q;
                residual[i]=mass[i]-q*waterUnit;species=java.util.Map.of(mmm.chemistry.BuiltinChemicalDefinitions.H2O,q);}
            int material=materials[i];long units=mass[i];String key=material+":"+units;var composition=species;
            double u=energyCache.computeIfAbsent(key,k->caloric.internalEnergyAtTemperature(
                    production.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,production.fluids().material(material).id()),
                    mmm.units.Mass.kilograms(units),composition,293.15));
            energy[i]=Double.doubleToRawLongBits(u);
        }
        return new mmm.inventory.DeviceLiquidInventory(source.cellOffsets(),materials,source.supportMasks(),source.volumes(),mass,energy,present,offsets,handles,amounts,residual);
    }
    private static VulkanGranularStateLayout.Configuration emptyProductionGranular(ProductionInputs production) {
        var source=production.data().granular();
        var state=new VulkanGranularStateLayout.State(source.initial().definitions(),java.util.List.of(),java.util.List.of(),java.util.List.of(),java.util.List.of());
        return VulkanGranularStateLayout.Configuration.fromRegistries(state,source.registry(),production.chemistry(),production.thermal(),source.contactCapacity());
    }
    private static double granularEnergy(ProductionInputs production,String id,double mass) {
        return production==null?mass*800*293.15:new mmm.thermal.ThermalStateFunction(production.thermal()).internalEnergyAtTemperature(
                production.thermal().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.GRANULAR,id),mass,java.util.Map.of(),293.15);
    }
    /** Initial topology and inventory are prepared once, before continuous timing. */
    private static VulkanLiquidRegion.GasConfiguration seedGas(VulkanDeviceContext context,GpuLiquidGeometryBenchmark.Fixture fixture,double cellSize,GpuGasFixture.FixtureGas definitions) {
        int grid=fixture.momentum().sizeX(),n=grid*grid*grid;long a=context.storageOffsetAlignment();
        var liquid=VulkanLiquidStateLayout.forState(fixture.inventory(),fixture.momentum(),a);
        var geometry=GpuGasFixture.geometry(liquid,a);
        var totals=VulkanLiquidCellVolumes.layout(liquid,2*n,-40,geometry.endOffset(),a);
        var gasGeometry=VulkanGasGeometry.layout(totals,word(geometry.masks()),word(geometry.occupied()),word(geometry.cells()),word(geometry.planes()),word(geometry.micro()),true,cellSize,totals.endOffset(),a).withMixture(geometry.mixture());
        var gas=new VulkanGasStateLayout(mmm.spatial.SpatialGrid.rectangular(grid,grid,grid),n,definitions.definitions(),gasGeometry.endOffset(),a);
        var tables=VulkanGasThermodynamics.tables(definitions.definitions(),definitions.gas(),definitions.thermal(),mmm.fluid.TestFluidDefinitions.load().chemistry());
        var thermal=VulkanGasThermodynamics.layout(gas,tables,Math.toIntExact(gasGeometry.dryVolumes().offset()/4),gas.endOffset(),a);
        var initial=java.nio.ByteBuffer.allocate(Math.toIntExact(thermal.endOffset())).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        liquid.importState(initial,fixture.inventory(),fixture.momentum());totals.initializeHeader(initial);gasGeometry.initializeHeader(initial);thermal.initializeHeaderAndTables(initial);
        for(int c=0;c<n;c++){initial.putInt(Math.toIntExact(geometry.masks().offset()+4L*c),fixture.fixed().mask(c));initial.putLong(Math.toIntExact(geometry.occupied().offset()+8L*c),fixture.occupied()[c]);}
        try(var storage=new VulkanRegionStorage(context,thermal.endOffset(),1024*1024,liquid.grid());var liquidKernel=new VulkanLiquidGeometry(context);
            var totalKernel=new VulkanLiquidCellVolumes(context);var gasKernel=new VulkanGasGeometry(context);var thermalKernel=new VulkanGasThermodynamics(context)) {
            storage.initialize(initial,0);
            try(var source=storage.capture()){GpuGasFixture.advance(context,storage,source,liquidKernel,totalKernel,gasKernel,thermalKernel,liquid,geometry,totals,gasGeometry,thermal,true,false,true);}
            try(var source=storage.capture()){GpuGasFixture.seed(storage,source,initial,gas,gasGeometry,definitions);}
            var ambientRegistry=new mmm.fluid.air.GasRegistry(definitions.gas().components(),java.util.List.of(
                    new mmm.fluid.air.GasRegistry.AmbientFraction(mmm.fluid.air.BuiltinGasDefinitions.NITROGEN,mmm.chemistry.ExactRational.parse(".7")),
                    new mmm.fluid.air.GasRegistry.AmbientFraction(mmm.fluid.air.BuiltinGasDefinitions.OXYGEN,mmm.chemistry.ExactRational.parse(".3"))));
            var spatial=gas.exportSpatialState(initial);
            return VulkanLiquidRegion.GasConfiguration.forState(spatial,tables,cellSize,true,
                    VulkanGasAmbient.ambientTables(ambientRegistry,definitions.definitions(),293.15),mmm.fluid.TestFluidDefinitions.load().chemistry());
        }
    }
    private static VulkanGranularStateLayout.Configuration seedGranular(int grid,int count,ProductionInputs production){
        var registry=mmm.fluid.TestFluidDefinitions.load().granular();var ids=registry.materials().stream().map(mmm.granular.ParticulateMaterial::id).toList();
        var definitions=new VulkanGranularStateLayout.Definitions(registry.fingerprint(),registry.chemicalRegistryFingerprint(),ids,
                production==null?java.util.List.of():production.chemistry().species().stream().map(mmm.chemistry.ChemicalSpecies::id).toList());
        var particles=new java.util.ArrayList<VulkanGranularStateLayout.Particle>();var deposits=new java.util.ArrayList<VulkanGranularStateLayout.Deposit>();
        int sand=registry.materialHandle(mmm.granular.BuiltinGranularDefinitions.FINE_SAND),width=Math.min(grid-4,(int)Math.ceil(Math.sqrt(count)));
        var kernel=new mmm.granular.GranularKernel(grid,grid,grid,.5);long volume=137258;double mass=volume/134217728.0*registry.material(sand).referenceDensity();
        int bedCell=2*grid*grid+(grid-4);float porosity=.38f;double bedTop=.5*.25/(1.0-porosity);
        var bed=new VulkanGranularStateLayout.Row(sand,1L<<22,mmm.units.Mass.fromKilograms(.03125*registry.material(sand).referenceDensity()),false,0,new int[0],new long[0],granularEnergy(production,ids.get(sand),.03125*registry.material(sand).referenceDensity()));
        deposits.add(new VulkanGranularStateLayout.Deposit(bed,bedCell,porosity));
        for(int i=0;i<count;i++){
            boolean grounded=i>=count-Math.min(16,count);int local=i-(count-Math.min(16,count));
            double x=grounded?.5*(grid-4)+.0625+(local%4)*.125:1.0+(i%width)*.125;
            double z=grounded?1.0+.0625+(local/4)*.125:1.0+(i/width)*.125;
            double y=grounded?bedTop+.0625+1e-6:.5*(3*grid/4+.5);
            if(x>=.5*grid-.1||z>=.5*grid-.1)throw new IllegalArgumentException("Granular fixture exceeds region size");
            kernel.capture((float)x,(float)y,(float)z,volume);int[] cells=new int[8];long[] quotas=new long[8];Arrays.fill(cells,-1);int mask=kernel.canonicalMask();
            for(int k=0;k<kernel.size();k++){cells[k]=kernel.cell(k);quotas[k]=kernel.floor(k)+((mask>>>k)&1);}
            var row=new VulkanGranularStateLayout.Row(sand,volume,mmm.units.Mass.fromKilograms(mass),false,0,new int[0],new long[0],granularEnergy(production,ids.get(sand),mass));
            particles.add(new VulkanGranularStateLayout.Particle(row,x,y,z,0,-.1,0,0,0,cells,quotas));
        }
        var caloric=ids.stream().map(id->new mmm.thermal.ThermalMaterialDefinition(id,mmm.thermal.ThermalMaterialDefinition.CarrierKind.GRANULAR,id,293.15,800*293.15,0,
                java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K,10000,800)))).toList();
        double[] references=new double[ids.size()];Arrays.fill(references,800*293.15);
        var state=new VulkanGranularStateLayout.State(definitions,particles,deposits,java.util.List.of(),java.util.List.of());
        return production==null?new VulkanGranularStateLayout.Configuration(state,registry,VulkanLiquidThermodynamics.tables(caloric),references,new double[ids.size()],1,32*count)
                :VulkanGranularStateLayout.Configuration.fromRegistries(state,registry,production.chemistry(),production.thermal(),32*count);
    }
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}

}
