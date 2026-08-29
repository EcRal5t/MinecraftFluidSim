package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.fluid.FluidMaterialRenderSnapshot;
import cc.ecisr.mmm.network.FluidChemicalSnapshotPayload;
import cc.ecisr.mmm.network.FluidGranularSnapshotPayload;
import cc.ecisr.mmm.network.FluidMaterialSnapshotPayload;
import cc.ecisr.mmm.network.FluidProtocol;
import cc.ecisr.mmm.network.FluidProtocolServer;
import cc.ecisr.mmm.network.FluidSnapshotPayload;
import cc.ecisr.mmm.network.FluidThermalSnapshotPayload;
import cc.ecisr.mmm.thermal.ThermalDisplaySnapshot;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;

import java.util.Objects;

/** Builds and publishes read-only client projections without owning region lifecycle or world state. */
final class FluidSnapshotPublisher {
	private final boolean materials;
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
		boolean materials = profile.hasCapability(WorldCapability.MATERIALS);
		boolean chemistry = profile.hasCapability(WorldCapability.CHEMISTRY);
		boolean granular = profile.hasCapability(WorldCapability.GRANULAR);
		boolean thermal = profile.hasCapability(WorldCapability.THERMAL);
		if (chemistry && chemicalRegistry == null || granular && granularRegistry == null
				|| thermal && thermalRegistry == null) {
			throw new IllegalArgumentException("Snapshot capabilities are inconsistent");
		}
		this.materials = materials;
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

	private SnapshotDispatch createDispatch(FluidRegion region) {
		if (thermal) {
			FluidThermalSnapshotPayload payload = createThermalSnapshot(region);
			return player -> FluidProtocolServer.send(player, payload);
		}
		if (granular) {
			FluidGranularSnapshotPayload payload = createGranularSnapshot(region);
			return player -> FluidProtocolServer.send(player, payload);
		}
		if (chemistry) {
			FluidChemicalSnapshotPayload payload = createChemicalSnapshot(region);
			return player -> FluidProtocolServer.send(player, payload);
		}
		if (materials) {
			FluidMaterialSnapshotPayload payload = createMaterialSnapshot(region);
			return player -> FluidProtocolServer.send(player, payload);
		}
		FluidSnapshotPayload payload = createPureSnapshot(region);
		return player -> FluidProtocolServer.send(player, payload);
	}

	private static FluidSnapshotPayload createPureSnapshot(FluidRegion region) {
		FlipFluidDomain domain = region.domain();
		List<FluidSnapshotPayload.Extension> extensions = List.of();
		long cellCount = (long) domain.sizeX() * domain.sizeY() * domain.sizeZ();
		if (domain.gasPressureActive() && cellCount <= FluidSnapshotPayload.MAX_EXTENSION_BYTES) {
			extensions = List.of(new FluidSnapshotPayload.Extension(
					FluidSnapshotPayload.EXTENSION_GAS_NODES, domain.copyGasNodeMask()));
		}
		return FluidSnapshotPayload.snapshot(
				region.dimension().identifier(), region.id(), region.networkGeneration(), region.nextSnapshotSequence(),
				region.origin(), domain.config().cellSize(), domain.sizeX(), domain.sizeY(), domain.sizeZ(),
				domain.copyQuantizedVolume(), domain.copyMarkerMask(), domain.copyBinarySolidMask(), region.debugOverlays(),
				extensions);
	}

	private static FluidMaterialSnapshotPayload createMaterialSnapshot(FluidRegion region) {
		return createMaterialBase(region, region.domain().copyMaterialRenderSnapshot());
	}

	private static FluidMaterialSnapshotPayload createMaterialBase(
			FluidRegion region, FluidMaterialRenderSnapshot snapshot
	) {
		return FluidMaterialSnapshotPayload.snapshot(
				region.dimension().identifier(), region.id(), region.networkGeneration(), region.nextSnapshotSequence(),
				region.origin(), snapshot, region.debugOverlays());
	}

	private static FluidChemicalSnapshotPayload createChemicalSnapshot(FluidRegion region) {
		if (!region.domain().hasChemistryState()) throw new IllegalStateException("Chemical region is missing parcel state");
		return createChemicalLayer(region, createMaterialBase(region, region.domain().copyMaterialRenderSnapshot()));
	}

	private static FluidChemicalSnapshotPayload createChemicalLayer(
			FluidRegion region, FluidMaterialSnapshotPayload base
	) {
		return FluidChemicalSnapshotPayload.snapshot(base,
				region.domain().chemicalParcels().registry().fingerprint(),
				region.domain().copyChemicalDisplaySnapshot());
	}

	private FluidGranularSnapshotPayload createGranularSnapshot(FluidRegion region) {
		if (!region.domain().hasGranularState()) throw new IllegalStateException("Granular region is missing parcel state");
		return createGranularLayer(region, createMaterialBase(region, region.domain().copyMaterialRenderSnapshot()));
	}

	private FluidGranularSnapshotPayload createGranularLayer(
			FluidRegion region, FluidMaterialSnapshotPayload base
	) {
		return FluidGranularSnapshotPayload.snapshot(base, region.domain().copyGranularDisplaySnapshot(),
				chemistry ? chemicalRegistry.fingerprint() : null,
				chemistry ? region.domain().copyChemicalDisplaySnapshot() : null);
	}

	private FluidThermalSnapshotPayload createThermalSnapshot(FluidRegion region) {
		if (!region.domain().hasThermalState()) throw new IllegalStateException("Thermal region is missing energy state");
		FluidMaterialSnapshotPayload base = createMaterialBase(region, region.domain().copyMaterialRenderSnapshot());
		FluidChemicalSnapshotPayload chemicalLayer = chemistry && !granular ? createChemicalLayer(region, base) : null;
		FluidGranularSnapshotPayload granularLayer = granular ? createGranularLayer(region, base) : null;
		ThermalDisplaySnapshot display = region.domain().copyThermalDisplaySnapshot();
		return new FluidThermalSnapshotPayload(FluidProtocol.Layout.THERMAL, base,
				thermalRegistry.fingerprint(), display.cellTemperatureQ(), display.coherentFraction(), display.debugFlags(),
				display.gasNodes().stream().map(node -> new FluidThermalSnapshotPayload.GasNodeDisplay(
						node.nodeId(), node.temperatureQ(), node.gaugePressureDecaPa())).toList(),
				chemicalLayer, granularLayer);
	}

	@FunctionalInterface
	private interface SnapshotDispatch {
		void sendTo(ServerPlayer player);
	}
}
