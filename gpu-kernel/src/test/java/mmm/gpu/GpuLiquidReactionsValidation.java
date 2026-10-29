package mmm.gpu;

import mmm.chemistry.*;
import mmm.fluid.*;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.inventory.DeviceLiquidInventory;
import mmm.thermal.*;
import mmm.units.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

/** Device dissociation, neutralization and a second committed pass over the resulting inventory. */
public final class GpuLiquidReactionsValidation {
    private GpuLiquidReactionsValidation(){}
    public static void main(String[] args){
        var chemistry=BuiltinChemicalDefinitions.REGISTRY;var granular=BuiltinGranularDefinitions.createRegistry();var gas=BuiltinGasDefinitions.createRegistry();
        var material=new ThermalMaterialDefinition("mmm:reaction_water",ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water",300,0,0,
                List.of(new ThermalMaterialDefinition.CaloricSegment(1,2000,4200)));
        var thermal=new ThermalRegistry(List.of(material),List.of(),List.of(),List.of(),chemistry.fingerprint(),granular.fingerprint(),gas.fingerprint());
        var fluid=new FluidMaterialRegistry(List.of(FluidMaterial.withSurfaceTension("mmm:water",1000,"mmm:water",0,90)));
        int acid=chemistry.speciesHandle(BuiltinChemicalDefinitions.HCL),base=chemistry.speciesHandle(BuiltinChemicalDefinitions.NAOH);
        int[] handles=acid<base?new int[]{acid,base}:new int[]{base,acid};
        var inventory=new DeviceLiquidInventory(new int[]{0,1,1,1,1,1,1,1,1},new int[]{0},new int[]{255},
                DeviceDyadicArray.encode(List.of(SignedDyadic.of(.008))),new long[]{10_000_000_000L},new long[]{0},new int[]{1},
                new int[]{0,2},handles,new long[]{100,100},new long[]{10_000_000_000L-76_000});
        var momentum=new MacMomentumState(2,2,2,new double[12],new double[12],new double[12]);
        try(var context=new VulkanDeviceContext(args.length>0?Integer.parseInt(args[0]):0,true,true);var reactions=new VulkanLiquidReactions(context)){
            long a=Math.max(8,context.storageOffsetAlignment());var liquid=new VulkanLiquidStateLayout(2,2,2,1,8,inventory.volumes().limbs().length,a,1);
            var definitions=VulkanReactionDefinitions.fromRegistries(chemistry,granular,thermal,gas).layout(liquid.bytes(),a);
            long reference=(definitions.endOffset()+a-1)&-a,speciesReference=(reference+8+a-1)&-a;
            var caloric=VulkanLiquidThermodynamics.layout(liquid,VulkanLiquidThermodynamics.tables(fluid,thermal),Math.toIntExact(reference/4),
                    Math.toIntExact(speciesReference/4),chemistry.species().size(),speciesReference+8L*chemistry.species().size(),a);
            var progress=new VulkanReactionProgressLayout(8,8,8,List.of(),definitions,caloric.endOffset(),a);
            var layout=VulkanLiquidReactions.layout(liquid,definitions,caloric,progress,fluid,chemistry,progress.endOffset(),a);
            var bytes=ByteBuffer.allocate(Math.toIntExact(layout.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
            liquid.importState(bytes,inventory,momentum);definitions.initialize(bytes);caloric.initializeHeaderAndTables(bytes);progress.initializeHeader(bytes);layout.initializeHeaderAndTables(bytes);
            try(var storage=new VulkanRegionStorage(context,layout.endOffset(),1024*1024)){
                storage.initialize(bytes,0);
                for(int step=0;step<2;step++)try(var source=storage.capture();var candidate=storage.prepare(source)){
                    var result=candidate.beginBatch((command,input,output,errors,staging,stack)->{
                        reactions.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));reactions.recordPrepare(command,layout);
                    },layout.feedbackOffset(),12);
                    if(result.error()!=0||result.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0)!=VulkanLiquidReactions.READY)
                        throw new AssertionError("Liquid reaction candidate failed: "+result.error());
                    int error=candidate.finishBatch((command,input,output,errors,staging,stack)->reactions.recordPublish(command,layout));
                    if(error!=0)throw new AssertionError("Liquid reaction publication failed: "+error);candidate.commit();
                }
                var actual=ByteBuffer.allocate(Math.toIntExact(liquid.bytes())).order(ByteOrder.LITTLE_ENDIAN);
                try(var capture=storage.capture()){storage.read(capture,0,actual);}actual.flip();var after=liquid.exportState(actual).inventory();
                var expected=Map.of(BuiltinChemicalDefinitions.H2O,100L,BuiltinChemicalDefinitions.NA_PLUS,100L,BuiltinChemicalDefinitions.CL_MINUS,100L);
                if(after.speciesHandles().length!=expected.size()||after.massUnits()[0]!=inventory.massUnits()[0]||after.energyBits()[0]!=0L)
                    throw new AssertionError("Chemical mass, energy or composition count changed unexpectedly");
                for(int i=0;i<after.speciesHandles().length;i++)if(after.speciesAmounts()[i]!=expected.getOrDefault(chemistry.species(after.speciesHandles()[i]).id(),-1L))
                    throw new AssertionError("Reaction did not complete exact neutralization");
                if(after.massResidualUnits()[0]!=inventory.massResidualUnits()[0])throw new AssertionError("Chemical residual mass changed");
                System.out.println("GPU liquid reactions: exact dissociation/neutralization, new species CSR and two committed steps passed");
            }
        }
    }
}
