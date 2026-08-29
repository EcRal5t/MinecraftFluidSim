package cc.ecisr.mmm.item;

import cc.ecisr.mmm.MmmMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * Matter, Motion & Mechanics item registrations. Actual container transfers remain server-authoritative;
 * the world-bridge flag only governs ownership of pre-existing vanilla world fluid.
 */
public final class MmmItems {
	public static final Item QUICK_START_GUIDE = register("quick_start_guide",
			properties -> new QuickStartGuideItem(properties.stacksTo(1).useItemDescriptionPrefix()));
	public static final Item PARTIAL_WATER_BUCKET = register("partial_water_bucket",
			properties -> new PartialWaterBucketItem(properties.stacksTo(1).useItemDescriptionPrefix()));
	public static final Item MATERIAL_BUCKET = register("material_bucket",
			properties -> new MaterialBucketItem(properties.stacksTo(1).useItemDescriptionPrefix()));
	public static final Item GRANULAR_CONTAINER = register("granular_container",
			properties -> new GranularContainerItem(properties.stacksTo(1).useItemDescriptionPrefix()));
	public static final Item CREATIVE_SOURCE = register("creative_source",
			properties -> new CreativeSourceItem(properties.stacksTo(1).useItemDescriptionPrefix()));
	public static final Item FIELD_PROBE = register("field_probe",
			properties -> new FieldProbeItem(properties.stacksTo(1).useItemDescriptionPrefix()));
	public static final Item CHEMICAL_INDICATOR = register("chemical_indicator",
			properties -> new ChemicalIndicatorItem(properties.stacksTo(1).useItemDescriptionPrefix(), false));
	public static final Item CHEMICAL_ERASER = register("chemical_eraser",
			properties -> new ChemicalIndicatorItem(properties.stacksTo(1).useItemDescriptionPrefix(), true));

	private MmmItems() { }

	public static void register() {
		// Loading this class performs the static registration.
	}

	private static Item register(String path, java.util.function.Function<Item.Properties, Item> factory) {
		Identifier identifier = MmmMod.id(path);
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, identifier);
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key)));
	}
}
