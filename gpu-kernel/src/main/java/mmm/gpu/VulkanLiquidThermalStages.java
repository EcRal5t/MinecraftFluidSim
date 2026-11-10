package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static mmm.gpu.VulkanLiquidRegion.*;
import static org.lwjgl.vulkan.VK10.*;

/** Thermal and phase stages over one resident local mixture. */
final class VulkanLiquidThermalStages {
    private final VulkanLiquidWorkspace workspace;
    private final VulkanLiquidRegion region;
    private final VulkanLiquidStateLayout liquid;
    private final EnumMap<GeometryField, VulkanLiquidStateLayout.Column> geometry;
    private final VulkanLiquidWorkspace.Transport transport;

    VulkanLiquidThermalStages(VulkanLiquidWorkspace workspace) {
        this.workspace = workspace;
        this.region = workspace.region;
        this.liquid = workspace.liquid;
        this.geometry = workspace.geometry;
        this.transport = workspace.transport;
    }

    void recordImportedThermal(org.lwjgl.vulkan.VkCommandBuffer command, GpuBuffer output, GpuBuffer errors) {
        if (transport == null || transport.thermal == null) return;
        var arena = new FlipKernelBindings.BufferRange(output);
        var errorBuffer = new FlipKernelBindings.BufferRange(errors);
        region.kernels.liquidThermalKernel.rebindRanges(arena, errorBuffer);
        region.kernels.liquidThermalKernel.record(command, transport.liquidThermal);
        if (transport.granular != null) region.kernels.liquidThermalKernel.record(command, transport.granular.thermal);
        if (transport.coherent != null) region.kernels.liquidThermalKernel.record(command, transport.coherent.thermal);
        if (transport.devices != null) region.kernels.liquidThermalKernel.record(command, transport.devices.thermal);
    }

    int executeReactions(VulkanRegionStorage.Candidate candidate, VulkanGasThermodynamics.Layout sourceEOS,
                         VulkanGasGeometry.Layout previousGeometry, double duration) {
        var activity = candidate.continueBatch((command,input,output,errors,staging,stack)-> {
            recordThermodynamicState(command,output,errors,duration);
            region.kernels.reactionKernel.recordLiquidActivity(command,transport.reactions,output,errors);
        },transport.reactions.progress().column(VulkanReactionProgressLayout.Field.CONTROL).offset()+28,4);
        int error=activity.error();
        if (error != 0) return error;
        error = region.kernels.reactionKernel.execute(candidate, transport.reactions,
                region.activeInputs != null && region.activeInputs.phaseChangeEnabled(),
                region.activeInputs == null ? 0 : region.activeInputs.maximumPhaseOperations(),
                sourceEOS, previousGeometry, duration,activity.feedback().getInt(0));
        return error;
    }

    void recordReactionCompletion(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,
                                  org.lwjgl.system.MemoryStack stack){
        region.kernels.reactionKernel.recordFinalCaloric(command,transport.reactions,output,errors);
        if(transport.devices!=null && transport.reactions.massTransfer()!=null
                && (transport.reactions.massTransfer().dryProposals>0 || transport.reactions.massTransfer().surfaceRules>0))
            region.kernels.thermalKernel.recordCondensationSettlement(command,output,errors,
                    transport.devices.state,transport.devices.settlement,transport.devices.thermal);
        transport.recordSolidGeometry(command,output,errors,stack);
    }

    /** Thermal and phase exchange use the contact geometry of the completed mechanical transport. */
    void recordThermodynamicState(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,double duration){
        var occupied=transport.entity==null?workspace.occupied():transport.entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ);
        region.kernel.rebindRanges(workspace.geometryRanges(output,errors,geometry.get(GeometryField.FACES),occupied));
        region.kernel.recordTotals(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
        if(transport.gas!=null)transport.gas.recordThermodynamics(command,output,errors,duration,true);
        else {
            region.mixtureKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
            region.mixtureKernel.record(command,workspace.mixture,region.requestCellSize);
        }
    }

    void recordLocalState(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,double duration,boolean energyOnly){
        var occupied=transport.entity==null?workspace.occupied():transport.entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ);
        region.kernel.rebindRanges(workspace.geometryRanges(output,errors,geometry.get(GeometryField.FACES),occupied));
        region.kernel.recordTotals(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
        if(transport.gas!=null)transport.gas.recordSource(command,output,errors,duration,region.kernel,energyOnly);
        else {
            region.mixtureKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
            region.mixtureKernel.record(command,workspace.mixture,region.requestCellSize);
            region.kernel.recordShapes(command,liquid.sizeX(),liquid.sizeY(),liquid.sizeZ());
        }
    }

    int executeThermal(VulkanLiquidWorkspace source, boolean sourceGeometryValid,
                       VulkanRegionStorage.Candidate candidate, double duration) {
        var thermal = transport.gas == null ? transport.thermal
                : VulkanRegionThermal.currentGas(transport.thermal, transport.gas.thermo,
                        transport.gas.geometry, liquid.alignment());
        boolean reuseGeometry=source==workspace&&sourceGeometryValid&&transport.entity==null;
        var headers = new ArrayList<HeaderWrite>(source == workspace ? List.<HeaderWrite>of() : transport.headers(liquid));
        var contactHeader = new HeaderWrite(thermal.contacts().header.offset(), thermal.contacts().headerBytes());
        headers.add(contactHeader);

        var status = candidate.beginBatch(headerBytes(headers), (command, input, output, errors, staging, stack) -> {
            if (source == workspace) {
                workspace.copies.recordPersistent(command, input, output, stack);
                if(reuseGeometry)workspace.copies.recordReusableGeometry(command,input,output,stack);
                workspace.writeHeaders(command, output, List.of(contactHeader), stack);
            } else {
                workspace.migrate(command, source, input, output, stack, headers);
            }
            if(region.activeEnvironment!=null)
                region.activeEnvironment.recordImport(command,output,transport.devices==null?null:transport.devices.state,errors,stack);
            workspace.recordActivity(command, output, stack);
            transport.beginDeviceRequest(command, source.transport, input, output, stack, false);
            transport.recordSolidGeometry(command, output, errors, stack);
            if(reuseGeometry){
                region.kernel.rebindRanges(workspace.geometryRanges(output,errors,geometry.get(GeometryField.FACES)));
                workspace.recordMixtureGeometry(command,output,errors,duration,true);
            } else recordLocalState(command,output,errors,duration,false);
            region.kernels.ledger.rebindRanges(new FlipKernelBindings.BufferRange(input),new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
        }, 0, 0);
        if (status.error() != 0) return status.error();

        int error = region.kernels.thermalKernel.execute(candidate, thermal, duration,
                source.transport.devices == null ? null : source.transport.devices.state,
                transport.devices == null ? null : transport.devices.settlement, false);
        if (error != 0) return error;
        boolean react=transport.reactions != null && (region.activeInputs == null || !region.activeInputs.pendingPhaseSettlementOnly());
        if (react) {
            error = executeReactions(candidate, transport.gas == null ? null : transport.gas.thermo,
                    transport.gas == null ? null : transport.gas.geometry, duration);
            if (error != 0) return error;
        }

        var stableHeader = new HeaderWrite(transport.thermal.contacts().header.offset(), transport.thermal.contacts().headerBytes());
        return candidate.finishBatch(stableHeader.bytes().remaining(), (command, input, output, errors, staging, stack) -> {
            if(transport.gas!=null){
                for(var field:new VulkanLocalGasTransport.Field[]{VulkanLocalGasTransport.Field.ENV_OLD_ENERGY,VulkanLocalGasTransport.Field.ENV_NEW_ENERGY,
                        VulkanLocalGasTransport.Field.ENV_OLD_SINK,VulkanLocalGasTransport.Field.ENV_NEW_SINK}){
                    var column=transport.gas.transport.column(field);org.lwjgl.vulkan.VK10.vkCmdFillBuffer(command,output.handle(),column.offset(),column.bytes(),0);
                }
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
            }
            if(react)recordReactionCompletion(command,output,errors,stack);
            recordLocalState(command,output,errors,duration,!react);
            if(region.activeEnvironment!=null)
                region.activeEnvironment.recordExport(command,output,transport.devices==null?null:transport.devices.state,errors,stack);
            if(!sourceGeometryValid){
                region.kernels.mixturePressureKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                region.kernels.mixturePressureKernel.recordInitializeWarm(command,transport.pressure.assembly);
            }
            region.kernels.ledger.record(command, thermal.reservoir());
            region.kernels.ledger.record(command, transport.thermalOnlyRoundoff);
            region.kernels.ledger.record(command, transport.thermalOnlyMechanical);
            region.kernels.ledger.record(command, transport.inventoryPosting);
            region.kernels.ledger.record(command, transport.sourcePosting);
            region.kernels.ledger.record(command, transport.sinkPosting);
            if(region.activeEnvironment!=null&&region.activeEnvironment.gas!=null)region.kernels.ledger.record(command, transport.environmentPosting);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, stack);
            copyColumn(command, output, transport.thermalOnlyRoundoff.result(), output, transport.roundoff.result(), stack);
            copyColumn(command, output, transport.thermalOnlyMechanical.result(), output, transport.mechanicalPosting.result(), stack);
            vkCmdFillBuffer(command, output.handle(), transport.heat.result().offset(), transport.heat.result().bytes(), 0);
            workspace.writeHeaders(command, output, List.of(stableHeader), stack);
        });
    }
}
