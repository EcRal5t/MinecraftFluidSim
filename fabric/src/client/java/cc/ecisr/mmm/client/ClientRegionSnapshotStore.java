package cc.ecisr.mmm.client;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.fluid.FluidMaterialRenderSnapshot;
import cc.ecisr.mmm.fluid.FluidMaterialSurfaceMesher;
import cc.ecisr.mmm.network.FluidChemicalSnapshotLedger;
import cc.ecisr.mmm.network.FluidChemicalSnapshotPayload;
import cc.ecisr.mmm.network.FluidGranularSnapshotLedger;
import cc.ecisr.mmm.network.FluidGranularSnapshotPayload;
import cc.ecisr.mmm.network.FluidMaterialSnapshotLedger;
import cc.ecisr.mmm.network.FluidMaterialSnapshotPayload;
import cc.ecisr.mmm.network.FluidProtocol;
import cc.ecisr.mmm.network.FluidSnapshotLedger;
import cc.ecisr.mmm.network.FluidSnapshotPayload;
import cc.ecisr.mmm.network.FluidThermalSnapshotLedger;
import cc.ecisr.mmm.network.FluidThermalSnapshotPayload;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side snapshot state: the five per-layout ledgers decide accept/stale/tombstone, the
 * region maps hold the newest accepted frames, and {@link #extract} publishes immutable,
 * dimension-filtered frame lists for the render thread. Interpolation blends only the pure
 * liquid volume/markers columns between two compatible snapshots.
 */
final class ClientRegionSnapshotStore {
	private static final long SNAPSHOT_BLEND_NANOS = 200_000_000L;

	private final FluidSnapshotLedger ledger = new FluidSnapshotLedger();
	private final FluidMaterialSnapshotLedger materialLedger = new FluidMaterialSnapshotLedger();
	private final FluidChemicalSnapshotLedger chemicalLedger = new FluidChemicalSnapshotLedger();
	private final FluidGranularSnapshotLedger granularLedger = new FluidGranularSnapshotLedger();
	private final FluidThermalSnapshotLedger thermalLedger = new FluidThermalSnapshotLedger();
	private final Map<UUID, SnapshotTransition> latest = new LinkedHashMap<>();
	private final Map<UUID, MaterialFrame> latestMaterials = new LinkedHashMap<>();
	private final Map<UUID, GranularFrame> latestGranular = new LinkedHashMap<>();
	private final Map<UUID, FluidThermalSnapshotPayload> latestThermal = new LinkedHashMap<>();
	private volatile List<FluidSnapshotPayload> extracted = List.of();
	private volatile List<MaterialFrame> extractedMaterials = List.of();
	private volatile List<GranularFrame> extractedGranular = List.of();

	synchronized void accept(FluidSnapshotPayload payload, FluidProtocol.Layout negotiatedLayout) {
		FluidSnapshotLedger.Decision decision = ledger.evaluate(payload, negotiatedLayout);
		switch (decision) {
			case CLEAR_ALL -> latest.clear();
			case REMOVE_REGION -> latest.remove(payload.regionId());
			case ACCEPT_SNAPSHOT -> {
				SnapshotTransition old = latest.get(payload.regionId());
				FluidSnapshotPayload from = old == null ? payload : old.to();
				latest.put(payload.regionId(), new SnapshotTransition(from, payload, System.nanoTime()));
			}
			case REJECT_PROTOCOL -> MmmMod.LOGGER.error(
					"Discarded Matter, Motion & Mechanics snapshot layout {} after negotiating {}",
					payload.layout(), negotiatedLayout
			);
			case IGNORE_STALE -> { }
		}
	}

	List<FluidSnapshotPayload> fluidFrames() {
		return extracted;
	}

	List<MaterialFrame> materialFrames() {
		return extractedMaterials;
	}

	List<GranularFrame> granularFrames() {
		return extractedGranular;
	}

	synchronized void accept(FluidMaterialSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		FluidMaterialSnapshotLedger.Decision decision = materialLedger.evaluate(payload, negotiation);
		switch (decision) {
			case CLEAR_ALL -> latestMaterials.clear();
			case REMOVE_REGION -> latestMaterials.remove(payload.regionId());
			case ACCEPT_SNAPSHOT -> {
				FluidMaterialRenderSnapshot snapshot = payload.toRenderSnapshot();
				latestMaterials.put(payload.regionId(), new MaterialFrame(
						payload, snapshot, FluidMaterialSurfaceMesher.build(snapshot), null
				));
			}
			case REJECT_PROTOCOL -> MmmMod.LOGGER.error(
					"Discarded Matter, Motion & Mechanics material snapshot layout {} after negotiation", payload.layout());
			case IGNORE_STALE -> { }
		}
	}

	synchronized void accept(FluidChemicalSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		FluidChemicalSnapshotLedger.Decision decision = chemicalLedger.evaluate(payload, negotiation);
		FluidMaterialSnapshotPayload material = payload.material();
		switch (decision) {
			case CLEAR_ALL -> latestMaterials.clear();
			case REMOVE_REGION -> latestMaterials.remove(material.regionId());
			case ACCEPT_SNAPSHOT -> {
				FluidMaterialRenderSnapshot snapshot = material.toRenderSnapshot();
				latestMaterials.put(material.regionId(), new MaterialFrame(
						material, snapshot, FluidMaterialSurfaceMesher.build(snapshot), new ChemicalFrame(
								payload, payload.topSpecies(), payload.topWeights(), payload.residualWeights(),
								payload.ph(), payload.indicatorFraction())));
			}
			case REJECT_PROTOCOL -> MmmMod.LOGGER.error(
					"Discarded Matter, Motion & Mechanics chemical snapshot layout {} after negotiation", payload.layout());
			case REJECT_REGISTRY -> MmmMod.LOGGER.error(
					"Discarded Matter, Motion & Mechanics chemical region {} with a foreign registry fingerprint", material.regionId());
			case IGNORE_STALE -> { }
		}
	}

	synchronized void accept(FluidGranularSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		FluidGranularSnapshotLedger.Decision decision = granularLedger.evaluate(payload, negotiation);
		FluidMaterialSnapshotPayload material = payload.material();
		switch (decision) {
			case CLEAR_ALL -> latestGranular.clear();
			case REMOVE_REGION -> latestGranular.remove(material.regionId());
			case ACCEPT_SNAPSHOT -> {
				FluidMaterialRenderSnapshot snapshot = material.toRenderSnapshot();
				FluidChemicalSnapshotPayload chemicalPayload = payload.toChemicalPayload();
				ChemicalFrame chemical = chemicalPayload == null ? null : new ChemicalFrame(chemicalPayload,
						chemicalPayload.topSpecies(), chemicalPayload.topWeights(), chemicalPayload.residualWeights(),
						chemicalPayload.ph(), chemicalPayload.indicatorFraction());
				MaterialFrame materialFrame = new MaterialFrame(material, snapshot,
						FluidMaterialSurfaceMesher.build(snapshot), chemical);
				latestGranular.put(material.regionId(), new GranularFrame(payload, materialFrame,
						payload.depositBulkFill(), payload.depositPorosity(), payload.depositTopMaterial()));
			}
			case REJECT_PROTOCOL -> MmmMod.LOGGER.error(
					"Discarded Matter, Motion & Mechanics granular snapshot layout {} after negotiation", payload.layout());
			case REJECT_REGISTRY -> MmmMod.LOGGER.error(
					"Discarded Matter, Motion & Mechanics granular region {} with a foreign registry fingerprint", material.regionId());
			case IGNORE_STALE -> { }
		}
	}

	synchronized void accept(FluidThermalSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		FluidThermalSnapshotLedger.Decision decision = thermalLedger.evaluate(payload, negotiation);
		FluidMaterialSnapshotPayload material = payload.material();
		switch (decision) {
			case CLEAR_ALL -> {
				latestMaterials.clear(); latestGranular.clear(); latestThermal.clear();
			}
			case REMOVE_REGION -> {
				latestMaterials.remove(material.regionId()); latestGranular.remove(material.regionId());
				latestThermal.remove(material.regionId());
			}
			case ACCEPT_SNAPSHOT -> {
				FluidMaterialRenderSnapshot snapshot = material.toRenderSnapshot();
				FluidGranularSnapshotPayload granular = payload.granularLayer();
				FluidChemicalSnapshotPayload chemicalPayload = granular == null
						? payload.chemicalLayer() : granular.toChemicalPayload();
				ChemicalFrame chemical = chemicalPayload == null ? null : new ChemicalFrame(chemicalPayload,
						chemicalPayload.topSpecies(), chemicalPayload.topWeights(), chemicalPayload.residualWeights(),
						chemicalPayload.ph(), chemicalPayload.indicatorFraction());
				MaterialFrame materialFrame = new MaterialFrame(material, snapshot,
						FluidMaterialSurfaceMesher.build(snapshot), chemical);
				if (granular == null) {
					latestMaterials.put(material.regionId(), materialFrame); latestGranular.remove(material.regionId());
				} else {
					latestGranular.put(material.regionId(), new GranularFrame(granular, materialFrame,
							granular.depositBulkFill(), granular.depositPorosity(), granular.depositTopMaterial()));
					latestMaterials.remove(material.regionId());
				}
				latestThermal.put(material.regionId(), payload);
			}
			case REJECT_PROTOCOL -> MmmMod.LOGGER.error(
					"Discarded Matter, Motion & Mechanics thermal snapshot layout {} after negotiation", payload.layout());
			case REJECT_REGISTRY -> MmmMod.LOGGER.error(
					"Discarded Matter, Motion & Mechanics thermal region {} with a foreign registry fingerprint", material.regionId());
			case IGNORE_STALE -> { }
		}
	}

	public void extract(LevelExtractionContext context) {
		List<SnapshotTransition> transitions;
		synchronized (this) {
			transitions = List.copyOf(latest.values());
		}
		long now = System.nanoTime();
		extracted = transitions.stream()
				.map(transition -> interpolate(transition, now))
				.filter(payload -> payload.dimension().equals(context.level().dimension().identifier()))
				.toList();
		synchronized (this) {
			extractedMaterials = latestMaterials.values().stream()
					.filter(frame -> frame.payload().dimension().equals(context.level().dimension().identifier()))
					.toList();
			extractedGranular = latestGranular.values().stream()
					.filter(frame -> frame.payload().material().dimension().equals(context.level().dimension().identifier()))
					.toList();
		}
	}

	private static FluidSnapshotPayload interpolate(SnapshotTransition transition, long now) {
		FluidSnapshotPayload from = transition.from();
		FluidSnapshotPayload to = transition.to();
		float blend = Math.min(1.0F, Math.max(0.0F, (float) (now - transition.startedAtNanos()) / SNAPSHOT_BLEND_NANOS));
		if (blend >= 1.0F || !compatible(from, to)) return to;
		byte[] volume = new byte[to.volume().length];
		byte[] markers = new byte[to.markers().length];
		for (int i = 0; i < volume.length; i++) {
			int first = Byte.toUnsignedInt(from.volume()[i]);
			int second = Byte.toUnsignedInt(to.volume()[i]);
			volume[i] = (byte) Math.round(first + (second - first) * blend);
			markers[i] = from.markers()[i] != 0 || to.markers()[i] != 0 ? (byte) 1 : (byte) 0;
		}
		return new FluidSnapshotPayload(
				to.layout(), to.kind(), to.dimension(), to.regionId(), to.generation(), to.sequence(),
				to.origin(), to.cellSize(), to.sizeX(), to.sizeY(), to.sizeZ(),
				volume, markers, to.solids(), to.debugOverlays(), to.extensions()
		);
	}

	private static boolean compatible(FluidSnapshotPayload first, FluidSnapshotPayload second) {
		return first.regionId().equals(second.regionId())
				&& first.generation() == second.generation()
				&& first.layout() == second.layout()
				&& first.dimension().equals(second.dimension())
				&& first.origin().equals(second.origin())
				&& Float.compare(first.cellSize(), second.cellSize()) == 0
				&& first.sizeX() == second.sizeX()
				&& first.sizeY() == second.sizeY()
				&& first.sizeZ() == second.sizeZ()
				&& first.volume().length == second.volume().length
				&& first.markers().length == second.markers().length;
	}

	synchronized void clear() {
		latest.clear();
		ledger.clear();
		latestMaterials.clear();
		materialLedger.clear();
		chemicalLedger.clear();
		latestGranular.clear();
		thermalLedger.clear();
		latestThermal.clear();
		granularLedger.clear();
		extracted = List.of();
		extractedMaterials = List.of();
		extractedGranular = List.of();
	}

	private record SnapshotTransition(FluidSnapshotPayload from, FluidSnapshotPayload to, long startedAtNanos) { }
	record MaterialFrame(
			FluidMaterialSnapshotPayload payload,
			FluidMaterialRenderSnapshot snapshot,
			FluidMaterialSurfaceMesher.Mesh mesh,
			ChemicalFrame chemical
	) { }
	record ChemicalFrame(
			FluidChemicalSnapshotPayload payload, short[] topSpecies, byte[] topWeights,
			byte[] residualWeights, byte[] ph, byte[] indicatorFraction
	) { }
	record GranularFrame(
			FluidGranularSnapshotPayload payload, MaterialFrame material,
			byte[] bulkFill, byte[] porosity, short[] topMaterial
	) { }
}
