package com.example.client;

import com.example.RealWaterMod;
import com.example.network.FluidSnapshotPayload;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Rebuilds a translucent, current-state surface from the latest volume
 * snapshot. Unlike particles, this contains no history and cannot drift.
 */
public final class ClientFluidVolumeRenderer implements AutoCloseable {
	private static final int SURFACE_THRESHOLD = 8;
	private static final float ISO_LEVEL = 0.50F;
	private static final float MARKER_INTERIOR_LEVEL = 0.65F;
	private static final int[][] CUBE_TETRAHEDRA = {
			{0, 5, 1, 6}, {0, 1, 2, 6}, {0, 2, 3, 6},
			{0, 3, 7, 6}, {0, 7, 4, 6}, {0, 4, 5, 6}
	};
	private static final long SNAPSHOT_BLEND_NANOS = 200_000_000L;
	private static final float WATER_SIDE_RED = 0.025F;
	private static final float WATER_SIDE_GREEN = 0.27F;
	private static final float WATER_SIDE_BLUE = 0.78F;
	private static final float WATER_SIDE_ALPHA = 0.56F;
	private static final float WATER_TOP_RED = 0.10F;
	private static final float WATER_TOP_GREEN = 0.48F;
	private static final float WATER_TOP_BLUE = 0.98F;
	private static final float WATER_TOP_ALPHA = 0.68F;
	private static final float SOLID_RED = 1.0F;
	private static final float SOLID_GREEN = 0.42F;
	private static final float SOLID_BLUE = 0.08F;
	private static final float SOLID_ALPHA = 0.20F;
	private static final float BOUNDS_ALPHA = 0.82F;

	private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

	private final StagedVertexBuffer stagedBuffer = new StagedVertexBuffer(
			() -> "Real Water debug surface",
			RenderType.BIG_BUFFER_SIZE
	);
	private volatile SnapshotTransition latest;
	private FluidSnapshotPayload extracted;

	public void accept(FluidSnapshotPayload payload) {
		if (payload.isClear()) {
			latest = null;
			return;
		}
		SnapshotTransition old = latest;
		FluidSnapshotPayload from = old == null ? payload : old.to();
		latest = new SnapshotTransition(from, payload, System.nanoTime());
	}

	public void extract(LevelExtractionContext context) {
		SnapshotTransition transition = latest;
		extracted = transition == null ? null : interpolate(transition, System.nanoTime());
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
				to.origin(), to.cellSize(), to.sizeX(), to.sizeY(), to.sizeZ(),
				volume, markers, to.solids(), to.debugOverlays()
		);
	}

	private static boolean compatible(FluidSnapshotPayload first, FluidSnapshotPayload second) {
		return first.origin().equals(second.origin())
				&& Float.compare(first.cellSize(), second.cellSize()) == 0
				&& first.sizeX() == second.sizeX()
				&& first.sizeY() == second.sizeY()
				&& first.sizeZ() == second.sizeZ()
				&& first.volume().length == second.volume().length
				&& first.markers().length == second.markers().length;
	}

	public void render(LevelRenderContext context) {
		FluidSnapshotPayload state = extracted;
		if (state == null || state.volume().length == 0) return;

		RenderPipeline pipeline = RenderPipelines.DEBUG_FILLED_BOX;
		VertexFormat format = pipeline.getVertexFormatBinding(0);
		if (format == null) return;
		PrimitiveTopology primitive = pipeline.getPrimitiveTopology();
		StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(
				format,
				primitive,
				primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null
		);

		buildGeometry(context, stagedBuffer.getVertexBuilder(draw), state);
		stagedBuffer.upload();
		StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(draw);
		if (info != null) execute(Minecraft.getInstance(), info, pipeline);
		stagedBuffer.endFrame();
	}

	private static void buildGeometry(LevelRenderContext context, VertexConsumer vertices, FluidSnapshotPayload state) {
		PoseStack poses = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		poses.pushPose();
		poses.translate(-camera.x, -camera.y, -camera.z);
		Matrix4fc matrix = poses.last().pose();
		float h = state.cellSize();

		renderFluidSurface(matrix, vertices, state, h);
		if (state.debugOverlays()) for (int z = 0; z < state.sizeZ(); z++) {
			for (int y = 0; y < state.sizeY(); y++) for (int x = 0; x < state.sizeX(); x++) {
				if (state.solids()[index(state, x, y, z)] != 0) renderSolidCell(matrix, vertices, state, x, y, z, h);
			}
		}

		if (state.debugOverlays()) renderBounds(matrix, vertices, state);
		poses.popPose();
	}

	/** Extracts the 0.5 density isosurface with marching tetrahedra. */
	private static void renderFluidSurface(Matrix4fc matrix, VertexConsumer vertices, FluidSnapshotPayload state, float h) {
		Vector3f[] positions = new Vector3f[8];
		for (int i = 0; i < positions.length; i++) positions[i] = new Vector3f();
		float[] values = new float[8];
		int nodeSizeX = state.sizeX() + 2, nodeSizeY = state.sizeY() + 2, nodeSizeZ = state.sizeZ() + 2;
		float[] scalarNodes = new float[nodeSizeX * nodeSizeY * nodeSizeZ];
		for (int nodeZ = 0; nodeZ < nodeSizeZ; nodeZ++) for (int nodeY = 0; nodeY < nodeSizeY; nodeY++) for (int nodeX = 0; nodeX < nodeSizeX; nodeX++) {
			scalarNodes[(nodeZ * nodeSizeY + nodeY) * nodeSizeX + nodeX] = scalar(state, nodeX - 1, nodeY - 1, nodeZ - 1);
		}
		for (int z = -1; z < state.sizeZ(); z++) for (int y = -1; y < state.sizeY(); y++) for (int x = -1; x < state.sizeX(); x++) {
			setSurfaceNode(positions, values, 0, state, scalarNodes, nodeSizeX, nodeSizeY, x, y, z, h);
			setSurfaceNode(positions, values, 1, state, scalarNodes, nodeSizeX, nodeSizeY, x + 1, y, z, h);
			setSurfaceNode(positions, values, 2, state, scalarNodes, nodeSizeX, nodeSizeY, x + 1, y + 1, z, h);
			setSurfaceNode(positions, values, 3, state, scalarNodes, nodeSizeX, nodeSizeY, x, y + 1, z, h);
			setSurfaceNode(positions, values, 4, state, scalarNodes, nodeSizeX, nodeSizeY, x, y, z + 1, h);
			setSurfaceNode(positions, values, 5, state, scalarNodes, nodeSizeX, nodeSizeY, x + 1, y, z + 1, h);
			setSurfaceNode(positions, values, 6, state, scalarNodes, nodeSizeX, nodeSizeY, x + 1, y + 1, z + 1, h);
			setSurfaceNode(positions, values, 7, state, scalarNodes, nodeSizeX, nodeSizeY, x, y + 1, z + 1, h);
			boolean crosses = false;
			boolean first = values[0] >= ISO_LEVEL;
			for (int i = 1; i < values.length; i++) if ((values[i] >= ISO_LEVEL) != first) { crosses = true; break; }
			if (!crosses) continue;
			for (int[] tetrahedron : CUBE_TETRAHEDRA) renderTetrahedron(matrix, vertices, positions, values, tetrahedron);
		}
	}

	private static void setSurfaceNode(
			Vector3f[] positions, float[] values, int slot, FluidSnapshotPayload state,
			float[] scalarNodes, int nodeSizeX, int nodeSizeY,
			int x, int y, int z, float h
	) {
		positions[slot].set(
				state.origin().getX() + (x + 0.5F) * h,
				state.origin().getY() + (y + 0.5F) * h,
				state.origin().getZ() + (z + 0.5F) * h
		);
		values[slot] = scalarNodes[((z + 1) * nodeSizeY + (y + 1)) * nodeSizeX + (x + 1)];
	}

	private static float scalar(FluidSnapshotPayload state, int x, int y, int z) {
		if (!inside(state, x, y, z) || isSolid(state, x, y, z)) return 0.0F;
		float weightedDensity = 0.0F;
		float weightSum = 0.0F;
		for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
			int sampleX = x + dx, sampleY = y + dy, sampleZ = z + dz;
			if (!inside(state, sampleX, sampleY, sampleZ) || isSolid(state, sampleX, sampleY, sampleZ)) continue;
			float weight = kernelWeight(dx) * kernelWeight(dy) * kernelWeight(dz);
			int raw = Byte.toUnsignedInt(state.volume()[index(state, sampleX, sampleY, sampleZ)]);
			float density = raw < SURFACE_THRESHOLD ? 0.0F : raw / 255.0F;
			weightedDensity += density * weight;
			weightSum += weight;
		}
		float density = weightSum <= 1.0E-6F ? 0.0F : weightedDensity / weightSum;
		if (state.markers()[index(state, x, y, z)] != 0) density = Math.max(density, MARKER_INTERIOR_LEVEL);
		return density;
	}

	private static float kernelWeight(int offset) {
		return offset == 0 ? 2.0F : 1.0F;
	}

	private static void renderTetrahedron(
			Matrix4fc matrix, VertexConsumer vertices, Vector3f[] positions, float[] values, int[] tetrahedron
	) {
		int[] inside = new int[4], outside = new int[4];
		int insideCount = 0, outsideCount = 0;
		for (int vertex : tetrahedron) {
			if (values[vertex] >= ISO_LEVEL) inside[insideCount++] = vertex;
			else outside[outsideCount++] = vertex;
		}
		if (insideCount == 0 || insideCount == 4) return;
		if (insideCount == 1 || insideCount == 3) {
			int lone = insideCount == 1 ? inside[0] : outside[0];
			int[] others = insideCount == 1 ? outside : inside;
			emitSurfaceTriangle(matrix, vertices,
					isoPoint(positions, values, lone, others[0]),
					isoPoint(positions, values, lone, others[1]),
					isoPoint(positions, values, lone, others[2]));
		} else {
			Vector3f ac = isoPoint(positions, values, inside[0], outside[0]);
			Vector3f ad = isoPoint(positions, values, inside[0], outside[1]);
			Vector3f bc = isoPoint(positions, values, inside[1], outside[0]);
			Vector3f bd = isoPoint(positions, values, inside[1], outside[1]);
			emitSurfaceTriangle(matrix, vertices, ac, ad, bd);
			emitSurfaceTriangle(matrix, vertices, ac, bd, bc);
		}
	}

	private static Vector3f isoPoint(Vector3f[] positions, float[] values, int first, int second) {
		float denominator = values[second] - values[first];
		float t = Math.abs(denominator) < 1.0E-6F ? 0.5F : (ISO_LEVEL - values[first]) / denominator;
		return new Vector3f(positions[first]).lerp(positions[second], t);
	}

	private static void emitSurfaceTriangle(Matrix4fc matrix, VertexConsumer vertices, Vector3f a, Vector3f b, Vector3f c) {
		Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
		float vertical = normal.lengthSquared() < 1.0E-10F ? 0.0F : Math.abs(normal.y) / normal.length();
		float r = lerp(WATER_SIDE_RED, WATER_TOP_RED, vertical);
		float g = lerp(WATER_SIDE_GREEN, WATER_TOP_GREEN, vertical);
		float blue = lerp(WATER_SIDE_BLUE, WATER_TOP_BLUE, vertical);
		float alpha = lerp(WATER_SIDE_ALPHA, WATER_TOP_ALPHA, vertical);
		vertex(vertices, matrix, a.x, a.y, a.z, r, g, blue, alpha);
		vertex(vertices, matrix, b.x, b.y, b.z, r, g, blue, alpha);
		vertex(vertices, matrix, c.x, c.y, c.z, r, g, blue, alpha);
		vertex(vertices, matrix, c.x, c.y, c.z, r, g, blue, alpha);
	}

	private static float lerp(float first, float second, float t) {
		return first + (second - first) * t;
	}

	private static void renderFluidCell(
			Matrix4fc matrix, VertexConsumer vertices, FluidSnapshotPayload state,
			int x, int y, int z, float h, float fill
	) {
		float minX = state.origin().getX() + x * h;
		float minY = state.origin().getY() + y * h;
		float minZ = state.origin().getZ() + z * h;
		float maxX = minX + h;
		float maxY = minY + h * fill;
		float maxZ = minZ + h;

		float leftHeight = neighborFill(state, x - 1, y, z) * h;
		if (leftHeight < h * fill) quadX(matrix, vertices, minX, minY + leftHeight, maxY, minZ, maxZ, false, WATER_SIDE_RED, WATER_SIDE_GREEN, WATER_SIDE_BLUE, WATER_SIDE_ALPHA);
		float rightHeight = neighborFill(state, x + 1, y, z) * h;
		if (rightHeight < h * fill) quadX(matrix, vertices, maxX, minY + rightHeight, maxY, minZ, maxZ, true, WATER_SIDE_RED, WATER_SIDE_GREEN, WATER_SIDE_BLUE, WATER_SIDE_ALPHA);
		float backHeight = neighborFill(state, x, y, z - 1) * h;
		if (backHeight < h * fill) quadZ(matrix, vertices, minZ, minX, maxX, minY + backHeight, maxY, false, WATER_SIDE_RED, WATER_SIDE_GREEN, WATER_SIDE_BLUE, WATER_SIDE_ALPHA);
		float frontHeight = neighborFill(state, x, y, z + 1) * h;
		if (frontHeight < h * fill) quadZ(matrix, vertices, maxZ, minX, maxX, minY + frontHeight, maxY, true, WATER_SIDE_RED, WATER_SIDE_GREEN, WATER_SIDE_BLUE, WATER_SIDE_ALPHA);

		if (!hasVisibleFluid(state, x, y + 1, z)) {
			quadY(matrix, vertices, maxY, minX, maxX, minZ, maxZ, true, WATER_TOP_RED, WATER_TOP_GREEN, WATER_TOP_BLUE, WATER_TOP_ALPHA);
		}
		if (!hasVisibleFluid(state, x, y - 1, z)) {
			quadY(matrix, vertices, minY, minX, maxX, minZ, maxZ, false, WATER_SIDE_RED, WATER_SIDE_GREEN, WATER_SIDE_BLUE, WATER_SIDE_ALPHA);
		}
	}

	private static void renderSolidCell(
			Matrix4fc matrix, VertexConsumer vertices, FluidSnapshotPayload state,
			int x, int y, int z, float h
	) {
		float minX = state.origin().getX() + x * h;
		float minY = state.origin().getY() + y * h;
		float minZ = state.origin().getZ() + z * h;
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
		float minX = state.origin().getX();
		float minY = state.origin().getY();
		float minZ = state.origin().getZ();
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

	private static float neighborFill(FluidSnapshotPayload state, int x, int y, int z) {
		if (!inside(state, x, y, z) || isSolid(state, x, y, z)) return 0.0F;
		return Byte.toUnsignedInt(state.volume()[index(state, x, y, z)]) / 255.0F;
	}

	private static boolean hasVisibleFluid(FluidSnapshotPayload state, int x, int y, int z) {
		return inside(state, x, y, z)
				&& !isSolid(state, x, y, z)
				&& Byte.toUnsignedInt(state.volume()[index(state, x, y, z)]) >= SURFACE_THRESHOLD;
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

	private static void box(Matrix4fc m, VertexConsumer v, float minX, float minY, float minZ, float maxX, float maxY, float maxZ, float r, float g, float b, float a) {
		quadZ(m, v, maxZ, minX, maxX, minY, maxY, true, r, g, b, a);
		quadZ(m, v, minZ, minX, maxX, minY, maxY, false, r, g, b, a);
		quadX(m, v, minX, minY, maxY, minZ, maxZ, false, r, g, b, a);
		quadX(m, v, maxX, minY, maxY, minZ, maxZ, true, r, g, b, a);
		quadY(m, v, maxY, minX, maxX, minZ, maxZ, true, r, g, b, a);
		quadY(m, v, minY, minX, maxX, minZ, maxZ, false, r, g, b, a);
	}

	private static void quadX(Matrix4fc m, VertexConsumer v, float x, float minY, float maxY, float minZ, float maxZ, boolean positive, float r, float g, float b, float a) {
		if (positive) {
			vertex(v, m, x, minY, maxZ, r, g, b, a); vertex(v, m, x, minY, minZ, r, g, b, a); vertex(v, m, x, maxY, minZ, r, g, b, a); vertex(v, m, x, maxY, maxZ, r, g, b, a);
		} else {
			vertex(v, m, x, minY, minZ, r, g, b, a); vertex(v, m, x, minY, maxZ, r, g, b, a); vertex(v, m, x, maxY, maxZ, r, g, b, a); vertex(v, m, x, maxY, minZ, r, g, b, a);
		}
	}

	private static void quadY(Matrix4fc m, VertexConsumer v, float y, float minX, float maxX, float minZ, float maxZ, boolean positive, float r, float g, float b, float a) {
		if (positive) {
			vertex(v, m, minX, y, maxZ, r, g, b, a); vertex(v, m, maxX, y, maxZ, r, g, b, a); vertex(v, m, maxX, y, minZ, r, g, b, a); vertex(v, m, minX, y, minZ, r, g, b, a);
		} else {
			vertex(v, m, minX, y, minZ, r, g, b, a); vertex(v, m, maxX, y, minZ, r, g, b, a); vertex(v, m, maxX, y, maxZ, r, g, b, a); vertex(v, m, minX, y, maxZ, r, g, b, a);
		}
	}

	private static void quadZ(Matrix4fc m, VertexConsumer v, float z, float minX, float maxX, float minY, float maxY, boolean positive, float r, float g, float b, float a) {
		if (positive) {
			vertex(v, m, minX, minY, z, r, g, b, a); vertex(v, m, maxX, minY, z, r, g, b, a); vertex(v, m, maxX, maxY, z, r, g, b, a); vertex(v, m, minX, maxY, z, r, g, b, a);
		} else {
			vertex(v, m, maxX, minY, z, r, g, b, a); vertex(v, m, minX, minY, z, r, g, b, a); vertex(v, m, minX, maxY, z, r, g, b, a); vertex(v, m, maxX, maxY, z, r, g, b, a);
		}
	}

	private static void vertex(VertexConsumer consumer, Matrix4fc matrix, float x, float y, float z, float r, float g, float b, float a) {
		consumer.addVertex(matrix, x, y, z).setColor(r, g, b, a);
	}

	private static void execute(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
				RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX
		);
		RenderTarget target = client.gameRenderer.mainRenderTarget();
		GpuTextureView color = target.getColorTextureView();
		if (color == null) return;
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> RealWaterMod.MOD_ID + " volume surface",
				color,
				Optional.empty(),
				target.getDepthTextureView(),
				OptionalDouble.empty()
		)) {
			pass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			pass.setVertexBuffer(0, info.vertexBuffer().slice());
			pass.setIndexBuffer(info.indexBuffer(), info.indexType());
			pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
		}
	}

	@Override
	public void close() {
		stagedBuffer.close();
	}

	private record SnapshotTransition(FluidSnapshotPayload from, FluidSnapshotPayload to, long startedAtNanos) { }
}
