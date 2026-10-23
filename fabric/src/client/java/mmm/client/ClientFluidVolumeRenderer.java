package mmm.client;

import mmm.network.FluidChemicalSnapshotPayload;
import mmm.network.FluidGranularSnapshotPayload;
import mmm.network.FluidMaterialSnapshotPayload;
import mmm.network.FluidProtocol;
import mmm.network.FluidSnapshotPayload;
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
        renderMaterials(context.levelState().cameraRenderState.pos, false);
        if (snapshots.fluidFrames().isEmpty()) return;
        backend.renderDebug(vertices -> {
            for (var state : snapshots.fluidFrames()) FluidSurfaceGeometry.buildGeometry(context, vertices, state);

        });
    }

    public void renderShadow(net.minecraft.world.phys.Vec3 cameraPosition) {
        renderMaterials(cameraPosition, true);
    }

    private void renderMaterials(net.minecraft.world.phys.Vec3 camera, boolean shadow) {
        if (snapshots.materialFrames().isEmpty() && snapshots.granularFrames().isEmpty()) return;
        {
            java.util.function.Consumer<com.mojang.blaze3d.vertex.VertexConsumer> geometry = vertices -> {
                for (var frame : snapshots.materialFrames()) MaterialSurfaceGeometry.buildGeometry(camera, vertices, frame, shadow);
                for (var frame : snapshots.granularFrames()) {
                    MaterialSurfaceGeometry.buildGeometry(camera, vertices, frame.material(), shadow);
                    GranularSurfaceGeometry.buildGeometry(camera, vertices, frame, shadow);
                }
            };
            if (shadow) backend.renderShadow(geometry);
            else backend.render(geometry);
        }
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




