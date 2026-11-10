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
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;


import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;

/** Owns persistent surface batches and their translucent render-pass submissions. */
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
			.withDepthStencilState(DepthStencilState.DEFAULT).withCull(true).build();
	static { IrisFluidCompatibility.register(SURFACE, DIAGNOSTIC); }
	private static final Vector4f COLOR_MODULATOR = new Vector4f(1.0F, 1.0F, 1.0F, 1.0F);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private final FluidRenderBatch main = new FluidRenderBatch("main");
    private final FluidRenderBatch shadow = new FluidRenderBatch("shadow");
    private final FluidRenderTiming mainTiming = new FluidRenderTiming("main");
    private final FluidRenderTiming shadowTiming = new FluidRenderTiming("shadow");
    private long draws, shadowDraws, submitNanos;
    private long nextCostReport = System.nanoTime() + 10_000_000_000L;
    private volatile boolean invalidated;

    void invalidate() { invalidated = true; }

    void render(java.util.List<ClientRegionSnapshotStore.MaterialFrame> materials,
            java.util.List<ClientRegionSnapshotStore.GranularFrame> granular,java.util.List<mmm.network.PipeSnapshotPayload.Segment> pipes, FluidRenderCoordinates coordinates,
            boolean isShadow) {
        if (invalidated) { main.clear(); shadow.clear(); invalidated = false; }
        if (materials.isEmpty() && granular.isEmpty() && pipes.isEmpty()) { main.clear(); shadow.clear(); return; }
        var view = !materials.isEmpty() ? materials.getFirst().view() : !granular.isEmpty() ? granular.getFirst().material().view() : FluidDisplaySettings.INSTANCE.view();
        boolean textured = isShadow || view.mode() == FluidDisplaySettings.Mode.COMPOSITION;
        var pipeline = textured ? SURFACE : DIAGNOSTIC;
        var batch = isShadow ? shadow : main;
        var timing = isShadow ? shadowTiming : mainTiming;
        timing.poll();
        var resources = java.util.List.of(sprite("mmm:water"), sprite("mmm:mercury"), sprite("mmm:oil"),
                IrisFluidCompatibility.materialState());
        batch.prepare(materials, granular, pipes, coordinates, pipeline.getVertexFormatBinding(0), resources, textured, isShadow);
        if (batch.vertices != null) {
            long start = System.nanoTime();
            execute(Minecraft.getInstance(), batch, pipeline, coordinates, timing);
            submitNanos += System.nanoTime() - start;
            if (isShadow) shadowDraws++; else draws++;
        }
        if (System.nanoTime() >= nextCostReport && draws + shadowDraws > 0) {
            var mainGpu = mainTiming.drain(); var shadowGpu = shadowTiming.drain();
            MmmMod.LOGGER.info("MMM presentation: main={} shadow={} vertexBuilds={}/{} indexSorts={}/{} "
                    + "build={} us sort={} us upload={} us submit={} us vertexUpload={} B indexUpload={} B "
                    + "GPU main={} us max={} us samples={} shadow={} us max={} us samples={}",
                    draws, shadowDraws, main.builds, shadow.builds, main.sorts, shadow.sorts,
                    (main.buildNanos + shadow.buildNanos)/1000, (main.sortNanos + shadow.sortNanos)/1000,
                    (main.uploadNanos + shadow.uploadNanos)/1000, submitNanos/1000,
                    main.vertexBytes + shadow.vertexBytes, main.indexBytes + shadow.indexBytes,
                    mainGpu.averageNanos()/1000, mainGpu.maxNanos()/1000, mainGpu.samples(),
                    shadowGpu.averageNanos()/1000, shadowGpu.maxNanos()/1000, shadowGpu.samples());
            main.resetCosts(); shadow.resetCosts(); draws = shadowDraws = submitNanos = 0;
            nextCostReport = System.nanoTime() + 10_000_000_000L;
        }
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
				.withDepthStencilState(DepthStencilState.DEFAULT).withCull(true).build();
	}

	private static void execute(Minecraft client, FluidRenderBatch batch, RenderPipeline pipeline,
			FluidRenderCoordinates coordinates, FluidRenderTiming timing) {
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
			pass.setVertexBuffer(0, batch.vertices.slice());
			pass.setIndexBuffer(batch.indices, batch.indexType);
			int slot = timing.begin(pass);
			pass.drawIndexed(batch.indexCount, 1, 0, 0, 0);
			timing.end(pass, slot);
		}
	}

	@Override
	public void close() {
		main.close(); shadow.close(); mainTiming.close(); shadowTiming.close();
	}
}
