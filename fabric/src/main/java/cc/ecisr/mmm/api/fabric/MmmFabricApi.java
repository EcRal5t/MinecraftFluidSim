package cc.ecisr.mmm.api.fabric;

import cc.ecisr.mmm.api.query.MmmQueryApiV1;
import cc.ecisr.mmm.api.query.FluidQueryResult;
import cc.ecisr.mmm.api.query.FluidQueryStatus;
import cc.ecisr.mmm.simulation.FluidSimulationService;
import net.minecraft.server.MinecraftServer;

/** Fabric discovery bridge; the returned contract itself is loader-neutral. */
public final class MmmFabricApi {
	private MmmFabricApi() { }

	public static MmmQueryApiV1 queryV1(MinecraftServer server) {
		if (server == null) throw new IllegalArgumentException("Minecraft server is missing");
		FluidSimulationService service = FluidSimulationService.find(server);
		if (service != null) return service.queryApiV1(server);
		return request -> FluidQueryResult.absent(server.isSameThread()
				? FluidQueryStatus.REGION_UNAVAILABLE : FluidQueryStatus.WRONG_THREAD,
				"Matter, Motion & Mechanics server service is not initialized");
	}
}
