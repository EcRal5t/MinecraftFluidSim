package mmm.client;

import java.util.Locale;
import mmm.network.ProcessTrendPayload;
import mmm.network.ProcessTrendRequest;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A read-only session history; polling ends when the screen closes. */
public final class ProcessTrendScreen extends Screen {
    private ProcessTrendPayload data;
    private int ticks;
    private long sequence, appliedSequence;
    public ProcessTrendScreen(ProcessTrendPayload data) { super(Component.literal("工艺曲线")); this.data=data; }
    public void accept(ProcessTrendPayload next) {
        if(ObservationOpen.newerReply(data.session(),sequence,appliedSequence,next.session(),next.sequence())) {
            appliedSequence=next.sequence(); data=next;
        }
    }
    public boolean matches(ProcessTrendPayload next, String dimension) {
        return data.session().equals(next.session()) && data.position().equals(next.position()) && data.dimension().equals(dimension) && dimension.equals(next.dimension());
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() {
        if (minecraft.level == null || !data.dimension().equals(minecraft.level.dimension().identifier().toString())) {
            onClose(); return;
        }
        if (data.available() && ++ticks % 10 == 0 && minecraft.player != null && ClientPlayNetworking.canSend(ProcessTrendRequest.TYPE))
            ClientPlayNetworking.send(new ProcessTrendRequest(data.position(),data.dimension(),data.session(),++sequence,false));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float partialTick) {
        IndustrialUi.panel(g, 8, 8, width - 16, height - 16);
        IndustrialUi.header(g, 9, 9, width - 18);
        super.extractRenderState(g,mouseX,mouseY,partialTick);
        IndustrialUi.brand(g, 13, 11, 16);
        int left=72, right=width-24, top=68, bottom=height-46;
        g.centeredText(font,title,width/2,15,0xffffffff);
        g.centeredText(font,Component.literal(data.label()+" · "+data.status()+" · 会话记录"),width/2,32,0xffd8d8d8);
        if (!data.available()) g.centeredText(font, Component.translatable("screen.mmm.trend.unavailable"), width/2, 49, IndustrialUi.FAULT);
        if (right<=left || bottom<=top || data.points().isEmpty()) {
            g.centeredText(font,Component.translatable("screen.mmm.trend.waiting"),width/2,55,IndustrialUi.MUTED); return;
        }
        var points=data.points();
        double minT=points.getFirst().seconds(), maxT=points.getLast().seconds();
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(var p:points){ min=Math.min(min,p.value()); max=Math.max(max,p.value()); }
        double padding=Math.max(1e-6,Math.max(Math.abs(min),Math.abs(max))*.05);
        if(max-min<2*padding){double center=(max+min)*.5;min=center-padding;max=center+padding;}
        double span=Math.max(1e-9,maxT-minT);
        g.fill(left, top, right + 1, bottom + 1, IndustrialUi.INSET);
        for (int i = 0; i <= 4; i++) {
            int y = top + (bottom - top) * i / 4;
            g.fill(left, y, right + 1, y + 1, 0xff30434b);
            g.text(font, number(max - (max - min) * i / 4.0), 16, y - 4, IndustrialUi.MUTED, false);
        }
        for (int i = 0; i <= 4; i++) {
            int x = left + (right - left) * i / 4;
            g.fill(x, top, x + 1, bottom + 1, 0xff30434b);
            if (i == 0 || i == 4) g.text(font, number(minT + (maxT - minT) * i / 4.0),
                    i == 0 ? x : Math.max(left, x - 28), bottom + 4, IndustrialUi.MUTED, false);
        }
        int lastX=0,lastY=0; boolean previous=false;
        for(var p:points){
            int x=left+(int)Math.round((right-left)*(p.seconds()-minT)/span);
            int y=bottom-(int)Math.round((bottom-top)*(p.value()-min)/(max-min));
            if(previous && p.connected()) line(g,lastX,lastY,x,y);
            else if (previous) g.fill(x-1, top+2, x+2, top+7, IndustrialUi.AMBER);
            g.fill(x-1,y-1,x+2,y+2,0xff65d8ff); lastX=x;lastY=y;previous=true;
        }
        boolean gaps = false;
        for (int i = 1; i < points.size(); i++) if (!points.get(i).connected()) { gaps = true; break; }
        g.centeredText(font, gaps ? Component.literal("s  ·  ").append(Component.translatable("screen.mmm.trend.gap"))
                : Component.literal("s"), (left + right) / 2, bottom + 17, gaps ? IndustrialUi.AMBER : IndustrialUi.MUTED);
        if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {
            var nearest = points.getFirst();
            double target = minT + span * (mouseX - left) / (right - left);
            for (var point : points) if (Math.abs(point.seconds() - target) < Math.abs(nearest.seconds() - target)) nearest = point;
            int x = left + (int)Math.round((right-left) * (nearest.seconds()-minT) / span);
            g.fill(x, top, x+1, bottom+1, 0xff8ba6aa);
            g.centeredText(font, Component.literal(number(nearest.seconds()) + " s  ·  " + number(nearest.value())),
                    (left+right)/2, top+4, IndustrialUi.TEXT);
        }
    }
    private static void line(GuiGraphicsExtractor g,int x0,int y0,int x1,int y1){
        int n=Math.max(Math.abs(x1-x0),Math.abs(y1-y0));
        for(int i=0;i<=n;i++){int x=n==0?x0:x0+(x1-x0)*i/n, y=n==0?y0:y0+(y1-y0)*i/n; g.fill(x,y,x+1,y+1,0xff65d8ff);}
    }
    private static String number(double value){return String.format(Locale.ROOT,"%.3g",value);}
}
