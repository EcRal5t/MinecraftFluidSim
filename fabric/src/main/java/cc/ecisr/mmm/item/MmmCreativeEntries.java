package cc.ecisr.mmm.item;

import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.transfer.ContainerFluidLedger;
import cc.ecisr.mmm.transfer.CreativeSourceDescriptor;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.world.item.CreativeModeTabs;

/** Places only directly usable content in vanilla creative categories. */
public final class MmmCreativeEntries {
	private MmmCreativeEntries() { }

	public static void register() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS)
				.register(entries -> entries.accept(MmmBlocks.FLUID_BOUNDARY));
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> {
			entries.accept(MmmBlocks.FLUID_CONTROLLER);
			entries.accept(MmmBlocks.THERMAL_RESERVOIR);
		});
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.INGREDIENTS).register(entries -> {
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
		});
	}
}
