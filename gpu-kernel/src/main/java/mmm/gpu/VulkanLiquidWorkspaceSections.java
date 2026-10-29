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
    private final GasReserves gasReserves;
    private final GranularReserves granularReserves;
    private final int coherentSpeciesCapacity, contactCapacity;

    VulkanLiquidWorkspaceSections(VulkanLiquidWorkspace workspace) {
        this.workspace = workspace;
        this.region = workspace.region;
        this.liquid = workspace.liquid;
        this.geometry = workspace.geometry;
        this.pressureColumns = workspace.pressureColumns;
        this.gasReserves = workspace.gasReserves;
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
            copyColumn(command,input,oldWorkspaceDynamics(old).pressure.column(VulkanGranularPressure.Field.DIAMETERS),output,workspace.transport.dynamics.pressure.column(VulkanGranularPressure.Field.DIAMETERS),stack);
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
    final class GranularDynamics {
        final VulkanCoupledPressure.Layout coupled;
        final VulkanGranularPressure.Layout pressure;
        final VulkanGranularContacts.Layout contacts;
        final VulkanGranularMotion.Layout motion;
        final VulkanGranularTransitions.Layout transitions;
        final VulkanGranularGeometry.Layout snapshotGeometry;
        final VulkanLiquidThermodynamics.Layout snapshotThermal;
        final long endOffset;
        GranularDynamics(GranularSection granular,GasSection gas,VulkanLiquidStateLayout.Column viscosity,VulkanLiquidStateLayout.Column bodyFaceAreas,long base){int particles=granular.state.particleCapacity(),faces=gas.geometry.faces();long a=liquid.alignment();
            coupled=new VulkanCoupledPressure.Layout(liquid.cellCount(),pressureColumns.get(PressureField.ROWS).count(),faces+3*particles,2*faces+75*particles,gas.operator.pressureBuffers(),base,a);
            pressure=new VulkanGranularPressure.Layout(granular.geometry,gas.operator,coupled,geometry.get(GeometryField.FIXED_MASKS),viscosity,bodyFaceAreas,coupled.endOffset(),a);
            pressure.bindActivity(word(workspace.activityCells),word(workspace.activityFaces));
            contacts=new VulkanGranularContacts.Layout(granular.state,pressure,contactCapacity,pressure.endOffset(),a);
            motion=new VulkanGranularMotion.Layout(granular.state,contacts,pressure,word(geometry.get(GeometryField.FIXED_MASKS)),contacts.endOffset(),a);motion.bindBedGeometry(granular.geometry);motion.bindActivity(word(workspace.activityCells));transitions=new VulkanGranularTransitions.Layout(granular.state,granular.geometry,gas.operator,liquid,geometry.get(GeometryField.FIXED_MASKS),viscosity,motion.endOffset(),a);transitions.bindActivity(word(workspace.activityCells));
            snapshotGeometry=new VulkanGranularGeometry.Layout(transitions.source,geometry.get(GeometryField.OCCUPIED_SOLID),transitions.endOffset(),a);
            snapshotThermal=VulkanLiquidThermodynamics.layout(transitions.source,region.granularConfiguration.thermal(),word(granular.materialReference),word(granular.speciesReference),region.granularConfiguration.speciesStride(),snapshotGeometry.endOffset(),a);
            endOffset=snapshotThermal.endOffset();
        }
        List<VulkanLiquidStateLayout.Column> persistentColumns(){return List.of(new VulkanLiquidStateLayout.Column(4L*coupled.headerWordOffset(),64,4),new VulkanLiquidStateLayout.Column(4L*pressure.headerWordOffset(),96,4),
                pressure.column(VulkanGranularPressure.Field.DIAMETERS),contacts.header(),motion.header,motion.columns.get(VulkanGranularMotion.Field.MATERIAL),transitions.header,transitions.column(VulkanGranularTransitions.Field.MATERIALS),
                new VulkanLiquidStateLayout.Column(4L*snapshotGeometry.headerWordOffset(),48,4),snapshotThermal.header(),snapshotThermal.offsets(),snapshotThermal.upper(),snapshotThermal.capacity(),snapshotThermal.referenceTemperature(),snapshotThermal.coldSensible());}
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(4L*coupled.headerWordOffset(),coupled.headerBytes()),new HeaderWrite(4L*pressure.headerWordOffset(),pressure.headerBytes()),
                new HeaderWrite(contacts.header().offset(),contacts.headerBytes()),new HeaderWrite(motion.header.offset(),motion.headerBytes()),new HeaderWrite(transitions.header.offset(),transitions.headerBytes()),
                new HeaderWrite(4L*snapshotGeometry.headerWordOffset(),snapshotGeometry.headerBytes()),new HeaderWrite(snapshotThermal.header().offset(),snapshotThermal.headerBytes()));}
    }
    final class GasAperture {
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
            eos=VulkanGasThermodynamics.layout(gas.state,region.gasConfiguration.tables(),word(volumes),volumes.offset()+volumes.bindingBytes(),a);
            flow=VulkanGasFlow.layout(eos,successor.geometry,gasReserves.pairs(),eos.endOffset(),a,word(workspace.activityCells));
            schurPressure=VulkanGasFlowSchurPressure.layout(flow,flow.endOffset(),a);
            remap=VulkanGasRemap.transportLayout(flow.routes(),eos,successor.retained,successor.future,region.gasConfiguration.ambient(),flow.word(VulkanGasFlow.Field.STEP),
                    gasReserves.parts(),schurPressure.endOffset(),a);
            retainedEOS=VulkanGasThermodynamics.selfPartsLayout(remap,region.gasConfiguration.tables(),flow.word(VulkanGasFlow.Field.RETAINED_VOLUME),remap.endOffset(),a);
            work=VulkanGasAdiabaticWork.layout(retainedEOS,eos.nodeVolumesWordOffset(),flow.word(VulkanGasFlow.Field.MODES),retainedEOS.endOffset(),a);endOffset=work.endOffset();
        }
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(eos.header().offset(),eos.headerBytes()),new HeaderWrite(flow.header.offset(),flow.headerBytes()),new HeaderWrite(schurPressure.header().offset(),schurPressure.headerBytes()),
                new HeaderWrite(remap.header.offset(),remap.headerBytes()),new HeaderWrite(retainedEOS.header().offset(),retainedEOS.headerBytes()),new HeaderWrite(work.header().offset(),work.headerBytes()));}
        List<VulkanLiquidStateLayout.Column> persistentColumns(){
            var columns=new ArrayList<VulkanLiquidStateLayout.Column>();workspace.transport.addGasTables(columns,eos);workspace.transport.addGasTables(columns,retainedEOS);
            columns.add(flow.header);columns.add(schurPressure.header());columns.add(remap.header);columns.add(work.header());
            for(var field:VulkanGasRemap.Field.values())if((field.name().startsWith("AMBIENT_")&&field!=VulkanGasRemap.Field.AMBIENT_BEFORE&&field!=VulkanGasRemap.Field.AMBIENT_AFTER||field==VulkanGasRemap.Field.DECLARED_SPECIES_OFFSETS||field==VulkanGasRemap.Field.DECLARED_SPECIES_HANDLES))columns.add(remap.column(field));return columns;
        }
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,GasAperture old,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            var from=old.persistentColumns();var to=persistentColumns();var headerOffsets=headers().stream().map(HeaderWrite::offset).toList();
            for(int i=0;i<from.size();i++)if(!headerOffsets.contains(to.get(i).offset()))copyColumn(command,input,from.get(i),output,to.get(i),stack);
        }
        int execute(VulkanRegionStorage.Candidate candidate,double duration,long step,VulkanRegionStorage.ComputeCommands prefix){
            var q=VulkanGasFlow.parameters(duration,.6,region.gasConfiguration.ambient(),step);int n=flow.nodes;
            workspace.capacityStage="gas aperture assemble";
            var batch=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                prefix.record(command,input,output,errors,staging,stack);
                var dst=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                region.kernels.flowKernel.rebindRanges(dst,err);region.kernels.flowEOS.rebindRanges(dst,err);region.kernels.flowRemap.rebindRanges(dst,err);region.kernels.flowRetainedEOS.rebindRanges(dst,err);region.kernels.flowWork.rebindRanges(dst,err);
                region.kernels.flowSchurPressure.rebindRanges(dst,err,schurPressure);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                for(var field:new VulkanGasRemap.Field[]{VulkanGasRemap.Field.ENERGY_ROUNDOFF,VulkanGasRemap.Field.SOURCE_ROUNDOFF,VulkanGasRemap.Field.RETAINED_ROUNDOFF,VulkanGasRemap.Field.MERGE_ROUNDOFF,VulkanGasRemap.Field.AUDIT_ENERGY}){
                    var c=remap.column(field);vkCmdFillBuffer(command,output.handle(),c.offset(),c.bindingBytes(),0);
                }
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                region.kernels.timed(command,"aperture-eos",()->{region.kernels.flowEOS.recordVolumes(command,eos,successor.geometry);region.kernels.flowEOS.record(command,eos);});
                region.kernels.timed(command,"aperture-assemble",()->region.kernels.flowKernel.recordAssemble(command,flow,q));
            },flow.requiredPairsOffset(),4);
            if(batch.error()!=0)return batch.error();if(batch.feedback().getInt(0)==0)return 0;
            workspace.capacityStage="gas aperture multi-layer Schur";
            return region.kernels.flowSchurPressure.execute(candidate,schurPressure,q,region.kernels::timed,(command,input,output,errors,staging,stack)->{
                region.kernels.flowKernel.recordRoutes(command,flow,q);
                region.kernels.timed(command,"aperture-integer-transport",()->region.kernels.flowRemap.recordPrepare(command,remap));
                region.kernels.flowRemap.recordTransportVolumes(command,remap,flow);region.kernels.flowRetainedEOS.record(command,retainedEOS);region.kernels.flowWork.record(command,work);
                region.kernels.timed(command,"aperture-energy-gather",()->region.kernels.flowRemap.recordFinish(command,remap,work,region.kernels.timings));
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
                gas.state.copyColumns(command,output,successor.future,output,stack);
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
                if(region.gasConfiguration.tables().isothermal()){region.kernels.flowEOS.recordVolumes(command,eos,successor.geometry);region.kernels.flowEOS.record(command,eos);}
            });
        }
    }
    final class GasSuccessor {
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
            this.old=old;var config=region.gasConfiguration;long a=liquid.alignment();
            totals=VulkanLiquidCellVolumes.layout(liquid,Math.addExact(liquid.limbCapacity(),liquid.cellCount()),accepted.sourceCellVolumes().quantumExponent(),base,a);
            geometry=VulkanGasGeometry.layout(totals,word(workspace.geometry.get(GeometryField.FIXED_MASKS)),occupiedWord,
                    word(workspace.geometry.get(GeometryField.CELLS)),word(workspace.geometry.get(GeometryField.PLANES)),word(workspace.geometry.get(GeometryField.LIQUID_MICRO)),config.sealedExterior(),config.cellSize(),totals.endOffset(),a).withLiquidFaces(liquidFacesWordOffset);
            retained=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gasReserves.nodes(),config.tables().definitions(),geometry.endOffset(),a);
            future=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gasReserves.nodes(),config.tables().definitions(),retained.endOffset(),a);
            lineage=VulkanGasLineage.layout(old.state,old.geometry,geometry,accepted,word(workspace.geometry.get(GeometryField.FLUXES)),word(old.volumes),gasReserves.groups(),future.endOffset(),a);
            remap=VulkanGasRemap.layout(lineage,old.thermo,retained,future,config.ambient(),gasReserves.parts(),lineage.endOffset(),a);
            retainedEOS=VulkanGasThermodynamics.layout(retained,config.tables(),lineage.word(VulkanGasLineage.Field.RETAINED_OLD_VOLUMES),remap.endOffset(),a);
            work=VulkanGasAdiabaticWork.layout(retainedEOS,lineage.word(VulkanGasLineage.Field.FUTURE_CONTROL_VOLUMES),old.operator.word(VulkanGasPressure.Field.MECHANICAL_MODE),retainedEOS.endOffset(),a);endOffset=work.endOffset();
        }
        List<HeaderWrite> headers(){return List.of(new HeaderWrite(totals.header().offset(),totals.headerBytes()),new HeaderWrite(geometry.header().offset(),geometry.headerBytes()),
                new HeaderWrite(4L*lineage.headerWordOffset(),lineage.headerBytes()),new HeaderWrite(4L*remap.headerWordOffset(),remap.headerBytes()),
                new HeaderWrite(retainedEOS.header().offset(),retainedEOS.headerBytes()),new HeaderWrite(work.header().offset(),work.headerBytes()));}
        void bind(GpuBuffer output,GpuBuffer errors){var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
            region.kernels.futureVolumeKernel.rebindRanges(arena,arena,err);region.kernels.futureGasGeometry.rebindRanges(arena,err);region.kernels.lineageKernel.rebindRanges(arena,err);
            region.kernels.remapKernel.rebindRanges(arena,err);region.kernels.retainedEOS.rebindRanges(arena,err);region.kernels.gasWork.rebindRanges(arena,err);}
        void recordGeometry(org.lwjgl.vulkan.VkCommandBuffer command){region.kernels.timed(command,"future-exact-volumes",()->region.kernels.futureVolumeKernel.record(command,totals));region.kernels.timed(command,"future-gas-geometry",()->region.kernels.futureGasGeometry.recordGeometry(command,geometry));region.kernels.timed(command,"future-gas-connectivity",()->region.kernels.futureGasGeometry.recordConnectivity(command,geometry));}
        void recordRemapPrepare(org.lwjgl.vulkan.VkCommandBuffer command){region.kernels.timed(command,"future-gas-components",()->region.kernels.futureGasGeometry.recordComponents(command,geometry));region.kernels.timed(command,"gas-lineage",()->region.kernels.lineageKernel.record(command,lineage));
            region.kernels.timed(command,"gas-remap-prepare",()->region.kernels.remapKernel.recordPrepare(command,remap));region.kernels.timed(command,"retained-gas-eos",()->region.kernels.retainedEOS.record(command,retainedEOS));region.kernels.timed(command,"gas-work",()->region.kernels.gasWork.record(command,work));}
        void recordRemapFinish(org.lwjgl.vulkan.VkCommandBuffer command){region.kernels.timed(command,"gas-remap-finish",()->region.kernels.remapKernel.recordFinish(command,remap,work,region.kernels.timings));}
        void migrate(org.lwjgl.vulkan.VkCommandBuffer command,GasSuccessor previous,GpuBuffer input,GpuBuffer output,org.lwjgl.system.MemoryStack stack){
            var from=new VulkanLiquidStateLayout.Column[]{previous.retainedEOS.segmentOffsets(),previous.retainedEOS.segmentUpper(),previous.retainedEOS.segmentHeatCapacity(),previous.retainedEOS.specificAnchorEnergy(),previous.retainedEOS.speciesReference()};
            var to=new VulkanLiquidStateLayout.Column[]{retainedEOS.segmentOffsets(),retainedEOS.segmentUpper(),retainedEOS.segmentHeatCapacity(),retainedEOS.specificAnchorEnergy(),retainedEOS.speciesReference()};
            for(int i=0;i<from.length;i++)copyColumn(command,input,from[i],output,to[i],stack);
            for(var field:VulkanGasRemap.Field.values())if((field.name().startsWith("AMBIENT_")&&field!=VulkanGasRemap.Field.AMBIENT_BEFORE&&field!=VulkanGasRemap.Field.AMBIENT_AFTER||field==VulkanGasRemap.Field.DECLARED_SPECIES_OFFSETS||field==VulkanGasRemap.Field.DECLARED_SPECIES_HANDLES))
                copyColumn(command,input,previous.remap.column(field),output,remap.column(field),stack);
        }
    }
    final class GasSection {
        final VulkanGasGeometry.Layout geometry;
        final VulkanGasStateLayout state;
        final VulkanLiquidStateLayout.Column volumes;
        final VulkanGasThermodynamics.Layout thermo;
        final VulkanGasPressure.Layout operator;
        final long endOffset;
        GasSection(VulkanLiquidCellVolumes.Layout totals,long base,int occupiedWord) {
            var config=region.gasConfiguration;long alignment=liquid.alignment();
            geometry=VulkanGasGeometry.layout(totals,word(workspace.geometry.get(GeometryField.FIXED_MASKS)),occupiedWord,
                    word(workspace.geometry.get(GeometryField.CELLS)),word(workspace.geometry.get(GeometryField.PLANES)),word(workspace.geometry.get(GeometryField.LIQUID_MICRO)),
                    config.sealedExterior(),config.cellSize(),base,alignment);
            state=new VulkanGasStateLayout(liquid.sizeX(),liquid.sizeY(),liquid.sizeZ(),gasReserves.nodes(),config.tables().definitions(),geometry.endOffset(),alignment);
            volumes=new VulkanLiquidStateLayout.Column(align(state.endOffset()),gasReserves.nodes(),8);
            thermo=VulkanGasThermodynamics.layout(state,config.tables(),word(volumes),volumes.offset()+volumes.bindingBytes(),alignment);
            operator=new VulkanGasPressure.Layout(thermo,geometry,workspace.geometry.get(GeometryField.CELLS),workspace.geometry.get(GeometryField.FACES),
                    new VulkanGasPressure.PressureBuffers(pressureColumns.get(PressureField.STARTS),pressureColumns.get(PressureField.COLUMNS),pressureColumns.get(PressureField.VALUES),
                            pressureColumns.get(PressureField.ROWS),pressureColumns.get(PressureField.PRESSURE),pressureColumns.get(PressureField.WORK),pressureColumns.get(PressureField.META),
                            pressureColumns.get(PressureField.PROBLEM),pressureColumns.get(PressureField.CONTROL)),granularReserves==null?0:3*granularReserves.particles(),thermo.endOffset(),alignment,
                    word(workspace.activityCells),word(workspace.activityFaces));endOffset=operator.endOffset();
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
}
