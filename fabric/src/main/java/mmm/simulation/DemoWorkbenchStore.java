package mmm.simulation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import mmm.network.DemoPreset;
import mmm.simulation.persistence.DurableFileOperations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;

/** Durable identity of persistent demo tanks; viewer tokens remain process-local. */
final class DemoWorkbenchStore {
    record Entry(UUID player, String dimension, UUID region, BlockPos anchor, Direction facing,
                 DemoPreset preset, boolean recipeReady) {
        Entry {
            Objects.requireNonNull(player); Objects.requireNonNull(region); Objects.requireNonNull(anchor);
            Objects.requireNonNull(facing); Objects.requireNonNull(preset);
            if (dimension == null || Identifier.tryParse(dimension) == null || facing.getAxis() == Direction.Axis.Y || !preset.tank())
                throw new IllegalArgumentException("Invalid persistent demo identity");
            anchor = anchor.immutable();
        }
    }
    private record Key(UUID player, String dimension) { }
    private final Path directory;
    private final Path target;
    private final Path temporary;

    DemoWorkbenchStore(Path worldRoot) {
        directory = worldRoot.toAbsolutePath().normalize().resolve("mmm");
        target = directory.resolve("demo-workbench.nbt");
        temporary = directory.resolve("demo-workbench.nbt.tmp");
    }

    List<Entry> loadAll() throws IOException {
        if (!Files.exists(target)) return List.of();
        try {
            var root = NbtIo.readCompressed(target, NbtAccounter.create(1 << 20));
            if (root.getIntOr("Version", 0) != 1) throw new IOException("Unsupported demo workbench metadata version");
            var entries = new ArrayList<Entry>();
            var keys = new HashSet<Key>();
            for (var value : root.getList("Entries").orElseThrow(() -> new IOException("Demo metadata entries are missing"))) {
                var tag = (CompoundTag) value;
                if (!tag.contains("Anchor") || !tag.contains("RecipeReady"))
                    throw new IOException("Demo metadata entry is incomplete");
                var entry = new Entry(UUID.fromString(tag.getStringOr("Player", "")), tag.getStringOr("Dimension", ""),
                        UUID.fromString(tag.getStringOr("Region", "")), BlockPos.of(tag.getLongOr("Anchor", 0)),
                        Direction.byName(tag.getStringOr("Facing", "")), DemoPreset.valueOf(tag.getStringOr("Preset", "")),
                        tag.getBooleanOr("RecipeReady", false));
                if (!keys.add(new Key(entry.player(), entry.dimension()))) throw new IOException("Duplicate demo workbench owner");
                entries.add(entry);
            }
            return List.copyOf(entries);
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read demo workbench metadata", invalid);
        }
    }

    void save(List<Entry> entries) throws IOException {
        var copy = List.copyOf(entries);
        var keys = new HashSet<Key>();
        var root = new CompoundTag();
        root.putInt("Version", 1);
        var list = new ListTag();
        for (var entry : copy) {
            if (!keys.add(new Key(entry.player(), entry.dimension()))) throw new IllegalArgumentException("Duplicate demo workbench owner");
            var tag = new CompoundTag();
            tag.putString("Player", entry.player().toString()); tag.putString("Dimension", entry.dimension());
            tag.putString("Region", entry.region().toString()); tag.putLong("Anchor", entry.anchor().asLong());
            tag.putString("Facing", entry.facing().getName()); tag.putString("Preset", entry.preset().name());
            tag.putBoolean("RecipeReady", entry.recipeReady()); list.add(tag);
        }
        root.put("Entries", list);
        DurableFileOperations.createDirectories(directory);
        NbtIo.writeCompressed(root, temporary);
        DurableFileOperations.forceFile(temporary);
        DurableFileOperations.atomicMoveReplacing(temporary, target);
        DurableFileOperations.forceDirectory(directory);
    }
}
