package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Common liquid/finite-gas pressure cost from resident topology and real inventory EOS. */
public final class GpuGasPressureBenchmark {
    private GpuGasPressureBenchmark() { }
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int device=Integer.parseInt(args[0]),grid=Integer.parseInt(args[1]),samples=Integer.parseInt(args[3]),warm=Integer.parseInt(args[4]);
        boolean cut=args[2].equals("cut");
        if(samples<1||warm<0)throw new IllegalArgumentException("Invalid pressure benchmark count");
        try(var context=new VulkanDeviceContext(device,true,true);var liquidKernel=new VulkanLiquidGeometry(context);
            var totalKernel=new VulkanLiquidCellVolumes(context);var geometryKernel=new VulkanGasGeometry(context);
            var thermalKernel=new VulkanGasThermodynamics(context);var projection=new VulkanLiquidProjection(context);
            var pressure=new VulkanPressureKernel(context,4096);var multigrid=new VulkanPressureMultigrid(context,args.length>5&&Boolean.parseBoolean(args[5]));
            var gasPressure=new VulkanGasPressure(context)) {
            System.out.println("Device: "+context.deviceName());
            var fixture=GpuLiquidGeometryBenchmark.fixture(grid,cut);int n=grid*grid*grid;
            long alignment=Math.max(8,context.storageOffsetAlignment());
            var liquid=VulkanLiquidStateLayout.forState(fixture.inventory(),fixture.momentum(),alignment);
            var geometry=GpuGasGeometryBenchmark.geometry(liquid,alignment);
            var totals=VulkanLiquidCellVolumes.layout(liquid,2*n,-40,geometry.endOffset(),alignment);
            var gasGeometry=VulkanGasGeometry.layout(totals,word(geometry.masks()),word(geometry.occupied()),word(geometry.cells()),
                    word(geometry.planes()),word(geometry.micro()),true,.25,totals.endOffset(),alignment);
            var definitions=GpuGasGeometryBenchmark.gasDefinitions();
            var gas=new VulkanGasStateLayout(grid,grid,grid,n,2*n,definitions.definitions(),gasGeometry.endOffset(),alignment);
            var tables=VulkanGasThermodynamics.tables(gas.definitions(),definitions.gas(),definitions.thermal(),mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY);
            var thermal=VulkanGasThermodynamics.layout(gas,tables,Math.toIntExact(gas.endOffset()/4),gas.endOffset()+8L*n,alignment);
            int size=n+gas.nodeCapacity();long[] cursor={thermal.endOffset()};
            var starts=column(cursor,size+1,4,alignment);var columns=column(cursor,7*n+gas.nodeCapacity(),4,alignment);
            var values=column(cursor,columns.count(),8,alignment);var rows=column(cursor,size,4,alignment);
            var solution=column(cursor,size,8,alignment);var work=column(cursor,7*size,8,alignment);
            var meta=column(cursor,2*size,4,alignment);var problem=column(cursor,3*size+1,8,alignment);
            var control=column(cursor,16,8,alignment);var partials=column(cursor,4*((size+255)/256),8,alignment);
            var hierarchy=VulkanPressureMultigrid.layout(grid,grid,grid,size);
            var mg=column(cursor,hierarchy.workspaceDoubles(),8,alignment);
            var buffers=new VulkanGasPressure.PressureBuffers(starts,columns,values,rows,solution,work,meta,problem,control);
            var gasLayout=new VulkanGasPressure.Layout(thermal,gasGeometry,geometry.cells(),geometry.faces(),buffers,cursor[0],alignment);
            int bytes=Math.toIntExact(gasLayout.endOffset());var initial=ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
            liquid.importState(initial,fixture.inventory(),fixture.momentum());totals.initializeHeader(initial);
            gasGeometry.initializeHeader(initial);thermal.initializeHeaderAndTables(initial);gasLayout.initializeHeader(initial);
            for(int c=0;c<n;c++){initial.putInt(Math.toIntExact(geometry.masks().offset()+4L*c),fixture.fixed().mask(c));initial.putLong(Math.toIntExact(geometry.occupied().offset()+8L*c),fixture.occupied()[c]);}
            var parameters=new VulkanLiquidProjection.Parameters(.25f,.05f,9.81f,1e-5f,200);
            try(var storage=new VulkanRegionStorage(context,bytes,1024*1024)) {
                storage.initialize(initial,0);
                try(var source=storage.capture()){GpuGasGeometryBenchmark.advance(storage,source,liquidKernel,totalKernel,geometryKernel,thermalKernel,liquid,geometry,totals,gasGeometry,thermal,true,false,true);}
                int nodes;try(var source=storage.capture()){nodes=GpuGasGeometryBenchmark.seed(storage,source,initial,gas,gasGeometry,definitions);}
                storage.initialize(initial,1);
                try(var source=storage.capture()){GpuGasGeometryBenchmark.advance(storage,source,liquidKernel,totalKernel,geometryKernel,thermalKernel,liquid,geometry,totals,gasGeometry,thermal,true,true,true);}
                System.out.printf("Resident common pressure: scene=%s grid=%d finite_nodes=%d capacity=%d; source pressure is zero for every solve. Includes gravity, assembly, PCG/MG, gas-face products and response. Topology/EOS prepared before timing.%n",cut?"cut":"dam",grid,nodes,gas.nodeCapacity());
                try(var source=storage.capture()) {
                    for(int i=0;i<warm;i++)solve(storage,source,projection,pressure,multigrid,gasPressure,geometry,gasLayout,hierarchy,partials,mg,parameters,false);
                    var before=storage.metrics();long[] times=new long[samples];
                    for(int i=0;i<samples;i++){long begin=System.nanoTime();solve(storage,source,projection,pressure,multigrid,gasPressure,geometry,gasLayout,hierarchy,partials,mg,parameters,false);times[i]=System.nanoTime()-begin;}
                    var after=storage.metrics();Arrays.sort(times);
                    System.out.println("scene,grid,finite_nodes,median_ms,max_ms,gpu_mean_ms,upload_B,readback_B,device_MiB");
                    System.out.printf("%s,%d,%d,%.3f,%.3f,%.3f,%d,%d,%.1f%n",cut?"cut":"dam",grid,nodes,times[samples/2]*1e-6,times[samples-1]*1e-6,
                            (after.gpuNanos()-before.gpuNanos())*1e-6/samples,(after.uploadBytes()-before.uploadBytes())/samples,(after.readbackBytes()-before.readbackBytes())/samples,after.deviceBytes()/1048576.0);
                    solve(storage,source,projection,pressure,multigrid,gasPressure,geometry,gasLayout,hierarchy,partials,mg,parameters,true);
                }
            }
        }
    }
    private static void solve(VulkanRegionStorage storage,VulkanRegionStorage.Capture source,VulkanLiquidProjection projection,VulkanPressureKernel pressure,
                              VulkanPressureMultigrid multigrid,VulkanGasPressure gas,GpuGasGeometryBenchmark.Geometry geometry,VulkanGasPressure.Layout layout,
                              VulkanPressureMultigrid.Layout hierarchy,VulkanLiquidStateLayout.Column partials,VulkanLiquidStateLayout.Column mg,
                              VulkanLiquidProjection.Parameters parameters,boolean diagnostics) {
        var b=layout.pressureBuffers();int size=layout.pressureSize(),grid=hierarchy.levels().getFirst().sizeX();
        var operator=new VulkanPressureKernel.GasOperator(gas,layout);
        try(var candidate=storage.prepare(source)) {
            var batch=candidate.beginBatch((command,input,output,errors,staging,stack)->{
                var err=new FlipKernelBindings.BufferRange(errors);
                projection.rebindRanges(List.of(range(output,geometry.cells()),range(output,geometry.faces()),range(output,b.starts()),range(output,b.columns()),range(output,b.values()),range(output,b.rows()),range(output,b.meta()),range(output,b.problem()),range(output,b.pressure()),err));
                pressure.rebindRanges(List.of(range(output,b.starts()),range(output,b.columns()),range(output,b.values()),range(output,b.rows()),range(output,b.pressure()),range(output,b.pressure()),range(output,b.work()),range(output,b.meta()),range(output,b.problem()),range(output,b.control()),range(output,partials),err));
                multigrid.rebindRanges(List.of(range(output,b.starts()),range(output,b.columns()),range(output,b.values()),range(output,b.rows()),range(output,b.meta()),range(output,b.work()),range(output,b.control()),range(output,mg)));
                gas.rebindRanges(new FlipKernelBindings.BufferRange(output),err);
                projection.prepare(command,grid,grid,grid,parameters,gas,layout);
                pressure.recordInitialize(command,size,size,parameters.iterations(),multigrid,hierarchy,operator);
                pressure.recordIterations(command,size,size,parameters.iterations(),multigrid,hierarchy,8,operator);
            },b.control().offset()+24,8);
            int recorded=8;
            if(batch.error()!=0)throw new AssertionError("Gas pressure initialize status="+batch.error());
            while(batch.feedback().getDouble(0)!=0.0) {
                if(recorded>=parameters.iterations())throw new AssertionError("Gas pressure exceeded iteration limit");
                batch=candidate.continueBatch((command,input,output,errors,staging,stack)->pressure.recordIterations(command,size,size,parameters.iterations(),multigrid,hierarchy,8,operator),b.control().offset()+24,8);
                recorded+=8;if(batch.error()!=0)throw new AssertionError("Gas pressure iteration status="+batch.error());
            }
            int error=candidate.finishBatch((command,input,output,errors,staging,stack)->{
                pressure.recordFinish(command,size,size,parameters.iterations(),multigrid,hierarchy,operator);
                projection.project(command,grid,grid,grid,parameters,gas,layout);
            });
            if(error!=0)throw new AssertionError("Gas pressure finish status="+error+", recorded="+recorded);
            if(diagnostics){var result=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);candidate.read(b.control().offset(),result);
                var state=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);candidate.read(layout.column(VulkanGasPressure.Field.NODE_ABSOLUTE_PRESSURE).offset(),state);
                System.out.printf("pressure iterations=%.0f rms=%.9g success=%.0f first_node_absolute_P=%.9g%n",result.getDouble(0),result.getDouble(8),result.getDouble(16),state.getDouble(0));
                var totals=new java.util.LinkedHashMap<String,Long>();for(var sample:multigrid.stageTimings())totals.merge(sample.stage(),sample.nanos(),Long::sum);
                totals.forEach((name,nanos)->System.out.printf("pressure_stage=%s gpu_ms=%.3f%n",name,nanos*1e-6));}
        }
    }
    private static FlipKernelBindings.BufferRange range(GpuBuffer buffer,VulkanLiquidStateLayout.Column column){return new FlipKernelBindings.BufferRange(buffer,column.offset(),column.bindingBytes());}
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width,long alignment){long offset=(cursor[0]+alignment-1)&-alignment;var result=new VulkanLiquidStateLayout.Column(offset,count,width);cursor[0]=offset+result.bindingBytes();return result;}
}
