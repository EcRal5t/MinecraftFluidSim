package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Locale;

/** Short module scenario: overlapping moving boxes, exact union, real viscous contact and
 * an analytic pressure load. Regional capacity/pressure solve remains the production caller. */
public final class GpuEntityBodyCouplingBenchmark {
    private GpuEntityBodyCouplingBenchmark(){}
    public static void main(String[] args){
        Locale.setDefault(Locale.ROOT);int device=args.length>0?Integer.parseInt(args[0]):0;
        try(var context=new VulkanDeviceContext(device,true,true);var bodiesKernel=new VulkanEntityBodyCoupling(context);var viscosityKernel=new VulkanLiquidViscosity(context)){
            int grid=8,n=grid*grid*grid;double h=.5,dt=.05,rho=1000,g=9.81;long a=Math.max(8,context.storageOffsetAlignment());
            var fixture=GpuLiquidGeometryBenchmark.fixture(grid,false,h);var liquid=VulkanLiquidStateLayout.forState(fixture.inventory(),fixture.momentum(),a);
            var geometry=GpuGasGeometryBenchmark.geometry(liquid,a);var totals=VulkanLiquidCellVolumes.layout(liquid,2*n,-40,geometry.endOffset(),a);
            var gasGeometry=VulkanGasGeometry.layout(totals,word(geometry.masks()),word(geometry.occupied()),word(geometry.cells()),word(geometry.planes()),word(geometry.micro()),true,h,totals.endOffset(),a);
            var definitions=GpuGasGeometryBenchmark.gasDefinitions();var gas=new VulkanGasStateLayout(grid,grid,grid,n,2*n,definitions.definitions(),gasGeometry.endOffset(),a);
            var thermal=VulkanGasThermodynamics.layout(gas,VulkanGasThermodynamics.tables(gas.definitions(),definitions.gas(),definitions.thermal(),mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY),Math.toIntExact(gas.endOffset()/4),gas.endOffset()+8L*n,a);
            int size=n+gas.nodeCapacity();long[] cursor={thermal.endOffset()};var starts=column(cursor,size+1,4,a);var columns=column(cursor,7*n+gas.nodeCapacity(),4,a);var values=column(cursor,columns.count(),8,a);var rows=column(cursor,size,4,a);
            var solution=column(cursor,size,8,a);var work=column(cursor,7*size,8,a);var meta=column(cursor,2*size,4,a);var problem=column(cursor,3*size+1,8,a);var control=column(cursor,16,8,a);
            var pressure=new VulkanGasPressure.Layout(thermal,gasGeometry,geometry.cells(),geometry.faces(),new VulkanGasPressure.PressureBuffers(starts,columns,values,rows,solution,work,meta,problem,control),cursor[0],a);
            cursor[0]=pressure.endOffset();var temperatures=column(cursor,n,8,a);
            var first=new mmm.world.MovingBodyFluidCoupling.Request(new java.util.UUID(0,1),new mmm.world.MovingBodyFluidCoupling.Bounds(.5,.75,.75,1.25,1.25,1.5),.4,.2,0);
            var second=new mmm.world.MovingBodyFluidCoupling.Request(new java.util.UUID(0,2),new mmm.world.MovingBodyFluidCoupling.Bounds(.75,.875,1,1.5,1.375,1.75),.4,.2,0);
            var config=new VulkanEntityBodyCoupling.Configuration(new mmm.fluid.EntityBodyState(List.of(first,second)),grid,grid,grid,h);
            var body=new VulkanEntityBodyCoupling.Layout(grid,grid,grid,4,2*config.cellEntries(),size,geometry.masks(),geometry.cells(),geometry.faces(),geometry.occupied(),cursor[0],a);
            var viscous=new VulkanLiquidViscosity.Layout(liquid,geometry.cells(),geometry.faces(),geometry.masks(),word(temperatures),-1,
                    new VulkanLiquidViscosity.Tables(new int[]{0,1,2},new double[]{273,1000,.001,.001,273,1000,.05,.05}),body.prescribedContacts(),false,body.endOffset(),a);
            body.bindPressure(pressure,null);body.bindViscosity(viscous);
            var initial=ByteBuffer.allocate(Math.toIntExact(viscous.endOffset())).order(ByteOrder.LITTLE_ENDIAN);liquid.importState(initial,fixture.inventory(),fixture.momentum());
            totals.initializeHeader(initial);gasGeometry.initializeHeader(initial);thermal.initializeHeaderAndTables(initial);pressure.initializeHeader(initial);viscous.initializeHeaderAndTables(initial);
            initial.duplicate().position(Math.toIntExact(body.header.offset())).put(body.headerBytes());body.initializeBodies(initial,config);
            var source=fixture.inventory();for(int c=0;c<n;c++){
                double v=0,m=0;for(int row=source.cellOffsets()[c];row<source.cellOffsets()[c+1];row++){v+=source.volumes().valueAt(row).toDouble();m+=mmm.units.Mass.kilograms(source.massUnits()[row]);}
                int offset=Math.toIntExact(geometry.cells().offset()+32L*c);initial.putDouble(offset,v).putDouble(offset+8,m).putDouble(offset+16,1);
                initial.putDouble(Math.toIntExact(temperatures.offset()+8L*c),293.15);initial.putDouble(Math.toIntExact(solution.offset()+8L*c),rho*g*(grid*h-(c/grid%grid+.5)*h));
                initial.putInt(Math.toIntExact(rows.offset()+4L*c),c);
            }
            for(int f=0;f<geometry.faces().count()/4;f++){int offset=Math.toIntExact(geometry.faces().offset()+32L*f);initial.putDouble(offset,100).putDouble(offset+8,0).putDouble(offset+16,1).putDouble(offset+24,1);}
            double expectedUnion=.46875;long exactVolume=0;double sumWork=0;mmm.world.MovingBodyFluidCoupling.Result result=null;
            try(var storage=new VulkanRegionStorage(context,viscous.endOffset(),1024*1024)){
                storage.initialize(initial,0);for(int step=0;step<4;step++)try(var capture=storage.capture();var candidate=storage.prepare(capture)){
                    long begin=System.nanoTime();var feedback=candidate.beginBatch((cmd,input,output,errors,staging,stack)->{
                        var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);bodiesKernel.rebindRanges(arena,err);viscosityKernel.rebindRanges(new FlipKernelBindings.BufferRange(input),arena,err);
                        bodiesKernel.recordGeometry(cmd,body,h);viscosityKernel.record(cmd,viscous,h,dt);bodiesKernel.recordPressure(cmd,body,h,dt);bodiesKernel.recordResult(cmd,body,h,dt);
                    },body.column(VulkanEntityBodyCoupling.Field.BODY_RESULTS).offset(),64*config.bodies().bodies().size());
                    if(feedback.error()!=0)throw new IllegalStateException("Entity body candidate error="+feedback.error());int error=candidate.finishBatch((cmd,input,output,errors,staging,stack)->{});if(error!=0)throw new IllegalStateException("Entity body finish error="+error);
                    result=body.result(feedback.feedback(),config);var occupied=ByteBuffer.allocate(8*n).order(ByteOrder.LITTLE_ENDIAN);candidate.read(body.column(VulkanEntityBodyCoupling.Field.OCCUPIED_SVQ).offset(),occupied);exactVolume=0;for(int c=0;c<n;c++)exactVolume+=occupied.getLong(8*c);
                    var sourceWork=ByteBuffer.allocate(8*n).order(ByteOrder.LITTLE_ENDIAN);candidate.read(body.column(VulkanEntityBodyCoupling.Field.SOURCE_WORK).offset(),sourceWork);sumWork=0;for(int c=0;c<n;c++)sumWork+=sourceWork.getDouble(8*c);
                    if(Math.abs(exactVolume/134217728.0-expectedUnion)>n/134217728.0||Math.abs(sumWork-result.externalWorkJoules())>1e-9)
                        throw new IllegalStateException("Entity union or actual source work differs from body work");
                    candidate.commit();System.out.printf("step=%d volume=%.9g impulse=(%.9g,%.9g,%.9g) work=%.9g wall_ms=%.3f%n",step,result.coupledVolumeCubicMetres(),result.impulseXKilogramMetresPerSecond(),result.impulseYKilogramMetresPerSecond(),result.impulseZKilogramMetresPerSecond(),result.externalWorkJoules(),(System.nanoTime()-begin)*1e-6);
                }
            }
            if(result==null||result.impulseXKilogramMetresPerSecond()<=0||result.impulseYKilogramMetresPerSecond()>=0||Math.abs(result.coupledVolumeCubicMetres()-expectedUnion)>1e-12)
                throw new IllegalStateException("Prescribed shear, buoyancy load or exact union did not complete");
            System.out.printf("Entity module complete: exactUnionSvq=%d externalWork=%.12g sourceWork=%.12g%n",exactVolume,result.externalWorkJoules(),sumWork);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int n,int width,long a){long offset=(cursor[0]+a-1)&-a;var c=new VulkanLiquidStateLayout.Column(offset,n,width);cursor[0]=offset+c.bindingBytes();return c;}
}
