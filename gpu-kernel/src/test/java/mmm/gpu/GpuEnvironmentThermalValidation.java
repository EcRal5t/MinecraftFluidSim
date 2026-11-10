package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Measures the resident environment graph and its finite-capacity exchange. */
public final class GpuEnvironmentThermalValidation {
    private GpuEnvironmentThermalValidation() {}
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int device = Integer.parseInt(args[0]), warm = Integer.parseInt(args[1]), samples = Integer.parseInt(args[2]);
        try (var context = new VulkanDeviceContext(device, true, true); var kernel = new VulkanEnvironmentThermal(context);
             var ledger = new VulkanEnergyLedger(context)) {
            verifyExchange(context, kernel, ledger);
            var nodes = new ArrayList<VulkanEnvironmentThermal.Node>();
            var edges = new ArrayList<VulkanEnvironmentThermal.Edge>();
            int side = 40; int[] index = new int[side*side*side]; Arrays.fill(index,-1);
            for (int z=0;z<side;z++) for (int y=0;y<side;y++) for (int x=0;x<side;x++) {
                if (x>=4&&x<36&&y>=4&&y<36&&z>=4&&z<36) continue;
                int position=(z*side+y)*side+x;index[position]=nodes.size();
                double capacity=1200; nodes.add(new VulkanEnvironmentThermal.Node(capacity*(293.15+x),0,capacity));
            }
            for (int z=0;z<side;z++) for (int y=0;y<side;y++) for (int x=0;x<side;x++) {
                int a=index[(z*side+y)*side+x]; if(a<0)continue;
                if(x+1<side) connect(edges,a,index[(z*side+y)*side+x+1]);
                if(y+1<side) connect(edges,a,index[(z*side+y+1)*side+x]);
                if(z+1<side) connect(edges,a,index[((z+1)*side+y)*side+x]);
            }
            var l=VulkanEnvironmentThermal.layout(nodes,edges,0,Math.max(8,context.storageOffsetAlignment()));
            var accounting=VulkanEnvironmentThermal.accounting(l,Math.max(8,context.storageOffsetAlignment()));
            var initial=ByteBuffer.allocate(Math.toIntExact(accounting.bytes())).order(ByteOrder.LITTLE_ENDIAN);l.initialize(initial,nodes,edges);accounting.initialize(initial);
            try(var storage=new VulkanRegionStorage(context,accounting.bytes(),1024*1024)) {
                storage.initialize(initial,0); long[] times=new long[samples]; VulkanRegionStorage.Metrics before=null;
                for(int step=0;step<warm+samples;step++) {
                    if(step==warm)before=storage.metrics(); long start=System.nanoTime();
                    advance(storage,kernel,ledger,l,accounting,.5);
                    if(step>=warm)times[step-warm]=System.nanoTime()-start;
                }
                var after=storage.metrics(); long sum=0;for(long t:times)sum+=t;Arrays.sort(times);
                System.out.printf("device=%s environment_nodes=%d edges=%d bytes=%d samples=%d mean_ms=%.4f p95_ms=%.4f gpu_mean_ms=%.4f readback_B=%d%n",
                    context.deviceName(),nodes.size(),edges.size(),accounting.bytes(),samples,sum*1e-6/samples,
                    times[(int)Math.ceil(.95*samples)-1]*1e-6,(after.gpuNanos()-before.gpuNanos())*1e-6/samples,(after.readbackBytes()-before.readbackBytes())/samples);
            }
        }
    }
    private static void verifyExchange(VulkanDeviceContext context,VulkanEnvironmentThermal kernel,VulkanEnergyLedger ledger) {
        double cw=125*4180.0, ci=7800*450.0, ca=1.2*1000.0;
        var nodes=List.of(new VulkanEnvironmentThermal.Node(cw*350,0,cw),new VulkanEnvironmentThermal.Node(ci*300,0,ci),new VulkanEnvironmentThermal.Node(ca*293,0,ca));
        var edges=List.of(new VulkanEnvironmentThermal.Edge(0,1,1.2,0),new VulkanEnvironmentThermal.Edge(1,2,5,0));
        var l=VulkanEnvironmentThermal.layout(nodes,edges,0,Math.max(8,context.storageOffsetAlignment()));
        var accounting=VulkanEnvironmentThermal.accounting(l,Math.max(8,context.storageOffsetAlignment()));
        var initial=ByteBuffer.allocate(Math.toIntExact(accounting.bytes())).order(ByteOrder.LITTLE_ENDIAN);l.initialize(initial,nodes,edges);accounting.initialize(initial);
        try(var storage=new VulkanRegionStorage(context,accounting.bytes(),1024*1024)) {
            storage.initialize(initial,0); for(int i=0;i<10;i++) advance(storage,kernel,ledger,l,accounting,.5);
            var result=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            try(var capture=storage.capture()){storage.read(capture,l.offset(VulkanEnvironmentThermal.Field.ENERGY),result);}
            double w=result.getDouble(0),iron=result.getDouble(8),air=result.getDouble(16);
            double before=nodes.stream().mapToDouble(VulkanEnvironmentThermal.Node::energy).sum();
            if(!(w<nodes.get(0).energy()&&iron>nodes.get(1).energy()&&air>nodes.get(2).energy()) || Math.abs((w+iron+air)-before)>1e-5)
                throw new IllegalStateException("Finite water/iron/air heat exchange failed");
            System.out.printf("finite_carriers water_K=%.8f iron_K=%.8f air_K=%.8f energy_error_J=%.9g%n",w/cw,iron/ci,air/ca,(w+iron+air)-before);
        }
    }
    private static void connect(List<VulkanEnvironmentThermal.Edge> edges,int a,int b) {
        if(b>=0)edges.add(new VulkanEnvironmentThermal.Edge(a,b,.026,0));
    }
    private static void advance(VulkanRegionStorage storage,VulkanEnvironmentThermal kernel,VulkanEnergyLedger ledger,VulkanEnvironmentThermal.Layout l,VulkanEnvironmentThermal.Accounting accounting,double duration){
        try(var capture=storage.capture();var candidate=storage.prepare(capture)){
            int error=candidate.execute(0,(command,input,output,errors,staging,stack)->{
                kernel.record(command,l,output,errors,duration);
                kernel.recordAccounting(command,accounting,ledger,input,output,errors);
            });
            if(error!=0)throw new IllegalStateException("Environment candidate error="+error);candidate.commit();
        }
    }
}
