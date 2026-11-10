package mmm.simulation;

import java.util.List;
import java.util.Optional;
import mmm.block.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dialog.*;
import net.minecraft.server.dialog.action.CommandTemplate;
import net.minecraft.server.dialog.action.ParsedTemplate;
import net.minecraft.server.dialog.input.*;
import net.minecraft.server.level.ServerPlayer;

/** Native Minecraft forms for the settings stored by industrial blocks. */
final class IndustrialDialogs {
    private IndustrialDialogs(){}
    static void box(ServerPlayer player,MaterialBoxBlockEntity box){
        var values=new String[box.getContainerSize()];var labels=new String[values.length];
        for(int i=0;i<values.length;i++){values[i]=Integer.toString(i);labels[i]=(i+1)+" · "+IndustrialOperations.describe(box.getItem(i));}
        show(player,"物料箱",List.of(option("slot","批次",Integer.toString(box.selected()),values,labels)),command(box.getBlockPos())+"box $(slot)");
    }
    static void port(ServerPlayer player,FluidPortBlockEntity port){
        show(player,"容器接口 · "+port.status(),List.of(
            option("mode","运行",port.mode().name(),new String[]{"STOPPED","INPUT","OUTPUT"},new String[]{"停止","进料","出料"}),
            option("phase","物相",port.phase().name(),new String[]{"LIQUID","SOLID","GAS"},new String[]{"液体","颗粒","气体"}),
            text("rate","速率 · L/s",Double.toString(port.nanosPerSecond()/1e6)),
            bool("redstone","红石控制",port.redstone())),
            command(port.getBlockPos())+"port $(mode) $(phase) $(rate) $(redstone)");
    }
    static void sensor(ServerPlayer player,ProcessSensorBlockEntity sensor){
        var first=sensor.first()==null?sensor.getBlockPos().relative(sensor.getBlockState().getValue(ProcessSensorBlock.FACING).getOpposite()):sensor.first();
        var second=sensor.second()==null?first:sensor.second();
        var measures=mmm.api.sensor.ProcessSensorSnapshot.Measure.values();var values=new String[measures.length];var labels=new String[measures.length];
        for(int i=0;i<measures.length;i++){values[i]=measures[i].name();labels[i]=measures[i].label();}
        show(player,"传感器 · "+sensor.snapshot().display(),List.of(
            option("measure","读数",sensor.measure().name(),values,labels),
            text("low","下限",Double.toString(sensor.low())),text("high","上限",Double.toString(sensor.high())),
            bool("below","低于阈值输出",sensor.below()),bool("analog","连续输出 0—15",sensor.analog()),
            text("first","起点 · X Y Z",coordinates(first)),text("second","终点 · X Y Z",coordinates(second))),
            command(sensor.getBlockPos())+"sensor $(measure) $(low) $(high) $(below) $(analog) $(first) $(second)");
    }
    static void valve(ServerPlayer player,BlockPos position,int opening){
        boolean pipe=player.level().getBlockState(position).is(MmmBlocks.PIPE_VALVE);
        var values=pipe?new String[]{"0","1","2"}:new String[]{"0","1","2","3","4"};
        var labels=pipe?new String[]{"关闭","半开","全开"}:new String[]{"关闭","¼","½","¾","全开"};
        show(player,pipe?"管道阀门":"阀门",List.of(option("opening","开度",Integer.toString(opening),values,labels)),command(position)+"valve $(opening)");
    }
    static void screen(ServerPlayer player,ScreenBlockEntity screen,double depositMass){
        show(player,"筛 · 滤渣 "+String.format(java.util.Locale.ROOT,"%.3g kg",depositMass),List.of(text("pore","孔径 · mm",Double.toString(screen.poreDiameter()*1000)),text("open","开放率 · 0—1",Double.toString(screen.openFraction()))),command(screen.getBlockPos())+"screen $(pore) $(open)");
    }
    private static String command(BlockPos pos){return "mmm industrial "+coordinates(pos)+" ";}
    private static String coordinates(BlockPos pos){return pos.getX()+" "+pos.getY()+" "+pos.getZ();}
    private static Input text(String key,String label,String value){return new Input(key,new TextInput(240,Component.literal(label),true,value,80,Optional.empty()));}
    private static Input bool(String key,String label,boolean value){return new Input(key,new BooleanInput(Component.literal(label),value,"true","false"));}
    private static Input option(String key,String label,String current,String[] values,String[] labels){
        var entries=new java.util.ArrayList<SingleOptionInput.Entry>();
        for(int i=0;i<values.length;i++)entries.add(new SingleOptionInput.Entry(values[i],Optional.of(Component.literal(labels[i])),values[i].equals(current)));
        return new Input(key,new SingleOptionInput(240,entries,Component.literal(label),true));
    }
    private static void show(ServerPlayer player,String title,List<Input> inputs,String command){
        var template=ParsedTemplate.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,new com.google.gson.JsonPrimitive(command)).getOrThrow();
        var common=new CommonDialogData(Component.literal(title),Optional.empty(),true,false,DialogAction.CLOSE,List.of(),inputs);
        player.openDialog(Holder.direct(new NoticeDialog(common,new ActionButton(new CommonButtonData(Component.literal("应用"),240),Optional.of(new CommandTemplate(template))))));
    }
}
