package mmm.gpuprobe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mmm.fluid.FluidDomainState;
import mmm.fluid.FluidMaterial;
import mmm.fluid.FluidMaterialRegistry;
import mmm.inventory.DeviceLiquidInventory;
import mmm.thermal.FiniteThermalDeviceInventory;
import mmm.thermal.ThermalAuditLedger;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalOwnerSnapshot;

/** One-time host import and explicit checkpoint export. Normal requests retain device captures. */
final class VulkanFluidStateCodec {
    private final FluidDomainState template;
    private final FiniteThermalDeviceInventory devices;
    private final List<VulkanThermalDeviceLayout.Port> ports;
    private final FluidMaterialRegistry fluids;
    private final VulkanReactionDefinitions.Tables reactionDefinitions;
    VulkanFluidStateCodec(FluidDomainState template){this(template,null,List.of());}
    VulkanFluidStateCodec(FluidDomainState template,FiniteThermalDeviceInventory devices,List<VulkanThermalDeviceLayout.Port> ports){
        this.template=java.util.Objects.requireNonNull(template);this.devices=devices;this.ports=List.copyOf(ports);
        var palette=template.materialPalette().isEmpty()?List.of(new FluidMaterial("mmm:water",template.config().density(),"mmm:water",0,Math.PI/2)):template.materialPalette();
        var groups=new FluidMaterialRegistry(palette);var pairs=new ArrayList<FluidMaterialRegistry.LiquidPairTension>();
        if(template.materialDefinitions()!=null)for(int a=0;a<groups.phaseGroups().size();a++)for(int b=a+1;b<groups.phaseGroups().size();b++){
            double tension=template.materialDefinitions().liquidPairTension(groups.phaseGroups().get(a),groups.phaseGroups().get(b));
            if(tension>0)pairs.add(new FluidMaterialRegistry.LiquidPairTension(a,b,tension));
        }
        fluids=new FluidMaterialRegistry(palette,pairs);
        if(template.thermalRegistry()!=null&&template.chemicalRegistry()!=null){var granular=template.granularRegistry();if(granular==null){var builtin=mmm.granular.BuiltinGranularDefinitions.createRegistry();granular=new mmm.granular.GranularRegistry(builtin.materials(),builtin.carriers());}
            reactionDefinitions=VulkanReactionDefinitions.fromRegistries(template.chemicalRegistry(),granular,template.thermalRegistry(),template.gasRegistry());
        }else reactionDefinitions=null;
    }
    FluidDomainState template(){return template;}
    FluidDomainState.Layout layout(){return template.layout();}
    FluidMaterialRegistry fluids(){return fluids;}
    VulkanLiquidRegion importRegion(VulkanDeviceContext context,VulkanLiquidGeometry geometry,VulkanLiquidProjection projection,VulkanPressureKernel pressure,VulkanPressureMultigrid multigrid){
        var inventory=DeviceLiquidInventory.encode(template.liquid());var thermal=template.thermalRegistry();var owner=template.thermalOwner();
        var chemistry=template.chemicalRegistry();var gasChemistry=chemistry==null?mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY:chemistry;
        var species=gasChemistry.species().stream().map(mmm.chemistry.ChemicalSpecies::id).toList();
        int stride=Math.max(1,chemistry==null?0:chemistry.species().size()),materials=fluids.size();
        int[] phases=new int[materials];long[] speciesMass=new long[stride];double[] reference=new double[materials],speciesReference=new double[materials*stride];Arrays.fill(speciesReference,Double.NaN);
        if(chemistry!=null)for(int i=0;i<stride;i++)speciesMass[i]=chemistry.speciesMassUnits(i,1);
        var liquidMaterials=new ArrayList<ThermalMaterialDefinition>();
        for(int m=0;m<materials;m++){
            phases[m]=fluids.phaseHandle(m);
            if(thermal==null)liquidMaterials.add(new ThermalMaterialDefinition("mmm:resident_isothermal_"+m,ThermalMaterialDefinition.CarrierKind.LIQUID,fluids.material(m).id(),293.15,0,0,List.of(new ThermalMaterialDefinition.CaloricSegment(mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K,100000,4200))));
            else{
                int host=thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID,fluids.material(m).id());var definition=thermal.material(host);liquidMaterials.add(definition);reference[m]=definition.referenceSpecificEnergyJPerKg();
                if(chemistry!=null)for(int s=0;s<stride;s++)if(thermal.hasSpeciesOffset(host,chemistry.species(s).id()))speciesReference[m*stride+s]=thermal.speciesOffset(host,chemistry.species(s).id()).referenceInternalEnergyJPerMol().divide(mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL).toDouble();
            }
        }
        var constitutive=template.materialDefinitions();
        int[] zeroOffsets=new int[materials+1];double[] zeroSegments=new double[4*materials];for(int m=0;m<materials;m++){zeroOffsets[m+1]=m+1;zeroSegments[4*m]=mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;zeroSegments[4*m+1]=100000;}
        var viscosity=constitutive==null?new VulkanLiquidViscosity.Tables(zeroOffsets,zeroSegments):VulkanLiquidViscosity.tables(fluids,constitutive);
        double[] temperatures=new double[materials];Arrays.fill(temperatures,293.15);
        var forces=thermal==null||constitutive==null?new VulkanLiquidForces.Tables(temperatures,new double[materials]):VulkanLiquidForces.tables(fluids,constitutive);
        int cells=inventory.cellCount(),stocks=Math.max(1,inventory.stockCount());
        var definitions=new VulkanLiquidRegion.MechanicalDefinitions(stocks,inventory.speciesHandles().length,Math.max(1,inventory.volumes().limbs().length),4,-40,phases,speciesMass,reference,speciesReference,stride,
                VulkanLiquidThermodynamics.tables(liquidMaterials),viscosity,forces,VulkanSurfaceTension.tables(fluids),template.config().viscousWallCondition()==mmm.fluid.FluidSolverConfig.WallCondition.NO_SLIP);
        VulkanLiquidRegion.GasConfiguration gas=null;
        var gasMaterials=new ArrayList<ThermalMaterialDefinition>();
        if(template.hasGas()){
            var snapshot=template.gas();var registry=template.gasRegistry();
            var gasDefinitions=new VulkanGasStateLayout.Definitions(registry.fingerprint(),snapshot.chemicalRegistryFingerprint(),registry.components().stream().map(mmm.fluid.air.GasComponentDefinition::id).toList(),species,registry.components().stream().map(mmm.fluid.air.GasComponentDefinition::allowedSpecies).toList());
            var tables=thermal==null?VulkanGasThermodynamics.Tables.isothermal(gasDefinitions):VulkanGasThermodynamics.tables(gasDefinitions,registry,thermal,gasChemistry);
            gas=VulkanLiquidRegion.GasConfiguration.forState(new VulkanGasStateLayout.State(snapshot,owner==null?new double[snapshot.nodes().size()]:owner.copyGasNodeEnergy()),tables,template.config().cellSize(),template.sealedDomainBoundary(),VulkanGasRemap.ambientTables(registry,gasDefinitions,owner==null?mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K:owner.insertionTemperatureK()));
            if(thermal!=null)for(String id:gasDefinitions.componentIds())gasMaterials.add(thermal.material(thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS,id)));
        }
        VulkanGranularStateLayout.Configuration granular=null;
        var granularMaterials=new ArrayList<ThermalMaterialDefinition>();
        if(template.granular()!=null){
            var snapshot=template.granular();var state=VulkanGranularStateLayout.State.fromSnapshot(snapshot,species,owner==null?new double[snapshot.activeParcels().size()]:owner.copyActiveParcelEnergy(),owner==null?new double[snapshot.depositGroups().size()]:owner.copyDepositEnergy(),template.config().cellSize()).withDynamics(template.granularDynamics());
            if(thermal==null){
                for(String id:state.definitions().materialIds())granularMaterials.add(new ThermalMaterialDefinition(id,ThermalMaterialDefinition.CarrierKind.GRANULAR,id,293.15,0,0,List.of(new ThermalMaterialDefinition.CaloricSegment(mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K,100000,800))));
                granular=new VulkanGranularStateLayout.Configuration(state,template.granularRegistry(),VulkanLiquidThermodynamics.tables(granularMaterials),new double[granularMaterials.size()],new double[granularMaterials.size()*Math.max(1,species.size())],Math.max(1,species.size()),Math.max(1,4*state.particles().size()));
            }else{
                granular=VulkanGranularStateLayout.Configuration.fromRegistries(state,template.granularRegistry(),gasChemistry,thermal,Math.max(1,4*state.particles().size()));
                for(String id:state.definitions().materialIds())granularMaterials.add(thermal.material(thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR,id)));
            }
        }
        VulkanCoherentStateLayout.Configuration coherent=null;VulkanThermalDeviceLayout.Configuration device=null;VulkanRegionThermal.Configuration heat=null;
        var coherentMaterials=new ArrayList<ThermalMaterialDefinition>();var deviceMaterials=new ArrayList<ThermalMaterialDefinition>();long[] occupied=new long[cells];
        if(thermal!=null){
            coherent=VulkanCoherentStateLayout.Configuration.fromRegistries(owner.coherentGroups(),species,thermal,gasChemistry);
            for(var group:owner.coherentGroups())occupied[group.cell()]=Math.addExact(occupied[group.cell()],group.occupiedVolumeSvq());
            for(String id:coherent.definitions().phaseIds())coherentMaterials.add(thermal.material(thermal.materialHandle(id)));
            if(devices!=null){device=VulkanThermalDeviceLayout.Configuration.fromRegistries(new VulkanThermalDeviceLayout.State(devices,ports),thermal);for(String id:device.definitions().materialIds())deviceMaterials.add(thermal.material(thermal.materialHandle(id)));}
            var bath=template.copyThermalBoundaryTemperaturesK();heat=VulkanRegionThermal.Configuration.fromMaterials(liquidMaterials,gasMaterials,granularMaterials,coherentMaterials,deviceMaterials,template.config().cellSize(),bath==null?new double[0]:bath,1);
        }
        VulkanRegionReactions.Configuration reactions=null;
        if(reactionDefinitions!=null){
            var pending=template.chemicalMassTransferPending().stream().map(p->{int rule=reactionDefinitions.identity().rules().indexOf(p.definitionId());if(rule<0)throw new IllegalArgumentException("Pending transfer is absent from GPU rules");return new VulkanReactionProgressLayout.Pending(p.cell(),p.material(),p.supportMask(),rule,p.pendingSeconds(),p.residualMoles());}).toList();
            var dirty=template.copyChemicalDirtyCells();var phase=template.pendingPhaseMotion()==null?new int[0]:template.pendingPhaseMotion().entries().stream().mapToInt(mmm.fluid.PendingPhaseMotionState.Entry::cell).toArray();
            var progress=new VulkanReactionProgressLayout.State(template.chemicalReactionCursor(),0,pending,dirty==null?new int[0]:dirty,phase);reactions=new VulkanRegionReactions.Configuration(reactionDefinitions,fluids,chemistry,progress,template.config().gravity(),template.precipitationEnabled(),thermal,template.gasRegistry());
        }
        var region=new VulkanLiquidRegion(context,geometry,projection,pressure,multigrid,inventory,template.momentum(),template.fixedGeometry(),occupied,definitions,gas,granular,heat,coherent,device,reactions,null);region.initializeInputs(template.entityBodyState());return region;
    }
    FluidDomainState export(VulkanLiquidRegion.Capture capture,ThermalAuditLedger audit){
        var progress=capture.reactionProgress();
        var pending=reactionDefinitions==null?List.<mmm.chemistry.RegionChemistry.MassTransferPending>of():progress.pending().stream().map(p->new mmm.chemistry.RegionChemistry.MassTransferPending(p.cell(),p.material(),p.support(),reactionDefinitions.identity().rules().get(p.rule()),p.seconds(),p.residualMoles())).sorted().toList();
        var liquid=capture.liquid();var granular=capture.hasGranular()?capture.granular():null;var gas=capture.hasGas()?capture.gas():null;
        ThermalOwnerSnapshot owners=null;
        if(template.thermalOwner()!=null){
            owners=new ThermalOwnerSnapshot(template.thermalRegistry().fingerprint(),template.thermalOwner().insertionTemperatureK(),granular==null?new double[0]:granular.particles().stream().mapToDouble(p->p.inventory().internalEnergy()).toArray(),
                    granular==null?new double[0]:granular.depositEnergy(),gas==null?new double[0]:gas.nodeEnergy(),capture.hasCoherent()?capture.coherent():List.of(),java.util.Objects.requireNonNull(audit));
            double[] energy=Arrays.stream(liquid.inventory().energyBits()).mapToDouble(Double::longBitsToDouble).toArray();owners.requireClosed(mmm.units.SignedDyadic.sum(energy),template.sizeX()*template.sizeY()*template.sizeZ());
        }
        var physicalGranular=granular==null?null:granular.toSnapshot(template.sizeX(),template.sizeY(),template.sizeZ(),template.config().cellSize());
        return new FluidDomainState(template.sizeX(),template.sizeY(),template.sizeZ(),template.config(),template.sealedDomainBoundary(),liquid.inventory().decode(),capture.generation()==0?template.phaseOccupancy():capture.occupancy(),liquid.momentum(),template.fixedGeometry(),capture.entityBodies(),template.materialPalette(),template.chemicalRegistry(),template.chemicalSource(),template.chemicalSink(),template.chemicalRegistry()==null?null:capture.generation()==0?template.copyChemicalDirtyCells():progress.dirtyCells(),capture.generation()==0?template.chemicalReactionCursor():progress.cursor(),capture.generation()==0?template.chemicalMassTransferPending():pending,
                template.granularRegistry(),physicalGranular,granular==null?null:granular.schedulerState(template.sizeX(),template.sizeY(),template.sizeZ()),template.precipitationEnabled(),template.gasRegistry(),gas==null?null:FluidDomainState.GasContinuation.persisted(gas.snapshot()),template.thermalRegistry(),owners,template.maximumThermalTransportSubsteps(),template.thermalOwner()==null?null:capture.generation()==0?template.pendingPhaseMotion():capture.pendingPhaseMotion(),template.copyThermalBoundaryTemperaturesK(),template.thermalOwner()==null?null:capture.generation()==0?template.exportCondensedPhasePressure():capture.pressure(),template.materialDefinitions(),template.activityState());
    }
    FiniteThermalDeviceInventory exportDevices(VulkanLiquidRegion.Capture capture){return capture.hasDevices()?capture.devices().inventory():devices;}
}
