package cc.ecisr.mmm.item;

import cc.ecisr.mmm.api.query.FluidCellObservation;
import cc.ecisr.mmm.api.query.FluidQueryResult;
import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.fluid.CellCarrierInventoryView;
import cc.ecisr.mmm.simulation.FluidSimulationService;
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
	private static final int MAX_SOLIDS = 4;
	private static final int MAX_GAS_COMPONENTS = 4;

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
		ShellFluidQuery.Resolution resolution = ShellFluidQuery.resolve(level, context.getClickedPos(),
				context.getClickedFace(), context.getClickLocation());
		FluidSimulationService service = FluidSimulationService.get(level.getServer());
		net.minecraft.world.phys.Vec3 point = resolution.interiorPoint();
		FluidQueryResult liquid = resolution.result();
		if (player.isShiftKeyDown() && point != null) {
			point = service.firstCondensedProbePoint(level, point, player.getLookAngle());
			if (point == null) {
				player.sendSystemMessage(Component.translatable("message.mmm.probe.no_condensed"));
				return InteractionResult.FAIL;
			}
			liquid = ShellFluidQuery.queryPoint(level, point);
		}
		FluidSimulationService.CarrierCellObservation carriers = point == null ? null
				: service.observeCellCarriers(level, point);
		player.sendSystemMessage(present(liquid, carriers));
		return InteractionResult.SUCCESS_SERVER;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context,
			net.minecraft.world.item.component.TooltipDisplay tooltipDisplay,
			java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
		tooltip.accept(Component.translatable("item.mmm.field_probe.tooltip").withStyle(ChatFormatting.GRAY));
		tooltip.accept(Component.translatable("item.mmm.field_probe.tooltip_scan").withStyle(ChatFormatting.DARK_GRAY));
	}

	private static Component present(
			FluidQueryResult result, FluidSimulationService.CarrierCellObservation carrierCell
	) {
		if (result.observation() == null && carrierCell == null) {
			return result.availabilityReason() == null
					? Component.translatable("message.mmm.probe.failed", result.status().name())
					: Component.translatable("message.mmm.probe.unavailable",
							result.status().name(), result.availabilityReason().name());
		}
		FluidCellObservation observation = result.observation();
		if (observation != null && carrierCell != null && (!observation.regionId().equals(carrierCell.regionId())
				|| observation.regionNetworkGeneration() != carrierCell.regionNetworkGeneration()
				|| observation.cellX() != carrierCell.cellX() || observation.cellY() != carrierCell.cellY()
				|| observation.cellZ() != carrierCell.cellZ())) {
			return Component.translatable("message.mmm.probe.inconsistent");
		}
		MutableComponent text = Component.translatable("message.mmm.probe.header",
				regionId(observation, carrierCell).toString().substring(0, 8), generation(observation, carrierCell),
				cellX(observation, carrierCell), cellY(observation, carrierCell), cellZ(observation, carrierCell));
		if (observation != null) {
			text.append("\n").append(Component.translatable("message.mmm.probe.liquid",
					decimal(observation.liquidVolumeCubicMetres() * 1_000.0),
					decimal(observation.liquidMassKilograms()), decimal(observation.densityKilogramsPerCubicMetre())));
			if (observation.temperatureKelvin().isPresent()) {
				text.append("\n").append(Component.translatable("message.mmm.probe.temperature",
						decimal(observation.temperatureKelvin().getAsDouble())));
			}
			appendMaterials(text, observation.materials());
			appendSpecies(text, observation.species());
		} else if (carrierCell.carriers() == null) {
			text.append("\n").append(Component.translatable("message.mmm.probe.empty"));
		}
		if (carrierCell != null && carrierCell.carriers() != null) appendCarriers(text, carrierCell.carriers());
		return text;
	}

	private static java.util.UUID regionId(
			FluidCellObservation liquid, FluidSimulationService.CarrierCellObservation carriers
	) { return liquid != null ? liquid.regionId() : carriers.regionId(); }

	private static long generation(
			FluidCellObservation liquid, FluidSimulationService.CarrierCellObservation carriers
	) { return liquid != null ? liquid.regionNetworkGeneration() : carriers.regionNetworkGeneration(); }

	private static int cellX(FluidCellObservation liquid, FluidSimulationService.CarrierCellObservation carriers) {
		return liquid != null ? liquid.cellX() : carriers.cellX();
	}

	private static int cellY(FluidCellObservation liquid, FluidSimulationService.CarrierCellObservation carriers) {
		return liquid != null ? liquid.cellY() : carriers.cellY();
	}

	private static int cellZ(FluidCellObservation liquid, FluidSimulationService.CarrierCellObservation carriers) {
		return liquid != null ? liquid.cellZ() : carriers.cellZ();
	}

	private static void appendCarriers(MutableComponent text, CellCarrierInventoryView carriers) {
		List<CellCarrierInventoryView.SolidAmount> solids = carriers.solids().stream()
				.sorted(Comparator.comparingLong(CellCarrierInventoryView.SolidAmount::solidVolumeSvq).reversed()
						.thenComparing(value -> value.kind().name()).thenComparing(CellCarrierInventoryView.SolidAmount::stableId))
				.toList();
		for (CellCarrierInventoryView.SolidAmount solid : solids.stream().limit(MAX_SOLIDS).toList()) {
			text.append("\n").append(Component.translatable("message.mmm.probe.solid", solid.kind().name(),
					solid.stableId(), solid.solidVolumeSvq(), decimal(solid.massKilograms())));
		}
		appendHidden(text, solids.size() - MAX_SOLIDS);
		CellCarrierInventoryView.GasAmount gas = carriers.gas();
		if (gas == null) return;
		text.append("\n").append(Component.translatable(gas.ambient()
					? "message.mmm.probe.gas_ambient" : "message.mmm.probe.gas_sealed",
				gas.nodeId(), decimal(gas.absolutePressurePascal()), decimal(gas.temperatureKelvin())));
		List<CellCarrierInventoryView.ComponentAmount> components = gas.components().stream()
				.sorted(Comparator.comparingLong(CellCarrierInventoryView.ComponentAmount::standardVolumeNanos).reversed()
						.thenComparing(CellCarrierInventoryView.ComponentAmount::componentId)).toList();
		for (CellCarrierInventoryView.ComponentAmount component
				: components.stream().limit(MAX_GAS_COMPONENTS).toList()) {
			text.append("\n").append(Component.translatable("message.mmm.probe.gas_component",
					component.componentId(), component.standardVolumeNanos(), decimal(component.massKilograms())));
		}
		appendHidden(text, components.size() - MAX_GAS_COMPONENTS);
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
