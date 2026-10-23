package mmm.client;

import mmm.network.FluidSnapshotPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;


import static mmm.client.QuadGeometry.box;
import static mmm.client.QuadGeometry.quadX;
import static mmm.client.QuadGeometry.quadY;
import static mmm.client.QuadGeometry.quadZ;


/**
 * Debug solid, gas and bounds geometry.
 * Every method is a pure function of the current fluid frame.
 */
final class FluidSurfaceGeometry {

	private static final float SOLID_RED = 1.0F;
	private static final float SOLID_GREEN = 0.42F;
	private static final float SOLID_BLUE = 0.08F;
	private static final float SOLID_ALPHA = 0.20F;
	private static final float GAS_RED = 0.55F;
	private static final float GAS_GREEN = 0.55F;
	private static final float GAS_BLUE = 0.58F;
	private static final float GAS_ALPHA = 0.18F;
	private static final float BOUNDS_ALPHA = 0.82F;

	static void buildGeometry(LevelRenderContext context, VertexConsumer vertices, FluidSnapshotPayload state) {
		PoseStack poses = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		poses.pushPose();
		poses.translate(state.origin().getX() - camera.x, state.origin().getY() - camera.y, state.origin().getZ() - camera.z);
		Matrix4fc matrix = poses.last().pose();
		float h = state.cellSize();


		if (state.debugOverlays()) for (int z = 0; z < state.sizeZ(); z++) {
			for (int y = 0; y < state.sizeY(); y++) for (int x = 0; x < state.sizeX(); x++) {
				if (state.solids()[index(state, x, y, z)] != 0) renderSolidCell(matrix, vertices, state, x, y, z, h);
			}
		}
		byte[] gasNodes = gasNodeMask(state);
		if (state.debugOverlays() && gasNodes != null) for (int z = 0; z < state.sizeZ(); z++) {
			for (int y = 0; y < state.sizeY(); y++) for (int x = 0; x < state.sizeX(); x++) {
				if (gasNodes[index(state, x, y, z)] != 0) renderGasCell(matrix, vertices, state, gasNodes, x, y, z, h);
			}
		}

		if (state.debugOverlays()) renderBounds(matrix, vertices, state);
		poses.popPose();
	}

	/** Per-cell sealed gas node ids from the snapshot extension, or null when absent. */
	private static byte[] gasNodeMask(FluidSnapshotPayload state) {
		for (FluidSnapshotPayload.Extension extension : state.extensions()) {
			if (extension.type() == FluidSnapshotPayload.EXTENSION_GAS_NODES
					&& extension.data().length == state.volume().length) {
				return extension.data();
			}
		}
		return null;
	}

	private static void renderGasCell(
			Matrix4fc matrix, VertexConsumer vertices, FluidSnapshotPayload state, byte[] gasNodes,
			int x, int y, int z, float h
	) {
		float minX = x * h;
		float minY = y * h;
		float minZ = z * h;
		float maxX = minX + h;
		float maxY = minY + h;
		float maxZ = minZ + h;
		if (!isGas(gasNodes, state, x - 1, y, z)) quadX(matrix, vertices, minX, minY, maxY, minZ, maxZ, false, GAS_RED, GAS_GREEN, GAS_BLUE, GAS_ALPHA);
		if (!isGas(gasNodes, state, x + 1, y, z)) quadX(matrix, vertices, maxX, minY, maxY, minZ, maxZ, true, GAS_RED, GAS_GREEN, GAS_BLUE, GAS_ALPHA);
		if (!isGas(gasNodes, state, x, y - 1, z)) quadY(matrix, vertices, minY, minX, maxX, minZ, maxZ, false, GAS_RED, GAS_GREEN, GAS_BLUE, GAS_ALPHA);
		if (!isGas(gasNodes, state, x, y + 1, z)) quadY(matrix, vertices, maxY, minX, maxX, minZ, maxZ, true, GAS_RED, GAS_GREEN, GAS_BLUE, GAS_ALPHA);
		if (!isGas(gasNodes, state, x, y, z - 1)) quadZ(matrix, vertices, minZ, minX, maxX, minY, maxY, false, GAS_RED, GAS_GREEN, GAS_BLUE, GAS_ALPHA);
		if (!isGas(gasNodes, state, x, y, z + 1)) quadZ(matrix, vertices, maxZ, minX, maxX, minY, maxY, true, GAS_RED, GAS_GREEN, GAS_BLUE, GAS_ALPHA);
	}

	private static boolean isGas(byte[] gasNodes, FluidSnapshotPayload state, int x, int y, int z) {
		return inside(state, x, y, z) && gasNodes[index(state, x, y, z)] != 0;
	}

	/** Extracts the 0.5 density isosurface with marching tetrahedra. */
	private static void renderSolidCell(
			Matrix4fc matrix, VertexConsumer vertices, FluidSnapshotPayload state,
			int x, int y, int z, float h
	) {
		float minX = x * h;
		float minY = y * h;
		float minZ = z * h;
		float maxX = minX + h;
		float maxY = minY + h;
		float maxZ = minZ + h;
		if (!isSolid(state, x - 1, y, z)) quadX(matrix, vertices, minX, minY, maxY, minZ, maxZ, false, SOLID_RED, SOLID_GREEN, SOLID_BLUE, SOLID_ALPHA);
		if (!isSolid(state, x + 1, y, z)) quadX(matrix, vertices, maxX, minY, maxY, minZ, maxZ, true, SOLID_RED, SOLID_GREEN, SOLID_BLUE, SOLID_ALPHA);
		if (!isSolid(state, x, y - 1, z)) quadY(matrix, vertices, minY, minX, maxX, minZ, maxZ, false, SOLID_RED, SOLID_GREEN, SOLID_BLUE, SOLID_ALPHA);
		if (!isSolid(state, x, y + 1, z)) quadY(matrix, vertices, maxY, minX, maxX, minZ, maxZ, true, SOLID_RED, SOLID_GREEN, SOLID_BLUE, SOLID_ALPHA);
		if (!isSolid(state, x, y, z - 1)) quadZ(matrix, vertices, minZ, minX, maxX, minY, maxY, false, SOLID_RED, SOLID_GREEN, SOLID_BLUE, SOLID_ALPHA);
		if (!isSolid(state, x, y, z + 1)) quadZ(matrix, vertices, maxZ, minX, maxX, minY, maxY, true, SOLID_RED, SOLID_GREEN, SOLID_BLUE, SOLID_ALPHA);
	}

	private static void renderBounds(Matrix4fc matrix, VertexConsumer vertices, FluidSnapshotPayload state) {
		float minX = 0;
		float minY = 0;
		float minZ = 0;
		float maxX = minX + state.sizeX() * state.cellSize();
		float maxY = minY + state.sizeY() * state.cellSize();
		float maxZ = minZ + state.sizeZ() * state.cellSize();
		float thickness = 0.035F;
		float r = 0.95F, g = 0.85F, b = 0.18F;

		box(matrix, vertices, minX, minY, minZ, maxX, minY + thickness, minZ + thickness, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, minY, maxZ - thickness, maxX, minY + thickness, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, maxY - thickness, minZ, maxX, maxY, minZ + thickness, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, maxY - thickness, maxZ - thickness, maxX, maxY, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, minY, minZ, minX + thickness, maxY, minZ + thickness, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, maxX - thickness, minY, minZ, maxX, maxY, minZ + thickness, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, minY, maxZ - thickness, minX + thickness, maxY, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, maxX - thickness, minY, maxZ - thickness, maxX, maxY, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, minY, minZ, minX + thickness, minY + thickness, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, maxX - thickness, minY, minZ, maxX, minY + thickness, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, minX, maxY - thickness, minZ, minX + thickness, maxY, maxZ, r, g, b, BOUNDS_ALPHA);
		box(matrix, vertices, maxX - thickness, maxY - thickness, minZ, maxX, maxY, maxZ, r, g, b, BOUNDS_ALPHA);
	}

	private static boolean isSolid(FluidSnapshotPayload state, int x, int y, int z) {
		return inside(state, x, y, z) && state.solids()[index(state, x, y, z)] != 0;
	}

	private static boolean inside(FluidSnapshotPayload state, int x, int y, int z) {
		return x >= 0 && x < state.sizeX() && y >= 0 && y < state.sizeY() && z >= 0 && z < state.sizeZ();
	}

	private static int index(FluidSnapshotPayload state, int x, int y, int z) {
		return (z * state.sizeY() + y) * state.sizeX() + x;
	}

}

