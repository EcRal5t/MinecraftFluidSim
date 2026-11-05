package mmm.gpu;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static mmm.gpu.VulkanLiquidRegion.*;
import static org.lwjgl.vulkan.VK10.*;

/** Per-carrier layouts and their device migration and recording operations. */
final class VulkanLiquidWorkspaceSections {
    private final VulkanLiquidWorkspace workspace;
    private final VulkanLiquidRegion region;
    private final VulkanLiquidStateLayout liquid;
    private final EnumMap<GeometryField, VulkanLiquidStateLayout.Column> geometry;
    private final EnumMap<PressureField, VulkanLiquidStateLayout.Column> pressureColumns;
    private final GranularReserves granularReserves;
    private final int coherentSpeciesCapacity, contactCapacity;

    VulkanLiquidWorkspaceSections(VulkanLiquidWorkspace workspace) {
        this.workspace = workspace;
        this.region = workspace.region;
        this.liquid = workspace.liquid;
        this.geometry = workspace.geometry;
        this.pressureColumns = workspace.pressureColumns;
        this.granularReserves = workspace.granularReserves;
        this.coherentSpeciesCapacity = workspace.coherentSpeciesCapacity;
        this.contactCapacity = workspace.contactCapacity;
    }

    private long align(long value) { return Math.addExact(value, liquid.alignment() - 1) & -liquid.alignment(); }

    final class CoherentSection {
        final VulkanCoherentStateLayout state;
        final VulkanCoherentGeometry.Layout geometry;
        final VulkanLiquidStateLayout.Column materialReference,speciesReference;
        final VulkanLiquidThermodynamics.Layout thermal;
        final long endOffset;
        CoherentSection(long base){var c=region.coherentConfiguration;long a=liquid.alignment();
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
    final class DeviceSection {
        final VulkanThermalDeviceLayout state;
        final VulkanLiquidStateLayout.Column materialReference,speciesReference;
        final VulkanLiquidThermodynamics.Layout thermal;
        final VulkanThermalDeviceLayout.Settlement settlement;
        final long endOffset;
        DeviceSection(long base){var c=region.deviceConfiguration;long a=liquid.alignment();
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
    final class GranularSection {
        final VulkanGranularStateLayout state;
        final VulkanGranularGeometry.Layout geometry;
        final VulkanLiquidStateLayout.Column materialReference,speciesReference;
        final VulkanLiquidThermodynamics.Layout thermal;
        final long endOffset;
        GranularSection(long base){var config=region.granularConfiguration;long a=liquid.alignment();
            state=new VulkanGranularStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),granularReserves.particles(),granularReserves.deposits(),granularReserves.species(),config.initial().definitions(),base,a);
            geometry=new VulkanGranularGeometry.Layout(state,workspace.geometry.get(GeometryField.OCCUPIED_SOLID),state.endOffset(),a);
            materialReference=new VulkanLiquidStateLayout.Column(align(geometry.endOffset()),config.materialReferences().length,8);
            speciesReference=new VulkanLiquidStateLayout.Column(align(materialReference.offset()+materialReference.bindingBytes()),config.speciesReferences().length,8);
            thermal=VulkanLiquidThermodynamics.layout(state,config.thermal(),word(materialReference),word(speciesReference),config.speciesStride(),speciesReference.offset()+speciesReference.bindingBytes(),a);endOffset=thermal.endOffset();
        }
        List<VulkanLiquidStateLayout.Column> persistentColumns(){return List.of(new VulkanLiquidStateLayout.Column(4L*geometry.headerWordOffset(),48,4),materialReference,speciesReference,
                thermal.header(),thermal.offsets(),thermal.upper(),thermal.capacity(),thermal.referenceTemperature(),thermal.coldSensible());}
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(4L*geometry.headerWordOffset(),geometry.headerBytes()),new HeaderWrite(thermal.header().offset(),thermal.headerBytes()));}
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,GranularSection old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            state.copyColumns(command,input,old.state,output,stack);var from=old.persistentColumns();var to=persistentColumns();for(int i=1;i<from.size();i++)if(i!=3)copyColumn(command,input,from.get(i),output,to.get(i),stack);
            copyColumn(command,input,old.owner().transport.pressure.assembly.column(VulkanMixturePressure.Field.DIAMETERS),output,workspace.transport.pressure.assembly.column(VulkanMixturePressure.Field.DIAMETERS),stack);
            copyColumn(command,input,oldWorkspaceDynamics(old).motion.columns.get(VulkanGranularMotion.Field.MATERIAL),output,workspace.transport.dynamics.motion.columns.get(VulkanGranularMotion.Field.MATERIAL),stack);
            copyColumn(command,input,oldWorkspaceDynamics(old).transitions.column(VulkanGranularTransitions.Field.MATERIALS),output,workspace.transport.dynamics.transitions.column(VulkanGranularTransitions.Field.MATERIALS),stack);
            var oldSnapshot=oldWorkspaceDynamics(old).snapshotThermal;var nextSnapshot=workspace.transport.dynamics.snapshotThermal;
            var oldTables=new VulkanLiquidStateLayout.Column[]{oldSnapshot.offsets(),oldSnapshot.upper(),oldSnapshot.capacity(),oldSnapshot.referenceTemperature(),oldSnapshot.coldSensible()};
            var nextTables=new VulkanLiquidStateLayout.Column[]{nextSnapshot.offsets(),nextSnapshot.upper(),nextSnapshot.capacity(),nextSnapshot.referenceTemperature(),nextSnapshot.coldSensible()};
            for(int i=0;i<oldTables.length;i++)copyColumn(command,input,oldTables[i],output,nextTables[i],stack);
        }
        private GranularDynamics oldWorkspaceDynamics(GranularSection value){return value.owner().transport.dynamics;}
        private VulkanLiquidWorkspace owner(){return workspace;}
    }
    final class PressureSection {
        final VulkanCoupledPressure.Layout coupled;
        final VulkanMixturePressure.Layout assembly;
        final VulkanMixtureFlux.Layout constraint;
        final long endOffset;
        PressureSection(GranularSection granular,GasSection gas,VulkanMixtureState.Layout mixture,VulkanLiquidStateLayout.Column viscosity,VulkanLiquidStateLayout.Column bodyFaceAreas,VulkanLiquidStateLayout.Column targetSolid,long base){
            int particles=granular==null?0:granular.state.particleCapacity(),faces=geometry.get(GeometryField.FACES).count()/4;long a=liquid.alignment();
            coupled=new VulkanCoupledPressure.Layout(liquid.cellCount(),pressureColumns.get(PressureField.ROWS).count(),faces+3*particles,2*faces+75*particles,new VulkanCoupledPressure.PressureBuffers(pressureColumns.get(PressureField.STARTS),pressureColumns.get(PressureField.COLUMNS),pressureColumns.get(PressureField.VALUES),pressureColumns.get(PressureField.ROWS),pressureColumns.get(PressureField.PRESSURE),pressureColumns.get(PressureField.WORK),pressureColumns.get(PressureField.PROBLEM),pressureColumns.get(PressureField.CONTROL)),base,a);
            assembly=new VulkanMixturePressure.Layout(liquid,granular==null?null:granular.geometry,
                    granular==null?geometry.get(GeometryField.OCCUPIED_SOLID):granular.geometry.column(VulkanGranularGeometry.Field.FIXED_SVQ),coupled,
                    geometry.get(GeometryField.CELLS),geometry.get(GeometryField.FACES),mixture.states(),geometry.get(GeometryField.FIXED_MASKS),viscosity,bodyFaceAreas,coupled.endOffset(),a);
            assembly.bindActivity(word(workspace.activityCells),word(workspace.activityFaces));
            constraint=new VulkanMixtureFlux.Layout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),geometry.get(GeometryField.FACES),geometry.get(GeometryField.FLUXES),gas!=null,mixture.states(),
                    geometry.get(GeometryField.FIXED_MASKS),targetSolid,gas==null?null:gas.thermo.states(),gas==null||region.gasConfiguration.sealedExterior(),assembly.endOffset(),a);
            endOffset=constraint.endOffset();
        }
        List<VulkanLiquidStateLayout.Column> persistentColumns(){return List.of(new VulkanLiquidStateLayout.Column(4L*coupled.headerWordOffset(),64,4),new VulkanLiquidStateLayout.Column(4L*assembly.headerWordOffset(),96,4),assembly.column(VulkanMixturePressure.Field.DIAMETERS),constraint.header());}
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(4L*coupled.headerWordOffset(),coupled.headerBytes()),new HeaderWrite(4L*assembly.headerWordOffset(),assembly.headerBytes()),new HeaderWrite(constraint.header().offset(),constraint.headerBytes()));}
    }
    final class GranularDynamics {
        final VulkanGranularContacts.Layout contacts;
        final VulkanGranularMotion.Layout motion;
        final VulkanGranularTransitions.Layout transitions;
        final VulkanGranularGeometry.Layout snapshotGeometry;
        final VulkanLiquidThermodynamics.Layout snapshotThermal;
        final long endOffset;
        GranularDynamics(GranularSection granular,PressureSection common,VulkanLiquidStateLayout.Column viscosity,long base){long a=liquid.alignment();var pressure=common.assembly;
            contacts=new VulkanGranularContacts.Layout(granular.state,pressure,contactCapacity,base,a);
            motion=new VulkanGranularMotion.Layout(granular.state,contacts,pressure,word(geometry.get(GeometryField.FIXED_MASKS)),contacts.endOffset(),a);motion.bindBedGeometry(granular.geometry);motion.bindActivity(word(workspace.activityCells));transitions=new VulkanGranularTransitions.Layout(granular.state,granular.geometry,geometry.get(GeometryField.CELLS),geometry.get(GeometryField.FACES),liquid,geometry.get(GeometryField.FIXED_MASKS),viscosity,motion.endOffset(),a);transitions.bindActivity(word(workspace.activityCells));
            snapshotGeometry=new VulkanGranularGeometry.Layout(transitions.source,geometry.get(GeometryField.OCCUPIED_SOLID),transitions.endOffset(),a);
            snapshotThermal=VulkanLiquidThermodynamics.layout(transitions.source,region.granularConfiguration.thermal(),word(granular.materialReference),word(granular.speciesReference),region.granularConfiguration.speciesStride(),snapshotGeometry.endOffset(),a);
            endOffset=snapshotThermal.endOffset();
        }
        List<VulkanLiquidStateLayout.Column> persistentColumns(){return List.of(contacts.header(),motion.header,motion.columns.get(VulkanGranularMotion.Field.MATERIAL),transitions.header,transitions.column(VulkanGranularTransitions.Field.MATERIALS),
                new VulkanLiquidStateLayout.Column(4L*snapshotGeometry.headerWordOffset(),48,4),snapshotThermal.header(),snapshotThermal.offsets(),snapshotThermal.upper(),snapshotThermal.capacity(),snapshotThermal.referenceTemperature(),snapshotThermal.coldSensible());}
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(contacts.header().offset(),contacts.headerBytes()),new HeaderWrite(motion.header.offset(),motion.headerBytes()),new HeaderWrite(transitions.header.offset(),transitions.headerBytes()),
                new HeaderWrite(4L*snapshotGeometry.headerWordOffset(),snapshotGeometry.headerBytes()),new HeaderWrite(snapshotThermal.header().offset(),snapshotThermal.headerBytes()));}
    }
    /** Fixed cell gas inventory, local mixture closure, and direct face transport. */
    final class GasSection {
        final VulkanGasStateLayout state;
        final VulkanLiquidStateLayout.Column volumes;
        final VulkanGasThermodynamics.Layout thermo;
        final VulkanMixtureState.Layout mixture;
        final VulkanGasGeometry.Layout geometry;
        final VulkanLocalGasTransport.ProductionLayout transport;
        final long endOffset;
        GasSection(VulkanLiquidCellVolumes.Layout totals,long base,int occupiedWord) {
            var config=region.gasConfiguration;long a=liquid.alignment();int cells=liquid.cellCount();
            state=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),cells,config.tables().definitions(),base,a);
            volumes=new VulkanLiquidStateLayout.Column(align(state.endOffset()),cells,8);
            thermo=VulkanGasThermodynamics.layout(state,config.tables(),word(volumes),volumes.offset()+volumes.bindingBytes(),a);
            int cellWord=word(workspace.geometry.get(GeometryField.CELLS));
            mixture=VulkanMixtureState.layout(new VulkanMixtureState.Inputs(cells,cellWord,4,cellWord+2,4,cellWord+4,4,
                    thermo,config.cellSize(),1,cellWord),thermo.endOffset(),a);
            geometry=VulkanGasGeometry.layout(totals,word(workspace.geometry.get(GeometryField.FIXED_MASKS)),occupiedWord,
                    cellWord,word(workspace.geometry.get(GeometryField.PLANES)),word(workspace.geometry.get(GeometryField.LIQUID_MICRO)),
                    config.sealedExterior(),config.cellSize(),mixture.endOffset(),a).withMixture(mixture);
            transport=new VulkanLocalGasTransport.ProductionLayout(state,thermo,mixture,word(workspace.geometry.get(GeometryField.FLUXES)),
                    word(workspace.geometry.get(GeometryField.FACES))+2,4,word(pressureColumns.get(PressureField.PRESSURE)),
                    word(workspace.activityCells),word(workspace.activityFaces),config.sealedExterior(),config.ambient(),config.speciesMasses(),geometry.endOffset(),a);
            endOffset=transport.endOffset;
        }
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(geometry.header().offset(),geometry.headerBytes()),
                new HeaderWrite(thermo.header().offset(),thermo.headerBytes()),new HeaderWrite(mixture.header().offset(),mixture.headerBytes()),
                new HeaderWrite(transport.column(VulkanLocalGasTransport.Field.HEADER).offset(),transport.headerBytes()),
                new HeaderWrite(transport.column(VulkanLocalGasTransport.Field.AMBIENT_DATA).offset(),transport.ambientBytes()),
                new HeaderWrite(transport.column(VulkanLocalGasTransport.Field.SPECIES_MASS).offset(),transport.speciesMassBytes()));}
        void recordSource(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,double dt,VulkanLiquidGeometry geometryKernel){
            String phase=geometryKernel==region.kernel?"source":"future";
            recordThermodynamics(command,output,errors,dt,phase);
            var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            region.kernels.timed(command,phase+"-liquid-shapes",()->geometryKernel.recordShapes(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ()));
            region.kernels.gasGeometry.rebindRanges(arena,err);
            region.kernels.timed(command,phase+"-gas-geometry",()->region.kernels.gasGeometry.recordLocalGeometry(command,geometry));
        }
        void recordThermodynamics(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,double dt){
            recordThermodynamics(command,output,errors,dt,"future");
        }
        private void recordThermodynamics(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,double dt,String phase){
            var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            region.kernels.gasThermo.rebindRanges(arena,err);
            region.kernels.timed(command,phase+"-gas-caloric",()->region.kernels.gasThermo.recordCaloric(command,thermo));
            region.mixtureKernel.rebindRanges(arena,err);
            region.kernels.timed(command,phase+"-mixture",()->region.mixtureKernel.record(command,mixture,region.requestCellSize,dt));
        }
        void recordTransport(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,
                             org.lwjgl.system.MemoryStack stack,double dt){
            region.kernels.localGasTransport.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
            region.kernels.timed(command,"local-gas-transport",()->region.kernels.localGasTransport.record(command,transport,stack,dt,region.kernels.timings));
        }
        void recordPressureWork(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors){
            region.kernels.localGasTransport.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
            region.kernels.timed(command,"local-gas-pressure-work",()->region.kernels.localGasTransport.recordPressureWork(command,transport,mixture.statesWord()));
        }
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,GasSection old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            state.copyColumns(command,input,old.state,output,stack);copyColumn(command,input,old.volumes,output,volumes,stack);
            var from=new VulkanLiquidStateLayout.Column[]{old.thermo.segmentOffsets(),old.thermo.segmentUpper(),old.thermo.segmentHeatCapacity(),old.thermo.specificAnchorEnergy(),old.thermo.speciesReference()};
            var to=new VulkanLiquidStateLayout.Column[]{thermo.segmentOffsets(),thermo.segmentUpper(),thermo.segmentHeatCapacity(),thermo.specificAnchorEnergy(),thermo.speciesReference()};
            for(int i=0;i<from.length;i++)copyColumn(command,input,from[i],output,to[i],stack);
        }
    }
}
