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

	public static Set load() throws Exception {
		PackLocationInfo location = new PackLocationInfo("mmm-production-registries",
				Component.literal("MMM production registries"), PackSource.BUILT_IN, Optional.empty());
		PackResources pack = new PathPackResources(location, resourceRoot());
		try (MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, List.of(pack))) {
			ChemicalRegistry chemical = ChemicalDataPackLoader.load(resources);
			GranularRegistry granular = GranularDataPackLoader.load(resources, chemical);
			GasRegistry gas = BuiltinGasDefinitions.createRegistry();
			ThermalRegistry thermal = ThermalDataPackLoader.load(resources, chemical.fingerprint(),
					granular.fingerprint(), gas.fingerprint());
			return new Set(BuiltinFluidMaterials.createRegistry(), chemical, granular, gas, thermal);
		}
	}

	private static Path resourceRoot() throws URISyntaxException {
		URL water = ProductionRegistries.class.getResource(PROBE_RESOURCE);
		if (water == null || !water.getProtocol().equals("file")) {
			throw new AssertionError("Production datapack resources are not available as a validation directory");
		}
		Path cursor = Path.of(water.toURI()).toAbsolutePath().normalize();
		while (cursor != null && !Files.isDirectory(cursor.resolve("data"))) cursor = cursor.getParent();
		if (cursor == null) throw new AssertionError("Could not resolve the production datapack root");
		return cursor;
	}

	public record Set(
			MaterialRegistry material,
			ChemicalRegistry chemical,
			GranularRegistry granular,
			GasRegistry gas,
			ThermalRegistry thermal
	) { }
}
