package mmm.fluid;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import mmm.chemistry.*;
import mmm.fluid.air.*;
import mmm.granular.*;
import mmm.thermal.*;

/** Test definitions read from the same data resources used by the game. */
public final class TestFluidDefinitions {
    private TestFluidDefinitions() { }
    private static final class Holder { static final FluidDefinitions VALUE=read(); }
    public static FluidDefinitions load() { return Holder.VALUE; }
    public static FluidDefinitions withThermal(ThermalRegistry thermal) {
        var world=load();
        var rebound=new ThermalRegistry(thermal.materials(),thermal.speciesOffsets(),thermal.transitions(),thermal.boundaries(),thermal.finiteDevices(),
                world.chemistry().fingerprint(),world.granular().fingerprint(),world.gas().fingerprint());
        return new FluidDefinitions(world.material(),world.chemistry(),world.gas(),world.granular(),rebound);
    }
    public static FluidDefinitions withRegistries(MaterialRegistry material,ChemicalRegistry chemistry,GasRegistry gas,
                                                   GranularRegistry granular,ThermalRegistry thermal) {
        var world=load();
        material=material==null?world.material():material;
        chemistry=chemistry==null?world.chemistry():chemistry;
        gas=gas==null?world.gas():gas;
        granular=granular==null?world.granular():granular;
        thermal=thermal==null?world.thermal():thermal;
        granular=new GranularRegistry(granular.materials(),granular.carriers(),granular.reactions(),chemistry);
        thermal=new ThermalRegistry(thermal.materials(),thermal.speciesOffsets(),thermal.transitions(),thermal.boundaries(),thermal.finiteDevices(),
                chemistry.fingerprint(),granular.fingerprint(),gas.fingerprint());
        return new FluidDefinitions(material,chemistry,gas,granular,thermal);
    }
    private static Path directory() {
        for(Path at=Path.of("").toAbsolutePath();at!=null;at=at.getParent()) {
            Path data=at.resolve("fabric/src/main/resources/data/mmm/mmm");
            if(Files.isDirectory(data))return data;
        }
        throw new IllegalStateException("Project material data is unavailable");
    }
    @FunctionalInterface private interface Decoder<T> { T read(String id,Reader source); }
    private static <T> List<T> category(Path base,String name,Decoder<T> decoder) throws IOException {
        Path directory=base.resolve(name);if(!Files.isDirectory(directory))return List.of();
        var result=new ArrayList<T>();
        try(var entries=Files.list(directory)) {
            for(Path file:entries.filter(p->p.toString().endsWith(".json")).sorted().toList()) {
                String namePart=file.getFileName().toString();String id="mmm:"+namePart.substring(0,namePart.length()-5);
                try(var reader=Files.newBufferedReader(file)){result.add(decoder.read(id,reader));}
            }
        }
        return List.copyOf(result);
    }
    private static FluidDefinitions read() {
        try {
            Path base=directory();
            var chemistry=new ChemicalRegistry(category(base,"species",ChemicalDefinitionCodec::decodeSpecies),
                    category(base,"chemical_phases",ChemicalDefinitionCodec::decodePhase),
                    category(base,"reactions",ChemicalDefinitionCodec::decodeReaction),
                    category(base,"gas_liquid_mass_transfers",ChemicalDefinitionCodec::decodeGasLiquidMassTransfer));
            var liquids=category(base,"fluid_materials",(id,reader)->{
                JsonObject j=JsonParser.parseReader(reader).getAsJsonObject();
                var fluid=new FluidMaterial(id,j.get("reference_density_kg_per_m3").getAsDouble(),j.get("miscibility_group").getAsString(),
                        j.get("surface_tension_n_per_m").getAsDouble(),j.get("contact_angle_radians").getAsDouble(),
                        (int)Long.parseUnsignedLong(j.get("display_argb").getAsString(),16));
                JsonArray points=j.getAsJsonArray("viscosity_points");var segments=new ArrayList<MaterialRegistry.ViscositySegment>();
                for(int i=1;i<points.size();i++){var a=points.get(i-1).getAsJsonArray();var b=points.get(i).getAsJsonArray();
                    segments.add(new MaterialRegistry.ViscositySegment(a.get(0).getAsDouble(),b.get(0).getAsDouble(),a.get(1).getAsDouble(),b.get(1).getAsDouble()));}
                var expansion=new LiquidThermalExpansion.Definition(id,j.get("reference_temperature_k").getAsDouble(),j.get("thermal_expansion_per_k").getAsDouble());
                return new MaterialRegistry.MaterialEntry(fluid,segments,expansion);
            });
            var interfaces=category(base,"fluid_interfaces",(id,reader)->{
                JsonObject j=JsonParser.parseReader(reader).getAsJsonObject();
                String first=j.get("first_miscibility_group").getAsString(),second=j.get("second_miscibility_group").getAsString();
                if(first.compareTo(second)>0){String swap=first;first=second;second=swap;}
                return new MaterialRegistry.LiquidPairTension(first,second,j.get("tension_n_per_m").getAsDouble());
            });
            var material=new MaterialRegistry(liquids,interfaces);
            var granular=new GranularRegistry(category(base,"particulate_materials",GranularDefinitionCodec::decodeMaterial),
                    category(base,"granular_carriers",GranularDefinitionCodec::decodeCarrier),
                    category(base,"solid_liquid_reactions",GranularDefinitionCodec::decodeReaction),chemistry);
            double concentration=GasRegistry.STANDARD_PRESSURE_PA/(GasRegistry.MOLAR_GAS_CONSTANT*GasRegistry.STANDARD_TEMPERATURE_K);
            var gasData=category(base,"gas_components",(id,reader)->{
                JsonObject j=JsonParser.parseReader(reader).getAsJsonObject();String species=j.get("species").getAsString();
                int handle=chemistry.speciesHandle(species);double mass=chemistry.species(handle).molarMassKgPerMol().toDouble();
                var component=new GasComponentDefinition(id,concentration*mass,List.of(species),List.of(new GasComponentDefinition.DefaultSpecies(
                        species,ExactRational.parse(Double.toString(concentration)),chemistry.speciesMassUnits(handle,1))));
                return new GasData(component,ExactRational.parse(j.get("ambient_fraction").getAsString()));
            });
            var components=gasData.stream().map(GasData::component).toList();
            var ambient=gasData.stream().filter(g->g.fraction().signum()>0).map(g->new GasRegistry.AmbientFraction(g.component().id(),g.fraction())).toList();
            var gas=new GasRegistry(components,ambient);
            var thermal=new ThermalRegistry(category(base,"thermal_materials",ThermalDefinitionCodec::decodeMaterial),
                    category(base,"thermal_species",ThermalDefinitionCodec::decodeSpecies),
                    category(base,"phase_transitions",ThermalDefinitionCodec::decodeTransition),
                    category(base,"thermal_boundaries",ThermalDefinitionCodec::decodeBoundary),
                    category(base,"finite_thermal_devices",ThermalDefinitionCodec::decodeFiniteDevice),
                    chemistry.fingerprint(),granular.fingerprint(),gas.fingerprint());
            return new FluidDefinitions(material,chemistry,gas,granular,thermal);
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }
    private record GasData(GasComponentDefinition component,ExactRational fraction) { }
}
