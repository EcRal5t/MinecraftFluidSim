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
import net.minecraft.world.level.material.MapColor;

/**
 * Disposable M6.0 route-A ownership prototypes. They carry no fluid logic; the smoke session only
 * observes how vanilla mechanics treat each placeholder shape. The whole class is deleted once the
 * ownership route decision is recorded.
 */
public final class RealWaterPrototypeBlocks {
	/** Solid baseline placeholder: full collision, occluding, like an inert stone block. */
	public static final Block FLUID_PROXY = register("fluid_proxy", BlockBehaviour.Properties.of()
			.mapColor(MapColor.COLOR_LIGHT_BLUE)
			.strength(0.5F)
			.sound(SoundType.GLASS)
			.noLootTable());

	/** Air-like placeholder: no collision and no occlusion, the closest block-level analogue of owned air. */
	public static final Block FLUID_PROXY_AIR = register("fluid_proxy_air", BlockBehaviour.Properties.of()
			.mapColor(MapColor.COLOR_LIGHT_BLUE)
			.strength(0.5F)
			.sound(SoundType.GLASS)
			.noCollision()
			.noOcclusion()
			.noLootTable());

	private RealWaterPrototypeBlocks() { }

	public static void register() {
		// Loading this class performs the two static registrations.
	}

	private static Block register(String path, BlockBehaviour.Properties properties) {
		Identifier identifier = RealWaterMod.id(path);
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, identifier);
		Block block = Registry.register(BuiltInRegistries.BLOCK, blockKey,
				new Block(properties.setId(blockKey)));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, identifier);
		Registry.register(BuiltInRegistries.ITEM, itemKey,
				new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		return block;
	}
}
