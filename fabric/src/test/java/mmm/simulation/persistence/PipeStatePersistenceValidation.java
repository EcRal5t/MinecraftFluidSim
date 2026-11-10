package mmm.simulation.persistence;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import mmm.chemistry.*;
import mmm.fluid.air.*;
import mmm.granular.*;
import mmm.inventory.LiquidCellInventory;
import mmm.pipe.*;
import mmm.units.SignedDyadic;

/** Pipe inventory and accepted world geometry share the world HEAD. */
public final class PipeStatePersistenceValidation {
    private PipeStatePersistenceValidation() { }
    public static void run() throws Exception {
        var chemistry=mmm.fluid.TestFluidDefinitions.load().chemistry();
        var liquid=ChemicalMixtureRecord.fromStableAmounts(chemistry,Map.of(BuiltinChemicalDefinitions.H2O,100L),7);
        var solid=SolidCompositionRecord.fromStableAmounts(chemistry,Map.of(BuiltinChemicalDefinitions.H2O,3L),2);
        var gas=new GasInventory(List.of(new GasComponentAmount("mmm:oxygen",100,200,
                List.of(new GasComponentAmount.SpeciesAmount("mmm:o2",2)),4)));
        var deposit=new mmm.granular.GranularTransferLedger.Portion(new GranularTransferLedger.Group("mmm:salt",1000,2000,solid),13.5);
        var segments=List.of(new PipeSegmentState("1",0,.25,gas,21,List.of(deposit)),
                new PipeSegmentState("2",1,.125,GasInventory.EMPTY,0,List.of()));
        var rows=List.of(List.of(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,0,255),SignedDyadic.of(.125),18007,liquid,52.25)),
                List.<LiquidCellInventory.Stock>of());
        var network=new PipeNetworkState(segments,List.of(new PipeConnection(0,1,1,.25,1,.5,.001)),List.of("mmm:water"),new LiquidCellInventory.Snapshot(rows));
        var audit=new PipeAudit(SignedDyadic.of(1),SignedDyadic.of(2),SignedDyadic.of(3),SignedDyadic.of(-4),SignedDyadic.of(5),SignedDyadic.of(-.125));
        var expected=new PipeDimensionState(9,network,audit,Map.of("1",new PipeInstallation("mmm:glass_pipe",Map.of("west","open","east","connected")),
                "2",new PipeInstallation("mmm:pipe_valve",Map.of("facing","up","opening","1"))));
        byte[] bytes=encode(expected,chemistry);var decoded=PipeStateCodec.read(new DataInputStream(new ByteArrayInputStream(bytes)),chemistry);
        check(Arrays.equals(bytes,encode(decoded,chemistry)),"pipe state did not round-trip exactly");
        check(decoded.installations().equals(expected.installations()) && decoded.network().segments().equals(segments)
                && decoded.network().liquids().rows().equals(rows) && decoded.audit().equals(audit),"pipe inventory or geometry changed");
        var root=Files.createTempDirectory("mmm-pipe-world-");
        try {
            var store=new EnvironmentThermalStore(root,chemistry,null,null,null);
            store.commit(store.prepare(Map.of(),Map.of(),Map.of(),Map.of("minecraft:overworld",expected)));
            var restored=store.read().pipes().get("minecraft:overworld");
            check(Arrays.equals(bytes,encode(restored,chemistry)),"world checkpoint changed pipe state");
            var staged=store.prepare(Map.of(),Map.of(),Map.of(),Map.of());
            check(store.read().pipes().containsKey("minecraft:overworld"),"unpublished world candidate changed pipe state");
            var head=new DataInputStream(Files.newInputStream(root.resolve("mmm/world-thermal/HEAD")));
            head.readInt();head.readInt();var id=head.readUTF();head.close();
            var file=root.resolve("mmm/world-thermal/checkpoints").resolve(id).resolve("pipes-0");
            byte[] corrupt=Files.readAllBytes(file);Files.write(file,Arrays.copyOf(corrupt,corrupt.length-1));
            try {store.read();throw new AssertionError("truncated pipe checkpoint was accepted");}catch(IOException expectedFailure) { }
        } finally {DurableFileOperations.deleteTreeWithin(root.getParent(),root);}
        var old=Files.createTempDirectory("mmm-pipe-v2-");
        try {
            var id=UUID.randomUUID().toString();var directory=old.resolve("mmm/world-thermal/checkpoints").resolve(id);Files.createDirectories(directory);
            try(var out=new DataOutputStream(Files.newOutputStream(directory.resolve("manifest")))) {
                out.writeInt(0x4d4d4554);out.writeInt(2);out.writeInt(0);out.writeInt(0);out.writeInt(0);
            }
            try(var out=new DataOutputStream(Files.newOutputStream(old.resolve("mmm/world-thermal/HEAD")))) {
                out.writeInt(0x4d4d4554);out.writeInt(2);out.writeUTF(id);
            }
            var snapshot=new EnvironmentThermalStore(old,chemistry,null,null,null).read();
            check(snapshot.pipes().isEmpty() && snapshot.regions().isEmpty(),"version 2 checkpoint did not load without pipe state");
        } finally {DurableFileOperations.deleteTreeWithin(old.getParent(),old);}
    }
    private static byte[] encode(PipeDimensionState state,ChemicalRegistry chemistry) throws IOException {
        var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){PipeStateCodec.write(out,state,chemistry);}return bytes.toByteArray();
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
