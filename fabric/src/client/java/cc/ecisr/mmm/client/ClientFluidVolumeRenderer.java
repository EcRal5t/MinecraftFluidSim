package cc.ecisr.mmm.client;

import cc.ecisr.mmm.network.FluidChemicalSnapshotPayload;
import cc.ecisr.mmm.network.FluidGranularSnapshotPayload;
import cc.ecisr.mmm.network.FluidMaterialSnapshotPayload;
import cc.ecisr.mmm.network.FluidProtocol;
import cc.ecisr.mmm.network.FluidSnapshotPayload;
import cc.ecisr.mmm.network.FluidThermalSnapshotPayload;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/** Coordinates accepted snapshot state, pure geometry builders and the GPU render backend. */
public final class ClientFluidVolumeRenderer implements AutoCloseable {
	private final ClientRegionSnapshotStore snapshots = new ClientRegionSnapshotStore();
	private final FluidRenderBackend backend = new FluidRenderBackend();

	public void accept(FluidSnapshotPayload payload, FluidProtocol.Layout negotiatedLayout) {
		snapshots.accept(payload, negotiatedLayout);
	}

	public void accept(FluidMaterialSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		snapshots.accept(payload, negotiation);
	}

	public void accept(FluidChemicalSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		snapshots.accept(payload, negotiation);
	}

	public void accept(FluidGranularSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		snapshots.accept(payload, negotiation);
	}

	public void accept(FluidThermalSnapshotPayload payload, FluidProtocol.Negotiation negotiation) {
		snapshots.accept(payload, negotiation);
	}

	public void extract(LevelExtractionContext context) {
		snapshots.extract(context);
	}

	public void render(LevelRenderContext context) {
		var fluidFrames = snapshots.fluidFrames();
		var materialFrames = snapshots.materialFrames();
		var granularFrames = snapshots.granularFrames();
		if (fluidFrames.isEmpty() && materialFrames.isEmpty() && granularFrames.isEmpty()) return;
		backend.render(vertices -> {
			for (FluidSnapshotPayload state : fluidFrames) {
				FluidSurfaceGeometry.buildGeometry(context, vertices, state);
			}
			for (ClientRegionSnapshotStore.MaterialFrame state : materialFrames) {
				MaterialSurfaceGeometry.buildGeometry(context, vertices, state);
			}
			for (ClientRegionSnapshotStore.GranularFrame state : granularFrames) {
				MaterialSurfaceGeometry.buildGeometry(context, vertices, state.material());
				GranularSurfaceGeometry.buildGeometry(context, vertices, state);
			}
		});
	}

	public void clear() {
		snapshots.clear();
	}

	@Override
	public void close() {
		clear();
		backend.close();
	}
}
