package mmm.gpu;

import mmm.fluid.FixedGeometrySnapshot;
import mmm.fluid.MacMomentumState;
import mmm.inventory.DeviceLiquidInventory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static mmm.gpu.VulkanLiquidStateLayout.Field.*;
import static org.lwjgl.vulkan.VK10.*;

/** Resident liquid/gas mechanical candidates, with replaceable layouts and shared native pipelines. */
final class VulkanLiquidRegion implements AutoCloseable {
    private float previousMechanicalLeafDuration;
    private double observationDuration;
    private int previousMechanicalSubsteps;
    private int successfulMechanicalSchedules;
    enum GeometryField { FIXED_MASKS, OCCUPIED_SOLID, SCREEN_FACES, CELLS, PLANES, LIQUID_MICRO, WALL_GEOMETRY, FACES, FLUXES }
    enum PressureField { STARTS, COLUMNS, VALUES, ROWS, PRESSURE, WORK, PROBLEM, CONTROL, PARTIALS }
    final VulkanDeviceContext context;
    final VulkanLiquidGeometry kernel;
    final VulkanLiquidProjection projection;
    final VulkanMixtureState mixtureKernel;
    final VulkanPressureKernel pressure;
    final VulkanPressureMultigrid multigrid;
    final Kernels kernels;
    final VulkanRegionStorage storage;
    final long maximumRange;
    private VulkanLiquidWorkspace workspace;
    VulkanPressureKernel.Progress pressureProgress;
    private mmm.units.SignedDyadic importRoundoff=mmm.units.SignedDyadic.ZERO;
    mmm.units.SignedDyadic importRoundoff(){return importRoundoff;}
    private boolean currentGeometryValid;
    private boolean currentGasNodesValid, currentMechanicalObservation;
    RequestInputs currentInputs=new RequestInputs(new mmm.fluid.EntityBodyState(List.of()),false,0);
    int requestedBodyCapacity,requestedBodyEntries;
    double requestCellSize=1;
    RequestInputs activeInputs;
    private final List<Growth> growths=Boolean.getBoolean("mmm.gpuMechanicalGrowths")?new ArrayList<>():null;
    record Growth(long generation,String stage,int[] before,int[] after,int[] required) { }
    synchronized List<Growth> growthDiagnostics(){return growths==null?List.of():List.copyOf(growths);}
    GasConfiguration gasConfiguration;
    VulkanGranularStateLayout.Configuration granularConfiguration;
    VulkanRegionThermal.Configuration thermalConfiguration;
    VulkanCoherentStateLayout.Configuration coherentConfiguration;
    // Defines the current workspace layout and reserved ports; actual devices and U live in the committed candidate.
    VulkanThermalDeviceLayout.Configuration deviceConfiguration;
    VulkanRegionReactions.Configuration reactionConfiguration;
    final GranularReserves initialGranularReserves;

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
            var ethanol=chemistry.hasSpecies(mmm.chemistry.BuiltinChemicalDefinitions.ETHANOL)
                    ?chemistry.species(chemistry.speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.ETHANOL)):null;
            var liquid=ethanol==null?null:ethanol.liquidProperties();
            var caloric=VulkanLiquidThermodynamics.tables(fluid,thermal).withAqueous(
                    fluid.materials().stream().map(m->m.id()).toList().indexOf("mmm:water"),
                    liquid==null?-1:chemistry.speciesHandle(ethanol.id()),liquid==null?0:ethanol.molarMassKgPerMol().toDouble(),
                    liquid==null?0:liquid.heatCapacityJPerKgK(),liquid==null?0:liquid.conductivityWPerMK());
            return new MechanicalDefinitions(stockCapacity,speciesCapacity,limbCapacity,scratchLimbsPerWindow,quantumExponent,phases,masses,reference,offsets,stride,caloric,VulkanLiquidViscosity.tables(fluid,constitutive,chemistry),VulkanLiquidForces.tables(fluid,constitutive),VulkanSurfaceTension.tables(fluid),noSlip);
        }

        MechanicalDefinitions capacities(int stocks,int species,int limbs,int windowLimbs) {
            return new MechanicalDefinitions(stocks,species,limbs,windowLimbs,quantumExponent,materialPhases,speciesMasses,materialReferences,speciesReferences,speciesStride,thermal,viscosity,forces,surface,noSlip);
        }
    }
    record GasConfiguration(mmm.fluid.air.SpatialGasState initial,VulkanGasThermodynamics.Tables tables,
                            int nodeCapacity,double cellSize,boolean sealedExterior,
                            VulkanGasAmbient.AmbientTables ambient,long[] speciesMasses) {
        static GasConfiguration forState(mmm.fluid.air.SpatialGasState initial,VulkanGasThermodynamics.Tables tables,double cellSize,
                                         boolean sealedExterior,VulkanGasAmbient.AmbientTables ambient,mmm.chemistry.ChemicalRegistry chemistry) {
            long[] masses=new long[tables.definitions().speciesIds().size()];
            for(int i=0;i<masses.length;i++)masses[i]=chemistry.speciesMassUnits(chemistry.speciesHandle(tables.definitions().speciesIds().get(i)),1);
            return new GasConfiguration(initial,tables,initial.cells().size(),cellSize,sealedExterior,ambient,masses);
        }
        @Override public long[] speciesMasses(){return speciesMasses.clone();}
        int componentCapacity(){return Math.multiplyExact(nodeCapacity+4,tables.definitions().componentIds().size());}
        GasConfiguration {
            java.util.Objects.requireNonNull(initial);java.util.Objects.requireNonNull(tables);java.util.Objects.requireNonNull(ambient);
            speciesMasses=speciesMasses.clone();if(speciesMasses.length!=tables.definitions().speciesIds().size())throw new IllegalArgumentException("Gas species masses differ from definitions");
            if(nodeCapacity<initial.cells().size()||!(cellSize>0&&Double.isFinite(cellSize)))throw new IllegalArgumentException("Invalid resident gas reserves");
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
        this.mixtureKernel=new VulkanMixtureState(context);
        maximumRange=context.maxStorageBufferRange();
        var liquid=definitions==null?VulkanLiquidStateLayout.forState(inventory,momentum,context.storageOffsetAlignment())
                :new VulkanLiquidStateLayout(momentum.grid(),definitions.stockCapacity(),
                        definitions.speciesCapacity(),Math.max(definitions.limbCapacity(),VulkanLiquidStateLayout.requiredVolumeCapacity(inventory,Math.max(definitions.stockCapacity(),Math.multiplyExact(inventory.cellCount(),definitions.materialPhases().length)),definitions.materialPhases().length)),context.storageOffsetAlignment(),definitions.materialPhases().length);
        if(!fixed.grid().equals(liquid.grid())||occupiedSolid.length!=liquid.cellCount())
            throw new IllegalArgumentException("Resident liquid geometry differs from its grid");
        workspace=new VulkanLiquidWorkspace(this,liquid,definitions);
        kernels=definitions==null?null:new Kernels(context,gasConfiguration!=null,granularConfiguration!=null,thermalConfiguration!=null,coherentConfiguration!=null,this.reactionConfiguration==null?null:this.reactionConfiguration.chemistry());
        VulkanRegionStorage created=null;
        try {
            created=new VulkanRegionStorage(context,workspace.bytes,1024*1024,liquid.grid());
            created.initialize(workspace.bytes,0,writer->importRoundoff=initialize(writer,inventory,momentum,fixed,occupiedSolid,gasConfiguration,granularConfiguration,thermalConfiguration,coherentConfiguration,deviceConfiguration,this.reactionConfiguration));
        }catch(RuntimeException|Error failure) {
            if(created!=null)try{created.close();}catch(RuntimeException|Error closing){failure.addSuppressed(closing);}
            if(kernels!=null)try{kernels.close();}catch(RuntimeException|Error closing){failure.addSuppressed(closing);}
            try{mixtureKernel.close();}catch(RuntimeException|Error closing){failure.addSuppressed(closing);}
            throw failure;
        }
        storage=created;
    }
    private mmm.units.SignedDyadic initialize(VulkanRegionStorage.Import writer,DeviceLiquidInventory inventory,MacMomentumState momentum,
                            FixedGeometrySnapshot fixed,long[] occupiedSolid,GasConfiguration gasConfiguration,
                            VulkanGranularStateLayout.Configuration granularConfiguration,VulkanRegionThermal.Configuration thermalConfiguration,
                            VulkanCoherentStateLayout.Configuration coherentConfiguration,VulkanThermalDeviceLayout.Configuration deviceConfiguration,
                            VulkanRegionReactions.Configuration reactionConfiguration) {
        var l=workspace.liquid;var originalEnergy=mmm.units.SignedDyadic.sum(energies(inventory.energyBits()));
        inventory=l.slotInventory(inventory);
        var roundoff=mmm.units.SignedDyadic.sum(energies(inventory.energyBits())).subtract(originalEnergy);
        if(inventory.cellCount()!=l.cellCount()||inventory.stockCount()>l.stockCapacity()||inventory.speciesHandles().length>l.speciesCapacity()||inventory.volumes().limbs().length>l.limbCapacity())
            throw new IllegalArgumentException("Liquid import exceeds resident capacity");
        var v=inventory.volumes();var encoded=l.encodeVolumes(inventory);
        writer.put(l.column(COUNTS),new int[]{inventory.stockCount(),inventory.speciesHandles().length,encoded.used()});
        writer.put(workspace.hierarchyTopology(),workspace.hierarchy.topologyData());
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
        double[] screens = new double[2 * l.grid().faceCount()];
        for (var screen : fixed.screens()) { screens[2 * screen.face()] = screen.poreDiameter(); screens[2 * screen.face() + 1] = screen.openFraction(); }
        writer.put(workspace.geometry.get(GeometryField.SCREEN_FACES),screens);
        if(workspace.transport!=null){workspace.transport.initialize(writer,gasConfiguration,granularConfiguration,thermalConfiguration,coherentConfiguration,deviceConfiguration,reactionConfiguration);
            var total=mmm.units.SignedDyadic.sum(energies(inventory.energyBits()));
            if(gasConfiguration!=null)total=total.add(mmm.units.SignedDyadic.sum(gasConfiguration.initial().internalEnergy()));
            if(granularConfiguration!=null){total=total.add(mmm.units.SignedDyadic.sum(granularConfiguration.initial().particles().stream().mapToDouble(p->p.inventory().internalEnergy()).toArray()));total=total.add(mmm.units.SignedDyadic.sum(granularConfiguration.initial().deposits().stream().mapToDouble(d->d.inventory().internalEnergy()).toArray()));}
            if(coherentConfiguration!=null)total=total.add(mmm.units.SignedDyadic.sum(coherentConfiguration.initial().stream().mapToDouble(mmm.thermal.CoherentPhaseStore.Group::internalEnergy).toArray()));
            writer.put(workspace.transport.inventoryPosting.result().offset(),canonical(total));
        }
        return roundoff;
    }
    private static double[] energies(long[] bits){return java.util.Arrays.stream(bits).mapToDouble(Double::longBitsToDouble).toArray();}
    private static ByteBuffer canonical(mmm.units.SignedDyadic value){var b=ByteBuffer.allocate(288).order(ByteOrder.LITTLE_ENDIAN);var n=value.significand().abs();int count=(n.bitLength()+31)/32;b.putInt(0,value.signum()).putInt(4,value.exponent()).putInt(8,count);for(int i=0;i<count;i++)b.putInt(16+4*i,n.shiftRight(32*i).intValue());return b;}
    static void thermalTables(VulkanRegionStorage.Import writer,VulkanLiquidThermodynamics.Layout layout) {
        var t=layout.tables();writer.put(layout.offsets(),t.offsets());writer.put(layout.upper(),t.upper());writer.put(layout.capacity(),t.capacity());
        writer.put(layout.referenceTemperature(),t.referenceTemperature());writer.put(layout.coldSensible(),t.coldSensible());
    }
    static void gasTables(VulkanRegionStorage.Import writer,VulkanGasThermodynamics.Layout layout) {
        for(var chunk:layout.tableChunks())writer.put(chunk.offset(),chunk.bytes());
    }
    long rebuildGeometry(){return advance(null);}
    long advanceMechanical(VulkanLiquidProjection.Parameters parameters) {
        java.util.Objects.requireNonNull(parameters,"Mechanical parameters");
        if(kernels==null)throw new IllegalStateException("Mechanical transport requires explicit constitutive definitions");
        return advance(parameters);
    }
    private synchronized long advance(VulkanLiquidProjection.Parameters parameters) {
        try(var source=captureState();var prepared=prepareMechanicalCandidate(source,parameters)){return prepared.commit();}
    }
    synchronized void initializeInputs(mmm.fluid.EntityBodyState bodies){
        double cellSize=thermalConfiguration!=null?thermalConfiguration.cellSize():gasConfiguration!=null?gasConfiguration.cellSize():requestCellSize;
        initializeInputs(bodies,cellSize);
    }
    synchronized void initializeInputs(mmm.fluid.EntityBodyState bodies,double cellSize){
        if(!(Double.isFinite(cellSize)&&cellSize>0))throw new IllegalArgumentException("Invalid initial cell size");
        requestCellSize=cellSize;
        try(var source=captureState();var prepared=prepareInitialGeometry(source,bodies)){prepared.commit();}
    }
    synchronized Capture captureState(){return new Capture(storage.capture(),workspace,currentGeometryValid,currentGasNodesValid,currentMechanicalObservation,currentInputs,observationDuration);}
    mmm.fluid.EntityBodyState requestedBodies(){return (activeInputs==null?currentInputs:activeInputs).bodies();}
    VulkanEntityBodyCoupling.Configuration bodyConfiguration(mmm.fluid.EntityBodyState bodies){return new VulkanEntityBodyCoupling.Configuration(bodies,workspace.liquid.sizeX(),workspace.liquid.sizeY(),workspace.liquid.sizeZ(),requestCellSize);}
    private VulkanLiquidWorkspace bodyWorkspace(VulkanLiquidWorkspace source,mmm.fluid.EntityBodyState oldBodies,java.util.Set<VulkanLiquidWorkspace> allocated){
        return bodyWorkspace(source,oldBodies,requestedBodies(),allocated);
    }
    private VulkanLiquidWorkspace bodyWorkspace(VulkanLiquidWorkspace source,mmm.fluid.EntityBodyState oldBodies,mmm.fluid.EntityBodyState targetBodies,java.util.Set<VulkanLiquidWorkspace> allocated){
        var old=bodyConfiguration(oldBodies);var next=bodyConfiguration(targetBodies);
        int bodies=Math.max(oldBodies.bodies().size(),next.bodies().bodies().size()),entries=Math.max(old.cellEntries(),next.cellEntries());
        VulkanLiquidWorkspace target=source;
        if(bodies>source.bodyCapacity||entries>source.bodyEntries){
            requestedBodyCapacity=Math.max(requestedBodyCapacity,growCount(source.bodyCapacity,bodies));requestedBodyEntries=Math.max(requestedBodyEntries,growCount(source.bodyEntries,entries));
            target=new VulkanLiquidWorkspace(this,source.liquid,source.transport.definitions,source.contactCapacity,source.thermalContactCapacity,source.thermalBathCapacity,source.granularReserves,source.coherentSpeciesCapacity);allocated.add(target);
        }
        target.sourceBodyState=oldBodies;return target;
    }
    record RequestInputs(mmm.fluid.EntityBodyState bodies,boolean phaseChangeEnabled,int maximumPhaseOperations,boolean pendingPhaseSettlementOnly,boolean mechanicalAuditEnabled,boolean[] activeCells){
        RequestInputs{java.util.Objects.requireNonNull(bodies);if(phaseChangeEnabled?maximumPhaseOperations<=0:maximumPhaseOperations!=0)throw new IllegalArgumentException("Phase request budget differs from enabled mode");activeCells=activeCells==null?null:activeCells.clone();}
        RequestInputs(mmm.fluid.EntityBodyState bodies,boolean phaseChangeEnabled,int maximumPhaseOperations){this(bodies,phaseChangeEnabled,maximumPhaseOperations,false,false,null);}
        RequestInputs(mmm.fluid.EntityBodyState bodies,boolean phaseChangeEnabled,int maximumPhaseOperations,boolean[] activeCells){this(bodies,phaseChangeEnabled,maximumPhaseOperations,false,false,activeCells);}
        RequestInputs(mmm.fluid.FluidStepRequest request){this(new mmm.fluid.EntityBodyState(request.movingBodies()),request.phaseChangeEnabled(),request.maximumPhaseOperations(),request.pendingPhaseSettlementOnly(),request.mechanicalAuditEnabled(),request.activeCells());}
        RequestInputs stationary(mmm.fluid.EntityBodyState retained){return new RequestInputs(new mmm.fluid.EntityBodyState(retained.bodies().stream().map(mmm.world.MovingBodyFluidCoupling.Request::stationary).toList()),false,0,true,mechanicalAuditEnabled,activeCells);}
    }
    record ReadoutInputs(VulkanLiquidStateLayout liquid,java.util.Map<GeometryField,VulkanLiquidStateLayout.Column> geometry,
                         VulkanLiquidThermodynamics.Layout liquidThermal,VulkanGranularStateLayout granular,VulkanLiquidThermodynamics.Layout granularThermal,VulkanGranularGeometry.Layout granularGeometry,
                         VulkanGasStateLayout gas,VulkanGasThermodynamics.Layout gasThermal,VulkanGasGeometry.Layout gasGeometry,
                         VulkanCoherentStateLayout coherent,VulkanLiquidThermodynamics.Layout coherentThermal,VulkanThermalDeviceLayout devices,VulkanLiquidThermodynamics.Layout deviceThermal,VulkanCoherentGeometry.Layout coherentGeometry,VulkanRegionThermal.Layout thermal,VulkanLiquidViscosity.Layout viscosity,VulkanLiquidMomentum.Layout momentum,VulkanMixtureState.Layout mixture,VulkanMechanicalEnergy.Layout mechanicalEnergy){}
    record ThermalPosting(mmm.units.SignedDyadic source,mmm.units.SignedDyadic sink,mmm.units.SignedDyadic mechanicalWork,mmm.units.SignedDyadic reservoir,mmm.units.SignedDyadic finiteDevices,mmm.units.SignedDyadic roundoff){
        mmm.thermal.ThermalAuditLedger apply(mmm.thermal.ThermalAuditLedger before){return before.withExternalSourceEnergy(source).withExternalSinkEnergy(sink).withMechanicalWork(mechanicalWork).withHeatExchange(reservoir).withFiniteDeviceExchange(finiteDevices).withRoundoff(roundoff);}
    }
    final class Capture implements AutoCloseable {
        private final VulkanRegionStorage.Capture capture;private final VulkanLiquidWorkspace layout;private final boolean geometryValid,gasNodesValid,mechanicalObservation;private final RequestInputs inputs;private final double flowDuration;
        private Capture(VulkanRegionStorage.Capture capture,VulkanLiquidWorkspace layout,boolean geometryValid,boolean gasNodesValid,boolean mechanicalObservation,RequestInputs inputs,double flowDuration){this.flowDuration=flowDuration;this.capture=capture;this.layout=layout;this.geometryValid=geometryValid;this.gasNodesValid=gasNodesValid;this.mechanicalObservation=mechanicalObservation;this.inputs=inputs;}
        double liquidFlow(int[] faces,double cellSize) {
            if(!mechanicalObservation||flowDuration<=0||layout.transport==null)return 0;
            double amount=0;var grid=layout.liquid.grid();var accepted=layout.transport.accepted;
            for(int face:faces){
                int axis=grid.faceAxis(face),left=grid.faceLeft(face),right=grid.faceRight(face);
                if(left>=0)amount+=routeVolume(accepted,7*left+2*axis+2);
                if(right>=0)amount-=routeVolume(accepted,7*right+2*axis+1);
            }
            return amount*cellSize*cellSize*cellSize/flowDuration;
        }
        double gasFlow(int[] faces) {
            if(!mechanicalObservation||flowDuration<=0||layout.transport==null||layout.transport.gas==null)return 0;
            var grid=layout.liquid.grid();var volume=layout.transport.gas.transport.column(VulkanLocalGasTransport.Field.VOLUME);
            double amount=0;
            for(int face:faces) {
                int axis=grid.faceAxis(face),left=grid.faceLeft(face),right=grid.faceRight(face);
                if(left>=0 && gasFaceRoute(left,2*axis+1,right))amount+=read(volume.offset()+8L*((2*axis+1)*grid.cellCount()+left),8).getDouble(0);
                if(right>=0 && gasFaceRoute(right,2*axis,left))amount-=read(volume.offset()+8L*(2*axis*grid.cellCount()+right),8).getDouble(0);
            }
            return amount/flowDuration;
        }
        private boolean gasFaceRoute(int cell,int side,int neighbor) {
            var gas=layout.transport.gas.transport;
            if(gas.routeTargets>=0 && read(4L*(gas.routeTargets+6*cell+side),4).getInt(0)!=neighbor)return false;
            if(gas.targetMasks>=0 && gas.targetSolid>=0) {
                int mask=read(4L*(gas.targetMasks+cell),4).getInt(0);
                long capacity=(long)Integer.bitCount((~mask)&255)<<21;
                if(read(4L*(gas.targetSolid+2*cell),8).getLong(0)>=capacity)return false;
            }
            return true;
        }
        private double routeVolume(VulkanLiquidAcceptedVolumes.Layout accepted,int route){
            var descriptor=read(accepted.descriptors().offset()+20L*route,20);
            int count=descriptor.getInt(8),exponent=descriptor.getInt(12),sign=descriptor.getInt(16);
            if(count==0||sign==0)return 0;
            var limbs=read(Integer.toUnsignedLong(descriptor.getInt(4))*4,4L*count);double amount=0;
            for(int index=0;index<count;index++)amount+=Math.scalb((double)Integer.toUnsignedLong(limbs.getInt(4*index)),exponent+32*index);
            return sign*amount;
        }
        GpuBuffer buffer(){return capture.buffer();}
        ReadoutInputs readoutInputs(){var t=layout.transport;var g=t==null?null:t.gas;return new ReadoutInputs(layout.liquid,java.util.Map.copyOf(layout.geometry),t==null?null:t.liquidThermal,
                t==null||t.granular==null?null:t.granular.state,t==null||t.granular==null?null:t.granular.thermal,t==null||t.granular==null?null:t.granular.geometry,g==null?null:g.state,g==null?null:g.thermo,
                g==null?null:g.geometry,t==null||t.coherent==null?null:t.coherent.state,t==null||t.coherent==null?null:t.coherent.thermal,
                t==null||t.devices==null?null:t.devices.state,t==null||t.devices==null?null:t.devices.thermal,t==null||t.coherent==null?null:t.coherent.geometry,t==null?null:t.thermal,t==null?null:t.viscosity,t==null?null:t.momentum,layout.mixture,t==null?null:t.energy);}
        int[] chemistryCounters(){var t=layout.transport;if(t==null||t.reactions==null)return new int[8];return ints(t.reactions.progress().column(VulkanReactionProgressLayout.Field.CONTROL),8);}
        int[] thermalPhaseCounters(){var t=layout.transport;if(inputs==null||!inputs.phaseChangeEnabled()||inputs.pendingPhaseSettlementOnly()||t==null||t.reactions==null||t.reactions.condensedPhase()==null)return new int[10];return ints(t.reactions.condensedPhase().column(VulkanCondensedPhaseChange.Field.CONTROL),10);}
        int[] massTransferCounters(){var t=layout.transport;if(inputs!=null&&inputs.pendingPhaseSettlementOnly()||t==null||t.reactions==null||t.reactions.massTransfer()==null)return new int[4];return ints(t.reactions.massTransfer().column(VulkanGasLiquidMassTransfer.Field.CONTROL),4);}
        int[] gasPhaseCounters(){var t=layout.transport;if(inputs==null||!inputs.phaseChangeEnabled()||inputs.pendingPhaseSettlementOnly()||t==null||t.reactions==null||t.reactions.massTransfer()==null)return new int[8];var c=ints(t.reactions.massTransfer().column(VulkanGasLiquidMassTransfer.Field.CONTROL),8);return new int[]{c[6],c[5],c[4],0,0,0,0,0};}
        int[] granularCounters(){var t=layout.transport;if(!mechanicalObservation||t==null||t.dynamics==null)return new int[6];return ints(t.dynamics.transitions.column(VulkanGranularTransitions.Field.CONTROL),6);}
        private int[] ints(VulkanLiquidStateLayout.Column column,int count){var b=read(column.offset(),4L*count);int[] values=new int[count];b.asIntBuffer().get(values);return values;}
        double[] pressureDiagnostics(){var b=read(layout.pressureColumns.get(PressureField.CONTROL).offset(),24);return new double[]{b.getDouble(0),b.getDouble(8),b.getDouble(16)};}
        long generation(){return capture.generation();}
        Capture retain(){return new Capture(capture.retain(),layout,geometryValid,gasNodesValid,mechanicalObservation,inputs,flowDuration);}
        VulkanLiquidStateLayout.State liquid(){var b=read(0,layout.liquid.bytes());return layout.liquid.exportState(b);}
        VulkanGranularStateLayout.State granular(){var s=layout.transport.granular.state;return s.exportState(read(s.baseOffset(),s.bytes()));}
        List<mmm.thermal.CoherentPhaseStore.Group> coherent(){var s=layout.transport.coherent.state;return s.exportState(read(s.baseOffset(),s.bytes()));}
        VulkanThermalDeviceLayout.State devices(){var s=layout.transport.devices.state;return s.exportState(read(s.baseOffset(),s.bytes()));}
        mmm.fluid.air.SpatialGasState gas(){
            var s=layout.transport.gas.state;var local=new VulkanGasStateLayout(s.grid(),s.nodeCapacity(),s.definitions(),8);
            var b=ByteBuffer.allocate(Math.toIntExact(local.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            for(var f:VulkanGasStateLayout.Field.values()){var c=s.column(f);if(c.bytes()>0)storage.read(capture,c.offset(),b.slice(Math.toIntExact(local.column(f).offset()),Math.toIntExact(c.bytes())));}
            return local.exportSpatialState(b);
        }
        mmm.fluid.EntityBodyState entityBodies(){return inputs.bodies();}
        int[] activityGasNodes() {
            var t=layout.transport;
            if(t==null||t.gas==null)return null;
            if(!gasNodesValid)throw new IllegalStateException("Activity requires prepared gas geometry");
            var geometry=t.gas.geometry;
            return ints(geometry.cellNodes(),layout.liquid.cellCount());
        }
        int[] activityParticleSupports() {
            var t=layout.transport;
            if(t==null||t.granular==null)return new int[0];
            var state=t.granular.state;
            int count=ints(state.column(VulkanGranularStateLayout.Field.COUNTS),1)[0];
            return ints(state.column(VulkanGranularStateLayout.Field.SUPPORT_CELLS),8*count);
        }
        RequestInputs requestInputs(){return inputs;}
        VulkanReactionProgressLayout.State reactionProgress(){var t=layout.transport;return t==null||t.reactions==null?VulkanReactionProgressLayout.State.empty():t.reactions.progress().export(c->read(c.offset(),c.bytes()));}
        boolean hasPendingPhaseMotion(){var t=layout.transport;return t!=null&&t.reactions!=null&&read(t.reactions.progress().column(VulkanReactionProgressLayout.Field.PHASE_PENDING_SUMMARY).offset(),4).getInt(0)!=0;}
        boolean hasPendingPhaseMotion(boolean[] activeCells){
            if(activeCells==null)return hasPendingPhaseMotion();
            var t=layout.transport;if(t==null||t.reactions==null)return false;
            var column=t.reactions.progress().column(VulkanReactionProgressLayout.Field.PHASE_MOTION_CELLS);
            if(activeCells.length!=column.count())throw new IllegalArgumentException("Activity mask differs from phase motion");
            var values=ints(column,column.count());
            for(int cell=0;cell<values.length;cell++)if(activeCells[cell]&&values[cell]!=0)return true;
            return false;
        }
        mmm.fluid.PendingPhaseMotionState pendingPhaseMotion(){return new mmm.fluid.PendingPhaseMotionState(layout.liquid.cellCount(),java.util.Arrays.stream(reactionProgress().phaseMotionCells()).mapToObj(mmm.fluid.PendingPhaseMotionState.Entry::new).toList());}
        boolean hasGas(){return layout.transport!=null&&layout.transport.gas!=null;}
        boolean hasGranular(){return layout.transport!=null&&layout.transport.granular!=null;}
        boolean hasCoherent(){return layout.transport!=null&&layout.transport.coherent!=null;}
        boolean hasDevices(){return layout.transport!=null&&layout.transport.devices!=null;}
        mmm.inventory.PhaseOccupancyState occupancy(){var c=layout.geometry.get(GeometryField.LIQUID_MICRO);var b=read(c.offset(),c.bytes());double[] values=new double[c.count()];b.asDoubleBuffer().get(values);return new mmm.inventory.PhaseOccupancyState(values);}
        mmm.fluid.CondensedPhasePressureField pressure(){
            var c=layout.mixture.states();var b=read(c.offset(),c.bytes());float[] values=new float[layout.liquid.cellCount()];
            for(int i=0;i<values.length;i++)values[i]=(float)(b.getDouble(56*i)-mmm.thermal.MixtureVolumeModel.REFERENCE_PRESSURE_PA);
            return new mmm.fluid.CondensedPhasePressureField(0,values);
        }
        mmm.units.SignedDyadic exactThermalInventory(){return dyadic(layout.transport.inventoryPosting.result());}
        boolean gasFlowActive(){var t=layout.transport;if(t==null||t.gas==null||!mechanicalObservation)return false;
            var c=t.gas.transport.column(VulkanLocalGasTransport.Field.PRIMAL_MASS);var b=read(c.offset(),c.bytes());
            for(int i=0;i<c.count();i++)if(b.getLong(8*i)!=0)return true;return false;}
        ThermalPosting thermalPosting(){var t=layout.transport;var zero=mmm.units.SignedDyadic.ZERO;return new ThermalPosting(dyadic(t.sourcePosting.result()),dyadic(t.sinkPosting.result()),dyadic(t.mechanicalPosting.result()),t.thermal==null?zero:dyadic(t.thermal.reservoir().result()),t.devices==null?zero:dyadic(t.devices.state.column(VulkanThermalDeviceLayout.Field.FLUID_EXCHANGE)),dyadic(t.roundoff.result()));}
        private mmm.units.SignedDyadic dyadic(VulkanLiquidStateLayout.Column c){var b=read(c.offset(),c.bytes());int sign=b.getInt(0),count=b.getInt(8);var n=java.math.BigInteger.ZERO;for(int i=count-1;i>=0;i--)n=n.shiftLeft(32).add(java.math.BigInteger.valueOf(Integer.toUnsignedLong(b.getInt(16+4*i))));return sign==0?mmm.units.SignedDyadic.ZERO:new mmm.units.SignedDyadic(sign<0?n.negate():n,b.getInt(4));}
        private ByteBuffer read(long offset,long length){var b=ByteBuffer.allocate(Math.toIntExact(length)).order(ByteOrder.LITTLE_ENDIAN);storage.read(capture,offset,b);return b.flip();}
        @Override public void close(){capture.close();}
    }
    final class Prepared implements AutoCloseable {
        private final Capture source;private final PreparedInterval result;private final java.util.Set<VulkanLiquidWorkspace> allocated;private boolean closed;private final RequestInputs inputs;
        private VulkanFluidStateCodec.ImportData imported;
        private mmm.units.SignedDyadic importedRoundoff;
        private VulkanRegionThermal.Configuration updatedHeat;
        private long mechanicalAttempts;
        private float mechanicalDuration;
        private Prepared(Capture source,PreparedInterval result,java.util.Set<VulkanLiquidWorkspace> allocated){this.source=source;this.result=result;this.allocated=allocated;this.inputs=activeInputs==null?source.inputs:activeInputs;}
        mmm.world.MovingBodyFluidCoupling.Result bodyCoupling(){return result.bodyResult;}
        mmm.units.SignedDyadic importRoundoff(){return importedRoundoff==null?mmm.units.SignedDyadic.ZERO:importedRoundoff;}
        Capture captureState(){synchronized(VulkanLiquidRegion.this){if(closed)throw new IllegalStateException("Prepared region is closed");return new Capture(result.candidate.captureCompleted(),result.workspace,result.geometryValid,result.gasNodesValid,result.mechanicalObservation,inputs,result.lastLeafDuration);}}
        int substeps(){return result.substeps;}
        long commit(){synchronized(VulkanLiquidRegion.this){if(closed)throw new IllegalStateException("Prepared region is closed");long generation=result.candidate.commit(source.capture);workspace=result.workspace;currentGeometryValid=result.geometryValid;currentGasNodesValid=result.gasNodesValid;currentMechanicalObservation=result.mechanicalObservation;currentInputs=inputs;
            if(result.mechanicalObservation){
                observationDuration=result.lastLeafDuration;previousMechanicalLeafDuration=result.lastLeafDuration;previousMechanicalSubsteps=result.substeps;
                successfulMechanicalSchedules=mechanicalAttempts==result.substeps?successfulMechanicalSchedules+1:0;
                if(successfulMechanicalSchedules>=8&&result.substeps>1){
                    previousMechanicalLeafDuration=Math.min(mechanicalDuration,result.lastLeafDuration*2);
                    previousMechanicalSubsteps=Math.max(1,result.substeps/2);successfulMechanicalSchedules=0;
                }
            }
            // The existing device configuration owns the workspace's port capacity; the import replaces its resident contents.
            if(imported!=null){gasConfiguration=imported.gas();granularConfiguration=imported.granular();thermalConfiguration=imported.heat();coherentConfiguration=imported.coherent();reactionConfiguration=enabledReactions(imported.reactions());importRoundoff=importedRoundoff;workspace.sourceBodyState=inputs.bodies();}
            if(updatedHeat!=null)thermalConfiguration=updatedHeat;
            return generation;}}
        @Override public void close(){synchronized(VulkanLiquidRegion.this){if(closed)return;closed=true;result.close();source.close();for(var w:allocated)if(w!=workspace)w.close();}}
    }
    /** Replaces only the thermal face input while retaining every resident inventory and derived field. */
    synchronized Prepared prepareThermalBoundary(Capture source,double[] temperatures) {
        if(!source.capture.isCurrent()||source.layout!=workspace)throw new IllegalStateException("Thermal input uses a stale region capture");
        if(thermalConfiguration==null||workspace.transport==null||workspace.transport.thermal==null)
            throw new IllegalStateException("Region has no thermal input");
        var column=workspace.transport.bathTemperatures;
        if(temperatures==null||temperatures.length!=column.count())throw new IllegalArgumentException("Thermal boundary field dimensions changed");
        for(double temperature:temperatures)if(!Double.isNaN(temperature)
                &&(!Double.isFinite(temperature)||temperature<mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K))
            throw new IllegalArgumentException("Thermal boundary temperature is invalid");
        var values=temperatures.clone();
        var input=ByteBuffer.allocateDirect(Math.multiplyExact(values.length,8)).order(ByteOrder.LITTLE_ENDIAN);
        for(double value:values)input.putDouble(value);input.flip();
        var pin=source.retain();
        var allocated=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<VulkanLiquidWorkspace,Boolean>());
        allocated.add(workspace);
        var candidate=storage.prepare(pin.capture);
        try {
            int error=candidate.execute(input.remaining(),(command,old,output,errors,staging,stack)->{
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                long offset=column.offset();var data=input.duplicate();
                while(data.hasRemaining()){
                    int count=Math.min(data.remaining(),65536);vkCmdUpdateBuffer(command,output.handle(),offset,data.slice(data.position(),count));
                    data.position(data.position()+count);offset+=count;
                }
            });
            if(error!=0)throw new IllegalStateException("Thermal boundary candidate failed: status="+error);
            var prepared=new Prepared(pin,new PreparedInterval(workspace,candidate,source.geometryValid,source.gasNodesValid,false,0,0,mmm.world.MovingBodyFluidCoupling.Result.NONE),allocated);
            prepared.updatedHeat=new VulkanRegionThermal.Configuration(thermalConfiguration.tables(),thermalConfiguration.materials(),
                    thermalConfiguration.cellSize(),values,thermalConfiguration.contactCapacity());
            return prepared;
        }catch(RuntimeException|Error failure){candidate.close();pin.close();throw failure;}
    }
    /** Prepare the derived state of the initial imported generation without advancing time. */
    private Prepared prepareInitialGeometry(Capture source,mmm.fluid.EntityBodyState bodies) {
        if(!source.capture.isCurrent()||source.layout!=workspace||source.generation()!=0)
            throw new IllegalStateException("Initial geometry uses a stale or noninitial capture");
        var previous=activeInputs;
        activeInputs=new RequestInputs(bodies,false,0);
        var pin=source.retain();
        var allocated=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<VulkanLiquidWorkspace,Boolean>());
        allocated.add(workspace);
        VulkanRegionStorage.Candidate candidate=null;
        try {
            var target=bodyWorkspace(pin.layout,bodies,allocated);
            candidate=storage.prepareInitial(pin.capture,target.bytes,target==pin.layout);
            int error=target.prepareInitialGeometry(pin.layout,candidate,bodyConfiguration(bodies));
            if(error!=0)throw new IllegalStateException("Resident initial geometry error="+error);
            return new Prepared(pin,new PreparedInterval(target,candidate,true,true,false,0,0,mmm.world.MovingBodyFluidCoupling.Result.NONE),allocated);
        } catch(RuntimeException|Error failure) {
            if(candidate!=null)candidate.close();
            pin.close();
            for(var allocatedWorkspace:allocated)if(allocatedWorkspace!=workspace)allocatedWorkspace.close();
            throw failure;
        } finally { activeInputs=previous; }
    }
    /** Returns null when the replacement needs a different layout or definitions. */
    synchronized Prepared prepareImport(Capture source,VulkanFluidStateCodec.ImportData data) {
        if(!source.capture.isCurrent()||source.layout!=workspace)throw new IllegalStateException("Import uses a stale region capture");
        if(!fitsImport(data))return null;
        var pin=source.retain();
        var allocated=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<VulkanLiquidWorkspace,Boolean>());
        allocated.add(workspace);
        var candidate=storage.prepareReallocated(pin.capture,workspace.bytes);
        try {
            var roundoff=new mmm.units.SignedDyadic[1];
            candidate.importState(writer->roundoff[0]=initialize(writer,data.inventory(),data.momentum(),data.fixed(),data.occupiedSolid(),
                    data.gas(),data.granular(),data.heat(),data.coherent(),data.device(),enabledReactions(data.reactions())));
            int error=workspace.prepareImportedGeometry(candidate,importBodyConfiguration(data));
            if(error!=0)throw new IllegalStateException("Resident import geometry error="+error);
            var previous=activeInputs;
            try {
                activeInputs=new RequestInputs(data.bodies(),false,0);
                var prepared=new Prepared(pin,new PreparedInterval(workspace,candidate,true,true,false,0,0,mmm.world.MovingBodyFluidCoupling.Result.NONE),allocated);
                prepared.imported=data;prepared.importedRoundoff=roundoff[0];
                return prepared;
            } finally { activeInputs=previous; }
        } catch(RuntimeException|Error failure){candidate.close();pin.close();throw failure;}
    }
    private static VulkanRegionReactions.Configuration enabledReactions(VulkanRegionReactions.Configuration value){return value!=null&&value.enabled()?value:null;}
    private VulkanEntityBodyCoupling.Configuration importBodyConfiguration(VulkanFluidStateCodec.ImportData data) {
        double cellSize=data.heat()!=null?data.heat().cellSize():data.gas()!=null?data.gas().cellSize():requestCellSize;
        return new VulkanEntityBodyCoupling.Configuration(data.bodies(),workspace.liquid.sizeX(),workspace.liquid.sizeY(),workspace.liquid.sizeZ(),cellSize);
    }
    private boolean fitsImport(VulkanFluidStateCodec.ImportData data) {
        var l=workspace.liquid;var t=workspace.transport;var d=data.definitions();
        if(t==null||d==null
                ||!data.fixed().grid().equals(l.grid())||data.occupiedSolid().length!=l.cellCount()
                ||data.inventory().cellCount()!=l.cellCount()
                ||!data.momentum().grid().equals(l.grid()))return false;
        var bodyConfiguration=importBodyConfiguration(data);
        if(data.bodies().bodies().size()>workspace.bodyCapacity||bodyConfiguration.cellEntries()>workspace.bodyEntries)return false;
        var old=t.definitions;
        if(old.quantumExponent()!=d.quantumExponent()||old.speciesStride()!=d.speciesStride()||old.noSlip()!=d.noSlip()
                ||!sameDefinition(old.materialPhases(),d.materialPhases())||!sameDefinition(old.speciesMasses(),d.speciesMasses())
                ||!sameDefinition(old.materialReferences(),d.materialReferences())||!sameDefinition(old.speciesReferences(),d.speciesReferences())
                ||!sameDefinition(old.thermal(),d.thermal())||!sameDefinition(old.viscosity(),d.viscosity())
                ||!sameDefinition(old.forces(),d.forces())||!sameDefinition(old.surface(),d.surface()))return false;
        var slotted=l.slotInventory(data.inventory());
        if(slotted.stockCount()>l.stockCapacity()||slotted.speciesHandles().length>l.speciesCapacity()
                ||VulkanLiquidStateLayout.requiredVolumeCapacity(slotted,l.stockCapacity())>l.limbCapacity())return false;
        var gas=data.gas();
        if((gas==null)!=(t.gas==null))return false;
        if(gas!=null&&(!sameDefinition(gas.tables(),gasConfiguration.tables())||!sameDefinition(gas.ambient(),gasConfiguration.ambient())
                ||gas.cellSize()!=gasConfiguration.cellSize()||gas.sealedExterior()!=gasConfiguration.sealedExterior()
                ||gas.initial().cells().size()>t.gas.state.nodeCapacity()))return false;
        var granular=data.granular();
        if((granular==null)!=(t.granular==null))return false;
        if(granular!=null&&(!sameDefinition(granular.initial().definitions(),t.granular.state.definitions())
                ||granular.initial().particles().size()>t.granular.state.particleCapacity()||granular.initial().deposits().size()>t.granular.state.depositCapacity()
                ||granular.speciesCapacity()>t.granular.state.speciesCapacity()||!sameDefinition(granular.thermal(),granularConfiguration.thermal())
                ||!sameDefinition(granular.materialReferences(),granularConfiguration.materialReferences())||!sameDefinition(granular.speciesReferences(),granularConfiguration.speciesReferences())))return false;
        var coherent=data.coherent();
        if((coherent==null)!=(t.coherent==null))return false;
        if(coherent!=null&&(!sameDefinition(coherent.definitions(),t.coherent.state.definitions())||coherent.initial().size()>t.coherent.state.groupCapacity()
                ||coherent.speciesCount()>t.coherent.state.speciesCapacity()||!sameDefinition(coherent.thermal(),coherentConfiguration.thermal())
                ||!sameDefinition(coherent.materialReferences(),coherentConfiguration.materialReferences())||!sameDefinition(coherent.speciesReferences(),coherentConfiguration.speciesReferences())))return false;
        var device=data.device();
        if((device==null)!=(t.devices==null))return false;
        if(device!=null&&(!sameDefinition(device.definitions(),t.devices.state.definitions())||device.initial().ports().size()>t.devices.state.portCapacity()
                ||!sameDefinition(device.thermal(),deviceConfiguration.thermal())||!sameDefinition(device.materialReferences(),deviceConfiguration.materialReferences())))return false;
        var heat=data.heat();
        if((heat==null)!=(t.thermal==null))return false;
        if(heat!=null&&(heat.cellSize()!=thermalConfiguration.cellSize()||heat.bathTemperatures().length!=thermalConfiguration.bathTemperatures().length
                ||!sameDefinition(heat.tables(),thermalConfiguration.tables())||!sameDefinition(heat.materials(),thermalConfiguration.materials())))return false;
        var reactions=enabledReactions(data.reactions());
        if((reactions==null)!=(t.reactions==null))return false;
        return reactions==null||sameDefinition(reactions.definitions(),reactionConfiguration.definitions())
                &&reactions.gravity()==reactionConfiguration.gravity()&&reactions.precipitationEnabled()==reactionConfiguration.precipitationEnabled()
                &&reactions.initial().pending().size()<=t.reactions.progress().pendingCapacity();
    }
    private static boolean sameDefinition(Object left,Object right) {
        if(left==right)return true;
        if(left==null||right==null||left.getClass()!=right.getClass())return false;
        if(!left.getClass().isRecord())return java.util.Objects.deepEquals(left,right);
        try {for(var field:left.getClass().getRecordComponents())if(!sameDefinition(field.getAccessor().invoke(left),field.getAccessor().invoke(right)))return false;return true;}
        catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot compare device definitions",failure);}
    }
    synchronized Prepared prepareMechanical(Capture source,VulkanLiquidProjection.Parameters parameters){return prepareMechanical(source,parameters,source.inputs);}
    synchronized Prepared prepareMechanical(Capture source,VulkanLiquidProjection.Parameters parameters,RequestInputs request){var previous=activeInputs;activeInputs=request.pendingPhaseSettlementOnly()?request.stationary(source.inputs.bodies()):request;try{return prepareMechanicalCandidate(source,parameters);}finally{activeInputs=previous;}}
    private synchronized Prepared prepareMechanicalCandidate(Capture source,VulkanLiquidProjection.Parameters parameters){
        if(!source.capture.isCurrent()||source.layout!=workspace)throw new IllegalStateException("Mechanical request uses a stale region capture");
        var pin=source.retain();var allocated=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<VulkanLiquidWorkspace,Boolean>());allocated.add(workspace);
        try{var spent=new long[1];var result=prepareInterval(pin.capture,pin.layout,pin.geometryValid,parameters,pin.generation()+1,1,0,false,spent,allocated);
            var prepared=new Prepared(pin,result,allocated);prepared.mechanicalAttempts=spent[0];prepared.mechanicalDuration=parameters==null?0:parameters.duration();return prepared;}
        catch(RuntimeException|Error failure){pin.close();for(var w:allocated)if(w!=workspace)w.close();throw failure;}
    }
    long advanceThermal(double duration){try(var source=captureState();var prepared=prepareThermal(source,duration)){return prepared.commit();}}
    synchronized Prepared prepareThermal(Capture source,double duration,RequestInputs request){var previous=activeInputs;activeInputs=request;try{return prepareThermal(source,duration);}finally{activeInputs=previous;}}
    synchronized Prepared prepareThermal(Capture source,double duration){
        if(!(Double.isFinite(duration)&&duration>0)||workspace.transport==null||workspace.transport.thermal==null)throw new IllegalArgumentException("Thermal request needs positive duration and configured thermodynamics");
        if(!source.capture.isCurrent()||source.layout!=workspace)throw new IllegalStateException("Thermal request uses a stale region capture");
        var pin=source.retain();var allocated=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<VulkanLiquidWorkspace,Boolean>());allocated.add(workspace);VulkanLiquidWorkspace target=workspace;
        try{requestCellSize=thermalConfiguration.cellSize();target=bodyWorkspace(target,pin.inputs.bodies(),allocated);while(true){var candidate=storage.prepareReallocated(pin.capture,target.bytes);
            try{int error=target.executeThermal(pin.layout,pin.geometryValid,candidate,duration);if(error!=0)throw new IllegalStateException("Resident thermal candidate error="+error+VulkanRegionThermal.describeError(error));
                return new Prepared(pin,new PreparedInterval(target,candidate,true,true,false,1,0,mmm.world.MovingBodyFluidCoupling.Result.NONE),allocated);
            }catch(VulkanRegionThermal.ContactCapacityGrowth growth){candidate.close();target=target.growThermalContacts(growth.required,growth.baths);allocated.add(target);}
            catch(VulkanRegionReactions.CapacityGrowth growth){candidate.close();target=target.growReactions(growth);allocated.add(target);}
            catch(RuntimeException|Error failure){candidate.close();throw failure;}
        }}catch(RuntimeException|Error failure){pin.close();for(var w:allocated)if(w!=workspace)w.close();throw failure;}
    }
    private record PreparedInterval(VulkanLiquidWorkspace workspace,VulkanRegionStorage.Candidate candidate,boolean geometryValid,boolean gasNodesValid,boolean mechanicalObservation,int substeps,float lastLeafDuration,mmm.world.MovingBodyFluidCoupling.Result bodyResult) implements AutoCloseable{
        @Override public void close(){candidate.close();}
    }
    static final class MotionTimeRefinement extends IllegalStateException{MotionTimeRefinement(String reason){super(reason);}}
    static final class GranularCapacityGrowth extends IllegalStateException{final int[] required;GranularCapacityGrowth(int[] required){this.required=required;}}
    static final class ContactCapacityGrowth extends IllegalStateException{final int required;ContactCapacityGrowth(int required){this.required=required;}}
    private static mmm.world.MovingBodyFluidCoupling.Bounds scaledBounds(mmm.world.MovingBodyFluidCoupling.Bounds bounds,double fraction){
        double scale=Math.cbrt(fraction),x=(bounds.minimumX()+bounds.maximumX())*.5,y=(bounds.minimumY()+bounds.maximumY())*.5,z=(bounds.minimumZ()+bounds.maximumZ())*.5;
        return new mmm.world.MovingBodyFluidCoupling.Bounds(x+(bounds.minimumX()-x)*scale,y+(bounds.minimumY()-y)*scale,z+(bounds.minimumZ()-z)*scale,
                x+(bounds.maximumX()-x)*scale,y+(bounds.maximumY()-y)*scale,z+(bounds.maximumZ()-z)*scale);
    }
    private static mmm.fluid.EntityBodyState interpolateBodies(mmm.fluid.EntityBodyState source,mmm.fluid.EntityBodyState target,double fraction){
        var old=new java.util.HashMap<java.util.UUID,mmm.world.MovingBodyFluidCoupling.Request>();
        for(var body:source.bodies())old.put(body.bodyId(),body);
        var middle=new java.util.ArrayList<mmm.world.MovingBodyFluidCoupling.Request>();
        for(var body:target.bodies()){
            var previous=old.remove(body.bodyId());
            if(previous==null){
                if(fraction>0.0)middle.add(body.withBounds(scaledBounds(body.bounds(),fraction)));
                continue;
            }
            var a=previous.bounds();var b=body.bounds();
            var bounds=new mmm.world.MovingBodyFluidCoupling.Bounds(
                    a.minimumX()+(b.minimumX()-a.minimumX())*fraction,a.minimumY()+(b.minimumY()-a.minimumY())*fraction,a.minimumZ()+(b.minimumZ()-a.minimumZ())*fraction,
                    a.maximumX()+(b.maximumX()-a.maximumX())*fraction,a.maximumY()+(b.maximumY()-a.maximumY())*fraction,a.maximumZ()+(b.maximumZ()-a.maximumZ())*fraction);
            middle.add(body.withBounds(bounds));
        }
        if(fraction<1.0)for(var body:source.bodies())if(old.containsKey(body.bodyId()))middle.add(body.withBounds(scaledBounds(body.bounds(),1.0-fraction)));
        return new mmm.fluid.EntityBodyState(middle);
    }
    private static mmm.world.MovingBodyFluidCoupling.Result combineBodyResults(mmm.world.MovingBodyFluidCoupling.Result first,
            mmm.world.MovingBodyFluidCoupling.Result last){
        var combined=first.plus(last);
        return new mmm.world.MovingBodyFluidCoupling.Result(last.coupledMarkers(),last.coupledVolumeCubicMetres(),
                combined.impulseXKilogramMetresPerSecond(),combined.impulseYKilogramMetresPerSecond(),combined.impulseZKilogramMetresPerSecond(),combined.externalWorkJoules(),combined.bodyImpulses());
    }
    private PreparedInterval prepareInterval(VulkanRegionStorage.Capture capture,VulkanLiquidWorkspace source,boolean sourceGeometryValid,VulkanLiquidProjection.Parameters parameters,
            long requestGeneration,long ordinal,int depth,boolean previous,
            long[] spent,java.util.Set<VulkanLiquidWorkspace> allocated){
        var old=currentInputs.bodies();var target=requestedBodies();
        int plannedDepth=0;
        if(parameters!=null&&previousMechanicalLeafDuration>0){
            float interval=parameters.duration();
            while(plannedDepth<mmm.fluid.FluidStepBudget.MAXIMUM_TIME_REFINEMENTS
                    &&(1L<<(plannedDepth+1))<=previousMechanicalSubsteps
                    &&interval*.5f>=previousMechanicalLeafDuration){interval*=.5f;plannedDepth++;}
        }
        return prepareBodyInterval(capture,source,sourceGeometryValid,parameters,requestGeneration,ordinal,depth,plannedDepth,previous,spent,allocated,old,target,old,target,0.0,1.0,parameters==null?0.0:parameters.duration());
    }
    private PreparedInterval prepareBodyInterval(VulkanRegionStorage.Capture capture,VulkanLiquidWorkspace source,boolean sourceGeometryValid,VulkanLiquidProjection.Parameters parameters,
            long requestGeneration,long ordinal,int depth,int plannedDepth,boolean previous,long[] spent,java.util.Set<VulkanLiquidWorkspace> allocated,mmm.fluid.EntityBodyState sourceBodies,mmm.fluid.EntityBodyState targetBodies,
            mmm.fluid.EntityBodyState originalSource,mmm.fluid.EntityBodyState originalTarget,double start,double end,double requestDuration){
        if(depth<plannedDepth)return splitBodyInterval(capture,source,sourceGeometryValid,parameters,requestGeneration,ordinal,depth,plannedDepth,previous,spent,allocated,sourceBodies,targetBodies,originalSource,originalTarget,start,end,requestDuration);
        if(parameters!=null)requestCellSize=parameters.cellSize();
        VulkanLiquidWorkspace target=bodyWorkspace(source,sourceBodies,targetBodies,allocated);
        while(true){
            target.sourceBodyState=sourceBodies;
            target.targetBodyState=targetBodies;
            if(++spent[0]>mmm.fluid.FluidStepBudget.MAXIMUM_CUMULATIVE_SUBSTEPS)
                throw new mmm.fluid.FluidSolverBackend.MechanicalWorkBudgetExceededException("Resident mechanical request exceeded cumulative substep budget");
            var candidate=storage.prepareReallocated(capture,target.bytes);int[] required=null,granularRequired=null;VulkanRegionReactions.CapacityGrowth reactionGrowth=null;int contactRequired=0,thermalRequired=0,thermalBathRequired=0;
            try{
                long sequence=ordinal==1?requestGeneration:Long.rotateLeft(requestGeneration,17)^ordinal;
                int error=target.execute(source,sourceGeometryValid,candidate,parameters,sequence,previous,end==1.0,requestDuration);
                if(error==0)return new PreparedInterval(target,candidate,parameters!=null&&target.transport!=null&&(end==1.0||target.transport.entity==null),parameters!=null,parameters!=null,1,parameters==null?0:parameters.duration(),
                        parameters==null?mmm.world.MovingBodyFluidCoupling.Result.NONE:target.bodyResult(candidate));
                if(error==64&&target.transport!=null)required=target.transport.requirements(candidate);
                else throw target.failure(candidate,error,parameters);
            }catch(VulkanRegionReactions.CapacityGrowth growth){reactionGrowth=growth;}
            catch(GranularCapacityGrowth growth){granularRequired=growth.required;}
            catch(ContactCapacityGrowth growth){contactRequired=growth.required;}
            catch(VulkanRegionThermal.ContactCapacityGrowth growth){thermalRequired=growth.required;thermalBathRequired=growth.baths;}
            catch(MotionTimeRefinement finer){
                candidate.close();
                if(parameters==null||depth>=mmm.fluid.FluidStepBudget.MAXIMUM_TIME_REFINEMENTS)throw finer;
                return splitBodyInterval(capture,source,sourceGeometryValid,parameters,requestGeneration,ordinal,depth,plannedDepth,previous,spent,allocated,sourceBodies,targetBodies,originalSource,originalTarget,start,end,requestDuration);
            }catch(RuntimeException|Error failure){candidate.close();throw failure;}
            candidate.close();VulkanLiquidWorkspace larger=reactionGrowth!=null?target.growReactions(reactionGrowth):granularRequired!=null?target.growGranular(granularRequired):thermalRequired>0||thermalBathRequired>0?target.growThermalContacts(thermalRequired,thermalBathRequired):contactRequired>0?target.growContacts(contactRequired):target.grow(required);allocated.add(larger);
            if(growths!=null)growths.add(new Growth(requestGeneration,target.capacityStage,target.capacities(),larger.capacities(),reactionGrowth!=null?new int[]{reactionGrowth.species,reactionGrowth.limbs,reactionGrowth.particles,reactionGrowth.deposits,reactionGrowth.solidSpecies,reactionGrowth.coherentGroups,reactionGrowth.coherentSpecies}:granularRequired!=null?granularRequired:thermalRequired>0||thermalBathRequired>0?new int[]{thermalRequired,thermalBathRequired}:contactRequired>0?new int[]{contactRequired}:required.clone()));
            target=larger;
        }
    }
    private PreparedInterval splitBodyInterval(VulkanRegionStorage.Capture capture,VulkanLiquidWorkspace source,boolean sourceGeometryValid,VulkanLiquidProjection.Parameters parameters,
            long requestGeneration,long ordinal,int depth,int plannedDepth,boolean previous,long[] spent,java.util.Set<VulkanLiquidWorkspace> allocated,mmm.fluid.EntityBodyState sourceBodies,mmm.fluid.EntityBodyState targetBodies,
            mmm.fluid.EntityBodyState originalSource,mmm.fluid.EntityBodyState originalTarget,double start,double end,double requestDuration){
        float half=parameters.duration()*.5f;
        if(!(half>0&&half<parameters.duration()))throw new MotionTimeRefinement("Mechanical interval cannot be refined further");
        var firstParameters=new VulkanLiquidProjection.Parameters(parameters.cellSize(),half,parameters.gravity(),parameters.tolerance(),parameters.iterations());
        double midpoint=(start+end)*.5;var middle=interpolateBodies(originalSource,originalTarget,midpoint);
        try(var first=prepareBodyInterval(capture,source,sourceGeometryValid,firstParameters,requestGeneration,2*ordinal,depth+1,plannedDepth,previous,spent,allocated,sourceBodies,middle,originalSource,originalTarget,start,midpoint,requestDuration);
            var intermediate=first.candidate.captureCompleted()){
            var secondParameters=new VulkanLiquidProjection.Parameters(parameters.cellSize(),parameters.duration()-half,parameters.gravity(),parameters.tolerance(),parameters.iterations());
            var tail=prepareBodyInterval(intermediate,first.workspace,first.geometryValid,secondParameters,requestGeneration,2*ordinal+1,depth+1,plannedDepth,true,spent,allocated,middle,targetBodies,originalSource,originalTarget,midpoint,end,requestDuration);
            return new PreparedInterval(tail.workspace,tail.candidate,tail.geometryValid,tail.gasNodesValid,tail.mechanicalObservation,first.substeps+tail.substeps,tail.lastLeafDuration,combineBodyResults(first.bodyResult,tail.bodyResult));
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
        var m=storage.metrics();
        return new VulkanRegionStorage.Metrics(m.submissions(),m.uploadBytes(),m.readbackBytes(),m.copiedBytes(),m.gpuNanos(),m.recordingNanos(),
                m.waitingNanos(),m.allocatedGenerations(),m.deviceBytes(),m.stagingBytes());
    }
    synchronized VulkanPressureKernel.Progress pressureProgress(){return pressureProgress;}
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
    synchronized int[] gasInventoryCounts() {
        if(workspace.transport==null||workspace.transport.gas==null)return new int[0];
        try(var capture=storage.capture()) {
            var output=ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            storage.read(capture,workspace.transport.gas.state.column(VulkanGasStateLayout.Field.COUNTS).offset(),output);
            return new int[]{output.getInt(0),output.getInt(4),output.getInt(8)};
        }
    }
    synchronized VulkanLiquidStateLayout layout(){return workspace.liquid;}
    record GranularReserves(int particles,int deposits,int species){}
    record HeaderWrite(long offset,ByteBuffer bytes) { }
    static long headerBytes(List<HeaderWrite> headers){return headers.stream().mapToLong(h->h.bytes().remaining()).sum();}

    static final class Kernels implements AutoCloseable {
        final java.util.ArrayList<AutoCloseable> owned=new java.util.ArrayList<>();
        VulkanLiquidGeometry futureGeometry;
        VulkanLiquidCellVolumes volumeKernel;
        VulkanLiquidCapacityInputs inputKernel;
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
        VulkanLocalGasTransport localGasTransport;
        VulkanGasGeometry gasGeometry;
        VulkanGasThermodynamics gasThermo;
        VulkanStageTimings timings;
        VulkanGranularGeometry granularGeometryKernel;
        VulkanMixturePressure mixturePressureKernel;
        VulkanCoupledPressure coupledKernel;
        VulkanMixtureFlux mixtureFlux;
        VulkanGranularContacts contactKernel;
        VulkanGranularMotion motionKernel;
        VulkanGranularTransitions transitionKernel;
        VulkanLiquidThermodynamics granularThermalKernel;
        VulkanRegionThermal thermalKernel;
        VulkanCoherentGeometry coherentGeometryKernel;
        VulkanRegionReactions reactionKernel;VulkanActivityPending activityPending;

        Kernels(VulkanDeviceContext context,boolean gasEnabled,boolean granularEnabled,boolean thermalEnabled,boolean coherentEnabled,mmm.chemistry.ChemicalRegistry chemistry) {
            try {
                if(Boolean.getBoolean("mmm.gpuMechanicalStages"))timings=own(new VulkanStageTimings(context,128));
                futureGeometry=own(new VulkanLiquidGeometry(context));volumeKernel=own(new VulkanLiquidCellVolumes(context));
                inputKernel=own(new VulkanLiquidCapacityInputs(context));
                acceptedKernel=own(new VulkanLiquidAcceptedVolumes(context));materialKernel=own(new VulkanLiquidMaterialRoutes(context));
                quantityKernel=own(new VulkanLiquidRouteQuantities(context));gatherKernel=own(new VulkanLiquidGather(context));
                momentumKernel=own(new VulkanLiquidMomentum(context));liquidThermalKernel=own(new VulkanLiquidThermodynamics(context));ledger=own(new VulkanEnergyLedger(context));
                viscosityKernel=own(new VulkanLiquidViscosity(context));entityKernel=own(new VulkanEntityBodyCoupling(context));forceKernel=own(new VulkanLiquidForces(context));surfaceKernel=own(new VulkanSurfaceTension(context));heatTransportKernel=own(new VulkanMechanicalHeatTransport(context));
                bodiesKernel=own(new VulkanMechanicalBodies(context));energyKernel=own(new VulkanMechanicalEnergy(context));
                if(chemistry!=null){reactionKernel=own(new VulkanRegionReactions(context,chemistry));activityPending=own(new VulkanActivityPending(context));}
                if(coherentEnabled)coherentGeometryKernel=own(new VulkanCoherentGeometry(context));
                if(thermalEnabled)thermalKernel=own(new VulkanRegionThermal(context));
                if(gasEnabled){gasGeometry=own(new VulkanGasGeometry(context));gasThermo=own(new VulkanGasThermodynamics(context));
                    localGasTransport=own(new VulkanLocalGasTransport(context));}
                mixturePressureKernel=own(new VulkanMixturePressure(context));coupledKernel=own(new VulkanCoupledPressure(context));mixtureFlux=own(new VulkanMixtureFlux(context));
                if(granularEnabled){granularGeometryKernel=own(new VulkanGranularGeometry(context));
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

    static void copyColumn(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer source,VulkanLiquidStateLayout.Column from,
                                   GpuBuffer target,VulkanLiquidStateLayout.Column to,org.lwjgl.system.MemoryStack stack) {
        long bytes=Math.min(from.bytes(),to.bytes());if(bytes!=0)VulkanRegionStorage.copy(command,source,from.offset(),target,to.offset(),bytes,stack);
    }
    static int growCount(int current,int required) {
        if(required<0)throw new IllegalArgumentException("Device capacity requirement exceeds int32 indexing");
        return required>current?Math.max(required,Math.multiplyExact(Math.max(1,current),2)):current;
    }
    static int ceil(int value,int divisor){if(value<0)throw new IllegalArgumentException("Negative device capacity requirement");return (int)(((long)value+divisor-1)/divisor);}
    static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    static VulkanEnergyLedger.Stream stream(VulkanLiquidStateLayout.Column column,int coefficient){return new VulkanEnergyLedger.Stream(1,word(column),column.count(),2,coefficient);}
    @Override public void close(){try{workspace.close();if(kernels!=null)kernels.close();mixtureKernel.close();}finally{storage.close();}}
}
