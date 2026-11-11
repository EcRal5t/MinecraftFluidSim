package mmm.client;

import java.util.List;
import mmm.network.ProcessTrendHistory.Point;
import mmm.network.ProcessTrendPayload;
import mmm.network.ProcessTrendPayload.Metric;
import mmm.network.ProcessTrendPayload.Series;
import mmm.network.ProcessTrendPayload.SeriesKey;

final class ProcessTrendPlotValidation {
    static void run() {
        var water=new Series(new SeriesKey("mmm:h2o","LIQUID",Metric.MASS),List.of(new Point(0,1000,false),new Point(5,200,true)));
        var oil=new Series(new SeriesKey("mmm:oil","LIQUID",Metric.MASS),List.of(new Point(5,10,false),new Point(6,20,true)));
        var axis=ProcessTrendPlot.axis(List.of(water,oil),5,6);
        check(axis.minimum()<=10 && axis.maximum()>=200 && axis.maximum()<1000,"Shared axis must include both visible components and exclude out-of-window points");
        check(ProcessTrendPlot.y(200,axis,0,200)<ProcessTrendPlot.y(10,axis,0,200),"Larger component value is higher on the same axis");
        check(ProcessTrendPlot.axis(List.of(water,oil),7,8)==null,"Empty interval has no fabricated axis data");
        var flat=ProcessTrendPlot.axis(List.of(oil),5,5);
        check(flat.minimum()<10 && flat.maximum()>10 && flat.step()>0,"Constant value remains visible");
        check(ProcessTrendPlot.number(90).equals("90") && ProcessTrendPlot.number(.005).equals("0.005"),"Ordinary axis numbers retain readable notation");
        check(CompositionNames.label("mmm:n2").equals(CompositionNames.label("mmm:nitrogen"))
                && CompositionNames.label("mmm:h2o").equals(CompositionNames.label("mmm:water")),"Carrier residual and species use identical names");
        check(CompositionNames.phaseIcon(mmm.controller.ControllerSummary.Phase.LIQUID,true)==null
                && CompositionNames.phaseIcon(mmm.controller.ControllerSummary.Phase.GAS,false)==IndustrialUi.Icon.GAS,"Ions omit phase icons; gases retain them");
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
