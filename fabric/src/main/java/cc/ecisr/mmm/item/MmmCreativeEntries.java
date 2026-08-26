package cc.ecisr.mmm.item;

import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.transfer.ContainerFluidLedger;
import cc.ecisr.mmm.transfer.CreativeSourceDescriptor;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;

/** One ordered product catalog containing only directly usable content. */
public final class MmmCreativeEntries {
	private MmmCreativeEntries() { }

	public static void register() {
		Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, cc.ecisr.mmm.MmmMod.id("main"),
				FabricCreativeModeTab.builder()
						.title(Component.translatable("itemGroup.mmm.main"))
						.icon(() -> MmmBlocks.FLUID_CONTROLLER.asItem().getDefaultInstance())
						.displayItems((parameters, entries) -> addEntries(entries))
						.build());
	}

	private static void addEntries(CreativeModeTab.Output entries) {
		entries.accept(MmmItems.QUICK_START_GUIDE);
		entries.accept(MmmBlocks.FLUID_BOUNDARY);
		entries.accept(MmmBlocks.FLUID_CONTROLLER);
		entries.accept(MmmBlocks.THERMAL_RESERVOIR);
		entries.accept(MmmItems.FIELD_PROBE);
		entries.accept(MmmItems.MATERIAL_BUCKET);
		entries.accept(MmmItems.GRANULAR_CONTAINER);
		for (String id : java.util.List.of(
					"mmm:source/material/water", "mmm:source/material/oil", "mmm:source/material/mercury",
					"mmm:source/material/water_oil", "mmm:source/reagent/water", "mmm:source/reagent/hcl",
					"mmm:source/reagent/naoh", "mmm:source/reagent/nacl", "mmm:source/reagent/nano3",
					"mmm:source/reagent/kcl", "mmm:source/reagent/kno3")) {
			entries.accept(CreativeSourceItem.stack(new CreativeSourceDescriptor(
						id, ContainerFluidLedger.NANOS_PER_BLOCK, 293.15)));
		}
		for (String id : java.util.List.of(
					"mmm:source/granular/fine_sand", "mmm:source/granular/light_beads")) {
			entries.accept(CreativeSourceItem.stack(new CreativeSourceDescriptor(id, SolidVolume.SVQ_PER_BV, null)));
		}
		entries.accept(MmmItems.CHEMICAL_INDICATOR);
		entries.accept(MmmItems.CHEMICAL_ERASER);
	}
}
