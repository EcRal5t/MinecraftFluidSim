package mmm.block;

import mmm.item.MmmComponents;
import mmm.simulation.FiniteHeatExchangerOperations;
import mmm.thermal.FiniteThermalDeviceItemCodec;
import mmm.thermal.FiniteThermalDeviceState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;

/** Installs a complete finite body through the region/world/item transaction. */
public final class FiniteHeatExchangerBlockItem extends BlockItem {
	FiniteHeatExchangerBlockItem(Block block, Properties properties) { super(block, properties); }

	@Override public InteractionResult useOn(UseOnContext context) {
		return FiniteHeatExchangerOperations.place(context);
	}

	public static ItemStack createStack(FiniteThermalDeviceState state, String worldProfileFingerprint) {
		if (worldProfileFingerprint == null || !worldProfileFingerprint.matches("[0-9a-f]{64}"))
			throw new IllegalArgumentException("Finite heat exchanger item requires a world capability profile");
		var stack = new ItemStack(MmmBlocks.FINITE_HEAT_EXCHANGER);
		stack.set(MmmComponents.FINITE_THERMAL_DEVICE, FiniteThermalDeviceItemCodec.encode(state));
		stack.set(MmmComponents.PROFILE_FINGERPRINT, worldProfileFingerprint);
		return stack;
	}

	@Override public void appendHoverText(ItemStack stack, TooltipContext context,
			net.minecraft.world.item.component.TooltipDisplay display,
			java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable(getDescriptionId() + ".tooltip").withStyle(ChatFormatting.GRAY));
		String encoded = stack.get(MmmComponents.FINITE_THERMAL_DEVICE);
		if (encoded == null) return;
		var body = FiniteThermalDeviceItemCodec.decode(encoded).body();
		tooltip.accept(Component.translatable("item.mmm.finite_heat_exchanger.inventory",
				mmm.units.Mass.kilograms(body.massUnits()), mmm.units.UnitFormat.energy(body.internalEnergyJ())).withStyle(ChatFormatting.GRAY));
	}
}
