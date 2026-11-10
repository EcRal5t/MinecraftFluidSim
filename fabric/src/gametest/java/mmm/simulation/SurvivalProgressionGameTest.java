package mmm.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mmm.block.MaterialBoxBlockEntity;
import mmm.block.MmmBlocks;
import mmm.item.MmmComponents;
import mmm.item.MmmItems;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/** Exercises the loaded recipes, their inventory triggers and real box containers. */
public final class SurvivalProgressionGameTest {
    private record Craft(String name, String unlock, String pattern, Map<Character,String> ingredients) { }
    private static final List<Craft> CRAFTS = List.of(
        new Craft("quick_start_guide", "minecraft:book", "BG       ", Map.of('B',"minecraft:book",'G',"minecraft:glass_pane")),
        new Craft("field_probe", "minecraft:compass", " G ICI R ", Map.of('G',"minecraft:glass_pane",'I',"minecraft:iron_ingot",'C',"minecraft:compass",'R',"minecraft:redstone")),
        new Craft("material_bucket", "minecraft:bucket", "BL       ", Map.of('B',"minecraft:bucket",'L',"minecraft:lapis_lazuli")),
        new Craft("granular_container", "minecraft:bucket", "BF       ", Map.of('B',"minecraft:bucket",'F',"minecraft:flint")),
        new Craft("gas_container", "minecraft:iron_ingot", " I I IIII", Map.of('I',"minecraft:iron_ingot")),
        new Craft("insulation", "minecraft:slime_ball", "WWWWSWWWW", Map.of('W',"minecraft:white_wool",'S',"minecraft:slime_ball")),
        new Craft("insulating_glass", "mmm:insulation", "IGIGGGIGI", Map.of('I',"mmm:insulation",'G',"minecraft:glass")),
        new Craft("insulated_glass_pipe", "mmm:insulating_glass", "G GG GG G", Map.of('G',"mmm:insulating_glass")),
        new Craft("fluid_port", "minecraft:iron_block", " I LBL I ", Map.of('I',"minecraft:iron_ingot",'L',"minecraft:lapis_lazuli",'B',"minecraft:iron_block")),
        new Craft("material_box", "minecraft:barrel", "IIIIBIIII", Map.of('I',"minecraft:iron_ingot",'B',"minecraft:barrel")),
        new Craft("process_sensor", "minecraft:comparator", "IRIICIIII", Map.of('I',"minecraft:iron_ingot",'R',"minecraft:redstone",'C',"minecraft:comparator")),
        new Craft("valve", "minecraft:piston", "IIIIP IRI".replace(" ","I"), Map.of('I',"minecraft:iron_ingot",'P',"minecraft:piston",'R',"minecraft:redstone")),
        new Craft("screen", "minecraft:iron_bars", "IIIBBBIII", Map.of('I',"minecraft:iron_ingot",'B',"minecraft:iron_bars")),
        new Craft("mechanical_driver", "minecraft:piston", " I IPI R ", Map.of('I',"minecraft:iron_ingot",'P',"minecraft:piston",'R',"minecraft:redstone")),
        new Craft("co2_adsorbent", "minecraft:charcoal", "AC       ", Map.of('A',"minecraft:charcoal",'C',"minecraft:clay_ball")),
        new Craft("hydration_catalyst", "minecraft:copper_ingot", "IC       ", Map.of('I',"minecraft:copper_ingot",'C',"minecraft:clay_ball"))
    );
    @GameTest(structure="mmm_gametest:piston",maxTicks=100)
    public void loadedRecipesAndUnlocks(GameTestHelper test) {
        var level=test.getLevel(); var server=level.getServer(); var player=test.makeMockServerPlayerInLevel();
        player.getAdvancements().reload(server.getAdvancements());
        for(var craft:CRAFTS) {
            var key=ResourceKey.create(Registries.RECIPE,Identifier.fromNamespaceAndPath("mmm",craft.name()));
            var holder=server.getRecipeManager().byKey(key).orElseThrow(() -> new AssertionError("Recipe failed to load: "+key));
            var grid=new ArrayList<ItemStack>();
            for(char symbol:craft.pattern().toCharArray()) grid.add(symbol==' ' ? ItemStack.EMPTY : new ItemStack(item(craft.ingredients().get(symbol))));
            var input=CraftingInput.of(3,3,grid);
            var matched=server.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,input,level).orElseThrow();
            test.assertTrue(matched.id().equals(key),"Recipe collides or is unreachable: "+key+" matched "+matched.id());
            test.assertTrue(matched.value().assemble(input).is(item("mmm:"+craft.name())),"Recipe output differs: "+key);
            var advancement=server.getAdvancements().get(Identifier.fromNamespaceAndPath("mmm","recipes/"+craft.name()));
            test.assertTrue(advancement!=null && advancement.value().rewards().recipes().contains(key),"Recipe unlock failed to load: "+key);
            var ingredient=new ItemStack(item(craft.unlock()));
            player.getInventory().clearContent(); player.getInventory().add(ingredient.copy());
            CriteriaTriggers.INVENTORY_CHANGED.trigger(player,player.getInventory(),ingredient);
            test.assertTrue(player.getRecipeBook().contains(key),"Inventory trigger did not unlock "+key);
        }
        test.succeed();
    }
    @GameTest(structure="mmm_gametest:piston",maxTicks=100)
    public void boxRetainsRealContainers(GameTestHelper test) {
        var level=test.getLevel(); var runtime=MmmRuntimeRegistry.require(level.getServer());
        var relative=new BlockPos(1,2,2); test.setBlock(relative,MmmBlocks.MATERIAL_BOX);
        var position=test.absolutePos(relative); var box=test.getBlockEntity(relative,MaterialBoxBlockEntity.class);
        var player=test.makeMockServerPlayerInLevel();
        test.assertTrue(box.isEmpty(),"New material box has imaginary containers");
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(MmmItems.MATERIAL_BUCKET));
        runtime.industrial().useBox(player,InteractionHand.MAIN_HAND,position);
        test.assertTrue(player.getMainHandItem().isEmpty() && box.getItem(0).is(MmmItems.MATERIAL_BUCKET),"Empty container was not placed in empty slot");
        var restored=(MaterialBoxBlockEntity)BlockEntity.loadStatic(position,level.getBlockState(position),box.saveWithFullMetadata(level.registryAccess()),level.registryAccess());
        test.assertTrue(restored!=null && restored.getItem(0).is(MmmItems.MATERIAL_BUCKET) && restored.getItem(1).isEmpty(),"Real and empty slots did not survive saving");
        runtime.industrial().useBox(player,InteractionHand.MAIN_HAND,position);
        test.assertTrue(player.getMainHandItem().is(MmmItems.MATERIAL_BUCKET) && box.getItem(0).isEmpty()
                && player.getMainHandItem().get(MmmComponents.TRANSFER_ACTOR)==null,"Empty container did not return cleanly");
        player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.WATER_BUCKET));
        runtime.industrial().useBox(player,InteractionHand.MAIN_HAND,position);
        box.setItem(1,MaterialBoxBlockEntity.newBatch(new ItemStack(MmmItems.GAS_CONTAINER)));
        test.setBlock(relative,Blocks.AIR);
        var dropped=level.getEntitiesOfClass(ItemEntity.class,new AABB(position).inflate(2));
        test.assertTrue(dropped.size()==2,"Breaking box changed its container count: "+dropped.size());
        test.assertTrue(dropped.stream().anyMatch(e->e.getItem().is(Items.WATER_BUCKET))
                && dropped.stream().anyMatch(e->e.getItem().is(MmmItems.GAS_CONTAINER))
                && dropped.stream().noneMatch(e->e.getItem().get(MmmComponents.TRANSFER_ACTOR)!=null),"Breaking box lost or changed actual inventory");
        test.succeed();
    }
    @GameTest(structure="mmm_gametest:piston",maxTicks=100)
    public void survivalBlockDrops(GameTestHelper test) {
        var level=test.getLevel(); var player=test.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var relative=new BlockPos(1,2,2); var position=test.absolutePos(relative);
        for(var block:List.of(MmmBlocks.INSULATION,MmmBlocks.INSULATING_GLASS,MmmBlocks.INSULATED_GLASS_PIPE,MmmBlocks.FLUID_PORT,
                MmmBlocks.MATERIAL_BOX,MmmBlocks.PROCESS_SENSOR,MmmBlocks.VALVE,MmmBlocks.SCREEN,MmmBlocks.MECHANICAL_DRIVER)) {
            test.setBlock(relative,block);
            player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STONE_PICKAXE));
            test.assertTrue(player.gameMode.destroyBlock(position),"Could not mine "+block);
            var drops=level.getEntitiesOfClass(ItemEntity.class,new AABB(position).inflate(2));
            test.assertTrue(drops.size()==1 && drops.getFirst().getItem().is(block.asItem()),"Incorrect survival drop for "+block);
            drops.forEach(ItemEntity::discard);
        }
        test.succeed();
    }
    private static Item item(String name) {
        var id=Identifier.parse(name);
        if(!BuiltInRegistries.ITEM.containsKey(id)) throw new AssertionError("Missing item "+name);
        return BuiltInRegistries.ITEM.getValue(id);
    }
}
