package mmm.compat.jade;

import net.minecraft.world.level.block.Block;
import snownee.jade.api.IWailaClientRegistration;

/** Split-environment target resolved only from Jade's client registration callback. */
public final class MmmJadeClientRegistration {
	private MmmJadeClientRegistration() { }

	public static void register(IWailaClientRegistration registration) {
		registration.registerBlockComponent(MmmJadeComponentProvider.INSTANCE, Block.class);
	}
}
