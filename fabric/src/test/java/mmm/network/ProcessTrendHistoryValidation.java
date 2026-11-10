package mmm.network;

public final class ProcessTrendHistoryValidation {
    public static void main(String[] args) {
        var history=new ProcessTrendHistory(); var first=new Object(); history.region(first);
        history.sample(0,1); history.sample(.05,2); history.sample(.05,3);
        check(history.points().size()==2 && history.points().getLast().value()==3, "same-time replacement");
        check(history.points().getLast().connected(), "continuous time");
        history.breakSeries("已暂停"); history.sample(.05,4);
        check(!history.points().getLast().connected(), "pause break");
        history.breakSeries(); history.sample(.1,5);
        check(!history.points().getLast().connected(), "unavailable break");
        for(int i=1;i<=300;i++) history.sample(i, i);
        check(history.points().size()==ProcessTrendHistory.LIMIT, "bounded history");
        history.region(new Object()); check(history.points().isEmpty(), "region incarnation");
        history.sample(2,1); history.sample(1,2);
        check(history.points().size()==1 && !history.points().getFirst().connected(), "clock reset");
        history.reset(); check(history.points().isEmpty(), "configuration reset");
    }
    private static void check(boolean value,String description) {
        if(!value) throw new AssertionError(description);
    }
}
