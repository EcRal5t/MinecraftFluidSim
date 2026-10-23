package mmm.gpuprobe;

import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ExactRational;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalSpeciesOffset;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import static mmm.gpuprobe.VulkanGasStateLayout.Field.*;

/** Additional resident gas geometry/topology/EOS cost over already resident liquid geometry. */
public final class GpuGasGeometryBenchmark {
    private GpuGasGeometryBenchmark() { }
    record Geometry(VulkanLiquidStateLayout.Column masks,VulkanLiquidStateLayout.Column occupied,
                            VulkanLiquidStateLayout.Column cells,VulkanLiquidStateLayout.Column planes,
                            VulkanLiquidStateLayout.Column micro,VulkanLiquidStateLayout.Column faces,
                            VulkanLiquidStateLayout.Column fluxes,long endOffset) { }
    record FixtureGas(GasRegistry gas,ThermalRegistry thermal,VulkanGasStateLayout.Definitions definitions) { }
    private record SeedRequest(double volume,double temperature) { }
    private record Seed(GasInventory inventory,double energy) { }
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int device=args.length>0?Integer.parseInt(args[0]):0,samples=args.length>1?Integer.parseInt(args[1]):10;
        if(samples<1)throw new IllegalArgumentException("At least one gas sample required");
        try(var context=new VulkanDeviceContext(device,true,true);var liquidGeometry=new VulkanLiquidGeometry(context);
            var totals=new VulkanLiquidCellVolumes(context);var geometry=new VulkanGasGeometry(context);
            var thermodynamics=new VulkanGasThermodynamics(context)) {
            System.out.println("Device: "+context.deviceName());
            System.out.println("Gas-only additional cost: exact dry presence, shared PLIC dry ports, sealed finite chambers, connectivity, membership CSR, parallel node volumes and heterogeneous segmented EOS.");
            System.out.println("Liquid geometry/totals remain resident. One initialization checkpoint seeds same-generation gas rows; timed candidates use no uploads and only status/connectivity feedback. No gas transport or pressure coupling included.");
            System.out.println("scene,grid,finite_nodes,median_ms,max_ms,gpu_mean_ms,connectivity_rounds_mean,upload_B_per_step,readback_B_per_step,device_MiB,staging_MiB");
            var definitions=gasDefinitions();
            for(int grid:new int[]{32,64})for(boolean cut:new boolean[]{false,true})
                measure(context,liquidGeometry,totals,geometry,thermodynamics,definitions,grid,cut,samples);
        }
    }
    private static void measure(VulkanDeviceContext context,VulkanLiquidGeometry liquidKernel,VulkanLiquidCellVolumes totalKernel,
                                VulkanGasGeometry geometryKernel,VulkanGasThermodynamics thermalKernel,FixtureGas definitions,
                                int grid,boolean cut,int samples) {
        var fixture=GpuLiquidGeometryBenchmark.fixture(grid,cut);
        long alignment=Math.max(8,context.storageOffsetAlignment());int cells=grid*grid*grid;
        var liquid=VulkanLiquidStateLayout.forState(fixture.inventory(),fixture.momentum(),alignment);
        Geometry geometry=geometry(liquid,alignment);
        var totals=VulkanLiquidCellVolumes.layout(liquid,2*cells,-40,geometry.endOffset(),alignment);
        var gasGeometry=VulkanGasGeometry.layout(totals,word(geometry.masks()),word(geometry.occupied()),word(geometry.cells()),
                word(geometry.planes()),word(geometry.micro()),true,.25,totals.endOffset(),alignment);
        var gas=new VulkanGasStateLayout(grid,grid,grid,cells,2*cells,2*cells,definitions.definitions(),gasGeometry.endOffset(),alignment);
        long volumeBase=gas.endOffset();
        var tables=VulkanGasThermodynamics.tables(gas.definitions(),definitions.gas(),definitions.thermal(),BuiltinChemicalDefinitions.REGISTRY);
        var thermal=VulkanGasThermodynamics.layout(gas,tables,Math.toIntExact(volumeBase/4),volumeBase+8L*cells,alignment);
        int bytes=Math.toIntExact(thermal.endOffset());
        if(bytes>context.maxStorageBufferRange())throw new IllegalArgumentException("Gas benchmark arena exceeds storage descriptor limit");
        var initial=ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        liquid.importState(initial,fixture.inventory(),fixture.momentum());totals.initializeHeader(initial);
        gasGeometry.initializeHeader(initial);thermal.initializeHeaderAndTables(initial);
        for(int cell=0;cell<cells;cell++) {
            initial.putInt(Math.toIntExact(geometry.masks().offset()+4L*cell),fixture.fixed().mask(cell));
            initial.putLong(Math.toIntExact(geometry.occupied().offset()+8L*cell),fixture.occupied()[cell]);
        }
        try(var storage=new VulkanRegionStorage(context,bytes,1024*1024)) {
            storage.initialize(initial,0);
            try(var source=storage.capture()) {advance(storage,source,liquidKernel,totalKernel,geometryKernel,thermalKernel,liquid,geometry,totals,gasGeometry,thermal,true,false,true);}
            int nodes;
            try(var captured=storage.capture()) {nodes=seed(storage,captured,initial,gas,gasGeometry,definitions);}
            storage.initialize(initial,1);
            try(var source=storage.capture()) {advance(storage,source,liquidKernel,totalKernel,geometryKernel,thermalKernel,liquid,geometry,totals,gasGeometry,thermal,true,true,true);}
            try(var source=storage.capture()) {
                for(int warm=0;warm<5;warm++)advance(storage,source,liquidKernel,totalKernel,geometryKernel,thermalKernel,liquid,geometry,totals,gasGeometry,thermal,false,true,false);
                var before=storage.metrics();long[] times=new long[samples];long rounds=0;
                for(int sample=0;sample<samples;sample++) {
                    long start=System.nanoTime();
                    rounds+=advance(storage,source,liquidKernel,totalKernel,geometryKernel,thermalKernel,liquid,geometry,totals,gasGeometry,thermal,false,true,false);
                    times[sample]=System.nanoTime()-start;
                }
                var after=storage.metrics();Arrays.sort(times);
                System.out.printf("%s,%d,%d,%.3f,%.3f,%.3f,%.1f,%d,%d,%.1f,%.1f%n",cut?"cut-partial-mixture":"dam-mixture",grid,nodes,
                        times[samples/2]*1e-6,times[samples-1]*1e-6,(after.gpuNanos()-before.gpuNanos())*1e-6/samples,(double)rounds/samples,
                        (after.uploadBytes()-before.uploadBytes())/samples,(after.readbackBytes()-before.readbackBytes())/samples,
                        after.deviceBytes()/1048576.0,after.stagingBytes()/1048576.0);
                advance(storage,source,liquidKernel,totalKernel,geometryKernel,thermalKernel,liquid,geometry,totals,gasGeometry,thermal,false,true,true);
            }
            try(var result=storage.capture()) {
                if(nodes>0) {
                    var state=read(storage,result,thermal.states().offset(),9*8);
                    System.out.printf("gas_state scene=%s grid=%d T=%.6f P=%.6f Cv=%.6f gamma=%.9f%n",
                            cut?"cut":"dam",grid,state.getDouble(0),state.getDouble(8),state.getDouble(24),state.getDouble(40));
                }
            }
        }
    }
    static int advance(VulkanRegionStorage storage,VulkanRegionStorage.Capture source,VulkanLiquidGeometry liquidKernel,
                               VulkanLiquidCellVolumes totalKernel,VulkanGasGeometry geometryKernel,VulkanGasThermodynamics thermalKernel,
                               VulkanLiquidStateLayout liquid,Geometry geometry,VulkanLiquidCellVolumes.Layout totals,
                               VulkanGasGeometry.Layout gasGeometry,VulkanGasThermodynamics.Layout thermal,
                               boolean rebuildLiquid,boolean eos,boolean commit) {
        try(var candidate=storage.prepare(source)) {
            var batch=candidate.beginBatch((command,input,output,errors,staging,stack)->{
                var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                if(rebuildLiquid) {
                    liquidKernel.rebindRanges(geometryRanges(liquid,geometry,output,errors));liquidKernel.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                    totalKernel.rebindRanges(src,dst,err);totalKernel.record(command,totals);
                }
                geometryKernel.rebindRanges(dst,err);thermalKernel.rebindRanges(dst,err);
                geometryKernel.recordGeometry(command,gasGeometry);geometryKernel.recordConnectivity(command,gasGeometry,4);
            },gasGeometry.changed().offset(),4);
            int rounds=4;
            if(batch.error()!=0)throw new AssertionError("Gas geometry status="+batch.error());
            while(batch.feedback().getInt(0)!=0) {
                if(rounds>=512)throw new AssertionError("Gas connectivity exceeded benchmark rounds");
                batch=candidate.continueBatch((command,input,output,errors,staging,stack)->geometryKernel.recordConnectivity(command,gasGeometry,4),gasGeometry.changed().offset(),4);
                rounds+=4;if(batch.error()!=0)throw new AssertionError("Gas connectivity status="+batch.error());
            }
            int error=candidate.finishBatch((command,input,output,errors,staging,stack)->{
                geometryKernel.recordComponents(command,gasGeometry);
                if(eos){thermalKernel.recordVolumes(command,thermal,gasGeometry);thermalKernel.record(command,thermal);}
            });
            if(error!=0)throw new AssertionError("Gas component/EOS status="+error);
            if(commit)candidate.commit();return rounds;
        }
    }
    static int seed(VulkanRegionStorage storage,VulkanRegionStorage.Capture source,ByteBuffer target,
                            VulkanGasStateLayout gas,VulkanGasGeometry.Layout geometry,FixtureGas definitions) {
        int nodes=read(storage,source,geometry.componentCount().offset(),4).getInt(0),cells=geometry.cells();
        var nodeByCell=read(storage,source,geometry.cellNodes().offset(),4*cells);
        var masks=read(storage,source,geometry.dryMasks().offset(),4*cells);
        var dry=read(storage,source,geometry.dryVolumes().offset(),8*cells);
        int[] keys=new int[nodes];Arrays.fill(keys,Integer.MAX_VALUE);double[] volumes=new double[nodes];
        for(int cell=0;cell<cells;cell++) {
            int node=nodeByCell.getInt(4*cell),mask=masks.getInt(4*cell);
            if(node>0){keys[node-1]=Math.min(keys[node-1],8*cell+Integer.numberOfTrailingZeros(mask));volumes[node-1]+=dry.getDouble(8*cell);}
            for(int micro=0;micro<8;micro++)putInt(target,gas,PIECE_NODE_ORDINAL,8*cell+micro,(mask&(1<<micro))==0?-1:node);
        }
        var model=new GasThermalModel(definitions.thermal(),definitions.gas());var cache=new HashMap<SeedRequest,Seed>();
        List<GasRegistry.AmbientFraction> recipe=List.of(new GasRegistry.AmbientFraction(BuiltinGasDefinitions.NITROGEN,ExactRational.parse(".7")),
                new GasRegistry.AmbientFraction(BuiltinGasDefinitions.OXYGEN,ExactRational.parse(".3")));
        int component=0,species=0;
        for(int node=0;node<nodes;node++) {
            double temperature=switch(node%3){case 0->260;case 1->380;default->720;};
            var request=new SeedRequest(volumes[node],temperature);
            var seed=cache.computeIfAbsent(request,r->{var inventory=definitions.gas().createAtState(r.volume(),120000,r.temperature(),recipe);
                return new Seed(inventory,model.internalEnergyAtTemperature(inventory,r.temperature()));});
            putInt(target,gas,TOPOLOGY_KEYS,node,keys[node]);putInt(target,gas,ROW_COMPONENT_OFFSETS,node,component);
            putDouble(target,gas,INTERNAL_ENERGY,node,seed.energy());
            for(var part:seed.inventory().components()) {
                putInt(target,gas,COMPONENT_HANDLES,component,gas.definitions().componentIds().indexOf(part.componentId()));
                putLong(target,gas,STANDARD_VOLUME_NANOS,component,part.standardVolumeNanos());putLong(target,gas,MASS_UNITS,component,part.massUnits());
                putLong(target,gas,MASS_RESIDUAL_UNITS,component,part.massResidualUnits());putInt(target,gas,COMPONENT_SPECIES_OFFSETS,component,species);
                for(var amount:part.species()) {putInt(target,gas,SPECIES_HANDLES,species,gas.definitions().speciesIds().indexOf(amount.speciesId()));
                    putLong(target,gas,SPECIES_AMOUNTS,species++,amount.amountQuanta());}
                component++;
            }
        }
        for(int row=nodes;row<=nodes+2;row++)putInt(target,gas,ROW_COMPONENT_OFFSETS,row,component);
        putInt(target,gas,COMPONENT_SPECIES_OFFSETS,component,species);
        putInt(target,gas,COUNTS,0,nodes);putInt(target,gas,COUNTS,1,component);putInt(target,gas,COUNTS,2,species);
        putInt(target,gas,ENERGY_PRESENT,0,1);
        System.out.printf("gas_seed cells=%d nodes=%d components=%d species=%d distinct_states=%d%n",cells,nodes,component,species,cache.size());return nodes;
    }
    static FixtureGas gasDefinitions() {
        var gas=BuiltinGasDefinitions.createRegistry();var chemistry=BuiltinChemicalDefinitions.REGISTRY;
        var nitrogen=new ThermalMaterialDefinition("mmm:bench_nitrogen",ThermalMaterialDefinition.CarrierKind.GAS,BuiltinGasDefinitions.NITROGEN,
                280,15000,0,List.of(new ThermalMaterialDefinition.CaloricSegment(100,320,720),new ThermalMaterialDefinition.CaloricSegment(320,600,800),new ThermalMaterialDefinition.CaloricSegment(600,1500,950)));
        var oxygen=new ThermalMaterialDefinition("mmm:bench_oxygen",ThermalMaterialDefinition.CarrierKind.GAS,BuiltinGasDefinitions.OXYGEN,
                300,20000,0,List.of(new ThermalMaterialDefinition.CaloricSegment(100,400,650),new ThermalMaterialDefinition.CaloricSegment(400,900,900),new ThermalMaterialDefinition.CaloricSegment(900,1500,1050)));
        var thermal=new ThermalRegistry(List.of(nitrogen,oxygen),List.of(
                new ThermalSpeciesOffset("mmm:bench_nitrogen_n2",BuiltinChemicalDefinitions.N2,nitrogen.id(),ExactRational.parse("600")),
                new ThermalSpeciesOffset("mmm:bench_oxygen_o2",BuiltinChemicalDefinitions.O2,oxygen.id(),ExactRational.parse("-400"))),
                List.of(),List.of(),chemistry.fingerprint(),"",gas.fingerprint());
        return new FixtureGas(gas,thermal,new VulkanGasStateLayout.Definitions(gas.fingerprint(),chemistry.fingerprint(),
                List.of(BuiltinGasDefinitions.NITROGEN,BuiltinGasDefinitions.OXYGEN),List.of(BuiltinChemicalDefinitions.N2,BuiltinChemicalDefinitions.O2)));
    }
    static Geometry geometry(VulkanLiquidStateLayout state,long alignment) {
        int cells=state.cellCount(),faces=state.column(VulkanLiquidStateLayout.Field.MOMENTUM_X).count()+state.column(VulkanLiquidStateLayout.Field.MOMENTUM_Y).count()+state.column(VulkanLiquidStateLayout.Field.MOMENTUM_Z).count();
        long[] cursor={state.bytes()};var masks=column(cursor,cells,4,alignment);var occupied=column(cursor,cells,8,alignment);
        var totals=column(cursor,4*cells,8,alignment);var planes=column(cursor,4*cells,8,alignment);var micro=column(cursor,8*cells,8,alignment);
        var face=column(cursor,4*faces,8,alignment);var flux=column(cursor,2*faces,8,alignment);
        return new Geometry(masks,occupied,totals,planes,micro,face,flux,cursor[0]);
    }
    static List<FlipKernelBindings.BufferRange> geometryRanges(VulkanLiquidStateLayout state,Geometry geometry,GpuBuffer buffer,GpuBuffer errors) {
        var ranges=new ArrayList<FlipKernelBindings.BufferRange>(14);
        ranges.addAll(state.ranges(buffer,VulkanLiquidStateLayout.Field.CELL_OFFSETS,VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES,VulkanLiquidStateLayout.Field.MASS_UNITS));
        ranges.add(range(buffer,geometry.masks()));ranges.add(range(buffer,geometry.occupied()));
        ranges.addAll(state.ranges(buffer,VulkanLiquidStateLayout.Field.MOMENTUM_X,VulkanLiquidStateLayout.Field.MOMENTUM_Y,VulkanLiquidStateLayout.Field.MOMENTUM_Z));
        ranges.add(range(buffer,geometry.cells()));ranges.add(range(buffer,geometry.planes()));ranges.add(range(buffer,geometry.micro()));ranges.add(range(buffer,geometry.faces()));
        ranges.add(new FlipKernelBindings.BufferRange(errors));ranges.add(range(buffer,geometry.fluxes()));return ranges;
    }
    private static FlipKernelBindings.BufferRange range(GpuBuffer buffer,VulkanLiquidStateLayout.Column column) {return new FlipKernelBindings.BufferRange(buffer,column.offset(),column.bindingBytes());}
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width,long alignment){long offset=(cursor[0]+alignment-1)&-alignment;
        var result=new VulkanLiquidStateLayout.Column(offset,count,width);cursor[0]=offset+result.bindingBytes();return result;}
    private static ByteBuffer read(VulkanRegionStorage storage,VulkanRegionStorage.Capture capture,long offset,int bytes){var result=ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);storage.read(capture,offset,result);return result;}
    private static void putInt(ByteBuffer output,VulkanGasStateLayout layout,VulkanGasStateLayout.Field field,int index,int value){output.putInt(Math.toIntExact(layout.column(field).offset()+4L*index),value);}
    private static void putLong(ByteBuffer output,VulkanGasStateLayout layout,VulkanGasStateLayout.Field field,int index,long value){output.putLong(Math.toIntExact(layout.column(field).offset()+8L*index),value);}
    private static void putDouble(ByteBuffer output,VulkanGasStateLayout layout,VulkanGasStateLayout.Field field,int index,double value){output.putDouble(Math.toIntExact(layout.column(field).offset()+8L*index),value);}
}
