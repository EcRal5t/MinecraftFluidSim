package mmm.network;

public final class ProcessTrendHistoryValidation {
    public static void main(String[] args) {
        var history=new ProcessTrendHistory(); var first=java.util.UUID.randomUUID(); long incarnation=1; history.region(first,incarnation);
        history.sample(0,1); history.sample(.05,2); history.sample(.05,3);
        check(history.points().size()==2 && history.points().getLast().value()==3, "same-time replacement");
        check(history.points().getLast().connected(), "continuous time");
        history.region(first,incarnation);
        check(history.points().size()==2, "stable region identity");
        history.breakSeries("已暂停"); history.sample(.05,4);
        check(!history.points().getLast().connected(), "pause break");
        history.breakSeries(); history.sample(.1,5);
        check(!history.points().getLast().connected(), "unavailable break");
        for(int i=1;i<=300;i++) history.sample(i, i);
        check(history.points().size()==ProcessTrendHistory.LIMIT, "bounded history");
        history.region(first,2); check(history.points().isEmpty(), "region incarnation");
        history.samplePaused(0,1); history.samplePaused(0,2);
        check(history.points().size()==1 && history.status().equals("已暂停"), "paused observation does not repeat");
        history.samplePaused(.05,3);
        check(history.points().size()==2 && !history.points().getLast().connected(), "manual paused step");
        history.sample(.1,4);
        check(!history.points().getLast().connected(), "resume after pause");
        history.sample(2,1); history.sample(1,2);
        check(history.points().size()==1 && !history.points().getFirst().connected(), "clock reset");
        history.reset(); check(history.points().isEmpty(), "configuration reset");
        check(ProcessTrendRange.valid(1.25,2.75) && !ProcessTrendRange.valid(2,2)
                && !ProcessTrendRange.valid(3,2) && !ProcessTrendRange.valid(-1,1)
                && !ProcessTrendRange.valid(Double.NaN,1) && !ProcessTrendRange.valid(0,Double.POSITIVE_INFINITY), "custom time interval");
    }
    private static void check(boolean value,String description) {
        if(!value) throw new AssertionError(description);
    }
}
