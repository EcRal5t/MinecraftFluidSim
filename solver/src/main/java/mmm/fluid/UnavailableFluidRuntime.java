package mmm.fluid;

import java.util.List;
import mmm.thermal.FiniteThermalDeviceInventory;

/** Keeps world loading and checkpoint inspection available when Vulkan cannot start. */
public final class UnavailableFluidRuntime implements FluidSolverBackend {
    private final String reason;
    public UnavailableFluidRuntime(String reason) { this.reason = java.util.Objects.requireNonNull(reason); }
    @Override public String id() { return "mmm:vulkan-unavailable"; }
    @Override public String unavailableReason() { return reason; }
    @Override public String executionDescription() { return "Vulkan unavailable: " + reason; }
    private IllegalStateException unavailable() { return new IllegalStateException(reason); }
    @Override public PreparedFluidState prepareState(FluidStateAccess source, FluidDomainState successor,
            FiniteThermalDeviceInventory devices, List<FluidStateAccess.DevicePort> ports) { throw unavailable(); }
    @Override public PreparedFluidState prepareThermalBoundary(ResidentFluidState source, double[] temperatures) { throw unavailable(); }
    @Override public FluidStepCandidate evaluate(FluidStepRequest request) { throw unavailable(); }
    @Override public FluidThermalStepCandidate evaluateThermal(FluidThermalStepRequest request) { throw unavailable(); }
}
