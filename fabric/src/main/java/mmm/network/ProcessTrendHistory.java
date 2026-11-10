package mmm.network;

import java.util.ArrayDeque;
import java.util.List;

/** Bounded, session-only observations on the region's simulation clock. */
public final class ProcessTrendHistory {
    public static final int LIMIT = 256;
    public record Point(double seconds, double value, boolean connected) { }
    private final ArrayDeque<Point> points = new ArrayDeque<>();
    private Object region;
    private boolean broken = true;
    private String status = "等待观测";

    public void reset() { points.clear(); region = null; broken = true; status = "等待观测"; }
    public void region(Object next) {
        if (region != next) { reset(); region = next; }
    }
    public void breakSeries() { breakSeries("不可用"); }
    public void breakSeries(String status) { broken = true; this.status = status; }
    public String status() { return status; }
    public void sample(double seconds, double value) {
        if (!Double.isFinite(seconds) || !Double.isFinite(value)) { breakSeries(); return; }
        Point last = points.peekLast();
        if (last != null && seconds < last.seconds()) { points.clear(); broken = true; last = null; }
        if (last != null && seconds == last.seconds() && !broken) {
            points.removeLast(); points.addLast(new Point(seconds, value, last.connected())); return;
        }
        if (points.size() == LIMIT) points.removeFirst();
        points.addLast(new Point(seconds, value, !broken && last != null));
        broken = false; status = "运行中";
    }
    public List<Point> points() { return List.copyOf(points); }
}
