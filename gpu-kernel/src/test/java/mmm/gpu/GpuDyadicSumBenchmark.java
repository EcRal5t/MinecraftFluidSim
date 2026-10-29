package mmm.gpu;

import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

/** Measures exact inventory remainder generation and compact limb allocation on resident buffers. */
public final class GpuDyadicSumBenchmark {
    private GpuDyadicSumBenchmark() { }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int device = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int samples = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        if (samples < 1) throw new IllegalArgumentException("At least one sample required");
        try (var context = new VulkanDeviceContext(device, true, true);
             var sum = new VulkanDyadicSum(context)) {
            System.out.println("Device: " + context.deviceName());
            System.out.println("Exact dyadic remainders + GPU prefix allocation only; excludes transport capacity and material settlement.");
            System.out.println("scene,operations,median_ms,max_ms,gpu_mean_ms,upload_B_per_step,readback_B_per_step,device_MiB");
            for (boolean tails : new boolean[]{false, true}) measure(context, sum, tails, samples);
        }
    }

    private static void measure(VulkanDeviceContext context, VulkanDyadicSum sum, boolean tails, int samples) {
        int n = tails ? 8192 : 524288, terms = 7*n, results = terms + 35*n,
                counts = results + 8*n, offsets = counts+n, limbs = offsets+n+1,
                capacity = (tails ? 34 : 2)*n, scan = limbs+capacity;
        int bytes = Math.toIntExact(4L*scan+VulkanPrefixScan.workspaceBytes(n));
        var layout = new VulkanDyadicSum.Layout(n,terms,results,counts,offsets,limbs,capacity,scan);
        var initial = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int operation=0; operation<n; operation++) for (int term=0; term<7; term++) {
            int word=7*operation+term, address=4*(terms+35*operation+5*term);
            int amount=term==0 || tails ? 1 : ((operation+13*term)%65535)*2+1;
            boolean active=term==0 || (tails ? term==1 : term<=operation%7);
            int exponent=term==0?0:tails?-1074:-24-3*(term-1);
            initial.putInt(4*word,amount);
            initial.putInt(address,0).putInt(address+4,word).putInt(address+8,active?1:0)
                    .putInt(address+12,exponent).putInt(address+16,active?(term==0?1:-1):0);
        }
        try (var storage = new VulkanRegionStorage(context, bytes, 1024*1024)) {
            storage.initialize(initial,0);
            for(int i=0;i<5;i++) advance(storage,sum,layout);
            var before=storage.metrics();
            long[] nanos=new long[samples];
            for(int i=0;i<samples;i++) {
                long start=System.nanoTime();advance(storage,sum,layout);nanos[i]=System.nanoTime()-start;
            }
            var after=storage.metrics();Arrays.sort(nanos);
            System.out.printf("%s,%d,%.3f,%.3f,%.3f,%d,%d,%.1f%n",tails?"wide-tails":"compact-remainders",n,
                    nanos[samples/2]*1e-6,nanos[samples-1]*1e-6,(after.gpuNanos()-before.gpuNanos())*1e-6/samples,
                    (after.uploadBytes()-before.uploadBytes())/samples,(after.readbackBytes()-before.readbackBytes())/samples,
                    after.deviceBytes()/1048576.0);
            // Sample three exact results after timing, including their variable-length prefix offsets.
            try(var capture=storage.capture()) {
                for(int operation:new int[]{0,n/2,n-1}) {
                    var result=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
                    storage.read(capture,4L*(results+8*operation),result);
                    var range=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                    storage.read(capture,4L*(offsets+operation),range);
                    int start=range.getInt(0),end=range.getInt(4);
                    var output=ByteBuffer.allocate(4*(end-start)).order(ByteOrder.LITTLE_ENDIAN);
                    storage.read(capture,4L*(limbs+start),output);
                    BigInteger magnitude=BigInteger.ZERO;
                    for(int j=end-start-1;j>=0;j--)magnitude=magnitude.shiftLeft(32)
                            .add(BigInteger.valueOf(Integer.toUnsignedLong(output.getInt(4*j))));
                    SignedDyadic expected=SignedDyadic.of(1);
                    for(int term=1;term<=(tails?1:operation%7);term++)
                        expected=expected.subtract(SignedDyadic.of(tails?1:((operation+13*term)%65535)*2+1,
                                tails?-1074:-24-3*(term-1)));
                    var actual=new SignedDyadic(magnitude,result.getInt(8));
                    if(result.getInt(0)!=1 || !actual.equals(expected))
                        throw new AssertionError("Resident exact remainder differs at operation "+operation);
                }
            }
        }
    }

    private static void advance(VulkanRegionStorage storage,VulkanDyadicSum sum,VulkanDyadicSum.Layout layout) {
        try(var source=storage.capture();var candidate=storage.prepare(source)) {
            int error=candidate.execute(0,(command,input,output,errors,staging,stack)-> {
                sum.rebindRanges(new FlipKernelBindings.BufferRange(input),new FlipKernelBindings.BufferRange(output),
                        new FlipKernelBindings.BufferRange(errors));
                sum.record(command,layout);
            });
            if(error!=0)throw new IllegalStateException("Resident dyadic operation failed: status="+error);
            candidate.commit();
        }
    }
}
