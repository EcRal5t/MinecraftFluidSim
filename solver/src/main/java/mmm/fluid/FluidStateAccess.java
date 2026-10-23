package mmm.fluid;

/**
 * A complete immutable solver generation. Device captures retain their generation's
 * buffers until close; layout queries perform no device readback.
 * Export is reserved for checkpoints, explicit inspection and CPU recovery.
 */
public interface FluidStateAccess extends AutoCloseable {
    FluidDomainState.Layout layout();
    FluidDomainState exportState();
    @Override default void close() { }
}
