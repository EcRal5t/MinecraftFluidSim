package mmm.pipe;

/** One bidirectional pipe opening; rise is the second segment elevation minus the first.
 * A sieve opening of zero denotes a connection without a sieve. */
public record PipeConnection(int first, int second, double lengthM, double areaM2, double riseM,
                             double opening, double sieveOpeningM) {
    public PipeConnection {
        if (first < 0 || second < 0 || first == second || !(Double.isFinite(lengthM) && lengthM > 0)
                || !(Double.isFinite(areaM2) && areaM2 > 0) || !Double.isFinite(riseM)
                || !(Double.isFinite(opening) && opening >= 0 && opening <= 1)
                || !(Double.isFinite(sieveOpeningM) && sieveOpeningM >= 0))
            throw new IllegalArgumentException("Invalid pipe connection");
    }
}
