package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import mmm.thermal.ThermalMaterialDefinition;
import static mmm.gpuprobe.VulkanRegionStorage.*;
import static org.lwjgl.vulkan.VK10.*;

/** Continuous real liquid/gas/active/deposit inventory conduction through spatial contacts. */
public final class GpuThermalContactsBenchmark {
    private GpuThermalContactsBenchmark(){}
    private record Tail(VulkanThermalConduction.Layout conduction,VulkanThermalContacts.Layout contacts,long bytes){}
    public static void main(String[] args){
        Locale.setDefault(Locale.ROOT);int device=args.length>0?Integer.parseInt(args[0]):0,grid=args.length>1?Integer.parseInt(args[1]):8;
        int warm=args.length>2?Integer.parseInt(args[2]):1,samples=args.length>3?Integer.parseInt(args[3]):3;
        if(grid<4||warm<0||samples<1)throw new IllegalArgumentException("Invalid thermal inventory benchmark dimensions");
        try(var context=new VulkanDeviceContext(device,true,true);var liquidGeometry=new VulkanLiquidGeometry(context);var totalsKernel=new VulkanLiquidCellVolumes(context);
            var gasGeometryKernel=new VulkanGasGeometry(context);var gasEOS=new VulkanGasThermodynamics(context);var caloric=new VulkanLiquidThermodynamics(context);
            var contacts=new VulkanThermalContacts(context);var conduction=new VulkanThermalConduction(context);var pressure=new VulkanPressureKernel(context,4096)){
            long a=Math.max(8,context.storageOffsetAlignment());int cells=grid*grid*grid;double h=.5;
            var fixture=GpuLiquidGeometryBenchmark.fixture(grid,true);var old=fixture.inventory();var mass=old.massUnits();var energy=old.energyBits();
            // The shared geometry fixture is authored at h=.25; these quantities use the actual h=.5 volume.
            for(int i=0;i<mass.length;i++){mass[i]*=8;energy[i]=Double.doubleToRawLongBits(Double.longBitsToDouble(energy[i])*8);}
            var inventory=new mmm.inventory.DeviceLiquidInventory(old.cellOffsets(),old.materials(),old.supportMasks(),old.volumes(),mass,energy,old.mixturePresent(),old.speciesOffsets(),old.speciesHandles(),old.speciesAmounts(),old.massResidualUnits());
            var liquid=VulkanLiquidStateLayout.forState(inventory,fixture.momentum(),a);var geometry=GpuGasGeometryBenchmark.geometry(liquid,a);
            var totals=VulkanLiquidCellVolumes.layout(liquid,2*cells,-40,geometry.endOffset(),a);
            var gasGeometry=VulkanGasGeometry.layout(totals,word(geometry.masks()),word(geometry.occupied()),word(geometry.cells()),word(geometry.planes()),word(geometry.micro()),true,h,totals.endOffset(),a);
            var definitions=GpuGasGeometryBenchmark.gasDefinitions();var gas=new VulkanGasStateLayout(grid,grid,grid,cells,2*cells,2*cells,definitions.definitions(),gasGeometry.endOffset(),a);
            var gasTables=VulkanGasThermodynamics.tables(gas.definitions(),definitions.gas(),definitions.thermal(),mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY);
            var gasThermal=VulkanGasThermodynamics.layout(gas,gasTables,Math.toIntExact(gas.endOffset()/4),gas.endOffset()+8L*cells,a);
            var materials=new java.util.ArrayList<ThermalMaterialDefinition>();
            materials.add(material("water",ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water",4200,.6));
            materials.add(material("oil",ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:oil",4200,.15));
            for(String id:gas.definitions().componentIds())materials.add(definitions.thermal().material(definitions.thermal().materialHandle(ThermalMaterialDefinition.CarrierKind.GAS,id)));
            // Use finite nonzero gas conductivity in this declared fixture; caloric curves remain unchanged.
            for(int i=2;i<4;i++){var m=materials.get(i);materials.set(i,new ThermalMaterialDefinition(m.id(),m.carrierKind(),m.carrierId(),m.referenceTemperatureK(),m.referenceSpecificEnergyJPerKg(),.026,m.caloricSegments()));}
            materials.add(material("sand",ThermalMaterialDefinition.CarrierKind.GRANULAR,"mmm:fine_sand",800,1.2));
            long[] cursor={gasThermal.endOffset()};var lr=column(cursor,2,8,a);var ls=column(cursor,2,8,a);
            var liquidThermal=VulkanLiquidThermodynamics.layout(liquid,VulkanLiquidThermodynamics.tables(materials.subList(0,2)),word(lr),word(ls),1,cursor[0],a);
            var solid=new VulkanGranularStateLayout(grid,grid,grid,1,1,0,new VulkanGranularStateLayout.Definitions("fixture","",List.of("mmm:fine_sand"),List.of()),liquidThermal.endOffset(),a);
            cursor[0]=solid.endOffset();var sr=column(cursor,1,8,a);var ss=column(cursor,1,8,a);
            var solidThermal=VulkanLiquidThermodynamics.layout(solid,VulkanLiquidThermodynamics.tables(materials.subList(4,5)),word(sr),word(ss),1,cursor[0],a);
            cursor[0]=solidThermal.endOffset();var bath=column(cursor,6*cells,8,a);long sourceEnd=cursor[0];
            var input=new VulkanThermalContacts.Inputs(liquid,liquidThermal,gas,gasThermal,gasGeometry,solid,solidThermal,word(geometry.masks()),word(bath),-1,h);
            var mapping=new VulkanThermalContacts.Materials(new int[]{0,1},new int[]{2,3},new int[]{4},materials.stream().mapToDouble(ThermalMaterialDefinition::conductivityWPerMeterK).toArray());
            var tables=VulkanThermalConduction.Tables.fromMaterials(materials);Tail tail=tail(input,mapping,tables,1,sourceEnd,a);
            var initial=ByteBuffer.allocate(Math.toIntExact(tail.bytes())).order(ByteOrder.LITTLE_ENDIAN);liquid.importState(initial,inventory,fixture.momentum());totals.initializeHeader(initial);gasGeometry.initializeHeader(initial);gasThermal.initializeHeaderAndTables(initial);
            liquidThermal.initializeHeaderAndTables(initial);solidThermal.initializeHeaderAndTables(initial);
            initial.putDouble(Math.toIntExact(lr.offset()),4200*293.15).putDouble(Math.toIntExact(lr.offset()+8),4200*293.15).putDouble(Math.toIntExact(sr.offset()),800*293.15);
            int c1=grid+1,c2=c1+1,c3=2*grid+2;long q=1024;double kg=2600.0*q/(1L<<27);
            var activeRow=new VulkanGranularStateLayout.Row(0,2*q,2*mmm.units.Mass.fromKilograms(kg),false,0,new int[0],new long[0],2*kg*800*450);
            var depositRow=new VulkanGranularStateLayout.Row(0,q,mmm.units.Mass.fromKilograms(kg),false,0,new int[0],new long[0],kg*800*240);
            int[] support={c1,c2,-1,-1,-1,-1,-1,-1};long[] quotas={q,q,0,0,0,0,0,0};
            solid.importState(initial,new VulkanGranularStateLayout.State(solid.definitions(),List.of(new VulkanGranularStateLayout.Particle(activeRow,1,.75,.25,0,0,0,0,0,support,quotas)),List.of(new VulkanGranularStateLayout.Deposit(depositRow,c3,.38f)),List.of(),List.of()));
            for(int c=0;c<cells;c++){initial.putInt(Math.toIntExact(geometry.masks().offset()+4L*c),fixture.fixed().mask(c));long occupied=fixture.occupied()[c]+(c==c1||c==c2||c==c3?q:0);initial.putLong(Math.toIntExact(geometry.occupied().offset()+8L*c),occupied);
                for(int direction=0;direction<6;direction++)initial.putDouble(Math.toIntExact(bath.offset()+8L*(6*c+direction)),c%grid==0&&direction==0?330:Double.NaN);}
            tail.conduction().initializeHeaderAndTables(initial);tail.contacts().initializeHeaderAndTables(initial);
            try(var storage=new VulkanRegionStorage(context,tail.bytes(),1024*1024)){
                storage.initialize(initial,0);try(var source=storage.capture()){GpuGasGeometryBenchmark.advance(storage,source,liquidGeometry,totalsKernel,gasGeometryKernel,gasEOS,liquid,geometry,totals,gasGeometry,gasThermal,true,false,true);}
                int nodes;try(var source=storage.capture()){nodes=GpuGasGeometryBenchmark.seed(storage,source,initial,gas,gasGeometry,definitions);}
                storage.initialize(initial,1);try(var source=storage.capture()){GpuGasGeometryBenchmark.advance(storage,source,liquidGeometry,totalsKernel,gasGeometryKernel,gasEOS,liquid,geometry,totals,gasGeometry,gasThermal,true,true,true);}
                long[] elapsed=new long[samples];VulkanRegionStorage.Metrics before=null;boolean upload=false;int growth=0;int[] counts=new int[4];
                for(int step=0;step<warm+samples;step++){
                    if(step==warm)before=storage.metrics();long start=System.nanoTime();try(var source=storage.capture()){
                        while(true){int[] required=advance(storage,source,contacts,conduction,pressure,caloric,gasEOS,input,tail,sourceEnd,upload);
                            if(required[0]==0){counts=Arrays.copyOfRange(required,1,5);break;}
                            if(required[4]<=tail.conduction().contacts)throw new IllegalStateException("Thermal contact requirement did not grow");
                            tail=tail(input,mapping,tables,Math.multiplyExact(required[4],2),sourceEnd,a);upload=true;growth++;
                        }
                    }upload=false;if(step>=warm)elapsed[step-warm]=System.nanoTime()-start;
                }
                var after=storage.metrics();Arrays.sort(elapsed);System.out.println("Device: "+context.deviceName());
                System.out.printf("grid=%d gas_nodes=%d owners_terms_samples_contacts=%s growths=%d median_ms=%.3f max_ms=%.3f gpu_ms=%.3f upload_B=%d readback_B=%d device_MiB=%.1f%n",grid,nodes,Arrays.toString(counts),growth,elapsed[samples/2]*1e-6,elapsed[samples-1]*1e-6,(after.gpuNanos()-before.gpuNanos())*1e-6/samples,(after.uploadBytes()-before.uploadBytes())/samples,(after.readbackBytes()-before.readbackBytes())/samples,after.deviceBytes()/1048576.);
                if(counts[0]!=inventory.stockCount()+nodes+2||counts[2]<=counts[0])throw new AssertionError("Spatial samples did not share unique inventory owners");
                try(var result=storage.capture()){var out=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);storage.read(result,solid.column(VulkanGranularStateLayout.Field.INTERNAL_ENERGY).offset(),out);System.out.printf("active_U_before=%.9g after=%.9g committed_steps=%d%n",activeRow.internalEnergy(),out.getDouble(0),warm+samples);}
            }
        }
    }
    private static int[] advance(VulkanRegionStorage storage,VulkanRegionStorage.Capture source,VulkanThermalContacts contacts,VulkanThermalConduction conduction,
                                 VulkanPressureKernel pressure,VulkanLiquidThermodynamics caloric,VulkanGasThermodynamics gasEOS,VulkanThermalContacts.Inputs input,Tail t,long sourceEnd,boolean upload){
        double dt=.05;try(var candidate=storage.prepareReallocated(source,t.bytes())){
            var writes=new java.util.ArrayList<VulkanGasThermodynamics.TableChunk>();if(upload){writes.add(new VulkanGasThermodynamics.TableChunk(t.conduction().header.offset(),t.conduction().headerBytes()));writes.addAll(t.conduction().tableChunks());writes.add(new VulkanGasThermodynamics.TableChunk(t.contacts().header.offset(),t.contacts().headerBytes()));writes.addAll(t.contacts().tableChunks());}
            long bytes=writes.stream().mapToLong(x->x.bytes().remaining()).sum();
            var status=candidate.beginBatch(bytes,(command,in,out,errors,staging,stack)->{
                copy(command,in,0,out,0,sourceEnd,stack);if(upload)for(var w:writes)vkCmdUpdateBuffer(command,out.handle(),w.offset(),w.bytes());else copy(command,in,sourceEnd,out,sourceEnd,t.bytes()-sourceEnd,stack);
                barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                var arena=new FlipKernelBindings.BufferRange(out);var err=new FlipKernelBindings.BufferRange(errors);caloric.rebindRanges(arena,err);gasEOS.rebindRanges(arena,err);contacts.rebindRanges(arena,err);conduction.rebindRanges(arena,err);pressure.rebindRanges(t.conduction().pressureRanges(arena,err));
                caloric.record(command,input.liquidThermal());caloric.record(command,input.granularThermal());gasEOS.record(command,input.gasThermal());contacts.recordPrepare(command,t.contacts());
            },t.contacts().column(VulkanThermalContacts.Field.REQUIREMENTS).offset(),16);
            int[] required=new int[5];required[0]=status.error();for(int i=0;i<4;i++)required[i+1]=status.feedback().getInt(4*i);if(status.error()==64)return required;if(status.error()!=0)throw new IllegalStateException("Thermal sampling error="+status.error());
            status=candidate.continueBatch((command,in,out,errors,staging,stack)->{conduction.recordPrepare(command,t.conduction(),dt);conduction.recordExplicit(command,t.conduction(),dt);},t.conduction().column(VulkanThermalConduction.Field.MODE).offset(),4);
            if(status.error()!=0)throw new IllegalStateException("Thermal explicit error="+status.error());if(status.feedback().getInt(0)==VulkanThermalConduction.IMPLICIT)conduction.solveImplicit(candidate,t.conduction(),pressure,dt);
            int error=candidate.finishBatch((command,in,out,errors,staging,stack)->{conduction.recordFinish(command,t.conduction(),dt);contacts.recordPublish(command,t.contacts());});if(error!=0)throw new IllegalStateException("Thermal finish error="+error);candidate.commit();return required;
        }
    }
    private static Tail tail(VulkanThermalContacts.Inputs input,VulkanThermalContacts.Materials materials,VulkanThermalConduction.Tables tables,int edges,long base,long a){var c=VulkanThermalConduction.layout(input.owners(),input.terms(),edges,tables,base,a);var s=VulkanThermalContacts.layout(input,materials,c,c.endOffset(),a);return new Tail(c,s,s.endOffset());}
    private static ThermalMaterialDefinition material(String id,ThermalMaterialDefinition.CarrierKind kind,String carrier,double cp,double k){return new ThermalMaterialDefinition("mmm:contacts_"+id,kind,carrier,293.15,cp*293.15,k,List.of(new ThermalMaterialDefinition.CaloricSegment(100,1500,cp)));}
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width,long a){long start=(cursor[0]+a-1)&-a;var c=new VulkanLiquidStateLayout.Column(start,count,width);cursor[0]=start+c.bindingBytes();return c;}
}
