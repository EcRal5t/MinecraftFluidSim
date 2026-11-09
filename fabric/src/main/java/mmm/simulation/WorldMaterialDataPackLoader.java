package mmm.simulation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ExactRational;
import mmm.fluid.FluidMaterial;
import mmm.fluid.MaterialRegistry;
import mmm.fluid.air.GasComponentDefinition;
import mmm.fluid.air.GasRegistry;
import mmm.thermal.LiquidThermalExpansion;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Loads liquid and ideal-gas properties from the world's data packs. */
final class WorldMaterialDataPackLoader {
    private WorldMaterialDataPackLoader() { }

    static MaterialRegistry liquids(ResourceManager resources) throws IOException {
        var entries = new ArrayList<MaterialRegistry.MaterialEntry>();
        for (var entry : category(resources, "mmm/fluid_materials").entrySet()) {
            String id = entry.getKey();
            JsonObject value = entry.getValue();
            var material = new FluidMaterial(id, number(value, "reference_density_kg_per_m3"),
                    value.get("miscibility_group").getAsString(), number(value, "surface_tension_n_per_m"),
                    number(value, "contact_angle_radians"), (int) Long.parseUnsignedLong(value.get("display_argb").getAsString(), 16));
            JsonArray points = value.getAsJsonArray("viscosity_points");
            var segments = new ArrayList<MaterialRegistry.ViscositySegment>();
            for (int i = 1; i < points.size(); i++) {
                JsonArray left = points.get(i - 1).getAsJsonArray(), right = points.get(i).getAsJsonArray();
                segments.add(new MaterialRegistry.ViscositySegment(left.get(0).getAsDouble(), right.get(0).getAsDouble(),
                        left.get(1).getAsDouble(), right.get(1).getAsDouble()));
            }
            var expansion = new LiquidThermalExpansion.Definition(id, number(value, "reference_temperature_k"),
                    number(value, "thermal_expansion_per_k"));
            entries.add(new MaterialRegistry.MaterialEntry(material, segments, expansion));
        }
        var interfaces = new ArrayList<MaterialRegistry.LiquidPairTension>();
        for (var value : category(resources, "mmm/fluid_interfaces", false).values()) {
            String first = value.get("first_miscibility_group").getAsString();
            String second = value.get("second_miscibility_group").getAsString();
            if (first.compareTo(second) > 0) {
                String swap = first;
                first = second;
                second = swap;
            }
            interfaces.add(new MaterialRegistry.LiquidPairTension(first, second, number(value, "tension_n_per_m")));
        }
        return new MaterialRegistry(entries, interfaces);
    }

    static GasRegistry gases(ResourceManager resources, ChemicalRegistry chemistry) throws IOException {
        var components = new ArrayList<GasComponentDefinition>();
        var ambient = new ArrayList<GasRegistry.AmbientFraction>();
        double concentration = GasRegistry.STANDARD_PRESSURE_PA / (GasRegistry.MOLAR_GAS_CONSTANT * GasRegistry.STANDARD_TEMPERATURE_K);
        for (var entry : category(resources, "mmm/gas_components").entrySet()) {
            String id = entry.getKey(); JsonObject value = entry.getValue();
            String species = value.get("species").getAsString();
            int handle = chemistry.speciesHandle(species);
            double molarMass = chemistry.species(handle).molarMassKgPerMol().toDouble();
            components.add(new GasComponentDefinition(id, concentration * molarMass, List.of(species),
                    List.of(new GasComponentDefinition.DefaultSpecies(species, ExactRational.parse(Double.toString(concentration)),
                            chemistry.speciesMassUnits(handle, 1)))));
            var fraction = ExactRational.parse(value.get("ambient_fraction").getAsString());
            if (fraction.signum() < 0) throw new IOException("Negative ambient fraction for " + id);
            if (fraction.signum() > 0) ambient.add(new GasRegistry.AmbientFraction(id, fraction));
        }
        return new GasRegistry(components, ambient);
    }

    private static double number(JsonObject value, String key) {
        return value.get(key).getAsDouble();
    }

    private static Map<String, JsonObject> category(ResourceManager resources, String prefix) throws IOException {
        return category(resources, prefix, true);
    }

    private static Map<String, JsonObject> category(ResourceManager resources, String prefix, boolean required) throws IOException {
        var result = new TreeMap<String, JsonObject>();
        for (var entry : resources.listResources(prefix, id -> id.getPath().endsWith(".json")).entrySet()) {
            Identifier resourceId = entry.getKey(); Resource resource = entry.getValue();
            String path = resourceId.getPath().substring(prefix.length() + 1);
            String name = path.substring(0, path.length() - ".json".length());
            Identifier stable = Identifier.tryBuild(resourceId.getNamespace(), name);
            if (stable == null || name.isEmpty()) throw new IOException("Invalid material definition " + resourceId);
            try (var reader = resource.openAsReader()) {
                if (result.put(stable.toString(), JsonParser.parseReader(reader).getAsJsonObject()) != null)
                    throw new IOException("Duplicate material definition " + stable);
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid material definition " + resourceId + " from " + resource.sourcePackId(), invalid);
            }
        }
        if (required && result.isEmpty()) throw new IOException("Missing material definitions " + prefix);
        return result;
    }
}
