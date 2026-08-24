package com.example.network;

import com.example.simulation.FluidSimulationService;
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationPacketListenerImpl;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Configuration-phase handshake and negotiated play-session gate for Real Water payloads. */
public final class FluidProtocolServer {
	private static final int HANDSHAKE_TIMEOUT_TICKS = 200;
	private static final ConfigurationTask.Type TASK_TYPE = new ConfigurationTask.Type("realwater:protocol_v1");
	private static final Map<ServerConfigurationPacketListenerImpl, HandshakeTask> PENDING = new IdentityHashMap<>();
	private static final Map<MinecraftServer, Map<UUID, FluidProtocol.Negotiation>> SESSIONS = new WeakHashMap<>();

	private FluidProtocolServer() { }

	public static void register() {
		PayloadTypeRegistry.clientboundConfiguration().register(
				FluidProtocolOfferPayload.TYPE, FluidProtocolOfferPayload.CODEC
		);
		PayloadTypeRegistry.serverboundConfiguration().register(
				FluidProtocolResponsePayload.TYPE, FluidProtocolResponsePayload.CODEC
		);
		PayloadTypeRegistry.clientboundPlay().register(FluidSnapshotPayload.TYPE, FluidSnapshotPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(FluidMaterialSnapshotPayload.TYPE, FluidMaterialSnapshotPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(FluidChemicalSnapshotPayload.TYPE, FluidChemicalSnapshotPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(FluidGranularSnapshotPayload.TYPE, FluidGranularSnapshotPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(FluidThermalSnapshotPayload.TYPE, FluidThermalSnapshotPayload.CODEC);

		ServerConfigurationNetworking.registerGlobalReceiver(
				FluidProtocolResponsePayload.TYPE, FluidProtocolServer::receiveResponse
		);
		ServerConfigurationConnectionEvents.CONFIGURE.register((listener, server) -> {
			removeSession(server, listener.getOwner().id());
			((FabricServerConfigurationPacketListenerImpl) listener).addTask(new HandshakeTask(listener, server));
		});
		ServerConfigurationConnectionEvents.DISCONNECT.register((listener, server) -> {
			synchronized (PENDING) {
				PENDING.remove(listener);
			}
			removeSession(server, listener.getOwner().id());
		});
		ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
			if (!isNegotiated(server, listener.player.getUUID())) {
				listener.disconnect(Component.literal("Real Water protocol negotiation was not completed"));
				return;
			}
			FluidSimulationService.get(server).sendSnapshots(listener.player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) ->
				removeSession(server, listener.player.getUUID())
		);
	}

	public static boolean send(ServerPlayer player, FluidSnapshotPayload payload) {
		if (!isNegotiated(player.level().getServer(), player.getUUID(), FluidProtocol.CURRENT_VERSION)
				|| !ServerPlayNetworking.canSend(player, FluidSnapshotPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	public static boolean send(ServerPlayer player, FluidMaterialSnapshotPayload payload) {
		if (!isNegotiated(player.level().getServer(), player.getUUID(), FluidProtocol.MATERIAL_SNAPSHOT_VERSION)
				|| !ServerPlayNetworking.canSend(player, FluidMaterialSnapshotPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	public static boolean send(ServerPlayer player, FluidChemicalSnapshotPayload payload) {
		if (!isNegotiated(player.level().getServer(), player.getUUID(), FluidProtocol.CHEMICAL_SNAPSHOT_VERSION)
				|| !ServerPlayNetworking.canSend(player, FluidChemicalSnapshotPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	public static boolean send(ServerPlayer player, FluidGranularSnapshotPayload payload) {
		if (!isNegotiated(player.level().getServer(), player.getUUID(), FluidProtocol.GRANULAR_SNAPSHOT_VERSION)
				|| !ServerPlayNetworking.canSend(player, FluidGranularSnapshotPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	public static boolean send(ServerPlayer player, FluidThermalSnapshotPayload payload) {
		if (!isNegotiated(player.level().getServer(), player.getUUID(), FluidProtocol.THERMAL_SNAPSHOT_VERSION)
				|| !ServerPlayNetworking.canSend(player, FluidThermalSnapshotPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	public static void clearServer(MinecraftServer server) {
		synchronized (SESSIONS) {
			SESSIONS.remove(server);
		}
		synchronized (PENDING) {
			PENDING.entrySet().removeIf(entry -> entry.getValue().server == server);
		}
	}

	private static void receiveResponse(
			FluidProtocolResponsePayload response,
			ServerConfigurationNetworking.Context context
	) {
		ServerConfigurationPacketListenerImpl listener = context.packetListener();
		HandshakeTask task;
		synchronized (PENDING) {
			task = PENDING.get(listener);
		}
		if (task == null) {
			listener.disconnect(Component.literal("Unexpected Real Water protocol response"));
			return;
		}
		FluidProtocol.Negotiation negotiation = FluidProtocol.validateResponse(task.offer.offer(), response);
		if (!negotiation.accepted()) {
			listener.disconnect(Component.literal("Real Water protocol incompatible: " + negotiation.failure()));
			return;
		}
		synchronized (SESSIONS) {
			SESSIONS.computeIfAbsent(context.server(), ignored -> new HashMap<>())
					.put(listener.getOwner().id(), negotiation);
		}
		synchronized (PENDING) {
			PENDING.remove(listener);
		}
		((FabricServerConfigurationPacketListenerImpl) listener).completeTask(TASK_TYPE);
	}

	private static boolean isNegotiated(MinecraftServer server, UUID playerId) {
		return negotiatedSession(server, playerId) != null;
	}

	private static boolean isNegotiated(MinecraftServer server, UUID playerId, int version) {
		FluidProtocol.Negotiation negotiation = negotiatedSession(server, playerId);
		return negotiation != null && negotiation.selectedVersion() == version;
	}

	private static FluidProtocol.Negotiation negotiatedSession(MinecraftServer server, UUID playerId) {
		synchronized (SESSIONS) {
			Map<UUID, FluidProtocol.Negotiation> serverSessions = SESSIONS.get(server);
			FluidProtocol.Negotiation negotiation = serverSessions == null ? null : serverSessions.get(playerId);
			if (negotiation == null || !negotiation.accepted()
					|| (negotiation.capabilities() & FluidProtocol.REQUIRED_CAPABILITIES) != FluidProtocol.REQUIRED_CAPABILITIES) return null;
			if (negotiation.selectedVersion() == FluidProtocol.CURRENT_VERSION) return negotiation;
			if (negotiation.selectedVersion() == FluidProtocol.MATERIAL_SNAPSHOT_VERSION) {
				return (negotiation.capabilities() & FluidProtocol.CAP_MATERIAL_SNAPSHOT) != 0L ? negotiation : null;
			}
			if (negotiation.selectedVersion() == FluidProtocol.CHEMICAL_SNAPSHOT_VERSION) {
				return (negotiation.capabilities() & (FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_CHEMICAL_SNAPSHOT))
						== (FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_CHEMICAL_SNAPSHOT) ? negotiation : null;
			}
			if (negotiation.selectedVersion() == FluidProtocol.GRANULAR_SNAPSHOT_VERSION) return
					(negotiation.capabilities() & (FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_GRANULAR_SNAPSHOT))
					== (FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_GRANULAR_SNAPSHOT) ? negotiation : null;
			return negotiation.selectedVersion() == FluidProtocol.THERMAL_SNAPSHOT_VERSION
					&& (negotiation.capabilities() & (FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_THERMAL_SNAPSHOT))
					== (FluidProtocol.CAP_MATERIAL_SNAPSHOT | FluidProtocol.CAP_THERMAL_SNAPSHOT) ? negotiation : null;
		}
	}

	private static void removeSession(MinecraftServer server, UUID playerId) {
		synchronized (SESSIONS) {
			Map<UUID, FluidProtocol.Negotiation> serverSessions = SESSIONS.get(server);
			if (serverSessions == null) return;
			serverSessions.remove(playerId);
			if (serverSessions.isEmpty()) SESSIONS.remove(server);
		}
	}

	private static final class HandshakeTask implements ConfigurationTask {
		private final ServerConfigurationPacketListenerImpl listener;
		private final MinecraftServer server;
		private final FluidProtocolOfferPayload offer;
		private int ticks;

		private HandshakeTask(ServerConfigurationPacketListenerImpl listener, MinecraftServer server) {
			this.listener = listener;
			this.server = server;
			FluidSimulationService service = FluidSimulationService.get(server);
			this.offer = new FluidProtocolOfferPayload(FluidProtocol.serverOffer(
					service.materialFeatureEnabled(), service.chemistryFeatureEnabled(),
					service.granularFeatureEnabled()));
		}

		@Override
		public void start(Consumer<Packet<?>> sender) {
			if (!ServerConfigurationNetworking.canSend(listener, FluidProtocolOfferPayload.TYPE)) {
				listener.disconnect(Component.literal(
						"This server requires the Real Water client Mod and protocol " + offer.offer().maximumVersion()
				));
				return;
			}
			synchronized (PENDING) {
				PENDING.put(listener, this);
			}
			sender.accept(ServerConfigurationNetworking.createClientboundPacket(offer));
		}

		@Override
		public boolean tick() {
			if (++ticks < HANDSHAKE_TIMEOUT_TICKS) return false;
			synchronized (PENDING) {
				PENDING.remove(listener);
			}
			removeSession(server, listener.getOwner().id());
			listener.disconnect(Component.literal("Timed out waiting for Real Water protocol negotiation"));
			return false;
		}

		@Override
		public Type type() {
			return TASK_TYPE;
		}
	}
}
