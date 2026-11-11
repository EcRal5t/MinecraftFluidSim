package mmm.client;

import java.util.List;
import mmm.network.ProcessTrendPayload.Series;

/** One shared physical scale for every visible component. */
final class ProcessTrendPlot {
    private ProcessTrendPlot() { }
    record Axis(double minimum,double maximum,double step) { }
    static Axis axis(List<Series> series,double start,double end) {
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(var s:series) for(var p:s.points()) if(p.seconds()>=start && p.seconds()<=end) {
            min=Math.min(min,p.value());max=Math.max(max,p.value());
        }
        if(!Double.isFinite(min)) return null;
        double padding=max>min?(max-min)*.05:Math.max(.001,Math.abs(min)*.05);
        double low=min-padding,high=max+padding,rough=(high-low)/4;
        double unit=Math.pow(10,Math.floor(Math.log10(rough))),fraction=rough/unit;
        double step=unit*(fraction<=1?1:fraction<=2?2:fraction<=5?5:10);
        return new Axis(Math.floor(low/step)*step,Math.ceil(high/step)*step,step);
    }
    static int y(double value,Axis axis,int top,int bottom) {
        return bottom-(int)Math.round((bottom-top)*(value-axis.minimum())/(axis.maximum()-axis.minimum()));
    }
    static String number(double value) {
        if(value==0) return "0";
        var rounded=java.math.BigDecimal.valueOf(value).round(new java.math.MathContext(5)).stripTrailingZeros();
        return Math.abs(value)>=.001 && Math.abs(value)<1e6?rounded.toPlainString():rounded.toString();
    }
}
