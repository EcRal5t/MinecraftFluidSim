package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Fixed-source cost of actual spatial search, contact response, CCD and trial support/occupancy. */
public final class GpuGranularMotionBenchmark {
    private GpuGranularMotionBenchmark(){}
    public static void main(String[] args){
        Locale.setDefault(Locale.ROOT);int device=args.length>0?Integer.parseInt(args[0]):0;
        int grid=args.length>1?Integer.parseInt(args[1]):64;
        int[] counts=args.length>2?Arrays.stream(args[2].split(",")).mapToInt(Integer::parseInt).toArray():new int[]{256,1536};
        int warmup=args.length>3?Integer.parseInt(args[3]):5,samples=args.length>4?Integer.parseInt(args[4]):10;
        if(grid<8||warmup<0||samples<1)throw new IllegalArgumentException("Invalid contact cost dimensions");
        try(var context=new VulkanDeviceContext(device,true,true);var contacts=new VulkanGranularContacts(context);
            var motion=new VulkanGranularMotion(context);var geometry=new VulkanGranularGeometry(context)){
            System.out.println("Device: "+context.deviceName());
            System.out.println("Fixed-source granular spatial contacts, compression/rebound/friction, swept positions, separation and integer support/occupancy; excludes pressure, liquid displacement, gas, common energy and production transfer.");
            System.out.println("grid,particles,contacts,median_ms,max_ms,gpu_mean_ms,upload_B,readback_B,device_MiB");
            for(int count:counts)measure(context,contacts,motion,geometry,grid,count,warmup,samples);
        }
    }
    private static void measure(VulkanDeviceContext context,VulkanGranularContacts contacts,VulkanGranularMotion motion,
                                VulkanGranularGeometry geometry,int grid,int count,int warmup,int samples){
        if(count<1)throw new IllegalArgumentException("Empty granular benchmark");
        long alignment=Math.max(8,context.storageOffsetAlignment());
        var state=new VulkanGranularStateLayout(grid,grid,grid,count,0,0,new VulkanGranularStateLayout.Definitions("fixture","fixture",List.of("sand"),List.of()),0,alignment);
        var finalK=new VulkanLiquidStateLayout.Column(state.endOffset(),count,8);
        var finalY=new VulkanLiquidStateLayout.Column(align(finalK.offset()+finalK.bytes(),alignment),count,8);
        var masks=new VulkanLiquidStateLayout.Column(align(finalY.offset()+finalY.bytes(),alignment),state.cells(),4);
        var coherent=new VulkanLiquidStateLayout.Column(align(masks.offset()+masks.bytes(),alignment),state.cells(),8);
        var contact=new VulkanGranularContacts.Layout(state,finalK,32*count,align(coherent.offset()+coherent.bytes(),alignment),alignment);
        var movement=new VulkanGranularMotion.Layout(state,contact,finalY,Math.toIntExact(masks.offset()/4),contact.endOffset(),alignment);
        var occupancy=new VulkanGranularGeometry.Layout(state,coherent,movement.endOffset(),alignment);
        var initial=ByteBuffer.allocate(Math.toIntExact(occupancy.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
        contact.initializeHeader(initial);initial.duplicate().position(Math.toIntExact(movement.header.offset())).put(movement.headerBytes());occupancy.initializeHeader(initial);
        int material=movement.word(VulkanGranularMotion.Field.MATERIAL)*4;
        initial.putDouble(material,.3).putDouble(material+8,.6).putDouble(material+16,.4);
        initial.putInt(Math.toIntExact(state.column(VulkanGranularStateLayout.Field.COUNTS).offset()),count);
        double h=.5,dt=.01;int width=Math.min(grid-4,(int)Math.ceil(Math.sqrt(count)));
        for(int i=0;i<count;i++){
            // Touching finite-mass spheres form an actual horizontal contact network on the bottom wall.
            double x=1.0+(i%width)*.125,y=.0625,z=1.0+(i/width)*.125;
            if(z>=grid*h-.25||x>=grid*h-.25)throw new IllegalArgumentException("Particle fixture exceeds region");
            int a=state.word(VulkanGranularStateLayout.Field.POSITION)*4+24*i;initial.putDouble(a,x).putDouble(a+8,y).putDouble(a+16,z);
            a=state.word(VulkanGranularStateLayout.Field.VELOCITY)*4+24*i;initial.putDouble(a,(i%2==0?.05:-.05)).putDouble(a+8,-.1).putDouble(a+16,.03);
            initial.putLong(state.word(VulkanGranularStateLayout.Field.SOLID_VOLUME_SVQ)*4+8*i,137258L);
            initial.putLong(state.word(VulkanGranularStateLayout.Field.MASS_UNITS)*4+8*i,272500000000L);
        }
        try(var storage=new VulkanRegionStorage(context,occupancy.endOffset(),1024*1024)){
            storage.initialize(initial,0);long[] elapsed=new long[samples];long gpu=0,upload=0,readback=0;int actualContacts=0;
            try(var source=storage.capture()){
                for(int sample=-warmup;sample<samples;sample++)try(var candidate=storage.prepare(source)){
                    var before=storage.metrics();long start=System.nanoTime();
                    var feedback=candidate.beginBatch((cmd,input,output,errors,staging,stack)->{
                        var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                        motion.rebindRanges(arena,err);contacts.rebindRanges(arena,err);geometry.rebindRanges(arena,err);
                        motion.recordContacts(cmd,movement,h,dt);contacts.recordPrepare(cmd,contact);
                        contacts.recordCompression(cmd,contact,16);contacts.recordRebound(cmd,contact,16);
                        motion.recordTrialPositions(cmd,movement,h,dt);motion.recordPublishPositions(cmd,movement,h);
                        geometry.recordSupports(cmd,occupancy,h);geometry.recordOccupancy(cmd,occupancy);
                    },contact.column(VulkanGranularContacts.Field.CONTROL).offset(),12);
                    var control=feedback.feedback().order(ByteOrder.LITTLE_ENDIAN);actualContacts=control.getInt(0);
                    if(feedback.error()!=0||control.getInt(4)>contact.contactCapacity()||control.getInt(8)!=0)
                        throw new IllegalStateException("Granular trial failed: error="+feedback.error()+", required="+control.getInt(4)+", retry="+control.getInt(8));
                    int error=candidate.finishBatch((cmd,input,output,errors,staging,stack)->{});
                    if(error!=0)throw new IllegalStateException("Granular finish error="+error);
                    long time=System.nanoTime()-start;var after=storage.metrics();
                    if(sample>=0){elapsed[sample]=time;gpu+=after.gpuNanos()-before.gpuNanos();upload+=after.uploadBytes()-before.uploadBytes();readback+=after.readbackBytes()-before.readbackBytes();}
                }
            }
            Arrays.sort(elapsed);System.out.printf("%d,%d,%d,%.3f,%.3f,%.3f,%.1f,%.1f,%.1f%n",grid,count,actualContacts,
                    elapsed[samples/2]*1e-6,elapsed[samples-1]*1e-6,gpu*1e-6/samples,(double)upload/samples,(double)readback/samples,storage.metrics().deviceBytes()/1048576.0);
        }
    }
    private static long align(long v,long a){return (v+a-1)&-a;}
}
