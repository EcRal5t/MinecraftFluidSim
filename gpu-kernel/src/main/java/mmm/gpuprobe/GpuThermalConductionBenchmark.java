package mmm.gpuprobe;

import mmm.thermal.ThermalMaterialDefinition;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import static mmm.gpuprobe.VulkanRegionStorage.*;
import static mmm.gpuprobe.VulkanThermalConduction.Field.*;
import static org.lwjgl.vulkan.VK10.*;

/** Continuous caloric transport cost. Spatial owner sampling is measured by the region integration. */
public final class GpuThermalConductionBenchmark {
    private GpuThermalConductionBenchmark() { }
    public static void main(String[] args){
        Locale.setDefault(Locale.ROOT);
        int device=Integer.parseInt(args[0]),grid=Integer.parseInt(args[1]),warm=Integer.parseInt(args[2]),samples=Integer.parseInt(args[3]);
        boolean stiff=args.length>4&&args[4].equals("stiff");double h=stiff?1e-5:.5,dt=.05;
        var material=new ThermalMaterialDefinition("mmm:conduction_cost_water",ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water",
                293.15,0,.6,List.of(new ThermalMaterialDefinition.CaloricSegment(100,350,4180),new ThermalMaterialDefinition.CaloricSegment(350,1700,4300)));
        try(var context=new VulkanDeviceContext(device,true,true);var conduction=new VulkanThermalConduction(context);
            var pressure=new VulkanPressureKernel(context,4096);var ledger=new VulkanEnergyLedger(context)){
            int owners=grid*grid*grid,edges=3*(grid-1)*grid*grid+2*grid*grid;long alignment=Math.max(8,context.storageOffsetAlignment());
            var l=VulkanThermalConduction.layout(owners,owners,edges,VulkanThermalConduction.Tables.fromMaterials(List.of(material)),0,alignment,2*grid*grid);
            var accounting=VulkanEnergyLedger.layout(List.of(
                    new VulkanEnergyLedger.Stream(1,l.word(ENERGY_CHANGE),l.column(ENERGY_CHANGE).count(),2,1),
                    new VulkanEnergyLedger.Stream(1,l.word(RESERVOIR_CHANGE),l.column(RESERVOIR_CHANGE).count(),2,-1)),l.endOffset(),alignment);
            var initial=ByteBuffer.allocate(Math.toIntExact(accounting.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
            l.initializeHeaderAndTables(initial);accounting.initializeHeader(initial);
            initialize(initial,l,grid,h,material);
            try(var storage=new VulkanRegionStorage(context,accounting.endOffset(),1024*1024)){
                storage.initialize(initial,0);long[] times=new long[samples];Metrics before=null;int implicitSteps=0,totalNewton=0;
                System.out.printf("Device: %s; grid=%d h=%g m dt=%g s, %d actual owners, %d finite-volume contacts, segmented caloric water%n",context.deviceName(),grid,h,dt,owners,edges);
                for(int step=0;step<warm+samples;step++){
                    if(step==warm)before=storage.metrics();long begin=System.nanoTime();int iterations;
                    try(var capture=storage.capture();var candidate=storage.prepare(capture)){
                        var status=candidate.beginBatch((command,input,output,errors,staging,stack)->{
                            if(capture.generation()>0){
                                barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT,stack);
                                copy(command,output,l.column(TEMPERATURE).offset(),output,l.column(OWNER_INITIAL_T).offset(),8L*owners,stack);
                                barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                            }
                            var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                            conduction.rebindRanges(dst,err);pressure.rebindRanges(l.pressureRanges(dst,err));ledger.rebindRanges(new FlipKernelBindings.BufferRange(input),dst,err);
                            conduction.recordPrepare(command,l,dt);conduction.recordExplicit(command,l,dt);
                        },l.column(MODE).offset(),4);
                        if(status.error()!=0)throw new IllegalStateException("Conduction prepare error="+status.error());int mode=status.feedback().getInt(0);
                        iterations=mode==VulkanThermalConduction.IMPLICIT?conduction.solveImplicit(candidate,l,pressure,dt):0;
                        if(mode==VulkanThermalConduction.IMPLICIT)implicitSteps++;
                        int error=candidate.finishBatch((command,input,output,errors,staging,stack)->{
                            conduction.recordFinish(command,l,dt);ledger.record(command,accounting);
                        });
                        if(error!=0)throw new IllegalStateException("Conduction publication error="+error);candidate.commit();
                    }
                    totalNewton+=iterations;double wall=(System.nanoTime()-begin)*1e-6;
                    if(step>=warm)times[step-warm]=Math.round(wall*1e6);
                    System.out.printf("step=%d wall_ms=%.3f newton=%d%n",step,wall,iterations);
                }
                var after=storage.metrics();Arrays.sort(times);
                System.out.println("scene,grid,median_ms,max_ms,gpu_mean_ms,upload_B,readback_B,device_MiB,implicit_steps,newton_iterations");
                System.out.printf("%s,%d,%.3f,%.3f,%.3f,%d,%d,%.1f,%d,%d%n",stiff?"stiff":"normal",grid,times[samples/2]*1e-6,times[samples-1]*1e-6,
                        (after.gpuNanos()-before.gpuNanos())*1e-6/samples,(after.uploadBytes()-before.uploadBytes())/samples,
                        (after.readbackBytes()-before.readbackBytes())/samples,after.deviceBytes()/1048576.,implicitSteps,totalNewton);
            }
        }
    }
    private static void initialize(ByteBuffer b,VulkanThermalConduction.Layout l,int grid,double h,ThermalMaterialDefinition material){
        put(b,l,COUNTS,0,l.owners);put(b,l,COUNTS,1,l.terms);put(b,l,COUNTS,2,l.contacts);double mass=1000*h*h*h;int edge=0;
        for(int z=0;z<grid;z++)for(int y=0;y<grid;y++)for(int x=0;x<grid;x++){
            int row=(z*grid+y)*grid+x;double t=293.15+100.0*x/(grid-1);
            put(b,l,TERM_OFFSETS,row,row);put(b,l,TERM_MATERIAL,row,0);value(b,l,TERM_MASS,row,mass);
            value(b,l,OWNER_INITIAL_T,row,t);value(b,l,OWNER_ENERGY,row,mass*material.sensibleSpecificEnergyAt(t));
            for(int axis=0;axis<3;axis++)if((axis==0?x:axis==1?y:z)+1<grid){
                put(b,l,CONTACT_A,edge,row);put(b,l,CONTACT_B,edge,row+(axis==0?1:axis==1?grid:grid*grid));value(b,l,CONTACT_G,edge,material.conductivityWPerMeterK()*h);edge++;
            }
            if(x==0||x+1==grid){put(b,l,CONTACT_A,edge,row);put(b,l,CONTACT_B,edge,-1);value(b,l,CONTACT_G,edge,2*material.conductivityWPerMeterK()*h);value(b,l,CONTACT_BATH_T,edge,x==0?500:200);edge++;}
        }
        put(b,l,TERM_OFFSETS,l.owners,l.terms);if(edge!=l.contacts)throw new IllegalStateException("Conduction contact count differs");
    }
    private static void put(ByteBuffer b,VulkanThermalConduction.Layout l,VulkanThermalConduction.Field f,int i,int x){b.putInt(Math.toIntExact(l.column(f).offset()+4L*i),x);}
    private static void value(ByteBuffer b,VulkanThermalConduction.Layout l,VulkanThermalConduction.Field f,int i,double x){b.putDouble(Math.toIntExact(l.column(f).offset()+8L*i),x);}
}
