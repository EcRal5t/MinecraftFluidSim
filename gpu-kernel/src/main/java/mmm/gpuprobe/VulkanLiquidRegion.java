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
    private boolean currentGasThermal;
    private boolean currentGeometryValid;
    private RequestInputs currentInputs=new RequestInputs(new mmm.fluid.EntityBodyState(List.of()),false,0);
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
                        definitions.speciesCapacity(),definitions.limbCapacity(),context.storageOffsetAlignment());
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
    /** Explicit performance diagnostics, called outside continuous-step timing. */
    void dumpGasFlowGraph(java.nio.file.Path path,double duration){
        try(var source=captureState()){
            var aperture=source.layout.transport.aperture;
            if(aperture==null)throw new IllegalStateException("No finite aperture graph");
            var flow=aperture.flow;
            int n=source.read(aperture.eos.gas().column(VulkanGasStateLayout.Field.COUNTS).offset(),4).getInt(0);
            int pairs=source.read(flow.requiredPairsOffset(),4).getInt(0);
            var bytes=ByteBuffer.allocate(Math.toIntExact(24L+36L*n+16L*pairs)).order(ByteOrder.LITTLE_ENDIAN);
            bytes.put("MMMGAS01".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(n).putInt(pairs).putDouble(duration);
            for(var field:new VulkanGasFlow.Field[]{VulkanGasFlow.Field.STORAGE,VulkanGasFlow.Field.DIAGONAL_ROOT})bytes.put(source.read(flow.column(field).offset(),8L*n));
            bytes.put(source.read(flow.column(VulkanGasFlow.Field.PROBLEM).offset(),8L*n));
            bytes.put(source.read(flow.column(VulkanGasFlow.Field.PROBLEM).offset()+16L*flow.nodes,8L*n));
            bytes.put(source.read(aperture.eos.gas().column(VulkanGasStateLayout.Field.TOPOLOGY_KEYS).offset(),4L*n));
            for(var field:new VulkanGasFlow.Field[]{VulkanGasFlow.Field.PAIR_A,VulkanGasFlow.Field.PAIR_B,VulkanGasFlow.Field.PAIR_G})bytes.put(source.read(flow.column(field).offset(),field.width*(long)pairs));
            bytes.flip();try(var file=java.nio.channels.FileChannel.open(path,java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,java.nio.file.StandardOpenOption.WRITE)){while(bytes.hasRemaining())file.write(bytes);}
        }catch(java.io.IOException failure){throw new IllegalStateException("Writing aperture performance graph failed",failure);}
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
            var s=layout.transport.gas.state;var local=new VulkanGasStateLayout(s.sizeX(),s.sizeY(),s.sizeZ(),s.nodeCapacity(),s.componentCapacity(),s.speciesCapacity(),s.definitions(),8);
            var b=ByteBuffer.allocate(Math.toIntExact(local.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            for(var f:VulkanGasStateLayout.Field.values()){var c=s.column(f);if(c.bytes()>0)storage.read(capture,c.offset(),b.slice(Math.toIntExact(local.column(f).offset()),Math.toIntExact(c.bytes())));}
            return local.exportState(b);
        }
        mmm.fluid.EntityBodyState entityBodies(){return inputs.bodies();}
        RequestInputs requestInputs(){return inputs;}
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
        try{while(true){var candidate=storage.prepareReallocated(pin.capture,target.bytes);
            try{int error=target.executeThermal(pin.layout,pin.currentGas,pin.geometryValid,candidate,duration);if(error!=0)throw new IllegalStateException("Resident thermal candidate error="+error);
                return new Prepared(pin,new PreparedInterval(target,candidate,true,target.transport.reactions==null,1),allocated);
            }catch(VulkanRegionThermal.ContactCapacityGrowth growth){candidate.close();target=target.growThermalContacts(growth.required,growth.baths);allocated.add(target);}
            catch(VulkanRegionReactions.CapacityGrowth growth){candidate.close();target=target.growReactionSpecies(growth.species);allocated.add(target);}
            catch(RuntimeException|Error failure){candidate.close();throw failure;}
        }}catch(RuntimeException|Error failure){pin.close();for(var w:allocated)if(w!=workspace)w.templates.close();throw failure;}
    }
    private record PreparedInterval(Workspace workspace,VulkanRegionStorage.Candidate candidate,boolean currentGas,boolean geometryValid,int substeps) implements AutoCloseable{
        @Override public void close(){candidate.close();}
    }
    private static final class MotionTimeRefinement extends IllegalStateException{MotionTimeRefinement(String reason){super(reason);}}
    private static final class GranularCapacityGrowth extends IllegalStateException{final int[] required;GranularCapacityGrowth(int[] required){this.required=required;}}
    private static final class ContactCapacityGrowth extends IllegalStateException{final int required;ContactCapacityGrowth(int required){this.required=required;}}
    private PreparedInterval prepareInterval(VulkanRegionStorage.Capture capture,Workspace source,boolean sourceCurrentGas,boolean sourceGeometryValid,VulkanLiquidProjection.Parameters parameters,
            boolean zeroInitialPressure,int capacityRounds,long requestGeneration,long ordinal,int depth,boolean previous,
            long[] spent,java.util.Set<Workspace> allocated){
        Workspace target=source;
        while(true){
            if(++spent[0]>mmm.fluid.CpuFluidSolverBackend.MAXIMUM_CUMULATIVE_SUBSTEPS)
                throw new mmm.fluid.FluidSolverBackend.MechanicalWorkBudgetExceededException("Resident mechanical request exceeded cumulative substep budget");
            var candidate=storage.prepareReallocated(capture,target.bytes);int[] required=null,granularRequired=null;int reactionSpeciesRequired=0;int contactRequired=0,thermalRequired=0,thermalBathRequired=0;
            try{
                long sequence=ordinal==1?requestGeneration:Long.rotateLeft(requestGeneration,17)^ordinal;
                int error=target.execute(source,sourceCurrentGas,sourceGeometryValid,candidate,parameters,zeroInitialPressure,capacityRounds,sequence,previous);
                if(error==0)return new PreparedInterval(target,candidate,false,parameters!=null&&capacityRounds>0&&target.transport!=null&&target.transport.reactions==null,1);
                if(error==64&&target.transport!=null)required=target.transport.requirements(candidate);
                else throw target.failure(candidate,error,parameters);
            }catch(VulkanRegionReactions.CapacityGrowth growth){reactionSpeciesRequired=growth.species;}
            catch(GranularCapacityGrowth growth){granularRequired=growth.required;}
            catch(ContactCapacityGrowth growth){contactRequired=growth.required;}
            catch(VulkanRegionThermal.ContactCapacityGrowth growth){thermalRequired=growth.required;thermalBathRequired=growth.baths;}
            catch(MotionTimeRefinement finer){
                candidate.close();
                if(parameters==null||depth>=mmm.fluid.CpuFluidSolverBackend.MAXIMUM_TIME_REFINEMENTS)throw finer;
                float half=parameters.duration()*.5f;if(!(half>0&&half<parameters.duration()))throw finer;
                var p=new VulkanLiquidProjection.Parameters(parameters.cellSize(),half,parameters.gravity(),parameters.tolerance(),parameters.iterations());
                try(var first=prepareInterval(capture,source,sourceCurrentGas,sourceGeometryValid,p,zeroInitialPressure,capacityRounds,requestGeneration,2*ordinal,depth+1,previous,spent,allocated);
                    var intermediate=first.candidate.captureCompleted()){
                    var second=new VulkanLiquidProjection.Parameters(parameters.cellSize(),parameters.duration()-half,parameters.gravity(),parameters.tolerance(),parameters.iterations());
                    var tail=prepareInterval(intermediate,first.workspace,first.currentGas,first.geometryValid,second,zeroInitialPressure,capacityRounds,requestGeneration,2*ordinal+1,depth+1,true,spent,allocated);
                    return new PreparedInterval(tail.workspace,tail.candidate,tail.currentGas,tail.geometryValid,first.substeps+tail.substeps);
                }
            }catch(RuntimeException|Error failure){candidate.close();throw failure;}
            candidate.close();Workspace larger=reactionSpeciesRequired>0?target.growReactionSpecies(reactionSpeciesRequired):granularRequired!=null?target.growGranular(granularRequired):thermalRequired>0||thermalBathRequired>0?target.growThermalContacts(thermalRequired,thermalBathRequired):contactRequired>0?target.growContacts(contactRequired):target.grow(required);allocated.add(larger);
            if(growths!=null)growths.add(new Growth(requestGeneration,target.capacityStage,target.capacities(),larger.capacities(),reactionSpeciesRequired>0?new int[]{reactionSpeciesRequired}:granularRequired!=null?granularRequired:thermalRequired>0||thermalBathRequired>0?new int[]{thermalRequired,thermalBathRequired}:contactRequired>0?new int[]{contactRequired}:required.clone()));
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
    record GranularReserves(int particles,int deposits,int species){}
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
        final int thermalContactCapacity,thermalBathCapacity;
        final GranularReserves granularReserves;
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
            this(liquid,definitions,gasReserves,contactCapacity,thermalContactCapacity,0);
        }
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity,int thermalContactCapacity,int thermalBathCapacity) {
            this(liquid,definitions,gasReserves,contactCapacity,thermalContactCapacity,thermalBathCapacity,initialGranularReserves!=null?initialGranularReserves:granularConfiguration==null?null:new GranularReserves(Math.max(1,2*granularConfiguration.initial().particles().size()),Math.max(1,2*granularConfiguration.initial().deposits().size()),Math.max(1,2*granularConfiguration.speciesCapacity())));
        }
        Workspace(VulkanLiquidStateLayout liquid,MechanicalDefinitions definitions,GasReserves gasReserves,int contactCapacity,int thermalContactCapacity,int thermalBathCapacity,GranularReserves granularReserves){
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
                if(reuseGeometry)kernel.recordFaces(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());else kernel.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                kernels.ledger.rebindRanges(new FlipKernelBindings.BufferRange(input),dst,err);
                if(transport.gas!=null&&!reuseGeometry){kernels.volumeKernel.rebindRanges(new FlipKernelBindings.BufferRange(input),dst,err);kernels.volumeKernel.record(command,transport.volumes);
                    kernels.gasGeometry.rebindRanges(dst,err);kernels.gasThermo.rebindRanges(dst,err);kernels.gasGeometry.recordGeometry(command,transport.gas.geometry);kernels.gasGeometry.recordConnectivity(command,transport.gas.geometry,4);}
            },transport.gas==null||reuseGeometry?0:transport.gas.geometry.changed().offset(),transport.gas==null||reuseGeometry?0:4);
            int rounds=4;
            while(status.error()==0&&transport.gas!=null&&!reuseGeometry&&status.feedback().getInt(0)!=0){
                if(rounds>=liquid.cellCount())throw new IllegalStateException("Thermal gas connectivity did not converge");
                status=candidate.continueBatch((command,input,output,errors,staging,stack)->kernels.gasGeometry.recordConnectivity(command,transport.gas.geometry,4),transport.gas.geometry.changed().offset(),4);rounds+=4;
            }
            if(status.error()!=0)return status.error();
            if(transport.gas!=null&&!reuseGeometry){status=candidate.continueBatch((command,input,output,errors,staging,stack)->kernels.gasGeometry.recordComponents(command,transport.gas.geometry),0,0);if(status.error()!=0)return status.error();}
            int error=kernels.thermalKernel.execute(candidate,thermal,duration,source.transport.devices==null?null:source.transport.devices.state,transport.devices==null?null:transport.devices.settlement,false);
            if(error!=0)return error;
            if(transport.reactions!=null&&(activeInputs==null||!activeInputs.pendingPhaseSettlementOnly())){error=kernels.reactionKernel.execute(candidate,transport.reactions);if(error!=0)return error;}
            var stableHeader=new HeaderWrite(transport.thermal.contacts().header.offset(),transport.thermal.contacts().headerBytes());
            return candidate.finishBatch(stableHeader.bytes().remaining(),(command,input,output,errors,staging,stack)->{
                kernels.ledger.record(command,thermal.reservoir());kernels.ledger.record(command,transport.thermalOnlyRoundoff);kernels.ledger.record(command,transport.inventoryPosting);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                copyColumn(command,output,transport.thermalOnlyRoundoff.result(),output,transport.roundoff.result(),stack);
                for(var entry:List.of(transport.heat,transport.mechanicalPosting,transport.sourcePosting,transport.sinkPosting))vkCmdFillBuffer(command,output.handle(),entry.result().offset(),entry.result().bytes(),0);
                writeHeaders(command,output,List.of(stableHeader),stack);
            });
        }
        int execute(Workspace source,boolean sourceCurrentGas,boolean sourceGeometryValid,VulkanRegionStorage.Candidate candidate,VulkanLiquidProjection.Parameters parameters,
                    boolean zeroInitialPressure,int capacityRounds,long step,boolean accumulateLedger) {
            boolean migrating=source!=this;
            // A completed successor already contains the next source PLIC and chamber CSR.
            // Inventory-writing stages and moving solids rebuild these fields below.
            boolean reuseGeometry=sourceGeometryValid&&(transport==null||transport.dynamics==null)
                    &&currentInputs.bodies().bodies().isEmpty()&&(activeInputs==null||activeInputs.bodies().bodies().isEmpty());
            var sourceHeaders=migrating?transport.headers(source.liquid):List.<HeaderWrite>of();
            var stableHeaders=migrating?transport.headers(liquid):List.<HeaderWrite>of();
            long uploadedBytes=headerBytes(sourceHeaders);
            var gas=transport==null?null:transport.gas;
            int pressureRows=pressureColumns.get(PressureField.ROWS).count();
            VulkanPressureKernel.PressureOperator gasOperator=transport!=null&&transport.dynamics!=null?
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
                if(reuseGeometry)kernel.recordFaces(command,sx,sy,sz);
                else if(gas==null)kernel.record(command, sx, sy, sz);
                if(gas!=null) {
                    var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                    kernels.gasGeometry.rebindRanges(dst,err);kernels.gasThermo.rebindRanges(dst,err);kernels.gasPressure.rebindRanges(dst,err);
                    if(!reuseGeometry)kernels.timed(command,"old-gas-components",()->kernels.gasGeometry.recordComponents(command,gas.geometry));
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
                    Runnable assemble=()->projection.prepare(command,sx,sy,sz,parameters,gas==null?null:kernels.gasPressure,gas==null?null:gas.operator);
                    if(kernels==null)assemble.run();else kernels.timed(command,"common-pressure-assemble",assemble);
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
                    Runnable initialize=()->pressure.recordInitialize(command,pressureRows,pressureRows,parameters.iterations(),multigrid,hierarchy,gasOperator);
                    Runnable firstBatch=()->recordPressureIterations(command,output,errors,parameters.iterations(),Math.min(16,parameters.iterations()),gasOperator);
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
                        kernel.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                    }
                    kernels.volumeKernel.rebindRanges(src,dst,err);kernels.volumeKernel.record(command,transport.volumes);
                    kernels.gasGeometry.rebindRanges(dst,err);kernels.gasThermo.rebindRanges(dst,err);kernels.gasPressure.rebindRanges(dst,err);
                    kernels.timed(command,"old-gas-geometry",()->kernels.gasGeometry.recordGeometry(command,gas.geometry));kernels.gasGeometry.recordConnectivity(command,gas.geometry,4);
                },transport.dynamics!=null&&parameters!=null?transport.dynamics.transitions.column(VulkanGranularTransitions.Field.CONTROL).offset():gas.geometry.changed().offset(),transport.dynamics!=null&&parameters!=null?12:4);
                int connectivityChanged;
                if(transport.dynamics!=null&&parameters!=null){
                    if(pre.error()==64){int[] required={pre.feedback().getInt(0),pre.feedback().getInt(4),pre.feedback().getInt(8)};
                        if(required[0]>granularReserves.particles()||required[1]>granularReserves.deposits()||required[2]>granularReserves.species())throw new GranularCapacityGrowth(required);}
                    if(pre.error()!=0)return pre.error();var changed=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);candidate.read(gas.geometry.changed().offset(),changed);connectivityChanged=changed.getInt(0);
                }else connectivityChanged=pre.feedback().getInt(0);
                int connectivityRounds=4;
                while(pre.error()==0&&connectivityChanged!=0) {
                    if(connectivityRounds>=liquid.cellCount())throw new IllegalStateException("Gas connectivity did not converge");
                    pre=candidate.continueBatch((command,input,output,errors,staging,stack)->kernels.gasGeometry.recordConnectivity(command,gas.geometry,4),gas.geometry.changed().offset(),4);connectivityRounds+=4;connectivityChanged=pre.feedback().getInt(0);
                }
                if(pre.error()!=0)return pre.error();
            }
            traceStage("source-gas-connectivity",stageMark);
            if (parameters == null) error = gas==null||reuseGeometry?candidate.execute(uploadedBytes, prepare):candidate.finishBatch(prepare);
            else {
                capacityStage="common pressure";
                long runOffset = pressureColumns.get(PressureField.CONTROL).offset() + 24;
                var result = gas==null||reuseGeometry?candidate.beginBatch(uploadedBytes,prepare,runOffset,8):candidate.continueBatch(prepare,runOffset,8);
                int scheduled = Math.min(16, parameters.iterations());
                while (result.error() == 0 && result.feedback().getDouble(0) != 0.0 && scheduled < parameters.iterations()) {
                    int batch = Math.min(16, parameters.iterations() - scheduled);
                    boolean profileContinuation=scheduled==Math.min(16,parameters.iterations());
                    result = candidate.continueBatch((command, input, output, errors, staging, stack) ->
                            {Runnable continuation=()->recordPressureIterations(command,output,errors,parameters.iterations(),batch,gasOperator);
                                if(profileContinuation&&kernels!=null)kernels.timed(command,"common-pressure-next-batch",continuation);else continuation.run();}, runOffset, 8);
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
                                error=candidate.continueBatch(finishTransport,0,0).error();
                                traceStage("gas-remap-common-energy",stageMark);
                                if(error==0&&transport.aperture!=null)error=transport.aperture.execute(candidate,parameters.duration(),step);
                                traceStage("gas-aperture-flow",stageMark);
                                if(error==0&&transport.thermal!=null){capacityStage="thermal contacts/conduction";error=kernels.thermalKernel.execute(candidate,transport.thermal,parameters.duration(),source.transport.devices==null?null:source.transport.devices.state,transport.devices==null?null:transport.devices.settlement,accumulateLedger);}
                                traceStage("thermal-contacts-conduction",stageMark);
                                if(error==0&&transport.reactions!=null&&(activeInputs==null||!activeInputs.pendingPhaseSettlementOnly())){capacityStage="liquid reactions";error=kernels.reactionKernel.execute(candidate,transport.reactions);}
                                if(error==0)error=candidate.finishBatch(migrating?headerBytes(stableHeaders):0,(command,input,output,errors,staging,stack)-> {
                                    transport.recordLedgers(command,accumulateLedger?source.transport:null);if(migrating)writeHeaders(command,output,stableHeaders,stack);
                                });
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
                    definitions.capacities(stocks,species,limbs,windowLimbs),nextGas,contactCapacity,thermalContactCapacity,thermalBathCapacity,granularReserves);
        }
        Workspace growContacts(int required){return new Workspace(liquid,transport.definitions,gasReserves,growCount(contactCapacity,required),thermalContactCapacity,thermalBathCapacity,granularReserves);}
        Workspace growThermalContacts(int required,int requiredBaths){
            if(required<=thermalContactCapacity&&requiredBaths<=thermalBathCapacity)throw new IllegalStateException("Thermal capacity feedback did not grow either column");
            return new Workspace(liquid,transport.definitions,gasReserves,contactCapacity,growCount(thermalContactCapacity,required),growCount(thermalBathCapacity,requiredBaths),granularReserves);
        }
        Workspace growReactionSpecies(int required){if(required<=liquid.speciesCapacity())throw new IllegalStateException("Reaction feedback did not grow species capacity");int species=growCount(liquid.speciesCapacity(),required);return new Workspace(new VulkanLiquidStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),liquid.stockCapacity(),species,liquid.limbCapacity(),liquid.alignment()),transport.definitions.capacities(liquid.stockCapacity(),species,liquid.limbCapacity(),transport.definitions.scratchLimbsPerWindow()),gasReserves,contactCapacity,thermalContactCapacity,thermalBathCapacity,granularReserves);}
        Workspace growGranular(int[] required){var r=granularReserves;var next=new GranularReserves(growCount(r.particles(),required[0]),growCount(r.deposits(),required[1]),growCount(r.species(),required[2]));
            if(next.equals(r))throw new IllegalStateException("Granular lifecycle feedback did not grow inventory");return new Workspace(liquid,transport.definitions,gasReserves,contactCapacity,thermalContactCapacity,thermalBathCapacity,next);}
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
        final VulkanEnergyLedger.Layout thermalOnlyRoundoff;
        final VulkanMechanicalBodies.Layout bodies;
        final VulkanMechanicalEnergy.Layout energy;
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
            viscosity=new VulkanLiquidViscosity.Layout(liquid,geometry.get(GeometryField.CELLS),geometry.get(GeometryField.FACES),geometry.get(GeometryField.FIXED_MASKS),word(sourceThermal.cellTemperatures()),-1,
                    definitions.viscosity(),null,definitions.noSlip(),sourceThermal.endOffset(),liquid.alignment());
            forces=VulkanLiquidForces.layout(liquid,definitions.forces(),word(sourceThermal.cellTemperatures()),word(geometry.get(GeometryField.FACES)),-1,viscosity.endOffset(),liquid.alignment());
            surface=VulkanSurfaceTension.layout(liquid,definitions.surface(),word(geometry.get(GeometryField.CELLS)),word(geometry.get(GeometryField.FACES)),word(geometry.get(GeometryField.PLANES)),
                    word(geometry.get(GeometryField.FIXED_MASKS)),-1,forces.endOffset(),liquid.alignment());
            coherent=coherentConfiguration==null?null:new CoherentSection(surface.endOffset());
            devices=deviceConfiguration==null?null:new DeviceSection(coherent==null?surface.endOffset():coherent.endOffset);
            long solidsBase=devices!=null?devices.endOffset:coherent!=null?coherent.endOffset:surface.endOffset();
            granular=granularConfiguration==null?null:new GranularSection(solidsBase);
            gas=gasConfiguration==null?null:new GasSection(volumes,granular==null?solidsBase:granular.endOffset,
                    granular==null?word(geometry.get(GeometryField.OCCUPIED_SOLID)):granular.geometry.word(VulkanGranularGeometry.Field.OCCUPIED_SVQ));
            dynamics=granular==null?null:new GranularDynamics(granular,gas,viscosity.column(VulkanLiquidViscosity.Field.MU),gas.endOffset);
            capacity=new VulkanLiquidCapacityLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gas==null?0:gas.state.nodeCapacity(),dynamics!=null?dynamics.endOffset:gas==null?solidsBase:gas.endOffset,liquid.alignment());
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
            successor=gas==null?null:new GasSuccessor(gas,accepted,liquidThermal.endOffset(),word(futureFaces));
            bodies=VulkanMechanicalBodies.layout(liquid,routes,word(geometry.get(GeometryField.FACES)),gas==null?-1:word(gas.geometry.cellNodes()),
                    gas==null?0:gas.state.nodeCapacity(),gas==null?-1:Math.toIntExact(gas.state.column(VulkanGasStateLayout.Field.COUNTS).offset()/4),
                    dynamics==null?null:new VulkanMechanicalBodies.GranularLinks(granular.state,dynamics.pressure,dynamics.contacts.word(VulkanGranularContacts.Field.CONTACT_COMPONENT)),
                    successor==null?liquidThermal.endOffset():successor.endOffset,liquid.alignment());
            gasHeat=successor==null||gasConfiguration.tables().isothermal()?null:VulkanGasMechanicalHeat.layout(successor.work,bodies.endOffset(),liquid.alignment());
            var mechanicalEnergy=VulkanMechanicalEnergy.layout(bodies,momentum,liquidThermal,successor==null?-1:word(successor.work.workBySource()),
                    word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_HEAT)),word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_CELL_WORK)),
                    dynamics==null?null:new VulkanMechanicalEnergy.GranularEnergy(granular.thermal,granular.geometry,-1,dynamics.contacts.word(VulkanGranularContacts.Field.PARTICLE_EXTERNAL_WORK)),(gasHeat==null?bodies.endOffset():gasHeat.endOffset()),liquid.alignment()).withSourceFaceK(dynamics==null?-1:word(viscosity.column(VulkanLiquidViscosity.Field.SOURCE_FACE_K)));
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
            reactions=reactionConfiguration==null?null:VulkanRegionReactions.layout(reactionConfiguration,liquid,liquidThermal,thermalBase,liquid.alignment());
            if(reactions!=null)thermalBase=reactions.endOffset();
            var thermalStreams=new ArrayList<VulkanEnergyLedger.Stream>();if(thermal!=null)thermalStreams.addAll(thermal.roundoffStreams());if(reactions!=null)thermalStreams.addAll(reactions.roundoffStreams());
            thermalOnlyRoundoff=VulkanEnergyLedger.layout(thermalStreams,thermalBase,liquid.alignment());thermalBase=thermalOnlyRoundoff.endOffset();
            var roundoffStreams=new ArrayList<VulkanEnergyLedger.Stream>(List.of(stream(quantities.energyRemainders(),1),stream(gather.energyRemainders(),-1),
                    stream(momentum.heatPostingRemainders(),1),stream(momentum.heatAllocationRemainders(),1)));
            roundoffStreams.addAll(viscosity.externalWorkRoundoffStreams());roundoffStreams.addAll(heatTransport.roundoffStreams());
            roundoffStreams.addAll(energy.roundoffStreams());if(successor!=null)roundoffStreams.addAll(successor.remap.roundoffStreams());
            if(dynamics!=null){roundoffStreams.addAll(dynamics.contacts.externalWorkRoundoffStreams());roundoffStreams.addAll(dynamics.transitions.roundoffStreams());}
            if(aperture!=null)roundoffStreams.addAll(aperture.remap.roundoffStreams());
            if(thermal!=null)roundoffStreams.addAll(thermal.roundoffStreams());if(reactions!=null)roundoffStreams.addAll(reactions.roundoffStreams());
            roundoff=VulkanEnergyLedger.layout(roundoffStreams,thermalBase,liquid.alignment());
            heat=VulkanEnergyLedger.layout(java.util.List.of(stream(momentum.cellHeat(),1)),roundoff.endOffset(),liquid.alignment());
            var work=new ArrayList<VulkanEnergyLedger.Stream>(List.of(stream(momentum.cellHeat(),1),stream(energy.faceTerms(),1),stream(energy.potentialTerms(),1)));
            work.add(new VulkanEnergyLedger.Stream(1,word(viscosity.column(VulkanLiquidViscosity.Field.CONTACT_WORK)),viscosity.column(VulkanLiquidViscosity.Field.CONTACT_WORK).count(),2,1));
            if(dynamics!=null){work.add(stream(energy.granularTerms(),1));work.add(stream(dynamics.contacts.column(VulkanGranularContacts.Field.FIXED_WORK),1));work.add(stream(dynamics.transitions.column(VulkanGranularTransitions.Field.HEAT),1));
                for(int i=0;i<17;i++)work.add(new VulkanEnergyLedger.Stream(1,dynamics.transitions.word(VulkanGranularTransitions.Field.LIFT_TERMS)+2*i,dynamics.transitions.inputRows,34,i<8?1:-1));}
            mechanicalPosting=VulkanEnergyLedger.layout(work,heat.endOffset(),liquid.alignment());
            var incoming=new ArrayList<VulkanEnergyLedger.Stream>();var outgoing=new ArrayList<VulkanEnergyLedger.Stream>();
            if(successor!=null){incoming.add(new VulkanEnergyLedger.Stream(1,successor.remap.word(VulkanGasRemap.Field.AUDIT_ENERGY),1,2,1));outgoing.add(new VulkanEnergyLedger.Stream(1,successor.remap.word(VulkanGasRemap.Field.AUDIT_ENERGY)+2,1,2,1));}
            if(aperture!=null){incoming.add(new VulkanEnergyLedger.Stream(1,aperture.remap.word(VulkanGasRemap.Field.AUDIT_ENERGY),1,2,1));outgoing.add(new VulkanEnergyLedger.Stream(1,aperture.remap.word(VulkanGasRemap.Field.AUDIT_ENERGY)+2,1,2,1));}
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
                var source=gasConfiguration.initial();var counts=VulkanGasStateLayout.forState(source.snapshot(),8);
                var local=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),counts.nodeCapacity(),counts.componentCapacity(),counts.speciesCapacity(),gas.state.definitions(),8);
                var data=ByteBuffer.allocate(Math.toIntExact(local.bytes())).order(ByteOrder.LITTLE_ENDIAN);local.importState(data,source.snapshot(),source.nodeEnergy());
                for(var field:VulkanGasStateLayout.Field.values()){var c=local.column(field);if(c.bytes()>0)writer.put(gas.state.column(field).offset(),data.slice(Math.toIntExact(c.offset()),Math.toIntExact(c.bytes())));}
            }
            if(successor!=null){gasTables(writer,successor.retainedEOS);remapTables(writer,successor.remap);}
            if(aperture!=null){gasTables(writer,aperture.eos);gasTables(writer,aperture.retainedEOS);remapTables(writer,aperture.remap);}
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
            kernels.futureGeometry.rebindRanges(geometryRanges(output,errors,futureFaces));
            if(successor!=null)successor.bind(output,errors);
            kernels.volumeKernel.record(command,volumes);
            if(dynamics==null)kernels.inputKernel.record(command,capacityInputs);else kernels.inputKernel.recordDisplacement(command,capacityInputs);
            kernels.capacityKernel.recordInitialize(command,capacitySolve,maxRounds);
            recordCapacityIterations(command,output,errors,Math.min(32,maxRounds),3);
        }
        void beginDeviceRequest(org.lwjgl.vulkan.VkCommandBuffer command,Transport old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack,boolean continuation){
            if(devices!=null&&!continuation){VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);devices.state.recordBeginRequest(command,input,old.devices.state,output,stack);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}
        }
        void recordSolidGeometry(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,org.lwjgl.system.MemoryStack stack){
            if(coherent!=null){kernels.coherentGeometryKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));kernels.coherentGeometryKernel.record(command,coherent.geometry);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                copyColumn(command,output,coherent.geometry.occupiedSvq(),output,geometry.get(GeometryField.OCCUPIED_SOLID),stack);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);}
            if(granular!=null){bindGranular(output,errors);kernels.granularGeometryKernel.recordOccupancy(command,granular.geometry);}
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
            kernels.timed(command,"future-liquid-geometry",()->{
                if(successor==null)kernels.futureGeometry.record(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
                else kernels.futureGeometry.recordCells(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
            });
            if(successor!=null)successor.recordGeometry(command);
            kernels.timed(command,"momentum-resolve",()->kernels.momentumKernel.recordResolve(command,momentum));kernels.timed(command,"mixing-heat",()->kernels.momentumKernel.recordHeatPosting(command,momentum));
            kernels.timed(command,"body-init",()->kernels.bodiesKernel.recordInitialize(command,bodies));kernels.timed(command,"body-connectivity",()->kernels.bodiesKernel.recordConnectivity(command,bodies,4));
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
                    routes.column(VulkanLiquidRouteLayout.Field.COUNTS),routes.column(VulkanLiquidRouteLayout.Field.SPECIES_OFFSETS),
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
                result.add(gas.geometry.header());result.add(new VulkanLiquidStateLayout.Column(4L*gas.operator.headerWordOffset(),96,4));
                result.add(gas.operator.column(VulkanGasPressure.Field.CELL_PHYSICAL_PRESSURE));addGasTables(result,gas.thermo);
            }
            if(successor!=null) {
                result.addAll(List.of(successor.totals.header(),successor.geometry.header(),successor.lineage.header,successor.remap.header,successor.work.header()));
                addGasTables(result,successor.retainedEOS);
                for(var field:VulkanGasRemap.Field.values())if(field.name().startsWith("AMBIENT_")&&field!=VulkanGasRemap.Field.AMBIENT_BEFORE&&field!=VulkanGasRemap.Field.AMBIENT_AFTER)result.add(successor.remap.column(field));
            }
            if(aperture!=null)result.addAll(aperture.persistentColumns());
            if(gasHeat!=null)result.add(gasHeat.header());
            if(reactions!=null)result.addAll(reactions.persistentColumns());result.add(thermalOnlyRoundoff.header());
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
            if(gasHeat!=null)result.add(new HeaderWrite(gasHeat.header().offset(),gasHeat.headerBytes()));
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
            state=new VulkanCoherentStateLayout(liquid.cellCount(),c.initial().size(),c.speciesCount(),c.definitions(),base,a);
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
        GranularDynamics(GranularSection granular,GasSection gas,VulkanLiquidStateLayout.Column viscosity,long base){int particles=granular.state.particleCapacity(),faces=gas.geometry.faces();long a=liquid.alignment();
            coupled=new VulkanCoupledPressure.Layout(liquid.cellCount(),pressureColumns.get(PressureField.ROWS).count(),faces+3*particles,2*faces+75*particles,gas.operator.pressureBuffers(),base,a);
            pressure=new VulkanGranularPressure.Layout(granular.geometry,gas.operator,coupled,geometry.get(GeometryField.FIXED_MASKS),viscosity,null,coupled.endOffset(),a);
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
        final VulkanGasFlowMixedPressure.Layout mixedPressure;
        final VulkanGasRemap.Layout remap;
        final VulkanGasAdiabaticWork.Layout work;
        final long endOffset;
        GasAperture(GasSection gas,GasSuccessor successor,long base){
            this.gas=gas;this.successor=successor;long a=liquid.alignment();
            var volumes=new VulkanLiquidStateLayout.Column(align(base),gasReserves.nodes(),8);
            eos=VulkanGasThermodynamics.layout(gas.state,gasConfiguration.tables(),word(volumes),volumes.offset()+volumes.bindingBytes(),a);
            flow=VulkanGasFlow.layout(eos,successor.geometry,gasReserves.pairs(),eos.endOffset(),a);
            mixedPressure=VulkanGasFlowMixedPressure.layout(flow,flow.endOffset(),a);
            remap=VulkanGasRemap.transportLayout(flow.routes(),eos,successor.retained,successor.future,gasConfiguration.ambient(),flow.word(VulkanGasFlow.Field.STEP),
                    gasReserves.parts(),gasReserves.partSpecies(),mixedPressure.endOffset(),a);
            retainedEOS=VulkanGasThermodynamics.layout(successor.retained,gasConfiguration.tables(),flow.word(VulkanGasFlow.Field.RETAINED_VOLUME),remap.endOffset(),a);
            work=VulkanGasAdiabaticWork.layout(retainedEOS,eos.nodeVolumesWordOffset(),flow.word(VulkanGasFlow.Field.MODES),retainedEOS.endOffset(),a);endOffset=work.endOffset();
        }
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(eos.header().offset(),eos.headerBytes()),new HeaderWrite(flow.header.offset(),flow.headerBytes()),new HeaderWrite(mixedPressure.header().offset(),mixedPressure.headerBytes()),
                new HeaderWrite(remap.header.offset(),remap.headerBytes()),new HeaderWrite(retainedEOS.header().offset(),retainedEOS.headerBytes()),new HeaderWrite(work.header().offset(),work.headerBytes()));}
        List<VulkanLiquidStateLayout.Column> persistentColumns(){
            var columns=new ArrayList<VulkanLiquidStateLayout.Column>();transport.addGasTables(columns,eos);transport.addGasTables(columns,retainedEOS);
            columns.add(flow.header);columns.add(mixedPressure.header());columns.add(remap.header);columns.add(work.header());
            for(var field:VulkanGasRemap.Field.values())if(field.name().startsWith("AMBIENT_")&&field!=VulkanGasRemap.Field.AMBIENT_BEFORE&&field!=VulkanGasRemap.Field.AMBIENT_AFTER)columns.add(remap.column(field));return columns;
        }
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,GasAperture old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            var from=old.persistentColumns();var to=persistentColumns();var headerOffsets=headers().stream().map(HeaderWrite::offset).toList();
            for(int i=0;i<from.size();i++)if(!headerOffsets.contains(to.get(i).offset()))copyColumn(command,input,from.get(i),output,to.get(i),stack);
        }
        int execute(VulkanRegionStorage.Candidate candidate,double duration,long step){
            var q=VulkanGasFlow.parameters(duration,.6,gasConfiguration.ambient(),step);int n=flow.nodes;
            capacityStage="gas aperture assemble";
            var batch=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                kernels.flowKernel.rebindRanges(dst,err);kernels.flowEOS.rebindRanges(dst,err);kernels.flowRemap.rebindRanges(dst,err);kernels.flowRetainedEOS.rebindRanges(dst,err);kernels.flowWork.rebindRanges(dst,err);
                kernels.flowMixedPressure.rebindRanges(dst,err);
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
                kernels.flowMixedPressure.recordInitialize(command,mixedPressure,q,200);kernels.flowMixedPressure.recordRefresh(command,mixedPressure,q,200);kernels.flowMixedPressure.recordIterations(command,mixedPressure,q,200,8);
            },mixedPressure.feedbackOffset(),8);
            while(batch.error()==0&&(batch.feedback().getInt(4)&1)!=0&&batch.feedback().getInt(0)<200){int count=Math.min(8,200-batch.feedback().getInt(0));boolean refresh=(batch.feedback().getInt(4)&2)!=0;
                batch=candidate.continueBatch((command,input,output,errors,staging,stack)->{if(refresh)kernels.flowMixedPressure.recordRefresh(command,mixedPressure,q,200);kernels.flowMixedPressure.recordIterations(command,mixedPressure,q,200,count);},mixedPressure.feedbackOffset(),8);}
            if(batch.error()!=0)return batch.error();
            batch=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                kernels.flowMixedPressure.recordFinish(command,mixedPressure,q,200);
                kernels.flowKernel.recordRoutes(command,flow,q);
                kernels.timed(command,"aperture-integer-transport",()->kernels.flowRemap.recordPrepare(command,remap));
                kernels.flowRemap.recordTransportVolumes(command,remap,flow);kernels.flowRetainedEOS.record(command,retainedEOS);kernels.flowWork.record(command,work);
                kernels.timed(command,"aperture-energy-gather",()->kernels.flowRemap.recordFinish(command,remap,work,kernels.timings));
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                gas.state.copyColumns(command,output,successor.future,output,stack);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                if(gasConfiguration.tables().isothermal()){kernels.flowEOS.recordVolumes(command,eos,successor.geometry);kernels.flowEOS.record(command,eos);}
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
        GasSuccessor(GasSection old,VulkanLiquidAcceptedVolumes.Layout accepted,long base,int liquidFacesWordOffset) {
            this.old=old;var config=gasConfiguration;long a=liquid.alignment();
            totals=VulkanLiquidCellVolumes.layout(liquid,Math.addExact(liquid.limbCapacity(),liquid.cellCount()),accepted.sourceCellVolumes().quantumExponent(),base,a);
            geometry=VulkanGasGeometry.layout(totals,word(Workspace.this.geometry.get(GeometryField.FIXED_MASKS)),old.geometry.occupiedWordOffset(),
                    word(Workspace.this.geometry.get(GeometryField.CELLS)),word(Workspace.this.geometry.get(GeometryField.PLANES)),word(Workspace.this.geometry.get(GeometryField.LIQUID_MICRO)),config.sealedExterior(),config.cellSize(),totals.endOffset(),a).withLiquidFaces(liquidFacesWordOffset);
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
        void recordRemapPrepare(org.lwjgl.vulkan.VkCommandBuffer command){kernels.timed(command,"future-gas-components",()->kernels.futureGasGeometry.recordComponents(command,geometry));kernels.timed(command,"gas-lineage",()->kernels.lineageKernel.record(command,lineage));
            kernels.timed(command,"gas-remap-prepare",()->kernels.remapKernel.recordPrepare(command,remap));kernels.timed(command,"retained-gas-eos",()->kernels.retainedEOS.record(command,retainedEOS));kernels.timed(command,"gas-work",()->kernels.gasWork.record(command,work));}
        void recordRemapFinish(org.lwjgl.vulkan.VkCommandBuffer command){kernels.timed(command,"gas-remap-finish",()->kernels.remapKernel.recordFinish(command,remap,work,kernels.timings));}
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
        VulkanGasMechanicalHeat gasHeatKernel;
        VulkanGasFlow flowKernel;
        VulkanGasFlowMixedPressure flowMixedPressure;
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
        VulkanRegionReactions reactionKernel;

        Kernels(VulkanDeviceContext context,boolean gasEnabled,boolean granularEnabled,boolean thermalEnabled,boolean coherentEnabled,boolean reactionEnabled) {
            try {
                if(Boolean.getBoolean("mmm.gpuMechanicalStages"))timings=own(new VulkanStageTimings(context,96));
                futureGeometry=own(new VulkanLiquidGeometry(context));volumeKernel=own(new VulkanLiquidCellVolumes(context));
                inputKernel=own(new VulkanLiquidCapacityInputs(context));capacityKernel=own(new VulkanLiquidCapacity(context));
                acceptedKernel=own(new VulkanLiquidAcceptedVolumes(context));materialKernel=own(new VulkanLiquidMaterialRoutes(context));
                quantityKernel=own(new VulkanLiquidRouteQuantities(context));gatherKernel=own(new VulkanLiquidGather(context));
                momentumKernel=own(new VulkanLiquidMomentum(context));liquidThermalKernel=own(new VulkanLiquidThermodynamics(context));ledger=own(new VulkanEnergyLedger(context));
                viscosityKernel=own(new VulkanLiquidViscosity(context));forceKernel=own(new VulkanLiquidForces(context));surfaceKernel=own(new VulkanSurfaceTension(context));heatTransportKernel=own(new VulkanMechanicalHeatTransport(context));
                bodiesKernel=own(new VulkanMechanicalBodies(context));energyKernel=own(new VulkanMechanicalEnergy(context));
                if(reactionEnabled)reactionKernel=own(new VulkanRegionReactions(context));
                if(coherentEnabled)coherentGeometryKernel=own(new VulkanCoherentGeometry(context));
                if(thermalEnabled)thermalKernel=own(new VulkanRegionThermal(context));
                gasGeometry=own(new VulkanGasGeometry(context));gasThermo=own(new VulkanGasThermodynamics(context));gasPressure=own(new VulkanGasPressure(context));
                if(gasEnabled){futureVolumeKernel=own(new VulkanLiquidCellVolumes(context));futureGasGeometry=own(new VulkanGasGeometry(context));lineageKernel=own(new VulkanGasLineage(context));
                    remapKernel=own(new VulkanGasRemap(context));retainedEOS=own(new VulkanGasThermodynamics(context));gasWork=own(new VulkanGasAdiabaticWork(context));gasHeatKernel=own(new VulkanGasMechanicalHeat(context));
                    flowKernel=own(new VulkanGasFlow(context));flowMixedPressure=own(new VulkanGasFlowMixedPressure(context));flowEOS=own(new VulkanGasThermodynamics(context));
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
