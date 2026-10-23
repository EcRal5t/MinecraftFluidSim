package mmm.gpuprobe;

import java.util.Arrays;
import java.util.Locale;

/** Continuous ambient liquid mechanical candidates, including device capacity and actual inventory transport. */
public final class GpuLiquidMechanicalBenchmark {
    private GpuLiquidMechanicalBenchmark() { }
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int device=args.length>0?Integer.parseInt(args[0]):0;
        int[] grids=Arrays.stream((args.length>1?args[1]:"32").split(",")).mapToInt(Integer::parseInt).toArray();
        int samples=args.length>2?Integer.parseInt(args[2]):10,warm=args.length>3?Integer.parseInt(args[3]):5;
        if(samples<1||warm<0)throw new IllegalArgumentException("Invalid measured or warmup step count");
        try(var context=new VulkanDeviceContext(device,true,true);var geometry=new VulkanLiquidGeometry(context);
            var projection=new VulkanLiquidProjection(context);var pressure=new VulkanPressureKernel(context,4096);
            var multigrid=new VulkanPressureMultigrid(context)) {
            System.out.println("Device: "+context.deviceName());
            System.out.println("Continuous ambient liquid: geometry/gravity/pressure -> capacity -> exact accepted cell volumes -> material routes -> quantities/gather -> MAC -> future geometry -> heat/exact ledgers -> commit.");
            System.out.println("Consecutive commits; explicit fixture reference tables, q=2^-40. Excludes finite gas, thermochemistry, runtime/backend adoption, world and presentation costs.");
            System.out.println("warmup_steps="+warm+" measured_steps="+samples);
            System.out.println("grid,steps,median_ms,max_ms,gpu_mean_ms,upload_B_per_step,readback_B_per_step,device_MiB,staging_MiB");
            for(int grid:grids)measure(context,geometry,projection,pressure,multigrid,grid,warm,samples);
        }
    }
    private static void measure(VulkanDeviceContext context,VulkanLiquidGeometry geometry,VulkanLiquidProjection projection,
                                VulkanPressureKernel pressure,VulkanPressureMultigrid multigrid,int grid,int warm,int samples) {
        var fixture=GpuLiquidGeometryBenchmark.fixture(grid,false);
        int cells=grid*grid*grid,stocks=2*cells;
        // This fixture explicitly uses no species and a zero reference for its caloric energy model.
        var definitions=new VulkanLiquidRegion.MechanicalDefinitions(stocks,0,4*stocks,4,-40,
                new int[]{0,1},new long[]{0},new double[]{0,0},new double[]{0,0},1);
        var parameters=new VulkanLiquidProjection.Parameters(.25f,.05f,9.81f,1e-5f,200);
        try(var region=new VulkanLiquidRegion(context,geometry,projection,pressure,multigrid,fixture.inventory(),fixture.momentum(),
                fixture.fixed(),fixture.occupied(),definitions)) {
            for(int step=0;step<warm;step++)region.advanceMechanical(parameters,512);
            var before=region.metrics();long[] times=new long[samples];
            for(int step=0;step<samples;step++) {
                long start=System.nanoTime();region.advanceMechanical(parameters,512);times[step]=System.nanoTime()-start;
            }
            var after=region.metrics();Arrays.sort(times);
            System.out.printf("%d,%d,%.3f,%.3f,%.3f,%.1f,%.1f,%.1f,%.1f%n",grid,samples,times[samples/2]*1e-6,times[samples-1]*1e-6,
                    (after.gpuNanos()-before.gpuNanos())*1e-6/samples,(double)(after.uploadBytes()-before.uploadBytes())/samples,
                    (double)(after.readbackBytes()-before.readbackBytes())/samples,after.deviceBytes()/1048576.0,after.stagingBytes()/1048576.0);
            System.out.println("final inventory_counts="+Arrays.toString(region.inventoryCounts())+" capacity_status_rounds="+Arrays.toString(region.capacityDiagnostics())
                    +" pressure_iterations_rms_status="+Arrays.toString(region.pressureDiagnostics()));
        }
    }
}
