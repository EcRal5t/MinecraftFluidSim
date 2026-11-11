package mmm.simulation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Offline block-world port for the production capture and takeover transaction. */
final class GpuCaptureFixture {
    private final FluidRegion region;
    private final FluidRegionManager manager;
    private final World world;
    private final TakeoverJournalFiles files;
    private final boolean takeover;
    private final double sampledVolume;

    private GpuCaptureFixture(FluidRegion region, FluidRegionManager manager, World world,
            TakeoverJournalFiles files, boolean takeover, double sampledVolume) {
        this.region = region; this.manager = manager; this.world = world;
        this.files = files; this.takeover = takeover; this.sampledVolume = sampledVolume;
    }

    static GpuCaptureFixture create(WorldStartupCoordinator.Result startup, CommittedWorldRegistries registries,
            FluidRegionManager manager, FluidRegionOperations operations, boolean takeover) throws IOException {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var dimension = ResourceKey.create(Registries.DIMENSION,
                Identifier.fromNamespaceAndPath("mmm", "gpu_capture"));
        var domain = FluidRegionFactory.createDomain(MinecraftDebugFixtures.BLOCK_SIZE_X,
                MinecraftDebugFixtures.BLOCK_SIZE_Y, MinecraftDebugFixtures.BLOCK_SIZE_Z, registries);
        var region = new FluidRegion(UUID.randomUUID(), dimension, BlockPos.ZERO, domain);
        if (!manager.addAndSelect(region).accepted()) throw new IllegalStateException("capture region admission failed");
        var world = new World(dimension.identifier().toString());
        var files = new TakeoverJournalFiles(startup.worldRoot());
        try {
            FluidRegionFactory.bindDefaultMaterialState(domain, registries.materialRegistry());
            int wetCells = FluidRegionFactory.captureWater(region, world::waterHeight);
            domain = region.editableHost();
            float sourceHeight = Blocks.WATER.defaultBlockState().getFluidState().getOwnHeight();
            double expectedVolume = 128.0 + 64.0 + 64.0 * (2.0 * sourceHeight - 1.0);
            double volume = domain.totalVolumeExact();
            if (wetCells != 256 || Math.abs(volume - expectedVolume) > 1.0e-4)
                throw new IllegalStateException("capture inventory mismatch: cells=" + wetCells + " volume=" + volume
                        + " expected=" + expectedVolume);
            registries.bindConfiguredState(domain, startup.config().maximumThermalSubstepsPerStep());
            if (takeover) {
                var candidates = MinecraftTakeoverAdapter.collectWater(region, FluidRegionFactory.CELLS_PER_BLOCK, world::blockAt);
                if (candidates.size() != 32) throw new IllegalStateException("takeover candidate count=" + candidates.size());
                var entries = new ArrayList<TakeoverJournal.BlockEntry>();
                candidates.entrySet().stream().sorted(Comparator.comparingLong(e -> e.getKey().asLong())).forEach(e ->
                        entries.add(new TakeoverJournal.BlockEntry(e.getKey().asLong(), NbtUtils.writeBlockState(e.getValue()))));
                var journal = new TakeoverJournal(world.dimension, entries);
                var result = TakeoverTransaction.begin(files, journal,
                        TakeoverBackupStore.warningText(startup.worldRoot(), journal), world,
                        TakeoverTransaction.FailureInjector.NONE);
                if (result.removedBlocks() != 32) throw new IllegalStateException("takeover removed=" + result.removedBlocks());
            }
            region.setPaused(true);
            var fixture = new GpuCaptureFixture(region, manager, world, files, takeover, volume);
            fixture.verifyCaptured();
            return fixture;
        } catch (IOException | RuntimeException | Error failure) {
            if (files.hasJournal()) try {
                TakeoverTransaction.recover(files, world, TakeoverTransaction.FailureInjector.NONE);
            } catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            manager.remove(region.id());
            throw failure;
        }
    }

    FluidRegion region() { return region; }
    /** Solver cell-volume units; multiply by 0.125 for cubic metres. */
    double sampledVolume() { return sampledVolume; }

    void verifyCaptured() {
        int expected = takeover ? 0 : 32;
        if (world.waterCount() != expected || files.hasJournal() != takeover || files.hasWarning() != takeover)
            throw new IllegalStateException("capture world/journal mismatch: water=" + world.waterCount()
                    + " journal=" + files.hasJournal() + " warning=" + files.hasWarning());
    }

    void restore() throws IOException {
        if (takeover) {
            var recovered = TakeoverTransaction.recover(files, world, TakeoverTransaction.FailureInjector.NONE);
            if (!recovered.found() || recovered.restoredBlocks() != 32 || recovered.skippedBlocks() != 0)
                throw new IllegalStateException("takeover restoration failed: " + recovered);
        }
        if (!world.current.equals(world.original) || files.hasJournal() || files.hasWarning())
            throw new IllegalStateException("capture restoration did not recover original world and release journal");
        if (manager.remove(region.id()) != region) throw new IllegalStateException("capture region release failed");
    }

    private static final class World implements TakeoverTransaction.WorldAccess {
        private final String dimension;
        private final Map<BlockPos, BlockState> original = new HashMap<>();
        private final Map<BlockPos, BlockState> current = new HashMap<>();
        World(String dimension) {
            this.dimension = dimension;
            for (int z = 2; z < 6; z++) for (int y = 1; y < 3; y++) for (int x = 2; x < 6; x++)
                original.put(new BlockPos(x, y, z), Blocks.WATER.defaultBlockState());
            current.putAll(original);
        }
        BlockState blockAt(BlockPos pos) { return current.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        float waterHeight(BlockPos pos) {
            var state = blockAt(pos);
            if (!state.is(Blocks.WATER)) return 0.0F;
            return blockAt(pos.above()).is(Blocks.WATER) ? 1.0F : state.getFluidState().getOwnHeight();
        }
        int waterCount() { return current.size(); }
        public boolean isDimensionLoaded(String id) { return dimension.equals(id); }
        public boolean remove(String id, TakeoverJournal.BlockEntry block) throws IOException {
            BlockPos pos = BlockPos.of(block.packedPosition());
            BlockState expected = expected(id, block, pos);
            if (!blockAt(pos).equals(expected)) return false;
            current.remove(pos);
            return true;
        }
        public TakeoverTransaction.RestoreOutcome restore(String id, TakeoverJournal.BlockEntry block) throws IOException {
            BlockPos pos = BlockPos.of(block.packedPosition());
            BlockState expected = expected(id, block, pos), present = blockAt(pos);
            if (present.equals(expected)) return TakeoverTransaction.RestoreOutcome.ALREADY_RESTORED;
            if (!present.isAir() && !present.is(Blocks.WATER)) return TakeoverTransaction.RestoreOutcome.SKIPPED_OCCUPIED;
            current.put(pos, expected);
            return TakeoverTransaction.RestoreOutcome.RESTORED;
        }
        private BlockState expected(String id, TakeoverJournal.BlockEntry block, BlockPos pos) throws IOException {
            BlockState state = original.get(pos);
            if (!dimension.equals(id) || state == null || !NbtUtils.writeBlockState(state).equals(block.state()))
                throw new IOException("invalid takeover journal state at " + pos);
            return state;
        }
    }
}
