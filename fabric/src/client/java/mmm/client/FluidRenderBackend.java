package mmm.client;

import mmm.MmmMod;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;

/** Owns the staged GPU buffer and the translucent render-pass submission. */
final class FluidRenderBackend implements AutoCloseable {
	private static final RenderPipeline SURFACE = surfacePipeline("fluid_surface");
	private static final RenderPipeline DIAGNOSTIC = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("mmm", "pipeline/fluid_diagnostic"))
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
			.withVertexShader("core/position_color").withFragmentShader("core/position_color")
			.withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
			.withDepthStencilState(DepthStencilState.DEFAULT).withCull(false).build();
	static { IrisFluidCompatibility.register(SURFACE, DIAGNOSTIC); }
	private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

	private final StagedVertexBuffer stagedBuffer = new StagedVertexBuffer(
			() -> "Matter, Motion & Mechanics material surface",
			RenderType.BIG_BUFFER_SIZE
	);
	private long buildNanos, uploadNanos, submitNanos, draws, indices;
	private long nextCostReport = System.nanoTime() + 10_000_000_000L;

	void render(boolean textured, FluidRenderCoordinates coordinates, Consumer<VertexConsumer> geometry) {
		render(textured ? SURFACE : DIAGNOSTIC, coordinates, geometry);
	}

	void renderShadow(FluidRenderCoordinates coordinates, Consumer<VertexConsumer> geometry) {
		render(SURFACE, coordinates, geometry);
	}

	void renderDebug(Consumer<VertexConsumer> geometry) {
		render(RenderPipelines.DEBUG_FILLED_BOX, null, geometry);
	}

	static void beginMaterial(VertexConsumer vertices, String materialId, int x, int y, int z) {
		IrisFluidCompatibility.beginMaterial(vertices, materialId, x, y, z);
	}

	static TextureAtlasSprite sprite(String materialId) {
		String texture = materialId.equals("mmm:water") ? "water_still"
				: materialId.equals("mmm:mercury") ? "iron_block" : "white_concrete";
		return Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS)
				.getSprite(Identifier.withDefaultNamespace("block/" + texture));
	}

	private static RenderPipeline surfacePipeline(String name) {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("mmm", "pipeline/" + name))
				.withBindGroupLayout(BindGroupLayouts.GLOBALS)
				.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
				.withBindGroupLayout(BindGroupLayouts.FOG)
				.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
				.withVertexShader("core/block").withFragmentShader("core/block")
				.withVertexBinding(0, DefaultVertexFormat.BLOCK)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withDepthStencilState(DepthStencilState.DEFAULT).withCull(false).build();
	}

	private void render(RenderPipeline pipeline, FluidRenderCoordinates coordinates, Consumer<VertexConsumer> geometry) {
		long buildStart = System.nanoTime();
		VertexFormat format = pipeline.getVertexFormatBinding(0);
		if (format == null) return;
		PrimitiveTopology primitive = pipeline.getPrimitiveTopology();
		VertexSorting sorting = RenderSystem.getProjectionType().vertexSorting();
		if (coordinates != null && sorting == VertexSorting.DISTANCE_TO_ORIGIN) {
			sorting = VertexSorting.byDistance(coordinates.cameraX(), coordinates.cameraY(), coordinates.cameraZ());
		}
		StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(
				format,
				primitive,
				primitive == PrimitiveTopology.QUADS ? sorting : null
		);

		VertexConsumer vertices = stagedBuffer.getVertexBuilder(draw);
		geometry.accept(vertices);
		long uploadStart = System.nanoTime();
		stagedBuffer.upload();
		long submitStart = System.nanoTime();
		StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(draw);
		if (info != null) execute(Minecraft.getInstance(), info, pipeline, coordinates);
		stagedBuffer.endFrame();
		long end = System.nanoTime();
		if (info != null) {
			buildNanos += uploadStart - buildStart;
			uploadNanos += submitStart - uploadStart;
			submitNanos += end - submitStart;
			draws++;
			indices += info.indexCount();
		}
		if (draws > 0 && end >= nextCostReport) {
			MmmMod.LOGGER.info("MMM presentation CPU per draw: build={} us, upload={} us, submit={} us, indices={}, draws={}; GPU time unmeasured",
					buildNanos / draws / 1000, uploadNanos / draws / 1000, submitNanos / draws / 1000, indices / draws, draws);
			buildNanos = uploadNanos = submitNanos = draws = indices = 0;
			nextCostReport = end + 10_000_000_000L;
		}
	}

	private static void execute(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline,
			FluidRenderCoordinates coordinates) {
		Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
		if (coordinates != null) modelView.translate(-coordinates.cameraX(), -coordinates.cameraY(), -coordinates.cameraZ());
		GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
				modelView, COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX
		);
		RenderTarget target = ChunkSectionLayerGroup.TRANSLUCENT.outputTarget();
		GpuTextureView color = target.getColorTextureView();
		if (color == null) return;
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> MmmMod.MOD_ID + " volume surface",
				color,
				Optional.empty(),
				target.getDepthTextureView(),
				OptionalDouble.empty()
		)) {
			pass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transforms);
			if (pipeline == SURFACE) {
				var atlas = client.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
				pass.bindTexture("Sampler0", atlas.getTextureView(), atlas.getSampler());
				pass.bindTexture("Sampler2", client.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			}
			pass.setVertexBuffer(0, info.vertexBuffer().slice());
			pass.setIndexBuffer(info.indexBuffer(), info.indexType());
			pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
		}
	}

	@Override
	public void close() {
		stagedBuffer.close();
	}
}
