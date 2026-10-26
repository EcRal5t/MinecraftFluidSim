package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import static org.lwjgl.vulkan.VK10.*;

/** Replays the captured aperture operator through the production Schur execution and back substitution. */
public final class GpuGasFlowReplay {
    private GpuGasFlowReplay() { }
    public static void main(String[] args) throws java.io.IOException {
        Locale.setDefault(Locale.ROOT);
        var file=Path.of(args[1]);var saved=ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic=new byte[8];saved.get(magic);
        if(!Arrays.equals(magic,"MMMGAS01".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new IllegalArgumentException("Not an aperture graph capture");
        int n=saved.getInt(),pairs=saved.getInt();double dt=saved.getDouble();
        double[] soft=doubles(saved,n),root=doubles(saved,n),rhs=doubles(saved,n),initial=doubles(saved,n);
        int[] keys=ints(saved,n),pa=ints(saved,pairs),pb=ints(saved,pairs);double[] weights=doubles(saved,pairs);
        System.setProperty("mmm.gpuMechanicalStages","true");
        try(var context=new VulkanDeviceContext(Integer.parseInt(args[0]),true,true);var schur=new VulkanGasFlowSchurPressure(context)) {
            long a=Math.max(8,context.storageOffsetAlignment());
            var fixture=GpuLiquidGeometryBenchmark.fixture(2,false);
            var liquid=VulkanLiquidStateLayout.forState(fixture.inventory(),fixture.momentum(),a);
            var g=GpuGasGeometryBenchmark.geometry(liquid,a);
            var totals=VulkanLiquidCellVolumes.layout(liquid,16,-40,g.endOffset(),a);
            var geometry=VulkanGasGeometry.layout(totals,word(g.masks()),word(g.occupied()),word(g.cells()),word(g.planes()),word(g.micro()),true,.5,totals.endOffset(),a);
            var definitions=GpuGasGeometryBenchmark.gasDefinitions();
            var gas=new VulkanGasStateLayout(2,2,2,n,1,definitions.definitions(),geometry.endOffset(),a);
            var tables=VulkanGasThermodynamics.tables(gas.definitions(),definitions.gas(),definitions.thermal(),mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY);
            var eos=VulkanGasThermodynamics.layout(gas,tables,Math.toIntExact(gas.endOffset()/4),gas.endOffset()+8L*n,a);
            var flow=VulkanGasFlow.layout(eos,geometry,Math.max(1,pairs),eos.endOffset(),a);
            var layout=VulkanGasFlowSchurPressure.layout(flow,flow.endOffset(),a);
            var data=ByteBuffer.allocate(Math.toIntExact(layout.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
            geometry.initializeHeader(data);eos.initializeHeaderAndTables(data);flow.initializeHeader(data);
            data.position(Math.toIntExact(layout.header().offset())).put(layout.headerBytes());data.clear();
            data.putInt(Math.toIntExact(gas.column(VulkanGasStateLayout.Field.COUNTS).offset()),n);
            int[] degree=new int[n];
            for(int e=0;e<pairs;e++){if(pa[e]>0)degree[pa[e]-1]++;if(pb[e]>0)degree[pb[e]-1]++;}
            int[] offsets=new int[n+1],starts=new int[n+1],cursor=new int[n];
            for(int i=0;i<n;i++){offsets[i+1]=offsets[i]+degree[i];starts[i+1]=starts[i]+degree[i]+1;}
            int[] adjacency=new int[offsets[n]],columns=new int[starts[n]];double[] values=new double[starts[n]];
            for(int i=0;i<n;i++)cursor[i]=offsets[i];
            for(int e=0;e<pairs;e++){
                if(pa[e]>0)adjacency[cursor[pa[e]-1]++]=e;
                if(pb[e]>0)adjacency[cursor[pb[e]-1]++]=e;
            }
            int longRows=0,tiles=0;
            for(int i=0;i<n;i++){
                put(data,flow,VulkanGasFlow.Field.NODE_COUNTS,i,degree[i]);put(data,flow,VulkanGasFlow.Field.NODE_OFFSETS,i,offsets[i]);
                put(data,flow,VulkanGasFlow.Field.STARTS,i,starts[i]);put(data,flow,VulkanGasFlow.Field.ROWS,i,i);
                put(data,flow,VulkanGasFlow.Field.STORAGE,i,soft[i]);put(data,flow,VulkanGasFlow.Field.DIAGONAL_ROOT,i,root[i]);
                put(data,flow,VulkanGasFlow.Field.PROBLEM,i,rhs[i]);put(data,flow,VulkanGasFlow.Field.PROBLEM,2*n+i,initial[i]);
                data.putInt(Math.toIntExact(gas.column(VulkanGasStateLayout.Field.TOPOLOGY_KEYS).offset()+4L*i),keys[i]);
                data.putDouble(Math.toIntExact(eos.stateOffset(i,VulkanGasThermodynamics.Quantity.PRESSURE)),initial[i]/root[i]);
                columns[starts[i]]=i;values[starts[i]]=1;
                for(int j=offsets[i];j<offsets[i+1];j++){
                    int e=adjacency[j],peer=(pa[e]==i+1?pb[e]:pa[e])-1,at=starts[i]+1+j-offsets[i];
                    columns[at]=peer>=0?peer:i;values[at]=peer>=0?-dt*weights[e]/(root[i]*root[peer]):0;
                }
                int length=degree[i]+1;
                if(length>128){
                    put(data,flow,VulkanGasFlow.Field.LONG_ROWS,longRows++,i);put(data,flow,VulkanGasFlow.Field.LONG_STARTS,i,tiles);
                    for(int tile=0;tile<(length+127)/128;tile++)put(data,flow,VulkanGasFlow.Field.TILE_OWNER,tiles++,i);
                }
            }
            put(data,flow,VulkanGasFlow.Field.NODE_OFFSETS,n,offsets[n]);put(data,flow,VulkanGasFlow.Field.STARTS,n,starts[n]);
            put(data,flow,VulkanGasFlow.Field.HASH_OFFSETS,flow.hashes,pairs);
            put(data,flow,VulkanGasFlow.Field.LONG_COUNTS,0,longRows);put(data,flow,VulkanGasFlow.Field.LONG_COUNTS,1,tiles);
            for(int slot=5;slot<=6;slot++)dispatch(data,flow,slot,slot==5?tiles:longRows);
            for(int i=0;i<adjacency.length;i++)put(data,flow,VulkanGasFlow.Field.NODE_PAIRS,i,adjacency[i]);
            for(int i=0;i<columns.length;i++){put(data,flow,VulkanGasFlow.Field.COLUMNS,i,columns[i]);put(data,flow,VulkanGasFlow.Field.VALUES,i,values[i]);}
            for(int e=0;e<pairs;e++){put(data,flow,VulkanGasFlow.Field.PAIR_A,e,pa[e]);put(data,flow,VulkanGasFlow.Field.PAIR_B,e,pb[e]);put(data,flow,VulkanGasFlow.Field.PAIR_G,e,weights[e]);}
            put(data,flow,VulkanGasFlow.Field.PROBLEM,3*n,1e-8);
            var q=new VulkanGasFlow.Parameters(dt,.6,101325,293.15,1.2,0);
            System.out.printf("Device: %s; capture=%s nodes=%d pairs=%d long_rows=%d dt=%.17g%n",context.deviceName(),file,n,pairs,longRows,dt);
            try(var storage=new VulkanRegionStorage(context,layout.endOffset(),1024*1024)) {
                storage.initialize(data,0);
                try(var capture=storage.capture();var candidate=storage.prepare(capture)) {
                    candidate.beginBatch((command,input,output,errors,staging,stack)->{
                        VulkanRegionStorage.copy(command,input,0,output,0,layout.endOffset(),stack);
                        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                        schur.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors),layout);
                    },0,0);
                    int error=schur.execute(candidate,layout,q,(command,label,body)->body.run(),(command,input,output,errors,staging,stack)->{});
                    var control=ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);candidate.read(flow.column(VulkanGasFlow.Field.CONTROL).offset(),control);
                    var result=ByteBuffer.allocate(8*n).order(ByteOrder.LITTLE_ENDIAN);candidate.read(flow.column(VulkanGasFlow.Field.PRESSURE).offset(),result);
                    double squares=0,max=0;int worst=-1;double worstProduct=0;
                    for(int i=0;i<n;i++){
                        double product=0;for(int at=starts[i];at<starts[i+1];at++)product+=values[at]*result.getDouble(8*columns[at]);
                        double residual=rhs[i]-product;squares+=residual*residual;
                        if(Math.abs(residual)>max){max=Math.abs(residual);worst=i;worstProduct=product;}
                    }
                    System.out.printf("aperture_replay error=%d iterations=%.0f gpu_residual=%.17g success=%.0f tolerance=%.17g original_residual_rms=%.17g maximum_residual=%.17g%n",error,control.getDouble(0),control.getDouble(8),control.getDouble(16),control.getDouble(80),Math.sqrt(squares/Math.max(1,n)),max);
                    if(worst>=0)System.out.printf("worst_row=%d topology_key=%d degree=%d rhs=%.17g Ax=%.17g pressure=%.17g storage=%.17g root=%.17g%n",worst,keys[worst],degree[worst],rhs[worst],worstProduct,result.getDouble(8*worst),soft[worst],root[worst]);
                    if(error!=0)throw new IllegalStateException("Aperture replay failed error="+error);
                }
            }
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    private static double[] doubles(ByteBuffer b,int n){double[] v=new double[n];for(int i=0;i<n;i++)v[i]=b.getDouble();return v;}
    private static int[] ints(ByteBuffer b,int n){int[] v=new int[n];for(int i=0;i<n;i++)v[i]=b.getInt();return v;}
    private static void put(ByteBuffer b,VulkanGasFlow.Layout l,VulkanGasFlow.Field f,int i,int v){b.putInt(Math.toIntExact(l.column(f).offset()+4L*i),v);}
    private static void put(ByteBuffer b,VulkanGasFlow.Layout l,VulkanGasFlow.Field f,int i,double v){b.putDouble(Math.toIntExact(l.column(f).offset()+8L*i),v);}
    private static void dispatch(ByteBuffer b,VulkanGasFlow.Layout l,int slot,int count){put(b,l,VulkanGasFlow.Field.DISPATCH,3*slot,Math.min(count,65535));put(b,l,VulkanGasFlow.Field.DISPATCH,3*slot+1,Math.max(1,(count+65534)/65535));put(b,l,VulkanGasFlow.Field.DISPATCH,3*slot+2,1);}
}
