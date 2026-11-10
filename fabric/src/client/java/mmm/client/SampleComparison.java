package mmm.client;

import mmm.network.SampleAnalysisPayload;

/** Keeps a comparison snapshot only while the player remains in the same dimension. */
final class SampleComparison {
    private String dimension;
    private SampleAnalysisPayload baseline;
    void observeDimension(String current) {
        if (current == null || !current.equals(dimension)) baseline = null;
        dimension = current;
    }
    void save(SampleAnalysisPayload sample) { baseline = sample; }
    SampleAnalysisPayload baseline() { return baseline; }
    void clear() { baseline = null; }
}
