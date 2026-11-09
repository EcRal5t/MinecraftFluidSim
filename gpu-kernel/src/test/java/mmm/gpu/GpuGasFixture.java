package mmm.gpu;

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
import static mmm.gpu.VulkanGasStateLayout.Field.*;

/** Additional resident gas geometry/topology/EOS cost over already resident liquid geometry. */
final class GpuGasFixture {
    private GpuGasFixture() { }
    record Geometry(VulkanLiquidStateLayout.Column masks,VulkanLiquidStateLayout.Column occupied,
                            VulkanLiquidStateLayout.Column cells,VulkanLiquidStateLayout.Column planes,
                            VulkanLiquidStateLayout.Column micro,VulkanLiquidStateLayout.Column faces,
                            VulkanLiquidStateLayout.Column fluxes,VulkanLiquidStateLayout.Column walls,VulkanMixtureState.Layout mixture,long endOffset) { }
    record FixtureGas(GasRegistry gas,ThermalRegistry thermal,VulkanGasStateLayout.Definitions definitions) { }
    private record SeedRequest(double volume,double temperature) { }
    private record Seed(GasInventory inventory,double energy) { }
    static void advance(VulkanDeviceContext context,VulkanRegionStorage storage,VulkanRegionStorage.Capture source,VulkanLiquidGeometry liquidKernel,
                               VulkanLiquidCellVolumes totalKernel,VulkanGasGeometry geometryKernel,VulkanGasThermodynamics thermalKernel,
                               VulkanLiquidStateLayout liquid,Geometry geometry,VulkanLiquidCellVolumes.Layout totals,
                               VulkanGasGeometry.Layout gasGeometry,VulkanGasThermodynamics.Layout thermal,
                               boolean rebuildLiquid,boolean eos,boolean commit) {
        try(var mixtureKernel=new VulkanMixtureState(context);var candidate=storage.prepare(source)) {
            int error=candidate.execute(0,(command,input,output,errors,staging,stack)->{
                var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                if(rebuildLiquid) {
                    liquidKernel.rebindRanges(geometryRanges(liquid,geometry,output,errors));liquidKernel.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                    totalKernel.rebindRanges(src,dst,err);totalKernel.record(command,totals);
                }
                geometryKernel.rebindRanges(dst,err);thermalKernel.rebindRanges(dst,err);mixtureKernel.rebindRanges(dst,err);
                org.lwjgl.vulkan.VK10.vkCmdUpdateBuffer(command,output.handle(),geometry.mixture().header().offset(),geometry.mixture().headerBytes());
                VulkanRegionStorage.barrier(command,org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                        org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT,stack);
                mixtureKernel.record(command,geometry.mixture(),gasGeometry.cellSize());
                geometryKernel.recordLocalGeometry(command,gasGeometry);
                if(eos)thermalKernel.record(command,thermal);
            });
            if(error!=0)throw new AssertionError("Gas component/EOS status="+error);
            if(commit)candidate.commit();
        }
    }
    static int seed(VulkanRegionStorage storage,VulkanRegionStorage.Capture source,ByteBuffer target,
                            VulkanGasStateLayout gas,VulkanGasGeometry.Layout geometry,FixtureGas definitions) {
        int nodes=geometry.cells(),cells=nodes,active=0;
        var dry=read(storage,source,geometry.dryVolumes().offset(),8*cells);
        double[] volumes=new double[nodes];
        for(int cell=0;cell<cells;cell++){volumes[cell]=dry.getDouble(8*cell);if(volumes[cell]>0)active++;}
        var model=new GasThermalModel(definitions.thermal(),definitions.gas());var cache=new HashMap<SeedRequest,Seed>();
        List<GasRegistry.AmbientFraction> recipe=List.of(new GasRegistry.AmbientFraction(BuiltinGasDefinitions.NITROGEN,ExactRational.parse(".7")),
                new GasRegistry.AmbientFraction(BuiltinGasDefinitions.OXYGEN,ExactRational.parse(".3")));
        int component=0,species=0,stride=gas.definitions().componentIds().size();
        for(int node=0;node<nodes;node++) {
            double temperature=switch(node%3){case 0->260;case 1->380;default->720;};
            var request=new SeedRequest(volumes[node],temperature);
            var seed=cache.computeIfAbsent(request,r->{var inventory=r.volume()>0?model.inventoryAtPressure(r.volume(),120000,r.temperature(),recipe):GasInventory.EMPTY;
                return new Seed(inventory,model.internalEnergyAtTemperature(inventory,r.temperature()));});
            putInt(target,gas,ROW_COMPONENT_OFFSETS,node,component);
            putDouble(target,gas,INTERNAL_ENERGY,node,seed.energy());
            for(int handle=0;handle<stride;handle++){
                putInt(target,gas,COMPONENT_HANDLES,component,handle);putInt(target,gas,COMPONENT_SPECIES_OFFSETS,component,species);
                putLong(target,gas,STANDARD_VOLUME_NANOS,component,0);putLong(target,gas,MASS_UNITS,component,0);putLong(target,gas,MASS_RESIDUAL_UNITS,component,0);
                var allowed=gas.definitions().allowedSpecies().get(handle);int firstSpecies=species;
                for(String id:allowed){putInt(target,gas,SPECIES_HANDLES,species,gas.definitions().speciesIds().indexOf(id));putLong(target,gas,SPECIES_AMOUNTS,species++,0);}
                for(var part:seed.inventory().components())if(gas.definitions().componentIds().indexOf(part.componentId())==handle){
                    putLong(target,gas,STANDARD_VOLUME_NANOS,component,part.standardVolumeNanos());putLong(target,gas,MASS_UNITS,component,part.massUnits());putLong(target,gas,MASS_RESIDUAL_UNITS,component,part.massResidualUnits());
                    for(var amount:part.species()){int slot=firstSpecies+allowed.indexOf(amount.speciesId());putLong(target,gas,SPECIES_AMOUNTS,slot,amount.amountQuanta());}break;
                }
                component++;
            }
        }
        for(int row=nodes;row<nodes+4;row++){
            putInt(target,gas,ROW_COMPONENT_OFFSETS,row,component);
            for(int handle=0;handle<stride;handle++){putInt(target,gas,COMPONENT_HANDLES,component,handle);putInt(target,gas,COMPONENT_SPECIES_OFFSETS,component,species);putLong(target,gas,STANDARD_VOLUME_NANOS,component,0);putLong(target,gas,MASS_UNITS,component,0);putLong(target,gas,MASS_RESIDUAL_UNITS,component,0);for(String id:gas.definitions().allowedSpecies().get(handle)){putInt(target,gas,SPECIES_HANDLES,species,gas.definitions().speciesIds().indexOf(id));putLong(target,gas,SPECIES_AMOUNTS,species++,0);}component++;}
        }
        putInt(target,gas,ROW_COMPONENT_OFFSETS,nodes+4,component);putInt(target,gas,COMPONENT_SPECIES_OFFSETS,component,species);
        putInt(target,gas,COUNTS,0,nodes);putInt(target,gas,COUNTS,1,component);putInt(target,gas,COUNTS,2,species);
        putInt(target,gas,ENERGY_PRESENT,0,1);
        System.out.printf("gas_seed cells=%d nodes=%d components=%d species=%d distinct_states=%d%n",cells,nodes,component,species,cache.size());return active;
    }
    static FixtureGas gasDefinitions() {
        var gas=mmm.fluid.TestFluidDefinitions.load().gas();var chemistry=mmm.fluid.TestFluidDefinitions.load().chemistry();
        var nitrogen=new ThermalMaterialDefinition("mmm:bench_nitrogen",ThermalMaterialDefinition.CarrierKind.GAS,BuiltinGasDefinitions.NITROGEN,
                280,15000,0,List.of(new ThermalMaterialDefinition.CaloricSegment(100,320,720),new ThermalMaterialDefinition.CaloricSegment(320,600,800),new ThermalMaterialDefinition.CaloricSegment(600,1500,950)));
        var oxygen=new ThermalMaterialDefinition("mmm:bench_oxygen",ThermalMaterialDefinition.CarrierKind.GAS,BuiltinGasDefinitions.OXYGEN,
                300,20000,0,List.of(new ThermalMaterialDefinition.CaloricSegment(100,400,650),new ThermalMaterialDefinition.CaloricSegment(400,900,900),new ThermalMaterialDefinition.CaloricSegment(900,1500,1050)));
        var thermal=new ThermalRegistry(List.of(nitrogen,oxygen),List.of(
                new ThermalSpeciesOffset("mmm:bench_nitrogen_n2",BuiltinChemicalDefinitions.N2,nitrogen.id(),ExactRational.parse("600")),
                new ThermalSpeciesOffset("mmm:bench_oxygen_o2",BuiltinChemicalDefinitions.O2,oxygen.id(),ExactRational.parse("-400"))),
                List.of(),List.of(),chemistry.fingerprint(),"",gas.fingerprint());
        return new FixtureGas(gas,thermal,new VulkanGasStateLayout.Definitions(gas.fingerprint(),chemistry.fingerprint(),
                List.of(BuiltinGasDefinitions.NITROGEN,BuiltinGasDefinitions.OXYGEN),List.of(BuiltinChemicalDefinitions.N2,BuiltinChemicalDefinitions.O2),List.of(BuiltinGasDefinitions.NITROGEN,BuiltinGasDefinitions.OXYGEN).stream().map(id->gas.component(id).allowedSpecies()).toList()));
    }
    static Geometry geometry(VulkanLiquidStateLayout state,long alignment) {
        int cells=state.cellCount(),faces=state.column(VulkanLiquidStateLayout.Field.MOMENTUM_X).count()+state.column(VulkanLiquidStateLayout.Field.MOMENTUM_Y).count()+state.column(VulkanLiquidStateLayout.Field.MOMENTUM_Z).count();
        long[] cursor={state.bytes()};var masks=column(cursor,cells,4,alignment);var occupied=column(cursor,cells,8,alignment);
        var totals=column(cursor,4*cells,8,alignment);var planes=column(cursor,4*cells,8,alignment);var micro=column(cursor,8*cells,8,alignment);
        var face=column(cursor,4*faces,8,alignment);var flux=column(cursor,2*faces,8,alignment);var walls=column(cursor,12*cells,8,alignment);
        var mixture=VulkanMixtureState.layout(new VulkanMixtureState.Inputs(cells,word(totals),4,word(totals)+2,4,word(totals)+4,4,null,.5),cursor[0],alignment);
        return new Geometry(masks,occupied,totals,planes,micro,face,flux,walls,mixture,mixture.endOffset());
    }
    static List<FlipKernelBindings.BufferRange> geometryRanges(VulkanLiquidStateLayout state,Geometry geometry,GpuBuffer buffer,GpuBuffer errors) {
        var ranges=new ArrayList<FlipKernelBindings.BufferRange>(14);
        ranges.addAll(state.ranges(buffer,VulkanLiquidStateLayout.Field.CELL_OFFSETS,VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES,VulkanLiquidStateLayout.Field.MASS_UNITS));
        ranges.add(range(buffer,geometry.masks()));ranges.add(range(buffer,geometry.occupied()));
        ranges.addAll(state.ranges(buffer,VulkanLiquidStateLayout.Field.MOMENTUM_X,VulkanLiquidStateLayout.Field.MOMENTUM_Y,VulkanLiquidStateLayout.Field.MOMENTUM_Z));
        ranges.add(range(buffer,geometry.cells()));ranges.add(range(buffer,geometry.planes()));ranges.add(range(buffer,geometry.micro()));ranges.add(range(buffer,geometry.faces()));
        ranges.add(new FlipKernelBindings.BufferRange(errors));ranges.add(range(buffer,geometry.fluxes()));ranges.add(range(buffer,geometry.walls()));return ranges;
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
