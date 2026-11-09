package mmm.simulation;

import mmm.chemistry.ChemicalDataPackLoader;
import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.MaterialRegistry;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.fluid.air.GasRegistry;
import mmm.granular.GranularDataPackLoader;
import mmm.granular.GranularRegistry;
import mmm.thermal.ThermalDataPackLoader;
import mmm.thermal.ThermalRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Loads the frozen production datapack registries for every persistence and solver validation gate. */
public final class ProductionRegistries {
	private static final String PROBE_RESOURCE = "/data/mmm/mmm/thermal_materials/water.json";

	private ProductionRegistries() { }

	public static Set load() {
        var definitions = mmm.fluid.TestFluidDefinitions.load();
        return new Set(definitions.material(), definitions.chemistry(), definitions.granular(), definitions.gas(), definitions.thermal());
    }

    public static List<mmm.fluid.FluidMaterial> palette() {
        var material = mmm.fluid.TestFluidDefinitions.load().material();
        return List.of(material.entry("mmm:water").material(), material.entry("mmm:oil").material(),
                material.entry("mmm:mercury").material());
    }

	public record Set(
			MaterialRegistry material,
			ChemicalRegistry chemical,
			GranularRegistry granular,
			GasRegistry gas,
			ThermalRegistry thermal
	) { }
}
