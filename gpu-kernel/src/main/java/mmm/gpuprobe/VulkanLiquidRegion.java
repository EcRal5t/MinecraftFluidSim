package mmm.gpuprobe;

import mmm.fluid.FixedGeometrySnapshot;
import mmm.fluid.MacMomentumState;
import mmm.inventory.DeviceLiquidInventory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;

import static mmm.gpuprobe.VulkanLiquidStateLayout.Field.*;
import static org.lwjgl.vulkan.VK10.*;

/** Owns resident liquid inventory, momentum and the geometry derived from the same generation. */
final class VulkanLiquidRegion implements AutoCloseable {
    enum GeometryField { FIXED_MASKS, OCCUPIED_SOLID, CELLS, PLANES, LIQUID_MICRO, FACES, FLUXES }

    private enum PressureField { STARTS, COLUMNS, VALUES, ROWS, INPUT, PRESSURE, WORK, META, PROBLEM, CONTROL, PARTIALS }
    private final EnumMap<PressureField, VulkanLiquidStateLayout.Column> pressureColumns = new EnumMap<>(PressureField.class);
    private final VulkanLiquidProjection projection;
    private final VulkanPressureKernel pressure;
    private final VulkanPressureMultigrid multigrid;
    private final VulkanPressureMultigrid.Layout hierarchy;
    private final VulkanLiquidStateLayout.Column multigridColumn;
    private final VulkanLiquidStateLayout liquid;
    private final EnumMap<GeometryField, VulkanLiquidStateLayout.Column> geometry = new EnumMap<>(GeometryField.class);
    private final VulkanRegionStorage storage;
    private final VulkanLiquidGeometry kernel;
    private final Transport transport;

    /** Explicit constitutive tables and reserve sizes for the ambient mechanical chain. */
    record MechanicalDefinitions(int stockCapacity,int speciesCapacity,int limbCapacity,int scratchLimbsPerWindow,
                                 int quantumExponent,int[] materialPhases,long[] speciesMasses,
                                 double[] materialReferences,double[] speciesReferences,int speciesStride) {
        MechanicalDefinitions {
            materialPhases=materialPhases.clone();speciesMasses=speciesMasses.clone();
            materialReferences=materialReferences.clone();speciesReferences=speciesReferences.clone();
            if(stockCapacity<0||speciesCapacity<0||limbCapacity<0||scratchLimbsPerWindow<1
                    ||quantumExponent < -50||quantumExponent > -24||speciesStride<1
                    ||materialPhases.length==0||materialPhases.length!=materialReferences.length
                    ||speciesMasses.length<speciesStride||speciesReferences.length!=materialPhases.length*speciesStride)
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
            return new MechanicalDefinitions(stockCapacity,speciesCapacity,limbCapacity,scratchLimbsPerWindow,quantumExponent,phases,masses,reference,offsets,stride);
        }

    }

    /** The backend owns the device and compiled kernels; a region owns its storage generations. */
    VulkanLiquidRegion(VulkanDeviceContext context, VulkanLiquidGeometry kernel,
                       VulkanLiquidProjection projection, VulkanPressureKernel pressure, VulkanPressureMultigrid multigrid,
                       DeviceLiquidInventory inventory, MacMomentumState momentum,
                       FixedGeometrySnapshot fixed, long[] occupiedSolid) {
        this(context,kernel,projection,pressure,multigrid,inventory,momentum,fixed,occupiedSolid,null);
    }

    VulkanLiquidRegion(VulkanDeviceContext context, VulkanLiquidGeometry kernel,
                       VulkanLiquidProjection projection, VulkanPressureKernel pressure, VulkanPressureMultigrid multigrid,
                       DeviceLiquidInventory inventory, MacMomentumState momentum,
                       FixedGeometrySnapshot fixed, long[] occupiedSolid,MechanicalDefinitions definitions) {
        liquid = definitions==null?VulkanLiquidStateLayout.forState(inventory,momentum,context.storageOffsetAlignment())
                :new VulkanLiquidStateLayout(momentum.sizeX(),momentum.sizeY(),momentum.sizeZ(),definitions.stockCapacity(),
                        definitions.speciesCapacity(),definitions.limbCapacity(),context.storageOffsetAlignment());
        if (!fixed.matchesShape(liquid.sizeX(), liquid.sizeY(), liquid.sizeZ())
                || occupiedSolid.length != liquid.cellCount())
            throw new IllegalArgumentException("Resident liquid geometry differs from its grid");
        this.kernel = kernel; this.projection = projection; this.pressure = pressure; this.multigrid = multigrid;
        hierarchy = VulkanPressureMultigrid.layout(liquid.sizeX(), liquid.sizeY(), liquid.sizeZ());
        int cells = liquid.cellCount();
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
                case STARTS -> Math.addExact(cells, 1);
                case COLUMNS, VALUES -> Math.multiplyExact(cells, 7);
                case ROWS, PRESSURE -> cells;
                case INPUT -> 1;
                case WORK -> Math.multiplyExact(cells, 5);
                case META -> Math.multiplyExact(cells, 2);
                case PROBLEM -> Math.addExact(Math.multiplyExact(cells, 3), 1);
                case CONTROL -> 16;
                case PARTIALS -> Math.multiplyExact((cells + 255) / 256, 4);
            };
            cursor = align(cursor);
            var column = new VulkanLiquidStateLayout.Column(cursor, count, width);
            pressureColumns.put(field, column);
            cursor = Math.addExact(cursor, column.bytes());
        }
        multigridColumn = new VulkanLiquidStateLayout.Column(align(cursor), hierarchy.workspaceDoubles(), 8);
        long bytes = align(multigridColumn.offset() + multigridColumn.bytes());
        transport=definitions==null?null:new Transport(context,definitions,bytes);
        if(transport!=null)bytes=transport.endOffset;
        VulkanRegionStorage created=null;
        try {
        if(bytes>context.maxStorageBufferRange())throw new IllegalArgumentException("Resident mechanical arena exceeds device descriptor range");
        var initial = ByteBuffer.allocate(Math.toIntExact(bytes)).order(ByteOrder.LITTLE_ENDIAN);
        liquid.importState(initial, inventory, momentum);
        if(transport!=null)transport.initialize(initial);
        var masks = geometry.get(GeometryField.FIXED_MASKS);
        var occupied = geometry.get(GeometryField.OCCUPIED_SOLID);
        for (int cell = 0; cell < cells; cell++) {
            long capacity = (long) Integer.bitCount(~fixed.mask(cell) & 255) << 21;
            if (occupiedSolid[cell] < 0 || occupiedSolid[cell] > capacity)
                throw new IllegalArgumentException("Solid inventory exceeds fixed open cell capacity");
            initial.putInt(Math.toIntExact(masks.offset() + 4L * cell), fixed.mask(cell));
            initial.putLong(Math.toIntExact(occupied.offset() + 8L * cell), occupiedSolid[cell]);
        }
        created = new VulkanRegionStorage(context,bytes,1024*1024);
        created.initialize(initial,0);
        }catch(RuntimeException|Error failure) {
            if(created!=null)try{created.close();}catch(RuntimeException|Error closing){failure.addSuppressed(closing);}
            if(transport!=null)try{transport.close();}catch(RuntimeException|Error closing){failure.addSuppressed(closing);}
            throw failure;
        }
        storage=created;

    }

    /** This stage prepares geometry only. The mechanical pipeline appends force/pressure/transport stages. */
    long rebuildGeometry() { return prepareFields(null, false); }

    /** Ambient-pressure liquid projection fields; finite gas and material transport join before a full step can publish. */
    long prepareMechanicalFields(VulkanLiquidProjection.Parameters parameters) { return prepareFields(parameters, false); }

    long prepareMechanicalFields(VulkanLiquidProjection.Parameters parameters, boolean zeroInitialPressure) {
        return prepareFields(parameters, zeroInitialPressure);
    }

    /** Advances inventory, momentum and mechanical heat in one private ambient-liquid candidate. */
    long advanceMechanical(VulkanLiquidProjection.Parameters parameters,int maximumCapacityRounds) {
        java.util.Objects.requireNonNull(parameters,"Mechanical parameters");
        if(transport==null)throw new IllegalStateException("Mechanical transport requires explicit constitutive definitions");
        if(maximumCapacityRounds<1)throw new IllegalArgumentException("Capacity round limit must be positive");
        return prepareFields(parameters,false,maximumCapacityRounds);
    }

    private long prepareFields(VulkanLiquidProjection.Parameters parameters, boolean zeroInitialPressure) {
        return prepareFields(parameters,zeroInitialPressure,0);
    }

    private long prepareFields(VulkanLiquidProjection.Parameters parameters, boolean zeroInitialPressure,int capacityRounds) {
        try (var source = storage.capture(); var candidate = storage.prepare(source)) {
            VulkanRegionStorage.ComputeCommands prepare = (command, input, output, errors, staging, stack) -> {
                kernel.rebindRanges(geometryRanges(output,errors,geometry.get(GeometryField.FACES)));
                int sx=liquid.sizeX(),sy=liquid.sizeY(),sz=liquid.sizeZ();
                kernel.record(command, sx, sy, sz);
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
                    projection.prepare(command,sx,sy,sz,parameters);
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
                    pressure.recordInitialize(command,liquid.cellCount(),liquid.cellCount(),parameters.iterations(),multigrid,hierarchy);
                    pressure.recordIterations(command,liquid.cellCount(),liquid.cellCount(),parameters.iterations(),multigrid,hierarchy,
                            Math.min(8,parameters.iterations()));
                }
            };
            int error;
            if (parameters == null) error = candidate.execute(0, prepare);
            else {
                long runOffset = pressureColumns.get(PressureField.CONTROL).offset() + 24;
                var result = candidate.beginBatch(prepare, runOffset, 8);
                int scheduled = Math.min(8, parameters.iterations());
                while (result.error() == 0 && result.feedback().getDouble(0) != 0.0 && scheduled < parameters.iterations()) {
                    int batch = Math.min(8, parameters.iterations() - scheduled);
                    result = candidate.continueBatch((command, input, output, errors, staging, stack) ->
                            pressure.recordIterations(command, liquid.cellCount(), liquid.cellCount(), parameters.iterations(),
                                    multigrid, hierarchy, batch), runOffset, 8);
                    scheduled += batch;
                }
                error = result.error();
                if(error==0) {
                    VulkanRegionStorage.ComputeCommands project=(command,input,output,errors,staging,stack)-> {
                        pressure.recordFinish(command,liquid.cellCount(),liquid.cellCount(),parameters.iterations(),multigrid,hierarchy);
                        projection.project(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),parameters);
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
                                    transport.capacityKernel.recordIterations(command,transport.capacity,count),control,8);
                            status=capacityResult.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0);rounds=capacityResult.feedback().getInt(4);
                        }
                        error=capacityResult.error();
                        if(error==0&&status!=VulkanLiquidCapacity.FEASIBLE)
                            throw new IllegalStateException("Resident liquid capacity failed: status="+status+", rounds="+rounds);
                        if(error==0)error=candidate.finishBatch((command,input,output,errors,staging,stack)->transport.recordFinish(command,parameters.gravity()>0?-1:parameters.gravity()<0?1:0));
                    }
                }
            }
            if (error != 0) {
                if ((error & 4) != 0 && parameters != null) {
                    var diagnostics = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
                    candidate.read(pressureColumns.get(PressureField.CONTROL).offset(), diagnostics);
                    throw new IllegalStateException("Resident liquid pressure failed: iterations=" + (int)diagnostics.getDouble(0)
                            + ", residualRms=" + diagnostics.getDouble(8) + ", target=" + parameters.tolerance() + ", status=" + error);
                }
                throw new IllegalStateException("Resident liquid candidate failed: status="+error+(capacityRounds==0?"":transport.prefixTails(candidate)));
            }
            return candidate.commit();
        }
    }

    /** Liquid inventory/momentum export is explicit and retains exact dyadic and integer columns. */
    VulkanLiquidStateLayout.State checkpointLiquid() {
        try (var capture = storage.capture()) {
            ByteBuffer bytes = ByteBuffer.allocate(Math.toIntExact(liquid.bytes()));
            storage.read(capture, 0, bytes);
            return liquid.exportState(bytes.flip());
        }
    }

    VulkanRegionStorage.Metrics metrics() { return storage.metrics(); }
    double[] pressureDiagnostics() {
        try (var capture = storage.capture()) {
            var output = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
            storage.read(capture, pressureColumns.get(PressureField.CONTROL).offset(), output);
            return new double[]{output.getDouble(0),output.getDouble(8),output.getDouble(16)};
        }
    }
    VulkanLiquidStateLayout layout() { return liquid; }
    int[] inventoryCounts() {
        try(var capture=storage.capture()) {
            var output=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);storage.read(capture,liquid.column(COUNTS).offset(),output);
            return new int[]{output.getInt(0),output.getInt(4),output.getInt(8)};
        }
    }
    int[] capacityDiagnostics() {
        if(transport==null)throw new IllegalStateException("No resident capacity solver");
        try(var capture=storage.capture()) {
            var output=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            storage.read(capture,transport.capacity.column(VulkanLiquidCapacityLayout.Field.CONTROL).offset(),output);
            return new int[]{output.getInt(0),output.getInt(4)};
        }
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

    private final class Transport implements AutoCloseable {
        final MechanicalDefinitions definitions;
        final VulkanLiquidStateLayout.Column futureFaces,phaseTable,massTable,materialReference,speciesReference;
        final VulkanLiquidRouteLayout routes;
        final VulkanLiquidCellVolumes.Layout volumes;
        final VulkanLiquidCapacityLayout capacity;
        final VulkanLiquidCapacityInputs.Layout capacityInputs;
        final VulkanLiquidAcceptedVolumes.Layout accepted;
        final VulkanLiquidMaterialRoutes.Layout materials;
        final VulkanLiquidRouteQuantities.Layout quantities;
        final VulkanLiquidGather.Layout gather;
        final VulkanLiquidMomentum.Layout momentum;
        final VulkanEnergyLedger.Layout roundoff,heat;
        final long endOffset;
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
        VulkanEnergyLedger ledger;

        Transport(VulkanDeviceContext context,MechanicalDefinitions definitions,long base) {
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
            capacity=new VulkanLiquidCapacityLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),0,volumes.endOffset(),liquid.alignment());
            capacityInputs=VulkanLiquidCapacityInputs.layout(volumes,capacity,word(geometry.get(GeometryField.FIXED_MASKS)),
                    word(geometry.get(GeometryField.OCCUPIED_SOLID)),word(geometry.get(GeometryField.FACES)),word(geometry.get(GeometryField.FLUXES)),
                    capacity.endOffset(),liquid.alignment());
            accepted=VulkanLiquidAcceptedVolumes.layout(volumes,capacity.word(VulkanLiquidCapacityLayout.Field.ACCEPTED),
                    capacityInputs.endOffset(),liquid.alignment(),Math.addExact(volumes.limbCapacity(),Math.multiplyExact(cells,14)));
            materials=VulkanLiquidMaterialRoutes.layout(liquid,routes,word(accepted.descriptors()),word(phaseTable),-1,
                    Math.multiplyExact(7*stocks+3*cells,definitions.scratchLimbsPerWindow()),accepted.endOffset(),liquid.alignment());
            quantities=VulkanLiquidRouteQuantities.layout(liquid,routes,word(materialReference),word(speciesReference),definitions.speciesStride(),materials.endOffset(),liquid.alignment());
            gather=VulkanLiquidGather.layout(liquid,liquid,routes,word(geometry.get(GeometryField.FIXED_MASKS)),word(massTable),quantities.endOffset(),liquid.alignment());
            momentum=VulkanLiquidMomentum.layout(liquid,liquid,routes,word(geometry.get(GeometryField.FACES)),word(futureFaces),-1,gather.endOffset(),liquid.alignment());
            roundoff=VulkanEnergyLedger.layout(java.util.List.of(stream(quantities.energyRemainders(),1),stream(gather.energyRemainders(),-1),
                    stream(momentum.heatPostingRemainders(),1),stream(momentum.heatAllocationRemainders(),1)),momentum.endOffset(),liquid.alignment());
            heat=VulkanEnergyLedger.layout(java.util.List.of(stream(momentum.cellHeat(),1)),roundoff.endOffset(),liquid.alignment());endOffset=heat.endOffset();
            try {
                futureGeometry=own(new VulkanLiquidGeometry(context));volumeKernel=own(new VulkanLiquidCellVolumes(context));
                inputKernel=own(new VulkanLiquidCapacityInputs(context));capacityKernel=own(new VulkanLiquidCapacity(context));
                acceptedKernel=own(new VulkanLiquidAcceptedVolumes(context));materialKernel=own(new VulkanLiquidMaterialRoutes(context));
                quantityKernel=own(new VulkanLiquidRouteQuantities(context));gatherKernel=own(new VulkanLiquidGather(context));
                momentumKernel=own(new VulkanLiquidMomentum(context));ledger=own(new VulkanEnergyLedger(context));
            }catch(RuntimeException|LinkageError failure){try{close();}catch(RuntimeException closing){failure.addSuppressed(closing);}throw failure;}
        }
        void initialize(ByteBuffer arena) {
            arena.duplicate().position(Math.toIntExact(phaseTable.offset())).slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(definitions.materialPhases());
            arena.duplicate().position(Math.toIntExact(massTable.offset())).slice().order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().put(definitions.speciesMasses());
            arena.duplicate().position(Math.toIntExact(materialReference.offset())).slice().order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().put(definitions.materialReferences());
            arena.duplicate().position(Math.toIntExact(speciesReference.offset())).slice().order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer().put(definitions.speciesReferences());
            volumes.initializeHeader(arena);capacity.initializeHeader(arena);capacityInputs.initializeHeader(arena);
            accepted.initializeHeader(arena);materials.initializeHeader(arena);quantities.initializeHeader(arena);gather.initializeHeader(arena);
            momentum.initializeHeader(arena);roundoff.initializeHeader(arena);heat.initializeHeader(arena);
        }
        void recordInitialize(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer input,GpuBuffer output,GpuBuffer errors,int maxRounds) {
            var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            volumeKernel.rebindRanges(src,dst,err);inputKernel.rebindRanges(dst,err);capacityKernel.rebindRanges(dst,err);
            acceptedKernel.rebindRanges(dst,err);materialKernel.rebindRanges(src,dst,err);quantityKernel.rebindRanges(src,dst,err);
            gatherKernel.rebindRanges(src,dst,err);momentumKernel.rebindRanges(src,dst,err);ledger.rebindRanges(src,dst,err);
            futureGeometry.rebindRanges(geometryRanges(output,errors,futureFaces));
            volumeKernel.record(command,volumes);inputKernel.record(command,capacityInputs);
            capacityKernel.recordInitialize(command,capacity,maxRounds);capacityKernel.recordIterations(command,capacity,Math.min(32,maxRounds));
        }
        void recordFinish(org.lwjgl.vulkan.VkCommandBuffer command,int gravitySign) {
            capacityKernel.recordFinish(command,capacity);acceptedKernel.record(command,accepted);materialKernel.record(command,materials,gravitySign);
            quantityKernel.record(command,quantities);gatherKernel.record(command,gather);momentumKernel.recordTransport(command,momentum);
            futureGeometry.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
            momentumKernel.recordResolve(command,momentum);momentumKernel.recordHeatPosting(command,momentum);
            ledger.record(command,roundoff);ledger.record(command,heat);
        }
        String prefixTails(VulkanRegionStorage.Candidate candidate) {
            var columns=new VulkanLiquidStateLayout.Column[]{volumes.totalOffsets(),accepted.volumeOffsets(),materials.reserveOffsets(),
                    routes.column(VulkanLiquidRouteLayout.Field.VOLUME_OFFSETS),routes.column(VulkanLiquidRouteLayout.Field.SPECIES_OFFSETS),
                    liquid.column(CELL_OFFSETS),liquid.column(SPECIES_OFFSETS),liquid.column(VOLUME_OFFSETS)};
            String[] names={"cellLimbs","acceptedLimbs","materialScratchLimbs","routeLimbs","routeSpecies","stocks","species","limbs"};
            var message=new StringBuilder("; last completed prefix tails:");
            for(int i=0;i<columns.length;i++) {
                var bytes=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);var column=columns[i];
                candidate.read(column.offset()+4L*(column.count()-1),bytes);message.append(' ').append(names[i]).append('=').append(bytes.getInt(0));
            }
            return message.toString();
        }
        private <T extends AutoCloseable>T own(T value){owned.add(value);return value;}
        private VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width) {
            var result=new VulkanLiquidStateLayout.Column(align(cursor[0]),count,width);cursor[0]=result.offset()+result.bindingBytes();return result;
        }
        @Override public void close() {
            RuntimeException failure=null;
            for(int i=owned.size()-1;i>=0;i--)try{owned.get(i).close();}catch(Exception closing){if(failure==null)failure=new IllegalStateException("Resident transport close failed",closing);else failure.addSuppressed(closing);}
            if(failure!=null)throw failure;
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static VulkanEnergyLedger.Stream stream(VulkanLiquidStateLayout.Column column,int coefficient) {
        return new VulkanEnergyLedger.Stream(1,word(column),column.count(),2,coefficient);
    }

    private FlipKernelBindings.BufferRange range(GpuBuffer buffer, GeometryField field) {
        var column = geometry.get(field);
        return new FlipKernelBindings.BufferRange(buffer, column.offset(), column.bindingBytes());
    }
    private FlipKernelBindings.BufferRange range(GpuBuffer buffer, PressureField field) {
        var column = pressureColumns.get(field);
        return new FlipKernelBindings.BufferRange(buffer, column.offset(), column.bindingBytes());
    }
    private long align(long value) { return Math.addExact(value, liquid.alignment() - 1) & -liquid.alignment(); }
    @Override public void close() { try { if(transport!=null)transport.close(); }finally{storage.close();} }
}
