package mmm.gpuprobe;

import mmm.network.FluidProtocolServer;
import mmm.network.FluidSnapshotPayload;
import mmm.simulation.MmmRuntimeRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** The only optional-probe type allowed to depend on host runtime and network implementations. */
final class MmmGpuProbeHostIntegration implements GpuProbeHostIntegration {
	@Override
	public boolean mainRegionExists(MinecraftServer server) {
		return MmmRuntimeRegistry.require(server).regionObservation().exists();
	}

	@Override
	public void publishSnapshot(ServerLevel level, SnapshotFrame frame) {
		FluidSnapshotPayload payload = FluidSnapshotPayload.snapshot(
				frame.dimension().identifier(), frame.regionId(), 1L, frame.sequence(), frame.origin(), frame.cellSize(),
				frame.nx(), frame.ny(), frame.nz(), frame.density(), frame.markers(), frame.solids(), true);
		for (ServerPlayer player : level.players()) FluidProtocolServer.send(player, payload);
	}

	@Override
	public void publishRemoval(MinecraftServer server, RemovalFrame frame) {
		FluidSnapshotPayload payload = FluidSnapshotPayload.removeRegion(
				frame.dimension().identifier(), frame.regionId(), 1L, frame.sequence());
		for (ServerPlayer player : server.getPlayerList().getPlayers()) FluidProtocolServer.send(player, payload);
	}

	@Override
	public void republishMainRegions(MinecraftServer server) {
		var main = MmmRuntimeRegistry.require(server).regionObservation();
		if (!main.exists()) return;
		for (ServerLevel level : server.getAllLevels()) main.sendSelectedTo(level);
	}
}
