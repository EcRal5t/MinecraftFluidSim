package mmm.gpu;

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
            var geometry=GpuGasFixture.geometry(liquid,a);var totals=VulkanLiquidCellVolumes.layout(liquid,2*n,-40,geometry.endOffset(),a);
            var gasGeometry=VulkanGasGeometry.layout(totals,word(geometry.masks()),word(geometry.occupied()),word(geometry.cells()),word(geometry.planes()),word(geometry.micro()),true,h,totals.endOffset(),a);
            var definitions=GpuGasFixture.gasDefinitions();var gas=new VulkanGasStateLayout(mmm.spatial.SpatialGrid.rectangular(grid,grid,grid),n,definitions.definitions(),gasGeometry.endOffset(),a);
            var thermal=VulkanGasThermodynamics.layout(gas,VulkanGasThermodynamics.tables(gas.definitions(),definitions.gas(),definitions.thermal(),mmm.fluid.TestFluidDefinitions.load().chemistry()),Math.toIntExact(gas.endOffset()/4),gas.endOffset()+8L*n,a);
            var mixture=VulkanMixtureState.layout(new VulkanMixtureState.Inputs(n,word(geometry.cells()),4,word(geometry.cells())+2,4,
                    word(geometry.cells())+4,4,thermal,h),thermal.endOffset(),a);
            int size=n;long[] cursor={mixture.endOffset()};var starts=column(cursor,size+1,4,a);var columns=column(cursor,7*n+gas.nodeCapacity(),4,a);var values=column(cursor,columns.count(),8,a);var rows=column(cursor,size,4,a);
            var solution=column(cursor,size,8,a);var work=column(cursor,5*size,8,a);var problem=column(cursor,2*size+1,8,a);var control=column(cursor,16,8,a);
            var pressure=new VulkanCoupledPressure.PressureBuffers(starts,columns,values,rows,solution,work,problem,control);
            int faceCount=geometry.faces().count()/4;
            var first=new mmm.world.MovingBodyFluidCoupling.Request(new java.util.UUID(0,1),new mmm.world.MovingBodyFluidCoupling.Bounds(.5,.75,.75,1.25,1.25,1.5),.4,.2,0,.875,1.0,1.125,0,0,.5,-1,0);
            var second=new mmm.world.MovingBodyFluidCoupling.Request(new java.util.UUID(0,2),new mmm.world.MovingBodyFluidCoupling.Bounds(.75,.875,1,1.5,1.375,1.75),.4,.2,0);
            var config=new VulkanEntityBodyCoupling.Configuration(new mmm.fluid.EntityBodyState(List.of(first,second)),grid,grid,grid,h);
            var coupled=new VulkanCoupledPressure.Layout(n,n,faceCount,faceCount+4,2*faceCount+24*config.cellEntries(),pressure,cursor[0],a);
            cursor[0]=coupled.endOffset();
            var temperatures=column(cursor,liquid.slotCount(),8,a);
            var body=new VulkanEntityBodyCoupling.Layout(mmm.spatial.SpatialGrid.rectangular(grid,grid,grid),4,2*config.cellEntries(),size,geometry.masks(),geometry.cells(),geometry.faces(),geometry.occupied(),cursor[0],a);
            var viscous=new VulkanLiquidViscosity.Layout(liquid,geometry.cells(),geometry.faces(),geometry.walls(),word(temperatures),-1,
                    new VulkanLiquidViscosity.Tables(new int[]{0,1,2},new double[]{273,1000,.001,.003,273,1000,.05,.1}),body.prescribedContacts(),false,body.endOffset(),a);
            body.bindPressure(mixture,coupled);body.bindViscosity(viscous);body.bindSourceOccupation(body);
            var initial=ByteBuffer.allocate(Math.toIntExact(viscous.endOffset())).order(ByteOrder.LITTLE_ENDIAN);liquid.importState(initial,fixture.inventory(),fixture.momentum());
            totals.initializeHeader(initial);gasGeometry.initializeHeader(initial);thermal.initializeHeaderAndTables(initial);mixture.initializeHeader(initial);coupled.initializeHeader(initial);viscous.initializeHeaderAndTables(initial);
            initial.duplicate().position(Math.toIntExact(body.header.offset())).put(body.headerBytes());body.initializeBodies(initial,config);
            var source=fixture.inventory();for(int c=0;c<n;c++){
                double v=0,m=0;for(int row=source.cellOffsets()[c];row<source.cellOffsets()[c+1];row++){v+=source.volumes().valueAt(row).toDouble();m+=mmm.units.Mass.kilograms(source.massUnits()[row]);}
                int offset=Math.toIntExact(geometry.cells().offset()+32L*c);initial.putDouble(offset,v).putDouble(offset+8,m).putDouble(offset+16,1);
                initial.putDouble(Math.toIntExact(solution.offset()+8L*c),rho*g*(grid*h-(c/grid%grid+.5)*h));
                initial.putInt(Math.toIntExact(rows.offset()+4L*c),c);
            }
            for(int row=0;row<liquid.slotCount();row++){
                int material=initial.getInt(Math.toIntExact(liquid.column(VulkanLiquidStateLayout.Field.MATERIALS).offset()+4L*row));
                initial.putDouble(Math.toIntExact(temperatures.offset()+8L*row),material==1?393.15:293.15);
            }
            int firstRow=initial.getInt(Math.toIntExact(liquid.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS).offset()));
            int last=initial.getInt(Math.toIntExact(liquid.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS).offset()+4));
            double weighted=0,totalVolume=0;
            for(int row=firstRow;row<last;row++){
                int material=initial.getInt(Math.toIntExact(liquid.column(VulkanLiquidStateLayout.Field.MATERIALS).offset()+4L*row));
                double volume=initial.getDouble(Math.toIntExact(liquid.column(VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES).offset()+8L*row));
                double fraction=((material==1?393.15:293.15)-273)/727;
                weighted+=volume*(material==1?.05+.05*fraction:.001+.002*fraction);totalVolume+=volume;
            }
            double expectedMu=weighted/totalVolume;
            for(int f=0;f<geometry.faces().count()/4;f++){int offset=Math.toIntExact(geometry.faces().offset()+32L*f);initial.putDouble(offset,100).putDouble(offset+8,0).putDouble(offset+16,1).putDouble(offset+24,1);}
            double expectedUnion=.46875;long exactVolume=0;double sumWork=0;mmm.world.MovingBodyFluidCoupling.Result result=null;
            try(var storage=new VulkanRegionStorage(context,viscous.endOffset(),1024*1024,liquid.grid())){
                storage.initialize(initial,0);for(int step=0;step<4;step++)try(var capture=storage.capture();var candidate=storage.prepare(capture)){
                    long begin=System.nanoTime();var feedback=candidate.beginBatch((cmd,input,output,errors,staging,stack)->{
                        var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);bodiesKernel.rebindRanges(arena,err);viscosityKernel.rebindRanges(new FlipKernelBindings.BufferRange(input),arena,err);
                        bodiesKernel.recordGeometry(cmd,body,h);viscosityKernel.record(cmd,viscous,h,dt);bodiesKernel.recordPressure(cmd,body,h,dt);bodiesKernel.recordResult(cmd,body,h,dt);
                    },body.column(VulkanEntityBodyCoupling.Field.BODY_RESULTS).offset(),VulkanEntityBodyCoupling.BODY_RESULT_BYTES*config.bodies().bodies().size());
                    if(feedback.error()!=0)throw new IllegalStateException("Entity body candidate error="+feedback.error());int error=candidate.finishBatch((cmd,input,output,errors,staging,stack)->{});if(error!=0)throw new IllegalStateException("Entity body finish error="+error);
                    var occupied=ByteBuffer.allocate(8*n).order(ByteOrder.LITTLE_ENDIAN);candidate.read(body.column(VulkanEntityBodyCoupling.Field.OCCUPIED_SVQ).offset(),occupied);exactVolume=0;for(int c=0;c<n;c++)exactVolume+=occupied.getLong(8*c);
                    int face=liquid.grid().faceAt(2,2,2,0);
                    var faceArea=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                    candidate.read(body.column(VulkanEntityBodyCoupling.Field.FACE_AREA).offset()+8L*face,faceArea);
                    if(Math.abs(faceArea.getDouble(0)-.75)>1e-12)
                        throw new IllegalStateException("Moving bodies did not close their shared face by geometric union");
                    var sourceWork=ByteBuffer.allocate(8*n).order(ByteOrder.LITTLE_ENDIAN);candidate.read(body.column(VulkanEntityBodyCoupling.Field.SOURCE_WORK).offset(),sourceWork);sumWork=0;for(int c=0;c<n;c++)sumWork+=sourceWork.getDouble(8*c);
                    result=body.result(feedback.feedback(),config,sumWork);
                    requireContactVelocity(candidate,body,liquid.grid(),first,h,dt,solution,viscous,result);
                    double viscousWork=sum(candidate,viscous.column(VulkanLiquidViscosity.Field.SOURCE_CELL_WORK));
                    double viscousHeat=sum(candidate,viscous.column(VulkanLiquidViscosity.Field.SOURCE_CELL_HEAT));
                    double beforeK=sum(candidate,viscous.column(VulkanLiquidViscosity.Field.SOURCE_FACE_K));
                    double afterK=sum(candidate,viscous.column(VulkanLiquidViscosity.Field.FINAL_FACE_K));
                    var firstMu=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);candidate.read(viscous.column(VulkanLiquidViscosity.Field.MU).offset(),firstMu);
                    if(Math.abs(firstMu.getDouble(0)-expectedMu)>1e-12)
                        throw new IllegalStateException("Liquid viscosity lost phase volume or temperature dependence");
                    if(viscousWork<=0||viscousHeat<=0||Math.abs(viscousWork+beforeK-afterK-viscousHeat)>1e-8*Math.max(1,viscousWork))
                        throw new IllegalStateException("Moving body viscous work, kinetic energy and heat differ");
                    if(Math.abs(exactVolume/134217728.0-expectedUnion)>n/134217728.0||Math.abs(sumWork-viscousWork)>1e-9)
                        throw new IllegalStateException("Entity union or actual source work differs from body work");
                    candidate.commit();System.out.printf("step=%d volume=%.9g impulse=(%.9g,%.9g,%.9g) work=%.9g wall_ms=%.3f%n",step,result.coupledVolumeCubicMetres(),result.impulseXKilogramMetresPerSecond(),result.impulseYKilogramMetresPerSecond(),result.impulseZKilogramMetresPerSecond(),result.externalWorkJoules(),(System.nanoTime()-begin)*1e-6);
                }
            }
            if(result==null||result.impulseXKilogramMetresPerSecond()<=0||result.impulseYKilogramMetresPerSecond()>=0||Math.abs(result.coupledVolumeCubicMetres()-expectedUnion)>1e-12)
                throw new IllegalStateException("Prescribed shear, buoyancy load or exact union did not complete");
            vacuumClosure(context);
            ambientClosure(context);
            System.out.printf("Entity module complete: exactUnionSvq=%d externalWork=%.12g sourceWork=%.12g%n",exactVolume,result.externalWorkJoules(),sumWork);
        }
    }
    private static void requireContactVelocity(VulkanRegionStorage.Candidate candidate,VulkanEntityBodyCoupling.Layout body,
                                               mmm.spatial.SpatialGrid grid,mmm.world.MovingBodyFluidCoupling.Request source,double h,double dt,
                                               VulkanLiquidStateLayout.Column pressure,VulkanLiquidViscosity.Layout viscosity,mmm.world.MovingBodyFluidCoupling.Result result){
        var count=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);candidate.read(body.column(VulkanEntityBodyCoupling.Field.CONTROL).offset(),count);
        int contacts=count.getInt(8),capacity=body.contactCapacity;
        var owners=ByteBuffer.allocate(4*capacity).order(ByteOrder.LITTLE_ENDIAN);candidate.read(body.column(VulkanEntityBodyCoupling.Field.CONTACT_BODY).offset(),owners);
        var faces=ByteBuffer.allocate(4*capacity).order(ByteOrder.LITTLE_ENDIAN);candidate.read(body.column(VulkanEntityBodyCoupling.Field.CONTACT_FACE).offset(),faces);
        var speeds=ByteBuffer.allocate(8*capacity).order(ByteOrder.LITTLE_ENDIAN);candidate.read(body.column(VulkanEntityBodyCoupling.Field.CONTACT_SPEED).offset(),speeds);
        var rows=ByteBuffer.allocate(8*capacity).order(ByteOrder.LITTLE_ENDIAN);candidate.read(body.column(VulkanEntityBodyCoupling.Field.PRESSURE_ROW).offset(),rows);
        var coefficients=ByteBuffer.allocate(16*capacity).order(ByteOrder.LITTLE_ENDIAN);candidate.read(body.column(VulkanEntityBodyCoupling.Field.PRESSURE_COEFFICIENT).offset(),coefficients);
        var shear=ByteBuffer.allocate(8*capacity).order(ByteOrder.LITTLE_ENDIAN);candidate.read(viscosity.column(VulkanLiquidViscosity.Field.CONTACT_IMPULSE).offset(),shear);
        var pressures=ByteBuffer.allocate(Math.toIntExact(pressure.bytes())).order(ByteOrder.LITTLE_ENDIAN);candidate.read(pressure.offset(),pressures);
        double[] torque=new double[3];boolean angular=false;
        for(int i=0;i<contacts;i++)if(owners.getInt(4*i)==0){
            int face=faces.getInt(4*i),axis=grid.faceAxis(face);double[] point=new double[3];
            for(int a=0;a<3;a++)point[a]=(grid.faceCoordinate(face,a)+(a==axis?0:.5))*h;
            double expected=source.surfaceVelocity(axis,point[0],point[1],point[2]);
            if(Math.abs(speeds.getDouble(8*i)-expected)>1e-12)throw new AssertionError("Contact did not use surface velocity");
            double linear=axis==0?source.velocityXMetresPerSecond():axis==1?source.velocityYMetresPerSecond():source.velocityZMetresPerSecond();
            angular |= Math.abs(expected-linear)>1e-8;
            double impulse=shear.getDouble(8*i);
            for(int side=0;side<2;side++){int row=rows.getInt(8*i+4*side);if(row>=0)impulse+=dt*h*h*h*coefficients.getDouble(16*i+8*side)*Math.max(0,pressures.getDouble(8*row));}
            double[] r={point[0]-source.centerXMetres(),point[1]-source.centerYMetres(),point[2]-source.centerZMetres()};
            torque[(axis+1)%3]+=r[(axis+2)%3]*impulse;torque[(axis+2)%3]-=r[(axis+1)%3]*impulse;
        }
        if(!angular)throw new AssertionError("Body contacts did not sample angular velocity");
        var impulse=result.bodyImpulses().stream().filter(value->value.bodyId().equals(source.bodyId())).findFirst().orElseThrow();
        double[] actual={impulse.angularImpulseX(),impulse.angularImpulseY(),impulse.angularImpulseZ()};
        for(int axis=0;axis<3;axis++)if(Math.abs(actual[axis]-torque[axis])>1e-8*Math.max(1,Math.abs(torque[axis])))
            throw new AssertionError("Body angular impulse lost its contact point");
    }

    private static void ambientClosure(VulkanDeviceContext context){
        int faces=6;long alignment=Math.max(8,context.storageOffsetAlignment());long[] cursor={0};
        var geometry=column(cursor,4*faces,8,alignment);var flux=column(cursor,2*faces,8,alignment);
        var mixture=column(cursor,7,8,alignment);var masks=column(cursor,1,4,alignment);
        var occupied=column(cursor,1,8,alignment);var gas=column(cursor,9,8,alignment);
        try(var kernel=new VulkanMixtureFlux(context)){
            for(boolean sealed:new boolean[]{false,true}){
                var layout=new VulkanMixtureFlux.Layout(mmm.spatial.SpatialGrid.rectangular(1,1,1),geometry,flux,true,mixture,masks,occupied,gas,sealed,cursor[0],alignment);
                var initial=ByteBuffer.allocate(Math.toIntExact(layout.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
                initial.duplicate().position(Math.toIntExact(layout.header().offset())).put(layout.headerBytes());
                initial.putDouble(Math.toIntExact(mixture.offset()+16),.125);
                initial.putDouble(Math.toIntExact(gas.offset()+16),1.0);
                initial.putLong(Math.toIntExact(occupied.offset()),16777216L);
                int face=1;initial.putDouble(Math.toIntExact(geometry.offset()+8L*(4*face+1)),1.0);
                initial.putDouble(Math.toIntExact(geometry.offset()+8L*(4*face+2)),1.0);
                try(var storage=new VulkanRegionStorage(context,layout.endOffset(),1024*1024,mmm.spatial.SpatialGrid.rectangular(1,1,1))){
                    storage.initialize(initial,0);
                    try(var source=storage.capture();var candidate=storage.prepare(source)){
                        int status=candidate.execute(0,(cmd,input,output,errors,staging,stack)->{
                            kernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                            kernel.recordAnalyze(cmd,layout,.5,.05);
                        });
                        if(status!=(sealed?4096:0))throw new AssertionError("Ambient closure status="+status+" sealed="+sealed);
                        if(!sealed){var accepted=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
                            candidate.read(flux.offset()+16L*face,accepted);
                            if(Math.abs(accepted.getDouble(0)-1.0)>1e-12||accepted.getDouble(8)!=0.0)
                                throw new AssertionError("Closing gas did not leave through the ambient face");}
                    }
                }
            }
        }
    }
    private static void vacuumClosure(VulkanDeviceContext context){
        int side=3,cells=27,faces=3*4*3*3,center=13,face=mmm.spatial.SpatialGrid.rectangular(side,side,side).faceAt(2,1,1,0);long alignment=Math.max(8,context.storageOffsetAlignment());long[] cursor={0};
        var geometry=column(cursor,4*faces,8,alignment);var flux=column(cursor,2*faces,8,alignment);
        var mixture=column(cursor,7*cells,8,alignment);var masks=column(cursor,cells,4,alignment);
        var occupied=column(cursor,cells,8,alignment);var gas=column(cursor,9*cells,8,alignment);
        var layout=new VulkanMixtureFlux.Layout(mmm.spatial.SpatialGrid.rectangular(side,side,side),geometry,flux,true,mixture,masks,occupied,gas,true,cursor[0],alignment);
        try(var kernel=new VulkanMixtureFlux(context);var storage=new VulkanRegionStorage(context,layout.endOffset(),1024*1024,mmm.spatial.SpatialGrid.rectangular(side,side,side))){
            for(boolean closed:new boolean[]{true,false}){
                var initial=ByteBuffer.allocate(Math.toIntExact(layout.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
                initial.duplicate().position(Math.toIntExact(layout.header().offset())).put(layout.headerBytes());
                for(int c=0;c<cells;c++)initial.putDouble(Math.toIntExact(mixture.offset()+8L*(7*c+2)),.125);
                for(int f=0;f<faces;f++)initial.putDouble(Math.toIntExact(geometry.offset()+8L*(4*f)),1).putDouble(Math.toIntExact(geometry.offset()+8L*(4*f+2)),1);
                initial.putDouble(Math.toIntExact(geometry.offset()+8L*(4*face+1)),1);
                initial.putDouble(Math.toIntExact(flux.offset()+16L*face),.1);
                if(closed)initial.putLong(Math.toIntExact(occupied.offset()+8L*center),16777216L);
                storage.initialize(initial,0);
                try(var source=storage.capture();var candidate=storage.prepare(source)){
                    int status=candidate.execute(0,(cmd,input,output,errors,staging,stack)->{
                        kernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                        kernel.recordAnalyze(cmd,layout,.5,.05);
                    });
                    if(status!=0)throw new IllegalStateException("Vacuum occupation failed: "+status);
                    var velocity=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);candidate.read(geometry.offset()+8L*(4*face+1),velocity);
                    double accepted=velocity.getDouble(0);
                    if(closed?accepted!=0.0:!(accepted>0.0&&accepted<=1.0))
                        throw new IllegalStateException("Vacuum occupation did not follow its open geometry: "+accepted);
                }
            }
        }
    }
    private static int word(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    private static double sum(VulkanRegionStorage.Candidate candidate,VulkanLiquidStateLayout.Column column){var data=ByteBuffer.allocate(Math.toIntExact(column.bytes())).order(ByteOrder.LITTLE_ENDIAN);candidate.read(column.offset(),data);double sum=0;for(int i=0;i<data.capacity();i+=8)sum+=data.getDouble(i);return sum;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int n,int width,long a){long offset=(cursor[0]+a-1)&-a;var c=new VulkanLiquidStateLayout.Column(offset,n,width);cursor[0]=offset+c.bindingBytes();return c;}
}
