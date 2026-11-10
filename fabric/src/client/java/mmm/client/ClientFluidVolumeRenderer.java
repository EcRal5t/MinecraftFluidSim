package mmm.client;

import mmm.network.FluidChemicalSnapshotPayload;
import mmm.network.FluidGranularSnapshotPayload;
import mmm.network.FluidMaterialSnapshotPayload;
import mmm.network.FluidProtocol;
import mmm.network.FluidThermalSnapshotPayload;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;

/** Coordinates accepted snapshot state, pure geometry builders and the GPU render backend. */
public final class ClientFluidVolumeRenderer implements AutoCloseable {
    private static ClientFluidVolumeRenderer current;
    public ClientFluidVolumeRenderer() { current = this; }
    public static ClientFluidVolumeRenderer current() { return current; }

    public String materialAt(double x, double y, double z) {
        for (var frame : snapshots.materialFrames()) {
            String material = materialAt(frame, x, y, z);
            if (material != null) return material;
        }
        for (var frame : snapshots.granularFrames()) {
            String material = materialAt(frame.material(), x, y, z);
            if (material != null) return material;
        }
        return null;
    }

    private static String materialAt(ClientRegionSnapshotStore.MaterialFrame frame, double x, double y, double z) {
        var origin = frame.payload().origin();
        int material = frame.snapshot().geometry().materialAt(x-origin.getX(), y-origin.getY(), z-origin.getZ());
        return material < 0 ? null : frame.payload().materials().get(material).id();
    }
	private final ClientRegionSnapshotStore snapshots = new ClientRegionSnapshotStore();
    private final ClientPipeSnapshotStore pipes=new ClientPipeSnapshotStore();
    private volatile java.util.List<mmm.network.PipeSnapshotPayload.Segment> extractedPipes=java.util.List.of();
    public void accept(mmm.network.PipeSnapshotPayload payload,FluidProtocol.Negotiation negotiation){pipes.accept(payload,negotiation);}
	private final FluidRenderBackend backend = new FluidRenderBackend();


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
        extractedPipes=pipes.forDimension(context.level().dimension().identifier());
	}

    public void render(LevelRenderContext context) {
        renderMaterials(context.levelState().cameraRenderState.pos, false);

    }

    public void renderShadow(net.minecraft.world.phys.Vec3 cameraPosition) {
        renderMaterials(cameraPosition, true);
    }

    private void renderMaterials(net.minecraft.world.phys.Vec3 camera, boolean shadow) {
        backend.render(snapshots.materialFrames(), snapshots.granularFrames(), extractedPipes,FluidRenderCoordinates.at(camera), shadow);
    }

    void invalidateResources() { backend.invalidate(); }
	public void clear() {
		snapshots.clear();pipes.clear();extractedPipes=java.util.List.of();
        backend.invalidate();
	}

	@Override
	public void close() {
		clear();
		backend.close();
	}
}




