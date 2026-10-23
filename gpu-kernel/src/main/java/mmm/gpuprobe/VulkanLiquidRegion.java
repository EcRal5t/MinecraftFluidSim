package mmm.gpuprobe;

import mmm.fluid.FixedGeometrySnapshot;
import mmm.fluid.MacMomentumState;
import mmm.inventory.DeviceLiquidInventory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static mmm.gpuprobe.VulkanLiquidStateLayout.Field.*;
import static org.lwjgl.vulkan.VK10.*;

/** Resident ambient-liquid candidates, with replaceable layouts and shared native pipelines. */
final class VulkanLiquidRegion implements AutoCloseable {
    enum GeometryField { FIXED_MASKS, OCCUPIED_SOLID, CELLS, PLANES, LIQUID_MICRO, FACES, FLUXES }
    private enum PressureField { STARTS, COLUMNS, VALUES, ROWS, INPUT, PRESSURE, WORK, META, PROBLEM, CONTROL, PARTIALS }
    private final VulkanLiquidGeometry kernel;
    private final VulkanLiquidProjection projection;
    private final VulkanPressureKernel pressure;
    private final VulkanPressureMultigrid multigrid;
    private final Kernels kernels;
    private final VulkanRegionStorage storage;
    private final long maximumRange;
    private Workspace workspace;
    private final GasConfiguration gasConfiguration;

    record MechanicalDefinitions(int stockCapacity,int speciesCapacity,int limbCapacity,int scratchLimbsPerWindow,
                                 int quantumExponent,int[] materialPhases,long[] speciesMasses,
                                 double[] materialReferences,double[] speciesReferences,int speciesStride,VulkanLiquidThermodynamics.Tables thermal) {
        MechanicalDefinitions {
            materialPhases=materialPhases.clone();speciesMasses=speciesMasses.clone();
            materialReferences=materialReferences.clone();speciesReferences=speciesReferences.clone();
            if(stockCapacity<0||speciesCapacity<0||limbCapacity<0||scratchLimbsPerWindow<1
                    ||quantumExponent < -50||quantumExponent > -24||speciesStride<1
                    ||materialPhases.length==0||materialPhases.length!=materialReferences.length
                    ||speciesMasses.length<speciesStride||speciesReferences.length!=materialPhases.length*speciesStride
                    ||thermal==null||thermal.referenceTemperature().length!=materialPhases.length)
                throw new IllegalArgumentException("Invalid resident mechanical definitions or reserves");
        }
        static MechanicalDefinitions fromRegistries(mmm.fluid.FluidMaterialRegistry fluid,mmm.chemistry.ChemicalRegistry chemistry,
                mmm.thermal.ThermalRegistry thermal,int stockCapacity,int speciesCapacity,int limbCapacity,int scratchLimbsPerWindow,int quantumExponent) {
            if(!thermal.chemicalRegistryFingerprint().equals(chemistry.fingerprint()))
                throw new IllegalArgumentException("Thermal and chemical registry fingerprints differ");
            int materials=fluid.materials().size(),stride=chemistry.species().size();
            int[] phases=new int[materials];long[] masses=new long[stride];double[] reference=new double[materials],offsets=new double[materials*stride];
            java.util.Arrays.fill(offsets,Double.NaN);
            for(int species=0;species<stride;species++)masses[species]=chemistry.speciesMassUnits(species,1);
            for(int material=0;material<materials;material++) {
                phases[material]=fluid.phaseHandle(material);
                int host=thermal.materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,fluid.material(material).id());
                reference[material]=thermal.material(host).referenceSpecificEnergyJPerKg();
                for(int species=0;species<stride;species++)if(thermal.hasSpeciesOffset(host,chemistry.species(species).id()))
                    offsets[material*stride+species]=thermal.speciesOffset(host,chemistry.species(species).id()).referenceInternalEnergyJPerMol()
                            .divide(mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL).toDouble();
            }
            return new MechanicalDefinitions(stockCapacity,speciesCapacity,limbCapacity,scratchLimbsPerWindow,quantumExponent,phases,masses,reference,offsets,stride,VulkanLiquidThermodynamics.tables(fluid,thermal));
        }

        MechanicalDefinitions capacities(int stocks,int species,int limbs,int windowLimbs) {
            return new MechanicalDefinitions(stocks,species,limbs,windowLimbs,quantumExponent,materialPhases,speciesMasses,materialReferences,speciesReferences,speciesStride,thermal);
        }
    }
    record GasConfiguration(VulkanGasStateLayout.State initial,VulkanGasThermodynamics.Tables tables,
                            int nodeCapacity,int componentCapacity,int speciesCapacity,double cellSize,boolean sealedExterior) {
        GasConfiguration {
            java.util.Objects.requireNonNull(initial);java.util.Objects.requireNonNull(tables);
            if(nodeCapacity<initial.snapshot().nodes().size()||componentCapacity<0||speciesCapacity<0
                    ||!(cellSize>0&&Double.isFinite(cellSize)))throw new IllegalArgumentException("Invalid resident gas reserves");
        }
    }
    VulkanLiquidRegion(VulkanDeviceContext context,VulkanLiquidGeometry kernel,VulkanLiquidProjection projection,
                       VulkanPressureKernel pressure,VulkanPressureMultigrid multigrid,DeviceLiquidInventory inventory,
                       MacMomentumState momentum,FixedGeometrySnapshot fixed,long[] occupiedSolid) {
        this(context,kernel,projection,pressure,multigrid,inventory,momentum,fixed,occupiedSolid,null);
    }
    VulkanLiquidRegion(VulkanDeviceContext context,VulkanLiquidGeometry kernel,VulkanLiquidProjection projection,
                       VulkanPressureKernel pressure,VulkanPressureMultigrid multigrid,DeviceLiquidInventory inventory,
                       MacMomentumState momentum,FixedGeometrySnapshot fixed,long[] occupiedSolid,MechanicalDefinitions definitions) {
        this(context,kernel,projection,pressure,multigrid,inventory,momentum,fixed,occupiedSolid,definitions,null);
    }
    VulkanLiquidRegion(VulkanDeviceContext context,VulkanLiquidGeometry kernel,VulkanLiquidProjection projection,
                       VulkanPressureKernel pressure,VulkanPressureMultigrid multigrid,DeviceLiquidInventory inventory,
                       MacMomentumState momentum,FixedGeometrySnapshot fixed,long[] occupiedSolid,MechanicalDefinitions definitions,GasConfiguration gasConfiguration) {
        if(gasConfiguration!=null&&definitions==null)throw new IllegalArgumentException("Gas requires mechanical constitutive definitions");
        this.gasConfiguration=gasConfiguration;
        this.kernel=kernel;this.projection=projection;this.pressure=pressure;this.multigrid=multigrid;
        maximumRange=context.maxStorageBufferRange();
        var liquid=definitions==null?VulkanLiquidStateLayout.forState(inventory,momentum,context.storageOffsetAlignment())
                :new VulkanLiquidStateLayout(momentum.sizeX(),momentum.sizeY(),momentum.sizeZ(),definitions.stockCapacity(),
                        definitions.speciesCapacity(),definitions.limbCapacity(),context.storageOffsetAlignment());
        if(!fixed.matchesShape(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ())||occupiedSolid.length!=liquid.cellCount())
            throw new IllegalArgumentException("Resident liquid geometry differs from its grid");
        workspace=new Workspace(liquid,definitions);
        kernels=definitions==null?null:new Kernels(context);
        VulkanRegionStorage created=null;
        try {
            var initial=ByteBuffer.allocate(Math.toIntExact(workspace.bytes)).order(ByteOrder.LITTLE_ENDIAN);
            liquid.importState(initial,inventory,momentum);
            if(workspace.transport!=null)workspace.transport.initialize(initial);
            if(gasConfiguration!=null)workspace.transport.gas.state.importState(initial,gasConfiguration.initial().snapshot(),gasConfiguration.initial().nodeEnergy());
            var masks=workspace.geometry.get(GeometryField.FIXED_MASKS);var occupied=workspace.geometry.get(GeometryField.OCCUPIED_SOLID);
            for(int cell=0;cell<liquid.cellCount();cell++) {
                long capacity=(long)Integer.bitCount(~fixed.mask(cell)&255)<<21;
                if(occupiedSolid[cell]<0||occupiedSolid[cell]>capacity)throw new IllegalArgumentException("Solid inventory exceeds fixed open capacity");
                initial.putInt(Math.toIntExact(masks.offset()+4L*cell),fixed.mask(cell));
                initial.putLong(Math.toIntExact(occupied.offset()+8L*cell),occupiedSolid[cell]);
            }
            created=new VulkanRegionStorage(context,workspace.bytes,1024*1024);created.initialize(initial,0);
        }catch(RuntimeException|Error failure) {
            if(created!=null)try{created.close();}catch(RuntimeException|Error closing){failure.addSuppressed(closing);}
            if(kernels!=null)try{kernels.close();}catch(RuntimeException|Error closing){failure.addSuppressed(closing);}
            throw failure;
        }
        storage=created;
    }
    long rebuildGeometry(){return advance(null,false,0);}
    long prepareMechanicalFields(VulkanLiquidProjection.Parameters parameters){return advance(parameters,false,0);}
    long prepareMechanicalFields(VulkanLiquidProjection.Parameters parameters,boolean zeroInitialPressure){return advance(parameters,zeroInitialPressure,0);}
    long advanceMechanical(VulkanLiquidProjection.Parameters parameters,int maximumCapacityRounds) {
        java.util.Objects.requireNonNull(parameters,"Mechanical parameters");
        if(kernels==null)throw new IllegalStateException("Mechanical transport requires explicit constitutive definitions");
        if(maximumCapacityRounds<1)throw new IllegalArgumentException("Capacity round limit must be positive");
        if(gasConfiguration!=null)throw new IllegalStateException("Gas advancement requires successor inventory remapping");
        return advance(parameters,false,maximumCapacityRounds);
    }
    private synchronized long advance(VulkanLiquidProjection.Parameters parameters,boolean zeroInitialPressure,int capacityRounds) {
        Workspace source=workspace,target=source;int[] required=null;
        try(var capture=storage.capture()) {
            for(int attempt=0;attempt<4;attempt++) {
                try(var candidate=target==source?storage.prepare(capture):storage.prepareReallocated(capture,target.bytes)) {
                    int error=target.execute(source,candidate,parameters,zeroInitialPressure,capacityRounds);
                    if(error==0){long generation=candidate.commit();workspace=target;return generation;}
                    if(error==64&&target.transport!=null)required=target.transport.requirements(candidate);
                    else throw target.failure(candidate,error,parameters);
                }
                if(attempt==3)break;
                target=target.grow(required);
            }
        }
        throw new IllegalStateException("Resident liquid capacity retry limit reached: required="+java.util.Arrays.toString(required));
    }
    synchronized VulkanLiquidStateLayout.State checkpointLiquid() {
        try(var capture=storage.capture()) {
            var bytes=ByteBuffer.allocate(Math.toIntExact(workspace.liquid.bytes()));storage.read(capture,0,bytes);
            return workspace.liquid.exportState(bytes.flip());
        }
    }
    VulkanRegionStorage.Metrics metrics(){return storage.metrics();}
    synchronized double[] pressureDiagnostics() {
        try(var capture=storage.capture()) {
            var output=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);storage.read(capture,workspace.pressureColumns.get(PressureField.CONTROL).offset(),output);
            return new double[]{output.getDouble(0),output.getDouble(8),output.getDouble(16)};
        }
    }
    synchronized int[] inventoryCounts() {
        try(var capture=storage.capture()) {
            var output=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);storage.read(capture,workspace.liquid.column(COUNTS).offset(),output);
            return new int[]{output.getInt(0),output.getInt(4),output.getInt(8)};
        }
    }
    synchronized int[] capacityDiagnostics() {
        if(workspace.transport==null)throw new IllegalStateException("No resident capacity solver");
        try(var capture=storage.capture()) {
            var output=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);storage.read(capture,workspace.transport.capacity.column(VulkanLiquidCapacityLayout.Field.CONTROL).offset(),output);
            return new int[]{output.getInt(0),output.getInt(4)};
        }
    }
    synchronized VulkanLiquidStateLayout layout(){return workspace.liquid;}
    private record HeaderWrite(long offset,ByteBuffer bytes) { }
    private static long headerBytes(List<HeaderWrite> headers){return headers.stream().mapToLong(h->h.bytes().remaining()).sum();}

    private final class Workspace {
        final VulkanLiquidStateLayout liquid;
        final EnumMap<GeometryField,VulkanLiquidStateLayout.Column> geometry=new EnumMap<>(GeometryField.class);
        final EnumMap<PressureField,VulkanLiquidStateLayout.Column> pressureColumns=new EnumMap<>(PressureField.class);
        final VulkanPressureMultigrid.Layout hierarchy;
        final VulkanLiquidStateLayout.Column multigridColumn;
        final Transport transport;
        final long bytes;
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions) {
            this.liquid=liquid;
        int cells = liquid.cellCount(),rows=Math.addExact(cells,gasConfiguration==null?0:gasConfiguration.nodeCapacity());
        hierarchy = VulkanPressureMultigrid.layout(liquid.sizeX(), liquid.sizeY(), liquid.sizeZ(),rows);
        int faces = Math.addExact(Math.addExact(liquid.column(MOMENTUM_X).count(),
                liquid.column(MOMENTUM_Y).count()), liquid.column(MOMENTUM_Z).count());
        long cursor = liquid.bytes();
        for (GeometryField field : GeometryField.values()) {
            int width = field == GeometryField.FIXED_MASKS ? 4 : 8;
            int count = switch (field) {
                case FIXED_MASKS, OCCUPIED_SOLID -> cells;
                case CELLS, PLANES -> Math.multiplyExact(4, cells);
                case LIQUID_MICRO -> Math.multiplyExact(8, cells);
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
                case WORK -> Math.multiplyExact(rows, 5);
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
        transport=definitions==null?null:new Transport(definitions,total);
        if(transport!=null)total=transport.endOffset;
        bytes=total;
        if(bytes>maximumRange)throw new IllegalArgumentException("Resident workspace exceeds device descriptor range");

        }

        int execute(Workspace source,VulkanRegionStorage.Candidate candidate,VulkanLiquidProjection.Parameters parameters,
                    boolean zeroInitialPressure,int capacityRounds) {
            boolean migrating=source!=this;
            var sourceHeaders=migrating?transport.headers(source.liquid):List.<HeaderWrite>of();
            var stableHeaders=migrating?transport.headers(liquid):List.<HeaderWrite>of();
            long uploadedBytes=headerBytes(sourceHeaders);
            var gas=transport==null?null:transport.gas;
            int pressureRows=pressureColumns.get(PressureField.ROWS).count();
            var gasOperator=gas==null?null:new VulkanPressureKernel.GasOperator(kernels.gasPressure,gas.operator);
            VulkanRegionStorage.ComputeCommands prepare = (command, input, output, errors, staging, stack) -> {
                if(gas==null&&migrating)migrate(command,source,input,output,stack,sourceHeaders);
                kernel.rebindRanges(geometryRanges(output,errors,geometry.get(GeometryField.FACES)));
                int sx=liquid.sizeX(),sy=liquid.sizeY(),sz=liquid.sizeZ();
                if(gas==null)kernel.record(command, sx, sy, sz);
                if(parameters!=null) {
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
                    var assembly = new ArrayList<FlipKernelBindings.BufferRange>(10);
                    assembly.add(range(output, GeometryField.CELLS));
                    assembly.add(range(output, GeometryField.FACES));
                    for (PressureField field : new PressureField[]{PressureField.STARTS,PressureField.COLUMNS,PressureField.VALUES,
                            PressureField.ROWS,PressureField.META,PressureField.PROBLEM,PressureField.PRESSURE})
                        assembly.add(range(output,field));
                    assembly.add(new FlipKernelBindings.BufferRange(errors));
                    projection.rebindRanges(assembly);
                    projection.prepare(command,sx,sy,sz,parameters,gas==null?null:kernels.gasPressure,gas==null?null:gas.operator);
                    var solve = new ArrayList<FlipKernelBindings.BufferRange>(12);
                    for(PressureField field:PressureField.values())solve.add(range(output,field));
                    solve.add(new FlipKernelBindings.BufferRange(errors));
                    pressure.rebindRanges(solve);
                    var coarse = new ArrayList<FlipKernelBindings.BufferRange>(8);
                    for(PressureField field : new PressureField[]{PressureField.STARTS,PressureField.COLUMNS,PressureField.VALUES,
                            PressureField.ROWS,PressureField.META,PressureField.WORK,PressureField.CONTROL})
                        coarse.add(range(output,field));
                    coarse.add(new FlipKernelBindings.BufferRange(output,multigridColumn.offset(),multigridColumn.bindingBytes()));
                    multigrid.rebindRanges(coarse);
                    pressure.recordInitialize(command,pressureRows,pressureRows,parameters.iterations(),multigrid,hierarchy,gasOperator);
                    pressure.recordIterations(command,pressureRows,pressureRows,parameters.iterations(),multigrid,hierarchy,
                            Math.min(8,parameters.iterations()),gasOperator);
                }
            };
            int error;
            if(gas!=null) {
                if(parameters!=null&&Double.compare(parameters.cellSize(),gasConfiguration.cellSize())!=0)throw new IllegalArgumentException("Gas and mechanical cell sizes differ");
                var pre=candidate.beginBatch(uploadedBytes,(command,input,output,errors,staging,stack)-> {
                    if(migrating)migrate(command,source,input,output,stack,sourceHeaders);
                    kernel.rebindRanges(geometryRanges(output,errors,geometry.get(GeometryField.FACES)));
                    kernel.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                    var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                    kernels.volumeKernel.rebindRanges(src,dst,err);kernels.volumeKernel.record(command,transport.volumes);
                    kernels.gasGeometry.rebindRanges(dst,err);kernels.gasThermo.rebindRanges(dst,err);kernels.gasPressure.rebindRanges(dst,err);
                    kernels.gasGeometry.recordGeometry(command,gas.geometry);kernels.gasGeometry.recordConnectivity(command,gas.geometry,4);
                },gas.geometry.changed().offset(),4);
                int connectivityRounds=4;
                while(pre.error()==0&&pre.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0)!=0) {
                    if(connectivityRounds>=liquid.cellCount())throw new IllegalStateException("Gas connectivity did not converge");
                    pre=candidate.continueBatch((command,input,output,errors,staging,stack)->kernels.gasGeometry.recordConnectivity(command,gas.geometry,4),gas.geometry.changed().offset(),4);connectivityRounds+=4;
                }
                if(pre.error()!=0)return pre.error();
                pre=candidate.continueBatch((command,input,output,errors,staging,stack)-> {
                    kernels.gasGeometry.recordComponents(command,gas.geometry);kernels.gasThermo.recordVolumes(command,gas.thermo,gas.geometry);kernels.gasThermo.record(command,gas.thermo);
                },gas.geometry.changed().offset(),4);
                if(pre.error()!=0)return pre.error();
            }
            if (parameters == null) error = gas==null?candidate.execute(uploadedBytes, prepare):candidate.finishBatch(prepare);
            else {
                long runOffset = pressureColumns.get(PressureField.CONTROL).offset() + 24;
                var result = gas==null?candidate.beginBatch(uploadedBytes,prepare,runOffset,8):candidate.continueBatch(prepare,runOffset,8);
                int scheduled = Math.min(8, parameters.iterations());
                while (result.error() == 0 && result.feedback().getDouble(0) != 0.0 && scheduled < parameters.iterations()) {
                    int batch = Math.min(8, parameters.iterations() - scheduled);
                    result = candidate.continueBatch((command, input, output, errors, staging, stack) ->
                            pressure.recordIterations(command, pressureRows, pressureRows, parameters.iterations(),
                                    multigrid, hierarchy, batch,gasOperator), runOffset, 8);
                    scheduled += batch;
                }
                error = result.error();
                if(error==0) {
                    VulkanRegionStorage.ComputeCommands project=(command,input,output,errors,staging,stack)-> {
                        pressure.recordFinish(command,pressureRows,pressureRows,parameters.iterations(),multigrid,hierarchy,gasOperator);
                        projection.project(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),parameters,gas==null?null:kernels.gasPressure,gas==null?null:gas.operator);
                        kernel.recordTransportPreferences(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),parameters.cellSize(),parameters.duration());
                    };
                    if(capacityRounds==0)error=candidate.finishBatch(project);
                    else {
                        long control=transport.capacity.column(VulkanLiquidCapacityLayout.Field.CONTROL).offset();
                        var capacityResult=candidate.continueBatch((command,input,output,errors,staging,stack)-> {
                            project.record(command,input,output,errors,staging,stack);
                            transport.recordInitialize(command,input,output,errors,capacityRounds);
                        },control,8);
                        int status=capacityResult.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0);
                        int rounds=capacityResult.feedback().getInt(4);
                        while(capacityResult.error()==0&&status==VulkanLiquidCapacity.RUNNING&&rounds<capacityRounds) {
                            int count=Math.min(32,capacityRounds-rounds);
                            capacityResult=candidate.continueBatch((command,input,output,errors,staging,stack)->
                                    kernels.capacityKernel.recordIterations(command,transport.capacity,count),control,8);
                            status=capacityResult.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0);rounds=capacityResult.feedback().getInt(4);
                        }
                        error=capacityResult.error();
                        if(error==0&&status!=VulkanLiquidCapacity.FEASIBLE)
                            throw new IllegalStateException("Resident liquid capacity failed: status="+status+", rounds="+rounds);
                        if(error==0) {
                            var body=candidate.continueBatch((command,input,output,errors,staging,stack)->
                                    transport.recordFinish(command,parameters.gravity()>0?-1:parameters.gravity()<0?1:0),transport.bodies.changed().offset(),4);
                            int bodyRounds=4;
                            while(body.error()==0&&body.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0)!=0) {
                                if(bodyRounds>=transport.bodies.vertices())throw new IllegalStateException("Mechanical connectivity did not converge");
                                body=candidate.continueBatch((command,input,output,errors,staging,stack)->kernels.bodiesKernel.recordConnectivity(command,transport.bodies,4),transport.bodies.changed().offset(),4);bodyRounds+=4;
                            }
                            error=body.error();
                            if(error==0)error=candidate.finishBatch(migrating?headerBytes(stableHeaders):0,(command,input,output,errors,staging,stack)-> {
                                transport.recordEnergy(command,parameters);
                                if(migrating)writeHeaders(command,output,stableHeaders,stack);
                            });
                        }
                    }
                }
            }
            return error;
        }
        IllegalStateException failure(VulkanRegionStorage.Candidate candidate,int error,VulkanLiquidProjection.Parameters parameters) {
            if((error&4)!=0&&parameters!=null) {
                var diagnostics=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);candidate.read(pressureColumns.get(PressureField.CONTROL).offset(),diagnostics);
                return new IllegalStateException("Resident liquid pressure failed: iterations="+(int)diagnostics.getDouble(0)+", residualRms="+diagnostics.getDouble(8)
                        +", target="+parameters.tolerance()+", status="+error);
            }
            return new IllegalStateException("Resident liquid candidate failed: status="+error);
        }
        Workspace grow(int[] required) {
            var definitions=transport.definitions;int cells=liquid.cellCount();
            int stocks=growCount(liquid.stockCapacity(),required[5]);
            int species=growCount(liquid.speciesCapacity(),Math.max(required[6],ceil(required[4],7)));
            int limbNeed=Math.max(required[7],Math.max(required[0]-cells,required[1]-15*cells));
            limbNeed=Math.max(limbNeed,required[3]-14*stocks);
            int limbs=growCount(liquid.limbCapacity(),limbNeed);
            int windows=Math.addExact(Math.multiplyExact(stocks,7),Math.multiplyExact(cells,3));
            int windowLimbs=definitions.scratchLimbsPerWindow();
            if(required[2]>transport.materials.scratchLimbCapacity())windowLimbs=growCount(windowLimbs,ceil(required[2],windows));
            if(stocks==liquid.stockCapacity()&&species==liquid.speciesCapacity()&&limbs==liquid.limbCapacity()&&windowLimbs==definitions.scratchLimbsPerWindow())
                throw new IllegalStateException("Resident capacity error has no larger requirement: "+java.util.Arrays.toString(required));
            return new Workspace(new VulkanLiquidStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),stocks,species,limbs,liquid.alignment()),
                    definitions.capacities(stocks,species,limbs,windowLimbs));
        }
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,Workspace source,GpuBuffer input,GpuBuffer output,
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
            if(to.gas!=null)to.gas.migrate(command,from.gas,input,output,stack);
            writeHeaders(command,output,headers,stack);
        }
        void writeHeaders(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,List<HeaderWrite> headers,org.lwjgl.system.MemoryStack stack) {
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
            for(var header:headers)vkCmdUpdateBuffer(command,output.handle(),header.offset(),header.bytes().duplicate());
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    private ArrayList<FlipKernelBindings.BufferRange> geometryRanges(GpuBuffer buffer,GpuBuffer errors,VulkanLiquidStateLayout.Column faces) {
        var ranges=new ArrayList<FlipKernelBindings.BufferRange>(14);
        ranges.addAll(liquid.ranges(buffer,CELL_OFFSETS,NUMERIC_VOLUMES,MASS_UNITS));
        ranges.add(range(buffer,GeometryField.FIXED_MASKS));ranges.add(range(buffer,GeometryField.OCCUPIED_SOLID));
        ranges.addAll(liquid.ranges(buffer,MOMENTUM_X,MOMENTUM_Y,MOMENTUM_Z));
        ranges.add(range(buffer,GeometryField.CELLS));ranges.add(range(buffer,GeometryField.PLANES));
        ranges.add(range(buffer,GeometryField.LIQUID_MICRO));
        ranges.add(new FlipKernelBindings.BufferRange(buffer,faces.offset(),faces.bindingBytes()));
        ranges.add(new FlipKernelBindings.BufferRange(errors));ranges.add(range(buffer,GeometryField.FLUXES));return ranges;
    }

    private final class Transport {
        final MechanicalDefinitions definitions;
        final VulkanLiquidStateLayout.Column futureFaces,phaseTable,massTable,materialReference,speciesReference;
        final VulkanLiquidRouteLayout routes;
        final VulkanLiquidCellVolumes.Layout volumes;
        final GasSection gas;
        final VulkanLiquidCapacityLayout capacity;
        final VulkanLiquidCapacityInputs.Layout capacityInputs;
        final VulkanLiquidAcceptedVolumes.Layout accepted;
        final VulkanLiquidMaterialRoutes.Layout materials;
        final VulkanLiquidRouteQuantities.Layout quantities;
        final VulkanLiquidGather.Layout gather;
        final VulkanLiquidMomentum.Layout momentum;
        final VulkanLiquidThermodynamics.Layout liquidThermal;
        final VulkanMechanicalBodies.Layout bodies;
        final VulkanMechanicalEnergy.Layout energy;
        final VulkanEnergyLedger.Layout roundoff,heat;
        final long endOffset;
        Transport(MechanicalDefinitions definitions,long base) {
            this.definitions=definitions;int cells=liquid.cellCount(),stocks=liquid.stockCapacity();
            long[] cursor={base};
            futureFaces=column(cursor,geometry.get(GeometryField.FACES).count(),8);
            phaseTable=column(cursor,definitions.materialPhases().length,4);
            massTable=column(cursor,definitions.speciesMasses().length,8);
            materialReference=column(cursor,definitions.materialReferences().length,8);
            speciesReference=column(cursor,definitions.speciesReferences().length,8);
            routes=new VulkanLiquidRouteLayout(stocks,Math.multiplyExact(liquid.speciesCapacity(),7),
                    Math.addExact(Math.multiplyExact(stocks,14),liquid.limbCapacity()),cursor[0],liquid.alignment());
            volumes=VulkanLiquidCellVolumes.layout(liquid,Math.addExact(liquid.limbCapacity(),cells),definitions.quantumExponent(),routes.endOffset(),liquid.alignment());
            gas=gasConfiguration==null?null:new GasSection(volumes,volumes.endOffset());
            capacity=new VulkanLiquidCapacityLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gas==null?0:gas.state.nodeCapacity(),gas==null?volumes.endOffset():gas.endOffset,liquid.alignment());
            capacityInputs=VulkanLiquidCapacityInputs.layout(volumes,capacity,word(geometry.get(GeometryField.FIXED_MASKS)),
                    word(geometry.get(GeometryField.OCCUPIED_SOLID)),word(geometry.get(GeometryField.FACES)),word(geometry.get(GeometryField.FLUXES)),
                    gas==null?null:gas.geometry,gas==null?null:gas.state,capacity.endOffset(),liquid.alignment());
            accepted=VulkanLiquidAcceptedVolumes.layout(volumes,capacity.word(VulkanLiquidCapacityLayout.Field.ACCEPTED),
                    capacityInputs.endOffset(),liquid.alignment(),Math.addExact(volumes.limbCapacity(),Math.multiplyExact(cells,14)));
            materials=VulkanLiquidMaterialRoutes.layout(liquid,routes,word(accepted.descriptors()),word(phaseTable),-1,
                    Math.multiplyExact(7*stocks+3*cells,definitions.scratchLimbsPerWindow()),accepted.endOffset(),liquid.alignment());
            quantities=VulkanLiquidRouteQuantities.layout(liquid,routes,word(materialReference),word(speciesReference),definitions.speciesStride(),materials.endOffset(),liquid.alignment());
            gather=VulkanLiquidGather.layout(liquid,liquid,routes,word(geometry.get(GeometryField.FIXED_MASKS)),word(massTable),quantities.endOffset(),liquid.alignment());
            momentum=VulkanLiquidMomentum.layout(liquid,liquid,routes,word(geometry.get(GeometryField.FACES)),word(futureFaces),-1,gather.endOffset(),liquid.alignment());
            liquidThermal=VulkanLiquidThermodynamics.layout(liquid,definitions.thermal(),word(materialReference),word(speciesReference),definitions.speciesStride(),momentum.endOffset(),liquid.alignment());
            bodies=VulkanMechanicalBodies.layout(liquid,routes,word(geometry.get(GeometryField.FACES)),gas==null?-1:word(gas.geometry.cellNodes()),
                    gas==null?0:gas.state.nodeCapacity(),gas==null?-1:Math.toIntExact(gas.state.column(VulkanGasStateLayout.Field.COUNTS).offset()/4),liquidThermal.endOffset(),liquid.alignment());
            energy=VulkanMechanicalEnergy.layout(bodies,momentum,liquidThermal,-1,bodies.endOffset(),liquid.alignment());
            var roundoffStreams=new ArrayList<VulkanEnergyLedger.Stream>(List.of(stream(quantities.energyRemainders(),1),stream(gather.energyRemainders(),-1),
                    stream(momentum.heatPostingRemainders(),1),stream(momentum.heatAllocationRemainders(),1)));
            roundoffStreams.addAll(energy.roundoffStreams());
            roundoff=VulkanEnergyLedger.layout(roundoffStreams,energy.endOffset(),liquid.alignment());
            heat=VulkanEnergyLedger.layout(java.util.List.of(stream(momentum.cellHeat(),1)),roundoff.endOffset(),liquid.alignment());endOffset=heat.endOffset();

        }
        void initialize(ByteBuffer arena) {
            arena.duplicate().position(Math.toIntExact(phaseTable.offset())).slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(definitions.materialPhases());
            arena.duplicate().position(Math.toIntExact(massTable.offset())).slice().order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().put(definitions.speciesMasses());
            arena.duplicate().position(Math.toIntExact(materialReference.offset())).slice().order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().put(definitions.materialReferences());
            arena.duplicate().position(Math.toIntExact(speciesReference.offset())).slice().order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().put(definitions.speciesReferences());
            if(gas!=null)gas.initialize(arena);
            volumes.initializeHeader(arena);capacity.initializeHeader(arena);capacityInputs.initializeHeader(arena);
            accepted.initializeHeader(arena);materials.initializeHeader(arena);quantities.initializeHeader(arena);gather.initializeHeader(arena);
            momentum.initializeHeader(arena);liquidThermal.initializeHeaderAndTables(arena);bodies.initializeHeader(arena);energy.initializeHeader(arena);roundoff.initializeHeader(arena);heat.initializeHeader(arena);
        }
        void recordInitialize(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer input,GpuBuffer output,GpuBuffer errors,int maxRounds) {
            var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            kernels.volumeKernel.rebindRanges(src,dst,err);kernels.inputKernel.rebindRanges(dst,err);kernels.capacityKernel.rebindRanges(dst,err);
            kernels.acceptedKernel.rebindRanges(dst,err);kernels.materialKernel.rebindRanges(src,dst,err);kernels.quantityKernel.rebindRanges(src,dst,err);
            kernels.gatherKernel.rebindRanges(src,dst,err);kernels.momentumKernel.rebindRanges(src,dst,err);kernels.ledger.rebindRanges(src,dst,err);
            kernels.liquidThermalKernel.rebindRanges(dst,err);kernels.bodiesKernel.rebindRanges(src,dst,err);kernels.energyKernel.rebindRanges(src,dst,err);
            kernels.futureGeometry.rebindRanges(geometryRanges(output,errors,futureFaces));
            kernels.volumeKernel.record(command,volumes);kernels.inputKernel.record(command,capacityInputs);
            kernels.capacityKernel.recordInitialize(command,capacity,maxRounds);kernels.capacityKernel.recordIterations(command,capacity,Math.min(32,maxRounds));
        }
        void recordFinish(org.lwjgl.vulkan.VkCommandBuffer command,int gravitySign) {
            kernels.capacityKernel.recordFinish(command,capacity);kernels.acceptedKernel.record(command,accepted);kernels.materialKernel.record(command,materials,gravitySign);
            kernels.quantityKernel.record(command,quantities);kernels.gatherKernel.record(command,gather);kernels.momentumKernel.recordTransport(command,momentum);
            kernels.futureGeometry.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
            kernels.momentumKernel.recordResolve(command,momentum);kernels.momentumKernel.recordHeatPosting(command,momentum);
            kernels.bodiesKernel.recordInitialize(command,bodies);kernels.bodiesKernel.recordConnectivity(command,bodies,4);
        }
        void recordEnergy(org.lwjgl.vulkan.VkCommandBuffer command,VulkanLiquidProjection.Parameters parameters) {
            kernels.bodiesKernel.recordMembers(command,bodies);kernels.liquidThermalKernel.record(command,liquidThermal);
            kernels.energyKernel.record(command,energy,parameters.cellSize(),parameters.gravity());
            kernels.ledger.record(command,roundoff);kernels.ledger.record(command,heat);
        }
        int[] requirements(VulkanRegionStorage.Candidate candidate) {
            var columns=new VulkanLiquidStateLayout.Column[]{volumes.totalOffsets(),accepted.volumeOffsets(),materials.reserveOffsets(),
                    routes.column(VulkanLiquidRouteLayout.Field.VOLUME_OFFSETS),routes.column(VulkanLiquidRouteLayout.Field.SPECIES_OFFSETS),
                    liquid.column(CELL_OFFSETS),liquid.column(SPECIES_OFFSETS),liquid.column(VOLUME_OFFSETS)};
            long[] offsets=new long[8];for(int i=0;i<8;i++)offsets[i]=columns[i].offset()+4L*(columns[i].count()-1);
            var bytes=ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);candidate.readWords(offsets,bytes);
            int[] result=new int[8];for(int i=0;i<8;i++)result[i]=bytes.getInt(4*i);return result;
        }
        java.util.List<HeaderWrite> headers(VulkanLiquidStateLayout source) {
            var result=new ArrayList<HeaderWrite>();if(gas!=null)result.addAll(gas.headers());
            result.addAll(java.util.List.of(new HeaderWrite(volumes.header().offset(),volumes.headerBytes(source)),
                    new HeaderWrite(capacity.column(VulkanLiquidCapacityLayout.Field.HEADER).offset(),capacity.headerBytes()),
                    new HeaderWrite(capacityInputs.header().offset(),capacityInputs.headerBytes()),
                    new HeaderWrite(accepted.header().offset(),accepted.headerBytes()),new HeaderWrite(materials.header().offset(),materials.headerBytes(source)),
                    new HeaderWrite(quantities.header().offset(),quantities.headerBytes(source)),new HeaderWrite(gather.header().offset(),gather.headerBytes(source)),
                    new HeaderWrite(momentum.header().offset(),momentum.headerBytes(source)),new HeaderWrite(liquidThermal.header().offset(),liquidThermal.headerBytes()),
                    new HeaderWrite(bodies.header().offset(),bodies.headerBytes(source)),new HeaderWrite(energy.header().offset(),energy.headerBytes(source)),new HeaderWrite(roundoff.header().offset(),roundoff.headerBytes()),
                    new HeaderWrite(heat.header().offset(),heat.headerBytes())));return result;
        }
        private VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width) {
            var result=new VulkanLiquidStateLayout.Column(align(cursor[0]),count,width);cursor[0]=result.offset()+result.bindingBytes();return result;
        }
    }
    private final class GasSection {
        final VulkanGasGeometry.Layout geometry;
        final VulkanGasStateLayout state;
        final VulkanLiquidStateLayout.Column volumes;
        final VulkanGasThermodynamics.Layout thermo;
        final VulkanGasPressure.Layout operator;
        final long endOffset;
        GasSection(VulkanLiquidCellVolumes.Layout totals,long base) {
            var config=gasConfiguration;long alignment=liquid.alignment();
            geometry=VulkanGasGeometry.layout(totals,word(Workspace.this.geometry.get(GeometryField.FIXED_MASKS)),word(Workspace.this.geometry.get(GeometryField.OCCUPIED_SOLID)),
                    word(Workspace.this.geometry.get(GeometryField.CELLS)),word(Workspace.this.geometry.get(GeometryField.PLANES)),word(Workspace.this.geometry.get(GeometryField.LIQUID_MICRO)),
                    config.sealedExterior(),config.cellSize(),base,alignment);
            state=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),config.nodeCapacity(),config.componentCapacity(),config.speciesCapacity(),config.tables().definitions(),geometry.endOffset(),alignment);
            volumes=new VulkanLiquidStateLayout.Column(align(state.endOffset()),config.nodeCapacity(),8);
            thermo=VulkanGasThermodynamics.layout(state,config.tables(),word(volumes),volumes.offset()+volumes.bindingBytes(),alignment);
            operator=new VulkanGasPressure.Layout(thermo,geometry,Workspace.this.geometry.get(GeometryField.CELLS),Workspace.this.geometry.get(GeometryField.FACES),
                    new VulkanGasPressure.PressureBuffers(pressureColumns.get(PressureField.STARTS),pressureColumns.get(PressureField.COLUMNS),pressureColumns.get(PressureField.VALUES),
                            pressureColumns.get(PressureField.ROWS),pressureColumns.get(PressureField.PRESSURE),pressureColumns.get(PressureField.WORK),pressureColumns.get(PressureField.META),
                            pressureColumns.get(PressureField.PROBLEM),pressureColumns.get(PressureField.CONTROL)),thermo.endOffset(),alignment);endOffset=operator.endOffset();
        }
        void initialize(ByteBuffer arena){geometry.initializeHeader(arena);thermo.initializeHeaderAndTables(arena);arena.duplicate().position(4*operator.headerWordOffset()).put(operator.headerBytes());}
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(geometry.header().offset(),geometry.headerBytes()),new HeaderWrite(thermo.header().offset(),thermo.headerBytes()),
                new HeaderWrite(4L*operator.headerWordOffset(),operator.headerBytes()));}
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,GasSection old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            state.copyColumns(command,input,old.state,output,stack);
            var from=new VulkanLiquidStateLayout.Column[]{old.thermo.segmentOffsets(),old.thermo.segmentUpper(),old.thermo.segmentHeatCapacity(),old.thermo.specificAnchorEnergy(),old.thermo.speciesReference(),old.operator.column(VulkanGasPressure.Field.CELL_PHYSICAL_PRESSURE)};
            var to=new VulkanLiquidStateLayout.Column[]{thermo.segmentOffsets(),thermo.segmentUpper(),thermo.segmentHeatCapacity(),thermo.specificAnchorEnergy(),thermo.speciesReference(),operator.column(VulkanGasPressure.Field.CELL_PHYSICAL_PRESSURE)};
            for(int i=0;i<from.length;i++)copyColumn(command,input,from[i],output,to[i],stack);
        }
    }
    private FlipKernelBindings.BufferRange range(GpuBuffer buffer, GeometryField field) {
        var column = geometry.get(field);
        return new FlipKernelBindings.BufferRange(buffer, column.offset(), column.bindingBytes());
    }
    private FlipKernelBindings.BufferRange range(GpuBuffer buffer, PressureField field) {
        var column = pressureColumns.get(field);
        return new FlipKernelBindings.BufferRange(buffer, column.offset(), column.bindingBytes());
    }
    private long align(long value) { return Math.addExact(value,liquid.alignment()-1)&-liquid.alignment(); }
    }
    private static final class Kernels implements AutoCloseable {
        final java.util.ArrayList<AutoCloseable> owned=new java.util.ArrayList<>();
        VulkanLiquidGeometry futureGeometry;
        VulkanLiquidCellVolumes volumeKernel;
        VulkanLiquidCapacityInputs inputKernel;
        VulkanLiquidCapacity capacityKernel;
        VulkanLiquidAcceptedVolumes acceptedKernel;
        VulkanLiquidMaterialRoutes materialKernel;
        VulkanLiquidRouteQuantities quantityKernel;
        VulkanLiquidGather gatherKernel;
        VulkanLiquidMomentum momentumKernel;
        VulkanLiquidThermodynamics liquidThermalKernel;
        VulkanEnergyLedger ledger;
        VulkanMechanicalBodies bodiesKernel;
        VulkanMechanicalEnergy energyKernel;
        VulkanGasGeometry gasGeometry;
        VulkanGasThermodynamics gasThermo;
        VulkanGasPressure gasPressure;


        Kernels(VulkanDeviceContext context) {
            try {
                futureGeometry=own(new VulkanLiquidGeometry(context));volumeKernel=own(new VulkanLiquidCellVolumes(context));
                inputKernel=own(new VulkanLiquidCapacityInputs(context));capacityKernel=own(new VulkanLiquidCapacity(context));
                acceptedKernel=own(new VulkanLiquidAcceptedVolumes(context));materialKernel=own(new VulkanLiquidMaterialRoutes(context));
                quantityKernel=own(new VulkanLiquidRouteQuantities(context));gatherKernel=own(new VulkanLiquidGather(context));
                momentumKernel=own(new VulkanLiquidMomentum(context));liquidThermalKernel=own(new VulkanLiquidThermodynamics(context));ledger=own(new VulkanEnergyLedger(context));
                bodiesKernel=own(new VulkanMechanicalBodies(context));energyKernel=own(new VulkanMechanicalEnergy(context));
                gasGeometry=own(new VulkanGasGeometry(context));gasThermo=own(new VulkanGasThermodynamics(context));gasPressure=own(new VulkanGasPressure(context));

            }catch(RuntimeException|LinkageError failure){try{close();}catch(RuntimeException closing){failure.addSuppressed(closing);}throw failure;}
        }
        private <T extends AutoCloseable>T own(T value){owned.add(value);return value;}
        @Override public void close() {
            RuntimeException failure=null;
            for(int i=owned.size()-1;i>=0;i--)try{owned.get(i).close();}catch(Exception closing){if(failure==null)failure=new IllegalStateException("Resident transport close failed",closing);else failure.addSuppressed(closing);}
            if(failure!=null)throw failure;
        }
    }

    private static void copyColumn(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer source,VulkanLiquidStateLayout.Column from,
                                   GpuBuffer target,VulkanLiquidStateLayout.Column to,org.lwjgl.system.MemoryStack stack) {
        long bytes=Math.min(from.bytes(),to.bytes());if(bytes!=0)VulkanRegionStorage.copy(command,source,from.offset(),target,to.offset(),bytes,stack);
    }
    private static int growCount(int current,int required) {
        if(required<0)throw new IllegalArgumentException("Device capacity requirement exceeds int32 indexing");
        return required>current?Math.max(required,Math.multiplyExact(Math.max(1,current),2)):current;
    }
    private static int ceil(int value,int divisor){if(value<0)throw new IllegalArgumentException("Negative device capacity requirement");return (int)(((long)value+divisor-1)/divisor);}
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static VulkanEnergyLedger.Stream stream(VulkanLiquidStateLayout.Column column,int coefficient){return new VulkanEnergyLedger.Stream(1,word(column),column.count(),2,coefficient);}
    @Override public void close(){try{if(kernels!=null)kernels.close();}finally{storage.close();}}
}
