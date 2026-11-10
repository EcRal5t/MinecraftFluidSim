package mmm.fluid;

/** A private device successor. Publication transfers one retained generation to the region owner. */
public interface PreparedFluidState extends AutoCloseable {
    FluidDomainState.Layout layout();
    FluidDomainState exportState();
    default void attachEnvironment(PreparedEnvironmentState environment) {
        throw new UnsupportedOperationException("Environment thermal candidates are unavailable");
    }
    FluidStateAccess commit();
    @Override void close();
}
