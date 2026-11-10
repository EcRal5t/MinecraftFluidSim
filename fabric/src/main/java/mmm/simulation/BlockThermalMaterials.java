package mmm.simulation;

import java.util.List;
import mmm.thermal.FiniteThermalDeviceDefinition;
import mmm.thermal.ThermalRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Data-pack block tags select the material and density of a cubic metre of solid. */
public final class BlockThermalMaterials {
    private record Mapping(TagKey<Block> tag, String deviceId) { }
    private static final List<Mapping> MAPPINGS = List.of(
            mapping("stone"), mapping("metal"), mapping("copper"), mapping("gold"),
            mapping("wood"), mapping("glass"), mapping("insulation"));

    private BlockThermalMaterials() { }

    private static Mapping mapping(String name) {
        return new Mapping(TagKey.create(Registries.BLOCK,
                Identifier.parse("mmm:ordinary_" + name + "_thermal_walls")),
                "mmm:ordinary_" + name + "_wall");
    }

    public static FiniteThermalDeviceDefinition resolve(BlockState state, ThermalRegistry registry) {
        String deviceId = null;
        for (Mapping mapping : MAPPINGS) if (state.is(mapping.tag())) {
            if (deviceId != null) throw new IllegalArgumentException("Multiple thermal material mappings for " + state);
            deviceId = mapping.deviceId();
        }
        return registry.finiteDevice(deviceId == null ? "mmm:ordinary_stone_wall" : deviceId);
    }
}
