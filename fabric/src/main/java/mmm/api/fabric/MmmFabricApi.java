package mmm.api.fabric;

import mmm.api.RegionAvailabilityReason;
import mmm.api.query.MmmQueryApi;
import mmm.api.event.MmmEventApi;
import mmm.api.query.FluidQueryResult;
import mmm.api.query.FluidQueryStatus;
import mmm.simulation.MmmRuntime;
import mmm.simulation.MmmRuntimeRegistry;
import net.minecraft.server.MinecraftServer;

/** Fabric discovery bridge; the returned contract itself is loader-neutral. */
public final class MmmFabricApi {
	private MmmFabricApi() { }
	public static mmm.api.transfer.MmmTransferApi transfers(MinecraftServer server) {
		if(server==null) throw new IllegalArgumentException("Minecraft server is missing");
		MmmRuntime runtime=MmmRuntimeRegistry.find(server);
		if(runtime!=null) return runtime.transfers();
		return new mmm.api.transfer.MmmTransferApi() {
			private mmm.api.transfer.TransferResult unavailable() {
				return new mmm.api.transfer.TransferResult(server.isSameThread()?mmm.api.transfer.TransferStatus.UNAVAILABLE:
						mmm.api.transfer.TransferStatus.WRONG_THREAD,null,null,-1,"Matter, Motion & Mechanics server service is not initialized");
			}
			public void registerActorProvider(String id,ActorProvider provider) { throw new IllegalStateException("Matter, Motion & Mechanics server service is not initialized"); }
			public mmm.api.transfer.TransferResult registerActor(mmm.api.transfer.ActorRef actor) { return unavailable(); }
			public mmm.api.transfer.TransferResult plan(mmm.api.transfer.TransferRequest request) { return unavailable(); }
			public mmm.api.transfer.TransferResult commit(java.util.UUID plan) { return unavailable(); }
			public mmm.api.transfer.TransferResult receipt(java.util.UUID operation) { return unavailable(); }
		};
	}

	public static MmmQueryApi query(MinecraftServer server) {
		if (server == null) throw new IllegalArgumentException("Minecraft server is missing");
		MmmRuntime runtime = MmmRuntimeRegistry.find(server);
		if (runtime != null) return runtime.queryApi();
		return request -> server.isSameThread()
				? FluidQueryResult.unavailable(RegionAvailabilityReason.PROFILE_LOCKED,
						"Matter, Motion & Mechanics server service is not initialized")
				: FluidQueryResult.absent(FluidQueryStatus.WRONG_THREAD,
						"Matter, Motion & Mechanics server service is not initialized");
	}

	/** Registration does not initialize world state; callbacks are delivered on the server thread. */
	public static MmmEventApi events(MinecraftServer server) {
		if (server == null) throw new IllegalArgumentException("Minecraft server is missing");
		return MmmRuntimeRegistry.events(server);
	}
}
