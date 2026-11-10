package mmm.simulation.ownership;

/** Constructs a durable ownership record for cross-package recovery fixtures. */
public final class OwnershipTestFixtures {
    private OwnershipTestFixtures() { }
    public static void publish(OwnershipFiles files,OwnershipCommitted committed) throws java.io.IOException {
        files.publishCommitted(committed);
    }
}
