package mmm.block;

import mmm.MmmMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.Set;
import java.util.function.Function;

/** Material blocks and simulation devices. */
public final class MmmBlocks {
	public static final Block INSULATION = register("insulation", Block::new);
	public static final Block INSULATING_GLASS = register("insulating_glass", Block::new);
	public static final Block FLUID_PORT = register("fluid_port", FluidPortBlock::new);
	public static final Block THERMAL_RESERVOIR = register("thermal_reservoir", ThermalReservoirBlock::new);
	public static final Block FINITE_HEAT_EXCHANGER = register("finite_heat_exchanger", FiniteHeatExchangerBlock::new);
	public static final Block MECHANICAL_DRIVER = register("mechanical_driver", MechanicalDriverBlock::new);
	public static final Block MATERIAL_BOX = register("material_box", MaterialBoxBlock::new);
	public static final Block VALVE = register("valve", ValveBlock::new);
	public static final Block PROCESS_SENSOR = register("process_sensor", ProcessSensorBlock::new);
	public static final Block SCREEN = register("screen", ScreenBlock::new);
	public static final BlockEntityType<ScreenBlockEntity> SCREEN_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,MmmMod.id("screen"),new BlockEntityType<>(ScreenBlockEntity::new,Set.of(SCREEN)));
	public static final BlockEntityType<ProcessSensorBlockEntity> PROCESS_SENSOR_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,MmmMod.id("process_sensor"),new BlockEntityType<>(ProcessSensorBlockEntity::new,Set.of(PROCESS_SENSOR)));
	public static final BlockEntityType<MaterialBoxBlockEntity> MATERIAL_BOX_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,MmmMod.id("material_box"),new BlockEntityType<>(MaterialBoxBlockEntity::new,Set.of(MATERIAL_BOX)));
	public static final BlockEntityType<FluidPortBlockEntity> FLUID_PORT_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,MmmMod.id("fluid_port"),new BlockEntityType<>(FluidPortBlockEntity::new,Set.of(FLUID_PORT)));
	public static final BlockEntityType<FiniteHeatExchangerBlockEntity> FINITE_HEAT_EXCHANGER_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE, MmmMod.id("finite_heat_exchanger"),
			new BlockEntityType<>(FiniteHeatExchangerBlockEntity::new, Set.of(FINITE_HEAT_EXCHANGER)));
	public static final BlockEntityType<ThermalReservoirBlockEntity> THERMAL_RESERVOIR_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			MmmMod.id("thermal_reservoir"),
			new BlockEntityType<>(ThermalReservoirBlockEntity::new, Set.of(THERMAL_RESERVOIR))
	);

	private MmmBlocks() { }

	public static void register() {
		// Loading this class performs the static block, item, and block-entity registrations.
	}


	private static Block register(String path, Function<BlockBehaviour.Properties, Block> factory) {
		Identifier identifier = MmmMod.id(path);
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, identifier);
		BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
				.mapColor(MapColor.METAL).noOcclusion().strength(5.0F, 6.0F).sound(SoundType.METAL).setId(blockKey);
		if (!path.equals("insulation") && !path.equals("insulating_glass")) properties.requiresCorrectToolForDrops();
		Block block = Registry.register(
				BuiltInRegistries.BLOCK,
				blockKey,
				factory.apply(properties)
		);
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, identifier);
		Item.Properties itemProperties = new Item.Properties().setId(itemKey).useBlockDescriptionPrefix();
		Registry.register(
				BuiltInRegistries.ITEM,
				itemKey,
				block instanceof FiniteHeatExchangerBlock ? new FiniteHeatExchangerBlockItem(block, itemProperties.stacksTo(1))
						: new MmmBlockItem(block, itemProperties)
		);
		return block;
	}
}
