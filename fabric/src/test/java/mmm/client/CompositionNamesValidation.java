package mmm.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import mmm.controller.ControllerSummary.Phase;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

final class CompositionNamesValidation {
    static void run() {
        try {
            Path root=Path.of(CompositionNamesValidation.class.getResource("/assets/mmm/mmm/substances/h2o.json").toURI());
            for(int i=0;i<5;i++) root=root.getParent();
            try(var manager=new MultiPackResourceManager(PackType.CLIENT_RESOURCES,List.of(pack("builtin",root)))) {
                CompositionNames.reload(manager);
                check(CompositionNames.label("mmm:n2").equals(CompositionNames.label("mmm:nitrogen")),"Builtin aliases resolve to one definition");
                check(CompositionNames.phaseIcon(Phase.GAS,false)==IndustrialUi.Icon.GAS,"Builtin phase icon data");
                Path custom=Files.createTempDirectory(Path.of("."),"substance-presentation-");
                write(custom,"assets/mmm/mmm/substances/h2o.json","""
                        {"name":"Resource water","translation_key":"test.water","aliases":["mmm:water"]}
                        """);
                write(custom,"assets/example/mmm/substances/crystal.json","""
                        {"name":"Crystal","aliases":["example:crystal_powder"]}
                        """);
                write(custom,"assets/mmm/mmm/composition/phase_icons.json","""
                        {"liquid":"liquid","gas":"flow","granular":"solid","coherent":"solid"}
                        """);
                try(var overridden=new MultiPackResourceManager(PackType.CLIENT_RESOURCES,List.of(pack("builtin",root),pack("custom",custom)))) {
                    CompositionNames.reload(overridden);
                    check(CompositionNames.label("mmm:h2o").getString().equals("Resource water")
                            && CompositionNames.label("mmm:water").equals(CompositionNames.label("mmm:h2o")),"Higher priority pack overrides names and aliases");
                    check(CompositionNames.label("mmm:ice").getString().equals("mmm:ice"),"Reload removes deleted aliases");
                    check(CompositionNames.label("example:crystal_powder").getString().equals("Crystal"),"New namespace needs no Java registration");
                    check(CompositionNames.phaseIcon(Phase.GAS,false)==IndustrialUi.Icon.FLOW
                            && CompositionNames.phaseIcon(Phase.GAS,true)==null,"Resource phase mapping preserves server ion semantics");
                }
                write(custom,"assets/example/mmm/substances/duplicate.json","""
                        {"name":"Duplicate","aliases":["mmm:water"]}
                        """);
                try(var invalid=new MultiPackResourceManager(PackType.CLIENT_RESOURCES,List.of(pack("builtin",root),pack("invalid",custom)))) {
                    boolean rejected=false;
                    try { CompositionNames.reload(invalid); } catch(IllegalArgumentException expected) { rejected=true; }
                    check(rejected && CompositionNames.label("mmm:h2o").getString().equals("Resource water"),"Invalid aliases reject the complete reload without partial publication");
                }
                CompositionNames.reload(manager);
                check(CompositionNames.label("example:crystal").getString().equals("example:crystal"),"Removing a pack clears its entries");
            }
        } catch(Exception error) { throw new AssertionError("Substance presentation validation failed",error); }
    }
    private static PathPackResources pack(String name,Path path) {
        return new PathPackResources(new PackLocationInfo(name,Component.literal(name),PackSource.BUILT_IN,Optional.empty()),path);
    }
    private static void write(Path root,String relative,String content) throws java.io.IOException {
        Path file=root.resolve(relative);Files.createDirectories(file.getParent());Files.writeString(file,content);
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
