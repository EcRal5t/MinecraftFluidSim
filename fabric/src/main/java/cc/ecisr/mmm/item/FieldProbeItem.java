package cc.ecisr.mmm.item;

import cc.ecisr.mmm.api.query.FluidCellObservation;
import cc.ecisr.mmm.api.query.FluidQueryResult;
import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.simulation.ShellFluidQuery;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Read-only, server-authored observation tool over the public Query contract. */
public final class FieldProbeItem extends Item {
	private static final int MAX_MATERIALS = 4;
	private static final int MAX_SPECIES = 4;

	public FieldProbeItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (context.getLevel().isClientSide()) return InteractionResult.SUCCESS;
		if (!(context.getLevel() instanceof ServerLevel level)
				|| !(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
		if (!MmmBlocks.isBoundary(level.getBlockState(context.getClickedPos()))) {
			player.sendSystemMessage(Component.translatable("message.mmm.probe.not_shell"));
			return InteractionResult.FAIL;
		}
		FluidQueryResult result = ShellFluidQuery.query(level, context.getClickedPos(),
				context.getClickedFace(), context.getClickLocation());
		player.sendSystemMessage(present(result));
		return InteractionResult.SUCCESS_SERVER;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context,
			net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
			java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("item.mmm.field_probe.tooltip").withStyle(ChatFormatting.GRAY));
	}

	private static Component present(FluidQueryResult result) {
		if (result.observation() == null) {
			return result.availabilityReason() == null
					? Component.translatable("message.mmm.probe.failed", result.status().name())
					: Component.translatable("message.mmm.probe.unavailable",
							result.status().name(), result.availabilityReason().name());
		}
		FluidCellObservation observation = result.observation();
		MutableComponent text = Component.translatable("message.mmm.probe.header",
				observation.regionId().toString().substring(0, 8), observation.regionNetworkGeneration(),
				observation.cellX(), observation.cellY(), observation.cellZ());
		text.append("\n").append(Component.translatable("message.mmm.probe.liquid",
				decimal(observation.liquidVolumeCubicMetres() * 1_000.0),
				decimal(observation.liquidMassKilograms()), decimal(observation.densityKilogramsPerCubicMetre())));
		if (observation.temperatureKelvin().isPresent()) {
			text.append("\n").append(Component.translatable("message.mmm.probe.temperature",
					decimal(observation.temperatureKelvin().getAsDouble())));
		}
		appendMaterials(text, observation.materials());
		appendSpecies(text, observation.species());
		return text;
	}

	private static void appendMaterials(MutableComponent text, List<FluidCellObservation.MaterialAmount> materials) {
		List<FluidCellObservation.MaterialAmount> sorted = materials.stream()
				.sorted(Comparator.comparingDouble(FluidCellObservation.MaterialAmount::volumeCubicMetres).reversed()
						.thenComparing(FluidCellObservation.MaterialAmount::materialId))
				.toList();
		for (FluidCellObservation.MaterialAmount material : sorted.stream().limit(MAX_MATERIALS).toList()) {
			text.append("\n").append(Component.translatable("message.mmm.probe.material", material.materialId(),
					decimal(material.volumeCubicMetres() * 1_000.0), decimal(material.massKilograms())));
		}
		appendHidden(text, sorted.size() - MAX_MATERIALS);
	}

	private static void appendSpecies(MutableComponent text, List<FluidCellObservation.SpeciesAmount> species) {
		List<FluidCellObservation.SpeciesAmount> sorted = species.stream()
				.sorted(Comparator.comparingLong(FluidCellObservation.SpeciesAmount::amountQuanta).reversed()
						.thenComparing(FluidCellObservation.SpeciesAmount::speciesId))
				.toList();
		for (FluidCellObservation.SpeciesAmount amount : sorted.stream().limit(MAX_SPECIES).toList()) {
			text.append("\n").append(Component.translatable("message.mmm.probe.species",
					amount.speciesId(), amount.amountQuanta()));
		}
		appendHidden(text, sorted.size() - MAX_SPECIES);
	}

	private static void appendHidden(MutableComponent text, int hidden) {
		if (hidden > 0) text.append("\n").append(Component.translatable("message.mmm.probe.hidden", hidden));
	}

	private static String decimal(double value) {
		return String.format(Locale.ROOT, "%.6g", value);
	}
}
