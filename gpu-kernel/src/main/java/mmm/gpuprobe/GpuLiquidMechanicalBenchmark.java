package mmm.gpuprobe;

import java.util.Arrays;
import java.util.Locale;

/** Continuous liquid/gas mechanical candidates, including device capacity and actual inventory transport. */
public final class GpuLiquidMechanicalBenchmark {
    private GpuLiquidMechanicalBenchmark() { }
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);System.setProperty("mmm.gpuMechanicalGrowths","true");
        if(args.length>6)System.setProperty("mmm.gpuCapacityFailureDump",args[6]);
        int device=args.length>0?Integer.parseInt(args[0]):0;
        int[] grids=Arrays.stream((args.length>1?args[1]:"32").split(",")).mapToInt(Integer::parseInt).toArray();
        int samples=args.length>2?Integer.parseInt(args[2]):10,warm=args.length>3?Integer.parseInt(args[3]):5;
        int granularCount=args.length>7?Integer.parseInt(args[7]):0;
        boolean gas=args.length>4&&Boolean.parseBoolean(args[4])||granularCount>0;
        boolean cut=args.length>5&&args[5].equals("cut");
        if(samples<1||warm<0)throw new IllegalArgumentException("Invalid measured or warmup step count");
        try(var context=new VulkanDeviceContext(device,true,true);var geometry=new VulkanLiquidGeometry(context);
            var projection=new VulkanLiquidProjection(context);var pressure=new VulkanPressureKernel(context,4096);
            var multigrid=new VulkanPressureMultigrid(context)) {
            System.out.println("Device: "+context.deviceName());
            System.out.println("Continuous resident liquid: geometry/thermal/viscosity/buoyancy/surface/gravity/pressure -> capacity -> exact accepted cell volumes -> material routes -> quantities/gather/viscous heat transport -> MAC -> future geometry -> mixing heat -> connected-body source K minus projected K and actual route PE -> common thermal allocation/exact ledgers -> commit.");
            System.out.println(gas?"Finite gas enabled: explicit N2/O2 .7/.3 fixture reservoir; old/future chamber geometry, EOS/common pressure, finite capacity groups, gas lineage/remap, actual control-volume work and synchronous finite-aperture inventory/cooling included.":"Ambient gas reservoir.");
            System.out.println("Consecutive commits; explicit fixture reference tables, q=2^-40. Excludes thermochemistry, runtime/backend adoption, world and presentation costs.");
            System.out.println("warmup_steps="+warm+" measured_steps="+samples);
            System.out.println("grid,steps,median_ms,max_ms,gpu_mean_ms,upload_B_per_step,readback_B_per_step,device_MiB,staging_MiB");
            for(int grid:grids)measure(context,geometry,projection,pressure,multigrid,grid,warm,samples,gas,cut,granularCount);
        }
    }
    private static void measure(VulkanDeviceContext context,VulkanLiquidGeometry geometry,VulkanLiquidProjection projection,
                                VulkanPressureKernel pressure,VulkanPressureMultigrid multigrid,int grid,int warm,int samples,boolean gas,boolean cut,int granularCount) {
        if(granularCount>0&&cut)throw new IllegalArgumentException("Granular continuous fixture uses the dam scene");
        double h=granularCount>0?.5:.25;
        var fixture=GpuLiquidGeometryBenchmark.fixture(grid,cut,h);
        System.out.println("scene="+(cut?"cut-partial-mixture":"dam-mixture"));
        int cells=grid*grid*grid,stocks=2*cells;
        // Explicit fixture caloric model: U=m*4200*T, anchored at the declared reference temperature.
        var caloric=new java.util.ArrayList<mmm.thermal.ThermalMaterialDefinition>();
        for(String id:new String[]{"mmm:water","mmm:oil"})caloric.add(new mmm.thermal.ThermalMaterialDefinition(id,
                mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,id,293.15,4200*293.15,0,
                java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K,10000,4200))));
        var fluidDefinitions=new mmm.fluid.FluidMaterialRegistry(java.util.List.of(mmm.fluid.BuiltinFluidMaterials.WATER,mmm.fluid.BuiltinFluidMaterials.OIL));
        var constitutive=mmm.fluid.BuiltinFluidMaterials.createRegistry();
        var definitions=new VulkanLiquidRegion.MechanicalDefinitions(stocks,0,4*stocks,4,-40,
                new int[]{0,1},new long[]{0},new double[]{4200*293.15,4200*293.15},new double[]{0,0},1,VulkanLiquidThermodynamics.tables(caloric),VulkanLiquidViscosity.tables(fluidDefinitions,constitutive),
                VulkanLiquidForces.tables(fluidDefinitions,constitutive),VulkanSurfaceTension.tables(fluidDefinitions),true);
        var parameters=new VulkanLiquidProjection.Parameters((float)h,.05f,9.81f,1e-5f,200);
        var gasConfig=gas?seedGas(context,fixture,h):null;
        var granularConfig=granularCount>0?seedGranular(grid,granularCount):null;
        try(var region=new VulkanLiquidRegion(context,geometry,projection,pressure,multigrid,fixture.inventory(),fixture.momentum(),
                fixture.fixed(),fixture.occupied(),definitions,gasConfig,granularConfig)) {
            System.out.println("Constitutive inputs: builtin water/oil viscosity and expansion; original sigma=0, pair tension=0, neutral contact angle; static no-slip walls, no prescribed body contacts.");
            for(int step=0;step<warm;step++)try{region.advanceMechanical(parameters,512);}
                catch(IllegalStateException failure){throw new IllegalStateException("Mechanical warmup step="+step+" failed",failure);}
            var before=region.metrics();long[] times=new long[samples],gpuTimes=new long[samples],uploads=new long[samples],reads=new long[samples],recordings=new long[samples],waits=new long[samples];
            for(int step=0;step<samples;step++) {
                var stepBefore=region.metrics();long start=System.nanoTime();
                try{region.advanceMechanical(parameters,512);}catch(IllegalStateException failure){throw new IllegalStateException("Mechanical sample step="+step+" failed",failure);}
                times[step]=System.nanoTime()-start;
                var stepAfter=region.metrics();gpuTimes[step]=stepAfter.gpuNanos()-stepBefore.gpuNanos();uploads[step]=stepAfter.uploadBytes()-stepBefore.uploadBytes();reads[step]=stepAfter.readbackBytes()-stepBefore.readbackBytes();recordings[step]=stepAfter.recordingNanos()-stepBefore.recordingNanos();waits[step]=stepAfter.waitingNanos()-stepBefore.waitingNanos();
            }
            var after=region.metrics();long[] sorted=times.clone();Arrays.sort(sorted);
            System.out.printf("%d,%d,%.3f,%.3f,%.3f,%.1f,%.1f,%.1f,%.1f%n",grid,samples,sorted[samples/2]*1e-6,sorted[samples-1]*1e-6,
                    (after.gpuNanos()-before.gpuNanos())*1e-6/samples,(double)(after.uploadBytes()-before.uploadBytes())/samples,
                    (double)(after.readbackBytes()-before.readbackBytes())/samples,after.deviceBytes()/1048576.0,after.stagingBytes()/1048576.0);
            for(int step=0;step<samples;step++)System.out.printf("sample=%d wall_ms=%.3f gpu_ms=%.3f upload_B=%d readback_B=%d record_ms=%.3f wait_ms=%.3f%n",step,times[step]*1e-6,gpuTimes[step]*1e-6,uploads[step],reads[step],recordings[step]*1e-6,waits[step]*1e-6);
            if(!region.growthDiagnostics().isEmpty())System.out.println("growth capacity_order=liquidStocks,liquidSpecies,liquidLimbs,windowLimbs,gasNodes,gasComponents,gasSpecies,parts,partSpecies,groups,pairs; required_order=cellLimbs,acceptedLimbs,materialLimbs,routeLimbs,routeSpecies,stocks,species,limbs,oldNodes,futureNodes,futureComponents,futureSpecies,retainedComponents,retainedSpecies,parts,partSpecies,futureCellLimbs,groups[,pairs,flowParts,flowPartSpecies,flowRetainedComponents,flowRetainedSpecies,flowFutureComponents,flowFutureSpecies,flowNodes,flowGroups]");
            for(var growth:region.growthDiagnostics())System.out.printf("growth generation=%d stage=%s before=%s after=%s required=%s%n",growth.generation(),growth.stage(),Arrays.toString(growth.before()),Arrays.toString(growth.after()),Arrays.toString(growth.required()));
            System.out.println("final inventory_counts="+Arrays.toString(region.inventoryCounts())+" capacity_status_rounds="+Arrays.toString(region.capacityDiagnostics())
                    +" pressure_iterations_rms_status="+Arrays.toString(region.pressureDiagnostics()));
            if(gas)System.out.println("final gas_counts="+Arrays.toString(region.gasInventoryCounts())+" old_future_gas_nodes_groups="+Arrays.toString(region.gasTopologyDiagnostics())+" aperture_pairs_iterations_rms_status_capture_escape="+Arrays.toString(region.gasFlowDiagnostics()));
            if(granularCount>0){var finalGranular=region.checkpointGranular();double sourceY=granularConfig.initial().particles().stream().mapToDouble(VulkanGranularStateLayout.Particle::y).sum();
                double finalY=finalGranular.particles().stream().mapToDouble(VulkanGranularStateLayout.Particle::y).sum();
                System.out.println("final granular_active="+finalGranular.particles().size()+" deposits="+finalGranular.deposits().size()+" summed_vertical_displacement="+(finalY-sourceY));}
        }
    }
    /** Initial topology and inventory are prepared once, before continuous timing. */
    private static VulkanLiquidRegion.GasConfiguration seedGas(VulkanDeviceContext context,GpuLiquidGeometryBenchmark.Fixture fixture,double cellSize) {
        int grid=fixture.momentum().sizeX(),n=grid*grid*grid;long a=context.storageOffsetAlignment();
        var liquid=VulkanLiquidStateLayout.forState(fixture.inventory(),fixture.momentum(),a);
        var geometry=GpuGasGeometryBenchmark.geometry(liquid,a);
        var totals=VulkanLiquidCellVolumes.layout(liquid,2*n,-40,geometry.endOffset(),a);
        var gasGeometry=VulkanGasGeometry.layout(totals,word(geometry.masks()),word(geometry.occupied()),word(geometry.cells()),word(geometry.planes()),word(geometry.micro()),true,cellSize,totals.endOffset(),a);
        var definitions=GpuGasGeometryBenchmark.gasDefinitions();
        var gas=new VulkanGasStateLayout(grid,grid,grid,n,2*n,2*n,definitions.definitions(),gasGeometry.endOffset(),a);
        var tables=VulkanGasThermodynamics.tables(definitions.definitions(),definitions.gas(),definitions.thermal(),mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY);
        var thermal=VulkanGasThermodynamics.layout(gas,tables,Math.toIntExact(gas.endOffset()/4),gas.endOffset()+8L*n,a);
        var initial=java.nio.ByteBuffer.allocate(Math.toIntExact(thermal.endOffset())).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        liquid.importState(initial,fixture.inventory(),fixture.momentum());totals.initializeHeader(initial);gasGeometry.initializeHeader(initial);thermal.initializeHeaderAndTables(initial);
        for(int c=0;c<n;c++){initial.putInt(Math.toIntExact(geometry.masks().offset()+4L*c),fixture.fixed().mask(c));initial.putLong(Math.toIntExact(geometry.occupied().offset()+8L*c),fixture.occupied()[c]);}
        try(var storage=new VulkanRegionStorage(context,thermal.endOffset(),1024*1024);var liquidKernel=new VulkanLiquidGeometry(context);
            var totalKernel=new VulkanLiquidCellVolumes(context);var gasKernel=new VulkanGasGeometry(context);var thermalKernel=new VulkanGasThermodynamics(context)) {
            storage.initialize(initial,0);
            try(var source=storage.capture()){GpuGasGeometryBenchmark.advance(storage,source,liquidKernel,totalKernel,gasKernel,thermalKernel,liquid,geometry,totals,gasGeometry,thermal,true,false,true);}
            try(var source=storage.capture()){GpuGasGeometryBenchmark.seed(storage,source,initial,gas,gasGeometry,definitions);}
            var ambientRegistry=new mmm.fluid.air.GasRegistry(definitions.gas().components(),java.util.List.of(
                    new mmm.fluid.air.GasRegistry.AmbientFraction(mmm.fluid.air.BuiltinGasDefinitions.NITROGEN,mmm.chemistry.ExactRational.parse(".7")),
                    new mmm.fluid.air.GasRegistry.AmbientFraction(mmm.fluid.air.BuiltinGasDefinitions.OXYGEN,mmm.chemistry.ExactRational.parse(".3"))));
            return VulkanLiquidRegion.GasConfiguration.forState(gas.exportState(initial),tables,cellSize,true,
                    VulkanGasRemap.ambientTables(ambientRegistry,definitions.definitions(),293.15));
        }
    }
    private static VulkanGranularStateLayout.Configuration seedGranular(int grid,int count){
        var registry=mmm.granular.BuiltinGranularDefinitions.createRegistry();var ids=registry.materials().stream().map(mmm.granular.ParticulateMaterial::id).toList();
        var definitions=new VulkanGranularStateLayout.Definitions(registry.fingerprint(),registry.chemicalRegistryFingerprint(),ids,java.util.List.of());
        var particles=new java.util.ArrayList<VulkanGranularStateLayout.Particle>();var deposits=new java.util.ArrayList<VulkanGranularStateLayout.Deposit>();
        int sand=registry.materialHandle(mmm.granular.BuiltinGranularDefinitions.FINE_SAND),width=Math.min(grid-4,(int)Math.ceil(Math.sqrt(count)));
        var kernel=new mmm.granular.GranularKernel(grid,grid,grid,.5);long volume=137258;double mass=volume/134217728.0*registry.material(sand).referenceDensity();
        int bedCell=2*grid*grid+(grid-4);float porosity=.38f;double bedTop=.5*.25/(1.0-porosity);
        var bed=new VulkanGranularStateLayout.Row(sand,1L<<22,mmm.units.Mass.fromKilograms(.03125*registry.material(sand).referenceDensity()),false,0,new int[0],new long[0],.03125*registry.material(sand).referenceDensity()*800*293.15);
        deposits.add(new VulkanGranularStateLayout.Deposit(bed,bedCell,porosity));
        for(int i=0;i<count;i++){
            boolean grounded=i>=count-Math.min(16,count);int local=i-(count-Math.min(16,count));
            double x=grounded?.5*(grid-4)+.0625+(local%4)*.125:1.0+(i%width)*.125;
            double z=grounded?1.0+.0625+(local/4)*.125:1.0+(i/width)*.125;
            double y=grounded?bedTop+.0625+1e-6:.5*(3*grid/4+.5);
            if(x>=.5*grid-.1||z>=.5*grid-.1)throw new IllegalArgumentException("Granular fixture exceeds region size");
            kernel.capture((float)x,(float)y,(float)z,volume);int[] cells=new int[8];long[] quotas=new long[8];Arrays.fill(cells,-1);int mask=kernel.canonicalMask();
            for(int k=0;k<kernel.size();k++){cells[k]=kernel.cell(k);quotas[k]=kernel.floor(k)+((mask>>>k)&1);}
            var row=new VulkanGranularStateLayout.Row(sand,volume,mmm.units.Mass.fromKilograms(mass),false,0,new int[0],new long[0],mass*800*293.15);
            particles.add(new VulkanGranularStateLayout.Particle(row,x,y,z,0,-.1,0,0,0,cells,quotas));
        }
        var caloric=ids.stream().map(id->new mmm.thermal.ThermalMaterialDefinition(id,mmm.thermal.ThermalMaterialDefinition.CarrierKind.GRANULAR,id,293.15,800*293.15,0,
                java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K,10000,800)))).toList();
        double[] references=new double[ids.size()];Arrays.fill(references,800*293.15);
        return new VulkanGranularStateLayout.Configuration(new VulkanGranularStateLayout.State(definitions,particles,deposits,java.util.List.of(),java.util.List.of()),registry,VulkanLiquidThermodynamics.tables(caloric),references,new double[ids.size()],1,32*count);
    }
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}

}
