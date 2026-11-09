package mmm.client;

import mmm.MmmMod;
import mmm.fluid.FluidMaterialRenderSnapshot;
import mmm.fluid.FluidMaterialSurfaceMesher;
import mmm.network.FluidChemicalSnapshotLedger;
import mmm.network.FluidChemicalSnapshotPayload;
import mmm.network.FluidGranularSnapshotLedger;
import mmm.network.FluidGranularSnapshotPayload;
import mmm.network.FluidMaterialSnapshotLedger;
import mmm.network.FluidMaterialSnapshotPayload;
import mmm.network.FluidProtocol;
import mmm.network.FluidThermalSnapshotLedger;
import mmm.network.FluidThermalSnapshotPayload;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side snapshot state: the per-layout ledgers decide accept/stale/tombstone, the
 * region maps hold the newest accepted frames, and {@link #extract} publishes immutable,
 * dimension-filtered frame lists for the render thread.
 */
final class ClientRegionSnapshotStore {
	private final FluidMaterialSnapshotLedger materialLedger = new FluidMaterialSnapshotLedger();
	private final FluidChemicalSnapshotLedger chemicalLedger = new FluidChemicalSnapshotLedger();
	private final FluidGranularSnapshotLedger granularLedger = new FluidGranularSnapshotLedger();
	private final FluidThermalSnapshotLedger thermalLedger = new FluidThermalSnapshotLedger();
	private final Map<UUID, MaterialFrame> latestMaterials = new LinkedHashMap<>();
	private final Map<UUID, GranularFrame> latestGranular = new LinkedHashMap<>();
	private final Map<UUID, FluidThermalSnapshotPayload> latestThermal = new LinkedHashMap<>();
	private volatile List<MaterialFrame> extractedMaterials = List.of();
	private volatile List<GranularFrame> extractedGranular = List.of();

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
						payload, snapshot, surfaceMesh(payload, snapshot), null, null
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
						material, snapshot, surfaceMesh(material, snapshot), new ChemicalFrame(
								payload, payload.topSpecies(), payload.topWeights(), payload.residualWeights(),
								payload.ph()), null));
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
						chemicalPayload.ph());
				MaterialFrame materialFrame = new MaterialFrame(material, snapshot,
						surfaceMesh(material, snapshot), chemical, null);
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
						chemicalPayload.ph());
				MaterialFrame materialFrame = new MaterialFrame(material, snapshot,
						surfaceMesh(material, snapshot), chemical, payload.cellTemperatureQ());
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

	FluidMaterialSurfaceMesher.Mesh surfaceMesh(
			FluidMaterialSnapshotPayload payload, FluidMaterialRenderSnapshot snapshot
	) {
		MaterialFrame previous = latestMaterials.get(payload.regionId());
		GranularFrame granular = latestGranular.get(payload.regionId());
		if (previous == null && granular != null) previous = granular.material();
		return previous != null && payload.sameSurface(previous.payload())
				? previous.mesh() : FluidMaterialSurfaceMesher.build(snapshot);
	}

	public void extract(LevelExtractionContext context) {
		synchronized (this) {
			FluidDisplaySettings.View view = FluidDisplaySettings.INSTANCE.view();
			latestMaterials.replaceAll((id, frame) -> frame.recolor(view));
			latestGranular.replaceAll((id, frame) -> new GranularFrame(frame.payload(), frame.material().recolor(view),
					frame.bulkFill(), frame.porosity(), frame.topMaterial()));
			extractedMaterials = latestMaterials.values().stream()
					.filter(frame -> frame.payload().dimension().equals(context.level().dimension().identifier()))
					.toList();
			extractedGranular = latestGranular.values().stream()
					.filter(frame -> frame.payload().material().dimension().equals(context.level().dimension().identifier()))
					.toList();
		}
	}

	synchronized void clear() {
		latestMaterials.clear();
		materialLedger.clear();
		chemicalLedger.clear();
		latestGranular.clear();
		thermalLedger.clear();
		latestThermal.clear();
		granularLedger.clear();
		extractedMaterials = List.of();
		extractedGranular = List.of();
	}

	record MaterialFrame(
			FluidMaterialSnapshotPayload payload,
			FluidMaterialRenderSnapshot snapshot,
			FluidMaterialSurfaceMesher.Mesh mesh,
			ChemicalFrame chemical,
			short[] temperatures,
			FluidDisplaySettings.View view,
			List<MaterialSurfaceGeometry.ColoredTriangle> triangles
	) {
		MaterialFrame(FluidMaterialSnapshotPayload payload, FluidMaterialRenderSnapshot snapshot,
				FluidMaterialSurfaceMesher.Mesh mesh, ChemicalFrame chemical, short[] temperatures) {
			this(payload, snapshot, mesh, chemical, temperatures, FluidDisplaySettings.INSTANCE.view());
		}
		MaterialFrame(FluidMaterialSnapshotPayload payload, FluidMaterialRenderSnapshot snapshot,
				FluidMaterialSurfaceMesher.Mesh mesh, ChemicalFrame chemical, short[] temperatures,
				FluidDisplaySettings.View view) {
			this(payload, snapshot, mesh, chemical, temperatures, view,
					MaterialSurfaceGeometry.prepare(payload, snapshot, mesh, chemical, temperatures, view));
		}
		MaterialFrame recolor(FluidDisplaySettings.View next) {
			return view.equals(next) ? this : new MaterialFrame(payload, snapshot, mesh, chemical, temperatures, next);
		}
	}
	record ChemicalFrame(
			FluidChemicalSnapshotPayload payload, short[] topSpecies, byte[] topWeights,
			byte[] residualWeights, byte[] ph
	) { }
	record GranularFrame(
			FluidGranularSnapshotPayload payload, MaterialFrame material,
			byte[] bulkFill, byte[] porosity, short[] topMaterial
	) { }
}
