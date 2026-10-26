package mmm.gpuprobe;

import mmm.chemistry.*;
import mmm.fluid.*;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.granular.*;
import mmm.inventory.DeviceLiquidInventory;
import mmm.thermal.*;
import mmm.units.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Real saline inventory precipitates a new bed while transporting nonzero MAC momentum. */
public final class GpuSolidLiquidReactionsValidation {
    private GpuSolidLiquidReactionsValidation(){}
    public static void main(String[] args){
        var chemical=BuiltinChemicalDefinitions.REGISTRY;var granular=BuiltinGranularDefinitions.createRegistry();var gas=BuiltinGasDefinitions.createRegistry();
        int grid=3,cells=27,cell=13;double h=.5,gravity=9.81;long initialMass=10_000_000_000L,ions=1_000_000;
        var fluid=new FluidMaterialRegistry(List.of(FluidMaterial.withSurfaceTension("mmm:water",1000,"mmm:water",0,90)));
        var water=material("mmm:reactive_water",ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water",4200);
        var materials=new ArrayList<ThermalMaterialDefinition>();materials.add(water);var solids=new ArrayList<ThermalMaterialDefinition>();
        for(var m:granular.materials()){var value=material("mmm:thermal_"+m.id().substring(m.id().indexOf(':')+1),ThermalMaterialDefinition.CarrierKind.GRANULAR,m.id(),800);solids.add(value);materials.add(value);}
        var thermal=new ThermalRegistry(materials,List.of(),List.of(),List.of(),chemical.fingerprint(),granular.fingerprint(),gas.fingerprint());
        int[] offsets=new int[cells+1];for(int i=cell+1;i<offsets.length;i++)offsets[i]=1;
        int sodium=chemical.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS),chloride=chemical.speciesHandle(BuiltinChemicalDefinitions.CL_MINUS);
        int[] species=sodium<chloride?new int[]{sodium,chloride}:new int[]{chloride,sodium};
        var inventory=new DeviceLiquidInventory(offsets,new int[]{0},new int[]{255},DeviceDyadicArray.encode(List.of(SignedDyadic.of(.008))),
                new long[]{initialMass},new long[]{0},new int[]{1},new int[]{0,2},species,new long[]{ions,ions},new long[]{initialMass-580*ions});
        int nx=4*3*3,ny=3*4*3,nz=3*3*4;double[] px=new double[nx],py=new double[ny],pz=new double[nz];double[] velocity={2,-1,3};
        for(int axis=0;axis<3;axis++)for(int side=0;side<2;side++){int x=1+(axis==0?side:0),y=1+(axis==1?side:0),z=1+(axis==2?side:0);
            int sx=grid+(axis==0?1:0),sy=grid+(axis==1?1:0),face=(z*sy+y)*sx+x;(axis==0?px:axis==1?py:pz)[face]=.5*velocity[axis];}
        var momentum=new MacMomentumState(grid,grid,grid,px,py,pz);
        try(var context=new VulkanDeviceContext(args.length>0?Integer.parseInt(args[0]):0,true,true);var kernel=new VulkanSolidLiquidReactions(context);var ledgerKernel=new VulkanEnergyLedger(context)){
            long a=Math.max(8,context.storageOffsetAlignment());var liquid=new VulkanLiquidStateLayout(grid,grid,grid,1,64,16,a,1);
            var definition=new VulkanGranularStateLayout.Definitions(granular.fingerprint(),chemical.fingerprint(),granular.materials().stream().map(ParticulateMaterial::id).toList(),chemical.species().stream().map(ChemicalSpecies::id).toList());
            var solid=new VulkanGranularStateLayout(grid,grid,grid,0,8,128,definition,liquid.bytes(),a);
            var tables=VulkanReactionDefinitions.fromRegistries(chemical,granular,thermal,gas).layout(solid.endOffset(),a);
            long lr=align(tables.endOffset(),a),ls=align(lr+8,a),gr=align(ls+8L*chemical.species().size(),a),gs=align(gr+8L*solids.size(),a);
            var liquidCaloric=VulkanLiquidThermodynamics.layout(liquid,VulkanLiquidThermodynamics.tables(fluid,thermal),Math.toIntExact(lr/4),Math.toIntExact(ls/4),chemical.species().size(),gs+8L*solids.size()*chemical.species().size(),a);
            var solidCaloric=VulkanLiquidThermodynamics.layout(solid,VulkanLiquidThermodynamics.tables(solids),Math.toIntExact(gr/4),Math.toIntExact(gs/4),chemical.species().size(),liquidCaloric.endOffset(),a);
            var progress=new VulkanReactionProgressLayout(cells,128,128,List.of(),tables,solidCaloric.endOffset(),a);
            var phase=new VulkanSolidLiquidReactions.Layout(liquid,solid,tables,liquidCaloric,solidCaloric,progress,fluid,chemical,granular,h,gravity,-1,progress.endOffset(),a);
            var ledger=VulkanEnergyLedger.layout(phase.roundoffStreams(),phase.endOffset,a);var initial=ByteBuffer.allocate(Math.toIntExact(ledger.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
            liquid.importState(initial,inventory,momentum);solid.importState(initial,new VulkanGranularStateLayout.State(definition,List.of(),List.of(),List.of(),List.of()));tables.initialize(initial);
            liquidCaloric.initializeHeaderAndTables(initial);solidCaloric.initializeHeaderAndTables(initial);progress.initializeHeader(initial);phase.initialize(initial);ledger.initializeHeader(initial);
            try(var storage=new VulkanRegionStorage(context,ledger.endOffset(),1024*1024)){
                storage.initialize(initial,0);
                try(var source=storage.capture();var candidate=storage.prepare(source)){
                    var result=candidate.beginBatch((command,input,output,errors,staging,stack)->{
                        kernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));kernel.recordPrepare(command,phase);
                    },phase.feedbackOffset(),48);
                    var feedback=result.feedback().order(ByteOrder.LITTLE_ENDIAN);
                    if(result.error()!=0||feedback.getInt(0)!=2)throw new AssertionError("Solid/liquid prepare failed: error="+result.error()+", status="+feedback.getInt(0));
                    int error=candidate.finishBatch((command,input,output,errors,staging,stack)->{
                        kernel.recordPublish(command,phase);ledgerKernel.rebindRanges(new FlipKernelBindings.BufferRange(input),new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));ledgerKernel.record(command,ledger);
                    });if(error!=0)throw new AssertionError("Solid/liquid publication failed: "+error);candidate.commit();
                }
                var ldata=ByteBuffer.allocate(Math.toIntExact(liquid.bytes())).order(ByteOrder.LITTLE_ENDIAN);
                var gdata=ByteBuffer.allocate(Math.toIntExact(solid.bytes())).order(ByteOrder.LITTLE_ENDIAN);
                try(var capture=storage.capture()){storage.read(capture,0,ldata);storage.read(capture,solid.baseOffset(),gdata);}ldata.flip();gdata.flip();
                var after=liquid.exportState(ldata).inventory();var bed=solid.exportState(gdata).deposits();if(bed.size()!=1||bed.getFirst().inventory().solidVolumeSvq()<=0)throw new AssertionError("No new precipitate bed");
                var deposited=bed.getFirst().inventory();if(after.massUnits()[0]+deposited.massUnits()!=initialMass)throw new AssertionError("Phase mass lost");
                SignedDyadic removed=inventory.volumes().valueAt(0).subtract(after.volumes().valueAt(0));
                if(!removed.equals(SignedDyadic.of(deposited.solidVolumeSvq(),-24)))throw new AssertionError("Liquid/SVQ transfer is not exact");
                for(int s:new int[]{sodium,chloride}){long count=0;for(int i=0;i<after.speciesHandles().length;i++)if(after.speciesHandles()[i]==s)count+=after.speciesAmounts()[i];
                    for(int i=0;i<deposited.species().length;i++)if(deposited.species()[i]==s)count+=deposited.amounts()[i];if(count!=ions)throw new AssertionError("Phase species lost");}
                double oldK=.5*(4+1+9),newK=0.0;var finalMomentum=liquid.exportState(ldata.duplicate().clear()).momentum();double finalMass=Mass.kilograms(after.massUnits()[0]);
                for(double[] component:new double[][]{finalMomentum.copyX(),finalMomentum.copyY(),finalMomentum.copyZ()})for(double q:component)if(q!=0)newK+=q*q/finalMass;
                double bulk=(double)deposited.solidVolumeSvq()/(1-bed.getFirst().porosity()),oldPE=gravity*.75,newPE=gravity*(finalMass*.75+Mass.kilograms(deposited.massUnits())*(.5+.5*bulk*Math.scalb(1.0,-27)/(.5*.5)));
                double total=Double.longBitsToDouble(after.energyBits()[0])+deposited.internalEnergy()+newK+newPE;
                if(Math.abs(total-oldK-oldPE)>1e-8)throw new AssertionError("Phase U/K/PE did not close: "+total+" vs "+(oldK+oldPE));
                System.out.println("GPU solid/liquid: new bed, exact mass/species/SVQ, nonzero MAC transfer and U/K/PE passed; svq="+deposited.solidVolumeSvq());
            }
        }
    }
    private static ThermalMaterialDefinition material(String id,ThermalMaterialDefinition.CarrierKind kind,String carrier,double cv){return new ThermalMaterialDefinition(id,kind,carrier,300,0,0,List.of(new ThermalMaterialDefinition.CaloricSegment(1,2000,cv)));}
    private static long align(long x,long a){return (x+a-1)&-a;}
}
