package mmm.fluid;

import mmm.thermal.EnvironmentThermalState;

/** A private world-thermal successor associated with one committed dimension generation. */
public interface PreparedEnvironmentState extends AutoCloseable {
    String dimensionId();
    long baseGeneration();
    EnvironmentThermalState capture();
    boolean isCurrent();
    void commit();
    @Override void close();
}
