package cc.ecisr.mmm.client;

import cc.ecisr.mmm.network.FluidGranularSnapshotPayload;
import cc.ecisr.mmm.network.FluidMaterialSnapshotPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

import static cc.ecisr.mmm.client.QuadGeometry.box;
import static cc.ecisr.mmm.client.QuadGeometry.quadX;
import static cc.ecisr.mmm.client.QuadGeometry.quadY;
import static cc.ecisr.mmm.client.QuadGeometry.quadZ;

/** Stateless active-parcel and deposit geometry. */
final class GranularSurfaceGeometry {
	private GranularSurfaceGeometry() { }

	static void buildGeometry(
			LevelRenderContext context, VertexConsumer vertices,
			ClientRegionSnapshotStore.GranularFrame frame
	) {
		FluidGranularSnapshotPayload payload = frame.payload();
		FluidMaterialSnapshotPayload state = payload.material();
		PoseStack poses = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		poses.pushPose();
		poses.translate(-camera.x, -camera.y, -camera.z);
		Matrix4fc matrix = poses.last().pose();
		float h = state.cellSize();
		for (int z = 0; z < state.sizeZ(); z++) for (int y = 0; y < state.sizeY(); y++) {
			for (int x = 0; x < state.sizeX(); x++) {
				int cell = granularIndex(state, x, y, z);
				float fill = Byte.toUnsignedInt(frame.bulkFill()[cell]) / 255.0F;
				if (fill <= 0.0F) continue;
				int material = frame.topMaterial()[cell];
				int color = payload.granularMaterials().get(material).argb();
				float porosity = Byte.toUnsignedInt(frame.porosity()[cell]) / 255.0F;
				renderDepositCell(matrix, vertices, state, frame.bulkFill(), x, y, z, h, fill, color,
						0.94F + 0.04F * (1.0F - porosity));
			}
		}
		for (var sample : payload.activeParcels()) {
			float centerX = state.origin().getX() + sample.xQ() / 65_535.0F * state.sizeX() * h;
			float centerY = state.origin().getY() + sample.yQ() / 65_535.0F * state.sizeY() * h;
			float centerZ = state.origin().getZ() + sample.zQ() / 65_535.0F * state.sizeZ() * h;
			float half = Math.max(h / 64.0F, sample.diameterQ() / 255.0F * h * 0.5F);
			int color = payload.granularMaterials().get(sample.materialIndex()).argb();
			box(matrix, vertices, centerX - half, centerY - half, centerZ - half,
					centerX + half, centerY + half, centerZ + half,
					((color >>> 16) & 0xff) / 255.0F, ((color >>> 8) & 0xff) / 255.0F,
					(color & 0xff) / 255.0F, 0.92F);
		}
		poses.popPose();
	}

	private static void renderDepositCell(
			Matrix4fc matrix, VertexConsumer vertices, FluidMaterialSnapshotPayload state, byte[] bulk,
			int x, int y, int z, float h, float fill, int argb, float alpha
	) {
		float red = ((argb >>> 16) & 0xff) / 255.0F;
		float green = ((argb >>> 8) & 0xff) / 255.0F;
		float blue = (argb & 0xff) / 255.0F;
		float minX = state.origin().getX() + x * h;
		float minY = state.origin().getY() + y * h;
		float minZ = state.origin().getZ() + z * h;
		float maxX = minX + h;
		float maxY = minY + h * fill;
		float maxZ = minZ + h;
		float left = granularFill(state, bulk, x - 1, y, z) * h;
		if (left < h * fill) quadX(matrix, vertices, minX, minY + left, maxY, minZ, maxZ,
				false, red * 0.82F, green * 0.82F, blue * 0.82F, alpha);
		float right = granularFill(state, bulk, x + 1, y, z) * h;
		if (right < h * fill) quadX(matrix, vertices, maxX, minY + right, maxY, minZ, maxZ,
				true, red * 0.82F, green * 0.82F, blue * 0.82F, alpha);
		float back = granularFill(state, bulk, x, y, z - 1) * h;
		if (back < h * fill) quadZ(matrix, vertices, minZ, minX, maxX, minY + back, maxY,
				false, red * 0.86F, green * 0.86F, blue * 0.86F, alpha);
		float front = granularFill(state, bulk, x, y, z + 1) * h;
		if (front < h * fill) quadZ(matrix, vertices, maxZ, minX, maxX, minY + front, maxY,
				true, red * 0.86F, green * 0.86F, blue * 0.86F, alpha);
		if (fill < 1.0F || granularFill(state, bulk, x, y + 1, z) == 0.0F) {
			quadY(matrix, vertices, maxY, minX, maxX, minZ, maxZ, true, red, green, blue, alpha);
		}
		if (granularFill(state, bulk, x, y - 1, z) == 0.0F) {
			quadY(matrix, vertices, minY, minX, maxX, minZ, maxZ, false,
					red * 0.72F, green * 0.72F, blue * 0.72F, alpha);
		}
	}

	private static float granularFill(
			FluidMaterialSnapshotPayload state, byte[] bulk, int x, int y, int z
	) {
		if (x < 0 || x >= state.sizeX() || y < 0 || y >= state.sizeY() || z < 0 || z >= state.sizeZ()) {
			return 0.0F;
		}
		return Byte.toUnsignedInt(bulk[granularIndex(state, x, y, z)]) / 255.0F;
	}

	private static int granularIndex(FluidMaterialSnapshotPayload state, int x, int y, int z) {
		return (z * state.sizeY() + y) * state.sizeX() + x;
	}
}
