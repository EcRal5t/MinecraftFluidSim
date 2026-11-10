package mmm.client;

import java.util.List;
import mmm.network.SampleAnalysisPayload;

public final class SampleComparisonValidation {
    private SampleComparisonValidation() { }
    public static void main(String[] args) {
        var state = new SampleComparison();
        var sample = new SampleAnalysisPayload(List.of("sample"));
        state.observeDimension("minecraft:overworld");
        state.save(sample);
        state.observeDimension("minecraft:overworld");
        if (state.baseline() != sample) throw new AssertionError("baseline lost in same dimension");
        state.observeDimension("minecraft:the_nether");
        if (state.baseline() != null) throw new AssertionError("baseline survived dimension change");
        state.save(sample);
        state.observeDimension(null);
        if (state.baseline() != null) throw new AssertionError("baseline survived disconnect");
    }
}
