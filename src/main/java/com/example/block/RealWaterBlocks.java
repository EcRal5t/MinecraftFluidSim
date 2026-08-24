package com.example.block;

import com.example.RealWaterMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.Set;
import java.util.function.Function;

/** Visible M2 controller shell blocks. Registration does not enable controller activation. */
public final class RealWaterBlocks {
	public static final Block FLUID_BOUNDARY = register("fluid_boundary", Block::new);
	public static final Block FLUID_CONTROLLER = register("fluid_controller", FluidControllerBlock::new);
	public static final Block THERMAL_RESERVOIR = register("thermal_reservoir", ThermalReservoirBlock::new);
	public static final BlockEntityType<ThermalReservoirBlockEntity> THERMAL_RESERVOIR_ENTITY = Registry.register(
			BuiltInRegistries.BLOCK_ENTITY_TYPE,
			RealWaterMod.id("thermal_reservoir"),
			new BlockEntityType<>(ThermalReservoirBlockEntity::new, Set.of(THERMAL_RESERVOIR))
	);

	private RealWaterBlocks() { }

	public static void register() {
		// Loading this class performs the static block, item, and block-entity registrations.
	}

	public static boolean isBoundary(BlockState state) {
		return state.is(FLUID_BOUNDARY) || state.is(FLUID_CONTROLLER) || state.is(THERMAL_RESERVOIR);
	}

	private static Block register(String path, Function<BlockBehaviour.Properties, Block> factory) {
		Identifier identifier = RealWaterMod.id(path);
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, identifier);
		Block block = Registry.register(
				BuiltInRegistries.BLOCK,
				blockKey,
				factory.apply(BlockBehaviour.Properties.of()
						.mapColor(MapColor.METAL)
						.noOcclusion()
						.requiresCorrectToolForDrops()
						.strength(5.0F, 6.0F)
						.sound(SoundType.METAL)
						.setId(blockKey))
		);
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, identifier);
		Registry.register(
				BuiltInRegistries.ITEM,
				itemKey,
				new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix())
		);
		return block;
	}
}
