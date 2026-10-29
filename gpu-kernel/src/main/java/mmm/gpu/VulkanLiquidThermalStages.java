package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static mmm.gpu.VulkanLiquidRegion.*;
import static org.lwjgl.vulkan.VK10.*;

/** Thermal candidate and phase topology stages over one resident workspace. */
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
        int error = region.kernels.reactionKernel.execute(candidate, transport.reactions,
                region.activeInputs != null && region.activeInputs.phaseChangeEnabled(),
                region.activeInputs == null ? 0 : region.activeInputs.maximumPhaseOperations(),
                sourceEOS, previousGeometry, duration);
        if (error != 0) return error;

        if (transport.phaseGasTopology == null) {
            return candidate.continueBatch((command, input, output, errors, staging, stack) -> {
                transport.recordSolidGeometry(command, output, errors, stack);
                recordLiquidCells(command, output, errors);
            }, 0, 0).error();
        }

        workspace.capacityStage = "phase gas topology";
        var phase = transport.phaseGasTopology;
        var status = candidate.continueBatch((command, input, output, errors, staging, stack) -> {
            var arena = new FlipKernelBindings.BufferRange(output);
            var errorBuffer = new FlipKernelBindings.BufferRange(errors);
            transport.recordSolidGeometry(command, output, errors, stack);
            recordLiquidCells(command, output, errors);
            region.kernels.gasThermo.rebindRanges(arena, errorBuffer);
            region.kernels.gasThermo.recordVolumes(command, transport.gas.thermo, previousGeometry);
            region.kernels.gasThermo.record(command, transport.gas.thermo);
            region.kernels.phaseGasTopology.rebindRanges(arena, errorBuffer);
            region.kernels.phaseGasTopology.recordGeometry(command, phase);
            region.kernels.phaseGasTopology.recordRemap(command, phase, previousGeometry);
        }, phase.remap().requirements().offset(), 32);
        if (status.error() == 64) {
            var required = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            candidate.readWords(new long[]{phase.requiredNodesOffset(), phase.requiredGroupsOffset(),
                    phase.requiredPartsOffset()}, required);
            throw new PhaseGasCapacityGrowth(required.getInt(0), required.getInt(4), required.getInt(8));
        }
        if (status.error() != 0) return status.error();

        status = candidate.continueBatch((command, input, output, errors, staging, stack) -> {
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, stack);
            transport.gas.state.copyColumns(command, output, phase.future(), output, stack);
            var plan = new VulkanBufferCopyPlan.Builder();
            transport.gas.geometry.copyDerived(plan, phase.geometry());
            plan.build().record(command, output, output, stack);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
            var arena = new FlipKernelBindings.BufferRange(output);
            var errorBuffer = new FlipKernelBindings.BufferRange(errors);
            region.kernels.gasThermo.rebindRanges(arena, errorBuffer);
            region.kernels.gasThermo.recordVolumes(command, transport.gas.thermo, transport.gas.geometry);
            region.kernels.gasThermo.record(command, transport.gas.thermo);
        }, 0, 0);
        if (status.error() == 0) workspace.phaseGasCanonical = true;
        return status.error();
    }

    private void recordLiquidCells(org.lwjgl.vulkan.VkCommandBuffer command, GpuBuffer output, GpuBuffer errors) {
        var occupied = transport.entity == null ? workspace.occupied()
                : transport.entity.column(VulkanEntityBodyCoupling.Field.COMBINED_SVQ);
        region.kernel.rebindRanges(workspace.geometryRanges(output, errors, geometry.get(GeometryField.FACES), occupied));
        region.kernel.recordCells(command, liquid.sizeX(), liquid.sizeY(), liquid.sizeZ());
    }

    int executeThermal(VulkanLiquidWorkspace source, boolean sourceCurrentGas, boolean sourceGeometryValid,
                       VulkanRegionStorage.Candidate candidate, double duration) {
        boolean reuseGeometry = sourceGeometryValid && region.currentInputs.bodies().bodies().isEmpty()
                && (region.activeInputs == null || region.activeInputs.bodies().bodies().isEmpty());
        var thermal = transport.gas == null ? transport.thermal
                : VulkanRegionThermal.currentGas(transport.thermal, transport.gas.thermo,
                        transport.gas.geometry, liquid.alignment());
        var headers = new ArrayList<HeaderWrite>(source == workspace ? List.<HeaderWrite>of() : transport.headers(liquid));
        var contactHeader = new HeaderWrite(thermal.contacts().header.offset(), thermal.contacts().headerBytes());
        headers.add(contactHeader);

        var status = candidate.beginBatch(headerBytes(headers), (command, input, output, errors, staging, stack) -> {
            if (source == workspace) {
                workspace.copyPersistent(command, input, output, stack);
                workspace.writeHeaders(command, output, List.of(contactHeader), stack);
            } else {
                workspace.migrate(command, source, input, output, stack, headers);
            }
            workspace.recordActivity(command, output, stack);
            transport.beginDeviceRequest(command, source.transport, input, output, stack, false);
            transport.recordSolidGeometry(command, output, errors, stack);
            if (reuseGeometry) workspace.copySourceGeometry(command, source, sourceCurrentGas, input, output, stack);
            region.kernel.rebindRanges(workspace.geometryRanges(output, errors, geometry.get(GeometryField.FACES)));
            if (reuseGeometry) region.kernel.recordVelocities(command, liquid.sizeX(), liquid.sizeY(), liquid.sizeZ());
            else region.kernel.record(command, liquid.sizeX(), liquid.sizeY(), liquid.sizeZ());
            var dst = new FlipKernelBindings.BufferRange(output);
            var errorBuffer = new FlipKernelBindings.BufferRange(errors);
            region.kernels.ledger.rebindRanges(new FlipKernelBindings.BufferRange(input), dst, errorBuffer);
            if (transport.gas != null && !reuseGeometry) {
                region.kernels.volumeKernel.rebindRanges(new FlipKernelBindings.BufferRange(input), dst, errorBuffer);
                region.kernels.volumeKernel.record(command, transport.volumes);
                region.kernels.gasGeometry.rebindRanges(dst, errorBuffer);
                region.kernels.gasThermo.rebindRanges(dst, errorBuffer);
                region.kernels.gasGeometry.recordGeometry(command, transport.gas.geometry);
                region.kernels.gasGeometry.recordSourceComponents(command, transport.gas.geometry, transport.gas.state);
            }
        }, 0, 0);
        if (status.error() != 0) return status.error();

        int error = region.kernels.thermalKernel.execute(candidate, thermal, duration,
                source.transport.devices == null ? null : source.transport.devices.state,
                transport.devices == null ? null : transport.devices.settlement, false);
        if (error != 0) return error;
        if (transport.reactions != null && (region.activeInputs == null || !region.activeInputs.pendingPhaseSettlementOnly())) {
            error = executeReactions(candidate, transport.gas == null ? null : transport.gas.thermo,
                    transport.gas == null ? null : transport.gas.geometry, duration);
            if (error != 0) return error;
        }

        var stableHeader = new HeaderWrite(transport.thermal.contacts().header.offset(), transport.thermal.contacts().headerBytes());
        return candidate.finishBatch(stableHeader.bytes().remaining(), (command, input, output, errors, staging, stack) -> {
            region.kernels.ledger.record(command, thermal.reservoir());
            region.kernels.ledger.record(command, transport.thermalOnlyRoundoff);
            region.kernels.ledger.record(command, transport.thermalOnlyMechanical);
            region.kernels.ledger.record(command, transport.inventoryPosting);
            region.kernels.ledger.record(command, transport.sourcePosting);
            region.kernels.ledger.record(command, transport.sinkPosting);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, stack);
            copyColumn(command, output, transport.thermalOnlyRoundoff.result(), output, transport.roundoff.result(), stack);
            copyColumn(command, output, transport.thermalOnlyMechanical.result(), output, transport.mechanicalPosting.result(), stack);
            vkCmdFillBuffer(command, output.handle(), transport.heat.result().offset(), transport.heat.result().bytes(), 0);
            workspace.writeHeaders(command, output, List.of(stableHeader), stack);
        });
    }
}
