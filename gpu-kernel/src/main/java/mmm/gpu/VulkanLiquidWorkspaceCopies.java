package mmm.gpu;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import static mmm.gpu.VulkanLiquidRegion.GeometryField;
import static mmm.gpu.VulkanLiquidRegion.PressureField;

/** Cached copies of persistent inventory and reusable geometry between candidate buffers. */
final class VulkanLiquidWorkspaceCopies implements AutoCloseable {
    private final VulkanLiquidWorkspace workspace;
    private VulkanBufferCopyPlan persistent, geometry;

    VulkanLiquidWorkspaceCopies(VulkanLiquidWorkspace workspace) { this.workspace=workspace; }

    void recordPersistent(VkCommandBuffer command,GpuBuffer input,GpuBuffer output,MemoryStack stack) {
        if(persistent==null) {
            var plan=new VulkanBufferCopyPlan.Builder();
            for(var field:VulkanLiquidStateLayout.Field.values()){var c=workspace.liquid.column(field);plan.copy(c,c);}
            plan.copy(workspace.hierarchyTopology(),workspace.hierarchyTopology());
            for(var field:new GeometryField[]{GeometryField.FIXED_MASKS,GeometryField.OCCUPIED_SOLID,GeometryField.SCREEN_FACES}){var c=workspace.geometry.get(field);plan.copy(c,c);}
            var warm=workspace.pressureColumns.get(PressureField.PRESSURE);plan.copy(warm,warm);
            var transport=workspace.transport;
            if(transport==null){var c=workspace.mixture.header();plan.copy(c,c);}
            else {
                for(var c:transport.persistentColumns())plan.copy(c,c);
                var reference=transport.pressure.coupled.column(VulkanCoupledPressure.Field.REFERENCE);plan.copy(reference,reference);
                if(transport.gas!=null)for(var f:VulkanGasStateLayout.Field.values()){var c=transport.gas.state.column(f);plan.copy(c,c);}
                if(transport.granular!=null)for(var f:VulkanGranularStateLayout.Field.values()){var c=transport.granular.state.column(f);plan.copy(c,c);}
                if(transport.coherent!=null)for(var f:VulkanCoherentStateLayout.Field.values()){var c=transport.coherent.state.column(f);plan.copy(c,c);}
                if(transport.devices!=null)for(var f:VulkanThermalDeviceLayout.Field.values()){var c=transport.devices.state.column(f);plan.copy(c,c);}
            }
            persistent=plan.build(workspace.region.context);
        }
        persistent.record(command,input,output,stack);
    }

    void recordReusableGeometry(VkCommandBuffer command,GpuBuffer input,GpuBuffer output,MemoryStack stack) {
        if(geometry==null) {
            var plan=new VulkanBufferCopyPlan.Builder();
            for(var field:new GeometryField[]{GeometryField.CELLS,GeometryField.PLANES,GeometryField.LIQUID_MICRO,GeometryField.WALL_GEOMETRY,GeometryField.FACES}) {
                var c=workspace.geometry.get(field);plan.copy(c,c);
            }
            plan.copy(workspace.mixture.states(),workspace.mixture.states());
            var gas=workspace.transport.gas;
            if(gas!=null) {
                plan.copy(gas.thermo.states(),gas.thermo.states());
                plan.copy(gas.thermo.caloricCache(),gas.thermo.caloricCache());
                for(var c:new VulkanLiquidStateLayout.Column[]{gas.geometry.dryVolumes(),gas.geometry.dryMasks(),gas.geometry.cellNodes()})plan.copy(c,c);
            }
            geometry=plan.build(workspace.region.context);
        }
        geometry.record(command,input,output,stack);
    }

    @Override public void close() {
        if(persistent!=null)persistent.close();
        if(geometry!=null)geometry.close();
    }
}
