package mmm.item;

import mmm.thermal.*;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;

public final class SurfaceCoatingItem extends Item {
    private final String materialId;
    public SurfaceCoatingItem(Properties properties,String materialId){super(properties);this.materialId=materialId;}
    public String materialId(){return materialId;}
    @Override public InteractionResult useOn(UseOnContext context){
        if(context.getLevel().isClientSide())return InteractionResult.SUCCESS;
        if(!(context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player))return InteractionResult.PASS;
        return mmm.simulation.MmmRuntimeRegistry.require(player.level().getServer()).finiteHeatExchangers().surface(player,context.getHand(),context.getClickedPos(),context.getClickedFace(),false);
    }
    public static ItemStack stack(SurfaceCoatingContents contents){
        var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.stream()
            .filter(candidate->candidate instanceof SurfaceCoatingItem surface&&surface.materialId().equals(contents.surface().materialId()))
            .findFirst().orElseThrow(()->new IllegalArgumentException("Unknown surface material item "+contents.surface().materialId()));
        var stack=new ItemStack(item);stack.set(MmmComponents.SURFACE_COATING,contents.encode());return stack;
    }
    @Override public void appendHoverText(ItemStack stack,TooltipContext context,net.minecraft.world.item.component.TooltipDisplay display,java.util.function.Consumer<net.minecraft.network.chat.Component> tooltip,net.minecraft.world.item.TooltipFlag flag){
        String encoded=stack.get(MmmComponents.SURFACE_COATING);
        if(encoded!=null){
            var contents=SurfaceCoatingContents.decode(encoded);
            tooltip.accept(net.minecraft.network.chat.Component.literal(mmm.units.Mass.kilograms(contents.surface().loadingMassUnits())+" kg"));
            contents.surface().speciesQuanta().forEach((species,quanta)->tooltip.accept(net.minecraft.network.chat.Component.literal(species+" · "+(quanta/(double)mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL)+" mol")));
        }
    }
}
