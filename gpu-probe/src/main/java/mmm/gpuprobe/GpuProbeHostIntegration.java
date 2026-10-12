package mmm.gpuprobe;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** Narrow bridge from the optional probe adapter to the authoritative host runtime and protocol. */
interface GpuProbeHostIntegration {
	boolean mainRegionExists(MinecraftServer server);
	void publishSnapshot(ServerLevel level, SnapshotFrame frame);
	void publishRemoval(MinecraftServer server, RemovalFrame frame);
	void republishMainRegions(MinecraftServer server);

	record SnapshotFrame(ResourceKey<Level> dimension, UUID regionId, long sequence, BlockPos origin,
	                     float cellSize, int nx, int ny, int nz, byte[] density, byte[] markers, byte[] solids) { }
	record RemovalFrame(ResourceKey<Level> dimension, UUID regionId, long sequence) { }
}
