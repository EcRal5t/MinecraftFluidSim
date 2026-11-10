package mmm.api.sensor;

import java.util.Locale;
import java.util.UUID;

/** Immutable committed process measurement shared with optional integrations. */
public record ProcessSensorSnapshot(Measure measure, Status status, Double value, UUID regionId,
                                    long regionIncarnation, long solverGeneration, double simulationSeconds) {
    public enum Measure {
        LEVEL("液位 · 0—1", "1"), LIQUID_TEMPERATURE("液体温度 · K", "K"), GAS_TEMPERATURE("气体温度 · K", "K"),
        PRESSURE("绝对压力 · Pa", "Pa"), FLOW("液体流量 · L/s", "L/s");
        private final String label, unit;
        Measure(String label,String unit){this.label=label;this.unit=unit;}
        public String label(){return label;}
        public String unit(){return unit;}
    }
    public enum Status {
        WAITING("等待观测"), RUNNING("运行中"), PAUSED("已暂停"), UNAVAILABLE("不可用"), NO_SAMPLE("无可测物质");
        private final String label;
        Status(String label) { this.label=label; }
        public String label() { return label; }
    }
    public ProcessSensorSnapshot {
        java.util.Objects.requireNonNull(measure); java.util.Objects.requireNonNull(status);
        if (value!=null && !Double.isFinite(value)) throw new IllegalArgumentException("Invalid sensor value");
        if ((status==Status.WAITING || status==Status.UNAVAILABLE || status==Status.NO_SAMPLE) && value!=null)
            throw new IllegalArgumentException("Unavailable sensor value");
        if ((status==Status.RUNNING || status==Status.PAUSED) && value==null)
            throw new IllegalArgumentException("Active or paused sensor has no sample");
        if (value==null && regionId!=null) throw new IllegalArgumentException("Missing sample has a region identity");
        if (regionId==null) {
            if(regionIncarnation!=0 || solverGeneration!=0 || simulationSeconds!=0 || value!=null)
                throw new IllegalArgumentException("Sensor observation has no region identity");
        } else if(regionIncarnation<=0 || solverGeneration<0 || !Double.isFinite(simulationSeconds) || simulationSeconds<0)
            throw new IllegalArgumentException("Invalid committed sensor identity");
    }
    public static ProcessSensorSnapshot waiting(Measure measure) { return new ProcessSensorSnapshot(measure,Status.WAITING,null,null,0,0,0); }
    public static ProcessSensorSnapshot unavailable(Measure measure,Status status) {
        return new ProcessSensorSnapshot(measure,status,null,null,0,0,0);
    }
    public String display() {
        return measure.label()+" · "+status.label()+(value==null?"":" · "+String.format(Locale.ROOT,"%.4g",value));
    }
}
