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
    private VulkanPressureKernel.Progress pressureProgress;
    private int previousPressureIterations=16;
    private int capacityIterationCount;
    private boolean currentGasThermal;
    private mmm.units.SignedDyadic importRoundoff=mmm.units.SignedDyadic.ZERO;
    mmm.units.SignedDyadic importRoundoff(){return importRoundoff;}
    private boolean currentGeometryValid;
    private RequestInputs currentInputs=new RequestInputs(new mmm.fluid.EntityBodyState(List.of()),false,0);
    private int requestedBodyCapacity,requestedBodyEntries;
    private double requestCellSize=1;
    private RequestInputs activeInputs;
    private final List<Growth> growths=Boolean.getBoolean("mmm.gpuMechanicalGrowths")?new ArrayList<>():null;
    record Growth(long generation,String stage,int[] before,int[] after,int[] required) { }
    synchronized List<Growth> growthDiagnostics(){return growths==null?List.of():List.copyOf(growths);}
    private final GasConfiguration gasConfiguration;
    private final VulkanGranularStateLayout.Configuration granularConfiguration;
    private final VulkanRegionThermal.Configuration thermalConfiguration;
    private final VulkanCoherentStateLayout.Configuration coherentConfiguration;
    private final VulkanThermalDeviceLayout.Configuration deviceConfiguration;
    private final VulkanRegionReactions.Configuration reactionConfiguration;
    private final GranularReserves initialGranularReserves;

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
                            int nodeCapacity,double cellSize,boolean sealedExterior,
                            VulkanGasRemap.AmbientTables ambient) {
        static GasConfiguration forState(VulkanGasStateLayout.State initial,VulkanGasThermodynamics.Tables tables,double cellSize,
                                         boolean sealedExterior,VulkanGasRemap.AmbientTables ambient) {
            return new GasConfiguration(initial,tables,reserve(initial.snapshot().nodes().size()),cellSize,sealedExterior,ambient);
        }
        int componentCapacity(){return Math.multiplyExact(nodeCapacity+2,tables.definitions().componentIds().size());}
        private static int reserve(int actual){return Math.multiplyExact(Math.max(1,actual),2);}
        GasConfiguration {
            java.util.Objects.requireNonNull(initial);java.util.Objects.requireNonNull(tables);java.util.Objects.requireNonNull(ambient);
            if(nodeCapacity<1||nodeCapacity<initial.snapshot().nodes().size()
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
        this(context,kernel,projection,pressure,multigrid,inventory,momentum,fixed,occupiedSolid,definitions,gasConfiguration,granularConfiguration,thermalConfiguration,null,null);
    }
    VulkanLiquidRegion(VulkanDeviceContext context,VulkanLiquidGeometry kernel,VulkanLiquidProjection projection,
                       VulkanPressureKernel pressure,VulkanPressureMultigrid multigrid,DeviceLiquidInventory inventory,
                       MacMomentumState momentum,FixedGeometrySnapshot fixed,long[] occupiedSolid,MechanicalDefinitions definitions,GasConfiguration gasConfiguration,
                       VulkanGranularStateLayout.Configuration granularConfiguration,VulkanRegionThermal.Configuration thermalConfiguration,
                       VulkanCoherentStateLayout.Configuration coherentConfiguration,VulkanThermalDeviceLayout.Configuration deviceConfiguration){
        this(context,kernel,projection,pressure,multigrid,inventory,momentum,fixed,occupiedSolid,definitions,gasConfiguration,granularConfiguration,thermalConfiguration,coherentConfiguration,deviceConfiguration,null,null);
    }
    VulkanLiquidRegion(VulkanDeviceContext context,VulkanLiquidGeometry kernel,VulkanLiquidProjection projection,VulkanPressureKernel pressure,VulkanPressureMultigrid multigrid,
                       DeviceLiquidInventory inventory,MacMomentumState momentum,FixedGeometrySnapshot fixed,long[] occupiedSolid,MechanicalDefinitions definitions,GasConfiguration gasConfiguration,
                       VulkanGranularStateLayout.Configuration granularConfiguration,VulkanRegionThermal.Configuration thermalConfiguration,VulkanCoherentStateLayout.Configuration coherentConfiguration,
                       VulkanThermalDeviceLayout.Configuration deviceConfiguration,VulkanRegionReactions.Configuration reactionConfiguration,GranularReserves granularReserves){
        if(reactionConfiguration!=null&&thermalConfiguration==null)throw new IllegalArgumentException("Liquid reactions require calibrated region thermodynamics");
        this.reactionConfiguration=reactionConfiguration!=null&&reactionConfiguration.enabled()?reactionConfiguration:null;this.initialGranularReserves=granularReserves;
        if((coherentConfiguration!=null||deviceConfiguration!=null)&&thermalConfiguration==null)throw new IllegalArgumentException("Solid/device thermal inventories require region thermodynamics");
        if(coherentConfiguration!=null&&thermalConfiguration.cellSize()!=.5)throw new IllegalArgumentException("Coherent SVQ geometry requires 0.5 m cells");
        this.coherentConfiguration=coherentConfiguration;this.deviceConfiguration=deviceConfiguration;
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
                        definitions.speciesCapacity(),Math.max(definitions.limbCapacity(),VulkanLiquidStateLayout.requiredVolumeCapacity(inventory,Math.max(definitions.stockCapacity(),Math.multiplyExact(inventory.cellCount(),definitions.materialPhases().length)),definitions.materialPhases().length)),context.storageOffsetAlignment(),definitions.materialPhases().length);
        if(!fixed.matchesShape(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ())||occupiedSolid.length!=liquid.cellCount())
            throw new IllegalArgumentException("Resident liquid geometry differs from its grid");
        workspace=new Workspace(liquid,definitions);
        kernels=definitions==null?null:new Kernels(context,gasConfiguration!=null,granularConfiguration!=null,thermalConfiguration!=null,coherentConfiguration!=null,this.reactionConfiguration!=null);
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
        var l=workspace.liquid;var originalEnergy=mmm.units.SignedDyadic.sum(energies(inventory.energyBits()));
        inventory=l.slotInventory(inventory);
        importRoundoff=mmm.units.SignedDyadic.sum(energies(inventory.energyBits())).subtract(originalEnergy);
        if(inventory.cellCount()!=l.cellCount()||inventory.stockCount()>l.stockCapacity()||inventory.speciesHandles().length>l.speciesCapacity()||inventory.volumes().limbs().length>l.limbCapacity())
            throw new IllegalArgumentException("Liquid import exceeds resident capacity");
        var v=inventory.volumes();var encoded=l.encodeVolumes(inventory);
        writer.put(l.column(COUNTS),new int[]{inventory.stockCount(),inventory.speciesHandles().length,encoded.used()});
        writer.put(l.column(CELL_OFFSETS),inventory.cellOffsets());writer.put(l.column(MATERIALS),inventory.materials());writer.put(l.column(SUPPORT_MASKS),inventory.supportMasks());
        writer.put(l.column(VOLUME_OFFSETS),encoded.offsets());writer.put(l.column(VOLUME_COUNTS),encoded.counts());writer.put(l.column(VOLUME_EXPONENTS),encoded.exponents());writer.put(l.column(VOLUME_LIMBS),encoded.limbs());
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
        if(workspace.transport!=null){workspace.transport.initialize(writer);
            var total=mmm.units.SignedDyadic.sum(energies(inventory.energyBits()));
            if(gasConfiguration!=null)total=total.add(mmm.units.SignedDyadic.sum(gasConfiguration.initial().nodeEnergy()));
            if(granularConfiguration!=null){total=total.add(mmm.units.SignedDyadic.sum(granularConfiguration.initial().particles().stream().mapToDouble(p->p.inventory().internalEnergy()).toArray()));total=total.add(mmm.units.SignedDyadic.sum(granularConfiguration.initial().deposits().stream().mapToDouble(d->d.inventory().internalEnergy()).toArray()));}
            if(coherentConfiguration!=null)total=total.add(mmm.units.SignedDyadic.sum(coherentConfiguration.initial().stream().mapToDouble(mmm.thermal.CoherentPhaseStore.Group::internalEnergy).toArray()));
            writer.put(workspace.transport.inventoryPosting.result().offset(),canonical(total));
        }
    }
    private static double[] energies(long[] bits){return java.util.Arrays.stream(bits).mapToDouble(Double::longBitsToDouble).toArray();}
    private static ByteBuffer canonical(mmm.units.SignedDyadic value){var b=ByteBuffer.allocate(288).order(ByteOrder.LITTLE_ENDIAN);var n=value.significand().abs();int count=(n.bitLength()+31)/32;b.putInt(0,value.signum()).putInt(4,value.exponent()).putInt(8,count);for(int i=0;i<count;i++)b.putInt(16+4*i,n.shiftRight(32*i).intValue());return b;}
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
        try(var source=captureState();var prepared=prepareMechanical(source,parameters,zeroInitialPressure,capacityRounds)){return prepared.commit();}
    }
    synchronized void initializeInputs(mmm.fluid.EntityBodyState bodies){currentInputs=new RequestInputs(bodies,false,0);}
    synchronized Capture captureState(){return new Capture(storage.capture(),workspace,currentGasThermal,currentGeometryValid,currentInputs);}
    int[] pressureReductionCounts(){
        try(var source=captureState()){
            if(source.layout.transport.gas==null)return new int[]{0,0};
            var data=source.read(4L*(source.layout.transport.gas.operator.headerWordOffset()+28),8);
            return new int[]{data.getInt(0),data.getInt(4)};
        }
    }
    void dumpCapacityInput(String path){
        try(var source=captureState()){
            var capacity=source.layout.transport.capacity;
            VulkanLiquidCapacity.dump(capacity,source.read(capacity.baseOffset(),capacity.bytes()),path);
        }
    }
    /** Explicit performance diagnostics, called outside continuous-step timing. */
    void dumpGasFlowGraph(java.nio.file.Path path,double duration){
        try(var source=captureState()){
            var aperture=source.layout.transport.aperture;
            if(aperture==null)throw new IllegalStateException("No finite aperture graph");
            var flow=aperture.flow;
            dumpGasFlowGraph(path,duration,flow,aperture.eos.gas(),source::read);
        }
    }
    private static void dumpGasFlowGraph(java.nio.file.Path path,double duration,VulkanGasFlow.Layout flow,
            VulkanGasStateLayout gas,java.util.function.BiFunction<Long,Long,ByteBuffer> read){
        try{
            int n=read.apply(gas.column(VulkanGasStateLayout.Field.COUNTS).offset(),4L).getInt(0);
            int pairs=read.apply(flow.requiredPairsOffset(),4L).getInt(0);
            var bytes=ByteBuffer.allocate(Math.toIntExact(24L+36L*n+16L*pairs)).order(ByteOrder.LITTLE_ENDIAN);
            bytes.put("MMMGAS01".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(n).putInt(pairs).putDouble(duration);
            for(var field:new VulkanGasFlow.Field[]{VulkanGasFlow.Field.STORAGE,VulkanGasFlow.Field.DIAGONAL_ROOT})bytes.put(read.apply(flow.column(field).offset(),8L*n));
            bytes.put(read.apply(flow.column(VulkanGasFlow.Field.PROBLEM).offset(),8L*n));
            bytes.put(read.apply(flow.column(VulkanGasFlow.Field.PROBLEM).offset()+16L*flow.nodes,8L*n));
            bytes.put(read.apply(gas.column(VulkanGasStateLayout.Field.TOPOLOGY_KEYS).offset(),4L*n));
            for(var field:new VulkanGasFlow.Field[]{VulkanGasFlow.Field.PAIR_A,VulkanGasFlow.Field.PAIR_B,VulkanGasFlow.Field.PAIR_G})bytes.put(read.apply(flow.column(field).offset(),field.width*(long)pairs));
            bytes.flip();try(var file=java.nio.channels.FileChannel.open(path,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,java.nio.file.StandardOpenOption.WRITE)){while(bytes.hasRemaining())file.write(bytes);}
        }catch(java.io.IOException failure){throw new IllegalStateException("Writing aperture performance graph failed",failure);}
    }
    /** Liquid/gas common pressure graph for offline cost analysis, after timing. */
    void dumpCommonPressureGraph(java.nio.file.Path path,double duration,double cellSize){
        try(var source=captureState()){
            if(source.layout.transport.granular!=null)throw new IllegalStateException("Common pressure graph export requires liquid/gas rows without granular Gram terms");
            var w=source.layout;var g=w.transport.gas.operator;var c=w.pressureColumns;
            int n=c.get(PressureField.ROWS).count(),ng=g.gasCapacity(),nnz=c.get(PressureField.VALUES).count();
            int edges=source.read(g.column(VulkanGasPressure.Field.FACE_OFFSETS).offset()+4L*ng,4).getInt(0);
            var header=ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
            header.put("MMMPRE01".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            header.putInt(w.liquid.sizeX()).putInt(w.liquid.sizeY()).putInt(w.liquid.sizeZ()).putInt(n).putInt(ng).putInt(nnz).putInt(edges).putDouble(duration).putDouble(cellSize).flip();
            var chunks=new ArrayList<ByteBuffer>();chunks.add(header);
            chunks.add(source.read(c.get(PressureField.STARTS).offset(),4L*(n+1)));
            chunks.add(source.read(c.get(PressureField.COLUMNS).offset(),4L*nnz));
            chunks.add(source.read(c.get(PressureField.VALUES).offset(),8L*nnz));
            chunks.add(source.read(c.get(PressureField.ROWS).offset(),4L*n));
            chunks.add(source.read(c.get(PressureField.META).offset(),8L*n));
            chunks.add(source.read(c.get(PressureField.PROBLEM).offset(),8L*n));
            chunks.add(source.read(c.get(PressureField.PRESSURE).offset(),8L*n));
            chunks.add(source.read(c.get(PressureField.PROBLEM).offset()+8L*n,8L*n));
            chunks.add(source.read(c.get(PressureField.PROBLEM).offset()+16L*n,8L*n));
            chunks.add(source.read(g.column(VulkanGasPressure.Field.FACE_OFFSETS).offset(),4L*(ng+1)));
            chunks.add(source.read(g.column(VulkanGasPressure.Field.PRODUCT_PEERS).offset(),4L*edges));
            chunks.add(source.read(g.column(VulkanGasPressure.Field.PRODUCT_WEIGHTS).offset(),8L*edges));
            chunks.add(source.read(g.column(VulkanGasPressure.Field.STORAGE).offset(),8L*ng));
            chunks.add(source.read(g.column(VulkanGasPressure.Field.DIAGONAL).offset(),8L*ng));
            try(var file=java.nio.channels.FileChannel.open(path,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,java.nio.file.StandardOpenOption.WRITE)){
                for(var bytes:chunks)while(bytes.hasRemaining())file.write(bytes);
            }
        }catch(java.io.IOException failure){throw new IllegalStateException("Writing common pressure performance graph failed",failure);}
    }
    private mmm.fluid.EntityBodyState requestedBodies(){return (activeInputs==null?currentInputs:activeInputs).bodies();}
    private VulkanEntityBodyCoupling.Configuration bodyConfiguration(mmm.fluid.EntityBodyState bodies){return new VulkanEntityBodyCoupling.Configuration(bodies,workspace.liquid.sizeX(),workspace.liquid.sizeY(),workspace.liquid.sizeZ(),requestCellSize);}
    private Workspace bodyWorkspace(Workspace source,mmm.fluid.EntityBodyState oldBodies,java.util.Set<Workspace> allocated){
        var old=bodyConfiguration(oldBodies);var next=bodyConfiguration(requestedBodies());
        int bodies=Math.max(oldBodies.bodies().size(),next.bodies().bodies().size()),entries=Math.max(old.cellEntries(),next.cellEntries());
        Workspace target=source;
        if(bodies>source.bodyCapacity||entries>source.bodyEntries){
            requestedBodyCapacity=Math.max(requestedBodyCapacity,growCount(source.bodyCapacity,bodies));requestedBodyEntries=Math.max(requestedBodyEntries,growCount(source.bodyEntries,entries));
            target=new Workspace(source.liquid,source.transport.definitions,source.gasReserves,source.contactCapacity,source.thermalContactCapacity,source.thermalBathCapacity,source.granularReserves,source.coherentSpeciesCapacity);allocated.add(target);
        }
        target.sourceBodyState=oldBodies;return target;
    }
    record RequestInputs(mmm.fluid.EntityBodyState bodies,boolean phaseChangeEnabled,int maximumPhaseOperations,boolean pendingPhaseSettlementOnly,boolean mechanicalAuditEnabled){
        RequestInputs{java.util.Objects.requireNonNull(bodies);if(phaseChangeEnabled?maximumPhaseOperations<=0:maximumPhaseOperations!=0)throw new IllegalArgumentException("Phase request budget differs from enabled mode");}
        RequestInputs(mmm.fluid.EntityBodyState bodies,boolean phaseChangeEnabled,int maximumPhaseOperations){this(bodies,phaseChangeEnabled,maximumPhaseOperations,false,false);}
        RequestInputs(mmm.fluid.FluidStepRequest request){this(new mmm.fluid.EntityBodyState(request.movingBodies()),request.phaseChangeEnabled(),request.maximumPhaseOperations(),request.pendingPhaseSettlementOnly(),request.mechanicalAuditEnabled());}
        RequestInputs stationary(mmm.fluid.EntityBodyState retained){return new RequestInputs(new mmm.fluid.EntityBodyState(retained.bodies().stream().map(body->new mmm.world.MovingBodyFluidCoupling.Request(body.bodyId(),body.bounds(),0,0,0)).toList()),false,0,true,mechanicalAuditEnabled);}
    }
    record ReadoutInputs(VulkanLiquidStateLayout liquid,java.util.Map<GeometryField,VulkanLiquidStateLayout.Column> geometry,
                         VulkanLiquidThermodynamics.Layout liquidThermal,VulkanGranularStateLayout granular,VulkanLiquidThermodynamics.Layout granularThermal,VulkanGranularGeometry.Layout granularGeometry,
                         VulkanGasStateLayout gas,VulkanGasThermodynamics.Layout gasThermal,VulkanGasGeometry.Layout gasGeometry,
                         VulkanCoherentStateLayout coherent,VulkanLiquidThermodynamics.Layout coherentThermal,VulkanThermalDeviceLayout devices,VulkanLiquidThermodynamics.Layout deviceThermal,VulkanCoherentGeometry.Layout coherentGeometry,VulkanRegionThermal.Layout thermal,VulkanLiquidViscosity.Layout viscosity,VulkanLiquidMomentum.Layout momentum,VulkanGasPressure.Layout gasPressure,VulkanMechanicalEnergy.Layout mechanicalEnergy){}
    record ThermalPosting(mmm.units.SignedDyadic source,mmm.units.SignedDyadic sink,mmm.units.SignedDyadic mechanicalWork,mmm.units.SignedDyadic reservoir,mmm.units.SignedDyadic finiteDevices,mmm.units.SignedDyadic roundoff){
        mmm.thermal.ThermalAuditLedger apply(mmm.thermal.ThermalAuditLedger before){return before.withExternalSourceEnergy(source).withExternalSinkEnergy(sink).withMechanicalWork(mechanicalWork).withHeatExchange(reservoir).withFiniteDeviceExchange(finiteDevices).withRoundoff(roundoff);}
    }
    final class Capture implements AutoCloseable {
        private final VulkanRegionStorage.Capture capture;private final Workspace layout;private final boolean currentGas,geometryValid;private final RequestInputs inputs;
        private Capture(VulkanRegionStorage.Capture capture,Workspace layout,boolean currentGas,boolean geometryValid,RequestInputs inputs){this.capture=capture;this.layout=layout;this.currentGas=currentGas;this.geometryValid=geometryValid;this.inputs=inputs;}
        GpuBuffer buffer(){return capture.buffer();}
        ReadoutInputs readoutInputs(){var t=layout.transport;var g=t==null?null:t.gas;return new ReadoutInputs(layout.liquid,java.util.Map.copyOf(layout.geometry),t==null?null:t.liquidThermal,
                t==null||t.granular==null?null:t.granular.state,t==null||t.granular==null?null:t.granular.thermal,t==null||t.granular==null?null:t.granular.geometry,g==null?null:g.state,g==null?null:currentGas?g.thermo:t.aperture==null?g.thermo:t.aperture.eos,
                g==null?null:currentGas||t.successor==null?g.geometry:t.successor.geometry,t==null||t.coherent==null?null:t.coherent.state,t==null||t.coherent==null?null:t.coherent.thermal,
                t==null||t.devices==null?null:t.devices.state,t==null||t.devices==null?null:t.devices.thermal,t==null||t.coherent==null?null:t.coherent.geometry,t==null?null:t.thermal,t==null?null:t.viscosity,t==null?null:t.momentum,g==null?null:g.operator,t==null?null:t.energy);}
        int[] chemistryCounters(){var t=layout.transport;if(t==null||t.reactions==null)return new int[8];return ints(t.reactions.progress().column(VulkanReactionProgressLayout.Field.CONTROL),8);}
        int[] thermalPhaseCounters(){var t=layout.transport;if(inputs==null||!inputs.phaseChangeEnabled()||inputs.pendingPhaseSettlementOnly()||t==null||t.reactions==null||t.reactions.condensedPhase()==null)return new int[10];return ints(t.reactions.condensedPhase().column(VulkanCondensedPhaseChange.Field.CONTROL),10);}
        int[] massTransferCounters(){var t=layout.transport;if(inputs!=null&&inputs.pendingPhaseSettlementOnly()||t==null||t.reactions==null||t.reactions.massTransfer()==null)return new int[4];return ints(t.reactions.massTransfer().column(VulkanGasLiquidMassTransfer.Field.CONTROL),4);}
        int[] gasPhaseCounters(){var t=layout.transport;if(inputs==null||!inputs.phaseChangeEnabled()||inputs.pendingPhaseSettlementOnly()||t==null||t.reactions==null||t.reactions.gasPhase()==null)return new int[8];return ints(t.reactions.gasPhase().column(VulkanGasPhaseChange.Field.CONTROL),8);}
        int[] granularCounters(){var t=layout.transport;if(currentGas||t==null||t.dynamics==null)return new int[6];return ints(t.dynamics.transitions.column(VulkanGranularTransitions.Field.CONTROL),6);}
        private int[] ints(VulkanLiquidStateLayout.Column column,int count){var b=read(column.offset(),4L*count);int[] values=new int[count];b.asIntBuffer().get(values);return values;}
        double[] pressureDiagnostics(){var b=read(layout.pressureColumns.get(PressureField.CONTROL).offset(),24);return new double[]{b.getDouble(0),b.getDouble(8),b.getDouble(16)};}
        long generation(){return capture.generation();}
        Capture retain(){return new Capture(capture.retain(),layout,currentGas,geometryValid,inputs);}
        VulkanLiquidStateLayout.State liquid(){var b=read(0,layout.liquid.bytes());return layout.liquid.exportState(b);}
        VulkanGranularStateLayout.State granular(){var s=layout.transport.granular.state;return s.exportState(read(s.baseOffset(),s.bytes()));}
        List<mmm.thermal.CoherentPhaseStore.Group> coherent(){var s=layout.transport.coherent.state;return s.exportState(read(s.baseOffset(),s.bytes()));}
        VulkanThermalDeviceLayout.State devices(){var s=layout.transport.devices.state;return s.exportState(read(s.baseOffset(),s.bytes()));}
        VulkanGasStateLayout.State gas(){
            var s=layout.transport.gas.state;var local=new VulkanGasStateLayout(s.sizeX(),s.sizeY(),s.sizeZ(),s.nodeCapacity(),s.definitions(),8);
            var b=ByteBuffer.allocate(Math.toIntExact(local.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            for(var f:VulkanGasStateLayout.Field.values()){var c=s.column(f);if(c.bytes()>0)storage.read(capture,c.offset(),b.slice(Math.toIntExact(local.column(f).offset()),Math.toIntExact(c.bytes())));}
            return local.exportState(b);
        }
        mmm.fluid.EntityBodyState entityBodies(){return inputs.bodies();}
        RequestInputs requestInputs(){return inputs;}
        mmm.chemistry.ChemicalTransferLedger filmExternalExchange(boolean incoming){
            var t=layout.transport;if(t==null||t.reactions==null||t.reactions.massTransfer()==null)return mmm.chemistry.ChemicalTransferLedger.EMPTY;
            var film=t.reactions.massTransfer();var speciesColumn=film.column(incoming?VulkanGasLiquidMassTransfer.Field.AMBIENT_SOURCE_SPECIES:VulkanGasLiquidMassTransfer.Field.AMBIENT_SINK_SPECIES);
            var massColumn=film.column(incoming?VulkanGasLiquidMassTransfer.Field.SOURCE_MASS:VulkanGasLiquidMassTransfer.Field.SINK_MASS);
            var species=read(speciesColumn.offset(),speciesColumn.bytes());var mass=read(massColumn.offset(),massColumn.bytes());
            var groups=new ArrayList<mmm.chemistry.ChemicalTransferLedger.Group>();var chemistry=reactionConfiguration.chemistry();int count=chemistry.species().size();
            for(int material=0;material<layout.liquid.materialCount();material++){
                var amounts=new java.util.TreeMap<String,Long>();long resolved=0;
                for(int sp=0;sp<count;sp++){long q=species.getLong(8*(material*count+sp));if(q==0)continue;amounts.put(chemistry.species(sp).id(),q);
                    var unit=chemistry.species(sp).molarMassKgPerMol().divide(mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL);
                    resolved=Math.addExact(resolved,Math.multiplyExact(q,mmm.units.Mass.exactUnits(unit.numerator(),unit.denominator())));}
                long total=mass.getLong(8*material);if(total!=0||!amounts.isEmpty())groups.add(new mmm.chemistry.ChemicalTransferLedger.Group(reactionConfiguration.fluids().material(material).id(),0,total,total-resolved,amounts));
            }
            return new mmm.chemistry.ChemicalTransferLedger(groups);
        }
        VulkanReactionProgressLayout.State reactionProgress(){var t=layout.transport;return t==null||t.reactions==null?VulkanReactionProgressLayout.State.empty():t.reactions.progress().export(c->read(c.offset(),c.bytes()));}
        boolean hasPendingPhaseMotion(){var t=layout.transport;return t!=null&&t.reactions!=null&&read(t.reactions.progress().column(VulkanReactionProgressLayout.Field.PHASE_PENDING_SUMMARY).offset(),4).getInt(0)!=0;}
        mmm.fluid.PendingPhaseMotionState pendingPhaseMotion(){return new mmm.fluid.PendingPhaseMotionState(layout.liquid.cellCount(),java.util.Arrays.stream(reactionProgress().phaseMotionCells()).mapToObj(mmm.fluid.PendingPhaseMotionState.Entry::new).toList());}
        boolean hasGas(){return layout.transport!=null&&layout.transport.gas!=null;}
        boolean hasGranular(){return layout.transport!=null&&layout.transport.granular!=null;}
        boolean hasCoherent(){return layout.transport!=null&&layout.transport.coherent!=null;}
        boolean hasDevices(){return layout.transport!=null&&layout.transport.devices!=null;}
        mmm.inventory.PhaseOccupancyState occupancy(){var c=layout.geometry.get(GeometryField.LIQUID_MICRO);var b=read(c.offset(),c.bytes());double[] values=new double[c.count()];b.asDoubleBuffer().get(values);return new mmm.inventory.PhaseOccupancyState(values);}
        mmm.fluid.CondensedPhasePressureField pressure(){var c=layout.transport!=null&&layout.transport.gas!=null?layout.transport.gas.operator.column(VulkanGasPressure.Field.CELL_PHYSICAL_PRESSURE):layout.pressureColumns.get(PressureField.PRESSURE);var b=read(c.offset(),8L*layout.liquid.cellCount());float[] values=new float[layout.liquid.cellCount()];for(int i=0;i<values.length;i++)values[i]=(float)b.getDouble(8*i);return new mmm.fluid.CondensedPhasePressureField(0,values);}
        mmm.units.SignedDyadic exactThermalInventory(){return dyadic(layout.transport.inventoryPosting.result());}
        boolean gasFlowActive(){var t=layout.transport;if(t==null||t.aperture==null||currentGas)return false;var f=t.aperture.flow;return read(f.requiredPairsOffset(),4).getInt(0)>0;}
        ThermalPosting thermalPosting(){var t=layout.transport;var zero=mmm.units.SignedDyadic.ZERO;return new ThermalPosting(dyadic(t.sourcePosting.result()),dyadic(t.sinkPosting.result()),dyadic(t.mechanicalPosting.result()),t.thermal==null?zero:dyadic(t.thermal.reservoir().result()),t.devices==null?zero:dyadic(t.devices.state.column(VulkanThermalDeviceLayout.Field.FLUID_EXCHANGE)),dyadic(t.roundoff.result()));}
        private mmm.units.SignedDyadic dyadic(VulkanLiquidStateLayout.Column c){var b=read(c.offset(),c.bytes());int sign=b.getInt(0),count=b.getInt(8);var n=java.math.BigInteger.ZERO;for(int i=count-1;i>=0;i--)n=n.shiftLeft(32).add(java.math.BigInteger.valueOf(Integer.toUnsignedLong(b.getInt(16+4*i))));return sign==0?mmm.units.SignedDyadic.ZERO:new mmm.units.SignedDyadic(sign<0?n.negate():n,b.getInt(4));}
        private ByteBuffer read(long offset,long length){var b=ByteBuffer.allocate(Math.toIntExact(length)).order(ByteOrder.LITTLE_ENDIAN);storage.read(capture,offset,b);return b.flip();}
        @Override public void close(){capture.close();}
    }
    final class Prepared implements AutoCloseable {
        private final Capture source;private final PreparedInterval result;private final java.util.Set<Workspace> allocated;private boolean closed;private final RequestInputs inputs;
        private Prepared(Capture source,PreparedInterval result,java.util.Set<Workspace> allocated){this.source=source;this.result=result;this.allocated=allocated;this.inputs=activeInputs==null?source.inputs:activeInputs;}
        mmm.world.MovingBodyFluidCoupling.Result bodyCoupling(){return result.bodyResult;}
        Capture captureState(){synchronized(VulkanLiquidRegion.this){if(closed)throw new IllegalStateException("Prepared region is closed");return new Capture(result.candidate.captureCompleted(),result.workspace,result.currentGas,result.geometryValid,inputs);}}
        int substeps(){return result.substeps;}
        long commit(){synchronized(VulkanLiquidRegion.this){if(closed)throw new IllegalStateException("Prepared region is closed");long generation=result.candidate.commit(source.capture);workspace=result.workspace;currentGasThermal=result.currentGas;currentGeometryValid=result.geometryValid;currentInputs=inputs;return generation;}}
        @Override public void close(){synchronized(VulkanLiquidRegion.this){if(closed)return;closed=true;result.close();source.close();for(var w:allocated)if(w!=workspace)w.templates.close();}}
    }
    synchronized Prepared prepareMechanical(Capture source,VulkanLiquidProjection.Parameters parameters,int capacityRounds){return prepareMechanical(source,parameters,capacityRounds,source.inputs);}
    synchronized Prepared prepareMechanical(Capture source,VulkanLiquidProjection.Parameters parameters,int capacityRounds,RequestInputs request){var previous=activeInputs;activeInputs=request.pendingPhaseSettlementOnly()?request.stationary(source.inputs.bodies()):request;try{return prepareMechanical(source,parameters,false,capacityRounds);}finally{activeInputs=previous;}}
    private synchronized Prepared prepareMechanical(Capture source,VulkanLiquidProjection.Parameters parameters,boolean zeroInitialPressure,int capacityRounds){
        if(!source.capture.isCurrent()||source.layout!=workspace)throw new IllegalStateException("Mechanical request uses a stale region capture");
        var pin=source.retain();var allocated=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Workspace,Boolean>());allocated.add(workspace);
        try{return new Prepared(pin,prepareInterval(pin.capture,pin.layout,pin.currentGas,pin.geometryValid,parameters,zeroInitialPressure,capacityRounds,pin.generation()+1,1,0,false,new long[1],allocated),allocated);}
        catch(RuntimeException|Error failure){pin.close();for(var w:allocated)if(w!=workspace)w.templates.close();throw failure;}
    }
    long advanceThermal(double duration){try(var source=captureState();var prepared=prepareThermal(source,duration)){return prepared.commit();}}
    synchronized Prepared prepareThermal(Capture source,double duration,RequestInputs request){var previous=activeInputs;activeInputs=request;try{return prepareThermal(source,duration);}finally{activeInputs=previous;}}
    synchronized Prepared prepareThermal(Capture source,double duration){
        if(!(Double.isFinite(duration)&&duration>0)||workspace.transport==null||workspace.transport.thermal==null)throw new IllegalArgumentException("Thermal request needs positive duration and configured thermodynamics");
        if(!source.capture.isCurrent()||source.layout!=workspace)throw new IllegalStateException("Thermal request uses a stale region capture");
        var pin=source.retain();var allocated=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Workspace,Boolean>());allocated.add(workspace);Workspace target=workspace;
        try{requestCellSize=thermalConfiguration.cellSize();target=bodyWorkspace(target,pin.inputs.bodies(),allocated);while(true){var candidate=storage.prepareReallocated(pin.capture,target.bytes);
            try{int error=target.executeThermal(pin.layout,pin.currentGas,pin.geometryValid,candidate,duration);if(error!=0)throw new IllegalStateException("Resident thermal candidate error="+error);
                return new Prepared(pin,new PreparedInterval(target,candidate,true,target.transport.reactions==null,1,mmm.world.MovingBodyFluidCoupling.Result.NONE),allocated);
            }catch(VulkanRegionThermal.ContactCapacityGrowth growth){candidate.close();target=target.growThermalContacts(growth.required,growth.baths);allocated.add(target);}
            catch(VulkanRegionReactions.CapacityGrowth growth){candidate.close();target=target.growReactions(growth);allocated.add(target);}
            catch(PhaseGasCapacityGrowth growth){candidate.close();target=target.growPhaseGas(growth);allocated.add(target);}
            catch(RuntimeException|Error failure){candidate.close();throw failure;}
        }}catch(RuntimeException|Error failure){pin.close();for(var w:allocated)if(w!=workspace)w.templates.close();throw failure;}
    }
    private record PreparedInterval(Workspace workspace,VulkanRegionStorage.Candidate candidate,boolean currentGas,boolean geometryValid,int substeps,mmm.world.MovingBodyFluidCoupling.Result bodyResult) implements AutoCloseable{
        @Override public void close(){candidate.close();}
    }
    private static final class MotionTimeRefinement extends IllegalStateException{MotionTimeRefinement(String reason){super(reason);}}
    private static final class PhaseGasCapacityGrowth extends IllegalStateException{final int nodes,groups,parts;PhaseGasCapacityGrowth(int nodes,int groups,int parts){this.nodes=nodes;this.groups=groups;this.parts=parts;}}
    private static final class GranularCapacityGrowth extends IllegalStateException{final int[] required;GranularCapacityGrowth(int[] required){this.required=required;}}
    private static final class ContactCapacityGrowth extends IllegalStateException{final int required;ContactCapacityGrowth(int required){this.required=required;}}
    private PreparedInterval prepareInterval(VulkanRegionStorage.Capture capture,Workspace source,boolean sourceCurrentGas,boolean sourceGeometryValid,VulkanLiquidProjection.Parameters parameters,
            boolean zeroInitialPressure,int capacityRounds,long requestGeneration,long ordinal,int depth,boolean previous,
            long[] spent,java.util.Set<Workspace> allocated){
        return prepareBodyInterval(capture,source,sourceCurrentGas,sourceGeometryValid,parameters,zeroInitialPressure,capacityRounds,requestGeneration,ordinal,depth,previous,spent,allocated,currentInputs.bodies());
    }
    private PreparedInterval prepareBodyInterval(VulkanRegionStorage.Capture capture,Workspace source,boolean sourceCurrentGas,boolean sourceGeometryValid,VulkanLiquidProjection.Parameters parameters,
            boolean zeroInitialPressure,int capacityRounds,long requestGeneration,long ordinal,int depth,boolean previous,long[] spent,java.util.Set<Workspace> allocated,mmm.fluid.EntityBodyState sourceBodies){
        if(parameters!=null)requestCellSize=parameters.cellSize();
        Workspace target=bodyWorkspace(source,sourceBodies,allocated);
        while(true){
            target.sourceBodyState=sourceBodies;
            if(++spent[0]>mmm.fluid.CpuFluidSolverBackend.MAXIMUM_CUMULATIVE_SUBSTEPS)
                throw new mmm.fluid.FluidSolverBackend.MechanicalWorkBudgetExceededException("Resident mechanical request exceeded cumulative substep budget");
            var candidate=storage.prepareReallocated(capture,target.bytes);int[] required=null,granularRequired=null;VulkanRegionReactions.CapacityGrowth reactionGrowth=null;PhaseGasCapacityGrowth phaseGasGrowth=null;int contactRequired=0,thermalRequired=0,thermalBathRequired=0;
            try{
                long sequence=ordinal==1?requestGeneration:Long.rotateLeft(requestGeneration,17)^ordinal;
                int error=target.execute(source,sourceCurrentGas,sourceGeometryValid,candidate,parameters,zeroInitialPressure,capacityRounds,sequence,previous);
                if(error==0)return new PreparedInterval(target,candidate,target.phaseGasCanonical,parameters!=null&&capacityRounds>0&&target.transport!=null&&target.transport.reactions==null,1,target.bodyResult(candidate));
                if(error==64&&target.transport!=null)required=target.transport.requirements(candidate);
                else throw target.failure(candidate,error,parameters);
            }catch(VulkanRegionReactions.CapacityGrowth growth){reactionGrowth=growth;}
            catch(PhaseGasCapacityGrowth growth){phaseGasGrowth=growth;}
            catch(GranularCapacityGrowth growth){granularRequired=growth.required;}
            catch(ContactCapacityGrowth growth){contactRequired=growth.required;}
            catch(VulkanRegionThermal.ContactCapacityGrowth growth){thermalRequired=growth.required;thermalBathRequired=growth.baths;}
            catch(MotionTimeRefinement finer){
                candidate.close();
                if(parameters==null||depth>=mmm.fluid.CpuFluidSolverBackend.MAXIMUM_TIME_REFINEMENTS)throw finer;
                float half=parameters.duration()*.5f;if(!(half>0&&half<parameters.duration()))throw finer;
                var p=new VulkanLiquidProjection.Parameters(parameters.cellSize(),half,parameters.gravity(),parameters.tolerance(),parameters.iterations());
                try(var first=prepareBodyInterval(capture,source,sourceCurrentGas,sourceGeometryValid,p,zeroInitialPressure,capacityRounds,requestGeneration,2*ordinal,depth+1,previous,spent,allocated,sourceBodies);
                    var intermediate=first.candidate.captureCompleted()){
                    var second=new VulkanLiquidProjection.Parameters(parameters.cellSize(),parameters.duration()-half,parameters.gravity(),parameters.tolerance(),parameters.iterations());
                    var tail=prepareBodyInterval(intermediate,first.workspace,first.currentGas,first.geometryValid,second,zeroInitialPressure,capacityRounds,requestGeneration,2*ordinal+1,depth+1,true,spent,allocated,requestedBodies());
                    return new PreparedInterval(tail.workspace,tail.candidate,tail.currentGas,tail.geometryValid,first.substeps+tail.substeps,first.bodyResult.plus(tail.bodyResult));
                }
            }catch(RuntimeException|Error failure){candidate.close();throw failure;}
            candidate.close();Workspace larger=phaseGasGrowth!=null?target.growPhaseGas(phaseGasGrowth):reactionGrowth!=null?target.growReactions(reactionGrowth):granularRequired!=null?target.growGranular(granularRequired):thermalRequired>0||thermalBathRequired>0?target.growThermalContacts(thermalRequired,thermalBathRequired):contactRequired>0?target.growContacts(contactRequired):target.grow(required);allocated.add(larger);
            if(growths!=null)growths.add(new Growth(requestGeneration,target.capacityStage,target.capacities(),larger.capacities(),phaseGasGrowth!=null?new int[]{phaseGasGrowth.nodes,phaseGasGrowth.groups,phaseGasGrowth.parts}:reactionGrowth!=null?new int[]{reactionGrowth.species,reactionGrowth.limbs,reactionGrowth.particles,reactionGrowth.deposits,reactionGrowth.solidSpecies,reactionGrowth.coherentGroups,reactionGrowth.coherentSpecies}:granularRequired!=null?granularRequired:thermalRequired>0||thermalBathRequired>0?new int[]{thermalRequired,thermalBathRequired}:contactRequired>0?new int[]{contactRequired}:required.clone()));
            target=larger;
        }
    }
    synchronized List<mmm.thermal.CoherentPhaseStore.Group> checkpointCoherent(){
        if(workspace.transport==null||workspace.transport.coherent==null)throw new IllegalStateException("No resident coherent inventory");
        var state=workspace.transport.coherent.state;try(var capture=storage.capture()){
            var bytes=ByteBuffer.allocate(Math.toIntExact(state.bytes()));storage.read(capture,state.baseOffset(),bytes);return state.exportState(bytes.flip());
        }
    }
    synchronized VulkanThermalDeviceLayout.State checkpointDevices(){
        if(workspace.transport==null||workspace.transport.devices==null)throw new IllegalStateException("No resident finite thermal devices");
        var state=workspace.transport.devices.state;try(var capture=storage.capture()){
            var bytes=ByteBuffer.allocate(Math.toIntExact(state.bytes()));storage.read(capture,state.baseOffset(),bytes);return state.exportState(bytes.flip());
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
    VulkanRegionStorage.Metrics metrics(){
        var m=storage.metrics();var schur=kernels==null?null:kernels.flowSchurPressure;
        long heatBytes=kernels==null||kernels.thermalKernel==null?0:kernels.thermalKernel.deviceBytes();
        return new VulkanRegionStorage.Metrics(m.submissions(),m.uploadBytes()+(schur==null?0:schur.headerUploadBytes()),m.readbackBytes(),
                m.copiedBytes()+(schur==null?0:schur.graphCopyBytes()),m.gpuNanos(),m.recordingNanos(),m.waitingNanos(),m.allocatedGenerations(),
                m.deviceBytes()+heatBytes+(schur==null?0:schur.scratchBytes()),m.stagingBytes());
    }
    synchronized VulkanPressureKernel.Progress pressureProgress(){return pressureProgress;}
    synchronized int capacityIterations(){return capacityIterationCount;}
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
    record GranularReserves(int particles,int deposits,int species){}
    private record GasReserves(int nodes,int parts,int groups,int pairs) { }
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
        final int thermalContactCapacity,thermalBathCapacity;
        final int bodyCapacity,bodyEntries;
        mmm.fluid.EntityBodyState sourceBodyState=currentInputs.bodies();
        final GranularReserves granularReserves;
        final int coherentSpeciesCapacity;boolean phaseGasCanonical;
        String capacityStage="source geometry";
        mmm.world.MovingBodyFluidCoupling.Result bodyResult(VulkanRegionStorage.Candidate candidate){
            if(transport==null||transport.entity==null||requestedBodies().bodies().isEmpty())return mmm.world.MovingBodyFluidCoupling.Result.NONE;
            var bytes=ByteBuffer.allocate(Math.multiplyExact(64,requestedBodies().bodies().size())).order(ByteOrder.LITTLE_ENDIAN);
            candidate.read(transport.entity.column(VulkanEntityBodyCoupling.Field.BODY_RESULTS).offset(),bytes);
            return transport.entity.result(bytes.flip(),bodyConfiguration(requestedBodies()));
        }
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions) {
            this(liquid,definitions,gasConfiguration==null?null:new GasReserves(gasConfiguration.nodeCapacity(),
                    Math.multiplyExact(gasConfiguration.componentCapacity(),7),Math.min(Math.multiplyExact(liquid.cellCount(),7),Math.multiplyExact(Math.addExact(gasConfiguration.nodeCapacity(),2),7)),Math.max(1,gasConfiguration.nodeCapacity())));
        }
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves) {
            this(liquid,definitions,gasReserves,granularConfiguration==null?0:granularConfiguration.contactCapacity());
        }
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity) {
            this(liquid,definitions,gasReserves,contactCapacity,0);
        }
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity,int thermalContactCapacity) {
            this(liquid,definitions,gasReserves,contactCapacity,thermalContactCapacity,0);
        }
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity,int thermalContactCapacity,int thermalBathCapacity) {
            this(liquid,definitions,gasReserves,contactCapacity,thermalContactCapacity,thermalBathCapacity,initialGranularReserves!=null?initialGranularReserves:granularConfiguration==null?null:new GranularReserves(Math.max(1,2*granularConfiguration.initial().particles().size()),Math.max(1,2*granularConfiguration.initial().deposits().size()),Math.max(1,2*granularConfiguration.speciesCapacity())));
        }
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity,int thermalContactCapacity,int thermalBathCapacity,GranularReserves granularReserves){
            this(liquid,definitions,gasReserves,contactCapacity,thermalContactCapacity,thermalBathCapacity,granularReserves,coherentConfiguration==null?0:coherentConfiguration.speciesCount());
        }
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity,int thermalContactCapacity,int thermalBathCapacity,GranularReserves granularReserves,int coherentSpeciesCapacity){
            this.coherentSpeciesCapacity=coherentSpeciesCapacity;
            bodyCapacity=requestedBodyCapacity;bodyEntries=requestedBodyEntries;
            this.liquid=liquid;this.gasReserves=gasReserves;this.contactCapacity=contactCapacity;this.thermalContactCapacity=thermalContactCapacity;this.thermalBathCapacity=thermalBathCapacity;this.granularReserves=granularReserves;
        int cells = liquid.cellCount(),rows=Math.addExact(Math.addExact(cells,gasReserves==null?0:gasReserves.nodes()),
                granularReserves==null?0:Math.multiplyExact(3,granularReserves.particles()));
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
        transport=definitions==null?null:new Transport(definitions,total);
        if(transport!=null)total=transport.endOffset;
        bytes=total;
        if(bytes>maximumRange)throw new IllegalArgumentException("Resident workspace exceeds device descriptor range");

        }

        int executeReactions(VulkanRegionStorage.Candidate candidate,VulkanGasThermodynamics.Layout sourceEOS,VulkanGasGeometry.Layout previousGeometry,double duration){
            int error=kernels.reactionKernel.execute(candidate,transport.reactions,activeInputs!=null&&activeInputs.phaseChangeEnabled(),activeInputs==null?0:activeInputs.maximumPhaseOperations(),sourceEOS,previousGeometry,duration);
            if(error!=0)return error;
            if(transport.phaseGasTopology==null)return candidate.continueBatch((command,input,output,errors,staging,stack)->{
                transport.recordSolidGeometry(command,output,errors,stack);kernel.rebindRanges(geometryRanges(output,errors,geometry.get(GeometryField.FACES),transport.entity==null?occupied():transport.entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ)));kernel.recordCells(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
            },0,0).error();
            capacityStage="phase gas topology";var phase=transport.phaseGasTopology;
            var status=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                transport.recordSolidGeometry(command,output,errors,stack);
                kernel.rebindRanges(geometryRanges(output,errors,geometry.get(GeometryField.FACES),transport.entity==null?occupied():transport.entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ)));kernel.recordCells(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                kernels.gasThermo.rebindRanges(arena,err);kernels.gasThermo.recordVolumes(command,transport.gas.thermo,previousGeometry);kernels.gasThermo.record(command,transport.gas.thermo);
                kernels.phaseGasTopology.rebindRanges(arena,err);kernels.phaseGasTopology.recordGeometry(command,phase);kernels.phaseGasTopology.recordRemap(command,phase,previousGeometry);
            },phase.remap().requirements().offset(),32);
            if(status.error()==64){var required=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);candidate.readWords(new long[]{phase.requiredNodesOffset(),phase.requiredGroupsOffset(),phase.requiredPartsOffset()},required);throw new PhaseGasCapacityGrowth(required.getInt(0),required.getInt(4),required.getInt(8));}
            if(status.error()!=0)return status.error();
            status=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                transport.gas.state.copyColumns(command,output,phase.future(),output,stack);
                var plan=new VulkanBufferCopyPlan.Builder();transport.gas.geometry.copyDerived(plan,phase.geometry());plan.build().record(command,output,output,stack);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);kernels.gasThermo.rebindRanges(arena,err);kernels.gasThermo.recordVolumes(command,transport.gas.thermo,transport.gas.geometry);kernels.gasThermo.record(command,transport.gas.thermo);
            },0,0);if(status.error()==0)phaseGasCanonical=true;return status.error();
        }
        int executeThermal(Workspace source,boolean sourceCurrentGas,boolean sourceGeometryValid,VulkanRegionStorage.Candidate candidate,double duration){
            boolean reuseGeometry=sourceGeometryValid&&currentInputs.bodies().bodies().isEmpty()&&(activeInputs==null||activeInputs.bodies().bodies().isEmpty());
            var thermal=transport.gas==null?transport.thermal:VulkanRegionThermal.currentGas(transport.thermal,transport.gas.thermo,transport.gas.geometry,liquid.alignment());
            var headers=new ArrayList<HeaderWrite>(source==this?List.<HeaderWrite>of():transport.headers(liquid));
            var contactHeader=new HeaderWrite(thermal.contacts().header.offset(),thermal.contacts().headerBytes());headers.add(contactHeader);
            var status=candidate.beginBatch(headerBytes(headers),(command,input,output,errors,staging,stack)->{
                if(source==this){copyPersistent(command,input,output,stack);writeHeaders(command,output,List.of(contactHeader),stack);}else migrate(command,source,input,output,stack,headers);
                transport.beginDeviceRequest(command,source.transport,input,output,stack,false);transport.recordSolidGeometry(command,output,errors,stack);
                if(reuseGeometry)copySourceGeometry(command,source,sourceCurrentGas,input,output,stack);
                kernel.rebindRanges(geometryRanges(output,errors,geometry.get(GeometryField.FACES)));
                if(reuseGeometry)kernel.recordVelocities(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());else kernel.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                kernels.ledger.rebindRanges(new FlipKernelBindings.BufferRange(input),dst,err);
                if(transport.gas!=null&&!reuseGeometry){kernels.volumeKernel.rebindRanges(new FlipKernelBindings.BufferRange(input),dst,err);kernels.volumeKernel.record(command,transport.volumes);
                    kernels.gasGeometry.rebindRanges(dst,err);kernels.gasThermo.rebindRanges(dst,err);kernels.gasGeometry.recordGeometry(command,transport.gas.geometry);kernels.gasGeometry.recordSourceComponents(command,transport.gas.geometry,transport.gas.state);}
            },0,0);
            if(status.error()!=0)return status.error();
            int error=kernels.thermalKernel.execute(candidate,thermal,duration,source.transport.devices==null?null:source.transport.devices.state,transport.devices==null?null:transport.devices.settlement,false);
            if(error!=0)return error;
            if(transport.reactions!=null&&(activeInputs==null||!activeInputs.pendingPhaseSettlementOnly())){error=executeReactions(candidate,transport.gas==null?null:transport.gas.thermo,transport.gas==null?null:transport.gas.geometry,duration);if(error!=0)return error;}
            var stableHeader=new HeaderWrite(transport.thermal.contacts().header.offset(),transport.thermal.contacts().headerBytes());
            return candidate.finishBatch(stableHeader.bytes().remaining(),(command,input,output,errors,staging,stack)->{
                kernels.ledger.record(command,thermal.reservoir());kernels.ledger.record(command,transport.thermalOnlyRoundoff);kernels.ledger.record(command,transport.thermalOnlyMechanical);kernels.ledger.record(command,transport.inventoryPosting);kernels.ledger.record(command,transport.sourcePosting);kernels.ledger.record(command,transport.sinkPosting);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                copyColumn(command,output,transport.thermalOnlyRoundoff.result(),output,transport.roundoff.result(),stack);
                copyColumn(command,output,transport.thermalOnlyMechanical.result(),output,transport.mechanicalPosting.result(),stack);
                for(var entry:List.of(transport.heat))vkCmdFillBuffer(command,output.handle(),entry.result().offset(),entry.result().bytes(),0);
                writeHeaders(command,output,List.of(stableHeader),stack);
            });
        }
        int execute(Workspace source,boolean sourceCurrentGas,boolean sourceGeometryValid,VulkanRegionStorage.Candidate candidate,VulkanLiquidProjection.Parameters parameters,
                    boolean zeroInitialPressure,int capacityRounds,long step,boolean accumulateLedger) {
            boolean migrating=source!=this;
            int firstPressureBatch=parameters==null?0:Math.min(parameters.iterations(),Math.max(16,((previousPressureIterations+15)/16)*16));
            // A completed successor already contains the next source PLIC and chamber CSR.
            // Inventory-writing stages and moving solids rebuild these fields below.
            boolean reuseGeometry=sourceGeometryValid&&(transport==null||transport.dynamics==null)
                    &&currentInputs.bodies().bodies().isEmpty()&&(activeInputs==null||activeInputs.bodies().bodies().isEmpty());
            var sourceHeaders=migrating?transport.headers(source.liquid):List.<HeaderWrite>of();
            var stableHeaders=migrating?transport.headers(liquid):List.<HeaderWrite>of();
            long uploadedBytes=headerBytes(sourceHeaders);
            var gas=transport==null?null:transport.gas;
            int pressureRows=pressureColumns.get(PressureField.ROWS).count();
            VulkanPressureKernel.PressureOperator baseOperator=transport!=null&&transport.dynamics!=null?
                    new VulkanCoupledPressure.Operator(kernels.coupledKernel,transport.dynamics.coupled):gas==null?null:new VulkanPressureKernel.GasOperator(kernels.gasPressure,gas.operator);
            VulkanRegionStorage.ComputeCommands prepare = (command, input, output, errors, staging, stack) -> {
                int sx=liquid.sizeX(),sy=liquid.sizeY(),sz=liquid.sizeZ();
                if((gas==null||reuseGeometry)&&kernels!=null&&kernels.timings!=null)kernels.timings.reset(command);
                if(gas==null||reuseGeometry) {
                    Runnable copy=()->{if(migrating)migrate(command,source,input,output,stack,sourceHeaders);else copyPersistent(command,input,output,stack);};
                    if(kernels==null)copy.run();else kernels.timed(command,"source-state-copy",copy);
                    if(transport!=null){transport.beginDeviceRequest(command,source.transport,input,output,stack,accumulateLedger);transport.recordSolidGeometry(command,output,errors,stack);}
                    if(reuseGeometry)kernels.timed(command,"source-derived-copy",()->copySourceGeometry(command,source,sourceCurrentGas,input,output,stack));
                }
                kernel.rebindRanges(geometryRanges(output,errors,geometry.get(GeometryField.FACES)));
                if(reuseGeometry)kernel.recordVelocities(command,sx,sy,sz);
                else if(gas==null)kernel.record(command, sx, sy, sz);
                if(gas!=null) {
                    var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                    kernels.gasGeometry.rebindRanges(dst,err);kernels.gasThermo.rebindRanges(dst,err);kernels.gasPressure.rebindRanges(dst,err);
                    if(!reuseGeometry)kernels.timed(command,"old-gas-components",()->kernels.gasGeometry.recordSourceComponents(command,gas.geometry,gas.state));
                    kernels.timed(command,"old-gas-volumes",()->kernels.gasThermo.recordVolumes(command,gas.thermo,gas.geometry));
                    kernels.timed(command,"old-gas-eos",()->kernels.gasThermo.record(command,gas.thermo));
                }
                if(parameters!=null) {
                    if(transport!=null) {
                        var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                        kernels.liquidThermalKernel.rebindRanges(dst,err);kernels.viscosityKernel.rebindRanges(src,dst,err);kernels.forceKernel.rebindRanges(dst,err);kernels.surfaceKernel.rebindRanges(dst,err);
                        transport.recordBodyContacts(command,output,errors);
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
                    Runnable assemble=()->projection.prepare(command,sx,sy,sz,parameters,gas==null?null:kernels.gasPressure,gas==null?null:gas.operator,transport==null||transport.dynamics!=null?null:()->transport.recordBodyPressure(command,parameters.duration()));
                    if(kernels==null)assemble.run();else kernels.timed(command,"common-pressure-assemble",assemble);
                    if(transport!=null&&transport.dynamics!=null){kernels.granularPressureKernel.recordIncidence(command,transport.dynamics.pressure,parameters.cellSize(),parameters.duration(),parameters.gravity());
                        transport.recordBodyPressure(command,parameters.duration());
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
                    Runnable initialize=()->pressure.recordInitialize(command,pressureRows,pressureRows,parameters.iterations(),multigrid,hierarchy,baseOperator);
                    Runnable firstBatch=()->recordPressureIterations(command,output,errors,parameters.iterations(),firstPressureBatch,baseOperator);
                    if(kernels==null){initialize.run();firstBatch.run();}else{
                        kernels.timed(command,"common-pressure-initialize",initialize);
                        kernels.timed(command,"common-pressure-first-batch",firstBatch);
                    }
                }
            };
            long[] stageMark=Boolean.getBoolean("mmm.gpuMechanicalStages")?new long[]{storage.metrics().gpuNanos(),System.nanoTime()}:null;
            int error;
            if(gas!=null&&!reuseGeometry) {
                capacityStage="source gas geometry/EOS";
                if(parameters!=null&&Double.compare(parameters.cellSize(),gasConfiguration.cellSize())!=0)throw new IllegalArgumentException("Gas and mechanical cell sizes differ");
                var pre=candidate.beginBatch(uploadedBytes,(command,input,output,errors,staging,stack)-> {
                    if(kernels.timings!=null)kernels.timings.reset(command);
                    kernels.timed(command,"source-state-copy",()->{if(migrating)migrate(command,source,input,output,stack,sourceHeaders);else copyPersistent(command,input,output,stack);});
                    transport.beginDeviceRequest(command,source.transport,input,output,stack,accumulateLedger);
                    transport.recordSolidGeometry(command,output,errors,stack);
                    kernel.rebindRanges(geometryRanges(output,errors,geometry.get(GeometryField.FACES)));
                    kernel.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                    var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                    if(transport.dynamics!=null&&parameters!=null){
                        kernels.liquidThermalKernel.rebindRanges(dst,err);kernels.liquidThermalKernel.record(command,transport.sourceThermal);
                        kernels.viscosityKernel.rebindRanges(src,dst,err);kernels.viscosityKernel.recordMaterial(command,transport.viscosity,parameters.cellSize());
                        kernels.transitionKernel.rebindRanges(dst,err);kernels.transitionKernel.record(command,transport.dynamics.transitions,output,parameters.cellSize(),parameters.duration(),parameters.gravity());
                        if(transport.entity!=null){
                            kernels.entityKernel.rebindRanges(dst,err);
                            kernels.entityKernel.recordCombinedOccupancy(command,transport.sourceEntity,parameters.cellSize());
                            kernels.entityKernel.recordCombinedOccupancy(command,transport.entity,parameters.cellSize());
                        }
                        kernel.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                    }
                    kernels.volumeKernel.rebindRanges(src,dst,err);kernels.volumeKernel.record(command,transport.volumes);
                    kernels.gasGeometry.rebindRanges(dst,err);kernels.gasThermo.rebindRanges(dst,err);kernels.gasPressure.rebindRanges(dst,err);
                    kernels.timed(command,"old-gas-geometry",()->kernels.gasGeometry.recordGeometry(command,gas.geometry));
                },transport.dynamics!=null&&parameters!=null?transport.dynamics.transitions.column(VulkanGranularTransitions.Field.CONTROL).offset():0,transport.dynamics!=null&&parameters!=null?12:0);
                if(transport.dynamics!=null&&parameters!=null&&pre.error()==64){
                    int[] required={pre.feedback().getInt(0),pre.feedback().getInt(4),pre.feedback().getInt(8)};
                    if(required[0]>granularReserves.particles()||required[1]>granularReserves.deposits()||required[2]>granularReserves.species())throw new GranularCapacityGrowth(required);
                }
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
                                if(kernels!=null)kernels.timed(command,"common-pressure-next-batch",continuation);else continuation.run();}, runOffset, feedbackBytes);
                    scheduled += batch;
                }
                if(result.error()==0&&result.feedback().getDouble(runFeedbackOffset)==0.0)previousPressureIterations=(int)result.feedback().getDouble(0);
                if(pressureDiagnostics)pressureProgress=VulkanPressureKernel.Progress.read(result.feedback());
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
                        if(transport!=null&&transport.entity!=null)kernels.entityKernel.recordResult(command,transport.entity,parameters.cellSize(),parameters.duration());
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
                        capacityIterationCount=rounds;error=capacityResult.error();traceStage("project-capacity",stageMark);
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
                                if(transport.reactions!=null){var flags=transport.reactions.progress().column(VulkanReactionProgressLayout.Field.PHASE_MOTION_CELLS);VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);vkCmdFillBuffer(command,output.handle(),flags.offset(),flags.bytes(),0);var summary=transport.reactions.progress().column(VulkanReactionProgressLayout.Field.PHASE_PENDING_SUMMARY);vkCmdFillBuffer(command,output.handle(),summary.offset(),summary.bytes(),0);VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}
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
                                boolean react=transport.reactions!=null&&(activeInputs==null||!activeInputs.pendingPhaseSettlementOnly());
                                VulkanRegionStorage.ComputeCommands finishLedgers=(command,input,output,errors,staging,stack)-> {
                                    transport.recordLedgers(command,accumulateLedger?source.transport:null);if(migrating)writeHeaders(command,output,stableHeaders,stack);
                                };
                                if(transport.aperture!=null)error=transport.aperture.execute(candidate,parameters.duration(),step,finishTransport);
                                else if(transport.thermal==null)error=candidate.continueBatch(finishTransport,0,0).error();
                                traceStage("transport-energy-aperture",stageMark);
                                boolean thermalFinishes=transport.thermal!=null&&!react;
                                if(error==0&&transport.thermal!=null){capacityStage="thermal contacts/conduction";
                                    error=kernels.thermalKernel.execute(candidate,transport.thermal,parameters.duration(),
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
            if(error==0&&kernels!=null&&kernels.timings!=null)for(var sample:kernels.timings.read())System.out.printf("mechanical_kernel=%s gpu_ms=%.3f%n",sample.stage(),sample.milliseconds());return error;
        }
        void recordPressureIterations(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,int maximum,int count,VulkanPressureKernel.PressureOperator gasOperator) {
            int rows=pressureColumns.get(PressureField.ROWS).count();
            // Reuse the same compiled command blocks when batching predicted work.
            // Converged stages retain the device's zero-work dispatches.
            for(int remaining=count;remaining>0;){
                int block=Math.min(16,remaining);remaining-=block;
                templates.record(command,new VulkanCommandTemplates.Key(0,output,errors,block,maximum,gasOperator==null?null:gasOperator.preconditionerKey()),
                        secondary->pressure.recordIterations(secondary,rows,rows,maximum,multigrid,hierarchy,block,gasOperator));
            }
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
                var failure=new IllegalStateException("Resident "+(aperture?"gas aperture":"liquid")+" pressure failed: iterations="+(int)diagnostics.getDouble(0)+", residualRms="+diagnostics.getDouble(8)
                        +", target="+(aperture?1e-8:parameters.tolerance())+", status="+error+", inner="+pressureProgress);
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
        Workspace grow(int[] required) {
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
            return new Workspace(new VulkanLiquidStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),stocks,species,limbs,liquid.alignment(),liquid.materialCount()),
                    definitions.capacities(stocks,species,limbs,windowLimbs),nextGas,contactCapacity,thermalContactCapacity,thermalBathCapacity,granularReserves,coherentSpeciesCapacity);
        }
        Workspace growContacts(int required){return new Workspace(liquid,transport.definitions,gasReserves,growCount(contactCapacity,required),thermalContactCapacity,thermalBathCapacity,granularReserves,coherentSpeciesCapacity);}
        Workspace growThermalContacts(int required,int requiredBaths){
            if(required<=thermalContactCapacity&&requiredBaths<=thermalBathCapacity)throw new IllegalStateException("Thermal capacity feedback did not grow either column");
            return new Workspace(liquid,transport.definitions,gasReserves,contactCapacity,growCount(thermalContactCapacity,required),growCount(thermalBathCapacity,requiredBaths),granularReserves,coherentSpeciesCapacity);
        }
        Workspace growReactions(VulkanRegionReactions.CapacityGrowth required){
            int coherentSpecies=growCount(coherentSpeciesCapacity,required.coherentSpecies);
            int species=growCount(liquid.speciesCapacity(),required.species),limbs=growCount(liquid.limbCapacity(),required.limbs);
            var next=granularReserves==null?null:new GranularReserves(growCount(granularReserves.particles(),required.particles),growCount(granularReserves.deposits(),required.deposits),growCount(granularReserves.species(),required.solidSpecies));
            if(species==liquid.speciesCapacity()&&limbs==liquid.limbCapacity()&&java.util.Objects.equals(next,granularReserves)&&coherentSpecies==coherentSpeciesCapacity)throw new IllegalStateException("Reaction feedback did not grow inventory capacity");
            return new Workspace(new VulkanLiquidStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),liquid.stockCapacity(),species,limbs,liquid.alignment(),liquid.materialCount()),
                    transport.definitions.capacities(liquid.stockCapacity(),species,limbs,transport.definitions.scratchLimbsPerWindow()),gasReserves,contactCapacity,thermalContactCapacity,thermalBathCapacity,next,coherentSpecies);
        }
        Workspace growPhaseGas(PhaseGasCapacityGrowth required){
            var next=new GasReserves(growGas(gasReserves.nodes(),required.nodes),growGas(gasReserves.parts(),required.parts),growGas(gasReserves.groups(),required.groups),gasReserves.pairs());
            if(next.equals(gasReserves))throw new IllegalStateException("Phase gas feedback did not grow capacity");
            return new Workspace(liquid,transport.definitions,next,contactCapacity,thermalContactCapacity,thermalBathCapacity,granularReserves,coherentSpeciesCapacity);
        }
        Workspace growGranular(int[] required){var r=granularReserves;var next=new GranularReserves(growCount(r.particles(),required[0]),growCount(r.deposits(),required[1]),growCount(r.species(),required[2]));
            if(next.equals(r))throw new IllegalStateException("Granular lifecycle feedback did not grow inventory");return new Workspace(liquid,transport.definitions,gasReserves,contactCapacity,thermalContactCapacity,thermalBathCapacity,next,coherentSpeciesCapacity);}
        /** Candidate scratch is produced by its kernels. Persist inventory, constitutive tables, headers and warm caches only. */
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
        void copySourceGeometry(org.lwjgl.vulkan.VkCommandBuffer command,Workspace source,boolean sourceCurrentGas,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            var plan=new VulkanBufferCopyPlan.Builder();
            for(var f:new GeometryField[]{GeometryField.CELLS,GeometryField.PLANES,GeometryField.LIQUID_MICRO})plan.copy(source.geometry.get(f),geometry.get(f));
            var completedFaces=sourceCurrentGas?source.geometry.get(GeometryField.FACES):source.transport.futureFaces;
            plan.copy(completedFaces,geometry.get(GeometryField.FACES));
            if(transport!=null&&transport.gas!=null){var old=source.transport;transport.gas.geometry.copyDerived(plan,sourceCurrentGas?old.gas.geometry:old.successor.geometry);}
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
            plan.build().record(command,input,output,stack);
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
    private ArrayList<FlipKernelBindings.BufferRange> geometryRanges(GpuBuffer buffer,GpuBuffer errors,VulkanLiquidStateLayout.Column faces) {
        return geometryRanges(buffer,errors,faces,occupied());
    }
    private ArrayList<FlipKernelBindings.BufferRange> geometryRanges(GpuBuffer buffer,GpuBuffer errors,VulkanLiquidStateLayout.Column faces,VulkanLiquidStateLayout.Column solid) {
        var ranges=new ArrayList<FlipKernelBindings.BufferRange>(14);
        ranges.addAll(liquid.ranges(buffer,CELL_OFFSETS,NUMERIC_VOLUMES,MASS_UNITS));
        ranges.add(range(buffer,GeometryField.FIXED_MASKS));ranges.add(new FlipKernelBindings.BufferRange(buffer,solid.offset(),solid.bindingBytes()));
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
            viscosity=new VulkanLiquidViscosity.Layout(liquid,geometry.get(GeometryField.CELLS),geometry.get(GeometryField.FACES),geometry.get(GeometryField.FIXED_MASKS),word(sourceThermal.cellTemperatures()),-1,
                    definitions.viscosity(),entity==null?null:entity.prescribedContacts(),definitions.noSlip(),entity==null?sourceThermal.endOffset():entity.endOffset(),liquid.alignment());
            forces=VulkanLiquidForces.layout(liquid,definitions.forces(),word(sourceThermal.cellTemperatures()),word(geometry.get(GeometryField.FACES)),-1,viscosity.endOffset(),liquid.alignment());
            surface=VulkanSurfaceTension.layout(liquid,definitions.surface(),word(geometry.get(GeometryField.CELLS)),word(geometry.get(GeometryField.FACES)),word(geometry.get(GeometryField.PLANES)),
                    word(geometry.get(GeometryField.FIXED_MASKS)),-1,forces.endOffset(),liquid.alignment());
            coherent=coherentConfiguration==null?null:new CoherentSection(surface.endOffset());
            devices=deviceConfiguration==null?null:new DeviceSection(coherent==null?surface.endOffset():coherent.endOffset);
            long solidsBase=devices!=null?devices.endOffset:coherent!=null?coherent.endOffset:surface.endOffset();
            granular=granularConfiguration==null?null:new GranularSection(solidsBase);
            if(entity!=null){var baseSolid=granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ);sourceEntity.bindBaseSolid(baseSolid);entity.bindBaseSolid(baseSolid);entity.bindViscosity(viscosity);}
            gas=gasConfiguration==null?null:new GasSection(volumes,granular==null?solidsBase:granular.endOffset,
                    sourceEntity!=null?sourceEntity.word(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):granular==null?word(geometry.get(GeometryField.OCCUPIED_SOLID)):granular.geometry.word(VulkanGranularGeometry.Field.OCCUPIED_SVQ));
            dynamics=granular==null?null:new GranularDynamics(granular,gas,viscosity.column(VulkanLiquidViscosity.Field.MU),entity==null?null:entity.column(VulkanEntityBodyCoupling.Field.FACE_AREA),gas.endOffset);
            if(entity!=null&&gas!=null)entity.bindPressure(gas.operator,dynamics==null?null:dynamics.coupled);
            if(entity!=null&&gas==null)entity.bindPressure(new VulkanGasPressure.PressureBuffers(pressureColumns.get(PressureField.STARTS),pressureColumns.get(PressureField.COLUMNS),pressureColumns.get(PressureField.VALUES),pressureColumns.get(PressureField.ROWS),pressureColumns.get(PressureField.PRESSURE),pressureColumns.get(PressureField.WORK),pressureColumns.get(PressureField.META),pressureColumns.get(PressureField.PROBLEM),pressureColumns.get(PressureField.CONTROL)));
            capacity=new VulkanLiquidCapacityLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gas==null?0:gas.state.nodeCapacity(),dynamics!=null?dynamics.endOffset:gas==null?solidsBase:gas.endOffset,liquid.alignment());
            capacityInputs=VulkanLiquidCapacityInputs.layout(volumes,capacity,word(geometry.get(GeometryField.FIXED_MASKS)),
                    word(entity!=null?entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ)),word(geometry.get(GeometryField.FACES)),word(geometry.get(GeometryField.FLUXES)),
                    gas==null?null:gas.geometry,gas==null?null:gas.state,capacity.endOffset(),liquid.alignment());
            capacitySolve=new VulkanLiquidCapacity.Layout(capacity,capacityInputs.endOffset(),liquid.alignment());
            accepted=VulkanLiquidAcceptedVolumes.layout(volumes,capacity.word(VulkanLiquidCapacityLayout.Field.ACCEPTED),
                    capacitySolve.endOffset(),liquid.alignment(),Math.addExact(volumes.limbCapacity(),Math.multiplyExact(cells,14)));
            materials=VulkanLiquidMaterialRoutes.layout(liquid,routes,word(accepted.descriptors()),word(phaseTable),-1,accepted.sourceCellVolumes().quantumExponent(),
                    Math.multiplyExact(7*stocks+3*cells,definitions.scratchLimbsPerWindow()),accepted.endOffset(),liquid.alignment());
            quantities=VulkanLiquidRouteQuantities.layout(liquid,routes,word(materialReference),word(speciesReference),definitions.speciesStride(),materials.endOffset(),liquid.alignment());
            gather=VulkanLiquidGather.layout(liquid,liquid,routes,word(geometry.get(GeometryField.FIXED_MASKS)),word(massTable),quantities.endOffset(),liquid.alignment());
            heatTransport=VulkanMechanicalHeatTransport.layout(liquid,liquid,routes,word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_HEAT)),gather.endOffset(),liquid.alignment());
            momentum=VulkanLiquidMomentum.layout(liquid,liquid,routes,word(geometry.get(GeometryField.FACES)),word(futureFaces),-1,heatTransport.endOffset(),liquid.alignment());
            liquidThermal=VulkanLiquidThermodynamics.layout(liquid,definitions.thermal(),word(materialReference),word(speciesReference),definitions.speciesStride(),momentum.endOffset(),liquid.alignment());
            successor=gas==null?null:new GasSuccessor(gas,accepted,liquidThermal.endOffset(),word(futureFaces),entity==null?gas.geometry.occupiedWordOffset():entity.word(VulkanEntityBodyCoupling.Field.COMBINED_SVQ));
            bodies=VulkanMechanicalBodies.layout(liquid,routes,word(geometry.get(GeometryField.FACES)),gas==null?-1:word(gas.geometry.cellNodes()),
                    gas==null?0:gas.state.nodeCapacity(),gas==null?-1:Math.toIntExact(gas.state.column(VulkanGasStateLayout.Field.COUNTS).offset()/4),
                    dynamics==null?null:new VulkanMechanicalBodies.GranularLinks(granular.state,dynamics.pressure,dynamics.contacts.word(VulkanGranularContacts.Field.CONTACT_COMPONENT)),
                    successor==null?liquidThermal.endOffset():successor.endOffset,liquid.alignment());
            gasHeat=successor==null||gasConfiguration.tables().isothermal()?null:VulkanGasMechanicalHeat.layout(successor.work,bodies.endOffset(),liquid.alignment());
            var mechanicalEnergy=VulkanMechanicalEnergy.layout(bodies,momentum,liquidThermal,successor==null?-1:word(successor.work.workBySource()),
                    word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_HEAT)),entity==null?word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_WORK)):entity.word(VulkanEntityBodyCoupling.Field.SOURCE_WORK),
                    dynamics==null?null:new VulkanMechanicalEnergy.GranularEnergy(granular.thermal,granular.geometry,-1,dynamics.contacts.word(VulkanGranularContacts.Field.PARTICLE_EXTERNAL_WORK)),(gasHeat==null?bodies.endOffset():gasHeat.endOffset()),liquid.alignment()).withSourceFaceK(dynamics==null?-1:word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_FACE_K)));
            if(entity!=null)mechanicalEnergy=mechanicalEnergy.withGasExternalWork(entity.word(VulkanEntityBodyCoupling.Field.GAS_EXTERNAL_WORK));
            energy=gasHeat==null?mechanicalEnergy:mechanicalEnergy.withGasEnergy(gasHeat);
            aperture=successor==null?null:new GasAperture(gas,successor,energy.endOffset());
            long thermalBase=aperture==null?energy.endOffset():aperture.endOffset;
            if(thermalConfiguration==null){bathTemperatures=null;thermal=null;}
            else{long[] tail={thermalBase};bathTemperatures=column(tail,thermalConfiguration.bathTemperatures().length,8);
                var inputs=new VulkanThermalContacts.Inputs(liquid,liquidThermal,gas==null?null:gas.state,aperture==null?null:aperture.eos,
                        successor==null?null:successor.geometry,granular==null?null:granular.state,granular==null?null:granular.thermal,
                        coherent==null?null:coherent.state,coherent==null?null:coherent.thermal,devices==null?null:devices.state,devices==null?null:devices.thermal,
                        word(geometry.get(GeometryField.FIXED_MASKS)),bathTemperatures.count()==0?-1:word(bathTemperatures),-1,thermalConfiguration.cellSize());
                thermal=VulkanRegionThermal.layout(inputs,thermalConfiguration,thermalContactCapacity,thermalBathCapacity,tail[0],liquid.alignment());thermalBase=thermal.endOffset();}
            VulkanRegionReactions.SolidInputs solidReactions=null;
            if(reactionConfiguration!=null&&granular!=null){
                var phaseTransitions=new VulkanGranularTransitions.Layout(granular.state,granular.geometry,geometry.get(GeometryField.CELLS),geometry.get(GeometryField.FACES),liquid,
                        geometry.get(GeometryField.FIXED_MASKS),viscosity.column(VulkanLiquidViscosity.Field.MU),thermalBase,liquid.alignment());
                solidReactions=new VulkanRegionReactions.SolidInputs(phaseTransitions,granular.thermal,granularConfiguration.registry(),thermalConfiguration.cellSize(),word(geometry.get(GeometryField.FACES)));
                thermalBase=phaseTransitions.endOffset();
            }
            reactions=reactionConfiguration==null?null:VulkanRegionReactions.layout(reactionConfiguration,liquid,liquidThermal,solidReactions,gas==null?null:new VulkanGasPhaseChange.Inputs(aperture==null?gas.thermo:aperture.eos,successor==null?gas.geometry:successor.geometry,liquidThermal,reactionConfiguration.gravity()),coherent==null?null:new VulkanCondensedPhaseChange.Inputs(liquid,coherent.state,liquidThermal,coherent.thermal,null,null,word(gas==null?pressureColumns.get(PressureField.PRESSURE):gas.operator.column(VulkanGasPressure.Field.CELL_PHYSICAL_PRESSURE)),word(geometry.get(GeometryField.FIXED_MASKS)),word(entity!=null?entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ)),thermalConfiguration.cellSize(),reactionConfiguration.gravity()),thermalBase,liquid.alignment());
            if(reactions!=null)thermalBase=reactions.endOffset();
            if(gas!=null&&reactions!=null){
                if(reactions.gasPhase()!=null)phaseBirth=reactions.gasPhase().column(VulkanGasPhaseChange.Field.BIRTH_NODE);
                else{phaseBirth=new VulkanLiquidStateLayout.Column(align(thermalBase),cells,4);thermalBase=phaseBirth.offset()+phaseBirth.bindingBytes();}
                phaseGasTopology=VulkanPhaseGasTopology.layout(gas.thermo,gas.geometry,word(entity!=null?entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ)),word(geometry.get(GeometryField.CELLS)),word(geometry.get(GeometryField.PLANES)),word(geometry.get(GeometryField.LIQUID_MICRO)),word(phaseBirth),gasConfiguration.ambient(),gasReserves.nodes(),gasReserves.groups(),gasReserves.parts(),thermalBase,liquid.alignment());thermalBase=phaseGasTopology.endOffset();
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
                writer.put(state.baseOffset(),data);thermalTables(writer,granular.thermal);
                writer.put(granular.materialReference,granularConfiguration.materialReferences());writer.put(granular.speciesReference,granularConfiguration.speciesReferences());
                var ids=state.definitions().materialIds();double[] diameters=new double[ids.size()],contact=new double[3*ids.size()];
                for(int i=0;i<ids.size();i++){var m=granularConfiguration.registry().material(granularConfiguration.registry().materialHandle(ids.get(i)));
                    diameters[i]=m.primaryGrainDiameterMeters();contact[3*i]=m.restitution();contact[3*i+1]=m.staticFriction();contact[3*i+2]=m.dynamicFriction();}
                writer.put(dynamics.pressure.column(VulkanGranularPressure.Field.DIAMETERS),diameters);writer.put(dynamics.motion.columns.get(VulkanGranularMotion.Field.MATERIAL),contact);writer.put(dynamics.transitions.column(VulkanGranularTransitions.Field.MATERIALS).offset(),dynamics.transitions.materialBytes(granularConfiguration.registry()));
            }
        }
        void recordInitialize(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer input,GpuBuffer output,GpuBuffer errors,int maxRounds) {
            var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            kernels.volumeKernel.rebindRanges(src,dst,err);kernels.inputKernel.rebindRanges(dst,err);kernels.capacityKernel.rebindRanges(dst,err);
            kernels.acceptedKernel.rebindRanges(dst,err);kernels.materialKernel.rebindRanges(src,dst,err);kernels.quantityKernel.rebindRanges(src,dst,err);
            kernels.gatherKernel.rebindRanges(src,dst,err);kernels.momentumKernel.rebindRanges(src,dst,err);kernels.ledger.rebindRanges(src,dst,err);
            kernels.liquidThermalKernel.rebindRanges(dst,err);kernels.heatTransportKernel.rebindRanges(src,dst,err);kernels.bodiesKernel.rebindRanges(src,dst,err);kernels.energyKernel.rebindRanges(src,dst,err);if(gasHeat!=null)kernels.gasHeatKernel.rebindRanges(dst,err);
            kernels.futureGeometry.rebindRanges(geometryRanges(output,errors,futureFaces,entity==null?occupied():entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ)));
            if(successor!=null)successor.bind(output,errors);
            kernels.volumeKernel.record(command,volumes);
            if(dynamics==null&&entity==null)kernels.inputKernel.record(command,capacityInputs);else kernels.inputKernel.recordDisplacement(command,capacityInputs);
            kernels.capacityKernel.recordInitialize(command,capacitySolve,maxRounds);
            recordCapacityIterations(command,output,errors,Math.min(32,maxRounds),3);
        }
        void beginDeviceRequest(org.lwjgl.vulkan.VkCommandBuffer command,Transport old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack,boolean continuation){
            if(reactions!=null)kernels.reactionKernel.recordClearPostings(command,reactions,output);
            Workspace.this.phaseGasCanonical=false;
            if(phaseGasTopology!=null)kernels.phaseGasTopology.recordClearPostings(command,phaseGasTopology,output);
            var clears=new ArrayList<VulkanLiquidStateLayout.Column>();if(phaseBirth!=null)clears.add(phaseBirth);
            if(successor!=null)clears.add(successor.remap.column(VulkanGasRemap.Field.AUDIT_ENERGY));if(aperture!=null)clears.add(aperture.remap.column(VulkanGasRemap.Field.AUDIT_ENERGY));
            if(!clears.isEmpty()){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);for(var column:clears)vkCmdFillBuffer(command,output.handle(),column.offset(),column.bytes(),0);VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}
            if(devices!=null&&!continuation){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);devices.state.recordBeginRequest(command,input,old.devices.state,output,stack);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}
        }
        void recordSolidGeometry(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,org.lwjgl.system.MemoryStack stack){
            if(coherent!=null){kernels.coherentGeometryKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));kernels.coherentGeometryKernel.record(command,coherent.geometry);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                copyColumn(command,output,coherent.geometry.occupiedSvq(),output,geometry.get(GeometryField.OCCUPIED_SOLID),stack);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}
            if(granular!=null){bindGranular(output,errors);kernels.granularGeometryKernel.recordOccupancy(command,granular.geometry);}
            if(entity!=null){
                kernels.entityKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                kernels.entityKernel.recordInputs(command,sourceEntity,output,bodyConfiguration(sourceBodyState));
                kernels.entityKernel.recordInputs(command,entity,output,bodyConfiguration(requestedBodies()));
                kernels.entityKernel.recordOccupancy(command,sourceEntity,requestCellSize);kernels.entityKernel.recordOccupancy(command,entity,requestCellSize);
            }
        }
        void recordBodyContacts(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors){if(entity!=null){
            kernels.entityKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
            kernels.entityKernel.recordContacts(command,entity,requestCellSize);if(dynamics==null)kernels.entityKernel.recordApertures(command,entity,requestCellSize);
        }}
        void recordBodyPressure(org.lwjgl.vulkan.VkCommandBuffer command,double duration){if(entity!=null)kernels.entityKernel.recordPressure(command,entity,requestCellSize,duration);}
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
            if(entity!=null)kernels.entityKernel.recordCombinedOccupancy(command,entity,parameters.cellSize());
        }
        void recordFinish(org.lwjgl.vulkan.VkCommandBuffer command,int gravitySign) {
            kernels.capacityKernel.recordFinish(command,capacitySolve);
            kernels.timed(command,"accepted-volumes",()->kernels.acceptedKernel.record(command,accepted));kernels.timed(command,"material-routes",()->kernels.materialKernel.record(command,materials,gravitySign,kernels.timings));
            kernels.timed(command,"quantities",()->kernels.quantityKernel.record(command,quantities));kernels.timed(command,"gather",()->kernels.gatherKernel.record(command,gather));kernels.timed(command,"viscous-heat-transport",()->kernels.heatTransportKernel.record(command,heatTransport));kernels.timed(command,"momentum-transport",()->kernels.momentumKernel.recordTransport(command,momentum));
            kernels.timed(command,"future-liquid-geometry",()->{
                if(successor==null)kernels.futureGeometry.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                else kernels.futureGeometry.recordCells(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
            });
            if(successor!=null)successor.recordGeometry(command);
            kernels.timed(command,"momentum-resolve",()->kernels.momentumKernel.recordResolve(command,momentum));kernels.timed(command,"mixing-heat",()->kernels.momentumKernel.recordHeatPosting(command,momentum));
            kernels.timed(command,"body-init",()->kernels.bodiesKernel.recordInitialize(command,bodies));kernels.timed(command,"body-connectivity",()->kernels.bodiesKernel.recordConnectivity(command,bodies));
        }
        void recordEnergy(org.lwjgl.vulkan.VkCommandBuffer command,VulkanLiquidProjection.Parameters parameters) {
            if(successor!=null)successor.recordRemapPrepare(command);
            if(gasHeat!=null)kernels.timed(command,"gas-mechanical-caloric",()->kernels.gasHeatKernel.recordPrepare(command,gasHeat));
            kernels.timed(command,"body-csr",()->kernels.bodiesKernel.recordMembers(command,bodies));kernels.timed(command,"liquid-thermal",()->kernels.liquidThermalKernel.record(command,liquidThermal));
            if(granular!=null)kernels.timed(command,"granular-thermal",()->kernels.granularThermalKernel.record(command,granular.thermal));
            kernels.timed(command,"common-energy",()->kernels.energyKernel.record(command,energy,parameters.cellSize(),parameters.gravity()));
            if(gasHeat!=null)kernels.timed(command,"gas-mechanical-temperature",()->kernels.gasHeatKernel.recordRefresh(command,gasHeat));
            if(successor!=null)successor.recordRemapFinish(command);
        }
        void recordLedgers(org.lwjgl.vulkan.VkCommandBuffer command,Transport previous) {
            kernels.timed(command,"roundoff-ledger",()->kernels.ledger.record(command,roundoff));kernels.timed(command,"heat-ledger",()->kernels.ledger.record(command,heat));
            if(previous!=null){kernels.ledger.recordAccumulate(command,roundoff,word(previous.roundoff.result()));kernels.ledger.recordAccumulate(command,heat,word(previous.heat.result()));}
            kernels.ledger.record(command,inventoryPosting);kernels.ledger.record(command,mechanicalPosting);kernels.ledger.record(command,sourcePosting);kernels.ledger.record(command,sinkPosting);
            if(previous!=null){kernels.ledger.recordAccumulate(command,mechanicalPosting,word(previous.mechanicalPosting.result()));kernels.ledger.recordAccumulate(command,sourcePosting,word(previous.sourcePosting.result()));kernels.ledger.recordAccumulate(command,sinkPosting,word(previous.sinkPosting.result()));}
            if(thermal!=null){kernels.ledger.record(command,thermal.reservoir());if(previous!=null)kernels.ledger.recordAccumulate(command,thermal.reservoir(),word(previous.thermal.reservoir().result()));}
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
    private final class CoherentSection {
        final VulkanCoherentStateLayout state;
        final VulkanCoherentGeometry.Layout geometry;
        final VulkanLiquidStateLayout.Column materialReference,speciesReference;
        final VulkanLiquidThermodynamics.Layout thermal;
        final long endOffset;
        CoherentSection(long base){var c=coherentConfiguration;long a=liquid.alignment();
            state=new VulkanCoherentStateLayout(liquid.cellCount(),Math.max(c.initial().size(),Math.multiplyExact(liquid.cellCount(),c.definitions().phaseIds().size())),coherentSpeciesCapacity,c.definitions(),base,a);
            geometry=VulkanCoherentGeometry.layout(state,state.endOffset(),a);
            materialReference=new VulkanLiquidStateLayout.Column(align(geometry.endOffset()),c.materialReferences().length,8);
            speciesReference=new VulkanLiquidStateLayout.Column(align(materialReference.offset()+materialReference.bindingBytes()),c.speciesReferences().length,8);
            thermal=VulkanLiquidThermodynamics.layout(state,c.thermal(),word(materialReference),word(speciesReference),c.speciesStride(),speciesReference.offset()+speciesReference.bindingBytes(),a);endOffset=thermal.endOffset();
        }
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(geometry.header().offset(),geometry.headerBytes()),new HeaderWrite(thermal.header().offset(),thermal.headerBytes()));}
        List<VulkanLiquidStateLayout.Column> persistentColumns(){return List.of(geometry.header(),materialReference,speciesReference,thermal.header(),thermal.offsets(),thermal.upper(),thermal.capacity(),thermal.referenceTemperature(),thermal.coldSensible());}
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,CoherentSection old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            state.copyColumns(command,input,old.state,output,stack);var from=old.persistentColumns();var to=persistentColumns();for(int i=1;i<from.size();i++)if(i!=3)copyColumn(command,input,from.get(i),output,to.get(i),stack);
        }
    }
    private final class DeviceSection {
        final VulkanThermalDeviceLayout state;
        final VulkanLiquidStateLayout.Column materialReference,speciesReference;
        final VulkanLiquidThermodynamics.Layout thermal;
        final VulkanThermalDeviceLayout.Settlement settlement;
        final long endOffset;
        DeviceSection(long base){var c=deviceConfiguration;long a=liquid.alignment();
            state=new VulkanThermalDeviceLayout(liquid.cellCount(),c.initial().inventory().devices().size(),c.initial().ports().size(),c.definitions(),base,a);
            materialReference=new VulkanLiquidStateLayout.Column(align(state.endOffset()),c.materialReferences().length,8);
            speciesReference=new VulkanLiquidStateLayout.Column(align(materialReference.offset()+materialReference.bindingBytes()),c.materialReferences().length,8);
            thermal=VulkanLiquidThermodynamics.layout(state,c.thermal(),word(materialReference),word(speciesReference),1,speciesReference.offset()+speciesReference.bindingBytes(),a);
            settlement=state.settlement(thermal.endOffset(),a);endOffset=settlement.endOffset();
        }
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(thermal.header().offset(),thermal.headerBytes()),new HeaderWrite(settlement.exchange().header().offset(),settlement.exchange().headerBytes()),new HeaderWrite(settlement.cumulative().header().offset(),settlement.cumulative().headerBytes()));}
        List<VulkanLiquidStateLayout.Column> persistentColumns(){return List.of(materialReference,speciesReference,thermal.header(),thermal.offsets(),thermal.upper(),thermal.capacity(),thermal.referenceTemperature(),thermal.coldSensible(),settlement.exchange().header(),settlement.cumulative().header());}
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,DeviceSection old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            state.copyColumns(command,input,old.state,output,stack);var from=old.persistentColumns();var to=persistentColumns();for(int i=0;i<8;i++)if(i!=2)copyColumn(command,input,from.get(i),output,to.get(i),stack);
        }
    }
    private final class GranularSection {
        final VulkanGranularStateLayout state;
        final VulkanGranularGeometry.Layout geometry;
        final VulkanLiquidStateLayout.Column materialReference,speciesReference;
        final VulkanLiquidThermodynamics.Layout thermal;
        final long endOffset;
        GranularSection(long base){var config=granularConfiguration;long a=liquid.alignment();
            state=new VulkanGranularStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),granularReserves.particles(),granularReserves.deposits(),granularReserves.species(),config.initial().definitions(),base,a);
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
            copyColumn(command,input,oldWorkspaceDynamics(old).transitions.column(VulkanGranularTransitions.Field.MATERIALS),output,transport.dynamics.transitions.column(VulkanGranularTransitions.Field.MATERIALS),stack);
        }
        private GranularDynamics oldWorkspaceDynamics(GranularSection value){return value.owner().transport.dynamics;}
        private Workspace owner(){return Workspace.this;}
    }
    private final class GranularDynamics {
        final VulkanCoupledPressure.Layout coupled;
        final VulkanGranularPressure.Layout pressure;
        final VulkanGranularContacts.Layout contacts;
        final VulkanGranularMotion.Layout motion;
        final VulkanGranularTransitions.Layout transitions;
        final long endOffset;
        GranularDynamics(GranularSection granular,GasSection gas,VulkanLiquidStateLayout.Column viscosity,VulkanLiquidStateLayout.Column bodyFaceAreas,long base){int particles=granular.state.particleCapacity(),faces=gas.geometry.faces();long a=liquid.alignment();
            coupled=new VulkanCoupledPressure.Layout(liquid.cellCount(),pressureColumns.get(PressureField.ROWS).count(),faces+3*particles,2*faces+75*particles,gas.operator.pressureBuffers(),base,a);
            pressure=new VulkanGranularPressure.Layout(granular.geometry,gas.operator,coupled,geometry.get(GeometryField.FIXED_MASKS),viscosity,bodyFaceAreas,coupled.endOffset(),a);
            contacts=new VulkanGranularContacts.Layout(granular.state,pressure,contactCapacity,pressure.endOffset(),a);
            motion=new VulkanGranularMotion.Layout(granular.state,contacts,pressure,word(geometry.get(GeometryField.FIXED_MASKS)),contacts.endOffset(),a);motion.bindBedGeometry(granular.geometry);transitions=new VulkanGranularTransitions.Layout(granular.state,granular.geometry,gas.operator,liquid,geometry.get(GeometryField.FIXED_MASKS),viscosity,motion.endOffset(),a);endOffset=transitions.endOffset();
        }
        List<VulkanLiquidStateLayout.Column> persistentColumns(){return List.of(new VulkanLiquidStateLayout.Column(4L*coupled.headerWordOffset(),64,4),new VulkanLiquidStateLayout.Column(4L*pressure.headerWordOffset(),96,4),
                pressure.column(VulkanGranularPressure.Field.DIAMETERS),contacts.header(),motion.header,motion.columns.get(VulkanGranularMotion.Field.MATERIAL),transitions.header,transitions.column(VulkanGranularTransitions.Field.MATERIALS));}
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(4L*coupled.headerWordOffset(),coupled.headerBytes()),new HeaderWrite(4L*pressure.headerWordOffset(),pressure.headerBytes()),
                new HeaderWrite(contacts.header().offset(),contacts.headerBytes()),new HeaderWrite(motion.header.offset(),motion.headerBytes()),new HeaderWrite(transitions.header.offset(),transitions.headerBytes()));}
    }
    private final class GasAperture {
        final GasSection gas;
        final GasSuccessor successor;
        final VulkanGasThermodynamics.Layout eos,retainedEOS;
        final VulkanGasFlow.Layout flow;
        final VulkanGasFlowSchurPressure.Layout schurPressure;
        final VulkanGasRemap.Layout remap;
        final VulkanGasAdiabaticWork.Layout work;
        final long endOffset;
        GasAperture(GasSection gas,GasSuccessor successor,long base){
            this.gas=gas;this.successor=successor;long a=liquid.alignment();
            var volumes=new VulkanLiquidStateLayout.Column(align(base),gasReserves.nodes(),8);
            eos=VulkanGasThermodynamics.layout(gas.state,gasConfiguration.tables(),word(volumes),volumes.offset()+volumes.bindingBytes(),a);
            flow=VulkanGasFlow.layout(eos,successor.geometry,gasReserves.pairs(),eos.endOffset(),a);
            schurPressure=VulkanGasFlowSchurPressure.layout(flow,flow.endOffset(),a);
            remap=VulkanGasRemap.transportLayout(flow.routes(),eos,successor.retained,successor.future,gasConfiguration.ambient(),flow.word(VulkanGasFlow.Field.STEP),
                    gasReserves.parts(),schurPressure.endOffset(),a);
            retainedEOS=VulkanGasThermodynamics.selfPartsLayout(remap,gasConfiguration.tables(),flow.word(VulkanGasFlow.Field.RETAINED_VOLUME),remap.endOffset(),a);
            work=VulkanGasAdiabaticWork.layout(retainedEOS,eos.nodeVolumesWordOffset(),flow.word(VulkanGasFlow.Field.MODES),retainedEOS.endOffset(),a);endOffset=work.endOffset();
        }
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(eos.header().offset(),eos.headerBytes()),new HeaderWrite(flow.header.offset(),flow.headerBytes()),new HeaderWrite(schurPressure.header().offset(),schurPressure.headerBytes()),
                new HeaderWrite(remap.header.offset(),remap.headerBytes()),new HeaderWrite(retainedEOS.header().offset(),retainedEOS.headerBytes()),new HeaderWrite(work.header().offset(),work.headerBytes()));}
        List<VulkanLiquidStateLayout.Column> persistentColumns(){
            var columns=new ArrayList<VulkanLiquidStateLayout.Column>();transport.addGasTables(columns,eos);transport.addGasTables(columns,retainedEOS);
            columns.add(flow.header);columns.add(schurPressure.header());columns.add(remap.header);columns.add(work.header());
            for(var field:VulkanGasRemap.Field.values())if((field.name().startsWith("AMBIENT_")&&field!=VulkanGasRemap.Field.AMBIENT_BEFORE&&field!=VulkanGasRemap.Field.AMBIENT_AFTER||field==VulkanGasRemap.Field.DECLARED_SPECIES_OFFSETS||field==VulkanGasRemap.Field.DECLARED_SPECIES_HANDLES))columns.add(remap.column(field));return columns;
        }
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,GasAperture old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            var from=old.persistentColumns();var to=persistentColumns();var headerOffsets=headers().stream().map(HeaderWrite::offset).toList();
            for(int i=0;i<from.size();i++)if(!headerOffsets.contains(to.get(i).offset()))copyColumn(command,input,from.get(i),output,to.get(i),stack);
        }
        int execute(VulkanRegionStorage.Candidate candidate,double duration,long step,VulkanRegionStorage.ComputeCommands prefix){
            var q=VulkanGasFlow.parameters(duration,.6,gasConfiguration.ambient(),step);int n=flow.nodes;
            capacityStage="gas aperture assemble";
            var batch=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                prefix.record(command,input,output,errors,staging,stack);
                var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                kernels.flowKernel.rebindRanges(dst,err);kernels.flowEOS.rebindRanges(dst,err);kernels.flowRemap.rebindRanges(dst,err);kernels.flowRetainedEOS.rebindRanges(dst,err);kernels.flowWork.rebindRanges(dst,err);
                kernels.flowSchurPressure.rebindRanges(dst,err,schurPressure);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                for(var field:new VulkanGasRemap.Field[]{VulkanGasRemap.Field.ENERGY_ROUNDOFF,VulkanGasRemap.Field.SOURCE_ROUNDOFF,VulkanGasRemap.Field.RETAINED_ROUNDOFF,VulkanGasRemap.Field.MERGE_ROUNDOFF,VulkanGasRemap.Field.AUDIT_ENERGY}){
                    var c=remap.column(field);vkCmdFillBuffer(command,output.handle(),c.offset(),c.bindingBytes(),0);
                }
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                kernels.timed(command,"aperture-eos",()->{kernels.flowEOS.recordVolumes(command,eos,successor.geometry);kernels.flowEOS.record(command,eos);});
                kernels.timed(command,"aperture-assemble",()->kernels.flowKernel.recordAssemble(command,flow,q));
            },flow.requiredPairsOffset(),4);
            if(batch.error()!=0)return batch.error();if(batch.feedback().getInt(0)==0)return 0;
            capacityStage="gas aperture multi-layer Schur";
            return kernels.flowSchurPressure.execute(candidate,schurPressure,q,kernels::timed,(command,input,output,errors,staging,stack)->{
                kernels.flowKernel.recordRoutes(command,flow,q);
                kernels.timed(command,"aperture-integer-transport",()->kernels.flowRemap.recordPrepare(command,remap));
                kernels.flowRemap.recordTransportVolumes(command,remap,flow);kernels.flowRetainedEOS.record(command,retainedEOS);kernels.flowWork.record(command,work);
                kernels.timed(command,"aperture-energy-gather",()->kernels.flowRemap.recordFinish(command,remap,work,kernels.timings));
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                gas.state.copyColumns(command,output,successor.future,output,stack);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                if(gasConfiguration.tables().isothermal()){kernels.flowEOS.recordVolumes(command,eos,successor.geometry);kernels.flowEOS.record(command,eos);}
            });
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
        GasSuccessor(GasSection old,VulkanLiquidAcceptedVolumes.Layout accepted,long base,int liquidFacesWordOffset,int occupiedWord) {
            this.old=old;var config=gasConfiguration;long a=liquid.alignment();
            totals=VulkanLiquidCellVolumes.layout(liquid,Math.addExact(liquid.limbCapacity(),liquid.cellCount()),accepted.sourceCellVolumes().quantumExponent(),base,a);
            geometry=VulkanGasGeometry.layout(totals,word(Workspace.this.geometry.get(GeometryField.FIXED_MASKS)),occupiedWord,
                    word(Workspace.this.geometry.get(GeometryField.CELLS)),word(Workspace.this.geometry.get(GeometryField.PLANES)),word(Workspace.this.geometry.get(GeometryField.LIQUID_MICRO)),config.sealedExterior(),config.cellSize(),totals.endOffset(),a).withLiquidFaces(liquidFacesWordOffset);
            retained=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gasReserves.nodes(),config.tables().definitions(),geometry.endOffset(),a);
            future=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gasReserves.nodes(),config.tables().definitions(),retained.endOffset(),a);
            lineage=VulkanGasLineage.layout(old.state,old.geometry,geometry,accepted,word(Workspace.this.geometry.get(GeometryField.FLUXES)),word(old.volumes),gasReserves.groups(),future.endOffset(),a);
            remap=VulkanGasRemap.layout(lineage,old.thermo,retained,future,config.ambient(),gasReserves.parts(),lineage.endOffset(),a);
            retainedEOS=VulkanGasThermodynamics.layout(retained,config.tables(),lineage.word(VulkanGasLineage.Field.RETAINED_OLD_VOLUMES),remap.endOffset(),a);
            work=VulkanGasAdiabaticWork.layout(retainedEOS,lineage.word(VulkanGasLineage.Field.FUTURE_CONTROL_VOLUMES),old.operator.word(VulkanGasPressure.Field.MECHANICAL_MODE),retainedEOS.endOffset(),a);endOffset=work.endOffset();
        }
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(totals.header().offset(),totals.headerBytes()),new HeaderWrite(geometry.header().offset(),geometry.headerBytes()),
                new HeaderWrite(4L*lineage.headerWordOffset(),lineage.headerBytes()),new HeaderWrite(4L*remap.headerWordOffset(),remap.headerBytes()),
                new HeaderWrite(retainedEOS.header().offset(),retainedEOS.headerBytes()),new HeaderWrite(work.header().offset(),work.headerBytes()));}
        void bind(GpuBuffer output,GpuBuffer errors){var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            kernels.futureVolumeKernel.rebindRanges(arena,arena,err);kernels.futureGasGeometry.rebindRanges(arena,err);kernels.lineageKernel.rebindRanges(arena,err);
            kernels.remapKernel.rebindRanges(arena,err);kernels.retainedEOS.rebindRanges(arena,err);kernels.gasWork.rebindRanges(arena,err);}
        void recordGeometry(org.lwjgl.vulkan.VkCommandBuffer command){kernels.timed(command,"future-exact-volumes",()->kernels.futureVolumeKernel.record(command,totals));kernels.timed(command,"future-gas-geometry",()->kernels.futureGasGeometry.recordGeometry(command,geometry));kernels.timed(command,"future-gas-connectivity",()->kernels.futureGasGeometry.recordConnectivity(command,geometry));}
        void recordRemapPrepare(org.lwjgl.vulkan.VkCommandBuffer command){kernels.timed(command,"future-gas-components",()->kernels.futureGasGeometry.recordComponents(command,geometry));kernels.timed(command,"gas-lineage",()->kernels.lineageKernel.record(command,lineage));
            kernels.timed(command,"gas-remap-prepare",()->kernels.remapKernel.recordPrepare(command,remap));kernels.timed(command,"retained-gas-eos",()->kernels.retainedEOS.record(command,retainedEOS));kernels.timed(command,"gas-work",()->kernels.gasWork.record(command,work));}
        void recordRemapFinish(org.lwjgl.vulkan.VkCommandBuffer command){kernels.timed(command,"gas-remap-finish",()->kernels.remapKernel.recordFinish(command,remap,work,kernels.timings));}
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,GasSuccessor previous,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            var from=new VulkanLiquidStateLayout.Column[]{previous.retainedEOS.segmentOffsets(),previous.retainedEOS.segmentUpper(),previous.retainedEOS.segmentHeatCapacity(),previous.retainedEOS.specificAnchorEnergy(),previous.retainedEOS.speciesReference()};
            var to=new VulkanLiquidStateLayout.Column[]{retainedEOS.segmentOffsets(),retainedEOS.segmentUpper(),retainedEOS.segmentHeatCapacity(),retainedEOS.specificAnchorEnergy(),retainedEOS.speciesReference()};
            for(int i=0;i<from.length;i++)copyColumn(command,input,from[i],output,to[i],stack);
            for(var field:VulkanGasRemap.Field.values())if((field.name().startsWith("AMBIENT_")&&field!=VulkanGasRemap.Field.AMBIENT_BEFORE&&field!=VulkanGasRemap.Field.AMBIENT_AFTER||field==VulkanGasRemap.Field.DECLARED_SPECIES_OFFSETS||field==VulkanGasRemap.Field.DECLARED_SPECIES_HANDLES))
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
            state=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gasReserves.nodes(),config.tables().definitions(),geometry.endOffset(),alignment);
            volumes=new VulkanLiquidStateLayout.Column(align(state.endOffset()),gasReserves.nodes(),8);
            thermo=VulkanGasThermodynamics.layout(state,config.tables(),word(volumes),volumes.offset()+volumes.bindingBytes(),alignment);
            operator=new VulkanGasPressure.Layout(thermo,geometry,Workspace.this.geometry.get(GeometryField.CELLS),Workspace.this.geometry.get(GeometryField.FACES),
                    new VulkanGasPressure.PressureBuffers(pressureColumns.get(PressureField.STARTS),pressureColumns.get(PressureField.COLUMNS),pressureColumns.get(PressureField.VALUES),
                            pressureColumns.get(PressureField.ROWS),pressureColumns.get(PressureField.PRESSURE),pressureColumns.get(PressureField.WORK),pressureColumns.get(PressureField.META),
                            pressureColumns.get(PressureField.PROBLEM),pressureColumns.get(PressureField.CONTROL)),granularReserves==null?0:3*granularReserves.particles(),thermo.endOffset(),alignment);endOffset=operator.endOffset();
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
    private VulkanLiquidStateLayout.Column occupied(){return transport!=null&&transport.sourceEntity!=null?transport.sourceEntity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ):transport!=null&&transport.granular!=null?transport.granular.geometry.column(VulkanGranularGeometry.Field.OCCUPIED_SVQ):geometry.get(GeometryField.OCCUPIED_SOLID);}
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
        VulkanEntityBodyCoupling entityKernel;
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
        VulkanGasMechanicalHeat gasHeatKernel;
        VulkanGasFlow flowKernel;
        VulkanGasFlowSchurPressure flowSchurPressure;
        VulkanGasThermodynamics flowEOS,flowRetainedEOS;
        VulkanGasRemap flowRemap;
        VulkanGasAdiabaticWork flowWork;
        VulkanStageTimings timings;
        VulkanGranularGeometry granularGeometryKernel;
        VulkanGranularPressure granularPressureKernel;
        VulkanCoupledPressure coupledKernel;
        VulkanGranularContacts contactKernel;
        VulkanGranularMotion motionKernel;
        VulkanGranularTransitions transitionKernel;
        VulkanLiquidThermodynamics granularThermalKernel;
        VulkanRegionThermal thermalKernel;
        VulkanCoherentGeometry coherentGeometryKernel;
        VulkanRegionReactions reactionKernel;VulkanPhaseGasTopology phaseGasTopology;

        Kernels(VulkanDeviceContext context,boolean gasEnabled,boolean granularEnabled,boolean thermalEnabled,boolean coherentEnabled,boolean reactionEnabled) {
            try {
                if(Boolean.getBoolean("mmm.gpuMechanicalStages"))timings=own(new VulkanStageTimings(context,128));
                futureGeometry=own(new VulkanLiquidGeometry(context));volumeKernel=own(new VulkanLiquidCellVolumes(context));
                inputKernel=own(new VulkanLiquidCapacityInputs(context));capacityKernel=own(new VulkanLiquidCapacity(context));
                acceptedKernel=own(new VulkanLiquidAcceptedVolumes(context));materialKernel=own(new VulkanLiquidMaterialRoutes(context));
                quantityKernel=own(new VulkanLiquidRouteQuantities(context));gatherKernel=own(new VulkanLiquidGather(context));
                momentumKernel=own(new VulkanLiquidMomentum(context));liquidThermalKernel=own(new VulkanLiquidThermodynamics(context));ledger=own(new VulkanEnergyLedger(context));
                viscosityKernel=own(new VulkanLiquidViscosity(context));entityKernel=own(new VulkanEntityBodyCoupling(context));forceKernel=own(new VulkanLiquidForces(context));surfaceKernel=own(new VulkanSurfaceTension(context));heatTransportKernel=own(new VulkanMechanicalHeatTransport(context));
                bodiesKernel=own(new VulkanMechanicalBodies(context));energyKernel=own(new VulkanMechanicalEnergy(context));
                if(reactionEnabled){reactionKernel=own(new VulkanRegionReactions(context));if(gasEnabled)phaseGasTopology=own(new VulkanPhaseGasTopology(context));}
                if(coherentEnabled)coherentGeometryKernel=own(new VulkanCoherentGeometry(context));
                if(thermalEnabled)thermalKernel=own(new VulkanRegionThermal(context));
                gasGeometry=own(new VulkanGasGeometry(context));gasThermo=own(new VulkanGasThermodynamics(context));gasPressure=own(new VulkanGasPressure(context));
                if(gasEnabled){futureVolumeKernel=own(new VulkanLiquidCellVolumes(context));futureGasGeometry=own(new VulkanGasGeometry(context));lineageKernel=own(new VulkanGasLineage(context));
                    // The pending graph experiment is not part of the resident execution chain.
                    remapKernel=own(new VulkanGasRemap(context));retainedEOS=own(new VulkanGasThermodynamics(context));gasWork=own(new VulkanGasAdiabaticWork(context));gasHeatKernel=own(new VulkanGasMechanicalHeat(context));
                    flowKernel=own(new VulkanGasFlow(context));flowSchurPressure=own(new VulkanGasFlowSchurPressure(context));flowEOS=own(new VulkanGasThermodynamics(context));
                    flowRetainedEOS=own(new VulkanGasThermodynamics(context));flowRemap=own(new VulkanGasRemap(context));flowWork=own(new VulkanGasAdiabaticWork(context));}
                if(granularEnabled){granularGeometryKernel=own(new VulkanGranularGeometry(context));granularPressureKernel=own(new VulkanGranularPressure(context));coupledKernel=own(new VulkanCoupledPressure(context));
                    contactKernel=own(new VulkanGranularContacts(context));motionKernel=own(new VulkanGranularMotion(context));transitionKernel=own(new VulkanGranularTransitions(context));granularThermalKernel=own(new VulkanLiquidThermodynamics(context));}
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
