package mmm.simulation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import mmm.block.MmmBlocks;
import mmm.block.PipeBlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/** The transparent pipes share geometry while their thermal and visual materials differ. */
public final class PipeAssetsValidation {
    private PipeAssetsValidation() { }
    public static void run() throws Exception {
        GpuControllerFixture.initializeItems();GpuControllerFixture.initializeIndustrialBlocks();
        require(MmmBlocks.INSULATED_GLASS_PIPE instanceof PipeBlock
                && BuiltInRegistries.BLOCK.getKey(MmmBlocks.INSULATED_GLASS_PIPE).equals(Identifier.parse("mmm:insulated_glass_pipe")),"insulated glass pipe registration");
        for(String pipe:List.of("glass_pipe","insulated_glass_pipe")) {
            String sprite=pipe.equals("glass_pipe")?"minecraft:block/glass":"mmm:block/insulating_glass";
            var state=read("assets/mmm/blockstates/"+pipe+".json");
            var parts=state.getAsJsonArray("multipart");require(parts.size()==19,"pipe connection models");
            for(var part:parts) {
                String model=part.getAsJsonObject().getAsJsonObject("apply").get("model").getAsString();
                require(model.startsWith("mmm:block/"+pipe+"_"),"pipe blockstate references wrong model");
                var texture=read("assets/mmm/models/block/"+model.substring("mmm:block/".length())+".json")
                        .getAsJsonObject("textures").getAsJsonObject("side");
                require(sprite.equals(texture.get("sprite").getAsString()) && texture.get("force_translucent").getAsBoolean(),"pipe transparency or material");
            }
            var item=read("assets/mmm/items/"+pipe+".json").getAsJsonObject("model").get("model").getAsString();
            var texture=read("assets/mmm/models/block/"+item.substring("mmm:block/".length())+".json").getAsJsonObject("textures").getAsJsonObject("side");
            require(sprite.equals(texture.get("sprite").getAsString()) && texture.get("force_translucent").getAsBoolean(),"pipe item material");
        }
        validateModelGeometry();
        validateInlineDeviceModels();
        var recipe=read("data/mmm/recipe/insulated_glass_pipe.json");
        require(recipe.getAsJsonObject("result").get("id").getAsString().equals("mmm:insulated_glass_pipe")
                && recipe.getAsJsonObject("key").get("G").getAsString().equals("mmm:insulating_glass"),"insulated glass pipe recipe");
        var advancement=read("data/mmm/advancement/recipes/insulated_glass_pipe.json");
        require(advancement.getAsJsonObject("rewards").getAsJsonArray("recipes").get(0).getAsString().equals("mmm:insulated_glass_pipe")
                && advancement.toString().contains("mmm:insulating_glass"),"recipe unlock");
        require(read("data/mmm/loot_table/blocks/insulated_glass_pipe.json").toString().contains("mmm:insulated_glass_pipe"),"pipe loot");
        for(String tag:List.of("data/minecraft/tags/block/mineable/pickaxe.json","data/minecraft/tags/block/needs_stone_tool.json",
                "data/mmm/tags/block/ordinary_insulation_thermal_walls.json"))
            require(read(tag).getAsJsonArray("values").toString().contains("mmm:insulated_glass_pipe"),"pipe tag "+tag);
        require(!read("data/mmm/tags/block/ordinary_glass_thermal_walls.json").getAsJsonArray("values").toString().contains("mmm:insulated_glass_pipe"),
                "insulated glass assigned two thermal materials");
        for(String language:List.of("en_us","zh_cn")) {
            var text=read("assets/mmm/lang/"+language+".json");
            require(text.has("block.mmm.insulated_glass_pipe") && text.has("block.mmm.insulated_glass_pipe.tooltip"),"pipe language "+language);
        }
    }
    private static void validateModelGeometry() throws Exception {
        var types=List.of("metal_pipe","insulated_pipe","glass_pipe","insulated_glass_pipe");
        var blocks=List.of(MmmBlocks.METAL_PIPE,MmmBlocks.INSULATED_PIPE,MmmBlocks.GLASS_PIPE,MmmBlocks.INSULATED_GLASS_PIPE);
        for(int type=0;type<types.size();type++) {
            var parts=read("assets/mmm/blockstates/"+types.get(type)+".json").getAsJsonArray("multipart");
            var shapes=new java.util.HashMap<String,net.minecraft.world.phys.shapes.VoxelShape>();
            for(var part:parts) {
                var item=part.getAsJsonObject();String id=item.getAsJsonObject("apply").get("model").getAsString();
                var model=read("assets/mmm/models/block/"+id.substring("mmm:block/".length())+".json");
                var shape=net.minecraft.world.phys.shapes.Shapes.empty();
                for(var e:model.getAsJsonArray("elements")) {
                    var box=e.getAsJsonObject();var from=box.getAsJsonArray("from");var to=box.getAsJsonArray("to");
                    shape=net.minecraft.world.phys.shapes.Shapes.joinUnoptimized(shape,net.minecraft.world.level.block.Block.box(
                            from.get(0).getAsDouble(),from.get(1).getAsDouble(),from.get(2).getAsDouble(),
                            to.get(0).getAsDouble(),to.get(1).getAsDouble(),to.get(2).getAsDouble()),net.minecraft.world.phys.shapes.BooleanOp.OR);
                }
                shapes.put(id,shape.optimize());
            }
            for(int combination=0;combination<729;combination++) {
                int n=combination;var state=blocks.get(type).defaultBlockState();
                for(var face:net.minecraft.core.Direction.values()) {
                    int mode=n%3;n/=3;
                    state=state.setValue(PipeBlock.port(face),mode==0?PipeBlock.Port.SEALED:mode==1?PipeBlock.Port.NONE:PipeBlock.Port.OPEN);
                }
                var assembled=net.minecraft.world.phys.shapes.Shapes.empty();
                for(var part:parts) {
                    var item=part.getAsJsonObject();
                    if(item.has("when") && !matches(item.getAsJsonObject("when"),state))continue;
                    String id=item.getAsJsonObject("apply").get("model").getAsString();
                    assembled=net.minecraft.world.phys.shapes.Shapes.joinUnoptimized(assembled,shapes.get(id),net.minecraft.world.phys.shapes.BooleanOp.OR);
                }
                require(!net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(assembled,PipeBlock.collision(state),
                        net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME),"pipe model geometry differs for "+types.get(type)+" state "+combination);
            }
        }
    }
    private static void validateInlineDeviceModels() throws Exception {
        for(var face:List.of(net.minecraft.core.Direction.EAST,net.minecraft.core.Direction.UP,net.minecraft.core.Direction.SOUTH)) {
            String axis=face.getAxis().getName();
            var screen=MmmBlocks.PIPE_SCREEN.defaultBlockState().setValue(mmm.block.PipeScreenBlock.FACING,face);
            require(!net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(
                    modelShape("pipe_screen_"+axis+"_0"),mmm.block.PipeScreenBlock.collision(screen),
                    net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME),"pipe screen model geometry differs on "+axis);
            for(int opening=0;opening<=2;opening++) {
                var valve=MmmBlocks.PIPE_VALVE.defaultBlockState().setValue(mmm.block.PipeValveBlock.FACING,face)
                        .setValue(mmm.block.PipeValveBlock.OPENING,opening);
                require(!net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(
                        modelShape("pipe_valve_"+axis+"_"+opening),mmm.block.PipeValveBlock.collision(valve),
                        net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME),"pipe valve model geometry differs on "+axis+"/"+opening);
            }
        }
    }
    private static net.minecraft.world.phys.shapes.VoxelShape modelShape(String name) throws Exception {
        var model=read("assets/mmm/models/block/"+name+".json");var shape=net.minecraft.world.phys.shapes.Shapes.empty();
        for(var element:model.getAsJsonArray("elements")) {
            var box=element.getAsJsonObject();var from=box.getAsJsonArray("from");var to=box.getAsJsonArray("to");
            shape=net.minecraft.world.phys.shapes.Shapes.joinUnoptimized(shape,net.minecraft.world.level.block.Block.box(
                    from.get(0).getAsDouble(),from.get(1).getAsDouble(),from.get(2).getAsDouble(),
                    to.get(0).getAsDouble(),to.get(1).getAsDouble(),to.get(2).getAsDouble()),net.minecraft.world.phys.shapes.BooleanOp.OR);
        }
        return shape.optimize();
    }
    private static boolean matches(JsonObject when,net.minecraft.world.level.block.state.BlockState state) {
        if(when.has("OR")) {
            for(var alternative:when.getAsJsonArray("OR"))if(matches(alternative.getAsJsonObject(),state))return true;
            return false;
        }
        for(var entry:when.entrySet()) {
            var face=net.minecraft.core.Direction.byName(entry.getKey());
            if(face==null || !state.getValue(PipeBlock.port(face)).getSerializedName().equals(entry.getValue().getAsString()))return false;
        }
        return true;
    }
    private static JsonObject read(String path) throws Exception {
        var input=PipeAssetsValidation.class.getClassLoader().getResourceAsStream(path);
        if(input==null)throw new AssertionError("Missing resource "+path);
        try(var reader=new InputStreamReader(input,StandardCharsets.UTF_8)){return JsonParser.parseReader(reader).getAsJsonObject();}
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
