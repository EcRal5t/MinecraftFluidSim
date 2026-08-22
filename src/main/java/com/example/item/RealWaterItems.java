package com.example.item;

import com.example.RealWaterMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * Real Water item registrations. Registration does not enable container gameplay: transfers are
 * gated by the world-bridge feature flag, and the partial bucket is obtainable only through the
 * transfer service (or /give for administrators).
 */
public final class RealWaterItems {
	public static final Item PARTIAL_WATER_BUCKET = register("partial_water_bucket",
			properties -> new PartialWaterBucketItem(properties.stacksTo(1).useItemDescriptionPrefix()));
	public static final Item MATERIAL_BUCKET = register("material_bucket",
			properties -> new MaterialBucketItem(properties.stacksTo(1).useItemDescriptionPrefix()));

	private RealWaterItems() { }

	public static void register() {
		// Loading this class performs the static registration.
	}

	private static Item register(String path, java.util.function.Function<Item.Properties, Item> factory) {
		Identifier identifier = RealWaterMod.id(path);
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, identifier);
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key)));
	}
}
