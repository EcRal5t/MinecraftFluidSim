package mmm.compat.jade;

import net.minecraft.world.level.block.Block;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

import java.lang.reflect.InvocationTargetException;

/** Optional Jade discovery entry point; Jade is never required to load the main Mod. */
@WailaPlugin
public final class MmmJadePlugin implements IWailaPlugin {
	@Override
	public void register(IWailaCommonRegistration registration) {
		registration.registerBlockDataProvider(MmmJadeServerDataProvider.INSTANCE, Block.class);
	}

	@Override
	public void registerClient(IWailaClientRegistration registration) {
		try {
			Class.forName("mmm.compat.jade.MmmJadeClientRegistration")
					.getMethod("register", IWailaClientRegistration.class)
					.invoke(null, registration);
		} catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
			throw new IllegalStateException("MMM Jade client bridge is unavailable", exception);
		} catch (InvocationTargetException exception) {
			throw new IllegalStateException("MMM Jade client registration failed", exception.getCause());
		}
	}
}
