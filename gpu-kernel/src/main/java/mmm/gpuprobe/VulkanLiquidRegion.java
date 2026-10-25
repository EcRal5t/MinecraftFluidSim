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

/** Resident liquid/gas mechanical candidates, with replaceable layouts and shared native pipelines. */
final class VulkanLiquidRegion implements AutoCloseable {
    enum GeometryField { FIXED_MASKS, OCCUPIED_SOLID, CELLS, PLANES, LIQUID_MICRO, FACES, FLUXES }
    private enum PressureField { STARTS, COLUMNS, VALUES, ROWS, INPUT, PRESSURE, WORK, META, PROBLEM, CONTROL, PARTIALS }
    private final VulkanDeviceContext context;
    private final VulkanLiquidGeometry kernel;
    private final VulkanLiquidProjection projection;
    private final VulkanPressureKernel pressure;
    private final VulkanPressureMultigrid multigrid;
    private final Kernels kernels;
    private final VulkanRegionStorage storage;
    private final long maximumRange;
    private Workspace workspace;
    private final List<Growth> growths=Boolean.getBoolean("mmm.gpuMechanicalGrowths")?new ArrayList<>():null;
    record Growth(long generation,String stage,int[] before,int[] after,int[] required) { }
    synchronized List<Growth> growthDiagnostics(){return growths==null?List.of():List.copyOf(growths);}
    private final GasConfiguration gasConfiguration;
    private final VulkanGranularStateLayout.Configuration granularConfiguration;
    private final VulkanRegionThermal.Configuration thermalConfiguration;

    record MechanicalDefinitions(int stockCapacity,int speciesCapacity,int limbCapacity,int scratchLimbsPerWindow,
                                 int quantumExponent,int[] materialPhases,long[] speciesMasses,
                                 double[] materialReferences,double[] speciesReferences,int speciesStride,VulkanLiquidThermodynamics.Tables thermal,
                                 VulkanLiquidViscosity.Tables viscosity,VulkanLiquidForces.Tables forces,VulkanSurfaceTension.Tables surface,boolean noSlip) {
        MechanicalDefinitions {
            materialPhases=materialPhases.clone();speciesMasses=speciesMasses.clone();
            materialReferences=materialReferences.clone();speciesReferences=speciesReferences.clone();
            if(stockCapacity<0||speciesCapacity<0||limbCapacity<0||scratchLimbsPerWindow<1
                    ||quantumExponent < -50||quantumExponent > -24||speciesStride<1
                    ||materialPhases.length==0||materialPhases.length!=materialReferences.length
                    ||speciesMasses.length<speciesStride||speciesReferences.length!=materialPhases.length*speciesStride
                    ||thermal==null||thermal.referenceTemperature().length!=materialPhases.length||viscosity==null||forces==null||surface==null
                    ||viscosity.offsets().length!=materialPhases.length+1||forces.referenceTemperatures().length!=materialPhases.length||surface.materialPhases().length!=materialPhases.length)
                throw new IllegalArgumentException("Invalid resident mechanical definitions or reserves");
        }
        static MechanicalDefinitions fromRegistries(mmm.fluid.FluidMaterialRegistry fluid,mmm.fluid.MaterialRegistry constitutive,mmm.chemistry.ChemicalRegistry chemistry,
                mmm.thermal.ThermalRegistry thermal,int stockCapacity,int speciesCapacity,int limbCapacity,int scratchLimbsPerWindow,int quantumExponent,boolean noSlip) {
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
            return new MechanicalDefinitions(stockCapacity,speciesCapacity,limbCapacity,scratchLimbsPerWindow,quantumExponent,phases,masses,reference,offsets,stride,VulkanLiquidThermodynamics.tables(fluid,thermal),VulkanLiquidViscosity.tables(fluid,constitutive),VulkanLiquidForces.tables(fluid,constitutive),VulkanSurfaceTension.tables(fluid),noSlip);
        }

        MechanicalDefinitions capacities(int stocks,int species,int limbs,int windowLimbs) {
            return new MechanicalDefinitions(stocks,species,limbs,windowLimbs,quantumExponent,materialPhases,speciesMasses,materialReferences,speciesReferences,speciesStride,thermal,viscosity,forces,surface,noSlip);
        }
    }
    record GasConfiguration(VulkanGasStateLayout.State initial,VulkanGasThermodynamics.Tables tables,
                            int nodeCapacity,int componentCapacity,int speciesCapacity,double cellSize,boolean sealedExterior,
                            VulkanGasRemap.AmbientTables ambient) {
        static GasConfiguration forState(VulkanGasStateLayout.State initial,VulkanGasThermodynamics.Tables tables,double cellSize,
                                         boolean sealedExterior,VulkanGasRemap.AmbientTables ambient) {
            var exact=VulkanGasStateLayout.forState(initial.snapshot(),8);
            return new GasConfiguration(initial,tables,reserve(exact.nodeCapacity()),reserve(exact.componentCapacity()),reserve(exact.speciesCapacity()),cellSize,sealedExterior,ambient);
        }
        private static int reserve(int actual){return Math.multiplyExact(Math.max(1,actual),2);}
        GasConfiguration {
            java.util.Objects.requireNonNull(initial);java.util.Objects.requireNonNull(tables);java.util.Objects.requireNonNull(ambient);
            if(nodeCapacity<1||nodeCapacity<initial.snapshot().nodes().size()||componentCapacity<0||speciesCapacity<0
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
        this(context,kernel,projection,pressure,multigrid,inventory,momentum,fixed,occupiedSolid,definitions,gasConfiguration,null);
    }
    VulkanLiquidRegion(VulkanDeviceContext context,VulkanLiquidGeometry kernel,VulkanLiquidProjection projection,
                       VulkanPressureKernel pressure,VulkanPressureMultigrid multigrid,DeviceLiquidInventory inventory,
                       MacMomentumState momentum,FixedGeometrySnapshot fixed,long[] occupiedSolid,MechanicalDefinitions definitions,GasConfiguration gasConfiguration,
                       VulkanGranularStateLayout.Configuration granularConfiguration){
        this(context,kernel,projection,pressure,multigrid,inventory,momentum,fixed,occupiedSolid,definitions,gasConfiguration,granularConfiguration,null);
    }
    VulkanLiquidRegion(VulkanDeviceContext context,VulkanLiquidGeometry kernel,VulkanLiquidProjection projection,
                       VulkanPressureKernel pressure,VulkanPressureMultigrid multigrid,DeviceLiquidInventory inventory,
                       MacMomentumState momentum,FixedGeometrySnapshot fixed,long[] occupiedSolid,MechanicalDefinitions definitions,GasConfiguration gasConfiguration,
                       VulkanGranularStateLayout.Configuration granularConfiguration,VulkanRegionThermal.Configuration thermalConfiguration){
        if(gasConfiguration!=null&&definitions==null)throw new IllegalArgumentException("Gas requires mechanical constitutive definitions");
        if(granularConfiguration!=null&&(gasConfiguration==null||gasConfiguration.cellSize()!=.5))
            throw new IllegalArgumentException("Resident granular SVQ requires the finite-gas configuration and 0.5 m cells");
        if(thermalConfiguration!=null&&(definitions==null||thermalConfiguration.bathTemperatures().length!=0&&thermalConfiguration.bathTemperatures().length!=6*occupiedSolid.length))
            throw new IllegalArgumentException("Thermal configuration requires constitutive definitions and cell-face bath temperatures");
        this.context=context;this.gasConfiguration=gasConfiguration;this.granularConfiguration=granularConfiguration;this.thermalConfiguration=thermalConfiguration;
        this.kernel=kernel;this.projection=projection;this.pressure=pressure;this.multigrid=multigrid;
        maximumRange=context.maxStorageBufferRange();
        var liquid=definitions==null?VulkanLiquidStateLayout.forState(inventory,momentum,context.storageOffsetAlignment())
                :new VulkanLiquidStateLayout(momentum.sizeX(),momentum.sizeY(),momentum.sizeZ(),definitions.stockCapacity(),
                        definitions.speciesCapacity(),definitions.limbCapacity(),context.storageOffsetAlignment());
        if(!fixed.matchesShape(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ())||occupiedSolid.length!=liquid.cellCount())
            throw new IllegalArgumentException("Resident liquid geometry differs from its grid");
        workspace=new Workspace(liquid,definitions);
        kernels=definitions==null?null:new Kernels(context,gasConfiguration!=null,granularConfiguration!=null,thermalConfiguration!=null);
        VulkanRegionStorage created=null;
        try {
            created=new VulkanRegionStorage(context,workspace.bytes,1024*1024);
            created.initialize(workspace.bytes,0,writer->initialize(writer,inventory,momentum,fixed,occupiedSolid));
        }catch(RuntimeException|Error failure) {
            if(created!=null)try{created.close();}catch(RuntimeException|Error closing){failure.addSuppressed(closing);}
            if(kernels!=null)try{kernels.close();}catch(RuntimeException|Error closing){failure.addSuppressed(closing);}
            throw failure;
        }
        storage=created;
    }
    private void initialize(VulkanRegionStorage.Import writer,DeviceLiquidInventory inventory,MacMomentumState momentum,
                            FixedGeometrySnapshot fixed,long[] occupiedSolid) {
        var l=workspace.liquid;
        if(inventory.cellCount()!=l.cellCount()||inventory.stockCount()>l.stockCapacity()||inventory.speciesHandles().length>l.speciesCapacity()||inventory.volumes().limbs().length>l.limbCapacity())
            throw new IllegalArgumentException("Liquid import exceeds resident capacity");
        var v=inventory.volumes();
        writer.put(l.column(COUNTS),new int[]{inventory.stockCount(),inventory.speciesHandles().length,v.limbs().length});
        writer.put(l.column(CELL_OFFSETS),inventory.cellOffsets());writer.put(l.column(MATERIALS),inventory.materials());writer.put(l.column(SUPPORT_MASKS),inventory.supportMasks());
        writer.put(l.column(VOLUME_OFFSETS),v.offsets());writer.put(l.column(VOLUME_SIGNS),v.signs());writer.put(l.column(VOLUME_EXPONENTS),v.exponents());writer.put(l.column(VOLUME_LIMBS),v.limbs());
        writer.put(l.column(MASS_UNITS),inventory.massUnits());writer.put(l.column(ENERGY_BITS),inventory.energyBits());writer.put(l.column(MIXTURE_PRESENT),inventory.mixturePresent());
        writer.put(l.column(SPECIES_OFFSETS),inventory.speciesOffsets());writer.put(l.column(SPECIES_HANDLES),inventory.speciesHandles());writer.put(l.column(SPECIES_AMOUNTS),inventory.speciesAmounts());
        writer.put(l.column(MASS_RESIDUAL_UNITS),inventory.massResidualUnits());
        double[] numeric=new double[inventory.stockCount()];for(int i=0;i<numeric.length;i++)numeric[i]=v.valueAt(i).toDouble();writer.put(l.column(NUMERIC_VOLUMES),numeric);
        writer.put(l.column(MOMENTUM_X),momentum.copyX());writer.put(l.column(MOMENTUM_Y),momentum.copyY());writer.put(l.column(MOMENTUM_Z),momentum.copyZ());
        int[] masks=new int[l.cellCount()];
        for(int cell=0;cell<masks.length;cell++) {
            masks[cell]=fixed.mask(cell);long capacity=(long)Integer.bitCount(~masks[cell]&255)<<21;
            if(occupiedSolid[cell]<0||occupiedSolid[cell]>capacity)throw new IllegalArgumentException("Solid inventory exceeds fixed open capacity");
        }
        writer.put(workspace.geometry.get(GeometryField.FIXED_MASKS),masks);writer.put(workspace.geometry.get(GeometryField.OCCUPIED_SOLID),occupiedSolid);
        if(workspace.transport!=null)workspace.transport.initialize(writer);
    }
    private static void thermalTables(VulkanRegionStorage.Import writer,VulkanLiquidThermodynamics.Layout layout) {
        var t=layout.tables();writer.put(layout.offsets(),t.offsets());writer.put(layout.upper(),t.upper());writer.put(layout.capacity(),t.capacity());
        writer.put(layout.referenceTemperature(),t.referenceTemperature());writer.put(layout.coldSensible(),t.coldSensible());
    }
    private static void gasTables(VulkanRegionStorage.Import writer,VulkanGasThermodynamics.Layout layout) {
        for(var chunk:layout.tableChunks())writer.put(chunk.offset(),chunk.bytes());
    }
    private static void remapTables(VulkanRegionStorage.Import writer,VulkanGasRemap.Layout layout) {
        for(var chunk:layout.tableChunks())writer.put(chunk.offset(),chunk.bytes());
    }
    long rebuildGeometry(){return advance(null,false,0);}
    long prepareMechanicalFields(VulkanLiquidProjection.Parameters parameters){return advance(parameters,false,0);}
    long prepareMechanicalFields(VulkanLiquidProjection.Parameters parameters,boolean zeroInitialPressure){return advance(parameters,zeroInitialPressure,0);}
    long advanceMechanical(VulkanLiquidProjection.Parameters parameters,int maximumCapacityRounds) {
        java.util.Objects.requireNonNull(parameters,"Mechanical parameters");
        if(kernels==null)throw new IllegalStateException("Mechanical transport requires explicit constitutive definitions");
        if(maximumCapacityRounds<1)throw new IllegalArgumentException("Capacity round limit must be positive");
        return advance(parameters,false,maximumCapacityRounds);
    }
    private synchronized long advance(VulkanLiquidProjection.Parameters parameters,boolean zeroInitialPressure,int capacityRounds) {
        var allocated=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Workspace,Boolean>());allocated.add(workspace);
        try(var original=storage.capture();var result=prepareInterval(original,workspace,parameters,zeroInitialPressure,capacityRounds,
                original.generation()+1,1,0,false,new long[1],allocated)){
            long generation=result.candidate.commit(original);workspace=result.workspace;return generation;
        }finally{for(var value:allocated)if(value!=workspace)value.templates.close();}
    }
    private record PreparedInterval(Workspace workspace,VulkanRegionStorage.Candidate candidate) implements AutoCloseable{
        @Override public void close(){candidate.close();}
    }
    private static final class MotionTimeRefinement extends IllegalStateException{MotionTimeRefinement(String reason){super(reason);}}
    private static final class ContactCapacityGrowth extends IllegalStateException{final int required;ContactCapacityGrowth(int required){this.required=required;}}
    private PreparedInterval prepareInterval(VulkanRegionStorage.Capture capture,Workspace source,VulkanLiquidProjection.Parameters parameters,
            boolean zeroInitialPressure,int capacityRounds,long requestGeneration,long ordinal,int depth,boolean previous,
            long[] spent,java.util.Set<Workspace> allocated){
        Workspace target=source;
        while(true){
            if(++spent[0]>mmm.fluid.CpuFluidSolverBackend.MAXIMUM_CUMULATIVE_SUBSTEPS)
                throw new mmm.fluid.FluidSolverBackend.MechanicalWorkBudgetExceededException("Resident mechanical request exceeded cumulative substep budget");
            var candidate=storage.prepareReallocated(capture,target.bytes);int[] required=null;int contactRequired=0,thermalRequired=0;
            try{
                long sequence=ordinal==1?requestGeneration:Long.rotateLeft(requestGeneration,17)^ordinal;
                int error=target.execute(source,candidate,parameters,zeroInitialPressure,capacityRounds,sequence,previous);
                if(error==0)return new PreparedInterval(target,candidate);
                if(error==64&&target.transport!=null)required=target.transport.requirements(candidate);
                else throw target.failure(candidate,error,parameters);
            }catch(ContactCapacityGrowth growth){contactRequired=growth.required;}
            catch(VulkanRegionThermal.ContactCapacityGrowth growth){thermalRequired=growth.required;}
            catch(MotionTimeRefinement finer){
                candidate.close();
                if(parameters==null||depth>=mmm.fluid.CpuFluidSolverBackend.MAXIMUM_TIME_REFINEMENTS)throw finer;
                float half=parameters.duration()*.5f;if(!(half>0&&half<parameters.duration()))throw finer;
                var p=new VulkanLiquidProjection.Parameters(parameters.cellSize(),half,parameters.gravity(),parameters.tolerance(),parameters.iterations());
                try(var first=prepareInterval(capture,source,p,zeroInitialPressure,capacityRounds,requestGeneration,2*ordinal,depth+1,previous,spent,allocated);
                    var intermediate=first.candidate.captureCompleted()){
                    var second=new VulkanLiquidProjection.Parameters(parameters.cellSize(),parameters.duration()-half,parameters.gravity(),parameters.tolerance(),parameters.iterations());
                    return prepareInterval(intermediate,first.workspace,second,zeroInitialPressure,capacityRounds,requestGeneration,2*ordinal+1,depth+1,true,spent,allocated);
                }
            }catch(RuntimeException|Error failure){candidate.close();throw failure;}
            candidate.close();Workspace larger=thermalRequired>0?target.growThermalContacts(thermalRequired):contactRequired>0?target.growContacts(contactRequired):target.grow(required);allocated.add(larger);
            if(growths!=null)growths.add(new Growth(requestGeneration,target.capacityStage,target.capacities(),larger.capacities(),thermalRequired>0?new int[]{thermalRequired}:contactRequired>0?new int[]{contactRequired}:required.clone()));
            target=larger;
        }
    }
    synchronized VulkanGranularStateLayout.State checkpointGranular(){
        if(workspace.transport==null||workspace.transport.granular==null)throw new IllegalStateException("No resident granular inventory");
        var state=workspace.transport.granular.state;try(var capture=storage.capture()){
            var data=ByteBuffer.allocate(Math.toIntExact(state.bytes()));storage.read(capture,state.baseOffset(),data);return state.exportState(data.flip());}
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
    synchronized int[] gasTopologyDiagnostics() {
        if(workspace.transport==null||workspace.transport.successor==null)return new int[0];
        var t=workspace.transport;int[] result=new int[3];
        try(var capture=storage.capture()) {
            long[] offsets={t.gas.geometry.componentCount().offset(),t.successor.geometry.componentCount().offset(),t.successor.lineage.requiredGroupsOffset()};
            for(int i=0;i<offsets.length;i++){var data=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);storage.read(capture,offsets[i],data);result[i]=data.getInt(0);}
        }return result;
    }
    synchronized int[] gasInventoryCounts() {
        if(workspace.transport==null||workspace.transport.gas==null)return new int[0];
        try(var capture=storage.capture()) {
            var output=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            storage.read(capture,workspace.transport.gas.state.column(VulkanGasStateLayout.Field.COUNTS).offset(),output);
            return new int[]{output.getInt(0),output.getInt(4),output.getInt(8)};
        }
    }
    synchronized double[] gasFlowDiagnostics(){
        if(workspace.transport==null||workspace.transport.aperture==null)return new double[0];var aperture=workspace.transport.aperture;
        try(var capture=storage.capture()){
            var pairs=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);storage.read(capture,aperture.flow.requiredPairsOffset(),pairs);
            var result=new double[6];result[0]=pairs.getInt(0);
            if(result[0]>0){var control=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);storage.read(capture,aperture.flow.column(VulkanGasFlow.Field.CONTROL).offset(),control);
                for(int i=0;i<3;i++)result[i+1]=control.getDouble(8*i);}
            var audit=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);storage.read(capture,aperture.remap.column(VulkanGasRemap.Field.AUDIT_ENERGY).offset(),audit);
            result[4]=audit.getDouble(0);result[5]=audit.getDouble(8);return result;
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
    private record GasReserves(int nodes,int components,int species,int parts,int partSpecies,int groups,int pairs) { }
    private record HeaderWrite(long offset,ByteBuffer bytes) { }
    private static long headerBytes(List<HeaderWrite> headers){return headers.stream().mapToLong(h->h.bytes().remaining()).sum();}

    private final class Workspace {
        final VulkanLiquidStateLayout liquid;
        final VulkanCommandTemplates templates=new VulkanCommandTemplates(context);
        final EnumMap<GeometryField,VulkanLiquidStateLayout.Column> geometry=new EnumMap<>(GeometryField.class);
        final EnumMap<PressureField,VulkanLiquidStateLayout.Column> pressureColumns=new EnumMap<>(PressureField.class);
        final VulkanPressureMultigrid.Layout hierarchy;
        final VulkanLiquidStateLayout.Column multigridColumn;
        final Transport transport;
        final long bytes;
        final GasReserves gasReserves;
        final int contactCapacity;
        final int thermalContactCapacity;
        String capacityStage="source geometry";
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions) {
            this(liquid,definitions,gasConfiguration==null?null:new GasReserves(gasConfiguration.nodeCapacity(),gasConfiguration.componentCapacity(),gasConfiguration.speciesCapacity(),
                    Math.multiplyExact(gasConfiguration.componentCapacity(),7),Math.multiplyExact(gasConfiguration.speciesCapacity(),7),Math.min(Math.multiplyExact(liquid.cellCount(),7),Math.multiplyExact(Math.addExact(gasConfiguration.nodeCapacity(),2),7)),Math.max(1,gasConfiguration.nodeCapacity())));
        }
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves) {
            this(liquid,definitions,gasReserves,granularConfiguration==null?0:granularConfiguration.contactCapacity());
        }
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity) {
            this(liquid,definitions,gasReserves,contactCapacity,thermalConfiguration==null?0:thermalConfiguration.contactCapacity());
        }
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity,int thermalContactCapacity) {
            this.liquid=liquid;this.gasReserves=gasReserves;this.contactCapacity=contactCapacity;this.thermalContactCapacity=thermalContactCapacity;
        int cells = liquid.cellCount(),rows=Math.addExact(Math.addExact(cells,gasReserves==null?0:gasReserves.nodes()),
                granularConfiguration==null?0:Math.multiplyExact(3,granularConfiguration.initial().particles().size()));
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
                    boolean zeroInitialPressure,int capacityRounds,long step,boolean accumulateLedger) {
            boolean migrating=source!=this;
            var sourceHeaders=migrating?transport.headers(source.liquid):List.<HeaderWrite>of();
            var stableHeaders=migrating?transport.headers(liquid):List.<HeaderWrite>of();
            long uploadedBytes=headerBytes(sourceHeaders);
            var gas=transport==null?null:transport.gas;
            int pressureRows=pressureColumns.get(PressureField.ROWS).count();
            VulkanPressureKernel.PressureOperator gasOperator=transport!=null&&transport.dynamics!=null?
                    new VulkanCoupledPressure.Operator(kernels.coupledKernel,transport.dynamics.coupled):gas==null?null:new VulkanPressureKernel.GasOperator(kernels.gasPressure,gas.operator);
            VulkanRegionStorage.ComputeCommands prepare = (command, input, output, errors, staging, stack) -> {
                if(gas==null&&kernels!=null&&kernels.timings!=null)kernels.timings.reset(command);
                if(gas==null) {
                    if(migrating)migrate(command,source,input,output,stack,sourceHeaders);else copyPersistent(command,input,output,stack);
                }
                kernel.rebindRanges(geometryRanges(output,errors,geometry.get(GeometryField.FACES)));
                int sx=liquid.sizeX(),sy=liquid.sizeY(),sz=liquid.sizeZ();
                if(gas==null)kernel.record(command, sx, sy, sz);
                else {
                    kernels.timed(command,"old-gas-components",()->kernels.gasGeometry.recordComponents(command,gas.geometry));
                    kernels.timed(command,"old-gas-volumes",()->kernels.gasThermo.recordVolumes(command,gas.thermo,gas.geometry));
                    kernels.timed(command,"old-gas-eos",()->kernels.gasThermo.record(command,gas.thermo));
                }
                if(parameters!=null) {
                    if(transport!=null) {
                        var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                        kernels.liquidThermalKernel.rebindRanges(dst,err);kernels.viscosityKernel.rebindRanges(src,dst,err);kernels.forceKernel.rebindRanges(dst,err);kernels.surfaceKernel.rebindRanges(dst,err);
                        kernels.timed(command,"source-thermal",()->kernels.liquidThermalKernel.record(command,transport.sourceThermal));
                        kernels.timed(command,"viscosity",()->kernels.viscosityKernel.record(command,transport.viscosity,parameters.cellSize(),parameters.duration()));
                        kernels.timed(command,"buoyancy",()->kernels.forceKernel.recordThermalBuoyancy(command,transport.forces,parameters.duration(),parameters.gravity()));
                        kernels.timed(command,"surface-tension",()->kernels.surfaceKernel.record(command,transport.surface,parameters.cellSize(),parameters.duration()));
                        if(transport.dynamics!=null)kernels.granularPressureKernel.recordFaces(command,transport.dynamics.pressure,parameters.cellSize(),parameters.duration(),parameters.gravity());
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
                    var assembly = new ArrayList<FlipKernelBindings.BufferRange>(10);
                    assembly.add(range(output, GeometryField.CELLS));
                    assembly.add(range(output, GeometryField.FACES));
                    for (PressureField field : new PressureField[]{PressureField.STARTS,PressureField.COLUMNS,PressureField.VALUES,
                            PressureField.ROWS,PressureField.META,PressureField.PROBLEM,PressureField.PRESSURE})
                        assembly.add(range(output,field));
                    assembly.add(new FlipKernelBindings.BufferRange(errors));
                    projection.rebindRanges(assembly);
                    projection.prepare(command,sx,sy,sz,parameters,gas==null?null:kernels.gasPressure,gas==null?null:gas.operator);
                    if(transport!=null&&transport.dynamics!=null){kernels.granularPressureKernel.recordIncidence(command,transport.dynamics.pressure,parameters.cellSize(),parameters.duration(),parameters.gravity());
                        kernels.coupledKernel.recordPrepare(command,transport.dynamics.coupled,parameters.duration());}
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
                    recordPressureIterations(command,output,errors,parameters.iterations(),Math.min(16,parameters.iterations()),gasOperator);
                }
            };
            long[] stageMark=Boolean.getBoolean("mmm.gpuMechanicalStages")?new long[]{storage.metrics().gpuNanos(),System.nanoTime()}:null;
            int error;
            if(gas!=null) {
                capacityStage="source gas geometry/EOS";
                if(parameters!=null&&Double.compare(parameters.cellSize(),gasConfiguration.cellSize())!=0)throw new IllegalArgumentException("Gas and mechanical cell sizes differ");
                var pre=candidate.beginBatch(uploadedBytes,(command,input,output,errors,staging,stack)-> {
                    if(kernels.timings!=null)kernels.timings.reset(command);
                    if(migrating)migrate(command,source,input,output,stack,sourceHeaders);else copyPersistent(command,input,output,stack);
                    if(transport.granular!=null){transport.bindGranular(output,errors);kernels.granularGeometryKernel.recordOccupancy(command,transport.granular.geometry);}
                    kernel.rebindRanges(geometryRanges(output,errors,geometry.get(GeometryField.FACES)));
                    kernel.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                    var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                    kernels.volumeKernel.rebindRanges(src,dst,err);kernels.volumeKernel.record(command,transport.volumes);
                    kernels.gasGeometry.rebindRanges(dst,err);kernels.gasThermo.rebindRanges(dst,err);kernels.gasPressure.rebindRanges(dst,err);
                    kernels.timed(command,"old-gas-geometry",()->kernels.gasGeometry.recordGeometry(command,gas.geometry));kernels.gasGeometry.recordConnectivity(command,gas.geometry,4);
                },gas.geometry.changed().offset(),4);
                int connectivityRounds=4;
                while(pre.error()==0&&pre.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0)!=0) {
                    if(connectivityRounds>=liquid.cellCount())throw new IllegalStateException("Gas connectivity did not converge");
                    pre=candidate.continueBatch((command,input,output,errors,staging,stack)->kernels.gasGeometry.recordConnectivity(command,gas.geometry,4),gas.geometry.changed().offset(),4);connectivityRounds+=4;
                }
                if(pre.error()!=0)return pre.error();
            }
            traceStage("source-gas-connectivity",stageMark);
            if (parameters == null) error = gas==null?candidate.execute(uploadedBytes, prepare):candidate.finishBatch(prepare);
            else {
                capacityStage="common pressure";
                long runOffset = pressureColumns.get(PressureField.CONTROL).offset() + 24;
                var result = gas==null?candidate.beginBatch(uploadedBytes,prepare,runOffset,8):candidate.continueBatch(prepare,runOffset,8);
                int scheduled = Math.min(16, parameters.iterations());
                while (result.error() == 0 && result.feedback().getDouble(0) != 0.0 && scheduled < parameters.iterations()) {
                    int batch = Math.min(16, parameters.iterations() - scheduled);
                    result = candidate.continueBatch((command, input, output, errors, staging, stack) ->
                            recordPressureIterations(command,output,errors,parameters.iterations(),batch,gasOperator), runOffset, 8);
                    scheduled += batch;
                }
                error = result.error();traceStage("pressure",stageMark);
                if(error==0) {
                    VulkanRegionStorage.ComputeCommands project=(command,input,output,errors,staging,stack)-> {
                        pressure.recordFinish(command,pressureRows,pressureRows,parameters.iterations(),multigrid,hierarchy,gasOperator);
                        if(transport==null||transport.dynamics==null)projection.project(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),parameters,gas==null?null:kernels.gasPressure,gas==null?null:gas.operator);
                        else{
                            kernels.coupledKernel.recordProject(command,transport.dynamics.coupled,parameters.duration());
                            kernels.granularPressureKernel.recordProjectedVelocities(command,transport.dynamics.pressure,parameters.cellSize(),parameters.duration(),parameters.gravity());
                            kernels.coupledKernel.recordDivergence(command,transport.dynamics.coupled);kernels.gasPressure.recordObserve(command,gas.operator,parameters);
                            kernels.granularPressureKernel.recordObserveGas(command,transport.dynamics.pressure,parameters.cellSize(),parameters.duration(),parameters.gravity());
                        }
                        kernel.recordTransportPreferences(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),parameters.cellSize(),parameters.duration());
                    };
                    if(capacityRounds==0)error=candidate.finishBatch(project);
                    else {
                        if(transport.dynamics!=null){
                            capacityStage="granular contact/motion";
                            var moved=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                                project.record(command,input,output,errors,staging,stack);transport.recordMotion(command,parameters);
                            },transport.dynamics.contacts.column(VulkanGranularContacts.Field.CONTROL).offset(),12);
                            if(moved.error()!=0)return moved.error();var contactState=moved.feedback().order(ByteOrder.LITTLE_ENDIAN);
                            if(contactState.getInt(4)>contactCapacity)throw new ContactCapacityGrowth(contactState.getInt(4));
                            if(contactState.getInt(8)!=0)throw new MotionTimeRefinement("Granular trial requests time refinement: flags="+contactState.getInt(8));
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
                        error=capacityResult.error();traceStage("project-capacity",stageMark);
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
                            var body=candidate.continueBatch((command,input,output,errors,staging,stack)->
                                    transport.recordFinish(command,parameters.gravity()>0?-1:parameters.gravity()<0?1:0),transport.bodies.changed().offset(),4);
                            int bodyRounds=4;
                            while(body.error()==0&&body.feedback().order(ByteOrder.LITTLE_ENDIAN).getInt(0)!=0) {
                                if(bodyRounds>=transport.bodies.vertices())throw new IllegalStateException("Mechanical connectivity did not converge");
                                body=candidate.continueBatch((command,input,output,errors,staging,stack)->kernels.bodiesKernel.recordConnectivity(command,transport.bodies,4),transport.bodies.changed().offset(),4);bodyRounds+=4;
                            }
                            error=body.error();
                            if(error==0&&transport.successor!=null) {
                                var successor=transport.successor;var changed=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);candidate.read(successor.geometry.changed().offset(),changed);
                                int roundsFuture=4;
                                while(changed.getInt(0)!=0&&error==0) {
                                    if(roundsFuture>=liquid.cellCount())throw new IllegalStateException("Future gas connectivity did not converge");
                                    var next=candidate.continueBatch((command,input,output,errors,staging,stack)->kernels.futureGasGeometry.recordConnectivity(command,successor.geometry,4),successor.geometry.changed().offset(),4);
                                    error=next.error();changed=next.feedback().order(ByteOrder.LITTLE_ENDIAN);roundsFuture+=4;
                                }
                            }
                            traceStage("transport-momentum-future-geometry-bodies",stageMark);if(error==0)capacityStage="gas lineage/remap/common energy";
                            VulkanRegionStorage.ComputeCommands finishTransport=(command,input,output,errors,staging,stack)-> {
                                transport.recordEnergy(command,parameters);
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
                                error=candidate.continueBatch(finishTransport,0,0).error();
                                if(error==0&&transport.aperture!=null)error=transport.aperture.execute(candidate,parameters.duration(),step);
                                if(error==0&&transport.thermal!=null){capacityStage="thermal contacts/conduction";error=kernels.thermalKernel.execute(candidate,transport.thermal,parameters.duration());}
                                if(error==0)error=candidate.finishBatch(migrating?headerBytes(stableHeaders):0,(command,input,output,errors,staging,stack)-> {
                                    transport.recordLedgers(command,accumulateLedger?source.transport:null);if(migrating)writeHeaders(command,output,stableHeaders,stack);
                                });
                            }
                        }
                    }
                }
            }
            traceStage("gas-remap-common-energy-ledgers",stageMark);
            if(error==0&&kernels!=null&&kernels.timings!=null)for(var sample:kernels.timings.read())System.out.printf("mechanical_kernel=%s gpu_ms=%.3f%n",sample.stage(),sample.milliseconds());return error;
        }
        void recordPressureIterations(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,int maximum,int count,VulkanPressureKernel.PressureOperator gasOperator) {
            int rows=pressureColumns.get(PressureField.ROWS).count();
            templates.record(command,new VulkanCommandTemplates.Key(0,output,errors,count,maximum),
                    secondary->pressure.recordIterations(secondary,rows,rows,maximum,multigrid,hierarchy,count,gasOperator));
        }
        void recordCapacityIterations(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,int count,int phase) {
            templates.record(command,new VulkanCommandTemplates.Key(1,output,errors,count,phase),
                    secondary->{kernels.capacityKernel.recordIterations(secondary,transport.capacitySolve,count,phase);});
        }
        private void traceStage(String name,long[] mark){if(mark==null)return;long gpu=storage.metrics().gpuNanos(),now=System.nanoTime();
            System.out.printf("mechanical_stage=%s gpu_ms=%.3f wall_ms=%.3f%n",name,(gpu-mark[0])*1e-6,(now-mark[1])*1e-6);mark[0]=gpu;mark[1]=now;}
        IllegalStateException failure(VulkanRegionStorage.Candidate candidate,int error,VulkanLiquidProjection.Parameters parameters) {
            if((error&4)!=0&&parameters!=null) {
                boolean aperture=capacityStage.startsWith("gas aperture");long offset=aperture?transport.aperture.flow.column(VulkanGasFlow.Field.CONTROL).offset():pressureColumns.get(PressureField.CONTROL).offset();
                var diagnostics=ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);candidate.read(offset,diagnostics);
                return new IllegalStateException("Resident "+(aperture?"gas aperture":"liquid")+" pressure failed: iterations="+(int)diagnostics.getDouble(0)+", residualRms="+diagnostics.getDouble(8)
                        +", target="+(aperture?1e-8:parameters.tolerance())+", status="+error);
            }
            return new IllegalStateException("Resident liquid candidate failed during "+capacityStage+": status="+error);
        }
        int[] capacities(){return new int[]{liquid.stockCapacity(),liquid.speciesCapacity(),liquid.limbCapacity(),transport.definitions.scratchLimbsPerWindow(),
                gasReserves==null?0:gasReserves.nodes(),gasReserves==null?0:gasReserves.components(),gasReserves==null?0:gasReserves.species(),
                gasReserves==null?0:gasReserves.parts(),gasReserves==null?0:gasReserves.partSpecies(),gasReserves==null?0:gasReserves.groups(),gasReserves==null?0:gasReserves.pairs()};}
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
            GasReserves nextGas=gasReserves;
            if(gasReserves!=null) {
                int flowComponents=required.length>19?Math.max(required[21],required[23]):0,flowSpecies=required.length>19?Math.max(required[22],required[24]):0;
                nextGas=new GasReserves(growGas(gasReserves.nodes(),Math.max(required[8],required[9])),
                        growGas(gasReserves.components(),Math.max(flowComponents,Math.max(required[10],required[12]))),growGas(gasReserves.species(),Math.max(flowSpecies,Math.max(required[11],required[13]))),
                        growGas(gasReserves.parts(),Math.max(required[14],required.length>19?required[19]:0)),growGas(gasReserves.partSpecies(),Math.max(required[15],required.length>19?required[20]:0)),growGas(gasReserves.groups(),required[17]),
                        growGas(gasReserves.pairs(),required.length>18?required[18]:0));
                limbs=growCount(limbs,Math.max(0,required[16]-cells));
            }
            if(stocks==liquid.stockCapacity()&&species==liquid.speciesCapacity()&&limbs==liquid.limbCapacity()&&windowLimbs==definitions.scratchLimbsPerWindow()&&java.util.Objects.equals(nextGas,gasReserves))
                throw new IllegalStateException("Device capacity error 64 after "+capacityStage+" reported no larger CSR requirement: "+java.util.Arrays.toString(required));
            return new Workspace(new VulkanLiquidStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),stocks,species,limbs,liquid.alignment()),
                    definitions.capacities(stocks,species,limbs,windowLimbs),nextGas,contactCapacity,thermalContactCapacity);
        }
        Workspace growContacts(int required){return new Workspace(liquid,transport.definitions,gasReserves,growCount(contactCapacity,required),thermalContactCapacity);}
        Workspace growThermalContacts(int required){return new Workspace(liquid,transport.definitions,gasReserves,contactCapacity,growCount(thermalContactCapacity,required));}
        /** Candidate scratch is produced by its kernels. Persist inventory, constitutive tables, headers and warm caches only. */
        void copyPersistent(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack) {
            liquid.copyColumns(command,liquid,input,output,stack);
            for(var field:new GeometryField[]{GeometryField.FIXED_MASKS,GeometryField.OCCUPIED_SOLID})copyColumn(command,input,geometry.get(field),output,geometry.get(field),stack);
            var warm=pressureColumns.get(PressureField.PRESSURE);copyColumn(command,input,warm,output,warm,stack);
            if(transport!=null) {
                for(var column:transport.persistentColumns())copyColumn(command,input,column,output,column,stack);
                if(transport.gas!=null)transport.gas.state.copyColumns(command,input,transport.gas.state,output,stack);
                if(transport.granular!=null)transport.granular.state.copyColumns(command,input,transport.granular.state,output,stack);
            }
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
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
            var oldForce=from.forceColumns();var newForce=to.forceColumns();
            for(int i=0;i<oldForce.size();i++)if(!from.forceHeaders().contains(oldForce.get(i)))copyColumn(command,input,oldForce.get(i),output,newForce.get(i),stack);
            if(to.gas!=null)to.gas.migrate(command,from.gas,input,output,stack);
            if(to.successor!=null)to.successor.migrate(command,from.successor,input,output,stack);
            if(to.granular!=null)to.granular.migrate(command,from.granular,input,output,stack);
            if(to.aperture!=null)to.aperture.migrate(command,from.aperture,input,output,stack);
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
    private ArrayList<FlipKernelBindings.BufferRange> geometryRanges(GpuBuffer buffer,GpuBuffer errors,VulkanLiquidStateLayout.Column faces) {
        var ranges=new ArrayList<FlipKernelBindings.BufferRange>(14);
        ranges.addAll(liquid.ranges(buffer,CELL_OFFSETS,NUMERIC_VOLUMES,MASS_UNITS));
        ranges.add(range(buffer,GeometryField.FIXED_MASKS));ranges.add(new FlipKernelBindings.BufferRange(buffer,occupied().offset(),occupied().bindingBytes()));
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
        final GranularSection granular;
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
            sourceThermal=VulkanLiquidThermodynamics.layout(liquid,definitions.thermal(),word(materialReference),word(speciesReference),definitions.speciesStride(),volumes.endOffset(),liquid.alignment());
            viscosity=new VulkanLiquidViscosity.Layout(liquid,geometry.get(GeometryField.CELLS),geometry.get(GeometryField.FACES),geometry.get(GeometryField.FIXED_MASKS),word(sourceThermal.cellTemperatures()),-1,
                    definitions.viscosity(),null,definitions.noSlip(),sourceThermal.endOffset(),liquid.alignment());
            forces=VulkanLiquidForces.layout(liquid,definitions.forces(),word(sourceThermal.cellTemperatures()),word(geometry.get(GeometryField.FACES)),-1,viscosity.endOffset(),liquid.alignment());
            surface=VulkanSurfaceTension.layout(liquid,definitions.surface(),word(geometry.get(GeometryField.CELLS)),word(geometry.get(GeometryField.FACES)),word(geometry.get(GeometryField.PLANES)),
                    word(geometry.get(GeometryField.FIXED_MASKS)),-1,forces.endOffset(),liquid.alignment());
            granular=granularConfiguration==null?null:new GranularSection(surface.endOffset());
            gas=gasConfiguration==null?null:new GasSection(volumes,granular==null?surface.endOffset():granular.endOffset,
                    granular==null?word(geometry.get(GeometryField.OCCUPIED_SOLID)):granular.geometry.word(VulkanGranularGeometry.Field.OCCUPIED_SVQ));
            dynamics=granular==null?null:new GranularDynamics(granular,gas,viscosity.column(VulkanLiquidViscosity.Field.MU),gas.endOffset);
            capacity=new VulkanLiquidCapacityLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gas==null?0:gas.state.nodeCapacity(),dynamics!=null?dynamics.endOffset:gas==null?surface.endOffset():gas.endOffset,liquid.alignment());
            capacityInputs=VulkanLiquidCapacityInputs.layout(volumes,capacity,word(geometry.get(GeometryField.FIXED_MASKS)),
                    word(granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ)),word(geometry.get(GeometryField.FACES)),word(geometry.get(GeometryField.FLUXES)),
                    gas==null?null:gas.geometry,gas==null?null:gas.state,capacity.endOffset(),liquid.alignment());
            capacitySolve=new VulkanLiquidCapacity.Layout(capacity,capacityInputs.endOffset(),liquid.alignment());
            accepted=VulkanLiquidAcceptedVolumes.layout(volumes,capacity.word(VulkanLiquidCapacityLayout.Field.ACCEPTED),
                    capacitySolve.endOffset(),liquid.alignment(),Math.addExact(volumes.limbCapacity(),Math.multiplyExact(cells,14)));
            materials=VulkanLiquidMaterialRoutes.layout(liquid,routes,word(accepted.descriptors()),word(phaseTable),-1,
                    Math.multiplyExact(7*stocks+3*cells,definitions.scratchLimbsPerWindow()),accepted.endOffset(),liquid.alignment());
            quantities=VulkanLiquidRouteQuantities.layout(liquid,routes,word(materialReference),word(speciesReference),definitions.speciesStride(),materials.endOffset(),liquid.alignment());
            gather=VulkanLiquidGather.layout(liquid,liquid,routes,word(geometry.get(GeometryField.FIXED_MASKS)),word(massTable),quantities.endOffset(),liquid.alignment());
            heatTransport=VulkanMechanicalHeatTransport.layout(liquid,liquid,routes,word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_HEAT)),gather.endOffset(),liquid.alignment());
            momentum=VulkanLiquidMomentum.layout(liquid,liquid,routes,word(geometry.get(GeometryField.FACES)),word(futureFaces),-1,heatTransport.endOffset(),liquid.alignment());
            liquidThermal=VulkanLiquidThermodynamics.layout(liquid,definitions.thermal(),word(materialReference),word(speciesReference),definitions.speciesStride(),momentum.endOffset(),liquid.alignment());
            successor=gas==null?null:new GasSuccessor(gas,accepted,liquidThermal.endOffset());
            bodies=VulkanMechanicalBodies.layout(liquid,routes,word(geometry.get(GeometryField.FACES)),gas==null?-1:word(gas.geometry.cellNodes()),
                    gas==null?0:gas.state.nodeCapacity(),gas==null?-1:Math.toIntExact(gas.state.column(VulkanGasStateLayout.Field.COUNTS).offset()/4),
                    dynamics==null?null:new VulkanMechanicalBodies.GranularLinks(granular.state,dynamics.pressure,dynamics.contacts.word(VulkanGranularContacts.Field.CONTACT_COMPONENT)),
                    successor==null?liquidThermal.endOffset():successor.endOffset,liquid.alignment());
            energy=VulkanMechanicalEnergy.layout(bodies,momentum,liquidThermal,successor==null?-1:word(successor.work.workBySource()),
                    word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_HEAT)),word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_WORK)),
                    dynamics==null?null:new VulkanMechanicalEnergy.GranularEnergy(granular.thermal,granular.geometry,-1,dynamics.contacts.word(VulkanGranularContacts.Field.PARTICLE_EXTERNAL_WORK)),bodies.endOffset(),liquid.alignment());
            aperture=successor==null?null:new GasAperture(gas,successor,energy.endOffset());
            long thermalBase=aperture==null?energy.endOffset():aperture.endOffset;
            if(thermalConfiguration==null){bathTemperatures=null;thermal=null;}
            else{long[] tail={thermalBase};bathTemperatures=column(tail,thermalConfiguration.bathTemperatures().length,8);
                var inputs=new VulkanThermalContacts.Inputs(liquid,liquidThermal,gas==null?null:gas.state,aperture==null?null:aperture.eos,
                        successor==null?null:successor.geometry,granular==null?null:granular.state,granular==null?null:granular.thermal,
                        word(geometry.get(GeometryField.FIXED_MASKS)),bathTemperatures.count()==0?-1:word(bathTemperatures),-1,thermalConfiguration.cellSize());
                thermal=VulkanRegionThermal.layout(inputs,thermalConfiguration,thermalContactCapacity,tail[0],liquid.alignment());thermalBase=thermal.endOffset();}
            var roundoffStreams=new ArrayList<VulkanEnergyLedger.Stream>(List.of(stream(quantities.energyRemainders(),1),stream(gather.energyRemainders(),-1),
                    stream(momentum.heatPostingRemainders(),1),stream(momentum.heatAllocationRemainders(),1)));
            roundoffStreams.addAll(viscosity.externalWorkRoundoffStreams());roundoffStreams.addAll(heatTransport.roundoffStreams());
            roundoffStreams.addAll(energy.roundoffStreams());if(successor!=null)roundoffStreams.addAll(successor.remap.roundoffStreams());
            if(dynamics!=null)roundoffStreams.addAll(dynamics.contacts.externalWorkRoundoffStreams());
            if(aperture!=null)roundoffStreams.addAll(aperture.remap.roundoffStreams());
            if(thermal!=null)roundoffStreams.addAll(thermal.roundoffStreams());
            roundoff=VulkanEnergyLedger.layout(roundoffStreams,thermalBase,liquid.alignment());
            heat=VulkanEnergyLedger.layout(java.util.List.of(stream(momentum.cellHeat(),1)),roundoff.endOffset(),liquid.alignment());endOffset=heat.endOffset();

        }
        void initialize(VulkanRegionStorage.Import writer) {
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
                var source=gasConfiguration.initial();var counts=VulkanGasStateLayout.forState(source.snapshot(),8);
                var local=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),counts.nodeCapacity(),counts.componentCapacity(),counts.speciesCapacity(),gas.state.definitions(),8);
                var data=ByteBuffer.allocate(Math.toIntExact(local.bytes())).order(ByteOrder.LITTLE_ENDIAN);local.importState(data,source.snapshot(),source.nodeEnergy());
                for(var field:VulkanGasStateLayout.Field.values()){var c=local.column(field);if(c.bytes()>0)writer.put(gas.state.column(field).offset(),data.slice(Math.toIntExact(c.offset()),Math.toIntExact(c.bytes())));}
            }
            if(successor!=null){gasTables(writer,successor.retainedEOS);remapTables(writer,successor.remap);}
            if(aperture!=null){gasTables(writer,aperture.eos);gasTables(writer,aperture.retainedEOS);remapTables(writer,aperture.remap);}
            if(thermal!=null){writer.put(bathTemperatures,thermalConfiguration.bathTemperatures());for(var chunk:thermal.tables())writer.put(chunk.offset(),chunk.bytes());}
            if(granular!=null) {
                var state=granular.state;
                var data=ByteBuffer.allocate(Math.toIntExact(state.bytes())).order(ByteOrder.LITTLE_ENDIAN);state.importState(data,granularConfiguration.initial());
                writer.put(state.baseOffset(),data);thermalTables(writer,granular.thermal);
                writer.put(granular.materialReference,granularConfiguration.materialReferences());writer.put(granular.speciesReference,granularConfiguration.speciesReferences());
                var ids=state.definitions().materialIds();double[] diameters=new double[ids.size()],contact=new double[3*ids.size()];
                for(int i=0;i<ids.size();i++){var m=granularConfiguration.registry().material(granularConfiguration.registry().materialHandle(ids.get(i)));
                    diameters[i]=m.primaryGrainDiameterMeters();contact[3*i]=m.restitution();contact[3*i+1]=m.staticFriction();contact[3*i+2]=m.dynamicFriction();}
                writer.put(dynamics.pressure.column(VulkanGranularPressure.Field.DIAMETERS),diameters);writer.put(dynamics.motion.columns.get(VulkanGranularMotion.Field.MATERIAL),contact);
            }
        }
        void recordInitialize(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer input,GpuBuffer output,GpuBuffer errors,int maxRounds) {
            var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            kernels.volumeKernel.rebindRanges(src,dst,err);kernels.inputKernel.rebindRanges(dst,err);kernels.capacityKernel.rebindRanges(dst,err);
            kernels.acceptedKernel.rebindRanges(dst,err);kernels.materialKernel.rebindRanges(src,dst,err);kernels.quantityKernel.rebindRanges(src,dst,err);
            kernels.gatherKernel.rebindRanges(src,dst,err);kernels.momentumKernel.rebindRanges(src,dst,err);kernels.ledger.rebindRanges(src,dst,err);
            kernels.liquidThermalKernel.rebindRanges(dst,err);kernels.heatTransportKernel.rebindRanges(src,dst,err);kernels.bodiesKernel.rebindRanges(src,dst,err);kernels.energyKernel.rebindRanges(src,dst,err);
            kernels.futureGeometry.rebindRanges(geometryRanges(output,errors,futureFaces));
            if(successor!=null)successor.bind(output,errors);
            kernels.volumeKernel.record(command,volumes);
            if(dynamics==null)kernels.inputKernel.record(command,capacityInputs);else kernels.inputKernel.recordDisplacement(command,capacityInputs);
            kernels.capacityKernel.recordInitialize(command,capacitySolve,maxRounds);
            recordCapacityIterations(command,output,errors,Math.min(32,maxRounds),3);
        }
        void bindGranular(GpuBuffer output,GpuBuffer errors){
            var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            kernels.granularGeometryKernel.rebindRanges(dst,err);kernels.granularPressureKernel.rebindRanges(dst,err);kernels.coupledKernel.rebindRanges(dst,err);
            kernels.contactKernel.rebindRanges(dst,err);kernels.motionKernel.rebindRanges(dst,err);kernels.granularThermalKernel.rebindRanges(dst,err);
        }
        void recordMotion(org.lwjgl.vulkan.VkCommandBuffer command,VulkanLiquidProjection.Parameters parameters){
            kernels.timed(command,"granular-contacts",()->kernels.motionKernel.recordContacts(command,dynamics.motion,parameters.cellSize(),parameters.duration()));
            kernels.contactKernel.recordPrepare(command,dynamics.contacts);kernels.contactKernel.recordCompression(command,dynamics.contacts,16);kernels.contactKernel.recordRebound(command,dynamics.contacts,16);
            kernels.timed(command,"granular-motion",()->kernels.motionKernel.recordTrialPositions(command,dynamics.motion,parameters.cellSize(),parameters.duration()));
            kernels.motionKernel.recordPublishPositions(command,dynamics.motion,parameters.cellSize());
            kernels.granularGeometryKernel.recordSupports(command,granular.geometry,parameters.cellSize());kernels.granularGeometryKernel.recordOccupancy(command,granular.geometry);
        }
        void recordFinish(org.lwjgl.vulkan.VkCommandBuffer command,int gravitySign) {
            kernels.capacityKernel.recordFinish(command,capacitySolve);
            kernels.timed(command,"accepted-volumes",()->kernels.acceptedKernel.record(command,accepted));kernels.timed(command,"material-routes",()->kernels.materialKernel.record(command,materials,gravitySign,kernels.timings));
            kernels.timed(command,"quantities",()->kernels.quantityKernel.record(command,quantities));kernels.timed(command,"gather",()->kernels.gatherKernel.record(command,gather));kernels.timed(command,"viscous-heat-transport",()->kernels.heatTransportKernel.record(command,heatTransport));kernels.timed(command,"momentum-transport",()->kernels.momentumKernel.recordTransport(command,momentum));
            kernels.timed(command,"future-liquid-geometry",()->kernels.futureGeometry.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ()));
            kernels.timed(command,"momentum-resolve",()->kernels.momentumKernel.recordResolve(command,momentum));kernels.timed(command,"mixing-heat",()->kernels.momentumKernel.recordHeatPosting(command,momentum));
            if(successor!=null)successor.recordGeometry(command);
            kernels.timed(command,"body-init",()->kernels.bodiesKernel.recordInitialize(command,bodies));kernels.timed(command,"body-connectivity",()->kernels.bodiesKernel.recordConnectivity(command,bodies,4));
        }
        void recordEnergy(org.lwjgl.vulkan.VkCommandBuffer command,VulkanLiquidProjection.Parameters parameters) {
            if(successor!=null)successor.recordRemap(command);
            kernels.timed(command,"body-csr",()->kernels.bodiesKernel.recordMembers(command,bodies));kernels.timed(command,"liquid-thermal",()->kernels.liquidThermalKernel.record(command,liquidThermal));
            if(granular!=null)kernels.timed(command,"granular-thermal",()->kernels.granularThermalKernel.record(command,granular.thermal));
            kernels.timed(command,"common-energy",()->kernels.energyKernel.record(command,energy,parameters.cellSize(),parameters.gravity()));
        }
        void recordLedgers(org.lwjgl.vulkan.VkCommandBuffer command,Transport previous) {
            kernels.timed(command,"roundoff-ledger",()->kernels.ledger.record(command,roundoff));kernels.timed(command,"heat-ledger",()->kernels.ledger.record(command,heat));
            if(previous!=null){kernels.ledger.recordAccumulate(command,roundoff,word(previous.roundoff.result()));kernels.ledger.recordAccumulate(command,heat,word(previous.heat.result()));}
            if(thermal!=null){kernels.ledger.record(command,thermal.reservoir());if(previous!=null)kernels.ledger.recordAccumulate(command,thermal.reservoir(),word(previous.thermal.reservoir().result()));}
        }
        int[] requirements(VulkanRegionStorage.Candidate candidate) {
            var columns=new VulkanLiquidStateLayout.Column[]{volumes.totalOffsets(),accepted.volumeOffsets(),materials.reserveOffsets(),
                    routes.column(VulkanLiquidRouteLayout.Field.VOLUME_OFFSETS),routes.column(VulkanLiquidRouteLayout.Field.SPECIES_OFFSETS),
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
            int[] result=new int[offsets.length];for(int i=0;i<result.length;i++)result[i]=bytes.getInt(4*i);return result;
        }
        List<VulkanLiquidStateLayout.Column> persistentColumns() {
            var result=new ArrayList<VulkanLiquidStateLayout.Column>(List.of(phaseTable,massTable,materialReference,speciesReference,
                    volumes.header(),capacity.column(VulkanLiquidCapacityLayout.Field.HEADER),capacityInputs.header(),accepted.header(),materials.header(),quantities.header(),gather.header(),momentum.header(),
                    liquidThermal.header(),liquidThermal.offsets(),liquidThermal.upper(),liquidThermal.capacity(),liquidThermal.referenceTemperature(),liquidThermal.coldSensible(),
                    bodies.header(),energy.header(),roundoff.header(),heat.header()));
            result.addAll(forceColumns());
            if(gas!=null) {
                result.add(gas.geometry.header());result.add(new VulkanLiquidStateLayout.Column(4L*gas.operator.headerWordOffset(),64,4));
                result.add(gas.operator.column(VulkanGasPressure.Field.CELL_PHYSICAL_PRESSURE));addGasTables(result,gas.thermo);
            }
            if(successor!=null) {
                result.addAll(List.of(successor.totals.header(),successor.geometry.header(),successor.lineage.header,successor.remap.header,successor.work.header()));
                addGasTables(result,successor.retainedEOS);
                for(var field:VulkanGasRemap.Field.values())if(field.name().startsWith("AMBIENT_")&&field!=VulkanGasRemap.Field.AMBIENT_BEFORE&&field!=VulkanGasRemap.Field.AMBIENT_AFTER)result.add(successor.remap.column(field));
            }
            if(aperture!=null)result.addAll(aperture.persistentColumns());
            if(thermal!=null){result.add(bathTemperatures);result.addAll(thermal.persistentColumns());}
            result.add(capacitySolve.column(VulkanLiquidCapacity.Field.HEADER));
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
            if(granular!=null){result.addAll(granular.headers());result.addAll(dynamics.headers());}
            if(thermal!=null)for(var chunk:thermal.headers())result.add(new HeaderWrite(chunk.offset(),chunk.bytes()));
            result.addAll(java.util.List.of(new HeaderWrite(volumes.header().offset(),volumes.headerBytes(source)),
                    new HeaderWrite(capacity.column(VulkanLiquidCapacityLayout.Field.HEADER).offset(),capacity.headerBytes()),
                    new HeaderWrite(capacityInputs.header().offset(),capacityInputs.headerBytes()),
                    new HeaderWrite(capacitySolve.column(VulkanLiquidCapacity.Field.HEADER).offset(),capacitySolve.headerBytes()),
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
    private final class GranularSection {
        final VulkanGranularStateLayout state;
        final VulkanGranularGeometry.Layout geometry;
        final VulkanLiquidStateLayout.Column materialReference,speciesReference;
        final VulkanLiquidThermodynamics.Layout thermal;
        final long endOffset;
        GranularSection(long base){var config=granularConfiguration;long a=liquid.alignment();
            state=new VulkanGranularStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),config.initial().particles().size(),config.initial().deposits().size(),config.speciesCapacity(),config.initial().definitions(),base,a);
            geometry=new VulkanGranularGeometry.Layout(state,Workspace.this.geometry.get(GeometryField.OCCUPIED_SOLID),state.endOffset(),a);
            materialReference=new VulkanLiquidStateLayout.Column(align(geometry.endOffset()),config.materialReferences().length,8);
            speciesReference=new VulkanLiquidStateLayout.Column(align(materialReference.offset()+materialReference.bindingBytes()),config.speciesReferences().length,8);
            thermal=VulkanLiquidThermodynamics.layout(state,config.thermal(),word(materialReference),word(speciesReference),config.speciesStride(),speciesReference.offset()+speciesReference.bindingBytes(),a);endOffset=thermal.endOffset();
        }
        List<VulkanLiquidStateLayout.Column> persistentColumns(){return List.of(new VulkanLiquidStateLayout.Column(4L*geometry.headerWordOffset(),48,4),materialReference,speciesReference,
                thermal.header(),thermal.offsets(),thermal.upper(),thermal.capacity(),thermal.referenceTemperature(),thermal.coldSensible());}
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(4L*geometry.headerWordOffset(),geometry.headerBytes()),new HeaderWrite(thermal.header().offset(),thermal.headerBytes()));}
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,GranularSection old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            state.copyColumns(command,input,old.state,output,stack);var from=old.persistentColumns();var to=persistentColumns();for(int i=1;i<from.size();i++)if(i!=3)copyColumn(command,input,from.get(i),output,to.get(i),stack);
            copyColumn(command,input,oldWorkspaceDynamics(old).pressure.column(VulkanGranularPressure.Field.DIAMETERS),output,transport.dynamics.pressure.column(VulkanGranularPressure.Field.DIAMETERS),stack);
            copyColumn(command,input,oldWorkspaceDynamics(old).motion.columns.get(VulkanGranularMotion.Field.MATERIAL),output,transport.dynamics.motion.columns.get(VulkanGranularMotion.Field.MATERIAL),stack);
        }
        private GranularDynamics oldWorkspaceDynamics(GranularSection value){return value.owner().transport.dynamics;}
        private Workspace owner(){return Workspace.this;}
    }
    private final class GranularDynamics {
        final VulkanCoupledPressure.Layout coupled;
        final VulkanGranularPressure.Layout pressure;
        final VulkanGranularContacts.Layout contacts;
        final VulkanGranularMotion.Layout motion;
        final long endOffset;
        GranularDynamics(GranularSection granular,GasSection gas,VulkanLiquidStateLayout.Column viscosity,long base){int particles=granular.state.particleCapacity(),faces=gas.geometry.faces();long a=liquid.alignment();
            coupled=new VulkanCoupledPressure.Layout(liquid.cellCount(),pressureColumns.get(PressureField.ROWS).count(),faces+3*particles,2*faces+75*particles,gas.operator.pressureBuffers(),base,a);
            pressure=new VulkanGranularPressure.Layout(granular.geometry,gas.operator,coupled,geometry.get(GeometryField.FIXED_MASKS),viscosity,null,coupled.endOffset(),a);
            contacts=new VulkanGranularContacts.Layout(granular.state,pressure,contactCapacity,pressure.endOffset(),a);
            motion=new VulkanGranularMotion.Layout(granular.state,contacts,pressure,word(geometry.get(GeometryField.FIXED_MASKS)),contacts.endOffset(),a);motion.bindBedGeometry(granular.geometry);endOffset=motion.endOffset();
        }
        List<VulkanLiquidStateLayout.Column> persistentColumns(){return List.of(new VulkanLiquidStateLayout.Column(4L*coupled.headerWordOffset(),64,4),new VulkanLiquidStateLayout.Column(4L*pressure.headerWordOffset(),96,4),
                pressure.column(VulkanGranularPressure.Field.DIAMETERS),contacts.header(),motion.header,motion.columns.get(VulkanGranularMotion.Field.MATERIAL));}
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(4L*coupled.headerWordOffset(),coupled.headerBytes()),new HeaderWrite(4L*pressure.headerWordOffset(),pressure.headerBytes()),
                new HeaderWrite(contacts.header().offset(),contacts.headerBytes()),new HeaderWrite(motion.header.offset(),motion.headerBytes()));}
    }
    private final class GasAperture {
        final GasSection gas;
        final GasSuccessor successor;
        final VulkanGasThermodynamics.Layout eos,retainedEOS;
        final VulkanGasFlow.Layout flow;
        final VulkanGasRemap.Layout remap;
        final VulkanGasAdiabaticWork.Layout work;
        final long endOffset;
        GasAperture(GasSection gas,GasSuccessor successor,long base){
            this.gas=gas;this.successor=successor;long a=liquid.alignment();
            var volumes=new VulkanLiquidStateLayout.Column(align(base),gasReserves.nodes(),8);
            eos=VulkanGasThermodynamics.layout(gas.state,gasConfiguration.tables(),word(volumes),volumes.offset()+volumes.bindingBytes(),a);
            flow=VulkanGasFlow.layout(eos,successor.geometry,gasReserves.pairs(),eos.endOffset(),a);
            remap=VulkanGasRemap.transportLayout(flow.routes(),eos,successor.retained,successor.future,gasConfiguration.ambient(),flow.word(VulkanGasFlow.Field.STEP),
                    gasReserves.parts(),gasReserves.partSpecies(),flow.endOffset(),a);
            retainedEOS=VulkanGasThermodynamics.layout(successor.retained,gasConfiguration.tables(),flow.word(VulkanGasFlow.Field.RETAINED_VOLUME),remap.endOffset(),a);
            work=VulkanGasAdiabaticWork.layout(retainedEOS,eos.nodeVolumesWordOffset(),flow.word(VulkanGasFlow.Field.MODES),retainedEOS.endOffset(),a);endOffset=work.endOffset();
        }
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(eos.header().offset(),eos.headerBytes()),new HeaderWrite(flow.header.offset(),flow.headerBytes()),
                new HeaderWrite(remap.header.offset(),remap.headerBytes()),new HeaderWrite(retainedEOS.header().offset(),retainedEOS.headerBytes()),new HeaderWrite(work.header().offset(),work.headerBytes()));}
        List<VulkanLiquidStateLayout.Column> persistentColumns(){
            var columns=new ArrayList<VulkanLiquidStateLayout.Column>();transport.addGasTables(columns,eos);transport.addGasTables(columns,retainedEOS);
            columns.add(flow.header);columns.add(remap.header);columns.add(work.header());
            for(var field:VulkanGasRemap.Field.values())if(field.name().startsWith("AMBIENT_")&&field!=VulkanGasRemap.Field.AMBIENT_BEFORE&&field!=VulkanGasRemap.Field.AMBIENT_AFTER)columns.add(remap.column(field));return columns;
        }
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,GasAperture old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            var from=old.persistentColumns();var to=persistentColumns();var headerOffsets=headers().stream().map(HeaderWrite::offset).toList();
            for(int i=0;i<from.size();i++)if(!headerOffsets.contains(to.get(i).offset()))copyColumn(command,input,from.get(i),output,to.get(i),stack);
        }
        int execute(VulkanRegionStorage.Candidate candidate,double duration,long step){
            var q=VulkanGasFlow.parameters(duration,.6,gasConfiguration.ambient(),step);var operator=kernels.flowKernel.pressureOperator(flow,q);int n=flow.nodes;
            capacityStage="gas aperture assemble";
            var batch=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                kernels.flowKernel.rebindRanges(dst,err);kernels.flowEOS.rebindRanges(dst,err);kernels.flowRemap.rebindRanges(dst,err);kernels.flowRetainedEOS.rebindRanges(dst,err);kernels.flowWork.rebindRanges(dst,err);
                kernels.flowPressure.rebindRanges(flow.pressureRanges(dst,err));
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                for(var field:new VulkanGasRemap.Field[]{VulkanGasRemap.Field.ENERGY_ROUNDOFF,VulkanGasRemap.Field.SOURCE_ROUNDOFF,VulkanGasRemap.Field.RETAINED_ROUNDOFF,VulkanGasRemap.Field.MERGE_ROUNDOFF,VulkanGasRemap.Field.AUDIT_ENERGY}){
                    var c=remap.column(field);vkCmdFillBuffer(command,output.handle(),c.offset(),c.bindingBytes(),0);
                }
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                kernels.timed(command,"aperture-eos",()->{kernels.flowEOS.recordVolumes(command,eos,successor.geometry);kernels.flowEOS.record(command,eos);});
                kernels.timed(command,"aperture-assemble",()->kernels.flowKernel.recordAssemble(command,flow,q));
            },flow.requiredPairsOffset(),4);
            if(batch.error()!=0)return batch.error();if(batch.feedback().getInt(0)==0)return 0;
            capacityStage="gas aperture pressure";
            batch=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                kernels.flowPressure.recordInitialize(command,n,n,200,null,null,operator);kernels.flowPressure.recordIterations(command,n,n,200,null,null,8,operator);
            },flow.column(VulkanGasFlow.Field.CONTROL).offset()+24,8);
            int scheduled=8;
            while(batch.error()==0&&batch.feedback().getDouble(0)!=0&&scheduled<200){
                int count=Math.min(8,200-scheduled);batch=candidate.continueBatch((command,input,output,errors,staging,stack)->kernels.flowPressure.recordIterations(command,n,n,200,null,null,count,operator),flow.column(VulkanGasFlow.Field.CONTROL).offset()+24,8);scheduled+=count;
            }
            if(batch.error()!=0)return batch.error();capacityStage="gas aperture remap";
            batch=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                kernels.flowPressure.recordFinish(command,n,n,200,null,null,operator);kernels.flowKernel.recordRoutes(command,flow,q);
                kernels.timed(command,"aperture-integer-transport",()->kernels.flowRemap.recordPrepare(command,remap));
                kernels.flowRemap.recordTransportVolumes(command,remap,flow);kernels.flowRetainedEOS.record(command,retainedEOS);kernels.flowWork.record(command,work);
                kernels.timed(command,"aperture-energy-gather",()->kernels.flowRemap.recordFinish(command,remap,work,kernels.timings));
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                gas.state.copyColumns(command,output,successor.future,output,stack);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
            },0,0);return batch.error();
        }
    }
    private final class GasSuccessor {
        final GasSection old;
        final VulkanLiquidCellVolumes.Layout totals;
        final VulkanGasGeometry.Layout geometry;
        final VulkanGasStateLayout retained,future;
        final VulkanGasLineage.Layout lineage;
        final VulkanGasRemap.Layout remap;
        final VulkanGasThermodynamics.Layout retainedEOS;
        final VulkanGasAdiabaticWork.Layout work;
        final long endOffset;
        GasSuccessor(GasSection old,VulkanLiquidAcceptedVolumes.Layout accepted,long base) {
            this.old=old;var config=gasConfiguration;long a=liquid.alignment();
            totals=VulkanLiquidCellVolumes.layout(liquid,Math.addExact(liquid.limbCapacity(),liquid.cellCount()),accepted.sourceCellVolumes().quantumExponent(),base,a);
            geometry=VulkanGasGeometry.layout(totals,word(Workspace.this.geometry.get(GeometryField.FIXED_MASKS)),old.geometry.occupiedWordOffset(),
                    word(Workspace.this.geometry.get(GeometryField.CELLS)),word(Workspace.this.geometry.get(GeometryField.PLANES)),word(Workspace.this.geometry.get(GeometryField.LIQUID_MICRO)),config.sealedExterior(),config.cellSize(),totals.endOffset(),a);
            retained=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gasReserves.nodes(),gasReserves.components(),gasReserves.species(),config.tables().definitions(),geometry.endOffset(),a);
            future=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gasReserves.nodes(),gasReserves.components(),gasReserves.species(),config.tables().definitions(),retained.endOffset(),a);
            lineage=VulkanGasLineage.layout(old.state,old.geometry,geometry,accepted,word(Workspace.this.geometry.get(GeometryField.FLUXES)),word(old.volumes),gasReserves.groups(),future.endOffset(),a);
            remap=VulkanGasRemap.layout(lineage,old.thermo,retained,future,config.ambient(),gasReserves.parts(),gasReserves.partSpecies(),lineage.endOffset(),a);
            retainedEOS=VulkanGasThermodynamics.layout(retained,config.tables(),lineage.word(VulkanGasLineage.Field.RETAINED_OLD_VOLUMES),remap.endOffset(),a);
            work=VulkanGasAdiabaticWork.layout(retainedEOS,lineage.word(VulkanGasLineage.Field.FUTURE_CONTROL_VOLUMES),old.operator.word(VulkanGasPressure.Field.MECHANICAL_MODE),retainedEOS.endOffset(),a);endOffset=work.endOffset();
        }
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(totals.header().offset(),totals.headerBytes()),new HeaderWrite(geometry.header().offset(),geometry.headerBytes()),
                new HeaderWrite(4L*lineage.headerWordOffset(),lineage.headerBytes()),new HeaderWrite(4L*remap.headerWordOffset(),remap.headerBytes()),
                new HeaderWrite(retainedEOS.header().offset(),retainedEOS.headerBytes()),new HeaderWrite(work.header().offset(),work.headerBytes()));}
        void bind(GpuBuffer output,GpuBuffer errors){var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            kernels.futureVolumeKernel.rebindRanges(arena,arena,err);kernels.futureGasGeometry.rebindRanges(arena,err);kernels.lineageKernel.rebindRanges(arena,err);
            kernels.remapKernel.rebindRanges(arena,err);kernels.retainedEOS.rebindRanges(arena,err);kernels.gasWork.rebindRanges(arena,err);}
        void recordGeometry(org.lwjgl.vulkan.VkCommandBuffer command){kernels.timed(command,"future-exact-volumes",()->kernels.futureVolumeKernel.record(command,totals));kernels.timed(command,"future-gas-geometry",()->kernels.futureGasGeometry.recordGeometry(command,geometry));kernels.timed(command,"future-gas-connectivity",()->kernels.futureGasGeometry.recordConnectivity(command,geometry,4));}
        void recordRemap(org.lwjgl.vulkan.VkCommandBuffer command){kernels.timed(command,"future-gas-components",()->kernels.futureGasGeometry.recordComponents(command,geometry));kernels.timed(command,"gas-lineage",()->kernels.lineageKernel.record(command,lineage));
            kernels.timed(command,"gas-remap-prepare",()->kernels.remapKernel.recordPrepare(command,remap));kernels.timed(command,"retained-gas-eos",()->kernels.retainedEOS.record(command,retainedEOS));kernels.timed(command,"gas-work",()->kernels.gasWork.record(command,work));kernels.timed(command,"gas-remap-finish",()->kernels.remapKernel.recordFinish(command,remap,work,kernels.timings));}
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,GasSuccessor previous,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            var from=new VulkanLiquidStateLayout.Column[]{previous.retainedEOS.segmentOffsets(),previous.retainedEOS.segmentUpper(),previous.retainedEOS.segmentHeatCapacity(),previous.retainedEOS.specificAnchorEnergy(),previous.retainedEOS.speciesReference()};
            var to=new VulkanLiquidStateLayout.Column[]{retainedEOS.segmentOffsets(),retainedEOS.segmentUpper(),retainedEOS.segmentHeatCapacity(),retainedEOS.specificAnchorEnergy(),retainedEOS.speciesReference()};
            for(int i=0;i<from.length;i++)copyColumn(command,input,from[i],output,to[i],stack);
            for(var field:VulkanGasRemap.Field.values())if(field.name().startsWith("AMBIENT_")&&field!=VulkanGasRemap.Field.AMBIENT_BEFORE&&field!=VulkanGasRemap.Field.AMBIENT_AFTER)
                copyColumn(command,input,previous.remap.column(field),output,remap.column(field),stack);
        }
    }
    private final class GasSection {
        final VulkanGasGeometry.Layout geometry;
        final VulkanGasStateLayout state;
        final VulkanLiquidStateLayout.Column volumes;
        final VulkanGasThermodynamics.Layout thermo;
        final VulkanGasPressure.Layout operator;
        final long endOffset;
        GasSection(VulkanLiquidCellVolumes.Layout totals,long base,int occupiedWord) {
            var config=gasConfiguration;long alignment=liquid.alignment();
            geometry=VulkanGasGeometry.layout(totals,word(Workspace.this.geometry.get(GeometryField.FIXED_MASKS)),occupiedWord,
                    word(Workspace.this.geometry.get(GeometryField.CELLS)),word(Workspace.this.geometry.get(GeometryField.PLANES)),word(Workspace.this.geometry.get(GeometryField.LIQUID_MICRO)),
                    config.sealedExterior(),config.cellSize(),base,alignment);
            state=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gasReserves.nodes(),gasReserves.components(),gasReserves.species(),config.tables().definitions(),geometry.endOffset(),alignment);
            volumes=new VulkanLiquidStateLayout.Column(align(state.endOffset()),gasReserves.nodes(),8);
            thermo=VulkanGasThermodynamics.layout(state,config.tables(),word(volumes),volumes.offset()+volumes.bindingBytes(),alignment);
            operator=new VulkanGasPressure.Layout(thermo,geometry,Workspace.this.geometry.get(GeometryField.CELLS),Workspace.this.geometry.get(GeometryField.FACES),
                    new VulkanGasPressure.PressureBuffers(pressureColumns.get(PressureField.STARTS),pressureColumns.get(PressureField.COLUMNS),pressureColumns.get(PressureField.VALUES),
                            pressureColumns.get(PressureField.ROWS),pressureColumns.get(PressureField.PRESSURE),pressureColumns.get(PressureField.WORK),pressureColumns.get(PressureField.META),
                            pressureColumns.get(PressureField.PROBLEM),pressureColumns.get(PressureField.CONTROL)),granularConfiguration==null?0:3*granularConfiguration.initial().particles().size(),thermo.endOffset(),alignment);endOffset=operator.endOffset();
        }
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
    private VulkanLiquidStateLayout.Column occupied(){return transport!=null&&transport.granular!=null?transport.granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ):geometry.get(GeometryField.OCCUPIED_SOLID);}
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
        VulkanLiquidViscosity viscosityKernel;
        VulkanLiquidForces forceKernel;
        VulkanSurfaceTension surfaceKernel;
        VulkanMechanicalHeatTransport heatTransportKernel;
        VulkanEnergyLedger ledger;
        VulkanMechanicalBodies bodiesKernel;
        VulkanMechanicalEnergy energyKernel;
        VulkanGasGeometry gasGeometry;
        VulkanGasThermodynamics gasThermo;
        VulkanGasPressure gasPressure;
        VulkanLiquidCellVolumes futureVolumeKernel;
        VulkanGasGeometry futureGasGeometry;
        VulkanGasLineage lineageKernel;
        VulkanGasRemap remapKernel;
        VulkanGasThermodynamics retainedEOS;
        VulkanGasAdiabaticWork gasWork;
        VulkanGasFlow flowKernel;
        VulkanPressureKernel flowPressure;
        VulkanGasThermodynamics flowEOS,flowRetainedEOS;
        VulkanGasRemap flowRemap;
        VulkanGasAdiabaticWork flowWork;
        VulkanStageTimings timings;
        VulkanGranularGeometry granularGeometryKernel;
        VulkanGranularPressure granularPressureKernel;
        VulkanCoupledPressure coupledKernel;
        VulkanGranularContacts contactKernel;
        VulkanGranularMotion motionKernel;
        VulkanLiquidThermodynamics granularThermalKernel;
        VulkanRegionThermal thermalKernel;

        Kernels(VulkanDeviceContext context,boolean gasEnabled,boolean granularEnabled,boolean thermalEnabled) {
            try {
                if(Boolean.getBoolean("mmm.gpuMechanicalStages"))timings=own(new VulkanStageTimings(context,96));
                futureGeometry=own(new VulkanLiquidGeometry(context));volumeKernel=own(new VulkanLiquidCellVolumes(context));
                inputKernel=own(new VulkanLiquidCapacityInputs(context));capacityKernel=own(new VulkanLiquidCapacity(context));
                acceptedKernel=own(new VulkanLiquidAcceptedVolumes(context));materialKernel=own(new VulkanLiquidMaterialRoutes(context));
                quantityKernel=own(new VulkanLiquidRouteQuantities(context));gatherKernel=own(new VulkanLiquidGather(context));
                momentumKernel=own(new VulkanLiquidMomentum(context));liquidThermalKernel=own(new VulkanLiquidThermodynamics(context));ledger=own(new VulkanEnergyLedger(context));
                viscosityKernel=own(new VulkanLiquidViscosity(context));forceKernel=own(new VulkanLiquidForces(context));surfaceKernel=own(new VulkanSurfaceTension(context));heatTransportKernel=own(new VulkanMechanicalHeatTransport(context));
                bodiesKernel=own(new VulkanMechanicalBodies(context));energyKernel=own(new VulkanMechanicalEnergy(context));
                if(thermalEnabled)thermalKernel=own(new VulkanRegionThermal(context));
                gasGeometry=own(new VulkanGasGeometry(context));gasThermo=own(new VulkanGasThermodynamics(context));gasPressure=own(new VulkanGasPressure(context));
                if(gasEnabled){futureVolumeKernel=own(new VulkanLiquidCellVolumes(context));futureGasGeometry=own(new VulkanGasGeometry(context));lineageKernel=own(new VulkanGasLineage(context));
                    remapKernel=own(new VulkanGasRemap(context));retainedEOS=own(new VulkanGasThermodynamics(context));gasWork=own(new VulkanGasAdiabaticWork(context));
                    flowKernel=own(new VulkanGasFlow(context));flowPressure=own(new VulkanPressureKernel(context,4096));flowEOS=own(new VulkanGasThermodynamics(context));
                    flowRetainedEOS=own(new VulkanGasThermodynamics(context));flowRemap=own(new VulkanGasRemap(context));flowWork=own(new VulkanGasAdiabaticWork(context));}
                if(granularEnabled){granularGeometryKernel=own(new VulkanGranularGeometry(context));granularPressureKernel=own(new VulkanGranularPressure(context));coupledKernel=own(new VulkanCoupledPressure(context));
                    contactKernel=own(new VulkanGranularContacts(context));motionKernel=own(new VulkanGranularMotion(context));granularThermalKernel=own(new VulkanLiquidThermodynamics(context));}
            }catch(RuntimeException|LinkageError failure){try{close();}catch(RuntimeException closing){failure.addSuppressed(closing);}throw failure;}
        }
        void timed(org.lwjgl.vulkan.VkCommandBuffer command,String label,Runnable commands){if(timings==null)commands.run();else timings.record(command,label,commands);}
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
    private static int growGas(int current,int required){if(required<0)throw new IllegalArgumentException("Gas capacity exceeds int32 indexing");return required>current?Math.multiplyExact(required,2):current;}
    private static int growCount(int current,int required) {
        if(required<0)throw new IllegalArgumentException("Device capacity requirement exceeds int32 indexing");
        return required>current?Math.max(required,Math.multiplyExact(Math.max(1,current),2)):current;
    }
    private static int ceil(int value,int divisor){if(value<0)throw new IllegalArgumentException("Negative device capacity requirement");return (int)(((long)value+divisor-1)/divisor);}
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static VulkanEnergyLedger.Stream stream(VulkanLiquidStateLayout.Column column,int coefficient){return new VulkanEnergyLedger.Stream(1,word(column),column.count(),2,coefficient);}
    @Override public void close(){try{workspace.templates.close();if(kernels!=null)kernels.close();}finally{storage.close();}}
}
