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

/** Shared device kinetics: fast chemistry, reactant competition, reversible equilibrium and fractional progress. */
public final class GpuLiquidReactionsValidation {
    private GpuLiquidReactionsValidation(){}
    public static void main(String[] args){
        int device=args.length>0?Integer.parseInt(args[0]):0;
        for(int scenario=0;scenario<6;scenario++)run(device,scenario);
    }
    private static void run(int device,int scenario){
        var chemistry=(scenario==0||scenario==5)?mmm.fluid.TestFluidDefinitions.load().chemistry():kineticRegistry(scenario);var baseGranular=mmm.fluid.TestFluidDefinitions.load().granular();
        var granular=new mmm.granular.GranularRegistry(baseGranular.materials(),baseGranular.carriers(),baseGranular.reactions(),chemistry);var gas=mmm.fluid.TestFluidDefinitions.load().gas();
        var material=new ThermalMaterialDefinition("mmm:reaction_water",ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water",300,0,0,
                List.of(new ThermalMaterialDefinition.CaloricSegment(1,2000,4200)));
        var thermal=new ThermalRegistry(List.of(material),List.of(),List.of(),List.of(),chemistry.fingerprint(),granular.fingerprint(),gas.fingerprint());
        var fluid=new FluidMaterialRegistry(List.of(FluidMaterial.withSurfaceTension("mmm:water",1000,"mmm:water",0,90, 0xff197ae8)));
        long initial=scenario==0?100:scenario==3?10:scenario==5?1_000_000:100_000;
        var initialSpecies=new java.util.TreeMap<Integer,Long>();
        if(scenario==5){
            initialSpecies.put(chemistry.speciesHandle(BuiltinChemicalDefinitions.CO2),initial);
            initialSpecies.put(chemistry.speciesHandle(BuiltinChemicalDefinitions.NAOH),initial);
            initialSpecies.put(chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O),100_000_000L);
        }else{
            initialSpecies.put(chemistry.speciesHandle(scenario==0?BuiltinChemicalDefinitions.HCL:"test:kinetic_a"),initial);
            initialSpecies.put(chemistry.speciesHandle(scenario==0?BuiltinChemicalDefinitions.NAOH:"test:kinetic_b"),scenario==0?initial:0L);
        }
        int[] handles=initialSpecies.keySet().stream().mapToInt(Integer::intValue).toArray();
        long[] amounts=initialSpecies.values().stream().mapToLong(Long::longValue).toArray();
        long speciesMass=0;for(int i=0;i<handles.length;i++)speciesMass+=chemistry.speciesMassUnits(handles[i],amounts[i]);
        var inventory=new DeviceLiquidInventory(new int[]{0,1,1,1,1,1,1,1,1},new int[]{0},new int[]{255},
                DeviceDyadicArray.encode(List.of(SignedDyadic.of(.008))),new long[]{10_000_000_000L},new long[]{Double.doubleToRawLongBits(scenario==4?210000.0:0.0)},new int[]{1},
                new int[]{0,handles.length},handles,amounts,new long[]{10_000_000_000L-speciesMass});
        var momentum=new MacMomentumState(2,2,2,new double[12],new double[12],new double[12]);
        try(var context=new VulkanDeviceContext(device,true,true);var reactions=new VulkanLiquidReactions(context,chemistry)){
            long a=Math.max(8,context.storageOffsetAlignment());var liquid=new VulkanLiquidStateLayout(2,2,2,1,8,inventory.volumes().limbs().length,a,1);
            var definitions=VulkanReactionDefinitions.fromRegistries(chemistry,granular,thermal,gas).layout(liquid.bytes(),a);
            long reference=(definitions.endOffset()+a-1)&-a,speciesReference=(reference+8+a-1)&-a;
            var caloric=VulkanLiquidThermodynamics.layout(liquid,VulkanLiquidThermodynamics.tables(fluid,thermal),Math.toIntExact(reference/4),
                    Math.toIntExact(speciesReference/4),chemistry.species().size(),speciesReference+8L*chemistry.species().size(),a);
            var progress=new VulkanReactionProgressLayout(8,8,8,definitions,caloric.endOffset(),a);
            var layout=VulkanLiquidReactions.layout(liquid,definitions,caloric,progress,fluid,chemistry,progress.endOffset(),a);
            var bytes=ByteBuffer.allocate(Math.toIntExact(layout.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
            liquid.importState(bytes,inventory,momentum);
            if(scenario==4)bytes.putDouble(Math.toIntExact(speciesReference)+8*chemistry.speciesHandle("test:kinetic_b"),-.1);
            definitions.initialize(bytes);caloric.initializeHeaderAndTables(bytes);progress.initializeHeader(bytes);layout.initializeHeaderAndTables(bytes);
            var storage=new VulkanRegionStorage(context,layout.endOffset(),1024*1024,liquid.grid());
            try {
                storage.initialize(bytes,0);
                for(int step=0;step<(scenario==0?2:scenario==5?3:100);step++){
                    try(var source=storage.capture();var candidate=storage.prepare(source)){
                    var result=candidate.beginBatch((command,input,output,errors,staging,stack)->{
                        reactions.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));reactions.recordPrepare(command,layout);
                    },layout.feedbackOffset(),12);
                    if(result.error()!=0||result.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0)!=VulkanLiquidReactions.READY)
                        throw new AssertionError("Liquid reaction candidate failed: "+result.error());
                    int error=candidate.finishBatch((command,input,output,errors,staging,stack)->reactions.recordPublish(command,layout));
                    if(error!=0)throw new AssertionError("Liquid reaction publication failed: "+error);candidate.commit();
                    }
                    if(step==49&&scenario==3){
                        var checkpoint=ByteBuffer.allocate(Math.toIntExact(layout.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
                        try(var capture=storage.capture()){storage.read(capture,0,checkpoint);}checkpoint.flip();
                        var saved=progress.export(column->checkpoint.duplicate().position(Math.toIntExact(column.offset())).slice().order(ByteOrder.LITTLE_ENDIAN));
                        // Rebuild the persisted kinetic columns from stable identities before resuming.
                        for(var chunk:progress.stateChunks(saved))checkpoint.duplicate().position(Math.toIntExact(chunk.offset())).put(chunk.bytes().duplicate());
                        storage.close();storage=new VulkanRegionStorage(context,layout.endOffset(),1024*1024,liquid.grid());storage.initialize(checkpoint,step+1);
                    }
                }
                var actual=ByteBuffer.allocate(Math.toIntExact(liquid.bytes())).order(ByteOrder.LITTLE_ENDIAN);
                try(var capture=storage.capture()){storage.read(capture,0,actual);}actual.flip();var after=liquid.exportState(actual).inventory();
                if(after.massUnits()[0]!=inventory.massUnits()[0]||Math.abs(Double.longBitsToDouble(after.energyBits()[0])-Double.longBitsToDouble(inventory.energyBits()[0]))>1e-6||after.massResidualUnits()[0]!=inventory.massResidualUnits()[0])
                    throw new AssertionError("Reaction changed exact mass/energy/residual");
                var actualAmounts=new java.util.TreeMap<String,Long>();
                for(int i=0;i<after.speciesHandles().length;i++)actualAmounts.put(chemistry.species(after.speciesHandles()[i]).id(),after.speciesAmounts()[i]);
                if(scenario==0){
                    var expected=Map.of(BuiltinChemicalDefinitions.H2O,100L,BuiltinChemicalDefinitions.NA_PLUS,100L,BuiltinChemicalDefinitions.CL_MINUS,100L);
                    if(!actualAmounts.equals(expected))throw new AssertionError("Exact neutralization failed: "+actualAmounts);
                } else if(scenario==5){
                    long bicarbonate=actualAmounts.getOrDefault(BuiltinChemicalDefinitions.HCO3_MINUS,0L);
                    long co2=actualAmounts.getOrDefault(BuiltinChemicalDefinitions.CO2,0L);
                    if(bicarbonate<=0||bicarbonate+co2!=initial||actualAmounts.getOrDefault(BuiltinChemicalDefinitions.NA_PLUS,0L)!=initial)
                        throw new AssertionError("Production CO2 and alkali reactions lost carbon or did not share inventory: "+actualAmounts);
                } else {
                    long remaining=actualAmounts.getOrDefault("test:kinetic_a",0L),b=actualAmounts.getOrDefault("test:kinetic_b",0L),c=actualAmounts.getOrDefault("test:kinetic_c",0L);
                    if(remaining+b+c!=initial||remaining<0||b<0||c<0)throw new AssertionError("Competitive reaction lost inventory: "+actualAmounts);
                    if(scenario==1&&(b<initial/3||Math.abs(b-c)>2))throw new AssertionError("Shared reactant competition biased reaction order: "+actualAmounts);
                    if(scenario==2&&Math.abs(b-2.0*initial/3)>initial*.01)throw new AssertionError("Reversible kinetics failed to approach equilibrium: "+actualAmounts);
                    if(scenario==3&&b!=4)throw new AssertionError("Subquantum rate or checkpoint progress was lost/repeated: "+actualAmounts);
                    if(scenario==4&&(b<=c||300+(Double.longBitsToDouble(after.energyBits()[0])+.1*b)/4200<=350))
                        throw new AssertionError("Temperature-dependent competition or reaction heat feedback failed: "+actualAmounts);
                }
                System.out.println("GPU liquid reactions scenario "+scenario+" passed: "+actualAmounts);
            } finally {storage.close();}
        }
    }
    private static ChemicalRegistry kineticRegistry(int scenario){
        var source=mmm.fluid.TestFluidDefinitions.load().chemistry();
        var species=new java.util.ArrayList<>(source.species());
        for(String id:List.of("test:kinetic_a","test:kinetic_b","test:kinetic_c"))
            species.add(new ChemicalSpecies(id,Map.of("H",2,"O",1),0,ExactRational.parse("18/1000"),0xffeeeeee));
        var phases=new java.util.ArrayList<>(source.phases());
        for(int i=0;i<phases.size();i++)if(phases.get(i).id().equals(BuiltinChemicalDefinitions.AQUEOUS)){
            var phase=phases.get(i);var allowed=new java.util.ArrayList<>(phase.allowedSpecies());
            allowed.addAll(List.of("test:kinetic_a","test:kinetic_b","test:kinetic_c"));
            phases.set(i,new ChemicalPhaseDefinition(phase.id(),phase.materialId(),allowed,phase.defaultConcentrationMolPerCubicMeter()));
        }
        var rules=new java.util.ArrayList<>(source.reactions());
        rules.add(new ChemicalReaction("test:kinetic_forward",ChemicalReaction.Kind.FINITE_RATE,BuiltinChemicalDefinitions.AQUEOUS,
                Map.of("test:kinetic_a",1),Map.of("test:kinetic_b",1),ExactRational.ZERO,
                new ChemicalReaction.Kinetics(scenario==3?.1:2,scenario==2?1:0,(scenario==1||scenario==4)?40000:0,0,300)));
        if(scenario==1||scenario==4)rules.add(new ChemicalReaction("test:kinetic_competitor",ChemicalReaction.Kind.FINITE_RATE,BuiltinChemicalDefinitions.AQUEOUS,
                Map.of("test:kinetic_a",1),Map.of("test:kinetic_c",1),ExactRational.ZERO,new ChemicalReaction.Kinetics(2,0,0,0,298.15)));
        return new ChemicalRegistry(species,phases,rules,source.gasLiquidMassTransfers());
    }

}
