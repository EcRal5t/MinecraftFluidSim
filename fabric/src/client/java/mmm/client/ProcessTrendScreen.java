package mmm.client;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import mmm.api.sensor.ProcessSensorSnapshot.Measure;
import mmm.network.ProcessTrendPayload;
import mmm.network.ProcessTrendRequest;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Bounded server history with a locally frozen view for inspection. */
public final class ProcessTrendScreen extends Screen {
    private static final int[] COLORS = {0xff65d8ff, 0xffffc568, 0xffc4a4ff, 0xff8de29d, 0xffff8f9e};
    private static final int[] WINDOWS = {10, 30, 60, 0};
    private ProcessTrendPayload data, frozen;
    private final EnumSet<Measure> selected = EnumSet.noneOf(Measure.class);
    private int ticks, windowIndex = 1;
    private double end = Double.NaN, customStart = Double.NaN, customEnd = Double.NaN;
    private EditBox startField, endField;
    private Button applyRange;
    private long sequence, appliedSequence;
    public ProcessTrendScreen(ProcessTrendPayload data) {
        super(Component.translatable("screen.mmm.trend.title")); this.data=data;
        try { selected.add(Measure.valueOf(data.label())); } catch (IllegalArgumentException ex) { selected.add(Measure.LEVEL); }
    }
    public void accept(ProcessTrendPayload next) {
        if(ObservationOpen.newerReply(data.session(),sequence,appliedSequence,next.session(),next.sequence())) {
            appliedSequence=next.sequence(); data=next;
        }
    }
    public boolean matches(ProcessTrendPayload next, String dimension) {
        return data.session().equals(next.session()) && data.position().equals(next.position()) && data.dimension().equals(dimension) && dimension.equals(next.dimension());
    }
    @Override protected void init() {
        String startText=startField==null?"":startField.getValue(), endText=endField==null?"":endField.getValue();
        int left=18, gap=4, buttonWidth=(width-36-4*gap)/5;
        for (var metric : Measure.values()) {
            int x=left+metric.ordinal()*(buttonWidth+gap);
            addRenderableWidget(IndustrialUi.button(name(metric), button -> {
                if (selected.contains(metric)) { if(selected.size()>1) selected.remove(metric); } else selected.add(metric);
                rebuildWidgets();
            }).selected(selected.contains(metric)).bounds(x,32,buttonWidth,20).build());
        }
        addRenderableWidget(IndustrialUi.button(windowLabel(), button -> { windowIndex=(windowIndex+1)%WINDOWS.length; end=customStart=customEnd=Double.NaN; rebuildWidgets(); }).bounds(18,57,112,20).build());
        addRenderableWidget(IndustrialUi.button(Component.translatable("screen.mmm.trend.back"), button -> move(-1)).bounds(134,57,72,20).build());
        addRenderableWidget(IndustrialUi.button(Component.translatable("screen.mmm.trend.forward"), button -> move(1)).bounds(210,57,72,20).build());
        addRenderableWidget(IndustrialUi.button(Component.translatable(frozen==null ? "screen.mmm.trend.freeze" : "screen.mmm.trend.live"), button -> {
            if(frozen==null) { frozen=data; end=Double.NaN; } else { frozen=null; end=customStart=customEnd=Double.NaN; }
            rebuildWidgets();
        }).bounds(286,57,100,20).build());
        startField=new EditBox(font,435,57,75,20,Component.translatable("screen.mmm.trend.from"));
        endField=new EditBox(font,550,57,75,20,Component.translatable("screen.mmm.trend.to"));
        startField.setValue(startText); endField.setValue(endText);
        startField.setResponder(ignored -> updateRangeButton()); endField.setResponder(ignored -> updateRangeButton());
        addRenderableWidget(startField); addRenderableWidget(endField);
        applyRange=addRenderableWidget(IndustrialUi.button(Component.translatable("screen.mmm.trend.apply"), button -> {
            if(frozen==null) frozen=data;
            customStart=parse(startField); customEnd=parse(endField); end=customEnd; rebuildWidgets();
        }).bounds(630,57,70,20).build());
        updateRangeButton();
    }
    private void updateRangeButton() { if(applyRange!=null) applyRange.active=mmm.network.ProcessTrendRange.valid(parse(startField),parse(endField)); }
    private static double parse(EditBox field) { try { return Double.parseDouble(field.getValue()); } catch (NumberFormatException ex) { return Double.NaN; } }

    private Component windowLabel() {
        if (Double.isFinite(customStart)) return Component.translatable("screen.mmm.trend.window_custom");
        return WINDOWS[windowIndex]==0 ? Component.translatable("screen.mmm.trend.window_all") : Component.translatable("screen.mmm.trend.window",WINDOWS[windowIndex]);
    }
    private static Component name(Measure metric) { return Component.translatable("screen.mmm.trend.metric."+metric.name().toLowerCase(Locale.ROOT)); }
    private List<ProcessTrendPayload.Series> series() { return (frozen==null?data:frozen).series(); }
    private double[] extent() {
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(var series:series()) for(var p:series.points()) { min=Math.min(min,p.seconds()); max=Math.max(max,p.seconds()); }
        return new double[]{min,max};
    }
    private void move(int direction) {
        if(frozen==null) frozen=data;
        double[] extent=extent(); if(!Double.isFinite(extent[0])) { rebuildWidgets(); return; }
        double span=Double.isNaN(customStart)?windowSpan(extent):customEnd-customStart; double current=Double.isNaN(end)?extent[1]:end;
        end=Math.max(Math.min(extent[1],extent[0]+span),Math.min(extent[1],current+direction*span*.5));
        if(!Double.isNaN(customStart)) { customStart=Math.max(0,end-span); customEnd=customStart+span; end=customEnd; }
        rebuildWidgets();
    }
    private double windowSpan(double[] extent) { return WINDOWS[windowIndex]==0?Math.max(.001,extent[1]-extent[0]):WINDOWS[windowIndex]; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() {
        if (minecraft.level == null || !data.dimension().equals(minecraft.level.dimension().identifier().toString())) { onClose(); return; }
        if (data.available() && ++ticks % 10 == 0 && minecraft.player != null && ClientPlayNetworking.canSend(ProcessTrendRequest.TYPE))
            ClientPlayNetworking.send(new ProcessTrendRequest(data.position(),data.dimension(),data.session(),++sequence,false));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float partialTick) {
        IndustrialUi.panel(g,8,8,width-16,height-16); IndustrialUi.header(g,9,9,width-18);
        super.extractRenderState(g,mouseX,mouseY,partialTick); IndustrialUi.brand(g,13,11,16);
        g.centeredText(font,title,width/2,14,IndustrialUi.TEXT);
        if(data.available()) g.text(font,Component.translatable("screen.mmm.trend.status."+data.status().toLowerCase(Locale.ROOT)),width-125,14,IndustrialUi.MUTED,false);
        g.text(font,Component.translatable("screen.mmm.trend.from"),393,63,IndustrialUi.MUTED,false);
        g.text(font,Component.translatable("screen.mmm.trend.to"),516,63,IndustrialUi.MUTED,false);
        if(!data.available()) g.text(font,Component.translatable("screen.mmm.trend.unavailable"),705,63,IndustrialUi.FAULT,false);
        double[] extent=extent();
        if(!Double.isFinite(extent[0])) { g.centeredText(font,Component.translatable("screen.mmm.trend.waiting"),width/2,height/2,IndustrialUi.MUTED); return; }
        double span=windowSpan(extent), maxT=Double.isNaN(customStart)?(Double.isNaN(end)?extent[1]:end):customEnd, minT=Double.isNaN(customStart)?Math.max(extent[0],maxT-span):customStart;
        if(maxT<=minT) maxT=minT+.001;
        int left=106,right=width-25,top=90,bottom=height-40, count=selected.size(), index=0;
        for(var metric:Measure.values()) if(selected.contains(metric)) {
            int y0=top+(bottom-top)*index/count, y1=top+(bottom-top)*(index+1)/count-12;
            var points=series().stream().filter(s -> s.measure()==metric).findFirst().map(ProcessTrendPayload.Series::points).orElse(List.of());
            chart(g,metric,points,left,right,y0,y1,minT,maxT,mouseX,mouseY); index++;
        }
        for (int i = 0; i <= 4; i++) {
            String label = number(minT + (maxT - minT) * i / 4);
            int x = left + (right - left) * i / 4;
            g.text(font, label, Math.clamp(x - font.width(label) / 2, left, right - font.width(label)), bottom + 2, IndustrialUi.MUTED, false);
        }
        g.centeredText(font,Component.translatable("screen.mmm.trend.time"),(left+right)/2,bottom+15,IndustrialUi.MUTED);
        g.fill(right-80,bottom+17,right-77,bottom+22,IndustrialUi.AMBER);
        g.text(font,Component.translatable("screen.mmm.trend.gap"),right-73,bottom+15,IndustrialUi.AMBER,false);
    }
    private void chart(GuiGraphicsExtractor g,Measure metric,List<mmm.network.ProcessTrendHistory.Point> points,int left,int right,int top,int bottom,double minT,double maxT,int mouseX,int mouseY) {
        int color=COLORS[metric.ordinal()];
        g.fill(left,top,right+1,bottom+1,IndustrialUi.INSET);
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(var p:points) if(p.seconds()>=minT && p.seconds()<=maxT) { min=Math.min(min,p.value()); max=Math.max(max,p.value()); }
        Component legend=Component.empty().append(name(metric)).append(" ("+metric.unit()+")");
        if(!Double.isFinite(min)) { g.text(font,legend,left+6,top+3,color,false); g.centeredText(font,Component.translatable("screen.mmm.trend.no_data"),(left+right)/2,top+4,IndustrialUi.MUTED); return; }
        AxisRange axis = AxisRange.of(min, max);
        min = axis.minimum(); max = axis.maximum();
        for (int i = 0; i <= Math.round((max - min) / axis.step()); i++) {
            double value = min + i * axis.step();
            int y = bottom - (int)Math.round((bottom - top) * (value - min) / (max - min));
            g.fill(left, y, right + 1, y + 1, 0xff30434b);
            String label = number(value);
            g.text(font, label, left - 4 - font.width(label), y - 4, IndustrialUi.MUTED, false);
        }
        for(int i=0;i<=4;i++) { int x=left+(right-left)*i/4; g.fill(x,top,x+1,bottom+1,0xff30434b); }
        int lastX=0,lastY=0; boolean previous=false;
        for(var p:points) {
            if(p.seconds()<minT || p.seconds()>maxT) { previous=false; continue; }
            int x=left+(int)Math.round((right-left)*(p.seconds()-minT)/(maxT-minT));
            int y=bottom-(int)Math.round((bottom-top)*(p.value()-min)/(max-min));
            if(previous && p.connected()) line(g,lastX,lastY,x,y,color);
            else if(previous) g.fill(x-1,top+2,x+2,top+6,IndustrialUi.AMBER);
            g.fill(x-1,y-1,x+2,y+2,color); lastX=x;lastY=y;previous=true;
        }
        g.fill(left+3,top+2,left+font.width(legend)+9,top+13,IndustrialUi.INSET);
        g.text(font,legend,left+6,top+3,color,false);
        if(mouseX>=left && mouseX<=right && mouseY>=top && mouseY<=bottom) {
            double target=minT+(maxT-minT)*(mouseX-left)/(right-left);
            var nearest=points.stream().filter(p -> p.seconds()>=minT && p.seconds()<=maxT).min(java.util.Comparator.comparingDouble(p -> Math.abs(p.seconds()-target))).orElse(null);
            if(nearest!=null) {
                int x=left+(int)Math.round((right-left)*(nearest.seconds()-minT)/(maxT-minT));
                g.fill(x,top,x+1,bottom+1,0xff8ba6aa);
                g.text(font,number(nearest.seconds())+" s",left+(right-left)/2-95,top+3,IndustrialUi.TEXT,false);
                g.text(font,number(nearest.value())+" "+metric.unit(),left+(right-left)/2+12,top+3,IndustrialUi.TEXT,false);
            }
        }
    }
    private static void line(GuiGraphicsExtractor g,int x0,int y0,int x1,int y1,int color) {
        int n=Math.max(Math.abs(x1-x0),Math.abs(y1-y0));
        for(int i=0;i<=n;i++) { int x=n==0?x0:x0+(x1-x0)*i/n,y=n==0?y0:y0+(y1-y0)*i/n; g.fill(x,y,x+1,y+1,color); }
    }
    private record AxisRange(double minimum, double maximum, double step) {
        static AxisRange of(double minimum, double maximum) {
            double padding = maximum > minimum ? (maximum - minimum) * .05 : Math.max(.001, Math.abs(minimum) * .05);
            double low = minimum - padding, high = maximum + padding;
            double rough = (high - low) / 2, unit = Math.pow(10, Math.floor(Math.log10(rough)));
            double fraction = rough / unit;
            double step = unit * (fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10);
            return new AxisRange(Math.floor(low / step) * step, Math.ceil(high / step) * step, step);
        }
    }
    private static String number(double value) {
        if (value == 0) return "0";
        var rounded = java.math.BigDecimal.valueOf(value).round(new java.math.MathContext(8)).stripTrailingZeros();
        return Math.abs(value) >= .001 && Math.abs(value) < 1e6 ? rounded.toPlainString() : rounded.toString();
    }
}
