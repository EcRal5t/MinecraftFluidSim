package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;
import static org.lwjgl.vulkan.VK10.*;

/** Continuous bed lifecycle through the resident canonical reassembler; no host inventory replay. */
public final class GpuGranularTransitionsBenchmark {
    private GpuGranularTransitionsBenchmark(){}
    public static void main(String[] args){
        Locale.setDefault(Locale.ROOT);int device=args.length>0?Integer.parseInt(args[0]):0;
        try(var context=new VulkanDeviceContext(device,true,true);var lifecycle=new VulkanGranularTransitions(context);var geometry=new VulkanGranularGeometry(context)){
            int n=8,cells=n*n*n;double h=.5,dt=.05;long a=Math.max(8,context.storageOffsetAlignment());
            var fixture=GpuLiquidGeometryBenchmark.fixture(n,false);var liquid=VulkanLiquidStateLayout.forState(fixture.inventory(),fixture.momentum(),a);
            var registry=mmm.granular.BuiltinGranularDefinitions.createRegistry();var ids=registry.materials().stream().map(mmm.granular.ParticulateMaterial::id).toList();
            var state=new VulkanGranularStateLayout(n,n,n,16,32,16,new VulkanGranularStateLayout.Definitions(registry.fingerprint(),"",ids,List.of()),liquid.bytes(),a);
            long cursor=state.endOffset();var coherent=new VulkanLiquidStateLayout.Column(align(cursor,a),cells,8);cursor=coherent.offset()+coherent.bytes();
            var masks=new VulkanLiquidStateLayout.Column(align(cursor,a),cells,4);cursor=masks.offset()+masks.bytes();
            var occupancy=new VulkanGranularGeometry.Layout(state,coherent,cursor,a);cursor=occupancy.endOffset();
            var cellData=new VulkanLiquidStateLayout.Column(align(cursor,a),4*cells,8);cursor=cellData.offset()+cellData.bytes();
            int faceCount=3*(n+1)*n*n;var faces=new VulkanLiquidStateLayout.Column(align(cursor,a),4*faceCount,8);cursor=faces.offset()+faces.bytes();
            var mu=new VulkanLiquidStateLayout.Column(align(cursor,a),cells,8);cursor=mu.offset()+mu.bytes();
            var transitions=new VulkanGranularTransitions.Layout(state,occupancy,cellData,faces,liquid,masks,mu,cursor,a);
            var initial=ByteBuffer.allocate(Math.toIntExact(transitions.endOffset())).order(ByteOrder.LITTLE_ENDIAN);liquid.importState(initial,fixture.inventory(),fixture.momentum());
            occupancy.initializeHeader(initial);initial.duplicate().position(Math.toIntExact(transitions.header.offset())).put(transitions.headerBytes());
            initial.duplicate().position(Math.toIntExact(transitions.column(VulkanGranularTransitions.Field.MATERIALS).offset())).put(transitions.materialBytes(registry));
            int sand=registry.materialHandle(mmm.granular.BuiltinGranularDefinitions.FINE_SAND);long svq=137258;
            double m=registry.material(sand).referenceDensity()*svq/134217728.0;
            var particle=new VulkanGranularStateLayout.Particle(new VulkanGranularStateLayout.Row(sand,svq,mmm.units.Mass.fromKilograms(m),false,0,new int[0],new long[0],m*800*293.15),
                    .75,.0625,.75,0,0,0,0,0,new int[8],new long[8]);
            long bedVolume=8_000_000;double bm=registry.material(sand).referenceDensity()*bedVolume/134217728.0;int bedCell=(5*n)*n+5;
            var bed=new VulkanGranularStateLayout.Deposit(new VulkanGranularStateLayout.Row(sand,bedVolume,mmm.units.Mass.fromKilograms(bm),false,0,new int[0],new long[0],bm*800*293.15),bedCell,.38f);
            state.importState(initial,new VulkanGranularStateLayout.State(state.definitions(),List.of(particle),List.of(bed),List.of(),List.of()));
            for(int c=0;c<cells;c++){int off=Math.toIntExact(cellData.offset()+32L*c);initial.putDouble(off,1).putDouble(off+8,125).putDouble(off+16,1);initial.putDouble(Math.toIntExact(mu.offset()+8L*c),.001);}
            var still=faceData(faceCount,0);var forced=faceData(faceCount,1);initial.duplicate().position(Math.toIntExact(faces.offset())).put(still.duplicate());
            try(var storage=new VulkanRegionStorage(context,transitions.endOffset(),1024*1024)){
                storage.initialize(initial,0);int settled=0,woken=0,relaxed=0;
                System.out.println("Device: "+context.deviceName());System.out.println("step,active,deposits,settled,woken,relaxed,wall_ms,error");
                for(int step=0;step<7;step++){
                    final boolean force=step==3;try(var source=storage.capture();var candidate=storage.prepare(source)){
                        long start=System.nanoTime();var feedback=candidate.beginBatch(force?forced.remaining():0,(cmd,input,output,errors,staging,stack)->{
                            var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                            geometry.rebindRanges(arena,err);lifecycle.rebindRanges(arena,err);
                            if(force){vkCmdUpdateBuffer(cmd,output.handle(),faces.offset(),forced.duplicate());VulkanRegionStorage.barrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}
                            geometry.recordSupports(cmd,occupancy,h);geometry.recordOccupancy(cmd,occupancy);lifecycle.record(cmd,transitions,output,h,dt,9.81);
                        },transitions.column(VulkanGranularTransitions.Field.CONTROL).offset(),24);
                        var control=feedback.feedback().order(ByteOrder.LITTLE_ENDIAN);if(feedback.error()!=0)throw new IllegalStateException("Granular lifecycle error="+feedback.error()+", P/D/S="+control.getInt(0)+"/"+control.getInt(4)+"/"+control.getInt(8));
                        int error=candidate.finishBatch((cmd,input,output,errors,staging,stack)->{});if(error!=0)throw new IllegalStateException("Granular lifecycle finish="+error);candidate.commit();
                        settled+=control.getInt(12);woken+=control.getInt(16);relaxed+=control.getInt(20);
                        System.out.printf("%d,%d,%d,%d,%d,%d,%.3f,%d%n",step,control.getInt(0),control.getInt(4),control.getInt(12),control.getInt(16),control.getInt(20),(System.nanoTime()-start)*1e-6,error);
                    }
                }
                try(var source=storage.capture()){
                    var data=ByteBuffer.allocate(Math.toIntExact(state.bytes()));storage.read(source,state.baseOffset(),data);var result=state.exportState(data.flip());
                    long finalVolume=0,finalMass=0;for(var p:result.particles()){finalVolume=Math.addExact(finalVolume,p.inventory().solidVolumeSvq());finalMass=Math.addExact(finalMass,p.inventory().massUnits());}
                    for(var d:result.deposits()){finalVolume=Math.addExact(finalVolume,d.inventory().solidVolumeSvq());finalMass=Math.addExact(finalMass,d.inventory().massUnits());}
                    if(finalVolume!=svq+bedVolume||finalMass!=particle.inventory().massUnits()+bed.inventory().massUnits()||settled==0||woken==0||relaxed==0||result.dynamics().logicalStep()!=7)
                        throw new IllegalStateException("Granular lifecycle did not complete or changed its exact inventory");
                    result.toSnapshot(n,n,n,h);System.out.printf("Continuous lifecycle complete: settled=%d,woken=%d,relaxed=%d,SVQ=%d,massUnits=%d%n",settled,woken,relaxed,finalVolume,finalMass);
                }
            }
        }
    }
    private static ByteBuffer faceData(int count,double velocity){var b=ByteBuffer.allocateDirect(32*count).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<count;i++)b.putDouble(125).putDouble(velocity).putDouble(1).putDouble(1);return b.flip();}
    private static long align(long v,long a){return (v+a-1)&-a;}
}
