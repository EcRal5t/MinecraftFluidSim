package cc.ecisr.mmm.item;

import cc.ecisr.mmm.simulation.FluidSimulationService;
import cc.ecisr.mmm.simulation.MmmRuntimeRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** Reusable operator indicator/eraser tools; both edit only marker provenance. */
public final class ChemicalIndicatorItem extends Item {
	private final boolean eraser;

	public ChemicalIndicatorItem(Item.Properties properties, boolean eraser) {
		super(properties);
		this.eraser = eraser;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.SUCCESS;
		FluidSimulationService.IndicatorUseResult result = MmmRuntimeRegistry
				.require(serverPlayer.level().getServer()).simulation()
				.editIndicatorAtAim(serverPlayer, !eraser);
		if (!result.accepted()) {
			serverPlayer.sendSystemMessage(Component.translatable("message.mmm.indicator.failed", result.failure()), true);
			return InteractionResult.FAIL;
		}
		net.minecraft.network.chat.MutableComponent message = Component.translatable(
				eraser ? "message.mmm.indicator.erased" : "message.mmm.indicator.applied",
				String.format(java.util.Locale.ROOT, "%.3f", result.affectedBlockVolume()),
				result.changedMarkers(), result.scannedCells());
		if (result.checkpointWarning() != null) message.append(Component.translatable(
				"message.mmm.indicator.checkpoint_warning", result.checkpointWarning()));
		serverPlayer.sendSystemMessage(message, true);
		return InteractionResult.SUCCESS_SERVER;
	}

	@Override
	public void appendHoverText(
			ItemStack stack, TooltipContext context,
			net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
			java.util.function.Consumer<Component> tooltip, TooltipFlag flag
	) {
		tooltip.accept(Component.translatable(eraser
				? "item.mmm.chemical_eraser.tooltip" : "item.mmm.chemical_indicator.tooltip")
				.withStyle(ChatFormatting.GRAY));
	}
}
