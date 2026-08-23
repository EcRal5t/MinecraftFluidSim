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
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.function.Function;

/** Visible M2 controller shell blocks. Registration does not enable controller activation. */
public final class RealWaterBlocks {
	public static final Block FLUID_BOUNDARY = register("fluid_boundary", Block::new);
	public static final Block FLUID_CONTROLLER = register("fluid_controller", FluidControllerBlock::new);

	private RealWaterBlocks() { }

	public static void register() {
		// Loading this class performs the two static registrations.
	}

	public static boolean isBoundary(BlockState state) {
		return state.is(FLUID_BOUNDARY) || state.is(FLUID_CONTROLLER);
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
