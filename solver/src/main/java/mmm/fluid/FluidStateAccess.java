package mmm.fluid;

/**
 * A complete immutable solver generation. Device captures retain their generation's
 * buffers until close; layout queries perform no device readback.
 * Export is reserved for checkpoints, explicit inspection and CPU recovery.
 */
public interface FluidStateAccess extends AutoCloseable {
    FluidDomainState.Layout layout();
    FluidDomainState exportState();
    record DevicePort(String providerId, String objectId, FluidDomainContracts.ThermalPortRequest contact) { }
    default mmm.thermal.FiniteThermalDeviceInventory finiteThermalDevices() { return null; }
    default java.util.List<DevicePort> finiteThermalPorts() { return java.util.List.of(); }
    default EnvironmentBinding environmentBinding() { return null; }
    /** Retains this immutable generation independently of the caller's lifetime. */
    default FluidStateAccess retain() { return this; }
    @Override default void close() { }
}
