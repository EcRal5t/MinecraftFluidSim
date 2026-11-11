package mmm.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import mmm.controller.ControllerSummary.Phase;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/** Resource-pack presentation of server-owned substances; no physical properties are inferred here. */
final class CompositionNames implements SimpleSynchronousResourceReloadListener {
    private static final String DIRECTORY="mmm/substances";
    private record Name(String translationKey,String fallback) { }
    private record Presentation(Map<String,Name> names,Map<Phase,IndustrialUi.Icon> icons) { }
    private static volatile Presentation presentation=new Presentation(Map.of(),Map.of());
    private CompositionNames() { }
    static void register() { ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new CompositionNames()); }
    @Override public Identifier getFabricId() { return mmm.MmmMod.id("composition_names"); }
    @Override public void onResourceManagerReload(ResourceManager manager) { reload(manager); }

    static void reload(ResourceManager manager) {
        var names=new HashMap<String,Name>();
        var aliases=new HashMap<String,String>();
        for(var entry:manager.listResources(DIRECTORY,id -> id.getPath().endsWith(".json")).entrySet()) {
            var path=entry.getKey();
            String id=Identifier.fromNamespaceAndPath(path.getNamespace(),path.getPath().substring(DIRECTORY.length()+1,path.getPath().length()-5)).toString();
            JsonObject json=read(entry.getValue());
            String fallback=requiredString(json,"name");
            String translation=json.has("translation_key")?requiredString(json,"translation_key"):translationKey(id);
            names.put(id,new Name(translation,fallback));
            if(json.has("aliases")) for(var alias:json.getAsJsonArray("aliases")) {
                String key=Identifier.parse(alias.getAsString()).toString();
                if(aliases.putIfAbsent(key,id)!=null) throw new IllegalArgumentException("Duplicate substance alias: "+key);
            }
        }
        for(var entry:aliases.entrySet()) {
            if(names.containsKey(entry.getKey())) throw new IllegalArgumentException("Substance alias shadows a definition: "+entry.getKey());
            names.put(entry.getKey(),names.get(entry.getValue()));
        }
        var icons=new EnumMap<Phase,IndustrialUi.Icon>(Phase.class);
        var iconResource=manager.getResource(mmm.MmmMod.id("mmm/composition/phase_icons.json"))
                .orElseThrow(() -> new IllegalArgumentException("Missing composition phase icons"));
        var mapping=read(iconResource);
        for(var phase:Phase.values()) icons.put(phase,IndustrialUi.Icon.valueOf(requiredString(mapping,phase.name().toLowerCase(Locale.ROOT)).toUpperCase(Locale.ROOT)));
        // Publish the complete reload together; a rejected pack leaves the previous presentation intact.
        presentation=new Presentation(Map.copyOf(names),Map.copyOf(icons));
    }
    private static JsonObject read(Resource resource) {
        try(var reader=resource.openAsReader()) { return JsonParser.parseReader(reader).getAsJsonObject(); }
        catch(IOException | RuntimeException error) { throw new IllegalArgumentException("Invalid substance presentation in "+resource.sourcePackId(),error); }
    }
    private static String requiredString(JsonObject json,String key) {
        var value=json.get(key);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
            throw new IllegalArgumentException("Missing presentation string: "+key);
        return value.getAsString();
    }
    private static String translationKey(String id) { return "substance."+id.replace(':','.'); }
    static IndustrialUi.Icon phaseIcon(Phase phase,boolean ion) { return ion?null:presentation.icons().get(phase); }
    static Component label(String id) {
        var name=presentation.names().get(id);
        return name==null?Component.translatableWithFallback(translationKey(id),id)
                :Component.translatableWithFallback(name.translationKey(),name.fallback());
    }
}
