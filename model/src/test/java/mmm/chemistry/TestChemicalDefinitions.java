package mmm.chemistry;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Chemical test definitions use the data shipped with the game. */
public final class TestChemicalDefinitions {
    private TestChemicalDefinitions() { }
    private static final class Holder { static final ChemicalRegistry VALUE=read(); }
    public static ChemicalRegistry load() { return Holder.VALUE; }
    @FunctionalInterface private interface Decoder<T> { T read(String id, Reader reader); }
    private static <T> List<T> category(Path base, String name, Decoder<T> decoder) throws IOException {
        var values=new ArrayList<T>();
        try(var paths=Files.list(base.resolve(name))) {
            for(var file:paths.filter(path->path.toString().endsWith(".json")).sorted().toList()) {
                String filename=file.getFileName().toString();
                try(var reader=Files.newBufferedReader(file)) { values.add(decoder.read("mmm:"+filename.substring(0,filename.length()-5),reader)); }
            }
        }
        return List.copyOf(values);
    }
    private static ChemicalRegistry read() {
        try {
            Path base=null;
            for(Path at=Path.of("").toAbsolutePath();at!=null;at=at.getParent()) {
                var candidate=at.resolve("fabric/src/main/resources/data/mmm/mmm");
                if(Files.isDirectory(candidate)) { base=candidate; break; }
            }
            if(base==null) throw new IllegalStateException("Project chemical data is unavailable");
            return new ChemicalRegistry(category(base,"species",ChemicalDefinitionCodec::decodeSpecies),
                    category(base,"chemical_phases",ChemicalDefinitionCodec::decodePhase),
                    category(base,"reactions",ChemicalDefinitionCodec::decodeReaction),
                    category(base,"gas_liquid_mass_transfers",ChemicalDefinitionCodec::decodeGasLiquidMassTransfer));
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }
}
