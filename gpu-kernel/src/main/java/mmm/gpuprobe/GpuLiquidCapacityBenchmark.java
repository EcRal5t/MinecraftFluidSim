package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/** Capacity repair cost on deliberately divergent preferred routes, with fixed resident inputs. */
public final class GpuLiquidCapacityBenchmark {
    private static final long Q=1L<<40;
    private static final int BATCH_ROUNDS=8;
    private GpuLiquidCapacityBenchmark() { }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int device=args.length>0?Integer.parseInt(args[0]):0;
        int[] grids=args.length>1?java.util.Arrays.stream(args[1].split(",")).mapToInt(Integer::parseInt).toArray():new int[]{32,64};
        String scene=args.length>2?args[2]:"all";
        int maxRounds=args.length>3?Integer.parseInt(args[3]):512;
        if(maxRounds<1||maxRounds>512)throw new IllegalArgumentException("Round limit must be within 1..512");
        boolean finalControl=args.length>4&&Boolean.parseBoolean(args[4]);
        boolean flowRatio=args.length>5&&Boolean.parseBoolean(args[5]);
        boolean failed=false;
        try(var context=new VulkanDeviceContext(device,true,true);var solver=new VulkanLiquidCapacity(context)) {
            System.out.println("Device: "+context.deviceName());
            System.out.println("Integer capacity repair only, quantum=2^-40, divergent preferred inputs; excludes material routes, inventory/MAC and full steps.");
            System.out.println("One solve per scene/grid, no warmup; uploads occur once before timing. Feedback reads status, rounds and phase; all final quotas are checked after timing.");
            System.out.println("scene,grid,status,rounds,wall_ms,gpu_ms,upload_B,readback_B,device_MiB");
            for(int grid:grids)for(boolean dam:new boolean[]{false,true}) {
                if(!scene.equals("all")&&!scene.equals(dam?"dam":"full"))continue;
                failed|=!measure(context,solver,grid,dam,maxRounds,finalControl,flowRatio);
            }
        }
        if(failed)throw new IllegalStateException("Capacity benchmark contains failed solves; inspect reported statuses");
    }

    private static boolean measure(VulkanDeviceContext context,VulkanLiquidCapacity solver,int grid,boolean dam,int maxRounds,boolean finalControl,boolean flowRatio) {
        var layout=new VulkanLiquidCapacityLayout(grid,grid,grid,dam?2:0,0,Math.max(8,context.storageOffsetAlignment()));
        var solveLayout=new VulkanLiquidCapacity.Layout(layout,layout.endOffset(),Math.max(8,context.storageOffsetAlignment()));
        if(solveLayout.endOffset()>context.maxStorageBufferRange())throw new IllegalArgumentException("Capacity arena exceeds device descriptor range");
        var initial=ByteBuffer.allocate(Math.toIntExact(solveLayout.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
        layout.initializeHeader(initial);solveLayout.initializeHeader(initial);
        long preferredViolations=0;
        for(int z=0;z<grid;z++)for(int y=0;y<grid;y++)for(int x=0;x<grid;x++) {
            int c=(z*grid+y)*grid+x;
            long k=quantity(grid,dam,x,y),lower=lower(grid,dam,x,y);
            putLong(initial,layout,VulkanLiquidCapacityLayout.Field.K,c,k);
            putLong(initial,layout,VulkanLiquidCapacityLayout.Field.LOWER,c,lower);
            putLong(initial,layout,VulkanLiquidCapacityLayout.Field.UPPER,c,dam?Q:k);
            putInt(initial,layout,VulkanLiquidCapacityLayout.Field.GROUP_ID,c,dam&&x>=grid/2?(z<grid/2?0:1):-1);
            int mask=(x>0?1:0)|(x+1<grid?2:0)|(y>0?4:0)|(y+1<grid?8:0)|(z>0?16:0)|(z+1<grid?32:0);
            putInt(initial,layout,VulkanLiquidCapacityLayout.Field.OPEN_NEIGHBORS,c,mask);
            long sent=0;
            if(!dam) {
                int slot=(y&1)==0?((x&1)==0?2:4):((x&1)==0?3:1);
                long amount=Q/16+(((c*0x9e3779b9L)>>>8)%7==0?1:0);
                putLong(initial,layout,VulkanLiquidCapacityLayout.Field.PREFERRED,7*c+slot,amount);sent=amount;
            } else if(k>0) {
                if(x+1<grid) {putLong(initial,layout,VulkanLiquidCapacityLayout.Field.PREFERRED,7*c+2,Q/8);sent+=Q/8;}
                if(y==grid/2-1&&y>0) {putLong(initial,layout,VulkanLiquidCapacityLayout.Field.PREFERRED,7*c+3,Q/8);sent+=Q/8;}
            }
            putLong(initial,layout,VulkanLiquidCapacityLayout.Field.PREFERRED,7*c,k-sent);
        }
        if(dam)for(int group=0;group<2;group++)
            putLong(initial,layout,VulkanLiquidCapacityLayout.Field.GROUP_CAPACITY,group,Q*grid*grid/128);
        // Count genuinely infeasible preferred receiver totals before uploading, outside measured work.
        for(int z=0;z<grid;z++)for(int y=0;y<grid;y++)for(int x=0;x<grid;x++) {
            int c=(z*grid+y)*grid+x;long received=getLong(initial,layout,VulkanLiquidCapacityLayout.Field.PREFERRED,7*c);
            for(int slot=1;slot<=6;slot++) {
                int axis=(slot-1)/2,side=(slot&1)==1?-1:1;
                int nx=x+(axis==0?side:0),ny=y+(axis==1?side:0),nz=z+(axis==2?side:0);
                if(nx<0||ny<0||nz<0||nx>=grid||ny>=grid||nz>=grid)continue;
                int donor=(nz*grid+ny)*grid+nx;
                received+=getLong(initial,layout,VulkanLiquidCapacityLayout.Field.PREFERRED,7*donor+((slot-1)^1)+1);
            }
            if(received<lower(grid,dam,x,y)||received>Q)preferredViolations++;
        }
        if(preferredViolations==0)throw new AssertionError("Capacity fixture has no preferred receiver violation");
        System.out.println("input scene="+(dam?"dam-gas-groups":"full-circulation")+" grid="+grid+" violated_receivers="+preferredViolations);
        try(var storage=new VulkanRegionStorage(context,solveLayout.endOffset(),1024*1024)) {
            storage.initialize(initial,0);
            try(var source=storage.capture();var candidate=storage.prepare(source)) {
                var before=storage.metrics();long start=System.nanoTime();
                var feedback=candidate.beginBatch((cmd,input,output,errors,staging,stack)-> {
                    solver.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                    solver.recordInitialize(cmd,solveLayout,maxRounds);
                    solver.recordIterations(cmd,solveLayout,Math.min(BATCH_ROUNDS,maxRounds),3);
                },layout.column(VulkanLiquidCapacityLayout.Field.CONTROL).offset(),12);
                int status=feedback.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0),rounds=feedback.feedback().getInt(4);
                int error=feedback.error();
                while(error==0&&status==VulkanLiquidCapacity.RUNNING&&rounds<maxRounds) {
                    int count=Math.min(BATCH_ROUNDS,maxRounds-rounds),phase=feedback.feedback().getInt(8);
                    feedback=candidate.continueBatch((cmd,input,output,errors,staging,stack)->solver.recordIterations(cmd,solveLayout,count,phase),
                            layout.column(VulkanLiquidCapacityLayout.Field.CONTROL).offset(),12);
                    error=feedback.error();status=feedback.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0);rounds=feedback.feedback().getInt(4);
                }
                if(error==0&&status==VulkanLiquidCapacity.FEASIBLE)
                    error=candidate.finishBatch((cmd,input,output,errors,staging,stack)->solver.recordFinish(cmd,solveLayout));
                long elapsed=System.nanoTime()-start;var after=storage.metrics();
                System.out.printf("%s,%d,%d,%d,%.3f,%.3f,%d,%d,%.1f%n",dam?"dam-gas-groups":"full-circulation",grid,status,rounds,
                        elapsed*1e-6,(after.gpuNanos()-before.gpuNanos())*1e-6,after.uploadBytes()-before.uploadBytes(),
                        after.readbackBytes()-before.readbackBytes(),after.deviceBytes()/1048576.0);
                if(finalControl) {
                    var column=layout.column(VulkanLiquidCapacityLayout.Field.CONTROL);
                    var values=ByteBuffer.allocate(Math.toIntExact(column.bytes())).order(ByteOrder.LITTLE_ENDIAN);
                    candidate.read(column.offset(),values);
                    var line=new StringBuilder("final_control=");
                    for(int j=0;j<column.count();j++)line.append(values.getInt(4*j)).append(j+1==column.count()?'\n':',');
                    System.out.print(line);
                }
                if(error!=0||status!=VulkanLiquidCapacity.FEASIBLE) {
                    System.out.println("FAILED scene="+(dam?"dam-gas-groups":"full-circulation")+" grid="+grid+" status="+status+" error="+error);
                    return false;
                }
                candidate.commit();
            }
            try(var result=storage.capture()) {
                var column=layout.column(VulkanLiquidCapacityLayout.Field.ACCEPTED);
                var accepted=ByteBuffer.allocate(Math.toIntExact(column.bytes())).order(ByteOrder.LITTLE_ENDIAN);
                storage.read(result,column.offset(),accepted);
                long moved=GpuLiquidCapacityReplay.verify(layout,initial,accepted,grid,grid,grid);
                if(flowRatio){long preferred=0;
                    for(int cell=0;cell<layout.cells();cell++)for(int slot=1;slot<7;slot++)preferred+=getLong(initial,layout,VulkanLiquidCapacityLayout.Field.PREFERRED,7*cell+slot);
                    System.out.println("transport preferred_quanta="+preferred+" accepted_quanta="+moved+" ratio="+(preferred==0?1.0:(double)moved/preferred));
                }
                System.out.println("All source quotas, receiver bounds and finite-group capacities passed; moved_quanta="+moved);
            }
            return true;
        }
    }

    private static long quantity(int grid,boolean dam,int x,int y) {return !dam?Q:x>=grid/2||y>=grid/2?0:y==grid/2-1?Q/2:Q;}
    private static long lower(int grid,boolean dam,int x,int y) {return !dam?Q:x<grid/2&&y<grid/2-1?Q:0;}
    private static void putInt(ByteBuffer out,VulkanLiquidCapacityLayout layout,VulkanLiquidCapacityLayout.Field field,int index,int value) {out.putInt(Math.toIntExact(layout.column(field).offset()+4L*index),value);}
    private static void putLong(ByteBuffer out,VulkanLiquidCapacityLayout layout,VulkanLiquidCapacityLayout.Field field,int index,long value) {out.putLong(Math.toIntExact(layout.column(field).offset()+8L*index),value);}
    private static long getLong(ByteBuffer in,VulkanLiquidCapacityLayout layout,VulkanLiquidCapacityLayout.Field field,int index) {return in.getLong(Math.toIntExact(layout.column(field).offset()+8L*index));}
}
