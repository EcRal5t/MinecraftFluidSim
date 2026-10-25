package mmm.fluid;

/** A private device successor. Publication transfers one retained generation to the region owner. */
public interface PreparedFluidState extends AutoCloseable {
    FluidDomainState.Layout layout();
    FluidDomainState exportState();
    FluidStateAccess commit();
    @Override void close();
}
