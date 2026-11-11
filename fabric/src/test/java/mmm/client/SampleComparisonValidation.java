package mmm.client;

import java.util.List;
import mmm.network.SampleAnalysisPayload;

public final class SampleComparisonValidation {
    private SampleComparisonValidation() { }
    public static void main(String[] args) {
        var state = new SampleComparison();
        var other = new SampleComparison();
        var sample = new SampleAnalysisPayload(SampleAnalysisPayload.Phase.GAS, 1, 2, 293.15, 15, false, List.of(),
                List.of(new SampleAnalysisPayload.Species("mmm:h2o", 1_000_000, .25, Double.NaN)));
        state.observeDimension("minecraft:overworld");
        state.save(sample);
        other.observeDimension("minecraft:overworld");
        if (other.baseline() != null) throw new AssertionError("baseline crossed clients");
        state.observeDimension("minecraft:overworld");
        if (state.baseline() != sample || state.baseline().phase() != SampleAnalysisPayload.Phase.GAS
                || state.baseline().species().getFirst().massFraction() != 0.25) throw new AssertionError("baseline metadata lost in same dimension");
        state.observeDimension("minecraft:the_nether");
        if (state.baseline() != null) throw new AssertionError("baseline survived dimension change");
        state.save(sample);
        state.observeDimension(null);
        if (state.baseline() != null) throw new AssertionError("baseline survived disconnect");
    }
}
