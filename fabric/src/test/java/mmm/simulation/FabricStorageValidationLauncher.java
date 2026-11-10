package mmm.simulation;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

/** Starts Fabric's class transformer without starting a Minecraft server. */
public final class FabricStorageValidationLauncher {
    public static void main(String[] args) throws Exception {
        System.setProperty("mmm.validation.fabricStorage","true");
        var loader=new Knot(EnvType.SERVER).init(new String[0]);
        try {
            loader.loadClass("mmm.simulation.MaterialBoxItemStorageValidation").getMethod("main",String[].class)
                    .invoke(null,(Object)new String[0]);
        } catch(java.lang.reflect.InvocationTargetException failure) {
            throw new RuntimeException(failure.getCause());
        }
    }
}
