package mmm.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import mmm.network.ProcessTrendPayload;
import mmm.network.ProcessTrendPayload.Metric;
import mmm.network.ProcessTrendPayload.SeriesKey;
import mmm.network.ProcessTrendRequest;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Selected component histories share a physical axis and a colour-keyed legend. */
public final class ProcessTrendScreen extends Screen {
    private static final int[] COLORS = {0xff65d8ff,0xffffc568,0xffc4a4ff,0xff8de29d,
            0xffff8f9e,0xffe9e6ab,0xff69b4ff,0xffdd9be3,0xffb9d876,0xffffa269,
            0xff65d9b1,0xffb5b7ff,0xfff486c3,0xff93d5e1,0xffd6bd9a,0xffe6e8ef};
    private static final int[] WINDOWS = {10,30,60,0};
    private ProcessTrendPayload data, frozen;
    private final LinkedHashSet<SeriesKey> selected = new LinkedHashSet<>();
    private Metric metric;
    private int ticks, windowIndex=1, offset;
    private double end=Double.NaN, customStart=Double.NaN, customEnd=Double.NaN;
    private EditBox startField,endField;
    private Button applyRange;
    private long sequence,appliedSequence;
    private boolean initializedSelection;
    private final List<Button> componentButtons=new ArrayList<>();

    public ProcessTrendScreen(ProcessTrendPayload data) {
        super(text("title")); this.data=data;
        try { metric=Metric.valueOf(data.label()); } catch(IllegalArgumentException ex) { metric=Metric.LEVEL; }
        chooseInitial();
    }
    private static Component text(String key) { return Component.translatable("screen.mmm.trend."+key); }
    private static Component name(Metric value) { return text("metric."+value.name().toLowerCase(Locale.ROOT)); }
    private ProcessTrendPayload view() { return frozen==null?data:frozen; }
    private List<SeriesKey> choices() {
        // A disappeared component remains selectable while its recorded series is retained.
        var result=new LinkedHashSet<>(view().catalog());
        view().series().forEach(s -> result.add(s.key()));
        return result.stream().filter(k -> k.metric()==metric).toList();
    }
    private void chooseInitial() {
        if(initializedSelection) return;
        var candidates=choices();
        if(candidates.isEmpty()) return;
        selected.add(candidates.stream().filter(k -> k.componentId().isEmpty()).findFirst().orElse(candidates.getFirst()));
        initializedSelection=true;
    }
    public void accept(ProcessTrendPayload next) {
        if(ObservationOpen.newerReply(data.session(),sequence,appliedSequence,next.session(),next.sequence())) {
            var old=choices(); appliedSequence=next.sequence(); data=next;
            chooseInitial(); if(!old.equals(choices())) rebuildWidgets();
        }
    }
    public boolean matches(ProcessTrendPayload next,String dimension) {
        return data.session().equals(next.session()) && data.position().equals(next.position())
                && data.dimension().equals(dimension) && dimension.equals(next.dimension());
    }
    @Override protected void init() {
        String startText=startField==null?"":startField.getValue(),endText=endField==null?"":endField.getValue();
        var metrics=view().catalog().stream().map(SeriesKey::metric).distinct().sorted().toList();
        int buttonWidth=(width-36-4*Math.max(0,metrics.size()-1))/Math.max(1,metrics.size());
        for(int i=0;i<metrics.size();i++) {
            var value=metrics.get(i);
            addRenderableWidget(IndustrialUi.button(name(value),button -> {
                var previous=List.copyOf(selected); selected.clear(); metric=value; offset=0;initializedSelection=false;
                for(var candidate:choices()) if(previous.stream().anyMatch(k -> k.componentId().equals(candidate.componentId())
                        && k.phase().equals(candidate.phase()) && k.residualMass()==candidate.residualMass())) selected.add(candidate);
                if(!selected.isEmpty()) initializedSelection=true;
                chooseInitial(); request(); rebuildWidgets();
            }).selected(metric==value).bounds(18+i*(buttonWidth+4),32,buttonWidth,20).build());
        }
        addRenderableWidget(IndustrialUi.button(windowLabel(),button -> {
            windowIndex=(windowIndex+1)%WINDOWS.length;end=customStart=customEnd=Double.NaN;rebuildWidgets();
        }).bounds(18,57,112,20).build());
        addRenderableWidget(IndustrialUi.button(text("back"),button -> move(-1)).bounds(134,57,72,20).build());
        addRenderableWidget(IndustrialUi.button(text("forward"),button -> move(1)).bounds(210,57,72,20).build());
        addRenderableWidget(IndustrialUi.button(text(frozen==null?"freeze":"live"),button -> {
            if(frozen==null) { frozen=data;end=Double.NaN; } else { frozen=null;end=customStart=customEnd=Double.NaN; }
            rebuildWidgets();
        }).bounds(286,57,100,20).build());
        startField=new EditBox(font,435,57,75,20,text("from"));endField=new EditBox(font,550,57,75,20,text("to"));
        startField.setValue(startText);endField.setValue(endText);
        startField.setResponder(ignored -> updateRangeButton());endField.setResponder(ignored -> updateRangeButton());
        addRenderableWidget(startField);addRenderableWidget(endField);
        applyRange=addRenderableWidget(IndustrialUi.button(text("apply"),button -> {
            if(frozen==null) frozen=data;
            customStart=parse(startField);customEnd=parse(endField);end=customEnd;rebuildWidgets();
        }).bounds(630,57,70,20).build());
        updateRangeButton();
        var candidates=choices(); offset=Math.clamp(offset,0,Math.max(0,candidates.size()-visibleRows()));
        componentButtons.clear();
        for(int i=offset;i<Math.min(candidates.size(),offset+visibleRows());i++) {
            var key=candidates.get(i);
            var button=IndustrialUi.button(Component.empty(),ignored -> {
                if(!selected.remove(key)) selected.add(key);
                request();rebuildWidgets();
            }).selected(selected.contains(key)).bounds(18,106+(i-offset)*23,180,21).build();
            button.setMessage(label(key));
            // The legend draws the label with its phase icon; narration retains the full name.
            button.active=selected.contains(key)||selected.size()<ProcessTrendRequest.MAX_SELECTED;
            componentButtons.add(addWidget(button));
        }
    }
    private int visibleRows() { return Math.max(1,(height-153)/23); }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {
        if(x>=18 && x<=202) { offset=Math.clamp(offset-(int)Math.signum(vertical)*3,0,Math.max(0,choices().size()-visibleRows()));rebuildWidgets();return true; }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    private void request() {
        if(minecraft!=null && minecraft.player!=null && ClientPlayNetworking.canSend(ProcessTrendRequest.TYPE))
            ClientPlayNetworking.send(new ProcessTrendRequest(data.position(),data.dimension(),data.session(),++sequence,false,List.copyOf(selected)));
    }
    @Override public void removed() {
        if(minecraft.player!=null && ClientPlayNetworking.canSend(ProcessTrendRequest.TYPE))
            ClientPlayNetworking.send(new ProcessTrendRequest(data.position(),data.dimension(),data.session(),++sequence,false,List.of(),true));
        super.removed();
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() {
        if(minecraft.level==null || !data.dimension().equals(minecraft.level.dimension().identifier().toString())) { onClose();return; }
        if(++ticks%10==0) request();
    }
    private void updateRangeButton() { if(applyRange!=null) applyRange.active=mmm.network.ProcessTrendRange.valid(parse(startField),parse(endField)); }
    private static double parse(EditBox field) { try { return Double.parseDouble(field.getValue()); } catch(NumberFormatException ex) { return Double.NaN; } }
    private Component windowLabel() {
        return Double.isFinite(customStart)?text("window_custom"):WINDOWS[windowIndex]==0?text("window_all")
                :Component.translatable("screen.mmm.trend.window",WINDOWS[windowIndex]);
    }
    private double[] extent() {
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(var series:view().series()) if(selected.contains(series.key())) for(var p:series.points()) { min=Math.min(min,p.seconds());max=Math.max(max,p.seconds()); }
        return new double[]{min,max};
    }
    private double windowSpan(double[] extent) { return WINDOWS[windowIndex]==0?Math.max(.001,extent[1]-extent[0]):WINDOWS[windowIndex]; }
    private void move(int direction) {
        if(frozen==null) frozen=data;
        double[] extent=extent();if(!Double.isFinite(extent[0])) { rebuildWidgets();return; }
        double span=Double.isNaN(customStart)?windowSpan(extent):customEnd-customStart,current=Double.isNaN(end)?extent[1]:end;
        end=Math.max(Math.min(extent[1],extent[0]+span),Math.min(extent[1],current+direction*span*.5));
        if(!Double.isNaN(customStart)) { customStart=Math.max(0,end-span);customEnd=customStart+span;end=customEnd; }
        rebuildWidgets();
    }
    private static Component label(SeriesKey key) {
        var label=key.componentId().isEmpty()?text("all"):CompositionNames.label(key.componentId());
        return key.residualMass()?label.copy().append(" ").append(Component.translatable("screen.mmm.controller.residual_mass")):label;
    }
    private int color(SeriesKey key) { return COLORS[Math.max(0,new ArrayList<>(selected).indexOf(key))%COLORS.length]; }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float partialTick) {
        IndustrialUi.panel(g,8,8,width-16,height-16);IndustrialUi.header(g,9,9,width-18);
        super.extractRenderState(g,mouseX,mouseY,partialTick);IndustrialUi.brand(g,13,11,16);
        g.centeredText(font,title,width/2,14,IndustrialUi.TEXT);
        g.text(font,text(data.available()?"status."+data.status().toLowerCase(Locale.ROOT):"unavailable"),width-135,14,IndustrialUi.MUTED,false);
        g.text(font,text("from"),393,63,IndustrialUi.MUTED,false);g.text(font,text("to"),516,63,IndustrialUi.MUTED,false);
        double[] extent=extent();
        double maxT=Double.isNaN(customStart)?(Double.isNaN(end)?extent[1]:end):customEnd;
        double minT=Double.isNaN(customStart)?Math.max(extent[0],maxT-windowSpan(extent)):customStart;
        if(maxT<=minT) maxT=minT+.001;
        int left=282,right=width-25,top=107,bottom=height-48;
        double hover=mouseX>=left && mouseX<=right && mouseY>=top && mouseY<=bottom && Double.isFinite(minT)
                ?minT+(maxT-minT)*(mouseX-left)/(right-left):Double.NaN;
        legend(g,hover,minT,maxT,mouseX,mouseY);
        g.text(font,name(metric).copy().append(" ("+metric.unit()+")"),left,87,IndustrialUi.CYAN,false);
        if(!Double.isFinite(extent[0])) { g.centeredText(font,text("waiting"),(left+right)/2,(top+bottom)/2,IndustrialUi.MUTED);return; }
        chart(g,left,right,top,bottom,minT,maxT,hover);
    }
    private void legend(GuiGraphicsExtractor g,double hover,double minT,double maxT,int mouseX,int mouseY) {
        g.text(font,text("components"),22,90,IndustrialUi.CYAN,false);
        var candidates=choices();
        for(int i=offset;i<Math.min(candidates.size(),offset+visibleRows());i++) {
            var key=candidates.get(i);int y=106+(i-offset)*23;boolean active=selected.contains(key);
            g.fill(18,y,198,y+21,active?0xff29454c:IndustrialUi.INSET);
            if(mouseX>=18&&mouseX<198&&mouseY>=y&&mouseY<y+21 || componentButtons.get(i-offset).isFocused())
                g.fill(18,y,20,y+21,IndustrialUi.CYAN);
            g.fill(22,y+7,29,y+14,active?color(key):IndustrialUi.EDGE);
            int x=33;
            if(!key.phase().isEmpty()) {
                var icon=CompositionNames.phaseIcon(mmm.controller.ControllerSummary.Phase.valueOf(key.phase()),key.ion());
                if(icon!=null) { IndustrialUi.icon(g,icon,x,y+3,14);x+=17; }
            }
            g.enableScissor(x,y,195,y+21);
            g.text(font,label(key),x,y+(active&&Double.isFinite(hover)?1:6),active?IndustrialUi.TEXT:IndustrialUi.MUTED,false);
            if(active && Double.isFinite(hover)) {
                var p=nearest(key,hover,minT,maxT);String value=p==null?"—":ProcessTrendPlot.number(p.value())+" "+metric.unit();
                g.text(font,value,x,y+11,color(key),false);
            }
            g.disableScissor();
        }
        IndustrialUi.scrollbar(g,201,106,visibleRows()*23,offset,visibleRows(),candidates.size());
        g.text(font,selected.size()+" / "+ProcessTrendRequest.MAX_SELECTED,22,height-33,IndustrialUi.MUTED,false);
    }
    private mmm.network.ProcessTrendHistory.Point nearest(SeriesKey key,double seconds,double minT,double maxT) {
        return view().series().stream().filter(s -> s.key().equals(key)).flatMap(s -> s.points().stream())
                .filter(p -> p.seconds()>=minT && p.seconds()<=maxT)
                .min(java.util.Comparator.comparingDouble(p -> Math.abs(p.seconds()-seconds))).orElse(null);
    }
    private void chart(GuiGraphicsExtractor g,int left,int right,int top,int bottom,double minT,double maxT,double hover) {
        var series=view().series().stream().filter(s -> selected.contains(s.key())).toList();
        var axis=ProcessTrendPlot.axis(series,minT,maxT);
        g.fill(left,top,right+1,bottom+1,IndustrialUi.INSET);
        if(axis==null) { g.centeredText(font,text("no_data"),(left+right)/2,(top+bottom)/2,IndustrialUi.MUTED);return; }
        for(int i=0;i<=Math.round((axis.maximum()-axis.minimum())/axis.step());i++) {
            double value=axis.minimum()+i*axis.step();int y=ProcessTrendPlot.y(value,axis,top,bottom);
            g.fill(left,y,right+1,y+1,0xff30434b);String label=ProcessTrendPlot.number(value);
            g.text(font,label,left-5-font.width(label),y-4,IndustrialUi.MUTED,false);
        }
        for(int i=0;i<=4;i++) {
            int x=left+(right-left)*i/4;g.fill(x,top,x+1,bottom+1,0xff30434b);
            String label=ProcessTrendPlot.number(minT+(maxT-minT)*i/4);
            g.text(font,label,Math.clamp(x-font.width(label)/2,left,right-font.width(label)),bottom+5,IndustrialUi.MUTED,false);
        }
        g.enableScissor(left,top,right+1,bottom+1);
        for(var s:series) {
            int lastX=0,lastY=0;boolean previous=false;
            for(var p:s.points()) {
                if(p.seconds()<minT||p.seconds()>maxT) { previous=false;continue; }
                int x=left+(int)Math.round((right-left)*(p.seconds()-minT)/(maxT-minT)),y=ProcessTrendPlot.y(p.value(),axis,top,bottom);
                if(previous&&p.connected()) line(g,lastX,lastY,x,y,color(s.key()));
                else if(previous) g.fill(x-1,top+2,x+2,top+6,IndustrialUi.AMBER);
                g.fill(x-1,y-1,x+2,y+2,color(s.key()));lastX=x;lastY=y;previous=true;
            }
        }
        if(Double.isFinite(hover)) { int x=left+(int)((hover-minT)*(right-left)/(maxT-minT));g.fill(x,top,x+1,bottom,0xff8ba6aa); }
        g.disableScissor();
        g.centeredText(font,text("time"),(left+right)/2,bottom+20,IndustrialUi.MUTED);
        if(Double.isFinite(hover)) g.text(font,ProcessTrendPlot.number(hover)+" s",right-95,87,IndustrialUi.TEXT,false);
        g.fill(right-52,bottom+22,right-49,bottom+27,IndustrialUi.AMBER);g.text(font,text("gap"),right-45,bottom+20,IndustrialUi.AMBER,false);
    }
    private static void line(GuiGraphicsExtractor g,int x0,int y0,int x1,int y1,int color) {
        int n=Math.max(Math.abs(x1-x0),Math.abs(y1-y0));
        for(int i=0;i<=n;i++) { int x=n==0?x0:x0+(x1-x0)*i/n,y=n==0?y0:y0+(y1-y0)*i/n;g.fill(x,y,x+1,y+1,color); }
    }
}
