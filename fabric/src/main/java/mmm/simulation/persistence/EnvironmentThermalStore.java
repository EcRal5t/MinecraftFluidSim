package mmm.simulation.persistence;

import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.air.GasComponentAmount;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasLimits;
import mmm.fluid.air.GasRegistry;
import mmm.granular.GranularRegistry;
import mmm.thermal.EnvironmentThermalState;
import mmm.thermal.FiniteThermalDeviceItemCodec;
import mmm.thermal.ThermalRegistry;
import mmm.units.SignedDyadic;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.util.*;

/** Immutable world snapshots published by one atomic HEAD replacement. */
public final class EnvironmentThermalStore {
    private static final int MAGIC = 0x4d4d4554, FORMAT = 1, MAX_NODES = 5_000_000, MAX_CONTACTS = 30_000_000;
    private final Path root;
    private final ActiveGenerationStore regions;

    public EnvironmentThermalStore(Path worldRoot, ChemicalRegistry chemistry, GranularRegistry granular,
                                   ThermalRegistry thermal, GasRegistry gas) {
        root = worldRoot.toAbsolutePath().normalize().resolve("mmm").resolve("world-thermal");
        regions = new ActiveGenerationStore(chemistry, granular, thermal, gas);
    }

    public record Removal(long generation, long checksum) { }
    public record Snapshot(Map<String, EnvironmentThermalState> environments, Map<UUID, RegionState> regions,
                           Map<UUID, Removal> removals) {
        public Snapshot { environments = Map.copyOf(environments); regions = Map.copyOf(regions); removals=Map.copyOf(removals); }
    }

    /** Captures a complete same-instant set, then publishes it as the only restore authority. */
    public void publish(Map<String, EnvironmentThermalState> environments, Map<UUID, RegionState> regionStates) throws IOException {
        commit(prepare(environments, regionStates, Map.of()));
    }

    /** Writes the immutable candidate without changing the published world generation. */
    public Prepared prepare(Map<String, EnvironmentThermalState> environments, Map<UUID, RegionState> regionStates) throws IOException {
        return prepare(environments,regionStates,Map.of());
    }
    public Prepared prepare(Map<String, EnvironmentThermalState> environments, Map<UUID, RegionState> regionStates,
                            Map<UUID, Removal> removals) throws IOException {
        byte[] base = Files.exists(root.resolve("HEAD")) ? Files.readAllBytes(root.resolve("HEAD")) : null;
        var snapshot = new Snapshot(environments, regionStates, removals);
        DurableFileOperations.createDirectories(root.resolve("checkpoints"));
        String id = UUID.randomUUID().toString();
        Path temporary = root.resolve("checkpoints").resolve(id + ".tmp");
        Path target = root.resolve("checkpoints").resolve(id);
        Files.createDirectory(temporary);
        var dimensions = new TreeMap<>(snapshot.environments());
        var sortedRegions = new TreeMap<>(snapshot.regions());
        try (var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary.resolve("manifest"))))) {
            out.writeInt(MAGIC); out.writeInt(FORMAT);
            out.writeInt(dimensions.size()); int index = 0;
            for (var entry : dimensions.entrySet()) {
                var state = entry.getValue();
                if (!entry.getKey().equals(state.dimensionId())) throw new IOException("Environment dimension key differs from state");
                String file = "environment-" + index++;
                Path path = temporary.resolve(file);
                try (var env = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path)))) { writeEnvironment(env, state); }
                DurableFileOperations.forceFile(path);
                out.writeUTF(entry.getKey()); out.writeLong(state.generation()); out.writeUTF(file);
            }
            out.writeInt(sortedRegions.size());
            for (var entry : sortedRegions.entrySet()) {
                var state = entry.getValue();
                if (!entry.getKey().equals(state.manifest().regionId())) throw new IOException("Region key differs from checkpoint");
                state.validate();
                String file = "region-" + entry.getKey();
                regions.writeTemporary(state, temporary.resolve(file), (point, path) -> { }, false);
                out.writeUTF(entry.getKey().toString()); out.writeLong(state.manifest().checkpointGeneration());
                out.writeLong(state.manifest().checksum()); out.writeUTF(file);
            }
            out.writeInt(snapshot.removals().size());
            for(var entry:new TreeMap<>(snapshot.removals()).entrySet()) {
                if(snapshot.regions().containsKey(entry.getKey()))throw new IOException("Removed region is still active");
                out.writeUTF(entry.getKey().toString()); out.writeLong(entry.getValue().generation()); out.writeLong(entry.getValue().checksum());
            }
        }
        DurableFileOperations.forceFile(temporary.resolve("manifest"));
        DurableFileOperations.forceDirectory(temporary);
        DurableFileOperations.atomicMove(temporary, target);
        DurableFileOperations.forceDirectory(target.getParent());
        return new Prepared(id, base, snapshot);
    }

    public static final class Prepared {
        private final String id;
        private final byte[] base;
        private final Snapshot snapshot;
        private boolean committed;
        private Prepared(String id, byte[] base, Snapshot snapshot) { this.id=id; this.base=base; this.snapshot=snapshot; }
        public Snapshot snapshot() { return snapshot; }
    }

    /** Atomically publishes a staged set only if the world HEAD is still its captured base. */
    public void commit(Prepared prepared) throws IOException {
        if (prepared == null || prepared.committed) throw new IOException("World checkpoint candidate is invalid");
        byte[] current = Files.exists(root.resolve("HEAD")) ? Files.readAllBytes(root.resolve("HEAD")) : null;
        if (!Arrays.equals(prepared.base,current)) throw new IOException("World checkpoint base changed before publication");
        String id=prepared.id;
        if (!Files.isDirectory(root.resolve("checkpoints").resolve(id))) throw new IOException("World checkpoint candidate is missing");
        Path headTemp = root.resolve("HEAD-" + id + ".tmp");
        try (var out = new DataOutputStream(Files.newOutputStream(headTemp))) { out.writeInt(MAGIC); out.writeInt(FORMAT); out.writeUTF(id); }
        DurableFileOperations.forceFile(headTemp);
        DurableFileOperations.atomicMoveReplacing(headTemp, root.resolve("HEAD"));
        prepared.committed = true;
        DurableFileOperations.forceDirectory(root);
    }

    /** Tests the commit identity after a publication error without reading checkpoint payloads. */
    public boolean isPublished(Prepared prepared) throws IOException {
        if(prepared==null || !Files.exists(root.resolve("HEAD")))return false;
        try(var in=new DataInputStream(Files.newInputStream(root.resolve("HEAD")))) {
            if(in.readInt()!=MAGIC || in.readInt()!=FORMAT)throw new IOException("Invalid world thermal HEAD");
            String id=in.readUTF(); if(in.read()!=-1)throw new IOException("Trailing world thermal HEAD data");
            return id.equals(prepared.id);
        }
    }

    /** Returns null before the first publication. Corrupt or incomplete committed data fails closed. */
    public Snapshot read() throws IOException {
        Path head = root.resolve("HEAD");
        if (!Files.exists(head)) return null;
        try (var in = new DataInputStream(Files.newInputStream(head))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) throw new IOException("Invalid world thermal HEAD");
            String id = in.readUTF();
            try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid world checkpoint identity", e); }
            if (in.read() != -1) throw new IOException("Trailing world thermal HEAD data");
            Path directory = root.resolve("checkpoints").resolve(id);
            return readSnapshot(directory);
        }
    }

    private Snapshot readSnapshot(Path directory) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(directory.resolve("manifest"))))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT) throw new IOException("Invalid world checkpoint manifest");
            var environments = new LinkedHashMap<String, EnvironmentThermalState>();
            int dimensions = count(in.readInt(), 10000);
            for (int i = 0; i < dimensions; i++) {
                String dimension = in.readUTF(); long generation = in.readLong(); String file = in.readUTF();
                if (!file.equals("environment-" + i)) throw new IOException("Invalid environment checkpoint path");
                Path path = directory.resolve(file);
                EnvironmentThermalState state;
                try (var env = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) { state = readEnvironment(env); if (env.read() != -1) throw new IOException("Trailing environment checkpoint data"); }
                if (!dimension.equals(state.dimensionId()) || generation != state.generation() || environments.put(dimension, state) != null)
                    throw new IOException("Environment checkpoint identity mismatch");
            }
            var states = new LinkedHashMap<UUID, RegionState>(); int count = count(in.readInt(), 100000);
            for (int i = 0; i < count; i++) {
                UUID id = UUID.fromString(in.readUTF()); long generation = in.readLong(), checksum = in.readLong(); String file = in.readUTF();
                if (!file.equals("region-" + id)) throw new IOException("Invalid region checkpoint path");
                var state = regions.readSlot(directory.resolve(file)); state.validate(); regions.validateReconstruction(state);
                if (!id.equals(state.manifest().regionId()) || generation != state.manifest().checkpointGeneration()
                        || checksum != state.manifest().checksum() || states.put(id, state) != null)
                    throw new IOException("Region checkpoint identity mismatch");
            }
            int removalCount=count(in.readInt(),100000); var removals=new LinkedHashMap<UUID,Removal>();
            for(int i=0;i<removalCount;i++) {
                UUID id=UUID.fromString(in.readUTF()); var removal=new Removal(in.readLong(),in.readLong());
                if(removal.generation()<=0 || states.containsKey(id) || removals.put(id,removal)!=null)throw new IOException("Invalid removed region reference");
            }
            if (in.read() != -1) throw new IOException("Trailing world checkpoint manifest data");
            return new Snapshot(environments, states, removals);
        } catch (IllegalArgumentException | ArithmeticException e) { throw new IOException("Invalid world checkpoint", e); }
    }

    private static void writeEnvironment(DataOutputStream out, EnvironmentThermalState state) throws IOException {
        out.writeInt(MAGIC); out.writeInt(FORMAT); out.writeUTF(state.dimensionId()); out.writeLong(state.generation()); out.writeUTF(state.registryFingerprint());
        var audit = state.audit();
        writeDyadic(out, audit.initial()); writeDyadic(out, audit.source()); writeDyadic(out, audit.sink()); writeDyadic(out, audit.regionExchange()); writeDyadic(out, audit.roundoff());
        out.writeInt(state.nodes().size());
        for (var node : state.nodes()) {
            var key = node.key(); out.writeInt(key.x()); out.writeInt(key.y()); out.writeInt(key.z()); out.writeInt(key.kind().ordinal()); out.writeInt(key.component());
            out.writeUTF(node.materialId()); out.writeLong(node.massUnits()); out.writeDouble(node.volumeCubicMetres()); out.writeDouble(node.internalEnergyJ());
            out.writeInt(node.gas().components().size());
            for (var component : node.gas().components()) {
                out.writeUTF(component.componentId()); out.writeLong(component.standardVolumeNanos()); out.writeLong(component.massUnits()); out.writeLong(component.massResidualUnits());
                out.writeInt(component.species().size());
                for (var species : component.species()) { out.writeUTF(species.speciesId()); out.writeLong(species.amountQuanta()); }
            }
            FiniteThermalDeviceItemCodec.writeSurfaces(out, node.surfaces());
        }
        out.writeInt(state.contacts().size());
        for (var contact : state.contacts()) { out.writeInt(contact.a()); out.writeInt(contact.b()); out.writeDouble(contact.conductanceWPerK()); out.writeDouble(contact.farTemperatureK()); }
    }

    private static EnvironmentThermalState readEnvironment(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC || in.readInt() != FORMAT) throw new IOException("Invalid environment checkpoint");
        String dimension = in.readUTF(); long generation = in.readLong(); String fingerprint = in.readUTF();
        var audit = new EnvironmentThermalState.Audit(readDyadic(in), readDyadic(in), readDyadic(in), readDyadic(in), readDyadic(in));
        int nodeCount = count(in.readInt(), MAX_NODES); var nodes = new ArrayList<EnvironmentThermalState.Node>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            int x = in.readInt(), y = in.readInt(), z = in.readInt(), kind = in.readInt(), componentIndex = in.readInt();
            if (kind < 0 || kind >= EnvironmentThermalState.Kind.values().length) throw new IOException("Invalid environment node kind");
            var key = new EnvironmentThermalState.Key(x, y, z, EnvironmentThermalState.Kind.values()[kind], componentIndex);
            String material = in.readUTF(); long mass = in.readLong(); double volume = in.readDouble(), energy = in.readDouble();
            int components = count(in.readInt(), GasLimits.MAX_COMPONENTS_PER_NODE); var gas = new ArrayList<GasComponentAmount>(components);
            for (int c = 0; c < components; c++) {
                String id = in.readUTF(); long nanos = in.readLong(), gasMass = in.readLong(), residual = in.readLong();
                int speciesCount = count(in.readInt(), GasLimits.MAX_SPECIES_PER_COMPONENT); var species = new ArrayList<GasComponentAmount.SpeciesAmount>(speciesCount);
                for (int s = 0; s < speciesCount; s++) species.add(new GasComponentAmount.SpeciesAmount(in.readUTF(), in.readLong()));
                gas.add(new GasComponentAmount(id, nanos, gasMass, species, residual));
            }
            nodes.add(new EnvironmentThermalState.Node(key, material, mass, volume, new GasInventory(gas), energy, FiniteThermalDeviceItemCodec.readSurfaces(in)));
        }
        int contactCount = count(in.readInt(), MAX_CONTACTS); var contacts = new ArrayList<EnvironmentThermalState.Contact>(contactCount);
        for (int i = 0; i < contactCount; i++) contacts.add(new EnvironmentThermalState.Contact(in.readInt(), in.readInt(), in.readDouble(), in.readDouble()));
        return new EnvironmentThermalState(dimension, generation, fingerprint, nodes, contacts, audit);
    }

    private static void writeDyadic(DataOutputStream out, SignedDyadic value) throws IOException {
        byte[] bytes = value.significand().toByteArray(); out.writeInt(value.exponent()); out.writeInt(bytes.length); out.write(bytes);
    }
    private static SignedDyadic readDyadic(DataInputStream in) throws IOException {
        int exponent = in.readInt(), length = count(in.readInt(), SignedDyadic.MAX_SIGNIFICAND_BITS / 8 + 2);
        if (length == 0) throw new IOException("Empty dyadic"); byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new EOFException();
        var result = new SignedDyadic(new BigInteger(bytes), exponent);
        if (result.exponent() != exponent || !Arrays.equals(result.significand().toByteArray(), bytes)) throw new IOException("Noncanonical dyadic");
        return result;
    }
    private static int count(int value, int maximum) throws IOException { if (value < 0 || value > maximum) throw new IOException("Checkpoint count out of range"); return value; }
}
