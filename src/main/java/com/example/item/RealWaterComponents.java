package com.example.item;

import com.example.RealWaterMod;
import com.mojang.serialization.Codec;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;

/**
 * Real Water item data components. The container truth is the exact long nanovolume quanta held
 * by {@code realwater:water_volume}; a partial bucket always carries a value strictly between
 * empty and one bucket, and out-of-range values are rejected instead of guessed.
 */
public final class RealWaterComponents {
	/** Exact pure-water volume in nanovolume quanta; strictly between empty and one bucket. */
	public static final DataComponentType<Long> WATER_VOLUME = register("water_volume");
	/** Exact material-container volume in nanovolume quanta; between empty and one bucket. */
	public static final DataComponentType<Long> FLUID_VOLUME = register("fluid_volume");
	/** Stable material id carried by a Real Water material container. */
	public static final DataComponentType<String> MATERIAL_ID = registerString("material_id");

	private RealWaterComponents() { }

	public static void register() {
		// Loading this class performs the static registration.
	}

	private static DataComponentType<Long> register(String path) {
		ResourceKey<DataComponentType<?>> key = ResourceKey.create(Registries.DATA_COMPONENT_TYPE, RealWaterMod.id(path));
		return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, key,
				DataComponentType.<Long>builder().persistent(Codec.LONG).networkSynchronized(ByteBufCodecs.VAR_LONG).build());
	}

	private static DataComponentType<String> registerString(String path) {
		ResourceKey<DataComponentType<?>> key = ResourceKey.create(Registries.DATA_COMPONENT_TYPE, RealWaterMod.id(path));
		return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, key,
				DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());
	}
}
