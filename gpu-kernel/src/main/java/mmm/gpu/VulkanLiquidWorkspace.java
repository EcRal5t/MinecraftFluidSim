package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static mmm.gpu.VulkanLiquidStateLayout.Field.*;
import static mmm.gpu.VulkanLiquidRegion.*;
import mmm.gpu.VulkanLiquidWorkspaceSections.*;
import static org.lwjgl.vulkan.VK10.*;

final class VulkanLiquidWorkspace {
    final VulkanLiquidRegion region;
    private final VulkanLiquidWorkspaceSections sections;
    private final VulkanLiquidThermalStages thermalStages;
        final VulkanLiquidStateLayout liquid;
        final VulkanLiquidStateLayout.Column activityCells, activityFaces;
        final VulkanCommandTemplates templates;
        final EnumMap<GeometryField,VulkanLiquidStateLayout.Column> geometry=new EnumMap<>(GeometryField.class);
        final EnumMap<PressureField,VulkanLiquidStateLayout.Column> pressureColumns=new EnumMap<>(PressureField.class);
        final VulkanPressureMultigrid.Layout hierarchy;
        final VulkanMixtureState.Layout mixture;
        final VulkanLiquidStateLayout.Column multigridColumn;
        final Transport transport;
        final long bytes;
        final int contactCapacity;
        final int thermalContactCapacity,thermalBathCapacity;
        final int bodyCapacity,bodyEntries;
        mmm.fluid.EntityBodyState sourceBodyState,targetBodyState;
        final GranularReserves granularReserves;
        final int coherentSpeciesCapacity;
        final long ambientMassUnits;
        String capacityStage="source geometry";
        mmm.world.MovingBodyFluidCoupling.Result bodyResult(VulkanRegionStorage.Candidate candidate){
            if(transport==null||transport.entity==null)return mmm.world.MovingBodyFluidCoupling.Result.NONE;
            var bytes=ByteBuffer.allocate(Math.multiplyExact(VulkanEntityBodyCoupling.BODY_RESULT_BYTES,targetBodyState.bodies().size())).order(ByteOrder.LITTLE_ENDIAN);
            if(bytes.hasRemaining())candidate.read(transport.entity.column(VulkanEntityBodyCoupling.Field.BODY_RESULTS).offset(),bytes);
            var ledger=ByteBuffer.allocate(Math.toIntExact(transport.entityWork.result().bytes())).order(ByteOrder.LITTLE_ENDIAN);
            candidate.read(transport.entityWork.result().offset(),ledger);
            int sign=ledger.getInt(0),count=ledger.getInt(8);var value=java.math.BigInteger.ZERO;
            for(int i=count-1;i>=0;i--)value=value.shiftLeft(32).add(java.math.BigInteger.valueOf(Integer.toUnsignedLong(ledger.getInt(16+4*i))));
            double work=sign==0?0.0:new mmm.units.SignedDyadic(sign<0?value.negate():value,ledger.getInt(4)).toDouble();
            return transport.entity.result(bytes.flip(),region.bodyConfiguration(targetBodyState),work);
        }
        VulkanLiquidWorkspace(VulkanLiquidRegion region,VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions) {
            this(region,liquid,definitions,region.granularConfiguration==null?0:region.granularConfiguration.contactCapacity());
        }
        VulkanLiquidWorkspace(VulkanLiquidRegion region,VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,int contactCapacity) {
            this(region,liquid,definitions,contactCapacity,0);
        }
        VulkanLiquidWorkspace(VulkanLiquidRegion region,VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,int contactCapacity,int thermalContactCapacity) {
            this(region,liquid,definitions,contactCapacity,thermalContactCapacity,0);
        }
        VulkanLiquidWorkspace(VulkanLiquidRegion region,VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,int contactCapacity,int thermalContactCapacity,int thermalBathCapacity) {
            this(region,liquid,definitions,contactCapacity,thermalContactCapacity,thermalBathCapacity,region.initialGranularReserves!=null?region.initialGranularReserves:region.granularConfiguration==null?null:new GranularReserves(Math.max(1,2*region.granularConfiguration.initial().particles().size()),Math.max(1,2*region.granularConfiguration.initial().deposits().size()),Math.max(1,2*region.granularConfiguration.speciesCapacity())));
        }
        VulkanLiquidWorkspace(VulkanLiquidRegion region,VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,int contactCapacity,int thermalContactCapacity,int thermalBathCapacity,GranularReserves granularReserves){
            this(region,liquid,definitions,contactCapacity,thermalContactCapacity,thermalBathCapacity,granularReserves,region.coherentConfiguration==null?0:region.coherentConfiguration.speciesCount());
        }
        VulkanLiquidWorkspace(VulkanLiquidRegion region,VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,int contactCapacity,int thermalContactCapacity,int thermalBathCapacity,GranularReserves granularReserves,int coherentSpeciesCapacity){
            this.region=region;
            this.ambientMassUnits=region.gasConfiguration==null?0:VulkanGasAmbient.cellMassUnits(region.gasConfiguration.ambient(),region.gasConfiguration.cellSize(),region.gasConfiguration.sealedExterior());
            this.templates=new VulkanCommandTemplates(region.context);
            this.sourceBodyState=region.currentInputs.bodies();
            this.targetBodyState=region.currentInputs.bodies();
            this.coherentSpeciesCapacity=coherentSpeciesCapacity;
            bodyCapacity=region.requestedBodyCapacity;bodyEntries=region.requestedBodyEntries;
            this.liquid=liquid;this.contactCapacity=contactCapacity;this.thermalContactCapacity=thermalContactCapacity;this.thermalBathCapacity=thermalBathCapacity;this.granularReserves=granularReserves;
        int cells = liquid.cellCount(),rows=Math.addExact(cells,
                granularReserves==null?0:Math.multiplyExact(3,granularReserves.particles()));
        hierarchy = VulkanPressureMultigrid.layout(liquid.sizeX(), liquid.sizeY(), liquid.sizeZ(),rows);
        int faces = Math.addExact(Math.addExact(liquid.column(MOMENTUM_X).count(),
                liquid.column(MOMENTUM_Y).count()), liquid.column(MOMENTUM_Z).count());
        long cursor = align(liquid.bytes());
        activityCells = new VulkanLiquidStateLayout.Column(cursor, cells, 4);
        cursor = align(cursor + activityCells.bindingBytes());
        activityFaces = new VulkanLiquidStateLayout.Column(cursor, faces, 4);
        cursor = align(cursor + activityFaces.bindingBytes());
        for (GeometryField field : GeometryField.values()) {
            int width = field == GeometryField.FIXED_MASKS ? 4 : 8;
            int count = switch (field) {
                case FIXED_MASKS, OCCUPIED_SOLID -> cells;
                case CELLS, PLANES -> Math.multiplyExact(4, cells);
                case LIQUID_MICRO -> Math.multiplyExact(8, cells);
                case WALL_GEOMETRY -> Math.multiplyExact(12, cells);
                case FACES -> Math.multiplyExact(4, faces);
                case FLUXES -> Math.multiplyExact(2, faces);
            };
            cursor = align(cursor);
            var column = new VulkanLiquidStateLayout.Column(cursor, count, width);
            geometry.put(field, column);
            cursor = Math.addExact(cursor, column.bytes());
        }
        for (PressureField field : PressureField.values()) {
            int width = switch (field) { case STARTS, COLUMNS, ROWS -> 4; default -> 8; };
            int count = switch (field) {
                case STARTS -> Math.addExact(rows, 1);
                case COLUMNS, VALUES -> Math.addExact(Math.multiplyExact(cells, 7),rows-cells);
                case ROWS, PRESSURE -> rows;
                case WORK -> Math.multiplyExact(rows, 5);
                case PROBLEM -> Math.addExact(Math.multiplyExact(rows, 2), 1);
                case CONTROL -> 16;
                case PARTIALS -> Math.multiplyExact((rows + 255) / 256, 4);
            };
            cursor = align(cursor);
            var column = new VulkanLiquidStateLayout.Column(cursor, count, width);
            pressureColumns.put(field, column);
            cursor = Math.addExact(cursor, column.bytes());
        }
        multigridColumn = new VulkanLiquidStateLayout.Column(align(cursor), hierarchy.workspaceDoubles(), 8);
        long total = align(multigridColumn.offset() + multigridColumn.bytes());
        sections = new VulkanLiquidWorkspaceSections(this);
        if(definitions==null){
            mixture=liquidOnlyMixture(total);
            transport=null;
            total=mixture.endOffset();
        } else {
            transport=new Transport(definitions,total);
            mixture=transport.mixture;
            total=transport.endOffset;
        }
        thermalStages = new VulkanLiquidThermalStages(this);
        bytes=total;
        if(bytes>region.maximumRange){
            long gasBytes=transport==null||transport.gas==null?0:transport.gas.endOffset-transport.gas.state.baseOffset();
            long granularBytes=transport==null||transport.granular==null?0:transport.granular.endOffset-transport.granular.state.baseOffset();
            long reactionBytes=transport==null||transport.reactions==null?0:transport.reactions.endOffset()-4L*transport.reactions.definitions().word();
            long progressBytes=transport==null||transport.reactions==null?0:transport.reactions.progress().bytes();
            throw new IllegalArgumentException("Resident workspace exceeds device descriptor range: bytes="+bytes+" limit="+region.maximumRange
                    +" gasBytes="+gasBytes+" granularBytes="+granularBytes+" reactionBytes="+reactionBytes+" progressBytes="+progressBytes);
        }

        }

        private void configureExterior(VulkanLiquidGeometry kernel){
            kernel.setExterior(region.gasConfiguration==null||region.gasConfiguration.sealedExterior(),mmm.units.Mass.kilograms(ambientMassUnits));
        }
        private VulkanMixtureState.Layout liquidOnlyMixture(long base){
            int cells=liquid.cellCount(),cellWord=word(geometry.get(GeometryField.CELLS));
            return VulkanMixtureState.layout(new VulkanMixtureState.Inputs(cells,cellWord,4,cellWord+2,4,cellWord+4,4,
                    null,region.requestCellSize,1.0,cellWord),base,liquid.alignment());
        }
        void recordMixtureGeometry(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,double duration,boolean reuseGeometry){
            int sx=liquid.sizeX(),sy=liquid.sizeY(),sz=liquid.sizeZ();
            configureExterior(region.kernel);
            if(region.kernels==null)region.kernel.recordTotals(command,sx,sy,sz);
            else region.kernels.timed(command,"source-liquid-totals",()->region.kernel.recordTotals(command,sx,sy,sz));
            if(transport!=null&&transport.gas!=null){
                if(reuseGeometry)transport.gas.recordThermodynamics(command,output,errors,duration);
                else transport.gas.recordSource(command,output,errors,duration,region.kernel);
            } else {
                region.mixtureKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                region.mixtureKernel.record(command,mixture,region.requestCellSize,duration);
                if(!reuseGeometry)region.kernel.recordShapes(command,sx,sy,sz);
            }
            if(reuseGeometry)region.kernel.recordVelocities(command,sx,sy,sz);
        }
        /** Prepare derived geometry for a replacement inventory in its private candidate. */
        int prepareImportedGeometry(VulkanRegionStorage.Candidate candidate, VulkanEntityBodyCoupling.Configuration bodies) {
            return candidate.finishBatch((command, input, output, errors, staging, stack) ->
                    recordImportedGeometry(command, output, errors, stack, bodies));
        }

        int prepareInitialGeometry(VulkanLiquidWorkspace source, VulkanRegionStorage.Candidate candidate,
                                   VulkanEntityBodyCoupling.Configuration bodies) {
            var headers = source == this ? List.<HeaderWrite>of() : transport.headers(source.liquid);
            return candidate.execute(headerBytes(headers), (command, input, output, errors, staging, stack) -> {
                if (source != this) {
                    migrate(command, source, input, output, stack, headers);
                    VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                            VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, stack);
                    copyColumn(command, input, source.transport.inventoryPosting.result(), output,
                            transport.inventoryPosting.result(), stack);
                    VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
                }
                recordImportedGeometry(command, output, errors, stack, bodies);
            });
        }

        private void recordImportedGeometry(org.lwjgl.vulkan.VkCommandBuffer command, GpuBuffer output, GpuBuffer errors,
                                            org.lwjgl.system.MemoryStack stack, VulkanEntityBodyCoupling.Configuration bodies) {
                if (transport != null) transport.recordSolidGeometry(command, output, errors, stack, bodies, bodies);
                region.kernel.rebindRanges(geometryRanges(output, errors, geometry.get(GeometryField.FACES)));
                recordMixtureGeometry(command,output,errors,1.0,false);
                thermalStages.recordImportedThermal(command, output, errors);
        }

        int executeReactions(VulkanRegionStorage.Candidate candidate, VulkanGasThermodynamics.Layout sourceEOS,
                             VulkanGasGeometry.Layout previousGeometry, double duration) {
            return thermalStages.executeReactions(candidate, sourceEOS, previousGeometry, duration);
        }
        int executeThermal(VulkanLiquidWorkspace source, boolean sourceGeometryValid,
                           VulkanRegionStorage.Candidate candidate, double duration) {
            return thermalStages.executeThermal(source, sourceGeometryValid, candidate, duration);
        }
        int execute(VulkanLiquidWorkspace source,boolean sourceGeometryValid,VulkanRegionStorage.Candidate candidate,VulkanLiquidProjection.Parameters parameters,
                    long step,boolean accumulateLedger,boolean finalRequest,double requestDuration) {
            boolean migrating=source!=this;
            boolean reuseGeometry=!migrating&&sourceGeometryValid&&transport!=null&&transport.entity==null;
            if(parameters!=null && transport==null)throw new IllegalStateException("Mechanical pressure requires resident transport");
            int maximumPressureIterations=parameters==null?0:parameters.iterations();
            int predictedPressureIterations=region.previousPressureIterations==0?32
                    :((region.previousPressureIterations+15)/16)*16;
            int firstPressureBatch=Math.min(maximumPressureIterations,predictedPressureIterations);
            int pressureSolverLimit=maximumPressureIterations+1;
            VulkanLiquidProjection.Parameters solverParameters=parameters==null?null:new VulkanLiquidProjection.Parameters(
                    parameters.cellSize(),parameters.duration(),parameters.gravity(),
                    Math.max(parameters.tolerance(),(float)(mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION
                            /(parameters.duration()*parameters.duration()))),parameters.iterations());
            var sourceHeaders=migrating?transport.headers(source.liquid):List.<HeaderWrite>of();
            var stableHeaders=migrating?transport.headers(liquid):List.<HeaderWrite>of();
            long uploadedBytes=headerBytes(sourceHeaders);
            var gas=transport==null?null:transport.gas;
            int pressureRows=pressureColumns.get(PressureField.ROWS).count();
            VulkanPressureKernel.PressureOperator baseOperator=transport!=null?
                    new VulkanCoupledPressure.Operator(region.kernels.coupledKernel,transport.pressure.coupled):null;
            VulkanRegionStorage.ComputeCommands prepare = (command, input, output, errors, staging, stack) -> {
                int sx=liquid.sizeX(),sy=liquid.sizeY(),sz=liquid.sizeZ();
                if(region.kernels!=null&&region.kernels.timings!=null)region.kernels.timings.reset(command);
                Runnable copy=()->{if(migrating)migrate(command,source,input,output,stack,sourceHeaders);else {
                    copyPersistent(command,input,output,stack);
                    if(reuseGeometry)copyReusableGeometry(command,input,output,stack);
                }};
                if(region.kernels==null)copy.run();else region.kernels.timed(command,"source-state-copy",copy);
                if(transport!=null){transport.beginDeviceRequest(command,source.transport,input,output,stack,accumulateLedger);transport.recordSolidGeometry(command,output,errors,stack);}
                recordActivity(command, output, stack);
                region.kernel.rebindRanges(geometryRanges(output,errors,geometry.get(GeometryField.FACES)));
                recordMixtureGeometry(command,output,errors,parameters==null?1.0:parameters.duration(),reuseGeometry);
                if(parameters!=null&&transport!=null){
                    VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                    copyColumn(command,output,mixture.states(),output,transport.sourceMixture,stack);
                    VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                }
                if(parameters!=null) {
                    if(transport!=null) {
                        var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                        region.kernels.liquidThermalKernel.rebindRanges(dst,err);region.kernels.viscosityKernel.rebindRanges(src,dst,err);region.kernels.forceKernel.rebindRanges(dst,err);region.kernels.surfaceKernel.rebindRanges(dst,err);
                        transport.recordBodyContacts(command,output,errors);
                        region.kernels.timed(command,"source-thermal",()->region.kernels.liquidThermalKernel.record(command,transport.sourceThermal));
                        region.kernels.timed(command,"viscosity",()->region.kernels.viscosityKernel.record(command,transport.viscosity,parameters.cellSize(),parameters.duration()));
                        region.kernels.timed(command,"buoyancy",()->region.kernels.forceKernel.recordThermalBuoyancy(command,transport.forces,parameters.duration(),parameters.gravity()));
                        region.kernels.timed(command,"surface-tension",()->region.kernels.surfaceKernel.record(command,transport.surface,parameters.cellSize(),parameters.duration()));
                        region.kernels.mixturePressureKernel.rebindRanges(dst,err);region.kernels.coupledKernel.rebindRanges(dst,err);region.kernels.mixtureFlux.rebindRanges(dst,err);
                        region.kernels.mixturePressureKernel.recordFaces(command,transport.pressure.assembly,parameters.cellSize(),parameters.duration(),parameters.gravity());
                    }
                    var assembly = new ArrayList<FlipKernelBindings.BufferRange>(13);
                    assembly.add(range(output, GeometryField.CELLS));
                    assembly.add(range(output, GeometryField.FACES));
                    for (PressureField field : new PressureField[]{PressureField.STARTS,PressureField.COLUMNS,PressureField.VALUES,
                            PressureField.ROWS,PressureField.PROBLEM,PressureField.PRESSURE})
                        assembly.add(range(output,field));
                    assembly.add(new FlipKernelBindings.BufferRange(errors));
                    assembly.add(new FlipKernelBindings.BufferRange(output, activityCells.offset(), activityCells.bindingBytes()));
                    assembly.add(new FlipKernelBindings.BufferRange(output, activityFaces.offset(), activityFaces.bindingBytes()));
                    assembly.add(new FlipKernelBindings.BufferRange(output, mixture.states().offset(), mixture.states().bindingBytes()));
                    region.projection.rebindRanges(assembly);
                    Runnable assemble=()->{
                        region.projection.prepareCoupled(command,sx,sy,sz,solverParameters,pressureRows);
                    };
                    if(region.kernels==null)assemble.run();else region.kernels.timed(command,"common-pressure-assemble",assemble);
                    if(transport!=null){region.kernels.mixturePressureKernel.recordIncidence(command,transport.pressure.assembly,parameters.cellSize(),parameters.duration(),parameters.gravity());
                        transport.recordBodyPressure(command,parameters.duration());
                        region.kernels.coupledKernel.recordPrepare(command,transport.pressure.coupled,parameters.duration());}
                    var solve = new ArrayList<FlipKernelBindings.BufferRange>(12);
                    for(PressureField field:PressureField.values())solve.add(range(output,field));
                    solve.add(new FlipKernelBindings.BufferRange(errors));
                    region.pressure.rebindRanges(solve);
                    var coarse = new ArrayList<FlipKernelBindings.BufferRange>(8);
                    for(PressureField field : new PressureField[]{PressureField.STARTS,PressureField.COLUMNS,PressureField.VALUES,
                            PressureField.ROWS,PressureField.WORK,PressureField.CONTROL})
                        coarse.add(range(output,field));
                    coarse.add(new FlipKernelBindings.BufferRange(output,multigridColumn.offset(),multigridColumn.bindingBytes()));
                    region.multigrid.rebindRanges(coarse);
                    Runnable initialize=()->region.pressure.recordInitialize(command,pressureRows,pressureRows,pressureSolverLimit,region.multigrid,hierarchy,baseOperator);
                    Runnable firstBatch=()->recordPressureIterations(command,output,errors,pressureSolverLimit,firstPressureBatch,baseOperator);
                    if(region.kernels==null){initialize.run();firstBatch.run();}else{
                        region.kernels.timed(command,"common-pressure-initialize",initialize);
                        region.kernels.timed(command,"common-pressure-first-batch",firstBatch);
                    }
                }
            };
            long[] stageMark=Boolean.getBoolean("mmm.gpuMechanicalStages")?new long[]{region.storage.metrics().gpuNanos(),System.nanoTime()}:null;
            int error;
            if (parameters == null) error = candidate.execute(uploadedBytes, prepare);
            else {
                capacityStage="common pressure";
                boolean pressureDiagnostics=Boolean.getBoolean("mmm.gpuMechanicalPressureDiagnostics");
                long controlOffset=pressureColumns.get(PressureField.CONTROL).offset();
                long runOffset = controlOffset;
                int feedbackBytes=pressureDiagnostics?128:32,runFeedbackOffset=24;
                var gasOperator=baseOperator;
                var result=candidate.beginBatch(uploadedBytes,prepare,runOffset,feedbackBytes);
                error = result.error();
                int dispatched=firstPressureBatch;
                while(error==0 && result.feedback().getDouble(runFeedbackOffset)!=0.0 && dispatched<maximumPressureIterations){
                    int count=Math.min(16,maximumPressureIterations-dispatched);
                    result=candidate.continueBatch((command,input,output,errors,staging,stack)->
                            recordPressureIterations(command,output,errors,pressureSolverLimit,count,gasOperator),runOffset,feedbackBytes);
                    error=result.error();dispatched+=count;
                }
                if(error==0)region.previousPressureIterations=(int)result.feedback().getDouble(0);
                if(pressureDiagnostics)region.pressureProgress=VulkanPressureKernel.Progress.read(result.feedback());
                traceStage("pressure",stageMark);
                if(error==0) {
                    VulkanRegionStorage.ComputeCommands project=(command,input,output,errors,staging,stack)-> {
                        region.pressure.recordFinishMechanical(command,pressureRows,pressureRows,pressureSolverLimit,region.multigrid,hierarchy,gasOperator);
                        region.kernels.mixturePressureKernel.recordClampPressure(command,transport.pressure.assembly,parameters.cellSize(),parameters.duration(),parameters.gravity());
                        region.kernels.coupledKernel.recordProject(command,transport.pressure.coupled,parameters.duration());
                        region.kernels.mixturePressureKernel.recordProjectedVelocities(command,transport.pressure.assembly,parameters.cellSize(),parameters.duration(),parameters.gravity());
                        region.kernel.recordTransportPreferences(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),parameters.cellSize(),parameters.duration());
                        region.kernels.mixtureFlux.recordAnalyze(command,transport.pressure.constraint,parameters.cellSize(),parameters.duration());
                    };
                    {
                        var accepted=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                            project.record(command,input,output,errors,staging,stack);
                        },transport.pressure.constraint.control().offset(),8);
                        if(accepted.error()==4096&&(transport.entity!=null||transport.dynamics!=null))
                            throw new MotionTimeRefinement("Moved solid leaves liquid without an open route");
                        if(accepted.error()!=0)return accepted.error();
                        int fluidLimit=accepted.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0);
                        if(fluidLimit!=0)throw new MotionTimeRefinement("Fluid transport exceeds the available donor inventory: "+fluidLimit+", cell="+accepted.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(4)+", dt="+parameters.duration());
                    }
                    VulkanRegionStorage.ComputeCommands finishProjection=(command,input,output,errors,staging,stack)->{
                        if(transport.entity!=null)region.kernels.entityKernel.recordResult(command,transport.entity,parameters.cellSize(),parameters.duration());
                    };
                    {
                        if(transport.dynamics!=null){
                            capacityStage="granular contact/motion";
                            var motion=transport.dynamics.motion;var contacts=transport.dynamics.contacts;
                            var started=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                                finishProjection.record(command,input,output,errors,staging,stack);
                                region.kernels.contactKernel.recordBeginInterval(command,contacts);
                                region.kernels.motionKernel.recordSpeed(command,motion,parameters.cellSize());
                            },0,0);
                            if(started.error()!=0)return started.error();
                            double remaining=parameters.duration();
                            long segments=0;
                            while(remaining>0.0){
                                if(++segments>mmm.fluid.FluidStepBudget.MAXIMUM_CUMULATIVE_SUBSTEPS)
                                    throw new mmm.fluid.FluidSolverBackend.MechanicalWorkBudgetExceededException("Granular motion exceeded cumulative substep budget");
                                double speed=VulkanGranularMotion.maximumSpeed(candidate,motion);
                                double duration=speed>0.0?Math.min(remaining,0.25*parameters.cellSize()/speed):remaining;
                                if(!(duration>0.0))throw new MotionTimeRefinement("Invalid granular motion interval");
                                int attempts=0;
                                while(true){
                                    double trial=duration;
                                    var moved=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                                        region.kernels.motionKernel.recordContacts(command,motion,parameters.cellSize(),trial);
                                        region.kernels.contactKernel.recordPrepare(command,contacts);region.kernels.contactKernel.recordCompression(command,contacts,16);region.kernels.contactKernel.recordRebound(command,contacts,16);
                                        region.kernels.motionKernel.recordTrialPositions(command,motion,parameters.cellSize(),trial);
                                    },contacts.column(VulkanGranularContacts.Field.CONTROL).offset(),12);
                                    if(moved.error()!=0)return moved.error();
                                    var contactState=moved.feedback().order(ByteOrder.LITTLE_ENDIAN);
                                    if(contactState.getInt(4)>contactCapacity)throw new ContactCapacityGrowth(contactState.getInt(4));
                                    int flags=contactState.getInt(8);
                                    for(int relax=0;flags==2&&relax<8;relax++){
                                        var relaxed=candidate.continueBatch((command,input,output,errors,staging,stack)->
                                                region.kernels.motionKernel.recordRelaxTrial(command,motion,parameters.cellSize(),trial),
                                                contacts.column(VulkanGranularContacts.Field.CONTROL).offset(),12);
                                        if(relaxed.error()!=0)return relaxed.error();
                                        flags=relaxed.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(8);
                                    }
                                    if(flags==0)break;
                                    if(++attempts>mmm.fluid.FluidStepBudget.MAXIMUM_TIME_REFINEMENTS)
                                        throw new MotionTimeRefinement("Granular trial requests time refinement: flags="+flags);
                                    var restored=candidate.continueBatch((command,input,output,errors,staging,stack)->region.kernels.contactKernel.recordRestoreTrial(command,contacts),0,0);
                                    if(restored.error()!=0)return restored.error();
                                    duration*=0.5;
                                }
                                double accepted=duration;
                                var next=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                                    region.kernels.contactKernel.recordAcceptTrial(command,contacts);
                                    region.kernels.motionKernel.recordPublishPositions(command,motion,parameters.cellSize());
                                    region.kernels.granularGeometryKernel.recordSupports(command,transport.granular.geometry,parameters.cellSize());
                                    region.kernels.motionKernel.recordSpeed(command,motion,parameters.cellSize());
                                },0,0);
                                if(next.error()!=0)return next.error();
                                remaining=Math.max(0.0,remaining-accepted);
                            }
                            var moved=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                                region.kernels.contactKernel.recordFinishInterval(command,contacts);
                                transport.recordMotionFinish(command,output,errors,parameters);
                                region.kernel.recordTransportPreferences(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),parameters.cellSize(),parameters.duration());
                            },0,0);
                            if(moved.error()==64){var feedback=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
                                candidate.read(transport.dynamics.transitions.column(VulkanGranularTransitions.Field.CONTROL).offset(),feedback);
                                int[] required={feedback.getInt(0),feedback.getInt(4),feedback.getInt(8)};
                                if(required[0]>granularReserves.particles()||required[1]>granularReserves.deposits()||required[2]>granularReserves.species())throw new GranularCapacityGrowth(required);}
                            if(moved.error()!=0)return moved.error();
                        }
                        capacityStage="liquid donor routes";
                        if(error==0) {
                            capacityStage="accepted volumes/material routes/gather/future gas geometry";
                            VulkanRegionStorage.ComputeCommands finishTransport=(command,input,output,errors,staging,stack)-> {
                                if(transport.dynamics==null)finishProjection.record(command,input,output,errors,staging,stack);
                                transport.recordInitialize(command,input,output,errors);
                                if(transport.entityWork!=null)region.kernels.ledger.record(command,transport.entityWork);
                                transport.recordFinish(command,output,errors,stack,parameters);
                                transport.recordEnergy(command,output,errors,parameters);
                                if(transport.reactions!=null)region.kernels.activityPending.record(command,output,activityCells,transport.reactions.progress().column(VulkanReactionProgressLayout.Field.PHASE_MOTION_CELLS),transport.reactions.progress().column(VulkanReactionProgressLayout.Field.PHASE_PENDING_SUMMARY));
                            };
                            if(error==0&&(!finalRequest||transport.thermal==null&&transport.reactions==null))error=candidate.finishBatch(migrating?headerBytes(stableHeaders):0,(command,input,output,errors,staging,stack)->{
                                finishTransport.record(command,input,output,errors,staging,stack);
                                if(finalRequest||transport.entity==null)thermalStages.recordLocalState(command,output,errors,parameters.duration());
                                transport.recordLedgers(command,accumulateLedger?source.transport:null,finalRequest,output,errors);
                                if(migrating)writeHeaders(command,output,stableHeaders,stack);
                            });
                            else if(error==0){
                                boolean react=transport.reactions!=null&&(region.activeInputs==null||!region.activeInputs.pendingPhaseSettlementOnly());
                                VulkanRegionStorage.ComputeCommands finishLedgers=(command,input,output,errors,staging,stack)-> {
                                    thermalStages.recordLocalState(command,output,errors,parameters.duration());
                                    transport.recordLedgers(command,accumulateLedger?source.transport:null,finalRequest,output,errors);if(migrating)writeHeaders(command,output,stableHeaders,stack);
                                };
                                if(transport.thermal==null)error=candidate.continueBatch(finishTransport,0,0).error();
                                traceStage("transport-energy",stageMark);
                                boolean thermalFinishes=transport.thermal!=null&&!react;
                                if(error==0&&transport.thermal!=null){capacityStage="thermal contacts/conduction";
                                    error=region.kernels.thermalKernel.execute(candidate,transport.thermal,requestDuration,
                                            source.transport.devices==null?null:source.transport.devices.state,transport.devices==null?null:transport.devices.settlement,false,
                                            finishTransport,thermalFinishes?finishLedgers:null,
                                            thermalFinishes?(migrating?headerBytes(stableHeaders):0):-1);
                                }
                                traceStage("thermal-contacts-conduction",stageMark);
                                if(error==0&&react){capacityStage="liquid reactions";error=executeReactions(candidate,transport.gas==null?null:transport.gas.thermo,transport.gas==null?null:transport.gas.geometry,requestDuration);}
                                if(error==0&&!thermalFinishes)error=candidate.finishBatch(migrating?headerBytes(stableHeaders):0,finishLedgers);
                            }
                        }
                    }
                }
            }
            if(error==4096&&transport!=null&&(transport.dynamics!=null||transport.entity!=null))
                throw new MotionTimeRefinement("Moved solid leaves liquid without open volume");
            traceStage("remaining-energy-ledgers",stageMark);
            if(error==0&&region.kernels!=null&&region.kernels.timings!=null){
                for(var sample:region.kernels.timings.read())System.out.printf("mechanical_kernel=%s gpu_ms=%.3f%n",sample.stage(),sample.milliseconds());
                if(parameters!=null)for(var sample:region.multigrid.stageTimings())System.out.printf("pressure_multigrid=%s gpu_ms=%.3f%n",sample.stage(),sample.milliseconds());
            }
            return error;
        }
        void recordPressureIterations(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,int maximum,int count,VulkanPressureKernel.PressureOperator gasOperator) {
            int rows=pressureColumns.get(PressureField.ROWS).count();
            // Reuse the same compiled command blocks when batching predicted work.
            // Converged stages retain the device's zero-work dispatches.
            for(int remaining=count;remaining>0;){
                int block=Math.min(16,remaining);remaining-=block;
                templates.record(command,new VulkanCommandTemplates.Key(0,output,errors,block,maximum,null),
                        secondary->region.pressure.recordIterations(secondary,rows,rows,maximum,region.multigrid,hierarchy,block,gasOperator));
            }
        }

        private void traceStage(String name,long[] mark){if(mark==null)return;long gpu=region.storage.metrics().gpuNanos(),now=System.nanoTime();
            System.out.printf("mechanical_stage=%s gpu_ms=%.3f wall_ms=%.3f%n",name,(gpu-mark[0])*1e-6,(now-mark[1])*1e-6);mark[0]=gpu;mark[1]=now;}
        IllegalStateException failure(VulkanRegionStorage.Candidate candidate,int error,VulkanLiquidProjection.Parameters parameters) {
            if((error&4)!=0&&parameters!=null) {
                var diagnostics=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);candidate.read(pressureColumns.get(PressureField.CONTROL).offset(),diagnostics);
                var failure=new IllegalStateException("Resident pressure failed: iterations="+(int)diagnostics.getDouble(0)+", residualRms="+diagnostics.getDouble(8)
                        +", target="+parameters.tolerance()+", status="+error+", inner="+region.pressureProgress);
                return failure;
            }
            return new IllegalStateException("Resident liquid candidate failed during "+capacityStage+": status="+error);
        }
        int[] capacities(){return new int[]{liquid.stockCapacity(),liquid.speciesCapacity(),liquid.limbCapacity(),transport.definitions.scratchLimbsPerWindow(),
                transport.gas==null?0:transport.gas.state.componentCapacity(),transport.gas==null?0:transport.gas.state.speciesCapacity()};}
        VulkanLiquidWorkspace grow(int[] required) {
            var definitions=transport.definitions;int cells=liquid.cellCount();
            int stocks=growCount(liquid.stockCapacity(),required[5]);
            int species=growCount(liquid.speciesCapacity(),Math.max(required[6],ceil(required[4],7)));
            int limbNeed=Math.max(required[7],Math.max(required[0]-cells,required[1]-15*cells));
            int limbs=growCount(liquid.limbCapacity(),limbNeed);
            int windows=Math.addExact(Math.multiplyExact(stocks,7),Math.multiplyExact(cells,3));
            int windowLimbs=definitions.scratchLimbsPerWindow();
            if(required[2]>transport.materials.scratchLimbCapacity())windowLimbs=growCount(windowLimbs,ceil(required[2],windows));
            if(stocks==liquid.stockCapacity()&&species==liquid.speciesCapacity()&&limbs==liquid.limbCapacity()&&windowLimbs==definitions.scratchLimbsPerWindow())
                throw new IllegalStateException("Device capacity error 64 after "+capacityStage+" reported no larger CSR requirement: "+java.util.Arrays.toString(required));
            return new VulkanLiquidWorkspace(region,new VulkanLiquidStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),stocks,species,limbs,liquid.alignment(),liquid.materialCount()),
                    definitions.capacities(stocks,species,limbs,windowLimbs),contactCapacity,thermalContactCapacity,thermalBathCapacity,granularReserves,coherentSpeciesCapacity);
        }
        VulkanLiquidWorkspace growContacts(int required){return new VulkanLiquidWorkspace(region,liquid,transport.definitions,growCount(contactCapacity,required),thermalContactCapacity,thermalBathCapacity,granularReserves,coherentSpeciesCapacity);}
        VulkanLiquidWorkspace growThermalContacts(int required,int requiredBaths){
            if(required<=thermalContactCapacity&&requiredBaths<=thermalBathCapacity)throw new IllegalStateException("Thermal capacity feedback did not grow either column");
            return new VulkanLiquidWorkspace(region,liquid,transport.definitions,contactCapacity,growCount(thermalContactCapacity,required),growCount(thermalBathCapacity,requiredBaths),granularReserves,coherentSpeciesCapacity);
        }
        VulkanLiquidWorkspace growReactions(VulkanRegionReactions.CapacityGrowth required){
            int coherentSpecies=growCount(coherentSpeciesCapacity,required.coherentSpecies);
            int species=growCount(liquid.speciesCapacity(),required.species),limbs=growCount(liquid.limbCapacity(),required.limbs);
            var next=granularReserves==null?null:new GranularReserves(growCount(granularReserves.particles(),required.particles),growCount(granularReserves.deposits(),required.deposits),growCount(granularReserves.species(),required.solidSpecies));
            if(species==liquid.speciesCapacity()&&limbs==liquid.limbCapacity()&&java.util.Objects.equals(next,granularReserves)&&coherentSpecies==coherentSpeciesCapacity)throw new IllegalStateException("Reaction feedback did not grow inventory capacity");
            return new VulkanLiquidWorkspace(region,new VulkanLiquidStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),liquid.stockCapacity(),species,limbs,liquid.alignment(),liquid.materialCount()),
                    transport.definitions.capacities(liquid.stockCapacity(),species,limbs,transport.definitions.scratchLimbsPerWindow()),contactCapacity,thermalContactCapacity,thermalBathCapacity,next,coherentSpecies);
        }
        VulkanLiquidWorkspace growGranular(int[] required){var r=granularReserves;var next=new GranularReserves(growCount(r.particles(),required[0]),growCount(r.deposits(),required[1]),growCount(r.species(),required[2]));
            if(next.equals(r))throw new IllegalStateException("Granular lifecycle feedback did not grow inventory");return new VulkanLiquidWorkspace(region,liquid,transport.definitions,contactCapacity,thermalContactCapacity,thermalBathCapacity,next,coherentSpeciesCapacity);}
        /** Candidate scratch is produced by its region.kernels. Persist inventory, constitutive tables, headers and warm caches only. */
        private VulkanBufferCopyPlan persistentCopies;
        void copyPersistent(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack) {
            if(persistentCopies==null){
                var plan=new VulkanBufferCopyPlan.Builder();
                for(var field:VulkanLiquidStateLayout.Field.values()){var c=liquid.column(field);plan.copy(c,c);}
                for(var field:new GeometryField[]{GeometryField.FIXED_MASKS,GeometryField.OCCUPIED_SOLID}){var c=geometry.get(field);plan.copy(c,c);}
                var warm=pressureColumns.get(PressureField.PRESSURE);plan.copy(warm,warm);
                if(transport==null){var c=mixture.header();plan.copy(c,c);}
                if(transport!=null) {
                    for(var c:transport.persistentColumns())plan.copy(c,c);
                    if(transport.gas!=null)for(var f:VulkanGasStateLayout.Field.values()){var c=transport.gas.state.column(f);plan.copy(c,c);}
                    if(transport.granular!=null)for(var f:VulkanGranularStateLayout.Field.values()){var c=transport.granular.state.column(f);plan.copy(c,c);}
                    if(transport.coherent!=null)for(var f:VulkanCoherentStateLayout.Field.values()){var c=transport.coherent.state.column(f);plan.copy(c,c);}
                    if(transport.devices!=null)for(var f:VulkanThermalDeviceLayout.Field.values()){var c=transport.devices.state.column(f);plan.copy(c,c);}
                }
                persistentCopies=plan.build();
            }
            persistentCopies.record(command,input,output,stack);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
        private VulkanBufferCopyPlan reusableGeometryCopies;
        void copyReusableGeometry(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            if(reusableGeometryCopies==null){
                var plan=new VulkanBufferCopyPlan.Builder();
                for(var field:new GeometryField[]{GeometryField.PLANES,GeometryField.LIQUID_MICRO,GeometryField.WALL_GEOMETRY,GeometryField.FACES}){
                    var c=geometry.get(field);plan.copy(c,c);
                }
                if(transport.gas!=null){
                    var gas=transport.gas.geometry;
                    for(var c:new VulkanLiquidStateLayout.Column[]{gas.presence(),gas.dryVolumes(),gas.dryMasks(),gas.faceAreas(),gas.cellNodes(),gas.microFractions()})plan.copy(c,c);
                }
                reusableGeometryCopies=plan.build();
            }
            reusableGeometryCopies.record(command,input,output,stack);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,VulkanLiquidWorkspace source,GpuBuffer input,GpuBuffer output,
                     org.lwjgl.system.MemoryStack stack,List<HeaderWrite> headers) {
            vkCmdFillBuffer(command,output.handle(),0,bytes,0);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
            liquid.copyColumns(command,source.liquid,input,output,stack);
            for(var field:new GeometryField[]{GeometryField.FIXED_MASKS,GeometryField.OCCUPIED_SOLID})
                copyColumn(command,input,source.geometry.get(field),output,geometry.get(field),stack);
            copyColumn(command,input,source.pressureColumns.get(PressureField.PRESSURE),output,pressureColumns.get(PressureField.PRESSURE),stack);
            var from=source.transport;var to=transport;
            var oldTables=new VulkanLiquidStateLayout.Column[]{from.phaseTable,from.massTable,from.materialReference,from.speciesReference};
            var newTables=new VulkanLiquidStateLayout.Column[]{to.phaseTable,to.massTable,to.materialReference,to.speciesReference};
            for(int i=0;i<oldTables.length;i++)copyColumn(command,input,oldTables[i],output,newTables[i],stack);
            var oldThermal=new VulkanLiquidStateLayout.Column[]{from.liquidThermal.offsets(),from.liquidThermal.upper(),from.liquidThermal.capacity(),from.liquidThermal.referenceTemperature(),from.liquidThermal.coldSensible()};
            var newThermal=new VulkanLiquidStateLayout.Column[]{to.liquidThermal.offsets(),to.liquidThermal.upper(),to.liquidThermal.capacity(),to.liquidThermal.referenceTemperature(),to.liquidThermal.coldSensible()};
            for(int i=0;i<oldThermal.length;i++)copyColumn(command,input,oldThermal[i],output,newThermal[i],stack);
            var oldForce=from.forceColumns();var newForce=to.forceColumns();
            for(int i=0;i<oldForce.size();i++)if(!from.forceHeaders().contains(oldForce.get(i)))copyColumn(command,input,oldForce.get(i),output,newForce.get(i),stack);
            if(to.gas!=null)to.gas.migrate(command,from.gas,input,output,stack);
            if(to.granular!=null)to.granular.migrate(command,from.granular,input,output,stack);
            if(to.coherent!=null)to.coherent.migrate(command,from.coherent,input,output,stack);
            if(to.devices!=null)to.devices.migrate(command,from.devices,input,output,stack);
            if(to.reactions!=null){var old=from.reactions.tables();var next=to.reactions.tables();for(int i=0;i<old.size();i++)VulkanRegionStorage.copy(command,input,old.get(i).offset(),output,next.get(i).offset(),old.get(i).bytes().remaining(),stack);
                var oldState=from.reactions.stateColumns();var newState=to.reactions.stateColumns();for(int i=0;i<oldState.size();i++)copyColumn(command,input,oldState.get(i),output,newState.get(i),stack);}
            if(to.thermal!=null){
                copyColumn(command,input,from.bathTemperatures,output,to.bathTemperatures,stack);
                var oldTablesThermal=from.thermal.tables();var newTablesThermal=to.thermal.tables();
                for(int i=0;i<oldTablesThermal.size();i++){var old=oldTablesThermal.get(i);var next=newTablesThermal.get(i);
                    VulkanRegionStorage.copy(command,input,old.offset(),output,next.offset(),old.bytes().remaining(),stack);}
            }
            writeHeaders(command,output,headers,stack);
        }
        void writeHeaders(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,List<HeaderWrite> headers,org.lwjgl.system.MemoryStack stack) {
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
            for(var header:headers)vkCmdUpdateBuffer(command,output.handle(),header.offset(),header.bytes().duplicate());
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    void recordActivity(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,org.lwjgl.system.MemoryStack stack) {
        VulkanActivityPending.recordInput(region,liquid,activityCells,activityFaces,command,output,stack);
    }
    List<FlipKernelBindings.BufferRange> geometryRanges(GpuBuffer buffer,GpuBuffer errors,VulkanLiquidStateLayout.Column faces) {
        return geometryRanges(buffer,errors,faces,occupied());
    }
    List<FlipKernelBindings.BufferRange> geometryRanges(GpuBuffer buffer,GpuBuffer errors,VulkanLiquidStateLayout.Column faces,VulkanLiquidStateLayout.Column solid) {
        return VulkanLiquidGeometry.ranges(liquid,geometry,buffer,errors,faces,solid);
    }

    final class Transport {
        final MechanicalDefinitions definitions;
        final VulkanLiquidStateLayout.Column futureFaces,phaseTable,massTable,materialReference,speciesReference;
        final VulkanLiquidRouteLayout routes;
        final VulkanLiquidCellVolumes.Layout volumes;
        final GasSection gas;
        final VulkanMixtureState.Layout mixture;
        final VulkanLiquidStateLayout.Column sourceMixture;
        final GranularSection granular;
        final CoherentSection coherent;
        final DeviceSection devices;
        final PressureSection pressure;
        final GranularDynamics dynamics;
        final VulkanLiquidThermodynamics.Layout sourceThermal;
        final VulkanLiquidViscosity.Layout viscosity;
        final VulkanLiquidForces.Layout forces;
        final VulkanSurfaceTension.Layout surface;
        final VulkanMechanicalHeatTransport.Layout heatTransport;
        final VulkanLiquidCapacityInputs.Layout capacityInputs;
        final VulkanLiquidAcceptedVolumes.Layout accepted;
        final VulkanLiquidMaterialRoutes.Layout materials;
        final VulkanLiquidRouteQuantities.Layout quantities;
        final VulkanLiquidGather.Layout gather;
        final VulkanLiquidMomentum.Layout momentum;
        final VulkanLiquidThermodynamics.Layout liquidThermal;
        final VulkanLiquidStateLayout.Column bathTemperatures;
        final VulkanRegionThermal.Layout thermal;
        final VulkanRegionReactions.Layout reactions;
        final VulkanEnergyLedger.Layout thermalOnlyRoundoff,thermalOnlyMechanical;
        final VulkanMechanicalBodies.Layout bodies;
        final VulkanMechanicalEnergy.Layout energy;
        final VulkanEntityBodyCoupling.Layout sourceEntity,entity;
        final VulkanEnergyLedger.Layout roundoff,heat,mechanicalPosting,sourcePosting,sinkPosting,inventoryPosting,entityWork;
        final long endOffset;
        Transport(MechanicalDefinitions definitions,long base) {
            this.definitions=definitions;int cells=liquid.cellCount(),stocks=liquid.stockCapacity();
            long[] cursor={base};
            futureFaces=column(cursor,geometry.get(GeometryField.FACES).count(),8);
            phaseTable=column(cursor,definitions.materialPhases().length,4);
            massTable=column(cursor,definitions.speciesMasses().length,8);
            materialReference=column(cursor,definitions.materialReferences().length,8);
            speciesReference=column(cursor,definitions.speciesReferences().length,8);
            routes=new VulkanLiquidRouteLayout(stocks,Math.multiplyExact(liquid.speciesCapacity(),7),cursor[0],liquid.alignment());
            volumes=VulkanLiquidCellVolumes.layout(liquid,Math.addExact(liquid.limbCapacity(),cells),definitions.quantumExponent(),routes.endOffset(),liquid.alignment());
            sourceThermal=VulkanLiquidThermodynamics.layout(liquid,definitions.thermal(),word(materialReference),word(speciesReference),definitions.speciesStride(),volumes.endOffset(),liquid.alignment());
            sourceEntity=bodyCapacity==0?null:new VulkanEntityBodyCoupling.Layout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),bodyCapacity,bodyEntries,pressureColumns.get(PressureField.ROWS).count(),geometry.get(GeometryField.FIXED_MASKS),geometry.get(GeometryField.CELLS),geometry.get(GeometryField.FACES),geometry.get(GeometryField.OCCUPIED_SOLID),sourceThermal.endOffset(),liquid.alignment());
            entity=sourceEntity==null?null:new VulkanEntityBodyCoupling.Layout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),bodyCapacity,bodyEntries,pressureColumns.get(PressureField.ROWS).count(),geometry.get(GeometryField.FIXED_MASKS),geometry.get(GeometryField.CELLS),geometry.get(GeometryField.FACES),geometry.get(GeometryField.OCCUPIED_SOLID),sourceEntity.endOffset(),liquid.alignment());
            viscosity=new VulkanLiquidViscosity.Layout(liquid,geometry.get(GeometryField.CELLS),geometry.get(GeometryField.FACES),geometry.get(GeometryField.WALL_GEOMETRY),word(sourceThermal.temperatures()),word(activityFaces),word(activityCells),
                    definitions.viscosity(),entity==null?null:entity.prescribedContacts(),definitions.noSlip(),entity==null?sourceThermal.endOffset():entity.endOffset(),liquid.alignment());
            forces=VulkanLiquidForces.layout(liquid,definitions.forces(),word(sourceThermal.cellTemperatures()),word(geometry.get(GeometryField.FACES)),-1,word(activityFaces),viscosity.endOffset(),liquid.alignment());
            surface=VulkanSurfaceTension.layout(liquid,definitions.surface(),word(geometry.get(GeometryField.CELLS)),word(geometry.get(GeometryField.FACES)),word(geometry.get(GeometryField.PLANES)),
                    word(geometry.get(GeometryField.FIXED_MASKS)),-1,word(activityFaces),forces.endOffset(),liquid.alignment());
            coherent=region.coherentConfiguration==null?null:sections.new CoherentSection(surface.endOffset());
            devices=region.deviceConfiguration==null?null:sections.new DeviceSection(coherent==null?surface.endOffset():coherent.endOffset);
            long solidsBase=devices!=null?devices.endOffset:coherent!=null?coherent.endOffset:surface.endOffset();
            granular=region.granularConfiguration==null?null:sections.new GranularSection(solidsBase);
            if(entity!=null){var baseSolid=granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ);sourceEntity.bindBaseSolid(baseSolid);entity.bindBaseSolid(baseSolid);entity.bindViscosity(viscosity);sourceEntity.bindActivity(word(activityFaces));entity.bindActivity(word(activityFaces));}
            gas=region.gasConfiguration==null?null:sections.new GasSection(volumes,granular==null?solidsBase:granular.endOffset,
                    sourceEntity!=null?sourceEntity.word(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):granular==null?word(geometry.get(GeometryField.OCCUPIED_SOLID)):granular.geometry.word(VulkanGranularGeometry.Field.OCCUPIED_SVQ));
            if(gas!=null)gas.transport.bindTargetOccupation(word(geometry.get(GeometryField.FIXED_MASKS)),word(entity!=null?entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ)));
            mixture=gas==null?liquidOnlyMixture(granular==null?solidsBase:granular.endOffset):gas.mixture;
            long mixtureEnd=gas==null?mixture.endOffset():gas.endOffset;
            sourceMixture=new VulkanLiquidStateLayout.Column(align(mixtureEnd),mixture.states().count(),8);
            long sourceMixtureEnd=align(sourceMixture.offset()+sourceMixture.bindingBytes());
            pressure=sections.new PressureSection(granular,gas,mixture,viscosity.column(VulkanLiquidViscosity.Field.MU),entity==null?null:entity.column(VulkanEntityBodyCoupling.Field.FACE_AREA),
                    entity!=null?entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ),sourceMixtureEnd);
            dynamics=granular==null?null:sections.new GranularDynamics(granular,pressure,viscosity.column(VulkanLiquidViscosity.Field.MU),pressure.endOffset);
            if(entity!=null){entity.bindPressure(mixture,pressure.coupled);entity.bindSourceOccupation(sourceEntity);}
            capacityInputs=VulkanLiquidCapacityInputs.layout(volumes,word(geometry.get(GeometryField.FIXED_MASKS)),
                    word(entity!=null?entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ)),word(geometry.get(GeometryField.FACES)),word(geometry.get(GeometryField.FLUXES)),
                    mixture.statesWord()+2*VulkanMixtureState.Quantity.LIQUID_SCALE.ordinal(),VulkanMixtureState.Quantity.values().length,
                    word(activityCells),word(activityFaces),dynamics!=null?dynamics.endOffset:pressure.endOffset,liquid.alignment());
            accepted=VulkanLiquidAcceptedVolumes.layout(volumes,word(capacityInputs.accepted()),
                    capacityInputs.endOffset(),liquid.alignment(),Math.addExact(volumes.limbCapacity(),Math.multiplyExact(cells,14)));
            materials=VulkanLiquidMaterialRoutes.layout(liquid,routes,word(accepted.descriptors()),word(phaseTable),-1,accepted.sourceCellVolumes().quantumExponent(),
                    Math.multiplyExact(7*stocks+3*cells,definitions.scratchLimbsPerWindow()),accepted.endOffset(),liquid.alignment());
            quantities=VulkanLiquidRouteQuantities.layout(liquid,routes,word(materialReference),word(speciesReference),definitions.speciesStride(),materials.endOffset(),liquid.alignment());
            gather=VulkanLiquidGather.layout(liquid,liquid,routes,word(geometry.get(GeometryField.FIXED_MASKS)),word(massTable),quantities.endOffset(),liquid.alignment());
            heatTransport=VulkanMechanicalHeatTransport.layout(liquid,liquid,routes,word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_HEAT)),gather.endOffset(),liquid.alignment());
            var mechanicalMomentum=VulkanLiquidMomentum.layout(liquid,liquid,routes,word(geometry.get(GeometryField.FACES)),word(futureFaces),-1,word(activityCells),word(activityFaces),heatTransport.endOffset(),liquid.alignment());
            momentum=(gas==null?mechanicalMomentum:mechanicalMomentum.withGasTransport(gas.transport)).withAmbientMass(ambientMassUnits);
            liquidThermal=VulkanLiquidThermodynamics.layout(liquid,definitions.thermal(),word(materialReference),word(speciesReference),definitions.speciesStride(),momentum.endOffset(),liquid.alignment());
            bodies=VulkanMechanicalBodies.layout(liquid,routes,word(geometry.get(GeometryField.FACES)),gas==null?-1:word(gas.geometry.cellNodes()),
                    gas==null?0:gas.state.nodeCapacity(),gas==null?-1:Math.toIntExact(gas.state.column(VulkanGasStateLayout.Field.COUNTS).offset()/4),
                    dynamics==null?null:new VulkanMechanicalBodies.GranularLinks(dynamics.transitions.source,pressure.assembly,dynamics.contacts.word(VulkanGranularContacts.Field.INTERVAL_COMPONENT)),
                    liquidThermal.endOffset(),liquid.alignment());
            var mechanicalEnergy=VulkanMechanicalEnergy.layout(bodies,momentum,liquidThermal,-1,
                    word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_HEAT)),entity==null?word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_WORK)):entity.word(VulkanEntityBodyCoupling.Field.SOURCE_WORK),
                    dynamics==null?null:new VulkanMechanicalEnergy.GranularEnergy(dynamics.snapshotThermal,dynamics.snapshotGeometry,-1,dynamics.contacts.word(VulkanGranularContacts.Field.INTERVAL_PARTICLE_WORK)),bodies.endOffset(),liquid.alignment()).withSourceFaceK(word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_FACE_K)));
            energy=mechanicalEnergy.withLocalMixture(sourceMixture,mixture,gas==null?null:gas.transport,gas==null?null:gas.thermo);
            long thermalBase=energy.endOffset();
            if(region.thermalConfiguration==null){bathTemperatures=null;thermal=null;}
            else{long[] tail={thermalBase};bathTemperatures=column(tail,region.thermalConfiguration.bathTemperatures().length,8);
                var inputs=new VulkanThermalContacts.Inputs(liquid,liquidThermal,gas==null?null:gas.state,gas==null?null:gas.thermo,
                        gas==null?null:gas.geometry,granular==null?null:granular.state,granular==null?null:granular.thermal,
                        coherent==null?null:coherent.state,coherent==null?null:coherent.thermal,devices==null?null:devices.state,devices==null?null:devices.thermal,
                        word(geometry.get(GeometryField.FIXED_MASKS)),bathTemperatures.count()==0?-1:word(bathTemperatures),word(geometry.get(GeometryField.FACES)),region.thermalConfiguration.cellSize(),word(activityCells),
                        word(geometry.get(GeometryField.PLANES)),word(geometry.get(GeometryField.CELLS)));
                thermal=VulkanRegionThermal.layout(inputs,region.thermalConfiguration,thermalContactCapacity,thermalBathCapacity,tail[0],liquid.alignment());
                thermal.contacts().wallGeometryWord=word(geometry.get(GeometryField.WALL_GEOMETRY));thermalBase=thermal.endOffset();}
            VulkanRegionReactions.SolidInputs solidReactions=null;
            if(region.reactionConfiguration!=null&&granular!=null){
                var phaseTransitions=new VulkanGranularTransitions.Layout(granular.state,granular.geometry,geometry.get(GeometryField.CELLS),geometry.get(GeometryField.FACES),liquid,
                        geometry.get(GeometryField.FIXED_MASKS),viscosity.column(VulkanLiquidViscosity.Field.MU),thermalBase,liquid.alignment());
                phaseTransitions.bindActivity(word(activityCells));
                solidReactions=new VulkanRegionReactions.SolidInputs(phaseTransitions,granular.thermal,region.granularConfiguration.registry(),region.thermalConfiguration.cellSize(),word(geometry.get(GeometryField.FACES)));
                thermalBase=phaseTransitions.endOffset();
            }
            reactions=region.reactionConfiguration==null?null:VulkanRegionReactions.layout(region.reactionConfiguration,liquid,liquidThermal,solidReactions,gas==null?null:new VulkanGasLiquidMassTransfer.Inputs(gas.thermo,gas.geometry,liquidThermal,region.reactionConfiguration.gravity(),ambientMassUnits,devices==null?null:devices.state,devices==null?null:devices.thermal),coherent==null?null:new VulkanCondensedPhaseChange.Inputs(liquid,coherent.state,liquidThermal,coherent.thermal,null,null,mixture.statesWord()+2*VulkanMixtureState.Quantity.PRESSURE.ordinal(),VulkanMixtureState.Quantity.values().length,word(geometry.get(GeometryField.FIXED_MASKS)),word(entity!=null?entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ)),region.thermalConfiguration.cellSize(),region.reactionConfiguration.gravity()),thermalBase,liquid.alignment(),word(activityCells),word(activityFaces),word(geometry.get(GeometryField.FACES)),region.thermalConfiguration.cellSize());
            if(reactions!=null)thermalBase=reactions.endOffset();
            var thermalStreams=new ArrayList<VulkanEnergyLedger.Stream>();if(thermal!=null)thermalStreams.addAll(thermal.roundoffStreams());if(reactions!=null)thermalStreams.addAll(reactions.roundoffStreams());
            thermalOnlyRoundoff=VulkanEnergyLedger.layout(thermalStreams,thermalBase,liquid.alignment());thermalBase=thermalOnlyRoundoff.endOffset();
            thermalOnlyMechanical=VulkanEnergyLedger.layout(reactions==null?List.of():reactions.mechanicalWorkStreams(),thermalBase,liquid.alignment());thermalBase=thermalOnlyMechanical.endOffset();
            entityWork=entity==null?null:VulkanEnergyLedger.layout(List.of(stream(entity.column(VulkanEntityBodyCoupling.Field.SOURCE_WORK),1)),thermalBase,liquid.alignment());
            if(entityWork!=null)thermalBase=entityWork.endOffset();
            var roundoffStreams=new ArrayList<VulkanEnergyLedger.Stream>(List.of(stream(quantities.energyRemainders(),1),stream(gather.energyRemainders(),-1),
                    stream(momentum.heatPostingRemainders(),1),stream(momentum.heatAllocationRemainders(),1)));
            if(entity==null)roundoffStreams.addAll(viscosity.externalWorkRoundoffStreams());roundoffStreams.addAll(heatTransport.roundoffStreams());
            if(gas!=null)roundoffStreams.add(stream(gas.transport.column(VulkanLocalGasTransport.Field.ENERGY_ROUNDOFF),1));
            if(gas!=null)roundoffStreams.add(stream(gas.transport.column(VulkanLocalGasTransport.Field.GATHER_ROUNDOFF),1));
            roundoffStreams.addAll(energy.roundoffStreams());
            if(dynamics!=null){roundoffStreams.addAll(dynamics.contacts.externalWorkRoundoffStreams());roundoffStreams.addAll(dynamics.transitions.roundoffStreams());}
            roundoff=VulkanEnergyLedger.layout(roundoffStreams,thermalBase,liquid.alignment());
            heat=VulkanEnergyLedger.layout(java.util.List.of(stream(momentum.cellHeat(),1)),roundoff.endOffset(),liquid.alignment());
            var work=new ArrayList<VulkanEnergyLedger.Stream>(List.of(stream(momentum.cellHeat(),1),stream(energy.faceTerms(),1),stream(energy.potentialTerms(),1)));
            if(entity==null)work.add(new VulkanEnergyLedger.Stream(1,word(viscosity.column(VulkanLiquidViscosity.Field.CONTACT_WORK)),viscosity.column(VulkanLiquidViscosity.Field.CONTACT_WORK).count(),2,1));
            else work.add(stream(entity.column(VulkanEntityBodyCoupling.Field.SOURCE_WORK),1));
            if(dynamics!=null){work.add(stream(energy.granularTerms(),1));work.add(stream(dynamics.contacts.column(VulkanGranularContacts.Field.FIXED_WORK),1));work.add(stream(dynamics.transitions.column(VulkanGranularTransitions.Field.HEAT),1));
                for(int i=0;i<17;i++)work.add(new VulkanEnergyLedger.Stream(1,dynamics.transitions.word(VulkanGranularTransitions.Field.LIFT_TERMS)+2*i,dynamics.transitions.inputRows,34,i<8?1:-1));}
            if(gas!=null){work.add(stream(energy.gasEnergy().potential(),1));work.add(stream(gas.transport.column(VulkanLocalGasTransport.Field.EXTERNAL_PRESSURE_WORK),1));}
            int elastic=2*VulkanMixtureState.Quantity.ELASTIC_ENERGY.ordinal();
            work.add(new VulkanEnergyLedger.Stream(1,word(sourceMixture)+elastic,cells,14,1));
            work.add(new VulkanEnergyLedger.Stream(1,mixture.statesWord()+elastic,cells,14,-1));
            mechanicalPosting=VulkanEnergyLedger.layout(work,heat.endOffset(),liquid.alignment());
            var incoming=new ArrayList<VulkanEnergyLedger.Stream>();var outgoing=new ArrayList<VulkanEnergyLedger.Stream>();
            if(gas!=null){incoming.add(stream(gas.transport.column(VulkanLocalGasTransport.Field.EXTERNAL_SOURCE_ENERGY),1));
                outgoing.add(stream(gas.transport.column(VulkanLocalGasTransport.Field.EXTERNAL_SINK_ENERGY),1));}
            sourcePosting=VulkanEnergyLedger.layout(incoming,mechanicalPosting.endOffset(),liquid.alignment());sinkPosting=VulkanEnergyLedger.layout(outgoing,sourcePosting.endOffset(),liquid.alignment());
            var inventory=new ArrayList<VulkanEnergyLedger.Stream>();inventory.add(new VulkanEnergyLedger.Stream(1,word(liquid.column(ENERGY_BITS)),liquid.stockCapacity(),2,1,word(liquid.column(COUNTS))));
            if(gas!=null)inventory.add(new VulkanEnergyLedger.Stream(1,Math.toIntExact(gas.state.column(VulkanGasStateLayout.Field.INTERNAL_ENERGY).offset()/4),gas.state.nodeCapacity(),2,1,Math.toIntExact(gas.state.column(VulkanGasStateLayout.Field.COUNTS).offset()/4)));
            if(granular!=null){var g=granular.state;inventory.add(new VulkanEnergyLedger.Stream(1,g.word(VulkanGranularStateLayout.Field.INTERNAL_ENERGY),g.particleCapacity(),2,1,g.word(VulkanGranularStateLayout.Field.COUNTS)));
                inventory.add(new VulkanEnergyLedger.Stream(1,g.word(VulkanGranularStateLayout.Field.INTERNAL_ENERGY)+2*g.particleCapacity(),g.depositCapacity(),2,1,g.word(VulkanGranularStateLayout.Field.COUNTS)+1));}
            if(coherent!=null)inventory.add(new VulkanEnergyLedger.Stream(1,coherent.state.word(VulkanCoherentStateLayout.Field.INTERNAL_ENERGY),coherent.state.groupCapacity(),2,1,coherent.state.word(VulkanCoherentStateLayout.Field.COUNTS)));
            inventoryPosting=VulkanEnergyLedger.layout(inventory,sinkPosting.endOffset(),liquid.alignment());endOffset=inventoryPosting.endOffset();

        }
        void initialize(VulkanRegionStorage.Import writer, GasConfiguration gasConfiguration,
                        VulkanGranularStateLayout.Configuration granularConfiguration, VulkanRegionThermal.Configuration thermalConfiguration,
                        VulkanCoherentStateLayout.Configuration coherentConfiguration, VulkanThermalDeviceLayout.Configuration deviceConfiguration,
                        VulkanRegionReactions.Configuration reactionConfiguration) {
            for(var header:headers(liquid))writer.put(header.offset(),header.bytes());
            writer.put(phaseTable,definitions.materialPhases());writer.put(massTable,definitions.speciesMasses());
            writer.put(materialReference,definitions.materialReferences());writer.put(speciesReference,definitions.speciesReferences());
            thermalTables(writer,sourceThermal);thermalTables(writer,liquidThermal);
            writer.put(viscosity.column(VulkanLiquidViscosity.Field.MATERIAL_OFFSETS),definitions.viscosity().offsets());
            writer.put(viscosity.column(VulkanLiquidViscosity.Field.VISCOSITY_SEGMENTS),definitions.viscosity().segments());
            writer.put(forces.referenceTemperatures(),definitions.forces().referenceTemperatures());writer.put(forces.expansionCoefficients(),definitions.forces().expansionCoefficients());
            var st=definitions.surface();writer.put(surface.phases(),st.materialPhases());writer.put(surface.tensions(),st.tension());writer.put(surface.cosines(),st.contactCosine());
            writer.put(surface.pairPhases(),st.pairPhases());writer.put(surface.pairTensions(),st.pairTension());
            if(gas!=null) {
                gasTables(writer,gas.thermo);
                var source=gasConfiguration.initial();
                var local=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),liquid.cellCount(),gas.state.definitions(),8);
                var data=ByteBuffer.allocate(Math.toIntExact(local.bytes())).order(ByteOrder.LITTLE_ENDIAN);
                local.importSpatialState(data,source);
                for(var field:VulkanGasStateLayout.Field.values()){var c=local.column(field);if(c.bytes()>0)writer.put(gas.state.column(field).offset(),data.slice(Math.toIntExact(c.offset()),Math.toIntExact(c.bytes())));}
                var unitVolumes=new double[liquid.cellCount()];java.util.Arrays.fill(unitVolumes,1.0);writer.put(gas.volumes,unitVolumes);
            }
            if(reactions!=null){for(var chunk:reactions.tables())writer.put(chunk.offset(),chunk.bytes());for(var chunk:reactions.progress().stateChunks(reactionConfiguration.initial()))writer.put(chunk.offset(),chunk.bytes());}
            if(thermal!=null){writer.put(bathTemperatures,thermalConfiguration.bathTemperatures());for(var chunk:thermal.tables())writer.put(chunk.offset(),chunk.bytes());}
            if(coherent!=null){var data=ByteBuffer.allocate(Math.toIntExact(coherent.state.bytes())).order(ByteOrder.LITTLE_ENDIAN);coherent.state.importState(data,coherentConfiguration.initial());writer.put(coherent.state.baseOffset(),data);
                thermalTables(writer,coherent.thermal);writer.put(coherent.materialReference,coherentConfiguration.materialReferences());writer.put(coherent.speciesReference,coherentConfiguration.speciesReferences());}
            if(devices!=null){var data=ByteBuffer.allocate(Math.toIntExact(devices.state.bytes())).order(ByteOrder.LITTLE_ENDIAN);devices.state.importState(data,deviceConfiguration.initial());writer.put(devices.state.baseOffset(),data);
                thermalTables(writer,devices.thermal);writer.put(devices.materialReference,deviceConfiguration.materialReferences());}
            if(granular!=null) {
                var state=granular.state;
                var data=ByteBuffer.allocate(Math.toIntExact(state.bytes())).order(ByteOrder.LITTLE_ENDIAN);state.importState(data,granularConfiguration.initial());
                writer.put(state.baseOffset(),data);thermalTables(writer,granular.thermal);thermalTables(writer,dynamics.snapshotThermal);
                writer.put(granular.materialReference,granularConfiguration.materialReferences());writer.put(granular.speciesReference,granularConfiguration.speciesReferences());
                var ids=state.definitions().materialIds();double[] diameters=new double[ids.size()],contact=new double[3*ids.size()];
                for(int i=0;i<ids.size();i++){var m=granularConfiguration.registry().material(granularConfiguration.registry().materialHandle(ids.get(i)));
                    diameters[i]=m.primaryGrainDiameterMeters();contact[3*i]=m.restitution();contact[3*i+1]=m.staticFriction();contact[3*i+2]=m.dynamicFriction();}
                writer.put(pressure.assembly.column(VulkanMixturePressure.Field.DIAMETERS),diameters);writer.put(dynamics.motion.columns.get(VulkanGranularMotion.Field.MATERIAL),contact);writer.put(dynamics.transitions.column(VulkanGranularTransitions.Field.MATERIALS).offset(),dynamics.transitions.materialBytes(granularConfiguration.registry()));
            }
        }
        void recordInitialize(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer input,GpuBuffer output,GpuBuffer errors) {
            var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            region.kernels.volumeKernel.rebindRanges(src,dst,err);region.kernels.inputKernel.rebindRanges(dst,err);
            region.kernels.acceptedKernel.rebindRanges(dst,err);region.kernels.materialKernel.rebindRanges(src,dst,err);region.kernels.quantityKernel.rebindRanges(src,dst,err);
            region.kernels.gatherKernel.rebindRanges(src,dst,err);region.kernels.momentumKernel.rebindRanges(src,dst,err);region.kernels.ledger.rebindRanges(src,dst,err);
            region.kernels.liquidThermalKernel.rebindRanges(dst,err);region.kernels.heatTransportKernel.rebindRanges(src,dst,err);region.kernels.bodiesKernel.rebindRanges(src,dst,err);region.kernels.energyKernel.rebindRanges(src,dst,err);
            region.kernels.futureGeometry.rebindRanges(geometryRanges(output,errors,futureFaces,entity==null?occupied():entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ)));
            configureExterior(region.kernels.futureGeometry);
            region.kernels.volumeKernel.record(command,volumes);
            region.kernels.inputKernel.record(command,capacityInputs);
        }
        void beginDeviceRequest(org.lwjgl.vulkan.VkCommandBuffer command,Transport old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack,boolean continuation){
            if(reactions!=null)region.kernels.reactionKernel.recordClearPostings(command,reactions,output);
            if(devices!=null&&!continuation){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);devices.state.recordBeginRequest(command,input,old.devices.state,output,stack);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}
        }
        void recordSolidGeometry(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,org.lwjgl.system.MemoryStack stack){
            recordSolidGeometry(command,output,errors,stack,
                    entity==null?null:region.bodyConfiguration(sourceBodyState),
                    entity==null?null:region.bodyConfiguration(targetBodyState));
        }
        void recordSolidGeometry(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,org.lwjgl.system.MemoryStack stack,
                                 VulkanEntityBodyCoupling.Configuration sourceBodies,VulkanEntityBodyCoupling.Configuration targetBodies){
            if(coherent!=null){region.kernels.coherentGeometryKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));region.kernels.coherentGeometryKernel.record(command,coherent.geometry);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                copyColumn(command,output,coherent.geometry.occupiedSvq(),output,geometry.get(GeometryField.OCCUPIED_SOLID),stack);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}
            if(granular!=null){bindGranular(output,errors);region.kernels.granularGeometryKernel.recordOccupancy(command,granular.geometry);}
            if(entity!=null){
                region.kernels.entityKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                region.kernels.entityKernel.recordInputs(command,sourceEntity,output,sourceBodies);
                region.kernels.entityKernel.recordInputs(command,entity,output,targetBodies);
                region.kernels.entityKernel.recordOccupancy(command,sourceEntity,sourceBodies.cellSize());
                region.kernels.entityKernel.recordOccupancy(command,entity,targetBodies.cellSize());
            }
        }
        void recordBodyContacts(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors){if(entity!=null){
            region.kernels.entityKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
            region.kernels.entityKernel.recordContacts(command,entity,region.requestCellSize);
        }}
        void recordBodyPressure(org.lwjgl.vulkan.VkCommandBuffer command,double duration){if(entity!=null)region.kernels.entityKernel.recordPressure(command,entity,region.requestCellSize,duration);}
        void bindGranular(GpuBuffer output,GpuBuffer errors){
            var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            region.kernels.granularGeometryKernel.rebindRanges(dst,err);
            region.kernels.contactKernel.rebindRanges(dst,err);region.kernels.motionKernel.rebindRanges(dst,err);region.kernels.granularThermalKernel.rebindRanges(dst,err);
        }
        void recordMotionFinish(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,VulkanLiquidProjection.Parameters parameters){
            region.kernels.granularGeometryKernel.recordOccupancy(command,granular.geometry);
            region.kernels.transitionKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
            region.kernels.transitionKernel.record(command,dynamics.transitions,output,parameters.cellSize(),parameters.duration(),parameters.gravity());
            region.kernels.granularGeometryKernel.recordOccupancy(command,dynamics.snapshotGeometry);
            if(entity!=null)region.kernels.entityKernel.recordCombinedOccupancy(command,entity,parameters.cellSize());
        }
        void recordFinish(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,
                          org.lwjgl.system.MemoryStack stack,VulkanLiquidProjection.Parameters parameters) {
            int gravitySign=parameters.gravity()>0?-1:parameters.gravity()<0?1:0;
            region.kernels.timed(command,"accepted-volumes",()->region.kernels.acceptedKernel.record(command,accepted));
            region.kernels.timed(command,"material-routes",()->region.kernels.materialKernel.record(command,materials,gravitySign,region.kernels.timings));
            region.kernels.timed(command,"quantities",()->region.kernels.quantityKernel.record(command,quantities));
            region.kernels.timed(command,"gather",()->region.kernels.gatherKernel.record(command,gather));
            region.kernels.timed(command,"viscous-heat-transport",()->region.kernels.heatTransportKernel.record(command,heatTransport));
            if(gas!=null)gas.recordTransport(command,output,errors,stack,parameters.duration());
            region.kernels.timed(command,"momentum-transport",()->region.kernels.momentumKernel.recordTransport(command,momentum));
            region.kernels.timed(command,"future-liquid-totals",()->region.kernels.futureGeometry.recordTotals(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ()));
            if(gas!=null){
                gas.recordThermodynamics(command,output,errors,parameters.duration());
                gas.recordPressureWork(command,output,errors);
                region.kernels.timed(command,"future-liquid-totals",()->region.kernels.futureGeometry.recordTotals(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ()));
                gas.recordSource(command,output,errors,parameters.duration(),region.kernels.futureGeometry);
            }else{
                region.mixtureKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                region.mixtureKernel.record(command,mixture,region.requestCellSize,parameters.duration());
                region.kernels.futureGeometry.recordShapes(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
            }
            region.kernels.timed(command,"momentum-resolve",()->region.kernels.momentumKernel.recordResolve(command,momentum));
            region.kernels.timed(command,"mixing-heat",()->region.kernels.momentumKernel.recordHeatPosting(command,momentum));
            if(gas!=null){
                region.kernels.gasThermo.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                region.kernels.timed(command,"post-mixing-gas-caloric",()->region.kernels.gasThermo.recordCaloric(command,gas.thermo));
            }
            region.kernels.timed(command,"body-init",()->region.kernels.bodiesKernel.recordInitialize(command,bodies));
            region.kernels.timed(command,"body-connectivity",()->region.kernels.bodiesKernel.recordConnectivity(command,bodies));
        }
        void recordEnergy(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,VulkanLiquidProjection.Parameters parameters) {
            region.kernels.timed(command,"body-csr",()->region.kernels.bodiesKernel.recordMembers(command,bodies));
            region.kernels.timed(command,"liquid-thermal",()->region.kernels.liquidThermalKernel.record(command,liquidThermal));
            if(granular!=null)region.kernels.timed(command,"granular-thermal",()->region.kernels.granularThermalKernel.record(command,dynamics.snapshotThermal));
            region.kernels.timed(command,"common-energy",()->region.kernels.energyKernel.record(command,energy,parameters.cellSize(),parameters.gravity()));
            if(dynamics!=null)region.kernels.transitionKernel.recordRefreshAfterEnergy(command,dynamics.transitions,parameters.cellSize(),parameters.duration(),parameters.gravity());
            region.kernels.timed(command,"future-liquid-totals",()->region.kernels.futureGeometry.recordTotals(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ()));
            if(gas!=null)gas.recordThermodynamics(command,output,errors,parameters.duration());
            else {
                region.mixtureKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                region.mixtureKernel.record(command,mixture,region.requestCellSize,parameters.duration());
            }
        }
        void recordLedgers(org.lwjgl.vulkan.VkCommandBuffer command,Transport previous,boolean finalRequest,GpuBuffer output,GpuBuffer errors) {
            region.kernels.timed(command,"roundoff-ledger",()->region.kernels.ledger.record(command,roundoff));region.kernels.timed(command,"heat-ledger",()->region.kernels.ledger.record(command,heat));
            if(previous!=null){region.kernels.ledger.recordAccumulate(command,roundoff,word(previous.roundoff.result()));region.kernels.ledger.recordAccumulate(command,heat,word(previous.heat.result()));}
            if(finalRequest)region.kernels.ledger.record(command,inventoryPosting);region.kernels.ledger.record(command,mechanicalPosting);region.kernels.ledger.record(command,sourcePosting);region.kernels.ledger.record(command,sinkPosting);
            if(previous!=null){region.kernels.ledger.recordAccumulate(command,mechanicalPosting,word(previous.mechanicalPosting.result()));region.kernels.ledger.recordAccumulate(command,sourcePosting,word(previous.sourcePosting.result()));region.kernels.ledger.recordAccumulate(command,sinkPosting,word(previous.sinkPosting.result()));}
            if(finalRequest){
                if(thermal!=null)region.kernels.ledger.record(command,thermal.reservoir());
                region.kernels.ledger.record(command,thermalOnlyRoundoff);region.kernels.ledger.record(command,thermalOnlyMechanical);
                var arena=new FlipKernelBindings.BufferRange(output);region.kernels.ledger.rebindRanges(arena,arena,new FlipKernelBindings.BufferRange(errors));
                region.kernels.ledger.recordAccumulate(command,roundoff,word(thermalOnlyRoundoff.result()));
                region.kernels.ledger.recordAccumulate(command,mechanicalPosting,word(thermalOnlyMechanical.result()));
            }
        }
        int[] requirements(VulkanRegionStorage.Candidate candidate) {
            var columns=new VulkanLiquidStateLayout.Column[]{volumes.totalOffsets(),accepted.volumeOffsets(),materials.reserveOffsets(),
                    routes.column(VulkanLiquidRouteLayout.Field.COUNTS),routes.column(VulkanLiquidRouteLayout.Field.SPECIES_REQUIRED),
                    liquid.column(CELL_OFFSETS),liquid.column(SPECIES_OFFSETS),liquid.column(VOLUME_OFFSETS)};
            long[] offsets=new long[8];for(int i=0;i<8;i++)offsets[i]=columns[i].offset()+4L*(columns[i].count()-1);
            var bytes=ByteBuffer.allocate(4*offsets.length).order(ByteOrder.LITTLE_ENDIAN);candidate.readWords(offsets,bytes);
            int[] result=new int[8];for(int i=0;i<offsets.length;i++)result[i]=bytes.getInt(4*i);result[3]=0;return result;
        }
        List<VulkanLiquidStateLayout.Column> persistentColumns() {
            var result=new ArrayList<VulkanLiquidStateLayout.Column>(List.of(phaseTable,massTable,materialReference,speciesReference,
                    volumes.header(),capacityInputs.header(),accepted.header(),materials.header(),quantities.header(),gather.header(),momentum.header(),
                    liquidThermal.header(),liquidThermal.offsets(),liquidThermal.upper(),liquidThermal.capacity(),liquidThermal.referenceTemperature(),liquidThermal.coldSensible(),
                    bodies.header(),energy.header(),roundoff.header(),heat.header(),mechanicalPosting.header(),sourcePosting.header(),sinkPosting.header(),inventoryPosting.header()));
            result.add(mixture.header());result.addAll(pressure.persistentColumns());
            result.addAll(forceColumns());
            if(gas!=null) {
                result.add(gas.geometry.header());result.add(gas.volumes);result.add(gas.transport.column(VulkanLocalGasTransport.Field.HEADER));result.add(gas.transport.column(VulkanLocalGasTransport.Field.AMBIENT_DATA));result.add(gas.transport.column(VulkanLocalGasTransport.Field.SPECIES_MASS));addGasTables(result,gas.thermo);
            }
            if(entity!=null){result.add(entity.header);result.add(sourceEntity.header);}
            if(entityWork!=null)result.add(entityWork.header());
            if(reactions!=null)result.addAll(reactions.persistentColumns());result.add(thermalOnlyRoundoff.header());result.add(thermalOnlyMechanical.header());
            if(thermal!=null){result.add(bathTemperatures);result.addAll(thermal.persistentColumns());}
            if(coherent!=null)result.addAll(coherent.persistentColumns());if(devices!=null)result.addAll(devices.persistentColumns());
            if(granular!=null){result.addAll(granular.persistentColumns());result.addAll(dynamics.persistentColumns());}
            return result;
        }
        List<VulkanLiquidStateLayout.Column> forceHeaders(){return List.of(sourceThermal.header(),viscosity.header(),forces.header(),surface.header(),heatTransport.header());}
        List<VulkanLiquidStateLayout.Column> forceColumns(){
            var columns=new ArrayList<VulkanLiquidStateLayout.Column>(forceHeaders());
            columns.addAll(List.of(sourceThermal.offsets(),sourceThermal.upper(),sourceThermal.capacity(),sourceThermal.referenceTemperature(),sourceThermal.coldSensible(),
                    viscosity.column(VulkanLiquidViscosity.Field.MATERIAL_OFFSETS),viscosity.column(VulkanLiquidViscosity.Field.VISCOSITY_SEGMENTS),
                    forces.referenceTemperatures(),forces.expansionCoefficients(),surface.phases(),surface.tensions(),surface.cosines(),surface.pairPhases(),surface.pairTensions()));return columns;
        }
        void addGasTables(List<VulkanLiquidStateLayout.Column> columns,VulkanGasThermodynamics.Layout thermo) {
            columns.addAll(List.of(thermo.header(),thermo.segmentOffsets(),thermo.segmentUpper(),thermo.segmentHeatCapacity(),thermo.specificAnchorEnergy(),thermo.speciesReference()));
        }
        java.util.List<HeaderWrite> headers(VulkanLiquidStateLayout source) {
            var result=new ArrayList<HeaderWrite>(List.of(new HeaderWrite(sourceThermal.header().offset(),sourceThermal.headerBytes()),
                    new HeaderWrite(viscosity.header().offset(),viscosity.headerBytes(source)),new HeaderWrite(forces.header().offset(),forces.headerBytes()),
                    new HeaderWrite(surface.header().offset(),surface.headerBytes()),new HeaderWrite(heatTransport.header().offset(),heatTransport.headerBytes(source))));if(gas!=null)result.addAll(gas.headers());
            result.addAll(pressure.headers());
            if(coherent!=null)result.addAll(coherent.headers());if(devices!=null)result.addAll(devices.headers());
            if(granular!=null){result.addAll(granular.headers());result.addAll(dynamics.headers());}
            result.add(new HeaderWrite(thermalOnlyRoundoff.header().offset(),thermalOnlyRoundoff.headerBytes()));
            result.add(new HeaderWrite(thermalOnlyMechanical.header().offset(),thermalOnlyMechanical.headerBytes()));
            if(entity!=null){result.add(new HeaderWrite(entity.header.offset(),entity.headerBytes()));result.add(new HeaderWrite(sourceEntity.header.offset(),sourceEntity.headerBytes()));}
            if(entityWork!=null)result.add(new HeaderWrite(entityWork.header().offset(),entityWork.headerBytes()));
            if(reactions!=null)for(var chunk:reactions.headers())result.add(new HeaderWrite(chunk.offset(),chunk.bytes()));
            if(thermal!=null)for(var chunk:thermal.headers())result.add(new HeaderWrite(chunk.offset(),chunk.bytes()));
            if(gas==null)result.add(new HeaderWrite(mixture.header().offset(),mixture.headerBytes()));
            result.addAll(java.util.List.of(new HeaderWrite(volumes.header().offset(),volumes.headerBytes(source)),
                    new HeaderWrite(capacityInputs.header().offset(),capacityInputs.headerBytes()),
                    new HeaderWrite(accepted.header().offset(),accepted.headerBytes()),new HeaderWrite(materials.header().offset(),materials.headerBytes(source)),
                    new HeaderWrite(quantities.header().offset(),quantities.headerBytes(source)),new HeaderWrite(gather.header().offset(),gather.headerBytes(source)),
                    new HeaderWrite(momentum.header().offset(),momentum.headerBytes(source)),new HeaderWrite(liquidThermal.header().offset(),liquidThermal.headerBytes()),
                    new HeaderWrite(bodies.header().offset(),bodies.headerBytes(source)),new HeaderWrite(energy.header().offset(),energy.headerBytes(source)),new HeaderWrite(roundoff.header().offset(),roundoff.headerBytes()),
                    new HeaderWrite(heat.header().offset(),heat.headerBytes()),new HeaderWrite(mechanicalPosting.header().offset(),mechanicalPosting.headerBytes()),new HeaderWrite(sourcePosting.header().offset(),sourcePosting.headerBytes()),new HeaderWrite(sinkPosting.header().offset(),sinkPosting.headerBytes()),new HeaderWrite(inventoryPosting.header().offset(),inventoryPosting.headerBytes())));return result;
        }
        private VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width) {
            var result=new VulkanLiquidStateLayout.Column(align(cursor[0]),count,width);cursor[0]=result.offset()+result.bindingBytes();return result;
        }
    }
    private FlipKernelBindings.BufferRange range(GpuBuffer buffer, GeometryField field) {
        var column = geometry.get(field);
        return new FlipKernelBindings.BufferRange(buffer, column.offset(), column.bindingBytes());
    }
    VulkanLiquidStateLayout.Column occupied(){return transport!=null&&transport.sourceEntity!=null?transport.sourceEntity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):transport!=null&&transport.granular!=null?transport.granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ):geometry.get(GeometryField.OCCUPIED_SOLID);}
    private FlipKernelBindings.BufferRange range(GpuBuffer buffer, PressureField field) {
        var column = pressureColumns.get(field);
        return new FlipKernelBindings.BufferRange(buffer, column.offset(), column.bindingBytes());
    }
    private long align(long value) { return Math.addExact(value,liquid.alignment()-1)&-liquid.alignment(); }
    }
