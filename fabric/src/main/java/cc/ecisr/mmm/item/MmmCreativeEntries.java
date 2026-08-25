package cc.ecisr.mmm.item;

import cc.ecisr.mmm.block.MmmBlocks;
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
			entries.accept(MmmItems.CHEMICAL_INDICATOR);
			entries.accept(MmmItems.CHEMICAL_ERASER);
		});
	}
}
