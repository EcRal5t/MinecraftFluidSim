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
    public ProcessTrendScreen(ProcessTrendPayload data) { super(Component.literal("工艺曲线")); this.data=data; }
    public void accept(ProcessTrendPayload next) { data=next; }
    public boolean matches(ProcessTrendPayload next, String dimension) {
        return data.position().equals(next.position()) && data.dimension().equals(dimension) && dimension.equals(next.dimension());
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void tick() {
        if (minecraft.level == null || !data.dimension().equals(minecraft.level.dimension().identifier().toString())) {
            onClose(); return;
        }
        if (data.available() && ++ticks % 10 == 0 && minecraft.player != null && ClientPlayNetworking.canSend(ProcessTrendRequest.TYPE))
            ClientPlayNetworking.send(new ProcessTrendRequest(data.position(),data.dimension()));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float partialTick) {
        super.extractRenderState(g,mouseX,mouseY,partialTick);
        int left=30, right=width-30, top=65, bottom=height-48;
        g.centeredText(font,title,width/2,15,0xffffffff);
        g.centeredText(font,Component.literal(data.label()+" · "+data.status()+" · 会话记录"),width/2,32,0xffd8d8d8);
        if (!data.available()) { g.centeredText(font,Component.literal("测点已离开范围或卸载"),width/2,50,0xffffaaaa); return; }
        if (right<=left || bottom<=top || data.points().isEmpty()) {
            g.centeredText(font,Component.literal("等待观测"),width/2,55,0xffaaaaaa); return;
        }
        var points=data.points();
        double minT=points.getFirst().seconds(), maxT=points.getLast().seconds();
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(var p:points){ min=Math.min(min,p.value()); max=Math.max(max,p.value()); }
        double padding=Math.max(1e-6,Math.max(Math.abs(min),Math.abs(max))*.05);
        if(max-min<2*padding){double center=(max+min)*.5;min=center-padding;max=center+padding;}
        double span=Math.max(1e-9,maxT-minT);
        g.fill(left,top,right+1,top+1,0xff777777); g.fill(left,bottom,right+1,bottom+1,0xff777777);
        int lastX=0,lastY=0; boolean previous=false;
        for(var p:points){
            int x=left+(int)Math.round((right-left)*(p.seconds()-minT)/span);
            int y=bottom-(int)Math.round((bottom-top)*(p.value()-min)/(max-min));
            if(previous && p.connected()) line(g,lastX,lastY,x,y);
            g.fill(x-1,y-1,x+2,y+2,0xff65d8ff); lastX=x;lastY=y;previous=true;
        }
        g.text(font,number(max),left,top-11,0xffbcbcbc,false);
        g.text(font,number(min),left,bottom+3,0xffbcbcbc,false);
        g.centeredText(font,Component.literal(number(minT)+" — "+number(maxT)+" s"),width/2,bottom+17,0xffbcbcbc);
    }
    private static void line(GuiGraphicsExtractor g,int x0,int y0,int x1,int y1){
        int n=Math.max(Math.abs(x1-x0),Math.abs(y1-y0));
        for(int i=0;i<=n;i++){int x=n==0?x0:x0+(x1-x0)*i/n, y=n==0?y0:y0+(y1-y0)*i/n; g.fill(x,y,x+1,y+1,0xff65d8ff);}
    }
    private static String number(double value){return String.format(Locale.ROOT,"%.3g",value);}
}
