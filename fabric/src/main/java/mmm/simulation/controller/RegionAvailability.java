package mmm.simulation.controller;

/** World chunk availability for a selected volume. */
public final class RegionAvailability {
    private RegionAvailability() { }
    public enum Status { READY, INCOMPLETE_CHUNKS }
    public record Result(Status status, String failure) {
        public boolean ready() { return status == Status.READY; }
    }
}
