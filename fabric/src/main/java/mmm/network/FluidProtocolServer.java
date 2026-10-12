package mmm.network;

import mmm.simulation.MinecraftControllerConsole;
import mmm.simulation.MmmRuntime;
import mmm.simulation.MmmRuntimeRegistry;
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

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Configuration-phase handshake and negotiated play-session gate for Matter, Motion & Mechanics payloads. */
public final class FluidProtocolServer {
	private static final int HANDSHAKE_TIMEOUT_TICKS = 200;
	private static final ConfigurationTask.Type TASK_TYPE = new ConfigurationTask.Type("mmm:protocol");
	private static final Map<ServerConfigurationPacketListenerImpl, HandshakeTask> PENDING = new IdentityHashMap<>();

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
		PayloadTypeRegistry.clientboundPlay().register(ControllerSummaryPayload.TYPE, ControllerSummaryPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ControllerActionPayload.TYPE, ControllerActionPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LifecycleOperationPreviewPayload.TYPE, LifecycleOperationPreviewPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LifecycleOperationRequestPayload.TYPE, LifecycleOperationRequestPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				ThermalReservoirSummaryPayload.TYPE, ThermalReservoirSummaryPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				ThermalReservoirSetpointPayload.TYPE, ThermalReservoirSetpointPayload.CODEC);

		ServerConfigurationNetworking.registerGlobalReceiver(
				FluidProtocolResponsePayload.TYPE, FluidProtocolServer::receiveResponse
		);
		ServerPlayNetworking.registerGlobalReceiver(ControllerActionPayload.TYPE, (payload, context) ->
				context.server().execute(() -> receiveControllerAction(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(LifecycleOperationRequestPayload.TYPE, (payload, context) ->
				context.server().execute(() -> receiveLifecycleOperation(payload, context.player())));
		ServerPlayNetworking.registerGlobalReceiver(ThermalReservoirSetpointPayload.TYPE, (payload, context) ->
				context.server().execute(() -> receiveThermalReservoirSetpoint(payload, context.player())));
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
				listener.disconnect(Component.literal("Matter, Motion & Mechanics protocol negotiation was not completed"));
				return;
			}
			MmmRuntime runtime = MmmRuntimeRegistry.require(server);
			runtime.containerRecovery().retryPlayer(listener.player.getUUID());
			runtime.regionObservation().sendAllTo(listener.player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
			MmmRuntime runtime = MmmRuntimeRegistry.find(server);
			if (runtime != null) runtime.controllerConsole().forgetInteractiveViewer(listener.player.getUUID());
			removeSession(server, listener.player.getUUID());
		});
	}

	public static boolean send(ServerPlayer player, FluidSnapshotPayload payload) {
		if (!isNegotiated(player.level().getServer(), player.getUUID(), FluidProtocol.Layout.PURE)
				|| !ServerPlayNetworking.canSend(player, FluidSnapshotPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	public static boolean send(ServerPlayer player, FluidMaterialSnapshotPayload payload) {
		if (!isNegotiated(player.level().getServer(), player.getUUID(), FluidProtocol.Layout.MATERIAL)
				|| !ServerPlayNetworking.canSend(player, FluidMaterialSnapshotPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	public static boolean send(ServerPlayer player, FluidChemicalSnapshotPayload payload) {
		if (!isNegotiated(player.level().getServer(), player.getUUID(), FluidProtocol.Layout.CHEMICAL)
				|| !ServerPlayNetworking.canSend(player, FluidChemicalSnapshotPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	public static boolean send(ServerPlayer player, FluidGranularSnapshotPayload payload) {
		if (!isNegotiated(player.level().getServer(), player.getUUID(), FluidProtocol.Layout.GRANULAR)
				|| !ServerPlayNetworking.canSend(player, FluidGranularSnapshotPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	public static boolean send(ServerPlayer player, FluidThermalSnapshotPayload payload) {
		if (!isNegotiated(player.level().getServer(), player.getUUID(), FluidProtocol.Layout.THERMAL)
				|| !ServerPlayNetworking.canSend(player, FluidThermalSnapshotPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	public static boolean send(ServerPlayer player, ControllerSummaryPayload payload) {
		if (!isNegotiated(player.level().getServer(), player.getUUID())
				|| !ServerPlayNetworking.canSend(player, ControllerSummaryPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	public static boolean send(ServerPlayer player, LifecycleOperationPreviewPayload payload) {
		if (!isNegotiated(player.level().getServer(), player.getUUID())
				|| !ServerPlayNetworking.canSend(player, LifecycleOperationPreviewPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	public static boolean send(ServerPlayer player, ThermalReservoirSummaryPayload payload) {
		if (!isNegotiated(player.level().getServer(), player.getUUID())
				|| !ServerPlayNetworking.canSend(player, ThermalReservoirSummaryPayload.TYPE)) return false;
		ServerPlayNetworking.send(player, payload);
		return true;
	}

	public static void clearServer(MinecraftServer server) {
		MmmRuntime runtime = MmmRuntimeRegistry.find(server);
		if (runtime != null) runtime.protocolSessions().clear();
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
			listener.disconnect(Component.literal("Unexpected Matter, Motion & Mechanics protocol response"));
			return;
		}
		FluidProtocol.Negotiation negotiation = FluidProtocol.validateResponse(task.offer.offer(), response);
		if (!negotiation.accepted()) {
			listener.disconnect(Component.literal("Matter, Motion & Mechanics protocol incompatible: " + negotiation.failure()));
			return;
		}
		MmmRuntimeRegistry.require(context.server()).protocolSessions()
				.accept(listener.getOwner().id(), negotiation);
		synchronized (PENDING) {
			PENDING.remove(listener);
		}
		((FabricServerConfigurationPacketListenerImpl) listener).completeTask(TASK_TYPE);
	}

	private static void receiveControllerAction(ControllerActionPayload payload, ServerPlayer player) {
		MinecraftControllerConsole console = MmmRuntimeRegistry.require(player.level().getServer()).controllerConsole();
		Component failure = console.applyControllerConsoleAction(player, payload.regionId(), payload.controllerPosition(),
				payload.summaryGeneration(), payload.action());
		if (failure != null) player.sendSystemMessage(Component.translatable(
				"message.mmm.controller.console_action_failed", failure), true);
		var summary = console.controllerSummary(player, payload.controllerPosition());
		if (summary != null) send(player, new ControllerSummaryPayload(summary));
	}

	private static void receiveLifecycleOperation(
			LifecycleOperationRequestPayload payload, ServerPlayer player
	) {
		MinecraftControllerConsole console = MmmRuntimeRegistry.require(player.level().getServer()).controllerConsole();
		if (payload.kind() == LifecycleOperationRequestPayload.Kind.PREVIEW) {
			var result = console.previewControllerLifecycleOperation(player, payload.regionId(),
					payload.controllerPosition(), payload.summaryGeneration(), payload.operation());
			if (result.failure() != null) {
				player.sendSystemMessage(Component.translatable(
						"message.mmm.controller.lifecycle_failed", result.failure()), true);
				var summary = console.controllerSummary(player, payload.controllerPosition());
				if (summary != null) send(player, new ControllerSummaryPayload(summary));
				return;
			}
			var refreshed = console.controllerSummary(player, payload.controllerPosition());
			if (refreshed == null || !send(player, new LifecycleOperationPreviewPayload(
					payload.controllerPosition(), result.preview(), refreshed))) {
				player.sendSystemMessage(Component.translatable(
						"message.mmm.controller.lifecycle_failed",
						Component.translatable("message.mmm.lifecycle.client_unsupported")), true);
			}
			return;
		}

		var result = console.confirmControllerLifecycleOperation(player, payload.regionId(),
				payload.controllerPosition(), payload.nonce(), payload.operation());
		if (result.committed()) player.level().playSound(null, payload.controllerPosition(),
				net.minecraft.sounds.SoundEvents.BEACON_ACTIVATE,
				net.minecraft.sounds.SoundSource.BLOCKS, 0.7F, 1.0F);
		player.sendSystemMessage(Component.translatable(result.committed()
				? "message.mmm.controller.lifecycle_committed" : "message.mmm.controller.lifecycle_failed",
				result.message()), true);
		var summary = console.controllerSummary(player, payload.controllerPosition());
		if (summary != null) send(player, new ControllerSummaryPayload(summary));
	}

	private static void receiveThermalReservoirSetpoint(
			ThermalReservoirSetpointPayload payload, ServerPlayer player
	) {
		MinecraftControllerConsole console = MmmRuntimeRegistry.require(player.level().getServer()).controllerConsole();
		String failure = console.applyThermalReservoirSetpoint(player, payload.position(),
				payload.summaryGeneration(), payload.temperatureK());
		if (failure != null) player.sendSystemMessage(Component.translatable(
				"message.mmm.thermal_reservoir.action_failed", failure), true);
		var summary = console.thermalReservoirSummary(player, payload.position());
		if (summary != null) send(player, new ThermalReservoirSummaryPayload(summary));
	}

	private static boolean isNegotiated(MinecraftServer server, UUID playerId) {
		MmmRuntime runtime = MmmRuntimeRegistry.find(server);
		return runtime != null && runtime.protocolSessions().isNegotiated(playerId);
	}

	private static boolean isNegotiated(MinecraftServer server, UUID playerId, FluidProtocol.Layout layout) {
		MmmRuntime runtime = MmmRuntimeRegistry.find(server);
		return runtime != null && runtime.protocolSessions().isNegotiated(playerId, layout);
	}

	private static void removeSession(MinecraftServer server, UUID playerId) {
		MmmRuntime runtime = MmmRuntimeRegistry.find(server);
		if (runtime != null) runtime.protocolSessions().remove(playerId);
	}

	private static final class HandshakeTask implements ConfigurationTask {
		private final ServerConfigurationPacketListenerImpl listener;
		private final MinecraftServer server;
		private final FluidProtocolOfferPayload offer;
		private int ticks;

		private HandshakeTask(ServerConfigurationPacketListenerImpl listener, MinecraftServer server) {
			this.listener = listener;
			this.server = server;
			this.offer = new FluidProtocolOfferPayload(
					FluidProtocol.serverOffer(MmmRuntimeRegistry.require(server).worldProfile()));
		}

		@Override
		public void start(Consumer<Packet<?>> sender) {
			if (!ServerConfigurationNetworking.canSend(listener, FluidProtocolOfferPayload.TYPE)) {
				listener.disconnect(Component.literal(
						"This server requires the Matter, Motion & Mechanics client Mod and "
								+ offer.offer().layout().name().toLowerCase() + " snapshot layout"
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
			listener.disconnect(Component.literal("Timed out waiting for Matter, Motion & Mechanics protocol negotiation"));
			return false;
		}

		@Override
		public Type type() {
			return TASK_TYPE;
		}
	}
}
