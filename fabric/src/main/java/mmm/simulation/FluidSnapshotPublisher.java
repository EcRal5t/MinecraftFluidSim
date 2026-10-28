package mmm.simulation;

import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.FluidMaterialRenderSnapshot;
import mmm.network.FluidChemicalSnapshotPayload;
import mmm.network.FluidGranularSnapshotPayload;
import mmm.network.FluidMaterialSnapshotPayload;
import mmm.network.FluidProtocol;
import mmm.network.FluidProtocolServer;
import mmm.network.FluidSnapshotPayload;
import mmm.network.FluidThermalSnapshotPayload;
import mmm.thermal.ThermalDisplaySnapshot;
import mmm.thermal.ThermalRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import mmm.granular.GranularRegistry;
import mmm.worldprofile.WorldCapability;
import mmm.worldprofile.WorldCapabilityProfile;

import java.util.Objects;

/** Builds and publishes read-only client projections without owning region lifecycle or world state. */
final class FluidSnapshotPublisher {
	private final boolean chemistry;
	private final boolean granular;
	private final boolean thermal;
	private final ChemicalRegistry chemicalRegistry;
	private final ThermalRegistry thermalRegistry;

	/** Snapshot capabilities follow the committed world capability profile, not a per-world flag set. */
	FluidSnapshotPublisher(
			WorldCapabilityProfile profile, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, ThermalRegistry thermalRegistry
	) {
		Objects.requireNonNull(profile, "profile");
		boolean chemistry = profile.hasCapability(WorldCapability.CHEMISTRY);
		boolean granular = profile.hasCapability(WorldCapability.GRANULAR);
		boolean thermal = profile.hasCapability(WorldCapability.THERMAL);
		if (chemistry && chemicalRegistry == null || granular && granularRegistry == null
				|| thermal && thermalRegistry == null) {
			throw new IllegalArgumentException("Snapshot capabilities are inconsistent");
		}
		this.chemistry = chemistry;
		this.granular = granular;
		this.thermal = thermal;
		this.chemicalRegistry = chemicalRegistry;
		this.thermalRegistry = thermalRegistry;
	}

	void sendTo(ServerPlayer player, FluidRegion region) {
		Objects.requireNonNull(player, "player");
		if (region == null || !region.solverLoaded()
				|| !player.level().dimension().equals(region.dimension())) return;
		createDispatch(region).sendTo(player);
	}

	void sendToLevel(ServerLevel level, FluidRegion region) {
		Objects.requireNonNull(level, "level");
		if (region == null || !region.solverLoaded() || !level.dimension().equals(region.dimension())) return;
		region.publishCommittedObservation();
		if (level.players().isEmpty()) return;
		SnapshotDispatch dispatch = createDispatch(region);
		for (ServerPlayer player : level.players()) dispatch.sendTo(player);
	}

	void sendRemoval(MinecraftServer server, FluidRegion region) {
		sendRemoval(server, region, region.networkGeneration());
	}

	void sendRemoval(MinecraftServer server, FluidRegion region, long tombstoneNetworkGeneration) {
		Objects.requireNonNull(server, "server");
		Objects.requireNonNull(region, "region");
		long sequence = region.nextSnapshotSequence();
		FluidSnapshotPayload purePayload = FluidSnapshotPayload.removeRegion(
				region.dimension().identifier(), region.id(), tombstoneNetworkGeneration, sequence);
		FluidMaterialSnapshotPayload materialPayload = FluidMaterialSnapshotPayload.removeRegion(
				region.dimension().identifier(), region.id(), tombstoneNetworkGeneration, sequence);
		FluidChemicalSnapshotPayload chemicalPayload = FluidChemicalSnapshotPayload.removeRegion(materialPayload);
		FluidGranularSnapshotPayload granularPayload = FluidGranularSnapshotPayload.removeRegion(materialPayload);
		FluidThermalSnapshotPayload thermalPayload = FluidThermalSnapshotPayload.removeRegion(materialPayload);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			FluidProtocolServer.send(player, purePayload);
			FluidProtocolServer.send(player, materialPayload);
			FluidProtocolServer.send(player, chemicalPayload);
			FluidProtocolServer.send(player, granularPayload);
			FluidProtocolServer.send(player, thermalPayload);
		}
	}

	void clearAll(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			FluidProtocolServer.send(player, FluidSnapshotPayload.clearAll());
			FluidProtocolServer.send(player, FluidMaterialSnapshotPayload.clearAll());
			FluidProtocolServer.send(player, FluidChemicalSnapshotPayload.clearAll());
			FluidProtocolServer.send(player, FluidGranularSnapshotPayload.clearAll());
			FluidProtocolServer.send(player, FluidThermalSnapshotPayload.clearAll());
		}
	}

	SnapshotDispatch createDispatch(FluidRegion region) {
		var display = region.display();
		if (thermal) {
			FluidThermalSnapshotPayload payload = createThermalSnapshot(region, display);
			return player -> FluidProtocolServer.send(player, payload);
		}
		if (granular) {
			FluidGranularSnapshotPayload payload = createGranularSnapshot(region, display);
			return player -> FluidProtocolServer.send(player, payload);
		}
		if (chemistry) {
			FluidChemicalSnapshotPayload payload = createChemicalSnapshot(region, display);
			return player -> FluidProtocolServer.send(player, payload);
		}
		FluidMaterialSnapshotPayload payload = createMaterialSnapshot(region, display);
		return player -> FluidProtocolServer.send(player, payload);
	}

	private static FluidMaterialSnapshotPayload createMaterialSnapshot(FluidRegion region, mmm.fluid.ResidentFluidState.Display display) {
		return createMaterialBase(region, display.material());
	}

	private static FluidMaterialSnapshotPayload createMaterialBase(
			FluidRegion region, FluidMaterialRenderSnapshot snapshot
	) {
		return FluidMaterialSnapshotPayload.snapshot(
				region.dimension().identifier(), region.id(), region.networkGeneration(), region.nextSnapshotSequence(),
				region.origin(), snapshot, region.debugOverlays());
	}

	private static FluidChemicalSnapshotPayload createChemicalSnapshot(FluidRegion region, mmm.fluid.ResidentFluidState.Display display) {
		if (display.chemical() == null) throw new IllegalStateException("Chemical region is missing parcel state");
		return createChemicalLayer(region, createMaterialBase(region, display.material()), display);
	}

	private static FluidChemicalSnapshotPayload createChemicalLayer(
			FluidRegion region, FluidMaterialSnapshotPayload base, mmm.fluid.ResidentFluidState.Display display
	) {
		return FluidChemicalSnapshotPayload.snapshot(base,
				region.solverLayout().chemicalRegistryFingerprint(),
				display.chemical());
	}

	private FluidGranularSnapshotPayload createGranularSnapshot(FluidRegion region, mmm.fluid.ResidentFluidState.Display display) {
		if (display.granular() == null) throw new IllegalStateException("Granular region is missing parcel state");
		return createGranularLayer(region, createMaterialBase(region, display.material()), display);
	}

	private FluidGranularSnapshotPayload createGranularLayer(
			FluidRegion region, FluidMaterialSnapshotPayload base, mmm.fluid.ResidentFluidState.Display display
	) {
		return FluidGranularSnapshotPayload.snapshot(base, display.granular(),
				chemistry ? chemicalRegistry.fingerprint() : null,
				chemistry ? display.chemical() : null);
	}

	private FluidThermalSnapshotPayload createThermalSnapshot(FluidRegion region, mmm.fluid.ResidentFluidState.Display display) {
		if (display.thermal() == null) throw new IllegalStateException("Thermal region is missing energy state");
		FluidMaterialSnapshotPayload base = createMaterialBase(region, display.material());
		FluidChemicalSnapshotPayload chemicalLayer = chemistry && !granular ? createChemicalLayer(region, base, display) : null;
		FluidGranularSnapshotPayload granularLayer = granular ? createGranularLayer(region, base, display) : null;
		ThermalDisplaySnapshot heat = display.thermal();
		return new FluidThermalSnapshotPayload(FluidProtocol.Layout.THERMAL, base,
				thermalRegistry.fingerprint(), heat.cellTemperatureQ(), heat.coherentFraction(), heat.debugFlags(),
				heat.gasNodes().stream().map(node -> new FluidThermalSnapshotPayload.GasNodeDisplay(
						node.nodeId(), node.temperatureQ(), node.gaugePressureDecaPa())).toList(),
				chemicalLayer, granularLayer);
	}

	@FunctionalInterface
	interface SnapshotDispatch {
		void sendTo(ServerPlayer player);
	}
}
