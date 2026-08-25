package cc.ecisr.mmm.api.fabric;

import cc.ecisr.mmm.api.query.MmmQueryApi;
import cc.ecisr.mmm.api.event.MmmEventApi;
import cc.ecisr.mmm.api.query.FluidQueryResult;
import cc.ecisr.mmm.api.query.FluidQueryStatus;
import cc.ecisr.mmm.simulation.FluidSimulationService;
import net.minecraft.server.MinecraftServer;

/** Fabric discovery bridge; the returned contract itself is loader-neutral. */
public final class MmmFabricApi {
	private MmmFabricApi() { }

	public static MmmQueryApi query(MinecraftServer server) {
		if (server == null) throw new IllegalArgumentException("Minecraft server is missing");
		FluidSimulationService service = FluidSimulationService.find(server);
		if (service != null) return service.queryApi(server);
		return request -> FluidQueryResult.absent(server.isSameThread()
				? FluidQueryStatus.REGION_UNAVAILABLE : FluidQueryStatus.WRONG_THREAD,
				"Matter, Motion & Mechanics server service is not initialized");
	}

	/** Registration does not initialize world state; callbacks are delivered on the server thread. */
	public static MmmEventApi events(MinecraftServer server) {
		if (server == null) throw new IllegalArgumentException("Minecraft server is missing");
		return FluidSimulationService.committedEvents(server);
	}
}
