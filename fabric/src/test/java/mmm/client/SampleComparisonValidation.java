package mmm.client;

import java.util.List;
import mmm.network.SampleAnalysisPayload;

public final class SampleComparisonValidation {
    private SampleComparisonValidation() { }
    public static void main(String[] args) {
        var state = new SampleComparison();
        var other = new SampleComparison();
        var sample = new SampleAnalysisPayload(SampleAnalysisPayload.Phase.GAS, List.of("sample"), List.of(new SampleAnalysisPayload.Fraction(0, 0.25)));
        state.observeDimension("minecraft:overworld");
        state.save(sample);
        other.observeDimension("minecraft:overworld");
        if (other.baseline() != null) throw new AssertionError("baseline crossed clients");
        state.observeDimension("minecraft:overworld");
        if (state.baseline() != sample || state.baseline().phase() != SampleAnalysisPayload.Phase.GAS
                || state.baseline().fractions().getFirst().value() != 0.25) throw new AssertionError("baseline metadata lost in same dimension");
        state.observeDimension("minecraft:the_nether");
        if (state.baseline() != null) throw new AssertionError("baseline survived dimension change");
        state.save(sample);
        state.observeDimension(null);
        if (state.baseline() != null) throw new AssertionError("baseline survived disconnect");
    }
}
