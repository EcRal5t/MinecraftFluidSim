package mmm.network;

/** Validates an explicitly chosen simulation-time interval. */
public final class ProcessTrendRange {
    private ProcessTrendRange() { }
    public static boolean valid(double start, double end) {
        return Double.isFinite(start) && Double.isFinite(end) && start >= 0 && end > start && Double.isFinite(end-start);
    }
}
