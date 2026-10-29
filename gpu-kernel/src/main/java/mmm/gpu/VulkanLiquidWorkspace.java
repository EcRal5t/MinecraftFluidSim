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
        final VulkanLiquidStateLayout.Column multigridColumn;
        final Transport transport;
        final long bytes;
        final GasReserves gasReserves;
        final int contactCapacity;
        final int thermalContactCapacity,thermalBathCapacity;
        final int bodyCapacity,bodyEntries;
        mmm.fluid.EntityBodyState sourceBodyState;
        final GranularReserves granularReserves;
        final int coherentSpeciesCapacity;boolean phaseGasCanonical;
        String capacityStage="source geometry";
        mmm.world.MovingBodyFluidCoupling.Result bodyResult(VulkanRegionStorage.Candidate candidate){
            if(transport==null||transport.entity==null||region.requestedBodies().bodies().isEmpty())return mmm.world.MovingBodyFluidCoupling.Result.NONE;
            var bytes=ByteBuffer.allocate(Math.multiplyExact(64,region.requestedBodies().bodies().size())).order(ByteOrder.LITTLE_ENDIAN);
            candidate.read(transport.entity.column(VulkanEntityBodyCoupling.Field.BODY_RESULTS).offset(),bytes);
            return transport.entity.result(bytes.flip(),region.bodyConfiguration(region.requestedBodies()));
        }
        VulkanLiquidWorkspace(VulkanLiquidRegion region,VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions) {
            this(region,liquid,definitions,region.gasConfiguration==null?null:new GasReserves(region.gasConfiguration.nodeCapacity(),
                    Math.multiplyExact(region.gasConfiguration.componentCapacity(),7),Math.min(Math.multiplyExact(liquid.cellCount(),7),Math.multiplyExact(Math.addExact(region.gasConfiguration.nodeCapacity(),2),7)),Math.max(1,region.gasConfiguration.nodeCapacity())));
        }
        VulkanLiquidWorkspace(VulkanLiquidRegion region,VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves) {
            this(region,liquid,definitions,gasReserves,region.granularConfiguration==null?0:region.granularConfiguration.contactCapacity());
        }
        VulkanLiquidWorkspace(VulkanLiquidRegion region,VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity) {
            this(region,liquid,definitions,gasReserves,contactCapacity,0);
        }
        VulkanLiquidWorkspace(VulkanLiquidRegion region,VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity,int thermalContactCapacity) {
            this(region,liquid,definitions,gasReserves,contactCapacity,thermalContactCapacity,0);
        }
        VulkanLiquidWorkspace(VulkanLiquidRegion region,VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity,int thermalContactCapacity,int thermalBathCapacity) {
            this(region,liquid,definitions,gasReserves,contactCapacity,thermalContactCapacity,thermalBathCapacity,region.initialGranularReserves!=null?region.initialGranularReserves:region.granularConfiguration==null?null:new GranularReserves(Math.max(1,2*region.granularConfiguration.initial().particles().size()),Math.max(1,2*region.granularConfiguration.initial().deposits().size()),Math.max(1,2*region.granularConfiguration.speciesCapacity())));
        }
        VulkanLiquidWorkspace(VulkanLiquidRegion region,VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity,int thermalContactCapacity,int thermalBathCapacity,GranularReserves granularReserves){
            this(region,liquid,definitions,gasReserves,contactCapacity,thermalContactCapacity,thermalBathCapacity,granularReserves,region.coherentConfiguration==null?0:region.coherentConfiguration.speciesCount());
        }
        VulkanLiquidWorkspace(VulkanLiquidRegion region,VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity,int thermalContactCapacity,int thermalBathCapacity,GranularReserves granularReserves,int coherentSpeciesCapacity){
            this.region=region;
            this.templates=new VulkanCommandTemplates(region.context);
            this.sourceBodyState=region.currentInputs.bodies();
            this.coherentSpeciesCapacity=coherentSpeciesCapacity;
            bodyCapacity=region.requestedBodyCapacity;bodyEntries=region.requestedBodyEntries;
            this.liquid=liquid;this.gasReserves=gasReserves;this.contactCapacity=contactCapacity;this.thermalContactCapacity=thermalContactCapacity;this.thermalBathCapacity=thermalBathCapacity;this.granularReserves=granularReserves;
        int cells = liquid.cellCount(),rows=Math.addExact(Math.addExact(cells,gasReserves==null?0:gasReserves.nodes()),
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
            int width = switch (field) { case STARTS, COLUMNS, ROWS, META -> 4; default -> 8; };
            int count = switch (field) {
                case STARTS -> Math.addExact(rows, 1);
                case COLUMNS, VALUES -> Math.addExact(Math.multiplyExact(cells, 7),rows-cells);
                case ROWS, PRESSURE -> rows;
                case INPUT -> 1;
                case WORK -> Math.multiplyExact(rows, 7);
                case META -> Math.multiplyExact(rows, 2);
                case PROBLEM -> Math.addExact(Math.multiplyExact(rows, 3), 1);
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
        transport=definitions==null?null:new Transport(definitions,total);
        thermalStages = new VulkanLiquidThermalStages(this);
        if(transport!=null)total=transport.endOffset;
        bytes=total;
        if(bytes>region.maximumRange)throw new IllegalArgumentException("Resident workspace exceeds device descriptor range");

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
                region.kernel.record(command, liquid.sizeX(), liquid.sizeY(), liquid.sizeZ());
                thermalStages.recordImportedThermal(command, output, errors);
                if (transport != null && transport.gas != null) {
                    var arena = new FlipKernelBindings.BufferRange(output);
                    var errorBuffer = new FlipKernelBindings.BufferRange(errors);
                    region.kernels.volumeKernel.rebindRanges(arena, arena, errorBuffer);
                    region.kernels.volumeKernel.record(command, transport.volumes);
                    region.kernels.gasGeometry.rebindRanges(arena, errorBuffer);
                    region.kernels.gasGeometry.recordGeometry(command, transport.gas.geometry);
                    region.kernels.gasGeometry.recordSourceComponents(command, transport.gas.geometry, transport.gas.state);
                    region.kernels.gasThermo.rebindRanges(arena, errorBuffer);
                    region.kernels.gasThermo.recordVolumes(command, transport.gas.thermo, transport.gas.geometry);
                    region.kernels.gasThermo.record(command, transport.gas.thermo);
                }
        }

        int executeReactions(VulkanRegionStorage.Candidate candidate, VulkanGasThermodynamics.Layout sourceEOS,
                             VulkanGasGeometry.Layout previousGeometry, double duration) {
            return thermalStages.executeReactions(candidate, sourceEOS, previousGeometry, duration);
        }
        int executeThermal(VulkanLiquidWorkspace source, boolean sourceCurrentGas, boolean sourceGeometryValid,
                           VulkanRegionStorage.Candidate candidate, double duration) {
            return thermalStages.executeThermal(source, sourceCurrentGas, sourceGeometryValid, candidate, duration);
        }
        int execute(VulkanLiquidWorkspace source,boolean sourceCurrentGas,boolean sourceGeometryValid,VulkanRegionStorage.Candidate candidate,VulkanLiquidProjection.Parameters parameters,
                    boolean zeroInitialPressure,int capacityRounds,long step,boolean accumulateLedger) {
            boolean migrating=source!=this;
            int firstPressureBatch=parameters==null?0:Math.min(parameters.iterations(),Math.max(16,((region.previousPressureIterations+15)/16)*16));
            // A completed successor already contains the next source PLIC and chamber CSR.
            // Inventory-writing stages and moving solids rebuild these fields below.
            boolean reuseGeometry=sourceGeometryValid&&(transport==null||transport.dynamics==null)
                    &&region.currentInputs.bodies().bodies().isEmpty()&&(region.activeInputs==null||region.activeInputs.bodies().bodies().isEmpty());
            var sourceHeaders=migrating?transport.headers(source.liquid):List.<HeaderWrite>of();
            var stableHeaders=migrating?transport.headers(liquid):List.<HeaderWrite>of();
            long uploadedBytes=headerBytes(sourceHeaders);
            var gas=transport==null?null:transport.gas;
            int pressureRows=pressureColumns.get(PressureField.ROWS).count();
            VulkanPressureKernel.PressureOperator baseOperator=transport!=null&&transport.dynamics!=null?
                    new VulkanCoupledPressure.Operator(region.kernels.coupledKernel,transport.dynamics.coupled):gas==null?null:new VulkanPressureKernel.GasOperator(region.kernels.gasPressure,gas.operator);
            VulkanRegionStorage.ComputeCommands prepare = (command, input, output, errors, staging, stack) -> {
                int sx=liquid.sizeX(),sy=liquid.sizeY(),sz=liquid.sizeZ();
                if((gas==null||reuseGeometry)&&region.kernels!=null&&region.kernels.timings!=null)region.kernels.timings.reset(command);
                if(gas==null||reuseGeometry) {
                    Runnable copy=()->{if(migrating)migrate(command,source,input,output,stack,sourceHeaders);else copyPersistent(command,input,output,stack);};
                    if(region.kernels==null)copy.run();else region.kernels.timed(command,"source-state-copy",copy);
                    if(transport!=null){transport.beginDeviceRequest(command,source.transport,input,output,stack,accumulateLedger);transport.recordSolidGeometry(command,output,errors,stack);}
                    if(reuseGeometry)region.kernels.timed(command,"source-derived-copy",()->copySourceGeometry(command,source,sourceCurrentGas,input,output,stack));
                }
                recordActivity(command, output, stack);
                region.kernel.rebindRanges(geometryRanges(output,errors,geometry.get(GeometryField.FACES)));
                if(reuseGeometry)region.kernel.recordVelocities(command,sx,sy,sz);
                else if(gas==null)region.kernel.record(command, sx, sy, sz);
                if(gas!=null) {
                    var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                    region.kernels.gasGeometry.rebindRanges(dst,err);region.kernels.gasThermo.rebindRanges(dst,err);region.kernels.gasPressure.rebindRanges(dst,err);
                    if(!reuseGeometry)region.kernels.timed(command,"old-gas-components",()->region.kernels.gasGeometry.recordSourceComponents(command,gas.geometry,gas.state));
                    region.kernels.timed(command,"old-gas-volumes",()->region.kernels.gasThermo.recordVolumes(command,gas.thermo,gas.geometry));
                    region.kernels.timed(command,"old-gas-eos",()->region.kernels.gasThermo.record(command,gas.thermo));
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
                        if(transport.dynamics!=null)region.kernels.granularPressureKernel.recordFaces(command,transport.dynamics.pressure,parameters.cellSize(),parameters.duration(),parameters.gravity());
                    }
                    if (zeroInitialPressure) {
                        var column = pressureColumns.get(PressureField.PRESSURE);
                        VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                VK_ACCESS_TRANSFER_WRITE_BIT, stack);
                        vkCmdFillBuffer(command, output.handle(), column.offset(), column.bytes(), 0);
                        VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT,
                                VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
                    }
                    var assembly = new ArrayList<FlipKernelBindings.BufferRange>(12);
                    assembly.add(range(output, GeometryField.CELLS));
                    assembly.add(range(output, GeometryField.FACES));
                    for (PressureField field : new PressureField[]{PressureField.STARTS,PressureField.COLUMNS,PressureField.VALUES,
                            PressureField.ROWS,PressureField.META,PressureField.PROBLEM,PressureField.PRESSURE})
                        assembly.add(range(output,field));
                    assembly.add(new FlipKernelBindings.BufferRange(errors));
                    assembly.add(new FlipKernelBindings.BufferRange(output, activityCells.offset(), activityCells.bindingBytes()));
                    assembly.add(new FlipKernelBindings.BufferRange(output, activityFaces.offset(), activityFaces.bindingBytes()));
                    region.projection.rebindRanges(assembly);
                    Runnable assemble=()->region.projection.prepare(command,sx,sy,sz,parameters,gas==null?null:region.kernels.gasPressure,gas==null?null:gas.operator,transport==null||transport.dynamics!=null?null:()->transport.recordBodyPressure(command,parameters.duration()));
                    if(region.kernels==null)assemble.run();else region.kernels.timed(command,"common-pressure-assemble",assemble);
                    if(transport!=null&&transport.dynamics!=null){region.kernels.granularPressureKernel.recordIncidence(command,transport.dynamics.pressure,parameters.cellSize(),parameters.duration(),parameters.gravity());
                        transport.recordBodyPressure(command,parameters.duration());
                        region.kernels.coupledKernel.recordPrepare(command,transport.dynamics.coupled,parameters.duration());}
                    var solve = new ArrayList<FlipKernelBindings.BufferRange>(12);
                    for(PressureField field:PressureField.values())solve.add(range(output,field));
                    solve.add(new FlipKernelBindings.BufferRange(errors));
                    region.pressure.rebindRanges(solve);
                    var coarse = new ArrayList<FlipKernelBindings.BufferRange>(8);
                    for(PressureField field : new PressureField[]{PressureField.STARTS,PressureField.COLUMNS,PressureField.VALUES,
                            PressureField.ROWS,PressureField.META,PressureField.WORK,PressureField.CONTROL})
                        coarse.add(range(output,field));
                    coarse.add(new FlipKernelBindings.BufferRange(output,multigridColumn.offset(),multigridColumn.bindingBytes()));
                    region.multigrid.rebindRanges(coarse);
                    Runnable initialize=()->region.pressure.recordInitialize(command,pressureRows,pressureRows,parameters.iterations(),region.multigrid,hierarchy,baseOperator);
                    Runnable firstBatch=()->recordPressureIterations(command,output,errors,parameters.iterations(),firstPressureBatch,baseOperator);
                    if(region.kernels==null){initialize.run();firstBatch.run();}else{
                        region.kernels.timed(command,"common-pressure-initialize",initialize);
                        region.kernels.timed(command,"common-pressure-first-batch",firstBatch);
                    }
                }
            };
            long[] stageMark=Boolean.getBoolean("mmm.gpuMechanicalStages")?new long[]{region.storage.metrics().gpuNanos(),System.nanoTime()}:null;
            int error;
            if(gas!=null&&!reuseGeometry) {
                capacityStage="source gas geometry/EOS";
                if(parameters!=null&&Double.compare(parameters.cellSize(),region.gasConfiguration.cellSize())!=0)throw new IllegalArgumentException("Gas and mechanical cell sizes differ");
                var pre=candidate.beginBatch(uploadedBytes,(command,input,output,errors,staging,stack)-> {
                    if(region.kernels.timings!=null)region.kernels.timings.reset(command);
                    region.kernels.timed(command,"source-state-copy",()->{if(migrating)migrate(command,source,input,output,stack,sourceHeaders);else copyPersistent(command,input,output,stack);});
                    transport.beginDeviceRequest(command,source.transport,input,output,stack,accumulateLedger);
                    transport.recordSolidGeometry(command,output,errors,stack);
                    region.kernel.rebindRanges(geometryRanges(output,errors,geometry.get(GeometryField.FACES)));
                    region.kernel.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                    var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                    region.kernels.volumeKernel.rebindRanges(src,dst,err);region.kernels.volumeKernel.record(command,transport.volumes);
                    region.kernels.gasGeometry.rebindRanges(dst,err);region.kernels.gasThermo.rebindRanges(dst,err);region.kernels.gasPressure.rebindRanges(dst,err);
                    region.kernels.timed(command,"old-gas-geometry",()->region.kernels.gasGeometry.recordGeometry(command,gas.geometry));
                },0,0);
                if(pre.error()!=0)return pre.error();
            }
            traceStage("source-gas-connectivity",stageMark);
            if (parameters == null) error = gas==null||reuseGeometry?candidate.execute(uploadedBytes, prepare):candidate.finishBatch(prepare);
            else {
                capacityStage="common pressure";
                boolean pressureDiagnostics=Boolean.getBoolean("mmm.gpuMechanicalPressureDiagnostics");
                long controlOffset=pressureColumns.get(PressureField.CONTROL).offset();
                long runOffset = controlOffset;
                int feedbackBytes=pressureDiagnostics?128:32,runFeedbackOffset=24;
                var gasOperator=baseOperator;
                var result=gas==null||reuseGeometry?candidate.beginBatch(uploadedBytes,prepare,runOffset,feedbackBytes):candidate.continueBatch(prepare,runOffset,feedbackBytes);
                int scheduled = firstPressureBatch;
                while (result.error() == 0 && result.feedback().getDouble(runFeedbackOffset) != 0.0 && scheduled < parameters.iterations()) {
                    int batch = Math.min(16, parameters.iterations() - scheduled);
                    result = candidate.continueBatch((command, input, output, errors, staging, stack) ->
                            {Runnable continuation=()->recordPressureIterations(command,output,errors,parameters.iterations(),batch,gasOperator);
                                if(region.kernels!=null)region.kernels.timed(command,"common-pressure-next-batch",continuation);else continuation.run();}, runOffset, feedbackBytes);
                    scheduled += batch;
                }
                if(result.error()==0&&result.feedback().getDouble(runFeedbackOffset)==0.0)region.previousPressureIterations=(int)result.feedback().getDouble(0);
                if(pressureDiagnostics)region.pressureProgress=VulkanPressureKernel.Progress.read(result.feedback());
                error = result.error();traceStage("pressure",stageMark);
                if(error==0) {
                    VulkanRegionStorage.ComputeCommands project=(command,input,output,errors,staging,stack)-> {
                        region.pressure.recordFinish(command,pressureRows,pressureRows,parameters.iterations(),region.multigrid,hierarchy,gasOperator);
                        if(transport==null||transport.dynamics==null)region.projection.project(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),parameters,gas==null?null:region.kernels.gasPressure,gas==null?null:gas.operator);
                        else{
                            region.kernels.coupledKernel.recordProject(command,transport.dynamics.coupled,parameters.duration());
                            region.kernels.granularPressureKernel.recordProjectedVelocities(command,transport.dynamics.pressure,parameters.cellSize(),parameters.duration(),parameters.gravity());
                            region.kernels.coupledKernel.recordDivergence(command,transport.dynamics.coupled);region.kernels.gasPressure.recordObserve(command,gas.operator,parameters);
                            region.kernels.granularPressureKernel.recordObserveGas(command,transport.dynamics.pressure,parameters.cellSize(),parameters.duration(),parameters.gravity());
                        }
                        if(transport!=null&&transport.entity!=null)region.kernels.entityKernel.recordResult(command,transport.entity,parameters.cellSize(),parameters.duration());
                        if(transport==null||transport.dynamics==null)region.kernel.recordTransportPreferences(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),parameters.cellSize(),parameters.duration());
                    };
                    if(capacityRounds==0)error=candidate.finishBatch(project);
                    else {
                        if(transport.dynamics!=null){
                            capacityStage="granular contact/motion";
                            var motion=transport.dynamics.motion;var contacts=transport.dynamics.contacts;
                            var started=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                                project.record(command,input,output,errors,staging,stack);
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
                        capacityStage="liquid capacity inputs/solve";
                        long control=transport.capacity.column(VulkanLiquidCapacityLayout.Field.CONTROL).offset();
                        var capacityResult=candidate.continueBatch((command,input,output,errors,staging,stack)-> {
                            if(transport.dynamics==null)project.record(command,input,output,errors,staging,stack);
                            transport.recordInitialize(command,input,output,errors,capacityRounds);
                        },control,12);
                        int status=capacityResult.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0);
                        int rounds=capacityResult.feedback().getInt(4);
                        while(capacityResult.error()==0&&status==VulkanLiquidCapacity.RUNNING&&rounds<capacityRounds) {
                            int count=Math.min(32,capacityRounds-rounds),phase=capacityResult.feedback().getInt(8);
                            capacityResult=candidate.continueBatch((command,input,output,errors,staging,stack)->
                                    recordCapacityIterations(command,output,errors,count,phase),control,12);
                            status=capacityResult.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0);rounds=capacityResult.feedback().getInt(4);
                        }
                        region.capacityIterationCount=rounds;error=capacityResult.error();traceStage("project-capacity",stageMark);
                        if(transport.dynamics!=null&&error==4096)throw new MotionTimeRefinement("Moved granular support exceeds available fixed capacity");
                        if(transport.dynamics!=null&&error==0&&status==2)throw new MotionTimeRefinement("Joint granular displacement capacity has no feasible transport");
                        if(error==0&&status!=VulkanLiquidCapacity.FEASIBLE) {
                            int words=transport.dynamics==null?12:8;var detail=ByteBuffer.allocate(4*words).order(ByteOrder.LITTLE_ENDIAN);candidate.read(control,detail);
                            int[] values=new int[words];for(int i=0;i<words;i++)values[i]=detail.getInt(4*i);
                            String dump=VulkanLiquidCapacity.dumpFailure(candidate,transport.capacity,System.getProperty("mmm.gpuCapacityFailureDump"));
                            String displacementDetail="";if(transport.dynamics!=null){var state=ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);candidate.read(transport.capacitySolve.column(VulkanLiquidCapacity.Field.STATE).offset(),state);
                                int[] phases=new int[9];for(int i=0;i<9;i++)phases[i]=state.getInt(4*i);displacementDetail=", displacement="+java.util.Arrays.toString(phases);}
                            throw new IllegalStateException("Resident liquid capacity failed: status="+status+", rounds="+rounds+", control="+java.util.Arrays.toString(values)+displacementDetail+", dump="+dump);
                        }
                        if(error==0) {
                            capacityStage="accepted volumes/material routes/gather/future gas geometry";
                            VulkanRegionStorage.ComputeCommands finishTransport=(command,input,output,errors,staging,stack)-> {
                                transport.recordFinish(command,parameters.gravity()>0?-1:parameters.gravity()<0?1:0);
                                transport.recordEnergy(command,parameters);
                                if(transport.reactions!=null)region.kernels.activityPending.record(command,output,activityCells,transport.reactions.progress().column(VulkanReactionProgressLayout.Field.PHASE_MOTION_CELLS),transport.reactions.progress().column(VulkanReactionProgressLayout.Field.PHASE_PENDING_SUMMARY));
                                if(transport.successor!=null) {
                                    VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                                    transport.gas.state.copyColumns(command,output,transport.successor.future,output,stack);
                                    VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                                }
                            };
                            if(error==0&&transport.aperture==null&&transport.thermal==null)error=candidate.finishBatch(migrating?headerBytes(stableHeaders):0,(command,input,output,errors,staging,stack)->{
                                finishTransport.record(command,input,output,errors,staging,stack);transport.recordLedgers(command,accumulateLedger?source.transport:null);
                                if(migrating)writeHeaders(command,output,stableHeaders,stack);
                            });
                            else if(error==0){
                                boolean react=transport.reactions!=null&&(region.activeInputs==null||!region.activeInputs.pendingPhaseSettlementOnly());
                                VulkanRegionStorage.ComputeCommands finishLedgers=(command,input,output,errors,staging,stack)-> {
                                    transport.recordLedgers(command,accumulateLedger?source.transport:null);if(migrating)writeHeaders(command,output,stableHeaders,stack);
                                };
                                if(transport.aperture!=null)error=transport.aperture.execute(candidate,parameters.duration(),step,finishTransport);
                                else if(transport.thermal==null)error=candidate.continueBatch(finishTransport,0,0).error();
                                traceStage("transport-energy-aperture",stageMark);
                                boolean thermalFinishes=transport.thermal!=null&&!react;
                                if(error==0&&transport.thermal!=null){capacityStage="thermal contacts/conduction";
                                    error=region.kernels.thermalKernel.execute(candidate,transport.thermal,parameters.duration(),
                                            source.transport.devices==null?null:source.transport.devices.state,transport.devices==null?null:transport.devices.settlement,accumulateLedger,
                                            transport.aperture==null?finishTransport:null,thermalFinishes?finishLedgers:null,
                                            thermalFinishes?(migrating?headerBytes(stableHeaders):0):-1);
                                }
                                traceStage("thermal-contacts-conduction",stageMark);
                                if(error==0&&react){capacityStage="liquid reactions";error=executeReactions(candidate,transport.gas==null?null:transport.aperture==null?transport.gas.thermo:transport.aperture.eos,transport.successor==null?null:transport.successor.geometry,parameters.duration());}
                                if(error==0&&!thermalFinishes)error=candidate.finishBatch(migrating?headerBytes(stableHeaders):0,finishLedgers);
                            }
                        }
                    }
                }
            }
            traceStage("remaining-energy-ledgers",stageMark);
            if(error==0&&region.kernels!=null&&region.kernels.timings!=null)for(var sample:region.kernels.timings.read())System.out.printf("mechanical_kernel=%s gpu_ms=%.3f%n",sample.stage(),sample.milliseconds());return error;
        }
        void recordPressureIterations(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,int maximum,int count,VulkanPressureKernel.PressureOperator gasOperator) {
            int rows=pressureColumns.get(PressureField.ROWS).count();
            // Reuse the same compiled command blocks when batching predicted work.
            // Converged stages retain the device's zero-work dispatches.
            for(int remaining=count;remaining>0;){
                int block=Math.min(16,remaining);remaining-=block;
                templates.record(command,new VulkanCommandTemplates.Key(0,output,errors,block,maximum,gasOperator==null?null:gasOperator.preconditionerKey()),
                        secondary->region.pressure.recordIterations(secondary,rows,rows,maximum,region.multigrid,hierarchy,block,gasOperator));
            }
        }

        void recordCapacityIterations(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,int count,int phase) {
            templates.record(command,new VulkanCommandTemplates.Key(1,output,errors,count,phase),
                    secondary->{region.kernels.capacityKernel.recordIterations(secondary,transport.capacitySolve,count,phase);});
        }
        private void traceStage(String name,long[] mark){if(mark==null)return;long gpu=region.storage.metrics().gpuNanos(),now=System.nanoTime();
            System.out.printf("mechanical_stage=%s gpu_ms=%.3f wall_ms=%.3f%n",name,(gpu-mark[0])*1e-6,(now-mark[1])*1e-6);mark[0]=gpu;mark[1]=now;}
        IllegalStateException failure(VulkanRegionStorage.Candidate candidate,int error,VulkanLiquidProjection.Parameters parameters) {
            if((error&4)!=0&&parameters!=null) {
                boolean aperture=capacityStage.startsWith("gas aperture");long offset=aperture?transport.aperture.flow.column(VulkanGasFlow.Field.CONTROL).offset():pressureColumns.get(PressureField.CONTROL).offset();
                var diagnostics=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);candidate.read(offset,diagnostics);
                var failure=new IllegalStateException("Resident "+(aperture?"gas aperture":"liquid")+" pressure failed: iterations="+(int)diagnostics.getDouble(0)+", residualRms="+diagnostics.getDouble(8)
                        +", target="+(aperture?1e-8:parameters.tolerance())+", status="+error+", inner="+region.pressureProgress);
                String dump=System.getProperty("mmm.gpuApertureFailureDump", "");
                if(aperture&&!dump.isBlank())try{
                    dumpGasFlowGraph(java.nio.file.Path.of(dump),parameters.duration(),transport.aperture.flow,
                            transport.aperture.eos.gas(),(at,length)->{var data=ByteBuffer.allocate(Math.toIntExact(length)).order(ByteOrder.LITTLE_ENDIAN);candidate.read(at,data);return data.flip();});
                }catch(RuntimeException captureFailure){failure.addSuppressed(captureFailure);}
                return failure;
            }
            return new IllegalStateException("Resident liquid candidate failed during "+capacityStage+": status="+error);
        }
        int[] capacities(){return new int[]{liquid.stockCapacity(),liquid.speciesCapacity(),liquid.limbCapacity(),transport.definitions.scratchLimbsPerWindow(),
                gasReserves==null?0:gasReserves.nodes(),gasReserves==null?0:transport.gas.state.componentCapacity(),gasReserves==null?0:transport.gas.state.speciesCapacity(),
                gasReserves==null?0:gasReserves.parts(),gasReserves==null?0:transport.successor.remap.speciesCapacity,gasReserves==null?0:gasReserves.groups(),gasReserves==null?0:gasReserves.pairs()};}
        VulkanLiquidWorkspace grow(int[] required) {
            var definitions=transport.definitions;int cells=liquid.cellCount();
            int stocks=growCount(liquid.stockCapacity(),required[5]);
            int species=growCount(liquid.speciesCapacity(),Math.max(required[6],ceil(required[4],7)));
            int limbNeed=Math.max(required[7],Math.max(required[0]-cells,required[1]-15*cells));
            int limbs=growCount(liquid.limbCapacity(),limbNeed);
            int windows=Math.addExact(Math.multiplyExact(stocks,7),Math.multiplyExact(cells,3));
            int windowLimbs=definitions.scratchLimbsPerWindow();
            if(required[2]>transport.materials.scratchLimbCapacity())windowLimbs=growCount(windowLimbs,ceil(required[2],windows));
            GasReserves nextGas=gasReserves;
            if(gasReserves!=null) {
                nextGas=new GasReserves(growGas(gasReserves.nodes(),Math.max(required[8],required[9])),
                        growGas(gasReserves.parts(),Math.max(required[14],required.length>19?required[19]:0)),growGas(gasReserves.groups(),required[17]),
                        growGas(gasReserves.pairs(),required.length>18?required[18]:0));
                limbs=growCount(limbs,Math.max(0,required[16]-cells));
            }
            if(stocks==liquid.stockCapacity()&&species==liquid.speciesCapacity()&&limbs==liquid.limbCapacity()&&windowLimbs==definitions.scratchLimbsPerWindow()&&java.util.Objects.equals(nextGas,gasReserves))
                throw new IllegalStateException("Device capacity error 64 after "+capacityStage+" reported no larger CSR requirement: "+java.util.Arrays.toString(required));
            return new VulkanLiquidWorkspace(region,new VulkanLiquidStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),stocks,species,limbs,liquid.alignment(),liquid.materialCount()),
                    definitions.capacities(stocks,species,limbs,windowLimbs),nextGas,contactCapacity,thermalContactCapacity,thermalBathCapacity,granularReserves,coherentSpeciesCapacity);
        }
        VulkanLiquidWorkspace growContacts(int required){return new VulkanLiquidWorkspace(region,liquid,transport.definitions,gasReserves,growCount(contactCapacity,required),thermalContactCapacity,thermalBathCapacity,granularReserves,coherentSpeciesCapacity);}
        VulkanLiquidWorkspace growThermalContacts(int required,int requiredBaths){
            if(required<=thermalContactCapacity&&requiredBaths<=thermalBathCapacity)throw new IllegalStateException("Thermal capacity feedback did not grow either column");
            return new VulkanLiquidWorkspace(region,liquid,transport.definitions,gasReserves,contactCapacity,growCount(thermalContactCapacity,required),growCount(thermalBathCapacity,requiredBaths),granularReserves,coherentSpeciesCapacity);
        }
        VulkanLiquidWorkspace growReactions(VulkanRegionReactions.CapacityGrowth required){
            int coherentSpecies=growCount(coherentSpeciesCapacity,required.coherentSpecies);
            int species=growCount(liquid.speciesCapacity(),required.species),limbs=growCount(liquid.limbCapacity(),required.limbs);
            var next=granularReserves==null?null:new GranularReserves(growCount(granularReserves.particles(),required.particles),growCount(granularReserves.deposits(),required.deposits),growCount(granularReserves.species(),required.solidSpecies));
            if(species==liquid.speciesCapacity()&&limbs==liquid.limbCapacity()&&java.util.Objects.equals(next,granularReserves)&&coherentSpecies==coherentSpeciesCapacity)throw new IllegalStateException("Reaction feedback did not grow inventory capacity");
            return new VulkanLiquidWorkspace(region,new VulkanLiquidStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),liquid.stockCapacity(),species,limbs,liquid.alignment(),liquid.materialCount()),
                    transport.definitions.capacities(liquid.stockCapacity(),species,limbs,transport.definitions.scratchLimbsPerWindow()),gasReserves,contactCapacity,thermalContactCapacity,thermalBathCapacity,next,coherentSpecies);
        }
        VulkanLiquidWorkspace growPhaseGas(PhaseGasCapacityGrowth required){
            var next=new GasReserves(growGas(gasReserves.nodes(),required.nodes),growGas(gasReserves.parts(),required.parts),growGas(gasReserves.groups(),required.groups),gasReserves.pairs());
            if(next.equals(gasReserves))throw new IllegalStateException("Phase gas feedback did not grow capacity");
            return new VulkanLiquidWorkspace(region,liquid,transport.definitions,next,contactCapacity,thermalContactCapacity,thermalBathCapacity,granularReserves,coherentSpeciesCapacity);
        }
        VulkanLiquidWorkspace growGranular(int[] required){var r=granularReserves;var next=new GranularReserves(growCount(r.particles(),required[0]),growCount(r.deposits(),required[1]),growCount(r.species(),required[2]));
            if(next.equals(r))throw new IllegalStateException("Granular lifecycle feedback did not grow inventory");return new VulkanLiquidWorkspace(region,liquid,transport.definitions,gasReserves,contactCapacity,thermalContactCapacity,thermalBathCapacity,next,coherentSpeciesCapacity);}
        /** Candidate scratch is produced by its region.kernels. Persist inventory, constitutive tables, headers and warm caches only. */
        private VulkanBufferCopyPlan persistentCopies;
        void copyPersistent(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack) {
            if(persistentCopies==null){
                var plan=new VulkanBufferCopyPlan.Builder();
                for(var field:VulkanLiquidStateLayout.Field.values()){var c=liquid.column(field);plan.copy(c,c);}
                for(var field:new GeometryField[]{GeometryField.FIXED_MASKS,GeometryField.OCCUPIED_SOLID}){var c=geometry.get(field);plan.copy(c,c);}
                var warm=pressureColumns.get(PressureField.PRESSURE);plan.copy(warm,warm);
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
        void copySourceGeometry(org.lwjgl.vulkan.VkCommandBuffer command,VulkanLiquidWorkspace source,boolean sourceCurrentGas,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            var plan=new VulkanBufferCopyPlan.Builder();
            for(var f:new GeometryField[]{GeometryField.CELLS,GeometryField.PLANES,GeometryField.LIQUID_MICRO,GeometryField.WALL_GEOMETRY})plan.copy(source.geometry.get(f),geometry.get(f));
            var completedFaces=sourceCurrentGas?source.geometry.get(GeometryField.FACES):source.transport.futureFaces;
            plan.copy(completedFaces,geometry.get(GeometryField.FACES));
            if(transport!=null&&transport.gas!=null){var old=source.transport;transport.gas.geometry.copyDerived(plan,sourceCurrentGas?old.gas.geometry:old.successor.geometry);}
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
            plan.build().record(command,input,output,stack);
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
            if(to.successor!=null)to.successor.migrate(command,from.successor,input,output,stack);
            if(to.granular!=null)to.granular.migrate(command,from.granular,input,output,stack);
            if(to.coherent!=null)to.coherent.migrate(command,from.coherent,input,output,stack);
            if(to.devices!=null)to.devices.migrate(command,from.devices,input,output,stack);
            if(to.aperture!=null)to.aperture.migrate(command,from.aperture,input,output,stack);
            if(to.phaseGasTopology!=null){var old=from.phaseGasTopology.tables();var next=to.phaseGasTopology.tables();for(int i=0;i<old.size();i++)VulkanRegionStorage.copy(command,input,old.get(i).offset(),output,next.get(i).offset(),old.get(i).bytes().remaining(),stack);}
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
        final GranularSection granular;
        final CoherentSection coherent;
        final DeviceSection devices;
        final GranularDynamics dynamics;
        final VulkanLiquidThermodynamics.Layout sourceThermal;
        final VulkanLiquidViscosity.Layout viscosity;
        final VulkanLiquidForces.Layout forces;
        final VulkanSurfaceTension.Layout surface;
        final VulkanMechanicalHeatTransport.Layout heatTransport;
        final VulkanLiquidCapacityLayout capacity;
        final VulkanLiquidCapacityInputs.Layout capacityInputs;
        final VulkanLiquidCapacity.Layout capacitySolve;
        final VulkanLiquidAcceptedVolumes.Layout accepted;
        final VulkanLiquidMaterialRoutes.Layout materials;
        final VulkanLiquidRouteQuantities.Layout quantities;
        final VulkanLiquidGather.Layout gather;
        final VulkanLiquidMomentum.Layout momentum;
        final VulkanLiquidThermodynamics.Layout liquidThermal;
        final GasSuccessor successor;
        final GasAperture aperture;
        final VulkanLiquidStateLayout.Column bathTemperatures;
        final VulkanRegionThermal.Layout thermal;
        final VulkanRegionReactions.Layout reactions;
        final VulkanPhaseGasTopology.Layout phaseGasTopology;final VulkanLiquidStateLayout.Column phaseBirth;
        final VulkanEnergyLedger.Layout thermalOnlyRoundoff,thermalOnlyMechanical;
        final VulkanMechanicalBodies.Layout bodies;
        final VulkanMechanicalEnergy.Layout energy;
        final VulkanEntityBodyCoupling.Layout sourceEntity,entity;
        final VulkanGasMechanicalHeat.Layout gasHeat;
        final VulkanEnergyLedger.Layout roundoff,heat,mechanicalPosting,sourcePosting,sinkPosting,inventoryPosting;
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
            viscosity=new VulkanLiquidViscosity.Layout(liquid,geometry.get(GeometryField.CELLS),geometry.get(GeometryField.FACES),geometry.get(GeometryField.WALL_GEOMETRY),word(sourceThermal.cellTemperatures()),word(activityFaces),word(activityCells),
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
            dynamics=granular==null?null:sections.new GranularDynamics(granular,gas,viscosity.column(VulkanLiquidViscosity.Field.MU),entity==null?null:entity.column(VulkanEntityBodyCoupling.Field.FACE_AREA),gas.endOffset);
            if(entity!=null&&gas!=null)entity.bindPressure(gas.operator,dynamics==null?null:dynamics.coupled);
            if(entity!=null&&gas==null)entity.bindPressure(new VulkanGasPressure.PressureBuffers(pressureColumns.get(PressureField.STARTS),pressureColumns.get(PressureField.COLUMNS),pressureColumns.get(PressureField.VALUES),pressureColumns.get(PressureField.ROWS),pressureColumns.get(PressureField.PRESSURE),pressureColumns.get(PressureField.WORK),pressureColumns.get(PressureField.META),pressureColumns.get(PressureField.PROBLEM),pressureColumns.get(PressureField.CONTROL)));
            capacity=new VulkanLiquidCapacityLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gas==null?0:gas.state.nodeCapacity(),dynamics!=null?dynamics.endOffset:gas==null?solidsBase:gas.endOffset,liquid.alignment());
            capacityInputs=VulkanLiquidCapacityInputs.layout(volumes,capacity,word(geometry.get(GeometryField.FIXED_MASKS)),
                    word(entity!=null?entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ)),word(geometry.get(GeometryField.FACES)),word(geometry.get(GeometryField.FLUXES)),
                    gas==null?null:gas.geometry,gas==null?null:gas.state,gas==null?null:gas.thermo,gas==null?null:gas.operator,word(activityCells),word(activityFaces),capacity.endOffset(),liquid.alignment());
            capacitySolve=new VulkanLiquidCapacity.Layout(capacity,capacityInputs.endOffset(),liquid.alignment());
            accepted=VulkanLiquidAcceptedVolumes.layout(volumes,capacity.word(VulkanLiquidCapacityLayout.Field.ACCEPTED),
                    capacitySolve.endOffset(),liquid.alignment(),Math.addExact(volumes.limbCapacity(),Math.multiplyExact(cells,14)));
            materials=VulkanLiquidMaterialRoutes.layout(liquid,routes,word(accepted.descriptors()),word(phaseTable),-1,accepted.sourceCellVolumes().quantumExponent(),
                    Math.multiplyExact(7*stocks+3*cells,definitions.scratchLimbsPerWindow()),accepted.endOffset(),liquid.alignment());
            quantities=VulkanLiquidRouteQuantities.layout(liquid,routes,word(materialReference),word(speciesReference),definitions.speciesStride(),materials.endOffset(),liquid.alignment());
            gather=VulkanLiquidGather.layout(liquid,liquid,routes,word(geometry.get(GeometryField.FIXED_MASKS)),word(massTable),quantities.endOffset(),liquid.alignment());
            heatTransport=VulkanMechanicalHeatTransport.layout(liquid,liquid,routes,word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_HEAT)),gather.endOffset(),liquid.alignment());
            momentum=VulkanLiquidMomentum.layout(liquid,liquid,routes,word(geometry.get(GeometryField.FACES)),word(futureFaces),-1,word(activityCells),word(activityFaces),heatTransport.endOffset(),liquid.alignment());
            liquidThermal=VulkanLiquidThermodynamics.layout(liquid,definitions.thermal(),word(materialReference),word(speciesReference),definitions.speciesStride(),momentum.endOffset(),liquid.alignment());
            successor=gas==null?null:sections.new GasSuccessor(gas,accepted,liquidThermal.endOffset(),word(futureFaces),entity==null?gas.geometry.occupiedWordOffset():entity.word(VulkanEntityBodyCoupling.Field.COMBINED_SVQ));
            bodies=VulkanMechanicalBodies.layout(liquid,routes,word(geometry.get(GeometryField.FACES)),gas==null?-1:word(gas.geometry.cellNodes()),
                    gas==null?0:gas.state.nodeCapacity(),gas==null?-1:Math.toIntExact(gas.state.column(VulkanGasStateLayout.Field.COUNTS).offset()/4),
                    dynamics==null?null:new VulkanMechanicalBodies.GranularLinks(dynamics.transitions.source,dynamics.pressure,dynamics.contacts.word(VulkanGranularContacts.Field.INTERVAL_COMPONENT)),
                    successor==null?liquidThermal.endOffset():successor.endOffset,liquid.alignment());
            gasHeat=successor==null||region.gasConfiguration.tables().isothermal()?null:VulkanGasMechanicalHeat.layout(successor.work,bodies.endOffset(),liquid.alignment());
            var mechanicalEnergy=VulkanMechanicalEnergy.layout(bodies,momentum,liquidThermal,successor==null?-1:word(successor.work.workBySource()),
                    word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_HEAT)),entity==null?word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_WORK)):entity.word(VulkanEntityBodyCoupling.Field.SOURCE_WORK),
                    dynamics==null?null:new VulkanMechanicalEnergy.GranularEnergy(dynamics.snapshotThermal,dynamics.snapshotGeometry,-1,dynamics.contacts.word(VulkanGranularContacts.Field.INTERVAL_PARTICLE_WORK)),(gasHeat==null?bodies.endOffset():gasHeat.endOffset()),liquid.alignment()).withSourceFaceK(dynamics==null?-1:word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_FACE_K)));
            if(entity!=null)mechanicalEnergy=mechanicalEnergy.withGasExternalWork(entity.word(VulkanEntityBodyCoupling.Field.GAS_EXTERNAL_WORK));
            energy=gasHeat==null?mechanicalEnergy:mechanicalEnergy.withGasEnergy(gasHeat);
            aperture=successor==null?null:sections.new GasAperture(gas,successor,energy.endOffset());
            long thermalBase=aperture==null?energy.endOffset():aperture.endOffset;
            if(region.thermalConfiguration==null){bathTemperatures=null;thermal=null;}
            else{long[] tail={thermalBase};bathTemperatures=column(tail,region.thermalConfiguration.bathTemperatures().length,8);
                var inputs=new VulkanThermalContacts.Inputs(liquid,liquidThermal,gas==null?null:gas.state,aperture==null?null:aperture.eos,
                        successor==null?null:successor.geometry,granular==null?null:granular.state,granular==null?null:granular.thermal,
                        coherent==null?null:coherent.state,coherent==null?null:coherent.thermal,devices==null?null:devices.state,devices==null?null:devices.thermal,
                        word(geometry.get(GeometryField.FIXED_MASKS)),bathTemperatures.count()==0?-1:word(bathTemperatures),word(geometry.get(GeometryField.FACES)),region.thermalConfiguration.cellSize(),word(activityCells),
                        word(geometry.get(GeometryField.PLANES)),word(geometry.get(GeometryField.CELLS)));
                thermal=VulkanRegionThermal.layout(inputs,region.thermalConfiguration,thermalContactCapacity,thermalBathCapacity,tail[0],liquid.alignment());thermalBase=thermal.endOffset();}
            VulkanRegionReactions.SolidInputs solidReactions=null;
            if(region.reactionConfiguration!=null&&granular!=null){
                var phaseTransitions=new VulkanGranularTransitions.Layout(granular.state,granular.geometry,geometry.get(GeometryField.CELLS),geometry.get(GeometryField.FACES),liquid,
                        geometry.get(GeometryField.FIXED_MASKS),viscosity.column(VulkanLiquidViscosity.Field.MU),thermalBase,liquid.alignment());
                phaseTransitions.bindActivity(word(activityCells));
                solidReactions=new VulkanRegionReactions.SolidInputs(phaseTransitions,granular.thermal,region.granularConfiguration.registry(),region.thermalConfiguration.cellSize(),word(geometry.get(GeometryField.FACES)));
                thermalBase=phaseTransitions.endOffset();
            }
            reactions=region.reactionConfiguration==null?null:VulkanRegionReactions.layout(region.reactionConfiguration,liquid,liquidThermal,solidReactions,gas==null?null:new VulkanGasPhaseChange.Inputs(aperture==null?gas.thermo:aperture.eos,successor==null?gas.geometry:successor.geometry,liquidThermal,region.reactionConfiguration.gravity()),coherent==null?null:new VulkanCondensedPhaseChange.Inputs(liquid,coherent.state,liquidThermal,coherent.thermal,null,null,word(gas==null?pressureColumns.get(PressureField.PRESSURE):gas.operator.column(VulkanGasPressure.Field.CELL_PHYSICAL_PRESSURE)),word(geometry.get(GeometryField.FIXED_MASKS)),word(entity!=null?entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ)),region.thermalConfiguration.cellSize(),region.reactionConfiguration.gravity()),thermalBase,liquid.alignment(),word(activityCells),word(activityFaces));
            if(reactions!=null)thermalBase=reactions.endOffset();
            if(gas!=null&&reactions!=null){
                if(reactions.gasPhase()!=null)phaseBirth=reactions.gasPhase().column(VulkanGasPhaseChange.Field.BIRTH_NODE);
                else{phaseBirth=new VulkanLiquidStateLayout.Column(align(thermalBase),cells,4);thermalBase=phaseBirth.offset()+phaseBirth.bindingBytes();}
                phaseGasTopology=VulkanPhaseGasTopology.layout(gas.thermo,gas.geometry,word(entity!=null?entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ)),word(geometry.get(GeometryField.CELLS)),word(geometry.get(GeometryField.PLANES)),word(geometry.get(GeometryField.LIQUID_MICRO)),word(phaseBirth),region.gasConfiguration.ambient(),gasReserves.nodes(),gasReserves.groups(),gasReserves.parts(),thermalBase,liquid.alignment());thermalBase=phaseGasTopology.endOffset();
            }else{phaseGasTopology=null;phaseBirth=null;}
            var thermalStreams=new ArrayList<VulkanEnergyLedger.Stream>();if(thermal!=null)thermalStreams.addAll(thermal.roundoffStreams());if(reactions!=null)thermalStreams.addAll(reactions.roundoffStreams());if(phaseGasTopology!=null)thermalStreams.addAll(phaseGasTopology.roundoffStreams());
            thermalOnlyRoundoff=VulkanEnergyLedger.layout(thermalStreams,thermalBase,liquid.alignment());thermalBase=thermalOnlyRoundoff.endOffset();
            thermalOnlyMechanical=VulkanEnergyLedger.layout(reactions==null?List.of():reactions.mechanicalWorkStreams(),thermalBase,liquid.alignment());thermalBase=thermalOnlyMechanical.endOffset();
            var roundoffStreams=new ArrayList<VulkanEnergyLedger.Stream>(List.of(stream(quantities.energyRemainders(),1),stream(gather.energyRemainders(),-1),
                    stream(momentum.heatPostingRemainders(),1),stream(momentum.heatAllocationRemainders(),1)));
            roundoffStreams.addAll(entity==null?viscosity.externalWorkRoundoffStreams():entity.externalWorkRoundoffStreams());roundoffStreams.addAll(heatTransport.roundoffStreams());
            roundoffStreams.addAll(energy.roundoffStreams());if(successor!=null)roundoffStreams.addAll(successor.remap.roundoffStreams());
            if(dynamics!=null){roundoffStreams.addAll(dynamics.contacts.externalWorkRoundoffStreams());roundoffStreams.addAll(dynamics.transitions.roundoffStreams());}
            if(aperture!=null)roundoffStreams.addAll(aperture.remap.roundoffStreams());
            if(thermal!=null)roundoffStreams.addAll(thermal.roundoffStreams());if(reactions!=null)roundoffStreams.addAll(reactions.roundoffStreams());if(phaseGasTopology!=null)roundoffStreams.addAll(phaseGasTopology.roundoffStreams());
            roundoff=VulkanEnergyLedger.layout(roundoffStreams,thermalBase,liquid.alignment());
            heat=VulkanEnergyLedger.layout(java.util.List.of(stream(momentum.cellHeat(),1)),roundoff.endOffset(),liquid.alignment());
            var work=new ArrayList<VulkanEnergyLedger.Stream>(List.of(stream(momentum.cellHeat(),1),stream(energy.faceTerms(),1),stream(energy.potentialTerms(),1)));
            if(entity==null)work.add(new VulkanEnergyLedger.Stream(1,word(viscosity.column(VulkanLiquidViscosity.Field.CONTACT_WORK)),viscosity.column(VulkanLiquidViscosity.Field.CONTACT_WORK).count(),2,1));
            else{work.add(new VulkanEnergyLedger.Stream(1,entity.word(VulkanEntityBodyCoupling.Field.BODY_RESULTS)+6,bodyCapacity,16,1));work.add(new VulkanEnergyLedger.Stream(1,entity.word(VulkanEntityBodyCoupling.Field.BODY_RESULTS)+8,bodyCapacity,16,1));}
            if(dynamics!=null){work.add(stream(energy.granularTerms(),1));work.add(stream(dynamics.contacts.column(VulkanGranularContacts.Field.FIXED_WORK),1));work.add(stream(dynamics.transitions.column(VulkanGranularTransitions.Field.HEAT),1));
                for(int i=0;i<17;i++)work.add(new VulkanEnergyLedger.Stream(1,dynamics.transitions.word(VulkanGranularTransitions.Field.LIFT_TERMS)+2*i,dynamics.transitions.inputRows,34,i<8?1:-1));}
            if(reactions!=null)work.addAll(reactions.mechanicalWorkStreams());
            mechanicalPosting=VulkanEnergyLedger.layout(work,heat.endOffset(),liquid.alignment());
            var incoming=new ArrayList<VulkanEnergyLedger.Stream>();var outgoing=new ArrayList<VulkanEnergyLedger.Stream>();
            if(successor!=null){incoming.add(new VulkanEnergyLedger.Stream(1,successor.remap.word(VulkanGasRemap.Field.AUDIT_ENERGY),1,2,1));outgoing.add(new VulkanEnergyLedger.Stream(1,successor.remap.word(VulkanGasRemap.Field.AUDIT_ENERGY)+2,1,2,1));}
            if(aperture!=null){incoming.add(new VulkanEnergyLedger.Stream(1,aperture.remap.word(VulkanGasRemap.Field.AUDIT_ENERGY),1,2,1));outgoing.add(new VulkanEnergyLedger.Stream(1,aperture.remap.word(VulkanGasRemap.Field.AUDIT_ENERGY)+2,1,2,1));}
            if(phaseGasTopology!=null){incoming.add(new VulkanEnergyLedger.Stream(1,phaseGasTopology.remap().word(VulkanGasRemap.Field.AUDIT_ENERGY),1,2,1));outgoing.add(new VulkanEnergyLedger.Stream(1,phaseGasTopology.remap().word(VulkanGasRemap.Field.AUDIT_ENERGY)+2,1,2,1));}
            if(reactions!=null){incoming.addAll(reactions.sourceStreams());outgoing.addAll(reactions.sinkStreams());}
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
                var local=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),source.snapshot().nodes().size(),gas.state.definitions(),8);
                var data=ByteBuffer.allocate(Math.toIntExact(local.bytes())).order(ByteOrder.LITTLE_ENDIAN);local.importState(data,source.snapshot(),source.nodeEnergy());
                for(var field:VulkanGasStateLayout.Field.values()){var c=local.column(field);if(c.bytes()>0)writer.put(gas.state.column(field).offset(),data.slice(Math.toIntExact(c.offset()),Math.toIntExact(c.bytes())));}
            }
            if(successor!=null){gasTables(writer,successor.retainedEOS);remapTables(writer,successor.remap);}
            if(aperture!=null){gasTables(writer,aperture.eos);gasTables(writer,aperture.retainedEOS);remapTables(writer,aperture.remap);}
            if(phaseGasTopology!=null)for(var chunk:phaseGasTopology.tables())writer.put(chunk.offset(),chunk.bytes());
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
                writer.put(dynamics.pressure.column(VulkanGranularPressure.Field.DIAMETERS),diameters);writer.put(dynamics.motion.columns.get(VulkanGranularMotion.Field.MATERIAL),contact);writer.put(dynamics.transitions.column(VulkanGranularTransitions.Field.MATERIALS).offset(),dynamics.transitions.materialBytes(granularConfiguration.registry()));
            }
        }
        void recordInitialize(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer input,GpuBuffer output,GpuBuffer errors,int maxRounds) {
            var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            region.kernels.volumeKernel.rebindRanges(src,dst,err);region.kernels.inputKernel.rebindRanges(dst,err);region.kernels.capacityKernel.rebindRanges(dst,err);
            region.kernels.acceptedKernel.rebindRanges(dst,err);region.kernels.materialKernel.rebindRanges(src,dst,err);region.kernels.quantityKernel.rebindRanges(src,dst,err);
            region.kernels.gatherKernel.rebindRanges(src,dst,err);region.kernels.momentumKernel.rebindRanges(src,dst,err);region.kernels.ledger.rebindRanges(src,dst,err);
            region.kernels.liquidThermalKernel.rebindRanges(dst,err);region.kernels.heatTransportKernel.rebindRanges(src,dst,err);region.kernels.bodiesKernel.rebindRanges(src,dst,err);region.kernels.energyKernel.rebindRanges(src,dst,err);if(gasHeat!=null)region.kernels.gasHeatKernel.rebindRanges(dst,err);
            region.kernels.futureGeometry.rebindRanges(geometryRanges(output,errors,futureFaces,entity==null?occupied():entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ)));
            if(successor!=null)successor.bind(output,errors);
            region.kernels.volumeKernel.record(command,volumes);
            if(dynamics==null&&entity==null)region.kernels.inputKernel.record(command,capacityInputs);else region.kernels.inputKernel.recordDisplacement(command,capacityInputs);
            region.kernels.capacityKernel.recordInitialize(command,capacitySolve,maxRounds);
            recordCapacityIterations(command,output,errors,Math.min(32,maxRounds),3);
        }
        void beginDeviceRequest(org.lwjgl.vulkan.VkCommandBuffer command,Transport old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack,boolean continuation){
            if(reactions!=null)region.kernels.reactionKernel.recordClearPostings(command,reactions,output);
            VulkanLiquidWorkspace.this.phaseGasCanonical=false;
            if(phaseGasTopology!=null)region.kernels.phaseGasTopology.recordClearPostings(command,phaseGasTopology,output);
            var clears=new ArrayList<VulkanLiquidStateLayout.Column>();if(phaseBirth!=null)clears.add(phaseBirth);
            if(successor!=null)clears.add(successor.remap.column(VulkanGasRemap.Field.AUDIT_ENERGY));if(aperture!=null)clears.add(aperture.remap.column(VulkanGasRemap.Field.AUDIT_ENERGY));
            if(!clears.isEmpty()){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);for(var column:clears)vkCmdFillBuffer(command,output.handle(),column.offset(),column.bytes(),0);VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}
            if(devices!=null&&!continuation){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);devices.state.recordBeginRequest(command,input,old.devices.state,output,stack);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}
        }
        void recordSolidGeometry(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,org.lwjgl.system.MemoryStack stack){
            recordSolidGeometry(command,output,errors,stack,
                    entity==null?null:region.bodyConfiguration(sourceBodyState),
                    entity==null?null:region.bodyConfiguration(region.requestedBodies()));
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
            region.kernels.entityKernel.recordContacts(command,entity,region.requestCellSize);if(dynamics==null)region.kernels.entityKernel.recordApertures(command,entity,region.requestCellSize);
        }}
        void recordBodyPressure(org.lwjgl.vulkan.VkCommandBuffer command,double duration){if(entity!=null)region.kernels.entityKernel.recordPressure(command,entity,region.requestCellSize,duration);}
        void bindGranular(GpuBuffer output,GpuBuffer errors){
            var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            region.kernels.granularGeometryKernel.rebindRanges(dst,err);region.kernels.granularPressureKernel.rebindRanges(dst,err);region.kernels.coupledKernel.rebindRanges(dst,err);
            region.kernels.contactKernel.rebindRanges(dst,err);region.kernels.motionKernel.rebindRanges(dst,err);region.kernels.granularThermalKernel.rebindRanges(dst,err);
        }
        void recordMotionFinish(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,VulkanLiquidProjection.Parameters parameters){
            region.kernels.granularGeometryKernel.recordOccupancy(command,granular.geometry);
            region.kernels.transitionKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
            region.kernels.transitionKernel.record(command,dynamics.transitions,output,parameters.cellSize(),parameters.duration(),parameters.gravity());
            region.kernels.granularGeometryKernel.recordOccupancy(command,dynamics.snapshotGeometry);
            if(entity!=null)region.kernels.entityKernel.recordCombinedOccupancy(command,entity,parameters.cellSize());
        }
        void recordFinish(org.lwjgl.vulkan.VkCommandBuffer command,int gravitySign) {
            region.kernels.capacityKernel.recordFinish(command,capacitySolve);
            region.kernels.timed(command,"accepted-volumes",()->region.kernels.acceptedKernel.record(command,accepted));
            region.kernels.timed(command,"material-routes",()->region.kernels.materialKernel.record(command,materials,gravitySign,region.kernels.timings));
            region.kernels.timed(command,"quantities",()->region.kernels.quantityKernel.record(command,quantities));
            region.kernels.timed(command,"gather",()->region.kernels.gatherKernel.record(command,gather));
            region.kernels.timed(command,"viscous-heat-transport",()->region.kernels.heatTransportKernel.record(command,heatTransport));
            region.kernels.timed(command,"momentum-transport",()->region.kernels.momentumKernel.recordTransport(command,momentum));
            region.kernels.timed(command,"future-liquid-geometry",()->{
                if(successor==null)region.kernels.futureGeometry.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                else region.kernels.futureGeometry.recordCells(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
            });
            if(successor!=null)successor.recordGeometry(command);
            region.kernels.timed(command,"momentum-resolve",()->region.kernels.momentumKernel.recordResolve(command,momentum));
            region.kernels.timed(command,"mixing-heat",()->region.kernels.momentumKernel.recordHeatPosting(command,momentum));
            region.kernels.timed(command,"body-init",()->region.kernels.bodiesKernel.recordInitialize(command,bodies));
            region.kernels.timed(command,"body-connectivity",()->region.kernels.bodiesKernel.recordConnectivity(command,bodies));
        }
        void recordEnergy(org.lwjgl.vulkan.VkCommandBuffer command,VulkanLiquidProjection.Parameters parameters) {
            if(successor!=null)successor.recordRemapPrepare(command);
            if(gasHeat!=null)region.kernels.timed(command,"gas-mechanical-caloric",()->region.kernels.gasHeatKernel.recordPrepare(command,gasHeat));
            region.kernels.timed(command,"body-csr",()->region.kernels.bodiesKernel.recordMembers(command,bodies));
            region.kernels.timed(command,"liquid-thermal",()->region.kernels.liquidThermalKernel.record(command,liquidThermal));
            if(granular!=null)region.kernels.timed(command,"granular-thermal",()->region.kernels.granularThermalKernel.record(command,dynamics.snapshotThermal));
            region.kernels.timed(command,"common-energy",()->region.kernels.energyKernel.record(command,energy,parameters.cellSize(),parameters.gravity()));
            if(dynamics!=null)region.kernels.transitionKernel.recordRefreshAfterEnergy(command,dynamics.transitions,parameters.cellSize(),parameters.duration(),parameters.gravity());
            if(gasHeat!=null)region.kernels.timed(command,"gas-mechanical-temperature",()->region.kernels.gasHeatKernel.recordRefresh(command,gasHeat));
            if(successor!=null)successor.recordRemapFinish(command);
        }
        void recordLedgers(org.lwjgl.vulkan.VkCommandBuffer command,Transport previous) {
            region.kernels.timed(command,"roundoff-ledger",()->region.kernels.ledger.record(command,roundoff));region.kernels.timed(command,"heat-ledger",()->region.kernels.ledger.record(command,heat));
            if(previous!=null){region.kernels.ledger.recordAccumulate(command,roundoff,word(previous.roundoff.result()));region.kernels.ledger.recordAccumulate(command,heat,word(previous.heat.result()));}
            region.kernels.ledger.record(command,inventoryPosting);region.kernels.ledger.record(command,mechanicalPosting);region.kernels.ledger.record(command,sourcePosting);region.kernels.ledger.record(command,sinkPosting);
            if(previous!=null){region.kernels.ledger.recordAccumulate(command,mechanicalPosting,word(previous.mechanicalPosting.result()));region.kernels.ledger.recordAccumulate(command,sourcePosting,word(previous.sourcePosting.result()));region.kernels.ledger.recordAccumulate(command,sinkPosting,word(previous.sinkPosting.result()));}
            if(thermal!=null){region.kernels.ledger.record(command,thermal.reservoir());if(previous!=null)region.kernels.ledger.recordAccumulate(command,thermal.reservoir(),word(previous.thermal.reservoir().result()));}
        }
        int[] requirements(VulkanRegionStorage.Candidate candidate) {
            var columns=new VulkanLiquidStateLayout.Column[]{volumes.totalOffsets(),accepted.volumeOffsets(),materials.reserveOffsets(),
                    routes.column(VulkanLiquidRouteLayout.Field.COUNTS),routes.column(VulkanLiquidRouteLayout.Field.SPECIES_REQUIRED),
                    liquid.column(CELL_OFFSETS),liquid.column(SPECIES_OFFSETS),liquid.column(VOLUME_OFFSETS)};
            long[] offsets=new long[8];for(int i=0;i<8;i++)offsets[i]=columns[i].offset()+4L*(columns[i].count()-1);
            if(successor!=null) {
                int count=capacityStage.startsWith("gas aperture")?(capacityStage.equals("gas aperture assemble")?19:27):18;
                offsets=java.util.Arrays.copyOf(offsets,count);offsets[8]=gas.geometry.componentCount().offset();offsets[9]=successor.geometry.componentCount().offset();
                long measured=successor.remap.requirements().offset();
                offsets[10]=measured+16;offsets[11]=measured+20;offsets[12]=measured+8;offsets[13]=measured+12;
                offsets[14]=measured;offsets[15]=measured+4;offsets[16]=successor.totals.totalOffsets().offset()+4L*liquid.cellCount();offsets[17]=successor.lineage.requiredGroupsOffset();
                if(count>18)offsets[18]=aperture.flow.requiredPairsOffset();
                if(count>19)for(int i=0;i<8;i++)offsets[19+i]=aperture.remap.requirements().offset()+4L*i;
            }
            var bytes=ByteBuffer.allocate(4*offsets.length).order(ByteOrder.LITTLE_ENDIAN);candidate.readWords(offsets,bytes);
            int[] result=new int[offsets.length];for(int i=0;i<result.length;i++)result[i]=bytes.getInt(4*i);result[3]=0;return result;
        }
        List<VulkanLiquidStateLayout.Column> persistentColumns() {
            var result=new ArrayList<VulkanLiquidStateLayout.Column>(List.of(phaseTable,massTable,materialReference,speciesReference,
                    volumes.header(),capacity.column(VulkanLiquidCapacityLayout.Field.HEADER),capacityInputs.header(),accepted.header(),materials.header(),quantities.header(),gather.header(),momentum.header(),
                    liquidThermal.header(),liquidThermal.offsets(),liquidThermal.upper(),liquidThermal.capacity(),liquidThermal.referenceTemperature(),liquidThermal.coldSensible(),
                    bodies.header(),energy.header(),roundoff.header(),heat.header(),mechanicalPosting.header(),sourcePosting.header(),sinkPosting.header(),inventoryPosting.header()));
            result.addAll(forceColumns());
            if(gas!=null) {
                result.add(gas.geometry.header());result.add(gas.operator.header());
                result.add(gas.operator.column(VulkanGasPressure.Field.CELL_PHYSICAL_PRESSURE));addGasTables(result,gas.thermo);
            }
            if(successor!=null) {
                result.addAll(List.of(successor.totals.header(),successor.geometry.header(),successor.lineage.header,successor.remap.header,successor.work.header()));
                addGasTables(result,successor.retainedEOS);
                for(var field:VulkanGasRemap.Field.values())if((field.name().startsWith("AMBIENT_")&&field!=VulkanGasRemap.Field.AMBIENT_BEFORE&&field!=VulkanGasRemap.Field.AMBIENT_AFTER||field==VulkanGasRemap.Field.DECLARED_SPECIES_OFFSETS||field==VulkanGasRemap.Field.DECLARED_SPECIES_HANDLES))result.add(successor.remap.column(field));
            }
            if(aperture!=null)result.addAll(aperture.persistentColumns());
            if(gasHeat!=null)result.add(gasHeat.header());
            if(entity!=null){result.add(entity.header);result.add(sourceEntity.header);}
            if(phaseGasTopology!=null){for(var chunk:phaseGasTopology.headers())result.add(new VulkanLiquidStateLayout.Column(chunk.offset(),chunk.bytes().remaining()/4,4));for(var chunk:phaseGasTopology.tables())result.add(new VulkanLiquidStateLayout.Column(chunk.offset(),chunk.bytes().remaining()/4,4));}
            if(reactions!=null)result.addAll(reactions.persistentColumns());result.add(thermalOnlyRoundoff.header());result.add(thermalOnlyMechanical.header());
            if(thermal!=null){result.add(bathTemperatures);result.addAll(thermal.persistentColumns());}
            result.add(capacitySolve.column(VulkanLiquidCapacity.Field.HEADER));
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
                    new HeaderWrite(surface.header().offset(),surface.headerBytes()),new HeaderWrite(heatTransport.header().offset(),heatTransport.headerBytes(source))));if(gas!=null)result.addAll(gas.headers());if(successor!=null)result.addAll(successor.headers());if(aperture!=null)result.addAll(aperture.headers());
            if(coherent!=null)result.addAll(coherent.headers());if(devices!=null)result.addAll(devices.headers());
            if(granular!=null){result.addAll(granular.headers());result.addAll(dynamics.headers());}
            result.add(new HeaderWrite(thermalOnlyRoundoff.header().offset(),thermalOnlyRoundoff.headerBytes()));
            result.add(new HeaderWrite(thermalOnlyMechanical.header().offset(),thermalOnlyMechanical.headerBytes()));
            if(gasHeat!=null)result.add(new HeaderWrite(gasHeat.header().offset(),gasHeat.headerBytes()));
            if(entity!=null){result.add(new HeaderWrite(entity.header.offset(),entity.headerBytes()));result.add(new HeaderWrite(sourceEntity.header.offset(),sourceEntity.headerBytes()));}
            if(phaseGasTopology!=null)for(var chunk:phaseGasTopology.headers())result.add(new HeaderWrite(chunk.offset(),chunk.bytes()));
            if(reactions!=null)for(var chunk:reactions.headers())result.add(new HeaderWrite(chunk.offset(),chunk.bytes()));
            if(thermal!=null)for(var chunk:thermal.headers())result.add(new HeaderWrite(chunk.offset(),chunk.bytes()));
            result.addAll(java.util.List.of(new HeaderWrite(volumes.header().offset(),volumes.headerBytes(source)),
                    new HeaderWrite(capacity.column(VulkanLiquidCapacityLayout.Field.HEADER).offset(),capacity.headerBytes()),
                    new HeaderWrite(capacityInputs.header().offset(),capacityInputs.headerBytes()),
                    new HeaderWrite(capacitySolve.column(VulkanLiquidCapacity.Field.HEADER).offset(),capacitySolve.headerBytes()),
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
